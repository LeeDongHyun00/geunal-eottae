# One entry point for developers, Claude sessions and CI. CI calls these same targets, so a command that passes
# locally is the command CI runs. Each part adds its own targets in its own PR.
SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

.PHONY: help setup lint test lint-repo lint-core test-core build-android build-ios lint-api test-api image-api scan-api \
	lint-gateway test-gateway image-gateway scan-gateway lint-android test-android connected-android up down e2e \
	audit audit-core lint-terraform lock-terraform

# Providers download once into .tools (gitignored, cached in CI) instead of once per root.
export TF_PLUGIN_CACHE_DIR ?= $(CURDIR)/.tools/terraform-plugins
tf_roots := $(wildcard infra/terraform/envs/*)

help: ## List the targets
	@grep -hE '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{ printf "  %-14s %s\n", $$1, $$2 }'

setup: ## Install the toolchain (safe to rerun)
	./scripts/dev-setup.sh

lint: lint-repo lint-core lint-api lint-gateway lint-android lint-terraform ## Run every linter

test: test-core test-api test-gateway test-android ## Run every part's tests

# PR CI runs these gates only for the parts a change touches, so .github/workflows/audit.yml runs them on main weekly:
# an advisory published against code nobody is editing would otherwise go unnoticed (docs/security/dependencies.md).
audit: audit-core scan-api scan-gateway ## Dependency vulnerability gates of every part, as the weekly audit runs them

audit-core: ## Rust core: cargo-deny advisories only (lint-core runs the whole policy)
	"$$(scripts/tool.sh cargo-deny)" --manifest-path core/Cargo.toml --locked check advisories

lint-repo: ## Lint workflows (actionlint) and every tracked shell script (shellcheck)
	shellcheck="$$(scripts/tool.sh shellcheck)"; \
	"$$shellcheck" $$(git ls-files '*.sh'); \
	"$$(scripts/tool.sh actionlint)" -color -shellcheck "$$shellcheck" .github/workflows/*.yml

lint-core: ## Rust core: rustfmt, clippy and cargo-deny
	cd core && cargo fmt --all --check
	cd core && cargo clippy --locked --workspace --all-targets -- -D warnings
	"$$(scripts/tool.sh cargo-deny)" --manifest-path core/Cargo.toml --locked check

test-core: ## Rust core: unit and smoke tests
	cd core && cargo test --locked

build-android: ## Rust core: Android libraries and Kotlin bindings in core/out/android
	core/scripts/build-android.sh

build-ios: ## Rust core: XCFramework and Swift bindings in core/out/ios (macOS only)
	core/scripts/build-ios.sh

lint-api: ## API: ktlint
	cd server/api && ./gradlew ktlintCheck

test-api: ## API: tests against PostgreSQL and Redis in Testcontainers (needs Docker)
	scripts/ensure-docker.sh
	cd server/api && ./gradlew test

image-api: ## API: boot jar and the container image geunal/api:local
	scripts/ensure-docker.sh
	cd server/api && ./gradlew bootJar
	scripts/docker-build.sh -t geunal/api:local server/api

scan-api: image-api ## API: Trivy on the image; fails on HIGH or CRITICAL findings that have a fix
	"$$(scripts/tool.sh trivy)" image --quiet --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed geunal/api:local

lint-gateway: ## Gateway: locked deps, format, compiler warnings, credo, and the image's Elixir and OTP
	cd server/gateway && mix deps.get --check-locked && mix deps.unlock --check-unused
	cd server/gateway && mix format --check-formatted
	cd server/gateway && mix compile --warnings-as-errors
	cd server/gateway && mix credo --strict
	elixir="$$(awk '$$1 == "elixir" { sub(/-otp-.*/, "", $$2); print $$2 }' .tool-versions)"; \
	otp="$$(awk '$$1 == "erlang" { print $$2 }' .tool-versions)"; \
	grep -q "^FROM hexpm/elixir:$$elixir-erlang-$$otp-" server/gateway/Dockerfile || \
	  { echo "server/gateway/Dockerfile must build with Elixir $$elixir and OTP $$otp (.tool-versions)" >&2; exit 1; }

test-gateway: ## Gateway: tests against Redis; a throwaway container unless REDIS_URL is set
	cd server/gateway && mix deps.get --check-locked
	cd server/gateway && scripts/with-redis.sh mix test

image-gateway: ## Gateway: the release image geunal/gateway:local
	scripts/ensure-docker.sh
	scripts/docker-build.sh -t geunal/gateway:local server/gateway

# Trivy finds no Hex packages inside a release, so mix.lock is scanned on its own.
scan-gateway: image-gateway ## Gateway: Trivy on the image and mix.lock; fails on HIGH or CRITICAL findings that have a fix
	trivy="$$(scripts/tool.sh trivy)"; \
	"$$trivy" image --quiet --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed geunal/gateway:local; \
	"$$trivy" fs --quiet --scanners vuln --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed server/gateway/mix.lock

# The Android app consumes the core's libraries and generated Kotlin from core/out/android, so its targets build them
# first (docs/parts/android.md §6: cargo-ndk, then Gradle). The pinned SDK comes from scripts/tool.sh android-sdk.
lint-android: build-android ## Android: ktlint and Android Lint
	cd apps/android && ANDROID_HOME="$$(../../scripts/tool.sh android-sdk)" ./gradlew ktlintCheck lint

test-android: build-android ## Android: assemble (debug, R8 release, instrumented tests) and run the unit tests
	cd apps/android && ANDROID_HOME="$$(../../scripts/tool.sh android-sdk)" ./gradlew assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest

connected-android: build-android ## Android: instrumented tests on the emulator or device adb sees
	cd apps/android && ANDROID_HOME="$$(../../scripts/tool.sh android-sdk)" ./gradlew connectedDebugAndroidTest

# -lockfile=readonly makes a root without a committed lock file, or one missing this platform, fail here rather than
# silently pick a provider build CI never saw.
lint-terraform: ## Terraform: fmt, then init (no backend) and validate of every root in infra/terraform/envs
	mkdir -p "$$TF_PLUGIN_CACHE_DIR"
	terraform="$$(scripts/tool.sh terraform)"; \
	"$$terraform" fmt -check -diff -recursive infra/terraform; \
	for root in $(tf_roots); do \
	  "$$terraform" -chdir="$$root" init -backend=false -input=false -lockfile=readonly >/dev/null; \
	  "$$terraform" -chdir="$$root" validate; \
	done

lock-terraform: ## Terraform: rewrite each root's lock file with checksums for Linux and macOS (after a provider bump)
	mkdir -p "$$TF_PLUGIN_CACHE_DIR"
	terraform="$$(scripts/tool.sh terraform)"; \
	for root in $(tf_roots); do \
	  "$$terraform" -chdir="$$root" get >/dev/null; \
	  "$$terraform" -chdir="$$root" providers lock \
	    -platform=linux_amd64 -platform=linux_arm64 -platform=darwin_amd64 -platform=darwin_arm64; \
	done

up: image-api image-gateway ## Local stack in Docker: PostgreSQL, Redis, S3, API, gateway (infra/docker-compose.yml)
	docker compose -f infra/docker-compose.yml up --detach --wait --wait-timeout 180

down: ## Stop the local stack and drop its data
	docker compose -f infra/docker-compose.yml down --volumes

e2e: up ## Headless end-to-end tests against the local stack, which stays up afterwards (make down)
	cd core && cargo test --locked -p geunal-e2e

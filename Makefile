# One entry point for developers, Claude sessions and CI. CI calls these same targets, so a command that passes
# locally is the command CI runs. Each part adds its own targets in its own PR.
SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

.PHONY: help setup lint test lint-repo lint-core test-core build-android build-ios

help: ## List the targets
	@grep -hE '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{ printf "  %-14s %s\n", $$1, $$2 }'

setup: ## Install the toolchain (safe to rerun)
	./scripts/dev-setup.sh

lint: lint-repo lint-core ## Run every linter

test: test-core ## Run every part's tests

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

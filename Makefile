# One entry point for developers, Claude sessions and CI. CI calls these same targets, so a command that passes
# locally is the command CI runs. Each part adds its own targets in its own PR.
SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

.PHONY: help setup lint lint-repo

help: ## List the targets
	@grep -hE '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{ printf "  %-10s %s\n", $$1, $$2 }'

setup: ## Install the toolchain (safe to rerun)
	./scripts/dev-setup.sh

lint: lint-repo ## Run every linter

lint-repo: ## Lint workflows (actionlint) and shell scripts (shellcheck)
	shellcheck="$$(scripts/tool.sh shellcheck)"; \
	"$$shellcheck" scripts/*.sh scripts/ci/*.sh; \
	"$$(scripts/tool.sh actionlint)" -color -shellcheck "$$shellcheck" .github/workflows/*.yml

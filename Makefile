# Recipes use bash for `set -e` + pipe behavior.
SHELL := /bin/bash

GRADLE := ./gradlew

# --- LOCAL_SDK toggle ------------------------------------------------------
#
# Default build pulls the SDK from mvn.breez.technology. Setting LOCAL_SDK=1
# instead builds the Rust dylib + KMP bindings from a side-by-side spark-sdk
# checkout and publishes to mavenLocal (which `build.gradle.kts` lists first).
#
#   make setup                              # uses published artifact
#   make setup LOCAL_SDK=1                  # SDK_PATH defaults to ../spark-sdk
#   make setup LOCAL_SDK=1 SDK_PATH=/path
#
# When LOCAL_SDK=1 the Gradle build also sets `jna.library.path` to
# $(SDK_PATH)/target/release so JNA picks up the freshly built dylib.

SDK_PATH ?= ../spark-sdk
KMP_CLI_DIR := $(SDK_PATH)/crates/breez-sdk/bindings/examples/cli/langs/kotlin-multiplatform

# Export so the Gradle build (build.gradle.kts) sees it for jna.library.path.
export SDK_PATH

# When LOCAL_SDK=1, switch the SDK dep to the in-tree libraryVersion
# emitted by the local build (currently `0.1.0` in spark-sdk's
# gradle.properties). Default leaves SDK_VERSION unset → build.gradle.kts
# falls back to its baked-in published version from mvn.breez.technology.
ifeq ($(LOCAL_SDK),1)
SDK_VERSION ?= 0.1.0
export SDK_VERSION
endif

# Load .env if present, exporting every var. Without this, `make run` would
# inherit only the parent shell's env — and the typical workflow is
# `cp .env.example .env`, edit, `make run`. Sourced before recipes execute.
ifneq (,$(wildcard ./.env))
	include .env
	export
endif

.PHONY: help setup build run up postgres-up postgres-wait logs down clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

setup: ## One-time per branch. With LOCAL_SDK=1, also builds + publishes the local SDK to mavenLocal.
ifeq ($(LOCAL_SDK),1)
	@echo "LOCAL_SDK=1 — building SDK from $(SDK_PATH)"
	@test -d "$(SDK_PATH)" || (echo "SDK_PATH='$(SDK_PATH)' not a directory"; exit 2)
	cd $(KMP_CLI_DIR) && $(MAKE) setup
endif
	$(GRADLE) --version

build: ## Compile the app
	$(GRADLE) build

postgres-up: ## Start the Postgres container in the background (idempotent)
	docker compose up -d postgres
	@$(MAKE) postgres-wait

postgres-wait:
	@echo "Waiting for Postgres to accept connections…"
	@for i in $$(seq 1 60); do \
	  if docker compose exec -T postgres pg_isready -U postgres -d sdk_mu_demo >/dev/null 2>&1; then \
	    echo "Postgres ready."; exit 0; \
	  fi; sleep 1; \
	done; echo "Postgres did not become ready in time" >&2; exit 1

run: build ## Run the server in the foreground (assumes postgres-up)
	$(GRADLE) run --console=plain

up: postgres-up run ## docker compose up (postgres) + run the app in the foreground

logs: ## Tail postgres logs
	docker compose logs -f postgres

down: ## Stop and remove the postgres container (keeps the named volume)
	docker compose down

clean: ## Remove build artifacts
	$(GRADLE) clean

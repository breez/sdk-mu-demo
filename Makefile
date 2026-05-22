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

# Load .env if present, exporting every var. Without this, `make run` would
# inherit only the parent shell's env — and the typical workflow is
# `cp .env.example .env`, edit, `make run`. Sourced before recipes execute.
ifneq (,$(wildcard ./.env))
	include .env
	export
endif

.PHONY: help setup build run up mysql-up mysql-wait logs down clean

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

mysql-up: ## Start the MySQL container in the background (idempotent)
	docker compose up -d mysql
	@$(MAKE) mysql-wait

mysql-wait:
	@echo "Waiting for MySQL to accept connections…"
	@for i in $$(seq 1 60); do \
	  if docker compose exec -T mysql mysqladmin ping -uroot -ppassword --silent 2>/dev/null; then \
	    echo "MySQL ready."; exit 0; \
	  fi; sleep 1; \
	done; echo "MySQL did not become ready in time" >&2; exit 1

run: build ## Run the server in the foreground (assumes mysql-up)
	$(GRADLE) run --console=plain

up: mysql-up run ## docker compose up (mysql) + run the app in the foreground

logs: ## Tail mysql logs
	docker compose logs -f mysql

down: ## Stop and remove the mysql container (keeps the named volume)
	docker compose down

clean: ## Remove build artifacts
	$(GRADLE) clean

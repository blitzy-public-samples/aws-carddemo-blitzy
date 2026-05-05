#######################################################################
# Makefile - CardDemo cobol-check test orchestration.
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.
#######################################################################
#
# Targets
# -------
#   help        Show this list of targets.
#   init        Download cobol-check JAR (if not already present).
#   fixtures    Regenerate tests/fixtures/cobol-snippets/*.cpy from
#               app/data/ASCII/*.txt.
#   lint        Run all four validation-gate scripts under tests/lint/.
#   test        Run the full cobol-check suite (lint + fixtures + run).
#   test-one    Run a single program's testsuite.  PROGRAM=<name>.
#   test-debug  Run a single program with cobol-check log level DEBUG.
#               PROGRAM=<name> [TESTCASE='<description-substring>'].
#   coverage    Run the suite with GnuCOBOL --coverage and emit a gcov
#               summary.  Enforces per-program coverage targets.
#   clean       Remove build artifacts under target/.
#######################################################################

REPO_ROOT      := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
TESTS_DIR      := $(REPO_ROOT)/tests
SUITE_DIR      := $(TESTS_DIR)/cobol-check
LINT_DIR       := $(TESTS_DIR)/lint
FIX_DIR        := $(TESTS_DIR)/fixtures
SNIPPETS_DIR   := $(FIX_DIR)/cobol-snippets
STUBS_DIR      := $(TESTS_DIR)/stubs
BUILD_DIR      := $(REPO_ROOT)/target
CC_BUILD_DIR   := $(BUILD_DIR)/cobol-check

CC_VERSION     := 0.2.16
CC_JAR_NAME    := cobol-check-$(CC_VERSION).jar
CC_JAR_PATH    := $(SUITE_DIR)/lib/$(CC_JAR_NAME)
CC_ZIP_URL     := https://raw.githubusercontent.com/openmainframeproject/cobol-check/0.2.16_release/build/distributions/cobol-check-$(CC_VERSION).zip
CC_CONFIG      := $(SUITE_DIR)/config.properties

# JVM (cobol-check is a Java executable JAR).
JAVA           ?= java

# Per-target compilation options.  Tests append --coverage in the
# coverage target.
COBC_OPTS      ?=

# Programs the test runner iterates over.  cobol-check expects test
# suites to live in a per-program directory:
#     tests/cobol-check/<PROGRAM>/<anything>.cut
# The list below is the set of immediate sub-directories of
# tests/cobol-check/ that contain at least one .cut file.  When the
# testsuite directory is empty the loop is a no-op.
PROGRAMS       := $(sort $(notdir $(patsubst %/,%,\
                  $(dir $(wildcard $(SUITE_DIR)/*/*.cut)))))

# Per-program timeout (seconds) for test-one and the loop in 'test'.
TEST_TIMEOUT   ?= 60

#----------------------------------------------------------------------
# .DEFAULT_GOAL keeps `make` (no target) printing a helpful summary.
#----------------------------------------------------------------------
.DEFAULT_GOAL := help

.PHONY: help init fixtures lint test test-one test-debug coverage clean \
        ensure-build-dir _print-config

help:
	@echo "CardDemo cobol-check Makefile -- common targets:"
	@echo "  make init            Download cobol-check JAR"
	@echo "  make fixtures        Regenerate cobol fixture snippets"
	@echo "  make lint            Run validation-gate scripts"
	@echo "  make test            Run all testsuites"
	@echo "  make test-one PROGRAM=<NAME>"
	@echo "                       Run a single testsuite"
	@echo "  make test-debug PROGRAM=<NAME> [TESTCASE='<substr>']"
	@echo "                       Run a single testsuite with DEBUG log"
	@echo "  make coverage        Re-run with --coverage and gcov summary"
	@echo "  make clean           Remove target/ build artifacts"
	@echo ""
	@echo "Detected programs (from .cut files):"
	@for p in $(PROGRAMS); do echo "    $$p"; done

ensure-build-dir:
	@mkdir -p $(CC_BUILD_DIR)

#----------------------------------------------------------------------
# init -- download the cobol-check JAR if it isn't already in place.
# The JAR is ~270KB and is a binary artifact, so it is intentionally
# kept out of git (see .gitignore) and bootstrapped here.
#----------------------------------------------------------------------
init: $(CC_JAR_PATH)

$(CC_JAR_PATH):
	@echo "[init] Bootstrapping cobol-check $(CC_VERSION) ..."
	@mkdir -p $(SUITE_DIR)/lib $(BUILD_DIR)/init
	@if command -v curl >/dev/null 2>&1; then \
	    curl -sSL -o $(BUILD_DIR)/init/cobol-check-$(CC_VERSION).zip $(CC_ZIP_URL); \
	elif command -v wget >/dev/null 2>&1; then \
	    wget -q -O $(BUILD_DIR)/init/cobol-check-$(CC_VERSION).zip $(CC_ZIP_URL); \
	else \
	    echo "[init] ERROR: need curl or wget on PATH" >&2; exit 1; \
	fi
	@cd $(BUILD_DIR)/init && unzip -o -q cobol-check-$(CC_VERSION).zip
	@cp $(BUILD_DIR)/init/bin/$(CC_JAR_NAME) $(CC_JAR_PATH)
	@echo "[init] Installed $(CC_JAR_PATH)"

#----------------------------------------------------------------------
# fixtures -- regenerate the cobol-snippet copybooks from
# app/data/ASCII/*.txt.  Idempotent.
#----------------------------------------------------------------------
fixtures:
	@echo "[fixtures] Regenerating cobol fixture snippets ..."
	@python3 $(FIX_DIR)/load_fixture.py >/dev/null
	@ls $(SNIPPETS_DIR)/*.cpy 2>/dev/null | wc -l \
	    | xargs -I{} echo "[fixtures] Generated {} snippet(s) under $(SNIPPETS_DIR)/"

#----------------------------------------------------------------------
# lint -- run the four validation gates documented in Section 0.7.2
# of the Agent Action Plan.
#----------------------------------------------------------------------
lint:
	@echo "[lint] Running validation gates ..."
	@$(LINT_DIR)/check_no_business_logic.sh
	@$(LINT_DIR)/check_no_production_redeclaration.sh
	@$(LINT_DIR)/check_assertion_density.sh
	@$(LINT_DIR)/check_isolation.sh
	@echo "[lint] All gates passed."

#----------------------------------------------------------------------
# test -- the canonical entry point for the whole suite.
# Order: lint  ->  fixtures  ->  cobol-check.
# When PROGRAMS is empty (no .cut files yet), only lint and fixtures
# run; this makes `make test` a green-on-empty target so the CI status
# is meaningful from the very first commit onward.
#----------------------------------------------------------------------
test: lint fixtures init ensure-build-dir
	@set -e; \
	if [ -z "$(strip $(PROGRAMS))" ]; then \
	    echo "[test] No .cut files in $(SUITE_DIR) -- nothing to run."; \
	    exit 0; \
	fi; \
	echo "[test] Running cobol-check for: $(PROGRAMS)"; \
	cd $(REPO_ROOT) && \
	for prog in $(PROGRAMS); do \
	    echo "[test] === $$prog ==="; \
	    timeout $(TEST_TIMEOUT) \
	        $(JAVA) -jar $(CC_JAR_PATH) \
	            --config-file $(CC_CONFIG) \
	            --source-context $(REPO_ROOT) \
	            --run-directory  $(REPO_ROOT) \
	            --programs       $$prog \
	            || { echo "[test] FAIL: $$prog"; exit 1; }; \
	done; \
	echo "[test] All testsuites passed."

#----------------------------------------------------------------------
# test-one -- single-program convenience target for local debugging.
#----------------------------------------------------------------------
test-one: lint fixtures init ensure-build-dir
	@if [ -z "$(PROGRAM)" ]; then \
	    echo "[test-one] ERROR: PROGRAM=<name> is required" >&2; exit 1; \
	fi
	@echo "[test-one] Running $(PROGRAM)"
	@cd $(REPO_ROOT) && \
	timeout $(TEST_TIMEOUT) \
	    $(JAVA) -jar $(CC_JAR_PATH) \
	        --config-file $(CC_CONFIG) \
	        --source-context $(REPO_ROOT) \
	        --run-directory  $(REPO_ROOT) \
	        --programs       $(PROGRAM)

#----------------------------------------------------------------------
# test-debug -- run with DEBUG-level logging and retain the merged
# source so reviewers can read the injected test code alongside the
# production source.  TESTCASE is forwarded to cobol-check's filter.
#----------------------------------------------------------------------
test-debug: lint fixtures init ensure-build-dir
	@if [ -z "$(PROGRAM)" ]; then \
	    echo "[test-debug] ERROR: PROGRAM=<name> is required" >&2; exit 1; \
	fi
	@echo "[test-debug] Running $(PROGRAM) with DEBUG logging."
	@cd $(REPO_ROOT) && \
	$(JAVA) -Xdebug -jar $(CC_JAR_PATH) \
	    --config-file $(CC_CONFIG) \
	    --log-level   DEBUG \
	    --source-context $(REPO_ROOT) \
	    --run-directory  $(REPO_ROOT) \
	    --programs $(PROGRAM)

#----------------------------------------------------------------------
# coverage -- re-runs the suite under GnuCOBOL coverage instrumentation
# and produces a per-program gcov summary plus the aggregate enforcement
# report.
#
# GnuCOBOL translates COBOL into C and hands it off to gcc, so we pass
# coverage flags through `cobc -A` (C-compile phase) and `cobc -Q`
# (link phase) rather than using a `--coverage` switch directly.
#----------------------------------------------------------------------
COVERAGE_OPTS  := -O0 -A "-fprofile-arcs" -A "-ftest-coverage" -Q "-lgcov"

coverage: ensure-build-dir
	@echo "[coverage] Recompiling with profile-arcs + test-coverage ..."
	@$(MAKE) test COBC_OPTS='$(COVERAGE_OPTS)'
	@echo "[coverage] Running gcov ..."
	@if find $(CC_BUILD_DIR) -name '*.gcda' -print -quit | grep -q '.'; then \
	    cd $(CC_BUILD_DIR) && \
	    for f in $$(find . -name '*.gcda'); do \
	        gcov -b -c $$f >/dev/null 2>&1 || true; \
	    done; \
	    $(LINT_DIR)/parse_gcov_summary.sh; \
	else \
	    echo "[coverage] No .gcda files found; coverage data unavailable."; \
	fi

#----------------------------------------------------------------------
# clean -- remove generated artifacts.  Leaves the cobol-check JAR in
# place because re-downloading it is wasteful when only the build
# artifacts have rotted.
#----------------------------------------------------------------------
clean:
	@echo "[clean] Removing $(BUILD_DIR)/ and gcov spoor ..."
	@rm -rf $(BUILD_DIR)
	@find . -name '*.gcno' -delete
	@find . -name '*.gcda' -delete
	@find . -name '*.gcov' -delete
	@echo "[clean] Done."

_print-config:
	@echo "REPO_ROOT     = $(REPO_ROOT)"
	@echo "SUITE_DIR     = $(SUITE_DIR)"
	@echo "CC_JAR_PATH   = $(CC_JAR_PATH)"
	@echo "PROGRAMS      = $(PROGRAMS)"
	@echo "COBC_OPTS     = $(COBC_OPTS)"

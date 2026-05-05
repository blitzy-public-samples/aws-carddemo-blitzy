#######################################################################
# Makefile -- CardDemo cobol-check test orchestration.
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
# Canonical command surface for the CardDemo automated test suite.
# Coordinates four toolchains: GnuCOBOL (cobc), OpenJDK (java), GNU
# Make, and the gcov coverage tool.  All paths are kept relative to
# the repository root (the directory in which this Makefile lives).
#
# Targets
# -------
#   all         (default) Run lint -> test -> coverage in sequence.
#   help        Print a short summary of every target and key variable.
#   init        Download the cobol-check JAR (one-time bootstrap).
#   fixtures    Regenerate tests/fixtures/cobol-snippets/*.cpy from
#               the byte-exact ASCII inputs under app/data/ASCII/.
#   lint        Run all four AAP-mandated validation gate scripts.
#   test        Run every cobol-check testsuite under tests/cobol-check.
#   test-one    Run a single program's testsuite.   PROGRAM=<name>.
#   test-debug  Run a single testsuite with DEBUG logging and merged
#               source preservation.
#                                                  PROGRAM=<name>
#                                                  [TESTCASE='<substr>']
#   coverage    Re-run the suite with GnuCOBOL coverage instrumentation
#               and emit a per-program gcov summary; enforces the
#               coverage thresholds in tests/lint/parse_gcov_summary.sh.
#   clean       Remove build artifacts under target/ and gcov spoor.
#   distclean   Like clean, plus remove the bootstrapped cobol-check JAR.
#
# Coverage targets (AAP Section 0.7.1)
# ------------------------------------
#   * Overall .................... >= 70%
#   * Business logic ............. >= 80%
#   * Data validation ............ >= 90%
#   * File I/O ................... >= 70%
# Per-program targets are enforced by tests/lint/parse_gcov_summary.sh.
#######################################################################

#----------------------------------------------------------------------
# Repository root.  Derived from the Makefile's own path so that
# `make -C <repo>` (and `make` invocations from any subdirectory)
# resolve every other path correctly regardless of cwd.
#----------------------------------------------------------------------
REPO_ROOT      := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))

#----------------------------------------------------------------------
# Tool versions and download endpoints.
#
# COBOL_CHECK_URL points at the upstream release ZIP that bundles the
# 0.2.16 JAR.  At the time of this writing the project does NOT publish
# a standalone .jar asset for v0.2.16, so the bootstrap unwraps the
# ZIP and lifts the JAR out of the bin/ directory.  This URL is also
# documented in the .github/workflows/test.yml CI pipeline.
#----------------------------------------------------------------------
COBOL_CHECK_VERSION ?= 0.2.16
COBOL_CHECK_JAR     ?= tests/cobol-check/lib/cobol-check-$(COBOL_CHECK_VERSION).jar
COBOL_CHECK_URL     ?= https://raw.githubusercontent.com/openmainframeproject/cobol-check/0.2.16_release/build/distributions/cobol-check-$(COBOL_CHECK_VERSION).zip

#----------------------------------------------------------------------
# Compiler & runtime invocations.  These are exported into the cobol-
# check sub-process so the bundled compile-and-run script picks them up.
# COBC_OPTS is intentionally empty by default; the `coverage` target
# overrides it via $(MAKE) test COBC_OPTS=...
#----------------------------------------------------------------------
COBC                ?= cobc
COBC_OPTS           ?=
JAVA                ?= java
JAVA_OPTS           ?=

#----------------------------------------------------------------------
# Directory layout.  Every path below is resolved relative to
# $(REPO_ROOT) so that recipes can `cd $(REPO_ROOT)` and still hit the
# correct file when invoked through `make -C` or via a wrapper.
#----------------------------------------------------------------------
SRC_DIR             ?= app/cbl
COPY_DIR            ?= app/cpy
BMS_COPY_DIR        ?= app/cpy-bms
DATA_DIR            ?= app/data/ASCII
TEST_DIR            ?= tests/cobol-check
SUITE_DIR           := $(REPO_ROOT)/$(TEST_DIR)
STUB_DIR            ?= tests/stubs
FIXTURE_DIR         ?= tests/fixtures
SNIPPET_DIR         := $(REPO_ROOT)/$(FIXTURE_DIR)/cobol-snippets
LINT_DIR            ?= tests/lint
BUILD_DIR           ?= target/cobol-check
COVERAGE_DIR        ?= $(BUILD_DIR)
INIT_WORK_DIR       := $(REPO_ROOT)/target/init

#----------------------------------------------------------------------
# Per-invocation parameters (override on the command line).
#   PROGRAM    program-id targeted by test-one / test-debug
#   TESTCASE   description-substring filter for test-debug
#   TEST_TIMEOUT     wall-clock timeout for the full test loop (s)
#   SINGLE_TIMEOUT   wall-clock timeout for a single program (s)
#----------------------------------------------------------------------
PROGRAM             ?=
TESTCASE            ?=
TEST_TIMEOUT        ?= 60
SINGLE_TIMEOUT      ?= 30

#----------------------------------------------------------------------
# Coverage thresholds (AAP Section 0.7.1).  These values are
# informational at the Makefile level: tests/lint/parse_gcov_summary.sh
# owns the per-program enforcement table.  Exposing them here lets
# CI / future developers override via environment variables and keeps
# the AAP targets discoverable through `make help`.
#----------------------------------------------------------------------
COV_THRESHOLD_OVERALL          ?= 70
COV_THRESHOLD_BUSINESS_LOGIC   ?= 80
COV_THRESHOLD_DATA_VALIDATION  ?= 90
COV_THRESHOLD_FILE_IO          ?= 70

#----------------------------------------------------------------------
# Coverage instrumentation flags.  GnuCOBOL translates COBOL into C and
# delegates to gcc; we therefore route -fprofile-arcs / -ftest-coverage
# through `cobc -A` (C-compile pass) and `-lgcov` through `cobc -Q`
# (link pass) instead of using a single --coverage switch.  -O0 ensures
# gcov can attribute coverage to the originating source line.
#----------------------------------------------------------------------
COVERAGE_OPTS       := -O0 -A "-fprofile-arcs" -A "-ftest-coverage" -Q "-lgcov"

#----------------------------------------------------------------------
# cobol-check artifact paths derived from the configuration above.
# CC_CONFIG must match application.source.directory etc. inside
# tests/cobol-check/config.properties.  CC_JAR_PATH is the ABSOLUTE
# path the recipes use; COBOL_CHECK_JAR (above) is the REPO-relative
# path documented to users.
#----------------------------------------------------------------------
CC_JAR_NAME         := cobol-check-$(COBOL_CHECK_VERSION).jar
CC_JAR_PATH         := $(REPO_ROOT)/$(COBOL_CHECK_JAR)
CC_CONFIG           := $(REPO_ROOT)/$(TEST_DIR)/config.properties

#----------------------------------------------------------------------
# Programs the test runner iterates over.  cobol-check expects test
# suites to live in a per-program directory:
#     tests/cobol-check/<PROGRAM>/<anything>.cut
# The list below is the set of immediate sub-directories of
# tests/cobol-check/ that contain at least one .cut file.  When the
# suite directory is empty the loop is a no-op (so `make test`
# remains green-on-empty for the very first commit that introduces
# the test scaffolding).
#----------------------------------------------------------------------
PROGRAMS            := $(sort $(notdir $(patsubst %/,%,\
                       $(dir $(wildcard $(SUITE_DIR)/*/*.cut)))))

#######################################################################
# Phony targets and default goal.
#######################################################################
.PHONY: all help init fixtures lint test test-one test-debug \
        coverage clean distclean ensure-build-dir _print-config

# AAP Section 0.4 / detailed instructions Phase 3: a bare `make`
# invocation runs the full pre-commit sequence (lint -> test ->
# coverage) so that contributors get full feedback in one shot.
.DEFAULT_GOAL := all

#######################################################################
# all -- the canonical pre-commit / CI-equivalent sequence.
#######################################################################
all: lint test coverage
	@echo "[all] Lint, test, and coverage all completed."

#######################################################################
# help -- print a usage summary.
#######################################################################
help:
	@echo "CardDemo cobol-check Makefile -- common targets:"
	@echo "  make all                          (default) lint + test + coverage"
	@echo "  make init                          One-time download of cobol-check JAR"
	@echo "  make fixtures                      Regenerate cobol fixture snippets"
	@echo "  make lint                          Run validation-gate scripts"
	@echo "  make test                          Run all testsuites"
	@echo "  make test-one PROGRAM=<NAME>       Run a single testsuite"
	@echo "  make test-debug PROGRAM=<NAME> [TESTCASE='<substr>']"
	@echo "                                     Run a single testsuite with DEBUG log"
	@echo "  make coverage                      Re-run with --coverage and gcov summary"
	@echo "  make clean                         Remove target/ build artifacts"
	@echo "  make distclean                     Like clean plus remove bootstrapped JAR"
	@echo ""
	@echo "Variables (override on command line):"
	@echo "  COBOL_CHECK_VERSION=$(COBOL_CHECK_VERSION)"
	@echo "  TEST_TIMEOUT=$(TEST_TIMEOUT)   (per-program wall-clock timeout, seconds)"
	@echo "  SINGLE_TIMEOUT=$(SINGLE_TIMEOUT) (test-one wall-clock timeout, seconds)"
	@echo "  PROGRAM='$(PROGRAM)'"
	@echo "  TESTCASE='$(TESTCASE)'"
	@echo "  COV_THRESHOLD_OVERALL=$(COV_THRESHOLD_OVERALL)"
	@echo "  COV_THRESHOLD_BUSINESS_LOGIC=$(COV_THRESHOLD_BUSINESS_LOGIC)"
	@echo "  COV_THRESHOLD_DATA_VALIDATION=$(COV_THRESHOLD_DATA_VALIDATION)"
	@echo "  COV_THRESHOLD_FILE_IO=$(COV_THRESHOLD_FILE_IO)"
	@echo ""
	@echo "Detected programs (sub-directories of $(TEST_DIR) with a .cut file):"
	@if [ -z "$(strip $(PROGRAMS))" ]; then \
	    echo "    (none yet)"; \
	else \
	    for p in $(PROGRAMS); do echo "    $$p"; done; \
	fi

ensure-build-dir:
	@mkdir -p $(REPO_ROOT)/$(BUILD_DIR)

#######################################################################
# init -- bootstrap the cobol-check JAR.
#
# The framework's 0.2.16 release publishes a ZIP that bundles the JAR
# under bin/.  We accept either curl or wget (whichever is on PATH) so
# the target works on minimal CI runners that ship only one of them.
# The JAR is intentionally NOT committed (see .gitignore) -- this
# target re-fetches it whenever the file is missing.
#######################################################################
init: $(CC_JAR_PATH)

$(CC_JAR_PATH):
	@echo "[init] Bootstrapping cobol-check $(COBOL_CHECK_VERSION) ..."
	@mkdir -p $(REPO_ROOT)/$(dir $(COBOL_CHECK_JAR)) $(INIT_WORK_DIR)
	@if command -v curl >/dev/null 2>&1; then \
	    curl -fsSL -o $(INIT_WORK_DIR)/$(CC_JAR_NAME).zip $(COBOL_CHECK_URL); \
	elif command -v wget >/dev/null 2>&1; then \
	    wget -q -O $(INIT_WORK_DIR)/$(CC_JAR_NAME).zip $(COBOL_CHECK_URL); \
	else \
	    echo "[init] ERROR: need curl or wget on PATH" >&2; exit 1; \
	fi
	@cd $(INIT_WORK_DIR) && unzip -o -q $(CC_JAR_NAME).zip
	@cp $(INIT_WORK_DIR)/bin/$(CC_JAR_NAME) $(CC_JAR_PATH)
	@echo "[init] Installed $(COBOL_CHECK_JAR)"

#######################################################################
# fixtures -- regenerate the cobol-snippet copybooks from
# app/data/ASCII/*.txt.  load_fixture.py is the single non-COBOL test
# helper in the suite and is called with its native CLI; it writes
# under tests/fixtures/cobol-snippets/ and is idempotent.
#######################################################################
fixtures:
	@echo "[fixtures] Regenerating cobol fixture snippets from $(DATA_DIR)/ ..."
	@mkdir -p $(SNIPPET_DIR)
	@python3 $(REPO_ROOT)/$(FIXTURE_DIR)/load_fixture.py \
	    --repo-root $(REPO_ROOT) \
	    --output-dir $(SNIPPET_DIR) >/dev/null
	@count=$$(ls -1 $(SNIPPET_DIR)/*.cpy 2>/dev/null | wc -l); \
	echo "[fixtures] Generated $$count snippet(s) under $(FIXTURE_DIR)/cobol-snippets/"

#######################################################################
# lint -- run the four AAP-mandated validation gates documented in
# Section 0.7.2.  Each script exits non-zero on any policy violation
# and the recipe exits at the first failure (set -e is implicit per
# `make`'s one-command-per-line execution model).
#######################################################################
lint:
	@echo "[lint] Running validation gates ..."
	@bash $(REPO_ROOT)/$(LINT_DIR)/check_no_business_logic.sh
	@bash $(REPO_ROOT)/$(LINT_DIR)/check_no_production_redeclaration.sh
	@bash $(REPO_ROOT)/$(LINT_DIR)/check_assertion_density.sh
	@bash $(REPO_ROOT)/$(LINT_DIR)/check_isolation.sh
	@echo "[lint] All gates passed."

#######################################################################
# test -- canonical entry point for the whole suite.
#
# Order: init (download JAR if needed) -> fixtures (regen snippets) ->
# per-program loop.  When PROGRAMS is empty the loop is a no-op and
# the target exits 0.  Each invocation of cobol-check is wrapped in
# `timeout $(TEST_TIMEOUT)` so a runaway test cannot hang the run.
#######################################################################
test: init fixtures ensure-build-dir
	@set -e; \
	if [ -z "$(strip $(PROGRAMS))" ]; then \
	    echo "[test] No .cut files in $(TEST_DIR) -- nothing to run."; \
	    exit 0; \
	fi; \
	echo "[test] Running cobol-check for: $(PROGRAMS)"; \
	cd $(REPO_ROOT) && \
	for prog in $(PROGRAMS); do \
	    echo "[test] === $$prog ==="; \
	    COBC_OPTS='$(COBC_OPTS)' \
	    timeout $(TEST_TIMEOUT) \
	        $(JAVA) $(JAVA_OPTS) -jar $(CC_JAR_PATH) \
	            --config-file    $(CC_CONFIG) \
	            --source-context $(REPO_ROOT) \
	            --run-directory  $(REPO_ROOT) \
	            --programs       $$prog \
	            || { echo "[test] FAIL: $$prog"; exit 1; }; \
	done; \
	echo "[test] All testsuites passed."

#######################################################################
# test-one -- run a single program's testsuite.
#######################################################################
test-one: init fixtures ensure-build-dir
	@if [ -z "$(PROGRAM)" ]; then \
	    echo "[test-one] ERROR: PROGRAM=<name> is required" >&2; \
	    echo "  Example: make test-one PROGRAM=CSUTLDTC" >&2; \
	    exit 1; \
	fi
	@echo "[test-one] Running $(PROGRAM)"
	@cd $(REPO_ROOT) && \
	COBC_OPTS='$(COBC_OPTS)' \
	timeout $(SINGLE_TIMEOUT) \
	    $(JAVA) $(JAVA_OPTS) -jar $(CC_JAR_PATH) \
	        --config-file    $(CC_CONFIG) \
	        --source-context $(REPO_ROOT) \
	        --run-directory  $(REPO_ROOT) \
	        --programs       $(PROGRAM) \
	    || { echo "[test-one] FAIL: $(PROGRAM)"; exit 1; }
	@echo "[test-one] $(PROGRAM) testsuite passed."

#######################################################################
# test-debug -- run a single program with DEBUG logging.  cobol-check
# preserves its merged source artefact under $(BUILD_DIR) so reviewers
# can inspect the injected test code alongside the production source.
# The TESTCASE variable is informational; cobol-check does not yet
# accept a per-testcase filter via CLI in 0.2.16.
#######################################################################
test-debug: init fixtures ensure-build-dir
	@if [ -z "$(PROGRAM)" ]; then \
	    echo "[test-debug] ERROR: PROGRAM=<name> is required" >&2; \
	    exit 1; \
	fi
	@echo "[test-debug] Running $(PROGRAM) with DEBUG logging."
	@if [ -n "$(TESTCASE)" ]; then \
	    echo "[test-debug] TESTCASE filter requested: $(TESTCASE)"; \
	    echo "[test-debug] (informational; v0.2.16 has no per-testcase CLI filter)"; \
	fi
	@cd $(REPO_ROOT) && \
	COBC_OPTS='$(COBC_OPTS)' \
	$(JAVA) -Xdebug $(JAVA_OPTS) -jar $(CC_JAR_PATH) \
	    --config-file    $(CC_CONFIG) \
	    --log-level      DEBUG \
	    --source-context $(REPO_ROOT) \
	    --run-directory  $(REPO_ROOT) \
	    --programs       $(PROGRAM)
	@echo "[test-debug] Merged source retained at $(BUILD_DIR)/ (if generated)."

#######################################################################
# coverage -- re-run the suite with GnuCOBOL coverage instrumentation
# and emit a per-program summary that enforces the AAP Section 0.7.1
# thresholds.  parse_gcov_summary.sh owns the per-program threshold
# table and exits non-zero on any miss; this recipe propagates that
# exit code so CI fails on coverage regressions.
#######################################################################
coverage: ensure-build-dir
	@echo "[coverage] Recompiling with profile-arcs + test-coverage ..."
	@$(MAKE) test COBC_OPTS='$(COVERAGE_OPTS)'
	@echo "[coverage] Aggregating gcov output ..."
	@if find $(REPO_ROOT)/$(BUILD_DIR) -name '*.gcda' -print -quit 2>/dev/null | grep -q '.'; then \
	    cd $(REPO_ROOT)/$(BUILD_DIR) && \
	    for f in $$(find . -name '*.gcda'); do \
	        gcov -b -c $$f >/dev/null 2>&1 || true; \
	    done; \
	    echo "[coverage] Parsing gcov summary against thresholds ..."; \
	    bash $(REPO_ROOT)/$(LINT_DIR)/parse_gcov_summary.sh; \
	else \
	    echo "[coverage] No .gcda files found; coverage data unavailable."; \
	    echo "[coverage] (This is expected when no .cut testsuites exist yet.)"; \
	fi

#######################################################################
# clean -- remove generated artifacts.  Leaves the cobol-check JAR in
# place because re-downloading it is wasteful when only the build
# artifacts have rotted; use `make distclean` to remove the JAR too.
#######################################################################
clean:
	@echo "[clean] Removing $(REPO_ROOT)/target/ and gcov spoor ..."
	@rm -rf $(REPO_ROOT)/target
	@find $(REPO_ROOT) -name '*.gcno' -delete 2>/dev/null || true
	@find $(REPO_ROOT) -name '*.gcda' -delete 2>/dev/null || true
	@find $(REPO_ROOT) -name '*.gcov' -delete 2>/dev/null || true
	@echo "[clean] Done."

#######################################################################
# distclean -- like clean, plus remove the bootstrapped JAR.  Useful
# before tagging releases or when upgrading COBOL_CHECK_VERSION.
#######################################################################
distclean: clean
	@echo "[distclean] Removing bootstrapped cobol-check JAR ..."
	@rm -f $(CC_JAR_PATH)
	@rmdir $(REPO_ROOT)/$(dir $(COBOL_CHECK_JAR)) 2>/dev/null || true
	@echo "[distclean] Done."

#######################################################################
# _print-config -- internal target for debugging the variable plumbing.
# Not advertised in `help` because it is not part of the user contract.
#######################################################################
_print-config:
	@echo "REPO_ROOT                      = $(REPO_ROOT)"
	@echo "COBOL_CHECK_VERSION            = $(COBOL_CHECK_VERSION)"
	@echo "COBOL_CHECK_JAR                = $(COBOL_CHECK_JAR)"
	@echo "COBOL_CHECK_URL                = $(COBOL_CHECK_URL)"
	@echo "COBC                           = $(COBC)"
	@echo "COBC_OPTS                      = $(COBC_OPTS)"
	@echo "JAVA                           = $(JAVA)"
	@echo "JAVA_OPTS                      = $(JAVA_OPTS)"
	@echo "SRC_DIR                        = $(SRC_DIR)"
	@echo "COPY_DIR                       = $(COPY_DIR)"
	@echo "BMS_COPY_DIR                   = $(BMS_COPY_DIR)"
	@echo "DATA_DIR                       = $(DATA_DIR)"
	@echo "TEST_DIR                       = $(TEST_DIR)"
	@echo "STUB_DIR                       = $(STUB_DIR)"
	@echo "FIXTURE_DIR                    = $(FIXTURE_DIR)"
	@echo "LINT_DIR                       = $(LINT_DIR)"
	@echo "BUILD_DIR                      = $(BUILD_DIR)"
	@echo "COVERAGE_DIR                   = $(COVERAGE_DIR)"
	@echo "PROGRAM                        = $(PROGRAM)"
	@echo "TESTCASE                       = $(TESTCASE)"
	@echo "TEST_TIMEOUT                   = $(TEST_TIMEOUT)"
	@echo "SINGLE_TIMEOUT                 = $(SINGLE_TIMEOUT)"
	@echo "COV_THRESHOLD_OVERALL          = $(COV_THRESHOLD_OVERALL)"
	@echo "COV_THRESHOLD_BUSINESS_LOGIC   = $(COV_THRESHOLD_BUSINESS_LOGIC)"
	@echo "COV_THRESHOLD_DATA_VALIDATION  = $(COV_THRESHOLD_DATA_VALIDATION)"
	@echo "COV_THRESHOLD_FILE_IO          = $(COV_THRESHOLD_FILE_IO)"
	@echo "PROGRAMS                       = $(PROGRAMS)"

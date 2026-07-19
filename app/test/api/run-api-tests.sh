#!/usr/bin/env bash
# =============================================================================
# run-api-tests.sh
#
# End-to-end HTTP/JSON test harness for the CardDemo REST/JSON API layer that is
# exposed via base CICS Web Support under the versioned base path
# /carddemo/api/v1 (see app/api/openapi.yaml `servers.url`).
#
# It exercises ALL seven effective endpoints and every documented edge case,
# then runs four first-class security assertions over the collected RESPONSE
# bodies. The API is strictly READ-ONLY: the only non-GET request is the single
# POST /signon used to obtain a bearer token (User Example Flow 1); no mutation
# endpoint exists and none is ever attempted.
#
# IMPORTANT: This script is designed to run against a LIVE CICS region. In the
# Blitzy build sandbox there is no running server, so it is validated for
# structural correctness (bash -n clean, robust control flow, complete coverage)
# rather than a green end-to-end run. When pointed at a real region it prints a
# per-check PASS/FAIL line and a final SUMMARY, exiting 0 only when every check
# passes.
#
# -----------------------------------------------------------------------------
# CONFIGURATION (all values are environment-overridable; defaults shown)
# -----------------------------------------------------------------------------
#   API_SCHEME  URL scheme for the region endpoint            (default: http)
#   API_HOST    CICS Web Support host                         (default: localhost)
#   API_PORT    TCPIPSERVICE listener port (matches CSD def)  (default: 3001)
#   API_BASE    API base path from openapi.yaml servers.url   (default: /carddemo/api/v1)
#   BASE_URL    Derived: ${API_SCHEME}://${API_HOST}:${API_PORT}${API_BASE}
#
#   API_USER    CardDemo user id seeded in USRSEC             (default: USER0001)
#   API_PASS    Password for API_USER (README.md L157-159)    (default: PASSWORD)
#
#   ACCT_OK     A valid account id (ACCTDAT fixture)          (default: 00000000001)
#   CUST_OK     A valid customer id (CUSTDAT fixture)         (default: 000000001)
#   CARD_OK     A valid card number / PAN (CARDDAT fixture)   (default: 0500024453765740)
#   TRAN_OK     A valid transaction id (TRANSACT fixture)     (default: 0000000000683580)
#   LIST_ACCT   Account with cross-referenced card(s) & tx    (default: 00000000050)
#   EMPTY_ACCT  Account with NO cross-referenced card(s)      (default: 00000000099)
#
#   FAULT_PATH  Optional operator-provided route that forces  (default: <unset>)
#               a controlled 500; when unset the 500 check is
#               SKIPPED in default mode / FAILED in release mode.
#               MUST be same-origin (a path, or a URL whose
#               scheme://host:port equals the target); a
#               cross-origin URL is rejected so the bearer token
#               can never be sent to a foreign host.
#
#   RELEASE_MODE Strict gate: 1/true/yes/on make jq REQUIRED and  (default: 0)
#               every optional scenario (500, duplicate sign-on,
#               extra-segment routing, list cap, negative-amount
#               matrix, unknown-token) MANDATORY. 0 keeps the
#               developer-friendly optional behaviour.
#
# NOTE ON EMPTY_ACCT: it MUST be a *valid-but-cardless* account, i.e. an account
# that resolves to zero cross-referenced cards. All shipped ASCII fixtures seed
# six transactions per cross-referenced account, so a genuinely empty result can
# only come from an account with no cards. COTRSVCC returns HTTP 200 with an
# empty transactions array (count 0) for such an account -- it does NOT return
# 404. If EMPTY_ACCT happens to own cards in your data set, override it with an
# account id that owns none.
#
# -----------------------------------------------------------------------------
# SECURITY ASSERTIONS (run against RESPONSE bodies only)
# -----------------------------------------------------------------------------
#   1. No full PAN is ever returned (masked to last four; masked form validated).
#   2. No card security code (CVV) field is ever serialized.
#   3. No unmasked SSN or government-issued id is ever returned.
#   4. No password is ever echoed back in any response body.
# Request bodies (which legitimately carry the sign-on password) are never
# collected, so the password check cannot false-positive on our own request.
#
# -----------------------------------------------------------------------------
# EXIT CODES
# -----------------------------------------------------------------------------
#   0  every executed check passed (FAILED == 0)
#   1  at least one check failed
#   2  a required dependency (curl) is missing
#
# -----------------------------------------------------------------------------
# USAGE
# -----------------------------------------------------------------------------
#   ./run-api-tests.sh
#   API_HOST=cics.example.com API_PORT=13080 ./run-api-tests.sh
#   FAULT_PATH=/carddemo/api/v1/accounts/00000000001?forceError=1 ./run-api-tests.sh
# =============================================================================

# Continue past individual failed assertions so that every check runs and a full
# summary is produced; do NOT enable `set -e`. `set -u` catches unset-variable
# bugs and `pipefail` surfaces failures inside pipelines.
set -u
set -o pipefail

# ---- Endpoint / connection configuration (env-overridable) ------------------
API_SCHEME="${API_SCHEME:-http}"
API_HOST="${API_HOST:-localhost}"
# Default matches the authoritative TCPIPSERVICE(CDAPISVC) PORTNUMBER in
# app/csd/CARDDEMOAPI.CSD (3001). Override with API_PORT for a region that
# installs the listener on a different operator-chosen port.
API_PORT="${API_PORT:-3001}"
API_BASE="${API_BASE:-/carddemo/api/v1}"
BASE_URL="${API_SCHEME}://${API_HOST}:${API_PORT}${API_BASE}"

# ---- Credentials (seeded in USRSEC; documented in README.md L157-159) --------
API_USER="${API_USER:-USER0001}"
API_PASS="${API_PASS:-PASSWORD}"

# ---- Fixture-derived valid identifiers --------------------------------------
ACCT_OK="${ACCT_OK:-00000000001}"
CUST_OK="${CUST_OK:-000000001}"
CARD_OK="${CARD_OK:-0500024453765740}"
TRAN_OK="${TRAN_OK:-0000000000683580}"
LIST_ACCT="${LIST_ACCT:-00000000050}"     # cross-referenced card(s) -> has transactions
EMPTY_ACCT="${EMPTY_ACCT:-00000000099}"   # NO cross-referenced cards -> 200 empty list, NOT 404

# ---- Invalid identifiers for negative (not-found) cases ---------------------
ACCT_BAD="99999999999"
CUST_BAD="999999999"
CARD_BAD="9999999999999999"
TRAN_BAD="9999999999999999"

# ---- Optional 500 fault-injection route (operator-provided) -----------------
# When unset the 500 check is SKIPPED (printed as SKIP), never failed.
FAULT_PATH="${FAULT_PATH:-}"

# ---- curl timeouts (env-overridable) : never hang on an unresponsive host ---
# --connect-timeout bounds the TCP-connect phase; --max-time bounds the whole
# transfer. A black-hole host (packets silently dropped) therefore fails fast
# instead of blocking the harness indefinitely.
API_CONNECT_TIMEOUT="${API_CONNECT_TIMEOUT:-5}"
API_MAX_TIME="${API_MAX_TIME:-30}"

# ---- Release (strict) mode --------------------------------------------------
# RELEASE_MODE=1 turns every optional degradation into a hard requirement so a
# release gate cannot go green while coverage is silently incomplete:
#   * jq becomes a REQUIRED dependency (precise JSON extraction is mandatory);
#   * the 500 fault-injection scenario is MANDATORY -- FAULT_PATH must be set,
#     otherwise the missing 500 check is recorded as a FAIL, not a SKIP;
#   * every extended scenario (duplicate sign-on, extra-segment routing, list
#     cap, negative-amount matrix, well-formed-but-unknown token) is asserted.
# The default (0) keeps the developer-friendly behaviour: jq optional, the 500
# scenario skipped when no fault route is supplied. Accept 1/true/yes/on.
case "${RELEASE_MODE:-0}" in
    1|true|TRUE|yes|YES|on|ON) RELEASE_MODE=1 ;;
    *)                         RELEASE_MODE=0 ;;
esac

# -----------------------------------------------------------------------------
# Command-line options
# -----------------------------------------------------------------------------
# Only -h/--help is accepted; every other setting is environment-overridable
# (see CONFIGURATION above). Options are parsed HERE, before the dependency
# checks, so `--help` is always available even on a host without curl.
# usage() writes the help text using only the printf shell builtin (no external
# command such as cat), so `--help` is guaranteed to work even on a host that
# is missing curl or coreutils entirely.
usage() {
    printf '%s\n' \
'Usage: run-api-tests.sh [-h|--help]' \
'' \
'End-to-end HTTP/JSON test harness for the CardDemo REST/JSON API layer. It' \
'signs on, exercises all seven endpoints and every documented edge case' \
'(404/400/401/200-empty/optional-500), asserts the uniform success ({data}) and' \
'error ({error:{code,message,requestId}}) envelopes and the application/json' \
'response content-type, then runs first-class security assertions over the' \
'collected response bodies.' \
'' \
'There are no positional arguments. All settings are environment-overridable:' \
'' \
'  API_SCHEME           URL scheme                     (default: http)' \
'  API_HOST             CICS Web Support host          (default: localhost)' \
'  API_PORT             TCPIPSERVICE listener port     (default: 3001)' \
'  API_BASE             API base path                  (default: /carddemo/api/v1)' \
'  API_USER             CardDemo user id               (default: USER0001)' \
'  API_PASS             Password for API_USER          (default: PASSWORD)' \
'  ACCT_OK CUST_OK CARD_OK TRAN_OK LIST_ACCT EMPTY_ACCT' \
'                       Valid fixture identifiers      (see CONFIGURATION header)' \
'  FAULT_PATH           Same-origin route forcing 500  (default: unset -> skipped)' \
'  RELEASE_MODE         Strict gate (1 => jq + all      (default: 0)' \
'                       scenarios mandatory)' \
'  API_CONNECT_TIMEOUT  curl connect timeout, seconds  (default: 5)' \
'  API_MAX_TIME         curl total timeout, seconds    (default: 30)' \
'' \
'Examples:' \
'  ./run-api-tests.sh' \
'  API_HOST=cics.example.com API_PORT=13080 ./run-api-tests.sh' \
'' \
'Exit codes: 0 every check passed; 1 at least one check failed; 2 curl missing.'
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            break
            ;;
        -*)
            printf 'ERROR: unknown option: %s\n\n' "$1" >&2
            usage >&2
            exit 2
            ;;
        *)
            printf 'ERROR: unexpected argument: %s\n\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

# -----------------------------------------------------------------------------
# Dependency checks
# -----------------------------------------------------------------------------
# curl is REQUIRED: without an HTTP client no check can run.
if ! command -v curl >/dev/null 2>&1; then
    printf 'ERROR: curl is required but was not found on PATH.\n' >&2
    exit 2
fi

# jq is OPTIONAL in default mode (grep/sed fallback) but REQUIRED in release
# mode, where precise JSON extraction is mandatory so a partially-parseable
# body cannot slip a malformed field past the grep fallback. HAVE_JQ is 1 or 0.
if command -v jq >/dev/null 2>&1; then
    HAVE_JQ=1
else
    HAVE_JQ=0
    if [ "$RELEASE_MODE" -eq 1 ]; then
        printf 'ERROR: jq is required in RELEASE_MODE but was not found on PATH.\n' >&2
        exit 2
    fi
    printf 'NOTE: jq not found; using grep/sed fallback for JSON parsing.\n' >&2
fi

# -----------------------------------------------------------------------------
# Temporary files
# -----------------------------------------------------------------------------
# BODY     holds the most recent response body written by curl -o.
# RESP_ALL accumulates EVERY response body (and only responses -- never request
#          bodies) so the security assertions can scan the full corpus at the
#          end. A newline separator is appended between bodies so that grep
#          cannot miss or spuriously merge a match across two concatenated
#          payloads.
# HDRS     holds the response headers (curl -D) of the most recent call so the
#          response Content-Type can be asserted (must be application/json).
# CURL_CFG holds a per-call curl --config (-K) file carrying the URL, method,
#          headers (including Authorization: Bearer ...) and a reference to the
#          request-body file. REQ_BODY holds the request body (which carries the
#          sign-on password). Both are created mode 0600 and rewritten on every
#          call so NO secret (password, bearer token) and no PAN-bearing URL is
#          ever placed on the curl argv, where it could surface in process
#          diagnostics such as `ps` (CWE-214). The whole set is removed on exit.
umask 077
BODY="$(mktemp)"
RESP_ALL="$(mktemp)"
HDRS="$(mktemp)"
CURL_CFG="$(mktemp)"
REQ_BODY="$(mktemp)"
chmod 600 "$BODY" "$RESP_ALL" "$HDRS" "$CURL_CFG" "$REQ_BODY"
trap 'rm -f "$RESP_ALL" "$BODY" "$HDRS" "$CURL_CFG" "$REQ_BODY"' EXIT

# -----------------------------------------------------------------------------
# Counters and issued bearer token
# -----------------------------------------------------------------------------
TESTS=0
PASSED=0
FAILED=0
TOKEN=""


# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

# http_call METHOD URL [DATA] [AUTH]
#
# Performs a single HTTP request and echoes the numeric HTTP status code on
# stdout. The response body is written to $BODY and also appended to $RESP_ALL
# (responses only). Arguments:
#   METHOD  HTTP verb (GET, POST, ...).
#   URL     Fully-qualified request URL.
#   DATA    Optional JSON request body; when non-empty a Content-Type header and
#           --data are added.
#   AUTH    Authorization control:
#             "auth"  -> send the issued token: Authorization: Bearer $TOKEN
#             ""      -> send no Authorization header
#             <other> -> send that literal value: Authorization: Bearer <other>
#                        (used to present a forged/invalid token for the 401 case)
http_call() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    local auth="${4:-}"

    # Build a per-call curl config file (-K). Every sensitive value -- the URL
    # (which may embed a full PAN for /cards and /xref), the Authorization
    # bearer token, and the request body (which carries the sign-on password)
    # -- lives ONLY inside this mode-0600 file, never on the curl command line.
    # The config file is rewritten (truncated) each call, and the request-body
    # file is emptied when a call sends no body so a prior password cannot
    # linger. Only "-K <cfg>" is ever visible in the process argv.
    : > "$CURL_CFG"
    : > "$REQ_BODY"
    {
        printf 'url = "%s"\n' "$url"
        printf 'request = "%s"\n' "$method"
        printf 'output = "%s"\n' "$BODY"
        printf 'dump-header = "%s"\n' "$HDRS"
        printf 'write-out = "%%{http_code}"\n'
        printf 'silent\n'
        printf 'connect-timeout = "%s"\n' "$API_CONNECT_TIMEOUT"
        printf 'max-time = "%s"\n' "$API_MAX_TIME"
        printf 'header = "Accept: application/json"\n'
    } > "$CURL_CFG"

    if [ -n "$data" ]; then
        # Body -> its own 0600 file, referenced as data = "@file"; the JSON
        # (with its embedded quotes and the password) never touches argv or the
        # config-file quoting rules.
        printf '%s' "$data" > "$REQ_BODY"
        printf 'header = "Content-Type: application/json"\n' >> "$CURL_CFG"
        printf 'data = "@%s"\n' "$REQ_BODY" >> "$CURL_CFG"
    fi

    if [ "$auth" = "auth" ]; then
        printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" >> "$CURL_CFG"
    elif [ -n "$auth" ]; then
        printf 'header = "Authorization: Bearer %s"\n' "$auth" >> "$CURL_CFG"
    fi

    # Start from a clean body and header dump so a connection failure cannot
    # leave stale content behind for the envelope/content-type assertions.
    : > "$BODY"
    : > "$HDRS"

    local code
    code="$(curl -K "$CURL_CFG")" || code="000"

    # Accumulate the RESPONSE body only, with a newline separator.
    cat "$BODY" >> "$RESP_ALL"
    printf '\n' >> "$RESP_ALL"

    # Scrub the request-body file immediately so the password does not persist
    # on disk between calls (belt-and-braces alongside the EXIT trap rm).
    : > "$REQ_BODY"

    printf '%s' "$code"
}

# check_status EXPECTED ACTUAL LABEL
# Compares an expected vs actual HTTP status, updates counters, and prints a
# single PASS/FAIL line.
check_status() {
    local expected="$1"
    local actual="$2"
    local label="$3"

    TESTS=$((TESTS + 1))
    if [ "$actual" = "$expected" ]; then
        PASSED=$((PASSED + 1))
        printf 'PASS: %s (expected=%s actual=%s)\n' "$label" "$expected" "$actual"
    else
        FAILED=$((FAILED + 1))
        printf 'FAIL: %s expected=%s actual=%s\n' "$label" "$expected" "$actual"
    fi
}

# record_pass LABEL / record_fail LABEL
# Count and print a non-status assertion (security checks, body-shape checks).
# They increment TESTS as well so the final SUMMARY arithmetic stays coherent
# (TESTS == PASSED + FAILED).
record_pass() {
    TESTS=$((TESTS + 1))
    PASSED=$((PASSED + 1))
    printf 'PASS: %s\n' "$1"
}
record_fail() {
    TESTS=$((TESTS + 1))
    FAILED=$((FAILED + 1))
    printf 'FAIL: %s\n' "$1"
}

# extract_token
# Reads the bearer token from the current $BODY (a SignonResponse whose token is
# at data.token). Uses jq when available, otherwise a grep/sed fallback. Prints
# the token (possibly empty) on stdout.
extract_token() {
    if [ "$HAVE_JQ" -eq 1 ]; then
        jq -r '.data.token // empty' "$BODY" 2>/dev/null
    else
        grep -o '"token"[[:space:]]*:[[:space:]]*"[^"]*"' "$BODY" \
            | head -1 \
            | sed -E 's/.*"token"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/'
    fi
}

# response_content_type
# Prints the value of the most recent response's Content-Type header (from the
# $HDRS dump), lowercased and trimmed of surrounding whitespace and CR; empty
# when the header is absent. The last matching header wins (in case of a proxy
# that appends one).
response_content_type() {
    grep -i '^Content-Type:' "$HDRS" 2>/dev/null \
        | tail -1 \
        | sed -E 's/^[Cc]ontent-[Tt]ype:[[:space:]]*//; s/[[:space:]]*$//' \
        | tr -d '\r' \
        | tr '[:upper:]' '[:lower:]'
}

# check_error_envelope EXPECTED_CODE LABEL
# Assert that the CURRENT $BODY is a uniform ERROR envelope
# ({"error":{"code","message","requestId"}}) whose code equals EXPECTED_CODE,
# whose message and requestId are non-empty, and whose response Content-Type is
# application/json. MUST be called immediately after the request under test,
# before the next http_call overwrites $BODY/$HDRS. Counts as one assertion.
check_error_envelope() {
    local expected_code="$1"
    local label="$2"
    local ct
    ct="$(response_content_type)"
    local ok=1
    local reason=""

    case "$ct" in
        application/json*) : ;;
        *) ok=0; reason="content-type '${ct:-<none>}' is not application/json" ;;
    esac

    if [ "$ok" -eq 1 ]; then
        if [ "$HAVE_JQ" -eq 1 ]; then
            local code msg rid
            code="$(jq -r '.error.code // empty' "$BODY" 2>/dev/null)"
            msg="$(jq -r '.error.message // empty' "$BODY" 2>/dev/null)"
            rid="$(jq -r '.error.requestId // empty' "$BODY" 2>/dev/null)"
            if [ "$code" != "$expected_code" ]; then
                ok=0; reason="error.code='${code:-<none>}' != '$expected_code'"
            elif [ -z "$msg" ]; then
                ok=0; reason="error.message is empty/absent"
            elif [ -z "$rid" ]; then
                ok=0; reason="error.requestId is empty/absent"
            fi
        else
            if ! grep -Eq "\"code\"[[:space:]]*:[[:space:]]*\"${expected_code}\"" "$BODY"; then
                ok=0; reason="error.code != '$expected_code'"
            elif ! grep -Eq '"message"[[:space:]]*:[[:space:]]*"[^"]+"' "$BODY"; then
                ok=0; reason="error.message is empty/absent"
            elif ! grep -Eq '"requestId"[[:space:]]*:[[:space:]]*"[^"]+"' "$BODY"; then
                ok=0; reason="error.requestId is empty/absent"
            fi
        fi
    fi

    if [ "$ok" -eq 1 ]; then
        record_pass "error-envelope: ${label} (code=${expected_code}, message+requestId present, application/json)"
    else
        record_fail "error-envelope: ${label} (${reason})"
    fi
}

# check_data_envelope LABEL IDENTIFYING_KEY
# Assert that the CURRENT $BODY is a uniform SUCCESS envelope whose top-level
# "data" is an object containing IDENTIFYING_KEY, and whose response
# Content-Type is application/json. MUST be called immediately after the request
# under test, before the next http_call overwrites $BODY/$HDRS. One assertion.
check_data_envelope() {
    local label="$1"
    local key="$2"
    local ct
    ct="$(response_content_type)"
    local ok=1
    local reason=""

    case "$ct" in
        application/json*) : ;;
        *) ok=0; reason="content-type '${ct:-<none>}' is not application/json" ;;
    esac

    if [ "$ok" -eq 1 ]; then
        if [ "$HAVE_JQ" -eq 1 ]; then
            if ! jq -e '(.data | type) == "object"' "$BODY" >/dev/null 2>&1; then
                ok=0; reason="top-level 'data' object absent"
            elif ! jq -e --arg k "$key" '.data | has($k)' "$BODY" >/dev/null 2>&1; then
                ok=0; reason="data.${key} absent"
            fi
        else
            if ! grep -Eq '"data"[[:space:]]*:[[:space:]]*\{' "$BODY"; then
                ok=0; reason="top-level 'data' object absent"
            elif ! grep -Eq "\"${key}\"[[:space:]]*:" "$BODY"; then
                ok=0; reason="data.${key} absent"
            fi
        fi
    fi

    if [ "$ok" -eq 1 ]; then
        record_pass "data-envelope: ${label} ({data:{...,${key},...}}, application/json)"
    else
        record_fail "data-envelope: ${label} (${reason})"
    fi
}

# jq_assert LABEL FILTER
# Precise JSON assertion over the CURRENT $BODY: FILTER must evaluate truthy for
# a PASS. These are the EXACT-schema checks the release gate depends on, so they
# require jq. Outside RELEASE_MODE (where jq may be absent) a missing jq records
# a NOTE and does not count; inside RELEASE_MODE jq is guaranteed present, so
# every such assertion runs. MUST be called immediately after the request under
# test, before the next http_call overwrites $BODY.
jq_assert() {
    local label="$1"
    local filter="$2"
    if [ "$HAVE_JQ" -ne 1 ]; then
        printf 'NOTE: %s requires jq; skipped (jq is mandatory in RELEASE_MODE)\n' \
            "$label" >&2
        return
    fi
    if jq -e "$filter" "$BODY" >/dev/null 2>&1; then
        record_pass "$label"
    else
        record_fail "$label"
    fi
}


# -----------------------------------------------------------------------------
# Test execution
# -----------------------------------------------------------------------------
printf '==> CardDemo REST/JSON API tests\n'
printf '    Target: %s\n' "$BASE_URL"
printf '    User:   %s\n\n' "$API_USER"

# ---- 1. Sign on (POST /signon) : expect 200, then capture the bearer token ---
# Mirrors User Example Flow 1: sign on -> bearer token -> authenticated inquiry.
printf '%s\n' '-- 1. Authentication --'
SIGNON_BODY="$(printf '{"userId":"%s","password":"%s"}' "$API_USER" "$API_PASS")"
status="$(http_call POST "${BASE_URL}/signon" "$SIGNON_BODY")"
check_status 200 "$status" "POST /signon"
check_data_envelope "POST /signon" "token"
TOKEN="$(extract_token)"
if [ -z "$TOKEN" ]; then
    # Non-fatal: continue so the negative/auth checks that do not need a valid
    # token still run and contribute to the summary.
    printf 'FATAL: no bearer token obtained from /signon; authenticated checks will not pass.\n' >&2
fi

# ---- 2. Positive inquiries (expect 200) with Authorization: Bearer $TOKEN ----
printf '\n-- 2. Positive inquiries (expect 200) --\n'
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}" "" auth)"
check_status 200 "$status" "GET /accounts/{acctId}"
check_data_envelope "GET /accounts/{acctId}" "accountId"
# Exact schema: every documented Account field present, and each of the five
# monetary fields is a signed decimal with exactly two fraction digits (the
# negative-amount matrix is enforced by the '^-?' below and over the corpus).
jq_assert "schema: account has all 11 required fields" \
    '.data|has("accountId") and has("activeStatus") and has("currentBalance") and has("creditLimit") and has("cashCreditLimit") and has("openDate") and has("expirationDate") and has("reissueDate") and has("currentCycleCredit") and has("currentCycleDebit") and has("groupId")'
jq_assert "schema: account monetary fields are signed 2dp decimals" \
    '[.data.currentBalance,.data.creditLimit,.data.cashCreditLimit,.data.currentCycleCredit,.data.currentCycleDebit]|all(type=="string" and test("^-?[0-9]+[.][0-9]{2}$"))'

status="$(http_call GET "${BASE_URL}/customers/${CUST_OK}" "" auth)"
check_status 200 "$status" "GET /customers/{custId}"
check_data_envelope "GET /customers/{custId}" "customerId"
# Exact schema: SSN / government id appear only in masked form and never raw.
jq_assert "schema: customer has customerId and no raw ssn/govtId key" \
    '(.data|has("customerId")) and ([.data|keys[]]|any(test("^(ssn|govtIssuedId|governmentIssuedId)$";"i"))|not)'

status="$(http_call GET "${BASE_URL}/cards/${CARD_OK}" "" auth)"
check_status 200 "$status" "GET /cards/{cardNum}"
check_data_envelope "GET /cards/{cardNum}" "cardNumberMasked"
# Exact schema: PAN masked (12 '*' + 4 digits), all Card fields present, no CVV.
jq_assert "schema: card masked PAN well-formed, 5 fields, no CVV" \
    '(.data.cardNumberMasked|type=="string" and test("^[*]{12}[0-9]{4}$")) and (.data|has("accountId") and has("embossedName") and has("expirationDate") and has("activeStatus")) and ([.data|keys[]]|any(test("cvv|securityCode";"i"))|not)'

# Card -> account/customer resolution (User Example Flow 2, step 1).
status="$(http_call GET "${BASE_URL}/xref/${CARD_OK}" "" auth)"
check_status 200 "$status" "GET /xref/{cardNum}"
check_data_envelope "GET /xref/{cardNum}" "accountId"
jq_assert "schema: xref has masked PAN + accountId + customerId only" \
    '(.data.cardNumberMasked|type=="string" and test("^[*]{12}[0-9]{4}$")) and (.data|has("accountId") and has("customerId"))'

# Account -> transaction list (User Example Flow 2, step 2). This is the
# CHANNEL/CONTAINER-backed operation; from the client the observable contract is
# the list envelope shape plus the 50-entry cap and truncated flag (C1).
status="$(http_call GET "${BASE_URL}/accounts/${LIST_ACCT}/transactions" "" auth)"
check_status 200 "$status" "GET /accounts/{acctId}/transactions"
check_data_envelope "GET /accounts/{acctId}/transactions" "transactions"
jq_assert "channel: list envelope shape (accountId,transactions[],count:int,truncated:bool)" \
    '(.data.accountId|type=="string") and (.data.transactions|type=="array") and (.data.count|type=="number") and (.data.truncated|type=="boolean")'
jq_assert "max-list: count==array length, count<=50, and truncated=>count==50" \
    '(.data.count == (.data.transactions|length)) and (.data.count <= 50) and ((.data.truncated|not) or (.data.count == 50))'
jq_assert "schema: every listed transaction has masked PAN + signed 2dp amount" \
    '.data.transactions|all((.cardNumberMasked|type=="string" and test("^[*]{12}[0-9]{4}$")) and (.amount|type=="string" and test("^-?[0-9]+[.][0-9]{2}$")) and has("transactionId"))'

status="$(http_call GET "${BASE_URL}/transactions/${TRAN_OK}" "" auth)"
check_status 200 "$status" "GET /transactions/{tranId}"
check_data_envelope "GET /transactions/{tranId}" "transactionId"
jq_assert "schema: transaction detail has masked PAN + signed 2dp amount" \
    '(.data.cardNumberMasked|type=="string" and test("^[*]{12}[0-9]{4}$")) and (.data.amount|type=="string" and test("^-?[0-9]+[.][0-9]{2}$")) and (.data|has("transactionId") and has("typeCode") and has("categoryCode"))'

# ---- 3. Not found (expect 404) ----------------------------------------------
printf '\n-- 3. Not found (expect 404) --\n'
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_BAD}" "" auth)"
check_status 404 "$status" "GET /accounts/{acctId} (unknown id)"
check_error_envelope NOT_FOUND "GET /accounts/{acctId} (unknown id)"

status="$(http_call GET "${BASE_URL}/customers/${CUST_BAD}" "" auth)"
check_status 404 "$status" "GET /customers/{custId} (unknown id)"
check_error_envelope NOT_FOUND "GET /customers/{custId} (unknown id)"

status="$(http_call GET "${BASE_URL}/cards/${CARD_BAD}" "" auth)"
check_status 404 "$status" "GET /cards/{cardNum} (unknown id)"
check_error_envelope NOT_FOUND "GET /cards/{cardNum} (unknown id)"

status="$(http_call GET "${BASE_URL}/transactions/${TRAN_BAD}" "" auth)"
check_status 404 "$status" "GET /transactions/{tranId} (unknown id)"
check_error_envelope NOT_FOUND "GET /transactions/{tranId} (unknown id)"

# ---- 4. Bad request (expect 400) --------------------------------------------
printf '\n-- 4. Bad request (expect 400) --\n'
# (a) Malformed JSON on sign-on.
status="$(http_call POST "${BASE_URL}/signon" '{ this is : not json')"
check_status 400 "$status" "POST /signon (malformed JSON)"
check_error_envelope BAD_REQUEST "POST /signon (malformed JSON)"

# (b) Unknown route under the API base.
status="$(http_call GET "${BASE_URL}/bogusroute" "" auth)"
check_status 400 "$status" "GET /bogusroute (unknown route)"
check_error_envelope BAD_REQUEST "GET /bogusroute (unknown route)"

# (c) Non-numeric path parameter.
status="$(http_call GET "${BASE_URL}/accounts/XYZ" "" auth)"
check_status 400 "$status" "GET /accounts/XYZ (non-numeric acct)"
check_error_envelope BAD_REQUEST "GET /accounts/XYZ (non-numeric acct)"

status="$(http_call GET "${BASE_URL}/accounts/ABC/transactions" "" auth)"
check_status 400 "$status" "GET /accounts/ABC/transactions (non-numeric acct)"
check_error_envelope BAD_REQUEST "GET /accounts/ABC/transactions (non-numeric acct)"

# ---- 5. Unauthorized (expect 401) -------------------------------------------
printf '\n-- 5. Unauthorized (expect 401) --\n'
# (a) No Authorization header on a protected resource.
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}")"
check_status 401 "$status" "GET /accounts/{acctId} (no token)"
check_error_envelope UNAUTHORIZED "GET /accounts/{acctId} (no token)"

# (b) Forged/invalid bearer token.
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}" "" "forged-invalid-token")"
check_status 401 "$status" "GET /accounts/{acctId} (forged token)"
check_error_envelope UNAUTHORIZED "GET /accounts/{acctId} (forged token)"

# ---- 6. Empty transaction list (expect 200, NOT 404) ------------------------
printf '\n-- 6. Empty transaction list (expect 200 + empty array) --\n'
status="$(http_call GET "${BASE_URL}/accounts/${EMPTY_ACCT}/transactions" "" auth)"
check_status 200 "$status" "GET /accounts/{acctId}/transactions (cardless -> empty)"
# Exact empty-list contract: the array MUST be empty AND count MUST be 0 AND
# truncated MUST be false. The previous OR let a body satisfy the check with
# only one of the two -- e.g. count 0 while the array was non-empty -- so the
# conjunction is required here.
if [ "$HAVE_JQ" -eq 1 ]; then
    if jq -e '((.data.transactions // ["x"]) | length) == 0
              and ((.data.count) == 0)
              and ((.data.truncated) == false)' \
        "$BODY" >/dev/null 2>&1; then
        record_pass "empty-list: transactions [] AND count 0 AND truncated false"
    else
        record_fail "empty-list: expected transactions [] AND count 0 AND truncated false"
    fi
else
    # Fallback: require an empty array AND count 0 (both must be present).
    if grep -Eq '"transactions"[[:space:]]*:[[:space:]]*\[[[:space:]]*\]' "$BODY" \
       && grep -Eq '"count"[[:space:]]*:[[:space:]]*0' "$BODY"; then
        record_pass "empty-list: transactions [] AND count 0"
    else
        record_fail "empty-list: expected transactions [] AND count 0"
    fi
fi

# ---- 7. Internal error (expect 500) -- fault-injected -----------------------
# The bearer token is sent on this request, so FAULT_PATH MUST be same-origin:
# an operator who could set FAULT_PATH to an arbitrary foreign URL would
# otherwise receive a valid token (credential exfiltration, CWE-200). We accept
# only (a) a path relative to the API base, (b) an absolute path on the target
# host, or (c) a full URL whose scheme://host:port exactly equals the target
# origin. Any other absolute URL is refused and recorded as a FAIL -- the token
# is never sent off-origin.
printf '\n-- 7. Internal error (expect 500) --\n'
if [ -n "$FAULT_PATH" ]; then
    ORIGIN="${API_SCHEME}://${API_HOST}:${API_PORT}"
    fault_url=""
    fault_reason=""
    case "$FAULT_PATH" in
        http://*|https://*)
            # Full URL: permit ONLY when it targets the exact same origin.
            case "$FAULT_PATH" in
                "${ORIGIN}"|"${ORIGIN}/"*) fault_url="$FAULT_PATH" ;;
                *) fault_reason="FAULT_PATH origin is not ${ORIGIN} (refused: token must never be sent off-origin)" ;;
            esac
            ;;
        //*)
            # Protocol-relative (//host/...) is a foreign origin: refuse.
            fault_reason="FAULT_PATH is protocol-relative (//...); a same-origin path or full ${ORIGIN} URL is required"
            ;;
        /*)
            # Absolute path on the target host.
            fault_url="${ORIGIN}${FAULT_PATH}"
            ;;
        *)
            # Path relative to the API base.
            fault_url="${BASE_URL}/${FAULT_PATH}"
            ;;
    esac

    if [ -n "$fault_url" ]; then
        status="$(http_call GET "$fault_url" "" auth)"
        check_status 500 "$status" "GET fault route (forced 500)"
        check_error_envelope INTERNAL_ERROR "GET fault route (forced 500)"
        # The 500 envelope must not leak internal CICS RESP2 detail.
        if grep -Eiq 'resp2' "$BODY"; then
            record_fail "500 body leaks internal RESP2 detail"
        else
            record_pass "500 body does not leak RESP2 detail"
        fi
    else
        # Malformed / cross-origin FAULT_PATH is always a hard failure (the
        # token would otherwise be exfiltrated); never a silent skip.
        record_fail "500 fault route rejected: ${fault_reason}"
    fi
elif [ "$RELEASE_MODE" -eq 1 ]; then
    # Release gate: the 500 path is mandatory. A missing fault route is a FAIL,
    # not a SKIP, so an incomplete run cannot pass the release gate.
    record_fail "500 path not exercised: FAULT_PATH is required in RELEASE_MODE"
else
    printf 'SKIP: 500 path (set FAULT_PATH to enable; mandatory in RELEASE_MODE)\n'
fi

# ---- 8. Extended mandatory scenarios ----------------------------------------
# These exercise behaviours the base flow did not: token lifecycle (duplicate
# sign-on issues distinct working tokens; a well-formed but unknown/expired
# token is rejected) and strict routing (an extra path segment is a 400, not a
# match). They are ordinary HTTP assertions and therefore run in every mode;
# their assertions are mandatory (a violation is a FAIL, never a skip).
printf '\n-- 8. Extended scenarios (token lifecycle + strict routing) --\n'

# (a) Duplicate sign-on: a second sign-on must succeed and yield a DISTINCT
#     token (collision-safe registry, AAP M3), and that second token must also
#     authenticate a protected inquiry. Exercises the token registry beyond the
#     single sign-on the base flow performs.
status="$(http_call POST "${BASE_URL}/signon" "$SIGNON_BODY")"
check_status 200 "$status" "POST /signon (second sign-on)"
check_data_envelope "POST /signon (second sign-on)" "token"
TOKEN2="$(extract_token)"
if [ -n "$TOKEN" ] && [ -n "$TOKEN2" ]; then
    if [ "$TOKEN" != "$TOKEN2" ]; then
        record_pass "duplicate-auth: second sign-on issued a distinct token"
    else
        record_fail "duplicate-auth: second sign-on reissued an identical token"
    fi
    status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}" "" "$TOKEN2")"
    check_status 200 "$status" "GET /accounts/{acctId} (second token authenticates)"
else
    record_fail "duplicate-auth: could not obtain two tokens to compare"
fi

# (b) Well-formed but UNKNOWN token (64 chars from the documented [0-9A-Z]
#     alphabet) must be rejected with 401. This is the same code path a valid
#     token follows once it has EXPIRED and been purged from the registry, so
#     it is the client-observable proxy for the expiry scenario.
UNKNOWN_TOKEN="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ012"
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}" "" "$UNKNOWN_TOKEN")"
check_status 401 "$status" "GET /accounts/{acctId} (well-formed unknown/expired token)"
check_error_envelope UNAUTHORIZED "GET /accounts/{acctId} (well-formed unknown/expired token)"

# (c) Strict routing: an extra path segment beyond a valid route must be a 400
#     (unknown route), not a spurious 200 (AAP M7 exact-segment enforcement).
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}/transactions/extra" "" auth)"
check_status 400 "$status" "GET /accounts/{acctId}/transactions/extra (extra segment)"
check_error_envelope BAD_REQUEST "GET /accounts/{acctId}/transactions/extra (extra segment)"

# (d) Strict routing: a trailing segment on a single-key resource is a 400.
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}/extra" "" auth)"
check_status 400 "$status" "GET /accounts/{acctId}/extra (extra segment)"
check_error_envelope BAD_REQUEST "GET /accounts/{acctId}/extra (extra segment)"


# -----------------------------------------------------------------------------
# Security assertions (scan the accumulated RESPONSE bodies only)
# -----------------------------------------------------------------------------
# Every failure increments FAILED. These are the four first-class data-handling
# rules from AAP section 0.6 and are treated as first-class test cases.
printf '\n-- Security assertions (over response bodies) --\n'

# 1. No full PAN. Match the KNOWN fixture PANs rather than a generic 16-digit
#    regex: legitimate 16-character transaction ids (e.g. 0000000000683580)
#    would otherwise false-positive. Additionally, every masked card value must
#    be well-formed: 12 asterisks followed by exactly four digits.
if grep -Eq '0500024453765740|4859452612877065' "$RESP_ALL"; then
    record_fail "security: a full PAN appears in a response body"
else
    record_pass "security: no full PAN (known fixture value) in any response body"
fi

# Generic (fixture-independent) raw-PAN detector: flag ANY JSON string value of
# 13-19 consecutive digits that appears under a key other than the handful that
# legitimately carry long all-digit strings (transactionId/tranId are 16-digit
# ids; id/token are opaque). A correctly masked PAN is "************1234"
# (asterisks + 4 digits) and never matches a pure-digit run, so this catches an
# unmasked PAN under ANY field name -- not just the shipped fixtures.
raw_pan_hits="$(grep -oE '"[A-Za-z0-9_]+"[[:space:]]*:[[:space:]]*"[0-9]{13,19}"' "$RESP_ALL" \
    | grep -viE '"(transactionId|tranId|id|token)"[[:space:]]*:' || true)"
if [ -n "$raw_pan_hits" ]; then
    record_fail "security: a raw 13-19 digit value appears under a non-id/token key (possible unmasked PAN)"
    # Print ONLY the offending key name and a redacted digit-count -- never the
    # value itself, which may be a live PAN. Echoing it into the log would
    # recreate the very leak this check exists to catch (CWE-532).
    printf '%s\n' "$raw_pan_hits" | head -5 | while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        k="$(printf '%s' "$hit" | sed -E 's/^"([A-Za-z0-9_]+)".*/\1/')"
        v="$(printf '%s' "$hit" | sed -E 's/.*:[[:space:]]*"([0-9]+)".*/\1/')"
        printf 'DETAIL: key=%s value=<redacted %d-digit numeric string>\n' \
            "$k" "${#v}"
    done
else
    record_pass "security: no raw 13-19 digit PAN-like value under any non-id/token key"
fi

bad_mask=0
mask_count=0
while IFS= read -r masked; do
    [ -z "$masked" ] && continue
    mask_count=$((mask_count + 1))
    if ! printf '%s' "$masked" | grep -Eq '^\*{12}[0-9]{4}$'; then
        bad_mask=1
        # A malformed "masked" value may in fact be an unmasked PAN, so log
        # only its length and the structural mismatch -- never the value
        # itself (CWE-532).
        printf 'DETAIL: malformed masked card value (len=%d; not ^*{12}[0-9]{4}$)\n' \
            "${#masked}"
    fi
done < <(grep -oE '"cardNumberMasked"[[:space:]]*:[[:space:]]*"[^"]*"' "$RESP_ALL" \
    | sed -E 's/.*:[[:space:]]*"([^"]*)".*/\1/')
# The mask check is only meaningful if at least one masked card value was
# actually observed; otherwise it would pass vacuously (the original defect:
# "checked 0"). Requiring mask_count > 0 makes a run that produced no card data
# -- e.g. an unreachable region where every body is empty -- FAIL here rather
# than report a hollow green.
if [ "$mask_count" -eq 0 ]; then
    record_fail "security: no masked card value (cardNumberMasked) observed in any response -- masking was never exercised"
elif [ "$bad_mask" -eq 0 ]; then
    record_pass "security: all ${mask_count} masked card value(s) well-formed (12 asterisks + 4 digits)"
else
    record_fail "security: at least one masked card value is malformed"
fi

# 1c. Monetary matrix (fixture-independent): EVERY value carried under a known
#     money key across ALL response bodies must be a signed decimal with exactly
#     two fraction digits (COBOL S9(n)V99 serialized with the sign and scale
#     preserved). This validates the negative-amount case too: a value such as
#     "-12.34" matches, while an unsigned/unscaled value like "1234" or "12.3"
#     is flagged. A malformed value is reported by key + length only (never the
#     digits), consistent with the redaction rule above.
bad_money=0
money_count=0
while IFS= read -r moneypair; do
    [ -z "$moneypair" ] && continue
    money_count=$((money_count + 1))
    mval="$(printf '%s' "$moneypair" | sed -E 's/.*:[[:space:]]*"([^"]*)".*/\1/')"
    if ! printf '%s' "$mval" | grep -Eq '^-?[0-9]+\.[0-9]{2}$'; then
        bad_money=1
        mkey="$(printf '%s' "$moneypair" | sed -E 's/^"([A-Za-z0-9_]+)".*/\1/')"
        printf 'DETAIL: malformed monetary value under key=%s (len=%d; not ^-?[0-9]+.[0-9]{2}$)\n' \
            "$mkey" "${#mval}"
    fi
done < <(grep -oE '"(currentBalance|creditLimit|cashCreditLimit|currentCycleCredit|currentCycleDebit|amount)"[[:space:]]*:[[:space:]]*"[^"]*"' "$RESP_ALL")
if [ "$money_count" -eq 0 ]; then
    # No monetary field observed at all means the money-bearing endpoints were
    # never successfully exercised -- a hollow pass, so fail (parity with the
    # mask_count guard above).
    record_fail "security: no monetary field observed in any response -- money scaling was never exercised"
elif [ "$bad_money" -eq 0 ]; then
    record_pass "security: all ${money_count} monetary value(s) are signed 2-decimal (negatives included)"
else
    record_fail "security: at least one monetary value is not a signed 2-decimal number"
fi

# 2. No card security code (CVV) field is ever serialized.
if grep -Eiq '"cvv"|"cardCvv"|"securityCode"' "$RESP_ALL"; then
    record_fail "security: a CVV/security-code field appears in a response body"
else
    record_pass "security: no CVV/security-code field in any response body"
fi

# 3. No unmasked SSN or government-issued id. Check both the known fixture
#    values and the field names (the JSON Customer omits them entirely).
if grep -Eq '020973888|00000000000049368437' "$RESP_ALL"; then
    record_fail "security: an unmasked SSN / government id value appears in a response body"
else
    record_pass "security: no unmasked SSN / government id value in any response body"
fi
if grep -Eiq '"ssn"|"govtIssuedId"|"governmentIssuedId"' "$RESP_ALL"; then
    record_fail "security: an SSN / government-id field name appears in a response body"
else
    record_pass "security: no SSN / government-id field name in any response body"
fi

# 4. No password is ever echoed. Check both the field name and the literal
#    password value (request bodies are never collected, so this cannot match
#    our own sign-on request).
if grep -Eiq '"password"' "$RESP_ALL"; then
    record_fail "security: a password field appears in a response body"
else
    record_pass "security: no password field in any response body"
fi
if grep -q "$API_PASS" "$RESP_ALL"; then
    record_fail "security: the password value appears in a response body"
else
    record_pass "security: password value never echoed in any response body"
fi

# -----------------------------------------------------------------------------
# Summary and exit
# -----------------------------------------------------------------------------
printf '\nSUMMARY: TESTS=%d PASSED=%d FAILED=%d\n' "$TESTS" "$PASSED" "$FAILED"
if [ "$FAILED" -eq 0 ]; then
    exit 0
else
    exit 1
fi

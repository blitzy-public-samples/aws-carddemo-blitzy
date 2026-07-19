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
#   API_PORT    TCPIPSERVICE listener port (operator-chosen)  (default: 8080)
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
#               SKIPPED (never counted as a failure).
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
API_PORT="${API_PORT:-8080}"
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

# -----------------------------------------------------------------------------
# Dependency checks
# -----------------------------------------------------------------------------
# curl is REQUIRED: without an HTTP client no check can run.
if ! command -v curl >/dev/null 2>&1; then
    printf 'ERROR: curl is required but was not found on PATH.\n' >&2
    exit 2
fi

# jq is OPTIONAL: when present it is used for precise JSON extraction; otherwise
# the script falls back to portable grep/sed parsing. HAVE_JQ is 1 or 0.
if command -v jq >/dev/null 2>&1; then
    HAVE_JQ=1
else
    HAVE_JQ=0
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
BODY="$(mktemp)"
RESP_ALL="$(mktemp)"
trap 'rm -f "$RESP_ALL" "$BODY"' EXIT

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

    local -a curl_args=(
        -s
        -o "$BODY"
        -w '%{http_code}'
        -X "$method"
        -H 'Accept: application/json'
    )

    if [ -n "$data" ]; then
        curl_args+=(-H 'Content-Type: application/json' --data "$data")
    fi

    if [ "$auth" = "auth" ]; then
        curl_args+=(-H "Authorization: Bearer ${TOKEN}")
    elif [ -n "$auth" ]; then
        curl_args+=(-H "Authorization: Bearer ${auth}")
    fi

    # Start from a clean body so a connection failure cannot leave stale content.
    : > "$BODY"

    local code
    code="$(curl "${curl_args[@]}" "$url")" || code="000"

    # Accumulate the RESPONSE body only, with a newline separator.
    cat "$BODY" >> "$RESP_ALL"
    printf '\n' >> "$RESP_ALL"

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

status="$(http_call GET "${BASE_URL}/customers/${CUST_OK}" "" auth)"
check_status 200 "$status" "GET /customers/{custId}"

status="$(http_call GET "${BASE_URL}/cards/${CARD_OK}" "" auth)"
check_status 200 "$status" "GET /cards/{cardNum}"

# Card -> account/customer resolution (User Example Flow 2, step 1).
status="$(http_call GET "${BASE_URL}/xref/${CARD_OK}" "" auth)"
check_status 200 "$status" "GET /xref/{cardNum}"

# Account -> transaction list (User Example Flow 2, step 2).
status="$(http_call GET "${BASE_URL}/accounts/${LIST_ACCT}/transactions" "" auth)"
check_status 200 "$status" "GET /accounts/{acctId}/transactions"

status="$(http_call GET "${BASE_URL}/transactions/${TRAN_OK}" "" auth)"
check_status 200 "$status" "GET /transactions/{tranId}"

# ---- 3. Not found (expect 404) ----------------------------------------------
printf '\n-- 3. Not found (expect 404) --\n'
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_BAD}" "" auth)"
check_status 404 "$status" "GET /accounts/{acctId} (unknown id)"

status="$(http_call GET "${BASE_URL}/customers/${CUST_BAD}" "" auth)"
check_status 404 "$status" "GET /customers/{custId} (unknown id)"

status="$(http_call GET "${BASE_URL}/cards/${CARD_BAD}" "" auth)"
check_status 404 "$status" "GET /cards/{cardNum} (unknown id)"

status="$(http_call GET "${BASE_URL}/transactions/${TRAN_BAD}" "" auth)"
check_status 404 "$status" "GET /transactions/{tranId} (unknown id)"

# ---- 4. Bad request (expect 400) --------------------------------------------
printf '\n-- 4. Bad request (expect 400) --\n'
# (a) Malformed JSON on sign-on.
status="$(http_call POST "${BASE_URL}/signon" '{ this is : not json')"
check_status 400 "$status" "POST /signon (malformed JSON)"

# (b) Unknown route under the API base.
status="$(http_call GET "${BASE_URL}/bogusroute" "" auth)"
check_status 400 "$status" "GET /bogusroute (unknown route)"

# (c) Non-numeric path parameter.
status="$(http_call GET "${BASE_URL}/accounts/XYZ" "" auth)"
check_status 400 "$status" "GET /accounts/XYZ (non-numeric acct)"

status="$(http_call GET "${BASE_URL}/accounts/ABC/transactions" "" auth)"
check_status 400 "$status" "GET /accounts/ABC/transactions (non-numeric acct)"

# ---- 5. Unauthorized (expect 401) -------------------------------------------
printf '\n-- 5. Unauthorized (expect 401) --\n'
# (a) No Authorization header on a protected resource.
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}")"
check_status 401 "$status" "GET /accounts/{acctId} (no token)"

# (b) Forged/invalid bearer token.
status="$(http_call GET "${BASE_URL}/accounts/${ACCT_OK}" "" "forged-invalid-token")"
check_status 401 "$status" "GET /accounts/{acctId} (forged token)"

# ---- 6. Empty transaction list (expect 200, NOT 404) ------------------------
printf '\n-- 6. Empty transaction list (expect 200 + empty array) --\n'
status="$(http_call GET "${BASE_URL}/accounts/${EMPTY_ACCT}/transactions" "" auth)"
check_status 200 "$status" "GET /accounts/{acctId}/transactions (cardless -> empty)"
if [ "$HAVE_JQ" -eq 1 ]; then
    if jq -e '((.data.transactions // []) | length) == 0 or ((.data.count // 0) == 0)' \
        "$BODY" >/dev/null 2>&1; then
        record_pass "empty-list: transactions array empty / count 0"
    else
        record_fail "empty-list: expected empty transactions array / count 0"
    fi
else
    if grep -Eq '"transactions"[[:space:]]*:[[:space:]]*\[[[:space:]]*\]|"count"[[:space:]]*:[[:space:]]*0' "$BODY"; then
        record_pass "empty-list: transactions array empty / count 0"
    else
        record_fail "empty-list: expected empty transactions array / count 0"
    fi
fi

# ---- 7. Internal error (expect 500) -- optional, fault-injected -------------
printf '\n-- 7. Internal error (expect 500) --\n'
if [ -n "$FAULT_PATH" ]; then
    # Accept a full URL, an absolute path, or a path relative to the API base.
    case "$FAULT_PATH" in
        http://*|https://*) fault_url="$FAULT_PATH" ;;
        /*)                 fault_url="${API_SCHEME}://${API_HOST}:${API_PORT}${FAULT_PATH}" ;;
        *)                  fault_url="${BASE_URL}/${FAULT_PATH}" ;;
    esac
    status="$(http_call GET "$fault_url" "" auth)"
    check_status 500 "$status" "GET fault route (forced 500)"
    # The 500 envelope must not leak internal CICS RESP2 detail.
    if grep -Eiq 'resp2' "$BODY"; then
        record_fail "500 body leaks internal RESP2 detail"
    else
        record_pass "500 body does not leak RESP2 detail"
    fi
else
    printf 'SKIP: 500 path (set FAULT_PATH to enable)\n'
fi


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
    record_pass "security: no full PAN in any response body"
fi

bad_mask=0
mask_count=0
while IFS= read -r masked; do
    [ -z "$masked" ] && continue
    mask_count=$((mask_count + 1))
    if ! printf '%s' "$masked" | grep -Eq '^\*{12}[0-9]{4}$'; then
        bad_mask=1
        printf 'DETAIL: malformed masked card value: %s\n' "$masked"
    fi
done < <(grep -oE '"cardNumberMasked"[[:space:]]*:[[:space:]]*"[^"]*"' "$RESP_ALL" \
    | sed -E 's/.*:[[:space:]]*"([^"]*)".*/\1/')
if [ "$bad_mask" -eq 0 ]; then
    record_pass "security: all masked card values well-formed (checked ${mask_count})"
else
    record_fail "security: at least one masked card value is malformed"
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


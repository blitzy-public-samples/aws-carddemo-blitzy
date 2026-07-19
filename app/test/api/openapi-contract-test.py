#!/usr/bin/env python3
# CardDemo REST/JSON API - OpenAPI 3.0 contract test.
# Self-contained validator that checks representative example responses for all
# seven CardDemo API endpoints (plus the uniform error envelope) against the
# schemas declared in app/api/openapi.yaml, and independently enforces the
# response envelope shapes, the monetary / masked-PAN string patterns, and the
# four first-class security exclusions (masked PAN only; no CVV; no SSN or
# government id; no password in any response).
#
# The test is intentionally dependency-light: it uses only the Python standard
# library plus the optional third-party packages PyYAML and jsonschema (and,
# when present, the modern "referencing" API used by jsonschema >= 4.18). If any
# optional package - or the spec file itself - is unavailable, the test prints a
# single SKIP line and exits 0 so it never blocks a build in a minimal
# environment. It NEVER installs packages and performs no network access.
#
# Licensed under the Apache License, Version 2.0:
#   http://www.apache.org/licenses/LICENSE-2.0
"""OpenAPI 3.0 contract test for the CardDemo REST/JSON API.

Run directly::

    python3 app/test/api/openapi-contract-test.py

The spec location may be overridden with the ``OPENAPI_SPEC`` environment
variable; otherwise it is resolved relative to this file at
``../../api/openapi.yaml``. The process exit code is ``1`` when any real
contract check fails and ``0`` on success or on a graceful SKIP. Non-fatal WARN
lines never force a nonzero exit on their own.
"""

import os
import sys
import re
import json
import warnings

# --- Optional dependency imports with graceful SKIP ------------------------
# PyYAML is required to parse the OpenAPI document; jsonschema powers the
# structural $ref validation layer. Either being absent yields a SKIP / exit 0
# rather than a hard failure, because this test must be safe to run in a
# stripped-down environment and must never attempt a network install.
try:
    import yaml
except Exception:
    print("SKIP: PyYAML not available")
    sys.exit(0)

try:
    import jsonschema  # noqa: F401  (imported to confirm availability)
    from jsonschema import Draft7Validator
except Exception:
    print("SKIP: jsonschema not available")
    sys.exit(0)


# --- Canonical patterns (kept in one place; also asserted against the spec) -
# MonetaryAmount: a signed decimal with exactly two fractional digits, e.g.
# "-12.34" or "194.00". MaskedPan: twelve asterisks followed by the last four
# digits, e.g. "************5740". These mirror the patterns declared in the
# spec's components.schemas.MonetaryAmount and components.schemas.MaskedPan.
MONEY_PATTERN = r"^-?\d+\.\d{2}$"
PAN_PATTERN = r"^\*{12}\d{4}$"
MONEY_RE = re.compile(MONEY_PATTERN)
PAN_RE = re.compile(PAN_PATTERN)

# Field names that carry a MonetaryAmount value in the response examples.
MONEY_FIELDS = frozenset({
    "currentBalance",
    "creditLimit",
    "cashCreditLimit",
    "currentCycleCredit",
    "currentCycleDebit",
    "amount",
})
# Field name that always carries a masked PAN in the response examples.
MASKED_PAN_FIELD = "cardNumberMasked"


# --- Result recording -------------------------------------------------------
# Real pass/fail checks are counted toward the SUMMARY. Warnings capture soft
# conditions (for example, an envelope schema name that is absent from the
# spec) and never, on their own, force a nonzero exit code.
_passes = []
_fails = []
_warns = []


def record_pass(label):
    """Record and print a passing check."""
    _passes.append(label)
    print("PASS: " + label)


def record_fail(detail):
    """Record and print a failing check."""
    _fails.append(detail)
    print("FAIL: " + detail)


def record_warn(detail):
    """Record and print a non-fatal warning."""
    _warns.append(detail)
    print("WARN: " + detail)


# --- Version-robust $ref validator -----------------------------------------
def validator_for(ref, root):
    """Return a ``Draft7Validator`` for a component ``$ref`` resolved against
    ``root``.

    ``ref`` is a JSON Pointer fragment such as
    ``"#/components/schemas/AccountResponse"`` and ``root`` is the full parsed
    OpenAPI document, which serves as the resolution base so nested
    ``$ref`` / ``allOf`` chains (for example Account -> MonetaryAmount) resolve.

    Two resolution strategies are supported so the test works across jsonschema
    releases: the modern ``referencing`` registry (jsonschema >= 4.18) is tried
    first, falling back to the deprecated ``RefResolver`` for older releases.
    """
    # Path A: referencing API (jsonschema >= 4.18).
    try:
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT7

        resource = Resource.from_contents(root, default_specification=DRAFT7)
        registry = Registry().with_resource(uri="urn:spec", resource=resource)
        # ref[1:] drops the leading '#'; the URN then points into the document.
        return Draft7Validator(
            {"$ref": "urn:spec#" + ref[1:]}, registry=registry
        )
    except Exception:
        # Fall through to the legacy resolver below.
        pass

    # Path B: legacy RefResolver (jsonschema < 4.18). The deprecation warning is
    # silenced because RefResolver is the only option on those older releases.
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        from jsonschema import RefResolver

        resolver = RefResolver.from_schema(root)
        return Draft7Validator({"$ref": ref}, resolver=resolver)


# --- Recursive traversal helper --------------------------------------------
def iter_kv(obj):
    """Yield every ``(key, value)`` pair found in nested dicts and lists.

    Dict entries are yielded as ``(key, value)`` and then recursed into; list
    items are recursed into without yielding a pair. Used by the structural
    checks to scan example payloads for field names regardless of nesting.
    """
    if isinstance(obj, dict):
        for key, value in obj.items():
            yield key, value
            yield from iter_kv(value)
    elif isinstance(obj, list):
        for item in obj:
            yield from iter_kv(item)


# --- Representative example payloads (inline, fixture-derived) --------------
# One success example per endpoint, each wrapped in the {"data": {...}} success
# envelope, plus an empty transaction-list variant and one error example in the
# {"error": {...}} failure envelope. Examples are kept inline (no external JSON,
# no fixture reads at runtime) so the deliverable stays self-contained. Values
# are derived from the app/data/ASCII fixtures (for example account
# 00000000001 with a 194.00 balance; card ending 5740 -> account 00000000050;
# transaction 0000000000683580). No example carries a CVV, SSN, government id,
# password, or an unmasked PAN.
SIGNON_EXAMPLE = {
    "data": {"token": "abc.def.ghi", "userType": "U", "expiresIn": 900}
}
ACCOUNT_EXAMPLE = {
    "data": {
        "accountId": "00000000001",
        "activeStatus": "Y",
        "currentBalance": "194.00",
        "creditLimit": "2020.00",
        "cashCreditLimit": "1020.00",
        "currentCycleCredit": "0.00",
        "currentCycleDebit": "0.00",
        "openDate": "2014-11-20",
        "expirationDate": "2025-05-20",
        "reissueDate": "2025-05-20",
        "groupId": "",
    }
}
CUSTOMER_EXAMPLE = {
    "data": {
        "customerId": "000000001",
        "firstName": "IMMANUEL",
        "lastName": "PUBLIC",
        "ficoCreditScore": 274,
    }
}
CARD_EXAMPLE = {
    "data": {
        "cardNumberMasked": "************5740",
        "accountId": "00000000050",
        "embossedName": "JOHN Q PUBLIC",
        "expirationDate": "2025-05-20",
        "activeStatus": "Y",
    }
}
XREF_EXAMPLE = {
    "data": {
        "cardNumberMasked": "************5740",
        "accountId": "00000000050",
        "customerId": "000000050",
    }
}
TRANSACTION_LIST_EXAMPLE = {
    "data": {
        "accountId": "00000000050",
        "transactions": [
            {
                "transactionId": "0000000000683580",
                "typeCode": "01",
                "categoryCode": "0001",
                "description": "POS PURCHASE - ACME STORE",
                "amount": "504.77",
                "cardNumberMasked": "************7065",
            }
        ],
        "count": 1,
    }
}
TRANSACTION_LIST_EMPTY_EXAMPLE = {
    "data": {"accountId": "00000000099", "transactions": [], "count": 0}
}
TRANSACTION_EXAMPLE = {
    "data": {
        "transactionId": "0000000000683580",
        "typeCode": "01",
        "categoryCode": "0001",
        "description": "POS PURCHASE - ACME STORE",
        "amount": "504.77",
        "cardNumberMasked": "************7065",
    }
}
ERROR_EXAMPLE = {
    "error": {
        "code": "NOT_FOUND",
        "message": "account not found",
        "requestId": "11111111-1111-1111-1111-111111111111",
    }
}

# Each success case is (label, envelope schema, inner schema, example). The
# empty transaction-list variant reuses the TransactionListResponse envelope so
# both the populated and empty shapes are exercised.
SUCCESS_CASES = [
    ("signon", "SignonResponse", "SignonData", SIGNON_EXAMPLE),
    ("getAccount", "AccountResponse", "Account", ACCOUNT_EXAMPLE),
    ("getCustomer", "CustomerResponse", "Customer", CUSTOMER_EXAMPLE),
    ("getCard", "CardResponse", "Card", CARD_EXAMPLE),
    ("getCardXref", "XrefResponse", "Xref", XREF_EXAMPLE),
    ("listAccountTransactions", "TransactionListResponse", "TransactionList",
     TRANSACTION_LIST_EXAMPLE),
    ("listAccountTransactionsEmpty", "TransactionListResponse",
     "TransactionList", TRANSACTION_LIST_EMPTY_EXAMPLE),
    ("getTransaction", "TransactionResponse", "Transaction",
     TRANSACTION_EXAMPLE),
]
# The single error case: (label, envelope schema, inner schema, example).
ERROR_CASE = ("error", "ErrorResponse", "Error", ERROR_EXAMPLE)

# The seven endpoint paths that must be declared in the spec (relative to the
# /carddemo/api/v1 server base).
REQUIRED_PATHS = [
    "/signon",
    "/accounts/{acctId}",
    "/customers/{custId}",
    "/cards/{cardNum}",
    "/xref/{cardNum}",
    "/accounts/{acctId}/transactions",
    "/transactions/{tranId}",
]
# The four error codes the contract must reference.
REQUIRED_ERROR_CODES = [
    "BAD_REQUEST",
    "UNAUTHORIZED",
    "NOT_FOUND",
    "INTERNAL_ERROR",
]


def error_envelope_ok(example):
    """Return ``(ok, detail)`` for the EXACT error-envelope shape.

    The top-level keys must be exactly ``{"error"}`` and the inner error
    object's keys must be exactly ``{"code", "message", "requestId"}`` - no
    more, no fewer. Factored out so it can be unit-tested independently of
    jsonschema.
    """
    top = set(example.keys())
    if top != {"error"}:
        return False, ("error example top-level keys %s != {'error'}"
                       % sorted(top))
    inner = example.get("error")
    if not isinstance(inner, dict):
        return False, "error example 'error' value is not an object"
    keys = set(inner.keys())
    expected = {"code", "message", "requestId"}
    if keys != expected:
        return False, ("error object keys %s != %s"
                       % (sorted(keys), sorted(expected)))
    return True, "error envelope shape exact"



# --- jsonschema validation layer -------------------------------------------
def run_schema_validation(spec):
    """Validate each wrapped example against its envelope schema.

    Component ``$ref`` chains are resolved against the full spec. When an
    envelope schema is absent, the inner resource schema is validated against
    the unwrapped payload instead; when neither is present, a non-fatal WARN is
    emitted and the structural checks below carry the contract guarantees.
    """
    schemas = spec.get("components", {}).get("schemas", {})
    for label, envelope, inner, example in SUCCESS_CASES + [ERROR_CASE]:
        if envelope in schemas:
            try:
                validator = validator_for(
                    "#/components/schemas/" + envelope, spec
                )
                errs = list(validator.iter_errors(example))
            except Exception as exc:  # defensive: never crash the whole run
                record_fail("%s: schema validation raised %r" % (label, exc))
                continue
            if errs:
                msgs = "; ".join(e.message for e in errs[:5])
                record_fail("%s does not satisfy %s: %s"
                            % (label, envelope, msgs))
            else:
                record_pass("%s validates against %s" % (label, envelope))
        elif inner in schemas:
            # Envelope missing; validate the unwrapped inner object instead.
            key = "error" if label == "error" else "data"
            payload = example.get(key)
            try:
                validator = validator_for(
                    "#/components/schemas/" + inner, spec
                )
                errs = list(validator.iter_errors(payload))
            except Exception as exc:
                record_fail("%s: inner schema validation raised %r"
                            % (label, exc))
                continue
            if errs:
                msgs = "; ".join(e.message for e in errs[:5])
                record_fail("%s inner does not satisfy %s: %s"
                            % (label, inner, msgs))
            else:
                record_pass("%s inner validates against %s" % (label, inner))
        else:
            record_warn("neither %s nor %s present in spec; relying on "
                        "structural checks for %s" % (envelope, inner, label))


# --- jsonschema-independent structural checks (always run) -----------------
def run_structural_checks(spec):
    """Run the nine structural checks that do not depend on jsonschema."""
    schemas = spec.get("components", {}).get("schemas", {})

    # 1. Success envelope: every success example's top-level keys == {"data"}.
    for label, _envelope, _inner, example in SUCCESS_CASES:
        top = set(example.keys())
        if top == {"data"}:
            record_pass("success envelope keys == {'data'} for " + label)
        else:
            record_fail("success example %s top-level keys %s != {'data'}"
                        % (label, sorted(top)))

    # 2. Error envelope EXACT shape.
    ok, detail = error_envelope_ok(ERROR_EXAMPLE)
    (record_pass if ok else record_fail)(detail)

    # 3. MonetaryAmount: every money field in the examples matches MONEY_RE.
    money_ok = True
    for label, _e, _i, example in SUCCESS_CASES:
        for key, value in iter_kv(example):
            if key in MONEY_FIELDS:
                if not (isinstance(value, str) and MONEY_RE.match(value)):
                    record_fail("money field %s=%r in %s does not match %s"
                                % (key, value, label, MONEY_PATTERN))
                    money_ok = False
    if money_ok:
        record_pass("all money fields match " + MONEY_PATTERN)
    # 3b. Spec's MonetaryAmount.pattern equals the canonical pattern.
    money_pat = schemas.get("MonetaryAmount", {}).get("pattern")
    if money_pat is None:
        record_warn("components.schemas.MonetaryAmount.pattern absent")
    elif money_pat == MONEY_PATTERN:
        record_pass("spec MonetaryAmount.pattern == " + MONEY_PATTERN)
    else:
        record_fail("spec MonetaryAmount.pattern %r != %r"
                    % (money_pat, MONEY_PATTERN))

    # 4. MaskedPan: every cardNumberMasked matches PAN_RE and is not raw digits.
    pan_ok = True
    for label, _e, _i, example in SUCCESS_CASES:
        for key, value in iter_kv(example):
            if key == MASKED_PAN_FIELD:
                if not (isinstance(value, str) and PAN_RE.match(value)):
                    record_fail("masked PAN %s=%r in %s does not match %s"
                                % (key, value, label, PAN_PATTERN))
                    pan_ok = False
                elif re.fullmatch(r"\d{16}", value):
                    record_fail("masked PAN %s=%r in %s is a raw 16-digit PAN"
                                % (key, value, label))
                    pan_ok = False
    if pan_ok:
        record_pass("all masked PAN fields match " + PAN_PATTERN)
    # 4b. Spec's MaskedPan.pattern equals the canonical pattern.
    pan_pat = schemas.get("MaskedPan", {}).get("pattern")
    if pan_pat is None:
        record_warn("components.schemas.MaskedPan.pattern absent")
    elif pan_pat == PAN_PATTERN:
        record_pass("spec MaskedPan.pattern == " + PAN_PATTERN)
    else:
        record_fail("spec MaskedPan.pattern %r != %r" % (pan_pat, PAN_PATTERN))

    # 5. Card schema and card example must contain no CVV.
    cvv_re = re.compile(r"cvv", re.I)
    card_props = schemas.get("Card", {}).get("properties", {})
    cvv_props = [k for k in card_props if cvv_re.search(k)]
    if cvv_props:
        record_fail("Card schema exposes CVV-like properties: %s" % cvv_props)
    else:
        record_pass("Card schema has no CVV property")
    cvv_keys = [k for k, _v in iter_kv(CARD_EXAMPLE) if cvv_re.search(k)]
    if cvv_keys:
        record_fail("card example exposes CVV-like keys: %s" % cvv_keys)
    else:
        record_pass("card example has no CVV key")

    # 6. Customer schema and example must contain no SSN / government id.
    ssn_re = re.compile(r"ssn|govt|government", re.I)
    cust_props = schemas.get("Customer", {}).get("properties", {})
    ssn_props = [k for k in cust_props if ssn_re.search(k)]
    if ssn_props:
        record_fail("Customer schema exposes SSN/govt properties: %s"
                    % ssn_props)
    else:
        record_pass("Customer schema has no SSN/govt property")
    ssn_keys = [k for k, _v in iter_kv(CUSTOMER_EXAMPLE) if ssn_re.search(k)]
    if ssn_keys:
        record_fail("customer example exposes SSN/govt keys: %s" % ssn_keys)
    else:
        record_pass("customer example has no SSN/govt key")

    # 7. No 'password' key anywhere in any success or error response example.
    #    (SignonRequest is a request schema and is intentionally not examined.)
    pw_hits = []
    for _label, _e, _i, example in SUCCESS_CASES:
        pw_hits += [k for k, _v in iter_kv(example) if k.lower() == "password"]
    pw_hits += [k for k, _v in iter_kv(ERROR_EXAMPLE) if k.lower() == "password"]
    if pw_hits:
        record_fail("response examples contain 'password' key(s): %s" % pw_hits)
    else:
        record_pass("no 'password' key in any response example")

    # 8. All seven endpoint paths must be declared.
    paths = spec.get("paths", {})
    missing_paths = [p for p in REQUIRED_PATHS if p not in paths]
    if missing_paths:
        record_fail("spec is missing required paths: %s" % missing_paths)
    else:
        record_pass("all seven endpoint paths present")

    # 9. All four error codes must be referenced somewhere in the spec.
    spec_text = json.dumps(spec)
    missing_codes = [c for c in REQUIRED_ERROR_CODES if c not in spec_text]
    if missing_codes:
        record_fail("spec does not reference error codes: %s" % missing_codes)
    else:
        record_pass("all four error codes referenced in spec")


# --- Spec resolution and entry point ---------------------------------------
def resolve_spec_path():
    """Resolve the OpenAPI spec path from ``OPENAPI_SPEC`` or relative to here.

    The environment override lets the same test drive an alternate contract
    (used by the self-tests); otherwise the spec is located next to the API
    programs at ``app/api/openapi.yaml`` relative to this test file.
    """
    override = os.environ.get("OPENAPI_SPEC")
    if override:
        return override
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "..", "..", "api", "openapi.yaml")


def main():
    """Load the spec and run every check. Returns the process exit code."""
    spec_path = resolve_spec_path()
    if not os.path.isfile(spec_path):
        # The spec is authored by a sibling program and may be absent when this
        # test is run standalone; that is a graceful SKIP, not a failure.
        print("SKIP: openapi.yaml not found at " + spec_path)
        return 0
    try:
        with open(spec_path, "r", encoding="utf-8") as handle:
            spec = yaml.safe_load(handle)
    except Exception as exc:
        print("FAIL: could not parse %s: %r" % (spec_path, exc))
        return 1
    if not isinstance(spec, dict):
        print("FAIL: %s did not parse to a mapping" % spec_path)
        return 1

    print("INFO: validating contract at " + spec_path)
    run_schema_validation(spec)
    run_structural_checks(spec)

    checks = len(_passes) + len(_fails)
    print("SUMMARY: checks=%d passed=%d failed=%d"
          % (checks, len(_passes), len(_fails)))
    if _warns:
        print("SUMMARY: warnings=%d" % len(_warns))
    return 1 if _fails else 0


if __name__ == "__main__":
    sys.exit(main())

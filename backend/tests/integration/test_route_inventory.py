# Integration tests for the mounted REST route inventory (app/main.py +
# app/api/v1/__init__.py). Traceability: AAP section 0.5.5 (the frozen REST
# endpoint list) and QA findings C07/C08.
#
#   QA issue C08: the API surface must expose EXACTLY the twenty operations
#   enumerated in AAP 0.5.5 -- no more, no fewer. The review found no test that
#   pinned the mounted route inventory, so a router accidentally re-adding the
#   removed by-account card helpers, or dropping/renaming an endpoint, would
#   pass CI silently. This module asserts the mounted operation set is byte-for-
#   byte the frozen contract, guarding against drift in BOTH directions
#   (missing AND extra routes) because set equality fails on either.
#
#   QA issue C07: the by-account card helper routes (GET/PUT keyed by account,
#   resolving one card with .limit(1)) were removed because they silently
#   selected the WRONG card on the non-unique account->card relationship. A
#   dedicated assertion proves no card route nested under /accounts/ is ever
#   remounted.
#
#   The two /health probes documented alongside the twenty /api/v1 operations
#   (readiness + liveness) are pinned separately so their loss is also caught.
#
# The tests inspect the live ``app`` singleton (the exact object app.main
# serves and conftest drives), so they exercise the real mounted surface
# without a database or HTTP client. Ochs naming (AAP 0.8.2 / 0.8.3) is
# honored: snake_case test-function names + file name, PascalCase helpers,
# camelCase locals, ALL_UPPERCASE module constants, 4-space indentation.
"""Integration tests pinning the mounted REST route inventory (QA C07/C08).

These tests enumerate every ``APIRoute`` on the running application and assert
the ``/api/v1`` operation set equals the twenty-operation contract frozen in
AAP section 0.5.5, that the removed by-account card helper routes are absent,
that ``POST /auth/logout`` is present, and that the two ``/health`` probes are
mounted. Set equality catches silent route drift in both directions -- a
newly-added or removed operation fails the contract test immediately.
"""

from fastapi.routing import APIRoute

from app.core.config import settings
from app.main import app

# Starlette adds HEAD for every GET route and OPTIONS for CORS preflight; they
# are transport artifacts, not part of the declared REST contract, so they are
# excluded from every enumeration below.
AUTO_METHODS = frozenset({"HEAD", "OPTIONS"})

# The mount prefix (``/api/v1``) sourced from settings so the expected paths
# stay correct if the prefix is ever reconfigured.
API_PREFIX = settings.API_V1_PREFIX

# The frozen REST contract (AAP 0.5.5), expressed as (METHOD, suffix) pairs
# RELATIVE to API_PREFIX. Exactly twenty operations: one row per legacy CICS
# transaction plus the logout, billpay-lookup, and admin-user-fetch support
# reads. Any addition or removal here must be a deliberate, AAP-backed change.
EXPECTED_API_OPERATIONS = frozenset(
    {
        ("POST", "/auth/login"),  # CC00 COSGN00C sign-on
        ("POST", "/auth/logout"),  # session revocation (support read)
        ("GET", "/menu"),  # CM00 COMEN01C regular menu
        ("GET", "/admin/menu"),  # CA00 COADM01C admin menu
        ("GET", "/accounts/{acctId}"),  # CAVW COACTVWC view
        ("PUT", "/accounts/{acctId}"),  # CAUP COACTUPC update
        ("GET", "/cards"),  # CCLI COCRDLIC list
        ("GET", "/cards/{cardNum}"),  # CCDL COCRDSLC view (PAN-keyed)
        ("PUT", "/cards/{cardNum}"),  # CCUP COCRDUPC update (PAN-keyed)
        ("GET", "/transactions"),  # CT00 COTRN00C list
        ("POST", "/transactions"),  # CT02 COTRN02C add
        ("GET", "/transactions/{tranId}"),  # CT01 COTRN01C view
        ("GET", "/reports/transactions"),  # CR00 CORPT00C report
        ("POST", "/billpay"),  # CB00 COBIL00C pay
        ("GET", "/billpay/{acctId}"),  # billpay available-credit lookup
        ("GET", "/admin/users"),  # CU00 COUSR00C list
        ("POST", "/admin/users"),  # CU01 COUSR01C add
        ("GET", "/admin/users/{userId}"),  # user fetch (update prefill)
        ("PUT", "/admin/users/{userId}"),  # CU02 COUSR02C update
        ("DELETE", "/admin/users/{userId}"),  # CU03 COUSR03C delete
    }
)

# The exact operation count the contract guarantees (AAP 0.5.5).
EXPECTED_API_OPERATION_COUNT = 20

# The health probes mounted OUTSIDE the versioned API prefix (liveness +
# readiness). Documented as the +2 HTTP operations beyond the twenty /api/v1
# operations, for twenty-two total.
EXPECTED_HEALTH_OPERATIONS = frozenset(
    {
        ("GET", "/health"),
        ("GET", "/health/ready"),
    }
)

# The logout operation whose presence C08 specifically requires be asserted.
LOGOUT_OPERATION = ("POST", "/auth/logout")


def CollectMountedOperations(fastapiApp) -> set:
    """Return every mounted operation as a set of ``(METHOD, PATH)`` tuples.

    Only ``APIRoute`` instances are considered (Mount/WebSocket/static routes
    are ignored), and the auto-generated HEAD/OPTIONS verbs are stripped so the
    result reflects the declared REST contract rather than transport artifacts.

    Args:
        fastapiApp: The FastAPI application whose routes are enumerated.

    Returns:
        A set of ``(method, full_path)`` tuples for all mounted API routes.
    """
    operations = set()
    for route in fastapiApp.routes:
        if not isinstance(route, APIRoute):
            continue
        for httpMethod in route.methods - AUTO_METHODS:
            operations.add((httpMethod, route.path))
    return operations


def ApiOperationSuffixes(operations, apiPrefix) -> set:
    """Return the prefixed operations as ``(METHOD, suffix)`` tuples.

    Operations whose path does not start with ``apiPrefix`` (for example the
    ``/health`` probes) are excluded so the result can be compared directly to
    the prefix-relative ``EXPECTED_API_OPERATIONS`` contract.

    Args:
        operations: The full ``(method, path)`` set from
            :func:`CollectMountedOperations`.
        apiPrefix: The versioned mount prefix (``/api/v1``) to strip.

    Returns:
        A set of ``(method, suffix)`` tuples for the versioned API surface.
    """
    suffixes = set()
    for httpMethod, path in operations:
        if path.startswith(apiPrefix):
            suffixes.add((httpMethod, path[len(apiPrefix):]))
    return suffixes


def test_api_v1_operation_set_matches_frozen_contract() -> None:
    """The mounted /api/v1 surface equals the AAP 0.5.5 twenty-operation set.

    Set equality fails on ANY drift -- a missing endpoint, a renamed path, a
    changed verb, or an extra operation -- so this single assertion is the
    primary guard against the silent route-inventory drift flagged by C08.
    """
    mountedOperations = CollectMountedOperations(app)
    apiOperations = ApiOperationSuffixes(mountedOperations, API_PREFIX)
    assert apiOperations == EXPECTED_API_OPERATIONS, (
        "Mounted /api/v1 operations diverged from the frozen AAP 0.5.5 "
        f"contract.\n  Unexpected extras: {apiOperations - EXPECTED_API_OPERATIONS}"
        f"\n  Missing required : {EXPECTED_API_OPERATIONS - apiOperations}"
    )


def test_api_v1_operation_count_is_twenty() -> None:
    """Exactly twenty operations are mounted under /api/v1 (AAP 0.5.5)."""
    mountedOperations = CollectMountedOperations(app)
    apiOperations = ApiOperationSuffixes(mountedOperations, API_PREFIX)
    assert len(apiOperations) == EXPECTED_API_OPERATION_COUNT


def test_no_by_account_card_routes_mounted() -> None:
    """No card route nested under /accounts/ is mounted (QA C07/C08).

    The removed by-account helpers resolved an account to a single card with
    ``.limit(1)``, silently selecting the wrong card on the non-unique
    account->card relationship. This proves none was re-added.
    """
    mountedOperations = CollectMountedOperations(app)
    apiOperations = ApiOperationSuffixes(mountedOperations, API_PREFIX)
    byAccountCardRoutes = {
        (httpMethod, suffix)
        for httpMethod, suffix in apiOperations
        if suffix.startswith("/accounts/") and "card" in suffix.lower()
    }
    assert byAccountCardRoutes == set(), (
        "By-account card helper routes were re-mounted (C07/C08 regression): "
        f"{byAccountCardRoutes}"
    )


def test_logout_operation_is_mounted() -> None:
    """POST /auth/logout is present in the mounted API surface (C08)."""
    mountedOperations = CollectMountedOperations(app)
    apiOperations = ApiOperationSuffixes(mountedOperations, API_PREFIX)
    assert LOGOUT_OPERATION in apiOperations


def test_health_probes_are_mounted() -> None:
    """Both /health liveness and /health/ready readiness probes are mounted.

    These two operations sit outside the /api/v1 prefix and account for the +2
    HTTP operations documented beyond the twenty API operations (twenty-two
    total). Pinning them guards their loss during router refactors.
    """
    mountedOperations = CollectMountedOperations(app)
    healthOperations = {
        (httpMethod, path)
        for httpMethod, path in mountedOperations
        if path.startswith("/health")
    }
    assert healthOperations == EXPECTED_HEALTH_OPERATIONS

# Unit tests for app.schemas.common.RequestBase control-character rejection.
# Traceability: QA finding F4 ("Online Security Gate"). A request DTO carrying
# an ASCII control character (notably the NUL U+0000) previously reached
# asyncpg/bcrypt and surfaced as an unhandled HTTP 500. RequestBase now runs a
# model_validator(mode="after") that walks every string field through
# app.utils.validators.ValidateNoControlChars, turning that malformed input
# into a bounded validation error (HTTP 422 at the API boundary).
#
# These are pure-model tests: NO database, NO fixtures, NO network. They are
# synchronous plain ``def test_*`` functions, so the project-wide
# asyncio_mode="auto" never applies. Ochs naming (AAP 0.8.2 / 0.8.3) is honored:
# snake_case test-function names (the pytest discovery contract) and file name,
# camelCase local variables, 4-space indentation, one asserted behavior per
# test. LoginRequest is used as the representative concrete RequestBase subclass
# because it has the smallest required-field surface (user_id + password).

import pytest
from pydantic import ValidationError

from app.schemas.auth import LoginRequest
from app.schemas.common import (
    DEFAULT_PAGE_SIZE,
    MAX_PAGE_SIZE,
    PaginationParams,
)

# The legacy card-browse page size (F-004, program COCRDLIC): exactly seven
# rows per screen. Pinned as a named constant so the parity assertions below
# read as intent, not a magic number.
LEGACY_CARD_BROWSE_PAGE_SIZE = 7

# A clean, in-width credential pair (both fields are PIC X(8), max length 8).
CLEAN_USER_ID = "ADMIN001"
CLEAN_PASSWORD = "PASSWORD"


def test_request_base_accepts_clean_string_fields():
    # Ordinary printable credentials must construct without error -- proves the
    # control-character guard does not over-reject legitimate input.
    loginModel = LoginRequest(user_id=CLEAN_USER_ID, password=CLEAN_PASSWORD)
    assert loginModel.user_id == CLEAN_USER_ID
    assert loginModel.password == CLEAN_PASSWORD


def test_request_base_rejects_nul_in_password():
    # A NUL byte in the password field (the reproduced payload) must raise a
    # bounded ValidationError rather than flow into bcrypt as an HTTP 500.
    with pytest.raises(ValidationError) as excInfo:
        LoginRequest(user_id=CLEAN_USER_ID, password="AB\x00CD")
    # The message names the offending field but must never echo the raw NUL.
    errorText = str(excInfo.value)
    assert "control characters" in errorText
    assert "\x00" not in errorText


def test_request_base_rejects_nul_in_user_id():
    # The guard walks EVERY string field, not just the password: a control
    # character in user_id is rejected identically.
    with pytest.raises(ValidationError):
        LoginRequest(user_id="AD\x00MIN", password=CLEAN_PASSWORD)


def test_request_base_rejects_c0_and_del_control_chars():
    # Representative C0 controls (TAB, LF, CR, unit-separator) plus DEL (U+007F)
    # are each rejected in a string field.
    for controlChar in ("\t", "\n", "\r", "\x1f", "\x7f"):
        with pytest.raises(ValidationError):
            LoginRequest(user_id=CLEAN_USER_ID, password=f"PW{controlChar}X")


# ---------------------------------------------------------------------------
# Pagination default parity (QA finding I27, Test Quality).
#
# F-004 (legacy program COCRDLIC) fixes the card-browse screen at at most seven
# rows per page. In the modern stack that limit lives as a single named
# constant, app.schemas.common.DEFAULT_PAGE_SIZE, which supplies the default for
# PaginationParams.page_size. I27 observed that mutating that constant from 7 to
# 8 left the ENTIRE backend suite green -- the legacy seven-row contract had no
# direct assertion pinning it. The three tests below close that gap: the first
# two fail the moment the constant (or the DTO default it feeds) drifts off 7,
# killing the surviving 7 -> 8 mutation; the third keeps the default coherent
# with its configured upper bound.
# ---------------------------------------------------------------------------


def test_default_page_size_constant_matches_legacy_card_browse_limit():
    # Pin the F-004 / COCRDLIC contract directly on the source-of-truth
    # constant. This is the assertion whose absence let the 7 -> 8 mutation
    # survive the whole backend suite (I27).
    assert DEFAULT_PAGE_SIZE == LEGACY_CARD_BROWSE_PAGE_SIZE


def test_pagination_params_default_page_size_is_legacy_card_browse_limit():
    # The constant only matters if it actually reaches the value a caller
    # receives when no page_size is supplied. Construct PaginationParams with no
    # arguments and assert the resolved default equals the legacy seven-row
    # limit -- this also fails under the 7 -> 8 mutation.
    paginationParams = PaginationParams()
    assert paginationParams.page_size == LEGACY_CARD_BROWSE_PAGE_SIZE


def test_pagination_params_default_page_size_within_max_bound():
    # Defense-in-depth: the legacy default must remain a legal value (never
    # above the configured maximum), keeping the constant and its bound coherent
    # if either is edited later.
    assert DEFAULT_PAGE_SIZE <= MAX_PAGE_SIZE

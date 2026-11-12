# Unit tests for the validation-error NaN/Infinity sanitizer in app.main.
# Traceability: QA SECURITY finding (dest gate) -- an unauthenticated caller can
#   smuggle an IEEE-754 NaN/Infinity onto any JSON body field via a literal such
#   as 1e400/NaN. Python's json.loads accepts it, but FastAPI's JSONResponse
#   renders with json.dumps(allow_nan=False), which RAISES on such a value. When
#   that raise happens inside the 422 validation-error handler, Starlette turns
#   the intended 422 into an unhandled 500 (a pre-auth denial-of-service). The
#   handlers therefore run every encoded error payload through
#   app.main._ReplaceNonFiniteFloats, which these tests pin directly.
#
# These are pure-function tests: NO database, NO fixtures, NO network. They are
# synchronous plain ``def test_*`` functions -- the project-wide
# asyncio_mode="auto" does not apply because none are coroutines. Ochs naming
# (AAP 0.8.2 / 0.8.3) is honored: snake_case test-function names (the pytest
# discovery contract), camelCase local variables, ALL_UPPERCASE module-level
# constants, 4-space indentation, and one asserted behavior per test.
#
# Importing ``app.main`` is safe here: the parent ``tests/conftest.py`` seeds a
# throwaway SECRET_KEY / ENVIRONMENT before any ``app.*`` import during
# collection, exactly as every integration test relies on.
"""Unit tests for ``app.main._ReplaceNonFiniteFloats`` (NaN/Infinity DoS fix)."""

from __future__ import annotations

import json
import math

import pytest

from app.main import NON_FINITE_FLOAT_PLACEHOLDER, _ReplaceNonFiniteFloats

# The three IEEE-754 special float values a caller can inject through a JSON
# literal (1e400 -> inf, -1e400 -> -inf, NaN -> nan). json.dumps(allow_nan=False)
# -- the encoder FastAPI's JSONResponse uses -- raises ValueError on each.
NON_FINITE_FLOATS = [float("inf"), float("-inf"), float("nan")]

# A representative structure mirroring one entry of RequestValidationError.errors()
# whose offending ``input`` carries a non-finite float nested inside a container.
NESTED_ERROR_INPUT = {"outer": [1.5, float("inf"), {"inner": float("nan")}], "ok": "x"}


@pytest.mark.parametrize("nonFiniteValue", NON_FINITE_FLOATS)
def test_replaces_scalar_non_finite_float(nonFiniteValue):
    # Each non-finite scalar float is replaced by the fixed placeholder string.
    result = _ReplaceNonFiniteFloats(nonFiniteValue)
    assert result == NON_FINITE_FLOAT_PLACEHOLDER


@pytest.mark.parametrize("finiteValue", [0.0, 1.5, -12.34, 3.14e10])
def test_preserves_finite_float(finiteValue):
    # Ordinary finite floats pass through structurally unchanged.
    result = _ReplaceNonFiniteFloats(finiteValue)
    assert result == finiteValue


@pytest.mark.parametrize("scalar", ["text", 42, True, None])
def test_preserves_non_float_scalars(scalar):
    # Strings, ints, bools and None are returned unchanged (identity for scalars).
    result = _ReplaceNonFiniteFloats(scalar)
    assert result == scalar
    assert type(result) is type(scalar)


def test_replaces_non_finite_nested_in_containers():
    # Non-finite floats are replaced at any depth (list -> dict -> value) while
    # every finite value and key is preserved.
    result = _ReplaceNonFiniteFloats(NESTED_ERROR_INPUT)
    assert result["ok"] == "x"
    assert result["outer"][0] == 1.5
    assert result["outer"][1] == NON_FINITE_FLOAT_PLACEHOLDER
    assert result["outer"][2]["inner"] == NON_FINITE_FLOAT_PLACEHOLDER


def test_normalizes_tuple_to_list():
    # A tuple (e.g. a Pydantic ``loc``) is walked and returned as a list so the
    # result is JSON-serializable, with any non-finite member replaced.
    result = _ReplaceNonFiniteFloats(("body", float("inf"), "user_id"))
    assert result == ["body", NON_FINITE_FLOAT_PLACEHOLDER, "user_id"]


def test_result_is_json_dumpable_with_allow_nan_false():
    # The end-to-end guarantee: after sanitizing, the structure serializes under
    # the exact encoder FastAPI's JSONResponse uses (allow_nan=False) WITHOUT
    # raising -- which is precisely what turns the 500 back into a clean 422.
    sanitized = _ReplaceNonFiniteFloats({"detail": [{"input": float("inf")}]})
    body = json.dumps(sanitized, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    assert NON_FINITE_FLOAT_PLACEHOLDER in body


def test_raw_non_finite_would_break_json_dumps_control():
    # Control assertion documenting the underlying failure mode: the SAME payload
    # WITHOUT sanitization raises under allow_nan=False. This is the exact raise
    # that, inside the 422 handler, produced the unhandled 500.
    assert not math.isfinite(float("inf"))
    with pytest.raises(ValueError):
        json.dumps({"input": float("inf")}, allow_nan=False)

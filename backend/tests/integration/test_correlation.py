"""Request-correlation-id propagation tests (QA finding M-32).

Covers the dependency-free correlation primitive in
:mod:`app.core.correlation` (id sanitization, the context-variable bind/get/reset
lifecycle, and the log filter that stamps ``correlation_id`` onto every record)
and the end-to-end HTTP behavior wired in :mod:`app.main`: every request receives
a validated correlation id -- a sanitized client-supplied ``X-Request-ID`` /
``X-Correlation-ID`` ingress header when offered, otherwise a fresh id -- which is
echoed on the ``X-Request-ID`` response header of both a normal response and the
sanitized HTTP 500, with the 500 body's ``correlation_id`` matching that header.

There is no legacy COBOL origin: this is modern observability infrastructure.
"""

import logging

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.core import correlation as corr
from app.main import create_application

# Set of lowercase hex characters a freshly generated (uuid4().hex) id contains.
HEX_CHARACTERS = set("0123456789abcdef")

# Length of a freshly generated correlation id (uuid4 hex, dashes removed).
GENERATED_ID_LENGTH = 32


# ---------------------------------------------------------------------------
# Unit tests -- app.core.correlation primitive (no HTTP needed).
# ---------------------------------------------------------------------------


def test_sanitize_preserves_a_valid_client_id():
    assert corr.SanitizeCorrelationId("trace-abc_1.2") == "trace-abc_1.2"


def test_sanitize_trims_surrounding_whitespace():
    assert corr.SanitizeCorrelationId("  req-77  ") == "req-77"


def test_sanitize_rejects_invalid_characters_and_generates_fresh():
    generatedId = corr.SanitizeCorrelationId("bad id with spaces")
    assert len(generatedId) == GENERATED_ID_LENGTH
    assert set(generatedId) <= HEX_CHARACTERS


def test_sanitize_rejects_crlf_log_forging_attempt():
    # A newline-bearing value could forge a second log line; it must be rejected.
    generatedId = corr.SanitizeCorrelationId("ok\r\nX-Injected: 1")
    assert len(generatedId) == GENERATED_ID_LENGTH


def test_sanitize_rejects_overlong_id():
    overlongCandidate = "a" * (corr.MAX_CORRELATION_ID_LENGTH + 1)
    assert len(corr.SanitizeCorrelationId(overlongCandidate)) == GENERATED_ID_LENGTH


def test_sanitize_accepts_exact_maximum_length():
    boundaryId = "a" * corr.MAX_CORRELATION_ID_LENGTH
    assert corr.SanitizeCorrelationId(boundaryId) == boundaryId


def test_sanitize_none_and_empty_generate_fresh():
    assert len(corr.SanitizeCorrelationId(None)) == GENERATED_ID_LENGTH
    assert len(corr.SanitizeCorrelationId("")) == GENERATED_ID_LENGTH


def test_bind_get_reset_roundtrip():
    assert corr.GetCorrelationId() == corr.ABSENT_CORRELATION_ID
    resetToken = corr.BindCorrelationId("req-abc")
    try:
        assert corr.GetCorrelationId() == "req-abc"
    finally:
        corr.ResetCorrelationId(resetToken)
    assert corr.GetCorrelationId() == corr.ABSENT_CORRELATION_ID


def test_log_filter_stamps_the_current_correlation_id():
    correlationFilter = corr.CorrelationIdLogFilter()
    record = logging.LogRecord("t", logging.INFO, "f", 1, "msg", None, None)
    resetToken = corr.BindCorrelationId("run-xyz")
    try:
        assert correlationFilter.filter(record) is True
        assert getattr(record, corr.CORRELATION_ID_LOG_FIELD) == "run-xyz"
    finally:
        corr.ResetCorrelationId(resetToken)


def test_log_filter_does_not_override_an_explicit_extra():
    correlationFilter = corr.CorrelationIdLogFilter()
    record = logging.LogRecord("t", logging.INFO, "f", 1, "msg", None, None)
    setattr(record, corr.CORRELATION_ID_LOG_FIELD, "explicit-id")
    correlationFilter.filter(record)
    assert getattr(record, corr.CORRELATION_ID_LOG_FIELD) == "explicit-id"


def test_install_correlation_filter_is_idempotent():
    corr.InstallCorrelationIdLogFilter()
    rootLogger = logging.getLogger()
    countAfterFirst = sum(
        isinstance(existing, corr.CorrelationIdLogFilter)
        for existing in rootLogger.filters
    )
    corr.InstallCorrelationIdLogFilter()
    countAfterSecond = sum(
        isinstance(existing, corr.CorrelationIdLogFilter)
        for existing in rootLogger.filters
    )
    assert countAfterFirst == countAfterSecond == 1


# ---------------------------------------------------------------------------
# Integration tests -- CorrelationIdMiddleware + the sanitized-500 handler.
# ---------------------------------------------------------------------------


@pytest.fixture
def correlation_app():
    """Build a dedicated app with two throwaway routes for correlation testing.

    A self-contained application instance (rather than the shared module app) so
    a route that deliberately raises can exercise the catch-all HTTP 500 handler
    without registering an undocumented route on the real app.

    Returns:
        A configured FastAPI app exposing ``GET /__cor_ok__`` (returns 200) and
        ``GET /__cor_boom__`` (raises, to reach :func:`_HandleUnexpectedError`).
    """
    application = create_application()

    @application.get("/__cor_ok__")
    async def _CorrelationOk() -> dict:
        return {"ok": True}

    @application.get("/__cor_boom__")
    async def _CorrelationBoom() -> dict:
        raise RuntimeError("deliberate correlation-test failure")

    return application


@pytest_asyncio.fixture
async def correlation_client(correlation_app):
    """Yield an httpx ASGI client bound to :func:`correlation_app`.

    ``raise_app_exceptions=False`` lets the deliberately-raising route return the
    sanitized HTTP 500 (Starlette's ServerErrorMiddleware re-raises after building
    the response) instead of propagating the exception into the test.

    Args:
        correlation_app: The dedicated application under test.

    Yields:
        The configured async HTTP client.
    """
    transport = ASGITransport(app=correlation_app, raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://test") as testClient:
        yield testClient


async def test_response_carries_a_fresh_id_when_no_ingress_header(correlation_client):
    response = await correlation_client.get("/__cor_ok__")
    correlationId = response.headers.get(corr.RESPONSE_HEADER_NAME)
    assert response.status_code == 200
    assert correlationId is not None
    assert len(correlationId) == GENERATED_ID_LENGTH
    assert set(correlationId) <= HEX_CHARACTERS


async def test_valid_ingress_id_is_echoed_verbatim(correlation_client):
    response = await correlation_client.get(
        "/__cor_ok__", headers={"X-Request-ID": "trace-abc_1.2"}
    )
    assert response.headers.get(corr.RESPONSE_HEADER_NAME) == "trace-abc_1.2"


async def test_invalid_ingress_id_is_rejected_and_replaced(correlation_client):
    response = await correlation_client.get(
        "/__cor_ok__", headers={"X-Request-ID": "bad id"}
    )
    correlationId = response.headers.get(corr.RESPONSE_HEADER_NAME)
    assert correlationId != "bad id"
    assert len(correlationId) == GENERATED_ID_LENGTH


async def test_correlation_id_alias_header_is_honored(correlation_client):
    response = await correlation_client.get(
        "/__cor_ok__", headers={"X-Correlation-ID": "alias-77"}
    )
    assert response.headers.get(corr.RESPONSE_HEADER_NAME) == "alias-77"


async def test_500_body_and_header_match_the_ingress_id(correlation_client):
    response = await correlation_client.get(
        "/__cor_boom__", headers={"X-Request-ID": "trace-500"}
    )
    body = response.json()
    assert response.status_code == 500
    assert body.get("detail") == "Internal Server Error"
    assert body.get("correlation_id") == "trace-500"
    assert response.headers.get(corr.RESPONSE_HEADER_NAME) == "trace-500"


async def test_500_without_ingress_id_has_matching_fresh_body_and_header(correlation_client):
    response = await correlation_client.get("/__cor_boom__")
    body = response.json()
    correlationId = body.get("correlation_id")
    assert response.status_code == 500
    assert correlationId == response.headers.get(corr.RESPONSE_HEADER_NAME)
    assert len(correlationId) == GENERATED_ID_LENGTH
    assert set(correlationId) <= HEX_CHARACTERS

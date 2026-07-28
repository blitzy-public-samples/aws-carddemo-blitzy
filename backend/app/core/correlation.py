# Cross-cutting request-correlation infrastructure (QA finding M-32). Assigns
# every inbound HTTP request a correlation id -- a validated, sanitized
# client-supplied id when one is offered on the ingress header, otherwise a fresh
# server-generated one -- stores it in a context variable for the life of the
# request, injects it as a ``correlation_id`` attribute on every log record
# emitted while that request (or a batch run) is in scope, and echoes it back on
# the response so a caller can correlate its request with the server logs. This
# replaces the prior behavior where a correlation id existed ONLY inside the
# unhandled-500 handler and was never propagated to logs, responses, or the batch
# tier. No legacy COBOL source: this is modern observability infrastructure.
"""In-process request-correlation context for the CardDemo backend and batch tier.

Before this module a correlation id was minted only inside the catch-all HTTP 500
handler (:func:`app.main._HandleUnexpectedError`); it never reached the logs of a
*successful* or an *expected-error* request, was never returned to the caller, and
had no equivalent in the batch tier -- so a client report or a cross-tier trace
could not be tied back to server log lines (QA finding M-32).

This module provides a small, dependency-free correlation primitive built only on
the standard library (:mod:`contextvars` and :mod:`logging`):

* :func:`SanitizeCorrelationId` validates a client-supplied id against a strict
  allow-list (``[A-Za-z0-9._-]``, at most :data:`MAX_CORRELATION_ID_LENGTH`
  characters) so a hostile value cannot inject log-forging newlines or an
  oversized string, and falls back to a fresh :func:`GenerateCorrelationId` when
  the candidate is absent or invalid.
* :func:`BindCorrelationId` / :func:`GetCorrelationId` / :func:`ResetCorrelationId`
  store and read the id in a :class:`~contextvars.ContextVar`, so it is available
  to every coroutine handling the current request (or the current batch run)
  without threading it through call signatures.
* :class:`CorrelationIdLogFilter` (installed by
  :func:`InstallCorrelationIdLogFilter`) injects the current id onto every log
  record as ``correlation_id`` -- giving structured/JSON handlers and the batch
  formatter a consistent field -- and never raises out of ``filter``.

Cross-tier propagation is achieved by contract, not by a new dependency: the HTTP
layer honors and echoes the ``X-Request-ID`` (or ``X-Correlation-ID``) header, and
the batch CLI seeds the same context variable from the
:data:`CORRELATION_ID_ENV_VAR` environment variable, so an orchestrator can pass a
single id through both tiers.

Scope note (AAP section 0.6): full distributed tracing (OpenTelemetry) and metrics
(Prometheus) are deliberately NOT implemented here. The frozen AAP dependency
inventory (section 0.6) lists no tracing or metrics client, so adding one would be
scope creep beyond the migration. This module is the in-process correlation
foundation such a layer would build upon.

Design (Ochs Rule): classes/functions are PascalCase, local variables are
camelCase, module constants are ALL_UPPERCASE, indentation is four spaces, methods
stay small, and only specific exception types are ever handled. The framework
override :meth:`logging.Filter.filter` keeps its lowercase name because the logging
module calls it by that exact name (an explicit, documented exception mirroring
:class:`app.core.log_masking.PanMaskingFilter`).
"""

from __future__ import annotations

import logging
import re
import uuid
from contextvars import ContextVar, Token

__all__ = [
    "CORRELATION_ID_LOG_FIELD",
    "CORRELATION_ID_ENV_VAR",
    "REQUEST_STATE_ATTRIBUTE",
    "INGRESS_HEADER_NAMES",
    "RESPONSE_HEADER_NAME",
    "MAX_CORRELATION_ID_LENGTH",
    "ABSENT_CORRELATION_ID",
    "TARGET_LOGGER_NAMES",
    "GenerateCorrelationId",
    "SanitizeCorrelationId",
    "GetCorrelationId",
    "BindCorrelationId",
    "ResetCorrelationId",
    "CorrelationIdLogFilter",
    "InstallCorrelationIdLogFilter",
]

# Name of the attribute the filter injects onto every log record. A structured
# (JSON) handler or a ``%(correlation_id)s`` format token reads this field; the
# batch CLI adds the token to its formatter so every batch line carries the id.
CORRELATION_ID_LOG_FIELD = "correlation_id"

# Key under which the id is stashed on the ASGI ``scope["state"]`` (and thus read
# back via ``request.state.correlation_id``). This is the bridge across Starlette's
# outer ``ServerErrorMiddleware``: the catch-all HTTP 500 handler runs OUTSIDE the
# user middleware stack -- so the request-scoped context variable has already been
# reset by the time it runs -- but the request's ``scope`` (and its ``state``) is
# the same object, so the handler can still recover this request's id from it.
REQUEST_STATE_ATTRIBUTE = "correlation_id"

# Environment variable the batch CLI reads to inherit a correlation id from a
# parent process / orchestrator, so a single id can span the API and batch tiers.
CORRELATION_ID_ENV_VAR = "CARDDEMO_CORRELATION_ID"

# Ingress request headers consulted, in order, for a client-supplied id. Compared
# case-insensitively (ASGI header names arrive lowercased). ``X-Request-ID`` is the
# conventional name; ``X-Correlation-ID`` is accepted as an alias.
INGRESS_HEADER_NAMES = ("x-request-id", "x-correlation-id")

# Response header the id is echoed on so the caller can record it. Canonical
# mixed-case spelling; HTTP header names are case-insensitive on the wire.
RESPONSE_HEADER_NAME = "X-Request-ID"

# Maximum accepted length of a client-supplied id. Anything longer is rejected and
# replaced with a fresh id, bounding the value written to logs and responses.
MAX_CORRELATION_ID_LENGTH = 64

# A correlation id is one or more URL/log-safe characters up to the length bound.
# Restricting to this set prevents CR/LF log-forging and control-character
# injection through the ingress header. Compiled once.
_CORRELATION_ID_PATTERN = re.compile(
    rf"^[A-Za-z0-9._-]{{1,{MAX_CORRELATION_ID_LENGTH}}}$"
)

# Placeholder returned by :func:`GetCorrelationId` when no id is bound (for example
# a log line emitted at import time, before any request or batch run). Chosen so a
# ``%(correlation_id)s`` formatter never renders an empty field.
ABSENT_CORRELATION_ID = "-"

# Loggers the correlation filter is attached to, matching the masking filter's
# targets so every server log path carries the id. The root logger ("") covers
# records that propagate to a root handler.
TARGET_LOGGER_NAMES = (
    "",
    "uvicorn",
    "uvicorn.error",
    "uvicorn.access",
    "sqlalchemy.engine",
)

# The context variable holding the current correlation id. A module-level default
# means :func:`GetCorrelationId` always returns a string, even off-request.
_correlationIdVar: ContextVar[str] = ContextVar(
    "carddemo_correlation_id", default=ABSENT_CORRELATION_ID
)


def GenerateCorrelationId() -> str:
    """Return a fresh, server-generated correlation id.

    Returns:
        A 32-character lowercase hexadecimal id (a UUID4 with dashes removed),
        which satisfies :data:`_CORRELATION_ID_PATTERN`.
    """
    return uuid.uuid4().hex


def SanitizeCorrelationId(candidate: str | None) -> str:
    """Validate a client-supplied id, else mint a fresh one.

    The candidate is accepted only when, after trimming surrounding whitespace, it
    matches :data:`_CORRELATION_ID_PATTERN` (URL/log-safe characters, at most
    :data:`MAX_CORRELATION_ID_LENGTH`). Any absent, empty, over-long, or
    otherwise-invalid value yields a fresh :func:`GenerateCorrelationId` instead,
    so a hostile or malformed ingress header can never reach the logs or the
    response.

    Args:
        candidate: The raw id from the ingress header (or environment variable),
            or ``None`` when none was supplied.

    Returns:
        A safe correlation id: the trimmed candidate when valid, otherwise a
        freshly generated id.
    """
    if candidate is not None:
        trimmedCandidate = candidate.strip()
        if _CORRELATION_ID_PATTERN.match(trimmedCandidate):
            return trimmedCandidate
    return GenerateCorrelationId()


def GetCorrelationId() -> str:
    """Return the correlation id bound to the current context.

    Returns:
        The bound id, or :data:`ABSENT_CORRELATION_ID` (``"-"``) when none is set.
    """
    return _correlationIdVar.get()


def BindCorrelationId(correlationId: str) -> Token[str]:
    """Bind ``correlationId`` to the current context.

    Args:
        correlationId: The (already sanitized) id to store for the current
            request or batch run.

    Returns:
        A reset :class:`~contextvars.Token` to pass to :func:`ResetCorrelationId`
        so the previous value is restored once the scope ends.
    """
    return _correlationIdVar.set(correlationId)


def ResetCorrelationId(resetToken: Token[str]) -> None:
    """Restore the correlation id that was bound before :func:`BindCorrelationId`.

    Args:
        resetToken: The token returned by the paired :func:`BindCorrelationId`.
    """
    _correlationIdVar.reset(resetToken)


class CorrelationIdLogFilter(logging.Filter):
    """Logging filter that stamps the current correlation id onto every record.

    Attached to the server's loggers and their handlers by
    :func:`InstallCorrelationIdLogFilter`, it sets ``record.correlation_id`` to the
    id bound in the current context (or :data:`ABSENT_CORRELATION_ID` when none is
    bound) so a structured handler or a ``%(correlation_id)s`` format token always
    finds the field populated. It never overwrites an id a caller set explicitly
    via ``extra={"correlation_id": ...}``, always returns ``True`` (it annotates,
    it never suppresses), and never raises out of ``filter`` so it cannot break the
    logging pipeline.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        """Attach the current correlation id to ``record`` in place.

        Args:
            record: The log record about to be emitted.

        Returns:
            Always ``True`` so the (now annotated) record is still emitted.
        """
        if not hasattr(record, CORRELATION_ID_LOG_FIELD):
            setattr(record, CORRELATION_ID_LOG_FIELD, GetCorrelationId())
        return True


def _AttachFilterOnce(
    filterTarget: logging.Logger | logging.Handler,
    correlationFilter: CorrelationIdLogFilter,
) -> None:
    """Attach ``correlationFilter`` to a logger or handler unless already present.

    Idempotent so repeated calls (a rebuilt app in tests, a re-run lifespan
    startup, or a batch process that configures logging more than once) never
    stack duplicate filters.

    Args:
        filterTarget: A :class:`logging.Logger` or :class:`logging.Handler`.
        correlationFilter: The filter instance to attach.
    """
    alreadyAttached = any(
        isinstance(existingFilter, CorrelationIdLogFilter)
        for existingFilter in filterTarget.filters
    )
    if not alreadyAttached:
        filterTarget.addFilter(correlationFilter)


def InstallCorrelationIdLogFilter() -> CorrelationIdLogFilter:
    """Install the correlation filter across every server log path.

    Attaches a shared :class:`CorrelationIdLogFilter` to each logger named in
    :data:`TARGET_LOGGER_NAMES` and to every handler already configured on those
    loggers. Covering the handlers is what guarantees a ``%(correlation_id)s``
    format token is safe: a handler-level filter runs for every record that
    reaches the handler (including records that propagated from a child logger),
    so the ``correlation_id`` attribute is always present before the formatter
    renders it. The operation is idempotent.

    Returns:
        The shared :class:`CorrelationIdLogFilter` instance that was installed
        (useful for assertions in tests).
    """
    correlationFilter = CorrelationIdLogFilter()
    for loggerName in TARGET_LOGGER_NAMES:
        targetLogger = logging.getLogger(loggerName)
        _AttachFilterOnce(targetLogger, correlationFilter)
        for logHandler in targetLogger.handlers:
            _AttachFilterOnce(logHandler, correlationFilter)
    if logging.lastResort is not None:
        _AttachFilterOnce(logging.lastResort, correlationFilter)
    return correlationFilter

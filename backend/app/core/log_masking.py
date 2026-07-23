# Cross-cutting log sanitization. Masks Primary Account Numbers (PANs) — and
# redacts secret-bearing structured-extra keys — on EVERY field of a log record
# and across EVERY server log path, so sensitive data cannot reach a log sink.
# The original leak was the uvicorn access log recording the full card number in
# the GET/PUT /cards/{cardNum} request URL (QA finding F8); QA finding M-06 then
# established that the first filter covered only the message/args and only the
# access + root loggers, leaving exception tracebacks (whose asyncpg
# "[parameters: (...)]" tail embeds the raw PAN bound to a failing statement),
# cached exception text, stack info, structured extras, the SQLAlchemy engine
# log, and the other uvicorn loggers unmasked. This single filter now closes all
# of those paths. No legacy COBOL source: this is modern observability
# infrastructure enforcing the AAP §0.7.8 "card_num is masked" guarantee on the
# logging surface, complementing the response-body masking done by the CardRead /
# CardSummary schemas.
"""Comprehensive sensitive-data masking logging filter for the CardDemo backend.

The backend already masks the card number (``CARD-NUM``) in every API response
body via the response schemas (AAP §0.7.8 / §0.4.4). Several *logging* surfaces
bypassed that guarantee:

* The card detail endpoints take the PAN as a URL **path** segment
  (``GET`` / ``PUT /api/v1/cards/{cardNum}``, ported from COCRDSLC / COCRDUPC),
  so the uvicorn *access* log — which records the full request line — wrote the
  complete 16-digit PAN on every card-detail request (QA finding F8).
* An exception raised while a PAN was bound to a SQL statement rendered a
  traceback whose driver ``[parameters: (...)]`` tail embedded the raw PAN, and
  that traceback text (``exc_info`` → cached ``exc_text``) plus any attached
  ``stack_info`` reached the error log unmasked.
* Structured ``extra={...}`` fields (which become attributes on the record) and
  the ``sqlalchemy.engine`` / ``uvicorn`` / ``uvicorn.error`` loggers were not
  covered at all (QA finding M-06).

This module closes every one of those gaps WITHOUT changing the REST contract
(the documented routes in AAP §0.5.5 keep ``/cards/{cardNum}``). A single
:class:`logging.Filter` mutates the log record IN PLACE, before any handler
formats it, so that:

* a PAN in the preformatted ``msg`` or in any ``%``-style ``args`` element is
  masked to its last four digits (``0500024453765740`` -> ``************5740``);
* a PAN in the exception traceback is masked — the filter pre-renders
  ``exc_info`` into ``exc_text`` (masked) so every downstream handler reuses the
  sanitized traceback, and masks an already-cached ``exc_text``;
* a PAN in an explicit ``stack_info`` string is masked;
* a PAN in the string value of any structured ``extra`` attribute is masked, and
  any structured extra whose KEY names a secret (``password``, ``token``,
  ``secret``, ``ssn``, ``cvv``, ``authorization`` ...) is redacted WHOLESALE —
  because a password or bearer token does not match the numeric PAN pattern and
  must never be logged in any form.

What is treated as a PAN:
    A run of 13 to 19 consecutive digits not adjacent to another digit. That is
    the ISO/IEC 7812 PAN length band, so it masks the 16-digit card numbers used
    here while deliberately NOT touching shorter identifiers that legitimately
    appear in URLs — the 11-digit ``acct_id`` and 9-digit ``cust_id`` are both
    below the 13-digit floor and pass through unchanged.

Where the filter is installed (:func:`InstallPanMaskingFilter`):
    The shared filter is attached to the root logger and to the ``uvicorn``,
    ``uvicorn.error``, ``uvicorn.access`` and ``sqlalchemy.engine`` loggers, to
    every handler already configured on those loggers, and to
    :data:`logging.lastResort` — so a record is sanitized whether it is emitted
    directly on one of those loggers, propagates to a root/ancestor handler, or
    falls through to the last-resort handler. The install is idempotent.

Design (Ochs Rule): the class/functions are PascalCase, local variables are
camelCase, module constants are ALL_UPPERCASE, indentation is four spaces, each
method stays small, and only specific, well-understood exception types are
handled (never a bare ``except``). The filter is defensive: it never raises out
of ``filter`` (a logging filter must not break the logging path) and returns
``True`` so the record is always emitted — only its sensitive content is
sanitized.
"""

import logging
import re

__all__ = [
    "PanMaskingFilter",
    "MaskPansInText",
    "InstallPanMaskingFilter",
    "SENSITIVE_EXTRA_KEYS",
    "REDACTED_PLACEHOLDER",
    "TARGET_LOGGER_NAMES",
]

# Minimum and maximum digit count of a Primary Account Number (ISO/IEC 7812).
# The 16-digit CardDemo PAN sits inside this band; the 11-digit acct_id and
# 9-digit cust_id fall below MIN_PAN_DIGITS and are therefore never masked.
MIN_PAN_DIGITS = 13
MAX_PAN_DIGITS = 19

# Number of trailing digits left visible after masking (matches the response-body
# masking of the card schemas, e.g. ``************5740``).
VISIBLE_TRAILING_DIGITS = 4

# Character substituted for each masked digit.
MASK_CHARACTER = "*"

# A PAN is a run of MIN..MAX digits with no adjacent digit on either side, so an
# embedded run inside a longer number is not partially masked. Compiled once.
PAN_PATTERN = re.compile(rf"(?<!\d)\d{{{MIN_PAN_DIGITS},{MAX_PAN_DIGITS}}}(?!\d)")

# Loggers whose records may carry a PAN. ``uvicorn.access`` emits the request
# line (URL) that contains the card-number path segment; ``uvicorn`` /
# ``uvicorn.error`` carry error tracebacks; ``sqlalchemy.engine`` echoes SQL and
# its bound parameters; the root logger ("") is included so anything that
# propagates to a root handler is covered too (QA finding M-06).
TARGET_LOGGER_NAMES = (
    "",
    "uvicorn",
    "uvicorn.error",
    "uvicorn.access",
    "sqlalchemy.engine",
)

# Structured-extra keys whose VALUE is a secret that must be redacted wholesale
# rather than PAN-masked, because a password / bearer token / secret does not
# match the numeric PAN pattern and must never be logged in any form. Mirrors the
# SENSITIVE_FIELD_NAMES set used by the 422 response-body redaction in main.py.
SENSITIVE_EXTRA_KEYS = frozenset(
    {
        "password",
        "cvv",
        "cvv_cd",
        "ssn",
        "secret",
        "secret_key",
        "session_secret",
        "token",
        "access_token",
        "refresh_token",
        "authorization",
        "api_key",
        "apikey",
    }
)

# Value substituted for a redacted sensitive structured-extra attribute.
REDACTED_PLACEHOLDER = "***redacted***"

# A stock :class:`logging.Formatter` used ONLY to render an exception tuple to
# text so the traceback body can be masked BEFORE any handler's formatter caches
# an unmasked ``exc_text`` on the record.
_EXCEPTION_RENDERER = logging.Formatter()

# Every attribute a stock LogRecord carries, plus the two names a Formatter
# injects later (``message`` and ``asctime``). Any OTHER attribute found on a
# record is a caller-supplied structured "extra" and is therefore subject to
# masking. Computed once from a representative record so it stays correct across
# Python versions (for example ``taskName`` added in 3.12).
_RESERVED_RECORD_ATTRIBUTES = frozenset(
    vars(logging.LogRecord("", logging.INFO, "", 0, "", None, None)).keys()
) | {"message", "asctime"}


def _MaskSinglePan(matchObject: "re.Match[str]") -> str:
    """Mask one matched PAN, keeping only its last :data:`VISIBLE_TRAILING_DIGITS`.

    Args:
        matchObject: A regex match whose group is a run of PAN-length digits.

    Returns:
        The PAN with every digit except the final visible ones replaced by
        :data:`MASK_CHARACTER` (for example ``0500024453765740`` becomes
        ``************5740``).
    """
    panDigits = matchObject.group(0)
    maskedLength = len(panDigits) - VISIBLE_TRAILING_DIGITS
    return MASK_CHARACTER * maskedLength + panDigits[-VISIBLE_TRAILING_DIGITS:]


def MaskPansInText(text: str) -> str:
    """Return ``text`` with every embedded PAN masked to its last four digits.

    Args:
        text: Arbitrary text that may contain one or more PANs (for example a
            rendered log message, a URL path, or an exception traceback).

    Returns:
        The text with each PAN-length digit run masked; text with no PAN is
        returned unchanged.
    """
    return PAN_PATTERN.sub(_MaskSinglePan, text)


def _MaskArgument(argumentValue: object) -> object:
    """Mask a single logging ``%``-style argument when it is a PAN-bearing string.

    Non-string arguments (ints, status codes, ...) are returned unchanged so the
    downstream ``msg % args`` formatting still succeeds with the original types.

    Args:
        argumentValue: One element of a ``LogRecord.args`` tuple/dict.

    Returns:
        The argument with any embedded PAN masked when it is a ``str``; otherwise
        the original value untouched.
    """
    if isinstance(argumentValue, str):
        return MaskPansInText(argumentValue)
    return argumentValue


class PanMaskingFilter(logging.Filter):
    """Logging filter that masks sensitive data on every field of a log record.

    Attached to the server's loggers, their handlers, and
    :data:`logging.lastResort` by :func:`InstallPanMaskingFilter`, it rewrites,
    IN PLACE and before any handler formats the record:

    * ``record.msg`` and every string element/value of ``record.args`` — a PAN
      is masked whether the URL is formatted into the message or passed as a
      ``%s`` argument;
    * the exception traceback — an already-cached ``record.exc_text`` is masked,
      and otherwise ``record.exc_info`` is pre-rendered into a masked
      ``exc_text`` so every downstream handler reuses the sanitized traceback;
    * an explicit ``record.stack_info`` string;
    * structured ``extra`` attributes — a PAN in a string value is masked, and a
      value whose attribute name is in :data:`SENSITIVE_EXTRA_KEYS` is redacted
      wholesale to :data:`REDACTED_PLACEHOLDER`.

    The filter always returns ``True`` (it sanitizes, it never suppresses) and
    never raises, so it cannot break the logging pipeline.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        """Sanitize a log record in place, masking any sensitive data it carries.

        Args:
            record: The log record about to be emitted.

        Returns:
            Always ``True`` so the (now sanitized) record is still emitted. The
            record's ``msg``, ``args``, ``exc_text``, ``stack_info`` and any
            structured-extra attributes are mutated in place.
        """
        try:
            self._MaskMessageAndArgs(record)
            self._MaskExceptionText(record)
            self._MaskStackInfo(record)
            self._MaskStructuredExtras(record)
        except (TypeError, ValueError, AttributeError):
            # A malformed record must never break logging; emit it rather than
            # raising out of the filter.
            return True
        return True

    def _MaskMessageAndArgs(self, record: logging.LogRecord) -> None:
        """Mask any PAN in the record's ``msg`` template and its ``args``.

        Args:
            record: The log record whose message and arguments are masked in
                place. The ``args`` container shape (tuple/dict) is preserved so
                custom formatters (for example uvicorn's ``AccessFormatter``,
                which unpacks a five-tuple) keep working.
        """
        if isinstance(record.msg, str):
            record.msg = MaskPansInText(record.msg)
        record.args = self._MaskArguments(record.args)

    def _MaskArguments(self, recordArgs: object) -> object:
        """Mask PANs inside a record's ``args``, preserving its tuple/dict shape.

        Args:
            recordArgs: The ``LogRecord.args`` value — a tuple of positional
                args, a single mapping for ``%(name)s`` templates, or ``None``.

        Returns:
            The args with every string element/value PAN-masked and the original
            container shape preserved; ``None`` and unknown shapes are returned
            unchanged.
        """
        if not recordArgs:
            return recordArgs
        if isinstance(recordArgs, tuple):
            return tuple(_MaskArgument(singleArg) for singleArg in recordArgs)
        if isinstance(recordArgs, dict):
            return {argKey: _MaskArgument(argValue) for argKey, argValue in recordArgs.items()}
        return recordArgs

    @staticmethod
    def _MaskExceptionText(record: logging.LogRecord) -> None:
        """Mask the exception traceback so every handler reuses masked text.

        A standard :class:`logging.Formatter` only computes ``exc_text`` when it
        is falsy, so pre-setting a masked value here means every handler reuses
        the sanitized traceback rather than re-rendering the raw ``exc_info``.

        Args:
            record: The log record whose ``exc_text`` / ``exc_info`` is masked in
                place. Records without exception information are untouched.
        """
        if record.exc_text:
            record.exc_text = MaskPansInText(record.exc_text)
            return
        if record.exc_info:
            renderedTraceback = _EXCEPTION_RENDERER.formatException(record.exc_info)
            record.exc_text = MaskPansInText(renderedTraceback)

    @staticmethod
    def _MaskStackInfo(record: logging.LogRecord) -> None:
        """Mask any explicit ``stack_info`` string attached to the record.

        Args:
            record: The log record whose ``stack_info`` is masked in place.
                Records without stack info are untouched.
        """
        if record.stack_info:
            record.stack_info = MaskPansInText(record.stack_info)

    @classmethod
    def _MaskStructuredExtras(cls, record: logging.LogRecord) -> None:
        """Mask/redact caller-supplied structured ``extra`` attributes.

        Every attribute on the record that is NOT one of the reserved LogRecord
        fields (:data:`_RESERVED_RECORD_ATTRIBUTES`) was supplied by the caller
        via ``logger.log(..., extra={...})``. Such an attribute is redacted
        wholesale when its name identifies a secret
        (:data:`SENSITIVE_EXTRA_KEYS`); otherwise, when its value is a string,
        any embedded PAN is masked. Non-string, non-sensitive extras pass through
        unchanged so numeric/temporal structured fields are never corrupted.

        Args:
            record: The log record whose structured-extra attributes are masked
                or redacted in place.
        """
        for attributeName, attributeValue in list(vars(record).items()):
            if attributeName in _RESERVED_RECORD_ATTRIBUTES:
                continue
            if attributeName.lower() in SENSITIVE_EXTRA_KEYS:
                setattr(record, attributeName, REDACTED_PLACEHOLDER)
            elif isinstance(attributeValue, str):
                setattr(record, attributeName, MaskPansInText(attributeValue))


def _AttachFilterOnce(filterTarget: object, maskingFilter: PanMaskingFilter) -> None:
    """Attach ``maskingFilter`` to a logger or handler unless one is already present.

    Idempotent so repeated calls (for example a test that rebuilds the app, or a
    re-run lifespan startup) do not stack duplicate filters on the same logger,
    handler, or last-resort handler.

    Args:
        filterTarget: A :class:`logging.Logger` or :class:`logging.Handler`.
        maskingFilter: The filter instance to attach.
    """
    alreadyAttached = any(isinstance(existing, PanMaskingFilter) for existing in filterTarget.filters)
    if not alreadyAttached:
        filterTarget.addFilter(maskingFilter)


def InstallPanMaskingFilter() -> PanMaskingFilter:
    """Install the masking filter across every server log path.

    Attaches a shared :class:`PanMaskingFilter` to each logger named in
    :data:`TARGET_LOGGER_NAMES`, to every handler already configured on those
    loggers, and to :data:`logging.lastResort`. Covering all three is deliberate:

    * a filter on a *logger* is consulted for records logged directly on it (how
      ``uvicorn.access`` emits the request line);
    * records that PROPAGATE from a child logger (for example the SQLAlchemy
      engine's ``sqlalchemy.engine.Engine``) are filtered by the *handlers* they
      reach on an ancestor logger — so masking every target logger's handlers
      (the root logger's included) covers the propagation path;
    * :data:`logging.lastResort` catches records emitted when no handler exists
      anywhere in the hierarchy (uvicorn's default application-logger setup).

    Because a logging filter only sees records at the point it is attached, this
    is called from application lifespan startup — after uvicorn has installed its
    access/error handlers. The operation is idempotent.

    Returns:
        The shared :class:`PanMaskingFilter` instance that was installed (useful
        for assertions in tests).
    """
    maskingFilter = PanMaskingFilter()
    for loggerName in TARGET_LOGGER_NAMES:
        targetLogger = logging.getLogger(loggerName)
        _AttachFilterOnce(targetLogger, maskingFilter)
        for logHandler in targetLogger.handlers:
            _AttachFilterOnce(logHandler, maskingFilter)
    if logging.lastResort is not None:
        _AttachFilterOnce(logging.lastResort, maskingFilter)
    return maskingFilter

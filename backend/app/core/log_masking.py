# Cross-cutting log sanitization. Masks Primary Account Numbers (PANs) that would
# otherwise reach log sinks — most notably the uvicorn access log, which records
# the full request URL and therefore the card number in GET/PUT /cards/{cardNum}
# (QA finding F8). No legacy COBOL source: this is modern observability
# infrastructure enforcing the AAP §0.7.8 "card_num is masked" guarantee on the
# logging surface, complementing the response-body masking already done by the
# CardRead / CardSummary schemas.
"""PAN-masking logging filter for the CardDemo backend.

The backend already masks the card number (``CARD-NUM``) in every API response
body via the response schemas (AAP §0.7.8 / §0.4.4). One surface bypassed that
guarantee: because the card detail endpoints take the PAN as a URL **path**
segment (``GET`` / ``PUT /api/v1/cards/{cardNum}``, ported from COCRDSLC /
COCRDUPC), the uvicorn *access* log — which records the full request line — wrote
the complete 16-digit PAN on every card-detail request (QA finding F8; a PCI-DSS
exposure).

This module closes that gap WITHOUT changing the REST contract (the documented
routes in AAP §0.5.5 keep ``/cards/{cardNum}``): it installs a
:class:`logging.Filter` that rewrites any Primary Account Number appearing in a
log record — whether in a preformatted message or in the record's ``%``-style
args — so only the last four digits survive (for example
``0500024453765740`` -> ``************5740``), matching the masking the response
body already applies.

What is treated as a PAN:
    A run of 13 to 19 consecutive digits not adjacent to another digit. That is
    the ISO/IEC 7812 PAN length band, so it masks the 16-digit card numbers used
    here while deliberately NOT touching shorter identifiers that legitimately
    appear in URLs — the 11-digit ``acct_id`` and 9-digit ``cust_id`` are both
    below the 13-digit floor and pass through unchanged.

Design (Ochs Rule): the class/functions are PascalCase, local variables are
camelCase, module constants are ALL_UPPERCASE, indentation is four spaces, each
method stays small, and only specific, well-understood types are handled (never
a bare ``except``). The filter is defensive: it never raises out of ``filter``
(a logging filter must not break the logging path) and returns ``True`` so the
record is always emitted — only its rendered digits are sanitized.
"""

import logging
import re

__all__ = ["PanMaskingFilter", "MaskPansInText", "InstallPanMaskingFilter"]

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
# line (URL) that contains the card-number path segment; the root logger is
# included so anything that propagates to a root handler is covered too.
TARGET_LOGGER_NAMES = ("uvicorn.access", "")


def _MaskSinglePan(matchObject: re.Match) -> str:
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
            rendered log message or a URL path).

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
    """Logging filter that masks Primary Account Numbers in every log record.

    Attached to the ``uvicorn.access`` logger (and the root logger) by
    :func:`InstallPanMaskingFilter`, it rewrites both a preformatted
    ``record.msg`` and each ``record.args`` element so a PAN can never be
    rendered in full — regardless of whether the access log formats the URL into
    the message directly or passes it as a ``%s`` argument. The filter always
    returns ``True`` (it sanitizes, it never suppresses) and never raises, so it
    cannot break the logging pipeline.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        """Sanitize a log record in place, masking any PAN it carries.

        Args:
            record: The log record about to be emitted.

        Returns:
            Always ``True`` so the (now sanitized) record is still emitted. The
            record's ``msg`` and ``args`` are mutated in place to mask any PAN.
        """
        try:
            if isinstance(record.msg, str):
                record.msg = MaskPansInText(record.msg)
            record.args = self._MaskArguments(record.args)
        except (TypeError, ValueError):
            # A malformed record must never break logging; emit it unchanged
            # rather than raising out of the filter.
            return True
        return True

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


def _AttachFilterOnce(filterTarget: object, maskingFilter: PanMaskingFilter) -> None:
    """Attach ``maskingFilter`` to a logger or handler unless one is already present.

    Idempotent so repeated calls (for example a test that rebuilds the app) do
    not stack duplicate filters on the same logger or handler.

    Args:
        filterTarget: A :class:`logging.Logger` or :class:`logging.Handler`.
        maskingFilter: The filter instance to attach.
    """
    alreadyAttached = any(isinstance(existing, PanMaskingFilter) for existing in filterTarget.filters)
    if not alreadyAttached:
        filterTarget.addFilter(maskingFilter)


def InstallPanMaskingFilter() -> PanMaskingFilter:
    """Install the PAN-masking filter on the request-logging loggers and handlers.

    Attaches a shared :class:`PanMaskingFilter` to each logger named in
    :data:`TARGET_LOGGER_NAMES` and to that logger's handlers. Both are covered
    deliberately: a filter on a *logger* is consulted for records logged directly
    on it (how ``uvicorn.access`` emits the request line), while records that
    PROPAGATE from a child logger are filtered only by the *handlers* they reach
    — so masking both the loggers and their handlers guarantees a PAN is masked
    on either path. The operation is idempotent.

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
    return maskingFilter

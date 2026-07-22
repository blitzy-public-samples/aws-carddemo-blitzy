"""Logging filter that masks Primary Account Numbers (PAN) in all log output.

QA Issue 5: card numbers (13-19 digit PANs) leaked into the server logs via two
distinct mechanisms --

  * Mechanism A -- exception tracebacks whose asyncpg
    ``[parameters: (...)]`` tail embedded the raw PAN bound to the failing
    statement (14 unmasked occurrences were observed in a single 500 traceback).
  * Mechanism B -- the uvicorn access log recording the full PAN inside the
    ``GET /cards/{cardNum}`` request path.

Both are addressed here by a single :class:`logging.Filter` that rewrites every
13-19 digit run to reveal only the last four digits, mutating the log record in
place before any handler formats it. The filter masks the rendered message, the
cached exception text (``exc_text``), and any ``stack_info`` so the traceback
body itself is sanitized (mechanism A), not merely the summary line.

Masking to the last four digits mirrors the UI/response masking rule (AAP
section 0.7.8: ``card_num`` and ``ssn`` are masked, ``cvv`` never returned).
Eleven-digit account ids (``ACCT-ID PIC 9(11)``) fall below the 13-digit floor
and are never masked; 16-digit card and transaction ids are masked (acceptable,
safe over-masking of an id that is not itself sensitive).

Naming follows the Ochs Rule. The ``filter`` method keeps its lowercase name
because it overrides the :class:`logging.Filter` framework contract.
"""
import logging
import re

# 13-19 consecutive digits, word-bounded: the ISO/IEC 7812 PAN length range.
PAN_PATTERN = re.compile(r"\b\d{13,19}\b")
MASK_CHAR = "*"
VISIBLE_TAIL_DIGITS = 4

# Loggers that carry either request paths (access) or bound-parameter tracebacks
# (error/engine). Root ("") is included for completeness; ``logging.lastResort``
# is filtered separately to catch application-logger records that propagate to a
# handler-less root under uvicorn's logging configuration.
TARGET_LOGGER_NAMES = (
    "",
    "uvicorn",
    "uvicorn.error",
    "uvicorn.access",
    "sqlalchemy.engine",
)

# Renders an exception tuple to text so the traceback can be masked BEFORE any
# handler's formatter caches an unmasked ``exc_text`` on the record.
_EXCEPTION_RENDERER = logging.Formatter()


def _MaskText(text: str) -> str:
    """Return ``text`` with every 13-19 digit run reduced to its last four.

    Args:
        text: Arbitrary log text that may embed a PAN.

    Returns:
        The text with each PAN-length digit run replaced by mask characters
        followed by the final four digits (for example ``0923877193247330``
        becomes ``************7330``).
    """

    def _Replace(match: "re.Match[str]") -> str:
        digits = match.group(0)
        maskedWidth = len(digits) - VISIBLE_TAIL_DIGITS
        return MASK_CHAR * maskedWidth + digits[-VISIBLE_TAIL_DIGITS:]

    return PAN_PATTERN.sub(_Replace, text)


class PanMaskingFilter(logging.Filter):
    """Mask PANs in a log record's message, exception text, and stack info.

    Installed on the target loggers, on every handler configured on those
    loggers, and on :data:`logging.lastResort`, so that every emit path -- the
    access log, an error traceback, a SQL echo, and application-logger
    fallthrough -- is sanitized in place. Because the shared record is mutated
    before formatting, all downstream handlers reuse the masked values.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        """Mask PANs on ``record`` in place and always keep the record.

        Args:
            record: The log record about to be handled.

        Returns:
            Always ``True`` -- this filter sanitizes content rather than
            suppressing records.
        """
        self._MaskMessageAndArgs(record)
        self._MaskExceptionText(record)
        self._MaskStackInfo(record)
        return True

    @staticmethod
    def _MaskArgValue(argValue: object) -> object:
        """Mask a single logging argument, leaving non-strings untouched.

        Args:
            argValue: One positional/keyword logging argument.

        Returns:
            The masked string when ``argValue`` is a string; otherwise the value
            unchanged (ints, ``Decimal``, ``datetime``, and the like pass through
            so numeric/temporal arguments are never corrupted).
        """
        if isinstance(argValue, str):
            return _MaskText(argValue)
        return argValue

    @classmethod
    def _MaskMessageAndArgs(cls, record: logging.LogRecord) -> None:
        """Mask the message template and every string arg IN PLACE.

        The record's ``args`` container arity is deliberately preserved -- a PAN
        in a ``%s`` argument (for example the request path) is rewritten, but the
        tuple/dict itself is kept -- so custom formatters keep working. In
        particular uvicorn's ``AccessFormatter`` unpacks a five-tuple of
        ``(client, method, path, http_version, status)``; nulling ``args`` would
        crash it (and cascade into ``handleError``), so only the string values
        are masked while ints and other types pass through untouched.
        """
        if isinstance(record.msg, str):
            record.msg = _MaskText(record.msg)
        if not record.args:
            return
        if isinstance(record.args, tuple):
            record.args = tuple(cls._MaskArgValue(argValue) for argValue in record.args)
        elif isinstance(record.args, dict):
            record.args = {
                argKey: cls._MaskArgValue(argValue)
                for argKey, argValue in record.args.items()
            }

    @staticmethod
    def _MaskExceptionText(record: logging.LogRecord) -> None:
        """Pre-render and mask the traceback so handlers cache masked text.

        A standard :class:`logging.Formatter` only computes ``exc_text`` when it
        is falsy, so setting a masked value here means every handler reuses the
        sanitized traceback (mechanism A).
        """
        if record.exc_text:
            record.exc_text = _MaskText(record.exc_text)
            return
        if record.exc_info:
            renderedTraceback = _EXCEPTION_RENDERER.formatException(record.exc_info)
            record.exc_text = _MaskText(renderedTraceback)

    @staticmethod
    def _MaskStackInfo(record: logging.LogRecord) -> None:
        """Mask any explicit ``stack_info`` attached to the record."""
        if record.stack_info:
            record.stack_info = _MaskText(record.stack_info)


# Module-level singleton: ``addFilter`` dedupes by identity, so repeated installs
# (for example a re-run lifespan startup) never stack duplicate filters.
_PAN_MASKING_FILTER = PanMaskingFilter()


def InstallPanMaskingFilter() -> None:
    """Install the shared PAN-masking filter across every server log path.

    Attaches the shared filter to each target logger, to every handler already
    configured on those loggers, and to :data:`logging.lastResort` (which emits
    application-logger records that propagate to a handler-less root). This must
    run after the logging handlers exist -- i.e. from application lifespan
    startup, by which point uvicorn has installed its access/error handlers.

    Idempotent: :meth:`logging.Filterer.addFilter` skips a filter instance that
    is already present, so calling this more than once is safe.
    """
    for loggerName in TARGET_LOGGER_NAMES:
        targetLogger = logging.getLogger(loggerName)
        targetLogger.addFilter(_PAN_MASKING_FILTER)
        for attachedHandler in targetLogger.handlers:
            attachedHandler.addFilter(_PAN_MASKING_FILTER)
    if logging.lastResort is not None:
        logging.lastResort.addFilter(_PAN_MASKING_FILTER)


__all__ = ["PanMaskingFilter", "InstallPanMaskingFilter"]

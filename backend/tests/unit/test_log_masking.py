# Unit tests for app.core.log_masking (QA finding F8 — PAN log leak).
# Traceability: the QA runtime checkpoint found that GET/PUT /cards/{cardNum}
#   logged the full 16-digit Primary Account Number (PAN) via the uvicorn access
#   log, because the card number is a URL path segment. These tests encode the
#   masking contract that closes that leak WITHOUT changing the REST path
#   (AAP §0.5.5 keeps /cards/{cardNum}) and consistent with the response-body
#   masking of the card schemas (AAP §0.7.8): a PAN (a 13-19 digit run, ISO/IEC
#   7812) is rendered with only its last four digits, while shorter identifiers
#   that legitimately appear in URLs — the 11-digit acct_id and 9-digit cust_id —
#   are left intact.
#
# Ochs naming (AAP 0.8.2 / 0.8.3): snake_case test-function names (the pytest
# discovery contract) and file name; camelCase local variables; ALL_UPPERCASE
# module-level constants; 4-space indentation; one asserted behavior per test.

import logging

from app.core.log_masking import (
    InstallPanMaskingFilter,
    MaskPansInText,
    PanMaskingFilter,
)

# A representative 16-digit CardDemo PAN and its expected masked rendering
# (12 mask characters + the last four digits).
SAMPLE_PAN = "0500024453765740"
SAMPLE_PAN_MASKED = "************5740"

# Identifiers that MUST NOT be masked: both fall below the 13-digit PAN floor.
SAMPLE_ACCT_ID = "00000000011"  # ACCT-ID PIC 9(11)
SAMPLE_CUST_ID = "000000009"    # CUST-ID PIC 9(09)


def test_masks_standalone_pan_to_last_four() -> None:
    """A bare 16-digit PAN is masked to its final four digits."""
    maskedText = MaskPansInText(SAMPLE_PAN)
    assert maskedText == SAMPLE_PAN_MASKED


def test_masks_pan_embedded_in_access_log_url() -> None:
    """A PAN embedded in an access-log request line is masked in place."""
    accessLine = f'GET /api/v1/cards/{SAMPLE_PAN} HTTP/1.1'
    maskedText = MaskPansInText(accessLine)
    assert SAMPLE_PAN not in maskedText
    assert f'GET /api/v1/cards/{SAMPLE_PAN_MASKED} HTTP/1.1' == maskedText


def test_does_not_mask_eleven_digit_account_id() -> None:
    """An 11-digit acct_id is below the PAN floor and is left unchanged."""
    accountUrl = f'/api/v1/accounts/{SAMPLE_ACCT_ID}'
    assert MaskPansInText(accountUrl) == accountUrl


def test_does_not_mask_nine_digit_customer_id() -> None:
    """A 9-digit cust_id is below the PAN floor and is left unchanged."""
    customerReference = f'customer {SAMPLE_CUST_ID} viewed'
    assert MaskPansInText(customerReference) == customerReference


def test_does_not_partially_mask_over_length_digit_run() -> None:
    """A 20-digit run is not a PAN and must be left wholly intact.

    The boundary anchors mean an over-length run matches nothing, so no PAN is
    ever masked mid-number (which would corrupt an otherwise-benign identifier).
    """
    overLengthDigits = "12345678901234567890"
    assert MaskPansInText(overLengthDigits) == overLengthDigits


def test_masks_thirteen_digit_lower_boundary() -> None:
    """The 13-digit lower boundary of the PAN band is masked."""
    lowerBoundaryPan = "1234567890123"
    assert MaskPansInText(lowerBoundaryPan) == "*********0123"


def test_masks_nineteen_digit_upper_boundary() -> None:
    """The 19-digit upper boundary of the PAN band is masked."""
    upperBoundaryPan = "1234567890123456789"
    assert MaskPansInText(upperBoundaryPan) == "***************6789"


def test_filter_masks_preformatted_message() -> None:
    """The filter masks a PAN carried in a preformatted record message."""
    maskingFilter = PanMaskingFilter()
    logRecord = logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg=f'GET /api/v1/cards/{SAMPLE_PAN} HTTP/1.1 200',
        args=(),
        exc_info=None,
    )
    filterResult = maskingFilter.filter(logRecord)
    assert filterResult is True
    assert SAMPLE_PAN not in logRecord.getMessage()
    assert SAMPLE_PAN_MASKED in logRecord.getMessage()


def test_filter_masks_percent_style_string_argument() -> None:
    """The filter masks a PAN passed as a %-style argument, not just the msg."""
    maskingFilter = PanMaskingFilter()
    logRecord = logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="%s %d",
        args=(f'/api/v1/cards/{SAMPLE_PAN}', 200),
        exc_info=None,
    )
    filterResult = maskingFilter.filter(logRecord)
    assert filterResult is True
    renderedMessage = logRecord.getMessage()
    assert SAMPLE_PAN not in renderedMessage
    assert SAMPLE_PAN_MASKED in renderedMessage
    # The non-string status-code argument must survive so formatting still works.
    assert renderedMessage.endswith("200")


def test_filter_preserves_non_pan_record_unchanged() -> None:
    """A record with no PAN is emitted unchanged (and the filter returns True)."""
    maskingFilter = PanMaskingFilter()
    originalMessage = "GET /api/v1/menu HTTP/1.1 200"
    logRecord = logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg=originalMessage,
        args=(),
        exc_info=None,
    )
    assert maskingFilter.filter(logRecord) is True
    assert logRecord.getMessage() == originalMessage


def test_install_attaches_filter_to_access_logger_and_is_idempotent() -> None:
    """Install attaches exactly one PAN filter to uvicorn.access, even if repeated."""
    accessLogger = logging.getLogger("uvicorn.access")
    originalFilters = list(accessLogger.filters)
    try:
        InstallPanMaskingFilter()
        InstallPanMaskingFilter()  # second call must not stack a duplicate
        panFilterCount = sum(
            1 for attachedFilter in accessLogger.filters
            if isinstance(attachedFilter, PanMaskingFilter)
        )
        assert panFilterCount == 1
    finally:
        # Restore the logger's original filter list so global state does not leak
        # into other tests.
        accessLogger.filters = originalFilters


def test_installed_filter_masks_emitted_access_record() -> None:
    """End to end: a record logged on uvicorn.access is masked at the handler."""
    accessLogger = logging.getLogger("uvicorn.access")
    capturedMessages: list[str] = []

    class _CaptureHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            capturedMessages.append(record.getMessage())

    captureHandler = _CaptureHandler()
    originalFilters = list(accessLogger.filters)
    originalLevel = accessLogger.level
    try:
        accessLogger.addHandler(captureHandler)
        accessLogger.setLevel(logging.INFO)
        InstallPanMaskingFilter()
        accessLogger.info('GET /api/v1/cards/%s HTTP/1.1', SAMPLE_PAN)
        assert capturedMessages, "expected the access record to be captured"
        assert SAMPLE_PAN not in capturedMessages[-1]
        assert SAMPLE_PAN_MASKED in capturedMessages[-1]
    finally:
        accessLogger.removeHandler(captureHandler)
        accessLogger.filters = originalFilters
        accessLogger.setLevel(originalLevel)

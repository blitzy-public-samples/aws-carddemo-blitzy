"""Signed zoned-decimal DISPLAY codec and exact ``Decimal`` arithmetic helpers.

This module is the single source of truth for numeric fidelity in the
modernized CardDemo backend. It ports the numeric semantics of the legacy
COBOL monetary fields (signed zoned-decimal DISPLAY with an implied decimal
point) into exact Python :class:`decimal.Decimal` arithmetic, and reproduces
the legacy interest *truncation* behavior.

The module is intentionally pure and dependency-light: it imports only the
Python standard library, performs no I/O, reads no configuration, and mutates
no global state (including :func:`decimal.getcontext`) at import time. It is
therefore safe to import from any tree -- the backend services and schemas,
the ``backend/tests`` golden-master parity harness, and the cross-tree
``batch/`` loaders and jobs.

Key semantics preserved from the mainframe:
    * Monetary fields are signed zoned-decimal DISPLAY (NOT ``COMP-3`` packed
      decimal). The sign is carried as an *overpunch* in the zone nibble of the
      final digit and the decimal point is implied (never physically stored).
    * The monthly-interest ``COMPUTE`` in ``CBACT04C`` carries no ``ROUNDED``
      phrase, so the legacy program truncates toward zero to two decimal
      places. :func:`TruncateToCents` reproduces this using ``ROUND_DOWN``.
    * ``float`` is never used anywhere in this module; every value flows
      through :class:`decimal.Decimal` to guarantee regulatory numeric parity.

Example:
    Decode stored amounts and truncate an interest product exactly as the
    legacy batch job would::

        >>> from decimal import Decimal
        >>> DecodeZonedDecimal("00000001940{", 2)
        Decimal('194.00')
        >>> DecodeZonedDecimal("0000009190}", 2)
        Decimal('-919.00')
        >>> TruncateToCents(Decimal("100.00") * Decimal("12.99") / Decimal(1200))
        Decimal('1.08')
"""

# Ported from COBOL copybooks CVACT01Y / CVTRA05Y (signed zoned-decimal DISPLAY
# monetary fields: ACCT-* PIC S9(10)V99 and TRAN-AMT PIC S9(09)V99) and from
# program CBACT04C (monthly-interest truncation, 1300-COMPUTE-INTEREST). See
# tech spec AAP section 0.7.1 (Finding #1: zoned decimal, not COMP-3) and
# section 0.7.2 (interest COMPUTE without ROUNDED => ROUND_DOWN truncation).

from decimal import Decimal, InvalidOperation, ROUND_DOWN
from typing import Final

__all__ = [
    "QUANTUM_CENTS",
    "MONEY_SCALE",
    "ACCOUNT_MONEY_DIGITS",
    "TRAN_AMOUNT_DIGITS",
    "POSITIVE_OVERPUNCH_TO_DIGIT",
    "NEGATIVE_OVERPUNCH_TO_DIGIT",
    "DIGIT_TO_POSITIVE_OVERPUNCH",
    "DIGIT_TO_NEGATIVE_OVERPUNCH",
    "DecodeZonedDecimal",
    "DecodeSignedAmount",
    "EncodeZonedDecimal",
    "ToDecimal",
    "TruncateToCents",
    "Quantize",
]

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Two-decimal quantization step shared by every monetary truncation helper.
QUANTUM_CENTS: Final = Decimal("0.01")

# Implied fractional-digit count for every CardDemo monetary field (``V99``).
MONEY_SCALE: Final = 2

# Total digit positions for the fixed-width account money fields.
# ``PIC S9(10)V99`` -> 10 integer + 2 fractional = 12 digit positions.
ACCOUNT_MONEY_DIGITS: Final = 12

# Total digit positions for the transaction amount fields.
# ``PIC S9(09)V99`` -> 9 integer + 2 fractional = 11 digit positions.
TRAN_AMOUNT_DIGITS: Final = 11

# Overpunch decode maps. The final byte of a zoned field encodes both the last
# digit and the sign: positive zone nibbles map ``{`` and ``A``-``I`` to 0-9;
# negative zone nibbles map ``}`` and ``J``-``R`` to 0-9. Verified against the
# ASCII seed datasets in app/data/ASCII (acctdata.txt, dailytran.txt).
POSITIVE_OVERPUNCH_TO_DIGIT: Final = {
    "{": "0", "A": "1", "B": "2", "C": "3", "D": "4",
    "E": "5", "F": "6", "G": "7", "H": "8", "I": "9",
}
NEGATIVE_OVERPUNCH_TO_DIGIT: Final = {
    "}": "0", "J": "1", "K": "2", "L": "3", "M": "4",
    "N": "5", "O": "6", "P": "7", "Q": "8", "R": "9",
}

# Inverse maps used when encoding a Decimal back into the fixed-width zoned form
# (built from the decode maps so the two directions can never drift apart).
DIGIT_TO_POSITIVE_OVERPUNCH: Final = {
    digit: char for char, digit in POSITIVE_OVERPUNCH_TO_DIGIT.items()
}
DIGIT_TO_NEGATIVE_OVERPUNCH: Final = {
    digit: char for char, digit in NEGATIVE_OVERPUNCH_TO_DIGIT.items()
}


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API)
# ---------------------------------------------------------------------------


def _ResolveSignNibble(lastChar: str) -> tuple[bool, str]:
    """Resolve the sign and the final digit from a zoned-decimal last byte.

    Args:
        lastChar: The final byte of a zoned-decimal field.

    Returns:
        A ``(signIsNegative, finalDigit)`` tuple where ``finalDigit`` is a
        single ASCII digit character (``"0"``-``"9"``).

    Raises:
        ValueError: If ``lastChar`` is neither a legal overpunch character nor
            a plain ASCII digit.
    """
    if lastChar in POSITIVE_OVERPUNCH_TO_DIGIT:
        return False, POSITIVE_OVERPUNCH_TO_DIGIT[lastChar]
    if lastChar in NEGATIVE_OVERPUNCH_TO_DIGIT:
        return True, NEGATIVE_OVERPUNCH_TO_DIGIT[lastChar]
    if lastChar.isdigit():
        return False, lastChar
    raise ValueError(f"Invalid zoned-decimal sign nibble: {lastChar!r}")


def _ComposeNumericText(allDigits: str, scale: int, signIsNegative: bool) -> str:
    """Insert the implied decimal point into an unsigned digit string.

    Args:
        allDigits: The full run of ASCII digits (sign already stripped).
        scale: Number of implied fractional digits.
        signIsNegative: Whether the decoded value is negative.

    Returns:
        A :class:`decimal.Decimal`-parseable numeric string, e.g. ``"-919.00"``.
    """
    signPrefix = "-" if signIsNegative else ""
    if scale == 0:
        return signPrefix + allDigits
    integerPart = allDigits[:-scale] or "0"
    fractionPart = allDigits[-scale:].rjust(scale, "0")
    return f"{signPrefix}{integerPart}.{fractionPart}"


def _DigitToOverpunch(lastDigit: str, signIsNegative: bool) -> str:
    """Map a final digit plus sign to its overpunch byte for encoding.

    Args:
        lastDigit: A single ASCII digit character (``"0"``-``"9"``).
        signIsNegative: Whether the encoded value is negative.

    Returns:
        The overpunch character carrying both the digit and the sign.
    """
    if signIsNegative:
        return DIGIT_TO_NEGATIVE_OVERPUNCH[lastDigit]
    return DIGIT_TO_POSITIVE_OVERPUNCH[lastDigit]


# ---------------------------------------------------------------------------
# Public API -- decode
# ---------------------------------------------------------------------------


def DecodeZonedDecimal(rawValue: str, scale: int) -> Decimal:
    """Decode a signed zoned-decimal DISPLAY field into an exact ``Decimal``.

    The field is the raw fixed-width run of bytes exactly as stored on the
    mainframe dataset: zero-filled digits with the sign carried as an overpunch
    in the final byte and the decimal point implied by ``scale``.

    Args:
        rawValue: The raw fixed-width field, e.g. ``"00000001940{"``.
        scale: The number of implied fractional digits (``2`` for money).

    Returns:
        The decoded value as a :class:`decimal.Decimal`.

    Raises:
        TypeError: If ``rawValue`` is not a ``str``.
        ValueError: If ``scale`` is negative, the field is entirely blank, the
            sign nibble is illegal, or the payload is non-numeric.

    Example:
        >>> DecodeZonedDecimal("0000009190}", 2)
        Decimal('-919.00')
    """
    if not isinstance(rawValue, str):
        raise TypeError(f"rawValue must be str, got {type(rawValue).__name__}")
    if scale < 0:
        raise ValueError(f"scale must be non-negative, got {scale}")
    if rawValue.strip() == "":
        raise ValueError("Cannot decode a blank zoned-decimal field")
    signIsNegative, finalDigit = _ResolveSignNibble(rawValue[-1])
    allDigits = rawValue[:-1] + finalDigit
    if not allDigits.isdigit():
        raise ValueError(f"Non-numeric zoned-decimal payload: {rawValue!r}")
    numericText = _ComposeNumericText(allDigits, scale, signIsNegative)
    try:
        return Decimal(numericText)
    except InvalidOperation as exc:
        raise ValueError(f"Invalid zoned-decimal value: {rawValue!r}") from exc


def DecodeSignedAmount(rawValue: str) -> Decimal:
    """Decode a 2-decimal monetary zoned field (the common money case).

    A thin convenience wrapper over :func:`DecodeZonedDecimal` that applies the
    fixed :data:`MONEY_SCALE` used by every CardDemo ``V99`` monetary field.

    Args:
        rawValue: The raw fixed-width monetary field, e.g. ``"00000001940{"``.

    Returns:
        The decoded amount as a :class:`decimal.Decimal` with scale 2.

    Example:
        >>> DecodeSignedAmount("00000001940{")
        Decimal('194.00')
    """
    return DecodeZonedDecimal(rawValue, MONEY_SCALE)


# ---------------------------------------------------------------------------
# Public API -- encode
# ---------------------------------------------------------------------------


def EncodeZonedDecimal(value: Decimal, totalDigits: int, scale: int) -> str:
    """Encode a ``Decimal`` into a fixed-width signed zoned-decimal field.

    Truncates (``ROUND_DOWN``) to ``scale`` decimals to match legacy
    ``COMPUTE`` semantics, then zero-pads to ``totalDigits`` and rewrites the
    final byte as the sign-bearing overpunch character. This reproduces the
    exact byte layout the mainframe would have written, for golden-master
    parity.

    Args:
        value: The amount to encode (``str``/``int``/``Decimal`` accepted).
        totalDigits: Total digit positions including the sign-bearing digit
            (``12`` for account money, ``11`` for transaction amounts).
        scale: The number of implied fractional digits.

    Returns:
        The fixed-width zoned-decimal string, e.g. ``"00000001940{"``.

    Raises:
        TypeError: If ``value`` is a ``float`` or other unsupported type.
        ValueError: If ``scale`` or ``totalDigits`` is invalid, or the value
            overflows ``totalDigits`` positions.

    Example:
        >>> EncodeZonedDecimal(Decimal("-919.00"), 11, 2)
        '0000009190}'
    """
    if scale < 0:
        raise ValueError(f"scale must be non-negative, got {scale}")
    if totalDigits <= 0:
        raise ValueError(f"totalDigits must be positive, got {totalDigits}")
    decimalValue = ToDecimal(value)
    signIsNegative = decimalValue < 0
    truncated = Quantize(abs(decimalValue), Decimal(1).scaleb(-scale))
    unscaledDigits = str(int(truncated.scaleb(scale).to_integral_value()))
    if len(unscaledDigits) > totalDigits:
        raise ValueError(f"Value {value!r} overflows {totalDigits} digits")
    paddedDigits = unscaledDigits.zfill(totalDigits)
    return paddedDigits[:-1] + _DigitToOverpunch(paddedDigits[-1], signIsNegative)


# ---------------------------------------------------------------------------
# Public API -- exact conversion + interest truncation
# ---------------------------------------------------------------------------


def ToDecimal(value: str | int | Decimal) -> Decimal:
    """Convert a safe numeric input to a FINITE ``Decimal`` without using float.

    Accepts ``str``, ``int``, or ``Decimal``. A ``float`` (or ``bool``, or any
    other type) is rejected with :class:`TypeError` so that binary
    floating-point rounding can never contaminate a monetary value.

    The result is additionally required to be FINITE. An IEEE-754 special value
    -- ``NaN``, ``Infinity`` or ``-Infinity`` -- is never a valid monetary or
    rate amount, yet ``decimal.Decimal`` accepts one both directly and parsed
    from a string such as ``"Infinity"`` / ``"NaN"`` / ``"inf"`` / ``"nan"``.
    Left unchecked, such a value flows through validation to the database and to
    response serialization, where ``json.dumps(allow_nan=False)`` (the encoder
    FastAPI's ``JSONResponse`` uses) raises and turns the response into a 500 --
    a denial-of-service vector (QA SECURITY finding: NaN/Infinity). Rejecting it
    here, at the single shared coercion point every money/rate schema routes
    through, sanitizes the user-supplied input once for the whole schema layer
    (Ochs Rule #3) and surfaces to the client as an HTTP 422 rather than a 500.

    Args:
        value: The value to convert (``str``, ``int``, or ``Decimal``).

    Returns:
        The value as a finite :class:`decimal.Decimal`.

    Raises:
        TypeError: If ``value`` is a ``float``, ``bool``, or unsupported type.
        ValueError: If a ``str`` cannot be parsed as a decimal number, or the
            resulting value is a non-finite ``NaN`` / ``Infinity`` /
            ``-Infinity``.
    """
    if isinstance(value, Decimal):
        decimalValue = value
    elif isinstance(value, bool):
        raise TypeError("bool is not a valid monetary value")
    elif isinstance(value, int):
        decimalValue = Decimal(value)
    elif isinstance(value, str):
        try:
            decimalValue = Decimal(value.strip())
        except InvalidOperation as exc:
            raise ValueError(f"Cannot parse Decimal from {value!r}") from exc
    else:
        raise TypeError(f"Unsupported type for Decimal: {type(value).__name__}")
    if not decimalValue.is_finite():
        raise ValueError(
            f"Non-finite Decimal is not a valid monetary value: {value!r}"
        )
    return decimalValue


def Quantize(value: Decimal, quantum: Decimal = QUANTUM_CENTS) -> Decimal:
    """Truncate a value to a quantum using ``ROUND_DOWN`` (toward zero).

    Args:
        value: The value to quantize (``str``/``int``/``Decimal`` accepted).
        quantum: The quantization step; defaults to :data:`QUANTUM_CENTS`.

    Returns:
        The value truncated toward zero to the precision of ``quantum``.

    Raises:
        TypeError: If ``value`` is a ``float`` or other unsupported type.
        ValueError: If the value cannot be quantized to ``quantum``.
    """
    decimalValue = ToDecimal(value)
    try:
        return decimalValue.quantize(quantum, rounding=ROUND_DOWN)
    except InvalidOperation as exc:
        raise ValueError(f"Cannot quantize {value!r} to {quantum}") from exc


def TruncateToCents(value: Decimal) -> Decimal:
    """Truncate a monetary value to whole cents (legacy interest semantics).

    Reproduces the ``CBACT04C`` monthly-interest ``COMPUTE`` which carries no
    ``ROUNDED`` phrase and therefore truncates toward zero to two decimals.
    Interest callers must route the raw product through this helper::

        TruncateToCents(balance * rate / Decimal(1200))

    Args:
        value: The monetary value to truncate (``str``/``int``/``Decimal``).

    Returns:
        The value truncated toward zero to two decimal places.

    Example:
        >>> TruncateToCents(Decimal("1.999"))
        Decimal('1.99')
        >>> TruncateToCents(Decimal("-1.999"))
        Decimal('-1.99')
    """
    return Quantize(value, QUANTUM_CENTS)

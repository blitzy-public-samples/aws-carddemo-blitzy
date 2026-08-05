/**
 * :module: ``frontend/src/components/display.ts``
 * :purpose: The display and error-text helpers the screen components share, held in
 *     one module so the seventeen pages do not each carry their own copy of the same
 *     three rules: how a wire value is rendered into a BMS field, how a COBOL blank
 *     test is applied to an entry value, and which text a failed call puts on line 23.
 * :output: The named ``displayText``, ``displayField``, ``parseWireDecimal``,
 *     ``isBlank`` and ``resolveApiErrorMessage`` helpers.
 * :note: Presentation only. No value is reformatted, rounded or re-scaled here: a
 *     money string stays the exact ``NUMERIC(p,s)`` decimal string the service sent
 *     and a date stays ``YYYY-MM-DD``. Every numeric wire field arrives as a string
 *     (the services serialize identifiers, amounts and scores as strings so COBOL
 *     scale and field width survive), so these helpers accept ``string`` and a
 *     caller holding a genuine ``number`` converts it explicitly.
 */

import type { ApiError } from '../api';

/**
 * :purpose: Render a wire value into a BMS output field, treating an absent value as
 *     the blank field a mapset shows rather than the literal ``"null"``.
 * :param value: the value carried by the response DTO.
 * :returns: the value unchanged, or the empty string when it is absent.
 */
export function displayText(value: string | null | undefined): string {
  return value === null || value === undefined ? '' : value;
}

/**
 * :purpose: Render a wire value inside its BMS field width, reproducing the
 *     truncation a COBOL ``MOVE`` into a ``PIC X(n)`` symbolic-map field performs —
 *     notably the 26-character ``TRAN-ORIG-TS`` / ``TRAN-PROC-TS`` timestamps moved
 *     into the ``X(10)`` ``TORIGDT`` / ``TPROCDT`` fields, which display the
 *     ``YYYY-MM-DD`` date portion.
 * :param value: the value carried by the response DTO.
 * :param width: the BMS ``LENGTH=`` of the target field.
 * :returns: the display text, truncated to ``width``, or the empty string when the
 *     value is absent.
 */
export function displayField(
  value: string | null | undefined,
  width: number,
): string {
  return displayText(value).slice(0, width);
}

/**
 * :purpose: Parse a wire decimal string into a number for a client-side numeric
 *     comparison, without ever writing the parsed value back onto the wire — the
 *     string form remains authoritative so no scale is lost.
 * :param value: the wire value, a fixed-scale decimal string such as
 *     ``"-00000001234.56"``.
 * :returns: the finite parsed value, or ``null`` when the value is absent, blank, or
 *     not a well-formed optionally-signed decimal.
 */
export function parseWireDecimal(value: string | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  const trimmed = value.trim();
  if (!/^[+-]?(?:\d{1,18}\.\d{1,6}|\d{1,18})$/.test(trimmed)) {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * :purpose: Apply the COBOL blank test (``IF field = SPACES OR LOW-VALUES``) to an
 *     entry value.
 * :param value: the value typed into the field.
 * :returns: ``true`` when the value holds nothing but whitespace.
 */
/**
 * :purpose: Leading run of the masked SSN presentation the account service emits
 *     (``PiiMasker.maskSsn``), shared by every screen that displays or echoes the
 *     regulated identifier.
 */
export const SSN_MASK_PREFIX = '***-**-';

export function isBlank(value: string): boolean {
  return value.trim().length === 0;
}

/**
 * :purpose: Resolve the line-23 text for a failed call. The backend error envelope's
 *     own ``message`` is authoritative — it carries the verbatim legacy literal —
 *     and the client-side message is used only when the envelope carried none.
 * :param error: the normalized error surfaced by ``useApi``.
 * :param fallback: text to use when neither the envelope nor the client produced a
 *     message; omitted yields the empty string.
 * :returns: the message to display.
 */
export function resolveApiErrorMessage(
  error: ApiError,
  fallback = '',
): string {
  const bodyMessage = error.body?.message;
  if (bodyMessage !== undefined && bodyMessage.length > 0) {
    return bodyMessage;
  }
  return error.message.length > 0 ? error.message : fallback;
}

/**
 * :module: ``frontend/src/pages/tranAddFormat.ts``
 * :purpose: Reproduce the two COBOL field edits that ``COTRN02C COPY-LAST-TRAN-DATA``
 *     applies when it copies the last transaction onto the add screen. The paragraph does
 *     not move the stored values through unchanged: the amount passes through
 *     ``WS-TRAN-AMT-E PIC +99999999.99`` and each timestamp lands in a ``PIC X(10)`` map
 *     field, so both arrive already edited into the shapes the screen's own checks accept.
 * :output: The named ``toAmountPicture`` and ``toMapDate`` helpers with their widths.
 */

import { toSignedAmountPicture } from '../components/display';

/** Integer digit count of the on-screen amount picture ``+99999999.99``. */
export const AMOUNT_INTEGER_DIGITS = 8;

/** Width of the ``TORIGDTI`` / ``TPROCDTI`` map fields (``PIC X(10)``). */
export const DATE_FIELD_WIDTH = 10;

/**
 * :purpose: Edit a scale-2 amount into the screen picture the add map carries, reproducing
 *     ``MOVE TRAN-AMT TO WS-TRAN-AMT-E`` where ``WS-TRAN-AMT-E`` is ``PIC +99999999.99``:
 *     a leading sign, eight zero-padded integer digits, the decimal point, then two
 *     decimal digits.
 * :param value: The amount in its wire form, for example ``603.22`` or ``-919.00``.
 * :returns: The twelve-character edited amount, for example ``+00000603.22``.
 */
export function toAmountPicture(value: string): string {
  return toSignedAmountPicture(value, AMOUNT_INTEGER_DIGITS);
}

/**
 * :purpose: Reduce a stored 26-character timestamp to the date the map field holds,
 *     reproducing the truncation of ``MOVE TRAN-ORIG-TS TO TORIGDTI`` where the source is
 *     ``X(26)`` and the target ``PIC X(10)``.
 * :param value: The timestamp in its wire form.
 * :returns: Its leftmost ten characters, the ``YYYY-MM-DD`` date.
 */
export function toMapDate(value: string): string {
  return value.trim().slice(0, DATE_FIELD_WIDTH);
}

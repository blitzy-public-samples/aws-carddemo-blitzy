/**
 * :module: ``frontend/src/components/display.ts``
 * :purpose: The display and error-text helpers the screen components share, held in one
 *     module so the seventeen pages do not each carry their own copy of the same three rules:
 *     how a wire value is rendered into a BMS field, how a COBOL blank test is applied to an
 *     entry value, and which text a failed call puts on line 23.
 * :output: The named ``displayText``, ``displayField``, ``parseWireDecimal``, ``isBlank``
 *     and ``resolveApiErrorMessage`` helpers.
 * :note: Presentation only. No value is reformatted, rounded or re-scaled here: a money
 *     string stays the exact ``NUMERIC(p,s)`` decimal string the service sent and a date stays
 *     ``YYYY-MM-DD``. Every numeric wire field arrives as a string (the services serialize
 *     identifiers, amounts and scores as strings so COBOL scale and field width survive), so
 *     these helpers accept ``string`` and a caller holding a genuine ``number`` converts it
 *     explicitly.
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
 * :purpose: Render a number the way a COBOL ``PIC 9(n)`` field holds it — right
 *     justified in exactly ``n`` digits with leading zeros. A display field declared
 *     ``PIC 9(02)`` stores 1 as ``01``, so a ``STRING ... DELIMITED BY SIZE`` of it
 *     contributes both characters and everything the program concatenates afterwards
 *     starts at the same column for every value. Rendering the number itself instead
 *     shortens the single-digit cases by one character and takes the text beside them
 *     with it, which is what pulls a two-digit row out of line with the rest.
 * :param value: the number to render.
 * :param digits: the ``n`` of the ``PIC 9(n)`` field.
 * :returns: the zero-padded digit string; a value already wider than ``digits`` is
 *     returned in full rather than truncated, since a lost digit changes the value.
 */
export function displayZoned(value: number, digits: number): string {
  return String(value).padStart(digits, '0');
}

/**
 * :purpose: Apply the menu screens' own option edit, exactly as ``COMEN01C`` L118-L129 and
 *     ``COADM01C`` perform it. The entered text is right justified into ``WS-OPTION-X PIC
 *     X(02) JUST RIGHT``, its blanks become zeros (``INSPECT ... REPLACING ALL ' ' BY '0'``)
 *     and the result is moved to ``WS-OPTION PIC 9(02)``; the option is then refused when it
 *     ``IS NOT NUMERIC``, exceeds the option count, or is zero. The edit belongs on the client
 *     as well as the server because the field it guards admits characters that are not digits.
 *     A 3270 numeric field accepts a minus sign and a period alongside 0-9 -- which is
 *     precisely why the program tests ``IS NOT NUMERIC`` rather than trusting the keyboard --
 *     so an entry like ``-1`` reaches the edit intact and has to be refused there. Stripping
 *     those characters as they are typed instead turns ``-1`` into the valid option ``1`` and
 *     dispatches a screen the operator never asked for.
 * :param value: the raw text held by the ``OPTION`` field.
 * :param optionCount: the screen's ``CDEMO-MENU-OPT-COUNT`` / ``CDEMO-ADMIN-OPT-COUNT`` --
 *     the total the map declares, not the number of options a role can see, since the role
 *     gate is a separate edit carrying its own message.
 * :returns: the accepted option number, or ``null`` when the edit refuses the entry.
 */
export function editMenuOption(value: string, optionCount: number): number | null {
  // MOVE OPTIONI(1:WS-IDX) TO WS-OPTION-X, a PIC X(02) JUST RIGHT field whose
  // remaining blanks INSPECT then replaces with zeros.
  const justified = value.trim().padStart(2, '0');
  // IF WS-OPTION IS NOT NUMERIC: a PIC 9(02) holds two digits and nothing else.
  if (!/^\d{2}$/.test(justified)) {
    return null;
  }
  const optionNumber = Number(justified);
  // IF WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS.
  if (optionNumber === 0 || optionNumber > optionCount) {
    return null;
  }
  return optionNumber;
}

/** Status the services answer with when they refuse a submitted value on its own terms. */
const HTTP_BAD_REQUEST = 400;

/**
 * :purpose: Decide whether a failed call means the server refused the VALUE it was sent,
 *     as opposed to failing to process the request at all. Only the former faults the
 *     control the value came from: a program that rejects an entry re-sends its map with
 *     the cursor on that field, while a failure to read a file leaves every entered value
 *     unjudged and marks nothing invalid.
 * :param error: the value a failed call rejected with, or ``null`` when it succeeded.
 * :returns: ``true`` when the failure was the server rejecting the submitted value.
 */
export function isRejectedValue(error: unknown): boolean {
  return (
    error !== null &&
    typeof error === 'object' &&
    'status' in error &&
    (error as ApiError).status === HTTP_BAD_REQUEST
  );
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
  // ``error.message`` is already the client's own resolved text: the api client
  // substitutes GENERIC_ERROR_MESSAGE for every failure that carried no envelope,
  // so a library diagnostic can never reach line 23 through here either.
  return error.message.length > 0 ? error.message : fallback;
}

/**
 * :purpose: Read the request field a failed call named, so the screen can mark that one
 *     control and place the cursor on it — the client half of the legacy
 *     ``MOVE -1 TO <field>L`` the programs perform beside every edit failure. The
 *     envelope's ``fieldErrors`` map is authoritative because the SERVICE performed the
 *     edit and therefore knows which field it refused; a screen no longer has to infer
 *     that by matching the message text.
 * :param error: the normalized error surfaced by ``useApi``, or ``null``.
 * :returns: the first field name the envelope named, or ``null`` when it named none —
 *     a whole-submission refusal, a state conflict, or a transport failure.
 * :note: The first key is the one to act on: the services evaluate their edits in the
 *     legacy screen order and report only the edit that failed first, exactly as a
 *     COBOL ``EVALUATE TRUE`` would have.
 */
export function resolveFaultedField(error: ApiError | null): string | null {
  const fieldErrors: unknown = error?.body?.fieldErrors;
  // The envelope is a wire value, so its shape is read rather than trusted. A refusal
  // that names no field serialises the member as an explicit JSON ``null`` -- every
  // whole-submission refusal does, such as the unchanged-record outcome -- and
  // ``Object.keys(null)`` throws, which would take the whole screen down through the
  // error boundary instead of showing the message the service sent.
  if (fieldErrors === null || typeof fieldErrors !== 'object') {
    return null;
  }
  const [first] = Object.keys(fieldErrors);
  return first ?? null;
}

/**
 * :purpose: Read the line-23 message a route guard handed to the screen it bounced
 *     the caller to. A CICS program that refused a transfer re-sent the receiving
 *     screen with its own explanation on line 23; the router carries that explanation
 *     in the navigation state, and this reads it back without trusting its shape.
 * :param state: the value of ``useLocation().state``.
 * :returns: the message, or an empty string when the navigation carried none.
 */
export function guardScreenMessage(state: unknown): string {
  if (state === null || typeof state !== 'object') {
    return '';
  }
  const candidate = (state as { screenMessage?: unknown }).screenMessage;
  return typeof candidate === 'string' ? candidate : '';
}

/** Decimal digit count of every signed monetary picture the mapsets carry. */
export const PICTURE_DECIMAL_DIGITS = 2;

/**
 * :purpose: Edit a scale-2 monetary value into a COBOL signed display picture — a leading
 *     sign, a fixed run of zero-padded integer digits, the decimal point, then two
 *     decimals. This is what a ``MOVE`` into a ``PIC +9(n)V99`` receiver produces, and
 *     the mapsets carry several such fields at different widths: ``COTRN02C``'s
 *     ``WS-TRAN-AMT-E`` is ``PIC +99999999.99`` and ``COBIL00C``'s ``WS-CURR-BAL`` is
 *     ``PIC +9999999999.99``, whose map field ``CURBAL`` is declared ``LENGTH=14``.
 * :param value: the value in its wire form, for example ``560.00`` or ``-919``.
 * :param integerDigits: the integer digit count the picture declares.
 * :returns: the edited value, for example ``+0000000560.00`` at ten integer digits.
 */
export function toSignedAmountPicture(value: string, integerDigits: number): string {
  const trimmed = value.trim();
  const sign = trimmed.startsWith('-') ? '-' : '+';
  const [whole = '', fraction = ''] = trimmed.replace(/^[-+]/, '').split('.');
  const integerPart = whole
    .replace(/\D/g, '')
    .padStart(integerDigits, '0')
    .slice(-integerDigits);
  const fractionPart = fraction
    .replace(/\D/g, '')
    .padEnd(PICTURE_DECIMAL_DIGITS, '0')
    .slice(0, PICTURE_DECIMAL_DIGITS);
  return `${sign}${integerPart}.${fractionPart}`;
}

/** Integer digit count of the ``+ZZZ,ZZZ,ZZZ.99`` map pictures (nine ``Z`` positions). */
export const SUPPRESSED_PICTURE_INTEGER_DIGITS = 9;

/**
 * :purpose: Edit a scale-2 monetary value into the BMS numeric edit picture
 *     ``PICOUT='+ZZZ,ZZZ,ZZZ.99'``, which ``COACTVW`` declares on all five of its amount
 *     fields (``ACRDLIM``, ``ACSHLIM``, ``ACURBAL``, ``ACRCYCR``, ``ACRCYDB``, each
 *     ``LENGTH=15``). Unlike a ``PIC +9(n)V99`` receiver, a ``Z`` position SUPPRESSES a
 *     leading zero: the zero and any comma still inside the suppressed run are replaced by
 *     spaces, and suppression stops at the first significant digit.
 * :param value: the value in its wire form, for example ``4998.00`` or ``-919.50``.
 * :returns: the edited value, always exactly 15 characters: the sign, nine zero-suppressed
 *     integer positions with their two group separators, the decimal point and two decimals.
 *     ``4998.00`` becomes ``+ 4,998.00``.
 * :note: Nine integer positions is what the picture declares, so a value with more of them
 *     loses its high-order digits, exactly as a COBOL ``MOVE`` into this receiver does. The
 *     account columns are ``NUMERIC(12,2)``, so a ten-digit balance is reachable and renders
 *     as its low-order nine digits -- the same characters the 3270 painted.
 */
export function toSuppressedAmountPicture(value: string): string {
  const trimmed = value.trim();
  const sign = trimmed.startsWith('-') ? '-' : '+';
  const [whole = '', fraction = ''] = trimmed.replace(/^[-+]/, '').split('.');
  const digits = whole
    .replace(/\D/g, '')
    .padStart(SUPPRESSED_PICTURE_INTEGER_DIGITS, '0')
    .slice(-SUPPRESSED_PICTURE_INTEGER_DIGITS);
  const grouped = `${digits.slice(0, 3)},${digits.slice(3, 6)},${digits.slice(6)}`;
  let significant = false;
  const suppressed = Array.from(grouped)
    .map((character) => {
      if (significant) {
        return character;
      }
      if (character === '0' || character === ',') {
        return ' ';
      }
      significant = true;
      return character;
    })
    .join('');
  const fractionPart = fraction
    .replace(/\D/g, '')
    .padEnd(PICTURE_DECIMAL_DIGITS, '0')
    .slice(0, PICTURE_DECIMAL_DIGITS);
  return `${sign}${suppressed}.${fractionPart}`;
}

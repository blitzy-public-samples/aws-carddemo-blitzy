/**
 * :purpose: The browse-filter edits shared by the list screens, expressed the way the
 *     COBOL programs express them: against the RAW content of the symbolic map field.
 *
 *     ``COTRN00C`` L206-217 and ``COCRDLIC`` ``2210-EDIT-ACCOUNT`` / ``2220-EDIT-CARD``
 *     both open with an ``EQUAL LOW-VALUES OR EQUAL SPACES`` test and, when that fails,
 *     require the field to be ``NUMERIC``. Neither trims first, and COBOL's ``IS
 *     NUMERIC`` on a ``PIC X(n)`` field is true only when every position holds a digit.
 *     A value carrying a tab, an embedded space or a surrounding space therefore fails
 *     the edit and is reported — it does not quietly become "no filter" and it does not
 *     quietly become its own trimmed self, which are the two ways a filter can be
 *     applied differently from the way it is displayed.
 */

/** The one character ``EQUAL SPACES`` accepts. */
const SPACE = ' ';

/**
 * :purpose: ``IF <filter> EQUAL LOW-VALUES OR EQUAL SPACES`` — the "not supplied" test
 *     every filter edit opens with. An untouched map field arrives as LOW-VALUES, whose
 *     browser analogue is the empty string; a field the operator blanked arrives as
 *     SPACES. Nothing else is "not supplied": a tab is neither a space nor LOW-VALUES,
 *     so it falls through to the numeric edit exactly as it does in the source.
 * :param value: the raw entry value, exactly as the field holds it.
 * :returns: ``true`` when the filter was not supplied and the browse is unfiltered.
 */
export function isFilterNotSupplied(value: string): boolean {
  if (value === '') {
    return true;
  }
  for (const character of value) {
    if (character !== SPACE) {
      return false;
    }
  }
  return true;
}

/**
 * :purpose: COBOL ``IS NUMERIC`` against a ``PIC X(n)`` field: every position must hold
 *     one of ``0``..``9``. Deliberately not a Unicode digit test — a COBOL numeric class
 *     test recognises no other digit, and no sign, decimal point, space or tab.
 * :param value: the raw entry value, exactly as the field holds it.
 * :returns: ``true`` when every character is an ASCII digit and there is at least one.
 */
export function isFilterAllDigits(value: string): boolean {
  if (value === '') {
    return false;
  }
  for (const character of value) {
    if (character < '0' || character > '9') {
      return false;
    }
  }
  return true;
}

/**
 * :purpose: The physical capacity of a 3270 input field. A field declared ``LENGTH=n``
 *     cannot hold an ``n + 1``-th character: the terminal refuses the keystroke and
 *     locks the keyboard rather than accepting a value the field cannot show. ``maxlength``
 *     reproduces that for typing and pasting but not for a value set programmatically or
 *     by autofill, so the entry handler applies the same limit — which also means the
 *     value submitted is always the value on display.
 * :param value: the incoming entry value.
 * :param width: the ``LENGTH`` the mapset declares for the field.
 * :returns: the value limited to the field's declared width.
 */
export function limitToFieldWidth(value: string, width: number): string {
  return value.length > width ? value.slice(0, width) : value;
}

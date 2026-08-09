/**
 * :module: ``frontend/src/pages/menuOptionEdit.ts``
 * :purpose: The option edit both menu screens perform on the two positions of their
 *     ``OPTION`` field, expressed the way ``COMEN01C`` and ``COADM01C`` express it:
 *     against the RAW content of the map field.
 *
 *     ``PROCESS-ENTER-KEY`` is identical in both programs (``COMEN01C`` L117-134,
 *     ``COADM01C`` L117-134). It transforms the entry and then tests it with ``IF
 *     WS-OPTION IS NOT NUMERIC OR WS-OPTION > <count> OR WS-OPTION = ZEROS``. That class
 *     test is the whole point of this module: discarding the characters it would have
 *     refused looks equivalent and is not, because it rewrites what the operator entered
 *     into a DIFFERENT, valid option and dispatches that one. ``-1`` became ``1`` and
 *     opened Account View. The test would also be dead code if the field could only ever
 *     hold digits, which is the source's own evidence that it cannot.
 * :output: The named ``toProgramOption`` and ``isOptionRefused`` functions, the refusal
 *     literal, the field width, and the two declared option counts.
 * :note: Logic only — no UI, no I/O and no browser global, so it imports cleanly under
 *     Jest.
 */

import { isFilterAllDigits } from './screenFilters';

/**
 * Width of the ``OPTION`` entry field. Both mapsets declare it identically:
 * ``OPTION DFHMDF ATTRB=(FSET,IC,NORM,NUM,UNPROT), JUSTIFY=(RIGHT,ZERO), LENGTH=2``
 * (``COMEN01.bms``, ``COADM01.bms`` L145-148).
 */
export const OPTION_FIELD_WIDTH = 2;

/** Declared main option count (``COMEN02Y`` ``CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10``). */
export const CDEMO_MENU_OPT_COUNT = 10;

/** Declared admin option count (``COADM02Y`` ``CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4``). */
export const CDEMO_ADMIN_OPT_COUNT = 4;

/**
 * Row-23 refusal literal of the option edit, verbatim and shared by both programs
 * (``COMEN01C`` L131-132, ``COADM01C`` L131-132).
 */
export const MSG_INVALID_OPTION = 'Please enter a valid option number...';

/** The one character the blank scan and ``REPLACING ALL ' '`` recognise. */
const SPACE = ' ';

/**
 * :purpose: Reproduce L117-124 — the four steps that turn the two positions of
 *     ``OPTIONI`` into ``WS-OPTION``. The ``PERFORM VARYING`` scans from the last
 *     position back for a non-blank; the reference modification ``OPTIONI(1:WS-IDX)``
 *     keeps the leading substring up to it; the move into ``WS-OPTION-X PIC X(02) JUST
 *     RIGHT`` right-justifies that substring; and ``INSPECT WS-OPTION-X REPLACING ALL ' '
 *     BY '0'`` fills the vacated positions with zeros. That is how the mapset's
 *     ``JUSTIFY=(RIGHT,ZERO)`` field makes ``' 1'`` and ``'1 '`` both mean option ``01``,
 *     and how an untouched field becomes ``00`` rather than nothing at all.
 * :param value: the raw ``OPTIONI`` value, exactly as the field holds it.
 * :returns: exactly ``OPTION_FIELD_WIDTH`` characters — the ``WS-OPTION`` the class test
 *     is applied to. A non-digit survives unaltered, because the program's own test is
 *     what refuses it.
 */
export function toProgramOption(value: string): string {
  const field = value.slice(0, OPTION_FIELD_WIDTH).padEnd(OPTION_FIELD_WIDTH, SPACE);
  let last = OPTION_FIELD_WIDTH;
  while (last > 1 && field.charAt(last - 1) === SPACE) {
    last -= 1;
  }
  const justified = field.slice(0, last).padStart(OPTION_FIELD_WIDTH, SPACE);
  return justified.split(SPACE).join('0');
}

/**
 * :purpose: The ``IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > <count> OR WS-OPTION =
 *     ZEROS`` edit (L127-134), applied to the value as received.
 * :param value: the raw ``OPTIONI`` value, exactly as the field holds it.
 * :param optionCount: the declared option count of that menu's table —
 *     ``CDEMO_MENU_OPT_COUNT`` or ``CDEMO_ADMIN_OPT_COUNT``. The declared count of the
 *     table, not the number of lines the screen shows: an option the role gate hides is
 *     refused by that gate, with its own separate literal.
 * :returns: ``true`` when the program would refuse this entry and re-send its map.
 */
export function isOptionRefused(value: string, optionCount: number): boolean {
  const candidate = toProgramOption(value);
  if (!isFilterAllDigits(candidate)) {
    return true;
  }
  const numeric = Number(candidate);
  return numeric === 0 || numeric > optionCount;
}

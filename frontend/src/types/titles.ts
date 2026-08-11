/**
 * :module: ``frontend/src/types/titles.ts``
 * :purpose: The screen-title literals of ``app/cpy/COTTL01Y.cpy`` and the per-mapset
 *     screen names, held once so every screen shows the same two title lines the legacy
 *     screens showed. All seventeen online programs move ``CCDA-TITLE01`` and ``CCDA-TITLE02``
 *     into the ``TITLE01``/``TITLE02`` header fields of their own mapset, so those two lines
 *     are shared by every screen; a screen's own name is a separate row-4 body literal of its
 *     mapset.
 * :output: The ``CCDA_TITLE01`` / ``CCDA_TITLE02`` header titles, their verbatim
 *     40-character ``PIC X(40)`` forms, and the ``SCREEN_NAMES`` map of per-mapset screen
 *     names.
 * :note: Every literal here is reproduced character for character from the source.
 *     ``CCDA_TITLE01`` / ``CCDA_TITLE02`` are the trimmed forms the header renders, because
 *     the shared header centres the title in its own 40-column region exactly as the
 *     copybook's internal padding centred it in the BMS field.
 */

/**
 * :purpose: ``CCDA-TITLE01`` exactly as declared — ``PIC X(40)`` with the padding that
 *     centres the text in the mapset's 40-column ``TITLE01`` field at ``POS=(1,21)``.
 */
export const CCDA_TITLE01_PIC_X40 = '      AWS Mainframe Modernization       ';

/**
 * :purpose: ``CCDA-TITLE02`` exactly as declared — ``PIC X(40)``, padded to centre the
 *     text in the mapset's ``TITLE02`` field at ``POS=(2,21)``.
 */
export const CCDA_TITLE02_PIC_X40 = '              CardDemo                  ';

/**
 * :purpose: First header title line every screen shows (``CCDA-TITLE01``).
 */
export const CCDA_TITLE01 = CCDA_TITLE01_PIC_X40.trim();

/**
 * :purpose: Second header title line every screen shows (``CCDA-TITLE02``).
 */
export const CCDA_TITLE02 = CCDA_TITLE02_PIC_X40.trim();

/**
 * :purpose: The row-4 screen name each mapset carries as its own body literal, keyed by
 *     the 7-character mapset name. ``COSGN00`` has none: the sign-on mapset states its
 *     purpose in a row-5 sentence instead.
 */
export const SCREEN_NAMES = {
  COACTUP: 'Update Account',
  COACTVW: 'View Account',
  COADM01: 'Admin Menu',
  COBIL00: 'Bill Payment',
  COCRDLI: 'List Credit Cards',
  COCRDSL: 'View Credit Card Detail',
  COCRDUP: 'Update Credit Card Details',
  COMEN01: 'Main Menu',
  CORPT00: 'Transaction Reports',
  COTRN00: 'List Transactions',
  COTRN01: 'View Transaction',
  COTRN02: 'Add Transaction',
  COUSR00: 'List Users',
  COUSR01: 'Add User',
  COUSR02: 'Update User',
  COUSR03: 'Delete User',
} as const;

/**
 * :module: ``frontend/src/pages/cardSelection.ts``
 * :purpose: The hand-over contract between the card-list screen ``COCRDLI`` and the two
 *     screens it transfers to, ``COCRDSL`` (detail) and ``COCRDUP`` (update). It carries the
 *     composite selection the legacy COMMAREA carried between those programs —
 *     ``CDEMO-CC00-CARD-SELECTED`` and ``CDEMO-CC00-ACCT-SELECTED`` — in the router location
 *     state, which is process memory rather than a URL.
 * :output: The named :ts:type:`CardSelectionState` contract, the
 * :func: `readCardSelection` reader, the two screen routes, and the
 * :func: `resolveExitRoute` PF3 target resolver.
 * :note: The selection travels in router location state, never in a path segment or a
 *     query string, so a card number does not reach the address bar, a bookmark, or an
 *     outbound ``Referer`` header. It DOES reach the session-history entry: the router stores
 *     location state in ``history.state.usr``, which the browser serializes with the entry. A
 *     reload therefore arrives WITH the selection and the receiving screen re-issues the keyed
 *     read; only a fresh navigation to the route arrives without one and falls back to the
 *     entry fields.
 */

/** :purpose: Route of the card-detail screen ``COCRDSL`` (CICS ``CCDL``). */
export const CARD_DETAIL_ROUTE = '/cards/view';

/** :purpose: Route of the card-update screen ``COCRDUP`` (CICS ``CCUP``). */
export const CARD_UPDATE_ROUTE = '/cards/update';

/** :purpose: Route of the card-list screen ``COCRDLI`` (CICS ``CCLI``). */
export const CARD_LIST_ROUTE = '/cards';

/**
 * :purpose: Route of the main menu ``COMEN01`` (CICS ``CM00``) — ``LIT-MENUPGM``, the
 *     PF3 target both card screens fall back to when no caller is recorded.
 */
export const MAIN_MENU_ROUTE = '/menu';

/**
 * :purpose: The composite card selection handed from one card screen to the next.
 * :field cardNumber: the selected sixteen-digit card number (``CARDSID``).
 * :field accountId: the account id the list screen was browsing (``ACCTSID``),
 *     completing the composite selection ``COCRDSLC`` edits; absent when the
 *     browse was not account-scoped.
 * :field from: the route of the screen that handed the selection over, carrying what
 *     ``CDEMO-FROM-PROGRAM`` carries in the COMMAREA. Absent when the screen was entered
 *     directly, which is the ``CDEMO-FROM-PROGRAM EQUAL SPACES`` case.
 */
export interface CardSelectionState {
  cardNumber: string;
  accountId?: string;
  from?: string;
  browse?: CardBrowseState;
}

/**
 * :purpose: The two browse filters the card-list screen was running under, carried to the
 *     screen it transfers to and handed straight back when that screen returns control. These
 *     are ``CDEMO-ACCT-ID`` and ``CDEMO-CARD-NUM`` — genuine ``COCOM01Y`` COMMAREA fields,
 *     which is why they survive an ``XCTL`` and why ``COCRDLIC`` can repaint them on re-entry:
 *     L840-L867 evaluates them and moves each one back into ``ACCTSIDO`` / ``CARDSIDO``, so
 *     the operator returns to the browse they set up instead of an unfiltered screen they must
 *     key again.
 * :field accountId: the account filter (``CDEMO-ACCT-ID``), empty when none was supplied.
 * :field cardNumber: the card filter (``CDEMO-CARD-NUM``), empty when none was supplied.
 * :note: The PAGE is deliberately NOT carried. ``WS-CA-SCREEN-NUM`` lives in
 *     ``WS-THIS-PROGCOMMAREA``, the program-private area appended after ``CARDDEMO-COMMAREA``,
 *     and ``COCRDLIC`` transfers with ``COMMAREA(CARDDEMO-COMMAREA)`` alone (L540, L568) — the
 *     extension does not travel, so on re-entry the program has no page to resume and browses
 *     from the first screen. Restoring the page would be MORE than the source does.
 */
export interface CardBrowseState {
  accountId: string;
  cardNumber: string;
}

/**
 * :purpose: Read a trimmed string member from an unknown router state record.
 * :param record: the state record.
 * :param key: the member to read.
 * :returns: the trimmed value, or the empty string when it is absent or blank.
 */
function readMember(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  return typeof value === 'string' ? value.trim() : '';
}

/**
 * :purpose: Read the card selection handed over by the card-list screen.
 * :param state: the router location state, of unknown shape.
 * :returns: the selection, with empty strings for members that did not arrive.
 */
export function readCardSelection(state: unknown): {
  cardNumber: string;
  accountId: string;
  from: string;
  browse: CardBrowseState | undefined;
} {
  if (typeof state !== 'object' || state === null) {
    return { cardNumber: '', accountId: '', from: '', browse: undefined };
  }
  const record = state as Record<string, unknown>;
  return {
    cardNumber: readMember(record, 'cardNumber'),
    accountId: readMember(record, 'accountId'),
    from: readMember(record, 'from'),
    browse: readCardBrowse(record.browse),
  };
}

/**
 * :purpose: Read the card-list browse filters a screen was handed, so a screen returning
 *     control can hand them straight back.
 * :param state: the ``browse`` member of a router location state, of unknown shape.
 * :returns: the two filters, or ``undefined`` when neither arrived.
 */
export function readCardBrowse(state: unknown): CardBrowseState | undefined {
  if (typeof state !== 'object' || state === null) {
    return undefined;
  }
  const record = state as Record<string, unknown>;
  const accountId = readMember(record, 'accountId');
  const cardNumber = readMember(record, 'cardNumber');
  if (accountId === '' && cardNumber === '') {
    // Neither COMMAREA field carries a filter, which is the same state as arriving with
    // none: the browse starts unfiltered from the first screen.
    return undefined;
  }
  return { accountId, cardNumber };
}

/**
 * :purpose: Resolve where PF3 leaves a card screen. ``COCRDSLC`` L308-321 and
 *     ``COCRDUPC`` L442-455 both answer PF3 by transferring to ``CDEMO-FROM-PROGRAM``,
 *     substituting ``LIT-MENUPGM`` only when no caller was recorded — so a screen reached
 *     from the card list returns to the card list, and one reached from the main menu
 *     returns to the main menu instead of being dropped somewhere the operator never was.
 * :param from: the recorded caller route, empty when none arrived.
 * :returns: the route PF3 navigates to.
 */
export function resolveExitRoute(from: string): string {
  return from === '' ? MAIN_MENU_ROUTE : from;
}

/**
 * :module: ``frontend/src/pages/cardSelection.ts``
 * :purpose: The hand-over contract between the card-list screen ``COCRDLI`` and the
 *     two screens it transfers to, ``COCRDSL`` (detail) and ``COCRDUP`` (update).
 *     It carries the composite selection the legacy COMMAREA carried between those
 *     programs — ``CDEMO-CC00-CARD-SELECTED`` and ``CDEMO-CC00-ACCT-SELECTED`` — in
 *     the router location state, which is process memory rather than a URL.
 * :output: The named :ts:type:`CardSelectionState` contract, the
 *     :func:`readCardSelection` reader, and the two screen routes.
 * :note: The selection travels in location state and never in a path segment or a
 *     query string, so a card number is not written to the address bar, the session
 *     history, a bookmark, or an outbound ``Referer`` header. A reload therefore
 *     arrives with no selection, exactly as a 3270 transaction started without a
 *     COMMAREA does, and the receiving screen falls back to its own entry fields.
 */

/** :purpose: Route of the card-detail screen ``COCRDSL`` (CICS ``CCDL``). */
export const CARD_DETAIL_ROUTE = '/cards/view';

/** :purpose: Route of the card-update screen ``COCRDUP`` (CICS ``CCUP``). */
export const CARD_UPDATE_ROUTE = '/cards/update';

/**
 * :purpose: The composite card selection handed from one card screen to the next.
 * :field cardNumber: the selected sixteen-digit card number (``CARDSID``).
 * :field accountId: the account id the list screen was browsing (``ACCTSID``),
 *     completing the composite selection ``COCRDSLC`` edits; absent when the
 *     browse was not account-scoped.
 */
export interface CardSelectionState {
  cardNumber: string;
  accountId?: string;
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
} {
  if (typeof state !== 'object' || state === null) {
    return { cardNumber: '', accountId: '' };
  }
  const record = state as Record<string, unknown>;
  return {
    cardNumber: readMember(record, 'cardNumber'),
    accountId: readMember(record, 'accountId'),
  };
}

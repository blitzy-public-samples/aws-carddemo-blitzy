/**
 * :module: ``frontend/src/api/cards.ts``
 * :purpose: Domain API module for the three CardDemo card screens —
 *   ``CardListPage`` (``COCRDLI`` / CICS ``CCLI``, ``app/cbl/COCRDLIC.cbl``),
 *   ``CardDetailPage`` (``COCRDSL`` / CICS ``CCDL``, ``app/cbl/COCRDSLC.cbl``),
 *   and ``CardUpdatePage`` (``COCRDUP`` / CICS ``CCUP``,
 *   ``app/cbl/COCRDUPC.cbl``). It re-expresses the legacy alternate-index browse
 *   (``CXACAIX`` -> ``findByAccountId``), single-record read, and
 *   read-validate-rewrite update as REST calls against the ``card-service`` card
 *   routes on the api-gateway, using the shared axios instance so session,
 *   correlation-id, and error normalization are applied uniformly.
 * :output: The named async functions ``listCards`` (``GET /cards``, 7 rows per
 *   page), ``getCard`` (``POST /cards/detail``), and ``updateCard``
 *   (``PUT /cards``). Neither of the latter two puts the card number in the URL:
 *   a PAN in a path or query string is written verbatim into every access log,
 *   proxy log and trace along the request path, so the key travels in the body.
 * :note: The 16-digit card number (PAN) is always a ``string``: it exceeds
 *   ``Number.MAX_SAFE_INTEGER`` and preserves leading zeros, so it is never
 *   typed as ``number``. Request/response DTOs are passed through untouched to
 *   preserve wire formats — money as ``string``, dates as ``YYYY-MM-DD``,
 *   single-character status flags, and the legacy misspelling
 *   ``cardExpiraionDate``.
 * :note: The 7-rows-per-page contract is enforced by ``card-service`` (the
 *   backend page size), so this module sends only the one-based ``page`` index
 *   and relies on the backend default rather than hard-coding a page size.
 * :note: The card number reaches these routes on the same-origin request path
 *   and query string only. It is never placed in an application URL, so it does
 *   not enter the address bar, the session history, or an outbound ``Referer``.
 *   The gateway masks PAN-shaped digit runs before a request path is logged and
 *   before it is echoed on an error envelope.
 */

import apiClient from './client';
import type {
  CardListRequestDto,
  CardListResponseDto,
  CardDetailResponseDto,
  CardUpdateRequestDto,
  CardUpdateResponseDto,
} from '../types';

/**
 * :purpose: List cards for the ``CardListPage`` screen (CICS ``CCLI`` /
 *   ``COCRDLIC``). The account-scoped browse can additionally narrow to a single
 *   card number, and pages 7 rows at a time.
 * :param request: optional ``accountId`` and ``cardNum`` filters, the one-based
 *   ``page`` index, the ``aid`` paging key (``PF7`` / ``PF8``), and the ``action``
 *   plus ``selectedCardNumber`` row selection; carried as query parameters
 *   (undefined values are omitted by axios and never sent as empty strings). The
 *   card filter is sent under the ``cardNumber`` parameter name bound by the
 *   ``card-service`` card-list route, while the client-side field keeps its
 *   ``cardNum`` DTO spelling.
 * :returns: the page of card rows together with the server paging state, the
 *   resolved row selection, and the legacy banners, as a
 *   :ts:type:`CardListResponseDto`.
 */
export async function listCards(
  request: CardListRequestDto,
): Promise<CardListResponseDto> {
  const response = await apiClient.get<CardListResponseDto>('/cards', {
    params: {
      accountId: request.accountId,
      cardNumber: request.cardNum,
      page: request.page,
      aid: request.aid,
      action: request.action,
      selectedCardNumber: request.selectedCardNumber,
    },
  });
  return response.data;
}

/**
 * :purpose: Fetch a single card's read-only detail for the ``CardDetailPage``
 *   screen (CICS ``CCDL`` / ``COCRDSLC``). The read is a POST because its key is
 *   a Primary Account Number and therefore travels in the request body, never in
 *   the URL; the operation itself reads and does not modify the card.
 * :param cardNumber: the 16-digit card number (PAN) as a ``string``; sent in the
 *   request body.
 * :param accountId: the ``ACCTSID`` the screen collects alongside ``CARDSID``,
 *   completing the composite selection ``COCRDSLC`` requires (``2200-EDIT-MAP-
 *   INPUTS``). Omitted from the body when ``undefined``.
 * :returns: the card detail as a :ts:type:`CardDetailResponseDto`.
 */
export async function getCard(
  cardNumber: string,
  accountId?: string,
): Promise<CardDetailResponseDto> {
  const response = await apiClient.post<CardDetailResponseDto>('/cards/detail', {
    cardNumber,
    accountId,
  });
  return response.data;
}

/**
 * :purpose: Update a card's editable fields for the ``CardUpdatePage`` screen
 *   (CICS ``CCUP`` / ``COCRDUPC``). The target card number travels in the body
 *   alongside the mutable fields -- never in the URL, because a PAN in a path or
 *   query string is recorded by every log along the way. The mutable fields are
 *   forwarded untouched so the ``cardExpiraionDate`` spelling and the
 *   single-character status flag are preserved.
 * :param cardNumber: the 16-digit card number (PAN) as a ``string``; sent in the
 *   request body as the record's identifier, never written by the service.
 * :param request: the editable card fields to persist.
 * :param accountId: the ``ACCTSID`` completing the composite selection, as on
 *   :func:`getCard`. Omitted from the body when ``undefined``.
 * :returns: the refreshed card record as a :ts:type:`CardUpdateResponseDto`.
 */
export async function updateCard(
  cardNumber: string,
  request: CardUpdateRequestDto,
  accountId?: string,
): Promise<CardUpdateResponseDto> {
  const response = await apiClient.put<CardUpdateResponseDto>('/cards', {
    ...request,
    cardNumber,
    accountId,
  });
  return response.data;
}

/**
 * :module: ``frontend/src/api/transactions.ts``
 * :purpose: Domain API module for the three online transaction screens —
 *   ``TranListPage`` (paginated list), ``TranViewPage`` (detail), and
 *   ``TranAddPage`` (add). It wraps the transaction routes exposed by the
 *   api-gateway and re-expresses the legacy CICS pseudo-conversational
 *   programs ``COTRN00C`` (transaction ``CT00``, list), ``COTRN01C``
 *   (transaction ``CT01``, view), and ``COTRN02C`` (transaction ``CT02``, add)
 *   as stateless REST calls issued through the shared axios instance.
 * :output: The named exports ``listTransactions`` (``GET /transactions``,
 *   ten rows per page), ``getTransaction`` (``GET /transactions/{id}``), and
 *   ``addTransaction`` (``POST /transactions``).
 * :note: Wire formats are preserved untouched — the 16-digit transaction id,
 *   the ``NUMERIC(11,2)`` monetary amount, and the 26-character origination and
 *   processing timestamps all travel as ``string`` with no numeric coercion.
 * :note: The add path never carries a client-supplied ``tranId``; the server
 *   assigns the 16-digit zero-padded id from a database sequence, replacing the
 *   legacy browse-last-then-increment scheme (rationale in
 *   ``docs/decision-log.md``).
 */

import apiClient from './client';
import type {
  TranListRequestDto,
  TranListResponseDto,
  TranViewResponseDto,
  TranAddRequestDto,
  TranAddResponseDto,
} from '../types';

/**
 * :purpose: List transactions for the ``TranListPage`` screen, re-expressing
 *   CICS transaction ``CT00`` (``COTRN00C``). The service returns at most ten
 *   rows per page — the spec-literal ``COTRN00`` page size — together with the
 *   paging anchors that drive the PF7 (backward) and PF8 (forward) controls.
 * :param request: the list query and paging state. Its optional fields
 *   (starting-id filter, page number, first/last-id anchors, forward-page and
 *   row-selection flags) are forwarded verbatim as query parameters; axios
 *   omits members that are ``undefined``.
 * :returns: a promise resolving to the page of rows plus its pagination state.
 * :note: The ten-rows-per-page size is the backend default and is not sent by
 *   the client, so the server remains the single source of truth for page size.
 */
export async function listTransactions(
  request: TranListRequestDto,
): Promise<TranListResponseDto> {
  const response = await apiClient.get<TranListResponseDto>('/transactions', {
    params: request,
  });
  return response.data;
}

/**
 * :purpose: Fetch a single transaction's full detail for the ``TranViewPage``
 *   screen, re-expressing CICS transaction ``CT01`` (``COTRN01C``).
 * :param transactionId: the 16-digit transaction identifier, carried as a
 *   ``string`` to preserve its fixed width and leading zeros; it is percent-
 *   encoded before being placed in the request path.
 * :returns: a promise resolving to the full transaction detail.
 */
export async function getTransaction(
  transactionId: string,
): Promise<TranViewResponseDto> {
  const response = await apiClient.get<TranViewResponseDto>(
    `/transactions/${encodeURIComponent(transactionId)}`,
  );
  return response.data;
}

/**
 * :purpose: Add a new transaction from the ``TranAddPage`` screen, re-expressing
 *   CICS transaction ``CT02`` (``COTRN02C``).
 * :param request: the entry payload. It deliberately omits ``tranId`` — the
 *   server assigns the 16-digit zero-padded id — so no client-supplied id is
 *   ever transmitted; the monetary amount and timestamps remain ``string``.
 * :returns: a promise resolving to the server-assigned transaction id and an
 *   optional informational message.
 */
export async function addTransaction(
  request: TranAddRequestDto,
): Promise<TranAddResponseDto> {
  const response = await apiClient.post<TranAddResponseDto>(
    '/transactions',
    request,
  );
  return response.data;
}

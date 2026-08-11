/**
 * :module: ``frontend/src/api/transactions.ts``
 * :purpose: Domain API module for the three online transaction screens — ``TranListPage``
 *     (paginated list), ``TranViewPage`` (detail), and ``TranAddPage`` (add). It wraps the
 *     transaction routes exposed by the api-gateway and re-expresses the legacy CICS
 *     pseudo-conversational programs ``COTRN00C`` (transaction ``CT00``, list), ``COTRN01C``
 *     (transaction ``CT01``, view), and ``COTRN02C`` (transaction ``CT02``, add) as stateless
 *     REST calls issued through the shared axios instance.
 * :output: The named exports ``listTransactions`` (``GET /transactions``, ten rows per
 *     page), ``getTransaction`` (``GET /transactions/detail?tranId=``), ``getLastTransaction``
 *     (``GET /transactions/last``), ``addTransaction`` (``POST /transactions``) and the
 *     key-resolution step ``resolveAddKey`` / ``resolveTransactionKeys`` (``POST
 *     /transactions/key``).
 * :note: Wire formats are preserved untouched — the 16-digit transaction id, the
 *     ``NUMERIC(11,2)`` monetary amount, the four-digit category code, the nine-digit merchant
 *     id and the origination and processing timestamps all travel as ``string`` with no
 *     numeric coercion in either direction.
 * :note: On the add path the two timestamps carry the ten-character ``YYYY-MM-DD`` date
 *     the screen collects and are stored in their 26-character fields verbatim, exactly as
 *     ``COTRN02C`` L464-L465 moves ``TORIGDT`` / ``TPROCDT`` into ``TRAN-ORIG-TS`` /
 *     ``TRAN-PROC-TS``; no value is expanded to a full timestamp.
 * :note: The add path never carries a client-supplied ``tranId``; the server assigns the
 *     16-digit zero-padded id from a database sequence, replacing the legacy
 *     browse-last-then-increment scheme (rationale in ``docs/decision-log.md``).
 */

import apiClient from './client';
import type {
  TranListRequestDto,
  TranListResponseDto,
  TranViewResponseDto,
  TranAddRequestDto,
  TranAddResponseDto,
  TranKeyDto,
  TranKeyResponseDto,
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
 *   ``string`` to preserve its fixed width and leading zeros. It travels as a
 *   REQUEST PARAMETER, never as a path segment: ``TRNIDIN`` is an ``X(16)`` field
 *   an operator may fill with any characters, and a value holding a path separator
 *   does not survive a path segment -- an intermediary normalizes the encoded form
 *   back into ``/..`` before routing, which lifted the request out of the ``/api``
 *   prefix and answered it with the SPA document, leaving the screen silently dead.
 *   A query string is not path-normalized, so the value reaches the service verbatim
 *   and misses the read, which is what the legacy ``READ`` does for any key not on
 *   file.
 * :returns: a promise resolving to the full transaction detail.
 */
export async function getTransaction(
  transactionId: string,
): Promise<TranViewResponseDto> {
  const response = await apiClient.get<TranViewResponseDto>('/transactions/detail', {
    params: { tranId: transactionId },
  });
  return response.data;
}

/**
 * :purpose: Fetch the last transaction on file, backing the add screen's
 *   ``F5=Copy Last Tran.`` action (``COTRN02C COPY-LAST-TRAN-DATA``, which reaches the
 *   same record with ``MOVE HIGH-VALUES TO TRAN-ID`` followed by ``STARTBR`` /
 *   ``READPREV``).
 * :returns: a promise resolving to the last transaction's full detail.
 */
export async function getLastTransaction(): Promise<TranViewResponseDto> {
  const response = await apiClient.get<TranViewResponseDto>('/transactions/last');
  return response.data;
}

/**
 * :purpose: Run the add screen's key-field edit (``COTRN02C``
 *   ``VALIDATE-INPUT-KEY-FIELDS``) and return the account/card pair it resolves.
 *   ``PROCESS-ENTER-KEY`` performs that paragraph — cross-reference reads included —
 *   before ``VALIDATE-INPUT-DATA-FIELDS``, so a key that is not on file is reported
 *   ahead of any empty data field; the paragraph also writes the counterpart key back
 *   onto the map, which is why the resolved pair comes back.
 * :param acctId: the ``ACTIDIN`` entry value; omitted when not supplied.
 * :param tranCardNum: the ``CARDNIN`` entry value; omitted when not supplied.
 * :returns: a promise resolving to the resolved account id and card number.
 */
export async function resolveTransactionKeys(
  acctId: string,
  tranCardNum: string,
): Promise<TranKeyResponseDto> {
  // Delegates to the body-carrying route rather than sending the keys as query
  // parameters: one of them is a card number, and a URL -- path or query string -- is
  // recorded verbatim by the browser tier, the gateway and every trace on the path.
  const resolved = await resolveAddKey({
    ...(acctId === '' ? {} : { acctId }),
    ...(tranCardNum === '' ? {} : { tranCardNum }),
  });
  return {
    acctId: resolved.acctId ?? acctId,
    tranCardNum: resolved.tranCardNum ?? tranCardNum,
  };
}

/**
 * :purpose: Add a new transaction from the ``TranAddPage`` screen, re-expressing
 *   CICS transaction ``CT02`` (``COTRN02C``).
 * :param request: the entry payload. It deliberately omits ``tranId`` — the
 *   server assigns the 16-digit zero-padded id — so no client-supplied id is
 *   ever transmitted; every numeric member and both timestamps remain ``string``.
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

/**
 * :purpose: Resolve the add screen's two key fields from whichever one the operator
 *   supplied, re-expressing ``COTRN02C VALIDATE-INPUT-KEY-FIELDS``. That paragraph
 *   runs BEFORE the eleven data-field blank guards, and the cross-reference read
 *   inside it is what publishes ``Account ID NOT found...`` and
 *   ``Card Number NOT found...``; calling it as its own step is what keeps those two
 *   literals reachable while a data field is still empty. The paragraph also moves the
 *   counterpart key back into its own map field, which is why both come back.
 * :param request: the key entry, carrying an account id, a card number, or neither.
 *   It is a POST body because one member is a card number and a URL is recorded
 *   verbatim by every intermediary on the path.
 * :returns: a promise resolving to both keys at their declared widths.
 */
export async function resolveAddKey(
  request: TranKeyDto,
): Promise<TranKeyDto> {
  const response = await apiClient.post<TranKeyDto>('/transactions/key', request);
  return response.data;
}

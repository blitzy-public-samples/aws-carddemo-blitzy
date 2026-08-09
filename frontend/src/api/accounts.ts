/**
 * :module: ``frontend/src/api/accounts.ts``
 * :purpose: Domain API module for the CardDemo account screens —
 *   ``AccountViewPage`` (read-only account + customer detail) and
 *   ``AccountUpdatePage`` (optimistic-lock update). Wraps the account routes on
 *   the api-gateway, re-expressing CICS transaction ``CAVW`` (program
 *   ``COACTVWC``) as ``GET /accounts/{id}`` and CICS transaction ``CAUP``
 *   (program ``COACTUPC``, read-snapshot-compare-rewrite concurrency) as
 *   ``PUT /accounts/{id}`` over the shared ``apiClient``.
 * :output: The named functions ``getAccount`` and ``updateAccount``.
 * :note: Wire formats are preserved verbatim — monetary fields stay ``string``,
 *   dates stay ``YYYY-MM-DD`` strings, and the misspelled ``acctExpiraionDate``
 *   is passed through untouched. The optimistic-lock ``version`` snapshot is
 *   echoed on update, never manufactured or incremented here; a concurrent
 *   modification is surfaced by ``apiClient`` as an ``ApiError`` (HTTP ``409``,
 *   ``isOptimisticLockConflict``) and is intentionally not caught in this module
 *   so the page can render the conflict banner.
 */

import apiClient from './client';
import type {
  AccountViewResponseDto,
  AccountUpdateRequestDto,
  AccountUpdateResponseDto,
} from '../types';

/**
 * :purpose: Fetch the joined, read-only account + customer detail for the
 *   ``AccountViewPage`` (CICS ``CAVW``, program ``COACTVWC``) via
 *   ``GET /accounts/{id}``.
 * :param accountId: the 11-digit account identifier as a ``string``; leading
 *   zeros and field width are preserved (never coerced to ``number``).
 * :returns: a ``Promise`` resolving to the :ts:type:`AccountViewResponseDto`
 *   payload (response body passed through unchanged by ``apiClient``).
 */
export async function getAccount(
  accountId: string,
): Promise<AccountViewResponseDto> {
  const response = await apiClient.get<AccountViewResponseDto>(
    `/accounts/${accountId}`,
  );
  return response.data;
}

/**
 * :purpose: Persist the edited account + customer fields for the
 *   ``AccountUpdatePage`` (CICS ``CAUP``, program ``COACTUPC``) in a single
 *   transactional unit using optimistic locking, via ``PUT /accounts/{id}``.
 * :param accountId: the 11-digit account identifier as a ``string``; leading
 *   zeros and field width are preserved (never coerced to ``number``).
 * :param request: the :ts:type:`AccountUpdateRequestDto` body — the ``version``
 *   snapshot read at display time plus the editable account + customer fields
 *   (including the misspelled ``acctExpiraionDate``); passed through untouched.
 * :returns: a ``Promise`` resolving to the refreshed
 *   :ts:type:`AccountUpdateResponseDto` (carrying the incremented ``version``).
 * :raises ApiError: HTTP ``409`` (``isOptimisticLockConflict``) when the record
 *   was changed concurrently; propagated unchanged so ``AccountUpdatePage`` can
 *   render the conflict banner (never caught or auto-retried here).
 */
export async function updateAccount(
  accountId: string,
  request: AccountUpdateRequestDto,
): Promise<AccountUpdateResponseDto> {
  const response = await apiClient.put<AccountUpdateResponseDto>(
    `/accounts/${accountId}`,
    request,
  );
  return response.data;
}

/**
 * :purpose: Run the ``COACTUPC`` edit pass over the entered values without rewriting
 *   anything, via ``POST /accounts/{id}/validate`` — the ENTER half of CICS ``CAUP``.
 *   ``1200-EDIT-MAP-INPUTS`` runs inside the legacy program, so the edits and their
 *   frozen literals live in the service and are exercised here rather than reproduced a
 *   second time in the browser, where the two copies could drift.
 * :param accountId: the 11-digit account identifier as a ``string``; leading zeros and
 *   field width are preserved (never coerced to ``number``).
 * :param request: the :ts:type:`AccountUpdateRequestDto` body — the same shape the
 *   rewrite sends, so the pass edits exactly the submission that would be written.
 * :returns: a ``Promise`` resolving to ``void`` when every edit passes (HTTP ``204``).
 * :raises ApiError: HTTP ``400``/``422`` carrying the first failing edit's message and,
 *   in ``fieldErrors``, the property it faulted; propagated unchanged so
 *   ``AccountUpdatePage`` can publish the legacy literal and mark that field.
 */
export async function validateAccountUpdate(
  accountId: string,
  request: AccountUpdateRequestDto,
): Promise<void> {
  await apiClient.post<void>(`/accounts/${accountId}/validate`, request);
}

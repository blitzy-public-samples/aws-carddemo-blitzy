/**
 * :module: ``frontend/src/api/billpay.ts``
 * :purpose: Domain API module for the bill-payment screen ``BillPayPage``. Wraps
 *   the ``billpay`` route exposed by the api-gateway, re-expressing the legacy
 *   CICS transaction ``CB00`` / program ``COBIL00C`` (pay the outstanding
 *   account balance in full against available credit, where available credit is
 *   credit limit minus current balance) as a single REST call
 *   (``app/cbl/COBIL00C.cbl``).
 * :output: The named export :ts:func:`payBill`, which submits a
 *   :ts:type:`BillPayRequestDto` and resolves to a :ts:type:`BillPayResponseDto`.
 * :note: This module is a thin data-layer wrapper only. It performs no
 *   available-credit or balance arithmetic and no reformatting; that business
 *   logic lives in the backend ``BillPaymentService``. The account id and every
 *   monetary field remain ``string`` end-to-end so fixed-width, zero-padded ids
 *   and packed-decimal scale survive without floating-point drift.
 */

import apiClient from './client';
import type { BillPayRequestDto, BillPayResponseDto } from '../types';

/**
 * :purpose: Submit a bill payment for a single account, re-expressing CICS
 *   transaction ``CB00`` (program ``COBIL00C``) as ``POST /billpay`` on the
 *   api-gateway. The request is forwarded to the backend ``BillPaymentService``,
 *   which reads the account, posts a full-balance payment transaction, and
 *   returns the resulting balance.
 * :param request: the bill-payment request body. ``request.accountId`` is the
 *   11-digit, zero-padded account id as a ``string`` (never a ``number``, so the
 *   width and any leading zeros are preserved); ``request.confirm`` is the
 *   single-character ``'Y'``/``'N'`` operator confirmation (``CONFIRM`` ``X(1)``).
 *   The DTO is forwarded to the server untouched.
 * :returns: a ``Promise`` resolving to the :ts:type:`BillPayResponseDto` returned
 *   by the service (echoed account id, current balance, generated transaction id
 *   when a payment was made, and the confirmation/error message). Monetary values
 *   are left as the server-provided decimal strings and are not reformatted.
 */
export async function payBill(
  request: BillPayRequestDto,
): Promise<BillPayResponseDto> {
  const response = await apiClient.post<BillPayResponseDto>('/billpay', request);
  return response.data;
}

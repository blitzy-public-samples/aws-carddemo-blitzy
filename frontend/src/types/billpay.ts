/**
 * :module: billpay
 * :purpose: Request/response DTO types for the bill-payment screen ``BillPayPage``
 *   (``COBIL00`` / ``COBIL00C`` / transaction ``CB00``), binding the
 *   ``POST /billpay`` request body and response of the ``billpay-service``. Bill
 *   payment pays the full outstanding balance against available credit (credit limit
 *   minus current balance).
 * :output: :ts:type:`BillPayRequestDto` and :ts:type:`BillPayResponseDto`.
 * :note: Member names mirror the backend ``BillPayment*Dto`` JSON contracts
 *   (camelCase) so axios payloads bind without field remapping. Monetary amounts and
 *   identifiers are carried as ``string`` to preserve packed-decimal scale and
 *   fixed-width, zero-padded ids without floating-point drift.
 */

/**
 * :purpose: Request body for ``POST /billpay``. Mirrors the backend
 *   ``BillPaymentRequestDto`` — the account to pay in full plus the operator's
 *   Y/N confirmation.
 * :field accountId: account id being paid (``ACTIDIN``); an 11-character,
 *   zero-padded numeric string.
 * :field confirm: single-character confirmation (``CONFIRM``); ``'Y'`` executes the
 *   payment, ``'N'`` or blank declines it. Typed ``string`` (not a ``'Y' | 'N'``
 *   union) because the raw field may be blank before the operator confirms.
 */
export interface BillPayRequestDto {
  accountId: string;
  confirm: string;
}

/**
 * :purpose: Response for ``POST /billpay``. Mirrors the backend
 *   ``BillPaymentResponseDto``: the paid account with its balance, the generated
 *   transaction id, and the confirmation / error banner.
 * :field accountId: account id that was paid, echoed from the request.
 * :field currentBalance: account balance as a decimal money string.
 * :field transactionId: 16-digit zero-padded id of the posted bill-payment
 *   transaction, or ``null`` when no payment was made (declined).
 * :field message: confirmation ("Payment successful...") or error text, or ``null``.
 */
export interface BillPayResponseDto {
  accountId: string;
  currentBalance: string;
  transactionId: string | null;
  message: string | null;
}

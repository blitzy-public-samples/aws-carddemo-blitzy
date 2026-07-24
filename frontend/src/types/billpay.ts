/**
 * Bill-payment DTO types for the CardDemo React SPA.
 *
 * :module: ``frontend/src/types/billpay.ts``
 *
 * :purpose: Declares the request/response DTO contracts for the bill-payment
 *   screen ``BillPayPage``, which replaces the legacy BMS 3270 mapset ``COBIL00``
 *   and its driving CICS program ``COBIL00C`` (transaction ``CB00``). The pair
 *   binds the ``POST /billpay`` request body and JSON response of the
 *   ``billpay-service`` controller so axios responses deserialize without field
 *   remapping.
 *
 * Field names, widths and semantics derive from the BMS symbolic map
 * ``app/cpy-bms/COBIL00.CPY`` (input view ``COBIL0AI`` / output view
 * ``COBIL0AO``) and the behaviour of ``app/cbl/COBIL00C.cbl``. Bill payment pays
 * the full outstanding balance against available credit, where available credit
 * equals the credit limit minus the current balance (AAP 0.4.2).
 *
 * :note: Monetary amounts and identifiers are modelled as ``string`` — never
 *   ``number`` — to preserve COBOL fixed-scale packed-decimal (``COMP-3``) money
 *   precision and fixed-width, zero-padded ids without floating-point drift.
 * :note: Import-free with no runtime side effects, so the module is safe to
 *   import from Jest (jsdom) without triggering environment evaluation.
 */

/**
 * Request body for ``POST /billpay`` (legacy transaction ``CB00``, program
 * ``COBIL00C``).
 *
 * :purpose: Carries the account whose outstanding balance is to be paid in full
 *   together with the operator's Y/N confirmation, mirroring the two
 *   operator-entered fields of the ``COBIL00`` input map ``COBIL0AI``. Payment is
 *   made against available credit (credit limit minus current balance).
 * :member accountId: account id being paid, from ``ACTIDIN`` (``PIC X(11)``). An
 *   11-character, zero-padded numeric string; kept as ``string`` to preserve
 *   leading zeros and field width (never parsed to ``number``).
 * :member confirm: single-character Y/N payment confirmation, from ``CONFIRM``
 *   (``PIC X(01)``). ``'Y'`` executes the payment; ``'N'`` (the default) or a
 *   blank declines it — ``COBIL00C`` rejects any other value with "Invalid value.
 *   Valid values are (Y/N)...". Modelled as ``string`` (not a ``'Y' | 'N'``
 *   union) because the field is a single raw character that may also be blank
 *   before the operator confirms.
 */
export interface BillPayRequestDto {
  accountId: string;
  confirm: string;
}

/**
 * JSON response body for ``POST /billpay`` (legacy transaction ``CB00``, program
 * ``COBIL00C``).
 *
 * :purpose: Echoes the paid account and its balance, mirroring the ``COBIL00``
 *   output map ``COBIL0AO`` and the ``COBIL00C`` success path, which decrements
 *   the balance by the payment amount and reports "Payment successful. Your
 *   Transaction ID is <TRAN-ID>.".
 * :member accountId: account id that was paid, echoed from ``ACTIDIN``
 *   (``PIC X(11)``); an 11-character, zero-padded numeric ``string``.
 * :member currentBalance: account balance rendered on the screen, from ``CURBAL``
 *   (``PIC X(14)`` money display) bound to ``ACCT-CURR-BAL``. A ``string`` money
 *   value preserving COBOL fixed-scale decimal precision.
 * :member newBalance: post-payment balance after ``COBIL00C`` applies
 *   ``ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT``; optional because it is returned
 *   only on a successful payment. A ``string`` money value.
 * :member confirmationNumber: the 16-digit, zero-padded transaction id generated
 *   for the posted bill-payment transaction and echoed in the ``COBIL00C``
 *   "...Your Transaction ID is <TRAN-ID>." confirmation; optional because it is
 *   returned only when a payment transaction was created. A ``string`` id.
 */
export interface BillPayResponseDto {
  accountId: string;
  currentBalance: string;
  newBalance?: string;
  confirmationNumber?: string;
}

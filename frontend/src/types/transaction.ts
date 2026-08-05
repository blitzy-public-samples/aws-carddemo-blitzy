/**
 * :module: transaction
 * :purpose: Request/response DTO types exchanged with the ``transaction-service``
 *   REST API for the three online transaction screens — ``TranListPage`` (list,
 *   ``COTRN00`` / ``CT00``), ``TranViewPage`` (detail, ``COTRN01`` / ``CT01``), and
 *   ``TranAddPage`` (add, ``COTRN02`` / ``CT02``).
 * :output: The list item / request / response DTOs, the view response DTO, and the
 *   add request / response DTOs.
 * :note: Member names mirror the backend ``Transaction*Dto`` JSON contracts
 *   (camelCase) so axios payloads bind without field remapping.
 * :note: Every monetary, identifier and numeric field — ``tranAmt``, the
 *   transaction id, the card number, the account id, ``tranMerchantId`` and
 *   ``tranCatCd`` — is carried as ``string``: the services serialize them as
 *   strings so packed-decimal scale and fixed COBOL field widths (including leading
 *   zeros) survive the wire, and a 16-digit id exceeds
 *   ``Number.MAX_SAFE_INTEGER``. Timestamps are ``string`` in the 26-character
 *   ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` form. No member of this module is a ``number``
 *   except the list paging counters.
 */

/**
 * :purpose: One row of the transaction list screen ``COTRN00``. Mirrors the backend
 *   ``TransactionListItemDto``.
 * :field tranId: 16-character transaction identifier.
 * :field tranDate: display date in ``MM/DD/YY`` form, derived server-side from the
 *   first ten characters of the origination timestamp; empty when that date portion
 *   is absent or unparseable.
 * :field tranDesc: transaction description.
 * :field tranAmt: signed monetary amount as a decimal string (``NUMERIC(11,2)``).
 */
export interface TranListItemDto {
  tranId: string;
  tranDate: string;
  tranDesc: string;
  tranAmt: string;
}

/**
 * :purpose: Query/paging parameters for the transaction list screen ``COTRN00``.
 *   Mirrors the backend ``TransactionListRequestDto``; every field is optional
 *   because the initial load, PF7 / PF8 paging, and row selection each supply a
 *   different subset.
 * :field action: paging/selection action indicator.
 * :field tranIdFilter: starting transaction-id filter positioning the browse.
 * :field pageNumber: one-based page index.
 * :field tranIdFirst: first transaction id on the current page (paging anchor).
 * :field tranIdLast: last transaction id on the current page (paging anchor).
 * :field nextPage: true when a forward page (PF8) is requested.
 * :field selectionFlag: single-character row-selection flag.
 * :field selectedTranId: transaction id chosen for drill-through to the view screen.
 */
export interface TranListRequestDto {
  action?: string;
  tranIdFilter?: string;
  pageNumber?: number;
  tranIdFirst?: string;
  tranIdLast?: string;
  nextPage?: boolean;
  selectionFlag?: string;
  selectedTranId?: string;
}

/**
 * :purpose: Response for the transaction list screen ``COTRN00``. Mirrors the
 *   backend ``TransactionListResponseDto``: up to ten rows plus the pagination state
 *   driving the PF7 / PF8 controls.
 * :field transactions: the page of :ts:type:`TranListItemDto` rows.
 * :field pageNumber: one-based index of the returned page.
 * :field tranIdFirst: first transaction id on the page, or ``null`` when empty.
 * :field tranIdLast: last transaction id on the page, or ``null`` when empty.
 * :field nextPage: true when a further forward page exists.
 * :field selectedTranId: echoed selected transaction id, or ``null``.
 * :field message: informational / error banner text, or ``null``.
 */
export interface TranListResponseDto {
  transactions: TranListItemDto[];
  pageNumber: number;
  tranIdFirst: string | null;
  tranIdLast: string | null;
  nextPage: boolean;
  selectedTranId: string | null;
  message: string | null;
}

/**
 * :purpose: Full transaction detail returned by the view screen ``COTRN01``,
 *   mirroring every persisted ``Transaction`` field under its backend camelCase name.
 * :field tranId: 16-character transaction identifier.
 * :field tranCardNum: 16-character card number / PAN.
 * :field tranTypeCd: two-character transaction type code.
 * :field tranCatCd: four-digit transaction category code (``TRAN-CAT-CD`` 9(04)) as a
 *   zero-padded string.
 * :field tranSource: origination source of the transaction.
 * :field tranDesc: transaction description.
 * :field tranAmt: signed monetary amount as a decimal string (``NUMERIC(11,2)``).
 * :field tranOrigTs: 26-character origination timestamp
 *   (``YYYY-MM-DD-HH.MM.SS.mmmmmm``).
 * :field tranProcTs: 26-character processing timestamp
 *   (``YYYY-MM-DD-HH.MM.SS.mmmmmm``).
 * :field tranMerchantId: 9-digit merchant identifier as a string.
 * :field tranMerchantName: merchant name.
 * :field tranMerchantCity: merchant city.
 * :field tranMerchantZip: merchant postal code.
 */
export interface TranViewResponseDto {
  tranId: string;
  tranCardNum: string;
  tranTypeCd: string;
  tranCatCd: string;
  tranSource: string;
  tranDesc: string;
  tranAmt: string;
  tranOrigTs: string;
  tranProcTs: string;
  tranMerchantId: string;
  tranMerchantName: string;
  tranMerchantCity: string;
  tranMerchantZip: string;
}

/**
 * :purpose: Entry payload for the add-transaction screen ``COTRN02``. ``tranId`` is
 *   intentionally absent because the server assigns the 16-digit zero-padded id.
 * :field acctId: 11-digit account identifier. Optional: the service requires
 *   *either* ``acctId`` *or* ``tranCardNum`` ("Account or Card Number must be
 *   entered").
 * :field tranCardNum: 16-character card number. Optional under the same
 *   account-or-card rule as ``acctId``.
 * :field tranTypeCd: two-character transaction type code.
 * :field tranCatCd: four-digit transaction category code (``TRAN-CAT-CD`` 9(04)) as a
 *   zero-padded string. A blank value is reported with the legacy category-code
 *   message rather than being coerced to zero.
 * :field tranSource: origination source of the transaction.
 * :field tranDesc: transaction description.
 * :field tranAmt: signed monetary amount as a decimal string (``NUMERIC(11,2)``).
 * :field tranOrigTs: origination timestamp. The screen collects the ``YYYY-MM-DD``
 *   date portion (``TORIGDT``, ten characters) and the service stores the submitted
 *   value verbatim in the 26-character field, exactly as ``COTRN02C`` L464 moves the
 *   ten-character screen field into ``TRAN-ORIG-TS``; it is NOT expanded to a full
 *   timestamp.
 * :field tranProcTs: processing timestamp, collected as the ``YYYY-MM-DD`` date
 *   portion (``TPROCDT``) and stored verbatim on the same terms (``COTRN02C``
 *   L465).
 * :field tranMerchantId: 9-digit merchant identifier as a string.
 * :field tranMerchantName: merchant name.
 * :field tranMerchantCity: merchant city.
 * :field tranMerchantZip: merchant postal code.
 * :field confirm: single-character confirmation flag (``'Y'`` / ``'N'``); optional,
 *   supplied on the confirmation pass.
 */
export interface TranAddRequestDto {
  acctId?: string;
  tranCardNum?: string;
  tranTypeCd: string;
  tranCatCd: string;
  tranSource: string;
  tranDesc: string;
  tranAmt: string;
  tranOrigTs: string;
  tranProcTs: string;
  tranMerchantId: string;
  tranMerchantName: string;
  tranMerchantCity: string;
  tranMerchantZip: string;
  confirm?: string;
}

/**
 * :purpose: Response for a successful add on screen ``COTRN02``.
 * :field tranId: 16-digit zero-padded transaction identifier assigned by the server.
 * :field message: informational / error banner text, or ``null``.
 */
export interface TranAddResponseDto {
  tranId: string;
  message: string | null;
}

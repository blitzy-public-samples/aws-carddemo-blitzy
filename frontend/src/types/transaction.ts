/**
 * :module: transaction
 * :purpose: Declares the request / response DTO interfaces exchanged between the
 *   CardDemo React SPA and the ``transaction-service`` REST API for the three
 *   online transaction screens — ``TranListPage`` (list), ``TranViewPage``
 *   (detail), and ``TranAddPage`` (add). Types-only module: it contributes no
 *   runtime code beyond the type layer and is therefore safe to import from Jest
 *   (jsdom) without triggering environment evaluation.
 * :note: Field names and value formats mirror the frozen backend
 *   ``com.carddemo.common.domain.Transaction`` JSON contract (camelCase) so axios
 *   responses deserialize without field remapping. The screen field inventory is
 *   derived from the BMS symbolic-map copybooks ``COTRN00.CPY`` (list, program
 *   ``COTRN00C``, transaction ``CT00``), ``COTRN01.CPY`` (view, ``COTRN01C``,
 *   ``CT01``), and ``COTRN02.CPY`` (add, ``COTRN02C``, ``CT02``); the BMS attribute
 *   bytes (length / flag / colour sub-fields) are ignored.
 * :note: Monetary values (``tranAmt``) are carried as ``string`` to preserve the
 *   COBOL ``S9(09)V99`` packed-decimal scale (``NUMERIC(11,2)``) exactly and avoid
 *   IEEE-754 rounding; identifier fields (transaction id, card number, account id,
 *   merchant id) are carried as ``string`` to preserve their fixed field widths and
 *   leading zeros; the 26-character timestamps are carried as ``string``. The
 *   numeric category code (``tranCatCd``) is the sole ``number`` field. Rationale is
 *   recorded in ``docs/decision-log.md``.
 * :note: The add flow never supplies ``tranId``: the server assigns the 16-digit
 *   zero-padded identifier from a database sequence. This replaces the legacy
 *   browse-last-then-increment mechanism (``COTRN02C``); the observable id format is
 *   preserved (see ``docs/decision-log.md`` and AAP 0.6.5).
 */

import type { Page } from './common';

/**
 * :purpose: One row of the transaction list screen ``COTRN00`` (``TranListPage``),
 *   assembled from the per-row symbolic-map fields ``TRNID<nn>`` (X16),
 *   ``TDATE<nn>`` (X8), ``TDESC<nn>`` (X26), and ``TAMT<nnn>`` (X12).
 * :field tranId: 16-character transaction identifier (``TRNID<nn>``; backend
 *   ``Transaction.tranId``).
 * :field tranProcessDate: display date rendered in the list ``TDATE<nn>`` column.
 *   Program ``COTRN00C`` derives it from the transaction origination timestamp
 *   (``TRAN-ORIG-TS`` date portion, formatted ``MM/DD/YY``); optional because a row
 *   is blank-padded when fewer than ten transactions remain on the page.
 * :field tranDesc: transaction description (``TDESC<nn>``; backend
 *   ``Transaction.tranDesc``).
 * :field tranAmt: signed monetary amount as a decimal string (``TAMT<nnn>``;
 *   backend ``Transaction.tranAmt``, ``NUMERIC(11,2)``).
 */
export interface TranListItemDto {
  tranId: string;
  tranProcessDate?: string;
  tranDesc: string;
  tranAmt: string;
}

/**
 * :purpose: Query parameters for the transaction list screen ``COTRN00``.
 * :field tranId: optional starting transaction-id filter, mirroring the BMS
 *   ``TRNIDIN`` (X16) entry field; when present the list is positioned at or after
 *   this identifier.
 * :field page: optional one-based page number driving PF7 (page-back) / PF8
 *   (page-forward) paging at ten rows per page; the first page is served when
 *   omitted.
 */
export interface TranListRequestDto {
  tranId?: string;
  page?: number;
}

/**
 * :purpose: Paged response for the transaction list screen ``COTRN00``, reusing the
 *   shared :ts:type:`Page` wrapper from ``./common``. Each page holds up to ten
 *   :ts:type:`TranListItemDto` rows together with the pagination state that enables
 *   the PF7 / PF8 controls.
 */
export type TranListResponseDto = Page<TranListItemDto>;

/**
 * :purpose: Full transaction detail returned by the view screen ``COTRN01``
 *   (``TranViewPage``), mirroring every persisted
 *   ``com.carddemo.common.domain.Transaction`` field with its exact backend
 *   camelCase name.
 * :field tranId: 16-character transaction identifier (``TRNID`` X16).
 * :field tranCardNum: 16-character card number / PAN (``CARDNUM`` X16).
 * :field tranTypeCd: two-character transaction type code (``TTYPCD`` X2).
 * :field tranCatCd: numeric transaction category code (``TCATCD`` X4; backend
 *   ``Integer``).
 * :field tranSource: origination source of the transaction (``TRNSRC`` X10).
 * :field tranDesc: transaction description (``TDESC`` X60 on screen; backend
 *   ``X(100)``).
 * :field tranAmt: signed monetary amount as a decimal string (``TRNAMT`` X12;
 *   ``NUMERIC(11,2)``).
 * :field tranOrigTs: 26-character origination timestamp
 *   (``YYYY-MM-DD-HH.MM.SS.mmmmmm``); the screen field ``TORIGDT`` (X10) shows its
 *   ``YYYY-MM-DD`` date portion.
 * :field tranProcTs: 26-character processing timestamp
 *   (``YYYY-MM-DD-HH.MM.SS.mmmmmm``); the screen field ``TPROCDT`` (X10) shows its
 *   ``YYYY-MM-DD`` date portion.
 * :field tranMerchantId: 9-digit merchant identifier as a string (``MID`` X9;
 *   backend ``Long``, carried as ``string`` to preserve the fixed width).
 * :field tranMerchantName: merchant name (``MNAME`` X30).
 * :field tranMerchantCity: merchant city (``MCITY`` X25).
 * :field tranMerchantZip: merchant postal code (``MZIP`` X10).
 */
export interface TranViewResponseDto {
  tranId: string;
  tranCardNum: string;
  tranTypeCd: string;
  tranCatCd: number;
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
 * :purpose: Entry payload for the add-transaction screen ``COTRN02``
 *   (``TranAddPage``, transaction ``CT02``). Carries the operator-entered fields;
 *   ``tranId`` is intentionally absent because the server assigns it on creation.
 * :field accountId: 11-digit account identifier (``ACTIDIN`` X11). Optional:
 *   program ``COTRN02C`` requires *either* ``accountId`` *or* ``cardNum`` to be
 *   supplied ("Account or Card Number must be entered").
 * :field cardNum: 16-character card number (``CARDNIN`` X16). Optional under the
 *   same account-or-card rule as ``accountId``.
 * :field tranTypeCd: two-character transaction type code (``TTYPCD`` X2).
 * :field tranCatCd: numeric transaction category code (``TCATCD`` X4).
 * :field tranSource: origination source of the transaction (``TRNSRC`` X10).
 * :field tranDesc: transaction description (``TDESC`` X60).
 * :field tranAmt: signed monetary amount as a decimal string (``TRNAMT`` X12;
 *   ``NUMERIC(11,2)``).
 * :field tranOrigTs: origination timestamp. The screen collects the ``YYYY-MM-DD``
 *   date portion (``TORIGDT`` X10); the value is carried under the backend
 *   26-character field name and expanded server-side.
 * :field tranProcTs: processing timestamp, collected as the ``YYYY-MM-DD`` date
 *   portion (``TPROCDT`` X10) and carried under the backend 26-character field name.
 * :field tranMerchantId: 9-digit merchant identifier as a string (``MID`` X9).
 * :field tranMerchantName: merchant name (``MNAME`` X30).
 * :field tranMerchantCity: merchant city (``MCITY`` X25).
 * :field tranMerchantZip: merchant postal code (``MZIP`` X10).
 * :field confirm: single-character confirmation flag (``CONFIRM`` X1, ``'Y'`` /
 *   ``'N'``); optional, supplied on the confirmation pass before the record is
 *   written.
 */
export interface TranAddRequestDto {
  accountId?: string;
  cardNum?: string;
  tranTypeCd: string;
  tranCatCd: number;
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
 * :purpose: Response for a successful add on screen ``COTRN02``, returning the
 *   server-generated identifier of the newly created transaction.
 * :field tranId: 16-digit zero-padded transaction identifier assigned by the
 *   database sequence. The generation mechanism replaces the legacy
 *   browse-last-then-increment approach (``COTRN02C``); the observable 16-digit
 *   zero-padded string format is preserved (see ``docs/decision-log.md``, AAP
 *   0.6.5).
 */
export interface TranAddResponseDto {
  tranId: string;
}

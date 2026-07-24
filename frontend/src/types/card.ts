/**
 * Card list / detail / update DTO types for the CardDemo React SPA.
 *
 * :module: ``frontend/src/types/card.ts``
 *
 * Declares the request / response contracts for the three card screens that
 * replace the legacy BMS 3270 mapsets:
 *
 * - ``CardListPage`` — mapset ``COCRDLI`` (program ``COCRDLIC``, transaction
 *   ``CCLI``); an account-scoped browse rendering 7 rows per page.
 * - ``CardDetailPage`` — mapset ``COCRDSL`` (program ``COCRDSLC``, transaction
 *   ``CCDL``); read-only card detail.
 * - ``CardUpdatePage`` — mapset ``COCRDUP`` (program ``COCRDUPC``, transaction
 *   ``CCUP``); editable card record.
 *
 * :source: BMS symbolic maps ``app/cpy-bms/COCRDLI.CPY``,
 *   ``app/cpy-bms/COCRDSL.CPY``, and ``app/cpy-bms/COCRDUP.CPY`` (the ``<F>I`` /
 *   ``<F>O`` two-view fields; the 3270 attribute bytes are not part of the wire
 *   contract and are omitted).
 * :mirrors: the frozen backend JSON contract of
 *   ``com.carddemo.common.domain.Card`` (and ``CardXref`` for the cross-reference
 *   ids). Member names match the backend camelCase properties exactly so axios
 *   responses deserialize without field remapping.
 *
 * :note cardExpiraionDate: the misspelling (missing the second ``T``) is
 *   intentional and preserved verbatim from the COBOL field
 *   ``CARD-EXPIRAION-DATE``; it is the backend ``Card`` entity's frozen JSON
 *   property name and MUST NOT be "corrected" to the conventionally spelled form
 *   with the second ``T`` (spec-literal fidelity rule).
 * :note cardNum: modelled as ``string``, never ``number`` — a 16-digit PAN
 *   exceeds ``Number.MAX_SAFE_INTEGER`` and a string also preserves any leading
 *   zeros. Account ids are likewise typed ``string`` to preserve their 11-digit
 *   zero-padded field width.
 * :note cardCvvCd: the sensitive card verification value is ``@JsonIgnore`` on the
 *   backend entity and is never serialized to clients, so it is deliberately
 *   absent from every response DTO here.
 * :note messages: the card detail / update screens use ``ERRMSG`` ``X(80)`` and
 *   ``INFOMSG`` ``X(40)`` (wider / narrower than the ``X(78)`` / ``X(45)`` used by
 *   other screens); the shared ``ErrMsg`` / ``InfoMsg`` aliases in ``./common``
 *   are plain ``string`` aliases (width documented, not enforced) and are reused
 *   by the page components rather than redefined here.
 * :note: rationale for these decisions is recorded in ``docs/decision-log.md``.
 *
 * :consumed by: ``CardListPage`` / ``CardDetailPage`` / ``CardUpdatePage``, the
 *   card REST client in ``frontend/src/api``, and the ``card-service`` contract
 *   tests.
 */

import type { Page } from './common';

/**
 * A single row of the card list screen ``COCRDLI``.
 *
 * Derived from the per-row symbolic-map fields ``CRDNUM<n>`` (``X(16)``),
 * ``ACCTNO<n>`` (``X(11)``), and ``CRDSTS<n>`` (``X(01)``) for ``n`` = 1..7. The
 * embossed name is not shown on the list screen and is therefore omitted; the
 * ``CardDetailPage`` carries it instead.
 *
 * :member cardNum: 16-character card number (PAN); mirrors ``Card.cardNum``.
 * :member cardAcctId: owning account id (11 digits); mirrors ``Card.cardAcctId``.
 * :member cardActiveStatus: single-character active-status flag — ``'Y'`` active,
 *   ``'N'`` inactive; mirrors ``Card.cardActiveStatus``.
 */
export interface CardListItemDto {
  cardNum: string;
  cardAcctId: string;
  cardActiveStatus: string;
}

/**
 * Filter and paging parameters for the card list screen ``COCRDLI``.
 *
 * Reproduces the ``ACCTSID`` (``X(11)``) and ``CARDSID`` (``X(16)``) filter
 * fields together with the ``PAGENO`` paging control. Both filters are optional:
 * the legacy program browses by account id via the ``CXACAIX`` alternate index
 * (``findByCardAcctId``) and can additionally narrow to a single card number.
 *
 * :member accountId: optional account-id filter (``ACCTSID``); kept as ``string``.
 * :member cardNum: optional card-number filter (``CARDSID``); kept as ``string``.
 * :member page: optional zero-based page index for the 7-row-per-page browse.
 */
export interface CardListRequestDto {
  accountId?: string;
  cardNum?: string;
  page?: number;
}

/**
 * Paged card-list response: a page of {@link CardListItemDto} rows.
 *
 * Reuses the generic ``Page<T>`` wrapper from ``./common`` (which carries the
 * ``items`` array and the ``PageInfo`` paging state) rather than redefining
 * pagination locally. The card list renders 7 rows per page, matching the legacy
 * ``COCRDLIC`` screen and the ``pageSize`` documented on ``PageInfo``.
 */
export type CardListResponseDto = Page<CardListItemDto>;

/**
 * Read-only card detail response for the ``COCRDSL`` screen.
 *
 * Mirrors the backend ``Card`` JSON contract. The detail screen displays the
 * expiration month and year separately (``EXPMON`` / ``EXPYEAR``); the wire
 * field remains the whole ``cardExpiraionDate`` (``YYYY-MM-DD``) and the page is
 * responsible for splitting it for display. The sensitive ``cardCvvCd`` is never
 * returned and is intentionally absent.
 *
 * :member cardNum: 16-character card number (PAN).
 * :member cardAcctId: owning account id (11 digits).
 * :member cardEmbossedName: name embossed on the card (``CRDNAME`` ``X(50)``).
 * :member cardActiveStatus: single-character active-status flag (``'Y'`` / ``'N'``).
 * :member cardExpiraionDate: expiration date in ``YYYY-MM-DD`` form; the spelling
 *   is the preserved legacy misspelling (missing second ``T``).
 */
export interface CardDetailResponseDto {
  cardNum: string;
  cardAcctId: string;
  cardEmbossedName: string;
  cardActiveStatus: string;
  cardExpiraionDate: string;
}

/**
 * Editable card fields submitted by the ``COCRDUP`` update screen.
 *
 * Carries the fields the legacy ``COCRDUPC`` program allows a user to change —
 * the embossed name (``CRDNAME``), the active-status code (``CRDSTCD``), and the
 * expiration date assembled from ``EXPDAY`` / ``EXPMON`` / ``EXPYEAR`` — plus the
 * identifying keys. ``cardNum`` identifies the target record (also supplied on
 * the request path). The misspelled ``cardExpiraionDate`` is preserved.
 *
 * :member cardNum: target card number (PAN); the update key.
 * :member cardAcctId: owning account id.
 * :member cardEmbossedName: updated embossed cardholder name.
 * :member cardActiveStatus: updated single-character active-status flag.
 * :member cardExpiraionDate: updated expiration date in ``YYYY-MM-DD`` form.
 */
export interface CardUpdateRequestDto {
  cardNum: string;
  cardAcctId: string;
  cardEmbossedName: string;
  cardActiveStatus: string;
  cardExpiraionDate: string;
}

/**
 * Refreshed card record returned after a successful update.
 *
 * The ``card-service`` responds with the re-read record, whose shape is identical
 * to {@link CardDetailResponseDto}; this alias makes that contract explicit while
 * allowing the two to diverge later without touching call sites.
 */
export interface CardUpdateResponseDto extends CardDetailResponseDto {}

/**
 * :module: card
 * :purpose: Request/response DTO types for the three card screens —
 *   ``CardListPage`` (``COCRDLI`` / ``CCLI``, account-scoped browse, 7 rows per
 *   page), ``CardDetailPage`` (``COCRDSL`` / ``CCDL``, read-only detail), and
 *   ``CardUpdatePage`` (``COCRDUP`` / ``CCUP``, editable record).
 * :output: The card list item / request / response DTOs, the detail response DTO,
 *   and the update request / response DTOs.
 * :note: Member names mirror the backend ``Card*Dto`` JSON contracts (camelCase) so
 *   axios payloads bind without field remapping. Card numbers, account ids, and the
 *   customer id are carried as ``string`` to preserve fixed field widths and leading
 *   zeros (a 16-digit PAN also exceeds ``Number.MAX_SAFE_INTEGER``).
 * :note: The misspelling ``cardExpiraionDate`` (missing the second ``T``) is
 *   preserved verbatim from the COBOL field ``CARD-EXPIRAION-DATE`` and must not be
 *   "corrected" (spec-literal fidelity rule).
 * :note: The sensitive ``cardCvvCd`` is never serialized to clients and is
 *   deliberately absent from every DTO here, including the update request.
 */

/**
 * :purpose: One row of the card list screen ``COCRDLI``. Mirrors the backend
 *   ``CardListItemDto``. The embossed name is shown on the detail screen only and is
 *   omitted here.
 * :field cardNum: 16-character card number (PAN).
 * :field cardAcctId: owning account id (11 digits).
 * :field cardActiveStatus: single-character active-status flag (``'Y'`` / ``'N'``).
 */
export interface CardListItemDto {
  cardNum: string;
  cardAcctId: string;
  cardActiveStatus: string;
}

/**
 * :purpose: Filter/paging parameters for the card list screen ``COCRDLI``. Both
 *   filters are optional: the browse is account-scoped via the ``CXACAIX`` alternate
 *   index and can additionally narrow to a single card number.
 * :field accountId: optional account-id filter (``ACCTSID``).
 * :field cardNum: optional card-number filter (``CARDSID``).
 * :field page: optional zero-based page index for the 7-row-per-page browse.
 */
export interface CardListRequestDto {
  accountId?: string;
  cardNum?: string;
  page?: number;
}

/**
 * :purpose: Response for the card list screen ``COCRDLI``. Mirrors the backend
 *   ``CardListResponseDto``, which returns the rows under a ``cards`` array.
 * :field cards: the page of :ts:type:`CardListItemDto` rows (up to 7).
 */
export interface CardListResponseDto {
  cards: CardListItemDto[];
}

/**
 * :purpose: Read-only card detail for the ``COCRDSL`` screen. Mirrors the backend
 *   ``CardDetailResponseDto``. The screen splits the expiration date into month /
 *   year for display; the wire field remains the whole ``cardExpiraionDate``.
 * :field cardNum: 16-character card number (PAN).
 * :field cardAcctId: owning account id (11 digits).
 * :field cardEmbossedName: name embossed on the card.
 * :field cardActiveStatus: single-character active-status flag (``'Y'`` / ``'N'``).
 * :field cardExpiraionDate: expiration date in ``YYYY-MM-DD`` form (preserved
 *   legacy misspelling).
 * :field custId: owning customer id (from the ``CardXref`` cross-reference).
 */
export interface CardDetailResponseDto {
  cardNum: string;
  cardAcctId: string;
  cardEmbossedName: string;
  cardActiveStatus: string;
  cardExpiraionDate: string;
  custId: string;
}

/**
 * :purpose: Editable card fields submitted by the ``COCRDUP`` update screen. Mirrors
 *   the backend ``CardUpdateRequestDto``, which carries only the mutable fields; the
 *   target card number travels in the request path, and ``cardCvvCd`` is never
 *   accepted from the client.
 * :field cardEmbossedName: updated embossed cardholder name.
 * :field cardActiveStatus: updated single-character active-status flag.
 * :field cardExpiraionDate: updated expiration date in ``YYYY-MM-DD`` form.
 */
export interface CardUpdateRequestDto {
  cardEmbossedName: string;
  cardActiveStatus: string;
  cardExpiraionDate: string;
}

/**
 * :purpose: Refreshed card record returned after a successful update; the re-read
 *   record, identical in shape to :ts:type:`CardDetailResponseDto`.
 */
export interface CardUpdateResponseDto extends CardDetailResponseDto {}

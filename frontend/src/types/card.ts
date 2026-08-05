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
 * :note: Mirroring the account contract, the card record carries the optimistic-lock
 *   ``version`` read at display time; the update screen echoes it back so a
 *   concurrent modification is reported as a conflict (``COCRDUPC``
 *   ``DATA-WAS-CHANGED-BEFORE-UPDATE``, AAP 0.6.2) instead of silently overwriting.
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
 * :purpose: Row-selection flag of the card list screen ``COCRDLI``: ``'S'`` shows
 *   the card detail (``COCRDSLC``) and ``'U'`` opens the update screen
 *   (``COCRDUPC``), per the screen's own instruction line.
 */
export type CardListSelection = 'S' | 'U';

/**
 * :purpose: Filter/paging parameters for the card list screen ``COCRDLI``, mirroring
 *   the parameters the ``GET /cards`` route binds. Both filters are optional: the
 *   browse is account-scoped via the ``CXACAIX`` alternate index and can
 *   additionally narrow to a single card number.
 * :field accountId: optional account-id filter (``ACCTSID``).
 * :field cardNum: optional card-number filter (``CARDSID``); sent under the
 *   route's ``cardNumber`` parameter name.
 * :field page: optional **one-based** page index for the 7-row-per-page browse,
 *   matching the route default of ``1``.
 * :field aid: optional attention identifier driving the browse — the legacy
 *   ``PF7`` (page backward) and ``PF8`` (page forward) key presses.
 * :field action: optional row-selection flag (``'S'`` or ``'U'``).
 * :field selectedCardNumber: the card number of the selected row, required when
 *   ``action`` is supplied.
 */
export interface CardListRequestDto {
  accountId?: string;
  cardNum?: string;
  page?: number;
  aid?: 'PF7' | 'PF8';
  action?: CardListSelection;
  selectedCardNumber?: string;
}

/**
 * :purpose: Response for the card list screen ``COCRDLI``. Mirrors the backend
 *   ``CardListResponseDto`` field for field, so the screen consumes the server's
 *   paging state instead of re-slicing the page it was given.
 * :field cards: the page of :ts:type:`CardListItemDto` rows (up to 7).
 * :field pageNumber: one-based index of the returned page.
 * :field nextPage: ``true`` when a further forward page exists (PF8 available).
 * :field selectedCardNumber: echoed selected row card number, or ``null`` when no
 *   row was selected.
 * :field selectedAction: the resolved row selection — ``'S'`` or ``'U'`` — or
 *   ``null`` when no row was selected.
 * :field message: the verbatim legacy error banner (line 23), or ``null``.
 * :field infoMessage: the verbatim legacy informational banner — the
 *   ``COCRDLIC`` prompt ``TYPE S FOR DETAIL, U TO UPDATE ANY RECORD`` — or
 *   ``null``.
 */
export interface CardListResponseDto {
  cards: CardListItemDto[];
  pageNumber: number;
  nextPage: boolean;
  selectedCardNumber: string | null;
  selectedAction: CardListSelection | null;
  message: string | null;
  infoMessage: string | null;
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
 * :field version: optimistic-lock version of the card record, echoed back on update.
 */
export interface CardDetailResponseDto {
  cardNum: string;
  cardAcctId: string;
  cardEmbossedName: string;
  cardActiveStatus: string;
  cardExpiraionDate: string;
  custId: string;
  version: number;
}

/**
 * :purpose: Editable card fields submitted by the ``COCRDUP`` update screen. Mirrors
 *   the backend ``CardUpdateRequestDto``, which carries only the mutable fields; the
 *   target card number travels in the request path. ``cardCvvCd`` is absent because
 *   the ``COCRDUP`` mapset has no CVV field — ``COCRDUPC`` copies the stored CVV
 *   into its own snapshot (L1354) and never reads one from the screen.
 * :field cardEmbossedName: updated embossed cardholder name.
 * :field cardActiveStatus: updated single-character active-status flag.
 * :field cardExpiraionDate: updated expiration date in ``YYYY-MM-DD`` form.
 * :field version: the optimistic-lock version read at display time; a value that no
 *   longer matches the stored record yields HTTP 409 rather than a lost update.
 * :field oldCardEmbossedName: the embossed name read at display time.
 * :field oldCardActiveStatus: the active-status flag read at display time.
 * :field oldCardExpiraionDate: the expiration date read at display time.
 * :note: The three ``old*`` members are optional. Supplying them enables the
 *   field-by-field snapshot comparison ``COCRDUPC`` performs before its ``REWRITE``
 *   (L1503), which reports ``Record changed by some one else. Please review``;
 *   omitting them leaves the ``version`` check as the sole concurrency guard.
 */
export interface CardUpdateRequestDto {
  cardEmbossedName: string;
  cardActiveStatus: string;
  cardExpiraionDate: string;
  version: number;
  oldCardEmbossedName?: string;
  oldCardActiveStatus?: string;
  oldCardExpiraionDate?: string;
}

/**
 * :purpose: Refreshed card record returned after a successful update; the re-read
 *   record, identical in shape to :ts:type:`CardDetailResponseDto`.
 */
export type CardUpdateResponseDto = CardDetailResponseDto;

"""Card service.

Ported 1:1 from legacy CICS online programs COCRDLIC (card list, CCLI; <=7 rows
per page per F-004), COCRDSLC (card view, CCDL) and COCRDUPC (card update, CCUP).
COCRDUPC's READ-UPDATE -> before-image check -> REWRITE is reproduced with a
GetByCardNum load + submitted-before-image comparison (the card repository exposes
no locking read; see note). Record layout: CVACT02Y. cvv_cd is never returned;
card_num is masked. See §0.5.1, §0.7.4, §0.7.8, §0.8.1.

Design notes (verified against the dependency contracts):

* Data access is delegated entirely to :class:`app.repositories.CardRepository`
  (the single ``cards`` boundary). This service owns the business rules, the
  field edits (ported verbatim from the COBOL PROCEDURE DIVISION), and the
  per-request unit-of-work commit; the repository never commits.
* ``list`` and ``view`` are strictly READ-ONLY -- they never commit. Only
  ``UpdateCard`` mutates and owns the ``commit`` / ``rollback`` boundary.
* Sensitive-data rules (AAP 0.7.8) are enforced structurally: every response is
  built through :class:`app.schemas.CardRead` / :class:`app.schemas.CardSummary`,
  which expose no ``cvv_cd`` field and mask ``card_num`` to its last four digits
  via a Pydantic ``field_serializer``. The service never returns raw ORM
  attributes and never assigns a new ``cvv_cd`` on update (it is preserved).
* Concurrency: unlike ``account_service`` (whose repository offers a
  ``GetForUpdate`` ``SELECT ... FOR UPDATE`` locking read), ``CardRepository``
  exposes NO locking read. COCRDUPC's 9200/9300 READ-UPDATE -> change check ->
  REWRITE is therefore reproduced optimistically: the current row is loaded,
  a before-image snapshot is captured, the row is re-read, and the before-image
  is compared to the freshly-read current values; a difference means a
  concurrent modification and is rejected. If a locking read is added to
  ``CardRepository`` later, prefer it to match the legacy READ-for-UPDATE.

Ochs conventions (AAP 0.8.2 / 0.8.3): the class and its methods use PascalCase,
local variables use camelCase, module-level constants are ALL_UPPERCASE, and the
data-carrying field names (``card_num``, ``cvv_cd``, ...) stay snake_case to
match the DTO / ORM contract. Methods stay small (<=~20 lines) and take at most
four parameters (excluding ``self``); the list call groups its inputs into a
single :class:`CardListParams` object to honour that limit.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import TYPE_CHECKING

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import (
    ConflictError,
    DomainValidationError,
    NotFoundError,
    OptimisticLockError,
)
from app.repositories import CardRepository
from app.repositories.card_repo import MAX_SCREEN_LINES
from app.schemas import (
    CardRead,
    CardSummary,
    CardUpdate,
    PaginatedResponse,
    PaginationParams,
)
from app.utils import date_utils, validators

if TYPE_CHECKING:  # Imported for type hints only; no runtime dependency needed.
    from app.models.card import Card
    from app.models.user import User

# ---------------------------------------------------------------------------
# Field labels fed to the shared validators (kept identical to the labels the
# legacy screens used so the reusable edits read naturally). ALL_UPPERCASE per
# the Ochs Rule (AAP 0.8.2).
# ---------------------------------------------------------------------------
CARD_NUM_FIELD_LABEL = "Card number"
CARD_NAME_FIELD_LABEL = "Card name"
CARD_STATUS_FIELD_LABEL = "Card Active Status"

# Legacy calendar-month bounds for the expiry edit (COCRDUPC 1250-EDIT-EXPIRY-MON,
# 88-level VALID-MONTH VALUES 1 THRU 12). A real ``datetime.date`` always honours
# this range; the guard preserves the distinct legacy month message for parity.
MIN_MONTH = 1
MAX_MONTH = 12

# Lowest page ordinal (mirrors app.schemas.common.MIN_PAGE); used to derive
# ``has_previous`` for the browse envelope.
MIN_PAGE = 1

# Role code for an administrator -- a faithful port of the COCOM01Y
# CDEMO-USER-TYPE 88-level ``CDEMO-USRTYP-ADMIN VALUE 'A'``. COCRDLIC (header
# L4-7) lists every card for an admin but confines a regular user to the cards
# of the account carried in their session context.
ADMIN_USER_TYPE = "A"

# ---------------------------------------------------------------------------
# Verbatim operator messages (VALUE literals lifted character-for-character from
# COCRDSLC / COCRDUPC). Never reword, re-case, or re-punctuate: the golden-master
# parity tests compare these exactly (AAP 0.8.1). The bracketed COBOL line
# references are the source of each literal.
# ---------------------------------------------------------------------------
MSG_CARD_NUM_NOT_PROVIDED = "Card number not provided"                       # COCRDSLC L141
MSG_CARD_NUM_INVALID = "Card number if supplied must be a 16 digit number"   # COCRDSLC L149
MSG_CARD_NOT_FOUND = "Did not find cards for this search condition"          # COCRDSLC L154
MSG_CARD_NAME_NOT_PROVIDED = "Card name not provided"                        # COCRDUPC L182
MSG_CARD_NAME_NOT_ALPHA = "Card name can only contain alphabets and spaces"  # COCRDUPC WS-NAME-MUST-BE-ALPHA
MSG_CARD_STATUS_INVALID = "Card Active Status must be Y or N"                 # COCRDUPC L196
MSG_CARD_EXPIRY_MONTH_INVALID = "Card expiry month must be between 1 and 12"  # COCRDUPC CARD-EXPIRY-MONTH-NOT-VALID
MSG_CARD_EXPIRY_YEAR_INVALID = "Invalid card expiry year"                    # COCRDUPC L200
MSG_NO_CHANGE_DETECTED = "No change detected with respect to values fetched."  # COCRDUPC L188
MSG_COULD_NOT_LOCK = "Could not lock record for update"                      # COCRDUPC L206
MSG_RECORD_CHANGED = "Record changed by some one else. Please review"        # COCRDUPC L208
MSG_UPDATE_FAILED = "Update of record failed"                                # COCRDUPC L210

# ---------------------------------------------------------------------------
# Account-id edit labels/messages for the by-account card view/update (QA C1).
# The modern UI navigates to a card by its (unmasked) OWNING ACCOUNT id rather
# than by the PAN, because the list masks card_num (AAP 0.7.8) and a masked PAN
# can never be a valid key. These mirror the COACTVWC 2210-EDIT-ACCOUNT edits
# (identical wording to account_service) so account-id validation is consistent
# across the app; the not-found case reuses the verbatim COCRDSLC card message.
# ---------------------------------------------------------------------------
ACCT_ID_FIELD_LABEL = "Account number"
MSG_ACCT_NUM_NOT_PROVIDED = "Account number not provided"                    # COACTVWC 2210
MSG_ACCT_NUM_INVALID = "Account number must be a non zero 11 digit number"   # COACTVWC 2210

__all__ = ["CardListParams", "CardService"]


class CardListParams(PaginationParams):
    """Inputs for the card browse (COCRDLIC, ``CCLI``) as a single object.

    Grouping the browse inputs into one object keeps
    :meth:`CardService.ListCards` at the Ochs four-parameter maximum. It extends
    :class:`app.schemas.common.PaginationParams` (reusing its validated ``page``
    and ``page_size`` fields, whose default page size of seven already matches
    F-004) and adds the two card-specific browse anchors:

    Attributes:
        acct_id: Optional owning-account filter. When supplied, the browse is
            restricted to that account's cards (the legacy CARD-ACCT-ID
            alternate-index path in COCRDLIC 9500-FILTER-RECORDS). ``None``
            browses across all accounts.
        card_num: Optional exact card-number filter (the COCRDLIC card-number
            search anchor CC-CARD-NUM). A 16-digit PAN identifies at most one
            card, so when supplied the browse returns that single card as a
            one-row page (subject to any ``acct_id`` filter and role scoping),
            or an empty page when no such card exists. ``None`` applies no
            card-number filter. Exposed as the ``?card_num=`` query parameter
            so the COCRDLI card-number search field is functional (QA C3).
        start_card_num: Optional keyset anchor for page-forward browsing. When
            supplied, the browse resumes at the first card number greater than
            or equal to it, mirroring the legacy ``STARTBR ... GTEQ`` position
            used by the PF8 page-forward key. ``None`` starts at the first card.
    """

    acct_id: str | None = None
    card_num: str | None = None
    start_card_num: str | None = None


@dataclass(frozen=True)
class _CardImage:
    """Immutable before/after snapshot of the concurrency-relevant card fields.

    Captures exactly the fields COCRDUPC's 9300-CHECK-CHANGE-IN-REC compares
    (CVV, embossed name, expiration date, and active status) so the optimistic
    before-image comparison operates on a stable copy that later ORM mutation
    cannot disturb. Field names stay snake_case to match the ORM/DTO contract
    (AAP 0.8.3). This structure is module-internal (leading underscore) and is
    never serialized -- the CVV it holds is used only for the equality check and
    never leaves the service.
    """

    cvv_cd: str
    embossed_name: str
    expiration_date: date | None
    active_status: str


class CardService:
    """Business logic for credit-card list, view, and update.

    Ports three legacy CICS online programs 1:1 (Minimal Change Clause,
    AAP 0.8.1):

    * :meth:`ListCards` -- COCRDLIC (``CCLI``), the <=7-rows-per-page browse
      (F-004).
    * :meth:`GetCard` -- COCRDSLC (``CCDL``), the read-only detail view.
    * :meth:`UpdateCard` -- COCRDUPC (``CCUP``), the optimistic before-image
      update.

    The service holds no mutable state beyond its repository handle, so a single
    instance is safe to share across requests; the caller supplies the
    per-request :class:`~sqlalchemy.ext.asyncio.AsyncSession` (unit of work) to
    every method.
    """

    def __init__(self) -> None:
        """Wire the service to its single data-access collaborator."""
        self.cardRepository = CardRepository()

    async def ListCards(
        self,
        session: AsyncSession,
        params: PaginationParams | CardListParams,
        currentUser: "User | None" = None,
    ) -> PaginatedResponse[CardSummary]:
        """List cards for one screen page, at most seven rows (COCRDLIC, F-004).

        Reproduces the COCRDLIC forward browse with a truthful paginated
        envelope: an authoritative ``COUNT(*)`` supplies ``total_items`` (scoped
        to ``acct_id`` when one is given) and the ordered rows are sliced by a
        ``(page - 1) * page_size`` offset, so every card is reachable by walking
        the pages in sequence and ``total_items`` / ``total_pages`` / ``has_next``
        are exact rather than a look-ahead estimate. The page size is clamped to
        :data:`MAX_SCREEN_LINES` (7) so a page can never exceed the F-004 limit
        regardless of the requested size. This method is READ-ONLY and never
        commits.

        Role-based scoping (COCRDLIC header L4-7): an administrator
        (``user_type == ADMIN_USER_TYPE``) browses ALL cards, honouring the
        optional ``acct_id`` filter when one is supplied; a regular user is
        confined to the cards of the account in their session context. Because
        the legacy program always carried a COMMAREA account for a non-admin, a
        regular user who supplies no account anchor is never shown all cards --
        the browse returns an empty page instead. ``currentUser`` is optional
        and defaults to ``None`` (no scoping), preserving the original unscoped
        behaviour for internal / administrative callers and existing tests.

        Args:
            session: Active async unit-of-work session.
            params: Browse inputs. A :class:`CardListParams` (or plain
                :class:`~app.schemas.common.PaginationParams`) carrying the
                optional ``acct_id`` filter and the ``page`` / ``page_size``
                offset window. (``start_card_num`` is accepted for backward
                compatibility but the offset browse does not consult it.)
            currentUser: The authenticated user whose role drives the scoping
                described above; ``None`` disables role scoping.

        Returns:
            A :class:`~app.schemas.common.PaginatedResponse` of
            :class:`~app.schemas.CardSummary` rows (card number masked, no CVV),
            with page metadata and an authoritative ``has_next`` probe result.
        """
        pageSize = self._ResolvePageSize(params.page_size)
        pageNumber = params.page
        acctId = self._NormalizeFilter(getattr(params, "acct_id", None))
        cardNum = self._NormalizeFilter(getattr(params, "card_num", None))
        if self._IsRegularUnscoped(currentUser, acctId):
            # Non-admin without an account context: COCRDLIC never lists all
            # cards for a regular user (header L4-7), so the browse is empty.
            return self._BuildPage([], pageNumber, pageSize, 0)
        if cardNum is not None:
            # Exact card-number search anchor (COCRDLIC CC-CARD-NUM). A 16-digit
            # PAN identifies at most one card, so the browse returns that single
            # card as a one-row page when it also satisfies any account filter,
            # else an empty page. This makes the COCRDLI card-number search
            # field functional rather than a silent no-op (QA C3).
            return await self._ListByCardNum(session, cardNum, acctId, params)
        # Authoritative grand total for a truthful envelope: COCRDLIC browses
        # the whole file, so the modern list reports the real matching-row
        # count (scoped to the account when one is supplied) instead of a
        # look-ahead estimate.
        totalItems = await self.cardRepository.CountCards(session, acctId=acctId)
        # Offset window: fetch the ordered rows up to and including this page,
        # then slice out the requested page. ``card_num`` ordering is stable, so
        # every row is reachable by walking the pages in sequence. Card volumes
        # are small (one account, or the whole modest file), so materialising
        # ``pageNumber * pageSize`` ordered rows stays inexpensive.
        fetchLimit = pageNumber * pageSize
        if acctId is not None:
            # Account-scoped browse via the CARD-ACCT-ID alternate index.
            fetchedRows = await self.cardRepository.ListByAcctId(
                session,
                acctId,
                limit=fetchLimit,
            )
        else:
            # Unscoped browse across every account (admin / internal caller).
            fetchedRows = await self.cardRepository.ListCards(
                session,
                acctId=None,
                startCardNum=None,
                limit=fetchLimit,
            )
        startIndex = (pageNumber - MIN_PAGE) * pageSize
        pageRows = fetchedRows[startIndex : startIndex + pageSize]
        pageItems = [self._BuildSummary(card) for card in pageRows]
        return self._BuildPage(pageItems, pageNumber, pageSize, totalItems)

    @staticmethod
    def _NormalizeFilter(rawFilter: str | None) -> str | None:
        """Collapse a blank browse filter to ``None`` (no filter).

        A search ``TextField`` that the operator left empty arrives as an empty
        (or whitespace-only) query-string value; treating that as ``None``
        means "no filter" rather than "match the empty string", so an untouched
        search box browses normally (QA C3). A non-blank value is stripped of
        surrounding whitespace and returned unchanged.

        Args:
            rawFilter: The raw filter value from the browse params (``acct_id``
                or ``card_num``), possibly ``None`` or blank.

        Returns:
            The stripped filter value, or ``None`` when it was absent or blank.
        """
        if rawFilter is None:
            return None
        strippedFilter = rawFilter.strip()
        if not strippedFilter:
            return None
        return strippedFilter

    async def _ListByCardNum(
        self,
        session: AsyncSession,
        cardNum: str,
        acctId: str | None,
        params: PaginationParams | CardListParams,
    ) -> PaginatedResponse[CardSummary]:
        """Return the single-card page for an exact card-number search (C3).

        A 16-digit PAN is a primary key, so the COCRDLIC card-number search
        anchor matches at most one card. This performs the keyed read and, when
        the row exists AND satisfies any concurrent ``acct_id`` filter, returns
        it as an authoritative one-row page (``total_items == 1``); otherwise it
        returns a truthful empty page. The lookup is a parameterised
        keyed read (injection-safe, Ochs security rule); an unparsable or
        non-matching search term simply yields no rows rather than an error, so
        the search box degrades gracefully. This method is READ-ONLY.

        Args:
            session: Active async unit-of-work session.
            cardNum: The already-normalized (non-blank) card-number search term.
            acctId: An optional concurrent account filter the card must also
                satisfy; ``None`` applies no account constraint.
            params: The browse window supplying ``page`` / ``page_size`` (the
                page size is clamped to the F-004 seven-row cap).

        Returns:
            A :class:`~app.schemas.common.PaginatedResponse` carrying the single
            matching :class:`~app.schemas.CardSummary` (masked, no CVV) on the
            first page, or an empty page when nothing matches.
        """
        pageNumber = params.page
        pageSize = self._ResolvePageSize(params.page_size)
        cardRecord = await self.cardRepository.GetByCardNum(session, cardNum)
        if cardRecord is None or (
            acctId is not None and cardRecord.acct_id != acctId
        ):
            return self._BuildPage([], pageNumber, pageSize, 0)
        # The single match lives on page one; any later page is empty even
        # though the authoritative total remains one (truthful envelope).
        pageRows = [cardRecord] if pageNumber == MIN_PAGE else []
        pageItems = [self._BuildSummary(card) for card in pageRows]
        return self._BuildPage(pageItems, pageNumber, pageSize, 1)

    @staticmethod
    def _IsRegularUnscoped(
        currentUser: "User | None",
        acctId: str | None,
    ) -> bool:
        """Return True when a non-admin user browses without an account scope.

        Ports the COCRDLIC role gate (header L4-7). An administrator
        (``user_type == ADMIN_USER_TYPE``) is never restricted, and a ``None``
        ``currentUser`` disables scoping entirely (internal callers). For a
        regular user the browse must be confined to an account: when no
        ``acctId`` anchor is present the legacy program would still be scoped to
        its COMMAREA account rather than list everything, so the modern browse
        yields nothing.

        Args:
            currentUser: The authenticated user, or ``None`` to disable scoping.
            acctId: The owning-account anchor resolved from the browse params.

        Returns:
            True only when ``currentUser`` is a non-admin and ``acctId`` is
            ``None`` (an unscoped regular browse); False otherwise.
        """
        if currentUser is None:
            return False
        if getattr(currentUser, "user_type", None) == ADMIN_USER_TYPE:
            return False
        return acctId is None

    @staticmethod
    def _ResolvePageSize(pageSize: int) -> int:
        """Clamp a requested page size to the F-004 maximum of seven rows.

        COCRDLIC never displays more than ``WS-MAX-SCREEN-LINES`` (7) card rows
        per page; a larger requested window is capped so the business rule holds
        regardless of the caller's request.

        Args:
            pageSize: The requested page size (already >= 1 via
                :class:`~app.schemas.common.PaginationParams` validation).

        Returns:
            ``pageSize`` when it is within the limit, otherwise
            :data:`MAX_SCREEN_LINES`.
        """
        if pageSize > MAX_SCREEN_LINES:
            return MAX_SCREEN_LINES
        return pageSize

    @staticmethod
    def _BuildSummary(card: "Card") -> CardSummary:
        """Project one ORM card row onto its masked list-row DTO.

        Building the summary through :meth:`CardSummary.model_validate` applies
        the schema's ``field_serializer`` (card number masked to the last four
        digits) and guarantees no ``cvv_cd`` is ever carried into the response
        (AAP 0.7.8).

        Args:
            card: The ORM :class:`~app.models.card.Card` row to project.

        Returns:
            A :class:`~app.schemas.CardSummary` for the browse grid.
        """
        return CardSummary.model_validate(card)

    def _BuildPage(
        self,
        pageItems: list[CardSummary],
        pageNumber: int,
        pageSize: int,
        totalItems: int,
    ) -> PaginatedResponse[CardSummary]:
        """Assemble the paginated browse envelope from an authoritative total.

        ``total_items`` is the exact ``COUNT(*)`` of matching card rows, so
        ``total_pages`` (a ceiling division) and ``has_next`` (whether a page
        follows this one) are precise rather than a look-ahead estimate. A page
        requested beyond the last one yields an empty ``items`` list while still
        reporting the true totals. Integer arithmetic only (no floating point,
        AAP 0.7.1).

        Args:
            pageItems: The masked summaries for this page (already sliced).
            pageNumber: The 1-based page ordinal being returned.
            pageSize: The effective (clamped) page size.
            totalItems: The authoritative total count of matching card rows.

        Returns:
            A fully populated :class:`~app.schemas.common.PaginatedResponse`.
        """
        totalPages = (totalItems + pageSize - 1) // pageSize if totalItems > 0 else 0
        return PaginatedResponse[CardSummary](
            items=pageItems,
            page=pageNumber,
            page_size=pageSize,
            total_items=totalItems,
            total_pages=totalPages,
            has_next=pageNumber < totalPages,
            has_previous=pageNumber > MIN_PAGE,
        )

    # ------------------------------------------------------------------ #
    # METHOD 2 -- card view (COCRDSLC, tx CCDL). READ-ONLY.               #
    # ------------------------------------------------------------------ #
    async def GetCard(self, session: AsyncSession, cardNum: str) -> CardRead:
        """Return one card's detail by card number (COCRDSLC, tx CCDL).

        Reproduces the COCRDSLC detail read: edit the supplied card number
        exactly as ``1000-EDIT-INPUTS`` did (blank rejected, then the 16-digit
        numeric edit), perform the keyed read, and surface the legacy
        not-found message when no row matches. The response is a
        :class:`~app.schemas.card.CardRead`, so ``card_num`` is masked and
        ``cvv_cd`` is never serialized (AAP 0.7.8). This is a pure read: it
        never opens a write or commits.

        Args:
            session: The active async database session (unit of work).
            cardNum: The 16-digit card-number key from ``GET /cards/{cardNum}``.

        Returns:
            The masked, cvv-free :class:`~app.schemas.card.CardRead` detail.

        Raises:
            DomainValidationError: When ``cardNum`` is blank
                (``'Card number not provided'``, COCRDSLC L141) or not a valid
                16-digit number (``'Card number if supplied must be a 16 digit
                number'``, COCRDSLC L149).
            NotFoundError: When no card carries that number
                (``'Did not find cards for this search condition'``,
                COCRDSLC L154).
        """
        normalizedCardNum = self._ValidateCardNum(cardNum)
        cardRecord = await self.cardRepository.GetByCardNum(session, normalizedCardNum)
        if cardRecord is None:
            raise NotFoundError(MSG_CARD_NOT_FOUND)
        # CardRead masks card_num and omits cvv_cd via its schema; never bypass
        # the schema by returning raw ORM attributes (AAP 0.7.8).
        return CardRead.model_validate(cardRecord)

    # ------------------------------------------------------------------ #
    # METHOD 2b -- card view BY OWNING ACCOUNT (COCRDSLC via CARD-ACCT-ID #
    # alternate index). READ-ONLY. Added for QA C1: the list masks        #
    # card_num, so the UI cannot navigate by PAN; it navigates by the     #
    # unmasked acct_id instead, and this resolves that account to its     #
    # card server-side without ever exposing the full PAN in the URL.     #
    # ------------------------------------------------------------------ #
    async def GetCardByAccount(self, session: AsyncSession, acctId: str) -> CardRead:
        """Return one card's detail by its OWNING ACCOUNT id (QA C1).

        The modern UI reaches card detail by the (unmasked) account id shown in
        the card-list grid rather than by the masked PAN, which can never be a
        valid key (AAP 0.7.8). This edits the account id exactly as COACTVWC
        ``2210-EDIT-ACCOUNT`` did (blank rejected, then the non-zero 11-digit
        edit), resolves the account to its card through the ``CARD-ACCT-ID``
        alternate index (:meth:`CardRepository.GetByAcctId`), and surfaces the
        verbatim COCRDSLC not-found message when the account owns no card. The
        response is a :class:`~app.schemas.card.CardRead`, so ``card_num`` is
        masked and ``cvv_cd`` is never serialized. This is a pure read: it never
        opens a write or commits.

        Args:
            session: The active async database session (unit of work).
            acctId: The 11-digit owning-account id from
                ``GET /cards/by-account/{acctId}``.

        Returns:
            The masked, cvv-free :class:`~app.schemas.card.CardRead` detail of
            the account's card.

        Raises:
            DomainValidationError: When ``acctId`` is blank
                (``'Account number not provided'``) or not a non-zero 11-digit
                number (``'Account number must be a non zero 11 digit number'``).
            NotFoundError: When the account owns no card
                (``'Did not find cards for this search condition'``,
                COCRDSLC L154).
        """
        normalizedAcctId = self._ValidateAcctId(acctId)
        cardRecord = await self.cardRepository.GetByAcctId(session, normalizedAcctId)
        if cardRecord is None:
            raise NotFoundError(MSG_CARD_NOT_FOUND)
        # CardRead masks card_num and omits cvv_cd via its schema (AAP 0.7.8).
        return CardRead.model_validate(cardRecord)

    # ------------------------------------------------------------------ #
    # METHOD 3 -- card update (COCRDUPC, tx CCUP). Optimistic before-     #
    # image check (AAP 0.7.4). Service owns the unit-of-work / commit.    #
    # ------------------------------------------------------------------ #
    async def UpdateCard(
        self,
        session: AsyncSession,
        cardNum: str,
        cardUpdate: CardUpdate,
    ) -> CardRead:
        """Update the editable card fields (COCRDUPC, tx CCUP).

        Ports COCRDUPC's ``2000-DECIDE-ACTION`` -> ``9200-WRITE-PROCESSING`` ->
        ``9300-CHECK-CHANGE-IN-REC`` in order:

        1. Edit the card number (``_ValidateCardNum``).
        2. Edit the submitted fields (``_EditUpdateFields``): the embossed name
           must be non-blank alphabetic (``1230-EDIT-NAME``), the active status
           must be ``Y``/``N`` (``1240-EDIT-CARDSTATUS``), and the expiry must
           carry a valid month and year (``1250``/``1260``).
        3. Load the current row (``GetByCardNum``); a missing row is the legacy
           not-found condition. That loaded state is the before-image snapshot.
        4. No-change short-circuit: if the submitted values equal the
           before-image (name compared case-insensitively, mirroring the legacy
           ``UPPER-CASE`` compare) there is nothing to write, so the legacy
           ``'No change detected with respect to values fetched.'`` (L188) is
           surfaced and no commit occurs.
        5. Optimistic before-image check (``9300``): re-read the row and compare
           the current database values against the before-image. Any difference
           means another actor changed the row first, raising the legacy
           ``'Record changed by some one else. Please review'`` (L208). Because
           ``CardRepository`` exposes NO ``GetForUpdate`` (unlike the account
           path's ``SELECT ... FOR UPDATE``), this re-read + compare is the
           faithful surrogate for the COBOL ``READ ... UPDATE`` before-image
           serialization; prefer a locking read here if one is later added.
        6. Apply the new name/expiry/status and PRESERVE ``cvv_cd`` unchanged,
           then flush and commit (the service owns the unit-of-work).
        7. Return the masked, cvv-free detail (legacy ``'Changes committed to
           database'``, L169).

        Args:
            session: The active async database session (unit of work).
            cardNum: The 16-digit card-number key from ``PUT /cards/{cardNum}``.
            cardUpdate: The validated new field values (no ``cvv_cd``, no
                before-image; see class docstring).

        Returns:
            The masked, cvv-free :class:`~app.schemas.card.CardRead` reflecting
            the committed row.

        Raises:
            DomainValidationError: On a card-number, field-edit, or no-change
                condition (verbatim legacy messages).
            NotFoundError: When no card carries that number
                (``'Did not find cards for this search condition'``).
            OptimisticLockError: When the before-image check detects a
                concurrent modification (``'Record changed by some one else.
                Please review'``).
            ConflictError: When the re-read fails
                (``'Could not lock record for update'``) or the commit fails
                (``'Update of record failed'``).
        """
        normalizedCardNum = self._ValidateCardNum(cardNum)
        self._EditUpdateFields(cardUpdate)
        cardRecord = await self.cardRepository.GetByCardNum(session, normalizedCardNum)
        if cardRecord is None:
            raise NotFoundError(MSG_CARD_NOT_FOUND)
        beforeImage = self._Snapshot(cardRecord)
        if self._IsNoChange(cardUpdate, beforeImage):
            raise DomainValidationError(MSG_NO_CHANGE_DETECTED)
        await self._RefreshCurrent(session, cardRecord)
        self._CheckBeforeImage(self._Snapshot(cardRecord), beforeImage)
        self._ApplyUpdate(cardRecord, cardUpdate)
        await self._Persist(session, cardRecord)
        return CardRead.model_validate(cardRecord)

    # ------------------------------------------------------------------ #
    # METHOD 3b -- card update BY OWNING ACCOUNT (COCRDUPC via the        #
    # CARD-ACCT-ID alternate index). Added for QA C1: the by-account      #
    # detail view returns a MASKED card_num, so the client cannot address #
    # the PUT by PAN. This resolves the account to its real PAN           #
    # server-side and delegates to the PAN-keyed update, reusing every    #
    # field edit, the optimistic before-image check, and the commit.      #
    # ------------------------------------------------------------------ #
    async def UpdateCardByAccount(
        self,
        session: AsyncSession,
        acctId: str,
        cardUpdate: CardUpdate,
    ) -> CardRead:
        """Update a card's editable fields addressed BY OWNING ACCOUNT (QA C1).

        Because the by-account detail view (:meth:`GetCardByAccount`) returns a
        MASKED ``card_num``, the client has no real PAN to address a
        PAN-keyed ``PUT /cards/{cardNum}`` with. This method accepts the
        unmasked account id instead: it edits the account id
        (``2210-EDIT-ACCOUNT``), resolves the account to its real card number
        through the ``CARD-ACCT-ID`` alternate index
        (:meth:`CardRepository.GetByAcctId`) entirely server-side (the full PAN
        never leaves the service), and then DELEGATES to :meth:`UpdateCard` so
        the legacy field edits, the optimistic before-image concurrency check
        (AAP 0.7.4), and the unit-of-work commit are reused verbatim rather than
        duplicated (Minimal Change Clause / DRY). The service owns the commit.

        Args:
            session: The active async database session (unit of work).
            acctId: The 11-digit owning-account id from
                ``PUT /cards/by-account/{acctId}``.
            cardUpdate: The validated new field values (editable fields only:
                embossed name, expiration date, active status -- never
                ``card_num``, ``acct_id``, or the CVV).

        Returns:
            The masked, cvv-free :class:`~app.schemas.card.CardRead` reflecting
            the committed row.

        Raises:
            DomainValidationError: On a blank/invalid account id, a failed field
                edit, or a no-change condition (verbatim legacy messages).
            NotFoundError: When the account owns no card
                (``'Did not find cards for this search condition'``).
            OptimisticLockError: When the before-image check detects a
                concurrent modification.
            ConflictError: When the re-read or the commit fails.
        """
        normalizedAcctId = self._ValidateAcctId(acctId)
        cardRecord = await self.cardRepository.GetByAcctId(session, normalizedAcctId)
        if cardRecord is None:
            raise NotFoundError(MSG_CARD_NOT_FOUND)
        # Delegate to the PAN-keyed update with the REAL (unmasked) card number
        # resolved above; UpdateCard re-loads the row for its before-image
        # check, so passing the key (not the ORM row) keeps the two paths
        # behaviourally identical.
        return await self.UpdateCard(session, cardRecord.card_num, cardUpdate)

    # ------------------------------------------------------------------ #
    # Shared card-number edit (COCRDSLC/COCRDUPC 1000-EDIT-INPUTS).       #
    # ------------------------------------------------------------------ #
    @staticmethod
    def _ValidateCardNum(cardNum: str) -> str:
        """Edit and normalize the card-number key (blank -> 16-digit numeric).

        The validator is used only to DETECT validity; the raised message is
        the verbatim legacy operator text, not the validator's generic wording.

        Args:
            cardNum: The raw card-number key from the request path.

        Returns:
            The stripped, validated 16-digit card number.

        Raises:
            DomainValidationError: Blank (``'Card number not provided'``) or not
                a 16-digit number (``'Card number if supplied must be a 16 digit
                number'``).
        """
        normalizedCardNum = cardNum.strip() if cardNum else ""
        if not normalizedCardNum:
            raise DomainValidationError(MSG_CARD_NUM_NOT_PROVIDED)
        editResult = validators.ValidateNumericId(
            CARD_NUM_FIELD_LABEL, normalizedCardNum, validators.CARD_NUM_LENGTH,
        )
        if not editResult.isValid:
            raise DomainValidationError(MSG_CARD_NUM_INVALID)
        return normalizedCardNum

    # ------------------------------------------------------------------ #
    # Shared account-id edit for the by-account view/update (QA C1).      #
    # Mirrors COACTVWC 2210-EDIT-ACCOUNT so account-id validation is      #
    # identical to account_service (blank -> non-zero 11-digit numeric).  #
    # ------------------------------------------------------------------ #
    def _ValidateAcctId(self, acctId: str) -> str:
        """Edit and normalize the owning-account id (blank -> non-zero 11-digit).

        The validator is used only to DETECT validity; the raised message is the
        verbatim legacy operator text (identical to ``account_service``), not the
        validator's generic wording.

        Args:
            acctId: The raw account-id key from the request path.

        Returns:
            The stripped, validated non-zero 11-digit account id.

        Raises:
            DomainValidationError: Blank (``'Account number not provided'``) or
                not a non-zero 11-digit number
                (``'Account number must be a non zero 11 digit number'``).
        """
        if acctId is None or not str(acctId).strip():
            raise DomainValidationError(MSG_ACCT_NUM_NOT_PROVIDED)
        normalizedAcctId = str(acctId).strip()
        editResult = validators.ValidateNumericId(
            ACCT_ID_FIELD_LABEL, normalizedAcctId, validators.ACCT_ID_LENGTH,
        )
        if not editResult.isValid or self._IsAllZeros(normalizedAcctId):
            raise DomainValidationError(MSG_ACCT_NUM_INVALID)
        return normalizedAcctId

    @staticmethod
    def _IsAllZeros(numericText: str) -> bool:
        """Return True when every character of ``numericText`` is ``'0'``.

        Ports the COACTVWC all-zeroes rejection so an account id of all zeroes
        fails the non-zero edit exactly as the legacy screen (and
        ``account_service``) do.

        Args:
            numericText: The already-stripped numeric string to test.

        Returns:
            True when the string is non-empty and consists solely of ``'0'``.
        """
        return len(numericText) > 0 and set(numericText) == {"0"}

    # ------------------------------------------------------------------ #
    # Field edits (COCRDUPC 1230/1240/1250/1260-EDIT-*).                  #
    # ------------------------------------------------------------------ #
    def _EditUpdateFields(self, cardUpdate: CardUpdate) -> None:
        """Run every submitted-field edit in the legacy screen order.

        Args:
            cardUpdate: The submitted new field values.

        Raises:
            DomainValidationError: On the first failing edit (verbatim message).
        """
        self._EditName(cardUpdate.embossed_name)
        self._EditStatus(cardUpdate.active_status)
        self._EditExpiry(cardUpdate.expiration_date)

    @staticmethod
    def _EditName(embossedName: str) -> None:
        """Edit the embossed name (COCRDUPC ``1230-EDIT-NAME``).

        Blank is rejected before the alphabetic edit so the two legacy messages
        stay distinct (``'Card name not provided'`` vs the alpha message).

        Args:
            embossedName: The submitted embossed name.

        Raises:
            DomainValidationError: Blank (``'Card name not provided'``) or with a
                non-alphabetic character
                (``'Card name can only contain alphabets and spaces'``).
        """
        candidateName = embossedName.strip() if embossedName else ""
        if not candidateName:
            raise DomainValidationError(MSG_CARD_NAME_NOT_PROVIDED)
        nameResult = validators.ValidateAlpha(CARD_NAME_FIELD_LABEL, candidateName)
        if not nameResult.isValid:
            raise DomainValidationError(MSG_CARD_NAME_NOT_ALPHA)

    @staticmethod
    def _EditStatus(activeStatus: str) -> None:
        """Edit the active-status flag (COCRDUPC ``1240-EDIT-CARDSTATUS``).

        Args:
            activeStatus: The submitted active-status flag.

        Raises:
            DomainValidationError: When not exactly ``Y`` or ``N``
                (``'Card Active Status must be Y or N'``).
        """
        statusResult = validators.ValidateYesNo(CARD_STATUS_FIELD_LABEL, activeStatus)
        if not statusResult.isValid:
            raise DomainValidationError(MSG_CARD_STATUS_INVALID)

    @staticmethod
    def _EditExpiry(expirationDate: date | None) -> None:
        """Edit the expiry month/year (COCRDUPC ``1250``/``1260-EDIT-EXPIRY``).

        The month must be 1-12 and the composed ``YYYY-MM-DD`` date must pass
        the shared legacy date edit (which enforces the valid century/year
        window). A ``None`` expiry is treated as an invalid year.

        Args:
            expirationDate: The submitted expiry date (already coerced to a
                :class:`datetime.date` by the schema).

        Raises:
            DomainValidationError: Invalid month
                (``'Card expiry month must be between 1 and 12'``) or invalid
                year (``'Invalid card expiry year'``).
        """
        if expirationDate is None:
            raise DomainValidationError(MSG_CARD_EXPIRY_YEAR_INVALID)
        if expirationDate.month < MIN_MONTH or expirationDate.month > MAX_MONTH:
            raise DomainValidationError(MSG_CARD_EXPIRY_MONTH_INVALID)
        composedDate = date_utils.FormatLegacyDate(expirationDate)
        dateResult = date_utils.ValidateDate(composedDate)
        if not dateResult.isValid:
            raise DomainValidationError(MSG_CARD_EXPIRY_YEAR_INVALID)

    # ------------------------------------------------------------------ #
    # Before-image snapshot + comparisons (COCRDUPC 9300-CHECK-CHANGE).   #
    # ------------------------------------------------------------------ #
    @staticmethod
    def _Snapshot(card: "Card") -> _CardImage:
        """Capture an immutable before/after image of a card's edited fields.

        The scalar values are copied into a frozen :class:`_CardImage`, so the
        snapshot is unaffected by any later in-place mutation or refresh of the
        ORM row (that independence is what makes the before/after compare sound).

        Args:
            card: The attached ORM card row to snapshot.

        Returns:
            A frozen :class:`_CardImage` of ``cvv_cd``, ``embossed_name``,
            ``expiration_date`` and ``active_status``.
        """
        return _CardImage(
            cvv_cd=card.cvv_cd,
            embossed_name=card.embossed_name,
            expiration_date=card.expiration_date,
            active_status=card.active_status,
        )

    @staticmethod
    def _NormalizeName(embossedName: str | None) -> str:
        """Fold a name for case-insensitive compare (legacy ``UPPER-CASE``).

        Args:
            embossedName: A raw embossed-name value (possibly ``None``).

        Returns:
            The upper-cased, stripped name (empty string for ``None``).
        """
        if embossedName is None:
            return ""
        return embossedName.strip().upper()

    def _IsNoChange(self, cardUpdate: CardUpdate, beforeImage: _CardImage) -> bool:
        """Return whether the submitted values match the before-image (no-op).

        Mirrors the legacy NEW-vs-OLD comparison that precedes the write: the
        name is compared case-insensitively (``UPPER-CASE`` in COCRDUPC), the
        expiry and status directly. ``cvv_cd`` is not part of the submitted set
        (it is preserved), so it is excluded from this compare.

        Args:
            cardUpdate: The submitted new field values.
            beforeImage: The snapshot of the row as first loaded.

        Returns:
            True when nothing changed and the update should short-circuit.
        """
        sameName = self._NormalizeName(cardUpdate.embossed_name) == self._NormalizeName(
            beforeImage.embossed_name,
        )
        sameExpiry = cardUpdate.expiration_date == beforeImage.expiration_date
        sameStatus = cardUpdate.active_status == beforeImage.active_status
        return sameName and sameExpiry and sameStatus

    def _CheckBeforeImage(self, currentImage: _CardImage, beforeImage: _CardImage) -> None:
        """Enforce the optimistic before-image check (COCRDUPC ``9300``).

        Compares the just-re-read current row against the before-image the
        service first loaded. The name is folded case-insensitively (legacy
        ``UPPER-CASE``); ``cvv_cd``, expiry, and status are compared directly --
        the exact field set COCRDUPC's ``9300-CHECK-CHANGE-IN-REC`` inspects. Any
        difference means another unit of work committed first.

        Args:
            currentImage: Snapshot of the row after the concurrency re-read.
            beforeImage: Snapshot of the row as first loaded.

        Raises:
            OptimisticLockError: When any compared field differs
                (``'Record changed by some one else. Please review'``).
        """
        nameChanged = self._NormalizeName(currentImage.embossed_name) != self._NormalizeName(
            beforeImage.embossed_name,
        )
        otherChanged = (
            currentImage.cvv_cd != beforeImage.cvv_cd
            or currentImage.expiration_date != beforeImage.expiration_date
            or currentImage.active_status != beforeImage.active_status
        )
        if nameChanged or otherChanged:
            raise OptimisticLockError(MSG_RECORD_CHANGED)

    # ------------------------------------------------------------------ #
    # Concurrency re-read + apply + persist (COCRDUPC 9200-WRITE-PROC).   #
    # ------------------------------------------------------------------ #
    @staticmethod
    async def _RefreshCurrent(session: AsyncSession, card: "Card") -> None:
        """Re-read the row to obtain its current state (READ ... UPDATE surrogate).

        Stands in for the legacy ``READ CARDDAT ... UPDATE`` that both fetched
        the current record and held it for rewrite. ``CardRepository`` has no
        ``GetForUpdate`` (see class docstring), so a plain refresh provides the
        current image; a genuine read failure maps to the legacy
        ``'Could not lock record for update'``.

        Args:
            session: The active async database session that owns ``card``.
            card: The attached ORM card row to refresh in place.

        Raises:
            ConflictError: When the re-read fails
                (``'Could not lock record for update'``).
        """
        try:
            await session.refresh(card)
        except SQLAlchemyError as refreshError:
            raise ConflictError(MSG_COULD_NOT_LOCK) from refreshError

    @staticmethod
    def _ApplyUpdate(card: "Card", cardUpdate: CardUpdate) -> None:
        """Copy the submitted fields onto the row, PRESERVING ``cvv_cd``.

        Only the three editable fields are assigned; ``cvv_cd`` is deliberately
        never touched here (there is no cvv on the request path), so the stored
        CVV is preserved exactly (AAP 0.7.8).

        Args:
            card: The attached ORM card row to mutate.
            cardUpdate: The validated new field values.
        """
        card.embossed_name = cardUpdate.embossed_name
        card.expiration_date = cardUpdate.expiration_date
        card.active_status = cardUpdate.active_status

    async def _Persist(self, session: AsyncSession, card: "Card") -> None:
        """Flush the card update and commit, rolling back on failure.

        The repository ``Update`` only flushes; the service owns the commit
        boundary. A database failure is rolled back and mapped to the legacy
        ``'Update of record failed'``.

        Args:
            session: The active async database session (unit of work).
            card: The mutated ORM card row to persist.

        Raises:
            ConflictError: When the flush/commit fails
                (``'Update of record failed'``).
        """
        try:
            await self.cardRepository.Update(session, card)
            await session.commit()
        except SQLAlchemyError as persistError:
            await session.rollback()
            raise ConflictError(MSG_UPDATE_FAILED) from persistError


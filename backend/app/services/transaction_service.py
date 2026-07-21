"""Transaction service.

Ported 1:1 from legacy CICS online programs COTRN00C (list, CT00), COTRN01C
(view, CT01), COTRN02C (add, CT02). Add-transaction validations reuse the batch
posting validator CBTRN02C (1500-VALIDATE-TRAN) reject codes 100-103 and the
additional code 109 (2800-UPDATE-ACCOUNT-REC). Record layouts CVTRA05Y (posted)
/ CVTRA06Y (daily). tran_amt is Decimal (never float). card_num is masked in
responses. See 0.5.1, 0.7.1, 0.7.3, 0.8.1.
"""

# Traceability (AAP 0.8.1): this module is the modern re-expression of three
# legacy CICS online COBOL programs plus the batch posting validator:
#   * COTRN00C (CT00) -> ListTransactions  -- list/browse the TRANSACT file.
#   * COTRN01C (CT01) -> GetTransaction    -- view one transaction by id.
#   * COTRN02C (CT02) -> AddTransaction    -- add a transaction, then post it
#       through the CBTRN02C posting validations (reject codes 100-103, 109).
# Business rules are preserved EXACTLY (Minimal Change Clause, AAP 0.8.1);
# monetary values stay Decimal end-to-end (AAP 0.7.1). In-code identifiers
# follow the Ochs Rule (PascalCase classes/methods, camelCase locals,
# ALL_UPPERCASE constants); the file/module name stays snake_case (AAP 0.8.3).

from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Optional

from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

# Posting-reject exceptions: app.core.exceptions exposes BOTH a Design A family
# of no-argument subclasses (imported here) AND a Design B enum-parameterized
# form (TransactionPostingError(PostingRejectCode.X)). Design A is used because
# it is the most explicit at the call site; both designs carry identical
# ``.code`` / ``.description`` values, so the online add path (COTRN02C) and the
# batch posting job (CBTRN02C) reconcile field-for-field (AAP 0.7.3).
#   InvalidCardNumberError   -> 100 "INVALID CARD NUMBER FOUND"
#   AccountNotFoundError     -> 101 "ACCOUNT RECORD NOT FOUND"
#   OverlimitTransactionError-> 102 "OVERLIMIT TRANSACTION"
#   AccountExpiredError      -> 103 "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
#   AccountUpdateFailedError -> 109 "ACCOUNT RECORD NOT FOUND"
from app.core.exceptions import (
    AccountExpiredError,
    AccountNotFoundError,
    AccountUpdateFailedError,
    DomainValidationError,
    InvalidCardNumberError,
    NotFoundError,
    OverlimitTransactionError,
)
from app.models.account import Account
from app.models.transaction import Transaction
from app.repositories import (
    AccountRepository,
    CardXrefRepository,
    TransactionRepository,
)
from app.schemas import (
    PaginatedResponse,
    PaginationParams,
    TransactionCreate,
    TransactionRead,
    TransactionSummary,
)
from app.utils import date_utils, decimal_utils, validators

__all__ = ["TransactionService"]

# ---------------------------------------------------------------------------
# Field-length constants (Ochs Rule: ALL_UPPERCASE). Widths come from the
# TRAN-RECORD copybook (CVTRA05Y) and the COTRN02 add screen; the shared
# identifier widths (ACCT_ID_LENGTH, CARD_NUM_LENGTH, TRAN_ID_LENGTH) are reused
# from app.utils.validators so a single definition governs every layer.
# ---------------------------------------------------------------------------
TRAN_TYPE_CD_LENGTH = 2          # TRAN-TYPE-CD PIC X(02) (numeric on the screen).
TRAN_CAT_CD_LENGTH = 4           # TRAN-CAT-CD PIC 9(04).
MERCHANT_ID_LENGTH = 9           # TRAN-MERCHANT-ID PIC 9(09).
TRAN_ID_PAD_WIDTH = 16           # TRAN-ID PIC X(16); next id is zero-padded to 16.

# ---------------------------------------------------------------------------
# Verbatim on-screen messages, ported character-for-character from the legacy
# programs (never reword, re-case, or re-punctuate). Cited to their source line.
# ---------------------------------------------------------------------------
# COTRN00C paging boundaries (L248 / L270). The service does NOT hard-fail on a
# boundary; it exposes has_previous / has_next flags on the paginated envelope,
# and a router surfaces these exact texts. Defined here as the single source of
# the verbatim wording.
MSG_ALREADY_TOP = "You are already at the top of the page..."
MSG_ALREADY_BOTTOM = "You are already at the bottom of the page..."

# COTRN01C view (CT01).
MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty..."               # L149
MSG_TRAN_NOT_FOUND = "Transaction ID NOT found..."              # L285
MSG_TRAN_LOOKUP_FAILED = "Unable to lookup Transaction..."      # L292

# COTRN02C add (CT02) -- key-field edits (VALIDATE-INPUT-KEY-FIELDS).
MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered..."  # L226
MSG_ACCT_ID_NOT_NUMERIC = "Account ID must be Numeric..."               # L199
MSG_CARD_NUM_NOT_NUMERIC = "Card Number must be Numeric..."             # L213
MSG_ACCT_ID_NOT_FOUND = "Account ID NOT found..."                      # L593
MSG_CARD_NUM_NOT_FOUND = "Card Number NOT found..."                    # L626

# COTRN02C add (CT02) -- data-field "can NOT be empty" edits (in COBOL order).
MSG_TYPE_CD_EMPTY = "Type CD can NOT be empty..."               # L254
MSG_CATEGORY_CD_EMPTY = "Category CD can NOT be empty..."       # L260
MSG_SOURCE_EMPTY = "Source can NOT be empty..."                 # L266
MSG_DESCRIPTION_EMPTY = "Description can NOT be empty..."       # L272
MSG_AMOUNT_EMPTY = "Amount can NOT be empty..."                 # L278
MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty..."           # L284
MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty..."           # L290
MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty..."       # L296
MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty..."   # L302
MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty..."   # L308
MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty..."     # L314

# COTRN02C add (CT02) -- numeric / format edits.
MSG_TYPE_CD_NOT_NUMERIC = "Type CD must be Numeric..."          # L325
MSG_CATEGORY_CD_NOT_NUMERIC = "Category CD must be Numeric..."  # L331
MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric..."  # L432
MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99"   # L345
MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD"  # L360
MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD"  # L375

# Field label handed to the reusable emptiness validator. Only the validator's
# boolean outcome is consumed; the verbatim COBOL message above is what is
# raised, so the label is purely for readability at the call site.
LABEL_TRAN_ID = "Transaction ID"


class TransactionService:
    """Business logic for listing, viewing, and adding transactions.

    Fuses three legacy CICS online programs -- COTRN00C (list, CT00), COTRN01C
    (view, CT01), and COTRN02C (add, CT02) -- with the batch posting validator
    CBTRN02C (reject codes 100-103 and 109) reused by the add path. The service
    orchestrates the transaction, card-cross-reference, and account repositories
    and owns the per-request unit-of-work: list and view are strictly read-only
    (no commit), while add wraps the transaction insert and the account-balance
    update in a single committed transaction (rolled back on any failure).

    All monetary arithmetic uses :class:`~decimal.Decimal` (never ``float``,
    AAP 0.7.1), and ``card_num`` is masked to its last four digits by the
    response schemas (AAP 0.7.8).
    """

    def __init__(self) -> None:
        """Wire the stateless repositories this service orchestrates.

        Each repository is stateless (the active session is passed per call),
        so constructing them once here is safe and lets a single service
        instance be shared across requests.
        """
        self.transactionRepository = TransactionRepository()
        self.xrefRepository = CardXrefRepository()
        self.accountRepository = AccountRepository()

    # -----------------------------------------------------------------------
    # METHOD 1 -- list (COTRN00C, CT00)
    # -----------------------------------------------------------------------
    async def ListTransactions(
        self,
        session: AsyncSession,
        params: PaginationParams,
        cardNum: Optional[str] = None,
    ) -> PaginatedResponse[TransactionSummary]:
        """List posted transactions as one paginated page (COTRN00C, CT00).

        Ports the COTRN00C transaction-list browse. When ``cardNum`` is
        supplied the browse is filtered to that card's transactions (the
        TRAN-CARD-NUM alternate index, via ``ListByCardNum``); otherwise every
        transaction is browsed in ascending ``tran_id`` order (``ListTransactions``).
        The legacy PF7/PF8 paging-boundary messages (:data:`MSG_ALREADY_TOP`,
        :data:`MSG_ALREADY_BOTTOM`) are represented by the ``has_previous`` /
        ``has_next`` flags on the returned envelope rather than by a hard error,
        so a router can surface them without the service failing.

        This is a strictly read-only operation and never commits.

        Args:
            session: Active async database session (caller-owned).
            params: Page number and page size for the requested window.
            cardNum: Optional 16-digit card number to filter the browse by; when
                ``None`` (the documented default) every transaction is listed.

        Returns:
            A :class:`PaginatedResponse` of :class:`TransactionSummary` rows
            (``card_num`` masked on serialization) with accurate page metadata.
        """
        totalItems = await self._CountTransactions(session, cardNum)
        fetchLimit = params.page * params.page_size
        if cardNum:
            fetchedRows = await self.transactionRepository.ListByCardNum(
                session, cardNum, limit=fetchLimit
            )
        else:
            fetchedRows = await self.transactionRepository.ListTransactions(
                session, startTranId=None, limit=fetchLimit
            )
        startIndex = (params.page - 1) * params.page_size
        pageRows = fetchedRows[startIndex:fetchLimit]
        summaryItems = [
            TransactionSummary.model_validate(txRecord) for txRecord in pageRows
        ]
        return PaginatedResponse.Create(summaryItems, totalItems, params)

    async def _CountTransactions(
        self, session: AsyncSession, cardNum: Optional[str]
    ) -> int:
        """Count matching transactions for pagination metadata.

        The transaction repository intentionally exposes no count method (it is
        a thin data-access layer), so this bounded, parameterized ``COUNT(*)``
        is composed here to keep the :class:`PaginatedResponse` totals accurate
        without modifying the dependency repository. The predicate is an ORM
        column comparison (never string SQL), so it is injection-safe.

        Args:
            session: Active async database session.
            cardNum: Optional card number to scope the count to one card.

        Returns:
            The total number of matching transactions.
        """
        countStmt = select(func.count()).select_from(Transaction)
        if cardNum:
            countStmt = countStmt.where(Transaction.card_num == cardNum)
        countResult = await session.execute(countStmt)
        return int(countResult.scalar_one())

    # -----------------------------------------------------------------------
    # METHOD 2 -- view (COTRN01C, CT01)
    # -----------------------------------------------------------------------
    async def GetTransaction(
        self, session: AsyncSession, tranId: str
    ) -> TransactionRead:
        """Fetch a single transaction by id (COTRN01C, CT01).

        Ports COTRN01C's PROCESS-ENTER-KEY / READ-TRANSACT-FILE flow: a blank
        id is rejected before any lookup; a missing row and a genuine read error
        surface the two distinct legacy messages. This is a strictly read-only
        operation and never commits.

        Args:
            session: Active async database session (caller-owned).
            tranId: The 16-character transaction id to view.

        Returns:
            A :class:`TransactionRead` for the row (``card_num`` masked).

        Raises:
            DomainValidationError: When ``tranId`` is blank (COTRN01C L149).
            NotFoundError: When no row matches (L285) or the read fails (L292).
        """
        if not validators.ValidateRequired(LABEL_TRAN_ID, tranId).isValid:
            raise DomainValidationError(MSG_TRAN_ID_EMPTY)
        try:
            txRecord = await self.transactionRepository.GetByTranId(session, tranId)
        except SQLAlchemyError as lookupError:
            raise NotFoundError(MSG_TRAN_LOOKUP_FAILED) from lookupError
        if txRecord is None:
            raise NotFoundError(MSG_TRAN_NOT_FOUND)
        return TransactionRead.model_validate(txRecord)

    # -----------------------------------------------------------------------
    # METHOD 3 -- add (COTRN02C, CT02) + posting validation (CBTRN02C)
    # -----------------------------------------------------------------------
    async def AddTransaction(
        self, session: AsyncSession, transactionCreate: TransactionCreate
    ) -> TransactionRead:
        """Add a transaction and post it to the account (COTRN02C + CBTRN02C).

        Mirrors COTRN02C's operation order exactly: VALIDATE-INPUT-KEY-FIELDS
        (resolve the owning card/account) -> VALIDATE-INPUT-DATA-FIELDS (the
        per-field edits) -> the CBTRN02C posting validations (reject codes
        100-103) -> ADD-TRANSACTION (assign the next id and insert) -> the
        2800-UPDATE-ACCOUNT-REC balance update (reject code 109). The insert and
        the balance update form a single unit-of-work: this method owns the
        commit and rolls back on any failure so the two writes are atomic.

        Args:
            session: Active async database session (caller-owned).
            transactionCreate: The validated add-transaction request DTO.

        Returns:
            A :class:`TransactionRead` of the newly posted transaction
            (``card_num`` masked on serialization).

        Raises:
            DomainValidationError: For any failed key-field or data-field edit,
                with the verbatim COTRN02C message.
            NotFoundError: When the supplied account id or card number does not
                resolve to a cross-reference row.
            InvalidCardNumberError: Posting code 100 (unknown card cross-ref).
            AccountNotFoundError: Posting code 101 (account row absent).
            OverlimitTransactionError: Posting code 102 (over the credit limit).
            AccountExpiredError: Posting code 103 (received after expiration).
            AccountUpdateFailedError: Posting code 109 (account update failed).
        """
        # Step 1 -- VALIDATE-INPUT-KEY-FIELDS: resolve the card + account keys.
        cardNum, acctId = await self._ResolveCardAndAccount(session, transactionCreate)
        # Step 2 -- VALIDATE-INPUT-DATA-FIELDS: per-field edits; parse the amount.
        tranAmt = self._ValidateDataFields(transactionCreate)
        # Step 3 -- CBTRN02C posting validation (codes 100/101, then 102/103).
        account = await self._LoadAccountForPosting(session, cardNum)
        self._RunPostingValidation(account, tranAmt, transactionCreate.orig_ts)
        # Steps 4 + 5 -- insert the transaction and post the balances atomically.
        try:
            transaction = await self._InsertNewTransaction(
                session, cardNum, tranAmt, transactionCreate
            )
            await self._PostToAccount(session, acctId, tranAmt)
            await session.commit()
        except (SQLAlchemyError, AccountUpdateFailedError):
            # Either write failing voids the whole unit-of-work: roll back and
            # re-raise so no partial (transaction-without-posting) state leaks.
            await session.rollback()
            raise
        # commit expires ORM attributes; refresh before serializing the response.
        await session.refresh(transaction)
        return TransactionRead.model_validate(transaction)

    # -----------------------------------------------------------------------
    # Key-field resolution (COTRN02C VALIDATE-INPUT-KEY-FIELDS)
    # -----------------------------------------------------------------------
    async def _ResolveCardAndAccount(
        self, session: AsyncSession, transactionCreate: TransactionCreate
    ) -> tuple[str, str]:
        """Resolve the (card_num, acct_id) pair from the supplied key fields.

        Ports the COTRN02C ``EVALUATE TRUE`` key-field edit: the account-id path
        takes precedence (the card is resolved from the account cross-reference),
        the card path runs only when no account id was supplied, and if neither
        key is present the "must be entered" edit fires.

        Args:
            session: Active async database session.
            transactionCreate: The add-transaction request DTO.

        Returns:
            A ``(card_num, acct_id)`` tuple, both zero-padded to their key width.

        Raises:
            DomainValidationError: When neither key was supplied (L226).
            NotFoundError: When the supplied key does not resolve to a cross-ref.
        """
        providedAcctId = transactionCreate.acct_id
        providedCardNum = transactionCreate.card_num
        if providedAcctId is not None and providedAcctId.strip():
            return await self._ResolveViaAcctId(session, providedAcctId)
        if providedCardNum is not None and providedCardNum.strip():
            return await self._ResolveViaCardNum(session, providedCardNum)
        raise DomainValidationError(MSG_ACCT_OR_CARD_REQUIRED)  # COTRN02C L226

    async def _ResolveViaAcctId(
        self, session: AsyncSession, acctId: str
    ) -> tuple[str, str]:
        """Resolve the owning card from an account id (COTRN02C READ-CXACAIX).

        The account id must be all digits (L199); it is then zero-padded to the
        11-char key width and looked up through the account alternate index
        (CXACAIX). The first cross-reference row supplies the card number.

        Args:
            session: Active async database session.
            acctId: The operator-entered account identifier.

        Returns:
            A ``(card_num, acct_id)`` tuple.

        Raises:
            DomainValidationError: When ``acctId`` is not numeric (L199).
            NotFoundError: When no cross-reference row exists (L593).
        """
        if not self._IsNumericText(acctId):
            raise DomainValidationError(MSG_ACCT_ID_NOT_NUMERIC)  # COTRN02C L199
        normalizedAcctId = acctId.strip().zfill(validators.ACCT_ID_LENGTH)
        xrefRows = await self.xrefRepository.ListByAcctId(session, normalizedAcctId)
        if not xrefRows:
            raise NotFoundError(MSG_ACCT_ID_NOT_FOUND)  # COTRN02C L593
        return xrefRows[0].xref_card_num, normalizedAcctId

    async def _ResolveViaCardNum(
        self, session: AsyncSession, cardNum: str
    ) -> tuple[str, str]:
        """Resolve the owning account from a card number (COTRN02C READ-CCXREF).

        The card number must be all digits (L213); it is then zero-padded to the
        16-char key width and looked up in the card cross-reference. The row
        supplies the account id.

        Args:
            session: Active async database session.
            cardNum: The operator-entered card number.

        Returns:
            A ``(card_num, acct_id)`` tuple.

        Raises:
            DomainValidationError: When ``cardNum`` is not numeric (L213).
            NotFoundError: When no cross-reference row exists (L626).
        """
        if not self._IsNumericText(cardNum):
            raise DomainValidationError(MSG_CARD_NUM_NOT_NUMERIC)  # COTRN02C L213
        normalizedCardNum = cardNum.strip().zfill(validators.CARD_NUM_LENGTH)
        xrefRecord = await self.xrefRepository.GetByCardNum(session, normalizedCardNum)
        if xrefRecord is None:
            raise NotFoundError(MSG_CARD_NUM_NOT_FOUND)  # COTRN02C L626
        return normalizedCardNum, xrefRecord.acct_id

    # -----------------------------------------------------------------------
    # Data-field edits (COTRN02C VALIDATE-INPUT-DATA-FIELDS)
    # -----------------------------------------------------------------------
    def _ValidateDataFields(self, transactionCreate: TransactionCreate) -> Decimal:
        """Run the COTRN02C data-field edits and return the parsed amount.

        Applies the edits in the legacy order: the emptiness edits (first empty
        field wins), the Type-CD/Category-CD/Merchant-ID numeric edits, the
        amount format edit, and the Orig/Proc date format edits.

        Args:
            transactionCreate: The add-transaction request DTO.

        Returns:
            The transaction amount as an exact :class:`~decimal.Decimal`.

        Raises:
            DomainValidationError: On the first failed edit, with its verbatim
                COTRN02C message.
        """
        self._ValidateRequiredFields(transactionCreate)
        self._ValidateNumericFields(transactionCreate)
        tranAmt = self._ParseAmount(transactionCreate)
        self._ValidateDates(transactionCreate)
        return tranAmt

    def _ValidateRequiredFields(self, transactionCreate: TransactionCreate) -> None:
        """Enforce the COTRN02C "can NOT be empty" edits (L251-320).

        The legacy ``EVALUATE TRUE`` stops at the first empty field, so the
        checks run in screen order and the first blank raises its verbatim
        message. ``ValidateRequired`` supplies the emptiness test; the raised
        text is the exact COBOL constant (the validator's own label is unused).

        Args:
            transactionCreate: The add-transaction request DTO.

        Raises:
            DomainValidationError: For the first blank field.
        """
        requiredEdits = (
            (transactionCreate.tran_type_cd, MSG_TYPE_CD_EMPTY),
            (transactionCreate.tran_cat_cd, MSG_CATEGORY_CD_EMPTY),
            (transactionCreate.tran_source, MSG_SOURCE_EMPTY),
            (transactionCreate.tran_desc, MSG_DESCRIPTION_EMPTY),
            (transactionCreate.tran_amt, MSG_AMOUNT_EMPTY),
            (transactionCreate.orig_ts, MSG_ORIG_DATE_EMPTY),
            (transactionCreate.proc_ts, MSG_PROC_DATE_EMPTY),
            (transactionCreate.merchant_id, MSG_MERCHANT_ID_EMPTY),
            (transactionCreate.merchant_name, MSG_MERCHANT_NAME_EMPTY),
            (transactionCreate.merchant_city, MSG_MERCHANT_CITY_EMPTY),
            (transactionCreate.merchant_zip, MSG_MERCHANT_ZIP_EMPTY),
        )
        for fieldValue, emptyMessage in requiredEdits:
            if not validators.ValidateRequired(emptyMessage, fieldValue).isValid:
                raise DomainValidationError(emptyMessage)

    def _ValidateNumericFields(self, transactionCreate: TransactionCreate) -> None:
        """Enforce the COTRN02C numeric edits for Type/Category/Merchant.

        Ports L322-337 (Type CD / Category CD) and L430-436 (Merchant ID): each
        value must be all digits (COBOL ``IS NUMERIC``) or the matching "must be
        Numeric" edit fires. The emptiness edits run first, so each value here
        is already present.

        Args:
            transactionCreate: The add-transaction request DTO.

        Raises:
            DomainValidationError: For the first non-numeric field.
        """
        if not self._IsNumericText(transactionCreate.tran_type_cd):
            raise DomainValidationError(MSG_TYPE_CD_NOT_NUMERIC)  # COTRN02C L325
        if not self._IsNumericText(transactionCreate.tran_cat_cd):
            raise DomainValidationError(MSG_CATEGORY_CD_NOT_NUMERIC)  # L331
        if not self._IsNumericText(transactionCreate.merchant_id):
            raise DomainValidationError(MSG_MERCHANT_ID_NOT_NUMERIC)  # L432

    def _ParseAmount(self, transactionCreate: TransactionCreate) -> Decimal:
        """Parse the transaction amount as an exact Decimal (COTRN02C L339-351).

        The screen edit checks the signed ``-99999999.99`` picture and then
        applies ``NUMVAL-C``. :func:`decimal_utils.ToDecimal` performs the exact
        conversion and rejects binary ``float`` input outright (AAP 0.7.1); any
        parse failure surfaces the verbatim amount-format message.

        Args:
            transactionCreate: The add-transaction request DTO.

        Returns:
            The amount as an exact :class:`~decimal.Decimal`.

        Raises:
            DomainValidationError: When the amount is not a parseable decimal.
        """
        try:
            return decimal_utils.ToDecimal(transactionCreate.tran_amt)
        except (ValueError, InvalidOperation, TypeError) as amountError:
            raise DomainValidationError(MSG_AMOUNT_FORMAT) from amountError  # L345

    def _ValidateDates(self, transactionCreate: TransactionCreate) -> None:
        """Enforce the COTRN02C Orig/Proc date format edits (L353-427).

        Each date must be a valid ``YYYY-MM-DD`` value. The DTO already parses
        the inputs to :class:`~datetime.datetime`; the date portion is re-checked
        through :func:`date_utils.ValidateDate` for behavioral parity and to keep
        the edit independently testable.

        Args:
            transactionCreate: The add-transaction request DTO.

        Raises:
            DomainValidationError: When either date fails the format edit.
        """
        origDateText = transactionCreate.orig_ts.date().isoformat()
        if not date_utils.ValidateDate(origDateText).isValid:
            raise DomainValidationError(MSG_ORIG_DATE_FORMAT)  # COTRN02C L360
        procDateText = transactionCreate.proc_ts.date().isoformat()
        if not date_utils.ValidateDate(procDateText).isValid:
            raise DomainValidationError(MSG_PROC_DATE_FORMAT)  # COTRN02C L375

    # -----------------------------------------------------------------------
    # Posting validation (CBTRN02C 1500-VALIDATE-TRAN, codes 100-103)
    # -----------------------------------------------------------------------
    async def _LoadAccountForPosting(
        self, session: AsyncSession, cardNum: str
    ) -> Account:
        """Re-read the card cross-ref and account for posting (codes 100/101).

        CBTRN02C 1500-VALIDATE-TRAN independently re-reads the XREF by card
        number (INVALID KEY -> code 100) and then the account (INVALID KEY ->
        code 101). This re-read is intentional parity even though the card was
        already resolved during key-field validation: the batch validator makes
        both reads, so the online add path does too, keeping the reject codes
        reconcilable with the batch posting job (AAP 0.7.3).

        Args:
            session: Active async database session.
            cardNum: The resolved 16-digit card number.

        Returns:
            The owning :class:`~app.models.account.Account`.

        Raises:
            InvalidCardNumberError: Code 100 -- no cross-reference for the card.
            AccountNotFoundError: Code 101 -- no account for the cross-ref.
        """
        xrefRecord = await self.xrefRepository.GetByCardNum(session, cardNum)
        if xrefRecord is None:
            raise InvalidCardNumberError()  # code 100 "INVALID CARD NUMBER FOUND"
        account = await self.accountRepository.GetByAcctId(session, xrefRecord.acct_id)
        if account is None:
            raise AccountNotFoundError()  # code 101 "ACCOUNT RECORD NOT FOUND"
        return account

    def _RunPostingValidation(
        self, account: Account, tranAmt: Decimal, origTs: Optional[object]
    ) -> None:
        """Apply the CBTRN02C over-limit and expiration edits (codes 102/103).

        Reproduces 1500-VALIDATE-TRAN exactly. Over-limit uses the cycle
        credit/debit running total plus the transaction amount --
        ``WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + amount`` --
        and rejects when the credit limit is below it. This is DISTINCT from the
        bill-payment "available credit = credit limit - current balance"
        (F-006) rule and must not be conflated. Expiration rejects when the
        account expiration date precedes the transaction's original date. All
        arithmetic is :class:`~decimal.Decimal` (never ``float``).

        Args:
            account: The owning account, already loaded.
            tranAmt: The transaction amount (exact Decimal).
            origTs: The transaction's original timestamp (``datetime``).

        Raises:
            OverlimitTransactionError: Code 102 -- over the credit limit.
            AccountExpiredError: Code 103 -- received after expiration.
        """
        # Code 102 -- over-limit (CBTRN02C L406-414).
        tempBal = account.curr_cyc_credit - account.curr_cyc_debit + tranAmt
        if account.credit_limit < tempBal:
            raise OverlimitTransactionError()  # code 102 "OVERLIMIT TRANSACTION"
        # Code 103 -- received after expiration (CBTRN02C L415-421). A missing
        # expiration date cannot expire the transaction, so it passes.
        if origTs is not None and account.expiration_date is not None:
            if account.expiration_date < origTs.date():
                raise AccountExpiredError()  # code 103

    # -----------------------------------------------------------------------
    # ADD-TRANSACTION insert (COTRN02C) + 2800-UPDATE-ACCOUNT-REC (code 109)
    # -----------------------------------------------------------------------
    async def _InsertNewTransaction(
        self,
        session: AsyncSession,
        cardNum: str,
        tranAmt: Decimal,
        transactionCreate: TransactionCreate,
    ) -> Transaction:
        """Assign the next id, build the row, and insert it (no commit).

        Mirrors COTRN02C ADD-TRANSACTION: the next id is derived from the current
        maximum, the TRAN-RECORD fields are populated, and the row is inserted
        (the repository flushes but does not commit -- the caller owns the
        unit-of-work).

        Args:
            session: Active async database session.
            cardNum: The resolved 16-digit card number for TRAN-CARD-NUM.
            tranAmt: The transaction amount (exact Decimal).
            transactionCreate: The add-transaction request DTO.

        Returns:
            The inserted (session-attached) :class:`~app.models.transaction.Transaction`.
        """
        nextTranId = await self._NextTranId(session)
        transaction = self._BuildTransaction(
            nextTranId, cardNum, tranAmt, transactionCreate
        )
        await self.transactionRepository.Insert(session, transaction)
        return transaction

    async def _NextTranId(self, session: AsyncSession) -> str:
        """Compute the next transaction id (COTRN02C STARTBR/READPREV + ADD 1).

        The legacy program browses the TRANSACT file from HIGH-VALUES backwards
        to read the highest existing id, then adds one. Here the repository's
        ``MAX(tran_id)`` supplies the current maximum; the successor is
        right-justified in the 16-character ``TRAN-ID`` by zero-filling.

        Args:
            session: Active async database session.

        Returns:
            The next transaction id as a 16-character zero-padded string.
        """
        currentMax = await self.transactionRepository.GetMaxTranId(session)
        if currentMax is None:
            return "1".zfill(TRAN_ID_PAD_WIDTH)
        return str(int(currentMax) + 1).zfill(TRAN_ID_PAD_WIDTH)

    def _BuildTransaction(
        self,
        nextTranId: str,
        cardNum: str,
        tranAmt: Decimal,
        transactionCreate: TransactionCreate,
    ) -> Transaction:
        """Populate a TRAN-RECORD (CVTRA05Y) from the request (COTRN02C L450-465).

        ``status`` is intentionally NOT set here: the ORM model defaults it to
        POSTED (matching the fact that COTRN02C writes directly to the posted
        TRANSACT file). ``tran_amt`` is kept as an exact Decimal and ``card_num``
        is the resolved 16-digit card.

        Args:
            nextTranId: The assigned 16-character transaction id.
            cardNum: The resolved 16-digit card number.
            tranAmt: The transaction amount (exact Decimal).
            transactionCreate: The add-transaction request DTO.

        Returns:
            A new, unsaved :class:`~app.models.transaction.Transaction`.
        """
        return Transaction(
            tran_id=nextTranId,
            tran_type_cd=transactionCreate.tran_type_cd,
            tran_cat_cd=transactionCreate.tran_cat_cd,
            tran_source=transactionCreate.tran_source,
            tran_desc=transactionCreate.tran_desc,
            tran_amt=tranAmt,
            card_num=cardNum,
            merchant_id=transactionCreate.merchant_id,
            merchant_name=transactionCreate.merchant_name,
            merchant_city=transactionCreate.merchant_city,
            merchant_zip=transactionCreate.merchant_zip,
            orig_ts=transactionCreate.orig_ts,
            proc_ts=transactionCreate.proc_ts,
        )

    async def _PostToAccount(
        self, session: AsyncSession, acctId: str, tranAmt: Decimal
    ) -> None:
        """Lock the account, adjust its balances, and persist (code 109).

        Ports CBTRN02C 2800-UPDATE-ACCOUNT-REC: the account is read for update
        (SELECT ... FOR UPDATE, reproducing the CICS READ-for-UPDATE lock), its
        balances are adjusted, and the row is updated. A missing account or any
        database error during the update maps to reject code 109 (the legacy
        ``REWRITE ... INVALID KEY``).

        Args:
            session: Active async unit-of-work session (owns the row lock).
            acctId: The 11-digit account id to lock and update.
            tranAmt: The transaction amount (exact Decimal).

        Raises:
            AccountUpdateFailedError: Code 109 -- the account could not be updated.
        """
        try:
            lockedAccount = await self.accountRepository.GetForUpdate(session, acctId)
            if lockedAccount is None:
                raise AccountUpdateFailedError()  # code 109 "ACCOUNT RECORD NOT FOUND"
            self._ApplyBalanceUpdate(lockedAccount, tranAmt)
            await self.accountRepository.Update(session, lockedAccount)
        except SQLAlchemyError as updateError:
            raise AccountUpdateFailedError() from updateError  # code 109

    def _ApplyBalanceUpdate(self, account: Account, tranAmt: Decimal) -> None:
        """Adjust the account balances in place (CBTRN02C L547-553).

        Adds the amount to the current balance, then to the cycle credit total
        when the amount is non-negative or to the cycle debit total otherwise.
        Every operand is a :class:`~decimal.Decimal`, so no binary floating-point
        rounding can enter the monetary math (AAP 0.7.1).

        Args:
            account: The locked, session-attached account to mutate.
            tranAmt: The transaction amount (exact Decimal).
        """
        account.curr_bal = account.curr_bal + tranAmt
        if tranAmt >= 0:
            account.curr_cyc_credit = account.curr_cyc_credit + tranAmt
        else:
            account.curr_cyc_debit = account.curr_cyc_debit + tranAmt

    # -----------------------------------------------------------------------
    # Small shared predicate
    # -----------------------------------------------------------------------
    @staticmethod
    def _IsNumericText(value: object) -> bool:
        """Report whether ``value`` is a non-empty run of ASCII digits.

        Reproduces the COBOL ``IS NUMERIC`` class test for the unsigned display
        fields (account id, card number, type/category codes, merchant id):
        combining :meth:`str.isascii` with :meth:`str.isdigit` rejects Unicode
        digit look-alikes so any later ``int(...)`` stays safe. A non-string or
        blank value is not numeric.

        Args:
            value: The candidate value (already whitespace-stripped by the DTO).

        Returns:
            True when ``value`` is a non-empty ASCII digit string.
        """
        if not isinstance(value, str):
            return False
        candidate = value.strip()
        return bool(candidate) and candidate.isascii() and candidate.isdigit()

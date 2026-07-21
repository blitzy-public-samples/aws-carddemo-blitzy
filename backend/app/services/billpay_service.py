"""Bill payment service.

Ported 1:1 from legacy CICS online program COBIL00C (tx CB00). Implements
pay-in-full bill payment: reads the account, computes available credit
(F-006: available_credit = credit_limit - curr_bal), and on confirmation posts
a payment transaction (TYPE 02) and zeroes the current balance. Money is
Decimal (never float). See §0.5.1, §0.7.1, §0.8.1.

Legacy source artifacts (REFERENCE only -- never modified):
    * app/cbl/COBIL00C.cbl -- the CICS online bill-payment program whose
      PROCEDURE DIVISION logic (PROCESS-ENTER-KEY, READ-ACCTDAT-FILE,
      READ-CXACAIX-FILE, WRITE-TRANSACT-FILE, UPDATE-ACCTDAT-FILE) is ported to
      the two public methods and their helpers below.
    * app/cpy/CVACT01Y.cpy -- ACCOUNT-RECORD (ACCT-CURR-BAL / ACCT-CREDIT-LIMIT
      drive the F-006 available-credit figure and the pay-in-full amount).
    * app/cpy/CVACT03Y.cpy -- CARD-XREF-RECORD (XREF-CARD-NUM resolves the card
      the payment transaction is written against, via the CXACAIX account path).
    * app/cpy/CVTRA05Y.cpy -- TRAN-RECORD (the layout of the bill-payment
      transaction row that is inserted on confirmation).

Business-rule boundary (AAP §0.8.1): the single governing rule here is
F-006 -- available_credit = credit_limit - curr_bal. This is intentionally
DISTINCT from the batch posting over-limit rule (reject code 102), which uses
``curr_cyc_credit - curr_cyc_debit + tran_amt`` compared against
``credit_limit``. That cycle formula lives in the posting service / batch job
and is NEVER computed here.

Numeric fidelity (AAP §0.7.1): every monetary value flows through
:class:`decimal.Decimal` via :mod:`app.utils.decimal_utils`; binary floating
point is never used, because it would violate the regulatory numeric-parity
requirement for currency amounts.

Unit-of-work ownership (AAP §0.4.3): the ``get_db`` FastAPI dependency does not
commit; the service and repository layers own ``commit()``. :meth:`PayBill`
therefore performs the transaction insert and the balance rewrite inside one
database transaction and commits it explicitly, rolling back on any failure so
no partial payment is ever persisted. :meth:`GetBillPayInfo` is read-only and
never commits.

Naming conventions (Ochs Rule, AAP §0.8.3): the module/file name is snake_case;
the class and its methods are PascalCase; local variables are camelCase; and
module-level constants are ALL_UPPERCASE with underscores.
"""

# Ported from COBOL online program COBIL00C (app/cbl/COBIL00C.cbl) together with
# the record copybooks CVACT01Y (account), CVACT03Y (card cross-reference) and
# CVTRA05Y (transaction). The F-006 available-credit rule and the pay-in-full
# behavior are preserved verbatim under the Minimal Change Clause (AAP §0.8.1);
# the F-006 formula is deliberately NOT conflated with the batch posting
# over-limit rule (reject code 102, AAP §0.7.3).

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import DomainValidationError, NotFoundError
from app.models.account import Account
from app.models.transaction import Transaction
from app.repositories import (
    AccountRepository,
    CardXrefRepository,
    TransactionRepository,
)
from app.schemas import BillPayRequest, BillPayResponse
from app.utils import decimal_utils, validators

__all__ = ["BillPayService"]


# ---------------------------------------------------------------------------
# Field labels and lengths (Ochs Rule §0.8.2: constants are ALL_UPPERCASE). The
# account-id length is sourced from the shared validators module so this service
# stays in lock-step with the single source of truth rather than restating the
# magic number 11.
# ---------------------------------------------------------------------------

# Human-readable label used to build the numeric-id edit failure message
# (mirrors the legacy "Acct ID" screen field wording in COBIL00C).
ACCT_ID_LABEL = "Acct ID"

# ACCT-ID PIC 9(11): the exact digit count the account identifier must have.
ACCT_ID_LENGTH = validators.ACCT_ID_LENGTH

# TRAN-ID PIC X(16): width of the zero-padded numeric transaction identifier.
TRAN_ID_WIDTH = validators.TRAN_ID_LENGTH

# Seed used when the transactions table is empty (COBOL READPREV on an empty
# file leaves TRAN-ID at zero, and ``ADD 1`` then yields the first id of 1).
FIRST_TRAN_ID_SEED = "1"

# ---------------------------------------------------------------------------
# Confirmation-flag values (COBIL00C EVALUATE CONFIRMI, L173-191). The legacy
# program accepts both upper- and lower-case 'Y'/'N'; callers' input is upper
# cased before comparison to reproduce that behavior exactly.
# ---------------------------------------------------------------------------
CONFIRM_YES = "Y"
CONFIRM_NO = "N"
VALID_CONFIRM_VALUES = (CONFIRM_YES, CONFIRM_NO)

# ---------------------------------------------------------------------------
# Payment-transaction literals -- VERBATIM from COBIL00C PROCESS-ENTER-KEY
# (L220-229). These reproduce the exact TRAN-RECORD field values the mainframe
# wrote for an online bill payment, for traceability and downstream parity.
# ---------------------------------------------------------------------------
PAYMENT_TRAN_TYPE = "02"                      # MOVE '02' TO TRAN-TYPE-CD          [L220]
PAYMENT_TRAN_CATEGORY = "2"                   # MOVE 2 TO TRAN-CAT-CD              [L221]
PAYMENT_TRAN_SOURCE = "POS TERM"              # MOVE 'POS TERM' TO TRAN-SOURCE     [L222]
PAYMENT_TRAN_DESC = "BILL PAYMENT - ONLINE"   # MOVE '...' TO TRAN-DESC            [L223]
PAYMENT_MERCHANT_ID = "999999999"             # MOVE 999999999 TO TRAN-MERCHANT-ID [L226]
PAYMENT_MERCHANT_NAME = "BILL PAYMENT"        # MOVE '...' TO TRAN-MERCHANT-NAME   [L227]
PAYMENT_MERCHANT_CITY = "N/A"                 # MOVE 'N/A' TO TRAN-MERCHANT-CITY   [L228]
PAYMENT_MERCHANT_ZIP = "N/A"                  # MOVE 'N/A' TO TRAN-MERCHANT-ZIP    [L229]

# The pay-in-full "nothing to pay" threshold: COBIL00C rejects a payment when
# ACCT-CURR-BAL <= ZEROS (L198). Exact Decimal zero -- never a float literal.
ZERO_AMOUNT = Decimal("0")

# ---------------------------------------------------------------------------
# User-facing messages -- VERBATIM from COBIL00C (character-for-character,
# including the trailing ellipsis) so the ported behavior surfaces the identical
# wording the legacy screen produced.
# ---------------------------------------------------------------------------
MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty..."                 # [L161]
MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)..."  # [L187]
MSG_NOTHING_TO_PAY = "You have nothing to pay..."                 # [L201]
MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment..."         # [L237]
MSG_UNABLE_ADD_TRAN = "Unable to Add Bill pay Transaction..."     # [L543]
MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found..."                 # [L361]
MSG_XREF_NOT_FOUND = "Unable to lookup XREF AIX file..."          # [L432]

# Success confirmation. COBIL00C builds "Payment successful.  Your Transaction
# ID is <TRAN-ID>." via STRING (L527-530); the modern equivalent formats the
# posted transaction id into the same wording.
PAYMENT_SUCCESS_TEMPLATE = "Payment successful. Your Transaction ID is {tranId}."


class BillPayService:
    """Online bill-payment business logic (1:1 port of COBIL00C, tx CB00).

    Orchestrates the account, card cross-reference, and transaction
    repositories to reproduce the legacy pay-in-full bill-payment flow while
    honoring the F-006 available-credit rule and exact ``Decimal`` money
    arithmetic. The class is stateless apart from its repository handles, so a
    single instance is safe to reuse across requests; the active
    :class:`~sqlalchemy.ext.asyncio.AsyncSession` is always passed in per call
    and the transaction boundary is owned by :meth:`PayBill` (AAP §0.4.3).

    Public methods:
        GetBillPayInfo: Read-only lookup of an account's balance, credit limit,
            and F-006 available credit (the COBIL00C initial screen).
        PayBill: Validate, guard, and -- on confirmation -- post a pay-in-full
            payment transaction and zero the current balance in one committed
            unit of work (the COBIL00C confirm='Y' path).
    """

    def __init__(self) -> None:
        """Instantiate the service with its three collaborating repositories.

        Each repository is stateless (it takes the session per call), so
        constructing them here once is safe and mirrors the legacy program's
        fixed set of VSAM files: ACCTDAT (account), CXACAIX (card
        cross-reference alternate index) and TRANSACT (transaction).
        """
        self.accountRepository = AccountRepository()
        self.xrefRepository = CardXrefRepository()
        self.transactionRepository = TransactionRepository()

    # -----------------------------------------------------------------------
    # Public API
    # -----------------------------------------------------------------------

    async def GetBillPayInfo(self, session: AsyncSession, acctId: str) -> BillPayResponse:
        """Return an account's balance, limit, and F-006 available credit.

        Modern equivalent of the COBIL00C initial-screen read (PROCESS-ENTER-KEY
        with a blank confirmation flag, then READ-ACCTDAT-FILE): it validates the
        account id, reads the account (a lock-free read -- this path never
        mutates state and never commits), and returns the display figures with
        ``available_credit = credit_limit - curr_bal`` (F-006).

        Args:
            session: Active async unit-of-work session (not committed here).
            acctId: The 11-digit account identifier to look up; leading zeros are
                significant and preserved.

        Returns:
            A :class:`~app.schemas.billpay.BillPayResponse` carrying the current
            balance, credit limit, F-006 available credit, and the full-balance
            ``payment_amount``; ``tran_id`` and ``message`` are ``None`` because
            no payment has been posted.

        Raises:
            DomainValidationError: If ``acctId`` is blank or is not exactly
                :data:`ACCT_ID_LENGTH` digits.
            NotFoundError: If no account exists with that id.
        """
        validatedAcctId = self._ValidateAcctId(acctId)
        account = await self.accountRepository.GetByAcctId(session, validatedAcctId)
        if account is None:
            raise NotFoundError(MSG_ACCOUNT_NOT_FOUND)
        return self._BuildReadOnlyResponse(account, None)

    async def PayBill(self, session: AsyncSession, billPayRequest: BillPayRequest) -> BillPayResponse:
        """Post a pay-in-full bill payment (COBIL00C confirm='Y' path).

        Reproduces PROCESS-ENTER-KEY in the exact legacy guard order: validate
        the account id, validate the confirmation flag, lock and read the account
        for update, reject when there is nothing to pay, and -- only when the
        payment is confirmed -- post the payment transaction and zero the
        balance in a single committed unit of work. When the flag is 'N' the
        method returns the F-006 figures with the "confirm to pay" prompt and
        posts nothing.

        Args:
            session: Active async unit-of-work session; :meth:`PayBill` owns the
                ``commit()`` / ``rollback()`` for the confirmed-payment path.
            billPayRequest: The validated request DTO carrying ``acct_id`` and
                the ``confirm`` flag. The optional ``payment_amount`` field is
                intentionally NOT used for the amount: COBIL00C pays the entire
                current balance (Minimal Change Clause), so the amount is always
                the full ``curr_bal``.

        Returns:
            A :class:`~app.schemas.billpay.BillPayResponse`. For a confirmed
            payment it carries the zeroed balance, the recomputed available
            credit, the posted ``tran_id``, and a success message; for an
            unconfirmed ('N') request it carries the current figures and the
            "confirm to pay" prompt with ``tran_id=None``.

        Raises:
            DomainValidationError: If the account id is blank/non-numeric, the
                confirmation flag is neither 'Y' nor 'N', there is nothing to pay
                (balance <= 0), or the payment transaction cannot be inserted.
            NotFoundError: If the account does not exist, or no card
                cross-reference is found to post the payment against.
        """
        validatedAcctId = self._ValidateAcctId(billPayRequest.acct_id)
        confirmFlag = self._NormalizeConfirm(billPayRequest.confirm)
        account = await self.accountRepository.GetForUpdate(session, validatedAcctId)
        if account is None:
            raise NotFoundError(MSG_ACCOUNT_NOT_FOUND)
        if decimal_utils.ToDecimal(account.curr_bal) <= ZERO_AMOUNT:
            raise DomainValidationError(MSG_NOTHING_TO_PAY)
        if confirmFlag != CONFIRM_YES:
            return self._BuildReadOnlyResponse(account, MSG_CONFIRM_PAYMENT)
        return await self._ExecutePayment(session, account)

    # -----------------------------------------------------------------------
    # Private helpers (module-internal; keep each public method small per Ochs).
    # -----------------------------------------------------------------------

    def _ValidateAcctId(self, acctId: str) -> str:
        """Validate the account id: present, then exactly 11 ASCII digits.

        Mirrors the COBIL00C empty-field guard (L159-161) followed by the
        fixed-length numeric edit. The blank check is performed first so the
        verbatim "Acct ID can NOT be empty..." wording surfaces for an empty
        value, exactly as the legacy screen did.

        Args:
            acctId: The candidate account identifier.

        Returns:
            The validated account id, unchanged (leading zeros preserved).

        Raises:
            DomainValidationError: If the value is blank or is not exactly
                :data:`ACCT_ID_LENGTH` digits.
        """
        if acctId is None or acctId.strip() == "":
            raise DomainValidationError(MSG_ACCT_ID_EMPTY)
        editResult = validators.ValidateNumericId(ACCT_ID_LABEL, acctId, ACCT_ID_LENGTH)
        if not editResult.isValid:
            raise DomainValidationError(editResult.message)
        return acctId

    def _NormalizeConfirm(self, confirm: str) -> str:
        """Normalize and validate the confirmation flag to 'Y' or 'N'.

        Reproduces the COBIL00C ``EVALUATE CONFIRMI`` (L173-191), which accepts
        both upper- and lower-case 'Y'/'N'; the value is stripped and upper
        cased before the membership check so 'y'/'n' behave exactly like their
        uppercase forms. Any other value raises the verbatim "Invalid value..."
        wording (L187).

        Args:
            confirm: The raw confirmation flag from the request.

        Returns:
            The normalized flag: the uppercase literal 'Y' or 'N'.

        Raises:
            DomainValidationError: If the value is neither 'Y' nor 'N'.
        """
        normalizedConfirm = (confirm or "").strip().upper()
        if normalizedConfirm not in VALID_CONFIRM_VALUES:
            raise DomainValidationError(MSG_INVALID_CONFIRM)
        return normalizedConfirm

    def _ComputeAvailableCredit(self, account: Account) -> Decimal:
        """Compute F-006 available credit = credit_limit - curr_bal.

        The single governing bill-payment rule (AAP §0.8.1). Both operands are
        routed through :func:`app.utils.decimal_utils.ToDecimal` so the result
        is an exact :class:`decimal.Decimal`; float is never involved. This is
        deliberately NOT the posting over-limit formula (reject code 102).

        Args:
            account: The account whose available credit is computed.

        Returns:
            The available credit as an exact :class:`decimal.Decimal`; it may be
            negative when the balance exceeds the credit limit.
        """
        creditLimit = decimal_utils.ToDecimal(account.credit_limit)
        currentBalance = decimal_utils.ToDecimal(account.curr_bal)
        return creditLimit - currentBalance

    async def _NextTranId(self, session: AsyncSession) -> str:
        """Return the next zero-padded 16-digit transaction id.

        Ports the COBIL00C "read the last transaction, add one" sequence
        (MOVE HIGH-VALUES -> STARTBR -> READPREV -> ENDBR -> ADD 1, L212-217).
        When the table is empty the max id is ``None`` and the first id is 1,
        matching the legacy zero-then-increment behavior.

        Args:
            session: Active async unit-of-work session.

        Returns:
            The next transaction id as a 16-character zero-padded digit string.
        """
        currentMax = await self.transactionRepository.GetMaxTranId(session)
        if currentMax is None or currentMax.strip() == "":
            return FIRST_TRAN_ID_SEED.zfill(TRAN_ID_WIDTH)
        return str(int(currentMax) + 1).zfill(TRAN_ID_WIDTH)

    def _BuildPaymentTransaction(self, cardNum: str, tranId: str, paymentAmount: Decimal) -> Transaction:
        """Build the bill-payment :class:`Transaction` with verbatim literals.

        Reproduces the COBIL00C TRAN-RECORD population (L218-232): a payment of
        ``paymentAmount`` against ``cardNum`` with the fixed type/category,
        source, description and merchant fields. ``orig_ts`` and ``proc_ts`` are
        set to the same current UTC timestamp (the legacy program moves one
        formatted timestamp into both). The ``status`` column is intentionally
        left unset so the model default (POSTED) applies.

        Args:
            cardNum: The card number the payment is posted against
                (``XREF-CARD-NUM`` resolved via the account cross-reference).
            tranId: The zero-padded 16-digit transaction id.
            paymentAmount: The exact ``Decimal`` amount being paid (full balance).

        Returns:
            A fully-populated, not-yet-persisted :class:`Transaction`.
        """
        paymentTimestamp = datetime.now(timezone.utc)
        return Transaction(
            tran_id=tranId,
            tran_type_cd=PAYMENT_TRAN_TYPE,
            tran_cat_cd=PAYMENT_TRAN_CATEGORY,
            tran_source=PAYMENT_TRAN_SOURCE,
            tran_desc=PAYMENT_TRAN_DESC,
            tran_amt=paymentAmount,
            merchant_id=PAYMENT_MERCHANT_ID,
            merchant_name=PAYMENT_MERCHANT_NAME,
            merchant_city=PAYMENT_MERCHANT_CITY,
            merchant_zip=PAYMENT_MERCHANT_ZIP,
            card_num=cardNum,
            orig_ts=paymentTimestamp,
            proc_ts=paymentTimestamp,
        )

    async def _ExecutePayment(self, session: AsyncSession, account: Account) -> BillPayResponse:
        """Post a confirmed pay-in-full payment and zero the balance.

        Reproduces the COBIL00C confirmed path (L210-235): resolve the card via
        the account cross-reference, compute the next transaction id, build and
        insert the payment transaction, subtract it from the balance (leaving
        zero), and commit -- all as one unit of work owned here. The amount is
        always the full current balance (Minimal Change Clause).

        Args:
            session: Active async unit-of-work session; committed here.
            account: The locked account being paid (fetched via ``GetForUpdate``).

        Returns:
            A success :class:`~app.schemas.billpay.BillPayResponse` with the
            zeroed balance, recomputed available credit, posted ``tran_id`` and
            a success message.

        Raises:
            NotFoundError: If the account has no card cross-reference to post
                the payment against.
            DomainValidationError: If the payment transaction cannot be
                inserted / committed (the unit of work is rolled back first).
        """
        paymentAmount = decimal_utils.ToDecimal(account.curr_bal)
        xrefs = await self.xrefRepository.ListByAcctId(session, account.acct_id)
        if not xrefs:
            raise NotFoundError(MSG_XREF_NOT_FOUND)
        nextTranId = await self._NextTranId(session)
        paymentTransaction = self._BuildPaymentTransaction(
            xrefs[0].xref_card_num, nextTranId, paymentAmount
        )
        await self._CommitPayment(session, paymentTransaction, account, paymentAmount)
        availableCredit = self._ComputeAvailableCredit(account)
        return BillPayResponse(
            acct_id=account.acct_id,
            curr_bal=account.curr_bal,
            credit_limit=account.credit_limit,
            available_credit=availableCredit,
            payment_amount=paymentAmount,
            tran_id=nextTranId,
            message=PAYMENT_SUCCESS_TEMPLATE.format(tranId=nextTranId),
        )

    async def _CommitPayment(
        self,
        session: AsyncSession,
        transaction: Transaction,
        account: Account,
        paymentAmount: Decimal,
    ) -> None:
        """Insert the payment, zero the balance, and commit atomically.

        Ports WRITE-TRANSACT-FILE + ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL -
        TRAN-AMT`` + UPDATE-ACCTDAT-FILE (L233-235). The insert and the balance
        rewrite are committed together as one unit of work; any
        :class:`sqlalchemy.exc.SQLAlchemyError` (for example the legacy DUPKEY /
        INVALID KEY write failure) triggers a rollback so no partial payment is
        persisted, and is re-raised as the verbatim "Unable to Add Bill pay
        Transaction..." validation error (L543).

        Args:
            session: Active async unit-of-work session; committed or rolled back.
            transaction: The fully-built payment transaction to insert.
            account: The locked account whose balance is zeroed.
            paymentAmount: The exact ``Decimal`` amount subtracted from the
                balance (equal to the full current balance, so the result is 0).

        Raises:
            DomainValidationError: If the insert or commit fails; the unit of
                work is rolled back before the error is raised.
        """
        try:
            await self.transactionRepository.Insert(session, transaction)
            account.curr_bal = decimal_utils.ToDecimal(account.curr_bal) - paymentAmount
            await self.accountRepository.Update(session, account)
            await session.commit()
        except SQLAlchemyError as exc:
            await session.rollback()
            raise DomainValidationError(MSG_UNABLE_ADD_TRAN) from exc

    def _BuildReadOnlyResponse(self, account: Account, message: str | None) -> BillPayResponse:
        """Build a no-posting response carrying the F-006 display figures.

        Shared by :meth:`GetBillPayInfo` (initial screen, ``message=None``) and
        the unconfirmed :meth:`PayBill` branch (``message`` = the "confirm to
        pay" prompt). No transaction is posted, so ``tran_id`` is ``None`` and
        ``payment_amount`` reflects the full balance that *would* be paid.

        Args:
            account: The account whose figures are surfaced.
            message: Optional status message (``None`` for the plain read).

        Returns:
            A :class:`~app.schemas.billpay.BillPayResponse` with the current
            balance, credit limit, F-006 available credit, full-balance
            ``payment_amount``, ``tran_id=None`` and the supplied ``message``.
        """
        availableCredit = self._ComputeAvailableCredit(account)
        return BillPayResponse(
            acct_id=account.acct_id,
            curr_bal=account.curr_bal,
            credit_limit=account.credit_limit,
            available_credit=availableCredit,
            payment_amount=account.curr_bal,
            tran_id=None,
            message=message,
        )

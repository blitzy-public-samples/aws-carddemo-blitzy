"""Account service.

Ported 1:1 from legacy CICS online programs COACTVWC (account view, CAVW) and
COACTUPC (account update, CAUP). The COACTUPC READ-UPDATE -> REWRITE optimistic
lock (CICS/VSAM record locking) is reproduced with SELECT ... FOR UPDATE plus a
before-image comparison. Money fields use Decimal (never float). Record layouts:
CVACT01Y (account), CVCUS01Y (customer), CVACT03Y (xref). See §0.5.1, §0.7.4, §0.8.1.

This module owns the account view + account update business rules. It is the
only place those rules live (the API routers are thin and the repositories only
do data access), matching the AAP layered architecture (§0.4.1/§0.4.3). Every
operator-facing message below is copied verbatim from the originating COBOL
88-level condition so behavior is preserved exactly (Minimal Change Clause,
§0.8.1).

Legacy paragraph traceability:
    * COACTVWC 2210-EDIT-ACCOUNT / 9200 / 9300 / 9400  -> ``GetAccount``.
    * COACTUPC 9600-WRITE-PROCESSING                    -> ``UpdateAccount``
      (lock account, lock customer, change check, rewrite, commit).
    * COACTUPC 9700-CHECK-CHANGE-IN-REC                 -> ``_CheckBeforeImage``
      (freshly-locked DB vs the before-image the client fetched).
    * COACTUPC 1205-COMPARE-OLD-NEW                     -> ``_EnsureChanged``
      (no-change short-circuit).
"""

from __future__ import annotations

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import (
    ConflictError,
    DomainValidationError,
    NotFoundError,
    OptimisticLockError,
)
from app.models.account import Account
from app.models.customer import Customer
from app.repositories import (
    AccountRepository,
    CardXrefRepository,
    CustomerRepository,
)
from app.schemas import AccountDetail, AccountRead, AccountUpdate, CustomerRead
from app.utils import date_utils, decimal_utils, validators

# ---------------------------------------------------------------------------
# Verbatim operator messages (ALL_UPPERCASE constants per the Ochs Rule).
# Each literal is the exact ``VALUE`` clause of the originating COBOL 88-level
# condition; never reword, re-case, or re-punctuate these.
# ---------------------------------------------------------------------------
# COACTVWC 2210-EDIT-ACCOUNT + read paragraphs.
MSG_ACCT_NOT_PROVIDED = "Account number not provided"
MSG_ACCT_NON_ZERO_11 = "Account number must be a non zero 11 digit number"
MSG_XREF_NOT_FOUND = "Did not find this account in account card xref file"
MSG_ACCT_NOT_FOUND = "Did not find this account in account master file"
MSG_CUST_NOT_FOUND = "Did not find associated customer in master file"
# COACTUPC 9600-WRITE-PROCESSING + 9700-CHECK-CHANGE-IN-REC + 1205-COMPARE-OLD-NEW.
# NOTE: the flag named LOCKED-BUT-UPDATE-FAILED carries the VALUE below; the
# user-facing text is "Update of record failed", not the flag name itself.
MSG_LOCK_ACCT_FAILED = "Could not lock account record for update"
MSG_LOCK_CUST_FAILED = "Could not lock customer record for update"
MSG_RECORD_CHANGED = "Record changed by some one else. Please review"
MSG_UPDATE_FAILED = "Update of record failed"
MSG_NO_CHANGE_DETECTED = "No change detected with respect to values fetched."

# ---------------------------------------------------------------------------
# Field labels passed to the ported validators (COACTUPC WS-EDIT-VARIABLE-NAME).
# ---------------------------------------------------------------------------
ACCT_ID_FIELD_LABEL = "Account number"
ACCT_STATUS_FIELD_LABEL = "Account Status"

# ---------------------------------------------------------------------------
# Editable account attributes, grouped by how they are validated/applied. Money
# fields are parsed through ``decimal_utils.ToDecimal`` (exact Decimal, never
# float); date fields are validated through ``date_utils.ValidateDate``. The
# maps double as the field-label source for validator messages.
# ---------------------------------------------------------------------------
MONEY_FIELD_LABELS = {
    "curr_bal": "Current Balance",
    "credit_limit": "Credit Limit",
    "cash_credit_limit": "Cash Credit Limit",
    "curr_cyc_credit": "Current Cycle Credit",
    "curr_cyc_debit": "Current Cycle Debit",
}
DATE_FIELD_LABELS = {
    "expiration_date": "Expiry Date",
    "reissue_date": "Reissue Date",
}
STATUS_FIELD_NAME = "active_status"
GROUP_ID_FIELD_NAME = "group_id"

MONEY_FIELD_NAMES = tuple(MONEY_FIELD_LABELS)
DATE_FIELD_NAMES = tuple(DATE_FIELD_LABELS)
# Full set of editable account fields compared by the optimistic-lock check
# (COACTUPC 9700) and the no-change short-circuit (COACTUPC 1205).
EDITABLE_ACCOUNT_FIELDS = (
    (STATUS_FIELD_NAME,)
    + MONEY_FIELD_NAMES
    + DATE_FIELD_NAMES
    + (GROUP_ID_FIELD_NAME,)
)


class AccountService:
    """Account view + update business logic (COACTVWC + COACTUPC).

    A thin, stateless orchestration object: it validates input with the ported
    field edits, drives the ``AccountRepository`` / ``CustomerRepository`` /
    ``CardXrefRepository`` for data access, enforces the READ-for-UPDATE ->
    REWRITE optimistic-lock semantics (§0.7.4), and assembles the combined
    ``AccountDetail`` (account + masked customer) response.

    Unit-of-work ownership: this service OWNS the transaction boundary for
    updates. The repositories only ``flush`` (never commit); ``UpdateAccount``
    issues the single ``session.commit()`` and rolls back on failure.
    ``GetAccount`` is read-only and never commits.

    Concurrency note (§0.7.4): the ``Account`` ORM model has no
    ``version``/``updated_at`` column (Minimal Change Clause), so the lost-update
    guard is the pessimistic ``SELECT ... FOR UPDATE`` acquired by the locking
    reads. The exact COBOL 9700 before-image compare is additionally implemented
    in :meth:`_CheckBeforeImage` and runs whenever the client echoes the fetched
    before-image; the verified ``AccountUpdate`` DTO transmits only new values,
    so that path relies on the row lock. Both approaches are preserved.
    """

    def __init__(self) -> None:
        """Instantiate the collaborating repositories (no I/O, no session)."""
        self.accountRepository = AccountRepository()
        self.customerRepository = CustomerRepository()
        self.xrefRepository = CardXrefRepository()

    async def GetAccount(self, session: AsyncSession, acctId: str) -> AccountDetail:
        """Return the combined account + customer view (COACTVWC, tx CAVW).

        Reproduces the COACTVWC read flow exactly: validate the account filter,
        resolve the owning customer through the card cross-reference (the
        ``CXACAIX`` account alternate index), read the account master, then read
        the customer master. Each miss raises the verbatim legacy message. This
        is a read-only operation and never commits.

        Args:
            session: Active async unit-of-work session.
            acctId: The 11-digit account identifier (leading zeros significant).

        Returns:
            An :class:`AccountDetail` embedding the masked :class:`CustomerRead`.

        Raises:
            DomainValidationError: The account id is blank or not a non-zero
                11-digit number (COACTVWC 2210-EDIT-ACCOUNT).
            NotFoundError: No cross-reference, account, or customer row exists
                (COACTVWC 9200 / 9300 / 9400).
        """
        normalizedId = self._ValidateAcctId(acctId)
        custId = await self._ResolveCustomerId(session, normalizedId)
        accountRecord = await self.accountRepository.GetByAcctId(session, normalizedId)
        if accountRecord is None:
            raise NotFoundError(MSG_ACCT_NOT_FOUND)
        customerRecord = await self.customerRepository.GetByCustId(session, custId)
        if customerRecord is None:
            raise NotFoundError(MSG_CUST_NOT_FOUND)
        return self._BuildAccountDetail(accountRecord, customerRecord)

    async def UpdateAccount(
        self,
        session: AsyncSession,
        acctId: str,
        accountUpdate: AccountUpdate,
    ) -> AccountDetail:
        """Update an account under the COACTUPC optimistic-lock flow (tx CAUP).

        Reproduces COACTUPC 9600-WRITE-PROCESSING: validate inputs, lock the
        account (``SELECT ... FOR UPDATE``), resolve + lock the owning customer,
        run the before-image change check (9700) and the no-change short-circuit
        (1205), apply the new field values (money as exact ``Decimal``), then
        persist both records and commit as a single unit of work.

        Args:
            session: Active async unit-of-work session; this service commits it.
            acctId: The 11-digit account identifier to update.
            accountUpdate: Validated partial-update payload (new values only).

        Returns:
            The refreshed :class:`AccountDetail` (account + masked customer).

        Raises:
            DomainValidationError: Invalid account id / field, or no change
                detected relative to the current record.
            NotFoundError: The account, cross-reference, or customer is missing.
            ConflictError: A locking read or the persistence step failed.
            OptimisticLockError: The record changed under the client (9700).
        """
        normalizedId = self._ValidateAcctId(acctId)
        self._ValidateUpdateFields(accountUpdate)
        accountRecord = await self._LockAccount(session, normalizedId)
        custId = await self._ResolveCustomerId(session, normalizedId)
        customerRecord = await self._LockCustomer(session, custId)
        currentImage = self._AccountImage(accountRecord)
        self._CheckBeforeImage(currentImage, self._ExtractBeforeImage(accountUpdate))
        self._EnsureChanged(currentImage, self._SubmittedImage(accountUpdate))
        self._ApplyAccountFields(accountRecord, accountUpdate)
        return await self._PersistUpdate(session, accountRecord, customerRecord)

    # -----------------------------------------------------------------------
    # Input validation helpers (COACTVWC 2210-EDIT-ACCOUNT + COACTUPC edits).
    # -----------------------------------------------------------------------
    def _ValidateAcctId(self, acctId: str) -> str:
        """Validate the account filter and return the normalized 11-digit id.

        Reproduces COACTVWC 2210-EDIT-ACCOUNT: a blank filter is "not provided";
        a value that is not an 11-digit number, or is all zeroes, fails the
        non-zero-11-digit edit. Uses ``validators.ValidateNumericId`` to detect
        validity but raises the verbatim COBOL message on failure.

        Args:
            acctId: The raw account identifier from the request path.

        Returns:
            The whitespace-stripped, validated 11-digit account id.

        Raises:
            DomainValidationError: The id is blank, non-numeric, wrong length,
                or all zeroes.
        """
        if acctId is None or not str(acctId).strip():
            raise DomainValidationError(MSG_ACCT_NOT_PROVIDED)
        normalizedId = str(acctId).strip()
        idCheck = validators.ValidateNumericId(
            ACCT_ID_FIELD_LABEL, normalizedId, validators.ACCT_ID_LENGTH
        )
        if not idCheck.isValid or self._IsAllZeros(normalizedId):
            raise DomainValidationError(MSG_ACCT_NON_ZERO_11)
        return normalizedId

    @staticmethod
    def _IsAllZeros(numericText: str) -> bool:
        """Return True when every character of ``numericText`` is ``'0'``."""
        return set(numericText) == {"0"}

    def _ValidateUpdateFields(self, accountUpdate: AccountUpdate) -> None:
        """Re-run the COACTUPC field edits on every supplied editable field.

        The schema already coerces/validates on construction; this reproduces
        the PROCEDURE DIVISION edits at the service boundary so an invalid value
        surfaces as a :class:`DomainValidationError` carrying the validator's
        message. Only fields the client explicitly set (and set to a non-null
        value) are checked, matching the partial-update contract.

        Args:
            accountUpdate: The partial-update payload to validate.

        Raises:
            DomainValidationError: A supplied field fails its ported edit.
        """
        updatedFields = accountUpdate.model_fields_set
        if STATUS_FIELD_NAME in updatedFields and accountUpdate.active_status is not None:
            self._RequireValid(
                validators.ValidateYesNo(ACCT_STATUS_FIELD_LABEL, accountUpdate.active_status)
            )
        for moneyField, fieldLabel in MONEY_FIELD_LABELS.items():
            moneyValue = getattr(accountUpdate, moneyField)
            if moneyField in updatedFields and moneyValue is not None:
                self._RequireValid(validators.ValidateSignedNumber(fieldLabel, moneyValue))
        for dateField in DATE_FIELD_NAMES:
            dateValue = getattr(accountUpdate, dateField)
            if dateField in updatedFields and dateValue is not None:
                self._RequireValid(date_utils.ValidateDate(dateValue.isoformat()))

    @staticmethod
    def _RequireValid(
        result: validators.ValidationResult | date_utils.DateValidationResult,
    ) -> None:
        """Raise :class:`DomainValidationError` when a validation result failed.

        Args:
            result: A ``ValidationResult`` or ``DateValidationResult`` (both
                expose ``isValid`` and ``message``).

        Raises:
            DomainValidationError: When ``result.isValid`` is False.
        """
        if not result.isValid:
            raise DomainValidationError(result.message)

    # -----------------------------------------------------------------------
    # Cross-reference + record-locking helpers (COACTVWC 9200 / COACTUPC 9600).
    # -----------------------------------------------------------------------
    async def _ResolveCustomerId(self, session: AsyncSession, acctId: str) -> str:
        """Resolve the owning customer id via the card cross-reference.

        Modern equivalent of the ``CXACAIX`` account alternate-index read in
        COACTVWC 9200-GETCARDXREF-BYACCT: the account is joined to its customer
        through the card cross-reference.

        Args:
            session: Active async session.
            acctId: The 11-digit account identifier.

        Returns:
            The 9-digit customer id from the first cross-reference row.

        Raises:
            NotFoundError: No cross-reference row exists for the account.
        """
        xrefRows = await self.xrefRepository.ListByAcctId(session, acctId)
        if not xrefRows:
            raise NotFoundError(MSG_XREF_NOT_FOUND)
        return xrefRows[0].cust_id

    async def _LockAccount(self, session: AsyncSession, acctId: str) -> Account:
        """Acquire the account row lock (COACTUPC 9600 READ ... UPDATE).

        Distinguishes a genuine lock failure (a specific ``SQLAlchemyError`` on
        the locking read -> ``ConflictError``) from a missing row
        (``None`` -> ``NotFoundError``), matching the legacy resp-code handling.

        Args:
            session: Active async session; the lock is held until commit.
            acctId: The account identifier to lock.

        Returns:
            The locked :class:`Account`.

        Raises:
            ConflictError: The locking read itself failed.
            NotFoundError: No account row has that id.
        """
        try:
            accountRecord = await self.accountRepository.GetForUpdate(session, acctId)
        except SQLAlchemyError as exc:
            raise ConflictError(MSG_LOCK_ACCT_FAILED) from exc
        if accountRecord is None:
            raise NotFoundError(MSG_ACCT_NOT_FOUND)
        return accountRecord

    async def _LockCustomer(self, session: AsyncSession, custId: str) -> Customer:
        """Acquire the customer row lock (COACTUPC 9600 READ ... UPDATE).

        Args:
            session: Active async session; the lock is held until commit.
            custId: The customer identifier to lock.

        Returns:
            The locked :class:`Customer`.

        Raises:
            ConflictError: The locking read itself failed.
            NotFoundError: No customer row has that id.
        """
        try:
            customerRecord = await self.customerRepository.GetForUpdate(session, custId)
        except SQLAlchemyError as exc:
            raise ConflictError(MSG_LOCK_CUST_FAILED) from exc
        if customerRecord is None:
            raise NotFoundError(MSG_CUST_NOT_FOUND)
        return customerRecord

    # -----------------------------------------------------------------------
    # Optimistic-lock + no-change helpers (COACTUPC 9700 + 1205).
    # -----------------------------------------------------------------------
    @staticmethod
    def _AccountImage(record: Account) -> dict:
        """Snapshot the editable account fields of ``record`` into a dict."""
        return {fieldName: getattr(record, fieldName) for fieldName in EDITABLE_ACCOUNT_FIELDS}

    @staticmethod
    def _SubmittedImage(accountUpdate: AccountUpdate) -> dict:
        """Snapshot only the editable fields the client explicitly submitted."""
        updatedFields = accountUpdate.model_fields_set
        return {
            fieldName: getattr(accountUpdate, fieldName)
            for fieldName in EDITABLE_ACCOUNT_FIELDS
            if fieldName in updatedFields
        }

    @staticmethod
    def _ExtractBeforeImage(accountUpdate: AccountUpdate) -> dict | None:
        """Return the client-echoed before-image, or ``None`` when unavailable.

        Forward-compatible with a future ``AccountUpdate`` that echoes the values
        the client fetched (the COBOL ACUP-OLD before-image). The verified DTO
        (``extra='forbid'``) carries only new values, so this returns ``None``
        and the ``SELECT ... FOR UPDATE`` lock provides the §0.7.4 serialization
        (see :meth:`_CheckBeforeImage`). When a before-image IS supplied, the
        exact COBOL 9700 compare runs against it.

        Args:
            accountUpdate: The partial-update payload.

        Returns:
            A before-image mapping of editable fields, or ``None``.
        """
        beforeImageSource = getattr(accountUpdate, "before_image", None)
        if beforeImageSource is None:
            return None
        return {
            fieldName: getattr(beforeImageSource, fieldName, None)
            for fieldName in EDITABLE_ACCOUNT_FIELDS
        }

    @staticmethod
    def _CheckBeforeImage(currentImage: dict, beforeImage: dict | None) -> None:
        """Reject the update when the record changed under the client (9700).

        Reproduces COACTUPC 9700-CHECK-CHANGE-IN-REC: if the freshly-locked DB
        record differs from the before-image the client fetched, someone else
        changed it in the interim, so the update is rejected instead of silently
        overwriting the concurrent change. Two faithful strategies coexist
        (AAP §0.7.4 guidance): (1) this before-image compare, used whenever the
        client echoes the fetched values; and (2) the ``SELECT ... FOR UPDATE``
        serialization always in force via the locking reads, used when no
        before-image is available (the verified DTO + version-less model path).

        Args:
            currentImage: Editable-field snapshot of the freshly-locked record.
            beforeImage: The before-image the client fetched, or ``None``.

        Raises:
            OptimisticLockError: A field differs from the before-image.
        """
        if beforeImage is None:
            return
        for fieldName in EDITABLE_ACCOUNT_FIELDS:
            if fieldName in beforeImage and beforeImage[fieldName] != currentImage[fieldName]:
                raise OptimisticLockError(MSG_RECORD_CHANGED)

    @staticmethod
    def _EnsureChanged(currentImage: dict, submittedImage: dict) -> None:
        """Short-circuit a no-op update (COACTUPC 1205-COMPARE-OLD-NEW).

        When the submitted values match the current record (nothing to change),
        surface the verbatim "No change detected ..." message rather than issue
        a no-op REWRITE. Compared new-vs-current because the DTO transmits only
        new values (AAP §0.7.4 guidance for the version-less model).

        Args:
            currentImage: Editable-field snapshot of the current record.
            submittedImage: The editable fields the client submitted.

        Raises:
            DomainValidationError: Nothing was submitted, or every submitted
                value already equals the current record.
        """
        if not submittedImage:
            raise DomainValidationError(MSG_NO_CHANGE_DETECTED)
        hasChange = any(
            submittedImage[fieldName] != currentImage[fieldName]
            for fieldName in submittedImage
        )
        if not hasChange:
            raise DomainValidationError(MSG_NO_CHANGE_DETECTED)

    # -----------------------------------------------------------------------
    # Apply + persist helpers (COACTUPC 9600 field moves + REWRITE + commit).
    # -----------------------------------------------------------------------
    @staticmethod
    def _ApplyAccountFields(accountRecord: Account, accountUpdate: AccountUpdate) -> None:
        """Copy the submitted new values onto the locked account record.

        Money fields pass through ``decimal_utils.ToDecimal`` to guarantee an
        exact ``Decimal`` (a ``float`` is rejected outright); status, date, and
        group-id fields are assigned directly (already validated/typed by the
        schema). Only fields the client explicitly set are touched, and null
        money values are skipped (a balance/limit cannot be cleared to NULL).

        Args:
            accountRecord: The locked, session-attached account to mutate.
            accountUpdate: The validated partial-update payload.
        """
        updatedFields = accountUpdate.model_fields_set
        for moneyField in MONEY_FIELD_NAMES:
            moneyValue = getattr(accountUpdate, moneyField)
            if moneyField in updatedFields and moneyValue is not None:
                setattr(accountRecord, moneyField, decimal_utils.ToDecimal(moneyValue))
        for plainField in (STATUS_FIELD_NAME, *DATE_FIELD_NAMES, GROUP_ID_FIELD_NAME):
            if plainField in updatedFields:
                setattr(accountRecord, plainField, getattr(accountUpdate, plainField))

    async def _PersistUpdate(
        self,
        session: AsyncSession,
        accountRecord: Account,
        customerRecord: Customer,
    ) -> AccountDetail:
        """Flush both records, build the response, then commit (COACTUPC REWRITE).

        The repositories only flush; this service owns the commit. The response
        is assembled from the in-memory, mutated records BEFORE ``commit`` so
        that attribute access never triggers a post-commit async lazy refresh
        (``expire_on_commit`` would otherwise require an await and raise
        ``MissingGreenlet``). A specific ``SQLAlchemyError`` rolls back and maps
        to the legacy LOCKED-BUT-UPDATE-FAILED path.

        Args:
            session: The owning async unit-of-work session.
            accountRecord: The mutated, locked account.
            customerRecord: The locked customer (re-persisted for parity).

        Returns:
            The refreshed :class:`AccountDetail`.

        Raises:
            ConflictError: Persisting the update failed (rolled back).
        """
        try:
            await self.accountRepository.Update(session, accountRecord)
            await self.customerRepository.Update(session, customerRecord)
            accountDetail = self._BuildAccountDetail(accountRecord, customerRecord)
            await session.commit()
        except SQLAlchemyError as exc:
            await session.rollback()
            raise ConflictError(MSG_UPDATE_FAILED) from exc
        return accountDetail

    @staticmethod
    def _BuildAccountDetail(accountRecord: Account, customerRecord: Customer) -> AccountDetail:
        """Assemble the combined account + masked-customer view payload.

        Builds :class:`AccountDetail` explicitly (its ``customer`` field is
        required, so a single ``model_validate`` on the account alone would
        fail): the account is projected to :class:`AccountRead`, the customer to
        the SSN-masking :class:`CustomerRead`, and the two are combined. Reading
        happens from already-loaded in-memory attributes, so no lazy DB load is
        triggered.

        Args:
            accountRecord: The account ORM row.
            customerRecord: The owning customer ORM row.

        Returns:
            The combined :class:`AccountDetail`.
        """
        accountRead = AccountRead.model_validate(accountRecord)
        customerRead = CustomerRead.model_validate(customerRecord)
        return AccountDetail(**accountRead.model_dump(), customer=customerRead)

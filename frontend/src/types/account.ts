/**
 * :module: ``frontend/src/types/account.ts``
 * :purpose: Request / response DTO interfaces for the CardDemo account screens —
 *   ``AccountViewPage`` (read-only account + customer detail; BMS mapset
 *   ``COACTVW``, program ``COACTVWC``, CICS transaction ``CAVW``,
 *   ``GET /accounts/{id}``) and ``AccountUpdatePage`` (optimistic-lock update;
 *   BMS mapset ``COACTUP``, program ``COACTUPC``, CICS transaction ``CAUP``,
 *   ``PUT /accounts/{id}``).
 * :output: The :ts:type:`AccountViewResponseDto`, :ts:type:`AccountUpdateRequestDto`
 *   and :ts:type:`AccountUpdateResponseDto` wire contracts, plus the optional
 *   page helpers :ts:type:`AccountUpdateFormParts` and
 *   :ts:type:`OptimisticLockConflict`.
 * :note: Member names mirror the frozen backend JSON contracts
 *   ``com.carddemo.common.dto.AccountViewResponseDto`` /
 *   ``AccountUpdateRequestDto`` / ``AccountUpdateResponseDto`` (camelCase) exactly,
 *   so REST payloads bind without field remapping. Monetary and identifier fields
 *   are typed ``string`` to preserve ``NUMERIC(p,s)`` scale and zero-padded field
 *   width on the wire, as reflected by the symbolic maps ``COACTVW.CPY`` and
 *   ``COACTUP.CPY``.
 * :note: The identifier ``acctExpiraionDate`` retains the legacy copybook
 *   misspelling (missing the second ``T``, from COBOL ``ACCT-EXPIRAION-DATE``)
 *   verbatim as a frozen contract; it must NOT be "corrected" by inserting the
 *   missing ``T``.
 * :note: Type-only import of ``ActiveStatus``; the module declares no runtime
 *   values and has no side effects, so it is safe to import from Jest (jsdom).
 */

import type { ActiveStatus } from './common';

/**
 * :purpose: The account and customer attributes exchanged on BOTH the account
 *   update request and the account view / update responses — the editable field
 *   set of the ``COACTUP`` maintenance screen, excluding the server-managed
 *   identity fields (``acctId``, ``custId``) and the read-only account address ZIP
 *   (``acctAddrZip``) that the responses add on top.
 * :note: Internal composition base (not part of the public API) declared once so
 *   the misspelled ``acctExpiraionDate`` and every shared member appear exactly
 *   once; the exported DTOs below re-expose every member through inheritance.
 */
interface AccountMutableFields {
  /** ``ACCT-ACTIVE-STATUS`` X(01) — ``'Y'`` active / ``'N'`` inactive (shared ``ActiveStatus`` alias). */
  acctActiveStatus: ActiveStatus;
  /** ``ACCT-CURR-BAL`` NUMERIC(12,2) — current balance; ``string`` preserves scale. */
  acctCurrBal: string;
  /** ``ACCT-CREDIT-LIMIT`` NUMERIC(12,2) — credit limit; ``string`` preserves scale. */
  acctCreditLimit: string;
  /** ``ACCT-CASH-CREDIT-LIMIT`` NUMERIC(12,2) — cash-credit limit; ``string`` preserves scale. */
  acctCashCreditLimit: string;
  /** ``ACCT-OPEN-DATE`` X(10) — account open date in ``YYYY-MM-DD`` wire form. */
  acctOpenDate: string;
  /**
   * ``ACCT-EXPIRAION-DATE`` X(10) — account expiration date in ``YYYY-MM-DD``.
   *
   * :note: The misspelling (missing the second ``T``) is intentional and mirrors
   *   the legacy frozen contract; do not rename it by inserting the missing ``T``.
   */
  acctExpiraionDate: string;
  /** ``ACCT-REISSUE-DATE`` X(10) — reissue date in ``YYYY-MM-DD`` wire form. */
  acctReissueDate: string;
  /** ``ACCT-CURR-CYC-CREDIT`` NUMERIC(12,2) — current-cycle credit total; ``string`` preserves scale. */
  acctCurrCycCredit: string;
  /** ``ACCT-CURR-CYC-DEBIT`` NUMERIC(12,2) — current-cycle debit total; ``string`` preserves scale. */
  acctCurrCycDebit: string;
  /** ``ACCT-GROUP-ID`` X(10) — disclosure / pricing group identifier. */
  acctGroupId: string;
  /** ``CUST-FIRST-NAME`` X(25). */
  custFirstName: string;
  /** ``CUST-MIDDLE-NAME`` X(25). */
  custMiddleName: string;
  /** ``CUST-LAST-NAME`` X(25). */
  custLastName: string;
  /** ``CUST-ADDR-LINE-1`` X(50). */
  custAddrLine1: string;
  /** ``CUST-ADDR-LINE-2`` X(50). */
  custAddrLine2: string;
  /** ``CUST-ADDR-LINE-3`` X(50) — third address line (city). */
  custAddrLine3: string;
  /** ``CUST-ADDR-STATE-CD`` X(02) — two-character state code. */
  custAddrStateCd: string;
  /** ``CUST-ADDR-COUNTRY-CD`` X(03) — three-character country code. */
  custAddrCountryCd: string;
  /** ``CUST-ADDR-ZIP`` X(05) — customer postal code (distinct from account ``acctAddrZip``). */
  custAddrZip: string;
  /** ``CUST-PHONE-NUM-1`` X(13). */
  custPhoneNum1: string;
  /** ``CUST-PHONE-NUM-2`` X(13). */
  custPhoneNum2: string;
  /** ``CUST-SSN`` — Social Security number; sensitive, masked / encrypted server-side. */
  custSsn: string;
  /** ``CUST-GOVT-ISSUED-ID`` X(20) — government-issued id; sensitive, masked server-side. */
  custGovtIssuedId: string;
  /** ``CUST-DOB-YYYY-MM-DD`` X(10) — date of birth in ``YYYY-MM-DD`` wire form. */
  custDobYyyyMmDd: string;
  /** ``CUST-EFT-ACCOUNT-ID`` X(10) — electronic-funds-transfer account id. */
  custEftAccountId: string;
  /** ``CUST-PRI-CARD-HOLDER-IND`` X(01) — single-character primary-card-holder flag. */
  custPriCardHolderInd: string;
  /** ``CUST-FICO-CREDIT-SCORE`` 9(03) — numeric FICO credit score. */
  custFicoCreditScore: number;
}

/**
 * :purpose: Request body for ``PUT /accounts/{id}`` (``AccountUpdatePage``, CICS
 *   transaction ``CAUP``, program ``COACTUPC``, BMS mapset ``COACTUP``). Carries
 *   the editable account + customer fields the client echoes back after editing,
 *   together with the optimistic-lock ``version`` read at display time.
 * :output: The account / customer attributes to persist together in one
 *   ``@Transactional`` unit.
 * :note: ``acctId`` is not part of the body — it identifies the target through the
 *   request path; ``custId`` is derived server-side. The misspelled
 *   ``acctExpiraionDate`` is preserved verbatim.
 * :param version: the account optimistic-lock version snapshot the client read
 *   when the screen was displayed. The server compares it against the current
 *   record; a mismatch raises ``OptimisticLockException``, surfaced as the
 *   ``ApiErrorResponse`` message "Record changed by some one else. Please review"
 *   (see :ts:type:`OptimisticLockConflict`), reproducing the legacy
 *   read-snapshot-compare-rewrite concurrency check.
 */
export interface AccountUpdateRequestDto extends AccountMutableFields {
  version: number;
}

/**
 * :purpose: Response body for ``GET /accounts/{id}`` (``AccountViewPage``, CICS
 *   transaction ``CAVW``, program ``COACTVWC``, BMS mapset ``COACTVW``) — the
 *   joined, read-only account + customer detail.
 * :output: Every editable attribute (:ts:type:`AccountMutableFields`) plus the
 *   server-managed identity fields and the optimistic-lock version.
 * :param acctId: ``ACCT-ID`` 9(11) — 11-digit account identifier; ``string``
 *   preserves the zero-padded field width.
 * :param acctAddrZip: ``ACCT-ADDR-ZIP`` X(10) — account address ZIP (read-only on
 *   the account screens; distinct from the customer ``custAddrZip``).
 * :param custId: ``CUST-ID`` 9(09) — 9-digit owning-customer identifier;
 *   ``string`` preserves the zero-padded field width.
 * :param version: the current optimistic-lock version; the client echoes it back
 *   on ``PUT`` to detect concurrent modification.
 */
export interface AccountViewResponseDto extends AccountMutableFields {
  acctId: string;
  acctAddrZip: string;
  custId: string;
  version: number;
}

/**
 * :purpose: Response body for a successful ``PUT /accounts/{id}`` — the refreshed
 *   account record after the update commits.
 * :output: The same shape as :ts:type:`AccountViewResponseDto`, carrying the
 *   incremented optimistic-lock ``version`` so the client may issue a subsequent
 *   update without re-reading.
 * :note: Modeled as an extension of the view response because the account-service
 *   controller returns the freshly-read record; declared as a distinct named type
 *   so callers can express update-result intent.
 */
export interface AccountUpdateResponseDto extends AccountViewResponseDto {}

/* ------------------------------------------------------------------------- *
 * Optional page-level helpers (NOT REST wire contracts)                     *
 * ------------------------------------------------------------------------- */

/**
 * :purpose: Optional scaffolding for the ``AccountUpdatePage`` form, capturing the
 *   DECOMPOSED entry fields of the BMS mapset ``COACTUP`` before the page
 *   recomposes them into the consolidated wire fields of
 *   :ts:type:`AccountUpdateRequestDto`.
 * :output: Individual date components, Social-Security-number segments and
 *   telephone segments as edited on screen.
 * :note: Not a REST contract — the page assembles ``acctOpenDate``,
 *   ``acctExpiraionDate``, ``acctReissueDate`` and ``custDobYyyyMmDd`` from the
 *   Year / Mon / Day parts, ``custSsn`` from the three SSN segments, and
 *   ``custPhoneNum1`` / ``custPhoneNum2`` from the three phone segments each,
 *   prior to submission.
 */
export interface AccountUpdateFormParts {
  /** ``OPNYEAR`` X(04) — account open-date year component. */
  opnYear: string;
  /** ``OPNMON`` X(02) — account open-date month component. */
  opnMon: string;
  /** ``OPNDAY`` X(02) — account open-date day component. */
  opnDay: string;
  /** ``EXPYEAR`` X(04) — account expiration-date year component. */
  expYear: string;
  /** ``EXPMON`` X(02) — account expiration-date month component. */
  expMon: string;
  /** ``EXPDAY`` X(02) — account expiration-date day component. */
  expDay: string;
  /** ``RISYEAR`` X(04) — account reissue-date year component. */
  risYear: string;
  /** ``RISMON`` X(02) — account reissue-date month component. */
  risMon: string;
  /** ``RISDAY`` X(02) — account reissue-date day component. */
  risDay: string;
  /** ``DOBYEAR`` X(04) — customer date-of-birth year component. */
  dobYear: string;
  /** ``DOBMON`` X(02) — customer date-of-birth month component. */
  dobMon: string;
  /** ``DOBDAY`` X(02) — customer date-of-birth day component. */
  dobDay: string;
  /** ``ACTSSN1`` X(03) — SSN area segment. */
  ssnArea: string;
  /** ``ACTSSN2`` X(02) — SSN group segment. */
  ssnGroup: string;
  /** ``ACTSSN3`` X(04) — SSN serial segment. */
  ssnSerial: string;
  /** ``ACSPH1A`` — phone-1 area-code segment. */
  phone1Area: string;
  /** ``ACSPH1B`` — phone-1 prefix segment. */
  phone1Prefix: string;
  /** ``ACSPH1C`` — phone-1 line-number segment. */
  phone1Line: string;
  /** ``ACSPH2A`` — phone-2 area-code segment. */
  phone2Area: string;
  /** ``ACSPH2B`` — phone-2 prefix segment. */
  phone2Prefix: string;
  /** ``ACSPH2C`` — phone-2 line-number segment. */
  phone2Line: string;
}

/**
 * :purpose: Optional thin projection of the optimistic-lock conflict surfaced by
 *   ``PUT /accounts/{id}`` when the account ``version`` no longer matches.
 * :output: The single human-readable conflict message the page renders.
 * :note: The backend reports the conflict through the standard ``ApiErrorResponse``
 *   (see ``./common``) carrying the verbatim legacy message "Record changed by
 *   some one else. Please review"; this alias narrows that to the message alone
 *   for pages that only need to display it.
 * :param message: the conflict message to display.
 */
export interface OptimisticLockConflict {
  message: string;
}

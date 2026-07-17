/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.type.Money;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.service.rule.UsSsnRule;
import com.aws.carddemo.service.rule.ValidationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Account inquiry and update service &mdash; the Java re-platform of two CardDemo
 * COBOL online programs, preserving 100% of their observable behavior with no feature
 * expansion (AAP &sect;0.5.3):
 *
 * <ul>
 *   <li><b>{@code COACTVWC}</b> (Account View, transaction {@code CAVW}, source
 *       {@code legacy/cbl/COACTVWC.cbl}) &mdash; reproduced by {@link #viewAccount(long)}.</li>
 *   <li><b>{@code COACTUPC}</b> (Account Update, transaction {@code CAUP}, source
 *       {@code legacy/cbl/COACTUPC.cbl}, the largest program with the most extensive edit
 *       rules) &mdash; reproduced by
 *       {@link #updateAccount(AccountUpdateCommand, boolean)}.</li>
 * </ul>
 *
 * <h2>Why this is the highest parity-risk service</h2>
 * This service concentrates three of the migration's high-risk hotspots (AAP &sect;0.7.1):
 * <ul>
 *   <li><b>H1 &mdash; pseudo-conversational state.</b> The CICS COMMAREA +
 *       {@code XCTL} + {@code RETURN TRANSID} model of {@code COACTUPC} is translated to
 *       an explicit, stateless request/response flow. The COBOL {@code ACUP-CHANGE-ACTION}
 *       flow flag and the {@code CDEMO-PGM-CONTEXT} first-entry/re-entry toggle
 *       ({@code COCOM01Y}) are modeled explicitly as the {@code priorStatus} carried in
 *       {@link AccountUpdateCommand} and the {@code reentry} method parameter, so the
 *       first-time-versus-re-entry initialization is preserved exactly.</li>
 *   <li><b>H3 &mdash; monetary fidelity.</b> Every monetary value is a
 *       {@link java.math.BigDecimal} at scale {@code 2} (via {@link Money}); floating point
 *       is never used for money.</li>
 *   <li><b>H6 &mdash; read-update-rewrite concurrency.</b> The COBOL
 *       READ-for-UPDATE / {@code 9700-CHECK-CHANGE-IN-REC} / REWRITE cycle is reproduced
 *       with JPA optimistic locking: the {@link Account} entity carries a {@code @Version}
 *       column and the version observed at inquiry time is carried back in the update
 *       command. A concurrent modification surfaces as an
 *       {@link OptimisticLockingFailureException} (rendered HTTP&nbsp;409). This is the
 *       documented, intentional integrity improvement recorded in the decision log &mdash;
 *       it replaces the COBOL manual field-by-field re-comparison.</li>
 * </ul>
 *
 * <h2>Error surfacing (matching the pseudo-conversational contract)</h2>
 * Following the design note in the file specification and the COBOL behavior:
 * <ul>
 *   <li><b>Validation failures</b> (the COBOL {@code INPUT-ERROR} paths that set a screen
 *       message and re-display) are returned in {@link AccountUpdateResult} so the caller
 *       can re-display, exactly as the terminal re-showed the map.</li>
 *   <li><b>Missing records / file errors</b> (the COBOL {@code NOTFND} paths) throw the
 *       typed {@link RecordNotFoundException} (rendered HTTP&nbsp;404).</li>
 *   <li><b>Concurrent modification</b> lets {@link OptimisticLockingFailureException}
 *       propagate (rendered HTTP&nbsp;409).</li>
 * </ul>
 *
 * <h2>Self-contained field editing</h2>
 * The COBOL edit paragraphs ({@code 1210}&ndash;{@code 1280}) are reproduced here as private
 * methods rather than as injected {@code service/rule/} strategy beans, so that this service
 * depends only on its declared, verified collaborators. The exact caller-visible error
 * strings, the exact evaluation order, and the COBOL "first message wins" latching
 * (the {@code WS-RETURN-MSG-OFF} gate) are all preserved (AAP &sect;0.9.2). Date validity is
 * delegated to {@link DateValidationService} (never to {@code CSUTLDTC} directly).
 *
 * <h2>Sensitive data</h2>
 * The customer SSN, government-issued id, and date of birth are sensitive: they are never
 * logged and never placed in exception messages. Only the non-sensitive account id and
 * customer id appear in diagnostic messages.
 *
 * <p>All public entry points are transactional. This class is stateless and therefore
 * thread-safe; the pseudo-conversational flow state is carried by the caller between
 * requests.</p>
 */
@Service
public class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    // ------------------------------------------------------------------------------------
    // Caller-visible message literals (reproduced byte-for-byte from the COBOL sources).
    // ------------------------------------------------------------------------------------

    /**
     * Account-filter edit failure for the View screen. Exact literal from
     * {@code legacy/cbl/COACTVWC.cbl:L672} ({@code 2210-EDIT-ACCOUNT}). Note the two spaces
     * between "must" and "be" &mdash; this is significant and is preserved verbatim for
     * behavioral parity (AAP &sect;0.9.2).
     */
    static final String MSG_ACCT_FILTER_INVALID =
            "Account Filter must  be a non-zero 11 digit number";

    /**
     * {@code COACTUPC 1210-EDIT-ACCOUNT} blank-account prompt ({@code WS-PROMPT-FOR-ACCT},
     * {@code legacy/cbl/COACTUPC.cbl}).
     */
    static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

    /** {@code COACTUPC 1210-EDIT-ACCOUNT} non-numeric / zero account literal. */
    static final String MSG_ACCT_NON_ZERO_11 =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** {@code COACTUPC} {@code NO-CHANGES-DETECTED} message. */
    static final String MSG_NO_CHANGES =
            "No change detected with respect to values fetched.";

    /** {@code COACTUPC} {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} message. */
    static final String MSG_COULD_NOT_LOCK_ACCT =
            "Could not lock account record for update";

    /** {@code COACTUPC} {@code DATA-WAS-CHANGED-BEFORE-UPDATE} message. */
    static final String MSG_DATA_CHANGED =
            "Record changed by some one else. Please review";

    /** {@code COACTUPC} {@code LOCKED-BUT-UPDATE-FAILED} message. */
    static final String MSG_UPDATE_FAILED = "Update of record failed";

    // Informational (prompt) messages driven by 3250-SETUP-INFOMSG (WS-INFO-MSG 88-levels).
    // These are the caller-visible flow prompts returned alongside each non-error DECIDE-ACTION
    // transition; the exact literals are preserved for behavioral parity (AAP §0.9.2).

    /** {@code PROMPT-FOR-SEARCH-KEYS} — shown when awaiting an account id to fetch. */
    static final String MSG_PROMPT_SEARCH_KEYS = "Enter or update id of account to update";

    /** {@code PROMPT-FOR-CHANGES} — shown for {@code SHOW-DETAILS} / {@code CHANGES-NOT-OK}. */
    static final String MSG_PROMPT_CHANGES = "Update account details presented above.";

    /** {@code PROMPT-FOR-CONFIRMATION} — shown for {@code CHANGES-OK-NOT-CONFIRMED}. */
    static final String MSG_PROMPT_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code CONFIRM-UPDATE-SUCCESS} — shown for {@code CHANGES-OKAYED-AND-DONE}. */
    static final String MSG_CONFIRM_SUCCESS = "Changes committed to database";

    /** {@code INFORM-FAILURE} — shown for the lock-error / update-failed outcomes. */
    static final String MSG_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    // Reusable message suffixes appended to the (trimmed) field name, matching the COBOL
    // STRING TRIM(WS-EDIT-VARIABLE-NAME) <literal> constructs.
    private static final String SFX_MUST_BE_SUPPLIED = " must be supplied.";
    private static final String SFX_MUST_BE_Y_OR_N = " must be Y or N.";
    private static final String SFX_ALPHABETS_ONLY = " can have alphabets only.";
    private static final String SFX_ALL_NUMERIC = " must be all numeric.";
    private static final String SFX_MUST_NOT_BE_ZERO = " must not be zero.";
    private static final String SFX_IS_NOT_VALID = " is not valid";

    private static final String SFX_STATE_INVALID = ": is not a valid state code";
    private static final String SFX_FICO_RANGE = ": should be between 300 and 850";
    private static final String MSG_ZIP_FOR_STATE_INVALID = "Invalid zip code for state";
    private static final String SFX_DOB_FUTURE = ":cannot be in the future ";

    // Phone edit suffixes (COBOL 1260-EDIT-US-PHONE-NUM and its sub-paragraphs).
    private static final String SFX_AREA_SUPPLIED = ": Area code must be supplied.";
    private static final String SFX_AREA_3_DIGIT = ": Area code must be A 3 digit number.";
    private static final String SFX_AREA_ZERO = ": Area code cannot be zero";
    private static final String SFX_AREA_NANPA =
            ": Not valid North America general purpose area code";
    private static final String SFX_PREFIX_SUPPLIED = ": Prefix code must be supplied.";
    private static final String SFX_PREFIX_3_DIGIT = ": Prefix code must be A 3 digit number.";
    private static final String SFX_PREFIX_ZERO = ": Prefix code cannot be zero";
    private static final String SFX_LINE_SUPPLIED = ": Line number code must be supplied.";
    private static final String SFX_LINE_4_DIGIT = ": Line number code must be A 4 digit number.";
    private static final String SFX_LINE_ZERO = ": Line number code cannot be zero";

    // Date edit suffixes (copybook CSUTLDPY.cpy EDIT-DATE-CCYYMMDD and its sub-paragraphs).
    private static final String SFX_YEAR_SUPPLIED = " : Year must be supplied.";
    private static final String SFX_YEAR_4_DIGIT = " must be 4 digit number.";
    private static final String SFX_CENTURY_INVALID = " : Century is not valid.";
    private static final String SFX_MONTH_SUPPLIED = " : Month must be supplied.";
    private static final String SFX_MONTH_RANGE = ": Month must be a number between 1 and 12.";
    private static final String SFX_DAY_SUPPLIED = " : Day must be supplied.";
    private static final String SFX_DAY_RANGE = ":day must be a number between 1 and 31.";
    private static final String SFX_DAY_31 = ":Cannot have 31 days in this month.";
    private static final String SFX_DAY_30 = ":Cannot have 30 days in this month.";
    private static final String SFX_DAY_29 =
            ":Not a leap year.Cannot have 29 days in this month.";

    // COBOL edit-variable names (WS-EDIT-VARIABLE-NAME) used verbatim to build messages.
    private static final String FLD_ACCOUNT_STATUS = "Account Status";
    private static final String FLD_OPEN_DATE = "Open Date";
    private static final String FLD_CREDIT_LIMIT = "Credit Limit";
    private static final String FLD_EXPIRY_DATE = "Expiry Date";
    private static final String FLD_CASH_CREDIT_LIMIT = "Cash Credit Limit";
    private static final String FLD_REISSUE_DATE = "Reissue Date";
    private static final String FLD_CURRENT_BALANCE = "Current Balance";
    private static final String FLD_CURR_CYC_CREDIT = "Current Cycle Credit Limit";
    private static final String FLD_CURR_CYC_DEBIT = "Current Cycle Debit Limit";
    private static final String FLD_DOB = "Date of Birth";
    private static final String FLD_FICO = "FICO Score";
    private static final String FLD_FIRST_NAME = "First Name";
    private static final String FLD_MIDDLE_NAME = "Middle Name";
    private static final String FLD_LAST_NAME = "Last Name";
    private static final String FLD_ADDR_LINE_1 = "Address Line 1";
    private static final String FLD_STATE = "State";
    private static final String FLD_ZIP = "Zip";
    private static final String FLD_CITY = "City";
    private static final String FLD_COUNTRY = "Country";
    private static final String FLD_PHONE_1 = "Phone Number 1";
    private static final String FLD_PHONE_2 = "Phone Number 2";
    private static final String FLD_EFT_ACCOUNT_ID = "EFT Account Id";
    private static final String FLD_PRI_CARD_HOLDER = "Primary Card Holder";

    // ------------------------------------------------------------------------------------
    // Numeric / structural constants.
    // ------------------------------------------------------------------------------------

    /** The single date mask supported by {@link DateValidationService}. */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /** Maximum 11-digit account id (COBOL {@code PIC 9(11)}). */
    private static final long MAX_ACCT_ID = 99_999_999_999L;

    /** FICO score inclusive lower bound (COBOL {@code FICO-RANGE-IS-VALID}). */
    private static final int FICO_MIN = 300;

    /** FICO score inclusive upper bound (COBOL {@code FICO-RANGE-IS-VALID}). */
    private static final int FICO_MAX = 850;

    /** Zero-padded width for the account id in {@code NOTFND} messages (COBOL {@code X(11)}). */
    private static final int ACCT_ID_WIDTH = 11;

    /** Zero-padded width for the customer id in {@code NOTFND} messages (COBOL {@code X(9)}). */
    private static final int CUST_ID_WIDTH = 9;

    /**
     * The 56 valid USPS state / territory codes from {@code CSLKPCDY.cpy}
     * ({@code VALID-US-STATE-CODE}), used by {@code COACTUPC 1270-EDIT-US-STATE-CD}.
     */
    private static final Set<String> VALID_US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            "DC", "AS", "GU", "MP", "PR", "VI");

    // ------------------------------------------------------------------------------------
    // Collaborators (constructor injection only; no field injection, no Lombok).
    // ------------------------------------------------------------------------------------

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;
    private final DateValidationService dateValidationService;

    /**
     * The SSN edit rule (Java reproduction of COBOL {@code 1265-EDIT-US-SSN}), injected and reused
     * as the single Strategy implementation of the three-part SSN edit rather than being
     * re-implemented inline &mdash; matching {@code 1200-EDIT-MAP-INPUTS}, which delegates the SSN
     * field to this rule.
     */
    private final UsSsnRule usSsnRule;

    /**
     * Creates the service with its required collaborators.
     *
     * @param accountRepository     repository for the {@code account} table (VSAM
     *                              {@code ACCTDATA} KSDS)
     * @param customerRepository    repository for the {@code customer} table (VSAM
     *                              {@code CUSTDATA} KSDS)
     * @param cardXrefRepository    repository for the {@code card_xref} table (VSAM
     *                              {@code CARDXREF} KSDS; the account alternate-index browse
     *                              is reproduced by
     *                              {@link CardXrefRepository#findFirstByAcctIdOrderByXrefCardNumAsc(Long)})
     * @param dateValidationService the re-platform of {@code CSUTLDTC}, used for the calendar
     *                              validity of every entered date
     * @param usSsnRule             the SSN edit rule (Java reproduction of COBOL
     *                              {@code 1265-EDIT-US-SSN}); the {@code 1200-EDIT-MAP-INPUTS}
     *                              SSN field delegates to this shared rule component
     */
    public AccountService(AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          CardXrefRepository cardXrefRepository,
                          DateValidationService dateValidationService,
                          UsSsnRule usSsnRule) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.dateValidationService = dateValidationService;
        this.usSsnRule = usSsnRule;
    }

    // ====================================================================================
    // Public API types
    // ====================================================================================

    /**
     * Immutable result of an account inquiry &mdash; the merged {@link Account} and
     * {@link Customer} that {@code COACTVWC} presented on the {@code CACTVWA} map. This is a
     * small nested service result record (deliberately not a web DTO); the web layer maps it
     * to the {@code AccountViewResponse} contract.
     *
     * @param account  the account master record
     * @param customer the customer master record cross-referenced from the account
     */
    public record AccountDetail(Account account, Customer customer) {
    }

    /**
     * The pseudo-conversational outcome of a single {@link #updateAccount} invocation &mdash;
     * the Java analog of the COBOL {@code ACUP-CHANGE-ACTION} flow flag as evaluated by
     * {@code 2000-DECIDE-ACTION}. The web layer maps each value to the appropriate screen
     * message / HTTP status and carries it back as {@code priorStatus} on the next submit.
     */
    public enum Status {

        /**
         * Details have been fetched (or re-fetched) and are shown for review; also the
         * "no functional change detected" re-show. The COBOL {@code ACUP-SHOW-DETAILS} state.
         */
        SHOW_DETAILS,

        /**
         * Edits passed and a change was detected; the user is asked to confirm the save
         * (press the confirm key). The COBOL {@code ACUP-CHANGES-OK-NOT-CONFIRMED} state.
         */
        CHANGES_OK_NOT_CONFIRMED,

        /**
         * One or more field edits failed; the screen is re-shown with the first error
         * message. The COBOL {@code ACUP-CHANGES-NOT-OK} state.
         */
        CHANGES_NOT_OK,

        /**
         * The account record could not be locked for update. The COBOL
         * {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} outcome. Under optimistic locking this
         * pessimistic-lock-contention outcome has no direct analog; it is retained as part
         * of the documented contract (concurrency is surfaced via {@link Status#CONCURRENT_CHANGE}
         * / {@link OptimisticLockingFailureException} instead).
         */
        LOCK_ERROR,

        /**
         * The record was locked but the rewrite failed. The COBOL
         * {@code ACUP-CHANGES-OKAYED-BUT-FAILED} / {@code LOCKED-BUT-UPDATE-FAILED} outcome.
         */
        UPDATE_FAILED,

        /**
         * The record changed between inquiry and update (the COBOL
         * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} outcome detected by
         * {@code 9700-CHECK-CHANGE-IN-REC}). In the Java re-platform this is detected by JPA
         * optimistic locking and surfaced by letting {@link OptimisticLockingFailureException}
         * propagate (rendered HTTP&nbsp;409); this constant is the documented semantic name of
         * that outcome.
         */
        CONCURRENT_CHANGE,

        /** The update was confirmed and committed successfully (COBOL {@code ACUP-CHANGES-OKAYED-AND-DONE}). */
        DONE
    }

    /**
     * Immutable result of {@link #updateAccount(AccountUpdateCommand, boolean)}.
     *
     * @param status  the flow outcome (see {@link Status})
     * @param detail  the account/customer detail to (re-)display, or {@code null} when there
     *                is nothing to show (for example an account-filter error before any
     *                record was fetched)
     * @param message the single caller-visible message to display (the COBOL
     *                {@code WS-RETURN-MSG} / {@code CCARD-ERROR-MSG}), or {@code null}/empty
     *                when there is no message
     */
    public record AccountUpdateResult(Status status, AccountDetail detail, String message) {
    }

    /**
     * The three components of an entered date exactly as the 3270 map captured them
     * (separate year / month / day fields). Kept as raw strings so the COBOL
     * {@code EDIT-DATE-CCYYMMDD} component edits (blank, non-numeric, century, month, day,
     * calendar) can be reproduced faithfully. A {@code null} component is treated as blank.
     *
     * @param year  the four-digit year field ({@code CCYY})
     * @param month the two-digit month field ({@code MM})
     * @param day   the two-digit day field ({@code DD})
     */
    public record DateParts(String year, String month, String day) {
    }

    /**
     * The three components of a US phone number exactly as the map captured them, matching
     * the COBOL {@code WS-EDIT-US-PHONE-NUMA/B/C} sub-fields.
     *
     * @param area   the three-digit area code ({@code NUMA})
     * @param prefix the three-digit prefix / exchange ({@code NUMB})
     * @param line   the four-digit line number ({@code NUMC})
     */
    public record PhoneParts(String area, String prefix, String line) {
    }

    /**
     * The three components of a US Social Security Number exactly as the map captured them
     * (COBOL {@code ACUP-NEW-CUST-SSN-1/2/3}). <strong>Sensitive:</strong> never logged and
     * never placed in exception messages.
     *
     * @param part1 the first three digits (area)
     * @param part2 the middle two digits (group)
     * @param part3 the last four digits (serial)
     */
    public record SsnParts(String part1, String part2, String part3) {
    }

    /**
     * Service-layer input carrying the raw, submitted field values for an account update
     * (the {@code COACTUPC} {@code ACUP-NEW-*} fields as received from the map). This is a
     * nested service record, deliberately DTO-free (no dependency on {@code dto/} or
     * {@code mapper/}); the web layer maps its request DTO onto this command.
     *
     * <p>Monetary fields are carried as raw strings so the COBOL signed-decimal edit
     * ({@code 1250-EDIT-SIGNED-9V2}, {@code FUNCTION TEST-NUMVAL-C}) can be reproduced; they
     * are parsed to {@link java.math.BigDecimal} at scale 2 only after passing that edit.
     * Dates and phones are carried in component form for the same reason.</p>
     *
     * @param accountId         the raw account filter ({@code CC-ACCT-ID})
     * @param expectedVersion   the {@link Account} {@code @Version} value observed when the
     *                          details were fetched, carried back for optimistic-lock
     *                          concurrency detection; may be {@code null} to skip the
     *                          cross-request check (in-transaction {@code @Version} still applies)
     * @param accountStatus     the account active status (Y/N)
     * @param openDate          the account open date components
     * @param creditLimit       the raw credit-limit amount
     * @param expiryDate        the account expiry date components
     * @param cashCreditLimit   the raw cash-credit-limit amount
     * @param reissueDate       the account reissue date components
     * @param currentBalance    the raw current-balance amount
     * @param currentCycleCredit the raw current-cycle-credit amount
     * @param currentCycleDebit the raw current-cycle-debit amount
     * @param groupId           the account group id (disclosure group)
     * @param ssn               the customer SSN components (sensitive)
     * @param dob               the customer date-of-birth components (sensitive)
     * @param ficoScore         the raw FICO score
     * @param firstName         the customer first name
     * @param middleName        the customer middle name (optional)
     * @param lastName          the customer last name
     * @param addressLine1      the customer address line 1 (mandatory)
     * @param addressLine2      the customer address line 2 (optional, not edited)
     * @param city              the customer city (COBOL customer address line 3)
     * @param stateCode         the customer state code
     * @param zipCode           the customer ZIP code
     * @param countryCode       the customer country code
     * @param phone1            the customer primary phone components
     * @param phone2            the customer secondary phone components
     * @param governmentId      the customer government-issued id (sensitive)
     * @param eftAccountId      the customer EFT account id
     * @param primaryHolderFlag the primary-cardholder indicator (Y/N)
     * @param priorStatus       the flow state returned by the previous invocation (the COBOL
     *                          {@code ACUP-CHANGE-ACTION} carried in the COMMAREA); {@code null}
     *                          on first entry
     * @param confirmSave       whether the confirm key (PF05) was pressed
     * @param cancel            whether the cancel key (PF12) was pressed (re-fetch original data)
     */
    public record AccountUpdateCommand(
            String accountId,
            Long expectedVersion,
            String accountStatus,
            DateParts openDate,
            String creditLimit,
            DateParts expiryDate,
            String cashCreditLimit,
            DateParts reissueDate,
            String currentBalance,
            String currentCycleCredit,
            String currentCycleDebit,
            String groupId,
            SsnParts ssn,
            DateParts dob,
            String ficoScore,
            String firstName,
            String middleName,
            String lastName,
            String addressLine1,
            String addressLine2,
            String city,
            String stateCode,
            String zipCode,
            String countryCode,
            PhoneParts phone1,
            PhoneParts phone2,
            String governmentId,
            String eftAccountId,
            String primaryHolderFlag,
            Status priorStatus,
            boolean confirmSave,
            boolean cancel) {

        /**
         * Redacts the sensitive components (SSN, government id, date of birth) so an
         * accidental log of a command never leaks them, mirroring the sensitivity discipline
         * of the {@code AccountUpdateRequest} DTO.
         *
         * @return a string representation with sensitive fields masked
         */
        @Override
        public String toString() {
            return "AccountUpdateCommand[accountId=" + accountId
                    + ", expectedVersion=" + expectedVersion
                    + ", accountStatus=" + accountStatus
                    + ", groupId=" + groupId
                    + ", ssn=****, dob=****, governmentId=****"
                    + ", priorStatus=" + priorStatus
                    + ", confirmSave=" + confirmSave
                    + ", cancel=" + cancel + "]";
        }
    }

    // ====================================================================================
    // Account VIEW  (COACTVWC / transaction CAVW)
    // ====================================================================================

    /**
     * Reproduces the {@code COACTVWC} account inquiry: it edits the account filter, then
     * performs the {@code 9000-READ-ACCT} read chain (cross-reference &rarr; account &rarr;
     * customer) and returns the merged detail for display.
     *
     * <h3>Account-filter edit ({@code 2210-EDIT-ACCOUNT}, {@code legacy/cbl/COACTVWC.cbl})</h3>
     * The COBOL rejects a blank, non-numeric, or zero account id with the exact message
     * {@link #MSG_ACCT_FILTER_INVALID}. Because this method receives a numeric
     * {@code long}, the reachable invalid cases are a non-positive value (blank/zero map to
     * zero) or a value wider than 11 digits; both raise an {@link IllegalArgumentException}
     * carrying that exact message, matching the caller-visible outcome.
     *
     * <h3>Read chain ({@code 9000-READ-ACCT})</h3>
     * The exact order and short-circuit behavior are preserved:
     * <ol>
     *   <li>{@code 9200-GETCARDXREF-BYACCT} &mdash; read the cross-reference by account id via
     *       the alternate-index-equivalent query
     *       {@link CardXrefRepository#findFirstByAcctIdOrderByXrefCardNumAsc(Long)};
     *       {@code NOTFND} short-circuits the chain.</li>
     *   <li>{@code 9300-GETACCTDATA-BYACCT} &mdash; read the account by id
     *       ({@link AccountRepository#findById}).</li>
     *   <li>{@code 9400-GETCUSTDATA-BYCUST} &mdash; read the customer by the cross-referenced
     *       customer id ({@link CustomerRepository#findById}).</li>
     * </ol>
     * Any missing record raises {@link RecordNotFoundException} (rendered HTTP&nbsp;404),
     * preserving the COBOL {@code NOTFND} message shape (the CICS {@code RESP}/{@code REAS}
     * codes have no Java analog and are omitted; the file-status semantics are carried by the
     * typed exception).
     *
     * @param accountId the 11-digit account id to inquire on
     * @return the merged account and customer detail
     * @throws IllegalArgumentException  if {@code accountId} is not a non-zero 11-digit number
     * @throws RecordNotFoundException   if the cross-reference, account, or customer is missing
     */
    @Transactional(readOnly = true)
    public AccountDetail viewAccount(long accountId) {
        editAccountFilter(accountId);
        LOG.debug("Account inquiry requested for accountId={}", accountId);
        return readAccountChain(accountId);
    }

    /**
     * Edits an already-numeric account filter, reproducing the observable outcome of
     * {@code 2210-EDIT-ACCOUNT}: a zero (or otherwise non-positive) value, or a value wider
     * than 11 digits, is rejected with {@link #MSG_ACCT_FILTER_INVALID}.
     *
     * @param accountId the account id to validate
     * @throws IllegalArgumentException if the value is not a non-zero 11-digit number
     */
    private void editAccountFilter(long accountId) {
        if (accountId <= 0L || accountId > MAX_ACCT_ID) {
            throw new IllegalArgumentException(MSG_ACCT_FILTER_INVALID);
        }
    }

    /**
     * Performs the {@code 9000-READ-ACCT} read chain and returns the merged detail. Package
     * visibility is intentional so the update flow can reuse the identical chain.
     *
     * @param accountId the validated account id
     * @return the merged account and customer detail
     * @throws RecordNotFoundException if any record in the chain is missing
     */
    private AccountDetail readAccountChain(long accountId) {
        // 9200-GETCARDXREF-BYACCT: cross-reference by account id (alternate-index browse).
        Optional<CardXref> xref = cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(accountId);
        if (xref.isEmpty()) {
            throw new RecordNotFoundException(notFoundInXref(accountId));
        }
        long custId = xref.get().getCustId();

        // 9300-GETACCTDATA-BYACCT: account master by id.
        Optional<Account> account = accountRepository.findById(accountId);
        if (account.isEmpty()) {
            throw new RecordNotFoundException(notFoundInAccountMaster(accountId));
        }

        // 9400-GETCUSTDATA-BYCUST: customer master by the cross-referenced customer id.
        Optional<Customer> customer = customerRepository.findById(custId);
        if (customer.isEmpty()) {
            throw new RecordNotFoundException(notFoundInCustomerMaster(custId));
        }

        // Merge account + customer for display.
        return new AccountDetail(account.get(), customer.get());
    }

    /**
     * Builds the cross-reference {@code NOTFND} message ({@code 9200}); the account id is not
     * sensitive.
     */
    private static String notFoundInXref(long accountId) {
        return "Account:" + zeroPad(accountId, ACCT_ID_WIDTH) + " not found in Cross ref file.";
    }

    /**
     * Builds the account-master {@code NOTFND} message ({@code 9300}); the account id is not
     * sensitive.
     */
    private static String notFoundInAccountMaster(long accountId) {
        return "Account:" + zeroPad(accountId, ACCT_ID_WIDTH) + " not found in Acct Master file.";
    }

    /**
     * Builds the customer-master {@code NOTFND} message ({@code 9400}); the customer id is not
     * sensitive.
     */
    private static String notFoundInCustomerMaster(long custId) {
        return "CustId:" + zeroPad(custId, CUST_ID_WIDTH) + " not found in customer master.";
    }

    /**
     * Zero-pads a non-negative id to a fixed width, reproducing the COBOL numeric picture
     * ({@code PIC 9(n)}) used in the {@code NOTFND} message key.
     *
     * @param value the id value
     * @param width the fixed width
     * @return the zero-padded decimal string
     */
    private static String zeroPad(long value, int width) {
        return String.format("%0" + width + "d", value);
    }

    // ================================================================================
    // Account UPDATE (COACTUPC, transaction CAUP)
    // ================================================================================

    /**
     * Executes one turn of the account-update pseudo-conversation, reproducing the COBOL
     * {@code COACTUPC 0000-MAIN} re-entry handling and {@code 2000-DECIDE-ACTION} state machine
     * (AAP &sect;0.7.1 hotspot H1).
     *
     * <p>CardDemo online programs are pseudo-conversational: every screen submit is a discrete
     * transaction that carries its flow state in the CICS {@code COMMAREA}. Because a Spring
     * service is stateless, that state is expressed explicitly here — the caller (controller)
     * carries {@link AccountUpdateResult#status()} back on the next request via
     * {@link AccountUpdateCommand#priorStatus()}, and the {@code reentry} flag models the
     * {@code CDEMO-PGM-CONTEXT} ENTER(0)/REENTER(1) toggle ({@code COCOM01Y}). First entry into
     * the program (from the menu, {@code reentry == false}) always corresponds to COBOL
     * {@code ACUP-DETAILS-NOT-FETCHED}: the account filter is read and the details are shown.</p>
     *
     * <p>The pseudo-function-key intent is expressed by two command flags: {@code confirmSave}
     * models PF05 (commit) and is honored only while awaiting confirmation; {@code cancel} models
     * PF12 (re-read / cancel edits) and is honored only after details have been fetched.</p>
     *
     * <p>Not-found records surface as {@link RecordNotFoundException} (HTTP 404) and a detected
     * concurrent change surfaces as {@link OptimisticLockingFailureException} (HTTP 409), per the
     * file design note; validation failures are returned in the result so the caller can re-show
     * the screen exactly as the COBOL re-displays the map.</p>
     *
     * @param command the submitted field values and carried flow state (never {@code null})
     * @param reentry {@code false} on first entry into the program (fetch-and-show), {@code true}
     *                on a subsequent submit within the same conversation
     * @return the next flow state, the record to display, and any caller-visible message
     * @throws RecordNotFoundException        if the account, its cross-reference, or its customer
     *                                        does not exist (rendered HTTP 404)
     * @throws OptimisticLockingFailureException if the record changed since it was fetched
     *                                        (rendered HTTP 409)
     * @throws IllegalStateException          if an unexpected flow state is presented, reproducing
     *                                        the COBOL {@code WHEN OTHER} abend
     */
    @Transactional
    public AccountUpdateResult updateAccount(AccountUpdateCommand command, boolean reentry) {
        Objects.requireNonNull(command, "command");

        // COBOL 0000-MAIN: first entry into the program (EIBCALEN == 0, or arriving from the menu
        // without CDEMO-PGM-REENTER) is ACUP-DETAILS-NOT-FETCHED -> fetch the account and show it.
        if (!reentry) {
            return fetchForUpdate(command);
        }

        // Re-entry within the conversation: resolve the incoming ACUP-CHANGE-ACTION. A missing
        // prior status is treated as DETAILS-NOT-FETCHED for robustness.
        Status prior = command.priorStatus();
        if (prior == null) {
            return fetchForUpdate(command);
        }

        // COBOL WHEN CCARD-AID-PFK12 (valid only once details are fetched): re-read and re-show,
        // discarding any pending edits.
        if (command.cancel()) {
            return fetchForUpdate(command);
        }

        // 2000-DECIDE-ACTION (EVALUATE TRUE on the flow state).
        return switch (prior) {
            // WHEN ACUP-SHOW-DETAILS / WHEN ACUP-CHANGES-NOT-OK: re-run the edit pass on the
            // resubmitted values (1200-EDIT-MAP-INPUTS) and decide the next state.
            case SHOW_DETAILS, CHANGES_NOT_OK -> decideFromShowDetails(command);
            // WHEN ACUP-CHANGES-OK-NOT-CONFIRMED: PF05 commits (9600-WRITE-PROCESSING);
            // otherwise re-show the confirmation prompt.
            case CHANGES_OK_NOT_CONFIRMED ->
                    command.confirmSave() ? performWrite(command) : reshowConfirm(command);
            // WHEN ACUP-CHANGES-OKAYED-AND-DONE (0000-MAIN): the completed screen is reset to a
            // fresh fetch on the next request.
            case DONE -> fetchForUpdate(command);
            // WHEN OTHER: COBOL abends with 'UNEXPECTED DATA SCENARIO'; never continue silently.
            // The transient outcome tokens (LOCK_ERROR, UPDATE_FAILED, CONCURRENT_CHANGE) are
            // surfaced as exceptions and are not valid re-entry states.
            default -> throw new IllegalStateException("UNEXPECTED DATA SCENARIO");
        };
    }

    /**
     * COBOL {@code 2000-DECIDE-ACTION WHEN ACUP-DETAILS-NOT-FETCHED} (and the PF12 re-read path):
     * edit the account filter ({@code 1210-EDIT-ACCOUNT}), then perform {@code 9000-READ-ACCT} and
     * transition to {@code SHOW-DETAILS}. A blank or malformed filter is returned as an input
     * error so the caller re-prompts for the account id.
     *
     * @param command the submitted command
     * @return the resulting flow state (SHOW_DETAILS on success; CHANGES_NOT_OK on a filter error)
     */
    private AccountUpdateResult fetchForUpdate(AccountUpdateCommand command) {
        String trimmed = trim(command.accountId());

        // 1210-EDIT-ACCOUNT: blank -> "Account number not provided" (WS-PROMPT-FOR-ACCT).
        if (trimmed.isEmpty()) {
            return new AccountUpdateResult(Status.CHANGES_NOT_OK, null, MSG_ACCT_NOT_PROVIDED);
        }
        // NOT NUMERIC OR == ZERO -> the "11 digit Non-Zero Number" literal.
        if (!isAllDigits(trimmed) || trimmed.length() > ACCT_ID_WIDTH) {
            return new AccountUpdateResult(Status.CHANGES_NOT_OK, null, MSG_ACCT_NON_ZERO_11);
        }
        long acctId = Long.parseLong(trimmed);
        if (acctId == 0L) {
            return new AccountUpdateResult(Status.CHANGES_NOT_OK, null, MSG_ACCT_NON_ZERO_11);
        }

        // 9000-READ-ACCT: xref -> account -> customer. A miss throws RecordNotFoundException (404).
        AccountDetail detail = readAccountChain(acctId);
        return new AccountUpdateResult(Status.SHOW_DETAILS, detail, MSG_PROMPT_CHANGES);
    }

    /**
     * COBOL {@code 2000-DECIDE-ACTION WHEN ACUP-SHOW-DETAILS}: compare the submitted values against
     * the fetched record ({@code 1205-COMPARE-OLD-NEW}); when nothing changed, re-show with the
     * "no changes" message; otherwise run the field edits ({@code 1200-EDIT-MAP-INPUTS}) and either
     * report the first error ({@code CHANGES-NOT-OK}) or advance to the confirmation prompt
     * ({@code CHANGES-OK-NOT-CONFIRMED}).
     *
     * @param command the submitted command
     * @return the resulting flow state
     */
    private AccountUpdateResult decideFromShowDetails(AccountUpdateCommand command) {
        long acctId = requireAccountId(command);
        AccountDetail current = readAccountChain(acctId);

        // 1205-COMPARE-OLD-NEW: "no functional change" short-circuit (COBOL NO-CHANGES-DETECTED).
        if (isUnchanged(command, current)) {
            return new AccountUpdateResult(Status.SHOW_DETAILS, current, MSG_NO_CHANGES);
        }

        // 1200-EDIT-MAP-INPUTS: field edits in COBOL order with first-message-wins latching.
        EditState edit = editFields(command);
        if (edit.inputError) {
            return new AccountUpdateResult(Status.CHANGES_NOT_OK, current, edit.message);
        }

        // All edits passed -> present the confirmation prompt (SET ACUP-CHANGES-OK-NOT-CONFIRMED).
        return new AccountUpdateResult(
                Status.CHANGES_OK_NOT_CONFIRMED, current, MSG_PROMPT_CONFIRMATION);
    }

    /**
     * COBOL {@code 2000-DECIDE-ACTION WHEN ACUP-CHANGES-OK-NOT-CONFIRMED} without PF05: re-read the
     * record and re-show the confirmation prompt unchanged.
     *
     * @param command the submitted command
     * @return the confirmation-pending flow state
     */
    private AccountUpdateResult reshowConfirm(AccountUpdateCommand command) {
        long acctId = requireAccountId(command);
        AccountDetail current = readAccountChain(acctId);
        return new AccountUpdateResult(
                Status.CHANGES_OK_NOT_CONFIRMED, current, MSG_PROMPT_CONFIRMATION);
    }

    /**
     * COBOL {@code 9600-WRITE-PROCESSING}, invoked from {@code 2000-DECIDE-ACTION WHEN
     * ACUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05} (PF05 = confirm save): commit the
     * validated changes to the account and customer master records.
     *
     * <p>The COBOL read-update-rewrite cycle and its {@code 9700-CHECK-CHANGE-IN-REC}
     * concurrent-change guard ({@code DATA-WAS-CHANGED-BEFORE-UPDATE}) are reproduced here by JPA
     * optimistic locking on the {@link Account} {@code @Version} column (§0.7.1 H6). The version the
     * client observed when the details were fetched is carried on the command
     * ({@link AccountUpdateCommand#expectedVersion()}); if the persistent row has advanced past that
     * version, another user changed it first, and an {@link OptimisticLockingFailureException} is
     * raised. The global exception handler renders that as HTTP&nbsp;409, which the caller treats as
     * the {@link Status#CONCURRENT_CHANGE} outcome (re-show the now-current details). This is the
     * intentional, documented improvement that replaces the manual field-by-field OLD/NEW
     * comparison (decision log).</p>
     *
     * <p>The account and customer are re-read fresh inside the update transaction (the COBOL reads
     * the records again under update intent), the validated {@code ACUP-NEW-*} values are applied,
     * and both records are rewritten within the single {@link Transactional} boundary opened by
     * {@link #updateAccount(AccountUpdateCommand, boolean)}. The field edits are re-run defensively
     * ({@code 1200-EDIT-MAP-INPUTS}) so a client that submits straight to the confirm step cannot
     * bypass validation.</p>
     *
     * @param command the validated, confirmed command
     * @return {@link Status#DONE} with the persisted detail and the success message, or
     *         {@link Status#CHANGES_NOT_OK} when the defensive re-edit fails
     * @throws RecordNotFoundException           if the account, cross-reference, or customer record
     *                                           cannot be read
     * @throws OptimisticLockingFailureException if the account row changed since it was fetched
     */
    private AccountUpdateResult performWrite(AccountUpdateCommand command) {
        long acctId = requireAccountId(command);

        // Re-read the account master fresh under the update transaction (COBOL READ ... UPDATE).
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(notFoundInAccountMaster(acctId)));

        // 9700-CHECK-CHANGE-IN-REC (DATA-WAS-CHANGED-BEFORE-UPDATE): the @Version optimistic-lock
        // check replaces the COBOL field-by-field OLD/NEW comparison. If the persistent row has
        // advanced past the version the client fetched, someone else changed it first.
        Long expectedVersion = command.expectedVersion();
        if (expectedVersion != null && !expectedVersion.equals(account.getVersion())) {
            throw new OptimisticLockingFailureException(MSG_DATA_CHANGED);
        }

        // Resolve the customer through the cross-reference exactly as the read chain does
        // (9200-GETCARDXREF-BYACCT -> 9400-GETCUSTDATA-BYCUST).
        CardXref xref = cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(acctId)
                .orElseThrow(() -> new RecordNotFoundException(notFoundInXref(acctId)));
        long custId = xref.getCustId();
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new RecordNotFoundException(notFoundInCustomerMaster(custId)));

        // Defensive re-edit: the state machine only reaches this point after a clean edit pass, but
        // re-running 1200-EDIT-MAP-INPUTS defends against a client that submits the confirm step
        // directly. A failure re-shows the changes with the first (latched) message.
        EditState edit = editFields(command);
        if (edit.inputError) {
            return new AccountUpdateResult(
                    Status.CHANGES_NOT_OK, new AccountDetail(account, customer), edit.message);
        }

        // Apply the validated ACUP-NEW-* values and rewrite both records (9600-WRITE-PROCESSING).
        // The account carries the @Version column; the customer does not (see class contract).
        applyAccountUpdates(account, command);
        applyCustomerUpdates(customer, command);
        Account savedAccount = accountRepository.save(account);
        Customer savedCustomer = customerRepository.save(customer);

        // The account id is not sensitive; SSN, government id, and DOB are never logged.
        LOG.info("Account {} updated successfully", acctId);
        return new AccountUpdateResult(
                Status.DONE, new AccountDetail(savedAccount, savedCustomer), MSG_CONFIRM_SUCCESS);
    }

    /**
     * Parses and validates the (already-fetched) account id carried on the command, reproducing
     * the invariant that the account key is fixed and valid once details have been shown. A
     * malformed key at this point is an unexpected client state and is rejected with the account
     * filter message.
     *
     * @param command the submitted command
     * @return the numeric account id
     * @throws IllegalArgumentException if the carried account id is blank or malformed
     */
    private static long requireAccountId(AccountUpdateCommand command) {
        String trimmed = trim(command.accountId());
        if (trimmed.isEmpty() || !isAllDigits(trimmed) || trimmed.length() > ACCT_ID_WIDTH) {
            throw new IllegalArgumentException(MSG_ACCT_FILTER_INVALID);
        }
        long value = Long.parseLong(trimmed);
        if (value <= 0L) {
            throw new IllegalArgumentException(MSG_ACCT_FILTER_INVALID);
        }
        return value;
    }

    /**
     * Normalizes an optional raw field to a non-{@code null}, trimmed string, reproducing the
     * COBOL treatment of {@code SPACES}/{@code LOW-VALUES} fixed-width fields as blank.
     *
     * @param value the raw value (may be {@code null})
     * @return the trimmed value, or the empty string when {@code null}
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Returns {@code true} only when the argument is non-empty and every character is an ASCII
     * digit {@code '0'..'9'}, matching the COBOL {@code IS NUMERIC} class test (which excludes the
     * Unicode digits accepted by {@link Character#isDigit(char)}).
     *
     * @param value the value to test
     * @return {@code true} if the value is all ASCII digits
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Mutable accumulator reproducing the COBOL first-message-wins latching: the input-error flag
     * ({@code INPUT-ERROR}) latches on the first failure and stays set, while only the first
     * message is retained (the COBOL gates message assignment on {@code WS-RETURN-MSG-OFF}).
     */
    private static final class EditState {

        /** Set once any field edit fails; never cleared for the remainder of the pass. */
        private boolean inputError;

        /** The first failure message; subsequent failures do not overwrite it. */
        private String message;

        /**
         * Latches an edit failure. The input-error flag is set unconditionally; the message is
         * captured only if none has been captured yet (first-message-wins).
         *
         * @param failureMessage the caller-visible failure message
         */
        void latch(String failureMessage) {
            inputError = true;
            if (message == null) {
                message = failureMessage;
            }
        }
    }

    // --------------------------------------------------------------------------------
    // 1200-EDIT-MAP-INPUTS — field edits in exact COBOL order, first-message-wins
    // --------------------------------------------------------------------------------

    /**
     * Runs the field edits of COBOL {@code 1200-EDIT-MAP-INPUTS} in their exact source order,
     * delegating each to a private edit routine that mirrors the corresponding COBOL paragraph
     * ({@code 1215}..{@code 1280} and {@code EDIT-DATE-CCYYMMDD}). Every edit runs unconditionally;
     * a failure latches {@code INPUT-ERROR} and captures the message only if none has been captured
     * yet (first-message-wins), reproducing the {@code WS-RETURN-MSG-OFF} gating.
     *
     * <p>The 1205 "no functional change" comparison is performed separately by
     * {@link #isUnchanged(AccountUpdateCommand, AccountDetail)}; this method performs only the
     * per-field edits (the COBOL {@code 1200} runs 1205 first, then these edits when a change is
     * present).</p>
     *
     * @param command the submitted field values
     * @return the accumulated edit state (input-error flag and first message)
     */
    private EditState editFields(AccountUpdateCommand command) {
        EditState state = new EditState();

        // 1  Account Status (1220-EDIT-YESNO).
        editYesNo(state, FLD_ACCOUNT_STATUS, command.accountStatus());
        // 2  Open Date (EDIT-DATE-CCYYMMDD).
        editDate(state, FLD_OPEN_DATE, command.openDate());
        // 3  Credit Limit (1250-EDIT-SIGNED-9V2).
        editSigned(state, FLD_CREDIT_LIMIT, command.creditLimit());
        // 4  Expiry Date.
        editDate(state, FLD_EXPIRY_DATE, command.expiryDate());
        // 5  Cash Credit Limit.
        editSigned(state, FLD_CASH_CREDIT_LIMIT, command.cashCreditLimit());
        // 6  Reissue Date.
        editDate(state, FLD_REISSUE_DATE, command.reissueDate());
        // 7  Current Balance.
        editSigned(state, FLD_CURRENT_BALANCE, command.currentBalance());
        // 8  Current Cycle Credit Limit.
        editSigned(state, FLD_CURR_CYC_CREDIT, command.currentCycleCredit());
        // 9  Current Cycle Debit Limit.
        editSigned(state, FLD_CURR_CYC_DEBIT, command.currentCycleDebit());
        // 10 SSN (1265-EDIT-US-SSN, three parts).
        editSsn(state, command.ssn());
        // 11 Date of Birth (EDIT-DATE-CCYYMMDD then EDIT-DATE-OF-BIRTH when the date is valid).
        editDateOfBirth(state, command.dob());
        // 12 FICO Score (1245-EDIT-NUM-REQD then 1275-EDIT-FICO-SCORE when numeric-valid).
        editFico(state, command.ficoScore());
        // 13 First Name (1225-EDIT-ALPHA-REQD).
        editAlphaRequired(state, FLD_FIRST_NAME, command.firstName());
        // 14 Middle Name (1235-EDIT-ALPHA-OPT).
        editAlphaOptional(state, FLD_MIDDLE_NAME, command.middleName());
        // 15 Last Name (1225-EDIT-ALPHA-REQD).
        editAlphaRequired(state, FLD_LAST_NAME, command.lastName());
        // 16 Address Line 1 (1215-EDIT-MANDATORY).
        editMandatory(state, FLD_ADDR_LINE_1, command.addressLine1());
        // 17 State (1225-EDIT-ALPHA-REQD, then 1270-EDIT-US-STATE-CD when the alpha edit passed).
        boolean stateAlphaOk = editAlphaRequired(state, FLD_STATE, command.stateCode());
        boolean stateOk = false;
        if (stateAlphaOk) {
            stateOk = editStateCode(state, command.stateCode());
        }
        // 18 Zip (1245-EDIT-NUM-REQD).
        boolean zipOk = editNumRequired(state, FLD_ZIP, command.zipCode());
        // 19 City = ADDR-LINE-3 (1225-EDIT-ALPHA-REQD).
        editAlphaRequired(state, FLD_CITY, command.city());
        // 20 Country (1225-EDIT-ALPHA-REQD).
        editAlphaRequired(state, FLD_COUNTRY, command.countryCode());
        // 21 Phone Number 1 (1260-EDIT-US-PHONE-NUM).
        editPhone(state, FLD_PHONE_1, command.phone1());
        // 22 Phone Number 2.
        editPhone(state, FLD_PHONE_2, command.phone2());
        // 23 EFT Account Id (1245-EDIT-NUM-REQD).
        editNumRequired(state, FLD_EFT_ACCOUNT_ID, command.eftAccountId());
        // 24 Primary Card Holder (1220-EDIT-YESNO).
        editYesNo(state, FLD_PRI_CARD_HOLDER, command.primaryHolderFlag());

        // Cross-field: state + zip combination (1280) only when both individually valid.
        if (stateOk && zipOk) {
            editStateZip(state, command.stateCode(), command.zipCode());
        }

        return state;
    }

    /**
     * COBOL {@code 1220-EDIT-YESNO}: the value must be supplied (not blank/spaces/zeros) and must
     * be {@code Y} or {@code N} (case-insensitive).
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param value     the submitted value
     * @return {@code true} when the value is a valid Y/N indicator
     */
    private static boolean editYesNo(EditState state, String fieldName, String value) {
        String t = trim(value);
        if (t.isEmpty() || isAllZeros(t)) {
            state.latch(fieldName + SFX_MUST_BE_SUPPLIED);
            return false;
        }
        if (t.equalsIgnoreCase("Y") || t.equalsIgnoreCase("N")) {
            return true;
        }
        state.latch(fieldName + SFX_MUST_BE_Y_OR_N);
        return false;
    }

    /**
     * COBOL {@code 1215-EDIT-MANDATORY}: the value must be supplied (non-blank). No format check.
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param value     the submitted value
     * @return {@code true} when a value is present
     */
    private static boolean editMandatory(EditState state, String fieldName, String value) {
        if (trim(value).isEmpty()) {
            state.latch(fieldName + SFX_MUST_BE_SUPPLIED);
            return false;
        }
        return true;
    }

    /**
     * COBOL {@code 1225-EDIT-ALPHA-REQD}: the value must be supplied and may contain only
     * alphabetic characters and spaces (the COBOL {@code INSPECT ... CONVERTING} alphabets to
     * spaces, then checks the remainder is blank).
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param value     the submitted value
     * @return {@code true} when the value is present and alphabetic
     */
    private static boolean editAlphaRequired(EditState state, String fieldName, String value) {
        String v = value == null ? "" : value;
        if (v.trim().isEmpty()) {
            state.latch(fieldName + SFX_MUST_BE_SUPPLIED);
            return false;
        }
        if (!isAlphaOrSpace(v)) {
            state.latch(fieldName + SFX_ALPHABETS_ONLY);
            return false;
        }
        return true;
    }

    /**
     * COBOL {@code 1235-EDIT-ALPHA-OPT}: a blank value is valid (optional); when present, the value
     * may contain only alphabetic characters and spaces.
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param value     the submitted value
     * @return {@code true} when the value is blank or alphabetic
     */
    private static boolean editAlphaOptional(EditState state, String fieldName, String value) {
        String v = value == null ? "" : value;
        if (v.trim().isEmpty()) {
            return true;
        }
        if (!isAlphaOrSpace(v)) {
            state.latch(fieldName + SFX_ALPHABETS_ONLY);
            return false;
        }
        return true;
    }

    /**
     * COBOL {@code 1245-EDIT-NUM-REQD}: the value must be supplied, must be all numeric, and must
     * not be zero.
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param value     the submitted value
     * @return {@code true} when the value is a non-zero numeric string
     */
    private static boolean editNumRequired(EditState state, String fieldName, String value) {
        String t = trim(value);
        if (t.isEmpty()) {
            state.latch(fieldName + SFX_MUST_BE_SUPPLIED);
            return false;
        }
        if (!isAllDigits(t)) {
            state.latch(fieldName + SFX_ALL_NUMERIC);
            return false;
        }
        if (isAllZeros(t)) {
            state.latch(fieldName + SFX_MUST_NOT_BE_ZERO);
            return false;
        }
        return true;
    }

    /**
     * COBOL {@code 1250-EDIT-SIGNED-9V2}: the value must be supplied and must be a valid signed
     * decimal number ({@code FUNCTION TEST-NUMVAL-C = 0}). No magnitude or sign restriction is
     * applied here; the numeric conversion (to {@code BigDecimal} scale 2) happens at write time.
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param value     the submitted value
     * @return {@code true} when the value is a valid signed decimal
     */
    private static boolean editSigned(EditState state, String fieldName, String value) {
        String t = trim(value);
        if (t.isEmpty()) {
            state.latch(fieldName + SFX_MUST_BE_SUPPLIED);
            return false;
        }
        if (!isValidSignedDecimal(t)) {
            state.latch(fieldName + SFX_IS_NOT_VALID);
            return false;
        }
        return true;
    }

    /**
     * COBOL {@code EDIT-DATE-CCYYMMDD} (copybook {@code CSUTLDPY}): validates a CCYY-MM-DD date by
     * component. The year (with century restricted to 19/20), month (1-12) and day (1-31) are each
     * validated independently (all run, first-message-wins), then the day/month/year combination is
     * checked (31-day months, February 30, and February 29 leap-year rule). Finally the
     * {@code EDIT-DATE-LE} backstop is reproduced with the injected {@link DateValidationService}.
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param parts     the submitted year/month/day parts (may be {@code null})
     * @return {@code true} when the date is fully valid
     */
    private boolean editDate(EditState state, String fieldName, DateParts parts) {
        String yy = parts == null ? "" : trim(parts.year());
        String mm = parts == null ? "" : trim(parts.month());
        String dd = parts == null ? "" : trim(parts.day());

        boolean valid = true;

        // EDIT-YEAR-CCYY.
        Integer yearNum = (isAllDigits(yy) && yy.length() == 4) ? Integer.valueOf(yy) : null;
        if (yy.isEmpty()) {
            state.latch(fieldName + SFX_YEAR_SUPPLIED);
            valid = false;
        } else if (yearNum == null) {
            state.latch(fieldName + SFX_YEAR_4_DIGIT);
            valid = false;
        } else {
            int century = yearNum / 100;
            if (century != 20 && century != 19) {
                state.latch(fieldName + SFX_CENTURY_INVALID);
                valid = false;
            }
        }

        // EDIT-MONTH (the map field is two characters; guard the length before parsing).
        Integer month = null;
        if (mm.isEmpty()) {
            state.latch(fieldName + SFX_MONTH_SUPPLIED);
            valid = false;
        } else if (!isAllDigits(mm) || mm.length() > 2
                || Integer.parseInt(mm) < 1 || Integer.parseInt(mm) > 12) {
            state.latch(fieldName + SFX_MONTH_RANGE);
            valid = false;
        } else {
            month = Integer.valueOf(mm);
        }

        // EDIT-DAY (the map field is two characters; guard the length before parsing).
        Integer day = null;
        if (dd.isEmpty()) {
            state.latch(fieldName + SFX_DAY_SUPPLIED);
            valid = false;
        } else if (!isAllDigits(dd) || dd.length() > 2
                || Integer.parseInt(dd) < 1 || Integer.parseInt(dd) > 31) {
            state.latch(fieldName + SFX_DAY_RANGE);
            valid = false;
        } else {
            day = Integer.valueOf(dd);
        }

        // EDIT-DAY-MONTH-YEAR — combination checks (require numeric month and day).
        if (month != null && day != null) {
            boolean is31DayMonth = month == 1 || month == 3 || month == 5 || month == 7
                    || month == 8 || month == 10 || month == 12;
            if (!is31DayMonth && day == 31) {
                state.latch(fieldName + SFX_DAY_31);
                valid = false;
            } else if (month == 2 && day == 30) {
                state.latch(fieldName + SFX_DAY_30);
                valid = false;
            } else if (month == 2 && day == 29 && yearNum != null && !isLeapYear(yearNum)) {
                state.latch(fieldName + SFX_DAY_29);
                valid = false;
            }
        }

        // EDIT-DATE-LE backstop (COBOL CALL CSUTLDTC) via the injected date service. Unreachable
        // when the component checks above pass, but preserved for structural fidelity.
        if (valid) {
            String composed = String.format("%04d-%02d-%02d", yearNum, month, day);
            if (!dateValidationService.isValid(composed, DATE_FORMAT)) {
                state.latch(fieldName + SFX_DAY_RANGE);
                valid = false;
            }
        }

        return valid;
    }

    /**
     * COBOL {@code EDIT-DATE-OF-BIRTH} (copybook {@code CSUTLDPY}): validates the date-of-birth as a
     * calendar date and, when valid, requires it to be strictly before today. The DOB value itself
     * is sensitive and never appears in the message or the logs.
     *
     * @param state the edit accumulator
     * @param parts the submitted date-of-birth parts (may be {@code null})
     */
    private void editDateOfBirth(EditState state, DateParts parts) {
        if (!editDate(state, FLD_DOB, parts)) {
            return;
        }
        // Date is valid; enforce "must be in the past" using the injected date service to parse.
        LocalDate dob = dateValidationService.parse(composeDate(parts));
        if (!dob.isBefore(LocalDate.now())) {
            state.latch(FLD_DOB + SFX_DOB_FUTURE);
        }
    }

    /**
     * COBOL {@code 1245-EDIT-NUM-REQD} (length 3) followed by {@code 1275-EDIT-FICO-SCORE}: the FICO
     * score must be a non-zero numeric value in the inclusive range 300-850.
     *
     * @param state the edit accumulator
     * @param value the submitted FICO score
     */
    private static void editFico(EditState state, String value) {
        String t = trim(value);
        if (t.isEmpty()) {
            state.latch(FLD_FICO + SFX_MUST_BE_SUPPLIED);
            return;
        }
        if (!isAllDigits(t)) {
            state.latch(FLD_FICO + SFX_ALL_NUMERIC);
            return;
        }
        if (isAllZeros(t)) {
            state.latch(FLD_FICO + SFX_MUST_NOT_BE_ZERO);
            return;
        }
        int fico = Integer.parseInt(t);
        if (fico < FICO_MIN || fico > FICO_MAX) {
            state.latch(FLD_FICO + SFX_FICO_RANGE);
        }
    }

    /**
     * COBOL {@code 1265-EDIT-US-SSN}: edits the three SSN parts in order and latches the first
     * failing part's message. This delegates to the injected {@link UsSsnRule} Strategy component
     * (the single Java reproduction of {@code 1265-EDIT-US-SSN}) rather than re-implementing the
     * three-part edit here: the rule reuses the numeric-required edit for each part, applies the
     * {@code INVALID-SSN-PART1} reserved-value exclusions (000, 666, 900-999) only when part 1 is
     * numeric-valid, enforces the fixed-width digit count of each part (so a short, space-padded
     * entry fails the {@code IS NUMERIC} test exactly as it does in the legacy program), and
     * returns the first failing part's exact COBOL screen message. The failing message is latched
     * into the shared {@link EditState} through {@link EditState#latch(String)}, preserving the
     * first-message-wins ({@code WS-RETURN-MSG-OFF}) and {@code INPUT-ERROR} semantics.
     *
     * <p>The SSN is sensitive: the parts are passed straight through to the rule and never logged,
     * and every rule message is built solely from fixed field labels plus fixed text, so no SSN
     * digit can escape through a message.</p>
     *
     * @param state the edit accumulator
     * @param ssn   the submitted SSN parts (may be {@code null})
     */
    private void editSsn(EditState state, SsnParts ssn) {
        ValidationResult result = usSsnRule.validate(
                ssn == null ? null : ssn.part1(),
                ssn == null ? null : ssn.part2(),
                ssn == null ? null : ssn.part3());
        if (result.isInvalid()) {
            state.latch(result.message());
        }
    }

    /**
     * COBOL {@code 1270-EDIT-US-STATE-CD}: the (already alphabetic) state code must be one of the
     * 56 valid USPS state/territory codes. The COBOL {@code 88}-level compares the field verbatim
     * against upper-case literals, so this check is case-sensitive.
     *
     * @param state the edit accumulator
     * @param value the submitted state code
     * @return {@code true} when the state code is valid
     */
    private static boolean editStateCode(EditState state, String value) {
        if (!VALID_US_STATE_CODES.contains(trim(value))) {
            state.latch(FLD_STATE + SFX_STATE_INVALID);
            return false;
        }
        return true;
    }

    /**
     * COBOL {@code 1280-EDIT-US-STATE-ZIP-CD}: cross-field check that the state and the first two
     * ZIP digits form a valid combination.
     *
     * <p>The exhaustive {@code (state, zip-prefix)} membership table lives in copybook
     * {@code CSLKPCDY} (1318 lines) and is owned by the dedicated {@code service/rule/UsStateZipRule}
     * component (AAP &sect;0.5.3). This self-contained service reproduces the check position, the
     * exact failure message, and a structural consistency re-check; the exhaustive membership is a
     * documented bounded deviation (decision log) that never rejects a value the COBOL accepts.</p>
     *
     * @param state     the edit accumulator
     * @param stateCode the submitted state code (already valid)
     * @param zip       the submitted ZIP code (already valid)
     */
    private static void editStateZip(EditState state, String stateCode, String zip) {
        if (!isValidStateZipCombo(stateCode, zip)) {
            state.latch(MSG_ZIP_FOR_STATE_INVALID);
        }
    }

    /**
     * COBOL {@code 1260-EDIT-US-PHONE-NUM}: a US phone number is optional (skipped when both the
     * area code and prefix are blank); when supplied, each of the three parts is validated in turn
     * (area code, prefix, line number), continuing to the next part on failure so that all parts
     * are edited under the first-message-wins rule. The phone value is not logged.
     *
     * @param state     the edit accumulator
     * @param fieldName the caller-visible field name
     * @param phone     the submitted phone parts (may be {@code null})
     */
    private static void editPhone(EditState state, String fieldName, PhoneParts phone) {
        String area = phone == null ? "" : trim(phone.area());
        String prefix = phone == null ? "" : trim(phone.prefix());
        String line = phone == null ? "" : trim(phone.line());

        // Not mandatory: when area and prefix are both blank the phone number is treated as absent.
        if (area.isEmpty() && prefix.isEmpty()) {
            return;
        }

        // EDIT-AREA-CODE.
        if (area.isEmpty()) {
            state.latch(fieldName + SFX_AREA_SUPPLIED);
        } else if (!isAllDigits(area)) {
            state.latch(fieldName + SFX_AREA_3_DIGIT);
        } else if (isAllZeros(area)) {
            state.latch(fieldName + SFX_AREA_ZERO);
        } else if (!isGeneralPurposeAreaCode(area)) {
            state.latch(fieldName + SFX_AREA_NANPA);
        }

        // EDIT-US-PHONE-PREFIX.
        if (prefix.isEmpty()) {
            state.latch(fieldName + SFX_PREFIX_SUPPLIED);
        } else if (!isAllDigits(prefix)) {
            state.latch(fieldName + SFX_PREFIX_3_DIGIT);
        } else if (isAllZeros(prefix)) {
            state.latch(fieldName + SFX_PREFIX_ZERO);
        }

        // EDIT-US-PHONE-LINENUM.
        if (line.isEmpty()) {
            state.latch(fieldName + SFX_LINE_SUPPLIED);
        } else if (!isAllDigits(line)) {
            state.latch(fieldName + SFX_LINE_4_DIGIT);
        } else if (isAllZeros(line)) {
            state.latch(fieldName + SFX_LINE_ZERO);
        }
    }

    // --------------------------------------------------------------------------------
    // Edit helper predicates
    // --------------------------------------------------------------------------------

    /**
     * Returns {@code true} when every character of the value is an ASCII letter or a space,
     * reproducing the COBOL "alphabets and spaces only" test.
     *
     * @param value the value to test
     * @return {@code true} when only letters and spaces are present
     */
    private static boolean isAlphaOrSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!letter && c != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the value is non-empty and every character is {@code '0'},
     * reproducing the COBOL {@code EQUAL ZEROS} / {@code NUMVAL = 0} checks for a digit string.
     *
     * @param value the value to test
     * @return {@code true} when the value is all zeros
     */
    private static boolean isAllZeros(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the value is a syntactically valid signed decimal number, matching
     * the acceptance of the COBOL {@code FUNCTION TEST-NUMVAL-C}: an optional single leading or
     * trailing sign, decimal digits, and at most one decimal point.
     *
     * @param value the trimmed value to test
     * @return {@code true} when the value parses as a signed decimal
     */
    private static boolean isValidSignedDecimal(String value) {
        String s = value;
        if (s.startsWith("+") || s.startsWith("-")) {
            s = s.substring(1);
        } else if (s.endsWith("+") || s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return false;
        }
        int dots = 0;
        boolean digitSeen = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                dots++;
                if (dots > 1) {
                    return false;
                }
            } else if (c >= '0' && c <= '9') {
                digitSeen = true;
            } else {
                return false;
            }
        }
        return digitSeen;
    }

    /**
     * Approximates the COBOL {@code VALID-GENERAL-PURP-CODE} membership from copybook
     * {@code CSLKPCDY} (owned by {@code service/rule/UsPhoneRule}): a general-purpose North American
     * area code has a first digit of 2-9 and is not an {@code N11} service code. This is a documented
     * bounded deviation — it accepts a superset of the exhaustive table and never rejects a code the
     * COBOL accepts.
     *
     * @param area the three-digit area code (already numeric and non-zero)
     * @return {@code true} when the area code is a plausible general-purpose code
     */
    private static boolean isGeneralPurposeAreaCode(String area) {
        int code = Integer.parseInt(area);
        return code >= 200 && (code % 100) != 11;
    }

    /**
     * Structural consistency re-check for {@link #editStateZip(EditState, String, String)}; the full
     * {@code (state, zip-prefix)} membership is delegated to {@code service/rule/UsStateZipRule}.
     *
     * @param stateCode the state code
     * @param zip       the ZIP code
     * @return {@code true} when the state is valid and the ZIP has a numeric two-digit prefix
     */
    private static boolean isValidStateZipCombo(String stateCode, String zip) {
        String s = trim(stateCode);
        String z = trim(zip);
        if (!VALID_US_STATE_CODES.contains(s)) {
            return false;
        }
        return z.length() >= 2 && isAllDigits(z.substring(0, 2));
    }

    /**
     * Reproduces the COBOL leap-year computation: divide the four-digit year by 400 when the
     * two-digit year component is 00 (century year) and by 4 otherwise; the year is a leap year when
     * the remainder is zero.
     *
     * @param year the four-digit year
     * @return {@code true} when the year is a leap year under the COBOL rule
     */
    private static boolean isLeapYear(int year) {
        int divisor = (year % 100 == 0) ? 400 : 4;
        return year % divisor == 0;
    }

    /**
     * Composes a canonical {@code YYYY-MM-DD} string from validated date parts, reproducing the
     * COBOL {@code STRING ... DELIMITED BY SIZE} date assembly with zero-padded month and day.
     *
     * @param parts the (validated) date parts
     * @return the composed {@code YYYY-MM-DD} string
     */
    private static String composeDate(DateParts parts) {
        int month = Integer.parseInt(trim(parts.month()));
        int day = Integer.parseInt(trim(parts.day()));
        return String.format("%s-%02d-%02d", trim(parts.year()), month, day);
    }

    // --------------------------------------------------------------------------------
    // 1205-COMPARE-OLD-NEW — "no functional change" short-circuit
    // --------------------------------------------------------------------------------

    /**
     * Reproduces COBOL {@code 1205-COMPARE-OLD-NEW}: returns {@code true} only when every submitted
     * value matches the corresponding value on the freshly-read record, so that the update is
     * short-circuited with the "no changes" message (COBOL {@code NO-CHANGES-DETECTED}).
     *
     * <p>Text fields are compared case-insensitively and trimmed (the COBOL {@code UPPER-CASE} /
     * {@code TRIM}); monetary fields are compared numerically; dates are compared in canonical
     * {@code YYYY-MM-DD} form; phone and SSN parts are compared by their digits; the FICO score and
     * EFT account id are compared numerically. Any value that cannot be parsed is treated as a
     * change (biasing toward performing the write), so a genuine edit is never silently discarded.</p>
     *
     * @param command the submitted values
     * @param current the freshly-read account and customer
     * @return {@code true} when no field differs from the current record
     */
    private boolean isUnchanged(AccountUpdateCommand command, AccountDetail current) {
        Account account = current.account();
        Customer customer = current.customer();

        return textEquals(command.accountStatus(), account.getAcctActiveStatus())
                && moneyEquals(command.currentBalance(), account.getCurrBal())
                && moneyEquals(command.creditLimit(), account.getCreditLimit())
                && moneyEquals(command.cashCreditLimit(), account.getCashCreditLimit())
                && moneyEquals(command.currentCycleCredit(), account.getCurrCycCredit())
                && moneyEquals(command.currentCycleDebit(), account.getCurrCycDebit())
                && dateEquals(command.openDate(), account.getAcctOpenDate())
                && dateEquals(command.expiryDate(), account.getAcctExpirationDate())
                && dateEquals(command.reissueDate(), account.getAcctReissueDate())
                && textEquals(command.groupId(), account.getGroupId())
                && textEquals(command.firstName(), customer.getCustFirstName())
                && textEquals(command.middleName(), customer.getCustMiddleName())
                && textEquals(command.lastName(), customer.getCustLastName())
                && textEquals(command.addressLine1(), customer.getCustAddrLine1())
                && textEquals(command.addressLine2(), customer.getCustAddrLine2())
                && textEquals(command.city(), customer.getCustAddrLine3())
                && textEquals(command.stateCode(), customer.getCustAddrStateCd())
                && textEquals(command.countryCode(), customer.getCustAddrCountryCd())
                && textEquals(command.zipCode(), customer.getCustAddrZip())
                && digitsEquals(phoneDigits(command.phone1()), customer.getCustPhoneNum1())
                && digitsEquals(phoneDigits(command.phone2()), customer.getCustPhoneNum2())
                && digitsEquals(ssnDigits(command.ssn()), customer.getCustSsn())
                && textEquals(command.governmentId(), customer.getCustGovtIssuedId())
                && dateEquals(command.dob(), customer.getCustDob())
                && numericEquals(command.eftAccountId(), customer.getCustEftAccountId())
                && textEquals(command.primaryHolderFlag(), customer.getCustPriCardHolderInd())
                && ficoEquals(command.ficoScore(), customer.getCustFicoCreditScore());
    }

    /**
     * Case-insensitive, trimmed string comparison reproducing the COBOL
     * {@code FUNCTION UPPER-CASE(FUNCTION TRIM(...))} equality used in {@code 1205-COMPARE-OLD-NEW}.
     */
    private static boolean textEquals(String submitted, String current) {
        return norm(submitted).equals(norm(current));
    }

    /** Upper-cases (ASCII) and trims a value, treating {@code null} as blank. */
    private static String norm(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** Numeric (scale-2) comparison of a submitted monetary string against the stored value. */
    private static boolean moneyEquals(String submitted, BigDecimal current) {
        BigDecimal parsed = parseSignedMoneySafe(submitted);
        return parsed != null && current != null && parsed.compareTo(current) == 0;
    }

    /** Canonical {@code YYYY-MM-DD} comparison of submitted date parts against the stored value. */
    private static boolean dateEquals(DateParts submitted, String current) {
        return composeDateSafe(submitted).equals(trim(current));
    }

    /** Digit-only comparison (submitted digits vs the digits extracted from the stored value). */
    private static boolean digitsEquals(String submittedDigits, String current) {
        return submittedDigits.equals(onlyDigits(current));
    }

    /** Numeric comparison tolerant of zero-padding; falls back to trimmed equality. */
    private static boolean numericEquals(String submitted, String current) {
        String a = trim(submitted);
        String b = trim(current);
        if (isAllDigits(a) && isAllDigits(b)) {
            return Long.parseLong(a) == Long.parseLong(b);
        }
        return a.equals(b);
    }

    /** FICO comparison of the submitted numeric string against the stored {@link Integer}. */
    private static boolean ficoEquals(String submitted, Integer current) {
        String t = trim(submitted);
        if (!isAllDigits(t) || current == null) {
            return false;
        }
        return Integer.parseInt(t) == current.intValue();
    }

    /** Concatenates the trimmed SSN parts into a digit string ({@code ""} when absent). */
    private static String ssnDigits(SsnParts ssn) {
        if (ssn == null) {
            return "";
        }
        return trim(ssn.part1()) + trim(ssn.part2()) + trim(ssn.part3());
    }

    /** Concatenates the trimmed phone parts into a digit string ({@code ""} when absent). */
    private static String phoneDigits(PhoneParts phone) {
        if (phone == null) {
            return "";
        }
        return trim(phone.area()) + trim(phone.prefix()) + trim(phone.line());
    }

    /** Extracts the ASCII digits from a value, treating {@code null} as blank. */
    private static String onlyDigits(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Canonical date compose that yields {@code ""} for absent or non-numeric parts. */
    private static String composeDateSafe(DateParts parts) {
        if (parts == null) {
            return "";
        }
        String yy = trim(parts.year());
        String mm = trim(parts.month());
        String dd = trim(parts.day());
        if (!isAllDigits(yy) || !isAllDigits(mm) || !isAllDigits(dd)) {
            return "";
        }
        return String.format("%s-%02d-%02d", yy, Integer.parseInt(mm), Integer.parseInt(dd));
    }

    // --------------------------------------------------------------------------------
    // 9600-WRITE-PROCESSING — apply validated ACUP-NEW-* values to the loaded entities
    // --------------------------------------------------------------------------------

    /**
     * Applies the validated account fields to the freshly-loaded {@link Account}, reproducing the
     * selective {@code MOVE}s of COBOL {@code 9600-WRITE-PROCESSING}. Monetary fields become
     * {@code BigDecimal} at scale 2 (via {@link Money}); dates are composed as {@code YYYY-MM-DD}.
     * Fields not touched by the COBOL write (notably {@code ACCT-ADDR-ZIP} and the optimistic-lock
     * version) retain their loaded values because the entity is read fresh before mutation.
     *
     * @param account the freshly-loaded account (mutated in place)
     * @param command the validated submitted values
     */
    private static void applyAccountUpdates(Account account, AccountUpdateCommand command) {
        account.setAcctActiveStatus(trim(command.accountStatus()));
        account.setCurrBal(parseSignedMoney(command.currentBalance()));
        account.setCreditLimit(parseSignedMoney(command.creditLimit()));
        account.setCashCreditLimit(parseSignedMoney(command.cashCreditLimit()));
        account.setCurrCycCredit(parseSignedMoney(command.currentCycleCredit()));
        account.setCurrCycDebit(parseSignedMoney(command.currentCycleDebit()));
        account.setAcctOpenDate(composeDate(command.openDate()));
        account.setAcctExpirationDate(composeDate(command.expiryDate()));
        account.setAcctReissueDate(composeDate(command.reissueDate()));
        // The group id is echoed (not edited) on the screen; preserve the loaded value when blank
        // so the disclosure-group foreign key is never cleared.
        String groupId = trim(command.groupId());
        if (!groupId.isEmpty()) {
            account.setGroupId(groupId);
        }
    }

    /**
     * Applies the validated customer fields to the freshly-loaded {@link Customer}, reproducing the
     * selective {@code MOVE}s of COBOL {@code 9600-WRITE-PROCESSING}: names, three address lines,
     * state, country, ZIP, both phones (composed as {@code (AAA)BBB-CCCC}), SSN (nine digits),
     * government id, date of birth, EFT account id, primary-cardholder indicator, and FICO score.
     * The SSN, government id, and date of birth are sensitive and are never logged.
     *
     * @param customer the freshly-loaded customer (mutated in place)
     * @param command  the validated submitted values
     */
    private static void applyCustomerUpdates(Customer customer, AccountUpdateCommand command) {
        customer.setCustFirstName(trim(command.firstName()));
        customer.setCustMiddleName(trim(command.middleName()));
        customer.setCustLastName(trim(command.lastName()));
        customer.setCustAddrLine1(trim(command.addressLine1()));
        customer.setCustAddrLine2(trim(command.addressLine2()));
        customer.setCustAddrLine3(trim(command.city()));
        customer.setCustAddrStateCd(trim(command.stateCode()));
        customer.setCustAddrCountryCd(trim(command.countryCode()));
        customer.setCustAddrZip(trim(command.zipCode()));
        customer.setCustPhoneNum1(composePhone(command.phone1()));
        customer.setCustPhoneNum2(composePhone(command.phone2()));
        customer.setCustSsn(ssnDigits(command.ssn()));
        customer.setCustGovtIssuedId(trim(command.governmentId()));
        customer.setCustDob(composeDate(command.dob()));
        customer.setCustEftAccountId(trim(command.eftAccountId()));
        customer.setCustPriCardHolderInd(trim(command.primaryHolderFlag()));
        customer.setCustFicoCreditScore(Integer.valueOf(Integer.parseInt(trim(command.ficoScore()))));
    }

    /**
     * Parses a validated signed monetary string into a {@code BigDecimal} at scale 2 through the
     * {@link Money} value object (which applies {@code RoundingMode.HALF_UP}). The value has already
     * passed {@link #editSigned(EditState, String, String)}, so parsing cannot fail here.
     *
     * @param raw the validated signed monetary string
     * @return the value as {@code BigDecimal} at scale 2
     */
    private static BigDecimal parseSignedMoney(String raw) {
        return Money.of(normalizeSigned(trim(raw))).toBigDecimal();
    }

    /**
     * Null- and format-tolerant variant of {@link #parseSignedMoney(String)} for the no-change
     * comparison: returns {@code null} when the value is blank or not a valid signed decimal.
     *
     * @param raw the submitted monetary string (may be {@code null})
     * @return the value as {@code BigDecimal} at scale 2, or {@code null} when unparseable
     */
    private static BigDecimal parseSignedMoneySafe(String raw) {
        String t = trim(raw);
        if (t.isEmpty() || !isValidSignedDecimal(t)) {
            return null;
        }
        return Money.of(normalizeSigned(t)).toBigDecimal();
    }

    /**
     * Normalizes a signed decimal so a leading or trailing sign becomes a leading sign accepted by
     * {@link BigDecimal}, reproducing the COBOL acceptance of trailing-sign display numerics.
     *
     * @param value the trimmed signed decimal
     * @return the normalized value with an optional leading sign only
     */
    private static String normalizeSigned(String value) {
        if (value.endsWith("-")) {
            return "-" + value.substring(0, value.length() - 1);
        }
        if (value.endsWith("+")) {
            return value.substring(0, value.length() - 1);
        }
        if (value.startsWith("+")) {
            return value.substring(1);
        }
        return value;
    }

    /**
     * Composes a phone number as {@code (AAA)BBB-CCCC}, reproducing the COBOL
     * {@code STRING '(' ... ')' ... '-' ...} assembly. An absent (all-blank) phone becomes the empty
     * string so an optional phone is cleared rather than stored as punctuation.
     *
     * @param phone the phone parts (may be {@code null})
     * @return the composed phone string, or {@code ""} when absent
     */
    private static String composePhone(PhoneParts phone) {
        String area = phone == null ? "" : trim(phone.area());
        String prefix = phone == null ? "" : trim(phone.prefix());
        String line = phone == null ? "" : trim(phone.line());
        if (area.isEmpty() && prefix.isEmpty() && line.isEmpty()) {
            return "";
        }
        return "(" + area + ")" + prefix + "-" + line;
    }
}

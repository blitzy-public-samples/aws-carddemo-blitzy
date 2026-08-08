package com.carddemo.account.domain;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.domain.validation.AccountIdValidator;
import com.carddemo.account.domain.validation.AlphabeticOptionalValidator;
import com.carddemo.account.domain.validation.AlphabeticRequiredValidator;
import com.carddemo.account.domain.validation.CalendarDateValidator;
import com.carddemo.account.domain.validation.CreditScoreRangeValidator;
import com.carddemo.account.domain.validation.DateOfBirthValidator;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.domain.validation.MandatoryFieldValidator;
import com.carddemo.account.domain.validation.NumericRequiredValidator;
import com.carddemo.account.domain.validation.SignedDecimalValidator;
import com.carddemo.account.domain.validation.UsPhoneNumberValidator;
import com.carddemo.account.domain.validation.UsSocialSecurityNumberValidator;
import com.carddemo.account.domain.validation.UsStateCodeValidator;
import com.carddemo.account.domain.validation.UsStateZipPrefixValidator;
import com.carddemo.account.domain.validation.YesNoFlagValidator;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.AccountCustomerLinkEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.CustomerContextChanged;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.AccountCustomerLinkRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.cobol.CobolDateValidator;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edits one submitted account and customer pair, then stores both records and one outbox row.
 *
 * <p>Three paragraphs of {@code app/cbl/COACTUPC.cbl} run here.
 * {@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COACTUPC.cbl:L1429-L1678} runs the search-key
 * edit, the twenty-four field edits and the cross-field edit.
 * {@code 1205-COMPARE-OLD-NEW} at {@code app/cbl/COACTUPC.cbl:L1681-L1779} reports whether the
 * caller changed a value, and it runs ahead of every field edit.
 * {@code 9600-WRITE-PROCESSING} at {@code app/cbl/COACTUPC.cbl:L3888-L4104} reads both records
 * for update, applies the field moves and stores both rows.
 *
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} lives in {@link ConcurrentChangeDetector}. This class calls
 * it where {@code app/cbl/COACTUPC.cbl:L3947-L3948} performs it.
 *
 * <p>One pass carries one message. {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479} holds text only while
 * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480} reads true, so a
 * pass keeps the first message it produces and drops every later one. Every edit still runs.
 *
 * <p>The account row, the customer row and the outbox row commit together.
 * {@link #updateAccount} opens the transaction {@link OutboxWriter} requires and publishes
 * nothing.
 */
@Service
public class AccountUpdateService {

    // -----------------------------------------------------------------------------------------
    // Verdict text. Every literal is copied character for character from its condition name.
    // -----------------------------------------------------------------------------------------

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at {@code app/cbl/COACTUPC.cbl:L489-L490}, set at
     * {@code app/cbl/COACTUPC.cbl:L1442}.
     */
    public static final String NO_INPUT_RECEIVED = "No input received";

    /**
     * {@code 88 NO-CHANGES-DETECTED} at {@code app/cbl/COACTUPC.cbl:L491-L492}, set at
     * {@code app/cbl/COACTUPC.cbl:L1769}.
     */
    public static final String NO_CHANGE_DETECTED = "No change detected with respect to values fetched.";

    /**
     * {@code 88 COULD-NOT-LOCK-ACCT-FOR-UPDATE} at {@code app/cbl/COACTUPC.cbl:L517-L518}, set at
     * {@code app/cbl/COACTUPC.cbl:L3912}.
     */
    public static final String COULD_NOT_LOCK_ACCOUNT = "Could not lock account record for update";

    /**
     * {@code 88 COULD-NOT-LOCK-CUST-FOR-UPDATE} at {@code app/cbl/COACTUPC.cbl:L519-L520}, set at
     * {@code app/cbl/COACTUPC.cbl:L3939}.
     */
    public static final String COULD_NOT_LOCK_CUSTOMER = "Could not lock customer record for update";

    /**
     * The two messages that report a row this service could not lock.
     *
     * <p>A caller has to tell these apart from an edit that a submitted field failed, because the two
     * ask for opposite responses: a lock failure is worth retrying unchanged, and a failed edit is
     * not. Both texts come from {@code app/cbl/COACTUPC.cbl:L3907-L3915} and
     * {@code app/cbl/COACTUPC.cbl:L3934-L3942}, where the source reports them alongside its own
     * changed-record verdict.
     *
     * <p>The list is exposed rather than the two constants, so a caller cannot come to depend on one
     * and miss the other when a third lock is added.
     *
     * @return the two texts, in the order the source can produce them
     */
    public static List<String> lockFailureMessages() {
        return List.of(COULD_NOT_LOCK_ACCOUNT, COULD_NOT_LOCK_CUSTOMER);
    }

    /**
     * Fixed target-side refusal when the submitted account and customer do not name one
     * relationship held by {@code account_customer_link}. The source derives the customer
     * identifier from the cross-reference and never lets the caller choose it independently.
     */
    static final String ACCOUNT_CUSTOMER_RELATIONSHIP_NOT_FOUND =
            "Account and customer do not name one stored relationship";

    /** Message returned when submitted keys name records outside the fetched pair. */
    static final String IDENTIFIER_OWNERSHIP_MISMATCH =
            "Submitted identifiers do not match the fetched records.";

    // -----------------------------------------------------------------------------------------
    // Edit labels, in the order the container paragraph moves them. Each label opens the message
    // its edit writes, so the text is part of the contract a caller reads.
    // -----------------------------------------------------------------------------------------

    /** Label of edit 1, moved at {@code app/cbl/COACTUPC.cbl:L1472}. */
    static final String ACCOUNT_STATUS_LABEL = "Account Status";

    /** Label of edit 2, moved at {@code app/cbl/COACTUPC.cbl:L1478}. */
    static final String OPEN_DATE_LABEL = "Open Date";

    /** Label of edit 3, moved at {@code app/cbl/COACTUPC.cbl:L1484}. */
    static final String CREDIT_LIMIT_LABEL = "Credit Limit";

    /** Label of edit 4, moved at {@code app/cbl/COACTUPC.cbl:L1490}. */
    static final String EXPIRY_DATE_LABEL = "Expiry Date";

    /** Label of edit 5, moved at {@code app/cbl/COACTUPC.cbl:L1496}. */
    static final String CASH_CREDIT_LIMIT_LABEL = "Cash Credit Limit";

    /** Label of edit 6, moved at {@code app/cbl/COACTUPC.cbl:L1503}. */
    static final String REISSUE_DATE_LABEL = "Reissue Date";

    /** Label of edit 7, moved at {@code app/cbl/COACTUPC.cbl:L1509}. */
    static final String CURRENT_BALANCE_LABEL = "Current Balance";

    /**
     * Label of edit 8, moved at {@code app/cbl/COACTUPC.cbl:L1515}. The literal there is
     * {@code 'Current Cycle Credit Limit'} and holds twenty-six characters, while
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53} holds
     * twenty-five. The move drops the final letter, and the trim at every message site cannot
     * restore it, so each message under this label opens with these twenty-five characters. The
     * field holds a running total and the label names a limit.
     */
    static final String CURRENT_CYCLE_CREDIT_LABEL = "Current Cycle Credit Limi";

    /**
     * Label of edit 9, moved at {@code app/cbl/COACTUPC.cbl:L1522}. The literal holds exactly
     * twenty-five characters, so the move keeps every one. The field holds a running total and
     * the label names a limit.
     */
    static final String CURRENT_CYCLE_DEBIT_LABEL = "Current Cycle Debit Limit";

    /** Label of edit 11, moved at {@code app/cbl/COACTUPC.cbl:L1533}. */
    static final String DATE_OF_BIRTH_LABEL = "Date of Birth";

    /** Label of edit 12, moved at {@code app/cbl/COACTUPC.cbl:L1545}, for the credit score. */
    static final String FICO_SCORE_LABEL = "FICO Score";

    /** Label of edit 13, moved at {@code app/cbl/COACTUPC.cbl:L1560}. */
    static final String FIRST_NAME_LABEL = "First Name";

    /** Label of edit 14, moved at {@code app/cbl/COACTUPC.cbl:L1568}. */
    static final String MIDDLE_NAME_LABEL = "Middle Name";

    /** Label of edit 15, moved at {@code app/cbl/COACTUPC.cbl:L1576}. */
    static final String LAST_NAME_LABEL = "Last Name";

    /** Label of edit 16, moved at {@code app/cbl/COACTUPC.cbl:L1584}. */
    static final String ADDRESS_LINE_1_LABEL = "Address Line 1";

    /** Label of edit 17, moved at {@code app/cbl/COACTUPC.cbl:L1592}. */
    static final String STATE_LABEL = "State";

    /** Label of edit 18, moved at {@code app/cbl/COACTUPC.cbl:L1605}. */
    static final String ZIP_LABEL = "Zip";

    /**
     * Label of edit 19, moved at {@code app/cbl/COACTUPC.cbl:L1615}. The edit reads
     * {@code CUST-ADDR-LINE-3} at {@code app/cbl/COACTUPC.cbl:L1616}, which the entity holds as
     * the city. {@code app/cbl/COACTUPC.cbl:L1613} marks address line 2 optional and
     * {@code app/cbl/COACTUPC.cbl:L1614} leaves its label move commented out, so no edit reads
     * that field.
     */
    static final String CITY_LABEL = "City";

    /** Label of edit 20, moved at {@code app/cbl/COACTUPC.cbl:L1623}. */
    static final String COUNTRY_LABEL = "Country";

    /** Label of edit 21, moved at {@code app/cbl/COACTUPC.cbl:L1632}. */
    static final String PHONE_NUMBER_1_LABEL = "Phone Number 1";

    /** Label of edit 22, moved at {@code app/cbl/COACTUPC.cbl:L1640}. */
    static final String PHONE_NUMBER_2_LABEL = "Phone Number 2";

    /** Label of edit 23, moved at {@code app/cbl/COACTUPC.cbl:L1648}. */
    static final String EFT_ACCOUNT_ID_LABEL = "EFT Account Id";

    /** Label of edit 24, moved at {@code app/cbl/COACTUPC.cbl:L1657}. */
    static final String PRIMARY_CARD_HOLDER_LABEL = "Primary Card Holder";

    // -----------------------------------------------------------------------------------------
    // Widths, scales and slice positions.
    // -----------------------------------------------------------------------------------------

    /**
     * Width edit 18 covers, moved at {@code app/cbl/COACTUPC.cbl:L1607}. The field is
     * {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14}, so the edit reads its
     * first five characters alone.
     */
    private static final int ZIP_EDIT_WIDTH = 5;

    /**
     * Scale of a whole-number field. {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L22} carries no fractional digit.
     */
    private static final int INTEGRAL_SCALE = 0;

    /** First index of the year in a stored ten-character date. */
    private static final int DATE_YEAR_BEGIN_INDEX = 0;

    /** Index past the year in a stored ten-character date. */
    private static final int DATE_YEAR_END_INDEX = 4;

    /** First index of the month, which follows the separator at index 4. */
    private static final int DATE_MONTH_BEGIN_INDEX = 5;

    /** Index past the month. */
    private static final int DATE_MONTH_END_INDEX = 7;

    /** First index of the day, which follows the separator at index 7. */
    private static final int DATE_DAY_BEGIN_INDEX = 8;

    /** Index past the day. */
    private static final int DATE_DAY_END_INDEX = 10;

    /**
     * First index of the area code in a stored telephone number. The redefine at
     * {@code app/cbl/COACTUPC.cbl:L811-L819} places a literal open bracket at index 0.
     */
    private static final int PHONE_AREA_CODE_BEGIN_INDEX = 1;

    /** Index past the area code, where a literal close bracket sits. */
    private static final int PHONE_AREA_CODE_END_INDEX = 4;

    /** First index of the prefix. */
    private static final int PHONE_PREFIX_BEGIN_INDEX = 5;

    /** Index past the prefix, where a literal hyphen sits. */
    private static final int PHONE_PREFIX_END_INDEX = 8;

    /** First index of the line number. */
    private static final int PHONE_LINE_NUMBER_BEGIN_INDEX = 9;

    /**
     * Index past the line number. The two positions that follow are filler, from
     * {@code app/cbl/COACTUPC.cbl:L819}.
     */
    private static final int PHONE_LINE_NUMBER_END_INDEX = 13;

    /**
     * First index of part one of the Social Security Number, from
     * {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L831}.
     */
    private static final int SSN_PART_1_BEGIN_INDEX = 0;

    /** Index past part one, and the first index of part two. */
    private static final int SSN_PART_1_END_INDEX = 3;

    /** Index past part two, and the first index of part three. */
    private static final int SSN_PART_2_END_INDEX = 5;

    /** Index past part three. */
    private static final int SSN_PART_3_END_INDEX = 9;

    /** The character a fixed-width text field pads with. */
    private static final char SPACE = ' ';

    /** The character {@code LOW-VALUES} places in every position of a text field. */
    private static final char LOW_VALUE = '\u0000';

    /** The character a whole-number display field pads with on the left. */
    private static final String LEADING_ZERO = "0";

    /** The text a fixed-width field pads with on the right. */
    private static final String PADDING = " ";

    // -----------------------------------------------------------------------------------------
    // Collaborators.
    // -----------------------------------------------------------------------------------------

    /** Reads the account master row for update and stores it. */
    private final AccountRepository accountRepository;

    /** Reads the customer master row for update and stores it. */
    private final CustomerRepository customerRepository;

    /** Resolves the customer identifier the platform stores for one account. */
    private final AccountCustomerLinkRepository accountCustomerLinkRepository;

    /** Reports whether the stored records changed under the caller. */
    private final ConcurrentChangeDetector concurrentChangeDetector;

    /** Stores the one event row this update produces. */
    private final OutboxWriter outboxWriter;

    /** The explicit transaction boundary around the two rows and their outbox event. */
    private final TransactionTemplate transactionTemplate;

    /** Records only after that transaction has committed or failed. */
    private final AccountMeters meters;

    /**
     * Bound on how long each locked read of one update waits, as a PostgreSQL interval string.
     *
     * <p>Rendered once at construction from {@code carddemo.write.lock-wait-ms}, because it is the
     * same text on every request.
     */
    private final String lockWaitBound;

    /**
     * Takes the three repositories, the concurrency check, the outbox writer and the meter
     * registry.
     *
     * @param accountRepository        store of the account master row
     * @param customerRepository       store of the customer master row
     * @param accountCustomerLinkRepository service-local account-to-customer relationship table
     * @param concurrentChangeDetector the check at {@code app/cbl/COACTUPC.cbl:L3947-L3948}
     * @param outboxWriter             writer of the one event row, joining this transaction
     * @param transactionTemplate      boundary around one update attempt
     * @param meters                   recording surface used after the transaction completes
     * @param properties               the bound {@code carddemo} block, read for its lock-wait bound
     * @throws NullPointerException when an argument is {@code null}
     */
    public AccountUpdateService(AccountRepository accountRepository,
            CustomerRepository customerRepository,
            AccountCustomerLinkRepository accountCustomerLinkRepository,
            ConcurrentChangeDetector concurrentChangeDetector,
            OutboxWriter outboxWriter,
            TransactionTemplate transactionTemplate,
            AccountMeters meters,
            AccountProperties properties) {

        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must be present");
        this.customerRepository =
                Objects.requireNonNull(customerRepository, "customerRepository must be present");
        this.accountCustomerLinkRepository = Objects.requireNonNull(accountCustomerLinkRepository,
                "accountCustomerLinkRepository must be present");
        this.concurrentChangeDetector = Objects.requireNonNull(concurrentChangeDetector,
                "concurrentChangeDetector must be present");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must be present");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
        this.lockWaitBound = Objects.requireNonNull(properties, "properties must be present")
                .write().lockWaitMs() + "ms";
    }

    /**
     * Edits the submitted pair and, once every edit passes, stores both records and one event row.
     *
     * <p>The proposed pair holds {@code ACUP-NEW-ACCT-DATA} and {@code ACUP-NEW-CUST-DATA}. The
     * fetched pair holds {@code ACUP-OLD-ACCT-DATA} and {@code ACUP-OLD-CUST-DATA}, the copy the
     * caller was shown. This method reads both records again under a write lock, and the fetched
     * pair is what the concurrency check compares them against.
     *
     * <p>An absent fetched pair runs the search-key edit at
     * {@code app/cbl/COACTUPC.cbl:L1433-L1449} and returns. A pair that matches the fetched copy
     * returns {@link #NO_CHANGE_DETECTED} with no field edit and no write, from
     * {@code app/cbl/COACTUPC.cbl:L1463-L1467}. Every other pair reaches the field edits and,
     * on a clean pass, the write.
     *
     * <p>A failing edit is a reported message and never an exception. An exception leaving this
     * method marks a store fault, and the one transaction rolls back with it.
     *
     * <p>Neither proposed object is modified.
     *
     * @param proposedAccount  the account values the caller submits
     * @param proposedCustomer the customer values the caller submits
     * @param fetchedAccount   the account values the caller was shown; {@code null} when none was
     *                         fetched
     * @param fetchedCustomer  the customer values the caller was shown; {@code null} when none was
     *                         fetched
     * @return a passing verdict once both rows and the event row are written. It carries
     *         {@link #NO_CHANGE_DETECTED} when nothing changed. A failing verdict carries the
     *         first message the pass produced
     * @throws NullPointerException when either proposed object is {@code null}
     */
    public EditResult updateAccount(AccountEntity proposedAccount, CustomerEntity proposedCustomer,
            AccountEntity fetchedAccount, CustomerEntity fetchedCustomer) {

        Objects.requireNonNull(proposedAccount, "proposedAccount must be present");
        Objects.requireNonNull(proposedCustomer, "proposedCustomer must be present");

        long startedAt = System.nanoTime();
        try {
            TransactionResult result = Objects.requireNonNull(
                    transactionTemplate.execute(status -> updateWithinTransaction(
                            proposedAccount, proposedCustomer, fetchedAccount, fetchedCustomer)),
                    "the account update transaction must answer with a result");
            result.recordCommitted(meters, Duration.ofNanos(System.nanoTime() - startedAt));
            return result.verdict();
        } catch (LockNotTaken notTaken) {
            // A datastore that refuses a locking statement leaves a PostgreSQL transaction unusable,
            // so the outcome cannot be returned across the boundary and is carried out through it.
            // The row is untouched either way; this decides only what the caller is told.
            //
            // Counted exactly as the empty-result arm of writeProcessing is counted, which records
            // the latency and nothing else. The two arms answer the same message for the same reason,
            // so counting one of them as a service failure would make an operator read a contended
            // write as a defect.
            meters.recordUpdateLatency(Duration.ofNanos(System.nanoTime() - startedAt));
            return EditResult.failure(notTaken.getMessage());
        } catch (RuntimeException failure) {
            meters.recordUpdateFailure();
            throw failure;
        }
    }

    /**
     * Resolves the customer identifier from the account-to-customer link and checks both copies the
     * caller submitted against it.
     *
     * <p>The source reads the cross-reference before the customer record. This check restores that
     * authority boundary without a synchronous call to another service, and it reads the pair alone:
     * the table holds no card number, because no query here reads a card. A missing relationship and
     * a mismatched relationship produce one fixed message that reveals no identifier.
     */
    private Optional<String> authoritativeCustomerId(AccountEntity proposedAccount,
            CustomerEntity proposedCustomer, AccountEntity fetchedAccount,
            CustomerEntity fetchedCustomer) {

        String proposedAccountId = proposedAccount.getAccountId();
        String fetchedAccountId = fetchedAccount.getAccountId();
        String proposedCustomerId = proposedCustomer.getCustomerId();
        String fetchedCustomerId = fetchedCustomer.getCustomerId();
        if (proposedAccountId == null || !proposedAccountId.equals(fetchedAccountId)
                || proposedCustomerId == null || fetchedCustomerId == null) {
            return Optional.empty();
        }

        return accountCustomerLinkRepository
                .findByAccountId(proposedAccountId)
                .map(AccountCustomerLinkEntity::getCustomerId)
                .filter(proposedCustomerId::equals)
                .filter(fetchedCustomerId::equals);
    }

    /**
     * Runs the edit and write path inside the explicit transaction boundary.
     *
     * @param proposedAccount  the account values the caller submits
     * @param proposedCustomer the customer values the caller submits
     * @param fetchedAccount   the account values the caller was shown
     * @param fetchedCustomer  the customer values the caller was shown
     * @return the verdict and the bounded metric outcomes to record after commit
     */
    private TransactionResult updateWithinTransaction(AccountEntity proposedAccount,
            CustomerEntity proposedCustomer, AccountEntity fetchedAccount,
            CustomerEntity fetchedCustomer) {

        // app/cbl/COACTUPC.cbl:L1433 tests ACUP-DETAILS-NOT-FETCHED and L1446 returns.
        if (fetchedAccount == null || fetchedCustomer == null) {
            EditResult result = editSearchKey(proposedAccount);
            return new TransactionResult(result, false, !result.valid());
        }

        // The path and fetched pair own both identifiers. Refuse a submitted key change before
        // field validation or any repository access can obscure that boundary.
        if (!submittedIdentifiersMatchFetched(proposedAccount, proposedCustomer,
                fetchedAccount, fetchedCustomer)) {
            return new TransactionResult(
                    EditResult.failure(IDENTIFIER_OWNERSHIP_MISMATCH), false, true);
        }

        // app/cbl/COACTUPC.cbl:L1460-L1461 runs ahead of every field edit.
        if (!proposedDiffersFromFetched(proposedAccount, proposedCustomer, fetchedAccount,
                fetchedCustomer)) {
            if (authoritativeCustomerId(proposedAccount, proposedCustomer, fetchedAccount,
                    fetchedCustomer).isEmpty()) {
                return new TransactionResult(
                        EditResult.failure(ACCOUNT_CUSTOMER_RELATIONSHIP_NOT_FOUND), false, true);
            }
            // app/cbl/COACTUPC.cbl:L1466 clears the non-key flags and L1467 returns.
            return new TransactionResult(new EditResult(true, NO_CHANGE_DETECTED), false, false);
        }

        // app/cbl/COACTUPC.cbl:L1470-L1676.
        EditResult fieldEdits = editMapInputs(proposedAccount, proposedCustomer);
        if (!fieldEdits.valid()) {
            return new TransactionResult(fieldEdits, false, true);
        }

        Optional<String> authoritativeCustomerId = authoritativeCustomerId(proposedAccount,
                proposedCustomer, fetchedAccount, fetchedCustomer);
        if (authoritativeCustomerId.isEmpty()) {
            return new TransactionResult(
                    EditResult.failure(ACCOUNT_CUSTOMER_RELATIONSHIP_NOT_FOUND), false, true);
        }

        // app/cbl/COACTUPC.cbl:L3888-L4104.
        EditResult written = writeProcessing(proposedAccount, proposedCustomer, fetchedAccount,
                fetchedCustomer, authoritativeCustomerId.get());
        boolean applied = written.valid() && !written.hasMessage();
        return new TransactionResult(written, applied, false);
    }

    /** Reports whether the submitted pair keeps the identifiers of the pair the caller fetched. */
    private static boolean submittedIdentifiersMatchFetched(AccountEntity proposedAccount,
            CustomerEntity proposedCustomer, AccountEntity fetchedAccount,
            CustomerEntity fetchedCustomer) {
        return Objects.equals(proposedAccount.getAccountId(), fetchedAccount.getAccountId())
                && Objects.equals(proposedCustomer.getCustomerId(),
                        fetchedCustomer.getCustomerId());
    }

    /**
     * Runs the search-key edit of {@code app/cbl/COACTUPC.cbl:L1433-L1449}.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1435-L1436} performs the account edit, and that edit is the
     * only one on this path. {@code app/cbl/COACTUPC.cbl:L1441-L1442} then sets
     * {@code NO-SEARCH-CRITERIA-RECEIVED} for a blank filter, and that {@code SET} carries no
     * {@code WS-RETURN-MSG-OFF} guard, so it replaces the text
     * {@code app/cbl/COACTUPC.cbl:L1792} wrote.
     *
     * @param proposedAccount the account values the caller submits
     * @return the verdict of the account edit, or a failing verdict carrying
     *         {@link #NO_INPUT_RECEIVED} for a blank identifier
     */
    private static EditResult editSearchKey(AccountEntity proposedAccount) {
        EditResult accountFilter = AccountIdValidator.validate(proposedAccount.getAccountId());

        // app/cbl/COACTUPC.cbl:L1790 sets FLG-ACCTFILTER-BLANK in the not-supplied branch alone.
        if (isNotSupplied(proposedAccount.getAccountId())) {
            return EditResult.failure(NO_INPUT_RECEIVED);
        }

        return accountFilter;
    }

    /**
     * Runs the twenty-four field edits and the cross-field edit, in the order the container
     * paragraph runs them, from {@code app/cbl/COACTUPC.cbl:L1470-L1676}.
     *
     * <p>Four gates sit in this sequence. The date-of-birth check runs behind
     * {@code app/cbl/COACTUPC.cbl:L1539}, the credit-score range behind
     * {@code app/cbl/COACTUPC.cbl:L1553}, the state-code lookup behind
     * {@code app/cbl/COACTUPC.cbl:L1599} and the state-with-postal-prefix lookup behind
     * {@code app/cbl/COACTUPC.cbl:L1665-L1666}. A fifth gate holds parts two and three of the
     * Social Security Number behind part one, and it lives inside
     * {@link UsSocialSecurityNumberValidator} at {@code app/cbl/COACTUPC.cbl:L2448}.
     *
     * <p>The gate at {@code app/cbl/COACTUPC.cbl:L1599} reads {@code FLG-ALPHA-ISVALID}, the flag
     * the alphabetic edit sets at {@code app/cbl/COACTUPC.cbl:L1949}, which holds the state result
     * only while edit 17 is the last alphabetic edit to run. Here each validator returns its own
     * verdict and each gate reads that returned value.
     *
     * @param proposedAccount  the account values the caller submits
     * @param proposedCustomer the customer values the caller submits
     * @return the verdict of {@code app/cbl/COACTUPC.cbl:L1671-L1675}
     */
    private static EditResult editMapInputs(AccountEntity proposedAccount,
            CustomerEntity proposedCustomer) {

        ValidationPass pass = new ValidationPass();

        // 1. app/cbl/COACTUPC.cbl:L1472-L1476.
        pass.record(YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL,
                proposedAccount.getActiveStatus()));

        // 2. app/cbl/COACTUPC.cbl:L1478-L1482.
        pass.record(CalendarDateValidator.validate(OPEN_DATE_LABEL,
                editDateField(proposedAccount.getOpenDate(), PicClause.ACCT_OPEN_DATE_WIDTH)));

        // 3. app/cbl/COACTUPC.cbl:L1484-L1488.
        pass.record(SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL,
                amountText(proposedAccount.getCreditLimit(),
                        PicClause.ACCT_CREDIT_LIMIT_SCALE)));

        // 4. app/cbl/COACTUPC.cbl:L1490-L1494.
        pass.record(CalendarDateValidator.validate(EXPIRY_DATE_LABEL,
                editDateField(proposedAccount.getExpirationDate(),
                        PicClause.ACCT_EXPIRATION_DATE_WIDTH)));

        // 5. app/cbl/COACTUPC.cbl:L1496-L1501.
        pass.record(SignedDecimalValidator.validate(CASH_CREDIT_LIMIT_LABEL,
                amountText(proposedAccount.getCashCreditLimit(),
                        PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE)));

        // 6. app/cbl/COACTUPC.cbl:L1503-L1507.
        pass.record(CalendarDateValidator.validate(REISSUE_DATE_LABEL,
                editDateField(proposedAccount.getReissueDate(),
                        PicClause.ACCT_REISSUE_DATE_WIDTH)));

        // 7. app/cbl/COACTUPC.cbl:L1509-L1513.
        pass.record(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL,
                amountText(proposedAccount.getCurrentBalance(), PicClause.ACCT_CURR_BAL_SCALE)));

        // 8. app/cbl/COACTUPC.cbl:L1515-L1520.
        pass.record(SignedDecimalValidator.validate(CURRENT_CYCLE_CREDIT_LABEL,
                amountText(proposedAccount.getCurrentCycleCredit(),
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE)));

        // 9. app/cbl/COACTUPC.cbl:L1522-L1527.
        pass.record(SignedDecimalValidator.validate(CURRENT_CYCLE_DEBIT_LABEL,
                amountText(proposedAccount.getCurrentCycleDebit(),
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE)));

        // 10. app/cbl/COACTUPC.cbl:L1529-L1531. L1529 moves the label 'SSN', and
        // app/cbl/COACTUPC.cbl:L2439, L2469 and L2481 each replace it before a message leaves the
        // paragraph, so no message carries it. The validator reads the three parts.
        String socialSecurityNumber = atDeclaredWidth(proposedCustomer.getSocialSecurityNumber(),
                PicClause.CUST_SSN_WIDTH);
        pass.record(UsSocialSecurityNumberValidator.validate(
                socialSecurityNumber.substring(SSN_PART_1_BEGIN_INDEX, SSN_PART_1_END_INDEX),
                socialSecurityNumber.substring(SSN_PART_1_END_INDEX, SSN_PART_2_END_INDEX),
                socialSecurityNumber.substring(SSN_PART_2_END_INDEX, SSN_PART_3_END_INDEX)));

        // 11. app/cbl/COACTUPC.cbl:L1533-L1543, with the gate at L1539 and its END-IF at L1543.
        String dateOfBirth =
                editDateField(proposedCustomer.getDateOfBirth(), PicClause.CUST_DOB_WIDTH);
        if (pass.record(CalendarDateValidator.validate(DATE_OF_BIRTH_LABEL, dateOfBirth))) {
            pass.record(DateOfBirthValidator.validate(DATE_OF_BIRTH_LABEL, dateOfBirth));
        }

        // 12. app/cbl/COACTUPC.cbl:L1545-L1556, with the gate at L1553 and its END-IF at L1556.
        String creditScore = creditScoreText(proposedCustomer.getFicoCreditScore());
        if (pass.record(NumericRequiredValidator.validate(FICO_SCORE_LABEL, creditScore,
                PicClause.CUST_FICO_CREDIT_SCORE_WIDTH))) {
            pass.record(CreditScoreRangeValidator.validate(FICO_SCORE_LABEL, creditScore));
        }

        // 13. app/cbl/COACTUPC.cbl:L1560-L1566.
        pass.record(AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL,
                proposedCustomer.getFirstName(), PicClause.CUST_FIRST_NAME_WIDTH));

        // 14. app/cbl/COACTUPC.cbl:L1568-L1574.
        pass.record(AlphabeticOptionalValidator.validate(MIDDLE_NAME_LABEL,
                proposedCustomer.getMiddleName(), PicClause.CUST_MIDDLE_NAME_WIDTH));

        // 15. app/cbl/COACTUPC.cbl:L1576-L1582.
        pass.record(AlphabeticRequiredValidator.validate(LAST_NAME_LABEL,
                proposedCustomer.getLastName(), PicClause.CUST_LAST_NAME_WIDTH));

        // 16. app/cbl/COACTUPC.cbl:L1584-L1590, the one call site of this edit.
        pass.record(MandatoryFieldValidator.validate(ADDRESS_LINE_1_LABEL,
                proposedCustomer.getAddressLine1(), PicClause.CUST_ADDR_LINE_1_WIDTH));

        // 17. app/cbl/COACTUPC.cbl:L1592-L1602, with the gate at L1599 and its END-IF at L1602.
        boolean stateCodeAccepted = pass.record(AlphabeticRequiredValidator.validate(STATE_LABEL,
                proposedCustomer.getAddressStateCode(), PicClause.CUST_ADDR_STATE_CD_WIDTH));
        if (stateCodeAccepted) {
            stateCodeAccepted = pass.record(UsStateCodeValidator.validate(STATE_LABEL,
                    proposedCustomer.getAddressStateCode()));
        }

        // 18. app/cbl/COACTUPC.cbl:L1605-L1611.
        boolean postalCodeAccepted = pass.record(NumericRequiredValidator.validate(ZIP_LABEL,
                proposedCustomer.getAddressZip(), ZIP_EDIT_WIDTH));

        // 19. app/cbl/COACTUPC.cbl:L1615-L1621.
        pass.record(AlphabeticRequiredValidator.validate(CITY_LABEL,
                proposedCustomer.getAddressCity(), PicClause.CUST_ADDR_LINE_3_WIDTH));

        // 20. app/cbl/COACTUPC.cbl:L1623-L1630.
        pass.record(AlphabeticRequiredValidator.validate(COUNTRY_LABEL,
                proposedCustomer.getAddressCountryCode(), PicClause.CUST_ADDR_COUNTRY_CD_WIDTH));

        // 21. app/cbl/COACTUPC.cbl:L1632-L1638.
        pass.record(editTelephoneNumber(PHONE_NUMBER_1_LABEL, proposedCustomer.getPhoneNumber1(),
                PicClause.CUST_PHONE_NUM_1_WIDTH));

        // 22. app/cbl/COACTUPC.cbl:L1640-L1646.
        pass.record(editTelephoneNumber(PHONE_NUMBER_2_LABEL, proposedCustomer.getPhoneNumber2(),
                PicClause.CUST_PHONE_NUM_2_WIDTH));

        // 23. app/cbl/COACTUPC.cbl:L1648-L1655.
        pass.record(NumericRequiredValidator.validate(EFT_ACCOUNT_ID_LABEL,
                proposedCustomer.getEftAccountId(), PicClause.CUST_EFT_ACCOUNT_ID_WIDTH));

        // 24. app/cbl/COACTUPC.cbl:L1657-L1662.
        pass.record(YesNoFlagValidator.validate(PRIMARY_CARD_HOLDER_LABEL,
                proposedCustomer.getPrimaryCardHolderIndicator()));

        // Cross-field edit, app/cbl/COACTUPC.cbl:L1664-L1669. The container paragraph invokes it,
        // and no field edit does. The validator builds the four-character key at
        // app/cbl/COACTUPC.cbl:L2537-L2540 and marks both fields on a failure at L2546 and L2547.
        if (stateCodeAccepted && postalCodeAccepted) {
            pass.record(UsStateZipPrefixValidator.validate(proposedCustomer.getAddressStateCode(),
                    proposedCustomer.getAddressZip()));
        }

        return pass.verdict();
    }

    /**
     * Runs the telephone edit of {@code app/cbl/COACTUPC.cbl:L2225-L2427} over the three parts the
     * redefine at {@code app/cbl/COACTUPC.cbl:L811-L819} names.
     *
     * <p>The stored field holds the form {@code (999)999-9999}. The area code, the prefix and the
     * line number therefore sit at fixed positions, and the bracket and hyphen positions belong to
     * no part.
     *
     * @param fieldLabel      the label the message opens with
     * @param telephoneNumber the stored fifteen-character field; may be {@code null}
     * @param declaredWidth   the width the picture clause declares
     * @return the verdict the telephone edit produced
     */
    private static EditResult editTelephoneNumber(String fieldLabel, String telephoneNumber,
            int declaredWidth) {

        String field = atDeclaredWidth(telephoneNumber, declaredWidth);
        return UsPhoneNumberValidator.validate(fieldLabel,
                field.substring(PHONE_AREA_CODE_BEGIN_INDEX, PHONE_AREA_CODE_END_INDEX),
                field.substring(PHONE_PREFIX_BEGIN_INDEX, PHONE_PREFIX_END_INDEX),
                field.substring(PHONE_LINE_NUMBER_BEGIN_INDEX, PHONE_LINE_NUMBER_END_INDEX));
    }

    /**
     * Reports whether the caller changed a value, from
     * {@code app/cbl/COACTUPC.cbl:L1681-L1779}.
     *
     * <p>Two conditions run there. The first holds eleven account terms at
     * {@code app/cbl/COACTUPC.cbl:L1684-L1700} and the second holds eighteen customer terms at
     * {@code app/cbl/COACTUPC.cbl:L1708-L1768}. A term that differs sets
     * {@code CHANGE-HAS-OCCURRED} and leaves the paragraph, so one differing term is enough.
     *
     * <p>{@link ConcurrentChangeDetector} answers a different question over an overlapping field
     * set, and the two comparisons differ on nine of those fields.
     *
     * @param proposedAccount  the account values the caller submits
     * @param proposedCustomer the customer values the caller submits
     * @param fetchedAccount   the account values the caller was shown
     * @param fetchedCustomer  the customer values the caller was shown
     * @return true when at least one term differs
     */
    private static boolean proposedDiffersFromFetched(AccountEntity proposedAccount,
            CustomerEntity proposedCustomer, AccountEntity fetchedAccount,
            CustomerEntity fetchedCustomer) {

        return !accountMatchesFetched(proposedAccount, fetchedAccount)
                || !customerMatchesFetched(proposedCustomer, fetchedCustomer);
    }

    /**
     * Compares the eleven account terms of {@code app/cbl/COACTUPC.cbl:L1684-L1700}. Each term
     * below carries the source line it reproduces.
     *
     * @param proposed the account values the caller submits
     * @param fetched  the account values the caller was shown
     * @return true when all eleven terms match
     */
    private static boolean accountMatchesFetched(AccountEntity proposed, AccountEntity fetched) {

        return
                // L1684 ACUP-NEW-ACCT-ID-X = ACUP-OLD-ACCT-ID-X
                matchesAsSupplied(proposed.getAccountId(), fetched.getAccountId(),
                        PicClause.ACCT_ID_WIDTH)
                // L1685-L1688 FUNCTION UPPER-CASE on both sides, with no trim
                && matchesFoldedToUpperCase(proposed.getActiveStatus(), fetched.getActiveStatus(),
                        PicClause.ACCT_ACTIVE_STATUS_WIDTH)
                // L1689 ACUP-NEW-CURR-BAL = ACUP-OLD-CURR-BAL
                && matchesNumerically(proposed.getCurrentBalance(), fetched.getCurrentBalance(),
                        PicClause.ACCT_CURR_BAL_SCALE)
                // L1690 ACUP-NEW-CREDIT-LIMIT = ACUP-OLD-CREDIT-LIMIT
                && matchesNumerically(proposed.getCreditLimit(), fetched.getCreditLimit(),
                        PicClause.ACCT_CREDIT_LIMIT_SCALE)
                // L1691 ACUP-NEW-CASH-CREDIT-LIMIT = ACUP-OLD-CASH-CREDIT-LIMIT
                && matchesNumerically(proposed.getCashCreditLimit(), fetched.getCashCreditLimit(),
                        PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE)
                // L1692 ACUP-NEW-OPEN-DATE = ACUP-OLD-OPEN-DATE, the whole field
                && matchesAsSupplied(proposed.getOpenDate(), fetched.getOpenDate(),
                        PicClause.ACCT_OPEN_DATE_WIDTH)
                // L1693 ACUP-NEW-EXPIRAION-DATE = ACUP-OLD-EXPIRAION-DATE, the whole field
                && matchesAsSupplied(proposed.getExpirationDate(), fetched.getExpirationDate(),
                        PicClause.ACCT_EXPIRATION_DATE_WIDTH)
                // L1694 ACUP-NEW-REISSUE-DATE = ACUP-OLD-REISSUE-DATE, the whole field
                && matchesAsSupplied(proposed.getReissueDate(), fetched.getReissueDate(),
                        PicClause.ACCT_REISSUE_DATE_WIDTH)
                // L1695 ACUP-NEW-CURR-CYC-CREDIT = ACUP-OLD-CURR-CYC-CREDIT
                && matchesNumerically(proposed.getCurrentCycleCredit(),
                        fetched.getCurrentCycleCredit(), PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
                // L1696 ACUP-NEW-CURR-CYC-DEBIT = ACUP-OLD-CURR-CYC-DEBIT
                && matchesNumerically(proposed.getCurrentCycleDebit(),
                        fetched.getCurrentCycleDebit(), PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
                // L1697-L1700 FUNCTION UPPER-CASE over FUNCTION TRIM on both sides
                && matchesTrimmedAndFolded(proposed.getGroupId(), fetched.getGroupId());
    }

    /**
     * Compares the eighteen customer terms of {@code app/cbl/COACTUPC.cbl:L1708-L1768}. Each term
     * below carries the source line it reproduces. The two telephone numbers contribute three
     * terms each.
     *
     * @param proposed the customer values the caller submits
     * @param fetched  the customer values the caller was shown
     * @return true when all eighteen terms match
     */
    private static boolean customerMatchesFetched(CustomerEntity proposed, CustomerEntity fetched) {

        return
                // L1708-L1711 customer identifier, folded over a trim
                matchesTrimmedAndFolded(proposed.getCustomerId(), fetched.getCustomerId())
                // L1712-L1715
                && matchesTrimmedAndFolded(proposed.getFirstName(), fetched.getFirstName())
                // L1716-L1719
                && matchesTrimmedAndFolded(proposed.getMiddleName(), fetched.getMiddleName())
                // L1720-L1723
                && matchesTrimmedAndFolded(proposed.getLastName(), fetched.getLastName())
                // L1724-L1727
                && matchesTrimmedAndFolded(proposed.getAddressLine1(), fetched.getAddressLine1())
                // L1728-L1731 address line 2, compared here and edited by no paragraph
                && matchesTrimmedAndFolded(proposed.getAddressLine2(), fetched.getAddressLine2())
                // L1732-L1735 CUST-ADDR-LINE-3, which the entity holds as the city
                && matchesTrimmedAndFolded(proposed.getAddressCity(), fetched.getAddressCity())
                // L1736-L1739
                && matchesTrimmedAndFolded(proposed.getAddressStateCode(),
                        fetched.getAddressStateCode())
                // L1740-L1743
                && matchesTrimmedAndFolded(proposed.getAddressCountryCode(),
                        fetched.getAddressCountryCode())
                // L1744-L1747
                && matchesTrimmedAndFolded(proposed.getAddressZip(), fetched.getAddressZip())
                // L1748-L1750 the three parts of telephone number 1, each compared as supplied
                && matchesTelephoneParts(proposed.getPhoneNumber1(), fetched.getPhoneNumber1(),
                        PicClause.CUST_PHONE_NUM_1_WIDTH)
                // L1751-L1753 the three parts of telephone number 2
                && matchesTelephoneParts(proposed.getPhoneNumber2(), fetched.getPhoneNumber2(),
                        PicClause.CUST_PHONE_NUM_2_WIDTH)
                // L1754 ACUP-NEW-CUST-SSN-X = ACUP-OLD-CUST-SSN-X
                && matchesAsSupplied(proposed.getSocialSecurityNumber(),
                        fetched.getSocialSecurityNumber(), PicClause.CUST_SSN_WIDTH)
                // L1755-L1758 government-issued identifier, folded over a trim
                && matchesTrimmedAndFolded(proposed.getGovernmentIssuedId(),
                        fetched.getGovernmentIssuedId())
                // L1759-L1760 the date of birth, the whole field
                && matchesAsSupplied(proposed.getDateOfBirth(), fetched.getDateOfBirth(),
                        PicClause.CUST_DOB_WIDTH)
                // L1761-L1762
                && matchesAsSupplied(proposed.getEftAccountId(), fetched.getEftAccountId(),
                        PicClause.CUST_EFT_ACCOUNT_ID_WIDTH)
                // L1763-L1766
                && matchesTrimmedAndFolded(proposed.getPrimaryCardHolderIndicator(),
                        fetched.getPrimaryCardHolderIndicator())
                // L1767-L1768 the credit score, a whole number on both sides
                && matchesNumerically(proposed.getFicoCreditScore(), fetched.getFicoCreditScore(),
                        INTEGRAL_SCALE);
    }

    /**
     * Stores both records and one event row, from {@code app/cbl/COACTUPC.cbl:L3888-L4104}.
     *
     * <p>The two reads for update sit at {@code app/cbl/COACTUPC.cbl:L3894-L3903} and
     * {@code app/cbl/COACTUPC.cbl:L3921-L3930}, and a response other than normal reports the
     * matching lock message at {@code app/cbl/COACTUPC.cbl:L3912} and
     * {@code app/cbl/COACTUPC.cbl:L3939}. The concurrency check runs at
     * {@code app/cbl/COACTUPC.cbl:L3947-L3948} and its verdict returns at
     * {@code app/cbl/COACTUPC.cbl:L3950-L3952}. The field moves run at
     * {@code app/cbl/COACTUPC.cbl:L3960-L4059} and the two rewrites at
     * {@code app/cbl/COACTUPC.cbl:L4066} and {@code app/cbl/COACTUPC.cbl:L4086}.
     *
     * <p>One transaction covers the account row, the customer row and the outbox rows. A store
     * fault raises its exception and rolls them all back. The source sets
     * {@code LOCKED-BUT-UPDATE-FAILED} at {@code app/cbl/COACTUPC.cbl:L4079} with no rollback and
     * again at {@code app/cbl/COACTUPC.cbl:L4098} with the rollback at
     * {@code app/cbl/COACTUPC.cbl:L4099-L4101}.
     *
     * <p>An event is written for each record this call changed. A caller who submits a raised credit
     * limit and resubmits the customer values it was shown changes the account row alone, so one
     * {@code AccountStateChanged} is written and no {@code CustomerContextChanged}. The reverse case
     * writes the customer event alone. A caller reaches this paragraph only once the submitted pair
     * differs from the fetched pair, so at least one event is always written.
     *
     * @param proposedAccount  the account values the caller submits
     * @param proposedCustomer the customer values the caller submits
     * @param fetchedAccount   the account values the caller was shown
     * @param fetchedCustomer  the customer values the caller was shown
     * @param authoritativeCustomerId customer identifier derived from
     *                                {@code account_customer_link}
     * @return a passing verdict once both rows and the event row are written, otherwise a failing
     *         verdict carrying one message
     */
    private EditResult writeProcessing(AccountEntity proposedAccount,
            CustomerEntity proposedCustomer, AccountEntity fetchedAccount,
            CustomerEntity fetchedCustomer, String authoritativeCustomerId) {

        // Bound the wait before either locked read runs. PostgreSQL waits forever by default, so a
        // row another writer held kept this request open for as long as that writer held it, and the
        // two answers below were unreachable through contention: the wait ended in a lock or it did
        // not end. Transaction-local, so it governs these two reads and nothing else.
        accountRepository.applyLockWaitBound(lockWaitBound);

        // app/cbl/COACTUPC.cbl:L3892-L3903. A row that has gone returns empty and a lock the
        // datastore will not grant inside the bound raises, and app/cbl/COACTUPC.cbl:L3907 draws no
        // distinction between them: both are a response other than DFHRESP(NORMAL) to a READ UPDATE.
        Optional<AccountEntity> lockedAccount;
        try {
            lockedAccount =
                    accountRepository.findForUpdateByAccountId(proposedAccount.getAccountId());
        } catch (PessimisticLockingFailureException | JpaSystemException notTaken) {
            // app/cbl/COACTUPC.cbl:L3907-L3915.
            throw new LockNotTaken(COULD_NOT_LOCK_ACCOUNT, notTaken);
        }
        if (lockedAccount.isEmpty()) {
            // app/cbl/COACTUPC.cbl:L3907-L3915.
            return EditResult.failure(COULD_NOT_LOCK_ACCOUNT);
        }

        // app/cbl/COACTUPC.cbl:L3919-L3930. The source takes this identifier from the
        // cross-reference, and the caller cannot replace it with another customer identifier.
        Optional<CustomerEntity> lockedCustomer;
        try {
            lockedCustomer = customerRepository.findForUpdateByCustomerId(authoritativeCustomerId);
        } catch (PessimisticLockingFailureException | JpaSystemException notTaken) {
            // app/cbl/COACTUPC.cbl:L3934-L3942.
            throw new LockNotTaken(COULD_NOT_LOCK_CUSTOMER, notTaken);
        }
        if (lockedCustomer.isEmpty()) {
            // app/cbl/COACTUPC.cbl:L3934-L3942.
            return EditResult.failure(COULD_NOT_LOCK_CUSTOMER);
        }

        AccountEntity storedAccount = lockedAccount.get();
        CustomerEntity storedCustomer = lockedCustomer.get();

        // app/cbl/COACTUPC.cbl:L3947-L3948, and its verdict at L3950-L3952.
        if (concurrentChangeDetector.storedRecordChanged(storedAccount, storedCustomer,
                fetchedAccount, fetchedCustomer)) {
            return EditResult.failure(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE);
        }

        applyAccountFields(proposedAccount, storedAccount);
        applyCustomerFields(proposedCustomer, storedCustomer);

        // app/cbl/COACTUPC.cbl:L4066 and L4086.
        accountRepository.save(storedAccount);
        customerRepository.save(storedCustomer);

        // One event per record this call changed, and no event for a record it left as it stood.
        // The two rewrites at app/cbl/COACTUPC.cbl:L4066 and L4086 run unconditionally, and each
        // one writes back the values the caller submitted, so a record whose submitted values equal
        // its fetched values is rewritten with what it already held. An event announces a change of
        // state, and there is none to announce for such a record: a consumer that projected it
        // would rewrite its own copy with the values already in it, and a reader of the topic would
        // read a change that never happened.
        //
        // The comparison is the one app/cbl/COACTUPC.cbl:L1684-L1768 draws, per record rather than
        // over the pair. proposedDiffersFromFetched already held for the pair to reach this
        // paragraph, so at least one of these two writes runs and a committed write is never
        // silent. The concurrency check above has established that the stored pair still equals the
        // fetched pair, so a record differing from what the caller was shown is a record whose
        // columns this transaction changes.
        boolean accountChanged = !accountMatchesFetched(proposedAccount, fetchedAccount);
        boolean customerChanged = !customerMatchesFetched(proposedCustomer, fetchedCustomer);

        if (accountChanged) {
            outboxWriter.write(AccountStateChanged.of(storedAccount.getAccountId(),
                    AccountStateChanged.ChangeKind.ACCOUNT_UPDATED,
                    storedAccount.getCurrentBalance(), storedAccount.getCreditLimit(),
                    storedAccount.getCurrentCycleCredit(), storedAccount.getCurrentCycleDebit(),
                    storedAccount.getExpirationDate()));
        }
        if (customerChanged) {
            outboxWriter.writeCustomerContext(
                    CustomerContextChanged.of(storedAccount.getAccountId(),
                            storedCustomer.getFirstName(), storedCustomer.getMiddleName(),
                            storedCustomer.getLastName(), storedCustomer.getAddressLine1(),
                            storedCustomer.getAddressLine2(), storedCustomer.getAddressCity(),
                            storedCustomer.getAddressStateCode(),
                            storedCustomer.getAddressCountryCode(), storedCustomer.getAddressZip(),
                            storedCustomer.getFicoCreditScore()));
        }

        return EditResult.ok();
    }

    /**
     * Carries transactional outcomes to the caller so meters are touched only after commit.
     *
     * @param verdict           business result returned to the controller
     * @param applied           whether both rows and the outbox event were written
     * @param validationFailure whether a field edit rejected the request
     */
    static record TransactionResult(
            EditResult verdict,
            boolean applied,
            boolean validationFailure) {

        TransactionResult {
            Objects.requireNonNull(verdict, "verdict");
        }

        void recordCommitted(AccountMeters meters, Duration elapsed) {
            meters.recordUpdateLatency(elapsed);
            if (applied) {
                meters.recordUpdateApplied();
            }
            if (validationFailure) {
                meters.recordValidationFailure();
            }
        }
    }

    /**
     * Copies the ten account fields of {@code app/cbl/COACTUPC.cbl:L3960-L4002} onto the row read
     * for update. Each line below carries the move it reproduces.
     *
     * <p>The identifier is the key the read supplied, so no move sets it.
     * {@code app/cbl/COACTUPC.cbl:L3960-L4002} carries no move for {@code ACCT-ADDR-ZIP}, and this
     * method leaves the stored value of that column standing.
     *
     * @param proposed the account values the caller submits, read and never changed
     * @param stored   the row read for update, which this method updates
     */
    private static void applyAccountFields(AccountEntity proposed, AccountEntity stored) {

        // L3962
        stored.setActiveStatus(proposed.getActiveStatus());
        // L3964
        stored.setCurrentBalance(
                storedAmount(proposed.getCurrentBalance(), PicClause.ACCT_CURR_BAL_SCALE));
        // L3966
        stored.setCreditLimit(
                storedAmount(proposed.getCreditLimit(), PicClause.ACCT_CREDIT_LIMIT_SCALE));
        // L3968-L3969
        stored.setCashCreditLimit(storedAmount(proposed.getCashCreditLimit(),
                PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE));
        // L3971-L3972
        stored.setCurrentCycleCredit(storedAmount(proposed.getCurrentCycleCredit(),
                PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
        // L3974
        stored.setCurrentCycleDebit(storedAmount(proposed.getCurrentCycleDebit(),
                PicClause.ACCT_CURR_CYC_DEBIT_SCALE));
        // L3976-L3982 assemble the ten-character form the column holds
        stored.setOpenDate(proposed.getOpenDate());
        // L3984-L3990
        stored.setExpirationDate(proposed.getExpirationDate());
        // L3993-L4000
        stored.setReissueDate(proposed.getReissueDate());
        // L4002
        stored.setGroupId(proposed.getGroupId());
    }

    /**
     * Copies the seventeen customer fields of {@code app/cbl/COACTUPC.cbl:L4009-L4059} onto the row
     * read for update. Each line below carries the move it reproduces.
     *
     * <p>The identifier is the key the read supplied, so no move sets it. Neither the Social
     * Security Number nor the government-issued identifier reaches a message, a log line, an event
     * payload or an exception text from here.
     *
     * @param proposed the customer values the caller submits, read and never changed
     * @param stored   the row read for update, which this method updates
     */
    private static void applyCustomerFields(CustomerEntity proposed, CustomerEntity stored) {

        // L4010-L4011
        stored.setFirstName(proposed.getFirstName());
        // L4012-L4013
        stored.setMiddleName(proposed.getMiddleName());
        // L4014
        stored.setLastName(proposed.getLastName());
        // L4015-L4016
        stored.setAddressLine1(proposed.getAddressLine1());
        // L4017-L4018
        stored.setAddressLine2(proposed.getAddressLine2());
        // L4019-L4020 CUST-ADDR-LINE-3
        stored.setAddressCity(proposed.getAddressCity());
        // L4021-L4022
        stored.setAddressStateCode(proposed.getAddressStateCode());
        // L4023-L4024
        stored.setAddressCountryCode(proposed.getAddressCountryCode());
        // L4025
        stored.setAddressZip(proposed.getAddressZip());
        // L4027-L4033 assemble the fifteen-character form the column holds
        stored.setPhoneNumber1(proposed.getPhoneNumber1());
        // L4035-L4041
        stored.setPhoneNumber2(proposed.getPhoneNumber2());
        // L4044
        stored.setSocialSecurityNumber(proposed.getSocialSecurityNumber());
        // L4045-L4046
        stored.setGovernmentIssuedId(proposed.getGovernmentIssuedId());
        // L4047-L4052 assemble the ten-character form the column holds
        stored.setDateOfBirth(proposed.getDateOfBirth());
        // L4054-L4055
        stored.setEftAccountId(proposed.getEftAccountId());
        // L4056-L4057
        stored.setPrimaryCardHolderIndicator(proposed.getPrimaryCardHolderIndicator());
        // L4058-L4059
        stored.setFicoCreditScore(storedAmount(proposed.getFicoCreditScore(), INTEGRAL_SCALE));
    }

    // -----------------------------------------------------------------------------------------
    // Field readings. Each one turns a stored column into the operand the source edit reads.
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the eight characters {@code WS-EDIT-DATE-CCYYMMDD} holds from the ten a date column
     * holds.
     *
     * <p>The column carries a year, a separator, a month, a separator and a day. The redefine at
     * {@code app/cbl/COACTUPC.cbl:L772-L777} names four characters of year, two of month and two
     * of day with no separator between them, so this method drops the two separator positions.
     *
     * @param storedDate    the ten-character column value; may be {@code null}
     * @param declaredWidth the width the picture clause declares for the column
     * @return exactly {@value CobolDateValidator#EDIT_DATE_WIDTH} characters
     */
    private static String editDateField(String storedDate, int declaredWidth) {

        String field = atDeclaredWidth(storedDate, declaredWidth);

        return field.substring(DATE_YEAR_BEGIN_INDEX, DATE_YEAR_END_INDEX)
                + field.substring(DATE_MONTH_BEGIN_INDEX, DATE_MONTH_END_INDEX)
                + field.substring(DATE_DAY_BEGIN_INDEX, DATE_DAY_END_INDEX);
    }

    /**
     * Renders one amount as the display text the signed-decimal edit reads.
     *
     * <p>The source edits {@code ACUP-NEW-CREDIT-LIMIT} and its siblings, each declared
     * {@code PIC X(12)} at {@code app/cbl/COACTUPC.cbl:L763-L795} over a
     * {@code PIC S9(10)V99} redefine. An absent amount stands for the low values that field holds
     * before a caller supplies digits, and the edit reports it as not supplied.
     *
     * @param amount the column value; may be {@code null}
     * @param scale  the scale {@link PicClause} publishes for the field
     * @return the digits and the point the edit reads, or {@code null} for an absent amount
     */
    private static String amountText(BigDecimal amount, int scale) {

        if (amount == null) {
            return null;
        }

        return CobolDecimal.truncateToScale(amount, scale).toPlainString();
    }

    /**
     * Renders the credit score as the three characters
     * {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L845} holds.
     *
     * <p>A {@code PIC 9(03)} display field pads on the left with zeros, so a score of two digits
     * reaches the edit as three characters. A value that needs more than three characters is
     * returned whole, and the numeric edit reports it.
     *
     * @param creditScore the column value; may be {@code null}
     * @return three characters, a wider rendering, or {@code null} for an absent score
     */
    private static String creditScoreText(BigDecimal creditScore) {

        if (creditScore == null) {
            return null;
        }

        String digits =
                CobolDecimal.truncateToScale(creditScore, INTEGRAL_SCALE).toPlainString();
        int width = PicClause.CUST_FICO_CREDIT_SCORE_WIDTH;
        if (digits.length() >= width) {
            return digits;
        }

        return LEADING_ZERO.repeat(width - digits.length()) + digits;
    }

    // -----------------------------------------------------------------------------------------
    // Comparison and padding helpers. ConcurrentChangeDetector carries the same set for the
    // comparison it answers, and the two differ where the two source paragraphs differ.
    // -----------------------------------------------------------------------------------------

    /**
     * Compares two text fields with the case and the spacing each side carries.
     *
     * <p>Both operands reach the declared width first, so trailing padding never separates two
     * equal values. A leading space survives that step and separates them.
     *
     * @param proposed      the value the caller submits
     * @param fetched       the value the caller was shown
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return true when the two padded values match
     */
    private static boolean matchesAsSupplied(String proposed, String fetched, int declaredWidth) {

        return Objects.equals(atDeclaredWidth(proposed, declaredWidth),
                atDeclaredWidth(fetched, declaredWidth));
    }

    /**
     * Compares two text fields with both sides folded to upper case and neither side trimmed,
     * reproducing {@code FUNCTION UPPER-CASE} at {@code app/cbl/COACTUPC.cbl:L1685-L1688}.
     *
     * @param proposed      the value the caller submits
     * @param fetched       the value the caller was shown
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return true when the two folded values match
     */
    private static boolean matchesFoldedToUpperCase(String proposed, String fetched,
            int declaredWidth) {

        return Objects.equals(
                atDeclaredWidth(proposed, declaredWidth).toUpperCase(Locale.ROOT),
                atDeclaredWidth(fetched, declaredWidth).toUpperCase(Locale.ROOT));
    }

    /**
     * Compares two text fields with both sides trimmed and then folded to upper case, reproducing
     * {@code FUNCTION UPPER-CASE} over {@code FUNCTION TRIM} at
     * {@code app/cbl/COACTUPC.cbl:L1697-L1700} and at every folded customer term.
     *
     * <p>{@code FUNCTION TRIM} removes leading and trailing spaces, so padding on either end
     * never separates two equal values. {@link Locale#ROOT} fixes the fold, which keeps the verdict
     * the same on every host.
     *
     * @param proposed the value the caller submits
     * @param fetched  the value the caller was shown
     * @return true when the two trimmed and folded values match
     */
    private static boolean matchesTrimmedAndFolded(String proposed, String fetched) {

        return trimSpaces(proposed).toUpperCase(Locale.ROOT)
                .equals(trimSpaces(fetched).toUpperCase(Locale.ROOT));
    }

    /**
     * Compares two amounts by value at the declared scale.
     *
     * <p>{@link BigDecimal#compareTo} reads value alone, so two operands holding one amount at two
     * scales match. An absent operand counts as zero, the value a fixed-point field holds when it
     * carries no digits.
     *
     * @param proposed the amount the caller submits
     * @param fetched  the amount the caller was shown
     * @param scale    the scale {@link PicClause} publishes for the field
     * @return true when the two amounts hold one value
     */
    private static boolean matchesNumerically(BigDecimal proposed, BigDecimal fetched, int scale) {

        return comparableAmount(proposed, scale).compareTo(comparableAmount(fetched, scale)) == 0;
    }

    /**
     * Compares two telephone numbers through the area code, the prefix and the line number, from
     * {@code app/cbl/COACTUPC.cbl:L1748-L1753}. The bracket and hyphen positions belong to no part
     * and no comparison reads them.
     *
     * @param proposed      the value the caller submits
     * @param fetched       the value the caller was shown
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return true when all three part pairs match
     */
    private static boolean matchesTelephoneParts(String proposed, String fetched,
            int declaredWidth) {

        String proposedField = atDeclaredWidth(proposed, declaredWidth);
        String fetchedField = atDeclaredWidth(fetched, declaredWidth);

        return proposedField.substring(PHONE_AREA_CODE_BEGIN_INDEX, PHONE_AREA_CODE_END_INDEX)
                        .equals(fetchedField.substring(PHONE_AREA_CODE_BEGIN_INDEX,
                                PHONE_AREA_CODE_END_INDEX))
                && proposedField.substring(PHONE_PREFIX_BEGIN_INDEX, PHONE_PREFIX_END_INDEX)
                        .equals(fetchedField.substring(PHONE_PREFIX_BEGIN_INDEX,
                                PHONE_PREFIX_END_INDEX))
                && proposedField
                        .substring(PHONE_LINE_NUMBER_BEGIN_INDEX, PHONE_LINE_NUMBER_END_INDEX)
                        .equals(fetchedField.substring(PHONE_LINE_NUMBER_BEGIN_INDEX,
                                PHONE_LINE_NUMBER_END_INDEX));
    }

    /**
     * Returns an amount at the declared scale for a comparison. An absent amount becomes zero at
     * that scale.
     *
     * @param amount the amount to scale; may be {@code null}
     * @param scale  the scale {@link PicClause} publishes for the field
     * @return the scaled amount, never {@code null}
     */
    private static BigDecimal comparableAmount(BigDecimal amount, int scale) {

        return CobolDecimal.truncateToScale(amount == null ? BigDecimal.ZERO : amount, scale);
    }

    /**
     * Returns an amount at the declared scale for a column. An absent amount stays absent, and the
     * column that receives it reports its own state.
     *
     * @param amount the amount to scale; may be {@code null}
     * @param scale  the scale {@link PicClause} publishes for the field
     * @return the scaled amount, or {@code null} for an absent amount
     */
    private static BigDecimal storedAmount(BigDecimal amount, int scale) {

        return amount == null ? null : CobolDecimal.truncateToScale(amount, scale);
    }

    /**
     * Returns a value padded on the right to the width the field declares. An absent value becomes
     * that width in spaces, and a longer value is returned whole.
     *
     * @param value         the value to pad; may be {@code null}
     * @param declaredWidth the width {@link PicClause} publishes for the field
     * @return the padded value, never {@code null}
     */
    private static String atDeclaredWidth(String value, int declaredWidth) {

        String present = value == null ? "" : value;

        if (present.length() >= declaredWidth) {
            return present;
        }

        return present + PADDING.repeat(declaredWidth - present.length());
    }

    /**
     * Removes leading and trailing spaces, reproducing {@code FUNCTION TRIM}. A space is the one
     * character removed, so a tab survives.
     *
     * @param value the value to trim; may be {@code null}
     * @return the trimmed value, never {@code null}
     */
    private static String trimSpaces(String value) {

        if (value == null) {
            return "";
        }

        int begin = 0;
        int end = value.length();
        while (begin < end && value.charAt(begin) == SPACE) {
            begin++;
        }
        while (end > begin && value.charAt(end - 1) == SPACE) {
            end--;
        }

        return value.substring(begin, end);
    }

    /**
     * Answers the two-arm not-supplied test of {@code app/cbl/COACTUPC.cbl:L1787-L1788}. An absent
     * value and an empty value stand for {@code LOW-VALUES}, and so does a field carrying the null
     * character in every position. A field of spaces answers {@code EQUAL SPACES}.
     *
     * @param value the value to test; may be {@code null}
     * @return true when the field arrived with no content
     */
    private static boolean isNotSupplied(String value) {

        return value == null || value.isEmpty() || isEveryCharacter(value, LOW_VALUE)
                || isEveryCharacter(value, SPACE);
    }

    /**
     * Reports whether every position of a value carries one character.
     *
     * @param value     the value to read, never {@code null}
     * @param character the character every position must carry
     * @return true when the value carries that character throughout
     */
    private static boolean isEveryCharacter(String value, char character) {

        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != character) {
                return false;
            }
        }

        return true;
    }

    /**
     * One validation pass: the single message slot and the input-error flag.
     *
     * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479} carries text only
     * while {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480} reads
     * true, and {@code app/cbl/COACTUPC.cbl:L876} makes the slot available once per pass. The slot
     * keeps the first message a pass produces. {@code 88 INPUT-ERROR VALUE '1'} at
     * {@code app/cbl/COACTUPC.cbl:L173} accumulates over every edit.
     */
    private static final class ValidationPass {

        /** The message slot. An unset slot stands for the all-space state. */
        private String returnMessage;

        /** The input-error flag, which one failing edit sets for the whole pass. */
        private boolean inputError;

        /**
         * Records one verdict and reports whether that verdict passed.
         *
         * <p>A failing verdict sets the input-error flag. Its message reaches the slot only while
         * the slot is unset, so a later failing edit adds no text.
         *
         * @param verdict the verdict one edit produced
         * @return true when the edit accepted the value
         */
        private boolean record(EditResult verdict) {

            if (verdict.valid()) {
                return true;
            }

            inputError = true;
            if (returnMessage == null && verdict.hasMessage()) {
                returnMessage = verdict.message();
            }

            return false;
        }

        /**
         * Reports the pass outcome, from {@code app/cbl/COACTUPC.cbl:L1671-L1675}.
         *
         * @return a failing verdict carrying the retained message, or a passing verdict
         */
        private EditResult verdict() {

            return inputError ? EditResult.failure(returnMessage) : EditResult.ok();
        }
    }

    /**
     * Reports that a locked read could not take its row, carrying the message that names which row.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L3907} and {@code app/cbl/COACTUPC.cbl:L3934} both test a
     * {@code READ UPDATE} for a response other than {@code DFHRESP(NORMAL)} and draw no distinction
     * between the ways one can arrive. A row that has gone reaches this outcome by returning empty,
     * which needs no exception. A datastore that refuses the locking statement, whether because the
     * wait ran out, because it broke a deadlock, or because the privilege to lock was withdrawn,
     * raises instead, and the statement leaves a PostgreSQL transaction unusable, so the outcome
     * cannot be returned across the transaction boundary.
     *
     * <p>The message is one of the two texts {@code app/cbl/COACTUPC.cbl:L517-L520} declares, so a
     * caller reads which record could not be taken and nothing about why.
     */
    static final class LockNotTaken extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Carries one of the two source texts and the failure the locked read reported.
         *
         * @param message the source text naming which record could not be taken
         * @param cause   the failure, whose own message reaches no response body
         */
        LockNotTaken(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

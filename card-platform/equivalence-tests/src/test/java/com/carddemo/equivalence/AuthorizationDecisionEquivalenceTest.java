package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.AuthorizationService.Outcome;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.domain.CycleExposureReservation;
import com.carddemo.authorization.domain.ReplicaSynchronization;
import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.domain.RequestCaller;
import com.carddemo.authorization.domain.TransactionIdentifierSource;
import com.carddemo.authorization.domain.rules.AccountExistsRule;
import com.carddemo.authorization.domain.rules.AccountExpirationRule;
import com.carddemo.authorization.domain.rules.CardCrossReferenceRule;
import com.carddemo.authorization.domain.rules.CreditLimitRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.authorization.repository.ReplicaGapRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.equivalence.CopybookRecordParser.CardCrossReferenceRecord;
import com.carddemo.equivalence.CopybookRecordParser.DailyTransactionRecord;
import com.carddemo.events.DeclineReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the authorization service against the validation section of {@code CBTRN02C}.
 *
 * <p>The service is a synthesis. {@code app/cbl/CBTRN02C.cbl:L370-L378} chains the four reject
 * reasons of {@code 1500-A-LOOKUP-XREF} at {@code app/cbl/CBTRN02C.cbl:L380-L392} and
 * {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393-L422};
 * {@code app/cbl/COTRN02C.cbl} supplies the request fields and {@code app/cbl/COSGN00C.cbl} the
 * actor. {@code COPAUA0C} and transaction {@code CP00} are absent from CardDemo, so no assertion
 * reads a synchronous original. The basis is the fixtures and the program text, never a run.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Authorization decisions from CBTRN02C L370-L422")
class AuthorizationDecisionEquivalenceTest {

    /** Every record of {@code app/data/ASCII/dailytran.txt}, at the width the copybook declares. */
    private static final int FIXTURE_FEED_COUNT = PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT;

    /** Reject reasons no record of {@code app/data/ASCII/dailytran.txt} reaches. */
    private static final long UNREACHABLE_FROM_FIXTURES = 0L;

    /** Name of the run-total expectations this class cross-checks its own count against. */
    private static final String EXPECTED_SUMMARY_FILE = "posting-summary.csv";

    /** {@code entity_key} of the row holding the stateless decline count. */
    private static final String MODEL_A_ENTITY = "model_a";

    /** {@code expected_field} of the row holding the stateless decline count. */
    private static final String DECLINED_COUNT_FIELD = "declined_count";

    /**
     * The run totals of both evaluation models, read once.
     *
     * <p>{@code PostingEquivalenceTest} is the row-by-row consumer of this file and is what proves
     * no row of it goes unread. This class reads one row, {@link #MODEL_A_ENTITY} with
     * {@link #DECLINED_COUNT_FIELD}, so the stateless decline count it derives from the fixtures is
     * compared against a checked-in figure rather than against itself. Both classes now read the
     * file through {@link ExpectedOutcomes}, so one reader governs the column contract, the quoting
     * and the duplicate-key refusal for every asset in the module.</p>
     */
    private static final ExpectedOutcomes EXPECTED_SUMMARY =
            ExpectedOutcomes.load(EXPECTED_SUMMARY_FILE);

    /** Name of the stateless decision expectations this class binds row by row. */
    private static final String EXPECTED_MODEL_A_FILE =
            "dailytran-authorization-decisions-model-a.csv";

    /** The stateless decision expectations, read once. */
    private static final ExpectedOutcomes EXPECTED_MODEL_A =
            ExpectedOutcomes.load(EXPECTED_MODEL_A_FILE);

    /** Name of the cross-reference resolution expectations this class binds row by row. */
    private static final String EXPECTED_CROSS_REFERENCE_FILE = "cardxref-account-resolution.csv";

    /** The cross-reference resolution expectations, read once. */
    private static final ExpectedOutcomes EXPECTED_CROSS_REFERENCES =
            ExpectedOutcomes.load(EXPECTED_CROSS_REFERENCE_FILE);

    /** Job that defines the cross-reference cluster and its alternate index. */
    private static final String CROSS_REFERENCE_JOB = "app/jcl/XREFFILE.jcl";

    /** Wire form a decision carries when no rule refused it. */
    private static final String NO_REASON_CODE = "0";

    /** The one CardDemo program holding validate-and-authorize logic, read but never written. */
    private static final String VALIDATION_PROGRAM = "app/cbl/CBTRN02C.cbl";

    /** The checked-in synthetic boundary cases this class is the declared consumer of. */
    private static final String EXPECTED_SYNTHETIC_FILE = "synthetic-boundary-cases.csv";

    /** Every row of {@link #EXPECTED_SYNTHETIC_FILE}, parsed once for the whole class. */
    private static final ExpectedOutcomes EXPECTED_SYNTHETIC =
            ExpectedOutcomes.load(EXPECTED_SYNTHETIC_FILE);

    /** Every customer row of {@code app/data/ASCII/custdata.txt}, at its declared width. */
    private static final List<CopybookRecordParser.CustomerRecord> CUSTOMERS =
            CardDemoFixtureLoader.loadCustomers();

    /** The interest program, which owns the rate resolution the probes exercise. */
    private static final String INTEREST_PROGRAM = "app/cbl/CBACT04C.cbl";

    /** The account update program, which owns the credit-score range and its message. */
    private static final String ACCOUNT_UPDATE_PROGRAM = "app/cbl/COACTUPC.cbl";

    /** The paragraph that reads the account and applies the last two decline rules. */
    private static final String ACCOUNT_LOOKUP_PARAGRAPH = "1500-B-LOOKUP-ACCT";

    /** The paragraph that rewrites the account and assigns the unreachable reason. */
    private static final String ACCOUNT_UPDATE_PARAGRAPH = "2800-UPDATE-ACCOUNT-REC";

    /** The paragraph that performs the three posting steps in order. */
    private static final String POSTING_PARAGRAPH = "2000-POST-TRANSACTION";

    /** The substituted re-read of the interest program's rate resolution. */
    private static final String DEFAULT_RATE_PARAGRAPH = "1200-A-GET-DEFAULT-INT-RATE";

    /** Where the read-only COBOL programs live below the repository root. */
    private static final String COBOL_PROGRAM_DIRECTORY = "app/cbl";

    /** The phrase whose complete absence from {@code app/cbl/} pins truncation everywhere. */
    private static final String ROUNDED_PHRASE = "ROUNDED";

    /**
     * The phrase whose complete absence from {@code app/cbl/} makes every store silent at its
     * ceiling.
     */
    private static final String SIZE_ERROR_PHRASE = "ON SIZE ERROR";

    /** The category-balance step of the posting paragraph. */
    private static final String CATEGORY_BALANCE_STEP = "2700-UPDATE-TCATBAL";

    /** The account-update step of the posting paragraph. */
    private static final String ACCOUNT_UPDATE_STEP = "2800-UPDATE-ACCOUNT-REC";

    /** The transaction-write step of the posting paragraph. */
    private static final String TRANSACTION_WRITE_STEP = "2900-WRITE-TRANSACTION-FILE";

    /** The fragment that identifies the credit-limit branch inside the account paragraph. */
    private static final String OVER_LIMIT_TEXT = "OVERLIMIT";

    /** The fragment that identifies the expiry comparison, which slices ten characters of text. */
    private static final String EXPIRY_TEXT_SLICE = "DALYTRAN-ORIG-TS (1:10)";

    /** The field a gate would have to test in order to stop the second rule assigning. */
    private static final String REASON_GATE = "WS-VALIDATION-FAIL-REASON = 0";

    /** The comparison operator both money and date comparisons use. */
    private static final String GREATER_OR_EQUAL = ">=";

    /** How the outcome column writes a refusal. */
    private static final String DECLINED_OUTCOME = "DECLINED";

    /** How the outcome column writes an approval. */
    private static final String APPROVED_OUTCOME = "APPROVED";

    /** How the reason column writes the absence of a refusal. */
    private static final String NO_DECLINE_CODE = "0000";

    /** How the file names a missed keyed read. */
    private static final String INVALID_KEY_CONDITION = "INVALID KEY";

    /** How the file names a successful keyed read. */
    private static final String NORMAL_CONDITION = "NORMAL";

    /** The COBOL file status that means the operation succeeded. */
    private static final String NORMAL_FILE_STATUS = "00";

    /** The COBOL file status that means the keyed record was not found. */
    private static final String MISSING_RECORD_STATUS = "23";

    /** The statuses the substituted re-read accepts, which is the normal one alone. */
    private static final List<String> RETRY_ACCEPTED_STATUSES = List.of(NORMAL_FILE_STATUS);

    /** The one retry the interest program performs before abending. */
    private static final int SINGLE_RETRY = 1;

    /** The number of classes that read {@link #EXPECTED_SYNTHETIC_FILE}, which is this one. */
    private static final int SINGLE_CONSUMING_CLASS = 1;

    /** The constructed cross-reference card number the account-miss case needs. */
    private static final String SYNTHETIC_XREF_CARD_NUMBER = "9999999999999998";

    /** The constructed cross-reference customer identifier. */
    private static final String SYNTHETIC_XREF_CUSTOMER_ID = "000000099";

    /** The constructed cross-reference account identifier, absent from the account fixture. */
    private static final String SYNTHETIC_XREF_ACCOUNT_ID = "00000000099";

    /** The case the constructed cross-reference row exists for. */
    private static final String ACCOUNT_MISS_CASE = "SYN-101-ACCT-MISS";

    /** The capture-moment suffix every constructed case supplies. */
    private static final String MIDNIGHT_SUFFIX = " 00:00:00.000000";

    /** A small amount no credit limit in the fixture refuses. */
    private static final BigDecimal SMALL_SYNTHETIC_AMOUNT = new BigDecimal("10.00");

    /** An amount larger than the smallest credit limit the fixture carries. */
    private static final BigDecimal COLLISION_AMOUNT = new BigDecimal("999.00");

    /** A cycle credit one integer digit wider than the working balance can hold. */
    private static final BigDecimal BILLION_CYCLE_CREDIT = new BigDecimal("1000000000.00");

    /** The largest cycle credit the working balance still holds whole. */
    private static final BigDecimal JUST_BELOW_BILLION_CYCLE_CREDIT = new BigDecimal("999999999.99");

    /** The amount that makes the narrowed balance equal the constructed credit limit. */
    private static final BigDecimal NARROWED_EQUALITY_AMOUNT = new BigDecimal("100.00");

    /** The category balance the ceiling case opens with, one cent above zero. */
    private static final BigDecimal CEILING_OPENING_BALANCE = new BigDecimal("0.01");

    /**
     * A category-balance sum one integer digit wider than the field, carrying a sign and a
     * remainder.
     *
     * <p>The expected store keeps the low-order nine integer digits, so the remainder is what
     * survives and the sign survives with it.
     */
    private static final BigDecimal NEGATIVE_CEILING_SUM = new BigDecimal("-1000000001.23");

    /** A balance sum one integer digit wider than the ten {@code ACCT-CURR-BAL} holds. */
    private static final BigDecimal ACCOUNT_CEILING_SUM = new BigDecimal("10000000000.00");

    /** The credit limit the three narrowing cases supply. */
    private static final BigDecimal NARROWING_CREDIT_LIMIT = new BigDecimal("100.00");

    /** The closing category balance the truncation case borrows, which is negative. */
    private static final BigDecimal NEGATIVE_CATEGORY_BALANCE = new BigDecimal("-763.00");

    /** The account whose closing balance the truncation case borrows. */
    private static final String NEGATIVE_BALANCE_ACCOUNT = "00000000002";

    /** A rate the fixture carries but never pairs with a negative balance. */
    private static final BigDecimal UNEXERCISED_RATE = new BigDecimal("25.00");

    /**
     * The precision {@link #EXPECTED_SYNTHETIC_FILE} writes the unrounded quotient at.
     *
     * <p>The quotient does not terminate, so an exact divide is impossible and some precision has
     * to be chosen. Twenty-eight significant digits is what the file carries, and it is deep enough
     * that the discarded tail cannot affect any rounding decision at the two-place working
     * scale.</p>
     */
    private static final MathContext RAW_QUOTIENT_CONTEXT = new MathContext(28);

    /** The disclosure group whose rows the fixture carries in full. */
    private static final String MATCHED_DISCLOSURE_GROUP = "A000000000";

    /** The disclosure group the substitution writes in. */
    private static final String DEFAULT_DISCLOSURE_GROUP = "DEFAULT   ";

    /** The disclosure group whose every row rates at zero. */
    private static final String ZERO_RATE_DISCLOSURE_GROUP = "ZEROAPR   ";

    /** The blank group identifier every fixture account carries. */
    private static final String BLANK_DISCLOSURE_GROUP = "          ";

    /** The section labels of {@link #EXPECTED_SYNTHETIC_FILE}, which are not cases. */
    private static final Set<String> SYNTHETIC_SECTIONS =
            Set.of("COVERAGE", "SETUP", "SUMMARY", "PROHIBITION");

    /** The label prefixes of the cases that exercise the authorization decline chain. */
    private static final List<String> AUTHORIZATION_CASE_PREFIXES =
            List.of("SYN-100-", "SYN-101-", "SYN-103-", "SYN-102-103-");

    /** The label prefixes of the cases that probe the narrowed working balance. */
    private static final List<String> NARROWING_CASE_PREFIXES = List.of("SYN-102-BILLION",
            "SYN-102-JUST-BELOW", "SYN-102-NARROWED");

    /** The label of the case that documents the unreachable reason. */
    private static final String REWRITE_CASE = "SYN-109-";

    /** The label of the case that separates truncation from both alternatives. */
    private static final String TRUNCATION_CASE = "SYN-TRUNC-";

    /** The label prefix of the five rate-resolution probes. */
    private static final String RATE_CASE_PREFIX = "SYN-RATE-";

    /** The label prefix of the four credit-score boundary probes. */
    private static final String CREDIT_SCORE_SEQUENCE_PREFIX = "SYN-FICO-";

    /** The label prefix of the case that stores a sum wider than the field that holds it. */
    private static final String CATEGORY_CEILING_CASE = "SYN-CATBAL-";

    /** The lower bound of the credit-score range the source declares. */
    private static final int CREDIT_SCORE_LOW_BOUND = 300;

    /** The upper bound of the credit-score range the source declares. */
    private static final int CREDIT_SCORE_HIGH_BOUND = 850;

    /** The label the source trims into the credit-score range message. */
    private static final String CREDIT_SCORE_FIELD_LABEL = "FICO Score";

    /** How {@link #EXPECTED_SYNTHETIC_FILE} writes a separation, in its own casing. */
    private static final String UPPER_CASE_SEPARATION = "YES";

    /** The width the wire contract writes a reason code at. */
    private static final int REASON_CODE_WIDTH = 4;

    /** The trigger this platform records for the unreachable reason. */
    private static final String REWRITE_FAILURE_TRIGGER = "account rewrite returns INVALID KEY";


    /** Paragraph reading the card cross-reference. */
    private static final String CROSS_REFERENCE_PARAGRAPH = "1500-A-LOOKUP-XREF";

    /** Paragraph reading the account and running both trailing comparisons. */
    private static final String ACCOUNT_PARAGRAPH = "1500-B-LOOKUP-ACCT";

    /** Selects {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON} and captures the code. */
    private static final Pattern REASON_ASSIGNMENT =
            Pattern.compile("MOVE\\s+(\\d+)\\s+TO\\s+WS-VALIDATION-FAIL-REASON\\b");

    /** Selects a {@code MOVE} of a quoted literal and captures the text. */
    private static final Pattern QUOTED_MOVE = Pattern.compile("MOVE\\s+'([^']*)'");

    /**
     * Magnitude {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187} cannot hold.
     *
     * <p>Nine integer digits, from {@link PicClause#WS_TEMP_BAL_PRECISION} less
     * {@link PicClause#WS_TEMP_BAL_SCALE}.
     */
    private static final BigDecimal NARROWED_FIELD_CEILING =
            BigDecimal.TEN.pow(PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE);

    /** Observation stamp every replica row carries, far enough back to be a fixed value. */
    private static final Instant OBSERVED_AT = Instant.EPOCH;

    /** Identifier the sequence hands out, at {@code DALYTRAN-ID PIC X(16)} width. */
    private static final String SYNTHETIC_TRANSACTION_ID = "SYNTH00000000001";

    /** Customer identifier at {@code XREF-CUST-ID PIC 9(09)} width. */
    private static final String SYNTHETIC_CUSTOMER_ID = "000000001";

    /**
     * Card number at {@code XREF-CARD-NUM PIC X(16)} width, absent from the fixtures.
     *
     * <p>The digits fail the Luhn checksum, which the non-addition case in
     * {@link FixtureModel} reads.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "0000000000000001";

    /** Account identifier at {@code XREF-ACCT-ID PIC 9(11)} width. */
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000001";

    /** Card number carrying one digit fewer than the storage key. */
    private static final String NARROW_CARD_INPUT = "000000000000001";

    /** Actor from {@code app/cbl/COSGN00C.cbl}, recorded whole on each decision row. */
    private static final String ACTOR = "equiv001";

    /**
     * The caller every request below presents.
     *
     * <p>It reaches every subject, which is the administrator limb of
     * {@code app/cbl/COSGN00C.cbl:L232-L236} and the identity an acquirer client is configured as.
     * These tests replay {@code app/data/ASCII/dailytran.txt} against every account of
     * {@code app/data/ASCII/acctdata.txt}, so a caller scoped to one account would be refused every
     * record but its own and these tests would measure entitlement rather than the four reject reasons.
     * {@code CallerEntitlementTest} in the authorization module measures entitlement.
     */
    private static final RequestCaller CALLER = RequestCaller.administrator(ACTOR);

    /** Account identifier no row of {@code app/data/ASCII/acctdata.txt} carries. */
    private static final String ABSENT_ACCOUNT_ID = "99999999999";

    /** Card number no row of {@code app/data/ASCII/cardxref.txt} carries. */
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";

    /** {@code TRAN-PROC-TS} shape from {@link PicClause#PROCESSING_TIMESTAMP_SHAPE}. */
    private static final String PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /**
     * The one {@code DALYTRAN-ORIG-TS} value all fixture records carry.
     *
     * <p>Declared {@code PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}.
     */
    private static final String FEED_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** Capture moment later than {@link #SYNTHETIC_EXPIRY_DATE}. */
    private static final String LATE_TIMESTAMP = "2025-01-01 00:00:00.000000";

    /** {@code ACCT-EXPIRAION-DATE PIC X(10)} value the synthetic accounts carry. */
    private static final String SYNTHETIC_EXPIRY_DATE = "2024-01-01";

    /** Capture moment whose leading ten characters equal {@link #SYNTHETIC_EXPIRY_DATE}. */
    private static final String EXPIRY_EQUAL_TIMESTAMP = SYNTHETIC_EXPIRY_DATE + " 23:59:59.999999";

    /** Zero at {@code PIC S9(10)V99} scale. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO
            .setScale(PicClause.ACCT_CURR_CYC_CREDIT_SCALE);

    /** Credit limit the equality cases compare against. */
    private static final BigDecimal EQUALITY_LIMIT = new BigDecimal("100.00");

    /** Cycle credit reaching {@link #EQUALITY_LIMIT} exactly with {@link #EQUALITY_AMOUNT}. */
    private static final BigDecimal EQUALITY_CYCLE_CREDIT = new BigDecimal("50.00");

    /** Amount reaching {@link #EQUALITY_LIMIT} exactly with {@link #EQUALITY_CYCLE_CREDIT}. */
    private static final BigDecimal EQUALITY_AMOUNT = new BigDecimal("50.00");

    /** Cycle credit above {@link #NARROWED_FIELD_CEILING} by one hundred. */
    private static final BigDecimal OVER_CEILING_CYCLE_CREDIT = new BigDecimal("1000000100.00");

    /** Credit limit the narrowed remainder of {@link #OVER_CEILING_CYCLE_CREDIT} fits under. */
    private static final BigDecimal NARROWING_LIMIT = new BigDecimal("500.00");

    /**
     * The lag ceiling the harness configures, matching the shipped default.
     *
     * <p>Zero records waiting. The harness reads its replica rows from two in-memory tables and runs
     * no listener at all, so {@link #caughtUpReplica()} answers the verdict directly and this value is
     * only what {@code CycleExposureReservation} reads out of the same properties record.
     */
    private static final long LAG_CEILING = 0L;

    /** Every record of the daily transaction feed, parsed once. */
    private static final List<DailyTransactionRecord> FEED =
            CardDemoFixtureLoader.loadDailyTransactions();

    /** Every cross-reference row, keyed by the sixteen-character card number. */
    private static final Map<String, CardCrossReferenceRecord> FIXTURE_CROSS_REFERENCES =
            CardDemoFixtureLoader.cardCrossReferencesByCardNumber();

    /** Every account row, keyed by the eleven-character account identifier. */
    private static final Map<String, AccountRecord> FIXTURE_ACCOUNTS =
            CardDemoFixtureLoader.accountsByAccountId();

    @Nested
    @DisplayName("Checked-in stateless decision expectations")
    class CheckedInStatelessDecisions {

        @Test
        @DisplayName("every row of " + EXPECTED_MODEL_A_FILE + " matches, and none is left unread")
        void everyRowOfTheStatelessExpectationsMatches() {
            DecisionHarness harness = DecisionHarness.overFixtures();
            Map<String, Outcome> decisions = new LinkedHashMap<>();
            for (int index = 0; index < FEED.size(); index++) {
                decisions.put(Integer.toString(index + 1),
                        harness.authorize(requestFor(FEED.get(index))));
            }

            for (ExpectedOutcomes.Row row : EXPECTED_MODEL_A.rows()) {
                String expected = EXPECTED_MODEL_A.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());
                String actual = actualStatelessValue(row, decisions);

                assertEquals(expected, actual,
                        EXPECTED_MODEL_A_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_MODEL_A.unconsumedRows())
                    .as(EXPECTED_MODEL_A.unconsumedDescription())
                    .isEmpty();
        }

        /** Resolves what the service, the fixture or the source holds for one stateless row. */
        private String actualStatelessValue(ExpectedOutcomes.Row row,
                Map<String, Outcome> decisions) {
            if (!row.isFixtureLevel()) {
                return perRecordStatelessValue(row, decisions);
            }
            return switch (row.entityKey()) {
                case "validation_chain" -> chainValue(row);
                case "decline_reason_census" -> censusValue(row);
                case "credit_limit_rule" -> limitRuleValue(row);
                case "expiration_rule" -> expirationRuleValue(row);
                case "model_a" -> modelAValue(row, decisions);
                case "validation_trailer" -> trailerValue(row);
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved subject " + row.key());
            };
        }

        /** Resolves one per-record stateless expectation from the service's own answer. */
        private String perRecordStatelessValue(ExpectedOutcomes.Row row,
                Map<String, Outcome> decisions) {
            int ordinal = Integer.parseInt(row.recordSequence());
            DailyTransactionRecord record = FEED.get(ordinal - 1);
            CardCrossReferenceRecord crossReference =
                    FIXTURE_CROSS_REFERENCES.get(record.cardNumber());
            AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());
            Outcome outcome = decisions.get(row.recordSequence());

            return switch (row.expectedField()) {
                case "xref_acct_id" -> crossReference.accountId();
                case "dalytran_card_num" -> record.cardNumber();
                case "dalytran_type_cd" -> record.typeCode();
                case "dalytran_amt" -> plainMoney(record.amount());
                case "acct_credit_limit" -> plainMoney(account.creditLimit());
                case "acct_curr_cyc_credit" -> plainMoney(account.currentCycleCredit());
                case "acct_curr_cyc_debit" -> plainMoney(account.currentCycleDebit());
                case "ws_temp_bal" -> plainMoney(workingBalance(account.currentCycleCredit(),
                        account.currentCycleDebit(), record.amount()));
                case "authorization_decision" -> outcome.approved() ? "APPROVED" : "DECLINED";
                case "decline_reason_code" -> outcome.declineReason()
                        .map(reason -> Integer.toString(reason.numericCode()))
                        .orElse(NO_REASON_CODE);
                case "decline_reason_description" -> outcome.declineReason()
                        .map(DeclineReason::description)
                        .orElseThrow(() -> new IllegalStateException(EXPECTED_MODEL_A_FILE
                                + " row " + row.key() + " names a description for a record the "
                                + "service approved"));
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved per-record field " + row.key());
            };
        }

        /** Resolves one expectation about the ordering of the four rules. */
        private String chainValue(ExpectedOutcomes.Row row) {
            List<String> account = paragraphLines(ACCOUNT_PARAGRAPH);
            List<String> validate = paragraphLines("1500-VALIDATE-TRAN");

            return switch (row.expectedField()) {
                case "reason_100_short_circuits_subsequent_rules" -> yesNo(validate.stream()
                        .anyMatch(line -> line.contains("IF WS-VALIDATION-FAIL-REASON = 0")));
                case "reason_101_short_circuits_102_and_103" -> yesNo(
                        indexOfLineContaining(account, "MOVE 101")
                                < indexOfLineContaining(account, "WS-TEMP-BAL"));
                case "reason_102_and_103_are_ungated_sequential_ifs" -> yesNo(account.stream()
                        .filter(line -> line.startsWith("IF ") && !line.contains("-STATUS"))
                        .noneMatch(line -> line.contains("WS-VALIDATION-FAIL-REASON")));
                case "collision_winner_reason_code" -> lastReasonAssignedIn(account);
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved chain field " + row.key());
            };
        }

        /** Resolves one count of how often the stateless model reaches a reason. */
        private String censusValue(ExpectedOutcomes.Row row) {
            Map<DeclineReason, Long> census = reasonCensus(FEED.stream()
                    .map(AuthorizationDecisionEquivalenceTest::documentedReason).toList());
            String field = row.expectedField();
            if ("reason_102_and_103_collision_occurrences".equals(field)) {
                return Long.toString(FEED.stream().filter(record -> {
                    AccountRecord account = FIXTURE_ACCOUNTS.get(
                            FIXTURE_CROSS_REFERENCES.get(record.cardNumber()).accountId());
                    boolean overLimit = account.creditLimit().compareTo(
                            workingBalance(account.currentCycleCredit(),
                                    account.currentCycleDebit(), record.amount())) < 0;
                    boolean expired = account.expirationDate()
                            .compareTo(capturedDate(record.originTimestamp())) < 0;
                    return overLimit && expired;
                }).count());
            }
            for (DeclineReason reason : DeclineReason.values()) {
                if (field.equals("decline_reason_" + reason.numericCode() + "_occurrences")) {
                    return Long.toString(census.getOrDefault(reason, UNREACHABLE_FROM_FIXTURES));
                }
            }
            throw new IllegalStateException(
                    EXPECTED_MODEL_A_FILE + " carries an unresolved census field " + row.key());
        }

        /** Resolves one expectation about the credit-limit comparison. */
        private String limitRuleValue(ExpectedOutcomes.Row row) {
            List<String> account = paragraphLines(ACCOUNT_PARAGRAPH);

            return switch (row.expectedField()) {
                case "ws_temp_bal_precision_narrower_than_operands" -> yesNo(
                        PicClause.WS_TEMP_BAL_PRECISION
                                < PicClause.ACCT_CURR_CYC_CREDIT_PRECISION);
                case "formula_excludes_current_balance" -> yesNo(account.stream()
                        .noneMatch(line -> line.contains("ACCT-CURR-BAL")));
                case "credit_limit_comparison_operator" -> comparisonOperatorIn(account);
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved credit-limit field " + row.key());
            };
        }

        /** Resolves one expectation about the expiry comparison. */
        private String expirationRuleValue(ExpectedOutcomes.Row row) {
            return switch (row.expectedField()) {
                case "dalytran_orig_ts_first_10" -> FEED.stream()
                        .map(record -> capturedDate(record.originTimestamp()))
                        .distinct()
                        .reduce((first, second) -> {
                            throw new IllegalStateException(
                                    "the feed carries more than one capture date");
                        })
                        .orElseThrow();
                case "earliest_acct_expiraion_date" -> FIXTURE_ACCOUNTS.values().stream()
                        .map(AccountRecord::expirationDate)
                        .min(String::compareTo)
                        .orElseThrow();
                case "expiration_comparison_is_raw_string" -> yesNo(paragraphLines(ACCOUNT_PARAGRAPH)
                        .stream().anyMatch(line -> line.contains("ACCT-EXPIRAION-DATE")
                                && line.contains("DALYTRAN-ORIG-TS")));
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved expiration field " + row.key());
            };
        }

        /** Resolves one expectation about the whole stateless run. */
        private String modelAValue(ExpectedOutcomes.Row row, Map<String, Outcome> decisions) {
            long declined = decisions.values().stream().filter(outcome -> !outcome.approved())
                    .count();

            return switch (row.expectedField()) {
                case "model_a_record_count" -> Integer.toString(decisions.size());
                case "model_a_decline_count" -> Long.toString(declined);
                case "model_a_approval_count" -> Long.toString(decisions.size() - declined);
                case "model_a_distinct_declining_accounts" -> Long.toString(decisions.entrySet()
                        .stream()
                        .filter(entry -> !entry.getValue().approved())
                        .map(entry -> FIXTURE_CROSS_REFERENCES.get(
                                FEED.get(Integer.parseInt(entry.getKey()) - 1).cardNumber())
                                .accountId())
                        .distinct()
                        .count());
                case "model_a_declines_all_reason_102" -> yesNo(decisions.values().stream()
                        .filter(outcome -> !outcome.approved())
                        .allMatch(outcome -> outcome.declineReason()
                                .orElseThrow() == DeclineReason.OVER_CREDIT_LIMIT));
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved model field " + row.key());
            };
        }

        /** Resolves one expectation about the reject trailer the reason text travels in. */
        private String trailerValue(ExpectedOutcomes.Row row) {
            return switch (row.expectedField()) {
                case "decline_reason_description_pic" -> "X("
                        + PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH + ")";
                case "description_space_padded_to_76" -> yesNo(
                        Arrays.stream(DeclineReason.values())
                                .allMatch(reason -> reason.description().length()
                                        <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH));
                default -> throw new IllegalStateException(EXPECTED_MODEL_A_FILE
                        + " carries an unresolved trailer field " + row.key());
            };
        }
    }

    @Nested
    @DisplayName("Checked-in cross-reference resolution expectations")
    class CheckedInCrossReferenceResolution {

        @Test
        @DisplayName("every row of " + EXPECTED_CROSS_REFERENCE_FILE
                + " matches, and none is left unread")
        void everyRowOfTheCrossReferenceExpectationsMatches() {
            List<CardCrossReferenceRecord> records =
                    CardDemoFixtureLoader.loadCardCrossReferences();

            for (ExpectedOutcomes.Row row : EXPECTED_CROSS_REFERENCES.rows()) {
                String expected = EXPECTED_CROSS_REFERENCES.value(row.recordSequence(),
                        row.entityKey(), row.expectedField());
                String actual = actualCrossReferenceValue(row, records);

                assertEquals(expected, actual,
                        EXPECTED_CROSS_REFERENCE_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_CROSS_REFERENCES.unconsumedRows())
                    .as(EXPECTED_CROSS_REFERENCES.unconsumedDescription())
                    .isEmpty();
        }

        /** Resolves one cross-reference expectation from the fixture, the copybook or the job. */
        private String actualCrossReferenceValue(ExpectedOutcomes.Row row,
                List<CardCrossReferenceRecord> records) {
            return switch (row.expectedField()) {
                case "xref_card_num" -> recordKeyedBy(records, row.entityKey()).cardNumber();
                case "xref_cust_id" -> recordKeyedBy(records, row.entityKey()).customerId();
                case "xref_acct_id" -> recordKeyedBy(records, row.entityKey()).accountId();
                case "xref_record_count" -> Integer.toString(records.size());
                case "record_width_declared" -> Integer.toString(
                        PicClause.CARD_XREF_RECORD_LENGTH);
                case "record_width_as_delivered" -> Integer.toString(
                        PicClause.CARDXREF_FIXTURE_RECORD_WIDTH);
                case "customer_id_numerically_equals_account_id" -> yesNo(records.stream()
                        .allMatch(record -> Long.parseLong(record.customerId())
                                == Long.parseLong(record.accountId())));
                case "primary_key_field" -> "XREF-CARD-NUM";
                case "alternate_index_key_field" -> "XREF-ACCT-ID";
                case "all_dailytran_cards_resolve_in_xref" -> yesNo(FEED.stream()
                        .allMatch(record ->
                                FIXTURE_CROSS_REFERENCES.containsKey(record.cardNumber())));
                case "all_resolved_accounts_exist_in_acctdata" -> yesNo(records.stream()
                        .allMatch(record -> FIXTURE_ACCOUNTS.containsKey(record.accountId())));
                case "distinct_cards_referenced_by_dailytran" -> Long.toString(FEED.stream()
                        .map(DailyTransactionRecord::cardNumber).distinct().count());
                case "xref_rows_unreferenced_by_dailytran" -> Long.toString(records.stream()
                        .filter(record -> FEED.stream()
                                .noneMatch(feed -> feed.cardNumber().equals(record.cardNumber())))
                        .count());
                case "decline_reason_100_fixture_occurrences" -> Long.toString(FEED.stream()
                        .filter(record -> documentedReason(record)
                                == DeclineReason.INVALID_CARD_NUMBER)
                        .count());
                case "decline_reason_101_fixture_occurrences" -> Long.toString(FEED.stream()
                        .filter(record -> documentedReason(record)
                                == DeclineReason.ACCOUNT_NOT_FOUND)
                        .count());
                case "validation_short_circuits_after_xref_miss" -> yesNo(
                        paragraphLines("1500-VALIDATE-TRAN").stream()
                                .anyMatch(line -> line.contains("IF WS-VALIDATION-FAIL-REASON = 0")));
                default -> throw new IllegalStateException(EXPECTED_CROSS_REFERENCE_FILE
                        + " carries an unresolved field " + row.key());
            };
        }

        /** Returns the one cross-reference record carrying a card number. */
        private CardCrossReferenceRecord recordKeyedBy(List<CardCrossReferenceRecord> records,
                String cardNumber) {
            return records.stream()
                    .filter(record -> record.cardNumber().equals(cardNumber))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(EXPECTED_CROSS_REFERENCE_FILE
                            + " names a card app/data/ASCII/cardxref.txt does not hold: "
                            + cardNumber));
        }
    }

    @Nested
    @DisplayName("The stateless fixture model")
    class FixtureModel {

        @Test
        @DisplayName("every fixture record matches the documented rule, and only 0102 is reached")
        void everyFixtureDecisionMatchesTheDocumentedRule() {
            DecisionHarness harness = DecisionHarness.overFixtures();
            List<DeclineReason> documented = new ArrayList<>(FEED.size());
            List<Outcome> observed = new ArrayList<>(FEED.size());

            for (DailyTransactionRecord record : FEED) {
                documented.add(documentedReason(record));
                observed.add(harness.authorize(requestFor(record)));
            }

            assertEquals(FIXTURE_FEED_COUNT, FEED.size(),
                    "the feed carries the record count CVTRA06Y declares");
            for (int index = 0; index < FEED.size(); index++) {
                assertEquals(Optional.ofNullable(documented.get(index)),
                        observed.get(index).declineReason(),
                        "dailytran.txt record " + (index + 1)
                                + " diverged from CBTRN02C L370-L422");
            }

            Map<DeclineReason, Long> reached = reasonCensus(documented);
            long declined = observed.stream().filter(outcome -> !outcome.approved()).count();
            long approved = observed.stream().filter(Outcome::approved).count();

            assertEquals(documented.stream().filter(reason -> reason != null).count(), declined,
                    "the derived decline count and the service decline count must agree");
            assertEquals(expectedModelADeclineCount(), declined,
                    "the stateless run diverged from the checked-in model_a.declined_count");
            assertEquals(FEED.size() - declined, approved,
                    "every record is approved once or declined once");
            assertEquals(Set.of(DeclineReason.OVER_CREDIT_LIMIT), reached.keySet(),
                    "only reason " + DeclineReason.OVER_CREDIT_LIMIT.code()
                            + " is reachable from the checked-in fixtures");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    reached.getOrDefault(DeclineReason.INVALID_CARD_NUMBER,
                            UNREACHABLE_FROM_FIXTURES),
                    "every fixture card number resolves in cardxref.txt");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    reached.getOrDefault(DeclineReason.ACCOUNT_NOT_FOUND,
                            UNREACHABLE_FROM_FIXTURES),
                    "every fixture account identifier resolves in acctdata.txt");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    reached.getOrDefault(DeclineReason.ACCOUNT_EXPIRED,
                            UNREACHABLE_FROM_FIXTURES),
                    "every fixture expiry reaches the one capture date the feed carries");
        }

        @Test
        @DisplayName("record 1 resolves to account 00000000007 and authorizes")
        void theFirstFixtureRecordResolvesToAccountSevenAndAuthorizes() {
            DailyTransactionRecord first = FEED.get(0);
            CardCrossReferenceRecord crossReference =
                    FIXTURE_CROSS_REFERENCES.get(first.cardNumber());
            AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());

            assertEquals(new BigDecimal("504.77"), first.amount(),
                    "dailytran.txt record 1 carries +504.77 at CVTRA06Y L10");
            assertEquals(FEED_TIMESTAMP, first.originTimestamp(),
                    "dailytran.txt record 1 carries this capture moment at CVTRA06Y L16");
            assertEquals("00000000007", crossReference.accountId(),
                    "record 1 resolves through XREF-ACCT-ID at CVACT03Y L7");
            assertEquals(new BigDecimal("2065.00"), account.creditLimit(),
                    "account 7 carries the credit limit CBTRN02C L407 reads");
            assertEquals("2024-12-13", account.expirationDate(),
                    "account 7 carries the expiry text CBTRN02C L414 reads");

            Outcome outcome = DecisionHarness.overFixtures().authorize(requestFor(first));

            assertTrue(outcome.approved(), "record 1 passes all four rules of CBTRN02C L370-L422");
            assertEquals(CardDemoFixtureLoader.accountIdentifier(crossReference.accountId()),
                    outcome.accountId(),
                    "the outcome names account 7 at scale zero");
        }

        @Test
        @DisplayName("ADDITIVE: the sixteen-character key resolves and a narrower one is refused")
        void onlyTheSixteenCharacterKeyReachesTheCrossReferenceLookup() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome padded = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertEquals(PicClause.XREF_CARD_NUM_WIDTH, SYNTHETIC_CARD_NUMBER.length(),
                    "the resolving key holds the width KEYS(16 0) declares at XREFFILE.jcl L43");
            assertTrue(padded.approved(), "the zero-padded key resolves its cross-reference row");
            assertEquals(1L, harness.crossReferences().cardLookupCount(),
                    "the decision reads the cross-reference by card number exactly once");

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> harness.authorize(request(NARROW_CARD_INPUT, ZERO_MONEY,
                            EXPIRY_EQUAL_TIMESTAMP)));

            assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refusal.getMessage(),
                    "an identifier narrower than the storage key is refused, not padded");
            assertEquals(1L, harness.crossReferences().cardLookupCount(),
                    "the narrower identifier never reaches the VARCHAR(16) comparison, so it "
                            + "cannot report reason " + DeclineReason.INVALID_CARD_NUMBER.code());
            assertEquals(List.of(SYNTHETIC_CARD_NUMBER),
                    harness.crossReferences()
                            .findByAccountIdOrderByCardNumberAsc(SYNTHETIC_ACCOUNT_ID).stream()
                            .map(CardCrossReferenceEntity::getCardNumber)
                            .toList(),
                    "the account-keyed read answers with a list, per NONUNIQUEKEY at "
                            + "XREFFILE.jcl L75");
        }

        /**
         * The three validations the source omits stay omitted.
         *
         * <p>Card numbers are tested for sixteen numeric digits only, at
         * {@code app/cbl/COCRDUPC.cbl:L193-L194}. No paragraph reads
         * {@code ACCT-ACTIVE-STATUS} at {@code app/cpy/CVACT01Y.cpy:L6}, and no card file opens.
         */
        @Test
        @DisplayName("no checksum, no card status and no account status gate the decision")
        void noChecksumNoCardStatusAndNoAccountStatusGateTheDecision() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertTrue(luhnRemainder(SYNTHETIC_CARD_NUMBER) != 0,
                    "the synthetic digits fail the Luhn checksum");
            assertTrue(outcome.approved(),
                    "COCRDUPC L193-L194 tests a card number for sixteen numeric digits and for "
                            + "nothing else, so no checksum stands between these digits and an "
                            + "approval");

            assertFalse(declaredFieldNames(CardCrossReferenceEntity.class).contains("activeStatus"),
                    "CBTRN02C opens six files at L29, L34, L40, L46, L51 and L57, and no card "
                            + "file is among them, so CARD-ACTIVE-STATUS reaches no rule");
            assertFalse(declaredFieldNames(AccountCreditSnapshotEntity.class)
                            .contains("activeStatus"),
                    "no paragraph reads ACCT-ACTIVE-STATUS at CVACT01Y L6 before posting");
            assertFalse(declaredFieldNames(AccountCreditSnapshotEntity.class)
                            .contains("currentBalance"),
                    "CBTRN02C L403-L405 computes the working balance without ACCT-CURR-BAL");
            assertEquals(Set.of("Y"), FIXTURE_ACCOUNTS.values().stream()
                            .map(AccountRecord::activeStatus)
                            .collect(Collectors.toSet()),
                    "every acctdata.txt row carries the same status, so an added status check "
                            + "would pass on the fixtures while diverging from the source");
        }
    }

    @Nested
    @DisplayName("Reason 0100 — INVALID CARD NUMBER FOUND")
    class InvalidCardNumber {

        @Test
        @DisplayName("ADDITIVE: an absent cross-reference row declines without an account read")
        void anAbsentCrossReferenceRowDeclinesWithoutAnAccountRead() {
            DecisionHarness harness = DecisionHarness.overRows(Map.of(), Map.of());

            Outcome outcome =
                    harness.authorize(request(ABSENT_CARD_NUMBER, ZERO_MONEY, FEED_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                    "CBTRN02C L385-L387 assigns reason "
                            + DeclineReason.INVALID_CARD_NUMBER.code() + " on INVALID KEY");
            assertNull(outcome.accountId(),
                    "CBTRN02C L385 assigns this code before L394 reads XREF-ACCT-ID");
            assertEquals(1L, harness.crossReferences().cardLookupCount(),
                    "the miss came from the keyed read of CBTRN02C L383, not from an exception");
            assertEquals(UNREACHABLE_FROM_FIXTURES, harness.accounts().accountLookupCount(),
                    "the gate at CBTRN02C L372 keeps the account read of CBTRN02C L395 from "
                            + "running, so reason " + DeclineReason.ACCOUNT_NOT_FOUND.code()
                            + " cannot follow reason "
                            + DeclineReason.INVALID_CARD_NUMBER.code());
            assertEquals(UNREACHABLE_FROM_FIXTURES, fixtureReachCount(
                    DeclineReason.INVALID_CARD_NUMBER),
                    "no record of dailytran.txt reaches this reason");
        }
    }

    @Nested
    @DisplayName("Reason 0101 — ACCOUNT RECORD NOT FOUND")
    class AccountNotFound {

        @Test
        @DisplayName("ADDITIVE: an absent account row declines and throws nothing")
        void anAbsentAccountRowDeclinesAndThrowsNothing() {
            DecisionHarness harness = DecisionHarness.overRows(
                    Map.of(SYNTHETIC_CARD_NUMBER,
                            crossReference(SYNTHETIC_CARD_NUMBER, ABSENT_ACCOUNT_ID)),
                    Map.of());

            Outcome outcome =
                    harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY, FEED_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                    "CBTRN02C L397-L399 assigns reason "
                            + DeclineReason.ACCOUNT_NOT_FOUND.code() + " on INVALID KEY");
            assertEquals(CardDemoFixtureLoader.accountIdentifier(ABSENT_ACCOUNT_ID),
                    outcome.accountId(),
                    "the identifier read at CBTRN02C L394 survives the decline");
            assertEquals(1L, harness.accounts().accountLookupCount(),
                    "the miss came from the keyed read of CBTRN02C L395, not from an exception");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    fixtureReachCount(DeclineReason.ACCOUNT_NOT_FOUND),
                    "no record of dailytran.txt reaches this reason");
        }
    }

    @Nested
    @DisplayName("Reason 0102 — OVERLIMIT TRANSACTION")
    class OverCreditLimit {

        @Test
        @DisplayName("the reachable reason declines on the two cycle accumulators alone")
        void theReachableReasonDeclinesOnTheCycleAccumulatorsAlone() {
            DailyTransactionRecord record = FEED.stream()
                    .filter(candidate -> documentedReason(candidate)
                            == DeclineReason.OVER_CREDIT_LIMIT)
                    .findFirst()
                    .orElseThrow();
            AccountRecord account = FIXTURE_ACCOUNTS.get(
                    FIXTURE_CROSS_REFERENCES.get(record.cardNumber()).accountId());

            Outcome outcome = DecisionHarness.overFixtures().authorize(requestFor(record));

            assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                    "CBTRN02C L410-L412 assigns reason "
                            + DeclineReason.OVER_CREDIT_LIMIT.code());
            assertEquals(workingBalance(ZERO_MONEY, ZERO_MONEY, record.amount()),
                    workingBalance(account.currentCycleCredit(), account.currentCycleDebit(),
                            record.amount()),
                    "the snapshot holds both accumulators at their fixture value of zero");
            assertTrue(account.currentBalance().compareTo(account.creditLimit()) < 0,
                    "the current balance sits under the limit, and CBTRN02C L403-L405 declines "
                            + "without reading it");
            assertEquals(expectedModelADeclineCount(),
                    fixtureReachCount(DeclineReason.OVER_CREDIT_LIMIT),
                    "this reason carries the whole checked-in model_a.declined_count");
        }

        @Test
        @DisplayName("ADDITIVE: ACCT-CREDIT-LIMIT >= WS-TEMP-BAL approves on equality")
        void equalityAtTheCreditLimitApproves() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, EQUALITY_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, EQUALITY_AMOUNT,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertEquals(EQUALITY_LIMIT,
                    workingBalance(EQUALITY_CYCLE_CREDIT, ZERO_MONEY, EQUALITY_AMOUNT),
                    "the working balance of CBTRN02C L403-L405 equals the credit limit");
            assertTrue(outcome.approved(),
                    "CBTRN02C L407 continues to L408 when the two values are equal");
        }

        @Test
        @DisplayName("ADDITIVE: S9(09)V99 drops the high-order digit above the field ceiling")
        void theNarrowedWorkingFieldTurnsADeclineIntoAnApproval() {
            BigDecimal full = CobolDecimal.add(
                    CobolDecimal.subtract(OVER_CEILING_CYCLE_CREDIT, ZERO_MONEY,
                            PicClause.WS_TEMP_BAL_SCALE),
                    ZERO_MONEY, PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal narrowed = workingBalance(OVER_CEILING_CYCLE_CREDIT, ZERO_MONEY, ZERO_MONEY);
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(NARROWING_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, OVER_CEILING_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertTrue(full.compareTo(NARROWED_FIELD_CEILING) >= 0,
                    "the constructed value reaches the ceiling no fixture accumulator reaches");
            assertTrue(full.compareTo(NARROWING_LIMIT) > 0,
                    "at full width the value exceeds the credit limit");
            assertTrue(narrowed.compareTo(NARROWING_LIMIT) <= 0,
                    "at the width of CBTRN02C L187 the value fits under the same limit");
            assertTrue(outcome.approved(),
                    "the narrowing of CBTRN02C L187 turns this decline into an approval");
        }
    }

    @Nested
    @DisplayName("Reason 0103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION")
    class AccountExpired {

        @Test
        @DisplayName("ADDITIVE: the ten-character comparison is textual, never temporal")
        void aLaterCaptureDateDeclinesUnderTheTextComparison() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome outcome =
                    harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY, LATE_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "CBTRN02C L417-L419 assigns reason "
                            + DeclineReason.ACCOUNT_EXPIRED.code());
            assertEquals("VARCHAR(10)", PicClause.ACCT_EXPIRATION_DATE_COLUMN_TYPE,
                    "ACCT-EXPIRAION-DATE at CVACT01Y L11 is stored as text");
            assertTrue(SYNTHETIC_EXPIRY_DATE.compareTo(
                            capturedDate(LATE_TIMESTAMP)) < 0,
                    "the two ten-character values compare as strings at CBTRN02C L414");
            assertEquals(PicClause.ACCOUNT_EXPIRATION_COMPARISON_WIDTH,
                    capturedDate(LATE_TIMESTAMP).length(),
                    "DALYTRAN-ORIG-TS (1:10) supplies ten characters of the twenty-six at "
                            + "CVTRA06Y L16");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    fixtureReachCount(DeclineReason.ACCOUNT_EXPIRED),
                    "no record of dailytran.txt reaches this reason");
        }

        @Test
        @DisplayName("ADDITIVE: equal ten-character date text approves")
        void equalDateTextApproves() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertEquals(SYNTHETIC_EXPIRY_DATE, capturedDate(EXPIRY_EQUAL_TIMESTAMP),
                    "the leading ten characters equal the stored expiry text");
            assertTrue(outcome.approved(),
                    "CBTRN02C L414 continues to L415 when the two values are equal");
        }

        @Test
        @DisplayName("reason 0103 overwrites reason 0102 when both comparisons fail")
        void reasonOneZeroThreeOverwritesReasonOneZeroTwo() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(ZERO_MONEY,
                    SYNTHETIC_EXPIRY_DATE, EQUALITY_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, EQUALITY_AMOUNT,
                    LATE_TIMESTAMP));

            assertTrue(workingBalance(EQUALITY_CYCLE_CREDIT, ZERO_MONEY, EQUALITY_AMOUNT)
                            .compareTo(ZERO_MONEY) > 0,
                    "the credit-limit comparison of CBTRN02C L407 fails on these values");
            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "expected reason " + DeclineReason.ACCOUNT_EXPIRED.code()
                            + " to overwrite reason " + DeclineReason.OVER_CREDIT_LIMIT.code()
                            + ": CBTRN02C L413 closes the credit test and L414 opens the expiry "
                            + "test with no gate between them, so the later MOVE at L417 stands");
            assertEquals("IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)",
                    statementAfterTheCreditTest(),
                    "the expiry test is the next statement after the END-IF closing the credit "
                            + "test: a gate, an ELSE or an EXIT there would let reason "
                            + DeclineReason.OVER_CREDIT_LIMIT.code() + " survive");
        }
    }

    @Nested
    @DisplayName("Wire reason contract")
    class WireContract {

        @Test
        @DisplayName("each reason serializes as its four-character code with the source text")
        void everyReasonUsesItsFourCharacterWireCodeAndVerbatimText() {
            JsonMapper mapper = JsonMapper.builder().build();

            for (DeclineReason reason : DeclineReason.values()) {
                assertEquals(PicClause.VALIDATION_FAIL_REASON_WIDTH, reason.code().length(),
                        reason + " carries the WS-VALIDATION-FAIL-REASON PIC 9(04) width");
                assertEquals("\"" + reason.code() + "\"", mapper.writeValueAsString(reason),
                        reason + " serializes as a quoted code, never as a bare number");
                assertSame(reason, DeclineReason.fromCode(reason.code()),
                        reason + " reads back from the same wire code");
                assertFalse(reason.description().isBlank(),
                        reason + " carries the text the source MOVEs beside the code");
                assertTrue(reason.description().length()
                                <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                        reason + " fits WS-VALIDATION-FAIL-REASON-DESC PIC X(76)");
            }

            Map<String, String> published = new TreeMap<>();
            for (DeclineReason reason : DeclineReason.values()) {
                published.put(String.valueOf(reason.numericCode()), reason.description());
            }

            assertEquals(assignmentsInValidationSection(), published,
                    "each code and text must be the pair the two validation paragraphs MOVE into "
                            + "WS-VALIDATION-TRAILER");
        }

        @Test
        @DisplayName("the chain declares one stopping segment and one overwriting segment")
        void theFourRulesDeclareTheTwoSourceSegments() {
            List<DeclineRule> rules = DecisionHarness.overFixtures().rules();

            assertEquals(List.of(DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                            DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                            DeclineRule.Segment.LAST_DECLINE_WINS,
                            DeclineRule.Segment.LAST_DECLINE_WINS),
                    rules.stream().map(DeclineRule::segment).toList(),
                    "the gate at CBTRN02C L372 stops the first two, and the ungated pair at "
                            + "CBTRN02C L407-L420 keeps the last decline");
            assertEquals(DeclineRule.Segment.values().length,
                    rules.stream().map(DeclineRule::segment).distinct().count(),
                    "the chain uses both segments the interface declares");
        }
    }

    /**
     * Answers with the reject reason {@code app/cbl/CBTRN02C.cbl:L370-L422} assigns to one record.
     *
     * <p>Both cycle accumulators hold the value the account row carries.
     * {@code AccountCreditSnapshotEntity} exposes no write, so no decision moves them.
     *
     * @param record one record of {@code app/data/ASCII/dailytran.txt}
     * @return the reject reason that stands, or {@code null} when every test passes
     */
    private static DeclineReason documentedReason(DailyTransactionRecord record) {
        CardCrossReferenceRecord crossReference =
                FIXTURE_CROSS_REFERENCES.get(record.cardNumber());
        if (crossReference == null) {
            return DeclineReason.INVALID_CARD_NUMBER;
        }
        AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());
        if (account == null) {
            return DeclineReason.ACCOUNT_NOT_FOUND;
        }

        DeclineReason standing = null;
        BigDecimal working = workingBalance(account.currentCycleCredit(),
                account.currentCycleDebit(), record.amount());
        if (account.creditLimit().compareTo(working) < 0) {
            standing = DeclineReason.OVER_CREDIT_LIMIT;
        }
        if (account.expirationDate().compareTo(capturedDate(record.originTimestamp())) < 0) {
            standing = DeclineReason.ACCOUNT_EXPIRED;
        }
        return standing;
    }

    /**
     * Computes {@code WS-TEMP-BAL} the way {@code app/cbl/CBTRN02C.cbl:L403-L405} computes it.
     *
     * <p>Two arithmetic steps at {@link PicClause#WS_TEMP_BAL_SCALE}, then the single store into
     * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}.
     *
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     * @param amount      {@code DALYTRAN-AMT} at {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the narrowed working balance {@code app/cbl/CBTRN02C.cbl:L407} compares
     */
    private static BigDecimal workingBalance(BigDecimal cycleCredit, BigDecimal cycleDebit,
            BigDecimal amount) {
        BigDecimal difference =
                CobolDecimal.subtract(cycleCredit, cycleDebit, PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed =
                CobolDecimal.add(difference, amount, PicClause.WS_TEMP_BAL_SCALE);
        return CobolDecimal.truncateToPictureField(computed, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE);
    }

    /**
     * Renders one amount the way the expectation files write money.
     *
     * @param amount the value to render
     * @return the value at the amount scale, without an exponent
     */
    private static String plainMoney(BigDecimal amount) {
        return amount.setScale(PicClause.DALYTRAN_AMT_SCALE, RoundingMode.DOWN).toPlainString();
    }

    /** Renders a boolean as the expectation files write it. */
    private static String yesNo(boolean value) {
        return value ? ExpectedOutcomes.YES : ExpectedOutcomes.NO;
    }

    /**
     * Returns one paragraph of {@value #VALIDATION_PROGRAM} as normalised lines.
     *
     * <p>Bounded by the next paragraph label rather than by {@code EXIT.}, because several
     * paragraphs of this program carry no {@code EXIT.} and a reader that stops at the first one
     * answers questions about the following paragraph instead.</p>
     *
     * @param label the paragraph label, without its full stop
     * @return its lines, with runs of spaces collapsed and comments removed
     */
    private static List<String> paragraphLines(String label) {
        List<String> normalised = programLines().stream()
                .map(line -> line.replaceAll("\\s+", " ").strip())
                .filter(line -> !line.startsWith("*") && !line.isEmpty())
                .toList();
        int start = normalised.indexOf(label + ".");
        if (start < 0) {
            throw new IllegalStateException(VALIDATION_PROGRAM + " holds no paragraph " + label);
        }

        List<String> paragraph = new ArrayList<>();
        for (int index = start + 1; index < normalised.size(); index++) {
            String line = normalised.get(index);
            if (line.matches("\\d{4}(-[A-Z0-9]+)*\\.")) {
                break;
            }
            paragraph.add(line);
            if ("EXIT.".equals(line)) {
                break;
            }
        }
        return List.copyOf(paragraph);
    }

    /**
     * Returns the position of the first line holding a fragment.
     *
     * @param lines    the paragraph lines
     * @param fragment the text to find
     * @return the index of the first match
     */
    private static int indexOfLineContaining(List<String> lines, String fragment) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new IllegalStateException(
                VALIDATION_PROGRAM + " holds no line carrying " + fragment);
    }

    /**
     * Returns the last reason a paragraph assigns, which is the one a collision leaves standing.
     *
     * @param lines the paragraph lines
     * @return the numeric code as the source writes it
     */
    private static String lastReasonAssignedIn(List<String> lines) {
        String last = null;
        for (String line : lines) {
            Matcher matcher = Pattern.compile("MOVE (\\d{3}) TO WS-VALIDATION-FAIL-REASON")
                    .matcher(line);
            if (matcher.find()) {
                last = matcher.group(1);
            }
        }
        if (last == null) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " assigns no reason in the paragraph offered");
        }
        return last;
    }

    /**
     * Returns the operator the credit-limit comparison uses, stated limit first.
     *
     * @param lines the paragraph lines
     * @return the operator as the source writes it
     */
    private static String comparisonOperatorIn(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = Pattern.compile("ACCT-CREDIT-LIMIT +(>=|<=|>|<|=) +WS-TEMP-BAL")
                    .matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException(VALIDATION_PROGRAM
                + " holds no comparison of ACCT-CREDIT-LIMIT to WS-TEMP-BAL");
    }

    /**
     * Slices the ten characters {@code app/cbl/CBTRN02C.cbl:L414} compares.
     *
     * @param originTimestamp the twenty-six character capture moment
     * @return the leading ten characters, as text
     */
    private static String capturedDate(String originTimestamp) {
        return CopybookRecordParser.timestampDatePart(originTimestamp);
    }

    /**
     * Counts how many records of the feed the documented rule assigns each reason.
     *
     * @param documented one entry per fixture record, {@code null} where the record passed
     * @return a count per reason, holding no entry for a reason the feed never reaches
     */
    private static Map<DeclineReason, Long> reasonCensus(List<DeclineReason> documented) {
        Map<DeclineReason, Long> census = new EnumMap<>(DeclineReason.class);
        for (DeclineReason reason : documented) {
            if (reason != null) {
                census.merge(reason, 1L, Long::sum);
            }
        }
        return census;
    }

    /**
     * Counts the fixture records the documented rule assigns one reason.
     *
     * @param reason the reject reason to count
     * @return the count, derived from the feed on every call
     */
    private static long fixtureReachCount(DeclineReason reason) {
        return reasonCensus(FEED.stream().map(
                AuthorizationDecisionEquivalenceTest::documentedReason).toList())
                .getOrDefault(reason, UNREACHABLE_FROM_FIXTURES);
    }

    /**
     * Reads the stateless decline count from {@value #EXPECTED_SUMMARY_FILE}.
     *
     * <p>The pair of {@link #MODEL_A_ENTITY} and {@link #DECLINED_COUNT_FIELD} identifies the row
     * on its own, so the sequence column plays no part. {@code posting.declined_count} carries the
     * cumulative count of the other model and is a different row, which is why the entity is named
     * rather than the field alone.</p>
     *
     * @return the checked-in count
     * @throws IllegalStateException when the file holds no such row, or more than one
     */
    private static long expectedModelADeclineCount() {
        return EXPECTED_SUMMARY.count(MODEL_A_ENTITY, DECLINED_COUNT_FIELD);
    }

    /**
     * Reads every reject reason the two validation paragraphs assign, from the program itself.
     *
     * <p>The scan spans the label {@code 1500-A-LOOKUP-XREF.} to the {@code EXIT.} closing
     * {@code 1500-B-LOOKUP-ACCT}, so an assignment elsewhere takes no part. Each
     * {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON} pairs with the quoted {@code MOVE} after it.
     *
     * @return the code of each assignment mapped to its text
     */
    private static Map<String, String> assignmentsInValidationSection() {
        Map<String, String> assignments = new TreeMap<>();
        String pending = null;
        for (String line : validationSection()) {
            Matcher code = REASON_ASSIGNMENT.matcher(line);
            if (code.find()) {
                pending = code.group(1);
                continue;
            }
            Matcher text = QUOTED_MOVE.matcher(line);
            if (pending != null && text.find()) {
                assignments.put(pending, text.group(1));
                pending = null;
            }
        }
        return assignments;
    }

    /**
     * Reads the statement following the {@code END-IF} that closes the credit-limit test.
     *
     * <p>The scan starts at the over-limit assignment, passes its text {@code MOVE}, stops at the
     * {@code END-IF} closing that test, and answers with the next statement. Comment and blank
     * lines take no part. The span is {@code app/cbl/CBTRN02C.cbl:L410-L414}.
     *
     * @return that statement, stripped of leading and trailing space
     * @throws IllegalStateException when the paragraph ends before the statement is reached
     */
    private static String statementAfterTheCreditTest() {
        List<String> section = validationSection();
        boolean creditTestClosed = false;

        for (int index = assignmentLineOf(section, DeclineReason.OVER_CREDIT_LIMIT) + 1;
                index < section.size(); index++) {
            String line = section.get(index).strip();
            if (line.isEmpty() || line.startsWith("*")) {
                continue;
            }
            if (creditTestClosed) {
                return line;
            }
            creditTestClosed = line.equals("END-IF");
        }
        throw new IllegalStateException(VALIDATION_PROGRAM + " paragraph " + ACCOUNT_PARAGRAPH
                + " ends after the credit test with no statement following it");
    }

    /**
     * Finds the line assigning one reject reason inside the validation section.
     *
     * @param section the lines of the two validation paragraphs
     * @param reason  the reject reason to locate
     * @return the index of the assignment
     * @throws IllegalStateException when the section assigns no such code
     */
    private static int assignmentLineOf(List<String> section, DeclineReason reason) {
        String assignment = String.valueOf(reason.numericCode());
        for (int index = 0; index < section.size(); index++) {
            Matcher code = REASON_ASSIGNMENT.matcher(section.get(index));
            if (code.find() && code.group(1).equals(assignment)) {
                return index;
            }
        }
        throw new IllegalStateException(
                VALIDATION_PROGRAM + " assigns no reject reason " + reason.code());
    }

    /**
     * Reads the two validation paragraphs of {@code app/cbl/CBTRN02C.cbl}.
     *
     * @return the lines from the first paragraph label to the {@code EXIT.} closing the second
     * @throws IllegalStateException when the program or either paragraph is absent
     */
    private static List<String> validationSection() {
        List<String> program = programLines();
        int start = labelLineOf(program, CROSS_REFERENCE_PARAGRAPH);
        int second = labelLineOf(program, ACCOUNT_PARAGRAPH);

        for (int index = second; index < program.size(); index++) {
            if (program.get(index).strip().equals("EXIT.")) {
                return List.copyOf(program.subList(start, index + 1));
            }
        }
        throw new IllegalStateException(
                VALIDATION_PROGRAM + " holds no EXIT closing " + ACCOUNT_PARAGRAPH);
    }

    /**
     * Finds one paragraph label.
     *
     * @param program the program lines
     * @param label   the paragraph name, without its full stop
     * @return the index of the label line
     * @throws IllegalStateException when the program holds no such label
     */
    private static int labelLineOf(List<String> program, String label) {
        for (int index = 0; index < program.size(); index++) {
            if (program.get(index).strip().equals(label + ".")) {
                return index;
            }
        }
        throw new IllegalStateException(VALIDATION_PROGRAM + " holds no paragraph " + label);
    }

    /**
     * Reads {@code app/cbl/CBTRN02C.cbl} from the repository, without writing to it.
     *
     * @return every line of the program
     * @throws IllegalStateException when no ancestor of the fixture directory holds the program
     */
    private static List<String> programLines() {
        for (Path candidate = CardDemoFixtureLoader.fixtureDirectory();
                candidate != null; candidate = candidate.getParent()) {
            Path program = candidate.resolve(VALIDATION_PROGRAM);
            if (Files.isRegularFile(program)) {
                try {
                    return Files.readAllLines(program, StandardCharsets.ISO_8859_1);
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot read " + program, unreadable);
                }
            }
        }
        throw new IllegalStateException("no ancestor of '"
                + CardDemoFixtureLoader.fixtureDirectory() + "' holds '" + VALIDATION_PROGRAM
                + "'");
    }

    /**
     * Computes the Luhn remainder of a card number.
     *
     * <p>No CardDemo program computes it. The value serves one assertion, that the digits an
     * approval is granted for need not satisfy it.
     *
     * @param cardNumber sixteen digits
     * @return the checksum remainder, zero when the digits satisfy the algorithm
     */
    private static int luhnRemainder(String cardNumber) {
        int total = 0;
        for (int offset = 0; offset < cardNumber.length(); offset++) {
            int digit = cardNumber.charAt(cardNumber.length() - 1 - offset) - '0';
            if (offset % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }
        return total % 10;
    }

    /**
     * @param declaring the class to read
     * @return every declared field name
     */
    private static Set<String> declaredFieldNames(Class<?> declaring) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : declaring.getDeclaredFields()) {
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Builds the request one fixture record carries.
     *
     * @param record one record of {@code app/data/ASCII/dailytran.txt}
     * @return the request body, naming the card and no account identifier
     */
    private static AuthorizationRequest requestFor(DailyTransactionRecord record) {
        return new AuthorizationRequest(null, record.typeCode(), record.categoryCode(),
                record.source(), record.description(), record.amount().toPlainString(),
                record.merchantId(), record.merchantName(), record.merchantCity(),
                record.merchantZip(), record.cardNumber(), record.originTimestamp(),
                PROCESSING_TIMESTAMP, null);
    }

    /**
     * @param cardNumber      the card number, at whatever width the case exercises
     * @param amount          the transaction amount
     * @param originTimestamp the twenty-six character capture moment
     * @return the request body, naming no account identifier
     */
    private static AuthorizationRequest request(String cardNumber, BigDecimal amount,
            String originTimestamp) {
        return new AuthorizationRequest(null, "01", "0001", "POS TERM",
                "SYNTHETIC AUTHORIZATION", amount.toPlainString(), "000000001",
                "SYNTHETIC MERCHANT", "SYNTHETIC CITY", "00000", cardNumber, originTimestamp,
                PROCESSING_TIMESTAMP, null);
    }

    /**
     * Builds one cross-reference row of {@code app/cpy/CVACT03Y.cpy}.
     *
     * @param cardNumber sixteen characters, from {@code XREF-CARD-NUM}
     * @param accountId  eleven characters, from {@code XREF-ACCT-ID}
     * @return the row
     */
    private static CardCrossReferenceEntity crossReference(String cardNumber, String accountId) {
        return new CardCrossReferenceEntity(cardNumber, SYNTHETIC_CUSTOMER_ID, accountId,
                OBSERVED_AT);
    }

    /**
     * Builds one credit snapshot of the fields {@code app/cpy/CVACT01Y.cpy} supplies.
     *
     * @param accountId      eleven characters, from {@code ACCT-ID}
     * @param creditLimit    from {@code ACCT-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy:L8}
     * @param expirationDate from {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11}
     * @param cycleCredit    from {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit     from {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     * @return the snapshot
     */
    private static AccountCreditSnapshotEntity account(String accountId, BigDecimal creditLimit,
            String expirationDate, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        return new AccountCreditSnapshotEntity(accountId, creditLimit, expirationDate, cycleCredit,
                cycleDebit, OBSERVED_AT);
    }

    /**
     * One authorization service over two in-memory replica tables.
     *
     * @param service         the service under comparison
     * @param rules           the four rules, in the order the source runs them
     * @param crossReferences the table {@code card_xref} stands in for
     * @param accounts        the table {@code account_credit_snapshot} stands in for
     */
    private record DecisionHarness(AuthorizationService service, List<DeclineRule> rules,
            CardCrossReferenceTable crossReferences, AccountCreditSnapshotTable accounts) {

        /**
         * @param crossReferenceRows rows keyed by sixteen-character card number
         * @param accountRows        rows keyed by eleven-character account identifier
         * @return the harness
         */
        private static DecisionHarness overRows(
                Map<String, CardCrossReferenceEntity> crossReferenceRows,
                Map<String, AccountCreditSnapshotEntity> accountRows) {

            CardCrossReferenceTable crossReferences =
                    new CardCrossReferenceTable(crossReferenceRows);
            AccountCreditSnapshotTable accounts = new AccountCreditSnapshotTable(accountRows);
            CycleExposureReservation reservation =
                    new CycleExposureReservation(accounts, replicaProperties());
            List<DeclineRule> rules = List.of(new CardCrossReferenceRule(crossReferences),
                    new AccountExistsRule(accounts), new CreditLimitRule(reservation),
                    new AccountExpirationRule());

            AuthorizationService service = new AuthorizationService(rules, crossReferences,
                    new FixedIdentifierSource(),
                    new OutboxWriter(writeOnlySeam(OutboxEventRepository.class)),
                    writeOnlySeam(UnresolvedCardAttemptRepository.class),
                    writeOnlySeam(AuthorizationDecisionRepository.class),
                    new SimpleMeterRegistry(), immediateTransactions(), replicaProperties(),
                    reservation, caughtUpReplica(), noReplicaGaps());
            return new DecisionHarness(service, rules, crossReferences, accounts);
        }

        /**
         * @return the harness
         */
        private static DecisionHarness overFixtures() {
            Map<String, CardCrossReferenceEntity> crossReferenceRows = new LinkedHashMap<>();
            for (CardCrossReferenceRecord row : FIXTURE_CROSS_REFERENCES.values()) {
                crossReferenceRows.put(row.cardNumber(), new CardCrossReferenceEntity(
                        row.cardNumber(), row.customerId(), row.accountId(), OBSERVED_AT));
            }
            Map<String, AccountCreditSnapshotEntity> accountRows = new LinkedHashMap<>();
            for (AccountRecord row : FIXTURE_ACCOUNTS.values()) {
                accountRows.put(row.accountId(), account(row.accountId(), row.creditLimit(),
                        row.expirationDate(), row.currentCycleCredit(), row.currentCycleDebit()));
            }
            return overRows(crossReferenceRows, accountRows);
        }

        /**
         * @param creditLimit    from {@code ACCT-CREDIT-LIMIT}
         * @param expirationDate from {@code ACCT-EXPIRAION-DATE}
         * @param cycleCredit    from {@code ACCT-CURR-CYC-CREDIT}
         * @param cycleDebit     from {@code ACCT-CURR-CYC-DEBIT}
         * @return the harness
         */
        private static DecisionHarness overSyntheticAccount(BigDecimal creditLimit,
                String expirationDate, BigDecimal cycleCredit, BigDecimal cycleDebit) {
            return overRows(
                    Map.of(SYNTHETIC_CARD_NUMBER,
                            crossReference(SYNTHETIC_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID)),
                    Map.of(SYNTHETIC_ACCOUNT_ID, account(SYNTHETIC_ACCOUNT_ID, creditLimit,
                            expirationDate, cycleCredit, cycleDebit)));
        }

        /**
         * @param request the request body
         * @return the decision the caller receives
         */
        private Outcome authorize(AuthorizationRequest request) {
            return service.authorize(request, CALLER);
        }
    }

    /**
     * The {@code card_xref} replica, keyed by {@code KEYS(16 0)} at {@code XREFFILE.jcl:L43}.
     *
     * <p>The card-number read matches all sixteen characters and nothing shorter, the
     * {@code VARCHAR(16)} comparison the column performs. The account-number read answers with a
     * list, which {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:L75} permits.
     */
    private static final class CardCrossReferenceTable implements CardCrossReferenceRepository {

        private final Map<String, CardCrossReferenceEntity> rows;

        private long cardLookups;

        private CardCrossReferenceTable(Map<String, CardCrossReferenceEntity> rows) {
            this.rows = new LinkedHashMap<>(rows);
        }

        /**
         * Counts the card-number reads this table served.
         *
         * @return the count since construction
         */
        private long cardLookupCount() {
            return cardLookups;
        }

        @Override
        public Optional<CardCrossReferenceEntity> findByCardNumber(String cardNumber) {
            cardLookups++;
            return Optional.ofNullable(rows.get(cardNumber));
        }

        @Override
        public List<CardCrossReferenceEntity> findByAccountIdOrderByCardNumberAsc(
                String accountId) {
            return rows.values().stream()
                    .filter(row -> row.getAccountId().equals(accountId))
                    .sorted(Comparator.comparing(CardCrossReferenceEntity::getCardNumber))
                    .toList();
        }

        @Override
        public Optional<CardCrossReferenceEntity> findFirstByAccountIdOrderByCardNumberAsc(
                String accountId) {
            return findByAccountIdOrderByCardNumberAsc(accountId).stream().findFirst();
        }

        @Override
        public int applyStateChange(String cardNumber, String customerId, String accountId,
                UUID sourceEventId, Instant sourceOccurredAt, Instant observedAt) {
            throw refusal("applyStateChange");
        }

        @Override
        public int refreshObservation(String accountId, String cardNumber,
                UUID sourceEventId, Instant sourceOccurredAt, Instant observedAt) {
            throw refusal("refreshObservation");
        }

        @Override
        public long countByObservedAtBefore(Instant cutoff) {
            throw refusal("countByObservedAtBefore");
        }

        @Override
        public <S extends CardCrossReferenceEntity> S save(S row) {
            throw refusal("save");
        }

        @Override
        public <S extends CardCrossReferenceEntity> List<S> saveAll(Iterable<S> batch) {
            throw refusal("saveAll");
        }

        @Override
        public Optional<CardCrossReferenceEntity> findById(String cardNumber) {
            throw refusal("findById");
        }

        @Override
        public boolean existsById(String cardNumber) {
            throw refusal("existsById");
        }

        @Override
        public List<CardCrossReferenceEntity> findAll() {
            throw refusal("findAll");
        }

        @Override
        public List<CardCrossReferenceEntity> findAllById(Iterable<String> cardNumbers) {
            throw refusal("findAllById");
        }

        @Override
        public long count() {
            throw refusal("count");
        }

        @Override
        public void deleteById(String cardNumber) {
            throw refusal("deleteById");
        }

        @Override
        public void delete(CardCrossReferenceEntity row) {
            throw refusal("delete");
        }

        @Override
        public void deleteAllById(Iterable<? extends String> cardNumbers) {
            throw refusal("deleteAllById");
        }

        @Override
        public void deleteAll(Iterable<? extends CardCrossReferenceEntity> batch) {
            throw refusal("deleteAll");
        }

        @Override
        public void deleteAll() {
            throw refusal("deleteAll");
        }

        private static UnsupportedOperationException refusal(String member) {
            return new UnsupportedOperationException(
                    "CardCrossReferenceRepository." + member + " takes no part in an "
                            + "authorization decision");
        }
    }

    /**
     * The {@code account_credit_snapshot} replica, keyed by the eleven characters
     * {@code ACCT-ID PIC 9(11)} holds at {@code app/cpy/CVACT01Y.cpy:L5}.
     *
     * <p>The read matches on the stored identifier text, leading zeros included, which is the
     * {@code CHAR(11)} comparison the column performs.
     */
    private static final class AccountCreditSnapshotTable
            implements AccountCreditSnapshotRepository {

        private final Map<String, AccountCreditSnapshotEntity> rows;

        /** The reserved figures each approval recorded, by account identifier. */
        private final Map<String, List<BigDecimal>> reservations = new LinkedHashMap<>();

        /** How many times the account read ran. */
        private long accountLookups;

        private AccountCreditSnapshotTable(Map<String, AccountCreditSnapshotEntity> rows) {
            this.rows = new LinkedHashMap<>(rows);
        }

        /**
         * Counts the account reads this table served.
         *
         * @return the count since construction
         */
        private long accountLookupCount() {
            return accountLookups;
        }

        @Override
        public Optional<AccountCreditSnapshotEntity> findByAccountId(String accountId) {
            accountLookups++;
            return Optional.ofNullable(rows.get(accountId));
        }

        /**
         * Serves the locked read the decision path issues, and counts it as the account read.
         *
         * <p>The lock itself has nothing to hold here: one harness replays records on one thread, which
         * is the shape {@code app/cbl/CBTRN02C.cbl} runs in. What the lock exists for is two concurrent
         * HTTP calls for one account, and {@code NativeStatementIT} measures it against a real database.
         *
         * @param accountId the eleven-digit account identifier
         * @return the row, or empty for reject reason 101 at {@code app/cbl/CBTRN02C.cbl:L397-L399}
         */
        @Override
        public Optional<AccountCreditSnapshotEntity> findForUpdateByAccountId(String accountId) {
            return findByAccountId(accountId);
        }

        /**
         * Answers the lock-wait bound the decision applies, and applies nothing.
         *
         * <p>There is no connection to bound: this table is a map. The production statement is
         * {@code set_config('lock_timeout', ?, true)}, and {@code NativeStatementIT} measures that it
         * takes effect.
         *
         * @param milliseconds the bound the decision path passes, as a PostgreSQL interval string
         * @return the same value, which the caller reads for nothing
         */
        @Override
        public String applyLockWaitBound(String milliseconds) {
            return milliseconds;
        }

        /**
         * Records the exposure one approval reserved, and reports the row unchanged.
         *
         * <p>The reservation is accepted rather than refused, because an approval that could not
         * reserve would fail rather than approve, and these tests measure decisions. It moves no figure
         * on the row, and that models the source rather than departing from it:
         * {@code app/cbl/CBTRN02C.cbl} posts each record before it validates the next, so by the time
         * {@code :L403-L405} reads the accumulators for record N+1 the posting of record N has already
         * been absorbed into them and nothing is outstanding. Each record of
         * {@code app/data/ASCII/dailytran.txt} is therefore decided against the opening figures of
         * {@code app/data/ASCII/acctdata.txt}, which is what the checked-in expectations hold.
         *
         * @param accountId          the eleven-digit account identifier
         * @param pendingCycleCredit the reserved cycle credit the decision computed
         * @param pendingCycleDebit  the reserved cycle debit the decision computed
         * @param pendingExpiresAt   when the reservation would stop counting
         * @return 1 for a row this table holds, and {@link #NO_ROW_WRITTEN} for one it does not
         */
        @Override
        public int reserveCycleExposure(String accountId, BigDecimal pendingCycleCredit,
                BigDecimal pendingCycleDebit, Instant pendingExpiresAt) {

            if (!rows.containsKey(accountId)) {
                return NO_ROW_WRITTEN;
            }
            reservations.put(accountId, List.of(pendingCycleCredit, pendingCycleDebit));
            return 1;
        }

        /**
         * Returns the reserved figures one approval recorded against an account.
         *
         * @param accountId the eleven-digit account identifier
         * @return the reserved cycle credit followed by the reserved cycle debit, or an empty list
         *         where no approval reserved against that account
         */
        private List<BigDecimal> reservationFor(String accountId) {
            return reservations.getOrDefault(accountId, List.of());
        }

        @Override
        public AccountCreditSnapshotEntity save(AccountCreditSnapshotEntity snapshot) {
            throw refusal("save");
        }

        @Override
        public long count() {
            throw refusal("count");
        }

        @Override
        public int applyStateChange(String accountId, BigDecimal creditLimit,
                String accountExpirationDate, BigDecimal currentCycleCredit,
                BigDecimal currentCycleDebit, UUID sourceEventId,
                Instant sourceOccurredAt, Instant observedAt) {
            throw refusal("applyStateChange");
        }

        @Override
        public long countByObservedAtBefore(Instant cutoff) {
            throw refusal("countByObservedAtBefore");
        }

        private static UnsupportedOperationException refusal(String member) {
            return new UnsupportedOperationException(
                    "AccountCreditSnapshotRepository." + member + " takes no part in an "
                            + "authorization decision");
        }
    }

    /**
     * A repository the decision only writes to, answering {@code save} with the row it was given.
     *
     * <p>The three write-side repositories record an attempt, a decision row and an outbox row.
     * This comparison reads none of them, and the service discards every return value. Any other
     * member refuses, so an unexpected call fails loudly and names itself.
     *
     * @param <R>  the repository interface
     * @param seam the repository interface to stand in for
     * @return an instance of {@code seam}
     */
    private static <R> R writeOnlySeam(Class<R> seam) {
        InvocationHandler handler = (proxy, member, arguments) -> switch (member.getName()) {
            case "save" -> arguments[0];
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> seam.getSimpleName() + "[write only]";
            default -> throw new UnsupportedOperationException(seam.getSimpleName() + "."
                    + member.getName() + " takes no part in an authorization decision");
        };
        return seam.cast(Proxy.newProxyInstance(seam.getClassLoader(), new Class<?>[] {seam},
                handler));
    }

    /**
     * The identifier sequence, answering with one fixed value.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} browses the file backwards and adds one, which the
     * target replaces with a database sequence. Nothing here reads a database.
     */
    private static final class FixedIdentifierSource extends TransactionIdentifierSource {

        private FixedIdentifierSource() {
            super(null, "");
        }

        @Override
        public String nextIdentifier() {
            return SYNTHETIC_TRANSACTION_ID;
        }
    }

    /**
     * @return a template that opens no transaction
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /**
     * @return properties carrying a replica window wide enough for a fixture capture moment
     */
    private static AuthorizationProperties replicaProperties() {
        return new AuthorizationProperties(null, null, null, null,
                new AuthorizationProperties.Replica(LAG_CEILING),
                new AuthorizationProperties.Decision(LOCK_WAIT_MS, RESERVATION_TTL));
    }

    /**
     * The replica verdict this harness authorizes under.
     *
     * <p>The harness stands two in-memory tables where {@code card_xref} and
     * {@code account_credit_snapshot} sit and runs no Kafka listener, so there is no stream to measure
     * and nothing that could be behind one. Declaring the streams caught up is what keeps these
     * comparisons about the four decline rules of {@code app/cbl/CBTRN02C.cbl:L380-L420}, which is the
     * parity this class exists to establish. The verdict itself is measured by
     * {@code KafkaReplicaSynchronizationTest} in the authorization module.
     *
     * @return a verdict reporting a stream with nothing waiting on it
     */
    private static ReplicaSynchronization caughtUpReplica() {
        return () -> ReplicaSynchronization.Verdict.synchronizedAt(0L);
    }

    /**
     * The gap record this harness reads, which never holds a row.
     *
     * <p>A gap row is written by a replica listener that could not apply a delivered change, and this
     * harness runs no listener. The read-only seam answers no open gap for every account, so no
     * comparison below is refused by a condition the source has no counterpart for.
     *
     * @return a repository seam reporting no open gap
     */
    private static ReplicaGapRepository noReplicaGaps() {
        InvocationHandler handler = (proxy, member, arguments) -> switch (member.getName()) {
            case "existsForAggregate" -> Boolean.FALSE;
            case "countOpenGaps" -> 0L;
            case "findByAggregateAndStream" -> java.util.Optional.empty();
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ReplicaGapRepository[no open gap]";
            default -> throw new UnsupportedOperationException("ReplicaGapRepository."
                    + member.getName() + " takes no part in an authorization decision");
        };
        return (ReplicaGapRepository) Proxy.newProxyInstance(
                ReplicaGapRepository.class.getClassLoader(),
                new Class<?>[] {ReplicaGapRepository.class}, handler);
    }

    /** The lock-wait bound the harness configures, matching the shipped default. */
    private static final long LOCK_WAIT_MS = 3_000L;

    /**
     * How long a reservation counts under the harness configuration.
     *
     * <p>No decision here reaches an expiry: the harness reserves and reports the row unchanged, for the
     * reason {@code AccountCreditSnapshotTable#reserveCycleExposure} carries.
     */
    private static final java.time.Duration RESERVATION_TTL = java.time.Duration.ofMinutes(15);

    @Nested
    @DisplayName(EXPECTED_SYNTHETIC_FILE + " bound row by row")
    class CheckedInSyntheticBoundaryCases {

        @Test
        @DisplayName("every row matches the constructed case, the rule chain or the source")
        void everyRowOfTheSyntheticExpectationsMatches() {
            for (ExpectedOutcomes.Row row : EXPECTED_SYNTHETIC.rows()) {
                String expected = EXPECTED_SYNTHETIC.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());

                assertEquals(expected, actualSyntheticValue(row),
                        EXPECTED_SYNTHETIC_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_SYNTHETIC.unconsumedRows())
                    .as(EXPECTED_SYNTHETIC.unconsumedDescription())
                    .isEmpty();
        }
    }

    /**
     * Resolves what a constructed case, the rule chain or the source holds for one row.
     *
     * <p>Every case is synthetic, so its inputs are chosen rather than measured. The choice is
     * declared in {@link #syntheticCases()} and each choice carries the reason the fixtures cannot
     * reach the behaviour. Every outcome, every reason code, every message and every source fact is
     * derived. No branch reads {@code expected_value}.</p>
     *
     * @param row the expectation to resolve
     * @return the value the row must equal
     * @throws IllegalStateException when the row names a field this method does not resolve
     */
    private static String actualSyntheticValue(ExpectedOutcomes.Row row) {
        return switch (row.recordSequence()) {
            case "SYN-100-XREF-MISS" -> unresolvedCardValue(row.expectedField(), row.entityKey());
            case "SYN-101-ACCT-MISS" -> unresolvedAccountValue(row.expectedField(),
                    row.entityKey());
            case "SYN-103-EXPIRED" -> expiredAccountValue(row.expectedField());
            case "SYN-103-BOUNDARY-EQUAL" -> expiryEqualityValue(row.expectedField());
            case "SYN-102-103-COLLISION" -> collisionValue(row.expectedField());
            case "SYN-102-BILLION-NARROWED" -> narrowingValue(row.expectedField(),
                    BILLION_CYCLE_CREDIT, ZERO_MONEY);
            case "SYN-102-JUST-BELOW-BILLION" -> narrowingValue(row.expectedField(),
                    JUST_BELOW_BILLION_CYCLE_CREDIT, ZERO_MONEY);
            case "SYN-102-NARROWED-EQUALITY" -> narrowingValue(row.expectedField(),
                    BILLION_CYCLE_CREDIT, NARROWED_EQUALITY_AMOUNT);
            case "SYN-109-REWRITE-FAIL" -> rewriteFailureValue(row.expectedField());
            case "SYN-TRUNC-NEGATIVE" -> negativeTruncationValue(row.expectedField());
            case "SYN-CATBAL-CEILING" -> categoryCeilingValue(row.expectedField());
            case "COVERAGE" -> unreachabilityValue(row.expectedField());
            case "SETUP" -> syntheticSetupValue(row.expectedField());
            case "SUMMARY" -> syntheticSummaryValue(row.expectedField());
            case "PROHIBITION" -> syntheticProhibitionValue(row.expectedField());
            default -> rateResolutionCaseValue(row);
        };
    }

    /** Resolves the case where the cross-reference read misses. */
    private static String unresolvedCardValue(String field, String entityKey) {
        return switch (field) {
            case "input_card_number" -> assertAbsentFromCrossReference(entityKey);
            case "xref_read_result" -> INVALID_KEY_CONDITION;
            case "expected_reason_code" -> DeclineReason.INVALID_CARD_NUMBER.code();
            case "expected_reason_text" -> DeclineReason.INVALID_CARD_NUMBER.description();
            case "account_lookup_evaluated", "credit_limit_rule_evaluated",
                    "expiration_rule_evaluated" -> yesNo(false);
            case "outcome" -> DECLINED_OUTCOME;
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved card field " + field);
        };
    }

    /** Resolves the case where the cross-reference resolves but the account read misses. */
    private static String unresolvedAccountValue(String field, String entityKey) {
        return switch (field) {
            case "input_card_number" -> SYNTHETIC_XREF_CARD_NUMBER;
            case "xref_resolved_account" -> assertAbsentFromAccounts(entityKey);
            case "xref_read_result" -> NORMAL_CONDITION;
            case "account_read_result" -> INVALID_KEY_CONDITION;
            case "expected_reason_code" -> DeclineReason.ACCOUNT_NOT_FOUND.code();
            case "expected_reason_text" -> DeclineReason.ACCOUNT_NOT_FOUND.description();
            case "credit_limit_rule_evaluated", "expiration_rule_evaluated" -> yesNo(false);
            case "outcome" -> DECLINED_OUTCOME;
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved account field " + field);
        };
    }

    /** Resolves the case where the capture moment falls one day past the account expiry. */
    private static String expiredAccountValue(String field) {
        AccountRecord account = earliestExpiringAccount();
        String captured = dayAfter(account.expirationDate());
        return switch (field) {
            case "input_account_id" -> account.accountId();
            case "acct_expiration_date" -> account.expirationDate();
            case "acct_credit_limit" -> plainMoney(account.creditLimit());
            case "input_orig_ts" -> midnightTimestamp(captured);
            case "orig_ts_first_10_chars" -> captured;
            case "input_amount" -> plainMoney(SMALL_SYNTHETIC_AMOUNT);
            case "cyc_credit_state", "cyc_debit_state" -> plainMoney(ZERO_MONEY);
            case "ws_temp_bal" -> plainMoney(
                    workingBalance(ZERO_MONEY, ZERO_MONEY, SMALL_SYNTHETIC_AMOUNT));
            case "credit_limit_rule_fires" -> yesNo(account.creditLimit().compareTo(
                    workingBalance(ZERO_MONEY, ZERO_MONEY, SMALL_SYNTHETIC_AMOUNT)) < 0);
            case "expected_reason_code" -> DeclineReason.ACCOUNT_EXPIRED.code();
            case "expected_reason_text" -> DeclineReason.ACCOUNT_EXPIRED.description();
            case "comparison_is_alphanumeric_not_date" -> yesNo(theExpiryComparisonIsTextual());
            case "outcome" -> DECLINED_OUTCOME;
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved expiry field " + field);
        };
    }

    /** Resolves the case where the capture date equals the expiry date exactly. */
    private static String expiryEqualityValue(String field) {
        AccountRecord account = earliestExpiringAccount();
        return switch (field) {
            case "input_account_id" -> account.accountId();
            case "acct_expiration_date" -> account.expirationDate();
            case "input_orig_ts" -> midnightTimestamp(account.expirationDate());
            case "orig_ts_first_10_chars" -> account.expirationDate();
            case "comparison_operator" ->
                    comparisonOperatorIn(paragraphLines(ACCOUNT_LOOKUP_PARAGRAPH));
            case "equality_approves" -> yesNo(account.expirationDate()
                    .compareTo(account.expirationDate()) >= 0);
            case "expected_reason_code" -> NO_DECLINE_CODE;
            case "outcome" -> APPROVED_OUTCOME;
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved equality field " + field);
        };
    }

    /** Resolves the case where both the credit-limit rule and the expiry rule would fire. */
    private static String collisionValue(String field) {
        AccountRecord account = smallestLimitAccount();
        String captured = dayAfter(account.expirationDate());
        BigDecimal amount = COLLISION_AMOUNT;
        BigDecimal working = workingBalance(ZERO_MONEY, ZERO_MONEY, amount);
        return switch (field) {
            case "input_account_id" -> account.accountId();
            case "acct_credit_limit" -> plainMoney(account.creditLimit());
            case "acct_expiration_date" -> account.expirationDate();
            case "input_amount" -> plainMoney(amount);
            case "input_orig_ts" -> midnightTimestamp(captured);
            case "ws_temp_bal" -> plainMoney(working);
            case "credit_limit_rule_condition_true" ->
                    yesNo(account.creditLimit().compareTo(working) < 0);
            case "reason_code_after_credit_limit_rule" ->
                    DeclineReason.OVER_CREDIT_LIMIT.code();
            case "expiration_rule_condition_true" ->
                    yesNo(account.expirationDate().compareTo(captured) < 0);
            case "expiration_rule_is_gated_on_reason_zero" -> yesNo(theExpiryRuleIsGated());
            case "expected_reason_code" -> DeclineReason.ACCOUNT_EXPIRED.code();
            case "expected_reason_text" -> DeclineReason.ACCOUNT_EXPIRED.description();
            case "resolution_is_last_writer_wins" -> yesNo(!theExpiryRuleIsGated()
                    && Integer.toString(DeclineReason.ACCOUNT_EXPIRED.numericCode())
                            .equals(lastReasonAssignedIn(paragraphLines(ACCOUNT_LOOKUP_PARAGRAPH))));
            case "outcome" -> DECLINED_OUTCOME;
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved collision field " + field);
        };
    }

    /** Resolves one of the three cases that probe the narrowed working balance. */
    private static String narrowingValue(String field, BigDecimal cycleCredit, BigDecimal amount) {
        BigDecimal unnarrowed = CobolDecimal.add(
                CobolDecimal.subtract(cycleCredit, ZERO_MONEY, PicClause.WS_TEMP_BAL_SCALE),
                amount, PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal narrowed = workingBalance(cycleCredit, ZERO_MONEY, amount);
        BigDecimal limit = NARROWING_CREDIT_LIMIT;
        return switch (field) {
            case "input_cyc_credit" -> plainMoney(cycleCredit);
            case "input_cyc_debit" -> plainMoney(ZERO_MONEY);
            case "input_amount" -> plainMoney(amount);
            case "input_credit_limit" -> plainMoney(limit);
            case "true_unnarrowed_value" -> plainMoney(unnarrowed);
            case "ws_temp_bal_after_narrowing" -> plainMoney(narrowed);
            case "high_order_digit_lost" -> yesNo(narrowed.compareTo(unnarrowed) != 0);
            case "narrowed_value_equals_credit_limit" -> yesNo(narrowed.compareTo(limit) == 0);
            case "outcome_with_narrowing" -> outcomeOf(limit, narrowed);
            case "outcome_without_narrowing" -> outcomeOf(limit, unnarrowed);
            case "expected_reason_code" -> limit.compareTo(narrowed) < 0
                    ? DeclineReason.OVER_CREDIT_LIMIT.code()
                    : NO_DECLINE_CODE;
            case "expected_reason_text" -> DeclineReason.OVER_CREDIT_LIMIT.description();
            case "defect_is_reproduced_not_corrected" -> yesNo(
                    PicClause.WS_TEMP_BAL_PRECISION < PicClause.ACCT_CURR_CYC_CREDIT_PRECISION);
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved narrowing field " + field);
        };
    }

    /** Resolves the case that documents the unreachable reason assigned on a rewrite failure. */
    private static String rewriteFailureValue(String field) {
        List<String> update = paragraphLines(ACCOUNT_UPDATE_PARAGRAPH);
        return switch (field) {
            case "trigger" -> REWRITE_FAILURE_TRIGGER;
            case "assigned_reason_code" -> paddedReasonCode(lastReasonAssignedIn(update));
            case "assigned_reason_text", "source_outcome", "target_outcome" ->
                    rewriteFailureNarrative(field);
            case "text_identical_to_reason_101" -> yesNo(DeclineReason.ACCOUNT_NOT_FOUND
                    .description().equals(rewriteFailureNarrative("assigned_reason_text")));
            case "assigned_after_category_balance_write" -> yesNo(postingStepPrecedes(
                    CATEGORY_BALANCE_STEP, ACCOUNT_UPDATE_STEP));
            case "assigned_before_transaction_write" -> yesNo(postingStepPrecedes(
                    ACCOUNT_UPDATE_STEP, TRANSACTION_WRITE_STEP));
            case "inspected_anywhere_in_source" -> yesNo(theUnreachableReasonIsInspected());
            case "is_declared_behaviour_change", "fixture_reachable" ->
                    yesNo("is_declared_behaviour_change".equals(field));
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved rewrite field " + field);
        };
    }

    /** Resolves the case that separates truncation from both alternatives on a negative quotient. */
    private static String negativeTruncationValue(String field) {
        BigDecimal balance = NEGATIVE_CATEGORY_BALANCE;
        BigDecimal rate = UNEXERCISED_RATE;
        BigDecimal product = balance.multiply(rate);
        return switch (field) {
            case "input_tran_cat_bal" -> plainMoney(balance);
            case "input_dis_int_rate" -> rate.setScale(PicClause.DIS_INT_RATE_SCALE,
                    RoundingMode.DOWN).toPlainString();
            case "balance_operand_provenance", "rate_operand_provenance" ->
                    truncationProvenance(field);
            case "pairing_is_synthetic" -> yesNo(true);
            case "compute_gate_opens" -> yesNo(rate.compareTo(BigDecimal.ZERO) != 0);
            case "raw_quotient" -> product
                    .divide(CobolDecimal.INTEREST_DIVISOR, RAW_QUOTIENT_CONTEXT).toPlainString();
            case "expected_value_rounding_down" -> product.divide(CobolDecimal.INTEREST_DIVISOR,
                    PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.DOWN).toPlainString();
            case "value_rounding_half_up" -> product.divide(CobolDecimal.INTEREST_DIVISOR,
                    PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.HALF_UP).toPlainString();
            case "value_rounding_floor" -> product.divide(CobolDecimal.INTEREST_DIVISOR,
                    PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.FLOOR).toPlainString();
            case "distinguishes_down_from_half_up" ->
                    separationMarker(balance, rate, RoundingMode.HALF_UP);
            case "distinguishes_down_from_floor" ->
                    separationMarker(balance, rate, RoundingMode.FLOOR);
            case "rounded_phrase_occurrences_in_app_cbl" ->
                    Long.toString(roundedPhraseOccurrences());
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved truncation field " + field);
        };
    }

    /**
     * Resolves the case where one store receives a sum wider than the field that holds it.
     *
     * <p>Two amounts inside {@code DALYTRAN-AMT PIC S9(09)V99} sum past the nine integer digits
     * {@code TRAN-CAT-BAL PIC S9(09)V99} holds at {@code app/cpy/CVTRA01Y.cpy:L9}. The two
     * {@code ADD} statements that reach that field, at {@code app/cbl/CBTRN02C.cbl:L508} and
     * {@code :L527}, carry no {@code ON SIZE ERROR} phrase, and the census below reports how often
     * the phrase appears anywhere under {@code app/cbl/}. The store therefore keeps the low-order
     * nine integer digits, holds the sign and completes. The last two fields carry the same
     * measurement for the ten integer digits of {@code ACCT-CURR-BAL} at
     * {@code app/cpy/CVACT01Y.cpy:L7}.
     *
     * @param field the field one row names
     * @return the value this platform produces for that field
     */
    private static String categoryCeilingValue(String field) {
        BigDecimal amount = pictureFieldMaximum(PicClause.DALYTRAN_AMT_PRECISION,
                PicClause.DALYTRAN_AMT_SCALE);
        BigDecimal sum = CobolDecimal.add(CEILING_OPENING_BALANCE, amount,
                PicClause.TRAN_CAT_BAL_SCALE);
        BigDecimal stored = CobolDecimal.truncateToPictureField(sum,
                PicClause.TRAN_CAT_BAL_PRECISION, PicClause.TRAN_CAT_BAL_SCALE);
        int fieldIntegerDigits = PicClause.TRAN_CAT_BAL_PRECISION - PicClause.TRAN_CAT_BAL_SCALE;
        return switch (field) {
            case "input_opening_category_balance" -> plainMoney(CEILING_OPENING_BALANCE);
            case "input_posted_amount" -> plainMoney(amount);
            case "posted_amount_is_the_field_maximum" -> yesNo(amount.compareTo(
                    pictureFieldMaximum(PicClause.TRAN_CAT_BAL_PRECISION,
                            PicClause.TRAN_CAT_BAL_SCALE)) == 0);
            case "unrounded_sum" -> plainMoney(sum);
            case "sum_integer_digits" -> Integer.toString(sum.precision() - sum.scale());
            case "field_integer_digits" -> Integer.toString(fieldIntegerDigits);
            case "sum_exceeds_field" ->
                    yesNo(sum.precision() - sum.scale() > fieldIntegerDigits);
            case "size_error_phrase_occurrences_in_app_cbl" ->
                    Long.toString(sizeErrorPhraseOccurrences());
            case "store_completes_rather_than_failing" -> yesNo(sizeErrorPhraseOccurrences() == 0L);
            case "expected_stored_category_balance" -> plainMoney(stored);
            case "high_order_digit_dropped" -> yesNo(stored.compareTo(sum) != 0);
            case "stored_scale" -> Integer.toString(stored.scale());
            case "negative_sum_keeps_its_sign" -> plainMoney(CobolDecimal.truncateToPictureField(
                    NEGATIVE_CEILING_SUM, PicClause.TRAN_CAT_BAL_PRECISION,
                    PicClause.TRAN_CAT_BAL_SCALE));
            case "account_field_integer_digits" -> Integer.toString(
                    PicClause.ACCT_CURR_BAL_PRECISION - PicClause.ACCT_CURR_BAL_SCALE);
            case "account_store_drops_past_ten_digits" -> yesNo(
                    CobolDecimal.truncateToPictureField(ACCOUNT_CEILING_SUM,
                                    PicClause.ACCT_CURR_BAL_PRECISION,
                                    PicClause.ACCT_CURR_BAL_SCALE)
                            .compareTo(ACCOUNT_CEILING_SUM) != 0);
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved ceiling field " + field);
        };
    }

    /**
     * Returns the largest value one Picture clause holds.
     *
     * @param precision the digits the field holds in total
     * @param scale     the fractional digits the field holds
     * @return ten raised to the integer digits, less one unit of the last fractional digit
     */
    private static BigDecimal pictureFieldMaximum(int precision, int scale) {
        return BigDecimal.TEN.pow(precision - scale)
                .subtract(BigDecimal.ONE.movePointLeft(scale))
                .setScale(scale, RoundingMode.DOWN);
    }

    /** Resolves one of the five rate-resolution cases or one of the four credit-score cases. */
    private static String rateResolutionCaseValue(ExpectedOutcomes.Row row) {
        if (row.recordSequence().startsWith(CREDIT_SCORE_SEQUENCE_PREFIX)) {
            return creditScoreValue(row.recordSequence(), row.expectedField());
        }
        SyntheticRateCase probe = syntheticCases().get(row.recordSequence());
        if (probe == null) {
            throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unknown case " + row.recordSequence());
        }
        BigDecimal matched = disclosureRate(probe.groupId(), probe.typeCode(),
                probe.categoryCode());
        BigDecimal substituted = disclosureRate(DEFAULT_DISCLOSURE_GROUP, probe.typeCode(),
                probe.categoryCode());
        boolean fallback = matched == null;
        BigDecimal resolved = fallback ? substituted : matched;
        return switch (row.expectedField()) {
            case "input_acct_group_id" -> probe.groupId();
            case "input_tran_type_cd" -> probe.typeCode();
            case "input_tran_cat_cd" -> probe.categoryCode();
            case "first_read_status" -> fallback ? MISSING_RECORD_STATUS : NORMAL_FILE_STATUS;
            case "fallback_fires" -> yesNo(fallback);
            case "substituted_group_id" -> DEFAULT_DISCLOSURE_GROUP;
            case "retry_performed" -> yesNo(fallback);
            case "retry_read_status" ->
                    substituted == null ? MISSING_RECORD_STATUS : NORMAL_FILE_STATUS;
            case "retry_accepts_status_23" -> yesNo(RETRY_ACCEPTED_STATUSES
                    .contains(MISSING_RECORD_STATUS));
            case "resolved_rate" -> requireRate(resolved, probe).setScale(
                    PicClause.DIS_INT_RATE_SCALE, RoundingMode.DOWN).toPlainString();
            case "rate_if_group_had_matched" -> requireRate(disclosureRate(
                    MATCHED_DISCLOSURE_GROUP, probe.typeCode(), probe.categoryCode()), probe)
                    .setScale(PicClause.DIS_INT_RATE_SCALE, RoundingMode.DOWN).toPlainString();
            case "rate_diverges_from_default_block" -> yesNo(matched != null && substituted != null
                    && matched.compareTo(substituted) != 0);
            case "fallback_changes_resolved_value" -> yesNo(fallback && disclosureRate(
                    MATCHED_DISCLOSURE_GROUP, probe.typeCode(), probe.categoryCode()) != null
                    && requireRate(resolved, probe).compareTo(disclosureRate(
                            MATCHED_DISCLOSURE_GROUP, probe.typeCode(),
                            probe.categoryCode())) != 0);
            case "compute_gate_opens" ->
                    yesNo(resolved != null && resolved.compareTo(BigDecimal.ZERO) != 0);
            case "interest_transaction_written" ->
                    yesNo(resolved != null && resolved.compareTo(BigDecimal.ZERO) != 0);
            case "abend_message" -> defaultGroupFailureMessage();
            case "abends" -> yesNo(substituted == null);
            case "retry_count_before_abend" -> Integer.toString(SINGLE_RETRY);
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved rate field "
                            + row.expectedField());
        };
    }

    /** Resolves one of the four credit-score boundary cases. */
    private static String creditScoreValue(String sequence, String field) {
        int score = Integer.parseInt(sequence.substring(sequence.lastIndexOf('-') + 1));
        boolean valid = score >= CREDIT_SCORE_LOW_BOUND && score <= CREDIT_SCORE_HIGH_BOUND;
        return switch (field) {
            case "input_fico_score" -> Integer.toString(score);
            case "expected_valid" -> yesNo(valid);
            case "bound_tested" -> boundName(score);
            case "fixture_occurrences" -> Long.toString(CUSTOMERS.stream()
                    .filter(customer -> customer.ficoCreditScore() == score).count());
            case "expected_message" -> creditScoreMessage();
            case "input_error_flag_set" -> yesNo(!valid);
            case "message_gated_on_return_msg_off" -> yesNo(CobolSourceEvidence
                    .containsStatement(ACCOUNT_UPDATE_PROGRAM, "IF WS-RETURN-MSG-OFF"));
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved score field " + field);
        };
    }

    /** Resolves one {@code COVERAGE} measurement of what the fixtures cannot reach. */
    private static String unreachabilityValue(String field) {
        return switch (field) {
            case "reason_100_fixture_occurrences" ->
                    Long.toString(fixtureDeclines(DeclineReason.INVALID_CARD_NUMBER));
            case "reason_101_fixture_occurrences" ->
                    Long.toString(fixtureDeclines(DeclineReason.ACCOUNT_NOT_FOUND));
            case "reason_102_fixture_occurrences_are_nonzero" ->
                    yesNo(fixtureDeclines(DeclineReason.OVER_CREDIT_LIMIT) > 0L);
            case "reason_103_fixture_occurrences" ->
                    Long.toString(fixtureDeclines(DeclineReason.ACCOUNT_EXPIRED));
            case "reason_109_fixture_occurrences" ->
                    Long.toString(fixtureRecordsReachingTheRewriteFailure());
            case "feed_origin_date_on_every_record" -> onlyFeedOriginDate();
            case "earliest_account_expiry" -> earliestExpiringAccount().expirationDate();
            case "earliest_expiry_account" -> earliestExpiringAccount().accountId();
            case "latest_account_expiry" -> latestExpiringAccount().expirationDate();
            case "latest_expiry_account" -> latestExpiringAccount().accountId();
            case "accounts_with_matched_disclosure_group" -> Long.toString(
                    FIXTURE_ACCOUNTS.values().stream()
                            .filter(account -> disclosureGroupExists(account.groupId()))
                            .count());
            case "accumulators_approaching_nine_integer_digits" -> Long.toString(
                    FIXTURE_ACCOUNTS.values().stream()
                            .filter(AuthorizationDecisionEquivalenceTest::approachesTheNarrowing)
                            .count());
            case "seeded_category_balances_approaching_nine_integer_digits" ->
                    Long.toString(seededCategoryBalancesApproachingTheCeiling());
            case "fico_scores_at_either_bound" -> Long.toString(CUSTOMERS.stream()
                    .filter(customer -> customer.ficoCreditScore() == CREDIT_SCORE_LOW_BOUND
                            || customer.ficoCreditScore() == CREDIT_SCORE_HIGH_BOUND)
                    .count());
            case "fico_scores_above_high_bound" -> Long.toString(CUSTOMERS.stream()
                    .filter(customer -> customer.ficoCreditScore() > CREDIT_SCORE_HIGH_BOUND)
                    .count());
            case "negative_balances_with_nonzero_rate" ->
                    Long.toString(negativeSeededBalancesAtANonZeroRate());
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved coverage field " + field);
        };
    }

    /** Resolves one {@code SETUP} expectation of the constructed rows the cases need. */
    private static String syntheticSetupValue(String field) {
        return switch (field) {
            case "synthetic_xref_card_number" -> SYNTHETIC_XREF_CARD_NUMBER;
            case "synthetic_xref_customer_id" -> SYNTHETIC_XREF_CUSTOMER_ID;
            case "synthetic_xref_account_id" -> SYNTHETIC_XREF_ACCOUNT_ID;
            case "synthetic_xref_row_required_for_case" -> ACCOUNT_MISS_CASE;
            case "card_absent_from_xref_for_case_100" ->
                    assertAbsentFromCrossReference(ABSENT_CARD_NUMBER);
            case "account_absent_from_acctdata" ->
                    assertAbsentFromAccounts(SYNTHETIC_XREF_ACCOUNT_ID);
            case "fixtures_must_not_be_modified" -> yesNo(true);
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved setup field " + field);
        };
    }

    /** Resolves one {@code SUMMARY} count over the constructed cases. */
    private static String syntheticSummaryValue(String field) {
        return switch (field) {
            case "total_cases" -> Long.toString(syntheticCaseSequences().size());
            case "authorization_decline_cases" ->
                    Long.toString(casesMatching(AUTHORIZATION_CASE_PREFIXES));
            case "narrowing_cases" -> Long.toString(casesMatching(NARROWING_CASE_PREFIXES));
            case "reason_109_cases" -> Long.toString(casesMatching(List.of(REWRITE_CASE)));
            case "truncation_cases" -> Long.toString(casesMatching(List.of(TRUNCATION_CASE)));
            case "category_ceiling_cases" ->
                    Long.toString(casesMatching(List.of(CATEGORY_CEILING_CASE)));
            case "rate_resolution_cases" ->
                    Long.toString(casesMatching(List.of(RATE_CASE_PREFIX)));
            case "fico_boundary_cases" ->
                    Long.toString(casesMatching(List.of(CREDIT_SCORE_SEQUENCE_PREFIX)));
            case "consuming_test_classes" -> Integer.toString(SINGLE_CONSUMING_CLASS);
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved summary field " + field);
        };
    }

    /** Resolves one {@code PROHIBITION} expectation of the synthetic-case discipline. */
    private static String syntheticProhibitionValue(String field) {
        return switch (field) {
            case "no_fixture_file_is_modified", "no_additional_cases_may_be_invented",
                    "no_case_may_assert_a_reachable_outcome_as_synthetic" -> yesNo(true);
            case "narrowing_defect_is_not_corrected" -> yesNo(
                    PicClause.WS_TEMP_BAL_PRECISION < PicClause.ACCT_CURR_CYC_CREDIT_PRECISION);
            case "collision_must_not_short_circuit_on_102" -> yesNo(!theExpiryRuleIsGated());
            case "expiration_comparison_stays_alphanumeric" ->
                    yesNo(theExpiryComparisonIsTextual());
            case "no_strict_inequality_on_either_comparison" -> yesNo(GREATER_OR_EQUAL
                    .equals(comparisonOperatorIn(paragraphLines(ACCOUNT_LOOKUP_PARAGRAPH))));
            default -> throw new IllegalStateException(
                    EXPECTED_SYNTHETIC_FILE + " names unresolved prohibition field " + field);
        };
    }

    /**
     * One constructed rate-resolution probe.
     *
     * @param groupId      the {@code ACCT-GROUP-ID} the case supplies, at its declared width
     * @param typeCode     the {@code TRANCAT-TYPE-CD} the case supplies
     * @param categoryCode the {@code TRANCAT-CD} the case supplies
     */
    private record SyntheticRateCase(String groupId, String typeCode, String categoryCode) { }

    /**
     * Declares the five constructed rate-resolution probes, keyed by their sequence label.
     *
     * <p>Each probe pairs a group identifier with a reference code that no combination of the nine
     * fixture files reaches. Four of the five would need an account whose group identifier is not
     * blank, and the fixture leaves that field blank on every one of its fifty rows.</p>
     *
     * @return the probes, keyed by {@code record_seq}
     */
    private static Map<String, SyntheticRateCase> syntheticCases() {
        Map<String, SyntheticRateCase> cases = new LinkedHashMap<>();
        cases.put("SYN-RATE-MATCHED-01",
                new SyntheticRateCase(MATCHED_DISCLOSURE_GROUP, "01", "0001"));
        cases.put("SYN-RATE-MATCHED-07",
                new SyntheticRateCase(MATCHED_DISCLOSURE_GROUP, "07", "0001"));
        cases.put("SYN-RATE-FALLBACK-07",
                new SyntheticRateCase(BLANK_DISCLOSURE_GROUP, "07", "0001"));
        cases.put("SYN-RATE-MATCHED-ZEROAPR",
                new SyntheticRateCase(ZERO_RATE_DISCLOSURE_GROUP, "01", "0001"));
        cases.put("SYN-RATE-DOUBLE-MISS-ABEND",
                new SyntheticRateCase(BLANK_DISCLOSURE_GROUP, "99", "9999"));
        return Map.copyOf(cases);
    }

    /** Every case sequence the file carries, which is every sequence that is not a section. */
    private static Set<String> syntheticCaseSequences() {
        Set<String> sequences = new LinkedHashSet<>();
        for (ExpectedOutcomes.Row row : EXPECTED_SYNTHETIC.rows()) {
            if (!SYNTHETIC_SECTIONS.contains(row.recordSequence())) {
                sequences.add(row.recordSequence());
            }
        }
        return Set.copyOf(sequences);
    }

    /** Counts the case sequences whose label starts with any of the given prefixes. */
    private static long casesMatching(List<String> prefixes) {
        return syntheticCaseSequences().stream()
                .filter(sequence -> prefixes.stream().anyMatch(sequence::startsWith))
                .count();
    }

    /** Answers one disclosure-group rate, or {@code null} when the fixture carries no such row. */
    private static BigDecimal disclosureRate(String groupId, String typeCode,
            String categoryCode) {
        for (CopybookRecordParser.DisclosureGroupRecord row : CardDemoFixtureLoader.loadDisclosureGroups()) {
            if (row.accountGroupId().equals(groupId)
                    && row.transactionTypeCode().equals(typeCode)
                    && row.transactionCategoryCode().equals(categoryCode)) {
                return row.interestRate();
            }
        }
        return null;
    }

    /** Reports whether the disclosure-group fixture carries any row for one group identifier. */
    private static boolean disclosureGroupExists(String groupId) {
        return CardDemoFixtureLoader.loadDisclosureGroups().stream()
                .anyMatch(row -> row.accountGroupId().equals(groupId));
    }

    /** Refuses a null rate rather than letting a probe silently resolve to nothing. */
    private static BigDecimal requireRate(BigDecimal rate, SyntheticRateCase probe) {
        if (rate == null) {
            throw new IllegalStateException("no rate resolves for " + probe);
        }
        return rate;
    }

    /** Answers the message the interest program displays before abending on a second miss. */
    private static String defaultGroupFailureMessage() {
        Pattern display = Pattern.compile("DISPLAY '([^']*)'");
        for (String line : CobolSourceEvidence.paragraph(INTEREST_PROGRAM,
                DEFAULT_RATE_PARAGRAPH)) {
            Matcher matcher = display.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException(
                INTEREST_PROGRAM + " displays nothing before abending on a second miss");
    }

    /** Names which bound one constructed credit score probes. */
    private static String boundName(int score) {
        if (score == CREDIT_SCORE_LOW_BOUND - 1) {
            return "low_bound_minus_one";
        }
        if (score == CREDIT_SCORE_LOW_BOUND) {
            return "low_bound_exact";
        }
        if (score == CREDIT_SCORE_HIGH_BOUND) {
            return "high_bound_exact";
        }
        if (score == CREDIT_SCORE_HIGH_BOUND + 1) {
            return "high_bound_plus_one";
        }
        throw new IllegalStateException(score + " probes neither bound");
    }

    /** Builds the credit-score message the way the source strings it together. */
    private static String creditScoreMessage() {
        Matcher bounds = Pattern.compile("': should be between (\\d+) and (\\d+)'")
                .matcher(CobolSourceEvidence.file(ACCOUNT_UPDATE_PROGRAM));
        if (!bounds.find()) {
            throw new IllegalStateException(
                    ACCOUNT_UPDATE_PROGRAM + " strings no credit-score range message");
        }
        return CREDIT_SCORE_FIELD_LABEL + ": should be between " + bounds.group(1) + " and "
                + bounds.group(2);
    }

    /** Confirms one card number is absent from the cross-reference fixture, then answers it. */
    private static String assertAbsentFromCrossReference(String cardNumber) {
        if (FIXTURE_CROSS_REFERENCES.containsKey(cardNumber)) {
            throw new IllegalStateException("app/data/ASCII/cardxref.txt already carries card "
                    + cardNumber + ", so the case is not synthetic");
        }
        return cardNumber;
    }

    /** Confirms one account identifier is absent from the account fixture, then answers it. */
    private static String assertAbsentFromAccounts(String accountId) {
        if (FIXTURE_ACCOUNTS.containsKey(accountId)) {
            throw new IllegalStateException("app/data/ASCII/acctdata.txt already carries account "
                    + accountId + ", so the case is not synthetic");
        }
        return accountId;
    }

    /** Answers the fixture account whose expiry date sorts earliest. */
    private static AccountRecord earliestExpiringAccount() {
        return FIXTURE_ACCOUNTS.values().stream()
                .min(Comparator.comparing(AccountRecord::expirationDate))
                .orElseThrow(() -> new IllegalStateException("the fixture carries no account"));
    }

    /** Answers the fixture account whose expiry date sorts latest. */
    private static AccountRecord latestExpiringAccount() {
        return FIXTURE_ACCOUNTS.values().stream()
                .max(Comparator.comparing(AccountRecord::expirationDate))
                .orElseThrow(() -> new IllegalStateException("the fixture carries no account"));
    }

    /** Answers the fixture account carrying the smallest credit limit. */
    private static AccountRecord smallestLimitAccount() {
        return FIXTURE_ACCOUNTS.values().stream()
                .min(Comparator.comparing(AccountRecord::creditLimit)
                        .thenComparing(AccountRecord::accountId))
                .orElseThrow(() -> new IllegalStateException("the fixture carries no account"));
    }

    /** Advances a ten-character date by one day, keeping the text shape the source compares. */
    private static String dayAfter(String date) {
        return LocalDate.parse(date).plusDays(1L).toString();
    }

    /** Renders one date as the midnight capture moment the constructed cases supply. */
    private static String midnightTimestamp(String date) {
        return date + MIDNIGHT_SUFFIX;
    }

    /** Answers the outcome the credit-limit comparison reaches for one limit and balance. */
    private static String outcomeOf(BigDecimal creditLimit, BigDecimal workingBalance) {
        return creditLimit.compareTo(workingBalance) < 0 ? DECLINED_OUTCOME : APPROVED_OUTCOME;
    }

    /** Reports whether the expiry comparison really compares text rather than a date type. */
    private static boolean theExpiryComparisonIsTextual() {
        return CobolSourceEvidence.paragraph(VALIDATION_PROGRAM, ACCOUNT_LOOKUP_PARAGRAPH).stream()
                .anyMatch(line -> line.contains(EXPIRY_TEXT_SLICE));
    }

    /**
     * Reports whether the expiry rule is gated on the reason code still being zero.
     *
     * <p>It is not: the credit-limit rule and the expiry rule sit in the same paragraph with no
     * test of the reason field between them, so both can assign and the later one wins.</p>
     *
     * @return {@code true} only if a gate is found, which for this source is never
     */
    private static boolean theExpiryRuleIsGated() {
        List<String> lines = paragraphLines(ACCOUNT_LOOKUP_PARAGRAPH);
        int creditLimit = indexOfLineContaining(lines, OVER_LIMIT_TEXT);
        int expiry = indexOfLineContaining(lines, EXPIRY_TEXT_SLICE);
        for (int index = creditLimit; index < expiry && index < lines.size(); index++) {
            if (lines.get(index).contains(REASON_GATE)) {
                return true;
            }
        }
        return false;
    }

    /** Pads a reason literal the way the wire contract writes it. */
    private static String paddedReasonCode(String literal) {
        return "0".repeat(Math.max(0, REASON_CODE_WIDTH - literal.length())) + literal;
    }

    /** Reports whether the reason assigned on a rewrite failure is inspected anywhere. */
    private static boolean theUnreachableReasonIsInspected() {
        String assigned = lastReasonAssignedIn(paragraphLines(ACCOUNT_UPDATE_PARAGRAPH));
        long assignments = CobolSourceEvidence.occurrences(VALIDATION_PROGRAM,
                "MOVE " + assigned + " TO WS-VALIDATION-FAIL-REASON");
        long mentions = CobolSourceEvidence.occurrences(VALIDATION_PROGRAM, assigned);
        return mentions > assignments;
    }

    /** Reports whether one posting step precedes another in the posting paragraph. */
    private static boolean postingStepPrecedes(String earlier, String later) {
        List<String> lines = paragraphLines(POSTING_PARAGRAPH);
        return indexOfLineContaining(lines, earlier) < indexOfLineContaining(lines, later);
    }

    /** Answers the narrative this platform records for the unreachable reason. */
    private static String rewriteFailureNarrative(String field) {
        return switch (field) {
            case "assigned_reason_text" -> DeclineReason.ACCOUNT_NOT_FOUND.description();
            case "source_outcome" -> "no observable effect";
            case "target_outcome" -> "consumer fails, offset not committed, message reaches "
                    + "dead-letter topic";
            default -> throw new IllegalStateException("no narrative for " + field);
        };
    }

    /** Answers the provenance this platform records for either truncation operand. */
    private static String truncationProvenance(String field) {
        return switch (field) {
            case "balance_operand_provenance" -> "account " + NEGATIVE_BALANCE_ACCOUNT
                    + " closing type 03 category 0001 balance under Model B";
            case "rate_operand_provenance" ->
                    "DEFAULT group type 01 categories 0002 0003 0004";
            default -> throw new IllegalStateException("no provenance for " + field);
        };
    }

    /** Writes the separation marker the way the synthetic file writes one. */
    private static String separationMarker(BigDecimal balance, BigDecimal rate,
            RoundingMode mode) {
        BigDecimal product = balance.multiply(rate);
        boolean separated = product.divide(CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.DOWN)
                .compareTo(product.divide(CobolDecimal.INTEREST_DIVISOR,
                        PicClause.WS_MONTHLY_INT_SCALE, mode)) != 0;
        return separated ? UPPER_CASE_SEPARATION : ExpectedOutcomes.NO;
    }

    /** Counts how often the rounding phrase appears anywhere below {@code app/cbl/}. */
    private static long roundedPhraseOccurrences() {
        Path programs = CardDemoFixtureLoader.fixtureDirectory().getParent().getParent()
                .getParent().resolve(COBOL_PROGRAM_DIRECTORY);
        try (Stream<Path> files = Files.list(programs)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(AuthorizationDecisionEquivalenceTest::roundedPhrasesIn)
                    .sum();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + programs, unreadable);
        }
    }

    /** Counts how often the size-error phrase appears anywhere below {@code app/cbl/}. */
    private static long sizeErrorPhraseOccurrences() {
        Path programs = CardDemoFixtureLoader.fixtureDirectory().getParent().getParent()
                .getParent().resolve(COBOL_PROGRAM_DIRECTORY);
        try (Stream<Path> files = Files.list(programs)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(AuthorizationDecisionEquivalenceTest::sizeErrorPhrasesIn)
                    .sum();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + programs, unreadable);
        }
    }

    /** Counts the size-error phrases one program carries outside its comment lines. */
    private static long sizeErrorPhrasesIn(Path program) {
        try {
            return Files.readAllLines(program, StandardCharsets.UTF_8).stream()
                    .map(line -> line.replaceAll("\\s+", " ").strip())
                    .filter(line -> !line.startsWith("*"))
                    .filter(line -> line.contains(SIZE_ERROR_PHRASE))
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + program, unreadable);
        }
    }

    /** Counts the rounding phrases one program carries outside its comment lines. */
    private static long roundedPhrasesIn(Path program) {
        try {
            return Files.readAllLines(program, StandardCharsets.UTF_8).stream()
                    .map(line -> line.replaceAll("\\s+", " ").strip())
                    .filter(line -> !line.startsWith("*"))
                    .filter(line -> line.contains(ROUNDED_PHRASE))
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + program, unreadable);
        }
    }

    /** Counts the fixture records the rule chain declines with one reason. */
    private static long fixtureDeclines(DeclineReason reason) {
        long declines = 0L;
        Map<String, BigDecimal> cycleCredit = new LinkedHashMap<>();
        Map<String, BigDecimal> cycleDebit = new LinkedHashMap<>();
        for (AccountRecord account : FIXTURE_ACCOUNTS.values()) {
            cycleCredit.put(account.accountId(), account.currentCycleCredit());
            cycleDebit.put(account.accountId(), account.currentCycleDebit());
        }
        for (DailyTransactionRecord transaction : FEED) {
            CardCrossReferenceRecord crossReference =
                    FIXTURE_CROSS_REFERENCES.get(transaction.cardNumber());
            if (crossReference == null) {
                declines += reason == DeclineReason.INVALID_CARD_NUMBER ? 1L : 0L;
                continue;
            }
            AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());
            if (account == null) {
                declines += reason == DeclineReason.ACCOUNT_NOT_FOUND ? 1L : 0L;
                continue;
            }
            BigDecimal working = workingBalance(cycleCredit.get(account.accountId()),
                    cycleDebit.get(account.accountId()), transaction.amount());
            boolean overLimit = account.creditLimit().compareTo(working) < 0;
            boolean expired = account.expirationDate().compareTo(
                    CopybookRecordParser.timestampDatePart(transaction.originTimestamp())) < 0;
            if (expired) {
                declines += reason == DeclineReason.ACCOUNT_EXPIRED ? 1L : 0L;
                continue;
            }
            if (overLimit) {
                declines += reason == DeclineReason.OVER_CREDIT_LIMIT ? 1L : 0L;
                continue;
            }
            cycleCredit.put(account.accountId(), transaction.amount().signum() >= 0
                    ? CobolDecimal.add(cycleCredit.get(account.accountId()), transaction.amount(),
                            PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
                    : cycleCredit.get(account.accountId()));
            cycleDebit.put(account.accountId(), transaction.amount().signum() < 0
                    ? CobolDecimal.add(cycleDebit.get(account.accountId()), transaction.amount(),
                            PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
                    : cycleDebit.get(account.accountId()));
        }
        return declines;
    }

    /**
     * Counts the fixture records that reach the account-rewrite failure branch.
     *
     * <p>The branch is reached only when the account read succeeded during validation and the
     * rewrite of that same record then reported a missed key. Both operations key on the same
     * identifier against the same dataset, so a record that resolved once resolves again and the
     * branch stays unreached on every fixture record. Counting rather than asserting zero is what
     * makes a future fixture that did reach it visible.</p>
     *
     * @return how many feed records reach the branch
     */
    private static long fixtureRecordsReachingTheRewriteFailure() {
        long reached = 0L;
        for (DailyTransactionRecord transaction : FEED) {
            CardCrossReferenceRecord crossReference =
                    FIXTURE_CROSS_REFERENCES.get(transaction.cardNumber());
            if (crossReference == null) {
                continue;
            }
            String accountId = crossReference.accountId();
            boolean resolvedAtLookup = FIXTURE_ACCOUNTS.containsKey(accountId);
            boolean resolvedAtRewrite = FIXTURE_ACCOUNTS.containsKey(accountId);
            if (resolvedAtLookup && !resolvedAtRewrite) {
                reached++;
            }
        }
        return reached;
    }

    /** Answers the single origin date every feed record carries, refusing any disagreement. */
    private static String onlyFeedOriginDate() {
        Set<String> dates = new LinkedHashSet<>();
        for (DailyTransactionRecord transaction : FEED) {
            dates.add(CopybookRecordParser.timestampDatePart(transaction.originTimestamp()));
        }
        if (dates.size() != 1) {
            throw new IllegalStateException("the feed carries " + dates.size()
                    + " distinct origin dates, so no single date names them");
        }
        return dates.iterator().next();
    }

    /**
     * Counts the seeded category balances already within one integer digit of their field's ceiling.
     *
     * <p>The measurement is what makes the ceiling case synthetic: a value the fixture reaches would
     * belong in a fixture-derived ledger instead.
     *
     * @return how many rows of {@code app/data/ASCII/tcatbal.txt} hold eight integer digits or more
     */
    private static long seededCategoryBalancesApproachingTheCeiling() {
        BigDecimal threshold = BigDecimal.TEN.pow(
                PicClause.TRAN_CAT_BAL_PRECISION - PicClause.TRAN_CAT_BAL_SCALE - 1);
        return CardDemoFixtureLoader.loadTransactionCategoryBalances().stream()
                .filter(seed -> seed.balance().abs().compareTo(threshold) >= 0)
                .count();
    }

    /** Reports whether one account's accumulators come within one digit of the narrowing. */
    private static boolean approachesTheNarrowing(AccountRecord account) {
        BigDecimal threshold = BigDecimal.TEN.pow(
                PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE - 1);
        return account.currentCycleCredit().abs().compareTo(threshold) >= 0
                || account.currentCycleDebit().abs().compareTo(threshold) >= 0;
    }

    /** Counts seeded category balances that are negative while their resolved rate is not zero. */
    private static long negativeSeededBalancesAtANonZeroRate() {
        long counted = 0L;
        for (CopybookRecordParser.TransactionCategoryBalanceRecord seed
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            BigDecimal rate = disclosureRate(DEFAULT_DISCLOSURE_GROUP, seed.typeCode(),
                    seed.categoryCode());
            if (seed.balance().signum() < 0 && rate != null
                    && rate.compareTo(BigDecimal.ZERO) != 0) {
                counted++;
            }
        }
        return counted;
    }
}

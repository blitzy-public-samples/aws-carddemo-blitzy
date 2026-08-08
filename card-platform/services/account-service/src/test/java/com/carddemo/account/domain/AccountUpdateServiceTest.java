package com.carddemo.account.domain;

import com.carddemo.account.domain.validation.DateOfBirthValidator;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.CustomerContextChanged;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.cobol.CobolDateValidator;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Asserts {@link AccountUpdateService#updateAccount} against the paragraphs it carries.
 *
 * <p>Three paragraphs of {@code app/cbl/COACTUPC.cbl} reach this surface.
 * {@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COACTUPC.cbl:L1429-L1679} runs the search-key
 * edit, twenty-four field edits and one cross-field edit. {@code 1205-COMPARE-OLD-NEW} at
 * {@code app/cbl/COACTUPC.cbl:L1681-L1779} answers whether the caller changed a value.
 * {@code 9600-WRITE-PROCESSING} at {@code app/cbl/COACTUPC.cbl:L3888-L4104} locks both records,
 * applies the field moves and rewrites the account at {@code app/cbl/COACTUPC.cbl:L4066} and the
 * customer at {@code app/cbl/COACTUPC.cbl:L4086}.
 *
 * <p>Every test drives the public method. {@code 1205-COMPARE-OLD-NEW} stays private, and each
 * assertion over it reads the verdict the public method returns.
 *
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4109-L4195} answers a second
 * question: whether the stored record moved under the caller. {@link ConcurrentChangeDetector}
 * carries that paragraph and {@code ConcurrentChangeDetectorTest} asserts it field by field. The
 * tests here read its verdict once, at the point {@code app/cbl/COACTUPC.cbl:L3947-L3948} performs
 * it.
 *
 * <p>Each validator owns its own test under {@code domain/validation}, and those tests hold the
 * message table. The assertions here read which edit ran and in which order.
 *
 * <p>One pass carries one message. {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479} holds text only while
 * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480} reads true, so a
 * pass keeps the first message it produces. {@link EditResult} carries one verdict and one message
 * slot, and holds no violation list and no severity.
 *
 * <p>Four measured findings from {@code app/cbl/COACTUPC.cbl} reach
 * {@code card-platform/docs/business-rule-flags.md}. One condition name is declared twice over the
 * same host at {@code app/cbl/COACTUPC.cbl:L497} and {@code app/cbl/COACTUPC.cbl:L513}, each with
 * its own literal. Two neighbouring condition names at {@code app/cbl/COACTUPC.cbl:L494} and
 * {@code app/cbl/COACTUPC.cbl:L496} carry one identical literal. Two {@code STRING} statements
 * close with {@code END-IF} at {@code app/cbl/COACTUPC.cbl:L1811} and
 * {@code app/cbl/COACTUPC.cbl:L2212}, where the other twenty-seven close with {@code END-STRING}.
 * Five members under {@code app/} carry carriage-return line endings: {@code CBSTM03A.CBL},
 * {@code CBSTM03B.CBL}, {@code COACTUPC.cbl}, {@code COSTM01.CPY} and {@code CREASTMT.JCL}.
 *
 * <p>Choices behind the target shape sit in {@code card-platform/docs/decision-log.md}. The
 * paragraph-to-class mapping sits in {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>The container arrives from {@link AbstractAccountPostgresTest}, and this class declares none.
 * Each test method runs in a transaction the framework rolls back, so the seeded row counts other
 * test classes read stay correct.
 */
@DisplayName("AccountUpdateService.updateAccount over COACTUPC L1429 to L4104")
class AccountUpdateServiceTest extends AbstractAccountPostgresTest {

    /**
     * Supplies one unused key triple per test. {@code V2__seed.sql} holds accounts
     * {@code 00000000001} through {@code 00000000050} and customers {@code 000000001} through
     * {@code 000000050}, and every key built here sits above that range.
     */
    private static final AtomicInteger KEY_SEQUENCE = new AtomicInteger();

    /** Renders the ten characters a date column holds, matching {@code CUST-DOB} formatting. */
    private static final DateTimeFormatter TEN_CHARACTER_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Renders the eight characters a date edit reads.
     *
     * <p>{@code AccountUpdateService.editDateField} strips the two separators of the ten-character
     * column before either date edit sees the value, so a validator called directly takes the form
     * {@code WS-EDIT-DATE-CCYYMMDD} at {@code app/cpy/CSUTLDWY.cpy} holds.
     */
    private static final DateTimeFormatter EIGHT_CHARACTER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Text {@code app/cbl/COACTUPC.cbl:L2146} appends when the class test refuses a field. */
    private static final String MUST_BE_ALL_NUMERIC = " must be all numeric.";

    /** Text {@code app/cbl/COACTUPC.cbl:L2163} appends when the converted value is zero. */
    private static final String MUST_NOT_BE_ZERO = " must not be zero.";

    /** Text the yes-or-no edit at {@code app/cbl/COACTUPC.cbl:L1886} appends. */
    private static final String MUST_BE_YES_OR_NO = " must be Y or N.";

    /** Text the alphabetic edit at {@code app/cbl/COACTUPC.cbl:L1941} appends. */
    private static final String ALPHABETS_ONLY = " can have alphabets only.";

    /** Text {@code app/cbl/COACTUPC.cbl:L2523} appends when the score falls outside the range. */
    private static final String FICO_RANGE = ": should be between 300 and 850";

    /** Text {@code app/cbl/COACTUPC.cbl:L2503} appends when the state code is unlisted. */
    private static final String NOT_A_VALID_STATE_CODE = ": is not a valid state code";

    /** Literal {@code app/cbl/COACTUPC.cbl:L2550} moves for an unlisted state and postcode pair. */
    private static final String INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /** Text the signed-decimal edit at {@code app/cbl/COACTUPC.cbl:L2191} appends. */
    private static final String MUST_BE_SUPPLIED = " must be supplied.";

    /**
     * A credit limit above the one {@link #account(Keys)} stores, so a submitted pair carrying it
     * differs from the fetched pair and reaches {@code 9600-WRITE-PROCESSING}.
     */
    private static final BigDecimal RAISED_CREDIT_LIMIT = new BigDecimal("20300.00");

    @Autowired
    private AccountUpdateService service;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private EntityManager entityManager;

    /**
     * The mapper {@code config/KafkaProducerConfig} declares, and the one
     * {@code outbox/OutboxWriter} serialized the stored payload with.
     */
    @Autowired
    @Qualifier("accountEventObjectMapper")
    private ObjectMapper eventMapper;

    // =============================================================================================
    // The container paragraph, 1200-EDIT-MAP-INPUTS at app/cbl/COACTUPC.cbl:L1429-L1679.
    // =============================================================================================

    /**
     * The gate at {@code app/cbl/COACTUPC.cbl:L1463-L1468}.
     *
     * <p>Three conditions open it: {@code NO-CHANGES-FOUND} at
     * {@code app/cbl/COACTUPC.cbl:L1463}, {@code ACUP-CHANGES-OK-NOT-CONFIRMED} at
     * {@code app/cbl/COACTUPC.cbl:L1464} and {@code ACUP-CHANGES-OKAYED-AND-DONE} at
     * {@code app/cbl/COACTUPC.cbl:L1465}. The second and the third name screen states the source
     * carries between two terminal interactions. A stateless request carries neither, so the gate
     * reduces to its first condition, and the tests below reach it through the two request shapes
     * the other two conditions describe.
     *
     * <p>When the gate opens, {@code app/cbl/COACTUPC.cbl:L1466} clears the non-key flags and
     * {@code app/cbl/COACTUPC.cbl:L1467} leaves the paragraph, so none of the twenty-four field
     * edits runs.
     */
    @Nested
    @DisplayName("the three-way gate at L1463 to L1465")
    class TheThreeWayGate {

        @Test
        @Transactional
        @DisplayName("A pair matching the fetched copy answers the L491 text and writes nothing")
        void anUnchangedPairAnswersTheNoChangeText() {
            Keys keys = persistedPair();

            EditResult verdict = service.updateAccount(account(keys), customer(keys),
                    account(keys), customer(keys));

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED)
                    .isEqualTo("No change detected with respect to values fetched.");
            assertThat(accountStateChangedRows(keys)).isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("L1466 clears the flags and L1467 leaves, so no field edit reads a refused "
                + "value")
        void noFieldEditRunsWhileTheGateHolds() {
            Keys keys = keys();
            AccountEntity stored = account(keys);
            stored.setActiveStatus("X");
            persist(stored, customer(keys), keys);

            AccountEntity submitted = account(keys);
            submitted.setActiveStatus("X");
            EditResult verdict =
                    service.updateAccount(submitted, customer(keys), account(keys, "X"),
                            customer(keys));

            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
            assertThat(verdict.message()).doesNotContain(MUST_BE_YES_OR_NO);
        }

        @Test
        @Transactional
        @DisplayName("A pair resubmitted after its change was applied answers the L491 text again")
        void aResubmissionAfterTheChangeWasAppliedAnswersTheNoChangeText() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            EditResult applied = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));
            EditResult resubmitted = service.updateAccount(account(keys, "Y", "20300.00"),
                    customer(keys), account(keys, "Y", "20300.00"), customer(keys));

            assertThat(applied.valid()).isTrue();
            assertThat(applied.hasMessage()).isFalse();
            assertThat(resubmitted.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        }
    }

    /**
     * The branch at {@code app/cbl/COACTUPC.cbl:L1433-L1449}.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1433} tests {@code ACUP-DETAILS-NOT-FETCHED},
     * {@code app/cbl/COACTUPC.cbl:L1435-L1436} performs the account-identifier edit,
     * {@code app/cbl/COACTUPC.cbl:L1438} clears the fetched account and
     * {@code app/cbl/COACTUPC.cbl:L1446} leaves the paragraph. A blank filter reaches
     * {@code app/cbl/COACTUPC.cbl:L1441-L1442}, which sets the condition name declared at
     * {@code app/cbl/COACTUPC.cbl:L489-L490}.
     */
    @Nested
    @DisplayName("the not-yet-fetched branch at L1433 to L1449")
    class TheNotYetFetchedBranch {

        @Test
        @Transactional
        @DisplayName("The L1435 to L1436 edit reads the account identifier and no other field")
        void anAbsentFetchedPairEditsTheIdentifierAlone() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setFicoCreditScore(BigDecimal.ZERO);
            submitted.setAddressStateCode("1A");

            EditResult verdict = service.updateAccount(account(keys), submitted, null, null);

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
            assertThat(accountStateChangedRows(keys)).isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("A blank account identifier answers the L489 to L490 text")
        void aBlankIdentifierAnswersTheNoInputText() {
            EditResult verdict = service.updateAccount(new AccountEntity(), new CustomerEntity(),
                    null, null);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.NO_INPUT_RECEIVED)
                    .isEqualTo("No input received");
        }
    }

    /**
     * The four nested gates of the container paragraph.
     *
     * <p>Each gate holds one edit behind the verdict of the edit above it. The date-of-birth
     * reasonableness check sits behind {@code app/cbl/COACTUPC.cbl:L1539} and runs at
     * {@code app/cbl/COACTUPC.cbl:L1540}. The credit-score range sits behind
     * {@code app/cbl/COACTUPC.cbl:L1553} and runs at {@code app/cbl/COACTUPC.cbl:L1554}. The
     * state-code lookup sits behind {@code app/cbl/COACTUPC.cbl:L1599} and runs at
     * {@code app/cbl/COACTUPC.cbl:L1600}. The cross-field lookup sits behind
     * {@code app/cbl/COACTUPC.cbl:L1665-L1666} and runs at {@code app/cbl/COACTUPC.cbl:L1667}.
     *
     * <p>Each test reads the gate in both directions. The first call fails the edit above the gate
     * and the returned message carries nothing the gated edit writes. The second call clears that
     * edit and the returned message carries the text the gated edit writes.
     */
    @Nested
    @DisplayName("the four nested gates at L1539, L1553, L1599 and L1665")
    class TheFourNestedGates {

        @Test
        @Transactional
        @DisplayName("The L1540 reasonableness check runs only once the L1536 edit clears")
        void theDateOfBirthGateOpensOnTheCalendarEdit() {
            Keys keys = persistedPair();
            CustomerEntity impossibleMonth = customer(keys);
            impossibleMonth.setDateOfBirth("2999-13-01");
            CustomerEntity futureDate = customer(keys);
            futureDate.setDateOfBirth(LocalDate.now().plusYears(1).format(TEN_CHARACTER_DATE));

            EditResult held = service.updateAccount(account(keys), impossibleMonth, account(keys),
                    customer(keys));
            EditResult opened = service.updateAccount(account(keys), futureDate, account(keys),
                    customer(keys));

            assertThat(held.valid()).isFalse();
            assertThat(held.message())
                    .startsWith(AccountUpdateService.DATE_OF_BIRTH_LABEL)
                    .doesNotContain(CobolDateValidator.FUTURE_DATE_MESSAGE);
            assertThat(opened.message())
                    .isEqualTo(AccountUpdateService.DATE_OF_BIRTH_LABEL
                            + CobolDateValidator.FUTURE_DATE_MESSAGE);
        }

        @Test
        @Transactional
        @DisplayName("The L1554 range check runs only once the L1549 edit clears")
        void theCreditScoreGateOpensOnTheNumericEdit() {
            Keys keys = persistedPair();
            CustomerEntity zeroScore = customer(keys);
            zeroScore.setFicoCreditScore(BigDecimal.ZERO);
            CustomerEntity scoreBelowRange = customer(keys);
            scoreBelowRange.setFicoCreditScore(new BigDecimal("274"));

            EditResult held = service.updateAccount(account(keys), zeroScore, account(keys),
                    customer(keys));
            EditResult opened = service.updateAccount(account(keys), scoreBelowRange, account(keys),
                    customer(keys));

            assertThat(held.message())
                    .isEqualTo(AccountUpdateService.FICO_SCORE_LABEL + MUST_NOT_BE_ZERO)
                    .doesNotContain(FICO_RANGE);
            assertThat(opened.message())
                    .isEqualTo(AccountUpdateService.FICO_SCORE_LABEL + FICO_RANGE);
        }

        @Test
        @Transactional
        @DisplayName("The L1600 state lookup runs only once the L1595 edit clears")
        void theStateCodeGateOpensOnTheAlphabeticEdit() {
            Keys keys = persistedPair();
            CustomerEntity notAlphabetic = customer(keys);
            notAlphabetic.setAddressStateCode("1A");
            CustomerEntity unlistedState = customer(keys);
            unlistedState.setAddressStateCode("ZZ");

            EditResult held = service.updateAccount(account(keys), notAlphabetic, account(keys),
                    customer(keys));
            EditResult opened = service.updateAccount(account(keys), unlistedState, account(keys),
                    customer(keys));

            assertThat(held.message())
                    .isEqualTo(AccountUpdateService.STATE_LABEL + ALPHABETS_ONLY)
                    .doesNotContain(NOT_A_VALID_STATE_CODE);
            assertThat(opened.message())
                    .isEqualTo(AccountUpdateService.STATE_LABEL + NOT_A_VALID_STATE_CODE);
        }

        @Test
        @Transactional
        @DisplayName("The L1667 cross-field lookup runs only once both field edits clear")
        void theCrossFieldGateOpensOnBothFieldEdits() {
            Keys keys = persistedPair();
            CustomerEntity notNumericPostcode = customer(keys);
            notNumericPostcode.setAddressZip("ABCDE     ");
            CustomerEntity unlistedPair = customer(keys);
            unlistedPair.setAddressZip("12546     ");

            EditResult held = service.updateAccount(account(keys), notNumericPostcode,
                    account(keys), customer(keys));
            EditResult opened = service.updateAccount(account(keys), unlistedPair, account(keys),
                    customer(keys));

            assertThat(held.message())
                    .isEqualTo(AccountUpdateService.ZIP_LABEL + MUST_BE_ALL_NUMERIC)
                    .doesNotContain(INVALID_ZIP_FOR_STATE);
            assertThat(opened.message()).isEqualTo(INVALID_ZIP_FOR_STATE);
        }
    }

    /**
     * Two field-level readings of the container paragraph.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1615} moves the label {@code 'City'} and
     * {@code app/cbl/COACTUPC.cbl:L1616} moves {@code ACUP-NEW-CUST-ADDR-LINE-3} into the edit
     * field, over a width of fifty at {@code app/cbl/COACTUPC.cbl:L1617}. The entity holds that
     * column as {@code addressCity}.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1613} marks address line 2 optional and
     * {@code app/cbl/COACTUPC.cbl:L1614} leaves its label move commented out, so no edit reads
     * that column. Two comparisons still read it: {@code app/cbl/COACTUPC.cbl:L1728-L1731} and
     * {@code app/cbl/COACTUPC.cbl:L4160-L4161}.
     */
    @Nested
    @DisplayName("the two field readings at L1613 to L1621")
    class TheTwoFieldReadings {

        @Test
        @Transactional
        @DisplayName("The L1615 label opens the message the L1616 column produces")
        void theCityLabelOpensTheThirdAddressLineMessage() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setAddressCity("12345");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.CITY_LABEL + ALPHABETS_ONLY);
        }

        @Test
        @Transactional
        @DisplayName("L1614 leaves address line 2 unedited and L1728 to L1731 still count it "
                + "changed")
        void addressLineTwoIsUneditedAndStillCountsAsAChange() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setAddressLine2("12345 67890");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
            assertThat(storedCustomer(keys).getAddressLine2()).isEqualTo("12345 67890");
            assertThat(customerContextChangedRows(keys)).hasSize(1);
            assertThat(accountStateChangedRows(keys)).isEmpty();
        }
    }

    // =============================================================================================
    // An absent value is refused exactly as a blank one, from app/cbl/COACTUPC.cbl:L1470-L1676.
    // =============================================================================================

    /**
     * Every mandatory edit refuses an absent value, and says which field it was.
     *
     * <p>The source read its input from a fixed-width 3270 map area, so a field the operator cleared
     * arrived as spaces and the edit read spaces. A caller over the Hypertext Transfer Protocol can
     * leave a component out altogether, which the map area had no way to express, and the edit reads
     * an absent value instead. The two have to answer the same text: the alternative is a caller that
     * drops a field, reads 200, and never learns the value it meant to send did not arrive.
     *
     * <p>Each test below leaves one component absent and asserts the text the edit that owns it
     * composes. The refusals run in the order {@code 1200-EDIT-MAP-INPUTS} declares, so a body
     * missing two components reports the earlier one, which the last test pins.
     */
    @Nested
    @DisplayName("an absent mandatory value is refused as a blank one is")
    class AnAbsentMandatoryValueIsRefused {

        @Test
        @Transactional
        @DisplayName("Edit 1 at L1472 refuses an absent status, and says it was not supplied")
        void anAbsentStatusIsRefused() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setActiveStatus(null);

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.ACCOUNT_STATUS_LABEL + MUST_BE_SUPPLIED);
            assertThat(verdict.message())
                    .describedAs("the arm at app/cbl/COACTUPC.cbl:L1861-L1863 runs before the one at "
                            + "L1866, so a value that was not supplied is not reported as a value "
                            + "that is neither Y nor N")
                    .doesNotContain(MUST_BE_YES_OR_NO);
        }

        @Test
        @Transactional
        @DisplayName("Edit 2 at L1478 refuses an absent open date")
        void anAbsentOpenDateIsRefused() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setOpenDate(null);

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .startsWith(AccountUpdateService.OPEN_DATE_LABEL.trim())
                    .contains("must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 3 at L1484 refuses an absent credit limit")
        void anAbsentCreditLimitIsRefused() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(null);

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.CREDIT_LIMIT_LABEL.trim()
                            + " must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 7 at L1509 refuses an absent balance")
        void anAbsentBalanceIsRefused() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentBalance(null);

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).contains("must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 10 at L1529 refuses an absent Social Security number")
        void anAbsentSocialSecurityNumberIsRefused() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setAddressLine2("A change so the gate opens");

            EditResult verdict = service.updateAccount(account(keys),
                    withoutSocialSecurityNumber(submitted), account(keys), customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).contains("must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 12 at L1545 refuses an absent credit score")
        void anAbsentCreditScoreIsRefused() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setFicoCreditScore(null);

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.FICO_SCORE_LABEL.trim() + " must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 13 at L1560 refuses an absent first name")
        void anAbsentFirstNameIsRefused() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setFirstName(null);

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.FIRST_NAME_LABEL.trim() + " must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 16 at L1584 refuses an absent first address line")
        void anAbsentAddressLineOneIsRefused() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setAddressLine1(null);

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).isEqualTo("Address Line 1 must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("Edit 24 at L1657 refuses an absent primary card holder indicator")
        void anAbsentPrimaryCardHolderIndicatorIsRefused() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setPrimaryCardHolderIndicator(null);

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).contains("must be supplied.");
        }

        @Test
        @Transactional
        @DisplayName("An absent value and a blank one answer the same text")
        void anAbsentValueAndABlankOneAnswerTheSameText() {
            Keys absentKeys = persistedPair();
            AccountEntity absent = account(absentKeys);
            absent.setActiveStatus(null);
            EditResult absentVerdict = service.updateAccount(absent, customer(absentKeys),
                    account(absentKeys), customer(absentKeys));

            Keys blankKeys = persistedPair();
            AccountEntity blank = account(blankKeys);
            blank.setActiveStatus(" ");
            EditResult blankVerdict = service.updateAccount(blank, customer(blankKeys),
                    account(blankKeys), customer(blankKeys));

            assertThat(absentVerdict.message()).isEqualTo(blankVerdict.message());
        }

        @Test
        @Transactional
        @DisplayName("Two absent components report the one the source edits first")
        void twoAbsentComponentsReportTheEarlierEdit() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setActiveStatus(null);
            CustomerEntity submittedCustomer = customer(keys);
            submittedCustomer.setFirstName(null);

            EditResult verdict = service.updateAccount(submitted, submittedCustomer, account(keys),
                    customer(keys));

            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.ACCOUNT_STATUS_LABEL + MUST_BE_SUPPLIED);
        }

        /**
         * Asserts a blank value clears the five edits that read one without refusing it.
         *
         * <p>These are the components {@code api/AccountController} lets a caller omit, and omitting
         * one keeps the stored value, so the edit reads the stored value rather than a blank. A blank
         * still reaches these edits when the stored value is itself blank, which every seeded row with
         * no second address line demonstrates, and the edits have to accept it. The telephone edit
         * says so in its own comment at {@code app/cbl/COACTUPC.cbl:L2233}:
         * {@code Not mandatory to enter a phone number}.
         *
         * <p>A blank rather than an absent value is what this test submits, because the five columns
         * are declared {@code NOT NULL} in {@code db/migration/V1__schema.sql} and
         * {@code CustomerContextChanged} refuses to carry an absent one.
         */
        @Test
        @Transactional
        @DisplayName("A blank component no mandatory edit reads is accepted")
        void aBlankOptionalComponentIsAccepted() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setMiddleName("");
            submitted.setAddressLine2("");
            submitted.setPhoneNumber1("");
            submitted.setPhoneNumber2("");
            submitted.setGovernmentIssuedId("");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
        }
    }

    // =============================================================================================
    // Which record changed decides which event is written. app/cbl/COACTUPC.cbl:L1684-L1768 draws
    // the comparison, and this service applies it per record rather than over the pair.
    // =============================================================================================

    /**
     * One event per record this call changed, and none for a record it left as it stood.
     *
     * <p>The two rewrites at {@code app/cbl/COACTUPC.cbl:L4066} and {@code :L4086} run
     * unconditionally, each writing back the values the caller submitted, so a record whose
     * submitted values equal its fetched values is rewritten with what it already held. An event
     * announces a change of state, and such a record has none to announce.
     *
     * <p>Reaching this paragraph at all requires the submitted pair to differ from the fetched pair,
     * so at least one event is always written and a committed write is never silent.
     */
    @Nested
    @DisplayName("which record changed decides which event is written")
    class WhichRecordChangedDecidesWhichEvent {

        @Test
        @Transactional
        @DisplayName("An account-only change writes the account event alone")
        void anAccountOnlyChangeWritesTheAccountEventAlone() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(accountStateChangedRows(keys)).hasSize(1);
            assertThat(customerContextChangedRows(keys)).isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("A customer-only change writes the customer event alone")
        void aCustomerOnlyChangeWritesTheCustomerEventAlone() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setFirstName("Renamed");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(customerContextChangedRows(keys)).hasSize(1);
            assertThat(accountStateChangedRows(keys)).isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("A change to both records writes both events")
        void aChangeToBothRecordsWritesBothEvents() {
            Keys keys = persistedPair();
            AccountEntity submittedAccount = account(keys);
            submittedAccount.setCreditLimit(new BigDecimal("20300.00"));
            CustomerEntity submittedCustomer = customer(keys);
            submittedCustomer.setFirstName("Renamed");

            EditResult verdict = service.updateAccount(submittedAccount, submittedCustomer,
                    account(keys), customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(accountStateChangedRows(keys)).hasSize(1);
            assertThat(customerContextChangedRows(keys)).hasSize(1);
        }

        @Test
        @Transactional
        @DisplayName("An unchanged pair returns the L1466 no-change text and writes no event")
        void anUnchangedPairWritesNoEvent() {
            Keys keys = persistedPair();

            EditResult verdict = service.updateAccount(account(keys), customer(keys), account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
            assertThat(accountStateChangedRows(keys)).isEmpty();
            assertThat(customerContextChangedRows(keys)).isEmpty();
        }

        @Test
        @Transactional
        @DisplayName("Every committed write produces at least one event")
        void everyCommittedWriteProducesAtLeastOneEvent() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setAddressLine2("A second line no edit reads");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
            assertThat(accountStateChangedRows(keys).size()
                    + customerContextChangedRows(keys).size()).isGreaterThanOrEqualTo(1);
        }
    }

    // =============================================================================================
    // The twenty-five character host, WS-EDIT-VARIABLE-NAME PIC X(25) at
    // app/cbl/COACTUPC.cbl:L53.
    // =============================================================================================

    /**
     * The two cycle labels the container paragraph moves into a twenty-five character host.
     *
     * <p>{@code WS-EDIT-VARIABLE-NAME} is {@code PIC X(25)} at
     * {@code app/cbl/COACTUPC.cbl:L53}. The literal at {@code app/cbl/COACTUPC.cbl:L1515} holds
     * twenty-six characters, so the move drops its final character. The literal at
     * {@code app/cbl/COACTUPC.cbl:L1522} holds twenty-five and the move keeps every one.
     */
    @Nested
    @DisplayName("the twenty-five character host at L53")
    class TheTwentyFiveCharacterHost {

        /** The twenty-six characters {@code app/cbl/COACTUPC.cbl:L1515} moves. */
        private static final String CYCLE_CREDIT_LITERAL = "Current Cycle Credit Limit";

        /** The twenty-five characters {@code app/cbl/COACTUPC.cbl:L1522} moves. */
        private static final String CYCLE_DEBIT_LITERAL = "Current Cycle Debit Limit";

        @Test
        @DisplayName("The L1515 literal holds 26 characters and the host keeps its first 25")
        void theCycleCreditLiteralLosesItsFinalCharacter() {
            assertThat(CYCLE_CREDIT_LITERAL).hasSize(26);
            assertThat(AccountUpdateService.CURRENT_CYCLE_CREDIT_LABEL)
                    .hasSize(CobolDateValidator.EDIT_VARIABLE_NAME_WIDTH)
                    .isEqualTo("Current Cycle Credit Limi")
                    .isEqualTo(CYCLE_CREDIT_LITERAL.substring(0,
                            CobolDateValidator.EDIT_VARIABLE_NAME_WIDTH));
        }

        @Test
        @DisplayName("The L1522 literal holds 25 characters and the host keeps all of them")
        void theCycleDebitLiteralSurvivesWhole() {
            assertThat(CYCLE_DEBIT_LITERAL).hasSize(25);
            assertThat(AccountUpdateService.CURRENT_CYCLE_DEBIT_LABEL)
                    .hasSize(CobolDateValidator.EDIT_VARIABLE_NAME_WIDTH)
                    .isEqualTo("Current Cycle Debit Limit")
                    .isEqualTo(CYCLE_DEBIT_LITERAL);
        }

        @Test
        @Transactional
        @DisplayName("Edit 8 at L1515 to L1520 opens its message with the truncated label")
        void theTruncatedLabelOpensTheCycleCreditMessage() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentCycleCredit(null);

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.message())
                    .isEqualTo("Current Cycle Credit Limi" + MUST_BE_SUPPLIED)
                    .doesNotContain(CYCLE_CREDIT_LITERAL);
        }

        @Test
        @Transactional
        @DisplayName("Edit 9 at L1522 to L1527 opens its message with the whole label")
        void theWholeLabelOpensTheCycleDebitMessage() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentCycleDebit(null);

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.message())
                    .isEqualTo("Current Cycle Debit Limit" + MUST_BE_SUPPLIED);
        }
    }

    // =============================================================================================
    // The one message slot, WS-RETURN-MSG at app/cbl/COACTUPC.cbl:L479-L480.
    // =============================================================================================

    /**
     * The single message slot and the order it fills.
     *
     * <p>Every edit writes its text under the guard {@code IF WS-RETURN-MSG-OFF}, and the condition
     * name at {@code app/cbl/COACTUPC.cbl:L480} holds only while the slot is all spaces. The
     * earliest failing edit therefore owns the text, and later failing edits add none.
     * {@code app/cbl/COACTUPC.cbl:L1671-L1675} returns that one slot.
     */
    @Nested
    @DisplayName("the single message slot at L479 to L480")
    class TheSingleMessageSlot {

        @Test
        @Transactional
        @DisplayName("Three failing edits answer one message, the earliest in the L1470 to L1676 "
                + "order")
        void theEarliestFailingEditOwnsTheOnlyMessage() {
            Keys keys = persistedPair();
            AccountEntity submittedAccount = account(keys);
            submittedAccount.setActiveStatus("X");
            submittedAccount.setCurrentCycleCredit(null);
            CustomerEntity submittedCustomer = customer(keys);
            submittedCustomer.setFicoCreditScore(BigDecimal.ZERO);

            EditResult verdict = service.updateAccount(submittedAccount, submittedCustomer,
                    account(keys), customer(keys));

            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.ACCOUNT_STATUS_LABEL + MUST_BE_YES_OR_NO)
                    .doesNotContain(AccountUpdateService.CURRENT_CYCLE_CREDIT_LABEL)
                    .doesNotContain(MUST_NOT_BE_ZERO);
        }

        @Test
        @Transactional
        @DisplayName("A failing edit returns the L1671 to L1675 verdict and raises nothing")
        void aFailingEditIsReportedAndNeverThrown() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setActiveStatus("X");

            assertThatCode(() -> {
                EditResult verdict = service.updateAccount(submitted, customer(keys),
                        account(keys), customer(keys));
                assertThat(verdict.valid()).isFalse();
                assertThat(verdict.hasMessage()).isTrue();
            }).doesNotThrowAnyException();
        }
    }

    // =============================================================================================
    // 1205-COMPARE-OLD-NEW at app/cbl/COACTUPC.cbl:L1681-L1779, read through the public method.
    // =============================================================================================

    /**
     * The nine terms where {@code 1205-COMPARE-OLD-NEW} reads a field differently from
     * {@code 9700-CHECK-CHANGE-IN-REC}.
     *
     * <p>Each test below covers one term on the {@code 1205} side and names both locators once.
     * A term that differs sets {@code CHANGE-HAS-OCCURRED} at
     * {@code app/cbl/COACTUPC.cbl:L1703} and leaves the paragraph at
     * {@code app/cbl/COACTUPC.cbl:L1704}, so the field edits run. A pass where every term matches
     * reaches {@code app/cbl/COACTUPC.cbl:L1769} and answers the text at
     * {@code app/cbl/COACTUPC.cbl:L491-L492}.
     */
    @Nested
    @DisplayName("the nine divergent terms of 1205 at L1681 to L1779")
    class TheNineDivergentTerms {

        @Test
        @Transactional
        @DisplayName("Term 1: L1684 and L1708 to L1711 read both keys, where L4109 to L4195 read "
                + "neither")
        void bothKeysTakePartInTheComparison() {
            Keys keys = persistedPair();
            Keys other = keys();
            AccountEntity submitted = account(keys);
            submitted.setAccountId(other.accountId());

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.IDENTIFIER_OWNERSHIP_MISMATCH);
        }

        @Test
        @Transactional
        @DisplayName("Term 2: L1697 to L1700 fold the group over a trim, where L4139 to L4140 fold "
                + "to lower case with no trim")
        void theGroupIdentifierIsFoldedOverATrim() {
            Keys keys = keys();
            persist(account(keys, "Y", "20200.00", "Premium00 "), customer(keys), keys);

            EditResult verdict = service.updateAccount(account(keys, "Y", "20200.00", "PREMIUM00"),
                    customer(keys), account(keys, "Y", "20200.00", "Premium00 "), customer(keys));

            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        }

        @Test
        @Transactional
        @DisplayName("Term 3: L1692 to L1694 read the whole date, where L4127 to L4137 read three "
                + "slices")
        void theAccountDatesAreReadWhole() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setOpenDate("2014/11/20");

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
            assertThat(storedAccount(keys).getOpenDate()).isEqualTo("2014/11/20");
        }

        @Test
        @Transactional
        @DisplayName("Term 4: L1685 to L1688 fold the status to upper case, where L4115 reads it "
                + "plain")
        void theActiveStatusIsFoldedToUpperCase() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setActiveStatus("y");

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        }

        @Test
        @Transactional
        @DisplayName("Term 5: L1748 to L1753 read six telephone parts, where L4169 to L4170 read "
                + "two whole fields")
        void theTelephoneNumbersAreReadInParts() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setPhoneNumber1("[908]119.8310  ");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        }

        @Test
        @Transactional
        @DisplayName("Term 6: L1744 to L1747 fold the postcode over a trim, where L4168 reads it "
                + "plain")
        void theCustomerPostcodeIsFoldedOverATrim() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setAddressZip("27604");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        }

        @Test
        @Transactional
        @DisplayName("Term 7: L1759 to L1760 read one whole date of birth, where L4174 to L4179 "
                + "read three slices at two offsets")
        void theDateOfBirthIsReadWhole() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setDateOfBirth("1961/06/08");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
            assertThat(storedCustomer(keys).getDateOfBirth()).isEqualTo("1961/06/08");
        }

        @Test
        @Transactional
        @DisplayName("Term 8: L1763 to L1766 fold the card holder over a trim, where L4183 to "
                + "L4185 read it plain")
        void thePrimaryCardHolderIsFoldedOverATrim() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setPrimaryCardHolderIndicator("y");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        }

        @Test
        @Transactional
        @DisplayName("Term 9: L1689 to L1696 read the alphanumeric hosts, where L4117 to L4125 "
                + "read the numeric redefines; both target sides compare at the declared scale")
        void theMonetaryTermsCompareAtTheDeclaredScale() {
            Keys keys = persistedPair();
            AccountEntity belowScale = account(keys);
            belowScale.setCurrentBalance(new BigDecimal("1940.004"));
            AccountEntity withinScale = account(keys);
            withinScale.setCurrentBalance(new BigDecimal("1940.01"));

            EditResult unchanged = service.updateAccount(belowScale, customer(keys), account(keys),
                    customer(keys));
            EditResult changed = service.updateAccount(withinScale, customer(keys), account(keys),
                    customer(keys));

            assertThat(unchanged.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
            assertThat(changed.valid()).isTrue();
            assertThat(changed.hasMessage()).isFalse();
        }

        @Test
        @Transactional
        @DisplayName("The plain terms at L1754, L1761 to L1762 and L1767 to L1768 pad on the right "
                + "and fold nothing")
        void thePlainTermsPadOnTheRight() {
            Keys keys = persistedPair();
            CustomerEntity paddedIdentifier = customer(keys);
            paddedIdentifier.setEftAccountId("0053581");
            CustomerEntity changedNumber = customer(keys);
            changedNumber.setSocialSecurityNumber("020973889");

            EditResult padded = service.updateAccount(account(keys), paddedIdentifier,
                    account(keys), customer(keys, "0053581   "));
            EditResult numberChanged = service.updateAccount(account(keys), changedNumber,
                    account(keys), customer(keys));

            assertThat(padded.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
            assertThat(numberChanged.valid()).isTrue();
            assertThat(numberChanged.hasMessage()).isFalse();
        }
    }

    /**
     * The concurrency verdict at {@code app/cbl/COACTUPC.cbl:L3947-L3952}.
     *
     * <p>The check runs after both records are read for update and before the two rewrites at
     * {@code app/cbl/COACTUPC.cbl:L4066} and {@code app/cbl/COACTUPC.cbl:L4086}. A mismatch sets
     * the condition name at {@code app/cbl/COACTUPC.cbl:L521} whose literal sits at
     * {@code app/cbl/COACTUPC.cbl:L522}.
     */
    @Nested
    @DisplayName("the concurrency verdict at L3947 to L3952")
    class TheConcurrencyVerdict {

        @Test
        @Transactional
        @DisplayName("A row another writer moved answers the L522 text and writes nothing")
        void aMovedRowAnswersItsTextAndWritesNothing() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            EditResult verdict = service.updateAccount(submitted, customer(keys),
                    account(keys, "Y", "20100.00"), customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .isEqualTo(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE)
                    .isEqualTo("Record changed by some one else. Please review");
            assertThat(storedAccount(keys).getCreditLimit())
                    .isEqualByComparingTo(new BigDecimal("20200.00"));
            assertThat(accountStateChangedRows(keys)).isEmpty();
        }
    }

    // =============================================================================================
    // One transaction and one event row, from app/cbl/COACTUPC.cbl:L4066 and L4086.
    // =============================================================================================

    /**
     * The two record rewrites and the event row.
     *
     * <p>The source rewrites two files in one unit of work: the account at
     * {@code app/cbl/COACTUPC.cbl:L4066} and the customer at
     * {@code app/cbl/COACTUPC.cbl:L4086}. Every file definition in {@code app/csd/CARDDEMO.CSD}
     * carries {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, at
     * {@code app/csd/CARDDEMO.CSD:L3-L9} for the first. The target commits both rows and the event
     * row together.
     *
     * <p>{@code outbox/OutboxWriter} joins the transaction its caller opened and publishes nothing.
     * The relay sweep, its ordering and the schema check sit under {@code outbox} and no test here
     * reaches them.
     */
    @Nested
    @DisplayName("the two rewrites at L4066 and L4086 and the event row")
    class TheTwoRewritesAndTheEventRow {

        @Test
        @Transactional
        @DisplayName("The L4066 and L4086 pass writes one event row, unpublished and unstamped")
        void aCleanPassWritesOneUnpublishedEventRow() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            List<OutboxEventEntity> rows = accountStateChangedRows(keys);
            assertThat(rows).hasSize(1);
            OutboxEventEntity row = rows.get(0);
            assertThat(row.getAggregateId()).isEqualTo(keys.accountId());
            assertThat(row.isPublished()).isFalse();
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getEventType()).isEqualTo(AccountStateChanged.EVENT_TYPE);
        }

        @Test
        @Transactional
        @DisplayName("The payload of the L4066 rewrite names ACCOUNT_UPDATED and the account")
        void thePayloadNamesTheChangeKindAndTheAccount() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            service.updateAccount(submitted, customer(keys), account(keys), customer(keys));
            flushAndDetach();
            JsonNode payload = payloadOfTheAccountStateChangedRow(keys);

            assertThat(payload.get("changeKind").stringValue())
                    .isEqualTo(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED.name());
            assertThat(payload.get("accountId").stringValue()).isEqualTo(keys.accountId());
            assertThat(payload.get("expirationDate").stringValue()).isEqualTo("2025-05-20");
        }

        @Test
        @Transactional
        @DisplayName("Every CVACT01Y L7, L8, L13 and L14 property travels as a quoted string")
        void everyMonetaryPropertyTravelsAsAQuotedString() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            service.updateAccount(submitted, customer(keys), account(keys), customer(keys));
            flushAndDetach();
            JsonNode payload = payloadOfTheAccountStateChangedRow(keys);

            for (String property : List.of("currentBalance", "creditLimit", "currentCycleCredit",
                    "currentCycleDebit")) {
                assertThat(payload.get(property).isString())
                        .describedAs("%s travels as a JSON number", property)
                        .isTrue();
                assertThat(payload.get(property).stringValue())
                        .matches(AccountStateChanged.MONETARY_PATTERN);
            }
        }

        @Test
        @Transactional
        @DisplayName("A pass refused before L4066 leaves both rows and the outbox alone")
        void aRefusedPassLeavesBothRowsAndTheOutboxAlone() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(new BigDecimal("20300.00"));
            CustomerEntity submittedCustomer = customer(keys);
            submittedCustomer.setFirstName("Renamed");

            EditResult verdict = service.updateAccount(submitted, submittedCustomer,
                    account(keys, "Y", "20100.00"), submittedCustomer);
            flushAndDetach();

            assertThat(verdict.valid()).isFalse();
            assertThat(storedAccount(keys).getCreditLimit())
                    .isEqualByComparingTo(new BigDecimal("20200.00"));
            assertThat(storedCustomer(keys).getFirstName()).isEqualTo("Immanuel");
            assertThat(accountStateChangedRows(keys)).isEmpty();
        }
    }

    // =============================================================================================
    // Which edit runs, from app/cbl/COACTUPC.cbl:L2137-L2201 and app/cpy/CSUTLDPY.cpy:L284-L366.
    // =============================================================================================

    /**
     * The two numeric edits and the four date call sites.
     *
     * <p>The signed-decimal edit reads its whole host. {@code WS-EDIT-SIGNED-NUMBER-9V2-X} is
     * {@code PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L55}, and the gate at
     * {@code app/cbl/COACTUPC.cbl:L2201} accepts a value the currency-aware conversion reads, where
     * zero means valid. The numeric edit runs a class test at
     * {@code app/cbl/COACTUPC.cbl:L2137-L2138} and then a plain conversion at
     * {@code app/cbl/COACTUPC.cbl:L2156-L2157}, and the class test refuses a currency symbol.
     *
     * <p>Date validation here is strict. {@code app/cpy/CSUTLDPY.cpy:L291} moves the mask
     * {@code 'YYYYMMDD'} and {@code app/cpy/CSUTLDPY.cpy:L298} accepts severity zero alone. The
     * paragraph runs from {@code app/cpy/CSUTLDPY.cpy:L284} to its closing period at
     * {@code app/cpy/CSUTLDPY.cpy:L321}, with its exit at {@code app/cpy/CSUTLDPY.cpy:L323}, and
     * carries no message-number branch. Four call sites reach it:
     * {@code app/cbl/COACTUPC.cbl:L1480}, {@code app/cbl/COACTUPC.cbl:L1492},
     * {@code app/cbl/COACTUPC.cbl:L1505} and {@code app/cbl/COACTUPC.cbl:L1536}.
     */
    @Nested
    @DisplayName("which edit runs, at L2137 to L2201 and CSUTLDPY L284 to L366")
    class WhichEditRuns {

        @Test
        @Transactional
        @DisplayName("The L2201 gate accepts a signed amount carrying a decimal point")
        void theSignedDecimalGateAcceptsASignedAmount() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentBalance(new BigDecimal("-1940.55"));

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));
            flushAndDetach();

            assertThat(verdict.valid()).isTrue();
            assertThat(verdict.hasMessage()).isFalse();
            assertThat(storedAccount(keys).getCurrentBalance())
                    .isEqualByComparingTo(new BigDecimal("-1940.55"));
        }

        @Test
        @Transactional
        @DisplayName("The L2137 class test refuses a currency symbol the L2201 gate would read")
        void theClassTestRefusesACurrencySymbol() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setEftAccountId("$053581756");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.message())
                    .isEqualTo(AccountUpdateService.EFT_ACCOUNT_ID_LABEL + MUST_BE_ALL_NUMERIC);
        }

        @Test
        @Transactional
        @DisplayName("The L1480 open-date call site refuses a day the month does not hold")
        void theOpenDateCallSiteRefusesAnImpossibleDay() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setOpenDate("2014-02-30");

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).startsWith(AccountUpdateService.OPEN_DATE_LABEL);
        }

        @Test
        @Transactional
        @DisplayName("The L1492 expiry call site refuses a day the month does not hold")
        void theExpiryCallSiteRefusesAnImpossibleDay() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setExpirationDate("2025-02-30");

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).startsWith(AccountUpdateService.EXPIRY_DATE_LABEL);
        }

        @Test
        @Transactional
        @DisplayName("The L1505 reissue call site refuses a day the month does not hold")
        void theReissueCallSiteRefusesAnImpossibleDay() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setReissueDate("2025-02-30");

            EditResult verdict = service.updateAccount(submitted, customer(keys), account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).startsWith(AccountUpdateService.REISSUE_DATE_LABEL);
        }

        @Test
        @Transactional
        @DisplayName("The L1536 birth-date call site refuses a month outside 1 through 12")
        void theBirthDateCallSiteRefusesAnImpossibleMonth() {
            Keys keys = persistedPair();
            CustomerEntity submitted = customer(keys);
            submitted.setDateOfBirth("1961-13-08");

            EditResult verdict = service.updateAccount(account(keys), submitted, account(keys),
                    customer(keys));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message()).startsWith(AccountUpdateService.DATE_OF_BIRTH_LABEL);
        }

        /**
         * The boundary itself, against one date that does not move while the test runs.
         *
         * <p>{@code app/cpy/CSUTLDPY.cpy:L350} compares with strict greater-than, so the reference
         * day is refused and the day before it passes. Both verdicts are read at the seam
         * {@link DateOfBirthValidator#validate(String, String, LocalDate)} exposes, which takes the
         * day the comparison runs against as an argument. One captured date supplies both the
         * submitted value and the reference, so the pair cannot disagree about which day it is.
         *
         * <p>This used to read {@code LocalDate.now()} for the value while production read it again
         * for the comparison. A run that crossed local midnight between those two reads submitted
         * yesterday's date against today's reference, which passes, and the assertion that today is
         * refused failed for a reason that had nothing to do with the rule.
         */
        @Test
        @DisplayName("CSUTLDPY L350 compares with strict greater-than, so the reference day is "
                + "refused and the day before it passes")
        void theReferenceDayIsRefusedAndTheDayBeforeItPasses() {
            LocalDate reference = LocalDate.of(2024, 2, 29);

            EditResult onTheDay = DateOfBirthValidator.validate(
                    AccountUpdateService.DATE_OF_BIRTH_LABEL,
                    reference.format(EIGHT_CHARACTER_DATE), reference);
            EditResult theDayBefore = DateOfBirthValidator.validate(
                    AccountUpdateService.DATE_OF_BIRTH_LABEL,
                    reference.minusDays(1).format(EIGHT_CHARACTER_DATE), reference);

            assertThat(onTheDay.valid()).isFalse();
            assertThat(onTheDay.message())
                    .isEqualTo(AccountUpdateService.DATE_OF_BIRTH_LABEL
                            + CobolDateValidator.FUTURE_DATE_MESSAGE);
            assertThat(theDayBefore.valid()).isTrue();
            assertThat(theDayBefore.hasMessage()).isFalse();
        }

        /**
         * The same rule reached through the public method, using two values whose verdict a rollover
         * cannot change.
         *
         * <p>{@code AccountUpdateService} reaches the check through
         * {@link DateOfBirthValidator#validate(String, String)}, which reads the current date itself,
         * so no fixed date can be handed to it. What can be fixed is the distance from the boundary:
         * one day after the captured date is refused whether the comparison runs on the captured day
         * or on the next one, and one day before it passes on either. Both verdicts are therefore
         * invariant under a midnight crossing, and together they prove the service compares against
         * the current date rather than against a constant.
         */
        @Test
        @Transactional
        @DisplayName("The update path refuses a date one day ahead and accepts one day behind, on "
                + "either side of a rollover")
        void theUpdatePathRefusesADateAheadAndAcceptsOneBehind() {
            LocalDate captured = LocalDate.now();
            Keys keys = persistedPair();
            CustomerEntity ahead = customer(keys);
            ahead.setDateOfBirth(captured.plusDays(1).format(TEN_CHARACTER_DATE));
            CustomerEntity behind = customer(keys);
            behind.setDateOfBirth(captured.minusDays(1).format(TEN_CHARACTER_DATE));

            EditResult aheadVerdict = service.updateAccount(account(keys), ahead, account(keys),
                    customer(keys));
            EditResult behindVerdict = service.updateAccount(account(keys), behind,
                    account(keys), customer(keys));

            assertThat(aheadVerdict.valid()).isFalse();
            assertThat(aheadVerdict.message())
                    .isEqualTo(AccountUpdateService.DATE_OF_BIRTH_LABEL
                            + CobolDateValidator.FUTURE_DATE_MESSAGE);
            assertThat(behindVerdict.valid()).isTrue();
            assertThat(behindVerdict.hasMessage()).isFalse();
        }
    }

    // =============================================================================================
    // Arithmetic. app/cpy/CVACT01Y.cpy declares five PIC S9(10)V99 fields at L7, L8, L9, L13
    // and L14, and app/cpy/CVACT01Y.cpy:L12 declares ACCT-REISSUE-DATE PIC X(10).
    // =============================================================================================

    /**
     * Monetary storage at the declared scale.
     *
     * <p>The {@code ROUNDED} phrase appears zero times across the twenty-eight programs under
     * {@code app/cbl}, so every store truncates toward zero. {@link CobolDecimal} carries that one
     * mode and this service reaches every amount through it.
     */
    @Nested
    @DisplayName("monetary storage at the scale CVACT01Y declares")
    class MonetaryStorage {

        @Test
        @DisplayName("The five PIC S9(10)V99 fields at CVACT01Y L7, L8, L9, L13 and L14 hold two "
                + "fractional digits")
        void theFiveDeclaredScalesAreTwo() {
            assertThat(List.of(PicClause.ACCT_CURR_BAL_SCALE, PicClause.ACCT_CREDIT_LIMIT_SCALE,
                    PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE, PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                    PicClause.ACCT_CURR_CYC_DEBIT_SCALE))
                    .containsExactly(2, 2, 2, 2, 2);
        }

        @Test
        @Transactional
        @DisplayName("A third fractional digit on CVACT01Y L7 truncates toward zero, where half-up "
                + "would carry")
        void aThirdFractionalDigitTruncatesTowardZero() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentBalance(new BigDecimal("1940.005"));
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            service.updateAccount(submitted, customer(keys), account(keys), customer(keys));
            flushAndDetach();

            assertThat(storedAccount(keys).getCurrentBalance().toPlainString())
                    .isEqualTo("1940.00")
                    .isEqualTo(CobolDecimal
                            .truncateToScale(new BigDecimal("1940.005"),
                                    PicClause.ACCT_CURR_BAL_SCALE)
                            .toPlainString());
            assertThat(new BigDecimal("1940.005")
                    .setScale(PicClause.ACCT_CURR_BAL_SCALE, RoundingMode.HALF_UP).toPlainString())
                    .isEqualTo("1940.01")
                    .isNotEqualTo("1940.00");
        }

        @Test
        @Transactional
        @DisplayName("A negative third fractional digit on CVACT01Y L7 truncates toward zero as "
                + "well")
        void aNegativeThirdFractionalDigitTruncatesTowardZero() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentBalance(new BigDecimal("-1940.005"));
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            service.updateAccount(submitted, customer(keys), account(keys), customer(keys));
            flushAndDetach();

            assertThat(storedAccount(keys).getCurrentBalance().toPlainString())
                    .isEqualTo("-1940.00");
            assertThat(new BigDecimal("-1940.005")
                    .setScale(PicClause.ACCT_CURR_BAL_SCALE, RoundingMode.HALF_UP).toPlainString())
                    .isEqualTo("-1940.01")
                    .isNotEqualTo("-1940.00");
        }

        @Test
        @Transactional
        @DisplayName("The stored payload carries the CVACT01Y L7 amount truncated, not carried")
        void theEventPayloadCarriesTheTruncatedAmount() {
            Keys keys = persistedPair();
            AccountEntity submitted = account(keys);
            submitted.setCurrentBalance(new BigDecimal("1940.006"));
            submitted.setCreditLimit(new BigDecimal("20300.00"));

            service.updateAccount(submitted, customer(keys), account(keys), customer(keys));
            flushAndDetach();

            assertThat(payloadOfTheAccountStateChangedRow(keys).get("currentBalance").stringValue())
                    .isEqualTo("1940.00");
        }
    }

    // =============================================================================================
    // The two locked reads of 9600-WRITE-PROCESSING, and the answers a refused lock produces.
    // app/cbl/COACTUPC.cbl:L3892-L3903 reads the account for update and L3907-L3915 answers a
    // response other than DFHRESP(NORMAL). app/cbl/COACTUPC.cbl:L3919-L3930 reads the customer and
    // L3934-L3942 answers the same way.
    // =============================================================================================

    /**
     * The exception arms of the two locked reads.
     *
     * <p>Each locked read has two ways of not producing a row, and the source draws no distinction
     * between them: {@code app/cbl/COACTUPC.cbl:L3907} tests for a response other than
     * {@code DFHRESP(NORMAL)} and answers one message for every one of them. A row that has gone
     * returns empty, which the groups above cover. A lock the datastore will not grant inside the
     * bound {@code carddemo.write.lock-wait-ms} sets raises instead, and the raising arm had no test
     * of its own.
     *
     * <p>Two exception types reach each arm. Hibernate reports a refused lock as a pessimistic-lock
     * failure, and reports a statement the driver aborted for another reason as a system fault; the
     * production catch names both, because a lock timeout has been observed to arrive as either
     * depending on where the driver raises. Four cases follow: two types on the account read and the
     * same two on the customer read.
     *
     * <p>Every test in this group runs OUTSIDE a test transaction, which is a deliberate departure
     * from every other group of this class. The refusal is carried out of
     * {@code updateAccount} as {@code LockNotTaken}, which leaves the transaction the service opened
     * marked for rollback. A test transaction would have been that transaction, so the rollback would
     * have been the test's own and nothing about it observable. Running outside one makes the service
     * open a physical transaction of its own, so what it did or did not commit can be read back
     * afterwards. The rows are committed by {@link #inOwnTransaction(Supplier)} and removed by
     * {@link #removeCommittedRows()}.
     *
     * <p>{@link MockitoSpyBean} wraps the two repositories, so the bound {@code lock_timeout}
     * statement and every unstubbed read still reach the real repository and the real database. Only
     * the one locked read a test names is made to raise, and it is stubbed with {@code doThrow} rather
     * than {@code when(...).thenThrow(...)}: the latter calls the method being stubbed, which would
     * perform the very read the test is arranging.
     */
    @Nested
    @TestPropertySource(properties = {
            "carddemo.outbox.relay.fixed-delay-ms=3600000",
            "carddemo.retention.sweep-interval-ms=3600000"
    })
    @DisplayName("a refused lock at L3907 and at L3934")
    class RefusedLocks {

        /** Text a refused lock reports, which reveals nothing about the row or the datastore. */
        private static final String DRIVER_TEXT = "canceling statement due to lock timeout";

        /** Meter name the update latency timer carries. */
        private static final String UPDATE_LATENCY = "carddemo.account.update.latency";

        /** Meter name the transaction-failure counter carries. */
        private static final String TRANSACTION_FAILURES = "carddemo.account.transaction.failures";

        /** Tag value that counter carries for the update path. */
        private static final String UPDATE_OPERATION = "update";

        /** Wraps the account repository so one locked read can be made to raise. */
        @MockitoSpyBean
        private AccountRepository lockedAccounts;

        /** Wraps the customer repository for the same reason. */
        @MockitoSpyBean
        private CustomerRepository lockedCustomers;

        /** Opens the committing and cleaning transactions this group needs. */
        @Autowired
        private PlatformTransactionManager transactionManager;

        /** Reads the meter values a refusal moved. */
        @Autowired
        private MeterRegistry meters;

        /** Keys of the rows this group committed, removed after each test. */
        private Keys committed;

        /** Removes the rows the test committed, whatever the test did. */
        @AfterEach
        void removeCommittedRows() {
            if (committed == null) {
                return;
            }
            Keys keys = committed;
            committed = null;
            inOwnTransaction(() -> {
                entityManager.createNativeQuery("DELETE FROM outbox_event WHERE aggregate_id = ?1")
                        .setParameter(1, keys.accountId())
                        .executeUpdate();
                entityManager
                        .createNativeQuery(
                                "DELETE FROM account_customer_link WHERE account_id = ?1")
                        .setParameter(1, keys.accountId())
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM account WHERE account_id = ?1")
                        .setParameter(1, keys.accountId())
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM customer WHERE customer_id = ?1")
                        .setParameter(1, keys.customerId())
                        .executeUpdate();
                return null;
            });
        }

        /**
         * The four cases: two exception types on each of the two locked reads.
         *
         * @param onTheCustomerRead whether the customer read raises rather than the account read
         * @param systemFault       whether the raised type is the system fault rather than the
         *                          pessimistic-lock failure
         */
        @ParameterizedTest(name = "customerRead={0}, systemFault={1}")
        @CsvSource({"false,false", "false,true", "true,false", "true,true"})
        @DisplayName("answers the fixed message of its own paragraph and commits nothing")
        void aRefusedLockAnswersItsFixedMessageAndCommitsNothing(boolean onTheCustomerRead,
                boolean systemFault) {
            Keys keys = commitPair();
            AccountEntity before = readCommittedAccount(keys);
            CustomerEntity customerBefore = readCommittedCustomer(keys);
            refuseTheLock(keys, onTheCustomerRead, systemFault);

            EditResult verdict = service.updateAccount(raisedLimit(keys), customer(keys),
                    account(keys), customer(keys));

            String expected = onTheCustomerRead
                    ? AccountUpdateService.COULD_NOT_LOCK_CUSTOMER
                    : AccountUpdateService.COULD_NOT_LOCK_ACCOUNT;
            assertThat(verdict.valid())
                    .as("a refused lock is a failing verdict and not a fault")
                    .isFalse();
            assertThat(verdict.message())
                    .as("the message app/cbl/COACTUPC.cbl answers for this paragraph")
                    .isEqualTo(expected);

            assertThat(readCommittedAccount(keys).getCreditLimit())
                    .as("the credit limit the caller submitted did not commit")
                    .isEqualByComparingTo(before.getCreditLimit());
            assertThat(readCommittedCustomer(keys).getFirstName())
                    .isEqualTo(customerBefore.getFirstName());
            assertThat(committedOutboxRows(keys))
                    .as("a transaction that wrote nothing announces nothing")
                    .isZero();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("the transaction the service opened ended rather than staying open")
                    .isFalse();
        }

        /**
         * Asserts the message names nothing about the row, the caller or the datastore.
         *
         * <p>The two texts are fixed constants, and the driver text of the cause reaches neither.
         * A refused lock names which record could not be locked and stops there: telling a caller
         * that another writer holds a row, or which statement the driver cancelled, describes the
         * traffic of every other caller of the same account.
         */
        @ParameterizedTest(name = "customerRead={0}")
        @CsvSource({"false", "true"})
        @DisplayName("reveals no identifier and no text from the cause")
        void aRefusedLockRevealsNoIdentifierAndNoDriverText(boolean onTheCustomerRead) {
            Keys keys = commitPair();
            refuseTheLock(keys, onTheCustomerRead, false);

            EditResult verdict = service.updateAccount(raisedLimit(keys), customer(keys),
                    account(keys), customer(keys));

            assertThat(verdict.message())
                    .doesNotContain(keys.accountId())
                    .doesNotContain(keys.customerId())
                    .doesNotContain(keys.cardNumber())
                    .doesNotContain(DRIVER_TEXT)
                    .doesNotContain("lock_timeout")
                    .doesNotContain(PessimisticLockingFailureException.class.getSimpleName())
                    .doesNotContain(JpaSystemException.class.getSimpleName());
        }

        /**
         * Asserts a refused lock is timed and is not counted as a service failure.
         *
         * <p>{@code AccountUpdateService.updateAccount} records the latency of the refused attempt
         * and leaves the transaction-failure counter alone, exactly as the empty-result arm of the
         * same paragraph does. The two arms answer one message for one reason, so counting one of
         * them as a defect would have an operator read a contended write as a fault of this service.
         */
        @Test
        @DisplayName("is timed as an update and counted as no failure")
        void aRefusedLockIsTimedAndCountedAsNoFailure() {
            Keys keys = commitPair();
            long timedBefore = updateLatencyCount();
            double failuresBefore = updateFailureCount();
            refuseTheLock(keys, false, false);

            service.updateAccount(raisedLimit(keys), customer(keys), account(keys), customer(keys));

            assertThat(updateLatencyCount())
                    .as("the refused attempt was timed")
                    .isEqualTo(timedBefore + 1L);
            assertThat(updateFailureCount())
                    .as("a contended write is not a failure of this service")
                    .isEqualTo(failuresBefore);
        }

        /**
         * Asserts the refused attempt left nothing behind that stops the next one.
         *
         * <p>This is the observable content of the rollback. The refusal is carried out of a
         * transaction that PostgreSQL has already put beyond use, and a transaction left open or a
         * connection returned to the pool inside an aborted transaction would make the next update
         * fail too. Removing the stub and updating the same pair proves the opposite: the second
         * attempt commits its columns and writes its event.
         */
        @Test
        @DisplayName("leaves the next update of the same pair free to commit")
        void aRefusedLockLeavesTheNextUpdateFreeToCommit() {
            Keys keys = commitPair();
            refuseTheLock(keys, false, false);
            service.updateAccount(raisedLimit(keys), customer(keys), account(keys), customer(keys));

            Mockito.reset(lockedAccounts);
            EditResult second = service.updateAccount(raisedLimit(keys), customer(keys),
                    account(keys), customer(keys));

            assertThat(second.valid()).isTrue();
            assertThat(second.hasMessage()).isFalse();
            assertThat(readCommittedAccount(keys).getCreditLimit())
                    .as("the second attempt committed the limit the first could not")
                    .isEqualByComparingTo(RAISED_CREDIT_LIMIT);
            assertThat(committedOutboxRows(keys))
                    .as("one event announces the one change that committed")
                    .isEqualTo(1L);
        }

        /**
         * Makes one of the two locked reads raise one of the two types.
         *
         * @param keys              the keys the read names
         * @param onTheCustomerRead whether the customer read raises rather than the account read
         * @param systemFault       whether the raised type is the system fault
         */
        private void refuseTheLock(Keys keys, boolean onTheCustomerRead, boolean systemFault) {
            RuntimeException refusal = systemFault
                    ? new JpaSystemException(new RuntimeException(DRIVER_TEXT))
                    : new PessimisticLockingFailureException(DRIVER_TEXT);
            if (onTheCustomerRead) {
                Mockito.doThrow(refusal).when(lockedCustomers)
                        .findForUpdateByCustomerId(keys.customerId());
                return;
            }
            Mockito.doThrow(refusal).when(lockedAccounts)
                    .findForUpdateByAccountId(keys.accountId());
        }

        /**
         * Commits one account, one customer and one relationship row, and records them for removal.
         *
         * @return the keys the three rows carry
         */
        private Keys commitPair() {
            committed = inOwnTransaction(this::persistOnePair);
            return committed;
        }

        /**
         * Stores the three rows inside the committing transaction.
         *
         * @return the keys they carry
         */
        private Keys persistOnePair() {
            Keys keys = keys();
            persist(account(keys), customer(keys), keys);
            return keys;
        }

        /**
         * Builds the submitted account, differing from the stored one by its credit limit alone.
         *
         * @param keys the keys it carries
         * @return the submitted account
         */
        private AccountEntity raisedLimit(Keys keys) {
            AccountEntity submitted = account(keys);
            submitted.setCreditLimit(RAISED_CREDIT_LIMIT);
            return submitted;
        }

        /**
         * Reads the committed account row on a transaction of its own.
         *
         * @param keys the keys it carries
         * @return the stored row
         */
        private AccountEntity readCommittedAccount(Keys keys) {
            return inOwnTransaction(() -> accounts.findById(keys.accountId()).orElseThrow());
        }

        /**
         * Reads the committed customer row on a transaction of its own.
         *
         * @param keys the keys it carries
         * @return the stored row
         */
        private CustomerEntity readCommittedCustomer(Keys keys) {
            return inOwnTransaction(() -> customers.findById(keys.customerId()).orElseThrow());
        }

        /**
         * Counts the committed outbox rows of one account.
         *
         * @param keys the keys they carry
         * @return the count
         */
        private long committedOutboxRows(Keys keys) {
            return inOwnTransaction(() -> outboxEvents.findAll().stream()
                    .filter(row -> keys.accountId().equals(row.getAggregateId()))
                    .count());
        }

        /** @return how many update latencies have been recorded */
        private long updateLatencyCount() {
            Timer timer = meters.find(UPDATE_LATENCY).timer();
            return timer == null ? 0L : timer.count();
        }

        /** @return how many update transactions have been counted as failures */
        private double updateFailureCount() {
            Counter counter = meters.find(TRANSACTION_FAILURES)
                    .tag("operation", UPDATE_OPERATION).counter();
            return counter == null ? 0.0d : counter.count();
        }

        /**
         * Runs one unit of work in a transaction of its own and commits it.
         *
         * @param work the work to run
         * @param <T>  what it answers
         * @return what it answered
         */
        private <T> T inOwnTransaction(Supplier<T> work) {
            TransactionTemplate own = new TransactionTemplate(transactionManager);
            own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return own.execute(status -> work.get());
        }
    }

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * One unused key triple.
     *
     * @param accountId  eleven digits, the width {@code app/jcl/ACCTFILE.jcl:L40} declares
     * @param customerId nine digits, the width {@code app/jcl/CUSTFILE.jcl:L50} declares
     * @param cardNumber sixteen digits, the key {@code app/jcl/XREFFILE.jcl:L39-L47} declares
     */
    private record Keys(String accountId, String customerId, String cardNumber) {
    }

    /**
     * Allocates one key triple above the seeded range.
     *
     * @return keys no other test and no migration uses
     */
    private static Keys keys() {
        int ordinal = KEY_SEQUENCE.incrementAndGet();
        return new Keys(String.format(Locale.ROOT, "009%08d", ordinal),
                String.format(Locale.ROOT, "009%06d", ordinal),
                String.format(Locale.ROOT, "9%015d", ordinal));
    }

    /**
     * Allocates keys, stores the account row, the customer row and the relationship row, then
     * detaches everything.
     *
     * @return the keys the three stored rows carry
     */
    private Keys persistedPair() {
        Keys keys = keys();
        persist(account(keys), customer(keys), keys);
        return keys;
    }

    /**
     * Stores one account row, one customer row and the relationship the update resolves the
     * customer through, then detaches everything.
     *
     * <p>{@code domain/AccountUpdateService} reads {@code account_customer_link} for the customer
     * the account names, the read {@code app/cbl/COACTUPC.cbl:L3617-L3618} performs. The insert
     * below is Structured Query Language (SQL) over the table
     * {@code src/main/resources/db/migration/V7__account_customer_link.sql} creates. The card
     * number {@code keys} carries is not written: the table holds the pair alone, because no query
     * in this service reads a card.
     *
     * @param account  the account row to store
     * @param customer the customer row to store
     * @param keys     the keys the relationship row names
     */
    private void persist(AccountEntity account, CustomerEntity customer, Keys keys) {
        accounts.save(account);
        customers.save(customer);
        entityManager
                .createNativeQuery(
                        "INSERT INTO account_customer_link (account_id, customer_id) "
                                + "VALUES (?1, ?2)")
                .setParameter(1, keys.accountId())
                .setParameter(2, keys.customerId())
                .executeUpdate();
        flushAndDetach();
    }

    /** Writes every pending change and empties the persistence context. */
    private void flushAndDetach() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Reads the stored account row.
     *
     * @param keys the keys the row carries
     * @return the row the database holds
     */
    private AccountEntity storedAccount(Keys keys) {
        return accounts.findByAccountId(keys.accountId()).orElseThrow();
    }

    /**
     * Reads the stored customer row.
     *
     * @param keys the keys the row carries
     * @return the row the database holds
     */
    private CustomerEntity storedCustomer(Keys keys) {
        return customers.findByCustomerId(keys.customerId()).orElseThrow();
    }

    /**
     * Reads every stored account-state event for one account.
     *
     * <p>No migration seeds {@code outbox_event}, and each test owns its account identifier, so the
     * filter below returns the rows one update wrote. The customer-detail event travels on its own
     * contract and this filter leaves it out.
     *
     * @param keys the keys the update carried
     * @return the account-state rows, in the order the store returns them
     */
    private List<OutboxEventEntity> accountStateChangedRows(Keys keys) {
        return outboxEvents.findAll().stream()
                .filter(row -> keys.accountId().equals(row.getAggregateId()))
                .filter(row -> AccountStateChanged.EVENT_TYPE.equals(row.getEventType()))
                .toList();
    }

    /**
     * Reads every stored customer-detail event for one account.
     *
     * <p>The companion of {@link #accountStateChangedRows(Keys)}. Both events carry the account
     * identifier as their aggregate, since the account is what a consumer of either one keys on, so
     * the event type is what separates them.
     *
     * @param keys the keys the update carried
     * @return the customer-detail rows, in the order the store returns them
     */
    private List<OutboxEventEntity> customerContextChangedRows(Keys keys) {
        return outboxEvents.findAll().stream()
                .filter(row -> keys.accountId().equals(row.getAggregateId()))
                .filter(row -> CustomerContextChanged.EVENT_TYPE.equals(row.getEventType()))
                .toList();
    }

    /**
     * Reads the payload of the one account-state event row.
     *
     * @param keys the keys the update carried
     * @return the payload as a tree
     */
    private JsonNode payloadOfTheAccountStateChangedRow(Keys keys) {
        List<OutboxEventEntity> rows = accountStateChangedRows(keys);
        assertThat(rows).hasSize(1);
        return eventMapper.readTree(rows.get(0).getPayload());
    }

    /**
     * Builds one account that clears every edit.
     *
     * <p>The values come from row one of {@code app/data/ASCII/acctdata.txt}. Each call returns a
     * fresh instance.
     *
     * @param keys the keys the account carries
     * @return the account
     */
    private static AccountEntity account(Keys keys) {
        return account(keys, "Y");
    }

    /**
     * Builds one account carrying the active status supplied.
     *
     * @param keys         the keys the account carries
     * @param activeStatus the value edit 1 at {@code app/cbl/COACTUPC.cbl:L1472} reads
     * @return the account
     */
    private static AccountEntity account(Keys keys, String activeStatus) {
        return account(keys, activeStatus, "20200.00");
    }

    /**
     * Builds one account carrying the active status and credit limit supplied.
     *
     * @param keys         the keys the account carries
     * @param activeStatus the value edit 1 at {@code app/cbl/COACTUPC.cbl:L1472} reads
     * @param creditLimit  the value edit 3 at {@code app/cbl/COACTUPC.cbl:L1484} reads
     * @return the account
     */
    private static AccountEntity account(Keys keys, String activeStatus, String creditLimit) {
        return account(keys, activeStatus, creditLimit, "Premium001");
    }

    /**
     * Builds one account carrying the active status, credit limit and group supplied.
     *
     * @param keys         the keys the account carries
     * @param activeStatus the value edit 1 at {@code app/cbl/COACTUPC.cbl:L1472} reads
     * @param creditLimit  the value edit 3 at {@code app/cbl/COACTUPC.cbl:L1484} reads
     * @param groupId      the value {@code app/cbl/COACTUPC.cbl:L1697-L1700} compares
     * @return the account
     */
    private static AccountEntity account(Keys keys, String activeStatus, String creditLimit,
            String groupId) {
        AccountEntity account = new AccountEntity();
        account.setAccountId(keys.accountId());
        account.setActiveStatus(activeStatus);
        account.setCurrentBalance(new BigDecimal("1940.00"));
        account.setCreditLimit(new BigDecimal(creditLimit));
        account.setCashCreditLimit(new BigDecimal("10200.00"));
        account.setOpenDate("2014-11-20");
        account.setExpirationDate("2025-05-20");
        account.setReissueDate("2025-05-20");
        account.setCurrentCycleCredit(new BigDecimal("500.00"));
        account.setCurrentCycleDebit(new BigDecimal("250.00"));
        account.setAddressZip("27604     ");
        account.setGroupId(groupId);
        return account;
    }

    /**
     * Builds one customer that clears every edit.
     *
     * <p>The names and the address come from row one of {@code app/data/ASCII/custdata.txt}. Three
     * values differ from that row, and the row itself fails three edits. The postcode is
     * {@code 27604} where the row holds {@code 12546}, and {@code app/cpy/CSLKPCDY.cpy:L1071-L1073}
     * lists {@code NC} with {@code 27} and not with {@code 12}. The second telephone number carries
     * area code {@code 919} where the row holds {@code 373}, which the 980 codes at
     * {@code app/cpy/CSLKPCDY.cpy:L24-L1011} do not list. The credit score is {@code 688} where the
     * row holds {@code 274}, below the 300 the range edit at
     * {@code app/cbl/COACTUPC.cbl:L2515} accepts.
     *
     * @param keys the keys the customer carries
     * @return the customer
     */
    private static CustomerEntity customer(Keys keys) {
        return customer(keys, "0053581756");
    }

    /**
     * Copies one customer, leaving the Social Security number absent.
     *
     * <p>{@code CustomerEntity} guards that column and refuses an absent number, so the state a
     * caller reaches by submitting one part of three cannot be built by clearing the field. It is
     * built by never setting it, which is what {@code api/AccountController} does for an incomplete
     * triple.
     *
     * @param source the customer to copy
     * @return a copy carrying every value but the Social Security number
     */
    private static CustomerEntity withoutSocialSecurityNumber(CustomerEntity source) {
        CustomerEntity copy = new CustomerEntity();
        copy.setCustomerId(source.getCustomerId());
        copy.setFirstName(source.getFirstName());
        copy.setMiddleName(source.getMiddleName());
        copy.setLastName(source.getLastName());
        copy.setAddressLine1(source.getAddressLine1());
        copy.setAddressLine2(source.getAddressLine2());
        copy.setAddressCity(source.getAddressCity());
        copy.setAddressStateCode(source.getAddressStateCode());
        copy.setAddressCountryCode(source.getAddressCountryCode());
        copy.setAddressZip(source.getAddressZip());
        copy.setPhoneNumber1(source.getPhoneNumber1());
        copy.setPhoneNumber2(source.getPhoneNumber2());
        copy.setGovernmentIssuedId(source.getGovernmentIssuedId());
        copy.setDateOfBirth(source.getDateOfBirth());
        copy.setEftAccountId(source.getEftAccountId());
        copy.setPrimaryCardHolderIndicator(source.getPrimaryCardHolderIndicator());
        copy.setFicoCreditScore(source.getFicoCreditScore());
        return copy;
    }

    /**
     * Builds one customer carrying the electronic funds transfer identifier supplied.
     *
     * @param keys          the keys the customer carries
     * @param eftAccountId  the value edit 23 at {@code app/cbl/COACTUPC.cbl:L1648} reads
     * @return the customer
     */
    private static CustomerEntity customer(Keys keys, String eftAccountId) {
        CustomerEntity customer = new CustomerEntity();
        customer.setCustomerId(keys.customerId());
        customer.setFirstName("Immanuel");
        customer.setMiddleName("Madeline");
        customer.setLastName("Kessler");
        customer.setAddressLine1("618 Deshaun Route");
        customer.setAddressLine2("Apt. 802");
        customer.setAddressCity("Altenwerthshire");
        customer.setAddressStateCode("NC");
        customer.setAddressCountryCode("USA");
        customer.setAddressZip("27604     ");
        customer.setPhoneNumber1("(908)119-8310  ");
        customer.setPhoneNumber2("(919)693-8684  ");
        customer.setSocialSecurityNumber("020973888");
        customer.setGovernmentIssuedId("00000000000049368437");
        customer.setDateOfBirth("1961-06-08");
        customer.setEftAccountId(eftAccountId);
        customer.setPrimaryCardHolderIndicator("Y");
        customer.setFicoCreditScore(new BigDecimal("688"));
        return customer;
    }
}

package com.carddemo.ledger.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.TestIdentityPasswords;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.TransactionCategoryBalanceId;
import com.carddemo.ledger.outbox.OutboxRelay;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the category-balance upsert of {@link TransactionCategoryBalanceRepository} against a real
 * database.
 *
 * <p>The statement is native, so neither of the two start-up checks reads it. Spring Data derives and
 * checks a method-name query and Hibernate validates every mapped column against the migrated schema,
 * while a native statement is passed to the driver as text. Its {@code ON CONFLICT} target, its
 * excluded-row reference and the {@code MOD} that keeps the sum inside the column are invisible to
 * both, so a defect in any of them surfaces on the first posting a running service applies.
 *
 * <p>Three properties are asserted. An absent key is inserted at the amount itself, which is
 * {@code 2700-A-CREATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl:L503-L510}. A present key takes
 * the amount on top of its stored balance, which is {@code 2700-B-UPDATE-TCATBAL-REC} at
 * {@code :L526-L528}. And a sum wider than the nine integer digits
 * {@code TRAN-CAT-BAL PIC S9(09)V99} holds at {@code app/cpy/CVTRA01Y.cpy:L9} keeps its low-order
 * nine digits and its sign, which is what an {@code ADD} with no {@code ON SIZE ERROR} phrase does.
 *
 * <p>The third property is the one runtime testing found missing. Two approved amounts inside
 * {@code DALYTRAN-AMT PIC S9(09)V99} summed past the column, {@code NUMERIC(11,2)} answered SQLSTATE
 * 22003, the consumer transaction rolled back and the approved authorization reached the dead-letter
 * topic. {@code src/main/resources/db/migration/V7__category_balance_ceiling.sql} records the store
 * this class reads back.
 *
 * <p>Flyway owns the schema, so the migrations under test are the shipped ones. No broker is reached:
 * a stand-in replaces the producer template and the relay.
 */
@Testcontainers
@SpringBootTest(properties = {
    // The two listeners of this service must not retry an absent broker.
    "spring.kafka.listener.auto-startup=false",
    "TOPIC_DEAD_LETTER_SUFFIX=.DLT",
    // One of the four credentials application.yml leaves without a default. The value below is a
    // generated fake this repository states nowhere else, and config/SecurityConfig refuses a blank
    // or published one at start-up. The three identity hashes arrive from card-platform/pom.xml,
    // because a password that is not adaptively encoded is refused.
    "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-the-category-balance-test",
    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
    "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
})
@DisplayName("The category-balance upsert of TransactionCategoryBalanceRepository, on a real database")
class TransactionCategoryBalanceRepositoryTest {

    /** Login the container creates, and the schema owner Flyway migrates under. */
    private static final String DATABASE_LOGIN = "carddemo_ledger_svc";

    /** A generated value for this run, matching no provider credential shape. */
    private static final String DATABASE_SECRET = "a-generated-database-value-for-the-category";

    /** The schema Flyway migrates into, which is also the one the shipped URL selects. */
    private static final String SCHEMA = "ledger_service";

    /** An account no row of {@code V2__seed.sql} carries, so its key starts absent. */
    private static final String NEW_ACCOUNT = "00000000099";

    /** A transaction type code, from {@code TRANCAT-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CODE = "01";

    /** A transaction category code, from {@code TRANCAT-CD PIC 9(04)}. */
    private static final String CATEGORY_CODE = "0001";

    /** The one row either arm of the statement writes. */
    private static final int ONE_ROW = 1;

    /** The image tag the compose stack pins. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("carddemo_ledger")
            .withUsername(DATABASE_LOGIN)
            .withPassword(DATABASE_SECRET);

    static {
        POSTGRES.start();
    }

    /** Replaces the producer template, so the context starts with no broker reachable. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> producerTemplate;

    /** Prevents the scheduled publisher from running beside these assertions. */
    @MockitoBean
    private OutboxRelay relay;

    private TransactionCategoryBalanceRepository categoryBalances;
    private JdbcTemplate database;

    /**
     * Runs one modifying statement in its own committed transaction.
     *
     * <p>The statement carries {@code @Modifying} and no transaction of its own, so it is called
     * inside a transaction in production and must be here too. Committing rather than rolling back
     * also means every read below opens a fresh persistence context: the statement is native, so a
     * session that had already loaded the row would not learn of the change from it.
     */
    private TransactionTemplate boundary;

    /**
     * Points the context at the container, selecting the schema the shipped URL selects.
     *
     * <p>{@code currentSchema} is not decoration. {@code application.yml} carries it on
     * {@code spring.datasource.url}, and the statement under test is native, so it is the only thing
     * that resolves its unqualified table name.
     *
     * @param registry the property registry this class adds the container coordinates to
     */
    @DynamicPropertySource
    static void containerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Resolves the store and clears the one key these tests write.
     *
     * @param context the started application context
     */
    @BeforeEach
    void resolveBeansAndClearKey(ApplicationContext context) {
        categoryBalances = context.getBean(TransactionCategoryBalanceRepository.class);
        database = context.getBean(JdbcTemplate.class);
        boundary = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        database.update("""
                DELETE FROM ledger_service.transaction_category_balance
                 WHERE account_id = ?
                """, NEW_ACCOUNT);
    }

    /**
     * Adds one amount through the statement under test.
     *
     * @param amount the signed amount to add
     * @return the row count the statement answered
     */
    private int add(String amount) {
        return boundary.execute(status -> categoryBalances.addToCategoryBalance(NEW_ACCOUNT,
                TYPE_CODE, CATEGORY_CODE, new BigDecimal(amount)));
    }

    /**
     * Reads the stored balance for the key these tests write.
     *
     * @return the balance the column holds
     */
    private BigDecimal storedBalance() {
        TransactionCategoryBalanceEntity row = categoryBalances
                .findById(new TransactionCategoryBalanceId(NEW_ACCOUNT, TYPE_CODE, CATEGORY_CODE))
                .orElseThrow();
        return row.getCategoryBalance();
    }

    /** The largest value the field holds, from its Picture clause. */
    private static BigDecimal fieldMaximum() {
        int integerDigits = PicClause.TRAN_CAT_BAL_PRECISION - PicClause.TRAN_CAT_BAL_SCALE;
        return BigDecimal.TEN.pow(integerDigits)
                .subtract(BigDecimal.ONE.movePointLeft(PicClause.TRAN_CAT_BAL_SCALE))
                .setScale(PicClause.TRAN_CAT_BAL_SCALE, java.math.RoundingMode.DOWN);
    }

    @Nested
    @DisplayName("The two arms of 2700-UPDATE-TCATBAL")
    class BothArms {

        @Test
        @DisplayName("an absent key is inserted at the amount itself (:L503-L510)")
        void anAbsentKeyIsInsertedAtTheAmount() {
            int applied = add("504.77");

            assertAll(
                    () -> assertEquals(ONE_ROW, applied, "the insert arm reports one row"),
                    () -> assertEquals(new BigDecimal("504.77"), storedBalance(),
                            "INITIALIZE then ADD makes the opening balance the amount"));
        }

        @Test
        @DisplayName("a present key takes the amount on top of its stored balance (:L527)")
        void aPresentKeyTakesTheAmountOnTop() {
            add("504.77");

            int applied = add("-4.77");

            assertAll(
                    () -> assertEquals(ONE_ROW, applied, "the conflict arm reports one row"),
                    () -> assertEquals(new BigDecimal("500.00"), storedBalance(),
                            "a negative amount lowers the balance, as a refund does"));
        }

        @Test
        @DisplayName("the field maximum is stored whole, so nothing below the ceiling is lost")
        void theFieldMaximumIsStoredWhole() {
            int applied = add(fieldMaximum().toPlainString());

            assertAll(
                    () -> assertEquals(ONE_ROW, applied),
                    () -> assertEquals(fieldMaximum(), storedBalance(),
                            "nine integer digits and two fractional digits reach the column"));
        }
    }

    @Nested
    @DisplayName("The picture-field ceiling at app/cbl/CBTRN02C.cbl:L508 and :L527")
    class PictureFieldCeiling {

        @Test
        @DisplayName("a sum past nine integer digits is stored rather than refused")
        void aSumPastNineIntegerDigitsIsStored() {
            add("0.01");
            BigDecimal sum = fieldMaximum().add(new BigDecimal("0.01"));

            int applied = add(fieldMaximum().toPlainString());

            assertAll(
                    () -> assertEquals(ONE_ROW, applied,
                            "the store completes, where SQLSTATE 22003 would have rolled the "
                                    + "posting back and dead-lettered an approved authorization"),
                    () -> assertEquals(CobolDecimal.truncateToPictureField(sum,
                                    PicClause.TRAN_CAT_BAL_PRECISION,
                                    PicClause.TRAN_CAT_BAL_SCALE),
                            storedBalance(),
                            "the column holds what the COBOL field holds, and the two agree"),
                    () -> assertEquals(new BigDecimal("0.00"), storedBalance(),
                            "the low-order nine integer digits of 1000000000.00 are all zero"));
        }

        @Test
        @DisplayName("a negative sum past the ceiling keeps its sign and its remainder")
        void aNegativeSumPastTheCeilingKeepsItsSign() {
            add("-1.23");
            BigDecimal sum = fieldMaximum().negate().subtract(new BigDecimal("1.23"));

            add(fieldMaximum().negate().toPlainString());

            assertAll(
                    () -> assertEquals(CobolDecimal.truncateToPictureField(sum,
                                    PicClause.TRAN_CAT_BAL_PRECISION,
                                    PicClause.TRAN_CAT_BAL_SCALE),
                            storedBalance(),
                            "the remainder carries the sign of the sum, as the COBOL store does"),
                    () -> assertTrue(storedBalance().compareTo(BigDecimal.ZERO) < 0,
                            "a debit stays a debit"));
        }

        @Test
        @DisplayName("the divisor in the statement is the width the Picture clause declares")
        void theDivisorIsTheDeclaredWidth() throws NoSuchMethodException {
            Method upsert = TransactionCategoryBalanceRepository.class.getMethod(
                    "addToCategoryBalance", String.class, String.class, String.class,
                    BigDecimal.class);
            String statement = upsert.getAnnotation(Query.class).value();
            String divisor = BigDecimal.TEN
                    .pow(PicClause.TRAN_CAT_BAL_PRECISION - PicClause.TRAN_CAT_BAL_SCALE)
                    .toPlainString();

            assertAll(
                    () -> assertTrue(statement.contains("MOD("),
                            "the conflict arm stores through MOD: " + statement),
                    () -> assertTrue(statement.contains(divisor),
                            "the divisor has to be " + divisor + ": " + statement));
        }
    }
}

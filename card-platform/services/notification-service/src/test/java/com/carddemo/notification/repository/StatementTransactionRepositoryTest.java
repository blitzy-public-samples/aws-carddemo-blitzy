package com.carddemo.notification.repository;

import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves the ordered finder of {@link StatementTransactionRepository} against a live database, and
 * proves a second save under one composite key updates the stored row.
 *
 * <p>The composite key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. Its two
 * parts are {@code TRNX-CARD-NUM PIC X(16)} at L22 then {@code TRNX-ID PIC X(16)} at L23. The cluster
 * definition declares the same 32 bytes at offset zero as {@code KEYS(32 0)} at
 * {@code app/jcl/CREASTMT.JCL:L30}, a Job Control Language (JCL) member. The file description
 * {@code 05 FD-TRNXS-ID.} at {@code app/cbl/CBSTM03B.CBL:L60-L62} carries those two 16-byte parts
 * above a 318-byte remainder. In the target key the card half is a card token at 64 lower-case
 * hexadecimal characters, and the masked display value sits in a column of its own.
 *
 * <p>One derived finder stands in for two source mechanisms. The sort step
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} imposed card order
 * then transaction order, and both comparisons are character comparisons. The break-on-change
 * grouping at {@code app/cbl/CBSTM03A.CBL:L819-L825}, inside {@code 8500-READTRNX-READ} at
 * {@code app/cbl/CBSTM03A.CBL:L818}, then read one card's runs from that sorted input. The
 * {@code OrderBy} clause of the finder supplies the same order from the primary key.
 *
 * <p>The one-interface-per-aggregate shape descends from {@code app/cbl/CBSTM03B.CBL}. Its parameter
 * area {@code 01 LK-M03B-AREA.} at {@code app/cbl/CBSTM03B.CBL:L100-L112} carries an operation code,
 * a key and a key length, and the dispatch at {@code app/cbl/CBSTM03B.CBL:L118-L128} routes all four
 * datasets through it. A keyed read became a find, a sequential read became an ordered list, a write
 * became an insert and a rewrite became an update.
 *
 * <p>Two source constructs get no assertion here. The statement tables
 * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} at {@code app/cbl/CBSTM03A.CBL:L226} and
 * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at {@code app/cbl/CBSTM03A.CBL:L228} held ten rows per card.
 * No test below caps a result. The output step
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at {@code app/jcl/CREASTMT.JCL:L54} copied 50
 * bytes across the 52 bytes the two timestamps occupy, delivering 24 of the 26 characters of
 * {@code TRNX-PROC-TS}. Every row below keeps all 26.
 *
 * <p>Each version this class depends on is pinned by Agent Action Plan section 0.5.1:
 * {@code postgres:18.4}, Testcontainers 2.0.5, JUnit Jupiter 6.0.3 and Java 25.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Source-to-target mapping,
 * including the two constructs named above: {@code card-platform/docs/traceability-matrix.md}.
 * Flagged source findings: {@code card-platform/docs/business-rule-flags.md}.
 */
@DisplayName("The statement read model's ordered finder and its keyed upsert")
class StatementTransactionRepositoryTest extends NotificationRepositoryTestSupport {

    /** Width of the card token the key column holds, in lower-case hexadecimal characters. */
    private static final int CARD_TOKEN_WIDTH = 64;

    /** Width of {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23}. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** The card whose rows every ordering assertion reads. */
    private static final String FIRST_CARD_TOKEN = cardToken("a1");

    /** A second card, so one finder call must leave its rows behind. */
    private static final String SECOND_CARD_TOKEN = cardToken("b2");

    /** A card the read model holds no row for. */
    private static final String ABSENT_CARD_TOKEN = cardToken("c3");

    /** Display value of every row: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "4321";

    /** Ceiling wide enough that no assertion below is served a short list. */
    private static final Limit WHOLE_CARD = Limit.of(500);

    /**
     * Origin timestamp text: a space at position 11, colons, then six fractional digits.
     * Source: {@code TRNX-ORIG-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L34}.
     */
    private static final String ORIGIN_TIMESTAMP = "2024-03-17 14:22:31.123456";

    /**
     * Processing timestamp text: a dash at position 11, dots, two hundredths digits, then four zero
     * characters. Source: {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}.
     */
    private static final String PROCESSING_TIMESTAMP = "2024-03-17-14.22.31.120000";

    /** Width both timestamp columns hold. */
    private static final int TIMESTAMP_WIDTH = 26;

    @Autowired
    private StatementTransactionRepository statementTransactions;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Builds one row that satisfies all fourteen columns of {@code statement_transaction}, every one
     * of them {@code NOT NULL}.
     *
     * @param token the card token, the first key part
     * @param transactionTail the digits closing the sixteen-character transaction identifier
     * @param amount the transaction amount at scale two, negative for a refund
     * @param description the free text describing the transaction
     * @return a row ready to save
     */
    private static StatementTransactionEntity row(String token, String transactionTail,
            BigDecimal amount, String description) {
        return new StatementTransactionEntity(
                new StatementTransactionId(token, transactionId(transactionTail)),
                MASKED_CARD_NUMBER,
                "DB",
                "1",
                "POS",
                description,
                amount,
                "49936",
                "Riverside Coffee House",
                "Portland",
                "97204",
                ORIGIN_TIMESTAMP,
                PROCESSING_TIMESTAMP);
    }

    /**
     * Builds one row carrying a settled amount and a fixed description.
     *
     * @param token the card token, the first key part
     * @param transactionTail the digits closing the sixteen-character transaction identifier
     * @return a row ready to save
     */
    private static StatementTransactionEntity row(String token, String transactionTail) {
        return row(token, transactionTail, new BigDecimal("194.00"), "Coffee and a pastry");
    }

    /**
     * Left-pads a hexadecimal tail to the width the card token column holds.
     *
     * @param tail lower-case hexadecimal characters closing the token
     * @return the token at {@link #CARD_TOKEN_WIDTH} characters
     */
    private static String cardToken(String tail) {
        return "0".repeat(CARD_TOKEN_WIDTH - tail.length()) + tail;
    }

    /**
     * Left-pads a digit tail to the width {@code TRNX-ID PIC X(16)} declares, so character order and
     * numeric order agree under the character comparison of
     * {@code app/jcl/CREASTMT.JCL:L53}.
     *
     * @param tail digits closing the identifier
     * @return the identifier at {@link #TRANSACTION_ID_WIDTH} characters
     */
    private static String transactionId(String tail) {
        return "0".repeat(TRANSACTION_ID_WIDTH - tail.length()) + tail;
    }

    /**
     * Reads the transaction identifier out of each row, in the order the finder returned them.
     *
     * @param rows what the finder returned
     * @return the identifiers in that order
     */
    private static List<String> transactionIdsOf(List<StatementTransactionEntity> rows) {
        List<String> identifiers = new ArrayList<>(rows.size());
        for (StatementTransactionEntity found : rows) {
            identifiers.add(found.getId().getTransactionId());
        }
        return identifiers;
    }

    /**
     * The finder returns one card's rows in ascending transaction-identifier order.
     *
     * <p>The three rows arrive in an order no sort would produce, so the returned sequence comes from
     * the {@code OrderBy} clause and not from the insertion order. That clause carries the order of
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}.
     */
    @Test
    @DisplayName("One card's rows come back ordered by transaction identifier ascending")
    void theFinderReturnsOneCardsRowsInAscendingTransactionOrder() {
        statementTransactions.save(row(FIRST_CARD_TOKEN, "30"));
        statementTransactions.save(row(FIRST_CARD_TOKEN, "10"));
        statementTransactions.save(row(FIRST_CARD_TOKEN, "20"));
        entityManager.flush();
        entityManager.clear();

        List<StatementTransactionEntity> found = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(FIRST_CARD_TOKEN, WHOLE_CARD);

        assertThat(transactionIdsOf(found))
                .containsExactly(transactionId("10"), transactionId("20"), transactionId("30"));
    }

    /**
     * The finder returns more than ten rows for one card, all of them ordered.
     *
     * <p>Twelve rows arrive in descending order and all twelve come back ascending. The statement
     * tables at {@code app/cbl/CBSTM03A.CBL:L226} and {@code app/cbl/CBSTM03A.CBL:L228} held ten rows
     * per card. This result holds twelve.
     */
    @Test
    @DisplayName("A card holding twelve rows returns all twelve, ordered")
    void theFinderReturnsMoreThanTenRowsForOneCard() {
        List<String> expected = new ArrayList<>();
        for (int position = 12; position >= 1; position--) {
            statementTransactions.save(row(FIRST_CARD_TOKEN, String.valueOf(position)));
        }
        for (int position = 1; position <= 12; position++) {
            expected.add(transactionId(String.valueOf(position)));
        }
        entityManager.flush();
        entityManager.clear();

        List<StatementTransactionEntity> found = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(FIRST_CARD_TOKEN, WHOLE_CARD);

        assertThat(transactionIdsOf(found)).containsExactlyElementsOf(expected);
    }

    /** The finder returns the queried card's rows and leaves every other card's rows behind. */
    @Test
    @DisplayName("A second card's rows stay out of the first card's result")
    void theFinderExcludesAnotherCardsRows() {
        statementTransactions.save(row(FIRST_CARD_TOKEN, "10"));
        statementTransactions.save(row(SECOND_CARD_TOKEN, "20"));
        statementTransactions.save(row(SECOND_CARD_TOKEN, "30"));
        entityManager.flush();
        entityManager.clear();

        List<StatementTransactionEntity> found = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(FIRST_CARD_TOKEN, WHOLE_CARD);

        assertThat(found).extracting(entity -> entity.getId().getCardToken())
                .containsOnly(FIRST_CARD_TOKEN);
        assertThat(transactionIdsOf(found)).containsExactly(transactionId("10"))
                .doesNotContain(transactionId("20"), transactionId("30"));
    }

    /**
     * The finder answers a card with no rows with an empty list and raises nothing.
     *
     * <p>The return type is a list, so a caller reads a size of zero and never a null or an absent
     * value.
     */
    @Test
    @DisplayName("A card with no rows returns an empty list and raises nothing")
    void theFinderReturnsAnEmptyListForACardWithNoRows() {
        statementTransactions.save(row(FIRST_CARD_TOKEN, "10"));
        entityManager.flush();
        entityManager.clear();

        List<StatementTransactionEntity> found = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(ABSENT_CARD_TOKEN, WHOLE_CARD);

        assertThat(found).isNotNull().isEmpty();
        assertThatCode(() -> statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(ABSENT_CARD_TOKEN, WHOLE_CARD))
                .doesNotThrowAnyException();
    }

    /**
     * Saving a second row under one composite key updates the stored row and adds none.
     *
     * <p>The clear between the two saves drops the managed instance, so the second read reaches the
     * database. This update path is what a redelivered {@code TransactionPosted} event takes, and the
     * duplicate-key abend at {@code app/cbl/CBTRN02C.cbl:L562-L579} is what it closes.
     */
    @Test
    @DisplayName("A second save under one key updates the row and leaves the count at one")
    void savingAnExistingKeyUpdatesTheRowAndAddsNone() {
        statementTransactions.save(
                row(FIRST_CARD_TOKEN, "10", new BigDecimal("194.00"), "First delivery"));
        entityManager.flush();
        entityManager.clear();

        statementTransactions.save(
                row(FIRST_CARD_TOKEN, "10", new BigDecimal("212.75"), "Second delivery"));
        entityManager.flush();
        entityManager.clear();

        assertThat(statementTransactions.count()).isEqualTo(1L);
        List<StatementTransactionEntity> found = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(FIRST_CARD_TOKEN, WHOLE_CARD);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getDescription().strip()).isEqualTo("Second delivery");
        assertThat(found.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("212.75"));
    }

    /**
     * The interface declares the ordered finder, the retention delete, and nothing else.
     *
     * <p>The finder carries no annotation of any kind, so Spring Data derives it from its name alone.
     * Its two parameter types are the card token and a row ceiling. The interface declares no nested
     * type, so it carries no projection.
     */
    @Test
    @DisplayName("The interface declares the finder, the retention delete, and no other member")
    void theInterfaceDeclaresTheFinderTheRetentionDeleteAndNothingElse()
            throws NoSuchMethodException {
        assertThat(StatementTransactionRepository.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("findByIdCardTokenOrderByIdTransactionIdAsc",
                        "deleteProcessedBefore");
        assertThat(StatementTransactionRepository.class.getDeclaredClasses()).isEmpty();

        Method finder = StatementTransactionRepository.class.getDeclaredMethod(
                "findByIdCardTokenOrderByIdTransactionIdAsc", String.class, Limit.class);
        assertThat(finder.getParameterTypes()).containsExactly(String.class, Limit.class);
        assertThat(finder.getReturnType()).isEqualTo(List.class);
        assertThat(finder.getAnnotations()).isEmpty();
        assertThat(((ParameterizedType) finder.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(StatementTransactionEntity.class);
    }

    /**
     * The identifier type of the repository is the nested embeddable, not a surrogate.
     *
     * <p>The single generic supertype is the list-returning Spring Data create-read-update-delete
     * interface, parameterised with the entity then its nested key class.
     */
    @Test
    @DisplayName("The repository is parameterised with the entity and its nested key class")
    void theRepositoryIsParameterisedWithTheNestedEmbeddableIdentifier() {
        Type[] supertypes = StatementTransactionRepository.class.getGenericInterfaces();

        assertThat(supertypes).hasSize(1);
        ParameterizedType crud = (ParameterizedType) supertypes[0];
        assertThat(crud.getRawType()).isEqualTo(ListCrudRepository.class);
        assertThat(crud.getActualTypeArguments())
                .containsExactly(StatementTransactionEntity.class, StatementTransactionId.class);
    }

    /**
     * The embedded key field is named {@code id}, which is the prefix both halves of the finder name
     * resolve through.
     *
     * <p>The name reads as a find on {@code id.cardToken} ordered by {@code id.transactionId}. A field
     * under another name leaves both paths unresolved and stops the context from starting, so the
     * injected repository and the callable finder are themselves the proof.
     */
    @Test
    @DisplayName("The embedded key field named id binds both halves of the derived finder")
    void theEmbeddedKeyFieldNamedIdBindsTheDerivedFinder() throws NoSuchFieldException {
        Field key = StatementTransactionEntity.class.getDeclaredField("id");

        assertThat(key.getType()).isEqualTo(StatementTransactionId.class);
        assertThat(statementTransactions).isNotNull();
        assertThatCode(() -> statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(FIRST_CARD_TOKEN, WHOLE_CARD))
                .doesNotThrowAnyException();
    }

    /**
     * A refund keeps its negative sign, and both timestamps keep all twenty-six characters.
     *
     * <p>The amount column holds nine integer digits and two fractional digits, matching
     * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}. The comparison reads the
     * value and not the scale. Comparisons on the shorter character columns strip the padding those
     * columns return.
     */
    @Test
    @DisplayName("A refund keeps its sign and both timestamps keep twenty-six characters")
    void theFinderKeepsARefundSignAndBothTwentySixCharacterTimestamps() {
        statementTransactions.save(row(FIRST_CARD_TOKEN, "40", new BigDecimal("-45.50"),
                "Refund of a duplicate charge"));
        entityManager.flush();
        entityManager.clear();

        List<StatementTransactionEntity> found = statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(FIRST_CARD_TOKEN, WHOLE_CARD);

        assertThat(found).hasSize(1);
        StatementTransactionEntity refund = found.get(0);
        assertThat(refund.getAmount()).isEqualByComparingTo(new BigDecimal("-45.5")).isNegative();
        assertThat(refund.getOriginTimestamp())
                .hasSize(TIMESTAMP_WIDTH).isEqualTo(ORIGIN_TIMESTAMP);
        assertThat(refund.getProcessingTimestamp())
                .hasSize(TIMESTAMP_WIDTH).isEqualTo(PROCESSING_TIMESTAMP);
        assertThat(refund.getMaskedCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
        assertThat(refund.getCategoryCode()).isEqualTo("0001");
        assertThat(refund.getMerchantId()).isEqualTo("000049936");
        assertThat(refund.getTypeCode()).isEqualTo("DB");
        assertThat(refund.getSource().strip()).isEqualTo("POS");
        assertThat(refund.getMerchantName().strip()).isEqualTo("Riverside Coffee House");
        assertThat(refund.getMerchantCity().strip()).isEqualTo("Portland");
        assertThat(refund.getMerchantZip().strip()).isEqualTo("97204");
        assertThat(refund.getDescription().strip()).isEqualTo("Refund of a duplicate charge");
    }
}

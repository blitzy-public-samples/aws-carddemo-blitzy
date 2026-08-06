package com.carddemo.notification.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.notification.entity.NotificationLogEntity;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.repository.Repository;

/**
 * Proves a delivery attempt reaches {@code notification_log}, and proves
 * {@link NotificationLogRepository} declares no read path over the table.
 *
 * <p>Four tests cover two behaviours. Three write a row and flush it, which makes PostgreSQL apply
 * all six {@code NOT NULL} columns and both check constraints
 * {@code src/main/resources/db/migration/V1__schema.sql} declares. The fourth reads the interface by
 * reflection and pins its declared surface.
 *
 * <p>{@link NotificationRepositoryTestSupport} supplies the database carrying that migrated schema.
 *
 * <p>ADDITIVE, with no Common Business Oriented Language (COBOL) ancestor. No CardDemo program
 * records a delivery attempt. {@code app/cbl/CBSTM03A.CBL:L488-L502} writes fifteen statement lines
 * to a sequential dataset and records nothing about the write.
 *
 * <p>The contract under test descends from {@code app/cbl/CBSTM03B.CBL}. The parameter area
 * {@code 01 LK-M03B-AREA.} at {@code app/cbl/CBSTM03B.CBL:L100} carries an operation code at
 * {@code app/cbl/CBSTM03B.CBL:L102}, and the write code {@code 88 M03B-WRITE VALUE 'W'} at
 * {@code app/cbl/CBSTM03B.CBL:L107} is the one operation these tests exercise.
 *
 * <p>Every card value below is masked. A stored card number carries twelve mask characters then
 * four digits, at the width {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}
 * declares. No fixture here holds an unmasked card number, and no field, value or message here
 * names a card verification code.
 *
 * <p>The masked and the unmasked card-number form both occupy sixteen characters:
 * {@code card-platform/docs/suggested-next-tasks.md}.
 */
@DisplayName("NotificationLogRepository, the delivery-attempt log")
final class NotificationLogRepositoryTest extends NotificationRepositoryTestSupport {

    /**
     * The card three attempts below name, held as its token.
     *
     * <p>Lower-case hexadecimal characters filling the width
     * {@link NotificationLogEntity#CARD_TOKEN_LENGTH} fixes, matching the shape the check
     * constraint {@code ck_notification_log_card_token} holds.</p>
     */
    private static final String CARD_TOKEN = "4d5f8a1b2c3e6079".repeat(4);

    /**
     * The card the fourth attempt names, distinct from {@link #CARD_TOKEN}.
     *
     * <p>The same width and the same shape, over a different card.</p>
     */
    private static final String UNMATCHED_CARD_TOKEN = "7b0e3a6c9d215f84".repeat(4);

    /**
     * The display card number every attempt below carries: twelve mask characters then the last
     * four digits.
     *
     * <p>Sixteen characters in all, the width {@link NotificationLogEntity#CARD_NUMBER_LENGTH}
     * fixes, matching the shape the check constraint {@code ck_notification_log_card_number}
     * holds. Every card sharing those four digits masks to one value, and the column identifies no
     * single card.</p>
     */
    private static final String MASKED_CARD_NUMBER = "************4321";

    /**
     * The transaction three attempts below alert on.
     *
     * <p>{@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} is alphanumeric, and the
     * value fills the width {@link NotificationLogEntity#TRANSACTION_ID_LENGTH} fixes.</p>
     */
    private static final String TRANSACTION_ID = "ALERT00000000001";

    /**
     * The transaction the fourth attempt alerts on, which the read model holds no row for.
     *
     * <p>The same width, over a transaction no seeded or written statement row names.</p>
     */
    private static final String UNMATCHED_TRANSACTION_ID = "NOSTATEMENTROW01";

    /** The rendered format one attempt carried, at ten of the twenty characters the column holds. */
    private static final String TEXT_CHANNEL = "PLAIN_TEXT";

    /** The rendered format a second attempt carried, at four of the same twenty characters. */
    private static final String MARKUP_CHANNEL = "HTML";

    /**
     * Name openings Spring Data derives a read from.
     *
     * <p>{@code count} is absent from the list: {@code count()} accepts no argument and names no
     * column.</p>
     */
    private static final List<String> READ_PREFIXES =
            List.of("find", "get", "read", "query", "search", "stream", "exists");

    /** The interface under test. */
    @Autowired
    private NotificationLogRepository attempts;

    /** Sends a pending write to the database, and empties the persistence context before a read. */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Inserts one attempt carrying all six values, and reads every one of them back unchanged.
     *
     * <p>The flush carries the row to PostgreSQL inside the transaction the slice rolls back. The
     * clear then empties the persistence context, so the read loads the stored row and not the
     * written instance. Both fixed-width character values fill their declared widths, and
     * PostgreSQL pads neither.</p>
     */
    @Test
    @DisplayName("a fully populated attempt inserts and reads back unchanged")
    void aFullyPopulatedAttemptInserts() {
        UUID id = UUID.randomUUID();
        NotificationLogEntity written = attempt(id, TEXT_CHANNEL);

        attempts.save(written);
        entityManager.flush();
        entityManager.clear();

        NotificationLogEntity stored = entityManager.find(NotificationLogEntity.class, id);
        assertNotNull(stored, "the row is present under the identifier it was written under");
        assertAll("the six values of the stored attempt",
                () -> assertEquals(1L, attempts.count(), "the table holds the one row written"),
                () -> assertEquals(id, stored.getId(), "the identifier reads back"),
                () -> assertEquals(CARD_TOKEN, stored.getCardToken(),
                        "the card token reads back character for character"),
                () -> assertEquals(MASKED_CARD_NUMBER, stored.getMaskedCardNumber(),
                        "the masked card number reads back with no padding"),
                () -> assertEquals(TRANSACTION_ID, stored.getTransactionId(),
                        "the transaction identifier reads back with no padding"),
                () -> assertEquals(TEXT_CHANNEL, stored.getChannel(),
                        "the channel reads back at the width it was written at"),
                () -> assertEquals(written.getAttemptedAt(), stored.getAttemptedAt(),
                        "the attempt instant reads back to the microsecond"));
    }

    /**
     * Inserts two attempts on one card and one transaction, and finds both rows present.
     *
     * <p>The two rows share their card token, their masked card number and their transaction
     * identifier, and they carry different identifiers. The identifier alone carries
     * {@code pk_notification_log}, which is the one uniqueness constraint on the table, and a
     * repeated delivery lands beside the first attempt.</p>
     */
    @Test
    @DisplayName("two attempts on one card and one transaction both insert")
    void twoAttemptsOnOneCardAndTransactionBothInsert() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        attempts.save(attempt(firstId, TEXT_CHANNEL));
        attempts.save(attempt(secondId, MARKUP_CHANNEL));
        entityManager.flush();

        assertAll("two attempts on one card and one transaction",
                () -> assertEquals(2L, attempts.count(), "both rows are present"),
                () -> assertNotNull(entityManager.find(NotificationLogEntity.class, firstId),
                        "the first attempt is present"),
                () -> assertNotNull(entityManager.find(NotificationLogEntity.class, secondId),
                        "the second attempt is present"));
    }

    /**
     * Inserts an attempt naming a card and a transaction the read model holds no row for.
     *
     * <p>The migration declares no foreign key from {@code notification_log} to
     * {@code statement_transaction}, and the insert lands with the read model empty. Nothing orders
     * the two writes.</p>
     */
    @Test
    @DisplayName("an attempt inserts with no statement row to match")
    void anAttemptInsertsWithNoStatementRowToMatch() {
        assertEquals(0L, attempts.count(), "the attempt log opens empty");
        UUID id = UUID.randomUUID();

        attempts.save(attempt(id, UNMATCHED_CARD_TOKEN, UNMATCHED_TRANSACTION_ID, TEXT_CHANNEL));
        entityManager.flush();

        assertAll("the unmatched attempt",
                () -> assertEquals(1L, attempts.count(), "the row is present"),
                () -> assertNotNull(entityManager.find(NotificationLogEntity.class, id),
                        "the row is present under the identifier it was written under"));
    }

    /**
     * Pins the declared surface of {@link NotificationLogRepository}, which carries no read path.
     *
     * <p>The interface extends the Spring Data marker repository, which declares no method, so the
     * three methods it declares itself are the whole surface. Two of them write and
     * {@code count()} returns a total.</p>
     *
     * <p>None accepts a paging, sorting, example or criteria argument. None opens with a name
     * Spring Data derives a read from, and none returns a collection or a projection. The interface
     * declares no nested type, and adding a finder breaks one of the assertions below.</p>
     */
    @Test
    @DisplayName("the interface declares no read path over the attempt log")
    void theInterfaceDeclaresNoReadPath() {
        Method[] declared = NotificationLogRepository.class.getDeclaredMethods();
        Set<String> names = Arrays.stream(declared)
                .map(Method::getName)
                .collect(Collectors.toSet());
        Set<Class<?>> argumentTypes = Arrays.stream(declared)
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .collect(Collectors.toSet());
        Set<Class<?>> returnTypes = Arrays.stream(declared)
                .map(Method::getReturnType)
                .collect(Collectors.toSet());

        Type[] extended = NotificationLogRepository.class.getGenericInterfaces();
        assertEquals(1, extended.length, "the interface extends one interface, found " + extended.length);
        ParameterizedType marker = assertInstanceOf(ParameterizedType.class, extended[0],
                "the extended interface carries type arguments");

        assertAll("the declared surface of NotificationLogRepository",
                () -> assertEquals(Set.of("save", "count", "deleteAttemptsBefore"), names,
                        "the interface declares one insert, one total and one retention delete"),
                () -> assertEquals(0, NotificationLogRepository.class.getDeclaredClasses().length,
                        "the interface declares no nested projection type"),
                () -> assertTrue(names.stream().noneMatch(NotificationLogRepositoryTest::readsRows),
                        "no declared name opens with a read prefix, found " + names),
                () -> assertEquals(Set.of(NotificationLogEntity.class, Instant.class), argumentTypes,
                        "a declared method accepts the attempt row or a cutoff instant and nothing"
                                + " else, found " + argumentTypes),
                () -> assertEquals(Set.of(NotificationLogEntity.class, long.class, int.class),
                        returnTypes, "a declared method returns the attempt row or a count, and"
                                + " never a collection or a projection, found " + returnTypes),
                () -> assertEquals(Repository.class, marker.getRawType(),
                        "the interface extends the Spring Data marker repository, which declares no"
                                + " method of its own"),
                () -> assertArrayEquals(new Type[] {NotificationLogEntity.class, UUID.class},
                        marker.getActualTypeArguments(),
                        "the marker carries the attempt row and its identifier type"));
    }

    /**
     * Builds one fully populated attempt on {@link #CARD_TOKEN} and {@link #TRANSACTION_ID}.
     *
     * @param id the identifier of the attempt, which carries the whole primary key
     * @param channel the rendered format the attempt carried
     * @return an attempt carrying all six values, none of them absent
     */
    private static NotificationLogEntity attempt(UUID id, String channel) {
        return attempt(id, CARD_TOKEN, TRANSACTION_ID, channel);
    }

    /**
     * Builds one fully populated attempt on the card and transaction named.
     *
     * <p>The instant truncates to microseconds, the precision
     * {@code attempted_at TIMESTAMP(6) WITH TIME ZONE} holds, so a stored value equals the written
     * one.</p>
     *
     * @param id the identifier of the attempt, which carries the whole primary key
     * @param cardToken the token of the card the attempt alerted
     * @param transactionId the transaction the attempt alerted on
     * @param channel the rendered format the attempt carried
     * @return an attempt carrying all six values, none of them absent
     */
    private static NotificationLogEntity attempt(UUID id, String cardToken, String transactionId,
            String channel) {
        return new NotificationLogEntity(id, cardToken, MASKED_CARD_NUMBER, transactionId, channel,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Reports whether a declared method name opens with a read prefix.
     *
     * @param name the declared method name
     * @return {@code true} when {@link #READ_PREFIXES} holds an opening of the name
     */
    private static boolean readsRows(String name) {
        return READ_PREFIXES.stream().anyMatch(name::startsWith);
    }
}

package com.carddemo.authorization.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts the outbox row one event produces, for both message-key forms the platform publishes.
 *
 * <p>Column {@code aggregate_id} carries the Kafka message key, and it records two forms rather than
 * one. An account identifier holds eleven digits, read from {@code ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT01Y.cpy:L5}, and a transaction identifier holds sixteen characters, read from
 * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. The second form exists because the
 * one decline the source assigns before it holds an account identifier is reject reason {@code 0100}
 * at {@code app/cbl/CBTRN02C.cbl:L382-L387}, where the keyed read of the cross-reference file misses.
 *
 * <p>What these tests protect is the boundary. A writer that accepted only the account form silently
 * dropped every reason {@code 0100} decline, which is the shape of defect that reaches production
 * because nothing fails: the decision was still correct and the response still went back.
 *
 * <p>Every test runs in memory over a stubbed repository. No database, no broker and no application
 * context takes part.
 */
@DisplayName("outbox rows, and the two message-key forms one column carries")
class OutboxWriterTest {

    /** Row one of {@code app/data/ASCII/cardxref.txt}, its account identifier, eleven digits. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The identifier a sequence allocates, sixteen characters. */
    private static final String TRANSACTION_ID = "0000001000000001";

    /** The card number every event below carries, masked before it reaches a payload. */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /** Opaque card surrogate the authorized event carries beside the masked number. */
    private static final String CARD_TOKEN = "0".repeat(64);

    /** The amount every event below carries, at the two digits the Picture clause fixes. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The capture timestamp an authorized event carries, twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.412000";

    /** Collects what the writer saved, so a test can read the row rather than a mock's memory. */
    private OutboxEventRepository outboxEvents;

    /** The subject, over the stubbed repository. */
    private OutboxWriter writer;

    /** Builds the writer over a repository that returns whatever it is given. */
    @BeforeEach
    void buildWriter() {
        outboxEvents = mock(OutboxEventRepository.class);
        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        writer = new OutboxWriter(outboxEvents);
    }

    /** The eleven-digit form, which every resolved outcome uses. */
    @Nested
    @DisplayName("the account key form")
    class AccountKeyForm {

        /** Asserts an approval keys its row on the eleven-digit account identifier. */
        @Test
        void anApprovedEventKeysOnItsAccount() {
            OutboxEventEntity row = writer.writeAuthorized(authorized());

            assertEquals(TransactionAuthorized.EVENT_TYPE, row.getEventType(),
                    "the row records the event type a relay routes on");
            assertEquals(ACCOUNT_ID, row.getAggregateId(),
                    "an approval names the account the cross-reference resolved");
            assertEquals(OutboxEventEntity.AGGREGATE_ID_LENGTH, row.getAggregateId().length(),
                    "ACCT-ID at app/cpy/CVACT01Y.cpy:L5 holds eleven digits");
            assertNotNull(row.getEventId(), "the row records the identifier consumers deduplicate on");
            assertFalse(row.isPublished(), "a fresh row is unpublished until the relay sends it");
        }

        /** Asserts a resolved decline keys its row on the same eleven-digit form. */
        @Test
        void aResolvedDeclineKeysOnItsAccount() {
            OutboxEventEntity row = writer.writeDeclined(TransactionDeclined.of(ACCOUNT_ID,
                    TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, AMOUNT, MASKED_CARD_NUMBER));

            assertEquals(TransactionDeclined.EVENT_TYPE, row.getEventType(),
                    "a reject code names a declined event");
            assertEquals(ACCOUNT_ID, row.getAggregateId(),
                    "reason 0102 fires after the account read succeeded, so an account exists");
            assertTrue(row.getPayload().contains("\"schemaVersion\":1"),
                    "a resolved decline travels under schemas/transaction-declined-v1.json");
        }
    }

    /**
     * The sixteen-character form, which no producer writes and this writer still accepts.
     *
     * <p>{@code domain/AuthorizationService} records an unresolved card in
     * {@code unresolved_card_attempt} and {@code authorization_decision} and writes no outbox row,
     * because reject code {@code 0100} fires where the cross-reference read missed and no account
     * identifier exists to key an event on. What these assertions hold is the writer's side of the
     * retained contract at {@code schemas/transaction-declined-v2.json}: a record written under it
     * before that decision stays readable, and a reinstated producer would write through here. They
     * are therefore about this class and not about what the service publishes, which
     * {@code domain/AuthorizationServiceTest} measures.
     */
    @Nested
    @DisplayName("the transaction key form, retained and unpublished")
    class TransactionKeyForm {

        /** Asserts an unresolved-card decline keys its row on the transaction identifier. */
        @Test
        void anUnresolvedCardDeclineKeysOnItsTransaction() {
            OutboxEventEntity row = writer.writeDeclined(TransactionDeclined
                    .ofUnresolvedAccount(TRANSACTION_ID, AMOUNT, MASKED_CARD_NUMBER));

            assertEquals(TransactionDeclined.EVENT_TYPE, row.getEventType(),
                    "a reject code names a declined event whichever key form it carries");
            assertEquals(TRANSACTION_ID, row.getAggregateId(),
                    "with no account resolved, the transaction identifier is the message key");
            assertEquals(OutboxEventEntity.TRANSACTION_KEY_LENGTH, row.getAggregateId().length(),
                    "TRAN-ID at app/cpy/CVTRA05Y.cpy:L5 holds sixteen characters");
        }

        /**
         * Asserts the row a reason {@code 0100} decline produces validates against version 2 and
         * names no account.
         */
        @Test
        void anUnresolvedCardDeclineTravelsUnderVersionTwoAndNamesNoAccount() {
            OutboxEventEntity row = writer.writeDeclined(TransactionDeclined
                    .ofUnresolvedAccount(TRANSACTION_ID, AMOUNT, MASKED_CARD_NUMBER));

            String payload = row.getPayload();
            assertTrue(payload.contains("\"schemaVersion\":2"),
                    "reason 0100 travels under schemas/transaction-declined-v2.json");
            assertFalse(payload.contains("\"accountId\""),
                    "version 2 declares no accountId, so the writer emits none");
            assertTrue(payload.contains("\"" + DeclineReason.INVALID_CARD_NUMBER.code() + "\""),
                    "the payload carries the reject code app/cbl/CBTRN02C.cbl:L385 assigns");
            assertTrue(payload.contains(MASKED_CARD_NUMBER),
                    "the payload carries the last four digits behind twelve mask characters");
        }

        /**
         * Asserts the two key forms cannot be confused, because their widths do not overlap.
         *
         * <p>This is why one column serves both. An eleven-digit value can only be an account
         * identifier and a sixteen-character value can only be a transaction identifier, so a reader
         * of the column never has to guess which form a row carries.
         */
        @Test
        void theTwoKeyFormsAreDistinguishedByWidthAlone() {
            EventEnvelope accountKeyed =
                    EventEnvelope.of(TransactionDeclined.EVENT_TYPE, ACCOUNT_ID, 1);
            EventEnvelope transactionKeyed = EventEnvelope.of(TransactionDeclined.EVENT_TYPE,
                    TRANSACTION_ID, TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION);

            assertTrue(accountKeyed.carriesAccountKey(), "eleven digits is the account form");
            assertFalse(accountKeyed.carriesTransactionKey(),
                    "eleven digits is never the transaction form");
            assertTrue(transactionKeyed.carriesTransactionKey(),
                    "sixteen characters is the transaction form");
            assertFalse(transactionKeyed.carriesAccountKey(),
                    "sixteen characters is never the account form");
        }
    }

    /** What the column refuses, which is everything that is neither key form. */
    @Nested
    @DisplayName("keys the column refuses")
    class RefusedKeys {

        /** Asserts a key of neither width is refused before a row is built. */
        @Test
        void aKeyOfNeitherWidthIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> row("12345"), "five characters is neither key form");

            assertTrue(refused.getMessage().contains("aggregateId"),
                    "the message names the column, and the message is: " + refused.getMessage());
        }

        /**
         * Asserts an eleven-character key holding anything but digits is refused.
         *
         * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} is numeric, and the check
         * constraint {@code V4__outbox_transaction_key.sql} writes says so, so the entity refuses the
         * value before the database has to.
         */
        @Test
        void anElevenCharacterKeyThatIsNotAllDigitsIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> row("0000000005A"),
                    "eleven characters holding a letter is neither key form");
        }

        /**
         * Asserts no row is built without a key at all.
         *
         * <p>An absent key is a different fault from a malformed one, and the entity reports it as
         * one: a missing argument raises {@link NullPointerException} while a value of the wrong shape
         * raises {@link IllegalArgumentException}. A caller reading only the type learns which of the
         * two mistakes it made.
         */
        @Test
        void anAbsentKeyIsRefused() {
            assertThrows(NullPointerException.class, () -> row(null),
                    "a row with no message key could not be published");
        }

        /**
         * Builds one row directly, bypassing the writer, so the entity's own check is what fails.
         *
         * @param aggregateId the candidate message key
         * @return the row, when the key is one of the two forms
         */
        private OutboxEventEntity row(String aggregateId) {
            return new OutboxEventEntity(UUID.randomUUID(), TransactionDeclined.EVENT_TYPE,
                    aggregateId, "{}", Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    /** @return an authorized event naming {@link #ACCOUNT_ID} */
    private static TransactionAuthorized authorized() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP);
    }
}

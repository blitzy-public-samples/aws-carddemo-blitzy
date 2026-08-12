package com.carddemo.authorization.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Asserts the outbox row one event produces, for both message-key forms the platform publishes.
 *
 * <p>Column {@code aggregate_id} carries the Kafka message key, and one form reaches a row this writer
 * builds: an account identifier of eleven digits, read from {@code ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT01Y.cpy:L5}. Every decision this service records names the account it applies to,
 * reject reason {@code 0100} of {@code app/cbl/CBTRN02C.cbl:L382-L387} included, so every event of one
 * account stays on one partition and in publish order.
 *
 * <p>What these tests protect is the boundary between reading and writing. The sixteen-character
 * transaction form of {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} is still admitted by
 * {@code EventEnvelope#AGGREGATE_KEY_PATTERN} and still governed by a retained document, because a
 * record published under it is still on its topic. Writing it is what migration {@code V19} closed, and
 * a writer that let it through would save a row the column now refuses.
 *
 * <p>Every test runs in memory over a stubbed repository. No database, no broker and no application
 * context takes part.
 */
@DisplayName("outbox rows, and the one message-key form the column now admits")
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

        /**
         * Asserts a decline whose read resolved an account keys its row on the eleven-digit form.
         *
         * <p>Reject code {@code 0100} is excluded because it resolved no account to key on, which the
         * transaction-key tests below cover. The other three follow
         * {@code MOVE XREF-ACCT-ID TO FD-ACCT-ID} at {@code app/cbl/CBTRN02C.cbl:L394} and carry the
         * account that read named.
         *
         * @param reason one reject code assigned after the cross-reference read resolved an account
         */
        @ParameterizedTest
        @EnumSource(value = DeclineReason.class, names = "INVALID_CARD_NUMBER",
                mode = EnumSource.Mode.EXCLUDE)
        void aDeclineKeysOnItsAccount(DeclineReason reason) {
            OutboxEventEntity row = writer.writeDeclined(declinedWithDetail(reason));

            assertEquals(TransactionDeclined.EVENT_TYPE, row.getEventType(),
                    "a reject code names a declined event");
            assertEquals(ACCOUNT_ID, row.getAggregateId(),
                    "a decision that resolved an account names it, so it keys on one");
            assertTrue(row.getPayload().contains("\"schemaVersion\":"
                            + TransactionDeclined.TRANSACTION_DETAIL_SCHEMA_VERSION),
                    "such a decline travels under the version carrying the nine values"
                            + " REJECT-TRAN-DATA needs");
        }
    }

    /**
     * The sixteen-character form, which one outcome writes and this writer admits for that outcome
     * alone.
     *
     * <p>Reject code {@code 0100} is assigned by the cross-reference read failing, so it resolves no
     * account and has no eleven-digit key. The identifier this service minted is the only value it can
     * be keyed on that no caller supplied, and {@code schemas/transaction-declined-v2.json} is the one
     * declined document declaring no {@code accountId} and retyping {@code aggregateId} to the sixteen
     * printable characters that identifier occupies.
     *
     * <p>So the writer admits the transaction form for that one pairing of event type and contract
     * version, and refuses it for every other. Migration {@code V19} records why the column still
     * admits both widths — a {@code CHECK} narrowed after rows exist is enforced on every
     * {@code UPDATE} of them — and migration {@code V24} records why the pairing is written again.
     */
    @Nested
    @DisplayName("the transaction key form, written by the one outcome that resolves no account")
    class TransactionKeyForm {

        /** Asserts the writer admits the transaction-keyed form for the account-less contract. */
        @Test
        void aTransactionKeyedDeclineIsWrittenForTheAccountLessContract() {
            OutboxEventEntity row = writer.writeDeclined(TransactionDeclined
                    .ofUnresolvedAccount(TRANSACTION_ID, AMOUNT, MASKED_CARD_NUMBER));

            assertEquals(TransactionDeclined.EVENT_TYPE, row.getEventType(),
                    "a reject code names a declined event");
            assertEquals(TRANSACTION_ID, row.getAggregateId(),
                    "the identifier this service minted is the key, so no caller chose the"
                            + " partition");
            assertTrue(row.getPayload().contains("\"schemaVersion\":"
                            + TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION),
                    "and the row travels under the one document declaring no accountId: "
                            + row.getPayload());
            assertFalse(row.getPayload().contains("\"accountId\""),
                    "which is visible in the payload: " + row.getPayload());
        }

        /**
         * Asserts a transaction-keyed record under any other contract version cannot be built, so the
         * writer's own narrowing is the second of two checks rather than the only one.
         *
         * <p>The admission above is narrow on purpose. Version 3 requires an {@code accountId} and keys
         * on it, so a version-3 record carrying a sixteen-character key would name an account in its
         * payload that its key does not name, and a consumer partitioning by account would read it off
         * the wrong partition.
         *
         * <p>Which layer refuses that is what this test pins. {@code TransactionDeclined} refuses it in
         * its own constructor, naming the eleven-digit pattern, so the pairing never reaches this
         * writer through any factory. The writer narrows the key form again for the same pairing, which
         * is what holds a record built through the canonical constructor — a deserializer does that —
         * to the same rule.
         */
        @Test
        void aTransactionKeyedRecordUnderAnotherContractCannotBeBuilt() {
            EventEnvelope transactionKeyedAtVersionThree = EventEnvelope.of(
                    TransactionDeclined.EVENT_TYPE, TRANSACTION_ID,
                    TransactionDeclined.TRANSACTION_DETAIL_SCHEMA_VERSION);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> TransactionDeclined.of(transactionKeyedAtVersionThree, TRANSACTION_ID,
                            DeclineReason.ACCOUNT_NOT_FOUND, AMOUNT, MASKED_CARD_NUMBER),
                    "only the account-less declined contract keys on a transaction identifier");

            assertTrue(refused.getMessage().contains(EventEnvelope.AGGREGATE_ID_PATTERN),
                    "the message names the key form every other contract carries, and the message"
                            + " is: " + refused.getMessage());
            verify(outboxEvents, never()).save(any(OutboxEventEntity.class));
        }

        /**
         * Asserts the account-less record is both readable and publishable, which is what promoting its
         * document to PUBLISHED buys.
         *
         * <p>Both halves matter. A consumer meeting a record published under this document before the
         * producer path existed still has to read it, so {@code EventContracts#violationsOf} accepts it.
         * And the producer path now exists, so {@code EventContracts#publishViolationsOf} must accept it
         * too — that method is the gate {@code outbox/OutboxWriter} passes every payload through, and a
         * RETAINED posture there is what refused this outcome an event at all.
         */
        @Test
        void theTransactionKeyedRecordIsReadableAndPublishable() {
            TransactionDeclined accountLess = TransactionDeclined
                    .ofUnresolvedAccount(TRANSACTION_ID, AMOUNT, MASKED_CARD_NUMBER);
            String payload = new String(new JsonSchemaValidatingSerializer<Object>().serialize(
                    EventContracts.defaultTopicFor(TransactionDeclined.EVENT_TYPE), accountLess),
                    StandardCharsets.UTF_8);

            assertTrue(EventContracts.violationsOf(TransactionDeclined.EVENT_TYPE, payload)
                            .isEmpty(),
                    "a record already published under this document stopped being readable");
            assertTrue(EventContracts.publishViolationsOf(TransactionDeclined.EVENT_TYPE, payload)
                            .isEmpty(),
                    "and a producer may write it: " + EventContracts
                            .publishViolationsOf(TransactionDeclined.EVENT_TYPE, payload));
            assertTrue(payload.contains("\"schemaVersion\":"
                            + TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION),
                    "the record declares the version its document names: " + payload);
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

    /**
     * What the publish gate refuses inside a free-text field, before the row exists to roll back.
     *
     * <p>{@code TRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9} and the merchant name and city
     * beside it are the only values on this event a caller writes in prose, so they are the only values
     * that can carry a cardholder number, a social security number or a card verification code by
     * accident. The gate screens them on the way into the outbox rather than on the way out of it,
     * which is what makes the refusal and the decision roll back together: no row is saved at all.
     *
     * <p>Each test asserts the repository was never asked to save, because a row that reached the
     * repository would commit with the request and publish afterwards.
     */
    @Nested
    @DisplayName("free-text fields, screened before a row is saved")
    class ScreenedNarrative {

        /** Asserts a cardholder number in the description stops the write. */
        @Test
        void aCardNumberInTheDescriptionIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithDescription("Refund for card 4111111111111111")), "digit run");
        }

        /** Asserts the punctuated form of the same number stops the write. */
        @Test
        void aPunctuatedCardNumberInTheDescriptionIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithDescription("Refund for card 4111-1111-1111-1111")),
                    "separated digit run");
        }

        /** Asserts a formatted social security number in the description stops the write. */
        @Test
        void aFormattedSocialSecurityNumberInTheDescriptionIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithDescription("Cardholder SSN 123-45-6789")), "social security");
        }

        /**
         * Asserts the unseparated form of the same number stops the write.
         *
         * <p>Nine digits in a row carries no separator to key on, so the narrative screen refuses the
         * shape rather than the format. {@code app/data/ASCII/dailytran.txt} carries no description of
         * that shape across its three hundred records, so nothing the fixtures publish is caught by it.
         */
        @Test
        void anUnseparatedSocialSecurityNumberInTheDescriptionIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithDescription("Cardholder ref 123456789")), "nine digits");
        }

        /** Asserts a labelled card verification code in the description stops the write. */
        @Test
        void aLabelledVerificationCodeInTheDescriptionIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithDescription("Phone order, cvv 123 supplied")), "verification");
        }

        /**
         * Asserts a description that is a bare three-digit value stops the write.
         *
         * <p>A verification code carries no label when a field holds nothing else, so the narrative
         * screen refuses a value that is only three or four digits. Every description in
         * {@code app/data/ASCII/dailytran.txt} is prose, so none is refused by this rule.
         */
        @Test
        void aBareVerificationCodeInTheDescriptionIsRefused() {
            assertRefused(() -> writer.writeAuthorized(authorizedWithDescription("123")),
                    "bare code");
        }

        /** Asserts the merchant name is screened on the same terms as the description. */
        @Test
        void aCardNumberInTheMerchantNameIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithMerchantName("Abshire-Lowe 4111111111111111")), "merchant name");
        }

        /** Asserts the merchant city is screened on the same terms as the description. */
        @Test
        void aCardNumberInTheMerchantCityIsRefused() {
            assertRefused(() -> writer.writeAuthorized(
                    authorizedWithMerchantCity("North Enoshaven 4111111111111111")),
                    "merchant city");
        }

        /**
         * Asserts the capture source is screened, which is the shortest free-text value on the event.
         *
         * <p>{@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8} holds ten characters,
         * so a card number cannot fit and an unseparated government identifier can.
         */
        @Test
        void anUnseparatedSocialSecurityNumberInTheSourceIsRefused() {
            assertRefused(() -> writer.writeAuthorized(authorizedWithSource("123456789")),
                    "capture source");
        }

        /** Asserts a decline carries the same screen, so a rejected transaction cannot leak either. */
        @Test
        void aCardNumberInADeclineDescriptionIsRefused() {
            assertRefused(() -> writer.writeDeclined(
                    declinedWithDescription("Refund for card 4111111111111111")), "decline");
        }

        /**
         * Asserts ordinary prose still reaches a row, so the screen has not closed the field.
         *
         * <p>Without this the tests above would pass against a writer that refused every description,
         * which would break every record in the fixtures.
         */
        @Test
        void ordinaryProseStillReachesARow() {
            OutboxEventEntity row = writer.writeAuthorized(
                    authorizedWithDescription("Purchase at Abshire-Lowe, order 4471"));

            assertEquals(ACCOUNT_ID, row.getAggregateId(), "the row was saved under its account");
            verify(outboxEvents).save(any(OutboxEventEntity.class));
        }

        /**
         * Runs the write, asserts it was refused, and asserts nothing was saved.
         *
         * @param write what the writer was asked to do
         * @param what  the shape under test, named in the assertion message
         */
        private void assertRefused(org.junit.jupiter.api.function.Executable write, String what) {
            assertThrows(IllegalArgumentException.class, write,
                    "the gate admitted a " + what + " into an outbox row");
            verify(outboxEvents, never()).save(any(OutboxEventEntity.class));
        }
    }

    /**
     * Builds an authorized event carrying the supplied description and nothing else unusual.
     *
     * @param description the description under test
     * @return the authorized event naming {@link #ACCOUNT_ID}
     */
    private static TransactionAuthorized authorizedWithDescription(String description) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                description, AMOUNT, "800000000", "Abshire-Lowe", "North Enoshaven", "72112",
                MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP);
    }

    /**
     * Builds an authorized event carrying the supplied merchant name.
     *
     * @param merchantName the merchant name under test
     * @return the authorized event naming {@link #ACCOUNT_ID}
     */
    private static TransactionAuthorized authorizedWithMerchantName(String merchantName) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", merchantName, "North Enoshaven",
                "72112", MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP);
    }

    /**
     * Builds an authorized event carrying the supplied merchant city.
     *
     * @param merchantCity the merchant city under test
     * @return the authorized event naming {@link #ACCOUNT_ID}
     */
    private static TransactionAuthorized authorizedWithMerchantCity(String merchantCity) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe", merchantCity,
                "72112", MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP);
    }

    /**
     * Builds an authorized event carrying the supplied capture source.
     *
     * @param source the capture source under test
     * @return the authorized event naming {@link #ACCOUNT_ID}
     */
    private static TransactionAuthorized authorizedWithSource(String source) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", source,
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe", "North Enoshaven",
                "72112", MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP);
    }

    /**
     * Builds a declined event carrying the supplied description.
     *
     * @param description the description under test
     * @return the declined event naming {@link #ACCOUNT_ID}
     */
    private static TransactionDeclined declinedWithDescription(String description) {
        return TransactionDeclined.withTransactionDetail(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, "01", "0001", "POS TERM", description, AMOUNT,
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                ORIGIN_TIMESTAMP);
    }

    /**
     * Builds the decline a producer writes, carrying the nine values {@code REJECT-TRAN-DATA} needs.
     *
     * @param reason the reject reason that stands, any of the four
     * @return the declined event naming {@link #ACCOUNT_ID}
     */
    private static TransactionDeclined declinedWithDetail(DeclineReason reason) {
        return TransactionDeclined.withTransactionDetail(ACCOUNT_ID, TRANSACTION_ID, reason, "01",
                "0001", "POS TERM", "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
    }

    /** @return an authorized event naming {@link #ACCOUNT_ID} */
    private static TransactionAuthorized authorized() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP);
    }
}

package com.carddemo.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Checks that the free-text values a posted event copies forward are screened before the row exists.
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L424-L444} copies all twelve daily-transaction fields onto the
 * posted record and stamps the processing timestamp, so every value a caller wrote into the
 * authorization arrives here unchanged. Three of those values are prose:
 * {@code TRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9}, and the merchant name and city at
 * {@code app/cpy/CVTRA05Y.cpy:L12-L13}. The source checks none of their content, which is why the
 * publish gate screens them on the way into {@code outbox_event} rather than on the way out.
 *
 * <p>The property this class protects is the transaction boundary of {@code domain/PostingService}.
 * The posting rows, the processed-event marker and the outbox row commit together, so a screened
 * value has to fail while that transaction can still roll back. Every test therefore asserts the
 * repository was never asked to save: a row that reached the repository would commit with the balance
 * arithmetic beside it and publish afterwards.
 *
 * <p>Every test runs in memory over a stubbed repository. No database, no broker and no application
 * context takes part.
 */
@DisplayName("free-text values a posted event carries, screened before a row is saved")
class OutboxWriterTest {

    /** Row one of {@code app/data/ASCII/cardxref.txt}, its account identifier, eleven digits. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The identifier a sequence allocates, sixteen characters. */
    private static final String TRANSACTION_ID = "0000001000000001";

    /** The masked form every payload carries, twelve asterisks and four digits. */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /** Opaque card surrogate, sixty-four lower-case hexadecimal characters. */
    private static final String CARD_TOKEN = "0".repeat(64);

    /** The amount, at the two digits {@code TRAN-AMT PIC S9(09)V99} fixes. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The balance after the add of {@code app/cbl/CBTRN02C.cbl:L547}. */
    private static final BigDecimal NEW_BALANCE = new BigDecimal("1200.00");

    /** The capture timestamp, twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.412000";

    /**
     * The processing timestamp of {@code app/cbl/CBTRN02C.cbl:L438}, twenty-six characters.
     *
     * <p>Two fractional digits carry information and four zeros follow them, because
     * {@code app/cbl/CBTRN02C.cbl:L701} takes the fraction from a hundredths field and writes four
     * zero characters after it.
     */
    private static final String POSTED_AT = "2022-06-10-19.27.54.120000";

    /** Collects what the writer offered to save, so a test can assert nothing was offered. */
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

    /** What the gate refuses inside one of the three prose values. */
    @Nested
    @DisplayName("the three prose values")
    class ProseValues {

        /** Asserts a cardholder number in the description stops the write. */
        @Test
        void aCardNumberInTheDescriptionIsRefused() {
            assertRefused(posted("Refund for card 4111111111111111", "Abshire-Lowe",
                    "North Enoshaven"), "digit run");
        }

        /** Asserts the punctuated form of the same number stops the write. */
        @Test
        void aPunctuatedCardNumberInTheDescriptionIsRefused() {
            assertRefused(posted("Refund for card 4111-1111-1111-1111", "Abshire-Lowe",
                    "North Enoshaven"), "separated digit run");
        }

        /** Asserts a formatted social security number in the description stops the write. */
        @Test
        void aFormattedSocialSecurityNumberInTheDescriptionIsRefused() {
            assertRefused(posted("Cardholder SSN 123-45-6789", "Abshire-Lowe", "North Enoshaven"),
                    "social security");
        }

        /**
         * Asserts the unseparated form of the same number stops the write.
         *
         * <p>Nine digits in a row carries no separator to key on, so the screen refuses the shape.
         * No description across the three hundred records of {@code app/data/ASCII/dailytran.txt}
         * carries that shape, so nothing the fixtures post is caught by it.
         */
        @Test
        void anUnseparatedSocialSecurityNumberInTheDescriptionIsRefused() {
            assertRefused(posted("Cardholder ref 123456789", "Abshire-Lowe", "North Enoshaven"),
                    "nine digits");
        }

        /** Asserts a labelled card verification code in the description stops the write. */
        @Test
        void aLabelledVerificationCodeInTheDescriptionIsRefused() {
            assertRefused(posted("Phone order, cvv 123 supplied", "Abshire-Lowe",
                    "North Enoshaven"), "verification");
        }

        /** Asserts a description holding nothing but a three-digit code stops the write. */
        @Test
        void aBareVerificationCodeInTheDescriptionIsRefused() {
            assertRefused(posted("123", "Abshire-Lowe", "North Enoshaven"), "bare code");
        }

        /** Asserts the merchant name is screened on the same terms. */
        @Test
        void aCardNumberInTheMerchantNameIsRefused() {
            assertRefused(posted("Purchase at Abshire-Lowe", "Abshire-Lowe 4111111111111111",
                    "North Enoshaven"), "merchant name");
        }

        /** Asserts the merchant city is screened on the same terms. */
        @Test
        void aCardNumberInTheMerchantCityIsRefused() {
            assertRefused(posted("Purchase at Abshire-Lowe", "Abshire-Lowe",
                    "North Enoshaven 4111111111111111"), "merchant city");
        }

        /**
         * Asserts the merchant postal code keeps the nine-digit form its source field carries.
         *
         * <p>{@code TRAN-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L14} holds ten
         * characters, and a United States postal code written in full is nine digits with a
         * separator, which is the shape a social security number takes. The postal code is therefore
         * screened for a cardholder number and not for that shape, or a real code would be refused.
         */
        @Test
        void aNineDigitMerchantPostalCodeStillReachesARow() {
            OutboxEventEntity row = writer.write(postedWithMerchantZip("72112-6716"));

            assertThat(row.getAggregateId()).isEqualTo(ACCOUNT_ID);
            verify(outboxEvents).save(any(OutboxEventEntity.class));
        }

        /**
         * Asserts ordinary prose still reaches a row.
         *
         * <p>Without this the tests above would pass against a writer that refused every
         * description, which would stop every record in the fixtures from posting.
         */
        @Test
        void ordinaryProseStillReachesARow() {
            OutboxEventEntity row = writer.write(posted("Purchase at Abshire-Lowe, order 4471",
                    "Abshire-Lowe", "North Enoshaven"));

            assertThat(row.getEventType()).isEqualTo(TransactionPosted.EVENT_TYPE);
            assertThat(row.getAggregateId()).isEqualTo(ACCOUNT_ID);
            assertThat(row.isPublished()).isFalse();
            verify(outboxEvents).save(any(OutboxEventEntity.class));
        }

        /**
         * Runs the write, asserts it was refused, and asserts nothing was saved.
         *
         * @param event the event under test
         * @param what  the shape under test, named in the assertion message
         */
        private void assertRefused(TransactionPosted event, String what) {
            assertThatThrownBy(() -> writer.write(event))
                    .as("the gate admitted a " + what + " into an outbox row")
                    .isInstanceOf(IllegalArgumentException.class);
            verify(outboxEvents, never()).save(any(OutboxEventEntity.class));
        }
    }

    /**
     * Builds the posted event that follows one authorization carrying the supplied prose.
     *
     * @param description  the transaction description
     * @param merchantName the merchant name
     * @param merchantCity the merchant city
     * @return the posted event, at the version that carries all fifteen payload values
     */
    private static TransactionPosted posted(String description, String merchantName,
            String merchantCity) {
        return TransactionPosted.forAuthorized(
                TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                        description, AMOUNT, "800000000", merchantName, merchantCity, "72112",
                        MASKED_CARD_NUMBER, CARD_TOKEN, ORIGIN_TIMESTAMP),
                NEW_BALANCE, POSTED_AT);
    }

    /**
     * Builds the posted event that follows one ordinary authorization, at the supplied postal code.
     *
     * @param merchantZip the merchant postal code
     * @return the posted event, at the version that carries all fifteen payload values
     */
    private static TransactionPosted postedWithMerchantZip(String merchantZip) {
        return TransactionPosted.forAuthorized(
                TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                        "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe",
                        "North Enoshaven", merchantZip, MASKED_CARD_NUMBER, CARD_TOKEN,
                        ORIGIN_TIMESTAMP),
                NEW_BALANCE, POSTED_AT);
    }
}

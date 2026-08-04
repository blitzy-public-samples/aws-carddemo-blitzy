package com.carddemo.authorization;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.cobol.PanMasker;

import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that nothing this service can hand to a logger carries a card number, a monetary amount or
 * an account identifier.
 *
 * <p>ADDITIVE. No COBOL program renders a record for a log; {@code app/cbl/CBTRN02C.cbl:L714-L727}
 * formats a two-byte file status into four digits and nothing else. This class has no ancestor.
 *
 * <p>Two shapes are covered. {@link AuthorizationRequest} is a record, so the compiler writes a
 * {@code toString} that prints every component unless the record overrides it, which makes the
 * default the dangerous case. {@link AccountCreditSnapshotEntity} is the read side of the account
 * record, and every column but the expiry date is either an identifier or money.
 *
 * <p>Each test asserts on the value, not on the field name. A rendering that named the field and
 * withheld the value would pass an assertion written against the name and still leak nothing, so the
 * assertions look for the digits themselves.
 */
@DisplayName("Diagnostic redaction, the authorization service")
class DiagnosticRedactionTest {

    /** A full sixteen-digit Primary Account Number, the width {@code CARD-NUM PIC X(16)} holds. */
    private static final String FULL_CARD_NUMBER = "4111222233337065";

    /** The last four characters of {@link #FULL_CARD_NUMBER}, the only part a log may carry. */
    private static final String VISIBLE_DIGITS = "7065";

    /** An amount at the scale {@code TRAN-AMT PIC S9(09)V99} declares. */
    private static final String AMOUNT_TEXT = "+00001250.75";

    /** The eleven-digit account identifier of {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Free text a caller supplies, which a rendering must not repeat. */
    private static final String DESCRIPTION = "CARDHOLDER SUPPLIED NARRATIVE";

    /** A merchant name, the second free-text component. */
    private static final String MERCHANT_NAME = "ACME HARDWARE OF SPRINGFIELD";

    @Nested
    @DisplayName("AuthorizationRequest")
    class AuthorizationRequestDiagnostics {

        @Test
        @DisplayName("the rendering masks the card number and keeps only the last four digits")
        void theRenderingMasksTheCardNumber() {
            String rendered = fullyPopulatedRequest().toString();

            assertThat(rendered)
                    .withFailMessage("the full Primary Account Number reached a log line")
                    .doesNotContain(FULL_CARD_NUMBER);
            assertThat(rendered).contains(PanMasker.maskCardNumber(FULL_CARD_NUMBER));
            assertThat(rendered).contains(VISIBLE_DIGITS);
            assertThat(rendered).contains("************" + VISIBLE_DIGITS);
        }

        @Test
        @DisplayName("the rendering withholds the amount, both identifiers and both free-text fields")
        void theRenderingWithholdsTheAmountAndBothIdentifiers() {
            String rendered = fullyPopulatedRequest().toString();

            assertThat(rendered)
                    .withFailMessage("the amount reached a log line")
                    .doesNotContain(AMOUNT_TEXT).doesNotContain("1250.75");
            assertThat(rendered)
                    .withFailMessage("the account identifier reached a log line")
                    .doesNotContain(ACCOUNT_ID);
            assertThat(rendered)
                    .withFailMessage("the transaction identifier reached a log line")
                    .doesNotContain("0000000000000001");
            assertThat(rendered)
                    .withFailMessage("caller-supplied free text reached a log line")
                    .doesNotContain(DESCRIPTION).doesNotContain(MERCHANT_NAME);
        }

        @Test
        @DisplayName("the rendering names the record and every component it withholds")
        void theRenderingStillIdentifiesTheRecord() {
            String rendered = fullyPopulatedRequest().toString();

            assertThat(rendered).startsWith("AuthorizationRequest[");
            assertThat(rendered).endsWith("]");
            for (String component : List.of("transactionId", "transactionTypeCode",
                    "transactionCategoryCode", "source", "description", "amount", "merchantId",
                    "merchantName", "merchantCity", "merchantZip", "cardNumber", "originTimestamp",
                    "processingTimestamp", "accountId")) {
                assertThat(rendered)
                        .withFailMessage("the rendering stopped naming the component " + component)
                        .contains(component + "=");
            }
            assertThat(rendered)
                    .withFailMessage("the rendering stopped reporting which components arrived")
                    .contains(AuthorizationRequest.WITHHELD);
        }

        @Test
        @DisplayName("an absent card number is reported as absent rather than as null")
        void anAbsentCardNumberRendersFullyMasked() {
            AuthorizationRequest request = new AuthorizationRequest("0000000000000001", "01", "5411",
                    "POS", DESCRIPTION, AMOUNT_TEXT, "123456789", MERCHANT_NAME, "SPRINGFIELD",
                    "62701", null, "2024-01-15 10:30:00", "2024-01-15 10:30:01", ACCOUNT_ID);

            assertThat(request.toString())
                    .contains("cardNumber=" + AuthorizationRequest.ABSENT);
            assertThat(request.toString())
                    .withFailMessage("an absent card number rendered as a bare null")
                    .doesNotContain("cardNumber=null");
        }

        @Test
        @DisplayName("redaction changes no accessor, so the decision still reads the full card number")
        void redactionChangesNoAccessor() {
            AuthorizationRequest request = fullyPopulatedRequest();

            assertThat(request.cardNumber()).isEqualTo(FULL_CARD_NUMBER);
            assertThat(request.canonicalCardNumber()).isEqualTo(FULL_CARD_NUMBER);
            assertThat(request.canonicalAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.amountValue()).isEqualByComparingTo(new BigDecimal("1250.75"));
            assertThat(request.amountValue().scale()).isEqualTo(2);
            assertThat(request.description()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the wire payload still carries the full card number, amount and account id")
        void theWirePayloadStillCarriesEveryComponent() throws Exception {
            ObjectMapper mapper = JsonMapper.builder().build();
            AuthorizationRequest request = fullyPopulatedRequest();

            String json = mapper.writeValueAsString(request);

            assertThat(json)
                    .withFailMessage("redaction reached the wire and the decision would lose the "
                            + "card number the cross-reference lookup keys on")
                    .contains(FULL_CARD_NUMBER);
            assertThat(json).contains(AMOUNT_TEXT).contains(ACCOUNT_ID).contains(DESCRIPTION);
            assertThat(json).doesNotContain("redacted");
            assertThat(mapper.readValue(json, AuthorizationRequest.class)).isEqualTo(request);
        }

        @Test
        @DisplayName("equals and hashCode still read every component")
        void equalsAndHashCodeStillReadEveryComponent() {
            AuthorizationRequest one = fullyPopulatedRequest();
            AuthorizationRequest same = fullyPopulatedRequest();
            AuthorizationRequest otherCard = new AuthorizationRequest("0000000000000001", "01",
                    "5411", "POS", DESCRIPTION, AMOUNT_TEXT, "123456789", MERCHANT_NAME,
                    "SPRINGFIELD", "62701", "4111222233330001", "2024-01-15 10:30:00",
                    "2024-01-15 10:30:01", ACCOUNT_ID);

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one)
                    .withFailMessage("the override reached equality and collapsed two card numbers")
                    .isNotEqualTo(otherCard);
        }

        /** Builds a request carrying every component, so no assertion passes by absence. */
        private AuthorizationRequest fullyPopulatedRequest() {
            return new AuthorizationRequest("0000000000000001", "01", "5411", "POS", DESCRIPTION,
                    AMOUNT_TEXT, "123456789", MERCHANT_NAME, "SPRINGFIELD", "62701",
                    FULL_CARD_NUMBER, "2024-01-15 10:30:00", "2024-01-15 10:30:01", ACCOUNT_ID);
        }
    }

    /**
     * The eleven digits {@code ACCT-ID PIC 9(11)} holds, chosen so no other value in this class
     * shares a substring with it and no assertion can pass by coincidence.
     */
    private static final String SNAPSHOT_ACCOUNT_ID = "98765432109";

    @Nested
    @DisplayName("AccountCreditSnapshotEntity")
    class AccountCreditSnapshotDiagnostics {

        @Test
        @DisplayName("the rendering withholds the identifier and all three monetary columns")
        void theRenderingWithholdsTheIdentifierAndTheMoney() {
            String rendered = snapshot().toString();

            assertThat(rendered)
                    .withFailMessage("the account identifier reached a log line")
                    .doesNotContain(SNAPSHOT_ACCOUNT_ID);
            assertThat(rendered)
                    .withFailMessage("the credit limit reached a log line")
                    .doesNotContain("5000.00");
            assertThat(rendered)
                    .withFailMessage("the cycle credit reached a log line")
                    .doesNotContain("1200.50");
            assertThat(rendered)
                    .withFailMessage("the cycle debit reached a log line")
                    .doesNotContain("300.25");
        }

        @Test
        @DisplayName("the rendering names the expiry date reason 103 compares and withholds it")
        void theRenderingKeepsTheExpiryDate() {
            String rendered = snapshot().toString();

            assertThat(rendered).startsWith("AccountCreditSnapshotEntity[");
            assertThat(rendered)
                    .withFailMessage("the expiry date reason 103 compares reached a log line")
                    .doesNotContain("2099-12-31");
            assertThat(rendered).contains("accountExpirationDate=" + EventEnvelope.WITHHELD);
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("redaction changes no accessor, so the credit-limit rule still reads the money")
        void redactionChangesNoAccessor() {
            AccountCreditSnapshotEntity entity = snapshot();

            assertThat(entity.getAccountId()).isEqualTo(SNAPSHOT_ACCOUNT_ID);
            assertThat(entity.getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
            assertThat(entity.getCurrentCycleCredit())
                    .isEqualByComparingTo(new BigDecimal("1200.50"));
            assertThat(entity.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("300.25"));
            assertThat(entity.getAccountExpirationDate()).isEqualTo("2099-12-31");
        }

        /** Builds a snapshot at the scales the account picture clauses declare. */
        private AccountCreditSnapshotEntity snapshot() {
            return new AccountCreditSnapshotEntity(SNAPSHOT_ACCOUNT_ID,
                    new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("1200.50"),
                    new BigDecimal("300.25"), Instant.parse("2026-01-01T00:00:00Z"));
        }
    }
}

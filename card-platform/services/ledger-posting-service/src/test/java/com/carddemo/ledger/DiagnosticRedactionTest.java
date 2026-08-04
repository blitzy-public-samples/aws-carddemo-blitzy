package com.carddemo.ledger;

import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.entity.TransactionEntity;

import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that no entity of the posting path can hand a logger a transaction identifier, an account
 * identifier or a monetary amount.
 *
 * <p>ADDITIVE. No COBOL program renders a record for a log; {@code app/cbl/CBTRN02C.cbl:L714-L727}
 * formats a two-byte file status into four digits and nothing else. This class has no ancestor.
 *
 * <p>This is the service that holds the money, so all five of its entities are covered. Each
 * assertion looks for the value rather than the field name, because a rendering that named a field
 * and withheld its value must pass.
 */
@DisplayName("Diagnostic redaction, the ledger posting service")
class DiagnosticRedactionTest {

    /** Sixteen digits, the width {@code TRAN-ID PIC X(16)} declares. */
    private static final String TRANSACTION_ID = "9876543210987654";

    /** Eleven digits, the width {@code ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "98765432109";

    /** The masked form {@code TransactionEntity} stores, twelve masks and four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /**
     * The full Primary Account Number the masked form above stands for. The reject row masks it in
     * its own constructor, so this value reaches no column and no rendering.
     */
    private static final String CARD_NUMBER = "4859452612877065";

    /** An amount at the two-place scale {@code TRAN-AMT PIC S9(09)V99} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("1250.75");

    @Test
    @DisplayName("the transaction rendering keeps the identifier and withholds every value")
    void theTransactionRenderingWithholdsTheIdentifierAndTheAmount() {
        TransactionEntity transaction = new TransactionEntity(TRANSACTION_ID, "01", "5411", "POS",
                "CARDHOLDER SUPPLIED NARRATIVE", AMOUNT, "123456789",
                "ACME HARDWARE OF SPRINGFIELD", "SPRINGFIELD", "62701", MASKED_CARD_NUMBER,
                "2024-01-15 10:30:00", "2024-01-15 10:30:01.00 0000");

        String rendered = transaction.toString();

        assertThat(rendered)
                .withFailMessage("the transaction identifier is the one handle a reader needs to "
                        + "join a log line to a row, and the rendering stopped carrying it")
                .contains("transactionId=" + TRANSACTION_ID);
        assertThat(rendered)
                .withFailMessage("the amount reached a log line")
                .doesNotContain("1250.75");
        assertThat(rendered)
                .withFailMessage("the masked card number reached a log line, and a log needs none")
                .doesNotContain(MASKED_CARD_NUMBER).doesNotContain("7065");
        assertThat(rendered)
                .withFailMessage("caller-supplied free text reached a log line")
                .doesNotContain("CARDHOLDER SUPPLIED NARRATIVE")
                .doesNotContain("ACME HARDWARE OF SPRINGFIELD");
        assertThat(rendered).startsWith("TransactionEntity[");
        assertThat(rendered).endsWith("]");
        for (String component : List.of("typeCode", "categoryCode", "amount", "originTimestamp",
                "processedTimestamp")) {
            assertThat(rendered)
                    .withFailMessage("the rendering stopped naming the component " + component)
                    .contains(component + "=" + EventEnvelope.WITHHELD);
        }
        assertThat(transaction.getTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(transaction.getAmount()).isEqualByComparingTo(AMOUNT);
    }

    @Test
    @DisplayName("the balance projection rendering carries no value at all")
    void theBalanceProjectionRenderingCarriesNoValueAtAll() {
        AccountBalanceProjectionEntity projection = new AccountBalanceProjectionEntity(ACCOUNT_ID,
                new BigDecimal("4321.99"), new BigDecimal("1200.50"), new BigDecimal("300.25"));

        String rendered = projection.toString();

        assertThat(rendered)
                .withFailMessage("the account identifier reached a log line")
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered)
                .withFailMessage("a balance reached a log line")
                .doesNotContain("4321.99").doesNotContain("1200.50").doesNotContain("300.25");
        assertThat(rendered).isEqualTo("AccountBalanceProjectionEntity[accountId="
                + EventEnvelope.WITHHELD + ", currentBalance=" + EventEnvelope.WITHHELD
                + ", cycleCredit=" + EventEnvelope.WITHHELD + ", cycleDebit="
                + EventEnvelope.WITHHELD + "]");
        assertThat(projection.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(projection.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("4321.99"));
    }

    @Test
    @DisplayName("the outbox rendering withholds the aggregate identifier and the payload")
    void theOutboxRenderingWithholdsTheAggregateIdentifierAndThePayload() {
        UUID eventId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String payload = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"newBalance\":\"4321.99\"}";
        OutboxEventEntity event = new OutboxEventEntity(eventId, "TransactionPosted", ACCOUNT_ID,
                payload, Instant.parse("2024-01-15T10:30:00Z"));

        String rendered = event.toString();

        assertThat(rendered)
                .withFailMessage("the aggregate identifier reached a log line")
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered)
                .withFailMessage("the payload reached a log line")
                .doesNotContain(payload).doesNotContain("4321.99");
        assertThat(rendered).contains("eventId=11111111-2222-3333-4444-555555555555");
        assertThat(rendered).contains("eventType=TransactionPosted");
        assertThat(rendered).contains("published=false");
        assertThat(rendered).contains("aggregateId=" + EventEnvelope.WITHHELD);
        assertThat(event.getAggregateId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("the reject rendering withholds the identifier and keeps the source reason text")
    void theRejectRenderingWithholdsTheIdentifierAndKeepsTheReason() {
        UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        RejectedTransactionEntity reject = new RejectedTransactionEntity(id, TRANSACTION_ID, "0102",
                "OVERLIMIT TRANSACTION", CARD_NUMBER, AMOUNT, "01", "5411", "123456789",
                "2024-01-15 10:30:00.000000", Instant.parse("2024-01-15T10:30:00Z"));

        String rendered = reject.toString();

        assertThat(rendered)
                .withFailMessage("the transaction identifier reached a log line")
                .doesNotContain(TRANSACTION_ID);
        assertThat(rendered)
                .withFailMessage("the full card number of the refused record reached a log line")
                .doesNotContain(CARD_NUMBER);
        assertThat(rendered)
                .withFailMessage("the amount of the refused record reached a log line")
                .doesNotContain(AMOUNT.toPlainString());
        assertThat(rendered).contains("id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(rendered).contains("rejectReasonCode=0102");
        assertThat(rendered)
                .withFailMessage("the reject reason text is why a reader opens this row")
                .contains("rejectReasonDescription=OVERLIMIT TRANSACTION");
        assertThat(rendered).contains("transactionId redacted");
        assertThat(reject.getTransactionId()).isEqualTo(TRANSACTION_ID);
    }

    @Test
    @DisplayName("every rendering names its own class, so a stack trace still says which row it was")
    void everyRenderingNamesItsOwnClass() {
        assertThat(new AccountBalanceProjectionEntity(ACCOUNT_ID, new BigDecimal("0.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00")).toString())
                .startsWith("AccountBalanceProjectionEntity[");
        assertThat(new OutboxEventEntity(UUID.randomUUID(), "TransactionPosted", ACCOUNT_ID, "{}",
                Instant.EPOCH).toString()).startsWith("OutboxEventEntity{");
        assertThat(new RejectedTransactionEntity(UUID.randomUUID(), TRANSACTION_ID, "0100",
                "INVALID CARD NUMBER FOUND", CARD_NUMBER, AMOUNT, "01", "5411", "123456789",
                "2024-01-15 10:30:00.000000", Instant.EPOCH).toString())
                .startsWith("RejectedTransactionEntity{");
        assertThat(new TransactionEntity(TRANSACTION_ID, "01", "5411", "POS", "D", AMOUNT,
                "123456789", "M", "C", "62701", MASKED_CARD_NUMBER, "2024-01-15 10:30:00",
                "2024-01-15 10:30:01.00 0000").toString()).startsWith("TransactionEntity[");
    }
}

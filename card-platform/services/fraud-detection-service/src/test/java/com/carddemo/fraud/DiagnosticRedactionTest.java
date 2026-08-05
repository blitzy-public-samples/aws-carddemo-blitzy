package com.carddemo.fraud;

import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.entity.VelocityWindowEntity;

import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that no entity of the fraud detection service can hand a logger a transaction identifier,
 * an account identifier or a monetary amount.
 *
 * <p>No COBOL (Common Business Oriented Language) ancestor: no COBOL program renders a record for
 * a log, and this service has no ancestor of any kind.
 *
 * <p>The assessment outcome survives redaction on purpose. A risk score and a flagged verdict name
 * no cardholder and carry no money, and reading them is why anyone opens an assessment row. The two
 * identifiers that would tie that verdict to one payment and one cardholder do not survive.
 *
 * <p>Every assertion looks for the value rather than the field name, because a rendering that named
 * a field and withheld its value must pass.
 */
@DisplayName("Diagnostic redaction, the fraud detection service")
class DiagnosticRedactionTest {

    /** Sixteen digits, the width {@code TRAN-ID PIC X(16)} declares. */
    private static final String TRANSACTION_ID = "9876543210987654";

    /** Eleven digits, the width {@code XREF-ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The moment the fixtures share, so no assertion depends on the clock. */
    private static final Instant AT = Instant.parse("2024-01-15T10:30:00Z");

    @Test
    @DisplayName("the assessment rendering withholds both identifiers and keeps the verdict")
    void theAssessmentRenderingWithholdsBothIdentifiers() {
        FraudAssessmentEntity assessment = new FraudAssessmentEntity(TRANSACTION_ID, ACCOUNT_ID, 87,
                true, List.of("VELOCITY", "AMOUNT_ANOMALY"), AT);

        String rendered = assessment.toString();

        assertThat(rendered)
                .withFailMessage("the transaction identifier is the one handle a reader needs to "
                        + "join a log line to a row, and the rendering stopped carrying it")
                .contains("transactionId=" + TRANSACTION_ID);
        assertThat(rendered)
                .withFailMessage("the account identifier reached a log line")
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered).isEqualTo("FraudAssessmentEntity[transactionId=" + TRANSACTION_ID
                + ", accountId=" + EventEnvelope.WITHHELD + ", riskScore=" + EventEnvelope.WITHHELD
                + ", flagged=" + EventEnvelope.WITHHELD + "]");
        assertThat(assessment.getTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(assessment.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(assessment.getRiskScore()).isEqualTo(87);
    }

    @Test
    @DisplayName("a cleared assessment withholds its verdict and the account identifier")
    void aClearedAssessmentRendersItsOwnVerdict() {
        FraudAssessmentEntity cleared = new FraudAssessmentEntity(TRANSACTION_ID, ACCOUNT_ID, 12,
                false, List.of(), AT);

        assertThat(cleared.toString()).isEqualTo("FraudAssessmentEntity[transactionId="
                + TRANSACTION_ID + ", accountId=" + EventEnvelope.WITHHELD + ", riskScore="
                + EventEnvelope.WITHHELD + ", flagged=" + EventEnvelope.WITHHELD + "]");
        assertThat(cleared.toString()).doesNotContain(ACCOUNT_ID).doesNotContain("12");
    }

    @Test
    @DisplayName("the velocity rendering withholds the identifier and the running total")
    void theVelocityRenderingWithholdsTheIdentifierAndTheTotal() {
        VelocityWindowEntity window = new VelocityWindowEntity(ACCOUNT_ID, AT, 7,
                new BigDecimal("4321.99"), AT.plusSeconds(60));

        String rendered = window.toString();

        assertThat(rendered)
                .withFailMessage("the account identifier reached a log line")
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered)
                .withFailMessage("the running total reached a log line")
                .doesNotContain("4321.99");
        assertThat(rendered).startsWith("VelocityWindowEntity[");
        assertThat(rendered).contains("windowStart=2024-01-15T10:30:00Z");
        assertThat(rendered)
                .withFailMessage("the count a velocity decision turns on reached a log line")
                .contains("authorizationCount=" + EventEnvelope.WITHHELD);
        assertThat(rendered).contains("accountId=" + EventEnvelope.WITHHELD)
                .contains("totalAmount=" + EventEnvelope.WITHHELD);
        assertThat(window.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(window.getTotalAmount()).isEqualByComparingTo(new BigDecimal("4321.99"));
    }

    @Test
    @DisplayName("the velocity key withholds the identifier a persistence exception would print")
    void theVelocityKeyWithholdsTheIdentifier() {
        VelocityWindowEntity.VelocityWindowId key =
                new VelocityWindowEntity.VelocityWindowId(ACCOUNT_ID, AT);

        String rendered = key.toString();

        assertThat(rendered)
                .withFailMessage("a key class reaches a persistence exception message, and the "
                        + "account identifier reached it")
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered).isEqualTo("VelocityWindowId[accountId=" + EventEnvelope.WITHHELD
                + ", windowStart=2024-01-15T10:30:00Z]");
    }

    @Test
    @DisplayName("the outbox rendering withholds the aggregate identifier and the payload")
    void theOutboxRenderingWithholdsTheAggregateIdentifierAndThePayload() {
        UUID eventId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String payload = "{\"transactionId\":\"" + TRANSACTION_ID + "\",\"riskScore\":87}";
        OutboxEventEntity event =
                new OutboxEventEntity(eventId, "FraudFlagged", ACCOUNT_ID, payload, AT);

        String rendered = event.toString();

        assertThat(rendered)
                .withFailMessage("the aggregate identifier reached a log line")
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered)
                .withFailMessage("the payload reached a log line")
                .doesNotContain(payload).doesNotContain(TRANSACTION_ID);
        assertThat(rendered).contains("eventId=11111111-2222-3333-4444-555555555555");
        assertThat(rendered).contains("eventType=FraudFlagged");
        assertThat(rendered).contains("published=false");
        assertThat(rendered).contains("aggregateId=" + EventEnvelope.WITHHELD);
        assertThat(event.getAggregateId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.getPayload()).isEqualTo(payload);
    }
}

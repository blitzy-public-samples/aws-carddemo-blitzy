package com.carddemo.account.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One service-local copy of the card cross-reference record.
 *
 * <p>{@code XREF-CARD-NUM}, {@code XREF-CUST-ID} and {@code XREF-ACCT-ID} come from
 * {@code app/cpy/CVACT03Y.cpy:L5-L7}. The account update path reads this replica to derive the
 * customer identifier from the account identifier, matching the three-hop lookup in
 * {@code app/cbl/COACTVWC.cbl}. It never trusts a caller to choose an unrelated customer row.
 *
 * <p>The full card number remains the primary key because {@code KEYS(16 0)} at
 * {@code app/jcl/XREFFILE.jcl:L43} declares that key. No method renders or logs it.
 */
@Entity
@Table(name = "card_xref",
        indexes = {
                @Index(name = "idx_card_xref_account_id", columnList = "account_id"),
                @Index(name = "ix_card_xref_observed_at", columnList = "observed_at")
        })
public class CardCrossReferenceEntity {

    /** Full card number, the sixteen-character source primary key. */
    @Id
    @Column(name = "card_number", nullable = false, length = PicClause.XREF_CARD_NUM_WIDTH)
    private String cardNumber;

    /** Customer identifier, kept as nine characters so leading zeros survive. */
    @Column(name = "customer_id", nullable = false,
            length = PicClause.XREF_CUST_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_CUST_ID_WIDTH + ")")
    private String customerId;

    /** Account identifier, kept as the eleven-character alternate-index key. */
    @Column(name = "account_id", nullable = false,
            length = PicClause.XREF_ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_ACCT_ID_WIDTH + ")")
    private String accountId;

    /** Event that most recently refreshed this replica row, or null for a seed row. */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    /** Producer timestamp of the most recent refresh, or null for a seed row. */
    @Column(name = "source_occurred_at")
    private Instant sourceOccurredAt;

    /** Time this service last observed the row. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Constructor used by the persistence provider. */
    protected CardCrossReferenceEntity() {
    }

    /**
     * Builds one seeded or refreshed relationship.
     *
     * @param cardNumber full card number, exactly sixteen characters
     * @param customerId customer identifier, exactly nine digits
     * @param accountId account identifier, exactly eleven digits
     * @param observedAt time this service observed the relationship
     */
    public CardCrossReferenceEntity(String cardNumber, String customerId, String accountId,
            Instant observedAt) {
        this.cardNumber = requireWidth("cardNumber", cardNumber, PicClause.XREF_CARD_NUM_WIDTH);
        this.customerId = requireDigits("customerId", customerId, PicClause.XREF_CUST_ID_WIDTH);
        this.accountId = requireDigits("accountId", accountId, PicClause.XREF_ACCT_ID_WIDTH);
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    /** Returns the full source key. Callers must not log or serialize it. */
    public String getCardNumber() {
        return cardNumber;
    }

    /** Returns the nine-digit customer identifier. */
    public String getCustomerId() {
        return customerId;
    }

    /** Returns the eleven-digit account identifier. */
    public String getAccountId() {
        return accountId;
    }

    /** Returns the event that last refreshed the row, or null for a seed row. */
    public UUID getSourceEventId() {
        return sourceEventId;
    }

    /** Returns the producer time of the last refresh, or null for a seed row. */
    public Instant getSourceOccurredAt() {
        return sourceOccurredAt;
    }

    /** Returns when this service last observed the row. */
    public Instant getObservedAt() {
        return observedAt;
    }

    private static String requireWidth(String field, String value, int width) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " must be exactly " + width
                    + " characters wide");
        }
        return value;
    }

    private static String requireDigits(String field, String value, int width) {
        requireWidth(field, value, width);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(field + " must contain digits only");
            }
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardCrossReferenceEntity that)) {
            return false;
        }
        return Objects.equals(cardNumber, that.getCardNumber());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(cardNumber);
    }
}

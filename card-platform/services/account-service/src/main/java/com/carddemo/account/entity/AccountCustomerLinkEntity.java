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
 * The account-to-customer relationship this service reads, and no card number.
 *
 * <p>{@code XREF-CUST-ID} and {@code XREF-ACCT-ID} come from {@code app/cpy/CVACT03Y.cpy:L6-L7}.
 * The account update path reads this row to derive the customer identifier from the account
 * identifier, matching the three-hop lookup in {@code app/cbl/COACTVWC.cbl}. It never trusts a
 * caller to choose an unrelated customer row.
 *
 * <p>{@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5} is deliberately absent.
 * It was the source primary key and this service reads no card, so replicating it here stored a
 * Primary Account Number that nothing on this side would ever read. The card and authorization
 * services hold the cross-reference whole, because each of them keys on a card.
 *
 * <p>The account identifier is the key. The source alternate index over it is non-unique — one
 * account reaches every card it holds at {@code KEYS(11 25)} in {@code app/jcl/XREFFILE.jcl:L72-L77}
 * — and every card of one account names one customer, so one row per account loses no relationship
 * and removes the ordering the previous query needed to pick between rows.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "account_customer_link",
        indexes = {
                @Index(name = "ix_account_customer_link_observed_at", columnList = "observed_at")
        })
public class AccountCustomerLinkEntity {

    /** Account identifier, the eleven-character key this service resolves by. */
    @Id
    @Column(name = "account_id", nullable = false,
            length = PicClause.XREF_ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_ACCT_ID_WIDTH + ")")
    private String accountId;

    /** Customer identifier, kept as nine characters so leading zeros survive. */
    @Column(name = "customer_id", nullable = false,
            length = PicClause.XREF_CUST_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_CUST_ID_WIDTH + ")")
    private String customerId;

    /** Event that most recently refreshed this row, or null for a carried-across row. */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    /** Producer timestamp of the most recent refresh, or null for a carried-across row. */
    @Column(name = "source_occurred_at")
    private Instant sourceOccurredAt;

    /** Time this service last observed the relationship. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Constructor used by the persistence provider. */
    protected AccountCustomerLinkEntity() {
    }

    /**
     * Builds one relationship.
     *
     * @param accountId account identifier, exactly eleven digits
     * @param customerId customer identifier, exactly nine digits
     * @param observedAt time this service observed the relationship
     */
    public AccountCustomerLinkEntity(String accountId, String customerId, Instant observedAt) {
        this.accountId = requireDigits("accountId", accountId, PicClause.XREF_ACCT_ID_WIDTH);
        this.customerId = requireDigits("customerId", customerId, PicClause.XREF_CUST_ID_WIDTH);
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    /** Returns the eleven-digit account identifier. */
    public String getAccountId() {
        return accountId;
    }

    /** Returns the nine-digit customer identifier. */
    public String getCustomerId() {
        return customerId;
    }

    /** Returns the event that last refreshed the row, or null for a carried-across row. */
    public UUID getSourceEventId() {
        return sourceEventId;
    }

    /** Returns the producer time of the last refresh, or null for a carried-across row. */
    public Instant getSourceOccurredAt() {
        return sourceOccurredAt;
    }

    /** Returns when this service last observed the relationship. */
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
        if (!(other instanceof AccountCustomerLinkEntity that)) {
            return false;
        }
        return Objects.equals(accountId, that.getAccountId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }
}

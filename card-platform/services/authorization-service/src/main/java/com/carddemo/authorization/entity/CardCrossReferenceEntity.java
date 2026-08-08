package com.carddemo.authorization.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code card_xref}, the cross-reference from a card number to an account.
 *
 * <p>The authorization service owns this table. The card service keeps a separate replica of the
 * same source record in its own schema, and no service reads another service's schema.
 *
 * <p>Transformed from {@code 01 CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:L4}, version
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} at {@code app/cpy/CVACT03Y.cpy:L10}. The copybook
 * header at {@code app/cpy/CVACT03Y.cpy:L2} states RECLN 50, and the four field widths sum to
 * that length: 16 plus 9 plus 11 plus 14.
 *
 * <p>Three of the four fields carry a column. The trailing {@code FILLER PIC X(14)} at
 * {@code app/cpy/CVACT03Y.cpy:L8} carries none. Every width below comes from {@link PicClause}.
 *
 * <pre>
 * copybook field   picture     line   column        type            note
 * XREF-CARD-NUM    PIC X(16)   L5     card_number   VARCHAR(16)     primary key
 * XREF-CUST-ID     PIC 9(09)   L6     customer_id   CHAR(9)
 * XREF-ACCT-ID     PIC 9(11)   L7     account_id    CHAR(11)        indexed, not unique
 * FILLER           PIC X(14)   L8     none
 * </pre>
 *
 * <p>Both identifiers hold a fixed count of digits as characters rather than as a number. Every
 * one of the fifty rows of {@code app/data/ASCII/cardxref.txt} opens both identifiers with a zero,
 * so a numeric column would drop that padding and return a shorter value than the source holds.
 * The account identifier is also the Kafka message key, so the padded form and the numeric form
 * are not interchangeable.
 *
 * <p>The key strategy comes from the Job Control Language (JCL) member that defines the
 * Virtual Storage Access Method (VSAM) dataset. {@code KEYS(16 0)} at
 * {@code app/jcl/XREFFILE.jcl:L43} declares a sixteen-byte key at offset zero, which is
 * {@code XREF-CARD-NUM}. {@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74} declares an
 * eleven-byte alternate key at offset 25, where {@code XREF-ACCT-ID} starts.
 * {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:L75} admits many rows per account, and one
 * account holds many cards.
 *
 * <p>Both source read paths survive the transformation. {@code app/cbl/COTRN02C.cbl:L208} reads
 * through the alternate index on an account identifier, and {@code app/cbl/COTRN02C.cbl:L222}
 * reads through the primary key on a card number. The first decline rule reads this table:
 * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns reject reason 100 with the text
 * {@code INVALID CARD NUMBER FOUND} when the keyed read returns an invalid-key condition.
 *
 * <p>{@code src/main/resources/db/migration/V1__schema.sql:L18-L29} creates the table and the
 * index {@code idx_card_xref_account_id}. Hibernate runs under {@code ddl-auto: validate} at
 * {@code src/main/resources/application.yml:L35}, so a column name, type, precision, scale or
 * nullability that drifts from the migration fails at startup against PostgreSQL 18.4.
 *
 * <p>The migration leaves every object unqualified.
 * {@code spring.jpa.properties.hibernate.default_schema} at
 * {@code src/main/resources/application.yml:L38} names the schema at run time. The {@link Table}
 * annotation below sets no {@code schema} attribute.
 *
 * <p>{@code card_number} holds the full Primary Account Number (PAN), and
 * {@link #getCardNumber()} returns all sixteen characters of it. The card cross-reference lookup
 * keys on that value. A caller passes it through {@code com.carddemo.cobol.PanMasker} before it
 * reaches an event payload, a log line or an application programming interface response. No
 * method here renders the row as text, no field here holds a card verification value, and no
 * statement here logs.
 *
 * <p>The constructor rejects a null argument, a card number of any width other than sixteen, and
 * an identifier carrying a fractional part or a negative sign. Any character may occupy any of
 * those sixteen positions, matching {@code PIC X}. The constructor adds no checksum. The source
 * tests a card number for the numeric class only, at {@code app/cbl/COCRDUPC.cbl:L784}, and its
 * message at {@code app/cbl/COCRDUPC.cbl:L194} names sixteen digits. The lookup paragraph at
 * {@code app/cbl/CBTRN02C.cbl:L380-L392} tests no card-number format at all.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "card_xref",
        indexes = {
                @Index(name = "idx_card_xref_account_id",
                        columnList = "account_id, card_number"),
                @Index(name = "ix_card_xref_observed_at", columnList = "observed_at")})
public class CardCrossReferenceEntity {


    /**
     * Full card number, {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}.
     *
     * <p>Column {@code card_number VARCHAR(16) NOT NULL}, the primary key from
     * {@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43}. A caller supplies the value; no
     * sequence and no generator assigns one. All fifty rows of
     * {@code app/data/ASCII/cardxref.txt} carry sixteen numeric characters here.
     */
    @Id
    @Column(name = "card_number", nullable = false, length = PicClause.XREF_CARD_NUM_WIDTH)
    private String cardNumber;

    /**
     * Customer identifier, {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}.
     *
     * <p>Column {@code customer_id CHAR(9) NOT NULL}. The account view program carries this
     * field from the cross-reference record at {@code app/cbl/COACTVWC.cbl:L739} and keys its
     * customer read on it at {@code app/cbl/COACTVWC.cbl:L828-L829}.
     *
     * <p>The field is a {@link String} and not a number. {@code PIC 9(09)} is a display field
     * nine characters wide, and every one of the fifty records of
     * {@code app/data/ASCII/cardxref.txt} fills all nine with leading zeros. A numeric column
     * would store such a value as a number and return it without its padding, which is no longer
     * the nine-character value the source record carries. The column check constraint
     * {@code ck_card_xref_customer_id_digits} holds the width and the digit class.
     */
    @Column(name = "customer_id", nullable = false,
            length = PicClause.XREF_CUST_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_CUST_ID_WIDTH + ")")
    private String customerId;

    /**
     * Account identifier, {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>Column {@code account_id CHAR(11) NOT NULL}, indexed and not unique by
     * {@code idx_card_xref_account_id}. The stored value is already the eleven-character
     * zero-padded form the event envelope carries as its aggregate identifier and Kafka
     * message key. No rendering step stands between the column and the key.
     *
     * <p>The field is a {@link String} for the same reason {@link #customerId} is. The
     * alternate-index key at {@code KEYS(11,25)} in {@code app/jcl/XREFFILE.jcl:L74} occupies
     * eleven bytes, and a numeric column would return a padded record's value without its leading
     * zeros. The column check constraint
     * {@code ck_card_xref_account_id_digits} holds the width and the digit class.
     */
    @Column(name = "account_id", nullable = false,
            length = PicClause.XREF_ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_ACCT_ID_WIDTH + ")")
    private String accountId;

    // ------------------------------------------------------------------------------------
    // Replica freshness. No COBOL ancestor: the source has no replica to keep current.
    // app/cbl/CBTRN02C.cbl:L382 reads the cross-reference dataset itself, so it cannot be
    // stale.
    // A copy that cannot say how old it is cannot be refused when it is too old, which is the
    // whole point of the three columns below.
    // ------------------------------------------------------------------------------------

    /**
     * The state-change event that last wrote this row, or null for a row loaded by
     * {@code V2__seed.sql}.
     *
     * <p>The seed is the initial load rather than an event, so it names none. A check constraint in
     * {@code src/main/resources/db/migration/V1__schema.sql} ties this column to
     * {@link #getSourceOccurredAt()}: a row carries both halves of its provenance or neither.
     */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    /**
     * When the event that last wrote this row occurred, or null for a seeded row.
     *
     * <p>This is the ordering value, and it is the producer's clock rather than this service's. An
     * update whose event did not occur after the stored one is discarded, which is how an
     * out-of-order delivery leaves the row alone instead of moving it backwards.
     */
    @Column(name = "source_occurred_at")
    private Instant sourceOccurredAt;

    /**
     * When this row was last written, by seed or by event. Never null.
     *
     * <p>A freshness check reads this column and nothing else, so it always has a value to compare.
     * A seeded row carries the moment the migration ran, which is the truthful answer for an
     * initial load.
     */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /**
     * No-argument constructor for the persistence provider.
     *
     * <p>Hibernate calls this constructor to materialise a row, then populates the four fields
     * directly. Application code calls
     * {@link #CardCrossReferenceEntity(String, String, String, Instant)}.
     */
    protected CardCrossReferenceEntity() {
    }

    /**
     * Builds one cross-reference row from the four mapped fields.
     *
     * @param cardNumber the full card number, exactly
     *                   {@value PicClause#XREF_CARD_NUM_WIDTH} characters wide
     * @param customerId the customer identifier, exactly
     *                   {@value PicClause#XREF_CUST_ID_WIDTH} digits
     * @param accountId  the account identifier, exactly
     *                   {@value PicClause#XREF_ACCT_ID_WIDTH} digits
     * @param observedAt when this service wrote the row, the moment a freshness check measures
     *                   staleness against
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the card number is not
     *                                  {@value PicClause#XREF_CARD_NUM_WIDTH} characters wide,
     *                                  or if either identifier is the wrong width or holds a
     *                                  character outside {@code 0} through {@code 9}
     */
    public CardCrossReferenceEntity(String cardNumber, String customerId,
            String accountId, Instant observedAt) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");

        if (cardNumber.length() != PicClause.XREF_CARD_NUM_WIDTH) {
            throw new IllegalArgumentException("cardNumber must be exactly "
                    + PicClause.XREF_CARD_NUM_WIDTH + " characters wide, found width "
                    + cardNumber.length());
        }
        requireDigits("customerId", customerId, PicClause.XREF_CUST_ID_WIDTH);
        requireDigits("accountId", accountId, PicClause.XREF_ACCT_ID_WIDTH);

        this.cardNumber = cardNumber;
        this.customerId = customerId;
        this.accountId = accountId;
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    /**
     * Rejects an identifier that is the wrong width or carries a character outside
     * {@code 0} through {@code 9}.
     *
     * <p>A {@code PIC 9(n)} display field is exactly n characters wide and holds only digits.
     * Both halves of that contract are checked here, and the column check constraint repeats
     * them in the database. Neither failure message carries a character of the rejected value.
     * The width message reports a length and the digit message reports a position, which keeps
     * a customer identifier out of any log line this class reaches.
     *
     * @param fieldName the field under check, named in any failure message
     * @param value     the identifier to check
     * @param width     the exact number of digits the Picture clause declares
     * @throws IllegalArgumentException if the width is wrong or a character is not a digit
     */
    private static void requireDigits(String fieldName, String value, int width) {
        if (value.length() != width) {
            throw new IllegalArgumentException(fieldName + " must be exactly " + width
                    + " digits wide, found width " + value.length());
        }
        for (int position = 0; position < width; position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(fieldName
                        + " must hold digits only, found a character outside 0 through 9 at "
                        + "position " + (position + 1));
            }
        }
    }

    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Returns the customer identifier this card belongs to.
     *
     * @return the value of column {@code customer_id}, exactly
     *         {@value PicClause#XREF_CUST_ID_WIDTH} digits
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Returns the account identifier this card belongs to.
     *
     * @return the value of column {@code account_id}, exactly
     *         {@value PicClause#XREF_ACCT_ID_WIDTH} digits and ready to use as the message key
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Compares on {@code card_number} alone, the primary key.
     *
     * <p>The test admits any subclass and reads the argument through
     * {@link #getCardNumber()}, so a Hibernate proxy equals the row it stands for.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a cross-reference row carrying the same card
     *         number
     */
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

    /**
     * Hashes {@code card_number} alone, matching {@link #equals(Object)}.
     *
     * <p>A caller supplies the primary key at construction and no database sequence assigns one,
     * so the hash holds steady from construction through persistence.
     *
     * @return the hash of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(cardNumber);
    }

    /**
     * Returns the state-change event that last wrote this row.
     *
     * @return the event identifier, or null for a seeded row
     */
    public UUID getSourceEventId() {
        return sourceEventId;
    }

    /**
     * Returns when the event that last wrote this row occurred.
     *
     * @return the producer-side time, or null for a seeded row
     */
    public Instant getSourceOccurredAt() {
        return sourceOccurredAt;
    }

    /**
     * Returns when this row was last written.
     *
     * @return the observation time, never null once the row has been read from the database
     */
    public Instant getObservedAt() {
        return observedAt;
    }

    /**
     * Reports whether this row was observed recently enough to authorize against.
     *
     * <p>The caller supplies the window, so the policy lives in configuration and not here. A row
     * with no observation time is reported stale.
     *
     * @param now       the current time
     * @param maxAge    how old an observation may be and still count as fresh
     * @return true when this row was observed within {@code maxAge} of {@code now}
     * @throws NullPointerException if {@code now} or {@code maxAge} is null
     */
    public boolean isFreshAt(Instant now, Duration maxAge) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxAge, "maxAge");
        if (observedAt == null) {
            return false;
        }
        return !observedAt.isBefore(now.minus(maxAge));
    }

    /**
     * Records that a state-change event wrote this row, unless that event is not newer than the one
     * already recorded.
     *
     * <p>Kafka orders messages within a partition and every event for one account carries that
     * account as its key, so an out-of-order delivery is unusual rather than routine. It is still
     * possible: a redelivery after a rebalance can arrive behind a newer event that a different
     * consumer instance already applied. Comparing the producer's clock is what makes applying the
     * older one a no-op instead of a regression.
     *
     * @param eventId    the event that carried the change
     * @param occurredAt when that event occurred, from its envelope
     * @param observedAt when this service wrote the row
     * @return true when the row was marked, and false when the event was not newer than the one
     *         already recorded and nothing changed
     * @throws NullPointerException if any argument is null
     */
    public boolean markObserved(UUID eventId, Instant occurredAt, Instant observedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(observedAt, "observedAt");
        if (this.sourceOccurredAt != null && !occurredAt.isAfter(this.sourceOccurredAt)) {
            return false;
        }
        this.sourceEventId = eventId;
        this.sourceOccurredAt = occurredAt;
        this.observedAt = observedAt;
        return true;
    }
}

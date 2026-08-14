package com.carddemo.card.entity;

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
 * One row of {@code card_xref}, this service's private replica of the card-to-account
 * cross-reference.
 *
 * <p>Transformed from {@code 01 CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:L4}, version
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} at {@code app/cpy/CVACT03Y.cpy:L10}. The copybook
 * header at {@code app/cpy/CVACT03Y.cpy:L2} states RECLN 50, and the four field widths sum to
 * that length: 16 plus 9 plus 11 plus 14. Three of the four fields carry a column.</p>
 *
 * <pre>
 * copybook field   picture     line   column        type            note
 * XREF-CARD-NUM    PIC X(16)   L5     card_number   CHAR(16)        primary key
 * XREF-CUST-ID     PIC 9(09)   L6     customer_id   CHAR(9)
 * XREF-ACCT-ID     PIC 9(11)   L7     account_id    CHAR(11)        indexed, not unique
 * FILLER           PIC X(14)   L8     none
 * </pre>
 *
 * <p>DEVIATION. The trailing {@code FILLER PIC X(14)} at {@code app/cpy/CVACT03Y.cpy:L8} is
 * dropped and carries no column.</p>
 *
 * <p>DEVIATION. Column {@code card_number} is {@code CHAR(16)} here, and the authorization
 * service declares {@code VARCHAR(16)} on its own replica of the same source record. The two
 * replicas are independently owned, and no service reads another service's schema.</p>
 *
 * <p>DEVIATION. The per-service data-ownership table mandates this replica, and no live
 * card-program behaviour translates onto it.</p>
 *
 * <p>Every cross-reference inclusion in the card programs is switched off or absent.
 * {@code app/cbl/COCRDUPC.cbl:L356} and {@code app/cbl/COCRDSLC.cbl:L237} both read
 * {@code *COPY CVACT03Y.}, commented out. {@code app/cbl/COCRDLIC.cbl} names CVACT03Y nowhere,
 * and its one card-copybook line is {@code COPY CVACT02Y.} at {@code app/cbl/COCRDLIC.cbl:L290}.
 * The Customer Information Control System (CICS) file definitions {@code CCXREF} at
 * {@code app/csd/CARDDEMO.CSD:L37} and {@code CXACAIX} at {@code app/csd/CARDDEMO.CSD:L63} appear
 * in no card program.</p>
 *
 * <p>No listener of this service consumes an event, so no event maintains these rows: they hold
 * what {@code src/main/resources/db/migration/V2__seed.sql} loaded. Two methods mutate a field.
 * {@link #markObserved(UUID, Instant, Instant)} writes the three provenance columns and no mapping
 * column. {@link #reconcileAccountId(String, Instant)} writes {@code accountId} and
 * {@code observedAt}, and {@code domain/CardCrossReferenceReconciler} is its one caller, reached
 * from a card update.</p>
 *
 * <p>The key strategy comes from the Job Control Language (JCL) member that defines the Virtual
 * Storage Access Method (VSAM) dataset. {@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43}
 * declares a sixteen-byte key at offset zero, which is {@code XREF-CARD-NUM}, and
 * {@code RECORDSIZE(50 50)} at {@code app/jcl/XREFFILE.jcl:L44} fixes the row width.
 * {@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74} declares an eleven-byte Alternate Index
 * (AIX) key at offset 25, where {@code XREF-ACCT-ID} starts. {@code NONUNIQUEKEY} at
 * {@code app/jcl/XREFFILE.jcl:L75} admits many rows per account, and one account holds many
 * cards.</p>
 *
 * <p>The card list program treats a duplicate on an account-keyed read as success.
 * {@code app/cbl/COCRDLIC.cbl:L1157-L1158} pairs {@code WHEN DFHRESP(NORMAL)} with
 * {@code WHEN DFHRESP(DUPREC)} on one branch, and L1208 to L1209, L1305 to L1306 and L1333 to
 * L1334 repeat that pairing. The index declared below sets no {@code unique} attribute.</p>
 *
 * <p>{@code src/main/resources/db/migration/V1__schema.sql:L44-L49} creates the table, and L53
 * creates the index {@code idx_card_xref_account_id}. Flyway owns every Data Definition Language
 * (DDL) statement, and Hibernate runs under {@code ddl-auto: validate} at
 * {@code src/main/resources/application.yml:L39}. A column name, type, precision, scale or
 * nullability that drifts from the migration stops start-up against PostgreSQL 18.4.</p>
 *
 * <p>The migration leaves every object unqualified.
 * {@code spring.jpa.properties.hibernate.default_schema} at
 * {@code src/main/resources/application.yml:L42} names the schema at run time from
 * {@code SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA}, which
 * {@code card-platform/docker-compose.yml:L492} sets. The {@link Table} annotation below sets no
 * {@code schema} attribute.</p>
 *
 * <p>{@code card_number} holds the full Primary Account Number (PAN), and
 * {@link #getCardNumber()} returns all sixteen characters. The cross-reference lookup keys on
 * that value, and the outbox writer of this service masks it at the serialization boundary. A
 * caller passes it through {@code com.carddemo.cobol.PanMasker} before it reaches an event
 * payload, a log line or an application programming interface response. No method here renders
 * the row as text, no column here holds a card verification value, and no statement here
 * logs.</p>
 *
 * <p>The constructor rejects a null argument, a card number of any width other than sixteen, and
 * an identifier carrying a fractional part or a negative sign. Any character may occupy any of
 * those sixteen positions, matching {@code PIC X}. The constructor adds no checksum. The one
 * card-number rule in the source names sixteen digits, at
 * {@code app/cbl/COCRDUPC.cbl:L194}.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "card_xref",
        indexes = {
                @Index(name = "idx_card_xref_account_id", columnList = "account_id"),
                @Index(name = "ix_card_xref_observed_at", columnList = "observed_at")})
public class CardCrossReferenceEntity {


    /**
     * Full card number. {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}.
     *
     * <p>Column {@code card_number CHAR(16) NOT NULL}, the primary key from {@code KEYS(16 0)} at
     * {@code app/jcl/XREFFILE.jcl:L43}. A caller supplies the value, and no sequence and no
     * generator assigns one. All 50 records of {@code app/data/ASCII/cardxref.txt} carry sixteen
     * numeric characters here, the first of them stated masked as {@code ************5740}. The
     * fixture holds it in full, and a comment repeating it would be a card number a reader
     * copies out of documentation.</p>
     *
     * <p>PostgreSQL reports {@code CHAR} as {@code bpchar} through Java Database Connectivity
     * (JDBC) metadata, and {@link Column#columnDefinition()} names it verbatim. The start-up
     * check compares the mapped type with the reported type and accepts a match on that
     * name.</p>
     */
    @Id
    @Column(name = "card_number", nullable = false,
            length = PicClause.XREF_CARD_NUM_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_CARD_NUM_WIDTH + ")")
    private String cardNumber;

    /**
     * Customer identifier. {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}.
     *
     * <p>Column {@code customer_id CHAR(9) NOT NULL}, nine bytes at offset 16 of the 50-byte
     * record. No key and no index in {@code app/jcl/XREFFILE.jcl} names this field.</p>
     *
     * <p>The field is a {@link String} and not a number. {@code PIC 9(09)} is a display field nine
     * characters wide, and a numeric column drops the leading zeros a value there carries. The
     * column check constraint {@code ck_card_xref_customer_id_digits} holds the width and the digit
     * class.</p>
     */
    @Column(name = "customer_id", nullable = false,
            length = PicClause.XREF_CUST_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_CUST_ID_WIDTH + ")")
    private String customerId;

    /**
     * Account identifier. {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>Column {@code account_id CHAR(11) NOT NULL}, covered by the non-unique index
     * {@code idx_card_xref_account_id}. Eleven bytes at offset 25, which is where
     * {@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74} points.</p>
     *
     * <p>This picture clause fixes the account-identifier width for this service. Column
     * {@code outbox_event.aggregate_id} is {@code CHAR(11)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}, and the shared event envelope holds
     * its aggregate identifier to eleven digits. This column now holds the same eleven characters
     * those two carry, so the value moves from row to event key unchanged and no rendering step
     * stands between them.</p>
     */
    @Column(name = "account_id", nullable = false,
            length = PicClause.XREF_ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_ACCT_ID_WIDTH + ")")
    private String accountId;

    // ------------------------------------------------------------------------------------
    // Replica freshness. No COBOL ancestor: the source has no replica to keep current.
    // app/cbl/COCRDSLC.cbl reads the cross-reference dataset itself, so it cannot be stale.
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
     * <p>Hibernate calls this constructor to materialise a row, then populates the fields
     * directly, so a row read from the database reaches no guard below. Application code calls
     * {@link #CardCrossReferenceEntity(String, String, String, Instant)}.</p>
     */
    protected CardCrossReferenceEntity() {
    }

    /**
     * One cross-reference row: the three mapped fields in copybook order, then the moment this
     * service observed them.
     *
     * @param cardNumber the full card number, exactly
     *                   {@value PicClause#XREF_CARD_NUM_WIDTH} characters wide
     * @param customerId the customer identifier, exactly
     *                   {@value PicClause#XREF_CUST_ID_WIDTH} digits
     * @param accountId  the account identifier, exactly
     *                   {@value PicClause#XREF_ACCT_ID_WIDTH} digits
     * @param observedAt when this service observed the row, or {@code null} for a seeded row that
     *                   no observation has refreshed
     * @throws NullPointerException     if any of the first three arguments is {@code null}
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
     * Rejects an identifier that is the wrong width or carries a character outside {@code 0}
     * through {@code 9}.
     *
     * <p>A {@code PIC 9(n)} display field is exactly n characters wide and holds only digits.
     * Both halves of that contract are checked here, and the column check constraint repeats them
     * in the database. Neither failure message carries a character of the rejected value. The
     * width message reports a length and the digit message reports a position.</p>
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
     * @return the value of column {@code customer_id}
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Returns the account identifier this card belongs to.
     *
     * @return the value of column {@code account_id}
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Compares on {@code card_number} alone, the primary key.
     *
     * <p>The test admits any subclass and reads the argument through
     * {@link #getCardNumber()}, so a Hibernate proxy equals the row it stands for.</p>
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
     * so the hash holds steady from construction through persistence.</p>
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

    /**
     * Brings the account identifier of this replica row into step with the card row that owns the
     * mapping, and records that the row was observed.
     *
     * <p>This service owns {@code card}, and {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6} is the authoritative card-to-account mapping. The replica
     * carries the same mapping from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}, taken from a fixture, and nothing in the source keeps the two
     * files in step: {@code app/cbl/COCRDUPC.cbl:L356} reads {@code *COPY CVACT03Y.} commented out,
     * so the update program never opened the cross-reference at all. A divergence between the two is
     * therefore possible in the source and was possible here, and this method is what closes it.
     *
     * <p>The observation time moves whether or not the mapping did, because the row was read and
     * compared either way, and a reader of {@code observed_at} is asking when this service last
     * confirmed the row rather than when it last changed it. The event columns are left alone: no
     * event carried this correction, and claiming one would misreport where the value came from.
     *
     * @param authoritativeAccountId the account identifier the owning card row holds, exactly eleven
     *                               digits with leading zeros
     * @param observedAt             when this service compared the row
     * @return true when the mapping disagreed and was corrected, and false when it already agreed
     * @throws NullPointerException if either argument is null
     */
    public boolean reconcileAccountId(String authoritativeAccountId, Instant observedAt) {
        Objects.requireNonNull(authoritativeAccountId, "authoritativeAccountId");
        Objects.requireNonNull(observedAt, "observedAt");

        this.observedAt = observedAt;
        if (authoritativeAccountId.equals(this.accountId)) {
            return false;
        }
        this.accountId = authoritativeAccountId;
        return true;
    }
}

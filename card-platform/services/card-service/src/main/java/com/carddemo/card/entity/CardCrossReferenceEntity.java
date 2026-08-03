package com.carddemo.card.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

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
 * XREF-CUST-ID     PIC 9(09)   L6     customer_id   NUMERIC(9,0)
 * XREF-ACCT-ID     PIC 9(11)   L7     account_id    NUMERIC(11,0)   indexed, not unique
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
 * <p>Events keep these rows current. No method here mutates a field, and an event-driven upsert
 * replaces a row whole.</p>
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
 * <p>The three deviations above, and every other decision behind this class, are recorded in
 * {@code card-platform/docs/decision-log.md}.</p>
 */
@Entity
@Table(name = "card_xref",
        indexes = @Index(name = "idx_card_xref_account_id", columnList = "account_id"))
public class CardCrossReferenceEntity {

    /**
     * Digits after the decimal point in both identifier columns.
     * {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6} and
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} are integer display
     * numerics, and {@link PicClause} declares no scale constant for either one.
     */
    private static final int IDENTIFIER_SCALE = 0;

    /**
     * Full card number. {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}.
     *
     * <p>Column {@code card_number CHAR(16) NOT NULL}, the primary key from {@code KEYS(16 0)} at
     * {@code app/jcl/XREFFILE.jcl:L43}. A caller supplies the value, and no sequence and no
     * generator assigns one. All 50 records of {@code app/data/ASCII/cardxref.txt} carry sixteen
     * numeric characters here, the first of them {@code 0500024453765740}.</p>
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
     * <p>Column {@code customer_id NUMERIC(9,0) NOT NULL}, nine bytes at offset 16 of the 50-byte
     * record. No key and no index in {@code app/jcl/XREFFILE.jcl} names this field. Record one of
     * {@code app/data/ASCII/cardxref.txt} carries {@code 000000050} here, and the column holds
     * the value 50.</p>
     */
    @Column(name = "customer_id", nullable = false,
            precision = PicClause.XREF_CUST_ID_WIDTH, scale = IDENTIFIER_SCALE)
    private BigDecimal customerId;

    /**
     * Account identifier. {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>Column {@code account_id NUMERIC(11,0) NOT NULL}, covered by the non-unique index
     * {@code idx_card_xref_account_id}. Eleven bytes at offset 25, which is where
     * {@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74} points.</p>
     *
     * <p>This picture clause fixes the account-identifier width for the whole platform. Column
     * {@code outbox_event.aggregate_id} is {@code VARCHAR(11)} at
     * {@code src/main/resources/db/migration/V1__schema.sql:L66}, and the shared event envelope
     * holds its aggregate identifier to eleven digits. The outbox writer renders the
     * eleven-character zero-padded form that the Kafka message key carries; this column holds the
     * numeric value and no padded text.</p>
     */
    @Column(name = "account_id", nullable = false,
            precision = PicClause.XREF_ACCT_ID_WIDTH, scale = IDENTIFIER_SCALE)
    private BigDecimal accountId;

    /**
     * No-argument constructor for the persistence provider.
     *
     * <p>Hibernate calls this constructor to materialise a row, then populates the three fields
     * directly. A row read from the database therefore reaches no guard below. Application code
     * calls {@link #CardCrossReferenceEntity(String, BigDecimal, BigDecimal)}.</p>
     */
    protected CardCrossReferenceEntity() {
    }

    /**
     * Builds one cross-reference row from the three mapped fields, in copybook order.
     *
     * @param cardNumber the full card number, exactly
     *                   {@value PicClause#XREF_CARD_NUM_WIDTH} characters wide
     * @param customerId the customer identifier, an integer with no negative sign
     * @param accountId  the account identifier, an integer with no negative sign
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the card number is not
     *                                  {@value PicClause#XREF_CARD_NUM_WIDTH} characters wide,
     *                                  or if either identifier carries a fractional part or a
     *                                  negative sign
     */
    public CardCrossReferenceEntity(String cardNumber, BigDecimal customerId,
            BigDecimal accountId) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");

        if (cardNumber.length() != PicClause.XREF_CARD_NUM_WIDTH) {
            throw new IllegalArgumentException("cardNumber must be exactly "
                    + PicClause.XREF_CARD_NUM_WIDTH + " characters wide, found width "
                    + cardNumber.length());
        }
        requireUnsignedInteger("customerId", customerId);
        requireUnsignedInteger("accountId", accountId);

        this.cardNumber = cardNumber;
        this.customerId = customerId;
        this.accountId = accountId;
    }

    /**
     * Rejects an identifier that carries a fractional part or a negative sign.
     *
     * <p>This method reads {@link BigDecimal#scale()} and {@link BigDecimal#signum()} and
     * computes nothing. {@code com.carddemo.cobol.CobolDecimal} owns every scale change on this
     * platform, and no method of this class performs one.</p>
     *
     * @param fieldName the field under check, named in any failure message
     * @param value     the identifier to check
     * @throws IllegalArgumentException if the value carries a fractional part or a negative sign
     */
    private static void requireUnsignedInteger(String fieldName, BigDecimal value) {
        if (value.scale() != IDENTIFIER_SCALE) {
            throw new IllegalArgumentException(fieldName + " must be an integer with scale "
                    + IDENTIFIER_SCALE + ", found scale " + value.scale());
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative, found "
                    + value.toPlainString());
        }
    }

    /**
     * Returns the full card number, all {@value PicClause#XREF_CARD_NUM_WIDTH} characters of it.
     *
     * <p>The value is the unmasked Primary Account Number. A caller that publishes it or logs it
     * passes it through {@code com.carddemo.cobol.PanMasker} first.</p>
     *
     * @return the value of column {@code card_number}
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Returns the customer identifier this card belongs to.
     *
     * @return the value of column {@code customer_id}
     */
    public BigDecimal getCustomerId() {
        return customerId;
    }

    /**
     * Returns the account identifier this card belongs to.
     *
     * @return the value of column {@code account_id}
     */
    public BigDecimal getAccountId() {
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
}

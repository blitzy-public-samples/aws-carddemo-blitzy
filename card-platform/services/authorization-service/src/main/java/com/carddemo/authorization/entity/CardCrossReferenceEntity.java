package com.carddemo.authorization.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

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
 * XREF-CUST-ID     PIC 9(09)   L6     customer_id   NUMERIC(9,0)
 * XREF-ACCT-ID     PIC 9(11)   L7     account_id    NUMERIC(11,0)   indexed, not unique
 * FILLER           PIC X(14)   L8     none
 * </pre>
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
 * <p>Design decisions and every deviation from the copybook:
 * {@code card-platform/docs/decision-log.md}. Field-by-field provenance, the omitted
 * {@code FILLER} included: {@code card-platform/docs/traceability-matrix.md}. The absent checksum
 * is register item 24 in {@code card-platform/docs/business-rule-flags.md}.
 */
@Entity
@Table(name = "card_xref",
        indexes = @Index(name = "idx_card_xref_account_id", columnList = "account_id"))
public class CardCrossReferenceEntity {

    /**
     * Digits after the decimal point in both identifier columns. {@code XREF-CUST-ID PIC 9(09)}
     * at {@code app/cpy/CVACT03Y.cpy:L6} and {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7} are integer display numerics, and
     * {@link PicClause} declares no scale constant for either one.
     */
    private static final int IDENTIFIER_SCALE = 0;

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
     * <p>Column {@code customer_id NUMERIC(9,0) NOT NULL}. The account view program carries this
     * field from the cross-reference record at {@code app/cbl/COACTVWC.cbl:L739} and keys its
     * customer read on it at {@code app/cbl/COACTVWC.cbl:L828-L829}.
     */
    @Column(name = "customer_id", nullable = false,
            precision = PicClause.XREF_CUST_ID_WIDTH, scale = IDENTIFIER_SCALE)
    private BigDecimal customerId;

    /**
     * Account identifier, {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>Column {@code account_id NUMERIC(11,0) NOT NULL}, indexed and not unique by
     * {@code idx_card_xref_account_id}. {@code com.carddemo.authorization.outbox.OutboxWriter}
     * renders the eleven-character zero-padded form that the event envelope carries as its
     * aggregate identifier and Kafka message key.
     */
    @Column(name = "account_id", nullable = false,
            precision = PicClause.XREF_ACCT_ID_WIDTH, scale = IDENTIFIER_SCALE)
    private BigDecimal accountId;

    /**
     * No-argument constructor for the persistence provider.
     *
     * <p>Hibernate calls this constructor to materialise a row, then populates the three fields
     * directly. Application code calls
     * {@link #CardCrossReferenceEntity(String, BigDecimal, BigDecimal)}.
     */
    protected CardCrossReferenceEntity() {
    }

    /**
     * Builds one cross-reference row from the three mapped fields.
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
     * platform, and no method of this class performs one.
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
     * passes it through {@code com.carddemo.cobol.PanMasker} first.
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
}

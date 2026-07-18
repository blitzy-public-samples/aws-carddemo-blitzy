package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * JPA entity for the AWS CardDemo card master record.
 *
 * <p>Origin and traceability (AAP section 0.6.10): this entity is migrated one-for-one from the
 * legacy COBOL copybook {@code CARD-RECORD} defined in {@code legacy/cpy/CVACT02Y.cpy}
 * (record length 150), which described the layout of the VSAM {@code CARDDAT} KSDS. Every COBOL
 * {@code 05}-level field maps to exactly one persisted column below; the trailing
 * {@code FILLER PIC X(59)} is mainframe layout padding and is intentionally not persisted here.
 * Fixed-width, byte-for-byte record parity for flat-file feeds is enforced separately by the
 * fixed-width record mapper rather than by this table mapping (AAP section 0.6.2).</p>
 *
 * <p>Key semantics (AAP section 0.6.2): the primary key is the 16-character card number
 * ({@code CARD-NUM} maps to column {@code card_num}), matching the KSDS primary key. The VSAM
 * alternate index {@code CARDAIX} was built on {@code CARD-ACCT-ID} (verified {@code AXRKP=16} in
 * {@code legacy/catlg/LISTCAT.txt}); it is reproduced in the relational model as a secondary index
 * on {@code card_acct_id}. The Java property is deliberately named {@code cardAcctId} so that the
 * Spring Data repository derived query {@code findByCardAcctId(Long)} resolves and replaces that
 * alternate-index access path (AAP section 0.4.1).</p>
 *
 * <p>Field-name note: the COBOL field {@code CARD-EXPIRAION-DATE} contains an original misspelling
 * ("EXPIRAION"). It is preserved verbatim in the Java property {@code cardExpiraionDate} and the
 * column {@code card_expiraion_date} to keep 1:1 traceability with the source copybook, honoring
 * the "no changes beyond the technology substitution" mandate (AAP section 0.1.1).</p>
 *
 * <p>Type mapping: {@code PIC X(n)} character fields map to {@link String}; the ISO
 * {@code yyyy-MM-dd} expiration value maps to {@link java.time.LocalDate} (verified against the
 * fixture {@code legacy/data/ASCII/carddata.txt}); the unsigned {@code PIC 9(n)} integral fields
 * map to {@link Long} and {@link Integer}. No floating-point types are used anywhere in the model,
 * consistent with the decimal-fidelity rule (AAP section 0.6.1).</p>
 *
 * <p>This class is intentionally non-final so that the JPA provider (Hibernate) can create runtime
 * proxies, and it deliberately does not implement {@link java.io.Serializable} because its identity
 * is a single simple field (no composite {@code @IdClass}/{@code @EmbeddedId}).</p>
 */
@Entity
@Table(name = "card")
public class Card {

    /**
     * Card number ({@code CARD-NUM PIC X(16)}) &mdash; the 16-character primary account number.
     * This is the VSAM {@code CARDDAT} KSDS primary key and is an application-assigned natural key,
     * so no {@code @GeneratedValue} strategy is applied.
     */
    @Id
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * Owning account id ({@code CARD-ACCT-ID PIC 9(11)}). Modelled as a plain scalar foreign key to
     * {@code account.acct_id}; no JPA relationship is declared, preserving the loosely-coupled VSAM
     * file design (AAP section 0.6.2). This property backs the {@code CARDAIX} alternate index via
     * the repository derived query {@code findByCardAcctId}.
     */
    @Column(name = "card_acct_id", precision = 11)
    private Long cardAcctId;

    /**
     * Card verification value ({@code CARD-CVV-CD PIC 9(03)}). Sensitive value: it is deliberately
     * never emitted by {@link #toString()}.
     */
    @Column(name = "card_cvv_cd", precision = 3)
    private Integer cardCvvCd;

    /**
     * Embossed cardholder name ({@code CARD-EMBOSSED-NAME PIC X(50)}).
     */
    @Column(name = "card_embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date ({@code CARD-EXPIRAION-DATE PIC X(10)}, ISO {@code yyyy-MM-dd}). The
     * COBOL misspelling "EXPIRAION" is preserved verbatim for source traceability.
     */
    @Column(name = "card_expiraion_date")
    private LocalDate cardExpiraionDate;

    /**
     * Active-status flag ({@code CARD-ACTIVE-STATUS PIC X(01)}), typically {@code "Y"} or
     * {@code "N"}.
     */
    @Column(name = "card_active_status", length = 1)
    private String cardActiveStatus;

    /**
     * Default no-argument constructor required by the JPA specification for entity instantiation.
     */
    public Card() {
        // Required by JPA/Hibernate; no initialization logic is needed.
    }

    /**
     * Convenience constructor that populates every persisted field. Provided for programmatic
     * construction, unit-test fixtures, and reference-data seeding; it performs field assignment
     * only and invokes no overridable methods.
     *
     * @param cardNum           the 16-character card number (primary key)
     * @param cardAcctId        the owning account id
     * @param cardCvvCd         the card verification value
     * @param cardEmbossedName  the embossed cardholder name
     * @param cardExpiraionDate the expiration date (misspelling preserved from the source copybook)
     * @param cardActiveStatus  the active-status flag
     */
    public Card(String cardNum,
                Long cardAcctId,
                Integer cardCvvCd,
                String cardEmbossedName,
                LocalDate cardExpiraionDate,
                String cardActiveStatus) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardCvvCd = cardCvvCd;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpiraionDate = cardExpiraionDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    // ------------------------------------------------------------------
    // Accessors (one getter/setter pair per persisted field)
    // ------------------------------------------------------------------

    /**
     * Returns the card number (primary key).
     *
     * @return the 16-character card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number (primary key).
     *
     * @param cardNum the 16-character card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the owning account id.
     *
     * @return the account id, or {@code null} if unset
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the owning account id.
     *
     * @param cardAcctId the account id
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the card verification value.
     *
     * @return the CVV, or {@code null} if unset
     */
    public Integer getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the card verification value.
     *
     * @param cardCvvCd the CVV
     */
    public void setCardCvvCd(Integer cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the embossed cardholder name.
     *
     * @return the embossed name, or {@code null} if unset
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the embossed cardholder name.
     *
     * @param cardEmbossedName the embossed name
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date (misspelling preserved from the source copybook).
     *
     * @return the expiration date, or {@code null} if unset
     */
    public LocalDate getCardExpiraionDate() {
        return cardExpiraionDate;
    }

    /**
     * Sets the card expiration date (misspelling preserved from the source copybook).
     *
     * @param cardExpiraionDate the expiration date
     */
    public void setCardExpiraionDate(LocalDate cardExpiraionDate) {
        this.cardExpiraionDate = cardExpiraionDate;
    }

    /**
     * Returns the active-status flag.
     *
     * @return the active-status flag, or {@code null} if unset
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the active-status flag.
     *
     * @param cardActiveStatus the active-status flag
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    // ------------------------------------------------------------------
    // Identity &mdash; based solely on the primary key (card_num)
    // ------------------------------------------------------------------

    /**
     * Entity equality is defined solely by the primary key {@code cardNum}, mirroring VSAM KSDS
     * record identity. Two {@code Card} instances are equal when they are the same concrete type
     * and carry the same (possibly {@code null}) card number.
     *
     * @param o the object to compare with this card
     * @return {@code true} if {@code o} is a {@code Card} with an equal card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Card other = (Card) o;
        return cardNum != null ? cardNum.equals(other.cardNum) : other.cardNum == null;
    }

    /**
     * Hash code derived solely from the primary key {@code cardNum}, kept consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the card number, or {@code 0} when the card number is {@code null}
     */
    @Override
    public int hashCode() {
        return cardNum != null ? cardNum.hashCode() : 0;
    }

    /**
     * Returns a diagnostic string representation of this card. The card verification value
     * ({@code cardCvvCd}) is deliberately omitted for security hygiene and must never be logged.
     *
     * @return a string containing the non-sensitive fields of this card
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum='" + cardNum + '\''
                + ", cardAcctId=" + cardAcctId
                + ", cardEmbossedName='" + cardEmbossedName + '\''
                + ", cardExpiraionDate=" + cardExpiraionDate
                + ", cardActiveStatus='" + cardActiveStatus + '\''
                + '}';
    }
}

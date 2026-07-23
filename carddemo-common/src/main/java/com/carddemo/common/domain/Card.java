package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity mapping the legacy COBOL ``CARD-RECORD`` layout (copybook
 * ``CVACT02Y``, RECLN 150) onto the PostgreSQL ``cards`` table.
 *
 * Each instance represents a single payment card in the CardDemo domain. The
 * entity carries the 16-character card number (PAN) as its primary key, a
 * scalar foreign key to the owning account, the sensitive card verification
 * value (CVV), the embossed cardholder name, the card expiration date, and the
 * single-character active-status flag. The trailing COBOL ``FILLER PIC X(59)``
 * is intentionally not mapped.
 *
 * The card number and CVV are persisted as ``String`` (not numeric) so that
 * exact digit sequences and leading zeros are preserved. The CVV is a sensitive
 * value and is never emitted by {@link #toString()}; the card number is masked
 * to its last four characters there.
 *
 * :ivar cardNum: 16-character primary account number; primary key (``card_num``).
 * :ivar cardAcctId: owning account identifier; scalar foreign key to ``accounts.acct_id`` (``card_acct_id``).
 * :ivar cardCvvCd: sensitive 3-digit card verification value (``card_cvv_cd``).
 * :ivar cardEmbossedName: name embossed on the card (``card_embossed_name``).
 * :ivar cardExpiraionDate: expiration date in ``YYYY-MM-DD`` form (``card_expiraion_date``).
 * :ivar cardActiveStatus: single-character active-status flag (``card_active_status``).
 */
@Entity
@Table(name = "cards")
public class Card {

    /**
     * Card number (PAN), 16 characters, primary key.
     *
     * Stored as text to preserve exact digits and any leading zeros.
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    /**
     * Owning account identifier; scalar foreign key to ``accounts.acct_id``.
     *
     * Modeled as a plain ``Long`` rather than a JPA association so the record
     * mirrors the source copybook and card-service repositories can expose
     * derived queries such as ``findByCardAcctId``.
     */
    @Column(name = "card_acct_id", nullable = false)
    private Long cardAcctId;

    /**
     * Sensitive card verification value (CVV), 3 digits.
     *
     * Stored as text to preserve leading zeros and to support masking and
     * encryption at rest; never included in {@link #toString()} or logs.
     */
    @Column(name = "card_cvv_cd", length = 3)
    private String cardCvvCd;

    /**
     * Name embossed on the physical card, up to 50 characters.
     */
    @Column(name = "card_embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date, 10 characters (``YYYY-MM-DD``).
     *
     * The field and column names preserve the legacy source misspelling
     * ``CARD-EXPIRAION-DATE`` (missing the second ``T``) verbatim.
     */
    @Column(name = "card_expiraion_date", length = 10)
    private String cardExpiraionDate;

    /**
     * Single-character active-status flag.
     */
    @Column(name = "card_active_status", length = 1)
    private String cardActiveStatus;

    /**
     * Creates an empty card instance.
     *
     * Required by the JPA specification for entity instantiation.
     */
    public Card() {
        // No-argument constructor required by the JPA specification.
    }

    /**
     * :returns: the card number (PAN).
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * :param cardNum: the card number (PAN) to set.
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * :returns: the owning account identifier.
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * :param cardAcctId: the owning account identifier to set.
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * :returns: the sensitive card verification value (CVV).
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * :param cardCvvCd: the sensitive card verification value (CVV) to set.
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * :returns: the embossed cardholder name.
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * :param cardEmbossedName: the embossed cardholder name to set.
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * :returns: the card expiration date (``YYYY-MM-DD``).
     */
    public String getCardExpiraionDate() {
        return cardExpiraionDate;
    }

    /**
     * :param cardExpiraionDate: the card expiration date (``YYYY-MM-DD``) to set.
     */
    public void setCardExpiraionDate(String cardExpiraionDate) {
        this.cardExpiraionDate = cardExpiraionDate;
    }

    /**
     * :returns: the single-character active-status flag.
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * :param cardActiveStatus: the single-character active-status flag to set.
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Compares two cards for equality by their primary key (``cardNum``).
     *
     * :param o: the object to compare with.
     * :returns: ``true`` when both are cards with an equal ``cardNum``.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Card card = (Card) o;
        return Objects.equals(cardNum, card.cardNum);
    }

    /**
     * :returns: a hash code derived from the primary key (``cardNum``).
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * Renders a diagnostic representation that OMITS the sensitive CVV and masks
     * the card number to its last four characters.
     *
     * :returns: a CVV-free, PAN-masked string representation of this card.
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum=" + maskPan(cardNum)
                + ", cardAcctId=" + cardAcctId
                + ", cardEmbossedName=" + cardEmbossedName
                + ", cardExpiraionDate=" + cardExpiraionDate
                + ", cardActiveStatus=" + cardActiveStatus
                + '}';
    }

    /**
     * Masks a card number so that at most its last four characters remain
     * visible; shorter values are fully masked.
     *
     * :param pan: the raw card number to mask.
     * :returns: the masked card number, or ``null`` when the input is ``null``.
     */
    private static String maskPan(String pan) {
        if (pan == null) {
            return null;
        }
        int length = pan.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + pan.substring(length - 4);
    }
}

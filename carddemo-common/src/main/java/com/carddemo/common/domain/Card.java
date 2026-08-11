package com.carddemo.common.domain;

import com.carddemo.common.crypto.CryptoConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA entity mapping the legacy COBOL ``CARD-RECORD`` layout (copybook ``CVACT02Y``, RECLN
 *     150) onto the PostgreSQL ``cards`` table. Each instance represents a single payment card
 *     in the CardDemo domain. The entity carries the 16-character card number (PAN) as its
 *     primary key, a scalar foreign key to the owning account, the sensitive card verification
 *     value (CVV), the embossed cardholder name, the card expiration date, and the
 *     single-character active-status flag. The trailing COBOL ``FILLER PIC X(59)`` is
 *     intentionally not mapped. The card number and CVV are persisted as ``String`` (not
 *     numeric) so that exact digit sequences and leading zeros are preserved. The CVV is a
 *     sensitive value and is never emitted by {@link #toString()}; the card number is masked
 *     to its last four characters there.
 * :ivar cardNum: 16-character primary account number; primary key (``card_num``).
 * :ivar cardAcctId: owning account identifier; scalar foreign key to ``accounts.acct_id``
 *     (``card_acct_id``).
 * :ivar cardCvvCd: sensitive 3-digit card verification value (``card_cvv_cd``).
 * :ivar cardEmbossedName: name embossed on the card (``card_embossed_name``).
 * :ivar cardExpiraionDate: expiration date in ``YYYY-MM-DD`` form
 *     (``card_expiraion_date``).
 * :ivar cardActiveStatus: single-character active-status flag (``card_active_status``).
 */
@Entity
@Table(name = "cards", indexes = {
        @Index(name = "idx_cards_card_acct_id", columnList = "card_acct_id")
})
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
     * Read-only association to the owning account, mapped over the same
     * ``card_acct_id`` column as {@link #cardAcctId}.
     *
     * The scalar id remains the single writable mapping; this association is
     * ``insertable=false``/``updatable=false`` so it never duplicates the
     * column, and it declares the ``fk_cards_account`` foreign key so the
     * card-to-account referential-integrity intent is explicit in the schema.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_acct_id", referencedColumnName = "acct_id",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_cards_account"))
    @JsonIgnore
    private Account account;

    /**
     * Sensitive card verification value (CVV), 3 digits.
     *
     * Retained because ``CVACT02Y`` declares ``CARD-CVV-CD`` as part of the frozen
     * 150-byte card record; encrypted at rest via {@link CryptoConverter}, never
     * serialized to clients, and never included in {@link #toString()} or logs.
     */
    @Column(name = "card_cvv_cd", length = 512)
    @Convert(converter = CryptoConverter.class)
    @JsonIgnore
    private String cardCvvCd;

    /**
     * Name embossed on the physical card, up to 50 characters.
     */
    @Column(name = "card_embossed_name", length = 50, nullable = false)
    private String cardEmbossedName;

    /**
     * Card expiration date, 10 characters (``YYYY-MM-DD``).
     *
     * The field and column names preserve the legacy source misspelling
     * ``CARD-EXPIRAION-DATE`` (missing the second ``T``) verbatim.
     */
    @Column(name = "card_expiraion_date", length = 10, nullable = false)
    private String cardExpiraionDate;

    /**
     * Single-character active-status flag.
     */
    @Column(name = "card_active_status", length = 1, nullable = false)
    private String cardActiveStatus;

    /**
     * Optimistic-locking version (JPA-managed) detecting concurrent card updates.
     *
     * :purpose: ``COCRDUPC`` read the CARDDAT record for update, holding a VSAM update lock
     *     for the whole rewrite; a stateless service cannot hold that lock across requests.
     *     Without a version column concurrent card updates all reported success and only the
     *     last writer's values survived, so the earlier edits were silently lost. The counter
     *     makes the database itself the arbiter: a writer whose row moved since it was read
     *     matches no row and is answered with the verbatim ``DATA-WAS-CHANGED-BEFORE-UPDATE``
     *     outcome instead (AAP 0.6.2) [app/cbl/COCRDUPC.cbl:L1498-1519].
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Creates an empty card instance.
     *
     * Required by the JPA specification for entity instantiation.
     */
    public Card() {
        // No-argument constructor required by the JPA specification.
    }

    /**
     * :purpose: Read ``version``.
     * :returns: the optimistic-locking version, or ``null`` before the row is first persisted.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * :purpose: Set ``version``.
     * :param version: the optimistic-locking version to carry; normally managed by the provider.
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * :purpose: Read ``cardNum``.
     * :returns: the card number (PAN).
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * :purpose: Set ``cardNum``.
     * :param cardNum: the card number (PAN) to set.
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * :purpose: Read ``cardAcctId``.
     * :returns: the owning account identifier.
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * :purpose: Set ``cardAcctId``.
     * :param cardAcctId: the owning account identifier to set.
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * :purpose: Read ``cardCvvCd``.
     * :returns: the sensitive card verification value (CVV); never serialized to
     *     clients.
     */
    @JsonIgnore
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * :purpose: Read ``account``.
     * :returns: the read-only owning-account association, or ``null`` when not
     *     loaded.
     */
    public Account getAccount() {
        return account;
    }

    /**
     * :purpose: Set ``cardCvvCd``.
     * :param cardCvvCd: the sensitive card verification value (CVV) to set.
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * :purpose: Read ``cardEmbossedName``.
     * :returns: the embossed cardholder name.
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * :purpose: Set ``cardEmbossedName``.
     * :param cardEmbossedName: the embossed cardholder name to set.
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * :purpose: Read ``cardExpiraionDate``.
     * :returns: the card expiration date (``YYYY-MM-DD``).
     */
    public String getCardExpiraionDate() {
        return cardExpiraionDate;
    }

    /**
     * :purpose: Set ``cardExpiraionDate``.
     * :param cardExpiraionDate: the card expiration date (``YYYY-MM-DD``) to set.
     */
    public void setCardExpiraionDate(String cardExpiraionDate) {
        this.cardExpiraionDate = cardExpiraionDate;
    }

    /**
     * :purpose: Read ``cardActiveStatus``.
     * :returns: the single-character active-status flag.
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * :purpose: Set ``cardActiveStatus``.
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
        if (!(o instanceof Card other)) {
            return false;
        }
        return cardNum != null && cardNum.equals(other.getCardNum());
    }

    /**
     * :purpose: Hash consistent with :java:meth:`equals`.
     * :returns: a proxy-stable hash code consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Card.class.hashCode();
    }

    /**
     * :purpose: Diagnostic rendering that never discloses unmasked PII.
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

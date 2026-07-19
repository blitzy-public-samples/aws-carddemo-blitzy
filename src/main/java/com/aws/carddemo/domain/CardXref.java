package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity for the card cross-reference record.
 *
 * <p>Origin: migrated one-for-one from COBOL copybook {@code CARD-XREF-RECORD}
 * (record length 50) defined in {@code legacy/cpy/CVACT03Y.cpy}. This entity
 * models the legacy VSAM KSDS {@code CCXREF}, which cross-references a card
 * number to its owning customer and account.</p>
 *
 * <p>Verified legacy layout (50 bytes total):</p>
 * <pre>
 * 01  CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM   PIC X(16).   -- 16 bytes -&gt; {@link #xrefCardNum}
 *     05  XREF-CUST-ID    PIC 9(09).   --  9 bytes -&gt; {@link #xrefCustId}
 *     05  XREF-ACCT-ID    PIC 9(11).   -- 11 bytes -&gt; {@link #xrefAcctId}
 *     05  FILLER          PIC X(14).   -- 14 bytes -- NOT persisted
 * </pre>
 *
 * <p>Key semantics (AAP &sect;0.3.3, &sect;0.6.2) and review finding F6: the
 * authoritative VSAM {@code CCXREF} KSDS is keyed <em>uniquely</em> on the
 * 16-byte card number alone ({@code legacy/jcl/XREFFILE.jcl} defines
 * {@code KEYS(16 0)}; {@code legacy/catlg/LISTCAT.txt} confirms the account id
 * is a <em>nonunique</em> alternate index {@code CXACAIX} at offset 25). The
 * AAP-frozen mapping retains the three-column composite {@link IdClass}
 * ({@link CardXrefId}: card + customer + account) for structural traceability;
 * to prevent that composite key from admitting duplicate card numbers (which
 * the true KSDS forbids) a {@code UNIQUE} constraint
 * {@code uk_card_xref_card_num} is enforced on {@code xref_card_num}. Account
 * access remains nonunique secondary access via the derived query
 * {@code findByXrefAcctId(Long)}. This divergence between the frozen composite
 * {@code IdClass} and the true single-column unique key is recorded in
 * {@code docs/decision-log.md}.</p>
 *
 * <p>Alternate-index migration: the legacy VSAM alternate index {@code CXACAIX}
 * over {@code XREF-ACCT-ID} (verified {@code AXRKP=25} in
 * {@code legacy/catlg/LISTCAT.txt}) is reproduced as a Spring Data derived
 * query {@code findByXrefAcctId(Long)} on the repository; lookup by card is
 * exposed as {@code findByXrefCardNum(String)}. The property names on this
 * entity ({@code xrefAcctId}, {@code xrefCardNum}) are chosen so those derived
 * queries resolve without an explicit {@code @Query}.</p>
 *
 * <p>Design notes: no JPA relationships are declared; the cross-reference keeps
 * scalar keys only, matching the parity-first mapping strategy (AAP
 * &sect;0.6.2, &sect;0.7.3). Monetary/decimal concerns do not apply to this
 * record. The {@code FILLER(14)} bytes carry no business meaning and are not
 * persisted as a column.</p>
 */
@Entity
@Table(
        name = "card_xref",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_card_xref_card_num",
                columnNames = "xref_card_num"))
@IdClass(CardXref.CardXrefId.class)
public class CardXref {

    /**
     * Card number cross-reference key. Legacy {@code XREF-CARD-NUM PIC X(16)}.
     * Fixed-width 16-character card number; part of the composite primary key.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "xref_card_num", length = 16)
    private String xrefCardNum;

    /**
     * Customer identifier cross-reference key. Legacy
     * {@code XREF-CUST-ID PIC 9(09)}. Nine-digit customer id; part of the
     * composite primary key. Mapped to SQL {@code NUMERIC(9)} via
     * {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) so Hibernate expects
     * {@code NUMERIC} rather than the default {@code BIGINT} (review finding F1).
     */
    @Id
    @Column(name = "xref_cust_id", precision = 9)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Long xrefCustId;

    /**
     * Account identifier cross-reference key. Legacy
     * {@code XREF-ACCT-ID PIC 9(11)}. Eleven-digit account id; part of the
     * composite primary key. Backs the {@code CXACAIX} alternate index via the
     * repository derived query {@code findByXrefAcctId}. Mapped to SQL
     * {@code NUMERIC(11)} via {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) so
     * Hibernate expects {@code NUMERIC} rather than the default {@code BIGINT}
     * (review finding F1).
     */
    @Id
    @Column(name = "xref_acct_id", precision = 11)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Long xrefAcctId;

    /**
     * Protected no-argument constructor required by the JPA specification for
     * entity instantiation. Public visibility keeps the type freely
     * constructible by frameworks and tests.
     */
    public CardXref() {
        // No-args constructor mandated by JPA; fields populated by the
        // persistence provider or via setters.
    }

    /**
     * Convenience all-arguments constructor for programmatic and test
     * construction of a fully populated cross-reference record.
     *
     * @param xrefCardNum the 16-character card number key
     * @param xrefCustId  the customer identifier key
     * @param xrefAcctId  the account identifier key
     */
    public CardXref(String xrefCardNum, Long xrefCustId, Long xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Returns the card number cross-reference key.
     *
     * @return the card number ({@code XREF-CARD-NUM})
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number cross-reference key.
     *
     * @param xrefCardNum the card number ({@code XREF-CARD-NUM})
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the customer identifier cross-reference key.
     *
     * @return the customer id ({@code XREF-CUST-ID})
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the customer identifier cross-reference key.
     *
     * @param xrefCustId the customer id ({@code XREF-CUST-ID})
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the account identifier cross-reference key.
     *
     * @return the account id ({@code XREF-ACCT-ID})
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the account identifier cross-reference key.
     *
     * @param xrefAcctId the account id ({@code XREF-ACCT-ID})
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Entity equality is defined over the full composite key (card + customer +
     * account), consistent with the VSAM {@code CCXREF} primary-key semantics.
     * Uses an {@code instanceof} check so a Hibernate proxy compares equal to
     * its underlying entity, and treats an instance whose key is not fully
     * populated (any component {@code null}) as not equal to any other instance,
     * so distinct transient rows are never collapsed (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code CardXref} with an
     *         equal, fully populated composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXref that)) {
            return false;
        }
        return xrefCardNum != null && xrefCustId != null && xrefAcctId != null
                && xrefCardNum.equals(that.xrefCardNum)
                && xrefCustId.equals(that.xrefCustId)
                && xrefAcctId.equals(that.xrefAcctId);
    }

    /**
     * Returns a constant, identity-stable hash code. A constant (rather than one
     * derived from the mutable composite key) is used so the hash does not change
     * as the key components are assigned, keeping instances locatable in
     * hash-based collections and consistent with {@link #equals(Object)} (review
     * finding F10).
     *
     * @return a stable, class-level hash code
     */
    @Override
    public int hashCode() {
        return CardXref.class.hashCode();
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class
     * name and an opaque per-instance identity token. The cross-referenced card
     * number, customer id and account id are deliberately never emitted so that
     * payment/customer identifiers cannot leak into logs or error messages
     * (CWE-532; review finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "CardXref@" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Composite primary-key class for {@link CardXref}, referenced by
     * {@link IdClass}. Its field names and types match the {@code @Id} fields
     * of the enclosing entity exactly ({@code xrefCardNum}, {@code xrefCustId},
     * {@code xrefAcctId}), as required for {@link IdClass}-based composite keys.
     *
     * <p>Implemented as a nested {@code static} class (not a separate
     * {@code *Id.java} file) per the migration design, and made
     * {@link Serializable} as mandated by the JPA specification for identifier
     * classes.</p>
     */
    public static class CardXrefId implements Serializable {

        /** Serialization version identifier for this JPA identifier class. */
        private static final long serialVersionUID = 1L;

        /** Card number key component; mirrors {@link CardXref#xrefCardNum}. */
        private String xrefCardNum;

        /** Customer id key component; mirrors {@link CardXref#xrefCustId}. */
        private Long xrefCustId;

        /** Account id key component; mirrors {@link CardXref#xrefAcctId}. */
        private Long xrefAcctId;

        /**
         * No-argument constructor required by JPA for identifier classes.
         */
        public CardXrefId() {
            // No-args constructor mandated by JPA for @IdClass identifiers.
        }

        /**
         * All-arguments constructor for convenient identifier construction.
         *
         * @param xrefCardNum the card number key component
         * @param xrefCustId  the customer id key component
         * @param xrefAcctId  the account id key component
         */
        public CardXrefId(String xrefCardNum, Long xrefCustId, Long xrefAcctId) {
            this.xrefCardNum = xrefCardNum;
            this.xrefCustId = xrefCustId;
            this.xrefAcctId = xrefAcctId;
        }

        /**
         * Returns the card number key component.
         *
         * @return the card number
         */
        public String getXrefCardNum() {
            return xrefCardNum;
        }

        /**
         * Sets the card number key component.
         *
         * @param xrefCardNum the card number
         */
        public void setXrefCardNum(String xrefCardNum) {
            this.xrefCardNum = xrefCardNum;
        }

        /**
         * Returns the customer id key component.
         *
         * @return the customer id
         */
        public Long getXrefCustId() {
            return xrefCustId;
        }

        /**
         * Sets the customer id key component.
         *
         * @param xrefCustId the customer id
         */
        public void setXrefCustId(Long xrefCustId) {
            this.xrefCustId = xrefCustId;
        }

        /**
         * Returns the account id key component.
         *
         * @return the account id
         */
        public Long getXrefAcctId() {
            return xrefAcctId;
        }

        /**
         * Sets the account id key component.
         *
         * @param xrefAcctId the account id
         */
        public void setXrefAcctId(Long xrefAcctId) {
            this.xrefAcctId = xrefAcctId;
        }

        /**
         * Equality over all three key components, as required for a correct
         * JPA identifier class.
         *
         * @param o the object to compare with
         * @return {@code true} if the other object is a {@code CardXrefId} with
         *         equal components
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CardXrefId that = (CardXrefId) o;
            return Objects.equals(xrefCardNum, that.xrefCardNum)
                    && Objects.equals(xrefCustId, that.xrefCustId)
                    && Objects.equals(xrefAcctId, that.xrefAcctId);
        }

        /**
         * Hash code over all three key components, consistent with
         * {@link #equals(Object)}.
         *
         * @return the identifier hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(xrefCardNum, xrefCustId, xrefAcctId);
        }

        /**
         * Returns a diagnostic representation including all three key
         * components.
         *
         * @return a string representation of this identifier
         */
        @Override
        public String toString() {
            return "CardXrefId{"
                    + "xrefCardNum='" + xrefCardNum + '\''
                    + ", xrefCustId=" + xrefCustId
                    + ", xrefAcctId=" + xrefAcctId
                    + '}';
        }
    }
}

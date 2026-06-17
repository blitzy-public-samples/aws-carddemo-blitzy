package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity mapping the legacy VSAM {@code TRANCATG} dataset to the relational
 * table {@code transaction_category}.
 *
 * <p><b>Source of truth.</b> This entity is a direct, field-faithful migration of
 * the COBOL copybook {@code app/cpy/CVTRA04Y.cpy} ({@code TRAN-CAT-RECORD},
 * record length 60). The legacy VSAM KSDS exposes a 6-byte primary key
 * (verified in {@code app/catlg/LISTCAT.txt}: {@code TRANCATG KEYLEN=6}) composed
 * of a 2-character transaction type code and a 4-digit transaction category code,
 * which is reproduced here as a two-column composite primary key
 * {@code (type_cd, cat_cd)}.</p>
 *
 * <p><b>COBOL &rarr; Java field mapping.</b></p>
 * <pre>
 *   01  TRAN-CAT-RECORD.                              (RECLN 60)
 *       05  TRAN-CAT-KEY.                             &rarr; {@link TransactionCategoryId} (composite PK)
 *           10  TRAN-TYPE-CD        PIC X(02)         &rarr; id.typeCd      -&gt; type_cd       CHAR(2)
 *           10  TRAN-CAT-CD         PIC 9(04)         &rarr; id.catCd       -&gt; cat_cd        INTEGER (leading zeros stripped)
 *       05  TRAN-CAT-TYPE-DESC      PIC X(50)         &rarr; catTypeDesc    -&gt; cat_type_desc VARCHAR(50)
 *       05  FILLER                  PIC X(04)         &rarr; (omitted — not persisted)
 * </pre>
 *
 * <p><b>Binding schema contract.</b> Hibernate boots with {@code ddl-auto=validate},
 * so the column names and JDBC types below MUST match
 * {@code src/main/resources/db/migration/V1__schema.sql} exactly:</p>
 * <pre>
 *   CREATE TABLE transaction_category (
 *       type_cd       CHAR(2) NOT NULL,
 *       cat_cd        INTEGER NOT NULL,
 *       cat_type_desc VARCHAR(50),
 *       CONSTRAINT pk_transaction_category PRIMARY KEY (type_cd, cat_cd),
 *       CONSTRAINT fk_trancat_type FOREIGN KEY (type_cd)
 *           REFERENCES transaction_type (type_cd)
 *   );
 * </pre>
 *
 * <p><b>Design notes.</b></p>
 * <ul>
 *   <li>The composite key is modelled with {@link EmbeddedId} over the public,
 *       static, nested {@link TransactionCategoryId} {@code @Embeddable} class.
 *       Keeping the id class nested preserves the folder-wide convention of
 *       exactly ten entity files while remaining referenceable by repositories.</li>
 *   <li>The {@code fk_trancat_type} foreign key to {@code transaction_type} is
 *       enforced at the database level only; this entity intentionally uses a
 *       flat scalar design and does <em>not</em> declare a JPA
 *       {@code @ManyToOne} relationship.</li>
 *   <li>Both key parts hold assigned business values, so no
 *       {@code @GeneratedValue} strategy is applied.</li>
 * </ul>
 *
 * <p><b>Repository contract.</b> The corresponding Spring Data repository declares
 * {@code JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId>};
 * the nested id class is deliberately {@code public static} so it can be referenced
 * as the repository's identifier type.</p>
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory {

    /**
     * Composite primary key {@code (type_cd, cat_cd)} migrated from the COBOL
     * {@code TRAN-CAT-KEY} group field.
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Human-readable category description.
     *
     * <p>Migrated from {@code TRAN-CAT-TYPE-DESC PIC X(50)}; persisted to the
     * nullable {@code cat_type_desc VARCHAR(50)} column.</p>
     */
    @Column(name = "cat_type_desc", length = 50)
    private String catTypeDesc;

    /**
     * Protected/no-argument constructor required by the JPA specification.
     */
    public TransactionCategory() {
        // Required by JPA for entity instantiation via reflection.
    }

    /**
     * Constructs a fully-populated transaction category from an existing
     * composite key and a description.
     *
     * @param id          the composite primary key; must not be {@code null}
     *                    when persisting
     * @param catTypeDesc the category description (may be {@code null})
     */
    public TransactionCategory(TransactionCategoryId id, String catTypeDesc) {
        this.id = id;
        this.catTypeDesc = catTypeDesc;
    }

    /**
     * Convenience constructor that assembles the composite key from its
     * individual components.
     *
     * @param typeCd      the 2-character transaction type code
     *                    ({@code TRAN-TYPE-CD})
     * @param catCd       the transaction category code ({@code TRAN-CAT-CD},
     *                    leading zeros stripped)
     * @param catTypeDesc the category description (may be {@code null})
     */
    public TransactionCategory(String typeCd, Integer catCd, String catTypeDesc) {
        this.id = new TransactionCategoryId(typeCd, catCd);
        this.catTypeDesc = catTypeDesc;
    }

    /**
     * @return the composite primary key
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * @param id the composite primary key to set
     */
    public void setId(TransactionCategoryId id) {
        this.id = id;
    }

    /**
     * @return the category description
     */
    public String getCatTypeDesc() {
        return catTypeDesc;
    }

    /**
     * @param catTypeDesc the category description to set
     */
    public void setCatTypeDesc(String catTypeDesc) {
        this.catTypeDesc = catTypeDesc;
    }

    /**
     * Entity equality is defined solely by the composite primary key, mirroring
     * the VSAM key semantics of the legacy {@code TRANCATG} dataset.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory)) {
            return false;
        }
        TransactionCategory that = (TransactionCategory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "TransactionCategory{" +
                "id=" + id +
                ", catTypeDesc='" + catTypeDesc + '\'' +
                '}';
    }

    /**
     * Embeddable composite primary key for {@link TransactionCategory}, migrated
     * from the COBOL {@code TRAN-CAT-KEY} group field.
     *
     * <p>This class is intentionally {@code public static} so it can be used as
     * the identifier type of the Spring Data
     * {@code JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId>}.
     * It implements {@link Serializable}, exposes a public no-argument
     * constructor, and defines {@code equals}/{@code hashCode} over both key
     * components as required by the JPA specification for embedded identifiers.</p>
     */
    @Embeddable
    public static class TransactionCategoryId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Transaction type code ({@code TRAN-TYPE-CD PIC X(02)}).
         *
         * <p>The DDL column {@code type_cd} is {@code CHAR(2)} (JDBC type code
         * {@code CHAR} = 1). The {@link JdbcTypeCode} override is mandatory:
         * without it Hibernate would map a {@code String} to {@code VARCHAR}
         * (code 12) and fail {@code ddl-auto=validate} against the fixed-width
         * column. This part of the key is also the foreign key into
         * {@code transaction_type}, enforced at the database level.</p>
         */
        @Column(name = "type_cd", length = 2)
        @JdbcTypeCode(SqlTypes.CHAR)
        private String typeCd;

        /**
         * Transaction category code ({@code TRAN-CAT-CD PIC 9(04)}), persisted to
         * the {@code cat_cd INTEGER} column with any leading zeros stripped.
         */
        @Column(name = "cat_cd")
        private Integer catCd;

        /**
         * Public no-argument constructor required by JPA for embeddable
         * identifiers.
         */
        public TransactionCategoryId() {
            // Required by JPA for embeddable id instantiation via reflection.
        }

        /**
         * Constructs a composite key from its two business components.
         *
         * @param typeCd the 2-character transaction type code
         * @param catCd  the transaction category code (leading zeros stripped)
         */
        public TransactionCategoryId(String typeCd, Integer catCd) {
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * @return the transaction type code
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * @param typeCd the transaction type code to set
         */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /**
         * @return the transaction category code
         */
        public Integer getCatCd() {
            return catCd;
        }

        /**
         * @param catCd the transaction category code to set
         */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionCategoryId)) {
                return false;
            }
            TransactionCategoryId that = (TransactionCategoryId) o;
            return Objects.equals(typeCd, that.typeCd)
                    && Objects.equals(catCd, that.catCd);
        }

        @Override
        public int hashCode() {
            return Objects.hash(typeCd, catCd);
        }

        @Override
        public String toString() {
            return "TransactionCategoryId{" +
                    "typeCd='" + typeCd + '\'' +
                    ", catCd=" + catCd +
                    '}';
        }
    }
}

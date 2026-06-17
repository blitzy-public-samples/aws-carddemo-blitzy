package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapping the legacy VSAM {@code TRANTYPE} key-sequenced dataset to the relational
 * table {@code transaction_type}. It is the faithful Java translation of the COBOL record
 * {@code TRAN-TYPE-RECORD} defined by copybook {@code app/cpy/CVTRA03Y.cpy} (record length 60),
 * and it preserves the two-character transaction-type code (the VSAM primary key,
 * {@code KEYLEN = 2}, {@code RKP = 0} per {@code app/catlg/LISTCAT.txt}) together with its
 * human-readable description.
 *
 * <p>This is the <strong>simplest</strong> entity in the CardDemo domain model — a single-column
 * assigned primary key and one scalar attribute — and therefore serves as the canonical example
 * of the project's entity conventions (assigned business key, {@link JdbcTypeCode} pinning for
 * {@code CHAR} columns, value-based {@link #equals(Object)}/{@link #hashCode()} on the identifier,
 * and a flat scalar-foreign-key design with no JPA associations).</p>
 *
 * <h2>Record layout (CVTRA03Y.cpy — RECLN = 60)</h2>
 * <pre>
 *   COBOL field        PIC      Java field           Column                Notes
 *   ----------------   ------   ------------------   -------------------   --------------------------
 *   TRAN-TYPE          X(02)    typeCd  (String)     type_cd  CHAR(2)      &#64;Id, assigned ("01".."07")
 *   TRAN-TYPE-DESC     X(50)    typeDesc(String)     type_desc VARCHAR(50) e.g. "Purchase"
 *   FILLER             X(08)    (not stored)         &#8212;                   COBOL padding, not persisted
 *   ----------------   ------   ------------------   -------------------   --------------------------
 *   2 + 50 + 8 = 60 bytes
 * </pre>
 *
 * <h2>Binding schema contract</h2>
 * <p>The application boots with {@code spring.jpa.hibernate.ddl-auto=validate}, so Hibernate
 * validates this mapping against the Flyway-created schema in
 * {@code src/main/resources/db/migration/V1__schema.sql}:</p>
 * <pre>
 *   CREATE TABLE transaction_type (
 *       type_cd   CHAR(2)     NOT NULL,
 *       type_desc VARCHAR(50),
 *       CONSTRAINT pk_transaction_type PRIMARY KEY (type_cd)
 *   );
 * </pre>
 * <p>Any divergence in table name, column name, or JDBC type code raises a
 * {@code SchemaManagementException} and aborts startup. In particular, {@link #typeCd} is annotated
 * with {@link JdbcTypeCode}({@link SqlTypes#CHAR}): a plain Java {@code String} otherwise maps to
 * JDBC {@code VARCHAR} (type code 12), whereas the column is {@code CHAR(2)} (type code 1), and
 * Hibernate 6.4 schema validation compares JDBC type codes — forcing {@code CHAR} keeps the entity
 * byte-compatible with the legacy fixed-width key on both H2 (PostgreSQL mode) and PostgreSQL.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>Assigned key</strong> — {@code type_cd} is a business key supplied by the seed data
 *       (V2 reference seed) and the application, never database-generated; consequently there is
 *       deliberately <em>no</em> {@code @GeneratedValue}.</li>
 *   <li><strong>Flat scalar-FK design</strong> — child tables ({@code transaction_category}, and
 *       transitively {@code transactions} / {@code transaction_category_balance}) reference this
 *       table through database-level foreign keys only; no JPA {@code @OneToMany}/{@code @ManyToOne}
 *       association is declared here, matching the project-wide repository-by-id access pattern.</li>
 *   <li><strong>Identity semantics</strong> — {@link #equals(Object)} and {@link #hashCode()} are
 *       based solely on the {@code @Id} ({@link #typeCd}), the stable identifier of the row.</li>
 * </ul>
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    /**
     * The two-character transaction-type code and primary key, ported from {@code TRAN-TYPE}
     * {@code PIC X(02)}. Pinned to JDBC {@code CHAR} via {@link JdbcTypeCode} so the mapping matches
     * the {@code type_cd CHAR(2)} column under {@code ddl-auto=validate}. This is an assigned
     * business key (e.g. {@code "01".."07"}); it is never generated.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_cd", length = 2)
    private String typeCd;

    /**
     * The human-readable transaction-type description, ported from {@code TRAN-TYPE-DESC}
     * {@code PIC X(50)} and mapped to the {@code type_desc VARCHAR(50)} column (e.g. {@code "Purchase"}).
     */
    @Column(name = "type_desc", length = 50)
    private String typeDesc;

    /**
     * Public no-argument constructor required by the JPA specification for entity instantiation.
     */
    public TransactionType() {
        // No-arg constructor required by JPA / Hibernate.
    }

    /**
     * Convenience all-arguments constructor for tests, seeding, and mapper code.
     *
     * @param typeCd   the two-character transaction-type code (primary key)
     * @param typeDesc the transaction-type description
     */
    public TransactionType(String typeCd, String typeDesc) {
        this.typeCd = typeCd;
        this.typeDesc = typeDesc;
    }

    /**
     * Returns the two-character transaction-type code (primary key).
     *
     * @return the transaction-type code
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the two-character transaction-type code (primary key).
     *
     * @param typeCd the transaction-type code to set
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction-type description.
     *
     * @return the transaction-type description
     */
    public String getTypeDesc() {
        return typeDesc;
    }

    /**
     * Sets the transaction-type description.
     *
     * @param typeDesc the transaction-type description to set
     */
    public void setTypeDesc(String typeDesc) {
        this.typeDesc = typeDesc;
    }

    /**
     * Equality is based solely on the primary key {@link #typeCd}, the stable identity of the row.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionType} with an equal {@code typeCd}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionType)) {
            return false;
        }
        TransactionType that = (TransactionType) o;
        return Objects.equals(typeCd, that.typeCd);
    }

    /**
     * Hash code derived solely from the primary key {@link #typeCd}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this entity
     */
    @Override
    public int hashCode() {
        return Objects.hash(typeCd);
    }

    /**
     * Diagnostic representation including both fields; this entity carries no sensitive data.
     *
     * @return a string representation of this transaction type
     */
    @Override
    public String toString() {
        return "TransactionType{"
                + "typeCd='" + typeCd + '\''
                + ", typeDesc='" + typeDesc + '\''
                + '}';
    }
}

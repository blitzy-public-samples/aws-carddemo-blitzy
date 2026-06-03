package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for customer master data.
 *
 * <p>Maps the 500-byte {@code CUSTOMER-RECORD} from {@code app/cpy/CVCUS01Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68). The copybook declares 18 user fields plus a trailing
 * {@code FILLER PIC X(168)}; the filler carries no business meaning and is intentionally not
 * represented in Java (PR-13 maps only the named fields).</p>
 *
 * <p>This is a CORE, PII-rich master-data table with no outgoing foreign keys. Rows are seeded by
 * the data-initialization load ({@code DataInitializationJobConfig}) from
 * {@code app/data/ASCII/custdata.txt}; they are read by the account/customer view flows
 * ({@code COACTVWC} &rarr; {@code CustomerController}/{@code CustomerService}), joined through the
 * cross-reference junction ({@link CardXref}, whose {@code xref_cust_id} references
 * {@code customers.cust_id}), and read by statement generation ({@code CBSTM03A}).</p>
 *
 * <p><strong>PII / sensitive fields.</strong></p>
 * <ul>
 *   <li>{@link #ssn} &mdash; Social Security Number. Stored verbatim (leading zeros retained, see
 *       below) and <strong>MUST be masked</strong> by {@code CustomerMapper} on all outbound DTOs
 *       per PR-20 (rendered as {@code ***-**-####} with only the last 4 digits visible). The entity
 *       never stores a masked value; masking is exclusively a DTO/mapper-boundary concern.</li>
 *   <li>{@link #govtIssuedId} &mdash; Government-issued identifier; treat as sensitive.</li>
 *   <li>{@link #phoneNum1}, {@link #phoneNum2} &mdash; Contact numbers.</li>
 *   <li>{@link #dob} &mdash; Date of birth.</li>
 * </ul>
 *
 * <p><strong>Schema conformance (mandatory).</strong> Every {@code @Column} mapping below mirrors
 * the committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql} (table
 * {@code customers}). The {@code dev}, {@code prod}, and {@code test} profiles all run Hibernate
 * with {@code ddl-auto: validate}, so any divergence in column name, SQL type, or length would
 * cause the Spring context to fail to start. In particular, the committed DDL dictates several
 * mappings that differ from a naive 1:1 string mapping of the COBOL {@code PIC} clauses:</p>
 * <ul>
 *   <li>{@code addr_state_cd CHAR(2)}, {@code addr_country_cd CHAR(3)}, and
 *       {@code pri_card_holder_ind CHAR(1)} &rarr; {@link String} fields annotated with
 *       {@link JdbcTypeCode}{@code (Types.CHAR)} so Hibernate binds them as fixed-width
 *       {@code CHAR} (a plain {@code String} resolves to {@code VARCHAR} and would fail validation
 *       against the committed schema), consistent with the {@code CHAR} mappings in
 *       {@link Account}, {@link Card}, and {@link Transaction}. Note the COBOL field names
 *       {@code CUST-ADDR-STATE-CD}/{@code CUST-ADDR-COUNTRY-CD}/{@code CUST-ADDR-ZIP} normalize to
 *       the DDL column names {@code addr_state_cd}/{@code addr_country_cd}/{@code addr_zip}.</li>
 *   <li>{@code dob DATE} &rarr; {@link LocalDate} (not {@code String}); the COBOL
 *       {@code CUST-DOB-YYYY-MM-DD PIC X(10)} text date is normalized to a SQL {@code DATE} column,
 *       consistent with the {@code DATE} mappings on {@link Account} and {@link Card}.</li>
 *   <li>{@code fico_credit_score SMALLINT} &rarr; {@link Integer} annotated with
 *       {@link JdbcTypeCode}{@code (Types.SMALLINT)}. The Java type is modeled as {@code Integer}
 *       (ample headroom for the {@code PIC 9(03)} range 0&ndash;999); {@code @JdbcTypeCode} forces
 *       the {@code SMALLINT} JDBC binding required so {@code ddl-auto: validate} matches the
 *       committed column (a plain {@code Integer} resolves to {@code INTEGER} and would fail
 *       validation against the {@code SMALLINT} column).</li>
 *   <li>{@code version INTEGER NOT NULL DEFAULT 0} &rarr; {@link Integer}.</li>
 *   <li>The audit fields keep the canonical Java names
 *       {@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy} but map to the DDL
 *       column names
 *       {@code created_date}/{@code last_modified_date}/{@code created_by}/{@code last_modified_by}.</li>
 * </ul>
 *
 * <p><strong>Leading-zero preservation (PR-13).</strong> {@link #ssn} maps the COBOL
 * {@code CUST-SSN PIC 9(09)} to {@code ssn VARCHAR(9)} / {@link String} (never a numeric type) so
 * leading zeros are retained (e.g. {@code "012345678"}); a numeric mapping would silently drop
 * them.</p>
 *
 * <p><strong>Optimistic locking (PR-22).</strong> {@code @Version version} replaces VSAM
 * {@code READ UPDATE} exclusive-lock semantics with non-blocking optimistic concurrency; a
 * concurrent modification raises {@code OptimisticLockException} (mapped to HTTP 409). The column
 * is {@code INTEGER NOT NULL DEFAULT 0}, so the version is modeled as an {@link Integer}.</p>
 *
 * <p><strong>Auditing (AAP &sect;0.6.12).</strong> {@code @CreatedDate}/{@code @LastModifiedDate}/
 * {@code @CreatedBy}/{@code @LastModifiedBy} are populated by Spring Data's
 * {@code AuditingEntityListener} (wired via {@link EntityListeners}) when
 * {@code @EnableJpaAuditing} is active &mdash; addressing the audit-log gap noted in the original
 * system.</p>
 *
 * <p>Persistence and auditing annotations use the {@code jakarta.*} namespace (PR-28). Identity-based
 * {@link #equals(Object)}/{@link #hashCode()} derive from the natural primary key {@code custId}
 * alone ({@code cust_id BIGINT NOT NULL PRIMARY KEY}).</p>
 *
 * @see Account
 * @see Card
 * @see CardXref
 */
@Entity
@Table(name = "customers")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    /**
     * Maps COBOL {@code CUST-ID PIC 9(09)} &rarr; {@code cust_id BIGINT NOT NULL PRIMARY KEY}. The
     * natural 9-digit customer identifier; also the target of the foreign key
     * {@code card_xref.xref_cust_id}. Held as a {@link Long} for headroom and to match the
     * {@code BIGINT} column.
     */
    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    /**
     * Maps COBOL {@code CUST-FIRST-NAME PIC X(25)} &rarr; {@code first_name VARCHAR(25) NOT NULL}.
     */
    @Column(name = "first_name", length = 25, nullable = false)
    private String firstName;

    /** Maps COBOL {@code CUST-MIDDLE-NAME PIC X(25)} &rarr; {@code middle_name VARCHAR(25)}. */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /**
     * Maps COBOL {@code CUST-LAST-NAME PIC X(25)} &rarr; {@code last_name VARCHAR(25) NOT NULL}.
     */
    @Column(name = "last_name", length = 25, nullable = false)
    private String lastName;

    /** Maps COBOL {@code CUST-ADDR-LINE-1 PIC X(50)} &rarr; {@code addr_line_1 VARCHAR(50)}. */
    @Column(name = "addr_line_1", length = 50)
    private String addrLine1;

    /** Maps COBOL {@code CUST-ADDR-LINE-2 PIC X(50)} &rarr; {@code addr_line_2 VARCHAR(50)}. */
    @Column(name = "addr_line_2", length = 50)
    private String addrLine2;

    /** Maps COBOL {@code CUST-ADDR-LINE-3 PIC X(50)} &rarr; {@code addr_line_3 VARCHAR(50)}. */
    @Column(name = "addr_line_3", length = 50)
    private String addrLine3;

    /**
     * Maps COBOL {@code CUST-ADDR-STATE-CD PIC X(02)} &rarr; {@code addr_state_cd CHAR(2)}.
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width {@code CHAR} binding required by
     * {@code ddl-auto: validate} (a plain {@code String} resolves to {@code VARCHAR} and would fail
     * validation against the committed schema).
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "addr_state_cd", length = 2)
    private String stateCd;

    /**
     * Maps COBOL {@code CUST-ADDR-COUNTRY-CD PIC X(03)} &rarr; {@code addr_country_cd CHAR(3)}.
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width {@code CHAR} binding required by
     * {@code ddl-auto: validate}.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "addr_country_cd", length = 3)
    private String countryCd;

    /** Maps COBOL {@code CUST-ADDR-ZIP PIC X(10)} &rarr; {@code addr_zip VARCHAR(10)}. */
    @Column(name = "addr_zip", length = 10)
    private String zipCd;

    /** Maps COBOL {@code CUST-PHONE-NUM-1 PIC X(15)} &rarr; {@code phone_num_1 VARCHAR(15)}. */
    @Column(name = "phone_num_1", length = 15)
    private String phoneNum1;

    /** Maps COBOL {@code CUST-PHONE-NUM-2 PIC X(15)} &rarr; {@code phone_num_2 VARCHAR(15)}. */
    @Column(name = "phone_num_2", length = 15)
    private String phoneNum2;

    /**
     * Maps COBOL {@code CUST-SSN PIC 9(09)} &rarr; {@code ssn VARCHAR(9)}. Stored as a
     * {@link String} (never numeric) to preserve leading zeros per PR-13 (e.g. {@code "012345678"}).
     * <strong>PII (PR-20):</strong> masked to {@code ***-**-####} by {@code CustomerMapper} on
     * outbound DTOs; the entity always holds the unmasked value.
     */
    @Column(name = "ssn", length = 9)
    private String ssn;

    /** Maps COBOL {@code CUST-GOVT-ISSUED-ID PIC X(20)} &rarr; {@code govt_issued_id VARCHAR(20)}. */
    @Column(name = "govt_issued_id", length = 20)
    private String govtIssuedId;

    /**
     * Date of birth. Maps COBOL {@code CUST-DOB-YYYY-MM-DD PIC X(10)} &rarr; {@code dob DATE}. The
     * 10-character text date is normalized to a SQL {@code DATE} / {@link LocalDate}, consistent
     * with the {@code DATE} mappings on {@link Account} and {@link Card}.
     */
    @Column(name = "dob")
    private LocalDate dob;

    /** Maps COBOL {@code CUST-EFT-ACCOUNT-ID PIC X(10)} &rarr; {@code eft_account_id VARCHAR(10)}. */
    @Column(name = "eft_account_id", length = 10)
    private String eftAccountId;

    /**
     * Maps COBOL {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} &rarr; {@code pri_card_holder_ind
     * CHAR(1)} (e.g. {@code "Y"}/{@code "N"}). {@code @JdbcTypeCode(Types.CHAR)} forces the
     * fixed-width {@code CHAR} binding required by {@code ddl-auto: validate}.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "pri_card_holder_ind", length = 1)
    private String primaryCardHolderInd;

    /**
     * Maps COBOL {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} &rarr; {@code fico_credit_score SMALLINT}.
     * The 3-digit FICO score (range 0&ndash;999) is modeled as an {@link Integer} for headroom;
     * {@code @JdbcTypeCode(Types.SMALLINT)} forces the {@code SMALLINT} JDBC binding so Hibernate's
     * {@code ddl-auto: validate} matches the committed {@code SMALLINT} column (a plain
     * {@code Integer} resolves to {@code INTEGER} and would fail validation).
     */
    @JdbcTypeCode(Types.SMALLINT)
    @Column(name = "fico_credit_score")
    private Integer ficoScore;

    /**
     * Optimistic-locking version (PR-22). Maps {@code version INTEGER NOT NULL DEFAULT 0}; managed
     * by Hibernate. Modeled as {@link Integer} to match the {@code INTEGER} column.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * Audit timestamp set once on initial persist by Spring Data's {@code AuditingEntityListener}
     * via {@code @CreatedDate} (active when {@code @EnableJpaAuditing} is present); immutable
     * thereafter ({@code updatable = false}). Maps to {@code created_date TIMESTAMP NOT NULL DEFAULT
     * CURRENT_TIMESTAMP} (AAP &sect;0.6.12).
     */
    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit timestamp refreshed on every update by Spring Data's {@code AuditingEntityListener} via
     * {@code @LastModifiedDate}. Maps to {@code last_modified_date TIMESTAMP NOT NULL DEFAULT
     * CURRENT_TIMESTAMP} (AAP &sect;0.6.12).
     */
    @LastModifiedDate
    @Column(name = "last_modified_date", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Identity of the principal that created the record, populated once on persist via
     * {@code @CreatedBy} ({@code updatable = false}). Maps to {@code created_by VARCHAR(50)}.
     */
    @CreatedBy
    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;

    /**
     * Identity of the principal that last modified the record, refreshed on update via
     * {@code @LastModifiedBy}. Maps to {@code last_modified_by VARCHAR(50)}.
     */
    @LastModifiedBy
    @Column(name = "last_modified_by", length = 50)
    private String updatedBy;

    // -------------------- equals / hashCode (natural-key identity) --------------------

    /**
     * Identity equality based solely on the natural primary key {@link #custId}. Two
     * {@code Customer} instances are equal only when both have a non-null, equal {@code custId}.
     * Transient (unsaved) instances with a {@code null} customer id are therefore never equal to
     * one another.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer)) {
            return false;
        }
        Customer that = (Customer) o;
        return custId != null && custId.equals(that.custId);
    }

    /**
     * Hash code derived from the natural primary key {@link #custId} (0 when {@code custId} is
     * {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return custId != null ? custId.hashCode() : 0;
    }
}

package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the legacy CardDemo customer master to the relational
 * {@code customers} table.
 *
 * <p><strong>Source of truth.</strong> This entity is a faithful, field-for-field
 * port of the COBOL copybook {@code app/cpy/CVCUS01Y.cpy} (record name
 * {@code CUSTOMER-RECORD}, fixed record length 500) that backed the legacy VSAM
 * KSDS dataset {@code AWS.M2.CARDDEMO.CUSTDATA} (primary-key length 9, relative
 * key position 0 &#8594; {@code CUST-ID}, per {@code app/catlg/LISTCAT.txt}). The
 * transformation is part of the COBOL&#8594;Java migration described in AAP
 * &sect;0.4.1.1 (Entity Layer) and follows the data-type rules of AAP &sect;0.1.2.</p>
 *
 * <p><strong>Schema contract.</strong> The mapping is bound to
 * {@code src/main/resources/db/migration/V1__schema.sql}. Flyway owns the schema
 * and Hibernate runs with {@code spring.jpa.hibernate.ddl-auto=validate}, so every
 * column name, JDBC type, and the {@code cust_id} primary key declared here MUST
 * match that DDL exactly &mdash; otherwise application boot fails schema
 * validation. The 18 persistent fields below correspond 1:1 to the 18 columns of
 * the {@code customers} table; the legacy {@code FILLER X(168)} trailing bytes are
 * intentionally <em>not</em> persisted.</p>
 *
 * <p><strong>Fixed-point and date rules.</strong> The numeric identifiers
 * {@code CUST-ID 9(09)} and {@code CUST-FICO-CREDIT-SCORE 9(03)} map to
 * {@link Long} ({@code BIGINT}) and {@link Integer} ({@code INTEGER}). The date
 * field {@code CUST-DOB-YYYY-MM-DD X(10)} maps to {@link LocalDate} ({@code DATE}).
 * The fixed-width {@code CHAR} fields {@code addr_state_cd} (2) and
 * {@code pri_card_holder_ind} (1) carry {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )}
 * so Hibernate emits JDBC type {@code CHAR} (code&nbsp;1) rather than the default
 * {@code VARCHAR} (code&nbsp;12); without this, Hibernate&nbsp;6.4 schema validation
 * rejects the {@code CHAR} columns.</p>
 *
 * <p><strong>SSN as String.</strong> Although {@code CUST-SSN} is declared
 * {@code 9(09)} (numeric) in COBOL, the column is {@code VARCHAR(9)} and the SSN is
 * modeled as a {@link String}. This preserves any leading zeros (which a numeric
 * type would silently drop) and matches the binding contract.</p>
 *
 * <p><strong>PII suppression (AAP &sect;0.6.8).</strong> This record carries
 * personally identifiable information &mdash; the full Social Security Number
 * ({@link #ssn}), the government-issued identifier ({@link #govtIssuedId}), and the
 * date of birth ({@link #dob}). The SSN is <em>persisted in full</em> because the
 * legacy data model stores it, but it MUST be exposed at most as its last four
 * digits at the DTO/mapper boundary and MUST NEVER appear in full in any API
 * response or log line. To keep the entity itself log-safe, {@link #toString()}
 * masks the SSN to its last four digits and omits the government id entirely;
 * comprehensive suppression at the API boundary is enforced separately in the
 * DTO/mapper layer and Logback masking.</p>
 *
 * <p><strong>Design.</strong> A deliberately flat, scalar mapping &mdash; there are
 * no JPA associations to {@code Account}, {@code Card}, or {@code CardXref};
 * referential integrity is expressed by foreign keys in the schema and navigated by
 * repositories. The primary key is <em>assigned</em> (carried over from the legacy
 * data), so there is no {@code @GeneratedValue}.</p>
 */
@Entity
@Table(name = "customers")
public class Customer {

    /**
     * Customer identifier &mdash; legacy {@code CUST-ID PIC 9(09)}; column
     * {@code cust_id BIGINT}. Primary key (VSAM KSDS key length 9, RKP 0). The id
     * is assigned by the migrated data, never database-generated.
     */
    @Id
    @Column(name = "cust_id")
    private Long custId;

    /** Legacy {@code CUST-FIRST-NAME PIC X(25)}; column {@code first_name VARCHAR(25)}. */
    @Column(name = "first_name", length = 25)
    private String firstName;

    /** Legacy {@code CUST-MIDDLE-NAME PIC X(25)}; column {@code middle_name VARCHAR(25)}. */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /** Legacy {@code CUST-LAST-NAME PIC X(25)}; column {@code last_name VARCHAR(25)}. */
    @Column(name = "last_name", length = 25)
    private String lastName;

    /** Legacy {@code CUST-ADDR-LINE-1 PIC X(50)}; column {@code addr_line_1 VARCHAR(50)}. */
    @Column(name = "addr_line_1", length = 50)
    private String addrLine1;

    /** Legacy {@code CUST-ADDR-LINE-2 PIC X(50)}; column {@code addr_line_2 VARCHAR(50)}. */
    @Column(name = "addr_line_2", length = 50)
    private String addrLine2;

    /** Legacy {@code CUST-ADDR-LINE-3 PIC X(50)}; column {@code addr_line_3 VARCHAR(50)}. */
    @Column(name = "addr_line_3", length = 50)
    private String addrLine3;

    /**
     * Legacy {@code CUST-ADDR-STATE-CD PIC X(02)}; column {@code addr_state_cd CHAR(2)}.
     * Fixed-width {@code CHAR}; {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )}
     * is required so Hibernate validation matches the {@code CHAR} column.
     */
    @Column(name = "addr_state_cd", length = 2)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String addrStateCd;

    /** Legacy {@code CUST-ADDR-COUNTRY-CD PIC X(03)}; column {@code addr_country_cd VARCHAR(3)}. */
    @Column(name = "addr_country_cd", length = 3)
    private String addrCountryCd;

    /** Legacy {@code CUST-ADDR-ZIP PIC X(10)}; column {@code addr_zip VARCHAR(10)}. */
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    /** Legacy {@code CUST-PHONE-NUM-1 PIC X(15)}; column {@code phone_num_1 VARCHAR(15)}. */
    @Column(name = "phone_num_1", length = 15)
    private String phoneNum1;

    /** Legacy {@code CUST-PHONE-NUM-2 PIC X(15)}; column {@code phone_num_2 VARCHAR(15)}. */
    @Column(name = "phone_num_2", length = 15)
    private String phoneNum2;

    /**
     * Legacy {@code CUST-SSN PIC 9(09)}; column {@code ssn VARCHAR(9)}. Modeled as a
     * {@link String} to preserve leading zeros and match the {@code VARCHAR} contract.
     * Persisted in full; never exposed beyond its last four digits (AAP &sect;0.6.8).
     */
    @Column(name = "ssn", length = 9)
    private String ssn;

    /**
     * Legacy {@code CUST-GOVT-ISSUED-ID PIC X(20)}; column {@code govt_issued_id VARCHAR(20)}.
     * Sensitive identifier &mdash; omitted from {@link #toString()} (AAP &sect;0.6.8).
     */
    @Column(name = "govt_issued_id", length = 20)
    private String govtIssuedId;

    /**
     * Legacy {@code CUST-DOB-YYYY-MM-DD PIC X(10)}; column {@code dob DATE}. Stored as
     * an ISO {@link LocalDate}.
     */
    @Column(name = "dob")
    private LocalDate dob;

    /** Legacy {@code CUST-EFT-ACCOUNT-ID PIC X(10)}; column {@code eft_account_id VARCHAR(10)}. */
    @Column(name = "eft_account_id", length = 10)
    private String eftAccountId;

    /**
     * Legacy {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}; column
     * {@code pri_card_holder_ind CHAR(1)}. Fixed-width {@code CHAR};
     * {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )} is required so
     * Hibernate validation matches the {@code CHAR} column.
     */
    @Column(name = "pri_card_holder_ind", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String priCardHolderInd;

    /** Legacy {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}; column {@code fico_credit_score INTEGER}. */
    @Column(name = "fico_credit_score")
    private Integer ficoCreditScore;

    /**
     * Creates an empty customer instance. Required by the JPA provider for
     * entity instantiation and used by mappers and tests; callers populate the
     * persistent fields through the setters below.
     */
    public Customer() {
        // No-args constructor required by Jakarta Persistence 3.1.
    }

    // ------------------------------------------------------------------
    // Accessors: one getter/setter pair per persistent column.
    // ------------------------------------------------------------------

    public Long getCustId() {
        return custId;
    }

    public void setCustId(Long custId) {
        this.custId = custId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAddrLine1() {
        return addrLine1;
    }

    public void setAddrLine1(String addrLine1) {
        this.addrLine1 = addrLine1;
    }

    public String getAddrLine2() {
        return addrLine2;
    }

    public void setAddrLine2(String addrLine2) {
        this.addrLine2 = addrLine2;
    }

    public String getAddrLine3() {
        return addrLine3;
    }

    public void setAddrLine3(String addrLine3) {
        this.addrLine3 = addrLine3;
    }

    public String getAddrStateCd() {
        return addrStateCd;
    }

    public void setAddrStateCd(String addrStateCd) {
        this.addrStateCd = addrStateCd;
    }

    public String getAddrCountryCd() {
        return addrCountryCd;
    }

    public void setAddrCountryCd(String addrCountryCd) {
        this.addrCountryCd = addrCountryCd;
    }

    public String getAddrZip() {
        return addrZip;
    }

    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    public String getPhoneNum1() {
        return phoneNum1;
    }

    public void setPhoneNum1(String phoneNum1) {
        this.phoneNum1 = phoneNum1;
    }

    public String getPhoneNum2() {
        return phoneNum2;
    }

    public void setPhoneNum2(String phoneNum2) {
        this.phoneNum2 = phoneNum2;
    }

    public String getSsn() {
        return ssn;
    }

    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = govtIssuedId;
    }

    public LocalDate getDob() {
        return dob;
    }

    public void setDob(LocalDate dob) {
        this.dob = dob;
    }

    public String getEftAccountId() {
        return eftAccountId;
    }

    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    public String getPriCardHolderInd() {
        return priCardHolderInd;
    }

    public void setPriCardHolderInd(String priCardHolderInd) {
        this.priCardHolderInd = priCardHolderInd;
    }

    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }

    public void setFicoCreditScore(Integer ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    // ------------------------------------------------------------------
    // Identity and representation.
    // ------------------------------------------------------------------

    /**
     * Entity equality is defined by the assigned primary key {@link #custId}.
     * Two customers are equal only when both carry the same non-null id; two
     * transient customers with {@code null} ids are never considered equal, which
     * is the recommended behavior for JPA entities.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Customer} with the same id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer)) {
            return false;
        }
        Customer other = (Customer) o;
        return custId != null && custId.equals(other.custId);
    }

    /**
     * Hash code derived from the assigned primary key {@link #custId}. Because the
     * id is assigned (never reassigned after the entity is created or loaded), the
     * value is stable across the entity lifecycle and consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of {@link #custId} (0 when the id is {@code null})
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(custId);
    }

    /**
     * Returns a log-safe string representation. The Social Security Number is
     * masked to its last four digits and the government-issued identifier is
     * omitted entirely, honoring the PII-suppression rule of AAP &sect;0.6.8.
     * Full PII suppression for API responses is enforced separately in the
     * DTO/mapper layer.
     *
     * @return a diagnostic representation that never reveals the full SSN or the
     *         government-issued id
     */
    @Override
    public String toString() {
        return "Customer{"
                + "custId=" + custId
                + ", firstName='" + firstName + '\''
                + ", middleName='" + middleName + '\''
                + ", lastName='" + lastName + '\''
                + ", addrLine1='" + addrLine1 + '\''
                + ", addrLine2='" + addrLine2 + '\''
                + ", addrLine3='" + addrLine3 + '\''
                + ", addrStateCd='" + addrStateCd + '\''
                + ", addrCountryCd='" + addrCountryCd + '\''
                + ", addrZip='" + addrZip + '\''
                + ", phoneNum1='" + phoneNum1 + '\''
                + ", phoneNum2='" + phoneNum2 + '\''
                + ", ssn='" + maskSsn(ssn) + '\''
                + ", dob=" + dob
                + ", eftAccountId='" + eftAccountId + '\''
                + ", priCardHolderInd='" + priCardHolderInd + '\''
                + ", ficoCreditScore=" + ficoCreditScore
                + '}';
    }

    /**
     * Masks a Social Security Number so that, at most, its last four digits are
     * revealed (AAP &sect;0.6.8). Values of four characters or fewer are masked
     * completely so no digits leak.
     *
     * @param value the raw SSN, may be {@code null}
     * @return {@code null} when {@code value} is {@code null}; otherwise the value
     *         with every character except the final four replaced by {@code '*'}
     */
    private static String maskSsn(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + value.substring(length - 4);
    }
}

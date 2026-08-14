package com.carddemo.notification.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The ten cardholder fields one alert reports, held for one account.
 *
 * <p>One instance maps to one row of {@code cardholder_context}, whose thirteen columns this module
 * declares in {@code src/main/resources/db/migration/V1__schema.sql}. The primary key
 * {@code pk_cardholder_context} covers {@code account_id} alone.
 *
 * <p>{@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} reads the same ten
 * fields: the name at {@code L462-L469}, the first two address lines at {@code L470-L471}, the third
 * at {@code L472-L481} and the credit score at {@code L485}. That program read them from the
 * customer record directly, through the copybook {@code app/cbl/CBSTM03A.CBL:L55} copies, because
 * one program held every dataset. This service owns no customer record.
 *
 * <p>Each column and the source field it maps, from {@code app/cpy/CVCUS01Y.cpy}:
 *
 * <pre>
 * CUST-FIRST-NAME         L6    first_name        VARCHAR(25)
 * CUST-MIDDLE-NAME        L7    middle_name       VARCHAR(25)
 * CUST-LAST-NAME          L8    last_name         VARCHAR(25)
 * CUST-ADDR-LINE-1        L9    address_line_1    VARCHAR(50)
 * CUST-ADDR-LINE-2        L10   address_line_2    VARCHAR(50)
 * CUST-ADDR-LINE-3        L11   address_line_3    VARCHAR(50)
 * CUST-ADDR-STATE-CD      L12   state_code        VARCHAR(2)
 * CUST-ADDR-COUNTRY-CD    L13   country_code      VARCHAR(3)
 * CUST-ADDR-ZIP           L14   zip_code          VARCHAR(10)
 * CUST-FICO-CREDIT-SCORE  L22   fico_score        CHAR(3)
 * </pre>
 *
 * <p>{@code account_id} carries {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7},
 * which is the key every {@code CustomerContextChanged} event travels under.
 *
 * <p>ADDITIVE as a table: two columns carry no source field. {@code source_occurred_at} holds the
 * {@code occurredAt} of the event the row was built from, and
 * {@code repository/CardholderContextRepository} compares it so an older event applies nothing.
 * {@code observed_at} holds when this service applied the row.
 *
 * <p>The remaining fields of {@code app/cpy/CVCUS01Y.cpy} carry no column here: the two telephone
 * numbers at {@code L15-L16}, the national identifier at {@code L17}, the government identifier at
 * {@code L18}, the date of birth at {@code L19}, the funds-transfer account at {@code L20}, the
 * primary-holder indicator at {@code L21} and the trailing {@code FILLER PIC X(168)} at
 * {@code L23}. No alert reports one, so this service holds none of them.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "cardholder_context",
        indexes = @Index(name = "ix_cardholder_context_observed_at",
                columnList = "observed_at"))
public class CardholderContextEntity {

    /**
     * The account this context belongs to, eleven digit characters with any leading zero kept.
     *
     * <p>Maps to {@code account_id CHAR(11) NOT NULL}, which carries the primary key
     * {@code pk_cardholder_context}.</p>
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_id", nullable = false, updatable = false,
            length = PicClause.XREF_ACCT_ID_WIDTH)
    private String accountId;

    /** Source: {@code CUST-FIRST-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L6}. */
    @Column(name = "first_name", nullable = false, length = PicClause.CUST_FIRST_NAME_WIDTH)
    private String firstName;

    /** Source: {@code CUST-MIDDLE-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L7}. */
    @Column(name = "middle_name", nullable = false, length = PicClause.CUST_MIDDLE_NAME_WIDTH)
    private String middleName;

    /** Source: {@code CUST-LAST-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L8}. */
    @Column(name = "last_name", nullable = false, length = PicClause.CUST_LAST_NAME_WIDTH)
    private String lastName;

    /** Source: {@code CUST-ADDR-LINE-1 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L9}. */
    @Column(name = "address_line_1", nullable = false,
            length = PicClause.CUST_ADDR_LINE_1_WIDTH)
    private String addressLine1;

    /** Source: {@code CUST-ADDR-LINE-2 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L10}. */
    @Column(name = "address_line_2", nullable = false,
            length = PicClause.CUST_ADDR_LINE_2_WIDTH)
    private String addressLine2;

    /** Source: {@code CUST-ADDR-LINE-3 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L11}. */
    @Column(name = "address_line_3", nullable = false,
            length = PicClause.CUST_ADDR_LINE_3_WIDTH)
    private String addressLine3;

    /** Source: {@code CUST-ADDR-STATE-CD PIC X(2)} at {@code app/cpy/CVCUS01Y.cpy:L12}. */
    @Column(name = "state_code", nullable = false,
            length = PicClause.CUST_ADDR_STATE_CD_WIDTH)
    private String stateCode;

    /** Source: {@code CUST-ADDR-COUNTRY-CD PIC X(3)} at {@code app/cpy/CVCUS01Y.cpy:L13}. */
    @Column(name = "country_code", nullable = false,
            length = PicClause.CUST_ADDR_COUNTRY_CD_WIDTH)
    private String countryCode;

    /** Source: {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14}. */
    @Column(name = "zip_code", nullable = false, length = PicClause.CUST_ADDR_ZIP_WIDTH)
    private String zipCode;

    /**
     * The credit score, three digit characters with any leading zero kept.
     *
     * <p>Source: {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22}. The
     * migration declares the column {@code CHAR(3)} and constrains it to three digits.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fico_score", nullable = false,
            length = PicClause.CUST_FICO_CREDIT_SCORE_WIDTH)
    private String ficoScore;

    /**
     * The {@code occurredAt} of the event this row was built from.
     *
     * <p>ADDITIVE. The upsert applies nothing whose value is not later than the stored one, so a
     * redelivered or reordered event cannot move the row backwards.</p>
     */
    @Column(name = "source_occurred_at", nullable = false)
    private Instant sourceOccurredAt;

    /** When this service last applied an event to this row. ADDITIVE. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Required by the persistence provider. */
    protected CardholderContextEntity() {
    }

    /**
     * Takes the key, the ten cardholder values in column order, and the two additive instants.
     *
     * @param accountId the account this context belongs to, up to
     *        {@value PicClause#XREF_ACCT_ID_WIDTH} digits
     * @param firstName the given name
     * @param middleName the middle name
     * @param lastName the family name
     * @param addressLine1 the first address line
     * @param addressLine2 the second address line
     * @param addressLine3 the third address line
     * @param stateCode the state code
     * @param countryCode the country code
     * @param zipCode the mail code
     * @param ficoScore the credit score, up to
     *        {@value PicClause#CUST_FICO_CREDIT_SCORE_WIDTH} digits
     * @param sourceOccurredAt the {@code occurredAt} of the event this row was built from
     * @param observedAt when this service applied the row
     * @throws NullPointerException when an argument is null
     * @throws IllegalArgumentException when {@code accountId} or {@code ficoScore} is not the digit
     *         count its column holds
     */
    public CardholderContextEntity(String accountId, String firstName, String middleName,
            String lastName, String addressLine1, String addressLine2, String addressLine3,
            String stateCode, String countryCode, String zipCode, String ficoScore,
            Instant sourceOccurredAt, Instant observedAt) {
        this.accountId = requireDigits(accountId, PicClause.XREF_ACCT_ID_WIDTH, "accountId");
        this.firstName = Objects.requireNonNull(firstName, "firstName is required");
        this.middleName = Objects.requireNonNull(middleName, "middleName is required");
        this.lastName = Objects.requireNonNull(lastName, "lastName is required");
        this.addressLine1 = Objects.requireNonNull(addressLine1, "addressLine1 is required");
        this.addressLine2 = Objects.requireNonNull(addressLine2, "addressLine2 is required");
        this.addressLine3 = Objects.requireNonNull(addressLine3, "addressLine3 is required");
        this.stateCode = Objects.requireNonNull(stateCode, "stateCode is required");
        this.countryCode = Objects.requireNonNull(countryCode, "countryCode is required");
        this.zipCode = Objects.requireNonNull(zipCode, "zipCode is required");
        this.ficoScore =
                requireDigits(ficoScore, PicClause.CUST_FICO_CREDIT_SCORE_WIDTH, "ficoScore");
        this.sourceOccurredAt =
                Objects.requireNonNull(sourceOccurredAt, "sourceOccurredAt is required");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt is required");
    }

    /**
     * Checks a digit-character argument against the width its column declares, then left-pads a
     * shorter value with zeros.
     *
     * @param value the argument
     * @param width the digit count the column holds
     * @param field the field name the message reports
     * @return the argument at {@code width} digits
     * @throws NullPointerException when the argument is null
     * @throws IllegalArgumentException when the argument is empty, wider than the column, or
     *         carries a character that is not a digit
     */
    private static String requireDigits(String value, int width, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isEmpty() || value.length() > width
                || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " holds \"" + value
                    + "\" and the column declares up to " + width + " digits");
        }
        return "0".repeat(width - value.length()) + value;
    }

    /** @return the account this context belongs to */
    public String getAccountId() {
        return accountId;
    }

    /** @return the given name */
    public String getFirstName() {
        return firstName;
    }

    /** @return the middle name */
    public String getMiddleName() {
        return middleName;
    }

    /** @return the family name */
    public String getLastName() {
        return lastName;
    }

    /** @return the first address line */
    public String getAddressLine1() {
        return addressLine1;
    }

    /** @return the second address line */
    public String getAddressLine2() {
        return addressLine2;
    }

    /** @return the third address line */
    public String getAddressLine3() {
        return addressLine3;
    }

    /** @return the state code */
    public String getStateCode() {
        return stateCode;
    }

    /** @return the country code */
    public String getCountryCode() {
        return countryCode;
    }

    /** @return the mail code */
    public String getZipCode() {
        return zipCode;
    }

    /** @return the credit score */
    public String getFicoScore() {
        return ficoScore;
    }

    /** @return the {@code occurredAt} of the event this row was built from */
    public Instant getSourceOccurredAt() {
        return sourceOccurredAt;
    }

    /** @return when this service last applied an event to this row */
    public Instant getObservedAt() {
        return observedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardholderContextEntity that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }

    /**
     * Renders the type and the field count, and no field value.
     *
     * <p>Ten fields name a person, an address or a credit score, and the key names an account. A
     * caller reads them through their accessors.</p>
     *
     * @return a fixed description carrying no cardholder value
     */
    @Override
    public String toString() {
        return "CardholderContextEntity[13 fields withheld]";
    }
}

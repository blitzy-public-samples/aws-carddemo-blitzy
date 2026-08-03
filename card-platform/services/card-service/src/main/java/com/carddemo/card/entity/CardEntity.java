package com.carddemo.card.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of {@code card}, the table the card service owns.
 *
 * <p>Transformed from {@code 01 CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy:L4}, version stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} at {@code app/cpy/CVACT02Y.cpy:L13}. The copybook header at
 * {@code app/cpy/CVACT02Y.cpy:L2} states RECLN 150. The seven field widths sum to that length:
 * 16 plus 11 plus 3 plus 50 plus 10 plus 1 plus 59. Every width below comes from
 * {@link PicClause}.
 *
 * <p>The copybook fields and their lines are {@code CARD-NUM PIC X(16)} at L5,
 * {@code CARD-ACCT-ID PIC 9(11)} at L6, {@code CARD-CVV-CD PIC 9(03)} at L7,
 * {@code CARD-EMBOSSED-NAME PIC X(50)} at L8, {@code CARD-EXPIRAION-DATE PIC X(10)} at L9, and
 * {@code CARD-ACTIVE-STATUS PIC X(01)} at L10. Each field Javadoc below repeats its own locator and
 * names its column.
 *
 * <p>DEVIATION, dropped field: the trailing {@code FILLER PIC X(59)} at
 * {@code app/cpy/CVACT02Y.cpy:L11} carries no column and no Java field. Columns 92 through 150 hold
 * a blank on all fifty records of {@code app/data/ASCII/carddata.txt}.
 *
 * <p>DEVIATION, corrected spelling: the copybook spells the field at
 * {@code app/cpy/CVACT02Y.cpy:L9} {@code CARD-EXPIRAION-DATE}, and both the Java field
 * {@link #getExpirationDate()} and the column {@code expiration_date} spell it {@code expiration}.
 * No source behaviour reads the identifier text.
 *
 * <p>DEVIATION, two column types for one Picture clause: {@code expiration_date} is a {@code DATE}
 * column here, {@link PicClause#CARD_EXPIRATION_DATE_COLUMN_TYPE}. The account service holds
 * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11} as
 * {@link PicClause#ACCT_EXPIRATION_DATE_COLUMN_TYPE}, and
 * {@code app/cbl/CBTRN02C.cbl:L414} compares that field with the first ten characters of a
 * 26-character origin timestamp as text.
 *
 * <p>DEVIATION, two column types for one source field: {@code card_number} is {@code CHAR(16)}
 * here. The authorization service declares the same source field {@code VARCHAR(16)} in its own
 * {@code card_xref} replica. The two services share no table.
 *
 * <p>The key strategy comes from the Job Control Language (JCL) member that defines the Virtual
 * Storage Access Method (VSAM) dataset. {@code KEYS(16 0)} at {@code app/jcl/CARDFILE.jcl:L54}
 * declares a sixteen-byte key at offset zero, which is {@code CARD-NUM}, and
 * {@code RECORDSIZE(150 150)} at {@code app/jcl/CARDFILE.jcl:L55} fixes the row width.
 * {@code KEYS(11 16)} at {@code app/jcl/CARDFILE.jcl:L85} declares an eleven-byte Alternate Index
 * (AIX) key at offset 16, where {@code CARD-ACCT-ID} starts.
 *
 * <p>{@code NONUNIQUEKEY} at {@code app/jcl/CARDFILE.jcl:L86} admits many rows per account, so
 * {@code idx_card_account_id} carries no unique constraint.
 * {@code app/cbl/COCRDLIC.cbl:L1157-L1158} treats {@code DFHRESP(NORMAL)} and
 * {@code DFHRESP(DUPREC)} as one outcome, and the same pair repeats at L1208-L1209, at
 * L1305-L1306 and at L1333-L1334.
 *
 * <p>Both source read paths survive the transformation.
 * {@code app/cbl/COCRDSLC.cbl:L742-L744} reads the card file on the primary key.
 * {@code app/cbl/COCRDSLC.cbl:L783-L785} reads it through the alternate index on an account
 * identifier. The path definition at {@code app/jcl/CARDFILE.jcl:L100-L102} names the dataset that
 * {@code app/csd/CARDDEMO.CSD:L13-L14} opens as {@code CARDAIX}.
 *
 * <p>{@code src/main/resources/db/migration/V1__schema.sql:L18-L37} creates the table and the
 * index. Hibernate runs under {@code ddl-auto: validate} at
 * {@code src/main/resources/application.yml:L39}, so a column name, type or nullability that drifts
 * from the migration fails at start-up against PostgreSQL 18.4. The migration writes every Data
 * Definition Language (DDL) object unqualified, and
 * {@code spring.jpa.properties.hibernate.default_schema} at
 * {@code src/main/resources/application.yml:L42} names the schema at run time. The {@link Table}
 * annotation below sets no {@code schema} attribute.
 *
 * <p>{@code card_number} holds the full Primary Account Number (PAN). A caller passes that value
 * through {@code com.carddemo.cobol.PanMasker} before it reaches an event payload, a log line or an
 * Application Programming Interface (API) response.
 * {@code app/cbl/COCRDLIC.cbl:L1165-L1171} builds a list row from {@code CARD-NUM},
 * {@code CARD-ACCT-ID} and {@code CARD-ACTIVE-STATUS} alone. No method here renders the row as text
 * and no statement here logs.
 *
 * <p>The constructor adds no checksum. {@code app/cbl/COCRDUPC.cbl:L194} carries the only
 * card-number rule in the source, and its text names sixteen digits. This class also adds no rule
 * around {@code active_status}: {@code app/jcl/POSTTRAN.jcl} allocates six datasets for the posting
 * program and no card dataset, so posting reads no card status.
 *
 * <p>Design decisions and every deviation above: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "card",
        indexes = @Index(name = "idx_card_account_id", columnList = "account_id"))
public class CardEntity {

    /**
     * Digits after the decimal point in both {@code NUMERIC} columns.
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6} and
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} are unsigned integer
     * display numerics, and {@link PicClause} declares no scale constant for either one.
     */
    private static final int IDENTIFIER_SCALE = 0;

    /**
     * The affirmative value of {@code active_status}, from
     * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    private static final String ACTIVE_STATUS_YES = "Y";

    /**
     * The negative value of {@code active_status}, from the same condition name at
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    private static final String ACTIVE_STATUS_NO = "N";

    /**
     * Full card number, {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
     *
     * <p>Column {@code card_number CHAR(16) NOT NULL}, the primary key from {@code KEYS(16 0)} at
     * {@code app/jcl/CARDFILE.jcl:L54}. A caller supplies the value; no sequence and no generator
     * assigns one. The Picture clause is alphanumeric, so any character may occupy any of the
     * sixteen positions. All fifty rows of {@code app/data/ASCII/carddata.txt} carry sixteen
     * numeric characters here, row one holding {@code 0500024453765740}.
     *
     * <p>PostgreSQL reports a {@code CHAR} column as {@code bpchar} through Java Database
     * Connectivity (JDBC) metadata, and {@link Column#columnDefinition()} names that type
     * verbatim. The start-up check compares the mapped type with the reported type.
     */
    @Id
    @Column(name = "card_number", nullable = false,
            length = PicClause.CARD_NUM_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CARD_NUM_WIDTH + ")")
    private String cardNumber;

    /**
     * Account identifier, {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}.
     *
     * <p>Column {@code account_id NUMERIC(11,0) NOT NULL}, indexed and not unique by
     * {@code idx_card_account_id}. One account holds many cards, and
     * {@code app/cbl/COCRDLIC.cbl} pages through them. Columns 17 through 27 of every row of
     * {@code app/data/ASCII/carddata.txt} carry eleven numeric characters, row one holding
     * {@code 00000000050}.
     */
    @Column(name = "account_id", nullable = false,
            precision = PicClause.CARD_ACCT_ID_WIDTH, scale = IDENTIFIER_SCALE)
    private BigDecimal accountId;

    /**
     * Card verification value, {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     *
     * <p>Column {@code card_verification_value NUMERIC(3,0) NOT NULL}. The source stores this
     * field in the clear and the column keeps it. No event payload, no log line and no API
     * response carries the value. Columns 28 through 30 of every row of
     * {@code app/data/ASCII/carddata.txt} carry three numeric characters, row one holding
     * {@code 747}.
     */
    @Column(name = "card_verification_value", nullable = false,
            precision = PicClause.CARD_CVV_CD_WIDTH, scale = IDENTIFIER_SCALE)
    private BigDecimal cardVerificationValue;

    /**
     * Embossed cardholder name, {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     *
     * <p>Column {@code embossed_name CHAR(50) NOT NULL}, fixed width and space padded. Columns 31
     * through 80 of row one of {@code app/data/ASCII/carddata.txt} carry {@code Aniya Von} padded
     * with spaces to the full fifty bytes. {@code app/cbl/COCRDUPC.cbl:L1499-L1501} folds the
     * field to upper case before it compares.
     */
    @Column(name = "embossed_name", nullable = false,
            length = PicClause.CARD_EMBOSSED_NAME_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CARD_EMBOSSED_NAME_WIDTH + ")")
    private String embossedName;

    /**
     * Expiration date. The copybook spells the field {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}.
     *
     * <p>Column {@code expiration_date DATE NOT NULL}. The ten characters read
     * {@code YYYY-MM-DD}, and the source only ever decomposes or displays them.
     * {@code app/cbl/COCRDUPC.cbl:L115-L121} redefines the field as a four-character year, a
     * one-character separator, a two-character month, a second separator and a two-character day.
     * {@code app/cbl/COCRDUPC.cbl:L1467-L1474} reassembles it from year, month and day around two
     * literal hyphens, and {@code app/cbl/COCRDUPC.cbl:L1505-L1507} slices it as {@code (1:4)},
     * {@code (6:2)} and {@code (9:2)}.
     *
     * <p>Columns 81 through 90 match {@code YYYY-MM-DD} on all fifty rows of
     * {@code app/data/ASCII/carddata.txt} with no malformed value, row one holding
     * {@code 2023-03-09}. The years present are 2023 on eleven rows, 2024 on fourteen and 2025 on
     * twenty-five.
     */
    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    /**
     * Active status flag, {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10}.
     *
     * <p>Column {@code active_status CHAR(1) NOT NULL}, holding
     * {@value #ACTIVE_STATUS_YES} or {@value #ACTIVE_STATUS_NO} from
     * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}. The
     * source folds no case on this field. Column 91 holds {@value #ACTIVE_STATUS_YES} on all
     * fifty rows of {@code app/data/ASCII/carddata.txt}.
     */
    @Column(name = "active_status", nullable = false,
            length = PicClause.CARD_ACTIVE_STATUS_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CARD_ACTIVE_STATUS_WIDTH + ")")
    private String activeStatus;

    /**
     * No-argument constructor for the persistence provider.
     *
     * <p>Hibernate calls this constructor to materialise a row, then populates the six fields
     * directly. A row already in the database reaches no guard below. Application code calls
     * {@link #CardEntity(String, BigDecimal, BigDecimal, String, LocalDate, String)}.
     */
    protected CardEntity() {
    }

    /**
     * Builds one card row from the six mapped fields, in copybook order.
     *
     * @param cardNumber            the full card number, exactly
     *                              {@value PicClause#CARD_NUM_WIDTH} characters wide
     * @param accountId             the account identifier, an integer with no negative sign
     * @param cardVerificationValue the card verification value, an integer with no negative sign
     * @param embossedName          the embossed cardholder name, at most
     *                              {@value PicClause#CARD_EMBOSSED_NAME_WIDTH} characters wide
     * @param expirationDate        the expiration date
     * @param activeStatus          the active status flag, {@value #ACTIVE_STATUS_YES} or
     *                              {@value #ACTIVE_STATUS_NO}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the card number is not
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters wide, if the
     *                                  embossed name exceeds
     *                                  {@value PicClause#CARD_EMBOSSED_NAME_WIDTH} characters, if
     *                                  the active status is neither {@value #ACTIVE_STATUS_YES}
     *                                  nor {@value #ACTIVE_STATUS_NO}, or if either identifier
     *                                  carries a fractional part or a negative sign
     */
    public CardEntity(String cardNumber, BigDecimal accountId, BigDecimal cardVerificationValue,
            String embossedName, LocalDate expirationDate, String activeStatus) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(cardVerificationValue, "cardVerificationValue must not be null");
        Objects.requireNonNull(embossedName, "embossedName must not be null");
        Objects.requireNonNull(expirationDate, "expirationDate must not be null");
        Objects.requireNonNull(activeStatus, "activeStatus must not be null");

        requireCardNumberWidth(cardNumber);
        requireUnsignedInteger("accountId", accountId);
        requireUnsignedInteger("cardVerificationValue", cardVerificationValue);
        requireEmbossedNameWidth(embossedName);
        requireActiveStatusFlag(activeStatus);

        this.cardNumber = cardNumber;
        this.accountId = accountId;
        this.cardVerificationValue = cardVerificationValue;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
    }

    /**
     * Rejects a card number whose width is not {@value PicClause#CARD_NUM_WIDTH} characters.
     *
     * <p>The check reads the width and nothing else. {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5} is alphanumeric and fixed width, so no character class
     * applies and no checksum applies. The failure message carries no card-number characters.
     *
     * @param value the card number to check
     * @throws IllegalArgumentException if the width is not {@value PicClause#CARD_NUM_WIDTH}
     */
    private static void requireCardNumberWidth(String value) {
        if (value.length() != PicClause.CARD_NUM_WIDTH) {
            throw new IllegalArgumentException("cardNumber must be exactly "
                    + PicClause.CARD_NUM_WIDTH + " characters wide, found width "
                    + value.length());
        }
    }

    /**
     * Rejects an embossed name wider than {@value PicClause#CARD_EMBOSSED_NAME_WIDTH} characters.
     *
     * <p>{@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8} is space padded
     * on the record, so a shorter value is admitted at any width down to zero.
     *
     * @param value the embossed name to check
     * @throws IllegalArgumentException if the width exceeds
     *                                  {@value PicClause#CARD_EMBOSSED_NAME_WIDTH}
     */
    private static void requireEmbossedNameWidth(String value) {
        if (value.length() > PicClause.CARD_EMBOSSED_NAME_WIDTH) {
            throw new IllegalArgumentException("embossedName must be at most "
                    + PicClause.CARD_EMBOSSED_NAME_WIDTH + " characters wide, found width "
                    + value.length());
        }
    }

    /**
     * Rejects an active status flag outside the two-value domain of the column.
     *
     * <p>The width must be {@value PicClause#CARD_ACTIVE_STATUS_WIDTH} and the value must be
     * {@value #ACTIVE_STATUS_YES} or {@value #ACTIVE_STATUS_NO}, from
     * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}. The
     * comparison folds no case, matching the source.
     *
     * @param value the flag to check
     * @throws IllegalArgumentException if the width or the value is outside the domain
     */
    private static void requireActiveStatusFlag(String value) {
        if (value.length() != PicClause.CARD_ACTIVE_STATUS_WIDTH) {
            throw new IllegalArgumentException("activeStatus must be exactly "
                    + PicClause.CARD_ACTIVE_STATUS_WIDTH + " character wide, found width "
                    + value.length());
        }
        if (!ACTIVE_STATUS_YES.equals(value) && !ACTIVE_STATUS_NO.equals(value)) {
            throw new IllegalArgumentException("activeStatus must be " + ACTIVE_STATUS_YES
                    + " or " + ACTIVE_STATUS_NO + ", found " + value);
        }
    }

    /**
     * Rejects an identifier that carries a fractional part or a negative sign.
     *
     * <p>The method reads {@link BigDecimal#scale()} and {@link BigDecimal#signum()} and computes
     * nothing. {@code com.carddemo.cobol.CobolDecimal} owns every scale change on this platform,
     * and no method of this class performs one. A Picture clause of {@code 9} with no leading
     * {@code S} holds no sign. Neither failure message carries the rejected value, which keeps a
     * card verification value out of any message this class produces.
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
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }

    /**
     * Applies a card update to the three fields the card update path changes.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1466}, L1467-L1474 and L1475 move an embossed name, an
     * assembled expiration date and an active status onto the record. The rewrite follows at
     * {@code app/cbl/COCRDUPC.cbl:L1477-L1483}. The read-for-update at
     * {@code app/cbl/COCRDUPC.cbl:L1427-L1430} keys on a card number, so the card number and the
     * account identifier act as search keys.
     *
     * <p>The card update map carries no card verification value field.
     * {@code app/bms/COCRDUP.bms} declares {@code CRDNAME} at L107, {@code CRDSTCD} at L117,
     * {@code EXPMON} at L127, {@code EXPYEAR} at L135 and {@code EXPDAY} at L142. The card number,
     * the account identifier and the card verification value hold their values here. The guards
     * match those of the all-arguments constructor.
     *
     * @param embossedName   the new embossed cardholder name, at most
     *                       {@value PicClause#CARD_EMBOSSED_NAME_WIDTH} characters wide
     * @param expirationDate the new expiration date
     * @param activeStatus   the new active status flag, {@value #ACTIVE_STATUS_YES} or
     *                       {@value #ACTIVE_STATUS_NO}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the embossed name exceeds
     *                                  {@value PicClause#CARD_EMBOSSED_NAME_WIDTH} characters, or
     *                                  if the active status is neither
     *                                  {@value #ACTIVE_STATUS_YES} nor {@value #ACTIVE_STATUS_NO}
     */
    public void applyUpdate(String embossedName, LocalDate expirationDate, String activeStatus) {
        Objects.requireNonNull(embossedName, "embossedName must not be null");
        Objects.requireNonNull(expirationDate, "expirationDate must not be null");
        Objects.requireNonNull(activeStatus, "activeStatus must not be null");

        requireEmbossedNameWidth(embossedName);
        requireActiveStatusFlag(activeStatus);

        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the full card number, all {@value PicClause#CARD_NUM_WIDTH} characters of it.
     *
     * <p>The returned value is unmasked.
     *
     * @return the value of column {@code card_number}
     */
    public String getCardNumber() {
        return cardNumber;
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
     * Returns the card verification value.
     *
     * <p>The value reaches no event payload, no log line and no API response.
     *
     * @return the value of column {@code card_verification_value}
     */
    public BigDecimal getCardVerificationValue() {
        return cardVerificationValue;
    }

    /**
     * Returns the embossed cardholder name.
     *
     * @return the value of column {@code embossed_name}
     */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Returns the expiration date.
     *
     * @return the value of column {@code expiration_date}
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Returns the active status flag, {@value #ACTIVE_STATUS_YES} or {@value #ACTIVE_STATUS_NO}.
     *
     * @return the value of column {@code active_status}
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Compares on {@code card_number} alone, the primary key.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code CardEntity} carrying the same card
     *         number
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        CardEntity that = (CardEntity) other;
        return Objects.equals(cardNumber, that.cardNumber);
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

package com.carddemo.card.entity;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
 * <p>ADDITIVE, opaque paging token: {@code card_token} has no copybook field. The source keeps a
 * card-number browse key in private transaction state, while a REST cursor can enter URL and proxy
 * logs. A one-way digest preserves keyset paging without exposing the Primary Account Number.
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
 * <p>{@code src/main/resources/db/migration/V1__schema.sql:L10-L74} creates the source-derived
 * columns, the derived card token and the account index.
 * Hibernate runs under {@code ddl-auto: validate} at
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
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "card",
        indexes = @Index(name = "idx_card_account_id", columnList = "account_id, card_number"))
public class CardEntity {

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
     * numeric characters here, row one holding a value this comment states masked as
     * {@code ************5740}. The fixture holds it in full, and a comment repeating it would
     * be a card number a reader copies out of documentation.
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
     * <p>Column {@code account_id CHAR(11) NOT NULL}, indexed and not unique by
     * {@code idx_card_account_id}. One account holds many cards, and
     * {@code app/cbl/COCRDLIC.cbl} pages through them. Columns 17 through 27 of every row of
     * {@code app/data/ASCII/carddata.txt} carry eleven numeric characters, row one holding
     * {@code 00000000050}.
     *
     * <p>The field is a {@link String} and not a number. {@code PIC 9(11)} is a display field
     * eleven characters wide, and a numeric column stores {@code 00000000050} as fifty and
     * returns {@code 50}. That value matches none of three destinations: the eleven-byte
     * alternate-index key at {@code KEYS(11 16)} in {@code app/jcl/CARDFILE.jcl:L85}, the account
     * identifier in {@code card_xref}, or the aggregate identifier an event carries. The column check
     * constraint {@code ck_card_account_id_digits} holds the width and the digit class.
     */
    @Column(name = "account_id", nullable = false,
            length = PicClause.CARD_ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CARD_ACCT_ID_WIDTH + ")")
    private String accountId;

    /**
     * Card verification value, {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     *
     * <p>Column {@code card_verification_value CHAR(3) NOT NULL}. The source stores this field in
     * the clear and the column keeps it. {@code app/cpy/CVACT02Y.cpy:L7} declares the field, and
     * three AAP sections govern it.
     *
     * <ul>
     *   <li>0.4.1 stores it, and never serializes it into any event or log</li>
     *   <li>0.6.4 repeats that rule</li>
     *   <li>0.2.2 places payment-card industry controls beyond the one documented masking
     *       deviation out of scope</li>
     * </ul>
     *
     * <p>Columns 28 through 30 of every row of {@code app/data/ASCII/carddata.txt} carry three
     * numeric characters. This comment states none of them, because a verification value repeated
     * here is one a reader copies out of documentation.
     *
     * <p>Four properties keep the value inside this class, and
     * {@code CardholderDataExposureTest} asserts each one. This class declares no accessor that
     * returns it, so no caller can read it out. {@link JsonIgnore} keeps it out of every Jackson
     * rendering, including one produced by a configuration that makes private fields visible.
     * {@link #toString()} prints {@value com.carddemo.events.EventEnvelope#WITHHELD} in its
     * place. {@link #applyUpdate} never changes it, matching {@code app/bms/COCRDUP.bms}, whose
     * card update map declares no field for it.
     *
     * <p>The type is {@link String} and not a number for the same reason {@link #accountId} is.
     * {@code PIC 9(03)} is a three-character display field, and a numeric column returns
     * {@code 7} for a stored {@code 007}. That is a different card verification value. The column check constraint {@code ck_card_verification_value_digits} holds the
     * width and the digit class.
     */
    @JsonIgnore
    @Column(name = "card_verification_value", nullable = false,
            length = PicClause.CARD_CVV_CD_WIDTH,
            columnDefinition = "bpchar(" + PicClause.CARD_CVV_CD_WIDTH + ")")
    private String cardVerificationValue;

    /**
     * Embossed cardholder name, {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     *
     * <p>Column {@code embossed_name CHAR(50) NOT NULL}, fixed width and space padded. Columns 31
     * through 80 of every row of {@code app/data/ASCII/carddata.txt} carry a cardholder name padded
     * with spaces to the full fifty bytes. No fixture name is reproduced here.
     * {@code app/cbl/COCRDUPC.cbl:L1499-L1501} folds the field to upper case before it compares.
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
     * {@code app/data/ASCII/carddata.txt} with no malformed value, and no row's date is reproduced
     * here. The years present are 2023 on eleven rows, 2024 on fourteen and 2025 on twenty-five, so
     * every seeded card carries a source-era expiration rather than a current one.
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
     * Card token, the platform identity of one card. No field of
     * {@code app/cpy/CVACT02Y.cpy} declares it.
     *
     * <p>Column {@code card_token CHAR(64) NOT NULL}, unique and derived. The constructor derives it
     * from the card number through {@link PanMasker#cardToken(String)}, which is the one derivation
     * this platform holds. {@code V2__seed.sql} carries the result of that derivation as a
     * checked-in literal on each of its fifty rows, under the build-scope key the build supplies,
     * and {@code com.carddemo.card.domain.CardTokenReconciler} brings each of those rows onto the
     * deployment's own key at start-up. There is deliberately no setter: a row this service builds
     * is correct by construction, and a row it did not build is corrected by a statement.
     * {@code V1__schema.sql} documents the column, and {@code CardRepositoryIT} compares the
     * literals against this derivation over every seeded row.
     *
     * <p>This value, not the masked card number, identifies a card outside this service. The
     * masked form keeps four digits, so every card sharing those four digits masks to one value
     * and names no single row. Section 0.1.1 of the plan requires a tokenized or masked card
     * number on the wire, and section 0.6.4 keeps the full Primary Account Number (PAN) for the
     * decision and a substitute for the payload.
     *
     * <p>Nothing derives a card number back from this value: {@link PanMasker#cardToken(String)}
     * is a digest, so a caller holding a token and no card number holds no card number.
     */
    @Column(name = "card_token", nullable = false,
            length = PanMasker.CARD_TOKEN_LENGTH,
            columnDefinition = "bpchar(" + PanMasker.CARD_TOKEN_LENGTH + ")")
    private String cardToken;

    /**
     * No-argument constructor for the persistence provider.
     *
     * <p>Hibernate calls this constructor to materialise a row, then populates the seven fields
     * directly. A row already in the database reaches no guard below. Application code calls
     * {@link #CardEntity(String, String, String, String, LocalDate, String)}.
     */
    protected CardEntity() {
    }

    /**
     * Builds one card row from the six source fields, in copybook order. The derived card token is
     * assigned after those fields pass their source-shaped validation.
     *
     * @param cardNumber            the full card number, exactly
     *                              {@value PicClause#CARD_NUM_WIDTH} characters wide
     * @param accountId             the account identifier, exactly
     *                              {@value PicClause#CARD_ACCT_ID_WIDTH} digits
     * @param cardVerificationValue the card verification value, exactly
     *                              {@value PicClause#CARD_CVV_CD_WIDTH} digits
     * @param embossedName          the embossed cardholder name, at most
     *                              {@value PicClause#CARD_EMBOSSED_NAME_WIDTH} characters wide
     * @param expirationDate        the expiration date
     * @param activeStatus          the active status flag, {@value #ACTIVE_STATUS_YES} or
     *                              {@value #ACTIVE_STATUS_NO}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if any one of these checks fails.
     *                                  <ul>
     *                                  <li>The card number is
     *                                  {@value PicClause#CARD_NUM_WIDTH} characters wide.</li>
     *                                  <li>The embossed name is no longer than
     *                                  {@value PicClause#CARD_EMBOSSED_NAME_WIDTH}
     *                                  characters.</li>
     *                                  <li>The active status is {@value #ACTIVE_STATUS_YES} or
     *                                  {@value #ACTIVE_STATUS_NO}.</li>
     *                                  <li>The account identifier and the card verification value
     *                                  each carry their declared width and no character outside
     *                                  {@code 0} through {@code 9}.</li>
     *                                  </ul>
     * @see #getCardToken() for the seventh field, which this constructor derives rather than
     *      accepting
     */
    public CardEntity(String cardNumber, String accountId, String cardVerificationValue,
            String embossedName, LocalDate expirationDate, String activeStatus) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(cardVerificationValue, "cardVerificationValue must not be null");
        Objects.requireNonNull(embossedName, "embossedName must not be null");
        Objects.requireNonNull(expirationDate, "expirationDate must not be null");
        Objects.requireNonNull(activeStatus, "activeStatus must not be null");

        requireCardNumberWidth(cardNumber);
        requireDigits("accountId", accountId, PicClause.CARD_ACCT_ID_WIDTH);
        requireDigits("cardVerificationValue", cardVerificationValue,
                PicClause.CARD_CVV_CD_WIDTH);
        requireEmbossedNameWidth(embossedName);
        requireActiveStatusFlag(activeStatus);

        this.cardNumber = cardNumber;
        this.accountId = accountId;
        this.cardVerificationValue = cardVerificationValue;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
        this.cardToken = PanMasker.cardToken(cardNumber);
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
     * Rejects a digit field that is the wrong width or carries a character outside {@code 0}
     * through {@code 9}.
     *
     * <p>A {@code PIC 9(n)} display field is exactly n characters wide and holds only digits.
     * Both halves of that contract are checked here, and the column check constraint repeats them
     * in the database. Neither failure message carries a character of the rejected value. The
     * width message reports a length and the digit message reports a position. That keeps a card
     * verification value out of every message this class produces, including the message a caller
     * logs after a rejected update.
     *
     * @param fieldName the field under check, named in any failure message
     * @param value     the value to check
     * @param width     the exact number of digits the Picture clause declares
     * @throws IllegalArgumentException if the width is wrong or a character is not a digit
     */
    private static void requireDigits(String fieldName, String value, int width) {
        if (value.length() != width) {
            throw new IllegalArgumentException(fieldName + " must be exactly " + width
                    + " digits wide, found width " + value.length());
        }
        for (int position = 0; position < width; position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(fieldName
                        + " must hold digits only, found a character outside 0 through 9 at "
                        + "position " + (position + 1));
            }
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

    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Returns the account identifier this card belongs to.
     *
     * @return the value of column {@code account_id}, exactly
     *         {@value PicClause#CARD_ACCT_ID_WIDTH} digits with leading zeros
     */
    public String getAccountId() {
        return accountId;
    }

    // No accessor returns card_verification_value. The column keeps the value because
    // app/cpy/CVACT02Y.cpy:L7 declares the field, and the value never leaves the service. An
    // accessor is the shortest path out, so this class declares none.
    // CardholderDataExposureTest fails if an accessor for the field appears.

    public String getEmbossedName() {
        return embossedName;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Returns the card token, the value that identifies this card outside this service.
     *
     * <p>The paging cursor of the card list carries this value. It is opaque, it names exactly one
     * card, and it reveals no digit of the card number, so it travels where a card number must not.
     *
     * @return the value of column {@code card_token}, exactly
     *         {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal characters
     */
    public String getCardToken() {
        return cardToken;
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

    /**
     * Renders the account identifier and the active status, and withholds every other field.
     *
     * <p>A rendering of an entity reaches a log line as soon as any code concatenates the object
     * into a message, so this one carries no cardholder data at all. The card number is a Primary
     * Account Number and the card verification value is authentication data. Neither belongs in a
     * log. The embossed name is the cardholder's name and the expiration date completes a card
     * record, so both are withheld too. The account identifier names the row a reader needs, and
     * the active status is the field an update changes.
     *
     * <p>The card number is withheld here rather than masked. A caller that needs to name a card
     * in a log line passes it through {@code com.carddemo.cobol.PanMasker} at the point of use.
     *
     * @return a single-line rendering carrying no cardholder data
     */
    @Override
    public String toString() {
        return "CardEntity[cardNumber=" + EventEnvelope.WITHHELD
                + ", accountId=" + EventEnvelope.WITHHELD
                + ", cardVerificationValue=" + EventEnvelope.WITHHELD
                + ", embossedName=" + EventEnvelope.WITHHELD
                + ", expirationDate=" + EventEnvelope.WITHHELD
                + ", activeStatus=" + activeStatus
                + "]";
    }
}

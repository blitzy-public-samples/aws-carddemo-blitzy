/*
 * Card.java — JPA Entity mapping VSAM CARDDATA (150-byte KSDS record)
 *
 * Source: app/cpy/CVACT02Y.cpy (CARD-RECORD)
 * VSAM Dataset: AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
 * Record Length: 150 bytes
 * Primary Key: CARD-NUM PIC X(16) — 16-character card number
 * Alternate Index (AIX): CARD-ACCT-ID at position 16, length 11
 *
 * COBOL Record Layout (CVACT02Y.cpy):
 *   01  CARD-RECORD.
 *       05  CARD-NUM                          PIC X(16).     -> cardNum (String, 16 chars) [PK]
 *       05  CARD-ACCT-ID                      PIC 9(11).     -> accountId (String, 11 chars) [AIX INDEXED]
 *       05  CARD-CVV-CD                       PIC 9(03).     -> cvvCode (String, 3 chars) [PII]
 *       05  CARD-EMBOSSED-NAME                PIC X(50).     -> embossedName (String, 50 chars)
 *       05  CARD-EXPIRAION-DATE               PIC X(10).     -> expirationDate (String, 10 chars)
 *       05  CARD-ACTIVE-STATUS                PIC X(01).     -> activeStatus (String, 1 char)
 *       05  FILLER                            PIC X(59).     -> not mapped
 *   Total: 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes
 *
 * Migration Notes:
 * - CARD-NUM (PIC X(16)) is alphanumeric; stored as String preserving exact 16-char format
 * - CARD-ACCT-ID (PIC 9(11)) is numeric display; stored as String to preserve leading zeros
 * - CARD-CVV-CD (PIC 9(03)) is PII — stored as String to preserve leading zeros and for
 *   secure handling; must NEVER appear in log output
 * - CARD-NUM is also PII (card number) — masked in toString() for security
 * - COBOL typo "CARD-EXPIRAION-DATE" corrected to "expirationDate" in Java;
 *   database column uses "expiration_date"
 * - @Index on accountId replicates the VSAM Alternate Index (AIX) on CARD-ACCT-ID
 *   (position 16, length 11), enabling efficient card-to-account lookups
 * - @Version for optimistic locking replaces CICS READ UPDATE -> REWRITE pattern
 * - FILLER (59 bytes) is not mapped — serves only as VSAM record padding
 * - No COMP-3/packed-decimal fields in this record; all fields are display format
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;

/**
 * JPA entity representing a credit card record.
 *
 * <p>Maps the VSAM CARDDATA KSDS dataset (150-byte records) defined in COBOL
 * copybook CVACT02Y.cpy. Each card is associated with an account via
 * {@code accountId} (CARD-ACCT-ID), which has a VSAM Alternate Index (AIX)
 * replicated as a database index for efficient lookups.</p>
 *
 * <p>This entity contains PII fields: {@code cardNum} (credit card number) and
 * {@code cvvCode} (card verification value). These fields are masked in
 * {@link #toString()} to prevent accidental exposure in logs. Consuming code
 * must apply appropriate access controls and encryption when transmitting or
 * storing these values.</p>
 *
 * <p>Optimistic locking via {@code @Version} replaces the CICS
 * READ UPDATE → REWRITE concurrency pattern used in the original
 * COBOL programs (COCRDSLC, COCRDUPC).</p>
 */
@Entity
@Table(
        name = "cards",
        indexes = {
                @Index(name = "idx_card_acct_id", columnList = "card_acct_id")
        }
)
public class Card {

    // =========================================================================
    // Primary Key — CARD-NUM PIC X(16)
    // =========================================================================

    /**
     * Credit card number (16-character alphanumeric string).
     * Maps to COBOL CARD-NUM PIC X(16). This is the VSAM KSDS primary key.
     *
     * <p><strong>PII:</strong> This field contains a credit card number and
     * must be handled with appropriate security controls. It is masked in
     * {@link #toString()} output.</p>
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    // =========================================================================
    // Indexed Field — CARD-ACCT-ID PIC 9(11) [AIX]
    // =========================================================================

    /**
     * Account identifier associated with this card (11-character numeric string).
     * Maps to COBOL CARD-ACCT-ID PIC 9(11). Stored as String to preserve
     * leading zeros (e.g., "00000000050").
     *
     * <p>This field has a database index ({@code idx_card_acct_id}) that
     * replicates the VSAM Alternate Index (AIX) defined on CARDDATA at
     * position 16, length 11. The AIX enables efficient card-by-account
     * lookups used by COCRDLIC (credit card list) and COCRDSLC (credit
     * card detail) CICS programs.</p>
     */
    @Column(name = "card_acct_id", length = 11, nullable = false)
    private String accountId;

    // =========================================================================
    // PII Field — CARD-CVV-CD PIC 9(03)
    // =========================================================================

    /**
     * Card Verification Value code (3-digit numeric string).
     * Maps to COBOL CARD-CVV-CD PIC 9(03). Stored as String to preserve
     * leading zeros (e.g., "007") and for PII handling consistency.
     *
     * <p><strong>PII:</strong> This field is highly sensitive payment card data.
     * It must NEVER appear in log output, error messages, or API responses
     * unless explicitly required and properly secured. It is fully masked
     * in {@link #toString()} output.</p>
     */
    @Column(name = "card_cvv_cd", length = 3)
    private String cvvCode;

    // =========================================================================
    // Display Fields — Name, Expiry, Status
    // =========================================================================

    /**
     * Name embossed on the physical card (up to 50 characters).
     * Maps to COBOL CARD-EMBOSSED-NAME PIC X(50).
     */
    @Column(name = "card_embossed_name", length = 50)
    private String embossedName;

    /**
     * Card expiration date in YYYY-MM-DD format (10 characters).
     * Maps to COBOL CARD-EXPIRAION-DATE PIC X(10).
     *
     * <p>Note: The COBOL source uses the misspelling "EXPIRAION" (missing 'T').
     * The Java field corrects the spelling to {@code expirationDate}, and the
     * database column uses {@code expiration_date}.</p>
     */
    @Column(name = "card_expiration_date", length = 10)
    private String expirationDate;

    /**
     * Card active status indicator (1 character).
     * Maps to COBOL CARD-ACTIVE-STATUS PIC X(01).
     * Typical values: "Y" (active), "N" (inactive).
     */
    @Column(name = "card_active_status", length = 1)
    private String activeStatus;

    // =========================================================================
    // Optimistic Locking — Replaces CICS READ UPDATE -> REWRITE
    // =========================================================================

    /**
     * JPA version field for optimistic locking.
     * Replaces the CICS READ UPDATE → REWRITE concurrency control pattern
     * used in COBOL programs COCRDSLC and COCRDUPC for card updates.
     * Managed automatically by JPA; manual modification should be avoided.
     */
    @Version
    @Column(name = "version")
    private Long version;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Default no-argument constructor required by JPA.
     * Protected access prevents direct instantiation outside the persistence
     * framework while allowing JPA proxy creation.
     */
    protected Card() {
        // Required by JPA specification
    }

    /**
     * Parameterized constructor for programmatic card creation.
     * Creates a new Card instance with all business fields populated.
     * The {@code version} field is not included as it is managed by JPA.
     *
     * @param cardNum        credit card number (16 characters, PII)
     * @param accountId      associated account ID (11 characters, numeric string)
     * @param cvvCode        card verification value (3 digits, PII)
     * @param embossedName   name embossed on the card (up to 50 characters)
     * @param expirationDate card expiration date in YYYY-MM-DD format (10 characters)
     * @param activeStatus   active status indicator ("Y" or "N", 1 character)
     */
    public Card(String cardNum, String accountId, String cvvCode,
                String embossedName, String expirationDate, String activeStatus) {
        this.cardNum = cardNum;
        this.accountId = accountId;
        this.cvvCode = cvvCode;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    /**
     * Returns the credit card number (PII).
     * @return 16-character card number string
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the credit card number (PII).
     * @param cardNum 16-character card number string
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the associated account identifier.
     * @return 11-character numeric account ID string
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the associated account identifier.
     * @param accountId 11-character numeric account ID string
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the Card Verification Value code (PII).
     * @return 3-digit CVV code string
     */
    public String getCvvCode() {
        return cvvCode;
    }

    /**
     * Sets the Card Verification Value code (PII).
     * @param cvvCode 3-digit CVV code string
     */
    public void setCvvCode(String cvvCode) {
        this.cvvCode = cvvCode;
    }

    /**
     * Returns the name embossed on the card.
     * @return embossed name string (up to 50 characters)
     */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Sets the name embossed on the card.
     * @param embossedName embossed name string (up to 50 characters)
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /**
     * Returns the card expiration date.
     * @return expiration date in YYYY-MM-DD format (10 characters)
     */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the card expiration date.
     * @param expirationDate expiration date in YYYY-MM-DD format (10 characters)
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the card active status indicator.
     * @return active status ("Y" or "N", 1 character)
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the card active status indicator.
     * @param activeStatus active status ("Y" or "N", 1 character)
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the JPA version for optimistic locking.
     * @return version number managed by JPA
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the JPA version for optimistic locking.
     * Typically managed by JPA; manual setting should be avoided.
     * @param version version number
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // =========================================================================
    // equals() and hashCode() — Based on cardNum (VSAM KSDS primary key)
    // =========================================================================

    /**
     * Compares this Card with another object for equality based on the
     * primary key ({@code cardNum}). Follows JPA entity best practices where
     * equality is determined by the natural/business key rather than object
     * identity.
     *
     * @param o the object to compare with
     * @return true if both objects represent the same card (same cardNum)
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
     * Returns a hash code based on the primary key ({@code cardNum}).
     * Consistent with {@link #equals(Object)} — two Card objects with
     * the same cardNum will have the same hash code.
     *
     * @return hash code derived from cardNum
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    // =========================================================================
    // toString() — PII fields masked for security
    // =========================================================================

    /**
     * Returns a string representation of this Card entity with PII fields masked.
     *
     * <p>The {@code cardNum} field is masked to show only the last four digits
     * (e.g., "************5740") following PCI-DSS conventions. The {@code cvvCode}
     * field is fully masked (e.g., "***") to prevent accidental exposure in
     * logs, stack traces, or debug output.</p>
     *
     * @return formatted string with PII-safe card information
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum='" + maskCardNum(cardNum) + '\''
                + ", accountId='" + accountId + '\''
                + ", cvvCode='***'"
                + ", embossedName='" + embossedName + '\''
                + ", expirationDate='" + expirationDate + '\''
                + ", activeStatus='" + activeStatus + '\''
                + ", version=" + version
                + '}';
    }

    // =========================================================================
    // Private helper — PII masking
    // =========================================================================

    /**
     * Masks a credit card number to show only the last four digits,
     * following PCI-DSS display conventions. Returns "****" if the
     * card number is null or shorter than four characters.
     *
     * @param num the full card number to mask
     * @return masked card number (e.g., "************5740")
     */
    private static String maskCardNum(String num) {
        if (num == null || num.length() < 4) {
            return "****";
        }
        return "*".repeat(num.length() - 4) + num.substring(num.length() - 4);
    }
}

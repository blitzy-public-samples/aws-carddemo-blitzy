package com.carddemo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable JSON response payload for the account-view contract
 * ({@code GET /accounts/{id}} on {@code AccountController}).
 *
 * <p><strong>Lineage.</strong> This DTO is the Spring Boot re-expression of the legacy
 * CICS online program {@code COACTVWC} and its BMS screen {@code COACTVW}. The original
 * program presents a <em>joined</em> view: given an account id it reads the card
 * cross-reference ({@code CARDXREF}) to resolve the owning customer, then reads the
 * {@code ACCOUNT} record (copybook {@code CVACT01Y}) and the {@code CUSTOMER} record
 * (copybook {@code CVCUS01Y}) and renders both on a single 3270 panel. Because no
 * standalone customer endpoint exists in the target system, that account + customer
 * join is <em>flattened</em> into this one response object rather than split across a
 * nested customer DTO.</p>
 *
 * <p><strong>Field provenance.</strong> Every component below maps one-to-one to a field
 * of {@code CVACT01Y} (account) or {@code CVCUS01Y} (customer), excluding the trailing
 * {@code FILLER} padding of each record. COBOL fixed-point money fields
 * ({@code PIC S9(10)V99 COMP-3}) become {@link java.math.BigDecimal}; {@code PIC X(10)}
 * date fields become {@link java.time.LocalDate} (serialized as ISO {@code yyyy-MM-dd});
 * numeric identifiers become {@link Long}; the FICO score becomes {@link Integer}; and
 * status / code / text fields become {@link String}.</p>
 *
 * <p><strong>PII suppression (MANDATORY).</strong> The legacy {@code COACTVWC} screen
 * displayed the full Social Security Number (it formatted {@code CUST-SSN} as
 * {@code nnn-nn-nnnn}). This REST contract deliberately diverges from that behavior in
 * order to honor the platform PII rule: the only SSN-derived component exposed here is
 * {@link #ssnLastFour()}, which carries <em>at most the last four digits</em>. There is
 * intentionally <strong>no</strong> component capable of holding a complete nine-digit
 * SSN, so a full SSN can never be serialized from this type. The last-four extraction is
 * performed upstream in the entity&rarr;DTO mapper. Consistent with the same rule set,
 * this response never carries the card verification value (CVV) or any password material.</p>
 *
 * <p><strong>Optimistic locking.</strong> {@link #version()} surfaces the JPA
 * {@code @Version} token of the underlying {@code Account} entity. Clients echo it back
 * on {@code PUT /accounts/{id}} so the service layer can detect a concurrent modification
 * (a stale version) and surface it as HTTP&nbsp;409 &mdash; the relational replacement for
 * the legacy {@code READ ... UPDATE} / {@code REWRITE} file-status conflict guard used by
 * {@code COACTUPC}.</p>
 *
 * <p><strong>Serialization.</strong> Null components are omitted from the rendered JSON
 * via {@link JsonInclude}. Monetary {@link java.math.BigDecimal} values are written as
 * JSON numbers (with two-decimal scale applied by the application's Jackson configuration)
 * and {@link java.time.LocalDate} values as ISO-8601 calendar dates.</p>
 *
 * <p>This is a {@code Tier-0} DTO: it depends on nothing inside {@code com.carddemo} and
 * imports only JDK types plus the Jackson serialization annotation.</p>
 *
 * @param accountId                   {@code CVACT01Y.ACCT-ID PIC 9(11)} &mdash; unique account identifier.
 * @param activeStatus                {@code CVACT01Y.ACCT-ACTIVE-STATUS PIC X(01)} &mdash; active flag (e.g. {@code "Y"}/{@code "N"}).
 * @param currentBalance              {@code CVACT01Y.ACCT-CURR-BAL PIC S9(10)V99} &mdash; current outstanding balance.
 * @param creditLimit                 {@code CVACT01Y.ACCT-CREDIT-LIMIT PIC S9(10)V99} &mdash; total credit limit.
 * @param cashCreditLimit             {@code CVACT01Y.ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} &mdash; cash-advance credit limit.
 * @param openDate                    {@code CVACT01Y.ACCT-OPEN-DATE PIC X(10)} &mdash; account open date.
 * @param expirationDate              {@code CVACT01Y.ACCT-EXPIRAION-DATE PIC X(10)} &mdash; account expiration date (legacy field name retains its spelling).
 * @param reissueDate                 {@code CVACT01Y.ACCT-REISSUE-DATE PIC X(10)} &mdash; account reissue date.
 * @param currentCycleCredit          {@code CVACT01Y.ACCT-CURR-CYC-CREDIT PIC S9(10)V99} &mdash; credits posted in the current cycle.
 * @param currentCycleDebit           {@code CVACT01Y.ACCT-CURR-CYC-DEBIT PIC S9(10)V99} &mdash; debits posted in the current cycle.
 * @param accountAddressZip           {@code CVACT01Y.ACCT-ADDR-ZIP PIC X(10)} &mdash; account address ZIP code.
 * @param accountGroupId              {@code CVACT01Y.ACCT-GROUP-ID PIC X(10)} &mdash; disclosure / pricing group id.
 * @param customerId                  {@code CVCUS01Y.CUST-ID PIC 9(09)} &mdash; owning customer identifier.
 * @param firstName                   {@code CVCUS01Y.CUST-FIRST-NAME PIC X(25)} &mdash; customer first name.
 * @param middleName                  {@code CVCUS01Y.CUST-MIDDLE-NAME PIC X(25)} &mdash; customer middle name.
 * @param lastName                    {@code CVCUS01Y.CUST-LAST-NAME PIC X(25)} &mdash; customer last name.
 * @param addressLine1                {@code CVCUS01Y.CUST-ADDR-LINE-1 PIC X(50)} &mdash; address line 1.
 * @param addressLine2                {@code CVCUS01Y.CUST-ADDR-LINE-2 PIC X(50)} &mdash; address line 2.
 * @param addressLine3                {@code CVCUS01Y.CUST-ADDR-LINE-3 PIC X(50)} &mdash; address line 3.
 * @param stateCode                   {@code CVCUS01Y.CUST-ADDR-STATE-CD PIC X(02)} &mdash; state code.
 * @param countryCode                 {@code CVCUS01Y.CUST-ADDR-COUNTRY-CD PIC X(03)} &mdash; country code.
 * @param customerZip                 {@code CVCUS01Y.CUST-ADDR-ZIP PIC X(10)} &mdash; customer address ZIP code.
 * @param phoneNumber1                {@code CVCUS01Y.CUST-PHONE-NUM-1 PIC X(15)} &mdash; primary phone number.
 * @param phoneNumber2                {@code CVCUS01Y.CUST-PHONE-NUM-2 PIC X(15)} &mdash; secondary phone number.
 * @param ssnLastFour                 derived from {@code CVCUS01Y.CUST-SSN PIC 9(09)} &mdash; <strong>last four digits only (PII-suppressed)</strong>.
 * @param governmentIssuedId          {@code CVCUS01Y.CUST-GOVT-ISSUED-ID PIC X(20)} &mdash; government-issued identifier.
 * @param dateOfBirth                 {@code CVCUS01Y.CUST-DOB-YYYY-MM-DD PIC X(10)} &mdash; date of birth.
 * @param eftAccountId                {@code CVCUS01Y.CUST-EFT-ACCOUNT-ID PIC X(10)} &mdash; EFT account identifier.
 * @param primaryCardHolderIndicator  {@code CVCUS01Y.CUST-PRI-CARD-HOLDER-IND PIC X(01)} &mdash; primary card-holder indicator.
 * @param ficoScore                   {@code CVCUS01Y.CUST-FICO-CREDIT-SCORE PIC 9(03)} &mdash; FICO credit score.
 * @param version                     JPA {@code @Version} optimistic-lock token of the {@code Account} entity (echoed on update; stale value &rarr; HTTP 409).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(

        // ---- Account fields (CVACT01Y, FILLER excluded) ----
        Long accountId,                  // ACCT-ID                 PIC 9(11)
        String activeStatus,             // ACCT-ACTIVE-STATUS      PIC X(01)
        BigDecimal currentBalance,       // ACCT-CURR-BAL           PIC S9(10)V99
        BigDecimal creditLimit,          // ACCT-CREDIT-LIMIT       PIC S9(10)V99
        BigDecimal cashCreditLimit,      // ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99
        LocalDate openDate,              // ACCT-OPEN-DATE          PIC X(10)
        LocalDate expirationDate,        // ACCT-EXPIRAION-DATE     PIC X(10)
        LocalDate reissueDate,           // ACCT-REISSUE-DATE       PIC X(10)
        BigDecimal currentCycleCredit,   // ACCT-CURR-CYC-CREDIT    PIC S9(10)V99
        BigDecimal currentCycleDebit,    // ACCT-CURR-CYC-DEBIT     PIC S9(10)V99
        String accountAddressZip,        // ACCT-ADDR-ZIP           PIC X(10)
        String accountGroupId,           // ACCT-GROUP-ID           PIC X(10)

        // ---- Customer view fields (CVCUS01Y subset, FILLER excluded) ----
        Long customerId,                 // CUST-ID                 PIC 9(09)
        String firstName,                // CUST-FIRST-NAME         PIC X(25)
        String middleName,               // CUST-MIDDLE-NAME        PIC X(25)
        String lastName,                 // CUST-LAST-NAME          PIC X(25)
        String addressLine1,             // CUST-ADDR-LINE-1        PIC X(50)
        String addressLine2,             // CUST-ADDR-LINE-2        PIC X(50)
        String addressLine3,             // CUST-ADDR-LINE-3        PIC X(50)
        String stateCode,                // CUST-ADDR-STATE-CD      PIC X(02)
        String countryCode,              // CUST-ADDR-COUNTRY-CD    PIC X(03)
        String customerZip,              // CUST-ADDR-ZIP           PIC X(10)
        String phoneNumber1,             // CUST-PHONE-NUM-1        PIC X(15)
        String phoneNumber2,             // CUST-PHONE-NUM-2        PIC X(15)
        String ssnLastFour,              // CUST-SSN PIC 9(09) -> LAST 4 DIGITS ONLY (PII-suppressed)
        String governmentIssuedId,       // CUST-GOVT-ISSUED-ID     PIC X(20)
        LocalDate dateOfBirth,           // CUST-DOB-YYYY-MM-DD     PIC X(10)
        String eftAccountId,             // CUST-EFT-ACCOUNT-ID     PIC X(10)
        String primaryCardHolderIndicator, // CUST-PRI-CARD-HOLDER-IND PIC X(01)
        Integer ficoScore,              // CUST-FICO-CREDIT-SCORE  PIC 9(03)

        // ---- Optimistic-lock token ----
        Long version                     // JPA @Version of Account (echoed on PUT; stale -> HTTP 409)
) {
}

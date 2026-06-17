package com.carddemo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable JSON request body for the account-update operation
 * (<code>PUT /accounts/{id}</code>) handled by {@code AccountController}.
 *
 * <h2>Lineage</h2>
 * <p>This DTO is the Java&nbsp;17 re-expression of the legacy CICS online
 * program {@code COACTUPC} and its BMS screen {@code COACTUP}. That screen is a
 * <em>combined account&nbsp;+&nbsp;customer maintenance</em> screen, so a single
 * update request edits both the account record ({@code CVACT01Y}) and the
 * owning customer record ({@code CVCUS01Y}) within one optimistically-locked
 * unit of work — the COBOL {@code READ ... UPDATE} / {@code REWRITE} pattern.</p>
 *
 * <h2>Field selection</h2>
 * <ul>
 *   <li>The account identifier is supplied through the URL path
 *       ({@code /accounts/{id}}) and is therefore <strong>not</strong> a body
 *       field, mirroring the immutable VSAM primary key {@code ACCT-ID}.</li>
 *   <li>Only the editable fields from {@code CVACT01Y} / {@code CVCUS01Y} are
 *       present; the trailing COBOL {@code FILLER} areas carry no business data
 *       and are excluded.</li>
 *   <li>{@link #version()} is the optimistic-locking token the client read from
 *       {@code AccountResponse}; the service compares it against the JPA
 *       {@code @Version} column and raises HTTP&nbsp;409 on a mismatch
 *       (AAP&nbsp;&sect;0.6.6).</li>
 * </ul>
 *
 * <h2>Validation</h2>
 * <p>Bean Validation ({@code jakarta.validation}) constraints reproduce the
 * field-level edits performed by {@code COACTUPC}. The editable account/customer
 * fields are null-tolerant by specification, which matches the partial-update
 * semantics of a {@code PUT}: a field omitted from the JSON body is left unchanged
 * and skips its constraint. The sole exception is {@link #version()}, which is
 * <strong>required</strong> ({@code @NotNull}): it is the optimistic-locking token,
 * and accepting a null would silently bypass the concurrency contract
 * (AAP&nbsp;&sect;0.6.6). Constraint violations are surfaced as HTTP&nbsp;400 by the
 * {@code GlobalExceptionHandler} when the request is bound with
 * {@code @Valid @RequestBody}.</p>
 *
 * <h2>PII</h2>
 * <p>{@code ssn} is accepted as <strong>input only</strong> — the legacy
 * {@code COACTUP} screen captures it in the three parts {@code ACTSSN1}/{@code 2}
 * /{@code 3} (3&nbsp;+&nbsp;2&nbsp;+&nbsp;4 = 9&nbsp;digits). It is never echoed
 * in any response (responses expose only {@code ssnLastFour}), and
 * {@link #toString()} is overridden to redact it so the value cannot leak
 * through diagnostic logging.</p>
 *
 * @param activeStatus               account active flag — {@code ACCT-ACTIVE-STATUS PIC X(01)}; {@code 'Y'} or {@code 'N'}
 * @param currentBalance             current account balance — {@code ACCT-CURR-BAL PIC S9(10)V99}
 * @param creditLimit                total credit limit — {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}
 * @param cashCreditLimit            cash-advance credit limit — {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
 * @param currentCycleCredit         current-cycle credit total — {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
 * @param currentCycleDebit          current-cycle debit total — {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
 * @param openDate                   account open date — {@code ACCT-OPEN-DATE PIC X(10)} (YYYY-MM-DD)
 * @param expirationDate             account expiration date — {@code ACCT-EXPIRAION-DATE PIC X(10)} (YYYY-MM-DD)
 * @param reissueDate                account reissue date — {@code ACCT-REISSUE-DATE PIC X(10)} (YYYY-MM-DD)
 * @param accountAddressZip          account address ZIP — {@code ACCT-ADDR-ZIP PIC X(10)}
 * @param accountGroupId             account / disclosure group id — {@code ACCT-GROUP-ID PIC X(10)}
 * @param firstName                  customer first name — {@code CUST-FIRST-NAME PIC X(25)}
 * @param middleName                 customer middle name — {@code CUST-MIDDLE-NAME PIC X(25)}
 * @param lastName                   customer last name — {@code CUST-LAST-NAME PIC X(25)}
 * @param addressLine1               customer address line 1 — {@code CUST-ADDR-LINE-1 PIC X(50)}
 * @param addressLine2               customer address line 2 — {@code CUST-ADDR-LINE-2 PIC X(50)}
 * @param addressLine3               customer address line 3 — {@code CUST-ADDR-LINE-3 PIC X(50)}
 * @param stateCode                  customer state code — {@code CUST-ADDR-STATE-CD PIC X(02)}
 * @param countryCode                customer country code — {@code CUST-ADDR-COUNTRY-CD PIC X(03)}
 * @param customerZip                customer address ZIP — {@code CUST-ADDR-ZIP PIC X(10)}
 * @param phoneNumber1               customer primary phone — {@code CUST-PHONE-NUM-1 PIC X(15)}
 * @param phoneNumber2               customer secondary phone — {@code CUST-PHONE-NUM-2 PIC X(15)}
 * @param ssn                        customer SSN (input only, never echoed) — {@code CUST-SSN PIC 9(09)}
 * @param governmentIssuedId         government-issued id — {@code CUST-GOVT-ISSUED-ID PIC X(20)}
 * @param dateOfBirth                customer date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)} (YYYY-MM-DD)
 * @param eftAccountId               EFT account id — {@code CUST-EFT-ACCOUNT-ID PIC X(10)}
 * @param primaryCardHolderIndicator primary card-holder flag — {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}
 * @param ficoScore                  FICO credit score — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}
 * @param version                    optimistic-lock token echoed from {@code AccountResponse}; required (rejected as HTTP&nbsp;400 if absent) so the concurrency contract cannot be bypassed (AAP&nbsp;&sect;0.6.6)
 */
public record AccountUpdateRequest(

        // ===================== Account fields (CVACT01Y) =====================

        // ACCT-ACTIVE-STATUS PIC X(01) — active flag; COACTUPC edits as YES/NO ('Y'/'N')
        @Pattern(regexp = "[YN]", message = "activeStatus must be 'Y' or 'N'")
        String activeStatus,

        // ACCT-CURR-BAL PIC S9(10)V99 — current account balance
        @Digits(integer = 10, fraction = 2)
        BigDecimal currentBalance,

        // ACCT-CREDIT-LIMIT PIC S9(10)V99 — total credit limit
        @Digits(integer = 10, fraction = 2)
        BigDecimal creditLimit,

        // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 — cash-advance credit limit
        @Digits(integer = 10, fraction = 2)
        BigDecimal cashCreditLimit,

        // ACCT-CURR-CYC-CREDIT PIC S9(10)V99 — current-cycle credit total
        @Digits(integer = 10, fraction = 2)
        BigDecimal currentCycleCredit,

        // ACCT-CURR-CYC-DEBIT PIC S9(10)V99 — current-cycle debit total
        @Digits(integer = 10, fraction = 2)
        BigDecimal currentCycleDebit,

        // ACCT-OPEN-DATE PIC X(10) — account open date (YYYY-MM-DD)
        LocalDate openDate,

        // ACCT-EXPIRAION-DATE PIC X(10) — account expiration date (YYYY-MM-DD)
        LocalDate expirationDate,

        // ACCT-REISSUE-DATE PIC X(10) — account reissue date (YYYY-MM-DD)
        LocalDate reissueDate,

        // ACCT-ADDR-ZIP PIC X(10) — account address ZIP code
        @Size(max = 10)
        String accountAddressZip,

        // ACCT-GROUP-ID PIC X(10) — account / disclosure group id
        @Size(max = 10)
        String accountGroupId,

        // ==================== Customer fields (CVCUS01Y) =====================

        // CUST-FIRST-NAME PIC X(25)
        @Size(max = 25)
        String firstName,

        // CUST-MIDDLE-NAME PIC X(25)
        @Size(max = 25)
        String middleName,

        // CUST-LAST-NAME PIC X(25)
        @Size(max = 25)
        String lastName,

        // CUST-ADDR-LINE-1 PIC X(50)
        @Size(max = 50)
        String addressLine1,

        // CUST-ADDR-LINE-2 PIC X(50)
        @Size(max = 50)
        String addressLine2,

        // CUST-ADDR-LINE-3 PIC X(50)
        @Size(max = 50)
        String addressLine3,

        // CUST-ADDR-STATE-CD PIC X(02)
        @Size(max = 2)
        String stateCode,

        // CUST-ADDR-COUNTRY-CD PIC X(03)
        @Size(max = 3)
        String countryCode,

        // CUST-ADDR-ZIP PIC X(10)
        @Size(max = 10)
        String customerZip,

        // CUST-PHONE-NUM-1 PIC X(15) — COACTUP captures as area(3)+prefix(3)+line(4)
        @Size(max = 15)
        String phoneNumber1,

        // CUST-PHONE-NUM-2 PIC X(15) — COACTUP captures as area(3)+prefix(3)+line(4)
        @Size(max = 15)
        String phoneNumber2,

        // CUST-SSN PIC 9(09) — INPUT ONLY; COACTUP captures as ACTSSN1(3)+ACTSSN2(2)+ACTSSN3(4);
        // never echoed in responses (responses expose ssnLastFour only)
        @Pattern(regexp = "\\d{9}", message = "ssn must be exactly 9 digits")
        String ssn,

        // CUST-GOVT-ISSUED-ID PIC X(20)
        @Size(max = 20)
        String governmentIssuedId,

        // CUST-DOB-YYYY-MM-DD PIC X(10) — date of birth (YYYY-MM-DD)
        LocalDate dateOfBirth,

        // CUST-EFT-ACCOUNT-ID PIC X(10)
        @Size(max = 10)
        String eftAccountId,

        // CUST-PRI-CARD-HOLDER-IND PIC X(01)
        @Size(max = 1)
        String primaryCardHolderIndicator,

        // CUST-FICO-CREDIT-SCORE PIC 9(03)
        Integer ficoScore,

        // ===================== Optimistic-lock token =========================

        // Optimistic-lock handle echoed from AccountResponse; AccountService compares
        // it with the JPA @Version column → HTTP 409 on concurrent modification (AAP §0.6.6).
        // REQUIRED at the request boundary: a missing version would bypass the concurrency
        // contract, so a null is rejected as HTTP 400 (unlike the null-tolerant edit fields).
        @NotNull(message = "version is required")
        Long version

) {

    /**
     * Returns a diagnostic string representation with the {@code ssn} component
     * redacted.
     *
     * <p>A record's compiler-generated {@code toString()} emits every component,
     * which would include the social-security number and could leak PII into
     * application logs (for example via {@code log.debug("req={}", request)}).
     * This override preserves the troubleshooting value of the remaining fields
     * while masking the SSN, complementing the Logback masking and the
     * service-layer "never echo" rule mandated by AAP&nbsp;&sect;0.6.8.</p>
     *
     * @return a string representation in which the SSN is replaced by {@code "***"}
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest["
                + "activeStatus=" + activeStatus
                + ", currentBalance=" + currentBalance
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", currentCycleCredit=" + currentCycleCredit
                + ", currentCycleDebit=" + currentCycleDebit
                + ", openDate=" + openDate
                + ", expirationDate=" + expirationDate
                + ", reissueDate=" + reissueDate
                + ", accountAddressZip=" + accountAddressZip
                + ", accountGroupId=" + accountGroupId
                + ", firstName=" + firstName
                + ", middleName=" + middleName
                + ", lastName=" + lastName
                + ", addressLine1=" + addressLine1
                + ", addressLine2=" + addressLine2
                + ", addressLine3=" + addressLine3
                + ", stateCode=" + stateCode
                + ", countryCode=" + countryCode
                + ", customerZip=" + customerZip
                + ", phoneNumber1=" + phoneNumber1
                + ", phoneNumber2=" + phoneNumber2
                + ", ssn=***"
                + ", governmentIssuedId=" + governmentIssuedId
                + ", dateOfBirth=" + dateOfBirth
                + ", eftAccountId=" + eftAccountId
                + ", primaryCardHolderIndicator=" + primaryCardHolderIndicator
                + ", ficoScore=" + ficoScore
                + ", version=" + version
                + "]";
    }
}

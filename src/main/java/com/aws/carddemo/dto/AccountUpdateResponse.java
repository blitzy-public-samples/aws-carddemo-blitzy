/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Response DTO for the CardDemo <em>Account Update</em> screen.
 *
 * <p>This is the immutable, transport-layer representation of the output
 * (display) side of BMS map {@code COACTUP} &mdash; specifically the
 * {@code CACTUPAO} symbolic map group in copybook {@code app/cpy-bms/COACTUP.CPY}
 * (relocated to {@code legacy/cpy-bms/COACTUP.CPY}). It is produced by the
 * account-update flow migrated from COBOL program {@code COACTUPC} (CICS
 * transaction {@code CAUP}) and returned by {@code AccountUpdateController}.</p>
 *
 * <p>The Account Update screen redisplays the account and customer detail
 * fields the operator submitted, together with an informational message, an
 * error message, and the function-key legend. On a failed edit the legacy
 * program re-sends the entered values alongside an error message; this DTO
 * therefore mirrors the data fields of the corresponding request so the client
 * can re-render the screen. Each field preserves the name, maximum length, and
 * PIC-derived type of its {@code CACTUPAO} source field, per the field-contract
 * parity requirement.</p>
 *
 * <p><strong>Monetary fields.</strong> The five monetary values
 * ({@code creditLimit}, {@code cashLimit}, {@code currentBalance},
 * {@code currentCycleCredit}, {@code currentCycleDebit}) are modeled as
 * {@link java.math.BigDecimal} at scale {@code 2}; floating-point types are
 * never used for money. Non-null monetary values supplied to the canonical
 * constructor are normalized to scale {@code 2} using
 * {@link java.math.RoundingMode#HALF_UP}, matching the rounding convention used
 * throughout the migrated system.</p>
 *
 * <p><strong>Sensitive data.</strong> The Social Security Number parts
 * ({@code ssnPart1}, {@code ssnPart2}, {@code ssnPart3}), the date-of-birth
 * parts ({@code dobYear}, {@code dobMonth}, {@code dobDay}), and the government
 * identifier ({@code governmentId}) are treated as sensitive: their values are
 * masked by {@link #toString()} and must never be written to logs. No card
 * verification value (CVV) is present on this screen and none is added here.</p>
 *
 * <p>The BMS attribute bytes (colour, protection, highlight and validation
 * sub-fields such as {@code *C}/{@code *P}/{@code *H}/{@code *V}) are
 * deliberately omitted: this DTO expresses the field-level data contract, not a
 * rendered 3270 terminal, so no terminal-rendering attributes are carried.</p>
 *
 * @param transactionName    header transaction id ({@code TRNNAMEO}, X(4)).
 * @param title01            first title line ({@code TITLE01O}, X(40)).
 * @param title02            second title line ({@code TITLE02O}, X(40)).
 * @param currentDate        current date text ({@code CURDATEO}, X(8)).
 * @param programName        owning program name ({@code PGMNAMEO}, X(8)).
 * @param currentTime        current time text ({@code CURTIMEO}, X(8)).
 * @param accountId          echoed account identifier key ({@code ACCTSIDO}, X(11)).
 * @param accountStatus      account active status flag ({@code ACSTTUSO}, X(1)).
 * @param openYear           account open-date year part ({@code OPNYEARO}, X(4)).
 * @param openMonth          account open-date month part ({@code OPNMONO}, X(2)).
 * @param openDay            account open-date day part ({@code OPNDAYO}, X(2)).
 * @param creditLimit        credit limit, monetary scale 2 ({@code ACRDLIMO}, X(15)).
 * @param expiryYear         card expiry year part ({@code EXPYEARO}, X(4)).
 * @param expiryMonth        card expiry month part ({@code EXPMONO}, X(2)).
 * @param expiryDay          card expiry day part ({@code EXPDAYO}, X(2)).
 * @param cashLimit          cash credit limit, monetary scale 2 ({@code ACSHLIMO}, X(15)).
 * @param reissueYear        reissue year part ({@code RISYEARO}, X(4)).
 * @param reissueMonth       reissue month part ({@code RISMONO}, X(2)).
 * @param reissueDay         reissue day part ({@code RISDAYO}, X(2)).
 * @param currentBalance     current balance, monetary scale 2 ({@code ACURBALO}, X(15)).
 * @param currentCycleCredit current cycle credit, monetary scale 2 ({@code ACRCYCRO}, X(15)).
 * @param groupId            account (disclosure) group id ({@code AADDGRPO}, X(10)).
 * @param currentCycleDebit  current cycle debit, monetary scale 2 ({@code ACRCYDBO}, X(15)).
 * @param customerId         customer identifier ({@code ACSTNUMO}, X(9)).
 * @param ssnPart1           SSN area part &mdash; SENSITIVE ({@code ACTSSN1O}, X(3)).
 * @param ssnPart2           SSN group part &mdash; SENSITIVE ({@code ACTSSN2O}, X(2)).
 * @param ssnPart3           SSN serial part &mdash; SENSITIVE ({@code ACTSSN3O}, X(4)).
 * @param dobYear            date-of-birth year &mdash; SENSITIVE ({@code DOBYEARO}, X(4)).
 * @param dobMonth           date-of-birth month &mdash; SENSITIVE ({@code DOBMONO}, X(2)).
 * @param dobDay             date-of-birth day &mdash; SENSITIVE ({@code DOBDAYO}, X(2)).
 * @param ficoScore          customer FICO score ({@code ACSTFCOO}, X(3)).
 * @param firstName          customer first name ({@code ACSFNAMO}, X(25)).
 * @param middleName         customer middle name ({@code ACSMNAMO}, X(25)).
 * @param lastName           customer last name ({@code ACSLNAMO}, X(25)).
 * @param addressLine1       customer address line 1 ({@code ACSADL1O}, X(50)).
 * @param stateCode          customer state code ({@code ACSSTTEO}, X(2)).
 * @param addressLine2       customer address line 2 ({@code ACSADL2O}, X(50)).
 * @param zipCode            customer ZIP code ({@code ACSZIPCO}, X(5)).
 * @param city               customer city ({@code ACSCITYO}, X(50)).
 * @param countryCode        customer country code ({@code ACSCTRYO}, X(3)).
 * @param phone1Area         primary phone area code ({@code ACSPH1AO}, X(3)).
 * @param phone1Prefix       primary phone prefix ({@code ACSPH1BO}, X(3)).
 * @param phone1Line         primary phone line number ({@code ACSPH1CO}, X(4)).
 * @param governmentId       government-issued id &mdash; SENSITIVE ({@code ACSGOVTO}, X(20)).
 * @param phone2Area         secondary phone area code ({@code ACSPH2AO}, X(3)).
 * @param phone2Prefix       secondary phone prefix ({@code ACSPH2BO}, X(3)).
 * @param phone2Line         secondary phone line number ({@code ACSPH2CO}, X(4)).
 * @param eftAccountId       EFT account identifier ({@code ACSEFTCO}, X(10)).
 * @param primaryHolderFlag  primary card-holder flag ({@code ACSPFLGO}, X(1)).
 * @param infoMessage        informational message line ({@code INFOMSGO}, X(45)).
 * @param errorMessage       error message line ({@code ERRMSGO}, X(78)).
 * @param functionKeys       function-key legend line ({@code FKEYSO}, X(21)).
 * @param functionKeySave    save function-key legend ({@code FKEY05O}, X(7)).
 * @param functionKeyCancel  cancel function-key legend ({@code FKEY12O}, X(10)).
 */
public record AccountUpdateResponse(
        String transactionName,
        String title01,
        String title02,
        String currentDate,
        String programName,
        String currentTime,
        String accountId,
        String accountStatus,
        String openYear,
        String openMonth,
        String openDay,
        BigDecimal creditLimit,
        String expiryYear,
        String expiryMonth,
        String expiryDay,
        BigDecimal cashLimit,
        String reissueYear,
        String reissueMonth,
        String reissueDay,
        BigDecimal currentBalance,
        BigDecimal currentCycleCredit,
        String groupId,
        BigDecimal currentCycleDebit,
        String customerId,
        String ssnPart1,
        String ssnPart2,
        String ssnPart3,
        String dobYear,
        String dobMonth,
        String dobDay,
        String ficoScore,
        String firstName,
        String middleName,
        String lastName,
        String addressLine1,
        String stateCode,
        String addressLine2,
        String zipCode,
        String city,
        String countryCode,
        String phone1Area,
        String phone1Prefix,
        String phone1Line,
        String governmentId,
        String phone2Area,
        String phone2Prefix,
        String phone2Line,
        String eftAccountId,
        String primaryHolderFlag,
        String infoMessage,
        String errorMessage,
        String functionKeys,
        String functionKeySave,
        String functionKeyCancel) {

    /** Scale applied to every monetary field, mirroring COBOL {@code PIC ...V99}. */
    private static final int MONETARY_SCALE = 2;

    /** Placeholder rendered in {@link #toString()} in place of sensitive values. */
    private static final String MASKED = "****";

    /**
     * Canonical constructor that normalizes the monetary components to a fixed
     * scale of {@value #MONETARY_SCALE} using {@link RoundingMode#HALF_UP}.
     *
     * <p>Normalization is null-safe: a {@code null} monetary value is retained
     * as {@code null} (absent), while any non-null value is coerced to scale
     * {@value #MONETARY_SCALE}. This enforces the monetary representation
     * invariant at the DTO boundary; it performs no business calculation.</p>
     */
    public AccountUpdateResponse {
        creditLimit = normalizeMoney(creditLimit);
        cashLimit = normalizeMoney(cashLimit);
        currentBalance = normalizeMoney(currentBalance);
        currentCycleCredit = normalizeMoney(currentCycleCredit);
        currentCycleDebit = normalizeMoney(currentCycleDebit);
    }

    /**
     * Coerces a monetary amount to the fixed monetary scale.
     *
     * @param value the amount to normalize, may be {@code null}.
     * @return {@code null} if {@code value} is {@code null}; otherwise
     *         {@code value} rescaled to {@value #MONETARY_SCALE} decimal places
     *         using {@link RoundingMode#HALF_UP}.
     */
    private static BigDecimal normalizeMoney(BigDecimal value) {
        return value == null ? null : value.setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Returns a diagnostic string representation with sensitive fields masked.
     *
     * <p>This override deliberately replaces the record's compiler-generated
     * {@code toString()} so that the Social Security Number parts, the
     * date-of-birth parts and the government identifier are never rendered in
     * string form and therefore never reach logs. Every non-sensitive field is
     * shown with its actual value. This screen carries no CVV.</p>
     *
     * @return a representation in which each sensitive value is shown as the
     *         {@value #MASKED} placeholder.
     */
    @Override
    public String toString() {
        return "AccountUpdateResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", title02=" + title02
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", currentTime=" + currentTime
                + ", accountId=" + accountId
                + ", accountStatus=" + accountStatus
                + ", openYear=" + openYear
                + ", openMonth=" + openMonth
                + ", openDay=" + openDay
                + ", creditLimit=" + creditLimit
                + ", expiryYear=" + expiryYear
                + ", expiryMonth=" + expiryMonth
                + ", expiryDay=" + expiryDay
                + ", cashLimit=" + cashLimit
                + ", reissueYear=" + reissueYear
                + ", reissueMonth=" + reissueMonth
                + ", reissueDay=" + reissueDay
                + ", currentBalance=" + currentBalance
                + ", currentCycleCredit=" + currentCycleCredit
                + ", groupId=" + groupId
                + ", currentCycleDebit=" + currentCycleDebit
                + ", customerId=" + customerId
                + ", ssnPart1=" + MASKED
                + ", ssnPart2=" + MASKED
                + ", ssnPart3=" + MASKED
                + ", dobYear=" + MASKED
                + ", dobMonth=" + MASKED
                + ", dobDay=" + MASKED
                + ", ficoScore=" + ficoScore
                + ", firstName=" + firstName
                + ", middleName=" + middleName
                + ", lastName=" + lastName
                + ", addressLine1=" + addressLine1
                + ", stateCode=" + stateCode
                + ", addressLine2=" + addressLine2
                + ", zipCode=" + zipCode
                + ", city=" + city
                + ", countryCode=" + countryCode
                + ", phone1Area=" + phone1Area
                + ", phone1Prefix=" + phone1Prefix
                + ", phone1Line=" + phone1Line
                + ", governmentId=" + MASKED
                + ", phone2Area=" + phone2Area
                + ", phone2Prefix=" + phone2Prefix
                + ", phone2Line=" + phone2Line
                + ", eftAccountId=" + eftAccountId
                + ", primaryHolderFlag=" + primaryHolderFlag
                + ", infoMessage=" + infoMessage
                + ", errorMessage=" + errorMessage
                + ", functionKeys=" + functionKeys
                + ", functionKeySave=" + functionKeySave
                + ", functionKeyCancel=" + functionKeyCancel
                + "]";
    }
}

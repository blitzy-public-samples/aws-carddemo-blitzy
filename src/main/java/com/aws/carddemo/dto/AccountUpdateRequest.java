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

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <em>Account Update</em> screen.
 *
 * <p>This is the immutable, transport-layer representation of the input (entry)
 * side of BMS map {@code COACTUP} / mapset {@code CACTUPA} &mdash; specifically
 * the {@code CACTUPAI} symbolic input group in copybook
 * {@code app/cpy-bms/COACTUP.CPY} (relocated to {@code legacy/cpy-bms/COACTUP.CPY}).
 * It is consumed by the account-update flow migrated from COBOL program
 * {@code COACTUPC} (CICS transaction {@code CAUP}) and received by
 * {@code AccountUpdateController}. This is the largest online screen in
 * CardDemo: it carries every user-editable account and customer field.</p>
 *
 * <p><strong>Field-contract parity.</strong> Each component preserves the name,
 * maximum length, and PIC-derived type of its {@code CACTUPAI} source field
 * one-for-one, because dropping a field or an edit rule would be a behavioral
 * regression. On the 3270 screen the editable fields are the
 * {@code ATTRB=(UNPROT), HILIGHT=UNDERLINE} fields of {@code app/bms/COACTUP.bms};
 * dates are split into separate year / month / day fields and the Social
 * Security Number and telephone numbers are split into parts, so each part is
 * modeled here as a separate field to match the map exactly.</p>
 *
 * <p><strong>Monetary fields.</strong> The five monetary values
 * ({@code creditLimit}, {@code cashLimit}, {@code currentBalance},
 * {@code currentCycleCredit}, {@code currentCycleDebit}) are modeled as
 * {@link java.math.BigDecimal} at scale {@code 2}; floating-point types are
 * never used for money. Non-null monetary values supplied to the canonical
 * constructor are normalized to scale {@code 2} using
 * {@link java.math.RoundingMode#HALF_UP}, matching the rounding convention used
 * throughout the migrated system. This normalization enforces the monetary
 * representation invariant at the DTO boundary and performs no business
 * calculation.</p>
 *
 * <p><strong>Structural validation.</strong> Every character field declares a
 * {@link jakarta.validation.constraints.Size} constraint matching its
 * {@code PIC X(n)} length exactly, and the fields the legacy program treats as
 * numeric (the account id, the date parts, the FICO score, the SSN parts and
 * the phone parts) additionally declare a numeric
 * {@link jakarta.validation.constraints.Pattern} that permits a blank or
 * partially entered value ({@code ^\d{0,n}$}). Only these <em>structural</em>
 * constraints (length and basic format) live on the DTO. The detailed edit
 * paragraphs migrated from {@code COACTUPC} &mdash; mandatory-versus-optional
 * rules, value ranges, and cross-field date validity &mdash; are enforced in the
 * {@code service/rule} components and the controller, not here; this type holds
 * no business logic.</p>
 *
 * <p><strong>Sensitive data.</strong> The Social Security Number parts
 * ({@code ssnPart1}, {@code ssnPart2}, {@code ssnPart3}), the date-of-birth
 * parts ({@code dobYear}, {@code dobMonth}, {@code dobDay}), and the government
 * identifier ({@code governmentId}) are treated as sensitive: their values are
 * masked by {@link #toString()} and must never be written to logs. No card
 * verification value (CVV) is present on this screen and none is added here.</p>
 *
 * <p><strong>Action.</strong> The {@code action} component carries the operator
 * intent that the legacy program derived from {@code EIBAID} (see shared
 * copybook {@code CSSTRPFY.cpy}), modeled as the shared {@link PfKeyAction}
 * enum. For this screen the meaningful keys, per the map footer, are
 * {@link PfKeyAction#ENTER} (Process), {@link PfKeyAction#PF3} (Exit),
 * {@link PfKeyAction#PF5} (Save) and {@link PfKeyAction#PF12} (Cancel); the
 * controller decides the behavior for each.</p>
 *
 * @param accountId          account identifier key ({@code ACCTSIDI}, X(11)); numeric.
 * @param accountStatus      account active status flag ({@code ACSTTUSI}, X(1)).
 * @param openYear           account open-date year part ({@code OPNYEARI}, X(4)); numeric.
 * @param openMonth          account open-date month part ({@code OPNMONI}, X(2)); numeric.
 * @param openDay            account open-date day part ({@code OPNDAYI}, X(2)); numeric.
 * @param creditLimit        credit limit, monetary scale 2 ({@code ACRDLIMI}, X(15)).
 * @param expiryYear         card expiry year part ({@code EXPYEARI}, X(4)); numeric.
 * @param expiryMonth        card expiry month part ({@code EXPMONI}, X(2)); numeric.
 * @param expiryDay          card expiry day part ({@code EXPDAYI}, X(2)); numeric.
 * @param cashLimit          cash credit limit, monetary scale 2 ({@code ACSHLIMI}, X(15)).
 * @param reissueYear        reissue year part ({@code RISYEARI}, X(4)); numeric.
 * @param reissueMonth       reissue month part ({@code RISMONI}, X(2)); numeric.
 * @param reissueDay         reissue day part ({@code RISDAYI}, X(2)); numeric.
 * @param currentBalance     current balance, monetary scale 2 ({@code ACURBALI}, X(15)).
 * @param currentCycleCredit current cycle credit, monetary scale 2 ({@code ACRCYCRI}, X(15)).
 * @param groupId            account (disclosure) group id ({@code AADDGRPI}, X(10)).
 * @param currentCycleDebit  current cycle debit, monetary scale 2 ({@code ACRCYDBI}, X(15)).
 * @param customerId         customer identifier ({@code ACSTNUMI}, X(9)).
 * @param ssnPart1           SSN area part &mdash; SENSITIVE ({@code ACTSSN1I}, X(3)); numeric.
 * @param ssnPart2           SSN group part &mdash; SENSITIVE ({@code ACTSSN2I}, X(2)); numeric.
 * @param ssnPart3           SSN serial part &mdash; SENSITIVE ({@code ACTSSN3I}, X(4)); numeric.
 * @param dobYear            date-of-birth year &mdash; SENSITIVE ({@code DOBYEARI}, X(4)); numeric.
 * @param dobMonth           date-of-birth month &mdash; SENSITIVE ({@code DOBMONI}, X(2)); numeric.
 * @param dobDay             date-of-birth day &mdash; SENSITIVE ({@code DOBDAYI}, X(2)); numeric.
 * @param ficoScore          customer FICO score ({@code ACSTFCOI}, X(3)); numeric.
 * @param firstName          customer first name ({@code ACSFNAMI}, X(25)).
 * @param middleName         customer middle name ({@code ACSMNAMI}, X(25)).
 * @param lastName           customer last name ({@code ACSLNAMI}, X(25)).
 * @param addressLine1       customer address line 1 ({@code ACSADL1I}, X(50)).
 * @param stateCode          customer state code ({@code ACSSTTEI}, X(2)).
 * @param addressLine2       customer address line 2 ({@code ACSADL2I}, X(50)).
 * @param zipCode            customer ZIP code ({@code ACSZIPCI}, X(5)).
 * @param city               customer city ({@code ACSCITYI}, X(50)).
 * @param countryCode        customer country code ({@code ACSCTRYI}, X(3)).
 * @param phone1Area         primary phone area code ({@code ACSPH1AI}, X(3)); numeric.
 * @param phone1Prefix       primary phone prefix ({@code ACSPH1BI}, X(3)); numeric.
 * @param phone1Line         primary phone line number ({@code ACSPH1CI}, X(4)); numeric.
 * @param governmentId       government-issued id &mdash; SENSITIVE ({@code ACSGOVTI}, X(20)).
 * @param phone2Area         secondary phone area code ({@code ACSPH2AI}, X(3)); numeric.
 * @param phone2Prefix       secondary phone prefix ({@code ACSPH2BI}, X(3)); numeric.
 * @param phone2Line         secondary phone line number ({@code ACSPH2CI}, X(4)); numeric.
 * @param eftAccountId       EFT account identifier ({@code ACSEFTCI}, X(10)).
 * @param primaryHolderFlag  primary card-holder flag ({@code ACSPFLGI}, X(1)).
 * @param action             the operator's attention key (Enter / PF3 / PF5 / PF12)
 *                           translated from {@code EIBAID} via {@code CSSTRPFY.cpy}.
 */
public record AccountUpdateRequest(

        @Size(max = 11)
        @Pattern(regexp = "^\\d{0,11}$")
        String accountId,

        @Size(max = 1)
        String accountStatus,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String openYear,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String openMonth,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String openDay,

        BigDecimal creditLimit,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String expiryYear,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String expiryMonth,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String expiryDay,

        BigDecimal cashLimit,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String reissueYear,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String reissueMonth,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String reissueDay,

        BigDecimal currentBalance,

        BigDecimal currentCycleCredit,

        @Size(max = 10)
        String groupId,

        BigDecimal currentCycleDebit,

        @Size(max = 9)
        String customerId,

        @Size(max = 3)
        @Pattern(regexp = "^\\d{0,3}$")
        String ssnPart1,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String ssnPart2,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String ssnPart3,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String dobYear,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String dobMonth,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String dobDay,

        @Size(max = 3)
        @Pattern(regexp = "^\\d{0,3}$")
        String ficoScore,

        @Size(max = 25)
        String firstName,

        @Size(max = 25)
        String middleName,

        @Size(max = 25)
        String lastName,

        @Size(max = 50)
        String addressLine1,

        @Size(max = 2)
        String stateCode,

        @Size(max = 50)
        String addressLine2,

        @Size(max = 5)
        String zipCode,

        @Size(max = 50)
        String city,

        @Size(max = 3)
        String countryCode,

        @Size(max = 3)
        @Pattern(regexp = "^\\d{0,3}$")
        String phone1Area,

        @Size(max = 3)
        @Pattern(regexp = "^\\d{0,3}$")
        String phone1Prefix,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String phone1Line,

        @Size(max = 20)
        String governmentId,

        @Size(max = 3)
        @Pattern(regexp = "^\\d{0,3}$")
        String phone2Area,

        @Size(max = 3)
        @Pattern(regexp = "^\\d{0,3}$")
        String phone2Prefix,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String phone2Line,

        @Size(max = 10)
        String eftAccountId,

        @Size(max = 1)
        String primaryHolderFlag,

        PfKeyAction action) {

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
    public AccountUpdateRequest {
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
        return "AccountUpdateRequest["
                + "accountId=" + accountId
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
                + ", action=" + action
                + "]";
    }
}

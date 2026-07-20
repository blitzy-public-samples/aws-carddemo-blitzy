/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

import java.math.BigDecimal;

/**
 * Response DTO for the CardDemo <strong>Account View</strong> screen.
 *
 * <p>This type is the Java re-platform of the BMS output map {@code COACTVW}
 * ({@code CACTVWAO} symbolic output group in {@code app/cpy-bms/COACTVW.CPY},
 * screen defined in {@code app/bms/COACTVW.bms}) driven by the online program
 * {@code COACTVWC} (CICS transaction {@code CAVW}). It is produced by
 * {@code AccountViewController} / {@code AccountService} and carries every
 * display field of the selected account together with the associated customer
 * details, merging data sourced from the {@code account} and {@code customer}
 * tables.</p>
 *
 * <p><b>Field contract.</b> Each component maps one-to-one to a {@code CACTVWAO}
 * output field, preserving the original field name (camel-cased), maximum
 * length, and PIC-derived type so that the 3270 field-level contract remains
 * intact (no feature expansion). Terminal-only attribute bytes (the
 * {@code C}/{@code P}/{@code H}/{@code V} sub-fields present in the copybook)
 * are intentionally omitted because no terminal rendering occurs in the
 * re-platformed system.</p>
 *
 * <p><b>Monetary fidelity.</b> The five monetary fields
 * ({@link #creditLimit()}, {@link #cashLimit()}, {@link #currentBalance()},
 * {@link #currentCycleCredit()}, {@link #currentCycleDebit()}) correspond to the
 * BMS {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} numeric-edited fields and are modeled as
 * {@link java.math.BigDecimal} (scale 2). Floating-point types
 * ({@code double}/{@code float}) are never used for decimal money values.</p>
 *
 * <p><b>Sensitive data.</b> The {@code ssn}, {@code dateOfBirth}, and
 * {@code governmentId} components are part of the displayed account-view
 * contract and therefore remain fields on this response, but they are masked in
 * {@link #toString()} and must never be logged. The {@code toString()} override
 * below emits {@code ***} in place of their values while printing every other
 * component. This screen has no card verification value (CVV) field.</p>
 *
 * <p>This is an immutable value carrier with no business logic; population is
 * performed by the mapper/service layer.</p>
 *
 * @param transactionName    header transaction id echo ({@code TRNNAMEO}, X(4))
 * @param title01            first title line ({@code TITLE01O}, X(40))
 * @param currentDate        current date header text ({@code CURDATEO}, X(8))
 * @param programName        program name echo ({@code PGMNAMEO}, X(8))
 * @param title02            second title line ({@code TITLE02O}, X(40))
 * @param currentTime        current time header text ({@code CURTIMEO}, X(8))
 * @param accountId          echoed 11-digit account id ({@code ACCTSIDO}, 9(11))
 * @param accountStatus      active/closed flag ({@code ACSTTUSO}, X(1))
 * @param dateOpened         account open date, ISO-style string ({@code ADTOPENO}, X(10))
 * @param creditLimit        credit limit, monetary scale 2 ({@code ACRDLIMO}, +ZZZ,ZZZ,ZZZ.99)
 * @param expiryDate         card/account expiry date string ({@code AEXPDTO}, X(10))
 * @param cashLimit          cash credit limit, monetary scale 2 ({@code ACSHLIMO}, +ZZZ,ZZZ,ZZZ.99)
 * @param reissueDate        reissue date string ({@code AREISDTO}, X(10))
 * @param currentBalance     current balance, monetary scale 2 ({@code ACURBALO}, +ZZZ,ZZZ,ZZZ.99)
 * @param currentCycleCredit current cycle credit, monetary scale 2 ({@code ACRCYCRO}, +ZZZ,ZZZ,ZZZ.99)
 * @param groupId            account/disclosure group id ({@code AADDGRPO}, X(10))
 * @param currentCycleDebit  current cycle debit, monetary scale 2 ({@code ACRCYDBO}, +ZZZ,ZZZ,ZZZ.99)
 * @param customerId         customer number ({@code ACSTNUMO}, X(9))
 * @param ssn                social security number &mdash; SENSITIVE, masked in {@code toString()} ({@code ACSTSSNO}, X(12))
 * @param dateOfBirth        customer date of birth &mdash; SENSITIVE, masked in {@code toString()} ({@code ACSTDOBO}, X(10))
 * @param ficoScore          FICO credit score ({@code ACSTFCOO}, X(3))
 * @param firstName          customer first name ({@code ACSFNAMO}, X(25))
 * @param middleName         customer middle name ({@code ACSMNAMO}, X(25))
 * @param lastName           customer last name ({@code ACSLNAMO}, X(25))
 * @param addressLine1       address line 1 ({@code ACSADL1O}, X(50))
 * @param stateCode          state code ({@code ACSSTTEO}, X(2))
 * @param addressLine2       address line 2 ({@code ACSADL2O}, X(50))
 * @param zipCode            ZIP code ({@code ACSZIPCO}, X(5))
 * @param city               city ({@code ACSCITYO}, X(50))
 * @param countryCode        country code ({@code ACSCTRYO}, X(3))
 * @param phone1             primary phone number ({@code ACSPHN1O}, X(13))
 * @param governmentId       government-issued id &mdash; SENSITIVE, masked in {@code toString()} ({@code ACSGOVTO}, X(20))
 * @param phone2             secondary phone number ({@code ACSPHN2O}, X(13))
 * @param eftAccountId       EFT account id ({@code ACSEFTCO}, X(10))
 * @param primaryHolderFlag  primary card-holder indicator ({@code ACSPFLGO}, X(1))
 * @param infoMessage        informational message ({@code INFOMSGO}, X(45))
 * @param errorMessage       error message; constants trace to {@code app/cpy/CSMSG01Y.cpy} ({@code ERRMSGO}, X(78))
 */
public record AccountViewResponse(
        // --- Screen header / system fields ---
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        // --- Account detail fields ---
        String accountId,
        String accountStatus,
        String dateOpened,
        BigDecimal creditLimit,
        String expiryDate,
        BigDecimal cashLimit,
        String reissueDate,
        BigDecimal currentBalance,
        BigDecimal currentCycleCredit,
        String groupId,
        BigDecimal currentCycleDebit,
        // --- Customer detail fields ---
        String customerId,
        String ssn,
        String dateOfBirth,
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
        String phone1,
        String governmentId,
        String phone2,
        String eftAccountId,
        String primaryHolderFlag,
        // --- Screen message fields ---
        String infoMessage,
        String errorMessage) {

    /** Placeholder emitted in {@link #toString()} for every sensitive field. */
    private static final String MASKED = "***";

    /**
     * Returns a diagnostic string representation of this response with the three
     * sensitive fields ({@code ssn}, {@code dateOfBirth}, {@code governmentId})
     * masked as {@value #MASKED}. All other components are rendered with their
     * actual values. This override replaces the record's default
     * {@code toString()} so that sensitive personally identifiable information is
     * never exposed through logging, diagnostics, or accidental string
     * interpolation.
     *
     * @return a string representation with sensitive fields masked
     */
    @Override
    public String toString() {
        return "AccountViewResponse["
                + "transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", accountId=" + accountId
                + ", accountStatus=" + accountStatus
                + ", dateOpened=" + dateOpened
                + ", creditLimit=" + creditLimit
                + ", expiryDate=" + expiryDate
                + ", cashLimit=" + cashLimit
                + ", reissueDate=" + reissueDate
                + ", currentBalance=" + currentBalance
                + ", currentCycleCredit=" + currentCycleCredit
                + ", groupId=" + groupId
                + ", currentCycleDebit=" + currentCycleDebit
                + ", customerId=" + customerId
                + ", ssn=" + MASKED
                + ", dateOfBirth=" + MASKED
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
                + ", phone1=" + phone1
                + ", governmentId=" + MASKED
                + ", phone2=" + phone2
                + ", eftAccountId=" + eftAccountId
                + ", primaryHolderFlag=" + primaryHolderFlag
                + ", infoMessage=" + infoMessage
                + ", errorMessage=" + errorMessage
                + "]";
    }
}

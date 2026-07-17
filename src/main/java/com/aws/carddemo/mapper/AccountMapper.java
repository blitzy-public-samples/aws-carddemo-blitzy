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
package com.aws.carddemo.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.AccountUpdateRequest;
import com.aws.carddemo.dto.AccountUpdateResponse;
import com.aws.carddemo.dto.AccountViewResponse;

/**
 * Hand-written mapper between the {@link Account} / {@link Customer} JPA entities
 * and the Account View / Account Update data-transfer objects.
 *
 * <p><b>Source lineage.</b> This component is the Java re-platform of the field
 * movement performed by two online COBOL programs:</p>
 * <ul>
 *   <li>{@code COACTVWC} (CICS transaction {@code CAVW}) &mdash; the read-only
 *       <em>Account View</em> inquiry that merges an account and its owning
 *       customer into a single display map ({@code CACTVWAO} in
 *       {@code app/cpy-bms/COACTVW.CPY}); and</li>
 *   <li>{@code COACTUPC} (CICS transaction {@code CAUP}) &mdash; the
 *       <em>Account Update</em> screen (the largest online program) whose map
 *       ({@code CACTUPAO} in {@code app/cpy-bms/COACTUP.CPY}) splits the social
 *       security number, dates of birth, account dates and phone numbers into
 *       their individual component fields.</li>
 * </ul>
 *
 * <p><b>Decompose / compose duality.</b> Because the View screen carries single
 * formatted strings while the Update screen carries the same data split into
 * component parts, this mapper both <em>decomposes</em> entity strings into
 * parts ({@link #toUpdateResponse}) and <em>composes</em> parts back into the
 * stored entity strings ({@link #updateEntities}). The split/join helpers are a
 * faithful, lossless representation of the corresponding COBOL
 * {@code STRING} / reference-modification field handling; for a
 * component-only decomposition and recomposition a substring split followed by a
 * join reproduces the original stored value exactly.</p>
 *
 * <p><b>Cross-cutting guarantees.</b></p>
 * <ul>
 *   <li>Monetary values are always {@link BigDecimal} at scale {@value #MONETARY_SCALE};
 *       every copy is normalized with {@link RoundingMode#HALF_UP} without
 *       altering the numeric value. Floating-point types are never used for
 *       money.</li>
 *   <li>The sensitive customer fields &mdash; social security number, date of
 *       birth and government-issued id &mdash; are part of the Account
 *       View/Update display contract and are therefore copied into the response
 *       DTOs (which already mask them in {@code toString()}). This mapper copies
 *       them normally and <strong>never logs them</strong>.</li>
 *   <li>{@link #updateEntities} never overwrites the entity primary keys
 *       ({@code acctId}, {@code custId}) or the optimistic-locking
 *       {@code version}; those remain under the ownership of the persistence
 *       layer and the calling service.</li>
 *   <li>The mapper performs no business validation (numeric/date edit rules live
 *       in {@code service/rule}) and no persistence (the service owns the
 *       transaction); it performs only safe, null- and length-tolerant field
 *       parsing and formatting.</li>
 * </ul>
 *
 * <p>The type is a stateless Spring {@link Component} and is therefore
 * thread-safe; all helper methods are {@code static} and side-effect free
 * except for the in-place entity mutation performed by {@link #updateEntities}.</p>
 */
@Component
public class AccountMapper {

    /** Scale applied to every monetary field, mirroring COBOL {@code PIC S9(10)V99}. */
    private static final int MONETARY_SCALE = 2;

    /**
     * Number of leading characters of the stored 10-character ZIP taken for the
     * 5-character display field ({@code ACSZIPC}, {@code X(5)}).
     */
    private static final int ZIP_DISPLAY_LENGTH = 5;

    /**
     * First screen title line, byte-exact from {@code CCDA-TITLE01}
     * ({@code legacy/cpy/COTTL01Y.cpy}, {@code PIC X(40)}).
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title line, byte-exact from {@code CCDA-TITLE02}
     * ({@code legacy/cpy/COTTL01Y.cpy}, {@code PIC X(40)}).
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /** Account View transaction id ({@code LIT-THISTRANID} in {@code COACTVWC}). */
    private static final String VIEW_TRANSACTION_NAME = "CAVW";

    /** Account View program name ({@code LIT-THISPGM} in {@code COACTVWC}). */
    private static final String VIEW_PROGRAM_NAME = "COACTVWC";

    /** Account Update transaction id ({@code LIT-THISTRANID} in {@code COACTUPC}). */
    private static final String UPDATE_TRANSACTION_NAME = "CAUP";

    /** Account Update program name ({@code LIT-THISPGM} in {@code COACTUPC}). */
    private static final String UPDATE_PROGRAM_NAME = "COACTUPC";

    /**
     * Primary function-key footer of the Update screen ({@code FKEYS} initial
     * value in {@code legacy/bms/COACTUP.bms}, {@code X(21)}).
     */
    private static final String FKEY_PROCESS_EXIT = "ENTER=Process F3=Exit";

    /** Save function-key label ({@code FKEY05} initial value, {@code X(7)}). */
    private static final String FKEY_SAVE = "F5=Save";

    /** Cancel function-key label ({@code FKEY12} initial value, {@code X(10)}). */
    private static final String FKEY_CANCEL = "F12=Cancel";

    /**
     * Builds the read-only Account View response by merging an account with its
     * owning customer, reproducing the field movement of {@code COACTVWC}.
     *
     * <p>All fields are copied as single formatted strings (the View screen does
     * not split any field). Monetary values are normalized to scale
     * {@value #MONETARY_SCALE}. The {@code ssn}, {@code dateOfBirth} and
     * {@code governmentId} components are populated from the customer record for
     * legacy display parity; the response DTO masks them in {@code toString()}.</p>
     *
     * @param account      the account whose details are displayed; must not be {@code null}
     * @param customer     the owning customer whose details are merged in; must not be {@code null}
     * @param infoMessage  informational message for the screen footer; may be {@code null}
     * @param errorMessage error message for the screen footer; may be {@code null}
     * @param now          the timestamp used to render the header date/time; when
     *                     {@code null} the header date and time are rendered as
     *                     empty strings
     * @return a fully populated {@link AccountViewResponse}
     */
    public AccountViewResponse toViewResponse(Account account,
                                              Customer customer,
                                              String infoMessage,
                                              String errorMessage,
                                              LocalDateTime now) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(customer, "customer must not be null");

        String currentDate = now == null ? "" : DateUtils.formatDateMmDdYy(now.toLocalDate());
        String currentTime = now == null ? "" : DateUtils.formatTimeHhMmSs(now.toLocalTime());
        String ficoScore = customer.getCustFicoCreditScore() == null
                ? ""
                : String.valueOf(customer.getCustFicoCreditScore());

        return new AccountViewResponse(
                // --- Screen header / system fields ---
                VIEW_TRANSACTION_NAME,                        // transactionName
                SCREEN_TITLE_01,                              // title01
                currentDate,                                  // currentDate
                VIEW_PROGRAM_NAME,                            // programName
                SCREEN_TITLE_02,                              // title02
                currentTime,                                  // currentTime
                // --- Account detail fields ---
                String.valueOf(account.getAcctId()),          // accountId
                account.getAcctActiveStatus(),                // accountStatus
                account.getAcctOpenDate(),                    // dateOpened
                scale2(account.getCreditLimit()),             // creditLimit
                account.getAcctExpirationDate(),              // expiryDate
                scale2(account.getCashCreditLimit()),         // cashLimit
                account.getAcctReissueDate(),                 // reissueDate
                scale2(account.getCurrBal()),                 // currentBalance
                scale2(account.getCurrCycCredit()),           // currentCycleCredit
                account.getGroupId(),                         // groupId
                scale2(account.getCurrCycDebit()),            // currentCycleDebit
                // --- Customer detail fields ---
                String.valueOf(customer.getCustId()),         // customerId
                customer.getCustSsn(),                        // ssn (SENSITIVE - raw digits)
                customer.getCustDob(),                        // dateOfBirth (SENSITIVE)
                ficoScore,                                    // ficoScore
                customer.getCustFirstName(),                  // firstName
                customer.getCustMiddleName(),                 // middleName
                customer.getCustLastName(),                   // lastName
                customer.getCustAddrLine1(),                  // addressLine1
                customer.getCustAddrStateCd(),                // stateCode
                customer.getCustAddrLine2(),                  // addressLine2
                leadingFive(customer.getCustAddrZip()),       // zipCode (leading 5 of stored X10)
                customer.getCustAddrLine3(),                  // city (<- CUST-ADDR-LINE-3)
                customer.getCustAddrCountryCd(),              // countryCode
                customer.getCustPhoneNum1(),                  // phone1
                customer.getCustGovtIssuedId(),               // governmentId (SENSITIVE)
                customer.getCustPhoneNum2(),                  // phone2
                customer.getCustEftAccountId(),               // eftAccountId
                customer.getCustPriCardHolderInd(),           // primaryHolderFlag
                // --- Screen message fields ---
                infoMessage,                                  // infoMessage
                errorMessage);                                // errorMessage
    }

    /**
     * Builds the Account Update response, reproducing the field movement of
     * {@code COACTUPC} by <em>decomposing</em> the composite entity strings into
     * their individual component fields (year/month/day for dates; area/prefix/line
     * for phones; the three social-security parts).
     *
     * <p>Scalar, monetary, name and address fields are copied exactly as in the
     * View builder (monetary values normalized to scale {@value #MONETARY_SCALE};
     * {@code city} sourced from {@code CUST-ADDR-LINE-3}; {@code zipCode} taking
     * the leading five characters of the stored ZIP). The sensitive SSN,
     * date-of-birth and government-id fields are populated for legacy display
     * parity and are masked by the DTO's {@code toString()}.</p>
     *
     * @param account      the account whose details are displayed; must not be {@code null}
     * @param customer     the owning customer whose details are merged in; must not be {@code null}
     * @param infoMessage  informational message for the screen footer; may be {@code null}
     * @param errorMessage error message for the screen footer; may be {@code null}
     * @param now          the timestamp used to render the header date/time; when
     *                     {@code null} the header date and time are rendered as
     *                     empty strings
     * @return a fully populated {@link AccountUpdateResponse}
     */
    public AccountUpdateResponse toUpdateResponse(Account account,
                                                  Customer customer,
                                                  String infoMessage,
                                                  String errorMessage,
                                                  LocalDateTime now) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(customer, "customer must not be null");

        String currentDate = now == null ? "" : DateUtils.formatDateMmDdYy(now.toLocalDate());
        String currentTime = now == null ? "" : DateUtils.formatTimeHhMmSs(now.toLocalTime());
        String ficoScore = customer.getCustFicoCreditScore() == null
                ? ""
                : String.valueOf(customer.getCustFicoCreditScore());

        // Decompose the composite entity strings into their screen component parts.
        String[] openDate = splitDate(account.getAcctOpenDate());
        String[] expiryDate = splitDate(account.getAcctExpirationDate());
        String[] reissueDate = splitDate(account.getAcctReissueDate());
        String[] ssn = splitSsn(customer.getCustSsn());
        String[] dob = splitDate(customer.getCustDob());
        String[] phone1 = splitPhone(customer.getCustPhoneNum1());
        String[] phone2 = splitPhone(customer.getCustPhoneNum2());

        return new AccountUpdateResponse(
                // --- Screen header / system fields ---
                UPDATE_TRANSACTION_NAME,                      // transactionName
                SCREEN_TITLE_01,                              // title01
                SCREEN_TITLE_02,                              // title02
                currentDate,                                  // currentDate
                UPDATE_PROGRAM_NAME,                          // programName
                currentTime,                                  // currentTime
                // --- Account detail fields (dates decomposed) ---
                String.valueOf(account.getAcctId()),          // accountId
                account.getAcctActiveStatus(),                // accountStatus
                openDate[0],                                  // openYear
                openDate[1],                                  // openMonth
                openDate[2],                                  // openDay
                scale2(account.getCreditLimit()),             // creditLimit
                expiryDate[0],                                // expiryYear
                expiryDate[1],                                // expiryMonth
                expiryDate[2],                                // expiryDay
                scale2(account.getCashCreditLimit()),         // cashLimit
                reissueDate[0],                               // reissueYear
                reissueDate[1],                               // reissueMonth
                reissueDate[2],                               // reissueDay
                scale2(account.getCurrBal()),                 // currentBalance
                scale2(account.getCurrCycCredit()),           // currentCycleCredit
                account.getGroupId(),                         // groupId
                scale2(account.getCurrCycDebit()),            // currentCycleDebit
                // --- Customer detail fields (ssn/dob/phones decomposed) ---
                String.valueOf(customer.getCustId()),         // customerId
                ssn[0],                                       // ssnPart1 (SENSITIVE)
                ssn[1],                                       // ssnPart2 (SENSITIVE)
                ssn[2],                                       // ssnPart3 (SENSITIVE)
                dob[0],                                       // dobYear (SENSITIVE)
                dob[1],                                       // dobMonth (SENSITIVE)
                dob[2],                                       // dobDay (SENSITIVE)
                ficoScore,                                    // ficoScore
                customer.getCustFirstName(),                  // firstName
                customer.getCustMiddleName(),                 // middleName
                customer.getCustLastName(),                   // lastName
                customer.getCustAddrLine1(),                  // addressLine1
                customer.getCustAddrStateCd(),                // stateCode
                customer.getCustAddrLine2(),                  // addressLine2
                leadingFive(customer.getCustAddrZip()),       // zipCode (leading 5 of stored X10)
                customer.getCustAddrLine3(),                  // city (<- CUST-ADDR-LINE-3)
                customer.getCustAddrCountryCd(),              // countryCode
                phone1[0],                                    // phone1Area
                phone1[1],                                    // phone1Prefix
                phone1[2],                                    // phone1Line
                customer.getCustGovtIssuedId(),               // governmentId (SENSITIVE)
                phone2[0],                                    // phone2Area
                phone2[1],                                    // phone2Prefix
                phone2[2],                                    // phone2Line
                customer.getCustEftAccountId(),               // eftAccountId
                customer.getCustPriCardHolderInd(),           // primaryHolderFlag
                // --- Screen message fields ---
                infoMessage,                                  // infoMessage
                errorMessage,                                 // errorMessage
                // --- Function-key footer labels ---
                FKEY_PROCESS_EXIT,                            // functionKeys
                FKEY_SAVE,                                     // functionKeySave
                FKEY_CANCEL);                                 // functionKeyCancel
    }

    /**
     * Applies the edited Account Update request onto the supplied managed
     * entities in place, reproducing the field movement of {@code COACTUPC} by
     * <em>composing</em> the screen component parts back into the composite
     * entity strings.
     *
     * <p>The account and customer are expected to have been fetched by the
     * calling service; this method mutates them but performs no persistence and
     * no business validation. The entity primary keys ({@code acctId},
     * {@code custId}) and the optimistic-locking {@code version} are never
     * modified.</p>
     *
     * <p>The FICO score is applied only when the request carries a parseable
     * value: a blank or non-numeric value leaves the existing score unchanged
     * (numeric edit validation is the responsibility of {@code service/rule}).</p>
     *
     * @param req      the edited request fields; must not be {@code null}
     * @param account  the managed account to update in place; must not be {@code null}
     * @param customer the managed customer to update in place; must not be {@code null}
     */
    public void updateEntities(AccountUpdateRequest req, Account account, Customer customer) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(customer, "customer must not be null");

        // --- Account (keys and version are intentionally left untouched) ---
        account.setAcctActiveStatus(req.accountStatus());
        account.setCreditLimit(scale2(req.creditLimit()));
        account.setCashCreditLimit(scale2(req.cashLimit()));
        account.setCurrBal(scale2(req.currentBalance()));
        account.setCurrCycCredit(scale2(req.currentCycleCredit()));
        account.setCurrCycDebit(scale2(req.currentCycleDebit()));
        account.setGroupId(req.groupId());
        account.setAcctOpenDate(composeDate(req.openYear(), req.openMonth(), req.openDay()));
        account.setAcctExpirationDate(composeDate(req.expiryYear(), req.expiryMonth(), req.expiryDay()));
        account.setAcctReissueDate(composeDate(req.reissueYear(), req.reissueMonth(), req.reissueDay()));

        // --- Customer (primary key custId is intentionally left untouched) ---
        customer.setCustFirstName(req.firstName());
        customer.setCustMiddleName(req.middleName());
        customer.setCustLastName(req.lastName());
        customer.setCustAddrLine1(req.addressLine1());
        customer.setCustAddrLine2(req.addressLine2());
        customer.setCustAddrLine3(req.city());
        customer.setCustAddrStateCd(req.stateCode());
        customer.setCustAddrZip(req.zipCode());
        customer.setCustAddrCountryCd(req.countryCode());
        customer.setCustSsn(composeSsn(req.ssnPart1(), req.ssnPart2(), req.ssnPart3()));
        customer.setCustGovtIssuedId(req.governmentId());
        customer.setCustDob(composeDate(req.dobYear(), req.dobMonth(), req.dobDay()));
        customer.setCustPhoneNum1(composePhone(req.phone1Area(), req.phone1Prefix(), req.phone1Line()));
        customer.setCustPhoneNum2(composePhone(req.phone2Area(), req.phone2Prefix(), req.phone2Line()));
        customer.setCustEftAccountId(req.eftAccountId());
        customer.setCustPriCardHolderInd(req.primaryHolderFlag());
        applyFicoScore(req.ficoScore(), customer);
    }

    // ------------------------------------------------------------------------
    // Private helpers - null- and length-safe field parsing / formatting.
    // ------------------------------------------------------------------------

    /**
     * Normalizes a monetary amount to the fixed monetary scale without altering
     * its value.
     *
     * @param value the amount to normalize; may be {@code null}
     * @return {@code null} when {@code value} is {@code null}; otherwise
     *         {@code value} rescaled to {@value #MONETARY_SCALE} places using
     *         {@link RoundingMode#HALF_UP}
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Returns the leading five characters of the stored ZIP for the 5-character
     * display field, preserving a {@code null} input as {@code null}.
     *
     * @param zip the stored ZIP (up to 10 characters); may be {@code null}
     * @return {@code null} when {@code zip} is {@code null}; the whole value when
     *         it is five characters or shorter; otherwise its first five characters
     */
    private static String leadingFive(String zip) {
        if (zip == null) {
            return null;
        }
        return zip.length() <= ZIP_DISPLAY_LENGTH ? zip : zip.substring(0, ZIP_DISPLAY_LENGTH);
    }

    /**
     * Applies a FICO score to the customer when the supplied value is parseable.
     *
     * @param ficoScore the FICO score text from the request; may be {@code null}
     *                  or blank
     * @param customer  the customer to update; must not be {@code null}
     */
    private static void applyFicoScore(String ficoScore, Customer customer) {
        if (ficoScore == null || ficoScore.isBlank()) {
            // No value supplied: leave the existing score unchanged.
            return;
        }
        try {
            customer.setCustFicoCreditScore(Integer.valueOf(ficoScore.trim()));
        } catch (NumberFormatException ex) {
            // Non-numeric input: leave the existing score unchanged. Numeric edit
            // validation is enforced by the service/rule layer, not the mapper.
        }
    }

    /**
     * Splits a {@code "YYYY-MM-DD"} date string into its year, month and day
     * components. The split is a pure, lossless field decomposition: joining the
     * three results with {@link #composeDate} reproduces the original value.
     *
     * @param date the date string; may be {@code null} or shorter than expected
     * @return a three-element array {@code [year, month, day]}; missing
     *         components are returned as empty strings, never {@code null}
     */
    private static String[] splitDate(String date) {
        return new String[] {
                safeSubstring(date, 0, 4),
                safeSubstring(date, 5, 7),
                safeSubstring(date, 8, 10)
        };
    }

    /**
     * Composes a {@code "YYYY-MM-DD"} date string from its year, month and day
     * components.
     *
     * @param year  the year component; may be {@code null}
     * @param month the month component; may be {@code null}
     * @param day   the day component; may be {@code null}
     * @return an empty string when all three components are empty; otherwise the
     *         components joined with {@code '-'}
     */
    private static String composeDate(String year, String month, String day) {
        String y = nullToEmpty(year);
        String m = nullToEmpty(month);
        String d = nullToEmpty(day);
        if (y.isEmpty() && m.isEmpty() && d.isEmpty()) {
            return "";
        }
        return y + "-" + m + "-" + d;
    }

    /**
     * Splits a raw nine-digit social security number into its area, group and
     * serial parts (3 / 2 / 4 digits). The split is a pure, lossless field
     * decomposition: joining the three results with {@link #composeSsn}
     * reproduces the original value.
     *
     * @param ssn the raw nine-digit SSN; may be {@code null} or shorter
     * @return a three-element array {@code [part1, part2, part3]}; missing
     *         components are returned as empty strings, never {@code null}
     */
    private static String[] splitSsn(String ssn) {
        return new String[] {
                safeSubstring(ssn, 0, 3),
                safeSubstring(ssn, 3, 5),
                safeSubstring(ssn, 5, 9)
        };
    }

    /**
     * Composes a raw nine-digit social security number from its three parts.
     *
     * @param part1 the area part; may be {@code null}
     * @param part2 the group part; may be {@code null}
     * @param part3 the serial part; may be {@code null}
     * @return an empty string when all three parts are empty; otherwise the parts
     *         concatenated with no separators
     */
    private static String composeSsn(String part1, String part2, String part3) {
        String p1 = nullToEmpty(part1);
        String p2 = nullToEmpty(part2);
        String p3 = nullToEmpty(part3);
        if (p1.isEmpty() && p2.isEmpty() && p3.isEmpty()) {
            return "";
        }
        return p1 + p2 + p3;
    }

    /**
     * Splits a {@code "(XXX)XXX-XXXX"} phone number into its area, prefix and
     * line components. The split is a pure, lossless field decomposition: joining
     * the three results with {@link #composePhone} reproduces the original value.
     *
     * @param phone the formatted phone number; may be {@code null} or shorter
     * @return a three-element array {@code [area, prefix, line]}; missing
     *         components are returned as empty strings, never {@code null}
     */
    private static String[] splitPhone(String phone) {
        return new String[] {
                safeSubstring(phone, 1, 4),
                safeSubstring(phone, 5, 8),
                safeSubstring(phone, 9, 13)
        };
    }

    /**
     * Composes a {@code "(XXX)XXX-XXXX"} phone number from its area, prefix and
     * line components.
     *
     * @param area   the three-digit area code; may be {@code null}
     * @param prefix the three-digit prefix; may be {@code null}
     * @param line   the four-digit line number; may be {@code null}
     * @return an empty string when all three components are empty; otherwise the
     *         canonical {@code "(area)prefix-line"} rendering
     */
    private static String composePhone(String area, String prefix, String line) {
        String a = nullToEmpty(area);
        String p = nullToEmpty(prefix);
        String l = nullToEmpty(line);
        if (a.isEmpty() && p.isEmpty() && l.isEmpty()) {
            return "";
        }
        return "(" + a + ")" + p + "-" + l;
    }

    /**
     * Returns the substring of {@code value} bounded to the {@code [start, end)}
     * range, tolerating {@code null} and short inputs so that no
     * {@link StringIndexOutOfBoundsException} can be thrown.
     *
     * @param value the source string; may be {@code null}
     * @param start the inclusive start index
     * @param end   the exclusive end index
     * @return the bounded substring, or an empty string when {@code value} is
     *         {@code null} or too short to contain any of the requested range
     */
    private static String safeSubstring(String value, int start, int end) {
        if (value == null) {
            return "";
        }
        int length = value.length();
        if (start >= length) {
            return "";
        }
        int boundedEnd = Math.min(end, length);
        if (start >= boundedEnd) {
            return "";
        }
        return value.substring(start, boundedEnd);
    }

    /**
     * Returns the supplied value, or an empty string when it is {@code null}.
     *
     * @param value the value; may be {@code null}
     * @return {@code value} when non-{@code null}; otherwise an empty string
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

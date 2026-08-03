package com.carddemo.account.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Customer detail a caller supplies when updating a customer.
 *
 * <p>Transformed component by component from the group {@code 10 ACUP-NEW-CUST-DATA.} at
 * {@code app/cbl/COACTUPC.cbl:L797}, whose twenty members end at
 * {@code app/cbl/COACTUPC.cbl:L845}. Component order follows member order. Each component holds
 * text. card-platform/docs/traceability-matrix.md maps every component to its member.</p>
 *
 * <p>Three kinds of constraint appear here. Nineteen components declare the width of their source
 * member. Component {@code addressLine1} declares a presence test. Component
 * {@code ficoCreditScore} declares the inclusive score range.</p>
 *
 * <p>The {@code domain/validation} package of this service carries the field edits that
 * {@code app/cbl/COACTUPC.cbl:L1200} through {@code app/cbl/COACTUPC.cbl:L1676} perform. Each
 * edit reports one failing field with the message text its source paragraph composes.</p>
 *
 * <p>Components {@code socialSecurityPart1}, {@code socialSecurityPart2} and
 * {@code socialSecurityPart3} hold the three parts of the Social Security Number that
 * {@code app/cbl/COACTUPC.cbl:L830-L833} declares. Paragraph {@code 1265-EDIT-US-SSN} at
 * {@code app/cbl/COACTUPC.cbl:L2431} labels each part on its own at
 * {@code app/cbl/COACTUPC.cbl:L2439}, {@code app/cbl/COACTUPC.cbl:L2469} and
 * {@code app/cbl/COACTUPC.cbl:L2481}.</p>
 *
 * <p>Three source observations reach card-platform/docs/business-rule-flags.md. The label
 * {@code 'SSN'} at {@code app/cbl/COACTUPC.cbl:L1529} reaches no message. The comments at
 * {@code app/cbl/COACTUPC.cbl:L2433-L2435} name digit ranges for parts two and three that no
 * statement applies. {@code app/cbl/COACTUPC.cbl:L1607} edits five zip characters while
 * {@code app/cbl/COACTUPC.cbl:L809} declares ten.</p>
 *
 * <p>The three Social Security Number parts and {@code governmentIssuedId} arrive here and travel
 * no further. None of those four values reaches a response body, a log line, a published event, a
 * validation-failure message or an error payload. Every failure message names a field label and
 * carries no character of the value, matching the messages at
 * {@code app/cbl/COACTUPC.cbl:L2431-L2487}.</p>
 *
 * <p>card-platform/docs/decision-log.md records four deviations this record takes part in: the two
 * date shapes, the three-part Social Security Number model, the unreachable label, and the zip
 * width.</p>
 *
 * @param customerId                 customer identifier, from
 *                                   {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L798}, redefined as
 *                                   {@code PIC 9(09)} at {@code app/cbl/COACTUPC.cbl:L799-L800}
 * @param firstName                  customer first name, from
 *                                   {@code ACUP-NEW-CUST-FIRST-NAME PIC X(25)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L801}. Label
 *                                   {@code 'First Name'} at {@code app/cbl/COACTUPC.cbl:L1560}
 *                                   names it, and the required alphabetic edit accepts the 26
 *                                   upper-case and 26 lower-case letters at
 *                                   {@code app/cbl/COACTUPC.cbl:L588-L591}
 * @param middleName                 customer middle name, from
 *                                   {@code ACUP-NEW-CUST-MIDDLE-NAME PIC X(25)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L802}. Label
 *                                   {@code 'Middle Name'} at {@code app/cbl/COACTUPC.cbl:L1568}
 *                                   names it, and the optional alphabetic edit at
 *                                   {@code app/cbl/COACTUPC.cbl:L1571} passes an empty value
 * @param lastName                   customer last name, from
 *                                   {@code ACUP-NEW-CUST-LAST-NAME PIC X(25)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L803}. Label {@code 'Last Name'}
 *                                   at {@code app/cbl/COACTUPC.cbl:L1576} names it, and the
 *                                   required alphabetic edit governs it
 * @param addressLine1               first line of the customer address, from
 *                                   {@code ACUP-NEW-CUST-ADDR-LINE-1 PIC X(50)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L804}. Label
 *                                   {@code 'Address Line 1'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1584} names it, and the
 *                                   mandatory edit at {@code app/cbl/COACTUPC.cbl:L1829-L1834}
 *                                   fails a null, an empty and an all-space value alike
 * @param addressLine2               second line of the customer address, from
 *                                   {@code ACUP-NEW-CUST-ADDR-LINE-2 PIC X(50)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L805}.
 *                                   {@code app/cbl/COACTUPC.cbl:L1613} marks the line optional
 *                                   and {@code app/cbl/COACTUPC.cbl:L1614} leaves its label move
 *                                   commented out, so no edit reads the value and this component
 *                                   declares no constraint
 * @param addressCity                city of the customer address, from
 *                                   {@code ACUP-NEW-CUST-ADDR-LINE-3 PIC X(50)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L806}. Label {@code 'City'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1615} governs the third address
 *                                   line at {@code app/cbl/COACTUPC.cbl:L1616}, edited as fifty
 *                                   required alphabetic characters
 * @param addressStateCode           two-letter state code of the customer address, from
 *                                   {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L807}. Label {@code 'State'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1592} names it, and the
 *                                   state-code test at {@code app/cbl/COACTUPC.cbl:L1599-L1602}
 *                                   runs only when the alphabetic edit passed
 * @param addressCountryCode         three-letter country code of the customer address, from
 *                                   {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD PIC X(03)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L808}. Label {@code 'Country'}
 *                                   at {@code app/cbl/COACTUPC.cbl:L1623} names it, edited as
 *                                   three required alphabetic characters
 * @param addressZip                 postal code of the customer address, from
 *                                   {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L809}. Label {@code 'Zip'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1605} names it, and
 *                                   {@code app/cbl/COACTUPC.cbl:L1607} edits five numeric
 *                                   characters, the width {@link #ADDRESS_ZIP_MAX_LENGTH} holds.
 *                                   The combination test at
 *                                   {@code app/cbl/COACTUPC.cbl:L1665-L1669} pairs the first two
 *                                   digits with the state code and reports
 *                                   {@code 'Invalid zip code for state'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L2550} with no label prefix
 * @param phoneNumber1               primary telephone number as fifteen characters carrying the
 *                                   mask {@code (999)999-9999} and two trailing spaces, from
 *                                   {@code ACUP-NEW-CUST-PHONE-NUM-1 PIC X(15)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L810} with the part redefine at
 *                                   {@code app/cbl/COACTUPC.cbl:L811-L819}. Label
 *                                   {@code 'Phone Number 1'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1632} names it and
 *                                   {@code app/cbl/COACTUPC.cbl:L1633} moves the whole field.
 *                                   The telephone edit slices an area code, a prefix and a line
 *                                   number, reporting them at
 *                                   {@code app/cbl/COACTUPC.cbl:L2272},
 *                                   {@code app/cbl/COACTUPC.cbl:L2343} and
 *                                   {@code app/cbl/COACTUPC.cbl:L2396}
 * @param phoneNumber2               secondary telephone number in the same fifteen-character
 *                                   mask, from {@code ACUP-NEW-CUST-PHONE-NUM-2 PIC X(15)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L820} with the part redefine at
 *                                   {@code app/cbl/COACTUPC.cbl:L821-L829}. Label
 *                                   {@code 'Phone Number 2'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1640} names it and
 *                                   {@code app/cbl/COACTUPC.cbl:L1641} moves the whole field
 * @param socialSecurityPart1        first three digits of the Social Security Number, from
 *                                   {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L831}. Label
 *                                   {@code 'SSN: First 3 chars'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L2439} names it, and the
 *                                   exclusion at {@code app/cbl/COACTUPC.cbl:L121-L123} fails
 *                                   000, 666 and 900 through 999
 * @param socialSecurityPart2        fourth and fifth digits of the Social Security Number, from
 *                                   {@code ACUP-NEW-CUST-SSN-2 PIC X(02)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L832}. Label
 *                                   {@code 'SSN 4th & 5th chars'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L2469} names it, and the numeric
 *                                   edit at {@code app/cbl/COACTUPC.cbl:L2472} is the only test
 *                                   the program applies
 * @param socialSecurityPart3        last four digits of the Social Security Number, from
 *                                   {@code ACUP-NEW-CUST-SSN-3 PIC X(04)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L833}. Label
 *                                   {@code 'SSN Last 4 chars'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L2481} names it, and the numeric
 *                                   edit at {@code app/cbl/COACTUPC.cbl:L2484} is the only test
 *                                   the program applies
 * @param governmentIssuedId         government-issued identifier of the customer, from
 *                                   {@code ACUP-NEW-CUST-GOVT-ISSUED-ID PIC X(20)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L836}. No edit reads the value
 *                                   and the program gives it no label
 * @param dateOfBirth                date of birth as eight digits in year, month and day order
 *                                   with no separator, from
 *                                   {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD PIC X(08)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L837} with the parts at
 *                                   {@code app/cbl/COACTUPC.cbl:L838-L842}. Label
 *                                   {@code 'Date of Birth'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1533} names it and the mask
 *                                   {@code 'YYYYMMDD'} at {@code app/cpy/CSUTLDPY.cpy:L291}
 *                                   fixes the shape, so the ten-character separated form the
 *                                   stored column holds fails the width. The calendar check
 *                                   gates the reasonableness check at
 *                                   {@code app/cbl/COACTUPC.cbl:L1539-L1541}, and the
 *                                   strictly-greater test at {@code app/cpy/CSUTLDPY.cpy:L350}
 *                                   fails today's own date and every later date with
 *                                   {@code ':cannot be in the future '} at
 *                                   {@code app/cpy/CSUTLDPY.cpy:L362-L363}
 * @param eftAccountId               electronic funds transfer account identifier, from
 *                                   {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID PIC X(10)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L843}. Label
 *                                   {@code 'EFT Account Id'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1648} names it, edited as ten
 *                                   required numeric characters
 * @param primaryCardHolderIndicator flag marking the customer as the primary holder, from
 *                                   {@code ACUP-NEW-CUST-PRI-HOLDER-IND PIC X(01)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L844}. Label
 *                                   {@code 'Primary Card Holder'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1657} names it, and
 *                                   {@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L78} lists the two passing
 *                                   values
 * @param ficoCreditScore            FICO credit score as three digits, from
 *                                   {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L845} with the
 *                                   {@code PIC 9(03)} redefine at
 *                                   {@code app/cbl/COACTUPC.cbl:L846-L847}. Label
 *                                   {@code 'FICO Score'} at
 *                                   {@code app/cbl/COACTUPC.cbl:L1545} names it, and condition
 *                                   {@code FICO-RANGE-IS-VALID} at
 *                                   {@code app/cbl/COACTUPC.cbl:L848-L849} accepts 300 through
 *                                   850 inclusive
 */
public record CustomerDataRequest(

        @Size(max = CUSTOMER_ID_MAX_LENGTH)
        String customerId,

        @Size(max = FIRST_NAME_MAX_LENGTH)
        String firstName,

        @Size(max = MIDDLE_NAME_MAX_LENGTH)
        String middleName,

        @Size(max = LAST_NAME_MAX_LENGTH)
        String lastName,

        @NotBlank(message = ADDRESS_LINE_1_REQUIRED_MESSAGE)
        @Size(max = ADDRESS_LINE_1_MAX_LENGTH)
        String addressLine1,

        String addressLine2,

        @Size(max = ADDRESS_CITY_MAX_LENGTH)
        String addressCity,

        @Size(max = ADDRESS_STATE_CODE_MAX_LENGTH)
        String addressStateCode,

        @Size(max = ADDRESS_COUNTRY_CODE_MAX_LENGTH)
        String addressCountryCode,

        @Size(max = ADDRESS_ZIP_MAX_LENGTH)
        String addressZip,

        @Size(max = PHONE_NUMBER_MAX_LENGTH)
        String phoneNumber1,

        @Size(max = PHONE_NUMBER_MAX_LENGTH)
        String phoneNumber2,

        @Size(max = SOCIAL_SECURITY_PART_1_MAX_LENGTH)
        String socialSecurityPart1,

        @Size(max = SOCIAL_SECURITY_PART_2_MAX_LENGTH)
        String socialSecurityPart2,

        @Size(max = SOCIAL_SECURITY_PART_3_MAX_LENGTH)
        String socialSecurityPart3,

        @Size(max = GOVERNMENT_ISSUED_ID_MAX_LENGTH)
        String governmentIssuedId,

        @Size(max = DATE_OF_BIRTH_MAX_LENGTH)
        String dateOfBirth,

        @Size(max = EFT_ACCOUNT_ID_MAX_LENGTH)
        String eftAccountId,

        @Size(max = PRIMARY_CARD_HOLDER_INDICATOR_MAX_LENGTH)
        String primaryCardHolderIndicator,

        @Size(max = FICO_CREDIT_SCORE_MAX_LENGTH)
        @DecimalMin(value = LOWEST_PASSING_CREDIT_SCORE, message = FICO_RANGE_MESSAGE)
        @DecimalMax(value = HIGHEST_PASSING_CREDIT_SCORE, message = FICO_RANGE_MESSAGE)
        String ficoCreditScore) {

    /**
     * Widest {@code customerId} this record holds, from
     * {@code ACUP-NEW-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTUPC.cbl:L798}.
     */
    public static final int CUSTOMER_ID_MAX_LENGTH = 9;

    /**
     * Widest {@code firstName} this record holds, from
     * {@code ACUP-NEW-CUST-FIRST-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L801}. The
     * account update program passes the same 25 at {@code app/cbl/COACTUPC.cbl:L1562}.
     */
    public static final int FIRST_NAME_MAX_LENGTH = 25;

    /**
     * Widest {@code middleName} this record holds, from
     * {@code ACUP-NEW-CUST-MIDDLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L802}. The
     * account update program passes the same 25 at {@code app/cbl/COACTUPC.cbl:L1570}.
     */
    public static final int MIDDLE_NAME_MAX_LENGTH = 25;

    /**
     * Widest {@code lastName} this record holds, from
     * {@code ACUP-NEW-CUST-LAST-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L803}. The
     * account update program passes the same 25 at {@code app/cbl/COACTUPC.cbl:L1578}.
     */
    public static final int LAST_NAME_MAX_LENGTH = 25;

    /**
     * Widest {@code addressLine1} this record holds, from
     * {@code ACUP-NEW-CUST-ADDR-LINE-1 PIC X(50)} at {@code app/cbl/COACTUPC.cbl:L804}. The
     * account update program passes the same 50 at {@code app/cbl/COACTUPC.cbl:L1586}.
     */
    public static final int ADDRESS_LINE_1_MAX_LENGTH = 50;

    /**
     * Width that {@code ACUP-NEW-CUST-ADDR-LINE-2 PIC X(50)} declares at
     * {@code app/cbl/COACTUPC.cbl:L805}. No constraint on {@code addressLine2} reads this value,
     * and a mapper writing the stored column does.
     */
    public static final int ADDRESS_LINE_2_MAX_LENGTH = 50;

    /**
     * Widest {@code addressCity} this record holds, from
     * {@code ACUP-NEW-CUST-ADDR-LINE-3 PIC X(50)} at {@code app/cbl/COACTUPC.cbl:L806}. The
     * account update program passes the same 50 at {@code app/cbl/COACTUPC.cbl:L1617}.
     */
    public static final int ADDRESS_CITY_MAX_LENGTH = 50;

    /**
     * Widest {@code addressStateCode} this record holds, from
     * {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} at {@code app/cbl/COACTUPC.cbl:L807}. The
     * account update program passes the same 2 at {@code app/cbl/COACTUPC.cbl:L1594}.
     */
    public static final int ADDRESS_STATE_CODE_MAX_LENGTH = 2;

    /**
     * Widest {@code addressCountryCode} this record holds, from
     * {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L808}. The
     * account update program passes the same 3 at {@code app/cbl/COACTUPC.cbl:L1626}.
     */
    public static final int ADDRESS_COUNTRY_CODE_MAX_LENGTH = 3;

    /**
     * Widest {@code addressZip} this record holds. {@code app/cbl/COACTUPC.cbl:L1607} passes 5 to
     * the numeric edit, while {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at
     * {@code app/cbl/COACTUPC.cbl:L809} and {@code CUST-ADDR-ZIP PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L14} both declare ten characters. This constant holds the
     * edited width of 5.
     */
    public static final int ADDRESS_ZIP_MAX_LENGTH = 5;

    /**
     * Widest telephone number either phone component holds, from
     * {@code ACUP-NEW-CUST-PHONE-NUM-1 PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L810} and
     * {@code ACUP-NEW-CUST-PHONE-NUM-2 PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L820}. The
     * fifteen characters carry a one-character separator, three digits, a one-character
     * separator, three digits, a one-character separator, four digits and two trailing spaces.
     * The redefine at {@code app/cbl/COACTUPC.cbl:L811-L819} lays out that order.
     */
    public static final int PHONE_NUMBER_MAX_LENGTH = 15;

    /**
     * Widest {@code socialSecurityPart1} this record holds, from
     * {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L831}. Paragraph
     * {@code 1265-EDIT-US-SSN} passes the same 3 at {@code app/cbl/COACTUPC.cbl:L2441}.
     */
    public static final int SOCIAL_SECURITY_PART_1_MAX_LENGTH = 3;

    /**
     * Widest {@code socialSecurityPart2} this record holds, from
     * {@code ACUP-NEW-CUST-SSN-2 PIC X(02)} at {@code app/cbl/COACTUPC.cbl:L832}. Paragraph
     * {@code 1265-EDIT-US-SSN} passes the same 2 at {@code app/cbl/COACTUPC.cbl:L2471}.
     */
    public static final int SOCIAL_SECURITY_PART_2_MAX_LENGTH = 2;

    /**
     * Widest {@code socialSecurityPart3} this record holds, from
     * {@code ACUP-NEW-CUST-SSN-3 PIC X(04)} at {@code app/cbl/COACTUPC.cbl:L833}. Paragraph
     * {@code 1265-EDIT-US-SSN} passes the same 4 at {@code app/cbl/COACTUPC.cbl:L2483}.
     */
    public static final int SOCIAL_SECURITY_PART_3_MAX_LENGTH = 4;

    /**
     * Widest {@code governmentIssuedId} this record holds, from
     * {@code ACUP-NEW-CUST-GOVT-ISSUED-ID PIC X(20)} at {@code app/cbl/COACTUPC.cbl:L836}.
     */
    public static final int GOVERNMENT_ISSUED_ID_MAX_LENGTH = 20;

    /**
     * Widest {@code dateOfBirth} this record holds, from
     * {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:L837}. The
     * eight characters carry four year digits, two month digits and two day digits, which the
     * redefine at {@code app/cbl/COACTUPC.cbl:L838-L842} lays out and the mask
     * {@code 'YYYYMMDD'} at {@code app/cpy/CSUTLDPY.cpy:L291} names. The stored column
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19} holds two
     * separators and ten characters.
     */
    public static final int DATE_OF_BIRTH_MAX_LENGTH = 8;

    /**
     * Widest {@code eftAccountId} this record holds, from
     * {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code app/cbl/COACTUPC.cbl:L843}. The
     * account update program passes the same 10 at {@code app/cbl/COACTUPC.cbl:L1651}.
     */
    public static final int EFT_ACCOUNT_ID_MAX_LENGTH = 10;

    /**
     * Widest {@code primaryCardHolderIndicator} this record holds, from
     * {@code ACUP-NEW-CUST-PRI-HOLDER-IND PIC X(01)} at {@code app/cbl/COACTUPC.cbl:L844}.
     */
    public static final int PRIMARY_CARD_HOLDER_INDICATOR_MAX_LENGTH = 1;

    /**
     * Widest {@code ficoCreditScore} this record holds, from
     * {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L845}. The
     * account update program passes the same 3 at {@code app/cbl/COACTUPC.cbl:L1548}.
     */
    public static final int FICO_CREDIT_SCORE_MAX_LENGTH = 3;

    /**
     * Lowest FICO credit score that passes, held as text for the range constraint. Condition
     * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code app/cbl/COACTUPC.cbl:L848-L849} names 300 as the first passing value.
     */
    public static final String LOWEST_PASSING_CREDIT_SCORE = "300";

    /**
     * Highest FICO credit score that passes, held as text for the range constraint. The same
     * condition at {@code app/cbl/COACTUPC.cbl:L848-L849} names 850 as the last passing value.
     */
    public static final String HIGHEST_PASSING_CREDIT_SCORE = "850";

    /**
     * Message a caller reads when {@code addressLine1} carries no value. Paragraph
     * {@code 1215-EDIT-MANDATORY} composes the text from
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} and the literal
     * {@code ' must be supplied.'} at {@code app/cbl/COACTUPC.cbl:L1840-L1841}, and the label
     * {@code 'Address Line 1'} arrives at {@code app/cbl/COACTUPC.cbl:L1584}.
     */
    public static final String ADDRESS_LINE_1_REQUIRED_MESSAGE = "Address Line 1 must be supplied.";

    /**
     * Message a caller reads when {@code ficoCreditScore} falls outside 300 through 850.
     * Paragraph {@code 1275-EDIT-FICO-SCORE} composes the text from
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} and the literal
     * {@code ': should be between 300 and 850'} at {@code app/cbl/COACTUPC.cbl:L2522-L2523}, and
     * the label {@code 'FICO Score'} arrives at {@code app/cbl/COACTUPC.cbl:L1545}.
     */
    public static final String FICO_RANGE_MESSAGE = "FICO Score: should be between 300 and 850";
}

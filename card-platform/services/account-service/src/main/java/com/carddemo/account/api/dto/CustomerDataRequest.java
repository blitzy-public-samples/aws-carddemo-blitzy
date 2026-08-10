package com.carddemo.account.api.dto;

import com.carddemo.account.domain.validation.DomainEdit;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.domain.validation.UsPhoneNumberValidator;
import com.carddemo.account.domain.validation.UsSocialSecurityNumberValidator;
import com.carddemo.account.domain.validation.UsStateCodeValidator;
import com.carddemo.account.domain.validation.UsStateZipPrefixValidator;
import com.carddemo.cobol.PicClause;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Customer detail a caller supplies when updating a customer.
 *
 * <p>Transformed component by component from the group {@code 10 ACUP-NEW-CUST-DATA.} at
 * {@code app/cbl/COACTUPC.cbl:L797}, whose twenty members end at {@code app/cbl/COACTUPC.cbl:L845}.
 * Component order follows member order.</p>
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
 * <p>The label {@code 'SSN'} at {@code app/cbl/COACTUPC.cbl:L1529} reaches no message. The comments
 * at {@code app/cbl/COACTUPC.cbl:L2433-L2435} name digit ranges for parts two and three that no
 * statement applies. {@code app/cbl/COACTUPC.cbl:L1607} edits five zip characters while
 * {@code app/cbl/COACTUPC.cbl:L809} declares ten.</p>
 *
 * <p>The three Social Security Number parts and {@code governmentIssuedId} arrive here and travel
 * no further. No response body, no published event, no validation-failure message and no error
 * payload carries any of those four values. Every failure message names a field label and carries
 * no character of the value, matching the messages at
 * {@code app/cbl/COACTUPC.cbl:L2431-L2487}.</p>
 *
 * <p>{@link #toString()} names no component value, so a logged instance carries nothing
 * sensitive. {@code CustomerDataRequestTest} asserts that.</p>
 *
 * @param customerId                 customer identifier, from
 *                                   {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 *                                   {@code app/cbl/COACTUPC.cbl:L798}, redefined as
 *                                   {@code PIC 9(09)} at {@code app/cbl/COACTUPC.cbl:L799-L800}.
 *                                   Nine digits, zero padded on the left, bounded by
 *                                   {@link #CUSTOMER_ID_PATTERN}.
 *                                   {@code app/cbl/COACTUPC.cbl:L1222} applies no edit to it, so
 *                                   {@code api/AccountController.updateAccount} is what refuses a
 *                                   value of another shape
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
 *                                   characters, the width
 *                                   {@link #ADDRESS_ZIP_EDITED_LENGTH} holds. The component itself
 *                                   carries the ten characters
 *                                   {@link #ADDRESS_ZIP_MAX_LENGTH} holds, so a stored ZIP+4 value
 *                                   round-trips and positions six to ten are never edited.
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
        @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
        String customerId,

        @Size(max = FIRST_NAME_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.ALPHABETIC_REQUIRED, label = FIRST_NAME_LABEL,
                width = FIRST_NAME_MAX_LENGTH)
        String firstName,

        @Size(max = MIDDLE_NAME_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.ALPHABETIC_OPTIONAL, label = MIDDLE_NAME_LABEL,
                width = MIDDLE_NAME_MAX_LENGTH)
        String middleName,

        @Size(max = LAST_NAME_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.ALPHABETIC_REQUIRED, label = LAST_NAME_LABEL,
                width = LAST_NAME_MAX_LENGTH)
        String lastName,

        @NotBlank(message = ADDRESS_LINE_1_REQUIRED_MESSAGE)
        @Size(max = ADDRESS_LINE_1_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        @DomainEdit(value = DomainEdit.Edit.MANDATORY, label = ADDRESS_LINE_1_LABEL,
                width = ADDRESS_LINE_1_MAX_LENGTH)
        String addressLine1,

        @Size(max = ADDRESS_LINE_2_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String addressLine2,

        @Size(max = ADDRESS_CITY_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.ALPHABETIC_REQUIRED, label = ADDRESS_CITY_LABEL,
                width = ADDRESS_CITY_MAX_LENGTH)
        String addressCity,

        @Size(max = ADDRESS_STATE_CODE_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.ALPHABETIC_REQUIRED, label = ADDRESS_STATE_CODE_LABEL,
                width = ADDRESS_STATE_CODE_MAX_LENGTH)
        String addressStateCode,

        @Size(max = ADDRESS_COUNTRY_CODE_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.ALPHABETIC_REQUIRED,
                label = ADDRESS_COUNTRY_CODE_LABEL, width = ADDRESS_COUNTRY_CODE_MAX_LENGTH)
        String addressCountryCode,

        @Size(max = ADDRESS_ZIP_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.NUMERIC_REQUIRED, label = ADDRESS_ZIP_LABEL,
                width = ADDRESS_ZIP_EDITED_LENGTH, heldWidth = ADDRESS_ZIP_MAX_LENGTH)
        String addressZip,

        @Size(max = PHONE_NUMBER_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String phoneNumber1,

        @Size(max = PHONE_NUMBER_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String phoneNumber2,

        @Size(max = SOCIAL_SECURITY_PART_1_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String socialSecurityPart1,

        @Size(max = SOCIAL_SECURITY_PART_2_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String socialSecurityPart2,

        @Size(max = SOCIAL_SECURITY_PART_3_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String socialSecurityPart3,

        @Size(max = GOVERNMENT_ISSUED_ID_MAX_LENGTH)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String governmentIssuedId,

        @Size(max = DATE_OF_BIRTH_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.DATE_OF_BIRTH, label = DATE_OF_BIRTH_LABEL)
        String dateOfBirth,

        @Size(max = EFT_ACCOUNT_ID_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.NUMERIC_REQUIRED, label = EFT_ACCOUNT_ID_LABEL,
                width = EFT_ACCOUNT_ID_MAX_LENGTH)
        String eftAccountId,

        @Size(max = PRIMARY_CARD_HOLDER_INDICATOR_MAX_LENGTH)
        @DomainEdit(value = DomainEdit.Edit.YES_NO_FLAG,
                label = PRIMARY_CARD_HOLDER_INDICATOR_LABEL)
        String primaryCardHolderIndicator,

        @Size(max = FICO_CREDIT_SCORE_MAX_LENGTH)
        @DecimalMin(value = LOWEST_PASSING_CREDIT_SCORE, message = FICO_RANGE_MESSAGE)
        @DecimalMax(value = HIGHEST_PASSING_CREDIT_SCORE, message = FICO_RANGE_MESSAGE)
        @DomainEdit(value = DomainEdit.Edit.CREDIT_SCORE_RANGE, label = FICO_CREDIT_SCORE_LABEL)
        String ficoCreditScore) {

    /**
     * Widest {@code customerId} this record holds, from
     * {@code ACUP-NEW-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTUPC.cbl:L798}.
     */
    public static final int CUSTOMER_ID_MAX_LENGTH = 9;

    /**
     * The width and the alphabet of {@code customerId}.
     *
     * <p>{@code ACUP-NEW-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTUPC.cbl:L798} is redefined as
     * {@code ACUP-NEW-CUST-ID PIC 9(09)} at {@code app/cbl/COACTUPC.cbl:L799-L800}, and
     * {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} is the column it keys. Nine
     * digits, and the leading zeros belong to the value, so customer one is {@code 000000001} and
     * not {@code 1}. Table {@code customer} carries the same rule as
     * {@code ck_customer_customer_id_digits} in
     * {@code src/main/resources/db/migration/V1__schema.sql}.
     *
     * <p>{@code api/CustomerController} applies this pattern to the path variable of
     * {@code GET /customers/{customerId}} and {@code api/AccountController.updateAccount} applies
     * it to the value the body carries.
     */
    public static final String CUSTOMER_ID_PATTERN = "^[0-9]{9}$";

    /**
     * Message a caller reads when {@code customerId} is not nine digits.
     *
     * <p>ADDITIVE. {@code app/cbl/COACTUPC.cbl:L1222} calls the field 'actually not editable' and
     * the program applies no edit to it, so the source emits no text for it. This text follows the
     * spelling of the account identifier text at {@code api/AccountController.ACCOUNT_ID_MESSAGE}.
     */
    public static final String CUSTOMER_ID_MESSAGE =
            "Customer Id must be a 9 digit Number, zero padded on the left";

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
     * Widest {@code addressLine2} this record holds, from
     * {@code ACUP-NEW-CUST-ADDR-LINE-2 PIC X(50)} at {@code app/cbl/COACTUPC.cbl:L805}. The stored
     * column {@code address_line_2 VARCHAR(50)} holds the same 50 at
     * {@code src/main/resources/db/migration/V1__schema.sql:L39}, so a longer value would reach the
     * database and fail there instead of being reported to the caller.
     *
     * <p>The field carries a bound and no emptiness test. Only address lines 1 and 3 are validated
     * at {@code app/cbl/COACTUPC.cbl:L1585} and {@code app/cbl/COACTUPC.cbl:L1616}; line 2 is
     * validated nowhere, so the source accepts it empty and this record does too.
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
     * Widest {@code addressZip} this record holds, which is the width the source field declares:
     * {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at {@code app/cbl/COACTUPC.cbl:L809} and
     * {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14}. The column
     * {@code customer.address_zip} is {@code VARCHAR(10)} to match, and
     * {@code api/AccountRecordMapper} pads a shorter value to that width before storing it.
     *
     * <p>The width the edit inspects is narrower and is {@link #ADDRESS_ZIP_EDITED_LENGTH}. The two
     * are separate constants because the source separates them: it moves the whole ten-character
     * field into the edit area and then confines every test to the first five positions.
     */
    public static final int ADDRESS_ZIP_MAX_LENGTH = PicClause.CUST_ADDR_ZIP_WIDTH;

    /**
     * Characters of {@code addressZip} the numeric edit inspects.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1607} moves 5 into {@code WS-EDIT-ALPHANUM-LENGTH}, and every
     * test of {@code 1245-EDIT-NUM-REQD} reads
     * {@code WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH)}. Positions six to ten are carried,
     * stored and returned, and are never inspected, so a stored ZIP+4 such as {@code 19852-6716}
     * passes the source edit on {@code 19852}.
     *
     * <p>Thirty of the fifty seeded customers hold a ZIP+4 value and the other twenty hold a
     * five-digit value padded to ten characters. Editing the full width refused every one of them,
     * so a value this service had returned from {@code GET} could not be sent back to {@code PUT}.
     */
    public static final int ADDRESS_ZIP_EDITED_LENGTH = 5;

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

    // The eleven field labels app/cbl/COACTUPC.cbl moves into WS-EDIT-VARIABLE-NAME before it runs
    // an edit. Each message a caller reads is that label joined to a reason, so the label is part
    // of the contract text and is reproduced character for character.

    /** Label at {@code app/cbl/COACTUPC.cbl:L1560}. */
    public static final String FIRST_NAME_LABEL = "First Name";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1568}. */
    public static final String MIDDLE_NAME_LABEL = "Middle Name";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1576}. */
    public static final String LAST_NAME_LABEL = "Last Name";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1584}. */
    public static final String ADDRESS_LINE_1_LABEL = "Address Line 1";

    /**
     * Label at {@code app/cbl/COACTUPC.cbl:L1615}.
     *
     * <p>The label reads {@code City} while the field it edits is
     * {@code ACUP-NEW-CUST-ADDR-LINE-3}, which is the third address line. The source names the
     * third line the city, and this component follows that naming.
     */
    public static final String ADDRESS_CITY_LABEL = "City";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1592}. */
    public static final String ADDRESS_STATE_CODE_LABEL = "State";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1623}. */
    public static final String ADDRESS_COUNTRY_CODE_LABEL = "Country";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1605}. */
    public static final String ADDRESS_ZIP_LABEL = "Zip";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1632}. */
    public static final String PHONE_NUMBER_1_LABEL = "Phone Number 1";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1640}. */
    public static final String PHONE_NUMBER_2_LABEL = "Phone Number 2";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1648}. */
    public static final String EFT_ACCOUNT_ID_LABEL = "EFT Account Id";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1657}. */
    public static final String PRIMARY_CARD_HOLDER_INDICATOR_LABEL = "Primary Card Holder";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1533}. */
    public static final String DATE_OF_BIRTH_LABEL = "Date of Birth";

    /** Label at {@code app/cbl/COACTUPC.cbl:L1545}. */
    public static final String FICO_CREDIT_SCORE_LABEL = "FICO Score";

    /**
     * The characters a free-text component may hold: printable ones and nothing else.
     *
     * <p>The range runs from the space at {@code 0x20} to the tilde at {@code 0x7E}, so every C0
     * control character is outside it, carriage return and line feed included. Every character of
     * all fifty records of {@code app/data/ASCII/custdata.txt} falls inside it.
     *
     * <p>The guard is declared only on the components whose edit does not already exclude a control
     * character. An alphabetic edit accepts letters and spaces, and a numeric edit accepts digits,
     * so a control character fails those edits already. The address lines, the telephone numbers,
     * the three parts of the social security number and the government identifier take no such
     * edit in the source. This guard is what bounds their character set.
     *
     * <p>No COBOL ancestor. The source reads each field from a fixed-width map area, which no
     * control character can reach, so it needs no equivalent.
     */
    public static final String PRINTABLE_TEXT_PATTERN = "^[ -~]*$";

    /**
     * Message a caller reads when a component carries a character outside
     * {@value #PRINTABLE_TEXT_PATTERN}. No source counterpart, for the reason that pattern records.
     */
    public static final String CONTROL_CHARACTER_MESSAGE =
            "Text fields must hold printable characters only.";

    /**
     * Position of the first digit of the area code inside a stored telephone number.
     *
     * <p>{@code WS-EDIT-US-PHONE-NUM-X} redefines the fifteen-character field at
     * {@code app/cbl/COACTUPC.cbl:L83-L96}. One filler character holds {@code (}, then three
     * characters of area code, a filler holding {@code )}, three characters of prefix, a filler
     * holding {@code -}, then the line number. All fifty records of
     * {@code app/data/ASCII/custdata.txt} carry that shape.
     */
    private static final int PHONE_AREA_CODE_START = 1;

    /** Position after the last digit of the area code, from the same redefinition. */
    private static final int PHONE_AREA_CODE_END = 4;

    /** Position of the first digit of the prefix, from the same redefinition. */
    private static final int PHONE_PREFIX_START = 5;

    /** Position after the last digit of the prefix, from the same redefinition. */
    private static final int PHONE_PREFIX_END = 8;

    /** Position of the first digit of the line number, from the same redefinition. */
    private static final int PHONE_LINE_NUMBER_START = 9;

    /** Position after the last digit of the line number, from the same redefinition. */
    private static final int PHONE_LINE_NUMBER_END = 13;

    /** Stands in for a component that arrived, in the form {@link #toString()} returns. */
    public static final String WITHHELD = "<withheld>";

    /** Stands in for a component that did not arrive, in the form {@link #toString()} returns. */
    public static final String ABSENT = "<absent>";

    /**
     * Reports whether the state code names one of the fifty-six codes the source lists.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1599-L1602} runs this table lookup only when the alphabetic
     * edit of the same field passed. This method therefore answers {@code true} while that edit is
     * failing, and the alphabetic message reaches the caller on its own. The gate is the source's,
     * not a convenience: two messages for one field is not what the source reports.
     *
     * @return {@code true} when the code is listed, or when the alphabetic edit has not passed
     */
    @AssertTrue
    public boolean isAddressStateCodeListed() {
        if (!alphabeticEditPassed(addressStateCode, ADDRESS_STATE_CODE_LABEL,
                ADDRESS_STATE_CODE_MAX_LENGTH)) {
            return true;
        }
        return UsStateCodeValidator.validate(ADDRESS_STATE_CODE_LABEL, addressStateCode).valid();
    }

    /**
     * Reports whether the state code and the postal code form one of the listed combinations.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1665-L1669} runs this cross-field edit only when the state
     * edit and the postal-code edit have both passed, and this method reproduces that gate. The
     * combination list holds two hundred and forty entries, from
     * {@code app/cpy/CSLKPCDY.cpy:L1071-L1073} onward.
     *
     * @return {@code true} when the pair is listed, or when either field is still failing its own
     *         edit
     */
    @AssertTrue
    public boolean isAddressStateZipCombinationListed() {
        boolean stateReady = alphabeticEditPassed(addressStateCode, ADDRESS_STATE_CODE_LABEL,
                        ADDRESS_STATE_CODE_MAX_LENGTH)
                && UsStateCodeValidator
                        .validate(ADDRESS_STATE_CODE_LABEL, addressStateCode).valid();
        boolean zipReady = numericEditPassed(addressZip, ADDRESS_ZIP_LABEL,
                ADDRESS_ZIP_EDITED_LENGTH, ADDRESS_ZIP_MAX_LENGTH);
        if (!stateReady || !zipReady) {
            return true;
        }
        return UsStateZipPrefixValidator.validate(addressStateCode, addressZip).valid();
    }

    /**
     * Reports whether the three parts of the social security number pass their edit together.
     *
     * <p>Paragraph {@code 1265-EDIT-US-SSN} at {@code app/cbl/COACTUPC.cbl:L2431} edits all three
     * parts on one pass. It labels each part on its own at {@code app/cbl/COACTUPC.cbl:L2439},
     * {@code app/cbl/COACTUPC.cbl:L2469} and {@code app/cbl/COACTUPC.cbl:L2481}, so the edit
     * cannot be declared on any one component.
     *
     * @return {@code true} when the three parts pass, and when none of them was supplied
     */
    @AssertTrue
    public boolean isSocialSecurityNumberValid() {
        return UsSocialSecurityNumberValidator.validate(socialSecurityPart1, socialSecurityPart2,
                socialSecurityPart3).valid();
    }

    /**
     * Reports whether the first telephone number passes the source's edit.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1632-L1637} moves the whole fifteen-character field and lets
     * the redefinition at {@code app/cbl/COACTUPC.cbl:L83-L96} split it. The three parts are
     * therefore sliced here at the same offsets, rather than carried as three components.
     *
     * @return {@code true} when the number passes, and when it was not supplied
     */
    @AssertTrue
    public boolean isPhoneNumber1Valid() {
        return phoneNumberValid(phoneNumber1, PHONE_NUMBER_1_LABEL);
    }

    /**
     * Reports whether the second telephone number passes the source's edit.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1640-L1645} runs the same paragraph over the second field.
     *
     * @return {@code true} when the number passes, and when it was not supplied
     */
    @AssertTrue
    public boolean isPhoneNumber2Valid() {
        return phoneNumberValid(phoneNumber2, PHONE_NUMBER_2_LABEL);
    }

    /**
     * Slices a stored telephone number and runs the source's edit over its three parts.
     *
     * @param phoneNumber the fifteen-character stored form, which may be {@code null}
     * @param label       the field label the message carries
     * @return {@code true} when the edit passes, and when the field was not supplied
     */
    private static boolean phoneNumberValid(String phoneNumber, String label) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return true;
        }
        if (phoneNumber.length() < PHONE_LINE_NUMBER_END) {
            // Too short to hold the redefinition's parts. The edit refuses it, and it refuses it
            // for the same reason the source would: the parts it reads are not there.
            return false;
        }
        EditResult outcome = UsPhoneNumberValidator.validate(label,
                phoneNumber.substring(PHONE_AREA_CODE_START, PHONE_AREA_CODE_END),
                phoneNumber.substring(PHONE_PREFIX_START, PHONE_PREFIX_END),
                phoneNumber.substring(PHONE_LINE_NUMBER_START, PHONE_LINE_NUMBER_END));
        return outcome.valid();
    }

    /**
     * Reports whether one component passes the alphabetic edit, without reporting a message.
     *
     * @param value the component value
     * @param label the field label
     * @param width the width the edit inspects
     * @return {@code true} when the edit passes
     */
    private static boolean alphabeticEditPassed(String value, String label, int width) {
        return com.carddemo.account.domain.validation.AlphabeticRequiredValidator
                .validate(label, value, width).valid();
    }

    /**
     * Reports whether one component passes the numeric edit, without reporting a message.
     *
     * @param value the component value
     * @param label the field label
     * @param width the width the edit inspects
     * @return {@code true} when the edit passes
     */
    private static boolean numericEditPassed(String value, String label, int width) {
        return numericEditPassed(value, label, width, width);
    }

    /**
     * Reports whether the numeric edit passes for a field whose held width exceeds its edited width.
     *
     * @param value      the submitted value; may be {@code null}
     * @param label      the field label the message opens with
     * @param width      the characters the edit inspects
     * @param heldWidth  the characters the source field declares
     * @return {@code true} when the edit passes
     */
    private static boolean numericEditPassed(String value, String label, int width, int heldWidth) {
        return com.carddemo.account.domain.validation.NumericRequiredValidator
                .validate(label, value, width, heldWidth).valid();
    }

    /**
     * Renders this request with every customer value withheld.
     *
     * <p>No component value appears. Each is named and reported as {@value #WITHHELD} or
     * {@value #ABSENT}, so a reader can tell which values arrived without reading one of them. The
     * customer identifier is withheld on the same terms as the rest, because it identifies the
     * person the remaining components describe.
     *
     * @return the withheld form of this request, never {@code null}
     */
    @Override
    public String toString() {
        return "CustomerDataRequest[customerId=" + present(customerId)
                + ", firstName=" + present(firstName)
                + ", middleName=" + present(middleName)
                + ", lastName=" + present(lastName)
                + ", addressLine1=" + present(addressLine1)
                + ", addressLine2=" + present(addressLine2)
                + ", addressCity=" + present(addressCity)
                + ", addressStateCode=" + present(addressStateCode)
                + ", addressCountryCode=" + present(addressCountryCode)
                + ", addressZip=" + present(addressZip)
                + ", phoneNumber1=" + present(phoneNumber1)
                + ", phoneNumber2=" + present(phoneNumber2)
                + ", socialSecurityPart1=" + present(socialSecurityPart1)
                + ", socialSecurityPart2=" + present(socialSecurityPart2)
                + ", socialSecurityPart3=" + present(socialSecurityPart3)
                + ", governmentIssuedId=" + present(governmentIssuedId)
                + ", dateOfBirth=" + present(dateOfBirth)
                + ", eftAccountId=" + present(eftAccountId)
                + ", primaryCardHolderIndicator=" + present(primaryCardHolderIndicator)
                + ", ficoCreditScore=" + present(ficoCreditScore) + "]";
    }

    /**
     * Reports whether a component arrived, without disclosing what it holds.
     *
     * @param value the component to describe; may be {@code null}
     * @return {@value #WITHHELD} when the component holds at least one character that is not
     *         whitespace, {@value #ABSENT} when it does not
     */
    private static String present(String value) {
        return value != null && !value.isBlank() ? WITHHELD : ABSENT;
    }
}

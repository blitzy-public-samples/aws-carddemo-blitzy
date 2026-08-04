package com.carddemo.account.api.dto;

import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads whether the field edits of {@code app/cbl/COACTUPC.cbl} actually run when a request is
 * validated, and whether any request or view renders a value.
 *
 * <p>ADDITIVE. This class has no COBOL ancestor. It exists because a validator nothing calls edits
 * nothing: the {@code domain/validation} package held faithful translations of paragraphs
 * {@code 1215-EDIT-MANDATORY} through {@code 1280-EDIT-US-STATE-ZIP-CD}, and the request records
 * declared width bounds alone, so every one of those edits was unreachable through Bean Validation.
 * The tests here assert the wiring rather than the rules; the rules themselves are asserted against
 * the validator classes directly by their own tests and by the equivalence module.
 *
 * <p>Each test runs one validator pass in memory. No test starts an application context, opens a
 * connection or contacts a broker.
 */
@DisplayName("request and view records: the edits run, and no rendering carries a value")
class DtoValidationWiringTest {

    /**
     * A customer request whose every component passes.
     *
     * <p>Both telephone numbers are the pair record 8 of {@code app/data/ASCII/custdata.txt}
     * carries. Record 8 rather than record 1, because record 1 holds {@code (373)693-8684} as
     * its second number and {@code app/cpy/CSLKPCDY.cpy} lists {@code 373} in neither of its
     * two area code tables.
     *
     * <p>Which table is read decides the outcome, so it is worth naming. The copybook holds two
     * bands: 410 general purpose codes at {@code app/cpy/CSLKPCDY.cpy:L521-L930}, and a separate
     * set of easily recognisable codes. {@code app/cbl/COACTUPC.cbl:L2298} tests
     * {@code VALID-GENERAL-PURP-CODE}, so an easily recognisable code such as {@code 744} is
     * listed in the copybook and still fails the edit. A value has to sit in the 410 code band.
     *
     * <p>Held to that band, only 12 of the 50 fixture records carry two numbers that both pass.
     * The fixture would therefore not survive the source's own telephone edit at
     * {@code app/cbl/COACTUPC.cbl:L1632-L1637}, and nothing in the source edits data on load: the
     * edits run on the update screen alone, so the fixture was never held to them. The
     * observation belongs to the data rather than to this record.
     */
    private static final CustomerDataRequest VALID_CUSTOMER = new CustomerDataRequest(
            "000000001", "Aaron", "A", "Aaronson", "500 Market Street", "Suite 200", "Springfield",
            "IL", "USA", "62704", "(345)563-7159", "(443)197-1271", "020", "97", "3888",
            "GOVID000000000000001", "19750304", "0000000001", "Y", "742");

    /** An account request whose every component passes. */
    private static final AccountDataRequest VALID_ACCOUNT = new AccountDataRequest(
            "Y", "1000.00", "5000.00", "1000.00", "20200101", "20301231", "20250101", "0.00",
            "0.00", "ZEROGROUP1");

    /** Built once for the whole class and closed after the last test. */
    private static ValidatorFactory validatorFactory;

    /** The validator every test below uses. */
    private static Validator validator;

    /** Opens the one validator this class shares. */
    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes it. */
    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /** The customer request reaches the edits its source block performs. */
    @Nested
    @DisplayName("CustomerDataRequest reaches its edits")
    class CustomerEdits {

        @Test
        @DisplayName("a request whose every component passes reports nothing")
        void aValidRequestReportsNothing() {
            assertTrue(validator.validate(VALID_CUSTOMER).isEmpty(),
                    () -> "the reference request must pass every edit: "
                            + messagesOf(validator.validate(VALID_CUSTOMER)));
        }

        @Test
        @DisplayName("a digit in a name reaches the alphabetic edit at L1560")
        void aDigitInANameReachesTheAlphabeticEdit() {
            Set<ConstraintViolation<CustomerDataRequest>> violations =
                    validator.validate(withFirstName("Aar0n"));

            assertFalse(violations.isEmpty(),
                    "app/cbl/COACTUPC.cbl:L1560-L1565 runs 1225-EDIT-ALPHA-REQD over this field, so"
                            + " a digit must be refused. Before the edits were attached, this"
                            + " request validated clean.");
            assertTrue(messagesOf(violations).contains(CustomerDataRequest.FIRST_NAME_LABEL),
                    () -> "the message must carry the label the source moves at L1560: "
                            + messagesOf(violations));
        }

        @Test
        @DisplayName("an unlisted state code reaches the table lookup at L1599")
        void anUnlistedStateCodeReachesTheTableLookup() {
            assertFalse(validator.validate(withStateCode("ZZ")).isEmpty(),
                    "app/cbl/COACTUPC.cbl:L1599-L1602 runs 1270-EDIT-US-STATE-CD over the"
                            + " fifty-six codes of app/cpy/CSLKPCDY.cpy, and ZZ is not one");
            assertTrue(validator.validate(withStateCode("IL")).isEmpty(),
                    () -> "a listed code must pass: "
                            + messagesOf(validator.validate(withStateCode("IL"))));
        }

        @Test
        @DisplayName("a non-alphabetic state code reports the alphabetic message alone")
        void aNonAlphabeticStateCodeReportsOneMessage() {
            Set<ConstraintViolation<CustomerDataRequest>> violations =
                    validator.validate(withStateCode("1L"));

            assertEquals(1, violations.size(),
                    () -> "app/cbl/COACTUPC.cbl:L1599 gates the table lookup on the alphabetic edit"
                            + " passing, so one field reports one message: "
                            + messagesOf(violations));
        }

        @Test
        @DisplayName("an unlisted state and postal code pair reaches the cross-field edit at L1665")
        void anUnlistedStateAndZipPairReachesTheCrossFieldEdit() {
            CustomerDataRequest mismatched = withStateZip("IL", "99999");

            assertFalse(validator.validate(mismatched).isEmpty(),
                    "app/cbl/COACTUPC.cbl:L1665-L1669 runs 1280-EDIT-US-STATE-ZIP-CD over the two"
                            + " hundred and forty combinations of app/cpy/CSLKPCDY.cpy");
        }

        @Test
        @DisplayName("a social security number outside the listed shape reaches the edit at L2431")
        void anInvalidSocialSecurityNumberReachesItsEdit() {
            assertFalse(validator.validate(withSocialSecurity("000", "97", "3888")).isEmpty(),
                    "app/cbl/COACTUPC.cbl:L2447-L2453 refuses an area of 000, and paragraph"
                            + " 1265-EDIT-US-SSN edits all three parts on one pass");
        }

        @Test
        @DisplayName("a telephone number outside the stored shape reaches the edit at L1632")
        void anInvalidTelephoneNumberReachesItsEdit() {
            assertFalse(validator.validate(withPhoneNumber1("(373)119-8310")).isEmpty(),
                    "the redefinition at app/cbl/COACTUPC.cbl:L83-L96 splits the field into an"
                            + " area code, a prefix and a line number, and 373 is absent from the"
                            + " 410 code general purpose band that app/cbl/COACTUPC.cbl:L2298"
                            + " tests");
            assertFalse(validator.validate(withPhoneNumber1("(744)119-8310")).isEmpty(),
                    "744 is listed in app/cpy/CSLKPCDY.cpy as an easily recognisable code and not"
                            + " in the general purpose band, and app/cbl/COACTUPC.cbl:L2298 tests"
                            + " the band alone, so being listed somewhere is not enough");
            assertFalse(validator.validate(withPhoneNumber1("9081198310")).isEmpty(),
                    "a value that does not carry the parentheses and hyphen of the stored form"
                            + " cannot be split at the offsets the source reads");
        }

        @Test
        @DisplayName("a credit score outside 300 through 850 reaches the range edit at L848")
        void aCreditScoreOutsideTheRangeReachesItsEdit() {
            assertFalse(validator.validate(withCreditScore("299")).isEmpty(),
                    "condition FICO-RANGE-IS-VALID at app/cbl/COACTUPC.cbl:L848-L849 accepts 300"
                            + " through 850 inclusive");
            assertTrue(validator.validate(withCreditScore("300")).isEmpty(),
                    () -> "the lower bound is inclusive: "
                            + messagesOf(validator.validate(withCreditScore("300"))));
        }

        @Test
        @DisplayName("the second address line is bounded, and its constant was already declared")
        void theSecondAddressLineIsBounded() {
            assertEquals(50, CustomerDataRequest.ADDRESS_LINE_2_MAX_LENGTH,
                    "ACUP-NEW-CUST-ADDR-LINE-2 PIC X(50) at app/cbl/COACTUPC.cbl:L807");
            assertFalse(validator.validate(withAddressLine2("x".repeat(51))).isEmpty(),
                    "the width constant was declared and never applied, so a caller could send an"
                            + " address line of any length at all. app/cbl/COACTUPC.cbl:L1614"
                            + " carries the label for this field commented out, which is why the"
                            + " source performs no edit on it and why the width bound here is"
                            + " additive.");
            assertTrue(validator.validate(withAddressLine2("x".repeat(50))).isEmpty(),
                    () -> "a value at the declared width must pass: "
                            + messagesOf(validator.validate(withAddressLine2("x".repeat(50)))));
        }

        @Test
        @DisplayName("a carriage return in an address line is refused")
        void aCarriageReturnInAnAddressLineIsRefused() {
            for (CustomerDataRequest request : List.of(
                    withAddressLine2("Suite\r\n200"),
                    withGovernmentIssuedId("GOV\r001"))) {
                Set<ConstraintViolation<CustomerDataRequest>> violations =
                        validator.validate(request);

                assertTrue(violations.stream().anyMatch(violation ->
                                CustomerDataRequest.CONTROL_CHARACTER_MESSAGE
                                        .equals(violation.getMessage())),
                        () -> "a control character must be refused: " + messagesOf(violations));
            }
        }
    }

    /** The account request reaches the edits its source block performs. */
    @Nested
    @DisplayName("AccountDataRequest reaches its edits")
    class AccountEdits {

        @Test
        @DisplayName("a request whose every component passes reports nothing")
        void aValidRequestReportsNothing() {
            assertTrue(validator.validate(VALID_ACCOUNT).isEmpty(),
                    () -> "the reference request must pass every edit: "
                            + messagesOf(validator.validate(VALID_ACCOUNT)));
        }

        @Test
        @DisplayName("a status outside Y and N reaches the flag edit at L1472")
        void aStatusOutsideYesAndNoReachesItsEdit() {
            assertFalse(validator.validate(withActiveStatus("X")).isEmpty(),
                    "88 FLG-YES-NO-VALID at app/cbl/COACTUPC.cbl:L91 lists Y and N, and"
                            + " app/cbl/COACTUPC.cbl:L1472-L1476 runs 1220-EDIT-YESNO over this"
                            + " field");
        }

        @Test
        @DisplayName("an amount that is not a signed decimal reaches the edit at L1484")
        void anAmountThatIsNotADecimalReachesItsEdit() {
            assertFalse(validator.validate(withCreditLimit("five thousand")).isEmpty(),
                    "app/cbl/COACTUPC.cbl:L1484-L1488 runs 1250-EDIT-SIGNED-9V2 over this field");
        }

        @Test
        @DisplayName("a date that is not a calendar date reaches the edit at L1478")
        void aDateThatIsNotACalendarDateReachesItsEdit() {
            assertFalse(validator.validate(withOpenDate("20201332")).isEmpty(),
                    "app/cbl/COACTUPC.cbl:L1478 labels the field Open Date and the date validator"
                            + " reached through CSUTLDTC refuses month 13");
        }
    }

    /** No request and no view renders a value. */
    @Nested
    @DisplayName("no rendering carries a value")
    class Redaction {

        @Test
        @DisplayName("the customer request names every component and renders no value")
        void theCustomerRequestRendersNoComponent() {
            String rendering = VALID_CUSTOMER.toString();

            for (String value : List.of("Aaron", "Aaronson", "500 Market Street", "020", "3888",
                    "GOVID000000000000001", "19750304", "0000000001", "742",
                    VALID_CUSTOMER.phoneNumber1(), VALID_CUSTOMER.phoneNumber2())) {
                assertFalse(rendering.contains(value),
                        () -> "the rendering carries " + value + ": " + rendering);
            }
            assertTrue(rendering.contains("customerId=" + CustomerDataRequest.WITHHELD)
                            && rendering.contains("ficoCreditScore=" + CustomerDataRequest.WITHHELD),
                    "each component is named and reported as withheld or absent, so a reader can "
                            + "tell which values arrived without reading one: " + rendering);
            assertFalse(rendering.contains(CustomerDataRequest.ABSENT),
                    "every component of the reference request arrived, so none reads as absent: "
                            + rendering);
        }

        @Test
        @DisplayName("the account request names every component and renders no money")
        void theAccountRequestRendersNoMoney() {
            String rendering = VALID_ACCOUNT.toString();

            for (String value : List.of("1000.00", "5000.00", "20301231", "ZEROGROUP1")) {
                assertFalse(rendering.contains(value),
                        () -> "the rendering carries " + value + ": " + rendering);
            }
            assertTrue(rendering.contains("creditLimit=" + EventEnvelope.WITHHELD)
                            && rendering.contains("groupId=" + EventEnvelope.WITHHELD),
                    "each component is named and its value withheld: " + rendering);
        }

        @Test
        @DisplayName("the customer view renders its identifier and nothing else")
        void theCustomerViewRendersOnlyItsIdentifier() {
            CustomerView view = new CustomerView("000000001", 742, "19750304", "Aaron", "A",
                    "Aaronson", "500 Market Street", "Suite 200", "Springfield", "IL", "62704",
                    "USA", "(908)119-8310", "(373)693-8684", "0000000001", "Y");
            String rendering = view.toString();

            assertFalse(rendering.contains("customerId=000000001"),
                    "the customer identifier identifies the person the remaining components "
                            + "describe, so it is withheld on the same terms: " + rendering);
            assertTrue(rendering.contains("customerId="),
                    "the rendering still names the component, so a reader knows which record this "
                            + "is a rendering of: " + rendering);
            for (String value : List.of("742", "19750304", "Aaron", "Aaronson",
                    "500 Market Street", "(908)119-8310", "0000000001")) {
                assertFalse(rendering.contains(value),
                        () -> "the rendering carries " + value + ": " + rendering);
            }
        }

        @Test
        @DisplayName("the account view renders its identifier and status and no money")
        void theAccountViewRendersNoMoney() {
            AccountView view = new AccountView("00000000001", "Y", new BigDecimal("1234.56"),
                    new BigDecimal("5000.00"), new BigDecimal("1000.00"), new BigDecimal("10.00"),
                    new BigDecimal("20.00"), "2020-01-01", "2030-12-31", "2025-01-01",
                    "ZEROGROUP1");
            String rendering = view.toString();

            assertFalse(rendering.contains("accountId=00000000001"),
                    "the account identifier is the key every event of this account carries: "
                            + rendering);
            assertTrue(rendering.contains("accountId=") && rendering.contains("activeStatus="),
                    "the rendering names every component it withholds: " + rendering);
            for (String value : List.of("1234.56", "5000.00", "1000.00", "10.00", "20.00",
                    "2030-12-31")) {
                assertFalse(rendering.contains(value),
                        () -> "the rendering carries " + value + ": " + rendering);
            }
        }

        @Test
        @DisplayName("the cycle close response names every component and renders no value")
        void theCycleCloseResponseRendersNoAccumulator() {
            String rendering = new CycleCloseResponse("00000000001", new BigDecimal("1234.56"),
                    new BigDecimal("7654.32")).toString();

            for (String value : List.of("00000000001", "1234.56", "7654.32")) {
                assertFalse(rendering.contains(value),
                        () -> "the rendering carries " + value + ": " + rendering);
            }
            assertTrue(rendering.contains("accountId=" + EventEnvelope.WITHHELD),
                    "the account identifier is the key every event of this account carries, so a"
                            + " rendering withholds it and names the component instead: " + rendering);
            assertTrue(rendering.contains("currentCycleCredit=" + EventEnvelope.WITHHELD)
                            && rendering.contains("currentCycleDebit=" + EventEnvelope.WITHHELD),
                    "each withheld accumulator still names its component, because the pair is what"
                            + " app/cbl/CBTRN02C.cbl:L403-L407 authorizes against: " + rendering);
        }
    }

    /**
     * Copies the reference customer request with one first name.
     *
     * @param firstName the value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withFirstName(String firstName) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), firstName,
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), VALID_CUSTOMER.addressLine2(),
                VALID_CUSTOMER.addressCity(), VALID_CUSTOMER.addressStateCode(),
                VALID_CUSTOMER.addressCountryCode(), VALID_CUSTOMER.addressZip(),
                VALID_CUSTOMER.phoneNumber1(), VALID_CUSTOMER.phoneNumber2(),
                VALID_CUSTOMER.socialSecurityPart1(), VALID_CUSTOMER.socialSecurityPart2(),
                VALID_CUSTOMER.socialSecurityPart3(), VALID_CUSTOMER.governmentIssuedId(),
                VALID_CUSTOMER.dateOfBirth(), VALID_CUSTOMER.eftAccountId(),
                VALID_CUSTOMER.primaryCardHolderIndicator(), VALID_CUSTOMER.ficoCreditScore());
    }

    /**
     * Copies the reference customer request with one state code.
     *
     * @param stateCode the value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withStateCode(String stateCode) {
        return withStateZip(stateCode, VALID_CUSTOMER.addressZip());
    }

    /**
     * Copies the reference customer request with one state code and one postal code.
     *
     * @param stateCode the state value to substitute
     * @param zip       the postal value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withStateZip(String stateCode, String zip) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), VALID_CUSTOMER.firstName(),
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), VALID_CUSTOMER.addressLine2(),
                VALID_CUSTOMER.addressCity(), stateCode, VALID_CUSTOMER.addressCountryCode(), zip,
                VALID_CUSTOMER.phoneNumber1(), VALID_CUSTOMER.phoneNumber2(),
                VALID_CUSTOMER.socialSecurityPart1(), VALID_CUSTOMER.socialSecurityPart2(),
                VALID_CUSTOMER.socialSecurityPart3(), VALID_CUSTOMER.governmentIssuedId(),
                VALID_CUSTOMER.dateOfBirth(), VALID_CUSTOMER.eftAccountId(),
                VALID_CUSTOMER.primaryCardHolderIndicator(), VALID_CUSTOMER.ficoCreditScore());
    }

    /**
     * Copies the reference customer request with three social security parts.
     *
     * @param part1 the first part
     * @param part2 the second part
     * @param part3 the third part
     * @return the copy
     */
    private static CustomerDataRequest withSocialSecurity(String part1, String part2,
            String part3) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), VALID_CUSTOMER.firstName(),
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), VALID_CUSTOMER.addressLine2(),
                VALID_CUSTOMER.addressCity(), VALID_CUSTOMER.addressStateCode(),
                VALID_CUSTOMER.addressCountryCode(), VALID_CUSTOMER.addressZip(),
                VALID_CUSTOMER.phoneNumber1(), VALID_CUSTOMER.phoneNumber2(), part1, part2, part3,
                VALID_CUSTOMER.governmentIssuedId(), VALID_CUSTOMER.dateOfBirth(),
                VALID_CUSTOMER.eftAccountId(), VALID_CUSTOMER.primaryCardHolderIndicator(),
                VALID_CUSTOMER.ficoCreditScore());
    }

    /**
     * Copies the reference customer request with one first telephone number.
     *
     * @param phoneNumber the value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withPhoneNumber1(String phoneNumber) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), VALID_CUSTOMER.firstName(),
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), VALID_CUSTOMER.addressLine2(),
                VALID_CUSTOMER.addressCity(), VALID_CUSTOMER.addressStateCode(),
                VALID_CUSTOMER.addressCountryCode(), VALID_CUSTOMER.addressZip(), phoneNumber,
                VALID_CUSTOMER.phoneNumber2(), VALID_CUSTOMER.socialSecurityPart1(),
                VALID_CUSTOMER.socialSecurityPart2(), VALID_CUSTOMER.socialSecurityPart3(),
                VALID_CUSTOMER.governmentIssuedId(), VALID_CUSTOMER.dateOfBirth(),
                VALID_CUSTOMER.eftAccountId(), VALID_CUSTOMER.primaryCardHolderIndicator(),
                VALID_CUSTOMER.ficoCreditScore());
    }

    /**
     * Copies the reference customer request with one credit score.
     *
     * @param creditScore the value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withCreditScore(String creditScore) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), VALID_CUSTOMER.firstName(),
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), VALID_CUSTOMER.addressLine2(),
                VALID_CUSTOMER.addressCity(), VALID_CUSTOMER.addressStateCode(),
                VALID_CUSTOMER.addressCountryCode(), VALID_CUSTOMER.addressZip(),
                VALID_CUSTOMER.phoneNumber1(), VALID_CUSTOMER.phoneNumber2(),
                VALID_CUSTOMER.socialSecurityPart1(), VALID_CUSTOMER.socialSecurityPart2(),
                VALID_CUSTOMER.socialSecurityPart3(), VALID_CUSTOMER.governmentIssuedId(),
                VALID_CUSTOMER.dateOfBirth(), VALID_CUSTOMER.eftAccountId(),
                VALID_CUSTOMER.primaryCardHolderIndicator(), creditScore);
    }

    /**
     * Copies the reference customer request with one second address line.
     *
     * @param addressLine2 the value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withAddressLine2(String addressLine2) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), VALID_CUSTOMER.firstName(),
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), addressLine2, VALID_CUSTOMER.addressCity(),
                VALID_CUSTOMER.addressStateCode(), VALID_CUSTOMER.addressCountryCode(),
                VALID_CUSTOMER.addressZip(), VALID_CUSTOMER.phoneNumber1(),
                VALID_CUSTOMER.phoneNumber2(), VALID_CUSTOMER.socialSecurityPart1(),
                VALID_CUSTOMER.socialSecurityPart2(), VALID_CUSTOMER.socialSecurityPart3(),
                VALID_CUSTOMER.governmentIssuedId(), VALID_CUSTOMER.dateOfBirth(),
                VALID_CUSTOMER.eftAccountId(), VALID_CUSTOMER.primaryCardHolderIndicator(),
                VALID_CUSTOMER.ficoCreditScore());
    }

    /**
     * Copies the reference customer request with one government identifier.
     *
     * @param governmentIssuedId the value to substitute
     * @return the copy
     */
    private static CustomerDataRequest withGovernmentIssuedId(String governmentIssuedId) {
        return new CustomerDataRequest(VALID_CUSTOMER.customerId(), VALID_CUSTOMER.firstName(),
                VALID_CUSTOMER.middleName(), VALID_CUSTOMER.lastName(),
                VALID_CUSTOMER.addressLine1(), VALID_CUSTOMER.addressLine2(),
                VALID_CUSTOMER.addressCity(), VALID_CUSTOMER.addressStateCode(),
                VALID_CUSTOMER.addressCountryCode(), VALID_CUSTOMER.addressZip(),
                VALID_CUSTOMER.phoneNumber1(), VALID_CUSTOMER.phoneNumber2(),
                VALID_CUSTOMER.socialSecurityPart1(), VALID_CUSTOMER.socialSecurityPart2(),
                VALID_CUSTOMER.socialSecurityPart3(), governmentIssuedId,
                VALID_CUSTOMER.dateOfBirth(), VALID_CUSTOMER.eftAccountId(),
                VALID_CUSTOMER.primaryCardHolderIndicator(), VALID_CUSTOMER.ficoCreditScore());
    }

    /**
     * Copies the reference account request with one active status.
     *
     * @param activeStatus the value to substitute
     * @return the copy
     */
    private static AccountDataRequest withActiveStatus(String activeStatus) {
        return new AccountDataRequest(activeStatus, VALID_ACCOUNT.currentBalance(),
                VALID_ACCOUNT.creditLimit(), VALID_ACCOUNT.cashCreditLimit(),
                VALID_ACCOUNT.openDate(), VALID_ACCOUNT.expirationDate(),
                VALID_ACCOUNT.reissueDate(), VALID_ACCOUNT.currentCycleCredit(),
                VALID_ACCOUNT.currentCycleDebit(), VALID_ACCOUNT.groupId());
    }

    /**
     * Copies the reference account request with one credit limit.
     *
     * @param creditLimit the value to substitute
     * @return the copy
     */
    private static AccountDataRequest withCreditLimit(String creditLimit) {
        return new AccountDataRequest(VALID_ACCOUNT.activeStatus(), VALID_ACCOUNT.currentBalance(),
                creditLimit, VALID_ACCOUNT.cashCreditLimit(), VALID_ACCOUNT.openDate(),
                VALID_ACCOUNT.expirationDate(), VALID_ACCOUNT.reissueDate(),
                VALID_ACCOUNT.currentCycleCredit(), VALID_ACCOUNT.currentCycleDebit(),
                VALID_ACCOUNT.groupId());
    }

    /**
     * Copies the reference account request with one open date.
     *
     * @param openDate the value to substitute
     * @return the copy
     */
    private static AccountDataRequest withOpenDate(String openDate) {
        return new AccountDataRequest(VALID_ACCOUNT.activeStatus(), VALID_ACCOUNT.currentBalance(),
                VALID_ACCOUNT.creditLimit(), VALID_ACCOUNT.cashCreditLimit(), openDate,
                VALID_ACCOUNT.expirationDate(), VALID_ACCOUNT.reissueDate(),
                VALID_ACCOUNT.currentCycleCredit(), VALID_ACCOUNT.currentCycleDebit(),
                VALID_ACCOUNT.groupId());
    }

    /**
     * Joins the texts of a violation set, for a failure message.
     *
     * @param violations the violations reported
     * @param <T>        the validated type
     * @return the texts, comma separated, or a note that the set is empty
     */
    private static <T> String messagesOf(Set<ConstraintViolation<T>> violations) {
        if (violations.isEmpty()) {
            return "no violation";
        }
        List<String> texts = new ArrayList<>();
        for (ConstraintViolation<T> violation : violations) {
            texts.add(violation.getPropertyPath() + " " + violation.getMessage());
        }
        return String.join(", ", texts);
    }
}

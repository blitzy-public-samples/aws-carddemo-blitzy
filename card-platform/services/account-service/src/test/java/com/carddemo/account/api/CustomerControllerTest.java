package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.CustomerRepository;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Behaviour tests for {@link CustomerController}.
 *
 * <p>The endpoint under test reproduces the customer read at
 * {@code app/cbl/COACTVWC.cbl:L860-L870}. What these tests assert is the two things about it that are
 * easy to get wrong: the credit score is a whole number, and the two identity documents never travel.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the customer read surface")
class CustomerControllerTest {

    /** Row one of {@code app/data/ASCII/custdata.txt}, its customer identifier. */
    private static final String CUSTOMER_ID = "000000050";

    private CustomerRepository customers;
    private CustomerController controller;

    /** Builds the controller over a stubbed store before each test. */
    @BeforeEach
    void buildController() {
        customers = mock(CustomerRepository.class);
        controller = new CustomerController(customers);
    }

    /** Asserts a stored customer is returned with every one of the sixteen view components. */
    @Test
    void aStoredCustomerIsReturnedInFull() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        ResponseEntity<?> response = controller.readCustomer(CUSTOMER_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "a stored row answers 200");
        CustomerView view = assertInstanceOf(CustomerView.class, response.getBody());
        assertEquals(CUSTOMER_ID, view.customerId(), "the identifier keeps its leading zeros");
        assertEquals("Arlene", view.firstName(), "the given name");
        assertEquals("Abshire", view.lastName(), "the family name");
        assertEquals("AR", view.addressStateCode(),
                "one of the 56 codes app/cpy/CSLKPCDY.cpy:L1012 lists");
        assertEquals("72112", view.addressZip(),
                "checked with the state code against app/cpy/CSLKPCDY.cpy:L1071-L1073");
        assertEquals("Y", view.primaryCardHolderIndicator(),
                "CUST-PRI-CARD-HOLDER-IND PIC X(01) at app/cpy/CVCUS01Y.cpy:L22");
    }

    /**
     * Asserts the credit score is rendered as a whole number.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L21} holds three
     * digits and no fraction, so a score carrying a decimal point would misdescribe the column. The
     * column is {@code NUMERIC} and the entity holds a decimal, which is why the conversion has to be
     * made explicitly rather than left to a serializer.
     */
    @Test
    void theCreditScoreIsRenderedAsAWholeNumber() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        CustomerView view = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());

        assertEquals(Integer.valueOf(688), view.ficoCreditScore(),
                "three digits, no fraction, and inside the 300 to 850 range the edits enforce");
    }

    /** Asserts a row carrying no credit score is returned rather than refused. */
    @Test
    void anAbsentCreditScoreIsCarriedAsAbsent() {
        CustomerEntity stored = storedCustomer();
        stored.setFicoCreditScore(null);
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(stored));

        CustomerView view = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());

        assertNull(view.ficoCreditScore(),
                "a row with no score reads back with none, rather than with a fabricated zero");
    }

    /**
     * Asserts the view carries neither identity document.
     *
     * <p>{@code CUSTOMER-RECORD} declares nineteen modelled fields and the view declares sixteen. The
     * three it omits are the recombined Social Security column, the government-issued identifier, and
     * the address postcode's account-side twin. A read is the wrong operation to disclose the first
     * two through, and no caller of this platform needs them back.
     */
    @Test
    void theViewCarriesNeitherIdentityDocument() {
        var components = Arrays.stream(CustomerView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertFalse(components.contains("socialSecurityNumber"),
                "the Social Security column never travels in a response");
        assertFalse(components.contains("governmentIssuedId"),
                "and neither does the government-issued identifier");
        assertEquals(16, components.size(),
                "sixteen of the nineteen modelled fields of app/cpy/CVCUS01Y.cpy:L4-L23");
    }

    /**
     * Asserts a read that missed answers 404 with a problem document naming no identifier.
     *
     * <p>The detail names nothing, for the reason {@code config/SecurityConfig} gives for its own 403:
     * a caller probing for another subject's rows should learn nothing from the answer.
     */
    @Test
    void aReadThatMissedAnswersNotFoundAndNamesNothing() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.readCustomer(CUSTOMER_ID);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no row answers 404");
        ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
        assertEquals(ApiProblem.NOT_FOUND, problem.title(), "one fixed title");
        assertFalse(problem.detail().contains(CUSTOMER_ID),
                "the detail repeats no identifier back to its sender");
        assertNull(problem.messages(), "no field failed, so no field text is carried");
    }

    /**
     * Asserts the path pattern pins nine digits.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}. The leading zeros belong to the
     * value, because the source compares the identifier as text, so a pattern admitting a shorter
     * value would admit an identifier that matches no row.
     */
    @Test
    void thePathPatternPinsNineDigits() {
        assertTrue(CUSTOMER_ID.matches(CustomerController.CUSTOMER_ID_PATTERN),
                "nine digits with leading zeros is the shape a row carries");
        assertFalse("50".matches(CustomerController.CUSTOMER_ID_PATTERN),
                "an unpadded identifier matches no row and is refused before a read");
        assertFalse("0000000501".matches(CustomerController.CUSTOMER_ID_PATTERN),
                "ten digits is one too many");
        assertFalse("00000005A".matches(CustomerController.CUSTOMER_ID_PATTERN),
                "a letter is not a digit");
    }

    /** @return the stored customer, from row one of app/data/ASCII/custdata.txt */
    private static CustomerEntity storedCustomer() {
        CustomerEntity stored = new CustomerEntity();
        stored.setCustomerId(CUSTOMER_ID);
        stored.setFirstName("Arlene");
        stored.setMiddleName("Fay");
        stored.setLastName("Abshire");
        stored.setAddressLine1("8829 Ondricka Trail");
        stored.setAddressLine2("Suite 407");
        stored.setAddressCity("North Enoshaven");
        stored.setAddressStateCode("AR");
        stored.setAddressCountryCode("USA");
        stored.setAddressZip("72112");
        stored.setPhoneNumber1("(501)5551234");
        stored.setPhoneNumber2("(501)5555678");
        stored.setSocialSecurityNumber("429541163");
        stored.setGovernmentIssuedId("AR8829114");
        stored.setDateOfBirth("1971-08-14");
        stored.setEftAccountId("4829571130");
        stored.setPrimaryCardHolderIndicator("Y");
        stored.setFicoCreditScore(new BigDecimal("688"));
        return stored;
    }
}

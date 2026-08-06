package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.CustomerRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Behaviour tests for {@link CustomerController}.
 *
 * <p>The endpoint under test reproduces {@code 9400-GETCUSTDATA-BYCUST}, whose keyed read sits at
 * {@code app/cbl/COACTVWC.cbl:L826-L834} and whose miss text sits at {@code :L846-L856}. The stored
 * row these tests use is record one of {@code app/data/ASCII/custdata.txt}, field for field, as
 * {@code src/main/resources/db/migration/V2__seed.sql:L158-L164} loads it.
 *
 * <p>Six properties are easy to lose in translation and each has a test below: the credit score is a
 * whole number, the two identity documents never travel, the miss text keeps the spacing and the
 * spelling of the source, the date of birth and the telephone numbers keep their stored width, the
 * third address line is the city, and a score under the update floor still reads back.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the customer read surface")
class CustomerControllerTest {

    /** Record one of {@code app/data/ASCII/custdata.txt}, its customer identifier at columns 1-9. */
    private static final String CUSTOMER_ID = "000000001";

    /** An account identifier, eleven digits wide, from {@code app/data/ASCII/acctdata.txt}. */
    private static final String ACCOUNT_ID = "00000000050";

    /** Columns 280-288 of that record. It reaches no response. */
    private static final String STORED_SSN = "020973888";

    /** Columns 289-308 of that record. It reaches no response. */
    private static final String STORED_GOVERNMENT_ID = "00000000000049368437";

    /** Columns 250-264 of that record, holding the mask and its two trailing spaces. */
    private static final String STORED_PHONE_1 = "(908)119-8310  ";

    /** Columns 265-279 of that record. */
    private static final String STORED_PHONE_2 = "(373)693-8684  ";

    /** Columns 309-318 of that record. */
    private static final String STORED_DATE_OF_BIRTH = "1961-06-08";

    /** Columns 185-234 of that record, which {@code app/cbl/COACTVWC.cbl:L513} moves to the city. */
    private static final String STORED_CITY = "Altenwerthshire                                   ";

    /** The text {@code app/cbl/COACTVWC.cbl:L846-L856} builds for this identifier and a 404. */
    private static final String MISS_TEXT =
            "CustId:000000001 not found in customer master.Resp: 404 REAS:Not Found";

    private CustomerRepository customers;
    private CustomerController controller;

    /** Builds the controller over a stubbed store before each test. */
    @BeforeEach
    void buildController() {
        customers = mock(CustomerRepository.class);
        controller = new CustomerController(customers);
    }

    /** Asserts a stored customer is returned, and that the read keys on the customer identifier. */
    @Test
    void aStoredCustomerIsReturnedInFull() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        ResponseEntity<?> response = controller.readCustomer(CUSTOMER_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "a stored row answers 200");
        CustomerView view = assertInstanceOf(CustomerView.class, response.getBody());
        assertEquals(CUSTOMER_ID, view.customerId(), "the identifier keeps its leading zeros");
        assertEquals("Immanuel                 ", view.firstName(), "the given name");
        assertEquals("Kessler                  ", view.lastName(), "the family name");
        assertEquals("NC", view.addressStateCode(),
                "one of the 56 codes app/cpy/CSLKPCDY.cpy:L1012 lists");
        assertEquals("12546     ", view.addressZip(),
                "checked with the state code against app/cpy/CSLKPCDY.cpy:L1071-L1073");
        assertEquals("Y", view.primaryCardHolderIndicator(),
                "CUST-PRI-CARD-HOLDER-IND PIC X(01) at app/cpy/CVCUS01Y.cpy:L21");
        verify(customers).findByCustomerId(CUSTOMER_ID);
    }

    /**
     * Asserts the credit score is rendered as a whole number.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22} holds three
     * digits and no fraction. The column is {@code NUMERIC(3,0)} and the entity holds a decimal, so
     * the conversion is made in the mapper and not left to a serializer.
     */
    @Test
    void theCreditScoreIsRenderedAsAWholeNumber() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        CustomerView view = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());

        assertEquals(Integer.valueOf(274), view.ficoCreditScore(),
                "three digits and no fraction, at columns 330 to 332 of record one");
    }

    /** Asserts a row carrying no credit score is answered and not refused. */
    @Test
    void anAbsentCreditScoreIsCarriedAsAbsent() {
        CustomerEntity stored = storedCustomer();
        stored.setFicoCreditScore(null);
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(stored));

        CustomerView view = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());

        assertNull(view.ficoCreditScore(),
                "a row with no score reads back with none, and not with a fabricated zero");
    }

    /**
     * Asserts a score under the range the update path enforces still reads back.
     *
     * <p>{@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code app/cbl/COACTUPC.cbl:L848-L849} governs an update. 21 of the 50 records of
     * {@code app/data/ASCII/custdata.txt} carry a lower value at columns 330 to 332, the lowest
     * being 1, and records one and two carry 274 and 268.
     */
    @Test
    void aScoreUnderTheUpdateFloorIsReturnedIntact() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        CustomerView first = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());
        assertEquals(Integer.valueOf(274), first.ficoCreditScore(), "record one reads back at 274");

        CustomerEntity second = storedCustomer();
        second.setCustomerId("000000002");
        second.setFicoCreditScore(new BigDecimal("268"));
        when(customers.findByCustomerId("000000002")).thenReturn(Optional.of(second));

        ResponseEntity<?> response = controller.readCustomer("000000002");
        assertEquals(HttpStatus.OK, response.getStatusCode(), "record two answers 200");
        assertEquals(Integer.valueOf(268),
                assertInstanceOf(CustomerView.class, response.getBody()).ficoCreditScore(),
                "record two reads back at 268");
    }

    /**
     * Asserts the view declares neither identity document.
     *
     * <p>{@code 01 CUSTOMER-RECORD} declares eighteen fields at
     * {@code app/cpy/CVCUS01Y.cpy:L5-L22} and a 168-byte filler at {@code :L23}. The view declares
     * sixteen components. The two it omits are {@code CUST-SSN} at {@code :L17} and
     * {@code CUST-GOVT-ISSUED-ID} at {@code :L18}, which the source displays at
     * {@code app/cbl/COACTVWC.cbl:L496-L504} and {@code :L519}.
     */
    @Test
    void theViewDeclaresNeitherIdentityDocument() {
        var components = Arrays.stream(CustomerView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertFalse(components.contains("socialSecurityNumber"),
                "the Social Security Number never travels in a response");
        assertFalse(components.contains("governmentIssuedId"),
                "and neither does the government-issued identifier");
        assertEquals(16, components.size(),
                "sixteen of the eighteen fields of app/cpy/CVCUS01Y.cpy:L5-L22");
    }

    /**
     * Asserts the serialized response discloses neither identity document.
     *
     * <p>This measures the wire form of a fully populated view, where the assertion above measures
     * the declaration.
     */
    @Test
    void theSerializedViewDisclosesNeitherIdentityDocument() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        String wire = JsonMapper.builder().build()
                .writeValueAsString(controller.readCustomer(CUSTOMER_ID).getBody());

        assertFalse(wire.contains(STORED_SSN), "no Social Security Number reaches the wire");
        assertFalse(wire.contains(STORED_GOVERNMENT_ID),
                "no government-issued identifier reaches the wire");
        assertFalse(wire.contains("socialSecurityNumber"), "and no member names one");
        assertFalse(wire.contains("governmentIssuedId"), "and no member names the other");
        assertTrue(wire.contains(STORED_PHONE_1), "the values a caller may read are present");
    }

    /**
     * Asserts a read that missed answers 404 carrying the text the source builds.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:L846-L856} concatenates {@code 'CustId:'} at {@code :L847},
     * the identifier at {@code :L848}, {@code ' not found'} at {@code :L849},
     * {@code ' in customer master.Resp: '} at {@code :L850} with its trailing space, the response
     * code, {@code ' REAS:'} at {@code :L852} in upper case, and the reason code. The status and
     * the reason phrase of this response stand in for the two transaction-monitor codes.
     */
    @Test
    void aReadThatMissedAnswersTheTextTheSourceBuilds() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.readCustomer(CUSTOMER_ID);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no row answers 404");
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType(),
                "one shape of error for the whole service");
        ApiProblem problem = assertInstanceOf(ApiProblem.class, response.getBody());
        assertEquals(ApiProblem.NOT_FOUND, problem.title(), "one fixed title");
        assertEquals(MISS_TEXT, problem.detail(), "the text of app/cbl/COACTVWC.cbl:L846-L856");
        assertTrue(problem.detail().contains("master.Resp: 404"),
                "app/cbl/COACTVWC.cbl:L850 keeps its trailing space");
        assertTrue(problem.detail().contains(" REAS:"),
                "app/cbl/COACTVWC.cbl:L852 keeps its upper case");
        assertFalse(problem.detail().contains(" Reas:"),
                "the mixed-case spelling belongs to the account text at app/cbl/COACTVWC.cbl:L802");
        assertTrue(problem.detail().length() <= 75,
                "inside WS-RETURN-MSG PIC X(75) at app/cbl/COACTVWC.cbl:L117");
        assertNull(problem.messages(), "no field failed, so no field text is carried");
    }

    /**
     * Asserts the date of birth and both telephone numbers keep their stored width.
     *
     * <p>{@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19} holds ten
     * characters with two hyphens. {@code CUST-PHONE-NUM-1} and {@code CUST-PHONE-NUM-2}
     * {@code PIC X(15)} at {@code :L15-L16} hold the mask that
     * {@code app/cbl/COACTUPC.cbl:L82-L100} redefines, whose separators are named at {@code :L86},
     * {@code :L91} and {@code :L96} and whose trailing filler is two characters at {@code :L100}.
     */
    @Test
    void theDateOfBirthAndTelephoneNumbersRoundTripWhole() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        CustomerView view = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());

        assertEquals(STORED_DATE_OF_BIRTH, view.dateOfBirth(), "ten characters, two hyphens");
        assertEquals(10, view.dateOfBirth().length(), "the width the column holds");
        assertEquals(STORED_PHONE_1, view.phoneNumber1(), "the mask arrives whole");
        assertEquals(STORED_PHONE_2, view.phoneNumber2(), "and so does the second");
        assertEquals(15, view.phoneNumber1().length(), "the width the column holds");
        assertTrue(view.phoneNumber1().endsWith("  "),
                "the two trailing characters of app/cbl/COACTUPC.cbl:L100");
    }

    /**
     * Asserts the third address line is exposed as the city.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:L513} moves {@code CUST-ADDR-LINE-3} to the city field, and
     * {@code app/cbl/COACTUPC.cbl:L1615-L1616} labels that field {@code 'City'} while reading the
     * third line.
     */
    @Test
    void theThirdAddressLineIsExposedAsTheCity() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));

        CustomerView view = assertInstanceOf(CustomerView.class,
                controller.readCustomer(CUSTOMER_ID).getBody());

        assertEquals(STORED_CITY, view.addressCity(), "the third line arrives as the city");
        assertTrue(Arrays.stream(CustomerView.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .noneMatch("addressLine3"::equals),
                "no component carries the copybook's own name for the field");
    }

    /**
     * Asserts the path pattern pins nine digits.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}, keyed through
     * {@code WS-CARD-RID-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTVWC.cbl:L76-L77}. The leading
     * zeros belong to the value, and a pattern admitting a shorter one would admit an identifier
     * that matches no row.
     */
    @Test
    void thePathPatternPinsNineDigits() {
        assertTrue(CUSTOMER_ID.matches(CustomerController.CUSTOMER_ID_PATTERN),
                "nine digits with leading zeros is the shape a row carries");
        assertFalse("50".matches(CustomerController.CUSTOMER_ID_PATTERN),
                "an unpadded identifier matches no row and is refused before a read");
        assertFalse("0000000501".matches(CustomerController.CUSTOMER_ID_PATTERN),
                "ten digits is one too many");
        assertFalse(ACCOUNT_ID.matches(CustomerController.CUSTOMER_ID_PATTERN),
                "an eleven digit account identifier reaches no customer");
        assertFalse("00000000A".matches(CustomerController.CUSTOMER_ID_PATTERN),
                "a letter is not a digit");
    }

    /**
     * Asserts the endpoint holds one store and writes nothing.
     *
     * <p>A read is not a state change, so no outbox row and no event follows one. The field
     * inventory is what holds that: a collaborator the class does not hold cannot be called.
     */
    @Test
    void theEndpointHoldsOneStoreAndWritesNothing() {
        List<String> collaborators = new ArrayList<>();
        for (Field field : CustomerController.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                collaborators.add(field.getType().getName());
            }
        }

        assertEquals(List.of(CustomerRepository.class.getName()), collaborators,
                "one store, and no publisher, writer or second store");

        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));
        controller.readCustomer(CUSTOMER_ID);

        verify(customers, never()).save(any());
        verify(customers, never()).findForUpdateByCustomerId(any());
    }

    /** @return record one of app/data/ASCII/custdata.txt, field for field */
    private static CustomerEntity storedCustomer() {
        CustomerEntity stored = new CustomerEntity();
        stored.setCustomerId(CUSTOMER_ID);
        stored.setFirstName("Immanuel                 ");
        stored.setMiddleName("Madeline                 ");
        stored.setLastName("Kessler                  ");
        stored.setAddressLine1("618 Deshaun Route                                 ");
        stored.setAddressLine2("Apt. 802                                          ");
        stored.setAddressCity(STORED_CITY);
        stored.setAddressStateCode("NC");
        stored.setAddressCountryCode("USA");
        stored.setAddressZip("12546     ");
        stored.setPhoneNumber1(STORED_PHONE_1);
        stored.setPhoneNumber2(STORED_PHONE_2);
        stored.setSocialSecurityNumber(STORED_SSN);
        stored.setGovernmentIssuedId(STORED_GOVERNMENT_ID);
        stored.setDateOfBirth(STORED_DATE_OF_BIRTH);
        stored.setEftAccountId("0053581756");
        stored.setPrimaryCardHolderIndicator("Y");
        stored.setFicoCreditScore(new BigDecimal("274"));
        return stored;
    }
}

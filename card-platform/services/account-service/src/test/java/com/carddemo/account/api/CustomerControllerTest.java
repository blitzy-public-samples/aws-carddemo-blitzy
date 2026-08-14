package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.cobol.PicClause;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Web-layer tests for {@link CustomerController}, driving the one route and reading each answer off
 * the wire.
 *
 * <p>One Customer Information Control System (CICS) transaction reaches this class. Its paragraph
 * {@code 9400-GETCUSTDATA-BYCUST} at {@code app/cbl/COACTVWC.cbl:L825} issues one keyed read at
 * {@code :L826-L834} and builds one miss text at {@code :L846-L856}. These tests assert the shape
 * of each answer: which properties arrive, in what order, and in what form.
 *
 * <p>The dispatcher is built over the controller and the real {@link AccountApiExceptionHandler}, so
 * mapping, path-variable binding, method validation and serialization are the running ones. No
 * application context, database, broker or container takes part.
 *
 * <p>The Social Security Number and the government-issued identifier are withheld from every
 * answer, and these tests hold that. Rationale for the two omissions and for resolution by customer
 * identifier alone: {@code card-platform/docs/decision-log.md}. Flagged findings around the source
 * paragraph: {@code card-platform/docs/business-rule-flags.md}. Field mapping from
 * {@code app/cpy/CVCUS01Y.cpy}: {@code card-platform/docs/traceability-matrix.md}.
 */
@DisplayName("the customer read surface")
class CustomerControllerTest {

    /**
     * The customer this class reads, nine digits wide and padded on the left with zeros.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} fixes the width, and
     * {@code WS-CARD-RID-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTVWC.cbl:L76-L77} keys the read
     * as text.
     */
    private static final String CUSTOMER_ID = "000000001";

    /**
     * The sixteen read properties, in the order the customer block moves them.
     *
     * <p>The block is gated by {@code IF FOUND-CUST-IN-MASTER} at
     * {@code app/cbl/COACTVWC.cbl:L493}. Each move carries its own locator.
     *
     * <ul>
     *   <li>{@code CUST-ID} at {@code :L494}</li>
     *   <li>{@code CUST-FICO-CREDIT-SCORE} at {@code :L505-L506}</li>
     *   <li>{@code CUST-DOB-YYYY-MM-DD} at {@code :L507}</li>
     *   <li>{@code CUST-FIRST-NAME} at {@code :L508}</li>
     *   <li>{@code CUST-MIDDLE-NAME} at {@code :L509}</li>
     *   <li>{@code CUST-LAST-NAME} at {@code :L510}</li>
     *   <li>{@code CUST-ADDR-LINE-1} at {@code :L511}</li>
     *   <li>{@code CUST-ADDR-LINE-2} at {@code :L512}</li>
     *   <li>{@code CUST-ADDR-LINE-3} at {@code :L513}</li>
     *   <li>{@code CUST-ADDR-STATE-CD} at {@code :L514}</li>
     *   <li>{@code CUST-ADDR-ZIP} at {@code :L515}</li>
     *   <li>{@code CUST-ADDR-COUNTRY-CD} at {@code :L516}</li>
     *   <li>{@code CUST-PHONE-NUM-1} at {@code :L517}</li>
     *   <li>{@code CUST-PHONE-NUM-2} at {@code :L518}</li>
     *   <li>{@code CUST-EFT-ACCOUNT-ID} at {@code :L520}</li>
     *   <li>{@code CUST-PRI-CARD-HOLDER-IND} at {@code :L521-L522}</li>
     * </ul>
     */
    private static final List<String> VIEW_PROPERTIES_IN_SOURCE_ORDER = List.of(
            "customerId", "ficoCreditScore", "dateOfBirth", "firstName", "middleName", "lastName",
            "addressLine1", "addressLine2", "addressCity", "addressStateCode", "addressZip",
            "addressCountryCode", "phoneNumber1", "phoneNumber2", "eftAccountId",
            "primaryCardHolderIndicator");

    /**
     * Spellings a serializer could give the two fields the view omits.
     *
     * <p>{@code CUST-SSN} sits at {@code app/cpy/CVCUS01Y.cpy:L17} and
     * {@code CUST-GOVT-ISSUED-ID} at {@code :L18}. The entity names them
     * {@code socialSecurityNumber} and {@code governmentIssuedId}, and each spelling below is one a
     * naming strategy could produce from either name.
     */
    private static final List<String> OMITTED_IDENTITY_PROPERTIES = List.of(
            "socialSecurityNumber", "social_security_number", "ssn", "SSN",
            "governmentIssuedId", "government_issued_id", "govtIssuedId", "governmentId");

    /**
     * Property names that would carry a list of field texts or a stack trace.
     *
     * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTVWC.cbl:L117} is one field, and the
     * guard at {@code :L845} lets one text stand per read. No miss answer carries any of these.
     */
    private static final List<String> LIST_AND_TRACE_PROPERTIES = List.of(
            "messages", "errors", "violations", "fieldErrors", "timestamp", "trace");

    /** Packages holding a type that could publish an event. */
    private static final List<String> PUBLISHING_PACKAGES = List.of(
            "com.carddemo.account.outbox", "com.carddemo.account.messaging",
            "com.carddemo.account.config", "com.carddemo.events");

    /**
     * Name fragments under which a card value would travel, folded to lower case.
     *
     * <p>{@code 01 CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy:L4-L23} declares no card number
     * and no card verification value. The card record is {@code app/cpy/CVACT02Y.cpy}, which this
     * service neither stores nor reads.
     */
    private static final List<String> CARD_VALUE_FRAGMENTS = List.of(
            "cardnumber", "cardverification", "cvv", "cardexpir", "embossed");

    /**
     * The one name in the customer layout that mentions a card.
     *
     * <p>{@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at {@code app/cpy/CVCUS01Y.cpy:L21} is a
     * single-character flag, moved at {@code app/cbl/COACTVWC.cbl:L521-L522}.
     */
    private static final String CARD_HOLDER_FLAG = "primaryCardHolderIndicator";

    /** The first literal of the miss text, from {@code app/cbl/COACTVWC.cbl:L847}. */
    private static final String MISS_OPENING = "CustId:";

    /** The second literal, from {@code app/cbl/COACTVWC.cbl:L849}, carrying no trailing word. */
    private static final String MISS_MISSING = " not found";

    /** The third literal, from {@code app/cbl/COACTVWC.cbl:L850}, carrying a trailing space. */
    private static final String MISS_FILE_AND_RESPONSE = " in customer master.Resp: ";

    /** The fourth literal, from {@code app/cbl/COACTVWC.cbl:L852}, spelled in upper case. */
    private static final String MISS_REASON = " REAS:";

    /** The mixed-case spelling the account paragraph uses at {@code app/cbl/COACTVWC.cbl:L802}. */
    private static final String ACCOUNT_REASON_SPELLING = " Reas:";

    /**
     * The whole miss text, byte for byte, as {@code app/cbl/COACTVWC.cbl:L846-L856} builds it.
     *
     * <p>The status and the reason phrase of the response stand in for the two codes the
     * transaction monitor sets at {@code :L832-L833}.
     */
    private static final String MISS_TEXT =
            "CustId:000000001 not found in customer master.Resp: 404 REAS:Not Found";

    /** Nine digits of a stored value that reaches no response. */
    private static final String STORED_SOCIAL_SECURITY_NUMBER = "918273645";

    /** Twenty characters of a second stored value that reaches no response. */
    private static final String STORED_GOVERNMENT_ISSUED_ID = "GOVT-ID-4471-CHECKED";

    /**
     * The date of birth the stored row carries.
     *
     * <p>{@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19} holds ten
     * characters in year, month and day order with two hyphens.
     */
    private static final String STORED_DATE_OF_BIRTH = "1961-06-08";

    /**
     * The Fair Isaac Corporation (FICO) credit score the stored row carries.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22} holds three
     * digits, and {@link CustomerEntity} holds a decimal the mapper narrows to a whole number.
     */
    private static final BigDecimal STORED_CREDIT_SCORE = new BigDecimal("274");

    private static final ObjectMapper JSON = new ObjectMapper();

    private CustomerRepository customers;
    private MockMvc mockMvc;

    @BeforeEach
    void buildSlice() {
        customers = mock(CustomerRepository.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(validating(new CustomerController(customers)))
                .setControllerAdvice(new AccountApiExceptionHandler())
                .build();
    }

    /**
     * Wraps the controller so its {@code @Validated} method constraints are enforced.
     *
     * <p>A running service gets this from {@code spring-boot-starter-validation}, which proxies
     * every {@code @Validated} bean. A standalone dispatcher registers no post-processor, so the
     * {@code @Pattern} on the path variable would be inert without the wrapper.
     *
     * @param controller the controller to wrap
     * @return the controller behind a validating proxy
     */
    private static CustomerController validating(CustomerController controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.afterPropertiesSet();
        return (CustomerController) processor.postProcessAfterInitialization(controller,
                CustomerController.class.getSimpleName());
    }

    /** The sixteen properties a read answers, their order, and the form each takes. */
    @Nested
    @DisplayName("the sixteen customer view properties")
    class TheSixteenCustomerViewProperties {

        /**
         * A read answers exactly sixteen properties and no seventeenth.
         *
         * <p>The customer block moves sixteen values a caller reads, at
         * {@code app/cbl/COACTVWC.cbl:L494} and {@code :L505-L522}.
         */
        @Test
        void aReadAnswersExactlySixteenProperties() throws Exception {
            resolveCustomer();

            JsonNode answer = readCustomer();

            assertAll("the read carries the sixteen values a caller reads",
                    () -> assertEquals(16, answer.size(),
                            "sixteen properties arrive and no seventeenth"),
                    () -> assertEquals(VIEW_PROPERTIES_IN_SOURCE_ORDER,
                            List.copyOf(answer.propertyNames()),
                            "and they are the sixteen the block moves"),
                    () -> assertEquals(16, CustomerView.class.getRecordComponents().length,
                            "the record declares sixteen components"));
        }

        /**
         * The sixteen properties arrive in the order the source block moves them.
         *
         * <p>The order runs from {@code app/cbl/COACTVWC.cbl:L494} to {@code :L522}. Each name is
         * located in the raw answer, and the positions strictly increase.
         */
        @Test
        void thePropertiesArriveInTheSourceMoveOrder() throws Exception {
            resolveCustomer();

            String wire = readCustomerBody();

            int previous = -1;
            for (String property : VIEW_PROPERTIES_IN_SOURCE_ORDER) {
                int position = wire.indexOf('"' + property + '"');
                assertTrue(position > previous,
                        property + " follows the property the block moves before it");
                previous = position;
            }
        }

        /**
         * The third address line arrives as the city.
         *
         * <p>{@code CUST-ADDR-LINE-3 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L11} is moved to the
         * city output field {@code ACSCITYO} at {@code app/cbl/COACTVWC.cbl:L513}. The account
         * update program labels the same field {@code 'City'} at
         * {@code app/cbl/COACTUPC.cbl:L1615} and reads the third line at {@code :L1616}.
         */
        @Test
        void theThirdAddressLineArrivesAsTheCity() throws Exception {
            resolveCustomer();

            JsonNode answer = readCustomer();
            List<String> declared = propertyNamesOf(CustomerView.class);

            assertAll("the third line is named for what the source displays it as",
                    () -> assertEquals(storedCustomer().getAddressCity(),
                            answer.get("addressCity").stringValue(),
                            "the value app/cbl/COACTVWC.cbl:L513 moves arrives under addressCity"),
                    () -> assertEquals(PicClause.CUST_ADDR_LINE_3_WIDTH,
                            answer.get("addressCity").stringValue().length(),
                            "at the width app/cpy/CVCUS01Y.cpy:L11 declares"),
                    () -> assertTrue(declared.contains("addressCity"),
                            "the record declares the city name"),
                    () -> assertFalse(declared.contains("addressLine3"),
                            "and it declares no third-line name"));
        }

        /**
         * The date of birth arrives as ten characters of text.
         *
         * <p>{@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19} is moved
         * whole at {@code app/cbl/COACTVWC.cbl:L507}. The text keeps the year, month and day order
         * its field name states, and no property parses it into a date.
         */
        @Test
        void theDateOfBirthArrivesAsTenCharactersOfText() throws Exception {
            resolveCustomer();

            JsonNode value = readCustomer().get("dateOfBirth");

            assertAll("the date of birth travels as the text the field holds",
                    () -> assertTrue(value.isString(),
                            "the property arrives as text and never as a date"),
                    () -> assertEquals(PicClause.CUST_DOB_WIDTH, value.stringValue().length(),
                            "carrying the ten characters app/cpy/CVCUS01Y.cpy:L19 declares"),
                    () -> assertTrue(value.stringValue().matches("\\d{4}-\\d{2}-\\d{2}"),
                            "in the year, month and day order the field name states"));
        }

        /**
         * The credit score arrives as a whole number.
         *
         * <p>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22} holds
         * three digits and no fraction, and {@code app/cbl/COACTVWC.cbl:L505-L506} moves it as a
         * number.
         */
        @Test
        void theCreditScoreArrivesAsAWholeNumber() throws Exception {
            resolveCustomer();

            JsonNode value = readCustomer().get("ficoCreditScore");

            assertAll("the score carries no fraction and no quotation marks",
                    () -> assertTrue(value.isIntegralNumber(),
                            "the property arrives as a whole number and never as text"),
                    () -> assertEquals(STORED_CREDIT_SCORE.intValueExact(), value.intValue(),
                            "at the value the row holds"),
                    () -> assertFalse(readCustomerBody().contains("\"ficoCreditScore\":\""),
                            "and app/cpy/CVCUS01Y.cpy:L22 declares no fraction to quote"));
        }

        /**
         * A row carrying no credit score answers a property carrying none.
         *
         * <p>Sixteen properties arrive whatever the row holds, so a reader counts the same
         * sixteen the block moves at {@code app/cbl/COACTVWC.cbl:L494-L522} on every answer.
         */
        @Test
        void anAbsentCreditScoreArrivesAsAnEmptyProperty() throws Exception {
            CustomerEntity stored = storedCustomer();
            stored.setFicoCreditScore(null);
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(stored));

            JsonNode answer = readCustomer();

            assertAll("an absent score is answered as absent and never as a zero",
                    () -> assertTrue(answer.get("ficoCreditScore").isNull(),
                            "the property carries no value"),
                    () -> assertEquals(16, answer.size(),
                            "and the sixteen properties still arrive"));
        }

        /**
         * Each of the fifteen text properties arrives as the row holds it.
         *
         * <p>The fifteen values are moved at {@code app/cbl/COACTVWC.cbl:L494},
         * {@code :L507-L518} and {@code :L520-L522}. Trailing spaces belong to the stored value, so
         * no property is trimmed on the way out.
         */
        @Test
        void everyTextPropertyArrivesAsTheRowHoldsIt() throws Exception {
            resolveCustomer();

            JsonNode answer = readCustomer();

            assertAll(textPropertiesOf(storedCustomer()).stream().map(text -> () -> {
                JsonNode value = answer.get(text.property());
                assertTrue(value.isString(), text.property() + " arrives as text");
                assertEquals(text.storedValue(), value.stringValue(),
                        text.property() + " arrives with the characters the row holds");
            }));
        }
    }

    /** The two stored fields the source displays and no answer carries. */
    @Nested
    @DisplayName("the two omitted identity documents")
    class TheTwoOmittedIdentityDocuments {

        /**
         * The view declares neither identity document.
         *
         * <p>{@code 01 CUSTOMER-RECORD} declares eighteen fields at
         * {@code app/cpy/CVCUS01Y.cpy:L5-L22} and a 168-character filler at {@code :L23}. Eighteen
         * fields less the two at {@code :L17-L18} gives the sixteen components the view declares.
         */
        @Test
        void theViewDeclaresNeitherIdentityDocument() {
            List<String> declared = propertyNamesOf(CustomerView.class);

            assertAll("the view names neither identity document",
                    () -> assertEquals(16, declared.size(),
                            "sixteen components, one per field a caller reads"),
                    () -> assertAll(OMITTED_IDENTITY_PROPERTIES.stream()
                            .map(omitted -> () -> assertFalse(declared.contains(omitted),
                                    omitted + " reaches no component of the view"))));
        }

        /**
         * Neither stored value and neither name reaches a read answer.
         *
         * <p>The source displays both. {@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy:L17} reaches
         * a screen through the hyphenating {@code STRING} at
         * {@code app/cbl/COACTVWC.cbl:L496-L504}, which replaced a plain move commented out at
         * {@code :L495}. {@code CUST-GOVT-ISSUED-ID} at {@code app/cpy/CVCUS01Y.cpy:L18} reaches it
         * through the plain move at {@code app/cbl/COACTVWC.cbl:L519}.
         */
        @Test
        void neitherStoredValueReachesAReadAnswer() throws Exception {
            resolveCustomer();

            String wire = readCustomerBody();

            assertAll("both stored values stay in the row",
                    () -> assertFalse(wire.contains(STORED_SOCIAL_SECURITY_NUMBER),
                            "no Social Security Number reaches the wire"),
                    () -> assertFalse(wire.contains(STORED_GOVERNMENT_ISSUED_ID),
                            "no government-issued identifier reaches the wire"),
                    () -> assertAll(OMITTED_IDENTITY_PROPERTIES.stream()
                            .map(omitted -> () -> assertFalse(wire.contains('"' + omitted + '"'),
                                    omitted + " names no property on the wire"))));
        }

        /**
         * The property that follows the two telephone numbers is the transfer identifier.
         *
         * <p>{@code CUST-GOVT-ISSUED-ID} is moved at {@code app/cbl/COACTVWC.cbl:L519}, between the
         * second telephone number at {@code :L518} and the electronic funds transfer identifier at
         * {@code :L520}. The omission closes that gap, leaving the transfer identifier next.
         */
        @Test
        void theTransferIdentifierFollowsTheSecondTelephoneNumber() throws Exception {
            resolveCustomer();

            List<String> arrived = List.copyOf(readCustomer().propertyNames());

            assertEquals("eftAccountId", arrived.get(arrived.indexOf("phoneNumber2") + 1),
                    "the property of app/cbl/COACTVWC.cbl:L520 follows the one of :L518");
        }
    }

    /** The one text a miss answers, and the shape it travels in. */
    @Nested
    @DisplayName("the single customer message slot")
    class TheSingleMessageSlot {

        /**
         * A read that missed answers the text the source builds, byte for byte.
         *
         * <p>{@code app/cbl/COACTVWC.cbl:L846-L856} concatenates four literals around the
         * identifier, a response code and a reason code. The spacing and the spelling of each
         * survive unaltered.
         *
         * <ul>
         *   <li>{@code 'CustId:'} at {@code :L847}, then the identifier at {@code :L848}</li>
         *   <li>{@code ' not found'} at {@code :L849}, carrying no trailing word</li>
         *   <li>{@code ' in customer master.Resp: '} at {@code :L850}, carrying a trailing
         *       space</li>
         *   <li>{@code ' REAS:'} at {@code :L852}, spelled in upper case</li>
         * </ul>
         */
        @Test
        void aReadThatMissedAnswersTheSourceTextByteForByte() throws Exception {
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

            MvcResult result = performRead();
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());
            String detail = answer.get("detail").stringValue();

            assertAll("the miss text arrives whole, in one slot",
                    () -> assertEquals(404, result.getResponse().getStatus(),
                            "a row this service does not hold answers 404"),
                    () -> assertEquals(MISS_TEXT, detail,
                            "the whole text of app/cbl/COACTVWC.cbl:L846-L856"),
                    () -> assertTrue(detail.startsWith(MISS_OPENING + CUSTOMER_ID + MISS_MISSING),
                            "the literals of :L847, :L848 and :L849 arrive in order"),
                    () -> assertTrue(detail.contains(MISS_FILE_AND_RESPONSE),
                            "the trailing space of :L850 survives"),
                    () -> assertTrue(detail.contains(MISS_REASON),
                            "the upper case of :L852 survives"),
                    () -> assertFalse(detail.contains(ACCOUNT_REASON_SPELLING),
                            "and the mixed-case spelling of :L802 reaches no customer text"),
                    () -> assertTrue(detail.length() <= 75,
                            "inside WS-RETURN-MSG PIC X(75) at :L117"));
        }

        /**
         * The four literals the controller holds carry the bytes of the source.
         *
         * <p>Each constant is compared against the literal read from
         * {@code app/cbl/COACTVWC.cbl}, so a change on either side fails here.
         */
        @Test
        void theControllerLiteralsCarryTheSourceBytes() {
            assertAll("one spelling on each side",
                    () -> assertEquals(MISS_OPENING,
                            CustomerController.CUSTOMER_NOT_FOUND_OPENING,
                            "the literal of app/cbl/COACTVWC.cbl:L847"),
                    () -> assertEquals(MISS_MISSING,
                            CustomerController.CUSTOMER_NOT_FOUND_MISSING,
                            "the literal of app/cbl/COACTVWC.cbl:L849"),
                    () -> assertEquals(MISS_FILE_AND_RESPONSE,
                            CustomerController.CUSTOMER_NOT_FOUND_FILE_AND_RESPONSE,
                            "the literal of app/cbl/COACTVWC.cbl:L850, trailing space included"),
                    () -> assertEquals(MISS_REASON, CustomerController.CUSTOMER_NOT_FOUND_REASON,
                            "the literal of app/cbl/COACTVWC.cbl:L852, upper case included"));
        }

        /**
         * A miss answer carries one text, no list of field texts and no stack trace.
         *
         * <p>The guard at {@code app/cbl/COACTVWC.cbl:L845} lets one text stand per read, so one
         * text is the whole answer.
         */
        @Test
        void aMissAnswerCarriesOneTextAndNoList() throws Exception {
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

            MvcResult result = performRead();
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());

            assertAll("one text is the whole miss contract",
                    () -> assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            result.getResponse().getContentType(),
                            "a miss answers one problem document"),
                    () -> assertEquals(ApiProblem.NOT_FOUND, answer.get("title").stringValue(),
                            "under one fixed title"),
                    () -> assertAll(LIST_AND_TRACE_PROPERTIES.stream()
                            .map(absent -> () -> assertFalse(answer.has(absent),
                                    absent + " reaches no miss answer"))));
        }

        /**
         * No answer carries card data and no answer carries a card verification value.
         *
         * <p>{@code 01 CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy:L4-L23} declares neither.
         * The one name in it that mentions a card is the flag at {@code :L21}, moved at
         * {@code app/cbl/COACTVWC.cbl:L521-L522}.
         */
        @Test
        void noAnswerCarriesCardData() throws Exception {
            resolveCustomer();

            String read = readCustomerBody();
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());
            String miss = performRead().getResponse().getContentAsString();
            List<String> cardNames = propertyNamesOf(CustomerView.class).stream()
                    .filter(name -> name.toLowerCase().contains("card"))
                    .toList();

            assertAll("this service holds no card value to answer with",
                    () -> assertAll(CARD_VALUE_FRAGMENTS.stream().map(fragment -> () -> assertAll(
                            () -> assertFalse(read.toLowerCase().contains(fragment),
                                    fragment + " names nothing in a read answer"),
                            () -> assertFalse(miss.toLowerCase().contains(fragment),
                                    fragment + " names nothing in a miss answer")))),
                    () -> assertEquals(List.of(CARD_HOLDER_FLAG), cardNames,
                            "the flag of app/cpy/CVCUS01Y.cpy:L21 is the one card name"),
                    () -> assertAll(CARD_VALUE_FRAGMENTS.stream().map(fragment -> () -> assertTrue(
                            Arrays.stream(CustomerEntity.class.getDeclaredFields())
                                    .map(Field::getName)
                                    .map(String::toLowerCase)
                                    .noneMatch(name -> name.contains(fragment)),
                            "no stored field of the customer row is named " + fragment))));
        }
    }

    /** How a read resolves one customer, and what it refuses before reading. */
    @Nested
    @DisplayName("the customer resolution path")
    class TheResolutionPath {

        /**
         * A read resolves by customer identifier alone.
         *
         * <p>{@code 9000-READ-ACCT} at {@code app/cbl/COACTVWC.cbl:L687-L720} performs three hops:
         * the cross-reference read at {@code :L723}, which lands {@code XREF-CUST-ID} at
         * {@code :L739}, the account read at {@code :L774}, and the customer read at {@code :L825}.
         * The customer read performs the third hop, keyed on the identifier the path names.
         *
         * <p>The identifier reaches the store as text, keeping the leading zeros
         * {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} declares.
         */
        @Test
        void aReadResolvesByCustomerIdentifierAlone() throws Exception {
            resolveCustomer();

            readCustomer();

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(customers, times(1)).findByCustomerId(key.capture());
            assertAll("one keyed read serves the route",
                    () -> assertEquals(CUSTOMER_ID, key.getValue(),
                            "the identifier keeps its leading zeros"),
                    () -> assertEquals(PicClause.CUST_ID_WIDTH, key.getValue().length(),
                            "at the width app/cpy/CVCUS01Y.cpy:L5 declares"),
                    () -> verifyNoMoreInteractions(customers));
        }

        /**
         * The route reaches no cross-reference store.
         *
         * <p>The first hop of {@code 9000-READ-ACCT} reads the cross-reference file at
         * {@code app/cbl/COACTVWC.cbl:L723} through its alternate index. The authorization service
         * and the card service own that table, and this route holds one store.
         */
        @Test
        void theRouteReachesNoCrossReferenceStore() {
            List<String> collaborators = collaboratorTypesOf(CustomerController.class);

            assertAll("one store answers the route",
                    () -> assertEquals(List.of(CustomerRepository.class.getName()), collaborators,
                            "the customer store is the one collaborator declared"),
                    () -> assertTrue(collaborators.stream()
                            .noneMatch(type -> type.toLowerCase().contains("crossreference")),
                            "and no cross-reference store is reachable from a request"));
        }

        /**
         * A path of the wrong width is refused before the store is read.
         *
         * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} fixes the width, and
         * {@code MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID} at {@code app/cbl/COACTVWC.cbl:L708}
         * lands the digits over zeros.
         */
        @Test
        void aPathOfTheWrongWidthIsRefusedBeforeTheStoreIsRead() throws Exception {
            MvcResult result = mockMvc.perform(get("/customers/{customerId}", "1")).andReturn();

            assertAll("the width is checked before the store",
                    () -> assertEquals(422, result.getResponse().getStatus(),
                            "a path of the wrong width is a caller's to correct"),
                    () -> verifyNoInteractions(customers));
        }

        /**
         * The path pattern pins nine digits.
         *
         * <p>{@code WS-CARD-RID-CUST-ID-X PIC X(09)} at {@code app/cbl/COACTVWC.cbl:L76-L77} keys
         * the read as nine characters of text, so a shorter value matches no row.
         */
        @Test
        void thePathPatternPinsNineDigits() {
            assertAll("nine digits, and nothing else, reaches a row",
                    () -> assertTrue(CUSTOMER_ID.matches(CustomerController.CUSTOMER_ID_PATTERN),
                            "nine digits with leading zeros is the shape a row carries"),
                    () -> assertFalse("1".matches(CustomerController.CUSTOMER_ID_PATTERN),
                            "an unpadded identifier is refused"),
                    () -> assertFalse("0000000012".matches(CustomerController.CUSTOMER_ID_PATTERN),
                            "ten digits is one too many"),
                    () -> assertFalse("00000000A".matches(CustomerController.CUSTOMER_ID_PATTERN),
                            "a letter is not a digit"));
        }
    }

    /** What the route publishes, and what it could publish at all. */
    @Nested
    @DisplayName("customer read publication behaviour")
    class PublicationBehaviour {

        /**
         * No declared field of the controller could publish an event.
         *
         * <p>A read is a supporting query, so no outbox row and no event follows one.
         * {@code app/cbl/COACTVWC.cbl} carries three {@code EXEC CICS READ} operations and no
         * {@code WRITE}, {@code REWRITE} or {@code DELETE} operation across all 941 of its lines.
         */
        @Test
        void noDeclaredFieldCouldPublish() {
            List<String> reachable = Arrays.stream(CustomerController.class.getDeclaredFields())
                    .map(Field::getType)
                    .map(Class::getName)
                    .toList();

            assertAll("nothing that publishes is reachable from a request",
                    PUBLISHING_PACKAGES.stream().map(banned -> () -> assertTrue(
                            reachable.stream().noneMatch(type -> type.startsWith(banned + ".")),
                            "no declared field has a type in " + banned)));
        }

        /**
         * A read writes through no store.
         *
         * <p>The keyed read of {@code 9400-GETCUSTDATA-BYCUST} at
         * {@code app/cbl/COACTVWC.cbl:L826-L834} is the whole paragraph, and it changes nothing a
         * consumer could observe.
         */
        @Test
        void aReadWritesThroughNoStore() throws Exception {
            resolveCustomer();

            readCustomer();

            assertAll("a query writes nothing",
                    () -> verify(customers, never()).save(any()),
                    () -> verify(customers, never()).findForUpdateByCustomerId(any()));
        }
    }

    // Fixtures and helpers.

    /**
     * One text property and the value the stored row holds for it.
     *
     * @param property    the property name on the wire
     * @param storedValue the value the row holds
     */
    private record TextProperty(String property, String storedValue) {}

    /**
     * Pairs each of the fifteen text properties with the value the row holds.
     *
     * <p>The order is the order {@code app/cbl/COACTVWC.cbl:L494-L522} moves the fields, less the
     * credit score at {@code :L505-L506}, which is a number.
     *
     * @param stored the stored row
     * @return the fifteen pairs
     */
    private static List<TextProperty> textPropertiesOf(CustomerEntity stored) {
        return List.of(
                new TextProperty("customerId", stored.getCustomerId()),
                new TextProperty("dateOfBirth", stored.getDateOfBirth()),
                new TextProperty("firstName", stored.getFirstName()),
                new TextProperty("middleName", stored.getMiddleName()),
                new TextProperty("lastName", stored.getLastName()),
                new TextProperty("addressLine1", stored.getAddressLine1()),
                new TextProperty("addressLine2", stored.getAddressLine2()),
                new TextProperty("addressCity", stored.getAddressCity()),
                new TextProperty("addressStateCode", stored.getAddressStateCode()),
                new TextProperty("addressZip", stored.getAddressZip()),
                new TextProperty("addressCountryCode", stored.getAddressCountryCode()),
                new TextProperty("phoneNumber1", stored.getPhoneNumber1()),
                new TextProperty("phoneNumber2", stored.getPhoneNumber2()),
                new TextProperty("eftAccountId", stored.getEftAccountId()),
                new TextProperty("primaryCardHolderIndicator",
                        stored.getPrimaryCardHolderIndicator()));
    }

    /**
     * @param type the record type
     * @return the component names, in the order the record declares them
     */
    private static List<String> propertyNamesOf(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * @param type the class to inspect
     * @return the type names of its instance fields, in declaration order
     */
    private static List<String> collaboratorTypesOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .toList();
    }

    /** Stubs the customer store to resolve its row. */
    private void resolveCustomer() {
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));
    }

    /**
     * Reads the customer and parses the answer.
     *
     * @return the answer, parsed
     * @throws Exception when the request or the parse fails
     */
    private JsonNode readCustomer() throws Exception {
        return JSON.readTree(readCustomerBody());
    }

    /**
     * Reads the customer and returns the answer as it arrived on the wire.
     *
     * @return the raw answer
     * @throws Exception when the request fails
     */
    private String readCustomerBody() throws Exception {
        return performRead().getResponse().getContentAsString();
    }

    /**
     * Sends one read of the customer this class names.
     *
     * @return the result, carrying the answer
     * @throws Exception when the request fails
     */
    private MvcResult performRead() throws Exception {
        return mockMvc.perform(get("/customers/{customerId}", CUSTOMER_ID)).andReturn();
    }

    /**
     * Builds one stored customer row, each value at the width its Picture clause declares.
     *
     * <p>The widths come from {@code app/cpy/CVCUS01Y.cpy:L5-L22} through {@link PicClause}, so a
     * value carries the trailing spaces the fixed-width record carries.
     *
     * @return the row
     */
    private static CustomerEntity storedCustomer() {
        CustomerEntity stored = new CustomerEntity();
        stored.setCustomerId(CUSTOMER_ID);
        stored.setFirstName(atWidth("Alina", PicClause.CUST_FIRST_NAME_WIDTH));
        stored.setMiddleName(atWidth("Beatrix", PicClause.CUST_MIDDLE_NAME_WIDTH));
        stored.setLastName(atWidth("Castellan", PicClause.CUST_LAST_NAME_WIDTH));
        stored.setAddressLine1(atWidth("412 Harbour Row", PicClause.CUST_ADDR_LINE_1_WIDTH));
        stored.setAddressLine2(atWidth("Suite 19", PicClause.CUST_ADDR_LINE_2_WIDTH));
        stored.setAddressCity(atWidth("Fairhaven", PicClause.CUST_ADDR_LINE_3_WIDTH));
        stored.setAddressStateCode(atWidth("NC", PicClause.CUST_ADDR_STATE_CD_WIDTH));
        stored.setAddressCountryCode(atWidth("USA", PicClause.CUST_ADDR_COUNTRY_CD_WIDTH));
        stored.setAddressZip(atWidth("27601", PicClause.CUST_ADDR_ZIP_WIDTH));
        stored.setPhoneNumber1(atWidth("(919)555-0143", PicClause.CUST_PHONE_NUM_1_WIDTH));
        stored.setPhoneNumber2(atWidth("(919)555-0187", PicClause.CUST_PHONE_NUM_2_WIDTH));
        stored.setSocialSecurityNumber(STORED_SOCIAL_SECURITY_NUMBER);
        stored.setGovernmentIssuedId(STORED_GOVERNMENT_ISSUED_ID);
        stored.setDateOfBirth(STORED_DATE_OF_BIRTH);
        stored.setEftAccountId(atWidth("0053581756", PicClause.CUST_EFT_ACCOUNT_ID_WIDTH));
        stored.setPrimaryCardHolderIndicator(
                atWidth("Y", PicClause.CUST_PRI_CARD_HOLDER_IND_WIDTH));
        stored.setFicoCreditScore(STORED_CREDIT_SCORE);
        return stored;
    }

    /**
     * Pads one value on the right to the width a Picture clause declares.
     *
     * @param value the value the row carries
     * @param width the width the Picture clause declares
     * @return the value at that width
     */
    private static String atWidth(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}

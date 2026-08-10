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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.AccountUpdateResponse;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.domain.AccountSnapshot;
import com.carddemo.account.domain.AccountUpdateOutcome;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.ConcurrentChangeDetector;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.cobol.PicClause;
import java.lang.reflect.Field;
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
 * Web-layer tests for {@link AccountController}, driving both routes and reading each answer off
 * the wire.
 *
 * <p>Two Customer Information Control System (CICS) transactions reach this class.
 * {@code app/cbl/COACTVWC.cbl} displays one account, and {@code app/cbl/COACTUPC.cbl} updates one
 * account with its customer. These tests assert the shape of each answer: which properties arrive,
 * in what order, and in what form.
 *
 * <p>The dispatcher is built over the controller and the real {@link AccountApiExceptionHandler},
 * so request mapping, path-variable binding, method validation, body binding and serialization are
 * all the running ones. No application context, no database, no broker and no container takes part.
 * One command runs this class on a clean machine.
 *
 * <p>Every collaborator is stubbed. Nothing here re-tests a field edit, a concurrency check or an
 * outbox row. {@code domain/AccountUpdateServiceTest}, {@code domain/validation/} and the classes
 * under {@code outbox/} own those, and {@code repository/SchemaColumnTypeTest} owns every
 * schema-level claim.
 *
 * <p>Rationale for the two-hop resolution asserted here, against the three hops
 * {@code 9000-READ-ACCT} performs at {@code app/cbl/COACTVWC.cbl:L687-L720}:
 * {@code card-platform/docs/decision-log.md}. The three guards whose setters are commented out at
 * {@code app/cbl/COACTVWC.cbl:L696}, {@code :L792} and {@code :L842}:
 * {@code card-platform/docs/business-rule-flags.md}. Field mapping, among it the corrected spelling
 * of {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11}:
 * {@code card-platform/docs/traceability-matrix.md}.
 */
@DisplayName("the account read and update surface")
class AccountControllerTest {

    /** An account identifier filling {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. */
    private static final String ACCOUNT_ID = "00000000050";

    /** A customer identifier filling {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}. */
    private static final String CUSTOMER_ID = "000000050";

    /** A postal code filling {@code ACCT-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L15}. */
    private static final String STORED_ADDRESS_ZIP = "72112";

    /**
     * A value of the width {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17} declares.
     * No response of this platform carries the column, and the assertions below hold that.
     */
    private static final String STORED_SOCIAL_SECURITY_NUMBER = "429541163";

    /**
     * A value of the width {@code CUST-GOVT-ISSUED-ID PIC X(20)} at
     * {@code app/cpy/CVCUS01Y.cpy:L18} declares, withheld from every response on the same terms.
     */
    private static final String STORED_GOVERNMENT_ISSUED_ID = "AR8829114";

    /**
     * The eleven read properties, in the order {@code 1200-SETUP-SCREEN-VARS} moves them.
     *
     * <p>The paragraph label sits at {@code app/cbl/COACTVWC.cbl:L460}. The account block is gated
     * at {@code :L471-L472}. Each move carries its own locator.
     *
     * <ul>
     *   <li>{@code CC-ACCT-ID} at {@code :L468}</li>
     *   <li>{@code ACCT-ACTIVE-STATUS} at {@code :L473}</li>
     *   <li>{@code ACCT-CURR-BAL} at {@code :L475}</li>
     *   <li>{@code ACCT-CREDIT-LIMIT} at {@code :L477}</li>
     *   <li>{@code ACCT-CASH-CREDIT-LIMIT} at {@code :L479-L480}</li>
     *   <li>{@code ACCT-CURR-CYC-CREDIT} at {@code :L482-L483}</li>
     *   <li>{@code ACCT-CURR-CYC-DEBIT} at {@code :L485}</li>
     *   <li>{@code ACCT-OPEN-DATE} at {@code :L487}</li>
     *   <li>{@code ACCT-EXPIRAION-DATE} at {@code :L488}</li>
     *   <li>{@code ACCT-REISSUE-DATE} at {@code :L489}</li>
     *   <li>{@code ACCT-GROUP-ID} at {@code :L490}</li>
     * </ul>
     */
    private static final List<String> VIEW_PROPERTIES_IN_SOURCE_ORDER = List.of(
            "accountId", "activeStatus", "currentBalance", "creditLimit", "cashCreditLimit",
            "currentCycleCredit", "currentCycleDebit", "openDate", "expirationDate", "reissueDate",
            "groupId");

    /**
     * The five monetary read properties paired with the scale their source field declares.
     *
     * <p>{@code app/cpy/CVACT01Y.cpy} declares {@code PIC S9(10)V99} at {@code :L7} for the
     * balance, at {@code :L8} and {@code :L9} for the two credit limits, and at {@code :L13} and
     * {@code :L14} for the two cycle accumulators. Each scale is read from {@link PicClause}.
     */
    private static final List<MonetaryProperty> MONETARY_PROPERTIES = List.of(
            new MonetaryProperty("currentBalance", PicClause.ACCT_CURR_BAL_SCALE),
            new MonetaryProperty("creditLimit", PicClause.ACCT_CREDIT_LIMIT_SCALE),
            new MonetaryProperty("cashCreditLimit", PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE),
            new MonetaryProperty("currentCycleCredit", PicClause.ACCT_CURR_CYC_CREDIT_SCALE),
            new MonetaryProperty("currentCycleDebit", PicClause.ACCT_CURR_CYC_DEBIT_SCALE));

    /**
     * The three date read properties paired with the width their source field declares.
     *
     * <p>{@code app/cpy/CVACT01Y.cpy} declares {@code PIC X(10)} at {@code :L10}, {@code :L11} and
     * {@code :L12}. Each width is read from {@link PicClause}.
     */
    private static final List<DateProperty> DATE_PROPERTIES = List.of(
            new DateProperty("openDate", PicClause.ACCT_OPEN_DATE_WIDTH),
            new DateProperty("expirationDate", PicClause.ACCT_EXPIRATION_DATE_WIDTH),
            new DateProperty("reissueDate", PicClause.ACCT_REISSUE_DATE_WIDTH));

    /**
     * Property names that would carry a list of field texts or a stack trace.
     *
     * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479} is one field under
     * the guard {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code :L480}, so one text is all a
     * validation pass produces. No answer of this class carries any of these.
     */
    private static final List<String> LIST_AND_TRACE_PROPERTIES = List.of(
            "messages", "errors", "violations", "fieldErrors", "timestamp", "trace");

    /** Packages holding a type that could publish an event. */
    private static final List<String> PUBLISHING_PACKAGES = List.of(
            "com.carddemo.account.outbox", "com.carddemo.account.messaging",
            "com.carddemo.account.config", "com.carddemo.events");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AccountRepository accounts;
    private CustomerRepository customers;
    private AccountUpdateService accountUpdates;
    private MockMvc mockMvc;

    @BeforeEach
    void buildSlice() {
        accounts = mock(AccountRepository.class);
        customers = mock(CustomerRepository.class);
        accountUpdates = mock(AccountUpdateService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(validating(
                        new AccountController(accounts, customers, accountUpdates)))
                .setControllerAdvice(new AccountApiExceptionHandler())
                .build();
    }

    /**
     * Wraps the controller so its {@code @Validated} method constraints are enforced.
     *
     * <p>A running service gets this from {@code spring-boot-starter-validation}, which proxies
     * every {@code @Validated} bean. A standalone dispatcher registers no post-processor, so without
     * the wrapper the {@code @Pattern} on the path variable would be inert.
     *
     * @param controller the controller to wrap
     * @return the controller behind a validating proxy
     */
    private static AccountController validating(AccountController controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.afterPropertiesSet();
        return (AccountController) processor.postProcessAfterInitialization(controller,
                AccountController.class.getSimpleName());
    }

    /** The eleven properties a read answers, their order, and the form each takes. */
    @Nested
    @DisplayName("the eleven account view properties")
    class TheElevenAccountViewProperties {

        /**
         * A read answers exactly eleven properties and no twelfth.
         *
         * <p>{@code 1200-SETUP-SCREEN-VARS} at {@code app/cbl/COACTVWC.cbl:L460} moves eleven
         * values, at {@code :L468} and {@code :L473-L490}.
         */
        @Test
        void aReadAnswersExactlyElevenProperties() throws Exception {
            resolveAccount();

            JsonNode answer = readAccount();

            assertAll("the read carries the eleven values of the source paragraph",
                    () -> assertEquals(11, answer.size(),
                            "eleven properties arrive and no twelfth"),
                    () -> assertEquals(VIEW_PROPERTIES_IN_SOURCE_ORDER,
                            List.copyOf(answer.propertyNames()),
                            "and they are the eleven the paragraph moves"));
        }

        /**
         * The eleven properties arrive in the order the source paragraph moves them.
         *
         * <p>The order runs from {@code app/cbl/COACTVWC.cbl:L468} to {@code :L490}. Each name is
         * located in the raw answer, and the positions strictly increase.
         */
        @Test
        void thePropertiesArriveInTheSourceMoveOrder() throws Exception {
            resolveAccount();

            String wire = readAccountBody();

            int previous = -1;
            for (String property : VIEW_PROPERTIES_IN_SOURCE_ORDER) {
                int position = wire.indexOf('"' + property + '"');
                assertTrue(position > previous,
                        property + " follows the property the paragraph moves before it");
                previous = position;
            }
        }

        /**
         * The view declares no postal code property.
         *
         * <p>{@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy:L15} has zero references across
         * the whole of {@code app/cbl/COACTVWC.cbl}, so the display program never reads it. The
         * column is stored and the entity maps it.
         */
        @Test
        void theViewCarriesNoPostalCode() throws Exception {
            resolveAccount();

            JsonNode answer = readAccount();

            assertAll("the postal code the display program never reads reaches no property",
                    () -> assertFalse(answer.has("addressZip"),
                            "no postal code property arrives"),
                    () -> assertFalse(propertyNamesOf(AccountView.class).contains("addressZip"),
                            "and the record declares none"),
                    () -> assertFalse(readAccountBody().contains(STORED_ADDRESS_ZIP),
                            "so the stored value reaches no answer"));
        }

        /**
         * The view declares no customer identifier property.
         *
         * <p>{@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17} declares twelve fields
         * at {@code :L5-L16}, and none is a customer identifier. The source resolves one through
         * the cross-reference read at {@code app/cbl/COACTVWC.cbl:L723}.
         */
        @Test
        void theViewCarriesNoCustomerIdentifier() throws Exception {
            resolveAccount();

            JsonNode answer = readAccount();
            List<String> declared = propertyNamesOf(AccountView.class);

            assertAll("an account record names no customer, so the view names none",
                    () -> assertFalse(answer.has("customerId"),
                            "no customer identifier property arrives"),
                    () -> assertFalse(declared.contains("customerId"),
                            "and the record declares none"),
                    () -> assertTrue(declared.stream()
                            .noneMatch(name -> name.toLowerCase().contains("customer")),
                            "and no property of the view names a customer at all"));
        }

        /**
         * Each monetary property arrives as text at the scale its source field declares.
         *
         * <p>The five {@code PIC S9(10)V99} fields sit at {@code app/cpy/CVACT01Y.cpy:L7},
         * {@code :L8}, {@code :L9}, {@code :L13} and {@code :L14}. Each scale is read from
         * {@link PicClause}.
         */
        @Test
        void eachMonetaryPropertyArrivesAsTextAtItsDeclaredScale() throws Exception {
            resolveAccount();

            JsonNode answer = readAccount();

            assertAll(MONETARY_PROPERTIES.stream().map(money -> () -> {
                JsonNode value = answer.get(money.property());
                assertTrue(value.isString(),
                        money.property() + " arrives as text and never as a number");
                String text = value.stringValue();
                assertEquals(money.scale(), text.length() - text.indexOf('.') - 1,
                        money.property() + " keeps the fractional digits its scale declares");
            }));
        }

        /**
         * Each date property arrives as text at the width its source field declares.
         *
         * <p>The three {@code PIC X(10)} fields sit at {@code app/cpy/CVACT01Y.cpy:L10},
         * {@code :L11} and {@code :L12}. {@code app/cbl/CBTRN02C.cbl:L414-L420} compares the
         * expiry as text against the leading characters of a timestamp, so the ten characters
         * stand.
         */
        @Test
        void eachDatePropertyArrivesAsTextAtItsDeclaredWidth() throws Exception {
            resolveAccount();

            JsonNode answer = readAccount();

            assertAll(DATE_PROPERTIES.stream().map(date -> () -> {
                JsonNode value = answer.get(date.property());
                assertTrue(value.isString(), date.property() + " arrives as text");
                assertEquals(date.width(), value.stringValue().length(),
                        date.property() + " keeps the characters its width declares");
            }));
        }

        /**
         * The expiry property carries the corrected spelling.
         *
         * <p>The source field is {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11},
         * and the display program moves it at {@code app/cbl/COACTVWC.cbl:L488}.
         */
        @Test
        void theExpiryPropertyCarriesTheCorrectedSpelling() {
            List<String> declared = propertyNamesOf(AccountView.class);

            assertAll("one spelling reaches the wire",
                    () -> assertTrue(declared.contains("expirationDate"),
                            "the corrected spelling is the property name"),
                    () -> assertFalse(declared.contains("expiraionDate"),
                            "and the source spelling reaches no property"));
        }
    }

    /** The one message slot every answer carries. */
    @Nested
    @DisplayName("the single message slot")
    class TheSingleMessageSlot {

        /**
         * An accepted update answers exactly two properties.
         *
         * <p>{@link AccountUpdateResponse} models one text and the resulting account.
         * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479} is that one text,
         * open when it holds spaces per {@code :L480}.
         */
        @Test
        void anAcceptedUpdateAnswersExactlyTwoProperties() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            JsonNode answer = JSON.readTree(updateAccount(bodyNamingTheCustomer()).getResponse()
                    .getContentAsString());

            assertAll("one text and one account, and nothing else",
                    () -> assertEquals(2, answer.size(), "two properties arrive and no third"),
                    () -> assertEquals(List.of("message", "account"),
                            List.copyOf(answer.propertyNames()),
                            "and they are the two the record declares"),
                    () -> assertEquals(2, AccountUpdateResponse.class.getRecordComponents().length,
                            "the record declares two components"));
        }

        /**
         * An accepted update nests the resulting account under one property.
         *
         * <p>The eleven values are the same eleven {@code app/cbl/COACTVWC.cbl:L468-L490} moves,
         * so a caller reads the row as it now stands without a second call.
         */
        @Test
        void anAcceptedUpdateNestsTheViewUnderAccount() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            JsonNode answer = JSON.readTree(updateAccount(bodyNamingTheCustomer()).getResponse()
                    .getContentAsString());
            JsonNode account = answer.get("account");

            assertAll("the nested account is one whole view",
                    () -> assertEquals(AccountController.UPDATE_APPLIED_MESSAGE,
                            answer.get("message").stringValue(),
                            "the accepted text of app/cbl/COACTUPC.cbl:L474-L475"),
                    () -> assertEquals(11, account.size(), "eleven nested properties arrive"),
                    () -> assertEquals(VIEW_PROPERTIES_IN_SOURCE_ORDER,
                            List.copyOf(account.propertyNames()),
                            "in the order the source paragraph moves them"));
        }

        /**
         * A refused field answers one text in one slot.
         *
         * <p>The text arrives character for character. {@code app/cbl/COACTUPC.cbl:L2209} supplies
         * {@code ' is not valid'} with no trailing period, and no answer of this class adds one.
         */
        @Test
        void aRefusedFieldAnswersOneTextInOneSlot() throws Exception {
            String refusal = AccountDataRequest.CREDIT_LIMIT_LABEL
                    + AccountDataRequest.IS_NOT_VALID_MESSAGE_SUFFIX;
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(AccountUpdateOutcome.of(EditResult.failure(refusal)));

            MvcResult result = updateAccount(bodyNamingTheCustomer());
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());

            assertAll("one text, in one slot, unaltered",
                    () -> assertEquals(422, result.getResponse().getStatus(),
                            "a refused field is a caller's to correct"),
                    () -> assertEquals(refusal, answer.get("detail").stringValue(),
                            "the text arrives with the spacing the source supplies"),
                    () -> assertFalse(answer.get("detail").stringValue().endsWith("."),
                            "and no trailing period is added to it"));
        }

        /**
         * A failing answer carries no list of field texts and no stack trace.
         *
         * <p>The guard at {@code app/cbl/COACTUPC.cbl:L480} lets one text stand per validation
         * pass, and {@code :L876} reopens the slot once per pass.
         */
        @Test
        void aFailingAnswerCarriesNoListAndNoTrace() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(AccountUpdateOutcome.of(EditResult.failure(AccountDataRequest.CURRENT_BALANCE_LABEL
                            + AccountDataRequest.IS_NOT_VALID_MESSAGE_SUFFIX)));

            MvcResult result = updateAccount(bodyNamingTheCustomer());
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());

            assertAll("one text is the whole error contract",
                    () -> assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            result.getResponse().getContentType(),
                            "a refusal answers one problem document"),
                    () -> assertAll(LIST_AND_TRACE_PROPERTIES.stream()
                            .map(absent -> () -> assertFalse(answer.has(absent),
                                    absent + " reaches no failing answer"))));
        }

        /**
         * A lost race answers the concurrency text in that same slot.
         *
         * <p>{@link ConcurrentChangeDetector#RECORD_CHANGED_MESSAGE} is the verdict of
         * {@code app/cbl/COACTUPC.cbl:L3950-L3952}, whose literal sits at {@code :L521-L522}. A
         * lost race is a caller's to retry, so it answers a status of its own.
         */
        @Test
        void aLostRaceAnswersTheConcurrencyTextInThatSameSlot() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any())).thenReturn(AccountUpdateOutcome.of(
                    EditResult.failure(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE)));

            MvcResult result = updateAccount(bodyNamingTheCustomer());
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());

            assertAll("the concurrency verdict reaches the caller unaltered",
                    () -> assertEquals(409, result.getResponse().getStatus(),
                            "a lost race is a caller's to retry"),
                    () -> assertEquals(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE,
                            answer.get("detail").stringValue(),
                            "carrying the text of app/cbl/COACTUPC.cbl:L521-L522"));
        }

        /**
         * A passing verdict that carries its own text answers that text.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L1463-L1467} returns when it finds nothing changed, and
         * that outcome wrote no row.
         */
        @Test
        void aPassingVerdictCarryingATextAnswersThatText() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any())).thenReturn(
                    outcomeCarrying(new EditResult(true, AccountUpdateService.NO_CHANGE_DETECTED)));

            JsonNode answer = JSON.readTree(updateAccount(bodyNamingTheCustomer()).getResponse()
                    .getContentAsString());

            assertEquals(AccountUpdateService.NO_CHANGE_DETECTED,
                    answer.get("message").stringValue(),
                    "the no-change text of app/cbl/COACTUPC.cbl:L1463-L1467 stands");
        }
    }

    /** What each route publishes, and what it could publish at all. */
    @Nested
    @DisplayName("publication behaviour")
    class PublicationBehaviour {

        /**
         * A read touches no collaborator that mutates.
         *
         * <p>An account read is a supporting query. {@code 9300-GETACCTDATA-BYACCT} at
         * {@code app/cbl/COACTVWC.cbl:L774-L784} performs one keyed read and writes nothing.
         */
        @Test
        void aReadTouchesNoMutatingCollaborator() throws Exception {
            resolveAccount();

            readAccount();

            assertAll("a query writes nothing and publishes nothing",
                    () -> verifyNoInteractions(accountUpdates),
                    () -> verify(accounts, never()).save(any()),
                    () -> verifyNoInteractions(customers));
        }

        /**
         * An update delegates exactly once to the collaborator that owns the write.
         *
         * <p>{@code app/cbl/COACTUPC.cbl} rewrites two files in one unit of work,
         * {@code REWRITE FILE(LIT-ACCTFILENAME)} at {@code :L4066} and
         * {@code REWRITE FILE(LIT-CUSTFILENAME)} at {@code :L4086}. {@link AccountUpdateService}
         * opens the one transaction both rows and the outbox row commit in, so one delegation is
         * one outbox row.
         */
        @Test
        void anUpdateDelegatesExactlyOnce() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            updateAccount(bodyNamingTheCustomer());

            verify(accountUpdates, times(1)).updateAccount(any(), any(), any(), any());
            verifyNoMoreInteractions(accountUpdates);
        }

        /**
         * No declared field of the controller could publish an event.
         *
         * <p>Request handling reaches no publisher, no outbox writer and no event type, so a read
         * cannot publish whatever it does.
         *
         * <p>{@code app/cbl/COACTVWC.cbl} carries three {@code EXEC CICS READ} operations and zero
         * {@code WRITE}, {@code REWRITE} or {@code DELETE} operations across all 941 of its lines,
         * so the display transaction changes nothing a consumer could observe.
         */
        @Test
        void noDeclaredFieldCouldPublish() {
            List<String> reachable = Arrays.stream(AccountController.class.getDeclaredFields())
                    .map(Field::getType)
                    .map(Class::getName)
                    .toList();

            assertAll("nothing that publishes is reachable from a request",
                    PUBLISHING_PACKAGES.stream().map(banned -> () -> assertTrue(
                            reachable.stream().noneMatch(type -> type.startsWith(banned + ".")),
                            "no declared field has a type in " + banned)));
        }

        /**
         * An update writes through no store of its own.
         *
         * <p>The two rewrites of {@code app/cbl/COACTUPC.cbl:L4066} and {@code :L4086} belong to
         * one collaborator here, and this route reaches neither store to write.
         */
        @Test
        void anUpdateWritesThroughNoStoreOfItsOwn() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            updateAccount(bodyNamingTheCustomer());

            assertAll("one collaborator owns the write",
                    () -> verify(accounts, never()).save(any()),
                    () -> verify(customers, never()).save(any()));
        }
    }

    /** How a read resolves one account, and what a miss answers. */
    @Nested
    @DisplayName("the resolution path")
    class TheResolutionPath {

        /**
         * A read resolves by account identifier alone.
         *
         * <p>{@code 9000-READ-ACCT} at {@code app/cbl/COACTVWC.cbl:L687-L720} performs three hops:
         * the cross-reference read at {@code :L723}, the account read at {@code :L774}, and the
         * customer read at {@code :L825}. This service holds no cross-reference table, and
         * {@code GET /customers/{customerId}} answers the customer.
         *
         * <p>The identifier reaches the store as text, keeping the leading zeros
         * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} declares.
         */
        @Test
        void aReadResolvesByAccountIdentifierAlone() throws Exception {
            resolveAccount();

            readAccount();

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(accounts, times(1)).findByAccountId(key.capture());
            assertAll("one keyed read serves the route",
                    () -> assertEquals(ACCOUNT_ID, key.getValue(),
                            "the identifier keeps its leading zeros"),
                    () -> verifyNoMoreInteractions(accounts));
        }

        /**
         * A read that missed answers the source text and no customer data.
         *
         * <p>{@code 9300-GETACCTDATA-BYACCT} concatenates four literals around the identifier at
         * {@code app/cbl/COACTVWC.cbl:L796-L806}. The spacing of each survives unaltered.
         *
         * <ul>
         *   <li>{@code 'Account:'} at {@code :L797}, then the identifier at {@code :L798}</li>
         *   <li>{@code ' not found in'} at {@code :L799}</li>
         *   <li>{@code ' Acct Master file.Resp:'} at {@code :L800}, carrying no space on either
         *       side of the colon</li>
         *   <li>{@code ' Reas:'} at {@code :L802}, in mixed case</li>
         * </ul>
         */
        @Test
        void aReadThatMissedAnswersTheSourceTextAndNoCustomerData() throws Exception {
            when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            MvcResult result = mockMvc.perform(get("/accounts/{accountId}", ACCOUNT_ID))
                    .andReturn();
            String wire = result.getResponse().getContentAsString();
            JsonNode answer = JSON.readTree(wire);

            assertAll("the miss text arrives whole, and it names one account",
                    () -> assertEquals(404, result.getResponse().getStatus(),
                            "a row this service does not hold answers 404"),
                    () -> assertTrue(answer.get("detail").stringValue().startsWith(
                            AccountController.ACCOUNT_NOT_FOUND_OPENING + ACCOUNT_ID
                                    + AccountController.ACCOUNT_NOT_FOUND_MISSING),
                            "the first three literals arrive in order"),
                    () -> assertTrue(answer.get("detail").stringValue().contains(
                            AccountController.ACCOUNT_NOT_FOUND_FILE_AND_RESPONSE),
                            "the spacing of app/cbl/COACTVWC.cbl:L800 survives"),
                    () -> assertTrue(answer.get("detail").stringValue().contains(
                            AccountController.ACCOUNT_NOT_FOUND_REASON),
                            "and the mixed case of app/cbl/COACTVWC.cbl:L802 survives"),
                    () -> assertFalse(wire.contains(STORED_SOCIAL_SECURITY_NUMBER),
                            "no customer value reaches a miss answer"),
                    () -> assertFalse(wire.contains("Arlene"),
                            "and no customer name reaches it"));
        }

        /**
         * An update naming a customer this service does not hold answers the miss outcome.
         *
         * <p>The customer read of {@code 9400-GETCUSTDATA-BYCUST} at
         * {@code app/cbl/COACTVWC.cbl:L825} is keyed strictly on the customer identifier, and this
         * route reads it the same way.
         */
        @Test
        void anUpdateNamingNoStoredCustomerAnswersTheMissOutcome() throws Exception {
            when(accounts.findByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(storedAccount()));
            when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

            MvcResult result = updateAccount(bodyNamingTheCustomer());
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());

            assertAll("a missing row answers the miss outcome and writes nothing",
                    () -> assertEquals(404, result.getResponse().getStatus(),
                            "a row this service does not hold answers 404"),
                    () -> assertEquals(ApiProblem.NOT_FOUND_DETAIL,
                            answer.get("detail").stringValue(),
                            "carrying the one miss text this service writes"),
                    () -> verifyNoInteractions(accountUpdates));
        }

        /**
         * A path carrying anything but eleven digits is refused before any store is read.
         *
         * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} fixes the width, and
         * {@code MOVE WS-CARD-RID-ACCT-ID-X TO WS-CARD-RID-ACCT-ID} at
         * {@code app/cbl/COACTVWC.cbl:L691} lands the digits over zeros.
         */
        @Test
        void aPathOfTheWrongWidthIsRefusedBeforeAnyStoreIsRead() throws Exception {
            MvcResult result = mockMvc.perform(get("/accounts/{accountId}", "50")).andReturn();

            assertAll("the width is checked before the store",
                    () -> assertEquals(422, result.getResponse().getStatus(),
                            "a path of the wrong width is a caller's to correct"),
                    () -> verifyNoInteractions(accounts));
        }
    }

    /** The blocks an update body carries, and how the stored pair is read. */
    @Nested
    @DisplayName("the update request contract")
    class TheUpdateRequestContract {

        /**
         * The request declares two blocks, of ten and twenty components.
         *
         * <p>{@code ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl:L757} groups the submitted
         * screen fields, and the customer block opens with {@code ACUP-NEW-CUST-ID-X} at
         * {@code :L798}. The three Social Security parts are modelled apart, as
         * {@code app/cbl/COACTUPC.cbl:L1607} reads them.
         */
        @Test
        void theRequestDeclaresTwoBlocksOfTenAndTwenty() {
            assertAll("the submitted shape matches the screen the source submits",
                    () -> assertEquals(List.of("accountData", "customerData"),
                            propertyNamesOf(AccountUpdateRequest.class),
                            "two blocks, and no identifier component beside them"),
                    () -> assertEquals(10, AccountDataRequest.class.getRecordComponents().length,
                            "ten account components"),
                    () -> assertEquals(20, CustomerDataRequest.class.getRecordComponents().length,
                            "twenty customer components"),
                    () -> assertTrue(propertyNamesOf(CustomerDataRequest.class).containsAll(
                            List.of("socialSecurityPart1", "socialSecurityPart2",
                                    "socialSecurityPart3")),
                            "with the three Social Security parts modelled apart"));
        }

        /**
         * The path identifier is the only identifier the update route reads.
         *
         * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} is the key, and
         * {@code MOVE WS-CARD-RID-ACCT-ID-X TO WS-CARD-RID-ACCT-ID} at
         * {@code app/cbl/COACTVWC.cbl:L691} lands it. {@code ACUP-NEW-DETAILS} at
         * {@code app/cbl/COACTUPC.cbl:L757} groups the submitted fields and names no account.
         *
         * <p>A body naming a second account would let a caller entitled to one account write
         * another.
         */
        @Test
        void thePathIdentifierIsTheOnlyOneTheRouteReads() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            updateAccount(bodyNamingTheCustomer());

            ArgumentCaptor<AccountEntity> proposed = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountUpdates).updateAccount(proposed.capture(), any(), any(), any());
            assertAll("the path names the account written",
                    () -> assertEquals(ACCOUNT_ID, proposed.getValue().getAccountId(),
                            "the account the caller is scoped against is the account written"),
                    () -> assertFalse(propertyNamesOf(AccountUpdateRequest.class)
                            .contains("accountId"),
                            "and the body declares no identifier component"));
        }

        /**
         * Both stored rows are read by their text identifiers.
         *
         * <p>Each store keys on the identifier as text, so a value keeps the leading zeros its
         * Picture clause declares. {@code ACCT-ID PIC 9(11)} sits at
         * {@code app/cpy/CVACT01Y.cpy:L5} and {@code CUST-ID PIC 9(09)} at
         * {@code app/cpy/CVCUS01Y.cpy:L5}.
         *
         * <p>An accepted update reads each row exactly once. That one read supplies the pair the
         * caller was shown, which {@code app/cbl/COACTUPC.cbl} holds in
         * {@code ACUP-OLD-ACCT-DATA}. The row as the write left it comes back inside
         * {@link com.carddemo.account.domain.AccountUpdateOutcome}, read off the locked row while
         * the transaction still held it, so no second statement is issued to report it. The count is
         * asserted rather than the calls, because a re-read would be invisible to every other
         * assertion here: it answers the same values, and only the number of statements changes.
         */
        @Test
        void bothStoredRowsAreReadByTheirTextIdentifiers() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            updateAccount(bodyNamingTheCustomer());

            assertAll("the pair is read by two text keys, once each",
                    () -> verify(accounts, times(1)).findByAccountId(ACCOUNT_ID),
                    () -> verify(customers, times(1)).findByCustomerId(CUSTOMER_ID));
        }

        /**
         * A body naming no customer is refused with one text.
         *
         * <p>{@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4-L17} declares no customer
         * identifier, so a caller names the customer it means to write.
         */
        @Test
        void aBodyNamingNoCustomerIsRefusedWithOneText() throws Exception {
            MvcResult result = updateAccount("{}");
            JsonNode answer = JSON.readTree(result.getResponse().getContentAsString());

            assertAll("the customer is named by the caller",
                    () -> assertEquals(422, result.getResponse().getStatus(),
                            "an unnamed customer is a caller's to correct"),
                    () -> assertEquals(AccountController.CUSTOMER_ID_REQUIRED_MESSAGE,
                            answer.get("detail").stringValue(),
                            "carrying the one text this branch writes"),
                    () -> verifyNoInteractions(accountUpdates));
        }

        /**
         * Neither identity document reaches a read answer or an update answer.
         *
         * <p>{@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy:L17} and
         * {@code CUST-GOVT-ISSUED-ID} at {@code :L18} are both stored, and the source moves them
         * to the screen at {@code app/cbl/COACTVWC.cbl:L496-L504} and {@code :L519}.
         */
        @Test
        void neitherIdentityDocumentReachesAnAnswer() throws Exception {
            resolveBoth();
            when(accountUpdates.updateAccount(any(), any(), any(), any()))
                    .thenReturn(outcomeCarrying(EditResult.ok()));

            String read = readAccountBody();
            String updated = updateAccount(bodyNamingTheCustomer()).getResponse()
                    .getContentAsString();

            assertAll("neither document travels in either answer",
                    () -> assertFalse(read.contains(STORED_SOCIAL_SECURITY_NUMBER),
                            "the read answer carries no Social Security Number"),
                    () -> assertFalse(read.contains(STORED_GOVERNMENT_ISSUED_ID),
                            "the read answer carries no government-issued identifier"),
                    () -> assertFalse(updated.contains(STORED_SOCIAL_SECURITY_NUMBER),
                            "the update answer carries no Social Security Number"),
                    () -> assertFalse(updated.contains(STORED_GOVERNMENT_ISSUED_ID),
                            "the update answer carries no government-issued identifier"));
        }
    }

    // Fixtures and helpers.

    /**
     * One monetary read property and the scale its source field declares.
     *
     * @param property the property name on the wire
     * @param scale    the scale from {@link PicClause}
     */
    private record MonetaryProperty(String property, int scale) {}

    /**
     * One date read property and the width its source field declares.
     *
     * @param property the property name on the wire
     * @param width    the width from {@link PicClause}
     */
    private record DateProperty(String property, int width) {}

    /**
     * @param type the record type
     * @return the component names, in the order the record declares them
     */
    private static List<String> propertyNamesOf(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** Stubs the account store to resolve its row. */
    private void resolveAccount() {
        when(accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(storedAccount()));
    }

    /** Stubs both stores to resolve their rows. */
    private void resolveBoth() {
        resolveAccount();
        when(customers.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(storedCustomer()));
    }

    /**
     * Reads one account and parses the answer.
     *
     * @return the answer, parsed
     * @throws Exception when the request or the parse fails
     */
    private JsonNode readAccount() throws Exception {
        return JSON.readTree(readAccountBody());
    }

    /**
     * Reads one account and returns the answer as it arrived on the wire.
     *
     * @return the raw answer
     * @throws Exception when the request fails
     */
    private String readAccountBody() throws Exception {
        return mockMvc.perform(get("/accounts/{accountId}", ACCOUNT_ID))
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Submits one update body.
     *
     * @param body the body to submit
     * @return the result, carrying the answer
     * @throws Exception when the request fails
     */
    private MvcResult updateAccount(String body) throws Exception {
        return mockMvc.perform(put("/accounts/{accountId}", ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    /**
     * @return the body
     */
    private static String bodyNamingTheCustomer() {
        return "{\"customerData\":{\"customerId\":\"" + CUSTOMER_ID + "\"}}";
    }

    /**
     * Builds the stored account row.
     *
     * @return row one of {@code app/data/ASCII/acctdata.txt}
     */
    private static AccountEntity storedAccount() {
        AccountEntity stored = new AccountEntity();
        stored.setAccountId(ACCOUNT_ID);
        stored.setActiveStatus("Y");
        stored.setCurrentBalance(new BigDecimal("1010.00"));
        stored.setCreditLimit(new BigDecimal("10000.00"));
        stored.setCashCreditLimit(new BigDecimal("5000.00"));
        stored.setOpenDate("2015-03-01");
        stored.setExpirationDate("2025-02-28");
        stored.setReissueDate("2020-03-01");
        stored.setCurrentCycleCredit(new BigDecimal("0.00"));
        stored.setCurrentCycleDebit(new BigDecimal("1010.00"));
        stored.setAddressZip(STORED_ADDRESS_ZIP);
        stored.setGroupId("ZEROAPR");
        return stored;
    }

    /**
     * Builds the stored customer row.
     *
     * @return row one of {@code app/data/ASCII/custdata.txt}
     */
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
        stored.setAddressZip(STORED_ADDRESS_ZIP);
        stored.setPhoneNumber1("(501)5551234");
        stored.setPhoneNumber2("(501)5555678");
        stored.setSocialSecurityNumber(STORED_SOCIAL_SECURITY_NUMBER);
        stored.setGovernmentIssuedId(STORED_GOVERNMENT_ISSUED_ID);
        stored.setDateOfBirth("1971-08-14");
        stored.setEftAccountId("4829571130");
        stored.setPrimaryCardHolderIndicator("Y");
        stored.setFicoCreditScore(new BigDecimal("688"));
        return stored;
    }
    /**
     * Wraps one verdict as the outcome the service now answers with, carrying the stored account.
     *
     * <p>The controller reads the account out of this outcome rather than reading the row a second
     * time after the transaction committed, so a stubbed passing verdict has to carry the snapshot
     * the real service reads off the row it wrote.
     *
     * @param verdict the verdict to carry
     * @return that verdict beside a snapshot of the stored account
     */
    private static AccountUpdateOutcome outcomeCarrying(EditResult verdict) {
        return new AccountUpdateOutcome(verdict, AccountSnapshot.of(storedAccount()));
    }

}

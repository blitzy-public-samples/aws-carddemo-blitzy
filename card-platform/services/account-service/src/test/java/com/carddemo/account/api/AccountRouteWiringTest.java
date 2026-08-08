package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Wiring tests: the four routes exist, each reaches its handler, and each agrees with its security rule.
 *
 * <p>This is the test that answers the question the other classes in this package cannot. Each of those
 * drives a controller object directly, which proves what a method does and proves nothing about whether a
 * request can reach it. Before these controllers existed, every method of
 * {@code domain/AccountUpdateService} and {@code domain/BillingCycleService} was unreachable over the
 * Hypertext Transfer Protocol while every unit test around them passed, so a suite of direct tests is
 * exactly the wrong instrument for detecting that.
 *
 * <p>Two things are checked, and together they cover the gap.
 *
 * <p>The request tests build the dispatcher over the three controllers and the real
 * {@code AccountApiExceptionHandler}, so request mapping, path-variable binding, bean validation on
 * parameters, body binding and the error path are all the real ones. What they leave out is the security
 * chain, because the slice annotation that would install it lives in a module this platform does not
 * carry offline.
 *
 * <p>The agreement test closes that gap from the other side. It reads the mappings the controllers
 * declare and compares them against the route constants {@code config/SecurityConfig} authorizes.
 * A mapping with no rule would be denied by the chain's final rule, and a rule with no mapping would
 * answer 404, and both are silent failures a running service reveals only when somebody calls the route.
 * {@code config/SecurityConfigTest} covers what each rule then decides.
 */
@DisplayName("the four account routes and their security rules")
class AccountRouteWiringTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000050";

    /** Row one of {@code app/data/ASCII/custdata.txt}, its customer identifier. */
    private static final String CUSTOMER_ID = "000000050";

    /**
     * The smallest body the update route accepts, which is a complete customer block.
     *
     * <p>It is not one component, and the reason is worth stating because it shapes the whole contract.
     * {@code CustomerDataRequest} carries a mandatory-field edit on ten of its components and a
     * cross-field check on the three Social Security parts, each reproducing an edit
     * {@code app/cbl/COACTUPC.cbl:L1470-L1676} performs. Those edits refuse an absent value exactly as
     * they refuse a blank one, so a block that is present has to be complete.
     *
     * <p>That is the 3270 screen's own contract. The screen was filled from the fetched record and
     * submitted every field, so a field the operator did not touch still arrived. What a caller may omit
     * here is a whole block, not a component of one.
     */
    private static final String MINIMAL_BODY = """
            {"customerData":{"customerId":"000000050","firstName":"Arlene","lastName":"Abshire",\
            "addressLine1":"8829 Ondricka Trail","addressCity":"North Enoshaven",\
            "addressStateCode":"AR","addressCountryCode":"USA","addressZip":"72112",\
            "socialSecurityPart1":"429","socialSecurityPart2":"54","socialSecurityPart3":"1163",\
            "eftAccountId":"4829571130","primaryCardHolderIndicator":"Y","ficoCreditScore":"688"}}""";

    private AccountRepository accounts;
    private CustomerRepository customers;
    private AccountUpdateService accountUpdates;
    private BillingCycleService billingCycles;
    private MockMvc mockMvc;

    /** Builds the dispatcher over the three controllers and the real error handler. */
    @BeforeEach
    void buildDispatcher() {
        accounts = mock(AccountRepository.class);
        customers = mock(CustomerRepository.class);
        accountUpdates = mock(AccountUpdateService.class);
        billingCycles = mock(BillingCycleService.class);

        when(accounts.findByAccountId(any())).thenReturn(Optional.of(storedAccount()));
        when(customers.findByCustomerId(any())).thenReturn(Optional.of(storedCustomer()));
        when(accountUpdates.updateAccount(any(), any(), any(), any())).thenReturn(EditResult.ok());
        when(billingCycles.closeBillingCycle(any())).thenReturn(Optional.of(storedAccount()));

        mockMvc = MockMvcBuilders
                .standaloneSetup(validating(new AccountController(accounts, customers,
                                accountUpdates)),
                        validating(new CustomerController(customers)),
                        validating(new BillingCycleController(billingCycles)))
                .setControllerAdvice(new AccountApiExceptionHandler())
                .build();
    }

    /**
     * Wraps one controller so its {@code @Validated} method constraints are enforced.
     *
     * <p>A running service gets this from {@code spring-boot-starter-validation}, which registers the
     * post-processor that proxies every {@code @Validated} bean. A standalone dispatcher registers no
     * post-processor, so without this the {@code @Pattern} on each path variable would be inert and a
     * test asserting a rejection would read a success and prove nothing.
     *
     * @param controller the controller to wrap
     * @param <T>        the controller type
     * @return the controller behind a validating proxy
     */
    @SuppressWarnings("unchecked")
    private static <T> T validating(T controller) {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.afterPropertiesSet();
        return (T) processor.postProcessAfterInitialization(controller,
                controller.getClass().getSimpleName());
    }

    /** Each route is mapped and reaches the work behind it. */
    @Nested
    @DisplayName("each route reaches its handler")
    class EachRouteReachesItsHandler {

        /** Asserts the account read is mapped and its view reaches the response. */
        @Test
        void theAccountReadIsMapped() throws Exception {
            MvcResult result = mockMvc.perform(get("/accounts/{id}", ACCOUNT_ID)).andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString().contains(ACCOUNT_ID),
                    "the handler ran and its view reached the response");
            verify(accounts).findByAccountId(ACCOUNT_ID);
        }

        /** Asserts the customer read is mapped and its view reaches the response. */
        @Test
        void theCustomerReadIsMapped() throws Exception {
            MvcResult result = mockMvc.perform(get("/customers/{id}", CUSTOMER_ID)).andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString().contains("Arlene"),
                    "the handler ran and its view reached the response");
            verify(customers).findByCustomerId(CUSTOMER_ID);
        }

        /**
         * Asserts the account update is mapped and reaches the update service.
         *
         * <p>This is the assertion that would have failed before the controller existed:
         * {@link AccountUpdateService#updateAccount} could not be called by any client at all.
         */
        @Test
        void theAccountUpdateIsMappedAndReachesTheService() throws Exception {
            MvcResult result = mockMvc.perform(put("/accounts/{id}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(MINIMAL_BODY))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString()
                            .contains(AccountController.UPDATE_APPLIED_MESSAGE),
                    "the service's verdict reached the response");
            verify(accountUpdates).updateAccount(any(), any(), any(), any());
        }

        /**
         * Asserts the cycle close is mapped and reaches the cycle service.
         *
         * <p>The same assertion for {@link BillingCycleService#closeBillingCycle}, which matters more
         * than most: without it the two accumulators never reset and available credit shrinks until
         * every transaction declines with reject code {@code 0102}.
         */
        @Test
        void theCycleCloseIsMappedAndReachesTheService() throws Exception {
            // The media type is required even though no body is sent: it is the cross-site request
            // forgery control of this route, and a call omitting it reads 415.
            MvcResult result = mockMvc.perform(post("/accounts/{id}/cycle-close", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString().contains(ACCOUNT_ID),
                    "both accumulators reached the response");
            verify(billingCycles).closeBillingCycle(ACCOUNT_ID);
        }

        /** Asserts a method no route carries is refused rather than served. */
        @Test
        void aMethodNoRouteCarriesIsRefused() throws Exception {
            assertEquals(405, mockMvc.perform(post("/accounts/{id}", ACCOUNT_ID)).andReturn()
                            .getResponse().getStatus(),
                    "the account route carries a read and an update and no other method");
            assertEquals(405, mockMvc.perform(put("/customers/{id}", CUSTOMER_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse().getStatus(),
                    "a customer is updated through the account route, in one transaction with it");
        }
    }

    /** What the real error path does with a request that is wrong rather than unauthorized. */
    @Nested
    @DisplayName("malformed requests through the real error path")
    class MalformedRequests {

        /**
         * Asserts an identifier of the wrong width answers 422 with a problem document.
         *
         * <p>The path pattern is a bean constraint on the method parameter, so the failure arrives at
         * {@link AccountApiExceptionHandler} rather than as a mapping mismatch, and the caller reads
         * which width the column holds.
         */
        @Test
        void anIdentifierOfTheWrongWidthAnswersUnprocessable() throws Exception {
            MvcResult result = mockMvc.perform(get("/accounts/{id}", "50")).andReturn();

            assertEquals(422, result.getResponse().getStatus(),
                    "two digits is not the eleven ACCT-ID at app/cpy/CVACT01Y.cpy:L5 holds");
            assertTrue(result.getResponse().getContentType()
                            .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
                    "one shape of error for the whole service");
            assertTrue(result.getResponse().getContentAsString()
                            .contains(AccountController.ACCOUNT_ID_MESSAGE),
                    "the caller reads which width the column holds");
        }

        /** Asserts a customer identifier of the wrong width answers 422 the same way. */
        @Test
        void aCustomerIdentifierOfTheWrongWidthAnswersUnprocessable() throws Exception {
            MvcResult result = mockMvc.perform(get("/customers/{id}", "50")).andReturn();

            assertEquals(422, result.getResponse().getStatus(),
                    "two digits is not the nine CUST-ID at app/cpy/CVCUS01Y.cpy:L5 holds");
            assertTrue(result.getResponse().getContentAsString()
                            .contains(CustomerController.CUSTOMER_ID_MESSAGE),
                    "the caller reads which width the column holds");
        }

        /** Asserts an unreadable body answers 400 with a problem document quoting nothing. */
        @Test
        void anUnreadableBodyAnswersBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(put("/accounts/{id}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"customerData\":"))
                    .andReturn();

            assertEquals(400, result.getResponse().getStatus(), "nothing could be read");
            assertTrue(result.getResponse().getContentAsString()
                            .contains(ApiProblem.MALFORMED_REQUEST),
                    "the caller reads one fixed title and none of the parser's own message");
        }

        /** Asserts a body naming no customer answers 422 and says which component was missing. */
        @Test
        void aBodyNamingNoCustomerAnswersUnprocessable() throws Exception {
            MvcResult result = mockMvc.perform(put("/accounts/{id}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();

            assertEquals(422, result.getResponse().getStatus(),
                    "an account record names no customer, so the caller has to");
            assertTrue(result.getResponse().getContentAsString()
                            .contains("Customer Id must be supplied"),
                    "the answer says which component was missing");
        }

        /**
         * Asserts nested field constraints do not run as a second, competing validation pass.
         *
         * <p>The domain service applies the source edits in their declared order and returns one
         * message. Cascading Bean Validation here would report a set in unspecified order before
         * that source-ordered pass can run.</p>
         */
        @Test
        void nestedBoundsDoNotPreemptTheSourceOrderedDomainValidation() throws Exception {
            MvcResult result = mockMvc.perform(put("/accounts/{id}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customerData\":{\"customerId\":\"" + CUSTOMER_ID + "\","
                                    + "\"ficoCreditScore\":\"274\"}}"))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus(),
                    "the mocked domain verdict, not a competing bean pass, decides the response");
            assertFalse(result.getResponse().getContentAsString()
                            .contains("should be between 300 and 850"),
                    "the nested record did not preempt the source-ordered domain validator");
        }
    }

    /** Agreement between the mappings and the security rules. */
    @Nested
    @DisplayName("mappings against security rules")
    class MappingsAgainstSecurityRules {

        /**
         * Asserts every mapping the three controllers declare is a route the security chain names.
         *
         * <p>The chain's final rule denies everything it has not named, so a mapping with no rule is a
         * route a caller reaches a 403 on however correct the handler behind it is. Nothing else in this
         * module would catch that.
         */
        @Test
        void everyMappingIsARouteTheChainAuthorizes() {
            Set<String> authorized = new TreeSet<>(authorizedRoutesOfSecurityConfig());
            List<String> unauthorized = new ArrayList<>();

            for (String mapping : declaredMappings()) {
                if (!authorized.contains(mapping)) {
                    unauthorized.add(mapping);
                }
            }

            assertEquals(List.of(), unauthorized,
                    "a mapping the chain does not name is denied by its final rule: " + unauthorized);
        }

        /**
         * Asserts every route the security chain names is a mapping a controller declares.
         *
         * <p>The other direction. A rule naming a route no controller serves is a rule that authorizes
         * a 404, which reads in a review like working authorization and is not.
         */
        @Test
        void everyAuthorizedRouteIsAMappingAControllerDeclares() {
            Set<String> declared = new TreeSet<>(declaredMappings());
            List<String> unmapped = new ArrayList<>();

            for (String route : authorizedRoutesOfSecurityConfig()) {
                if (!declared.contains(route)) {
                    unmapped.add(route);
                }
            }

            assertEquals(List.of(), unmapped,
                    "a rule naming no mapping authorizes a 404: " + unmapped);
        }

        /** Asserts the three routes are the exact set, so neither side carries a fourth. */
        @Test
        void theThreeRoutesAreTheExactSet() {
            assertEquals(new TreeSet<>(List.of("/accounts/{accountId}",
                            "/accounts/{accountId}/cycle-close", "/customers/{customerId}")),
                    new TreeSet<>(declaredMappings()),
                    "two Customer Information Control System transactions and one operational endpoint");
        }

        /**
         * Asserts the path patterns the controllers validate match the widths the columns hold.
         *
         * <p>A pattern admitting an unpadded identifier would admit one that matches no row, and the
         * caller would read 404 for a request that was malformed rather than absent.
         */
        @Test
        void bothPathPatternsPinTheColumnWidths() {
            assertEquals("^[0-9]{11}$", AccountController.ACCOUNT_ID_PATTERN,
                    "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5");
            assertEquals("^[0-9]{9}$", CustomerController.CUSTOMER_ID_PATTERN,
                    "CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5");
        }
    }

    /**
     * Reads every path {@code config/SecurityConfig} names in a route rule, from its source text.
     *
     * <p>The paths stay literals in that file rather than becoming constants, because a reader of a
     * filter chain should see the route table without following a reference, and because the
     * cross-module contract in {@code equivalence-tests} reads the same literals. This method therefore
     * reads the text, which is the same instrument that contract uses.
     *
     * @return the authorized routes, in the order the chain declares them
     */
    private static List<String> authorizedRoutesOfSecurityConfig() {
        String chain = readSecurityConfigSource();
        Matcher rule = SECURITY_ROUTE_RULE.matcher(chain);

        List<String> routes = new ArrayList<>();
        while (rule.find()) {
            String route = rule.group(1);
            if (!routes.contains(route)) {
                routes.add(route);
            }
        }
        assertTrue(!routes.isEmpty(),
                "config/SecurityConfig names its routes with requestMatchers(HttpMethod.X, \"path\")");
        return routes;
    }

    /** Matches one route rule of a filter chain, capturing the path it names. */
    private static final Pattern SECURITY_ROUTE_RULE =
            Pattern.compile("\\.requestMatchers\\(HttpMethod\\.\\w+,\\s*\"([^\"]+)\"\\)");

    /**
     * Reads the source of {@code config/SecurityConfig} from the module it lives in.
     *
     * <p>Walking up from the working directory finds the module root whether the test runs from the
     * module or from the aggregator, which is the difference between a test that passes on one machine
     * and a test that passes everywhere.
     *
     * @return the file as text
     */
    private static String readSecurityConfigSource() {
        Path relative = Path.of("src", "main", "java", "com", "carddemo", "account", "config",
                "SecurityConfig.java");
        Path directory = Path.of("").toAbsolutePath();
        for (int levels = 0; levels < 6 && directory != null; levels++) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    throw new AssertionError("cannot read " + candidate, unreadable);
                }
            }
            Path module = directory.resolve("services").resolve("account-service").resolve(relative);
            if (Files.isRegularFile(module)) {
                try {
                    return Files.readString(module, StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    throw new AssertionError("cannot read " + module, unreadable);
                }
            }
            directory = directory.getParent();
        }
        throw new AssertionError("cannot find " + relative + " from " + Path.of("").toAbsolutePath());
    }

    /**
     * Reads every route the three controllers map, as a class-level prefix joined to a method-level path.
     *
     * @return the mapped routes
     */
    private static List<String> declaredMappings() {
        List<String> mappings = new ArrayList<>();
        for (Class<?> controller : List.of(AccountController.class, CustomerController.class,
                BillingCycleController.class)) {
            String prefix = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                pathOf(method).ifPresent(path -> mappings.add(prefix + path));
            }
        }
        return mappings;
    }

    /**
     * Reads the method-level path of one handler method, if it declares one.
     *
     * @param method the candidate handler
     * @return the path, or empty when the method maps nothing
     */
    private static Optional<String> pathOf(Method method) {
        GetMapping read = method.getAnnotation(GetMapping.class);
        if (read != null) {
            return Optional.of(read.value()[0]);
        }
        PutMapping update = method.getAnnotation(PutMapping.class);
        if (update != null) {
            return firstOf(update.value(), update.path());
        }
        PostMapping create = method.getAnnotation(PostMapping.class);
        if (create != null) {
            // value() and path() are aliases and only one of them is populated. A mapping written
            // as @PostMapping("/x") fills value(); one written as @PostMapping(path = "/x",
            // consumes = ...) fills path() and leaves value() empty, and reading value() alone
            // failed with an index error rather than reporting the route.
            return firstOf(create.value(), create.path());
        }
        return Optional.empty();
    }

    /**
     * Returns the first path either alias carries.
     *
     * <p>{@code value} and {@code path} are aliases on every Spring mapping annotation, and reading
     * one of them alone breaks on a mapping written with the other.
     *
     * @param value the {@code value} alias
     * @param path  the {@code path} alias
     * @return the first entry of whichever alias is populated, or empty when neither is
     */
    private static Optional<String> firstOf(String[] value, String[] path) {
        if (value.length > 0) {
            return Optional.of(value[0]);
        }
        if (path.length > 0) {
            return Optional.of(path[0]);
        }
        return Optional.empty();
    }

    /** @return the stored account, from row one of app/data/ASCII/acctdata.txt */
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
        stored.setCurrentCycleDebit(new BigDecimal("0.00"));
        stored.setAddressZip("72112");
        stored.setGroupId("ZEROAPR");
        return stored;
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

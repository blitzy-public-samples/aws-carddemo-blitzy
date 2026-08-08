package com.carddemo.card.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.domain.CardQueryService;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.cobol.PanMasker;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Wiring tests: the three routes exist, each reaches its handler, and each agrees with its security
 * rule.
 *
 * <p>This is the test that answers the question the other classes in this package cannot. Each of
 * those drives a controller object directly, which proves what a method does and proves nothing about
 * whether a request can reach it. Before this controller existed, every method of
 * {@code domain/CardQueryService} was unreachable over the Hypertext Transfer Protocol and
 * {@code domain/CardUpdateService} did not exist at all, while the unit tests around the read side
 * passed.
 *
 * <p>Two things are checked, and together they cover the gap.
 *
 * <p>The request tests build the dispatcher over the controller and the real
 * {@code CardApiExceptionHandler}, so request mapping, query and header binding, method validation
 * and the error path are all the real ones. What they leave out is the security chain, because the
 * slice annotation that would install it lives in a module this platform does not carry offline.
 *
 * <p>The agreement test closes that gap from the other side. It reads the mappings the controller
 * declares and compares them against the routes {@code config/SecurityConfig} authorizes. A mapping
 * with no rule would be denied by the chain's final rule, and a rule with no mapping would answer
 * 404, and both are silent failures a running service reveals only when somebody calls the route.
 * {@code config/SecurityConfigTest} covers what each rule then decides.
 */
@DisplayName("the three card routes and their security rules")
class CardRouteWiringTest {

    /** Card number of the row the requests name, sixteen digits. */
    private static final String CARD_NUMBER = "4111111111111150";

    /** The account the card belongs to, eleven digits. */
    private static final String ACCOUNT_ID = "00000000050";

    /**
     * The irreversible token of {@link #CARD_NUMBER}, which names that card in a path and in a paging
     * cursor alike.
     */
    private static final String CARD_TOKEN = PanMasker.cardToken(CARD_NUMBER);

    /** A complete update body, which the update route accepts whole. */
    private static final String UPDATE_BODY = """
            {"embossedName":"ALEXANDER J MORGAN",\
            "expiryYear":"2029","expiryMonth":"12","expiryDay":"31","activeStatus":"N"}""";

    /** The path of the two routes that name one card, which carries that card's token. */
    private static final String CARD_PATH = "/cards/" + CARD_TOKEN;

    private CardQueryService cardQueries;
    private CardUpdateService cardUpdates;
    private MockMvc mockMvc;

    /** Builds the dispatcher over the controller and the real error handler. */
    @BeforeEach
    void buildDispatcher() {
        cardQueries = mock(CardQueryService.class);
        cardUpdates = mock(CardUpdateService.class);

        when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(page());
        when(cardQueries.listBackward(any(), any(), any(), any())).thenReturn(page());
        when(cardQueries.findByCardToken(any())).thenReturn(Optional.of(storedCard()));
        when(cardUpdates.updateCard(any(), any())).thenReturn(CardUpdateResponse.updated());

        mockMvc = MockMvcBuilders
                .standaloneSetup(validating(new CardController(cardQueries, cardUpdates)))
                .setControllerAdvice(new CardApiExceptionHandler())
                .build();
    }

    /**
     * Wraps the controller so its {@code @Validated} method constraints are enforced.
     *
     * <p>A running service gets this from {@code spring-boot-starter-validation}, which registers the
     * post-processor that proxies every {@code @Validated} bean. A standalone dispatcher registers
     * none, so without this the constraints on the query parameters and on the cursor header would be
     * inert and a test asserting a rejection would read a success and prove nothing.
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

        /** Asserts the list route is mapped and its page reaches the response. */
        @Test
        void theListRouteIsMapped() throws Exception {
            MvcResult result = mockMvc.perform(get("/cards").param("accountId", ACCOUNT_ID))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString().contains("************1150"),
                    "the handler ran and its masked row reached the response");
            verify(cardQueries).listForward(null, null, ACCOUNT_ID, null);
        }

        /** Asserts the cursor header binds and selects the browse position. */
        @Test
        void theCursorHeaderBinds() throws Exception {
            mockMvc.perform(get("/cards").param("accountId", ACCOUNT_ID)
                    .header(CardController.CURSOR_HEADER, CARD_TOKEN)).andReturn();

            verify(cardQueries).listForward(CARD_TOKEN, null, ACCOUNT_ID, null);
        }

        /** Asserts the direction parameter selects the backward browse. */
        @Test
        void theDirectionParameterSelectsTheBackwardBrowse() throws Exception {
            mockMvc.perform(get("/cards").param("accountId", ACCOUNT_ID)
                    .param("direction", "backward")).andReturn();

            verify(cardQueries).listBackward(null, null, ACCOUNT_ID, null);
        }

        /** Asserts the page size binds as a number. */
        @Test
        void thePageSizeBinds() throws Exception {
            mockMvc.perform(get("/cards").param("accountId", ACCOUNT_ID)
                    .param("pageSize", "3")).andReturn();

            verify(cardQueries).listForward(null, 3, ACCOUNT_ID, null);
        }

        /** Asserts the read route is mapped and reaches the read side. */
        @Test
        void theReadRouteIsMapped() throws Exception {
            MvcResult result = mockMvc.perform(get(CARD_PATH)).andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString().contains("************1150"),
                    "the handler ran and its masked card reached the response");
            verify(cardQueries).findByCardToken(CARD_TOKEN);
        }

        /**
         * Asserts the update route is mapped and reaches the update service.
         *
         * <p>This is the assertion that would have failed before this controller existed:
         * {@link CardUpdateService#updateCard} could not be called by any client at all.
         */
        @Test
        void theUpdateRouteIsMapped() throws Exception {
            MvcResult result = mockMvc.perform(put(CARD_PATH)
                    .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY)).andReturn();

            assertEquals(200, result.getResponse().getStatus(), "the route exists");
            assertTrue(result.getResponse().getContentAsString().contains("UPDATED"),
                    "the handler ran and the outcome reached the response");
            verify(cardUpdates).updateCard(eq(CARD_NUMBER), any());
        }

        /**
         * Asserts every mapping holds a variable name and no resolved identifier.
         *
         * <p>Two routes carry the card token as a path variable, reproducing transaction
         * {@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347-L348} and transaction {@code CCUP} at
         * {@code app/csd/CARDDEMO.CSD:L367-L369}, each of which addresses one card. A mapping is a
         * template, so it holds the variable name and never a value a caller sent.
         */
        @Test
        void everyMappingHoldsAVariableNameAndNoResolvedIdentifier() {
            for (String mapping : declaredMappings()) {
                assertFalse(mapping.matches(".*\\d{5,}.*"),
                        "a mapping holding a run of five digits would be a resolved key: "
                                + mapping);
            }
            assertTrue(declaredMappings().contains("/cards/{cardToken}"),
                    "the two routes that name one card carry the token standing for the value the "
                            + "source keys on at app/cbl/COCRDSLC.cbl:L740: " + declaredMappings());
        }
    }

    /** Malformed requests reach the real error path. */
    @Nested
    @DisplayName("malformed requests through the real error path")
    class MalformedRequestsThroughTheRealErrorPath {

        /** Asserts a missing account parameter answers 400 rather than reaching the read side. */
        @Test
        void aMissingAccountParameterAnswersBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(get("/cards")).andReturn();

            assertEquals(400, result.getResponse().getStatus(),
                    "the account the ownership rule reads is required");
            verify(cardQueries, never()).listForward(any(), any(), any(), any());
        }

        /** Asserts a malformed account parameter answers 400 with the source's wording. */
        @Test
        void aMalformedAccountParameterAnswersBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(get("/cards").param("accountId", "50"))
                    .andReturn();

            assertEquals(400, result.getResponse().getStatus(),
                    "the constraint fired, so the proxy is in place");
            assertTrue(result.getResponse().getContentAsString().contains("11 DIGIT NUMBER"),
                    "the source text reaches the caller, and the body read: "
                            + result.getResponse().getContentAsString());
        }

        /** Asserts a malformed cursor header answers 400. */
        @Test
        void aMalformedCursorHeaderAnswersBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(get("/cards").param("accountId", ACCOUNT_ID)
                    .header(CardController.CURSOR_HEADER, "4111")).andReturn();

            assertEquals(400, result.getResponse().getStatus(),
                    "a cursor that is not a card token names no row");
        }

        /** Asserts a direction naming neither way answers 400. */
        @Test
        void anUnknownDirectionAnswersBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(get("/cards").param("accountId", ACCOUNT_ID)
                    .param("direction", "sideways")).andReturn();

            assertEquals(400, result.getResponse().getStatus(), "there are two directions");
            assertTrue(result.getResponse().getContentAsString()
                            .contains(CardController.DIRECTION_MESSAGE),
                    "the additive text reaches the caller");
        }

        /** Asserts an unreadable body answers 400 and discloses nothing of the body. */
        @Test
        void anUnreadableBodyAnswersBadRequest() throws Exception {
            MvcResult result = mockMvc.perform(put(CARD_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"embossedName\":\"" + CARD_NUMBER)).andReturn();

            assertEquals(400, result.getResponse().getStatus(), "the body could not be read");
            assertFalse(result.getResponse().getContentAsString().contains(CARD_NUMBER),
                    "no part of the unreadable body reaches the response, and the body read: "
                            + result.getResponse().getContentAsString());
        }

        /**
         * Asserts a path value of another shape answers 400 through the chain, and that a full card
         * number is one such value.
         *
         * <p>The refusal is what keeps a Primary Account Number out of a request line even where a
         * caller sends one: the constraint reads the value, refuses it, and the handler never runs.
         */
        @Test
        void aPathValueOfAnotherShapeAnswersBadRequest() throws Exception {
            for (String refused : List.of(CARD_TOKEN.substring(1), CARD_NUMBER,
                    CARD_TOKEN.toUpperCase(Locale.ROOT))) {
                MvcResult result = mockMvc.perform(get("/cards/" + refused)).andReturn();
                String body = result.getResponse().getContentAsString();

                assertEquals(400, result.getResponse().getStatus(),
                        "the constraint on the path variable refused " + refused);
                assertTrue(body.contains(CardValidationMessages.ADDITIVE_CARD_TOKEN_MALFORMED),
                        "the token refusal text reaches the caller: " + body);
                assertFalse(body.contains(refused),
                        "the refusal echoes the value the caller sent: " + body);
            }
            verify(cardQueries, never()).findByCardToken(any());
        }
    }

    /** Agreement between the mappings and the security rules. */
    @Nested
    @DisplayName("mappings against security rules")
    class MappingsAgainstSecurityRules {

        /**
         * Asserts every mapping the controller declares is a route the security chain names.
         *
         * <p>The chain's final rule denies everything it has not named, so a mapping with no rule is a
         * route a caller reaches a 403 on however correct the handler behind it is. Nothing else in
         * this module would catch that.
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
                    "a mapping the chain does not name is denied by its final rule: "
                            + unauthorized);
        }

        /**
         * Asserts every route the security chain names is a mapping the controller declares.
         *
         * <p>The other direction. A rule naming a route no controller serves is a rule that
         * authorizes a 404, which reads in a review like working authorization and is not.
         */
        @Test
        void everyAuthorizedRouteIsAMappingTheControllerDeclares() {
            Set<String> declared = new TreeSet<>(declaredMappings());
            List<String> unmapped = new ArrayList<>();

            for (String route : authorizedRoutesOfSecurityConfig()) {
                if (!declared.contains(route)) {
                    unmapped.add(route);
                }
            }

            assertEquals(List.of(), unmapped, "a rule naming no mapping authorizes a 404: "
                    + unmapped);
        }

        /** Asserts the two mapped paths are the exact set, so neither side carries a third. */
        @Test
        void theTwoMappedPathsAreTheExactSet() {
            assertEquals(new TreeSet<>(List.of("/cards", "/cards/{cardToken}")),
                    new TreeSet<>(declaredMappings()),
                    "three Customer Information Control System transactions over two paths");
        }

        /**
         * Asserts the account pattern pins the width the column holds.
         *
         * <p>A pattern admitting an unpadded identifier would admit one that matches no row, and the
         * caller would read an empty page for a request that was malformed rather than unmatched.
         */
        @Test
        void theAccountAndCursorPatternsPinTheirStorageShapes() {
            assertEquals("^[0-9]{11}$", CardController.ACCOUNT_ID_PATTERN,
                    "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6");
            assertEquals(PanMasker.CARD_TOKEN_PATTERN, CardController.CURSOR_PATTERN,
                    "the controller accepts only the irreversible token the read side resolves");
        }

        /**
         * Asserts the ownership parameter the chain reads is the one this controller declares.
         *
         * <p>The rule captures a query parameter by name. A rename on either side would leave the
         * rule reading an absent parameter, which denies every request, and the route would answer
         * 403 for every caller including the entitled ones.
         */
        @Test
        void theOwnershipParameterNameMatchesTheChain() {
            String chain = readSecurityConfigSource();

            assertTrue(chain.contains("ownsRequestParameter(ACCOUNT_SCOPE, \""
                            + CardController.ACCOUNT_ID_PARAMETER + "\")"),
                    "the chain reads the parameter this controller declares");
        }
    }

    /**
     * Reads every path {@code config/SecurityConfig} names in a route rule, from its source text.
     *
     * <p>The paths stay literals in that file rather than becoming constants, because a reader of a
     * filter chain should see the route table without following a reference, and because the
     * cross-module contract in {@code equivalence-tests} reads the same literals. This method
     * therefore reads the text, which is the same instrument that contract uses.
     *
     * @return the authorized routes, in the order the chain declares them
     */
    private static List<String> authorizedRoutesOfSecurityConfig() {
        Matcher rule = SECURITY_ROUTE_RULE.matcher(readSecurityConfigSource());

        List<String> routes = new ArrayList<>();
        while (rule.find()) {
            String route = rule.group(1);
            if (!routes.contains(route)) {
                routes.add(route);
            }
        }
        assertFalse(routes.isEmpty(),
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
        Path relative = Path.of("src", "main", "java", "com", "carddemo", "card", "config",
                "SecurityConfig.java");
        Path directory = Path.of("").toAbsolutePath();
        for (int levels = 0; levels < 6 && directory != null; levels++) {
            for (Path candidate : List.of(directory.resolve(relative),
                    directory.resolve("services").resolve("card-service").resolve(relative))) {
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Files.readString(candidate, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        throw new AssertionError("cannot read " + candidate, unreadable);
                    }
                }
            }
            directory = directory.getParent();
        }
        throw new AssertionError("cannot find " + relative + " from "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Reads every route the controller maps, as a class-level prefix joined to a method-level path.
     *
     * @return the mapped routes
     */
    private static List<String> declaredMappings() {
        List<String> mappings = new ArrayList<>();
        String prefix = CardController.class.getAnnotation(RequestMapping.class).value()[0];
        for (Method method : CardController.class.getDeclaredMethods()) {
            pathOf(method).ifPresent(path -> mappings.add(prefix + path));
        }
        return mappings;
    }

    /**
     * Reads the method-level path of one handler method, if it declares one.
     *
     * <p>A mapping annotation with no value maps the class-level prefix itself, which is how the list
     * route and the update route both sit on the collection.
     *
     * @param method the candidate handler
     * @return the path, or empty when the method maps nothing
     */
    private static Optional<String> pathOf(Method method) {
        GetMapping read = method.getAnnotation(GetMapping.class);
        if (read != null) {
            return Optional.of(firstOf(read.value(), read.path()));
        }
        PutMapping update = method.getAnnotation(PutMapping.class);
        if (update != null) {
            return Optional.of(firstOf(update.value(), update.path()));
        }
        PostMapping create = method.getAnnotation(PostMapping.class);
        if (create != null) {
            return Optional.of(firstOf(create.value(), create.path()));
        }
        return Optional.empty();
    }

    /**
     * Reads the path a mapping annotation declares under either of its two aliased members.
     *
     * <p>{@code value} and {@code path} are the same member of the annotation, so an annotation that
     * also sets {@code consumes} or {@code produces} names the path under {@code path} and leaves
     * {@code value} empty. Plain reflection does not resolve the alias, so both are read here, and a
     * mapping that sets neither maps the class-level prefix itself.
     *
     * @param value the {@code value} member
     * @param path  the {@code path} member
     * @return the declared path, or the empty string when the annotation declares none
     */
    private static String firstOf(String[] value, String[] path) {
        if (value.length > 0) {
            return value[0];
        }
        return path.length > 0 ? path[0] : "";
    }

    /**
     * Builds a one-row page the list stubs return.
     *
     * @return the page
     */
    private static CardPage page() {
        return new CardPage(List.of(new CardListRow(CARD_NUMBER, ACCOUNT_ID, "Y")), false,
                CARD_TOKEN, CARD_TOKEN);
    }

    /**
     * Builds the stored card the read stub returns.
     *
     * @return the row
     */
    private static CardEntity storedCard() {
        return new CardEntity(CARD_NUMBER, ACCOUNT_ID, "123", "ALEXANDER J MORGAN",
                LocalDate.of(2028, 11, 30), "Y");
    }
}

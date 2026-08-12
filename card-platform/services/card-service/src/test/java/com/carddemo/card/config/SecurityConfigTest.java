package com.carddemo.card.config;

import com.carddemo.card.TestIdentityPasswords;
import com.carddemo.cobol.PanMasker;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.config.SecurityConfig.SecurityIdentities;
import com.carddemo.card.config.SecurityConfig.SecurityIdentities.Identity;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Tests over {@link SecurityConfig} for the card service.
 *
 * <p>No test here starts an application context, opens a socket or reaches a database. Two of them
 * read class metadata, and the rest call the static methods the filter chains install: the
 * ownership decision, the identity mapping and the two problem writers. That keeps the suite
 * runnable on a clean machine, which is the convention the other suites in this module follow.
 *
 * <p>What the ownership tests assert is the control that answers the review finding directly: an
 * authenticated caller holding one account or card reaches that one and receives a refusal for any
 * other. The decision is exercised here as a unit, against a stub authentication, so the rule holds
 * whether or not a handler exists yet to sit behind it.
 */
@DisplayName("SecurityConfig, request authentication and authorization in card")
class SecurityConfigTest {

    /** Account identifier the stub caller owns, from record 1 of app/data/ASCII/acctdata.txt. */
    private static final String OWNED_ACCOUNT = "00000000001";

    /** Account identifier the stub caller does not own. */
    private static final String OTHER_ACCOUNT = "00000000002";

    /** Card number the stub caller owns, record 1 of app/data/ASCII/cardxref.txt. */
    private static final String OWNED_CARD_NUMBER = "0500024453765740";

    /** Card number the stub caller does not own, record 3 of the same fixture. */
    private static final String OTHER_CARD_NUMBER = "0923877193247330";

    /**
     * A card number sharing the last four digits of {@link #OWNED_CARD_NUMBER} and no other digit.
     *
     * <p>Constructed rather than seeded. The fifty cards of {@code app/data/ASCII/carddata.txt} end
     * in fifty different groups of four, so the fixture cannot show what a masked authority admits.
     */
    private static final String COLLIDING_CARD_NUMBER = "9999999999995740";

    /**
     * Card token the stub caller owns, derived rather than typed.
     *
     * <p>A literal would go stale the moment the card-token key turned over, and the constant would
     * then claim to be a token of that card while naming a value nothing derives. The literal that
     * pins the shipped key lives in one place, {@code equivalence-tests CardTokenKeyContractTest},
     * and this constant only has to be the token the granted authority and the checked route agree
     * on.
     */
    private static final String OWNED_CARD = PanMasker.cardToken(OWNED_CARD_NUMBER);

    /** An encoded password, which is the only form a configured identity carries. */
    private static final String ENCODED_PASSWORD =
            "{bcrypt}$2a$10$09aw53YezqFzaOe9qHVMR.KYVTyZ497/zI1dK2cpMnKcolhsF2XEW";

    /** Builds the authentication of an ordinary identity holding one account and one card. */
    private static Authentication cardholder() {
        return UsernamePasswordAuthenticationToken.authenticated("user0001", null, List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("SCOPE_ACCOUNT_" + OWNED_ACCOUNT),
                new SimpleGrantedAuthority("SCOPE_CARD_" + OWNED_CARD)));
    }

    /**
     * Runs {@code body} with {@code authentication} installed, and clears the context afterwards.
     *
     * <p>{@link SecurityConfig#ownsPathVariable(String, String)} reads the current authentication
     * rather than
     * receiving one, because the handler that asks it is already inside the request. The context is
     * cleared in a finally block so one test cannot leave an identity behind for the next.
     *
     * @param authentication the identity to install
     * @param body           the assertions to run under it
     */
    private static void withAuthentication(Authentication authentication, Runnable body) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            body.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Builds the authentication of an administrator, which carries no ownership scope. */
    private static Authentication administrator() {
        return UsernamePasswordAuthenticationToken.authenticated("admin001", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /** Wraps one path variable in the context an authorization rule receives. */
    private static RequestAuthorizationContext pathContext(String name, String value) {
        return new RequestAuthorizationContext(new MockHttpServletRequest(), Map.of(name, value));
    }

    @Nested
    @DisplayName("The class declares the posture it claims")
    class Declaration {

        @Test
        @DisplayName("it is a configuration carrying web security, method security and properties")
        void carriesTheFourAnnotations() {
            assertAll(
                    () -> assertNotNull(SecurityConfig.class.getAnnotation(Configuration.class),
                            "SecurityConfig must be a @Configuration"),
                    () -> assertNotNull(SecurityConfig.class.getAnnotation(EnableWebSecurity.class),
                            "@EnableWebSecurity installs the filter chain"),
                    () -> assertNotNull(
                            SecurityConfig.class.getAnnotation(EnableMethodSecurity.class),
                            "@EnableMethodSecurity lets a handler state an ownership check that "
                                    + "the filter chain cannot decide from the request alone"),
                    () -> assertNotNull(SecurityConfig.class
                                    .getAnnotation(EnableConfigurationProperties.class),
                            "@EnableConfigurationProperties binds carddemo.security.users"));
        }

        @Test
        @DisplayName("two ordered filter chains and one password encoder are declared")
        void declaresTwoOrderedChains() {
            Method management = beanMethod("managementSecurity");
            Method api = beanMethod("apiSecurity");
            assertAll(
                    () -> assertEquals(SecurityFilterChain.class, management.getReturnType(),
                            "managementSecurity returns a filter chain"),
                    () -> assertEquals(SecurityFilterChain.class, api.getReturnType(),
                            "apiSecurity returns a filter chain"),
                    () -> assertEquals(1, management.getAnnotation(Order.class).value(),
                            "the actuator chain runs first because its matcher is narrower"),
                    () -> assertEquals(2, api.getAnnotation(Order.class).value(),
                            "the API chain runs second"),
                    () -> assertEquals(PasswordEncoder.class,
                            beanMethod("passwordEncoder").getReturnType(),
                            "an encoder is declared, so a configured password is verified against "
                                    + "its algorithm prefix and never compared as text"));
        }

        /** Locates one {@link Bean} method by name and fails when it is not annotated. */
        private static Method beanMethod(String name) {
            for (Method method : SecurityConfig.class.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    assertNotNull(method.getAnnotation(Bean.class),
                            name + " must be a @Bean method");
                    return method;
                }
            }
            throw new AssertionError("SecurityConfig declares no method named " + name);
        }
    }

    @Nested
    @DisplayName("Ownership decides who reaches an identifier")
    class Ownership {

        @Test
        @DisplayName("a cardholder reaches the account it owns")
        void ownedAccountIsGranted() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            assertTrue(rule.authorize(SecurityConfigTest::cardholder,
                            pathContext("accountId", OWNED_ACCOUNT)).isGranted(),
                    "the identity carries SCOPE_ACCOUNT_" + OWNED_ACCOUNT);
        }

        @Test
        @DisplayName("a cardholder is refused another subject's account")
        void otherAccountIsRefused() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                            pathContext("accountId", OTHER_ACCOUNT)).isGranted(),
                    "an authenticated caller is not entitled to every account");
        }

        @Test
        @DisplayName("a leading zero is part of the identifier and not decoration")
        void anIdentifierWithoutItsLeadingZerosIsRefused() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                            pathContext("accountId", "1")).isGranted(),
                    "ACCT-ID PIC 9(11) is eleven characters, so 1 names no account");
        }

        @Test
        @DisplayName("an administrator reaches any identifier, which is the COSGN00C fork")
        void administratorPassesEveryCheck() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            assertTrue(rule.authorize(SecurityConfigTest::administrator,
                            pathContext("accountId", OTHER_ACCOUNT)).isGranted(),
                    "SEC-USR-TYPE A reaches the administrator path at "
                            + "app/cbl/COSGN00C.cbl:L232-L236");
        }

        @Test
        @DisplayName("an unauthenticated caller is refused")
        void unauthenticatedIsRefused() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            assertAll(
                    () -> assertFalse(rule.authorize(() -> null,
                                    pathContext("accountId", OWNED_ACCOUNT)).isGranted(),
                            "no authentication means no ownership"),
                    () -> assertFalse(rule.authorize(
                                    () -> UsernamePasswordAuthenticationToken.unauthenticated(
                                            "user0001", null),
                                    pathContext("accountId", OWNED_ACCOUNT)).isGranted(),
                            "an unauthenticated token means no ownership"));
        }

        @Test
        @DisplayName("a missing path variable is refused rather than waved through")
        void missingVariableIsRefused() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                            new RequestAuthorizationContext(new MockHttpServletRequest(), Map.of()))
                            .isGranted(),
                    "a rule and a route that disagree about a path deny");
        }

        @Test
        @DisplayName("a card is owned by its derived token, never by a masked form or a number")
        void cardOwnershipUsesTheDerivedToken() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.CARD_SCOPE, "cardToken");
            assertAll(
                    () -> assertTrue(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken", OWNED_CARD)).isGranted(),
                            "the identity carries the token authority of its own card"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken",
                                            PanMasker.cardToken(OTHER_CARD_NUMBER)))
                                    .isGranted(),
                            "the token of another card is refused"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken",
                                            PanMasker.cardToken(COLLIDING_CARD_NUMBER)))
                                    .isGranted(),
                            "a card sharing the last four digits is a different token, so the "
                                    + "authority admits one card and not a group of them"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken",
                                            PanMasker.maskCardNumber(OWNED_CARD_NUMBER)))
                                    .isGranted(),
                            "a masked card number matches no scope, so a caller cannot substitute "
                                    + "the weaker form for the token the route declares"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken", OWNED_CARD_NUMBER)).isGranted(),
                            "a full card number matches no scope either"));
        }

        @Test
        @DisplayName("the card path variable is compared as it stands, and nothing is derived from it")
        void theCardPathVariableIsComparedAsItStands() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.CARD_SCOPE, "cardToken");

            assertAll(
                    () -> assertTrue(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken", OWNED_CARD)).isGranted(),
                            "the path value is the authority value, so the authority the platform "
                                    + "grants is the authority it compares"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken", OWNED_CARD_NUMBER)).isGranted(),
                            "the full number of the caller's own card owns nothing: the rule "
                                    + "derives no token, so a number never resolves to one"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken", OWNED_CARD.toUpperCase(Locale.ROOT)))
                                    .isGranted(),
                            "the comparison is exact, so the upper-case rendering of the caller's "
                                    + "own token owns nothing either"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    new RequestAuthorizationContext(new MockHttpServletRequest(),
                                            Map.of())).isGranted(),
                            "an absent path variable owns nothing"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("cardToken", "   ")).isGranted(),
                            "a blank value owns nothing"),
                    () -> assertTrue(rule.authorize(SecurityConfigTest::administrator,
                                    pathContext("cardToken",
                                            PanMasker.cardToken(OTHER_CARD_NUMBER))).isGranted(),
                            "an administrator owns every card, which is COSGN00C L232-L236 "
                                    + "expressed as an entitlement"));
        }

        @Test
        @DisplayName("a query parameter carries ownership the same way a path variable does")
        void requestParameterOwnership() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsRequestParameter(SecurityConfig.ACCOUNT_SCOPE, "accountId");
            MockHttpServletRequest owned = new MockHttpServletRequest();
            owned.setParameter("accountId", OWNED_ACCOUNT);
            MockHttpServletRequest other = new MockHttpServletRequest();
            other.setParameter("accountId", OTHER_ACCOUNT);
            assertAll(
                    () -> assertTrue(rule.authorize(SecurityConfigTest::cardholder,
                                    new RequestAuthorizationContext(owned)).isGranted(),
                            "the caller owns the account it asks about"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    new RequestAuthorizationContext(other)).isGranted(),
                            "the caller does not own the other account"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    new RequestAuthorizationContext(
                                            new MockHttpServletRequest())).isGranted(),
                            "an absent parameter denies"));
        }
    }

    @Nested
    @DisplayName("Identities come from configuration and carry no plaintext password")
    class Identities {

        @Test
        @DisplayName("a role becomes an authority and each scope becomes one of its own")
        void identitiesMapOntoAuthorities() {
            List<UserDetails> users = SecurityConfig.toUserDetails(new SecurityIdentities(List.of(
                    new Identity("user0001", ENCODED_PASSWORD, "USER",
                            List.of("SCOPE_ACCOUNT_" + OWNED_ACCOUNT)),
                    new Identity("admin001", ENCODED_PASSWORD, "ADMIN", null))));

            assertAll(
                    () -> assertEquals(2, users.size(), "one user per configured identity"),
                    () -> assertEquals(2, users.get(0).getAuthorities().size(),
                            "an ordinary identity carries its role and its one scope"),
                    () -> assertTrue(users.get(0).getAuthorities().stream()
                                    .anyMatch(a -> a.getAuthority().equals("ROLE_USER")),
                            "the role reaches the authority list with its prefix"),
                    () -> assertTrue(users.get(0).getAuthorities().stream()
                                    .anyMatch(a -> a.getAuthority()
                                            .equals("SCOPE_ACCOUNT_" + OWNED_ACCOUNT)),
                            "the scope reaches the authority list verbatim"),
                    () -> assertEquals(1, users.get(1).getAuthorities().size(),
                            "an administrator needs no scope: it passes by role"),
                    () -> assertEquals(ENCODED_PASSWORD, users.get(0).getPassword(),
                            "the configured value is used as it stands, so the encoder reads its "
                                    + "prefix instead of anything comparing text"));
        }

        @Test
        @DisplayName("start-up refuses when no identity is configured")
        void anEmptyIdentityListStopsStartUp() {
            assertAll(
                    () -> assertThrows(IllegalStateException.class,
                            () -> SecurityConfig.toUserDetails(new SecurityIdentities(List.of())),
                            "an empty list stops start-up"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> SecurityConfig.toUserDetails(new SecurityIdentities(null)),
                            "an absent list stops start-up"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> SecurityConfig.toUserDetails(null),
                            "absent configuration stops start-up"));
        }

        @Test
        @DisplayName("an identity missing a field is refused, and the message carries no value")
        void anIncompleteIdentityIsRefused() {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.toUserDetails(new SecurityIdentities(List.of(
                            new Identity("user0001", "  ", "USER", null)))));
            assertAll(
                    () -> assertTrue(thrown.getMessage().contains("password"),
                            "the message names the missing field: " + thrown.getMessage()),
                    () -> assertFalse(thrown.getMessage().contains("user0001"),
                            "the message names no identity: " + thrown.getMessage()));
        }
        /**
         * Asserts a role this service grants nothing for stops start-up.
         *
         * <p>A configured role that reached the authority list unchecked would authenticate an
         * identity able to reach nothing, and would report no reason. The message names the
         * configured word because an operator correcting a typo needs to know which word to
         * correct, and a role is not a credential.
         */
        @Test
        @DisplayName("a role this service grants nothing for stops start-up")
        void anUnknownRoleStopsStartUp() {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.toUserDetails(new SecurityIdentities(List.of(
                            new Identity("user0001", ENCODED_PASSWORD, "OPERATOR", null)))));

            assertAll(
                    () -> assertTrue(refused.getMessage().contains("OPERATOR"),
                            "the message names the configured word: " + refused.getMessage()),
                    () -> assertTrue(refused.getMessage().contains("USER"),
                            "the message lists the roles this service reads: "
                                    + refused.getMessage()),
                    () -> assertFalse(refused.getMessage().contains(ENCODED_PASSWORD),
                            "the message carries no credential: " + refused.getMessage()));
        }

        /**
         * Asserts a role written into the scope list stops start-up.
         *
         * <p>This is the one refusal here that closes a privilege escalation rather than a
         * configuration mistake. Every authority reaches one list, so a role granted through the
         * scope list would pass a route rule naming that role. The scopes an operator writes are
         * therefore read before they become authorities, not after.
         */
        @Test
        @DisplayName("a role written into the scope list stops start-up")
        void aRoleInTheScopeListStopsStartUp() {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.toUserDetails(new SecurityIdentities(List.of(
                            new Identity("user0001", ENCODED_PASSWORD, "USER",
                                    List.of("ROLE_ADMIN"))))));

            assertAll(
                    () -> assertTrue(refused.getMessage().contains("ROLE_ADMIN"),
                            "the message names the entry: " + refused.getMessage()),
                    () -> assertTrue(refused.getMessage().contains("role"),
                            "the message says what the entry is: " + refused.getMessage()));
        }

        /**
         * Asserts a scope that cannot grant what it names stops start-up.
         *
         * <p>Three shapes are read. An entry with no {@code SCOPE_} prefix is not an ownership
         * authority. An entry naming a kind no route rule reads reaches nothing. An entry whose value
         * misses its kind's shape reaches no row, which is the quietest of the three: it reads as an
         * ownership claim and grants nothing.
         */
        @Test
        @DisplayName("a scope of an unknown kind, an unknown shape or no prefix stops start-up")
        void aScopeThatGrantsNothingStopsStartUp() {
            assertAll(
                    () -> assertThrows(IllegalStateException.class,
                            () -> withScopes("ACCOUNT_" + OWNED_ACCOUNT),
                            "an entry with no SCOPE_ prefix is not an ownership authority"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> withScopes("SCOPE_LEDGER_00000000001"),
                            "no route rule reads a kind outside the three"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> withScopes("SCOPE_ACCOUNT_1"),
                            "an account identifier is eleven digits, leading zeros included"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> withScopes("SCOPE_CUSTOMER_" + OWNED_ACCOUNT),
                            "a customer identifier is nine digits, so an eleven-digit value is not "
                                    + "one"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> withScopes("SCOPE_CARD_0500024453765740"),
                            "a card is owned by its derived token, never by a card number"),
                    () -> assertThrows(IllegalStateException.class,
                            () -> withScopes("SCOPE_ACCOUNT"),
                            "a kind with no value names no row"));
        }

        /**
         * Asserts the three shipped kinds are accepted, and a blank entry is dropped rather than
         * refused.
         *
         * <p>The blank case is the shipped deployment: both the composition and the ConfigMap pass an
         * empty {@code USER_SCOPES} to a container, and an empty authority is not a claim. Refusing
         * it would stop a start-up the shipped artifacts ask for.
         */
        @Test
        @DisplayName("the three shipped kinds are accepted and a blank entry is dropped")
        void theShippedScopesAreAcceptedAndABlankEntryIsDropped() {
            List<UserDetails> accepted = withScopes("SCOPE_ACCOUNT_" + OWNED_ACCOUNT,
                    "SCOPE_CUSTOMER_000000001", "SCOPE_CARD_" + OWNED_CARD, "", "   ");

            assertAll(
                    () -> assertEquals(4, accepted.get(0).getAuthorities().size(),
                            "the role and the three well-formed scopes, and nothing for the two "
                                    + "blank entries"),
                    () -> assertTrue(accepted.get(0).getAuthorities().stream()
                                    .allMatch(granted -> !granted.getAuthority().isBlank()),
                            "a blank authority would read as a claim while naming nothing"));
        }

        /**
         * Builds one identity carrying the given scopes.
         *
         * @param scopes the scopes to configure
         * @return the mapped users
         */
        private List<UserDetails> withScopes(String... scopes) {
            return SecurityConfig.toUserDetails(new SecurityIdentities(List.of(
                    new Identity("user0001", ENCODED_PASSWORD, "USER", List.of(scopes)))));
        }
    }

    @Nested
    @DisplayName("A refusal explains nothing it should not")
    class Refusals {

        @Test
        @DisplayName("401 carries a problem document, a scheme and no resource")
        void unauthorizedIsAProblemDocument() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/accounts/" + OTHER_ACCOUNT);
            MockHttpServletResponse response = new MockHttpServletResponse();

            SecurityConfig.unauthorized(request, response, null);

            String body = response.getContentAsString();
            assertAll(
                    () -> assertEquals(401, response.getStatus(), "the status is 401"),
                    () -> assertEquals("application/problem+json;charset=UTF-8",
                            response.getContentType(), "the body is a problem document"),
                    () -> assertTrue(response.getHeader("WWW-Authenticate").startsWith("Basic "),
                            "a 401 states the scheme it expects"),
                    () -> assertEquals("no-store", response.getHeader("Cache-Control"),
                            "no cache keeps a copy of a refusal"),
                    () -> assertFalse(body.contains(OTHER_ACCOUNT),
                            "the detail repeats no identifier from the request: " + body),
                    () -> assertFalse(body.contains("/accounts"),
                            "the detail repeats no route from the request: " + body),
                    () -> assertTrue(body.contains("\"status\":401"),
                            "the document carries the status member: " + body));
        }

        @Test
        @DisplayName("403 carries a problem document naming neither operation nor identifier")
        void forbiddenIsAProblemDocument() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/accounts/" + OTHER_ACCOUNT);
            MockHttpServletResponse response = new MockHttpServletResponse();

            SecurityConfig.forbidden().handle(request, response, null);

            String body = response.getContentAsString();
            assertAll(
                    () -> assertEquals(403, response.getStatus(), "the status is 403"),
                    () -> assertEquals("application/problem+json;charset=UTF-8",
                            response.getContentType(), "the body is a problem document"),
                    () -> assertFalse(body.contains(OTHER_ACCOUNT),
                            "a caller probing for another subject's rows learns no identifier: "
                                    + body),
                    () -> assertFalse(body.contains("/accounts"),
                            "and no route: " + body));
        }
    }

    @Nested
    @DisplayName("The published-credential guard refuses a credential this repository publishes")
    class PublishedCredentialGuardTests {

        /** A broker login entry carrying a usable password. */
        private static final String BROKER_JAAS = "org.apache.kafka.common.security.plain."
                + "PlainLoginModule required username=\"svc\" password=\"a-generated-value\";";

        /**
         * A card-token key of the accepted length that this repository names nowhere else.
         *
         * <p>It is not the key the repository publishes, so it needs no acknowledgement, and it is
         * not a placeholder, so the marker check leaves it alone. It derives no token here: nothing
         * in this class tokenizes a card number.
         */
        private static final String GENERATED_CARD_TOKEN_KEY =
                "a-generated-card-token-key-for-this-test-only";

        /** An environment with every checked credential fit to run with. */
        private MockEnvironment usable() {
            return new MockEnvironment()
                    .withProperty(SecurityConfig.DATASOURCE_PASSWORD_PROPERTY, "a-generated-value")
                    .withProperty(SecurityConfig.BROKER_JAAS_PROPERTY, BROKER_JAAS)
                    .withProperty(PanMasker.CARD_TOKEN_SECRET_VARIABLE, GENERATED_CARD_TOKEN_KEY);
        }

        /**
         * Returns a usable environment that records which properties were read.
         *
         * <p>The two accepting cases below have nothing to assert on otherwise. Both call a
         * {@code void} guard with a configuration it should accept, so both pass against a guard
         * that reads nothing and returns — which is the one failure a start-up check must not have.
         * Recording the reads turns "it did not object" into "it looked, and then it did not
         * object", and it is what lets the encryption case assert the thing its name claims: that
         * the keystore is not merely accepted while empty, but never asked for.</p>
         *
         * @return an environment holding the same values as {@link #usable()}
         */
        private RecordingEnvironment recording() {
            RecordingEnvironment environment = new RecordingEnvironment();
            environment.setProperty(SecurityConfig.DATASOURCE_PASSWORD_PROPERTY,
                    "a-generated-value");
            environment.setProperty(SecurityConfig.BROKER_JAAS_PROPERTY, BROKER_JAAS);
            environment.setProperty(PanMasker.CARD_TOKEN_SECRET_VARIABLE, GENERATED_CARD_TOKEN_KEY);
            return environment;
        }

        /** A {@link MockEnvironment} that remembers every property key asked of it. */
        private static final class RecordingEnvironment extends MockEnvironment {

            /** Keys read, in the order the guard read them. */
            private final List<String> reads = new ArrayList<>();

            @Override
            public String getProperty(String key) {
                reads.add(key);
                return super.getProperty(key);
            }

            @Override
            public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
                reads.add(key);
                return super.getProperty(key, targetType, defaultValue);
            }

            /** Reports whether the guard read one property. */
            boolean read(String key) {
                return reads.contains(key);
            }

            /** Returns the keys read, for a failure message. */
            List<String> reads() {
                return List.copyOf(reads);
            }
        }

        /** The message from refusing one identity password, so a case below reads as one line. */
        private String refusalFor(String identityPassword) {
            return assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableSecret(identityPassword,
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY))
                    .getMessage();
        }

        @Test
        @DisplayName("a usable configuration passes, and the guard reads every value it vouches for")
        void usableConfigurationPasses() {
            RecordingEnvironment environment = recording();

            SecurityConfig.requireUsableCredentials(environment);

            assertAll(
                    () -> assertTrue(environment.read(SecurityConfig.DATASOURCE_PASSWORD_PROPERTY),
                            "the datasource password was never read: " + environment.reads()),
                    () -> assertTrue(environment.read(SecurityConfig.BROKER_JAAS_PROPERTY),
                            "the broker credential was never read: " + environment.reads()),
                    () -> assertTrue(environment.read(SecurityConfig.SERVER_SSL_ENABLED_PROPERTY),
                            "the encryption switch was never read, so the keystore rule below it"
                                    + " never ran: " + environment.reads()));
        }

        @Test
        @DisplayName("an identity password that is not adaptively encoded is refused")
        void anIdentityPasswordThatIsNotAdaptivelyEncodedIsRefused() {
            assertAll(
                    () -> assertTrue(refusalFor("{noop}not-a-hash").contains("{noop}"),
                            "the delegating encoder answers noop by comparing the stored and"
                                    + " supplied values, so a password under it is plaintext"),
                    () -> assertTrue(refusalFor("{MD5}0123456789abcdef").contains("{MD5}"),
                            "a digest identifier names no adaptive encoder"),
                    () -> assertTrue(refusalFor("{sha256}0123456789abcdef").contains("{sha256}"),
                            "the delegating encoder's sha256 entry is refused as well"),
                    () -> assertTrue(refusalFor("{ldap}{SSHA}0123456789").contains("{ldap}"),
                            "an ldap entry is refused"),
                    () -> assertTrue(refusalFor("{bcrypt}").contains("no bcrypt hash"),
                            "a prefix carrying no payload authenticates nobody, and the shape check"
                                    + " is what refuses it"),
                    () -> assertTrue(refusalFor("{bcrypt}not-a-hash").contains("no bcrypt hash"),
                            "a bcrypt prefix has to carry a bcrypt hash"),
                    () -> assertTrue(refusalFor("{bcrypt}$2a$04$" + "a".repeat(53))
                                    .contains("cost of 4"),
                            "a cost below " + SecurityConfig.BCRYPT_MINIMUM_COST
                                    + " verifies faster for whoever holds the hash too"));
        }

        /**
         * Asserts the deployment path of the card-token key is checked.
         *
         * <p>The build supplies the same key as a system property, and {@code SecurityConfig} reads
         * that path first so this check does not stop every test that starts a context. The three
         * cases below are about the path a deployment uses, so the build property is cleared while
         * they run and restored afterwards.
         */
        @Test
        @DisplayName("a card-token key that is absent, a placeholder or too short is refused")
        void anUnusableCardTokenKeyIsRefused() {
            MockEnvironment absent = new MockEnvironment()
                    .withProperty(SecurityConfig.DATASOURCE_PASSWORD_PROPERTY, "a-generated-value")
                    .withProperty(SecurityConfig.BROKER_JAAS_PROPERTY, BROKER_JAAS);
            MockEnvironment tooShort = usable()
                    .withProperty(PanMasker.CARD_TOKEN_SECRET_VARIABLE, "too-short-a-key");
            MockEnvironment placeholder = usable().withProperty(
                    PanMasker.CARD_TOKEN_SECRET_VARIABLE,
                    "REPLACE-THIS-PLACEHOLDER-WITH-A-GENERATED-CARD-TOKEN-KEY");

            String heldKey = System.getProperty(SecurityConfig.CARD_TOKEN_KEY_PROPERTY);
            try {
                System.clearProperty(SecurityConfig.CARD_TOKEN_KEY_PROPERTY);
                assertAll(
                        () -> assertTrue(refusalFrom(absent)
                                        .contains(PanMasker.CARD_TOKEN_SECRET_VARIABLE),
                                "an absent key has to name the variable that supplies it"),
                        () -> assertTrue(refusalFrom(tooShort).contains(
                                        String.valueOf(PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH)),
                                "a short key is refused rather than padded"),
                        () -> assertTrue(refusalFrom(placeholder).contains("placeholder"),
                                "the value deploy/k8s/31-secret.example.yaml carries is a"
                                        + " placeholder and not a key"));
            } finally {
                if (heldKey == null) {
                    System.clearProperty(SecurityConfig.CARD_TOKEN_KEY_PROPERTY);
                } else {
                    System.setProperty(SecurityConfig.CARD_TOKEN_KEY_PROPERTY, heldKey);
                }
            }
        }

        /** The message from refusing one whole environment. */
        private String refusalFrom(MockEnvironment environment) {
            return assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment)).getMessage();
        }

        @Test
        @DisplayName("a database password still carrying the placeholder is refused")
        void placeholderDatabasePasswordIsRefused() {
            MockEnvironment environment = usable().withProperty(
                    SecurityConfig.DATASOURCE_PASSWORD_PROPERTY,
                    "REPLACE-WITH-A-GENERATED-PASSWORD");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment));

            assertAll(
                    () -> assertTrue(thrown.getMessage()
                                    .startsWith(SecurityConfig.DATASOURCE_PASSWORD_PROPERTY),
                            "the message opens with the property: " + thrown.getMessage()),
                    () -> assertFalse(thrown.getMessage().contains("REPLACE-WITH"),
                            "and repeats no part of the value: " + thrown.getMessage()));
        }

        /**
         * Proves this service refuses to start on the card-token key this repository publishes,
         * however that key arrives and whatever the configuration says about it.
         *
         * <p>{@code PanMasker.requireCardTokenSecretFitForUse} admits the published key when a
         * deployment states that it means to use it, and that library rule is proved by
         * {@code com.carddemo.cobol.PanMaskerTest}. This service is stricter, because it derives a
         * token: {@code requireUsableCardTokenKey} refuses the published value outright, so no
         * statement makes it usable here. Both are kept, and the stricter one runs first, which is
         * why the refusal below names the variable and the condition rather than a value to set.
         *
         * <p>Setting the acknowledgement afterwards therefore changes nothing, and the second
         * assertion is what proves the outright refusal rather than a conditional one.
         */
        @Test
        @DisplayName("the card-token key this repository publishes is refused however it arrives")
        void publishedCardTokenKeyIsRefusedUnlessStated() {
            String held =
                    System.getProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);
            String heldKey = System.getProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY);
            try {
                System.setProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY,
                        PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET);
                System.clearProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);

                IllegalStateException unstated = assertThrows(IllegalStateException.class,
                        () -> SecurityConfig.requireUsableCredentials(usable()),
                        "this service started on the key this repository publishes with nothing"
                                + " stating that it meant to");
                assertAll(
                        () -> assertTrue(unstated.getMessage()
                                        .contains(SecurityConfig.CARD_TOKEN_KEY_VARIABLE),
                                "the refusal names the variable that carries the key: "
                                        + unstated.getMessage()),
                        () -> assertFalse(unstated.getMessage()
                                        .contains(PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET),
                                "and repeats no part of the key itself: " + unstated.getMessage()));

                System.setProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY, "true");
                IllegalStateException stated = assertThrows(IllegalStateException.class,
                        () -> SecurityConfig.requireUsableCredentials(usable()),
                        "a statement made the published key usable in a service that derives"
                                + " tokens, which is the one place it must not be");
                assertTrue(stated.getMessage().contains(SecurityConfig.CARD_TOKEN_KEY_VARIABLE),
                        "the refusal is the same one, so the statement changed nothing: "
                                + stated.getMessage());
            } finally {
                if (held == null) {
                    System.clearProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);
                } else {
                    System.setProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY, held);
                }
                if (heldKey == null) {
                    System.clearProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY);
                } else {
                    System.setProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY, heldKey);
                }
            }
        }

        @Test
        @DisplayName("the database password this repository publishes is refused")
        void publishedDatabasePasswordIsRefused() {
            MockEnvironment environment = usable().withProperty(
                    SecurityConfig.DATASOURCE_PASSWORD_PROPERTY,
                    SecurityConfig.PUBLISHED_DATABASE_PASSWORD);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment));

            assertTrue(thrown.getMessage().contains("publishes as an example"),
                    "the message says why: " + thrown.getMessage());
        }

        @Test
        @DisplayName("an absent database password is refused")
        void absentDatabasePasswordIsRefused() {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty(SecurityConfig.BROKER_JAAS_PROPERTY, BROKER_JAAS);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment));

            assertTrue(thrown.getMessage().contains("is not set"),
                    "the message says which condition failed: " + thrown.getMessage());
        }

        @Test
        @DisplayName("a broker credential still carrying the placeholder is refused, value unnamed")
        void placeholderBrokerCredentialIsRefused() {
            MockEnvironment environment = usable().withProperty(SecurityConfig.BROKER_JAAS_PROPERTY,
                    "org.apache.kafka.common.security.plain.PlainLoginModule required"
                            + " username=\"svc\" password=\"REPLACE-WITH-A-GENERATED-VALUE\";");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment));

            assertAll(
                    () -> assertTrue(thrown.getMessage()
                                    .startsWith(SecurityConfig.BROKER_JAAS_PROPERTY),
                            "the message opens with the property: " + thrown.getMessage()),
                    () -> assertFalse(thrown.getMessage().contains("password="),
                            "and carries no part of the login entry: " + thrown.getMessage()));
        }

        @Test
        @DisplayName("encryption switched on with no keystore is refused")
        void encryptionWithoutAKeystoreIsRefused() {
            MockEnvironment environment = usable()
                    .withProperty(SecurityConfig.SERVER_SSL_ENABLED_PROPERTY, "true")
                    .withProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PROPERTY, "");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment));

            assertTrue(thrown.getMessage()
                            .startsWith(SecurityConfig.SERVER_SSL_KEYSTORE_PROPERTY),
                    "the message opens with the keystore property: " + thrown.getMessage());
        }

        @Test
        @DisplayName("encryption switched on with a placeholder keystore password is refused")
        void encryptionWithAPlaceholderKeystorePasswordIsRefused() {
            MockEnvironment environment = usable()
                    .withProperty(SecurityConfig.SERVER_SSL_ENABLED_PROPERTY, "true")
                    .withProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PROPERTY,
                            "file:/etc/carddemo/tls/keystore.p12")
                    .withProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY,
                            "REPLACE-WITH-THE-KEYSTORE-PASSWORD");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableCredentials(environment));

            assertTrue(thrown.getMessage()
                            .startsWith(SecurityConfig.SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY),
                    "the message opens with the property: " + thrown.getMessage());
        }

        @Test
        @DisplayName("encryption switched off asks for no keystore at all")
        void encryptionOffAsksForNoKeystore() {
            RecordingEnvironment environment = recording();
            environment.setProperty(SecurityConfig.SERVER_SSL_ENABLED_PROPERTY, "false");
            environment.setProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PROPERTY, "");
            environment.setProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY, "");

            SecurityConfig.requireUsableCredentials(environment);

            assertAll(
                    () -> assertTrue(environment.read(SecurityConfig.SERVER_SSL_ENABLED_PROPERTY),
                            "the switch is read, because the answer depends on it: "
                                    + environment.reads()),
                    () -> assertFalse(environment.read(SecurityConfig.SERVER_SSL_KEYSTORE_PROPERTY),
                            "the keystore was read. Accepting an empty keystore is a weaker"
                                    + " guarantee than never asking for one, and only the second is"
                                    + " what this name claims: " + environment.reads()),
                    () -> assertFalse(
                            environment.read(SecurityConfig.SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY),
                            "the keystore password was read, for a port that encrypts nothing: "
                                    + environment.reads()));
        }

        @Test
        @DisplayName("only an identity password has to arrive already encoded")
        void onlyAnIdentityPasswordCarriesAnEncodingPrefix() {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableSecret("plaintext",
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY));

            assertAll(
                    () -> assertTrue(thrown.getMessage().contains("encoding prefix"),
                            "an identity password without a prefix is refused: "
                                    + thrown.getMessage()),
                    () -> SecurityConfig.requireUsableSecret("plaintext",
                            SecurityConfig.DATASOURCE_PASSWORD_PROPERTY),
                    () -> SecurityConfig.requireUsableSecret("plaintext",
                            SecurityConfig.SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY));
        }

        @Test
        @DisplayName("the guard is a static post-processor bean, so it runs before the datasource")
        void theGuardRunsBeforeTheDatasource() throws Exception {
            Method factory = SecurityConfig.class.getDeclaredMethod("publishedCredentialGuard");

            assertAll(
                    () -> assertTrue(Modifier.isStatic(factory.getModifiers()),
                            "a non-static factory would build the configuration class early"),
                    () -> assertTrue(factory.isAnnotationPresent(Bean.class),
                            "the guard reaches the context as a bean"),
                    () -> assertTrue(BeanFactoryPostProcessor.class
                                    .isAssignableFrom(factory.getReturnType()),
                            "a post-processor is built before an ordinary bean, which is what"
                                    + " puts this check ahead of the connection pool"));
        }
    }
    @Nested
    @DisplayName("Only approved adaptive password encodings are accepted")
    class ApprovedPasswordEncodings {

        /**
         * Encodings Spring Security's stock delegating encoder maps and this platform refuses.
         *
         * <p>{@code noop} stores a password in plain text. {@code MD4}, {@code MD5},
         * {@code SHA-1}, {@code SHA-256}, {@code sha256} and {@code ldap} are unsalted or
         * single-pass digests, so a guess costs one hash. {@code pbkdf2} without a suffix names
         * the parameter set Spring Security shipped before 5.8. {@code argon2} and {@code scrypt}
         * are adaptive, and they are refused for a different reason: both implementations call
         * Bouncy Castle, which is not a dependency of this platform.
         */
        private final List<String> refusedEncodings = List.of(
                "noop", "MD4", "MD5", "SHA-1", "SHA-256", "sha256", "ldap", "pbkdf2",
                "argon2", "argon2@SpringSecurity_v5_8", "scrypt", "scrypt@SpringSecurity_v5_8");

        /** A password no identity holds, used where a value has to be present and mean nothing. */
        private static final String SAMPLE = "a-value-that-authenticates-nothing";

        @Test
        @DisplayName("the allowlist names bcrypt and the current pbkdf2 parameter set, and no more")
        void theAllowlistNamesTwoAdaptiveEncodings() {
            assertAll(
                    () -> assertEquals(List.of("bcrypt", "pbkdf2@SpringSecurity_v5_8"),
                            SecurityConfig.APPROVED_PASSWORD_ENCODINGS,
                            "the allowlist is the whole set of encodings a configured password may"
                                    + " declare, so a third entry is a decision and not a detail"),
                    () -> assertEquals(10, SecurityConfig.BCRYPT_MINIMUM_COST,
                            "ten is the cost the generation recipes in .env.example and ci.yml"
                                    + " produce, so lowering the floor accepts a hash neither"
                                    + " recipe would generate"));
        }

        @Test
        @DisplayName("every legacy and plaintext encoding is refused at start-up")
        void everyLegacyEncodingIsRefusedAtStartUp() {
            for (String encoding : refusedEncodings) {
                IllegalStateException thrown = assertThrows(IllegalStateException.class,
                        () -> SecurityConfig.requireUsableSecret("{" + encoding + "}" + SAMPLE,
                                SecurityConfig.IDENTITY_PASSWORD_PROPERTY),
                        "an identity password declaring {" + encoding + "} must stop start-up");
                assertAll(
                        () -> assertTrue(thrown.getMessage().contains("{" + encoding + "}"),
                                "the message names the encoding that was declared: "
                                        + thrown.getMessage()),
                        () -> assertFalse(thrown.getMessage().contains(SAMPLE),
                                "no message carries the value: " + thrown.getMessage()));
            }
        }

        @Test
        @DisplayName("an approved encoding passes, and a bcrypt cost below the floor does not")
        void anApprovedEncodingPassesAndACheapBcryptCostDoesNot() {
            String cheap = "{bcrypt}$2a$04$" + "0123456789012345678901"
                    + "0123456789012345678901234567890";
            IllegalStateException tooCheap = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableSecret(cheap,
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY));
            IllegalStateException notAHash = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableSecret("{bcrypt}" + SAMPLE,
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY));
            IllegalStateException noPrefix = assertThrows(IllegalStateException.class,
                    () -> SecurityConfig.requireUsableSecret(SAMPLE,
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY));

            assertAll(
                    () -> SecurityConfig.requireUsableSecret(ENCODED_PASSWORD,
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY),
                    () -> SecurityConfig.requireUsableSecret(
                            "{pbkdf2@SpringSecurity_v5_8}"
                                    + Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
                                            .encode(SAMPLE),
                            SecurityConfig.IDENTITY_PASSWORD_PROPERTY),
                    () -> assertTrue(tooCheap.getMessage().contains("cost of 4"),
                            "the message names the cost that was declared: "
                                    + tooCheap.getMessage()),
                    () -> assertTrue(notAHash.getMessage().contains("no bcrypt"),
                            "a value declaring bcrypt and carrying something else is refused: "
                                    + notAHash.getMessage()),
                    () -> assertTrue(noPrefix.getMessage().contains("encoding prefix"),
                            "a value declaring no encoding at all is refused: "
                                    + noPrefix.getMessage()));
        }

        @Test
        @DisplayName("the encoder bean refuses a refused encoding rather than answering false")
        void theEncoderBeanRefusesARefusedEncodingRatherThanAnsweringFalse() {
            PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

            assertAll(
                    () -> assertInstanceOf(DelegatingPasswordEncoder.class, encoder,
                            "the bean delegates by encoding identifier"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> encoder.matches(SAMPLE, "{noop}" + SAMPLE),
                            "a plaintext stored value must throw. Answering false would read as a"
                                    + " wrong password and leave the encoding in place"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> encoder.matches(SAMPLE, "{MD5}" + SAMPLE),
                            "an unsalted digest must throw for the same reason"),
                    () -> assertTrue(encoder.encode(SAMPLE).startsWith("{bcrypt}$2"),
                            "this class encodes with bcrypt, so a hash it generates is one it"
                                    + " accepts"));
        }

        @Test
        @DisplayName("each test hash still verifies the plaintext declared beside it")
        void eachTestHashStillVerifiesThePlaintextDeclaredBesideIt() {
            PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

            assertAll(
                    () -> assertTrue(encoder.matches(TestIdentityPasswords.ADMIN_PASSWORD,
                                    TestIdentityPasswords.ADMIN_PASSWORD_HASH),
                            "the ADMIN hash in TestIdentityPasswords no longer verifies its"
                                    + " plaintext, so every test that authenticates as that"
                                    + " identity would fail for a reason unrelated to its subject"),
                    () -> assertTrue(encoder.matches(TestIdentityPasswords.USER_PASSWORD,
                                    TestIdentityPasswords.USER_PASSWORD_HASH),
                            "the USER hash in TestIdentityPasswords no longer verifies its"
                                    + " plaintext, so every test that authenticates as that"
                                    + " identity would fail for a reason unrelated to its subject"),
                    () -> assertTrue(encoder.matches(TestIdentityPasswords.MONITORING_PASSWORD,
                                    TestIdentityPasswords.MONITORING_PASSWORD_HASH),
                            "the MONITORING hash in TestIdentityPasswords no longer verifies its"
                                    + " plaintext, so every test that authenticates as that"
                                    + " identity would fail for a reason unrelated to its subject"));
        }
    }

    @Nested
    @DisplayName("Cross-site request forgery, refused by the shape of the request")
    class CrossSiteRequestForgery {

        /**
         * Builds a request of one method carrying one content type.
         *
         * @param method      the HTTP method
         * @param contentType the {@code Content-Type} header, or null to send none
         * @return the request the check receives
         */
        private static MockHttpServletRequest request(String method, String contentType) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod(method);
            if (contentType != null) {
                request.setContentType(contentType);
            }
            return request;
        }

        @Test
        @DisplayName("a form-encoded POST is the forged shape and is refused")
        void aFormEncodedPostIsRefused() {
            assertTrue(SecurityConfig.isBrowserSimpleStateChange(
                            request("POST", MediaType.APPLICATION_FORM_URLENCODED_VALUE)),
                    "a browser sends exactly this cross-origin with no preflight, carrying the"
                            + " credential it holds for this origin");
        }

        @Test
        @DisplayName("multipart and plain text are refused for the same reason")
        void multipartAndPlainTextAreRefused() {
            assertTrue(SecurityConfig.isBrowserSimpleStateChange(
                    request("POST", MediaType.MULTIPART_FORM_DATA_VALUE)));
            assertTrue(SecurityConfig.isBrowserSimpleStateChange(
                    request("POST", MediaType.TEXT_PLAIN_VALUE)));
        }

        @Test
        @DisplayName("a charset parameter does not hide a form encoding")
        void aCharsetParameterDoesNotHideAFormEncoding() {
            assertTrue(SecurityConfig.isBrowserSimpleStateChange(
                            request("POST", "Application/X-WWW-Form-Urlencoded; charset=UTF-8")),
                    "the comparison ignores parameters and case, or the control is one header"
                            + " away from being bypassed");
        }

        @Test
        @DisplayName("a JSON POST is not the forged shape and passes this check")
        void aJsonPostPassesThisCheck() {
            assertFalse(SecurityConfig.isBrowserSimpleStateChange(
                            request("POST", MediaType.APPLICATION_JSON_VALUE)),
                    "application/json is not a content type a browser can send cross-origin"
                            + " without asking first, so it needs no refusal here");
        }

        @Test
        @DisplayName("a POST naming no media type reaches the route, which answers 415")
        void aPostNamingNoMediaTypeReachesTheRoute() {
            assertFalse(SecurityConfig.isBrowserSimpleStateChange(request("POST", null)),
                    "a missing header is a caller's mistake and reads 415; a form encoding is the"
                            + " shape of an attack and reads 403");
        }

        @Test
        @DisplayName("a read is never refused by this check, whatever it names")
        void aReadIsNeverRefusedByThisCheck() {
            assertFalse(SecurityConfig.isBrowserSimpleStateChange(
                            request("GET", MediaType.APPLICATION_FORM_URLENCODED_VALUE)),
                    "a read changes nothing, so forging one achieves nothing");
            assertFalse(SecurityConfig.isBrowserSimpleStateChange(
                    request("HEAD", MediaType.TEXT_PLAIN_VALUE)));
        }

        @Test
        @DisplayName("every state-changing method is covered, not only the one a browser can send")
        void everyStateChangingMethodIsCovered() {
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                assertTrue(SecurityConfig.isBrowserSimpleStateChange(
                                request(method, MediaType.TEXT_PLAIN_VALUE)),
                        method + " changes state and costs nothing to cover");
            }
        }

        @Test
        @DisplayName("the refused set is exactly the three CORS-simple content types")
        void theRefusedSetIsExactlyTheThreeSimpleContentTypes() {
            assertEquals(List.of(MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                            MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.TEXT_PLAIN_VALUE),
                    SecurityConfig.BROWSER_SIMPLE_CONTENT_TYPES,
                    "a fourth entry would refuse a request no browser can forge, and a missing one"
                            + " would leave a route reachable from another origin");
        }
    }
}

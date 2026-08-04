package com.carddemo.ledger.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.ledger.config.SecurityConfig.SecurityIdentities;
import com.carddemo.ledger.config.SecurityConfig.SecurityIdentities.Identity;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Tests over {@link SecurityConfig} for the ledger posting service.
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
@DisplayName("SecurityConfig, request authentication and authorization in ledger posting")
class SecurityConfigTest {

    /** Account identifier the stub caller owns, from record 1 of app/data/ASCII/acctdata.txt. */
    private static final String OWNED_ACCOUNT = "00000000001";

    /** Account identifier the stub caller does not own. */
    private static final String OTHER_ACCOUNT = "00000000002";

    /** Masked card the stub caller owns, from record 1 of app/data/ASCII/cardxref.txt. */
    private static final String OWNED_CARD = "************5740";

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
        @DisplayName("a card is owned by its masked form, never by a card number")
        void cardOwnershipUsesTheMaskedForm() {
            AuthorizationManager<RequestAuthorizationContext> rule =
                    SecurityConfig.ownsPathVariable(SecurityConfig.CARD_SCOPE, "maskedCardNumber");
            assertAll(
                    () -> assertTrue(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("maskedCardNumber", OWNED_CARD)).isGranted(),
                            "the identity carries SCOPE_CARD_" + OWNED_CARD),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("maskedCardNumber", "************9999"))
                                    .isGranted(),
                            "another card sharing no last four digits is refused"),
                    () -> assertFalse(rule.authorize(SecurityConfigTest::cardholder,
                                    pathContext("maskedCardNumber", "0500024453765740"))
                                    .isGranted(),
                            "a full card number matches no scope, so a caller cannot substitute "
                                    + "one for the masked form the route declares"));
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

        /** An environment with every checked credential fit to run with. */
        private MockEnvironment usable() {
            return new MockEnvironment()
                    .withProperty(SecurityConfig.DATASOURCE_PASSWORD_PROPERTY, "a-generated-value")
                    .withProperty(SecurityConfig.BROKER_JAAS_PROPERTY, BROKER_JAAS);
        }

        @Test
        @DisplayName("a usable configuration passes")
        void usableConfigurationPasses() {
            SecurityConfig.requireUsableCredentials(usable());
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
            SecurityConfig.requireUsableCredentials(usable()
                    .withProperty(SecurityConfig.SERVER_SSL_ENABLED_PROPERTY, "false")
                    .withProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PROPERTY, "")
                    .withProperty(SecurityConfig.SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY, ""));
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
}

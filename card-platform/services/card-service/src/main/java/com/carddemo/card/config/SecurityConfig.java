package com.carddemo.card.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Request authentication and authorization for the card service.
 *
 * <p>This service owns the card record, and the card record carries the two values that must never
 * leave it: the full card number and, in {@code card_verification_value}, authentication data.
 * {@code entity/CardEntity} keeps both inside itself and
 * {@code entity/CardholderDataExposureTest} asserts that it does. The routes below add the second
 * half of that guarantee, which is that only an entitled caller reaches a card at all.
 *
 * <h2>Where the two roles come from</h2>
 *
 * <p>{@code app/cbl/COSGN00C.cbl} is the only place in the source that establishes an identity.
 * It reads the security file, compares the supplied password against {@code SEC-USR-PWD} at
 * {@code app/cbl/COSGN00C.cbl:L223}, and forks on {@code SEC-USR-TYPE PIC X(01)} at
 * {@code app/cpy/CSUSR01Y.cpy}: the value {@code A} reaches the administrator menu at
 * {@code app/cbl/COSGN00C.cbl:L232-L236} and every other value reaches the ordinary menu. Those two
 * outcomes become {@code ROLE_ADMIN} and {@code ROLE_USER} here, and nothing else.
 * {@code ROLE_MONITORING} is additive: it carries no API access and exists only so a metrics
 * scrape can authenticate without holding a business role.
 *
 * <p>DEVIATION, deliberate: the source comparison at {@code app/cbl/COSGN00C.cbl:L223} is a
 * plaintext comparison of two eight-character fields. This class does not reproduce it. Each
 * configured identity carries an already-encoded password, {@link PasswordEncoderFactories}
 * supplies the delegating encoder that reads its {@code {bcrypt}} prefix, and no plaintext password
 * is stored, compared or logged anywhere on this platform.
 *
 * <h2>The four properties this class holds</h2>
 *
 * <ol>
 * <li><b>Default deny.</b> The last rule of the API chain is {@code denyAll()}, not
 * {@code authenticated()}. A route that no rule below names is refused, so adding an endpoint
 * without deciding who may reach it fails closed. Adding a route means adding a line to
 * {@link #apiSecurity(HttpSecurity)} that states its rule; weakening the final rule instead
 * defeats the whole arrangement. The one exception is the container's own error and asynchronous
 * dispatch, which the first rule permits: neither is a request a caller made, and refusing them
 * would answer 403 to every request that was going to be a 404.</li>
 * <li><b>Ownership, not merely authentication.</b> An authenticated caller is not thereby entitled
 * to another subject's data. {@link #ownsPathVariable(String, String)} compares the identifier in
 * the request path against the authorities the identity carries, so a caller reaches its own rows
 * and receives 403 for anyone else's. The check runs in the filter chain, ahead of every handler,
 * so no handler can forget it.</li>
 * <li><b>No session and no cross-site request forgery token.</b> The session policy is
 * {@code STATELESS} and the surface is a JavaScript Object Notation (JSON) API reached by a
 * program, so no cookie carries authentication and the forgery a token defends against cannot
 * occur. Disabling the token while keeping a cookie session would be the mistake; both are absent
 * here.</li>
 * <li><b>No caching of a response.</b> Spring Security writes
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} together with
 * {@code Pragma: no-cache} and {@code Expires: 0} on every response, and
 * {@link #apiSecurity(HttpSecurity)} names that writer rather than relying on the default, so a
 * reader can see it. Financial and personal data therefore reach no shared cache and no browser
 * store.</li>
 * </ol>
 *
 * <h2>The management port</h2>
 *
 * <p>{@link #managementSecurity(HttpSecurity)} runs first and covers the actuator surface, which
 * {@code src/main/resources/application.yml} places on its own port. Health answers without a
 * credential because a container and a Kubernetes probe have none, and every other endpoint,
 * metrics and the Prometheus scrape included, requires {@code ROLE_MONITORING}. An identity with
 * a business role receives 403 there, and an anonymous caller receives 401.
 *
 * <h2>Transport</h2>
 *
 * <p>HTTP Basic sends a credential as base-64 text, which is encoding and not encryption, so the
 * transport has to be encrypted. {@code card-platform/deploy/k8s/30-configmap.yaml} carries the
 * Transport Layer Security settings, and a deployment that terminates them elsewhere keeps the hop
 * to this service inside a boundary it trusts.
 *
 * <h2>Why this file is repeated in each service</h2>
 *
 * <p>{@code card-platform/pom.xml} bans a dependency from one service on another, which is the
 * property that keeps the consumers independent, and neither shared library is a place for this
 * code: {@code libs/cobol-compat} carries no framework annotation by design and
 * {@code libs/event-contracts} carries the event contracts. Six small copies that each state
 * their own routes are the intended shape, and each copy differs only in
 * {@link #apiSecurity(HttpSecurity)}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityConfig.SecurityIdentities.class)
public class SecurityConfig {

    /** Role an administrator identity carries, from {@code SEC-USR-TYPE} value {@code A}. */
    static final String ROLE_ADMIN = "ADMIN";

    /** Role every other identity carries, from any other {@code SEC-USR-TYPE} value. */
    static final String ROLE_USER = "USER";

    /** Additive role a metrics scrape carries. It reaches no business route. */
    static final String ROLE_MONITORING = "MONITORING";

    /** Prefix Spring Security expects on a role authority. */
    static final String ROLE_PREFIX = "ROLE_";

    /** Prefix every ownership authority carries, followed by a kind and an identifier. */
    static final String SCOPE_PREFIX = "SCOPE_";

    /** Ownership kind for an account identifier, {@code ACCT-ID PIC 9(11)}. */
    static final String ACCOUNT_SCOPE = "ACCOUNT";

    /** Ownership kind for a customer identifier, {@code CUST-ID PIC 9(09)}. */
    static final String CUSTOMER_SCOPE = "CUSTOMER";

    /** Ownership kind for a card, named by its masked form and never by a full card number. */
    static final String CARD_SCOPE = "CARD";

    /** Realm name a 401 carries, so a client knows which credential to present. */
    private static final String REALM = "carddemo";

    /**
     * Supplies the encoder that reads the prefix of a configured password.
     *
     * <p>{@link PasswordEncoderFactories#createDelegatingPasswordEncoder()} encodes with bcrypt
     * and verifies against any prefix it knows, so an identity configured today keeps working when
     * a deployment re-encodes it under a newer algorithm. Nothing here encodes a plaintext password
     * at run time: a configured value is already encoded.
     *
     * @return the delegating encoder every identity below is verified against
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Builds the identity store from {@code carddemo.security.users}.
     *
     * <p>Start-up fails when the list is empty. A service that runs with no identity would either
     * refuse every request or, worse, invite a default identity, and neither belongs in a
     * deployment. {@code card-platform/.env.example} documents the variables that carry the
     * encoded passwords, and none of them holds a usable default.
     *
     * @param identities the configured identities
     * @return the identity store the filter chains authenticate against
     * @throws IllegalStateException when no identity is configured
     */
    @Bean
    public UserDetailsService cardDemoIdentities(SecurityIdentities identities) {
        return new InMemoryUserDetailsManager(toUserDetails(identities));
    }

    /**
     * Maps configured identities onto Spring Security users.
     *
     * <p>An identity becomes one authority for its role and one authority per ownership scope, so
     * the whole entitlement of a caller travels in the authority list and no custom principal type
     * is needed. A scope reads {@code SCOPE_ACCOUNT_00000000001} or
     * {@code SCOPE_CARD_1134636222d1a2485d20203d0e970c72124893eb01be73a5fde5fdcccc2c4ac9}:
     * the kind, then the identifier exactly as
     * the column holds it, leading zeros included.
     *
     * @param identities the configured identities
     * @return one {@link UserDetails} per configured identity
     * @throws IllegalStateException when no identity is configured
     */
    static List<UserDetails> toUserDetails(SecurityIdentities identities) {
        if (identities == null || identities.users() == null || identities.users().isEmpty()) {
            throw new IllegalStateException("carddemo.security.users carries no identity. "
                    + "Configure at least one identity with an encoded password before start-up; "
                    + "see card-platform/.env.example.");
        }
        return identities.users().stream().map(SecurityConfig::toUserDetails).toList();
    }

    /**
     * Maps one configured identity onto a Spring Security user.
     *
     * @param identity the configured identity
     * @return the user, carrying its role authority and one authority per ownership scope
     * @throws IllegalStateException when the identity omits a username, a password or a role
     */
    private static UserDetails toUserDetails(SecurityIdentities.Identity identity) {
        require(identity.username(), "username");
        require(identity.password(), "password");
        requireUsableSecret(identity.password(), IDENTITY_PASSWORD_PROPERTY);
        require(identity.role(), "role");
        List<String> scopes = identity.scopes() == null ? List.of() : identity.scopes();
        return User.withUsername(identity.username())
                .password(identity.password())
                .authorities(Stream.concat(Stream.of(ROLE_PREFIX + identity.role()),
                                scopes.stream())
                        .toArray(String[]::new))
                .build();
    }

    /**
     * Rejects an identity with a missing field, naming the field and never the value.
     *
     * @param value the configured value
     * @param field the field name the message reports
     * @throws IllegalStateException when the value is absent or blank
     */
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "every entry of carddemo.security.users carries a " + field);
        }
    }

    /**
     * Secures the actuator surface: health without a credential, everything else for monitoring.
     *
     * <p>The chain runs first because its matcher is the narrower one. Spring Boot exposes health
     * alone by default and documents that the rest wants securing; this chain states that posture
     * explicitly so it survives a change to
     * {@code management.endpoints.web.exposure.include}.
     *
     * @param http the builder Spring Security supplies
     * @return the chain covering every actuator endpoint
     * @throws Exception when the builder rejects the configuration
     */
    @Bean
    @Order(1)
    public SecurityFilterChain managementSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(requests -> requests
                        // The container dispatches an error and an asynchronous
                        // continuation through this chain as well. Neither is a request a
                        // caller made, and authorizing them again turns every 404 into a 403
                        // and hides the real status from a client. The original request was
                        // authorized or refused before either dispatch was created, and
                        // src/main/resources/application.yml leaves every
                        // server.error.include-* key at a value that reveals no detail.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                            .permitAll()
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .anyRequest().hasRole(ROLE_MONITORING))
                .httpBasic(basic -> basic.authenticationEntryPoint(SecurityConfig::unauthorized))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(SecurityConfig::unauthorized)
                        .accessDeniedHandler(forbidden()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .build();
    }

    /**
     * Secures the card service API.
     *
     * <p>Three routes answer, and the first one is shaped by where a card number may appear.
     *
     * <ul>
     * <li>{@code POST /cards/detail} reads one card. The full card number arrives in the request
     * body and never in the path, because a path reaches an access log, a proxy log, a trace and a
     * browser history, and none of those is a place for a Primary Account Number. The route is a
     * {@code POST} for that reason alone: it changes nothing. Ownership cannot be decided in the
     * filter chain here, because the identifier sits in the body, so the handler asks
     * {@link #cardOwnership()} and answers 403 when the caller holds no matching
     * {@code SCOPE_CARD_} authority. That predicate lives in this file beside the three route
     * rules, so the whole of the access control is still readable in one place, and the handler
     * invokes it rather than restating it.</li>
     * <li>{@code PUT /cards} updates one card and is administrator-only. The card number arrives
     * in the body for the same reason. {@code app/cbl/COCRDUPC.cbl} reaches the update screen
     * through the administrator path.</li>
     * <li>{@code GET /cards} lists the cards of one account, which arrives as the
     * {@code accountId} query parameter, and the rule scopes it to the accounts the caller owns.
     * The page size follows the seven-element screen tables of
     * {@code app/cbl/COCRDLIC.cbl}.</li>
     * </ul>
     *
     * @param http the builder Spring Security supplies
     * @return the chain covering every request that is not an actuator endpoint
     * @throws Exception when the builder rejects the configuration
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // The container dispatches an error and an asynchronous
                        // continuation through this chain as well. Neither is a request a
                        // caller made, and authorizing them again turns every 404 into a 403
                        // and hides the real status from a client. The original request was
                        // authorized or refused before either dispatch was created, and
                        // src/main/resources/application.yml leaves every
                        // server.error.include-* key at a value that reveals no detail.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                            .permitAll()
                        .requestMatchers(HttpMethod.POST, "/cards/detail")
                            .hasAnyRole(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/cards")
                            .hasRole(ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/cards")
                            .access(ownsRequestParameter(ACCOUNT_SCOPE, "accountId"))
                        // Default deny. A route named by no rule above is refused.
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(SecurityConfig::unauthorized))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(SecurityConfig::unauthorized)
                        .accessDeniedHandler(forbidden()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .build();
    }

    /**
     * Grants a request only when the caller owns the identifier the path carries.
     *
     * <p>The manager reads one path variable and looks for the authority
     * {@code SCOPE_<kind>_<value>} among the caller's authorities. {@code ROLE_ADMIN} passes
     * every check, which is the administrator fork of {@code app/cbl/COSGN00C.cbl:L232-L236}}
     * expressed as an entitlement. Every other identity reaches its own identifiers and receives
     * 403 for another subject's, which is the control that stops one caller reading another
     * cardholder's account, customer record, balance or alert history.
     *
     * <p>A missing path variable denies. That case means a rule and a route disagree about the
     * shape of a path, and denying is the safe reading of a disagreement.
     *
     * @param kind     the ownership kind, one of {@link #ACCOUNT_SCOPE},
     *                 {@link #CUSTOMER_SCOPE} or {@link #CARD_SCOPE}
     * @param variable name of the path variable the rule captures
     * @return the manager the rule applies
     */
    static AuthorizationManager<RequestAuthorizationContext> ownsPathVariable(String kind,
            String variable) {
        return (authentication, context) -> {
            String value = context.getVariables().get(variable);
            boolean granted = value != null
                    && holds(authentication.get(), SCOPE_PREFIX + kind + "_" + value);
            return new AuthorizationDecision(granted);
        };
    }

    /**
     * Grants a request only when the caller owns the identifier a query parameter carries.
     *
     * <p>A card list is requested by account, and the account arrives as a query parameter rather
     * than as a path segment. The check is otherwise the one
     * {@link #ownsPathVariable(String, String)} performs. An absent parameter denies.
     *
     * @param kind      the ownership kind
     * @param parameter name of the query parameter carrying the identifier
     * @return the manager the rule applies
     */
    static AuthorizationManager<RequestAuthorizationContext> ownsRequestParameter(String kind,
            String parameter) {
        return (authentication, context) -> {
            String value = context.getRequest().getParameter(parameter);
            boolean granted = value != null
                    && holds(authentication.get(), SCOPE_PREFIX + kind + "_" + value);
            return new AuthorizationDecision(granted);
        };
    }

    /**
     * Reports whether the caller of the current request owns one card.
     *
     * <p>{@code POST /cards/detail} carries its identifier in the request body, so the filter chain
     * cannot read it: reading a body inside the chain would consume the stream the handler needs.
     * The rule for that route therefore checks the role alone, and the handler asks this predicate
     * for the ownership half once it has read the body.
     *
     * <p>The scope is named by the masked form and never by the full number, which
     * {@link #CARD_SCOPE} states and which the notification service's own card route already
     * follows. A handler masks the number it read and passes the masked value here.
     *
     * <p>The check is the one {@link #ownsPathVariable(String, String)} performs, including the
     * administrator fork: {@code ROLE_ADMIN} owns every card, which is
     * {@code app/cbl/COSGN00C.cbl:L232-L236} expressed as an entitlement.
     *
     * <p>This predicate is a bean of this file rather than an annotation on a handler. An annotation
     * would put one authority rule outside the chain, where the route table can no longer be read as
     * a table, and it would take a Spring Expression Language string over a body component that no
     * compiler checks. A bean keeps the rule beside the three that surround it and lets a test hand
     * the handler a predicate directly.
     *
     * @return the predicate the card detail handler asks, never {@code null}
     */
    @Bean
    public CardOwnership cardOwnership() {
        return maskedCardNumber -> maskedCardNumber != null
                && holds(SecurityContextHolder.getContext().getAuthentication(),
                        SCOPE_PREFIX + CARD_SCOPE + "_" + maskedCardNumber);
    }

    /**
     * Answers whether the caller of the current request owns one card, named by its masked form.
     *
     * <p>Declared here, next to the route rules, so that every authority this service requires is
     * stated in one file. {@code api/CardController} holds a reference to it and states no rule of
     * its own.
     */
    @FunctionalInterface
    public interface CardOwnership {

        /**
         * Reports whether the caller owns the card.
         *
         * @param maskedCardNumber the masked card number: twelve mask characters then the last four
         *                         digits, or {@code null}
         * @return {@code true} when the caller is an administrator or holds the ownership scope of
         *         that card, and {@code false} for every other caller and for {@code null}
         */
        boolean ownsCard(String maskedCardNumber);
    }

    /**
     * Answers whether an authenticated caller holds an authority, with administrator passing all.
     *
     * @param caller    the authentication under test, possibly {@code null}
     * @param authority the authority the rule requires
     * @return {@code true} when the caller is authenticated and holds the authority or is an
     *         administrator
     */
    private static boolean holds(Authentication caller, String authority) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        String administrator = ROLE_PREFIX + ROLE_ADMIN;
        for (GrantedAuthority granted : caller.getAuthorities()) {
            String held = granted.getAuthority();
            if (administrator.equals(held) || authority.equals(held)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Answers a request that carried no usable credential with 401.
     *
     * <p>The body is a problem document and the detail names no account, card, customer or route.
     * A 401 that repeated the requested resource would put an identifier a caller does not own
     * into that caller's own logs. {@code WWW-Authenticate} is present because a 401 has to say
     * which scheme it expects.
     *
     * @param request  the request that failed to authenticate
     * @param response the response to write
     * @param failure  the authentication failure, whose message reaches no output
     * @throws IOException when the response cannot be written
     */
    static void unauthorized(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
        problem(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "This request carried no usable credential.");
    }

    /**
     * Answers an authenticated request that may not use an operation with 403.
     *
     * <p>The detail names neither the operation nor the identifier, so a caller probing for
     * another subject's rows learns only that it may not have them. That is the
     * privacy-preserving answer: a 404 would leak that the row exists and a detailed 403 would
     * leak the identifier back into a log.
     *
     * @return the handler the chains install
     */
    static AccessDeniedHandler forbidden() {
        return (request, response, denied) -> problem(response, HttpStatus.FORBIDDEN, "Forbidden",
                "This identity may not use this operation.");
    }

    /**
     * Writes one problem document, as RFC 9457 describes it.
     *
     * <p>The four members are written by hand rather than through an object mapper, so the body of
     * a security failure depends on no serializer configuration and can carry no field a mapper
     * added. {@code Cache-Control: no-store} keeps the answer out of every cache.
     *
     * @param response the response to write
     * @param status   the status to send
     * @param title    the short, human-readable title
     * @param detail   the explanation, which names no identifier and no route
     * @throws IOException when the response cannot be written
     */
    private static void problem(HttpServletResponse response, HttpStatus status, String title,
            String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}");
    }

    /**
     * The identities {@code carddemo.security.users} configures.
     *
     * <p>Each entry carries a username, an already-encoded password, one role and, for an ordinary
     * identity, the ownership scopes it holds. An administrator needs no scope: it passes every
     * ownership check by role.
     *
     * @param users the configured identities, at least one
     */
    @ConfigurationProperties(prefix = "carddemo.security")
    public record SecurityIdentities(List<Identity> users) {

        /**
         * One configured identity.
         *
         * @param username the identity, matching {@code SEC-USR-ID PIC X(08)} in spirit though
         *                 not in width
         * @param password the encoded password, carrying its algorithm prefix, for example
         *                 {@code {bcrypt}$2a$10$...}. A plaintext value authenticates nothing
         * @param role     {@code ADMIN}, {@code USER} or {@code MONITORING}
         * @param scopes   the ownership authorities this identity holds, each
         *                 {@code SCOPE_ACCOUNT_<id>}, {@code SCOPE_CUSTOMER_<id>} or
         *                 {@code SCOPE_CARD_<token>}. Empty for an administrator and for a
         *                 monitoring identity
         */
        public record Identity(String username, String password, String role, List<String> scopes) {
        }
    }

    // ------------------------------------------------------------------------------------
    // Published-credential guard. No COBOL ancestor: the CardDemo source compares a stored password
    // against a supplied one directly at app/cbl/COSGN00C.cbl:L223, so it has no notion of a
    // credential being unfit to run with. This platform publishes example values in
    // card-platform/.env.example and deploy/k8s/31-secret.example.yaml, and a reader following the
    // quickstart unchanged would otherwise be running with credentials this repository states in
    // plain text.
    //
    // Refusing them at start-up rather than at first use is the point. A placeholder bcrypt hash
    // does not throw when it is checked: the encoder logs that the value does not look like BCrypt
    // and returns false, so the service boots, answers every request with 401, and looks
    // misconfigured rather than insecure. A start-up failure naming the variable says what is
    // wrong once.
    //
    // Four credentials reach this service, and all four are checked here: the password for its own
    // database login, the broker login entry that carries its Simple Authentication and Security
    // Layer password, the identity password hashes checked as each identity is mapped, and the
    // keystore password, checked only when a port is actually encrypted.
    // ------------------------------------------------------------------------------------

    /** Marks a value this repository publishes as an example rather than as a credential. */
    static final String PLACEHOLDER_MARKER = "REPLACE";

    /**
     * A database password this repository has published in plain text. Every example credential
     * carries {@link #PLACEHOLDER_MARKER} now, and this one literal is kept beside that rule
     * because it reached running containers and written documentation before the rule existed.
     */
    static final String PUBLISHED_DATABASE_PASSWORD = "carddemo-local-demo-only";

    /** Property carrying the datasource password, checked at start-up. */
    static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.password";

    /**
     * Property carrying this service's broker credential. The value is a Java Authentication and
     * Authorization Service login entry with the password inside it, so no message below names the
     * value and no log line records it.
     */
    static final String BROKER_JAAS_PROPERTY = "spring.kafka.properties.sasl.jaas.config";

    /** Property that switches Transport Layer Security on for the service port. */
    static final String SERVER_SSL_ENABLED_PROPERTY = "server.ssl.enabled";

    /** Property naming the keystore the encrypted ports read. */
    static final String SERVER_SSL_KEYSTORE_PROPERTY = "server.ssl.key-store";

    /** Property carrying the keystore password the encrypted ports read. */
    static final String SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY = "server.ssl.key-store-password";

    /**
     * Property carrying a request identity's password, the one secret that must arrive already
     * encoded. A datasource, broker or keystore password is presented to another process verbatim
     * and cannot be a hash.
     */
    static final String IDENTITY_PASSWORD_PROPERTY = "carddemo.security.users[].password";

    /**
     * Refuses to start when a credential this repository publishes reached the running service.
     *
     * <p>The method is static and the type it returns is a {@link BeanFactoryPostProcessor},
     * which is what makes the check run early. Spring builds every post-processor before it builds
     * an ordinary bean, so this refusal lands before the connection pool, the schema migration and
     * the broker client exist. Declared as a plain bean instead, it would run after the datasource
     * had already tried the password and failed with a message about a database rather than about
     * a variable, which sends an operator looking in the wrong place.
     *
     * @return a guard whose run proved every checked credential fit to run with
     */
    @Bean
    public static PublishedCredentialGuard publishedCredentialGuard() {
        return new PublishedCredentialGuard();
    }

    /**
     * Checks the three credentials this service reads from configuration, and the keystore
     * password when a port is encrypted.
     *
     * <p>Identity password hashes are not checked here. Each one is checked as its identity is
     * mapped, which is where the property name is known.
     *
     * @param environment the resolved environment, which already carries substituted variables
     * @throws IllegalStateException when a checked credential is absent, unusable or published
     */
    static void requireUsableCredentials(Environment environment) {
        String datasourcePassword = environment.getProperty(DATASOURCE_PASSWORD_PROPERTY);
        requireUsableSecret(datasourcePassword, DATASOURCE_PASSWORD_PROPERTY);
        if (PUBLISHED_DATABASE_PASSWORD.equals(datasourcePassword)) {
            throw new IllegalStateException(DATASOURCE_PASSWORD_PROPERTY
                    + " holds a value this repository publishes as an example."
                    + " Set a distinct password for this service before start-up.");
        }
        requireUsableSecret(environment.getProperty(BROKER_JAAS_PROPERTY), BROKER_JAAS_PROPERTY);
        requireKeystoreMaterialWhenEncrypted(environment);
    }

    /**
     * Refuses to start with an encrypted port and nothing behind it.
     *
     * <p>Switching encryption on and leaving the keystore empty is a configuration that fails later
     * and further away, while a service that never encrypted anything keeps answering. The check
     * runs only when a port is actually encrypted, so the shipped configuration, which encrypts
     * neither port, needs no keystore and no keystore password.
     *
     * @param environment the resolved environment
     * @throws IllegalStateException when a port is encrypted and its keystore is absent or unusable
     */
    private static void requireKeystoreMaterialWhenEncrypted(Environment environment) {
        if (!environment.getProperty(SERVER_SSL_ENABLED_PROPERTY, Boolean.class, Boolean.FALSE)) {
            return;
        }
        String keystore = environment.getProperty(SERVER_SSL_KEYSTORE_PROPERTY);
        if (keystore == null || keystore.isBlank()) {
            throw new IllegalStateException(SERVER_SSL_KEYSTORE_PROPERTY + " is empty while "
                    + SERVER_SSL_ENABLED_PROPERTY + " is true. Name the keystore this service"
                    + " presents, or switch encryption off; deploy/k8s/31-secret.example.yaml"
                    + " templates the material.");
        }
        requireUsableSecret(environment.getProperty(SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY),
                SERVER_SSL_KEYSTORE_PASSWORD_PROPERTY);
    }

    /**
     * Refuses a secret that is absent, blank, a published placeholder, or an identity password
     * with no encoding prefix.
     *
     * <p>No message carries the value. Each one names the property and states which of the three
     * conditions failed, which is everything an operator needs and nothing an attacker does.
     *
     * @param value    the configured secret
     * @param property the property name the message reports
     * @throws IllegalStateException when the secret is unfit to run with
     */
    static void requireUsableSecret(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is not set. See card-platform/.env.example"
                    + " for the variable and how to generate a value for it.");
        }
        if (value.contains(PLACEHOLDER_MARKER)) {
            throw new IllegalStateException(property + " still holds the placeholder this"
                    + " repository publishes. Generate a real value; card-platform/.env.example"
                    + " carries the command.");
        }
        if (IDENTITY_PASSWORD_PROPERTY.equals(property) && !value.startsWith("{")) {
            throw new IllegalStateException(property + " carries no encoding prefix such as"
                    + " {bcrypt}. A value without one authenticates nobody and would let this"
                    + " service start looking misconfigured rather than refusing to start.");
        }
    }

    /**
     * Evidence that the published-credential guard ran.
     *
     * <p>The type carries no state. Its presence in the context is what says the check passed, and
     * a test can assert the bean exists rather than asserting on a log line.
     *
     * <p>It post-processes nothing. Implementing the interface is how it earns its place in the
     * early phase of a context refresh, and the environment it reads is a singleton the context
     * registers before that phase begins.
     */
    public static final class PublishedCredentialGuard implements BeanFactoryPostProcessor {

        /** Built only by {@link SecurityConfig#publishedCredentialGuard()}. */
        private PublishedCredentialGuard() {
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            requireUsableCredentials(beanFactory.getBean(Environment.class));
        }
    }
}

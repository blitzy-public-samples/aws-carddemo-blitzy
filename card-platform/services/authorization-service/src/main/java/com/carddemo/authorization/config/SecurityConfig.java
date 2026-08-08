package com.carddemo.authorization.config;

import com.carddemo.authorization.domain.AuthenticatedActor;
import com.carddemo.authorization.domain.CallerNotEntitledException;
import com.carddemo.authorization.domain.RequestCaller;
import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import com.carddemo.cobol.PanMasker;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Request authentication and authorization for the authorization service.
 *
 * <p>This service holds the only synchronous surface a client reaches, and it is the sole writer
 * of an authorization decision. One route answers, {@code POST /authorizations}, and every other
 * request is refused by the final rule of {@link #apiSecurity(HttpSecurity)}.
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
 * <p>{@code ROLE_ACQUIRER} is additive too, and it is the only role that reaches
 * {@code POST /authorizations}. The route accepts a card in the request body and authorizes
 * against whichever account the cross-reference resolves it to, so it grants its caller reach
 * over every card the platform holds. A cardholder identity must therefore not hold it: an
 * ordinary {@code ROLE_USER} carries ownership scopes over its own account, customer and card,
 * and this route consults none of them. The acquirer is a machine identity a point-of-sale
 * network presents, it owns no row, and it is configured separately from every cardholder.
 *
 * <p>DEVIATION, deliberate: the source comparison at {@code app/cbl/COSGN00C.cbl:L223} is a
 * plaintext comparison of two eight-character fields. This class does not reproduce it. Each
 * configured identity carries an already-encoded password, {@link #approvedPasswordEncoder()}
 * verifies it against an allowlist of adaptive encodings, and no plaintext password is stored,
 * compared or logged anywhere on this platform. {@code {noop}} names the plaintext encoding, and it
 * is refused at start-up, so the source comparison cannot return through configuration either.
 *
 * <h2>The five properties this class holds</h2>
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
 * <li><b>No session, no forgery token, and a compensating cross-site check.</b> The session policy
 * is {@code STATELESS}, so no cookie carries authentication and a forgery token has no session to
 * live in. The token protection is therefore off, and that on its own is not a defence: a browser
 * attaches a cached HTTP Basic credential to a request a foreign page caused, without asking the
 * person reading that page. {@link CrossSiteRequestFilter} is the control that closes it. Every
 * state-changing request has to declare a first-party {@code Sec-Fetch-Site}, name this origin if
 * it names an origin at all, and carry a non-simple request header no HTML form can set.</li>
 * <li><b>No caching of a response.</b> Spring Security writes
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} together with
 * {@code Pragma: no-cache} and {@code Expires: 0} on every response, and
 * {@link #apiSecurity(HttpSecurity)} names that writer rather than relying on the default, so a
 * reader can see it. Financial and personal data therefore reach no shared cache and no browser
 * store.</li>
 * <li><b>A bounded request rate.</b> {@link RequestRateCeilingFilter} runs ahead of this chain and
 * bounds requests from one source, requests presenting one identity, state-changing requests from
 * one source, failed authentications from one source, and requests in flight. It runs ahead rather
 * than behind because the expensive part of a refused credential is the bcrypt verification, so a
 * caller nobody bounded would otherwise spend this service's processor on guesses.</li>
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
 *
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

    /**
     * Additive role the acquiring workload carries, and the only role that authorizes a card.
     *
     * <p>It reaches {@code POST /authorizations} and nothing else. No cardholder identity carries
     * it, because the route reaches every card the platform holds rather than the caller's own.
     */
    static final String ROLE_ACQUIRER = "ACQUIRER";

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
     * Encoding identifier of the adaptive hash this platform encodes with, and the identifier a
     * configured password carries in braces ahead of the hash itself.
     */
    static final String BCRYPT_ENCODING_ID = "bcrypt";

    /**
     * Encoding identifier of the second accepted adaptive hash.
     *
     * <p>The suffix is part of the identifier. {@code pbkdf2} alone names the weaker parameter set
     * Spring Security shipped before 5.8, and a password carrying that identifier is refused here.
     */
    static final String PBKDF2_ENCODING_ID = "pbkdf2@SpringSecurity_v5_8";

    /**
     * Lowest bcrypt cost a configured password may carry, and the cost this class encodes with.
     *
     * <p>Cost is logarithmic, so ten means 2^10 key-derivation rounds. Ten is also what the
     * generation recipes in {@code card-platform/.env.example} and
     * {@code .github/workflows/ci.yml} produce, so a hash either recipe generates is accepted and a
     * hash carrying a smaller cost is not.
     */
    static final int BCRYPT_MINIMUM_COST = 10;

    /**
     * Every encoding a configured password may declare, in the order the encoder tries them.
     *
     * <p>This is an allowlist rather than a preference, and the encodings it leaves out are the
     * point of it. Spring Security's stock delegating encoder also maps {@code noop},
     * {@code MD4}, {@code MD5}, {@code SHA-1}, {@code SHA-256}, {@code sha256} and {@code ldap}.
     * Those mappings exist so that a deployment holding legacy hashes can migrate off them. This
     * platform holds none, so mapping them would only mean that a plaintext or unsalted-digest
     * password verifies successfully.
     *
     * <p>{@code argon2} and {@code scrypt} are left out for a different reason. Both
     * implementations call Bouncy Castle, which is not a dependency of this platform, so a password
     * carrying either identifier would fail at the first authentication rather than at start-up.
     * Refusing them here reports the gap while an operator is still reading the message.
     */
    static final List<String> APPROVED_PASSWORD_ENCODINGS =
            List.of(BCRYPT_ENCODING_ID, PBKDF2_ENCODING_ID);

    /**
     * Matches a bcrypt hash and captures its two cost digits.
     *
     * <p>The three version markers are the ones bcrypt implementations write: {@code $2a$} is the
     * original, {@code $2b$} the corrected form, and {@code $2y$} a variant of it. Fifty-three
     * characters follow the cost: twenty-two of salt and thirty-one of hash, in the radix-64
     * alphabet bcrypt uses.
     */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("^\\$2[aby]\\$([0-9]{2})\\$[./A-Za-z0-9]{53}$");

    /**
     * Supplies the encoder that reads the prefix of a configured password.
     *
     * <p>Nothing here encodes a plaintext password at run time: a configured value arrives already
     * encoded. The encode side exists so that a hash generated with this class carries the
     * parameters the verify side accepts.
     *
     * @return the delegating encoder every identity below is verified against
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return approvedPasswordEncoder();
    }

    /**
     * Builds the delegating encoder over {@link #APPROVED_PASSWORD_ENCODINGS} and nothing else.
     *
     * <p>An identifier the map does not carry reaches the unmapped-identifier encoder Spring
     * Security installs by default, which throws rather than answering false. A refused encoding
     * therefore cannot authenticate anybody, and it cannot be mistaken for a wrong password either.
     *
     * @return an encoder that verifies an approved encoding and refuses every other
     */
    static DelegatingPasswordEncoder approvedPasswordEncoder() {
        Map<String, PasswordEncoder> approved = new LinkedHashMap<>();
        approved.put(BCRYPT_ENCODING_ID, new BCryptPasswordEncoder(BCRYPT_MINIMUM_COST));
        approved.put(PBKDF2_ENCODING_ID, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        return new DelegatingPasswordEncoder(BCRYPT_ENCODING_ID, approved);
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
     * {@code SCOPE_CARD_<card token>}: the kind, then the identifier exactly as the column holds
     * it, leading zeros included. A card token is the sixty-four hexadecimal characters
     * {@code com.carddemo.cobol.PanMasker.cardToken} derives under the configured card-token key,
     * so an authority granted for one card names that card alone and no card sharing its last four
     * digits.
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
        requireRecordableUsername(identity.username());
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
     * Rejects a configured username the decision audit column cannot record whole.
     *
     * <p>Every decision this service takes records the name of the identity that asked for it, and
     * {@code authorization_decision.actor} is
     * {@value AuthorizationDecisionEntity#ACTOR_MAX_LENGTH} characters wide. A wider name used to be
     * shortened to fit, which made two identities agreeing in their leading characters share one
     * recorded actor: the shipped nine-character monitoring identity reached the column as
     * {@code monitor0} while the column was eight characters wide. An audit row that cannot name one
     * identity does not audit.
     *
     * <p>Refusing at start-up is the point. The alternative is a service that starts and then fails, or
     * silently mis-attributes, at its first decision. A username is not a credential, so the message
     * reports the configured value: an operator correcting the configuration needs to know which entry
     * to correct.
     *
     * @param username the configured username, already known to be present and non-blank
     * @throws IllegalStateException when the name is wider than the audit column records
     */
    private static void requireRecordableUsername(String username) {
        if (username.length() > AuthorizationDecisionEntity.ACTOR_MAX_LENGTH) {
            throw new IllegalStateException("carddemo.security.users[].username '" + username
                    + "' holds " + username.length() + " characters, and every decision records the "
                    + "requesting identity in a column of "
                    + AuthorizationDecisionEntity.ACTOR_MAX_LENGTH
                    + ". Shorten it, so two identities cannot share one recorded actor.");
        }
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
                // Forgery-token protection needs a session to hold the token, and this chain
                // admits none. The exposure list of src/main/resources/application.yml names
                // health, metrics and the Prometheus scrape, and all three are reads, so this
                // chain answers no state-changing request for a token to protect.
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .build();
    }

    /**
     * Secures the authorization service API.
     *
     * <p>{@code POST /authorizations} admits {@code ROLE_ACQUIRER} and {@code ROLE_ADMIN}, and
     * refuses every other identity including {@code ROLE_USER}. The caller is a point-of-sale
     * terminal or an acquirer rather than a cardholder, and it presents a card in the request body,
     * so no path variable carries an identifier an ownership scope could be compared against. That
     * makes the role itself the whole authorization decision, and it is why a cardholder identity
     * is refused here: a route that reaches every card the platform holds must not be reachable by
     * an identity entitled to one card. The decision rules themselves refuse a card the
     * cross-reference does not carry, with reason 0100 from
     * {@code app/cbl/CBTRN02C.cbl:L385-L387}, but a refusal is still a durable decision and a
     * published event, so the rules are not a substitute for the role check.
     *
     * <p>{@code ROLE_ADMIN} keeps the route because {@code app/cbl/COSGN00C.cbl:L232-L236} forks an
     * administrator onto every function the region offers, and this platform expresses that fork as
     * an entitlement rather than a menu.
     *
     * <p>The role rule here is the first of two gates. This route names its subject in the request
     * body, and it may name an account instead of a card, so neither identifier is known until the
     * decision path has resolved it and no path variable exists for
     * {@link #ownsPathVariable(String, String)} to read. The ownership comparison therefore runs
     * where the resolution happens, in {@code domain/CallerEntitlement}, applied by
     * {@code domain/AuthorizationService} after the card and the account are resolved and before a
     * transaction identifier is allocated. A refused caller receives the same 403
     * {@link #forbidden()} writes, and no decision row, attempt row or event is produced for it.
     *
     * <p>Nothing else is reachable. This service exposes no query surface: a caller that wants a
     * balance reads the ledger service and a caller that wants a card reads the card service.
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
                        .requestMatchers(HttpMethod.POST, "/authorizations")
                            .hasAnyRole(ROLE_ACQUIRER, ROLE_ADMIN)
                        // Default deny. A route named by no rule above is refused.
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(SecurityConfig::unauthorized))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(SecurityConfig::unauthorized)
                        .accessDeniedHandler(forbidden()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Forgery-token protection needs a session to hold the token, and this chain
                // admits none. config/CrossSiteRequestFilter is the compensating control: it
                // refuses a state-changing request that declares a foreign site or origin, or that
                // carries no non-simple request header, so a cached credential a browser replays
                // from a foreign page reaches no handler.
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
     * Reads a resolved principal into the domain record the decision path compares ownership with.
     *
     * <p>{@code POST /authorizations} is the one route whose subject no rule above can read, because
     * the request body names a card or an account and the card of an account is resolved by a lookup.
     * Its ownership comparison therefore happens inside the decision, and the decision needs the
     * caller's authorities as plain text. Producing them belongs here rather than in the controller:
     * {@code Authentication} and {@code GrantedAuthority} are access-control types, and this file is
     * the one place per service that declares access control.
     *
     * <p>A principal that is not an {@code Authentication}, or one carrying no authority, yields a
     * caller entitled to nothing rather than a null. {@code domain/CallerEntitlement} refuses such a
     * caller, which is the safe reading: an absent entitlement is not an unrestricted one. The route
     * rule above requires a role, so no such call reaches the handler in the first place.
     *
     * @param caller the principal the container resolved, or {@code null} on a call carrying none
     * @return the caller the decision path reads, never {@code null}
     * @throws IllegalStateException when the principal name is wider than the audit column holds
     */
    public static RequestCaller callerOf(Principal caller) {
        if (caller instanceof Authentication authenticated) {
            return AuthenticatedActor.callerOf(authenticated, authenticated.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList());
        }
        return AuthenticatedActor.callerOf(caller, List.of());
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
     * <p>The text is {@link CallerNotEntitledException#DETAIL}, which is also what
     * {@code api/GlobalExceptionHandler} answers when the decision path refuses a caller the subject it
     * resolved. One owner of the text keeps the two refusals reading alike, so a caller cannot tell a
     * route denial from an ownership denial by reading the body.
     *
     * @return the handler the chains install
     */
    static AccessDeniedHandler forbidden() {
        return (request, response, denied) -> problem(response, HttpStatus.FORBIDDEN, "Forbidden",
                CallerNotEntitledException.DETAIL);
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
    // Five secrets reach this service, and all five are checked here: the password for its own
    // database login, the broker login entry that carries its Simple Authentication and Security
    // Layer password, the identity password hashes checked as each identity is mapped, the
    // card-token key every derivation is taken under, and the keystore password, checked only when
    // a port is actually encrypted.
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
     * A card-token key this repository published as the effective default of every deployment path.
     *
     * <p>It reached {@code .env.example}, the Compose stack and the Kubernetes Secret template
     * before those three carried placeholders. A card token is a keyed code over a sixteen-digit
     * card number, so anyone holding this key and one token can recompute the token of every
     * candidate card number offline, and the pseudonym stops being one. Start-up refuses the value
     * for that reason, whichever path it arrives through.
     */
    static final String PUBLISHED_CARD_TOKEN_KEY = "carddemo-demo-card-token-key-not-for-production";

    /**
     * The card-token key {@code card-platform/pom.xml} supplies to Surefire and Failsafe.
     *
     * <p>It exists so that a build can derive a token at all, and so that the fifty
     * {@code card_token} literals in the card service seed can be compared against the one
     * derivation this platform holds. It travels one way, as a JVM system property, and a
     * deployment supplies its key through {@link #CARD_TOKEN_KEY_VARIABLE} instead. Arriving
     * through that variable therefore means a deployment is running on a key this repository
     * publishes, and start-up refuses it.
     */
    static final String BUILD_SCOPE_CARD_TOKEN_KEY = "carddemo-build-scope-card-token-key-tests-only";

    /** System property carrying the card-token key, which is the path a build supplies it on. */
    static final String CARD_TOKEN_KEY_PROPERTY = PanMasker.CARD_TOKEN_SECRET_PROPERTY;

    /** Environment variable carrying the card-token key, which is the path a deployment uses. */
    static final String CARD_TOKEN_KEY_VARIABLE = PanMasker.CARD_TOKEN_SECRET_VARIABLE;

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
     * Checks the two credentials and the card-token key this service reads from configuration, and
     * the keystore password when a port is encrypted.
     *
     * <p>Identity password hashes are not checked here. Each one is checked as its identity is
     * mapped, which is where the property name is known.
     *
     * @param environment the resolved environment, which already carries substituted variables
     * @throws IllegalStateException when a checked secret is absent, unusable or published
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
        requireUsableCardTokenKey(environment);
        requireKeystoreMaterialWhenEncrypted(environment);
        // The card-token key is the fifth value this repository publishes, and it is the one a
        // derivation site cannot refuse: an absent or too-short key stops a derivation, while the
        // published key derives tokens that anyone holding this repository can recompute for a
        // candidate card number. PanMasker owns the check because it owns how the key is read, and
        // the demonstration paths state their intent in their own configuration.
        PanMasker.requireCardTokenSecretFitForUse();
    }

    /**
     * Refuses to start without a card-token key of this deployment's own.
     *
     * <p>This service derives card tokens, which is why the check lives here and not in the four
     * services that only read a token arriving in an event or a request. A token is a keyed code
     * over a sixteen-digit card number: with the key, one token is enough to recompute the token of
     * every candidate card number offline, so a published key turns the pseudonym back into the
     * card number it replaced.
     *
     * <p>Five conditions stop start-up. A key that is absent, blank or still a placeholder is
     * refused by {@link #requireUsableSecret}. A key shorter than
     * {@code PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH} is refused, because a short key is padded to
     * the hash block size rather than filling it. {@link #PUBLISHED_CARD_TOKEN_KEY} is refused
     * however it arrives. And {@link #BUILD_SCOPE_CARD_TOKEN_KEY} is refused when it arrives
     * through {@link #CARD_TOKEN_KEY_VARIABLE}, which is the deployment path; a build supplies the
     * same value as a system property, and that path is left open so this check does not stop every
     * test that starts a context.
     *
     * <p>No message carries the key. Each one names the variable and the condition that failed.
     *
     * @param environment the resolved environment, which already carries substituted variables
     * @throws IllegalStateException when no usable card-token key of this deployment's own is set
     */
    static void requireUsableCardTokenKey(Environment environment) {
        String fromBuild = System.getProperty(CARD_TOKEN_KEY_PROPERTY);
        String fromDeployment = environment.getProperty(CARD_TOKEN_KEY_VARIABLE);
        boolean suppliedByBuild = fromBuild != null && !fromBuild.isBlank();
        String configured = suppliedByBuild ? fromBuild.strip() : fromDeployment;

        requireUsableSecret(configured, CARD_TOKEN_KEY_VARIABLE);
        if (configured.length() < PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH) {
            throw new IllegalStateException(CARD_TOKEN_KEY_VARIABLE + " holds "
                    + configured.length() + " characters and at least "
                    + PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH + " are required. A shorter key is"
                    + " padded to the hash block size rather than filling it.");
        }
        if (PUBLISHED_CARD_TOKEN_KEY.equals(configured)) {
            throw new IllegalStateException(CARD_TOKEN_KEY_VARIABLE + " holds a card-token key this"
                    + " repository published as a default. Generate one for this deployment;"
                    + " card-platform/.env.example carries the command. Anyone holding that key and"
                    + " one card token can recompute the token of every card number.");
        }
        if (!suppliedByBuild && BUILD_SCOPE_CARD_TOKEN_KEY.equals(configured)) {
            throw new IllegalStateException(CARD_TOKEN_KEY_VARIABLE + " holds the build-scope"
                    + " card-token key card-platform/pom.xml supplies to this repository's own"
                    + " tests. It is published in this repository, so it is not a deployment key."
                    + " Generate one; card-platform/.env.example carries the command.");
        }
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
     * that is not the output of an adaptive one-way encoder.
     *
     * <p>No message carries the value. Each one names the property and states which condition
     * failed, which is everything an operator needs and nothing an attacker does.
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
        if (IDENTITY_PASSWORD_PROPERTY.equals(property)) {
            requireApprovedPasswordEncoding(value, property);
        }
    }

    /**
     * Refuses an identity password that declares no encoding, declares one this platform does not
     * accept, or declares bcrypt below {@link #BCRYPT_MINIMUM_COST}.
     *
     * <p>The check runs at start-up rather than at the first authentication. Left to run time, a
     * refused encoding shows up as a 401 for an identity an operator believes is configured, which
     * reads as a wrong password rather than as a wrong algorithm.
     *
     * <p>No message carries the value. Each one names the property, the identifier that was
     * declared and the identifiers that are accepted, which is what an operator needs.
     *
     * @param value    the configured password, which arrives already encoded
     * @param property the property name the message reports
     * @throws IllegalStateException when the encoding is absent, is not approved, or is bcrypt
     *                               below the cost floor
     */
    static void requireApprovedPasswordEncoding(String value, String property) {
        int close = value.startsWith("{") ? value.indexOf('}') : -1;
        if (close < 0) {
            throw new IllegalStateException(property + " carries no encoding prefix such as"
                    + " {bcrypt}. A value without one authenticates nobody and would let this"
                    + " service start looking misconfigured rather than refusing to start.");
        }
        String encoding = value.substring(1, close);
        if (!APPROVED_PASSWORD_ENCODINGS.contains(encoding)) {
            throw new IllegalStateException(property + " declares the encoding {" + encoding
                    + "}, which this platform does not accept. Re-encode the password under one of "
                    + APPROVED_PASSWORD_ENCODINGS + "; card-platform/.env.example carries the"
                    + " command. A plaintext or unsalted-digest password is refused here rather"
                    + " than verified successfully at run time.");
        }
        if (BCRYPT_ENCODING_ID.equals(encoding)) {
            requireBcryptCost(value.substring(close + 1), property);
        }
    }

    /**
     * Refuses a bcrypt hash that is malformed or carries a cost below the floor.
     *
     * @param hash     the hash following the {@code {bcrypt}} identifier
     * @param property the property name the message reports
     * @throws IllegalStateException when the value is not a bcrypt hash, or its cost is below
     *                               {@link #BCRYPT_MINIMUM_COST}
     */
    private static void requireBcryptCost(String hash, String property) {
        Matcher shape = BCRYPT_HASH.matcher(hash);
        if (!shape.matches()) {
            throw new IllegalStateException(property + " declares {bcrypt} and carries no bcrypt"
                    + " hash behind it. A bcrypt hash reads $2a$, $2b$ or $2y$, then two cost"
                    + " digits, then fifty-three characters of salt and hash.");
        }
        int cost = Integer.parseInt(shape.group(1));
        if (cost < BCRYPT_MINIMUM_COST) {
            throw new IllegalStateException(property + " declares a bcrypt cost of " + cost
                    + " and at least " + BCRYPT_MINIMUM_COST + " is required. Cost is logarithmic,"
                    + " so every step below the floor halves what an offline guess costs.");
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

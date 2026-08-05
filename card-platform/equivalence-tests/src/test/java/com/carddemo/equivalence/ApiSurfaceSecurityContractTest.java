package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.api.ApiProblem;
import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountReadResponse;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.AccountUpdateResponse;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.api.dto.CustomerReadResponse;
import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.api.dto.CycleCloseResponse;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.api.AuthorizationResponse;
import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardDetailRequest;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardSummary;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.cobol.PanMasker;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.SensitiveEventProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

/**
 * Pins every security property the shipped Application Programming Interface surface expresses, and
 * states the boundary of what that surface can express at all.
 *
 * <p>Twenty-seven source files across three service modules make up that surface. Eighteen declare a
 * record, and two of those declare the same name in two modules, so seventeen record names make up
 * the classified inventory. The other nine are the six endpoints those modules answer a request on,
 * the two handlers that answer a rejected request, and the message inventory
 * {@link CardValidationMessages}. Twelve record types leave a service as a response body, six enter
 * one as a request body, and this class holds every one of them in a declared inventory so a new type
 * cannot join the surface unclassified.</p>
 *
 * <h2>What is asserted</h2>
 *
 * <p><strong>Response redaction.</strong> Every one of the sixty-four response components is
 * classified, and no classified component may carry a value the platform refuses to emit. The rule
 * is the one {@link SensitiveEventProperties} applies at the event boundary, widened by the names a
 * customer record adds, and it is aware of type as well as name:
 * {@link CardSummary#cardNumber()} is named {@code cardNumber} and carries the masked form only,
 * which is twelve mask characters followed by the last four digits. Its declared classification says
 * so and every built instance is measured against
 * {@link CardUpdated#MASKED_CARD_NUMBER_PATTERN}. A name alone would condemn it and a name alone
 * would also miss a component named innocently.</p>
 *
 * <p><strong>The direction of travel.</strong> {@link AuthorizationRequest} declares an unmasked
 * {@code cardNumber} because the decision runs on the full Primary Account Number, exactly as
 * {@code app/cbl/CBTRN02C.cbl:L383-L387} keys the cross-reference read on it. Section 0.6.4 of the
 * plan states that rule and states that only the published payload is masked. No response type
 * declares an unmasked card number, and {@link CustomerDataRequest} accepts the three social
 * security slices and the government-issued identifier that no response returns.</p>
 *
 * <p><strong>A redaction the source does not perform.</strong> {@code app/cbl/COACTVWC.cbl:L497-L501}
 * moves {@code CUST-SSN} to the account view screen in three slices, and line 519 moves
 * {@code CUST-GOVT-ISSUED-ID} to it. {@link CustomerView} returns neither, while
 * {@link CustomerEntity} stores both. That omission is a deliberate deviation, so it is asserted
 * rather than left to chance.</p>
 *
 * <p><strong>Error-body exposure.</strong> {@link ApiErrorResponse} carries a status, one message
 * and one route template. It declares no throwable, no stack trace and no echo of a request, and its
 * own constructor refuses a route that holds a run of more than four digits, which is the shape of a
 * resolved card number or account identifier. {@link ApiProblem} is the account service's error body
 * and carries the four members RFC 9457 defines plus one list of field texts. It declares no route at
 * all, so there is no path for it to echo, and its own representation withholds the field texts while
 * naming how many there are.</p>
 *
 * <p><strong>Serialized shape.</strong> Every response is built and serialized through Jackson, and
 * the emitted key set is compared against the declared components. A getter added beside the
 * components of a record is serialized as a further property, so it would widen the body without
 * widening the component list, and that is the drift this catches. The masked component is compared
 * against {@link PanMasker#maskCardNumber(String)}, so the response boundary is tied to the
 * production masker rather than to a literal.</p>
 *
 * <p><strong>Transport configuration.</strong> Each of the six services exposes exactly the health,
 * metrics and Prometheus actuator endpoints and no diagnostic endpoint, and each resolves its
 * database password from a placeholder whose innermost form carries no default, so no credential is
 * baked into an artifact.</p>
 *
 * <h2>Access control on the web surface</h2>
 *
 * <p><strong>The chains.</strong> Each of the six services declares two filter chains: a
 * management chain matching the actuator endpoints, and an ordinary chain matching every other
 * request. Every main source of every module is scanned, and the six {@code SecurityConfig} files
 * are the only ones that name a security type, so neither shared library depends on the
 * framework.</p>
 *
 * <p><strong>The principal each route requires.</strong> Every route rule of every ordinary chain
 * is read out of the source and compared against a declared inventory, so a route that changes the
 * authority it demands fails here rather than changing quietly. Each ordinary chain ends in
 * {@code denyAll()}, which is what makes a route no rule named refused rather than merely
 * authenticated, and each management chain admits the liveness probe and requires
 * {@code ROLE_MONITORING} for everything else.</p>
 *
 * <p><strong>The two refusals.</strong> The entry point and the access-denied handler are invoked
 * against a recording response, so the 401 an unauthenticated caller receives and the 403 an
 * authenticated caller without the authority receives are measured rather than described. Both are
 * problem documents, both carry {@code Cache-Control: no-store}, and neither names an account, a
 * customer, a card or the route that was refused. A detailed refusal would put an identifier the
 * caller does not own into that caller's own log.</p>
 *
 * <p>Section 0.2.2 of the plan still excludes production hardening, multi-factor authentication and
 * payment-card industry controls beyond the one documented masking deviation, and section 0.3.4
 * still records that no user interface is in scope. {@code WebSurfaceAccessControl} therefore keeps
 * measuring the two things that have not landed: no module declares a cross-origin policy, because
 * a browser is not a client of this platform, and no module carries a method-level rule, because
 * every authority this platform requires is expressed in a chain where the whole route table can
 * be read at once.</p>
 *
 * <p>No failure message here carries a card number, a verification value, a social security number
 * or a government-issued identifier. A divergence is reported by type name, component name and
 * declared type only.</p>
 */
class ApiSurfaceSecurityContractTest {

    /**
     * Source files making up the Application Programming Interface surface of the three modules
     * this class classifies: the account, authorization and card services. Eighteen of the
     * twenty-seven declare a record; the other nine are the six endpoints those modules answer a
     * request on, the two handlers that answer a rejected request, and the card message inventory.
     */
    private static final int API_SOURCE_FILE_COUNT = 30;

    /** Components the twelve response types declare between them. */
    private static final int RESPONSE_COMPONENT_COUNT = 68;

    /**
     * Fewest main sources the six services and the two libraries carry between them, all of which
     * are scanned. A scan returning fewer has missed a module or a source root.
     */
    private static final int MAIN_SOURCE_FILE_FLOOR = 153;

    /** Directory below the repository root holding the six service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** Directory below the repository root holding the two shared libraries. */
    private static final String LIBRARIES_DIRECTORY = "card-platform/libs";

    /** Path below a module to its main sources. */
    private static final String MAIN_SOURCE_PATH = "src/main/java";

    /** Path below a service module to its configuration. */
    private static final String APPLICATION_YAML = "src/main/resources/application.yml";

    /** Suffix a Java source file carries. */
    private static final String JAVA_SUFFIX = ".java";

    /** The six service modules, in the dependency order section 0.4.4 of the plan states. */
    private static final List<String> ALL_MODULES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    /** The two shared libraries every service depends on. */
    private static final List<String> ALL_LIBRARIES = List.of("event-contracts", "cobol-compat");

    /**
     * A sixteen-digit card number in the shape {@code CARD-NUM PIC X(16)} declares at
     * {@code app/cpy/CVACT02Y.cpy:L6}. It is a shape and not a fixture row, so nothing real is
     * masked here.
     */
    private static final String SHAPED_CARD_NUMBER = "1234567890123456";

    /** An eleven-digit account identifier, the width {@code ACCT-ID PIC 9(11)} declares. */
    private static final String SHAPED_ACCOUNT_ID = "00000000011";

    /**
     * The card token of {@link #SHAPED_CARD_NUMBER}, derived by the production helper.
     *
     * <p>The paging cursor of the card list carries this form and never a card number. A card
     * number leaves the service only masked, and a masked value names every card sharing four
     * digits, so it identifies no single browse position. AAP section 0.6.4 fixes the rule.
     */
    private static final String SHAPED_CARD_TOKEN = PanMasker.cardToken(SHAPED_CARD_NUMBER);

    /** How a response component may carry its value. */
    private enum Disclosure {

        /** Carries no value the source or the platform treats as sensitive. */
        SAFE,

        /**
         * Carries a card number in its masked form: twelve mask characters then the last four
         * digits. The controller masks with {@link com.carddemo.cobol.PanMasker#maskCardNumber}
         * before it builds the body, and every instance built here is measured against
         * {@link CardUpdated#MASKED_CARD_NUMBER_PATTERN}.
         */
        MASKED,

        /**
         * A value the source screen discloses, carried forward for equivalence. The classification
         * names the line that discloses it.
         */
        SOURCE_DISCLOSED
    }

    /** The twelve types a service returns as a response body. */
    private static final List<Class<?>> RESPONSE_TYPES = List.of(
            AccountView.class,
            CustomerView.class,
            AccountReadResponse.class,
            CustomerReadResponse.class,
            AccountUpdateResponse.class,
            CycleCloseResponse.class,
            ApiProblem.class,
            AuthorizationResponse.class,
            ApiErrorResponse.class,
            CardDetailResponse.class,
            CardListResponse.class,
            CardSummary.class,
            CardUpdateResponse.class,
            CardUpdateResponse.RefreshedCard.class);

    /** The six types a service accepts as a request body. */
    private static final List<Class<?>> REQUEST_TYPES = List.of(
            AccountDataRequest.class,
            AccountUpdateRequest.class,
            CustomerDataRequest.class,
            AuthorizationRequest.class,
            CardDetailRequest.class,
            CardUpdateRequest.class);

    /**
     * Every response component, keyed by declaring type and component name.
     *
     * <p>A component missing from this map, or a mapped component the type no longer declares, fails
     * {@code everyResponseComponentIsClassified}. Classification is therefore a step a widening of
     * the surface cannot skip.</p>
     */
    private static final Map<String, Disclosure> RESPONSE_COMPONENTS = responseComponents();

    /** Builds the classification of all sixty-eight response components. */
    private static Map<String, Disclosure> responseComponents() {
        Map<String, Disclosure> components = new LinkedHashMap<>();

        // AccountView. Field set from app/cbl/COACTVWC.cbl, record layout app/cpy/CVACT01Y.cpy.
        for (String component : List.of("accountId", "activeStatus", "currentBalance", "creditLimit",
                "cashCreditLimit", "currentCycleCredit", "currentCycleDebit", "openDate",
                "expirationDate", "reissueDate", "groupId")) {
            components.put("AccountView." + component, Disclosure.SAFE);
        }

        // CustomerView. Record layout app/cpy/CVCUS01Y.cpy, screen moves app/cbl/COACTVWC.cbl.
        for (String component : List.of("customerId", "ficoCreditScore", "firstName", "middleName",
                "lastName", "addressLine1", "addressLine2", "addressCity", "addressStateCode",
                "addressZip", "addressCountryCode", "phoneNumber1", "phoneNumber2",
                "primaryCardHolderIndicator")) {
            components.put("CustomerView." + component, Disclosure.SAFE);
        }
        // CUST-DOB-YYYY-MM-DD reaches the screen at app/cbl/COACTVWC.cbl:L507.
        components.put("CustomerView.dateOfBirth", Disclosure.SOURCE_DISCLOSED);
        // CUST-EFT-ACCOUNT-ID reaches the screen at app/cbl/COACTVWC.cbl:L520.
        components.put("CustomerView.eftAccountId", Disclosure.SOURCE_DISCLOSED);

        // AccountReadResponse. The message slot is WS-RETURN-MSG at app/cbl/COACTVWC.cbl:L117,
        // which carries a not-found line and never a submitted value. The account slot nests
        // AccountView, whose eleven components are classified above.
        for (String component : List.of("message", "account")) {
            components.put("AccountReadResponse." + component, Disclosure.SAFE);
        }

        // CustomerReadResponse. Same message slot, nesting CustomerView.
        for (String component : List.of("message", "customer")) {
            components.put("CustomerReadResponse." + component, Disclosure.SAFE);
        }

        // AccountUpdateResponse. The message slot is WS-RETURN-MSG at app/cbl/COACTUPC.cbl:L479,
        // which carries a field label and never a submitted value. The account slot nests
        // AccountView, whose eleven components are classified above.
        for (String component : List.of("message", "account")) {
            components.put("AccountUpdateResponse." + component, Disclosure.SAFE);
        }

        // CycleCloseResponse. Counters app/cbl/CBACT04C.cbl:L353-L354 zeroes.
        for (String component : List.of("accountId", "currentCycleCredit", "currentCycleDebit")) {
            components.put("CycleCloseResponse." + component, Disclosure.SAFE);
        }

        // ApiProblem. The account service's error body, whose four standard members are the ones RFC
        // 9457 defines and are spelled as account-service config/SecurityConfig spells them when it
        // answers 401 and 403. The messages member carries the field texts the validation pass at
        // app/cbl/COACTUPC.cbl:L1470-L1676 emits, each a field label joined to a fixed literal, so no
        // submitted value travels in it.
        for (String component : List.of("type", "title", "status", "detail", "messages")) {
            components.put("ApiProblem." + component, Disclosure.SAFE);
        }

        // AuthorizationResponse. Reason codes app/cbl/CBTRN02C.cbl:L385-L420.
        for (String component : List.of("transactionId", "accountId", "approved",
                "declineReasonCode", "declineReasonDescription")) {
            components.put("AuthorizationResponse." + component, Disclosure.SAFE);
        }

        // ApiErrorResponse. Message field WS-RETURN-MSG at app/cbl/COCRDUPC.cbl:L173.
        for (String component : List.of("status", "message", "route")) {
            components.put("ApiErrorResponse." + component, Disclosure.SAFE);
        }

        // CardDetailResponse. Record layout app/cpy/CVACT02Y.cpy.
        components.put("CardDetailResponse.maskedCardNumber", Disclosure.MASKED);
        for (String component : List.of("accountId", "embossedName", "expirationDate",
                "activeStatus")) {
            components.put("CardDetailResponse." + component, Disclosure.SAFE);
        }

        // CardListResponse. Page size and lookahead app/cbl/COCRDLIC.cbl:L1285. The cursor is
        // SAFE because it carries a card token: opaque, not reversible, and naming exactly one
        // browse position. A cursor carrying a card number would be neither SAFE nor MASKED.
        for (String component : List.of("cards", "nextPageExists", "nextCursor")) {
            components.put("CardListResponse." + component, Disclosure.SAFE);
        }

        // CardSummary. Named for the source field and carrying the masked form.
        components.put("CardSummary.cardNumber", Disclosure.MASKED);
        components.put("CardSummary.accountId", Disclosure.SAFE);
        components.put("CardSummary.activeStatus", Disclosure.SAFE);

        // CardUpdateResponse and its refreshed snapshot, app/cbl/COCRDUPC.cbl:L1513-L1517.
        for (String component : List.of("outcome", "message", "refreshedCard")) {
            components.put("CardUpdateResponse." + component, Disclosure.SAFE);
        }
        for (String component : List.of("embossedName", "expiryYear", "expiryMonth", "expiryDay",
                "activeStatus")) {
            components.put("RefreshedCard." + component, Disclosure.SAFE);
        }

        return Map.copyOf(components);
    }

    /**
     * Name fragments a response refuses beyond the ones {@link SensitiveEventProperties} refuses.
     *
     * <p>The event denylist covers the verification value, the password, the social security number
     * and the unmasked card number. A customer record adds one more value that no response returns,
     * and the aliases below catch a spelling the list did not anticipate. Every fragment is compared
     * in lower case, and each is long enough that no component name of this surface holds it by
     * accident.</p>
     */
    private static final List<String> RESPONSE_ONLY_FORBIDDEN_FRAGMENTS = List.of(
            "governmentissued",
            "governmentid",
            "govtissued",
            "driverslicense",
            "taxidentification",
            "passportnumber");

    /** Annotations and types a web endpoint requires, none of which this surface declares. */
    private static final List<String> WEB_ENDPOINT_TOKENS = List.of(
            "@RestController",
            "@Controller",
            "@ControllerAdvice",
            "@RestControllerAdvice",
            "@RequestMapping",
            "@GetMapping",
            "@PostMapping",
            "@PutMapping",
            "@DeleteMapping",
            "@PatchMapping",
            "@ResponseBody",
            "@ResponseStatus");

    /**
     * Files declaring a web-endpoint annotation. Eight carry a controller and four carry the handler
     * that answers a rejected request. Keys take the form {@code module/FileName.java}, which is how
     * {@link #MAIN_SOURCES} keys a source.
     *
     * <p>The account service carries three controllers, because it answers three resources: the
     * account view and update of {@code app/cbl/COACTVWC.cbl} and {@code app/cbl/COACTUPC.cbl}, the
     * customer read those two resolve through the cross-reference, and the cycle close that
     * reproduces {@code app/cbl/CBACT04C.cbl:L353-L354}. One handler serves all three.</p>
     *
     * <p>The card service carries one controller for three routes, because
     * {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl} and {@code app/cbl/COCRDUPC.cbl}
     * all address one collection of cards. One handler sits beside it.</p>
     *
     * <p>The ledger controller and the fraud controller carry no handler beside them. Each
     * constrains the identifier its route accepts, and the framework answers a miss with its own
     * problem document.</p>
     */
    private static final Set<String> ENDPOINT_SOURCE_FILES = Set.of(
            "account-service/AccountController.java",
            "account-service/CustomerController.java",
            "account-service/BillingCycleController.java",
            "authorization-service/AuthorizationController.java",
            "authorization-service/GlobalExceptionHandler.java",
            "account-service/AccountApiExceptionHandler.java",
            "card-service/CardController.java",
            "card-service/CardApiExceptionHandler.java",
            "ledger-posting-service/BalanceQueryController.java",
            "notification-service/NotificationHistoryController.java",
            "notification-service/NotificationApiExceptionHandler.java",
            "fraud-detection-service/FraudAssessmentController.java");

    /**
     * Annotations and types every service declares to configure a filter chain. Each of the six
     * {@code SecurityConfig} files carries all six, and no other main source carries any.
     */
    private static final List<String> DECLARED_SECURITY_TOKENS = List.of(
            "SecurityFilterChain",
            "@EnableWebSecurity",
            "@EnableMethodSecurity",
            "UserDetailsService",
            "HttpSecurity",
            "org.springframework.security");

    /**
     * Annotations and types no main source declares.
     *
     * <p>The first four are method-level rules. Every authority this platform requires is expressed
     * in a chain instead, where the whole route table is readable in one place and a route nobody
     * named is refused by the {@code denyAll()} that ends it; an annotation on a method cannot
     * refuse a route that has no method yet. {@code WebSecurityCustomizer} is the mechanism that
     * takes a path out of the chain altogether, and an exposed {@code AuthenticationManager} is the
     * seam through which a second authentication path enters. Neither exists, so the chain each
     * service declares is the whole of its access control.</p>
     */
    private static final List<String> ABSENT_SECURITY_TOKENS = List.of(
            "@PreAuthorize",
            "@PostAuthorize",
            "@Secured",
            "@RolesAllowed",
            "WebSecurityCustomizer",
            "AuthenticationManager");

    /** Annotations and types a cross-origin policy requires. */
    private static final List<String> CROSS_ORIGIN_TOKENS = List.of(
            "@CrossOrigin",
            "CorsConfiguration",
            "CorsRegistry",
            "addCorsMappings",
            "allowedOrigins");

    /** Dependency coordinates a security starter would appear under. */
    private static final List<String> SECURITY_ARTIFACT_TOKENS = List.of(
            "spring-boot-starter-security",
            "spring-security",
            "spring-boot-starter-oauth2-client",
            "spring-boot-starter-oauth2-resource-server",
            "nimbus-jose-jwt");

    /** A type the classpath would carry if any module depended on a security starter. */
    private static final String SECURITY_FILTER_CHAIN_TYPE =
            "org.springframework.security.web.SecurityFilterChain";

    /** Actuator endpoints every service exposes, and the only ones any service exposes. */
    private static final Set<String> EXPOSED_ACTUATOR_ENDPOINTS =
            Set.of("health", "metrics", "prometheus");

    /**
     * Actuator endpoints no service exposes.
     *
     * <p>Each one returns something a demo does not need and an attacker does: {@code env} and
     * {@code configprops} return the resolved database password, {@code heapdump} returns the heap
     * that holds every card number read so far, and {@code loggers} lets a caller raise the level
     * that section 0.6.4 of the plan keeps at INFO so identifiers stay out of centralized logs.</p>
     */
    private static final List<String> DIAGNOSTIC_ACTUATOR_ENDPOINTS = List.of(
            "env",
            "configprops",
            "heapdump",
            "threaddump",
            "beans",
            "loggers",
            "shutdown",
            "auditevents",
            "httpexchanges",
            "mappings",
            "scheduledtasks",
            "caches",
            "sessions",
            "startup",
            "quartz");

    /** Configuration keys whose value must resolve from the environment and not from an artifact. */
    private static final List<String> CREDENTIAL_KEYS = List.of("password", "username");

    /** File every service declares its two filter chains in. */
    private static final String SECURITY_CONFIG_FILE = "SecurityConfig.java";

    /**
     * The configuration class of each service, by binary name.
     *
     * <p>Named rather than discovered, because the two refusals below are invoked on the real class
     * and a class this map failed to name would go unmeasured rather than reported.</p>
     */
    private static final Map<String, String> SECURITY_CONFIG_TYPES = Map.of(
            "authorization-service", "com.carddemo.authorization.config.SecurityConfig",
            "ledger-posting-service", "com.carddemo.ledger.config.SecurityConfig",
            "fraud-detection-service", "com.carddemo.fraud.config.SecurityConfig",
            "notification-service", "com.carddemo.notification.config.SecurityConfig",
            "account-service", "com.carddemo.account.config.SecurityConfig",
            "card-service", "com.carddemo.card.config.SecurityConfig");

    /**
     * The authority every route of every ordinary chain requires, in the order the chain names them.
     *
     * <p>Order carries meaning. A chain applies the first rule whose matcher accepts a request, so
     * the two rules that name a method and a path for {@code /accounts/{accountId}} have to precede
     * the ownership rule, and the ownership rule for {@code GET /cards} has to follow the two rules
     * that name a narrower path below it.</p>
     *
     * <p>Three shapes appear. {@code hasRole} and {@code hasAnyRole} require an authority and
     * nothing more, so they guard the operations only an administrator performs and the two an
     * ordinary identity may perform on any subject. {@code access(ownsPathVariable(...))} and
     * {@code access(ownsRequestParameter(...))} require the caller to hold a scope naming the very
     * identifier the request carries, which is the difference between an authenticated caller and an
     * entitled one: the source screens of {@code app/cbl/COACTVWC.cbl} and
     * {@code app/cbl/COCRDLIC.cbl} accept any signed-on identity and any account number it types,
     * and reproducing that would let one cardholder read another's rows.</p>
     */
    private static final Map<String, List<String>> API_ROUTE_RULES = Map.of(
            "authorization-service", List.of(
                    "POST /authorizations -> hasAnyRole(USER, ADMIN)"),
            "ledger-posting-service", List.of(
                    "GET /balances/{accountId} -> access(ownsPathVariable(ACCOUNT, accountId))"),
            "fraud-detection-service", List.of(
                    "GET /fraud-assessments/** -> hasRole(ADMIN)"),
            "notification-service", List.of(
                    "GET /notifications/{cardToken} -> "
                            + "access(ownsPathVariable(CARD, cardToken))"),
            "account-service", List.of(
                    "POST /accounts/{accountId}/cycle-close -> hasRole(ADMIN)",
                    "PUT /accounts/{accountId} -> hasRole(ADMIN)",
                    "GET /accounts/{accountId} -> access(ownsPathVariable(ACCOUNT, accountId))",
                    "GET /customers/{customerId} -> access(ownsPathVariable(CUSTOMER, customerId))"),
            "card-service", List.of(
                    "POST /cards/detail -> hasAnyRole(USER, ADMIN)",
                    "PUT /cards -> hasRole(ADMIN)",
                    "GET /cards -> access(ownsRequestParameter(ACCOUNT, accountId))"));

    /** Constant names the route inventory above reads as the value each one holds. */
    private static final Map<String, String> RULE_CONSTANT_NAMES = Map.of(
            "ROLE_ADMIN", "ADMIN",
            "ROLE_USER", "USER",
            "ROLE_MONITORING", "MONITORING",
            "ACCOUNT_SCOPE", "ACCOUNT",
            "CUSTOMER_SCOPE", "CUSTOMER",
            "CARD_SCOPE", "CARD");

    /** Rule that ends every ordinary chain, so a route no rule above named is refused. */
    private static final String DEFAULT_DENY_RULE = ".anyRequest().denyAll()";

    /** Rule that ends every management chain. */
    private static final String MANAGEMENT_DEFAULT_RULE = ".anyRequest().hasRole(ROLE_MONITORING)";

    /** The one actuator endpoint a management chain admits without a credential. */
    private static final String PERMITTED_PROBE = ".requestMatchers(EndpointRequest.to(\"health\"))";

    /** Broker transport every service configures, and the mechanism it authenticates with. */
    private static final String BROKER_SECURITY_PROTOCOL = "SASL_PLAINTEXT";

    /** Simple Authentication and Security Layer mechanism every service configures. */
    private static final String BROKER_SASL_MECHANISM = "PLAIN";

    /** Key carrying the broker login entry, whose password sits inside the entry rather than beside it. */
    private static final String BROKER_LOGIN_KEY = "sasl.jaas.config";

    /** Media type both refusals carry, as RFC 9457 defines it. */
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    /** Members a problem document carries, in the order the handler writes them. */
    private static final List<String> PROBLEM_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** Route a refused request asked for, used only to prove no refusal repeats it. */
    private static final String REFUSED_ROUTE = "/accounts/" + SHAPED_ACCOUNT_ID;

    /**
     * Wraps a supplier so its value is read once, on first use.
     *
     * <p>The tables below read files, and reading on first use keeps a read failure inside the
     * test that asks for the value rather than in class initialisation.
     *
     * @param <T>    type the supplier yields
     * @param source supplier to read once
     * @return a supplier that reads {@code source} at most once
     */
    private static <T> Supplier<T> readOnce(Supplier<T> source) {
        return new Supplier<>() {

            private T value;

            private boolean read;

            @Override
            public synchronized T get() {
                if (!read) {
                    value = source.get();
                    read = true;
                }
                return value;
            }
        };
    }

    /** Repository root, being the third ancestor of the fixture directory. */
    private static final Supplier<Path> REPOSITORY_ROOT = readOnce(() ->
            CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent());

    /** Every main source of the six services and the two libraries, keyed by module and file name. */
    private static final Supplier<Map<String, String>> MAIN_SOURCES =
            readOnce(ApiSurfaceSecurityContractTest::readMainSources);

    /** The configuration of every service, keyed by module name. */
    private static final Supplier<Map<String, String>> APPLICATION_YAMLS =
            readOnce(ApiSurfaceSecurityContractTest::readApplicationYamls);

    /** One populated instance of every response type, keyed by simple name. */
    private static final Supplier<Map<String, Object>> RESPONSE_INSTANCES =
            readOnce(ApiSurfaceSecurityContractTest::buildResponseInstances);

    /** Reads every main source of every module of the platform. */
    private static Map<String, String> readMainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String module : ALL_MODULES) {
            readSourcesBelow(REPOSITORY_ROOT.get()
                    .resolve(SERVICES_DIRECTORY)
                    .resolve(module)
                    .resolve(MAIN_SOURCE_PATH), module, sources);
        }
        for (String library : ALL_LIBRARIES) {
            readSourcesBelow(REPOSITORY_ROOT.get()
                    .resolve(LIBRARIES_DIRECTORY)
                    .resolve(library)
                    .resolve(MAIN_SOURCE_PATH), library, sources);
        }
        return Map.copyOf(sources);
    }

    /** Reads every Java source below one directory into {@code sources}, keyed by module and name. */
    private static void readSourcesBelow(Path directory, String module, Map<String, String> sources) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("no main sources at " + directory);
        }
        try (Stream<Path> tree = Files.walk(directory)) {
            tree.filter(path -> path.getFileName().toString().endsWith(JAVA_SUFFIX))
                    .sorted()
                    .forEach(path -> sources.put(module + "/" + path.getFileName(), readText(path)));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + directory, unreadable);
        }
    }

    /** Reads the configuration of every service. */
    private static Map<String, String> readApplicationYamls() {
        Map<String, String> configurations = new LinkedHashMap<>();
        for (String module : ALL_MODULES) {
            configurations.put(module, readText(REPOSITORY_ROOT.get()
                    .resolve(SERVICES_DIRECTORY)
                    .resolve(module)
                    .resolve(APPLICATION_YAML)));
        }
        return Map.copyOf(configurations);
    }

    /** Reads a file as text, turning the checked failure into an unchecked one. */
    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /**
     * Builds one populated instance of every response type.
     *
     * <p>Each value satisfies the constructor of the type that receives it. Five types validate
     * what they are given: an account identifier of exactly eleven digits, a declined authorization
     * carrying both a reason and its text, and the one update outcome that carries a refreshed
     * snapshot. The decline
     * names {@link DeclineReason#OVER_CREDIT_LIMIT} because a response carrying an account
     * identifier cannot also carry {@link DeclineReason#INVALID_CARD_NUMBER}: reason
     * {@code 0100} is the one outcome where the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L383-L387} resolved no account.</p>
     *
     * @return one instance per response type, keyed by simple name
     */
    private static Map<String, Object> buildResponseInstances() {
        String masked = PanMasker.maskCardNumber(SHAPED_CARD_NUMBER);
        CardSummary summary = new CardSummary(masked, SHAPED_ACCOUNT_ID, "Y");
        CardUpdateResponse.RefreshedCard refreshed =
                new CardUpdateResponse.RefreshedCard("EMBOSSED NAME", "2026", "08", "02", "Y");

        Map<String, Object> instances = new LinkedHashMap<>();
        AccountView accountView = new AccountView(SHAPED_ACCOUNT_ID, "Y",
                new BigDecimal("843.00"), new BigDecimal("5000.00"), new BigDecimal("500.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"),
                "2020-01-01", "2026-12-31", "2024-01-01", "ZEROAPR");
        instances.put("AccountView", accountView);
        CustomerView customerView = new CustomerView("000000011", 750, "1975-04-12",
                "FIRST", "M", "LAST", "ADDRESS LINE ONE", "ADDRESS LINE TWO", "CITY", "NY",
                "10001", "USA", "2125551234", "2125555678", "0000000001", "Y");
        instances.put("CustomerView", customerView);
        // Both read responses carry the not-found slot empty, which is what a found row returns.
        instances.put("AccountReadResponse", new AccountReadResponse("", accountView));
        instances.put("CustomerReadResponse", new CustomerReadResponse("", customerView));
        // The message is the one app/cbl/COACTUPC.cbl:L2206-L2211 composes: a trimmed field label
        // joined to the literal at app/cbl/COACTUPC.cbl:L2209, which carries no trailing period.
        instances.put("AccountUpdateResponse",
                new AccountUpdateResponse("Current Balance is not valid", accountView));
        instances.put("CycleCloseResponse", new CycleCloseResponse(SHAPED_ACCOUNT_ID,
                new BigDecimal("0.00"), new BigDecimal("0.00")));
        // The messages member is populated rather than omitted, because the serialized-shape
        // assertion compares the emitted keys against the declared components and the record omits a
        // null member from the wire form. The one text is the message app/cbl/COACTUPC.cbl:L2206-L2211
        // composes for a rejected balance.
        instances.put("ApiProblem", ApiProblem.of(422, ApiProblem.VALIDATION_FAILED,
                ApiProblem.VALIDATION_FAILED_DETAIL,
                List.of("Current Balance is not valid")));
        instances.put("AuthorizationResponse", new AuthorizationResponse("0000000000000001",
                SHAPED_ACCOUNT_ID, false, DeclineReason.OVER_CREDIT_LIMIT,
                DeclineReason.OVER_CREDIT_LIMIT.description()));
        instances.put("ApiErrorResponse",
                new ApiErrorResponse(404, "DID NOT FIND THIS CARD", "/cards/{cardNumber}"));
        instances.put("CardDetailResponse", new CardDetailResponse(masked, SHAPED_ACCOUNT_ID,
                "EMBOSSED NAME", LocalDate.of(2026, 12, 31), "Y"));
        instances.put("CardListResponse",
                new CardListResponse(List.of(summary), true, SHAPED_CARD_TOKEN));
        instances.put("CardSummary", summary);
        instances.put("CardUpdateResponse", new CardUpdateResponse(
                CardUpdateResponse.UpdateOutcome.CHANGED_BEFORE_UPDATE,
                CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE, refreshed));
        instances.put("RefreshedCard", refreshed);
        return Map.copyOf(instances);
    }

    /** The mapper a service serializes a response body with. */
    private static ObjectMapper mapper() {
        return JsonMapper.builder().build();
    }

    /** The key of one component in {@link #RESPONSE_COMPONENTS}. */
    private static String componentKey(Class<?> type, RecordComponent component) {
        return type.getSimpleName() + "." + component.getName();
    }

    /**
     * Whether a response may not declare a component of this name.
     *
     * <p>The event rule comes first, so one denylist governs both boundaries. The fragments this
     * class adds cover the values a customer record holds and no response returns.</p>
     *
     * @param componentName name of the component to judge
     * @return {@code true} when no response may declare the name
     */
    private static boolean isForbiddenOnAResponse(String componentName) {
        if (SensitiveEventProperties.isForbidden(componentName)) {
            return true;
        }
        String folded = componentName.toLowerCase(Locale.ROOT);
        for (String fragment : RESPONSE_ONLY_FORBIDDEN_FRAGMENTS) {
            if (folded.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Every occurrence of any token in any main source, reported as module, file and token. */
    private static List<String> occurrencesOf(List<String> tokens) {
        List<String> occurrences = new ArrayList<>();
        for (Map.Entry<String, String> source : MAIN_SOURCES.get().entrySet()) {
            for (String token : tokens) {
                if (source.getValue().contains(token)) {
                    occurrences.add(source.getKey() + " declares " + token);
                }
            }
        }
        return occurrences;
    }

    /**
     * The value of one configuration key, as the file spells it.
     *
     * @param configuration configuration text to read
     * @param key           key to find, matched at the start of a line after its indentation
     * @return every value the key carries, in file order
     */
    private static List<String> valuesOf(String configuration, String key) {
        List<String> values = new ArrayList<>();
        for (String line : configuration.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                values.add(trimmed.substring(key.length() + 1).trim());
            }
        }
        return values;
    }

    /**
     * The actuator web-exposure include value, excluding health-group membership lists that use the
     * same YAML leaf key.
     *
     * @param configuration configuration text to read
     * @return the one value bound from the shared management-endpoint environment setting
     */
    private static List<String> actuatorExposureIncludes(String configuration) {
        return valuesOf(configuration, "include").stream()
                .filter(value -> value.contains("MANAGEMENT_ENDPOINTS"))
                .toList();
    }

    /**
     * The literal a placeholder chain falls back to.
     *
     * <p>A configuration value is a chain such as {@code ${OUTER:${INNER:literal}}}. The literal is
     * what an environment supplying nothing leaves behind, so it is what an artifact actually
     * carries. It sits after the last colon, and the closing braces and any surrounding quotes are
     * dropped.</p>
     *
     * @param rawValue value as the file spells it
     * @return the literal fallback, or an empty string when the chain has none
     */
    private static String literalFallbackOf(String rawValue) {
        String value = rawValue.replace("\"", "").replace("'", "").trim();
        int lastColon = value.lastIndexOf(':');
        if (lastColon < 0) {
            return value;
        }
        String tail = value.substring(lastColon + 1);
        while (tail.endsWith("}")) {
            tail = tail.substring(0, tail.length() - 1);
        }
        return tail.trim();
    }

    /**
     * Whether the innermost placeholder of a chain declares a default.
     *
     * <p>{@code ${A:${B}}} declares none, so nothing starts without {@code B} in the environment.
     * {@code ${A:${B:literal}}} declares one, and that literal ships inside the artifact.</p>
     *
     * @param rawValue value as the file spells it
     * @return {@code true} when the innermost placeholder carries a default
     */
    private static boolean innermostPlaceholderCarriesADefault(String rawValue) {
        String value = rawValue.replace("\"", "").trim();
        int open = value.lastIndexOf("${");
        if (open < 0) {
            return true;
        }
        int close = value.indexOf('}', open);
        String innermost = close < 0 ? value.substring(open) : value.substring(open, close);
        return innermost.contains(":");
    }

    /** The endpoints one exposure value resolves to when the environment supplies nothing. */
    private static Set<String> exposedEndpointsOf(String rawValue) {
        Set<String> endpoints = new LinkedHashSet<>();
        for (String endpoint : literalFallbackOf(rawValue).split(",")) {
            String trimmed = endpoint.trim();
            if (!trimmed.isEmpty()) {
                endpoints.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return endpoints;
    }

    /**
     * The configuration source of one service.
     *
     * @param module module name
     * @return the text of that module's {@link #SECURITY_CONFIG_FILE}
     */
    private static String securityConfigOf(String module) {
        String source = MAIN_SOURCES.get().get(module + "/" + SECURITY_CONFIG_FILE);
        if (source == null) {
            throw new IllegalStateException(module + " declares no " + SECURITY_CONFIG_FILE);
        }
        return source;
    }

    /**
     * The body of one method, from its opening brace to the brace that closes it.
     *
     * <p>Braces are counted rather than matched by regular expression, because a chain body nests
     * several lambda bodies inside itself and a non-greedy match would stop at the first of
     * them.</p>
     *
     * @param source    source text to read
     * @param signature text identifying the method, matched as a substring
     * @return the body including both braces
     */
    private static String methodBodyOf(String source, String signature) {
        int declaration = source.indexOf(signature);
        if (declaration < 0) {
            throw new IllegalStateException("no method matching " + signature);
        }
        int open = source.indexOf('{', declaration);
        int depth = 0;
        for (int position = open; position < source.length(); position++) {
            char character = source.charAt(position);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, position + 1);
                }
            }
        }
        throw new IllegalStateException(signature + " is not closed");
    }

    /** The body of the chain matching every request that is not an actuator endpoint. */
    private static String apiChainOf(String module) {
        return methodBodyOf(securityConfigOf(module), "SecurityFilterChain apiSecurity");
    }

    /** The body of the chain matching the actuator endpoints. */
    private static String managementChainOf(String module) {
        return methodBodyOf(securityConfigOf(module), "SecurityFilterChain managementSecurity");
    }

    /**
     * Every route rule of one chain, in the order the chain declares them.
     *
     * <p>A rule is a {@code requestMatchers} call naming a method and a path, followed by the
     * authority that route requires. Comment lines between the two are skipped, and the constant
     * names of {@link #RULE_CONSTANT_NAMES} are replaced by the values they hold so a failure reads
     * as a route table rather than as Java.</p>
     *
     * @param chain body of a filter-chain method
     * @return one {@code "METHOD path -> rule"} entry per route, in declaration order
     */
    private static List<String> routeRulesOf(String chain) {
        List<String> rules = new ArrayList<>();
        String[] lines = chain.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = ROUTE_MATCHER.matcher(lines[index]);
            if (!matcher.find()) {
                continue;
            }
            String rule = null;
            for (int ahead = index + 1; ahead < lines.length; ahead++) {
                String candidate = lines[ahead].trim();
                if (candidate.isEmpty() || candidate.startsWith("//")) {
                    continue;
                }
                rule = candidate;
                break;
            }
            if (rule == null) {
                throw new IllegalStateException("no rule follows " + lines[index].trim());
            }
            rules.add(matcher.group(1) + " " + matcher.group(2) + " -> " + normalizedRule(rule));
        }
        return rules;
    }

    /**
     * Where the last rule naming a method and a path sits in a chain.
     *
     * <p>Used only to prove the default deny follows every rule. A rule after it is unreachable, and
     * an unreachable rule reads as protection that is not there.</p>
     *
     * @param chain body of a filter-chain method
     * @return index of the last method-and-path rule, or {@code -1} when the chain declares none
     */
    private static int lastRuleIndexOf(String chain) {
        return chain.lastIndexOf(".requestMatchers(HttpMethod.");
    }

    /** Matches a rule that names a method and a path. */
    private static final Pattern ROUTE_MATCHER = Pattern.compile(
            "\\.requestMatchers\\(HttpMethod\\.(\\w+),\\s*\"([^\"]+)\"\\)");

    /**
     * One rule as a reader would state it: no leading dot, no quotes, and constants replaced by the
     * values they hold.
     *
     * @param rule rule as the source spells it
     * @return the rule in reading form
     */
    private static String normalizedRule(String rule) {
        String normalized = rule.trim();
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("\"", "");
        for (Map.Entry<String, String> constant : RULE_CONSTANT_NAMES.entrySet()) {
            normalized = normalized.replace(constant.getKey(), constant.getValue());
        }
        return normalized.trim();
    }

    /**
     * The broker login entry one configuration declares, folded onto one line.
     *
     * <p>The entry is a folded block, so its value continues on every following line indented more
     * deeply than the key. Joining them is what lets the password inside it be read as one
     * placeholder. A login entry ends in a semicolon, so the scan stops there rather than at the
     * next key: the line after the entry is a comment indented more deeply than the key it explains,
     * and an indentation rule alone would fold it into the value.</p>
     *
     * @param configuration configuration text to read
     * @return the login entry as one line, or an empty string when the key is absent
     */
    private static String brokerLoginOf(String configuration) {
        String[] lines = configuration.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (!trimmed.startsWith(BROKER_LOGIN_KEY + ":")) {
                continue;
            }
            int indent = lines[index].indexOf(BROKER_LOGIN_KEY);
            StringBuilder entry = new StringBuilder();
            for (int ahead = index + 1; ahead < lines.length; ahead++) {
                String continuation = lines[ahead];
                if (continuation.isBlank()) {
                    break;
                }
                int continuationIndent = continuation.length()
                        - continuation.stripLeading().length();
                if (continuationIndent <= indent) {
                    break;
                }
                String value = continuation.trim();
                if (value.startsWith("#")) {
                    break;
                }
                entry.append(entry.isEmpty() ? "" : " ").append(value);
                if (value.endsWith(";")) {
                    break;
                }
            }
            return entry.toString();
        }
        return "";
    }

    /**
     * The value the {@code password} member of a login entry carries.
     *
     * @param login login entry, folded onto one line
     * @return the value between the quotes, without the trailing semicolon, or an empty string when
     *         the entry names no password
     */
    private static String brokerPasswordOf(String login) {
        String member = "password=\"";
        int start = login.indexOf(member);
        if (start < 0) {
            return "";
        }
        int value = start + member.length();
        int end = login.indexOf('"', value);
        return end < 0 ? login.substring(value) : login.substring(value, end);
    }

    /** The configuration class of one service, loaded from the classpath. */
    private static Class<?> securityConfigTypeOf(String module) {
        try {
            return Class.forName(SECURITY_CONFIG_TYPES.get(module));
        } catch (ClassNotFoundException absent) {
            throw new IllegalStateException(module + " declares no "
                    + SECURITY_CONFIG_TYPES.get(module), absent);
        }
    }

    /**
     * One declared method of a configuration class, made reachable.
     *
     * <p>Both refusals are package-private, so reflection is what lets this class measure the
     * response the framework would receive without widening their visibility.</p>
     *
     * @param module         module whose configuration declares the method
     * @param name           method name
     * @param parameterCount number of parameters the method takes
     * @return the method, accessible
     */
    private static Method declaredMethodOf(String module, String name, int parameterCount) {
        for (Method candidate : securityConfigTypeOf(module).getDeclaredMethods()) {
            if (candidate.getName().equals(name)
                    && candidate.getParameterCount() == parameterCount) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        throw new IllegalStateException(module + " declares no " + name + " taking "
                + parameterCount + " parameters");
    }

    /** Invokes a method, turning a reflective failure into an unchecked one. */
    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot invoke " + method.getName(), failure);
        }
    }

    /** A request for a route a caller does not own, used to drive both refusals. */
    private static MockHttpServletRequest refusedRequest() {
        return new MockHttpServletRequest("GET", REFUSED_ROUTE);
    }

    /**
     * The response an unauthenticated caller receives, produced by the entry point itself.
     *
     * @param module module whose entry point answers
     * @return the recorded response
     */
    private static MockHttpServletResponse challengeOf(String module) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(declaredMethodOf(module, "unauthorized", 3), null,
                refusedRequest(), response, null);
        return response;
    }

    /**
     * The response an authenticated caller without the authority receives, produced by the
     * access-denied handler itself.
     *
     * @param module module whose handler answers
     * @return the recorded response
     */
    private static MockHttpServletResponse refusalOf(String module) {
        Object handler = invoke(declaredMethodOf(module, "forbidden", 0), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            Class<?> handlerType =
                    Class.forName("org.springframework.security.web.access.AccessDeniedHandler");
            Method handle = handlerType.getMethod("handle", HttpServletRequest.class,
                    HttpServletResponse.class,
                    Class.forName("org.springframework.security.access.AccessDeniedException"));
            handle.invoke(handler, refusedRequest(), response, null);
        } catch (ReflectiveOperationException unusable) {
            throw new IllegalStateException("cannot invoke the access-denied handler of " + module,
                    unusable);
        }
        return response;
    }

    /** The body of a recorded response, as text. */
    private static String bodyOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the recorded response", unreadable);
        }
    }

    /** The property names one serialized object carries, in the order it carries them. */
    private static List<String> keysOf(JsonNode object) {
        List<String> keys = new ArrayList<>();
        object.propertyNames().forEach(keys::add);
        return keys;
    }

    /** The component names one record type declares, in declaration order. */
    private static List<String> componentNamesOf(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** Reads one record component off an instance through its accessor. */
    private static Object readComponent(Object instance, RecordComponent component) {
        try {
            return component.getAccessor().invoke(instance);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "the accessor of " + component.getName() + " did not answer", failure);
        }
    }

    /** The declared field names of one entity, in declaration order. */
    private static Set<String> fieldNamesOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                names.add(field.getName());
            }
        }
        return names;
    }


    @Nested
    @DisplayName("Redaction of every response body")
    class ResponseRedaction {

        /**
         * The surface is thirty files, and every record among them is classified as a response
         * or a request. A new file or a new record fails here, which is where classification is
         * enforced. Ten of the thirty declare no record: the authorization endpoint and the
         * handler that answers a rejected request, both of which the plan requires at
         * {@code POST /authorizations}; the three account-service endpoints and the handler beside
         * them, plus the package-private account mapper, which the plan requires for the account
         * view, the account update and the cycle close; the card endpoint and the handler beside it,
         * which the plan requires for the card list, the card view and the card update; and the card
         * message inventory.
         *
         * <p>Eighteen files declare a record and seventeen record names come out of them, because
         * {@code ApiErrorResponse} is declared once in the authorization service and once in the card
         * service. The two declarations carry different components, and the classified inventory
         * holds the card one, which is the shape {@code ErrorBodyExposure} measures.</p>
         *
         * <p>One endpoint file serves three routes. {@code CardController} maps the list, the read
         * and the update of {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl} and
         * {@code app/cbl/COCRDUPC.cbl} on one collection, so the file count and the route count are
         * different numbers and neither substitutes for the other.</p>
         */
        @Test
        @DisplayName("the surface is thirty files and every record on it is classified")
        void theApiSurfaceIsThirtyFilesAndEveryRecordIsClassified() {
            Map<String, String> apiSources = new LinkedHashMap<>();
            readSourcesBelow(REPOSITORY_ROOT.get().resolve(SERVICES_DIRECTORY)
                    .resolve("account-service").resolve(MAIN_SOURCE_PATH)
                    .resolve("com/carddemo/account/api"), "account-service", apiSources);
            readSourcesBelow(REPOSITORY_ROOT.get().resolve(SERVICES_DIRECTORY)
                    .resolve("authorization-service").resolve(MAIN_SOURCE_PATH)
                    .resolve("com/carddemo/authorization/api"), "authorization-service", apiSources);
            readSourcesBelow(REPOSITORY_ROOT.get().resolve(SERVICES_DIRECTORY)
                    .resolve("card-service").resolve(MAIN_SOURCE_PATH)
                    .resolve("com/carddemo/card/api"), "card-service", apiSources);

            assertEquals(API_SOURCE_FILE_COUNT, apiSources.size(),
                    "the surface is " + API_SOURCE_FILE_COUNT + " files: " + apiSources.keySet());

            Set<String> declaredRecords = new TreeSet<>();
            Set<String> declaredClasses = new TreeSet<>();
            for (Map.Entry<String, String> source : apiSources.entrySet()) {
                String simpleName = source.getKey()
                        .substring(source.getKey().lastIndexOf('/') + 1)
                        .replace(JAVA_SUFFIX, "");
                if (source.getValue().contains("public record " + simpleName + "(")) {
                    declaredRecords.add(simpleName);
                } else {
                    declaredClasses.add(simpleName);
                }
            }

            Set<String> classified = new TreeSet<>();
            for (Class<?> type : RESPONSE_TYPES) {
                classified.add(type.getSimpleName());
            }
            for (Class<?> type : REQUEST_TYPES) {
                classified.add(type.getSimpleName());
            }
            // RefreshedCard is declared inside CardUpdateResponse and has no file of its own.
            classified.remove(CardUpdateResponse.RefreshedCard.class.getSimpleName());

            assertEquals(classified, declaredRecords,
                    "every record of the surface is classified as a response or a request");
            assertEquals(Set.of("AuthorizationController", "GlobalExceptionHandler",
                            "AccountController", "CustomerController", "BillingCycleController",
                            "AccountApiExceptionHandler", "AccountRecordMapper", "CardController",
                            "CardApiExceptionHandler",
                            CardValidationMessages.class.getSimpleName()), declaredClasses,
                    "the ten files that declare no record are the authorization endpoint, the "
                            + "three account-service endpoints, the account mapper, the card "
                            + "endpoint, the three handlers that answer a rejected request, and "
                            + "the message inventory");
            assertEquals(14, RESPONSE_TYPES.size(), "fourteen types leave as a response body");
            assertEquals(6, REQUEST_TYPES.size(), "six types enter as a request body");
        }

        /** Every declared component is classified, and every classified component is declared. */
        @Test
        @DisplayName("all sixty-eight response components are classified in both directions")
        void everyResponseComponentIsClassified() {
            Set<String> declared = new TreeSet<>();
            for (Class<?> type : RESPONSE_TYPES) {
                assertTrue(type.isRecord(), type.getSimpleName() + " is a record");
                for (RecordComponent component : type.getRecordComponents()) {
                    declared.add(componentKey(type, component));
                }
            }

            assertEquals(RESPONSE_COMPONENT_COUNT, declared.size(),
                    "the response surface declares " + RESPONSE_COMPONENT_COUNT + " components");
            assertEquals(new TreeSet<>(RESPONSE_COMPONENTS.keySet()), declared,
                    "a component added to a response is classified before it ships, and a "
                            + "classification whose component is gone is removed");
        }

        /**
         * No response component carries a name the platform refuses, unless it is classified for
         * the masked form. The rule is name-aware and classification-aware, because either alone is
         * wrong here, and the test below measures the value those two components actually carry.
         */
        @Test
        @DisplayName("no response component carries a forbidden value")
        void noResponseComponentCarriesAForbiddenValue() {
            List<String> offenders = new ArrayList<>();
            int maskedComponents = 0;
            for (Class<?> type : RESPONSE_TYPES) {
                for (RecordComponent component : type.getRecordComponents()) {
                    String key = componentKey(type, component);
                    boolean masked = RESPONSE_COMPONENTS.get(key) == Disclosure.MASKED;
                    if (masked) {
                        maskedComponents++;
                    }
                    if (isForbiddenOnAResponse(component.getName()) && !masked) {
                        offenders.add(key + " typed " + component.getType().getSimpleName());
                    }
                }
            }

            assertEquals(List.of(), offenders,
                    "a response component carries a value no response may carry: " + offenders);
            assertEquals(2, maskedComponents,
                    "two response components are classified for the masked form");
        }

        /**
         * The card record stores a verification value and no response returns it. The first assertion
         * keeps the second from passing vacuously if the field were ever renamed away.
         */
        @Test
        @DisplayName("no response returns the verification value the card record stores")
        void noResponseReturnsTheVerificationValueTheCardRecordStores() {
            assertTrue(fieldNamesOf(CardEntity.class).contains("cardVerificationValue"),
                    "the card record stores a verification value, per app/cpy/CVACT02Y.cpy:L7");

            for (Class<?> type : RESPONSE_TYPES) {
                for (RecordComponent component : type.getRecordComponents()) {
                    String folded = component.getName().toLowerCase(Locale.ROOT);
                    assertFalse(folded.contains("verification") || folded.contains("cvv")
                                    || folded.contains("securitycode"),
                            componentKey(type, component) + " names a verification value");
                }
            }
        }

        /**
         * The customer record stores a social security number and a government-issued identifier,
         * {@code app/cbl/COACTVWC.cbl:L497-L501} and line 519 put both on the source screen, and no
         * response here returns either. That omission is the deviation, so it is asserted.
         */
        @Test
        @DisplayName("no response returns the social security number or the government identifier")
        void noResponseReturnsTheIdentifiersTheSourceScreenDiscloses() {
            Set<String> customerFields = fieldNamesOf(CustomerEntity.class);
            assertTrue(customerFields.contains("socialSecurityNumber"),
                    "the customer record stores a social security number, "
                            + "per app/cpy/CVCUS01Y.cpy:L17");
            assertTrue(customerFields.contains("governmentIssuedId"),
                    "the customer record stores a government-issued identifier, "
                            + "per app/cpy/CVCUS01Y.cpy:L18");

            List<String> returned = new ArrayList<>();
            for (Class<?> type : RESPONSE_TYPES) {
                for (RecordComponent component : type.getRecordComponents()) {
                    String folded = component.getName().toLowerCase(Locale.ROOT);
                    if (folded.contains("socialsecurity") || folded.contains("ssn")
                            || folded.contains("government") || folded.contains("govt")) {
                        returned.add(componentKey(type, component));
                    }
                }
            }
            assertEquals(List.of(), returned,
                    "the source screen discloses both at app/cbl/COACTVWC.cbl:L497-L501 and L519, "
                            + "and no response here does: " + returned);

            assertFalse(componentNamesOf(CustomerView.class).contains("socialSecurityNumber"),
                    "the customer view omits the social security number");
            assertFalse(componentNamesOf(CustomerView.class).contains("governmentIssuedId"),
                    "the customer view omits the government-issued identifier");
            assertTrue(componentNamesOf(CustomerView.class).contains("dateOfBirth"),
                    "the customer view carries the date of birth the source discloses at "
                            + "app/cbl/COACTVWC.cbl:L507");
            assertTrue(componentNamesOf(CustomerView.class).contains("eftAccountId"),
                    "the customer view carries the transfer account the source discloses at "
                            + "app/cbl/COACTVWC.cbl:L520");
        }

        /**
         * Every component that names a card number is classified for the masked form, and the two
         * that do are the two the card service returns. The component is declared as text, because
         * the frozen contract of {@link CardDetailResponse} carries the masked number directly, so
         * the value itself is measured in the test below.
         */
        @Test
        @DisplayName("every card-number component of a response is classified for the masked form")
        void everyCardNumberComponentOfAResponseIsClassifiedMasked() {
            List<String> cardNumberComponents = new ArrayList<>();
            for (Class<?> type : RESPONSE_TYPES) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (component.getName().toLowerCase(Locale.ROOT).contains("cardnumber")) {
                        cardNumberComponents.add(componentKey(type, component));
                        assertEquals(String.class, component.getType(),
                                componentKey(type, component)
                                        + " carries the masked number as text");
                        assertEquals(Disclosure.MASKED,
                                RESPONSE_COMPONENTS.get(componentKey(type, component)),
                                componentKey(type, component) + " is classified as masked");
                    }
                }
            }

            assertEquals(List.of("CardDetailResponse.maskedCardNumber", "CardSummary.cardNumber"),
                    cardNumberComponents,
                    "two responses name a card number and both are classified for the masked form");
        }

        /**
         * No response the platform builds carries an unmasked number. This is the behavioural half of
         * the classification rule above, and it is the assertion that matters: the value of every
         * component classified {@link Disclosure#MASKED} is measured against
         * {@link CardUpdated#MASKED_CARD_NUMBER_PATTERN}, the unmasked shape is shown not to match
         * it, and the masking helper is shown to produce a value that does.
         */
        @Test
        @DisplayName("no response body carries an unmasked card number")
        void noResponseBodyCarriesAnUnmaskedCardNumber() {
            assertFalse(SHAPED_CARD_NUMBER.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "a sixteen-digit card number is not a masked card number");

            String maskedText = PanMasker.maskCardNumber(SHAPED_CARD_NUMBER);
            assertEquals(SHAPED_CARD_NUMBER.length(), maskedText.length(),
                    "the masked form keeps the width of the number it masks");
            assertTrue(maskedText.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "the masked form is twelve mask characters and four digits");

            List<String> unmasked = new ArrayList<>();
            for (Class<?> type : RESPONSE_TYPES) {
                Object instance = RESPONSE_INSTANCES.get().get(type.getSimpleName());
                assertNotNull(instance, type.getSimpleName() + " has an instance to measure");
                for (RecordComponent component : type.getRecordComponents()) {
                    String key = componentKey(type, component);
                    if (RESPONSE_COMPONENTS.get(key) != Disclosure.MASKED) {
                        continue;
                    }
                    Object value = readComponent(instance, component);
                    assertNotNull(value, key + " carries a value to measure");
                    if (!String.valueOf(value).matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN)) {
                        unmasked.add(key);
                    }
                }
            }
            assertEquals(List.of(), unmasked,
                    "a component classified for the masked form carries something else: " + unmasked);

            assertTrue(new CardSummary(maskedText, SHAPED_ACCOUNT_ID, "Y").cardNumber()
                            .matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "the card-number slot of a card summary carries the masked form");
            assertTrue(new CardDetailResponse(maskedText, SHAPED_ACCOUNT_ID, "NAME",
                            LocalDate.of(2026, 12, 31), "Y").maskedCardNumber()
                            .matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "the card-number slot of a card detail carries the masked form");
        }

        /**
         * Every response is serialized and the emitted keys are compared against the declared
         * components. An accessor added beside the components would widen the body, and this is where
         * that widening is caught.
         */
        @Test
        @DisplayName("every serialized response carries exactly its declared components")
        void everySerializedResponseCarriesExactlyItsDeclaredComponents() {
            ObjectMapper mapper = mapper();
            for (Class<?> type : RESPONSE_TYPES) {
                Object instance = RESPONSE_INSTANCES.get().get(type.getSimpleName());
                assertNotNull(instance, type.getSimpleName() + " has an instance to serialize");
                JsonNode tree = mapper.valueToTree(instance);

                assertTrue(tree.isObject(), type.getSimpleName() + " serializes as an object");
                assertEquals(componentNamesOf(type), keysOf(tree),
                        type.getSimpleName() + " emits its components and nothing besides");
            }
        }

        /** The two nested bodies emit the components of the type they nest, and nothing besides. */
        @Test
        @DisplayName("a nested response body emits the components of the type it nests")
        void aNestedResponseBodyEmitsTheComponentsOfTheTypeItNests() {
            ObjectMapper mapper = mapper();

            JsonNode list = mapper.valueToTree(RESPONSE_INSTANCES.get().get("CardListResponse"));
            JsonNode cards = list.get("cards");
            assertTrue(cards.isArray() && !cards.isEmpty(), "the list carries at least one card");
            assertEquals(componentNamesOf(CardSummary.class), keysOf(cards.get(0)),
                    "an entry of the card list emits the components of a card summary");

            JsonNode update = mapper.valueToTree(RESPONSE_INSTANCES.get().get("CardUpdateResponse"));
            JsonNode refreshed = update.get("refreshedCard");
            assertTrue(refreshed.isObject(), "the update response carries a refreshed snapshot");
            assertEquals(componentNamesOf(CardUpdateResponse.RefreshedCard.class),
                    keysOf(refreshed),
                    "the refreshed snapshot emits its own components and nothing besides");
        }

        /**
         * The value under a card-number key equals what the production masker produces from the same
         * number, which ties the response boundary to {@link PanMasker} rather than to a literal.
         */
        @Test
        @DisplayName("the serialized card number comes from the production masker")
        void theSerializedCardNumberComesFromTheProductionMasker() {
            ObjectMapper mapper = mapper();
            String expected = PanMasker.maskCardNumber(SHAPED_CARD_NUMBER);

            JsonNode detail = mapper.valueToTree(RESPONSE_INSTANCES.get().get("CardDetailResponse"));
            assertEquals(expected, detail.get("maskedCardNumber").stringValue(),
                    "the card detail emits what the masker produces");

            JsonNode summary = mapper.valueToTree(RESPONSE_INSTANCES.get().get("CardSummary"));
            assertEquals(expected, summary.get("cardNumber").stringValue(),
                    "the card summary emits what the masker produces");

            assertEquals(SHAPED_CARD_NUMBER.substring(
                            SHAPED_CARD_NUMBER.length() - PanMasker.VISIBLE_DIGIT_COUNT),
                    expected.substring(expected.length() - PanMasker.VISIBLE_DIGIT_COUNT),
                    "the last four digits survive and the first twelve do not");
            assertFalse(expected.contains(SHAPED_CARD_NUMBER.substring(0, 12)),
                    "the leading twelve digits are gone from the emitted value");
        }

        /**
         * The paging cursor of the card list carries a card token, so no response component of the
         * platform carries a card number in any form other than the masked one.
         *
         * <p>A cursor is the one response value a caller sends back, which makes it the one place a
         * card number could travel out and return unnoticed. The assertion reads the serialized
         * value rather than the record component, because serialization is where a response reaches
         * a caller.
         */
        @Test
        @DisplayName("the paging cursor serializes as a card token and not as a card number")
        void thePagingCursorSerializesAsACardTokenAndNotAsACardNumber() {
            ObjectMapper mapper = mapper();
            JsonNode page = mapper.valueToTree(RESPONSE_INSTANCES.get().get("CardListResponse"));
            String cursor = page.get("nextCursor").stringValue();

            assertEquals(SHAPED_CARD_TOKEN, cursor,
                    "the cursor emits what PanMasker.cardToken derives");
            assertTrue(cursor.matches(PanMasker.CARD_TOKEN_PATTERN),
                    "the cursor holds the one card-token shape this platform declares, found "
                            + cursor.length() + " characters");
            assertFalse(cursor.contains(SHAPED_CARD_NUMBER),
                    "no card number survives into the cursor");
            assertNotEquals(PanMasker.maskCardNumber(SHAPED_CARD_NUMBER), cursor,
                    "the cursor is not the masked form either, which names every card sharing "
                            + "four digits and therefore names no single browse position");
        }

        /**
         * The unmasked number and the customer identifiers enter on the request side only. Section
         * 0.6.4 of the plan requires the decision to run on the full number, exactly as
         * {@code app/cbl/CBTRN02C.cbl:L383-L387} keys the cross-reference read on it.
         */
        @Test
        @DisplayName("the unmasked number and the customer identifiers enter on the request side only")
        void theUnmaskedValuesEnterOnTheRequestSideOnly() {
            RecordComponent inboundCardNumber = null;
            for (RecordComponent component : AuthorizationRequest.class.getRecordComponents()) {
                if ("cardNumber".equals(component.getName())) {
                    inboundCardNumber = component;
                }
            }
            assertNotNull(inboundCardNumber,
                    "the authorization request accepts the number the decision keys on");
            assertEquals(String.class, inboundCardNumber.getType(),
                    "the inbound number is text, because the cross-reference read keys on all "
                            + "sixteen characters at app/cbl/CBTRN02C.cbl:L383-L387");

            List<String> inboundIdentifiers = componentNamesOf(CustomerDataRequest.class);
            assertTrue(inboundIdentifiers.containsAll(List.of("socialSecurityPart1",
                            "socialSecurityPart2", "socialSecurityPart3", "governmentIssuedId")),
                    "the customer request accepts the three slices and the government identifier "
                            + "the source update path accepts");

            for (Class<?> type : RESPONSE_TYPES) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (component.getType() != String.class
                            || !"cardNumber".equals(component.getName())) {
                        continue;
                    }
                    String key = componentKey(type, component);
                    assertEquals(Disclosure.MASKED, RESPONSE_COMPONENTS.get(key),
                            key + " returns a card number as text, so its classification has to "
                                    + "say the value is masked");
                    Object body = RESPONSE_INSTANCES.get().get(type.getSimpleName());
                    assertNotNull(body, "no instance is built for " + type.getSimpleName());
                    String emitted = (String) readComponent(body, component);
                    assertTrue(emitted.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                            key + " would return the unmasked number as text");
                }
            }
        }
    }


    @Nested
    @DisplayName("Exposure through the error body")
    class ErrorBodyExposure {

        /** Names a diagnostic component would carry, none of which the error body declares. */
        private static final List<String> DIAGNOSTIC_COMPONENT_FRAGMENTS = List.of(
                "stack",
                "trace",
                "exception",
                "throwable",
                "cause",
                "detail",
                "debug",
                "sql",
                "query",
                "request",
                "payload",
                "body",
                "parameter");

        /** The error body carries a status, one message and one route template, and nothing else. */
        @Test
        @DisplayName("the error body carries a status, one message and one route template")
        void theErrorBodyCarriesStatusMessageAndRouteOnly() {
            RecordComponent[] components = ApiErrorResponse.class.getRecordComponents();

            assertEquals(3, components.length, "the error body declares three components");
            assertEquals(List.of("status", "message", "route"),
                    componentNamesOf(ApiErrorResponse.class),
                    "the error body declares the status, the message and the route");
            assertEquals(int.class, components[0].getType(), "the status is a number");
            assertEquals(String.class, components[1].getType(), "the message is text");
            assertEquals(String.class, components[2].getType(), "the route is text");
        }

        /**
         * The error body declares no throwable and no diagnostic component. A stack trace names
         * internal types and file positions, and an echoed request carries whatever the caller sent,
         * including a card number.
         */
        @Test
        @DisplayName("the error body declares no throwable and no diagnostic component")
        void theErrorBodyDeclaresNoThrowableAndNoDiagnosticComponent() {
            for (RecordComponent component : ApiErrorResponse.class.getRecordComponents()) {
                assertFalse(Throwable.class.isAssignableFrom(component.getType()),
                        component.getName() + " would carry a throwable");
                assertFalse(StackTraceElement[].class.isAssignableFrom(component.getType()),
                        component.getName() + " would carry a stack trace");
                assertFalse(Map.class.isAssignableFrom(component.getType()),
                        component.getName() + " would carry an open map of values");

                String folded = component.getName().toLowerCase(Locale.ROOT);
                for (String fragment : DIAGNOSTIC_COMPONENT_FRAGMENTS) {
                    assertFalse(folded.contains(fragment),
                            component.getName() + " names a diagnostic value through " + fragment);
                }
                assertFalse(isForbiddenOnAResponse(component.getName()),
                        component.getName() + " names a value no response may carry");
            }
        }

        /**
         * The error body refuses a resolved path and accepts a template. A route such as
         * {@code /cards/1234567890123456} would put the number in the body and in every log line that
         * copies it, and the constructor refuses any run of more than four digits.
         */
        @Test
        @DisplayName("the error body refuses a resolved route and accepts a template")
        void theErrorBodyRefusesAResolvedRouteAndAcceptsATemplate() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ApiErrorResponse(404, "DID NOT FIND THIS CARD",
                            "/cards/" + SHAPED_CARD_NUMBER),
                    "a route holding a card number is refused");
            assertThrows(IllegalArgumentException.class,
                    () -> new ApiErrorResponse(404, "DID NOT FIND THIS CARD",
                            "/accounts/" + SHAPED_ACCOUNT_ID),
                    "a route holding an account identifier is refused");
            assertThrows(NullPointerException.class,
                    () -> new ApiErrorResponse(404, "DID NOT FIND THIS CARD", null),
                    "a route is required");

            ApiErrorResponse template =
                    new ApiErrorResponse(404, "DID NOT FIND THIS CARD", "/cards/{cardNumber}");
            assertEquals("/cards/{cardNumber}", template.route(),
                    "a template keeps the path variable as its name");

            JsonNode tree = mapper().valueToTree(template);
            assertEquals(List.of("status", "message", "route"), keysOf(tree),
                    "the serialized error body carries three keys");
            assertFalse(tree.toString().contains(SHAPED_CARD_NUMBER),
                    "no card number reaches the serialized error body");
        }

        /**
         * The account service's error body carries the four members RFC 9457 defines and one list of
         * field texts, spelled exactly as the refusal that service's chain writes spells them.
         *
         * <p>One service answering two shapes of error would make a client parse both. The four names
         * this asserts are the same four {@link #PROBLEM_MEMBERS} holds, which is what the two
         * refusals of {@code WebSurfaceAccessControl} measure on the chain side, so a rename on either
         * side fails on the other.</p>
         */
        @Test
        @DisplayName("the account error body carries the four RFC 9457 members and one message list")
        void theAccountErrorBodyCarriesTheFourProblemMembersAndOneMessageList() {
            RecordComponent[] components = ApiProblem.class.getRecordComponents();

            assertEquals(5, components.length, "the account error body declares five components");
            assertEquals(List.of("type", "title", "status", "detail", "messages"),
                    componentNamesOf(ApiProblem.class),
                    "the account error body declares the four standard members then the field texts");
            assertEquals(PROBLEM_MEMBERS,
                    componentNamesOf(ApiProblem.class).subList(0, PROBLEM_MEMBERS.size()),
                    "the four standard members are spelled as the refusal the chain writes spells "
                            + "them, and in the same order");
            assertEquals(String.class, components[0].getType(), "the type is text");
            assertEquals(String.class, components[1].getType(), "the title is text");
            assertEquals(int.class, components[2].getType(), "the status is a number");
            assertEquals(String.class, components[3].getType(), "the detail is text");
            assertEquals(List.class, components[4].getType(), "the field texts are a list");
        }

        /**
         * The account error body declares no throwable, no stack trace, no open map and no component
         * that would echo a request.
         *
         * <p>{@code detail} is exempt from the fragment scan and from nothing else. RFC 9457 names
         * that member, {@link #PROBLEM_MEMBERS} holds it, and the chain of every one of the six
         * services already writes it, so refusing the name here would refuse the standard. What the
         * member carries is asserted instead: every value comes from a constant of the record, and the
         * assertion below measures that no constant names an identifier or a route.</p>
         */
        @Test
        @DisplayName("the account error body declares no throwable and no diagnostic component")
        void theAccountErrorBodyDeclaresNoThrowableAndNoDiagnosticComponent() {
            for (RecordComponent component : ApiProblem.class.getRecordComponents()) {
                assertFalse(Throwable.class.isAssignableFrom(component.getType()),
                        component.getName() + " would carry a throwable");
                assertFalse(StackTraceElement[].class.isAssignableFrom(component.getType()),
                        component.getName() + " would carry a stack trace");
                assertFalse(Map.class.isAssignableFrom(component.getType()),
                        component.getName() + " would carry an open map of values");

                String folded = component.getName().toLowerCase(Locale.ROOT);
                for (String fragment : DIAGNOSTIC_COMPONENT_FRAGMENTS) {
                    if ("detail".equals(component.getName()) && "detail".equals(fragment)) {
                        continue;
                    }
                    assertFalse(folded.contains(fragment),
                            component.getName() + " names a diagnostic value through " + fragment);
                }
                assertFalse(isForbiddenOnAResponse(component.getName()),
                        component.getName() + " names a value no response may carry");
            }

            for (String fixed : List.of(ApiProblem.VALIDATION_FAILED_DETAIL,
                    ApiProblem.NOT_FOUND_DETAIL, ApiProblem.MALFORMED_REQUEST_DETAIL,
                    ApiProblem.INTERNAL_FAILURE_DETAIL)) {
                assertFalse(fixed.contains("/"),
                        "a detail naming a path would put the requested route in the body: " + fixed);
                assertFalse(fixed.matches(".*\\d{5,}.*"),
                        "a detail carrying a run of five or more digits would carry an identifier: "
                                + fixed);
            }
        }

        /**
         * The account error body refuses an empty message list and withholds the field texts from its
         * own representation.
         *
         * <p>A document carrying an empty {@code messages} member says a field failed and declines to
         * say which, so the constructor refuses it and a failure with no field text omits the member.
         * The representation withholds the texts because a text produced by an edit quotes the label
         * of a field the caller supplied, and a representation reaching a log would copy it.</p>
         */
        @Test
        @DisplayName("the account error body refuses an empty message list and withholds the texts")
        void theAccountErrorBodyRefusesAnEmptyMessageListAndWithholdsTheTexts() {
            assertThrows(IllegalArgumentException.class,
                    () -> ApiProblem.of(422, ApiProblem.VALIDATION_FAILED,
                            ApiProblem.VALIDATION_FAILED_DETAIL, List.of()),
                    "an empty message list is refused rather than stored");
            assertThrows(NullPointerException.class,
                    () -> new ApiProblem(null, ApiProblem.NOT_FOUND, 404,
                            ApiProblem.NOT_FOUND_DETAIL, null),
                    "the problem type is required");

            ApiProblem withoutTexts = ApiProblem.of(404, ApiProblem.NOT_FOUND,
                    ApiProblem.NOT_FOUND_DETAIL);
            assertNull(withoutTexts.messages(), "a failure with no field text carries no member");
            assertEquals(List.of("type", "title", "status", "detail"),
                    keysOf(mapper().valueToTree(withoutTexts)),
                    "the absent member is omitted from the wire form rather than sent as null");

            String rejected = "Current Balance is not valid";
            ApiProblem withTexts = ApiProblem.of(422, ApiProblem.VALIDATION_FAILED,
                    ApiProblem.VALIDATION_FAILED_DETAIL, List.of(rejected));
            assertFalse(withTexts.toString().contains(rejected),
                    "the representation would copy a field text into every log line that prints it");
            assertTrue(withTexts.toString().contains("1 " + EventEnvelope.WITHHELD),
                    "the representation says how many texts there are and withholds them: "
                            + withTexts.toString());
            assertEquals(List.of(rejected), withTexts.messages(),
                    "the caller still receives the text the edit produced");
        }
    }

    @Nested
    @DisplayName("Configuration of the transport and the credentials")
    class TransportConfiguration {

        /**
         * Text this repository puts in every published example credential, so a value that still
         * carries it is a value nobody replaced. Each service declares the same marker
         * package-privately; it is restated here because this class reads six services rather than
         * being one.
         */
        private static final String PUBLISHED_PLACEHOLDER_MARKER = "REPLACE";

        /**
         * Every service exposes the health, metrics and Prometheus endpoints when the environment
         * supplies nothing, which is what a container runs with.
         */
        @Test
        @DisplayName("every service exposes health, metrics and Prometheus and nothing else")
        void everyServiceExposesHealthMetricsAndPrometheusOnly() {
            for (String module : ALL_MODULES) {
                List<String> declared =
                        actuatorExposureIncludes(APPLICATION_YAMLS.get().get(module));
                assertEquals(1, declared.size(),
                        module + " declares one actuator exposure list: " + declared);

                String raw = declared.get(0);
                assertFalse(raw.contains("*"),
                        module + " would expose every actuator endpoint through a wildcard");
                assertEquals(EXPOSED_ACTUATOR_ENDPOINTS, exposedEndpointsOf(raw),
                        module + " exposes the three endpoints the plan requires");
            }
        }

        /** No service exposes a diagnostic endpoint, and none declares an exclusion to rely on. */
        @Test
        @DisplayName("no service exposes a diagnostic actuator endpoint")
        void noServiceExposesADiagnosticActuatorEndpoint() {
            for (String module : ALL_MODULES) {
                Set<String> exposed =
                        exposedEndpointsOf(actuatorExposureIncludes(
                                APPLICATION_YAMLS.get().get(module))
                                .get(0));
                for (String diagnostic : DIAGNOSTIC_ACTUATOR_ENDPOINTS) {
                    assertFalse(exposed.contains(diagnostic),
                            module + " exposes the " + diagnostic + " endpoint");
                }
                assertTrue(valuesOf(APPLICATION_YAMLS.get().get(module), "exclude").isEmpty(),
                        module + " names an exclusion, so its exposure is a denylist rather than "
                                + "the allowlist this contract reads");
            }
        }

        /**
         * No artifact carries a credential. Each service resolves its user and its password from a
         * placeholder, and the innermost placeholder of the password carries no default, so nothing
         * starts with a password the repository supplied.
         */
        @Test
        @DisplayName("no service bakes a credential into an artifact")
        void noServiceBakesACredentialIntoAnArtifact() {
            for (String module : ALL_MODULES) {
                String configuration = APPLICATION_YAMLS.get().get(module);
                for (String key : CREDENTIAL_KEYS) {
                    List<String> values = valuesOf(configuration, key);
                    assertFalse(values.isEmpty(), module + " declares a " + key);
                    for (String value : values) {
                        assertTrue(value.replace("\"", "").startsWith("${"),
                                module + " declares a literal " + key
                                        + " rather than a placeholder");
                    }
                }

                for (String password : valuesOf(configuration, "password")) {
                    assertFalse(innermostPlaceholderCarriesADefault(password),
                            module + " declares a fallback password, so the artifact carries one");
                }

            }
        }

        /**
         * Every service authenticates to the broker, and the password it presents comes from the
         * environment rather than from the artifact.
         *
         * <p>The assertion above cannot reach this password. It sits inside a login entry as a member
         * of a folded block rather than beside a key of its own, and {@link #valuesOf} reads the
         * value of a key. The entry is read whole here and its password is measured with the same
         * rule the datasource password is measured with, so the two credentials a service presents
         * are held to one standard.</p>
         *
         * <p>The username carries a default and the password carries none, which is the split the
         * configuration comment states: a login name is not a credential, and an unset password stops
         * start-up rather than presenting one this repository published.</p>
         *
         * <p>No COBOL ancestor: the CardDemo source authenticates nothing to anything. A batch step
         * reads a dataset the scheduler already entitled it to read, and no program presents a
         * credential to a message broker because there is no broker. This assertion measures a
         * mechanism the platform introduced, so it names the deviation rather than a source
         * line.</p>
         */
        @Test
        @DisplayName("every service presents a broker credential the environment supplies")
        void everyServicePresentsABrokerCredentialTheEnvironmentSupplies() {
            for (String module : ALL_MODULES) {
                String configuration = APPLICATION_YAMLS.get().get(module);

                List<String> protocols = valuesOf(configuration, "protocol");
                assertEquals(1, protocols.size(),
                        module + " declares the broker transport once");
                assertEquals(BROKER_SECURITY_PROTOCOL, literalFallbackOf(protocols.get(0)),
                        module + " falls back to a broker transport other than the one the shipped "
                                + "stack authenticates over");

                List<String> mechanisms = valuesOf(configuration, "sasl.mechanism");
                assertEquals(1, mechanisms.size(),
                        module + " declares the authentication mechanism once");
                assertEquals(BROKER_SASL_MECHANISM, literalFallbackOf(mechanisms.get(0)),
                        module + " falls back to a mechanism other than the one the shipped broker "
                                + "accepts");

                String login = brokerLoginOf(configuration);
                assertFalse(login.isEmpty(), module + " declares no broker login entry, so it "
                        + "presents no credential on a transport that requires one");
                assertTrue(login.contains("PlainLoginModule required"),
                        module + " names a login module other than the one its mechanism requires: "
                                + login);
                assertTrue(login.endsWith(";"),
                        module + " declares a login entry that is not terminated: " + login);
                assertTrue(login.contains("username=\"${"),
                        module + " bakes a broker login name into the artifact rather than "
                                + "resolving it");

                String password = brokerPasswordOf(login);
                assertFalse(password.isEmpty(), module + " declares a login entry naming no "
                        + "password");
                assertTrue(password.startsWith("${"),
                        module + " bakes a broker password into the artifact");
                assertFalse(innermostPlaceholderCarriesADefault(password),
                        module + " declares a fallback broker password, so the artifact carries "
                                + "one");
                assertFalse(password.contains(PUBLISHED_PLACEHOLDER_MARKER),
                        module + " leaves the published placeholder in the login entry");
            }
        }
    }


    @Nested
    @DisplayName("The web surface and the access control on it")
    class WebSurfaceAccessControl {

        /**
         * Five modules declare a web endpoint and ten files carry the annotations: the synchronous
         * authorization surface the plan requires at {@code POST /authorizations}, the account and
         * customer reads and the account update the plan requires of the account service, the cycle
         * close beside them, the read-only balance query at {@code GET /balances/{accountId}}, the
         * read-only notification history, and the read-only fraud assessment query at
         * {@code GET /fraud-assessments}. All five are named here by file, so a sixth module or an
         * eleventh file fails this assertion and the failure text names what then has to be written.
         *
         * <p>The account service is the only module here that answers a request which writes. Its
         * three routes and their authorities sit in {@link #API_ROUTE_RULES}, and the two writing
         * routes require the administrator role, because {@code app/cbl/COACTUPC.cbl} is reached from
         * the administrator menu and {@code app/cbl/CBACT04C.cbl} runs as a scheduled job rather than
         * from a terminal at all. The three assertions this contract owes a new endpoint already
         * cover it: {@link #anUnauthenticatedCallerReceivesAChallengeThatNamesNothing} measures the
         * status an anonymous caller receives,
         * {@link #anAuthenticatedCallerWithoutTheAuthorityReceivesARefusalNamingNothing} measures the
         * refusal body, both measure the headers the response carries, and
         * {@code ErrorBodyExposure} measures the body its handler returns for a failure the chain
         * admitted.</p>
         *
         * <p>The balance query returns the four values {@code 1200-SETUP-SCREEN-VARS} at
         * {@code app/cbl/COACTVWC.cbl:L460} moves, less the six the account service owns. It reads
         * and writes nothing, so it adds a route to the surface and no path that changes a
         * balance.</p>
         *
         * <p>The fraud assessment query reads the rows the fraud consumer records and carries no
         * write route, so it adds no path that drives an assessment from a request. Its two
         * operations sit behind the one rule {@link #API_ROUTE_RULES} declares for that module,
         * {@code GET /fraud-assessments/**}, and the three assertions this contract owed a new
         * endpoint already cover it: {@link #anUnauthenticatedCallerReceivesAChallengeThatNamesNothing}
         * measures the status an anonymous caller receives,
         * {@link #anAuthenticatedCallerWithoutTheAuthorityReceivesARefusalNamingNothing} measures
         * the refusal body, and both measure the headers the response carries. Each runs over
         * {@link #ALL_MODULES}, so the fraud detection service was already among them.</p>
         */
        @Test
        @DisplayName("the web endpoints are the twelve files six services declare")
        void theWebEndpointsAreTheTwelveFilesSixServicesDeclare() {
            assertTrue(MAIN_SOURCES.get().size() >= MAIN_SOURCE_FILE_FLOOR,
                    "the scan reached " + MAIN_SOURCES.get().size() + " main sources of the six "
                            + "services and the two libraries, under the "
                            + MAIN_SOURCE_FILE_FLOOR + " they carry between them");

            Set<String> declaring = new TreeSet<>();
            Set<String> modules = new TreeSet<>();
            for (String occurrence : occurrencesOf(WEB_ENDPOINT_TOKENS)) {
                String file = occurrence.substring(0, occurrence.indexOf(" declares "));
                declaring.add(file);
                modules.add(file.substring(0, file.indexOf('/')));
            }

            assertEquals(ENDPOINT_SOURCE_FILES, declaring,
                    "a file now declares a web endpoint this contract does not name, so the "
                            + "contract has to grow assertions that call it: the status code an "
                            + "anonymous caller receives, the body an unhandled failure returns, "
                            + "and the headers the response carries. Add them beside the redaction "
                            + "assertions above: " + declaring);
            assertEquals(
                    Set.of("authorization-service", "account-service", "card-service",
                            "ledger-posting-service", "notification-service",
                            "fraud-detection-service"),
                    modules,
                    "the synchronous surface is the authorization endpoint, the account, customer "
                            + "and cycle-close routes, the card list, read and update, the balance "
                            + "query, the notification history and the fraud assessment query, and "
                            + "no other module answers a request: " + modules);
        }

        /**
         * Each of the six services declares its access control in one file, and no other file
         * declares any of it.
         *
         * <p>Locating it in one place per service is what makes the route table of the next
         * assertion readable at all. A rule spread across the annotations of several controllers
         * cannot be read as a table, and the route that carries no rule is then invisible rather
         * than refused.</p>
         *
         * <p>Neither shared library carries a security type. {@code cobol-compat} holds the
         * arithmetic and parsing the equivalence guarantee rests on and stays framework-free so it
         * remains unit-testable in isolation, and {@code event-contracts} is imported by every
         * service, so a framework type in either would reach all six.</p>
         */
        @Test
        @DisplayName("each service declares its access control in one file and no library does")
        void eachServiceDeclaresItsAccessControlInOneFile() {
            Map<String, Set<String>> declaring = new LinkedHashMap<>();
            for (String occurrence : occurrencesOf(DECLARED_SECURITY_TOKENS)) {
                String file = occurrence.substring(0, occurrence.indexOf(" declares "));
                String token = occurrence.substring(occurrence.indexOf(" declares ") + 10);
                declaring.computeIfAbsent(file, unused -> new TreeSet<>()).add(token);
            }

            Set<String> expected = new TreeSet<>();
            for (String module : ALL_MODULES) {
                expected.add(module + "/" + SECURITY_CONFIG_FILE);
            }
            assertEquals(expected, new TreeSet<>(declaring.keySet()),
                    "access control is declared in exactly one file per service, and the route "
                            + "table below reads that file: " + declaring.keySet());

            for (String module : ALL_MODULES) {
                assertEquals(new TreeSet<>(DECLARED_SECURITY_TOKENS),
                        declaring.get(module + "/" + SECURITY_CONFIG_FILE),
                        module + " declares a different set of security types from its peers, so "
                                + "one service is configured unlike the other five");
            }

            List<String> methodRules = occurrencesOf(ABSENT_SECURITY_TOKENS);
            assertEquals(List.of(), methodRules,
                    "a method-level rule or an authentication bypass now exists, so the route "
                            + "table below is no longer the whole of the access control and this "
                            + "contract has to grow assertions for what the annotation adds: "
                            + methodRules);
        }

        /**
         * Every route of every ordinary chain requires the authority {@link #API_ROUTE_RULES}
         * declares for it, in the order the chain applies the rules.
         *
         * <p>This is the assertion the earlier absence of a chain could not express. A chain applies
         * the first rule whose matcher accepts the request, so the order is part of the contract and
         * not a detail of formatting: moving the ownership rule for {@code GET /accounts/{accountId}}
         * above the administrator rule for {@code PUT} on the same path would leave the update
         * guarded by ownership alone.</p>
         *
         * <p>Every chain ends in {@code denyAll()} rather than {@code authenticated()}. The
         * difference decides what happens the day a controller lands for a route no rule names: with
         * {@code denyAll()} the route is refused until a rule is written, and with
         * {@code authenticated()} it is open to every identity that can sign on.</p>
         */
        @Test
        @DisplayName("every route names the authority it requires, and an unnamed route is denied")
        void everyRouteNamesTheAuthorityItRequires() {
            for (String module : ALL_MODULES) {
                String chain = apiChainOf(module);

                assertEquals(API_ROUTE_RULES.get(module), routeRulesOf(chain),
                        module + " declares a route table other than the one this contract states, "
                                + "so a route changed the authority it requires");
                assertTrue(chain.contains(DEFAULT_DENY_RULE),
                        module + " does not end its chain in " + DEFAULT_DENY_RULE
                                + ", so a route no rule names is reachable rather than refused");
                assertTrue(chain.indexOf(DEFAULT_DENY_RULE) > lastRuleIndexOf(chain),
                        module + " declares a route rule after its default deny, where no request "
                                + "reaches it");
                assertTrue(chain.contains(".csrf(csrf -> csrf.disable())"),
                        module + " leaves cross-site request forgery protection on a stateless "
                                + "surface, where the token has no session to live in");
                assertTrue(chain.contains("SessionCreationPolicy.STATELESS"),
                        module + " admits a session, so the pseudo-conversational state section "
                                + "0.6.4 of the plan removes would return by another route");
            }
        }

        /**
         * Every management chain admits the liveness probe and requires {@code ROLE_MONITORING} for
         * everything else.
         *
         * <p>The probe is admitted because the thing that calls it cannot hold a credential: an
         * orchestrator restarts a container on a failed probe, and a probe answering 401 would
         * restart every healthy container instead. Nothing else is admitted, because the metric
         * families section 0.6.4 of the plan names count declined transactions per account and are
         * therefore as disclosing as the rows they count.</p>
         */
        @Test
        @DisplayName("every management chain admits the probe and requires the monitoring role")
        void everyManagementChainAdmitsTheProbeAndRequiresTheMonitoringRole() {
            for (String module : ALL_MODULES) {
                String chain = managementChainOf(module);

                assertTrue(chain.contains("EndpointRequest.toAnyEndpoint()"),
                        module + " does not match its management chain to the actuator endpoints, "
                                + "so the chain either misses them or captures the API");
                assertTrue(chain.contains(PERMITTED_PROBE),
                        module + " does not admit the liveness probe, so a failed authentication "
                                + "would read as a failed container");
                assertTrue(chain.contains(MANAGEMENT_DEFAULT_RULE),
                        module + " does not require the monitoring role for every other endpoint, "
                                + "so the metrics are readable without one");
                assertEquals(List.of(), routeRulesOf(chain),
                        module + " names a method and a path in its management chain, which the "
                                + "endpoint matcher above already selects");
            }
        }

        /**
         * An unauthenticated caller receives a 401 that names the scheme it expects and nothing
         * about what it asked for.
         *
         * <p>The entry point is invoked rather than described, so the header, the media type, the
         * cache directive and the four members of the body are measured as a caller would receive
         * them.</p>
         *
         * <p>The detail names no account, no customer, no card and not the route. A refusal that
         * repeated the requested resource would write an identifier the caller does not own into
         * that caller's own log, which is the disclosure the masking rule of section 0.6.4 exists to
         * prevent, arriving through the error path instead of the success path.</p>
         */
        @Test
        @DisplayName("an unauthenticated caller receives a challenge that names nothing it asked for")
        void anUnauthenticatedCallerReceivesAChallengeThatNamesNothing() {
            for (String module : ALL_MODULES) {
                MockHttpServletResponse response = challengeOf(module);

                assertEquals(401, response.getStatus(),
                        module + " answers a caller with no credential with a status other than "
                                + "401");
                assertEquals("Basic realm=\"carddemo\", charset=\"UTF-8\"",
                        response.getHeader("WWW-Authenticate"),
                        module + " sends a 401 that does not say which scheme it expects");
                assertNotNull(response.getContentType(), module + " sends no media type");
                assertTrue(response.getContentType().startsWith(PROBLEM_MEDIA_TYPE),
                        module + " answers a refusal with a media type other than a problem "
                                + "document: " + response.getContentType());
                assertEquals("no-store", response.getHeader("Cache-Control"),
                        module + " lets a refusal be cached, so a later caller could be served one");

                assertProblemDocument(module, bodyOf(response), 401, "Unauthorized");
            }
        }

        /**
         * An authenticated caller without the authority receives a 403 that names neither the
         * operation nor the identifier.
         *
         * <p>A 404 would say the row exists to a caller that may not have it, and a detailed 403
         * would put the identifier back into a log. The handler answers both cases with the same
         * body, so probing for another subject's rows returns the same three hundred and eighty-four
         * bytes whatever the subject.</p>
         *
         * <p>A 403 carries no {@code WWW-Authenticate} header. The caller authenticated
         * successfully, so offering it a scheme to retry with would invite it to retry a request
         * that cannot succeed.</p>
         */
        @Test
        @DisplayName("an authenticated caller without the authority receives a 403 naming nothing")
        void anAuthenticatedCallerWithoutTheAuthorityReceivesARefusalNamingNothing() {
            for (String module : ALL_MODULES) {
                MockHttpServletResponse response = refusalOf(module);

                assertEquals(403, response.getStatus(),
                        module + " answers an entitled-but-unauthorized caller with a status other "
                                + "than 403");
                assertNull(response.getHeader("WWW-Authenticate"),
                        module + " offers a scheme to a caller that already authenticated");
                assertNotNull(response.getContentType(), module + " sends no media type");
                assertTrue(response.getContentType().startsWith(PROBLEM_MEDIA_TYPE),
                        module + " answers a refusal with a media type other than a problem "
                                + "document: " + response.getContentType());
                assertEquals("no-store", response.getHeader("Cache-Control"),
                        module + " lets a refusal be cached, so a later caller could be served one");

                assertProblemDocument(module, bodyOf(response), 403, "Forbidden");
            }
        }

        /**
         * Measures one refusal body: its four members, its status, its title, and the identifiers it
         * does not carry.
         *
         * @param module module that produced the body
         * @param body   the body as the caller receives it
         * @param status status the document reports
         * @param title  title the document reports
         */
        private void assertProblemDocument(String module, String body, int status, String title) {
            JsonNode document = mapper().readTree(body);

            assertEquals(PROBLEM_MEMBERS, keysOf(document),
                    module + " writes a problem document carrying members other than the four RFC "
                            + "9457 names: " + keysOf(document));
            assertEquals(status, document.get("status").asInt(),
                    module + " reports a status in the body other than the one it sent");
            assertEquals(title, document.get("title").asString(),
                    module + " reports an unexpected title");
            assertEquals("about:blank", document.get("type").asString(),
                    module + " names a problem type this platform does not publish a page for");

            String detail = document.get("detail").asString();
            assertFalse(detail.isBlank(), module + " refuses without saying anything");
            for (String disclosed : List.of(REFUSED_ROUTE, SHAPED_ACCOUNT_ID, SHAPED_CARD_NUMBER,
                    "accountId", "customerId", "cardNumber")) {
                assertFalse(body.contains(disclosed),
                        module + " names " + disclosed + " in a refusal, which writes what the "
                                + "caller asked for into the caller's own log");
            }
        }

        /**
         * Every service depends on the security starter, neither library and no aggregator does, and
         * no module depends on a token-issuing starter.
         *
         * <p>The classpath check is the one that cannot be fooled by a coordinate this list did not
         * anticipate: the type the chains are built from is loadable, which is what lets the two
         * refusals above be invoked rather than described.</p>
         *
         * <p>No module reaches for OAuth 2.0 or a JavaScript Object Signing and Encryption library.
         * Signon in the source compares a stored password against a supplied one at
         * {@code app/cbl/COSGN00C.cbl:L223}; there is no token, no issuer and no expiry to
         * reproduce, and introducing one would add an unrequested identity provider to a demo
         * section 0.8.1 of the plan asks to keep walkable in about five minutes.</p>
         */
        @Test
        @DisplayName("every service depends on the security starter and no library does")
        void everyServiceDependsOnTheSecurityStarterAndNoLibraryDoes() {
            List<String> declarations = new ArrayList<>();
            List<Path> descriptors = new ArrayList<>();
            descriptors.add(REPOSITORY_ROOT.get().resolve("card-platform/pom.xml"));
            descriptors.add(REPOSITORY_ROOT.get().resolve("card-platform/equivalence-tests/pom.xml"));
            for (String module : ALL_MODULES) {
                descriptors.add(REPOSITORY_ROOT.get().resolve(SERVICES_DIRECTORY)
                        .resolve(module).resolve("pom.xml"));
            }
            for (String library : ALL_LIBRARIES) {
                descriptors.add(REPOSITORY_ROOT.get().resolve(LIBRARIES_DIRECTORY)
                        .resolve(library).resolve("pom.xml"));
            }

            for (Path descriptor : descriptors) {
                String text = readText(descriptor);
                for (String artifact : SECURITY_ARTIFACT_TOKENS) {
                    if (text.contains(artifact)) {
                        declarations.add(descriptor.getFileName() + " of "
                                + descriptor.getParent().getFileName() + " declares " + artifact);
                    }
                }
            }
            assertEquals(10, descriptors.size(),
                    "the aggregator, the two libraries, the six services and the equivalence module "
                            + "are all read");

            List<String> expected = new ArrayList<>();
            for (String module : ALL_MODULES) {
                expected.add("pom.xml of " + module + " declares spring-boot-starter-security");
            }
            assertEquals(expected, declarations,
                    "the six services depend on the security starter and nothing else does, so a "
                            + "library or the aggregator now carries the framework, or a service "
                            + "reaches for a coordinate this contract does not name: "
                            + declarations);

            assertDoesNotThrow(() -> Class.forName(SECURITY_FILTER_CHAIN_TYPE),
                    "the type the chains are built from is loadable, which is what lets the two "
                            + "refusals above be invoked rather than described");
        }

        /**
         * No module declares a cross-origin policy. A browser is not a client of this platform yet,
         * because section 0.3.4 of the plan records that no user interface is in scope.
         */
        @Test
        @DisplayName("no module declares a cross-origin policy yet")
        void noModuleDeclaresACrossOriginPolicyYet() {
            List<String> policies = occurrencesOf(CROSS_ORIGIN_TOKENS);
            assertEquals(List.of(), policies,
                    "a cross-origin policy now exists, so this contract has to grow assertions for "
                            + "the origins it admits and the ones it refuses: " + policies);
        }
    }

    @Nested
    @DisplayName("Self-verification of the scans this class performs")
    class SelfVerification {

        /** The source scan finds a token that is present and reports none that is absent. */
        @Test
        @DisplayName("the source scan finds a token it is given and misses none")
        void theSourceScanFindsATokenItIsGiven() {
            assertFalse(MAIN_SOURCES.get().isEmpty(), "sources were read");
            assertTrue(MAIN_SOURCES.get().containsKey("card-service/CardEntity.java"),
                    "the scan reaches a source below a nested package");

            String someSource = MAIN_SOURCES.get().get("card-service/CardEntity.java");
            assertTrue(someSource.contains("class CardEntity"),
                    "the scan reads the text of a source and not only its name");

            assertFalse(occurrencesOf(List.of("class CardEntity")).isEmpty(),
                    "a token that is present is found, so an empty result means absence");
            assertEquals(List.of(),
                    occurrencesOf(List.of("@RestController_this_token_is_not_in_any_source")),
                    "a token that is absent is not reported");
        }

        /** The response rule refuses the names it must refuse and passes the names it must pass. */
        @Test
        @DisplayName("the response rule refuses a forbidden name and passes a safe one")
        void theResponseRuleRefusesAForbiddenNameAndPassesASafeOne() {
            for (String forbidden : List.of("cardVerificationValue", "cvv", "socialSecurityNumber",
                    "ssn", "password", "pan", "pin", "cardNumber", "primaryAccountNumber",
                    "governmentIssuedId", "govtIssuedId", "driversLicense", "passportNumber")) {
                assertTrue(isForbiddenOnAResponse(forbidden),
                        forbidden + " is a name no response may declare");
            }

            for (String safe : List.of("maskedCardNumber", "accountId", "activeStatus",
                    "currentBalance", "expirationDate", "shippingAddress", "expandedName",
                    "dateOfBirth", "eftAccountId", "route", "message", "status")) {
                assertFalse(isForbiddenOnAResponse(safe),
                        safe + " is a name a response may declare");
            }
        }

        /** The exposure reader resolves a nested default, a plain default and a wildcard. */
        @Test
        @DisplayName("the exposure reader resolves a nested default and reports a wildcard")
        void theExposureReaderResolvesADefaultAndReportsAWildcard() {
            assertEquals(Set.of("health", "metrics", "prometheus"),
                    exposedEndpointsOf("${MANAGEMENT_ENDPOINTS:health,metrics,prometheus}"),
                    "a plain default is read");
            assertEquals(Set.of("health", "metrics", "prometheus"),
                    exposedEndpointsOf("\"${OUTER:${MANAGEMENT_ENDPOINTS:health,metrics,"
                            + "prometheus}}\""),
                    "a nested default is read through both placeholders and the quotes");
            assertEquals(Set.of("health", "env"), exposedEndpointsOf("${A:health, env}"),
                    "a space after a comma is not part of an endpoint name");
            assertEquals(Set.of("*"), exposedEndpointsOf("${A:*}"),
                    "a wildcard default is reported as one, so the assertion above catches it");
        }

        /** The credential reader tells a fallback password from one the environment must supply. */
        @Test
        @DisplayName("the credential reader tells a fallback password from a required one")
        void theCredentialReaderTellsAFallbackFromARequiredValue() {
            assertFalse(innermostPlaceholderCarriesADefault(
                            "${SPRING_DATASOURCE_PASSWORD:${POSTGRES_PASSWORD}}"),
                    "the shipped form leaves the password to the environment");
            assertTrue(innermostPlaceholderCarriesADefault(
                            "${SPRING_DATASOURCE_PASSWORD:${POSTGRES_PASSWORD:letmein}}"),
                    "a fallback inside the innermost placeholder is reported");
            assertTrue(innermostPlaceholderCarriesADefault("${POSTGRES_PASSWORD:letmein}"),
                    "a single placeholder carrying a fallback is reported");
            assertTrue(innermostPlaceholderCarriesADefault("letmein"),
                    "a literal carries its own value, which is the worst case of all");

            assertEquals(List.of("${POSTGRES_USER:carddemo}"),
                    valuesOf("spring:\n  datasource:\n    username: ${POSTGRES_USER:carddemo}\n",
                            "username"),
                    "the reader takes the value of a key and not the key");
            assertEquals(List.of(), valuesOf("    # password: letmein\n", "password"),
                    "a commented key is not a declaration");
        }

        /** The serialized-key reader reports the keys of an object in the order it carries them. */
        @Test
        @DisplayName("the serialized-key reader reports the keys of an object in order")
        void theSerializedKeyReaderReportsTheKeysOfAnObjectInOrder() {
            JsonNode tree = mapper().valueToTree(
                    new ApiErrorResponse(422, "message text", "/cards/{cardNumber}"));

            assertEquals(List.of("status", "message", "route"), keysOf(tree),
                    "the keys are reported in declaration order");
            assertEquals(3, keysOf(tree).size(), "no key is reported twice");
            assertEquals(List.of(), keysOf(mapper().valueToTree(Map.of())),
                    "an empty object yields no key");
        }

        /** Every response instance the serialization tests use is built and populated. */
        @Test
        @DisplayName("every response type has a populated instance to serialize")
        void everyResponseTypeHasAPopulatedInstance() {
            assertEquals(RESPONSE_TYPES.size(), RESPONSE_INSTANCES.get().size(),
                    "one instance per response type");
            for (Class<?> type : RESPONSE_TYPES) {
                Object instance = RESPONSE_INSTANCES.get().get(type.getSimpleName());
                assertNotNull(instance, type.getSimpleName() + " has an instance");
                assertTrue(type.isInstance(instance),
                        type.getSimpleName() + " is keyed to an instance of itself");
            }
        }
    }
}


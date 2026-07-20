package com.aws.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Failsafe integration test proving the cross-layer observability required by review finding #51:
 * a single authenticated request must yield a trace that evidences the
 * {@code http.server.requests → service → repository} boundaries, and the added observations must be
 * <em>low-cardinality</em> and carry <em>no sensitive attributes</em>.
 *
 * <p><b>Why this test exists.</b> Before finding #51 the migration relied solely on the
 * auto-configured HTTP ({@code http.server.requests}) and Spring Batch spans; no business service or
 * repository method was annotated, so a representative trace never showed the intervening
 * web&rarr;service and service&rarr;repository boundaries. {@code ObservabilityConfig} now registers a
 * single {@link io.micrometer.observation.aop.ObservedAspect ObservedAspect} and a bounded set of
 * {@link io.micrometer.observation.annotation.Observed @Observed} entry points is annotated; this test
 * is the local verification the Observability rule mandates.</p>
 *
 * <p><b>Representative flow.</b> The account-view screen (CICS {@code CAVW} / {@code COACTVWC}) is the
 * cleanest cross-layer path because {@code AccountViewService.mainEntry} (annotated
 * {@code carddemo.account.view}) calls the <em>declared</em> finder
 * {@code CardXrefRepository.findByXrefAcctId} (annotated {@code carddemo.repository.cardxref.by-account}),
 * the {@code CXACAIX} alternate-index read. Driving {@code GET}&nbsp;then&nbsp;{@code POST}
 * {@code /account/view} over the real Spring MVC + Spring Security + JPA stack (Testcontainers
 * PostgreSQL, Flyway-seeded account {@code 1}) therefore produces the full three-layer span tree.</p>
 *
 * <p><b>Capture mechanism.</b> A {@link CapturingObservationHandler} is attached to the real
 * autowired {@link ObservationRegistry} (rather than a replacement registry) so the assertions
 * observe the production wiring exactly. Recording is gated by an enable flag and the buffer is reset
 * per test, so a handler left on a cached application context never records outside the test window.</p>
 *
 * @see com.aws.carddemo.config.ObservabilityConfig
 */
@AutoConfigureMockMvc
class ObservabilityTracingIT extends AbstractPostgresIntegrationTest {

    /** Micrometer observation name on {@code AccountViewService.mainEntry} (service layer). */
    private static final String OBS_SERVICE_ACCOUNT_VIEW = "carddemo.account.view";

    /** Micrometer observation name on {@code CardXrefRepository.findByXrefAcctId} (repository layer). */
    private static final String OBS_REPOSITORY_CARDXREF = "carddemo.repository.cardxref.by-account";

    /** Auto-configured Spring MVC server observation name (web layer / trace root). */
    private static final String OBS_HTTP_SERVER = "http.server.requests";

    /** GET/POST route for the account-view screen (CICS tran CAVW / program COACTVWC). */
    private static final String ROUTE_ACCOUNT_VIEW = "/account/view";

    /** Request-parameter name for the account-id search filter (BMS ACCTSIDI, PIC 9(11)). */
    private static final String PARAM_ACCT_ID = "acctsid";

    /** Request-parameter name carrying the pressed PF-key token. */
    private static final String PARAM_PFKEY = "pfkey";

    /** PF-key token for the ENTER action (resolved to {@code DFHENTER}). */
    private static final String PFKEY_ENTER = "ENTER";

    /** Flyway-seeded account id whose {@code CCXREF} rows the read chain resolves. */
    private static final String SEEDED_ACCT_ID = "1";

    /** Non-admin role; either authenticated role may reach the account-view screen. */
    private static final String ROLE_USER = "USER";

    /**
     * Attribute keys that must never appear on any observation. Compared after stripping
     * {@code '_'} and {@code '.'} and lower-casing, so variants such as {@code acct_id} /
     * {@code acct.id} / {@code ACCTID} all collapse to the same sensitive token.
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "acctid", "accountid", "cardnum", "cardnumber", "custid", "customerid",
            "usrid", "userid", "username", "ssn", "password", "pwd", "pin", "cvv");

    /** The default {@code ObservedAspect} convention contributes only these low-cardinality keys. */
    private static final Set<String> ALLOWED_OBSERVED_KEYS = Set.of("class", "method");

    /** Regex matching a 16-digit card-number-like value anywhere in a tag value (defense in depth). */
    private static final String CARD_NUMBER_REGEX = ".*\\d{16}.*";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservationRegistry observationRegistry;

    private final CapturingObservationHandler handler = new CapturingObservationHandler();

    @BeforeEach
    void attachHandler() {
        handler.reset();
        handler.enable();
        observationRegistry.observationConfig().observationHandler(handler);
    }

    @AfterEach
    void detachHandler() {
        // The registry exposes no remove API; disabling stops recording so a handler left on a
        // cached context is inert for every subsequent test.
        handler.disable();
    }

    /**
     * Drives one authenticated account-view request and asserts the resulting trace evidences the
     * web&rarr;service&rarr;repository boundaries with only low-cardinality, non-sensitive attributes.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void representativeTraceEvidencesWebServiceRepositoryBoundaries() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // First entry (SEND-MAP): render the prompt and flip the pseudo-conversational context.
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW).session(session))
                .andExpect(status().isOk());
        // Re-entry (RECEIVE-MAP + 9000-READ-ACCT): the CXACAIX read chain runs for account 1.
        mockMvc.perform(post(ROUTE_ACCOUNT_VIEW)
                        .session(session)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk());

        List<Observation.Context> stopped = handler.stopped();

        // (1) All three layers produced an observation.
        Set<String> names = new HashSet<>();
        stopped.forEach(context -> names.add(context.getName()));
        assertThat(names)
                .as("web, service and repository observations must all be present")
                .contains(OBS_HTTP_SERVER, OBS_SERVICE_ACCOUNT_VIEW, OBS_REPOSITORY_CARDXREF);

        // (2) Cross-layer nesting: repository under service, service under HTTP server span.
        Observation.Context repository = firstNamed(stopped, OBS_REPOSITORY_CARDXREF);
        Observation.Context service = firstNamed(stopped, OBS_SERVICE_ACCOUNT_VIEW);
        assertThat(hasAncestorNamed(repository, OBS_SERVICE_ACCOUNT_VIEW))
                .as("repository span must nest under the account-view service span")
                .isTrue();
        assertThat(hasAncestorNamed(service, OBS_HTTP_SERVER))
                .as("service span must nest under the HTTP server span")
                .isTrue();

        // (3) The @Observed observations expose only low-cardinality class/method keys.
        for (Observation.Context context : stopped) {
            if (context.getName().startsWith("carddemo.")) {
                Set<String> keys = new HashSet<>();
                context.getLowCardinalityKeyValues().forEach(kv -> keys.add(kv.getKey()));
                context.getHighCardinalityKeyValues().forEach(kv -> keys.add(kv.getKey()));
                assertThat(keys)
                        .as("observation %s must expose only low-cardinality class/method keys",
                                context.getName())
                        .isSubsetOf(ALLOWED_OBSERVED_KEYS);
            }
        }

        // (4) No observation on any layer carries a sensitive identifier as a key or value.
        for (Observation.Context context : stopped) {
            context.getAllKeyValues().forEach(kv -> {
                String normalizedKey = kv.getKey().toLowerCase(Locale.ROOT)
                        .replace("_", "").replace(".", "");
                assertThat(SENSITIVE_KEYS)
                        .as("observation %s must not tag sensitive key '%s'",
                                context.getName(), kv.getKey())
                        .doesNotContain(normalizedKey);
                assertThat(kv.getValue())
                        .as("observation %s key '%s' must not leak a card-number-like value",
                                context.getName(), kv.getKey())
                        .doesNotMatch(CARD_NUMBER_REGEX);
            });
        }
    }

    /** Returns the first captured context with the given name, failing the test if none matched. */
    private static Observation.Context firstNamed(List<Observation.Context> contexts, String name) {
        return contexts.stream()
                .filter(context -> name.equals(context.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no observation named " + name + " was captured"));
    }

    /**
     * Walks the parent chain of {@code start} and reports whether any ancestor observation carries
     * {@code ancestorName}, evidencing a cross-layer boundary without requiring the ancestor to be the
     * immediate parent (robust against framework-inserted intermediate observations).
     */
    private static boolean hasAncestorNamed(Observation.ContextView start, String ancestorName) {
        ObservationView parent = start.getParentObservation();
        while (parent != null) {
            Observation.ContextView parentContext = parent.getContextView();
            if (ancestorName.equals(parentContext.getName())) {
                return true;
            }
            parent = parentContext.getParentObservation();
        }
        return false;
    }

    /**
     * Records every stopped {@link Observation.Context} while enabled. Attached to the production
     * {@link ObservationRegistry}; recording is gated so a handler left on a cached application
     * context stays inert outside the owning test.
     */
    private static final class CapturingObservationHandler
            implements ObservationHandler<Observation.Context> {

        private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        private volatile boolean enabled;

        void enable() {
            this.enabled = true;
        }

        void disable() {
            this.enabled = false;
        }

        void reset() {
            this.stopped.clear();
        }

        List<Observation.Context> stopped() {
            return this.stopped;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            if (this.enabled) {
                this.stopped.add(context);
            }
        }
    }
}

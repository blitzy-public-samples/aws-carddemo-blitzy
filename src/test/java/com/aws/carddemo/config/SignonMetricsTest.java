package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.config.ObservabilityConfig.SignonMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Unit tests for {@link ObservabilityConfig.SignonMetrics}, the listener that emits the
 * {@code carddemo.signon} counter at the controller-reached authentication boundary (review finding
 * P7-OBS-01).
 *
 * <p>The tests exercise the listener directly against an in-memory {@link SimpleMeterRegistry} (no
 * Spring context), asserting the pre-registration invariant, the success/failure increments, and the
 * privacy invariant that only the low-cardinality {@code outcome} tag is attached &mdash; never a user
 * identifier. They deliberately do <em>not</em> re-test Spring's own event publishing (that the wired
 * {@code ProviderManager} publishes the events is covered by the runtime re-verification and by the
 * existing {@code SecurityConfigIT} authentication assertions).</p>
 */
class SignonMetricsTest {

    /** Fresh in-memory registry per test so counter values start at a known baseline. */
    private SimpleMeterRegistry registry;

    /** Subject under test, constructed against {@link #registry}. */
    private SignonMetrics metrics;

    /**
     * Creates a fresh registry and a {@link SignonMetrics} bound to it before each test.
     */
    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SignonMetrics(registry);
    }

    /**
     * Closes the registry after each test to release its resources.
     */
    @AfterEach
    void tearDown() {
        registry.close();
    }

    /**
     * Both {@code carddemo.signon} series are pre-registered at construction so they are present (at
     * zero) before any signon occurs, giving a clean before/after Prometheus comparison.
     */
    @Test
    void bothOutcomeSeriesArePreRegisteredAtZero() {
        assertThat(counter("success").count()).isZero();
        assertThat(counter("failure").count()).isZero();
    }

    /**
     * A published {@link AuthenticationSuccessEvent} increments only the {@code outcome=success} series.
     */
    @Test
    void successEventIncrementsSuccessCounterOnly() {
        metrics.onAuthenticationSuccess(new AuthenticationSuccessEvent(authToken()));

        assertThat(counter("success").count()).isEqualTo(1.0d);
        assertThat(counter("failure").count()).isZero();
    }

    /**
     * A wrong-password failure event increments only the {@code outcome=failure} series.
     */
    @Test
    void badCredentialsFailureEventIncrementsFailureCounterOnly() {
        metrics.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(
                authToken(), new BadCredentialsException("Invalid credentials")));

        assertThat(counter("failure").count()).isEqualTo(1.0d);
        assertThat(counter("success").count()).isZero();
    }

    /**
     * An unknown-user failure (which Spring Security also publishes as an
     * {@code AbstractAuthenticationFailureEvent}) is counted as a failure, so both COBOL failure paths
     * ("Wrong Password" and "User not found") are reflected in the same {@code outcome=failure} series.
     */
    @Test
    void userNotFoundFailureEventIncrementsFailureCounter() {
        metrics.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(
                authToken(), new UsernameNotFoundException("User not found")));

        assertThat(counter("failure").count()).isEqualTo(1.0d);
        assertThat(counter("success").count()).isZero();
    }

    /**
     * Privacy invariant (P7-OBS-01): the emitted counter carries only the single {@code outcome} tag and
     * no user identifier, even though the source event carries the authenticated principal name.
     */
    @Test
    void counterCarriesOnlyOutcomeTagAndNoUserIdentifier() {
        metrics.onAuthenticationSuccess(new AuthenticationSuccessEvent(authToken()));

        Counter c = counter("success");
        assertThat(c.getId().getTags()).hasSize(1);
        assertThat(c.getId().getTag(SignonMetrics.OUTCOME_TAG)).isEqualTo("success");
        // The principal name ("ADMIN001") must not appear anywhere in the meter identity.
        assertThat(c.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("ADMIN001"));
    }

    /**
     * Repeated events accumulate on the appropriate series (counters are monotonic).
     */
    @Test
    void repeatedEventsAccumulate() {
        metrics.onAuthenticationSuccess(new AuthenticationSuccessEvent(authToken()));
        metrics.onAuthenticationSuccess(new AuthenticationSuccessEvent(authToken()));
        metrics.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(
                authToken(), new BadCredentialsException("Invalid credentials")));

        assertThat(counter("success").count()).isEqualTo(2.0d);
        assertThat(counter("failure").count()).isEqualTo(1.0d);
    }

    /**
     * Looks up the pre-registered {@code carddemo.signon} counter for the given {@code outcome} tag.
     *
     * @param outcome the outcome tag value ({@code success} or {@code failure})
     * @return the matching {@link Counter}
     */
    private Counter counter(String outcome) {
        return registry.get(SignonMetrics.SIGNON_METER_NAME)
                .tag(SignonMetrics.OUTCOME_TAG, outcome)
                .counter();
    }

    /**
     * Builds an authenticated token whose principal name is a recognizable user id, so the privacy
     * assertion can prove that id never leaks into a meter tag.
     *
     * @return an authenticated {@link Authentication} for user {@code ADMIN001}
     */
    private static Authentication authToken() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "ADMIN001", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}

package com.carddemo.authorization.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds how much of this service one source, one identity or one moment may consume.
 *
 * <p>ADDITIVE. The source had no request rate to bound. A CICS transaction arrived from a terminal
 * an operator was sitting at, and the nightly feed of {@code app/jcl/POSTTRAN.jcl} arrived once a
 * day as a dataset, so neither path could be driven faster than a person or a scheduler drove it. A
 * credential presented over HTTP can be, and the cost of an attempt is not small: every request
 * carrying a credential runs one bcrypt verification, and an accepted call records one
 * authorization decision, consumes one transaction identifier from the sequence, writes one outbox
 * row and from there reaches the three services that consume the fan-out.
 *
 * <p>Four ceilings are applied, each over a fixed window, and any one of them refuses the request
 * with 429.
 *
 * <ul>
 * <li><b>Failed authentications from one source.</b> The credential-guessing control. A source that
 * has already failed the configured number of authentications inside the window is refused before
 * the next guess reaches the password encoder. Only a request that CARRIED a credential and was
 * answered 401 counts, so a caller that simply forgot to authenticate is not treated as a guess.
 * </li>
 * <li><b>Requests from one source.</b> The blanket ceiling.</li>
 * <li><b>Requests presenting one identity.</b> The same ceiling keyed by the username the
 * credential names, so one credential spread across many addresses is bounded too. The username is
 * read from the header without verifying it, is used as a counter key alone, and is never
 * logged.</li>
 * <li><b>State-changing requests from one source.</b> A stricter ceiling on {@code POST},
 * {@code PUT}, {@code PATCH} and {@code DELETE}. {@code POST /authorizations} is the one route of
 * this service, so this is the authorization-route quota: the ceiling bounding how fast a
 * decision can be asked for.</li>
 * </ul>
 *
 * <p>Concurrency is bounded as well: past the configured number of requests in flight the next one
 * is refused rather than queued behind a connection pool that would then time out.
 *
 * <p>The filter runs at {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} minus one, which
 * places it ahead of the security chain. That ordering is the point of the control: a refusal has
 * to cost less than the attempt it refuses, and an attempt that reached the chain would already
 * have paid for a bcrypt verification.
 *
 * <p>The management base path is exempt. A container health check, a Kubernetes probe and a metrics
 * scrape all arrive on a schedule the deployment sets, and a throttled probe reads as a failed
 * container, which is the reasoning {@code config/SecurityConfig} gives for admitting the probe
 * without a credential.
 *
 * <p>The counters live in this process, so a deployment running several replicas bounds each
 * replica rather than the cluster, and the source is {@code ServletRequest.getRemoteAddr()}
 * rather than a forwarding header. A deployment behind a proxy sets
 * {@code server.forward-headers-strategy} so the container resolves the client address itself.
 *
 * <p>The map of counters is bounded at {@link #MAX_TRACKED_KEYS}. Past it the rolled windows are
 * removed first, and a key that still finds no room shares one overflow window, so an attacker
 * varying the address or the username cannot grow this map without limit. That removal walks the
 * whole map, so it runs at most once every tenth of a window and never twice at once; a caller
 * sending a run of addresses nobody has seen before therefore pays for one walk rather than one per
 * address.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
public class RequestRateCeilingFilter extends OncePerRequestFilter {

    /** Name of the meter counting refusals, tagged by the ceiling that refused. */
    static final String THROTTLED_METER = "carddemo.authorization.requests.throttled";

    /** The tag key naming which ceiling refused a request. */
    static final String STAGE_TAG = "stage";

    /** The ceiling that counts failed authentications from one source. */
    static final String AUTHENTICATION_STAGE = "authentication";

    /** The blanket ceiling on requests from one source. */
    static final String SOURCE_STAGE = "source";

    /** The ceiling on requests presenting one identity. */
    static final String IDENTITY_STAGE = "identity";

    /** The stricter ceiling on state-changing requests from one source. */
    static final String WRITE_STAGE = "write";

    /** The bound on requests in flight at one moment. */
    static final String CONCURRENCY_STAGE = "concurrency";

    /** The five ceilings, in the order they are applied. */
    static final List<String> STAGES = List.of(AUTHENTICATION_STAGE, SOURCE_STAGE, IDENTITY_STAGE,
            WRITE_STAGE, CONCURRENCY_STAGE);

    /** The methods that write, and therefore carry the stricter ceiling. */
    static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    /** Largest number of counter keys held at once, before expired windows are removed. */
    static final int MAX_TRACKED_KEYS = 10_000;

    /** The window every key past {@link #MAX_TRACKED_KEYS} shares. */
    static final String OVERFLOW_KEY = "overflow";

    /** The one text a refusal carries. It names no route, no ceiling and no identifier. */
    static final String REFUSAL_BODY = "{\"type\":\"about:blank\",\"title\":\"Too many "
            + "requests\",\"status\":429,\"detail\":\"This request exceeded a rate this service "
            + "accepts. Retry after the interval the response names.\"}";

    /** The credential scheme an identity ceiling can be keyed from. */
    private static final String BASIC_PREFIX = "Basic ";

    /** Longest credential header read, so a large header cannot be turned into a counter key. */
    private static final int MAX_CREDENTIAL_HEADER_LENGTH = 1024;

    /** Length of one window, in milliseconds. */
    private final long windowMillis;

    /**
     * Shortest interval between two expiry sweeps of the counter map, a tenth of one window.
     *
     * <p>Derived rather than configured: a sweep exists to reclaim rolled windows, so how often one
     * is worth running follows the length of a window and nothing else.
     */
    private final long expirySweepIntervalMillis;

    /** Requests one source, and one identity, may make inside one window. */
    private final long requestsPerWindow;

    /** State-changing requests one source may make inside one window. */
    private final long writeRequestsPerWindow;

    /** Failed authentications from one source inside one window before that source is refused. */
    private final long authenticationFailuresPerWindow;

    /** Requests in flight at one moment. */
    private final int concurrentRequests;

    /** Path prefix of the management surface, which no ceiling applies to. */
    private final String managementBasePath;

    /** One counter per ceiling, registered at start-up so a dashboard reads zero, not none. */
    private final Map<String, Counter> refusals;

    /** The fixed windows, keyed by ceiling and subject, bounded at {@link #MAX_TRACKED_KEYS}. */
    private final ConcurrentHashMap<String, FixedWindow> windows = new ConcurrentHashMap<>();

    /** Requests in flight at this moment. */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * When the last expiry sweep ran, on the same clock the windows are stamped from.
     *
     * <p>{@link Long#MIN_VALUE} until the first sweep, so the first key that finds the map full
     * sweeps rather than waiting out an interval that never started.
     */
    private final AtomicLong lastExpirySweepMillis = new AtomicLong(Long.MIN_VALUE);

    /**
     * Takes every ceiling and registers one counter per ceiling.
     *
     * @param windowSeconds                   length of one window, one second or more
     * @param requestsPerWindow               requests one source, and one identity, may make
     * @param writeRequestsPerWindow          state-changing requests one source may make
     * @param authenticationFailuresPerWindow failed authentications from one source before that
     *                                        source is refused for the rest of the window
     * @param concurrentRequests              requests in flight at one moment
     * @param managementBasePath              path prefix of the exempt management surface
     * @param meters                          the registry the refusal counters are registered in
     * @throws IllegalStateException when a ceiling falls below one. The message names the property
     *                               and carries no value
     */
    public RequestRateCeilingFilter(
            @Value("${carddemo.api.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${carddemo.api.rate-limit.requests-per-window:600}") long requestsPerWindow,
            @Value("${carddemo.api.rate-limit.write-requests-per-window:120}")
            long writeRequestsPerWindow,
            @Value("${carddemo.api.rate-limit.authentication-failures-per-window:20}")
            long authenticationFailuresPerWindow,
            @Value("${carddemo.api.rate-limit.concurrent-requests:64}") int concurrentRequests,
            @Value("${management.endpoints.web.base-path:/actuator}") String managementBasePath,
            MeterRegistry meters) {

        require(windowSeconds, "carddemo.api.rate-limit.window-seconds");
        require(requestsPerWindow, "carddemo.api.rate-limit.requests-per-window");
        require(writeRequestsPerWindow, "carddemo.api.rate-limit.write-requests-per-window");
        require(authenticationFailuresPerWindow,
                "carddemo.api.rate-limit.authentication-failures-per-window");
        require(concurrentRequests, "carddemo.api.rate-limit.concurrent-requests");
        if (managementBasePath == null || managementBasePath.isBlank()) {
            throw new IllegalStateException(
                    "management.endpoints.web.base-path names the management surface");
        }

        this.windowMillis = windowSeconds * 1000L;
        // A tenth of a window, and never less than one millisecond: the shortest window a caller may
        // configure is one second, and a tenth of that is still a hundred milliseconds.
        this.expirySweepIntervalMillis = Math.max(1L, this.windowMillis / 10L);
        this.requestsPerWindow = requestsPerWindow;
        this.writeRequestsPerWindow = writeRequestsPerWindow;
        this.authenticationFailuresPerWindow = authenticationFailuresPerWindow;
        this.concurrentRequests = concurrentRequests;
        this.managementBasePath = managementBasePath.trim();

        MeterRegistry registry = Objects.requireNonNull(meters, "meters must be present");
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String stage : STAGES) {
            counters.put(stage, Counter.builder(THROTTLED_METER)
                    .description("requests refused by a rate ceiling")
                    .tag(STAGE_TAG, stage)
                    .register(registry));
        }
        this.refusals = Map.copyOf(counters);
    }

    /**
     * Applies every ceiling, then passes the request along and records a failed authentication.
     *
     * @param request  the request under inspection
     * @param response the response a refusal is written to
     * @param chain    the rest of the chain
     * @throws ServletException when the rest of the chain raises one
     * @throws IOException      when the response cannot be written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path != null && path.startsWith(this.managementBasePath)) {
            chain.doFilter(request, response);
            return;
        }

        long now = nowMillis();
        String source = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();

        // Read rather than create. A source that has never failed an authentication has no
        // window here, and creating one per request would double what this map holds for
        // traffic that authenticates correctly.
        FixedWindow failures = this.windows.get(AUTHENTICATION_STAGE + ':' + source);
        if (failures != null
                && failures.reached(now, this.windowMillis, this.authenticationFailuresPerWindow)) {
            refuse(response, AUTHENTICATION_STAGE,
                    failures.retryAfterSeconds(now, this.windowMillis));
            return;
        }
        if (!admits(SOURCE_STAGE, source, now, this.requestsPerWindow, response)) {
            return;
        }
        String identity = identityOf(request);
        if (identity != null
                && !admits(IDENTITY_STAGE, identity, now, this.requestsPerWindow, response)) {
            return;
        }
        if (WRITE_METHODS.contains(methodOf(request))
                && !admits(WRITE_STAGE, source, now, this.writeRequestsPerWindow, response)) {
            return;
        }
        if (this.inFlight.incrementAndGet() > this.concurrentRequests) {
            this.inFlight.decrementAndGet();
            refuse(response, CONCURRENCY_STAGE, 1L);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            this.inFlight.decrementAndGet();
            if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()
                    && request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
                long failed = nowMillis();
                windowFor(AUTHENTICATION_STAGE + ':' + source, failed)
                        .record(failed, this.windowMillis);
            }
        }
    }

    /**
     * Names the request method in upper case, treating an absent method as one that writes.
     *
     * <p>A request arriving with no method is not a read this filter can vouch for, so it takes the
     * stricter ceiling rather than the blanket one.
     *
     * @param request the request under inspection
     * @return the method in upper case, or {@code POST} when the request names none
     */
    private static String methodOf(HttpServletRequest request) {
        String method = request.getMethod();
        return method == null ? "POST" : method.toUpperCase(Locale.ROOT);
    }

    /**
     * Admits one request against a ceiling, writing the refusal when the ceiling is reached.
     *
     * @param stage    the ceiling being applied, one of {@link #STAGES}
     * @param subject  the source address or the identity the ceiling is keyed by
     * @param now      the current instant, in milliseconds
     * @param ceiling  requests the window admits
     * @param response the response a refusal is written to
     * @return {@code true} when the request was admitted
     * @throws IOException when the refusal cannot be written
     */
    private boolean admits(String stage, String subject, long now, long ceiling,
            HttpServletResponse response) throws IOException {
        FixedWindow window = windowFor(stage + ':' + subject, now);
        if (window.admit(now, this.windowMillis, ceiling)) {
            return true;
        }
        refuse(response, stage, window.retryAfterSeconds(now, this.windowMillis));
        return false;
    }

    /**
     * Writes the one refusal this filter answers with.
     *
     * @param response          the response to write
     * @param stage             the ceiling that refused, which the counter is tagged by
     * @param retryAfterSeconds seconds until the window rolls, one or more
     * @throws IOException when the response cannot be written
     */
    private void refuse(HttpServletResponse response, String stage, long retryAfterSeconds)
            throws IOException {
        this.refusals.get(stage).increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.getWriter().write(REFUSAL_BODY);
    }

    /**
     * Returns the window for one key, keeping the map bounded.
     *
     * <p>At capacity the expired windows are removed first. A key that still finds no room shares
     * the overflow window, which bounds the map without letting one new key deny service to a key
     * already being tracked.
     *
     * @param key the ceiling and subject, joined
     * @param now the current instant, in milliseconds
     * @return the window this key counts in
     */
    private FixedWindow windowFor(String key, long now) {
        FixedWindow tracked = this.windows.get(key);
        if (tracked != null) {
            return tracked;
        }
        if (this.windows.size() >= MAX_TRACKED_KEYS) {
            removeExpiredWindows(now);
        }
        if (this.windows.size() >= MAX_TRACKED_KEYS) {
            return this.windows.computeIfAbsent(OVERFLOW_KEY, unused -> new FixedWindow(now));
        }
        return this.windows.computeIfAbsent(key, unused -> new FixedWindow(now));
    }

    /**
     * Removes the windows that have rolled, at most once every {@link #expirySweepIntervalMillis}.
     *
     * <p>The sweep walks every entry, so it is {@value #MAX_TRACKED_KEYS} comparisons. Running it
     * for each arriving key was the defect: once the map was full, a run of keys nobody had seen
     * before turned one cheap lookup each into one full walk each, and a caller sending such a run
     * spent this service's processor time rather than its own.
     *
     * <p>Two guards make one sweep serve them all. The interval is what stops a second walk from
     * following the first, and the compare-and-set is what stops two threads walking at once: a
     * thread that loses it has nothing to do, because the thread that won is removing the same
     * entries it would have removed.
     *
     * <p>The interval is a tenth of a window rather than a fixed number of milliseconds, so it
     * follows the configured window instead of contradicting it. A window that has rolled is
     * therefore reclaimed within a tenth of a window of rolling, and every key arriving before that
     * shares the overflow window exactly as it did before, which is the same admission the map's
     * bound has always produced.
     *
     * @param now the current instant, in milliseconds
     */
    private void removeExpiredWindows(long now) {
        long swept = this.lastExpirySweepMillis.get();
        if (swept != Long.MIN_VALUE && now - swept < this.expirySweepIntervalMillis) {
            return;
        }
        if (!this.lastExpirySweepMillis.compareAndSet(swept, now)) {
            return;
        }
        long expiredBefore = now - this.windowMillis;
        this.windows.entrySet().removeIf(entry -> !OVERFLOW_KEY.equals(entry.getKey())
                && entry.getValue().startedBefore(expiredBefore));
    }

    /**
     * Reads the current instant.
     *
     * <p>The window arithmetic reads the clock through this one method, so a measurement of the
     * window boundary does not have to wait for the boundary to arrive.
     *
     * @return milliseconds since the epoch
     */
    long nowMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Reports how many counter keys are held at this moment.
     *
     * @return the tracked key count
     */
    int trackedKeyCount() {
        return this.windows.size();
    }

    /**
     * Reports when the last expiry sweep of the counter map ran.
     *
     * <p>The value a test reads to prove one sweep served a whole burst of keys nobody had seen
     * before, rather than one sweep serving each of them.
     *
     * @return the sweep instant in milliseconds, or {@link Long#MIN_VALUE} before the first sweep
     */
    long lastExpirySweepAt() {
        return this.lastExpirySweepMillis.get();
    }

    /**
     * Names the identity a Basic credential carries, without verifying it.
     *
     * <p>The username is a counter key and nothing else. It is never logged, and the password the
     * same header carries is not retained.
     *
     * @param request the request under inspection
     * @return the username, or {@code null} when the request carries no readable Basic credential
     */
    private static String identityOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() > MAX_CREDENTIAL_HEADER_LENGTH
                || !header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return null;
        }
        String encoded = header.substring(BASIC_PREFIX.length()).trim();
        if (encoded.isEmpty()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        String credential = new String(decoded, StandardCharsets.UTF_8);
        int separator = credential.indexOf(':');
        return separator > 0 ? credential.substring(0, separator) : null;
    }

    /**
     * Refuses a ceiling below one, naming the property and no value.
     *
     * @param ceiling  the configured ceiling
     * @param property the property name the message reports
     * @throws IllegalStateException when the ceiling falls below one
     */
    private static void require(long ceiling, String property) {
        if (ceiling < 1L) {
            throw new IllegalStateException(property + " counts up from one");
        }
    }

    /**
     * One fixed window of one counter.
     *
     * <p>A fixed window rather than a sliding one: it costs two longs per key, which is what keeps
     * {@link #MAX_TRACKED_KEYS} keys affordable, and the boundary effect it admits is bounded by
     * the window length.
     */
    private static final class FixedWindow {

        /** When the current window opened, in milliseconds. */
        private long startMillis;

        /** Requests counted in the current window. */
        private long count;

        /**
         * Opens the first window.
         *
         * @param startMillis the instant the window opens
         */
        FixedWindow(long startMillis) {
            this.startMillis = startMillis;
        }

        /**
         * Counts one request when the window has room.
         *
         * @param now          the current instant, in milliseconds
         * @param windowMillis the window length
         * @param ceiling      requests the window admits
         * @return {@code true} when the request was counted
         */
        synchronized boolean admit(long now, long windowMillis, long ceiling) {
            roll(now, windowMillis);
            if (this.count >= ceiling) {
                return false;
            }
            this.count++;
            return true;
        }

        /**
         * Counts one event without a ceiling, which is how a failed authentication is recorded.
         *
         * @param now          the current instant, in milliseconds
         * @param windowMillis the window length
         */
        synchronized void record(long now, long windowMillis) {
            roll(now, windowMillis);
            this.count++;
        }

        /**
         * Reports whether the count has reached a ceiling.
         *
         * @param now          the current instant, in milliseconds
         * @param windowMillis the window length
         * @param ceiling      the ceiling to test
         * @return {@code true} when the window holds the ceiling or more
         */
        synchronized boolean reached(long now, long windowMillis, long ceiling) {
            roll(now, windowMillis);
            return this.count >= ceiling;
        }

        /**
         * Reports how long until this window rolls, never less than one second.
         *
         * @param now          the current instant, in milliseconds
         * @param windowMillis the window length
         * @return whole seconds a caller should wait
         */
        synchronized long retryAfterSeconds(long now, long windowMillis) {
            long remaining = this.startMillis + windowMillis - now;
            return remaining <= 0L ? 1L : (remaining + 999L) / 1000L;
        }

        /**
         * Reports whether this window opened before an instant, which is how it is purged.
         *
         * @param instant the instant to compare against, in milliseconds
         * @return {@code true} when the window opened before it
         */
        synchronized boolean startedBefore(long instant) {
            return this.startMillis < instant;
        }

        /**
         * Opens a new window once the current one has run out.
         *
         * @param now          the current instant, in milliseconds
         * @param windowMillis the window length
         */
        private void roll(long now, long windowMillis) {
            if (now - this.startMillis >= windowMillis) {
                this.startMillis = now;
                this.count = 0L;
            }
        }
    }
}

package com.carddemo.notification.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a state-changing request that a first-party client did not make.
 *
 * <p>ADDITIVE. A 3270 terminal cannot be driven by a page a cardholder happens to be reading: a
 * transaction identifier reaches {@code app/csd/CARDDEMO.CSD} only from a terminal a signed-on
 * operator holds, so the source had no cross-site vector to defend against. HTTP Basic does have
 * one, because a browser attaches a cached credential to a request a page made without being asked.
 * This service answers reads alone, and its read model is written by the three listeners rather
 * than by a caller. That is the reason the filter is here rather than the reason it is not: the
 * read-only shape of this surface becomes an enforced property instead of a fact a reader has to go
 * and check, and the first write added is refused from a foreign page without anyone remembering to
 * guard it.
 *
 * <p>Three conditions are tested, and any one of them refuses the request.
 *
 * <ul>
 * <li><b>Fetch metadata.</b> A request declaring a {@code Sec-Fetch-Site} that is neither
 * {@code same-origin} nor {@code same-site} is refused.</li>
 * <li><b>Origin.</b> An {@code Origin} naming another origin is refused.</li>
 * <li><b>A request header no form can set.</b> An unsafe method carrying no value in the
 * header {@code carddemo.api.cross-site.required-header} names is refused.</li>
 * </ul>
 *
 * <p>Safe methods pass untouched. {@code GET}, {@code HEAD}, {@code OPTIONS} and {@code TRACE}
 * change nothing, and the review of this surface concedes that a read is already protected: no
 * cross-origin policy is granted anywhere, so a browser withholds the answer from the page that
 * asked for it. That is also why no path is exempted: the liveness probe and the metrics scrape
 * are reads, so they never reach a test here.
 *
 * <p>The filter runs at {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} minus one, which puts
 * it ahead of the security chain and last of the four filters that run there. A forged call is by
 * definition authenticated, so refusing it before the chain is what stops this platform paying for a
 * bcrypt verification on a request it was always going to refuse.
 *
 * <p>Running early is only safe because the check defers when the request carries no
 * {@code Authorization} header. Such a request is refused by the chain with 401 and no password to
 * verify, so nothing is spent and the answer a caller sees is the one it saw when this filter ran
 * after the chain. A cross-site request presenting a credential is refused here with 403, before the
 * verification and before {@code api/AuthorizationController} runs. The two answers a caller can
 * observe are therefore unchanged, and the cost of the refusal is not.
 *
 * <p>The refusal is 403 carrying the same problem document {@code config/SecurityConfig} writes for
 * an unauthorized or forbidden request. It names no route, no header value and no identifier.
 *
 * <p>A deployment terminating Transport Layer Security at a proxy has to forward the client-facing
 * host, which {@code server.forward-headers-strategy} does. Without it this filter compares
 * {@code Origin} against the address the proxy dialled rather than the one the browser used.
 *
 * <p>An instance holds no mutable state beyond its counter, so request threads share one.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
public class CrossSiteRequestFilter extends OncePerRequestFilter {

    /** The header a first-party client sets, and the shipped default of the property below. */
    public static final String DEFAULT_REQUIRED_HEADER = "X-CardDemo-Request";

    /** The header a browser fills in with the relationship between page and target. */
    static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";

    /** The two {@code Sec-Fetch-Site} values a first-party call carries. */
    static final Set<String> FIRST_PARTY_FETCH_SITES = Set.of("same-origin", "same-site");

    /** The methods that change nothing and are therefore never tested. */
    static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** Name of the meter counting refusals. */
    static final String REFUSED_METER = "carddemo.notification.requests.cross.site.refused";

    /** The one text a refusal carries. It names no route, no header value and no identifier. */
    static final String REFUSAL_BODY = "{\"type\":\"about:blank\",\"title\":\"Forbidden\","
            + "\"status\":403,\"detail\":\"This service accepts a state-changing request only from "
            + "a first-party client presenting the request header it requires.\"}";

    /** Name of the header a state-changing request has to carry. */
    private final String requiredHeader;

    /** Counts refusals, so the control is visible on the metrics endpoint. */
    private final Counter refused;

    /**
     * Takes the header name and registers the refusal counter.
     *
     * @param requiredHeader value of {@code carddemo.api.cross-site.required-header}, a non-blank
     *                       header name. It is not a secret: its protection is that a form cannot
     *                       set a header and a cross-origin script call cannot either without a
     *                       preflight this platform never grants
     * @param meters         the registry the refusal counter is registered in
     * @throws IllegalStateException when the header name is blank. The message names the property
     */
    public CrossSiteRequestFilter(
            @Value("${carddemo.api.cross-site.required-header:" + DEFAULT_REQUIRED_HEADER + "}")
            String requiredHeader,
            MeterRegistry meters) {
        if (requiredHeader == null || requiredHeader.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.api.cross-site.required-header names a header");
        }
        this.requiredHeader = requiredHeader.trim();
        this.refused = Counter.builder(REFUSED_METER)
                .description("state-changing requests refused as cross-site")
                .register(Objects.requireNonNull(meters, "meters must be present"));
    }

    /**
     * Refuses a forged state-changing request and passes every other one along unchanged.
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

        String method = request.getMethod();
        if (method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            // No credential, so there is no cached credential to forge with and no password to
            // verify. The chain answers 401, which is the answer this route gave before this
            // filter was ordered ahead of it.
            chain.doFilter(request, response);
            return;
        }
        if (declaresAForeignSite(request) || declaresAForeignOrigin(request)
                || carriesNoRequestHeader(request)) {
            this.refused.increment();
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.getWriter().write(REFUSAL_BODY);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Reports whether the browser stated the request came from somewhere else.
     *
     * @param request the request under inspection
     * @return {@code true} when {@code Sec-Fetch-Site} is present and is not first-party
     */
    private static boolean declaresAForeignSite(HttpServletRequest request) {
        String site = request.getHeader(FETCH_SITE_HEADER);
        return site != null
                && !FIRST_PARTY_FETCH_SITES.contains(site.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Reports whether an {@code Origin} header names something other than this service.
     *
     * @param request the request under inspection
     * @return {@code true} when {@code Origin} is present and does not match this service's origin
     */
    private static boolean declaresAForeignOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        return origin != null && !origin.trim().equalsIgnoreCase(originOf(request));
    }

    /**
     * Reports whether the non-simple header a first-party client sets is missing.
     *
     * @param request the request under inspection
     * @return {@code true} when the configured header is absent or blank
     */
    private boolean carriesNoRequestHeader(HttpServletRequest request) {
        String declared = request.getHeader(this.requiredHeader);
        return declared == null || declared.isBlank();
    }

    /**
     * Renders the origin this request reached, as a browser would write it.
     *
     * <p>The default port of the scheme is left off, because a browser leaves it off too.
     *
     * @param request the request under inspection
     * @return the scheme, host and, where it is not the default, the port
     */
    private static String originOf(HttpServletRequest request) {
        String scheme = request.getScheme() == null
                ? "" : request.getScheme().toLowerCase(Locale.ROOT);
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        StringBuilder own = new StringBuilder(scheme).append("://").append(request.getServerName());
        if (port > 0 && !defaultPort) {
            own.append(':').append(port);
        }
        return own.toString();
    }

    /**
     * Reports the header name this instance requires, for the tests that measure the contract.
     *
     * @return the configured header name
     */
    String requiredHeader() {
        return this.requiredHeader;
    }
}

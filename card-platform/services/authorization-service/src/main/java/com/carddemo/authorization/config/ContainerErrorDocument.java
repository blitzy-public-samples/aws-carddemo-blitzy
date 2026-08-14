package com.carddemo.authorization.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import org.apache.catalina.Container;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Answers a request the container refused with the problem document this service publishes.
 *
 * <p>Two paths reach a caller without passing through {@code api/GlobalExceptionHandler}, and both
 * answered in a shape this service does not publish. A request target the firewall of the security
 * chain refuses — an encoded semicolon or an encoded percent sign — is answered by {@code
 * HttpServletResponse.sendError}, which the container turns into an error dispatch. The framework
 * renders that dispatch as {@code {"timestamp","status","error","path"}}, and the path member
 * copies the resolved request path into the body, where a request line here names the card being
 * authorized. A request target Tomcat itself refuses — an encoded slash, an encoded backslash or an
 * encoded null — reaches no mapped context at all, and {@code
 * org.apache.catalina.valves.ErrorReportValve} answers it with an HTML page from a route that
 * publishes JavaScript Object Notation alone.
 *
 * <p>This class answers both with the four members of RFC 9457, spelled exactly as {@link
 * SecurityConfig} spells them when it answers 401 and 403, and carrying the same media type. Those
 * two are the other failures raised before this service sees a request, so a caller that already
 * parses them parses this. {@code src/main/resources/openapi.yaml} publishes that schema under the
 * name {@code Problem}.
 *
 * <p>No member names anything the caller sent. There is no path member, no query string, no header
 * and no method: the document carries the status, the reason phrase of that status and one fixed
 * sentence. Every {@code server.error.include-*} key of {@code src/main/resources/application.yml}
 * is set to the value that reveals nothing, so the attributes behind any framework body carry
 * nothing either.
 *
 * <p>Three response headers travel with it, and they are the three the security chain sets on every
 * answer it reaches: {@code X-Content-Type-Options}, {@code X-Frame-Options} and {@code
 * Cache-Control}. A refusal that omitted them would be the one answer of this service a browser may
 * sniff, frame or cache.
 *
 * <p>ADDITIVE. {@code app/cbl/COTRN02C.cbl} reads fixed-width map fields from a 3270 screen and
 * {@code app/cbl/CBTRN02C.cbl} reads fixed-length records from a sequential file, so no request
 * target could be malformed and the source carries no refusal of one to reproduce.
 */
@Configuration(proxyBeanMethods = false)
public class ContainerErrorDocument {

    /**
     * The one sentence a refused request target carries.
     *
     * <p>The sentence {@code api/GlobalExceptionHandler} answers a protocol refusal with, because
     * this is the same class of failure: the request named a target this service does not serve. It
     * is repeated here rather than read from there because that constant is not visible outside its
     * own package, and {@code ContainerErrorDocumentTest} holds the two spellings together.
     */
    public static final String REFUSED_REQUEST_DETAIL =
            "This route does not serve the method or path this request named...";

    /** Title of a status this service cannot name, which no refusal of a request target reaches. */
    private static final String UNKNOWN_STATUS_TITLE = "Error";

    /**
     * Name of the header that stops a browser sniffing a body for a type other than the declared
     * one.
     *
     * <p>Named here because {@code org.springframework.http.HttpHeaders} declares neither this name
     * nor the framing one, and the security chain writes both from the framework's own header
     * writers rather than from constants this module could read.
     */
    static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** Value of {@link #CONTENT_TYPE_OPTIONS}, which pins the declared type. */
    static final String NO_SNIFF = "nosniff";

    /** Name of the header that stops a browser framing this answer. */
    static final String FRAME_OPTIONS = "X-Frame-Options";

    /** Value of {@link #FRAME_OPTIONS}, which refuses every framing origin. */
    static final String DENY_FRAMING = "DENY";

    /** Value of {@code Cache-Control}, which keeps a refusal out of every cache. */
    private static final String NO_STORE = "no-store";

    /** Records that a refusal was rendered, at a level a demo can leave on. */
    private static final Logger LOG = LoggerFactory.getLogger(ContainerErrorDocument.class);

    /**
     * Renders the document for one status.
     *
     * <p>Written by hand rather than through an object mapper, for the reason {@link
     * SecurityConfig} gives for the same choice: the body of a failure raised before this service
     * ran depends on no serializer configuration and can carry no member a mapper added. Both
     * callers below render through this one method, so the two paths cannot answer differently.
     *
     * @param status the status the container set
     * @return the problem document, as one line of JavaScript Object Notation
     */
    static String document(int status) {
        return "{\"type\":\"about:blank\",\"title\":\"" + title(status) + "\",\"status\":" + status
                + ",\"detail\":\"" + REFUSED_REQUEST_DETAIL + "\"}";
    }

    /**
     * Names the class of failure, which is the reason phrase of the status and never a caller
     * value.
     *
     * @param status the status the container set
     * @return the reason phrase, or a fixed word when the status resolves to none
     */
    private static String title(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? UNKNOWN_STATUS_TITLE : resolved.getReasonPhrase();
    }

    /**
     * Registers the writer of the error dispatch, for the refusals that reach a mapped context.
     *
     * <p>Registered for {@link DispatcherType#ERROR} alone, so it reads no request a caller made
     * and answers only a dispatch the container created. It runs before every other filter of that
     * dispatch and delegates to none of them, which is what keeps the framework's error controller
     * from writing a second shape after it.
     *
     * @return the registration, mapped over every path of the error dispatch
     */
    @Bean
    public FilterRegistrationBean<Filter> containerErrorDocumentFilter() {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new ErrorDispatchFilter());
        registration.setName("containerErrorDocumentFilter");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.ERROR));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Replaces the container's error-report valve, for the refusals that reach no mapped context.
     *
     * <p>Ordered last so it runs after the framework's own Tomcat customizer, which adds an {@link
     * ErrorReportValve} of its own with the report suppressed. Any valve of that type is removed
     * and this one takes its place, and the host is told to add no default at start-up, so exactly
     * one valve renders and it is this one.
     *
     * @return the customizer
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerErrorDocumentValve() {
        return factory -> factory.addContextCustomizers(context -> {
            Container host = context.getParent();
            if (host == null) {
                LOG.warn("No host carries this context, so the container refusal keeps its"
                        + " framework rendering");
                return;
            }
            Pipeline pipeline = host.getPipeline();
            for (Valve valve : pipeline.getValves()) {
                if (valve instanceof ErrorReportValve) {
                    pipeline.removeValve(valve);
                }
            }
            if (host instanceof StandardHost standardHost) {
                standardHost.setErrorReportValveClass("");
            }
            pipeline.addValve(new ProblemDocumentValve());
        });
    }

    /**
     * Writes the document over an error dispatch the container created.
     *
     * <p>The status is the one the container set before the dispatch, and nothing here reads the
     * request. A response another writer already committed is left alone, because the bytes it
     * wrote cannot be recalled.
     *
     * <p>Visible to its own package so {@code ContainerErrorDocumentTest} drives it directly,
     * rather than through a running container.
     */
    static final class ErrorDispatchFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            if (!(response instanceof HttpServletResponse http) || http.isCommitted()) {
                return;
            }
            int status = http.getStatus();
            LOG.info("Answering an authorization request the container refused, status {}", status);
            http.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            http.setCharacterEncoding(StandardCharsets.UTF_8.name());
            http.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
            http.setHeader(CONTENT_TYPE_OPTIONS, NO_SNIFF);
            http.setHeader(FRAME_OPTIONS, DENY_FRAMING);
            http.getWriter().write(document(status));
        }
    }

    /**
     * Writes the document for a request target refused before any context was mapped.
     *
     * <p>{@code report} is the one method of the superclass this overrides, so the ordering, commit
     * and asynchronous rules of the valve stay the container's. The reporter is used rather than
     * the writer, and the response is finished, because that is how the superclass writes its own
     * page: at this point no application holds the stream.
     *
     * <p>Two answers are left exactly as they are. One that already carries bytes is never
     * rewritten. One a route chose, which is a status set without {@code sendError} on a request
     * that reached a mapped context, is never given a body: a route that answers a status and no
     * body has published that, as the {@code 404} of {@code src/main/resources/openapi.yaml} does.
     * What remains is the request target the container refused, which reached no context at all,
     * and the failure something marked in error and left unwritten.
     *
     * <p>Visible to its own package so {@code ContainerErrorDocumentTest} drives it directly,
     * rather than through a running container.
     */
    static final class ProblemDocumentValve extends ErrorReportValve {

        /** Suppresses the inherited page, so nothing but the document below can be written. */
        ProblemDocumentValve() {
            setShowReport(false);
            setShowServerInfo(false);
        }

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int status = response.getStatus();
            if (status < HttpStatus.BAD_REQUEST.value() || response.getContentWritten() > 0) {
                return;
            }
            if (!response.isError() && request != null && request.getContext() != null) {
                return;
            }
            try {
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8);
                response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
                response.setHeader(CONTENT_TYPE_OPTIONS, NO_SNIFF);
                response.setHeader(FRAME_OPTIONS, DENY_FRAMING);
                PrintWriter reporter = response.getReporter();
                if (reporter == null) {
                    return;
                }
                reporter.write(document(status));
                response.finishResponse();
                LOG.info("Answering an authorization request target the container refused,"
                        + " status {}", status);
            } catch (IOException unwritable) {
                LOG.warn("An authorization request target the container refused could not be"
                        + " answered: {}", unwritable.getClass().getName());
            }
        }
    }
}

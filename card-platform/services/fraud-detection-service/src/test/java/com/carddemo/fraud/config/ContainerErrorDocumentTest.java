package com.carddemo.fraud.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.fraud.api.ApiProblem;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.apache.catalina.Context;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Measures what a caller reads when the container refuses a assessment request target.
 *
 * <p>Two paths reach a caller without passing through {@code api/FraudApiExceptionHandler}, and the
 * review found both answering in a shape this service does not publish: the error dispatch behind
 * {@code HttpServletResponse.sendError}, whose framework body copies the resolved request path into
 * a {@code path} member, and the container's own report valve, which answers a request target
 * Tomcat refused with an HTML page. An assessment path carries an account identifier, so the first
 * leaked a value the caller sent, and the second returned {@code text/html} from a route that
 * publishes JavaScript Object Notation alone.
 *
 * <p>Every assertion below reads one of the three seams the subject installs: the rendered
 * document, the registration and behaviour of the error-dispatch writer, and the valve that
 * replaces the container's reporter. The wire behaviour of the valve is measured against a stubbed
 * response rather than a running container, because a container start would prove one status on one
 * route while these prove each branch of the method.
 */
@DisplayName("ContainerErrorDocument, the assessment answer to a refused request target")
class ContainerErrorDocumentTest {

    /** A request target carrying an account identifier, as the review's own probe did. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The path the review submitted, with the encoded semicolon the security firewall refuses. */
    private static final String REFUSED_PATH = "/fraud-assessments/" + ACCOUNT_ID + "x%3By";

    /** Members a framework error body carries and this document may not. */
    private static final List<String> WITHHELD_MEMBERS =
            List.of("path", "query", "method", "uri", "timestamp", "trace", "stack_trace",
                    "exception", "errors", "message");

    @Nested
    @DisplayName("the rendered document")
    class TheDocument {

        @Test
        @DisplayName("carries the four members of RFC 9457 and nothing besides")
        void carriesTheFourMembersAndNothingBesides() {
            assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"" + ContainerErrorDocument.REFUSED_REQUEST_DETAIL
                            + "\"}",
                    ContainerErrorDocument.document(400));
        }

        @ParameterizedTest
        @CsvSource({
            "404,Not Found",
            "405,Method Not Allowed",
            "500,Internal Server Error",
        })
        @DisplayName("titles each status this service can name with its reason phrase")
        void titlesEachStatusWithItsReasonPhrase(int status, String phrase) {
            assertEquals("{\"type\":\"about:blank\",\"title\":\"" + phrase + "\",\"status\":"
                            + status + ",\"detail\":\""
                            + ContainerErrorDocument.REFUSED_REQUEST_DETAIL + "\"}",
                    ContainerErrorDocument.document(status));
        }

        @Test
        @DisplayName("titles a status this service cannot name with one fixed word")
        void titlesAStatusItCannotNameWithOneFixedWord() {
            assertTrue(ContainerErrorDocument.document(499).contains("\"title\":\"Error\""),
                    ContainerErrorDocument.document(499));
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 404, 405, 413, 500})
        @DisplayName("names no member that could carry a value the caller sent")
        void namesNoMemberThatCouldCarryACallerValue(int status) {
            String document = ContainerErrorDocument.document(status);

            for (String member : WITHHELD_MEMBERS) {
                assertFalse(document.contains("\"" + member + "\":"),
                        member + " must not be a member of " + document);
            }
        }

        @Test
        @DisplayName("spells the detail as the handler beside it spells the same refusal")
        void spellsTheDetailAsTheHandlerBesideItDoes() {
            assertEquals(ApiProblem.UNSUPPORTED_REQUEST,
                    ContainerErrorDocument.REFUSED_REQUEST_DETAIL,
                    "one refusal of a request target reads the same however it was raised");
        }
    }

    @Nested
    @DisplayName("the registration of the error-dispatch writer")
    class TheRegistration {

        private final FilterRegistrationBean<Filter> registration =
                new ContainerErrorDocument().containerErrorDocumentFilter();

        @Test
        @DisplayName("runs on the error dispatch alone, over every path, before every other filter")
        void runsOnTheErrorDispatchAloneBeforeEveryOtherFilter() {
            assertEquals("containerErrorDocumentFilter", this.registration.getFilterName());
            assertEquals(EnumSet.of(DispatcherType.ERROR),
                    this.registration.determineDispatcherTypes());
            assertEquals(List.of("/*"), List.copyOf(this.registration.getUrlPatterns()));
            assertEquals(Ordered.HIGHEST_PRECEDENCE, this.registration.getOrder());
        }

        @Test
        @DisplayName("registers the writer of this class and no other filter")
        void registersTheWriterOfThisClass() {
            assertInstanceOf(ContainerErrorDocument.ErrorDispatchFilter.class,
                    this.registration.getFilter());
        }
    }

    @Nested
    @DisplayName("the error dispatch the container creates")
    class TheErrorDispatch {

        private final ContainerErrorDocument.ErrorDispatchFilter filter =
                new ContainerErrorDocument.ErrorDispatchFilter();

        @ParameterizedTest
        @ValueSource(ints = {400, 404, 500})
        @DisplayName("writes the document, the media type and the three headers")
        void writesTheDocumentTheMediaTypeAndTheThreeHeaders(int status) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(status);
            MockFilterChain chain = new MockFilterChain();

            this.filter.doFilter(request(), response, chain);

            assertEquals(ContainerErrorDocument.document(status), response.getContentAsString());
            assertTrue(response.getContentType()
                            .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
                    response.getContentType());
            assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());
            assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
            assertEquals(ContainerErrorDocument.NO_SNIFF,
                    response.getHeader(ContainerErrorDocument.CONTENT_TYPE_OPTIONS));
            assertEquals(ContainerErrorDocument.DENY_FRAMING,
                    response.getHeader(ContainerErrorDocument.FRAME_OPTIONS));
            assertNull(chain.getRequest(), "the writer answers the dispatch and delegates to no"
                    + " filter that could write a second shape");
        }

        @Test
        @DisplayName("names nothing the caller sent, the account identifier included")
        void namesNothingTheCallerSent() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(400);

            this.filter.doFilter(request(), response, new MockFilterChain());

            assertFalse(response.getContentAsString().contains(ACCOUNT_ID),
                    response.getContentAsString());
            assertFalse(response.getContentAsString().contains(REFUSED_PATH),
                    response.getContentAsString());
        }

        @Test
        @DisplayName("leaves a response another writer already committed alone")
        void leavesACommittedResponseAlone() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(400);
            response.getWriter().write("{\"already\":\"written\"}");
            response.setCommitted(true);

            this.filter.doFilter(request(), response, new MockFilterChain());

            assertEquals("{\"already\":\"written\"}", response.getContentAsString());
            assertNull(response.getHeader(ContainerErrorDocument.CONTENT_TYPE_OPTIONS));
        }

        @Test
        @DisplayName("leaves a response that is not an HTTP one alone")
        void leavesANonHttpResponseAlone() {
            ServletResponse response = mock(ServletResponse.class);
            MockFilterChain chain = new MockFilterChain();

            assertDoesNotThrow(() -> this.filter.doFilter(request(), response, chain));
            assertNull(chain.getRequest());
        }

        private MockHttpServletRequest request() {
            return new MockHttpServletRequest("GET", REFUSED_PATH);
        }
    }

    @Nested
    @DisplayName("the valve that answers a target refused before a context was mapped")
    class TheValve {

        private final ContainerErrorDocument.ProblemDocumentValve valve =
                new ContainerErrorDocument.ProblemDocumentValve();

        @Test
        @DisplayName("is installed last, so it runs after the framework's own Tomcat customizer")
        void isInstalledLast() throws Exception {
            Order order = ContainerErrorDocument.class
                    .getDeclaredMethod("containerErrorDocumentValve").getAnnotation(Order.class);

            assertEquals(Ordered.LOWEST_PRECEDENCE, order.value());
        }

        @Test
        @DisplayName("leaves one reporter on the host, and it is not the container's own")
        void leavesOneReporterAndItIsNotTheContainersOwn() {
            StandardHost host = new StandardHost();
            host.getPipeline().addValve(new ErrorReportValve());
            StandardContext context = new StandardContext();
            context.setParent(host);

            customize(context);

            List<Valve> reporters = Arrays.stream(host.getPipeline().getValves())
                    .filter(ErrorReportValve.class::isInstance)
                    .toList();
            assertEquals(1, reporters.size(), reporters.toString());
            assertNotEquals(ErrorReportValve.class, reporters.get(0).getClass());
            assertInstanceOf(ContainerErrorDocument.ProblemDocumentValve.class, reporters.get(0));
            assertEquals("", host.getErrorReportValveClass(),
                    "the host must add no reporter of its own when it starts");
        }

        @Test
        @DisplayName("leaves a context that no host carries alone")
        void leavesAContextNoHostCarriesAlone() {
            assertDoesNotThrow(() -> customize(new StandardContext()));
        }

        @Test
        @DisplayName("writes no page of its own, so only the document can be written")
        void writesNoPageOfItsOwn() {
            assertFalse(this.valve.isShowReport());
            assertFalse(this.valve.isShowServerInfo());
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 404, 500})
        @DisplayName("writes the document, the media type and the three headers, then finishes")
        void writesTheDocumentTheMediaTypeAndTheThreeHeaders(int status) throws Exception {
            StringWriter written = new StringWriter();
            Response response = stubbedResponse(status, 0L, new PrintWriter(written));

            this.valve.report(null, response, null);

            assertEquals(ContainerErrorDocument.document(status), written.toString());
            verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            verify(response).setCharacterEncoding(StandardCharsets.UTF_8);
            verify(response).setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            verify(response).setHeader(ContainerErrorDocument.CONTENT_TYPE_OPTIONS,
                    ContainerErrorDocument.NO_SNIFF);
            verify(response).setHeader(ContainerErrorDocument.FRAME_OPTIONS,
                    ContainerErrorDocument.DENY_FRAMING);
            verify(response).finishResponse();
        }

        @Test
        @DisplayName("leaves an answer that already carries bytes alone")
        void leavesAnAnswerThatAlreadyCarriesBytesAlone() throws Exception {
            StringWriter written = new StringWriter();
            Response response = stubbedResponse(500, 1L, new PrintWriter(written));

            this.valve.report(null, response, null);

            assertEquals("", written.toString());
            verify(response, never()).setContentType(anyString());
            verify(response, never()).finishResponse();
        }

        @Test
        @DisplayName("leaves an answer that reports no failure alone")
        void leavesAnAnswerThatReportsNoFailureAlone() throws Exception {
            StringWriter written = new StringWriter();
            Response response = stubbedResponse(200, 0L, new PrintWriter(written));

            this.valve.report(null, response, null);

            assertEquals("", written.toString());
            verify(response, never()).setContentType(anyString());
            verify(response, never()).finishResponse();
        }

        @Test
        @DisplayName("finishes nothing when the container offers no reporter")
        void finishesNothingWhenThereIsNoReporter() throws Exception {
            Response response = stubbedResponse(400, 0L, null);

            this.valve.report(null, response, null);

            verify(response, never()).finishResponse();
        }

        @Test
        @DisplayName("leaves a status a route answered without a body alone")
        void leavesAStatusARouteAnsweredWithoutABodyAlone() throws Exception {
            StringWriter written = new StringWriter();
            Response response = stubbedResponse(404, 0L, new PrintWriter(written));
            when(response.isError()).thenReturn(false);

            this.valve.report(requestInsideAContext(), response, null);

            assertEquals("", written.toString(),
                    "a route that answers a status and no body has published that answer");
            verify(response, never()).setContentType(anyString());
            verify(response, never()).finishResponse();
        }

        @Test
        @DisplayName("answers a target that reached no context, marked in error or not")
        void answersATargetThatReachedNoContext() throws Exception {
            StringWriter written = new StringWriter();
            Response response = stubbedResponse(400, 0L, new PrintWriter(written));
            when(response.isError()).thenReturn(false);
            Request unmapped = mock(Request.class);
            when(unmapped.getContext()).thenReturn(null);

            this.valve.report(unmapped, response, null);

            assertEquals(ContainerErrorDocument.document(400), written.toString(),
                    "an encoded slash, backslash or null reaches no context, and no route chose"
                            + " this status");
            verify(response).finishResponse();
        }

        @Test
        @DisplayName("raises nothing when the reporter cannot be reached")
        void raisesNothingWhenTheReporterCannotBeReached() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(400);
            when(response.getContentWritten()).thenReturn(0L);
            when(response.isError()).thenReturn(true);
            when(response.getReporter()).thenThrow(new IOException("no reporter"));

            assertDoesNotThrow(() -> this.valve.report(null, response, null));
        }

        private void customize(StandardContext context) {
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
            WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer =
                    new ContainerErrorDocument().containerErrorDocumentValve();

            customizer.customize(factory);

            List<TomcatContextCustomizer> customizers =
                    List.copyOf(factory.getContextCustomizers());
            assertEquals(1, customizers.size(), customizers.toString());
            customizers.forEach(each -> each.customize(context));
        }

        private Response stubbedResponse(int status, long written, PrintWriter reporter)
                throws IOException {

            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(status);
            when(response.getContentWritten()).thenReturn(written);
            when(response.getReporter()).thenReturn(reporter);
            when(response.isError()).thenReturn(true);
            return response;
        }

        private Request requestInsideAContext() {
            Request request = mock(Request.class);
            when(request.getContext()).thenReturn(mock(Context.class));
            return request;
        }
    }
}

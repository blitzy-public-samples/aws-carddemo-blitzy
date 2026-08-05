package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.api.dto.AccountUpdateRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

/**
 * Tests for {@link AccountApiExceptionHandler} and {@link ApiProblem}.
 *
 * <p>What this class protects is the promise the error body makes: it carries the verbatim texts the
 * source emits and nothing a caller sent. A handler that let a rejected value through would reflect a
 * Social Security number or a date of birth back to whoever sent it and into every access log on the
 * way, which is a disclosure created by an error path rather than by a feature.
 *
 * <p>The handler replaces the abend routine at {@code app/cbl/CBTRN02C.cbl:L707-L711}, which displayed
 * one message, moved 999 into an abend code and performed no cleanup, from more than twenty call sites.
 * Four status codes replace that one outcome.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the account error body")
class AccountApiExceptionHandlerTest {

    /** The subject. */
    private final AccountApiExceptionHandler handler = new AccountApiExceptionHandler();

    /** The four codes the handler answers, and the shape each body carries. */
    @Nested
    @DisplayName("status codes and shapes")
    class StatusCodesAndShapes {

        /**
         * Asserts a body whose fields failed answers 422 carrying one text per failing field.
         *
         * <p>422 rather than 400, because the body was read and its values are not ones the source
         * accepts. A caller reading 400 would look for a syntax mistake it did not make.
         */
        @Test
        void aFailedFieldEditAnswersUnprocessableWithItsTexts() throws Exception {
            ResponseEntity<ApiProblem> response = handler.onInvalidBody(
                    invalidBody("FICO Score: should be between 300 and 850",
                            "Address Line 1 must be supplied."));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "the body was read and its values were refused");
            ApiProblem problem = response.getBody();
            assertNotNull(problem, "a refusal carries a body");
            assertEquals(ApiProblem.VALIDATION_FAILED, problem.title(), "one fixed title");
            assertEquals(List.of("Address Line 1 must be supplied.",
                            "FICO Score: should be between 300 and 850"),
                    problem.messages(),
                    "sorted, so one request reads the same list however the framework ordered it");
        }

        /**
         * Asserts a body that could not be read answers 400 and carries no field text.
         *
         * <p>The parser's own message quotes the text it failed on, which can hold a date of birth, so
         * the body carries a fixed text instead.
         */
        @Test
        void anUnreadableBodyAnswersBadRequestAndQuotesNothing() {
            ResponseEntity<ApiProblem> response = handler.onUnreadableBody(
                    new HttpMessageNotReadableException("unexpected character at 429-54-1163",
                            (org.springframework.http.HttpInputMessage) null));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "nothing could be read, so nothing could be edited");
            ApiProblem problem = response.getBody();
            assertNotNull(problem, "a refusal carries a body");
            assertEquals(ApiProblem.MALFORMED_REQUEST, problem.title(), "one fixed title");
            assertFalse(problem.detail().contains("429-54-1163"),
                    "the parser's own message, which quoted a value, reaches no caller");
            assertNull(problem.messages(), "no field failed, so no field text is carried");
        }

        /** Asserts a constraint failure answers 422 carrying its texts. */
        @Test
        void aConstraintFailureAnswersUnprocessable() {
            ResponseEntity<ApiProblem> response = handler.onConstraintViolation(
                    new ConstraintViolationException("two constraints", Set.of()));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "a constraint is an edit by another name");
            assertEquals(List.of(ApiProblem.VALIDATION_FAILED),
                    response.getBody().messages(),
                    "a failure carrying no text of its own still says a field failed");
        }

        /** Asserts a rejected value answers 422 and carries no field text of its own. */
        @Test
        void aRejectedValueAnswersUnprocessable() {
            ResponseEntity<ApiProblem> response = handler.onRejectedValue(
                    new IllegalArgumentException("accountId holds 10 characters and ACCT-ID holds 11"));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "a width a column cannot hold is a value the caller can correct");
            assertNull(response.getBody().messages(),
                    "the exception message names a component and is logged rather than returned");
        }

        /**
         * Asserts any other fault answers 500 and reveals nothing about itself.
         *
         * <p>A message raised deep in a driver or a parser can quote a value this service holds, so the
         * exception class reaches the log and the message reaches nothing.
         */
        @Test
        void anyOtherFaultAnswersInternalFailureAndRevealsNothing() {
            ResponseEntity<ApiProblem> response = handler.onInternalFailure(
                    new IllegalStateException("connection failed for user carddemo password hunter2"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                    "a fault this service could not classify");
            ApiProblem problem = response.getBody();
            assertEquals(ApiProblem.INTERNAL_FAILURE, problem.title(), "one fixed title");
            assertFalse(problem.detail().contains("hunter2"),
                    "the exception's own message reaches no caller");
            assertTrue(problem.detail().contains("Nothing was changed"),
                    "the caller is told the request had no effect, which is what it needs to decide"
                            + " whether to retry");
        }

        /**
         * Asserts every body is typed as a problem document.
         *
         * <p>{@code config/SecurityConfig} already writes {@code application/problem+json} for 401 and
         * 403. One service answering two shapes of error would make a client parse both.
         */
        @Test
        void everyBodyIsTypedAsAProblemDocument() throws Exception {
            List<ResponseEntity<ApiProblem>> answers = List.of(
                    handler.onInvalidBody(invalidBody("one text")),
                    handler.onUnreadableBody(new HttpMessageNotReadableException("broken",
                            (org.springframework.http.HttpInputMessage) null)),
                    handler.onConstraintViolation(
                            new ConstraintViolationException("none", Set.of())),
                    handler.onRejectedValue(new IllegalArgumentException("a component")),
                    handler.onInternalFailure(new IllegalStateException("a fault")));

            for (ResponseEntity<ApiProblem> answer : answers) {
                assertEquals(MediaType.APPLICATION_PROBLEM_JSON,
                        answer.getHeaders().getContentType(),
                        "one shape of error for the whole service");
                assertEquals(ApiProblem.ABOUT_BLANK, answer.getBody().type(),
                        "the status code carries the semantics, so the type is the default one");
            }
        }
    }

    /** Invariants of the body record itself. */
    @Nested
    @DisplayName("the problem record")
    class TheProblemRecord {

        /**
         * Asserts an empty message list is refused rather than stored.
         *
         * <p>A document carrying an empty {@code messages} member says a field failed and declines to
         * say which, which is worse than omitting the member.
         */
        @Test
        void anEmptyMessageListIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> ApiProblem.of(422, ApiProblem.VALIDATION_FAILED,
                            ApiProblem.VALIDATION_FAILED_DETAIL, List.of()),
                    "an empty list says nothing and looks like it should say something");

            assertTrue(refused.getMessage().contains("messages"),
                    "the refusal names the member, and it reads: " + refused.getMessage());
        }

        /** Asserts the three standard members are required. */
        @Test
        void theThreeStandardMembersAreRequired() {
            assertThrows(NullPointerException.class,
                    () -> new ApiProblem(null, ApiProblem.NOT_FOUND, 404, "detail", null),
                    "the type is required");
            assertThrows(NullPointerException.class,
                    () -> new ApiProblem(ApiProblem.ABOUT_BLANK, null, 404, "detail", null),
                    "the title is required");
            assertThrows(NullPointerException.class,
                    () -> new ApiProblem(ApiProblem.ABOUT_BLANK, ApiProblem.NOT_FOUND, 404, null,
                            null),
                    "the detail is required");
        }

        /** Asserts the message list is copied, so a caller cannot alter a built document. */
        @Test
        void theMessageListIsCopied() {
            List<String> mutable = new java.util.ArrayList<>(List.of("one text"));
            ApiProblem problem = ApiProblem.of(422, ApiProblem.VALIDATION_FAILED,
                    ApiProblem.VALIDATION_FAILED_DETAIL, mutable);

            mutable.add("a text the caller added afterwards");

            assertEquals(List.of("one text"), problem.messages(),
                    "the document carries what it was built with");
            assertThrows(UnsupportedOperationException.class,
                    () -> problem.messages().add("another"), "and refuses to be added to");
        }

        /**
         * Asserts the rendering names every member and discloses no message text.
         *
         * <p>A message produced by an edit can quote the label of a field a caller supplied, and this
         * record reaches a log line whenever a handler is diagnosed. The count stays because it is
         * useful; the texts do not because they are not this record's to disclose twice.
         */
        @Test
        void theRenderingDisclosesNoMessageText() {
            String rendered = ApiProblem.of(422, ApiProblem.VALIDATION_FAILED,
                            ApiProblem.VALIDATION_FAILED_DETAIL,
                            List.of("FICO Score: should be between 300 and 850"))
                    .toString();

            assertFalse(rendered.contains("FICO"), "no message text reaches a rendering");
            assertTrue(rendered.contains("messages=1"), "the count does, because it is useful");
            assertTrue(rendered.contains(ApiProblem.VALIDATION_FAILED),
                    "and so does the title, which is a constant of this record");
        }

        /** Asserts a document with no messages renders that fact rather than a count. */
        @Test
        void aDocumentWithNoMessagesRendersThatFact() {
            String rendered =
                    ApiProblem.of(404, ApiProblem.NOT_FOUND, ApiProblem.NOT_FOUND_DETAIL).toString();

            assertTrue(rendered.contains("messages=<absent>"),
                    "an absent member reads as absent rather than as zero");
        }
    }

    /**
     * Builds one binding failure carrying the field texts given.
     *
     * @param messages the texts the failing fields produced
     * @return the failure a controller method would have thrown
     * @throws NoSuchMethodException never, unless this test class is renamed without its helper
     */
    private static MethodArgumentNotValidException invalidBody(String... messages)
            throws NoSuchMethodException {
        BindingResult binding =
                new BeanPropertyBindingResult(null, "accountUpdateRequest");
        for (int index = 0; index < messages.length; index++) {
            binding.rejectValue(null, "code" + index, messages[index]);
        }
        MethodParameter parameter = new MethodParameter(
                AccountApiExceptionHandlerTest.class.getDeclaredMethod("boundParameter",
                        AccountUpdateRequest.class), 0);
        return new MethodArgumentNotValidException(parameter, binding);
    }

    /**
     * A method whose one parameter stands in for a bound request body.
     *
     * <p>{@link MethodArgumentNotValidException} requires a method parameter to describe the binding
     * that failed, and this is the smallest thing that satisfies it.
     *
     * @param request the bound body, never read
     */
    @SuppressWarnings("unused")
    private void boundParameter(@Autowired AccountUpdateRequest request) {
        // A signature this test reflects over. It is never invoked.
    }
}

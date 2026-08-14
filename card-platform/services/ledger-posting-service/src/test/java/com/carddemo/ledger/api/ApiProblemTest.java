package com.carddemo.ledger.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the error body of this service carries fixed text and nothing a caller sent.
 *
 * <p>{@link LedgerApiExceptionHandlerTest} asserts which status each failure answers. This class
 * asserts the properties of the document itself, which hold wherever it is built: every member is
 * present, the type is the one this service writes, and no detail names a value, a route, a table, a
 * column or an exception class.
 *
 * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl} answers a failure by abending at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} and returns nothing to a caller, because a batch program has
 * none. There is no source text for these details to reproduce, so the property under test is that
 * they stay fixed rather than that they match a literal.
 */
@DisplayName("The problem document of the balance query")
final class ApiProblemTest {

    /** Every detail text this record declares. */
    private static final List<String> DETAILS = List.of(
            ApiProblem.INVALID_ACCOUNT_ID,
            ApiProblem.BALANCE_DEPENDENCY_UNAVAILABLE,
            ApiProblem.BALANCE_NOT_READ);

    /** Asserts the factory fills the type every document carries. */
    @Test
    @DisplayName("the factory carries the one problem type and the arguments given")
    void theFactoryCarriesTheOneProblemType() {
        ApiProblem built = ApiProblem.of(ApiProblem.BAD_REQUEST, 400, ApiProblem.INVALID_ACCOUNT_ID);

        assertAll(
                () -> assertEquals("about:blank", built.type(),
                        "the type is the one this service writes"),
                () -> assertEquals(ApiProblem.ABOUT_BLANK, built.type(),
                        "the constant and the literal agree"),
                () -> assertEquals(ApiProblem.BAD_REQUEST, built.title(), "the title given"),
                () -> assertEquals(400, built.status(), "the status given"),
                () -> assertEquals(ApiProblem.INVALID_ACCOUNT_ID, built.detail(),
                        "the detail given"));
    }

    /** Asserts a document cannot be built without one of its text members. */
    @Test
    @DisplayName("a missing text member is refused rather than serialized as null")
    void aMissingTextMemberIsRefused() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new ApiProblem(null, ApiProblem.BAD_REQUEST, 400, "detail"),
                        "the type is required"),
                () -> assertThrows(NullPointerException.class,
                        () -> new ApiProblem(ApiProblem.ABOUT_BLANK, null, 400, "detail"),
                        "the title is required"),
                () -> assertThrows(NullPointerException.class,
                        () -> new ApiProblem(ApiProblem.ABOUT_BLANK, ApiProblem.BAD_REQUEST, 400,
                                null),
                        "the detail is required"));
    }

    /**
     * Asserts the three titles are the phrases the status codes name.
     *
     * <p>{@code config/SecurityConfig} writes this same shape for {@code 401} and {@code 403}, and a
     * caller reading one service should not have to learn two spellings of the same phrase.
     */
    @Test
    @DisplayName("each title is the standard phrase of the status it accompanies")
    void eachTitleIsTheStandardPhrase() {
        assertAll(
                () -> assertEquals("Bad Request", ApiProblem.BAD_REQUEST, "400"),
                () -> assertEquals("Service Unavailable", ApiProblem.SERVICE_UNAVAILABLE, "503"),
                () -> assertEquals("Internal Server Error", ApiProblem.INTERNAL_SERVER_ERROR,
                        "500"));
    }

    /**
     * Asserts no detail names an implementation value.
     *
     * <p>The framework body this record replaced carried the resolved request path. These texts carry
     * no path, no table, no column, no exception class and no digit of an account identifier.
     */
    @Test
    @DisplayName("no detail names a route, a table, a column, an exception class or an identifier")
    void noDetailNamesAnImplementationValue() {
        for (String detail : DETAILS) {
            assertAll(
                    () -> assertFalse(detail.contains("/"),
                            "no route or path separator: " + detail),
                    () -> assertFalse(detail.contains("_"),
                            "no table or column name: " + detail),
                    () -> assertFalse(detail.contains("Exception"),
                            "no exception class: " + detail),
                    () -> assertFalse(detail.matches(".*\\d.*"),
                            "no digit, so no identifier and no status: " + detail),
                    () -> assertTrue(detail.endsWith("."),
                            "each detail is a sentence: " + detail));
        }
    }

    /**
     * Asserts the two failure details stay distinct.
     *
     * <p>They exist because they call for different actions. A dependency being away is worth
     * retrying; a fault inside this service is not, and one text serving both would tell a caller to
     * repeat something that cannot succeed.
     */
    @Test
    @DisplayName("the unavailability detail invites a retry and the fault detail does not")
    void theTwoFailureDetailsStayDistinct() {
        assertAll(
                () -> assertTrue(ApiProblem.BALANCE_DEPENDENCY_UNAVAILABLE.contains("Retry"),
                        "an away dependency invites a retry"),
                () -> assertFalse(ApiProblem.BALANCE_NOT_READ.contains("Retry"),
                        "a fault inside this service does not"),
                () -> assertEquals(DETAILS.size(), DETAILS.stream().distinct().count(),
                        "the three details are three texts"));
    }
}

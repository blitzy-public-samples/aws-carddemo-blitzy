package com.carddemo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialization contract tests for {@link ErrorResponse}, guarding QA FINAL_ALT
 * <strong>Issue 5</strong> (information disclosure): the standardized error envelope must NOT leak the
 * resolved request {@code path} back to the caller, while the {@code path} component is still retained
 * in-process (for logging, correlation, and the {@code GlobalExceptionHandler} object-level tests).
 *
 * <p>These are pure Jackson unit tests over the canonical {@link ObjectMapper} configuration the API
 * uses for serialization (the JSR-310 module enables {@code LocalDateTime} timestamp output). They
 * assert the <em>wire</em> shape directly, complementing the runtime {@code curl} re-verification.</p>
 */
class ErrorResponseSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Mirror the production serialization stack: JavaTimeModule for LocalDateTime, and the
        // record's own @JsonInclude(NON_NULL) drives null-omission for fieldErrors.
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Serialized error JSON omits the @JsonIgnore'd path component (Issue 5)")
    void serializedError_doesNotExposePath() throws Exception {
        ErrorResponse error = ErrorResponse.of(404, "Not Found",
                "Account not found with id: 999999", "/accounts/999999");

        String json = objectMapper.writeValueAsString(error);
        JsonNode node = objectMapper.readTree(json);

        // The request path must NOT appear anywhere in the outbound envelope.
        assertThat(node.has("path")).as("error JSON must not contain a 'path' field").isFalse();
        assertThat(json).doesNotContain("/accounts/999999");
        assertThat(json).doesNotContain("\"path\"");

        // The legitimate, client-facing fields remain present.
        assertThat(node.get("status").asInt()).isEqualTo(404);
        assertThat(node.get("error").asText()).isEqualTo("Not Found");
        assertThat(node.get("message").asText()).isEqualTo("Account not found with id: 999999");
        assertThat(node.has("timestamp")).isTrue();

        // The path is still retained on the in-process object for server-side use / handler tests.
        assertThat(error.path()).isEqualTo("/accounts/999999");
    }

    @Test
    @DisplayName("Validation error JSON keeps fieldErrors but still omits path (Issue 5)")
    void validationError_keepsFieldErrors_butOmitsPath() throws Exception {
        ErrorResponse error = ErrorResponse.of(400, "Bad Request", "Validation failed",
                "/transactions",
                List.of(new ErrorResponse.FieldErrorDetail("amount", "must be a valid decimal")));

        String json = objectMapper.writeValueAsString(error);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("path")).as("validation error JSON must not contain 'path'").isFalse();
        assertThat(json).doesNotContain("/transactions");

        // fieldErrors must survive (the value of this contract for 400s).
        assertThat(node.has("fieldErrors")).isTrue();
        assertThat(node.get("fieldErrors")).hasSize(1);
        assertThat(node.get("fieldErrors").get(0).get("field").asText()).isEqualTo("amount");

        // path retained on the object.
        assertThat(error.path()).isEqualTo("/transactions");
    }
}

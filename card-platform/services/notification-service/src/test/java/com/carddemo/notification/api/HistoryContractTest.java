package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.domain.NotificationRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests binding the shipped history endpoint description to the shipped schema migration.
 *
 * <p>The two files have to agree about how a card is named. A masked card value cannot name one
 * card, since two cards sharing their last four digits share one masked form, and a full Primary
 * Account Number (PAN) in a route lands in every access log and proxy on the way.
 *
 * <p>These tests read the two shipped files and assert that neither drifts back. The account
 * pattern agrees across the route and migration. The masked form appears only as a display value,
 * and no Primary Account Number (PAN) pattern survives anywhere.
 *
 * <p>The paging assertions cover the second half. The endpoint bounds what one call returns, and the
 * ceiling equals {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS}. That equality is the point: a
 * caller cannot ask for more rows than a renderer will accept.
 *
 * <p>Reading the files rather than a running application keeps these assertions in the unit test
 * phase, which is where every other test of this module runs.
 */
@DisplayName("Shipped history contract of the notification service")
class HistoryContractTest {

    /** Classpath location of the shipped interface description. */
    private static final String OPENAPI = "/openapi.yaml";

    /** Classpath location of the shipped schema migration. */
    private static final String SCHEMA_MIGRATION = "/db/migration/V1__schema.sql";

    /** The only path the description declares. */
    private static final String HISTORY_PATH = "/notifications/{cardToken}";

    /** Name of the one path parameter, and the identity property of the response envelope. */
    private static final String TOKEN = "cardToken";

    /** The one query parameter the route declares, named for the bound it carries. */
    private static final String PAGE_SIZE = NotificationHistoryController.PAGE_SIZE_PARAMETER;

    /** Pattern a card token matches, in both shipped files and in the controller. */
    private static final String TOKEN_PATTERN = PanMasker.CARD_TOKEN_PATTERN;

    /** Characters a card token spans. */
    private static final int TOKEN_LENGTH = PanMasker.CARD_TOKEN_LENGTH;

    /** Characters the masked display value spans, the width {@code CHAR(16)} declares. */
    private static final int MASKED_LENGTH = PicClause.TRAN_CARD_NUM_WIDTH;

    /** Pattern the masked display value matches in the migration. */
    private static final String MASKED_PATTERN = "^\\*{12}[0-9]{4}$";

    /** Pattern the masked display value matches in the response, which admits a fully masked one. */
    private static final String RESPONSE_MASKED_PATTERN = "^\\*{12}([0-9]{4}|\\*{4})$";

    /** The pattern a full sixteen-digit card number would match, which must appear nowhere. */
    private static final String PAN_PATTERN = "^[0-9]{16}$";

    /** The shipped interface description, character for character. */
    private static String rawOpenapi;

    /** The shipped interface description, parsed. */
    private static Map<String, Object> openapi;

    /** The shipped schema migration, character for character. */
    private static String rawMigration;

    @BeforeAll
    static void readShippedFiles() {
        rawOpenapi = readClasspathResource(OPENAPI);
        openapi = parseYaml(rawOpenapi);
        rawMigration = readClasspathResource(SCHEMA_MIGRATION);
    }

    @Test
    @DisplayName("The only path takes an opaque card token")
    void onlyPathTakesTheCardToken() {
        assertThat(paths().keySet()).containsExactly(HISTORY_PATH);
    }

    @Test
    @DisplayName("The path parameter is a card token with the pattern the migration enforces")
    void pathParameterIsACardToken() {
        Map<String, Object> parameter = parameter(TOKEN);
        assertThat(parameter.get("in")).isEqualTo("path");
        assertThat(parameter.get("required")).isEqualTo(true);

        Map<String, Object> schema = schemaOf(parameter);
        assertThat(schema.get("type")).isEqualTo("string");
        assertThat(schema.get("pattern")).isEqualTo(TOKEN_PATTERN);
        assertThat(schema.get("minLength")).isEqualTo(TOKEN_LENGTH);
        assertThat(schema.get("maxLength")).isEqualTo(TOKEN_LENGTH);
    }

    @Test
    @DisplayName("No request carries a card number of either form")
    void noRequestCarriesACardNumber() {
        assertThat(parameterNames())
                .as("a masked value identifies no single card and a full one belongs in no route")
                .containsExactly(TOKEN, "pageSize")
                .doesNotContain("cardNumber", "maskedCardNumber");
        assertThat(schemaOf(parameter(TOKEN)).get("pattern"))
                .as("the one request value is a token")
                .isNotEqualTo(MASKED_PATTERN);
    }

    @Test
    @DisplayName("The controller enforces the pattern the description declares")
    void theControllerEnforcesTheDeclaredPattern() {
        assertThat(NotificationHistoryController.CARD_TOKEN_PATTERN).isEqualTo(TOKEN_PATTERN);
        assertThat(NotificationHistoryController.ROUTE_TEMPLATE).isEqualTo(HISTORY_PATH);
    }

    @Test
    @DisplayName("No shipped file carries a full card number pattern")
    void noShippedFileCarriesAPanPattern() {
        assertThat(rawOpenapi)
                .as("a route or a schema still describes sixteen numeric digits")
                .doesNotContain(PAN_PATTERN);
        assertThat(rawMigration)
                .as("the migration still admits sixteen numeric digits somewhere")
                .doesNotContain(PAN_PATTERN);
    }

    @Test
    @DisplayName("The migration keys the read model on the card token")
    void migrationKeysOnTheCardToken() {
        assertThat(rawMigration)
                .contains("card_token           CHAR(64)      NOT NULL")
                .contains("PRIMARY KEY (card_token, transaction_id)")
                .contains("CONSTRAINT ck_statement_transaction_card_token");
        assertThat(rawMigration)
                .as("the key half is a token, and no card number of either form matches its shape")
                .contains("CHECK (card_token ~ '" + TOKEN_PATTERN + "')");
        assertThat(rawMigration)
                .as("no key column of the read model is a card number")
                .doesNotContain("PRIMARY KEY (card_number, transaction_id)");
    }

    @Test
    @DisplayName("Every card number the migration stores is masked and keys nothing")
    void everyStoredCardNumberIsMaskedAndKeysNothing() {
        assertThat(rawMigration)
                .as("the read model carries the masked form as a display column")
                .contains("masked_card_number   CHAR(16)      NOT NULL")
                .contains("CONSTRAINT ck_statement_transaction_masked_card_number");
        assertThat(rawMigration)
                .contains("CONSTRAINT ck_notification_log_card_token")
                .contains("CONSTRAINT ck_notification_log_card_number")
                .contains("ix_notification_log_card_token");
        assertThat(countOccurrences(rawMigration, "CHECK (masked_card_number ~ '"
                        + MASKED_PATTERN + "')"))
                .as("both display columns are held to the masked form")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("The response carries the token as identity and the masked number as display data")
    void responseCarriesTheTokenAsIdentityAndTheMaskedNumberAsDisplayData() {
        Map<String, Object> history = historySchema();
        Map<String, Object> properties = nested(history, "properties");

        assertThat(asStrings(history.get("required")))
                .as("the masked form is absent when no row reports one, so it is not required")
                .containsExactly(TOKEN, "transactionCount", "totalAmount", "transactions");
        assertThat(properties.keySet())
                .containsExactly(TOKEN, "cardNumber", "transactionCount", "totalAmount",
                        "transactions");
        assertThat(nested(properties, TOKEN).get("pattern")).isEqualTo(TOKEN_PATTERN);
        assertThat(nested(properties, "cardNumber").get("pattern")).isEqualTo(MASKED_PATTERN);
        assertThat(nested(properties, "cardNumber").get("maxLength")).isEqualTo(MASKED_LENGTH);
    }

    @Test
    @DisplayName("The response bounds its array and its count at the renderer ceiling")
    void responseBoundsItsArrayAndItsCount() {
        Map<String, Object> properties = nested(historySchema(), "properties");
        int ceiling = NotificationRenderer.MAXIMUM_STATEMENT_ROWS;

        assertThat(nested(properties, "transactions").get("maxItems"))
                .as("a caller cannot receive more rows than a renderer will accept")
                .isEqualTo(ceiling);
        assertThat(nested(properties, "transactionCount").get("maximum"))
                .as("the count carries the same ceiling as the array it counts")
                .isEqualTo(ceiling);
    }

    @Test
    @DisplayName("The endpoint declares no offset paging, which a concurrent insert would shift")
    void endpointDeclaresNoOffsetPaging() {
        assertThat(parameterNames())
                .as("an offset lets a concurrent insert shift a page already read")
                .doesNotContain("offset", "page", "pageNumber");
        assertThat(parameterNames()).containsExactly(TOKEN, PAGE_SIZE);
        assertThat(schemaOf(parameter(PAGE_SIZE)).get("minimum")).isEqualTo(1);
    }

    @Test
    @DisplayName("The endpoint bounds one response at the ceiling the renderer accepts")
    void endpointBoundsOneResponseAtTheRendererCeiling() {
        Map<String, Object> pageSize = parameter(PAGE_SIZE);
        assertThat(pageSize.get("in")).isEqualTo("query");
        assertThat(pageSize.get("required")).isEqualTo(false);

        Map<String, Object> schema = schemaOf(pageSize);
        assertThat(schema.get("type")).isEqualTo("integer");
        assertThat(schema.get("minimum")).isEqualTo(1);
        assertThat(schema.get("maximum"))
                .as("a caller cannot ask for more rows than a renderer will accept")
                .isEqualTo(NotificationRenderer.MAXIMUM_STATEMENT_ROWS);
    }

    @Test
    @DisplayName("The rendered alert carries its own row ceiling, whatever the response returns")
    void theRenderedAlertCarriesItsOwnCeiling() {
        assertThat(NotificationRenderer.MAXIMUM_STATEMENT_ROWS)
                .as("a rendered alert is bounded, so one card cannot produce an unbounded message")
                .isPositive();
    }

    @Test
    @DisplayName("The migration carries an index for each retention delete")
    void migrationCarriesRetentionIndexes() {
        assertThat(rawMigration)
                .contains("ix_statement_transaction_processing_timestamp")
                .contains("ix_notification_log_attempted_at")
                .contains("ix_cardholder_context_observed_at")
                .contains("ix_processed_event_processed_at");
    }

    /**
     * Returns the declared paths.
     *
     * @return the path map
     */
    private static Map<String, Object> paths() {
        return nested(openapi, "paths");
    }

    /**
     * Returns the parameters the history operation declares.
     *
     * @return one map per parameter
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters() {
        Map<String, Object> operation = nested(nested(paths(), HISTORY_PATH), "get");
        return (List<Map<String, Object>>) operation.get("parameters");
    }

    /**
     * Returns the names of every declared parameter.
     *
     * @return the parameter names
     */
    private static List<String> parameterNames() {
        return parameters().stream().map(parameter -> (String) parameter.get("name")).toList();
    }

    /**
     * Returns one declared parameter by name.
     *
     * @param name the parameter name
     * @return the parameter
     * @throws AssertionError when the operation declares no such parameter
     */
    private static Map<String, Object> parameter(String name) {
        return parameters().stream()
                .filter(parameter -> name.equals(parameter.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the history operation declares no parameter named " + name));
    }

    /**
     * Returns the schema of one parameter.
     *
     * @param parameter the parameter
     * @return its schema
     */
    private static Map<String, Object> schemaOf(Map<String, Object> parameter) {
        return nested(parameter, "schema");
    }

    /**
     * Returns the response schema of the history endpoint.
     *
     * @return the {@code NotificationHistory} schema
     */
    private static Map<String, Object> historySchema() {
        return nested(nested(nested(openapi, "components"), "schemas"), "NotificationHistory");
    }

    /**
     * Returns one nested mapping.
     *
     * @param parent the enclosing mapping
     * @param key    the key to read
     * @return the nested mapping
     * @throws AssertionError when the key is absent or is not a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new AssertionError("the shipped description carries no mapping at " + key);
        }
        return (Map<String, Object>) value;
    }

    /**
     * Returns one sequence of strings.
     *
     * @param value the parsed sequence
     * @return the strings it holds
     */
    @SuppressWarnings("unchecked")
    private static List<String> asStrings(Object value) {
        return (List<String>) value;
    }

    /**
     * Counts how many times one value occurs in one text.
     *
     * @param text  the text to search
     * @param value the value to count
     * @return the number of occurrences
     */
    private static int countOccurrences(String text, String value) {
        int count = 0;
        for (int from = text.indexOf(value); from >= 0; from = text.indexOf(value, from + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Reads one shipped resource from the classpath.
     *
     * @param location absolute classpath location
     * @return the resource, character for character
     * @throws AssertionError when the module ships no such resource or it cannot be read
     */
    private static String readClasspathResource(String location) {
        try (InputStream stream = HistoryContractTest.class.getResourceAsStream(location)) {
            if (stream == null) {
                throw new AssertionError("this module ships no resource at " + location);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new AssertionError("the resource at " + location + " could not be read",
                    unreadable);
        }
    }

    /**
     * Parses one document.
     *
     * @param document the document text
     * @return the parsed mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String document) {
        return (Map<String, Object>) new Yaml(new LoaderOptions()).load(document);
    }
}

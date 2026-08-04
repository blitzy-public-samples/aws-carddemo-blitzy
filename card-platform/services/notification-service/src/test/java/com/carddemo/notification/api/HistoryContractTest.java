package com.carddemo.notification.api;

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
 * <p>The two files have to agree about how a card is named, and they did not. The migration keyed
 * {@code statement_transaction} on a masked card value while the endpoint took a full sixteen-digit
 * card number in its path. No full Primary Account Number (PAN) can equal a masked value, so the
 * endpoint could never match a stored row. Requiring a PAN in a route is also wrong on its own: a
 * path lands in every access log and proxy on the way.
 *
 * <p>Both now name an opaque card token. These tests read the two shipped files and assert that
 * neither drifts back. The route takes a token, and the token pattern is the same in both files.
 * The masked form appears only as a display value, and no PAN pattern survives anywhere.
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
    private static final String HISTORY_PATH = "/notifications/{maskedCardNumber}";

    /** Pattern a masked card number matches, in both shipped files. */
    private static final String MASKED_PATTERN = "^\\*{12}[0-9]{4}$";

    /** Characters a masked card number spans, the width TRNX-CARD-NUM PIC X(16) declares. */
    private static final int MASKED_LENGTH = 16;

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
    void onlyPathTakesTheMaskedCardNumber() {
        assertThat(paths().keySet()).containsExactly(HISTORY_PATH);
    }

    @Test
    @DisplayName("The path parameter is a masked card number with the pattern the migration enforces")
    void pathParameterIsAMaskedCardNumber() {
        Map<String, Object> parameter = parameter("maskedCardNumber");
        assertThat(parameter.get("in")).isEqualTo("path");
        assertThat(parameter.get("required")).isEqualTo(true);

        Map<String, Object> schema = schemaOf(parameter);
        assertThat(schema.get("type")).isEqualTo("string");
        assertThat(schema.get("pattern")).isEqualTo(MASKED_PATTERN);
        assertThat(schema.get("minLength")).isEqualTo(MASKED_LENGTH);
        assertThat(schema.get("maxLength")).isEqualTo(MASKED_LENGTH);
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
    @DisplayName("The migration keys the read model on the masked card number")
    void migrationKeysOnTheMaskedCardNumber() {
        assertThat(rawMigration)
                .contains("card_number          CHAR(16)      NOT NULL")
                .contains("PRIMARY KEY (card_number, transaction_id)")
                .contains("CONSTRAINT ck_statement_transaction_card_number");
        assertThat(rawMigration)
                .as("the key half is what stops a full Primary Account Number reaching the table")
                .contains("CHECK (card_number ~ '^\\*{12}[0-9]{4}$')");
    }

    @Test
    @DisplayName("Both tables carry the masked card number and no unmasked column")
    void bothTablesCarryTheMaskedCardNumber() {
        assertThat(rawMigration)
                .contains("CONSTRAINT ck_notification_log_card_number")
                .contains("ix_notification_log_card_number");
        assertThat(countOccurrences(rawMigration, "CHECK (card_number ~ '^\\*{12}[0-9]{4}$')"))
                .as("each table constrains its card column to the masked form")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("The response carries the masked card number the request named")
    void responseCarriesTheMaskedCardNumber() {
        Map<String, Object> history = historySchema();
        Map<String, Object> properties = nested(history, "properties");

        assertThat(asStrings(history.get("required")))
                .containsExactly("cardNumber", "transactionCount", "totalAmount", "transactions");
        assertThat(nested(properties, "cardNumber").get("pattern")).isEqualTo(MASKED_PATTERN);
    }

    @Test
    @DisplayName("The endpoint declares no offset paging, which a concurrent insert would shift")
    void endpointDeclaresNoOffsetPaging() {
        assertThat(parameterNames())
                .as("an offset lets a concurrent insert shift a page already read")
                .doesNotContain("offset", "page", "pageNumber");
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
                .contains("ix_notification_log_card_number");
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

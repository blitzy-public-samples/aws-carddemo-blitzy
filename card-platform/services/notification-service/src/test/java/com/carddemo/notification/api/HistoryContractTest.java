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
 * <p>The two files name a card differently, and that difference is the contract. The route carries
 * the card number, which is {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} and
 * the first part of the key {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} declares. The
 * migration keys on the card token instead, so the stored key is derived and no card number is
 * stored.
 *
 * <p>These tests read the two shipped files and assert that neither drifts. The card-number pattern
 * appears in the route parameter and nowhere else in the description. The migration carries it
 * nowhere at all, and every card number it stores is masked and keys nothing.
 *
 * <p>The remaining assertions cover the response shape. The envelope names the card once, by its
 * masked form, and neither the count nor the array carries a ceiling: the response covers the whole
 * history of one card, matching {@code app/cbl/CBSTM03A.CBL:L429}, which totals every row of one
 * card between two key breaks. {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS} bounds one
 * rendered alert and not this body.
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

    /** The migration that renamed one column and one index, and added {@code outcome}. */
    private static final String RENAME_MIGRATION = "/db/migration/V5__rendered_not_delivered.sql";

    /** The only path the description declares. */
    private static final String HISTORY_PATH = "/notifications/{cardNumber}";

    /** Name of the one path parameter, and the display property of the response envelope. */
    private static final String CARD_NUMBER = "cardNumber";

    /** Pattern a card token matches, which the migration enforces on its key column. */
    private static final String TOKEN_PATTERN = PanMasker.CARD_TOKEN_PATTERN;

    /** Characters a card number spans, in the route and in {@code TRNX-CARD-NUM PIC X(16)}. */
    private static final int CARD_NUMBER_LENGTH = PanMasker.CARD_NUMBER_LENGTH;

    /** Characters the masked display value spans, the width {@code CHAR(16)} declares. */
    private static final int MASKED_LENGTH = PicClause.TRAN_CARD_NUM_WIDTH;

    /** Pattern the masked display value matches in the migration. */
    private static final String MASKED_PATTERN = "^\\*{12}[0-9]{4}$";

    /** The pattern a full sixteen-digit card number matches, which the route parameter declares. */
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
    @DisplayName("The only path takes the card number")
    void onlyPathTakesTheCardNumber() {
        assertThat(paths().keySet()).containsExactly(HISTORY_PATH);
    }

    @Test
    @DisplayName("The path parameter is a card number of sixteen digits")
    void pathParameterIsACardNumber() {
        Map<String, Object> parameter = parameter(CARD_NUMBER);
        assertThat(parameter.get("in")).isEqualTo("path");
        assertThat(parameter.get("required")).isEqualTo(true);

        Map<String, Object> schema = schemaOf(parameter);
        assertThat(schema.get("type")).isEqualTo("string");
        assertThat(schema.get("pattern")).isEqualTo(PAN_PATTERN);
        assertThat(schema.get("minLength")).isEqualTo(CARD_NUMBER_LENGTH);
        assertThat(schema.get("maxLength")).isEqualTo(CARD_NUMBER_LENGTH);
    }

    @Test
    @DisplayName("The request carries the card number and no masked value or token")
    void theRequestCarriesTheCardNumberAlone() {
        assertThat(parameterNames())
                .as("the route names the card the source key names, and reads nothing else")
                .containsExactly(CARD_NUMBER);
        assertThat(schemaOf(parameter(CARD_NUMBER)).get("pattern"))
                .as("a masked value identifies no single card, so no route reads one")
                .isNotEqualTo(MASKED_PATTERN)
                .isNotEqualTo(TOKEN_PATTERN);
    }

    @Test
    @DisplayName("The controller enforces the pattern the description declares")
    void theControllerEnforcesTheDeclaredPattern() {
        assertThat(NotificationHistoryController.CARD_NUMBER_PATTERN).isEqualTo(PAN_PATTERN);
        assertThat(NotificationHistoryController.ROUTE_TEMPLATE).isEqualTo(HISTORY_PATH);
    }

    @Test
    @DisplayName("The card-number pattern appears in the route parameter alone")
    void thePanPatternAppearsInTheRouteParameterAlone() {
        assertThat(countOccurrences(rawOpenapi, PAN_PATTERN))
                .as("the path parameter declares it, and no response schema does")
                .isEqualTo(1);
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
    @DisplayName("The response names the card once, by its masked form, and carries no token")
    void responseNamesTheCardOnceByItsMaskedForm() {
        Map<String, Object> history = historySchema();
        Map<String, Object> properties = nested(history, "properties");

        assertThat(asStrings(history.get("required")))
                .as("the masked form is derived from the path value, so every response carries it")
                .containsExactly(CARD_NUMBER, "transactionCount", "totalAmount", "transactions");
        assertThat(properties.keySet())
                .containsExactly(CARD_NUMBER, "transactionCount", "totalAmount", "transactions");
        assertThat(nested(properties, CARD_NUMBER).get("pattern")).isEqualTo(MASKED_PATTERN);
        assertThat(nested(properties, CARD_NUMBER).get("maxLength")).isEqualTo(MASKED_LENGTH);
        assertThat(rawOpenapi)
                .as("the stored key is a storage key and reaches no response")
                .doesNotContain("cardToken:")
                .doesNotContain("name: cardToken");
    }

    @Test
    @DisplayName("Neither the array nor the count carries a ceiling")
    void responseBoundsNeitherItsArrayNorItsCount() {
        Map<String, Object> properties = nested(historySchema(), "properties");

        assertThat(nested(properties, "transactions").get("maxItems"))
                .as("the array covers the whole history of one card")
                .isNull();
        assertThat(nested(properties, "transactionCount").get("maximum"))
                .as("the count covers the whole history of one card")
                .isNull();
        assertThat(nested(properties, "transactionCount").get("minimum")).isEqualTo(0);
    }

    @Test
    @DisplayName("The endpoint declares no paging parameter of any kind")
    void endpointDeclaresNoPagingParameter() {
        assertThat(parameterNames())
                .as("a bounded page would report a total over rows the source totalled in full")
                .doesNotContain("offset", "page", "pageNumber", "pageSize", "size", "limit",
                        "cursor", "sort")
                .containsExactly(CARD_NUMBER);
        assertThat(rawOpenapi)
                .as("no query parameter survives in the description either")
                .doesNotContain("in: query");
    }

    @Test
    @DisplayName("The rendered alert carries its own row ceiling, whatever the response returns")
    void theRenderedAlertCarriesItsOwnCeiling() {
        assertThat(NotificationRenderer.MAXIMUM_STATEMENT_ROWS)
                .as("a rendered alert is bounded, so one card cannot produce an unbounded message")
                .isPositive();
    }

    /**
     * Holds that each retention delete has an index to run on.
     *
     * <p>{@code ix_notification_log_attempted_at} is the name V1 created and {@code
     * V5__rendered_not_delivered.sql} renamed to {@code ix_notification_log_rendered_at}, together
     * with the column it orders. Both names are asserted here: V1's because it is still the
     * statement that created the index, and V5's because it is the name the live schema carries and
     * the one {@code repository/NotificationLogRepository} documents.</p>
     */
    @Test
    @DisplayName("The migration carries an index for each retention delete")
    void migrationCarriesRetentionIndexes() {
        assertThat(rawMigration)
                .contains("ix_statement_transaction_processing_timestamp")
                .contains("ix_notification_log_attempted_at")
                .contains("ix_cardholder_context_observed_at")
                .contains("ix_processed_event_processed_at");
        assertThat(readClasspathResource(RENAME_MIGRATION))
                .as("the index the rendered-alert purge runs on, under the name it now carries")
                .contains("ALTER INDEX ix_notification_log_attempted_at")
                .contains("RENAME TO ix_notification_log_rendered_at");
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

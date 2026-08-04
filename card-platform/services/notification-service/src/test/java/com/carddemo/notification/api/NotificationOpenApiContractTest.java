package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import jakarta.persistence.Column;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code src/main/resources/openapi.yaml} in step with the Java contract, and holds every
 * read-model column to an event field that supplies it.
 *
 * <p>The document is hand-written and no generator produces it, so nothing but a test keeps it
 * truthful. These tests read it as text and compare it against the records and the entity.
 *
 * <p>The provenance tests are the ones that matter most. A column no event supplies could never be
 * filled, so each of the thirteen columns is matched to a component of
 * {@link TransactionAuthorized} or {@link TransactionPosted}.
 */
final class NotificationOpenApiContractTest {

    /** The interface description, read once. */
    private static String document;

    /** Maps each read-model column to the event component that supplies it. */
    private static final Map<String, String> COLUMN_PROVENANCE = provenance();

    @BeforeAll
    static void readDocument() throws IOException {
        try (InputStream stream = NotificationOpenApiContractTest.class
                .getResourceAsStream("/openapi.yaml")) {
            assertTrue(stream != null, "openapi.yaml is on the classpath");
            document = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDocumentDescribesTheOnePathTheControllerMaps() {
        assertTrue(document.contains("  /notifications/{maskedCardNumber}:"), "the one path");
        assertEquals("/notifications/{maskedCardNumber}",
                NotificationHistoryController.ROUTE_TEMPLATE, "the route template constant");
        assertFalse(document.contains("/api/"), "no api prefix");
        assertFalse(document.contains("/v1/"), "no version prefix");
    }

    @Test
    void theDocumentDescribesTheTwoStatusCodesAndNoOther() {
        assertTrue(document.contains("'200':"), "200");
        assertTrue(document.contains("'400':"), "400");
        assertFalse(document.contains("'404':"), "an empty collection is not a missing resource");
        assertFalse(document.contains("'500':"), "the document declares no fault response");
    }

    @Test
    void thePathParameterCarriesTheMaskedPatternTheControllerEnforces() {
        assertTrue(document.contains("pattern: '^\\*{12}[0-9]{4}$'"),
                "the masked pattern appears in the document");
        assertEquals("^\\*{12}[0-9]{4}$",
                NotificationHistoryController.MASKED_CARD_NUMBER_PATTERN,
                "the controller enforces the same pattern");
    }

    @Test
    void noFullCardNumberPatternAndNoSixteenDigitLiteralAppears() {
        assertFalse(document.contains("^[0-9]{16}$"),
                "a full card number is not a path value here");
        Matcher digits = Pattern.compile("(?<![0-9])[0-9]{16}(?![0-9])").matcher(document);
        assertFalse(digits.find(), "no sixteen-digit literal appears in the document");
        assertFalse(document.toLowerCase().contains("cvv"),
                "the card verification value appears nowhere");
    }

    @Test
    void theErrorSchemaMatchesTheErrorRecord() {
        assertTrue(document.contains("    ApiError:"), "the error schema is defined");
        assertTrue(document.contains("$ref: '#/components/schemas/ApiError'"),
                "the 400 response references it");
        for (RecordComponent component : ApiErrorResponse.class.getRecordComponents()) {
            assertTrue(document.contains("        " + component.getName() + ":"),
                    "the schema declares " + component.getName());
        }
    }

    @Test
    void theErrorSchemaDeclaresNoPropertyThatCouldEchoARequestValue() {
        for (String forbidden : List.of("instance:", "path:", "uri:", "requestUri:", "detail:")) {
            assertFalse(document.contains("        " + forbidden),
                    "the error schema declares " + forbidden);
        }
    }

    @Test
    void theEnvelopeSchemaMatchesTheResponseRecord() {
        List<String> components =
                Arrays.stream(NotificationHistoryResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();
        assertEquals(List.of("cardNumber", "transactionCount", "totalAmount", "transactions"),
                components, "four components");
        for (String component : components) {
            assertTrue(document.contains("        " + component + ":"),
                    "the envelope declares " + component);
        }
    }

    @Test
    void theItemSchemaMatchesTheItemRecord() {
        List<String> components =
                Arrays.stream(NotificationTransactionItem.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();
        assertEquals(12, components.size(), "twelve components");
        for (String component : components) {
            assertTrue(document.contains("        " + component + ":"),
                    "the item declares " + component);
        }
    }

    @Test
    void theItemComponentsAreTheColumnsMinusTheCardNumber() {
        Set<String> itemComponents =
                Arrays.stream(NotificationTransactionItem.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toUnmodifiableSet());
        Set<String> columnsMinusCard = COLUMN_PROVENANCE.keySet().stream()
                .filter(column -> !column.equals("cardNumber"))
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(columnsMinusCard, itemComponents,
                "the card number sits on the envelope and every other column is an item component");
    }

    @Test
    void everyMonetaryValueTravelsAsAStringWithNineIntegerDigits() {
        assertEquals(2, countOf(document, "pattern: '^-?\\d{1,9}\\.\\d{2}$'"),
                "the amount and the total carry the same shape");
        assertFalse(document.contains("d{1,10}"),
                "the ten-digit shape belongs to an account balance and this service holds none");
        assertFalse(document.contains("type: number"), "no monetary value is a JSON number");
    }

    @Test
    void everyReadModelColumnIsSuppliedByAnEventComponent() {
        Set<String> authorized = componentNamesOf(TransactionAuthorized.class);
        Set<String> posted = componentNamesOf(TransactionPosted.class);

        for (Map.Entry<String, String> entry : COLUMN_PROVENANCE.entrySet()) {
            String supplier = entry.getValue();
            assertTrue(authorized.contains(supplier) || posted.contains(supplier),
                    "column " + entry.getKey() + " names supplier " + supplier
                            + ", which neither event carries");
        }
    }

    @Test
    void theProvenanceCoversEveryMappedColumnAndNoOther() {
        List<String> mapped = new ArrayList<>();
        for (Field field : StatementTransactionId.class.getDeclaredFields()) {
            if (field.getAnnotation(Column.class) != null) {
                mapped.add(field.getName());
            }
        }
        for (Field field : StatementTransactionEntity.class.getDeclaredFields()) {
            if (field.getAnnotation(Column.class) != null) {
                mapped.add(field.getName());
            }
        }
        assertEquals(13, mapped.size(), "thirteen mapped columns");
        assertEquals(Set.copyOf(mapped), COLUMN_PROVENANCE.keySet(),
                "every mapped column carries a provenance entry and no entry is invented");
    }

    @Test
    void onlyTheProcessingTimestampComesFromThePostedEventAlone() {
        Set<String> authorized = componentNamesOf(TransactionAuthorized.class);
        List<String> postedOnly = COLUMN_PROVENANCE.entrySet().stream()
                .filter(entry -> !authorized.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(List.of("processingTimestamp"), postedOnly,
                "the authorized event supplies twelve columns and the posted event the thirteenth");
    }

    @Test
    void theDocumentRecordsTheMergeAcrossBothEvents() {
        assertTrue(document.contains("TransactionAuthorized supplies"),
                "the document names the event that supplies twelve columns");
        assertTrue(document.contains("TransactionPosted supplies"),
                "the document names the event that supplies the processing timestamp");
        assertTrue(document.contains("Either event may arrive first"),
                "the document states that arrival order does not matter");
    }

    /**
     * Declares which event component supplies each read-model column.
     *
     * <p>Column names are the entity field names. Supplier names are record component names of the
     * two events, which {@link #everyReadModelColumnIsSuppliedByAnEventComponent()} checks.
     *
     * @return the mapping
     */
    private static Map<String, String> provenance() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("cardNumber", "maskedCardNumber");
        columns.put("transactionId", "transactionId");
        columns.put("typeCode", "transactionTypeCode");
        columns.put("categoryCode", "merchantCategoryCode");
        columns.put("source", "source");
        columns.put("description", "description");
        columns.put("amount", "amount");
        columns.put("merchantId", "merchantId");
        columns.put("merchantName", "merchantName");
        columns.put("merchantCity", "merchantCity");
        columns.put("merchantZip", "merchantZip");
        columns.put("originTimestamp", "authorizedAt");
        columns.put("processingTimestamp", "postedAt");
        return Map.copyOf(columns);
    }

    /**
     * Names every record component of one event.
     *
     * @param event the event record
     * @return the component names
     */
    private static Set<String> componentNamesOf(Class<?> event) {
        return Arrays.stream(event.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Counts how many times one text appears in another.
     *
     * @param text the text to search
     * @param sought the text to count
     * @return the count
     */
    private static int countOf(String text, String sought) {
        int count = 0;
        int at = text.indexOf(sought);
        while (at >= 0) {
            count++;
            at = text.indexOf(sought, at + sought.length());
        }
        return count;
    }
}

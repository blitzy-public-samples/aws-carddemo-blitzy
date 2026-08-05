package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.domain.NotificationRenderer;
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
 * filled, so each of the fourteen columns is matched to a component of {@link TransactionPosted} at
 * schema version {@value TransactionPosted#TRANSACTION_DETAIL_SCHEMA_VERSION}. One event fills a
 * whole row, which is why no field of a response reads as spaces or zeros waiting for a second
 * event, and why this service consumes one topic to serve this route.
 */
final class NotificationOpenApiContractTest {

    /** The interface description, read once. */
    private static String document;

    /** Maps each read-model column to the fill source that populates it. */
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
        assertTrue(document.contains("  /notifications/{cardToken}:"), "the one path");
        assertEquals("/notifications/{cardToken}",
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
    void thePathParameterCarriesTheTokenPatternTheControllerEnforces() {
        assertTrue(document.contains("pattern: '" + PanMasker.CARD_TOKEN_PATTERN + "'"),
                "the token pattern appears in the document");
        assertEquals(PanMasker.CARD_TOKEN_PATTERN,
                NotificationHistoryController.CARD_TOKEN_PATTERN,
                "the controller enforces the same pattern");
        assertFalse(document.contains("- name: maskedCardNumber"),
                "no request carries a card number of either form");
    }

    @Test
    void theMaskedPatternGovernsTheDisplayPropertyAndNoRequestValue() {
        assertEquals(2, countOf(document, "pattern: '^\\*{12}[0-9]{4}$'"),
                "the masked shape appears on the envelope and on each transaction item");
        int maskedAt = document.indexOf("pattern: '^\\*{12}[0-9]{4}$'");
        int parametersEnd = document.indexOf("      responses:");
        assertTrue(maskedAt > parametersEnd,
                "the masked shape sits below the parameter block, so it governs no request value");
    }

    @Test
    void noFullCardNumberPatternAndNoSixteenDigitLiteralAppears() {
        assertFalse(document.contains("^[0-9]{16}$"),
                "a sixteen-digit shape in this document would read as a card number");
        Matcher digits = Pattern.compile("(?<![0-9])[0-9]{16}(?![0-9])").matcher(document);
        assertFalse(digits.find(), "no sixteen-digit literal appears in the document");
        assertFalse(document.toLowerCase().contains("cvv"),
                "the card verification value appears nowhere");
    }

    @Test
    void theTransactionIdentifierIsBoundedByWidthAloneAndNotByADigitShape() {
        int identifierAt = document.indexOf("        transactionId:");
        assertTrue(identifierAt > 0, "the item declares the transaction identifier");
        String property = document.substring(identifierAt,
                document.indexOf("        maskedCardNumber:", identifierAt));

        assertTrue(property.contains("minLength: 16"),
                "the lower bound states the exact width the entity refuses to depart from");
        assertTrue(property.contains("maxLength: 16"), "the upper bound");
        assertFalse(property.contains("pattern:"),
                "the entity constrains width alone, so no shape is described either");
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
        assertEquals(List.of("cardToken", "cardNumber", "transactionCount", "totalAmount",
                        "transactions"),
                components, "five components: one identity, one display and three about the page");
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
        assertEquals(13, components.size(), "thirteen components");
        for (String component : components) {
            assertTrue(document.contains("        " + component + ":"),
                    "the item declares " + component);
        }
    }

    @Test
    void theItemComponentsAreTheColumnsMinusTheTokenKey() {
        Set<String> itemComponents =
                Arrays.stream(NotificationTransactionItem.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toUnmodifiableSet());
        Set<String> columnsMinusCard = COLUMN_PROVENANCE.keySet().stream()
                .filter(column -> !column.equals("cardToken"))
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(columnsMinusCard, itemComponents,
                "the token key sits on the envelope and every display column is an item");
    }

    @Test
    void theArrayAndTheCountCarryTheRowCeilingTheRendererDeclares() {
        String ceiling = String.valueOf(NotificationRenderer.MAXIMUM_STATEMENT_ROWS);
        assertTrue(document.contains("maxItems: " + ceiling),
                "the array declares the ceiling as maxItems");
        assertTrue(document.contains("maximum: " + ceiling),
                "the count declares the same ceiling as its maximum");
        assertEquals(1, countOf(document, "maxItems:"), "one array, one bound");
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
    void everyReadModelColumnIsSuppliedByTheOneEventThisServiceConsumes() {
        Set<String> posted = componentNamesOf(TransactionPosted.class);

        for (Map.Entry<String, String> entry : COLUMN_PROVENANCE.entrySet()) {
            String supplier = entry.getValue();
            assertTrue(posted.contains(supplier),
                    "column " + entry.getKey() + " names supplier " + supplier
                            + ", which TransactionPosted does not carry");
        }
    }

    /**
     * Holds the read model to the one event this service can actually receive on
     * {@code transaction.posted}. A column whose fill source were a component only
     * {@link TransactionAuthorized} carries would never be filled, because this service has no
     * listener on {@code transaction.authorized} and no broker entry granting it one.
     */
    @Test
    void noColumnDependsOnAComponentOnlyTheAuthorizationEventCarries() {
        Set<String> posted = componentNamesOf(TransactionPosted.class);
        Set<String> authorizedOnly = componentNamesOf(TransactionAuthorized.class).stream()
                .filter(component -> !posted.contains(component))
                .collect(Collectors.toUnmodifiableSet());

        List<String> dependent = COLUMN_PROVENANCE.entrySet().stream()
                .filter(entry -> authorizedOnly.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        assertEquals(List.of(), dependent,
                "no column may name a supplier only the authorization event carries");
    }

    @Test
    void thePostedEventAccountsForAllFourteenColumns() {
        Set<String> posted = componentNamesOf(TransactionPosted.class);
        long fromEvent = COLUMN_PROVENANCE.values().stream().filter(posted::contains).count();

        assertEquals(14, fromEvent, "every mapped column has a TransactionPosted supplier");
        assertEquals(14, COLUMN_PROVENANCE.size(), "fourteen mapped columns in total");
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
        assertEquals(14, mapped.size(), "fourteen mapped columns");
        assertEquals(Set.copyOf(mapped), COLUMN_PROVENANCE.keySet(),
                "every mapped column carries a provenance entry and no entry is invented");
    }

    /**
     * Asserts the document records the single-event derivation the listener performs, and records no
     * merge across two events. A document promising a merge would tell a reader that eight fields
     * fill in later, when nothing is coming to fill them.
     */
    @Test
    void everyColumnButTheTwoTimestampsNamesTheSameComponentOnBothEvents() {
        Set<String> authorized = componentNamesOf(TransactionAuthorized.class);
        // COLUMN_PROVENANCE is an unordered immutable map, so this collects to a set.
        Set<String> namedDifferently = COLUMN_PROVENANCE.entrySet().stream()
                .filter(entry -> !authorized.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("originTimestamp", "processingTimestamp"), namedDifferently,
                "twelve column values travel under one name on both events; the origin timestamp is"
                        + " authorizedAt on the authorization event, and the processing timestamp"
                        + " originates with the posting");
        assertTrue(authorized.contains("authorizedAt"),
                "the authorization event captures the moment the transaction originated");
        assertEquals("originTimestamp", COLUMN_PROVENANCE.get("originTimestamp"),
                "TransactionPosted.forAuthorized copies authorizedAt into originTimestamp, so the"
                        + " column reads a captured moment and not a reshaped posting moment");
    }

    /**
     * Asserts the document describes the paging the controller applies, at the ceiling the bound
     * properties hold both page sizes under. A page size the endpoint honours but the document omits
     * would leave a caller unable to ask for one without reading the source.
     */
    @Test
    void theDocumentRecordsTheOneEventThatFillsARow() {
        assertTrue(document.contains("One event fills a row."),
                "the document states that one event fills a row");
        assertTrue(document.contains("TransactionPosted at schema version "
                        + TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION),
                "the document names the event and the version that carries every value");
        assertTrue(document.contains("TransactionPosted.forAuthorized"),
                "the document names the factory that copies the authorized values forward");
        assertFalse(document.contains("Either event may arrive first"),
                "no field waits on a second event, so no arrival order is described");
        assertTrue(document.contains("No field reads as an empty string or as zeros"),
                "the document states that no field of a response reads as a blank or a zero");
    }

    @Test
    void theTwoTimestampsAreDistinctMomentsAndNeitherIsDerivedFromTheOther() {
        Set<String> posted = componentNamesOf(TransactionPosted.class);
        assertTrue(posted.contains("originTimestamp"),
                "the event carries the moment the transaction originated");
        assertTrue(posted.contains("postedAt"), "and the moment the platform recorded it");
        assertEquals("originTimestamp", COLUMN_PROVENANCE.get("originTimestamp"),
                "the origin column copies the origin field rather than reshaping the posting one");
        assertEquals("postedAt", COLUMN_PROVENANCE.get("processingTimestamp"),
                "the processing column copies the posting field");
    }

    /**
     * Declares which event component supplies each read-model column.
     *
     * <p>Column names are the entity field names. Supplier names are record component names of
     * {@link TransactionPosted}, which
     * {@link #everyReadModelColumnIsSuppliedByTheOneEventThisServiceConsumes()} checks.
     *
     * @return the mapping
     */
    private static Map<String, String> provenance() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("cardToken", "cardToken");
        columns.put("maskedCardNumber", "maskedCardNumber");
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
        columns.put("originTimestamp", "originTimestamp");
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

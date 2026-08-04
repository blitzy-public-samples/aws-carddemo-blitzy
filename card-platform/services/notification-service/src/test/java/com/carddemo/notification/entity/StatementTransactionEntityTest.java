package com.carddemo.notification.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PicClause;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

/**
 * Mapping tests for {@link StatementTransactionEntity}.
 *
 * <p>Three properties are under test. The key holds the masked card number, so a full Primary
 * Account Number (PAN) is refused rather than stored. Every character column is declared
 * {@code CHAR}, matching {@code src/main/resources/db/migration/V1__schema.sql}, because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} and a {@code varchar} mapping against a
 * {@code bpchar} column stops start-up. And the thirteen columns are the thirteen fields of
 * {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20} with the trailing filler dropped.
 */
final class StatementTransactionEntityTest {

    /** A masked card number: twelve mask characters then four digits. */
    private static final String MASKED_CARD = "************7065";

    /** A transaction identifier at its full sixteen characters. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** Columns the table declares, from V1__schema.sql. */
    private static final int COLUMN_COUNT = 13;

    /**
     * Columns declared CHAR: every {@code PIC X} field of the record, and the two {@code PIC 9}
     * identifier fields the migration declares CHAR so their leading zeros survive.
     */
    private static final int CHARACTER_COLUMN_COUNT = 12;

    @Test
    void theKeyTakesTheMaskedCardNumber() {
        StatementTransactionId id = new StatementTransactionId(MASKED_CARD, TRANSACTION_ID);
        assertEquals(MASKED_CARD, id.getCardNumber(), "card number");
        assertEquals(TRANSACTION_ID, id.getTransactionId(), "transaction identifier");
        assertEquals(PicClause.TRAN_CARD_NUM_WIDTH, id.getCardNumber().length(), "width");
    }

    @Test
    void theKeyRefusesAnUnmaskedCardNumber() {
        String sixteenDigits = "4859452612877" + "065";
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new StatementTransactionId(sixteenDigits, TRANSACTION_ID),
                "an unmasked value is refused");
        assertTrue(refused.getMessage().contains("masked"), "the message names the masked form");
    }

    @Test
    void theKeyRefusesACardNumberOfTheWrongWidth() {
        assertThrows(IllegalArgumentException.class,
                () -> new StatementTransactionId("*******7065", TRANSACTION_ID), "too short");
        assertThrows(IllegalArgumentException.class,
                () -> new StatementTransactionId("*************7065", TRANSACTION_ID), "too long");
    }

    @Test
    void theKeyRefusesATransactionIdentifierOfTheWrongWidth() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new StatementTransactionId(MASKED_CARD, "1"), "fifteen characters short");
        assertTrue(refused.getMessage().contains(String.valueOf(PicClause.TRAN_ID_WIDTH)),
                "the message names the width the column holds");
    }

    @Test
    void twoKeysOverTheSameTwoPartsAreEqual() {
        StatementTransactionId one = new StatementTransactionId(MASKED_CARD, TRANSACTION_ID);
        StatementTransactionId two = new StatementTransactionId(MASKED_CARD, TRANSACTION_ID);
        assertEquals(one, two, "equal");
        assertEquals(one.hashCode(), two.hashCode(), "same hash");
        assertFalse(one.equals(new StatementTransactionId(MASKED_CARD, "0000000000000002")),
                "a different transaction identifier is a different key");
    }

    @Test
    void theRowCarriesEveryValueItWasBuiltFrom() {
        StatementTransactionEntity row = row(new BigDecimal("194.00"));
        assertEquals(MASKED_CARD, row.getId().getCardNumber(), "card number");
        assertEquals(TRANSACTION_ID, row.getId().getTransactionId(), "transaction identifier");
        assertEquals("01", row.getTypeCode(), "type code");
        assertEquals("0001", row.getCategoryCode(),
                "the category code reads as four digits, left-padded with zeros");
        assertEquals("POS TERM", row.getSource(), "source");
        assertEquals("Purchase", row.getDescription(), "description");
        assertEquals(new BigDecimal("194.00"), row.getAmount(), "amount");
        assertEquals("800000000", row.getMerchantId(), "merchant identifier");
        assertEquals("2022-06-10 19:27:53.000000", row.getOriginTimestamp(), "origin timestamp");
        assertEquals("2022-07-19-23.16.01.470000", row.getProcessingTimestamp(),
                "processing timestamp");
    }

    @Test
    void anAmountOfTheWrongScaleIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> row(new BigDecimal("194.000")), "three fractional digits are refused");
        assertTrue(refused.getMessage().contains("amount"), "the message names the field");
    }

    @Test
    void twoRowsUnderOneKeyAreEqual() {
        assertEquals(row(new BigDecimal("194.00")), row(new BigDecimal("1.00")),
                "identity is the key alone");
    }

    @Test
    void thirteenColumnsAreMappedAndTheFillerIsNotAmongThem() {
        assertEquals(COLUMN_COUNT, mappedColumns().size(), "columns");
        assertFalse(mappedColumns().contains("filler"), "the trailing filler carries no column");
    }

    @Test
    void everyCharacterColumnIsDeclaredChar() {
        int declared = 0;
        for (Field field : allMappedFields()) {
            if (field.getType() == String.class) {
                JdbcTypeCode code = field.getAnnotation(JdbcTypeCode.class);
                assertNotNull(code, "field " + field.getName() + " carries no JdbcTypeCode");
                assertEquals(SqlTypes.CHAR, code.value(), "field " + field.getName());
                declared++;
            }
        }
        assertEquals(CHARACTER_COLUMN_COUNT, declared, "character columns");
    }

    @Test
    void theAmountColumnCarriesNineIntegerDigits() throws NoSuchFieldException {
        Column amount =
                StatementTransactionEntity.class.getDeclaredField("amount").getAnnotation(
                        Column.class);
        assertEquals(PicClause.TRAN_AMT_PRECISION, amount.precision(), "precision");
        assertEquals(PicClause.TRAN_AMT_SCALE, amount.scale(), "scale");
        assertEquals(11, amount.precision(), "TRNX-AMT PIC S9(09)V99 is eleven total digits");
    }

    @Test
    void theEntityDeclaresNoSchemaAndNoRelationship() {
        Table table = StatementTransactionEntity.class.getAnnotation(Table.class);
        assertNotNull(StatementTransactionEntity.class.getAnnotation(Entity.class), "entity");
        assertEquals("statement_transaction", table.name(), "table");
        assertEquals("", table.schema(), "the migration owns the schema");
        assertEquals(1, table.indexes().length,
                "the one secondary index V1__schema.sql creates is declared here");
        assertEquals("ix_statement_transaction_processing_timestamp", table.indexes()[0].name(),
                "the declared index names the one the migration creates");
        assertEquals("processing_timestamp", table.indexes()[0].columnList(),
                "the index reads the column the migration indexes");
    }

    @Test
    void theKeyIsEmbeddedAndThereAreNoSetters() {
        boolean embedded = false;
        for (Field field : StatementTransactionEntity.class.getDeclaredFields()) {
            if (field.getAnnotation(EmbeddedId.class) != null) {
                embedded = true;
            }
        }
        assertTrue(embedded, "the key is an @EmbeddedId");
        for (var method : StatementTransactionEntity.class.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set") && Modifier.isPublic(
                            method.getModifiers()),
                    "method " + method.getName() + " is a setter");
        }
        for (var method : StatementTransactionId.class.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set") && Modifier.isPublic(
                            method.getModifiers()),
                    "method " + method.getName() + " is a setter");
        }
    }

    @Test
    void aNullArgumentIsRefusedByName() {
        NullPointerException refused = assertThrows(NullPointerException.class,
                () -> new StatementTransactionEntity(
                        new StatementTransactionId(MASKED_CARD, TRANSACTION_ID), null,
                        "1", "POS TERM", "Purchase", new BigDecimal("1.00"),
                        "800000000", "Merchant", "City", "72112",
                        "2022-06-10 19:27:53.000000", "2022-07-19-23.16.01.470000"),
                "a null type code is refused");
        assertTrue(refused.getMessage().contains("typeCode"), "the message names the field");
    }

    @Test
    void theProviderConstructorLeavesEveryValueUnset() throws Exception {
        var constructor = StatementTransactionEntity.class.getDeclaredConstructor();
        assertTrue(Modifier.isProtected(constructor.getModifiers()), "protected");
        constructor.setAccessible(true);
        StatementTransactionEntity empty = constructor.newInstance();
        assertNull(empty.getId(), "the provider fills the key");
    }

    /**
     * Names every column the entity maps, key parts included.
     *
     * @return the column names
     */
    private static List<String> mappedColumns() {
        List<String> names = new ArrayList<>();
        for (Field field : allMappedFields()) {
            names.add(field.getAnnotation(Column.class).name());
        }
        return names;
    }

    /**
     * Collects every field carrying a column, from the entity and from its key.
     *
     * @return the fields
     */
    private static List<Field> allMappedFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : StatementTransactionId.class.getDeclaredFields()) {
            if (field.getAnnotation(Column.class) != null) {
                fields.add(field);
            }
        }
        for (Field field : StatementTransactionEntity.class.getDeclaredFields()) {
            if (field.getAnnotation(Column.class) != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Builds one row carrying an amount.
     *
     * @param amount the amount
     * @return the row
     */
    private static StatementTransactionEntity row(BigDecimal amount) {
        return new StatementTransactionEntity(
                new StatementTransactionId(MASKED_CARD, TRANSACTION_ID), "01", "1",
                "POS TERM", "Purchase", amount, "800000000", "Merchant", "City",
                "72112", "2022-06-10 19:27:53.000000", "2022-07-19-23.16.01.470000");
    }
}

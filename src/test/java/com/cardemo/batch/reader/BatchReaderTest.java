package com.cardemo.batch.reader;

import com.cardemo.batch.reader.FixedWidthFileReader.ColumnSpec;
import com.cardemo.batch.reader.FixedWidthFileReader.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FixedWidthFileReader} and {@link DailyTransactionReader}.
 *
 * <p>Tests the EBCDIC-compatible zoned-decimal parsing, record-length validation,
 * column specification factory methods, FieldType enum, and ColumnSpec record.</p>
 */
@ExtendWith(MockitoExtension.class)
class BatchReaderTest {

    @InjectMocks
    private FixedWidthFileReader fixedWidthFileReader;

    // ══════════════════════════════════════════════════════════════
    //  FixedWidthFileReader — FieldType enum
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FieldType Enum")
    class FieldTypeTests {

        @Test
        @DisplayName("All three values exist")
        void allValuesExist() {
            FieldType[] values = FieldType.values();
            assertThat(values).containsExactly(FieldType.STRING, FieldType.INTEGER, FieldType.ZONED_DECIMAL);
        }

        @Test
        @DisplayName("valueOf returns correct constant")
        void valueOfReturnsConstant() {
            assertThat(FieldType.valueOf("STRING")).isEqualTo(FieldType.STRING);
            assertThat(FieldType.valueOf("INTEGER")).isEqualTo(FieldType.INTEGER);
            assertThat(FieldType.valueOf("ZONED_DECIMAL")).isEqualTo(FieldType.ZONED_DECIMAL);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  FixedWidthFileReader — ColumnSpec record
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ColumnSpec Record")
    class ColumnSpecTests {

        @Test
        @DisplayName("Full constructor preserves all values")
        void fullConstructor() {
            ColumnSpec spec = new ColumnSpec(1, 11, "accountId", FieldType.STRING, 0);
            assertThat(spec.startPosition()).isEqualTo(1);
            assertThat(spec.endPosition()).isEqualTo(11);
            assertThat(spec.fieldName()).isEqualTo("accountId");
            assertThat(spec.dataType()).isEqualTo(FieldType.STRING);
            assertThat(spec.scale()).isEqualTo(0);
        }

        @Test
        @DisplayName("Convenience constructor sets scale to 0")
        void convenienceConstructor() {
            ColumnSpec spec = new ColumnSpec(1, 11, "accountId", FieldType.STRING);
            assertThat(spec.scale()).isEqualTo(0);
        }

        @Test
        @DisplayName("Record equals and toString work correctly")
        void equalsAndToString() {
            ColumnSpec spec1 = new ColumnSpec(1, 11, "field", FieldType.STRING, 0);
            ColumnSpec spec2 = new ColumnSpec(1, 11, "field", FieldType.STRING, 0);
            assertThat(spec1).isEqualTo(spec2);
            assertThat(spec1.toString()).contains("field");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  FixedWidthFileReader — parseZonedDecimal
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseZonedDecimal — EBCDIC overpunch decoding")
    class ParseZonedDecimal {

        @Test
        @DisplayName("Null input returns zero")
        void nullReturnsZero() {
            assertThat(FixedWidthFileReader.parseZonedDecimal(null, 2))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Empty string returns zero")
        void emptyReturnsZero() {
            assertThat(FixedWidthFileReader.parseZonedDecimal("", 2))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Blank string returns zero")
        void blankReturnsZero() {
            assertThat(FixedWidthFileReader.parseZonedDecimal("   ", 2))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Plain numeric string with scale=2")
        void plainNumericWithScale() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("12345", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("Plain numeric string with scale=0")
        void plainNumericNoScale() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("12345", 0);
            assertThat(result).isEqualByComparingTo(new BigDecimal("12345"));
        }

        // Positive overpunch characters: { = +0, A-I = +1 to +9
        @Test
        @DisplayName("Positive overpunch '{' = trailing +0")
        void positiveOverpunchBrace() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234{", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("123.40"));
        }

        @Test
        @DisplayName("Positive overpunch 'A' = trailing +1")
        void positiveOverpunchA() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234A", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("123.41"));
        }

        @Test
        @DisplayName("Positive overpunch 'E' = trailing +5")
        void positiveOverpunchE() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234E", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("Positive overpunch 'I' = trailing +9")
        void positiveOverpunchI() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234I", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("123.49"));
        }

        // Negative overpunch characters: } = -0, J-R = -1 to -9
        @Test
        @DisplayName("Negative overpunch '}' = trailing -0")
        void negativeOverpunchBrace() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234}", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("-123.40"));
        }

        @Test
        @DisplayName("Negative overpunch 'J' = trailing -1")
        void negativeOverpunchJ() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234J", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("-123.41"));
        }

        @Test
        @DisplayName("Negative overpunch 'N' = trailing -5")
        void negativeOverpunchN() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234N", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("-123.45"));
        }

        @Test
        @DisplayName("Negative overpunch 'R' = trailing -9")
        void negativeOverpunchR() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("1234R", 2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("-123.49"));
        }

        @Test
        @DisplayName("Invalid overpunch character throws IllegalArgumentException")
        void invalidCharThrows() {
            assertThatThrownBy(() -> FixedWidthFileReader.parseZonedDecimal("1234Z", 2))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Single-digit input with overpunch")
        void singleDigitWithOverpunch() {
            BigDecimal result = FixedWidthFileReader.parseZonedDecimal("E", 0);
            assertThat(result).isEqualByComparingTo(new BigDecimal("5"));
        }

        @Test
        @DisplayName("All-zeros returns zero regardless of scale")
        void allZerosReturnsZero() {
            assertThat(FixedWidthFileReader.parseZonedDecimal("00000", 2))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  FixedWidthFileReader — validateRecordLength
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validateRecordLength")
    class ValidateRecordLength {

        @Test
        @DisplayName("Null line throws IllegalArgumentException")
        void nullLineThrows() {
            assertThatThrownBy(() ->
                    FixedWidthFileReader.validateRecordLength(null, 100, "testfile"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Line shorter than expected throws IllegalArgumentException")
        void shortLineThrows() {
            assertThatThrownBy(() ->
                    FixedWidthFileReader.validateRecordLength("short", 100, "testfile"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Line of exact length does not throw")
        void exactLengthAccepted() {
            String line = "A".repeat(100);
            // Should not throw
            FixedWidthFileReader.validateRecordLength(line, 100, "testfile");
        }

        @Test
        @DisplayName("Line longer than expected is accepted (truncation handled elsewhere)")
        void longerLineAccepted() {
            String line = "A".repeat(200);
            // Should not throw — longer lines are allowed
            FixedWidthFileReader.validateRecordLength(line, 100, "testfile");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  FixedWidthFileReader — Column Spec Factory Methods
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Column Specification Factory Methods")
    class ColumnSpecFactories {

        @Test
        @DisplayName("getAccountColumnSpecs returns 12 fields")
        void accountSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getAccountColumnSpecs();
            assertThat(specs).hasSize(12);
            assertThat(specs.get(0).fieldName()).isEqualTo("acctId");
        }

        @Test
        @DisplayName("getCardColumnSpecs returns 6 fields")
        void cardSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getCardColumnSpecs();
            assertThat(specs).hasSize(6);
            assertThat(specs.get(0).fieldName()).isEqualTo("cardNum");
        }

        @Test
        @DisplayName("getCardXrefColumnSpecs returns 3 fields")
        void xrefSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getCardXrefColumnSpecs();
            assertThat(specs).hasSize(3);
        }

        @Test
        @DisplayName("getCustomerColumnSpecs returns 18 fields")
        void customerSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getCustomerColumnSpecs();
            assertThat(specs).hasSize(18);
            assertThat(specs.get(0).fieldName()).isEqualTo("custId");
        }

        @Test
        @DisplayName("getTransactionColumnSpecs returns 13 fields")
        void transactionSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getTransactionColumnSpecs();
            assertThat(specs).hasSize(13);
        }

        @Test
        @DisplayName("getDailyTransactionColumnSpecs delegates to getTransactionColumnSpecs")
        void dailyTransactionSpecsDelegates() {
            List<ColumnSpec> daily = FixedWidthFileReader.getDailyTransactionColumnSpecs();
            List<ColumnSpec> regular = FixedWidthFileReader.getTransactionColumnSpecs();
            assertThat(daily).isEqualTo(regular);
        }

        @Test
        @DisplayName("getUserSecurityColumnSpecs returns 5 fields")
        void userSecuritySpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getUserSecurityColumnSpecs();
            assertThat(specs).hasSize(5);
        }

        @Test
        @DisplayName("getTransactionTypeColumnSpecs returns 2 fields")
        void transactionTypeSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getTransactionTypeColumnSpecs();
            assertThat(specs).hasSize(2);
        }

        @Test
        @DisplayName("getTransactionCategoryColumnSpecs returns 3 fields")
        void transactionCategorySpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getTransactionCategoryColumnSpecs();
            assertThat(specs).hasSize(3);
        }

        @Test
        @DisplayName("getCategoryBalanceColumnSpecs returns 4 fields")
        void categoryBalanceSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getCategoryBalanceColumnSpecs();
            assertThat(specs).hasSize(4);
        }

        @Test
        @DisplayName("getDiscountGroupColumnSpecs returns 4 fields")
        void discountGroupSpecs() {
            List<ColumnSpec> specs = FixedWidthFileReader.getDiscountGroupColumnSpecs();
            assertThat(specs).hasSize(4);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DailyTransactionReader
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DailyTransactionReader")
    class DailyTransactionReaderTests {

        @Test
        @DisplayName("DailyTransactionReader is instantiable")
        void isInstantiable() {
            DailyTransactionReader reader = new DailyTransactionReader();
            assertThat(reader).isNotNull();
        }
    }
}

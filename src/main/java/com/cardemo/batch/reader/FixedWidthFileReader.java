package com.cardemo.batch.reader;

import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic fixed-width file parser implementing the Template Method pattern.
 * Translates COBOL FILE SECTION FD definitions and sequential READ INTO
 * patterns into Spring Batch {@link FlatFileItemReader} configurations.
 *
 * <p>Supports all 9 fixed-width test fixture files from {@code app/data/ASCII/}:
 * acctdata.txt (300 bytes), carddata.txt (150 bytes), cardxref.txt (50 bytes),
 * custdata.txt (500 bytes), dailytran.txt (350 bytes), discgrp.txt (50 bytes),
 * tcatbal.txt (50 bytes), trancatg.txt (60 bytes), trantype.txt (60 bytes).</p>
 *
 * <p>COBOL pattern translated:</p>
 * <pre>{@code
 * FILE SECTION.
 * FD  dataset-FILE.
 * 01  FD-record.
 *     05 FD-key    PIC X(nn).
 *     05 FD-data   PIC X(nnn).
 * PROCEDURE DIVISION.
 *     OPEN INPUT dataset-FILE
 *     READ dataset-FILE INTO record
 * }</pre>
 *
 * <p>The {@link #createReader} method serves as the template method, accepting
 * column specifications and field-set mappers as the variable parts.</p>
 */
@Component
public class FixedWidthFileReader {

    /**
     * Enumerates the data types found in COBOL fixed-width record definitions.
     * Each type maps to a specific COBOL PIC clause pattern.
     */
    public enum FieldType {
        /** PIC X(n) — alphanumeric field, trimmed on read. */
        STRING,
        /** PIC 9(n) — unsigned integer with no decimal point. */
        INTEGER,
        /** PIC S9(m)V99 — signed decimal with zoned sign encoding in last byte. */
        ZONED_DECIMAL
    }

    /**
     * Defines a single column within a fixed-width record layout.
     * Positions are 1-based inclusive, matching COBOL PIC clause conventions
     * and Spring Batch {@link Range} semantics.
     *
     * @param startPosition 1-based inclusive start position of the field
     * @param endPosition   1-based inclusive end position of the field
     * @param fieldName     Java field name for FieldSet access
     * @param dataType      the COBOL data type classification
     * @param scale         decimal scale for numeric types (0 for STRING/INTEGER, 2 for V99)
     */
    public record ColumnSpec(
            int startPosition,
            int endPosition,
            String fieldName,
            FieldType dataType,
            int scale
    ) {
        /**
         * Convenience constructor for non-decimal fields (STRING and INTEGER types)
         * where scale is always zero.
         *
         * @param startPosition 1-based inclusive start position
         * @param endPosition   1-based inclusive end position
         * @param fieldName     Java field name for FieldSet access
         * @param dataType      the COBOL data type classification
         */
        public ColumnSpec(int startPosition, int endPosition,
                          String fieldName, FieldType dataType) {
            this(startPosition, endPosition, fieldName, dataType, 0);
        }
    }

    /**
     * Template method that creates a configured {@link FlatFileItemReader} for
     * any fixed-width file format. Translates the COBOL {@code OPEN INPUT} +
     * {@code READ INTO} pattern into Spring Batch reader configuration.
     *
     * @param <T>            the target entity type
     * @param readerName     unique name for the reader (used in Spring Batch metadata)
     * @param resource       Spring Resource pointing to the fixed-width data file
     * @param columnSpecs    column definitions matching the COBOL copybook layout
     * @param fieldSetMapper mapping function converting parsed FieldSet to entity
     * @return a fully configured FlatFileItemReader ready for a Spring Batch step
     */
    public <T> FlatFileItemReader<T> createReader(
            String readerName,
            Resource resource,
            List<ColumnSpec> columnSpecs,
            FieldSetMapper<T> fieldSetMapper) {

        FlatFileItemReader<T> reader = new FlatFileItemReader<>();
        reader.setName(readerName);
        reader.setResource(resource);
        reader.setEncoding("ASCII");
        // Strict=true: fail if file missing (COBOL OPEN error → ABEND equivalent)
        reader.setStrict(true);
        // No headers in COBOL fixed-width files
        reader.setLinesToSkip(0);

        // Build Range and field-name arrays from column specifications
        List<Range> rangeList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        for (ColumnSpec spec : columnSpecs) {
            rangeList.add(new Range(spec.startPosition(), spec.endPosition()));
            nameList.add(spec.fieldName());
        }

        // Configure tokenizer with column positions matching COBOL FD definitions
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setColumns(rangeList.toArray(new Range[0]));
        tokenizer.setNames(nameList.toArray(new String[0]));
        // Strict=false: handle lines shorter than expected; trailing filler
        // may be truncated in some files (e.g., cardxref.txt has 36-char lines
        // despite RECLN 50 because 14-byte filler is omitted)
        tokenizer.setStrict(false);

        // Wire tokenizer and field mapper together via DefaultLineMapper
        DefaultLineMapper<T> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);

        reader.setLineMapper(lineMapper);
        return reader;
    }

    /**
     * Parses a COBOL zoned decimal string into a Java {@link BigDecimal} with
     * exact precision. Handles the EBCDIC-to-ASCII overpunch encoding where the
     * last byte carries both the sign and the final digit.
     *
     * <p>Overpunch encoding (ASCII representation):</p>
     * <ul>
     *   <li>Positive: '{' = 0, 'A' = 1, 'B' = 2, ... 'I' = 9</li>
     *   <li>Negative: '}' = 0, 'J' = 1, 'K' = 2, ... 'R' = 9</li>
     *   <li>Neutral: '0'-'9' treated as positive</li>
     * </ul>
     *
     * <p>Examples from actual data:</p>
     * <ul>
     *   <li>{@code "00000001940{"} with scale=2 → BigDecimal("194.00")</li>
     *   <li>{@code "0000005047G"} with scale=2 → BigDecimal("504.77")</li>
     *   <li>{@code "0000009190}"} with scale=2 → BigDecimal("-919.00")</li>
     * </ul>
     *
     * @param raw   the raw zoned decimal string from the fixed-width record
     * @param scale number of decimal places (e.g., 2 for COBOL V99)
     * @return exact BigDecimal with specified scale and HALF_UP rounding
     */
    public static BigDecimal parseZonedDecimal(String raw, int scale) {
        // Handle null, empty, or blank input — return zero with correct scale
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }

        // Decode the last character which encodes both sign and final digit
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        boolean negative = false;
        char lastDigit;

        switch (lastChar) {
            // Positive overpunch: { = 0, A-I = 1-9
            case '{' -> lastDigit = '0';
            case 'A' -> lastDigit = '1';
            case 'B' -> lastDigit = '2';
            case 'C' -> lastDigit = '3';
            case 'D' -> lastDigit = '4';
            case 'E' -> lastDigit = '5';
            case 'F' -> lastDigit = '6';
            case 'G' -> lastDigit = '7';
            case 'H' -> lastDigit = '8';
            case 'I' -> lastDigit = '9';
            // Negative overpunch: } = 0, J-R = 1-9
            case '}' -> { lastDigit = '0'; negative = true; }
            case 'J' -> { lastDigit = '1'; negative = true; }
            case 'K' -> { lastDigit = '2'; negative = true; }
            case 'L' -> { lastDigit = '3'; negative = true; }
            case 'M' -> { lastDigit = '4'; negative = true; }
            case 'N' -> { lastDigit = '5'; negative = true; }
            case 'O' -> { lastDigit = '6'; negative = true; }
            case 'P' -> { lastDigit = '7'; negative = true; }
            case 'Q' -> { lastDigit = '8'; negative = true; }
            case 'R' -> { lastDigit = '9'; negative = true; }
            // Neutral: regular digit character treated as positive
            default -> {
                if (lastChar >= '0' && lastChar <= '9') {
                    lastDigit = lastChar;
                } else {
                    throw new IllegalArgumentException(
                            "Invalid zoned decimal overpunch character: '"
                                    + lastChar + "' in value: " + raw);
                }
            }
        }

        // Build pure digits string by replacing encoded last character
        String digits = trimmed.substring(0, trimmed.length() - 1) + lastDigit;

        // Insert decimal point at the correct position
        // For PIC S9(m)V99 with scale=2, last 2 digits are decimal portion
        String numericString;
        if (scale > 0 && digits.length() > scale) {
            int decimalPos = digits.length() - scale;
            numericString = digits.substring(0, decimalPos)
                    + "." + digits.substring(decimalPos);
        } else if (scale > 0) {
            // Edge case: fewer digits than scale — pad with leading zeros
            StringBuilder padded = new StringBuilder();
            int padding = scale - digits.length();
            for (int i = 0; i < padding; i++) {
                padded.append('0');
            }
            numericString = "0." + padded + digits;
        } else {
            // No decimal places (scale=0)
            numericString = digits;
        }

        // Prepend sign for negative values
        if (negative) {
            numericString = "-" + numericString;
        }

        return new BigDecimal(numericString).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Validates that a data line meets the minimum expected record length.
     * Replicates COBOL's implicit record-length validation via FD RECLN.
     * Uses {@code >=} rather than {@code ==} because some files may include
     * trailing whitespace beyond the defined record length.
     *
     * @param line           the raw data line to validate
     * @param expectedLength minimum expected length in characters (COBOL RECLN)
     * @param fileName       source file name for diagnostic messages
     * @throws IllegalArgumentException if the line is null or shorter than expected
     */
    public static void validateRecordLength(String line, int expectedLength,
                                            String fileName) {
        if (line == null) {
            throw new IllegalArgumentException(
                    "Null record in file '" + fileName
                            + "': expected minimum length " + expectedLength
                            + " characters");
        }
        if (line.length() < expectedLength) {
            throw new IllegalArgumentException(
                    "Record too short in file '" + fileName
                            + "': expected minimum " + expectedLength
                            + " characters but got " + line.length());
        }
    }

    // =========================================================================
    // Predefined Column Specifications — one method per COBOL record type
    // These MUST match the COBOL copybook definitions byte-for-byte.
    // =========================================================================

    /**
     * Column specifications for ACCTDATA (CVACT01Y.cpy, ACCOUNT-RECORD, RECLN 300).
     * Maps 12 data fields totaling 122 bytes; 178-byte FILLER is not mapped.
     *
     * @return immutable list of column specifications for account records
     */
    public static List<ColumnSpec> getAccountColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 11, "acctId", FieldType.STRING),
                new ColumnSpec(12, 12, "activeStatus", FieldType.STRING),
                new ColumnSpec(13, 24, "currBal", FieldType.ZONED_DECIMAL, 2),
                new ColumnSpec(25, 36, "creditLimit", FieldType.ZONED_DECIMAL, 2),
                new ColumnSpec(37, 48, "cashCreditLimit", FieldType.ZONED_DECIMAL, 2),
                new ColumnSpec(49, 58, "openDate", FieldType.STRING),
                new ColumnSpec(59, 68, "expirationDate", FieldType.STRING),
                new ColumnSpec(69, 78, "reissueDate", FieldType.STRING),
                new ColumnSpec(79, 90, "currCycCredit", FieldType.ZONED_DECIMAL, 2),
                new ColumnSpec(91, 102, "currCycDebit", FieldType.ZONED_DECIMAL, 2),
                new ColumnSpec(103, 112, "addrZip", FieldType.STRING),
                new ColumnSpec(113, 122, "groupId", FieldType.STRING)
        );
    }

    /**
     * Column specifications for CARDDATA (CVACT02Y.cpy, CARD-RECORD, RECLN 150).
     * Maps 6 data fields totaling 91 bytes; 59-byte FILLER is not mapped.
     *
     * @return immutable list of column specifications for card records
     */
    public static List<ColumnSpec> getCardColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 16, "cardNum", FieldType.STRING),
                new ColumnSpec(17, 27, "accountId", FieldType.STRING),
                new ColumnSpec(28, 30, "cvvCode", FieldType.STRING),
                new ColumnSpec(31, 80, "embossedName", FieldType.STRING),
                new ColumnSpec(81, 90, "expirationDate", FieldType.STRING),
                new ColumnSpec(91, 91, "activeStatus", FieldType.STRING)
        );
    }

    /**
     * Column specifications for CARDXREF (CVACT03Y.cpy, CARD-XREF-RECORD, RECLN 50).
     * Maps 3 data fields totaling 36 bytes; 14-byte FILLER is not mapped.
     * Note: actual file lines are 36 characters (filler omitted) — tokenizer
     * strict mode is false to accommodate shorter lines.
     *
     * @return immutable list of column specifications for card cross-reference records
     */
    public static List<ColumnSpec> getCardXrefColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 16, "cardNum", FieldType.STRING),
                new ColumnSpec(17, 25, "custId", FieldType.STRING),
                new ColumnSpec(26, 36, "accountId", FieldType.STRING)
        );
    }

    /**
     * Column specifications for CUSTDATA (CVCUS01Y.cpy, CUSTOMER-RECORD, RECLN 500).
     * Maps 18 data fields totaling 332 bytes; 168-byte FILLER is not mapped.
     * Contains PII fields (SSN, government ID) that require secure handling.
     *
     * @return immutable list of column specifications for customer records
     */
    public static List<ColumnSpec> getCustomerColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 9, "custId", FieldType.STRING),
                new ColumnSpec(10, 34, "firstName", FieldType.STRING),
                new ColumnSpec(35, 59, "middleName", FieldType.STRING),
                new ColumnSpec(60, 84, "lastName", FieldType.STRING),
                new ColumnSpec(85, 134, "addrLine1", FieldType.STRING),
                new ColumnSpec(135, 184, "addrLine2", FieldType.STRING),
                new ColumnSpec(185, 234, "addrLine3", FieldType.STRING),
                new ColumnSpec(235, 236, "addrStateCode", FieldType.STRING),
                new ColumnSpec(237, 239, "addrCountryCode", FieldType.STRING),
                new ColumnSpec(240, 249, "addrZip", FieldType.STRING),
                new ColumnSpec(250, 264, "phoneNum1", FieldType.STRING),
                new ColumnSpec(265, 279, "phoneNum2", FieldType.STRING),
                new ColumnSpec(280, 288, "ssn", FieldType.STRING),
                new ColumnSpec(289, 308, "govtIssuedId", FieldType.STRING),
                new ColumnSpec(309, 318, "dateOfBirth", FieldType.STRING),
                new ColumnSpec(319, 328, "eftAccountId", FieldType.STRING),
                new ColumnSpec(329, 329, "priCardHolderInd", FieldType.STRING),
                new ColumnSpec(330, 332, "ficoCreditScore", FieldType.STRING)
        );
    }

    /**
     * Column specifications for TRANSACT (CVTRA05Y.cpy, TRAN-RECORD, RECLN 350).
     * Maps 13 data fields totaling 330 bytes; 20-byte FILLER is not mapped.
     * TRAN-AMT is a zoned decimal field (PIC S9(09)V99) requiring overpunch parsing.
     *
     * @return immutable list of column specifications for transaction records
     */
    public static List<ColumnSpec> getTransactionColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 16, "tranId", FieldType.STRING),
                new ColumnSpec(17, 18, "tranTypeCd", FieldType.STRING),
                new ColumnSpec(19, 22, "tranCatCd", FieldType.STRING),
                new ColumnSpec(23, 32, "tranSource", FieldType.STRING),
                new ColumnSpec(33, 132, "tranDesc", FieldType.STRING),
                new ColumnSpec(133, 143, "tranAmt", FieldType.ZONED_DECIMAL, 2),
                new ColumnSpec(144, 152, "merchantId", FieldType.STRING),
                new ColumnSpec(153, 202, "merchantName", FieldType.STRING),
                new ColumnSpec(203, 252, "merchantCity", FieldType.STRING),
                new ColumnSpec(253, 262, "merchantZip", FieldType.STRING),
                new ColumnSpec(263, 278, "cardNum", FieldType.STRING),
                new ColumnSpec(279, 304, "origTimestamp", FieldType.STRING),
                new ColumnSpec(305, 330, "procTimestamp", FieldType.STRING)
        );
    }

    /**
     * Column specifications for DALYTRAN (CVTRA06Y.cpy, DALYTRAN-RECORD, RECLN 350).
     * Shares the same layout as TRANSACT — same field positions and types.
     * Used by the daily transaction batch posting process.
     *
     * @return immutable list of column specifications for daily transaction records
     */
    public static List<ColumnSpec> getDailyTransactionColumnSpecs() {
        // DALYTRAN-RECORD has identical layout to TRAN-RECORD per CVTRA06Y.cpy
        return getTransactionColumnSpecs();
    }

    /**
     * Column specifications for USRSEC (CSUSR01Y.cpy, SEC-USER-DATA, RECLN 80).
     * Maps 5 data fields totaling 57 bytes; 23-byte FILLER is not mapped.
     * SEC-USR-PWD is a sensitive field — plaintext in legacy, BCrypt-hashed in target.
     *
     * @return immutable list of column specifications for user security records
     */
    public static List<ColumnSpec> getUserSecurityColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 8, "userId", FieldType.STRING),
                new ColumnSpec(9, 28, "firstName", FieldType.STRING),
                new ColumnSpec(29, 48, "lastName", FieldType.STRING),
                new ColumnSpec(49, 56, "password", FieldType.STRING),
                new ColumnSpec(57, 57, "userType", FieldType.STRING)
        );
    }

    /**
     * Column specifications for TRANTYPE (7 reference records, ~60 bytes per line).
     * Maps 2 data fields (type code + description); 8-byte FILLER is not mapped.
     *
     * @return immutable list of column specifications for transaction type records
     */
    public static List<ColumnSpec> getTransactionTypeColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 2, "typeCode", FieldType.STRING),
                new ColumnSpec(3, 52, "typeDescription", FieldType.STRING)
        );
    }

    /**
     * Column specifications for TRANCATG (18 category records, ~60 bytes per line).
     * Maps 3 data fields (type code prefix + category code + description);
     * 4-byte FILLER is not mapped.
     *
     * @return immutable list of column specifications for transaction category records
     */
    public static List<ColumnSpec> getTransactionCategoryColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 2, "typeCode", FieldType.STRING),
                new ColumnSpec(3, 6, "categoryCode", FieldType.STRING),
                new ColumnSpec(7, 56, "categoryDescription", FieldType.STRING)
        );
    }

    /**
     * Column specifications for TCATBAL (CVTRA01Y.cpy, TRAN-CAT-BAL-RECORD, RECLN 50).
     * Maps 4 data fields totaling 28 bytes; 22-byte FILLER is not mapped.
     * TRAN-CAT-BAL is a zoned decimal field (PIC S9(09)V99).
     *
     * @return immutable list of column specifications for category balance records
     */
    public static List<ColumnSpec> getCategoryBalanceColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 11, "accountId", FieldType.STRING),
                new ColumnSpec(12, 13, "typeCode", FieldType.STRING),
                new ColumnSpec(14, 17, "categoryCode", FieldType.STRING),
                new ColumnSpec(18, 28, "balance", FieldType.ZONED_DECIMAL, 2)
        );
    }

    /**
     * Column specifications for DISCGRP (CVTRA02Y.cpy, DIS-GROUP-RECORD, RECLN 50).
     * Maps 4 data fields totaling 22 bytes; 28-byte FILLER is not mapped.
     * DIS-INT-RATE is a zoned decimal field (PIC S9(04)V99).
     *
     * @return immutable list of column specifications for discount group records
     */
    public static List<ColumnSpec> getDiscountGroupColumnSpecs() {
        return List.of(
                new ColumnSpec(1, 10, "groupId", FieldType.STRING),
                new ColumnSpec(11, 12, "tranTypeCode", FieldType.STRING),
                new ColumnSpec(13, 16, "tranCatCode", FieldType.STRING),
                new ColumnSpec(17, 22, "interestRate", FieldType.ZONED_DECIMAL, 2)
        );
    }
}

package com.carddemo.batch.reader;

import com.carddemo.entity.Account;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.batch.item.file.transform.FieldSet;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Spring Batch FlatFileItemReader configuration for reading account data from fixed-width text files.
 * 
 * <p>This reader configuration processes account data migrated from mainframe VSAM KSDS ACCTFILE
 * (app/data/ASCII/acctdata.txt), converting 300-byte fixed-width records matching COBOL CVACT01Y.cpy
 * copybook structure to Account entity objects for batch processing by AccountDataLoadJob.</p>
 * 
 * <p><strong>Source File Mapping:</strong></p>
 * <ul>
 *   <li>COBOL Copybook: app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD structure, 300-byte RECLN)</li>
 *   <li>Data File: app/data/ASCII/acctdata.txt (EBCDIC-to-ASCII converted fixed-width data)</li>
 *   <li>Target Entity: {@link Account} (JPA entity with BigDecimal monetary fields)</li>
 * </ul>
 * 
 * <p><strong>Fixed-Width Field Layout (300 bytes total):</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>Field Name</th>
 *     <th>COBOL Definition</th>
 *     <th>Positions</th>
 *     <th>Length</th>
 *     <th>Java Type</th>
 *   </tr>
 *   <tr>
 *     <td>accountId</td>
 *     <td>ACCT-ID PIC 9(11)</td>
 *     <td>1-11</td>
 *     <td>11</td>
 *     <td>Long</td>
 *   </tr>
 *   <tr>
 *     <td>activeStatus</td>
 *     <td>ACCT-ACTIVE-STATUS PIC X(01)</td>
 *     <td>12</td>
 *     <td>1</td>
 *     <td>String</td>
 *   </tr>
 *   <tr>
 *     <td>currentBalance</td>
 *     <td>ACCT-CURR-BAL PIC S9(10)V99</td>
 *     <td>13-24</td>
 *     <td>12</td>
 *     <td>BigDecimal (scale=2)</td>
 *   </tr>
 *   <tr>
 *     <td>creditLimit</td>
 *     <td>ACCT-CREDIT-LIMIT PIC S9(10)V99</td>
 *     <td>25-36</td>
 *     <td>12</td>
 *     <td>BigDecimal (scale=2)</td>
 *   </tr>
 *   <tr>
 *     <td>cashCreditLimit</td>
 *     <td>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99</td>
 *     <td>37-48</td>
 *     <td>12</td>
 *     <td>BigDecimal (scale=2)</td>
 *   </tr>
 *   <tr>
 *     <td>openDate</td>
 *     <td>ACCT-OPEN-DATE PIC X(10)</td>
 *     <td>49-58</td>
 *     <td>10</td>
 *     <td>LocalDate</td>
 *   </tr>
 *   <tr>
 *     <td>expirationDate</td>
 *     <td>ACCT-EXPIRAION-DATE PIC X(10)</td>
 *     <td>59-68</td>
 *     <td>10</td>
 *     <td>LocalDate</td>
 *   </tr>
 *   <tr>
 *     <td>reissueDate</td>
 *     <td>ACCT-REISSUE-DATE PIC X(10)</td>
 *     <td>69-78</td>
 *     <td>10</td>
 *     <td>LocalDate</td>
 *   </tr>
 *   <tr>
 *     <td>currentCycleCredit</td>
 *     <td>ACCT-CURR-CYC-CREDIT PIC S9(10)V99</td>
 *     <td>79-90</td>
 *     <td>12</td>
 *     <td>BigDecimal (scale=2)</td>
 *   </tr>
 *   <tr>
 *     <td>currentCycleDebit</td>
 *     <td>ACCT-CURR-CYC-DEBIT PIC S9(10)V99</td>
 *     <td>91-102</td>
 *     <td>12</td>
 *     <td>BigDecimal (scale=2)</td>
 *   </tr>
 *   <tr>
 *     <td>addressZip</td>
 *     <td>ACCT-ADDR-ZIP PIC X(10)</td>
 *     <td>103-112</td>
 *     <td>10</td>
 *     <td>String</td>
 *   </tr>
 *   <tr>
 *     <td>groupId</td>
 *     <td>ACCT-GROUP-ID PIC X(10)</td>
 *     <td>113-122</td>
 *     <td>10</td>
 *     <td>String</td>
 *   </tr>
 *   <tr>
 *     <td>filler</td>
 *     <td>FILLER PIC X(178)</td>
 *     <td>123-300</td>
 *     <td>178</td>
 *     <td>(padding, not mapped)</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>CRITICAL: COBOL Display Format Monetary Field Handling</strong></p>
 * <p>Monetary fields in the source data file use COBOL display format (PIC S9(10)V99) where
 * the last byte encodes BOTH the final digit AND the sign using special characters:</p>
 * 
 * <p><strong>Positive Sign Encoding:</strong></p>
 * <ul>
 *   <li>'{' (left brace) = digit 0, positive → converts to "0"</li>
 *   <li>'A' = digit 1, positive → converts to "1"</li>
 *   <li>'B' = digit 2, positive → converts to "2"</li>
 *   <li>'C' = digit 3, positive → converts to "3"</li>
 *   <li>'D' = digit 4, positive → converts to "4"</li>
 *   <li>'E' = digit 5, positive → converts to "5"</li>
 *   <li>'F' = digit 6, positive → converts to "6"</li>
 *   <li>'G' = digit 7, positive → converts to "7"</li>
 *   <li>'H' = digit 8, positive → converts to "8"</li>
 *   <li>'I' = digit 9, positive → converts to "9"</li>
 * </ul>
 * 
 * <p><strong>Negative Sign Encoding:</strong></p>
 * <ul>
 *   <li>'}' (right brace) = digit 0, negative → converts to "-0"</li>
 *   <li>'J' = digit 1, negative → converts to "-1"</li>
 *   <li>'K' = digit 2, negative → converts to "-2"</li>
 *   <li>'L' = digit 3, negative → converts to "-3"</li>
 *   <li>'M' = digit 4, negative → converts to "-4"</li>
 *   <li>'N' = digit 5, negative → converts to "-5"</li>
 *   <li>'O' = digit 6, negative → converts to "-6"</li>
 *   <li>'P' = digit 7, negative → converts to "-7"</li>
 *   <li>'Q' = digit 8, negative → converts to "-8"</li>
 *   <li>'R' = digit 9, negative → converts to "-9"</li>
 * </ul>
 * 
 * <p><strong>Example Conversions:</strong></p>
 * <ul>
 *   <li>"00000001940{" → 19.40 (positive, last char '{' = 0)</li>
 *   <li>"00000020200{" → 202.00 (positive, last char '{' = 0)</li>
 *   <li>"00000010200{" → 102.00 (positive, last char '{' = 0)</li>
 *   <li>"00000123456}" → -1234.56 (negative, last char '}' = 6)</li>
 *   <li>"00000078912C" → 789.13 (positive, last char 'C' = 3)</li>
 * </ul>
 * 
 * <p><strong>BigDecimal Precision Requirements:</strong></p>
 * <p>All monetary fields are converted to BigDecimal with:</p>
 * <ul>
 *   <li>Precision: 12 (matching PIC S9(10)V99 total digits)</li>
 *   <li>Scale: 2 (matching V99 decimal places)</li>
 *   <li>RoundingMode: HALF_UP (matching COBOL rounding behavior)</li>
 * </ul>
 * <p>This ensures exact decimal precision preservation from COBOL COMP-3 packed decimal format,
 * producing identical financial calculation results as the mainframe system.</p>
 * 
 * <p><strong>Date Format:</strong></p>
 * <p>Date fields use ISO-8601 format (YYYY-MM-DD) and are parsed to LocalDate for timezone-agnostic
 * date storage, replacing COBOL PIC X(10) date fields.</p>
 * 
 * <p><strong>Character Encoding:</strong></p>
 * <p>Files are read with UTF-8 encoding as they have been converted from mainframe EBCDIC to ASCII
 * during the data migration process.</p>
 * 
 * <p><strong>Integration with AccountDataLoadJob:</strong></p>
 * <p>This reader bean is injected into AccountDataLoadJob and used as the ItemReader in the
 * batch step configuration for reading and processing account data files.</p>
 * 
 * <p><strong>Error Handling:</strong></p>
 * <p>The reader is configured with strict(true) to fail fast on parse errors, ensuring data
 * quality validation during batch processing. Invalid records will cause the job to fail with
 * detailed error messages for investigation and correction.</p>
 * 
 * @see Account
 * @see AccountFieldSetMapper
 * @see <a href="Section 0.4">Agent Action Plan - Source Files app/data/ASCII/acctdata.txt and app/cpy/CVACT01Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - AccountDataLoadJob</a>
 * @see <a href="Section 0.10">Special Instructions - COBOL COMP-3 to Java BigDecimal Precision Mapping</a>
 */
@Configuration
public class AccountDataReader {

    /**
     * Date formatter for parsing COBOL date fields in YYYY-MM-DD format.
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Creates and configures a FlatFileItemReader for reading fixed-width account data files.
     * 
     * <p>This bean is automatically registered with Spring Batch and can be injected into
     * AccountDataLoadJob for use as an ItemReader in the batch step configuration.</p>
     * 
     * <p><strong>Configuration Details:</strong></p>
     * <ul>
     *   <li>Reader Name: "accountDataReader" (for batch metadata identification)</li>
     *   <li>Resource: ClassPathResource("data/acctdata.txt") with FileSystemResource fallback</li>
     *   <li>Encoding: UTF-8 (EBCDIC-to-ASCII converted data)</li>
     *   <li>Tokenizer: FixedLengthTokenizer with exact field ranges matching COBOL copybook</li>
     *   <li>Mapper: Custom AccountFieldSetMapper with COBOL display format handling</li>
     *   <li>Strict Mode: true (fail fast on parse errors)</li>
     *   <li>Lines to Skip: 0 (no header line)</li>
     * </ul>
     * 
     * <p><strong>File Location Resolution:</strong></p>
     * <p>The reader first attempts to load the file from the classpath (src/main/resources/data/).
     * If not found, it falls back to a file system resource at the specified absolute path.
     * This supports both test execution (classpath) and production batch execution (file system).</p>
     * 
     * @return Configured FlatFileItemReader for Account entities
     */
    @Bean
    public FlatFileItemReader<Account> accountDataReader() {
        // Determine resource location - try classpath first, then file system
        org.springframework.core.io.Resource resource;
        
        // Check if file exists in classpath (for test execution)
        ClassPathResource classPathResource = new ClassPathResource("data/acctdata.txt");
        if (classPathResource.exists()) {
            resource = classPathResource;
        } else {
            // Fall back to file system resource (for production batch execution)
            File dataFile = new File("src/main/resources/data/acctdata.txt");
            resource = new FileSystemResource(dataFile);
        }

        // Configure FixedLengthTokenizer with exact field ranges from COBOL copybook
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setNames(
            "accountId",           // ACCT-ID
            "activeStatus",        // ACCT-ACTIVE-STATUS
            "currentBalance",      // ACCT-CURR-BAL
            "creditLimit",         // ACCT-CREDIT-LIMIT
            "cashCreditLimit",     // ACCT-CASH-CREDIT-LIMIT
            "openDate",            // ACCT-OPEN-DATE
            "expirationDate",      // ACCT-EXPIRAION-DATE
            "reissueDate",         // ACCT-REISSUE-DATE
            "currentCycleCredit",  // ACCT-CURR-CYC-CREDIT
            "currentCycleDebit",   // ACCT-CURR-CYC-DEBIT
            "addressZip",          // ACCT-ADDR-ZIP
            "groupId"              // ACCT-GROUP-ID
        );
        
        // Set column ranges matching COBOL copybook structure (1-indexed positions)
        tokenizer.setColumns(
            new Range(1, 11),      // accountId: positions 1-11 (11 bytes)
            new Range(12, 12),     // activeStatus: position 12 (1 byte)
            new Range(13, 24),     // currentBalance: positions 13-24 (12 bytes)
            new Range(25, 36),     // creditLimit: positions 25-36 (12 bytes)
            new Range(37, 48),     // cashCreditLimit: positions 37-48 (12 bytes)
            new Range(49, 58),     // openDate: positions 49-58 (10 bytes)
            new Range(59, 68),     // expirationDate: positions 59-68 (10 bytes)
            new Range(69, 78),     // reissueDate: positions 69-78 (10 bytes)
            new Range(79, 90),     // currentCycleCredit: positions 79-90 (12 bytes)
            new Range(91, 102),    // currentCycleDebit: positions 91-102 (12 bytes)
            new Range(103, 112),   // addressZip: positions 103-112 (10 bytes)
            new Range(113, 122)    // groupId: positions 113-122 (10 bytes)
            // Positions 123-300 are FILLER (padding), not mapped
        );
        
        tokenizer.setStrict(true);

        // Build and return configured FlatFileItemReader
        return new FlatFileItemReaderBuilder<Account>()
            .name("accountDataReader")
            .resource(resource)
            .lineTokenizer(tokenizer)
            .fieldSetMapper(new AccountFieldSetMapper())
            .encoding("UTF-8")
            .linesToSkip(0)
            .strict(true)
            .build();
    }

    /**
     * Custom FieldSetMapper for converting fixed-width text fields to Account entity.
     * 
     * <p>This mapper handles the special COBOL display format for signed monetary fields,
     * where the last character encodes both the final digit and the sign using special
     * characters ('{', 'A'-'I' for positive, '}', 'J'-'R' for negative).</p>
     * 
     * <p>All monetary values are converted to BigDecimal with exact scale=2 and
     * RoundingMode.HALF_UP to preserve COBOL COMP-3 packed decimal precision.</p>
     */
    public static class AccountFieldSetMapper implements FieldSetMapper<Account> {

        /**
         * Maps a FieldSet (parsed fixed-width record) to an Account entity.
         * 
         * <p>Performs the following transformations:</p>
         * <ul>
         *   <li>accountId: String to Long conversion with leading zero handling</li>
         *   <li>activeStatus: Direct string mapping (trimmed)</li>
         *   <li>Monetary fields: COBOL display format to BigDecimal with scale=2</li>
         *   <li>Date fields: ISO date string to LocalDate</li>
         *   <li>String fields: Direct mapping with whitespace trimming</li>
         * </ul>
         * 
         * @param fieldSet Parsed field set from fixed-width record
         * @return Populated Account entity
         * @throws IllegalArgumentException if field values are invalid or cannot be parsed
         */
        @Override
        public Account mapFieldSet(FieldSet fieldSet) {
            try {
                Account account = new Account();

                // Map accountId (PIC 9(11)) - numeric field with potential leading zeros
                String accountIdStr = fieldSet.readString("accountId").trim();
                if (!accountIdStr.isEmpty()) {
                    account.setAccountId(Long.parseLong(accountIdStr));
                }

                // Map activeStatus (PIC X(01)) - single character
                account.setActiveStatus(fieldSet.readString("activeStatus").trim());

                // Map monetary fields with COBOL display format conversion
                account.setCurrentBalance(
                    parseCobolDisplayMoney(fieldSet.readString("currentBalance"))
                );
                account.setCreditLimit(
                    parseCobolDisplayMoney(fieldSet.readString("creditLimit"))
                );
                account.setCashCreditLimit(
                    parseCobolDisplayMoney(fieldSet.readString("cashCreditLimit"))
                );

                // Map date fields (PIC X(10)) - YYYY-MM-DD format
                account.setOpenDate(
                    parseDate(fieldSet.readString("openDate"))
                );
                account.setExpirationDate(
                    parseDate(fieldSet.readString("expirationDate"))
                );
                account.setReissueDate(
                    parseDate(fieldSet.readString("reissueDate"))
                );

                // Map cycle monetary fields with COBOL display format conversion
                account.setCurrentCycleCredit(
                    parseCobolDisplayMoney(fieldSet.readString("currentCycleCredit"))
                );
                account.setCurrentCycleDebit(
                    parseCobolDisplayMoney(fieldSet.readString("currentCycleDebit"))
                );

                // Map string fields (PIC X(10))
                account.setAddressZip(fieldSet.readString("addressZip").trim());
                account.setGroupId(fieldSet.readString("groupId").trim());

                return account;

            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "Failed to map FieldSet to Account entity: " + e.getMessage(), e
                );
            }
        }

        /**
         * Parses a COBOL display format monetary field to BigDecimal with exact precision.
         * 
         * <p>COBOL display format (PIC S9(10)V99) encodes signed numeric values where the
         * last character represents both the final digit and the sign:</p>
         * <ul>
         *   <li>'{' through 'I' = digits 0-9, positive</li>
         *   <li>'}' through 'R' = digits 0-9, negative</li>
         * </ul>
         * 
         * <p><strong>Algorithm:</strong></p>
         * <ol>
         *   <li>Extract last character from the field</li>
         *   <li>Convert last character to digit and determine sign</li>
         *   <li>Replace last character with the digit</li>
         *   <li>Insert decimal point 2 positions from the end (V99 = 2 decimals)</li>
         *   <li>Convert to BigDecimal with scale=2, RoundingMode.HALF_UP</li>
         *   <li>Apply sign (negate if negative)</li>
         * </ol>
         * 
         * <p><strong>Example Transformations:</strong></p>
         * <ul>
         *   <li>"00000001940{" → "000000019400" → "0000000194.00" → BigDecimal(19.40)</li>
         *   <li>"00000020200{" → "000000202000" → "0000002020.00" → BigDecimal(202.00)</li>
         *   <li>"00000123456}" → "000001234566" → "0000012345.66" → BigDecimal(-12345.66)</li>
         * </ul>
         * 
         * @param cobolValue COBOL display format monetary value (12 characters)
         * @return BigDecimal with scale=2 and exact precision matching COBOL COMP-3
         * @throws IllegalArgumentException if the value cannot be parsed
         */
        private BigDecimal parseCobolDisplayMoney(String cobolValue) {
            if (cobolValue == null || cobolValue.trim().isEmpty()) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }

            String trimmedValue = cobolValue.trim();
            if (trimmedValue.isEmpty()) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }

            try {
                // Extract the last character which encodes digit + sign
                char lastChar = trimmedValue.charAt(trimmedValue.length() - 1);
                
                // Determine the digit and sign from the last character
                int lastDigit;
                boolean isNegative;

                if (lastChar >= '{' && lastChar <= 'I') {
                    // Positive values: '{' = 0, 'A' = 1, 'B' = 2, ..., 'I' = 9
                    if (lastChar == '{') {
                        lastDigit = 0;
                    } else {
                        lastDigit = lastChar - 'A' + 1;
                    }
                    isNegative = false;
                } else if (lastChar >= '}' && lastChar <= 'R') {
                    // Negative values: '}' = 0, 'J' = 1, 'K' = 2, ..., 'R' = 9
                    if (lastChar == '}') {
                        lastDigit = 0;
                    } else {
                        lastDigit = lastChar - 'J' + 1;
                    }
                    isNegative = true;
                } else if (lastChar >= '0' && lastChar <= '9') {
                    // Already a digit - assume positive (unsigned field or already converted)
                    lastDigit = lastChar - '0';
                    isNegative = false;
                } else {
                    throw new IllegalArgumentException(
                        "Invalid COBOL display format last character: '" + lastChar + 
                        "' in value: " + cobolValue
                    );
                }

                // Replace the last character with the actual digit
                String numericPart = trimmedValue.substring(0, trimmedValue.length() - 1) + lastDigit;

                // Remove leading zeros but keep at least one digit before decimal
                numericPart = numericPart.replaceFirst("^0+(?!$)", "");
                if (numericPart.isEmpty()) {
                    numericPart = "0";
                }

                // Insert decimal point 2 positions from the end (V99 = 2 decimal places)
                String withDecimal;
                if (numericPart.length() <= 2) {
                    // Less than 3 digits - pad with leading zeros
                    numericPart = String.format("%3s", numericPart).replace(' ', '0');
                    withDecimal = "0." + numericPart.substring(numericPart.length() - 2);
                } else {
                    int decimalPos = numericPart.length() - 2;
                    withDecimal = numericPart.substring(0, decimalPos) + "." + 
                                 numericPart.substring(decimalPos);
                }

                // Convert to BigDecimal with exact scale=2 and RoundingMode.HALF_UP
                BigDecimal result = new BigDecimal(withDecimal).setScale(2, RoundingMode.HALF_UP);

                // Apply sign
                if (isNegative) {
                    result = result.negate();
                }

                return result;

            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Failed to parse COBOL display format monetary value: " + cobolValue, e
                );
            }
        }

        /**
         * Parses a date string in YYYY-MM-DD format to LocalDate.
         * 
         * <p>Handles empty or whitespace-only date fields by returning null,
         * supporting optional date fields in the account record.</p>
         * 
         * @param dateStr Date string in ISO format (YYYY-MM-DD)
         * @return LocalDate instance, or null if dateStr is empty
         * @throws DateTimeParseException if the date string cannot be parsed
         */
        private LocalDate parseDate(String dateStr) {
            if (dateStr == null || dateStr.trim().isEmpty()) {
                return null;
            }
            
            String trimmedDate = dateStr.trim();
            if (trimmedDate.isEmpty()) {
                return null;
            }

            try {
                return LocalDate.parse(trimmedDate, DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                throw new DateTimeParseException(
                    "Failed to parse date value: " + dateStr, trimmedDate, e.getErrorIndex()
                );
            }
        }
    }
}

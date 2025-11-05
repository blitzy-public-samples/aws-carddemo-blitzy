package com.carddemo.constants;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter for BalanceType enum to database CHAR(1) column conversion.
 * 
 * <p>This converter enables bidirectional mapping between the Java BalanceType enum
 * (CURRENT, AVAILABLE, PENDING, HISTORICAL) and the PostgreSQL database CHAR(1) column
 * storing single-character codes ('C', 'A', 'P', 'H').</p>
 * 
 * <p><b>Purpose:</b></p>
 * <ul>
 *   <li>Convert enum to single-character code for compact database storage</li>
 *   <li>Convert database character code back to type-safe enum value</li>
 *   <li>Maintain backward compatibility with COBOL VSAM single-character indicators</li>
 *   <li>Enforce type safety while preserving database space efficiency</li>
 * </ul>
 * 
 * <p><b>Database Mapping:</b></p>
 * <pre>
 * PostgreSQL: balance_type CHAR(1)
 * Java:       BalanceType enum
 * Converter:  BalanceType <-> Character code
 * 
 * Examples:
 *   BalanceType.CURRENT    <-> 'C'
 *   BalanceType.AVAILABLE  <-> 'A'
 *   BalanceType.PENDING    <-> 'P'
 *   BalanceType.HISTORICAL <-> 'H'
 * </pre>
 * 
 * <p><b>Usage in Entity:</b></p>
 * <pre>
 * &#64;Entity
 * public class AccountBalance {
 *     
 *     &#64;Convert(converter = BalanceTypeConverter.class)
 *     &#64;Column(name = "balance_type", length = 1, nullable = false)
 *     private BalanceType balanceType;
 *     
 *     // ... other fields
 * }
 * </pre>
 * 
 * <p><b>Conversion Process:</b></p>
 * <ol>
 *   <li><b>To Database (convertToDatabaseColumn):</b>
 *       <ul>
 *         <li>Input: BalanceType enum value (e.g., BalanceType.HISTORICAL)</li>
 *         <li>Extract single-character code via getCode() method</li>
 *         <li>Convert char to String for database storage</li>
 *         <li>Output: String "H" stored in CHAR(1) column</li>
 *       </ul>
 *   </li>
 *   <li><b>From Database (convertToEntityAttribute):</b>
 *       <ul>
 *         <li>Input: String value from database (e.g., "H")</li>
 *         <li>Validate non-null and single-character</li>
 *         <li>Use BalanceType.fromCode() to lookup enum by code</li>
 *         <li>Output: BalanceType.HISTORICAL enum value</li>
 *       </ul>
 *   </li>
 * </ol>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li>Null database values: Returns null (handled by JPA nullable constraint)</li>
 *   <li>Invalid codes: IllegalArgumentException thrown by BalanceType.fromCode()</li>
 *   <li>Multi-character strings: IllegalArgumentException for data integrity</li>
 * </ul>
 * 
 * <p><b>Automatic Conversion:</b></p>
 * <p>The @Converter(autoApply = false) annotation means this converter must be
 * explicitly specified on entity fields using @Convert annotation. This provides
 * explicit control over which BalanceType fields use the converter versus standard
 * JPA enum handling.</p>
 * 
 * <p><b>COBOL Migration Notes:</b></p>
 * <p>This converter preserves the single-character balance type indicators used in
 * the COBOL VSAM files per Section 0.2 and 0.9 requirements:</p>
 * <pre>
 * COBOL: 01 BALANCE-TYPE PIC X(1).
 *        88 BAL-CURRENT    VALUE 'C'.
 *        88 BAL-AVAILABLE  VALUE 'A'.
 *        88 BAL-PENDING    VALUE 'P'.
 *        88 BAL-HISTORICAL VALUE 'H'.
 * 
 * Java:  BalanceType enum with character codes mapped via this converter
 * </pre>
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 * @see BalanceType
 * @see com.carddemo.entity.AccountBalance
 */
@Converter(autoApply = false)
public class BalanceTypeConverter implements AttributeConverter<BalanceType, String> {
    
    /**
     * Converts the BalanceType enum value to database column representation.
     * 
     * <p>This method is called by JPA when persisting or updating an entity with
     * a BalanceType field. It extracts the single-character code from the enum
     * and returns it as a String for storage in the CHAR(1) database column.</p>
     * 
     * <p><b>Conversion Logic:</b></p>
     * <ol>
     *   <li>Check if enum value is null (allowed for optional fields)</li>
     *   <li>Call balanceType.getCode() to get single-character code</li>
     *   <li>Convert char to String using String.valueOf()</li>
     *   <li>Return String for database storage</li>
     * </ol>
     * 
     * <p><b>Examples:</b></p>
     * <pre>
     * BalanceType.CURRENT    -> "C"
     * BalanceType.AVAILABLE  -> "A"
     * BalanceType.PENDING    -> "P"
     * BalanceType.HISTORICAL -> "H"
     * null                   -> null
     * </pre>
     * 
     * @param balanceType the BalanceType enum value to convert (may be null)
     * @return Single-character String code ('C', 'A', 'P', 'H') or null if input is null
     */
    @Override
    public String convertToDatabaseColumn(BalanceType balanceType) {
        if (balanceType == null) {
            return null;
        }
        return String.valueOf(balanceType.getCode());
    }
    
    /**
     * Converts the database column value to BalanceType enum.
     * 
     * <p>This method is called by JPA when loading an entity from the database.
     * It takes the single-character code stored in the database and converts it
     * back to the corresponding BalanceType enum value using the enum's lookup method.</p>
     * 
     * <p><b>Conversion Logic:</b></p>
     * <ol>
     *   <li>Check if database value is null or empty</li>
     *   <li>Validate that String contains exactly one character</li>
     *   <li>Call BalanceType.fromString() to lookup enum by code</li>
     *   <li>Return corresponding BalanceType enum value</li>
     * </ol>
     * 
     * <p><b>Examples:</b></p>
     * <pre>
     * "C" -> BalanceType.CURRENT
     * "A" -> BalanceType.AVAILABLE
     * "P" -> BalanceType.PENDING
     * "H" -> BalanceType.HISTORICAL
     * null -> null
     * </pre>
     * 
     * <p><b>Error Conditions:</b></p>
     * <ul>
     *   <li>Multi-character strings: Throws IllegalArgumentException</li>
     *   <li>Invalid codes (not C/A/P/H): Throws IllegalArgumentException via BalanceType.fromString()</li>
     *   <li>Empty strings: Returns null (treated as missing value)</li>
     * </ul>
     * 
     * @param dbData the single-character String from database ('C', 'A', 'P', 'H') or null
     * @return Corresponding BalanceType enum value, or null if input is null/empty
     * @throws IllegalArgumentException if dbData contains invalid or multi-character code
     */
    @Override
    public BalanceType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        return BalanceType.fromString(dbData);
    }
}

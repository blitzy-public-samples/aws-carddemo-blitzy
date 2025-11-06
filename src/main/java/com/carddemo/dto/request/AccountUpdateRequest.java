package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AccountUpdateRequest DTO
 * 
 * <p>Data Transfer Object for account update requests in the CardDemo application.
 * This DTO replaces the CICS COMMAREA structure used in the mainframe CAUP (Account Update) 
 * transaction and serves as the inbound contract for the PUT /api/accounts/{id} REST endpoint.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This class is derived from the following COBOL structures:</p>
 * <ul>
 *   <li><b>Copybook:</b> CVACT01Y.cpy - ACCOUNT-RECORD structure (lines 4-17)</li>
 *   <li><b>BMS Mapset:</b> COACTUP.bms - CACTUPA map for account update screen (lines 84-173)</li>
 * </ul>
 * 
 * <h2>Field Mappings</h2>
 * <table border="1">
 *   <tr>
 *     <th>Java Field</th>
 *     <th>COBOL Field</th>
 *     <th>BMS Field</th>
 *     <th>Type</th>
 *     <th>Validation</th>
 *   </tr>
 *   <tr>
 *     <td>accountId</td>
 *     <td>ACCT-ID PIC 9(11)</td>
 *     <td>ACCTSID</td>
 *     <td>Long</td>
 *     <td>@NotNull, 11 digits max</td>
 *   </tr>
 *   <tr>
 *     <td>creditLimit</td>
 *     <td>ACCT-CREDIT-LIMIT PIC S9(10)V99</td>
 *     <td>ACRDLIM</td>
 *     <td>BigDecimal</td>
 *     <td>@NotNull, @Digits(integer=10, fraction=2), range validation</td>
 *   </tr>
 *   <tr>
 *     <td>cashCreditLimit</td>
 *     <td>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99</td>
 *     <td>ACSHLIM</td>
 *     <td>BigDecimal</td>
 *     <td>@NotNull, @Digits(integer=10, fraction=2), range validation</td>
 *   </tr>
 *   <tr>
 *     <td>activeStatus</td>
 *     <td>ACCT-ACTIVE-STATUS PIC X(01)</td>
 *     <td>ACSTTUS</td>
 *     <td>String</td>
 *     <td>@Pattern(regexp="^[YN]$") - must be 'Y' or 'N'</td>
 *   </tr>
 * </table>
 * 
 * <h2>COBOL COMP-3 Packed Decimal to BigDecimal Precision</h2>
 * <p>The monetary fields (creditLimit and cashCreditLimit) use {@link BigDecimal} to preserve
 * the exact precision of COBOL COMP-3 packed decimal fields defined as PIC S9(10)V99:</p>
 * <ul>
 *   <li><b>Scale:</b> 2 decimal places (matching V99 in COBOL)</li>
 *   <li><b>Precision:</b> 10 integer digits + 2 decimal places = 12 total digits</li>
 *   <li><b>Rounding:</b> RoundingMode.HALF_UP (matches COBOL rounding behavior)</li>
 *   <li><b>Range:</b> -99,999,999,999.99 to 99,999,999,999.99</li>
 * </ul>
 * 
 * <h2>Validation Rules</h2>
 * <p>All validation annotations replicate the COBOL PIC clause restrictions and BMS field 
 * definitions from the mainframe application:</p>
 * <ul>
 *   <li><b>accountId:</b> Required field, maps to 11-digit numeric ACCT-ID</li>
 *   <li><b>creditLimit:</b> Required, must be between 0.00 and 99,999,999,999.99 with exactly 2 decimal places</li>
 *   <li><b>cashCreditLimit:</b> Required, same precision and range as creditLimit</li>
 *   <li><b>activeStatus:</b> Required, must be exactly 'Y' (Active) or 'N' (Inactive)</li>
 * </ul>
 * 
 * <h2>Transaction Context</h2>
 * <p>This DTO is used within Spring @Transactional boundaries to maintain ACID properties
 * equivalent to the original CICS transaction processing. The AccountUpdateService validates
 * the request and performs database updates atomically, with automatic rollback on exceptions.</p>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // JSON request body for PUT /api/accounts/{id}
 * {
 *   "accountId": 12345678901,
 *   "creditLimit": "25000.00",
 *   "cashCreditLimit": "5000.00",
 *   "activeStatus": "Y"
 * }
 * 
 * // Java usage in controller
 * @PutMapping("/api/accounts/{id}")
 * public ResponseEntity<AccountResponse> updateAccount(
 *         @PathVariable Long id,
 *         @Valid @RequestBody AccountUpdateRequest request) {
 *     return ResponseEntity.ok(accountUpdateService.updateAccount(request));
 * }
 * }</pre>
 * 
 * @see com.carddemo.entity.Account
 * @see com.carddemo.service.account.AccountUpdateService
 * @see com.carddemo.controller.AccountController
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountUpdateRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Account identification number.
     * 
     * <p><b>COBOL Mapping:</b> ACCT-ID PIC 9(11) from CVACT01Y.cpy line 5</p>
     * <p><b>BMS Field:</b> ACCTSID from COACTUP.bms line 84, LENGTH=11</p>
     * 
     * <p>This field uniquely identifies the account being updated. Must be a valid
     * 11-digit account number that exists in the database.</p>
     */
    @JsonProperty("accountId")
    @NotNull(message = "Account ID is required")
    private Long accountId;
    
    /**
     * Credit limit for the account.
     * 
     * <p><b>COBOL Mapping:</b> ACCT-CREDIT-LIMIT PIC S9(10)V99 from CVACT01Y.cpy line 8</p>
     * <p><b>BMS Field:</b> ACRDLIM from COACTUP.bms line 132, LENGTH=15</p>
     * 
     * <p>This field represents the maximum credit amount available to the account holder.
     * Uses BigDecimal to maintain exact COBOL COMP-3 packed decimal precision with
     * 10 integer digits and 2 decimal places (scale=2).</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field (cannot be null)</li>
     *   <li>Minimum value: 0.00 (no negative credit limits)</li>
     *   <li>Maximum value: 99,999,999,999.99 (matches COBOL PIC S9(10)V99 range)</li>
     *   <li>Exactly 2 decimal places required</li>
     *   <li>Maximum 10 integer digits</li>
     * </ul>
     * 
     * <p><b>Example values:</b> "5000.00", "25000.00", "100000.00"</p>
     */
    @JsonProperty("creditLimit")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @NotNull(message = "Credit limit is required")
    @DecimalMin(value = "0.00", message = "Credit limit must be at least 0.00")
    @DecimalMax(value = "99999999999.99", message = "Credit limit cannot exceed 99,999,999,999.99")
    @Digits(integer = 10, fraction = 2, message = "Credit limit must have at most 10 integer digits and exactly 2 decimal places")
    private BigDecimal creditLimit;
    
    /**
     * Cash credit limit for the account.
     * 
     * <p><b>COBOL Mapping:</b> ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 from CVACT01Y.cpy line 9</p>
     * <p><b>BMS Field:</b> ACSHLIM from COACTUP.bms line 170, LENGTH=15</p>
     * 
     * <p>This field represents the maximum cash advance amount available to the account holder.
     * Typically set to a lower value than the regular credit limit. Uses BigDecimal to maintain
     * exact COBOL COMP-3 packed decimal precision with 10 integer digits and 2 decimal places.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field (cannot be null)</li>
     *   <li>Minimum value: 0.00 (no negative cash credit limits)</li>
     *   <li>Maximum value: 99,999,999,999.99 (matches COBOL PIC S9(10)V99 range)</li>
     *   <li>Exactly 2 decimal places required</li>
     *   <li>Maximum 10 integer digits</li>
     * </ul>
     * 
     * <p><b>Business Rule:</b> Cash credit limit should typically be less than or equal to
     * the regular credit limit, though this constraint is enforced in the service layer
     * rather than through bean validation.</p>
     * 
     * <p><b>Example values:</b> "1000.00", "5000.00", "10000.00"</p>
     */
    @JsonProperty("cashCreditLimit")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @NotNull(message = "Cash credit limit is required")
    @DecimalMin(value = "0.00", message = "Cash credit limit must be at least 0.00")
    @DecimalMax(value = "99999999999.99", message = "Cash credit limit cannot exceed 99,999,999,999.99")
    @Digits(integer = 10, fraction = 2, message = "Cash credit limit must have at most 10 integer digits and exactly 2 decimal places")
    private BigDecimal cashCreditLimit;
    
    /**
     * Account active status flag.
     * 
     * <p><b>COBOL Mapping:</b> ACCT-ACTIVE-STATUS PIC X(01) from CVACT01Y.cpy line 6</p>
     * <p><b>BMS Field:</b> ACSTTUS from COACTUP.bms line 94, LENGTH=1</p>
     * 
     * <p>This field indicates whether the account is currently active and can be used for
     * transactions. Replicates the COBOL single-character flag with exact value matching.</p>
     * 
     * <p><b>Valid Values:</b></p>
     * <ul>
     *   <li><b>'Y'</b> - Account is Active (can process transactions)</li>
     *   <li><b>'N'</b> - Account is Inactive (transactions blocked)</li>
     * </ul>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field (cannot be null or empty)</li>
     *   <li>Must be exactly one character</li>
     *   <li>Must be uppercase 'Y' or uppercase 'N'</li>
     *   <li>No other characters or values are permitted</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent Condition Names:</b></p>
     * <pre>
     * 88 ACCOUNT-ACTIVE    VALUE 'Y'.
     * 88 ACCOUNT-INACTIVE  VALUE 'N'.
     * </pre>
     * 
     * <p><b>Example values:</b> "Y", "N"</p>
     */
    @JsonProperty("activeStatus")
    @NotNull(message = "Active status is required")
    @Pattern(regexp = "^[YN]$", message = "Active status must be 'Y' (Active) or 'N' (Inactive)")
    private String activeStatus;
}

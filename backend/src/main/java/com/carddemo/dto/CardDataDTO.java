package com.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Card Data Transfer Object
 * 
 * Encapsulates card-related working areas and screen navigation fields from COBOL CVCRD01Y.cpy copybook.
 * This DTO represents the CC-WORK-AREAS structure used in the original CICS application for coordinating
 * card management screen workflows and preserving navigation context between BMS map interactions.
 * 
 * In the REST API architecture, this DTO provides structured payload for card-related operations with
 * validation, error messaging, and workflow routing information. Essential for card list, card detail,
 * and card update operations that require multi-step user interactions with context preservation.
 * 
 * <p>Key Components:</p>
 * <ul>
 *   <li>PF key action identifiers (ENTER, CLEAR, PA keys, PF1-PF12)</li>
 *   <li>Program navigation state (next program, mapset, map)</li>
 *   <li>Error and return messages</li>
 *   <li>Core card/account/customer entity IDs</li>
 * </ul>
 * 
 * <p>COBOL REDEFINES Pattern:</p>
 * The original copybook uses REDEFINES to represent numeric IDs as both strings and numbers.
 * This DTO stores all IDs as strings with validation, and provides converter methods to retrieve
 * numeric representations when needed.
 * 
 * @see app/cpy/CVCRD01Y.cpy - Source COBOL copybook
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardDataDTO {

    /**
     * Action Identifier (PF Key Code)
     * 
     * Represents the attention identifier (AID) key pressed by the user on the 3270 terminal.
     * In the modern UI, this maps to button clicks and keyboard shortcuts.
     * 
     * Valid values:
     * - "ENTER" - Enter key pressed
     * - "CLEAR" - Clear key pressed
     * - "PA1", "PA2" - Program Attention keys
     * - "PFK01" through "PFK12" - Program Function keys 1-12
     * 
     * COBOL: CCARD-AID PIC X(5)
     */
    @JsonProperty("actionId")
    @Size(max = 5, message = "Action ID must not exceed 5 characters")
    private String actionId;

    /**
     * Next Program Name
     * 
     * Specifies the COBOL program to execute next in the workflow.
     * In the REST API architecture, this can be used for workflow routing logic.
     * 
     * COBOL: CCARD-NEXT-PROG PIC X(8)
     */
    @JsonProperty("nextProgram")
    @Size(max = 8, message = "Next program must not exceed 8 characters")
    private String nextProgram;

    /**
     * Next Mapset Name
     * 
     * Specifies the BMS mapset to display next.
     * In React, this can map to component routing decisions.
     * 
     * COBOL: CCARD-NEXT-MAPSET PIC X(7)
     */
    @JsonProperty("nextMapset")
    @Size(max = 7, message = "Next mapset must not exceed 7 characters")
    private String nextMapset;

    /**
     * Next Map Name
     * 
     * Specifies the specific BMS map within the mapset to display.
     * In React, this can map to specific view states within a component.
     * 
     * COBOL: CCARD-NEXT-MAP PIC X(7)
     */
    @JsonProperty("nextMap")
    @Size(max = 7, message = "Next map must not exceed 7 characters")
    private String nextMap;

    /**
     * Error Message
     * 
     * Contains error message text to display to the user when validation
     * or business rule violations occur.
     * 
     * COBOL: CCARD-ERROR-MSG PIC X(75)
     */
    @JsonProperty("errorMessage")
    @Size(max = 75, message = "Error message must not exceed 75 characters")
    private String errorMessage;

    /**
     * Return Message
     * 
     * Contains informational message text to display to the user upon
     * successful operations or to provide guidance.
     * 
     * When set to LOW-VALUES in COBOL (null or empty in Java), no message is displayed.
     * 
     * COBOL: CCARD-RETURN-MSG PIC X(75)
     * 88-level: CCARD-RETURN-MSG-OFF VALUE LOW-VALUES
     */
    @JsonProperty("returnMessage")
    @Size(max = 75, message = "Return message must not exceed 75 characters")
    private String returnMessage;

    /**
     * Account ID
     * 
     * Unique identifier for the credit card account.
     * Stored as string to preserve leading zeros, but validated as numeric.
     * 
     * COBOL: CC-ACCT-ID PIC X(11)
     * REDEFINES: CC-ACCT-ID-N PIC 9(11)
     */
    @JsonProperty("accountId")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @Pattern(regexp = "^[0-9]*$", message = "Account ID must contain only numeric characters")
    private String accountId;

    /**
     * Card Number
     * 
     * 16-digit credit card number.
     * Stored as string to preserve leading zeros, but validated as numeric.
     * 
     * COBOL: CC-CARD-NUM PIC X(16)
     * REDEFINES: CC-CARD-NUM-N PIC 9(16)
     */
    @JsonProperty("cardNumber")
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    @Pattern(regexp = "^[0-9]*$", message = "Card number must contain only numeric characters")
    private String cardNumber;

    /**
     * Customer ID
     * 
     * Unique identifier for the customer who owns the account.
     * Stored as string to preserve leading zeros, but validated as numeric.
     * 
     * COBOL: CC-CUST-ID PIC X(09)
     * REDEFINES: CC-CUST-ID-N PIC 9(9)
     */
    @JsonProperty("customerId")
    @Size(max = 9, message = "Customer ID must not exceed 9 characters")
    @Pattern(regexp = "^[0-9]*$", message = "Customer ID must contain only numeric characters")
    private String customerId;

    // ============================================================================
    // Helper Methods for PF Key Detection
    // ============================================================================

    /**
     * Checks if the ENTER key was pressed.
     * 
     * @return true if action ID is "ENTER"
     */
    public boolean isEnterAction() {
        return "ENTER".equals(actionId);
    }

    /**
     * Checks if the CLEAR key was pressed.
     * 
     * @return true if action ID is "CLEAR"
     */
    public boolean isClearAction() {
        return "CLEAR".equals(actionId);
    }

    /**
     * Checks if a specific Program Function key was pressed.
     * 
     * @param keyNumber the PF key number (1-12)
     * @return true if the specified PF key was pressed
     */
    public boolean isPFKeyAction(int keyNumber) {
        if (keyNumber < 1 || keyNumber > 12) {
            return false;
        }
        return actionId != null && actionId.equals(String.format("PFK%02d", keyNumber));
    }

    /**
     * Checks if an error message is present.
     * 
     * @return true if error message exists and is not empty
     */
    public boolean hasErrorMessage() {
        return errorMessage != null && !errorMessage.trim().isEmpty();
    }

    /**
     * Checks if a return message is present.
     * 
     * Returns false if the message is null, empty, or set to LOW-VALUES
     * (represented as null character in Java).
     * 
     * @return true if return message exists, is not empty, and is not LOW-VALUES
     */
    public boolean hasReturnMessage() {
        if (returnMessage == null || returnMessage.trim().isEmpty()) {
            return false;
        }
        // Check for LOW-VALUES representation (null character)
        return !returnMessage.equals(String.valueOf((char) 0)) 
               && !returnMessage.trim().equals("");
    }

    // ============================================================================
    // Converter Methods for Numeric IDs
    // ============================================================================

    /**
     * Converts account ID from string to Long.
     * 
     * Handles the COBOL REDEFINES pattern where CC-ACCT-ID (PIC X(11))
     * is redefined as CC-ACCT-ID-N (PIC 9(11)) for numeric processing.
     * 
     * @return account ID as Long, or null if not numeric or empty
     */
    public Long getAccountIdAsLong() {
        try {
            if (accountId != null && !accountId.trim().isEmpty()) {
                return Long.parseLong(accountId.trim());
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Converts card number from string to Long.
     * 
     * Handles the COBOL REDEFINES pattern where CC-CARD-NUM (PIC X(16))
     * is redefined as CC-CARD-NUM-N (PIC 9(16)) for numeric processing.
     * 
     * Note: Card numbers may exceed Long.MAX_VALUE in rare cases. Consider
     * using BigInteger for production if needed.
     * 
     * @return card number as Long, or null if not numeric or empty
     */
    public Long getCardNumberAsLong() {
        try {
            if (cardNumber != null && !cardNumber.trim().isEmpty()) {
                return Long.parseLong(cardNumber.trim());
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Converts customer ID from string to Long.
     * 
     * Handles the COBOL REDEFINES pattern where CC-CUST-ID (PIC X(09))
     * is redefined as CC-CUST-ID-N (PIC 9(9)) for numeric processing.
     * 
     * @return customer ID as Long, or null if not numeric or empty
     */
    public Long getCustomerIdAsLong() {
        try {
            if (customerId != null && !customerId.trim().isEmpty()) {
                return Long.parseLong(customerId.trim());
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

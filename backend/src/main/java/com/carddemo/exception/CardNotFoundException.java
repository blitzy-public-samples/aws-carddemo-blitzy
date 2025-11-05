/*****************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 ******************************************************************/

package com.carddemo.exception;

/**
 * Custom runtime exception thrown when card lookup operations fail to find
 * the requested card in the database.
 * 
 * <p>This exception represents scenarios where EXEC CICS READ CARDDAT operations
 * return DFHRESP(NOTFND) in COBOL programs (COCRDLIC.cbl, COCRDSLC.cbl, COCRDUPC.cbl).
 * It indicates that a card with the specified card number, card ID, or associated
 * account does not exist in the CARDDAT entity (PostgreSQL card table).</p>
 * 
 * <p>COBOL NOTFND Mapping:</p>
 * <ul>
 *   <li>Maps to COCRDLIC.cbl EXEC CICS READ CARDDAT...RESP(WS-RESP-CD) 
 *       where WS-RESP-CD = DFHRESP(NOTFND)</li>
 *   <li>Represents COCRDSLC.cbl card detail lookup returning NOTFND response code</li>
 *   <li>Equivalent to COCRDUPC.cbl update operation where card does not exist</li>
 *   <li>Corresponds to COBOL pattern: WHEN DFHRESP(NOTFND) ... card not found error handling</li>
 * </ul>
 * 
 * <p>Usage in Services:</p>
 * <ul>
 *   <li>CardListService.getCardsByAccountId(): Thrown when account has no associated cards</li>
 *   <li>CardDetailService.getCardDetail(): Thrown when card number not found in CardRepository</li>
 *   <li>CardUpdateService.updateCard(): Thrown when attempting to update non-existent card</li>
 *   <li>TransactionCreationService: Thrown when transaction references invalid card number</li>
 * </ul>
 * 
 * <p>Error Response via GlobalExceptionHandler:</p>
 * <ul>
 *   <li>HTTP Status: 404 NOT_FOUND</li>
 *   <li>Error body includes error code, message, masked card identifier, and identifier type</li>
 *   <li>Masks full card number in response for PCI compliance (shows only last 4 digits)</li>
 * </ul>
 * 
 * <p>This exception is immutable and thread-safe for use in concurrent card operations.</p>
 * 
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see com.carddemo.service.CardListService
 * @see com.carddemo.service.CardDetailService
 * @see com.carddemo.service.CardUpdateService
 */
public class CardNotFoundException extends RuntimeException {

    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The card identifier used in the failed lookup operation.
     * This could be a card number, card ID, or account ID depending on the lookup type.
     * This field is immutable after construction.
     */
    private final String cardIdentifier;

    /**
     * The type of identifier used in the failed lookup operation.
     * Specifies whether the lookup was by card number, card ID, or account ID.
     * This field is immutable after construction.
     */
    private final IdentifierType identifierType;

    /**
     * Enum defining the types of identifiers that can be used for card lookup operations.
     * 
     * <p>Each type corresponds to a different access path in the COBOL VSAM file operations:</p>
     * <ul>
     *   <li>CARD_NUMBER: Primary key lookup by 16-digit card number (COCRDSLC.cbl WS-CARD-RID-CARDNUM)</li>
     *   <li>CARD_ID: Direct lookup by internal card identifier (primary key in PostgreSQL)</li>
     *   <li>ACCOUNT_ID: Alternate index lookup by account ID (COCRDSLC.cbl LIT-CARDFILENAME-ACCT-PATH)</li>
     * </ul>
     */
    public enum IdentifierType {
        /**
         * Lookup by 16-digit card number (primary VSAM key).
         * Maps to COBOL: EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
         */
        CARD_NUMBER,

        /**
         * Lookup by internal card ID (database primary key).
         * Maps to JPA: cardRepository.findById(cardId)
         */
        CARD_ID,

        /**
         * Lookup by account ID (alternate index in VSAM).
         * Maps to COBOL: EXEC CICS READ FILE(LIT-CARDFILENAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID)
         */
        ACCOUNT_ID
    }

    /**
     * Constructs a new CardNotFoundException with the specified card identifier.
     * The identifier type defaults to CARD_NUMBER.
     * 
     * <p>The exception message is auto-generated as:
     * "Card not found with identifier: {cardIdentifier}"</p>
     * 
     * @param cardIdentifier the card identifier used in the failed lookup (card number, ID, or account ID)
     */
    public CardNotFoundException(String cardIdentifier) {
        super("Card not found with identifier: " + cardIdentifier);
        this.cardIdentifier = cardIdentifier;
        this.identifierType = IdentifierType.CARD_NUMBER;
    }

    /**
     * Constructs a new CardNotFoundException with the specified card identifier and type.
     * 
     * <p>The exception message is auto-generated based on the identifier type:
     * <ul>
     *   <li>CARD_NUMBER: "Card not found with card number: {cardIdentifier}"</li>
     *   <li>CARD_ID: "Card not found with card ID: {cardIdentifier}"</li>
     *   <li>ACCOUNT_ID: "No cards found for account ID: {cardIdentifier}"</li>
     * </ul>
     * 
     * @param cardIdentifier the card identifier used in the failed lookup
     * @param identifierType the type of identifier used (CARD_NUMBER, CARD_ID, or ACCOUNT_ID)
     */
    public CardNotFoundException(String cardIdentifier, IdentifierType identifierType) {
        super(buildMessage(cardIdentifier, identifierType));
        this.cardIdentifier = cardIdentifier;
        this.identifierType = identifierType != null ? identifierType : IdentifierType.CARD_NUMBER;
    }

    /**
     * Constructs a new CardNotFoundException with a custom message and card identifier.
     * The identifier type defaults to CARD_NUMBER.
     * 
     * <p>This constructor allows for custom error messages while preserving the card identifier
     * for error handling and logging purposes.</p>
     * 
     * @param message the custom detail message
     * @param cardIdentifier the card identifier used in the failed lookup
     */
    public CardNotFoundException(String message, String cardIdentifier) {
        super(message);
        this.cardIdentifier = cardIdentifier;
        this.identifierType = IdentifierType.CARD_NUMBER;
    }

    /**
     * Constructs a new CardNotFoundException with a custom message, card identifier, and type.
     * 
     * <p>This is the most flexible constructor, allowing full control over the error message
     * while capturing both the identifier and its type for comprehensive error handling.</p>
     * 
     * @param message the custom detail message
     * @param cardIdentifier the card identifier used in the failed lookup
     * @param identifierType the type of identifier used (CARD_NUMBER, CARD_ID, or ACCOUNT_ID)
     */
    public CardNotFoundException(String message, String cardIdentifier, IdentifierType identifierType) {
        super(message);
        this.cardIdentifier = cardIdentifier;
        this.identifierType = identifierType != null ? identifierType : IdentifierType.CARD_NUMBER;
    }

    /**
     * Constructs a new CardNotFoundException with a custom message and cause.
     * 
     * <p>This constructor is used when wrapping another exception (such as a database access exception)
     * that occurred during card lookup operations. The card identifier and type are not captured
     * in this case, as the focus is on the underlying cause.</p>
     * 
     * <p>Example usage:</p>
     * <pre>
     * try {
     *     Card card = cardRepository.findById(cardId)
     *         .orElseThrow(() -&gt; new CardNotFoundException("Card not found", cardId.toString(), IdentifierType.CARD_ID));
     * } catch (DataAccessException e) {
     *     throw new CardNotFoundException("Database error during card lookup", e);
     * }
     * </pre>
     * 
     * @param message the custom detail message
     * @param cause the cause of this exception (which is saved for later retrieval by the getCause() method)
     */
    public CardNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.cardIdentifier = null;
        this.identifierType = null;
    }

    /**
     * Returns the card identifier that was used in the failed lookup operation.
     * 
     * <p>This identifier could be:</p>
     * <ul>
     *   <li>A 16-digit card number (e.g., "4556123456789012")</li>
     *   <li>An internal card ID (e.g., "12345")</li>
     *   <li>An account ID (e.g., "10001234567")</li>
     * </ul>
     * 
     * <p>Note: For PCI compliance, this value should be masked before including
     * in external error responses. The GlobalExceptionHandler handles this masking
     * automatically by showing only the last 4 digits for card numbers.</p>
     * 
     * @return the card identifier used in the lookup, or null if not applicable
     */
    public String getCardIdentifier() {
        return cardIdentifier;
    }

    /**
     * Returns the type of identifier that was used in the failed lookup operation.
     * 
     * <p>This helps distinguish between different types of card lookups:</p>
     * <ul>
     *   <li>CARD_NUMBER: Lookup by card number (most common)</li>
     *   <li>CARD_ID: Lookup by internal database ID</li>
     *   <li>ACCOUNT_ID: Lookup by associated account ID</li>
     * </ul>
     * 
     * <p>The identifier type is useful for:</p>
     * <ul>
     *   <li>Generating appropriate error messages</li>
     *   <li>Determining which masking rules to apply</li>
     *   <li>Logging and debugging lookup failures</li>
     *   <li>Providing context in error responses to API clients</li>
     * </ul>
     * 
     * @return the identifier type, or null if not applicable
     */
    public IdentifierType getIdentifierType() {
        return identifierType;
    }

    /**
     * Builds an appropriate error message based on the card identifier and its type.
     * 
     * <p>This helper method generates context-specific error messages:</p>
     * <ul>
     *   <li>CARD_NUMBER: "Card not found with card number: {identifier}"</li>
     *   <li>CARD_ID: "Card not found with card ID: {identifier}"</li>
     *   <li>ACCOUNT_ID: "No cards found for account ID: {identifier}"</li>
     * </ul>
     * 
     * @param identifier the card identifier
     * @param type the identifier type
     * @return an appropriate error message
     */
    private static String buildMessage(String identifier, IdentifierType type) {
        if (type == null) {
            return "Card not found with identifier: " + identifier;
        }

        switch (type) {
            case CARD_NUMBER:
                return "Card not found with card number: " + identifier;
            case CARD_ID:
                return "Card not found with card ID: " + identifier;
            case ACCOUNT_ID:
                return "No cards found for account ID: " + identifier;
            default:
                return "Card not found with identifier: " + identifier;
        }
    }
}

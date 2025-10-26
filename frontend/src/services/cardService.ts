/**
 * Card Management Service Module
 * 
 * Converted from COBOL programs:
 * - COCRDLIC.cbl: Card list display with pagination (EXEC CICS STARTBR/READNEXT browse operations)
 * - COCRDSLC.cbl: Card selection logic (EXEC CICS READ single record retrieval)
 * - COCRDUPC.cbl: Card update operations (EXEC CICS REWRITE file updates)
 * 
 * Purpose: Provides API calls for credit card CRUD operations, converting VSAM CARDFILE
 *          I/O operations to modern REST API calls with proper type safety and error handling.
 * 
 * Key Conversions:
 * - COBOL EXEC CICS STARTBR/READNEXT → REST GET /api/cards with pagination parameters
 * - COBOL EXEC CICS READ FILE('CARDFILE') → REST GET /api/cards/:cardNum
 * - COBOL EXEC CICS REWRITE FILE('CARDFILE') → REST PUT /api/cards/:cardNum
 * - COBOL EXEC CICS WRITE FILE('CARDFILE') → REST POST /api/cards
 * - COBOL EXEC CICS DELETE FILE('CARDFILE') → REST DELETE /api/cards/:cardNum
 * - COBOL file-status codes → HTTP status codes with error messages
 * - COBOL WS-PAGE-NUM pagination logic → query parameters (page, pageSize)
 * - COBOL CARD-RECORD (CVACT02Y copybook) → TypeScript Card interface
 * 
 * Features:
 * - Pagination support for card list browsing matching COBOL WS-SCRN-COUNTER logic
 * - Card number masking for PCI DSS compliance (display only last 4 digits)
 * - Filtering by card number and account ID
 * - Sorting by multiple fields (cardNum, cardAcctId, cardStatus, cardExpirationDate)
 * - Comprehensive error handling with COBOL-equivalent error messages
 * - All COBOL validation rules preserved (status codes, expiration dates, embossed names)
 * 
 * @module services/cardService
 */

import api from './api';
import { Card, CardStatus } from '../types/card';
import { PaginationParams } from '../types/common';

/**
 * Query parameters interface for getAllCards
 * Maps to Spring Boot CardController query parameters
 * Preserves COBOL COCRDLIC filter and pagination logic
 */
interface GetCardsParams {
  /** Current page number (1-based, default: 1) - Maps to COBOL WS-CA-SCREEN-NUM */
  page?: number;
  
  /** Items per page (default: 20) - Maps to COBOL WS-MAX-SCREEN-LINES (7 in COBOL) */
  pageSize?: number;
  
  /** Sort field name - Maps to COBOL indexed file access patterns */
  sortBy?: string;
  
  /** Sort direction: 'asc' or 'desc' */
  sortDirection?: 'asc' | 'desc';
  
  /** Filter by card number (partial match supported) - Maps to COBOL WS-CARD-RID-CARDNUM */
  cardNum?: string;
  
  /** Filter by account ID - Maps to COBOL WS-CARD-RID-ACCT-ID for account-specific card lists */
  acctId?: number;
}

/**
 * Response structure for paginated card list
 * Matches Spring Boot Page<Card> response from CardController.getAllCards()
 * Preserves COBOL COCRDLIC pagination state (WS-CA-NEXT-PAGE-IND, WS-CA-LAST-PAGE-DISPLAYED)
 */
interface GetCardsResponse {
  /** Array of card records - Maps to COBOL WS-SCREEN-DATA array (WS-SCREEN-ROWS) */
  cards: Card[];
  
  /** Pagination metadata - Maps to COBOL WS-CA-SCREEN-NUM and page navigation flags */
  pagination: PaginationParams;
}

/**
 * Retrieve all cards with optional filtering, sorting, and pagination
 * 
 * Converted from COBOL: COCRDLIC.cbl PROCEDURE DIVISION paragraphs:
 * - 2000-PROCESS-INPUTS: Input validation and filter setup
 * - 3000-READ-CARD-DATA: EXEC CICS STARTBR/READNEXT loop for browse operations
 * - 3500-APPLY-FILTER-RECORD: Filter logic for account-based card lists
 * - 4000-POPULATE-SCREEN-DATA: Build response array matching screen rows
 * 
 * REST API Endpoint: GET /api/cards
 * COBOL File: CARDFILE (VSAM KSDS) with CARDAIX alternate index
 * COBOL Operations: EXEC CICS STARTBR, EXEC CICS READNEXT, EXEC CICS ENDBR
 * 
 * Pagination Logic:
 * - COBOL maintains WS-CA-SCREEN-NUM (current page number)
 * - COBOL sets WS-CA-NEXT-PAGE-EXISTS flag when more records available
 * - COBOL stores WS-CA-LAST-CARDKEY for resuming browse operations
 * - Java/React uses page/pageSize query parameters for server-side pagination
 * 
 * Filtering Logic:
 * - Admin users (COBOL: SEC-USR-TYPE='A') can view all cards
 * - Regular users can only view cards associated with specific account (COBOL: WS-EXCLUDE-THIS-RECORD filter)
 * - Frontend passes acctId parameter to filter by account
 * 
 * @param params - Optional query parameters for filtering, sorting, and pagination
 * @returns Promise resolving to cards array with pagination metadata
 * @throws ApiError if request fails (maps COBOL file-status codes to HTTP errors)
 * 
 * @example
 * ```typescript
 * // Get first page of all cards (admin view)
 * const { cards, pagination } = await getAllCards();
 * 
 * // Get cards for specific account (regular user view)
 * const accountCards = await getAllCards({ acctId: 1234567890 });
 * 
 * // Get paginated results with sorting
 * const page2 = await getAllCards({
 *   page: 2,
 *   pageSize: 20,
 *   sortBy: 'cardExpirationDate',
 *   sortDirection: 'asc'
 * });
 * 
 * // Filter by card number (partial match)
 * const filtered = await getAllCards({ cardNum: '1234' });
 * ```
 */
export const getAllCards = async (params?: GetCardsParams): Promise<GetCardsResponse> => {
  try {
    // Build query parameters object, preserving COBOL filter and pagination logic
    const queryParams: Record<string, string | number> = {
      // Pagination parameters - Maps to COBOL WS-CA-SCREEN-NUM
      page: params?.page || 1,
      // COBOL uses WS-MAX-SCREEN-LINES = 7, but modern UI typically shows more
      size: params?.pageSize || 20,
    };

    // Add optional sorting parameters if provided
    // Maps to COBOL indexed file access (primary key CARD-NUM, alternate index CARD-ACCT-ID)
    if (params?.sortBy) {
      queryParams.sort = `${params.sortBy},${params.sortDirection || 'asc'}`;
    }

    // Add optional filter parameters
    // Card number filter - Maps to COBOL WS-CARD-RID-CARDNUM browse key
    if (params?.cardNum) {
      queryParams.cardNum = params.cardNum;
    }

    // Account ID filter - Maps to COBOL account-specific filtering (WS-EXCLUDE-THIS-RECORD logic)
    if (params?.acctId) {
      queryParams.acctId = params.acctId;
    }

    // Make GET request to Spring Boot CardController.getAllCards()
    // Replaces COBOL: EXEC CICS STARTBR FILE('CARDDAT') RIDFLD(WS-CARD-RID)
    const response = await api.get('/cards', { params: queryParams });

    // Extract data from Spring Boot Page<Card> response
    const cardsData = response.data.content || response.data;
    const pagination: PaginationParams = {
      page: response.data.number + 1 || params?.page || 1, // Convert 0-based to 1-based
      pageSize: response.data.size || params?.pageSize || 20,
      totalItems: response.data.totalElements || 0,
      totalPages: response.data.totalPages || 1,
    };

    // Return structured response matching COBOL screen data structure
    // Maps to COBOL: WS-SCREEN-DATA with WS-SCREEN-ROWS array (7 rows in COBOL)
    return {
      cards: cardsData,
      pagination,
    };
  } catch (error) {
    // Error handling maps COBOL file-status codes to HTTP status codes:
    // COBOL file-status 00 (success) → HTTP 200
    // COBOL file-status 23 (record not found) → HTTP 404
    // COBOL file-status 90+ (system error) → HTTP 500
    // COBOL RESP codes from CICS → HTTP error codes from Spring Boot
    console.error('[CardService] Error retrieving cards:', error);
    throw error;
  }
};

/**
 * Retrieve a single card by card number
 * 
 * Converted from COBOL: COCRDSLC.cbl (Card selection program)
 * PROCEDURE DIVISION paragraphs:
 * - 3000-READ-CARD: EXEC CICS READ FILE('CARDDAT') INTO(CARD-RECORD) RIDFLD(CARD-NUM)
 * 
 * REST API Endpoint: GET /api/cards/:cardNum
 * COBOL File: CARDFILE (VSAM KSDS)
 * COBOL Operation: EXEC CICS READ FILE('CARDDAT') RIDFLD(CARD-NUM) INTO(CARD-RECORD)
 * 
 * Card Number Format:
 * - COBOL: PIC X(16) - 16-character card number
 * - Frontend: Masked format ************1234 (12 asterisks + last 4 digits)
 * - Backend: Full card number for retrieval, masked in response per PCI DSS
 * 
 * Error Conditions (COBOL → HTTP):
 * - COBOL file-status 23 (NOTFND) → HTTP 404 DataNotFoundException
 * - COBOL RESP(NOTAUTH) → HTTP 403 Forbidden
 * - COBOL RESP(IOERR) → HTTP 500 Internal Server Error
 * 
 * @param cardNum - Card number (16 characters, can be masked or full)
 * @returns Promise resolving to Card object with masked card number
 * @throws ApiError with status 404 if card not found
 * @throws ApiError with status 403 if user lacks permission to view card
 * 
 * @example
 * ```typescript
 * // Retrieve card by full card number
 * const card = await getCardById('4111111111111234');
 * console.log(card.cardNum); // '************1234' (masked)
 * 
 * // Retrieve card by masked card number
 * const cardMasked = await getCardById('************1234');
 * 
 * // Handle not found error
 * try {
 *   const card = await getCardById('9999999999999999');
 * } catch (error) {
 *   if (error.status === 404) {
 *     console.error('Card not found');
 *   }
 * }
 * ```
 */
export const getCardById = async (cardNum: string): Promise<Card> => {
  try {
    // Validate card number format (16 characters)
    // Maps to COBOL: CARD-NUM PIC X(16) length validation
    if (!cardNum || cardNum.length !== 16) {
      throw new Error('Invalid card number format. Card number must be 16 characters.');
    }

    // Make GET request to Spring Boot CardController.getCardById()
    // Replaces COBOL: EXEC CICS READ FILE('CARDDAT') RIDFLD(CARD-NUM) INTO(CARD-RECORD) END-EXEC
    const response = await api.get(`/cards/${cardNum}`);

    // Return card data with masked card number (PCI DSS compliance)
    // COBOL displays card number in full on mainframe terminal (secure environment)
    // Modern web UI masks card number to show only last 4 digits
    return response.data;
  } catch (error) {
    // Map COBOL error conditions to HTTP status codes:
    // COBOL: IF FILE-STATUS = '23' (record not found) → HTTP 404
    // COBOL: IF RESP = DFHRESP(NOTFND) → HTTP 404
    // COBOL: IF RESP = DFHRESP(NOTAUTH) → HTTP 403
    console.error(`[CardService] Error retrieving card ${cardNum}:`, error);
    throw error;
  }
};

/**
 * Create a new credit card
 * 
 * Converted from COBOL: COCRDUPC.cbl card creation logic
 * PROCEDURE DIVISION paragraphs:
 * - 2000-VALIDATE-INPUTS: Input field validation
 * - 3000-WRITE-CARD: EXEC CICS WRITE FILE('CARDDAT') FROM(CARD-RECORD) RIDFLD(CARD-NUM)
 * 
 * REST API Endpoint: POST /api/cards
 * COBOL File: CARDFILE (VSAM KSDS)
 * COBOL Operation: EXEC CICS WRITE FILE('CARDDAT') FROM(CARD-RECORD) RIDFLD(CARD-NUM)
 * 
 * Validation Rules (preserved from COBOL):
 * - Card number: 16 digits, numeric, unique (primary key)
 * - Account ID: 11 digits, must exist in ACCTFILE
 * - Embossed name: 1-50 characters, alphanumeric with spaces
 * - Expiration date: Future date, format YYYY-MM-DD
 * - Status: Must be valid CardStatus enum value (Y, N, B, E)
 * - Active date: Optional, cannot be future date if provided
 * 
 * COBOL Validation Logic:
 * - WS-EDIT-CARD-FLAG for validation state tracking
 * - FLG-CARDFILTER-ISVALID = '1' when all validations pass
 * - FLG-CARDFILTER-NOT-OK = '0' when validation fails
 * - Field-level validation in 2000-VALIDATE-INPUTS paragraph
 * 
 * @param cardData - New card data (excludes createdAt, updatedAt - set by backend)
 * @returns Promise resolving to newly created Card object
 * @throws ApiError with status 400 if validation fails
 * @throws ApiError with status 409 if card number already exists (duplicate key)
 * 
 * @example
 * ```typescript
 * const newCard = await createCard({
 *   cardNum: '4111111111111234',
 *   cardAcctId: 1234567890,
 *   cardEmbossedName: 'JOHN DOE',
 *   cardExpirationDate: '2027-12-31',
 *   cardStatus: CardStatus.ACTIVE,
 *   cardActiveDate: '2023-01-15'
 * });
 * 
 * console.log(newCard.cardNum); // '************1234' (masked in response)
 * ```
 */
export const createCard = async (
  cardData: Omit<Card, 'createdAt' | 'updatedAt'>
): Promise<Card> => {
  try {
    // Client-side validation before API call
    // Maps to COBOL: 2000-VALIDATE-INPUTS paragraph logic

    // Validate card number format (16 digits)
    if (!cardData.cardNum || !/^\d{16}$/.test(cardData.cardNum)) {
      throw new Error('Card number must be 16 digits');
    }

    // Validate account ID (11 digits)
    // Maps to COBOL: CARD-ACCT-ID PIC 9(11)
    if (!cardData.cardAcctId || cardData.cardAcctId.toString().length !== 11) {
      throw new Error('Account ID must be 11 digits');
    }

    // Validate embossed name (1-50 characters)
    // Maps to COBOL: CARD-EMBOSSED-NAME PIC X(50)
    if (!cardData.cardEmbossedName || cardData.cardEmbossedName.trim().length === 0) {
      throw new Error('Embossed name is required');
    }
    if (cardData.cardEmbossedName.length > 50) {
      throw new Error('Embossed name cannot exceed 50 characters');
    }

    // Validate expiration date (must be future date)
    // Maps to COBOL: CARD-EXPIRAION-DATE PIC X(10) with date validation logic
    const expirationDate = new Date(cardData.cardExpirationDate);
    const today = new Date();
    today.setHours(0, 0, 0, 0); // Reset time for date-only comparison
    if (expirationDate <= today) {
      throw new Error('Expiration date must be in the future');
    }

    // Validate card status (must be valid CardStatus enum value)
    // Maps to COBOL: CARD-ACTIVE-STATUS PIC X(01) with 88-level conditions
    const validStatuses = [CardStatus.ACTIVE, CardStatus.INACTIVE, CardStatus.BLOCKED, CardStatus.EXPIRED];
    if (!validStatuses.includes(cardData.cardStatus as CardStatus)) {
      throw new Error('Invalid card status. Must be Y, N, B, or E');
    }

    // Validate active date if provided (cannot be future date)
    if (cardData.cardActiveDate) {
      const activeDate = new Date(cardData.cardActiveDate);
      if (activeDate > today) {
        throw new Error('Active date cannot be in the future');
      }
    }

    // Make POST request to Spring Boot CardController.createCard()
    // Replaces COBOL: EXEC CICS WRITE FILE('CARDDAT') FROM(CARD-RECORD) RIDFLD(CARD-NUM) END-EXEC
    const response = await api.post('/cards', cardData);

    // Return newly created card with masked card number
    return response.data;
  } catch (error) {
    // Map COBOL error conditions:
    // COBOL: IF FILE-STATUS = '22' (duplicate key) → HTTP 409 Conflict
    // COBOL: IF WS-EDIT-CARD-FLAG = '0' (validation failed) → HTTP 400 Bad Request
    // COBOL: IF RESP = DFHRESP(DUPREC) → HTTP 409 Conflict
    console.error('[CardService] Error creating card:', error);
    throw error;
  }
};

/**
 * Update an existing credit card
 * 
 * Converted from COBOL: COCRDUPC.cbl card update logic
 * PROCEDURE DIVISION paragraphs:
 * - 2000-VALIDATE-INPUTS: Input field validation
 * - 3000-READ-CARD: Read existing card for update
 * - 4000-UPDATE-CARD: EXEC CICS REWRITE FILE('CARDDAT') FROM(CARD-RECORD)
 * 
 * REST API Endpoint: PUT /api/cards/:cardNum
 * COBOL File: CARDFILE (VSAM KSDS)
 * COBOL Operation: EXEC CICS REWRITE FILE('CARDDAT') FROM(CARD-RECORD)
 * 
 * Update Strategy:
 * - COBOL: READ for UPDATE, modify fields, REWRITE (optimistic locking via VSAM RBA)
 * - Java/JPA: Version-based optimistic locking with @Version annotation
 * - Frontend: Partial updates supported - only send changed fields
 * 
 * Updatable Fields:
 * - cardEmbossedName: Cardholder name (CARD-EMBOSSED-NAME)
 * - cardExpirationDate: Expiration date (CARD-EXPIRAION-DATE)
 * - cardStatus: Card status (CARD-ACTIVE-STATUS)
 * - cardActiveDate: Activation date (CARD-ACTIVE-DATE)
 * 
 * Non-Updatable Fields:
 * - cardNum: Primary key, cannot be changed
 * - cardAcctId: Account association, requires separate process
 * - createdAt, updatedAt: System-managed timestamps
 * 
 * Validation Rules (same as createCard):
 * - Embossed name: 1-50 characters if provided
 * - Expiration date: Future date if provided
 * - Status: Valid CardStatus enum value if provided
 * - Active date: Not future date if provided
 * 
 * @param cardNum - Card number to update (16 characters)
 * @param cardData - Partial card data with fields to update
 * @returns Promise resolving to updated Card object
 * @throws ApiError with status 404 if card not found
 * @throws ApiError with status 400 if validation fails
 * @throws ApiError with status 409 if optimistic locking conflict (concurrent update)
 * 
 * @example
 * ```typescript
 * // Update card status to blocked
 * const updated = await updateCard('4111111111111234', {
 *   cardStatus: CardStatus.BLOCKED
 * });
 * 
 * // Update multiple fields
 * const updated2 = await updateCard('************1234', {
 *   cardEmbossedName: 'JANE DOE',
 *   cardExpirationDate: '2028-12-31'
 * });
 * 
 * // Handle optimistic locking conflict
 * try {
 *   await updateCard(cardNum, updates);
 * } catch (error) {
 *   if (error.status === 409) {
 *     console.error('Card was modified by another user. Please refresh and try again.');
 *   }
 * }
 * ```
 */
export const updateCard = async (
  cardNum: string,
  cardData: Partial<Card>
): Promise<Card> => {
  try {
    // Validate card number format
    if (!cardNum || cardNum.length !== 16) {
      throw new Error('Invalid card number format. Card number must be 16 characters.');
    }

    // Client-side validation for updated fields
    // Maps to COBOL: 2000-VALIDATE-INPUTS paragraph

    // Validate embossed name if provided
    if (cardData.cardEmbossedName !== undefined) {
      if (cardData.cardEmbossedName.trim().length === 0) {
        throw new Error('Embossed name cannot be empty');
      }
      if (cardData.cardEmbossedName.length > 50) {
        throw new Error('Embossed name cannot exceed 50 characters');
      }
    }

    // Validate expiration date if provided
    if (cardData.cardExpirationDate !== undefined) {
      const expirationDate = new Date(cardData.cardExpirationDate);
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      if (expirationDate <= today) {
        throw new Error('Expiration date must be in the future');
      }
    }

    // Validate card status if provided
    if (cardData.cardStatus !== undefined) {
      const validStatuses = [CardStatus.ACTIVE, CardStatus.INACTIVE, CardStatus.BLOCKED, CardStatus.EXPIRED];
      if (!validStatuses.includes(cardData.cardStatus as CardStatus)) {
        throw new Error('Invalid card status. Must be Y, N, B, or E');
      }
    }

    // Validate active date if provided
    if (cardData.cardActiveDate !== undefined && cardData.cardActiveDate !== null) {
      const activeDate = new Date(cardData.cardActiveDate);
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      if (activeDate > today) {
        throw new Error('Active date cannot be in the future');
      }
    }

    // Make PUT request to Spring Boot CardController.updateCard()
    // Replaces COBOL: EXEC CICS READ FILE('CARDDAT') UPDATE RIDFLD(CARD-NUM) INTO(CARD-RECORD)
    //                 EXEC CICS REWRITE FILE('CARDDAT') FROM(CARD-RECORD)
    const response = await api.put(`/cards/${cardNum}`, cardData);

    // Return updated card data
    return response.data;
  } catch (error) {
    // Map COBOL error conditions:
    // COBOL: IF FILE-STATUS = '23' (record not found) → HTTP 404
    // COBOL: IF RESP = DFHRESP(NOTFND) → HTTP 404
    // COBOL: Optimistic locking conflict (VSAM RBA check) → HTTP 409 Conflict
    console.error(`[CardService] Error updating card ${cardNum}:`, error);
    throw error;
  }
};

/**
 * Delete a credit card
 * 
 * Converted from COBOL: Card deletion logic (typically restricted operation)
 * COBOL Operation: EXEC CICS DELETE FILE('CARDDAT') RIDFLD(CARD-NUM)
 * 
 * REST API Endpoint: DELETE /api/cards/:cardNum
 * COBOL File: CARDFILE (VSAM KSDS)
 * 
 * Business Rules:
 * - Cards with outstanding balances typically cannot be deleted (soft delete via status change)
 * - Cards associated with active accounts require special handling
 * - Deletion is often a logical delete (set status to 'D') rather than physical removal
 * - COBOL implementations may prevent deletion and require status change instead
 * 
 * Security Considerations:
 * - Delete operation typically restricted to admin users (COBOL: SEC-USR-TYPE='A')
 * - Audit trail required for card deletions (COBOL: write to audit file)
 * - Cross-reference records (XREFFILE) must be cleaned up
 * 
 * @param cardNum - Card number to delete (16 characters)
 * @returns Promise resolving to void on successful deletion
 * @throws ApiError with status 404 if card not found
 * @throws ApiError with status 403 if user lacks permission to delete card
 * @throws ApiError with status 400 if card cannot be deleted due to business rules
 * 
 * @example
 * ```typescript
 * // Delete a card
 * await deleteCard('4111111111111234');
 * 
 * // Handle deletion errors
 * try {
 *   await deleteCard(cardNum);
 *   console.log('Card deleted successfully');
 * } catch (error) {
 *   if (error.status === 400) {
 *     console.error('Card cannot be deleted. Please deactivate instead.');
 *   } else if (error.status === 403) {
 *     console.error('You do not have permission to delete cards.');
 *   }
 * }
 * ```
 */
export const deleteCard = async (cardNum: string): Promise<void> => {
  try {
    // Validate card number format
    if (!cardNum || cardNum.length !== 16) {
      throw new Error('Invalid card number format. Card number must be 16 characters.');
    }

    // Make DELETE request to Spring Boot CardController.deleteCard()
    // Replaces COBOL: EXEC CICS DELETE FILE('CARDDAT') RIDFLD(CARD-NUM) END-EXEC
    await api.delete(`/cards/${cardNum}`);

    // No return value for successful deletion
  } catch (error) {
    // Map COBOL error conditions:
    // COBOL: IF FILE-STATUS = '23' (record not found) → HTTP 404
    // COBOL: IF RESP = DFHRESP(NOTAUTH) → HTTP 403 Forbidden
    // COBOL: Business rule violation → HTTP 400 Bad Request
    console.error(`[CardService] Error deleting card ${cardNum}:`, error);
    throw error;
  }
};

/**
 * Card Service Object
 * Default export containing all card-related API operations
 * 
 * Provides a centralized interface for card management operations,
 * matching the service layer pattern from Spring Boot backend.
 * 
 * Usage:
 * ```typescript
 * import cardService from './services/cardService';
 * 
 * // Get all cards
 * const { cards, pagination } = await cardService.getAllCards({ page: 1, pageSize: 20 });
 * 
 * // Get specific card
 * const card = await cardService.getCardById('4111111111111234');
 * 
 * // Create new card
 * const newCard = await cardService.createCard(cardData);
 * 
 * // Update existing card
 * const updated = await cardService.updateCard(cardNum, updates);
 * 
 * // Delete card
 * await cardService.deleteCard(cardNum);
 * ```
 */
const cardService = {
  getAllCards,
  getCardById,
  createCard,
  updateCard,
  deleteCard,
};

export default cardService;

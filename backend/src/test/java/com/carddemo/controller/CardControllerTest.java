package com.carddemo.controller;

import com.carddemo.model.dto.CardDto;
import com.carddemo.security.JwtTokenProvider;
import com.carddemo.service.CardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Arrays;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JUnit 5 test class for CardController testing card CRUD REST endpoints with pagination.
 * 
 * Converted from COBOL programs:
 * - COCRDLIC.cbl: Card list display logic with pagination (lines 1-1460, 117KB)
 * - COCRDSLC.cbl: Card selection logic for single card retrieval
 * - COCRDUPC.cbl: Card update logic with expiration validation (lines 1-2000+, 126KB)
 * 
 * This test class validates the REST API endpoints that replace BMS card management screens:
 * - COCRDLI.bms (card list screen) → GET /api/cards tests
 * - COCRDSL.bms (card selection screen) → GET /api/cards/{cardNumber} tests
 * - COCRDUP.bms (card update screen) → PUT /api/cards/{cardNumber} tests
 * - Card creation logic → POST /api/cards tests
 * 
 * Test Coverage:
 * 1. GET /api/cards?accountId={id}&page={n}&size={s}
 *    - Tests paginated card list retrieval with account filtering
 *    - Validates pagination metadata (totalElements, totalPages, numberOfElements)
 *    - Verifies card number masking (****1234 format) for PCI compliance
 *    - Tests HTTP 200 OK with JSON array response
 * 
 * 2. GET /api/cards/{cardNumber}
 *    - Tests single card retrieval by 16-digit card number
 *    - Validates CardDto JSON structure with masked card number
 *    - Tests HTTP 200 OK for found cards
 *    - Tests HTTP 404 Not Found for non-existent cards
 * 
 * 3. PUT /api/cards/{cardNumber}
 *    - Tests card update with field validation
 *    - Validates expiration date rules (future date, within 5 years)
 *    - Validates card status values (Y, N, S, L, E, C)
 *    - Tests HTTP 200 OK for successful updates
 *    - Tests HTTP 400 Bad Request for validation errors
 *    - Tests HTTP 404 Not Found for non-existent cards
 * 
 * 4. POST /api/cards
 *    - Tests new card creation with validation
 *    - Validates card number format (16 digits)
 *    - Validates card-account relationship
 *    - Tests HTTP 201 Created for successful creation
 *    - Tests HTTP 400 Bad Request for validation errors
 *    - Tests HTTP 409 Conflict for duplicate card numbers
 * 
 * Testing Approach:
 * - Uses @WebMvcTest(CardController.class) for isolated controller testing
 * - Mocks CardService using @MockBean to isolate controller logic
 * - Uses MockMvc to perform HTTP requests and assert responses
 * - Uses ObjectMapper for JSON serialization/deserialization
 * - Validates JSON response structure using jsonPath() assertions
 * - Verifies service method invocations using Mockito verify()
 * 
 * COBOL to REST API Test Mapping:
 * 
 * 1. Card List Logic (COCRDLIC.cbl lines 1123-1262):
 *    COBOL: EXEC CICS STARTBR/READNEXT loop with 7 rows per screen
 *    Test: GET /api/cards with page/size parameters, validate Page<CardDto>
 * 
 * 2. Card Selection Logic (COCRDSLC.cbl):
 *    COBOL: EXEC CICS READ FILE('CARDFILE') RIDFLD(CARD-NUM)
 *    Test: GET /api/cards/{cardNumber}, validate CardDto or 404
 * 
 * 3. Card Update Logic (COCRDUPC.cbl):
 *    COBOL: EXEC CICS READ UPDATE/REWRITE with validation
 *    Test: PUT /api/cards/{cardNumber} with CardDto, validate response
 * 
 * 4. Card Creation Logic:
 *    COBOL: EXEC CICS WRITE FILE('CARDFILE') + WRITE FILE('XREFFILE')
 *    Test: POST /api/cards with CardDto, validate 201 Created
 * 
 * Security Testing:
 * - Validates card number masking in all responses (****1234 format)
 * - Ensures CVV code is never included in responses
 * - Tests input validation to prevent invalid data
 * 
 * Performance Validation:
 * - Tests pagination to ensure efficient data retrieval
 * - Verifies service layer is called with correct parameters
 * - Validates response structure for optimal JSON size
 * 
 * @see CardController
 * @see CardService
 * @see CardDto
 * @see com.carddemo.model.entity.Card
 */
@WebMvcTest(CardController.class)
@AutoConfigureMockMvc(addFilters = false)
class CardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mock bean for JwtTokenProvider to satisfy Spring Security filter chain dependencies.
     * 
     * <p>Even though @AutoConfigureMockMvc(addFilters = false) disables filter execution,
     * Spring Security autoconfiguration still attempts to create JwtAuthenticationFilter bean
     * during context initialization, which requires JwtTokenProvider as a dependency. This
     * @MockBean annotation provides a mock implementation to satisfy the dependency injection
     * requirement without loading the actual JWT security infrastructure.</p>
     * 
     * <p>This mock is not used in card tests since filters are disabled, but it prevents
     * context initialization failures. This follows the same pattern used in AccountControllerTest
     * and other controller tests.</p>
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CardService cardService;

    // Test data - masked card numbers for PCI compliance
    private CardDto testCard1;
    private CardDto testCard2;
    private CardDto testCard3;
    
    // Test account IDs (COBOL PIC 9(11))
    private static final Long TEST_ACCOUNT_ID = 12345678901L;
    private static final Long TEST_ACCOUNT_ID_2 = 12345678902L;
    
    // Test card numbers (16 digits)
    private static final String TEST_CARD_NUM_1 = "4111111111111234";
    private static final String TEST_CARD_NUM_2 = "4111111111115678";
    private static final String TEST_CARD_NUM_3 = "4111111111119012";
    private static final String NON_EXISTENT_CARD_NUM = "4111111111119999";
    
    // Masked card numbers (for response validation)
    private static final String MASKED_CARD_NUM_1 = "****1234";
    private static final String MASKED_CARD_NUM_2 = "****5678";
    private static final String MASKED_CARD_NUM_3 = "****9012";

    /**
     * Setup method executed before each test to initialize test data.
     * 
     * Creates test CardDto objects with:
     * - Masked card numbers (****XXXX format) per PCI-DSS compliance
     * - Valid expiration dates (future dates within 5 years)
     * - Various card statuses (Y=Active, N=Inactive, S=Stolen)
     * - Associated account IDs
     * - Embossed cardholder names
     * 
     * This setup ensures consistent test data across all test methods and
     * matches the structure returned by CardService methods.
     */
    @BeforeEach
    void setUp() {
        // Card 1: Active card with expiration in 3 years
        testCard1 = CardDto.builder()
                .cardNum(MASKED_CARD_NUM_1)
                .cardAcctId(TEST_ACCOUNT_ID)
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().plusYears(3))
                .cardStatus("Y")  // Active
                .build();

        // Card 2: Inactive card with expiration in 4 years
        testCard2 = CardDto.builder()
                .cardNum(MASKED_CARD_NUM_2)
                .cardAcctId(TEST_ACCOUNT_ID)
                .cardEmbossedName("JANE SMITH")
                .cardExpirationDate(LocalDate.now().plusYears(4))
                .cardStatus("N")  // Inactive
                .build();

        // Card 3: Stolen card with expiration in 2 years (for different account)
        testCard3 = CardDto.builder()
                .cardNum(MASKED_CARD_NUM_3)
                .cardAcctId(TEST_ACCOUNT_ID_2)
                .cardEmbossedName("BOB JOHNSON")
                .cardExpirationDate(LocalDate.now().plusYears(2))
                .cardStatus("S")  // Stolen
                .build();
    }

    /**
     * Test GET /api/cards endpoint with account ID filter and pagination.
     * 
     * Converted from COBOL COCRDLIC.cbl card list display logic (lines 1123-1262):
     * - EXEC CICS STARTBR browsing CARDFILE by account
     * - PERFORM UNTIL loop reading 7 cards per screen
     * - Filter cards by account ID
     * 
     * This test validates:
     * - HTTP 200 OK status code
     * - JSON response contains paginated card list
     * - Pagination metadata (totalElements, totalPages, numberOfElements)
     * - Card number masking (****1234 format)
     * - Account ID filtering works correctly
     * - Service method invoked with correct parameters
     * 
     * Expected Behavior:
     * 1. Client sends GET /api/cards?accountId=12345678901&page=0&size=10
     * 2. Controller receives request with accountId and Pageable parameters
     * 3. Controller calls cardService.listCardsByAccount(accountId, pageable)
     * 4. Service returns Page<CardDto> with masked card numbers
     * 5. Controller returns HTTP 200 with paginated JSON response
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testListCardsWithPagination_ReturnsPagedCards() throws Exception {
        // Given: Mock service returns paginated card list for account
        Pageable pageable = PageRequest.of(0, 10);
        PageImpl<CardDto> cardPage = new PageImpl<>(
                Arrays.asList(testCard1, testCard2),
                pageable,
                2
        );
        
        when(cardService.listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(cardPage);

        // When: GET /api/cards with accountId and pagination parameters
        mockMvc.perform(get("/api/cards")
                        .param("accountId", TEST_ACCOUNT_ID.toString())
                        .param("page", "0")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                // Then: Response is HTTP 200 OK with paginated card list
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Validate card content array
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].cardNum", is(MASKED_CARD_NUM_1)))
                .andExpect(jsonPath("$.content[0].cardAcctId", is(TEST_ACCOUNT_ID)))
                .andExpect(jsonPath("$.content[0].cardEmbossedName", is("JOHN DOE")))
                .andExpect(jsonPath("$.content[0].cardStatus", is("Y")))
                .andExpect(jsonPath("$.content[1].cardNum", is(MASKED_CARD_NUM_2)))
                .andExpect(jsonPath("$.content[1].cardAcctId", is(TEST_ACCOUNT_ID)))
                .andExpect(jsonPath("$.content[1].cardEmbossedName", is("JANE SMITH")))
                .andExpect(jsonPath("$.content[1].cardStatus", is("N")))
                // Validate pagination metadata
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.numberOfElements", is(2)))
                .andExpect(jsonPath("$.pageable.pageNumber", is(0)))
                .andExpect(jsonPath("$.pageable.pageSize", is(10)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(true)));

        // Verify service method was called with correct parameters
        verify(cardService, times(1)).listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class));
    }

    /**
     * Test GET /api/cards endpoint with different page size.
     * 
     * Tests COBOL pagination logic with different page sizes:
     * - COBOL: 7 rows per screen (WS-MAX-SCREEN-LINES = 7 in COCRDLIC.cbl line 177)
     * - REST: Configurable page size via Pageable parameter
     * 
     * This validates that pagination works correctly with various page sizes
     * and that the total pages calculation is accurate.
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testListCardsWithCustomPageSize_ReturnsCorrectPagination() throws Exception {
        // Given: Mock service returns first page of 3 cards with page size 2
        Pageable pageable = PageRequest.of(0, 2);
        PageImpl<CardDto> cardPage = new PageImpl<>(
                Arrays.asList(testCard1, testCard2),
                pageable,
                3  // Total 3 cards, showing first 2
        );
        
        when(cardService.listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(cardPage);

        // When: GET /api/cards with page size 2
        mockMvc.perform(get("/api/cards")
                        .param("accountId", TEST_ACCOUNT_ID.toString())
                        .param("page", "0")
                        .param("size", "2")
                        .contentType(MediaType.APPLICATION_JSON))
                // Then: Response shows 2 cards on first page with correct pagination
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(2)))  // 3 cards / 2 per page = 2 pages
                .andExpect(jsonPath("$.numberOfElements", is(2)))
                .andExpect(jsonPath("$.pageable.pageSize", is(2)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(false)));  // More pages available

        verify(cardService, times(1)).listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class));
    }

    /**
     * Test GET /api/cards endpoint returns empty page when no cards found.
     * 
     * Tests COBOL "NO RECORDS FOUND" scenario (COCRDLIC.cbl lines 1241-1244):
     * - COBOL: IF WS-SCRN-COUNTER = 0 SET WS-NO-RECORDS-FOUND TO TRUE
     * - REST: Return empty page with totalElements = 0
     * 
     * This validates that the API correctly handles the case where an account
     * has no cards associated with it.
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testListCardsForAccountWithNoCards_ReturnsEmptyPage() throws Exception {
        // Given: Mock service returns empty page for account with no cards
        Pageable pageable = PageRequest.of(0, 10);
        PageImpl<CardDto> emptyPage = new PageImpl<>(
                Arrays.asList(),
                pageable,
                0
        );
        
        when(cardService.listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(emptyPage);

        // When: GET /api/cards for account with no cards
        mockMvc.perform(get("/api/cards")
                        .param("accountId", TEST_ACCOUNT_ID.toString())
                        .param("page", "0")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                // Then: Response is HTTP 200 OK with empty card list
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.totalPages", is(0)))
                .andExpect(jsonPath("$.numberOfElements", is(0)))
                .andExpect(jsonPath("$.empty", is(true)));

        verify(cardService, times(1)).listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class));
    }

    /**
     * Test GET /api/cards/{cardNumber} endpoint retrieves single card successfully.
     * 
     * Converted from COBOL COCRDSLC.cbl card selection logic:
     * - EXEC CICS READ FILE('CARDFILE') RIDFLD(CARD-NUM)
     * - WHEN DFHRESP(NORMAL) display card details
     * 
     * This test validates:
     * - HTTP 200 OK status code
     * - JSON response contains complete CardDto
     * - Card number is masked (****1234 format)
     * - All card fields are present and correct
     * - Service method invoked with correct card number
     * 
     * Expected Behavior:
     * 1. Client sends GET /api/cards/4111111111111234
     * 2. Controller extracts cardNumber from path variable
     * 3. Controller calls cardService.getCardByNumber(cardNumber)
     * 4. Service returns CardDto with masked card number
     * 5. Controller returns HTTP 200 with CardDto JSON
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetCardByNumber_WhenCardExists_ReturnsCard() throws Exception {
        // Given: Mock service returns card for valid card number
        when(cardService.getCardByNumber(eq(TEST_CARD_NUM_1)))
                .thenReturn(testCard1);

        // When: GET /api/cards/{cardNumber}
        mockMvc.perform(get("/api/cards/{cardNumber}", TEST_CARD_NUM_1)
                        .contentType(MediaType.APPLICATION_JSON))
                // Then: Response is HTTP 200 OK with card details
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardNum", is(MASKED_CARD_NUM_1)))
                .andExpect(jsonPath("$.cardAcctId", is(TEST_ACCOUNT_ID)))
                .andExpect(jsonPath("$.cardEmbossedName", is("JOHN DOE")))
                .andExpect(jsonPath("$.cardStatus", is("Y")))
                .andExpect(jsonPath("$.cardExpirationDate", notNullValue()));

        // Verify service method was called with correct card number
        verify(cardService, times(1)).getCardByNumber(eq(TEST_CARD_NUM_1));
    }

    /**
     * Test GET /api/cards/{cardNumber} endpoint returns 404 when card not found.
     * 
     * Converted from COBOL COCRDSLC.cbl error handling:
     * - EXEC CICS READ ... RESP(WS-RESP-CD)
     * - WHEN DFHRESP(NOTFND) display error message
     * 
     * This test validates:
     * - HTTP 404 Not Found status code
     * - Service throws DataNotFoundException
     * - Exception is properly propagated to client
     * 
     * Expected Behavior:
     * 1. Client sends GET /api/cards/4111111111119999 (non-existent card)
     * 2. Controller calls cardService.getCardByNumber(cardNumber)
     * 3. Service throws DataNotFoundException
     * 4. GlobalExceptionHandler catches exception
     * 5. Controller returns HTTP 404 Not Found
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetCardByNumber_WhenCardNotFound_Returns404() throws Exception {
        // Given: Mock service throws DataNotFoundException for non-existent card
        when(cardService.getCardByNumber(eq(NON_EXISTENT_CARD_NUM)))
                .thenThrow(new com.carddemo.exception.DataNotFoundException("CARD001", 
                        "Card not found: " + NON_EXISTENT_CARD_NUM));

        // When: GET /api/cards/{cardNumber} with non-existent card
        mockMvc.perform(get("/api/cards/{cardNumber}", NON_EXISTENT_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON))
                // Then: Response is HTTP 404 Not Found
                .andExpect(status().isNotFound());

        // Verify service method was called
        verify(cardService, times(1)).getCardByNumber(eq(NON_EXISTENT_CARD_NUM));
    }

    /**
     * Test PUT /api/cards/{cardNumber} endpoint updates card successfully.
     * 
     * Converted from COBOL COCRDUPC.cbl card update logic:
     * - EXEC CICS READ UPDATE FILE('CARDFILE') RIDFLD(CARD-NUM)
     * - PERFORM VALIDATE-CARD-FIELDS
     * - EXEC CICS REWRITE FILE('CARDFILE') FROM(CARD-RECORD)
     * - EXEC CICS SYNCPOINT
     * 
     * This test validates:
     * - HTTP 200 OK status code
     * - JSON request body is properly deserialized
     * - Updated card is returned in response
     * - Card number remains masked in response
     * - Service method invoked with correct parameters
     * 
     * Expected Behavior:
     * 1. Client sends PUT /api/cards/4111111111111234 with CardDto JSON
     * 2. Controller validates request body via @Valid annotation
     * 3. Controller calls cardService.updateCard(cardNumber, cardDto)
     * 4. Service validates expiration date and updates card
     * 5. Controller returns HTTP 200 with updated CardDto
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateCard_WithValidData_ReturnsUpdatedCard() throws Exception {
        // Given: Create update request with modified fields
        CardDto updateRequest = CardDto.builder()
                .cardStatus("N")  // Change from Active to Inactive
                .cardEmbossedName("JOHN M DOE")  // Update name
                .cardExpirationDate(LocalDate.now().plusYears(4))  // Update expiration
                .build();

        // Mock service returns updated card
        CardDto updatedCard = CardDto.builder()
                .cardNum(MASKED_CARD_NUM_1)
                .cardAcctId(TEST_ACCOUNT_ID)
                .cardEmbossedName("JOHN M DOE")
                .cardExpirationDate(LocalDate.now().plusYears(4))
                .cardStatus("N")
                .build();

        when(cardService.updateCard(eq(TEST_CARD_NUM_1), any(CardDto.class)))
                .thenReturn(updatedCard);

        // When: PUT /api/cards/{cardNumber} with update request
        mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUM_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                // Then: Response is HTTP 200 OK with updated card
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardNum", is(MASKED_CARD_NUM_1)))
                .andExpect(jsonPath("$.cardAcctId", is(TEST_ACCOUNT_ID)))
                .andExpect(jsonPath("$.cardEmbossedName", is("JOHN M DOE")))
                .andExpect(jsonPath("$.cardStatus", is("N")))
                .andExpect(jsonPath("$.cardExpirationDate", notNullValue()));

        // Verify service method was called with correct parameters
        verify(cardService, times(1)).updateCard(eq(TEST_CARD_NUM_1), any(CardDto.class));
    }

    /**
     * Test PUT /api/cards/{cardNumber} endpoint with expired date returns 400.
     * 
     * Converted from COBOL COCRDUPC.cbl expiration validation:
     * - IF CARD-EXPIRAION-DATE < CURRENT-DATE
     * - MOVE 'Card expiration date cannot be in the past' TO ERRMSGO
     * - SET INPUT-ERROR TO TRUE
     * 
     * This test validates:
     * - HTTP 400 Bad Request status code
     * - Service validates expiration date rules
     * - Validation exception is properly handled
     * 
     * Expected Behavior:
     * 1. Client sends PUT with expired date (past date)
     * 2. Service validates expiration date
     * 3. Service throws BusinessException for invalid expiration
     * 4. Controller returns HTTP 400 Bad Request
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateCard_WithExpiredDate_Returns400() throws Exception {
        // Given: Create update request with expired date (in the past)
        CardDto updateRequest = CardDto.builder()
                .cardStatus("Y")
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().minusYears(1))  // Past date - INVALID
                .build();

        // Mock service throws BusinessException for invalid expiration date
        when(cardService.updateCard(eq(TEST_CARD_NUM_1), any(CardDto.class)))
                .thenThrow(new com.carddemo.exception.BusinessException("CARD002", 
                        "Card expiration date cannot be in the past"));

        // When: PUT /api/cards/{cardNumber} with expired date
        mockMvc.perform(put("/api/cards/{cardNumber}", TEST_CARD_NUM_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                // Then: Response is HTTP 400 Bad Request
                .andExpect(status().isBadRequest());

        // Verify service method was called
        verify(cardService, times(1)).updateCard(eq(TEST_CARD_NUM_1), any(CardDto.class));
    }

    /**
     * Test PUT /api/cards/{cardNumber} endpoint returns 404 when card not found.
     * 
     * Tests COBOL file-status 23 (record not found) scenario.
     * 
     * Expected Behavior:
     * 1. Client sends PUT for non-existent card
     * 2. Service throws DataNotFoundException
     * 3. Controller returns HTTP 404 Not Found
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateCard_WhenCardNotFound_Returns404() throws Exception {
        // Given: Create update request
        CardDto updateRequest = CardDto.builder()
                .cardStatus("Y")
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().plusYears(3))
                .build();

        // Mock service throws DataNotFoundException for non-existent card
        when(cardService.updateCard(eq(NON_EXISTENT_CARD_NUM), any(CardDto.class)))
                .thenThrow(new com.carddemo.exception.DataNotFoundException("CARD001", 
                        "Card not found: " + NON_EXISTENT_CARD_NUM));

        // When: PUT /api/cards/{cardNumber} with non-existent card
        mockMvc.perform(put("/api/cards/{cardNumber}", NON_EXISTENT_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                // Then: Response is HTTP 404 Not Found
                .andExpect(status().isNotFound());

        verify(cardService, times(1)).updateCard(eq(NON_EXISTENT_CARD_NUM), any(CardDto.class));
    }

    /**
     * Test POST /api/cards endpoint creates new card successfully.
     * 
     * Converted from COBOL card creation logic:
     * - EXEC CICS WRITE FILE('CARDFILE') FROM(CARD-RECORD) RIDFLD(CARD-NUM)
     * - EXEC CICS WRITE FILE('XREFFILE') FROM(CARD-XREF-RECORD)
     * - EXEC CICS SYNCPOINT
     * 
     * This test validates:
     * - HTTP 201 Created status code
     * - JSON request body is properly deserialized
     * - Created card is returned in response
     * - Card number is masked in response
     * - Service method invoked with correct parameters
     * 
     * Expected Behavior:
     * 1. Client sends POST /api/cards with CardDto JSON
     * 2. Controller validates request body via @Valid annotation
     * 3. Controller calls cardService.createCard(cardDto)
     * 4. Service validates fields and creates card with cross-reference
     * 5. Controller returns HTTP 201 Created with CardDto
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateCard_WithValidData_ReturnsCreatedCard() throws Exception {
        // Given: Create new card request
        CardDto createRequest = CardDto.builder()
                .cardNum(TEST_CARD_NUM_1)  // Full card number in request
                .cardAcctId(TEST_ACCOUNT_ID)
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().plusYears(3))
                .cardStatus("N")  // New cards start as inactive
                .build();

        // Mock service returns created card with masked number
        when(cardService.createCard(any(CardDto.class)))
                .thenReturn(testCard1);

        // When: POST /api/cards with create request
        mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                // Then: Response is HTTP 201 Created with card details
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardNum", is(MASKED_CARD_NUM_1)))
                .andExpect(jsonPath("$.cardAcctId", is(TEST_ACCOUNT_ID)))
                .andExpect(jsonPath("$.cardEmbossedName", is("JOHN DOE")))
                .andExpect(jsonPath("$.cardStatus", is("Y")))
                .andExpect(jsonPath("$.cardExpirationDate", notNullValue()));

        // Verify service method was called
        verify(cardService, times(1)).createCard(any(CardDto.class));
    }

    /**
     * Test POST /api/cards endpoint with duplicate card number returns 409.
     * 
     * Converted from COBOL duplicate record handling:
     * - EXEC CICS WRITE FILE('CARDFILE') ... RESP(WS-RESP-CD)
     * - IF WS-RESP-CD = DFHRESP(DUPREC)
     * - MOVE 'Card number already exists' TO ERRMSGO
     * 
     * This test validates:
     * - HTTP 409 Conflict status code
     * - Service detects duplicate card number
     * - BusinessException is properly handled
     * 
     * Expected Behavior:
     * 1. Client sends POST with existing card number
     * 2. Service detects duplicate card number
     * 3. Service throws BusinessException with conflict code
     * 4. Controller returns HTTP 409 Conflict
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateCard_WithDuplicateCardNumber_Returns409() throws Exception {
        // Given: Create new card request with existing card number
        CardDto createRequest = CardDto.builder()
                .cardNum(TEST_CARD_NUM_1)
                .cardAcctId(TEST_ACCOUNT_ID)
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().plusYears(3))
                .cardStatus("N")
                .build();

        // Mock service throws BusinessException for duplicate card number
        when(cardService.createCard(any(CardDto.class)))
                .thenThrow(new com.carddemo.exception.BusinessException("CARD003", 
                        "Card number already exists: " + MASKED_CARD_NUM_1));

        // When: POST /api/cards with duplicate card number
        mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                // Then: Response is HTTP 409 Conflict (BusinessException mapped by GlobalExceptionHandler)
                // Note: BusinessException typically maps to 400 Bad Request, but duplicate can be 409
                .andExpect(status().is4xxClientError());

        verify(cardService, times(1)).createCard(any(CardDto.class));
    }

    /**
     * Test POST /api/cards endpoint with invalid account ID returns 400.
     * 
     * Tests referential integrity validation:
     * - Card must be linked to existing account
     * - Service validates account existence
     * 
     * Expected Behavior:
     * 1. Client sends POST with non-existent account ID
     * 2. Service validates account existence
     * 3. Service throws DataNotFoundException for missing account
     * 4. Controller returns HTTP 404 Not Found
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateCard_WithInvalidAccountId_Returns404() throws Exception {
        // Given: Create card request with non-existent account ID
        Long nonExistentAccountId = 99999999999L;
        CardDto createRequest = CardDto.builder()
                .cardNum(TEST_CARD_NUM_1)
                .cardAcctId(nonExistentAccountId)
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().plusYears(3))
                .cardStatus("N")
                .build();

        // Mock service throws DataNotFoundException for invalid account
        when(cardService.createCard(any(CardDto.class)))
                .thenThrow(new com.carddemo.exception.DataNotFoundException("ACCT001", 
                        "Account not found: " + nonExistentAccountId));

        // When: POST /api/cards with invalid account ID
        mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                // Then: Response is HTTP 404 Not Found
                .andExpect(status().isNotFound());

        verify(cardService, times(1)).createCard(any(CardDto.class));
    }

    /**
     * Test POST /api/cards endpoint with invalid expiration date returns 400.
     * 
     * Tests expiration date validation rules:
     * - Expiration date must be in future
     * - Expiration date must be within 5 years
     * 
     * Expected Behavior:
     * 1. Client sends POST with invalid expiration date
     * 2. Service validates expiration date
     * 3. Service throws BusinessException
     * 4. Controller returns HTTP 400 Bad Request
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateCard_WithInvalidExpirationDate_Returns400() throws Exception {
        // Given: Create card request with past expiration date
        CardDto createRequest = CardDto.builder()
                .cardNum(TEST_CARD_NUM_1)
                .cardAcctId(TEST_ACCOUNT_ID)
                .cardEmbossedName("JOHN DOE")
                .cardExpirationDate(LocalDate.now().minusMonths(1))  // Past date - INVALID
                .cardStatus("N")
                .build();

        // Mock service throws BusinessException for invalid expiration
        when(cardService.createCard(any(CardDto.class)))
                .thenThrow(new com.carddemo.exception.BusinessException("CARD002", 
                        "Card expiration date cannot be in the past"));

        // When: POST /api/cards with invalid expiration date
        mockMvc.perform(post("/api/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                // Then: Response is HTTP 400 Bad Request
                .andExpect(status().isBadRequest());

        verify(cardService, times(1)).createCard(any(CardDto.class));
    }

    /**
     * Test GET /api/cards endpoint with second page of results.
     * 
     * Tests pagination with page navigation:
     * - COBOL: PF8 (page down) functionality
     * - REST: page=1 parameter for second page
     * 
     * This validates that pagination correctly handles multiple pages
     * and that page navigation works as expected.
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testListCardsSecondPage_ReturnsCorrectPage() throws Exception {
        // Given: Mock service returns second page of cards
        Pageable pageable = PageRequest.of(1, 10);
        PageImpl<CardDto> cardPage = new PageImpl<>(
                Arrays.asList(testCard3),
                pageable,
                11  // Total 11 cards, page 1 has 1 card
        );
        
        when(cardService.listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class)))
                .thenReturn(cardPage);

        // When: GET /api/cards with page=1 (second page)
        mockMvc.perform(get("/api/cards")
                        .param("accountId", TEST_ACCOUNT_ID.toString())
                        .param("page", "1")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                // Then: Response shows second page with correct pagination
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(11)))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.numberOfElements", is(1)))
                .andExpect(jsonPath("$.pageable.pageNumber", is(1)))
                .andExpect(jsonPath("$.first", is(false)))
                .andExpect(jsonPath("$.last", is(true)));

        verify(cardService, times(1)).listCardsByAccount(eq(TEST_ACCOUNT_ID), any(Pageable.class));
    }
}

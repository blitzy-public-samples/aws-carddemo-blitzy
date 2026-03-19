/*
 * ============================================================================
 * CardControllerTest.java — MockMvc Controller Test for CardController
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM to Java 25 + Spring Boot 3.5.x
 *
 * Source COBOL artifacts covered by this test:
 *   - COCRDLIC.cbl  — Credit Card List program (CICS transaction CCLI)
 *                      STARTBR/READNEXT browse with WS-MAX-SCREEN-LINES (7 rows)
 *   - COCRDSLC.cbl  — Credit Card Detail/Selection program (CICS transaction CCDL)
 *                      9100-GETCARD-BYACCTCARD (primary key) and
 *                      9150-GETCARD-BYACCT (CARDAIX alternate index)
 *   - COCRDUPC.cbl  — Credit Card Update program (CICS transaction CCUP)
 *                      1200-EDIT-MAP-INPUTS, 9000-READ-DATA, 9200-WRITE-PROCESSING
 *   - COCRDLI.bms   — Credit Card List BMS screen map
 *   - COCRDSL.bms   — Credit Card Detail BMS screen map
 *   - COCRDUP.bms   — Credit Card Update BMS screen map
 *   - CVACT02Y.cpy  — Card data record layout (150 bytes):
 *                      CARD-NUM PIC X(16), CARD-ACCT-ID PIC 9(11),
 *                      CARD-CVV-CD PIC 9(03), CARD-EMBOSSED-NAME PIC X(50),
 *                      CARD-EXPIRAION-DATE PIC X(10), CARD-ACTIVE-STATUS PIC X(01)
 *
 * Test Coverage Mapping:
 *   1. listCards_returnsOkWithPaginatedResults
 *          ← COCRDLIC 0000-MAIN → 9000-READ-FORWARD (STARTBR/READNEXT loop)
 *   2. listCards_withAccountFilter_returnsFilteredResults
 *          ← COCRDLIC 9500-FILTER-RECORDS (CARDAIX alternate index)
 *   3. getCardDetail_returnsOkWithCardData
 *          ← COCRDSLC 9100-GETCARD-BYACCTCARD (primary key READ CARDDAT)
 *   4. getCardDetail_notFound_returns404
 *          ← COCRDSLC DFHRESP(NOTFND), file status '23'
 *   5. getCardDetail_withAccountIdQuery_usesAIXLookup
 *          ← COCRDSLC 9150-GETCARD-BYACCT (CARDAIX alternate index)
 *   6. updateCard_returnsOkWithUpdatedCard
 *          ← COCRDUPC 1200-EDIT-MAP-INPUTS → 9000-READ-DATA → REWRITE
 *   7. updateCard_notFound_returns404
 *          ← COCRDUPC CARD-NOT-FOUND during READ
 *   8. updateCard_optimisticLockConflict_returns409
 *          ← COCRDUPC DATA-WAS-CHANGED-BEFORE-UPDATE (concurrent modification)
 *   9. updateCard_validationError_returns400
 *          ← COCRDUPC 1200-EDIT-MAP-INPUTS validation failures
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo.controller;

// Internal imports — controller under test and its dependencies
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.config.SecurityConfig;
import com.cardemo.entity.Card;
import com.cardemo.service.online.CreditCardDetailService;
import com.cardemo.service.online.CreditCardListService;
import com.cardemo.service.online.CreditCardUpdateService;
import com.cardemo.service.online.CreditCardUpdateService.CardUpdateRequest;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// External imports — Spring Boot Test and Spring Framework Test
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// External imports — Jackson JSON (provided by spring-boot-starter-web)
import com.fasterxml.jackson.databind.ObjectMapper;

// External imports — Jakarta Persistence exception
import jakarta.persistence.OptimisticLockException;

// Static imports — Mockito stubbing and verification
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

// Static imports — Spring MockMvc request builders and result matchers
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Java standard library
import java.util.List;
import java.util.Map;

/**
 * MockMvc controller tests for {@link CardController} — tests the REST credit
 * card endpoints that translate CICS transactions CCLI (Credit Card List),
 * CCDL (Credit Card Detail), and CCUP (Credit Card Update) into stateless REST.
 *
 * <p>This test uses {@code @WebMvcTest(CardController.class)} to limit the
 * application context to the web layer only. All three required service
 * dependencies are mocked via {@code @MockitoBean}.</p>
 *
 * <p>Uses {@code @Import(SecurityConfig.class)} to load the custom security
 * filter chain into the test context, which configures authorization rules
 * requiring authentication for {@code /api/**} endpoints. The
 * {@code @WithMockUser} annotation provides a mock authenticated user.</p>
 *
 * <h3>COBOL Program ↔ Endpoint Mapping</h3>
 * <table>
 *   <tr><th>COBOL Program</th><th>Transaction</th><th>Endpoint</th><th>Tests</th></tr>
 *   <tr>
 *     <td>COCRDLIC.cbl</td><td>CCLI</td>
 *     <td>GET /api/cards</td>
 *     <td>Tests 1–2 (paginated list, account filter)</td>
 *   </tr>
 *   <tr>
 *     <td>COCRDSLC.cbl</td><td>CCDL</td>
 *     <td>GET /api/cards/{num}</td>
 *     <td>Tests 3–5 (detail view, not found, AIX lookup)</td>
 *   </tr>
 *   <tr>
 *     <td>COCRDUPC.cbl</td><td>CCUP</td>
 *     <td>PUT /api/cards/{num}</td>
 *     <td>Tests 6–9 (update ok, not found, 409 conflict, 400 validation)</td>
 *   </tr>
 * </table>
 *
 * @see CardController
 * @see CreditCardListService
 * @see CreditCardDetailService
 * @see CreditCardUpdateService
 */
@WebMvcTest(CardController.class)
@Import(SecurityConfig.class)
@WithMockUser
class CardControllerTest {

    /**
     * MockMvc instance for performing simulated HTTP requests auto-configured
     * by {@code @WebMvcTest} and {@code @AutoConfigureMockMvc}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson ObjectMapper for serializing Java objects to JSON request bodies.
     * Used for PUT /api/cards/{num} update request body construction.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mock CreditCardListService — replaces the real service bean for isolated
     * controller testing. Maps to COCRDLIC.cbl CCLI transaction.
     *
     * <p>Uses {@code @MockitoBean} (Spring Framework 6.2+) instead of the
     * deprecated {@code @MockBean} to avoid compilation errors under
     * {@code -Xlint:all -Werror}.</p>
     */
    @MockitoBean
    private CreditCardListService creditCardListService;

    /**
     * Mock CreditCardDetailService — replaces the real service bean for isolated
     * controller testing. Maps to COCRDSLC.cbl CCDL transaction.
     */
    @MockitoBean
    private CreditCardDetailService creditCardDetailService;

    /**
     * Mock CreditCardUpdateService — replaces the real service bean for isolated
     * controller testing. Maps to COCRDUPC.cbl CCUP transaction.
     */
    @MockitoBean
    private CreditCardUpdateService creditCardUpdateService;

    // ========================================================================
    // Test 1: GET /api/cards — Paginated card list
    // COCRDLIC.cbl 0000-MAIN → 9000-READ-FORWARD (STARTBR/READNEXT loop)
    // WS-MAX-SCREEN-LINES=7 rows per BMS screen → 10 per API page
    // ========================================================================

    /**
     * Verifies that GET /api/cards returns HTTP 200 OK with a paginated card list.
     *
     * <p>Maps to COCRDLIC.cbl 0000-MAIN paragraph invoking 9000-READ-FORWARD
     * which performs EXEC CICS STARTBR / READNEXT to browse CARDDAT records
     * up to WS-MAX-SCREEN-LINES (7) rows per BMS screen. The REST API adapter
     * uses 10 items per page as the default page size.</p>
     *
     * <p>CVACT02Y.cpy field verification: The JSON response contains card data
     * with CARD-NUM (PIC X(16)), CARD-ACCT-ID (PIC 9(11)),
     * CARD-EMBOSSED-NAME (PIC X(50)), CARD-ACTIVE-STATUS (PIC X(01)).</p>
     */
    @Test
    @DisplayName("GET /api/cards - Returns 200 OK with paginated card list "
            + "(← COCRDLIC.cbl STARTBR/READNEXT browse pattern)")
    void listCards_returnsOkWithPaginatedResults() throws Exception {
        // Arrange: Create test Card entities matching CVACT02Y.cpy layout
        Card card1 = new Card(
                "4111111111111111",  // CARD-NUM PIC X(16)
                "00000000001",      // CARD-ACCT-ID PIC 9(11)
                "123",              // CARD-CVV-CD PIC 9(03) [PII]
                "JOHN DOE",         // CARD-EMBOSSED-NAME PIC X(50)
                "2028-12-31",       // CARD-EXPIRAION-DATE PIC X(10)
                "Y"                 // CARD-ACTIVE-STATUS PIC X(01)
        );
        Card card2 = new Card(
                "4222222222222222",
                "00000000002",
                "456",
                "JANE DOE",
                "2029-06-30",
                "Y"
        );

        // Build paginated result — 10 items per page, page 0, total 2
        Page<Card> page = new PageImpl<>(
                List.of(card1, card2),
                PageRequest.of(0, 10),
                2
        );

        // Mock CreditCardListService.listCards — maps COCRDLIC 9000-READ-FORWARD
        given(creditCardListService.listCards(isNull(), isNull(), eq(0)))
                .willReturn(page);

        // Act & Assert: Verify HTTP 200 and paginated JSON structure
        mockMvc.perform(get("/api/cards")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].cardNum").value("4111111111111111"))
                .andExpect(jsonPath("$.content[0].accountId").value("00000000001"))
                .andExpect(jsonPath("$.content[0].embossedName").value("JOHN DOE"))
                .andExpect(jsonPath("$.content[0].activeStatus").value("Y"))
                .andExpect(jsonPath("$.content[1].cardNum").value("4222222222222222"))
                .andExpect(jsonPath("$.content[1].accountId").value("00000000002"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));

        // Verify service interaction
        verify(creditCardListService).listCards(isNull(), isNull(), eq(0));
    }

    // ========================================================================
    // Test 2: GET /api/cards?accountId=... — Filtered by account (CARDAIX)
    // COCRDLIC.cbl 9500-FILTER-RECORDS → CARD-ACCT-ID comparison
    // ========================================================================

    /**
     * Verifies that GET /api/cards?accountId=00000000001 returns filtered results.
     *
     * <p>Maps to COCRDLIC.cbl 9500-FILTER-RECORDS paragraph where
     * IF CARD-ACCT-ID = WS-ACCTFILTER filters records by account. This uses
     * the CARDAIX (VSAM Alternate Index) access path keyed on
     * CARD-ACCT-ID at position 16 with length 11.</p>
     */
    @Test
    @DisplayName("GET /api/cards?accountId=... - Returns filtered results via CARDAIX "
            + "(← COCRDLIC.cbl 9500-FILTER-RECORDS)")
    void listCards_withAccountFilter_returnsFilteredResults() throws Exception {
        // Arrange: Both cards belong to account 00000000001
        Card card1 = new Card(
                "4111111111111111",
                "00000000001",
                "123",
                "JOHN DOE",
                "2028-12-31",
                "Y"
        );
        Card card2 = new Card(
                "4333333333333333",
                "00000000001",
                "789",
                "JOHN DOE JR",
                "2027-03-31",
                "Y"
        );

        Page<Card> filteredPage = new PageImpl<>(
                List.of(card1, card2),
                PageRequest.of(0, 10),
                2
        );

        // Mock with account filter — CARDAIX alternate index lookup
        given(creditCardListService.listCards(eq("00000000001"), isNull(), eq(0)))
                .willReturn(filteredPage);

        // Act & Assert: Verify all returned cards match the account filter
        mockMvc.perform(get("/api/cards")
                        .param("accountId", "00000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].accountId").value("00000000001"))
                .andExpect(jsonPath("$.content[1].accountId").value("00000000001"));

        verify(creditCardListService).listCards(eq("00000000001"), isNull(), eq(0));
    }

    // ========================================================================
    // Test 3: GET /api/cards/{num} — Card detail (primary key READ)
    // COCRDSLC.cbl 9100-GETCARD-BYACCTCARD (READ CARDDAT BY KEY)
    // ========================================================================

    /**
     * Verifies that GET /api/cards/{num} returns HTTP 200 OK with card detail.
     *
     * <p>Maps to COCRDSLC.cbl 9100-GETCARD-BYACCTCARD paragraph:
     * EXEC CICS READ FILE('CARDDAT') INTO(CARD-RECORD) RIDFLD(WS-CARD-RID-CARDNUM).
     * The CVACT02Y.cpy record layout defines all fields verified below.</p>
     *
     * <p>CVACT02Y.cpy field mapping:
     * <ul>
     *   <li>CARD-NUM PIC X(16) → cardNum (String, 16 chars)</li>
     *   <li>CARD-ACCT-ID PIC 9(11) → accountId (String, 11 digits)</li>
     *   <li>CARD-CVV-CD PIC 9(03) → cvvCode (String, 3 digits, PII)</li>
     *   <li>CARD-EMBOSSED-NAME PIC X(50) → embossedName</li>
     *   <li>CARD-EXPIRAION-DATE PIC X(10) → expirationDate</li>
     *   <li>CARD-ACTIVE-STATUS PIC X(01) → activeStatus ('Y'/'N')</li>
     * </ul></p>
     */
    @Test
    @DisplayName("GET /api/cards/{num} - Returns 200 OK with card detail "
            + "(← COCRDSLC.cbl 9100-GETCARD-BYACCTCARD)")
    void getCardDetail_returnsOkWithCardData() throws Exception {
        // Arrange: Build card with all CVACT02Y.cpy fields populated
        Card card = new Card(
                "4111111111111111",
                "00000000001",
                "123",
                "JOHN DOE",
                "2028-12-31",
                "Y"
        );

        given(creditCardDetailService.viewCardDetail(
                eq("4111111111111111"), isNull()))
                .willReturn(card);

        // Act & Assert: Verify all CVACT02Y.cpy fields in JSON response
        mockMvc.perform(get("/api/cards/{num}", "4111111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNum").value(card.getCardNum()))
                .andExpect(jsonPath("$.accountId").value(card.getAccountId()))
                .andExpect(jsonPath("$.embossedName").value("JOHN DOE"))
                .andExpect(jsonPath("$.expirationDate").value("2028-12-31"))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.cvvCode").value("123"));

        // Verify service called once with correct card number
        verify(creditCardDetailService).viewCardDetail(
                eq("4111111111111111"), isNull());
    }

    // ========================================================================
    // Test 4: GET /api/cards/{num} — Card not found (HTTP 404)
    // COCRDSLC.cbl 9100-GETCARD-BYACCTCARD → WS-RESP-CD=13 (NOTFND)
    // Maps VSAM file status '23' (record not found)
    // ========================================================================

    /**
     * Verifies that GET /api/cards/{num} returns HTTP 404 Not Found when the
     * card number does not exist in CARDDAT.
     *
     * <p>Maps to COCRDSLC.cbl DID-NOT-FIND-ACCTCARD-COMBO condition when
     * EXEC CICS READ returns DFHRESP(NOTFND). The VSAM file status code '23'
     * is translated to {@link RecordNotFoundException} in Java.</p>
     */
    @Test
    @DisplayName("GET /api/cards/{num} - Returns 404 when card not found "
            + "(← COCRDSLC.cbl DFHRESP(NOTFND), STATUS '23')")
    void getCardDetail_notFound_returns404() throws Exception {
        // Arrange: Service throws RecordNotFoundException (status '23')
        given(creditCardDetailService.viewCardDetail(
                eq("9999999999999999"), isNull()))
                .willThrow(new RecordNotFoundException(
                        "Card not found with key: 9999999999999999"));

        // Act & Assert: Verify HTTP 404
        mockMvc.perform(get("/api/cards/{num}", "9999999999999999"))
                .andExpect(status().isNotFound());
    }

    // ========================================================================
    // Test 5: GET /api/cards/{num}?accountId=... — AIX lookup
    // COCRDSLC.cbl 9150-GETCARD-BYACCT → READ CARDAIX
    // VSAM Alternate Index: XREF-ACCT-ID (position 16, length 11)
    // ========================================================================

    /**
     * Verifies that GET /api/cards/{num}?accountId=... uses the CARDAIX
     * alternate index access path to look up the card.
     *
     * <p>Maps to COCRDSLC.cbl 9150-GETCARD-BYACCT paragraph:
     * EXEC CICS READ FILE('CARDAIX') INTO(CARD-RECORD)
     * RIDFLD(WS-CARD-RID-ACCTID). The accountId query parameter triggers
     * the alternate index lookup path instead of the primary key lookup.</p>
     */
    @Test
    @DisplayName("GET /api/cards/{num}?accountId=... - Uses CARDAIX alternate "
            + "index lookup (← COCRDSLC.cbl 9150-GETCARD-BYACCT)")
    void getCardDetail_withAccountIdQuery_usesAIXLookup() throws Exception {
        // Arrange: Card found via AIX (account-based lookup)
        Card card = new Card(
                "4111111111111111",
                "00000000001",
                "123",
                "JOHN DOE",
                "2028-12-31",
                "Y"
        );

        given(creditCardDetailService.viewCardDetail(
                eq("4111111111111111"), eq("00000000001")))
                .willReturn(card);

        // Act & Assert: Verify AIX lookup returns card detail
        mockMvc.perform(get("/api/cards/{num}", "4111111111111111")
                        .param("accountId", "00000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNum").value("4111111111111111"))
                .andExpect(jsonPath("$.accountId").value("00000000001"));

        // Verify both parameters passed to service (AIX path)
        verify(creditCardDetailService).viewCardDetail(
                eq("4111111111111111"), eq("00000000001"));
    }

    // ========================================================================
    // Test 6: PUT /api/cards/{num} — Successful card update
    // COCRDUPC.cbl 0000-MAIN → 1200-EDIT-MAP-INPUTS → 9000-READ-DATA →
    // 9200-WRITE-PROCESSING (READ UPDATE → REWRITE)
    // ========================================================================

    /**
     * Verifies that PUT /api/cards/{num} returns HTTP 200 OK with the
     * updated card entity.
     *
     * <p>Maps to COCRDUPC.cbl complete update flow:
     * <ol>
     *   <li>1200-EDIT-MAP-INPUTS (field validation)</li>
     *   <li>9000-READ-DATA (EXEC CICS READ UPDATE)</li>
     *   <li>9200-WRITE-PROCESSING (EXEC CICS REWRITE with @Version lock)</li>
     * </ol></p>
     *
     * <p>Request body maps to COCRDUP.bms fields:
     * embossedName (← NAMEI), activeStatus (← CRDSTATI), expiryMonth (← EXPMON),
     * expiryYear (← EXPYEAR).</p>
     */
    @Test
    @DisplayName("PUT /api/cards/{num} - Returns 200 OK with updated card "
            + "(← COCRDUPC.cbl 1200-EDIT → 9000-READ → 9200-WRITE)")
    void updateCard_returnsOkWithUpdatedCard() throws Exception {
        // Arrange: Build the expected updated Card entity
        Card updatedCard = new Card(
                "4111111111111111",
                "00000000001",
                "123",
                "JOHN DOE",
                "2028-12-31",
                "Y"
        );

        given(creditCardUpdateService.updateCard(
                eq("4111111111111111"), any(CardUpdateRequest.class)))
                .willReturn(updatedCard);

        // Build request body matching COCRDUP.bms map fields
        Map<String, String> requestBody = Map.of(
                "embossedName", "JOHN DOE",
                "activeStatus", "Y",
                "expiryMonth", "12",
                "expiryYear", "2028"
        );

        // Act & Assert: Verify successful update response
        mockMvc.perform(put("/api/cards/{num}", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNum").value("4111111111111111"))
                .andExpect(jsonPath("$.embossedName").value("JOHN DOE"))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.expirationDate").value("2028-12-31"))
                .andExpect(jsonPath("$.accountId").value("00000000001"));

        verify(creditCardUpdateService).updateCard(
                eq("4111111111111111"), any(CardUpdateRequest.class));
    }

    // ========================================================================
    // Test 7: PUT /api/cards/{num} — Card not found (HTTP 404)
    // COCRDUPC.cbl → CARD-NOT-FOUND during EXEC CICS READ
    // ========================================================================

    /**
     * Verifies that PUT /api/cards/{num} returns HTTP 404 Not Found when the
     * card number does not exist in CARDDAT.
     *
     * <p>Maps to COCRDUPC.cbl CARD-NOT-FOUND condition during 9000-READ-DATA
     * where EXEC CICS READ DATASET('CARDDAT') returns DFHRESP(NOTFND).</p>
     */
    @Test
    @DisplayName("PUT /api/cards/{num} - Returns 404 when card not found "
            + "(← COCRDUPC.cbl CARD-NOT-FOUND during READ)")
    void updateCard_notFound_returns404() throws Exception {
        // Arrange: Service throws RecordNotFoundException
        given(creditCardUpdateService.updateCard(
                eq("9999999999999999"), any(CardUpdateRequest.class)))
                .willThrow(new RecordNotFoundException("Card", "9999999999999999"));

        Map<String, String> requestBody = Map.of(
                "embossedName", "JOHN DOE",
                "activeStatus", "Y",
                "expiryMonth", "12",
                "expiryYear", "2028"
        );

        // Act & Assert: Verify HTTP 404
        mockMvc.perform(put("/api/cards/{num}", "9999999999999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isNotFound());
    }

    // ========================================================================
    // Test 8: PUT /api/cards/{num} — Optimistic lock conflict (HTTP 409)
    // COCRDUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE (concurrent modification)
    // CCUP-OLD-DETAILS comparison between READ UPDATE and REWRITE
    // ========================================================================

    /**
     * Verifies that PUT /api/cards/{num} returns HTTP 409 Conflict when
     * concurrent modification is detected via optimistic locking.
     *
     * <p>Maps to COCRDUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE condition at
     * paragraph 9300-CHECK-CHANGE-IN-REC (line 1498). In the COBOL program,
     * this occurs when the data read during EXEC CICS READ UPDATE differs
     * from the values stored in CCUP-OLD-DETAILS at the time of REWRITE.
     * In Java, the JPA {@code @Version} field on the {@link Card} entity
     * triggers a {@link OptimisticLockException} on concurrent save.</p>
     *
     * <p>Note: In the real service, the OptimisticLockException from
     * cardRepository.save() is caught and wrapped as ValidationException.
     * However, deferred JPA flushes after transaction commit can propagate
     * the raw OptimisticLockException directly to the controller. This test
     * verifies the controller's explicit 409 handler for that scenario.</p>
     */
    @Test
    @DisplayName("PUT /api/cards/{num} - Returns 409 on optimistic lock conflict "
            + "(← COCRDUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE)")
    void updateCard_optimisticLockConflict_returns409() throws Exception {
        // Arrange: Service throws OptimisticLockException (deferred flush scenario)
        given(creditCardUpdateService.updateCard(
                eq("4111111111111111"), any(CardUpdateRequest.class)))
                .willThrow(new OptimisticLockException(
                        "Concurrent modification detected on card record"));

        Map<String, String> requestBody = Map.of(
                "embossedName", "JOHN DOE",
                "activeStatus", "Y",
                "expiryMonth", "12",
                "expiryYear", "2028"
        );

        // Act & Assert: Verify HTTP 409 with error body
        mockMvc.perform(put("/api/cards/{num}", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    // ========================================================================
    // Test 9: PUT /api/cards/{num} — Validation error (HTTP 400)
    // COCRDUPC.cbl 1200-EDIT-MAP-INPUTS validation failures:
    //   1230-EDIT-NAME (alphabetic check)
    //   1240-EDIT-CARDSTATUS ('Y'/'N' only)
    //   1250-EDIT-EXPIRY-MON (01-12)
    //   1260-EDIT-EXPIRY-YEAR (4-digit, not expired)
    // ========================================================================

    /**
     * Verifies that PUT /api/cards/{num} returns HTTP 400 Bad Request when
     * field validation fails.
     *
     * <p>Maps to COCRDUPC.cbl 1200-EDIT-MAP-INPUTS paragraph, which performs
     * sequential field validation:
     * <ul>
     *   <li>1230-EDIT-NAME: Alphabetic check on CARD-EMBOSSED-NAME</li>
     *   <li>1240-EDIT-CARDSTATUS: Only 'Y' or 'N' allowed</li>
     *   <li>1250-EDIT-EXPIRY-MON: Month must be 01–12</li>
     *   <li>1260-EDIT-EXPIRY-YEAR: 4-digit year, must not be expired</li>
     * </ul></p>
     */
    @Test
    @DisplayName("PUT /api/cards/{num} - Returns 400 on validation error "
            + "(← COCRDUPC.cbl 1200-EDIT-MAP-INPUTS validation failure)")
    void updateCard_validationError_returns400() throws Exception {
        // Arrange: Service throws ValidationException for invalid month (>12)
        given(creditCardUpdateService.updateCard(
                eq("4111111111111111"), any(CardUpdateRequest.class)))
                .willThrow(new ValidationException("expiryMonth",
                        "Month must be between 01 and 12"));

        // Invalid request body — month 13 is out of range per 1250-EDIT-EXPIRY-MON
        Map<String, String> requestBody = Map.of(
                "embossedName", "JOHN DOE",
                "activeStatus", "Y",
                "expiryMonth", "13",
                "expiryYear", "2028"
        );

        // Act & Assert: Verify HTTP 400 with error body
        mockMvc.perform(put("/api/cards/{num}", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}

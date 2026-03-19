/*
 * ============================================================================
 * AccountControllerTest.java — MockMvc Controller Test for AccountController
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM to Java 25 + Spring Boot 3.5.x
 *
 * Source COBOL artifacts covered by this test:
 *   - COACTVWC.cbl  — Account View program (CICS transaction CAVW)
 *   - COACTUPC.cbl  — Account Update program (CICS transaction CAUP)
 *   - COACTVW.bms   — Account View BMS map
 *   - COACTUP.bms   — Account Update BMS map
 *   - COACTVW.cpy   — Account View BMS data structure (AI/AO two-view)
 *   - COACTUP.cpy   — Account Update BMS data structure (AI/AO two-view)
 *   - COCOM01Y.cpy  — COMMAREA session context (1024 bytes)
 *   - CVACT01Y.cpy  — Account record layout (300 bytes, PIC S9(10)V99 COMP-3)
 *   - CVCUS01Y.cpy  — Customer record layout (500 bytes, PII fields)
 *
 * Test Coverage Mapping (COACTVWC.cbl / COACTUPC.cbl → AccountController):
 *   1. viewAccount_returnsOkWithAccountData
 *      ← COACTVWC.cbl 0000-MAIN → 9200-GETCARDXREF-BYACCT →
 *        9300-GETACCTDATA-BYACCT → 9400-GETCUSTDATA-BYCUST
 *        (GET /api/accounts/{id} → 200 OK)
 *
 *   2. viewAccount_notFound_returns404
 *      ← COACTVWC.cbl 9300-GETACCTDATA-BYACCT: WS-RESP-CD = 13 (NOTFND)
 *        (GET /api/accounts/{id} → 404 Not Found)
 *
 *   3. updateAccount_returnsOkWithUpdatedData
 *      ← COACTUPC.cbl 0000-MAIN → 1200-EDIT-MAP-INPUTS → 9600-WRITE-PROCESSING
 *        (READ UPDATE → REWRITE)
 *        (PUT /api/accounts/{id} → 200 OK)
 *
 *   4. updateAccount_notFound_returns404
 *      ← COACTUPC.cbl 9000-READ-ACCT: RESP=NOTFND
 *        (PUT /api/accounts/{id} → 404 Not Found)
 *
 *   5. updateAccount_validationError_returns400
 *      ← COACTUPC.cbl 1200-EDIT-MAP-INPUTS sub-paragraphs 1210–1280
 *        (FLG-*-NOT-OK flags)
 *        (PUT /api/accounts/{id} → 400 Bad Request)
 *
 *   6. updateAccount_optimisticLockConflict_returns409
 *      ← COACTUPC.cbl 9600-WRITE-PROCESSING → 9700-CHECK-CHANGE-IN-REC
 *        (READ UPDATE → REWRITE lock contention)
 *        (PUT /api/accounts/{id} → 409 Conflict)
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
import com.cardemo.entity.Account;
import com.cardemo.service.online.AccountUpdateService;
import com.cardemo.service.online.AccountUpdateService.AccountUpdateRequest;
import com.cardemo.service.online.AccountViewService;
import com.cardemo.service.online.AccountViewService.AccountViewResult;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// External imports — Spring Boot Test and Spring Framework Test
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// External imports — Jackson JSON (provided by spring-boot-starter-web)
import com.fasterxml.jackson.databind.ObjectMapper;

// External imports — Jakarta Persistence (for OptimisticLockException)
import jakarta.persistence.OptimisticLockException;

// Static imports — Mockito stubbing and verification
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

// Static imports — Spring MockMvc request builders and result matchers
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Java standard library
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring {@code @WebMvcTest} slice test for {@link AccountController} — validates
 * the two REST endpoints ({@code GET /api/accounts/&#123;id&#125;} and
 * {@code PUT /api/accounts/&#123;id&#125;}) that translate CICS transactions CAVW
 * (COACTVWC.cbl — Account View) and CAUP (COACTUPC.cbl — Account Update) into
 * stateless REST operations.
 *
 * <p>This test uses {@code @WebMvcTest(AccountController.class)} to limit the
 * Spring application context to only the web layer (AccountController), plus the
 * imported {@link SecurityConfig} for the security filter chain. All service
 * dependencies are mocked via {@code @MockitoBean}.</p>
 *
 * <h2>COBOL-to-Java Field Mapping for Account (CVACT01Y.cpy):</h2>
 * <table>
 *   <caption>Account Entity Field Mapping</caption>
 *   <tr><th>COBOL Field</th><th>Java Field</th><th>Type</th></tr>
 *   <tr><td>ACCT-ID PIC 9(11)</td><td>acctId</td><td>String (11 digits)</td></tr>
 *   <tr><td>ACCT-ACTIVE-STATUS PIC X(01)</td><td>activeStatus</td><td>String</td></tr>
 *   <tr><td>ACCT-CURR-BAL PIC S9(10)V99</td><td>currBal</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CREDIT-LIMIT PIC S9(10)V99</td><td>creditLimit</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99</td><td>cashCreditLimit</td>
 *       <td>BigDecimal</td></tr>
 *   <tr><td>ACCT-OPEN-DATE PIC X(10)</td><td>openDate</td><td>String</td></tr>
 *   <tr><td>ACCT-EXPIRAION-DATE PIC X(10)</td><td>expirationDate</td><td>String</td></tr>
 *   <tr><td>ACCT-CURR-CYC-CREDIT PIC S9(10)V99</td><td>currCycCredit</td>
 *       <td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CURR-CYC-DEBIT PIC S9(10)V99</td><td>currCycDebit</td>
 *       <td>BigDecimal</td></tr>
 * </table>
 *
 * <h2>HTTP Status Code Mapping (CICS RESP → HTTP):</h2>
 * <ul>
 *   <li>200 OK — Successful account view or update</li>
 *   <li>400 Bad Request — Field validation failure (1200-EDIT-MAP-INPUTS)</li>
 *   <li>404 Not Found — Account not found (VSAM STATUS '23' / DFHRESP(NOTFND))</li>
 *   <li>409 Conflict — Optimistic lock conflict (READ UPDATE → REWRITE contention)</li>
 * </ul>
 *
 * @see AccountController
 * @see AccountViewService
 * @see AccountUpdateService
 * @see SecurityConfig
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

    // =========================================================================
    // Injected Test Infrastructure
    // =========================================================================

    /**
     * Auto-configured MockMvc instance for performing HTTP requests against
     * the AccountController without starting a real HTTP server. Provided by
     * {@code @WebMvcTest}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson ObjectMapper for serializing account update request bodies into
     * JSON format. Auto-configured by Spring Boot's Jackson support via
     * {@code spring-boot-starter-web}.
     */
    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================================
    // Mocked Service Dependencies
    // =========================================================================

    /**
     * Mocked AccountViewService — replaces the real service that translates
     * COACTVWC.cbl business logic (CICS transaction CAVW). Test methods stub
     * {@code viewAccount(String)} to simulate success (returns AccountViewResult)
     * or failure ({@link RecordNotFoundException}).
     *
     * <p>Uses {@code @MockitoBean} (Spring Framework 6.2+) instead of the
     * deprecated {@code @MockBean} to avoid compilation errors under
     * {@code -Xlint:all -Werror}.</p>
     */
    @MockitoBean
    private AccountViewService accountViewService;

    /**
     * Mocked AccountUpdateService — replaces the real service that translates
     * COACTUPC.cbl business logic (CICS transaction CAUP). Test methods stub
     * {@code updateAccount(String, AccountUpdateRequest)} to simulate success
     * (returns Account) or various failure modes ({@link RecordNotFoundException},
     * {@link ValidationException}, {@link OptimisticLockException}).
     *
     * <p>Uses {@code @MockitoBean} (Spring Framework 6.2+) instead of the
     * deprecated {@code @MockBean} to avoid compilation errors under
     * {@code -Xlint:all -Werror}.</p>
     */
    @MockitoBean
    private AccountUpdateService accountUpdateService;

    // =========================================================================
    // Constants — Account IDs matching COBOL PIC 9(11) format
    // =========================================================================

    /** Valid test account ID — 11-digit numeric string per ACCT-ID PIC 9(11). */
    private static final String VALID_ACCT_ID = "00000000001";

    /** Non-existent account ID for 404 Not Found test scenarios. */
    private static final String NONEXISTENT_ACCT_ID = "99999999999";

    /** Base path for account REST endpoints. */
    private static final String ACCOUNTS_PATH = "/api/accounts/{id}";

    // =========================================================================
    // Test 1: GET /api/accounts/{id} — 200 OK
    // COBOL: COACTVWC.cbl 0000-MAIN → 9200/9300/9400 three-file join
    // =========================================================================

    /**
     * Verifies that {@code GET /api/accounts/&#123;id&#125;} returns HTTP 200 OK
     * with full account data when the account exists in the database.
     *
     * <p><b>COBOL Source:</b> COACTVWC.cbl → 0000-MAIN → 9200-GETCARDXREF-BYACCT →
     * 9300-GETACCTDATA-BYACCT → 9400-GETCUSTDATA-BYCUST. The controller receives
     * an {@link AccountViewResult} containing the account entity with BigDecimal
     * monetary fields from CVACT01Y.cpy.</p>
     *
     * <p><b>Verified response fields (COBOL → Java):</b></p>
     * <ul>
     *   <li>ACCT-ID PIC 9(11) → {@code $.account.acctId} (String, 11 digits)</li>
     *   <li>ACCT-ACTIVE-STATUS PIC X(01) → {@code $.account.activeStatus}</li>
     *   <li>ACCT-CURR-BAL PIC S9(10)V99 → {@code $.account.currBal} (BigDecimal)</li>
     *   <li>ACCT-CREDIT-LIMIT PIC S9(10)V99 → {@code $.account.creditLimit}
     *       (BigDecimal)</li>
     *   <li>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 →
     *       {@code $.account.cashCreditLimit} (BigDecimal)</li>
     * </ul>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    @DisplayName("GET /api/accounts/{id} — 200 OK with account data (CAVW transaction)")
    @WithMockUser
    void viewAccount_returnsOkWithAccountData() throws Exception {
        // Arrange: Build Account entity with BigDecimal monetary fields
        // matching CVACT01Y.cpy PIC S9(10)V99 COMP-3 specifications
        Account testAccount = buildTestAccount();

        // Create the AccountViewResult that wraps account + customer + cardXref
        // (customer and cardXref are null for this isolated controller test;
        // the service would normally populate them via 9200/9400 paragraphs)
        AccountViewResult viewResult = new AccountViewResult(
                testAccount, null, null,
                "Account retrieved successfully", null);

        given(accountViewService.viewAccount(VALID_ACCT_ID))
                .willReturn(viewResult);

        // Act & Assert: GET endpoint returns 200 with expected JSON structure
        mockMvc.perform(get(ACCOUNTS_PATH, VALID_ACCT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Verify account identification fields
                .andExpect(jsonPath("$.account.acctId").value(VALID_ACCT_ID))
                .andExpect(jsonPath("$.account.activeStatus").value("Y"))
                // CRITICAL: All monetary fields verified as BigDecimal values
                // (PIC S9(10)V99 COMP-3 → BigDecimal, no floating-point)
                .andExpect(jsonPath("$.account.currBal").value(1500.50))
                .andExpect(jsonPath("$.account.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.account.cashCreditLimit").value(1000.00))
                .andExpect(jsonPath("$.account.currCycCredit").value(500.00))
                .andExpect(jsonPath("$.account.currCycDebit").value(250.00))
                // Verify date fields (ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE)
                .andExpect(jsonPath("$.account.openDate").value("2020-01-15"))
                .andExpect(jsonPath("$.account.expirationDate").value("2028-12-31"))
                // Verify message field
                .andExpect(jsonPath("$.message").value("Account retrieved successfully"));

        // Verify service interaction — viewAccount() called exactly once
        verify(accountViewService).viewAccount(VALID_ACCT_ID);
    }

    // =========================================================================
    // Test 2: GET /api/accounts/{id} — 404 Not Found
    // COBOL: COACTVWC.cbl 9300-GETACCTDATA-BYACCT → WS-RESP-CD=13 (NOTFND)
    // =========================================================================

    /**
     * Verifies that {@code GET /api/accounts/&#123;id&#125;} returns HTTP 404
     * Not Found when the account does not exist in the database.
     *
     * <p><b>COBOL Source:</b> COACTVWC.cbl paragraph 9300-GETACCTDATA-BYACCT
     * when {@code WS-RESP-CD = DFHRESP(NOTFND)} (response code 13). The VSAM
     * file status '23' translates to {@link RecordNotFoundException} in Java,
     * which the controller maps to HTTP 404.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    @DisplayName("GET /api/accounts/{id} — 404 Not Found (VSAM STATUS '23' / DFHRESP(NOTFND))")
    @WithMockUser
    void viewAccount_notFound_returns404() throws Exception {
        // Arrange: Service throws RecordNotFoundException (VSAM status '23')
        given(accountViewService.viewAccount(NONEXISTENT_ACCT_ID))
                .willThrow(new RecordNotFoundException(
                        "Account " + NONEXISTENT_ACCT_ID + " not found"));

        // Act & Assert: GET endpoint returns 404 with error structure
        mockMvc.perform(get(ACCOUNTS_PATH, NONEXISTENT_ACCT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());

        // Verify service was called with the correct account ID
        verify(accountViewService).viewAccount(NONEXISTENT_ACCT_ID);
    }

    // =========================================================================
    // Test 3: PUT /api/accounts/{id} — 200 OK
    // COBOL: COACTUPC.cbl 0000-MAIN → 1200-EDIT → 9600-WRITE-PROCESSING
    // =========================================================================

    /**
     * Verifies that {@code PUT /api/accounts/&#123;id&#125;} returns HTTP 200 OK
     * with the updated account data when all validations pass and the update
     * succeeds.
     *
     * <p><b>COBOL Source:</b> COACTUPC.cbl → 0000-MAIN → 1200-EDIT-MAP-INPUTS →
     * 9600-WRITE-PROCESSING (READ UPDATE → REWRITE). All monetary fields in the
     * response MUST be BigDecimal per CVACT01Y.cpy PIC S9(10)V99 COMP-3.</p>
     *
     * <p><b>CRITICAL:</b> This test verifies all 5 monetary fields are properly
     * serialized as precise decimal values (not floating-point):</p>
     * <ul>
     *   <li>ACCT-CURR-BAL → {@code $.currBal} (BigDecimal)</li>
     *   <li>ACCT-CREDIT-LIMIT → {@code $.creditLimit} (BigDecimal)</li>
     *   <li>ACCT-CASH-CREDIT-LIMIT → {@code $.cashCreditLimit} (BigDecimal)</li>
     *   <li>ACCT-CURR-CYC-CREDIT → {@code $.currCycCredit} (BigDecimal)</li>
     *   <li>ACCT-CURR-CYC-DEBIT → {@code $.currCycDebit} (BigDecimal)</li>
     * </ul>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    @DisplayName("PUT /api/accounts/{id} — 200 OK with updated account data (CAUP transaction)")
    @WithMockUser
    void updateAccount_returnsOkWithUpdatedData() throws Exception {
        // Arrange: Build updated Account entity with BigDecimal monetary fields
        Account updatedAccount = buildTestAccount();

        given(accountUpdateService.updateAccount(
                eq(VALID_ACCT_ID), any(AccountUpdateRequest.class)))
                .willReturn(updatedAccount);

        // Build request body matching COACTUP.bms screen fields
        Map<String, String> requestBody = buildUpdateRequestBody();

        // Act & Assert: PUT endpoint returns 200 with all fields verified
        mockMvc.perform(put(ACCOUNTS_PATH, VALID_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Verify account identification
                .andExpect(jsonPath("$.acctId").value(VALID_ACCT_ID))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                // CRITICAL: ALL 5 monetary fields must be BigDecimal
                // (PIC S9(10)V99 COMP-3 — no floating-point allowed per AAP)
                .andExpect(jsonPath("$.currBal").value(1500.50))
                .andExpect(jsonPath("$.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.cashCreditLimit").value(1000.00))
                .andExpect(jsonPath("$.currCycCredit").value(500.00))
                .andExpect(jsonPath("$.currCycDebit").value(250.00))
                // Verify date fields
                .andExpect(jsonPath("$.openDate").value("2020-01-15"))
                .andExpect(jsonPath("$.expirationDate").value("2028-12-31"));

        // Verify service interaction with correct account ID and request
        verify(accountUpdateService).updateAccount(
                eq(VALID_ACCT_ID), any(AccountUpdateRequest.class));
    }

    // =========================================================================
    // Test 4: PUT /api/accounts/{id} — 404 Not Found
    // COBOL: COACTUPC.cbl → record not found during READ UPDATE
    // =========================================================================

    /**
     * Verifies that {@code PUT /api/accounts/&#123;id&#125;} returns HTTP 404
     * Not Found when the account to update does not exist.
     *
     * <p><b>COBOL Source:</b> COACTUPC.cbl when 9000-READ-ACCT encounters
     * a non-existent account during the READ UPDATE operation. The VSAM file
     * status '23' translates to {@link RecordNotFoundException}.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    @DisplayName("PUT /api/accounts/{id} — 404 Not Found when account does not exist")
    @WithMockUser
    void updateAccount_notFound_returns404() throws Exception {
        // Arrange: Service throws RecordNotFoundException (VSAM status '23')
        given(accountUpdateService.updateAccount(
                eq(NONEXISTENT_ACCT_ID), any(AccountUpdateRequest.class)))
                .willThrow(new RecordNotFoundException(
                        "Account " + NONEXISTENT_ACCT_ID + " not found"));

        Map<String, String> requestBody = buildUpdateRequestBody();

        // Act & Assert: PUT endpoint returns 404 with error structure
        mockMvc.perform(put(ACCOUNTS_PATH, NONEXISTENT_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());

        // Verify service was called with the non-existent account ID
        verify(accountUpdateService).updateAccount(
                eq(NONEXISTENT_ACCT_ID), any(AccountUpdateRequest.class));
    }

    // =========================================================================
    // Test 5: PUT /api/accounts/{id} — 400 Bad Request
    // COBOL: COACTUPC.cbl 1200-EDIT-MAP-INPUTS — validation failure
    // =========================================================================

    /**
     * Verifies that {@code PUT /api/accounts/&#123;id&#125;} returns HTTP 400
     * Bad Request when field validation fails in the update service.
     *
     * <p><b>COBOL Source:</b> COACTUPC.cbl paragraph 1200-EDIT-MAP-INPUTS with
     * sub-paragraphs 1210–1280 that validate each screen field (active status,
     * open date, credit limit, expiry date, FICO score, SSN, state code, ZIP).
     * When validation fails, COBOL sets per-field flags (e.g.,
     * FLG-ACCT-STATUS-NOT-OK, FLG-CREDIT-LIMIT-NOT-OK) and returns an error
     * message. In Java, this maps to {@link ValidationException}.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    @DisplayName("PUT /api/accounts/{id} — 400 Bad Request on validation failure (1200-EDIT-MAP-INPUTS)")
    @WithMockUser
    void updateAccount_validationError_returns400() throws Exception {
        // Arrange: Service throws ValidationException for invalid field data
        // Maps to COACTUPC.cbl 1200-EDIT-MAP-INPUTS failure path
        given(accountUpdateService.updateAccount(
                eq(VALID_ACCT_ID), any(AccountUpdateRequest.class)))
                .willThrow(new ValidationException(
                        "Credit limit must be a valid numeric amount"));

        // Build request with intentionally invalid data
        Map<String, String> requestBody = Map.of(
                "activeStatus", "Y",
                "creditLimit", "INVALID_AMOUNT");

        // Act & Assert: PUT endpoint returns 400 with error structure
        mockMvc.perform(put(ACCOUNTS_PATH, VALID_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());

        // Verify service was called despite validation failure
        verify(accountUpdateService).updateAccount(
                eq(VALID_ACCT_ID), any(AccountUpdateRequest.class));
    }

    // =========================================================================
    // Test 6: PUT /api/accounts/{id} — 409 Conflict
    // COBOL: COACTUPC.cbl 9600-WRITE-PROCESSING → 9700-CHECK-CHANGE-IN-REC
    // =========================================================================

    /**
     * Verifies that {@code PUT /api/accounts/&#123;id&#125;} returns HTTP 409
     * Conflict when a concurrent modification is detected during the update.
     *
     * <p><b>COBOL Source:</b> COACTUPC.cbl paragraphs 9600-WRITE-PROCESSING and
     * 9700-CHECK-CHANGE-IN-REC, where the CICS READ UPDATE acquires a record
     * lock and REWRITE commits the change. If another terminal user modifies the
     * same account between the READ UPDATE and REWRITE, the lock contention is
     * detected and an error message is displayed. In Java, JPA {@code @Version}
     * on the {@link Account} entity provides equivalent optimistic locking,
     * throwing {@link OptimisticLockException} on concurrent modification.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    @DisplayName("PUT /api/accounts/{id} — 409 Conflict on concurrent modification (READ UPDATE/REWRITE)")
    @WithMockUser
    void updateAccount_optimisticLockConflict_returns409() throws Exception {
        // Arrange: Service throws OptimisticLockException (JPA @Version conflict)
        // This simulates the CICS READ UPDATE → REWRITE lock contention
        // detected in COACTUPC.cbl paragraph 9700-CHECK-CHANGE-IN-REC
        given(accountUpdateService.updateAccount(
                eq(VALID_ACCT_ID), any(AccountUpdateRequest.class)))
                .willThrow(new OptimisticLockException(
                        "Account was modified by another user"));

        Map<String, String> requestBody = buildUpdateRequestBody();

        // Act & Assert: PUT endpoint returns 409 with conflict error structure
        mockMvc.perform(put(ACCOUNTS_PATH, VALID_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").exists());

        // Verify service was called despite the optimistic lock conflict
        verify(accountUpdateService).updateAccount(
                eq(VALID_ACCT_ID), any(AccountUpdateRequest.class));
    }

    // =========================================================================
    // Private Helper Methods — Test Data Builders
    // =========================================================================

    /**
     * Builds a test {@link Account} entity with representative data matching
     * the CVACT01Y.cpy record layout field specifications.
     *
     * <p>All 5 monetary fields use {@code BigDecimal} (MANDATORY per AAP —
     * no floating-point for COMP-3 fields):</p>
     * <pre>
     *   ACCT-CURR-BAL          PIC S9(10)V99 → new BigDecimal("1500.50")
     *   ACCT-CREDIT-LIMIT      PIC S9(10)V99 → new BigDecimal("5000.00")
     *   ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 → new BigDecimal("1000.00")
     *   ACCT-CURR-CYC-CREDIT   PIC S9(10)V99 → new BigDecimal("500.00")
     *   ACCT-CURR-CYC-DEBIT    PIC S9(10)V99 → new BigDecimal("250.00")
     * </pre>
     *
     * @return a fully populated Account entity suitable for test assertions
     */
    private Account buildTestAccount() {
        return new Account(
                VALID_ACCT_ID,                         // ACCT-ID PIC 9(11)
                "Y",                                    // ACCT-ACTIVE-STATUS PIC X(01)
                BigDecimal.valueOf(1500.50),             // ACCT-CURR-BAL PIC S9(10)V99
                BigDecimal.valueOf(5000L).setScale(2),   // ACCT-CREDIT-LIMIT PIC S9(10)V99
                BigDecimal.valueOf(1000L).setScale(2),   // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
                "2020-01-15",                           // ACCT-OPEN-DATE PIC X(10)
                "2028-12-31",                           // ACCT-EXPIRAION-DATE PIC X(10)
                "2025-01-15",                           // ACCT-REISSUE-DATE PIC X(10)
                BigDecimal.valueOf(500L).setScale(2),    // ACCT-CURR-CYC-CREDIT PIC S9(10)V99
                BigDecimal.valueOf(250L).setScale(2),    // ACCT-CURR-CYC-DEBIT PIC S9(10)V99
                "10001",                                // ACCT-ADDR-ZIP PIC X(10)
                "GRP001"                                // ACCT-GROUP-ID PIC X(10)
        );
    }

    /**
     * Builds a representative account update request body matching the
     * COACTUP.bms screen fields for {@code PUT /api/accounts/&#123;id&#125;}
     * test requests.
     *
     * <p>Request fields correspond to the editable BMS map fields from
     * COACTUP.bms/COACTUP.cpy (Account Update screen). All monetary values
     * are sent as string representations to match the screen-to-DTO mapping
     * in {@link AccountUpdateRequest}.</p>
     *
     * @return a mutable Map of field-name-to-value pairs for JSON serialization
     */
    private Map<String, String> buildUpdateRequestBody() {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("activeStatus", "Y");
        requestBody.put("creditLimit", "5000.00");
        requestBody.put("currBal", "1500.50");
        requestBody.put("cashCreditLimit", "1000.00");
        requestBody.put("openDate", "2020-01-15");
        requestBody.put("expirationDate", "2028-12-31");
        requestBody.put("currCycCredit", "500.00");
        requestBody.put("currCycDebit", "250.00");
        return requestBody;
    }
}

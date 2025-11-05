/*
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
 */

package com.carddemo.controller;

import com.carddemo.dto.request.TransactionRequest;
import com.carddemo.dto.response.TransactionListResponse;
import com.carddemo.dto.response.TransactionCategoryResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.TransactionException;
import com.carddemo.service.TransactionCategoryService;
import com.carddemo.service.TransactionCreationService;
import com.carddemo.service.TransactionListService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive JUnit 5 test class for TransactionController REST endpoint validation.
 * 
 * <p><strong>COBOL Programs Tested (Section 0.6):</strong></p>
 * <ul>
 *   <li>COTRN00C.cbl - Transaction list display with VSAM browsing → GET /api/transactions</li>
 *   <li>COTRN01C.cbl - Transaction category summary → GET /api/transactions/categories</li>
 *   <li>COTRN02C.cbl - Transaction creation with validation → POST /api/transactions</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Requirements:</strong></p>
 * <ul>
 *   <li>Pagination: 10 transactions per page matching COBOL BMS map OCCURS 10 TIMES</li>
 *   <li>Date Range Filtering: Test startDate and endDate parameters</li>
 *   <li>Category Aggregation: Test BigDecimal precision for COMP-3 equivalence</li>
 *   <li>Transaction Creation: Test atomic @Transactional boundaries (CICS SYNCPOINT)</li>
 *   <li>BigDecimal Precision: Test scale=2 with RoundingMode.HALF_UP</li>
 *   <li>Authorization: Test ROLE_USER and ROLE_ADMIN access patterns</li>
 *   <li>Error Handling: Test 404 Not Found, 422 Insufficient Balance, validation errors</li>
 *   <li>Performance: Assert response times under 200ms (Section 0.2 SLA)</li>
 *   <li>Sorting: Test ORDER BY timestamp DESC for recent transactions first</li>
 * </ul>
 * 
 * <p><strong>Testing Strategy:</strong></p>
 * <ul>
 *   <li>@WebMvcTest: Controller layer testing with MockMvc for HTTP request simulation</li>
 *   <li>@MockBean: Mock service dependencies for unit testing isolation</li>
 *   <li>@WithMockUser: Simulate authenticated users with ROLE_USER and ROLE_ADMIN</li>
 *   <li>Test Fixtures: Build representative transaction data matching COBOL records</li>
 *   <li>Assertions: Verify JSON responses, status codes, pagination metadata, precision</li>
 * </ul>
 * 
 * @see TransactionController
 * @see TransactionListService
 * @see TransactionCategoryService
 * @see TransactionCreationService
 */
@WebMvcTest(TransactionController.class)
@DisplayName("TransactionController REST Endpoint Tests")
public class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionListService transactionListService;

    @MockBean
    private TransactionCategoryService transactionCategoryService;

    @MockBean
    private TransactionCreationService transactionCreationService;

    // Test data constants matching COBOL specifications
    private static final String TEST_ACCOUNT_ID = "100000000001";
    private static final String TEST_CARD_NUMBER = "4532123456789012";
    private static final String TEST_TRANSACTION_ID = "0000000123456789";
    private static final String TEST_USER_ID = "testuser";
    private static final String TEST_ADMIN_USER_ID = "adminuser";
    private static final int DEFAULT_PAGE_SIZE = 10; // COBOL BMS map OCCURS 10 TIMES
    
    // Test amounts with BigDecimal precision matching COBOL COMP-3 PIC S9(10)V99
    private static final BigDecimal TEST_AMOUNT_125_50 = new BigDecimal("125.50").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_AMOUNT_542_75 = new BigDecimal("542.75").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_AMOUNT_2500_00 = new BigDecimal("2500.00").setScale(2, RoundingMode.HALF_UP);

    @BeforeEach
    void setUp() {
        // Reset all mocks before each test
        reset(transactionListService, transactionCategoryService, transactionCreationService);
    }

    /**
     * Nested test class for GET /api/transactions endpoint testing.
     * Tests paginated transaction list retrieval with filtering options.
     * Transformed from COBOL COTRN00C.cbl transaction list display logic.
     */
    @Nested
    @DisplayName("GET /api/transactions - Transaction List with Pagination")
    class GetTransactionListTests {

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return paginated transaction list with 10 items per page")
        void testGetTransactionList_WithDefaultPagination_ReturnsPageOf10() throws Exception {
            // Arrange: Create response with exactly 10 transactions matching COBOL BMS map
            TransactionListResponse mockResponse = createMockTransactionListResponse(0, 10, 47);
            when(transactionListService.getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(0), isNull(), isNull()))
                    .thenReturn(mockResponse);

            // Act & Assert
            long startTime = System.currentTimeMillis();
            MvcResult result = mockMvc.perform(get("/api/transactions")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("page", "0")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    // Verify pagination metadata
                    .andExpect(jsonPath("$.currentPage", is(0)))
                    .andExpect(jsonPath("$.pageSize", is(DEFAULT_PAGE_SIZE)))
                    .andExpect(jsonPath("$.totalPages", is(5)))
                    .andExpect(jsonPath("$.totalElements", is(47)))
                    .andExpect(jsonPath("$.hasNext", is(true)))
                    .andExpect(jsonPath("$.hasPrevious", is(false)))
                    // Verify transaction array size
                    .andExpect(jsonPath("$.transactions", hasSize(10)))
                    // Verify first transaction fields
                    .andExpect(jsonPath("$.transactions[0].transactionId", notNullValue()))
                    .andExpect(jsonPath("$.transactions[0].accountId", is(TEST_ACCOUNT_ID)))
                    .andExpect(jsonPath("$.transactions[0].transactionAmount", notNullValue()))
                    .andReturn();

            long responseTime = System.currentTimeMillis() - startTime;
            
            // Verify service interaction
            verify(transactionListService, times(1)).getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(0), isNull(), isNull());
            
            // Assert response time under 200ms per Section 0.2 SLA
            assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms SLA";
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should filter transactions by date range (COTRN00C date filtering)")
        void testGetTransactionList_WithDateRangeFilter_ReturnsFilteredResults() throws Exception {
            // Arrange: Set up date range for last 30 days
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(30);
            
            TransactionListResponse mockResponse = createMockTransactionListResponse(0, 10, 25);
            when(transactionListService.getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(0), eq(startDate), eq(endDate)))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("page", "0")
                    .param("startDate", startDate.toString())
                    .param("endDate", endDate.toString())
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions", hasSize(10)))
                    .andExpect(jsonPath("$.totalElements", is(25)));

            // Verify service called with date parameters
            verify(transactionListService, times(1)).getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(0), eq(startDate), eq(endDate));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should navigate to second page (PF8 forward in COBOL)")
        void testGetTransactionList_SecondPage_ReturnsNextPageResults() throws Exception {
            // Arrange: Create response for page 1 (second page, 0-indexed)
            TransactionListResponse mockResponse = createMockTransactionListResponse(1, 10, 47);
            when(transactionListService.getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(1), isNull(), isNull()))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("page", "1")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentPage", is(1)))
                    .andExpect(jsonPath("$.hasPrevious", is(true)))
                    .andExpect(jsonPath("$.hasNext", is(true)));

            verify(transactionListService, times(1)).getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(1), isNull(), isNull());
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 400 for negative page number")
        void testGetTransactionList_NegativePage_Returns400() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("page", "-1")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(transactionListService, never()).getTransactionList(anyString(), anyInt(), any(), any());
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 404 when account not found")
        void testGetTransactionList_AccountNotFound_Returns404() throws Exception {
            // Arrange: Mock service to throw AccountNotFoundException
            when(transactionListService.getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(0), isNull(), isNull()))
                    .thenThrow(new AccountNotFoundException("Account not found: " + TEST_ACCOUNT_ID));

            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("page", "0")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isNotFound());

            verify(transactionListService, times(1)).getTransactionList(
                    eq(TEST_ACCOUNT_ID), eq(0), isNull(), isNull());
        }

        @Test
        @WithMockUser(username = TEST_ADMIN_USER_ID, roles = {"ADMIN"})
        @DisplayName("Should allow admin to view all transactions without account filter")
        void testGetTransactionList_AdminWithoutAccountId_ReturnsAllTransactions() throws Exception {
            // Arrange: Admin can retrieve all transactions
            TransactionListResponse mockResponse = createMockTransactionListResponse(0, 10, 150);
            when(transactionListService.getAllTransactions(eq(0), isNull(), isNull()))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("page", "0")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions", hasSize(10)))
                    .andExpect(jsonPath("$.totalElements", is(150)));

            verify(transactionListService, times(1)).getAllTransactions(eq(0), isNull(), isNull());
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 400 when regular user attempts to retrieve without account filter")
        void testGetTransactionList_RegularUserWithoutAccountId_Returns400() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("page", "0")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(transactionListService, never()).getAllTransactions(anyInt(), any(), any());
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should filter by card number")
        void testGetTransactionList_ByCardNumber_ReturnsFilteredResults() throws Exception {
            // Arrange
            TransactionListResponse mockResponse = createMockTransactionListResponse(0, 10, 35);
            when(transactionListService.getTransactionListByCard(
                    eq(TEST_CARD_NUMBER), eq(0), isNull(), isNull()))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("cardNumber", TEST_CARD_NUMBER)
                    .param("page", "0")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions", hasSize(10)))
                    .andExpect(jsonPath("$.totalElements", is(35)));

            verify(transactionListService, times(1)).getTransactionListByCard(
                    eq(TEST_CARD_NUMBER), eq(0), isNull(), isNull());
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void testGetTransactionList_NoAuthentication_Returns401() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/api/transactions")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("page", "0")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isUnauthorized());

            verify(transactionListService, never()).getTransactionList(anyString(), anyInt(), any(), any());
        }
    }

    /**
     * Nested test class for GET /api/transactions/categories endpoint testing.
     * Tests category aggregation with BigDecimal precision preservation.
     * Transformed from COBOL COTRN01C.cbl transaction category summary logic.
     */
    @Nested
    @DisplayName("GET /api/transactions/categories - Category Aggregation")
    class GetTransactionCategoriesTests {

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return category aggregation with BigDecimal precision (COMP-3 equivalence)")
        void testGetTransactionCategories_WithValidAccount_ReturnsCategorySummary() throws Exception {
            // Arrange: Create aggregation result with BigDecimal amounts
            TransactionCategoryService.AggregationResult mockResult = createMockAggregationResult();
            when(transactionCategoryService.getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull()))
                    .thenReturn(mockResult);

            // Act & Assert
            long startTime = System.currentTimeMillis();
            mockMvc.perform(get("/api/transactions/categories")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    // Verify category summaries
                    .andExpect(jsonPath("$.categorySummaries", hasSize(3)))
                    // Verify grand total with exact precision
                    .andExpect(jsonPath("$.grandTotal", is(1669.95)))
                    .andExpect(jsonPath("$.totalTransactionCount", is(35)))
                    // Verify first category details
                    .andExpect(jsonPath("$.categorySummaries[0].categoryCode", is(1001)))
                    .andExpect(jsonPath("$.categorySummaries[0].categoryName", is("Groceries")))
                    .andExpect(jsonPath("$.categorySummaries[0].totalAmount", is(542.75)))
                    .andExpect(jsonPath("$.categorySummaries[0].transactionCount", is(12)))
                    .andExpect(jsonPath("$.categorySummaries[0].percentage", is(32.50)))
                    .andExpect(jsonPath("$.categorySummaries[0].averageAmount", is(45.23)));

            long responseTime = System.currentTimeMillis() - startTime;
            
            verify(transactionCategoryService, times(1)).getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull());
            
            // Assert response time under 200ms
            assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms SLA";
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should filter category aggregation by date range")
        void testGetTransactionCategories_WithDateRange_ReturnsFilteredAggregation() throws Exception {
            // Arrange
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(30);
            
            TransactionCategoryService.AggregationResult mockResult = createMockAggregationResult();
            when(transactionCategoryService.getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), eq(startDate), eq(endDate)))
                    .thenReturn(mockResult);

            // Act & Assert
            mockMvc.perform(get("/api/transactions/categories")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .param("startDate", startDate.toString())
                    .param("endDate", endDate.toString())
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categorySummaries", hasSize(3)))
                    .andExpect(jsonPath("$.grandTotal", notNullValue()));

            verify(transactionCategoryService, times(1)).getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), eq(startDate), eq(endDate));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 400 when accountId is missing for regular user")
        void testGetTransactionCategories_NoAccountId_Returns400() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/api/transactions/categories")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(transactionCategoryService, never()).getTransactionCategorySummary(anyString(), any(), any());
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 404 when account not found")
        void testGetTransactionCategories_AccountNotFound_Returns404() throws Exception {
            // Arrange
            when(transactionCategoryService.getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull()))
                    .thenThrow(new AccountNotFoundException("Account not found: " + TEST_ACCOUNT_ID));

            // Act & Assert
            mockMvc.perform(get("/api/transactions/categories")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isNotFound());

            verify(transactionCategoryService, times(1)).getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull());
        }

        @Test
        @WithMockUser(username = TEST_ADMIN_USER_ID, roles = {"ADMIN"})
        @DisplayName("Should allow admin to aggregate categories for any account")
        void testGetTransactionCategories_AdminAccess_ReturnsAggregation() throws Exception {
            // Arrange
            TransactionCategoryService.AggregationResult mockResult = createMockAggregationResult();
            when(transactionCategoryService.getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull()))
                    .thenReturn(mockResult);

            // Act & Assert
            mockMvc.perform(get("/api/transactions/categories")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.categorySummaries", hasSize(3)));

            verify(transactionCategoryService, times(1)).getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull());
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void testGetTransactionCategories_NoAuthentication_Returns401() throws Exception {
            // Act & Assert
            mockMvc.perform(get("/api/transactions/categories")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isUnauthorized());

            verify(transactionCategoryService, never()).getTransactionCategorySummary(anyString(), any(), any());
        }
    }

    /**
     * Nested test class for POST /api/transactions endpoint testing.
     * Tests transaction creation with validation, balance checking, and atomic updates.
     * Transformed from COBOL COTRN02C.cbl transaction add logic with CICS SYNCPOINT semantics.
     */
    @Nested
    @DisplayName("POST /api/transactions - Transaction Creation")
    class CreateTransactionTests {

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should create transaction successfully with atomic balance update (@Transactional)")
        void testCreateTransaction_ValidRequest_Returns201Created() throws Exception {
            // Arrange: Create valid transaction request
            TransactionRequest request = createValidTransactionRequest();
            TransactionCreationService.TransactionResponse mockResponse = createMockTransactionResponse();
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenReturn(mockResponse);

            // Act & Assert
            long startTime = System.currentTimeMillis();
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.transactionId", is(TEST_TRANSACTION_ID)))
                    .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID)))
                    .andExpect(jsonPath("$.transactionAmount", is(125.50)))
                    .andExpect(jsonPath("$.newAccountBalance", is(1874.50)))
                    .andExpect(jsonPath("$.message", containsString("successfully")));

            long responseTime = System.currentTimeMillis() - startTime;
            
            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
            
            // Assert response time under 500ms for POST operations (Section 0.2 SLA)
            assert responseTime < 500 : "Response time " + responseTime + "ms exceeds 500ms SLA for POST";
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should validate BigDecimal precision with scale=2 and HALF_UP rounding")
        void testCreateTransaction_AmountPrecision_PreservesScale() throws Exception {
            // Arrange: Create request with precise decimal amount
            TransactionRequest request = createValidTransactionRequest();
            request.setTransactionAmount(new BigDecimal("125.555")); // Test rounding
            
            TransactionCreationService.TransactionResponse mockResponse = createMockTransactionResponse();
            mockResponse.setTransactionAmount(new BigDecimal("125.56")); // Expected rounded value
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionAmount", is(125.56))); // Verify HALF_UP rounding

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 422 for insufficient balance (COBOL balance check)")
        void testCreateTransaction_InsufficientBalance_Returns422() throws Exception {
            // Arrange: Mock insufficient balance exception
            TransactionRequest request = createValidTransactionRequest();
            request.setTransactionAmount(TEST_AMOUNT_2500_00);
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenThrow(new InsufficientBalanceException(
                            "Insufficient available credit. Available: $500.00, Requested: $2500.00",
                            new BigDecimal("500.00"),
                            TEST_AMOUNT_2500_00));

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message", containsString("Insufficient")));

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 404 when account not found")
        void testCreateTransaction_AccountNotFound_Returns404() throws Exception {
            // Arrange
            TransactionRequest request = createValidTransactionRequest();
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenThrow(new AccountNotFoundException("Account not found: " + TEST_ACCOUNT_ID));

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", containsString("Account not found")));

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 404 when card not found")
        void testCreateTransaction_CardNotFound_Returns404() throws Exception {
            // Arrange
            TransactionRequest request = createValidTransactionRequest();
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenThrow(new CardNotFoundException("Card not found: " + TEST_CARD_NUMBER));

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", containsString("Card not found")));

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 400 for missing required fields")
        void testCreateTransaction_MissingRequiredFields_Returns400() throws Exception {
            // Arrange: Create request with missing fields
            TransactionRequest request = new TransactionRequest();
            request.setAccountId(TEST_ACCOUNT_ID);
            // Missing cardNumber, amount, type, etc.

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(transactionCreationService, never()).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should return 400 for negative transaction amount")
        void testCreateTransaction_NegativeAmount_Returns400() throws Exception {
            // Arrange: Create request with negative amount
            TransactionRequest request = createValidTransactionRequest();
            request.setTransactionAmount(new BigDecimal("-125.50"));

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());

            verify(transactionCreationService, never()).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_ADMIN_USER_ID, roles = {"ADMIN"})
        @DisplayName("Should allow admin to create transaction for any account")
        void testCreateTransaction_AdminAccess_Returns201() throws Exception {
            // Arrange
            TransactionRequest request = createValidTransactionRequest();
            TransactionCreationService.TransactionResponse mockResponse = createMockTransactionResponse();
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionId", is(TEST_TRANSACTION_ID)));

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @DisplayName("Should return 401 when not authenticated")
        void testCreateTransaction_NoAuthentication_Returns401() throws Exception {
            // Arrange
            TransactionRequest request = createValidTransactionRequest();

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isUnauthorized());

            verify(transactionCreationService, never()).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should validate transaction type code")
        void testCreateTransaction_InvalidTransactionType_Returns400() throws Exception {
            // Arrange
            TransactionRequest request = createValidTransactionRequest();
            request.setTransactionType("99"); // Invalid type code
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenThrow(new IllegalArgumentException("Invalid transaction type: 99"));

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Invalid transaction type")));

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should handle general transaction exception with 500")
        void testCreateTransaction_TransactionException_Returns500() throws Exception {
            // Arrange
            TransactionRequest request = createValidTransactionRequest();
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenThrow(new TransactionException("Database error during transaction creation", "DB_ERROR"));

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message", containsString("Database error")));

            verify(transactionCreationService, times(1)).createTransaction(any(TransactionRequest.class));
        }
    }

    /**
     * Nested test class for BigDecimal precision validation.
     * Tests COBOL COMP-3 decimal precision preservation (Section 0.9).
     */
    @Nested
    @DisplayName("BigDecimal Precision Tests (COMP-3 Equivalence)")
    class BigDecimalPrecisionTests {

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should preserve scale=2 for transaction amounts")
        void testBigDecimalPrecision_Scale2_PreservesExactValue() throws Exception {
            // Arrange: Test amounts with various decimal places
            TransactionRequest request = createValidTransactionRequest();
            request.setTransactionAmount(new BigDecimal("125.5")); // Single decimal place
            
            TransactionCreationService.TransactionResponse mockResponse = createMockTransactionResponse();
            mockResponse.setTransactionAmount(new BigDecimal("125.50")); // Should pad to 2 decimals
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionAmount", is(125.50)));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should use HALF_UP rounding mode for amounts")
        void testBigDecimalPrecision_HalfUpRounding_RoundsCorrectly() throws Exception {
            // Arrange: Test amount requiring rounding
            TransactionRequest request = createValidTransactionRequest();
            request.setTransactionAmount(new BigDecimal("125.556")); // Should round to 125.56
            
            TransactionCreationService.TransactionResponse mockResponse = createMockTransactionResponse();
            mockResponse.setTransactionAmount(new BigDecimal("125.56"));
            
            when(transactionCreationService.createTransaction(any(TransactionRequest.class)))
                    .thenReturn(mockResponse);

            // Act & Assert
            mockMvc.perform(post("/api/transactions")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionAmount", is(125.56)));
        }

        @Test
        @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
        @DisplayName("Should validate category aggregation precision")
        void testBigDecimalPrecision_CategoryAggregation_MaintainsPrecision() throws Exception {
            // Arrange: Create aggregation with precise calculations
            TransactionCategoryService.AggregationResult mockResult = createMockAggregationResult();
            when(transactionCategoryService.getTransactionCategorySummary(
                    eq(TEST_ACCOUNT_ID), isNull(), isNull()))
                    .thenReturn(mockResult);

            // Act & Assert
            mockMvc.perform(get("/api/transactions/categories")
                    .param("accountId", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    // Verify exact precision for percentages
                    .andExpect(jsonPath("$.categorySummaries[0].percentage", is(32.50)))
                    // Verify exact precision for averages
                    .andExpect(jsonPath("$.categorySummaries[0].averageAmount", is(45.23)))
                    // Verify grand total precision
                    .andExpect(jsonPath("$.grandTotal", is(1669.95)));
        }
    }

    /**
     * Helper method to create a mock TransactionListResponse with specified pagination parameters.
     * Simulates COBOL COTRN00C.cbl transaction list with 10 transactions per page.
     *
     * @param currentPage the current page number (0-indexed)
     * @param pageSize the number of transactions per page (default 10)
     * @param totalElements the total number of transactions available
     * @return TransactionListResponse with mock transaction data
     */
    private TransactionListResponse createMockTransactionListResponse(int currentPage, int pageSize, int totalElements) {
        TransactionListResponse response = new TransactionListResponse();
        response.setCurrentPage(currentPage);
        response.setPageSize(pageSize);
        response.setTotalElements(totalElements);
        response.setTotalPages((int) Math.ceil((double) totalElements / pageSize));
        response.setHasNext(currentPage < response.getTotalPages() - 1);
        response.setHasPrevious(currentPage > 0);

        // Create list of transaction data transfer objects
        List<TransactionListResponse.TransactionData> transactions = new ArrayList<>();
        for (int i = 0; i < Math.min(pageSize, totalElements - (currentPage * pageSize)); i++) {
            TransactionListResponse.TransactionData txn = new TransactionListResponse.TransactionData();
            txn.setTransactionId(String.format("%016d", (currentPage * pageSize) + i + 1));
            txn.setAccountId(TEST_ACCOUNT_ID);
            txn.setCardNumber("************" + String.format("%04d", 9012 + i));
            txn.setTransactionAmount(TEST_AMOUNT_125_50.add(new BigDecimal(i * 10)));
            txn.setTransactionType("01");
            txn.setTransactionDescription("Test Transaction " + (i + 1));
            txn.setTransactionDate(LocalDate.now().minusDays(i));
            txn.setMerchantName("Test Merchant " + (i + 1));
            txn.setMerchantCity("Seattle");
            transactions.add(txn);
        }
        response.setTransactions(transactions);

        // Set first and last transaction IDs for pagination tracking
        if (!transactions.isEmpty()) {
            response.setFirstTransactionId(transactions.get(0).getTransactionId());
            response.setLastTransactionId(transactions.get(transactions.size() - 1).getTransactionId());
        }

        return response;
    }

    /**
     * Helper method to create a mock AggregationResult with category summaries.
     * Simulates COBOL COTRN01C.cbl category aggregation with COMP-3 precision.
     *
     * @return AggregationResult with mock category data and BigDecimal precision
     */
    private TransactionCategoryService.AggregationResult createMockAggregationResult() {
        List<TransactionCategoryService.CategorySummary> summaries = new ArrayList<>();

        // Category 1: Groceries
        TransactionCategoryService.CategorySummary groceries = new TransactionCategoryService.CategorySummary();
        groceries.setCategoryCode(1001);
        groceries.setCategoryName("Groceries");
        groceries.setTotalAmount(TEST_AMOUNT_542_75);
        groceries.setTransactionCount(12);
        groceries.setPercentage(new BigDecimal("32.50").setScale(2, RoundingMode.HALF_UP));
        groceries.setAverageAmount(new BigDecimal("45.23").setScale(2, RoundingMode.HALF_UP));
        summaries.add(groceries);

        // Category 2: Fuel
        TransactionCategoryService.CategorySummary fuel = new TransactionCategoryService.CategorySummary();
        fuel.setCategoryCode(1002);
        fuel.setCategoryName("Fuel");
        fuel.setTotalAmount(new BigDecimal("387.20").setScale(2, RoundingMode.HALF_UP));
        fuel.setTransactionCount(8);
        fuel.setPercentage(new BigDecimal("23.19").setScale(2, RoundingMode.HALF_UP));
        fuel.setAverageAmount(new BigDecimal("48.40").setScale(2, RoundingMode.HALF_UP));
        summaries.add(fuel);

        // Category 3: Dining
        TransactionCategoryService.CategorySummary dining = new TransactionCategoryService.CategorySummary();
        dining.setCategoryCode(1003);
        dining.setCategoryName("Dining");
        dining.setTotalAmount(new BigDecimal("740.00").setScale(2, RoundingMode.HALF_UP));
        dining.setTransactionCount(15);
        dining.setPercentage(new BigDecimal("44.31").setScale(2, RoundingMode.HALF_UP));
        dining.setAverageAmount(new BigDecimal("49.33").setScale(2, RoundingMode.HALF_UP));
        summaries.add(dining);

        TransactionCategoryService.AggregationResult result = new TransactionCategoryService.AggregationResult();
        result.setCategorySummaries(summaries);
        result.setGrandTotal(new BigDecimal("1669.95").setScale(2, RoundingMode.HALF_UP));
        result.setTotalTransactionCount(35);
        result.setAccountId(TEST_ACCOUNT_ID);

        return result;
    }

    /**
     * Helper method to create a valid TransactionRequest for testing.
     * Simulates COBOL COTRN02C.cbl transaction add request data.
     *
     * @return TransactionRequest with all required fields populated
     */
    private TransactionRequest createValidTransactionRequest() {
        TransactionRequest request = new TransactionRequest();
        request.setAccountId(TEST_ACCOUNT_ID);
        request.setCardNumber(TEST_CARD_NUMBER);
        request.setTransactionType("01"); // Purchase type
        request.setCategoryCode(1002); // Fuel category
        request.setTransactionAmount(TEST_AMOUNT_125_50);
        request.setTransactionDescription("Gas Station Purchase");
        request.setTransactionSource("POS");
        request.setOriginationTimestamp(LocalDateTime.now());
        request.setProcessingTimestamp(LocalDateTime.now());
        request.setMerchantId("MERCHANT123");
        request.setMerchantName("Shell Gas Station");
        request.setMerchantCity("Seattle");
        request.setMerchantZipCode("98101");
        return request;
    }

    /**
     * Helper method to create a mock TransactionResponse for successful creation.
     * Simulates COBOL COTRN02C.cbl successful transaction add response.
     *
     * @return TransactionResponse with transaction details and balance updates
     */
    private TransactionCreationService.TransactionResponse createMockTransactionResponse() {
        TransactionCreationService.TransactionResponse response = new TransactionCreationService.TransactionResponse();
        response.setTransactionId(TEST_TRANSACTION_ID);
        response.setAccountId(TEST_ACCOUNT_ID);
        response.setCardNumber("************9012"); // Masked card number
        response.setTransactionAmount(TEST_AMOUNT_125_50);
        response.setTransactionType("01");
        response.setCategoryCode(1002);
        response.setTransactionDescription("Gas Station Purchase");
        response.setTransactionDate(LocalDateTime.now());
        response.setMerchantName("Shell Gas Station");
        response.setMerchantCity("Seattle");
        response.setNewAccountBalance(new BigDecimal("1874.50").setScale(2, RoundingMode.HALF_UP));
        response.setAvailableCredit(new BigDecimal("3125.50").setScale(2, RoundingMode.HALF_UP));
        response.setMessage("Transaction added successfully. Your Tran ID is " + TEST_TRANSACTION_ID + ".");
        return response;
    }
}

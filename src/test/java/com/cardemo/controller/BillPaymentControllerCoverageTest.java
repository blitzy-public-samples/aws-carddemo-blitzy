/*
 * BillPaymentControllerCoverageTest.java — Coverage tests for BillPaymentController
 * Tests POST /api/billing/pay endpoint
 */
package com.cardemo.controller;

import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.config.SecurityConfig;
import com.cardemo.service.online.BillPaymentService;
import com.cardemo.service.online.BillPaymentService.BillPaymentRequest;
import com.cardemo.service.online.BillPaymentService.BillPaymentResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage tests for BillPaymentController — exercises POST /api/billing/pay
 * including success, RecordNotFoundException (404), ValidationException (400),
 * and unexpected exception (500) paths. Also exercises the maskAccountId()
 * private helper through logging side effects.
 */
@WebMvcTest(BillPaymentController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class BillPaymentControllerCoverageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillPaymentService billPaymentService;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- Success path ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay returns 200 with successful result")
    void processBillPaymentSuccess() throws Exception {
        BillPaymentResult result = new BillPaymentResult();
        result.setSuccess(true);
        result.setMessage("Payment processed successfully");
        result.setAccountId("00000000010");
        result.setCurrentBalance(BigDecimal.ZERO);
        result.setCreditLimit(new BigDecimal("5000.00"));
        result.setActiveStatus("Y");
        result.setExpirationDate("12/31/2027");
        result.setCardNumber("4111111111111111");
        result.setTransactionId("0000000000000001");
        result.setPaymentAmount(new BigDecimal("150.00"));
        result.setProgramName("COBIL00C");
        result.setTitle("BILL PAYMENT");
        result.setCurrentDate("03/19/2026");

        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenReturn(result);

        String body = "{\"accountId\":\"00000000010\",\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.accountId").value("00000000010"))
                .andExpect(jsonPath("$.message").value("Payment processed successfully"));
    }

    // ---- RecordNotFoundException → 404 ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay returns 404 when account not found")
    void processBillPaymentRecordNotFound() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenThrow(new RecordNotFoundException("Account ID NOT found"));

        String body = "{\"accountId\":\"99999999999\",\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ---- ValidationException → 400 ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay returns 400 on validation error")
    void processBillPaymentValidationError() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenThrow(new ValidationException("Acct ID can NOT be empty"));

        String body = "{\"accountId\":\"\",\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Acct ID can NOT be empty"));
    }

    // ---- Unexpected exception → 500 ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay returns 500 on unexpected exception")
    void processBillPaymentUnexpectedException() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenThrow(new RuntimeException("Database connection lost"));

        String body = "{\"accountId\":\"00000000010\",\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    // ---- Exercises maskAccountId with short account ID ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay handles short account ID")
    void processBillPaymentShortAccountId() throws Exception {
        BillPaymentResult result = new BillPaymentResult();
        result.setSuccess(true);
        result.setMessage("Payment OK");

        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenReturn(result);

        // Short account ID exercises maskAccountId edge case
        String body = "{\"accountId\":\"AB\",\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ---- Exercises all request fields ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay exercises all BillPaymentRequest fields")
    void processBillPaymentAllRequestFields() throws Exception {
        BillPaymentResult result = new BillPaymentResult();
        result.setSuccess(true);

        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenReturn(result);

        // Exercise all fields: accountId, confirm, action
        String body = "{\"accountId\":\"00000000010\",\"confirm\":\"Y\",\"action\":\"PAY\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ---- Exercises BillPaymentResult fields in response ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay returns all BillPaymentResult fields")
    void processBillPaymentAllResultFields() throws Exception {
        BillPaymentResult result = new BillPaymentResult();
        result.setSuccess(true);
        result.setMessage("Payment processed");
        result.setAccountId("00000000010");
        result.setCurrentBalance(new BigDecimal("0.00"));
        result.setCreditLimit(new BigDecimal("10000.00"));
        result.setActiveStatus("Y");
        result.setExpirationDate("12/31/2028");
        result.setCardNumber("4222222222222222");
        result.setTransactionId("0000000000000099");
        result.setPaymentAmount(new BigDecimal("500.00"));
        result.setProgramName("COBIL00C");
        result.setTitle("BILL PAYMENT");
        result.setCurrentDate("03/19/2026");

        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenReturn(result);

        String body = "{\"accountId\":\"00000000010\",\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.accountId").exists())
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.creditLimit").exists())
                .andExpect(jsonPath("$.activeStatus").exists())
                .andExpect(jsonPath("$.expirationDate").exists())
                .andExpect(jsonPath("$.cardNumber").exists())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.paymentAmount").exists())
                .andExpect(jsonPath("$.programName").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.currentDate").exists());
    }

    // ---- Null account ID triggers maskAccountId null handling ----

    @Test
    @WithMockUser
    @DisplayName("POST /api/billing/pay with null accountId still processes")
    void processBillPaymentNullAccountId() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequest.class)))
                .thenThrow(new ValidationException("Acct ID can NOT be empty"));

        String body = "{\"confirm\":\"Y\"}";
        mockMvc.perform(post("/api/billing/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}

package com.cardemo.controller;

import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GlobalExceptionHandler — all 9 @ExceptionHandler methods.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("handleRecordNotFound returns 404")
    void handleRecordNotFound() {
        var ex = new RecordNotFoundException("Account not found");
        var response = handler.handleRecordNotFound(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
    }

    @Test
    @DisplayName("handleDuplicateRecord returns 409")
    void handleDuplicateRecord() {
        var ex = new DuplicateRecordException("Duplicate key");
        var response = handler.handleDuplicateRecord(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("status", 409);
    }

    @Test
    @DisplayName("handleValidation returns 400")
    void handleValidation() {
        var ex = new ValidationException("Field too long");
        var response = handler.handleValidation(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400);
    }

    @Test
    @DisplayName("handleMethodArgumentNotValid — single field error returns 400")
    void handleMethodArgumentNotValidSingle() {
        var target = new Object();
        var bindingResult = new BeanPropertyBindingResult(target, "request");
        bindingResult.addError(new FieldError("request", "userId", "User ID Cannot Be Empty"));
        var ex = new MethodArgumentNotValidException(null, bindingResult);

        var response = handler.handleMethodArgumentNotValid(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "User ID Cannot Be Empty");
    }

    @Test
    @DisplayName("handleMethodArgumentNotValid — multiple field errors returns 400")
    void handleMethodArgumentNotValidMultiple() {
        var target = new Object();
        var bindingResult = new BeanPropertyBindingResult(target, "request");
        bindingResult.addError(new FieldError("request", "userId", "required"));
        bindingResult.addError(new FieldError("request", "password", "too short"));
        var ex = new MethodArgumentNotValidException(null, bindingResult);

        var response = handler.handleMethodArgumentNotValid(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handleAuthentication returns 401")
    void handleAuthentication() {
        var ex = new AuthenticationException("Invalid credentials");
        var response = handler.handleAuthentication(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("status", 401);
    }

    @Test
    @DisplayName("handleOptimisticLock returns 409")
    void handleOptimisticLock() {
        var ex = new OptimisticLockException("Version mismatch");
        var response = handler.handleOptimisticLock(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("status", 409);
    }

    @Test
    @DisplayName("handleHttpMessageNotReadable — generic returns 400")
    void handleHttpMessageNotReadable() {
        var ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("Could not read JSON");
        when(ex.getCause()).thenReturn(null);

        var response = handler.handleHttpMessageNotReadable(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handleHttpMessageNotReadable — UnrecognizedPropertyException")
    void handleUnrecognizedProperty() {
        var jackson = mock(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        when(jackson.getPropertyName()).thenReturn("badField");
        when(jackson.getKnownPropertyIds()).thenReturn(java.util.List.of("goodField"));

        var ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("Unrecognized");
        when(ex.getCause()).thenReturn(jackson);

        var response = handler.handleHttpMessageNotReadable(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handleHttpMessageNotReadable — JsonParseException")
    void handleJsonParseException() {
        var jackson = mock(com.fasterxml.jackson.core.JsonParseException.class);

        var ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("Parse error");
        when(ex.getCause()).thenReturn(jackson);

        var response = handler.handleHttpMessageNotReadable(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handleHttpMessageNotReadable — MismatchedInputException")
    void handleMismatchedInput() {
        var jackson = mock(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);

        var ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("Mismatched input");
        when(ex.getCause()).thenReturn(jackson);

        var response = handler.handleHttpMessageNotReadable(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handleCardDemoException returns 500")
    void handleCardDemoException() {
        var ex = new CardDemoException("Internal error");
        var response = handler.handleCardDemoException(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("status", 500);
    }

    @Test
    @DisplayName("handleGeneral returns 500")
    void handleGeneral() {
        var ex = new RuntimeException("Unexpected");
        var response = handler.handleGeneral(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsKey("error");
    }
}

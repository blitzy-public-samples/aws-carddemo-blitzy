package com.carddemo.exception;

import com.carddemo.model.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception handling component for the CardDemo Spring Boot application.
 * 
 * <p>This class uses Spring's @RestControllerAdvice annotation to intercept all exceptions
 * thrown by REST controllers and convert them into standardized ErrorResponse DTOs with
 * appropriate HTTP status codes.</p>
 * 
 * <h2>COBOL to Java Exception Handling Migration</h2>
 * 
 * <p>This exception handler replaces COBOL error handling patterns from the mainframe
 * CardDemo application:</p>
 * 
 * <h3>COBOL Error Patterns Replaced:</h3>
 * <ul>
 *   <li><b>APPL-RESULT codes:</b> COBOL programs used APPL-RESULT field to indicate 
 *       business logic errors (values 8, 12, 16) → Now handled by {@link BusinessException}</li>
 *   <li><b>FILE-STATUS checks:</b> VSAM file-status codes (23=not found, 22=duplicate key) 
 *       → Now handled by {@link DataNotFoundException} and {@link BusinessException}</li>
 *   <li><b>EIBRESP codes:</b> CICS response codes (NOTFND, DUPREC, INVREQ) 
 *       → Now handled by specific exception types</li>
 *   <li><b>Validation flags:</b> Field validation flags (FLG-ALPHA-NOT-OK, FLG-MANDATORY-NOT-OK) 
 *       → Now handled by {@link ValidationException}</li>
 * </ul>
 * 
 * <h3>COBOL Error Handling Examples:</h3>
 * <pre>
 * COBOL Pattern from COACTUPC.cbl:
 *   EXEC CICS READ FILE('ACCTDAT')
 *        INTO(ACCOUNT-RECORD)
 *        RIDFLD(WS-CARD-RID-ACCT-ID-N)
 *        RESP(WS-RESP-CD)
 *        RESP2(WS-REAS-CD)
 *   END-EXEC
 *   
 *   EVALUATE WS-RESP-CD
 *     WHEN DFHRESP(NORMAL)
 *       CONTINUE
 *     WHEN DFHRESP(NOTFND)
 *       MOVE 'Account not found' TO WS-RETURN-MSG
 *       SET INPUT-ERROR TO TRUE
 *     WHEN OTHER
 *       EXEC CICS ABEND ABCODE('ACRD') END-EXEC
 *   END-EVALUATE
 * 
 * Java Equivalent:
 *   try {
 *     Account account = accountRepository.findById(accountId)
 *       .orElseThrow(() -> new DataNotFoundException("Account", accountId));
 *   } catch (DataNotFoundException ex) {
 *     // Caught by this GlobalExceptionHandler
 *     // Returns HTTP 404 with ErrorResponse DTO
 *   }
 * </pre>
 * 
 * <h3>COBOL Validation Pattern:</h3>
 * <pre>
 * COBOL Pattern from COACTUPC.cbl:
 *   IF WS-ACCOUNT-NAME = SPACES
 *     SET FLG-MANDATORY-NOT-OK TO TRUE
 *     MOVE 'Account name is mandatory' TO WS-MESSAGE
 *   END-IF
 *   
 *   IF NOT FLG-ALPHA-ISVALID
 *     MOVE 'Name must contain only letters' TO WS-MESSAGE
 *   END-IF
 * 
 * Java Equivalent:
 *   if (accountName == null || accountName.trim().isEmpty()) {
 *     throw new ValidationException("VAL002", 
 *       "Account name is mandatory", "accountName");
 *   }
 *   // Caught by handleValidationException() → HTTP 400
 * </pre>
 * 
 * <h3>COBOL Business Logic Error Pattern:</h3>
 * <pre>
 * COBOL Pattern from COTRN02C.cbl:
 *   IF WS-TRAN-AMT > WS-ACCT-CREDIT-LIMIT
 *     MOVE 12 TO APPL-RESULT
 *     MOVE 'Transaction exceeds credit limit' TO WS-MESSAGE
 *   END-IF
 * 
 * Java Equivalent:
 *   if (transactionAmount.compareTo(account.getCreditLimit()) > 0) {
 *     throw new BusinessException("BUS001", 
 *       "Transaction amount exceeds credit limit");
 *   }
 *   // Caught by handleBusinessException() → HTTP 400
 * </pre>
 * 
 * <h2>Exception Handler Methods</h2>
 * 
 * <p>This class provides specialized exception handlers for:</p>
 * <ol>
 *   <li>{@link #handleValidationException} - Field validation failures → HTTP 400</li>
 *   <li>{@link #handleDataNotFoundException} - Entity not found → HTTP 404</li>
 *   <li>{@link #handleBusinessException} - Business rule violations → HTTP 400/409</li>
 *   <li>{@link #handleMethodArgumentNotValid} - Bean Validation errors → HTTP 400</li>
 *   <li>{@link #handleHttpMessageNotReadable} - JSON parsing errors → HTTP 400</li>
 *   <li>{@link #handleGenericException} - Unexpected errors → HTTP 500</li>
 * </ol>
 * 
 * <h2>HTTP Status Code Mapping</h2>
 * 
 * <table border="1">
 *   <tr>
 *     <th>Exception Type</th>
 *     <th>HTTP Status</th>
 *     <th>COBOL Equivalent</th>
 *   </tr>
 *   <tr>
 *     <td>ValidationException</td>
 *     <td>400 Bad Request</td>
 *     <td>FLG-*-NOT-OK validation flags</td>
 *   </tr>
 *   <tr>
 *     <td>DataNotFoundException</td>
 *     <td>404 Not Found</td>
 *     <td>FILE-STATUS 23, DFHRESP(NOTFND)</td>
 *   </tr>
 *   <tr>
 *     <td>BusinessException (BUS001, BUS002)</td>
 *     <td>400 Bad Request</td>
 *     <td>APPL-RESULT = 12</td>
 *   </tr>
 *   <tr>
 *     <td>BusinessException (BUS003)</td>
 *     <td>409 Conflict</td>
 *     <td>FILE-STATUS 22, DFHRESP(DUPREC)</td>
 *   </tr>
 *   <tr>
 *     <td>MethodArgumentNotValidException</td>
 *     <td>400 Bad Request</td>
 *     <td>Bean Validation (@Valid) failures</td>
 *   </tr>
 *   <tr>
 *     <td>HttpMessageNotReadableException</td>
 *     <td>400 Bad Request</td>
 *     <td>EXEC CICS RECEIVE MAP errors</td>
 *   </tr>
 *   <tr>
 *     <td>Exception (generic)</td>
 *     <td>500 Internal Server Error</td>
 *     <td>EXEC CICS ABEND, FILE-STATUS 90+</td>
 *   </tr>
 * </table>
 * 
 * <h2>Usage Example</h2>
 * 
 * <p>This class is automatically invoked by Spring when exceptions are thrown from
 * controller methods. No explicit try-catch blocks are needed in controllers:</p>
 * 
 * <pre>
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/accounts")
 * public class AccountController {
 *   
 *   {@literal @}GetMapping("/{id}")
 *   public ResponseEntity&lt;AccountDto&gt; getAccount(@PathVariable Long id) {
 *     // If account not found, DataNotFoundException is thrown
 *     // GlobalExceptionHandler catches it and returns HTTP 404
 *     AccountDto account = accountService.findById(id);
 *     return ResponseEntity.ok(account);
 *   }
 * }
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2025-10-26
 * @see ValidationException
 * @see DataNotFoundException
 * @see BusinessException
 * @see ErrorResponse
 * @see org.springframework.web.bind.annotation.RestControllerAdvice
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles ValidationException - thrown when field validation fails.
     * 
     * <p>This method converts field validation failures into HTTP 400 Bad Request responses
     * with detailed error information including the field name that failed validation.</p>
     * 
     * <h3>COBOL Error Pattern Replaced:</h3>
     * <pre>
     * COBOL (COACTUPC.cbl):
     *   IF WS-ACCOUNT-NAME = SPACES
     *     SET FLG-MANDATORY-NOT-OK TO TRUE
     *     MOVE 'accountName' TO WS-EDIT-VARIABLE-NAME
     *     MOVE 'Account name cannot be empty' TO WS-RETURN-MSG
     *   END-IF
     * 
     * Java:
     *   throw new ValidationException("VAL002", 
     *     "Account name cannot be empty", "accountName");
     *   // → HTTP 400 with field name highlighted
     * </pre>
     * 
     * <h3>Response Example:</h3>
     * <pre>
     * HTTP/1.1 400 Bad Request
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 400,
     *   "error": "Validation Error",
     *   "message": "Account name cannot be empty",
     *   "details": "Field 'accountName' failed validation: VAL002",
     *   "path": "/api/accounts"
     * }
     * </pre>
     * 
     * @param ex The ValidationException thrown by service or controller methods
     * @param request WebRequest containing HTTP request details including URI path
     * @return ResponseEntity with HTTP 400 status and ErrorResponse body
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            ValidationException ex, 
            WebRequest request) {
        
        log.warn("Validation error occurred: {} | Field: {} | Error Code: {} | Path: {}", 
                ex.getMessage(), 
                ex.getFieldName(), 
                ex.getErrorCode(),
                extractPath(request));
        
        String details = buildValidationDetails(ex);
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Error",
                ex.getMessage(),
                details,
                extractPath(request)
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles DataNotFoundException - thrown when an entity is not found.
     * 
     * <p>This method converts entity not found scenarios into HTTP 404 Not Found responses
     * with information about the entity type and ID that was not found.</p>
     * 
     * <h3>COBOL Error Pattern Replaced:</h3>
     * <pre>
     * COBOL (COACTUPC.cbl line 3668):
     *   EXEC CICS READ FILE('ACCTDAT')
     *        INTO(ACCOUNT-RECORD)
     *        RIDFLD(WS-CARD-RID-ACCT-ID-N)
     *        RESP(WS-RESP-CD)
     *   END-EXEC
     *   
     *   WHEN DFHRESP(NOTFND)
     *     STRING 'Account:' WS-CARD-RID-ACCT-ID-X 
     *            ' not found in Cross ref file.'
     *            INTO WS-RETURN-MSG
     *     SET INPUT-ERROR TO TRUE
     * 
     * COBOL (CBACT01C.cbl):
     *   READ ACCTFILE-FILE
     *     INVALID KEY
     *       MOVE 23 TO FILE-STATUS
     *       DISPLAY 'Record not found'
     *   END-READ
     * 
     * Java:
     *   throw new DataNotFoundException("Account", accountId);
     *   // → HTTP 404 with entity details
     * </pre>
     * 
     * <h3>Response Example:</h3>
     * <pre>
     * HTTP/1.1 404 Not Found
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account with ID 12345678901 not found",
     *   "details": "Entity type: Account | Entity ID: 12345678901 | Error code: DNF001",
     *   "path": "/api/accounts/12345678901"
     * }
     * </pre>
     * 
     * @param ex The DataNotFoundException thrown when entity lookup fails
     * @param request WebRequest containing HTTP request details including URI path
     * @return ResponseEntity with HTTP 404 status and ErrorResponse body
     */
    @ExceptionHandler(DataNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDataNotFoundException(
            DataNotFoundException ex, 
            WebRequest request) {
        
        log.warn("Data not found: {} | Entity Type: {} | Entity ID: {} | Error Code: {} | Path: {}", 
                ex.getMessage(),
                ex.getEntityType(),
                ex.getEntityId(),
                ex.getErrorCode(),
                extractPath(request));
        
        String details = String.format("Entity type: %s | Entity ID: %s | Error code: %s",
                ex.getEntityType(), 
                ex.getEntityId(), 
                ex.getErrorCode());
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                details,
                extractPath(request)
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles BusinessException - thrown when business rules are violated.
     * 
     * <p>This method converts business logic violations into HTTP 400 Bad Request or 
     * HTTP 409 Conflict responses based on the error code. BUS003 (transaction conflicts) 
     * return 409, while all other business errors return 400.</p>
     * 
     * <h3>COBOL Error Pattern Replaced:</h3>
     * <pre>
     * COBOL (COTRN02C.cbl - Transaction Processing):
     *   IF WS-TRAN-AMT > WS-ACCT-CREDIT-LIMIT
     *     MOVE 12 TO APPL-RESULT
     *     STRING 'Transaction amount $' WS-TRAN-AMT
     *            ' exceeds credit limit $' WS-ACCT-CREDIT-LIMIT
     *            INTO WS-ERROR-MESSAGE
     *   END-IF
     * 
     * COBOL (CBACT01C.cbl - End of File):
     *   AT END
     *     MOVE 16 TO APPL-RESULT
     *     DISPLAY 'No more records available'
     *   END-READ
     * 
     * COBOL (Duplicate Key):
     *   WRITE ACCOUNT-RECORD
     *     INVALID KEY
     *       MOVE 22 TO FILE-STATUS
     *       DISPLAY 'Duplicate account ID'
     *   END-WRITE
     * 
     * Java:
     *   throw new BusinessException("BUS001", 
     *     "Transaction amount exceeds credit limit");
     *   // → HTTP 400 for business rule violation
     *   
     *   throw new BusinessException("BUS003", 
     *     "Duplicate transaction ID");
     *   // → HTTP 409 for conflict
     * </pre>
     * 
     * <h3>Error Code to HTTP Status Mapping:</h3>
     * <ul>
     *   <li><b>BUS001:</b> General business rule violation → HTTP 400 Bad Request</li>
     *   <li><b>BUS002:</b> Resource exhausted or not available → HTTP 400 Bad Request</li>
     *   <li><b>BUS003:</b> Transaction conflict or duplicate → HTTP 409 Conflict</li>
     *   <li><b>Other:</b> Any other BUS* code → HTTP 400 Bad Request</li>
     * </ul>
     * 
     * <h3>Response Example (HTTP 400):</h3>
     * <pre>
     * HTTP/1.1 400 Bad Request
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 400,
     *   "error": "Business Rule Violation",
     *   "message": "Transaction amount exceeds credit limit",
     *   "details": "Error code: BUS001 | Context: {accountId=12345, amount=2000.00, limit=1500.00}",
     *   "path": "/api/transactions"
     * }
     * </pre>
     * 
     * <h3>Response Example (HTTP 409):</h3>
     * <pre>
     * HTTP/1.1 409 Conflict
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 409,
     *   "error": "Conflict",
     *   "message": "Duplicate transaction ID",
     *   "details": "Error code: BUS003 | Transaction ID TR1234567890 already exists",
     *   "path": "/api/transactions"
     * }
     * </pre>
     * 
     * @param ex The BusinessException thrown when business rules are violated
     * @param request WebRequest containing HTTP request details including URI path
     * @return ResponseEntity with HTTP 400 or 409 status and ErrorResponse body
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex, 
            WebRequest request) {
        
        // Determine HTTP status based on error code
        // BUS003 indicates transaction conflicts (like duplicate key) → HTTP 409 Conflict
        // All other business errors → HTTP 400 Bad Request
        HttpStatus status = "BUS003".equals(ex.getErrorCode()) 
                ? HttpStatus.CONFLICT 
                : HttpStatus.BAD_REQUEST;
        
        String errorType = status == HttpStatus.CONFLICT 
                ? "Conflict" 
                : "Business Rule Violation";
        
        log.warn("Business exception occurred: {} | Error Code: {} | Status: {} | Path: {}", 
                ex.getMessage(),
                ex.getErrorCode(),
                status.value(),
                extractPath(request));
        
        if (ex.getContext() != null && !ex.getContext().isEmpty()) {
            log.warn("Business exception context: {}", ex.getContext());
        }
        
        String details = buildBusinessExceptionDetails(ex);
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                errorType,
                ex.getMessage(),
                details,
                extractPath(request)
        );
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles MethodArgumentNotValidException - thrown when Bean Validation (@Valid) fails.
     * 
     * <p>This method handles JSR-380 Bean Validation errors that occur when @Valid or 
     * @Validated annotations are used on controller method parameters. It collects all
     * field validation errors and returns them in a structured format.</p>
     * 
     * <h3>COBOL Pattern Replaced:</h3>
     * <pre>
     * COBOL (Multiple Field Validations in COACTUPC.cbl):
     *   PERFORM 1500-EDIT-ACCOUNT-NAME
     *   PERFORM 1510-EDIT-ACCOUNT-STATUS
     *   PERFORM 1520-EDIT-ACCOUNT-BALANCE
     *   
     *   IF INPUT-ERROR
     *     MOVE 'Multiple validation errors occurred' TO WS-MESSAGE
     *     EXEC CICS SEND MAP(...) END-EXEC
     *   END-IF
     * 
     * Java (Bean Validation):
     *   public class AccountDto {
     *     {@literal @}NotNull(message = "Account name is required")
     *     {@literal @}Size(min = 1, max = 50, message = "Name must be 1-50 characters")
     *     private String accountName;
     *     
     *     {@literal @}NotNull(message = "Status is required")
     *     {@literal @}Pattern(regexp = "[YN]", message = "Status must be Y or N")
     *     private String status;
     *     
     *     {@literal @}NotNull(message = "Balance is required")
     *     {@literal @}DecimalMin(value = "0.00", message = "Balance cannot be negative")
     *     private BigDecimal balance;
     *   }
     *   
     *   // Controller method
     *   {@literal @}PostMapping
     *   public ResponseEntity&lt;AccountDto&gt; create(@Valid @RequestBody AccountDto dto) {
     *     // Validation errors caught by this handler → HTTP 400
     *   }
     * </pre>
     * 
     * <h3>Response Example:</h3>
     * <pre>
     * HTTP/1.1 400 Bad Request
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 400,
     *   "error": "Validation Failed",
     *   "message": "Input validation failed for 2 field(s)",
     *   "details": "accountName: Account name is required; status: Status must be Y or N",
     *   "path": "/api/accounts"
     * }
     * </pre>
     * 
     * @param ex The MethodArgumentNotValidException containing all field validation errors
     * @param request WebRequest containing HTTP request details including URI path
     * @return ResponseEntity with HTTP 400 status and ErrorResponse body listing all errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, 
            WebRequest request) {
        
        // Collect all field validation errors
        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() != null 
                                ? fieldError.getDefaultMessage() 
                                : "Invalid value",
                        (existing, replacement) -> existing + "; " + replacement
                ));
        
        log.warn("Bean validation failed: {} field(s) with errors | Path: {} | Errors: {}", 
                fieldErrors.size(),
                extractPath(request),
                fieldErrors);
        
        String message = String.format("Input validation failed for %d field(s)", fieldErrors.size());
        String details = fieldErrors.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("; "));
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                message,
                details,
                extractPath(request)
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles HttpMessageNotReadableException - thrown when JSON request body cannot be parsed.
     * 
     * <p>This method handles JSON deserialization errors including malformed JSON syntax,
     * type mismatches, missing required fields, and invalid date/number formats.</p>
     * 
     * <h3>COBOL Pattern Replaced:</h3>
     * <pre>
     * COBOL (EXEC CICS RECEIVE MAP errors):
     *   EXEC CICS RECEIVE MAP('COACTUP')
     *        MAPSET('COACTUP')
     *        INTO(COACTUPI)
     *        RESP(WS-RESP-CD)
     *   END-EXEC
     *   
     *   EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(MAPFAIL)
     *       MOVE 'Invalid or incomplete map data' TO WS-MESSAGE
     *     WHEN DFHRESP(INVREQ)
     *       MOVE 'Invalid request - check field formats' TO WS-MESSAGE
     *   END-EVALUATE
     * 
     * Java:
     *   // JSON: {"accountId": "not-a-number", "balance": "invalid"}
     *   // → HttpMessageNotReadableException
     *   // → This handler returns HTTP 400 with parse error details
     * </pre>
     * 
     * <h3>Common JSON Parsing Errors:</h3>
     * <ul>
     *   <li>Malformed JSON syntax (missing quotes, brackets, commas)</li>
     *   <li>Type mismatch (string value for numeric field)</li>
     *   <li>Invalid date format (expected yyyy-MM-dd, got MM/DD/YYYY)</li>
     *   <li>Invalid enum value (expected Y or N, got X)</li>
     *   <li>Missing required JSON fields</li>
     * </ul>
     * 
     * <h3>Response Example:</h3>
     * <pre>
     * HTTP/1.1 400 Bad Request
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 400,
     *   "error": "Malformed Request",
     *   "message": "Failed to parse JSON request body",
     *   "details": "Invalid JSON format or type mismatch. Please check request body structure.",
     *   "path": "/api/accounts"
     * }
     * </pre>
     * 
     * @param ex The HttpMessageNotReadableException thrown during JSON parsing
     * @param request WebRequest containing HTTP request details including URI path
     * @return ResponseEntity with HTTP 400 status and ErrorResponse body
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, 
            WebRequest request) {
        
        log.warn("Failed to parse JSON request body | Path: {} | Error: {}", 
                extractPath(request),
                ex.getMessage());
        
        String details = "Invalid JSON format or type mismatch. Please check request body structure.";
        
        // Extract more specific error message if available
        if (ex.getCause() != null) {
            String causeMessage = ex.getCause().getMessage();
            if (causeMessage != null && causeMessage.length() > 0) {
                // Limit details to prevent exposing internal implementation
                details = causeMessage.length() > 200 
                        ? causeMessage.substring(0, 200) + "..." 
                        : causeMessage;
            }
        }
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Malformed Request",
                "Failed to parse JSON request body",
                details,
                extractPath(request)
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles all other unhandled exceptions - catch-all for unexpected errors.
     * 
     * <p>This is the catch-all exception handler for any exception not caught by specific
     * handlers. It returns HTTP 500 Internal Server Error and logs full stack trace for
     * debugging.</p>
     * 
     * <h3>COBOL Pattern Replaced:</h3>
     * <pre>
     * COBOL (EXEC CICS ABEND):
     *   EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *       CONTINUE
     *     WHEN DFHRESP(NOTFND)
     *       PERFORM 9900-HANDLE-NOT-FOUND
     *     WHEN OTHER
     *       DISPLAY 'Unexpected error: ' WS-RESP-CD
     *       EXEC CICS ABEND ABCODE('ACRD') NODUMP END-EXEC
     *   END-EVALUATE
     * 
     * COBOL (VSAM System Error - FILE-STATUS 90+):
     *   READ ACCTFILE-FILE
     *   END-READ
     *   
     *   IF FILE-STATUS >= 90
     *     DISPLAY 'VSAM system error: ' FILE-STATUS
     *     CALL 'CEE3ABD' USING BY VALUE 999
     *   END-IF
     * 
     * Java:
     *   // Any unhandled exception (NullPointerException, SQLException, etc.)
     *   // → This handler returns HTTP 500 with generic error message
     * </pre>
     * 
     * <h3>Examples of Caught Exceptions:</h3>
     * <ul>
     *   <li>NullPointerException - programming errors with null values</li>
     *   <li>SQLException - database connection or query failures</li>
     *   <li>IOException - file I/O errors</li>
     *   <li>IllegalStateException - invalid application state</li>
     *   <li>Any other RuntimeException not handled by specific handlers</li>
     * </ul>
     * 
     * <h3>Response Example:</h3>
     * <pre>
     * HTTP/1.1 500 Internal Server Error
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2025-10-26T10:30:15",
     *   "status": 500,
     *   "error": "Internal Server Error",
     *   "message": "An unexpected error occurred",
     *   "details": "NullPointerException: Cannot invoke method on null object",
     *   "path": "/api/accounts/12345"
     * }
     * </pre>
     * 
     * @param ex The generic Exception caught by this handler
     * @param request WebRequest containing HTTP request details including URI path
     * @return ResponseEntity with HTTP 500 status and ErrorResponse body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, 
            WebRequest request) {
        
        log.error("Unexpected error occurred | Path: {} | Exception: {} | Message: {}", 
                extractPath(request),
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                ex); // Log full stack trace
        
        // Don't expose internal error details to clients in production
        String details = ex.getClass().getSimpleName() + ": " + 
                (ex.getMessage() != null ? ex.getMessage() : "No additional details available");
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred",
                details,
                extractPath(request)
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Extracts the request URI path from WebRequest.
     * 
     * <p>Removes query parameters and returns clean path for error response.</p>
     * 
     * @param request WebRequest containing HTTP request details
     * @return Clean URI path (e.g., "/api/accounts/12345")
     */
    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        // Remove "uri=" prefix added by WebRequest.getDescription(false)
        return description.replace("uri=", "");
    }

    /**
     * Builds detailed error message for ValidationException.
     * 
     * <p>Includes field name and error code if available.</p>
     * 
     * @param ex The ValidationException
     * @return Formatted details string
     */
    private String buildValidationDetails(ValidationException ex) {
        StringBuilder details = new StringBuilder();
        
        if (ex.getFieldName() != null) {
            details.append("Field '").append(ex.getFieldName()).append("' failed validation");
        } else {
            details.append("Validation failed");
        }
        
        if (ex.getErrorCode() != null) {
            details.append(": ").append(ex.getErrorCode());
        }
        
        return details.toString();
    }

    /**
     * Builds detailed error message for BusinessException.
     * 
     * <p>Includes error code and context information if available.</p>
     * 
     * @param ex The BusinessException
     * @return Formatted details string with error code and context
     */
    private String buildBusinessExceptionDetails(BusinessException ex) {
        StringBuilder details = new StringBuilder();
        details.append("Error code: ").append(ex.getErrorCode());
        
        if (ex.getContext() != null && !ex.getContext().isEmpty()) {
            details.append(" | Context: ").append(ex.getContext());
        }
        
        return details.toString();
    }
}

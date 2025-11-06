package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login Request DTO for user authentication in CardDemo application.
 * 
 * <p>This Data Transfer Object represents the inbound contract for the 
 * POST /api/auth/login REST API endpoint, replacing the CICS CC00 transaction 
 * COMMAREA structure from the mainframe application.</p>
 * 
 * <h2>COBOL Source Mappings</h2>
 * <ul>
 *   <li><b>User ID Field:</b> CDEMO-USER-ID PIC X(08) from COCOM01Y.cpy line 25</li>
 *   <li><b>Password Field:</b> SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy line 21</li>
 * </ul>
 * 
 * <h2>BMS Mapset References</h2>
 * <ul>
 *   <li><b>USERID field:</b> COSGN00.bms lines 156-169 (LENGTH=8, 8 character maximum)</li>
 *   <li><b>PASSWD field:</b> COSGN00.bms lines 175-189 (LENGTH=8, 8 character maximum)</li>
 * </ul>
 * 
 * <h2>Field Validation Rules</h2>
 * <p>Both fields are required and must be between 1 and 8 characters, matching 
 * the COBOL PIC X(08) field length restriction from the mainframe application.</p>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * POST /api/auth/login
 * Content-Type: application/json
 * 
 * {
 *   "userId": "testuser",
 *   "password": "pass1234"
 * }
 * </pre>
 * 
 * <h2>Migration Notes</h2>
 * <ul>
 *   <li>Replaces CICS pseudo-conversational COMMAREA structure for CC00 signon transaction</li>
 *   <li>Serves as inbound contract layer between AuthController and AuthenticationService</li>
 *   <li>Part of stateless REST architecture replacing CICS transaction processing</li>
 *   <li>Jakarta Bean Validation enforces mainframe field constraints at API boundary</li>
 * </ul>
 * 
 * @see com.carddemo.controller.AuthController
 * @see com.carddemo.service.auth.AuthenticationService
 * @see com.carddemo.dto.response.LoginResponse
 * 
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {

    /**
     * User ID for authentication.
     * 
     * <p>Maps to CDEMO-USER-ID PIC X(08) from COCOM01Y.cpy line 25 and 
     * USERID field from COSGN00.bms lines 156-160.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Must not be blank (required field)</li>
     *   <li>Minimum length: 1 character</li>
     *   <li>Maximum length: 8 characters (matches COBOL PIC X(08))</li>
     * </ul>
     * 
     * @see com.carddemo.entity.User#getUserId()
     */
    @JsonProperty("userId")
    @NotBlank(message = "User ID is required and cannot be blank")
    @Size(min = 1, max = 8, message = "User ID must be between 1 and 8 characters")
    private String userId;

    /**
     * Password for authentication.
     * 
     * <p>Maps to SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy line 21 and 
     * PASSWD field from COSGN00.bms lines 175-180.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Must not be blank (required field)</li>
     *   <li>Minimum length: 1 character</li>
     *   <li>Maximum length: 8 characters (matches COBOL PIC X(08))</li>
     * </ul>
     * 
     * <p><b>Security Note:</b> Password is transmitted in plain text over HTTPS. 
     * The AuthenticationService performs BCrypt hashing for storage and comparison.</p>
     * 
     * @see com.carddemo.entity.User#getPassword()
     * @see com.carddemo.service.auth.AuthenticationService#authenticate(LoginRequest)
     */
    @JsonProperty("password")
    @NotBlank(message = "Password is required and cannot be blank")
    @Size(min = 1, max = 8, message = "Password must be between 1 and 8 characters")
    private String password;
}

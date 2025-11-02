/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Login Response Data Transfer Object
 * 
 * <p>Response DTO for user authentication and sign-on operations, transformed from 
 * COBOL BMS copybook COSGN00.CPY (COSGN0AO output structure) representing the 
 * sign-on screen response displayed after CICS transaction CC00 authentication.</p>
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <ul>
 *   <li>COSGN0AO.TRNNAMEO (PIC X(4)) → transactionName</li>
 *   <li>COSGN0AO.TITLE01O (PIC X(40)) → title01</li>
 *   <li>COSGN0AO.CURDATEO (PIC X(8)) → currentDate (converted to LocalDate)</li>
 *   <li>COSGN0AO.PGMNAMEO (PIC X(8)) → programName</li>
 *   <li>COSGN0AO.TITLE02O (PIC X(40)) → title02</li>
 *   <li>COSGN0AO.CURTIMEO (PIC X(9)) → currentTime (converted to LocalTime)</li>
 *   <li>COSGN0AO.APPLIDO (PIC X(8)) → applicationId</li>
 *   <li>COSGN0AO.SYSIDO (PIC X(8)) → systemId</li>
 *   <li>COSGN0AO.USERIDO (PIC X(8)) → userId</li>
 *   <li>COSGN0AO.ERRMSGO (PIC X(78)) → errorMessage</li>
 * </ul>
 * 
 * <p><b>Modern Architecture Enhancement:</b></p>
 * <ul>
 *   <li>token (JWT) - NEW field NOT present in COBOL, required by Spring Security 
 *       JWT-based authentication to replace CICS session management</li>
 * </ul>
 * 
 * <p><b>Business Logic Preservation:</b></p>
 * <p>This DTO maintains functional equivalence with the COBOL COSGN00C program's 
 * sign-on response behavior:</p>
 * <ul>
 *   <li>Preserves all screen display fields from 3270 terminal output</li>
 *   <li>Maintains field lengths matching COBOL PIC clauses for data integrity</li>
 *   <li>Converts COBOL date format (MM/DD/YY) to ISO-8601 LocalDate</li>
 *   <li>Converts COBOL time format (HH:MM:SS) to ISO-8601 LocalTime</li>
 *   <li>Includes error message field for authentication failure scenarios</li>
 *   <li>Adds JWT token for stateless REST API authentication per Section 0.1 
 *       of Agent Action Plan</li>
 * </ul>
 * 
 * <p><b>COBOL Program Logic Reference:</b></p>
 * <p>In COSGN00C.cbl, the POPULATE-HEADER-INFO paragraph populates output fields:</p>
 * <pre>
 * MOVE CCDA-TITLE01           TO TITLE01O OF COSGN0AO
 * MOVE CCDA-TITLE02           TO TITLE02O OF COSGN0AO
 * MOVE WS-TRANID              TO TRNNAMEO OF COSGN0AO
 * MOVE WS-PGMNAME             TO PGMNAMEO OF COSGN0AO
 * MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COSGN0AO
 * MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COSGN0AO
 * EXEC CICS ASSIGN APPLID(APPLIDO OF COSGN0AO) END-EXEC
 * EXEC CICS ASSIGN SYSID(SYSIDO OF COSGN0AO) END-EXEC
 * </pre>
 * 
 * <p><b>Usage in Modern Architecture:</b></p>
 * <p>Returned by AuthenticationController POST /api/auth/login endpoint upon 
 * successful authentication, establishing JWT-based session for subsequent 
 * authorized API requests via Authorization: Bearer {token} header.</p>
 * 
 * <p><b>Migration Context:</b></p>
 * <ul>
 *   <li>Replaces CICS pseudo-conversational COMMAREA-based state management</li>
 *   <li>Implements stateless REST API pattern per Section 0.3 Technical Interpretation</li>
 *   <li>Supports Redis-backed session storage per Section 0.5 Refactored Structure</li>
 *   <li>Enables React frontend consumption via JSON serialization</li>
 * </ul>
 * 
 * @see com.carddemo.controller.AuthenticationController
 * @see com.carddemo.service.AuthenticationService
 * @since 1.0
 * @version CardDemo v1.0-Java Migration
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse implements Serializable {

    /**
     * Serial version UID for Serializable implementation.
     * Enables Redis session storage and distributed caching support.
     */
    private static final long serialVersionUID = 1L;

    /**
     * JWT Bearer Token for API Authentication
     * 
     * <p><b>NEW FIELD - Not present in COBOL source</b></p>
     * 
     * <p>JWT (JSON Web Token) issued upon successful authentication, replacing 
     * CICS session management with stateless token-based authentication per 
     * Spring Security 6.x architecture requirements.</p>
     * 
     * <p><b>Token Contents:</b></p>
     * <ul>
     *   <li>Subject: userId</li>
     *   <li>Issued At: authentication timestamp</li>
     *   <li>Expiration: 24 hours from issuance</li>
     *   <li>Claims: userType (ROLE_USER or ROLE_ADMIN)</li>
     * </ul>
     * 
     * <p><b>Usage:</b> Client includes token in Authorization header for 
     * subsequent API requests: {@code Authorization: Bearer {token}}</p>
     * 
     * <p><b>Migration Rationale:</b> CICS maintains session state via COMMAREA 
     * across pseudo-conversational transactions. In stateless REST architecture, 
     * JWT token carries authentication context, eliminating server-side session 
     * state while maintaining security per Section 0.1 Security Model Migration.</p>
     */
    @JsonProperty("token")
    private String token;

    /**
     * User Identifier
     * 
     * <p>Maps to COSGN0AO.USERIDO (PIC X(8))</p>
     * 
     * <p>Unique user identifier authenticated against UserSecurity repository 
     * (USRSEC VSAM file in COBOL). Converted to uppercase per COBOL logic:
     * {@code MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID}</p>
     * 
     * <p>Stored in JWT token subject claim for authorization checks on 
     * protected endpoints. Used for user-specific data filtering in account, 
     * card, and transaction queries per role-based access control.</p>
     * 
     * <p><b>Validation:</b> Maximum 8 characters matching COBOL PIC X(8) length</p>
     */
    @JsonProperty("userId")
    @Size(max = 8, message = "User ID cannot exceed 8 characters")
    private String userId;

    /**
     * Transaction Name/ID
     * 
     * <p>Maps to COSGN0AO.TRNNAMEO (PIC X(4))</p>
     * 
     * <p>CICS transaction identifier for sign-on transaction. In COBOL source:
     * {@code WS-TRANID PIC X(04) VALUE 'CC00'}</p>
     * 
     * <p>Preserved for audit trail and transaction logging compatibility with 
     * mainframe conventions. Equivalent REST endpoint: POST /api/auth/login</p>
     * 
     * <p><b>Typical Value:</b> "CC00" (CICS sign-on transaction)</p>
     * <p><b>Validation:</b> Maximum 4 characters matching COBOL PIC X(4) length</p>
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name cannot exceed 4 characters")
    private String transactionName;

    /**
     * Primary Screen Title
     * 
     * <p>Maps to COSGN0AO.TITLE01O (PIC X(40))</p>
     * 
     * <p>Primary header title displayed at top of sign-on screen. In COBOL:
     * {@code MOVE CCDA-TITLE01 TO TITLE01O OF COSGN0AO}</p>
     * 
     * <p>Populated from COTTL01Y copybook common title constants. Typically 
     * contains application name "CardDemo Application" or similar branding.</p>
     * 
     * <p>Preserved for UI consistency during migration from 3270 terminal to 
     * React web interface per Section 0.4 BMS Screen transformation requirements.</p>
     * 
     * <p><b>Validation:</b> Maximum 40 characters matching COBOL PIC X(40) length</p>
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title 01 cannot exceed 40 characters")
    private String title01;

    /**
     * Secondary Screen Title
     * 
     * <p>Maps to COSGN0AO.TITLE02O (PIC X(40))</p>
     * 
     * <p>Secondary header title or subtitle displayed below primary title. 
     * In COBOL: {@code MOVE CCDA-TITLE02 TO TITLE02O OF COSGN0AO}</p>
     * 
     * <p>Typically contains screen-specific context like "Sign On" or 
     * additional application information.</p>
     * 
     * <p><b>Validation:</b> Maximum 40 characters matching COBOL PIC X(40) length</p>
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title 02 cannot exceed 40 characters")
    private String title02;

    /**
     * Current Date
     * 
     * <p>Maps to COSGN0AO.CURDATEO (PIC X(8))</p>
     * 
     * <p>Current system date at time of authentication. COBOL source populates 
     * from CURRENT-DATE function in MM/DD/YY format:</p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE  TO WS-CURDATE-DATA
     * MOVE WS-CURDATE-MONTH       TO WS-CURDATE-MM
     * MOVE WS-CURDATE-DAY         TO WS-CURDATE-DD
     * MOVE WS-CURDATE-YEAR(3:2)   TO WS-CURDATE-YY
     * MOVE WS-CURDATE-MM-DD-YY    TO CURDATEO OF COSGN0AO
     * </pre>
     * 
     * <p><b>Format Transformation:</b></p>
     * <ul>
     *   <li>COBOL: MM/DD/YY (8 characters including slashes)</li>
     *   <li>Java: LocalDate in ISO-8601 format (YYYY-MM-DD)</li>
     *   <li>JSON: "2024-01-15" (automatic Jackson serialization)</li>
     * </ul>
     * 
     * <p>Used for session timestamp and audit trail. Critical for date-sensitive 
     * operations and regulatory compliance logging per Section 0.9 Audit Requirements.</p>
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate currentDate;

    /**
     * Current Time
     * 
     * <p>Maps to COSGN0AO.CURTIMEO (PIC X(9))</p>
     * 
     * <p>Current system time at authentication. COBOL source populates from 
     * CURRENT-DATE function in HH:MM:SS format:</p>
     * <pre>
     * MOVE WS-CURTIME-HOURS       TO WS-CURTIME-HH
     * MOVE WS-CURTIME-MINUTE      TO WS-CURTIME-MM
     * MOVE WS-CURTIME-SECOND      TO WS-CURTIME-SS
     * MOVE WS-CURTIME-HH-MM-SS    TO CURTIMEO OF COSGN0AO
     * </pre>
     * 
     * <p><b>Format Transformation:</b></p>
     * <ul>
     *   <li>COBOL: HH:MM:SS (9 characters including colons)</li>
     *   <li>Java: LocalTime in ISO-8601 format</li>
     *   <li>JSON: "14:23:45" (automatic Jackson serialization)</li>
     * </ul>
     * 
     * <p>Combined with currentDate provides complete authentication timestamp 
     * for audit trail and session management.</p>
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;

    /**
     * Program Name
     * 
     * <p>Maps to COSGN0AO.PGMNAMEO (PIC X(8))</p>
     * 
     * <p>COBOL program identifier executing sign-on logic. In COBOL:
     * {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'}</p>
     * 
     * <p>Preserved for audit trail showing which component processed the 
     * authentication request. In Java architecture, corresponds to 
     * AuthenticationService but maintains COBOL program name for 
     * transaction traceability during parallel testing phase.</p>
     * 
     * <p><b>Typical Value:</b> "COSGN00C"</p>
     * <p><b>Validation:</b> Maximum 8 characters matching COBOL PIC X(8) length</p>
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name cannot exceed 8 characters")
    private String programName;

    /**
     * Application Identifier
     * 
     * <p>Maps to COSGN0AO.APPLIDO (PIC X(8))</p>
     * 
     * <p>CICS application identifier obtained via ASSIGN command. In COBOL:
     * {@code EXEC CICS ASSIGN APPLID(APPLIDO OF COSGN0AO) END-EXEC}</p>
     * 
     * <p>Identifies the CICS application region processing the transaction. 
     * In cloud-native architecture, corresponds to Kubernetes pod name or 
     * application instance identifier for distributed system tracing.</p>
     * 
     * <p>Preserved for environment identification and multi-instance deployment 
     * troubleshooting per Section 0.5 Kubernetes deployment requirements.</p>
     * 
     * <p><b>Validation:</b> Maximum 8 characters matching COBOL PIC X(8) length</p>
     */
    @JsonProperty("applicationId")
    @Size(max = 8, message = "Application ID cannot exceed 8 characters")
    private String applicationId;

    /**
     * System Identifier
     * 
     * <p>Maps to COSGN0AO.SYSIDO (PIC X(8))</p>
     * 
     * <p>CICS system identifier obtained via ASSIGN command. In COBOL:
     * {@code EXEC CICS ASSIGN SYSID(SYSIDO OF COSGN0AO) END-EXEC}</p>
     * 
     * <p>Identifies the z/OS system or LPAR executing the CICS region. In 
     * cloud architecture, maps to cluster name or deployment environment 
     * identifier (DEV, UAT, PROD).</p>
     * 
     * <p>Critical for multi-region deployments and cross-environment 
     * transaction tracing per enterprise monitoring requirements.</p>
     * 
     * <p><b>Validation:</b> Maximum 8 characters matching COBOL PIC X(8) length</p>
     */
    @JsonProperty("systemId")
    @Size(max = 8, message = "System ID cannot exceed 8 characters")
    private String systemId;

    /**
     * Error Message
     * 
     * <p>Maps to COSGN0AO.ERRMSGO (PIC X(78))</p>
     * 
     * <p>User-facing error message displayed for authentication failures. 
     * COBOL source sets various error messages:</p>
     * <ul>
     *   <li>"Please enter User ID ..." - Missing user ID</li>
     *   <li>"Please enter Password ..." - Missing password</li>
     *   <li>"Wrong Password. Try again ..." - Password mismatch</li>
     *   <li>"User not found. Try again ..." - Invalid user ID (RESP 13)</li>
     *   <li>"Unable to verify the User ..." - System error</li>
     * </ul>
     * 
     * <p>Populated from WS-MESSAGE working storage variable:
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO}</p>
     * 
     * <p><b>Successful Authentication:</b> Field is blank/spaces</p>
     * <p><b>Failed Authentication:</b> Contains descriptive error message</p>
     * 
     * <p>Frontend React components display this message to guide user 
     * correction. Error messages maintain exact wording from COBOL for 
     * user experience consistency per Section 0.9 functional equivalence.</p>
     * 
     * <p><b>Validation:</b> Maximum 78 characters matching COBOL PIC X(78) length</p>
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message cannot exceed 78 characters")
    private String errorMessage;

    /**
     * Indicates Successful Authentication
     * 
     * <p>Convenience method to determine if login was successful based on 
     * presence of JWT token and absence of error message.</p>
     * 
     * <p>In COBOL, successful authentication results in EXEC CICS XCTL to 
     * next program (COADM01C or COMEN01C). In REST API, success indicated 
     * by HTTP 200 status, populated JWT token, and blank error message.</p>
     * 
     * @return true if token is present and errorMessage is null/blank, false otherwise
     */
    public boolean isSuccess() {
        return token != null && !token.trim().isEmpty() 
            && (errorMessage == null || errorMessage.trim().isEmpty());
    }

    /**
     * Checks if Response Contains Error
     * 
     * <p>Convenience method to determine if authentication failed based on 
     * error message presence.</p>
     * 
     * <p>In COBOL, error scenarios result in SEND-SIGNON-SCREEN with populated 
     * ERRMSGO field. In REST API, errors indicated by populated errorMessage 
     * field and potentially null token.</p>
     * 
     * @return true if errorMessage is present and non-blank, false otherwise
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.trim().isEmpty();
    }
}

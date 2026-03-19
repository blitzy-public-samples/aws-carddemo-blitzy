/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.common.context;

import com.cardemo.common.enums.UserType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped session context bean mirroring the 1024-byte COBOL
 * {@code CARDDEMO-COMMAREA} structure defined in copybook {@code COCOM01Y.cpy}.
 *
 * <p>In the legacy CICS application, the COMMAREA is a contiguous memory area
 * passed between programs via {@code EXEC CICS XCTL} or
 * {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)}. It carries all session
 * state for the pseudo-conversational model: user identity, navigation routing
 * (from/to transaction and program), selected entity context (customer, account,
 * card), and screen navigation tracking (last map/mapset).</p>
 *
 * <p>In the Java migration, this class replaces the COMMAREA with a
 * request-scoped Spring bean. One instance is created per HTTP request and
 * destroyed at the end of the request, preserving the stateless nature of the
 * pseudo-conversational model where each SEND/RECEIVE cycle gets its own
 * COMMAREA copy.</p>
 *
 * <h2>COMMAREA Structure Mapping (COCOM01Y.cpy lines 19-44)</h2>
 * <pre>
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.
 *       10 CDEMO-FROM-TRANID     PIC X(04).  → {@link #fromTranId}
 *       10 CDEMO-FROM-PROGRAM    PIC X(08).  → {@link #fromProgram}
 *       10 CDEMO-TO-TRANID       PIC X(04).  → {@link #toTranId}
 *       10 CDEMO-TO-PROGRAM      PIC X(08).  → {@link #toProgram}
 *       10 CDEMO-USER-ID         PIC X(08).  → {@link #userId}
 *       10 CDEMO-USER-TYPE       PIC X(01).  → {@link #userType}
 *          88 CDEMO-USRTYP-ADMIN VALUE 'A'.  → {@link UserType#ADMIN}
 *          88 CDEMO-USRTYP-USER  VALUE 'U'.  → {@link UserType#USER}
 *       10 CDEMO-PGM-CONTEXT     PIC 9(01).  → {@link #pgmContext}
 *          88 CDEMO-PGM-ENTER    VALUE 0.    → {@link #PGM_ENTER}
 *          88 CDEMO-PGM-REENTER  VALUE 1.    → {@link #PGM_REENTER}
 *    05 CDEMO-CUSTOMER-INFO.
 *       10 CDEMO-CUST-ID         PIC 9(09).  → {@link #custId}
 *       10 CDEMO-CUST-FNAME      PIC X(25).  → {@link #custFname}
 *       10 CDEMO-CUST-MNAME      PIC X(25).  → {@link #custMname}
 *       10 CDEMO-CUST-LNAME      PIC X(25).  → {@link #custLname}
 *    05 CDEMO-ACCOUNT-INFO.
 *       10 CDEMO-ACCT-ID         PIC 9(11).  → {@link #acctId}
 *       10 CDEMO-ACCT-STATUS     PIC X(01).  → {@link #acctStatus}
 *    05 CDEMO-CARD-INFO.
 *       10 CDEMO-CARD-NUM        PIC 9(16).  → {@link #cardNum}
 *    05 CDEMO-MORE-INFO.
 *       10 CDEMO-LAST-MAP        PIC X(7).   → {@link #lastMap}
 *       10 CDEMO-LAST-MAPSET     PIC X(7).   → {@link #lastMapset}
 * </pre>
 *
 * <h2>Cross-Cutting Usage</h2>
 * <p>Every online service ({@code SignonService}, {@code MainMenuService},
 * {@code AccountViewService}, etc.) injects this bean via {@code @Autowired}.
 * The signon flow populates {@code userId}, {@code userType}, and initial
 * navigation fields. Selection screens populate entity IDs ({@code custId},
 * {@code acctId}, {@code cardNum}) for detail views.</p>
 *
 * <h2>Scope Limitation — Batch Context</h2>
 * <p><strong>Important:</strong> The {@code @RequestScope} annotation requires an
 * active HTTP request context (i.e., a web application context with an in-flight
 * servlet request). This bean is <em>not available</em> in Spring Batch job execution
 * contexts, where there is no HTTP request. Batch services that process transactions
 * offline (e.g., {@code DailyPostingService}, {@code InterestCalculationService})
 * should <em>not</em> inject {@code CardDemoContext}. If batch services require
 * user/session context in the future, a {@code @Scope("prototype")} fallback or a
 * custom scope resolver that works in both web and batch contexts should be
 * provided.</p>
 *
 * @see com.cardemo.common.enums.UserType
 */
@Component
@RequestScope
public class CardDemoContext {

    // ========================================================================
    // PGM Context Constants (← COBOL 88-level conditions on CDEMO-PGM-CONTEXT)
    // ========================================================================

    /**
     * Program context value indicating first entry into a program.
     * Maps to COBOL {@code 88 CDEMO-PGM-ENTER VALUE 0}.
     * When {@link #pgmContext} equals this value, the program should
     * display the initial screen with fresh data.
     */
    public static final int PGM_ENTER = 0;

    /**
     * Program context value indicating re-entry into a program after
     * user input submission.
     * Maps to COBOL {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     * When {@link #pgmContext} equals this value, the program should
     * receive and process the user's submitted data.
     */
    public static final int PGM_REENTER = 1;

    // ========================================================================
    // Group 1: General Info (← CDEMO-GENERAL-INFO)
    // ========================================================================

    /**
     * Transaction ID of the originating (previous) CICS transaction.
     * Maps to {@code CDEMO-FROM-TRANID PIC X(04)}.
     * Example values: "CC00" (sign-on), "CM00" (main menu), "CA00" (admin).
     * Maximum 4 characters.
     */
    private String fromTranId;

    /**
     * Program name of the originating (previous) CICS program.
     * Maps to {@code CDEMO-FROM-PROGRAM PIC X(08)}.
     * Example values: "COSGN00C", "COMEN01C", "COADM01C".
     * Maximum 8 characters.
     */
    private String fromProgram;

    /**
     * Transaction ID of the target (next) CICS transaction.
     * Maps to {@code CDEMO-TO-TRANID PIC X(04)}.
     * Set by the current program to indicate where control should transfer.
     * Maximum 4 characters.
     */
    private String toTranId;

    /**
     * Program name of the target (next) CICS program.
     * Maps to {@code CDEMO-TO-PROGRAM PIC X(08)}.
     * Set by the current program to indicate the XCTL target.
     * Maximum 8 characters.
     */
    private String toProgram;

    /**
     * Authenticated user ID for the current session.
     * Maps to {@code CDEMO-USER-ID PIC X(08)}.
     * Set during sign-on (COSGN00C) and carried through all transactions.
     * Maximum 8 characters matching the USRSEC primary key length.
     */
    private String userId;

    /**
     * User type (role) for the current session.
     * Maps to {@code CDEMO-USER-TYPE PIC X(01)} with 88-level conditions:
     * <ul>
     *   <li>{@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} → {@link UserType#ADMIN}</li>
     *   <li>{@code 88 CDEMO-USRTYP-USER  VALUE 'U'} → {@link UserType#USER}</li>
     * </ul>
     */
    private UserType userType;

    /**
     * Program context indicator for pseudo-conversational flow control.
     * Maps to {@code CDEMO-PGM-CONTEXT PIC 9(01)} with 88-level conditions:
     * <ul>
     *   <li>{@code 88 CDEMO-PGM-ENTER   VALUE 0} — first entry into program
     *       (see {@link #PGM_ENTER})</li>
     *   <li>{@code 88 CDEMO-PGM-REENTER VALUE 1} — re-entry after user input
     *       (see {@link #PGM_REENTER})</li>
     * </ul>
     * This is the core mechanism of the pseudo-conversational pattern:
     * value 0 means display fresh data, value 1 means process submitted data.
     */
    private int pgmContext;

    // ========================================================================
    // Group 2: Customer Info (← CDEMO-CUSTOMER-INFO)
    // ========================================================================

    /**
     * Current customer ID being viewed or edited.
     * Maps to {@code CDEMO-CUST-ID PIC 9(09)}.
     * Stored as a 9-digit numeric string to preserve leading zeros,
     * matching COBOL PIC 9(09) behavior where value 1 is stored as "000000001".
     */
    private String custId;

    /**
     * Current customer's first name.
     * Maps to {@code CDEMO-CUST-FNAME PIC X(25)}.
     * Maximum 25 characters.
     */
    private String custFname;

    /**
     * Current customer's middle name.
     * Maps to {@code CDEMO-CUST-MNAME PIC X(25)}.
     * Maximum 25 characters.
     */
    private String custMname;

    /**
     * Current customer's last name.
     * Maps to {@code CDEMO-CUST-LNAME PIC X(25)}.
     * Maximum 25 characters.
     */
    private String custLname;

    // ========================================================================
    // Group 3: Account Info (← CDEMO-ACCOUNT-INFO)
    // ========================================================================

    /**
     * Current account ID being viewed or edited.
     * Maps to {@code CDEMO-ACCT-ID PIC 9(11)}.
     * Stored as an 11-digit numeric string to preserve leading zeros,
     * matching COBOL PIC 9(11) behavior.
     */
    private String acctId;

    /**
     * Current account status.
     * Maps to {@code CDEMO-ACCT-STATUS PIC X(01)}.
     * Typical values: 'Y' (active), 'N' (inactive/closed).
     * Single character.
     */
    private String acctStatus;

    // ========================================================================
    // Group 4: Card Info (← CDEMO-CARD-INFO)
    // ========================================================================

    /**
     * Current card number being viewed or edited.
     * Maps to {@code CDEMO-CARD-NUM PIC 9(16)}.
     * Stored as a 16-digit numeric string to preserve leading zeros.
     * <strong>PII field</strong> — must not appear in plaintext in
     * {@link #toString()} output or log messages.
     */
    private String cardNum;

    // ========================================================================
    // Group 5: More Info (← CDEMO-MORE-INFO)
    // ========================================================================

    /**
     * Name of the last BMS map sent to the terminal.
     * Maps to {@code CDEMO-LAST-MAP PIC X(7)}.
     * Used for pseudo-conversational re-entry to determine which
     * map to receive data from. Maximum 7 characters.
     */
    private String lastMap;

    /**
     * Name of the last BMS mapset (containing the map) sent to the terminal.
     * Maps to {@code CDEMO-LAST-MAPSET PIC X(7)}.
     * Used together with {@link #lastMap} for re-entry processing.
     * Maximum 7 characters.
     */
    private String lastMapset;

    // ========================================================================
    // Getters and Setters — Standard JavaBean pattern
    // No validation in setters — validation is handled by the service layer,
    // matching COBOL behavior where COMMAREA fields are freely set via MOVE.
    // ========================================================================

    /**
     * Returns the originating transaction ID.
     *
     * @return the from-transaction ID, or {@code null} if not set
     */
    public String getFromTranId() {
        return fromTranId;
    }

    /**
     * Sets the originating transaction ID.
     *
     * @param fromTranId the from-transaction ID (max 4 chars)
     */
    public void setFromTranId(String fromTranId) {
        this.fromTranId = fromTranId;
    }

    /**
     * Returns the originating program name.
     *
     * @return the from-program name, or {@code null} if not set
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * Sets the originating program name.
     *
     * @param fromProgram the from-program name (max 8 chars)
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * Returns the target transaction ID.
     *
     * @return the to-transaction ID, or {@code null} if not set
     */
    public String getToTranId() {
        return toTranId;
    }

    /**
     * Sets the target transaction ID.
     *
     * @param toTranId the to-transaction ID (max 4 chars)
     */
    public void setToTranId(String toTranId) {
        this.toTranId = toTranId;
    }

    /**
     * Returns the target program name.
     *
     * @return the to-program name, or {@code null} if not set
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the target program name.
     *
     * @param toProgram the to-program name (max 8 chars)
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Returns the authenticated user ID.
     *
     * @return the user ID, or {@code null} if not authenticated
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the authenticated user ID.
     *
     * @param userId the user ID (max 8 chars)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user type (role) for the current session.
     *
     * @return the {@link UserType}, or {@code null} if not set
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the user type (role) for the current session.
     *
     * @param userType the {@link UserType} value
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Returns the program context indicator.
     *
     * @return {@link #PGM_ENTER} (0) for first entry,
     *         {@link #PGM_REENTER} (1) for re-entry after user input
     */
    public int getPgmContext() {
        return pgmContext;
    }

    /**
     * Sets the program context indicator.
     *
     * @param pgmContext {@link #PGM_ENTER} (0) or {@link #PGM_REENTER} (1)
     */
    public void setPgmContext(int pgmContext) {
        this.pgmContext = pgmContext;
    }

    /**
     * Returns the current customer ID.
     *
     * @return the customer ID (9-digit string), or {@code null} if not set
     */
    public String getCustId() {
        return custId;
    }

    /**
     * Sets the current customer ID.
     *
     * @param custId the customer ID (9-digit string preserving leading zeros)
     */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /**
     * Returns the current customer's first name.
     *
     * @return the first name, or {@code null} if not set
     */
    public String getCustFname() {
        return custFname;
    }

    /**
     * Sets the current customer's first name.
     *
     * @param custFname the first name (max 25 chars)
     */
    public void setCustFname(String custFname) {
        this.custFname = custFname;
    }

    /**
     * Returns the current customer's middle name.
     *
     * @return the middle name, or {@code null} if not set
     */
    public String getCustMname() {
        return custMname;
    }

    /**
     * Sets the current customer's middle name.
     *
     * @param custMname the middle name (max 25 chars)
     */
    public void setCustMname(String custMname) {
        this.custMname = custMname;
    }

    /**
     * Returns the current customer's last name.
     *
     * @return the last name, or {@code null} if not set
     */
    public String getCustLname() {
        return custLname;
    }

    /**
     * Sets the current customer's last name.
     *
     * @param custLname the last name (max 25 chars)
     */
    public void setCustLname(String custLname) {
        this.custLname = custLname;
    }

    /**
     * Returns the current account ID.
     *
     * @return the account ID (11-digit string), or {@code null} if not set
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the current account ID.
     *
     * @param acctId the account ID (11-digit string preserving leading zeros)
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the current account status.
     *
     * @return the account status character, or {@code null} if not set
     */
    public String getAcctStatus() {
        return acctStatus;
    }

    /**
     * Sets the current account status.
     *
     * @param acctStatus the account status (single character)
     */
    public void setAcctStatus(String acctStatus) {
        this.acctStatus = acctStatus;
    }

    /**
     * Returns the current card number.
     * <strong>PII field</strong> — callers must handle with care.
     *
     * @return the card number (16-digit string), or {@code null} if not set
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the current card number.
     *
     * @param cardNum the card number (16-digit string preserving leading zeros)
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the last BMS map name used.
     *
     * @return the last map name, or {@code null} if not set
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * Sets the last BMS map name used.
     *
     * @param lastMap the map name (max 7 chars)
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * Returns the last BMS mapset name used.
     *
     * @return the last mapset name, or {@code null} if not set
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * Sets the last BMS mapset name used.
     *
     * @param lastMapset the mapset name (max 7 chars)
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }

    // ========================================================================
    // Convenience Methods (← COBOL 88-level condition equivalents)
    // ========================================================================

    /**
     * Determines whether the current user has administrator privileges.
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-USRTYP-ADMIN} (i.e., {@code CDEMO-USER-TYPE = 'A'}).
     *
     * @return {@code true} if the user type is {@link UserType#ADMIN},
     *         {@code false} if the user type is any other value or {@code null}
     */
    public boolean isAdmin() {
        return userType == UserType.ADMIN;
    }

    /**
     * Determines whether this is a first entry into the program.
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-PGM-ENTER} (i.e., {@code CDEMO-PGM-CONTEXT = 0}).
     *
     * <p>When this returns {@code true}, the program should display the
     * initial screen with fresh data (no user input to process yet).</p>
     *
     * @return {@code true} if {@link #pgmContext} equals {@link #PGM_ENTER} (0)
     */
    public boolean isEnterContext() {
        return pgmContext == PGM_ENTER;
    }

    /**
     * Determines whether this is a re-entry into the program after user
     * input submission.
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-PGM-REENTER} (i.e., {@code CDEMO-PGM-CONTEXT = 1}).
     *
     * <p>When this returns {@code true}, the program should receive the
     * map data and process the user's submitted input.</p>
     *
     * @return {@code true} if {@link #pgmContext} equals {@link #PGM_REENTER} (1)
     */
    public boolean isReenterContext() {
        return pgmContext == PGM_REENTER;
    }

    // ========================================================================
    // Object overrides
    // ========================================================================

    /**
     * Returns a human-readable representation of this context for
     * logging and debugging purposes.
     *
     * <p><strong>PII protection:</strong> The {@link #cardNum} field is
     * masked (only last 4 digits shown) to prevent sensitive card numbers
     * from appearing in log files.</p>
     *
     * @return a string representation of the context with masked PII
     */
    @Override
    public String toString() {
        String maskedCard = cardNum != null
                ? "****" + cardNum.substring(Math.max(0, cardNum.length() - 4))
                : "null";
        return "CardDemoContext{"
                + "fromTranId='" + fromTranId + '\''
                + ", fromProgram='" + fromProgram + '\''
                + ", toTranId='" + toTranId + '\''
                + ", toProgram='" + toProgram + '\''
                + ", userId='" + userId + '\''
                + ", userType=" + userType
                + ", pgmContext=" + pgmContext
                + ", custId='" + custId + '\''
                + ", custFname='" + custFname + '\''
                + ", custMname='" + custMname + '\''
                + ", custLname='" + custLname + '\''
                + ", acctId='" + acctId + '\''
                + ", acctStatus='" + acctStatus + '\''
                + ", cardNum='" + maskedCard + '\''
                + ", lastMap='" + lastMap + '\''
                + ", lastMapset='" + lastMapset + '\''
                + '}';
    }
}

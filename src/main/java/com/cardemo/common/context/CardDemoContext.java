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
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * Request-scoped session context bean mirroring the 1024-byte COBOL
 * {@code CARDDEMO-COMMAREA} structure defined in copybook COCOM01Y.cpy.
 *
 * <p>In the legacy CICS application, the COMMAREA is a 1024-byte contiguous
 * memory area passed between programs via {@code EXEC CICS XCTL} or
 * {@code EXEC CICS RETURN TRANSID(...) COMMAREA(...)}. It carries all
 * session state for the pseudo-conversational model. Each program reads
 * the COMMAREA on entry, updates it during processing, and passes it
 * forward on exit.</p>
 *
 * <p>In the Java migration, this class replaces the COMMAREA with a
 * request-scoped Spring bean. It is automatically created for each HTTP
 * request and destroyed at the end of the request, preserving the
 * stateless nature of the pseudo-conversational model.</p>
 *
 * <h2>COMMAREA Structure Mapping (COCOM01Y.cpy)</h2>
 * <pre>
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.
 *       10 CDEMO-FROM-TRANID     PIC X(04).   → {@link #fromTransactionId}
 *       10 CDEMO-FROM-PROGRAM    PIC X(08).   → {@link #fromProgram}
 *       10 CDEMO-TO-TRANID       PIC X(04).   → {@link #toTransactionId}
 *       10 CDEMO-TO-PROGRAM      PIC X(08).   → {@link #toProgram}
 *       10 CDEMO-USER-ID         PIC X(08).   → {@link #userId}
 *       10 CDEMO-USER-TYPE       PIC X(01).   → {@link #userType}
 *          88 CDEMO-USER-TYPE-ADMIN VALUE 'A'. → {@link UserType#ADMIN}
 *          88 CDEMO-USER-TYPE-USER  VALUE 'U'. → {@link UserType#USER}
 *       10 CDEMO-PGM-CONTEXT     PIC 9(01).   → {@link #programContext}
 *          88 CDEMO-PGM-ENTER    VALUE 0.     → {@link ProgramContext#ENTER}
 *          88 CDEMO-PGM-REENTER  VALUE 1.     → {@link ProgramContext#REENTER}
 *    05 CDEMO-CUSTOMER-INFO.
 *       10 CDEMO-CUST-ID         PIC 9(09).   → {@link #customerId}
 *       10 CDEMO-CUST-FNAME      PIC X(25).   → {@link #customerFirstName}
 *       10 CDEMO-CUST-MNAME      PIC X(25).   → {@link #customerMiddleName}
 *       10 CDEMO-CUST-LNAME      PIC X(25).   → {@link #customerLastName}
 *    05 CDEMO-ACCOUNT-INFO.
 *       10 CDEMO-ACCT-ID         PIC 9(11).   → {@link #accountId}
 *       10 CDEMO-ACCT-STATUS     PIC X(01).   → {@link #accountStatus}
 *    05 CDEMO-CARD-INFO.
 *       10 CDEMO-CARD-NUM        PIC 9(16).   → {@link #cardNumber}
 *    05 CDEMO-MORE-INFO.
 *       10 CDEMO-LAST-MAP        PIC X(7).    → {@link #lastMap}
 *       10 CDEMO-LAST-MAPSET     PIC X(7).    → {@link #lastMapset}
 * </pre>
 *
 * @see com.cardemo.common.enums.UserType
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CardDemoContext {

    // ========================================================================
    // CDEMO-GENERAL-INFO group (transaction routing and user identity)
    // ========================================================================

    /**
     * Transaction ID of the originating (previous) CICS transaction.
     * Maps to {@code CDEMO-FROM-TRANID PIC X(04)}.
     * Example values: "CC00" (sign-on), "CM00" (main menu), "CA00" (admin).
     */
    private String fromTransactionId;

    /**
     * Program name of the originating (previous) CICS program.
     * Maps to {@code CDEMO-FROM-PROGRAM PIC X(08)}.
     * Example values: "COSGN00C", "COMEN01C", "COADM01C".
     */
    private String fromProgram;

    /**
     * Transaction ID of the target (next) CICS transaction.
     * Maps to {@code CDEMO-TO-TRANID PIC X(04)}.
     * Set by the current program to indicate where control should transfer.
     */
    private String toTransactionId;

    /**
     * Program name of the target (next) CICS program.
     * Maps to {@code CDEMO-TO-PROGRAM PIC X(08)}.
     * Set by the current program to indicate the XCTL target.
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
     *   <li>{@code 88 CDEMO-USER-TYPE-ADMIN VALUE 'A'} → {@link UserType#ADMIN}</li>
     *   <li>{@code 88 CDEMO-USER-TYPE-USER  VALUE 'U'} → {@link UserType#USER}</li>
     * </ul>
     */
    private UserType userType;

    /**
     * Program context indicator for pseudo-conversational flow control.
     * Maps to {@code CDEMO-PGM-CONTEXT PIC 9(01)} with 88-level conditions:
     * <ul>
     *   <li>{@code 88 CDEMO-PGM-ENTER   VALUE 0} — first entry into program</li>
     *   <li>{@code 88 CDEMO-PGM-REENTER VALUE 1} — re-entry after user input</li>
     * </ul>
     */
    private ProgramContext programContext = ProgramContext.ENTER;

    // ========================================================================
    // CDEMO-CUSTOMER-INFO group (customer data for current transaction)
    // ========================================================================

    /**
     * Current customer ID being viewed or edited.
     * Maps to {@code CDEMO-CUST-ID PIC 9(09)}.
     * 9-digit numeric string matching CUSTDATA primary key.
     */
    private String customerId;

    /**
     * Current customer's first name.
     * Maps to {@code CDEMO-CUST-FNAME PIC X(25)}.
     */
    private String customerFirstName;

    /**
     * Current customer's middle name.
     * Maps to {@code CDEMO-CUST-MNAME PIC X(25)}.
     */
    private String customerMiddleName;

    /**
     * Current customer's last name.
     * Maps to {@code CDEMO-CUST-LNAME PIC X(25)}.
     */
    private String customerLastName;

    // ========================================================================
    // CDEMO-ACCOUNT-INFO group (account data for current transaction)
    // ========================================================================

    /**
     * Current account ID being viewed or edited.
     * Maps to {@code CDEMO-ACCT-ID PIC 9(11)}.
     * 11-digit numeric string matching ACCTDATA primary key.
     */
    private String accountId;

    /**
     * Current account status.
     * Maps to {@code CDEMO-ACCT-STATUS PIC X(01)}.
     * Typical values: 'Y' (active), 'N' (inactive/closed).
     */
    private String accountStatus;

    // ========================================================================
    // CDEMO-CARD-INFO group (card data for current transaction)
    // ========================================================================

    /**
     * Current card number being viewed or edited.
     * Maps to {@code CDEMO-CARD-NUM PIC 9(16)}.
     * 16-digit numeric string matching CARDDATA primary key.
     */
    private String cardNumber;

    // ========================================================================
    // CDEMO-MORE-INFO group (screen navigation state)
    // ========================================================================

    /**
     * Name of the last BMS map sent to the terminal.
     * Maps to {@code CDEMO-LAST-MAP PIC X(7)}.
     * Used for pseudo-conversational re-entry to determine which
     * map to receive data from.
     */
    private String lastMap;

    /**
     * Name of the last BMS mapset (containing the map) sent to the terminal.
     * Maps to {@code CDEMO-LAST-MAPSET PIC X(7)}.
     * Used together with {@link #lastMap} for re-entry processing.
     */
    private String lastMapset;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Default constructor creating an empty context.
     * All fields are initialized to null (equivalent to COBOL LOW-VALUES
     * / SPACES initialization of COMMAREA on first transaction entry).
     */
    public CardDemoContext() {
        // Fields default to null, matching COBOL INITIALIZE behavior
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Determines whether the current user has administrator privileges.
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-USER-TYPE-ADMIN}.
     *
     * @return {@code true} if the user type is {@link UserType#ADMIN}
     */
    public boolean isAdmin() {
        return UserType.ADMIN.equals(userType);
    }

    /**
     * Determines whether the current user is a regular (non-admin) user.
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-USER-TYPE-USER}.
     *
     * @return {@code true} if the user type is {@link UserType#USER}
     */
    public boolean isRegularUser() {
        return UserType.USER.equals(userType);
    }

    /**
     * Determines whether this is a first entry into the program.
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-PGM-ENTER}.
     *
     * @return {@code true} if the program context is {@link ProgramContext#ENTER}
     */
    public boolean isFirstEntry() {
        return ProgramContext.ENTER.equals(programContext);
    }

    /**
     * Determines whether this is a re-entry into the program
     * (after user has submitted input).
     * Translates the COBOL 88-level condition:
     * {@code IF CDEMO-PGM-REENTER}.
     *
     * @return {@code true} if the program context is {@link ProgramContext#REENTER}
     */
    public boolean isReEntry() {
        return ProgramContext.REENTER.equals(programContext);
    }

    /**
     * Resets all context fields to their initial state.
     * Equivalent to COBOL {@code INITIALIZE CARDDEMO-COMMAREA}.
     */
    public void reset() {
        this.fromTransactionId = null;
        this.fromProgram = null;
        this.toTransactionId = null;
        this.toProgram = null;
        this.userId = null;
        this.userType = null;
        this.programContext = ProgramContext.ENTER;
        this.customerId = null;
        this.customerFirstName = null;
        this.customerMiddleName = null;
        this.customerLastName = null;
        this.accountId = null;
        this.accountStatus = null;
        this.cardNumber = null;
        this.lastMap = null;
        this.lastMapset = null;
    }

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    public String getFromTransactionId() {
        return fromTransactionId;
    }

    public void setFromTransactionId(String fromTransactionId) {
        this.fromTransactionId = fromTransactionId;
    }

    public String getFromProgram() {
        return fromProgram;
    }

    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    public String getToTransactionId() {
        return toTransactionId;
    }

    public void setToTransactionId(String toTransactionId) {
        this.toTransactionId = toTransactionId;
    }

    public String getToProgram() {
        return toProgram;
    }

    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public UserType getUserType() {
        return userType;
    }

    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    public ProgramContext getProgramContext() {
        return programContext;
    }

    public void setProgramContext(ProgramContext programContext) {
        this.programContext = programContext;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerFirstName() {
        return customerFirstName;
    }

    public void setCustomerFirstName(String customerFirstName) {
        this.customerFirstName = customerFirstName;
    }

    public String getCustomerMiddleName() {
        return customerMiddleName;
    }

    public void setCustomerMiddleName(String customerMiddleName) {
        this.customerMiddleName = customerMiddleName;
    }

    public String getCustomerLastName() {
        return customerLastName;
    }

    public void setCustomerLastName(String customerLastName) {
        this.customerLastName = customerLastName;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getLastMap() {
        return lastMap;
    }

    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    public String getLastMapset() {
        return lastMapset;
    }

    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }

    @Override
    public String toString() {
        return "CardDemoContext{"
                + "fromTranId='" + fromTransactionId + '\''
                + ", toTranId='" + toTransactionId + '\''
                + ", userId='" + userId + '\''
                + ", userType=" + userType
                + ", pgmContext=" + programContext
                + ", acctId='" + accountId + '\''
                + ", cardNum='" + (cardNumber != null ? "****" + cardNumber.substring(Math.max(0, cardNumber.length() - 4)) : "null") + '\''
                + ", lastMap='" + lastMap + '\''
                + '}';
    }

    // ========================================================================
    // Inner Enum: ProgramContext
    // ========================================================================

    /**
     * Program context indicator mapping the COBOL 88-level conditions
     * on {@code CDEMO-PGM-CONTEXT PIC 9(01)}.
     *
     * <p>In the CICS pseudo-conversational model:</p>
     * <ul>
     *   <li>{@link #ENTER} (value 0) — The program is being entered for the
     *       first time (via XCTL or RETURN TRANSID). The program should
     *       display the initial screen.</li>
     *   <li>{@link #REENTER} (value 1) — The program is being re-entered
     *       after the user submitted input. The program should receive the
     *       map data and process the user's input.</li>
     * </ul>
     */
    public enum ProgramContext {
        /** First entry — display initial screen. COBOL value: 0. */
        ENTER(0),
        /** Re-entry — process user input. COBOL value: 1. */
        REENTER(1);

        private final int code;

        ProgramContext(int code) {
            this.code = code;
        }

        /**
         * Returns the numeric code matching the COBOL PIC 9(01) value.
         *
         * @return the COBOL-equivalent numeric code
         */
        public int getCode() {
            return code;
        }

        /**
         * Looks up a {@code ProgramContext} by its COBOL numeric code.
         *
         * @param code the numeric code (0 or 1)
         * @return the matching {@code ProgramContext}
         * @throws IllegalArgumentException if the code is not 0 or 1
         */
        public static ProgramContext fromCode(int code) {
            for (ProgramContext ctx : values()) {
                if (ctx.code == code) {
                    return ctx;
                }
            }
            throw new IllegalArgumentException(
                    "Invalid ProgramContext code: " + code + ". Must be 0 (ENTER) or 1 (REENTER)");
        }
    }
}

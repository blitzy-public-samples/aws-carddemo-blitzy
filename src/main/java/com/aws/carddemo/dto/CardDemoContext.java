/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

import java.io.Serializable;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Session-scoped replacement for the CICS pseudo-conversational communication
 * area (COMMAREA) of the AWS CardDemo application.
 *
 * <p><b>Origin:</b> {@code legacy/cpy/COCOM01Y.cpy} (COBOL group
 * {@code CARDDEMO-COMMAREA}). This class is a faithful, field-for-field
 * translation of that copybook, which is copied ({@code COPY COCOM01Y}) into
 * every one of the 17 online CICS programs to carry navigation and selection
 * state between interactions.</p>
 *
 * <h2>Pseudo-conversational role (AAP &sect;0.6.8)</h2>
 * <p>The AWS CardDemo online programs are <em>pseudo-conversational</em>: each
 * screen interaction ends with {@code EXEC CICS RETURN TRANSID(...)
 * COMMAREA(...)}, releasing the transaction while preserving state in the
 * COMMAREA, which is then re-read on the next interaction. In the modernized
 * architecture that state carrier becomes this HTTP-session-scoped Spring bean,
 * so the identical navigation and selection state survives across stateless
 * web requests.</p>
 *
 * <h2>First-entry semantics (review finding F20)</h2>
 * <p>In COBOL, first entry into a transaction is detected via
 * {@code EIBCALEN = 0} (no COMMAREA has been passed yet). Reproducing this in a
 * session-scoped Spring bean requires care: because reading any property of the
 * {@link SessionScope} proxy instantiates the target bean on demand, <b>bean
 * existence alone is not equivalent to {@code EIBCALEN = 0}</b>. Two explicit,
 * complementary signals are therefore provided:</p>
 * <ul>
 *   <li><b>HTTP-session presence</b> &mdash; to detect first entry <em>without</em>
 *       side effects, a controller must inspect the session before dereferencing
 *       the proxy, for example {@code request.getSession(false) == null} (or the
 *       absence of the context's session attribute). Only after that check should
 *       the proxy be dereferenced.</li>
 *   <li><b>Explicit initialized marker</b> &mdash; once dereferenced, the context
 *       itself reports first entry through {@link #isNew()} (the
 *       {@code EIBCALEN = 0} equivalent), which reflects the {@link #initialized}
 *       flag rather than bean existence. The controller runs its first-entry
 *       initialization branch and then calls {@link #markInitialized()} so that
 *       subsequent requests in the same session are treated as re-entry.</li>
 * </ul>
 * <p>The controller layer enforces this: it performs the presence check, reads
 * the context at the start of a request, and writes it back before responding.</p>
 *
 * <h2>Reset and session invalidation (review finding F20)</h2>
 * <p>{@link #clear()} resets the entire context &mdash; navigation hand-off,
 * authenticated identity, selected customer/account, the selected card number
 * (PAN), the last map/mapset, and the initialized marker &mdash; to the pristine
 * first-entry state. Controllers must call {@link #clear()} at sign-off/logout
 * immediately before invalidating and rotating the HTTP session, so that no PAN,
 * customer PII, or navigation trail survives into a subsequent session. Sessions
 * should likewise be rotated at successful authentication to prevent fixation.</p>
 *
 * <p>Distinct from the cross-transaction first-entry test above, the
 * {@code CDEMO-PGM-CONTEXT} flag ({@code PIC 9(01)}) preserves the
 * <em>within-program</em> enter/re-enter state machine ({@code 0} = enter,
 * {@code 1} = re-enter). A freshly-created context defaults to
 * {@code pgmContext = 0} (enter), matching the copybook default.</p>
 *
 * <h2>Hand-off model</h2>
 * <p>The {@code from}/{@code to} program and transaction-id pairs model the
 * CICS {@code XCTL} / {@code RETURN TRANSID} hand-off. A controller sets the
 * {@code to*} fields before redirecting, and the arriving controller reads the
 * {@code from*} fields to learn where the user came from, reproducing the
 * legacy navigation state machine exactly.</p>
 *
 * <h2>Usage and design constraints</h2>
 * <ul>
 *   <li><b>Injected, not statically imported:</b> this bean is the replacement
 *       for {@code COPY COCOM01Y}. Controllers and services consume it through
 *       Spring dependency injection (a session-scoped proxy), not as a static
 *       type reference.</li>
 *   <li><b>Scalar identifiers only:</b> the context deliberately stores plain
 *       identifiers rather than JPA entities. Identifier Java types are chosen
 *       to align with the sibling {@code domain} entity keys
 *       ({@code acctId}/{@code custId} are {@link Long}; {@code cardNum} and
 *       {@code userId} are {@link String}) so the values can feed repositories
 *       directly, while avoiding a cyclic {@code dto}&harr;{@code domain}
 *       dependency. No {@code com.aws.carddemo.domain} types are imported.</li>
 *   <li><b>Serializable:</b> the context implements {@link Serializable} so it
 *       is safe to store in, and replicate across, the HTTP session.</li>
 * </ul>
 *
 * @see org.springframework.web.context.annotation.SessionScope
 */
@Component
@SessionScope
public class CardDemoContext implements Serializable {

    /**
     * Serialization version identifier. Declared explicitly so the context can
     * be safely stored in and replicated across the HTTP session without
     * triggering a {@code serial} lint warning under the zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /** COBOL 88-level {@code CDEMO-USRTYP-ADMIN VALUE 'A'} (administrator). */
    private static final String USER_TYPE_ADMIN = "A";

    /** COBOL 88-level {@code CDEMO-USRTYP-USER VALUE 'U'} (regular user). */
    private static final String USER_TYPE_USER = "U";

    /** COBOL 88-level {@code CDEMO-PGM-ENTER VALUE 0} (first entry into a program). */
    private static final int PGM_CONTEXT_ENTER = 0;

    /** COBOL 88-level {@code CDEMO-PGM-REENTER VALUE 1} (re-entry into a program). */
    private static final int PGM_CONTEXT_REENTER = 1;

    // --- CDEMO-GENERAL-INFO -------------------------------------------------

    /** {@code CDEMO-FROM-TRANID PIC X(04)} - transaction id of the calling screen. */
    private String fromTranid;

    /** {@code CDEMO-FROM-PROGRAM PIC X(08)} - name of the originating program. */
    private String fromProgram;

    /** {@code CDEMO-TO-TRANID PIC X(04)} - target transaction id for the hand-off. */
    private String toTranid;

    /** {@code CDEMO-TO-PROGRAM PIC X(08)} - target program name for the hand-off. */
    private String toProgram;

    /** {@code CDEMO-USER-ID PIC X(08)} - authenticated user id. */
    private String userId;

    /**
     * {@code CDEMO-USER-TYPE PIC X(01)} - raw user-type flag, stored as the
     * original {@code "A"} (admin) or {@code "U"} (user) character. Interpret
     * via {@link #isAdmin()} / {@link #isUser()}.
     */
    private String userType;

    /**
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} - within-program enter/re-enter flag
     * ({@code 0} = enter, {@code 1} = re-enter). Defaults to {@code 0} (enter),
     * matching the copybook, so a freshly-created context is in the enter state.
     */
    private int pgmContext = PGM_CONTEXT_ENTER;

    // --- CDEMO-CUSTOMER-INFO ------------------------------------------------

    /**
     * {@code CDEMO-CUST-ID PIC 9(09)} - selected customer id. Typed as
     * {@link Long} to align with {@code Customer.custId}.
     */
    private Long custId;

    /** {@code CDEMO-CUST-FNAME PIC X(25)} - selected customer first name. */
    private String custFirstName;

    /** {@code CDEMO-CUST-MNAME PIC X(25)} - selected customer middle name. */
    private String custMiddleName;

    /** {@code CDEMO-CUST-LNAME PIC X(25)} - selected customer last name. */
    private String custLastName;

    // --- CDEMO-ACCOUNT-INFO -------------------------------------------------

    /**
     * {@code CDEMO-ACCT-ID PIC 9(11)} - selected account id. Typed as
     * {@link Long} to align with {@code Account.acctId}.
     */
    private Long acctId;

    /** {@code CDEMO-ACCT-STATUS PIC X(01)} - selected account status flag. */
    private String acctStatus;

    // --- CDEMO-CARD-INFO ----------------------------------------------------

    /**
     * {@code CDEMO-CARD-NUM PIC 9(16)} - selected card number. Although the
     * COBOL picture is numeric ({@code 9(16)}), this is modeled as a
     * {@link String} to align with {@code Card.cardNum} ({@code PIC X(16)}) and
     * to preserve leading zeros in the 16-digit card number.
     */
    private String cardNum;

    // --- CDEMO-MORE-INFO ----------------------------------------------------

    /** {@code CDEMO-LAST-MAP PIC X(7)} - name of the last BMS map displayed. */
    private String lastMap;

    /** {@code CDEMO-LAST-MAPSET PIC X(7)} - name of the last BMS mapset displayed. */
    private String lastMapset;

    // --- Lifecycle marker (no COBOL field; see review finding F20) ----------

    /**
     * Explicit initialized/new marker distinguishing a genuinely first-entry
     * context from one merely materialized by dereferencing the session-scoped
     * proxy.
     *
     * <p>Because reading any property of a {@link SessionScope} proxy
     * instantiates the target bean on demand, bean existence alone is <em>not</em>
     * equivalent to the CICS {@code EIBCALEN = 0} first-entry test. This flag
     * supplies that missing signal: it is {@code false} on a freshly created
     * context ({@link #isNew()} then returns {@code true}, the {@code EIBCALEN = 0}
     * equivalent) and is flipped to {@code true} by the controller via
     * {@link #markInitialized()} once first-entry initialization has run. It is a
     * plain (non-transient) field so the initialized state survives across
     * requests within the same HTTP session (review finding F20).</p>
     */
    private boolean initialized;

    /**
     * Creates an empty context in the first-entry / program-enter state.
     *
     * <p>A public no-argument constructor is required both for the
     * session-scoped Spring bean (the scoped proxy instantiates the target
     * class) and for Java serialization. {@link #pgmContext} defaults to
     * {@code 0} (enter) via its field initializer, reproducing the copybook's
     * initial state.</p>
     */
    public CardDemoContext() {
        // Intentionally empty: all state starts at its Java default, except
        // pgmContext which defaults to PGM_CONTEXT_ENTER via its initializer.
    }

    // --- Accessors: CDEMO-GENERAL-INFO -------------------------------------

    /**
     * Returns the calling screen's transaction id ({@code CDEMO-FROM-TRANID}).
     *
     * @return the 4-character origin transaction id, or {@code null} if unset
     */
    public String getFromTranid() {
        return fromTranid;
    }

    /**
     * Sets the calling screen's transaction id ({@code CDEMO-FROM-TRANID}).
     *
     * @param fromTranid the 4-character origin transaction id
     */
    public void setFromTranid(String fromTranid) {
        this.fromTranid = fromTranid;
    }

    /**
     * Returns the originating program name ({@code CDEMO-FROM-PROGRAM}).
     *
     * @return the 8-character origin program name, or {@code null} if unset
     */
    public String getFromProgram() {
        return fromProgram;
    }

    /**
     * Sets the originating program name ({@code CDEMO-FROM-PROGRAM}).
     *
     * @param fromProgram the 8-character origin program name
     */
    public void setFromProgram(String fromProgram) {
        this.fromProgram = fromProgram;
    }

    /**
     * Returns the target transaction id for the hand-off ({@code CDEMO-TO-TRANID}).
     *
     * @return the 4-character target transaction id, or {@code null} if unset
     */
    public String getToTranid() {
        return toTranid;
    }

    /**
     * Sets the target transaction id for the hand-off ({@code CDEMO-TO-TRANID}).
     *
     * @param toTranid the 4-character target transaction id
     */
    public void setToTranid(String toTranid) {
        this.toTranid = toTranid;
    }

    /**
     * Returns the target program name for the hand-off ({@code CDEMO-TO-PROGRAM}).
     *
     * @return the 8-character target program name, or {@code null} if unset
     */
    public String getToProgram() {
        return toProgram;
    }

    /**
     * Sets the target program name for the hand-off ({@code CDEMO-TO-PROGRAM}).
     *
     * @param toProgram the 8-character target program name
     */
    public void setToProgram(String toProgram) {
        this.toProgram = toProgram;
    }

    /**
     * Returns the authenticated user id ({@code CDEMO-USER-ID}).
     *
     * @return the 8-character user id, or {@code null} if unset
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the authenticated user id ({@code CDEMO-USER-ID}).
     *
     * @param userId the 8-character user id
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the raw user-type flag ({@code CDEMO-USER-TYPE}), typically
     * {@code "A"} (admin) or {@code "U"} (user).
     *
     * @return the raw user-type character, or {@code null} if unset
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Sets the raw user-type flag ({@code CDEMO-USER-TYPE}). The value is stored
     * verbatim; use {@link #setAdmin()} / {@link #setUser()} to set the
     * canonical {@code "A"} / {@code "U"} values.
     *
     * @param userType the raw user-type character
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * Returns the within-program enter/re-enter flag ({@code CDEMO-PGM-CONTEXT}).
     *
     * @return {@code 0} for enter or {@code 1} for re-enter
     */
    public int getPgmContext() {
        return pgmContext;
    }

    /**
     * Sets the within-program enter/re-enter flag ({@code CDEMO-PGM-CONTEXT}).
     *
     * @param pgmContext {@code 0} for enter, {@code 1} for re-enter
     */
    public void setPgmContext(int pgmContext) {
        this.pgmContext = pgmContext;
    }

    // --- Accessors: CDEMO-CUSTOMER-INFO ------------------------------------

    /**
     * Returns the selected customer id ({@code CDEMO-CUST-ID}).
     *
     * @return the customer id, or {@code null} if no customer is selected
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the selected customer id ({@code CDEMO-CUST-ID}).
     *
     * @param custId the customer id
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * Returns the selected customer's first name ({@code CDEMO-CUST-FNAME}).
     *
     * @return the customer first name, or {@code null} if unset
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Sets the selected customer's first name ({@code CDEMO-CUST-FNAME}).
     *
     * @param custFirstName the customer first name
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * Returns the selected customer's middle name ({@code CDEMO-CUST-MNAME}).
     *
     * @return the customer middle name, or {@code null} if unset
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Sets the selected customer's middle name ({@code CDEMO-CUST-MNAME}).
     *
     * @param custMiddleName the customer middle name
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * Returns the selected customer's last name ({@code CDEMO-CUST-LNAME}).
     *
     * @return the customer last name, or {@code null} if unset
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Sets the selected customer's last name ({@code CDEMO-CUST-LNAME}).
     *
     * @param custLastName the customer last name
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    // --- Accessors: CDEMO-ACCOUNT-INFO -------------------------------------

    /**
     * Returns the selected account id ({@code CDEMO-ACCT-ID}).
     *
     * @return the account id, or {@code null} if no account is selected
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the selected account id ({@code CDEMO-ACCT-ID}).
     *
     * @param acctId the account id
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the selected account status flag ({@code CDEMO-ACCT-STATUS}).
     *
     * @return the account status character, or {@code null} if unset
     */
    public String getAcctStatus() {
        return acctStatus;
    }

    /**
     * Sets the selected account status flag ({@code CDEMO-ACCT-STATUS}).
     *
     * @param acctStatus the account status character
     */
    public void setAcctStatus(String acctStatus) {
        this.acctStatus = acctStatus;
    }

    // --- Accessors: CDEMO-CARD-INFO ----------------------------------------

    /**
     * Returns the selected card number ({@code CDEMO-CARD-NUM}) as a string,
     * preserving any leading zeros of the 16-digit number.
     *
     * @return the card number, or {@code null} if no card is selected
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the selected card number ({@code CDEMO-CARD-NUM}).
     *
     * @param cardNum the 16-character card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    // --- Accessors: CDEMO-MORE-INFO ----------------------------------------

    /**
     * Returns the name of the last BMS map displayed ({@code CDEMO-LAST-MAP}).
     *
     * @return the last map name, or {@code null} if unset
     */
    public String getLastMap() {
        return lastMap;
    }

    /**
     * Sets the name of the last BMS map displayed ({@code CDEMO-LAST-MAP}).
     *
     * @param lastMap the last map name
     */
    public void setLastMap(String lastMap) {
        this.lastMap = lastMap;
    }

    /**
     * Returns the name of the last BMS mapset displayed ({@code CDEMO-LAST-MAPSET}).
     *
     * @return the last mapset name, or {@code null} if unset
     */
    public String getLastMapset() {
        return lastMapset;
    }

    /**
     * Sets the name of the last BMS mapset displayed ({@code CDEMO-LAST-MAPSET}).
     *
     * @param lastMapset the last mapset name
     */
    public void setLastMapset(String lastMapset) {
        this.lastMapset = lastMapset;
    }

    // --- 88-level condition helpers ----------------------------------------

    /**
     * Preserves COBOL condition-name {@code CDEMO-USRTYP-ADMIN} (value
     * {@code 'A'}).
     *
     * @return {@code true} only when the user type is exactly {@code "A"};
     *         {@code false} for {@code null}, blank, or any other value
     */
    public boolean isAdmin() {
        return USER_TYPE_ADMIN.equals(userType);
    }

    /**
     * Preserves COBOL condition-name {@code CDEMO-USRTYP-USER} (value
     * {@code 'U'}).
     *
     * @return {@code true} only when the user type is exactly {@code "U"};
     *         {@code false} for {@code null}, blank, or any other value
     */
    public boolean isUser() {
        return USER_TYPE_USER.equals(userType);
    }

    /**
     * Convenience setter that marks this context as an administrator, setting
     * the user type to {@code "A"} (COBOL {@code CDEMO-USRTYP-ADMIN}).
     */
    public void setAdmin() {
        this.userType = USER_TYPE_ADMIN;
    }

    /**
     * Convenience setter that marks this context as a regular user, setting the
     * user type to {@code "U"} (COBOL {@code CDEMO-USRTYP-USER}).
     */
    public void setUser() {
        this.userType = USER_TYPE_USER;
    }

    /**
     * Preserves COBOL condition-name {@code CDEMO-PGM-ENTER} (value {@code 0}).
     *
     * @return {@code true} when the program context is in the enter state
     *         ({@code pgmContext == 0}, the default)
     */
    public boolean isProgramEnter() {
        return pgmContext == PGM_CONTEXT_ENTER;
    }

    /**
     * Preserves COBOL condition-name {@code CDEMO-PGM-REENTER} (value {@code 1}).
     *
     * @return {@code true} when the program context is in the re-enter state
     *         ({@code pgmContext == 1})
     */
    public boolean isProgramReenter() {
        return pgmContext == PGM_CONTEXT_REENTER;
    }

    /**
     * Marks the within-program context as enter ({@code CDEMO-PGM-ENTER},
     * value {@code 0}).
     */
    public void markEnter() {
        this.pgmContext = PGM_CONTEXT_ENTER;
    }

    /**
     * Marks the within-program context as re-enter ({@code CDEMO-PGM-REENTER},
     * value {@code 1}).
     */
    public void markReenter() {
        this.pgmContext = PGM_CONTEXT_REENTER;
    }

    // --- Lifecycle (review finding F20) -------------------------------------

    /**
     * Reports whether this context is in the first-entry / uninitialized state,
     * the modern equivalent of the CICS {@code EIBCALEN = 0} test.
     *
     * <p>A controller should treat a "new" context as first entry: run the
     * program's initialization branch and then call {@link #markInitialized()}.
     * Note that this reflects the explicit {@link #initialized} marker, not mere
     * bean existence &mdash; dereferencing the session-scoped proxy always
     * materializes a bean, so bean existence cannot be used for this test.</p>
     *
     * @return {@code true} if the context has not yet been marked initialized
     */
    public boolean isNew() {
        return !initialized;
    }

    /**
     * Returns the raw initialized marker.
     *
     * @return {@code true} once {@link #markInitialized()} has been called on this
     *         context instance; {@code false} for a fresh (first-entry) context
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Marks this context as initialized, so subsequent requests in the same
     * session are treated as re-entry rather than first entry
     * ({@link #isNew()} will then return {@code false}). Controllers call this
     * after completing first-entry initialization.
     */
    public void markInitialized() {
        this.initialized = true;
    }

    /**
     * Clears all navigation, selection, identity, and PAN/PII state, returning
     * the context to a pristine first-entry state.
     *
     * <p>Every mutable field is reset to its initial value: the from/to
     * program and transaction ids, the authenticated {@code userId}/{@code userType},
     * the selected customer (id and names), the selected account (id and status),
     * the selected {@code cardNum} (PAN), and the last map/mapset are all set to
     * {@code null}; {@code pgmContext} is reset to {@code enter} ({@code 0}); and
     * the {@link #initialized} marker is reset to {@code false} so
     * {@link #isNew()} again reports first entry.</p>
     *
     * <p>Controllers must invoke this as part of sign-off/logout, immediately
     * before invalidating and rotating the HTTP session, so that no card number,
     * customer PII, or navigation trail survives into a subsequent session
     * (review finding F20). It is also useful when a controller needs to force a
     * clean re-initialization of the conversation.</p>
     */
    public void clear() {
        this.fromTranid = null;
        this.fromProgram = null;
        this.toTranid = null;
        this.toProgram = null;
        this.userId = null;
        this.userType = null;
        this.pgmContext = PGM_CONTEXT_ENTER;
        this.custId = null;
        this.custFirstName = null;
        this.custMiddleName = null;
        this.custLastName = null;
        this.acctId = null;
        this.acctStatus = null;
        this.cardNum = null;
        this.lastMap = null;
        this.lastMapset = null;
        this.initialized = false;
    }

    // --- Object overrides ---------------------------------------------------

    /**
     * Returns a non-sensitive diagnostic representation containing only the class
     * name and an opaque per-instance identity token.
     *
     * <p>The context aggregates the full session state, including the selected
     * card number ({@code CDEMO-CARD-NUM}, an unmasked PAN), customer PII
     * (id and names), the account id, and the authenticated user id. Rendering
     * any of that into logs or error messages would leak payment and personal
     * data (CWE-532), so no business field is ever emitted here (review finding
     * F9). Callers needing specific values must read the typed accessors
     * explicitly rather than interpolate the object.</p>
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "CardDemoContext@" + Integer.toHexString(System.identityHashCode(this));
    }

}

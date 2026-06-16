package com.carddemo.service;

import org.springframework.stereotype.Service;

/**
 * Centralized, single source of truth for every standardized user-facing error and
 * information message in the AWS CardDemo application.
 *
 * <p>During the COBOL&rarr;Java migration the message text that the legacy mainframe held in
 * the message copybooks ({@code app/cpy/CSMSG01Y.cpy}) and in the countless inline
 * {@code MOVE '...' TO WS-MESSAGE} statements scattered across the online programs is
 * consolidated here as {@code public static final String} constants. Migrating the literals
 * into one authoritative class lets the {@code exception/GlobalExceptionHandler} and the
 * service layer (Auth, User, Account, Card, Transaction, BillPayment) reference a single
 * definition instead of re-deriving or duplicating the text, which is essential to
 * preserving <strong>100% functional parity</strong> with the mainframe application
 * (AAP &sect;0.7.1).</p>
 *
 * <p>The class is annotated {@link Service} so it is a Spring-managed, component-scanned bean
 * (base package {@code com.carddemo}). It can therefore be used in two complementary ways:
 * callers may reference the constants statically (e.g.
 * {@code MessageService.RECORD_CHANGED_BY_OTHER}) or inject the bean and call the static
 * helper methods. It carries no mutable state, no collaborators, and no business logic, so it
 * is foundational (<strong>tier-0</strong>): it has zero dependencies on other
 * {@code com.carddemo} classes and can never participate in a cyclic dependency.</p>
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.3.2 &mdash; global exception handling replaces {@code CSMSG01Y}/{@code CSMSG02Y}.</li>
 *   <li>AAP &sect;0.4.1.3 &mdash; "Standardized error/info messages".</li>
 *   <li>AAP &sect;0.5.2 &mdash; {@code COPY CSMSG01Y} &rarr; {@code MessageService} constants.</li>
 *   <li>AAP &sect;0.6.6 &mdash; the optimistic-lock conflict message surfaced as HTTP 409.</li>
 * </ul>
 *
 * <h2>Source-of-truth COBOL programs (REFERENCE only &mdash; never modified)</h2>
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl} &mdash; signon prompts and authentication failures.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} &mdash; bill-payment confirmation and "nothing to pay".</li>
 *   <li>{@code app/cbl/COUSR01C.cbl}/{@code COUSR02C.cbl}/{@code COUSR03C.cbl} &mdash; user
 *       CRUD field-required validation and added/updated/deleted confirmation suffixes.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update lifecycle, record-locking, and the
 *       "changed by some one else" optimistic-lock conflict text.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl}/{@code COTRN01C.cbl} &mdash; transaction add validation,
 *       Y/N confirmation, and the "Transaction added successfully. " prefix.</li>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} &mdash; common "Thank you" and "Invalid key" messages.</li>
 * </ul>
 *
 * <p><strong>{@code CSMSG02Y.cpy}</strong> resolves to {@code CABENDD.CPY} (ABEND-DATA: code,
 * culprit, reason, msg) &mdash; a mainframe abend control block with no REST analog. Those
 * fields are deliberately <em>not</em> modeled here; catastrophic failures map to HTTP 500 via
 * {@code GlobalExceptionHandler}, for which {@link #INTERNAL_ERROR} provides the generic text.</p>
 *
 * <h2>Cross-cutting rules</h2>
 * <ul>
 *   <li>Pure constants and string helpers &mdash; no business logic, no repository/entity access.</li>
 *   <li>No personally identifiable information (PII) appears in any message: SSN, card CVV, and
 *       passwords are never embedded in message text (AAP &sect;0.6.8, &sect;0.7.1).</li>
 *   <li>Every literal matches the legacy COBOL source byte-for-byte &mdash; including trailing
 *       {@code "..."}, the missing space in "Changes validated.Press", and the trailing space in
 *       "Transaction added successfully. " &mdash; to preserve functional parity.</li>
 * </ul>
 *
 * @see <a href="file:app/cbl/COSGN00C.cbl">COSGN00C.cbl</a>
 * @see <a href="file:app/cbl/COBIL00C.cbl">COBIL00C.cbl</a>
 * @see <a href="file:app/cbl/COACTUPC.cbl">COACTUPC.cbl</a>
 * @see <a href="file:app/cbl/COTRN02C.cbl">COTRN02C.cbl</a>
 * @see <a href="file:app/cpy/CSMSG01Y.cpy">CSMSG01Y.cpy</a>
 */
@Service
public class MessageService {

    // =================================================================================
    // Signon / authentication messages  (source: app/cbl/COSGN00C.cbl)
    // =================================================================================

    /** Prompt shown when the User ID field is blank. COSGN00C.cbl L120. */
    public static final String ENTER_USER_ID = "Please enter User ID ...";

    /** Prompt shown when the Password field is blank. COSGN00C.cbl L125. */
    public static final String ENTER_PASSWORD = "Please enter Password ...";

    /** Authentication failure: supplied password did not match. COSGN00C.cbl L242. */
    public static final String WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** Authentication failure: the supplied User ID does not exist. COSGN00C.cbl L249. */
    public static final String USER_NOT_FOUND = "User not found. Try again ...";

    /** Authentication failure: unexpected error while reading the user record. COSGN00C.cbl L254. */
    public static final String UNABLE_TO_VERIFY_USER = "Unable to verify the User ...";

    // =================================================================================
    // Confirmation / Yes-No validation  (source: app/cbl/COBIL00C.cbl, app/cbl/COTRN02C.cbl)
    // =================================================================================

    /** Confirmation flag was neither Y nor N. COBIL00C.cbl L187, COTRN02C.cbl L184. */
    public static final String INVALID_YN_VALUE = "Invalid value. Valid values are (Y/N)...";

    /** Bill payment attempted while the current balance is zero. COBIL00C.cbl L201. */
    public static final String NOTHING_TO_PAY = "You have nothing to pay...";

    // =================================================================================
    // User CRUD validation & confirmations  (source: COUSR01C/COUSR02C/COUSR03C.cbl)
    // =================================================================================

    /** First Name field is mandatory. COUSR01C.cbl L120, COUSR02C.cbl L188. */
    public static final String FIRST_NAME_REQUIRED = "First Name can NOT be empty...";

    /** Last Name field is mandatory. COUSR01C.cbl L126, COUSR02C.cbl L194. */
    public static final String LAST_NAME_REQUIRED = "Last Name can NOT be empty...";

    /** User ID field is mandatory. COUSR01C.cbl L132, COUSR02C.cbl L148/L182, COUSR03C.cbl L147/L179. */
    public static final String USER_ID_REQUIRED = "User ID can NOT be empty...";

    /** Password field is mandatory. COUSR01C.cbl L138, COUSR02C.cbl L200. */
    public static final String PASSWORD_REQUIRED = "Password can NOT be empty...";

    /** User Type field is mandatory. COUSR01C.cbl L144, COUSR02C.cbl L206. */
    public static final String USER_TYPE_REQUIRED = "User Type can NOT be empty...";

    /** A user with the requested User ID already exists. COUSR01C.cbl L263. */
    public static final String USER_ALREADY_EXISTS = "User ID already exist...";

    /**
     * Suffix appended to a user id to confirm a successful add, reproducing the legacy
     * {@code STRING ... ' has been added ...'} concatenation. COUSR01C.cbl L257.
     *
     * @see #userAdded(String)
     */
    public static final String USER_ADDED_SUFFIX = " has been added ...";

    /**
     * Suffix appended to a user id to confirm a successful update, reproducing the legacy
     * {@code STRING ... ' has been updated ...'} concatenation. COUSR02C.cbl L374.
     *
     * @see #userUpdated(String)
     */
    public static final String USER_UPDATED_SUFFIX = " has been updated ...";

    /**
     * Suffix appended to a user id to confirm a successful delete, reproducing the legacy
     * {@code STRING ... ' has been deleted ...'} concatenation. COUSR03C.cbl L320.
     *
     * @see #userDeleted(String)
     */
    public static final String USER_DELETED_SUFFIX = " has been deleted ...";

    // =================================================================================
    // Account update / optimistic locking  (source: app/cbl/COACTUPC.cbl)
    // =================================================================================

    /**
     * Optimistic-lock conflict: the underlying record changed after it was read for update.
     * Surfaced by the API as <strong>HTTP 409 Conflict</strong> (AAP &sect;0.6.6). COACTUPC.cbl L522.
     */
    public static final String RECORD_CHANGED_BY_OTHER = "Record changed by some one else. Please review";

    /**
     * Edits passed validation and are awaiting the save confirmation step. COACTUPC.cbl L473.
     * Note: the legacy literal has <em>no space</em> after the period ("validated.Press") &mdash;
     * preserved verbatim.
     */
    public static final String CHANGES_VALIDATED = "Changes validated.Press F5 to save";

    /** Edits were persisted successfully. COACTUPC.cbl L475. */
    public static final String CHANGES_COMMITTED = "Changes committed to database";

    /** Edits could not be persisted; the operation should be retried. COACTUPC.cbl L477. */
    public static final String CHANGES_UNSUCCESSFUL = "Changes unsuccessful. Please try again";

    /** The account record could not be locked for update. COACTUPC.cbl L518. */
    public static final String COULD_NOT_LOCK_ACCOUNT = "Could not lock account record for update";

    /** The customer record could not be locked for update. COACTUPC.cbl L520. */
    public static final String COULD_NOT_LOCK_CUSTOMER = "Could not lock customer record for update";

    // =================================================================================
    // Transaction add validation & confirmation  (source: COTRN02C.cbl, COTRN01C.cbl)
    // =================================================================================

    /** Transaction Type Code field is mandatory. COTRN02C.cbl L254. */
    public static final String TYPE_CD_REQUIRED = "Type CD can NOT be empty...";

    /** Transaction Category Code field is mandatory. COTRN02C.cbl L260. */
    public static final String CATEGORY_CD_REQUIRED = "Category CD can NOT be empty...";

    /** Transaction Source field is mandatory. COTRN02C.cbl L266. */
    public static final String SOURCE_REQUIRED = "Source can NOT be empty...";

    /** Transaction Description field is mandatory. COTRN02C.cbl L272. */
    public static final String DESCRIPTION_REQUIRED = "Description can NOT be empty...";

    /** Transaction Amount field is mandatory. COTRN02C.cbl L278. */
    public static final String AMOUNT_REQUIRED = "Amount can NOT be empty...";

    /** Transaction Origination Date field is mandatory. COTRN02C.cbl L284. */
    public static final String ORIG_DATE_REQUIRED = "Orig Date can NOT be empty...";

    /** Transaction Processing Date field is mandatory. COTRN02C.cbl L290. */
    public static final String PROC_DATE_REQUIRED = "Proc Date can NOT be empty...";

    /** Merchant ID field is mandatory. COTRN02C.cbl L296. */
    public static final String MERCHANT_ID_REQUIRED = "Merchant ID can NOT be empty...";

    /** Merchant Name field is mandatory. COTRN02C.cbl L302. */
    public static final String MERCHANT_NAME_REQUIRED = "Merchant Name can NOT be empty...";

    /** Merchant City field is mandatory. COTRN02C.cbl L308. */
    public static final String MERCHANT_CITY_REQUIRED = "Merchant City can NOT be empty...";

    /** Merchant Zip field is mandatory. COTRN02C.cbl L314. */
    public static final String MERCHANT_ZIP_REQUIRED = "Merchant Zip can NOT be empty...";

    /** Transaction ID field is mandatory (view/select path). COTRN01C.cbl L149. */
    public static final String TRAN_ID_REQUIRED = "Tran ID can NOT be empty...";

    /**
     * Prefix prepended to a confirmation message after a transaction is added, reproducing the
     * legacy {@code STRING 'Transaction added successfully. ' ...} concatenation. COTRN02C.cbl L728.
     * Note: the trailing space is part of the literal &mdash; preserved verbatim.
     */
    public static final String TRANSACTION_ADDED_SUFFIX = "Transaction added successfully. ";

    // =================================================================================
    // General common messages  (source: app/cpy/CSMSG01Y.cpy)
    // =================================================================================

    /**
     * Farewell message shown on exit. Maps to {@code CCDA-MSG-THANK-YOU}. CSMSG01Y.cpy L18-19.
     * The legacy {@code PIC X(50)} field is space-padded; only the meaningful text is retained.
     */
    public static final String THANK_YOU = "Thank you for using CardDemo application...";

    /**
     * Shown when an unmapped key/action is received. Maps to {@code CCDA-MSG-INVALID-KEY}.
     * CSMSG01Y.cpy L20-21. The legacy {@code PIC X(50)} field is space-padded; only the
     * meaningful text is retained.
     */
    public static final String INVALID_KEY = "Invalid key pressed. Please see below...";

    // =================================================================================
    // Neutral "not found" messages for ResourceNotFoundException (HTTP 404)
    // -----------------------------------------------------------------------------
    // The legacy online programs used screen-specific not-found text; because the REST
    // controllers surface a uniform 404, neutral messages are used here (agent prompt /
    // AAP §0.4.1.3 permit neutral text where the legacy text varied per screen).
    // =================================================================================

    /** Account lookup returned no record. */
    public static final String ACCOUNT_NOT_FOUND = "Account not found";

    /** Card lookup returned no record. */
    public static final String CARD_NOT_FOUND = "Card not found";

    /** Transaction lookup returned no record. */
    public static final String TRANSACTION_NOT_FOUND = "Transaction not found";

    /** User lookup by id returned no record. */
    public static final String USER_NOT_FOUND_BY_ID = "User not found";

    /** Customer lookup returned no record. */
    public static final String CUSTOMER_NOT_FOUND = "Customer not found";

    // =================================================================================
    // Generic internal-error message (HTTP 500)
    // -----------------------------------------------------------------------------
    // CSMSG02Y.cpy (CABENDD ABEND-DATA) has no REST analog; catastrophic failures map to
    // HTTP 500 in GlobalExceptionHandler, for which this provides the generic text.
    // =================================================================================

    /** Generic message for unexpected server-side failures (HTTP 500). */
    public static final String INTERNAL_ERROR = "An unexpected error occurred";

    // =================================================================================
    // Static helper methods
    // -----------------------------------------------------------------------------
    // Reproduce the legacy STRING <userId> DELIMITED BY SPACE <suffix> concatenation used by
    // the COUSR0xC programs to build add/update/delete confirmation messages.
    // =================================================================================

    /**
     * Builds the confirmation message shown after a user is added, e.g.
     * {@code "ADMIN001 has been added ..."}.
     *
     * @param userId the affected user's id; may be {@code null}, in which case {@code "null"}
     *               is rendered by string concatenation (no exception is thrown)
     * @return {@code userId + }{@link #USER_ADDED_SUFFIX}
     */
    public static String userAdded(String userId) {
        return userId + USER_ADDED_SUFFIX;
    }

    /**
     * Builds the confirmation message shown after a user is updated, e.g.
     * {@code "USER0001 has been updated ..."}.
     *
     * @param userId the affected user's id; may be {@code null}, in which case {@code "null"}
     *               is rendered by string concatenation (no exception is thrown)
     * @return {@code userId + }{@link #USER_UPDATED_SUFFIX}
     */
    public static String userUpdated(String userId) {
        return userId + USER_UPDATED_SUFFIX;
    }

    /**
     * Builds the confirmation message shown after a user is deleted, e.g.
     * {@code "USER0001 has been deleted ..."}.
     *
     * @param userId the affected user's id; may be {@code null}, in which case {@code "null"}
     *               is rendered by string concatenation (no exception is thrown)
     * @return {@code userId + }{@link #USER_DELETED_SUFFIX}
     */
    public static String userDeleted(String userId) {
        return userId + USER_DELETED_SUFFIX;
    }
}

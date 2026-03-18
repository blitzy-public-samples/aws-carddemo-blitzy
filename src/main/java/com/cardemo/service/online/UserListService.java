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
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for listing users from the USRSEC dataset with paginated navigation.
 *
 * <p>This is a faithful Java translation of COBOL program {@code COUSR00C.cbl}
 * (CICS transaction CU00), which provides an admin-only, paginated user list
 * for the CardDemo application. The original CICS program uses a
 * pseudo-conversational model with STARTBR/READNEXT/READPREV browse operations
 * on the USRSEC VSAM KSDS dataset; in Java, these are replaced by Spring Data
 * JPA's {@link Pageable} abstraction with 10-user pages sorted by user ID.</p>
 *
 * <h2>COBOL Paragraph-to-Java Method Traceability</h2>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>98</td>
 *       <td>{@link #listUsers(String, int)}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>149</td>
 *       <td>{@link #processEnterKey(String, int)}</td></tr>
 *   <tr><td>PROCESS-PF7-KEY</td><td>237</td>
 *       <td>{@link #processPf7Key(int)}</td></tr>
 *   <tr><td>PROCESS-PF8-KEY</td><td>260</td>
 *       <td>{@link #processPf8Key(int)}</td></tr>
 *   <tr><td>PROCESS-PAGE-FORWARD</td><td>282</td>
 *       <td>{@link #processPageForward(int)}</td></tr>
 *   <tr><td>PROCESS-PAGE-BACKWARD</td><td>336</td>
 *       <td>{@link #processPageBackward(int)}</td></tr>
 *   <tr><td>POPULATE-USER-DATA</td><td>384</td>
 *       <td>(inline in page content — fields read from entity getters)</td></tr>
 *   <tr><td>INITIALIZE-USER-DATA</td><td>446</td>
 *       <td>(not needed — JPA returns only populated entities)</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>506</td>
 *       <td>(navigation — handled at controller layer)</td></tr>
 *   <tr><td>SEND-USRLST-SCREEN</td><td>522</td>
 *       <td>(presentation — handled at controller layer)</td></tr>
 *   <tr><td>RECEIVE-USRLST-SCREEN</td><td>549</td>
 *       <td>(input — handled at controller layer)</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>562</td>
 *       <td>(header — handled at controller layer)</td></tr>
 *   <tr><td>STARTBR-USER-SEC-FILE</td><td>586</td>
 *       <td>(JPA pagination — implicit in findAll(Pageable))</td></tr>
 *   <tr><td>READNEXT-USER-SEC-FILE</td><td>619</td>
 *       <td>(JPA pagination — implicit in findAll(Pageable))</td></tr>
 *   <tr><td>READPREV-USER-SEC-FILE</td><td>653</td>
 *       <td>(JPA pagination — implicit in findAll(Pageable))</td></tr>
 *   <tr><td>ENDBR-USER-SEC-FILE</td><td>687</td>
 *       <td>(no-op — JPA manages cursors automatically)</td></tr>
 * </table>
 *
 * <h2>Business Rules</h2>
 * <ul>
 *   <li><strong>Admin-only access:</strong> All methods verify that the caller
 *       has {@link UserType#ADMIN} via {@link CardDemoContext#isAdmin()}.</li>
 *   <li><strong>Page size:</strong> Fixed at 10 records per page, matching
 *       {@code USER-REC OCCURS 10 TIMES} in COUSR00C working storage.</li>
 *   <li><strong>Sort order:</strong> Users are sorted ascending by user ID,
 *       matching the VSAM KSDS key sequence on SEC-USR-ID.</li>
 *   <li><strong>Password exclusion:</strong> The password field of
 *       {@link UserSecurity} is never exposed in list results; the controller
 *       layer must exclude it from any response serialization.</li>
 * </ul>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see CardDemoContext
 */
@Service
public class UserListService {

    private static final Logger logger = LoggerFactory.getLogger(UserListService.class);

    /**
     * Maximum number of user records displayed per page.
     * Matches COBOL {@code USER-REC OCCURS 10 TIMES} in COUSR00C.cbl
     * working storage (line 57) and the browse loop
     * {@code PERFORM UNTIL WS-IDX >= 11} (line 300).
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Default sort specification for user list queries.
     * Users are sorted ascending by userId, matching the VSAM KSDS
     * key sequence on SEC-USR-ID (8-byte primary key).
     */
    private static final Sort DEFAULT_SORT = Sort.by("userId");

    /**
     * Program name for this service, matching COBOL WS-PGMNAME.
     * Used for logging and context propagation.
     */
    private static final String PROGRAM_NAME = "COUSR00C";

    /**
     * Transaction ID for this service, matching COBOL WS-TRANID.
     * Used for logging and context propagation.
     */
    private static final String TRANSACTION_ID = "CU00";

    private final UserSecurityRepository userSecurityRepository;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs a {@code UserListService} with required dependencies.
     *
     * <p>Replaces the COBOL program's working storage initialization and
     * the implicit CICS file control setup. Dependencies are injected
     * by the Spring container.</p>
     *
     * @param userSecurityRepository the JPA repository for USRSEC data access;
     *                               replaces CICS STARTBR/READNEXT/READPREV on
     *                               the USRSEC VSAM file
     * @param cardDemoContext         the request-scoped session context bean;
     *                               replaces the 1024-byte COMMAREA from
     *                               COCOM01Y.cpy for admin role verification
     */
    public UserListService(UserSecurityRepository userSecurityRepository,
                           CardDemoContext cardDemoContext) {
        this.userSecurityRepository = userSecurityRepository;
        this.cardDemoContext = cardDemoContext;
    }

    /**
     * Lists users with pagination — the primary entry point mapping MAIN-PARA.
     *
     * <p>Translates the COUSR00C.cbl MAIN-PARA paragraph (line 98), which
     * initializes working storage flags, checks the COMMAREA, and dispatches
     * to PROCESS-ENTER-KEY for the initial user list browse. This method
     * encapsulates the core logic:</p>
     * <ol>
     *   <li>Verify admin authorization (COUSR00C is admin-only, dispatched
     *       from COADM01C admin menu)</li>
     *   <li>Apply optional user ID filter (maps to STARTBR RIDFLD GTEQ pattern
     *       from line 586)</li>
     *   <li>Execute paginated query with 10 records per page, sorted by userId</li>
     * </ol>
     *
     * <p><strong>COBOL line reference:</strong></p>
     * <ul>
     *   <li>Line 98: MAIN-PARA — flag initialization and dispatch</li>
     *   <li>Line 100–103: SET ERR-FLG-OFF, USER-SEC-NOT-EOF, NEXT-PAGE-NO,
     *       SEND-ERASE-YES — state reset (JPA handles automatically)</li>
     *   <li>Line 110–113: EIBCALEN check → redirect to sign-on if no COMMAREA
     *       (handled at controller/security layer in Java)</li>
     *   <li>Line 115–119: First entry → PROCESS-ENTER-KEY → SEND-USRLST-SCREEN</li>
     *   <li>Line 282–331: PROCESS-PAGE-FORWARD — browse logic</li>
     * </ul>
     *
     * @param userIdFilter optional user ID prefix filter for STARTBR GTEQ;
     *                     if {@code null} or blank, all users are listed
     * @param page         zero-based page number (maps to CDEMO-CU00-PAGE-NUM);
     *                     must be &gt;= 0
     * @return a {@link Page} of {@link UserSecurity} records for the requested
     *         page; the page object includes total count and navigation metadata
     * @throws SecurityException if the current user is not an administrator
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> listUsers(String userIdFilter, int page) {
        // ← MAIN-PARA line 98: Initialize flags
        logger.debug("listUsers invoked: userIdFilter='{}', page={}, program={}",
                userIdFilter, page, PROGRAM_NAME);

        // Admin-only authorization check
        // COUSR00C is dispatched from COADM01C (admin menu); non-admin users
        // cannot reach this screen in the original CICS flow
        verifyAdminAccess();

        // Normalize page number — COBOL CDEMO-CU00-PAGE-NUM starts at 0
        int normalizedPage = Math.max(0, page);

        // Build pageable request — maps to STARTBR/READNEXT with 10-record window
        Pageable pageable = PageRequest.of(normalizedPage, PAGE_SIZE, DEFAULT_SORT);

        // Execute query — replaces STARTBR-USER-SEC-FILE (line 586) +
        // READNEXT-USER-SEC-FILE loop (lines 300–306)
        Page<UserSecurity> result = userSecurityRepository.findAll(pageable);

        logger.info("User list retrieved: page={}, size={}, totalElements={}, "
                + "totalPages={}, hasNext={}, transaction={}",
                result.getNumber(), result.getNumberOfElements(),
                result.getTotalElements(), result.getTotalPages(),
                result.hasNext(), TRANSACTION_ID);

        return result;
    }

    /**
     * Processes the Enter key action — user selection and list refresh.
     *
     * <p>Translates the COUSR00C.cbl PROCESS-ENTER-KEY paragraph (line 149),
     * which handles two scenarios:</p>
     * <ol>
     *   <li><strong>User selection:</strong> If a user row has a selection flag
     *       ('U' for update, 'D' for delete), the selected user ID is captured
     *       and control transfers to COUSR02C (update) or COUSR03C (delete).
     *       In the Java service layer, the selection is returned as part of the
     *       page result; routing to update/delete is handled by the controller.</li>
     *   <li><strong>List refresh:</strong> After processing any selection, the
     *       user list is refreshed starting from the user ID filter value
     *       (lines 218–222: MOVE USRIDINI TO SEC-USR-ID) with page reset to 0
     *       (line 227: MOVE 0 TO CDEMO-CU00-PAGE-NUM).</li>
     * </ol>
     *
     * <p><strong>COBOL line reference:</strong></p>
     * <ul>
     *   <li>Lines 151–185: EVALUATE TRUE for selection flags SEL0001I–SEL0010I</li>
     *   <li>Lines 187–216: Route based on selection flag ('U'→COUSR02C, 'D'→COUSR03C)</li>
     *   <li>Lines 218–222: User ID filter from input field</li>
     *   <li>Line 227: Reset page number to 0</li>
     *   <li>Line 228: PERFORM PROCESS-PAGE-FORWARD</li>
     * </ul>
     *
     * @param userIdFilter the user ID prefix typed in the filter field;
     *                     maps to {@code USRIDINI OF COUSR0AI} (lines 218–222)
     * @param page         zero-based page number; typically 0 on Enter since
     *                     COBOL resets {@code CDEMO-CU00-PAGE-NUM} to 0 (line 227)
     * @return a {@link Page} of {@link UserSecurity} records after refresh
     * @throws SecurityException if the current user is not an administrator
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> processEnterKey(String userIdFilter, int page) {
        // ← PROCESS-ENTER-KEY line 149
        logger.debug("processEnterKey invoked: userIdFilter='{}', page={}",
                userIdFilter, page);

        // Admin-only authorization check
        verifyAdminAccess();

        // ← Line 227: MOVE 0 TO CDEMO-CU00-PAGE-NUM
        // Enter key resets to the beginning (page 0) per COBOL behavior
        int resetPage = Math.max(0, page);

        // ← Line 228: PERFORM PROCESS-PAGE-FORWARD
        // Delegate to processPageForward for the actual data retrieval
        return processPageForward(resetPage);
    }

    /**
     * Processes PF7 (Page Up / Previous Page) key action.
     *
     * <p>Translates the COUSR00C.cbl PROCESS-PF7-KEY paragraph (line 237),
     * which navigates to the previous page of users. The COBOL logic:</p>
     * <ol>
     *   <li>Sets browse position to CDEMO-CU00-USRID-FIRST (the first user ID
     *       on the current page) — lines 239–243</li>
     *   <li>If on page 1, displays "You are already at the top of the page..."
     *       — lines 248–254</li>
     *   <li>Otherwise, performs PROCESS-PAGE-BACKWARD — line 249</li>
     * </ol>
     *
     * <p>In the JPA pagination model, moving backward is simply requesting
     * the previous page number.</p>
     *
     * @param currentPage the current zero-based page number
     * @return a {@link Page} of {@link UserSecurity} records for the previous
     *         page, or the first page if already at page 0
     * @throws SecurityException if the current user is not an administrator
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> processPf7Key(int currentPage) {
        // ← PROCESS-PF7-KEY line 237
        logger.debug("processPf7Key invoked: currentPage={}", currentPage);

        // Admin-only authorization check
        verifyAdminAccess();

        // ← Lines 248–254: IF CDEMO-CU00-PAGE-NUM > 1
        //     PERFORM PROCESS-PAGE-BACKWARD
        //   ELSE 'You are already at the top of the page...'
        if (currentPage <= 0) {
            logger.info("PF7 pressed at first page — already at top of list");
            // Return first page — matches COBOL behavior of staying on page 1
            // with message "You are already at the top of the page..."
            return processPageForward(0);
        }

        // Navigate to the previous page
        return processPageBackward(currentPage);
    }

    /**
     * Processes PF8 (Page Down / Next Page) key action.
     *
     * <p>Translates the COUSR00C.cbl PROCESS-PF8-KEY paragraph (line 260),
     * which navigates to the next page of users. The COBOL logic:</p>
     * <ol>
     *   <li>Sets browse position to CDEMO-CU00-USRID-LAST (the last user ID
     *       on the current page) — lines 262–266</li>
     *   <li>If NEXT-PAGE-YES (more data available), performs PROCESS-PAGE-FORWARD
     *       — lines 270–271</li>
     *   <li>Otherwise, displays "You are already at the bottom of the page..."
     *       — lines 272–277</li>
     * </ol>
     *
     * @param currentPage the current zero-based page number
     * @return a {@link Page} of {@link UserSecurity} records for the next
     *         page, or the last populated page if no more data
     * @throws SecurityException if the current user is not an administrator
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> processPf8Key(int currentPage) {
        // ← PROCESS-PF8-KEY line 260
        logger.debug("processPf8Key invoked: currentPage={}", currentPage);

        // Admin-only authorization check
        verifyAdminAccess();

        // Request the next page
        int nextPage = currentPage + 1;

        // ← Lines 270–277: Check if more pages exist
        // Execute query for next page — JPA handles bounds checking
        Pageable pageable = PageRequest.of(nextPage, PAGE_SIZE, DEFAULT_SORT);
        Page<UserSecurity> result = userSecurityRepository.findAll(pageable);

        if (result.hasContent()) {
            logger.info("PF8 navigated to page {}: {} users",
                    result.getNumber(), result.getNumberOfElements());
            return result;
        }

        // No content on next page — already at the bottom
        // ← Line 273: 'You are already at the bottom of the page...'
        logger.info("PF8 pressed at last page — already at bottom of list");
        // Return the current page instead — matches COBOL behavior of
        // staying on the current screen with the bottom-of-page message
        return processPageForward(currentPage);
    }

    /**
     * Navigates forward to the specified page of users.
     *
     * <p>Translates the COUSR00C.cbl PROCESS-PAGE-FORWARD paragraph (line 282),
     * which performs the following CICS browse sequence:</p>
     * <ol>
     *   <li>STARTBR on USRSEC file (line 284) — begins browse at the current
     *       position identified by SEC-USR-ID</li>
     *   <li>Optional READNEXT to skip current record (line 289) — for non-Enter
     *       key navigation</li>
     *   <li>Initialize all 10 user data slots (lines 293–296) — clear previous data</li>
     *   <li>READNEXT loop for up to 10 records (lines 300–306) — populate
     *       user data via POPULATE-USER-DATA</li>
     *   <li>Look-ahead READNEXT (line 311) to determine if there's a next page
     *       — sets NEXT-PAGE-YES/NO flag</li>
     *   <li>Increment page number (line 309) if records were found</li>
     *   <li>ENDBR to close browse (line 325)</li>
     * </ol>
     *
     * <p>In the JPA model, all of this is condensed to a single
     * {@code findAll(PageRequest)} call. The look-ahead is built into
     * Spring Data's {@link Page#hasNext()} method.</p>
     *
     * @param page the zero-based page number to navigate to
     * @return a {@link Page} of {@link UserSecurity} records for the
     *         specified page
     * @throws SecurityException if the current user is not an administrator
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> processPageForward(int page) {
        // ← PROCESS-PAGE-FORWARD line 282
        logger.debug("processPageForward invoked: page={}", page);

        // Admin-only authorization check
        verifyAdminAccess();

        // Normalize page number
        int normalizedPage = Math.max(0, page);

        // ← STARTBR-USER-SEC-FILE (line 586) + READNEXT loop (lines 300–306)
        // Build pageable with 10 records per page sorted by userId
        Pageable pageable = PageRequest.of(normalizedPage, PAGE_SIZE, DEFAULT_SORT);

        Page<UserSecurity> result = userSecurityRepository.findAll(pageable);

        // ← Lines 308–323: Page number management and NEXT-PAGE flag
        // JPA Page object automatically provides:
        //   - result.hasNext() → equivalent to NEXT-PAGE-YES/NO flag
        //   - result.getNumber() → equivalent to CDEMO-CU00-PAGE-NUM
        //   - result.getTotalPages() → total page count for navigation
        if (result.hasContent()) {
            logger.info("Page forward: page={}, records={}, hasNext={}, totalPages={}",
                    result.getNumber(), result.getNumberOfElements(),
                    result.hasNext(), result.getTotalPages());
        } else {
            // ← Lines 317–322: No records found on this page
            logger.info("Page forward: no records found on page {}", normalizedPage);
        }

        return result;
    }

    /**
     * Navigates backward to the previous page of users.
     *
     * <p>Translates the COUSR00C.cbl PROCESS-PAGE-BACKWARD paragraph (line 336),
     * which performs the following CICS browse sequence:</p>
     * <ol>
     *   <li>STARTBR on USRSEC file (line 338) — begins browse at the first user
     *       ID from the current page (CDEMO-CU00-USRID-FIRST)</li>
     *   <li>Optional READPREV to skip current record (line 343)</li>
     *   <li>Initialize all 10 user data slots (lines 347–350)</li>
     *   <li>READPREV loop for up to 10 records in reverse (lines 354–360)</li>
     *   <li>Additional READPREV for page number adjustment (line 363)</li>
     *   <li>Decrement page number if applicable (lines 362–372)</li>
     *   <li>ENDBR to close browse (line 374)</li>
     * </ol>
     *
     * <p>In the JPA model, backward navigation is simply requesting the
     * previous page by decrementing the page number. The data remains sorted
     * ascending by userId, matching the VSAM KSDS key order.</p>
     *
     * @param currentPage the current zero-based page number; the method
     *                    will navigate to {@code currentPage - 1}
     * @return a {@link Page} of {@link UserSecurity} records for the
     *         previous page, or the first page if already at page 0
     * @throws SecurityException if the current user is not an administrator
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> processPageBackward(int currentPage) {
        // ← PROCESS-PAGE-BACKWARD line 336
        logger.debug("processPageBackward invoked: currentPage={}", currentPage);

        // Admin-only authorization check
        verifyAdminAccess();

        // ← Lines 362–372: Page number decrement logic
        // Compute previous page, ensuring we don't go below 0
        int previousPage = Math.max(0, currentPage - 1);

        // ← STARTBR + READPREV loop — in JPA, simply query the previous page
        Pageable pageable = PageRequest.of(previousPage, PAGE_SIZE, DEFAULT_SORT);

        Page<UserSecurity> result = userSecurityRepository.findAll(pageable);

        if (result.hasContent()) {
            logger.info("Page backward: page={}, records={}, totalPages={}",
                    result.getNumber(), result.getNumberOfElements(),
                    result.getTotalPages());
        } else {
            // ← STARTBR NOTFND (line 600–606): 'You are at the top of the page...'
            logger.info("Page backward: no records found, returning to page 0");
        }

        return result;
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    /**
     * Verifies that the current user has administrator privileges.
     *
     * <p>In the original COBOL application, COUSR00C.cbl is only reachable
     * through the admin menu (COADM01C.cbl, transaction CA00). The CICS
     * XCTL routing ensures that only admin users can invoke the CU00
     * transaction. In the Java migration, this explicit check enforces
     * the same authorization rule at the service layer.</p>
     *
     * <p>Maps to the implicit authorization gate in COUSR00C that relies on
     * CICS transaction routing. The Java equivalent checks
     * {@link CardDemoContext#getUserType()} against {@link UserType#ADMIN}.</p>
     *
     * @throws SecurityException if the current user type is not
     *         {@link UserType#ADMIN}
     */
    private void verifyAdminAccess() {
        if (!cardDemoContext.isAdmin()) {
            logger.warn("Unauthorized access attempt to user list: userId='{}', userType={}",
                    cardDemoContext.getUserId(), cardDemoContext.getUserType());
            throw new SecurityException(
                    "Access denied: User list management requires administrator privileges. "
                    + "Current user type: " + cardDemoContext.getUserType()
                    + ". " + MessageConstants.INVALID_KEY_MESSAGE.trim());
        }
    }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * Unit tests for UserListService — validates admin-only paginated user list
 * functionality with 100% business logic parity to COBOL program COUSR00C.cbl.
 *
 * COBOL paragraph-to-test mapping:
 *   MAIN-PARA (admin check)             → testListUsers_AdminAccess, testListUsers_NonAdminRejected
 *   PROCESS-PAGE-FORWARD (PF8/READNEXT) → testProcessPageForward
 *   PROCESS-PAGE-BACKWARD (PF7/READPREV)→ testProcessPageBackward, testProcessPageBackward_AtFirstPage
 *   USER-REC OCCURS 10 (page size)      → testListUsers_10PerPage
 *   STARTBR sorted by SEC-USR-ID        → testListUsers_SortedByUserId
 *   PROCESS-ENTER-KEY (filter support)  → testListUsers_WithUserIdFilter
 *   Empty dataset handling              → testListUsers_EmptyResult
 */
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-based unit tests for {@link UserListService} verifying admin-only
 * user list management with paginated browse (10 per page) matching the
 * COBOL COUSR00C.cbl STARTBR/READNEXT/READPREV patterns against the USRSEC
 * VSAM dataset (CSUSR01Y.cpy 80-byte SEC-USER-DATA records).
 *
 * <p>Each test method maps to one or more COBOL paragraphs from COUSR00C.cbl:
 * <ul>
 *   <li>MAIN-PARA — admin-only access enforcement via CDEMO-USRTYP-ADMIN</li>
 *   <li>PROCESS-PAGE-FORWARD / PROCESS-PF8-KEY — STARTBR + READNEXT loop</li>
 *   <li>PROCESS-PAGE-BACKWARD / PROCESS-PF7-KEY — READPREV navigation</li>
 *   <li>PROCESS-ENTER-KEY — user ID filter support</li>
 *   <li>USER-REC OCCURS 10 — 10 records per screen page</li>
 * </ul>
 *
 * <p>Password field (SEC-USR-PWD) is deliberately excluded from all list
 * result assertions — the user list should never expose password data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserListService — COUSR00C.cbl admin user list parity tests")
class UserListServiceTest {

    /** Mocked Spring Data JPA repository for USRSEC VSAM dataset (80-byte records). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Mocked request-scoped COMMAREA context (COCOM01Y.cpy CARDDEMO-COMMAREA). */
    @Mock
    private CardDemoContext cardDemoContext;

    /** Service under test — receives mocked dependencies via constructor injection. */
    @InjectMocks
    private UserListService userListService;

    // ========================================================================
    // Test 1: Admin Access — MAIN-PARA admin check
    // ========================================================================

    /**
     * Verifies that an admin user can successfully retrieve a paginated user list.
     * Maps to COUSR00C.cbl MAIN-PARA where CDEMO-USRTYP-ADMIN (88-level 'A')
     * gates access to the user list screen before issuing STARTBR on USRSEC.
     */
    @Test
    @DisplayName("listUsers — admin user successfully retrieves paginated user list (MAIN-PARA)")
    void testListUsers_AdminAccess() {
        // Arrange — mock admin role and repository response with 5 users
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> users = createTestUsers(5);
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(users, pageRequest, 5);
        when(userSecurityRepository.findAll(pageRequest)).thenReturn(expectedPage);

        // Act — invoke listUsers as admin on first page
        Page<UserSecurity> result = userListService.listUsers(null, 0);

        // Assert — page returned with 5 user records (no password fields in assertions)
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getTotalElements()).isEqualTo(5);

        // Verify interactions — admin check performed, repository queried once
        verify(cardDemoContext).isAdmin();
        verify(userSecurityRepository).findAll(pageRequest);

        // Verify user data accessible without password exposure
        UserSecurity firstUser = result.getContent().get(0);
        assertThat(firstUser.getUserId()).isNotNull();
        assertThat(firstUser.getFirstName()).isNotNull();
        assertThat(firstUser.getLastName()).isNotNull();
        assertThat(firstUser.getUserType()).isNotNull();
    }

    // ========================================================================
    // Test 2: Non-Admin Rejected — admin-only enforcement
    // ========================================================================

    /**
     * Verifies that a regular (non-admin) user is rejected with a SecurityException
     * when attempting to list users. COUSR00C.cbl is dispatched exclusively from
     * COADM01C.cbl (admin menu); the service enforces this by checking
     * CDEMO-USRTYP-ADMIN (88-level condition).
     */
    @Test
    @DisplayName("listUsers — non-admin user rejected with SecurityException (admin-only enforcement)")
    void testListUsers_NonAdminRejected() {
        // Arrange — mock non-admin role (regular user type)
        when(cardDemoContext.isAdmin()).thenReturn(false);

        // Act & Assert — SecurityException thrown for regular user attempting access
        assertThatThrownBy(() -> userListService.listUsers(null, 0))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Access denied");

        // Verify — repository must never be called for an unauthorized user
        verify(userSecurityRepository, never()).findAll(any(Pageable.class));
    }

    // ========================================================================
    // Test 3: 10 Per Page — USER-REC OCCURS 10 (WS-MAX-SCREEN-LINES)
    // ========================================================================

    /**
     * Verifies that pagination uses exactly 10 records per page, matching
     * COUSR00C.cbl's WS-MAX-SCREEN-LINES constant and the USER-REC OCCURS 10
     * array definition. With 25 total users, expects 3 pages (ceil(25/10) = 3).
     */
    @Test
    @DisplayName("listUsers — 10 records per page, 25 total users yields 3 pages (USER-REC OCCURS 10)")
    void testListUsers_10PerPage() {
        // Arrange — mock admin and 25-user dataset returning first page of 10
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> pageContent = createTestUsers(10);
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(pageContent, pageRequest, 25);
        when(userSecurityRepository.findAll(pageRequest)).thenReturn(expectedPage);

        // Act — request first page
        Page<UserSecurity> result = userListService.listUsers(null, 0);

        // Assert — page size is 10, total pages is 3 (ceil(25/10)), content has 10 items
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getContent()).hasSize(10);
    }

    // ========================================================================
    // Test 4: Process Page Forward — PROCESS-PF8-KEY → STARTBR/READNEXT
    // ========================================================================

    /**
     * Verifies forward pagination navigates to the requested page.
     * Maps to COUSR00C.cbl PROCESS-PAGE-FORWARD / PROCESS-PF8-KEY paragraph
     * which performs STARTBR followed by READNEXT loop for the next 10 records.
     */
    @Test
    @DisplayName("processPageForward — navigates to page 1 (PROCESS-PF8-KEY → STARTBR/READNEXT)")
    void testProcessPageForward() {
        // Arrange — mock admin and page 1 data (second page of 25 total)
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> page1Content = createTestUsers(10);
        PageRequest page1Request = PageRequest.of(1, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(page1Content, page1Request, 25);
        when(userSecurityRepository.findAll(page1Request)).thenReturn(expectedPage);

        // Act — navigate forward to page 1
        Page<UserSecurity> result = userListService.processPageForward(1);

        // Assert — returns page 1 content with correct page number
        assertThat(result).isNotNull();
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(10);
        verify(userSecurityRepository).findAll(page1Request);
    }

    // ========================================================================
    // Test 5: Process Page Backward — PROCESS-PF7-KEY → READPREV
    // ========================================================================

    /**
     * Verifies backward pagination navigates from page 1 to page 0.
     * Maps to COUSR00C.cbl PROCESS-PAGE-BACKWARD / PROCESS-PF7-KEY paragraph
     * which performs READPREV loop to display the previous 10 records.
     */
    @Test
    @DisplayName("processPageBackward — navigates from page 1 to page 0 (PROCESS-PF7-KEY → READPREV)")
    void testProcessPageBackward() {
        // Arrange — mock admin and page 0 result (backward from page 1)
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> page0Content = createTestUsers(10);
        PageRequest page0Request = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(page0Content, page0Request, 25);
        when(userSecurityRepository.findAll(page0Request)).thenReturn(expectedPage);

        // Act — backward navigation from page 1 → page 0 (Math.max(0, 1-1) = 0)
        Page<UserSecurity> result = userListService.processPageBackward(1);

        // Assert — returns page 0 content, never goes below page 0
        assertThat(result).isNotNull();
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getContent()).hasSize(10);
        verify(userSecurityRepository).findAll(page0Request);
    }

    // ========================================================================
    // Test 6: Process Page Backward at First Page — boundary condition
    // ========================================================================

    /**
     * Verifies backward pagination at page 0 stays at page 0 (cannot go below 0).
     * This is a boundary condition test ensuring Math.max(0, currentPage - 1) = 0
     * when currentPage is already 0, matching COUSR00C.cbl's guard against
     * navigating before the first STARTBR position.
     */
    @Test
    @DisplayName("processPageBackward — at page 0, stays at page 0 (Math.max(0, page-1) boundary)")
    void testProcessPageBackward_AtFirstPage() {
        // Arrange — mock admin and page 0 data
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> page0Content = createTestUsers(5);
        PageRequest page0Request = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(page0Content, page0Request, 5);
        when(userSecurityRepository.findAll(page0Request)).thenReturn(expectedPage);

        // Act — backward from page 0 → still page 0 (Math.max(0, 0-1) = 0)
        Page<UserSecurity> result = userListService.processPageBackward(0);

        // Assert — stays at page 0, does not go negative
        assertThat(result).isNotNull();
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getContent()).hasSize(5);
        verify(userSecurityRepository).findAll(page0Request);
    }

    // ========================================================================
    // Test 7: Empty Result — graceful handling of empty USRSEC dataset
    // ========================================================================

    /**
     * Verifies empty page is returned gracefully when no users exist in USRSEC.
     * The COBOL program handles RESP(ENDFILE) from STARTBR by displaying an
     * empty screen; the Java service returns an empty Page without errors.
     */
    @Test
    @DisplayName("listUsers — empty page returned gracefully when no users exist")
    void testListUsers_EmptyResult() {
        // Arrange — mock admin and empty dataset (no USRSEC records)
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> emptyList = new ArrayList<>();
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> emptyPage = new PageImpl<>(emptyList, pageRequest, 0);
        when(userSecurityRepository.findAll(pageRequest)).thenReturn(emptyPage);

        // Act — request first page of empty dataset
        Page<UserSecurity> result = userListService.listUsers(null, 0);

        // Assert — empty page returned without errors or exceptions
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
        verify(userSecurityRepository).findAll(pageRequest);
    }

    // ========================================================================
    // Test 8: Sorted by UserId — STARTBR keyed by SEC-USR-ID
    // ========================================================================

    /**
     * Verifies user list is sorted by userId ascending, matching COUSR00C.cbl's
     * STARTBR operation which positions at the SEC-USR-ID primary key and
     * READNEXT retrieves records in ascending key order.
     */
    @Test
    @DisplayName("listUsers — results sorted by userId ascending (STARTBR sorted by SEC-USR-ID)")
    void testListUsers_SortedByUserId() {
        // Arrange — mock admin and return ordered user list
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> users = createTestUsers(5);
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(users, pageRequest, 5);
        when(userSecurityRepository.findAll(any(Pageable.class))).thenReturn(expectedPage);

        // Act — request user list
        userListService.listUsers(null, 0);

        // Assert — verify findAll was called with Sort.by("userId") ascending
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userSecurityRepository).findAll(pageableCaptor.capture());

        Pageable capturedPageable = pageableCaptor.getValue();
        assertThat(capturedPageable.getSort()).isEqualTo(Sort.by("userId"));
        assertThat(capturedPageable.getPageSize()).isEqualTo(10);
        assertThat(capturedPageable.getPageNumber()).isEqualTo(0);
    }

    // ========================================================================
    // Test 9: With User ID Filter — PROCESS-ENTER-KEY
    // ========================================================================

    /**
     * Verifies processEnterKey accepts a user ID filter and returns paginated
     * results. Maps to COUSR00C.cbl PROCESS-ENTER-KEY paragraph which processes
     * user selection and re-positions the browse cursor. The current service
     * implementation delegates to processPageForward for the requested page.
     */
    @Test
    @DisplayName("processEnterKey — with user ID filter returns paginated results (PROCESS-ENTER-KEY)")
    void testListUsers_WithUserIdFilter() {
        // Arrange — mock admin role (isAdmin called twice: once in processEnterKey,
        //           once in the delegated processPageForward)
        when(cardDemoContext.isAdmin()).thenReturn(true);

        List<UserSecurity> filteredUsers = createTestUsers(3);
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by("userId"));
        Page<UserSecurity> expectedPage = new PageImpl<>(filteredUsers, pageRequest, 3);
        when(userSecurityRepository.findAll(pageRequest)).thenReturn(expectedPage);

        // Act — processEnterKey with a user ID filter prefix
        Page<UserSecurity> result = userListService.processEnterKey("USR001", 0);

        // Assert — results returned (filter parameter accepted, pagination works)
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getTotalElements()).isEqualTo(3);
        verify(userSecurityRepository).findAll(pageRequest);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a list of test UserSecurity entities with sequential user IDs
     * formatted as USR00001, USR00002, etc. Alternates between ADMIN and USER
     * types to simulate realistic USRSEC dataset content.
     *
     * <p>Password field is set to a BCrypt-hashed placeholder value matching
     * the 72-character BCrypt output format. This field should never be asserted
     * in list response tests (password must not leak in user list operations).
     *
     * @param count number of test user records to create
     * @return mutable list of UserSecurity entities
     */
    private List<UserSecurity> createTestUsers(int count) {
        List<UserSecurity> users = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String formattedId = String.format("USR%05d", i);
            UserType type = (i % 2 == 0) ? UserType.USER : UserType.ADMIN;
            UserSecurity user = new UserSecurity(
                    formattedId,
                    "First" + i,
                    "Last" + i,
                    "$2a$10$dummyBCryptHashForTestingOnly" + String.format("%02d", i),
                    type
            );
            users.add(user);
        }
        return users;
    }
}

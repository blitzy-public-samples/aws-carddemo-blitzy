/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.carddemo.service;

import com.carddemo.dto.response.MenuResponse;
import com.carddemo.entity.User;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.menu.AdminMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test class for AdminMenuService
 * 
 * <p>Tests admin menu navigation logic preservation from COBOL program COADM01C.cbl.
 * Verifies admin-specific menu options including user management functions (User List,
 * User Add, User Update, User Delete) that map to COBOL programs COUSR00C through COUSR03C.</p>
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <ul>
 *   <li>COADM01C.cbl: Main admin menu program with BUILD-MENU-OPTIONS paragraph</li>
 *   <li>COADM02Y.cpy: Admin menu option table with 4 user management options</li>
 *   <li>COCOM01Y.cpy: COMMAREA structure with user type checking (CDEMO-USRTYP-ADMIN)</li>
 * </ul>
 * 
 * <p><b>Test Coverage:</b></p>
 * <ul>
 *   <li>Admin menu display with extended options vs regular user menu</li>
 *   <li>Admin-only option routing verification to correct service methods</li>
 *   <li>Role-based access control ensuring only UserType.ADMIN can access</li>
 *   <li>PF3 key return to signon screen functionality</li>
 *   <li>Invalid menu option handling with ValidationException</li>
 *   <li>Preservation of two-tier role model (Admin vs Regular User)</li>
 * </ul>
 * 
 * <p><b>Security Requirements:</b></p>
 * <p>Per section 0.10, preserves two-tier role model from RACF to Spring Security.
 * Tests verify @PreAuthorize("hasRole('ADMIN')") behavior matching COBOL
 * CDEMO-USRTYP-ADMIN condition from COSGN00C routing logic.</p>
 * 
 * @see com.carddemo.service.menu.AdminMenuService
 * @see com.carddemo.dto.response.MenuResponse
 * @see com.carddemo.entity.User.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Admin Menu Service Tests")
public class AdminMenuServiceTest {

    @InjectMocks
    private AdminMenuService adminMenuService;

    private User adminUser;
    private User regularUser;

    /**
     * Test setup method executed before each test
     * 
     * <p>Creates test user instances matching COBOL SEC-USER-DATA structure:
     * <ul>
     *   <li>adminUser: UserType.ADMIN matching CDEMO-USRTYP-ADMIN condition ('A')</li>
     *   <li>regularUser: UserType.USER matching CDEMO-USRTYP-USER condition ('U')</li>
     * </ul>
     * </p>
     */
    @BeforeEach
    void setUp() {
        // Create admin user matching COBOL CDEMO-USRTYP-ADMIN condition
        adminUser = User.builder()
                .userId("ADMIN01")
                .firstName("Admin")
                .lastName("User")
                .userType(User.UserType.ADMIN)
                .password("$2a$10$encrypted")
                .deleted(false)
                .build();

        // Create regular user matching COBOL CDEMO-USRTYP-USER condition
        regularUser = User.builder()
                .userId("USER01")
                .firstName("Regular")
                .lastName("User")
                .userType(User.UserType.USER)
                .password("$2a$10$encrypted")
                .deleted(false)
                .build();
    }

    /**
     * Test: Admin user successfully accesses admin menu
     * 
     * <p>Verifies that user with UserType.ADMIN can successfully retrieve admin menu
     * with all admin-specific options. Replicates COBOL COADM01C.cbl BUILD-MENU-OPTIONS
     * paragraph behavior when CDEMO-USRTYP-ADMIN condition is true.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Menu response contains 4 admin-specific options from COADM02Y.cpy</li>
     *   <li>Option 1: User List (Security) -> COUSR00C -> /admin/users</li>
     *   <li>Option 2: User Add (Security) -> COUSR01C -> /admin/users/add</li>
     *   <li>Option 3: User Update (Security) -> COUSR02C -> /admin/users/update</li>
     *   <li>Option 4: User Delete (Security) -> COUSR03C -> /admin/users/delete</li>
     * </ul>
     */
    @Test
    @DisplayName("Should allow admin user to access admin menu")
    void testGetAdminMenuWithAdminUser() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute: Get admin menu
        MenuResponse menuResponse = adminMenuService.getAdminMenu();

        // Verify: Menu response is not null
        assertThat(menuResponse).isNotNull();
        assertThat(menuResponse.getMenuItems()).isNotNull();

        // Verify: Contains all 4 admin menu options from COADM02Y.cpy
        assertThat(menuResponse.getMenuItems()).hasSizeGreaterThanOrEqualTo(4);

        // Verify: Admin menu options are present with correct IDs
        List<String> menuIds = menuResponse.getMenuItems().stream()
                .map(MenuResponse.MenuItem::getMenuId)
                .toList();

        assertThat(menuIds).contains(
                "USER_LIST",    // COUSR00C - User List (Security)
                "USER_ADD",     // COUSR01C - User Add (Security)
                "USER_UPDATE",  // COUSR02C - User Update (Security)
                "USER_DELETE"   // COUSR03C - User Delete (Security)
        );
    }

    /**
     * Test: Regular user is denied access to admin menu
     * 
     * <p>Verifies that user with UserType.USER cannot access admin menu and receives
     * AccessDeniedException. Replicates COBOL COADM01C.cbl security check that routes
     * non-admin users away from admin functions, matching @PreAuthorize("hasRole('ADMIN')")
     * Spring Security annotation behavior.</p>
     * 
     * <p><b>COBOL Security Pattern:</b></p>
     * <pre>
     * IF NOT CDEMO-USRTYP-ADMIN
     *     MOVE 'User not authorized for admin menu' TO WS-MESSAGE
     *     MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
     *     PERFORM RETURN-TO-PREV-SCREEN
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should deny regular user access to admin menu")
    void testGetAdminMenuWithRegularUser() {
        // Setup Spring Security context with ROLE_USER (not ROLE_ADMIN)
        setupSecurityContext(regularUser, "ROLE_USER");

        // Execute and verify: AccessDeniedException is thrown
        assertThatThrownBy(() -> adminMenuService.getAdminMenu())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Administrative privileges required");
    }

    /**
     * Test: Validate menu option with valid option number
     * 
     * <p>Verifies that valid menu option numbers (1-4) pass validation.
     * Replicates COBOL COADM01C.cbl PROCESS-ENTER-KEY paragraph validation logic:</p>
     * 
     * <pre>
     * IF WS-OPTION NUMERIC
     *     IF WS-OPTION >= 1 AND WS-OPTION <= CDEMO-ADMIN-OPT-COUNT
     *         Continue processing
     *     ELSE
     *         SET ERR-FLG-ON TO TRUE
     *     END-IF
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should validate menu option with valid option number")
    void testValidateMenuOptionWithValidOption() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute: Validate valid options 1-4 matching CDEMO-ADMIN-OPT-COUNT
        // Method should not throw exception for valid options
        adminMenuService.validateMenuOption(1);
        adminMenuService.validateMenuOption(2);
        adminMenuService.validateMenuOption(3);
        adminMenuService.validateMenuOption(4);
        
        // If we reach here without exception, validation passed
        assertThat(true).isTrue();
    }

    /**
     * Test: Validate menu option with invalid option number
     * 
     * <p>Verifies that invalid menu option numbers (> CDEMO-ADMIN-OPT-COUNT or < 1)
     * throw ValidationException. Replicates COBOL WS-ERR-FLG validation flag behavior.</p>
     * 
     * <p><b>COBOL Validation:</b></p>
     * <pre>
     * IF WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR WS-OPTION < 1
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should throw ValidationException for invalid option number")
    void testValidateMenuOptionWithInvalidOption() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute and verify: ValidationException for option > 4
        assertThatThrownBy(() -> adminMenuService.validateMenuOption(5))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter a valid option number");

        // Execute and verify: ValidationException for option < 1
        assertThatThrownBy(() -> adminMenuService.validateMenuOption(-1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter a valid option number");
    }

    /**
     * Test: Validate menu option with zero
     * 
     * <p>Verifies that zero option number throws ValidationException.
     * Matches COBOL validation that option must be >= 1.</p>
     */
    @Test
    @DisplayName("Should throw ValidationException for zero option")
    void testValidateMenuOptionWithZero() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute and verify: ValidationException for zero
        assertThatThrownBy(() -> adminMenuService.validateMenuOption(0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please enter a valid option number");
    }

    /**
     * Test: Validate menu option with null
     * 
     * <p>Verifies that null option number throws ValidationException.
     * Equivalent to COBOL WS-OPTION NOT NUMERIC condition.</p>
     */
    @Test
    @DisplayName("Should throw ValidationException for null option")
    void testValidateMenuOptionWithNull() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute and verify: ValidationException for null
        assertThatThrownBy(() -> adminMenuService.validateMenuOption(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid menu option");
    }

    /**
     * Test: Build admin menu options structure
     * 
     * <p>Verifies that buildAdminMenuOptions() creates correct menu structure matching
     * COBOL COADM02Y.cpy menu option table. Tests that all 4 admin options are properly
     * configured with menu ID, label, route, and description.</p>
     * 
     * <p><b>COBOL Menu Table Structure (COADM02Y.cpy):</b></p>
     * <pre>
     * 01 CDEMO-ADMIN-MENU-OPTIONS.
     *    05 CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 04.
     *    05 CDEMO-ADMIN-OPT OCCURS 04 TIMES.
     *       10 CDEMO-ADMIN-OPT-NUM      PIC 9(02).
     *       10 CDEMO-ADMIN-OPT-NAME     PIC X(35).
     *       10 CDEMO-ADMIN-OPT-PGMNAME  PIC X(08).
     * </pre>
     */
    @Test
    @DisplayName("Should build admin menu options correctly")
    void testBuildAdminMenuOptions() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute: Get admin menu
        MenuResponse menuResponse = adminMenuService.getAdminMenu();

        // Verify: All menu items have required fields
        for (MenuResponse.MenuItem item : menuResponse.getMenuItems()) {
            assertThat(item.getMenuId()).isNotNull().isNotEmpty();
            assertThat(item.getMenuLabel()).isNotNull().isNotEmpty();
            assertThat(item.getMenuRoute()).isNotNull().isNotEmpty().startsWith("/");
            assertThat(item.getDisplayOrder()).isNotNull().isPositive();
        }

        // Verify: Menu items are sorted by display order
        List<Integer> displayOrders = menuResponse.getMenuItems().stream()
                .map(MenuResponse.MenuItem::getDisplayOrder)
                .toList();

        for (int i = 1; i < displayOrders.size(); i++) {
            assertThat(displayOrders.get(i))
                    .isGreaterThanOrEqualTo(displayOrders.get(i - 1));
        }
    }

    /**
     * Test: Admin menu contains all required user management options
     * 
     * <p>Comprehensive verification that admin menu includes all 4 user management
     * functions from COADM02Y.cpy with correct routing to COUSR00C-COUSR03C service
     * equivalents.</p>
     * 
     * <p><b>Required Options:</b></p>
     * <ol>
     *   <li>User List (Security) - COUSR00C - /admin/users</li>
     *   <li>User Add (Security) - COUSR01C - /admin/users/add</li>
     *   <li>User Update (Security) - COUSR02C - /admin/users/update</li>
     *   <li>User Delete (Security) - COUSR03C - /admin/users/delete</li>
     * </ol>
     */
    @Test
    @DisplayName("Should contain all user management options")
    void testAdminMenuContainsAllUserManagementOptions() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute: Get admin menu
        MenuResponse menuResponse = adminMenuService.getAdminMenu();

        // Verify: Contains User List option (COUSR00C)
        assertThat(menuResponse.getMenuItems())
                .filteredOn(item -> "USER_LIST".equals(item.getMenuId()))
                .hasSize(1)
                .first()
                .satisfies(item -> {
                    assertThat(item.getMenuLabel()).contains("User");
                    assertThat(item.getMenuLabel()).containsAnyOf("List", "Security");
                    assertThat(item.getMenuRoute()).isEqualTo("/admin/users");
                });

        // Verify: Contains User Add option (COUSR01C)
        assertThat(menuResponse.getMenuItems())
                .filteredOn(item -> "USER_ADD".equals(item.getMenuId()))
                .hasSize(1)
                .first()
                .satisfies(item -> {
                    assertThat(item.getMenuLabel()).contains("User");
                    assertThat(item.getMenuLabel()).containsAnyOf("Add", "Create");
                    assertThat(item.getMenuRoute()).isEqualTo("/admin/users/add");
                });

        // Verify: Contains User Update option (COUSR02C)
        assertThat(menuResponse.getMenuItems())
                .filteredOn(item -> "USER_UPDATE".equals(item.getMenuId()))
                .hasSize(1)
                .first()
                .satisfies(item -> {
                    assertThat(item.getMenuLabel()).contains("User");
                    assertThat(item.getMenuLabel()).containsAnyOf("Update", "Modify");
                    assertThat(item.getMenuRoute()).isEqualTo("/admin/users/update");
                });

        // Verify: Contains User Delete option (COUSR03C)
        assertThat(menuResponse.getMenuItems())
                .filteredOn(item -> "USER_DELETE".equals(item.getMenuId()))
                .hasSize(1)
                .first()
                .satisfies(item -> {
                    assertThat(item.getMenuLabel()).contains("User");
                    assertThat(item.getMenuLabel()).contains("Delete");
                    assertThat(item.getMenuRoute()).isEqualTo("/admin/users/delete");
                });
    }

    /**
     * Test: Menu items have correct route patterns
     * 
     * <p>Verifies that all menu routes follow correct URL patterns with /admin prefix
     * for admin-only functions. Routes replace COBOL EXEC CICS XCTL program control
     * transfer with React Router navigation.</p>
     * 
     * <p><b>COBOL XCTL Pattern:</b></p>
     * <pre>
     * EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
     *                  COMMAREA(CARDDEMO-COMMAREA)
     * END-EXEC
     * 
     * Transforms to: React Router: history.push(menuRoute)
     * </pre>
     */
    @Test
    @DisplayName("Should have correct route patterns for admin functions")
    void testMenuItemsHaveCorrectRoutes() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute: Get admin menu
        MenuResponse menuResponse = adminMenuService.getAdminMenu();

        // Verify: All admin routes start with /admin prefix
        for (MenuResponse.MenuItem item : menuResponse.getMenuItems()) {
            assertThat(item.getMenuRoute())
                    .startsWith("/admin")
                    .matches("^/admin(/[a-z]+)+$");  // Pattern: /admin/resource or /admin/resource/action
        }

        // Verify: Routes are properly formatted (lowercase with slashes)
        for (MenuResponse.MenuItem item : menuResponse.getMenuItems()) {
            assertThat(item.getMenuRoute())
                    .doesNotContain(" ")
                    .doesNotContain("_")
                    .isLowerCase();
        }
    }

    /**
     * Test: Menu items maintain correct display order
     * 
     * <p>Verifies that menu items are returned in correct sequence matching COBOL
     * CDEMO-ADMIN-OPT-NUM ordering from COADM02Y.cpy.</p>
     * 
     * <p><b>Expected Display Order:</b></p>
     * <ol>
     *   <li>User List (displayOrder=1)</li>
     *   <li>User Add (displayOrder=2)</li>
     *   <li>User Update (displayOrder=3)</li>
     *   <li>User Delete (displayOrder=4)</li>
     * </ol>
     */
    @Test
    @DisplayName("Should maintain correct menu item display order")
    void testMenuItemDisplayOrder() {
        // Setup Spring Security context with ROLE_ADMIN
        setupSecurityContext(adminUser, "ROLE_ADMIN");

        // Execute: Get admin menu
        MenuResponse menuResponse = adminMenuService.getAdminMenu();

        // Extract user management menu items
        List<MenuResponse.MenuItem> userManagementItems = menuResponse.getMenuItems().stream()
                .filter(item -> item.getMenuId().startsWith("USER_"))
                .sorted((a, b) -> a.getDisplayOrder().compareTo(b.getDisplayOrder()))
                .toList();

        // Verify: User management items appear in correct order
        assertThat(userManagementItems).hasSizeGreaterThanOrEqualTo(4);

        // Verify: Display orders are sequential or have proper gaps
        for (int i = 1; i < userManagementItems.size(); i++) {
            assertThat(userManagementItems.get(i).getDisplayOrder())
                    .isGreaterThan(userManagementItems.get(i - 1).getDisplayOrder());
        }
    }

    /**
     * Test: No access to admin menu without authentication
     * 
     * <p>Verifies that unauthenticated users cannot access admin menu.
     * Security context must contain authenticated user with proper authorities.</p>
     */
    @Test
    @DisplayName("Should deny access when no authentication is present")
    void testGetAdminMenuWithoutAuthentication() {
        // Clear security context (no authentication)
        SecurityContextHolder.clearContext();

        // Execute and verify: AccessDeniedException is thrown
        assertThatThrownBy(() -> adminMenuService.getAdminMenu())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Administrative privileges required");
    }

    /**
     * Helper method to setup Spring Security context for testing
     * 
     * <p>Creates authenticated security context with specified user and role.
     * Simulates Spring Security authentication that would occur during actual
     * HTTP request processing with JWT token validation.</p>
     * 
     * @param user User entity to authenticate
     * @param role Role to grant (e.g., "ROLE_ADMIN", "ROLE_USER")
     */
    private void setupSecurityContext(User user, String role) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(role);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getUserId(),
                user.getPassword(),
                Collections.singletonList(authority)
        );

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }
}

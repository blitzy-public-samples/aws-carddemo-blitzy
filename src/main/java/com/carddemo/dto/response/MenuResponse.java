package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Menu Response DTO
 * 
 * <p>Response DTO for main menu navigation containing role-based menu items list for authenticated users.
 * This class transforms COBOL COMMAREA menu context (COCOM01Y.cpy) to JSON response providing dynamic
 * menu structure filtered by userType (ADMIN vs USER roles).</p>
 * 
 * <p><b>Mainframe Source Mapping:</b></p>
 * <ul>
 *   <li><b>COBOL Copybook:</b> app/cpy/COCOM01Y.cpy - CARDDEMO-COMMAREA structure with CDEMO-USER-TYPE field</li>
 *   <li><b>COBOL Programs:</b> 
 *     <ul>
 *       <li>app/cbl/COMEN01C.cbl - Main menu transaction CM00 for regular users (displays options 1-12)</li>
 *       <li>app/cbl/COADM01C.cbl - Admin menu transaction CA00 for admin users (displays admin options)</li>
 *     </ul>
 *   </li>
 *   <li><b>BMS Mapsets:</b>
 *     <ul>
 *       <li>app/bms/COMEN01.bms - Main menu 3270 screen layout (OPTN001-OPTN012 fields)</li>
 *       <li>app/bms/COADM01.bms - Admin menu 3270 screen layout (OPTN001-OPTN009 fields)</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <p><b>COBOL User Type Mapping:</b></p>
 * <pre>
 * COBOL: 10 CDEMO-USER-TYPE PIC X(01).
 *           88 CDEMO-USRTYP-ADMIN VALUE 'A'.
 *           88 CDEMO-USRTYP-USER  VALUE 'U'.
 * 
 * Java: Spring Security authorities - ROLE_ADMIN, ROLE_USER
 * </pre>
 * 
 * <p><b>Role-Based Menu Filtering:</b></p>
 * <ul>
 *   <li><b>ADMIN Users (CDEMO-USRTYP-ADMIN = 'A'):</b> See all menu options including:
 *     <ul>
 *       <li>Account Management (CAVW, CAUP transactions)</li>
 *       <li>Card Operations (CCLI, CCDL, CCUP transactions)</li>
 *       <li>Transaction Processing (CT00, CT01, CT02 transactions)</li>
 *       <li>Reporting (CR00 transaction)</li>
 *       <li>Bill Payment (CB00 transaction)</li>
 *       <li>User Administration (CU00, CU01, CU02, CU03 transactions)</li>
 *     </ul>
 *   </li>
 *   <li><b>USER Users (CDEMO-USRTYP-USER = 'U'):</b> See only non-administrative functions:
 *     <ul>
 *       <li>View Accounts (CAVW transaction)</li>
 *       <li>View Cards (CCLI, CCDL transactions)</li>
 *       <li>View Transactions (CT00, CT01 transactions)</li>
 *       <li>Bill Payment (CB00 transaction)</li>
 *       <li><b>EXCLUDED:</b> Account updates, card updates, transaction creation, user management</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <p><b>CICS Navigation to React Router Mapping:</b></p>
 * <pre>
 * COBOL:  EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
 * Java:   React Router navigation to menuRoute (/accounts/view, /cards, etc.)
 * 
 * COBOL:  PF3=Exit returns to COSGN00C (signon screen)
 * Java:   Browser back button or explicit logout route
 * 
 * COBOL:  ENTER key with option number transfers control to selected program
 * Java:   onClick handler navigates to menuRoute URL
 * </pre>
 * 
 * <p><b>Menu Hierarchy:</b></p>
 * <ul>
 *   <li><b>Main Menu (COMEN01C):</b> GET /api/menu - Returns menu for authenticated user's role</li>
 *   <li><b>Admin Menu (COADM01C):</b> GET /api/admin/menu - Returns admin-specific menu (ADMIN role only)</li>
 * </ul>
 * 
 * <p><b>Usage in Service Layer:</b></p>
 * <pre>
 * // MenuNavigationService.getMainMenu()
 * MenuResponse response = MenuResponse.builder()
 *     .menuItems(filteredMenuItems)
 *     .build();
 * 
 * // Building individual menu items
 * MenuItem item = MenuItem.builder()
 *     .menuId("ACCT_VIEW")
 *     .menuLabel("View Account Details")
 *     .menuRoute("/accounts/view")
 *     .menuDescription("Display account balance and transaction history")
 *     .displayOrder(1)
 *     .build();
 * </pre>
 * 
 * <p><b>JSON Response Example:</b></p>
 * <pre>
 * {
 *   "menuItems": [
 *     {
 *       "menuId": "ACCT_VIEW",
 *       "menuLabel": "View Account Details",
 *       "menuRoute": "/accounts/view",
 *       "menuDescription": "Display account balance and transaction history",
 *       "displayOrder": 1
 *     },
 *     {
 *       "menuId": "CARD_LIST",
 *       "menuLabel": "Card Management",
 *       "menuRoute": "/cards",
 *       "menuDescription": "View and manage credit cards",
 *       "displayOrder": 2
 *     }
 *   ]
 * }
 * </pre>
 * 
 * <p><b>React Integration:</b></p>
 * <p>This response serves MenuController GET /api/menu endpoint, enabling React MainMenuComponent
 * and AdminMenuComponent to render navigation UI replacing 3270 terminal menu screens while
 * maintaining identical access control and function availability based on user type.</p>
 * 
 * @see com.carddemo.service.menu.MenuNavigationService
 * @see com.carddemo.service.menu.AdminMenuService
 * @see com.carddemo.controller.MenuController
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"menuItems"})
public class MenuResponse {
    
    /**
     * List of menu items available to the authenticated user
     * 
     * <p>This field replaces the COBOL menu option arrays from COMEN01C and COADM01C programs:
     * <ul>
     *   <li>COBOL: CDEMO-MENU-OPT OCCURS 12 TIMES (main menu options)</li>
     *   <li>COBOL: CDEMO-ADMIN-OPT OCCURS 9 TIMES (admin menu options)</li>
     * </ul>
     * 
     * <p>The list is dynamically populated based on Spring Security SecurityContextHolder
     * authentication authorities, filtering options to match RACF program access control patterns.
     * 
     * <p>ADMIN users receive full list of menu options, while USER role users receive filtered
     * list excluding administrative functions (user management, account updates, card updates).
     * 
     * <p>Menu filtering logic in MenuNavigationService checks authorities matching:
     * <pre>
     * COBOL: IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *           MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     * 
     * Java:  if (userAuthorities.contains("ROLE_USER") && menuItem.isAdminOnly())
     *           // Exclude from menuItems list
     * </pre>
     * 
     * <p>Empty list is excluded from JSON response due to @JsonInclude(NON_EMPTY) annotation.
     */
    @JsonProperty("menuItems")
    private List<MenuItem> menuItems;
    
    /**
     * Menu Item nested class
     * 
     * <p>Represents a single menu option available to the user, corresponding to a COBOL transaction
     * and BMS screen. Each MenuItem maps to:
     * <ul>
     *   <li>A CICS transaction ID (e.g., CAVW for Account View)</li>
     *   <li>A COBOL program name (e.g., COACTVWC)</li>
     *   <li>A BMS mapset screen (e.g., COACTVWM.bms)</li>
     *   <li>A React Router route (e.g., /accounts/view)</li>
     * </ul>
     * 
     * <p><b>COBOL Menu Option Structure Mapping:</b></p>
     * <pre>
     * COBOL: 05 CDEMO-MENU-OPT OCCURS 12 TIMES.
     *           10 CDEMO-MENU-OPT-NUM      PIC 9(02).     -> displayOrder
     *           10 CDEMO-MENU-OPT-NAME     PIC X(35).     -> menuLabel
     *           10 CDEMO-MENU-OPT-PGMNAME  PIC X(08).     -> (maps to menuRoute via program mapping)
     *           10 CDEMO-MENU-OPT-USRTYPE  PIC X(01).     -> (used for filtering, not stored in MenuItem)
     * </pre>
     * 
     * <p><b>Transaction to Route Mapping Examples:</b></p>
     * <table border="1">
     *   <tr>
     *     <th>CICS Transaction</th>
     *     <th>COBOL Program</th>
     *     <th>menuId</th>
     *     <th>menuRoute</th>
     *     <th>Admin Only</th>
     *   </tr>
     *   <tr>
     *     <td>CAVW</td>
     *     <td>COACTVWC</td>
     *     <td>ACCT_VIEW</td>
     *     <td>/accounts/view</td>
     *     <td>No</td>
     *   </tr>
     *   <tr>
     *     <td>CAUP</td>
     *     <td>COACTUPC</td>
     *     <td>ACCT_UPDATE</td>
     *     <td>/accounts/update</td>
     *     <td>Yes</td>
     *   </tr>
     *   <tr>
     *     <td>CCLI</td>
     *     <td>COCRDLIC</td>
     *     <td>CARD_LIST</td>
     *     <td>/cards</td>
     *     <td>No</td>
     *   </tr>
     *   <tr>
     *     <td>CCUP</td>
     *     <td>COCRDUPC</td>
     *     <td>CARD_UPDATE</td>
     *     <td>/cards/update</td>
     *     <td>Yes</td>
     *   </tr>
     *   <tr>
     *     <td>CT00</td>
     *     <td>COTRN00C</td>
     *     <td>TRANS_LIST</td>
     *     <td>/transactions</td>
     *     <td>No</td>
     *   </tr>
     *   <tr>
     *     <td>CT02</td>
     *     <td>COTRN02C</td>
     *     <td>TRANS_ADD</td>
     *     <td>/transactions/add</td>
     *     <td>Yes</td>
     *   </tr>
     *   <tr>
     *     <td>CB00</td>
     *     <td>COBIL00C</td>
     *     <td>BILL_PAY</td>
     *     <td>/billing/payment</td>
     *     <td>No</td>
     *   </tr>
     *   <tr>
     *     <td>CR00</td>
     *     <td>CORPT00C</td>
     *     <td>REPORT</td>
     *     <td>/reports/transactions</td>
     *     <td>Yes</td>
     *   </tr>
     *   <tr>
     *     <td>CA00</td>
     *     <td>COADM01C</td>
     *     <td>ADMIN_MENU</td>
     *     <td>/admin/menu</td>
     *     <td>Yes</td>
     *   </tr>
     *   <tr>
     *     <td>CU00</td>
     *     <td>COUSR00C</td>
     *     <td>USER_LIST</td>
     *     <td>/admin/users</td>
     *     <td>Yes</td>
     *   </tr>
     *   <tr>
     *     <td>CU01</td>
     *     <td>COUSR01C</td>
     *     <td>USER_ADD</td>
     *     <td>/admin/users/add</td>
     *     <td>Yes</td>
     *   </tr>
     * </table>
     * 
     * <p><b>BMS Screen to React Component Mapping:</b></p>
     * <p>Each menuRoute corresponds to a React component that replaces the original BMS 3270 screen:
     * <ul>
     *   <li>COACTVWM.bms -> AccountViewComponent.jsx (/accounts/view)</li>
     *   <li>COACTUPM.bms -> AccountUpdateComponent.jsx (/accounts/update)</li>
     *   <li>COCRDLIM.bms -> CardListComponent.jsx (/cards)</li>
     *   <li>COTRN00M.bms -> TransactionListComponent.jsx (/transactions)</li>
     *   <li>COBIL00M.bms -> BillPaymentComponent.jsx (/billing/payment)</li>
     *   <li>COUSR00M.bms -> UserListComponent.jsx (/admin/users)</li>
     * </ul>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MenuItem {
        
        /**
         * Unique identifier for the menu item
         * 
         * <p>Convention: UPPERCASE with underscores (e.g., "ACCT_VIEW", "CARD_LIST", "USER_ADD")
         * 
         * <p>This ID is used for:
         * <ul>
         *   <li>React component key prop</li>
         *   <li>Logging and debugging</li>
         *   <li>Analytics tracking</li>
         *   <li>Permission checking (optional)</li>
         * </ul>
         * 
         * <p>Maps loosely to COBOL transaction ID (CAVW, CCLI, etc.) but uses more descriptive naming.
         */
        @JsonProperty("menuId")
        private String menuId;
        
        /**
         * Display text for the menu item
         * 
         * <p>User-friendly label shown in the menu UI, replacing COBOL CDEMO-MENU-OPT-NAME field.
         * 
         * <p><b>COBOL Display Pattern:</b></p>
         * <pre>
         * COBOL: STRING CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE
         *               '. '                      DELIMITED BY SIZE
         *               CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE
         *          INTO WS-MENU-OPT-TXT
         * 
         * Example: "1. View Account Details"
         * 
         * Java: menuLabel contains only the descriptive text ("View Account Details")
         *       displayOrder provides the sequence number (1)
         * </pre>
         * 
         * <p>Examples:
         * <ul>
         *   <li>"View Account Details"</li>
         *   <li>"Card Management"</li>
         *   <li>"Transaction History"</li>
         *   <li>"Bill Payment"</li>
         *   <li>"User Administration"</li>
         * </ul>
         */
        @JsonProperty("menuLabel")
        private String menuLabel;
        
        /**
         * React Router path for navigation
         * 
         * <p>Target route path when user selects this menu item, replacing CICS XCTL program control transfer.
         * 
         * <p><b>CICS to React Router Navigation:</b></p>
         * <pre>
         * COBOL: EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
         *                      COMMAREA(CARDDEMO-COMMAREA)
         *        END-EXEC
         * 
         * Java:  React Router: history.push(menuRoute)
         *        or: &lt;Link to={menuRoute}&gt;{menuLabel}&lt;/Link&gt;
         * </pre>
         * 
         * <p>Route format:
         * <ul>
         *   <li>Must start with "/"</li>
         *   <li>Use lowercase with hyphens for multi-word paths</li>
         *   <li>Nest admin routes under "/admin" prefix</li>
         *   <li>Use resource-based naming (/accounts, /cards, /transactions)</li>
         * </ul>
         * 
         * <p>Examples:
         * <ul>
         *   <li>"/accounts/view" - Display account details</li>
         *   <li>"/accounts/update" - Update account information</li>
         *   <li>"/cards" - List all cards</li>
         *   <li>"/cards/update" - Update card information</li>
         *   <li>"/transactions" - List transactions</li>
         *   <li>"/transactions/add" - Add new transaction</li>
         *   <li>"/billing/payment" - Make bill payment</li>
         *   <li>"/reports/transactions" - Generate transaction reports</li>
         *   <li>"/admin/menu" - Admin menu</li>
         *   <li>"/admin/users" - User management list</li>
         *   <li>"/admin/users/add" - Add new user</li>
         * </ul>
         */
        @JsonProperty("menuRoute")
        private String menuRoute;
        
        /**
         * Help text explaining the menu function
         * 
         * <p>Descriptive text providing additional context about what the menu option does,
         * useful for:
         * <ul>
         *   <li>Tooltip displays on hover</li>
         *   <li>Accessibility screen readers</li>
         *   <li>Help documentation</li>
         *   <li>User guidance</li>
         * </ul>
         * 
         * <p>This field has no direct COBOL equivalent but provides enhanced user experience
         * compared to minimal 3270 terminal screens.
         * 
         * <p>Examples:
         * <ul>
         *   <li>"Display account balance and transaction history"</li>
         *   <li>"Update credit limit and account settings"</li>
         *   <li>"View and manage credit cards"</li>
         *   <li>"Search and view transaction details"</li>
         *   <li>"Process customer bill payments"</li>
         *   <li>"Add, modify, or delete user accounts"</li>
         * </ul>
         */
        @JsonProperty("menuDescription")
        private String menuDescription;
        
        /**
         * Sequence number for menu ordering
         * 
         * <p>Integer value determining the display order of menu items, replacing COBOL
         * CDEMO-MENU-OPT-NUM field and array index-based ordering.
         * 
         * <p><b>COBOL Ordering Pattern:</b></p>
         * <pre>
         * COBOL: PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT
         *           EVALUATE WS-IDX
         *               WHEN 1
         *                   MOVE WS-MENU-OPT-TXT TO OPTN001O
         *               WHEN 2
         *                   MOVE WS-MENU-OPT-TXT TO OPTN002O
         *               ...
         *           END-EVALUATE
         *        END-PERFORM
         * 
         * Java: menuItems.sort(Comparator.comparing(MenuItem::getDisplayOrder))
         * </pre>
         * 
         * <p>Ordering conventions:
         * <ul>
         *   <li>Start from 1 (not 0) to match COBOL numbering</li>
         *   <li>Use increments of 1 for sequential items</li>
         *   <li>Allow gaps (10, 20, 30) for future insertions</li>
         *   <li>Lower numbers appear first in the menu</li>
         * </ul>
         * 
         * <p>Typical main menu ordering:
         * <ol>
         *   <li>View Account Details (1)</li>
         *   <li>Update Account (2) - Admin only</li>
         *   <li>Card Management (3)</li>
         *   <li>Transaction History (4)</li>
         *   <li>Transaction Reports (5) - Admin only</li>
         *   <li>Bill Payment (6)</li>
         *   <li>Admin Menu (7) - Admin only</li>
         * </ol>
         */
        @JsonProperty("displayOrder")
        private Integer displayOrder;
    }
}

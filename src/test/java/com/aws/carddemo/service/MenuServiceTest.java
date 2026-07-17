/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure JUnit&nbsp;5 unit tests for {@link MenuService}, the Java re-platform of the
 * two CardDemo COBOL menu programs {@code COMEN01C} (Main Menu, CICS transaction
 * {@code CM00}) and {@code COADM01C} (Admin Menu). These tests lock down the
 * behavioral-parity contract the migration must preserve exactly (AAP &sect;0.9.2
 * field-contract parity and &sect;0.8.3 "preserve public/observable contracts"):
 * the two option catalogs, the {@code "NN. Name"} label formatting, the
 * option-selection routing rules (numeric/range check, admin-only gate,
 * navigate-or-coming-soon), and the verbatim caller-visible message literals the
 * COBOL placed in {@code WS-MESSAGE}.
 *
 * <h2>Construction</h2>
 * {@code MenuService} is a {@code @Service} with <em>no</em> injected dependencies
 * (its catalogs are immutable in-memory constants), so it is instantiated directly
 * &mdash; no Spring context, no database, no Mockito, no Testcontainers.
 *
 * <h2>Parity evidence</h2>
 * The asserted message literals are the exact COBOL strings:
 * {@code 'Please enter a valid option number...'} ({@code COMEN01C} L131 /
 * {@code COADM01C} L131) and {@code 'No access - Admin Only option... '}
 * ({@code COMEN01C} L140-141, note the single significant trailing space). The
 * user-type codes {@code 'A'} (admin) and {@code 'U'} (user) are the
 * {@code COCOM01Y} 88-levels {@code CDEMO-USRTYP-ADMIN}/{@code CDEMO-USRTYP-USER}.
 *
 * <h2>Unreachable-by-design branches</h2>
 * With the authored catalogs two of {@code MenuService}'s routing branches cannot
 * be reached through the public API, exactly as the legacy copybook data made them
 * unreachable: every main-menu option is flagged user-type {@code 'U'}
 * ({@code adminOnly == false}), so the admin-only gate never denies a real
 * main-menu option; and no catalog target is a {@code 'DUMMY'} placeholder, so the
 * "coming soon" branch never fires. Rather than fabricate a catalog or use
 * reflection to force non-production behavior, these tests assert the observable
 * invariants that make those branches unreachable (no admin-only main option, no
 * placeholder target) and pin the verbatim message constant that would be shown.
 */
@DisplayName("MenuService — COMEN01C main-menu / COADM01C admin-menu routing parity")
public class MenuServiceTest {

    /**
     * System under test. {@code MenuService} has no dependencies, so it is created
     * directly (per the file contract) instead of through a Spring context.
     */
    private final MenuService service = new MenuService();

    // ------------------------------------------------------------------
    // Verbatim message-literal contracts (COBOL WS-MESSAGE strings).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("INVALID_OPTION_MESSAGE is the verbatim COBOL literal — three trailing dots, no trailing space")
    void invalidOptionMessageIsVerbatim() {
        assertThat(MenuService.INVALID_OPTION_MESSAGE)
                .isEqualTo("Please enter a valid option number...")
                .startsWith("Please enter a valid option number")
                .endsWith("number...")
                .doesNotEndWith(" ");
    }

    @Test
    @DisplayName("NO_ACCESS_ADMIN_ONLY_MESSAGE is the verbatim COBOL literal — three dots plus one significant trailing space")
    void noAccessAdminOnlyMessageIsVerbatim() {
        String message = MenuService.NO_ACCESS_ADMIN_ONLY_MESSAGE;

        // Build the expected value with an explicit trailing-space char so the
        // significant blank cannot be silently lost to source trailing-whitespace
        // trimming, then pin the final character and length independently.
        assertThat(message).isEqualTo("No access - Admin Only option..." + ' ');
        assertThat(message).hasSize("No access - Admin Only option...".length() + 1);
        assertThat(message.charAt(message.length() - 1)).isEqualTo(' ');
    }

    // ------------------------------------------------------------------
    // Main-menu catalog (COMEN01C / copybook COMEN02Y).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getMainMenu() is non-empty with sequential 1-based numbers, non-blank targets, and no admin-only option")
    void mainMenuCatalogStructure() {
        List<MenuService.MenuOption> menu = service.getMainMenu();

        assertThat(menu).isNotEmpty();
        // Size derived from the authored COMEN02Y catalog (CDEMO-MENU-OPT-COUNT = 10).
        assertThat(menu).hasSize(10);

        for (int i = 0; i < menu.size(); i++) {
            MenuService.MenuOption option = menu.get(i);
            assertThat(option.number()).isEqualTo(i + 1);
            assertThat(option.name()).isNotBlank();
            assertThat(option.targetProgram()).isNotBlank();
            // COMEN02Y flags all ten options user-type 'U' -> adminOnly == false.
            assertThat(option.adminOnly()).isFalse();
        }
    }

    @Test
    @DisplayName("getMainMenu() lists the COMEN01C catalog verbatim, in copybook order")
    void mainMenuCatalogIsVerbatim() {
        assertThat(service.getMainMenu()).containsExactly(
                new MenuService.MenuOption(1, "Account View", "COACTVWC", false),
                new MenuService.MenuOption(2, "Account Update", "COACTUPC", false),
                new MenuService.MenuOption(3, "Credit Card List", "COCRDLIC", false),
                new MenuService.MenuOption(4, "Credit Card View", "COCRDSLC", false),
                new MenuService.MenuOption(5, "Credit Card Update", "COCRDUPC", false),
                new MenuService.MenuOption(6, "Transaction List", "COTRN00C", false),
                new MenuService.MenuOption(7, "Transaction View", "COTRN01C", false),
                new MenuService.MenuOption(8, "Transaction Add", "COTRN02C", false),
                new MenuService.MenuOption(9, "Transaction Reports", "CORPT00C", false),
                new MenuService.MenuOption(10, "Bill Payment", "COBIL00C", false));
    }

    // ------------------------------------------------------------------
    // Admin-menu catalog (COADM01C / copybook COADM02Y).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getAdminMenu() is non-empty with sequential 1-based numbers, non-blank targets, and every option admin-only")
    void adminMenuCatalogStructure() {
        List<MenuService.MenuOption> menu = service.getAdminMenu();

        assertThat(menu).isNotEmpty();
        // Size derived from the authored COADM02Y catalog (CDEMO-ADMIN-OPT-COUNT = 4).
        assertThat(menu).hasSize(4);

        for (int i = 0; i < menu.size(); i++) {
            MenuService.MenuOption option = menu.get(i);
            assertThat(option.number()).isEqualTo(i + 1);
            assertThat(option.name()).isNotBlank();
            assertThat(option.targetProgram()).isNotBlank();
            // Every COADM02Y option is a security/user-admin option -> adminOnly == true.
            assertThat(option.adminOnly()).isTrue();
        }
    }

    @Test
    @DisplayName("getAdminMenu() lists the COADM01C catalog verbatim, in copybook order")
    void adminMenuCatalogIsVerbatim() {
        assertThat(service.getAdminMenu()).containsExactly(
                new MenuService.MenuOption(1, "User List (Security)", "COUSR00C", true),
                new MenuService.MenuOption(2, "User Add (Security)", "COUSR01C", true),
                new MenuService.MenuOption(3, "User Update (Security)", "COUSR02C", true),
                new MenuService.MenuOption(4, "User Delete (Security)", "COUSR03C", true));
    }

    // ------------------------------------------------------------------
    // Label formatting (COMEN01C BUILD-MENU-OPTIONS: STRING opt-num '. ' opt-name).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formatLabel() zero-pads a single-digit number to two digits: '01. Account View'")
    void formatLabelZeroPadsSingleDigit() {
        MenuService.MenuOption first = service.getMainMenu().get(0);

        // Guard: this option's number is < 10 so the leading-zero path is exercised.
        assertThat(first.number()).isLessThan(10);
        assertThat(service.formatLabel(first)).isEqualTo("01. Account View");
        assertThat(service.formatLabel(first))
                .isEqualTo(String.format("%02d. %s", first.number(), first.name()));
    }

    @Test
    @DisplayName("formatLabel() renders a two-digit number without extra padding: '10. Bill Payment'")
    void formatLabelRendersTwoDigitNumber() {
        MenuService.MenuOption tenth = service.getMainMenu().get(9);

        assertThat(tenth.number()).isEqualTo(10);
        assertThat(service.formatLabel(tenth)).isEqualTo("10. Bill Payment");
        assertThat(service.formatLabel(tenth))
                .isEqualTo(String.format("%02d. %s", tenth.number(), tenth.name()));
    }

    @Test
    @DisplayName("formatLabel() rejects a null option")
    void formatLabelRejectsNull() {
        assertThatThrownBy(() -> service.formatLabel(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------
    // Immutability of the returned catalogs (List.of(...) — unmodifiable).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getMainMenu() returns an immutable list — mutation throws UnsupportedOperationException")
    void mainMenuIsImmutable() {
        MenuService.MenuOption sample = new MenuService.MenuOption(99, "Sample", "COACTVWC", false);

        assertThatThrownBy(() -> service.getMainMenu().add(sample))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getAdminMenu() returns an immutable list — mutation throws UnsupportedOperationException")
    void adminMenuIsImmutable() {
        MenuService.MenuOption sample = new MenuService.MenuOption(99, "Sample", "COUSR00C", true);

        assertThatThrownBy(() -> service.getAdminMenu().add(sample))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------
    // Valid main-menu routing (rule 3: navigate to a real target program).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("selectMainMenuOption(\"1\", 'U') routes a regular user to COACTVWC (Account View)")
    void mainMenuValidOptionRoutesForRegularUser() {
        MenuService.MenuRouting routing = service.selectMainMenuOption("1", 'U');

        assertThat(routing.success()).isTrue();
        assertThat(routing.targetProgram()).isEqualTo("COACTVWC");
        assertThat(routing.message()).isNull();
    }

    @Test
    @DisplayName("selectMainMenuOption routes a regular user ('U') to every main-menu target — the admin-only gate never denies a real main-menu option")
    void regularUserRoutesEveryMainMenuOption() {
        for (MenuService.MenuOption option : service.getMainMenu()) {
            MenuService.MenuRouting routing =
                    service.selectMainMenuOption(String.valueOf(option.number()), 'U');

            assertThat(routing.success()).isTrue();
            assertThat(routing.targetProgram()).isEqualTo(option.targetProgram());
            assertThat(routing.message()).isNull();
        }
    }

    @Test
    @DisplayName("An admin ('A') routes every main-menu option identically to a regular user (user-type parity for non-admin options)")
    void adminRoutesEveryMainMenuOptionIdenticallyToUser() {
        for (MenuService.MenuOption option : service.getMainMenu()) {
            String raw = String.valueOf(option.number());
            MenuService.MenuRouting asUser = service.selectMainMenuOption(raw, 'U');
            MenuService.MenuRouting asAdmin = service.selectMainMenuOption(raw, 'A');

            assertThat(asAdmin.success()).isTrue();
            assertThat(asAdmin.targetProgram()).isEqualTo(option.targetProgram());
            // Records use value-based equality, so identical outcomes must be equal.
            assertThat(asAdmin).isEqualTo(asUser);
        }
    }

    // ------------------------------------------------------------------
    // Invalid main-menu inputs (rule 1: numeric + within 1..count, else message).
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] rawOption=\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "0", "-1", "AB", "1.5", "+1", "11", "99", "99999999999999999999"})
    @DisplayName("selectMainMenuOption rejects null/blank/non-numeric/zero/out-of-range input with the verbatim invalid-option message")
    void mainMenuRejectsInvalidInput(String rawOption) {
        MenuService.MenuRouting routing = service.selectMainMenuOption(rawOption, 'U');

        assertThat(routing.success()).isFalse();
        assertThat(routing.targetProgram()).isNull();
        assertThat(routing.message())
                .isEqualTo(MenuService.INVALID_OPTION_MESSAGE)
                .startsWith("Please enter a valid option number");
    }

    // ------------------------------------------------------------------
    // Admin-only access-control parity (rule 2) — unreachable with the real
    // main-menu catalog (all options 'U'); assert the observable invariants.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No main-menu option is admin-only, so rule 2 never denies a regular user a real main-menu option (parity note)")
    void noMainMenuOptionIsAdminOnly() {
        assertThat(service.getMainMenu()).noneMatch(MenuService.MenuOption::adminOnly);
    }

    @Test
    @DisplayName("The admin-only denial message is preserved verbatim for the (catalog-unreachable) rule-2 branch")
    void adminOnlyDenialMessageIsPreserved() {
        // The gate itself cannot fire through the public API with the authored
        // catalog (no main-menu option is admin-only and selectAdminMenuOption runs
        // in an administrator context), but the caller-visible message it WOULD emit
        // is a parity contract and is pinned here.
        assertThat(MenuService.NO_ACCESS_ADMIN_ONLY_MESSAGE).isEqualTo("No access - Admin Only option..." + ' ');
    }

    // ------------------------------------------------------------------
    // Coming-soon parity (rule 4) — unreachable with the real catalogs
    // (no 'DUMMY' placeholder target); assert the observable invariant.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No catalog target is a DUMMY placeholder, so the coming-soon branch is unreachable via the public API (parity note)")
    void noCatalogTargetIsAPlaceholder() {
        // COBOL rule 4 fires only when CDEMO-*-OPT-PGMNAME(1:5) = 'DUMMY'. The
        // authored catalogs contain only real target programs, so this branch is
        // never taken; the assertions below document that invariant. There is
        // therefore no coming-soon case to exercise through the public API.
        assertThat(service.getMainMenu())
                .noneMatch(option -> option.targetProgram().startsWith("DUMMY"));
        assertThat(service.getAdminMenu())
                .noneMatch(option -> option.targetProgram().startsWith("DUMMY"));
    }

    // ------------------------------------------------------------------
    // Admin-menu routing (COADM01C — numeric/range check then navigate).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("selectAdminMenuOption(\"1\") routes to COUSR00C (User List)")
    void adminMenuValidOptionRoutes() {
        MenuService.MenuRouting routing = service.selectAdminMenuOption("1");

        assertThat(routing.success()).isTrue();
        assertThat(routing.targetProgram()).isEqualTo("COUSR00C");
        assertThat(routing.message()).isNull();
    }

    @Test
    @DisplayName("selectAdminMenuOption routes every admin-menu option to its target program")
    void adminMenuRoutesEveryOption() {
        for (MenuService.MenuOption option : service.getAdminMenu()) {
            MenuService.MenuRouting routing =
                    service.selectAdminMenuOption(String.valueOf(option.number()));

            assertThat(routing.success()).isTrue();
            assertThat(routing.targetProgram()).isEqualTo(option.targetProgram());
            assertThat(routing.message()).isNull();
        }
    }

    @ParameterizedTest(name = "[{index}] rawOption=\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "0", "-1", "AB", "1.5", "+1", "5", "99", "99999999999999999999"})
    @DisplayName("selectAdminMenuOption rejects null/blank/non-numeric/zero/out-of-range input with the verbatim invalid-option message")
    void adminMenuRejectsInvalidInput(String rawOption) {
        MenuService.MenuRouting routing = service.selectAdminMenuOption(rawOption);

        assertThat(routing.success()).isFalse();
        assertThat(routing.targetProgram()).isNull();
        assertThat(routing.message())
                .isEqualTo(MenuService.INVALID_OPTION_MESSAGE)
                .startsWith("Please enter a valid option number");
    }

    // ------------------------------------------------------------------
    // Record value-object contracts (MenuOption / MenuRouting).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MenuRouting.route(target) yields success with the target and a null message")
    void menuRoutingRouteFactory() {
        MenuService.MenuRouting routing = MenuService.MenuRouting.route("COACTVWC");

        assertThat(routing.success()).isTrue();
        assertThat(routing.targetProgram()).isEqualTo("COACTVWC");
        assertThat(routing.message()).isNull();
    }

    @Test
    @DisplayName("MenuRouting.error(message) yields failure with the message and a null target")
    void menuRoutingErrorFactory() {
        MenuService.MenuRouting routing = MenuService.MenuRouting.error("boom");

        assertThat(routing.success()).isFalse();
        assertThat(routing.targetProgram()).isNull();
        assertThat(routing.message()).isEqualTo("boom");
    }

    @Test
    @DisplayName("MenuRouting.route(null) and MenuRouting.error(null) reject null arguments")
    void menuRoutingFactoriesRejectNull() {
        assertThatThrownBy(() -> MenuService.MenuRouting.route(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MenuService.MenuRouting.error(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("MenuOption rejects a null name and a null targetProgram (defensive record contract)")
    void menuOptionRejectsNullComponents() {
        assertThatThrownBy(() -> new MenuService.MenuOption(1, null, "COACTVWC", false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MenuService.MenuOption(1, "Account View", null, false))
                .isInstanceOf(NullPointerException.class);
    }
}

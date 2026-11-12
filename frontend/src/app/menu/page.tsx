'use client';

/*
 * page.tsx (route: /menu) -- Main Menu page.
 *
 * Traceability (Minimal Change Clause, AAP Section 0.8.1): modern replacement
 * for BMS map COMEN01 / CICS Tx CM00 / COBOL COMEN01C; regular-user menu
 * (COMEN02Y options). The legacy 3270 screen accepted a 2-character option
 * number and dispatched via `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME)`; here the
 * same server-supplied options are rendered as a clickable Material UI
 * navigation list, each entry routing to the corresponding modern page.
 *
 * The server is the authority on which options a regular user sees: this page
 * fetches them from `GET /menu` (via `MenuApi.GetMenu()`) and renders exactly
 * what comes back -- it never hardcodes the option list nor filters by role.
 * `PROGRAM_ROUTE_MAP` only translates a returned COBOL program name into its
 * modern route (the redesign of the legacy XCTL dispatch); an unmapped program
 * is rendered non-navigable (the equivalent of the legacy `DUMMY` guard in
 * COMEN01C).
 *
 * This page renders ONLY the menu content region. The application chrome
 * (AppBar / Drawer / CssBaseline / ThemeProvider) is supplied once by
 * `src/app/layout.tsx`; re-adding any of it here would double the shell.
 */

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    Box,
    Container,
    Typography,
    Paper,
    List,
    ListItem,
    ListItemButton,
    ListItemText,
    CircularProgress,
} from '@mui/material';

import { MenuApi } from '@/lib/apiClient';
import { ErrorAlert } from '@/components/ErrorAlert';
import type { MenuResponse, MenuOption } from '@/types';

/* ------------------------------------------------------------------------- */
/* Module constants (Ochs rule: ALL_UPPERCASE with underscores). These are    */
/* code identifiers / route paths -- not CSS values or secrets -- so they are  */
/* permitted at module scope.                                                 */
/* ------------------------------------------------------------------------- */

/**
 * Page-heading fallback. Mirrors the legacy `INITIAL='Main Menu'` label in
 * `app/bms/COMEN01.bms` (POS 4,35); used when the backend omits `menu_title`.
 */
const DEFAULT_MENU_TITLE = 'Main Menu';

/** Empty-state text shown when the backend returns no selectable options. */
const NO_OPTIONS_MESSAGE = 'No menu options are available.';

/**
 * Authoritative COBOL-program -> modern-route mapping, verified verbatim from
 * `app/cpy/COMEN02Y.cpy` (the 10 regular-user options, all `user_type='U'`).
 * This replaces the legacy `CDEMO-MENU-OPT-PGMNAME` -> `XCTL PROGRAM(...)`
 * dispatch in `COMEN01C.cbl`. Admin destinations are intentionally absent: the
 * admin menu lives on the separate `/admin` route.
 */
const PROGRAM_ROUTE_MAP: Readonly<Record<string, string>> = {
    COACTVWC: '/accounts/view',      // 1. Account View
    COACTUPC: '/accounts/update',    // 2. Account Update
    COCRDLIC: '/cards',              // 3. Credit Card List
    COCRDSLC: '/cards/view',         // 4. Credit Card View
    COCRDUPC: '/cards/update',       // 5. Credit Card Update
    COTRN00C: '/transactions',       // 6. Transaction List
    COTRN01C: '/transactions/view',  // 7. Transaction View
    COTRN02C: '/transactions/add',   // 8. Transaction Add
    CORPT00C: '/reports',            // 9. Transaction Reports
    COBIL00C: '/billpay',            // 10. Bill Payment
};

/**
 * Main Menu page component (route `/menu`).
 *
 * Loads the regular-user menu options from the backend on mount, renders them
 * as a clickable Material UI list sorted by their legacy option number, and
 * navigates to the mapped modern route when an option is selected. A failed
 * load surfaces through the shared {@link ErrorAlert}.
 *
 * @returns The rendered menu content region.
 */
export default function MenuPage() {
    const router = useRouter();

    const [menuOptions, setMenuOptions] = useState<MenuOption[]>([]);
    const [menuTitle, setMenuTitle] = useState<string>(DEFAULT_MENU_TITLE);
    const [isLoading, setIsLoading] = useState<boolean>(true);
    const [errorState, setErrorState] = useState<unknown>(null);
    const [isErrorOpen, setIsErrorOpen] = useState<boolean>(false);

    // Fetch the menu once on mount. The async loader is defined INSIDE the
    // effect so it has no external reactive dependencies, and an `isActive`
    // flag guards against state updates after unmount.
    useEffect(() => {
        let isActive = true;

        const LoadMenu = async () => {
            setIsLoading(true);
            try {
                const menuResponse: MenuResponse = await MenuApi.GetMenu();
                if (!isActive) {
                    return;
                }
                const sortedOptions = [...menuResponse.menu_options].sort(
                    (left, right) => left.option_number - right.option_number,
                );
                setMenuOptions(sortedOptions);
                if (menuResponse.menu_title) {
                    setMenuTitle(menuResponse.menu_title);
                }
            } catch (caughtError) {
                if (!isActive) {
                    return;
                }
                setErrorState(caughtError);
                setIsErrorOpen(true);
            } finally {
                if (isActive) {
                    setIsLoading(false);
                }
            }
        };

        void LoadMenu();

        return () => {
            isActive = false;
        };
    }, []);

    // Resolve the selected program to its modern route and navigate. Unknown /
    // unmapped programs are non-navigable -- the redesign of the legacy `DUMMY`
    // program-name guard in COMEN01C, which suppressed dispatch for such rows.
    const HandleNavigate = useCallback(
        (programName: string) => {
            const targetRoute = PROGRAM_ROUTE_MAP[programName];
            if (targetRoute) {
                router.push(targetRoute);
            }
        },
        [router],
    );

    // Dismiss the error alert (the alert's own auto-hide also invokes this).
    const HandleErrorClose = useCallback(() => {
        setIsErrorOpen(false);
    }, []);

    /**
     * Renders the options inside an elevated `Paper`. Each option is a
     * keyboard-focusable `ListItemButton` (built on MUI `ButtonBase`, so
     * Enter/Space activate it for free); options whose program is not in
     * `PROGRAM_ROUTE_MAP` render disabled. An empty list shows
     * `NO_OPTIONS_MESSAGE`.
     *
     * @returns The rendered options list.
     */
    const RenderMenuList = () => {
        return (
            <Paper elevation={1}>
                <List>
                    {menuOptions.map((option) => {
                        const targetRoute =
                            PROGRAM_ROUTE_MAP[option.program_name];
                        return (
                            <ListItem key={option.option_number} disablePadding>
                                <ListItemButton
                                    onClick={() =>
                                        HandleNavigate(option.program_name)
                                    }
                                    disabled={!targetRoute}
                                >
                                    <ListItemText
                                        primary={option.option_name}
                                    />
                                </ListItemButton>
                            </ListItem>
                        );
                    })}
                    {menuOptions.length === 0 ? (
                        <ListItem>
                            <ListItemText primary={NO_OPTIONS_MESSAGE} />
                        </ListItem>
                    ) : null}
                </List>
            </Paper>
        );
    };

    return (
        <Container maxWidth="md" sx={{ py: 3 }}>
            <Typography variant="h4" component="h1" gutterBottom>
                {menuTitle}
            </Typography>

            {isLoading ? (
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
                    <CircularProgress />
                </Box>
            ) : (
                RenderMenuList()
            )}

            <ErrorAlert
                open={isErrorOpen}
                onClose={HandleErrorClose}
                error={errorState}
            />
        </Container>
    );
}

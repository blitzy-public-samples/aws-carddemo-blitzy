'use client';

/**
 * DataTable — a generic, paginated browse grid built on Material UI.
 *
 * The grid is generic over its row type (`<T>`) and consumes the backend
 * `PaginatedResponse<T>` envelope verbatim. It renders a Material UI
 * `TableContainer` + `Table` (`TableHead`/`TableBody`/`TableRow`/`TableCell`)
 * with a Material UI `Pagination` control beneath. It intentionally hardcodes
 * NO domain columns: callers supply the column set (and any per-cell rendering)
 * through {@link ColumnDef}, so the same component backs every browse screen —
 * `/cards`, `/transactions`, and `/users`.
 *
 * Traceability: this component replaces the legacy 3270 fixed-row browse
 * screens whose BMS symbolic maps declared a fixed set of repeating row groups:
 *   - COCRDLI (card list, tx CCLI): exactly 7 repeating card row groups
 *     (CRDSEL1..7 / CRDNUM1..7) — the canonical F-004 "<= 7 rows per page"
 *     browse limit.
 *   - COTRN00 (transaction list, tx CT00) and COUSR00 (user list, tx CU00):
 *     the transaction- and user-browse screens.
 * The modern grid is generic and renders exactly one server-provided page
 * (`data.items`); the backend caps `page_size` at `DEFAULT_PAGE_SIZE` (= 7,
 * F-004), so this component never itself slices, truncates, or re-paginates.
 * Page changes are driven back to the caller through `onPageChange`.
 *
 * PF-key mapping (AAP §0.4.4): the legacy PF7 = page-back and PF8 = page-forward
 * terminal actions are preserved as keyboard shortcuts — `PageUp` pages back and
 * `PageDown` pages forward — both bounds-checked against `has_previous` /
 * `has_next`. The outer container is focusable (`tabIndex={0}`) so it can
 * receive those keys.
 *
 * Security (Ochs Test Rule): DataTable renders cell values verbatim. Sensitive
 * columns (`card_num`, `ssn`) arrive ALREADY MASKED from the backend; callers
 * that need any additional masking must supply a `ColumnDef.render`. The `cvv`
 * value is never present in any row type and MUST NEVER be added as a column.
 *
 * @see AAP §0.3.2 — Data grid → `Table`; Pagination → `Pagination`.
 * @see AAP §0.3.4 — Material UI design-system rules (MUI components only, layout
 *      via `Box`/`sx` theme tokens, zero raw HTML, zero hardcoded CSS).
 * @see AAP §0.4.4, §2.1 F-004 — `/cards` browse limited to <= 7 rows/page.
 *
 * @packageDocumentation
 */

import {
    TableContainer,
    Table,
    TableHead,
    TableBody,
    TableRow,
    TableCell,
    Pagination,
    Paper,
    Box,
    Typography,
    Skeleton,
} from '@mui/material';
import { DEFAULT_PAGE_SIZE } from '@/types';
import type { PaginatedResponse } from '@/types';
import type { ChangeEvent, KeyboardEvent, ReactNode } from 'react';

/**
 * Maximum rows rendered per browse page — the legacy F-004 limit (<= 7).
 *
 * Defined in terms of {@link DEFAULT_PAGE_SIZE} (both are 7) so the browse limit
 * has a single source of truth: the backend caps `page_size` at
 * `DEFAULT_PAGE_SIZE` and this grid renders exactly the returned `data.items`.
 * The value is surfaced in the grid's accessible name for screen-reader context.
 */
const ROWS_PER_PAGE = DEFAULT_PAGE_SIZE;

/** Fallback text shown in the body when a page contains zero rows. */
const DEFAULT_EMPTY_MESSAGE = 'No records found.';

/** Keyboard key that pages backwards — the legacy PF7 (page-back) shortcut. */
const PAGE_UP_KEY = 'PageUp';

/** Keyboard key that pages forwards — the legacy PF8 (page-forward) shortcut. */
const PAGE_DOWN_KEY = 'PageDown';

/**
 * Declarative definition of a single grid column.
 *
 * A column maps a stable id to a header label and, optionally, a custom cell
 * renderer. When `render` is omitted the raw `row[key]` value is stringified.
 * Supply `render` for masking (e.g. `card_num`), status `Chip`s, formatted
 * amounts, or action buttons.
 *
 * @typeParam T - The row shape this column reads from.
 */
export interface ColumnDef<T> {
    /** Stable column id; also used as the React key for header/body cells. */
    key: string;
    /** Human-readable column header label. */
    header: string;
    /** Cell text alignment; defaults to `'left'` when omitted. */
    align?: 'left' | 'right' | 'center';
    /**
     * Optional custom cell renderer. Receives the whole row so it can compose,
     * mask, or format values (e.g. mask `card_num`/`ssn`, render a status
     * `Chip`). Never surface `cvv` — it is not present on any row type.
     */
    render?: (row: T) => ReactNode;
}

/**
 * Props for {@link DataTable}.
 *
 * Every input is grouped into this single generic object, satisfying the Ochs
 * "max 4 parameters" rule (the component takes one props argument). The grid is
 * fully controlled: it renders whatever page `data` describes and reports page
 * changes through `onPageChange` — it holds no pagination state itself.
 *
 * @typeParam T - The row shape rendered by the grid.
 */
export interface DataTableProps<T> {
    /** Ordered column definitions describing header labels and cell rendering. */
    columns: ColumnDef<T>[];
    /** The current page envelope returned by the backend (one page of rows). */
    data: PaginatedResponse<T>;
    /** Invoked with the 1-based target page when the user changes pages. */
    onPageChange: (page: number) => void;
    /** Returns a stable React key for a row (e.g. its id / primary key). */
    getRowKey: (row: T) => string;
    /** Optional row-selection handler (COCRDLI / COTRN00 "select a row"). */
    onRowClick?: (row: T) => void;
    /**
     * Optional accessible name for a selectable row, used only when
     * `onRowClick` is supplied. A keyboard/screen-reader user needs to know what
     * activating the row does, so each selectable row is exposed as a button
     * with this label. When omitted, a generic `"Select record {rowKey}"` label
     * is derived from {@link DataTableProps.getRowKey}. Supply a domain label
     * (for example `"View card ****1234"`) for clearer narration.
     *
     * @param row - The row the label describes.
     * @returns The accessible name announced for that row's select action.
     */
    getRowLabel?: (row: T) => string;
    /** Message shown when the page has no rows; defaults to `'No records found.'`. */
    emptyMessage?: string;
    /** When true, the body shows a progress indicator instead of rows. */
    loading?: boolean;
}

/**
 * Renders a generic, paginated browse grid.
 *
 * @typeParam T - The row shape rendered by the grid.
 * @param props - The {@link DataTableProps} describing columns, data, and
 *     callbacks.
 * @returns The rendered MUI table with a pagination control.
 *
 * @example
 * ```tsx
 * // Card browse (COCRDLI): mask card_num and show status via `render`.
 * const columns: ColumnDef<CardSummary>[] = [
 *     { key: 'card_num', header: 'Card Number' },
 *     { key: 'acct_id', header: 'Account' },
 *     {
 *         key: 'active_status',
 *         header: 'Status',
 *         render: (row) => <Chip label={row.active_status} />,
 *     },
 * ];
 *
 * <DataTable<CardSummary>
 *     columns={columns}
 *     data={pageEnvelope}
 *     getRowKey={(row) => row.card_num}
 *     onPageChange={HandlePageChange}
 *     onRowClick={HandleSelectCard}
 * />
 * ```
 */
export function DataTable<T>(props: DataTableProps<T>) {
    const { columns, data, onPageChange, getRowKey, onRowClick } = props;
    const { getRowLabel, emptyMessage = DEFAULT_EMPTY_MESSAGE } = props;
    const { loading = false } = props;

    /**
     * Resolves the accessible name announced for a selectable row's activation:
     * the caller-supplied {@link DataTableProps.getRowLabel} when present,
     * otherwise a generic label derived from the row key.
     *
     * @param row - The row being labelled.
     * @returns The accessible name for the row's select action.
     */
    function ResolveRowLabel(row: T): string {
        if (getRowLabel) {
            return getRowLabel(row);
        }
        return `Select record ${getRowKey(row)}`;
    }

    // Pagination facts are read verbatim from the snake_case wire contract; they
    // are the backend's field names (do NOT rename to camelCase).
    const { items, page, total_pages, has_next, has_previous } = data;

    // MUI `Pagination` requires `count >= 1` and `page` within `[1, count]`.
    // Clamp an empty result set to a single (current) page so it renders without
    // an out-of-range warning; non-empty pages are unaffected.
    const pageCount = Math.max(total_pages, 1);

    /**
     * Relays a `Pagination` click to the caller as the 1-based target page.
     *
     * @param event - The originating change event (unused; MUI-supplied).
     * @param page - The 1-based page the user selected.
     */
    function HandlePageChange(event: ChangeEvent<unknown>, page: number): void {
        onPageChange(page);
    }

    /**
     * Handles the PF7/PF8 pagination shortcuts, bounds-checked against the
     * envelope: `PageUp` pages back only when a previous page exists, and
     * `PageDown` pages forward only when a next page exists. Other keys are
     * ignored so normal focus/typing behavior is preserved.
     *
     * @param event - The keyboard event from the focusable grid container.
     */
    function HandleKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
        if (event.key === PAGE_UP_KEY && has_previous) {
            onPageChange(page - 1);
        } else if (event.key === PAGE_DOWN_KEY && has_next) {
            onPageChange(page + 1);
        }
    }

    /**
     * Resolves the content for a single cell: the column's custom `render` when
     * provided, otherwise the stringified raw value. A nullish value renders as
     * an empty string so the grid never shows the literal "null"/"undefined".
     *
     * @param column - The column being rendered.
     * @param row - The row supplying the cell value.
     * @returns The React node to render inside the cell.
     */
    function RenderCellValue(column: ColumnDef<T>, row: T): ReactNode {
        if (column.render) {
            return column.render(row);
        }
        return String((row as Record<string, unknown>)[column.key] ?? '');
    }

    // Body has three mutually exclusive states: loading, empty, and populated.
    let bodyContent: ReactNode;
    if (loading) {
        // Render a full page of skeleton rows (one per eventual data row) so the
        // table occupies the SAME height while loading as when populated. This
        // eliminates the layout shift (QA #16 CLS) that a single short spinner
        // row caused when it was swapped for the full result set, and it avoids
        // the CircularProgress non-composited stroke animation that was the
        // dominant CLS culprit. Skeleton's pulse animation is compositor-driven.
        bodyContent = Array.from({ length: ROWS_PER_PAGE }).map(
            (_unused, rowIndex) => (
                <TableRow key={`skeleton-${rowIndex}`} aria-hidden="true">
                    {columns.map((column) => (
                        <TableCell
                            key={column.key}
                            align={column.align ?? 'left'}
                        >
                            <Skeleton variant="text" />
                        </TableCell>
                    ))}
                </TableRow>
            ),
        );
    } else if (items.length === 0) {
        bodyContent = (
            <TableRow>
                <TableCell colSpan={columns.length} align="center">
                    <Typography variant="body2">{emptyMessage}</Typography>
                </TableCell>
            </TableRow>
        );
    } else {
        bodyContent = items.map((row) => {
            // A row is selectable only when the caller supplied `onRowClick`.
            // Selectable rows must be operable by KEYBOARD as well as mouse
            // (QA M-27): they become a focusable tab stop with an accessible
            // name, activate on Enter/Space, and show a visible focus ring.
            // Row semantics (role="row") are preserved so the contained cells
            // keep a valid parent — activation is layered on, not replaced.
            const isSelectable = Boolean(onRowClick);

            /**
             * Activates the row from the keyboard: Enter or Space invokes the
             * same selection callback as a click. Space is `preventDefault`ed so
             * it selects the row instead of scrolling the region. Other keys are
             * ignored so PF7/PF8 paging and normal focus movement are unaffected.
             *
             * @param event - The keyboard event from the focused row.
             */
            function HandleRowKeyDown(
                event: KeyboardEvent<HTMLTableRowElement>,
            ): void {
                if (!onRowClick) {
                    return;
                }
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onRowClick(row);
                }
            }

            return (
                <TableRow
                    key={getRowKey(row)}
                    hover
                    onClick={onRowClick ? () => onRowClick(row) : undefined}
                    onKeyDown={isSelectable ? HandleRowKeyDown : undefined}
                    tabIndex={isSelectable ? 0 : undefined}
                    aria-label={isSelectable ? ResolveRowLabel(row) : undefined}
                    sx={
                        isSelectable
                            ? {
                                  cursor: 'pointer',
                                  // Visible keyboard focus indicator (WCAG
                                  // 2.4.7) drawn with a theme token, inset so it
                                  // is not clipped by the TableContainer.
                                  '&:focus-visible': {
                                      outline: (theme) =>
                                          `2px solid ${theme.palette.primary.main}`,
                                      outlineOffset: '-2px',
                                  },
                              }
                            : undefined
                    }
                >
                    {columns.map((column) => (
                        <TableCell
                            key={column.key}
                            align={column.align ?? 'left'}
                        >
                            {RenderCellValue(column, row)}
                        </TableCell>
                    ))}
                </TableRow>
            );
        });
    }

    return (
        <Box
            onKeyDown={HandleKeyDown}
            tabIndex={0}
            role="region"
            aria-label={`Records table, up to ${ROWS_PER_PAGE} rows per page`}
            aria-busy={loading}
            // fullWidth + minWidth:0 keep the region within its (flex) parent so
            // the TableContainer below — not the page — owns any horizontal scroll
            // on narrow viewports (QA #2). The fill-parent width is the named
            // `theme.layout.fullWidth` token, not a hardcoded `100%` (QA M-25).
            sx={(theme) => ({ width: theme.layout.fullWidth, minWidth: 0 })}
        >
            <TableContainer
                component={Paper}
                // overflowX:auto scrolls wide tables internally (QA #2). minHeight
                // reserves a FULL page of vertical space — a 57px header plus seven
                // 64px rows (ROWS_PER_PAGE) — via the 8px spacing scale, so the
                // container (and everything below it, e.g. Pagination) keeps the
                // same height across the empty / loading / populated / partial-page
                // states. This eliminates the async-load layout shift (QA #16 CLS).
                sx={(theme) => ({
                    width: theme.layout.fullWidth,
                    overflowX: 'auto',
                    minHeight: theme.spacing(64),
                })}
            >
                <Table
                    // Fixed body-row height keeps every row (skeleton, data, or
                    // empty message) at the SAME vertical position, so swapping
                    // skeletons for data never nudges rows (QA #16). Header cells
                    // never wrap, so the head stays one line (57px) and the body
                    // does not shift up/down when column widths change on load.
                    sx={{
                        '& tbody .MuiTableRow-root': {
                            height: (theme) => theme.spacing(8),
                        },
                        '& thead .MuiTableCell-head': {
                            whiteSpace: 'nowrap',
                        },
                    }}
                >
                    <TableHead>
                        <TableRow>
                            {columns.map((column) => (
                                <TableCell
                                    key={column.key}
                                    align={column.align ?? 'left'}
                                >
                                    {column.header}
                                </TableCell>
                            ))}
                        </TableRow>
                    </TableHead>
                    <TableBody>{bodyContent}</TableBody>
                </Table>
            </TableContainer>
            <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
                <Pagination
                    count={pageCount}
                    page={page}
                    onChange={HandlePageChange}
                    color="primary"
                />
            </Box>
        </Box>
    );
}

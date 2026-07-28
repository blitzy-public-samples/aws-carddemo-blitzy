/**
 * DataTable.test.tsx — React Testing Library spec for the generic MUI browse
 * grid `@/components/DataTable`. It locks the grid contract: one header per
 * column, one row per data item with verbatim stringify plus custom `render`
 * overrides, `getRowKey`-based keys, the ROWS_PER_PAGE = 7 page limit (F-004,
 * traceable to app/cpy-bms/COCRDLI.CPY's exactly-7 repeating card row groups),
 * mouse pagination and PF7/PF8 (PageUp/PageDown) keyboard paging, optional row
 * click, the empty-message and loading states.
 *
 * Traceability: the legacy 3270 paged browse screens COCRDLI (card list, F-004),
 * COTRN00 (transaction list), and COUSR00 (user list). Greenfield test infra —
 * no legacy COBOL origin for the spec itself.
 */

import {
    RenderWithProviders,
    screen,
    within,
    fireEvent,
    userEvent,
    MakePaginatedResponse,
} from '../testUtils';
import { axe } from 'jest-axe';
import { DataTable } from '@/components/DataTable';
import type { ColumnDef } from '@/components/DataTable';
import type { PaginatedResponse } from '@/types';

// ---------------------------------------------------------------------------
// Local row fixture type + security invariants.
//
// SECURITY INVARIANTS (Ochs Test Rule): `card_num` is ALWAYS masked (last 4
// digits visible only), there is NO `cvv` field anywhere (it must never be
// surfaced to the UI), and all ids/money are strings so leading zeros survive
// (e.g. acct_id '00000000001'). SSN is never present on a browse row.
// ---------------------------------------------------------------------------

/** Minimal card-browse row shape used to exercise the generic `DataTable<T>`. */
type CardRow = {
    card_num: string;
    acct_id: string;
    embossed_name: string;
    active_status: string;
};

/** Base value for generated masked last-4 digits (keeps '************3456' first). */
const CARD_NUM_BASE = 3456;

/** The legacy F-004 browse limit asserted by the pagination specs. */
const ROWS_PER_PAGE_EXPECTED = 7;

/**
 * Column set mirroring the card-browse screen (COCRDLI). The `active_status`
 * column supplies a custom `render` (exercising the render path); the other
 * three fall back to the grid's raw stringify.
 */
const CARD_COLUMNS: ColumnDef<CardRow>[] = [
    { key: 'card_num', header: 'Card Number' },
    { key: 'acct_id', header: 'Account ID', align: 'right' },
    { key: 'embossed_name', header: 'Name' },
    {
        key: 'active_status',
        header: 'Status',
        render: (row) => (row.active_status === 'Y' ? 'Active' : 'Inactive'),
    },
];

/** Stable row key derived from the (masked) card number. */
const GET_ROW_KEY = (row: CardRow) => row.card_num;

/**
 * Build `count` masked card rows with unique last-4 suffixes starting at
 * {@link CARD_NUM_BASE}. `active_status` alternates 'Y'/'N' so a single small
 * fixture covers both branches of the Status column's custom `render`.
 *
 * @param count - Number of rows to generate.
 * @returns The generated, security-correct card rows.
 */
function makeCardRows(count: number): CardRow[] {
    const rows: CardRow[] = [];
    for (let index = 0; index < count; index += 1) {
        const lastFour = String(CARD_NUM_BASE + index).padStart(4, '0');
        rows.push({
            card_num: `************${lastFour}`,
            acct_id: String(index + 1).padStart(11, '0'),
            embossed_name: `CARDHOLDER ${lastFour}`,
            active_status: index % 2 === 0 ? 'Y' : 'N',
        });
    }
    return rows;
}

describe('DataTable', () => {
    /**
     * Options accepted by {@link renderCardTable}. Every field is optional so a
     * spec overrides only what it asserts on, keeping each `it` small.
     */
    interface RenderCardTableOptions {
        items?: CardRow[];
        dataOverrides?: Partial<PaginatedResponse<CardRow>>;
        onPageChange?: (page: number) => void;
        onRowClick?: (row: CardRow) => void;
        loading?: boolean;
        emptyMessage?: string;
    }

    /**
     * Render a `DataTable<CardRow>` with the shared card columns, merging in any
     * supplied overrides. Fresh `jest.fn()` mocks back the callbacks; the render
     * result (including `container`) and the mocks are returned so specs can
     * assert on them.
     *
     * @param options - Partial data / prop overrides for this render.
     * @returns The render result plus the `onPageChange` / `onRowClick` mocks.
     */
    function renderCardTable(options: RenderCardTableOptions = {}) {
        const {
            items = makeCardRows(1),
            dataOverrides,
            onPageChange = jest.fn(),
            onRowClick,
            loading = false,
            emptyMessage,
        } = options;

        const view = RenderWithProviders(
            <DataTable<CardRow>
                columns={CARD_COLUMNS}
                data={MakePaginatedResponse<CardRow>(items, dataOverrides)}
                getRowKey={GET_ROW_KEY}
                onPageChange={onPageChange}
                onRowClick={onRowClick}
                loading={loading}
                emptyMessage={emptyMessage}
            />,
        );

        return { ...view, onPageChange, onRowClick };
    }

    /**
     * Return only the `<tbody>` data/state rows (excludes the header row) by
     * scoping the query to the last MUI `rowgroup` with `within`.
     *
     * @returns The body rows currently rendered.
     */
    function getBodyRows(): HTMLElement[] {
        const rowGroups = screen.getAllByRole('rowgroup');
        const tableBody = rowGroups[rowGroups.length - 1];
        return within(tableBody).getAllByRole('row');
    }

    // ----------------------------------------------------------------------
    describe('rendering', () => {
        it('renders one header cell per column', () => {
            renderCardTable({ items: makeCardRows(3) });

            expect(
                screen.getByRole('columnheader', { name: 'Card Number' }),
            ).toBeInTheDocument();
            expect(
                screen.getByRole('columnheader', { name: 'Account ID' }),
            ).toBeInTheDocument();
            expect(
                screen.getByRole('columnheader', { name: 'Name' }),
            ).toBeInTheDocument();
            expect(
                screen.getByRole('columnheader', { name: 'Status' }),
            ).toBeInTheDocument();
            expect(screen.getAllByRole('columnheader')).toHaveLength(
                CARD_COLUMNS.length,
            );
        });

        it('renders one row per data item', () => {
            renderCardTable({ items: makeCardRows(3) });

            // Header row (1) + three data rows (3) = four total rows.
            expect(screen.getAllByRole('row')).toHaveLength(4);
            expect(getBodyRows()).toHaveLength(3);
            // A known masked card value is shown verbatim.
            expect(screen.getByText('************3456')).toBeInTheDocument();
        });

        it('uses the column render function when provided', () => {
            // makeCardRows alternates active_status: row 0 = 'Y', row 1 = 'N'.
            renderCardTable({ items: makeCardRows(2) });

            expect(screen.getByText('Active')).toBeInTheDocument();
            expect(screen.getByText('Inactive')).toBeInTheDocument();
            // The rendered label replaces the raw flag, which is never shown.
            expect(screen.queryByText('Y')).not.toBeInTheDocument();
            expect(screen.queryByText('N')).not.toBeInTheDocument();
        });

        it('uses getRowKey for stable, unique row keys (no key warning)', () => {
            const errorSpy = jest
                .spyOn(console, 'error')
                .mockImplementation(() => {});

            renderCardTable({ items: makeCardRows(2) });

            const hadKeyWarning = errorSpy.mock.calls.some((call) =>
                String(call[0]).toLowerCase().includes('key'),
            );
            expect(hadKeyWarning).toBe(false);
            // Both distinct rows render under their unique card_num keys.
            expect(screen.getByText('************3456')).toBeInTheDocument();
            expect(screen.getByText('************3457')).toBeInTheDocument();

            errorSpy.mockRestore();
        });
    });

    // ----------------------------------------------------------------------
    describe('pagination', () => {
        it('renders at most ROWS_PER_PAGE (7) rows per page (F-004)', () => {
            renderCardTable({
                items: makeCardRows(ROWS_PER_PAGE_EXPECTED),
                dataOverrides: {
                    page: 1,
                    total_items: 14,
                    total_pages: 2,
                    has_next: true,
                    has_previous: false,
                },
            });

            const bodyRows = getBodyRows();
            expect(bodyRows).toHaveLength(ROWS_PER_PAGE_EXPECTED);
            expect(bodyRows.length).toBeLessThanOrEqual(ROWS_PER_PAGE_EXPECTED);
            // The MUI Pagination control (a <nav>) is present for multi-page data.
            expect(screen.getByRole('navigation')).toBeInTheDocument();
        });

        it('calls onPageChange when a page button is clicked', async () => {
            const user = userEvent.setup();
            const { onPageChange } = renderCardTable({
                items: makeCardRows(ROWS_PER_PAGE_EXPECTED),
                dataOverrides: {
                    page: 1,
                    total_items: 14,
                    total_pages: 2,
                    has_next: true,
                    has_previous: false,
                },
            });

            await user.click(
                screen.getByRole('button', { name: /go to page 2/i }),
            );

            expect(onPageChange).toHaveBeenCalledWith(2);
        });

        it('pages forward on PF8 / PageDown when has_next is true', () => {
            const { container, onPageChange } = renderCardTable({
                dataOverrides: {
                    page: 1,
                    total_pages: 2,
                    has_next: true,
                    has_previous: false,
                },
            });

            fireEvent.keyDown(container.firstChild as HTMLElement, {
                key: 'PageDown',
            });

            expect(onPageChange).toHaveBeenCalledWith(2);
        });

        it('pages backward on PF7 / PageUp when has_previous is true', () => {
            const { container, onPageChange } = renderCardTable({
                dataOverrides: {
                    page: 2,
                    total_pages: 2,
                    has_next: false,
                    has_previous: true,
                },
            });

            fireEvent.keyDown(container.firstChild as HTMLElement, {
                key: 'PageUp',
            });

            expect(onPageChange).toHaveBeenCalledWith(1);
        });

        it('does not page beyond the first or last page', () => {
            // First page (has_previous false): PageUp is ignored.
            const first = renderCardTable({
                dataOverrides: {
                    page: 1,
                    total_pages: 2,
                    has_next: true,
                    has_previous: false,
                },
            });
            fireEvent.keyDown(first.container.firstChild as HTMLElement, {
                key: 'PageUp',
            });
            expect(first.onPageChange).not.toHaveBeenCalled();

            // Last page (has_next false): PageDown is ignored.
            const last = renderCardTable({
                dataOverrides: {
                    page: 2,
                    total_pages: 2,
                    has_next: false,
                    has_previous: true,
                },
            });
            fireEvent.keyDown(last.container.firstChild as HTMLElement, {
                key: 'PageDown',
            });
            expect(last.onPageChange).not.toHaveBeenCalled();
        });

        it('ignores keys other than PageUp / PageDown', () => {
            const { container, onPageChange } = renderCardTable({
                dataOverrides: {
                    page: 2,
                    total_pages: 3,
                    has_next: true,
                    has_previous: true,
                },
            });

            fireEvent.keyDown(container.firstChild as HTMLElement, {
                key: 'Enter',
            });

            expect(onPageChange).not.toHaveBeenCalled();
        });
    });

    // ----------------------------------------------------------------------
    describe('interaction', () => {
        it('calls onRowClick with the clicked row', async () => {
            const user = userEvent.setup();
            const rows = makeCardRows(2);
            const onRowClick = jest.fn();
            renderCardTable({ items: rows, onRowClick });

            await user.click(screen.getByText('************3456'));

            expect(onRowClick).toHaveBeenCalledTimes(1);
            expect(onRowClick).toHaveBeenCalledWith(rows[0]);
        });

        it('renders and is clickable without an onRowClick handler', async () => {
            const user = userEvent.setup();
            renderCardTable({ items: makeCardRows(2) });

            // Clicking a cell must not throw when no selection handler exists.
            await user.click(screen.getByText('************3456'));

            expect(screen.getByText('************3456')).toBeInTheDocument();
        });
    });

    // ----------------------------------------------------------------------
    describe('states', () => {
        it('shows the default empty message when there are no items', () => {
            renderCardTable({
                items: [],
                dataOverrides: {
                    total_items: 0,
                    total_pages: 0,
                },
            });

            expect(screen.getByText('No records found.')).toBeInTheDocument();
            // Only the single empty-state row is present (no data rows).
            expect(getBodyRows()).toHaveLength(1);
            expect(
                screen.queryByText('************3456'),
            ).not.toBeInTheDocument();
        });

        it('shows a custom empty message when provided', () => {
            renderCardTable({
                items: [],
                emptyMessage: 'No cards on file.',
                dataOverrides: {
                    total_items: 0,
                    total_pages: 0,
                },
            });

            expect(screen.getByText('No cards on file.')).toBeInTheDocument();
        });

        it('shows skeleton rows (aria-busy) and hides data rows while loading', () => {
            const { container } = renderCardTable({
                items: makeCardRows(3),
                loading: true,
            });

            // The region announces the busy state to assistive tech, and the body
            // is filled with skeleton placeholders instead of a short spinner row
            // so the table reserves its full height (QA #16 CLS fix).
            const region = screen.getByRole('region');
            expect(region).toHaveAttribute('aria-busy', 'true');
            expect(
                container.querySelectorAll('.MuiSkeleton-root').length,
            ).toBeGreaterThan(0);

            // Loading takes precedence over data: no card values are rendered.
            expect(
                screen.queryByText('************3456'),
            ).not.toBeInTheDocument();
        });
    });

    // ----------------------------------------------------------------------
    // Accessibility — keyboard-operable selectable rows (QA M-27) and the
    // automated axe gate (QA M-30). The legacy COCRDLI/COTRN00 "select a row"
    // action was mouse-only in the SPA; it must now be fully keyboard operable.
    // ----------------------------------------------------------------------
    describe('accessibility', () => {
        it('makes selectable rows focusable with an accessible name (M-27)', () => {
            const onRowClick = jest.fn();
            renderCardTable({ items: makeCardRows(2), onRowClick });

            const bodyRows = getBodyRows();
            for (const row of bodyRows) {
                // Each selectable row is a tab stop and exposes an accessible
                // name so a screen-reader user knows the row is actionable.
                expect(row).toHaveAttribute('tabindex', '0');
                expect(row).toHaveAttribute('aria-label');
            }
            // Default label derives from the row key (the masked card number).
            expect(
                screen.getByRole('row', { name: /Select record .*3456/ }),
            ).toBeInTheDocument();
        });

        it('does not make rows focusable when not selectable (M-27)', () => {
            renderCardTable({ items: makeCardRows(2) });

            for (const row of getBodyRows()) {
                expect(row).not.toHaveAttribute('tabindex');
                expect(row).not.toHaveAttribute('aria-label');
            }
        });

        it('activates a selectable row on Enter (M-27)', async () => {
            const user = userEvent.setup();
            const rows = makeCardRows(2);
            const onRowClick = jest.fn();
            renderCardTable({ items: rows, onRowClick });

            const firstRow = getBodyRows()[0];
            firstRow.focus();
            expect(firstRow).toHaveFocus();
            await user.keyboard('{Enter}');

            expect(onRowClick).toHaveBeenCalledTimes(1);
            expect(onRowClick).toHaveBeenCalledWith(rows[0]);
        });

        it('activates a selectable row on Space (M-27)', async () => {
            const user = userEvent.setup();
            const rows = makeCardRows(2);
            const onRowClick = jest.fn();
            renderCardTable({ items: rows, onRowClick });

            const secondRow = getBodyRows()[1];
            secondRow.focus();
            await user.keyboard('[Space]');

            expect(onRowClick).toHaveBeenCalledTimes(1);
            expect(onRowClick).toHaveBeenCalledWith(rows[1]);
        });

        it('uses getRowLabel for the row accessible name when provided (M-27)', () => {
            const onRowClick = jest.fn();
            RenderWithProviders(
                <DataTable<CardRow>
                    columns={CARD_COLUMNS}
                    data={MakePaginatedResponse<CardRow>(makeCardRows(1))}
                    getRowKey={GET_ROW_KEY}
                    onPageChange={jest.fn()}
                    onRowClick={onRowClick}
                    getRowLabel={(row) => `View card ${row.card_num}`}
                />,
            );

            expect(
                screen.getByRole('row', { name: 'View card ************3456' }),
            ).toBeInTheDocument();
        });

        it('has no axe violations for a populated selectable grid (M-30)', async () => {
            const { container } = renderCardTable({
                items: makeCardRows(3),
                onRowClick: jest.fn(),
                dataOverrides: {
                    page: 1,
                    total_items: 6,
                    total_pages: 2,
                    has_next: true,
                    has_previous: false,
                },
            });

            expect(await axe(container)).toHaveNoViolations();
        });

        it('has no axe violations in the empty state (M-30)', async () => {
            const { container } = renderCardTable({
                items: [],
                dataOverrides: { total_items: 0, total_pages: 0 },
            });

            expect(await axe(container)).toHaveNoViolations();
        });
    });
});

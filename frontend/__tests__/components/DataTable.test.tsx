/**
 * DataTable.test.tsx — verifies the generic MUI `DataTable` browse grid: header
 * rendering, one-row-per-item with verbatim stringify and `render` overrides,
 * the loading and empty states (and their precedence), optional row selection,
 * the `Pagination` control (page count, current page, click → `onPageChange`),
 * and the PF7/PF8 (`PageUp`/`PageDown`) keyboard shortcuts with their
 * `has_previous`/`has_next` bounds. Also confirms the component is reusable with
 * a second row type (`UserSummary`).
 *
 * Traceability: the legacy 3270 browse screens COCRDLI (card list, F-004 <= 7
 * rows/page), COTRN00 (transaction list), and COUSR00 (user list). Greenfield
 * test using the shared `testUtils` fixtures pre-staged for DataTable specs.
 */

import {
    RenderWithProviders,
    screen,
    fireEvent,
    userEvent,
    MakePaginatedResponse,
    MakeCardSummary,
    MakeUserSummary,
} from '../testUtils';
import { DataTable } from '@/components/DataTable';
import type { ColumnDef } from '@/components/DataTable';
import type { CardSummary, UserSummary, PaginatedResponse } from '@/types';

/**
 * Column set mirroring the card-browse screen (COCRDLI). The `active_status`
 * column exercises a custom `render`; the others fall back to raw stringify.
 */
const CARD_COLUMNS: ColumnDef<CardSummary>[] = [
    { key: 'card_num', header: 'Card Number' },
    { key: 'acct_id', header: 'Account', align: 'right' },
    {
        key: 'active_status',
        header: 'Status',
        render: (row) => `status:${row.active_status}`,
    },
];

/** The accessible-name fragment of the grid's focusable outer region. */
const GRID_REGION_NAME = /rows per page/i;

describe('DataTable', () => {
    /**
     * Options accepted by {@link RenderCardTable}. Each field is optional so a
     * spec overrides only what it asserts on (keeping every spec small).
     */
    interface RenderCardTableOptions {
        items?: CardSummary[];
        dataOverrides?: Partial<PaginatedResponse<CardSummary>>;
        onPageChange?: (page: number) => void;
        onRowClick?: (row: CardSummary) => void;
        loading?: boolean;
        emptyMessage?: string;
    }

    /**
     * Render a `DataTable<CardSummary>` with the shared card columns, merging in
     * any supplied overrides. Fresh `jest.fn()` mocks back the callbacks so
     * callers can assert on them via the returned handle.
     *
     * @param options - Partial data / prop overrides for this render.
     * @returns The mock callbacks passed to the rendered grid.
     */
    function RenderCardTable(options: RenderCardTableOptions = {}) {
        const {
            items = [MakeCardSummary()],
            dataOverrides,
            onPageChange = jest.fn(),
            onRowClick,
            loading = false,
            emptyMessage,
        } = options;

        RenderWithProviders(
            <DataTable<CardSummary>
                columns={CARD_COLUMNS}
                data={MakePaginatedResponse<CardSummary>(items, dataOverrides)}
                getRowKey={(row) => row.card_num}
                onPageChange={onPageChange}
                onRowClick={onRowClick}
                loading={loading}
                emptyMessage={emptyMessage}
            />,
        );

        return { onPageChange, onRowClick };
    }

    // ----------------------------------------------------------------------
    // Headers & rows
    // ----------------------------------------------------------------------

    it('renders a header cell per column', () => {
        RenderCardTable();

        expect(
            screen.getByRole('columnheader', { name: 'Card Number' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('columnheader', { name: 'Account' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('columnheader', { name: 'Status' }),
        ).toBeInTheDocument();
    });

    it('renders one row per item, stringifies raw values, and honors render', () => {
        RenderCardTable({
            items: [MakeCardSummary({ acct_id: '00000000011', active_status: 'Y' })],
        });

        // Raw stringify (acct_id shown verbatim, leading zeros preserved).
        expect(
            screen.getByRole('cell', { name: '00000000011' }),
        ).toBeInTheDocument();
        // Custom render override wins over the raw value.
        expect(
            screen.getByRole('cell', { name: 'status:Y' }),
        ).toBeInTheDocument();
        // One header row + one body row.
        expect(screen.getAllByRole('row')).toHaveLength(2);
    });

    // ----------------------------------------------------------------------
    // Loading & empty states
    // ----------------------------------------------------------------------

    it('shows a progress indicator and no data rows while loading', () => {
        RenderCardTable({
            items: [MakeCardSummary({ acct_id: '00000000011' })],
            loading: true,
        });

        expect(screen.getByRole('progressbar')).toBeInTheDocument();
        // Loading takes precedence over data: the row value is not rendered.
        expect(
            screen.queryByRole('cell', { name: '00000000011' }),
        ).not.toBeInTheDocument();
    });

    it('shows the default empty message when there are no rows', () => {
        RenderCardTable({ items: [] });

        expect(screen.getByText('No records found.')).toBeInTheDocument();
    });

    it('shows a custom empty message when provided', () => {
        RenderCardTable({ items: [], emptyMessage: 'No cards on file.' });

        expect(screen.getByText('No cards on file.')).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // Row selection (optional)
    // ----------------------------------------------------------------------

    it('calls onRowClick with the clicked row', async () => {
        const user = userEvent.setup();
        const card = MakeCardSummary({ acct_id: '00000000011' });
        const { onRowClick } = RenderCardTable({
            items: [card],
            onRowClick: jest.fn(),
        });

        await user.click(screen.getByRole('cell', { name: '00000000011' }));

        expect(onRowClick).toHaveBeenCalledTimes(1);
        expect(onRowClick).toHaveBeenCalledWith(card);
    });

    it('renders rows without a selection handler when onRowClick is omitted', () => {
        RenderCardTable({
            items: [MakeCardSummary({ acct_id: '00000000011' })],
        });

        // The grid still renders the row; onRowClick is genuinely optional.
        expect(
            screen.getByRole('cell', { name: '00000000011' }),
        ).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // Pagination control
    // ----------------------------------------------------------------------

    it('renders total_pages pages, marks the current page, and reports clicks', async () => {
        const user = userEvent.setup();
        const { onPageChange } = RenderCardTable({
            dataOverrides: {
                page: 1,
                total_pages: 3,
                has_next: true,
                has_previous: false,
            },
        });

        // Current page is marked via aria-current (MUI uses the value "page").
        expect(screen.getByRole('button', { name: 'page 1' })).toHaveAttribute(
            'aria-current',
            'page',
        );

        await user.click(screen.getByRole('button', { name: /go to page 2/i }));

        expect(onPageChange).toHaveBeenCalledWith(2);
    });

    // ----------------------------------------------------------------------
    // PF7 / PF8 keyboard shortcuts (bounds-checked)
    // ----------------------------------------------------------------------

    it('pages forward on PageDown (PF8) when has_next is true', () => {
        const { onPageChange } = RenderCardTable({
            dataOverrides: {
                page: 2,
                total_pages: 3,
                has_next: true,
                has_previous: true,
            },
        });

        fireEvent.keyDown(screen.getByRole('region', { name: GRID_REGION_NAME }), {
            key: 'PageDown',
        });

        expect(onPageChange).toHaveBeenCalledWith(3);
    });

    it('pages back on PageUp (PF7) when has_previous is true', () => {
        const { onPageChange } = RenderCardTable({
            dataOverrides: {
                page: 2,
                total_pages: 3,
                has_next: true,
                has_previous: true,
            },
        });

        fireEvent.keyDown(screen.getByRole('region', { name: GRID_REGION_NAME }), {
            key: 'PageUp',
        });

        expect(onPageChange).toHaveBeenCalledWith(1);
    });

    it('ignores PageDown at the last page (has_next false)', () => {
        const { onPageChange } = RenderCardTable({
            dataOverrides: {
                page: 3,
                total_pages: 3,
                has_next: false,
                has_previous: true,
            },
        });

        fireEvent.keyDown(screen.getByRole('region', { name: GRID_REGION_NAME }), {
            key: 'PageDown',
        });

        expect(onPageChange).not.toHaveBeenCalled();
    });

    it('ignores PageUp at the first page (has_previous false)', () => {
        const { onPageChange } = RenderCardTable({
            dataOverrides: {
                page: 1,
                total_pages: 3,
                has_next: true,
                has_previous: false,
            },
        });

        fireEvent.keyDown(screen.getByRole('region', { name: GRID_REGION_NAME }), {
            key: 'PageUp',
        });

        expect(onPageChange).not.toHaveBeenCalled();
    });

    it('ignores unrelated keys', () => {
        const { onPageChange } = RenderCardTable({
            dataOverrides: {
                page: 2,
                total_pages: 3,
                has_next: true,
                has_previous: true,
            },
        });

        fireEvent.keyDown(screen.getByRole('region', { name: GRID_REGION_NAME }), {
            key: 'Enter',
        });

        expect(onPageChange).not.toHaveBeenCalled();
    });

    // ----------------------------------------------------------------------
    // Generic reuse
    // ----------------------------------------------------------------------

    it('is reusable with a different row type (UserSummary)', () => {
        const userColumns: ColumnDef<UserSummary>[] = [
            { key: 'user_id', header: 'User ID' },
            { key: 'user_type', header: 'Type' },
        ];

        RenderWithProviders(
            <DataTable<UserSummary>
                columns={userColumns}
                data={MakePaginatedResponse<UserSummary>([
                    MakeUserSummary({ user_id: 'USER0001', user_type: 'U' }),
                ])}
                getRowKey={(row) => row.user_id}
                onPageChange={jest.fn()}
            />,
        );

        expect(
            screen.getByRole('columnheader', { name: 'User ID' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('cell', { name: 'USER0001' }),
        ).toBeInTheDocument();
    });
});

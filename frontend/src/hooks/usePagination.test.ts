/**
 * :module: usePagination.test
 * :purpose: Verify the BMS-faithful list pagination hook: the spec-literal page
 *     sizes (card list 7; transaction and user lists 10), the clamped PF8
 *     (forward) and PF7 (backward) navigation including the partial final page
 *     and the end/start no-ops, and the empty-list edge case.
 */

import { renderHook, act } from '@testing-library/react';
import {
  usePagination,
  CARD_LIST_PAGE_SIZE,
  TRANSACTION_LIST_PAGE_SIZE,
  USER_LIST_PAGE_SIZE,
} from './usePagination';

/**
 * Build a deterministic, ordered array of ``count`` string items
 * (``item-0`` .. ``item-(count-1)``) so slice contents can be asserted exactly.
 */
function makeItems(count: number): string[] {
  return Array.from({ length: count }, (_, i) => `item-${i}`);
}

describe('usePagination — exported page-size constants (spec-literal)', () => {
  it('pins the BMS row counts: card 7, transaction 10, user 10', () => {
    expect(CARD_LIST_PAGE_SIZE).toBe(7);
    expect(TRANSACTION_LIST_PAGE_SIZE).toBe(10);
    expect(USER_LIST_PAGE_SIZE).toBe(10);
  });
});

describe('usePagination — card list size (7 rows/page)', () => {
  it('reports 3 pages for 20 items and slices the first page', () => {
    const items = makeItems(20);
    const { result } = renderHook(() => usePagination(items, CARD_LIST_PAGE_SIZE));

    expect(result.current.page).toBe(0);
    expect(result.current.pageSize).toBe(7);
    expect(result.current.totalPages).toBe(3);
    expect(result.current.pageRows).toHaveLength(7);
    expect(result.current.pageRows[0]).toBe('item-0');
    expect(result.current.pageRows[6]).toBe('item-6');
    expect(result.current.hasPrevious).toBe(false);
    expect(result.current.hasNext).toBe(true);
  });

  it('PF8 pages forward and clamps at the partial final page', () => {
    const items = makeItems(20);
    const { result } = renderHook(() => usePagination(items, CARD_LIST_PAGE_SIZE));

    // page 0 -> 1 (full page)
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(1);
    expect(result.current.pageRows).toHaveLength(7);
    expect(result.current.pageRows[0]).toBe('item-7');
    expect(result.current.hasPrevious).toBe(true);
    expect(result.current.hasNext).toBe(true);

    // page 1 -> 2 (partial final page: 6 rows)
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(2);
    expect(result.current.pageRows).toHaveLength(6);
    expect(result.current.pageRows[0]).toBe('item-14');
    expect(result.current.pageRows[5]).toBe('item-19');
    expect(result.current.hasNext).toBe(false);

    // PF8 at the last page is a no-op (CA-NEXT-PAGE-NOT-EXISTS behaviour)
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(2);
    expect(result.current.pageRows).toHaveLength(6);
  });

  it('PF7 pages backward and clamps at page 0', () => {
    const items = makeItems(20);
    const { result } = renderHook(() => usePagination(items, CARD_LIST_PAGE_SIZE));

    // Advance to the last page first.
    act(() => result.current.nextPage());
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(2);

    // page 2 -> 1
    act(() => result.current.prevPage());
    expect(result.current.page).toBe(1);
    expect(result.current.pageRows[0]).toBe('item-7');

    // page 1 -> 0
    act(() => result.current.prevPage());
    expect(result.current.page).toBe(0);
    expect(result.current.hasPrevious).toBe(false);

    // PF7 at page 0 is a no-op.
    act(() => result.current.prevPage());
    expect(result.current.page).toBe(0);
    expect(result.current.hasPrevious).toBe(false);
  });
});

describe('usePagination — transaction / user list size (10 rows/page)', () => {
  it('reports 2 pages for 20 items and pages forward to the last page', () => {
    const items = makeItems(20);
    const { result } = renderHook(() =>
      usePagination(items, TRANSACTION_LIST_PAGE_SIZE),
    );

    expect(result.current.pageSize).toBe(10);
    expect(result.current.totalPages).toBe(2);
    expect(result.current.pageRows).toHaveLength(10);
    expect(result.current.pageRows[0]).toBe('item-0');
    expect(result.current.hasNext).toBe(true);

    // page 0 -> 1 (final page, still 10 rows)
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(1);
    expect(result.current.pageRows).toHaveLength(10);
    expect(result.current.pageRows[0]).toBe('item-10');
    expect(result.current.pageRows[9]).toBe('item-19');
    expect(result.current.hasNext).toBe(false);
  });

  it('USER_LIST_PAGE_SIZE behaves identically to the transaction size', () => {
    const items = makeItems(20);
    const { result } = renderHook(() => usePagination(items, USER_LIST_PAGE_SIZE));

    expect(result.current.pageSize).toBe(10);
    expect(result.current.totalPages).toBe(2);
    expect(result.current.pageRows).toHaveLength(10);
  });
});

describe('usePagination — goToPage / reset / initialPage', () => {
  it('goToPage clamps into [0, totalPages - 1]', () => {
    const items = makeItems(20);
    const { result } = renderHook(() => usePagination(items, CARD_LIST_PAGE_SIZE));

    act(() => result.current.goToPage(1));
    expect(result.current.page).toBe(1);

    // Above the last page clamps to the last page.
    act(() => result.current.goToPage(99));
    expect(result.current.page).toBe(2);

    // Below zero clamps to page 0.
    act(() => result.current.goToPage(-5));
    expect(result.current.page).toBe(0);
  });

  it('reset returns to page 0', () => {
    const items = makeItems(20);
    const { result } = renderHook(() => usePagination(items, CARD_LIST_PAGE_SIZE));

    act(() => result.current.goToPage(2));
    expect(result.current.page).toBe(2);

    act(() => result.current.reset());
    expect(result.current.page).toBe(0);
  });

  it('honours a non-zero initialPage', () => {
    const items = makeItems(20);
    const { result } = renderHook(() =>
      usePagination(items, CARD_LIST_PAGE_SIZE, 1),
    );

    expect(result.current.page).toBe(1);
    expect(result.current.pageRows[0]).toBe('item-7');
  });
});

describe('usePagination — empty list edge case', () => {
  it('reports a single empty page with navigation disabled', () => {
    const { result } = renderHook(() =>
      usePagination<string>([], CARD_LIST_PAGE_SIZE),
    );

    expect(result.current.totalPages).toBe(1);
    expect(result.current.pageRows).toHaveLength(0);
    expect(result.current.hasNext).toBe(false);
    expect(result.current.hasPrevious).toBe(false);

    // Navigation on an empty list stays on page 0.
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(0);
    act(() => result.current.prevPage());
    expect(result.current.page).toBe(0);
  });
});

describe('usePagination — clamps when the item list shrinks', () => {
  it('keeps the returned page within range after items shrink', () => {
    const twenty = makeItems(20);
    const { result, rerender } = renderHook(
      ({ data }: { data: string[] }) => usePagination(data, CARD_LIST_PAGE_SIZE),
      { initialProps: { data: twenty } },
    );

    // Move to the last page of the 20-item list.
    act(() => result.current.goToPage(2));
    expect(result.current.page).toBe(2);

    // Shrink to 5 items -> a single page; the returned page clamps to 0.
    rerender({ data: makeItems(5) });
    expect(result.current.totalPages).toBe(1);
    expect(result.current.page).toBe(0);
    expect(result.current.pageRows).toHaveLength(5);
    expect(result.current.hasNext).toBe(false);
  });
});

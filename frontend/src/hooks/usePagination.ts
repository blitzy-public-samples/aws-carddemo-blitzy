/**
 * :module: usePagination
 * :purpose: Reusable, side-effect-free pagination state hook that re-expresses
 *     the legacy BMS 3270 list paging as React state. The 3270 list screens
 *     browsed a fixed number of rows per screen and paged with PF7 (backward)
 *     and PF8 (forward); this hook reproduces that behaviour with client-side
 *     slicing over an in-memory item array. It is consumed by the card,
 *     transaction, and user list pages and drives the PF7/PF8 buttons rendered
 *     by the shared ``PFKeyBar`` component.
 * :note: Logic only — this module performs no I/O, has no side effects, and
 *     imports no UI. It is safe to import from Jest (jsdom) with zero
 *     environment evaluation. The per-screen row counts are centralized in the
 *     exported ``*_PAGE_SIZE`` constants below.
 */

import { useCallback, useMemo, useState } from 'react';

/**
 * :purpose: Rows displayed per page on the card list screen.
 * :note: Source-verified from ``app/cbl/COCRDLIC.cbl`` (screen ``COCRDLI``),
 *     where ``WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`` and
 *     ``WS-SCREEN-ROWS OCCURS 7 TIMES`` fix the card list at seven rows.
 */
export const CARD_LIST_PAGE_SIZE = 7;

/**
 * :purpose: Rows displayed per page on the transaction list screen.
 * :note: Source-verified from the ``COTRN00`` symbolic map
 *     (``app/cpy-bms/COTRN00.CPY`` / ``app/cbl/COTRN00C.cbl``), whose row
 *     fields run from ``SEL0001``/``TRNID01`` through ``SEL0010``/``TRNID10``,
 *     fixing the transaction list at ten rows.
 */
export const TRANSACTION_LIST_PAGE_SIZE = 10;

/**
 * :purpose: Rows displayed per page on the user list screen.
 * :note: Source-verified from ``app/cbl/COUSR00C.cbl`` (screen ``COUSR00``),
 *     where ``USER-REC OCCURS 10 TIMES`` fixes the user list at ten rows.
 */
export const USER_LIST_PAGE_SIZE = 10;

/**
 * :purpose: Return shape of :func:`usePagination`, exposing the current page
 *     slice together with the clamped navigation callbacks that back the
 *     PF7/PF8 controls.
 * :field page: zero-based index of the currently displayed page, already
 *     clamped into ``[0, totalPages - 1]``.
 * :field pageSize: number of rows per page (for example 7 for the card list,
 *     10 for the transaction and user lists).
 * :field pageRows: the items on the current page (a non-mutating slice of the
 *     input array, order preserved).
 * :field totalPages: total number of pages; always at least 1 (an empty list
 *     is one page with zero rows).
 * :field hasPrevious: ``true`` when a preceding page exists (PF7 enabled).
 * :field hasNext: ``true`` when a following page exists (PF8 enabled).
 * :field nextPage: PF8 action — advance one page, clamped at the last page.
 * :field prevPage: PF7 action — retreat one page, clamped at page 0.
 * :field goToPage: jump to an explicit page index, clamped into
 *     ``[0, totalPages - 1]``.
 * :field reset: return to page 0.
 */
export interface UsePaginationResult<T> {
  page: number;
  pageSize: number;
  pageRows: T[];
  totalPages: number;
  hasPrevious: boolean;
  hasNext: boolean;
  nextPage: () => void;
  prevPage: () => void;
  goToPage: (page: number) => void;
  reset: () => void;
}

/**
 * :purpose: Paginate an in-memory item array at a fixed page size, reproducing
 *     the BMS 3270 fixed-rows-per-screen paging with PF7 (backward) and PF8
 *     (forward) navigation. The effective page is clamped into range on every
 *     render, so a shrinking ``items`` array (for example after a filter) can
 *     never leave the current page out of range.
 * :param items: the full, ordered collection to paginate; sliced non-mutatively
 *     and never sorted or modified.
 * :param pageSize: rows per page — pass one of :data:`CARD_LIST_PAGE_SIZE`,
 *     :data:`TRANSACTION_LIST_PAGE_SIZE`, or :data:`USER_LIST_PAGE_SIZE`.
 * :param initialPage: zero-based starting page index; defaults to 0.
 * :returns: a :class:`UsePaginationResult` for the current page.
 * :note: The list REST endpoints back a server ``Page<T>`` (``{ items, page:
 *     PageInfo }`` from ``../types``). For CardDemo's small datasets a page may
 *     fetch the full result and paginate client-side at 7/10 to reproduce the
 *     3270 screen exactly, or pass the current server page's ``items`` and drive
 *     refetch by the ``page`` index; this hook always slices client-side and
 *     adds no server-mode branch.
 */
export function usePagination<T>(
  items: T[],
  pageSize: number,
  initialPage = 0,
): UsePaginationResult<T> {
  const [page, setPage] = useState(initialPage);

  // Total pages is never below 1: an empty list is a single page of zero rows.
  const totalPages = useMemo(
    () => Math.max(1, Math.ceil(items.length / pageSize)),
    [items.length, pageSize],
  );

  // Clamp the stored page into range for every derived output. Slicing and the
  // hasPrevious/hasNext flags use safePage so a shrunk item list stays valid
  // without an effect writing back to state.
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);

  const pageRows = useMemo(
    () => items.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [items, safePage, pageSize],
  );

  const hasPrevious = safePage > 0;
  const hasNext = safePage < totalPages - 1;

  // PF8 — page forward, clamped at the last (possibly partial) page.
  const nextPage = useCallback(
    () => setPage((p) => Math.min(p + 1, totalPages - 1)),
    [totalPages],
  );

  // PF7 — page backward, clamped at page 0.
  const prevPage = useCallback(() => setPage((p) => Math.max(p - 1, 0)), []);

  const goToPage = useCallback(
    (n: number) => setPage(() => Math.min(Math.max(n, 0), totalPages - 1)),
    [totalPages],
  );

  const reset = useCallback(() => setPage(0), []);

  return {
    page: safePage,
    pageSize,
    pageRows,
    totalPages,
    hasPrevious,
    hasNext,
    nextPage,
    prevPage,
    goToPage,
    reset,
  };
}

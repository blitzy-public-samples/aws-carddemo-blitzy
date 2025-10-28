/**
 * Custom React hook for managing table pagination
 * 
 * Converted from BMS repeating field patterns:
 * - COCRDLI.bms: 7-row card list display with PF7/PF8 navigation
 * - COTRN00.bms: 10-row transaction list display with PF7/PF8 navigation
 * 
 * This hook provides flexible pagination state management replacing mainframe
 * fixed-size OCCURS structures with configurable page sizes and rich navigation.
 * 
 * Key differences from COBOL/BMS approach:
 * - BMS: Fixed page size defined by OCCURS clause (e.g., OCCURS 7 TIMES)
 * - React: Flexible pageSize parameter allows dynamic sizing
 * - BMS: PF7/PF8 keys for prev/next navigation only
 * - React: Full navigation API (first, last, specific page, prev, next)
 * - BMS: One-indexed pages (1, 2, 3...)
 * - React: Zero-indexed internally (0, 1, 2...) for array slicing
 * 
 * @module hooks/usePagination
 */

import { useState, useMemo, useCallback, useEffect } from 'react';

/**
 * Pagination hook return type
 * 
 * Provides complete pagination state and navigation functions
 * for data tables and grids.
 */
export interface UsePaginationReturn {
  /** Current page number (zero-indexed) */
  currentPage: number;
  
  /** Number of items per page */
  pageSize: number;
  
  /** Total number of pages */
  totalPages: number;
  
  /** Total number of items */
  totalItems: number;
  
  /** Whether there is a previous page */
  hasPreviousPage: boolean;
  
  /** Whether there is a next page */
  hasNextPage: boolean;
  
  /** Navigate to specific page (zero-indexed) */
  goToPage: (page: number) => void;
  
  /** Navigate to next page */
  goToNextPage: () => void;
  
  /** Navigate to previous page */
  goToPreviousPage: () => void;
  
  /** Navigate to first page */
  goToFirstPage: () => void;
  
  /** Navigate to last page */
  goToLastPage: () => void;
  
  /** Get slice indices for current page [startIndex, endIndex) */
  getPageSlice: () => { startIndex: number; endIndex: number };
}

/**
 * Pagination hook configuration options
 */
export interface UsePaginationOptions {
  /** Number of items per page (default: 10) */
  pageSize?: number;
  
  /** Initial page number (zero-indexed, default: 0) */
  initialPage?: number;
}

/**
 * Custom React hook for managing table pagination
 * 
 * Provides pagination state management and navigation functions
 * for data tables and grids. Replaces BMS repeating field patterns
 * (e.g., COCRDLI 7-row display, COTRN00 10-row display).
 * 
 * COBOL/BMS Pagination Pattern:
 * ```cobol
 * * COCRDLI.cbl - Card List with 7-row display
 * 01  CARD-LIST-SCREEN.
 *     05  CARD-ROWS OCCURS 7 TIMES.
 *         10  CARD-NUM-O      PIC X(16).
 *         10  CARD-NAME-O     PIC X(25).
 *         10  CARD-STATUS-O   PIC X(10).
 * 
 * * PF7/PF8 key handling
 * IF EIBAID = DFHPF7
 *     SUBTRACT 1 FROM CURRENT-PAGE
 *     IF CURRENT-PAGE < 1
 *         MOVE 1 TO CURRENT-PAGE
 *     END-IF
 * END-IF.
 * 
 * IF EIBAID = DFHPF8
 *     ADD 1 TO CURRENT-PAGE
 *     IF CURRENT-PAGE > TOTAL-PAGES
 *         MOVE TOTAL-PAGES TO CURRENT-PAGE
 *     END-IF
 * END-IF.
 * 
 * * Calculate starting record
 * COMPUTE START-REC = ((CURRENT-PAGE - 1) * 7) + 1.
 * ```
 * 
 * @param totalItems - Total number of items to paginate
 * @param options - Pagination configuration options
 * @returns Pagination state and navigation functions
 * 
 * @example
 * // Basic usage with card list (COCRDLI.bms replacement)
 * const cards = [...]; // All cards from API
 * const {
 *   currentPage,
 *   pageSize,
 *   totalPages,
 *   hasNextPage,
 *   hasPreviousPage,
 *   goToNextPage,
 *   goToPreviousPage,
 *   getPageSlice
 * } = usePagination(cards.length, { pageSize: 7 });
 * 
 * const { startIndex, endIndex } = getPageSlice();
 * const visibleCards = cards.slice(startIndex, endIndex);
 * 
 * @example
 * // Transaction list with 10 items per page (COTRN00.bms replacement)
 * const transactions = [...];
 * const pagination = usePagination(transactions.length, { 
 *   pageSize: 10,
 *   initialPage: 0 
 * });
 * 
 * const visibleTransactions = transactions.slice(
 *   pagination.getPageSlice().startIndex,
 *   pagination.getPageSlice().endIndex
 * );
 * 
 * @example
 * // User list with configurable page size
 * const users = [...];
 * const [pageSize, setPageSize] = useState(10);
 * const pagination = usePagination(users.length, { pageSize });
 * 
 * const { startIndex, endIndex } = pagination.getPageSlice();
 * const visibleUsers = users.slice(startIndex, endIndex);
 */
export function usePagination(
  totalItems: number,
  options: UsePaginationOptions = {}
): UsePaginationReturn {
  const { pageSize = 10, initialPage = 0 } = options;
  
  // Current page state (zero-indexed)
  // Replaces COBOL CURRENT-PAGE variable (but zero-indexed instead of one-indexed)
  const [currentPage, setCurrentPage] = useState<number>(initialPage);
  
  // Calculate total pages
  // Equivalent to COBOL: COMPUTE TOTAL-PAGES = (TOTAL-ITEMS + PAGE-SIZE - 1) / PAGE-SIZE
  const totalPages = useMemo(() => {
    if (totalItems === 0) {
      return 0;
    }
    return Math.ceil(totalItems / pageSize);
  }, [totalItems, pageSize]);
  
  // Calculate pagination flags
  // Replaces COBOL: IF CURRENT-PAGE > 1 ... END-IF
  const hasPreviousPage = useMemo(() => {
    return currentPage > 0;
  }, [currentPage]);
  
  // Replaces COBOL: IF CURRENT-PAGE < TOTAL-PAGES ... END-IF
  const hasNextPage = useMemo(() => {
    return currentPage < totalPages - 1 && totalPages > 0;
  }, [currentPage, totalPages]);
  
  // Navigate to specific page
  // Replaces COBOL: MOVE page TO CURRENT-PAGE with bounds checking
  const goToPage = useCallback((page: number) => {
    // Ensure page is within valid range [0, totalPages)
    // COBOL equivalent: IF page < 0 MOVE 0 TO page. IF page >= TOTAL-PAGES MOVE TOTAL-PAGES - 1 TO page.
    const validPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
    setCurrentPage(validPage);
  }, [totalPages]);
  
  // Navigate to next page
  // Replaces COBOL: IF EIBAID = DFHPF8 ADD 1 TO CURRENT-PAGE END-IF
  const goToNextPage = useCallback(() => {
    if (hasNextPage) {
      setCurrentPage(prev => prev + 1);
    }
  }, [hasNextPage]);
  
  // Navigate to previous page
  // Replaces COBOL: IF EIBAID = DFHPF7 SUBTRACT 1 FROM CURRENT-PAGE END-IF
  const goToPreviousPage = useCallback(() => {
    if (hasPreviousPage) {
      setCurrentPage(prev => prev - 1);
    }
  }, [hasPreviousPage]);
  
  // Navigate to first page
  // No direct COBOL equivalent (adds convenience navigation)
  const goToFirstPage = useCallback(() => {
    setCurrentPage(0);
  }, []);
  
  // Navigate to last page
  // No direct COBOL equivalent (adds convenience navigation)
  const goToLastPage = useCallback(() => {
    setCurrentPage(Math.max(0, totalPages - 1));
  }, [totalPages]);
  
  // Get slice indices for current page
  // Replaces COBOL: COMPUTE START-REC = ((CURRENT-PAGE - 1) * PAGE-SIZE) + 1
  // Note: COBOL uses 1-indexed records, JavaScript uses 0-indexed arrays
  const getPageSlice = useCallback(() => {
    const startIndex = currentPage * pageSize;
    const endIndex = Math.min(startIndex + pageSize, totalItems);
    return { startIndex, endIndex };
  }, [currentPage, pageSize, totalItems]);
  
  // Reset to first page when total items changes significantly
  // (e.g., after applying filters)
  // This prevents showing an empty page when filters reduce the result set
  // COBOL equivalent: IF CURRENT-PAGE > TOTAL-PAGES MOVE 1 TO CURRENT-PAGE END-IF
  useEffect(() => {
    // Reset if current page is out of bounds OR if data becomes empty
    if (currentPage > 0 && (totalPages === 0 || currentPage >= totalPages)) {
      setCurrentPage(0);
    }
  }, [totalItems, totalPages, currentPage]);
  
  return {
    currentPage,
    pageSize,
    totalPages,
    totalItems,
    hasPreviousPage,
    hasNextPage,
    goToPage,
    goToNextPage,
    goToPreviousPage,
    goToFirstPage,
    goToLastPage,
    getPageSlice
  };
}

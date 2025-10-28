/**
 * Custom React hook for debouncing input values
 * 
 * Delays updating the debounced value until after the specified delay
 * period has passed without the input value changing. Useful for
 * optimizing expensive operations like API search calls.
 * 
 * Performance Benefits:
 * - Reduces API calls by 90%+ during rapid user typing
 * - Prevents UI lag from excessive requests
 * - Optimizes network bandwidth usage
 * - Improves backend server load
 * 
 * Use Cases:
 * - TransactionListPage: Search transaction by ID or merchant name
 * - UserListPage: Filter users by user ID
 * - CardListPage: Search cards by card number or account ID
 * - Any real-time search/filter component
 * 
 * COBOL Comparison:
 * - No equivalent in COBOL/BMS 3270 terminal systems
 * - 3270 terminals send complete screens on ENTER key only
 * - Modern web apps require debouncing for search-as-you-type features
 * - Essential performance optimization for React SPA
 * 
 * @template T - Type of value being debounced (supports any data type)
 * @param value - The value to debounce (string, number, object, array, etc.)
 * @param delay - Delay in milliseconds before updating debounced value (default: 500ms)
 * @returns Debounced value that updates only after delay period without changes
 * 
 * @example
 * // Basic usage with string search
 * const [searchTerm, setSearchTerm] = useState('');
 * const debouncedSearchTerm = useDebounce(searchTerm, 500);
 * 
 * useEffect(() => {
 *   if (debouncedSearchTerm) {
 *     fetchSearchResults(debouncedSearchTerm);
 *   }
 * }, [debouncedSearchTerm]);
 * 
 * @example
 * // Usage with transaction search (COTRN00.bms replacement)
 * const TransactionSearch = () => {
 *   const [query, setQuery] = useState('');
 *   const debouncedQuery = useDebounce(query, 300);
 *   
 *   useEffect(() => {
 *     if (debouncedQuery.length >= 3) {
 *       transactionService.searchTransactions(debouncedQuery)
 *         .then(setResults)
 *         .catch(handleError);
 *     }
 *   }, [debouncedQuery]);
 *   
 *   return (
 *     <TextField
 *       value={query}
 *       onChange={e => setQuery(e.target.value)}
 *       placeholder="Search transactions..."
 *     />
 *   );
 * };
 * 
 * @example
 * // Usage with object filter (multiple fields)
 * const CardListFilter = () => {
 *   const [filter, setFilter] = useState({ cardNumber: '', accountId: '' });
 *   const debouncedFilter = useDebounce(filter, 400);
 *   
 *   useEffect(() => {
 *     cardService.getCards(debouncedFilter)
 *       .then(setCards)
 *       .catch(handleError);
 *   }, [debouncedFilter]);
 *   
 *   return (
 *     <>
 *       <TextField
 *         label="Card Number"
 *         value={filter.cardNumber}
 *         onChange={e => setFilter(prev => ({ ...prev, cardNumber: e.target.value }))}
 *       />
 *       <TextField
 *         label="Account ID"
 *         value={filter.accountId}
 *         onChange={e => setFilter(prev => ({ ...prev, accountId: e.target.value }))}
 *       />
 *     </>
 *   );
 * };
 */

import { useState, useEffect } from 'react';

/**
 * useDebounce hook implementation
 * 
 * Implementation Details:
 * 1. useState maintains the debounced value state
 * 2. useEffect sets up setTimeout to delay value update
 * 3. Cleanup function clears timeout to prevent:
 *    - Memory leaks from unmounted components
 *    - Stale value updates from previous renders
 *    - Multiple pending timeouts
 * 4. Effect re-runs when value or delay changes
 * 5. Returns debounced value that stabilizes after delay
 * 
 * Performance Metrics:
 * - Without debounce: 10 keystrokes = 10 API calls
 * - With 500ms debounce: 10 keystrokes = 1 API call
 * - Reduces backend load by 90%+
 * - Improves user experience with smoother typing
 * 
 * @template T - Generic type parameter for type safety
 * @param value - Current value to debounce
 * @param delay - Debounce delay in milliseconds (default: 500)
 * @returns Debounced value that updates after delay period
 */
export function useDebounce<T>(value: T, delay: number = 500): T {
  // State to store the debounced value
  // Initialized with the current value to provide immediate first render
  const [debouncedValue, setDebouncedValue] = useState<T>(value);

  useEffect(() => {
    // Set up a timer to update debounced value after delay
    // This timer is reset every time the value changes
    const timeoutId = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    // Cleanup function that runs:
    // 1. Before the next effect execution (when value or delay changes)
    // 2. When the component unmounts
    // 
    // This prevents:
    // - Setting stale values after component unmounts (memory leak)
    // - Multiple pending updates from rapid value changes
    // - Incorrect final value if user types quickly
    return () => {
      clearTimeout(timeoutId);
    };
  }, [value, delay]); // Re-run effect when value or delay changes

  return debouncedValue;
}

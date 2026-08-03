/**
 * :module: ``frontend/src/hooks/index.ts``
 * :purpose: Barrel re-export of CardDemo custom React hooks. Gives the page and
 *   component modules a single import specifier (``../hooks`` / ``../../hooks``)
 *   for the session, list-pagination, and request-state hooks rather than a deep
 *   import per sibling module.
 * :output: The merged named-export namespace of the three sibling hook modules:
 *   ``useSession`` with its ``UseSessionResult`` contract; ``usePagination`` with
 *   its ``UsePaginationResult`` contract and the ``CARD_LIST_PAGE_SIZE``,
 *   ``TRANSACTION_LIST_PAGE_SIZE`` and ``USER_LIST_PAGE_SIZE`` row counts; and
 *   ``useApi`` with its ``UseApiResult`` contract. Every exported name across
 *   those modules is unique, so the wildcard re-exports combine into one
 *   collision-free namespace.
 * :note: Pure ES-module re-export file — no runtime logic, no default export, no
 *   side effects, and no ``import.meta`` — so it is safe to import from Jest
 *   (jsdom) without triggering any environment evaluation.
 */

export * from './useSession';
export * from './usePagination';
export * from './useApi';

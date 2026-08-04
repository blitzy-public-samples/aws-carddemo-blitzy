/**
 * :module: ``frontend/src/hooks/index.ts``
 * :purpose: Barrel entry point for the CardDemo custom React hook layer.
 *   Aggregates the three hook modules — ``useApi`` (REST call lifecycle),
 *   ``usePagination`` (BMS PF7/PF8 paging with the per-screen page sizes), and
 *   ``useSession`` (the provider-free authenticated user / role store) — so
 *   consumers (components and pages) import from a single specifier
 *   (``../hooks``) rather than reaching into individual modules.
 * :output: The merged named-export namespace of the three sibling modules: the
 *   ``useApi``, ``usePagination``, and ``useSession`` hooks together with their
 *   ``UseApiResult``, ``UsePaginationResult``, and ``UseSessionResult`` return
 *   contracts and the ``CARD_LIST_PAGE_SIZE`` / ``TRANSACTION_LIST_PAGE_SIZE`` /
 *   ``USER_LIST_PAGE_SIZE`` page-size constants. Every exported name across those
 *   modules is unique, so the wildcard re-exports combine into one collision-free
 *   namespace.
 * :note: Pure ES-module re-export file — no runtime logic, no default export, and
 *   no side effects beyond the re-exported modules' own initialization.
 */

export * from './useApi';
export * from './usePagination';
export * from './useSession';

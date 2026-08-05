/**
 * :module: ``frontend/src/hooks/index.ts``
 * :purpose: Barrel entry point for the CardDemo custom React hook layer.
 *   Aggregates the hook modules — ``useApi`` (REST call lifecycle),
 *   ``useScreenFocus`` (BMS cursor placement), and ``useSession`` (the
 *   provider-free authenticated user / role store) — so consumers (components and
 *   pages) import from a single specifier (``../hooks``) rather than reaching into
 *   individual modules.
 * :output: The merged named-export namespace of the sibling modules: the ``useApi``,
 *   ``useInitialFocus``, ``useFocusOnChange``, ``useFocusOnSettled`` and ``useSession``
 *   hooks together with their ``UseApiResult`` and ``UseSessionResult`` return
 *   contracts.
 * :note: ``useSession`` is re-exported by name, not by wildcard: the barrel is the
 *   application-facing surface and must expose only the hook and its return
 *   contract, never any store mutator.
 * :note: Pure ES-module re-export file — no runtime logic, no default export, and
 *   no side effects beyond the re-exported modules' own initialization.
 */

export * from './useApi';
export * from './useScreenFocus';
export { useSession } from './useSession';
export type { UseSessionResult } from './useSession';

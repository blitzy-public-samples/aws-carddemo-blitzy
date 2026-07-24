/**
 * :module: ``frontend/src/types/index.ts``
 * :purpose: Barrel entry point that aggregates and re-exports the complete
 *   public type surface of the CardDemo single-page-application type layer, so
 *   consumers import every shared primitive, session type, and request/response
 *   DTO from the single specifier ``../types`` (for example
 *   ``import { SessionContext, Role, SignonRequestDto, AccountViewResponseDto }
 *   from '../types'``) instead of reaching into each sibling module. Gives the
 *   ``api``, ``hooks``, ``components``, and ``pages`` folders one stable,
 *   ergonomic import path per the AAP ES-module convention.
 * :output: The merged named-export namespace of the ten sibling modules
 *   (``common``, ``session``, ``auth``, ``menu``, ``account``, ``card``,
 *   ``transaction``, ``billpay``, ``report``, ``user``). Every exported name
 *   across those modules is unique, so the wildcard re-exports combine into one
 *   collision-free namespace.
 * :note: Pure ES-module re-export file — no runtime logic, no default export,
 *   and no side effects.
 */

export * from './common';
export * from './session';
export * from './auth';
export * from './menu';
export * from './account';
export * from './card';
export * from './transaction';
export * from './billpay';
export * from './report';
export * from './user';

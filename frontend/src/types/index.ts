/**
 * Barrel module for the CardDemo frontend wire types.
 *
 * Aggregates the per-domain DTO/enum/constant modules under `src/types` into a
 * single public entry point so consumers can `import { ... } from '@/types'`
 * (the `@/*` -> `./src/*` path alias defined in tsconfig.json) instead of
 * reaching into individual files. Each re-exported module mirrors its matching
 * backend Pydantic schema; see the per-module headers for legacy copybook /
 * screen references.
 *
 * There are no name collisions across the domain modules, so a flat
 * `export *` surface is unambiguous.
 */

export * from './account';
export * from './auth';
export * from './billpay';
export * from './card';
export * from './cardXref';
export * from './common';
export * from './customer';
export * from './disclosureGroup';
export * from './menu';
export * from './report';
export * from './tranCategoryBalance';
export * from './transaction';
export * from './transactionCategory';
export * from './transactionType';
export * from './user';

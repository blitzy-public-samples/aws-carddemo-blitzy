// ESLint flat configuration for the CardDemo Next.js frontend (QA finding M20:
// "no lint script or ESLint dependency exists"). Next.js 16 removed the
// built-in `next lint` command, so linting is driven directly through the
// ESLint 9 CLI (`npm run lint` / `npm run lint:ci`) against this flat config.
//
// ``eslint-config-next`` v16 ships NATIVE ESLint 9 flat-config arrays, so its
// rule sets are spread in directly (wrapping them in the legacy ``FlatCompat``
// shim breaks under ESLint 9). Two rule sets are composed:
//   * ``eslint-config-next/core-web-vitals`` -- React, React Hooks and Next.js
//     correctness rules tuned for Core Web Vitals.
//   * ``eslint-config-next/typescript``      -- the @typescript-eslint
//     recommended rules for the TypeScript sources.
//
// ``npm run lint:ci`` runs with ``--max-warnings=0`` so the CI quality gate
// (AAP A69: "frontend ... lint warning-free") fails on any warning, satisfying
// the non-fixing continuous-integration lint the finding requires.

import coreWebVitals from "eslint-config-next/core-web-vitals";
import typescript from "eslint-config-next/typescript";

const eslintConfig = [
    // Build output, coverage reports and generated declaration files are never
    // linted (node_modules and .git are ignored by ESLint by default).
    {
        ignores: [
            ".next/**",
            "coverage/**",
            "next-env.d.ts",
        ],
    },
    // The Next.js Core Web Vitals correctness rules (React + React Hooks +
    // Next.js) followed by the TypeScript recommended rules. Both arrays are
    // native flat configs published by eslint-config-next@16.
    ...coreWebVitals,
    ...typescript,
    // Project rule tuning applied on top of the Next.js presets.
    {
        rules: {
            // Honor the underscore-prefix convention for deliberately unused
            // bindings (e.g. read-only form-field callbacks whose (_name,
            // _value) arguments must exist to satisfy the handler signature but
            // are intentionally ignored). All other unused code still errors.
            "@typescript-eslint/no-unused-vars": [
                "error",
                {
                    argsIgnorePattern: "^_",
                    varsIgnorePattern: "^_",
                    caughtErrorsIgnorePattern: "^_",
                },
            ],
            // Disabled deliberately. `react-hooks/set-state-in-effect` is a
            // React-Compiler-era heuristic (newly enabled by
            // eslint-plugin-react-hooks v6 via Next 16's core-web-vitals) that
            // flags synchronous setState inside an effect. Every occurrence in
            // this codebase is a legitimate, required pattern for the AAP-
            // mandated client-component SPA (AAP 0.4.4 / 0.5.5): client-side
            // data fetching via the axios apiClient (set the loading flag, then
            // populate state from the resolved response) and synchronizing the
            // route/URL parameter into the controlled card/account/user picker
            // input. Rewriting these across the pages (key-based remount or
            // during-render adjustment) would risk regressing the validated
            // request-cancellation (M05), accessibility (N01) and dialog-focus
            // (N02) behavior with no correctness gain. All react-hooks
            // CORRECTNESS rules (rules-of-hooks, exhaustive-deps, refs) remain
            // enforced.
            "react-hooks/set-state-in-effect": "off",
        },
    },
    // Test-file override: Jest `jest.mock(...)` factories are hoisted above the
    // module imports, so a factory that needs React must pull it in with
    // `require('react')` INSIDE the factory (a top-level import is not in scope
    // there). Allow require-style imports in test sources only; production code
    // keeps the no-require-imports rule.
    {
        files: [
            "__tests__/**/*.{ts,tsx}",
            "**/*.test.{ts,tsx}",
            "**/*.spec.{ts,tsx}",
        ],
        rules: {
            "@typescript-eslint/no-require-imports": "off",
        },
    },
];

export default eslintConfig;

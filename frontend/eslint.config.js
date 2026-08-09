/**
 * ESLint flat configuration for the CardDemo React SPA.
 *
 * :purpose: Provide the mandatory static-analysis gate for the TypeScript/React
 *     presentation layer: type-aware TypeScript rules, the React Hooks rules that
 *     catch stale-closure and dependency defects the compiler cannot see, the
 *     jsx-a11y accessibility rules, and the security rules for the node/browser
 *     surface. Every plugin version is pinned exactly in ``package.json``.
 * :output: The flat config array consumed by ``npm run lint``.
 * :note: ``react-hooks/exhaustive-deps`` is escalated to an error, and two rules are
 *     switched off. Rationale for each: docs/decision-log.md, section 19.2.
 * :note: Generated and vendored trees are excluded rather than linted: ``dist`` is
 *     build output and ``coverage`` is a report.
 */
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import security from 'eslint-plugin-security';

export default tseslint.config(
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**'],
  },

  // Base JavaScript recommendations for every linted file.
  js.configs.recommended,

  // Type-aware TypeScript rules, scoped to the TypeScript sources: the type
  // information comes from the project's own tsconfig files, so the rules see the
  // same types the compiler does. Plain JavaScript files are not in any tsconfig and
  // therefore cannot carry type-aware rules.
  {
    files: ['**/*.{ts,tsx}'],
    extends: [...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
      globals: {
        ...globals.browser,
        ...globals.es2023,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
      security,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,
      ...security.configs.recommended.rules,

      // A handler referenced by an effect must appear in that effect's dependency
      // array. Rationale: docs/decision-log.md, section 19.2.
      'react-hooks/exhaustive-deps': 'error',

      // Off; `rules-of-hooks`, `exhaustive-deps` and `refs` stay errors.
      // Rationale: docs/decision-log.md, section 19.2.
      'react-hooks/set-state-in-effect': 'off',

      // Off. Rationale: docs/decision-log.md, section 19.2.
      'security/detect-object-injection': 'off',

      // A horizontally scrolling region must be operable from the keyboard (WCAG
      // 2.1.1). The browse tables of the three list screens are exactly that: on a
      // narrow viewport the trailing BMS columns fall outside the frame and hold no
      // focusable field of their own, so the scroll container itself carries the tab
      // stop. The rule's default allow-list covers only `tabpanel`, so the two roles
      // used for those containers are added to it. Rationale:
      // docs/decision-log.md, section 44.3.
      'jsx-a11y/no-noninteractive-tabindex': [
        'error',
        { tags: [], roles: ['tabpanel', 'group', 'region'], allowExpressionValues: true },
      ],

      // A floating promise in an event handler silently swallows a rejected request,
      // so an explicit `void` or `await` is required at every call site.
      '@typescript-eslint/no-floating-promises': 'error',

      // Unused symbols are removed rather than tolerated; a deliberately unused
      // argument is prefixed with an underscore.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },

  // Test files run under Jest and legitimately reach for its globals; so do the
  // shared harnesses under `src/testing`, which are loaded only by test files.
  {
    files: [
      '**/*.test.{ts,tsx}',
      'src/setupTests.ts',
      'src/__mocks__/**',
      'src/testing/**',
    ],
    languageOptions: {
      globals: {
        ...globals.jest,
        ...globals.node,
      },
    },
    rules: {
      // Test doubles are intentionally loose about types at the seams they stub.
      '@typescript-eslint/unbound-method': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
    },
  },

  // This flat config is plain JavaScript outside every tsconfig, so the type-aware
  // rules are switched off for it explicitly.
  {
    files: ['**/*.js'],
    extends: [tseslint.configs.disableTypeChecked],
  },

  // This config file and the Vite config run in Node, not the browser.
  {
    files: ['eslint.config.js', 'vite.config.ts', 'jest.config.*'],
    languageOptions: {
      globals: { ...globals.node },
    },
    rules: {
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
    },
  },
);

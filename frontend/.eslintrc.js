/**
 * ESLint Configuration for OCR Processing Application Frontend
 *
 * Enforces code quality rules, best practices, and style guidelines for
 * JavaScript and TypeScript code in the Next.js/React application.
 *
 * Requirements:
 * - Code Quality Standards (Section 0.7.1): ALL code MUST pass ESLint with zero warnings
 * - TypeScript 5.3+ with strict mode (Section 0.1.2)
 * - WCAG 2.1 AA accessibility compliance (Section 0.7.10)
 * - React 18.3.1 and Next.js 14.2.21 best practices
 *
 * @see docs/DEVELOPMENT.md for development guidelines
 */

module.exports = {
  root: true,

  // Specify parser for TypeScript
  parser: '@typescript-eslint/parser',

  // Parser options for TypeScript and modern JavaScript
  parserOptions: {
    ecmaVersion: 2021,
    sourceType: 'module',
    ecmaFeatures: {
      jsx: true,
    },
    // Enable type-aware linting rules
    project: './tsconfig.json',
    tsconfigRootDir: __dirname,
  },

  // Environment definitions
  env: {
    browser: true,
    es2021: true,
    node: true,
    jest: true,
  },

  // Extend recommended configurations
  extends: [
    // Next.js core web vitals (includes react, react-hooks, jsx-a11y, next)
    'next/core-web-vitals',
    // TypeScript recommended rules
    'plugin:@typescript-eslint/recommended',
    // TypeScript type-aware recommended rules
    'plugin:@typescript-eslint/recommended-requiring-type-checking',
    // Prettier integration (must be last to override other configs)
    'plugin:prettier/recommended',
  ],

  // ESLint plugins (next/core-web-vitals already includes react, react-hooks, jsx-a11y)
  plugins: ['@typescript-eslint'],

  // React version detection
  settings: {
    react: {
      version: 'detect',
    },
  },

  // Custom rules configuration
  rules: {
    // ============================================================================
    // TYPESCRIPT RULES (Section 0.7.1)
    // ============================================================================

    // Enforce explicit function return types for exported functions
    '@typescript-eslint/explicit-module-boundary-types': 'warn',

    // Disallow 'any' type without justification (Section 0.7.1)
    '@typescript-eslint/no-explicit-any': 'error',

    // Enforce type definitions over interfaces where appropriate
    '@typescript-eslint/consistent-type-definitions': ['warn', 'interface'],

    // Require explicit return types on functions
    '@typescript-eslint/explicit-function-return-type': [
      'warn',
      {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
      },
    ],

    // Disallow unused variables (except those prefixed with _)
    '@typescript-eslint/no-unused-vars': [
      'error',
      {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      },
    ],

    // Enforce consistent use of type imports
    '@typescript-eslint/consistent-type-imports': [
      'warn',
      {
        prefer: 'type-imports',
        disallowTypeAnnotations: false,
      },
    ],

    // Disallow floating promises (must be awaited or handled)
    '@typescript-eslint/no-floating-promises': 'error',

    // Enforce proper promise handling
    '@typescript-eslint/no-misused-promises': [
      'error',
      {
        checksVoidReturn: false,
      },
    ],

    // Require await in async functions
    '@typescript-eslint/require-await': 'warn',

    // Disallow unnecessary type assertions
    '@typescript-eslint/no-unnecessary-type-assertion': 'warn',

    // Prefer nullish coalescing over logical or
    '@typescript-eslint/prefer-nullish-coalescing': 'warn',

    // Prefer optional chaining
    '@typescript-eslint/prefer-optional-chain': 'warn',

    // ============================================================================
    // REACT RULES (Section 0.7.2)
    // ============================================================================

    // Disable prop-types as we use TypeScript
    'react/prop-types': 'off',

    // Disable React in JSX scope (not needed in React 18+)
    'react/react-in-jsx-scope': 'off',

    // Enforce consistent function component declaration
    'react/function-component-definition': [
      'warn',
      {
        namedComponents: 'arrow-function',
        unnamedComponents: 'arrow-function',
      },
    ],

    // Enforce self-closing components
    'react/self-closing-comp': 'warn',

    // Disallow missing key prop in iterators
    'react/jsx-key': [
      'error',
      {
        checkFragmentShorthand: true,
      },
    ],

    // Disallow target="_blank" without rel="noopener noreferrer"
    'react/jsx-no-target-blank': 'error',

    // Enforce boolean attributes notation
    'react/jsx-boolean-value': ['warn', 'never'],

    // Enforce consistent JSX quotes
    'jsx-quotes': ['warn', 'prefer-double'],

    // Disallow unnecessary fragments
    'react/jsx-no-useless-fragment': 'warn',

    // ============================================================================
    // REACT HOOKS RULES (Section 0.7.1)
    // ============================================================================

    // Enforce Rules of Hooks
    'react-hooks/rules-of-hooks': 'error',

    // Enforce exhaustive dependencies
    'react-hooks/exhaustive-deps': 'warn',

    // ============================================================================
    // ACCESSIBILITY RULES (Section 0.7.10 - WCAG 2.1 AA Compliance)
    // ============================================================================

    // Enforce alt text on images
    'jsx-a11y/alt-text': [
      'error',
      {
        elements: ['img', 'object', 'area', 'input[type="image"]'],
        img: ['Image'],
        object: ['Object'],
        area: ['Area'],
        'input[type="image"]': ['InputImage'],
      },
    ],

    // Enforce ARIA props are valid
    'jsx-a11y/aria-props': 'error',

    // Enforce ARIA prop values are valid
    'jsx-a11y/aria-proptypes': 'error',

    // Enforce ARIA roles are valid
    'jsx-a11y/aria-role': 'error',

    // Enforce ARIA unsupported elements don't have ARIA attributes
    'jsx-a11y/aria-unsupported-elements': 'error',

    // Enforce anchor elements have content
    'jsx-a11y/anchor-has-content': 'error',

    // Enforce anchor elements with href are valid
    'jsx-a11y/anchor-is-valid': [
      'error',
      {
        components: ['Link'],
        specialLink: ['hrefLeft', 'hrefRight'],
        aspects: ['invalidHref', 'preferButton'],
      },
    ],

    // Enforce click events have keyboard events
    'jsx-a11y/click-events-have-key-events': 'warn',

    // Enforce form controls have labels
    'jsx-a11y/label-has-associated-control': [
      'error',
      {
        required: {
          some: ['nesting', 'id'],
        },
      },
    ],

    // Enforce interactive elements support keyboard interaction
    'jsx-a11y/interactive-supports-focus': 'warn',

    // Enforce media elements have captions
    'jsx-a11y/media-has-caption': 'warn',

    // Enforce mouse events have keyboard equivalents
    'jsx-a11y/mouse-events-have-key-events': 'warn',

    // Enforce no access key attribute
    'jsx-a11y/no-access-key': 'error',

    // Enforce no autofocus
    'jsx-a11y/no-autofocus': 'warn',

    // Enforce heading elements have content
    'jsx-a11y/heading-has-content': 'error',

    // ============================================================================
    // CODE QUALITY AND BEST PRACTICES (Section 0.7.1)
    // ============================================================================

    // Disallow console.log in production (Section 0.7.1)
    'no-console': [
      'warn',
      {
        allow: ['warn', 'error', 'info'],
      },
    ],

    // Disallow debugger statements
    'no-debugger': 'error',

    // Disallow alert, confirm, prompt
    'no-alert': 'warn',

    // Enforce consistent naming convention
    '@typescript-eslint/naming-convention': [
      'warn',
      {
        selector: 'variable',
        format: ['camelCase', 'PascalCase', 'UPPER_CASE'],
        leadingUnderscore: 'allow',
      },
      {
        selector: 'function',
        format: ['camelCase', 'PascalCase'],
      },
      {
        selector: 'typeLike',
        format: ['PascalCase'],
      },
      {
        selector: 'interface',
        format: ['PascalCase'],
        custom: {
          regex: '^I[A-Z]',
          match: false,
        },
      },
    ],

    // Enforce no duplicate imports
    'no-duplicate-imports': 'error',

    // Enforce proper import order (handled by prettier, but as fallback)
    'sort-imports': [
      'warn',
      {
        ignoreCase: true,
        ignoreDeclarationSort: true,
        ignoreMemberSort: false,
      },
    ],

    // Disallow empty catch blocks (Section 0.7.1)
    'no-empty': [
      'error',
      {
        allowEmptyCatch: false,
      },
    ],

    // Enforce proper error handling
    'no-throw-literal': 'error',

    // Disallow eval()
    'no-eval': 'error',

    // Disallow implied eval
    'no-implied-eval': 'error',

    // Disallow use of Object constructor
    'no-new-object': 'error',

    // Disallow Array constructor with single number argument
    'no-array-constructor': 'error',

    // Enforce proper use of semicolons
    '@typescript-eslint/semi': ['warn', 'always'],

    // Enforce consistent quote style
    '@typescript-eslint/quotes': [
      'warn',
      'single',
      {
        avoidEscape: true,
        allowTemplateLiterals: true,
      },
    ],

    // Enforce proper indentation (handled by Prettier)
    indent: 'off',
    '@typescript-eslint/indent': 'off',

    // Enforce proper line breaks (handled by Prettier)
    'linebreak-style': 'off',

    // Enforce maximum line length
    'max-len': [
      'warn',
      {
        code: 120,
        ignoreUrls: true,
        ignoreStrings: true,
        ignoreTemplateLiterals: true,
        ignoreRegExpLiterals: true,
        ignoreComments: true,
      },
    ],

    // Enforce no magic numbers without explanation
    '@typescript-eslint/no-magic-numbers': [
      'warn',
      {
        ignore: [-1, 0, 1, 2, 10, 100, 1000],
        ignoreArrayIndexes: true,
        ignoreDefaultValues: true,
        ignoreEnums: true,
        ignoreNumericLiteralTypes: true,
        ignoreReadonlyClassProperties: true,
      },
    ],

    // Enforce proper comments for complex logic
    'spaced-comment': [
      'warn',
      'always',
      {
        markers: ['/'],
      },
    ],

    // Require JSDoc comments for public functions (Section 0.7.1)
    'require-jsdoc': 'off', // Using TypeScript types instead

    // Enforce no TODO comments without ticket numbers (Section 0.7.1)
    'no-warning-comments': [
      'warn',
      {
        terms: ['TODO', 'FIXME', 'XXX'],
        location: 'start',
      },
    ],

    // ============================================================================
    // PRETTIER INTEGRATION
    // ============================================================================

    // Run Prettier as an ESLint rule
    'prettier/prettier': [
      'warn',
      {
        singleQuote: true,
        semi: true,
        trailingComma: 'es5',
        tabWidth: 2,
        printWidth: 100,
        arrowParens: 'always',
        endOfLine: 'lf',
      },
    ],
  },

  // Override rules for specific file patterns
  overrides: [
    {
      // Relax rules for test files
      files: [
        '**/__tests__/**/*',
        '**/*.test.ts',
        '**/*.test.tsx',
        '**/*.spec.ts',
        '**/*.spec.tsx',
      ],
      rules: {
        '@typescript-eslint/no-explicit-any': 'warn',
        '@typescript-eslint/no-magic-numbers': 'off',
        'no-console': 'off',
      },
    },
    {
      // Relax rules for configuration files
      files: ['*.config.js', '*.config.ts', '.eslintrc.js'],
      rules: {
        '@typescript-eslint/no-var-requires': 'off',
        '@typescript-eslint/no-magic-numbers': 'off',
      },
    },
    {
      // Relax rules for Next.js API routes
      files: ['pages/api/**/*'],
      rules: {
        '@typescript-eslint/explicit-module-boundary-types': 'off',
      },
    },
    {
      // Relax rules for Next.js pages
      files: ['pages/**/*'],
      rules: {
        'react/function-component-definition': 'off',
      },
    },
  ],

  // Ignore patterns
  ignorePatterns: [
    'node_modules/',
    '.next/',
    'out/',
    'build/',
    'dist/',
    'coverage/',
    '*.min.js',
    'public/',
    '.turbo/',
  ],
};

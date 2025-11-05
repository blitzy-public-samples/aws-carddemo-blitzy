/**
 * ESLint Configuration for OCR Processing Application
 * 
 * This configuration enforces code quality standards across the entire monorepo,
 * including frontend (React/Next.js) and backend (NestJS) TypeScript code.
 * 
 * Key Features:
 * - TypeScript support with strict type checking
 * - React and React Hooks best practices
 * - Next.js optimization rules
 * - NestJS patterns support
 * - Prettier integration (disables conflicting rules)
 * - Zero warnings policy for production code
 * 
 * @see https://eslint.org/docs/latest/user-guide/configuring/
 * @see https://typescript-eslint.io/
 */

module.exports = {
  // Use @typescript-eslint/parser for TypeScript file parsing
  parser: '@typescript-eslint/parser',
  
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: 'module',
    ecmaFeatures: {
      jsx: true,
    },
    // Project-aware TypeScript linting (improves type checking)
    project: ['./tsconfig.json', './frontend/tsconfig.json', './backend/tsconfig.json'],
  },

  // Extend recommended configurations
  extends: [
    // Base ESLint recommended rules
    'eslint:recommended',
    
    // TypeScript-specific recommended rules
    'plugin:@typescript-eslint/recommended',
    'plugin:@typescript-eslint/recommended-requiring-type-checking',
    
    // React best practices
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    
    // React Hooks rules
    'plugin:react-hooks/recommended',
    
    // Next.js specific rules (includes Core Web Vitals)
    'next/core-web-vitals',
    
    // Security best practices
    'plugin:security/recommended',
    
    // Accessibility rules (WCAG compliance)
    'plugin:jsx-a11y/recommended',
    
    // Prettier integration - MUST be last to disable conflicting rules
    'prettier',
  ],

  // Load plugins for additional rules
  plugins: [
    '@typescript-eslint',
    'react',
    'react-hooks',
    'jsx-a11y',
    'security',
    'import',
  ],

  // Define runtime environments
  env: {
    browser: true,    // Frontend environment
    node: true,       // Backend environment
    es2022: true,     // Modern JavaScript features
    jest: true,       // Testing environment
  },

  // Global variables
  globals: {
    NodeJS: true,
    JSX: true,
  },

  // Custom rule configurations
  rules: {
    // ========================================
    // General Code Quality Rules
    // ========================================
    
    // Warn on console usage (should use proper logging in production)
    'no-console': ['warn', { allow: ['warn', 'error'] }],
    
    // Disallow debugger statements in production
    'no-debugger': 'error',
    
    // Disallow alert, confirm, prompt
    'no-alert': 'error',
    
    // Require use of === and !==
    'eqeqeq': ['error', 'always'],
    
    // Disallow empty functions (except constructors and methods)
    'no-empty-function': ['error', { allow: ['constructors', 'methods'] }],
    
    // Disallow unnecessary template literals
    'no-useless-template-literals': 'error',
    
    // Prefer const over let when variable is never reassigned
    'prefer-const': 'error',
    
    // Disallow var declarations
    'no-var': 'error',
    
    // Require object literal shorthand syntax
    'object-shorthand': ['error', 'always'],
    
    // Require arrow functions as callbacks
    'prefer-arrow-callback': 'error',
    
    // Require template literals instead of string concatenation
    'prefer-template': 'error',
    
    // ========================================
    // TypeScript-Specific Rules
    // ========================================
    
    // Disable base rule in favor of TypeScript version
    'no-unused-vars': 'off',
    '@typescript-eslint/no-unused-vars': [
      'error',
      {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      },
    ],
    
    // Warn on explicit any (require justification via comment)
    '@typescript-eslint/no-explicit-any': 'warn',
    
    // Don't require explicit return types (inferred types are acceptable)
    '@typescript-eslint/explicit-module-boundary-types': 'off',
    '@typescript-eslint/explicit-function-return-type': 'off',
    
    // Enforce consistent type imports
    '@typescript-eslint/consistent-type-imports': [
      'error',
      {
        prefer: 'type-imports',
        disallowTypeAnnotations: true,
      },
    ],
    
    // Disallow non-null assertions (use proper type guards)
    '@typescript-eslint/no-non-null-assertion': 'warn',
    
    // Require consistent type definitions
    '@typescript-eslint/consistent-type-definitions': ['error', 'interface'],
    
    // Disallow unnecessary type assertions
    '@typescript-eslint/no-unnecessary-type-assertion': 'error',
    
    // Require promises to be handled properly
    '@typescript-eslint/no-floating-promises': 'error',
    
    // Disallow misused promises
    '@typescript-eslint/no-misused-promises': [
      'error',
      {
        checksVoidReturn: false,
      },
    ],
    
    // Require await in async functions
    '@typescript-eslint/require-await': 'error',
    
    // Enforce naming conventions
    '@typescript-eslint/naming-convention': [
      'error',
      {
        selector: 'interface',
        format: ['PascalCase'],
        custom: {
          regex: '^I[A-Z]',
          match: false,
        },
      },
      {
        selector: 'typeAlias',
        format: ['PascalCase'],
      },
      {
        selector: 'enum',
        format: ['PascalCase'],
      },
      {
        selector: 'class',
        format: ['PascalCase'],
      },
    ],
    
    // ========================================
    // React-Specific Rules
    // ========================================
    
    // Not needed in React 17+ and Next.js
    'react/react-in-jsx-scope': 'off',
    
    // Use TypeScript for prop validation instead
    'react/prop-types': 'off',
    
    // Enforce self-closing components
    'react/self-closing-comp': 'error',
    
    // Prevent missing display name in React components
    'react/display-name': 'off',
    
    // Enforce consistent JSX boolean attributes
    'react/jsx-boolean-value': ['error', 'never'],
    
    // Enforce consistent JSX curly spacing
    'react/jsx-curly-brace-presence': [
      'error',
      { props: 'never', children: 'never' },
    ],
    
    // Disallow missing key prop in iterators
    'react/jsx-key': [
      'error',
      {
        checkFragmentShorthand: true,
        checkKeyMustBeforeSpread: true,
      },
    ],
    
    // Prevent usage of dangerous JSX props
    'react/no-danger': 'warn',
    
    // Prevent usage of deprecated methods
    'react/no-deprecated': 'error',
    
    // Prevent direct mutation of state
    'react/no-direct-mutation-state': 'error',
    
    // Enforce React Hooks rules
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',
    
    // ========================================
    // Import/Export Rules
    // ========================================
    
    // Enforce consistent import order
    'import/order': [
      'error',
      {
        groups: [
          'builtin',
          'external',
          'internal',
          'parent',
          'sibling',
          'index',
          'type',
        ],
        'newlines-between': 'always',
        alphabetize: {
          order: 'asc',
          caseInsensitive: true,
        },
      },
    ],
    
    // Disallow duplicate imports
    'import/no-duplicates': 'error',
    
    // Ensure all imports are resolvable
    'import/no-unresolved': 'off', // TypeScript handles this
    
    // Disallow default exports (prefer named exports)
    'import/no-default-export': 'off', // Next.js pages require default exports
    
    // Prefer named exports
    'import/prefer-default-export': 'off',
    
    // ========================================
    // Accessibility Rules (WCAG 2.1 AA)
    // ========================================
    
    // Enforce alt text on images
    'jsx-a11y/alt-text': 'error',
    
    // Enforce anchor elements have valid href
    'jsx-a11y/anchor-is-valid': [
      'error',
      {
        components: ['Link'],
        specialLink: ['hrefLeft', 'hrefRight'],
        aspects: ['invalidHref', 'preferButton'],
      },
    ],
    
    // Enforce click events have keyboard events
    'jsx-a11y/click-events-have-key-events': 'error',
    
    // Enforce interactive elements are keyboard accessible
    'jsx-a11y/interactive-supports-focus': 'error',
    
    // ========================================
    // Security Rules
    // ========================================
    
    // Detect eval usage
    'security/detect-eval-with-expression': 'error',
    
    // Detect non-literal require
    'security/detect-non-literal-require': 'warn',
    
    // Detect unsafe regex
    'security/detect-unsafe-regex': 'error',
    
    // Detect buffer usage
    'security/detect-buffer-noassert': 'error',
    
    // ========================================
    // Best Practices
    // ========================================
    
    // Disallow empty catch blocks
    'no-empty': ['error', { allowEmptyCatch: false }],
    
    // Require error handling in callbacks
    'node/handle-callback-err': 'off', // Using promises/async-await
    
    // Disallow synchronous methods
    'node/no-sync': 'off', // Acceptable in build scripts
    
    // Enforce consistent return
    'consistent-return': 'off', // TypeScript handles this
    
    // Require default case in switch statements
    'default-case': 'error',
    
    // Disallow else after return
    'no-else-return': 'error',
    
    // Disallow magic numbers (use named constants)
    'no-magic-numbers': [
      'off', // Too restrictive, but encouraged via code review
      {
        ignore: [0, 1, -1],
        ignoreArrayIndexes: true,
        enforceConst: true,
      },
    ],
    
    // Require radix parameter
    'radix': 'error',
    
    // Require IIFEs to be wrapped
    'wrap-iife': ['error', 'inside'],
  },

  // Settings for plugins
  settings: {
    react: {
      version: 'detect', // Automatically detect React version
    },
    'import/resolver': {
      typescript: {
        alwaysTryTypes: true,
        project: ['./tsconfig.json', './frontend/tsconfig.json', './backend/tsconfig.json'],
      },
      node: {
        extensions: ['.js', '.jsx', '.ts', '.tsx'],
      },
    },
  },

  // Files and directories to ignore
  ignorePatterns: [
    // Dependencies
    'node_modules/',
    
    // Build output
    'dist/',
    'build/',
    '.next/',
    'out/',
    
    // Cache
    '.turbo/',
    '.cache/',
    '.eslintcache',
    
    // Coverage
    'coverage/',
    '.nyc_output/',
    
    // Environment
    '.env',
    '.env.*',
    
    // Logs
    '*.log',
    'logs/',
    
    // OS files
    '.DS_Store',
    'Thumbs.db',
    
    // IDE
    '.vscode/',
    '.idea/',
    '*.swp',
    '*.swo',
    
    // Generated files
    '*.d.ts',
    
    // Public assets
    'public/',
    
    // Docker
    'Dockerfile*',
    'docker-compose*.yml',
    
    // K8s
    'k8s/',
    
    // Terraform
    'terraform/',
    '*.tfstate',
    '*.tfvars',
    
    // Python (OCR service)
    '*.py',
    'ocr-service/',
    
    // Documentation
    'docs/',
    '*.md',
  ],

  // Override configurations for specific file patterns
  overrides: [
    // Configuration files can use CommonJS
    {
      files: [
        '.eslintrc.js',
        'jest.config.js',
        'next.config.js',
        'tailwind.config.js',
        '*.config.js',
      ],
      env: {
        node: true,
      },
      rules: {
        '@typescript-eslint/no-var-requires': 'off',
        'import/no-commonjs': 'off',
      },
    },
    
    // Test files have relaxed rules
    {
      files: [
        '**/__tests__/**/*.[jt]s?(x)',
        '**/?(*.)+(spec|test).[jt]s?(x)',
      ],
      extends: ['plugin:jest/recommended'],
      env: {
        jest: true,
      },
      rules: {
        '@typescript-eslint/no-explicit-any': 'off',
        '@typescript-eslint/no-non-null-assertion': 'off',
        'no-magic-numbers': 'off',
        'max-lines-per-function': 'off',
      },
    },
    
    // Next.js pages require default exports
    {
      files: [
        'frontend/pages/**/*.tsx',
        'frontend/pages/**/*.ts',
        'frontend/app/**/*.tsx',
        'frontend/app/**/*.ts',
      ],
      rules: {
        'import/no-default-export': 'off',
        'import/prefer-default-export': 'error',
      },
    },
    
    // NestJS specific overrides
    {
      files: [
        'backend/src/**/*.ts',
      ],
      rules: {
        // Allow decorators
        '@typescript-eslint/no-unsafe-call': 'off',
        '@typescript-eslint/no-unsafe-member-access': 'off',
        
        // Allow parameter properties
        '@typescript-eslint/parameter-properties': 'off',
        
        // NestJS uses classes extensively
        'max-classes-per-file': 'off',
      },
    },
    
    // Migration files can have any structure
    {
      files: ['backend/src/database/migrations/**/*.ts'],
      rules: {
        '@typescript-eslint/naming-convention': 'off',
        'class-methods-use-this': 'off',
      },
    },
  ],
};

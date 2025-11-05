/**
 * Jest Configuration for Frontend Application
 *
 * This configuration sets up Jest testing framework for the OCR Processing Application frontend.
 * It includes:
 * - React component testing with jsdom environment
 * - TypeScript and JSX transformation
 * - Module path aliasing matching tsconfig.json
 * - Code coverage thresholds enforcing ≥80% as per Section 0.7.1
 * - Setup for React Testing Library and jest-dom matchers
 *
 * Requirements Satisfied:
 * - Section 0.7.1: ALL services MUST achieve ≥80% code coverage
 * - Section 0.6.1: Frontend unit tests with Jest + React Testing Library
 * - Section 0.5.14: Component unit tests target 80% coverage
 */

const nextJest = require('next/jest');

// Create Jest config preset with Next.js
// This automatically handles TypeScript, JSX, CSS imports, and Next.js specific features
const createJestConfig = nextJest({
  // Provide the path to your Next.js app to load next.config.js and .env files in your test environment
  dir: './',
});

/**
 * Custom Jest configuration
 * @type {import('jest').Config}
 */
const customJestConfig = {
  /**
   * Test Environment
   * Use jsdom to simulate a browser environment for React component testing
   */
  testEnvironment: 'jsdom',

  /**
   * Setup Files After Environment
   * Runs setup code after the test environment is set up but before tests run
   * This imports jest-dom matchers like toBeInTheDocument(), toHaveClass(), etc.
   */
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],

  /**
   * Module Name Mapper
   * Maps module paths to allow for cleaner imports and to mock static assets
   * Aligns with tsconfig.json path mappings for consistency
   */
  moduleNameMapper: {
    // Handle path aliases matching tsconfig.json
    '^@/components/(.*)$': '<rootDir>/components/$1',
    '^@/pages/(.*)$': '<rootDir>/pages/$1',
    '^@/hooks/(.*)$': '<rootDir>/hooks/$1',
    '^@/lib/(.*)$': '<rootDir>/lib/$1',
    '^@/styles/(.*)$': '<rootDir>/styles/$1',
    '^@/utils/(.*)$': '<rootDir>/utils/$1',
    '^@/types/(.*)$': '<rootDir>/types/$1',

    // Mock CSS imports (CSS modules and global CSS)
    '^.+\\.module\\.(css|sass|scss)$': 'identity-obj-proxy',
    '^.+\\.(css|sass|scss)$': '<rootDir>/__mocks__/styleMock.js',

    // Mock static file imports (images, fonts, etc.)
    '^.+\\.(jpg|jpeg|png|gif|webp|avif|svg)$': '<rootDir>/__mocks__/fileMock.js',
    '^.+\\.(woff|woff2|eot|ttf|otf)$': '<rootDir>/__mocks__/fileMock.js',
  },

  /**
   * Test Match Patterns
   * Defines which files Jest should recognize as test files
   * Supports both .test and .spec naming conventions
   * Only matches files with .test or .spec in the name to exclude helper files
   */
  testMatch: ['**/__tests__/**/*.(test|spec).[jt]s?(x)', '**/?(*.)+(spec|test).[jt]s?(x)'],

  /**
   * Test Path Ignore Patterns
   * Directories to exclude from test discovery
   */
  testPathIgnorePatterns: ['/node_modules/', '/.next/', '/out/', '/coverage/', '/dist/'],

  /**
   * Coverage Collection Configuration
   * Specifies which files to include in code coverage reports
   * Excludes test files, configuration files, and type definitions
   */
  collectCoverageFrom: [
    // Include all TypeScript and TSX files in key directories
    'components/**/*.{ts,tsx}',
    'pages/**/*.{ts,tsx}',
    'hooks/**/*.{ts,tsx}',
    'lib/**/*.{ts,tsx}',
    'utils/**/*.{ts,tsx}',

    // Exclude specific patterns
    '!**/*.d.ts', // Type definition files
    '!**/node_modules/**', // Dependencies
    '!**/__tests__/**', // Test directories
    '!**/__mocks__/**', // Mock directories
    '!**/*.test.{ts,tsx}', // Test files
    '!**/*.spec.{ts,tsx}', // Spec files
    '!**/*.stories.{ts,tsx}', // Storybook files
    '!**/coverage/**', // Coverage output
    '!**/.next/**', // Next.js build output
    '!**/out/**', // Next.js export output
    '!**/dist/**', // Build output
    '!pages/_app.tsx', // Next.js app wrapper (mostly boilerplate)
    '!pages/_document.tsx', // Next.js document (mostly boilerplate)
    '!pages/api/**', // API routes (tested via integration tests)
    '!next.config.js', // Configuration file
    '!jest.config.js', // This file
    '!tailwind.config.js', // Configuration file
    '!postcss.config.js', // Configuration file
  ],

  /**
   * Coverage Threshold
   * Enforces minimum code coverage percentages as per Section 0.7.1
   * ALL services MUST achieve ≥80% code coverage (branches, functions, lines, statements)
   *
   * If coverage falls below these thresholds, the test suite will fail
   */
  coverageThreshold: {
    global: {
      branches: 80,
      functions: 80,
      lines: 80,
      statements: 80,
    },
  },

  /**
   * Coverage Reporters
   * Formats for coverage output
   * - text: Console output for immediate feedback
   * - lcov: Standard format for CI/CD integration
   * - html: Human-readable HTML report
   * - json: Machine-readable format for tooling
   */
  coverageReporters: ['text', 'lcov', 'html', 'json'],

  /**
   * Coverage Directory
   * Output location for coverage reports
   */
  coverageDirectory: 'coverage',

  /**
   * Module File Extensions
   * Order matters: Jest will look for these extensions in order
   */
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json'],

  /**
   * Transform Ignore Patterns
   * By default, Jest doesn't transform node_modules
   * Add exceptions here for ES modules that need transformation
   */
  transformIgnorePatterns: ['/node_modules/(?!(@tanstack|lucide-react)/)'],

  /**
   * Globals
   * Global variables available in all test files
   */
  globals: {
    'ts-jest': {
      tsconfig: {
        jsx: 'react-jsx',
      },
    },
  },

  /**
   * Max Workers
   * Limits concurrent test execution for better performance
   * Uses 50% of available CPU cores
   */
  maxWorkers: '50%',

  /**
   * Verbose
   * Display individual test results
   */
  verbose: true,

  /**
   * Clear Mocks
   * Automatically clear mock calls and instances between every test
   * Prevents test pollution
   */
  clearMocks: true,

  /**
   * Restore Mocks
   * Automatically restore mock state between every test
   */
  restoreMocks: true,

  /**
   * Reset Mocks
   * Reset mock state before each test
   */
  resetMocks: true,

  /**
   * Bail
   * Stop running tests after n failures (0 = run all tests)
   */
  bail: 0,

  /**
   * Error on Deprecated
   * Throw errors on deprecated APIs
   */
  errorOnDeprecated: true,
};

/**
 * Export Jest configuration
 * createJestConfig is exported this way to ensure that next/jest can load the Next.js config
 * which is async. This allows Next.js to properly configure Jest with all necessary settings.
 */
module.exports = createJestConfig(customJestConfig);

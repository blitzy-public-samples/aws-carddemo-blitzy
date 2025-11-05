import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright End-to-End Testing Configuration
 *
 * Configures Playwright test runner for comprehensive E2E testing of the OCR Processing Application.
 * Supports multiple browsers (Chromium, Firefox, WebKit) and mobile device emulation.
 *
 * Test Coverage Requirements (from Section 0.5.14):
 * - Authentication flows (login, signup, password reset)
 * - Document upload and processing
 * - Field correction and validation
 * - Search functionality
 * - Batch processing operations
 *
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  /**
   * Directory containing E2E test files
   */
  testDir: '__tests__/e2e',

  /**
   * Disable parallel test execution to ensure test isolation and prevent race conditions
   * Critical for tests that modify shared state or database records
   */
  fullyParallel: false,

  /**
   * Fail CI builds if test.only is accidentally committed
   * Ensures all tests run in CI environment
   */
  forbidOnly: !!process.env.CI,

  /**
   * Retry strategy for flaky tests
   * - CI: 2 retries to handle transient failures
   * - Local: 0 retries for faster feedback during development
   */
  retries: process.env.CI ? 2 : 0,

  /**
   * Number of parallel workers
   * - CI: 1 worker for stability and resource management
   * - Local: undefined (defaults to available CPU cores)
   */
  workers: process.env.CI ? 1 : undefined,

  /**
   * Test reporters for various output formats
   * - html: Visual test report with screenshots and traces
   * - list: Console output with test progress
   */
  reporter: [['html', { outputFolder: 'playwright-report', open: 'never' }], ['list']],

  /**
   * Shared settings for all test projects
   */
  use: {
    /**
     * Base URL for navigation
     * Configurable via PLAYWRIGHT_BASE_URL environment variable
     * Defaults to local development server
     */
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:3000',

    /**
     * Capture trace on first retry for debugging failed tests
     * Traces include:
     * - Network activity
     * - Screenshots at each step
     * - Console logs
     * - DOM snapshots
     */
    trace: 'on-first-retry',

    /**
     * Capture screenshots only when tests fail
     * Reduces storage requirements while maintaining debugging capability
     */
    screenshot: 'only-on-failure',

    /**
     * Record video only for failed test runs
     * Videos are automatically cleaned up for passing tests
     */
    video: 'retain-on-failure',

    /**
     * Action timeout for individual operations (clicks, fills, etc.)
     * Default: 30 seconds
     */
    actionTimeout: 30000,

    /**
     * Navigation timeout for page loads
     * Aligned with performance requirement: <2 seconds initial page load
     * Set higher to account for CI environment variability
     */
    navigationTimeout: 30000,
  },

  /**
   * Test projects for cross-browser and device testing
   * Ensures compatibility across different browsers and form factors
   */
  projects: [
    /**
     * Desktop Chromium (Google Chrome, Microsoft Edge)
     * Primary testing browser - most commonly used by enterprise users
     */
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 720 },
      },
    },

    /**
     * Desktop Firefox
     * Secondary browser for cross-browser compatibility
     */
    {
      name: 'firefox',
      use: {
        ...devices['Desktop Firefox'],
        viewport: { width: 1280, height: 720 },
      },
    },

    /**
     * Desktop WebKit (Safari)
     * Important for macOS/iOS users
     */
    {
      name: 'webkit',
      use: {
        ...devices['Desktop Safari'],
        viewport: { width: 1280, height: 720 },
      },
    },

    /**
     * Mobile Chrome (Android)
     * Tests mobile responsiveness for tablet devices
     * Per Section 0.7.11: Support tablet devices (iPad, Android tablets)
     */
    {
      name: 'Mobile Chrome',
      use: {
        ...devices['Pixel 5'],
      },
    },

    /**
     * Mobile Safari (iOS)
     * Tests mobile responsiveness for iPad and iPhone
     * Per Section 0.7.11: Support landscape and portrait orientations
     */
    {
      name: 'Mobile Safari',
      use: {
        ...devices['iPhone 12'],
      },
    },
  ],

  /**
   * Web Server Configuration
   * Automatically starts the Next.js development server before running tests
   *
   * Benefits:
   * - No manual server management required
   * - Consistent test environment
   * - Automatic cleanup after tests complete
   */
  webServer: {
    /**
     * Command to start the Next.js development server
     */
    command: 'npm run dev',

    /**
     * URL to wait for before starting tests
     * Must match the baseURL configuration
     */
    url: 'http://localhost:3000',

    /**
     * Reuse existing server if already running
     * Speeds up local development workflow
     */
    reuseExistingServer: !process.env.CI,

    /**
     * Timeout for server startup
     * 120 seconds to accommodate slow CI environments and cold starts
     */
    timeout: 120000,

    /**
     * Output server logs to console
     * Helpful for debugging server startup issues
     */
    stdout: 'pipe',
    stderr: 'pipe',
  },

  /**
   * Global timeout for each test
   * Default: 30 seconds per test
   * Can be overridden in individual tests if needed
   */
  timeout: 30000,

  /**
   * Expect timeout for assertions
   * Default: 5 seconds
   */
  expect: {
    timeout: 5000,
  },

  /**
   * Maximum failures before stopping test run
   * Undefined = run all tests regardless of failures
   */
  maxFailures: process.env.CI ? undefined : 5,
});

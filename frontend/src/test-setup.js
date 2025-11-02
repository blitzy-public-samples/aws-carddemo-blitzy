/**
 * Vitest Test Setup
 * 
 * Global test configuration and setup for all test files.
 * Configures jest-dom matchers and global test utilities.
 */

import { expect, afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import * as matchers from '@testing-library/jest-dom/matchers';

// Extend Vitest's expect with jest-dom matchers
expect.extend(matchers);

// Cleanup after each test
afterEach(() => {
  cleanup();
});

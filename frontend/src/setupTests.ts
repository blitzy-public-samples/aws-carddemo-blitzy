/**
 * Global Jest setup for the CardDemo React frontend test suite.
 *
 * :purpose: Register the ``@testing-library/jest-dom`` custom matchers
 *     (for example ``toBeInTheDocument``, ``toHaveValue`` and ``toBeDisabled``)
 *     on Jest's ``expect`` API. This module is referenced by the
 *     ``setupFilesAfterEnv`` entry in ``package.json`` and is evaluated once
 *     after the test framework is installed and before each test file runs,
 *     so every test can assert against the rendered DOM.
 */
import '@testing-library/jest-dom';

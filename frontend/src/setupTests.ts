/**
 * Global Jest setup for the CardDemo React frontend test suite.
 *
 * :purpose: Register the ``@testing-library/jest-dom`` custom matchers
 *     (for example ``toBeInTheDocument``, ``toHaveValue`` and ``toBeDisabled``)
 *     on Jest's ``expect`` API. This module is referenced by the
 *     ``setupFilesAfterEnv`` entry in ``package.json`` and is evaluated once
 *     after the test framework is installed and before each test file runs,
 *     so every test can assert against the rendered DOM. It also installs the
 *     ``TextEncoder`` / ``TextDecoder`` globals that the ``jsdom`` test
 *     environment does not provide but ``react-router`` requires, so the routed
 *     page components can be rendered under test.
 */
import { TextDecoder, TextEncoder } from 'node:util';
import '@testing-library/jest-dom';

/**
 * :purpose: Provide the WHATWG encoding globals inside the ``jsdom`` VM context.
 *     ``jsdom`` exposes neither, and ``react-router`` dereferences
 *     ``TextEncoder`` while resolving route data, which fails the whole suite
 *     with ``ReferenceError: TextEncoder is not defined``. Assigned only when
 *     absent so a future environment that supplies them natively is left alone.
 */
const encodingGlobals = globalThis as unknown as {
  TextEncoder?: typeof TextEncoder;
  TextDecoder?: typeof TextDecoder;
};

if (encodingGlobals.TextEncoder === undefined) {
  encodingGlobals.TextEncoder = TextEncoder;
}

if (encodingGlobals.TextDecoder === undefined) {
  encodingGlobals.TextDecoder = TextDecoder;
}

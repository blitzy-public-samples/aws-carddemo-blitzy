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
 *     page components can be rendered under test. Finally it filters the two React
 *     ``act`` diagnostics that arise from React's own drain ordering and that no test
 *     can answer, so the report carries only console output a change could act on.
 */
import { TextDecoder, TextEncoder } from 'node:util';
import '@testing-library/jest-dom';
import { isUnactionableReactActDiagnostic } from './testing/reactActDiagnostics';

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

/**
 * :purpose: Keep React's two unanswerable ``act`` drain diagnostics out of the test report,
 *     and pass everything else through untouched. Both are described in full, with the React
 *     source ordering that produces them, in ``src/testing/reactActDiagnostics.ts``. The filter is
 *     deliberately narrow. The update report is dropped only when the frame publication is on the
 *     captured stack, so an update that escapes ``act`` anywhere else still reaches the report,
 *     and every other ``console.error`` — including the one ``ErrorBoundary`` makes and every
 *     other React warning — is forwarded unchanged.
 */
const forwardConsoleError = console.error.bind(console);
console.error = (...args: unknown[]): void => {
  if (isUnactionableReactActDiagnostic(args, new Error('act-diagnostic').stack ?? '')) {
    return;
  }
  forwardConsoleError(...(args as Parameters<typeof console.error>));
};

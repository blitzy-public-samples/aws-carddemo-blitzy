/**
 * :module: reactActDiagnostics.test
 * :purpose: Pin the allowance ``setupTests.ts`` applies to ``console.error`` down to the
 *     two React ``act`` drain diagnostics it is for, so the filter cannot widen and
 *     start hiding output a change could act on — an update that escapes ``act``
 *     anywhere but the frame publication, or any other warning at all.
 */
import { isUnactionableReactActDiagnostic } from './reactActDiagnostics';

/** React's report of an update scheduled with no act queue live. */
const UPDATE_REPORT =
  'An update to ScreenFrame inside a test was not wrapped in act(...).\n\nWhen testing, ' +
  'code that causes React state updates should be wrapped into act(...):';

/** A stack in which the frame publication raised the update. */
const FRAME_STACK = [
  'Error: act-diagnostic',
  '    at console.error (src/setupTests.ts:52:46)',
  '    at warnIfUpdatesNotWrappedWithActDEV (node_modules/react-dom/cjs/react-dom-client.development.js:18757:9)',
  '    at dispatchSetState (node_modules/react-dom/cjs/react-dom-client.development.js:9127:7)',
  '    at src/components/Layout.tsx:410:7',
  '    at src/pages/AccountUpdatePage.tsx:1632:5',
].join('\n');

/** A stack in which a settled request wrote to a page nothing awaited. */
const REQUEST_STACK = [
  'Error: act-diagnostic',
  '    at console.error (src/setupTests.ts:52:46)',
  '    at warnIfUpdatesNotWrappedWithActDEV (node_modules/react-dom/cjs/react-dom-client.development.js:18757:9)',
  '    at dispatchSetState (node_modules/react-dom/cjs/react-dom-client.development.js:9127:7)',
  '    at src/hooks/useApi.ts:211:11',
].join('\n');

describe('the act-diagnostic allowance', () => {
  it('accepts the update report only from the frame publication', () => {
    expect(isUnactionableReactActDiagnostic([UPDATE_REPORT, 'ScreenFrame'], FRAME_STACK)).toBe(
      true,
    );
  });

  it('rejects the same report when a settled request raised it', () => {
    // This is the shape of a REAL test defect: a read that resolved after the test body
    // returned. It must reach the report.
    expect(
      isUnactionableReactActDiagnostic([UPDATE_REPORT, 'AccountViewPage'], REQUEST_STACK),
    ).toBe(false);
  });

  it('accepts the unawaited-scope report, which carries no stack of its own', () => {
    expect(
      isUnactionableReactActDiagnostic(
        [
          'A component suspended inside an `act` scope, but the `act` call was not awaited. ' +
            'When testing React components that depend on asynchronous data, you must await the result',
        ],
        'Error: act-diagnostic\n    at node_modules/react/cjs/react.development.js:890:23',
      ),
    ).toBe(true);
  });

  it('rejects every other console.error, whatever the stack', () => {
    expect(isUnactionableReactActDiagnostic(['Boom'], FRAME_STACK)).toBe(false);
    expect(
      isUnactionableReactActDiagnostic(
        ['Warning: validateDOMNesting(...): <div> cannot appear as a descendant of <p>.'],
        FRAME_STACK,
      ),
    ).toBe(false);
    expect(isUnactionableReactActDiagnostic([new Error('thrown')], FRAME_STACK)).toBe(false);
    expect(isUnactionableReactActDiagnostic([], FRAME_STACK)).toBe(false);
  });
});

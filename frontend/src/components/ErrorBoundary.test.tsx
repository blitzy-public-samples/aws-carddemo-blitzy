/**
 * :module: ErrorBoundary.test
 * :purpose: Verify the SPA's error containment and its telemetry: a render-time
 *     exception is caught and answered with the 24x80 frame carrying the verbatim
 *     legacy abend text on the line-23 message region rather than a blank device, the
 *     fault is reported as one structured record carrying a correlation reference, a
 *     failure that came from a REST call reuses the server-echoed correlation id, and
 *     the two window-level faults no boundary can see are reported too.
 * :output: Assertions only; the module exports nothing.
 * :note: React itself logs a caught error through ``console.error``, so the assertions
 *     select this module's own JSON records out of the spy's calls instead of asserting
 *     a call count.
 */
import { jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import ErrorBoundary, {
  ABEND_FRAME_TEST_ID,
  ABEND_MESSAGE,
  UNCAUGHT_ERROR_EVENT,
  registerGlobalErrorTelemetry,
  reportUncaughtError,
} from './ErrorBoundary';
import type { UncaughtErrorReport } from './ErrorBoundary';
import { ApiError } from '../api';

/** The ``console.error`` spy type, as ``jest.spyOn`` returns it. */
type ConsoleErrorSpy = jest.Spied<typeof console.error>;

/** Message thrown by the failing screen stand-in. */
const BOOM = 'card detail render failed';

/**
 * :purpose: A screen stand-in that fails while rendering, as a screen would when it
 *     dereferences a field the server did not send.
 * :returns: Never; it always throws.
 */
function Boom(): ReactElement {
  throw new Error(BOOM);
}

/**
 * :purpose: Collect the structured records this module emitted during a test.
 * :param spy: The ``console.error`` spy.
 * :returns: Every emitted :class:`UncaughtErrorReport`, in order.
 */
function reportsFrom(spy: ConsoleErrorSpy): UncaughtErrorReport[] {
  return spy.mock.calls
    .map((call): unknown => call[0])
    .filter((arg): arg is string => typeof arg === 'string')
    .filter((arg) => arg.includes(UNCAUGHT_ERROR_EVENT))
    .map((arg) => JSON.parse(arg) as UncaughtErrorReport);
}

describe('ErrorBoundary', () => {
  let consoleError: ConsoleErrorSpy;

  beforeEach(() => {
    consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('renders its children while nothing has failed', () => {
    render(
      <ErrorBoundary>
        <p data-testid="child">Sign on to CardDemo</p>
      </ErrorBoundary>,
    );

    expect(screen.getByTestId('child')).toHaveTextContent('Sign on to CardDemo');
    expect(screen.queryByTestId(ABEND_FRAME_TEST_ID)).toBeNull();
  });

  it('contains a render-time failure in the 24x80 frame with the line-23 abend message', () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    // The device is not blanked: the frame survives and carries the notice.
    const frame = screen.getByTestId(ABEND_FRAME_TEST_ID);
    expect(frame).toHaveClass('screen');
    const alert = screen.getByRole('alert');
    expect(alert).toHaveClass('errorBanner');
    expect(alert).toHaveTextContent(ABEND_MESSAGE);
    expect(frame).toContainElement(alert);
  });

  it('shows the verbatim legacy ABEND-MSG literal and nothing else', () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(ABEND_MESSAGE).toBe('UNEXPECTED ABEND OCCURRED.');
    // The failure notice is the frame's only text: no correlation id, no stack.
    expect(screen.getByTestId(ABEND_FRAME_TEST_ID).textContent).toBe(ABEND_MESSAGE);
  });

  it('reports the contained failure once, with a correlation id and the component stack', () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    const reports = reportsFrom(consoleError);
    expect(reports).toHaveLength(1);
    const [report] = reports;
    expect(report.source).toBe('render');
    expect(report.name).toBe('Error');
    expect(report.message).toBe(BOOM);
    expect(report.correlationId).toMatch(/\S/);
    expect(report.componentStack).toContain('Boom');
  });
});

describe('reportUncaughtError', () => {
  let consoleError: ConsoleErrorSpy;

  beforeEach(() => {
    consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it("reuses a failed call's server-echoed correlation id", () => {
    const failure = new ApiError(500, 'Unable to determine Card Number', undefined, false, 'cid-from-gateway');

    const report = reportUncaughtError(failure, 'unhandledrejection');

    expect(report.correlationId).toBe('cid-from-gateway');
    expect(report.name).toBe('ApiError');
    expect(reportsFrom(consoleError)).toEqual([report]);
  });

  it('generates a correlation id for a fault that never reached the server', () => {
    const first = reportUncaughtError(new TypeError('undefined is not an object'), 'error');
    const second = reportUncaughtError(new TypeError('undefined is not an object'), 'error');

    expect(first.correlationId).toMatch(/\S/);
    expect(second.correlationId).not.toBe(first.correlationId);
    expect(first.name).toBe('TypeError');
  });

  it('normalizes a thrown value that is not an Error', () => {
    expect(reportUncaughtError('plain string failure', 'error')).toMatchObject({
      name: 'Error',
      message: 'plain string failure',
    });
    expect(reportUncaughtError(undefined, 'unhandledrejection')).toMatchObject({
      name: 'Error',
      message: 'undefined',
    });
  });

  it('omits the component stack when there is none', () => {
    expect(reportUncaughtError(new Error('x'), 'error')).not.toHaveProperty('componentStack');
    expect(reportUncaughtError(new Error('x'), 'error', '')).not.toHaveProperty('componentStack');
  });
});

describe('registerGlobalErrorTelemetry', () => {
  let consoleError: ConsoleErrorSpy;

  beforeEach(() => {
    consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('reports an uncaught error event and an unhandled rejection', () => {
    const dispose = registerGlobalErrorTelemetry(window);

    window.dispatchEvent(
      new ErrorEvent('error', { error: new RangeError('out of range'), message: 'out of range' }),
    );
    // jsdom implements no `PromiseRejectionEvent`, so the rejection is dispatched as
    // the plain event carrying the same `reason` property the browser supplies.
    const rejection: Event & { reason?: unknown } = new Event('unhandledrejection');
    rejection.reason = new Error('session probe rejected');
    window.dispatchEvent(rejection);

    const reports = reportsFrom(consoleError);
    expect(reports.map((report) => report.source)).toEqual(['error', 'unhandledrejection']);
    expect(reports[0]).toMatchObject({ name: 'RangeError', message: 'out of range' });
    expect(reports[1]).toMatchObject({ name: 'Error', message: 'session probe rejected' });
    dispose();
  });

  it('falls back to the event message when the error object is unavailable', () => {
    const dispose = registerGlobalErrorTelemetry(window);

    window.dispatchEvent(new ErrorEvent('error', { message: 'Script error.' }));

    expect(reportsFrom(consoleError)[0]).toMatchObject({
      source: 'error',
      name: 'Error',
      message: 'Script error.',
    });
    dispose();
  });

  it('stops reporting once disposed', () => {
    const dispose = registerGlobalErrorTelemetry(window);
    dispose();

    window.dispatchEvent(new ErrorEvent('error', { message: 'after dispose' }));

    expect(reportsFrom(consoleError)).toEqual([]);
  });
});

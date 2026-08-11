/**
 * ErrorBoundary =============
 * :purpose: Contain an unrecoverable client-side failure and report it. A render-time
 *     exception in any of the seventeen screens is caught here and answered with the 24x80
 *     frame carrying the legacy abend text on the line-23 message region, instead of React
 *     unmounting the tree to a blank device. The module also registers the browser-level
 *     telemetry for the two faults no boundary can see — an uncaught ``error`` event and an
 *     ``unhandledrejection`` — so both are reported with the same ``X-Correlation-Id``
 *     correlation reference every REST call carries.
 * :output: The default export :class:`ErrorBoundary`, the reported-payload type
 * :class: `UncaughtErrorReport`, the reporter :func:`reportUncaughtError`, the
 *     browser-hook registrar :func:`registerGlobalErrorTelemetry`, and the line-23 literal
 *     :data:`ABEND_MESSAGE`.
 * :note: The legacy analogue is ``ABEND-ROUTINE``, which moves ``'UNEXPECTED ABEND
 *     OCCURRED.'`` into ``ABEND-MSG`` and sends it to the erased device before ``EXEC CICS
 *     ABEND ABCODE('9999')`` [app/cbl/COCRDUPC.cbl:L1531-L1552; app/cpy/CSMSG02Y.cpy]. The
 *     message is the verbatim legacy literal, so the failure notice invents no wording, and
 *     the correlation reference is reported to the telemetry channel only — no screen surfaces
 *     it, so the frame gains no output beyond the notice itself.
 * :note: Rationale for the containment boundary, its placement and the reuse of
 * :func: `ErrorBanner` is in docs/decision-log.md (SPA entry point).
 */
import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import ErrorBanner from './ErrorBanner';
import { generateCorrelationId, isApiError } from '../api';

/**
 * :purpose: The line-23 text shown for a contained client-side failure: the verbatim
 *     default ``ABEND-MSG`` of the legacy abend routine
 *     [app/cbl/COCRDUPC.cbl:L1534].
 */
export const ABEND_MESSAGE = 'UNEXPECTED ABEND OCCURRED.';

/**
 * :purpose: Test id of the contained-failure frame, so a caller can assert that the
 *     24x80 shell survived the failure.
 */
export const ABEND_FRAME_TEST_ID = 'screen-abend';

/**
 * :purpose: Event name every uncaught-failure record carries, so the SPA's records are
 *     selectable in the browser console and in any log forwarder alongside the
 *     backend's structured records.
 */
export const UNCAUGHT_ERROR_EVENT = 'spa.uncaught-error';

/**
 * :purpose: Where a reported fault was observed.
 *
 *     ``render`` — a component threw while rendering and was caught by the boundary;
 *     ``error`` — an uncaught exception reached the window;
 *     ``unhandledrejection`` — a promise rejected with no handler.
 */
export type UncaughtErrorSource = 'render' | 'error' | 'unhandledrejection';

/**
 * :purpose: The record emitted for one uncaught failure.
 * :param event: Always :data:`UNCAUGHT_ERROR_EVENT`.
 * :param correlationId: The failing request's own ``X-Correlation-Id`` when the fault
 *     came from a REST call that carried one, otherwise a freshly generated id, so
 *     every record is quotable to an operator.
 * :param source: See :class:`UncaughtErrorSource`.
 * :param name: Error class name, or ``Error`` when the thrown value was not an error.
 * :param message: The error's message.
 * :param componentStack: React's component stack, present only for ``render``.
 */
export interface UncaughtErrorReport {
  event: typeof UNCAUGHT_ERROR_EVENT;
  correlationId: string;
  source: UncaughtErrorSource;
  name: string;
  message: string;
  componentStack?: string;
}

/**
 * :purpose: Normalize an arbitrary thrown value to its class name and message.
 * :param value: The caught value, which JavaScript allows to be anything.
 * :returns: The name/message pair to report.
 */
function describeThrown(value: unknown): { name: string; message: string } {
  if (value instanceof Error) {
    return { name: value.name, message: value.message };
  }
  if (typeof value === 'string') {
    return { name: 'Error', message: value };
  }
  return { name: 'Error', message: String(value) };
}

/**
 * :purpose: Report one uncaught failure on the SPA's telemetry channel as a single
 *     structured record, correlated with the backend's own records for the same
 *     request whenever the fault carried a server-echoed correlation id.
 * :param value: The caught value.
 * :param source: Where the fault was observed.
 * :param componentStack: React's component stack, for a ``render`` fault.
 * :returns: The record that was emitted, so a caller can assert on it.
 */
export function reportUncaughtError(
  value: unknown,
  source: UncaughtErrorSource,
  componentStack?: string,
): UncaughtErrorReport {
  const { name, message } = describeThrown(value);
  const correlationId =
    isApiError(value) && value.correlationId !== undefined && value.correlationId.length > 0
      ? value.correlationId
      : generateCorrelationId();
  const report: UncaughtErrorReport = {
    event: UNCAUGHT_ERROR_EVENT,
    correlationId,
    source,
    name,
    message,
  };
  if (componentStack !== undefined && componentStack.length > 0) {
    report.componentStack = componentStack;
  }
  console.error(JSON.stringify(report));
  return report;
}

/**
 * :purpose: Register the browser-level telemetry for the faults a React boundary
 *     cannot observe: an uncaught exception and an unhandled promise rejection. Both
 *     are added as listeners rather than assigned to ``window.onerror`` /
 *     ``window.onunhandledrejection``, so an existing handler is not displaced, and
 *     neither listener calls ``preventDefault``, so the browser still reports the
 *     fault itself.
 * :param target: The window to register on; defaults to the current one.
 * :returns: A function that removes both listeners.
 */
export function registerGlobalErrorTelemetry(target: Window = window): () => void {
  const onError = (event: ErrorEvent): void => {
    reportUncaughtError(event.error ?? event.message, 'error');
  };
  const onRejection = (event: PromiseRejectionEvent): void => {
    reportUncaughtError(event.reason, 'unhandledrejection');
  };
  target.addEventListener('error', onError);
  target.addEventListener('unhandledrejection', onRejection);
  return (): void => {
    target.removeEventListener('error', onError);
    target.removeEventListener('unhandledrejection', onRejection);
  };
}

/**
 * :purpose: Props for :class:`ErrorBoundary`.
 * :param children: The application tree to contain.
 */
export interface ErrorBoundaryProps {
  children?: ReactNode;
}

/**
 * :purpose: State of :class:`ErrorBoundary`.
 * :param failed: ``true`` once a descendant has thrown while rendering.
 */
export interface ErrorBoundaryState {
  failed: boolean;
}

/**
 * :purpose: The SPA's root error boundary: it renders its children until a descendant
 *     throws while rendering, then reports the fault and presents the 24x80 frame with
 *     :data:`ABEND_MESSAGE` on the line-23 message region.
 * :note: A class component because ``getDerivedStateFromError`` and
 *     ``componentDidCatch`` are the only error-boundary API React exposes; there is no
 *     hook equivalent.
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  /** Nothing has failed until a descendant throws. */
  override state: ErrorBoundaryState = { failed: false };

  /**
   * :purpose: Switch the boundary to its contained-failure rendering.
   * :returns: The failed state.
   */
  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  /**
   * :purpose: Report the contained failure together with the component stack that
   *     identifies the screen it came from.
   * :param error: The error a descendant threw.
   * :param info: React's error info, carrying the component stack.
   */
  override componentDidCatch(error: Error, info: ErrorInfo): void {
    reportUncaughtError(error, 'render', info.componentStack ?? undefined);
  }

  /**
   * :purpose: Render the children, or the contained-failure frame.
   * :returns: The rendered tree.
   */
  override render(): ReactNode {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <div className="screen" data-testid={ABEND_FRAME_TEST_ID}>
        <div className="screen__body" />
        <ErrorBanner message={ABEND_MESSAGE} />
      </div>
    );
  }
}

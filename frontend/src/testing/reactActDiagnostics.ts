/**
 * :module: ``frontend/src/testing/reactActDiagnostics.ts``
 * :purpose: Recognise the two React ``act`` diagnostics that this suite cannot act on, so
 *     ``setupTests.ts`` can keep them out of the test report while every other
 *     ``console.error`` reaches it untouched.
 * :output: The named ``isUnactionableReactActDiagnostic`` predicate.
 * :note: Test-only. Nothing outside ``setupTests.ts`` and this module's own test imports
 *     it, so it reaches no production bundle. What React reports, and why no test can answer
 *     it ------------------------------------------------ ``act`` collects the work an
 *     interaction schedules into a queue and drains it when the scope closes. It restores the
 *     PREVIOUS queue first and drains second (``react/cjs/react.development.js``:
 *     ``popActScope(prevActQueue, prevActScopeDepth)`` then ``flushActQueue(queue)``), so for
 *     the whole of the outermost scope's final drain ``ReactSharedInternals.actQueue`` is
 *     already ``null``. React's own check is exactly that field —
 *     ``warnIfUpdatesNotWrappedWithActDEV`` reports an update when ``null ===
 *     ReactSharedInternals.actQueue`` — so any update scheduled DURING that drain is reported
 *     as unwrapped, however the test wrapped the interaction. Every CardDemo screen schedules
 *     such an update. A page publishes its header, message region and key legend into the
 *     frame from a ``useLayoutEffect`` (``Layout.setChrome``), because a CICS program moved
 *     every field into the symbolic map before its ONE ``SEND`` and nothing half-built ever
 *     reached the terminal. A layout effect runs inside the commit, and the commit an
 *     interaction causes happens inside that final drain, so the publication lands in the one
 *     window React cannot attribute to the interaction. Opening a further scope around the
 *     interaction (which ``interaction.ts`` does) moves all but the outermost drain's own
 *     updates inside a live queue, and that is why one report of this kind survives rather
 *     than twenty. The second message follows from the same drain: a queue left non-empty by
 *     it makes ``act`` report a suspension in a scope that was not awaited. It carries no
 *     stack of its own — React defers the check by several microtasks — so it is matched on
 *     its text alone. What is deliberately NOT matched -------------------------------- The
 *     update report is matched only when the frame publication is on the stack. An update that
 *     escapes ``act`` anywhere else — a settled request writing to a page that the test never
 *     awaited, which is a REAL test defect and was one of this suite's — is printed exactly as
 *     before.
 */

/** React's report of a state update scheduled outside an ``act`` scope. */
const UNWRAPPED_UPDATE = 'inside a test was not wrapped in act(...)';

/**
 * React's report of a scope whose queue the drain left non-empty. Matched on its text
 * because React raises it from a deferred callback that carries no stack of the code
 * that caused it.
 */
const UNAWAITED_SCOPE = 'A component suspended inside an `act` scope, but the `act` call was not awaited';

/**
 * The frame publication the screens drive from their layout effect. Present in the
 * stack of an update report that originates there, and absent from one that does not.
 */
const FRAME_PUBLICATION = 'Layout.tsx';

/**
 * :purpose: Whether a ``console.error`` call is one of React's two ``act`` drain
 *     diagnostics that no arrangement of ``act`` or ``await`` in a test can prevent.
 * :param args: The arguments the caller passed to ``console.error``.
 * :param stack: The stack captured at the call site, used to establish that an update
 *     report comes from the frame publication and not from somewhere a test could fix.
 * :returns: ``true`` only for those two messages, and for the update report only when
 *     the frame publication raised it.
 */
export function isUnactionableReactActDiagnostic(
  args: readonly unknown[],
  stack: string,
): boolean {
  const message = typeof args[0] === 'string' ? args[0] : '';
  if (message.includes(UNAWAITED_SCOPE)) {
    return true;
  }
  return message.includes(UNWRAPPED_UPDATE) && stack.includes(FRAME_PUBLICATION);
}

/**
 * :module: ``frontend/src/testing/interaction.ts``
 * :purpose: Start a ``user-event`` session whose interactions run inside an outer ``act``
 *     scope, so a state update a screen schedules from a LAYOUT effect during that interaction's
 *     commit is flushed inside the scope instead of escaping it.
 * :output: The named ``setupInteraction`` helper, a drop-in for ``userEvent.setup()``.
 * :note: Test-only. Nothing outside a ``*.test.tsx`` file imports this module, so it reaches
 *     no production bundle. Why an outer scope is needed ---------------------------- Every screen
 *     publishes its header, message region and key legend into the frame from a
 *     ``useLayoutEffect`` (``Layout.setChrome``), because a CICS program moved every field into
 *     the symbolic map before its ONE ``SEND`` and nothing half-built ever reached the terminal.
 *     Testing Library dispatches each DOM event through a synchronous ``act`` of its own (its
 *     ``eventWrapper``), which is the outermost scope in that situation and so hits the case
 *     above. Opening one more scope AROUND the interaction leaves a non-null queue to restore, the
 *     commit-phase update joins it, and the outer scope drains it — the update is flushed inside
 *     ``act`` rather than after it. The updates are applied in the order they were queued when the
 *     scope drains.
 */
import { act, configure, getConfig } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Options, UserEvent } from '@testing-library/user-event';

/**
 * :purpose: Run one interaction inside an act scope and settle everything it
 *     schedules, including an update a layout effect raises during its commit.
 * :param interaction: the ``user-event`` call to perform.
 */
async function inActScope(interaction: () => Promise<void>): Promise<void> {
  const { asyncWrapper } = getConfig();
  // Testing Library disables the act environment for the whole of a `user-event` call
  // (its `asyncWrapper` exists so `waitFor` can poll without one). With a scope open
  // around the call, every update the interaction schedules then lands with the
  // environment off and a live queue, which React reports as an environment "not
  // configured to support act". The wrapper is neutralised for the duration of this one
  // interaction -- nothing inside it polls -- and restored immediately afterwards.
  configure({
    asyncWrapper: (callback: (...args: unknown[]) => unknown): Promise<unknown> =>
      Promise.resolve(callback()),
  });
  try {
    await act(async () => {
      await interaction();
    });
  } finally {
    configure({ asyncWrapper });
  }
}

/**
 * :purpose: Wrap one bound session method so every call of it opens an act scope.
 * :param method: the session method, already bound to its instance by
 *     ``user-event``\'s own setup.
 * :returns: The same call signature, act-scoped.
 */
function scoped<A extends unknown[]>(
  method: (...args: A) => Promise<void>,
): (...args: A) => Promise<void> {
  return (...args: A): Promise<void> => inActScope(() => method(...args));
}

/**
 * :purpose: Begin an interaction session for a screen test.
 * :param options: ``user-event`` options, passed through unchanged.
 * :returns: The session, with the methods a screen test uses to key a value or
 *     transmit an attention identifier act-scoped, and every other method exactly as
 *     ``userEvent.setup()`` returns it.
 */
export function setupInteraction(options?: Options): UserEvent {
  const session = userEvent.setup(options);
  return {
    ...session,
    click: scoped(session.click),
    dblClick: scoped(session.dblClick),
    keyboard: scoped(session.keyboard),
    type: scoped(session.type),
    clear: scoped(session.clear),
    tab: scoped(session.tab),
  };
}

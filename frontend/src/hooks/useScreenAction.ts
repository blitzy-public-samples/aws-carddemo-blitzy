/**
 * :module: ``frontend/src/hooks/useScreenAction.ts``
 * :purpose: Bind a screen's AID handler to an identity-stable activator that always
 *     dispatches to the handler of the newest render. A 3270 program received the
 *     attention identifier together with the contents of the screen at the instant
 *     the key was pressed, so the values it acted on were always the values on
 *     display. This hook restores that guarantee for the React screens, whose
 *     line-24 legend reaches :func:`PFKeyBar` indirectly — a page publishes its
 *     handlers from an effect, through ``setChrome``, into the shell's state — and
 *     would otherwise dispatch the handler of a superseded render for the interval
 *     between a keystroke's commit and the effect that republishes the legend.
 * :output: The named ``useScreenAction`` hook.
 * :note: The handler is captured in a layout effect with no dependency list, so it is
 *     refreshed synchronously as part of the same commit that paints the new entry
 *     state. React flushes a discrete event's render, commit and layout effects
 *     before the event dispatch returns, so no later activation — key press, click,
 *     paste, autofill or keyboard wedge — can observe a handler older than the screen
 *     it is acting on. A passive ``useEffect`` would run after that commit and leave
 *     exactly the window this module exists to close.
 * :note: The returned activator never changes identity. A page can therefore publish
 *     it once, and the published legend stops being rebuilt on every keystroke.
 */

import { useCallback, useLayoutEffect, useRef } from 'react';

/**
 * :purpose: Wrap a screen handler so the activator published to the shared frame is
 *     identity-stable while always invoking the current render's handler.
 * :param handler: the screen's handler for one AID, rebuilt by each render that
 *     changes the entry state it reads.
 * :returns: an activator with a stable identity that forwards its arguments to the
 *     most recently rendered ``handler`` and returns that handler's result.
 */
export function useScreenAction<A extends unknown[], R>(
  handler: (...args: A) => R,
): (...args: A) => R {
  const latest = useRef(handler);
  useLayoutEffect(() => {
    latest.current = handler;
  });
  return useCallback((...args: A): R => latest.current(...args), []);
}

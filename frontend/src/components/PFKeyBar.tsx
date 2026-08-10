/**
 * PFKeyBar
 * ========
 *
 * :purpose: Render the BMS line-24 function-key legend as accessible buttons and
 *     bind the physical 3270 AID keys to their per-screen handlers. Mirrors the
 *     AID-to-PFKey mapping of ``app/cpy/CSSTRPFY.cpy`` and the navigation
 *     conventions of AAP 0.3.5 (ENTER=submit, PF3=exit/back, PF7=page backward,
 *     PF8=page forward), plus any extra keys a page declares (F4/F5/F12).
 * :note: The legend is a plain labelled group rather than an ARIA ``toolbar``. Every
 *     key is an independent tab stop, which is the 3270 legend's own behaviour and the
 *     tab order the mapsets declare; an ARIA toolbar would additionally owe a roving
 *     tabindex plus arrow and Escape handling, which would change that order.
 * :note: The listener reads the current legend, the current keyboard-lock state and the
 *     send counter through refs refreshed in a LAYOUT effect. A passive effect would be
 *     deferred past the commit and leave a window in which a key struck immediately
 *     after a keystroke acts on the screen state that preceded it.
 */
import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import type { ReactElement } from 'react';
import { PfKeyAction } from '../types';

/**
 * :purpose: One entry in the line-24 function-key legend.
 * :param action: The AID / PF-key this entry represents.
 * :param label: Full legend text shown on the button (e.g. ``F3=Exit``).
 * :param onActivate: Callback invoked when the key or its button is activated.
 * :param enabled: When ``false`` the key renders but neither clicks nor keydown fire.
 * :param dark: When ``true`` the legend is not displayed, the effect of a BMS
 *     ``ATTRB=DRK`` legend field the program has not yet un-darkened. The AID stays
 *     live, so the key still reaches the screen's handler exactly as it reaches a
 *     3270 program whose legend text happens to be non-display.
 */
export interface PFKeyDef {
  action: PfKeyAction;
  label: string;
  onActivate: () => void;
  enabled?: boolean;
  dark?: boolean;
}

/**
 * :purpose: Props for :func:`PFKeyBar`.
 * :param keys: Enabled function keys and handlers supplied by the active page.
 * :param tone: The colour the screen's mapset declares on its line-24 legend field.
 * :param inputInhibited: ``true`` while the screen's transaction is outstanding. A
 *     3270 keyboard is locked for the whole of a transaction, so no AID reaches the
 *     program and no legend key can be pressed until the reply arrives.
 * :param onAidDispatched: Notified with the dispatched action every time an AID is
 *     accepted, whether from a physical key or from a legend button. The shell uses
 *     it to count sends, which is what re-announces a repeated message and
 *     re-captures the header clock.
 * :param onUnhandledAid: Notified with an AID the active screen does NOT declare. This
 *     is the ``WHEN OTHER`` arm of the program's ``EVALUATE EIBAID``: a 3270 transmits
 *     every attention identifier to the program, which always answers -- twelve of the
 *     seventeen with ``MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE``, the other five by
 *     remapping the key to ENTER (``IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE``).
 *     Omitting it silently discards the key, which no program does.
 */
export interface PFKeyBarProps {
  keys: PFKeyDef[];
  inputInhibited?: boolean;
  onAidDispatched?: (action: PfKeyAction) => void;
  onUnhandledAid?: (action: PfKeyAction) => void;
  tone?: PFKeyBarTone;
}

/**
 * :purpose: The colour a mapset declares on its line-24 legend field. Fifteen of the
 *     seventeen mapsets declare ``COLOR=YELLOW``; ``COACTVW`` and ``COCRDLI`` declare
 *     ``COLOR=TURQUOISE``.
 */
export type PFKeyBarTone = 'yellow' | 'turquoise';

/**
 * :purpose: Translate a physical ``KeyboardEvent.key`` into the matching
 *     :class:`PfKeyAction`, or ``null`` when the key is not a mapped AID.
 * :param key: The DOM keyboard key name.
 * :returns: The corresponding PF-key action, or ``null``.
 */
function keyToAction(key: string): PfKeyAction | null {
  switch (key) {
    case 'Enter':
      return PfKeyAction.Enter;
    case 'F3':
      return PfKeyAction.PF3;
    case 'F4':
      return PfKeyAction.PF4;
    case 'F5':
      return PfKeyAction.PF5;
    case 'F7':
      return PfKeyAction.PF7;
    case 'F8':
      return PfKeyAction.PF8;
    case 'F12':
      return PfKeyAction.PF12;
    default:
      return null;
  }
}

/**
 * :purpose: Whether ENTER struck on this element is the element's OWN activation rather
 *     than the screen's AID. A 3270 has no focusable chrome, so the only elements for
 *     which ENTER means "activate me" are the shell's own controls: the line-24 legend
 *     buttons, and the skip link that reaches them. Claiming the key for the screen
 *     would call ``preventDefault`` on their native activation and leave them operable
 *     by pointer alone -- and the skip link exists solely for the operator who has no
 *     pointer.
 * :param target: The keydown event target.
 * :returns: ``true`` when the browser's own action for the key must be left alone.
 * :note: The skip link is the only anchor the application renders, so this exemption
 *     cannot reach a screen field.
 */
function activatesItselfOnEnter(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLButtonElement ||
    (target instanceof HTMLAnchorElement && target.hasAttribute('href'))
  );
}

/**
 * :purpose: The shared line-24 PF-key bar.
 * :param keys: The enabled function keys and handlers for the active screen.
 * :returns: The rendered function-key toolbar.
 */
export default function PFKeyBar({
  keys,
  inputInhibited = false,
  onAidDispatched,
  onUnhandledAid,
  tone = 'yellow',
}: PFKeyBarProps): ReactElement {
  // The legend a page publishes is rebuilt whenever one of its handlers changes
  // identity. Holding it in a ref that every render refreshes lets the document listener
  // always read the latest handlers while being bound exactly once, so a keystroke can
  // never reach a handler from a superseded render and the listener is not rebound on
  // every publication.
  //
  // The refresh MUST be a layout effect, and it carries NO dependency list. A keystroke
  // is a discrete event, so React renders and commits it synchronously; passive effects
  // are deferred to a later scheduler task, which leaves a window in which a keydown
  // arriving right behind the keystroke reads the superseded generation of the legend and
  // submits the screen state as it was BEFORE that keystroke. A layout effect runs inside
  // the same commit, and the pages publish their chrome from a layout effect too, so the
  // whole publish chain (page render -> setChrome -> frame render -> this refresh)
  // completes before the browser dispatches the next event.
  const keysRef = useRef<PFKeyDef[]>(keys);
  useLayoutEffect(() => {
    keysRef.current = keys;
  });

  // Same reasoning for the two values the listener reads about the current send.
  const inhibitedRef = useRef<boolean>(inputInhibited);
  useLayoutEffect(() => {
    inhibitedRef.current = inputInhibited;
  }, [inputInhibited]);
  const notifyRef = useRef<((action: PfKeyAction) => void) | undefined>(onAidDispatched);
  useLayoutEffect(() => {
    notifyRef.current = onAidDispatched;
  }, [onAidDispatched]);
  const unhandledRef = useRef<((action: PfKeyAction) => void) | undefined>(onUnhandledAid);
  useLayoutEffect(() => {
    unhandledRef.current = onUnhandledAid;
  }, [onUnhandledAid]);

  // The rendered lock (`inputInhibited`, the disabled legend and `X SYSTEM`) is a
  // committed view state, so it can only refuse a key that arrives in a LATER task than
  // the one that sent the previous AID. A key repeated inside a single task reads a
  // screen that has not yet been told it is busy, so each press would send its own
  // request carrying the cursor of the page still displayed — five PF8 presses issuing
  // five identical requests, or three ENTERs issuing three writes. This latch closes
  // that window synchronously, before the screen's own handler runs.
  const aidPendingRef = useRef<Set<PfKeyAction>>(new Set());
  useLayoutEffect(() => {
    if (!inputInhibited) {
      aidPendingRef.current.clear();
    }
  }, [inputInhibited]);

  /**
   * :purpose: Send one attention identifier, refusing it when the keyboard is locked by
   *     the screen's committed busy state, and refusing a repeat of the SAME identifier
   *     whose outcome is not yet known.
   * :param action: The AID being sent.
   * :note: Both activation paths -- the document keydown listener and the legend button --
   *     resolve the handler through ``keysRef`` here, so a key press and a click on the
   *     same legend key can never disagree about which handler is current. A 3270
   *     delivered the AID with the screen as displayed, so the handler reached must be the
   *     one belonging to the newest render of that screen.
   */
  const dispatchAid = useCallback((action: PfKeyAction): void => {
    const declared = keysRef.current.find((k) => k.action === action);
    if (declared === undefined || declared.enabled === false) {
      return;
    }
    if (inhibitedRef.current || aidPendingRef.current.has(action)) {
      return;
    }
    aidPendingRef.current.add(action);
    // Released on the next microtask unless the AID actually started a transaction, in
    // which case the committed lock owns the release. A key that only repaints or
    // leaves the screen therefore costs the operator nothing.
    queueMicrotask(() => {
      if (!inhibitedRef.current) {
        aidPendingRef.current.delete(action);
      }
    });
    notifyRef.current?.(action);
    declared.onActivate();
  }, []);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent): void {
      const action = keyToAction(event.key);
      if (action === null) {
        return;
      }
      // Leave ENTER alone when the focused element activates itself on it: a legend
      // button's native click handler fires on its own, and the skip link needs its
      // native navigation.
      if (action === PfKeyAction.Enter && activatesItselfOnEnter(event.target)) {
        return;
      }
      const declared = keysRef.current.find((k) => k.action === action);
      if (declared === undefined) {
        // An AID the screen does not declare. A 3270 transmits it anyway and the program
        // answers it -- the ``WHEN OTHER`` arm of its ``EVALUATE EIBAID`` -- so the key is
        // claimed here too and handed to the screen's own unhandled-key policy rather than
        // being dropped. Claiming it is also what stops the browser acting on the key
        // instead: F5 reloading the screen, or F12 opening the inspector, in the middle of
        // a transaction the operator meant to send.
        const unhandled = unhandledRef.current;
        if (unhandled === undefined) {
          return;
        }
        event.preventDefault();
        // The keyboard is locked for the whole of a transaction, so an AID struck while
        // one is outstanding is discarded rather than queued -- the same rule the
        // declared keys follow below.
        if (inhibitedRef.current) {
          return;
        }
        // The program answers by re-sending its map, so the send is counted exactly as a
        // declared key's is: that is what re-announces the message region and re-places
        // the cursor when the SAME key is struck twice.
        notifyRef.current?.(action);
        unhandled(action);
        return;
      }
      // The active screen claims this AID, so the browser's own action for the key
      // is suppressed whether or not the key is currently accepted: a disabled F5
      // must not reload the page out from under a request that is still in flight.
      event.preventDefault();
      if (declared.enabled === false) {
        return;
      }
      // A 3270 keyboard is locked from the moment an AID is sent until the program
      // replies, so every key struck while the reply is outstanding is discarded
      // rather than queued. Without this, a repeated PF8 issues one request per
      // press while every one of them still carries the first page's cursor.
      dispatchAid(action);
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
    // `dispatchAid` is created once and reads everything that varies through refs, so its
    // identity never changes; listing it would not change when this effect runs. The
    // listener is bound exactly once on purpose.
  }, [dispatchAid]);

  return (
    <div
      className={tone === 'turquoise' ? 'pfKeyBar pfKeyBar--turquoise' : 'pfKeyBar'}
      role="group"
      aria-label="Function keys"
    >
      {keys
        .filter((k) => k.dark !== true)
        .map((k) => (
          <button
            key={k.action}
            type="button"
            className="pfKey"
            disabled={k.enabled === false || inputInhibited}
            onClick={() => {
              dispatchAid(k.action);
            }}
          >
            {k.label}
          </button>
        ))}
    </div>
  );
}

/**
 * Header ======
 * :purpose: Reproduce the BMS 3270 screen header as up to three left/center/right rows.
 *     Row 1: the transaction caption + transaction id, the first title line, the date caption
 *     + date. Row 2: the program caption + program name, the second title line, the time
 *     caption + time. Row 3, rendered only for the sign-on mapset: ``AppID:`` + application id
 *     and ``SysID:`` + system id.
 * :output: The rendered ``<header>`` element.
 * :note: The captions are per-mapset BMS literals and differ between the sign-on mapset
 *     and the other sixteen. ``app/bms/COSGN00.bms`` L33/L46/L56/L69 spell them ``'Tran :'``,
 *     ``'Date :'``, ``'Prog :'``, ``'Time :'`` and add the ``'AppID:'`` (L79) / ``'SysID:'``
 *     (L88) row; the sixteen sibling mapsets spell them ``'Tran:'``, ``'Date:'``, ``'Prog:'``,
 *     ``'Time:'`` and have no third row.
 * :data: `SIGNON_CAPTIONS` and :data:`STANDARD_CAPTIONS` hold the two sets verbatim,
 *     selected by the ``captionStyle`` prop.
 * :note: Date renders ``MM/DD/YY`` and time renders 24-hour ``HH:MM:SS`` per ``FUNCTION
 *     CURRENT-DATE`` formatting in ``app/cpy/CSDAT01Y.cpy``. Captions/values are BLUE; the
 *     titles are YELLOW.
 */
import { useState } from 'react';
import type { ReactElement } from 'react';

/**
 * :purpose: The four header captions of one mapset, held verbatim.
 * :field tran: caption preceding the transaction id.
 * :field prog: caption preceding the program name.
 * :field date: caption preceding the date.
 * :field time: caption preceding the time.
 */
export interface HeaderCaptions {
  readonly tran: string;
  readonly prog: string;
  readonly date: string;
  readonly time: string;
}

/**
 * :purpose: Sign-on mapset captions, verbatim from ``app/bms/COSGN00.bms`` L33
 *     (``'Tran :'``), L56 (``'Prog :'``), L46 (``'Date :'``) and L69 (``'Time :'``).
 */
export const SIGNON_CAPTIONS: HeaderCaptions = {
  tran: 'Tran :',
  prog: 'Prog :',
  date: 'Date :',
  time: 'Time :',
};

/**
 * :purpose: Captions of the sixteen sibling mapsets (``COMEN01`` through
 *     ``COUSR03``), which spell the same four literals without the space before the
 *     colon.
 */
export const STANDARD_CAPTIONS: HeaderCaptions = {
  tran: 'Tran:',
  prog: 'Prog:',
  date: 'Date:',
  time: 'Time:',
};

/**
 * :purpose: Which mapset's caption literals a screen uses. ``'signon'`` selects
 *     :data:`SIGNON_CAPTIONS` and renders the ``AppID:`` / ``SysID:`` row that only
 *     ``COSGN00`` carries; ``'standard'`` selects :data:`STANDARD_CAPTIONS`.
 */
export type HeaderCaptionStyle = 'signon' | 'standard';

/**
 * :purpose: Props for :func:`Header`.
 * :param transactionId: 4-char CICS transaction id shown after the transaction caption.
 * :param programName: Program / screen name shown after the program caption.
 * :param title01: First title line (screen title), rendered YELLOW as ``<h1>``.
 * :param title02: Second title line, rendered YELLOW as ``<h2>``.
 * :param currentDate: Authoritative server ``MM/DD/YY`` date captured at response time;
 *     when omitted, a single client snapshot taken at mount is used.
 * :param currentTime: Authoritative server 24-hour ``HH:MM:SS`` time captured at response
 *     time; when omitted, a single client snapshot taken at mount is used.
 * :param captionStyle: Which mapset's caption literals to render; defaults to
 *     ``'standard'``, the spelling used by sixteen of the seventeen mapsets.
 * :param appId: Application id shown after ``AppID:`` (``COSGN00.bms`` ``APPLID``,
 *     ``LENGTH=8``); rendered only for the ``'signon'`` caption style.
 * :param sysId: System id shown after ``SysID:`` (``COSGN00.bms`` ``SYSID``,
 *     ``LENGTH=8``); rendered only for the ``'signon'`` caption style.
 * :param sendCount: How many attention identifiers the screen has sent. Every program runs
 *     ``POPULATE-HEADER-INFO`` before each ``SEND MAP``, so the client snapshot is re-captured
 *     whenever this advances rather than showing the moment the screen was first mounted.
 */
export interface HeaderProps {
  transactionId?: string;
  programName?: string;
  title01?: string;
  title02?: string;
  currentDate?: string;
  currentTime?: string;
  captionStyle?: HeaderCaptionStyle;
  appId?: string;
  sysId?: string;
  sendCount?: number;
}

/**
 * :purpose: Left-pad a non-negative integer to two digits.
 * :param n: The value to pad.
 * :output: A two-character, zero-padded string.
 */
function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/**
 * :purpose: Format a date as ``MM/DD/YY`` (matches ``WS-CURDATE-MM-DD-YY``).
 * :param now: The moment to format.
 * :output: The formatted date string.
 */
export function formatDate(now: Date): string {
  const mm = pad2(now.getMonth() + 1);
  const dd = pad2(now.getDate());
  const yy = pad2(now.getFullYear() % 100);
  return `${mm}/${dd}/${yy}`;
}

/**
 * :purpose: Format a time as 24-hour ``HH:MM:SS`` (matches ``WS-CURTIME-HH-MM-SS``).
 * :param now: The moment to format.
 * :output: The formatted time string.
 */
export function formatTime(now: Date): string {
  return `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`;
}

/**
 * :purpose: The header clock, together with the send it was captured on.
 * :param sendCount: The send the moment belongs to; comparing it with the current send
 *     is what tells the header whether a new send has to re-capture the clock.
 * :param at: The moment captured for that send.
 */
interface HeaderClockCapture {
  sendCount: number;
  at: Date;
}

/**
 * :purpose: The shared screen header. Renders the transaction id, program name,
 *     two title lines, and a date/time held as a single static snapshot, plus an
 *     optional application/system id row.
 * :param props: See :class:`HeaderProps`.
 * :output: The rendered header element.
 */
export default function Header({
  transactionId = '',
  programName = '',
  title01 = '',
  title02 = '',
  currentDate,
  currentTime,
  captionStyle = 'standard',
  appId,
  sysId,
  sendCount = 0,
}: HeaderProps): ReactElement {
  // Legacy POPULATE-HEADER-INFO captures FUNCTION CURRENT-DATE once per SEND MAP, so
  // the server-supplied props are authoritative. When they are absent, a client
  // snapshot stands in — re-captured on each send rather than left at the moment the
  // screen was mounted, which is not a live clock and drifts no further than one send.
  //
  // The capture is adjusted DURING RENDER when the send count moves, rather than held
  // in state and replaced from an effect. An effect cannot run until after the commit,
  // so the send that re-captured the clock first painted the PREVIOUS send's time and
  // then replaced it — two paints for one send — and that replacement was a state update
  // React schedules from the commit phase, the one case its `act` bookkeeping in a test
  // cannot attribute to the interaction that caused it. Setting state during render is
  // React's own answer to deriving a value from a changed prop: the render output is
  // discarded and recomputed immediately, before anything is committed, so the send
  // paints once and schedules nothing. The send number is stored WITH the moment, which
  // is what makes the comparison the loop's own exit condition.
  const [capture, setCapture] = useState<HeaderClockCapture>(() => ({
    sendCount,
    at: new Date(),
  }));
  if (capture.sendCount !== sendCount) {
    setCapture({ sendCount, at: new Date() });
  }
  const snapshot = capture.at;
  const dateText = currentDate ?? formatDate(snapshot);
  const timeText = currentTime ?? formatTime(snapshot);
  const captions = captionStyle === 'signon' ? SIGNON_CAPTIONS : STANDARD_CAPTIONS;
  // Only COSGN00.bms carries the row. Both of its fields are filled by the program before
  // the first send -- `EXEC CICS ASSIGN APPLID` and `ASSIGN SYSID`, COSGN00C L198-L203 --
  // so the mapset's blank INITIALs are what the map holds before that, not what the
  // operator sees. The deployment supplies both values in their place.
  const showAppRow = captionStyle === 'signon';

  return (
    <header className="appHeader">
      <div className="appHeader__row">
        <span className="appHeader__left">
          <span className="label">{captions.tran}</span>{' '}
          <span className="label" data-testid="tran-id">{transactionId}</span>
        </span>
        <h1 className="appHeader__center title" data-testid="title01">{title01}</h1>
        <span className="appHeader__right">
          <span className="label">{captions.date}</span>{' '}
          <span className="label" data-testid="cur-date">{dateText}</span>
        </span>
      </div>
      <div className="appHeader__row">
        <span className="appHeader__left">
          <span className="label">{captions.prog}</span>{' '}
          <span className="label" data-testid="pgm-name">{programName}</span>
        </span>
        <h2 className="appHeader__center title" data-testid="title02">{title02}</h2>
        <span className="appHeader__right">
          <span className="label">{captions.time}</span>{' '}
          <span className="label" data-testid="cur-time">{timeText}</span>
        </span>
      </div>
      {showAppRow && (
        <div className="appHeader__row" data-testid="app-sys-row">
          <span className="appHeader__left">
            <span className="label">AppID:</span>{' '}
            <span className="label" data-testid="app-id">{appId ?? ''}</span>
          </span>
          <span className="appHeader__right">
            <span className="label">SysID:</span>{' '}
            <span className="label" data-testid="sys-id">{sysId ?? ''}</span>
          </span>
        </div>
      )}
    </header>
  );
}

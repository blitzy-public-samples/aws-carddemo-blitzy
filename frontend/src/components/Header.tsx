/**
 * Header
 * ======
 *
 * :purpose: Reproduce the BMS 3270 screen header (``app/bms/COSGN00.bms``) as up
 *     to three left/center/right rows. Row 1: ``Tran :`` + transaction id, the
 *     screen title, ``Date :`` + date. Row 2: ``Prog :`` + program name, the
 *     second title line, ``Time :`` + time. Optional row 3 (rendered only when an
 *     application or system id is supplied): ``AppID:`` + application id and
 *     ``SysID:`` + system id. Date renders ``MM/DD/YY`` and time renders 24-hour
 *     ``HH:MM:SS`` per ``FUNCTION CURRENT-DATE`` formatting in
 *     ``app/cpy/CSDAT01Y.cpy``. Labels/values are BLUE; the titles are YELLOW.
 * :output: The rendered ``<header>`` element.
 */
import { useState } from 'react';
import type { ReactElement } from 'react';

/**
 * :purpose: Props for :func:`Header`.
 * :param transactionId: 4-char CICS transaction id shown after ``Tran :``.
 * :param programName: Program / screen name shown after ``Prog :``.
 * :param title01: First title line (screen title), rendered YELLOW as ``<h1>``.
 * :param title02: Second title line, rendered YELLOW as ``<h2>``.
 * :param currentDate: Authoritative server ``MM/DD/YY`` date captured at response
 *     time; when omitted, a single client snapshot taken at mount is used.
 * :param currentTime: Authoritative server 24-hour ``HH:MM:SS`` time captured at
 *     response time; when omitted, a single client snapshot taken at mount is used.
 * :param appId: Application id shown after ``AppID:``; its presence renders row 3.
 * :param sysId: System id shown after ``SysID:``; its presence renders row 3.
 */
export interface HeaderProps {
  transactionId?: string;
  programName?: string;
  title01?: string;
  title02?: string;
  currentDate?: string;
  currentTime?: string;
  appId?: string;
  sysId?: string;
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
  appId,
  sysId,
}: HeaderProps): ReactElement {
  // Legacy POPULATE-HEADER-INFO captures FUNCTION CURRENT-DATE once when the map
  // is sent, so the server-supplied props are authoritative. When they are
  // absent, capture a single client snapshot at mount rather than a live clock.
  const [snapshot] = useState<Date>(() => new Date());
  const dateText = currentDate ?? formatDate(snapshot);
  const timeText = currentTime ?? formatTime(snapshot);
  const showAppRow = appId !== undefined || sysId !== undefined;

  return (
    <header className="appHeader">
      <div className="appHeader__row">
        <span className="appHeader__left">
          <span className="label">Tran :</span>{' '}
          <span className="label" data-testid="tran-id">{transactionId}</span>
        </span>
        <h1 className="appHeader__center title" data-testid="title01">{title01}</h1>
        <span className="appHeader__right">
          <span className="label">Date :</span>{' '}
          <span className="label" data-testid="cur-date">{dateText}</span>
        </span>
      </div>
      <div className="appHeader__row">
        <span className="appHeader__left">
          <span className="label">Prog :</span>{' '}
          <span className="label" data-testid="pgm-name">{programName}</span>
        </span>
        <h2 className="appHeader__center title" data-testid="title02">{title02}</h2>
        <span className="appHeader__right">
          <span className="label">Time :</span>{' '}
          <span className="label" data-testid="cur-time">{timeText}</span>
        </span>
      </div>
      {showAppRow && (
        <div className="appHeader__row" data-testid="app-sys-row">
          <span className="appHeader__left">
            <span className="label">AppID:</span>{' '}
            <span className="label" data-testid="app-id">{appId ?? ''}</span>
          </span>
          <span className="appHeader__center" aria-hidden="true" />
          <span className="appHeader__right">
            <span className="label">SysID:</span>{' '}
            <span className="label" data-testid="sys-id">{sysId ?? ''}</span>
          </span>
        </div>
      )}
    </header>
  );
}

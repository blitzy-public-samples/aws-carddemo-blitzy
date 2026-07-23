/**
 * Header
 * ======
 *
 * :purpose: Reproduce the two-row BMS 3270 screen header (``app/bms/COSGN00.bms``).
 *     Row 1: ``Tran :`` + transaction id, the screen title, ``Date :`` + current
 *     date. Row 2: ``Prog :`` + program name, the second title line, ``Time :`` +
 *     a live clock. Date renders ``MM/DD/YY`` and time renders 24-hour
 *     ``HH:MM:SS`` per ``FUNCTION CURRENT-DATE`` formatting in
 *     ``app/cpy/CSDAT01Y.cpy``. Labels/values are BLUE; the titles are YELLOW.
 */
import { useEffect, useState } from 'react';
import type { ReactElement } from 'react';

/**
 * :purpose: Props for :func:`Header`.
 * :param transactionId: 4-char CICS transaction id shown after ``Tran :``.
 * :param programName: Program / screen name shown after ``Prog :``.
 * :param title01: First title line (screen title), rendered YELLOW.
 * :param title02: Second title line, rendered YELLOW.
 */
export interface HeaderProps {
  transactionId?: string;
  programName?: string;
  title01?: string;
  title02?: string;
}

/**
 * :purpose: Left-pad a non-negative integer to two digits.
 * :param n: The value to pad.
 * :returns: A two-character, zero-padded string.
 */
function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/**
 * :purpose: Format a date as ``MM/DD/YY`` (matches ``WS-CURDATE-MM-DD-YY``).
 * :param now: The moment to format.
 * :returns: The formatted date string.
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
 * :returns: The formatted time string.
 */
export function formatTime(now: Date): string {
  return `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`;
}

/**
 * :purpose: The shared two-row screen header with a live-ticking clock.
 * :param transactionId: Transaction id shown after ``Tran :``.
 * :param programName: Program name shown after ``Prog :``.
 * :param title01: First (screen) title, rendered YELLOW.
 * :param title02: Second title line, rendered YELLOW.
 * :returns: The rendered header element.
 */
export default function Header({
  transactionId = '',
  programName = '',
  title01 = '',
  title02 = '',
}: HeaderProps): ReactElement {
  const [now, setNow] = useState<Date>(() => new Date());

  useEffect(() => {
    const timer = setInterval(() => {
      setNow(new Date());
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, []);

  return (
    <header className="appHeader">
      <div className="headerRow">
        <span className="label">Tran :</span>
        <span className="label" data-testid="tran-id">{transactionId}</span>
        <span className="title" data-testid="title01">{title01}</span>
        <span className="label">Date :</span>
        <span className="label" data-testid="cur-date">{formatDate(now)}</span>
      </div>
      <div className="headerRow">
        <span className="label">Prog :</span>
        <span className="label" data-testid="pgm-name">{programName}</span>
        <span className="title" data-testid="title02">{title02}</span>
        <span className="label">Time :</span>
        <span className="label" data-testid="cur-time">{formatTime(now)}</span>
      </div>
    </header>
  );
}

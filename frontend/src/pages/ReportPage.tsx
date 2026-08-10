/**
 * ReportPage
 * ==========
 *
 * :purpose: Transaction-report request screen: pick the Monthly, Yearly or Custom
 *     report window, answer the ``Y``/``N`` confirmation, and launch the
 *     report-generation batch job through ``POST /reports``. 1:1 replacement for
 *     the BMS mapset ``app/bms/CORPT00.bms`` (CICS transaction ``CR00``, program
 *     ``app/cbl/CORPT00C.cbl``): captions, hints, validation messages, the
 *     confirmation gate and the line-24 legend are reproduced verbatim, and the
 *     legacy write to the extra-partition TDQ ``'JOBS'`` becomes the asynchronous
 *     ``JobLauncher`` hand-off performed behind that endpoint.
 * :output: The screen body only. Transaction id, program name, titles, the
 *     line-23 message and the line-24 function keys are published to the shared
 *     terminal shell through :func:`useScreenChrome`.
 */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  PfKeyAction,
  REPORT_TYPES,
  CCDA_TITLE01,
  CCDA_TITLE02,
  CCDA_MSG_INVALID_KEY,
} from '../types';
import type {
  ReportDateParts,
  ReportRequestDto,
  ReportResponseDto,
  ReportType,
} from '../types';
import { requestReport, ApiError } from '../api';
import { placeCursor, useApi, useScreenAction } from '../hooks';

/**
 * :purpose: Report name each screen message is built from (``WS-REPORT-NAME``).
 */
const REPORT_NAMES: Record<ReportType, string> = {
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly',
  CUSTOM: 'Custom',
};

/**
 * :purpose: Report-window captions, verbatim from the ``CORPT00`` mapset fields
 *     ``MONTHLY``, ``YEARLY`` and ``CUSTOM``.
 */
const REPORT_TYPE_LABELS: Record<ReportType, string> = {
  MONTHLY: 'Monthly (Current Month)',
  YEARLY: 'Yearly (Current Year)',
  CUSTOM: 'Custom (Date Range)',
};

/** :purpose: Value carried by the chosen report-window flag (``PIC X(01)``). */
const SELECTED_FLAG = 'Y';

/** :purpose: Highest accepted month (COBOL ``> '12'`` range edit). */
const MAX_MONTH = 12;

/** :purpose: Highest accepted day (COBOL ``> '31'`` range edit). */
const MAX_DAY = 31;

/** :purpose: Day count per month, January first; February is resolved by leap year. */
const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31] as const;

/** :purpose: Day count of a leap February. */
const LEAP_FEBRUARY_DAYS = 29;

/* Screen messages, character-for-character from CORPT00C. */
const MSG_SELECT_REPORT_TYPE = 'Select a report type to print report...';
const MSG_START_MONTH_EMPTY = 'Start Date - Month can NOT be empty...';
const MSG_START_DAY_EMPTY = 'Start Date - Day can NOT be empty...';
const MSG_START_YEAR_EMPTY = 'Start Date - Year can NOT be empty...';
const MSG_END_MONTH_EMPTY = 'End Date - Month can NOT be empty...';
const MSG_END_DAY_EMPTY = 'End Date - Day can NOT be empty...';
const MSG_END_YEAR_EMPTY = 'End Date - Year can NOT be empty...';
const MSG_START_MONTH_INVALID = 'Start Date - Not a valid Month...';
const MSG_START_DAY_INVALID = 'Start Date - Not a valid Day...';
const MSG_START_YEAR_INVALID = 'Start Date - Not a valid Year...';
const MSG_END_MONTH_INVALID = 'End Date - Not a valid Month...';
const MSG_END_DAY_INVALID = 'End Date - Not a valid Day...';
const MSG_END_YEAR_INVALID = 'End Date - Not a valid Year...';
const MSG_START_DATE_INVALID = 'Start Date - Not a valid date...';
const MSG_END_DATE_INVALID = 'End Date - Not a valid date...';
const CONFIRM_PROMPT_PREFIX = 'Please confirm to print the ';
const CONFIRM_PROMPT_SUFFIX = ' report...';
const INVALID_CONFIRM_SUFFIX = '" is not a valid value to confirm...';
const SUBMIT_SUCCESS_SUFFIX = ' report submitted for printing ...';

/**
 * :purpose: Control that receives the cursor on the next screen send. Each
 *     ``CORPT00C`` path ends with ``MOVE -1 TO <field>L`` naming one of the
 *     mapset's eight enterable fields; the values are the control ids.
 */
type ReportCursorField =
  | 'reportType-MONTHLY'
  | 'startDateMonth'
  | 'startDateDay'
  | 'startDateYear'
  | 'endDateMonth'
  | 'endDateDay'
  | 'endDateYear'
  | 'confirm';

/**
 * :purpose: A failed edit: the message for line 23 and the field the legacy
 *     screen cursors to when it re-sends the map.
 */
interface ReportEdit {
  field: ReportCursorField;
  message: string;
}

/**
 * :purpose: Report whether a date part was left empty (COBOL ``= SPACES OR
 *     LOW-VALUES``).
 * :param value: the entered date part.
 * :returns: ``true`` when the part is absent or blank.
 */
function isEmptyPart(value: string | undefined): boolean {
  return value === undefined || value.trim() === '';
}

/**
 * :purpose: Parse a date part the way ``FUNCTION NUMVAL-C`` followed by ``IS
 *     NUMERIC`` does: only all-digit text yields a value.
 * :param value: the entered date part.
 * :returns: the parsed number, or ``null`` when the part is not all digits.
 */
function parseDatePart(value: string | undefined): number | null {
  if (value === undefined) {
    return null;
  }
  const trimmed = value.trim();
  if (trimmed === '' || !/^\d+$/.test(trimmed)) {
    return null;
  }
  return Number(trimmed);
}

/**
 * :purpose: Report whether year/month/day form a real Gregorian date, which is the
 *     ``CSUTLDTC`` outcome ``CORPT00C`` accepts. A date before ``1582-10-15`` is
 *     acceptable too: the program tolerates message number ``2513``.
 * :param year: the entered year.
 * :param month: the entered month.
 * :param day: the entered day.
 * :returns: ``true`` when the date exists.
 */
function isCalendarDate(year: number, month: number, day: number): boolean {
  if (year < 1 || month < 1 || month > MAX_MONTH || day < 1) {
    return false;
  }
  const leapFebruary =
    month === 2 && year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const lastDay = leapFebruary ? LEAP_FEBRUARY_DAYS : DAYS_IN_MONTH[month - 1];
  return day <= lastDay;
}

/**
 * :purpose: Validate the custom date window in the order ``CORPT00C``
 *     ``PROCESS-ENTER-KEY`` applies — the six emptiness edits, then the six
 *     numeric and range edits, then the two calendar edits — stopping at the first
 *     failure as the legacy screen does.
 * :param start: the entered start-date month, day and year parts.
 * :param end: the entered end-date month, day and year parts.
 * :returns: the failed edit with the field the legacy screen cursors to, or
 *     ``null`` when the window is valid.
 */
function validateCustomWindow(
  start: ReportDateParts,
  end: ReportDateParts,
): ReportEdit | null {
  if (isEmptyPart(start.month)) {
    return { field: 'startDateMonth', message: MSG_START_MONTH_EMPTY };
  }
  if (isEmptyPart(start.day)) {
    return { field: 'startDateDay', message: MSG_START_DAY_EMPTY };
  }
  if (isEmptyPart(start.year)) {
    return { field: 'startDateYear', message: MSG_START_YEAR_EMPTY };
  }
  if (isEmptyPart(end.month)) {
    return { field: 'endDateMonth', message: MSG_END_MONTH_EMPTY };
  }
  if (isEmptyPart(end.day)) {
    return { field: 'endDateDay', message: MSG_END_DAY_EMPTY };
  }
  if (isEmptyPart(end.year)) {
    return { field: 'endDateYear', message: MSG_END_YEAR_EMPTY };
  }

  const startMonth = parseDatePart(start.month);
  if (startMonth === null || startMonth > MAX_MONTH) {
    return { field: 'startDateMonth', message: MSG_START_MONTH_INVALID };
  }
  const startDay = parseDatePart(start.day);
  if (startDay === null || startDay > MAX_DAY) {
    return { field: 'startDateDay', message: MSG_START_DAY_INVALID };
  }
  const startYear = parseDatePart(start.year);
  if (startYear === null) {
    return { field: 'startDateYear', message: MSG_START_YEAR_INVALID };
  }
  const endMonth = parseDatePart(end.month);
  if (endMonth === null || endMonth > MAX_MONTH) {
    return { field: 'endDateMonth', message: MSG_END_MONTH_INVALID };
  }
  const endDay = parseDatePart(end.day);
  if (endDay === null || endDay > MAX_DAY) {
    return { field: 'endDateDay', message: MSG_END_DAY_INVALID };
  }
  const endYear = parseDatePart(end.year);
  if (endYear === null) {
    return { field: 'endDateYear', message: MSG_END_YEAR_INVALID };
  }

  // The two calendar edits both cursor to the month part of their window.
  if (!isCalendarDate(startYear, startMonth, startDay)) {
    return { field: 'startDateMonth', message: MSG_START_DATE_INVALID };
  }
  if (!isCalendarDate(endYear, endMonth, endDay)) {
    return { field: 'endDateMonth', message: MSG_END_DATE_INVALID };
  }
  return null;
}

/**
 * :purpose: Assemble the ``POST /reports`` body: the chosen report-window flag,
 *     the custom date parts exactly as entered (custom window only) and the
 *     confirmation flag.
 * :param reportType: the chosen report window.
 * :param start: the entered start-date parts.
 * :param end: the entered end-date parts.
 * :param confirmValue: the entered confirmation flag.
 * :returns: the request payload.
 */
function buildReportRequest(
  reportType: ReportType,
  start: ReportDateParts,
  end: ReportDateParts,
  confirmValue: string,
): ReportRequestDto {
  switch (reportType) {
    case 'MONTHLY':
      return { monthly: SELECTED_FLAG, confirm: confirmValue };
    case 'YEARLY':
      return { yearly: SELECTED_FLAG, confirm: confirmValue };
    case 'CUSTOM':
      return {
        custom: SELECTED_FLAG,
        startDateMonth: start.month,
        startDateDay: start.day,
        startDateYear: start.year,
        endDateMonth: end.month,
        endDateDay: end.day,
        endDateYear: end.year,
        confirm: confirmValue,
      };
  }
}

/**
 * :purpose: Resolve the line-23 text of a failed submission: the backend
 *     ``ApiErrorResponse.message``, falling back to the normalized transport
 *     message when the failure carries no response body.
 * :param error: the normalized error raised by ``POST /reports``.
 * :returns: the message to display.
 */
function submissionErrorMessage(error: ApiError): string {
  const bodyMessage = error.body?.message;
  return bodyMessage !== undefined && bodyMessage !== '' ? bodyMessage : error.message;
}

/**
 * :purpose: The transaction-report request screen (CICS ``CR00`` / program
 *     ``CORPT00C``). Holds the report-window selection, the custom date-range
 *     entry and the confirmation flag, reproduces the legacy edits in their
 *     original order, and submits the confirmed request so the report job is
 *     launched asynchronously.
 * :returns: The rendered screen body.
 */
export default function ReportPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const {
    run: submitReport,
    loading: submitting,
    error: submitError,
  } = useApi<ReportResponseDto, [ReportRequestDto]>(requestReport);

  const [reportType, setReportType] = useState<ReportType | ''>('');
  const [startMonth, setStartMonth] = useState('');
  const [startDay, setStartDay] = useState('');
  const [startYear, setStartYear] = useState('');
  const [endMonth, setEndMonth] = useState('');
  const [endDay, setEndDay] = useState('');
  const [endYear, setEndYear] = useState('');
  const [confirmInput, setConfirmInput] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  // The reference the published message belongs to: the execution a confirmed submission
  // launched, or the correlation id of the envelope that refused it. Carried as an
  // attribute on the message region rather than as screen text, because CORPT00's message
  // field is a frozen literal contract and a launch that cannot be traced is not
  // observable.
  const [messageReference, setMessageReference] = useState<string | null>(null);
  const [infoMessage, setInfoMessage] = useState('');

  // Set synchronously for the whole submission; a second ENTER activation while it
  // is set returns without launching the report job again.
  const submissionInFlight = useRef(false);

  const [cursor, setCursor] = useState<{
    field: ReportCursorField;
    seq: number;
  }>({ field: 'reportType-MONTHLY', seq: 0 });

  /**
   * :purpose: ``MOVE -1 TO <field>L`` — name the control the next screen send
   *     places the cursor on. ``seq`` advances so a repeated outcome on the same
   *     field still moves the cursor.
   * :param field: Control that receives the cursor.
   */
  // The faulted control is the one the screen sends the cursor to. Every `CORPT00C`
  // path that reports something ends with `MOVE -1 TO <field>L`, so the cursor target
  // IS the control the row-23 message is about -- including the messages the SERVER
  // produces, which no client-side table of message text can enumerate.
  //
  // EXCEPT for the two messages that are not about a value at all. The confirmation
  // prompt `CORPT00C` publishes over an EMPTY confirm field once the dates have passed
  // their edits reports what the screen is waiting for, and an entry the operator has not
  // made yet cannot be wrong; `Invalid key pressed. Please see below...` (the `WHEN OTHER`
  // branch of `EVALUATE EIBAID`, L191-195) rejects an attention identifier rather than a
  // field. `"<value>" is not a valid value to confirm...` IS a refused value and still
  // marks.
  const isConfirmationPrompt =
    errorMessage.startsWith(CONFIRM_PROMPT_PREFIX) &&
    errorMessage.endsWith(CONFIRM_PROMPT_SUFFIX);
  const faultedField: ReportCursorField | null =
    errorMessage === '' || isConfirmationPrompt || errorMessage === CCDA_MSG_INVALID_KEY
      ? null
      : cursor.field;

  /**
   * :purpose: Ask for the cursor to be placed on a field, which is the program's
   *     ``MOVE -1 TO <field>L``. The sequence number makes a repeated request for the
   *     same field a NEW request, because a program performs that move on every pass
   *     that ends the same way -- the placement is not conditional on the field having
   *     changed. The move itself happens once the screen has settled, since the entry
   *     controls are disabled while its call is outstanding.
   * :param field: the field the cursor is owed to.
   * :returns: nothing.
   */
  const requestCursor = useCallback((field: ReportCursorField): void => {
    setCursor((previous) => ({ field, seq: previous.seq + 1 }));
  }, []);

  // The entry controls are disabled for the keyboard-locked interval and focusing
  // a disabled control is a no-op, so placement waits for the submission to settle.
  useEffect(() => {
    if (submitting) {
      return;
    }
    placeCursor(document.getElementById(cursor.field));
  }, [cursor, submitting]);

  // Clears every entry field, as ``INITIALIZE-ALL-FIELDS`` does.
  const resetFields = useCallback((): void => {
    setReportType('');
    setStartMonth('');
    setStartDay('');
    setStartYear('');
    setEndMonth('');
    setEndDay('');
    setEndYear('');
    setConfirmInput('');
  }, []);

  /**
   * :purpose: ENTER handler. Edits the report-window selection and, for the custom
   *     window, the date range; then applies the ``SUBMIT-JOB-TO-INTRDR``
   *     confirmation gate: a blank flag prompts for confirmation, ``N`` clears the
   *     screen, ``Y`` submits the request and reports the submission, and any other
   *     value is rejected.
   * :returns: A promise that settles once the outcome has been published.
   */
  const handleEnter = useCallback(async (): Promise<void> => {
    if (submissionInFlight.current) {
      return;
    }
    if (reportType === '') {
      setInfoMessage('');
      setErrorMessage(MSG_SELECT_REPORT_TYPE);
      requestCursor('reportType-MONTHLY');
      return;
    }

    const start: ReportDateParts = {
      month: startMonth,
      day: startDay,
      year: startYear,
    };
    const end: ReportDateParts = { month: endMonth, day: endDay, year: endYear };

    if (reportType === 'CUSTOM') {
      const failure = validateCustomWindow(start, end);
      if (failure !== null) {
        setInfoMessage('');
        setErrorMessage(failure.message);
        requestCursor(failure.field);
        return;
      }
    }

    const reportName = REPORT_NAMES[reportType];
    const confirmValue = confirmInput.trim();

    if (confirmValue === '') {
      setInfoMessage('');
      setErrorMessage(`${CONFIRM_PROMPT_PREFIX}${reportName}${CONFIRM_PROMPT_SUFFIX}`);
      requestCursor('confirm');
      return;
    }
    if (confirmValue === 'N' || confirmValue === 'n') {
      resetFields();
      setInfoMessage('');
      setErrorMessage('');
      requestCursor('reportType-MONTHLY');
      return;
    }
    if (confirmValue !== 'Y' && confirmValue !== 'y') {
      setInfoMessage('');
      setErrorMessage(`"${confirmValue}${INVALID_CONFIRM_SUFFIX}`);
      requestCursor('confirm');
      return;
    }

    submissionInFlight.current = true;
    let response: ReportResponseDto | undefined;
    try {
      response = await submitReport(
        buildReportRequest(reportType, start, end, confirmValue),
      );
    } finally {
      submissionInFlight.current = false;
    }
    if (response === undefined) {
      // The normalized failure reaches line 23 through the submission effect.
      requestCursor('reportType-MONTHLY');
      return;
    }

    // Which channel the text arrives on IS the colour CORPT00C sent it in: the service
    // populates `message` only where the program performs `MOVE DFHGREEN TO ERRMSGC`,
    // and `errorMessage` everywhere else. Deciding it by comparing the text against a
    // locally composed copy of the expected acknowledgement meant any wording drift on
    // either side would have rendered a failure in success green.
    const refusal = response.errorMessage?.trim() ?? '';
    if (refusal !== '') {
      setInfoMessage('');
      setErrorMessage(refusal);
      setMessageReference(null);
      requestCursor('reportType-MONTHLY');
      return;
    }
    const acknowledgement = response.message?.trim() ?? '';
    resetFields();
    setErrorMessage('');
    setInfoMessage(
      acknowledgement !== ''
        ? acknowledgement
        : `${reportName}${SUBMIT_SUCCESS_SUFFIX}`,
    );
    setMessageReference(response.jobExecutionId ?? null);
    requestCursor('reportType-MONTHLY');
  }, [
    confirmInput,
    endDay,
    endMonth,
    endYear,
    requestCursor,
    reportType,
    resetFields,
    startDay,
    startMonth,
    startYear,
    submitReport,
  ]);

  // F3 leaves the screen for the main menu, as ``XCTL PROGRAM('COMEN01C')`` does.
  const handleExit = useCallback((): void => {
    void navigate('/menu');
  }, [navigate]);

  // Publishes a failed submission on the line-23 message region.
  useEffect(() => {
    if (submitError !== null) {
      setInfoMessage('');
      setErrorMessage(submissionErrorMessage(submitError));
      setMessageReference(submitError.correlationId ?? null);
    }
  }, [submitError]);

  // Publishes the screen chrome: header fields, line-23 message and line-24 keys.
  // ENTER renders disabled while a submission is in flight.
  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``CORPT00C`` L191-195) — publish
   *     ``CCDA-MSG-INVALID-KEY`` and re-send the map. ``CORPT00.bms`` gives MONTHLY
   *     the ``IC`` attribute and the program also ends ``MOVE -1 TO MONTHLYL``, so the
   *     cursor returns to the monthly report type. Nothing entered was rejected.
   */
  const handleUnhandledKey = useCallback((): void => {
    setInfoMessage('');
    setMessageReference(null);
    setErrorMessage(CCDA_MSG_INVALID_KEY);
    requestCursor('reportType-MONTHLY');
  }, [requestCursor]);

  const activateExit = useScreenAction(handleExit);
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);
  const activateEnter = useScreenAction((): void => {
    void handleEnter();
  });

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: 'ENTER=Continue',
        onActivate: activateEnter,
        enabled: !submitting,
      },
      {
        action: PfKeyAction.PF3,
        label: 'F3=Back',
        onActivate: activateExit,
      },
    ];
    setChrome({
      transactionId: 'CR00',
      programName: 'CORPT00C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      messageReference,
      pfKeys,
      onUnhandledKey: activateUnhandledKey,
      busy: submitting,
    });
  }, [
    activateEnter,
    activateExit,
    activateUnhandledKey,
    messageReference,
    errorMessage,
    infoMessage,
    setChrome,
    submitting,
  ]);

  return (
    <div className="reportPage">
      <h3 className="neutral reportPage__heading" id="reportHeading">
        Transaction Reports
      </h3>

      <div
        className="reportPage__types"
        role="radiogroup"
        aria-labelledby="reportHeading"
        {...invalidFieldProps(faultedField === 'reportType-MONTHLY')}
      >
        {REPORT_TYPES.map((type) => (
          <div className="reportPage__option" key={type}>
            <input
              type="radio"
              id={`reportType-${type}`}
              name="reportType"
              value={type}
              checked={reportType === type}
              disabled={submitting}
              {...invalidFieldProps(faultedField === `reportType-${type}`)}
              onChange={() => {
                setReportType(type);
                // A rejection belongs to the turn that produced it. Changing the
                // selection changes the very input that turn rejected, so the message,
                // the invalid marking derived from it, and the cursor placement all
                // have to go with it -- CORPT00C clears WS-MESSAGE at the top of every
                // turn and never redisplays a previous one. The cursor is re-placed on
                // the control the operator just chose rather than left where the
                // rejection put it, so it is never dropped onto the document body.
                setErrorMessage('');
                setInfoMessage('');
                requestCursor(`reportType-${type}` as ReportCursorField);
              }}
            />{' '}
            <label className="prompt" htmlFor={`reportType-${type}`}>
              {REPORT_TYPE_LABELS[type]}
            </label>
          </div>
        ))}
      </div>

      {/*
        All six date parts stay enterable in every state of the screen. Each is
        declared ``ATTRB=(FSET,NORM,NUM,UNPROT) COLOR=GREEN HILIGHT=UNDERLINE``
        (app/bms/CORPT00.bms:127-193) and CORPT00C moves no attribute byte to any
        field, so the mapset offers one entry treatment and never protects these
        boxes. The window selection decides only whether the entered range is
        *read*: CORPT00C:212 evaluates the selectors in order and inspects the date
        parts under the custom branch alone, which is why buildReportRequest sends
        them for CUSTOM only. `submitting` is the shell-wide keyboard lock.
      */}
      <div className="reportPage__range">
        <div className="reportPage__dateRow">
          <span className="prompt">Start Date :</span>{' '}
          <input
            type="text"
            id="startDateMonth"
            className="field"
            {...invalidFieldProps(faultedField === 'startDateMonth')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={startMonth}
            disabled={submitting}
            aria-label="Start Date - Month"
            onChange={(event) => {
              setStartMonth(event.target.value);
            }}
          />
          <span className="label" aria-hidden="true">
            /
          </span>
          <input
            type="text"
            id="startDateDay"
            className="field"
            {...invalidFieldProps(faultedField === 'startDateDay')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={startDay}
            disabled={submitting}
            aria-label="Start Date - Day"
            onChange={(event) => {
              setStartDay(event.target.value);
            }}
          />
          <span className="label" aria-hidden="true">
            /
          </span>
          <input
            type="text"
            id="startDateYear"
            className="field"
            {...invalidFieldProps(faultedField === 'startDateYear')}
            inputMode="numeric"
            maxLength={4}
            size={4}
            value={startYear}
            disabled={submitting}
            aria-label="Start Date - Year"
            onChange={(event) => {
              setStartYear(event.target.value);
            }}
          />{' '}
          <span className="label">(MM/DD/YYYY)</span>
        </div>

        <div className="reportPage__dateRow">
          <span className="prompt">{'  End Date :'}</span>{' '}
          <input
            type="text"
            id="endDateMonth"
            className="field"
            {...invalidFieldProps(faultedField === 'endDateMonth')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={endMonth}
            disabled={submitting}
            aria-label="End Date - Month"
            onChange={(event) => {
              setEndMonth(event.target.value);
            }}
          />
          <span className="label" aria-hidden="true">
            /
          </span>
          <input
            type="text"
            id="endDateDay"
            className="field"
            {...invalidFieldProps(faultedField === 'endDateDay')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={endDay}
            disabled={submitting}
            aria-label="End Date - Day"
            onChange={(event) => {
              setEndDay(event.target.value);
            }}
          />
          <span className="label" aria-hidden="true">
            /
          </span>
          <input
            type="text"
            id="endDateYear"
            className="field"
            {...invalidFieldProps(faultedField === 'endDateYear')}
            inputMode="numeric"
            maxLength={4}
            size={4}
            value={endYear}
            disabled={submitting}
            aria-label="End Date - Year"
            onChange={(event) => {
              setEndYear(event.target.value);
            }}
          />{' '}
          <span className="label">(MM/DD/YYYY)</span>
        </div>
      </div>

      <div className="reportPage__confirm">
        <label className="prompt" htmlFor="confirm">
          {'The Report will be submitted for printing. Please confirm: '}
        </label>
        <input
          type="text"
          id="confirm"
          className="field"
          {...invalidFieldProps(faultedField === 'confirm', 'reportConfirmValues')}
          maxLength={1}
          size={1}
          value={confirmInput}
          disabled={submitting}
          onChange={(event) => {
            setConfirmInput(event.target.value);
          }}
        />{' '}
        <span className="neutral" id="reportConfirmValues">
          (Y/N)
        </span>
      </div>
    </div>
  );
}

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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, REPORT_TYPES, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  ReportDateParts,
  ReportRequestDto,
  ReportResponseDto,
  ReportType,
} from '../types';
import { requestReport, ApiError } from '../api';
import { useApi } from '../hooks';

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
 * :purpose: The control each ``CORPT00C`` edit faults for the value it rejected.
 *     The confirmation prompt, the ``N`` reset and the submitted/TDQ outcomes report a
 *     screen state rather than a rejected value, so none of them appears here and none
 *     marks a control invalid.
 */
const FAULTED_FIELD_BY_MESSAGE: Readonly<Record<string, ReportCursorField>> = {
  [MSG_SELECT_REPORT_TYPE]: 'reportType-MONTHLY',
  [MSG_START_MONTH_EMPTY]: 'startDateMonth',
  [MSG_START_MONTH_INVALID]: 'startDateMonth',
  [MSG_START_DATE_INVALID]: 'startDateMonth',
  [MSG_START_DAY_EMPTY]: 'startDateDay',
  [MSG_START_DAY_INVALID]: 'startDateDay',
  [MSG_START_YEAR_EMPTY]: 'startDateYear',
  [MSG_START_YEAR_INVALID]: 'startDateYear',
  [MSG_END_MONTH_EMPTY]: 'endDateMonth',
  [MSG_END_MONTH_INVALID]: 'endDateMonth',
  [MSG_END_DATE_INVALID]: 'endDateMonth',
  [MSG_END_DAY_EMPTY]: 'endDateDay',
  [MSG_END_DAY_INVALID]: 'endDateDay',
  [MSG_END_YEAR_EMPTY]: 'endDateYear',
  [MSG_END_YEAR_INVALID]: 'endDateYear',
};

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
  const [infoMessage, setInfoMessage] = useState('');

  const customSelected = reportType === 'CUSTOM';

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
  const faultedField: ReportCursorField | null = errorMessage.endsWith(
    INVALID_CONFIRM_SUFFIX,
  )
    ? 'confirm'
    : (FAULTED_FIELD_BY_MESSAGE[errorMessage] ?? null);

  const placeCursor = useCallback((field: ReportCursorField): void => {
    setCursor((previous) => ({ field, seq: previous.seq + 1 }));
  }, []);

  // The entry controls are disabled for the keyboard-locked interval and focusing
  // a disabled control is a no-op, so placement waits for the submission to settle.
  useEffect(() => {
    if (submitting) {
      return;
    }
    document.getElementById(cursor.field)?.focus();
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
      placeCursor('reportType-MONTHLY');
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
        placeCursor(failure.field);
        return;
      }
    }

    const reportName = REPORT_NAMES[reportType];
    const confirmValue = confirmInput.trim();

    if (confirmValue === '') {
      setInfoMessage('');
      setErrorMessage(`${CONFIRM_PROMPT_PREFIX}${reportName}${CONFIRM_PROMPT_SUFFIX}`);
      placeCursor('confirm');
      return;
    }
    if (confirmValue === 'N' || confirmValue === 'n') {
      resetFields();
      setInfoMessage('');
      setErrorMessage('');
      placeCursor('reportType-MONTHLY');
      return;
    }
    if (confirmValue !== 'Y' && confirmValue !== 'y') {
      setInfoMessage('');
      setErrorMessage(`"${confirmValue}${INVALID_CONFIRM_SUFFIX}`);
      placeCursor('confirm');
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
      placeCursor('reportType-MONTHLY');
      return;
    }

    const submitted = `${reportName}${SUBMIT_SUCCESS_SUFFIX}`;
    const serverMessage = response.errorMessage?.trim() ?? '';
    if (serverMessage !== '' && serverMessage !== submitted) {
      setInfoMessage('');
      setErrorMessage(serverMessage);
      placeCursor('reportType-MONTHLY');
      return;
    }
    resetFields();
    setErrorMessage('');
    setInfoMessage(submitted);
    placeCursor('reportType-MONTHLY');
  }, [
    confirmInput,
    endDay,
    endMonth,
    endYear,
    placeCursor,
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
    }
  }, [submitError]);

  // Publishes the screen chrome: header fields, line-23 message and line-24 keys.
  // ENTER renders disabled while a submission is in flight.
  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: 'ENTER=Continue',
        onActivate: () => {
          void handleEnter();
        },
        enabled: !submitting,
      },
      {
        action: PfKeyAction.PF3,
        label: 'F3=Back',
        onActivate: handleExit,
      },
    ];
    setChrome({
      transactionId: 'CR00',
      programName: 'CORPT00C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      busy: submitting,
    });
  }, [errorMessage, handleEnter, handleExit, infoMessage, setChrome, submitting]);

  return (
    <div className="reportPage">
      <h2 className="neutral reportPage__heading" id="reportHeading">
        Transaction Reports
      </h2>

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
              onChange={() => {
                setReportType(type);
              }}
            />{' '}
            <label className="prompt" htmlFor={`reportType-${type}`}>
              {REPORT_TYPE_LABELS[type]}
            </label>
          </div>
        ))}
      </div>

      <div className="reportPage__range">
        <div className="reportPage__dateRow">
          <span className="prompt">Start Date :</span>{' '}
          <input
            type="text"
            id="startDateMonth"
            {...invalidFieldProps(faultedField === 'startDateMonth')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={startMonth}
            disabled={!customSelected || submitting}
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
            {...invalidFieldProps(faultedField === 'startDateDay')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={startDay}
            disabled={!customSelected || submitting}
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
            {...invalidFieldProps(faultedField === 'startDateYear')}
            inputMode="numeric"
            maxLength={4}
            size={4}
            value={startYear}
            disabled={!customSelected || submitting}
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
            {...invalidFieldProps(faultedField === 'endDateMonth')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={endMonth}
            disabled={!customSelected || submitting}
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
            {...invalidFieldProps(faultedField === 'endDateDay')}
            inputMode="numeric"
            maxLength={2}
            size={2}
            value={endDay}
            disabled={!customSelected || submitting}
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
            {...invalidFieldProps(faultedField === 'endDateYear')}
            inputMode="numeric"
            maxLength={4}
            size={4}
            value={endYear}
            disabled={!customSelected || submitting}
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
          {...invalidFieldProps(faultedField === 'confirm')}
          maxLength={1}
          size={1}
          value={confirmInput}
          disabled={submitting}
          onChange={(event) => {
            setConfirmInput(event.target.value);
          }}
        />{' '}
        <span className="neutral">(Y/N)</span>
      </div>
    </div>
  );
}

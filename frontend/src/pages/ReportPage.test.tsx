/**
 * :module: ``frontend/src/pages/ReportPage.test.tsx``
 * :purpose: Verify the transaction-report request workflow of :func:`ReportPage`
 *     (BMS mapset ``app/bms/CORPT00.bms``, symbolic map
 *     ``app/cpy-bms/CORPT00.CPY``, CICS transaction ``CR00``, program
 *     ``app/cbl/CORPT00C.cbl``): the Monthly / Yearly / Custom report-window
 *     selection, the custom date-range edits in their legacy order, the
 *     ``SUBMIT-JOB-TO-INTRDR`` confirmation gate, the asynchronous report launch
 *     through ``POST /reports``, and the line-24 function keys. Every screen
 *     caption, hint, and message is asserted against the DOM ``textContent``
 *     rather than a normalized match, so the legacy text is verified
 *     character-for-character.
 * :output: Jest assertions only; no artifact is produced.
 * :note: ``../api`` is mocked with a self-contained module factory: no axios
 *     instance and no Vite build-time environment (``import.meta``) is
 *     evaluated. The factory carries a working ``ApiError`` class — the same
 *     module identity ``useApi`` narrows against — plus the ``signon`` export
 *     the session store links to. Under Jest's native-ESM runtime the mock is
 *     registered before the mocked graph is pulled in with ``await import``.
 *     The page is rendered inside :func:`Layout`, which owns the
 *     ``useScreenChrome`` context holding the line-23 message region and the
 *     line-24 legend, within a ``MemoryRouter`` that also serves the ``/menu``
 *     route PF3 targets.
 */

import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
// Jest's native-ESM runtime does not inject ``jest`` as a global (unlike
// ``describe`` / ``it`` / ``expect``), so it is imported explicitly.
import { jest } from '@jest/globals';
import { MemoryRouter, Route, Routes } from 'react-router';
// The header title lines every screen publishes (``COTTL01Y``).
import { CCDA_MSG_INVALID_KEY, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  ApiErrorResponse,
  ReportRequestDto,
  ReportResponseDto,
} from '../types';

jest.unstable_mockModule('../api', () => {
  class ApiError extends Error {
    readonly status: number;
    readonly body?: ApiErrorResponse;
    readonly isOptimisticLockConflict: boolean;

    constructor(
      status: number,
      message: string,
      body?: ApiErrorResponse,
      isOptimisticLockConflict = false,
    ) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
      this.body = body;
      this.isOptimisticLockConflict = isOptimisticLockConflict;
    }
  }

  return {
    // The session store and the REST hook this screen's module graph loads bind to
    // these barrel exports as well. ``getSessionIdentity`` is the production
    // ``GET /session`` probe the session harness drives; unanswered by this suite it
    // reports no session, and the harness is the only thing that changes that.
    getSessionIdentity: jest.fn(() => Promise.reject(new Error('No session'))),
    logout: jest.fn(() => Promise.resolve(undefined)),
    registerSessionExpiryHandler: jest.fn(() => () => undefined),
    requestReport: jest.fn(),
    signon: jest.fn(),
    ApiError,
    isApiError: (err: unknown): boolean => err instanceof ApiError,
    // The request-cancellation contract ``useApi`` binds to: the real scope hands the
    // caller's AbortSignal to axios, and the double simply invokes the call.
    runWithRequestSignal: (_signal: AbortSignal, call: () => unknown): unknown => call(),
    isCancelledRequest: (): boolean => false,
  };
});

const { requestReport, ApiError } = await import('../api');
const { default: Layout } = await import('../components/Layout');
const { default: ReportPage } = await import('./ReportPage');
const { seedSignedOnSession, seedSignedOutSession } = await import(
  '../testing/sessionHarness'
);

const requestReportMock = jest.mocked(requestReport);

/* Screen captions, verbatim from the CORPT00 mapset. */
const SCREEN_HEADING = 'Transaction Reports';
const MONTHLY_CAPTION = 'Monthly (Current Month)';
const YEARLY_CAPTION = 'Yearly (Current Year)';
const CUSTOM_CAPTION = 'Custom (Date Range)';
const START_DATE_CAPTION = 'Start Date :';
const END_DATE_CAPTION = '  End Date :';
const DATE_FORMAT_HINT = '(MM/DD/YYYY)';
const CONFIRM_CAPTION = 'The Report will be submitted for printing. Please confirm: ';
const CONFIRM_HINT = '(Y/N)';
const ENTER_LEGEND = 'ENTER=Continue';
const PF3_LEGEND = 'F3=Back';

/* Screen messages, verbatim from CORPT00C. */
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

/* Field accessible names (``aria-label``) of the custom date components. */
const START_MONTH_FIELD = 'Start Date - Month';
const START_DAY_FIELD = 'Start Date - Day';
const START_YEAR_FIELD = 'Start Date - Year';
const END_MONTH_FIELD = 'End Date - Month';
const END_DAY_FIELD = 'End Date - Day';
const END_YEAR_FIELD = 'End Date - Year';

/* The six date components, in the order CORPT00C edits them. */
const DATE_PART_FIELDS = [
  START_MONTH_FIELD,
  START_DAY_FIELD,
  START_YEAR_FIELD,
  END_MONTH_FIELD,
  END_DAY_FIELD,
  END_YEAR_FIELD,
] as const;

const SIGNED_ON_USER = 'USER01';

/** :purpose: Empty submission acknowledgement — the launch succeeded silently. */
const LAUNCH_ACK: ReportResponseDto = {};

/**
 * :purpose: One decomposed date as the three components the map exposes.
 * :field month: two-digit month (``SDTMM`` / ``EDTMM``).
 * :field day: two-digit day (``SDTDD`` / ``EDTDD``).
 * :field year: four-digit year (``SDTYYYY`` / ``EDTYYYY``).
 */
interface DateComponents {
  readonly month: string;
  readonly day: string;
  readonly year: string;
}

/**
 * :purpose: One custom-window edit scenario.
 * :field title: the behaviour the case pins down.
 * :field start: components typed into the start date.
 * :field end: components typed into the end date.
 * :field message: expected line-23 text, verbatim.
 */
interface CustomWindowCase {
  readonly title: string;
  readonly start: DateComponents;
  readonly end: DateComponents;
  readonly message: string;
}

const BLANK_DATE: DateComponents = { month: '', day: '', year: '' };
const VALID_START: DateComponents = { month: '01', day: '01', year: '2024' };
const VALID_END: DateComponents = { month: '12', day: '31', year: '2024' };

/**
 * :purpose: Render the report screen inside the shared 24x80 shell and a router
 *     that also serves the main-menu route, so the shell's line-23 message
 *     region, the line-24 legend, and the PF3 exit are all exercised.
 */
function renderReportScreen(): void {
  render(
    <MemoryRouter initialEntries={['/reports']}>
      <Routes>
        <Route
          path="/reports"
          element={
            <Layout>
              <ReportPage />
            </Layout>
          }
        />
        <Route path="/menu" element={<div data-testid="menu-screen">Main Menu</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

/** The three selector captions, in the order CORPT00.bms lists their fields. */
const REPORT_TYPE_CAPTIONS = [MONTHLY_CAPTION, YEARLY_CAPTION, CUSTOM_CAPTION] as const;

/**
 * :purpose: Key a character into one report-window selector, leaving the other two
 *     exactly as they are. Each selector is a single-character entry field
 *     (``LENGTH=1``, ``UNPROT``) and the program tests it only for being non-blank, so
 *     any character marks it.
 * :param caption: the mapset caption beside the selector.
 * :param value: the character to key; defaults to ``'X'``.
 */
function markReportType(caption: string, value = 'X'): void {
  fireEvent.change(screen.getByLabelText(caption), { target: { value } });
}

/**
 * :purpose: Choose exactly one report window: clear the other two selector fields and
 *     key a character into this one. The clearing is explicit because the map does not
 *     do it -- three independent fields can all carry a mark at once, and CORPT00C
 *     resolves that by map order rather than by refusing the screen.
 * :param caption: the mapset caption of the selector to choose.
 */
function selectReportType(caption: string): void {
  for (const other of REPORT_TYPE_CAPTIONS) {
    if (other !== caption) {
      fireEvent.change(screen.getByLabelText(other), { target: { value: '' } });
    }
  }
  markReportType(caption);
}

/**
 * :purpose: Type a value into a date component or the confirmation flag.
 * :param field: accessible name of the target field.
 * :param value: text to enter.
 */
function enterField(field: string, value: string): void {
  fireEvent.change(screen.getByLabelText(field), { target: { value } });
}

/**
 * :purpose: Fill the custom start and end date components.
 * :param start: components for the start date.
 * :param end: components for the end date.
 */
function enterCustomWindow(start: DateComponents, end: DateComponents): void {
  enterField(START_MONTH_FIELD, start.month);
  enterField(START_DAY_FIELD, start.day);
  enterField(START_YEAR_FIELD, start.year);
  enterField(END_MONTH_FIELD, end.month);
  enterField(END_DAY_FIELD, end.day);
  enterField(END_YEAR_FIELD, end.year);
}

/**
 * :purpose: Enter the confirmation flag (``CONFIRMI``).
 * :param value: the flag to enter.
 */
function enterConfirmation(value: string): void {
  enterField(CONFIRM_CAPTION.trim(), value);
}

/**
 * :purpose: Activate the ENTER legend button and flush the resulting submission.
 * :returns: a promise that settles once the outcome has been rendered.
 */
async function pressEnter(): Promise<void> {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: ENTER_LEGEND }));
    // Awaited so this is an asynchronous act scope: the effects and the promise
    // callbacks the interaction queues are flushed before it returns.
    await Promise.resolve();
  });
}

/**
 * :purpose: Read the line-23 error region exactly as rendered.
 * :returns: the untouched ``textContent`` of the alert region.
 */
function errorText(): string | null {
  return screen.getByRole('alert').textContent;
}

/**
 * :purpose: Read the line-23 informational region exactly as rendered.
 * :returns: the untouched ``textContent`` of the status region.
 */
function infoText(): string | null {
  return infoBanner()?.textContent ?? null;
}

/**
 * :purpose: Assert a caption is on screen with its spacing untouched, bypassing
 *     the default whitespace normalization of Testing Library.
 * :param caption: the verbatim caption expected in the document.
 */
function expectVerbatimCaption(caption: string): void {
  expect(screen.getByText(caption, { normalizer: (text) => text })).toBeInTheDocument();
}

beforeEach(async () => {
  requestReportMock.mockReset();
  requestReportMock.mockResolvedValue(LAUNCH_ACK);
  await seedSignedOnSession(SIGNED_ON_USER, 'U');
});

afterEach(async () => {
  await seedSignedOutSession();
});

/**
 * :purpose: The line-23 informational message region. The shared shell also renders a
 *     visually hidden ``role="status"`` busy announcer, so the banner is matched on its
 *     own class rather than on the role alone.
 * :returns: the informational banner, or ``null`` when line 23 carries no
 *     informational message.
 */
function infoBanner(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.errorBanner[role="status"]');
}

describe('ReportPage — screen (CORPT00 / transaction CR00)', () => {
  it('reaches the report endpoint through the module mock, never a live client', () => {
    expect(jest.isMockFunction(requestReport)).toBe(true);
  });

  it('publishes the transaction id, program name, and titles onto the shell', () => {
    renderReportScreen();

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CR00');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('CORPT00C');
    expect(screen.getByTestId('title01')).toHaveTextContent(CCDA_TITLE01);
    expect(screen.getByTestId('title02')).toHaveTextContent(CCDA_TITLE02);
    // The screen name lives in the body heading (BMS row 4), not in title02.
    expect(
      screen.getByRole('heading', { level: 3, name: SCREEN_HEADING }),
    ).toBeInTheDocument();
    // The body heading of row 4 labels the report-window group. It is a plain group,
    // not a radiogroup: the three selectors are three independent entry fields.
    expect(screen.getByRole('group', { name: SCREEN_HEADING })).toBeInTheDocument();
  });

  it('opens with an empty line-23 region and the signed-on session on the frame', () => {
    const { container } = render(
      <MemoryRouter initialEntries={['/reports']}>
        <Layout>
          <ReportPage />
        </Layout>
      </MemoryRouter>,
    );

    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(container.querySelector('.screen')).toHaveAttribute(
      'data-authenticated',
      'true',
    );
  });

  it('renders the three report-window selectors as the mapset declares them', () => {
    // MONTHLY POS=(7,10), YEARLY POS=(9,10), CUSTOM POS=(11,10): three separate
    // single-character UNPROT fields, each with its caption at column 15. Not a radio
    // group -- a group would be one tab stop of which exactly one member can be set.
    renderReportScreen();

    for (const caption of REPORT_TYPE_CAPTIONS) {
      const selector = screen.getByLabelText(caption);
      expect(selector.tagName).toBe('INPUT');
      expect(selector).toHaveAttribute('type', 'text');
      expect(selector).toHaveAttribute('maxlength', '1');
      expect(selector).toHaveAttribute('size', '1');
      expect(selector).toHaveValue('');
    }
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
  });

  it('keeps each selector a tab stop of its own, in map order', () => {
    renderReportScreen();

    const stops = [
      screen.getByLabelText(MONTHLY_CAPTION),
      screen.getByLabelText(YEARLY_CAPTION),
      screen.getByLabelText(CUSTOM_CAPTION),
      screen.getByLabelText(START_MONTH_FIELD),
    ];
    for (const stop of stops) {
      expect(stop).not.toHaveAttribute('tabindex', '-1');
    }
    // The document order the tab sequence follows IS the mapset's row order.
    const body = screen.getByRole('main');
    const entered = Array.from(body.querySelectorAll('input'));
    expect(entered.slice(0, 4)).toEqual(stops);
  });

  it('keeps a character in every selector the operator marks', () => {
    // The map protects nothing and clears nothing: an operator can key a character into
    // all three fields, and each keeps what was keyed.
    renderReportScreen();

    markReportType(MONTHLY_CAPTION, '1');
    markReportType(YEARLY_CAPTION, '2');

    expect(screen.getByLabelText(MONTHLY_CAPTION)).toHaveValue('1');
    expect(screen.getByLabelText(YEARLY_CAPTION)).toHaveValue('2');
    expect(screen.getByLabelText(CUSTOM_CAPTION)).toHaveValue('');
  });

  it('renders the date-range captions and both (MM/DD/YYYY) hints verbatim', () => {
    renderReportScreen();

    expectVerbatimCaption(START_DATE_CAPTION);
    expectVerbatimCaption(END_DATE_CAPTION);

    const hints = screen.getAllByText(DATE_FORMAT_HINT);
    expect(hints).toHaveLength(2);
    hints.forEach((hint) => {
      expect(hint.textContent).toBe(DATE_FORMAT_HINT);
    });
  });

  it('renders the confirmation caption and its (Y/N) hint verbatim', () => {
    renderReportScreen();

    expectVerbatimCaption(CONFIRM_CAPTION);
    expect(screen.getByText(CONFIRM_HINT).textContent).toBe(CONFIRM_HINT);
    expect(screen.getByLabelText(CONFIRM_CAPTION.trim())).toHaveValue('');
  });

  /*
   * All six date parts are declared ``ATTRB=(FSET,NORM,NUM,UNPROT)`` at
   * app/bms/CORPT00.bms:127-193 and CORPT00C moves no attribute byte to any field,
   * so the mapset never protects them: they are enterable in every state of the
   * screen, including before a window has been chosen. The window selection decides
   * only whether the entered range is *read* — CORPT00C:212 inspects the date parts
   * under the custom branch alone.
   */
  it('keeps all six date components enterable before any window is chosen', () => {
    renderReportScreen();

    DATE_PART_FIELDS.forEach((field) => {
      expect(screen.getByLabelText(field)).toBeEnabled();
    });
  });

  it('keeps all six date components enterable for the Custom window', () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);

    DATE_PART_FIELDS.forEach((field) => {
      expect(screen.getByLabelText(field)).toBeEnabled();
    });
  });

  it('keeps all six date components enterable for the Monthly and Yearly windows', () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    DATE_PART_FIELDS.forEach((field) => {
      expect(screen.getByLabelText(field)).toBeEnabled();
    });

    selectReportType(YEARLY_CAPTION);
    DATE_PART_FIELDS.forEach((field) => {
      expect(screen.getByLabelText(field)).toBeEnabled();
    });
  });

  /*
   * A range typed against a Monthly or Yearly window is ignored, not rejected:
   * CORPT00C's EVALUATE TRUE matches the selector first and never reaches the date
   * edits, so buildReportRequest carries the parts for CUSTOM only.
   */
  it('ignores a range typed against the Monthly window rather than rejecting it', async () => {
    renderReportScreen();

    enterCustomWindow(
      { month: '13', day: '99', year: 'ABCD' },
      { month: '13', day: '99', year: 'ABCD' },
    );
    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(requestReportMock).toHaveBeenCalledWith({
      monthly: 'X',
      yearly: '',
      custom: '',
      confirm: 'Y',
    });
  });
});

describe('ReportPage — report window required', () => {
  it('rejects ENTER with no report window chosen and launches nothing', async () => {
    renderReportScreen();

    await pressEnter();

    expect(errorText()).toBe(MSG_SELECT_REPORT_TYPE);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('marks the control the rejection sends the cursor to', async () => {
    // `CORPT00C` ends the no-window path with `MOVE -1 TO MONTHLYL`, so the report-type
    // option is the control the row-23 message is about, and the cursor plus the
    // accessible flag say so. The program contains no `MOVE DFHRED`, so the control is
    // not recoloured.
    renderReportScreen();

    await pressEnter();

    expect(errorText()).toBe(MSG_SELECT_REPORT_TYPE);
    const monthly = screen.getByLabelText(MONTHLY_CAPTION);
    expect(monthly).toBeInvalid();
    expect(monthly).not.toHaveClass('fieldError');
    expect(screen.getByLabelText(YEARLY_CAPTION)).not.toBeInvalid();
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
  });

  it('cursors the confirm box while asking for confirmation, without marking it', async () => {
    // The confirmation prompt is reported on the error line and ends with
    // `MOVE -1 TO CONFIRML`, so the confirm box is the control it is about -- but it is
    // the CURSOR the program moves there, not a rejection. The box is empty and `N` is an
    // accepted answer, so nothing about the entry is invalid yet; `CORPT00C` marks the
    // control on neither path (it contains no `MOVE DFHRED` at all).
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Monthly${CONFIRM_PROMPT_SUFFIX}`);
    const confirm = screen.getByLabelText(CONFIRM_CAPTION.trim());
    expect(confirm).not.toBeInvalid();
    expect(confirm).toHaveClass('field');
    expect(confirm).not.toHaveClass('fieldError');
    expect(confirm).not.toHaveAttribute('data-faulted');
    expect(confirm).toHaveFocus();
    expect(screen.getByLabelText(MONTHLY_CAPTION)).not.toBeInvalid();
  });

  it('marks the confirm box when the value it holds is not Y or N', async () => {
    // The rejection path DOES mark: the value standing on the screen was refused by the
    // edit, which is what `aria-invalid` asserts. It still paints nothing, because
    // `CORPT00C` moves no `DFHRED` anywhere.
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('X');
    await pressEnter();

    const confirm = screen.getByLabelText(CONFIRM_CAPTION.trim());
    expect(errorText()).toBe(`"X${INVALID_CONFIRM_SUFFIX}`);
    expect(confirm).toBeInvalid();
    expect(confirm).not.toHaveClass('fieldError');
    expect(confirm).not.toHaveAttribute('data-faulted');
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
  });

  it('clears the rejection once a window is chosen and confirmed', async () => {
    renderReportScreen();

    await pressEnter();
    expect(errorText()).toBe(MSG_SELECT_REPORT_TYPE);

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(infoText()).toBe(`Monthly${SUBMIT_SUCCESS_SUFFIX}`);
  });
});

/* The six emptiness edits, in the order CORPT00C applies them. */
const emptinessCases: readonly CustomWindowCase[] = [
  {
    title: 'a blank start month',
    start: BLANK_DATE,
    end: BLANK_DATE,
    message: MSG_START_MONTH_EMPTY,
  },
  {
    title: 'a blank start day',
    start: { month: '01', day: '', year: '' },
    end: BLANK_DATE,
    message: MSG_START_DAY_EMPTY,
  },
  {
    title: 'a blank start year',
    start: { month: '01', day: '01', year: '' },
    end: BLANK_DATE,
    message: MSG_START_YEAR_EMPTY,
  },
  {
    title: 'a blank end month',
    start: VALID_START,
    end: BLANK_DATE,
    message: MSG_END_MONTH_EMPTY,
  },
  {
    title: 'a blank end day',
    start: VALID_START,
    end: { month: '12', day: '', year: '' },
    message: MSG_END_DAY_EMPTY,
  },
  {
    title: 'a blank end year',
    start: VALID_START,
    end: { month: '12', day: '31', year: '' },
    message: MSG_END_YEAR_EMPTY,
  },
];

/* The six numeric / range edits and the two calendar edits that follow them. */
const invalidDateCases: readonly CustomWindowCase[] = [
  {
    title: 'a start month above 12',
    start: { month: '13', day: '01', year: '2024' },
    end: VALID_END,
    message: MSG_START_MONTH_INVALID,
  },
  {
    title: 'a non-numeric start month',
    start: { month: 'ab', day: '01', year: '2024' },
    end: VALID_END,
    message: MSG_START_MONTH_INVALID,
  },
  {
    title: 'a start day above 31',
    start: { month: '01', day: '32', year: '2024' },
    end: VALID_END,
    message: MSG_START_DAY_INVALID,
  },
  {
    title: 'a non-numeric start year',
    start: { month: '01', day: '01', year: '20x4' },
    end: VALID_END,
    message: MSG_START_YEAR_INVALID,
  },
  {
    title: 'an end month above 12',
    start: VALID_START,
    end: { month: '13', day: '31', year: '2024' },
    message: MSG_END_MONTH_INVALID,
  },
  {
    title: 'an end day above 31',
    start: VALID_START,
    end: { month: '12', day: '32', year: '2024' },
    message: MSG_END_DAY_INVALID,
  },
  {
    title: 'a non-numeric end year',
    start: VALID_START,
    end: { month: '12', day: '31', year: 'yyyy' },
    message: MSG_END_YEAR_INVALID,
  },
  {
    title: 'a start date that is not on the calendar',
    start: { month: '02', day: '30', year: '2023' },
    end: VALID_END,
    message: MSG_START_DATE_INVALID,
  },
  {
    title: 'an end date that is not on the calendar',
    start: VALID_START,
    end: { month: '02', day: '30', year: '2023' },
    message: MSG_END_DATE_INVALID,
  },
];

describe('ReportPage — custom window emptiness edits', () => {
  emptinessCases.forEach(({ title, start, end, message }) => {
    it(`rejects ${title}`, async () => {
      renderReportScreen();

      selectReportType(CUSTOM_CAPTION);
      enterCustomWindow(start, end);
      await pressEnter();

      expect(errorText()).toBe(message);
      expect(requestReportMock).not.toHaveBeenCalled();
    });
  });
});

describe('ReportPage — custom window numeric, range, and calendar edits', () => {
  invalidDateCases.forEach(({ title, start, end, message }) => {
    it(`rejects ${title}`, async () => {
      renderReportScreen();

      selectReportType(CUSTOM_CAPTION);
      enterCustomWindow(start, end);
      await pressEnter();

      expect(errorText()).toBe(message);
      expect(requestReportMock).not.toHaveBeenCalled();
    });
  });

  it('accepts a leap-year window and moves on to the confirmation gate', async () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    enterCustomWindow({ month: '02', day: '29', year: '2024' }, VALID_END);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Custom${CONFIRM_PROMPT_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('skips the date edits entirely for the Monthly window', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Monthly${CONFIRM_PROMPT_SUFFIX}`);
  });
});

describe('ReportPage — confirmation gate (SUBMIT-JOB-TO-INTRDR)', () => {
  it('prompts for confirmation of the Monthly window', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Monthly${CONFIRM_PROMPT_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('prompts for confirmation of the Yearly window', async () => {
    renderReportScreen();

    selectReportType(YEARLY_CAPTION);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Yearly${CONFIRM_PROMPT_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('prompts for confirmation of a valid Custom window', async () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    enterCustomWindow(VALID_START, VALID_END);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Custom${CONFIRM_PROMPT_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('rejects a confirmation flag that is neither Y nor N', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('X');
    await pressEnter();

    expect(errorText()).toBe(`"X${INVALID_CONFIRM_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('rejects a digit confirmation flag with the same quoted message', async () => {
    renderReportScreen();

    selectReportType(YEARLY_CAPTION);
    enterConfirmation('7');
    await pressEnter();

    expect(errorText()).toBe(`"7${INVALID_CONFIRM_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('clears every field on N without launching the report', async () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    enterCustomWindow(VALID_START, VALID_END);
    enterConfirmation('N');
    await pressEnter();

    expect(screen.getByLabelText(CUSTOM_CAPTION)).toHaveValue('');
    expect(screen.getByLabelText(START_MONTH_FIELD)).toHaveValue('');
    expect(screen.getByLabelText(END_YEAR_FIELD)).toHaveValue('');
    expect(screen.getByLabelText(CONFIRM_CAPTION.trim())).toHaveValue('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('accepts the lower-case n as a cancellation', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('n');
    await pressEnter();

    expect(screen.getByLabelText(MONTHLY_CAPTION)).toHaveValue('');
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
    expect(requestReportMock).not.toHaveBeenCalled();
  });
});

describe('ReportPage — asynchronous report launch (POST /reports)', () => {
  it('launches the Monthly report and reports the submission', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    // All three selector fields travel exactly as keyed: the map sends every field, the
    // program reads each only for being non-blank, and the service applies the same
    // first-non-blank rule. `markReportType` keys `X`, so `X` is what crosses the wire.
    const expected: ReportRequestDto = {
      monthly: 'X',
      yearly: '',
      custom: '',
      confirm: 'Y',
    };
    expect(requestReportMock).toHaveBeenCalledTimes(1);
    expect(requestReportMock).toHaveBeenCalledWith(expected);
    expect(infoText()).toBe(`Monthly${SUBMIT_SUCCESS_SUFFIX}`);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('launches the Yearly report on a lower-case confirmation', async () => {
    renderReportScreen();

    selectReportType(YEARLY_CAPTION);
    enterConfirmation('y');
    await pressEnter();

    const expected: ReportRequestDto = {
      monthly: '',
      yearly: 'X',
      custom: '',
      confirm: 'y',
    };
    expect(requestReportMock).toHaveBeenCalledTimes(1);
    expect(requestReportMock).toHaveBeenCalledWith(expected);
    expect(infoText()).toBe(`Yearly${SUBMIT_SUCCESS_SUFFIX}`);
  });

  it('runs the earlier window when two selectors carry a mark', async () => {
    // CORPT00C L212-256 evaluates MONTHLY, then YEARLY, then CUSTOM and takes the FIRST
    // that is non-blank, so a screen with two marks runs the earlier one rather than being
    // refused. A radio group could not express this state at all.
    renderReportScreen();

    markReportType(YEARLY_CAPTION);
    markReportType(CUSTOM_CAPTION);
    enterCustomWindow(VALID_START, VALID_END);
    enterConfirmation('Y');
    await pressEnter();

    // Yearly is what ran, and the custom date parts are NOT read under that branch.
    expect(requestReportMock).toHaveBeenCalledWith({
      monthly: '',
      yearly: 'X',
      custom: 'X',
      confirm: 'Y',
    });
    expect(infoText()).toBe(`Yearly${SUBMIT_SUCCESS_SUFFIX}`);
  });

  it('prompts with the earlier window name when two selectors carry a mark', async () => {
    renderReportScreen();

    markReportType(MONTHLY_CAPTION);
    markReportType(CUSTOM_CAPTION);
    await pressEnter();

    expect(errorText()).toBe(`${CONFIRM_PROMPT_PREFIX}Monthly${CONFIRM_PROMPT_SUFFIX}`);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('launches the Custom report with the date components as entered', async () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    enterCustomWindow(VALID_START, VALID_END);
    enterConfirmation('Y');
    await pressEnter();

    const expected: ReportRequestDto = {
      monthly: '',
      yearly: '',
      custom: 'X',
      startDateMonth: '01',
      startDateDay: '01',
      startDateYear: '2024',
      endDateMonth: '12',
      endDateDay: '31',
      endDateYear: '2024',
      confirm: 'Y',
    };
    expect(requestReportMock).toHaveBeenCalledTimes(1);
    expect(requestReportMock).toHaveBeenCalledWith(expected);
    expect(infoText()).toBe(`Custom${SUBMIT_SUCCESS_SUFFIX}`);
  });

  it('clears the screen once the launch has been acknowledged', async () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    enterCustomWindow(VALID_START, VALID_END);
    enterConfirmation('Y');
    await pressEnter();

    expect(screen.getByLabelText(CUSTOM_CAPTION)).toHaveValue('');
    expect(screen.getByLabelText(START_YEAR_FIELD)).toHaveValue('');
    expect(screen.getByLabelText(CONFIRM_CAPTION.trim())).toHaveValue('');
  });

  it('shows an acknowledgement that carries its own status line instead', async () => {
    const rejected = 'Report request could not be queued...';
    requestReportMock.mockResolvedValueOnce({ errorMessage: rejected });
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(errorText()).toBe(rejected);
    expect(screen.getByLabelText(MONTHLY_CAPTION)).toHaveValue('X');
  });

  it('surfaces the service message when the launch fails', async () => {
    const body: ApiErrorResponse = {
      timestamp: '2026-07-01T09:15:00.000Z',
      status: 503,
      error: 'Service Unavailable',
      message: 'Report service unavailable...',
      path: '/reports',
    };
    requestReportMock.mockRejectedValueOnce(new ApiError(503, 'Request failed', body));
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    await waitFor(() => {
      expect(errorText()).toBe(body.message);
    });
    expect(screen.getByLabelText(MONTHLY_CAPTION)).toHaveValue('X');
  });

  it('falls back to the transport message when the failure has no body', async () => {
    requestReportMock.mockRejectedValueOnce(new ApiError(0, 'Network Error'));
    renderReportScreen();

    selectReportType(YEARLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    await waitFor(() => {
      expect(errorText()).toBe('Network Error');
    });
  });

  it('holds ENTER closed while a launch is in flight and launches once', async () => {
    let acknowledge!: (value: ReportResponseDto) => void;
    requestReportMock.mockReturnValueOnce(
      new Promise<ReportResponseDto>((resolve) => {
        acknowledge = resolve;
      }),
    );
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: ENTER_LEGEND }));
    });

    expect(screen.getByRole('button', { name: ENTER_LEGEND })).toBeDisabled();
    fireEvent.keyDown(document, { key: 'Enter' });

    await act(async () => {
      acknowledge(LAUNCH_ACK);
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(screen.getByRole('button', { name: ENTER_LEGEND })).toBeEnabled();
    expect(requestReportMock).toHaveBeenCalledTimes(1);
    expect(infoText()).toBe(`Monthly${SUBMIT_SUCCESS_SUFFIX}`);
  });
});

describe('ReportPage — line-24 function keys', () => {
  it('publishes exactly the ENTER and PF3 legend of the mapset', () => {
    renderReportScreen();

    const legend = screen.getAllByRole('button');
    expect(legend.map((button) => button.textContent)).toEqual([
      ENTER_LEGEND,
      PF3_LEGEND,
    ]);
  });

  it('wires the ENTER key to the same handler as its legend button', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await act(async () => {
      fireEvent.keyDown(document, { key: 'Enter' });
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(requestReportMock).toHaveBeenCalledTimes(1);
    expect(infoText()).toBe(`Monthly${SUBMIT_SUCCESS_SUFFIX}`);
  });

  it('leaves the screen for the main menu on the PF3 legend button', () => {
    renderReportScreen();

    fireEvent.click(screen.getByRole('button', { name: PF3_LEGEND }));

    expect(screen.getByTestId('menu-screen')).toBeInTheDocument();
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('leaves the screen for the main menu on the PF3 key', () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    fireEvent.keyDown(document, { key: 'F3' });

    expect(screen.getByTestId('menu-screen')).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: CUSTOM_CAPTION })).not.toBeInTheDocument();
  });

  it('ignores an unmapped attention key', async () => {
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await act(async () => {
      fireEvent.keyDown(document, { key: 'F9' });
      // Awaited so this is an asynchronous act scope: the effects and the promise
      // callbacks the interaction queues are flushed before it returns.
      await Promise.resolve();
    });

    expect(requestReportMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('error-banner-empty')).toBeInTheDocument();
  });
});

describe('ReportPage — the message channel carries the colour (CORPT00C MOVE DFHGREEN)', () => {
  it('renders an acknowledgement that arrives on the success channel as informational', async () => {
    // CORPT00 declares ONE message field, ERRMSG POS=(23,1) COLOR=RED, and CORPT00C
    // performs `MOVE DFHGREEN TO ERRMSGC` before the submission acknowledgement and
    // before nothing else. The service therefore populates `message` for that one
    // outcome and `errorMessage` for every other, so the colour travels on the contract.
    const acknowledgement = `Monthly${SUBMIT_SUCCESS_SUFFIX}`;
    requestReportMock.mockResolvedValueOnce({ message: acknowledgement });
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(infoText()).toBe(acknowledgement);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('renders a refusal as an error even when its text matches the acknowledgement', async () => {
    // The previous implementation decided the channel by comparing the text against a
    // locally composed copy of the expected acknowledgement, so a refusal whose wording
    // happened to coincide would have been painted in success green. The channel now
    // decides, and the text is irrelevant to it.
    const collidingText = `Monthly${SUBMIT_SUCCESS_SUFFIX}`;
    requestReportMock.mockResolvedValueOnce({ errorMessage: collidingText });
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(errorText()).toBe(collidingText);
    expect(infoBanner()).toBeNull();
  });

  it('holds a standing rejection and its marking until the next AID', async () => {
    // CORPT00C writes line 23 only on a SEND MAP, and a keystroke transmits nothing, so
    // marking a window does not silently withdraw the report of the turn that rejected
    // the screen. The next AID is what replaces both.
    renderReportScreen();

    enterConfirmation('Y');
    await pressEnter();
    expect(errorText()).toBe(MSG_SELECT_REPORT_TYPE);
    expect(screen.getByLabelText(MONTHLY_CAPTION)).toBeInvalid();

    markReportType(YEARLY_CAPTION);

    expect(errorText()).toBe(MSG_SELECT_REPORT_TYPE);
    expect(screen.getByLabelText(MONTHLY_CAPTION)).toBeInvalid();

    await pressEnter();

    expect(screen.queryByRole('alert')).toBeNull();
    expect(infoText()).toBe(`Yearly${SUBMIT_SUCCESS_SUFFIX}`);
    expect(screen.getByLabelText(MONTHLY_CAPTION)).not.toBeInvalid();
  });

  /*
   * `aria-invalid` asserts that a control's CONTENT was refused. The `WHEN OTHER` branch
   * of `EVALUATE EIBAID` (L191-195) refuses a KEY, so the message it publishes takes the
   * cursor to `MONTHLY` -- `MOVE -1 TO MONTHLYL` -- without marking anything.
   */
  it('takes the cursor without faulting a control for a rejected function key', async () => {
    renderReportScreen();

    await act(async () => {
      fireEvent.keyDown(document, { key: 'F12' });
      await Promise.resolve();
    });

    expect(errorText()).toBe(CCDA_MSG_INVALID_KEY);
    expect(screen.getByLabelText(MONTHLY_CAPTION)).not.toHaveAttribute('aria-invalid');
    expect(document.querySelectorAll('[aria-invalid="true"]')).toHaveLength(0);
    expect(document.querySelectorAll('[data-faulted]')).toHaveLength(0);
  });

  /*
   * CORPT00C contains no ``MOVE DFHRED`` and no ``MOVE '*'`` at all: it marks the
   * offending date component with ``MOVE -1 TO <field>L`` -- the cursor -- and the
   * line-23 message, and nothing else. Reddening the box would be observable output
   * the mapset does not declare, so the accessible flag (which has no BMS analogue to
   * contradict) travels alone and the cursor names which of the six is wrong.
   */
  it('marks a rejected date component by cursor and flag alone, never by colour', async () => {
    renderReportScreen();

    selectReportType(CUSTOM_CAPTION);
    enterCustomWindow(
      { month: '13', day: '01', year: '2026' },
      { month: '01', day: '31', year: '2026' },
    );
    enterConfirmation('Y');
    await pressEnter();

    expect(errorText()).toBe('Start Date - Not a valid Month...');
    const month = screen.getByLabelText(START_MONTH_FIELD);
    expect(month).toHaveAttribute('aria-invalid', 'true');
    expect(month).toHaveFocus();
    expect(month.className).not.toContain('fieldError');
    expect(document.querySelectorAll('.fieldError')).toHaveLength(0);
    expect(requestReportMock).not.toHaveBeenCalled();
  });

  it('describes the confirmation field with its own (Y/N) value hint', () => {
    renderReportScreen();

    const confirm = screen.getByLabelText(CONFIRM_CAPTION.trim());
    const described = confirm.getAttribute('aria-describedby') ?? '';
    expect(described.split(' ')).toContain('reportConfirmValues');
    expect(document.getElementById('reportConfirmValues')?.textContent).toBe(
      CONFIRM_HINT,
    );
  });
});

/* ------------------------------------------------------------------ */
/* Launch traceability (Observability rule, AAP 0.7.5)                */
/* ------------------------------------------------------------------ */

describe('ReportPage — a launched report is traceable', () => {
  it('carries the launched execution id on the message region', async () => {
    // The submission's own identity travels back with the response so a launched report
    // can be followed into its log and trace records. It is an attribute, not screen
    // text: CORPT00's message field is a frozen literal contract.
    requestReportMock.mockResolvedValueOnce({ jobExecutionId: '4321' });
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(infoBanner()).toHaveAttribute('data-message-reference', '4321');
  });

  it('publishes no reference for an outcome that launched nothing', async () => {
    const rejected = 'Report request could not be queued...';
    requestReportMock.mockResolvedValueOnce({ errorMessage: rejected });
    renderReportScreen();

    selectReportType(MONTHLY_CAPTION);
    enterConfirmation('Y');
    await pressEnter();

    expect(errorText()).toBe(rejected);
    expect(screen.getByRole('alert')).not.toHaveAttribute('data-message-reference');
  });
});

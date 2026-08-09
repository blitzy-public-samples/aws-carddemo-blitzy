/**
 * :module: ``frontend/src/types/report.ts``
 * :purpose: Request / response DTO contracts for the report-request screen
 *   ``ReportPage`` (BMS mapset ``CORPT00``, legacy program ``CORPT00C``, CICS
 *   transaction ``CR00``). The page submits ``POST /reports``, which launches a
 *   report-generation batch job asynchronously on the reporting service.
 * :note: Member names mirror the frozen backend wire contract
 *   ``com.carddemo.common.dto.ReportRequestDto`` /
 *   ``com.carddemo.common.dto.ReportResponseDto`` (camelCase JSON properties)
 *   exactly, so axios request bodies serialize and REST responses deserialize
 *   without field remapping. Field widths and semantics derive from the
 *   symbolic map ``app/cpy-bms/CORPT00.CPY``.
 */

import type { ErrMsg } from './common';

/**
 * :purpose: The three mutually-exclusive report-window selectors offered by the
 *   ``CORPT00`` screen, modelled as a string-literal union.
 * :note: Derived from the radio-style single-character flags ``MONTHLYI``,
 *   ``YEARLYI`` and ``CUSTOMI`` (each ``PIC X(01)``). On the wire the selection
 *   is carried by the individual ``monthly`` / ``yearly`` / ``custom`` flag
 *   fields of :ts:type:`ReportRequestDto` (``'Y'`` marks the chosen window);
 *   this union is a client-side convenience for the page radio group and is not
 *   itself a wire field.
 */
export type ReportType = 'MONTHLY' | 'YEARLY' | 'CUSTOM';

/**
 * :purpose: Read-only, ordered list of the valid :ts:type:`ReportType` values,
 *   for rendering the report-window radio group without hard-coding the
 *   literals at each call site.
 * :note: Declared ``as const`` so its element type is exactly
 *   ``readonly ['MONTHLY', 'YEARLY', 'CUSTOM']``.
 */
export const REPORT_TYPES = ['MONTHLY', 'YEARLY', 'CUSTOM'] as const;

/**
 * :purpose: Client-side helper grouping the decomposed month / day / year parts
 *   of one custom date, mirroring the ``CORPT00`` two-character month and day
 *   and four-character year fields.
 * :note: Optional convenience for the page form only; it is NOT a wire type. The
 *   request / response DTOs carry the parts as the flat ``startDate*`` /
 *   ``endDate*`` fields the backend expects. Recomposition of the parts into a
 *   consolidated ``YYYY-MM-DD`` window is the reporting service's
 *   responsibility, not this carrier.
 * :field month: two-digit month (``SDTMM`` / ``EDTMM`` ``PIC X(02)``).
 * :field day: two-digit day (``SDTDD`` / ``EDTDD`` ``PIC X(02)``).
 * :field year: four-digit year (``SDTYYYY`` / ``EDTYYYY`` ``PIC X(04)``).
 */
export interface ReportDateParts {
  month?: string;
  day?: string;
  year?: string;
}

/**
 * :purpose: Inbound request body for ``POST /reports`` (report-request screen
 *   ``CORPT00``). Carries the report-window selector flags and, for the custom
 *   window, the decomposed start / end date parts exactly as entered on the
 *   3270 map.
 * :note: Mirrors ``com.carddemo.common.dto.ReportRequestDto`` field-for-field
 *   (source ``app/cpy-bms/CORPT00.CPY``). Values are single- / two- /
 *   four-character ``string`` flags rather than parsed numerics or booleans,
 *   matching the COBOL ``PIC X`` fields. Exactly one of ``monthly`` / ``yearly``
 *   / ``custom`` carries ``'Y'``; the ``startDate*`` / ``endDate*`` parts are
 *   populated only when ``custom`` is selected. All members are optional because
 *   the backend fields are size-constrained but nullable, and date-window
 *   parsing / validation belongs to the reporting service, not this carrier.
 * :field monthly: MONTHLY selector flag (``MONTHLYI`` ``PIC X(01)``, ``'Y'`` when chosen).
 * :field yearly: YEARLY selector flag (``YEARLYI`` ``PIC X(01)``, ``'Y'`` when chosen).
 * :field custom: CUSTOM selector flag (``CUSTOMI`` ``PIC X(01)``, ``'Y'`` when chosen).
 * :field startDateMonth: custom start-date month (``SDTMMI`` ``PIC X(02)``).
 * :field startDateDay: custom start-date day (``SDTDDI`` ``PIC X(02)``).
 * :field startDateYear: custom start-date year (``SDTYYYYI`` ``PIC X(04)``).
 * :field endDateMonth: custom end-date month (``EDTMMI`` ``PIC X(02)``).
 * :field endDateDay: custom end-date day (``EDTDDI`` ``PIC X(02)``).
 * :field endDateYear: custom end-date year (``EDTYYYYI`` ``PIC X(04)``).
 * :field confirm: confirmation flag (``CONFIRMI`` ``PIC X(01)``, ``'Y'`` / ``'N'``).
 */
export interface ReportRequestDto {
  monthly?: string;
  yearly?: string;
  custom?: string;
  startDateMonth?: string;
  startDateDay?: string;
  startDateYear?: string;
  endDateMonth?: string;
  endDateDay?: string;
  endDateYear?: string;
  confirm?: string;
}

/**
 * :purpose: Outbound response body for ``POST /reports`` (report-request screen
 *   ``CORPT00``). Echoes the report-window selector flags and custom date parts
 *   back to the client and adds the response-only fields the reporting service
 *   populates: the error / status line, the confirmation flag and the screen
 *   header.
 * :note: Mirrors ``com.carddemo.common.dto.ReportResponseDto`` field-for-field
 *   (source ``app/cpy-bms/CORPT00.CPY``). Because report generation is launched
 *   as an asynchronous batch job, the submission acknowledgement is conveyed on
 *   the row-23 status line (as the legacy ``CORPT00C`` screen re-displays after
 *   submit) rather than through a distinct job-id / status envelope; the backend
 *   contract exposes no such fields, so none are modelled here. All members are
 *   optional because the service populates only the relevant subset per request.
 * :field monthly: echoed MONTHLY selector flag (``MONTHLYO`` ``PIC X(01)``).
 * :field yearly: echoed YEARLY selector flag (``YEARLYO`` ``PIC X(01)``).
 * :field custom: echoed CUSTOM selector flag (``CUSTOMO`` ``PIC X(01)``).
 * :field startDateMonth: echoed custom start-date month (``SDTMMO`` ``PIC X(02)``).
 * :field startDateDay: echoed custom start-date day (``SDTDDO`` ``PIC X(02)``).
 * :field startDateYear: echoed custom start-date year (``SDTYYYYO`` ``PIC X(04)``).
 * :field endDateMonth: echoed custom end-date month (``EDTMMO`` ``PIC X(02)``).
 * :field endDateDay: echoed custom end-date day (``EDTDDO`` ``PIC X(02)``).
 * :field endDateYear: echoed custom end-date year (``EDTYYYYO`` ``PIC X(04)``).
 * :field confirm: confirmation flag set by the service (``CONFIRMO`` ``PIC X(01)``).
 * :field errorMessage: row-23 line for an outcome the screen reports as an ERROR
 *   (``ERRMSGO`` ``PIC X(78)`` sent in its declared ``COLOR=RED``); reuses the
 *   shared :ts:type:`ErrMsg` alias from ``./common``.
 * :field message: row-23 line for the one outcome the screen reports as a SUCCESS —
 *   the ``<Name> report submitted for printing ...`` acknowledgement, which
 *   ``CORPT00C`` precedes with ``MOVE DFHGREEN TO ERRMSGC`` and no other branch
 *   does. The colour is therefore carried by the CHOICE of member rather than
 *   inferred from the text; at most one of the two is ever populated.
 * :field title01: screen title line 1 (``TITLE01O`` ``PIC X(40)``).
 * :field title02: screen title line 2 (``TITLE02O`` ``PIC X(40)``).
 * :field trnName: header transaction name (``TRNNAMEO`` ``PIC X(04)``).
 * :field pgmName: header program name (``PGMNAMEO`` ``PIC X(08)``).
 * :field currentDate: header current date (``CURDATEO`` ``PIC X(08)``).
 * :field currentTime: header current time (``CURTIMEO`` ``PIC X(08)``).
 */
export interface ReportResponseDto {
  monthly?: string;
  yearly?: string;
  custom?: string;
  startDateMonth?: string;
  startDateDay?: string;
  startDateYear?: string;
  endDateMonth?: string;
  endDateDay?: string;
  endDateYear?: string;
  confirm?: string;
  errorMessage?: ErrMsg;
  message?: ErrMsg;
  title01?: string;
  title02?: string;
  trnName?: string;
  pgmName?: string;
  currentDate?: string;
  currentTime?: string;
  jobExecutionId?: string | null;
}

/**
 * :module: common
 * :purpose: Foundational, dependency-free shared TypeScript primitives for the
 *   CardDemo single-page-application type layer. Declares the message aliases,
 *   the PF-key action enum, the client-side field-error/highlight map, the
 *   pagination shapes, the standardized API error-response contract, and the
 *   shared active-status flag consumed by every other ``frontend/src/types``
 *   module and by the ``api``, ``hooks``, ``components``, and ``pages`` folders.
 */

/**
 * :purpose: Semantic alias for the line-23 error text rendered by the SPA.
 * :note: Mirrors the BMS ``ERRMSG`` symbolic-map field (maximum length 78
 *   characters; 80 on the two card screens). The width cannot be enforced on a
 *   TypeScript ``string`` and is documented here for reference only.
 */
export type ErrMsg = string;

/**
 * :purpose: Semantic alias for the informational message text rendered by the
 *   SPA.
 * :note: Mirrors the BMS ``INFOMSG`` symbolic-map field (maximum length 45
 *   characters; 40 on some screens). The width cannot be enforced on a
 *   TypeScript ``string`` and is documented here for reference only.
 */
export type InfoMsg = string;

/**
 * :purpose: Enumerates the 3270 attention-identifier (AID) actions the SPA
 *   reproduces as toolbar buttons and keyboard handlers.
 * :note: Derived from the ``CSSTRPFY`` AID mapping (``DFHENTER`` maps to ENTER,
 *   ``DFHCLEAR`` to CLEAR, ``DFHPF3`` to PF3, ``DFHPF7`` to PF7, ``DFHPF8`` to
 *   PF8, and so on). Observable conventions: ENTER submits, PF3 exits/returns,
 *   PF4 clears, PF5 saves (account update), PF7 pages backward, PF8 pages
 *   forward, PF12 cancels. Consumed by the ``PFKeyBar`` component and page
 *   key-handlers.
 */
export enum PfKeyAction {
  Enter = 'ENTER',
  Clear = 'CLEAR',
  PF3 = 'PF3',
  PF4 = 'PF4',
  PF5 = 'PF5',
  PF7 = 'PF7',
  PF8 = 'PF8',
  PF12 = 'PF12',
}

/**
 * :purpose: Client-side highlight state for a single form field, reproducing the
 *   ``CSSETATY`` 3270 attribute logic applied on re-entry.
 * :field invalid: ``true`` when the field failed validation
 *   (``FLG-<field>-NOT-OK``); the field is rendered RED.
 * :field blank: ``true`` when a required field was left empty
 *   (``FLG-<field>-BLANK``); a leading ``'*'`` marker is rendered.
 */
export interface FieldErrorState {
  invalid: boolean;
  blank: boolean;
}

/**
 * :purpose: Map of form field name to its client-side highlight state, used to
 *   reproduce the 3270 red / ``'*'`` field highlighting across a screen.
 * :note: Distinct from the backend ``ApiErrorResponse.fieldErrors`` (field name
 *   to message); this map carries only the local highlight flags.
 */
export type FieldErrorMap = Record<string, FieldErrorState>;

/**
 * :purpose: Pagination state for the list screens, modelling the BMS
 *   ``PAGENUM`` / ``PAGENO`` field together with the PF7/PF8 paging controls.
 * :field pageNumber: one-based index of the currently displayed page.
 * :field pageSize: number of rows displayed per page (7 for the card list
 *   COCRDLI; 10 for the transaction list COTRN00 and the user list COUSR00).
 * :field hasNext: ``true`` when a following page exists (PF8 enabled).
 * :field hasPrevious: ``true`` when a preceding page exists (PF7 enabled).
 */
export interface PageInfo {
  pageNumber: number;
  pageSize: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/**
 * :purpose: Reusable paged-result wrapper composed by the list response DTOs
 *   (card list, transaction list, user list).
 * :field items: the rows for the current page.
 * :field page: the pagination state for the current page.
 */
export interface Page<T> {
  items: T[];
  page: PageInfo;
}

/**
 * :purpose: Standardized JSON error body returned by the CardDemo REST APIs,
 *   mirroring the backend ``ErrorResponse`` DTO so axios responses deserialize
 *   cleanly.
 * :field timestamp: ISO-8601 instant at which the error was produced.
 * :field status: HTTP status code.
 * :field error: HTTP reason phrase.
 * :field errorCode: stable, non-sensitive application/domain error code that is
 *   decoupled from the HTTP status, mirroring ``ErrorResponse.errorCode``. It
 *   carries domain reject codes such as the batch posting codes ``100``-``103``
 *   so callers can branch on a precise code instead of parsing ``message``;
 *   absent when the error has no domain-specific code.
 * :field message: human-readable error description.
 * :field path: request path that produced the error.
 * :field traceId: MDC correlation id for the request; absent when tracing is
 *   unavailable.
 * :field fieldErrors: field name to message map driving per-field messages,
 *   combined by pages with the client ``FieldErrorMap`` highlight state; absent
 *   when the error is not field-specific.
 */
export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  errorCode?: string;
  message: string;
  path: string;
  traceId?: string;
  fieldErrors?: Record<string, string>;
}

/**
 * :purpose: Shared single-character active-status flag reused by the account
 *   (``ACCT-ACTIVE-STATUS``) and card (``CARD-ACTIVE-STATUS``) records.
 * :note: Maps the COBOL ``X(01)`` flag — ``'Y'`` active, ``'N'`` inactive.
 *   Domain modules reuse this alias rather than redefining it.
 */
export type ActiveStatus = 'Y' | 'N';

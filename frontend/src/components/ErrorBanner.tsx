/**
 * ErrorBanner
 * ===========
 *
 * :purpose: Render the BMS line-23 message region (``X(78)``, RED) that shows the
 *     current screen error or informational message, and provide the per-field
 *     highlight helpers derived from ``app/cpy/CSSETATY.cpy``: on re-entry an
 *     invalid or blank field renders RED (``.fieldError``) and a blank required
 *     field additionally receives a leading ``*`` marker (``.blankMarker``).
 */
import type { ReactElement } from 'react';
import type { FieldErrorMap, FieldErrorState } from '../types';

/** Maximum width of the line-23 message region (BMS ``X(78)``). */
export const ERROR_LINE_WIDTH = 78;

/**
 * :purpose: DOM id of the line-23 message region, so a screen can associate the
 *     message with the field whose edit produced it through ``aria-describedby``.
 */
export const ERROR_LINE_ID = 'screenMessageLine';

/**
 * :purpose: Props for :func:`ErrorBanner`.
 * :param message: Error message, rendered RED with ``role="alert"``.
 * :param noticeMessage: A non-failure condition the screen reports on the same line-23
 *     region and in the same RED its mapset declares statically -- reaching the end of
 *     a browse, or an empty page. It keeps the legacy colour but is announced with
 *     ``role="status"``: the 3270 had no notion of an assertive announcement, and
 *     interrupting a screen-reader user to tell them a list ended is not what the
 *     colour was expressing.
 * :param infoMessage: Informational text a program writes into that SAME ``ERRMSG``
 *     field after moving ``DFHGREEN`` into its colour byte, so it occupies line 23 and
 *     renders GREEN with ``role="status"``. Eight programs do this -- COADM01C, COMEN01C,
 *     COBIL00C, CORPT00C, COTRN02C, COUSR01C, COUSR02C and COUSR03C -- and because it is
 *     one field it holds either an error or this text, never both.
 * :param infoFieldMessage: Text a program writes into the mapset's own, SEPARATE
 *     ``INFOMSG`` field. Five mapsets declare one, and every one of them places it ABOVE
 *     the ``ERRMSG`` region at ``POS=(23,1)`` and colours it ``NEUTRAL``: COACTUP and
 *     COACTVW at ``POS=(22,23)``, COCRDLI at ``POS=(20,19)``, COCRDSL and COCRDUP at
 *     ``POS=(20,25)``. Their programs confirm that colour at runtime -- COCRDLIC,
 *     COACTVWC and COCRDSLC move ``DFHNEUTR`` into ``INFOMSGC`` when the line is shown
 *     and ``DFHBMDAR`` when it is not, while COACTUPC and COCRDUPC leave the declared
 *     colour in force and only darken the field -- and none of the five ever moves
 *     ``DFHGREEN`` anywhere. Supplying the prop reserves the row the field occupies, so
 *     the message region does not shift as the line comes and goes; an empty string
 *     renders the darkened state.
 * :param sendCount: How many attention identifiers the screen has sent. A CICS program
 *     writes ``ERRMSG`` on every ``SEND MAP``, so a second identical rejection is
 *     announced again; a live region only announces text that CHANGED, so the region is
 *     remounted per send to reproduce that.
 */
export interface ErrorBannerProps {
  message?: string;
  noticeMessage?: string;
  infoMessage?: string;
  infoFieldMessage?: string;
  messageReference?: string | null;
  sendCount?: number;
}

/**
 * :purpose: Whether a field should render in error (RED). True when the field is
 *     flagged invalid or blank, mirroring ``FLG-x-NOT-OK OR FLG-x-BLANK``.
 * :param state: The field's error state, if any.
 * :returns: ``true`` when the field is in error.
 */
export function isFieldInError(state: FieldErrorState | undefined): boolean {
  return state !== undefined && (state.invalid || state.blank);
}

/**
 * :purpose: The CSS class for a field given its error state.
 * :param state: The field's error state, if any.
 * :returns: ``fieldError`` when invalid/blank, otherwise an empty string.
 */
export function fieldErrorClass(state: FieldErrorState | undefined): string {
  return isFieldInError(state) ? 'fieldError' : '';
}

/*
 * Which screens may paint a field RED, settled by a census of the seventeen online
 * programs. A program reddens a field only through `MOVE DFHRED TO <field>C`, either
 * written inline or expanded from `app/cpy/CSSETATY.cpy`, and exactly five do:
 *
 *   COACTUPC  ACCTSIDC inline (L3177, L3183) plus 39 `COPY CSSETATY` expansions, which
 *             is every enterable field on the screen (ACSTTUS, the three date triples,
 *             both limits, the name/address/city/state/zip/country group, both phone
 *             triples, the SSN triple, ACSEFTC, ACSPFLG, ACSTFCO, ACURBAL, ACRCYCR,
 *             ACRCYDB)
 *   COCRDUPC  ACCTSIDC, CARDSIDC, CRDNAMEC, CRDSTCDC, EXPMONC, EXPYEARC (twice each)
 *   COCRDLIC  ACCTSIDC, CARDSIDC and CRDSEL1C..CRDSEL7C
 *   COCRDSLC  ACCTSIDC and CARDSIDC (twice each)
 *   COACTVWC  ACCTSIDC (twice)
 *
 * The other twelve contain NO `MOVE DFHRED` and no `COPY CSSETATY` at all -- COUSR02C's
 * single one colours `ERRMSGC`, the message line itself, not a field. Those twelve mark
 * a rejected entry with `MOVE -1 TO <field>L` (the cursor) and the line-23 message, and
 * nothing else, so a red frame on them is observable output the mapset never declares.
 *
 * Two idioms therefore carry the RED, and the red is driven by them and NOT by
 * `aria-invalid`, which every screen sets for its own, accessibility reason:
 *   - `fieldErrorClass` above, for the screens that hold a per-field `CSSETATY` flag map
 *     (the account and card update screens, the card detail screen, the card list's row
 *     selection column, the account view's key field);
 *   - `faultedFieldProps` below, for a control the screen marks from a single condition,
 *     which emits the paint signal together with the accessible one so a page cannot
 *     acquire one without the other.
 */

/**
 * :purpose: The leading marker for a field: ``*`` for a blank required field
 *     (``FLG-x-BLANK`` -> ``MOVE '*'``), otherwise an empty string.
 * :param state: The field's error state, if any.
 * :returns: ``*`` when blank, otherwise an empty string.
 */
export function fieldMarker(state: FieldErrorState | undefined): string {
  return state !== undefined && state.blank ? '*' : '';
}

/**
 * :purpose: Accessibility bindings emitted on the one control a screen faults for
 *     the current line-23 message, mirroring the program's ``MOVE -1 TO <field>L``
 *     cursor placement.
 * :param invalid: ``aria-invalid``, present only when the control is faulted.
 * :param describedBy: Points at :data:`ERROR_LINE_ID`, present only when faulted.
 */
export interface InvalidFieldProps {
  'aria-invalid'?: true;
  'aria-describedby'?: string;
}

/**
 * :purpose: Build the accessibility bindings for the control a screen faults,
 *     associating it with the line-23 message region and keeping any static hint
 *     the field already describes itself with.
 * :param invalid: ``true`` when this control is the one the screen faults.
 * :param hintId: Optional id of the field's own hint text, retained ahead of the
 *     message region so both are announced.
 * :returns: ``aria-invalid`` plus the merged description when faulted, the hint
 *     alone when not faulted, or an empty object when neither applies. The message
 *     region is referenced only when faulted because :data:`ERROR_LINE_ID` exists
 *     only on the error variant, so an unconditional reference would dangle.
 */
export function invalidFieldProps(invalid: boolean, hintId?: string): InvalidFieldProps {
  const described = [hintId, invalid ? ERROR_LINE_ID : undefined].filter(
    (part): part is string => part !== undefined && part !== '',
  );
  const props: InvalidFieldProps = {};
  if (invalid) {
    props['aria-invalid'] = true;
  }
  if (described.length > 0) {
    props['aria-describedby'] = described.join(' ');
  }
  return props;
}

/**
 * :purpose: Bindings for a control the program both faults and REPAINTS, on the five
 *     screens whose source moves ``DFHRED`` into a field (see the census above).
 * :param data-faulted: The paint signal the stylesheet keys the RED treatment off,
 *     present only when the control is faulted.
 */
export interface FaultedFieldProps extends InvalidFieldProps {
  'data-faulted'?: 'true';
}

/**
 * :purpose: Build the bindings for a faulted control that the program ALSO paints
 *     ``DFHRED``: the accessible state of :func:`invalidFieldProps` plus the
 *     ``data-faulted`` attribute the stylesheet paints from.
 * :param faulted: ``true`` when this control is the one the screen faults.
 * :param hintId: Optional id of the field's own hint text, forwarded unchanged.
 * :returns: The accessible bindings, carrying ``data-faulted`` when faulted.
 * :note: Use this ONLY where the legacy program moves ``DFHRED`` into that field.
 *     Everywhere else use :func:`invalidFieldProps`, which marks the control for
 *     assistive technology without painting a frame the mapset never declares.
 */
export function faultedFieldProps(faulted: boolean, hintId?: string): FaultedFieldProps {
  const props: FaultedFieldProps = invalidFieldProps(faulted, hintId);
  if (faulted) {
    props['data-faulted'] = 'true';
  }
  return props;
}

/**
 * :purpose: Build the accessible invalid state for a control rejected by its own
 *     value, with no line-23 message describing it — the row-action columns, which
 *     the legacy screens fault only on the next send.
 * :param invalid: ``true`` when the control's value is not an accepted code.
 * :returns: ``aria-invalid`` alone when invalid, otherwise an empty object. No
 *     description is emitted: with no message published, the error variant is not
 *     mounted and :data:`ERROR_LINE_ID` is absent from the document, so a reference
 *     would dangle. Use :func:`invalidFieldProps` whenever a message is on line 23.
 */
export function invalidValueProps(invalid: boolean): InvalidFieldProps {
  return invalid ? { 'aria-invalid': true } : {};
}

/**
 * :purpose: Whether any field in the map is currently flagged in error.
 * :param map: The field-error map, if any.
 * :returns: ``true`` when at least one field is in error.
 */
export function hasFieldErrors(map: FieldErrorMap | undefined): boolean {
  if (!map) {
    return false;
  }
  return Object.values(map).some((state) => isFieldInError(state));
}

/**
 * :purpose: Render the line-23 ``ERRMSG`` region. The mapsets declare one field there,
 *     so it carries one value: an error outranks a browse notice, which outranks the
 *     green informational text a program moves into the same field, and with none of
 *     them the region renders empty so the frame keeps the row.
 * :param message: Error text (RED, ``role="alert"``).
 * :param noticeMessage: Browse-boundary text (RED, ``role="status"``).
 * :param infoMessage: Green informational text (``role="status"``).
 * :param reference: Correlation carried as ``data-message-reference``.
 * :param sendKey: Identity of the current send. A CICS program writes ``ERRMSG`` on every
 *     ``SEND MAP``, so a second identical rejection is reported again; a live region only
 *     announces text that CHANGED, so the region is remounted per send to reproduce that.
 * :returns: The rendered line-23 region.
 */
function renderErrorLine(
  message: string | undefined,
  noticeMessage: string | undefined,
  infoMessage: string | undefined,
  reference: string | undefined,
  sendKey: string,
): ReactElement {
  if (message) {
    return (
      <div
        className="errorBanner"
        role="alert"
        id={ERROR_LINE_ID}
        data-message-reference={reference}
        key={sendKey}
      >
        {message}
      </div>
    );
  }
  if (noticeMessage) {
    return (
      <div
        className="errorBanner errorBanner--notice"
        role="status"
        id={ERROR_LINE_ID}
        data-message-reference={reference}
        key={sendKey}
      >
        {noticeMessage}
      </div>
    );
  }
  if (infoMessage) {
    // The id stays on every variant that carries text so an `aria-describedby` a
    // screen set on the control it faulted never dangles when the outcome turns
    // informational.
    return (
      <div
        className="errorBanner"
        role="status"
        id={ERROR_LINE_ID}
        data-message-reference={reference}
        key={sendKey}
      >
        {infoMessage}
      </div>
    );
  }
  // No text, so no live region: an empty one is a reader-only artefact of reserving the
  // row, and the variants above are remounted per send, which is what makes a repeated
  // message announce again.
  return <div className="errorBanner errorBanner--empty" data-testid="error-banner-empty" />;
}

/**
 * :purpose: The shared message region: the mapset's ``INFOMSG`` field, when it declares
 *     one, above the line-23 ``ERRMSG`` field.
 * :param message: Error message (RED, ``role="alert"``).
 * :param noticeMessage: Browse-boundary message (RED, ``role="status"``).
 * :param infoMessage: Green informational text in the ``ERRMSG`` field.
 * :param infoFieldMessage: Text of the separate ``INFOMSG`` field (NEUTRAL, above).
 * :param sendCount: How many attention identifiers the screen has sent; the line-23 region
 *     is remounted per send so a repeated identical message is announced again.
 * :returns: The rendered message region.
 */
export default function ErrorBanner({
  message,
  noticeMessage,
  infoMessage,
  infoFieldMessage,
  messageReference,
  sendCount = 0,
}: ErrorBannerProps): ReactElement {
  // Remounting on each send is what makes an unchanged message announce again: a live
  // region is silent when the text it already holds is written back to it.
  const sendKey = `send-${String(sendCount)}`;
  // The correlation the message belongs to, carried as an attribute rather than as screen
  // text: the 3270 message field is a frozen 79-character contract and appending a
  // reference to it would change literals the mapsets declare, while a request that
  // cannot be traced back to its log line is not observable. The value is whatever the
  // publishing screen holds — an error envelope's correlationId, or the execution id a
  // launched job reported.
  const reference = messageReference ?? undefined;
  const errorLine = renderErrorLine(message, noticeMessage, infoMessage, reference, sendKey);
  if (infoFieldMessage === undefined) {
    // Twelve of the seventeen mapsets declare no INFOMSG field, so line 23 is the whole
    // message region and no row is reserved above it.
    return errorLine;
  }
  // The five mapsets that declare both fields populate them on the same send -- COACTUPC
  // L2979-2981 moves WS-INFO-MSG to INFOMSGO and then WS-RETURN-MSG to ERRMSGO, COCRDLIC
  // L924-930 moves WS-ERROR-MSG to ERRMSGO and then WS-INFO-MSG to INFOMSGO -- so both
  // lines are rendered. The INFOMSG row comes first because every one of the five
  // declares it at a LOWER row number than ERRMSG at POS=(23,1): the error states what
  // was refused, the line above it states what to do next.
  return (
    <>
      <div
        className="errorBanner errorBanner--infoField"
        // The darkened state carries no text, so it is not announced: an empty live
        // region would be a reader-only artefact of reserving the row.
        role={infoFieldMessage === '' ? undefined : 'status'}
        data-testid="info-message-line"
      >
        {infoFieldMessage}
      </div>
      {errorLine}
    </>
  );
}

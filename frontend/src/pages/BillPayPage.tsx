/**
 * BillPayPage
 * ===========
 *
 * :purpose: Bill-payment screen — the 1:1 React replacement for BMS mapset
 *     ``app/bms/COBIL00.bms`` (map ``COBIL0A``), CICS transaction ``CB00``,
 *     program ``app/cbl/COBIL00C.cbl``. The operator enters an account id, the
 *     screen displays that account's current balance, and a single-character
 *     ``Y``/``N`` confirmation pays the outstanding balance in full against the
 *     account's available credit (credit limit minus current balance).
 * :output: The screen body for route ``/billpay``. The header, line-23 message
 *     region and line-24 function-key bar are published to the shared shell
 *     through ``useScreenChrome`` and are rendered by ``Layout``, never here.
 * :note: Field widths, labels and the function-key legend are taken verbatim
 *     from ``COBIL00.bms`` / ``app/cpy-bms/COBIL00.CPY``: ``ACTIDIN`` ``X(11)``,
 *     ``CURBAL`` ``X(14)`` (protected), ``CONFIRM`` ``X(1)``, ``ERRMSG``
 *     ``X(78)``. Money is carried and displayed as the server-supplied decimal
 *     ``string`` — it is never parsed, rounded or reformatted here.
 */

import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, CCDA_MSG_INVALID_KEY } from '../types';
import type { BillPayRequestDto, BillPayResponseDto } from '../types';
import { payBill } from '../api';
import { useApi, useFocusOnChange, useScreenAction } from '../hooks';
import OutputField from '../components/OutputField';
import { invalidFieldProps } from '../components/ErrorBanner';
import {
  displayText,
  resolveApiErrorMessage,
  toSignedAmountPicture,
} from '../components/display';

/** CICS transaction id of the bill-payment screen. */
const TRANSACTION_ID = 'CB00';

/** Legacy program name reproduced by this page. */
const PROGRAM_NAME = 'COBIL00C';

/**
 * Integer digit count of the balance picture. ``COBIL00C`` L56 declares
 * ``WS-CURR-BAL PIC +9999999999.99`` and L193-194 MOVEs ``ACCT-CURR-BAL`` through it into
 * ``CURBALI``, whose map field ``COBIL00.bms`` L103-106 declares ``LENGTH=14``. So the
 * balance reaches the screen edited — sign, ten zero-padded integer digits, point, two
 * decimals — not as the bare wire value.
 */
const BALANCE_INTEGER_DIGITS = 10;

/** ``COBIL00C`` empty account-id message. */
const MSG_ACCT_ID_EMPTY = 'Acct ID can NOT be empty...';

/** ``COBIL00C`` invalid confirm-flag message. */
const MSG_INVALID_CONFIRM = 'Invalid value. Valid values are (Y/N)...';

/** ``COBIL00C`` confirm-payment prompt shown once the balance is displayed. */
const MSG_CONFIRM_PAYMENT = 'Confirm to make a bill payment...';

/** ``COBIL00C`` L200-201 refusal for an account with a non-positive balance. */
const MSG_NOTHING_TO_PAY = 'You have nothing to pay...';

/**
 * ``COBIL00C`` success-banner prefix, including its trailing space. The legacy
 * ``STRING`` concatenates this with ``' Your Transaction ID is '``, hence the
 * two spaces in the rendered message.
 */
const MSG_PAYMENT_SUCCESSFUL = 'Payment successful. ';

/**
 * :purpose: The line-23 messages that take the cursor WITHOUT faulting the control it
 *     lands on, because none of them says that the control's content was refused:
 *
 *     - ``Confirm to make a bill payment...`` — published by ``COBIL00C`` L237 as soon as
 *       the balance is on screen, over an EMPTY confirm field. It reports what the screen
 *       is waiting for, and an entry the operator has not made yet cannot be wrong.
 *     - ``You have nothing to pay...`` — L200-201 refuses the account's zero BALANCE, not
 *       the account id the cursor returns to; the id it was keyed with is correct.
 *     - ``Invalid key pressed. Please see below...`` — the ``WHEN OTHER`` branch of
 *       ``EVALUATE EIBAID`` (L138-142) rejects an attention identifier, not a field.
 *
 *     Every other message on this screen — this screen's own value edits and the ones the
 *     service produces, which no client-side table could enumerate — does refuse a value,
 *     and faults the control the cursor lands on.
 */
const UNFAULTED_MESSAGES: ReadonlySet<string> = new Set([
  MSG_CONFIRM_PAYMENT,
  MSG_NOTHING_TO_PAY,
  CCDA_MSG_INVALID_KEY,
]);

/** ``ACTIDIN`` field width (``PIC X(11)``). */
const ACCT_ID_WIDTH = 11;

/** ``CURBAL`` field width (``PIC X(14)``). */
const CURR_BAL_WIDTH = 14;

/**
 * :purpose: The ``COBIL00`` balance caption, verbatim at its declared ``LENGTH=25``
 *     (``POS=(11,6) COLOR=TURQUOISE``): twenty-four visible characters plus the trailing
 *     pad column that separates it from ``CURBAL`` at column 32.
 */
const BALANCE_CAPTION = 'Your current balance is: ';

/** ``CONFIRM`` field width (``PIC X(1)``). */
const CONFIRM_WIDTH = 1;

/** Width of the decorative rule on BMS line 8 (``LENGTH=70``). */
const SEPARATOR_WIDTH = 70;

/**
 * :purpose: The enterable field a screen send places the cursor on. ``COBIL00C``
 *     ends every path with ``MOVE -1 TO ACTIDINL`` or ``MOVE -1 TO CONFIRML``
 *     before its ``SEND``, so each outcome names one of these two fields.
 */
type CursorField = 'acctId' | 'confirm';

/**
 * :purpose: A cursor placement. ``seq`` distinguishes consecutive placements on
 *     the same field so a repeated outcome still moves the cursor.
 */
interface ScreenCursor {
  field: CursorField;
  seq: number;
}

/**
 * :purpose: Build the success banner for a completed payment.
 * :param response: the bill-payment response carrying the generated
 *     transaction id and, normally, the server-composed message.
 * :returns: the server message verbatim when present, otherwise the message
 *     composed exactly as ``COBIL00C`` composes it.
 */
function buildPaymentSuccessMessage(response: BillPayResponseDto): string {
  const serverMessage = displayText(response.message);
  if (serverMessage.length > 0) {
    return serverMessage;
  }
  return `${MSG_PAYMENT_SUCCESSFUL} Your Transaction ID is ${displayText(
    response.transactionId,
  )}.`;
}

/**
 * :purpose: The bill-payment screen.
 * :returns: The rendered screen body.
 */
export default function BillPayPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { run, error, reset } = useApi<BillPayResponseDto, [BillPayRequestDto]>(
    payBill,
  );

  const [accountId, setAccountId] = useState('');
  const [currentBalance, setCurrentBalance] = useState('');
  const [confirmValue, setConfirmValue] = useState('');
  const [screenMessage, setScreenMessage] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [cursor, setCursor] = useState<ScreenCursor>({
    field: 'acctId',
    seq: 0,
  });

  const accountIdRef = useRef<HTMLInputElement | null>(null);
  const confirmRef = useRef<HTMLInputElement | null>(null);
  // True while a bill-payment request is in flight.
  const submittingRef = useRef(false);

  // Line-23 shows the page-produced text when there is one, otherwise the
  // message of the most recent failed call.
  const errorMessage =
    screenMessage.length > 0
      ? screenMessage
      : error === null
        ? ''
        : resolveApiErrorMessage(error);

  /**
   * :purpose: ``MOVE -1 TO <field>L`` — name the field the next screen send
   *     places the cursor on.
   * :param field: Field that receives the cursor.
   */
  const placeCursor = useCallback((field: CursorField): void => {
    setCursor((previous) => ({ field, seq: previous.seq + 1 }));
  }, []);

  // ACTIDIN carries the mapset's ``IC`` attribute, so the opening placement is
  // the screen's initial cursor position as well.
  const settled = !submitting;
  const acctIdCursor =
    settled && cursor.field === 'acctId' ? `acctId:${String(cursor.seq)}` : '';
  const confirmCursor =
    settled && cursor.field === 'confirm' ? `confirm:${String(cursor.seq)}` : '';
  useFocusOnChange(acctIdCursor, accountIdRef);
  useFocusOnChange(confirmCursor, confirmRef);

  // The faulted control is the one the screen sends the cursor to. Every `COBIL00C`
  // path that reports something ends with `MOVE -1 TO ACTIDINL` or `MOVE -1 TO
  // CONFIRML`, so the cursor target IS the field the row-23 message is about --
  // including the messages the SERVER produces, which no client-side table of message
  // text can enumerate.
  //
  // EXCEPT for the messages that are not about a value at all (:data:`UNFAULTED_MESSAGES`).
  // The cursor is a position; `aria-invalid` is an assertion that the control's CONTENT was
  // refused. `Invalid value. Valid values are (Y/N)...` (L187) IS such a refusal and still
  // marks.
  const faultedField: CursorField | null =
    errorMessage === '' || UNFAULTED_MESSAGES.has(errorMessage) ? null : cursor.field;

  /**
   * :purpose: ``INITIALIZE-ALL-FIELDS`` / ``CLEAR-CURRENT-SCREEN`` — blank the
   *     account id, balance, confirm flag and both message lines, then return
   *     the cursor to ``ACTIDIN``. Bound to F4 and to a ``N`` confirmation.
   */
  const handleClear = useCallback((): void => {
    setAccountId('');
    setCurrentBalance('');
    setConfirmValue('');
    setScreenMessage('');
    setInfoMessage('');
    reset();
    placeCursor('acctId');
  }, [placeCursor, reset]);

  /**
   * :purpose: ``RETURN-TO-PREV-SCREEN`` — leave the screen for the main menu
   *     (``COMEN01C``). Bound to F3.
   */
  const handleExit = useCallback((): void => {
    void navigate('/menu');
  }, [navigate]);

  /**
   * :purpose: ``PROCESS-ENTER-KEY`` — validate the account id, dispatch on the
   *     confirm flag, then either prompt for confirmation while displaying the
   *     current balance or submit the full-balance payment.
   * :note: The entry fields are read from the live controls, mirroring
   *     ``EXEC CICS RECEIVE MAP``, which reads the current screen buffer rather
   *     than a program variable.
   */
  const handleEnter = useCallback(async (): Promise<void> => {
    if (submittingRef.current) {
      return;
    }

    const enteredAccountId = (accountIdRef.current?.value ?? '').trim();
    if (enteredAccountId.length === 0) {
      reset();
      setInfoMessage('');
      setScreenMessage(MSG_ACCT_ID_EMPTY);
      placeCursor('acctId');
      return;
    }

    const enteredConfirm = (confirmRef.current?.value ?? '').trim();
    if (enteredConfirm === 'N' || enteredConfirm === 'n') {
      handleClear();
      return;
    }

    const confirmPay = enteredConfirm === 'Y' || enteredConfirm === 'y';
    if (!confirmPay && enteredConfirm.length > 0) {
      reset();
      setInfoMessage('');
      setScreenMessage(MSG_INVALID_CONFIRM);
      placeCursor('confirm');
      return;
    }

    setScreenMessage('');
    setInfoMessage('');

    const request: BillPayRequestDto = {
      accountId: enteredAccountId,
      confirm: enteredConfirm,
    };
    let response: BillPayResponseDto | undefined;
    submittingRef.current = true;
    setSubmitting(true);
    try {
      response = await run(request);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
    if (response === undefined) {
      // The balance already on screen is deliberately LEFT there. `COBIL00` declares
      // `CURBAL ATTRB=(ASKIP,FSET,NORM)`, and `COBIL0AO REDEFINES COBIL0AI` places
      // `CURBALO` and `CURBALI` on the same fourteen bytes, so the FSET tag returns the
      // displayed value on `RECEIVE MAP` and the send echoes it straight back. A read that
      // fails sends the map from inside `READ-ACCTDAT-FILE`, BEFORE the L193 move that
      // would replace it, so the legacy screen shows the previous balance beside the
      // not-found message too. Only a turn that reaches the account repaints the field --
      // which is the case the reported defect was actually about, and it is fixed above.
      placeCursor('acctId');
      return;
    }

    const transactionId = displayText(response.transactionId);
    if (transactionId.length > 0) {
      setAccountId('');
      setCurrentBalance('');
      setConfirmValue('');
      setInfoMessage(buildPaymentSuccessMessage(response));
      placeCursor('acctId');
      return;
    }

    // The balance is taken from the response on EVERY answered turn, which is what keeps
    // it tied to the account id beside it: COBIL00C moves `ACCT-CURR-BAL` into `CURBALI`
    // at L193-194 before it decides which message to send, so both outcomes below repaint
    // the field from the account just read, in the picture the mapset declares.
    const wireBalance = displayText(response.currentBalance);
    setCurrentBalance(
      wireBalance === ''
        ? ''
        : toSignedAmountPicture(wireBalance, BALANCE_INTEGER_DIGITS),
    );

    // `You have nothing to pay...` is sent on the RED channel with the balance beside it,
    // and it cursors ACTIDIN rather than CONFIRM (COBIL00C L202).
    const refusal = displayText(response.errorMessage);
    if (refusal.length > 0) {
      setInfoMessage('');
      setScreenMessage(refusal);
      placeCursor('acctId');
      return;
    }

    const previewMessage = displayText(response.message);
    const published =
      previewMessage.length > 0 ? previewMessage : MSG_CONFIRM_PAYMENT;
    setScreenMessage(published);
    // The confirm prompt ends ``MOVE -1 TO CONFIRML``; the nothing-to-pay refusal ends
    // ``MOVE -1 TO ACTIDINL``, because the account is what the operator must change.
    placeCursor(published === MSG_NOTHING_TO_PAY ? 'acctId' : 'confirm');
  }, [handleClear, placeCursor, reset, run]);

  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COBIL00C`` L138-142) — publish
   *     ``CCDA-MSG-INVALID-KEY`` and re-send the map, whose ``ATTRB=IC`` on ACTIDIN
   *     returns the cursor to the account-id field. Nothing the operator entered was
   *     rejected, so no field is faulted and any outstanding confirmation stands.
   */
  const handleUnhandledKey = useCallback((): void => {
    setScreenMessage(CCDA_MSG_INVALID_KEY);
    placeCursor('acctId');
  }, [placeCursor]);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateExit = useScreenAction(handleExit);
  const activateClear = useScreenAction(handleClear);
  const activateEnter = useScreenAction((): void => {
    void handleEnter();
  });
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    // The 3270 keyboard is LOCKED from the moment an AID is transmitted until the next
    // map arrives, so while the payment is outstanding the legend renders inactive and
    // no key is accepted -- the same treatment the sign-on and report screens carry.
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
        enabled: !submitting,
      },
      {
        action: PfKeyAction.PF4,
        label: 'F4=Clear',
        onActivate: activateClear,
        enabled: !submitting,
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      onUnhandledKey: activateUnhandledKey,
      busy: submitting,
    });
  }, [
    activateClear,
    activateEnter,
    activateExit,
    activateUnhandledKey,
    errorMessage,
    infoMessage,
    setChrome,
    submitting,
  ]);

  return (
    <section className="billPay" aria-labelledby="billPayHeading">
      <h3 className="neutral" id="billPayHeading">
        Bill Payment
      </h3>

      <div className="billPay__row">
        {/* COBIL00 paints this caption COLOR=GREEN (LENGTH=14, POS=(6,6)) -- the same
            green as the entry field beside it, and the one entry caption in the app that
            is not the TURQUOISE `.prompt` tone. */}
        <label className="green" htmlFor="billPayAcctId">
          Enter Acct ID:
        </label>
        <input
          {...invalidFieldProps(faultedField === 'acctId')}
          id="billPayAcctId"
          name="ACTIDIN"
          className="field"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          maxLength={ACCT_ID_WIDTH}
          size={ACCT_ID_WIDTH}
          value={accountId}
          disabled={submitting}
          ref={accountIdRef}
          data-testid="acct-id"
          onChange={(event) => setAccountId(event.target.value)}
        />
      </div>

      <div className="billPay__separator title" aria-hidden="true">
        {'-'.repeat(SEPARATOR_WIDTH)}
      </div>

      <dl className="billPay__row">
        {/* LENGTH=25 at POS=(11,6): twenty-four visible characters and a trailing pad
            column, which separates the caption from CURBAL at column 32. The literal
            carries that column, so it is rendered rather than trimmed. */}
        <OutputField
          label={BALANCE_CAPTION}
          value={currentBalance}
          testId="cur-bal"
          width={CURR_BAL_WIDTH}
        />
      </dl>

      <div className="billPay__row">
        <label className="prompt" htmlFor="billPayConfirm">
          Do you want to pay your balance now. Please confirm:{' '}
        </label>
        {/* CONFIRM is a one-character BMS field; the shared stylesheet enlarges its
            hit area to the adopted 24x24 WCAG 2.5.8 minimum. */}
        <input
          id="billPayConfirm"
          name="CONFIRM"
          className="field"
          type="text"
          autoComplete="off"
          maxLength={CONFIRM_WIDTH}
          size={CONFIRM_WIDTH}
          value={confirmValue}
          disabled={submitting}
          ref={confirmRef}
          {...invalidFieldProps(faultedField === 'confirm', 'billPayConfirmValues')}
          data-testid="confirm"
          onChange={(event) => setConfirmValue(event.target.value)}
        />
        <span className="neutral" id="billPayConfirmValues">
          (Y/N)
        </span>
      </div>
    </section>
  );
}

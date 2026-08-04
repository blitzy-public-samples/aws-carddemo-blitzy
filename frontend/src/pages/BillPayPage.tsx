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

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { BillPayRequestDto, BillPayResponseDto } from '../types';
import { payBill, ApiError } from '../api';
import { useApi } from '../hooks';

/** CICS transaction id of the bill-payment screen. */
const TRANSACTION_ID = 'CB00';

/** Legacy program name reproduced by this page. */
const PROGRAM_NAME = 'COBIL00C';

/** ``COBIL00C`` empty account-id message. */
const MSG_ACCT_ID_EMPTY = 'Acct ID can NOT be empty...';

/** ``COBIL00C`` invalid confirm-flag message. */
const MSG_INVALID_CONFIRM = 'Invalid value. Valid values are (Y/N)...';

/** ``COBIL00C`` confirm-payment prompt shown once the balance is displayed. */
const MSG_CONFIRM_PAYMENT = 'Confirm to make a bill payment...';

/**
 * ``COBIL00C`` success-banner prefix, including its trailing space. The legacy
 * ``STRING`` concatenates this with ``' Your Transaction ID is '``, hence the
 * two spaces in the rendered message.
 */
const MSG_PAYMENT_SUCCESSFUL = 'Payment successful. ';

/** ``ACTIDIN`` field width (``PIC X(11)``). */
const ACCT_ID_WIDTH = 11;

/** ``CURBAL`` field width (``PIC X(14)``). */
const CURR_BAL_WIDTH = 14;

/** ``CONFIRM`` field width (``PIC X(1)``). */
const CONFIRM_WIDTH = 1;

/** Width of the decorative rule on BMS line 8 (``LENGTH=70``). */
const SEPARATOR_WIDTH = 70;

/**
 * :purpose: Render a nullable wire value as display text.
 * :param value: the value received from the service.
 * :returns: the value itself, or an empty string when it is absent, so ``null``
 *     and ``undefined`` are never shown on screen.
 */
function displayText(value: string | null | undefined): string {
  return value === null || value === undefined ? '' : value;
}

/**
 * :purpose: Resolve the line-23 text for a failed call, preferring the
 *     standardized ``ApiErrorResponse.message`` carried by the response body.
 * :param error: the normalized error surfaced by ``useApi``.
 * :returns: the backend message when the body carried one, otherwise the
 *     already-resolved error message.
 */
function resolveErrorMessage(error: ApiError): string {
  const bodyMessage = error.body?.message;
  return bodyMessage !== undefined && bodyMessage.length > 0
    ? bodyMessage
    : error.message;
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
        : resolveErrorMessage(error);

  // Initial cursor position: ACTIDIN (BMS ``IC`` attribute).
  useEffect(() => {
    accountIdRef.current?.focus();
  }, []);

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
    accountIdRef.current?.focus();
  }, [reset]);

  /**
   * :purpose: ``RETURN-TO-PREV-SCREEN`` — leave the screen for the main menu
   *     (``COMEN01C``). Bound to F3.
   */
  const handleExit = useCallback((): void => {
    navigate('/menu');
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
      accountIdRef.current?.focus();
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
      confirmRef.current?.focus();
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
    try {
      response = await run(request);
    } finally {
      submittingRef.current = false;
    }
    if (response === undefined) {
      accountIdRef.current?.focus();
      return;
    }

    const transactionId = displayText(response.transactionId);
    if (transactionId.length > 0) {
      setAccountId('');
      setCurrentBalance('');
      setConfirmValue('');
      setInfoMessage(buildPaymentSuccessMessage(response));
      accountIdRef.current?.focus();
      return;
    }

    setCurrentBalance(displayText(response.currentBalance));
    const previewMessage = displayText(response.message);
    setScreenMessage(
      previewMessage.length > 0 ? previewMessage : MSG_CONFIRM_PAYMENT,
    );
    confirmRef.current?.focus();
  }, [handleClear, reset, run]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: 'ENTER=Continue',
        onActivate: handleEnter,
      },
      { action: PfKeyAction.PF3, label: 'F3=Back', onActivate: handleExit },
      { action: PfKeyAction.PF4, label: 'F4=Clear', onActivate: handleClear },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: 'CardDemo',
      title02: 'Bill Payment',
      errorMessage,
      infoMessage,
      pfKeys,
    });
  }, [
    setChrome,
    errorMessage,
    infoMessage,
    handleEnter,
    handleExit,
    handleClear,
  ]);

  return (
    <section className="billPay" aria-labelledby="billPayHeading">
      <h3 className="neutral" id="billPayHeading">
        Bill Payment
      </h3>

      <div className="billPay__row">
        <label className="prompt" htmlFor="billPayAcctId">
          Enter Acct ID:
        </label>
        <input
          id="billPayAcctId"
          name="ACTIDIN"
          className="field"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          maxLength={ACCT_ID_WIDTH}
          size={ACCT_ID_WIDTH}
          value={accountId}
          ref={accountIdRef}
          data-testid="acct-id"
          onChange={(event) => setAccountId(event.target.value)}
        />
      </div>

      <div className="billPay__separator title" aria-hidden="true">
        {'-'.repeat(SEPARATOR_WIDTH)}
      </div>

      <div className="billPay__row">
        <label className="prompt" htmlFor="billPayCurrBal">
          Your current balance is:{' '}
        </label>
        <input
          id="billPayCurrBal"
          name="CURBAL"
          className="label"
          type="text"
          readOnly
          tabIndex={-1}
          size={CURR_BAL_WIDTH}
          value={currentBalance}
          data-testid="cur-bal"
        />
      </div>

      <div className="billPay__row">
        <label className="prompt" htmlFor="billPayConfirm">
          Do you want to pay your balance now. Please confirm:{' '}
        </label>
        {/* BLITZY [A11Y]: CONFIRM is a 1-character BMS field, so the control is
            smaller than the 44x44px touch-target minimum. Implemented at the
            source width; flagged for designer review. */}
        <input
          id="billPayConfirm"
          name="CONFIRM"
          className="field"
          type="text"
          autoComplete="off"
          maxLength={CONFIRM_WIDTH}
          size={CONFIRM_WIDTH}
          value={confirmValue}
          ref={confirmRef}
          aria-describedby="billPayConfirmValues"
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

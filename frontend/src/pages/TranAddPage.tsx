/**
 * TranAddPage
 * ===========
 *
 * :purpose: Add-transaction entry screen for CICS transaction ``CT02`` (program
 *     ``COTRN02C``), replacing BMS mapset ``app/bms/COTRN02.bms``. It collects the key
 *     fields (account id or card number), the transaction detail fields and the
 *     ``(Y/N)`` confirmation flag, validates them in the legacy order, and posts the
 *     add request to the transaction service.
 * :note: The 16-digit zero-padded transaction id is assigned by the server, so the
 *     request carries no ``tranId``; the assigned id arrives on
 *     ``TranAddResponseDto.tranId`` and is surfaced on the line-23 confirmation.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { TranAddRequestDto, TranAddResponseDto } from '../types';
import { addTransaction, ApiError } from '../api';
import { useApi } from '../hooks';

/** The line-23 message literals of ``COTRN02C``, reproduced verbatim. */
const MESSAGES = {
  acctOrCardRequired: 'Account or Card Number must be entered...',
  acctIdNumeric: 'Account ID must be Numeric...',
  acctIdNotFound: 'Account ID NOT found...',
  cardNumberNumeric: 'Card Number must be Numeric...',
  cardNumberNotFound: 'Card Number NOT found...',
  typeCdEmpty: 'Type CD can NOT be empty...',
  categoryCdEmpty: 'Category CD can NOT be empty...',
  sourceEmpty: 'Source can NOT be empty...',
  descriptionEmpty: 'Description can NOT be empty...',
  amountEmpty: 'Amount can NOT be empty...',
  origDateEmpty: 'Orig Date can NOT be empty...',
  procDateEmpty: 'Proc Date can NOT be empty...',
  merchantIdEmpty: 'Merchant ID can NOT be empty...',
  merchantNameEmpty: 'Merchant Name can NOT be empty...',
  merchantCityEmpty: 'Merchant City can NOT be empty...',
  merchantZipEmpty: 'Merchant Zip can NOT be empty...',
  typeCdNumeric: 'Type CD must be Numeric...',
  categoryCdNumeric: 'Category CD must be Numeric...',
  amountFormat: 'Amount should be in format -99999999.99',
  origDateFormat: 'Orig Date should be in format YYYY-MM-DD',
  procDateFormat: 'Proc Date should be in format YYYY-MM-DD',
  origDateInvalid: 'Orig Date - Not a valid date...',
  procDateInvalid: 'Proc Date - Not a valid date...',
  merchantIdNumeric: 'Merchant ID must be Numeric...',
  confirmRequired: 'Confirm to add this transaction...',
  invalidYesNo: 'Invalid value. Valid values are (Y/N)...',
} as const;

/** Hint shown with the amount field (BMS line 15). */
const AMOUNT_HINT = '(-99999999.99)';

/** Hint shown with each date field (BMS line 15). */
const DATE_HINT = '(YYYY-MM-DD)';

/** Decorative rule drawn on BMS line 8 (``LENGTH=70``). */
const SEPARATOR_RULE = '-'.repeat(70);

/** Non-breaking gap that keeps a caption, its field and its hint on one line. */
const NBSP = '\u00a0';

/**
 * :purpose: The editable ``COTRN2AI`` symbolic-map fields held as screen state. Every
 *     member is a character field, as on the 3270 map, and its name doubles as the
 *     ``id`` of the rendered input.
 * :note: Maps ``ACTIDIN``, ``CARDNIN``, ``TTYPCD``, ``TCATCD``, ``TRNSRC``, ``TDESC``,
 *     ``TRNAMT``, ``TORIGDT``, ``TPROCDT``, ``MID``, ``MNAME``, ``MCITY``, ``MZIP`` and
 *     ``CONFIRM``, in screen order.
 */
interface TranAddFormState {
  acctId: string;
  tranCardNum: string;
  tranTypeCd: string;
  tranCatCd: string;
  tranSource: string;
  tranDesc: string;
  tranAmt: string;
  tranOrigTs: string;
  tranProcTs: string;
  tranMerchantId: string;
  tranMerchantName: string;
  tranMerchantCity: string;
  tranMerchantZip: string;
  confirm: string;
}

/** One editable field of the add-transaction screen. */
type TranAddField = keyof TranAddFormState;

/** The cleared screen produced by ``INITIALIZE-ALL-FIELDS``. */
const EMPTY_FORM: TranAddFormState = {
  acctId: '',
  tranCardNum: '',
  tranTypeCd: '',
  tranCatCd: '',
  tranSource: '',
  tranDesc: '',
  tranAmt: '',
  tranOrigTs: '',
  tranProcTs: '',
  tranMerchantId: '',
  tranMerchantName: '',
  tranMerchantCity: '',
  tranMerchantZip: '',
  confirm: '',
};

/**
 * :purpose: The first failed edit of a validation pass.
 * :param field: Field the cursor is repositioned to.
 * :param message: Line-23 text published for the failure.
 */
interface ValidationFailure {
  field: TranAddField;
  message: string;
}

/** Blank-field edits, in the order ``VALIDATE-INPUT-DATA-FIELDS`` applies them. */
const BLANK_GUARDS: ReadonlyArray<readonly [TranAddField, string]> = [
  ['tranTypeCd', MESSAGES.typeCdEmpty],
  ['tranCatCd', MESSAGES.categoryCdEmpty],
  ['tranSource', MESSAGES.sourceEmpty],
  ['tranDesc', MESSAGES.descriptionEmpty],
  ['tranAmt', MESSAGES.amountEmpty],
  ['tranOrigTs', MESSAGES.origDateEmpty],
  ['tranProcTs', MESSAGES.procDateEmpty],
  ['tranMerchantId', MESSAGES.merchantIdEmpty],
  ['tranMerchantName', MESSAGES.merchantNameEmpty],
  ['tranMerchantCity', MESSAGES.merchantCityEmpty],
  ['tranMerchantZip', MESSAGES.merchantZipEmpty],
];

/**
 * :purpose: Report whether a value is a non-empty run of digits, the COBOL
 *     ``IS NUMERIC`` class test on an unsigned character field.
 * :param value: Trimmed field value.
 * :returns: ``true`` when every character is ``0``-``9``.
 */
function isNumericField(value: string): boolean {
  return /^[0-9]+$/.test(value);
}

/**
 * :purpose: Report whether an amount has the screen shape ``COTRN02C`` requires: a sign,
 *     eight digits, a decimal point and two digits.
 * :param value: Trimmed amount field value.
 * :returns: ``true`` when the value matches the ``-99999999.99`` shape.
 */
function matchesAmountShape(value: string): boolean {
  return /^[-+][0-9]{8}\.[0-9]{2}$/.test(value);
}

/**
 * :purpose: Report whether a date has the ``YYYY-MM-DD`` character shape.
 * :param value: Trimmed date field value.
 * :returns: ``true`` when the value matches four digits, ``-``, two digits, ``-``, two
 *     digits.
 */
function matchesDateShape(value: string): boolean {
  return /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value);
}

/**
 * :purpose: Report whether a year is a leap year under the Gregorian rule.
 * :param year: Four-digit year.
 * :returns: ``true`` for a leap year.
 */
function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

/**
 * :purpose: Report whether a ``YYYY-MM-DD`` value names a real calendar date, the
 *     equivalent of a severity-zero ``CSUTLDTC`` result.
 * :param value: Date already known to carry the ``YYYY-MM-DD`` shape.
 * :returns: ``true`` when the month and day exist in that year.
 */
function isCalendarDate(value: string): boolean {
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(5, 7));
  const day = Number(value.slice(8, 10));
  if (month < 1 || month > 12) {
    return false;
  }
  const monthLengths = [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day >= 1 && day <= monthLengths[month - 1];
}

/**
 * :purpose: Validate the entered screen in the exact ``COTRN02C`` order — key fields,
 *     blank guards, numeric edits, amount and date shapes, calendar validity, merchant
 *     id — stopping at the first failure as the legacy program does.
 * :param form: Current screen state.
 * :returns: The first failure, or ``null`` when every edit passes.
 */
function validateForm(form: TranAddFormState): ValidationFailure | null {
  const acctId = form.acctId.trim();
  const cardNum = form.tranCardNum.trim();
  if (acctId !== '') {
    if (!isNumericField(acctId)) {
      return { field: 'acctId', message: MESSAGES.acctIdNumeric };
    }
  } else if (cardNum !== '') {
    if (!isNumericField(cardNum)) {
      return { field: 'tranCardNum', message: MESSAGES.cardNumberNumeric };
    }
  } else {
    return { field: 'acctId', message: MESSAGES.acctOrCardRequired };
  }

  for (const [field, message] of BLANK_GUARDS) {
    if (form[field].trim() === '') {
      return { field, message };
    }
  }

  if (!isNumericField(form.tranTypeCd.trim())) {
    return { field: 'tranTypeCd', message: MESSAGES.typeCdNumeric };
  }
  if (!isNumericField(form.tranCatCd.trim())) {
    return { field: 'tranCatCd', message: MESSAGES.categoryCdNumeric };
  }
  if (!matchesAmountShape(form.tranAmt.trim())) {
    return { field: 'tranAmt', message: MESSAGES.amountFormat };
  }
  if (!matchesDateShape(form.tranOrigTs.trim())) {
    return { field: 'tranOrigTs', message: MESSAGES.origDateFormat };
  }
  if (!matchesDateShape(form.tranProcTs.trim())) {
    return { field: 'tranProcTs', message: MESSAGES.procDateFormat };
  }
  if (!isCalendarDate(form.tranOrigTs.trim())) {
    return { field: 'tranOrigTs', message: MESSAGES.origDateInvalid };
  }
  if (!isCalendarDate(form.tranProcTs.trim())) {
    return { field: 'tranProcTs', message: MESSAGES.procDateInvalid };
  }
  if (!isNumericField(form.tranMerchantId.trim())) {
    return { field: 'tranMerchantId', message: MESSAGES.merchantIdNumeric };
  }
  return null;
}

/**
 * :purpose: Reposition the cursor onto a screen field, the SPA equivalent of
 *     ``MOVE -1 TO <field>L``.
 * :param field: Field whose input receives focus; the input ``id`` is the field name.
 */
function focusField(field: TranAddField): void {
  const element = document.getElementById(field);
  if (element !== null) {
    element.focus();
  }
}

/**
 * :purpose: Resolve the line-23 text of a rejected add, preferring the standardized
 *     ``ApiErrorResponse.message`` carried by the response body.
 * :param error: Normalized client error.
 * :returns: The message to publish.
 */
function resolveErrorMessage(error: ApiError): string {
  return error.body?.message ?? error.message;
}

/**
 * :purpose: Resolve the confirmation text of a successful add, keeping the
 *     server-assigned transaction id on screen.
 * :param response: Add response carrying the assigned id and its message.
 * :returns: The message to publish.
 */
function resolveSuccessMessage(response: TranAddResponseDto): string {
  const serverMessage = response.message ?? '';
  if (serverMessage !== '' && serverMessage.includes(response.tranId)) {
    return serverMessage;
  }
  return `Transaction added successfully.  Your Tran ID is ${response.tranId}.`;
}

/**
 * :purpose: Render the add-transaction screen and drive its two-step confirm workflow:
 *     the first ENTER validates the entered fields and asks for confirmation, ``Y``
 *     posts the add, ``N`` returns to entry.
 * :returns: The screen body; the header, the line-23 message and the line-24 function
 *     keys are supplied by the shared shell through :func:`useScreenChrome`.
 */
export default function TranAddPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const [form, setForm] = useState<TranAddFormState>(EMPTY_FORM);
  const [errorMessage, setErrorMessage] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  const {
    run: runAdd,
    loading: adding,
    error: addError,
  } = useApi<TranAddResponseDto, [TranAddRequestDto]>(addTransaction);

  useEffect(() => {
    if (addError === null) {
      return;
    }
    setInfoMessage('');
    setErrorMessage(resolveErrorMessage(addError));
  }, [addError]);

  const updateField = useCallback((field: TranAddField, value: string): void => {
    setForm((previous) => ({ ...previous, [field]: value }));
  }, []);

  const submitAdd = useCallback(
    async (current: TranAddFormState): Promise<void> => {
      const request: TranAddRequestDto = {
        tranTypeCd: current.tranTypeCd.trim(),
        tranCatCd: Number(current.tranCatCd.trim()),
        tranSource: current.tranSource.trim(),
        tranDesc: current.tranDesc.trim(),
        tranAmt: current.tranAmt.trim(),
        tranOrigTs: current.tranOrigTs.trim(),
        tranProcTs: current.tranProcTs.trim(),
        tranMerchantId: current.tranMerchantId.trim(),
        tranMerchantName: current.tranMerchantName.trim(),
        tranMerchantCity: current.tranMerchantCity.trim(),
        tranMerchantZip: current.tranMerchantZip.trim(),
        confirm: current.confirm.trim(),
      };
      const acctId = current.acctId.trim();
      if (acctId !== '') {
        request.acctId = acctId;
      }
      const cardNum = current.tranCardNum.trim();
      if (cardNum !== '') {
        request.tranCardNum = cardNum;
      }

      const response = await runAdd(request);
      if (response === undefined) {
        return;
      }
      setForm(EMPTY_FORM);
      setErrorMessage('');
      setInfoMessage(resolveSuccessMessage(response));
      focusField('acctId');
    },
    [runAdd],
  );

  const handleEnter = useCallback((): void => {
    if (adding) {
      return;
    }
    const failure = validateForm(form);
    if (failure !== null) {
      setInfoMessage('');
      setErrorMessage(failure.message);
      focusField(failure.field);
      return;
    }

    const flag = form.confirm.trim().toUpperCase();
    if (flag === 'Y') {
      void submitAdd(form);
      return;
    }
    setInfoMessage('');
    if (flag === '' || flag === 'N') {
      setForm((previous) => ({ ...previous, confirm: '' }));
      setErrorMessage(MESSAGES.confirmRequired);
    } else {
      setErrorMessage(MESSAGES.invalidYesNo);
    }
    focusField('confirm');
  }, [adding, form, submitAdd]);

  const handleExit = useCallback((): void => {
    navigate('/transactions');
  }, [navigate]);

  const handleClear = useCallback((): void => {
    setForm(EMPTY_FORM);
    setErrorMessage('');
    setInfoMessage('');
    focusField('acctId');
  }, []);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Add', onActivate: handleEnter },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: handleExit },
      { action: PfKeyAction.PF4, label: 'F4=Clear', onActivate: handleClear },
    ];
    setChrome({
      transactionId: 'CT02',
      programName: 'COTRN02C',
      title01: 'CardDemo',
      title02: 'Add Transaction',
      errorMessage,
      infoMessage,
      pfKeys,
    });
  }, [setChrome, errorMessage, infoMessage, handleEnter, handleExit, handleClear]);

  return (
    <form
      className="tranAdd"
      aria-labelledby="tranAddHeading"
      autoComplete="off"
      onSubmit={(event) => {
        event.preventDefault();
        handleEnter();
      }}
    >
      <h3 className="neutral" id="tranAddHeading">
        Add Transaction
      </h3>

      <div className="tranAdd__row">
        <label className="prompt" htmlFor="acctId">
          Enter Acct #:
        </label>{NBSP}
        <input
          className="field"
          id="acctId"
          name="acctId"
          type="text"
          inputMode="numeric"
          maxLength={11}
          size={11}
          value={form.acctId}
          onChange={(event) => updateField('acctId', event.target.value)}
        />{' '}
        <span className="neutral">(or)</span>{' '}
        <label className="prompt" htmlFor="tranCardNum">
          Card #:
        </label>{NBSP}
        <input
          className="field"
          id="tranCardNum"
          name="tranCardNum"
          type="text"
          inputMode="numeric"
          maxLength={16}
          size={16}
          value={form.tranCardNum}
          onChange={(event) => updateField('tranCardNum', event.target.value)}
        />
      </div>

      <div className="tranAdd__rule neutral" aria-hidden="true">
        {SEPARATOR_RULE}
      </div>

      <div className="tranAdd__row">
        <label className="prompt" htmlFor="tranTypeCd">
          Type CD:
        </label>{NBSP}
        <input
          className="field"
          id="tranTypeCd"
          name="tranTypeCd"
          type="text"
          inputMode="numeric"
          maxLength={2}
          size={2}
          value={form.tranTypeCd}
          onChange={(event) => updateField('tranTypeCd', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranCatCd">
          Category CD:
        </label>{NBSP}
        <input
          className="field"
          id="tranCatCd"
          name="tranCatCd"
          type="text"
          inputMode="numeric"
          maxLength={4}
          size={4}
          value={form.tranCatCd}
          onChange={(event) => updateField('tranCatCd', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranSource">
          Source:
        </label>{NBSP}
        <input
          className="field"
          id="tranSource"
          name="tranSource"
          type="text"
          maxLength={10}
          size={10}
          value={form.tranSource}
          onChange={(event) => updateField('tranSource', event.target.value)}
        />
      </div>

      <div className="tranAdd__row">
        <label className="prompt" htmlFor="tranDesc">
          Description:
        </label>{NBSP}
        <input
          className="field"
          id="tranDesc"
          name="tranDesc"
          type="text"
          maxLength={60}
          size={60}
          value={form.tranDesc}
          onChange={(event) => updateField('tranDesc', event.target.value)}
        />
      </div>

      <div className="tranAdd__row">
        <span className="tranAdd__group">
          <label className="prompt" htmlFor="tranAmt">
            Amount:
          </label>{NBSP}
          <input
            className="field"
            id="tranAmt"
            name="tranAmt"
            type="text"
            maxLength={12}
            size={12}
            aria-describedby="tranAmtHint"
            value={form.tranAmt}
            onChange={(event) => updateField('tranAmt', event.target.value)}
          />{NBSP}
          <span className="label" id="tranAmtHint">
            {AMOUNT_HINT}
          </span>
        </span>{' '}
        <span className="tranAdd__group">
          <label className="prompt" htmlFor="tranOrigTs">
            Orig Date:
          </label>{NBSP}
          <input
            className="field"
            id="tranOrigTs"
            name="tranOrigTs"
            type="text"
            maxLength={10}
            size={10}
            aria-describedby="tranOrigTsHint"
            value={form.tranOrigTs}
            onChange={(event) => updateField('tranOrigTs', event.target.value)}
          />{NBSP}
          <span className="label" id="tranOrigTsHint">
            {DATE_HINT}
          </span>
        </span>{' '}
        <span className="tranAdd__group">
          <label className="prompt" htmlFor="tranProcTs">
            Proc Date:
          </label>{NBSP}
          <input
            className="field"
            id="tranProcTs"
            name="tranProcTs"
            type="text"
            maxLength={10}
            size={10}
            aria-describedby="tranProcTsHint"
            value={form.tranProcTs}
            onChange={(event) => updateField('tranProcTs', event.target.value)}
          />{NBSP}
          <span className="label" id="tranProcTsHint">
            {DATE_HINT}
          </span>
        </span>
      </div>

      <div className="tranAdd__row">
        <label className="prompt" htmlFor="tranMerchantId">
          Merchant ID:
        </label>{NBSP}
        <input
          className="field"
          id="tranMerchantId"
          name="tranMerchantId"
          type="text"
          inputMode="numeric"
          maxLength={9}
          size={9}
          value={form.tranMerchantId}
          onChange={(event) => updateField('tranMerchantId', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranMerchantName">
          Merchant Name:
        </label>{NBSP}
        <input
          className="field"
          id="tranMerchantName"
          name="tranMerchantName"
          type="text"
          maxLength={30}
          size={30}
          value={form.tranMerchantName}
          onChange={(event) => updateField('tranMerchantName', event.target.value)}
        />
      </div>

      <div className="tranAdd__row">
        <label className="prompt" htmlFor="tranMerchantCity">
          Merchant City:
        </label>{NBSP}
        <input
          className="field"
          id="tranMerchantCity"
          name="tranMerchantCity"
          type="text"
          maxLength={25}
          size={25}
          value={form.tranMerchantCity}
          onChange={(event) => updateField('tranMerchantCity', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranMerchantZip">
          Merchant Zip:
        </label>{NBSP}
        <input
          className="field"
          id="tranMerchantZip"
          name="tranMerchantZip"
          type="text"
          maxLength={10}
          size={10}
          value={form.tranMerchantZip}
          onChange={(event) => updateField('tranMerchantZip', event.target.value)}
        />
      </div>

      <div className="tranAdd__row tranAdd__confirm">
        <label className="prompt" htmlFor="confirm">
          You are about to add this transaction. Please confirm :
        </label>{NBSP}
        <input
          className="field"
          id="confirm"
          name="confirm"
          type="text"
          maxLength={1}
          size={1}
          aria-describedby="confirmValues"
          value={form.confirm}
          onChange={(event) => updateField('confirm', event.target.value)}
        />{NBSP}
        <span className="neutral" id="confirmValues">
          (Y/N)
        </span>
      </div>
    </form>
  );
}


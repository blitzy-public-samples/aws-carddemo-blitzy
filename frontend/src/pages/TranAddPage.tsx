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
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  PfKeyAction,
  CCDA_TITLE01,
  CCDA_TITLE02,
  CCDA_MSG_INVALID_KEY,
} from '../types';
import type {
  TranAddRequestDto,
  TranAddResponseDto,
  TranKeyResponseDto,
  TranViewResponseDto,
} from '../types';
import { addTransaction, getLastTransaction, resolveTransactionKeys } from '../api';
import { placeCursor, useApi, useScreenAction } from '../hooks';
import { invalidFieldProps } from '../components/ErrorBanner';
import { resolveApiErrorMessage } from '../components/display';
import { toAmountPicture, toMapDate } from './tranAddFormat';
import { isFilterAllDigits, isFilterNotSupplied } from './screenFilters';

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

/**
 * :purpose: The field ``COTRN02C`` faults for each message raised by the server rather
 *     than by the on-screen edits, reproducing its ``MOVE -1 TO <field>L`` placements.
 *     Any message absent from the map faults the account key field, which is the
 *     program's own fallback target.
 */
const CURSOR_BY_SERVER_MESSAGE: Readonly<Record<string, TranAddField>> = {
  [MESSAGES.cardNumberNotFound]: 'tranCardNum',
  [MESSAGES.cardNumberNumeric]: 'tranCardNum',
};

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
 * :purpose: The shape half of ``VALIDATE-INPUT-KEY-FIELDS``: the ``EVALUATE TRUE`` that
 *     gives ``ACTIDIN`` priority over ``CARDNIN``, each field's ``IS NOT NUMERIC`` edit,
 *     and the ``WHEN OTHER`` that demands one of them. The paragraph's other half is the
 *     cross-reference read, which is a lookup and therefore runs in the service — and
 *     because that read lives inside this paragraph, it precedes every data-field check.
 * :param form: Current screen state.
 * :returns: The first key-field failure, or ``null`` when the supplied key is well formed.
 */
function validateKeyFields(form: TranAddFormState): ValidationFailure | null {
  // The EVALUATE tests each field as received — ``NOT = SPACES AND LOW-VALUES`` — so a
  // field holding a tab is supplied and must pass ``IS NUMERIC``. Trimming first would
  // make it indistinguishable from an untouched field and answer with the wrong literal.
  if (!isFilterNotSupplied(form.acctId)) {
    if (!isFilterAllDigits(form.acctId)) {
      return { field: 'acctId', message: MESSAGES.acctIdNumeric };
    }
    return null;
  }
  if (!isFilterNotSupplied(form.tranCardNum)) {
    if (!isFilterAllDigits(form.tranCardNum)) {
      return { field: 'tranCardNum', message: MESSAGES.cardNumberNumeric };
    }
    return null;
  }
  return { field: 'acctId', message: MESSAGES.acctOrCardRequired };
}

/**
 * :purpose: ``VALIDATE-INPUT-DATA-FIELDS`` — the eleven empty guards followed by the
 *     class and shape edits, in the paragraph's own order.
 * :param form: the entry state.
 * :returns: the first failure, or ``null`` when every data field passes.
 */
function validateDataFields(form: TranAddFormState): ValidationFailure | null {
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
    placeCursor(element);
  }
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
/** ENTER entry of the ``app/bms/COTRN02.bms`` line-24 legend. */
const ENTER_LABEL = 'ENTER=Continue';

/** PF3 entry of the mapset line-24 legend. */
const BACK_LABEL = 'F3=Back';

/** PF4 entry of the mapset line-24 legend. */
const CLEAR_LABEL = 'F4=Clear';

/** PF5 entry of the mapset line-24 legend. */
const COPY_LAST_LABEL = 'F5=Copy Last Tran.';

/**
 * :purpose: Route PF3 leaves for. ``COTRN02C`` L136-L142 transfers to
 *     ``CDEMO-FROM-PROGRAM``, or to ``COMEN01C`` when no caller set one. The main menu
 *     is the only program in the application that reaches this screen — ``COMEN02Y``
 *     option 8 names ``COTRN02C`` and no other program ``XCTL``s to it — and
 *     ``COMEN01C`` L148 stamps its own name into ``CDEMO-FROM-PROGRAM`` before the
 *     transfer, so both the menu-driven path and the no-caller default resolve to the
 *     main menu.
 */
const MENU_ROUTE = '/menu';

export default function TranAddPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const [form, setForm] = useState<TranAddFormState>(EMPTY_FORM);
  const [errorMessage, setErrorMessage] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  // The one field the screen faults for the current line-23 message. It tracks the
  // ``MOVE -1 TO <field>L`` target exactly, so the accessible invalid state and the
  // cursor placement are driven by the same decision rather than derived twice.
  const [faultedField, setFaultedField] = useState<TranAddField | null>(null);
  const {
    run: runAdd,
    loading: adding,
    error: addError,
  } = useApi<TranAddResponseDto, [TranAddRequestDto]>(addTransaction);
  const { run: runCopyLast, error: copyLastError } =
    useApi<TranViewResponseDto, []>(getLastTransaction);
  const {
    run: runResolveKeys,
    error: resolveKeysError,
    loading: resolvingKeys,
  } = useApi<TranKeyResponseDto, [string, string]>(resolveTransactionKeys);

  // A ref, not the render-derived ``adding`` flag, is what makes a second ENTER
  // arriving in the same tick a no-op: state updates are asynchronous, so two
  // activations can both observe ``adding === false`` and each create a distinct
  // transaction with its own generated id.
  const submitLatch = useRef<boolean>(false);

  useEffect(() => {
    if (addError === null) {
      return;
    }
    const message = resolveApiErrorMessage(addError);
    const faulted = CURSOR_BY_SERVER_MESSAGE[message] ?? 'acctId';
    setInfoMessage('');
    setErrorMessage(message);
    setFaultedField(faulted);
    // Every refusal in the source ends ``MOVE -1 TO <field>L`` before SEND-TRNADD-SCREEN,
    // so the cursor lands on the field that was faulted. Placing it here rather than
    // leaving it to the settle rule is what stops a card that is not on file from
    // marking CARDNIN while the cursor returns to ACTIDIN.
    focusField(faulted);
  }, [addError]);

  useEffect(() => {
    if (copyLastError === null) {
      return;
    }
    const message = resolveApiErrorMessage(copyLastError);
    const faulted = CURSOR_BY_SERVER_MESSAGE[message] ?? 'acctId';
    setInfoMessage('');
    setErrorMessage(message);
    setFaultedField(faulted);
    focusField(faulted);
  }, [copyLastError]);

  useEffect(() => {
    if (resolveKeysError === null) {
      return;
    }
    const message = resolveApiErrorMessage(resolveKeysError);
    const faulted = CURSOR_BY_SERVER_MESSAGE[message] ?? 'acctId';
    setInfoMessage('');
    setErrorMessage(message);
    setFaultedField(faulted);
    focusField(faulted);
  }, [resolveKeysError]);

  // COTRN02 marks ACTIDIN ``ATTRB=(FSET,IC,NORM,UNPROT)``, so the cursor starts on the
  // account key field when the screen is first shown.
  useEffect(() => {
    focusField('acctId');
  }, []);

  // ``INITIALIZE-ALL-FIELDS`` places the cursor back on the account field after a
  // completed write. The entry fields are disabled for the whole in-flight interval and
  // focusing a disabled field is a no-op, so the placement has to happen on the settle
  // rather than in the request's own continuation — but ONLY for the outcome that
  // initialises the screen. A refused write is placed by the effect above, on the field
  // the refusal names, and this rule would otherwise pull the cursor off it.
  const wasAdding = useRef<boolean>(false);
  useEffect(() => {
    if (wasAdding.current && !adding && addError === null) {
      focusField('acctId');
    }
    wasAdding.current = adding;
  }, [adding, addError]);

  const updateField = useCallback((field: TranAddField, value: string): void => {
    setForm((previous) => ({ ...previous, [field]: value }));
  }, []);

  const submitAdd = useCallback(
    async (current: TranAddFormState): Promise<void> => {
      const request: TranAddRequestDto = {
        tranTypeCd: current.tranTypeCd.trim(),
        tranCatCd: current.tranCatCd.trim(),
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

      try {
        const response = await runAdd(request);
        if (response === undefined) {
          return;
        }
        setForm(EMPTY_FORM);
        setErrorMessage('');
        setFaultedField(null);
        setInfoMessage(resolveSuccessMessage(response));
      } finally {
        submitLatch.current = false;
      }
    },
    [runAdd],
  );

  const publishFailure = useCallback((failure: ValidationFailure): void => {
    setInfoMessage('');
    setErrorMessage(failure.message);
    setFaultedField(failure.field);
    focusField(failure.field);
  }, []);

  /**
   * :purpose: ``VALIDATE-INPUT-KEY-FIELDS`` in full — the shape edits, then the
   *     cross-reference read, then the counterpart key written back onto the map
   *     (``MOVE XREF-CARD-NUM TO CARDNINI`` / ``MOVE XREF-ACCT-ID TO ACTIDINI``).
   * :param current: the entry state the ENTER was pressed against.
   * :returns: the resolved key pair, or ``null`` when the paragraph reported a failure.
   */
  const runKeyFieldEdit = useCallback(
    async (current: TranAddFormState): Promise<TranKeyResponseDto | null> => {
      const shape = validateKeyFields(current);
      if (shape !== null) {
        publishFailure(shape);
        return null;
      }
      const resolved = await runResolveKeys(
        current.acctId.trim(),
        current.tranCardNum.trim(),
      );
      if (resolved === undefined) {
        return null;
      }
      setForm((previous) => ({
        ...previous,
        acctId: resolved.acctId,
        tranCardNum: resolved.tranCardNum,
      }));
      return resolved;
    },
    [publishFailure, runResolveKeys],
  );

  /**
   * :purpose: The ``EVALUATE CONFIRMI`` that closes ``PROCESS-ENTER-KEY``: ``Y`` writes,
   *     ``N``/blank asks for the confirmation, anything else reports the valid values.
   * :param current: the edited entry state.
   */
  const confirmOrAdd = useCallback((current: TranAddFormState): void => {
    const flag = current.confirm.trim().toUpperCase();
    if (flag === 'Y') {
      submitLatch.current = true;
      void submitAdd(current);
      return;
    }
    setInfoMessage('');
    if (flag === '' || flag === 'N') {
      setErrorMessage(MESSAGES.confirmRequired);
      // Asking for the confirmation is not a rejection of what was typed: `N` is an
      // accepted value and blank is the state the map is sent in. `COTRN02C` L177-181
      // moves the message and `MOVE -1 TO CONFIRML` -- the CURSOR -- and marks nothing,
      // so the control carries no invalid state for a prompt it has not yet answered.
      setFaultedField(null);
    } else {
      // Anything other than Y or N IS a rejected value (`COTRN02C` L183-187), so the
      // control is marked as well as cursored.
      setErrorMessage(MESSAGES.invalidYesNo);
      setFaultedField('confirm');
    }
    focusField('confirm');
  }, [submitAdd]);

  const handleEnter = useCallback((): void => {
    if (submitLatch.current || adding || resolvingKeys) {
      return;
    }
    void (async (): Promise<void> => {
      // PROCESS-ENTER-KEY performs VALIDATE-INPUT-KEY-FIELDS and only then
      // VALIDATE-INPUT-DATA-FIELDS, and the cross-reference reads are inside the first
      // paragraph. So an account or card that is not on file is reported before any
      // empty data field — reversing the two is what answered a key that does not exist
      // with "Type CD can NOT be empty...".
      const resolved = await runKeyFieldEdit(form);
      if (resolved === null) {
        return;
      }
      const current: TranAddFormState = {
        ...form,
        acctId: resolved.acctId,
        tranCardNum: resolved.tranCardNum,
      };
      const failure = validateDataFields(current);
      if (failure !== null) {
        publishFailure(failure);
        return;
      }
      confirmOrAdd(current);
    })();
  }, [adding, confirmOrAdd, form, publishFailure, resolvingKeys, runKeyFieldEdit]);


  /**
   * :purpose: PF3 — ``RETURN-TO-PREV-SCREEN`` with the caller resolved by
   *     ``COTRN02C`` L136-L142, which is the main menu on every path into this screen
   *     (see :data:`MENU_ROUTE`). Leaving for the transaction list instead sent the
   *     operator to a screen no legacy caller returns them to, and cost them a second
   *     PF3 to reach the menu they came from.
   */
  const handleExit = useCallback((): void => {
    void navigate(MENU_ROUTE);
  }, [navigate]);

  const handleClear = useCallback((): void => {
    setForm(EMPTY_FORM);
    setErrorMessage('');
    setInfoMessage('');
    setFaultedField(null);
    focusField('acctId');
  }, []);

  /**
   * :purpose: PF5 — ``COPY-LAST-TRAN-DATA``: run the key-field edit, read the last
   *     transaction on file, copy its editable fields onto the screen, and then perform
   *     ``PROCESS-ENTER-KEY``. The paragraph opens with ``PERFORM
   *     VALIDATE-INPUT-KEY-FIELDS`` and closes with ``PERFORM PROCESS-ENTER-KEY``, so a
   *     copy made with no account or card is refused before anything is read, and a copy
   *     that succeeds leaves the screen asking for the confirmation rather than silently
   *     filled in. It copies only ``TTYPCD`` through ``MZIP`` — the key fields carry
   *     whatever the edit resolved.
   */
  const handleCopyLast = useCallback((): void => {
    if (submitLatch.current || adding || resolvingKeys) {
      return;
    }
    void (async (): Promise<void> => {
      const resolved = await runKeyFieldEdit(form);
      if (resolved === null) {
        return;
      }
      const last = await runCopyLast();
      if (last === undefined) {
        return;
      }
      const copied: TranAddFormState = {
        ...form,
        acctId: resolved.acctId,
        tranCardNum: resolved.tranCardNum,
        tranTypeCd: last.tranTypeCd,
        tranCatCd: last.tranCatCd,
        tranSource: last.tranSource,
        tranDesc: last.tranDesc,
        // The legacy paragraph moves the amount through PIC +99999999.99 and the
        // timestamps into PIC X(10) fields, so the copied values arrive already edited
        // into the shapes the screen's own checks require.
        tranAmt: toAmountPicture(last.tranAmt),
        tranOrigTs: toMapDate(last.tranOrigTs),
        tranProcTs: toMapDate(last.tranProcTs),
        tranMerchantId: last.tranMerchantId,
        tranMerchantName: last.tranMerchantName,
        tranMerchantCity: last.tranMerchantCity,
        tranMerchantZip: last.tranMerchantZip,
      };
      setForm(copied);
      setErrorMessage('');
      setInfoMessage('');
      // ``PERFORM PROCESS-ENTER-KEY`` closes the paragraph, and its key edit has already
      // run, so what remains is the data-field pass and the confirmation gate: a copied
      // screen therefore comes to rest asking to confirm rather than looking finished.
      const failure = validateDataFields(copied);
      if (failure !== null) {
        publishFailure(failure);
        return;
      }
      confirmOrAdd(copied);
    })();
  }, [adding, confirmOrAdd, form, publishFailure, resolvingKeys, runCopyLast, runKeyFieldEdit]);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COTRN02C`` L148-152) — publish
   *     ``CCDA-MSG-INVALID-KEY`` and re-send the map, whose ``ATTRB=IC`` on ACTIDIN
   *     returns the cursor to the account-id field. Every typed value stands and no
   *     field is faulted: nothing the operator entered was rejected.
   */
  const handleUnhandledKey = useCallback((): void => {
    setInfoMessage('');
    setErrorMessage(CCDA_MSG_INVALID_KEY);
    setFaultedField(null);
    focusField('acctId');
  }, []);

  const activateEnter = useScreenAction(handleEnter);
  const activateExit = useScreenAction(handleExit);
  const activateClear = useScreenAction(handleClear);
  const activateCopyLast = useScreenAction(handleCopyLast);
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    // The 3270 keyboard is LOCKED from the moment an AID is transmitted until the next
    // map arrives, so while the add is outstanding the legend renders inactive and no
    // key is accepted -- the same treatment the sign-on and report screens carry.
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: ENTER_LABEL,
        onActivate: activateEnter,
        enabled: !adding,
      },
      {
        action: PfKeyAction.PF3,
        label: BACK_LABEL,
        onActivate: activateExit,
        enabled: !adding,
      },
      {
        action: PfKeyAction.PF4,
        label: CLEAR_LABEL,
        onActivate: activateClear,
        enabled: !adding,
      },
      {
        action: PfKeyAction.PF5,
        label: COPY_LAST_LABEL,
        onActivate: activateCopyLast,
        enabled: !adding,
      },
    ];
    setChrome({
      transactionId: 'CT02',
      programName: 'COTRN02C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      // ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COTRN02C`` L148-152): the keys the
      // legend does not advertise draw the refusal literal, not silence.
      onUnhandledKey: activateUnhandledKey,
      // The key-field edit is a lookup, so its round trip is part of the same
      // keyboard-locked interval the write occupies.
      busy: adding || resolvingKeys,
      // Only the write locks the function keys: it is the action whose completion the
      // operator must see, because it posts a transaction.
      locked: adding,
    });
  }, [
    activateClear,
    activateCopyLast,
    activateEnter,
    activateExit,
    activateUnhandledKey,
    adding,
    errorMessage,
    infoMessage,
    resolvingKeys,
    setChrome,
  ]);

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
          disabled={adding}
          id="acctId"
          name="acctId"
          type="text"
          inputMode="numeric"
          maxLength={11}
          size={11}
          {...invalidFieldProps(faultedField === 'acctId')}
          value={form.acctId}
          onChange={(event) => updateField('acctId', event.target.value)}
        />{' '}
        <span className="neutral">(or)</span>{' '}
        <label className="prompt" htmlFor="tranCardNum">
          Card #:
        </label>{NBSP}
        <input
          className="field"
          disabled={adding}
          id="tranCardNum"
          name="tranCardNum"
          type="text"
          inputMode="numeric"
          maxLength={16}
          size={16}
          {...invalidFieldProps(faultedField === 'tranCardNum')}
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
          disabled={adding}
          id="tranTypeCd"
          name="tranTypeCd"
          type="text"
          inputMode="numeric"
          maxLength={2}
          size={2}
          {...invalidFieldProps(faultedField === 'tranTypeCd')}
          value={form.tranTypeCd}
          onChange={(event) => updateField('tranTypeCd', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranCatCd">
          Category CD:
        </label>{NBSP}
        <input
          className="field"
          disabled={adding}
          id="tranCatCd"
          name="tranCatCd"
          type="text"
          inputMode="numeric"
          maxLength={4}
          size={4}
          {...invalidFieldProps(faultedField === 'tranCatCd')}
          value={form.tranCatCd}
          onChange={(event) => updateField('tranCatCd', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranSource">
          Source:
        </label>{NBSP}
        <input
          className="field"
          disabled={adding}
          id="tranSource"
          name="tranSource"
          type="text"
          maxLength={10}
          size={10}
          {...invalidFieldProps(faultedField === 'tranSource')}
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
          disabled={adding}
          id="tranDesc"
          name="tranDesc"
          type="text"
          maxLength={60}
          size={60}
          {...invalidFieldProps(faultedField === 'tranDesc')}
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
          disabled={adding}
            id="tranAmt"
            name="tranAmt"
            type="text"
            maxLength={12}
            size={12}
            {...invalidFieldProps(faultedField === 'tranAmt', 'tranAmtHint')}
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
          disabled={adding}
            id="tranOrigTs"
            name="tranOrigTs"
            type="text"
            maxLength={10}
            size={10}
            {...invalidFieldProps(faultedField === 'tranOrigTs', 'tranOrigTsHint')}
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
          disabled={adding}
            id="tranProcTs"
            name="tranProcTs"
            type="text"
            maxLength={10}
            size={10}
            {...invalidFieldProps(faultedField === 'tranProcTs', 'tranProcTsHint')}
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
          disabled={adding}
          id="tranMerchantId"
          name="tranMerchantId"
          type="text"
          inputMode="numeric"
          maxLength={9}
          size={9}
          {...invalidFieldProps(faultedField === 'tranMerchantId')}
          value={form.tranMerchantId}
          onChange={(event) => updateField('tranMerchantId', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranMerchantName">
          Merchant Name:
        </label>{NBSP}
        <input
          className="field"
          disabled={adding}
          id="tranMerchantName"
          name="tranMerchantName"
          type="text"
          maxLength={30}
          size={30}
          {...invalidFieldProps(faultedField === 'tranMerchantName')}
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
          disabled={adding}
          id="tranMerchantCity"
          name="tranMerchantCity"
          type="text"
          maxLength={25}
          size={25}
          {...invalidFieldProps(faultedField === 'tranMerchantCity')}
          value={form.tranMerchantCity}
          onChange={(event) => updateField('tranMerchantCity', event.target.value)}
        />{' '}
        <label className="prompt" htmlFor="tranMerchantZip">
          Merchant Zip:
        </label>{NBSP}
        <input
          className="field"
          disabled={adding}
          id="tranMerchantZip"
          name="tranMerchantZip"
          type="text"
          maxLength={10}
          size={10}
          {...invalidFieldProps(faultedField === 'tranMerchantZip')}
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
          disabled={adding}
          id="confirm"
          name="confirm"
          type="text"
          maxLength={1}
          size={1}
          {...invalidFieldProps(faultedField === 'confirm', 'confirmValues')}
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

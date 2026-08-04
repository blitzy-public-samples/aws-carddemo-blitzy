/**
 * AccountUpdatePage
 * =================
 *
 * :purpose: Editable account + customer maintenance screen with optimistic-lock
 *     conflict handling — the React replacement for BMS mapset
 *     ``app/bms/COACTUP.bms`` (CICS transaction ``CAUP``, program ``COACTUPC``).
 *     The screen loads the current record through ``GET /accounts/{id}``, keeps the
 *     ``version`` snapshot read at display time, edits the ``UNPROT`` fields of the
 *     mapset, and submits them through ``PUT /accounts/{id}``. An HTTP ``409``
 *     conflict is reported on the message line and the record is not overwritten.
 * :output: The rendered screen body — heading, ``Account Number :`` entry field and
 *     the editable account / customer fields. The header, the line-23 message
 *     region and the line-24 function-key bar are rendered by the shared ``Layout``
 *     shell from the chrome this page publishes through ``useScreenChrome``.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { fieldErrorClass, fieldMarker } from '../components/ErrorBanner';
import { PfKeyAction } from '../types';
import type {
  AccountViewResponseDto,
  AccountUpdateRequestDto,
  AccountUpdateResponseDto,
  ActiveStatus,
  FieldErrorMap,
} from '../types';
import { getAccount, updateAccount, ApiError } from '../api';
import { useApi } from '../hooks';

/* ------------------------------------------------------------------ */
/* Screen identity (BMS COACTUP / CICS CAUP / program COACTUPC)       */
/* ------------------------------------------------------------------ */

/** CICS transaction id of the account-update screen. */
const TRANSACTION_ID = 'CAUP';

/** COBOL program the screen replaces. */
const PROGRAM_NAME = 'COACTUPC';

/** First header title line. */
const TITLE01 = 'CardDemo';

/** Second header title line, matching the ``Update Account`` screen heading. */
const TITLE02 = 'Update Account';

/** NEUTRAL screen heading at BMS ``POS=(4,33)``. */
const HEADING = 'Update Account';

/** NEUTRAL customer-section heading at BMS ``POS=(11,32)``. */
const CUSTOMER_SECTION_HEADING = 'Customer Details';

/** Visible entry hint for the four date fields carried as ``YYYY-MM-DD`` strings. */
const DATE_HINT = '(YYYY-MM-DD)';

/* ------------------------------------------------------------------ */
/* Message literals (COACTUPC WS-INFO-MSG / WS-RETURN-MSG 88-levels)  */
/* ------------------------------------------------------------------ */

/** ``PROMPT-FOR-SEARCH-KEYS``. */
const MSG_PROMPT_FOR_SEARCH_KEYS = 'Enter or update id of account to update';

/** ``PROMPT-FOR-CHANGES``. */
const MSG_PROMPT_FOR_CHANGES = 'Update account details presented above.';

/** ``PROMPT-FOR-CONFIRMATION``. */
const MSG_PROMPT_FOR_CONFIRMATION = 'Changes validated.Press F5 to save';

/** ``CONFIRM-UPDATE-SUCCESS``. */
const MSG_UPDATE_SUCCESS = 'Changes committed to database';

/** ``INFORM-FAILURE``. */
const MSG_FAILURE = 'Changes unsuccessful. Please try again';

/** ``SEARCHED-ACCT-ZEROES`` / ``SEARCHED-ACCT-NOT-NUMERIC``. */
const MSG_ACCOUNT_ID_INVALID = 'Account number must be a non zero 11 digit number';

/** ``WS-PROMPT-FOR-ACCT``. */
const MSG_ACCOUNT_NOT_PROVIDED = 'Account number not provided';

/** ``DATA-WAS-CHANGED-BEFORE-UPDATE`` — surfaced on an HTTP ``409`` conflict. */
const MSG_OPTIMISTIC_LOCK_CONFLICT = 'Record changed by some one else. Please review';

/* ------------------------------------------------------------------ */
/* Composed edit-message suffixes (COACTUPC 1215-1265 edit routines)  */
/* ------------------------------------------------------------------ */

/** ``1215-EDIT-MANDATORY`` / ``1245-EDIT-NUM-REQD`` / ``1250-EDIT-SIGNED-9V2`` blank result. */
const SUFFIX_MUST_BE_SUPPLIED = ' must be supplied.';

/** ``1245-EDIT-NUM-REQD`` class-test result. */
const SUFFIX_MUST_BE_ALL_NUMERIC = ' must be all numeric.';

/** ``1220-EDIT-YESNO`` result. */
const SUFFIX_MUST_BE_YES_NO = ' must be Y or N.';

/** ``1250-EDIT-SIGNED-9V2`` / ``EDIT-DATE-CCYYMMDD`` invalid-value result. */
const SUFFIX_IS_NOT_VALID = ' is not valid';

/** ``EDIT-AREA-CODE`` class-test result. */
const SUFFIX_AREA_CODE_3_DIGIT = ': Area code must be A 3 digit number.';

/** ``EDIT-US-PHONE-LINENUM`` class-test result. */
const SUFFIX_LINE_4_DIGIT = ': Line number code must be A 4 digit number.';

/* ------------------------------------------------------------------ */
/* Field captions (COACTUPC WS-EDIT-VARIABLE-NAME values)             */
/* ------------------------------------------------------------------ */

const VAR_ACCOUNT_STATUS = 'Account Status';
const VAR_OPEN_DATE = 'Open Date';
const VAR_CREDIT_LIMIT = 'Credit Limit';
const VAR_EXPIRY_DATE = 'Expiry Date';
const VAR_CASH_CREDIT_LIMIT = 'Cash Credit Limit';
const VAR_REISSUE_DATE = 'Reissue Date';
const VAR_CURRENT_BALANCE = 'Current Balance';
const VAR_CURR_CYC_CREDIT = 'Current Cycle Credit Limit';
const VAR_CURR_CYC_DEBIT = 'Current Cycle Debit Limit';
const VAR_SSN = 'SSN';
const VAR_DATE_OF_BIRTH = 'Date of Birth';
const VAR_FICO_SCORE = 'FICO Score';
const VAR_FIRST_NAME = 'First Name';
const VAR_LAST_NAME = 'Last Name';
const VAR_ADDRESS_LINE_1 = 'Address Line 1';
const VAR_ADDRESS_LINE_2 = 'Address Line 2';
const VAR_STATE = 'State';
const VAR_ZIP = 'Zip';
const VAR_CITY = 'City';
const VAR_COUNTRY = 'Country';
const VAR_PHONE_NUMBER_1 = 'Phone Number 1';
const VAR_PHONE_NUMBER_2 = 'Phone Number 2';
const VAR_EFT_ACCOUNT_ID = 'EFT Account Id';
const VAR_PRIMARY_CARD_HOLDER = 'Primary Card Holder';

/* ------------------------------------------------------------------ */
/* Declared field widths (COACTUP.bms LENGTH / record PIC clauses)    */
/* ------------------------------------------------------------------ */

const ACCOUNT_ID_LENGTH = 11;
const STATUS_LENGTH = 1;
const DATE_LENGTH = 10;
const AMOUNT_LENGTH = 15;
const ACCOUNT_GROUP_LENGTH = 10;
const CUSTOMER_ID_LENGTH = 9;
const SSN_LENGTH = 9;
const FICO_LENGTH = 3;
const NAME_LENGTH = 25;
const ADDRESS_LENGTH = 50;
const STATE_LENGTH = 2;
const ZIP_LENGTH = 5;
const COUNTRY_LENGTH = 3;
const PHONE_LENGTH = 15;
const GOVT_ID_LENGTH = 20;
const EFT_ACCOUNT_LENGTH = 10;
const CARD_HOLDER_LENGTH = 1;

/** ``S9(09)V99`` picture accepted by ``1250-EDIT-SIGNED-9V2``. */
const AMOUNT_PATTERN = /^[+-]?\d{1,9}(\.\d{1,2})?$/;

/** ``YYYY-MM-DD`` shape accepted by ``EDIT-DATE-CCYYMMDD``. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** Digits-only class test used by the numeric edits. */
const DIGITS_PATTERN = /^\d+$/;

/* ------------------------------------------------------------------ */
/* Local shapes                                                       */
/* ------------------------------------------------------------------ */

/**
 * :purpose: Entry-field state of the ``COACTUP`` mapset. Every member is a
 *     ``string`` so monetary amounts keep their ``NUMERIC(p,s)`` scale, identifiers
 *     keep their zero-padded width, and dates keep their ``YYYY-MM-DD`` wire form.
 * :note: ``acctExpiraionDate`` retains the legacy copybook misspelling verbatim.
 */
interface AccountUpdateFormState {
  acctActiveStatus: string;
  acctOpenDate: string;
  acctCreditLimit: string;
  acctExpiraionDate: string;
  acctCashCreditLimit: string;
  acctReissueDate: string;
  acctCurrBal: string;
  acctCurrCycCredit: string;
  acctGroupId: string;
  acctCurrCycDebit: string;
  custId: string;
  custSsn: string;
  custDobYyyyMmDd: string;
  custFicoCreditScore: string;
  custFirstName: string;
  custMiddleName: string;
  custLastName: string;
  custAddrLine1: string;
  custAddrStateCd: string;
  custAddrLine2: string;
  custAddrZip: string;
  custAddrLine3: string;
  custAddrCountryCd: string;
  custPhoneNum1: string;
  custGovtIssuedId: string;
  custPhoneNum2: string;
  custEftAccountId: string;
  custPriCardHolderInd: string;
}

/** Empty entry state used before the account is read and when the read fails. */
const EMPTY_FORM: AccountUpdateFormState = {
  acctActiveStatus: '',
  acctOpenDate: '',
  acctCreditLimit: '',
  acctExpiraionDate: '',
  acctCashCreditLimit: '',
  acctReissueDate: '',
  acctCurrBal: '',
  acctCurrCycCredit: '',
  acctGroupId: '',
  acctCurrCycDebit: '',
  custId: '',
  custSsn: '',
  custDobYyyyMmDd: '',
  custFicoCreditScore: '',
  custFirstName: '',
  custMiddleName: '',
  custLastName: '',
  custAddrLine1: '',
  custAddrStateCd: '',
  custAddrLine2: '',
  custAddrZip: '',
  custAddrLine3: '',
  custAddrCountryCd: '',
  custPhoneNum1: '',
  custGovtIssuedId: '',
  custPhoneNum2: '',
  custEftAccountId: '',
  custPriCardHolderInd: '',
};

/**
 * :purpose: Outcome of one pass of the edit sequence.
 * :param message: the first failing edit's message, empty when every edit passed.
 * :param fieldErrors: highlight state of every field whose edit failed.
 */
interface ValidationResult {
  message: string;
  fieldErrors: FieldErrorMap;
}

/**
 * :purpose: Description of one labelled entry field rendered by the screen body.
 * :param field: the entry-state member the field is bound to.
 * :param label: visible TURQUOISE caption; omitted for the BMS fields that carry none.
 * :param ariaLabel: accessible name used when the mapset supplies no visible caption.
 * :param maxLength: declared field width, applied as both the entry limit and the
 *     rendered character width so the field occupies its 3270 column span.
 * :param hint: optional visible entry hint associated through ``aria-describedby``.
 */
interface EntryFieldOptions {
  field: keyof AccountUpdateFormState;
  label?: string;
  ariaLabel?: string;
  maxLength: number;
  hint?: string;
}

/**
 * :purpose: The four AID handlers of the screen, held in a ref so the function
 *     keys published into the shared shell stay identity-stable while always
 *     invoking the handler built from the current entry state.
 * :param handleProcess: ENTER.
 * :param handleExit: PF3.
 * :param handleSave: PF5.
 * :param handleCancel: PF12.
 */
interface ScreenHandlers {
  handleProcess: () => void;
  handleExit: () => void;
  handleSave: () => Promise<void>;
  handleCancel: () => void;
}

/* ------------------------------------------------------------------ */
/* Edit helpers                                                       */
/* ------------------------------------------------------------------ */

/**
 * :purpose: Render any wire value as displayable text, so an absent member never
 *     reaches the DOM as ``null`` or ``undefined``.
 * :param value: the wire value.
 * :returns: the value as a string, or an empty string when it is absent.
 */
function asText(value: unknown): string {
  return value === null || value === undefined ? '' : String(value);
}

/**
 * :purpose: Whether a value is absent for edit purposes (``EQUAL SPACES`` or
 *     ``LOW-VALUES`` in the COBOL edits).
 * :param value: the entry value.
 * :returns: ``true`` when the value holds no non-blank character.
 */
function isBlank(value: string): boolean {
  return value.trim().length === 0;
}

/**
 * :purpose: COBOL ``IS NUMERIC`` class test.
 * :param value: the entry value.
 * :returns: ``true`` when every character is a digit.
 */
function isAllDigits(value: string): boolean {
  return DIGITS_PATTERN.test(value);
}

/**
 * :purpose: Whether a value is a masked identifier echoed back from the response.
 *     Masked SSN, government id and EFT account id carry the ``*`` mask character
 *     and are left to the server, which keeps the stored identifier.
 * :param value: the entry value.
 * :returns: ``true`` when the value contains the mask character.
 */
function isMaskShaped(value: string): boolean {
  return value.includes('*');
}

/**
 * :purpose: Reproduce a COBOL ``MOVE`` into a fixed-width alphanumeric field: the
 *     value is right-padded with spaces to the declared width and truncated when
 *     longer, so the fixed-width slices the edits operate on always exist.
 * :param value: the entry value.
 * :param width: the declared field width.
 * :returns: the value padded or truncated to exactly ``width`` characters.
 */
function padToWidth(value: string, width: number): string {
  return value.length >= width ? value.slice(0, width) : value.padEnd(width, ' ');
}

/**
 * :purpose: ``2210-EDIT-ACCOUNT`` — the account key must be an 11-digit, non-zero
 *     number.
 * :param value: the ``ACCTSID`` entry value.
 * :returns: ``true`` when the value satisfies the edit.
 */
function isValidAccountNumber(value: string): boolean {
  const trimmed = value.trim();
  return (
    trimmed.length === ACCOUNT_ID_LENGTH && isAllDigits(trimmed) && Number(trimmed) !== 0
  );
}

/**
 * :purpose: ``EDIT-DATE-CCYYMMDD`` — strict calendar validation of a ``YYYY-MM-DD``
 *     value, the ``java.time`` ``ResolverStyle.STRICT`` equivalent of the legacy
 *     ``CSUTLDTC`` / ``CEEDAYS`` check.
 * :param value: the entry value.
 * :returns: ``true`` when the value is a real calendar date in ``YYYY-MM-DD`` form.
 */
function isCalendarDate(value: string): boolean {
  if (!DATE_PATTERN.test(value)) {
    return false;
  }
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(5, 7));
  const day = Number(value.slice(8, 10));
  if (month < 1 || month > 12 || day < 1 || day > 31) {
    return false;
  }
  const candidate = new Date(Date.UTC(year, month - 1, day));
  return (
    candidate.getUTCFullYear() === year &&
    candidate.getUTCMonth() === month - 1 &&
    candidate.getUTCDate() === day
  );
}

/**
 * :purpose: Recover the ``WS-EDIT-US-PHONE-NUMA`` / ``-NUMB`` / ``-NUMC`` screen
 *     fields from the single ``PIC X(15)`` value the record and the REST contract
 *     carry in the fixed-width ``(aaa)ppp-llll`` presentation.
 * :param value: the entry value.
 * :returns: the area code, prefix and line number, each trimmed.
 */
function splitPhone(value: string): {
  areaCode: string;
  prefix: string;
  lineNumber: string;
} {
  const padded = padToWidth(value, PHONE_LENGTH);
  return {
    areaCode: padded.slice(1, 4).trim(),
    prefix: padded.slice(5, 8).trim(),
    lineNumber: padded.slice(9, 13).trim(),
  };
}

/**
 * :purpose: Narrow an entry value to the ``ACCT-ACTIVE-STATUS`` wire alias. Only
 *     reached once ``1220-EDIT-YESNO`` has accepted the value.
 * :param value: the entry value.
 * :returns: ``'N'`` for an inactive account, otherwise ``'Y'``.
 */
function toActiveStatus(value: string): ActiveStatus {
  return value === 'N' ? 'N' : 'Y';
}

/**
 * :purpose: Seed the entry state from an account read (or from the refreshed record
 *     an update returns).
 * :param response: the account + customer payload.
 * :returns: the entry state for every field the mapset presents.
 */
function toFormState(response: AccountViewResponseDto): AccountUpdateFormState {
  return {
    acctActiveStatus: asText(response.acctActiveStatus),
    acctOpenDate: asText(response.acctOpenDate),
    acctCreditLimit: asText(response.acctCreditLimit),
    acctExpiraionDate: asText(response.acctExpiraionDate),
    acctCashCreditLimit: asText(response.acctCashCreditLimit),
    acctReissueDate: asText(response.acctReissueDate),
    acctCurrBal: asText(response.acctCurrBal),
    acctCurrCycCredit: asText(response.acctCurrCycCredit),
    acctGroupId: asText(response.acctGroupId),
    acctCurrCycDebit: asText(response.acctCurrCycDebit),
    custId: asText(response.custId),
    custSsn: asText(response.custSsn),
    custDobYyyyMmDd: asText(response.custDobYyyyMmDd),
    custFicoCreditScore: asText(response.custFicoCreditScore),
    custFirstName: asText(response.custFirstName),
    custMiddleName: asText(response.custMiddleName),
    custLastName: asText(response.custLastName),
    custAddrLine1: asText(response.custAddrLine1),
    custAddrStateCd: asText(response.custAddrStateCd),
    custAddrLine2: asText(response.custAddrLine2),
    custAddrZip: asText(response.custAddrZip),
    custAddrLine3: asText(response.custAddrLine3),
    custAddrCountryCd: asText(response.custAddrCountryCd),
    custPhoneNum1: asText(response.custPhoneNum1),
    custGovtIssuedId: asText(response.custGovtIssuedId),
    custPhoneNum2: asText(response.custPhoneNum2),
    custEftAccountId: asText(response.custEftAccountId),
    custPriCardHolderInd: asText(response.custPriCardHolderInd),
  };
}

/**
 * :purpose: Build the ``PUT /accounts/{id}`` body from the entry state and the
 *     optimistic-lock ``version`` snapshot read at display time. ``custId`` is not
 *     carried: identity is derived server-side from the request path.
 * :param form: the current entry state.
 * :param version: the ``version`` snapshot the screen was displayed with.
 * :returns: the update request body.
 */
function toRequestDto(
  form: AccountUpdateFormState,
  version: number,
): AccountUpdateRequestDto {
  return {
    version,
    acctActiveStatus: toActiveStatus(form.acctActiveStatus),
    acctCurrBal: form.acctCurrBal,
    acctCreditLimit: form.acctCreditLimit,
    acctCashCreditLimit: form.acctCashCreditLimit,
    acctOpenDate: form.acctOpenDate,
    acctExpiraionDate: form.acctExpiraionDate,
    acctReissueDate: form.acctReissueDate,
    acctCurrCycCredit: form.acctCurrCycCredit,
    acctCurrCycDebit: form.acctCurrCycDebit,
    acctGroupId: form.acctGroupId,
    custFirstName: form.custFirstName,
    custMiddleName: form.custMiddleName,
    custLastName: form.custLastName,
    custAddrLine1: form.custAddrLine1,
    custAddrLine2: form.custAddrLine2,
    custAddrLine3: form.custAddrLine3,
    custAddrStateCd: form.custAddrStateCd,
    custAddrCountryCd: form.custAddrCountryCd,
    custAddrZip: form.custAddrZip,
    custPhoneNum1: form.custPhoneNum1,
    custPhoneNum2: form.custPhoneNum2,
    custSsn: form.custSsn,
    custGovtIssuedId: form.custGovtIssuedId,
    custDobYyyyMmDd: form.custDobYyyyMmDd,
    custEftAccountId: form.custEftAccountId,
    custPriCardHolderInd: form.custPriCardHolderInd,
    custFicoCreditScore: Number(form.custFicoCreditScore),
  };
}

/**
 * :purpose: Reproduce the ``COACTUPC`` ``1200-EDIT-MAP-INPUTS`` edit sequence over
 *     the fields the screen presents, in the legacy ``PERFORM`` order. Every edit
 *     runs so each failing field is highlighted, while only the first failure sets
 *     the message — the behaviour of the ``IF WS-RETURN-MSG-OFF`` guard.
 * :param accountNumber: the ``ACCTSID`` entry value.
 * :param form: the current entry state.
 * :returns: the first failing message and the highlight state of every failed field.
 */
function validateAccountUpdate(
  accountNumber: string,
  form: AccountUpdateFormState,
): ValidationResult {
  const fieldErrors: FieldErrorMap = {};
  let message = '';

  const fail = (field: string, blank: boolean, failure: string): void => {
    fieldErrors[field] = { invalid: !blank, blank };
    if (message === '') {
      message = failure;
    }
  };

  const editYesNo = (field: keyof AccountUpdateFormState, caption: string): void => {
    const value = form[field];
    if (isBlank(value)) {
      fail(field, true, caption + SUFFIX_MUST_BE_SUPPLIED);
      return;
    }
    if (value !== 'Y' && value !== 'N') {
      fail(field, false, caption + SUFFIX_MUST_BE_YES_NO);
    }
  };

  const editDate = (field: keyof AccountUpdateFormState, caption: string): void => {
    const value = form[field];
    if (isBlank(value)) {
      fail(field, true, caption + SUFFIX_MUST_BE_SUPPLIED);
      return;
    }
    if (!isCalendarDate(value.trim())) {
      fail(field, false, caption + SUFFIX_IS_NOT_VALID);
    }
  };

  const editAmount = (field: keyof AccountUpdateFormState, caption: string): void => {
    const value = form[field];
    if (isBlank(value)) {
      fail(field, true, caption + SUFFIX_MUST_BE_SUPPLIED);
      return;
    }
    if (!AMOUNT_PATTERN.test(value.trim())) {
      fail(field, false, caption + SUFFIX_IS_NOT_VALID);
    }
  };

  const editNumeric = (
    field: keyof AccountUpdateFormState,
    caption: string,
    editLength: number,
  ): void => {
    const value = form[field];
    if (isMaskShaped(value)) {
      return;
    }
    if (isBlank(value)) {
      fail(field, true, caption + SUFFIX_MUST_BE_SUPPLIED);
      return;
    }
    if (!isAllDigits(padToWidth(value.trim(), editLength))) {
      fail(field, false, caption + SUFFIX_MUST_BE_ALL_NUMERIC);
    }
  };

  const editRequired = (field: keyof AccountUpdateFormState, caption: string): void => {
    if (isBlank(form[field])) {
      fail(field, true, caption + SUFFIX_MUST_BE_SUPPLIED);
    }
  };

  const editPhone = (field: keyof AccountUpdateFormState, caption: string): void => {
    const { areaCode, prefix, lineNumber } = splitPhone(form[field]);
    if (areaCode === '' && prefix === '' && lineNumber === '') {
      return;
    }
    if (areaCode.length !== 3 || !isAllDigits(areaCode)) {
      fail(field, areaCode === '', caption + SUFFIX_AREA_CODE_3_DIGIT);
      return;
    }
    if (lineNumber.length !== 4 || !isAllDigits(lineNumber)) {
      fail(field, lineNumber === '', caption + SUFFIX_LINE_4_DIGIT);
    }
  };

  if (!isValidAccountNumber(accountNumber)) {
    fail('acctsid', isBlank(accountNumber), MSG_ACCOUNT_ID_INVALID);
  }
  editYesNo('acctActiveStatus', VAR_ACCOUNT_STATUS);
  editDate('acctOpenDate', VAR_OPEN_DATE);
  editAmount('acctCreditLimit', VAR_CREDIT_LIMIT);
  editDate('acctExpiraionDate', VAR_EXPIRY_DATE);
  editAmount('acctCashCreditLimit', VAR_CASH_CREDIT_LIMIT);
  editDate('acctReissueDate', VAR_REISSUE_DATE);
  editAmount('acctCurrBal', VAR_CURRENT_BALANCE);
  editAmount('acctCurrCycCredit', VAR_CURR_CYC_CREDIT);
  editAmount('acctCurrCycDebit', VAR_CURR_CYC_DEBIT);
  editNumeric('custSsn', VAR_SSN, SSN_LENGTH);
  editDate('custDobYyyyMmDd', VAR_DATE_OF_BIRTH);
  editNumeric('custFicoCreditScore', VAR_FICO_SCORE, FICO_LENGTH);
  editRequired('custFirstName', VAR_FIRST_NAME);
  editRequired('custLastName', VAR_LAST_NAME);
  editRequired('custAddrLine1', VAR_ADDRESS_LINE_1);
  editRequired('custAddrStateCd', VAR_STATE);
  editNumeric('custAddrZip', VAR_ZIP, ZIP_LENGTH);
  editRequired('custAddrLine3', VAR_CITY);
  editRequired('custAddrCountryCd', VAR_COUNTRY);
  editPhone('custPhoneNum1', VAR_PHONE_NUMBER_1);
  editPhone('custPhoneNum2', VAR_PHONE_NUMBER_2);
  editNumeric('custEftAccountId', VAR_EFT_ACCOUNT_ID, EFT_ACCOUNT_LENGTH);
  editYesNo('custPriCardHolderInd', VAR_PRIMARY_CARD_HOLDER);

  return { message, fieldErrors };
}


/* ------------------------------------------------------------------ */
/* Screen                                                             */
/* ------------------------------------------------------------------ */

/**
 * :purpose: The account-update screen. Reads the account identified by the
 *     ``accountId`` route parameter, keeps the ``version`` snapshot the read
 *     returned, edits the account and customer fields of the ``COACTUP`` mapset,
 *     and commits them in one transactional unit. A concurrent modification is
 *     reported as ``Record changed by some one else. Please review`` and nothing is
 *     overwritten.
 * :returns: The rendered screen body.
 */
export default function AccountUpdatePage(): ReactElement {
  const { accountId } = useParams();
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  const routeAccountId = accountId ?? '';

  const [accountNumber, setAccountNumber] = useState<string>(routeAccountId);
  const [form, setForm] = useState<AccountUpdateFormState>(EMPTY_FORM);
  const [version, setVersion] = useState<number | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrorMap>({});
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [infoMessage, setInfoMessage] = useState<string>(MSG_PROMPT_FOR_SEARCH_KEYS);
  const [saving, setSaving] = useState<boolean>(false);

  const { run: runGetAccount, error: loadError, loading } = useApi(getAccount);

  /**
   * :purpose: Read the account and seed the entry state together with the
   *     optimistic-lock ``version`` snapshot the update will echo back.
   * :param identifier: the 11-digit account key.
   */
  const loadAccount = useCallback(
    async (identifier: string): Promise<void> => {
      const loaded = await runGetAccount(identifier);
      if (loaded === undefined) {
        return;
      }
      setForm(toFormState(loaded));
      setVersion(loaded.version);
      setFieldErrors({});
      setErrorMessage('');
      setInfoMessage(MSG_PROMPT_FOR_CHANGES);
    },
    [runGetAccount],
  );

  // Keep the ACCTSID entry field in step with the route key.
  useEffect(() => {
    setAccountNumber(routeAccountId);
  }, [routeAccountId]);

  // Fetch the current values before any editing takes place.
  useEffect(() => {
    if (!isValidAccountNumber(routeAccountId)) {
      setForm(EMPTY_FORM);
      setVersion(null);
      setFieldErrors(
        routeAccountId === '' ? {} : { acctsid: { invalid: true, blank: false } },
      );
      setErrorMessage(routeAccountId === '' ? '' : MSG_ACCOUNT_ID_INVALID);
      setInfoMessage(MSG_PROMPT_FOR_SEARCH_KEYS);
      return;
    }
    setFieldErrors({});
    setErrorMessage('');
    setInfoMessage(MSG_PROMPT_FOR_SEARCH_KEYS);
    void loadAccount(routeAccountId);
  }, [routeAccountId, loadAccount]);

  // Surface a failed read on the message line.
  useEffect(() => {
    if (loadError !== null) {
      setForm(EMPTY_FORM);
      setVersion(null);
      setErrorMessage(loadError.body?.message ?? loadError.message);
      setInfoMessage('');
    }
  }, [loadError]);

  /**
   * :purpose: Record an entry-field change.
   * :param field: the entry-state member being edited.
   * :param value: the new value.
   */
  const handleFieldChange = useCallback(
    (field: keyof AccountUpdateFormState, value: string): void => {
      setForm((previous) => ({ ...previous, [field]: value }));
    },
    [],
  );

  /**
   * :purpose: ENTER — process the screen. A changed account key re-reads that
   *     account; otherwise the edit sequence runs and a clean pass invites the save.
   */
  const handleProcess = useCallback((): void => {
    const requested = accountNumber.trim();
    if (!isValidAccountNumber(requested)) {
      setFieldErrors({ acctsid: { invalid: !isBlank(requested), blank: isBlank(requested) } });
      setErrorMessage(MSG_ACCOUNT_ID_INVALID);
      setInfoMessage('');
      return;
    }
    if (requested !== routeAccountId) {
      navigate(`/accounts/${requested}/update`);
      return;
    }
    if (version === null) {
      void loadAccount(requested);
      return;
    }
    const result = validateAccountUpdate(requested, form);
    setFieldErrors(result.fieldErrors);
    if (result.message !== '') {
      setErrorMessage(result.message);
      setInfoMessage('');
      return;
    }
    setErrorMessage('');
    setInfoMessage(MSG_PROMPT_FOR_CONFIRMATION);
  }, [accountNumber, form, loadAccount, navigate, routeAccountId, version]);

  /**
   * :purpose: F5 — commit the edited account and customer fields, carrying the
   *     ``version`` snapshot read at display time. An HTTP ``409`` conflict reports
   *     the legacy ``DATA-WAS-CHANGED-BEFORE-UPDATE`` message and leaves the record
   *     and the entered values untouched, so the user reviews before retrying.
   */
  const handleSave = useCallback(async (): Promise<void> => {
    if (saving) {
      return;
    }
    const requested = accountNumber.trim();
    if (!isValidAccountNumber(requested)) {
      setFieldErrors({ acctsid: { invalid: !isBlank(requested), blank: isBlank(requested) } });
      setErrorMessage(MSG_ACCOUNT_ID_INVALID);
      setInfoMessage('');
      return;
    }
    if (version === null) {
      setErrorMessage(MSG_ACCOUNT_NOT_PROVIDED);
      setInfoMessage('');
      return;
    }
    const result = validateAccountUpdate(requested, form);
    setFieldErrors(result.fieldErrors);
    if (result.message !== '') {
      setErrorMessage(result.message);
      setInfoMessage('');
      return;
    }
    setErrorMessage('');
    setSaving(true);
    try {
      const updated: AccountUpdateResponseDto = await updateAccount(
        requested,
        toRequestDto(form, version),
      );
      setForm(toFormState(updated));
      setVersion(updated.version);
      setFieldErrors({});
      setErrorMessage('');
      setInfoMessage(MSG_UPDATE_SUCCESS);
    } catch (caught) {
      setInfoMessage('');
      if (caught instanceof ApiError) {
        setErrorMessage(
          caught.isOptimisticLockConflict
            ? MSG_OPTIMISTIC_LOCK_CONFLICT
            : caught.body?.message ?? caught.message,
        );
      } else if (caught instanceof Error && caught.message !== '') {
        setErrorMessage(caught.message);
      } else {
        setErrorMessage(MSG_FAILURE);
      }
    } finally {
      setSaving(false);
    }
  }, [accountNumber, form, saving, version]);

  /** :purpose: F3 — leave the screen for the calling menu. */
  const handleExit = useCallback((): void => {
    navigate('/menu');
  }, [navigate]);

  /** :purpose: F12 — abandon the edits and return to the account view. */
  const handleCancel = useCallback((): void => {
    navigate(routeAccountId === '' ? '/menu' : `/accounts/${routeAccountId}`);
  }, [navigate, routeAccountId]);

  // The published function keys dispatch through this ref, so a key activated at
  // any time acts on the current entry state and version snapshot.
  const handlersRef = useRef<ScreenHandlers>({
    handleProcess,
    handleExit,
    handleSave,
    handleCancel,
  });

  useEffect(() => {
    handlersRef.current = { handleProcess, handleExit, handleSave, handleCancel };
  }, [handleProcess, handleExit, handleSave, handleCancel]);

  const activateProcess = useCallback((): void => {
    handlersRef.current.handleProcess();
  }, []);

  const activateExit = useCallback((): void => {
    handlersRef.current.handleExit();
  }, []);

  const activateSave = useCallback((): void => {
    void handlersRef.current.handleSave();
  }, []);

  const activateCancel = useCallback((): void => {
    handlersRef.current.handleCancel();
  }, []);

  // Publish the screen chrome; Layout renders the header, message line and key bar.
  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Process', onActivate: activateProcess },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: activateExit },
      { action: PfKeyAction.PF5, label: 'F5=Save', onActivate: activateSave },
      { action: PfKeyAction.PF12, label: 'F12=Cancel', onActivate: activateCancel },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: TITLE01,
      title02: TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
    });
  }, [
    setChrome,
    errorMessage,
    infoMessage,
    activateProcess,
    activateExit,
    activateSave,
    activateCancel,
  ]);

  /**
   * :purpose: Render one labelled entry field of the mapset, applying the
   *     ``CSSETATY`` re-entry highlighting: an invalid or blank field renders RED and
   *     a blank required field carries a leading ``*`` marker.
   * :param options: the field description.
   * :returns: The rendered caption, entry field and optional hint.
   */
  function entryField({
    field,
    label,
    ariaLabel,
    maxLength,
    hint,
  }: EntryFieldOptions): ReactElement {
    const state = fieldErrors[field];
    const errorClass = fieldErrorClass(state);
    const marker = fieldMarker(state);
    const hintId = hint === undefined ? undefined : `${field}Hint`;
    return (
      <span className="accountUpdate__field">
        {marker !== '' && (
          <span className="fieldError" aria-hidden="true">
            {marker}
          </span>
        )}
        {label !== undefined && (
          <label className="prompt" htmlFor={field}>
            {label}
          </label>
        )}{' '}
        <input
          id={field}
          name={field}
          type="text"
          className={errorClass === '' ? 'field' : `field ${errorClass}`}
          maxLength={maxLength}
          size={maxLength}
          value={form[field]}
          aria-label={ariaLabel}
          aria-describedby={hintId}
          aria-invalid={errorClass === '' ? undefined : true}
          onChange={(event) => {
            handleFieldChange(field, event.target.value);
          }}
        />
        {hint !== undefined && (
          <span className="neutral" id={hintId}>
            {' '}
            {hint}
          </span>
        )}
      </span>
    );
  }

  const accountNumberState = fieldErrors.acctsid;
  const accountNumberErrorClass = fieldErrorClass(accountNumberState);
  const accountNumberMarker = fieldMarker(accountNumberState);

  return (
    <section className="accountUpdate" aria-labelledby="accountUpdateHeading">
      <h3 id="accountUpdateHeading" className="neutral">
        {HEADING}
      </h3>

      <div className="accountUpdate__row">
        <span className="accountUpdate__field">
          {accountNumberMarker !== '' && (
            <span className="fieldError" aria-hidden="true">
              {accountNumberMarker}
            </span>
          )}
          <label className="prompt" htmlFor="acctsid">
            Account Number :
          </label>{' '}
          <input
            id="acctsid"
            name="acctsid"
            type="text"
            className={
              accountNumberErrorClass === '' ? 'field' : `field ${accountNumberErrorClass}`
            }
            maxLength={ACCOUNT_ID_LENGTH}
            size={ACCOUNT_ID_LENGTH}
            value={accountNumber}
            aria-invalid={accountNumberErrorClass === '' ? undefined : true}
            onChange={(event) => {
              setAccountNumber(event.target.value);
            }}
          />
        </span>{' '}
        {entryField({
          field: 'acctActiveStatus',
          label: 'Active Y/N:',
          maxLength: STATUS_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'acctOpenDate',
          label: 'Opened :',
          maxLength: DATE_LENGTH,
          hint: DATE_HINT,
        })}{' '}
        {entryField({
          field: 'acctCreditLimit',
          label: 'Credit Limit        :',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'acctExpiraionDate',
          label: 'Expiry :',
          maxLength: DATE_LENGTH,
          hint: DATE_HINT,
        })}{' '}
        {entryField({
          field: 'acctCashCreditLimit',
          label: 'Cash credit Limit   :',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'acctReissueDate',
          label: 'Reissue:',
          maxLength: DATE_LENGTH,
          hint: DATE_HINT,
        })}{' '}
        {entryField({
          field: 'acctCurrBal',
          label: 'Current Balance     :',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'acctCurrCycCredit',
          label: 'Current Cycle Credit:',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'acctGroupId',
          label: 'Account Group:',
          maxLength: ACCOUNT_GROUP_LENGTH,
        })}{' '}
        {entryField({
          field: 'acctCurrCycDebit',
          label: 'Current Cycle Debit :',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <h4 className="neutral">{CUSTOMER_SECTION_HEADING}</h4>

      <div className="accountUpdate__row">
        {entryField({
          field: 'custId',
          label: 'Customer id  :',
          maxLength: CUSTOMER_ID_LENGTH,
        })}{' '}
        {entryField({ field: 'custSsn', label: 'SSN:', maxLength: SSN_LENGTH })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'custDobYyyyMmDd',
          label: 'Date of birth:',
          maxLength: DATE_LENGTH,
          hint: DATE_HINT,
        })}{' '}
        {entryField({ field: 'custFicoCreditScore', label: 'FICO Score:', maxLength: FICO_LENGTH })}
      </div>

      <div className="accountUpdate__row">
        {entryField({ field: 'custFirstName', label: 'First Name', maxLength: NAME_LENGTH })}{' '}
        {entryField({ field: 'custMiddleName', label: 'Middle Name:', maxLength: NAME_LENGTH })}{' '}
        {entryField({ field: 'custLastName', label: 'Last Name :', maxLength: NAME_LENGTH })}
      </div>

      <div className="accountUpdate__row">
        {entryField({ field: 'custAddrLine1', label: 'Address:', maxLength: ADDRESS_LENGTH })}{' '}
        {entryField({ field: 'custAddrStateCd', label: 'State', maxLength: STATE_LENGTH })}
      </div>

      <div className="accountUpdate__row">
        {entryField({
          field: 'custAddrLine2',
          ariaLabel: VAR_ADDRESS_LINE_2,
          maxLength: ADDRESS_LENGTH,
        })}{' '}
        {entryField({ field: 'custAddrZip', label: 'Zip', maxLength: ZIP_LENGTH })}
      </div>

      <div className="accountUpdate__row">
        {entryField({ field: 'custAddrLine3', label: 'City', maxLength: ADDRESS_LENGTH })}{' '}
        {entryField({ field: 'custAddrCountryCd', label: 'Country', maxLength: COUNTRY_LENGTH })}
      </div>

      <div className="accountUpdate__row">
        {entryField({ field: 'custPhoneNum1', label: 'Phone 1:', maxLength: PHONE_LENGTH })}{' '}
        {entryField({
          field: 'custGovtIssuedId',
          label: 'Government Issued Id Ref    :',
          maxLength: GOVT_ID_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {entryField({ field: 'custPhoneNum2', label: 'Phone 2:', maxLength: PHONE_LENGTH })}{' '}
        {entryField({
          field: 'custEftAccountId',
          label: 'EFT Account Id:',
          maxLength: EFT_ACCOUNT_LENGTH,
        })}{' '}
        {entryField({
          field: 'custPriCardHolderInd',
          label: 'Primary Card Holder Y/N:',
          maxLength: CARD_HOLDER_LENGTH,
        })}
      </div>

      <div className="accountUpdate__actions">
        <button
          type="button"
          className="accountUpdate__save"
          disabled={loading || saving}
          onClick={() => {
            void handleSave();
          }}
        >
          Save
        </button>
      </div>
    </section>
  );
}


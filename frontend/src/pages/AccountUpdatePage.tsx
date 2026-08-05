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
import { useNavigate, useParams } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  ERROR_LINE_ID,
  fieldErrorClass,
  fieldMarker,
  isFieldInError,
} from '../components/ErrorBanner';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type {
  AccountViewResponseDto,
  AccountUpdateRequestDto,
  AccountUpdateResponseDto,
  ActiveStatus,
  FieldErrorMap,
} from '../types';
import { getAccount, updateAccount, ApiError } from '../api';
import { useApi, useFocusOnChange, useInitialFocus } from '../hooks';
import { isBlank, SSN_MASK_PREFIX } from '../components/display';

/* ------------------------------------------------------------------ */
/* Screen identity (BMS COACTUP / CICS CAUP / program COACTUPC)       */
/* ------------------------------------------------------------------ */

/** CICS transaction id of the account-update screen. */
const TRANSACTION_ID = 'CAUP';

/** COBOL program the screen replaces. */
const PROGRAM_NAME = 'COACTUPC';

/** NEUTRAL screen heading at BMS ``POS=(4,33)``. */
const HEADING = 'Update Account';

/** NEUTRAL customer-section heading at BMS ``POS=(11,32)``. */
const CUSTOMER_SECTION_HEADING = 'Customer Details';

/* ------------------------------------------------------------------ */
/* Message literals (COACTUPC WS-INFO-MSG / WS-RETURN-MSG 88-levels)  */
/* ------------------------------------------------------------------ */

/** ``PROMPT-FOR-SEARCH-KEYS``. */
const MSG_PROMPT_FOR_SEARCH_KEYS = 'Enter or update id of account to update';

/** ENTER half of the BMS ``FKEYS`` legend ``ENTER=Process F3=Exit``. */
const PF_PROCESS_LABEL = 'ENTER=Process';

/** PF3 half of the BMS ``FKEYS`` legend ``ENTER=Process F3=Exit``. */
const PF_EXIT_LABEL = 'F3=Exit';

/** BMS ``FKEY05`` legend, declared ``ATTRB=(ASKIP,DRK)``. */
const PF_SAVE_LABEL = 'F5=Save';

/** BMS ``FKEY12`` legend, declared ``ATTRB=(ASKIP,DRK)``. */
const PF_CANCEL_LABEL = 'F12=Cancel';

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

/** ``COACTUPC`` ``NO-CHANGES-DETECTED``. */
const MSG_NO_CHANGES_DETECTED = 'No change detected with respect to values fetched.';

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

/** ``OPNYEAR`` / ``EXPYEAR`` / ``RISYEAR`` / ``DOBYEAR`` declared width. */
const DATE_YEAR_LENGTH = 4;

/** ``OPNMON`` / ``OPNDAY`` and the other date-part fields' declared width. */
const DATE_PART_LENGTH = 2;

/** ``ACTSSN1`` declared width. */
const SSN_AREA_LENGTH = 3;

/** ``ACTSSN2`` declared width. */
const SSN_GROUP_LENGTH = 2;

/** ``ACTSSN3`` declared width. */
const SSN_SERIAL_LENGTH = 4;

/** ``ACSPH1A`` / ``ACSPH2A`` declared width. */
const PHONE_AREA_LENGTH = 3;

/** ``ACSPH1B`` / ``ACSPH2B`` declared width. */
const PHONE_PREFIX_LENGTH = 3;

/** ``ACSPH1C`` / ``ACSPH2C`` declared width. */
const PHONE_LINE_LENGTH = 4;
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

/**
 * ``S9(09)V99`` picture accepted by ``1250-EDIT-SIGNED-9V2``.
 *
 * Both quantifiers are bounded, so the expression runs in linear time and the
 * `detect-unsafe-regex` heuristic misreads the nested group; the picture is a frozen
 * contract and is reproduced digit-for-digit.
 */
// eslint-disable-next-line security/detect-unsafe-regex
const AMOUNT_PATTERN = /^[+-]?\d{1,10}(\.\d{1,2})?$/;

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
  opnYear: string;
  opnMon: string;
  opnDay: string;
  acctCreditLimit: string;
  expYear: string;
  expMon: string;
  expDay: string;
  acctCashCreditLimit: string;
  risYear: string;
  risMon: string;
  risDay: string;
  acctCurrBal: string;
  acctCurrCycCredit: string;
  acctGroupId: string;
  acctCurrCycDebit: string;
  custId: string;
  actSsn1: string;
  actSsn2: string;
  actSsn3: string;
  dobYear: string;
  dobMon: string;
  dobDay: string;
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
  acsPh1A: string;
  acsPh1B: string;
  acsPh1C: string;
  custGovtIssuedId: string;
  acsPh2A: string;
  acsPh2B: string;
  acsPh2C: string;
  custEftAccountId: string;
  custPriCardHolderInd: string;
}

/** Empty entry state used before the account is read and when the read fails. */
const EMPTY_FORM: AccountUpdateFormState = {
  acctActiveStatus: '',
  opnYear: '',
  opnMon: '',
  opnDay: '',
  acctCreditLimit: '',
  expYear: '',
  expMon: '',
  expDay: '',
  acctCashCreditLimit: '',
  risYear: '',
  risMon: '',
  risDay: '',
  acctCurrBal: '',
  acctCurrCycCredit: '',
  acctGroupId: '',
  acctCurrCycDebit: '',
  custId: '',
  actSsn1: '',
  actSsn2: '',
  actSsn3: '',
  dobYear: '',
  dobMon: '',
  dobDay: '',
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
  acsPh1A: '',
  acsPh1B: '',
  acsPh1C: '',
  custGovtIssuedId: '',
  acsPh2A: '',
  acsPh2B: '',
  acsPh2C: '',
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
 * :purpose: One segment of a field the mapset splits across several 3270 fields.
 * :param field: the entry-state member the segment is bound to.
 * :param ariaLabel: accessible name; the mapset gives the segments no visible caption.
 * :param maxLength: the segment's declared width.
 */
interface SegmentOptions {
  field: keyof AccountUpdateFormState;
  ariaLabel: string;
  maxLength: number;
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
/**
 * :purpose: Every entry field of the ``COACTUP`` mapset in screen order — row by row,
 *     left to right — which is the order the source walks when it decides where to
 *     leave the cursor (``3300-SETUP-SCREEN-ATTRS``).
 */
const SCREEN_FIELD_ORDER: readonly string[] = [
  'acctsid',
  'acctActiveStatus',
  'opnYear',
  'opnMon',
  'opnDay',
  'acctCreditLimit',
  'expYear',
  'expMon',
  'expDay',
  'acctCashCreditLimit',
  'risYear',
  'risMon',
  'risDay',
  'acctCurrBal',
  'acctCurrCycCredit',
  'acctGroupId',
  'acctCurrCycDebit',
  'custId',
  'actSsn1',
  'actSsn2',
  'actSsn3',
  'dobYear',
  'dobMon',
  'dobDay',
  'custFicoCreditScore',
  'custFirstName',
  'custMiddleName',
  'custLastName',
  'custAddrLine1',
  'custAddrStateCd',
  'custAddrLine2',
  'custAddrZip',
  'custAddrLine3',
  'custAddrCountryCd',
  'acsPh1A',
  'acsPh1B',
  'acsPh1C',
  'custGovtIssuedId',
  'acsPh2A',
  'acsPh2B',
  'acsPh2C',
  'custEftAccountId',
  'custPriCardHolderInd',
];

/**
 * :purpose: The ``COACTUPC`` screen states, named after the ``ACUP-*`` condition
 *     names that drive its AID validity gate, its information message and the
 *     visibility of the ``F5`` and ``F12`` legends.
 */
type ScreenState =
  | 'DETAILS_NOT_FETCHED'
  | 'SHOW_DETAILS'
  | 'CHANGES_NOT_OK'
  | 'CHANGES_OK_NOT_CONFIRMED'
  | 'CHANGES_OKAYED_AND_DONE'
  | 'CHANGES_FAILED';

/**
 * :purpose: Entry-state members the source compares case-insensitively after
 *     trimming (``FUNCTION UPPER-CASE (FUNCTION TRIM (...))``) in
 *     ``1205-COMPARE-OLD-NEW``. Every other member is compared on its trimmed
 *     character value.
 */
const CASE_INSENSITIVE_FIELDS: ReadonlySet<keyof AccountUpdateFormState> = new Set([
  'acctActiveStatus',
  'acctGroupId',
  'custFirstName',
  'custMiddleName',
  'custLastName',
  'custAddrLine1',
  'custAddrLine2',
  'custAddrLine3',
  'custAddrStateCd',
  'custAddrCountryCd',
  'custGovtIssuedId',
  'custPriCardHolderInd',
]);

/**
 * :purpose: ``1205-COMPARE-OLD-NEW`` — decide whether the entry state still matches
 *     the values the read returned.
 * :param current: the current entry state.
 * :param original: the entry state seeded by the last successful read.
 * :returns: ``true`` when at least one field differs, the source's
 *     ``CHANGE-HAS-OCCURRED``; ``false`` is its ``NO-CHANGES-DETECTED``.
 */
function hasChanges(
  current: AccountUpdateFormState,
  original: AccountUpdateFormState,
): boolean {
  return (Object.keys(current) as Array<keyof AccountUpdateFormState>).some((field) => {
    const left = current[field].trim();
    const right = original[field].trim();
    return CASE_INSENSITIVE_FIELDS.has(field)
      ? left.toUpperCase() !== right.toUpperCase()
      : left !== right;
  });
}

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
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return '';
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
 * :purpose: Left-justify a segment in its declared 3270 field width, the effect of a
 *     COBOL ``MOVE`` into a fixed-width sub-field that a following ``STRING ...
 *     DELIMITED BY SIZE`` then copies in full.
 * :param value: the segment entry value.
 * :param width: the declared field width.
 * :returns: the value truncated or space-padded to exactly ``width`` characters.
 */
function toFieldWidth(value: string, width: number): string {
  return value.length >= width ? value.slice(0, width) : value.padEnd(width, ' ');
}

/**
 * :purpose: Split a ``YYYY-MM-DD`` record value into the year, month and day fields
 *     the mapset presents, at the substring positions ``(1:4)``, ``(6:2)`` and
 *     ``(9:2)`` the source uses.
 * :param value: the record value.
 * :returns: the year, month and day segments.
 */
function splitDate(value: string): { year: string; mon: string; day: string } {
  const padded = padToWidth(value, DATE_LENGTH);
  return {
    year: padded.slice(0, 4).trim(),
    mon: padded.slice(5, 7).trim(),
    day: padded.slice(8, 10).trim(),
  };
}

/**
 * :purpose: Rebuild the ``YYYY-MM-DD`` record value from its three entry fields,
 *     reproducing ``STRING year '-' mon '-' day DELIMITED BY SIZE``.
 * :param year: the year segment.
 * :param mon: the month segment.
 * :param day: the day segment.
 * :returns: the assembled value, or an empty string when every segment is blank.
 */
function joinDate(year: string, mon: string, day: string): string {
  if (isBlank(year) && isBlank(mon) && isBlank(day)) {
    return '';
  }
  return (
    toFieldWidth(year.trim(), DATE_YEAR_LENGTH) +
    '-' +
    toFieldWidth(mon.trim(), DATE_PART_LENGTH) +
    '-' +
    toFieldWidth(day.trim(), DATE_PART_LENGTH)
  );
}

/**
 * :purpose: Split the customer SSN into the ``ACTSSN1`` / ``ACTSSN2`` / ``ACTSSN3``
 *     fields. A value the service masked keeps its mask in the two leading segments
 *     so the screen never presents a regulated identifier it did not receive.
 * :param value: the record value, clear ``PIC 9(09)`` digits or a ``***-**-nnnn`` mask.
 * :returns: the area, group and serial segments.
 */
function splitSsn(value: string): { area: string; group: string; serial: string } {
  const trimmed = value.trim();
  if (isMaskShaped(trimmed)) {
    return {
      area: '***',
      group: '**',
      serial: trimmed.slice(-SSN_SERIAL_LENGTH),
    };
  }
  const padded = padToWidth(trimmed, SSN_LENGTH);
  return {
    area: padded.slice(0, 3).trim(),
    group: padded.slice(3, 5).trim(),
    serial: padded.slice(5, 9).trim(),
  };
}

/**
 * :purpose: Rebuild the ``PIC 9(09)`` SSN from its three entry fields. Segments still
 *     carrying the service mask are echoed back in the exact masked presentation, so
 *     the service retains the stored identifier instead of overwriting it.
 * :param area: the ``ACTSSN1`` segment.
 * :param group: the ``ACTSSN2`` segment.
 * :param serial: the ``ACTSSN3`` segment.
 * :returns: the assembled value, or an empty string when every segment is blank.
 */
function joinSsn(area: string, group: string, serial: string): string {
  if (isBlank(area) && isBlank(group) && isBlank(serial)) {
    return '';
  }
  if (isMaskShaped(area) || isMaskShaped(group) || isMaskShaped(serial)) {
    return SSN_MASK_PREFIX + serial.trim();
  }
  return (
    toFieldWidth(area.trim(), SSN_AREA_LENGTH) +
    toFieldWidth(group.trim(), SSN_GROUP_LENGTH) +
    toFieldWidth(serial.trim(), SSN_SERIAL_LENGTH)
  );
}

/**
 * :purpose: Rebuild the ``PIC X(15)`` phone number from its three entry fields,
 *     reproducing ``STRING '(' a ')' b '-' c DELIMITED BY SIZE``.
 * :param areaCode: the ``ACSPH1A`` / ``ACSPH2A`` segment.
 * :param prefix: the ``ACSPH1B`` / ``ACSPH2B`` segment.
 * :param lineNumber: the ``ACSPH1C`` / ``ACSPH2C`` segment.
 * :returns: the assembled value, or an empty string when every segment is blank.
 */
function joinPhone(areaCode: string, prefix: string, lineNumber: string): string {
  if (isBlank(areaCode) && isBlank(prefix) && isBlank(lineNumber)) {
    return '';
  }
  return (
    '(' +
    toFieldWidth(areaCode.trim(), PHONE_AREA_LENGTH) +
    ')' +
    toFieldWidth(prefix.trim(), PHONE_PREFIX_LENGTH) +
    '-' +
    toFieldWidth(lineNumber.trim(), PHONE_LINE_LENGTH)
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
  const opened = splitDate(asText(response.acctOpenDate));
  const expiry = splitDate(asText(response.acctExpiraionDate));
  const reissue = splitDate(asText(response.acctReissueDate));
  const dob = splitDate(asText(response.custDobYyyyMmDd));
  const ssn = splitSsn(asText(response.custSsn));
  const phone1 = splitPhone(asText(response.custPhoneNum1));
  const phone2 = splitPhone(asText(response.custPhoneNum2));
  return {
    acctActiveStatus: asText(response.acctActiveStatus),
    opnYear: opened.year,
    opnMon: opened.mon,
    opnDay: opened.day,
    acctCreditLimit: asText(response.acctCreditLimit),
    expYear: expiry.year,
    expMon: expiry.mon,
    expDay: expiry.day,
    acctCashCreditLimit: asText(response.acctCashCreditLimit),
    risYear: reissue.year,
    risMon: reissue.mon,
    risDay: reissue.day,
    acctCurrBal: asText(response.acctCurrBal),
    acctCurrCycCredit: asText(response.acctCurrCycCredit),
    acctGroupId: asText(response.acctGroupId),
    acctCurrCycDebit: asText(response.acctCurrCycDebit),
    custId: asText(response.custId),
    actSsn1: ssn.area,
    actSsn2: ssn.group,
    actSsn3: ssn.serial,
    dobYear: dob.year,
    dobMon: dob.mon,
    dobDay: dob.day,
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
    acsPh1A: phone1.areaCode,
    acsPh1B: phone1.prefix,
    acsPh1C: phone1.lineNumber,
    custGovtIssuedId: asText(response.custGovtIssuedId),
    acsPh2A: phone2.areaCode,
    acsPh2B: phone2.prefix,
    acsPh2C: phone2.lineNumber,
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
    acctOpenDate: joinDate(form.opnYear, form.opnMon, form.opnDay),
    acctExpiraionDate: joinDate(form.expYear, form.expMon, form.expDay),
    acctReissueDate: joinDate(form.risYear, form.risMon, form.risDay),
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
    custPhoneNum1: joinPhone(form.acsPh1A, form.acsPh1B, form.acsPh1C),
    custPhoneNum2: joinPhone(form.acsPh2A, form.acsPh2B, form.acsPh2C),
    custSsn: joinSsn(form.actSsn1, form.actSsn2, form.actSsn3),
    custGovtIssuedId: form.custGovtIssuedId,
    custDobYyyyMmDd: joinDate(form.dobYear, form.dobMon, form.dobDay),
    custEftAccountId: form.custEftAccountId,
    custPriCardHolderInd: form.custPriCardHolderInd,
    custFicoCreditScore: form.custFicoCreditScore.trim(),
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

  const editDateParts = (
    yearField: keyof AccountUpdateFormState,
    monField: keyof AccountUpdateFormState,
    dayField: keyof AccountUpdateFormState,
    caption: string,
  ): void => {
    const year = form[yearField];
    const mon = form[monField];
    const day = form[dayField];
    const blank = isBlank(year) && isBlank(mon) && isBlank(day);
    if (blank) {
      fail(yearField, true, caption + SUFFIX_MUST_BE_SUPPLIED);
      fieldErrors[monField] = { invalid: false, blank: true };
      fieldErrors[dayField] = { invalid: false, blank: true };
      return;
    }
    if (!isCalendarDate(joinDate(year, mon, day))) {
      fail(yearField, false, caption + SUFFIX_IS_NOT_VALID);
      fieldErrors[monField] = { invalid: true, blank: false };
      fieldErrors[dayField] = { invalid: true, blank: false };
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

  const editSsnParts = (caption: string): void => {
    const assembled = joinSsn(form.actSsn1, form.actSsn2, form.actSsn3);
    if (isMaskShaped(assembled)) {
      return;
    }
    if (isBlank(assembled)) {
      fail('actSsn1', true, caption + SUFFIX_MUST_BE_SUPPLIED);
      fieldErrors.actSsn2 = { invalid: false, blank: true };
      fieldErrors.actSsn3 = { invalid: false, blank: true };
      return;
    }
    if (!isAllDigits(padToWidth(assembled.trim(), SSN_LENGTH))) {
      fail('actSsn1', false, caption + SUFFIX_MUST_BE_ALL_NUMERIC);
      fieldErrors.actSsn2 = { invalid: true, blank: false };
      fieldErrors.actSsn3 = { invalid: true, blank: false };
    }
  };

  const editPhoneParts = (
    areaField: keyof AccountUpdateFormState,
    prefixField: keyof AccountUpdateFormState,
    lineField: keyof AccountUpdateFormState,
    caption: string,
  ): void => {
    const areaCode = form[areaField].trim();
    const prefix = form[prefixField].trim();
    const lineNumber = form[lineField].trim();
    if (areaCode === '' && prefix === '' && lineNumber === '') {
      return;
    }
    if (areaCode.length !== PHONE_AREA_LENGTH || !isAllDigits(areaCode)) {
      fail(areaField, areaCode === '', caption + SUFFIX_AREA_CODE_3_DIGIT);
      return;
    }
    if (lineNumber.length !== PHONE_LINE_LENGTH || !isAllDigits(lineNumber)) {
      fail(lineField, lineNumber === '', caption + SUFFIX_LINE_4_DIGIT);
    }
  };

  if (!isValidAccountNumber(accountNumber)) {
    fail('acctsid', isBlank(accountNumber), MSG_ACCOUNT_ID_INVALID);
  }
  editYesNo('acctActiveStatus', VAR_ACCOUNT_STATUS);
  editDateParts('opnYear', 'opnMon', 'opnDay', VAR_OPEN_DATE);
  editAmount('acctCreditLimit', VAR_CREDIT_LIMIT);
  editDateParts('expYear', 'expMon', 'expDay', VAR_EXPIRY_DATE);
  editAmount('acctCashCreditLimit', VAR_CASH_CREDIT_LIMIT);
  editDateParts('risYear', 'risMon', 'risDay', VAR_REISSUE_DATE);
  editAmount('acctCurrBal', VAR_CURRENT_BALANCE);
  editAmount('acctCurrCycCredit', VAR_CURR_CYC_CREDIT);
  editAmount('acctCurrCycDebit', VAR_CURR_CYC_DEBIT);
  editSsnParts(VAR_SSN);
  editDateParts('dobYear', 'dobMon', 'dobDay', VAR_DATE_OF_BIRTH);
  editNumeric('custFicoCreditScore', VAR_FICO_SCORE, FICO_LENGTH);
  editRequired('custFirstName', VAR_FIRST_NAME);
  editRequired('custLastName', VAR_LAST_NAME);
  editRequired('custAddrLine1', VAR_ADDRESS_LINE_1);
  editRequired('custAddrStateCd', VAR_STATE);
  editNumeric('custAddrZip', VAR_ZIP, ZIP_LENGTH);
  editRequired('custAddrLine3', VAR_CITY);
  editRequired('custAddrCountryCd', VAR_COUNTRY);
  editPhoneParts('acsPh1A', 'acsPh1B', 'acsPh1C', VAR_PHONE_NUMBER_1);
  editPhoneParts('acsPh2A', 'acsPh2B', 'acsPh2C', VAR_PHONE_NUMBER_2);
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
  // ACUP-OLD-* — the values the read returned, never edited by the screen. Both the
  // change detection and the save target are anchored to this snapshot.
  const [originalForm, setOriginalForm] = useState<AccountUpdateFormState>(EMPTY_FORM);
  const [version, setVersion] = useState<number | null>(null);
  // The account the snapshot belongs to. Only a successful read sets it, so the
  // editable ACCTSID field can never redirect a save to a different account.
  const [loadedAccountId, setLoadedAccountId] = useState<string | null>(null);
  const [screenState, setScreenState] = useState<ScreenState>('DETAILS_NOT_FETCHED');
  const [fieldErrors, setFieldErrors] = useState<FieldErrorMap>({});
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [infoMessage, setInfoMessage] = useState<string>(MSG_PROMPT_FOR_SEARCH_KEYS);
  const [saving, setSaving] = useState<boolean>(false);

  const { run: runGetAccount, error: loadError, loading } = useApi(getAccount);

  // Synchronous guard: two activations of F5 within one render cannot both reach the
  // service, because the ref is observed and set before the first await.
  const saveLatch = useRef<boolean>(false);

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
      const seeded = toFormState(loaded);
      setForm(seeded);
      setOriginalForm(seeded);
      setVersion(loaded.version);
      setLoadedAccountId(identifier);
      setScreenState('SHOW_DETAILS');
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
      setOriginalForm(EMPTY_FORM);
      setVersion(null);
      setLoadedAccountId(null);
      setScreenState('DETAILS_NOT_FETCHED');
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
      setOriginalForm(EMPTY_FORM);
      setVersion(null);
      setLoadedAccountId(null);
      setScreenState('DETAILS_NOT_FETCHED');
      setErrorMessage(loadError.body?.message ?? loadError.message);
      setInfoMessage('');
    }
  }, [loadError]);

  // 3300-SETUP-SCREEN-ATTRS positions the cursor on the first field, in screen order,
  // whose edit failed; with no failure it rests on the first editable field of a
  // fetched account, or on ACCTSID while the keys are still being asked for.
  const firstErrorField = SCREEN_FIELD_ORDER.find((field) =>
    isFieldInError(fieldErrors[field]),
  );
  const focusField =
    firstErrorField ??
    (screenState === 'DETAILS_NOT_FETCHED' ? 'acctsid' : 'acctActiveStatus');
  const focusRef = useInitialFocus<HTMLInputElement>();
  useFocusOnChange(`${focusField}:${errorMessage}`, focusRef);

  /**
   * :purpose: Associate a field with the hint that describes it and, for the field the
   *     line-23 message was raised for, with that message.
   * :param field: the field being rendered.
   * :param hintId: id of the field's own hint, when it has one.
   * :returns: the ``aria-describedby`` value, or ``undefined`` when neither applies.
   */
  const describedBy = (field: string, hintId: string | undefined): string | undefined => {
    const parts = [hintId, field === firstErrorField ? ERROR_LINE_ID : undefined].filter(
      (part): part is string => part !== undefined,
    );
    return parts.length === 0 ? undefined : parts.join(' ');
  };

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
      setScreenState('DETAILS_NOT_FETCHED');
      return;
    }
    // A key that does not match the snapshot fetches that account and repaints the
    // screen in place, the source's 9000-READ-ACCT followed by 3000-SEND-MAP.
    if (requested !== loadedAccountId || version === null) {
      void loadAccount(requested);
      return;
    }
    if (!hasChanges(form, originalForm)) {
      setFieldErrors({});
      setErrorMessage(MSG_NO_CHANGES_DETECTED);
      setInfoMessage(MSG_PROMPT_FOR_CHANGES);
      setScreenState('SHOW_DETAILS');
      return;
    }
    const result = validateAccountUpdate(requested, form);
    setFieldErrors(result.fieldErrors);
    if (result.message !== '') {
      setErrorMessage(result.message);
      setInfoMessage(MSG_PROMPT_FOR_CHANGES);
      setScreenState('CHANGES_NOT_OK');
      return;
    }
    setErrorMessage('');
    setInfoMessage(MSG_PROMPT_FOR_CONFIRMATION);
    setScreenState('CHANGES_OK_NOT_CONFIRMED');
  }, [accountNumber, form, loadAccount, loadedAccountId, originalForm, version]);

  /**
   * :purpose: F5 — commit the edited account and customer fields, carrying the
   *     ``version`` snapshot read at display time. An HTTP ``409`` conflict reports
   *     the legacy ``DATA-WAS-CHANGED-BEFORE-UPDATE`` message and leaves the record
   *     and the entered values untouched, so the user reviews before retrying.
   */
  const handleSave = useCallback(async (): Promise<void> => {
    if (saveLatch.current) {
      return;
    }
    if (loadedAccountId === null || version === null) {
      setErrorMessage(MSG_ACCOUNT_NOT_PROVIDED);
      setInfoMessage(MSG_PROMPT_FOR_SEARCH_KEYS);
      return;
    }
    // The source reads the record it is about to rewrite and aborts when it no longer
    // matches the snapshot taken at display time. An ACCTSID edited away from the
    // loaded key is exactly that mismatch, so it reports the same outcome and writes
    // nothing until a fresh read re-anchors the snapshot.
    if (accountNumber.trim() !== loadedAccountId) {
      setErrorMessage(MSG_OPTIMISTIC_LOCK_CONFLICT);
      setInfoMessage(MSG_PROMPT_FOR_CHANGES);
      setScreenState('SHOW_DETAILS');
      return;
    }
    const result = validateAccountUpdate(loadedAccountId, form);
    setFieldErrors(result.fieldErrors);
    if (result.message !== '') {
      setErrorMessage(result.message);
      setInfoMessage(MSG_PROMPT_FOR_CHANGES);
      setScreenState('CHANGES_NOT_OK');
      return;
    }
    setErrorMessage('');
    saveLatch.current = true;
    setSaving(true);
    try {
      const updated: AccountUpdateResponseDto = await updateAccount(
        loadedAccountId,
        toRequestDto(form, version),
      );
      const committed = toFormState(updated);
      setForm(committed);
      setOriginalForm(committed);
      setVersion(updated.version);
      setFieldErrors({});
      setErrorMessage('');
      setInfoMessage(MSG_UPDATE_SUCCESS);
      setScreenState('CHANGES_OKAYED_AND_DONE');
    } catch (caught) {
      setInfoMessage(MSG_FAILURE);
      setScreenState('CHANGES_FAILED');
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
      saveLatch.current = false;
      setSaving(false);
    }
  }, [accountNumber, form, loadedAccountId, version]);

  /** :purpose: F3 — leave the screen for the calling menu. */
  const handleExit = useCallback((): void => {
    void navigate('/menu');
  }, [navigate]);

  /**
   * :purpose: F12 — discard the edits by re-reading the account and repainting the
   *     original values, the source's ``WHEN CCARD-AID-PFK12`` branch of
   *     ``2000-DECIDE-ACTION``. The screen is never left.
   */
  const handleCancel = useCallback((): void => {
    if (loadedAccountId === null) {
      return;
    }
    void loadAccount(loadedAccountId);
  }, [loadAccount, loadedAccountId]);

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

  // COACTUPC L905-916: only ENTER, PF3, PF5-while-awaiting-confirmation and
  // PF12-once-details-are-fetched are valid AIDs; every other combination is
  // rewritten to ENTER before the screen decides what to do.
  const activateSave = useCallback((): void => {
    if (screenState !== 'CHANGES_OK_NOT_CONFIRMED') {
      handlersRef.current.handleProcess();
      return;
    }
    void handlersRef.current.handleSave();
  }, [screenState]);

  const activateCancel = useCallback((): void => {
    if (screenState === 'DETAILS_NOT_FETCHED') {
      handlersRef.current.handleProcess();
      return;
    }
    handlersRef.current.handleCancel();
  }, [screenState]);

  // Publish the screen chrome; Layout renders the header, message line and key bar.
  useEffect(() => {
    // COACTUPC 3390-SETUP-INFOMSG-ATTRS un-darkens FKEY05 only while the confirmation
    // is being prompted, and FKEY12 as soon as changes exist that are not yet saved.
    const awaitingConfirmation = screenState === 'CHANGES_OK_NOT_CONFIRMED';
    const changesPending =
      screenState === 'CHANGES_NOT_OK' ||
      screenState === 'CHANGES_OK_NOT_CONFIRMED' ||
      screenState === 'CHANGES_FAILED';
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_PROCESS_LABEL, onActivate: activateProcess },
      { action: PfKeyAction.PF3, label: PF_EXIT_LABEL, onActivate: activateExit },
      {
        action: PfKeyAction.PF5,
        label: PF_SAVE_LABEL,
        onActivate: activateSave,
        dark: !awaitingConfirmation,
      },
      {
        action: PfKeyAction.PF12,
        label: PF_CANCEL_LABEL,
        onActivate: activateCancel,
        dark: !changesPending,
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
      busy: loading || saving,
    });
  }, [
    setChrome,
    errorMessage,
    infoMessage,
    screenState,
    loading,
    saving,
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
          ref={field === focusField ? focusRef : undefined}
          type="text"
          className={errorClass === '' ? 'field' : `field ${errorClass}`}
          maxLength={maxLength}
          size={maxLength}
          value={form[field]}
          disabled={saving}
          aria-label={ariaLabel}
          aria-describedby={describedBy(field, hintId)}
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

  /**
   * :purpose: Render a field the mapset splits across several 3270 fields — the three
   *     account dates, the date of birth, the SSN and the two phone numbers. The
   *     visible caption labels the first segment, so the on-screen prompt is also its
   *     programmatic name; the later segments carry their own accessible names because
   *     the mapset gives them no caption.
   * :param label: the visible TURQUOISE caption.
   * :param segments: the segments in mapset order.
   * :param separator: literal rendered between segments, the mapset's ``'-'`` fields.
   * :returns: The rendered caption and segment fields.
   */
  function segmentGroup({
    label,
    segments,
    separator,
  }: {
    label: string;
    segments: SegmentOptions[];
    separator?: string;
  }): ReactElement {
    const [first] = segments;
    const groupState = segments.map((segment) => fieldErrors[segment.field]);
    const marker = groupState.map(fieldMarker).find((each) => each !== '') ?? '';
    return (
      <span className="accountUpdate__field">
        {marker !== '' && (
          <span className="fieldError" aria-hidden="true">
            {marker}
          </span>
        )}
        <label className="prompt" htmlFor={first.field}>
          {label}
        </label>{' '}
        {segments.map((segment, index) => {
          const errorClass = fieldErrorClass(fieldErrors[segment.field]);
          return (
            <span key={segment.field}>
              {index > 0 && separator !== undefined && (
                <span className="label" aria-hidden="true">
                  {separator}
                </span>
              )}
              <input
                id={segment.field}
                name={segment.field}
                ref={segment.field === focusField ? focusRef : undefined}
                type="text"
                className={errorClass === '' ? 'field' : `field ${errorClass}`}
                maxLength={segment.maxLength}
                size={segment.maxLength}
                value={form[segment.field]}
                disabled={saving}
                aria-label={index === 0 ? undefined : segment.ariaLabel}
                aria-describedby={describedBy(segment.field, undefined)}
                aria-invalid={errorClass === '' ? undefined : true}
                onChange={(event) => {
                  handleFieldChange(segment.field, event.target.value);
                }}
              />
            </span>
          );
        })}
      </span>
    );
  }

  const accountNumberState = fieldErrors.acctsid;
  const accountNumberErrorClass = fieldErrorClass(accountNumberState);
  const accountNumberMarker = fieldMarker(accountNumberState);

  return (
    <section className="accountUpdate" aria-labelledby="accountUpdateHeading">
      <h2 id="accountUpdateHeading" className="neutral">
        {HEADING}
      </h2>

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
            ref={focusField === 'acctsid' ? focusRef : undefined}
            type="text"
            className={
              accountNumberErrorClass === '' ? 'field' : `field ${accountNumberErrorClass}`
            }
            maxLength={ACCOUNT_ID_LENGTH}
            size={ACCOUNT_ID_LENGTH}
            value={accountNumber}
            disabled={saving}
            aria-describedby={describedBy('acctsid', undefined)}
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
        {segmentGroup({
          label: 'Opened :',
          separator: '-',
          segments: [
            { field: 'opnYear', ariaLabel: `${VAR_OPEN_DATE} year`, maxLength: DATE_YEAR_LENGTH },
            { field: 'opnMon', ariaLabel: `${VAR_OPEN_DATE} month`, maxLength: DATE_PART_LENGTH },
            { field: 'opnDay', ariaLabel: `${VAR_OPEN_DATE} day`, maxLength: DATE_PART_LENGTH },
          ],
        })}{' '}
        {entryField({
          field: 'acctCreditLimit',
          label: 'Credit Limit        :',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {segmentGroup({
          label: 'Expiry :',
          separator: '-',
          segments: [
            { field: 'expYear', ariaLabel: `${VAR_EXPIRY_DATE} year`, maxLength: DATE_YEAR_LENGTH },
            { field: 'expMon', ariaLabel: `${VAR_EXPIRY_DATE} month`, maxLength: DATE_PART_LENGTH },
            { field: 'expDay', ariaLabel: `${VAR_EXPIRY_DATE} day`, maxLength: DATE_PART_LENGTH },
          ],
        })}{' '}
        {entryField({
          field: 'acctCashCreditLimit',
          label: 'Cash credit Limit   :',
          maxLength: AMOUNT_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {segmentGroup({
          label: 'Reissue:',
          separator: '-',
          segments: [
            {
              field: 'risYear',
              ariaLabel: `${VAR_REISSUE_DATE} year`,
              maxLength: DATE_YEAR_LENGTH,
            },
            {
              field: 'risMon',
              ariaLabel: `${VAR_REISSUE_DATE} month`,
              maxLength: DATE_PART_LENGTH,
            },
            { field: 'risDay', ariaLabel: `${VAR_REISSUE_DATE} day`, maxLength: DATE_PART_LENGTH },
          ],
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

      <h3 className="neutral">{CUSTOMER_SECTION_HEADING}</h3>

      <div className="accountUpdate__row">
        {entryField({
          field: 'custId',
          label: 'Customer id  :',
          maxLength: CUSTOMER_ID_LENGTH,
        })}{' '}
        {segmentGroup({
          label: 'SSN:',
          separator: '-',
          segments: [
            { field: 'actSsn1', ariaLabel: `${VAR_SSN} area`, maxLength: SSN_AREA_LENGTH },
            { field: 'actSsn2', ariaLabel: `${VAR_SSN} group`, maxLength: SSN_GROUP_LENGTH },
            { field: 'actSsn3', ariaLabel: `${VAR_SSN} serial`, maxLength: SSN_SERIAL_LENGTH },
          ],
        })}
      </div>

      <div className="accountUpdate__row">
        {segmentGroup({
          label: 'Date of birth:',
          separator: '-',
          segments: [
            {
              field: 'dobYear',
              ariaLabel: `${VAR_DATE_OF_BIRTH} year`,
              maxLength: DATE_YEAR_LENGTH,
            },
            {
              field: 'dobMon',
              ariaLabel: `${VAR_DATE_OF_BIRTH} month`,
              maxLength: DATE_PART_LENGTH,
            },
            {
              field: 'dobDay',
              ariaLabel: `${VAR_DATE_OF_BIRTH} day`,
              maxLength: DATE_PART_LENGTH,
            },
          ],
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
        {segmentGroup({
          label: 'Phone 1:',
          segments: [
            {
              field: 'acsPh1A',
              ariaLabel: `${VAR_PHONE_NUMBER_1} area code`,
              maxLength: PHONE_AREA_LENGTH,
            },
            {
              field: 'acsPh1B',
              ariaLabel: `${VAR_PHONE_NUMBER_1} prefix`,
              maxLength: PHONE_PREFIX_LENGTH,
            },
            {
              field: 'acsPh1C',
              ariaLabel: `${VAR_PHONE_NUMBER_1} line number`,
              maxLength: PHONE_LINE_LENGTH,
            },
          ],
        })}{' '}
        {entryField({
          field: 'custGovtIssuedId',
          label: 'Government Issued Id Ref    :',
          maxLength: GOVT_ID_LENGTH,
        })}
      </div>

      <div className="accountUpdate__row">
        {segmentGroup({
          label: 'Phone 2:',
          segments: [
            {
              field: 'acsPh2A',
              ariaLabel: `${VAR_PHONE_NUMBER_2} area code`,
              maxLength: PHONE_AREA_LENGTH,
            },
            {
              field: 'acsPh2B',
              ariaLabel: `${VAR_PHONE_NUMBER_2} prefix`,
              maxLength: PHONE_PREFIX_LENGTH,
            },
            {
              field: 'acsPh2C',
              ariaLabel: `${VAR_PHONE_NUMBER_2} line number`,
              maxLength: PHONE_LINE_LENGTH,
            },
          ],
        })}{' '}
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

    </section>
  );
}

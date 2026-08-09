/**
 * CardUpdatePage
 * ==============
 *
 * :purpose: The editable credit-card screen — a 1:1 replacement for the BMS mapset
 *     ``app/bms/COCRDUP.bms`` / symbolic map ``app/cpy-bms/COCRDUP.CPY`` (CICS
 *     transaction ``CCUP``, program ``app/cbl/COCRDUPC.cbl``). It reads the card
 *     addressed by the route, shows the protected account and card numbers, and
 *     lets the operator edit the embossed name, the active status and the expiry
 *     month / year, then validate them (ENTER) and rewrite the record.
 * :output: The rendered screen body. The header, the line-23 message and the
 *     line-24 function keys are published to the shared shell through
 *     :func:`useScreenChrome`, so this page renders none of them itself.
 */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  ERROR_LINE_ID,
  fieldErrorClass,
  fieldMarker,
  isFieldInError,
} from '../components/ErrorBanner';
import OutputField from '../components/OutputField';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, SCREEN_NAMES } from '../types';

/**
 * Row-4 screen name of ``app/bms/COCRDUP.bms``, rendered as the screen's own
 * body heading. The two header title lines are the shared ``COTTL01Y`` pair.
 */
const SCREEN_NAME = SCREEN_NAMES.COCRDUP;
import type {
  CardDetailResponseDto,
  CardUpdateRequestDto,
  CardUpdateResponseDto,
  FieldErrorMap,
} from '../types';
import { ApiError, getCard, updateCard } from '../api';
import { useApi, useFocusOnChange, useFocusOnSettled, useInitialFocus } from '../hooks';
import { readCardSelection, resolveExitRoute } from './cardSelection';

/** CICS transaction id of this screen (``LIT-THISTRANID``). */
const TRANSACTION_ID = 'CCUP';

/** Legacy program name shown in the header (``LIT-THISPGM``). */
const PROGRAM_NAME = 'COCRDUPC';

/** BMS caption of ``ACCTSID`` (row 7, ``X(19)``); the padding aligns the column. */
const LABEL_ACCOUNT_NUMBER = 'Account Number    :';

/** BMS caption of ``CARDSID`` (row 8, ``X(19)``). */
const LABEL_CARD_NUMBER = 'Card Number       :';

/** BMS caption of ``CRDNAME`` (row 11, ``X(20)``). */
const LABEL_NAME_ON_CARD = 'Name on card      :';

/** BMS caption of ``CRDSTCD`` (row 13, ``X(20)``). */
const LABEL_CARD_ACTIVE = 'Card Active Y/N   :';

/** BMS caption of ``EXPMON`` / ``EXPYEAR`` (row 15, ``X(20)``). */
const LABEL_EXPIRY_DATE = 'Expiry Date       :';

/** ``FOUND-CARDS-FOR-ACCOUNT`` — shown once the record has been read. */
const FOUND_CARDS_FOR_ACCOUNT = 'Details of selected card shown above';

/**
 * ``PROMPT-FOR-SEARCH-KEYS`` — the ``CDEMO-PGM-ENTER`` and ``CCUP-DETAILS-NOT-FETCHED``
 * branches of ``3250-SETUP-INFOMSG``, so it is what the ``INFOMSG`` field holds whenever
 * no record is on display.
 */
const PROMPT_FOR_SEARCH_KEYS = 'Please enter Account and Card Number';

/**
 * ``PROMPT-FOR-CHANGES`` — the ``CCUP-CHANGES-NOT-OK`` branch of
 * ``3250-SETUP-INFOMSG``: the record is on display and an edit refused a field, so the
 * informational line invites the correction while ``ERRMSG`` names the refusal.
 */
const PROMPT_FOR_CHANGES = 'Update card details presented above.';

/** ENTER half of the BMS ``FKEYS`` legend ``ENTER=Process F3=Exit``. */
const PF_PROCESS_LABEL = 'ENTER=Process';

/** PF3 half of the BMS ``FKEYS`` legend ``ENTER=Process F3=Exit``. */
const PF_EXIT_LABEL = 'F3=Exit';

/** First half of the BMS ``FKEYSC`` legend ``F5=Save F12=Cancel``, declared ``DRK``. */
const PF_SAVE_LABEL = 'F5=Save';

/** Second half of the BMS ``FKEYSC`` legend ``F5=Save F12=Cancel``, declared ``DRK``. */
const PF_CANCEL_LABEL = 'F12=Cancel';

/** ``PROMPT-FOR-CONFIRMATION`` — clean edits awaiting the F5 rewrite. */
const PROMPT_FOR_CONFIRMATION = 'Changes validated.Press F5 to save';

/** ``CONFIRM-UPDATE-SUCCESS`` — the rewrite committed. */
const CONFIRM_UPDATE_SUCCESS = 'Changes committed to database';

/** ``INFORM-FAILURE`` — the rewrite did not complete. */
const INFORM_FAILURE = 'Changes unsuccessful. Please try again';

/**
 * ``1220-EDIT-CARD`` non-numeric literal. ``COCRDUPC`` MOVEs this text directly; the
 * mixed-case ``SEARCHED-CARD-NOT-NUMERIC`` 88-level is declared (L193-194) but never
 * SET, so this upper-case form is the only one the program can emit.
 */
const CARD_FILTER_NOT_NUMERIC = 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';

/** ``WS-PROMPT-FOR-ACCT`` — ``1210-EDIT-ACCOUNT`` found the account key absent. */
const PROMPT_FOR_ACCT = 'Account number not provided';

/** ``WS-PROMPT-FOR-CARD`` — ``1220-EDIT-CARD`` found the card key absent. */
const PROMPT_FOR_CARD = 'Card number not provided';

/** ``NO-SEARCH-CRITERIA-RECEIVED`` — neither key was supplied. */
const NO_SEARCH_CRITERIA_RECEIVED = 'No input received';

/**
 * ``1210-EDIT-ACCOUNT`` non-numeric literal, MOVEd directly for the same reason as the
 * card form above: both ``SEARCHED-ACCT-NOT-NUMERIC`` and ``SEARCHED-ACCT-ZEROES`` are
 * unreachable 88-levels, and an all-zero key takes the not-supplied branch instead.
 */
const ACCOUNT_FILTER_NOT_NUMERIC = 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/** ``CC-ACCT-ID`` is ``PIC 9(11)`` and ``1210-EDIT-ACCOUNT`` refuses all zeros. */
const ACCOUNT_NUMBER_PATTERN = /^\d{11}$/;

/** ``WS-PROMPT-FOR-NAME`` — ``1230-EDIT-NAME`` found the embossed name absent. */
const PROMPT_FOR_NAME = 'Card name not provided';

/** ``WS-NAME-MUST-BE-ALPHA`` — ``1230-EDIT-NAME`` found a non-alphabetic character. */
const NAME_MUST_BE_ALPHA = 'Card name can only contain alphabets and spaces';

/**
 * :purpose: ``NO-CHANGES-DETECTED`` — ``1200-EDIT-MAP-INPUTS`` compares the whole
 *     upper-cased new card data against the values fetched and, when they match, skips
 *     every remaining edit and publishes this instead of validating or rewriting.
 */
const NO_CHANGES_DETECTED = 'No change detected with respect to values fetched.';

/** ``1230-EDIT-NAME`` accepts alphabetic characters and spaces only. */
const CARD_NAME_PATTERN = /^[A-Za-z ]+$/;

/** ``CARD-STATUS-MUST-BE-YES-NO`` — ``FLG-YES-NO-VALID`` rejected the status. */
const CARD_STATUS_MUST_BE_YES_NO = 'Card Active Status must be Y or N';

/** ``CARD-EXPIRY-MONTH-NOT-VALID`` — ``VALID-MONTH`` rejected the month. */
const CARD_EXPIRY_MONTH_NOT_VALID = 'Card expiry month must be between 1 and 12';

/** ``CARD-EXPIRY-YEAR-NOT-VALID`` — ``VALID-YEAR`` rejected the year. */
const CARD_EXPIRY_YEAR_NOT_VALID = 'Invalid card expiry year';

/** ``DATA-WAS-CHANGED-BEFORE-UPDATE`` — the HTTP 409 optimistic-lock outcome. */
const DATA_WAS_CHANGED_BEFORE_UPDATE = 'Record changed by some one else. Please review';

/** ``CC-CARD-NUM`` must be exactly 16 digits (``CVACT02Y`` ``X(16)``). */
const CARD_NUMBER_PATTERN = /^\d{16}$/;

/** ``CARD-MONTH-CHECK`` accepts one or two digits before the range test. */
const EXPIRY_MONTH_PATTERN = /^\d{1,2}$/;

/** ``CARD-YEAR-CHECK`` is ``PIC 9(4)``. */
const EXPIRY_YEAR_PATTERN = /^\d{4}$/;

/** ``88 VALID-MONTH VALUES 1 THRU 12``. */
const MIN_EXPIRY_MONTH = 1;
const MAX_EXPIRY_MONTH = 12;

/** ``88 VALID-YEAR VALUES 1950 THRU 2099``. */
const MIN_EXPIRY_YEAR = 1950;
const MAX_EXPIRY_YEAR = 2099;

/** Active-status values accepted by ``88 FLG-YES-NO-VALID VALUES 'Y', 'N'``. */
const CARD_ACTIVE_STATUS_YES = 'Y';

/**
 * :purpose: The two search keys in screen order — ``ACCTSID`` on row 7 and ``CARDSID`` on
 *     row 8 — which is the order ``1200-EDIT-MAP-INPUTS`` edits them in while the record
 *     has not been fetched, and therefore the order the cursor is offered them in.
 */
const KEY_FIELD_ORDER: readonly string[] = ['acctsid', 'cardsid'];

/**
 * :purpose: Every editable data field of the ``COCRDUP`` mapset in screen order — rows 11,
 *     13 and 15 — which is the order ``COCRDUPC`` walks when deciding where to leave
 *     the cursor once a record is on display. Both keys turn ``DFHBMPRF`` in that state
 *     (``3300-SETUP-SCREEN-ATTRS``), so neither can hold the cursor.
 */
const DATA_FIELD_ORDER: readonly string[] = [
  'crdname',
  'crdstcd',
  'expmon',
  'expyear',
];
const CARD_ACTIVE_STATUS_NO = 'N';

/**
 * :purpose: The three segments of ``CARD-EXPIRAION-DATE-X`` (``X(10)``), whose
 *     redefinition is year / month / day. The legacy misspelling is preserved.
 * :field year: ``CARD-EXPIRY-YEAR`` (``X(4)``).
 * :field month: ``CARD-EXPIRY-MONTH`` (``X(2)``).
 * :field day: ``CARD-EXPIRY-DAY`` (``X(2)``), never editable on this screen.
 */
interface ExpiraionDateParts {
  year: string;
  month: string;
  day: string;
}

/**
 * :purpose: The result of one edit pass over the map inputs
 *     (``1200-EDIT-MAP-INPUTS``).
 * :field message: the line-23 text of the first failing edit, empty when the
 *     inputs are clean.
 * :field fieldErrors: per-field highlight flags for the failing fields.
 */
interface ValidationOutcome {
  message: string;
  fieldErrors: FieldErrorMap;
}

/**
 * :purpose: The editable data fields submitted to one edit pass.
 * :field cardName: ``CRDNAME`` as typed.
 * :field cardActiveStatus: ``CRDSTCD`` as typed.
 * :field expiryMonth: ``EXPMON`` as typed.
 * :field expiryYear: ``EXPYEAR`` as typed.
 */
interface CardUpdateInputs {
  cardName: string;
  cardActiveStatus: string;
  expiryMonth: string;
  expiryYear: string;
}

/**
 * :purpose: Split a ``YYYY-MM-DD`` expiration date into its year, month and day
 *     segments, mirroring the ``CARD-EXPIRAION-DATE-X`` redefinition.
 * :param value: the wire date; may be empty before the record is read.
 * :returns: the three segments, each empty when the input is shorter.
 */
function splitExpiraionDate(value: string): ExpiraionDateParts {
  const text = value ?? '';
  return {
    year: text.slice(0, 4),
    month: text.slice(5, 7),
    day: text.slice(8, 10),
  };
}

/**
 * :purpose: Rebuild the ``YYYY-MM-DD`` wire date from its segments, zero-padding
 *     the month to the two characters of ``CARD-EXPIRY-MONTH``.
 * :param year: the edited ``EXPYEAR`` value.
 * :param month: the edited ``EXPMON`` value.
 * :param day: the ``CARD-EXPIRY-DAY`` carried over from the record read.
 * :returns: the reassembled expiration date.
 */
function joinExpiraionDate(year: string, month: string, day: string): string {
  return `${year}-${month.padStart(2, '0')}-${day}`;
}

/**
 * :purpose: Resolve the line-23 text for a failed call, preferring the
 *     standardized backend ``ApiErrorResponse.message``.
 * :param error: the normalized error raised by the api client.
 * :param fallback: text used when the response carried no message.
 * :returns: the message to publish.
 */
function resolveServerMessage(error: ApiError, fallback: string): string {
  const bodyMessage = error.body?.message;
  return bodyMessage !== undefined && bodyMessage.length > 0 ? bodyMessage : fallback;
}

/**
 * :purpose: Join a base class with the conditional field-error class without
 *     leaving a trailing separator.
 * :param classes: the candidate class names.
 * :returns: the space-separated class attribute.
 */
function classNames(...classes: string[]): string {
  return classes.filter((name) => name.length > 0).join(' ');
}

/**
 * :purpose: Run one edit pass over the DATA fields of a fetched record, reproducing the
 *     ``1230-EDIT-NAME`` / ``1240-EDIT-CARDSTATUS`` / ``1250-EDIT-EXPIRY-MON`` /
 *     ``1260-EDIT-EXPIRY-YEAR`` order. As in ``COCRDUPC``, only the first failing
 *     edit publishes its message (``IF WS-RETURN-MSG-OFF``) while every failing
 *     field is still flagged. The two search keys are NOT edited here: ``1210-EDIT-ACCOUNT``
 *     and ``1220-EDIT-CARD`` run only while ``CCUP-DETAILS-NOT-FETCHED`` holds, and are
 *     reproduced by :func:`editSearchKeys`.
 * :param inputs: the map inputs to edit.
 * :returns: the :class:`ValidationOutcome` of the pass.
 */
function editMapInputs(inputs: CardUpdateInputs): ValidationOutcome {
  const fieldErrors: FieldErrorMap = {};
  let message = '';

  // ``1230-EDIT-NAME`` is the FIRST edit COCRDUPC performs on the fetched record, ahead
  // of the status and the expiry, so with several fields wrong it is the name that
  // publishes its message. Absence is reported before the alphabetic rule, and the edit
  // stops at the first of the two (``GO TO 1230-EDIT-NAME-EXIT``).
  const name = inputs.cardName;
  if (name.trim().length === 0) {
    fieldErrors.crdname = { invalid: false, blank: true };
    if (message.length === 0) {
      message = PROMPT_FOR_NAME;
    }
  } else if (!CARD_NAME_PATTERN.test(name)) {
    fieldErrors.crdname = { invalid: true, blank: false };
    if (message.length === 0) {
      message = NAME_MUST_BE_ALPHA;
    }
  }

  const status = inputs.cardActiveStatus;
  if (status.length === 0) {
    fieldErrors.crdstcd = { invalid: false, blank: true };
    if (message.length === 0) {
      message = CARD_STATUS_MUST_BE_YES_NO;
    }
  } else if (status !== CARD_ACTIVE_STATUS_YES && status !== CARD_ACTIVE_STATUS_NO) {
    fieldErrors.crdstcd = { invalid: true, blank: false };
    if (message.length === 0) {
      message = CARD_STATUS_MUST_BE_YES_NO;
    }
  }

  const month = inputs.expiryMonth.trim();
  const monthValue = Number(month);
  if (month.length === 0) {
    fieldErrors.expmon = { invalid: false, blank: true };
    if (message.length === 0) {
      message = CARD_EXPIRY_MONTH_NOT_VALID;
    }
  } else if (
    !EXPIRY_MONTH_PATTERN.test(month) ||
    monthValue < MIN_EXPIRY_MONTH ||
    monthValue > MAX_EXPIRY_MONTH
  ) {
    fieldErrors.expmon = { invalid: true, blank: false };
    if (message.length === 0) {
      message = CARD_EXPIRY_MONTH_NOT_VALID;
    }
  }

  const year = inputs.expiryYear.trim();
  const yearValue = Number(year);
  if (year.length === 0) {
    fieldErrors.expyear = { invalid: false, blank: true };
    if (message.length === 0) {
      message = CARD_EXPIRY_YEAR_NOT_VALID;
    }
  } else if (
    !EXPIRY_YEAR_PATTERN.test(year) ||
    yearValue < MIN_EXPIRY_YEAR ||
    yearValue > MAX_EXPIRY_YEAR
  ) {
    fieldErrors.expyear = { invalid: true, blank: false };
    if (message.length === 0) {
      message = CARD_EXPIRY_YEAR_NOT_VALID;
    }
  }

  return { message, fieldErrors };
}

/**
 * :purpose: Edit the two SEARCH KEYS, reproducing ``1210-EDIT-ACCOUNT`` then
 *     ``1220-EDIT-CARD``, which is the only edit pass ``1200-EDIT-MAP-INPUTS`` performs
 *     while ``CCUP-DETAILS-NOT-FETCHED``. When both keys are blank the whole submission
 *     is refused with ``NO-SEARCH-CRITERIA-RECEIVED`` rather than with either field's
 *     own prompt.
 * :param keyedAccount: the account number as keyed.
 * :param keyedCard: the card number as keyed.
 * :returns: the :class:`ValidationOutcome` of the key pass.
 */
function editSearchKeys(keyedAccount: string, keyedCard: string): ValidationOutcome {
  const fieldErrors: FieldErrorMap = {};
  const account = keyedAccount.trim();
  const cardKey = keyedCard.trim();
  let message = '';

  const accountBlank = account.length === 0;
  const cardBlank = cardKey.length === 0;
  if (accountBlank && cardBlank) {
    fieldErrors.acctsid = { invalid: false, blank: true };
    fieldErrors.cardsid = { invalid: false, blank: true };
    return { message: NO_SEARCH_CRITERIA_RECEIVED, fieldErrors };
  }

  if (accountBlank) {
    fieldErrors.acctsid = { invalid: false, blank: true };
    message = PROMPT_FOR_ACCT;
  } else if (!ACCOUNT_NUMBER_PATTERN.test(account) || Number(account) === 0) {
    fieldErrors.acctsid = { invalid: true, blank: false };
    message = ACCOUNT_FILTER_NOT_NUMERIC;
  }

  if (cardBlank) {
    fieldErrors.cardsid = { invalid: false, blank: true };
    if (message.length === 0) {
      message = PROMPT_FOR_CARD;
    }
  } else if (!CARD_NUMBER_PATTERN.test(cardKey)) {
    fieldErrors.cardsid = { invalid: true, blank: false };
    if (message.length === 0) {
      message = CARD_FILTER_NOT_NUMERIC;
    }
  }

  return { message, fieldErrors };
}

/**
 * :purpose: Report whether the entered card data matches the values fetched, which is
 *     the ``NO-CHANGES-DETECTED`` test ``1200-EDIT-MAP-INPUTS`` performs: it compares
 *     the WHOLE upper-cased new card data against the whole upper-cased old card data
 *     and, when they are equal, skips every remaining edit and neither validates nor
 *     rewrites. Comparing upper-cased is the legacy behaviour, so re-keying a name in a
 *     different case is correctly treated as no change.
 * :param entered: the values currently on the screen.
 * :param record: the record read at display time.
 * :returns: ``true`` when nothing was changed.
 */
function isUnchanged(
  entered: { cardName: string; cardActiveStatus: string; expiryMonth: string; expiryYear: string },
  record: CardDetailResponseDto,
): boolean {
  const expiry = splitExpiraionDate(record.cardExpiraionDate);
  const asKeyed = [
    entered.cardName,
    entered.cardActiveStatus,
    entered.expiryMonth.trim().padStart(2, '0'),
    entered.expiryYear.trim(),
  ]
    .join('\u0001')
    .toUpperCase();
  const asFetched = [
    record.cardEmbossedName,
    record.cardActiveStatus,
    expiry.month,
    expiry.year,
  ]
    .join('\u0001')
    .toUpperCase();
  return asKeyed === asFetched;
}

/**
 * :purpose: The ``COCRDUP`` card-update screen. Reads the card handed over in the
 *     router location state, edits the mutable fields, and rewrites the record
 *     through ``PUT /cards/{cardNumber}``.
 * :returns: The rendered screen body.
 */
export default function CardUpdatePage(): ReactElement {
  const location = useLocation();
  // The composite selection arrives in the router location state, so neither the
  // card number nor the account id is ever part of this screen's URL.
  const handover = readCardSelection(location.state);
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { run: runGetCard, loading: fetching, error: fetchError } = useApi(getCard);

  const [card, setCard] = useState<CardDetailResponseDto | null>(null);
  // The two SEARCH KEYS. ``3300-SETUP-SCREEN-ATTRS`` makes ACCTSID and CARDSID
  // enterable (``DFHBMFSE``) for exactly as long as ``CCUP-DETAILS-NOT-FETCHED`` holds,
  // and protects them (``DFHBMPRF``) once the record is on display — so this screen is a
  // usable ENTRY POINT as well as a hand-over target, which is what makes main-menu
  // option 5 and a reload work instead of dead-ending. They are seeded from the
  // hand-over when the previous screen supplied one.
  const [keyedAccount, setKeyedAccount] = useState(handover.accountId);
  const [keyedCard, setKeyedCard] = useState(handover.cardNumber);
  const [cardName, setCardName] = useState('');
  const [cardActiveStatus, setCardActiveStatus] = useState('');
  const [expiryMonth, setExpiryMonth] = useState('');
  const [expiryYear, setExpiryYear] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  // 3250-SETUP-INFOMSG runs on every send and every one of its branches selects a
  // literal, so the INFOMSG field is never blank once the screen has been sent. The
  // opening value is the CDEMO-PGM-ENTER branch.
  const [infoMessage, setInfoMessage] = useState(PROMPT_FOR_SEARCH_KEYS);
  const [fieldErrors, setFieldErrors] = useState<FieldErrorMap>({});
  const [changesValidated, setChangesValidated] = useState(false);
  const [saving, setSaving] = useState(false);

  // Synchronous guard: two activations of F5 inside one render cannot both reach the
  // service, because the ref is read and set before the first await.
  const saveLatch = useRef<boolean>(false);

  // The keys the screen is acting on. Once a record is displayed they are the record's
  // own, so a rewrite can never be aimed at a key the operator has since re-typed.
  const cardNumber = card === null ? keyedCard.trim() : (card.cardNum ?? keyedCard.trim());
  const accountId = card === null ? keyedAccount.trim() : (card.cardAcctId ?? '');

  /**
   * :purpose: Seed the display and entry fields from a card record, splitting the
   *     wire ``cardExpiraionDate`` into the month and year fields.
   * :param record: the record just read or rewritten.
   */
  const applyCard = useCallback((record: CardDetailResponseDto): void => {
    const expiry = splitExpiraionDate(record.cardExpiraionDate);
    setCard(record);
    setCardName(record.cardEmbossedName ?? '');
    setCardActiveStatus(record.cardActiveStatus ?? '');
    setExpiryMonth(expiry.month);
    setExpiryYear(expiry.year);
  }, []);

  /**
   * :purpose: Read a card record and seed the screen from it — ``9000-READ-DATA``. The
   *     keys are edited by the caller, so this issues the request unconditionally; a
   *     refused read leaves the previously displayed record untouched and lets the
   *     ``fetchError`` effect publish the server's message.
   * :param account: the ``ACCTSID`` key, or the empty string to read by card alone.
   * :param cardKey: the ``CARDSID`` key.
   */
  const readRecord = useCallback(
    async (account: string, cardKey: string): Promise<void> => {
      const record = await runGetCard(cardKey, account === '' ? undefined : account);
      if (record !== undefined) {
        applyCard(record);
        setFieldErrors({});
        setErrorMessage('');
        setInfoMessage(FOUND_CARDS_FOR_ACCOUNT);
        setChangesValidated(false);
      }
    },
    [applyCard, runGetCard],
  );

  /**
   * :purpose: Edit the two search keys and, when they are clean, read the record —
   *     the ``CCUP-DETAILS-NOT-FETCHED`` half of ``1200-EDIT-MAP-INPUTS``, which runs
   *     ``1210-EDIT-ACCOUNT`` then ``1220-EDIT-CARD`` before it reads anything. A
   *     refused key publishes its own prompt and issues no request, leaving the keys
   *     enterable so the operator can correct them in place.
   * :param account: the ``ACCTSID`` key to edit and read by.
   * :param cardKey: the ``CARDSID`` key to edit and read by.
   */
  const editThenRead = useCallback(
    async (account: string, cardKey: string): Promise<void> => {
      const outcome = editSearchKeys(account, cardKey);
      setFieldErrors(outcome.fieldErrors);
      if (outcome.message.length > 0) {
        setErrorMessage(outcome.message);
        // Nothing was read, so CCUP-DETAILS-NOT-FETCHED still holds and 3250 selects
        // PROMPT-FOR-SEARCH-KEYS.
        setInfoMessage(PROMPT_FOR_SEARCH_KEYS);
        return;
      }
      setErrorMessage('');
      await readRecord(account.trim(), cardKey.trim());
    },
    [readRecord],
  );

  /** :purpose: ENTER in the entry state — edit the keys as typed, then read. */
  const fetchByKeys = useCallback(async (): Promise<void> => {
    await editThenRead(keyedAccount, keyedCard);
  }, [editThenRead, keyedAccount, keyedCard]);

  // A hand-over from the list screen carries keys COCRDLIC built from a browse row, so
  // its record is read straight away — the PGM-ENTER / FROM-CCLISTPGM branch (L482-496),
  // "USER CAME FROM CREDIT CARD LIST SCREEN SO WE ALREADY HAVE THE FILTER KEYS". The keys
  // are still edited first, so a hand-over carrying a malformed key publishes its prompt
  // and issues no doomed read. Arriving with NO hand-over — main-menu option 5, a reload,
  // a bookmark — leaves the screen in its entry state with the keys enterable and no
  // message at all, which is how a 3270 transaction started without a COMMAREA presents
  // itself.
  const handoverReadRef = useRef<string>('');
  useEffect(() => {
    const handoverKey = `${handover.accountId}:${handover.cardNumber}`;
    if (handover.cardNumber === '' || handoverReadRef.current === handoverKey) {
      return;
    }
    handoverReadRef.current = handoverKey;
    void editThenRead(handover.accountId, handover.cardNumber);
  }, [editThenRead, handover.accountId, handover.cardNumber]);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    if (fetchError !== null) {
      setErrorMessage(resolveServerMessage(fetchError, fetchError.message));
      setInfoMessage(PROMPT_FOR_SEARCH_KEYS);
    }
  }, [fetchError]);

  /**
   * :purpose: Edit the current map inputs and publish the per-field highlights.
   * :returns: The :class:`ValidationOutcome` of the pass.
   */
  const editCurrentInputs = useCallback((): ValidationOutcome => {
    const outcome = editMapInputs({
      cardName,
      cardActiveStatus,
      expiryMonth,
      expiryYear,
    });
    setFieldErrors(outcome.fieldErrors);
    return outcome;
  }, [cardActiveStatus, cardName, expiryMonth, expiryYear]);

  /**
   * :purpose: Rewrite the record. Only the mutable fields travel in the body, the
   *     expiry day is carried over from the record read, and the version read at
   *     display time is echoed back; an HTTP 409 conflict publishes the
   *     concurrent-change message.
   * :param record: the record currently displayed.
   */
  const saveChanges = useCallback(
    async (record: CardDetailResponseDto): Promise<void> => {
      const expiry = splitExpiraionDate(record.cardExpiraionDate);
      const request: CardUpdateRequestDto = {
        cardEmbossedName: cardName,
        cardActiveStatus,
        cardExpiraionDate: joinExpiraionDate(
          expiryYear.trim(),
          expiryMonth.trim(),
          expiry.day,
        ),
        version: record.version,
        // The values read at display time, so the service can reproduce the
        // field-by-field comparison COCRDUPC performs before its REWRITE
        // (L1503) in addition to the version check.
        oldCardEmbossedName: record.cardEmbossedName,
        oldCardActiveStatus: record.cardActiveStatus,
        oldCardExpiraionDate: record.cardExpiraionDate,
      };
      saveLatch.current = true;
      setSaving(true);
      try {
        const updated: CardUpdateResponseDto = await updateCard(
          cardNumber,
          request,
          accountId === '' ? undefined : accountId,
        );
        applyCard(updated);
        setFieldErrors({});
        setChangesValidated(false);
        setErrorMessage('');
        setInfoMessage(CONFIRM_UPDATE_SUCCESS);
      } catch (error) {
        if (error instanceof ApiError && error.isOptimisticLockConflict) {
          setErrorMessage(DATA_WAS_CHANGED_BEFORE_UPDATE);
          setChangesValidated(false);
          // L997-998 SETs CCUP-SHOW-DETAILS for this branch, so 3250 selects
          // FOUND-CARDS-FOR-ACCOUNT rather than INFORM-FAILURE.
          setInfoMessage(FOUND_CARDS_FOR_ACCOUNT);
          // L997-998 answers the concurrency branch with CCUP-SHOW-DETAILS, and
          // L1107-1112 paints that state from CCUP-OLD-*, the values read at display
          // time — never from the edited CCUP-NEW-*. Re-reading here is what makes the
          // painted record, the no-change comparison and the version a retry carries one
          // consistent set: the operator reviews the record that beat them, as the message
          // asks, and re-applies the edit on top of it rather than undoing it unseen.
          const reviewed = await runGetCard(
            cardNumber,
            accountId === '' ? undefined : accountId,
          );
          if (reviewed !== undefined) {
            applyCard(reviewed);
            setFieldErrors({});
            setErrorMessage(DATA_WAS_CHANGED_BEFORE_UPDATE);
          }
          return;
        }
        if (error instanceof ApiError) {
          setErrorMessage(resolveServerMessage(error, INFORM_FAILURE));
        } else {
          setErrorMessage(INFORM_FAILURE);
        }
        setChangesValidated(false);
        // INFORM-FAILURE is declared under WS-INFO-MSG (L170-171), so it belongs on the
        // INFOMSG line; ERRMSG carries what the failure itself reported.
        setInfoMessage(INFORM_FAILURE);
      } finally {
        saveLatch.current = false;
        setSaving(false);
      }
    },
    [
      accountId,
      applyCard,
      cardActiveStatus,
      cardName,
      cardNumber,
      expiryMonth,
      expiryYear,
      runGetCard,
    ],
  );

  /**
   * :purpose: ENTER — edit the map inputs and, when they are clean, ask for the F5
   *     confirmation.
   */
  const handleProcess = useCallback((): void => {
    // ``1200-EDIT-MAP-INPUTS`` edits ONLY the search keys while the record has not been
    // fetched, and exits: the data fields are protected in that state and hold nothing to
    // edit.
    if (card === null) {
      void fetchByKeys();
      return;
    }
    // ``1200-EDIT-MAP-INPUTS`` tests for no change BEFORE the field edits and, when it
    // finds none, exits without validating and without offering the rewrite. Reporting a
    // commit for a submission that changed nothing is the one outcome the legacy screen
    // never produces.
    if (card !== null && isUnchanged({ cardName, cardActiveStatus, expiryMonth, expiryYear }, card)) {
      setFieldErrors({});
      setErrorMessage(NO_CHANGES_DETECTED);
      // The no-change branch GO TOes 1200-EDIT-MAP-INPUTS-EXIT at L691, BEFORE L696
      // SETs CCUP-CHANGES-NOT-OK, so the state stays CCUP-SHOW-DETAILS and 3250 keeps
      // FOUND-CARDS-FOR-ACCOUNT on the informational line beside the refusal.
      setInfoMessage(FOUND_CARDS_FOR_ACCOUNT);
      setChangesValidated(false);
      return;
    }
    const outcome = editCurrentInputs();
    if (outcome.message.length > 0) {
      setErrorMessage(outcome.message);
      // L696 SET CCUP-CHANGES-NOT-OK runs before the field edits, so 3250 selects
      // PROMPT-FOR-CHANGES.
      setInfoMessage(PROMPT_FOR_CHANGES);
      setChangesValidated(false);
      return;
    }
    setErrorMessage('');
    setInfoMessage(PROMPT_FOR_CONFIRMATION);
    setChangesValidated(true);
  }, [card, cardActiveStatus, cardName, editCurrentInputs, expiryMonth, expiryYear, fetchByKeys]);

  /**
   * :purpose: F5 — rewrite the record. When the edits have not been confirmed yet
   *     the edit pass runs first, and the rewrite proceeds only once it is clean.
   */
  const handleSave = useCallback((): void => {
    if (saveLatch.current) {
      return;
    }
    // PF5 is a valid AID only `WHEN CCARD-AID-PFK05 AND CCUP-CHANGES-OK-NOT-CONFIRMED`
    // (L416); every other AID is rewritten to ENTER. With no record fetched that makes it
    // the key-read, never a confirmation prompt for a record that is not on the screen.
    if (card === null) {
      void fetchByKeys();
      return;
    }
    if (isUnchanged({ cardName, cardActiveStatus, expiryMonth, expiryYear }, card)) {
      setFieldErrors({});
      setErrorMessage(NO_CHANGES_DETECTED);
      setInfoMessage(FOUND_CARDS_FOR_ACCOUNT);
      setChangesValidated(false);
      return;
    }
    void saveChanges(card);
  }, [card, cardActiveStatus, cardName, expiryMonth, expiryYear, fetchByKeys, saveChanges]);

  /**
   * :purpose: F3 — leave the screen. ``COCRDUPC`` L442-455 transfers to
   *     ``CDEMO-FROM-PROGRAM``, substituting ``LIT-MENUPGM`` only when no caller was
   *     recorded, so the operator is returned to the screen they actually came from.
   */
  const handleExit = useCallback((): void => {
    void navigate(resolveExitRoute(handover.from));
  }, [handover.from, navigate]);

  /**
   * :purpose: F12 — abandon the edits. ``COCRDUPC`` L958-966 answers PF12 by re-running
   *     ``9000-READ-DATA`` and setting ``CCUP-SHOW-DETAILS``: it repaints THIS screen with
   *     the stored values and never transfers away, so the operator keeps the record and
   *     can simply start again. Leaving the screen discarded their place as well as their
   *     edits.
   */
  const handleCancel = useCallback((): void => {
    setChangesValidated(false);
    setFieldErrors({});
    setErrorMessage('');
    void readRecord(accountId, cardNumber);
  }, [accountId, cardNumber, readRecord]);

  // Flushed synchronously at commit: the message and the function keys reach the
  // shell in the same frame as the body they belong to.
  useLayoutEffect(() => {
    // COCRDUPC L413-423: only ENTER, PF3, PF5-while-awaiting-confirmation and
    // PF12-once-details-are-fetched are valid AIDs; anything else is rewritten to ENTER.
    // COCRDUP.bms declares FKEYSC — the single field carrying BOTH 'F5=Save' and
    // 'F12=Cancel' — ATTRB=(ASKIP,DRK), and L1315-1317 un-darkens it only while the
    // confirmation is prompted, so the two legends appear and disappear together.
    const awaitingConfirmation = changesValidated;
    const detailsFetched = card !== null;
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_PROCESS_LABEL, onActivate: handleProcess },
      { action: PfKeyAction.PF3, label: PF_EXIT_LABEL, onActivate: handleExit },
      {
        action: PfKeyAction.PF5,
        label: PF_SAVE_LABEL,
        dark: !awaitingConfirmation,
        onActivate: () => {
          if (!awaitingConfirmation) {
            handleProcess();
            return;
          }
          handleSave();
        },
      },
      {
        action: PfKeyAction.PF12,
        label: PF_CANCEL_LABEL,
        dark: !awaitingConfirmation,
        onActivate: () => {
          if (!detailsFetched) {
            handleProcess();
            return;
          }
          handleCancel();
        },
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      busy: saving,
      errorMessage,
      infoFieldMessage: infoMessage,
      pfKeys,
    });
  }, [
    card,
    changesValidated,
    errorMessage,
    handleCancel,
    handleExit,
    handleProcess,
    handleSave,
    infoMessage,
    saving,
    setChrome,
  ]);

  // Which fields the operator may key — and therefore which the cursor may rest on —
  // follows ``3300-SETUP-SCREEN-ATTRS``: the two keys while the record has not been
  // fetched, the data fields once it is on display.
  const keysEnterable = card === null;
  // ``3300-SETUP-SCREEN-ATTRS`` moves ``DFHBMPRF`` over the four detail fields
  // ``WHEN CCUP-DETAILS-NOT-FETCHED`` — there is nothing to edit before a record is on
  // the screen — and over ALL SIX fields ``WHEN CCUP-CHANGES-OK-NOT-CONFIRMED`` /
  // ``WHEN CCUP-CHANGES-OKAYED-AND-DONE``, so a validated screen awaiting its F5 accepts
  // no further input. The two keys protect themselves by rendering as output cells.
  const detailsProtected = card === null || changesValidated;
  const fieldOrder = keysEnterable ? KEY_FIELD_ORDER : DATA_FIELD_ORDER;

  // COCRDUPC leaves the cursor on the first field, in screen order, whose edit failed;
  // with no failure it rests on the first field the operator may key.
  const firstErrorField = fieldOrder.find((field) => isFieldInError(fieldErrors[field]));
  const focusField = firstErrorField ?? fieldOrder[0];
  const focusRef = useInitialFocus<HTMLInputElement>();
  useFocusOnChange(`${focusField}:${errorMessage}`, focusRef);
  // The search keys are disabled while the read is in flight, which blurs the focused one
  // to the document body; the cursor is placed again on the field this screen state offers
  // it to as soon as the read settles.
  useFocusOnSettled(fetching, focusRef);

  /**
   * :purpose: Associate the field the line-23 message was raised for with that message.
   * :param field: the field being rendered.
   * :returns: the ``aria-describedby`` value, or ``undefined`` for every other field.
   */
  const describedBy = (field: string): string | undefined =>
    field === firstErrorField ? ERROR_LINE_ID : undefined;

  return (
    <div className="cardUpdate">
      <h3 className="cardUpdate__heading neutral">{SCREEN_NAME}</h3>

      {keysEnterable ? (
        // ``3300-SETUP-SCREEN-ATTRS`` sets both keys to ``DFHBMFSE`` — enterable, with the
        // cursor offered to them — for the whole of ``CCUP-DETAILS-NOT-FETCHED``. That is
        // what makes this screen a usable entry point in its own right: an operator who
        // arrives from main-menu option 5, a reload or a bookmark keys the account and card
        // here and presses ENTER to read the record.
        <div className="cardUpdate__keys">
          <label className="cardUpdate__label prompt" htmlFor="acctsid">
            {LABEL_ACCOUNT_NUMBER}
          </label>
          <span className="cardUpdate__value">
            <span className="cardUpdate__marker" aria-hidden="true">
              {fieldMarker(fieldErrors.acctsid)}
            </span>
            <input
              id="acctsid"
              data-testid="acctsid"
              type="text"
              ref={focusField === 'acctsid' ? focusRef : undefined}
              className={classNames(
                'charField',
                'charField--acctId',
                fieldErrorClass(fieldErrors.acctsid),
              )}
              value={keyedAccount}
              onChange={(event) => {
                setKeyedAccount(event.target.value);
              }}
              maxLength={11}
              size={11}
              disabled={fetching}
              autoComplete="off"
              aria-invalid={isFieldInError(fieldErrors.acctsid) || undefined}
              aria-describedby={describedBy('acctsid')}
            />
          </span>
          <label className="cardUpdate__label prompt" htmlFor="cardsid">
            {LABEL_CARD_NUMBER}
          </label>
          <span className="cardUpdate__value">
            <span className="cardUpdate__marker" aria-hidden="true">
              {fieldMarker(fieldErrors.cardsid)}
            </span>
            <input
              id="cardsid"
              data-testid="cardsid"
              type="text"
              ref={focusField === 'cardsid' ? focusRef : undefined}
              className={classNames('charField', fieldErrorClass(fieldErrors.cardsid))}
              value={keyedCard}
              onChange={(event) => {
                setKeyedCard(event.target.value);
              }}
              maxLength={16}
              size={16}
              disabled={fetching}
              autoComplete="off"
              aria-invalid={isFieldInError(fieldErrors.cardsid) || undefined}
              aria-describedby={describedBy('cardsid')}
            />
          </span>
        </div>
      ) : (
        // Once the record is on display both keys turn ``DFHBMPRF`` — protected — so they
        // are rendered as the stored values they now are, not as entry boxes.
        <dl className="cardUpdate__protected">
          <OutputField
            className="cardUpdate__outputRow"
            label={LABEL_ACCOUNT_NUMBER}
            labelClassName="cardUpdate__label prompt"
            testId="acctsid"
            value={accountId}
            valueClassName="cardUpdate__value label"
            width={11}
          />
          <OutputField
            className="cardUpdate__outputRow"
            label={LABEL_CARD_NUMBER}
            labelClassName="cardUpdate__label prompt"
            testId="cardsid"
            value={cardNumber}
            valueClassName="cardUpdate__value label"
            width={16}
          />
        </dl>
      )}

      <label className="cardUpdate__label prompt" htmlFor="crdname">
        {LABEL_NAME_ON_CARD}
      </label>
      <span className="cardUpdate__value">
        <span className="cardUpdate__marker" aria-hidden="true">
          {fieldMarker(fieldErrors.crdname)}
        </span>
        <input
          id="crdname"
          data-testid="crdname"
          ref={focusField === 'crdname' ? focusRef : undefined}
          className={classNames(fieldErrorClass(fieldErrors.crdname))}
          type="text"
          value={cardName}
          size={50}
          maxLength={50}
          disabled={saving}
          readOnly={detailsProtected}
          aria-invalid={isFieldInError(fieldErrors.crdname) || undefined}
          aria-describedby={describedBy('crdname')}
          onChange={(event) => setCardName(event.target.value)}
        />
      </span>

      <label className="cardUpdate__label prompt" htmlFor="crdstcd">
        {LABEL_CARD_ACTIVE}
      </label>
      <span className="cardUpdate__value">
        <span className="cardUpdate__marker" aria-hidden="true">
          {fieldMarker(fieldErrors.crdstcd)}
        </span>
        <input
          id="crdstcd"
          data-testid="crdstcd"
          ref={focusField === 'crdstcd' ? focusRef : undefined}
          className={classNames(fieldErrorClass(fieldErrors.crdstcd))}
          type="text"
          value={cardActiveStatus}
          size={1}
          maxLength={1}
          disabled={saving}
          readOnly={detailsProtected}
          aria-invalid={isFieldInError(fieldErrors.crdstcd) || undefined}
          aria-describedby={describedBy('crdstcd')}
          onChange={(event) => setCardActiveStatus(event.target.value)}
        />
      </span>

      <label className="cardUpdate__label prompt" htmlFor="expmon">
        {LABEL_EXPIRY_DATE}
      </label>
      <span className="cardUpdate__value">
        <span className="cardUpdate__marker" aria-hidden="true">
          {fieldMarker(fieldErrors.expmon)}
        </span>
        <input
          id="expmon"
          data-testid="expmon"
          ref={focusField === 'expmon' ? focusRef : undefined}
          className={classNames(fieldErrorClass(fieldErrors.expmon))}
          type="text"
          // The mapset gives row 15's two fields one shared caption, so each needs its
          // own name. Both begin with the visible caption text, which keeps the rendered
          // label contained in the accessible name.
          aria-label="Expiry Date Month"
          value={expiryMonth}
          size={2}
          maxLength={2}
          disabled={saving}
          readOnly={detailsProtected}
          aria-invalid={isFieldInError(fieldErrors.expmon) || undefined}
          aria-describedby={describedBy('expmon')}
          onChange={(event) => setExpiryMonth(event.target.value)}
        />
        <span className="cardUpdate__separator" aria-hidden="true">
          /
        </span>
        <span className="cardUpdate__marker" aria-hidden="true">
          {fieldMarker(fieldErrors.expyear)}
        </span>
        <input
          id="expyear"
          data-testid="expyear"
          ref={focusField === 'expyear' ? focusRef : undefined}
          className={classNames(fieldErrorClass(fieldErrors.expyear))}
          type="text"
          aria-label="Expiry Date Year"
          value={expiryYear}
          size={4}
          maxLength={4}
          disabled={saving}
          readOnly={detailsProtected}
          aria-invalid={isFieldInError(fieldErrors.expyear) || undefined}
          aria-describedby={describedBy('expyear')}
          onChange={(event) => setExpiryYear(event.target.value)}
        />
      </span>

    </div>
  );
}

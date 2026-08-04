/**
 * CardUpdatePage
 * ==============
 *
 * :purpose: The editable credit-card screen — a 1:1 replacement for the BMS mapset
 *     ``app/bms/COCRDUP.bms`` / symbolic map ``app/cpy-bms/COCRDUP.CPY`` (CICS
 *     transaction ``CCUP``, program ``app/cbl/COCRDUPC.cbl``). It reads the card
 *     addressed by the route, shows the protected account and card numbers, and
 *     lets the operator edit the embossed name, the active status and the expiry
 *     month / year, then validate them (ENTER) and rewrite the record (F5).
 * :output: The rendered screen body. The header, the line-23 message and the
 *     line-24 function keys are published to the shared shell through
 *     :func:`useScreenChrome`, so this page renders none of them itself.
 */
import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { fieldErrorClass, fieldMarker } from '../components/ErrorBanner';
import { PfKeyAction } from '../types';
import type {
  CardDetailResponseDto,
  CardUpdateRequestDto,
  CardUpdateResponseDto,
  FieldErrorMap,
} from '../types';
import { ApiError, getCard, updateCard } from '../api';
import { useApi } from '../hooks';

/** CICS transaction id of this screen (``LIT-THISTRANID``). */
const TRANSACTION_ID = 'CCUP';

/** Legacy program name shown in the header (``LIT-THISPGM``). */
const PROGRAM_NAME = 'COCRDUPC';

/** First header title line. */
const TITLE01 = 'CardDemo';

/** Second header title line and the screen heading (BMS row 4, ``X(26)``). */
const TITLE02 = 'Update Credit Card Details';

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

/** ``PROMPT-FOR-CONFIRMATION`` — clean edits awaiting the F5 rewrite. */
const PROMPT_FOR_CONFIRMATION = 'Changes validated.Press F5 to save';

/** ``CONFIRM-UPDATE-SUCCESS`` — the rewrite committed. */
const CONFIRM_UPDATE_SUCCESS = 'Changes committed to database';

/** ``INFORM-FAILURE`` — the rewrite did not complete. */
const INFORM_FAILURE = 'Changes unsuccessful. Please try again';

/** ``SEARCHED-CARD-NOT-NUMERIC`` — the card key is not 16 digits. */
const SEARCHED_CARD_NOT_NUMERIC = 'Card number if supplied must be a 16 digit number';

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
 * :purpose: The editable map inputs submitted to one edit pass.
 * :field cardNumber: the ``CARDSID`` key carried by the route.
 * :field cardActiveStatus: ``CRDSTCD`` as typed.
 * :field expiryMonth: ``EXPMON`` as typed.
 * :field expiryYear: ``EXPYEAR`` as typed.
 */
interface CardUpdateInputs {
  cardNumber: string;
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
 * :purpose: Run one edit pass over the map inputs, reproducing the
 *     ``1220-EDIT-CARD`` / ``1240-EDIT-CARDSTATUS`` / ``1250-EDIT-EXPIRY-MON`` /
 *     ``1260-EDIT-EXPIRY-YEAR`` order. As in ``COCRDUPC``, only the first failing
 *     edit publishes its message (``IF WS-RETURN-MSG-OFF``) while every failing
 *     field is still flagged.
 * :param inputs: the map inputs to edit.
 * :returns: the :class:`ValidationOutcome` of the pass.
 */
function editMapInputs(inputs: CardUpdateInputs): ValidationOutcome {
  const fieldErrors: FieldErrorMap = {};
  let message = '';

  if (!CARD_NUMBER_PATTERN.test(inputs.cardNumber)) {
    fieldErrors.cardsid = { invalid: true, blank: inputs.cardNumber.length === 0 };
    message = SEARCHED_CARD_NOT_NUMERIC;
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
 * :purpose: The ``COCRDUP`` card-update screen. Reads the card named by the
 *     ``cardNumber`` route segment, edits the mutable fields, and rewrites the
 *     record through ``PUT /cards/{cardNumber}``.
 * :returns: The rendered screen body.
 */
export default function CardUpdatePage(): ReactElement {
  const { cardNumber = '' } = useParams();
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { run: runGetCard, error: fetchError } = useApi(getCard);

  const [card, setCard] = useState<CardDetailResponseDto | null>(null);
  const [cardName, setCardName] = useState('');
  const [cardActiveStatus, setCardActiveStatus] = useState('');
  const [expiryMonth, setExpiryMonth] = useState('');
  const [expiryYear, setExpiryYear] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldErrorMap>({});
  const [changesValidated, setChangesValidated] = useState(false);

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
   * :purpose: Read the card addressed by the route. The key is edited first; a
   *     malformed route segment publishes the card-number message and issues no
   *     request.
   */
  const loadCard = useCallback(async (): Promise<void> => {
    if (!CARD_NUMBER_PATTERN.test(cardNumber)) {
      setFieldErrors({ cardsid: { invalid: true, blank: cardNumber.length === 0 } });
      setErrorMessage(SEARCHED_CARD_NOT_NUMERIC);
      setInfoMessage('');
      return;
    }
    const record = await runGetCard(cardNumber);
    if (record !== undefined) {
      applyCard(record);
      setFieldErrors({});
      setErrorMessage('');
      setInfoMessage(FOUND_CARDS_FOR_ACCOUNT);
      setChangesValidated(false);
    }
  }, [applyCard, cardNumber, runGetCard]);

  useEffect(() => {
    void loadCard();
  }, [loadCard]);

  useEffect(() => {
    if (fetchError !== null) {
      setErrorMessage(resolveServerMessage(fetchError, fetchError.message));
      setInfoMessage('');
    }
  }, [fetchError]);

  /**
   * :purpose: Edit the current map inputs and publish the per-field highlights.
   * :returns: The :class:`ValidationOutcome` of the pass.
   */
  const editCurrentInputs = useCallback((): ValidationOutcome => {
    const outcome = editMapInputs({
      cardNumber,
      cardActiveStatus,
      expiryMonth,
      expiryYear,
    });
    setFieldErrors(outcome.fieldErrors);
    return outcome;
  }, [cardActiveStatus, cardNumber, expiryMonth, expiryYear]);

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
      };
      try {
        const updated: CardUpdateResponseDto = await updateCard(cardNumber, request);
        applyCard(updated);
        setFieldErrors({});
        setChangesValidated(false);
        setErrorMessage('');
        setInfoMessage(CONFIRM_UPDATE_SUCCESS);
      } catch (error) {
        if (error instanceof ApiError && error.isOptimisticLockConflict) {
          setErrorMessage(DATA_WAS_CHANGED_BEFORE_UPDATE);
        } else if (error instanceof ApiError) {
          setErrorMessage(resolveServerMessage(error, INFORM_FAILURE));
        } else {
          setErrorMessage(INFORM_FAILURE);
        }
        setChangesValidated(false);
        setInfoMessage('');
      }
    },
    [applyCard, cardActiveStatus, cardName, cardNumber, expiryMonth, expiryYear],
  );

  /**
   * :purpose: ENTER — edit the map inputs and, when they are clean, ask for the F5
   *     confirmation.
   */
  const handleProcess = useCallback((): void => {
    const outcome = editCurrentInputs();
    if (outcome.message.length > 0) {
      setErrorMessage(outcome.message);
      setInfoMessage('');
      setChangesValidated(false);
      return;
    }
    setErrorMessage('');
    setInfoMessage(PROMPT_FOR_CONFIRMATION);
    setChangesValidated(true);
  }, [editCurrentInputs]);

  /**
   * :purpose: F5 — rewrite the record. When the edits have not been confirmed yet
   *     the edit pass runs first, and the rewrite proceeds only once it is clean.
   */
  const handleSave = useCallback((): void => {
    if (!changesValidated) {
      const outcome = editCurrentInputs();
      if (outcome.message.length > 0) {
        setErrorMessage(outcome.message);
        setInfoMessage('');
        setChangesValidated(false);
        return;
      }
      setChangesValidated(true);
    }
    if (card === null) {
      setErrorMessage('');
      setInfoMessage(PROMPT_FOR_CONFIRMATION);
      return;
    }
    void saveChanges(card);
  }, [card, changesValidated, editCurrentInputs, saveChanges]);

  /** :purpose: F3 — leave the screen for the card list. */
  const handleExit = useCallback((): void => {
    navigate('/cards');
  }, [navigate]);

  /** :purpose: F12 — abandon the edits and return to the card detail screen. */
  const handleCancel = useCallback((): void => {
    navigate(`/cards/${cardNumber}`);
  }, [cardNumber, navigate]);

  // Flushed synchronously at commit: the message and the function keys reach the
  // shell in the same frame as the body they belong to.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Process', onActivate: handleProcess },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: handleExit },
      { action: PfKeyAction.PF5, label: 'F5=Save', onActivate: handleSave },
      { action: PfKeyAction.PF12, label: 'F12=Cancel', onActivate: handleCancel },
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
    errorMessage,
    handleCancel,
    handleExit,
    handleProcess,
    handleSave,
    infoMessage,
    setChrome,
  ]);

  const accountNumber = card?.cardAcctId ?? '';

  return (
    <div className="cardUpdate">
      <h3 className="cardUpdate__heading neutral">{TITLE02}</h3>

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
          value={accountNumber}
          readOnly
          size={11}
          maxLength={11}
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
          className={classNames(fieldErrorClass(fieldErrors.cardsid))}
          type="text"
          value={cardNumber}
          readOnly
          size={16}
          maxLength={16}
        />
      </span>

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
          type="text"
          value={cardName}
          size={50}
          maxLength={50}
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
          className={classNames(fieldErrorClass(fieldErrors.crdstcd))}
          type="text"
          value={cardActiveStatus}
          size={1}
          maxLength={1}
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
          className={classNames(fieldErrorClass(fieldErrors.expmon))}
          type="text"
          value={expiryMonth}
          size={2}
          maxLength={2}
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
          className={classNames(fieldErrorClass(fieldErrors.expyear))}
          type="text"
          aria-label="Expiry Year"
          value={expiryYear}
          size={4}
          maxLength={4}
          onChange={(event) => setExpiryYear(event.target.value)}
        />
      </span>

      <span className="cardUpdate__actions">
        <button type="button" onClick={handleSave}>
          Save
        </button>
      </span>
    </div>
  );
}


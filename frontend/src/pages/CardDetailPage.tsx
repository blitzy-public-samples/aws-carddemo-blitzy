/**
 * CardDetailPage
 * ==============
 *
 * :purpose: Read-only credit-card detail screen — the SPA replacement for the BMS
 *     mapset ``app/bms/COCRDSL.bms`` / symbolic map ``app/cpy-bms/COCRDSL.CPY``
 *     (CICS transaction ``CCDL``, program ``app/cbl/COCRDSLC.cbl``). Accepts the
 *     ``ACCTSID`` and ``CARDSID`` search filters, sends both as the composite
 *     selection ``COCRDSLC`` edits, reads one card through
 *     ``GET /cards/{cardNumber}``, and displays the embossed name, the
 *     active-status flag, and the expiry month / year.
 * :output: The rendered screen body. The line-1/2 header, the line-23 message and
 *     the line-24 function-key legend are published to the shared shell through
 *     ``useScreenChrome`` and are NOT rendered here.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import OutputField from '../components/OutputField';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, SCREEN_NAMES } from '../types';

/**
 * Row-4 screen name of ``app/bms/COCRDSL.bms``, rendered as the screen's own
 * body heading. The two header title lines are the shared ``COTTL01Y`` pair.
 */
const SCREEN_NAME = SCREEN_NAMES.COCRDSL;
import type { CardDetailResponseDto } from '../types';
import { getCard } from '../api';
import { useApi, useFocusOnChange, useInitialFocus } from '../hooks';
import { readCardSelection } from './cardSelection';

/** CICS transaction id of this screen (``LIT-THISTRANID``). */
const TRANSACTION_ID = 'CCDL';

/** Legacy program name of this screen (``LIT-THISPGM``). */
const PROGRAM_NAME = 'COCRDSLC';

/** ``ACCTSID`` caption (BMS line 7, TURQUOISE). */
const LABEL_ACCOUNT_NUMBER = 'Account Number    :';

/** ``CARDSID`` caption (BMS line 8, TURQUOISE). */
const LABEL_CARD_NUMBER = 'Card Number       :';

/** ``CRDNAME`` caption (BMS line 11, TURQUOISE). */
const LABEL_NAME_ON_CARD = 'Name on card      :';

/** ``CRDSTCD`` caption (BMS line 13, TURQUOISE). */
const LABEL_CARD_ACTIVE = 'Card Active Y/N   :';

/** ``EXPMON`` / ``EXPYEAR`` caption (BMS line 15, TURQUOISE). */
const LABEL_EXPIRY_DATE = 'Expiry Date       :';

/**
 * :purpose: ``COCRDSLC`` ``FOUND-CARDS-FOR-ACCOUNT`` info message, set once the keyed
 *     read succeeds (``COCRDSLC.cbl:L129-130``). The three leading spaces are part of
 *     the literal.
 */
const FOUND_CARDS_FOR_ACCOUNT = '   Displaying requested details';

/**
 * :purpose: ``COCRDSLC`` ``WS-PROMPT-FOR-INPUT`` info message, the default whenever no
 *     other info message was set (``COCRDSLC.cbl:L131-132, L490-492``).
 */
const PROMPT_FOR_INPUT = 'Please enter Account and Card Number';

/** ENTER entry of the line-24 legend (BMS ``FKEYS``). */
const PF_ENTER_LABEL = 'ENTER=Search Cards';

/** PF3 entry of the line-24 legend (BMS ``FKEYS``). */
const PF_EXIT_LABEL = 'F3=Exit';

/** Declared width of ``ACCTSID`` (``PIC X(11)``). */
const ACCOUNT_FILTER_LENGTH = 11;

/** Declared width of ``CARDSID`` (``PIC X(16)``). */
const CARD_NUMBER_LENGTH = 16;

/** Declared width of ``CRDNAME`` (``PIC X(50)``). */
const CARD_NAME_LENGTH = 50;

/** Declared width of ``CRDSTCD`` (``PIC X(1)``). */
const CARD_STATUS_LENGTH = 1;

/** ``SEARCHED-ACCT-ZEROES`` / ``SEARCHED-ACCT-NOT-NUMERIC``. */
const MSG_ACCOUNT_NON_ZERO_11 = 'Account number must be a non zero 11 digit number';

/** ``SEARCHED-CARD-NOT-NUMERIC``. */
const MSG_CARD_16_DIGITS = 'Card number if supplied must be a 16 digit number';

/** ``2210-EDIT-ACCOUNT`` non-numeric filter literal. */
const MSG_ACCOUNT_FILTER_11_DIGITS = 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/** ``2220-EDIT-CARD`` non-numeric filter literal. */
const MSG_CARD_FILTER_16_DIGITS = 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';

/**
 * :purpose: The control each ``COCRDSLC`` edit faults for the value it rejected. A
 *     failed read reports an absent record rather than a rejected value, so its
 *     message is absent and marks neither control invalid.
 */
const FAULTED_FIELD_BY_MESSAGE: Readonly<Record<string, 'acctsid' | 'cardsid'>> = {
  [MSG_ACCOUNT_NON_ZERO_11]: 'acctsid',
  [MSG_ACCOUNT_FILTER_11_DIGITS]: 'acctsid',
  [MSG_CARD_16_DIGITS]: 'cardsid',
  [MSG_CARD_FILTER_16_DIGITS]: 'cardsid',
};

/** ``2200-EDIT-MAP-INPUTS`` treats ``*`` as "filter not supplied". */
const FILTER_WILDCARD = '*';

/** Route of the card list screen (``COCRDLI`` / ``CCLI``), the PF3 target. */
const CARD_LIST_ROUTE = '/cards';

/** A COBOL ``IS NUMERIC`` field: digits only. */
const DIGITS_ONLY = /^[0-9]+$/;

/** A COBOL zero-valued numeric field. */
const ZEROS_ONLY = /^0+$/;

/** ``CARD-EXPIRY-YEAR`` occupies the first four characters of ``YYYY-MM-DD``. */
const EXPIRY_YEAR_PATTERN = /^[0-9]{4}$/;

/** ``CARD-EXPIRY-MONTH`` occupies characters 6-7 of ``YYYY-MM-DD``. */
const EXPIRY_MONTH_PATTERN = /^[0-9]{2}$/;

/**
 * :purpose: Reduce a raw filter entry to its comparable value, mapping the legacy
 *     ``*`` wildcard and blanks onto "not supplied".
 * :param value: The raw ``ACCTSID`` / ``CARDSID`` entry.
 * :returns: The trimmed filter, or an empty string when not supplied.
 */
function normalizeFilter(value: string): string {
  const trimmed = value.trim();
  return trimmed === FILTER_WILDCARD ? '' : trimmed;
}

/**
 * :purpose: Reproduce the ``COCRDSLC`` field edits in their legacy order —
 *     ``2210-EDIT-ACCOUNT`` before ``2220-EDIT-CARD``, first message wins. The
 *     account is an optional filter; the card number is the read key.
 * :param accountFilter: The raw ``ACCTSID`` entry.
 * :param cardFilter: The raw ``CARDSID`` entry.
 * :returns: The line-23 message, or an empty string when the search may proceed.
 */
function validateSearchFilters(accountFilter: string, cardFilter: string): string {
  const account = normalizeFilter(accountFilter);
  const card = normalizeFilter(cardFilter);

  if (account !== '') {
    if (!DIGITS_ONLY.test(account)) {
      return MSG_ACCOUNT_FILTER_11_DIGITS;
    }
    if (account.length !== ACCOUNT_FILTER_LENGTH || ZEROS_ONLY.test(account)) {
      return MSG_ACCOUNT_NON_ZERO_11;
    }
  }

  if (card === '') {
    return MSG_CARD_16_DIGITS;
  }
  if (!DIGITS_ONLY.test(card)) {
    return MSG_CARD_FILTER_16_DIGITS;
  }
  if (card.length !== CARD_NUMBER_LENGTH || ZEROS_ONLY.test(card)) {
    return MSG_CARD_16_DIGITS;
  }

  return '';
}

/**
 * :purpose: Split ``cardExpiraionDate`` (``YYYY-MM-DD``, legacy misspelling
 *     preserved) into the ``EXPMON`` and ``EXPYEAR`` display fields, positionally
 *     as ``CARD-EXPIRAION-DATE-X`` is redefined.
 * :param cardExpiraionDate: The wire expiration date, absent when no card is loaded.
 * :returns: The two-character month and four-character year; either is an empty
 *     string when the corresponding positions are not numeric.
 */
function splitExpiraionDate(cardExpiraionDate: string | undefined): {
  expiryMonth: string;
  expiryYear: string;
} {
  const value = cardExpiraionDate ?? '';
  const year = value.slice(0, 4);
  const month = value.slice(5, 7);
  return {
    expiryMonth: EXPIRY_MONTH_PATTERN.test(month) ? month : '',
    expiryYear: EXPIRY_YEAR_PATTERN.test(year) ? year : '',
  };
}

/**
 * :purpose: The card detail screen.
 * :returns: The rendered screen body.
 */
export default function CardDetailPage(): ReactElement {
  const location = useLocation();
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  // The card list hands the composite selection over in the router location
  // state, so neither value is ever part of this screen's URL.
  const { cardNumber: selectedCardNumber, accountId: selectedAccountId } =
    readCardSelection(location.state);

  const [acctInput, setAcctInput] = useState(selectedAccountId);
  const [cardInput, setCardInput] = useState(selectedCardNumber);
  const [validationMessage, setValidationMessage] = useState('');

  const { data, loading, error, run } = useApi(getCard);
  const card: CardDetailResponseDto | null = data;

  // A client-side edit failure keeps the screen on its own literal; otherwise the
  // service's normalized message is surfaced verbatim.
  const errorMessage = validationMessage !== '' ? validationMessage : (error?.message ?? '');

  // 'SETUP MESSAGE' (L489-492): a successful keyed read reports the details are shown,
  // and with no other info message the screen falls back to prompting for both keys.
  const infoMessage = card !== null ? FOUND_CARDS_FOR_ACCOUNT : PROMPT_FOR_INPUT;

  // COCRDSL marks ACCTSID ``ATTRB=(FSET,IC,NORM,UNPROT)``, so the cursor starts there
  // and returns there whenever the screen reports a new outcome.
  const accountFilterRef = useInitialFocus<HTMLInputElement>();
  // The control the current line-23 message faults, mirroring ``MOVE -1 TO <field>L``.
  const faultedField = FAULTED_FIELD_BY_MESSAGE[errorMessage] ?? null;

  useFocusOnChange(errorMessage === '' ? null : errorMessage, accountFilterRef);

  const runSearch = useCallback(
    (accountFilter: string, cardFilter: string): void => {
      const message = validateSearchFilters(accountFilter, cardFilter);
      setValidationMessage(message);
      if (message === '') {
        // Both edited values are sent: COCRDSLC reads by card number and
        // qualifies the record with ACCTSID, so dropping the account would
        // widen the selection the screen collected.
        const account = normalizeFilter(accountFilter);
        void run(
          normalizeFilter(cardFilter),
          account === '' ? undefined : account,
        );
      }
    },
    [run],
  );

  const handleSearch = useCallback((): void => {
    runSearch(acctInput, cardInput);
  }, [runSearch, acctInput, cardInput]);

  // Hand-over read: a selection carried in from the card list displays that card
  // on entry and seeds both search fields with it.
  useEffect(() => {
    if (selectedCardNumber === '') {
      return;
    }
    setAcctInput(selectedAccountId);
    setCardInput(selectedCardNumber);
    runSearch(selectedAccountId, selectedCardNumber);
  }, [selectedCardNumber, selectedAccountId, runSearch]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_ENTER_LABEL, onActivate: handleSearch },
      {
        action: PfKeyAction.PF3,
        label: PF_EXIT_LABEL,
        onActivate: () => {
          void navigate(CARD_LIST_ROUTE);
        },
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
      busy: loading,
    });
  }, [setChrome, errorMessage, infoMessage, loading, handleSearch, navigate]);

  const { expiryMonth, expiryYear } = splitExpiraionDate(card?.cardExpiraionDate);
  const cardName = (card?.cardEmbossedName ?? '').slice(0, CARD_NAME_LENGTH);
  const cardStatus = (card?.cardActiveStatus ?? '').slice(0, CARD_STATUS_LENGTH);

  return (
    <section aria-labelledby="card-detail-heading">
      <h2 className="neutral screenTitle" id="card-detail-heading">
        {SCREEN_NAME}
      </h2>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          handleSearch();
        }}
      >
        <div>
          <label className="prompt" htmlFor="acctsid">
            {LABEL_ACCOUNT_NUMBER}
          </label>{' '}
          <input
            ref={accountFilterRef}
            className="field"
            data-testid="acctsid"
            {...invalidFieldProps(faultedField === 'acctsid')}
            id="acctsid"
            name="acctsid"
            type="text"
            inputMode="numeric"
            autoComplete="off"
            maxLength={ACCOUNT_FILTER_LENGTH}
            size={ACCOUNT_FILTER_LENGTH}
            value={acctInput}
            onChange={(event) => {
              setAcctInput(event.target.value);
            }}
          />
        </div>
        <div>
          <label className="prompt" htmlFor="cardsid">
            {LABEL_CARD_NUMBER}
          </label>{' '}
          <input
            className="field"
            data-testid="cardsid"
            {...invalidFieldProps(faultedField === 'cardsid')}
            id="cardsid"
            name="cardsid"
            type="text"
            inputMode="numeric"
            autoComplete="off"
            maxLength={CARD_NUMBER_LENGTH}
            size={CARD_NUMBER_LENGTH}
            value={cardInput}
            onChange={(event) => {
              setCardInput(event.target.value);
            }}
          />
        </div>
      </form>

      <dl>
        <OutputField
          label={LABEL_NAME_ON_CARD}
          testId="crdname"
          value={cardName}
          valueClassName="neutral"
        />
        <OutputField
          label={LABEL_CARD_ACTIVE}
          testId="crdstcd"
          value={cardStatus}
          valueClassName="neutral"
        />
        <OutputField
          label={LABEL_EXPIRY_DATE}
          testId="expiry-date"
          value={
            <>
              <span data-testid="expmon">{expiryMonth}</span>/
              <span data-testid="expyear">{expiryYear}</span>
            </>
          }
          valueClassName="neutral"
        />
      </dl>
    </section>
  );
}

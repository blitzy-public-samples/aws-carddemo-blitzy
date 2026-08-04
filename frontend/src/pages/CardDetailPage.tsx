/**
 * CardDetailPage
 * ==============
 *
 * :purpose: Read-only credit-card detail screen — the SPA replacement for the BMS
 *     mapset ``app/bms/COCRDSL.bms`` / symbolic map ``app/cpy-bms/COCRDSL.CPY``
 *     (CICS transaction ``CCDL``, program ``app/cbl/COCRDSLC.cbl``). Accepts the
 *     ``ACCTSID`` and ``CARDSID`` search filters, reads one card through
 *     ``GET /cards/{cardNumber}``, and displays the embossed name, the
 *     active-status flag, and the expiry month / year.
 * :output: The rendered screen body. The line-1/2 header, the line-23 message and
 *     the line-24 function-key legend are published to the shared shell through
 *     ``useScreenChrome`` and are NOT rendered here.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { CardDetailResponseDto } from '../types';
import { getCard } from '../api';
import { useApi } from '../hooks';

/** CICS transaction id of this screen (``LIT-THISTRANID``). */
const TRANSACTION_ID = 'CCDL';

/** Legacy program name of this screen (``LIT-THISPGM``). */
const PROGRAM_NAME = 'COCRDSLC';

/** First header title line. */
const TITLE01 = 'CardDemo';

/** Second header title line, and the body heading (BMS line 4). */
const TITLE02 = 'View Credit Card Detail';

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
  const { cardNumber } = useParams();
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  const [acctInput, setAcctInput] = useState('');
  const [cardInput, setCardInput] = useState(cardNumber ?? '');
  const [validationMessage, setValidationMessage] = useState('');

  const { data, loading, error, run } = useApi(getCard);
  const card: CardDetailResponseDto | null = data;

  // A client-side edit failure keeps the screen on its own literal; otherwise the
  // service's normalized message is surfaced verbatim.
  const errorMessage = validationMessage !== '' ? validationMessage : (error?.message ?? '');

  const runSearch = useCallback(
    (accountFilter: string, cardFilter: string): void => {
      const message = validateSearchFilters(accountFilter, cardFilter);
      setValidationMessage(message);
      if (message === '') {
        void run(normalizeFilter(cardFilter));
      }
    },
    [run],
  );

  const handleSearch = useCallback((): void => {
    runSearch(acctInput, cardInput);
  }, [runSearch, acctInput, cardInput]);

  // Route-driven read: /cards/:cardNumber displays that card on entry, and keeps
  // the CARDSID filter in step when the route parameter changes.
  useEffect(() => {
    const routeCardNumber = cardNumber ?? '';
    if (routeCardNumber === '') {
      return;
    }
    setCardInput(routeCardNumber);
    runSearch('', routeCardNumber);
  }, [cardNumber, runSearch]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_ENTER_LABEL, onActivate: handleSearch },
      {
        action: PfKeyAction.PF3,
        label: PF_EXIT_LABEL,
        onActivate: () => {
          navigate(CARD_LIST_ROUTE);
        },
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: TITLE01,
      title02: TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [setChrome, errorMessage, handleSearch, navigate]);

  const { expiryMonth, expiryYear } = splitExpiraionDate(card?.cardExpiraionDate);
  const cardName = (card?.cardEmbossedName ?? '').slice(0, CARD_NAME_LENGTH);
  const cardStatus = (card?.cardActiveStatus ?? '').slice(0, CARD_STATUS_LENGTH);

  return (
    <section aria-labelledby="card-detail-heading" aria-busy={loading}>
      <h2 className="neutral screenTitle" id="card-detail-heading">
        {TITLE02}
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
            className="field"
            data-testid="acctsid"
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
        <div>
          <dt className="prompt">{LABEL_NAME_ON_CARD}</dt>
          <dd className="neutral" data-testid="crdname">
            {cardName}
          </dd>
        </div>
        <div>
          <dt className="prompt">{LABEL_CARD_ACTIVE}</dt>
          <dd className="neutral" data-testid="crdstcd">
            {cardStatus}
          </dd>
        </div>
        <div>
          <dt className="prompt">{LABEL_EXPIRY_DATE}</dt>
          <dd className="neutral" data-testid="expiry-date">
            <span data-testid="expmon">{expiryMonth}</span>/
            <span data-testid="expyear">{expiryYear}</span>
          </dd>
        </div>
      </dl>
    </section>
  );
}

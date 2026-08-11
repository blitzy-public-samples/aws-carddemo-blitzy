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
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import {
  fieldErrorClass,
  fieldMarker,
  invalidFieldProps,
} from '../components/ErrorBanner';
import OutputField from '../components/OutputField';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, SCREEN_NAMES } from '../types';

/**
 * Row-4 screen name of ``app/bms/COCRDSL.bms``, rendered as the screen's own
 * body heading. The two header title lines are the shared ``COTTL01Y`` pair.
 */
const SCREEN_NAME = SCREEN_NAMES.COCRDSL;
import type { CardDetailResponseDto, FieldErrorMap } from '../types';
import { getCard } from '../api';
import {
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
} from '../hooks';
import { readCardSelection, resolveExitRoute } from './cardSelection';

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

/** ``WS-PROMPT-FOR-ACCT`` (COCRDSLC L138-139) -- ``2210-EDIT-ACCOUNT`` found no account key. */
const MSG_ACCOUNT_NOT_PROVIDED = 'Account number not provided';

/** ``WS-PROMPT-FOR-CARD`` (COCRDSLC L140-141) -- ``2220-EDIT-CARD`` found no card key. */
const MSG_CARD_NOT_PROVIDED = 'Card number not provided';

/**
 * ``NO-SEARCH-CRITERIA-RECEIVED`` — the ``2200-EDIT-MAP-INPUTS`` cross-field test that
 * runs after both field edits and, being unguarded by ``WS-RETURN-MSG-OFF``, overwrites
 * whichever prompt they left when NEITHER key was supplied (``COCRDSLC.cbl:L637-639``).
 */
const MSG_NO_SEARCH_CRITERIA = 'No input received';

/**
 * ``2210-EDIT-ACCOUNT`` non-numeric filter literal, MOVEd directly. ``COCRDSLC`` also
 * declares ``SEARCHED-ACCT-NOT-NUMERIC`` ('Account number must be a non zero 11 digit
 * number') but never SETs it, so this upper-case form is the only text the program can
 * put on line 23 for a rejected account filter.
 */
const MSG_ACCOUNT_FILTER_11_DIGITS = 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER';

/**
 * ``2220-EDIT-CARD`` non-numeric filter literal, MOVEd directly for the same reason:
 * ``SEARCHED-CARD-NOT-NUMERIC`` is an unreachable 88-level.
 */
const MSG_CARD_FILTER_16_DIGITS = 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER';


/**
 * :purpose: The control each ``COCRDSLC`` edit faults for the value it rejected. A
 *     failed read reports an absent record rather than a rejected value, so its
 *     message is absent and marks neither control invalid.
 */
const FAULTED_FIELDS_BY_MESSAGE: Readonly<Record<string, Readonly<FieldErrorMap>>> = {
  // ``1300-SETUP-SCREEN-ATTRS`` distinguishes the two fault shapes: a BLANK field takes
  // ``MOVE '*' TO <field>O`` as well as ``MOVE DFHRED TO <field>C``, while a NOT-OK
  // field takes the colour alone -- it already holds the value the operator typed.
  [MSG_ACCOUNT_NOT_PROVIDED]: { acctsid: { invalid: false, blank: true } },
  [MSG_ACCOUNT_FILTER_11_DIGITS]: { acctsid: { invalid: true, blank: false } },
  [MSG_CARD_NOT_PROVIDED]: { cardsid: { invalid: false, blank: true } },
  [MSG_CARD_FILTER_16_DIGITS]: { cardsid: { invalid: true, blank: false } },
  // Both keys are absent, so BOTH controls are faulted: the paragraph reddens them with
  // four INDEPENDENT ``IF``s -- not an EVALUATE -- so FLG-ACCTFILTER-BLANK and
  // FLG-CARDFILTER-BLANK each fault their own field and a both-blank send marks the pair.
  [MSG_NO_SEARCH_CRITERIA]: {
    acctsid: { invalid: false, blank: true },
    cardsid: { invalid: false, blank: true },
  },
};

/**
 * :purpose: The control that receives the cursor for each line-23 message. Unlike the
 *     colour rule this IS an ``EVALUATE`` with first-match-wins, so a both-blank send
 *     places the cursor on ``ACCTSID`` -- the account conditions are tested first --
 *     while a card-only fault places it on ``CARDSID`` (``MOVE -1 TO CARDSIDL``).
 *     ``WHEN OTHER`` homes the cursor on ``ACCTSID``.
 */
const CURSOR_FIELD_BY_MESSAGE: Readonly<Record<string, 'acctsid' | 'cardsid'>> = {
  [MSG_ACCOUNT_NOT_PROVIDED]: 'acctsid',
  [MSG_ACCOUNT_FILTER_11_DIGITS]: 'acctsid',
  [MSG_CARD_NOT_PROVIDED]: 'cardsid',
  [MSG_CARD_FILTER_16_DIGITS]: 'cardsid',
  // Both filters blank faults the account, the field 2210-EDIT-ACCOUNT rejected first
  // and the one ACCTSID's IC attribute already offers the cursor to.
  [MSG_NO_SEARCH_CRITERIA]: 'acctsid',
};

/** ``2200-EDIT-MAP-INPUTS`` treats ``*`` as "filter not supplied". */
const FILTER_WILDCARD = '*';

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
 * :purpose: Reproduce ``2200-EDIT-MAP-INPUTS`` exactly -- ``2210-EDIT-ACCOUNT``, then
 *     ``2220-EDIT-CARD``, then the cross-field edit. Both keys are mandatory here: each ``Not
 *     supplied`` branch sets ``INPUT-ERROR``, so a search missing either one is refused rather
 *     than widened. Only the first edit to find the message line free publishes its own (``IF
 *     WS-RETURN-MSG-OFF``), except that the cross-field edit's ``SET
 *     NO-SEARCH-CRITERIA-RECEIVED`` is unconditional and therefore overwrites it when BOTH
 *     filters are blank.
 * :param accountFilter: The raw ``ACCTSID`` entry.
 * :param cardFilter: The raw ``CARDSID`` entry.
 * :returns: The line-23 message, or an empty string when the search may proceed.
 * :note: ``CC-ACCT-ID`` is ``PIC X(11)`` and ``CC-CARD-NUM`` ``PIC X(16)``, so COBOL ``IS
 *     NUMERIC`` holds only when every character of the full field width is a digit. A short
 *     numeric entry such as ``123`` is space-padded and therefore fails it, which is why one
 *     test covers both "not numeric" and "not 11 characters".
 */
function validateSearchFilters(accountFilter: string, cardFilter: string): string {
  const account = normalizeFilter(accountFilter);
  const card = normalizeFilter(cardFilter);

  // 2210-EDIT-ACCOUNT runs before 2220-EDIT-CARD and only the FIRST failing edit
  // publishes its message (IF WS-RETURN-MSG-OFF), so an account fault masks a card
  // fault. BOTH keys are required: each edit routes an absent value to SET INPUT-ERROR
  // with its own prompt (WS-PROMPT-FOR-ACCT / WS-PROMPT-FOR-CARD), and a zero-valued
  // field counts as absent (CC-ACCT-ID-N EQUAL ZEROS / CC-CARD-NUM-N EQUAL ZEROS).
  //
  // Each branch reports the literal the program can actually emit for it. The
  // wrong-length case belongs to the non-numeric branch, because CC-ACCT-ID is
  // PIC X(11) and CC-CARD-NUM is PIC X(16): a short entry leaves trailing spaces in the
  // field and IS NOT NUMERIC rejects it. The absent case belongs to the blank branch.
  const accountBlank = account === '' || ZEROS_ONLY.test(account);
  const cardBlank = card === '' || ZEROS_ONLY.test(card);

  // The cross-field test runs LAST and is not guarded by WS-RETURN-MSG-OFF, so when
  // neither key was supplied its literal replaces whichever prompt the field edits set.
  if (accountBlank && cardBlank) {
    return MSG_NO_SEARCH_CRITERIA;
  }

  if (accountBlank) {
    return MSG_ACCOUNT_NOT_PROVIDED;
  }
  if (!DIGITS_ONLY.test(account) || account.length !== ACCOUNT_FILTER_LENGTH) {
    return MSG_ACCOUNT_FILTER_11_DIGITS;
  }

  if (cardBlank) {
    return MSG_CARD_NOT_PROVIDED;
  }
  if (!DIGITS_ONLY.test(card) || card.length !== CARD_NUMBER_LENGTH) {
    return MSG_CARD_FILTER_16_DIGITS;
  }

  // Every edit above returns its own literal, so reaching here means the search may run.
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
  const {
    cardNumber: selectedCardNumber,
    accountId: selectedAccountId,
    from: selectionFrom,
    browse: selectionBrowse,
  } = readCardSelection(location.state);

  const [acctInput, setAcctInput] = useState(selectedAccountId);
  const [cardInput, setCardInput] = useState(selectedCardNumber);
  const [validationMessage, setValidationMessage] = useState('');

  const { data, loading, error, run, reset } = useApi(getCard);
  const card: CardDetailResponseDto | null = data;

  // A client-side edit failure keeps the screen on its own literal; otherwise the
  // service's normalized message is surfaced verbatim.
  const errorMessage = validationMessage !== '' ? validationMessage : (error?.message ?? '');

  // 'SETUP MESSAGE' (L489-492): a successful keyed read reports the details are shown,
  // and with no other info message the screen falls back to prompting for both keys.
  // While an edit is being reported the screen cannot also claim the details are shown —
  // COCRDSLC reaches its 'SETUP MESSAGE' only after a read, so a refused input leaves the
  // prompt standing rather than the previous read's confirmation.
  const infoMessage =
    card !== null && errorMessage === '' ? FOUND_CARDS_FOR_ACCOUNT : PROMPT_FOR_INPUT;

  // COCRDSL marks ACCTSID ``ATTRB=(FSET,IC,NORM,UNPROT)``, so the cursor starts there
  // and returns there whenever the screen reports a new outcome.
  const accountFilterRef = useInitialFocus<HTMLInputElement>();
  const cardFilterRef = useRef<HTMLInputElement>(null);
  // The controls the current line-23 message reddens, and the one that takes the cursor.
  const faultedFields: Readonly<FieldErrorMap> = FAULTED_FIELDS_BY_MESSAGE[errorMessage] ?? {};
  // A read failure reports an absent record rather than a rejected value, so it faults
  // no control; the cursor then follows the source's WHEN OTHER and homes on ACCTSID.
  const cursorField = errorMessage === '' ? null : (CURSOR_FIELD_BY_MESSAGE[errorMessage] ?? 'acctsid');

  // One hook per control, each armed only while that control is the cursor target, so
  // the field the program rejected is the field the cursor lands on.
  useFocusOnChange(cursorField === 'acctsid' ? errorMessage : null, accountFilterRef);
  useFocusOnChange(cursorField === 'cardsid' ? errorMessage : null, cardFilterRef);
  // COCRDSL.bms declares `ACCTSID ATTRB=(FSET,IC,NORM,UNPROT)`: the IC attribute places
  // the cursor on the account key on EVERY `SEND MAP`, including the one that paints a
  // record just read (COCRDSLC L343-348 sends the map straight after 9000-READ-DATA).
  // The entry fields are disabled while that read is in flight, and disabling the focused
  // element blurs it to the document body, so the cursor has to be placed again once the
  // read settles or the operator's next keystrokes land nowhere.
  useFocusOnSettled(loading, accountFilterRef);

  const runSearch = useCallback(
    (accountFilter: string, cardFilter: string): void => {
      const message = validateSearchFilters(accountFilter, cardFilter);
      setValidationMessage(message);
      // ``COCRDSLC`` reaches every send through ``1000-SEND-MAP`` -> ``1100-SCREEN-INIT``,
      // which opens ``MOVE LOW-VALUES TO CCRDSLAO`` (L427-428). The three display fields
      // are therefore blank on the map unless ``9100-GETCARD-BYACCTCARD`` has just moved
      // a record into it -- so a refused edit and an absent record both paint an empty
      // record rather than leaving the previous card's number and embossed name standing
      // beside keys that were never read.
      reset();
      if (message === '') {
        // Both edited values are sent: 9100-GETCARD-BYACCTCARD reads by card number
        // and qualifies the record with ACCTSID, so dropping the account would widen
        // the selection the screen collected. The edits above guarantee both are
        // present here, so neither is ever omitted from the read.
        void run(normalizeFilter(cardFilter), normalizeFilter(accountFilter));
      }
    },
    [reset, run],
  );

  const handleSearch = useCallback((): void => {
    runSearch(acctInput, cardInput);
  }, [runSearch, acctInput, cardInput]);

  // Hand-over read: a selection carried in from the card list displays that card
  // on entry and seeds both search fields with it.
  //
  // The edits are deliberately NOT run here. COCRDSLC's entry branch for this case is
  // commented "COMING FROM CREDIT CARD LIST SCREEN / SELECTION CRITERIA ALREADY
  // VALIDATED": it performs SET INPUT-OK, moves both keys out of the COMMAREA and goes
  // straight to 9000-READ-DATA, never reaching 2200-EDIT-MAP-INPUTS. Only the
  // CDEMO-PGM-REENTER branch -- an AID on a screen already presented -- edits what the
  // operator typed.
  useEffect(() => {
    if (selectedCardNumber === '') {
      return;
    }
    setAcctInput(selectedAccountId);
    setCardInput(selectedCardNumber);
    setValidationMessage('');
    reset();
    void run(selectedCardNumber, selectedAccountId === '' ? undefined : selectedAccountId);
  }, [selectedCardNumber, selectedAccountId, run, reset]);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateSearch = useScreenAction(handleSearch);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_ENTER_LABEL, onActivate: activateSearch },
      {
        action: PfKeyAction.PF3,
        label: PF_EXIT_LABEL,
        onActivate: () => {
          // The browse position handed in travels straight back, so the list screen
          // PF3 returns to is the one the operator left rather than an unfiltered
          // first page. This is COCRDSLC returning the COMMAREA it was passed.
          void navigate(resolveExitRoute(selectionFrom), {
            state: { browse: selectionBrowse },
          });
        },
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      pfKeys,
      // ``COACTVWC``/``COACTUPC``/``COCRDLIC``/``COCRDSLC``/``COCRDUPC`` do not answer an
      // unhandled AID with a message: they ``SET PFK-INVALID TO TRUE``, and when the
      // struck key is not in the valid set they ``SET CCARD-AID-ENTER TO TRUE`` -- the
      // key is REWRITTEN to ENTER and the ENTER path runs.
      onUnhandledKey: activateSearch,
      busy: loading,
    });
  }, [
    activateSearch,
    errorMessage,
    loading,
    navigate,
    selectionBrowse,
    selectionFrom,
    setChrome,
  ]);

  const { expiryMonth, expiryYear } = splitExpiraionDate(card?.cardExpiraionDate);
  const cardName = (card?.cardEmbossedName ?? '').slice(0, CARD_NAME_LENGTH);
  const cardStatus = (card?.cardActiveStatus ?? '').slice(0, CARD_STATUS_LENGTH);

  return (
    <section aria-labelledby="card-detail-heading">
      <h3 className="neutral screenTitle" id="card-detail-heading">
        {SCREEN_NAME}
      </h3>

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
          {/*
            A 3270 locked the keyboard while the host was thinking, so both key fields are
            closed for exactly the interval the read is in flight. It is also what makes
            that interval visible: a disabled control is painted dim with no entry box.
          */}
          <span className="blankMarkerSlot" aria-hidden="true">
            {fieldMarker(faultedFields.acctsid)}
          </span>
          <input
            ref={accountFilterRef}
            className={`field charField charField--acctId ${fieldErrorClass(faultedFields.acctsid)}`.trimEnd()}
            data-testid="acctsid"
            {...invalidFieldProps(faultedFields.acctsid !== undefined)}
            id="acctsid"
            name="acctsid"
            disabled={loading}
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
          <span className="blankMarkerSlot" aria-hidden="true">
            {fieldMarker(faultedFields.cardsid)}
          </span>
          <input
            ref={cardFilterRef}
            className={`field ${fieldErrorClass(faultedFields.cardsid)}`.trimEnd()}
            data-testid="cardsid"
            {...invalidFieldProps(faultedFields.cardsid !== undefined)}
            id="cardsid"
            name="cardsid"
            disabled={loading}
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
          valueClassName="neutral protectedValue--underline"
        />
        <OutputField
          label={LABEL_CARD_ACTIVE}
          testId="crdstcd"
          value={cardStatus}
          valueClassName="neutral protectedValue--underline"
        />
        <OutputField
          label={LABEL_EXPIRY_DATE}
          testId="expiry-date"
          value={
            <>
              <span data-testid="expmon">{expiryMonth}</span>
              <span className="cardUpdate__separator" aria-hidden="true">
                /
              </span>
              <span data-testid="expyear">{expiryYear}</span>
            </>
          }
          valueClassName="neutral protectedValue--underline"
        />
      </dl>

      {/*
        COCRDSL's own INFOMSG field, ``POS=(20,25) COLOR=NEUTRAL LENGTH=40``: a body row
        in neutral, distinct from the line-23 RED ERRMSG region the shell renders.
      */}
      <p
        className="screenNote neutral cardDetail__infoMsg"
        data-testid="card-detail-infomsg"
        role="status"
      >
        {infoMessage}
      </p>
    </section>
  );
}

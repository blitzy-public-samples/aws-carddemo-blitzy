/**
 * TranViewPage
 * ============
 *
 * :purpose: Read-only transaction-detail screen. One-for-one replacement of the
 *     BMS mapset ``app/bms/COTRN01.bms`` (map ``COTRN1A``) and its driving CICS
 *     program ``app/cbl/COTRN01C.cbl`` (transaction ``CT01``). The screen loads
 *     the transaction named by the ``/transactions/:transactionId`` route
 *     parameter — the analogue of the legacy ``CDEMO-CT01-TRN-SELECTED``
 *     drill-through from the transaction list — and also accepts an inline
 *     transaction id (BMS field ``TRNIDIN``, ``X(16)``) to look up another
 *     transaction. Every detail field is a protected (``ATTRB=ASKIP``) display
 *     field, so all thirteen render read-only at their BMS ``LENGTH=`` widths.
 * :output: The screen body only. The shared shell (``Layout``) renders the
 *     header, the line-23 message region and the line-24 function-key bar from
 *     the chrome this page publishes through ``useScreenChrome``.
 */
import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import type { ChangeEvent, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, CCDA_MSG_INVALID_KEY } from '../types';
import type { TranViewResponseDto } from '../types';
import { getTransaction, ApiError } from '../api';
import {
  placeCursor,
  useApi,
  useFocusOnChange,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
} from '../hooks';
import { displayField } from '../components/display';
import OutputField from '../components/OutputField';
import { invalidFieldProps } from '../components/ErrorBanner';

/** Legacy ``PROCESS-ENTER-KEY`` message for a blank transaction id. */
const EMPTY_TRAN_ID_MESSAGE = 'Tran ID can NOT be empty...';

/** Legacy ``READ-TRANSACT-FILE`` message for ``DFHRESP(NOTFND)``. */
const TRANSACTION_NOT_FOUND_MESSAGE = 'Transaction ID NOT found...';

/** HTTP status the backend returns for a missing transaction record. */
const HTTP_NOT_FOUND = 404;

/**
 * :purpose: The decorative separator the mapset draws on line 8 — ``COTRN01.bms`` L94-99
 *     declares it ``LENGTH=70`` ``COLOR=NEUTRAL`` with seventy dashes as its ``INITIAL``
 *     value at column 6, so it is screen text of a fixed character width, not a rule whose
 *     width the layout decides. Rendered as an element whose width the flex column sets, the UA's
 *     ``hr { margin-inline: auto }`` won over the stretch and it collapsed to a dot.
 */
const SEPARATOR_RULE = '-'.repeat(70);

/** Width of the ``TRNIDIN`` entry field and the ``TRNID`` display field. */
const TRAN_ID_WIDTH = 16;

/** ``TRNIDIN`` caption of ``app/bms/COTRN01.bms`` (line 6, TURQUOISE). */
const TRAN_ID_LABEL = 'Enter Tran ID:';

/** Field name of the only unprotected field on the map (BMS ``TRNIDIN``). */
const TRAN_ID_FIELD_NAME = 'TRNIDIN';

/** ENTER entry of the mapset line-24 legend. */
const ENTER_LABEL = 'ENTER=Fetch';

/** PF3 entry of the mapset line-24 legend. */
const BACK_LABEL = 'F3=Back';

/** PF4 entry of the mapset line-24 legend. */
const CLEAR_LABEL = 'F4=Clear';

/** PF5 entry of the mapset line-24 legend. */
const BROWSE_LABEL = 'F5=Browse Tran.';

/** Route of the transaction-list screen (``CT00`` / ``COTRN00C``). */
const TRAN_LIST_ROUTE = '/transactions';

/** Route of the main menu, the PF3 target when no caller screen is recorded. */
const MENU_ROUTE = '/menu';

/**
 * :purpose: Resolve the line-23 message for a failed lookup. A ``404`` yields
 *     the frozen legacy not-found literal; any other failure surfaces the
 *     backend ``ApiErrorResponse.message``.
 * :param error: the normalized request failure, or ``null`` when none.
 * :returns: the message text, or the empty string when there is no failure.
 */
function resolveApiMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '';
  }
  if (error.status === HTTP_NOT_FOUND) {
    return TRANSACTION_NOT_FOUND_MESSAGE;
  }
  return error.body?.message ?? error.message;
}

/**
 * :purpose: The ``COTRN01`` view-transaction screen.
 * :returns: The rendered screen body.
 */
export default function TranViewPage(): ReactElement {
  const { transactionId } = useParams<{ transactionId: string }>();
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { data, error, loading, run, reset } = useApi<TranViewResponseDto, [string]>(getTransaction);

  // BMS TRNIDIN: the only unprotected field on the map.
  const [searchTranId, setSearchTranId] = useState<string>('');
  // Client-side edit result; it takes precedence over a request failure exactly
  // as the legacy blank check short-circuits before READ-TRANSACT-FILE runs.
  const [validationMessage, setValidationMessage] = useState<string>('');

  const errorMessage = validationMessage !== '' ? validationMessage : resolveApiMessage(error);

  // ``COTRN01C`` faults TRNIDIN for the blank-id edit and for a key that is not on
  // file; an infrastructure failure reports neither.
  const faultedTranId =
    errorMessage === EMPTY_TRAN_ID_MESSAGE || errorMessage === TRANSACTION_NOT_FOUND_MESSAGE;

  // COTRN01 marks TRNIDIN ``ATTRB=(FSET,IC,NORM,UNPROT)`` and every COTRN01C path ends
  // with ``MOVE -1 TO TRNIDINL``, so the cursor starts on the entry field, returns there
  // once a lookup settles, and returns there whenever the screen reports an outcome.
  const tranIdRef = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(loading, tranIdRef);
  useFocusOnChange(errorMessage === '' ? null : errorMessage, tranIdRef);

  // Legacy entry path: a transaction id arriving in CDEMO-CT01-TRN-SELECTED is
  // moved into TRNIDIN and PROCESS-ENTER-KEY runs immediately.
  useEffect(() => {
    if (transactionId === undefined || transactionId === '') {
      return;
    }
    setSearchTranId(transactionId);
    setValidationMessage('');
    reset();
    void run(transactionId);
  }, [transactionId, run, reset]);

  const handleSearchTranIdChange = useCallback((event: ChangeEvent<HTMLInputElement>): void => {
    setSearchTranId(event.target.value);
  }, []);

  const handleSearch = useCallback((): void => {
    const enteredTranId = searchTranId.trim();
    if (enteredTranId === '') {
      setValidationMessage(EMPTY_TRAN_ID_MESSAGE);
      // The thirteen display fields are blanked, because the legacy screen shows
      // nothing here either. COTRN01 declares every one of them ``ATTRB=(ASKIP,NORM)``
      // with NO FSET, so their modified-data tags are off and ``RECEIVE MAP`` returns
      // them as LOW-VALUES; this turn moves no value into them (the clearing MOVE and
      // the read are both guarded by ``IF NOT ERR-FLG-ON``), and ``SEND ... ERASE``
      // then paints an empty field for each. Keeping the previous transaction's values
      // on screen beside a fresh error would attribute them to an id nobody entered.
      reset();
      return;
    }
    setValidationMessage('');
    // ``COTRN01C`` L158-170 blanks all thirteen display fields -- TRNIDI, CARDNUMI,
    // TTYPCDI, TCATCDI, TRNSRCI, TRNAMTI, TDESCI, TORIGDTI, TPROCDTI, MIDI, MNAMEI,
    // MCITYI, MZIPI -- BEFORE ``PERFORM READ-TRANSACT-FILE``, and repopulates them at
    // L175-190 inside a SECOND ``IF NOT ERR-FLG-ON``. So a read that fails leaves the
    // map as the blanking MOVE left it. Clearing here rather than after the failure is
    // what stops the previous transaction's values -- its FULL card number among them --
    // from standing beside an id that was never found.
    reset();
    void run(enteredTranId);
  }, [searchTranId, run, reset]);

  /**
   * :purpose: PF3 — leave for the caller screen. ``COTRN01C`` returns to
   *     ``CDEMO-FROM-PROGRAM`` and falls back to ``COMEN01C`` when no caller is
   *     recorded; arriving with a route parameter is this screen's drill-through
   *     from the list, which is the caller in that case.
   */
  const handleExit = useCallback((): void => {
    void navigate(transactionId === undefined || transactionId === '' ? MENU_ROUTE : TRAN_LIST_ROUTE);
  }, [navigate, transactionId]);

  /**
   * :purpose: PF4 — ``CLEAR-CURRENT-SCREEN``: blank the entry field and every display
   *     field, clear the message, and leave the cursor on ``TRNIDIN``.
   */
  const handleClear = useCallback((): void => {
    setSearchTranId('');
    setValidationMessage('');
    reset();
    // PF4 re-sends the map, and every send honours ``ATTRB=IC`` on TRNIDIN. The
    // message-token hook above cannot observe a clear that leaves the message region
    // empty, so this send's cursor placement is performed here; otherwise the cursor
    // stays on whatever activated the key -- the line-24 ``F4`` button after a click.
    placeCursor(tranIdRef.current);
  }, [reset, tranIdRef]);

  /** :purpose: PF5 — ``Browse Tran.``: return to the transaction list (``COTRN00C``). */
  const handleBrowse = useCallback((): void => {
    void navigate(TRAN_LIST_ROUTE);
  }, [navigate]);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COTRN01C`` L128-132) — publish
   *     ``CCDA-MSG-INVALID-KEY`` and re-send the map, whose ``ATTRB=IC`` on TRNIDIN
   *     returns the cursor to the entry field. The displayed record stands: nothing
   *     was re-read and no entered value was rejected.
   */
  const handleUnhandledKey = useCallback((): void => {
    setValidationMessage(CCDA_MSG_INVALID_KEY);
    placeCursor(tranIdRef.current);
  }, [tranIdRef]);

  const activateSearch = useScreenAction(handleSearch);
  const activateExit = useScreenAction(handleExit);
  const activateClear = useScreenAction(handleClear);
  const activateBrowse = useScreenAction(handleBrowse);
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: ENTER_LABEL, onActivate: activateSearch },
      { action: PfKeyAction.PF3, label: BACK_LABEL, onActivate: activateExit },
      { action: PfKeyAction.PF4, label: CLEAR_LABEL, onActivate: activateClear },
      { action: PfKeyAction.PF5, label: BROWSE_LABEL, onActivate: activateBrowse },
    ];
    setChrome({
      transactionId: 'CT01',
      programName: 'COTRN01C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
      onUnhandledKey: activateUnhandledKey,
      busy: loading,
    });
  }, [
    activateBrowse,
    activateClear,
    activateExit,
    activateSearch,
    activateUnhandledKey,
    errorMessage,
    loading,
    setChrome,
  ]);

  return (
    <div className="tranViewPage">
      <h3 className="neutral">View Transaction</h3>

      <div className="screenLine">
        <label className="prompt" htmlFor={TRAN_ID_FIELD_NAME}>
          {TRAN_ID_LABEL}
        </label>{' '}
        <input
          ref={tranIdRef}
          id={TRAN_ID_FIELD_NAME}
          {...invalidFieldProps(faultedTranId)}
          name={TRAN_ID_FIELD_NAME}
          data-testid={TRAN_ID_FIELD_NAME}
          className="field"
          type="text"
          disabled={loading}
          value={searchTranId}
          maxLength={TRAN_ID_WIDTH}
          size={TRAN_ID_WIDTH}
          onChange={handleSearchTranIdChange}
        />
      </div>

      {/*
        COTRN01 row 8 paints a 70-character run of hyphens at column 6, NEUTRAL. It is
        rendered as that literal run rather than as an `hr`: a replaced element carries
        the user agent's own auto inline margins, which inside this flex column collapse
        it to a two-pixel dot. The four sibling rule-bearing screens render the same
        construct through the same shared CSS rule, so no `hr` remains anywhere.
      */}
      <p className="neutral tranView__rule" data-testid="tranViewRule" aria-hidden="true">
        {SEPARATOR_RULE}
      </p>

      <dl className="screenLine">
        <OutputField
          testId="tranId"
          row={1}
          labelCol={6}
          labelWidth={15}
          valueCol={22}
          label="Transaction ID:"
          width={TRAN_ID_WIDTH}
          value={displayField(data?.tranId, TRAN_ID_WIDTH)}
        />
        <OutputField
          testId="tranCardNum"
          row={1}
          labelCol={45}
          labelWidth={12}
          valueCol={58}
          label="Card Number:"
          width={16}
          value={displayField(data?.tranCardNum, 16)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranTypeCd"
          row={1}
          labelCol={6}
          labelWidth={8}
          valueCol={15}
          label="Type CD:"
          width={2}
          value={displayField(data?.tranTypeCd, 2)}
        />
        <OutputField
          testId="tranCatCd"
          row={1}
          labelCol={23}
          labelWidth={12}
          valueCol={36}
          label="Category CD:"
          width={4}
          value={displayField(data?.tranCatCd, 4)}
        />
        <OutputField
          testId="tranSource"
          row={1}
          labelCol={46}
          labelWidth={7}
          valueCol={54}
          label="Source:"
          width={10}
          value={displayField(data?.tranSource, 10)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranDesc"
          row={1}
          labelCol={6}
          labelWidth={12}
          valueCol={19}
          label="Description:"
          width={60}
          value={displayField(data?.tranDesc, 60)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranAmt"
          row={1}
          labelCol={6}
          labelWidth={7}
          valueCol={14}
          label="Amount:"
          width={12}
          value={displayField(data?.tranAmt, 12)}
        />
        <OutputField
          testId="tranOrigTs"
          row={1}
          labelCol={31}
          labelWidth={10}
          valueCol={42}
          label="Orig Date:"
          width={10}
          value={displayField(data?.tranOrigTs, 10)}
        />
        <OutputField
          testId="tranProcTs"
          row={1}
          labelCol={57}
          labelWidth={10}
          valueCol={68}
          label="Proc Date:"
          width={10}
          value={displayField(data?.tranProcTs, 10)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranMerchantId"
          row={1}
          labelCol={6}
          labelWidth={12}
          valueCol={19}
          label="Merchant ID:"
          width={9}
          value={displayField(data?.tranMerchantId, 9)}
        />
        <OutputField
          testId="tranMerchantName"
          row={1}
          labelCol={33}
          labelWidth={14}
          valueCol={48}
          label="Merchant Name:"
          width={30}
          value={displayField(data?.tranMerchantName, 30)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranMerchantCity"
          row={1}
          labelCol={6}
          labelWidth={14}
          valueCol={21}
          label="Merchant City:"
          width={25}
          value={displayField(data?.tranMerchantCity, 25)}
        />
        <OutputField
          testId="tranMerchantZip"
          row={1}
          labelCol={53}
          labelWidth={13}
          valueCol={67}
          label="Merchant Zip:"
          width={10}
          value={displayField(data?.tranMerchantZip, 10)}
        />
      </dl>
    </div>
  );
}

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
import { useCallback, useEffect, useState } from 'react';
import type { ChangeEvent, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { TranViewResponseDto } from '../types';
import { getTransaction, ApiError } from '../api';
import { useApi, useFocusOnChange, useFocusOnSettled, useInitialFocus } from '../hooks';
import { displayField } from '../components/display';
import OutputField from '../components/OutputField';
import { invalidFieldProps } from '../components/ErrorBanner';

/** Legacy ``PROCESS-ENTER-KEY`` message for a blank transaction id. */
const EMPTY_TRAN_ID_MESSAGE = 'Tran ID can NOT be empty...';

/** Legacy ``READ-TRANSACT-FILE`` message for ``DFHRESP(NOTFND)``. */
const TRANSACTION_NOT_FOUND_MESSAGE = 'Transaction ID NOT found...';

/** HTTP status the backend returns for a missing transaction record. */
const HTTP_NOT_FOUND = 404;

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
    void run(transactionId);
  }, [transactionId, run]);

  const handleSearchTranIdChange = useCallback((event: ChangeEvent<HTMLInputElement>): void => {
    setSearchTranId(event.target.value);
  }, []);

  const handleSearch = useCallback((): void => {
    const enteredTranId = searchTranId.trim();
    if (enteredTranId === '') {
      setValidationMessage(EMPTY_TRAN_ID_MESSAGE);
      return;
    }
    setValidationMessage('');
    void run(enteredTranId);
  }, [searchTranId, run]);

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
  }, [reset]);

  /** :purpose: PF5 — ``Browse Tran.``: return to the transaction list (``COTRN00C``). */
  const handleBrowse = useCallback((): void => {
    void navigate(TRAN_LIST_ROUTE);
  }, [navigate]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: ENTER_LABEL, onActivate: handleSearch },
      { action: PfKeyAction.PF3, label: BACK_LABEL, onActivate: handleExit },
      { action: PfKeyAction.PF4, label: CLEAR_LABEL, onActivate: handleClear },
      { action: PfKeyAction.PF5, label: BROWSE_LABEL, onActivate: handleBrowse },
    ];
    setChrome({
      transactionId: 'CT01',
      programName: 'COTRN01C',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
      busy: loading,
    });
  }, [
    setChrome,
    errorMessage,
    handleBrowse,
    handleClear,
    handleSearch,
    handleExit,
    loading,
  ]);

  return (
    <div className="tranViewPage">
      <h2 className="neutral">View Transaction</h2>

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

      <hr className="neutral" />

      <dl className="screenLine">
        <OutputField
          testId="tranId"
          label="Transaction ID:"
          width={TRAN_ID_WIDTH}
          value={displayField(data?.tranId, TRAN_ID_WIDTH)}
        />
        <OutputField
          testId="tranCardNum"
          label="Card Number:"
          width={16}
          value={displayField(data?.tranCardNum, 16)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranTypeCd"
          label="Type CD:"
          width={2}
          value={displayField(data?.tranTypeCd, 2)}
        />
        <OutputField
          testId="tranCatCd"
          label="Category CD:"
          width={4}
          value={displayField(data?.tranCatCd, 4)}
        />
        <OutputField
          testId="tranSource"
          label="Source:"
          width={10}
          value={displayField(data?.tranSource, 10)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranDesc"
          label="Description:"
          width={60}
          value={displayField(data?.tranDesc, 60)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranAmt"
          label="Amount:"
          width={12}
          value={displayField(data?.tranAmt, 12)}
        />
        <OutputField
          testId="tranOrigTs"
          label="Orig Date:"
          width={10}
          value={displayField(data?.tranOrigTs, 10)}
        />
        <OutputField
          testId="tranProcTs"
          label="Proc Date:"
          width={10}
          value={displayField(data?.tranProcTs, 10)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranMerchantId"
          label="Merchant ID:"
          width={9}
          value={displayField(data?.tranMerchantId, 9)}
        />
        <OutputField
          testId="tranMerchantName"
          label="Merchant Name:"
          width={30}
          value={displayField(data?.tranMerchantName, 30)}
        />
      </dl>

      <dl className="screenLine">
        <OutputField
          testId="tranMerchantCity"
          label="Merchant City:"
          width={25}
          value={displayField(data?.tranMerchantCity, 25)}
        />
        <OutputField
          testId="tranMerchantZip"
          label="Merchant Zip:"
          width={10}
          value={displayField(data?.tranMerchantZip, 10)}
        />
      </dl>
    </div>
  );
}

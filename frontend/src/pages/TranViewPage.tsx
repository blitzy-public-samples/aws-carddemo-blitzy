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
import { useNavigate, useParams } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { TranViewResponseDto } from '../types';
import { getTransaction, ApiError } from '../api';
import { useApi } from '../hooks';

/** Legacy ``PROCESS-ENTER-KEY`` message for a blank transaction id. */
const EMPTY_TRAN_ID_MESSAGE = 'Tran ID can NOT be empty...';

/** Legacy ``READ-TRANSACT-FILE`` message for ``DFHRESP(NOTFND)``. */
const TRANSACTION_NOT_FOUND_MESSAGE = 'Transaction ID NOT found...';

/** HTTP status the backend returns for a missing transaction record. */
const HTTP_NOT_FOUND = 404;

/** Width of the ``TRNIDIN`` entry field and the ``TRNID`` display field. */
const TRAN_ID_WIDTH = 16;

/**
 * :purpose: Render a value inside its BMS field width, reproducing the
 *     truncation a COBOL ``MOVE`` into a ``PIC X(n)`` symbolic-map field
 *     performs — notably the 26-character ``TRAN-ORIG-TS`` /
 *     ``TRAN-PROC-TS`` timestamps moved into the ``X(10)`` ``TORIGDT`` /
 *     ``TPROCDT`` fields, which display the ``YYYY-MM-DD`` date portion.
 * :param value: the wire value; numbers are stringified and absent values
 *     become the empty string so no field ever shows ``null`` / ``undefined``.
 * :param width: the BMS ``LENGTH=`` of the target field.
 * :returns: the display text for the field.
 */
function displayValue(value: string | number | null | undefined, width: number): string {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value).slice(0, width);
}

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
 * :purpose: Props for :func:`DetailField`.
 * :param id: control id, also used as the control name.
 * :param label: the BMS caption, rendered verbatim.
 * :param value: the display text.
 * :param width: the BMS ``LENGTH=`` of the field, used as its visible size.
 */
interface DetailFieldProps {
  id: string;
  label: string;
  value: string;
  width: number;
}

/**
 * :purpose: One protected (``ATTRB=ASKIP``) detail field: its turquoise caption
 *     plus the blue read-only value, associated so assistive technology and
 *     tests resolve the value by its caption.
 * :param props: see :class:`DetailFieldProps`.
 * :returns: the rendered caption/value pair.
 */
function DetailField({ id, label, value, width }: DetailFieldProps): ReactElement {
  return (
    <span className="detailField">
      <label className="prompt" htmlFor={id}>
        {label}
      </label>{' '}
      <input
        id={id}
        name={id}
        className="label"
        type="text"
        value={value}
        size={width}
        readOnly
      />
    </span>
  );
}

/**
 * :purpose: The ``COTRN01`` view-transaction screen.
 * :returns: The rendered screen body.
 */
export default function TranViewPage(): ReactElement {
  const { transactionId } = useParams<{ transactionId: string }>();
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();
  const { data, error, run } = useApi<TranViewResponseDto, [string]>(getTransaction);

  // BMS TRNIDIN: the only unprotected field on the map.
  const [searchTranId, setSearchTranId] = useState<string>('');
  // Client-side edit result; it takes precedence over a request failure exactly
  // as the legacy blank check short-circuits before READ-TRANSACT-FILE runs.
  const [validationMessage, setValidationMessage] = useState<string>('');

  const errorMessage = validationMessage !== '' ? validationMessage : resolveApiMessage(error);

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

  const handleExit = useCallback((): void => {
    navigate('/transactions');
  }, [navigate]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Search', onActivate: handleSearch },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: handleExit },
    ];
    setChrome({
      transactionId: 'CT01',
      programName: 'COTRN01C',
      title01: 'CardDemo',
      title02: 'View Transaction',
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [setChrome, errorMessage, handleSearch, handleExit]);

  return (
    <div className="tranViewPage">
      <h3 className="neutral">View Transaction</h3>

      <div className="screenLine">
        <label className="prompt" htmlFor="trnIdIn">
          Search Tran ID:
        </label>{' '}
        <input
          id="trnIdIn"
          name="trnIdIn"
          className="field"
          type="text"
          value={searchTranId}
          maxLength={TRAN_ID_WIDTH}
          size={TRAN_ID_WIDTH}
          onChange={handleSearchTranIdChange}
        />
      </div>

      <hr className="neutral" />

      <div className="screenLine">
        <DetailField
          id="tranId"
          label="Transaction ID:"
          width={TRAN_ID_WIDTH}
          value={displayValue(data?.tranId, TRAN_ID_WIDTH)}
        />
        <DetailField
          id="tranCardNum"
          label="Card Number:"
          width={16}
          value={displayValue(data?.tranCardNum, 16)}
        />
      </div>

      <div className="screenLine">
        <DetailField
          id="tranTypeCd"
          label="Type CD:"
          width={2}
          value={displayValue(data?.tranTypeCd, 2)}
        />
        <DetailField
          id="tranCatCd"
          label="Category CD:"
          width={4}
          value={displayValue(data?.tranCatCd, 4)}
        />
        <DetailField
          id="tranSource"
          label="Source:"
          width={10}
          value={displayValue(data?.tranSource, 10)}
        />
      </div>

      <div className="screenLine">
        <DetailField
          id="tranDesc"
          label="Description:"
          width={60}
          value={displayValue(data?.tranDesc, 60)}
        />
      </div>

      <div className="screenLine">
        <DetailField
          id="tranAmt"
          label="Amount:"
          width={12}
          value={displayValue(data?.tranAmt, 12)}
        />
        <DetailField
          id="tranOrigTs"
          label="Orig Date:"
          width={10}
          value={displayValue(data?.tranOrigTs, 10)}
        />
        <DetailField
          id="tranProcTs"
          label="Proc Date:"
          width={10}
          value={displayValue(data?.tranProcTs, 10)}
        />
      </div>

      <div className="screenLine">
        <DetailField
          id="tranMerchantId"
          label="Merchant ID:"
          width={9}
          value={displayValue(data?.tranMerchantId, 9)}
        />
        <DetailField
          id="tranMerchantName"
          label="Merchant Name:"
          width={30}
          value={displayValue(data?.tranMerchantName, 30)}
        />
      </div>

      <div className="screenLine">
        <DetailField
          id="tranMerchantCity"
          label="Merchant City:"
          width={25}
          value={displayValue(data?.tranMerchantCity, 25)}
        />
        <DetailField
          id="tranMerchantZip"
          label="Merchant Zip:"
          width={10}
          value={displayValue(data?.tranMerchantZip, 10)}
        />
      </div>
    </div>
  );
}

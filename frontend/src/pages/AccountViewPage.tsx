/**
 * AccountViewPage
 * ===============
 *
 * :purpose: Read-only account and customer detail screen, the 1:1 replacement of
 *     the BMS mapset ``app/bms/COACTVW.bms`` (CICS transaction ``CAVW``, program
 *     ``COACTVWC``). An 11-digit account number is either entered in the
 *     ``ACCTSID`` search field or supplied by the ``/accounts/:accountId`` route,
 *     validated with the ``COACTVWC`` account-filter edit, then fetched through
 *     ``GET /accounts/{id}`` and displayed under the BMS captions.
 * :output: The rendered screen body. The header, the line-23 message and the
 *     line-24 function-key bar are published to the shared ``Layout`` chrome
 *     rather than rendered here.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { AccountViewResponseDto } from '../types';
import { getAccount } from '../api';
import { useApi } from '../hooks';

/**
 * :purpose: Line-23 message rejecting an account number that is not an 11-digit
 *     non-zero value (``COACTVWC`` ``SEARCHED-ACCT-ZEROES`` /
 *     ``SEARCHED-ACCT-NOT-NUMERIC``).
 */
const ACCOUNT_NUMBER_ERROR = 'Account number must be a non zero 11 digit number';

/** :purpose: Leading mask rendered in place of the Social Security number digits. */
const SSN_MASK_PREFIX = '***-**-';

/** :purpose: An account number is exactly eleven numeric characters. */
const ACCOUNT_ID_PATTERN = /^\d{11}$/u;

/** :purpose: An all-zero account number is rejected like a non-numeric one. */
const ZERO_ACCOUNT_PATTERN = /^0+$/u;

/**
 * :purpose: Apply the ``COACTVWC`` 2210-EDIT-ACCOUNT filter edit to an entered
 *     account number: eleven numeric characters and not zero.
 * :param value: the account number exactly as entered or routed.
 * :returns: ``true`` when the value is an 11-digit non-zero account number.
 */
function isValidAccountId(value: string): boolean {
  return ACCOUNT_ID_PATTERN.test(value) && !ZERO_ACCOUNT_PATTERN.test(value);
}

/**
 * :purpose: Coerce a fetched value to display text, keeping an absent value blank.
 * :param value: the value taken from the fetched record.
 * :returns: the display text, or an empty string when the value is absent.
 */
function displayText(value: string | number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value);
}

/**
 * :purpose: Mask a Social Security number for display, retaining only its last
 *     four digits.
 * :param value: the Social Security number as received from the service.
 * :returns: the masked number, or an empty string when it carries no digits.
 */
function maskSsn(value: string | null | undefined): string {
  const digits = displayText(value).replace(/\D/gu, '');
  return digits === '' ? '' : `${SSN_MASK_PREFIX}${digits.slice(-4)}`;
}

/**
 * :purpose: Props for :func:`DetailField`.
 * :param label: the exact BMS caption; empty for the second address line, which
 *     the mapset leaves uncaptioned.
 * :param value: the read-only display text.
 * :param testId: stable ``data-testid`` of the value cell.
 * :param labelledBy: id of the caption naming this cell when it carries none of
 *     its own.
 */
interface DetailFieldProps {
  label: string;
  value: string;
  testId: string;
  labelledBy?: string;
}

/**
 * :purpose: Render one read-only caption / value pair of the account view.
 * :param props: see :class:`DetailFieldProps`.
 * :returns: the rendered caption / value pair.
 */
function DetailField({ label, value, testId, labelledBy }: DetailFieldProps): ReactElement {
  const captionId = `${testId}-label`;
  return (
    <div className="accountView__field">
      <dt className="prompt" id={captionId}>
        {label}
      </dt>
      <dd className="field" data-testid={testId} aria-labelledby={labelledBy ?? captionId}>
        {value}
      </dd>
    </div>
  );
}

/**
 * :purpose: The account view screen (CICS ``CAVW``, program ``COACTVWC``).
 * :returns: The rendered screen body.
 */
export default function AccountViewPage(): ReactElement {
  const navigate = useNavigate();
  const { accountId } = useParams();
  const { setChrome } = useScreenChrome();
  const { data, error, run } = useApi(getAccount);
  const [acctInput, setAcctInput] = useState<string>(accountId ?? '');
  const [validationMessage, setValidationMessage] = useState<string>('');

  const account: AccountViewResponseDto | null = data;

  /**
   * :purpose: Validate an account number and, when it passes, fetch the joined
   *     account + customer record.
   * :param value: the account number exactly as entered or routed.
   */
  const fetchAccount = useCallback(
    (value: string): void => {
      if (!isValidAccountId(value)) {
        setValidationMessage(ACCOUNT_NUMBER_ERROR);
        return;
      }
      setValidationMessage('');
      void run(value);
    },
    [run],
  );

  useEffect(() => {
    if (accountId === undefined || accountId === '') {
      return;
    }
    setAcctInput(accountId);
    fetchAccount(accountId);
  }, [accountId, fetchAccount]);

  const errorMessage =
    validationMessage !== '' ? validationMessage : displayText(error?.message);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: () => navigate('/menu') },
    ];
    setChrome({
      transactionId: 'CAVW',
      programName: 'COACTVWC',
      title01: 'CardDemo',
      title02: 'View Account',
      errorMessage,
      infoMessage: '',
      pfKeys,
    });
  }, [errorMessage, navigate, setChrome]);

  return (
    <div className="accountView">
      <h2 className="neutral">View Account</h2>

      <form
        className="accountView__search"
        onSubmit={(event) => {
          event.preventDefault();
          fetchAccount(acctInput);
        }}
      >
        <label className="prompt" htmlFor="acctsid">
          Account Number :
        </label>
        <input
          id="acctsid"
          name="acctsid"
          className="field"
          type="text"
          inputMode="numeric"
          autoComplete="off"
          maxLength={11}
          value={acctInput}
          data-testid="acctsid"
          onChange={(event) => setAcctInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              fetchAccount(acctInput);
            }
          }}
        />
      </form>

      <dl className="accountView__details">
        <DetailField
          label="Active Y/N:"
          value={displayText(account?.acctActiveStatus)}
          testId="acct-active-status"
        />
        <DetailField
          label="Opened:"
          value={displayText(account?.acctOpenDate)}
          testId="acct-open-date"
        />
        <DetailField
          label="Credit Limit        :"
          value={displayText(account?.acctCreditLimit)}
          testId="acct-credit-limit"
        />
        <DetailField
          label="Expiry:"
          value={displayText(account?.acctExpiraionDate)}
          testId="acct-expiraion-date"
        />
        <DetailField
          label="Cash credit Limit   :"
          value={displayText(account?.acctCashCreditLimit)}
          testId="acct-cash-credit-limit"
        />
        <DetailField
          label="Reissue:"
          value={displayText(account?.acctReissueDate)}
          testId="acct-reissue-date"
        />
        <DetailField
          label="Current Balance     :"
          value={displayText(account?.acctCurrBal)}
          testId="acct-curr-bal"
        />
        <DetailField
          label="Current Cycle Credit:"
          value={displayText(account?.acctCurrCycCredit)}
          testId="acct-curr-cyc-credit"
        />
        <DetailField
          label="Account Group:"
          value={displayText(account?.acctGroupId)}
          testId="acct-group-id"
        />
        <DetailField
          label="Current Cycle Debit :"
          value={displayText(account?.acctCurrCycDebit)}
          testId="acct-curr-cyc-debit"
        />
      </dl>

      <h3 className="neutral">Customer Details</h3>

      <dl className="accountView__details">
        <DetailField
          label="Customer id  :"
          value={displayText(account?.custId)}
          testId="cust-id"
        />
        <DetailField label="SSN:" value={maskSsn(account?.custSsn)} testId="cust-ssn" />
        <DetailField
          label="Date of birth:"
          value={displayText(account?.custDobYyyyMmDd)}
          testId="cust-dob-yyyy-mm-dd"
        />
        <DetailField
          label="FICO Score:"
          value={displayText(account?.custFicoCreditScore)}
          testId="cust-fico-credit-score"
        />
        <DetailField
          label="First Name"
          value={displayText(account?.custFirstName)}
          testId="cust-first-name"
        />
        <DetailField
          label="Middle Name:"
          value={displayText(account?.custMiddleName)}
          testId="cust-middle-name"
        />
        <DetailField
          label="Last Name :"
          value={displayText(account?.custLastName)}
          testId="cust-last-name"
        />
        <DetailField
          label="Address:"
          value={displayText(account?.custAddrLine1)}
          testId="cust-addr-line-1"
        />
        <DetailField
          label="State"
          value={displayText(account?.custAddrStateCd)}
          testId="cust-addr-state-cd"
        />
        <DetailField
          label=""
          value={displayText(account?.custAddrLine2)}
          testId="cust-addr-line-2"
          labelledBy="cust-addr-line-1-label"
        />
        <DetailField
          label="Zip"
          value={displayText(account?.custAddrZip)}
          testId="cust-addr-zip"
        />
        <DetailField
          label="City"
          value={displayText(account?.custAddrLine3)}
          testId="cust-addr-line-3"
        />
        <DetailField
          label="Country"
          value={displayText(account?.custAddrCountryCd)}
          testId="cust-addr-country-cd"
        />
        <DetailField
          label="Phone 1:"
          value={displayText(account?.custPhoneNum1)}
          testId="cust-phone-num-1"
        />
        <DetailField
          label="Government Issued Id Ref    :"
          value={displayText(account?.custGovtIssuedId)}
          testId="cust-govt-issued-id"
        />
        <DetailField
          label="Phone 2:"
          value={displayText(account?.custPhoneNum2)}
          testId="cust-phone-num-2"
        />
        <DetailField
          label="EFT Account Id:"
          value={displayText(account?.custEftAccountId)}
          testId="cust-eft-account-id"
        />
        <DetailField
          label="Primary Card Holder Y/N:"
          value={displayText(account?.custPriCardHolderInd)}
          testId="cust-pri-card-holder-ind"
        />
      </dl>
    </div>
  );
}

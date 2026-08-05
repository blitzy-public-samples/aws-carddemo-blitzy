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
import { useNavigate, useParams } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { AccountViewResponseDto } from '../types';
import { getAccount } from '../api';
import { useApi, useFocusOnChange, useInitialFocus } from '../hooks';
import { displayText, SSN_MASK_PREFIX } from '../components/display';
import OutputField from '../components/OutputField';

/**
 * :purpose: Line-23 message rejecting an account number that is not an 11-digit
 *     non-zero value (``COACTVWC`` ``SEARCHED-ACCT-ZEROES`` /
 *     ``SEARCHED-ACCT-NOT-NUMERIC``).
 */
const ACCOUNT_NUMBER_ERROR = 'Account number must be a non zero 11 digit number';

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
 * :purpose: The account view screen (CICS ``CAVW``, program ``COACTVWC``).
 * :returns: The rendered screen body.
 */
export default function AccountViewPage(): ReactElement {
  const navigate = useNavigate();
  const { accountId } = useParams();
  const { setChrome } = useScreenChrome();
  const { data, error, loading, run } = useApi(getAccount);
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

  // COACTVW marks ACCTSID ``ATTRB=(FSET,IC,NORM,UNPROT)``, so the cursor rests there
  // when the map is sent and returns there whenever the search is rejected.
  const acctInputRef = useInitialFocus<HTMLInputElement>();
  // ``COACTVWC`` faults ACCTSID only for its own 11-digit edit; a failed read
  // reports an absent record rather than a rejected value.
  const faultedAcctId = validationMessage !== '';

  useFocusOnChange(errorMessage === '' ? null : errorMessage, acctInputRef);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.PF3,
        label: 'F3=Exit',
        onActivate: () => {
          void navigate('/menu');
        },
      },
    ];
    setChrome({
      transactionId: 'CAVW',
      programName: 'COACTVWC',
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage: '',
      pfKeys,
      busy: loading,
    });
  }, [errorMessage, loading, navigate, setChrome]);

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
          {...invalidFieldProps(faultedAcctId)}
          id="acctsid"
          name="acctsid"
          ref={acctInputRef}
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
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Active Y/N:"
          value={displayText(account?.acctActiveStatus)}
          testId="acct-active-status"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Opened:"
          value={displayText(account?.acctOpenDate)}
          testId="acct-open-date"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Credit Limit        :"
          value={displayText(account?.acctCreditLimit)}
          testId="acct-credit-limit"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Expiry:"
          value={displayText(account?.acctExpiraionDate)}
          testId="acct-expiraion-date"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Cash credit Limit   :"
          value={displayText(account?.acctCashCreditLimit)}
          testId="acct-cash-credit-limit"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Reissue:"
          value={displayText(account?.acctReissueDate)}
          testId="acct-reissue-date"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Current Balance     :"
          value={displayText(account?.acctCurrBal)}
          testId="acct-curr-bal"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Current Cycle Credit:"
          value={displayText(account?.acctCurrCycCredit)}
          testId="acct-curr-cyc-credit"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Account Group:"
          value={displayText(account?.acctGroupId)}
          testId="acct-group-id"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Current Cycle Debit :"
          value={displayText(account?.acctCurrCycDebit)}
          testId="acct-curr-cyc-debit"
        />
      </dl>

      <h3 className="neutral">Customer Details</h3>

      <dl className="accountView__details">
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Customer id  :"
          value={displayText(account?.custId)}
          testId="cust-id"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="SSN:"
          value={maskSsn(account?.custSsn)}
          testId="cust-ssn"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Date of birth:"
          value={displayText(account?.custDobYyyyMmDd)}
          testId="cust-dob-yyyy-mm-dd"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="FICO Score:"
          value={displayText(account?.custFicoCreditScore)}
          testId="cust-fico-credit-score"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="First Name"
          value={displayText(account?.custFirstName)}
          testId="cust-first-name"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Middle Name:"
          value={displayText(account?.custMiddleName)}
          testId="cust-middle-name"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Last Name :"
          value={displayText(account?.custLastName)}
          testId="cust-last-name"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Address:"
          value={displayText(account?.custAddrLine1)}
          testId="cust-addr-line-1"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="State"
          value={displayText(account?.custAddrStateCd)}
          testId="cust-addr-state-cd"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label=""
          value={displayText(account?.custAddrLine2)}
          testId="cust-addr-line-2"
          labelledBy="cust-addr-line-1-label"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Zip"
          value={displayText(account?.custAddrZip)}
          testId="cust-addr-zip"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="City"
          value={displayText(account?.custAddrLine3)}
          testId="cust-addr-line-3"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Country"
          value={displayText(account?.custAddrCountryCd)}
          testId="cust-addr-country-cd"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Phone 1:"
          value={displayText(account?.custPhoneNum1)}
          testId="cust-phone-num-1"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Government Issued Id Ref    :"
          value={displayText(account?.custGovtIssuedId)}
          testId="cust-govt-issued-id"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Phone 2:"
          value={displayText(account?.custPhoneNum2)}
          testId="cust-phone-num-2"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="EFT Account Id:"
          value={displayText(account?.custEftAccountId)}
          testId="cust-eft-account-id"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Primary Card Holder Y/N:"
          value={displayText(account?.custPriCardHolderInd)}
          testId="cust-pri-card-holder-ind"
        />
      </dl>
    </div>
  );
}

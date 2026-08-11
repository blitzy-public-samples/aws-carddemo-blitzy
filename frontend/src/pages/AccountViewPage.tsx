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
import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02 } from '../types';
import type { AccountViewResponseDto } from '../types';
import { getAccount } from '../api';
import {
  useApi,
  useFocusOnChange,
  useInitialFocus,
  useFocusOnSettled,
  useScreenAction,
} from '../hooks';
import {
  displayField,
  displayText,
  toSuppressedAmountPicture,
  SSN_MASK_PREFIX,
} from '../components/display';
import OutputField from '../components/OutputField';

/**
 * :purpose: Line-23 message rejecting an account number that is not an eleven-digit
 *     non-zero value — ``COACTVWC`` ``2210-EDIT-ACCOUNT`` L672, verbatim. The double
 *     space after ``must`` and the hyphen in ``non-zero`` are the source literal's own.
 * :note: NOT the 88-level ``SEARCHED-ACCT-ZEROES`` / ``SEARCHED-ACCT-NOT-NUMERIC``
 *     (L125/L127), whose text reads ``'Account number must be a non zero 11 digit
 *     number'``: those condition names appear only on their own declaration lines and
 *     the program never ``SET``s either, so that text is unreachable. The update screen
 *     publishes different text again for the same edit.
 */
const ACCOUNT_NUMBER_ERROR = 'Account Filter must  be a non-zero 11 digit number';

/** :purpose: An account number is exactly eleven numeric characters. */
const ACCOUNT_ID_PATTERN = /^\d{11}$/u;

/** :purpose: An all-zero account number is rejected like a non-numeric one. */
const ZERO_ACCOUNT_PATTERN = /^0+$/u;

/**
 * :purpose: Render one of ``COACTVW``'s five amount fields through the numeric edit
 *     picture its map declares, ``PICOUT='+ZZZ,ZZZ,ZZZ.99'``.
 * :param value: the wire value, absent until an account has been read.
 * :returns: the 15-character edited value, or the empty string while the field is unread --
 *     an unread amount field is BLANK on the terminal, not ``+        0.00``.
 */
function editedAmount(value: string | null | undefined): string {
  const text = displayText(value);
  return text === '' ? '' : toSuppressedAmountPicture(text);
}

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
/**
 * Width of the ``ACSZIPC`` field, ``LENGTH=5`` at ``POS=(17,73)``. ``CUST-ADDR-ZIP`` is
 * ``PIC X(10)``, so the ``MOVE`` into the symbolic-map field truncates and the screen shows
 * the first five characters; rendering all ten overflowed the declared field.
 */
const ZIP_FIELD_WIDTH = 5;

/** Width of the ``ACCTSID`` entry field, ``LENGTH=11`` at ``POS=(5,38)``. */
const ACCOUNT_ID_LENGTH = 11;

/**
 * Class list of the ``ACCTSID`` entry field. ``charField`` sizes the box in character cells
 * so the field occupies exactly the eleven columns ``LENGTH=11`` declares, identically on
 * every screen that carries it.
 */
const ACCOUNT_ID_FIELD_CLASS = 'field charField charField--acctId';

/**
 * ``CSSETATY`` re-entry highlight. ``1300-SETUP-SCREEN-ATTRS`` (``COACTVWC.cbl`` L556-558)
 * moves ``DFHRED`` into ``ACCTSIDC`` whenever ``FLG-ACCTFILTER-NOT-OK`` is set, so a
 * rejected search paints the field itself red as well as the message line.
 */
const FAULTED_FIELD_CLASS = 'fieldError';

export default function AccountViewPage(): ReactElement {
  const navigate = useNavigate();
  const { accountId } = useParams();
  const { setChrome } = useScreenChrome();
  const { data, error, loading, run, reset } = useApi(getAccount);
  const [acctInput, setAcctInput] = useState<string>(accountId ?? '');
  const [validationMessage, setValidationMessage] = useState<string>('');

  const account: AccountViewResponseDto | null = data;

  /**
   * :purpose: Validate an account number and, when it passes, fetch the joined
   *     account + customer record.
   * :param value: the account number exactly as entered or routed.
   * :note: A rejected value clears the displayed record as well as reporting the
   *     error. ``COACTVWC`` reaches its send through ``1000-SEND-MAP``, whose first
   *     step ``1100-SCREEN-INIT`` does ``MOVE LOW-VALUES TO CACTVWAO`` before
   *     repainting, so the failed-edit path leaves the account and customer fields
   *     BLANK rather than showing the previous account's balances beside the newly
   *     typed number.
   */
  const fetchAccount = useCallback(
    (value: string): void => {
      if (!isValidAccountId(value)) {
        reset();
        setValidationMessage(ACCOUNT_NUMBER_ERROR);
        return;
      }
      setValidationMessage('');
      // ``1000-SEND-MAP`` reaches every send through ``1100-SCREEN-INIT``, whose first
      // step is ``MOVE LOW-VALUES TO CACTVWAO`` (L431-432): the output map is blanked
      // and only a successful ``9300-GETACCTDATA-BYACCT`` moves values back into it. So
      // a read that fails paints an empty record, not the previous account's balances
      // and masked PII beside the newly typed number.
      reset();
      void run(value);
    },
    [reset, run],
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
  // ``1300-SETUP-SCREEN-ATTRS`` paints ACCTSID ``DFHRED`` whenever
  // ``FLG-ACCTFILTER-NOT-OK`` is set, and ``9200-GETCARDXREF-BYACCT`` /
  // ``9300-GETACCTDATA-BYACCT`` set that flag on a NOTFND read exactly as
  // ``2210-EDIT-ACCOUNT`` sets it on a rejected value. The blank-filter path
  // paints the field red too. Every rejected search therefore faults the field.
  const faultedAcctId = errorMessage !== '';

  useFocusOnChange(errorMessage === '' ? null : errorMessage, acctInputRef);
  // The account key is disabled while the read is in flight, which blurs it to the
  // document body; `COACTVW.bms` gives ACCTSID the IC attribute on every send, so the
  // cursor is placed again as soon as the read settles.
  useFocusOnSettled(loading, acctInputRef);

  /**
   * :purpose: The ENTER AID of ``COACTVWC``: edit the account filter and read the
   *     record. ``COACTVWC`` L306-314 lists ENTER in its valid AID set and rewrites
   *     every other AID to it, so this one activator serves both.
   */
  const activateEnter = useScreenAction((): void => {
    fetchAccount(acctInput);
  });

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        // Registered but NOT advertised: ``COACTVW.bms`` L372-373 gives line 24 the
        // single literal '  F3=Exit ', so ENTER is a live AID with no legend entry --
        // the effect of a BMS legend field that never names it.
        action: PfKeyAction.Enter,
        label: 'ENTER=Fetch',
        onActivate: activateEnter,
        dark: true,
      },
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
      infoFieldMessage: '',
      pfKeys,
      // COACTVW declares its line-24 legend field COLOR=TURQUOISE, not the YELLOW
      // fifteen of the seventeen mapsets declare.
      pfKeyTone: 'turquoise',
      // ``COACTVWC``/``COACTUPC``/``COCRDLIC``/``COCRDSLC``/``COCRDUPC`` do not answer an
      // unhandled AID with a message: they ``SET PFK-INVALID TO TRUE``, and when the
      // struck key is not in the valid set they ``SET CCARD-AID-ENTER TO TRUE`` -- the
      // key is REWRITTEN to ENTER and the ENTER path runs.
      onUnhandledKey: activateEnter,
      busy: loading,
    });
  }, [activateEnter, errorMessage, loading, navigate, setChrome]);

  return (
    <div className="accountView">
      <h3 className="neutral">View Account</h3>

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
          className={
            faultedAcctId
              ? `${ACCOUNT_ID_FIELD_CLASS} ${FAULTED_FIELD_CLASS}`
              : ACCOUNT_ID_FIELD_CLASS
          }
          // A 3270 locked the keyboard while the host was thinking, so the entry field is
          // closed for exactly the interval the read is in flight. It is also what makes
          // that interval visible: a disabled control is painted dim with no entry box.
          disabled={loading}
          type="text"
          inputMode="numeric"
          autoComplete="off"
          maxLength={ACCOUNT_ID_LENGTH}
          size={ACCOUNT_ID_LENGTH}
          value={acctInput}
          data-testid="acctsid"
          onChange={(event) => setAcctInput(event.target.value)}
        />
      </form>

      <dl className="accountView__details">
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Active Y/N:"
          value={displayText(account?.acctActiveStatus)}
          testId="acct-active-status"
          row={1}
          labelCol={57}
          labelWidth={12}
          valueCol={70}
          width={1}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Opened:"
          value={displayText(account?.acctOpenDate)}
          testId="acct-open-date"
          row={2}
          labelCol={8}
          labelWidth={7}
          valueCol={17}
          width={10}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Credit Limit        :"
          value={editedAmount(account?.acctCreditLimit)}
          testId="acct-credit-limit"
          justifyRight
          row={2}
          labelCol={39}
          labelWidth={21}
          valueCol={61}
          width={15}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Expiry:"
          value={displayText(account?.acctExpiraionDate)}
          testId="acct-expiraion-date"
          row={3}
          labelCol={8}
          labelWidth={7}
          valueCol={17}
          width={10}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Cash credit Limit   :"
          value={editedAmount(account?.acctCashCreditLimit)}
          testId="acct-cash-credit-limit"
          justifyRight
          row={3}
          labelCol={39}
          labelWidth={21}
          valueCol={61}
          width={15}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Reissue:"
          value={displayText(account?.acctReissueDate)}
          testId="acct-reissue-date"
          row={4}
          labelCol={8}
          labelWidth={8}
          valueCol={17}
          width={10}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Current Balance     :"
          value={editedAmount(account?.acctCurrBal)}
          testId="acct-curr-bal"
          justifyRight
          row={4}
          labelCol={39}
          labelWidth={21}
          valueCol={61}
          width={15}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Current Cycle Credit:"
          value={editedAmount(account?.acctCurrCycCredit)}
          testId="acct-curr-cyc-credit"
          justifyRight
          row={5}
          labelCol={39}
          labelWidth={21}
          valueCol={61}
          width={15}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Account Group:"
          value={displayText(account?.acctGroupId)}
          testId="acct-group-id"
          row={6}
          labelCol={8}
          labelWidth={14}
          valueCol={23}
          width={10}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Current Cycle Debit :"
          value={editedAmount(account?.acctCurrCycDebit)}
          testId="acct-curr-cyc-debit"
          justifyRight
          row={6}
          labelCol={39}
          labelWidth={21}
          valueCol={61}
          width={15}
        />
      </dl>

      <h4 className="neutral">Customer Details</h4>

      <dl className="accountView__details">
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Customer id  :"
          value={displayText(account?.custId)}
          testId="cust-id"
          row={1}
          labelCol={8}
          labelWidth={14}
          valueCol={23}
          width={9}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="SSN:"
          value={maskSsn(account?.custSsn)}
          testId="cust-ssn"
          row={1}
          labelCol={49}
          labelWidth={4}
          valueCol={54}
          width={12}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Date of birth:"
          value={displayText(account?.custDobYyyyMmDd)}
          testId="cust-dob-yyyy-mm-dd"
          row={2}
          labelCol={8}
          labelWidth={14}
          valueCol={23}
          width={10}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="FICO Score:"
          value={displayText(account?.custFicoCreditScore)}
          testId="cust-fico-credit-score"
          row={2}
          labelCol={49}
          labelWidth={11}
          valueCol={61}
          width={3}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="First Name"
          value={displayText(account?.custFirstName)}
          testId="cust-first-name"
          row={3}
          labelCol={1}
          labelWidth={10}
          valueCol={1}
          width={25}
          valueRow={4}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Middle Name:"
          value={displayText(account?.custMiddleName)}
          testId="cust-middle-name"
          row={3}
          labelCol={28}
          labelWidth={13}
          valueCol={28}
          width={25}
          valueRow={4}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Last Name :"
          value={displayText(account?.custLastName)}
          testId="cust-last-name"
          row={3}
          labelCol={55}
          labelWidth={12}
          valueCol={55}
          width={25}
          valueRow={4}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Address:"
          value={displayText(account?.custAddrLine1)}
          testId="cust-addr-line-1"
          row={5}
          labelCol={1}
          labelWidth={8}
          valueCol={10}
          width={50}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="State"
          value={displayText(account?.custAddrStateCd)}
          testId="cust-addr-state-cd"
          row={5}
          labelCol={63}
          labelWidth={6}
          valueCol={73}
          width={2}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label=""
          value={displayText(account?.custAddrLine2)}
          testId="cust-addr-line-2"
          row={6}
          labelCol={1}
          labelWidth={1}
          valueCol={10}
          width={50}
          /*
           * COACTVW paints `Address:` at row 16 column 1 for the first address line only
           * and leaves row 17 unlabelled, so no caption is rendered here and the screen
           * matches the mapset. Naming the value after the FIRST line's caption instead
           * left the two lines announcing the same name, which is why the name is given
           * here rather than borrowed. Nothing is painted by it.
           */
          ariaLabel="Address line 2"
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Zip"
          value={displayField(account?.custAddrZip, ZIP_FIELD_WIDTH)}
          testId="cust-addr-zip"
          justifyRight
          row={6}
          labelCol={63}
          labelWidth={3}
          valueCol={73}
          width={5}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="City"
          value={displayText(account?.custAddrLine3)}
          testId="cust-addr-line-3"
          row={7}
          labelCol={1}
          labelWidth={5}
          valueCol={10}
          width={50}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Country"
          value={displayText(account?.custAddrCountryCd)}
          testId="cust-addr-country-cd"
          row={7}
          labelCol={63}
          labelWidth={7}
          valueCol={73}
          width={3}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Phone 1:"
          value={displayText(account?.custPhoneNum1)}
          testId="cust-phone-num-1"
          row={8}
          labelCol={1}
          labelWidth={8}
          valueCol={10}
          width={13}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Government Issued Id Ref    :"
          value={displayText(account?.custGovtIssuedId)}
          testId="cust-govt-issued-id"
          row={8}
          labelCol={24}
          labelWidth={30}
          valueCol={58}
          width={20}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Phone 2:"
          value={displayText(account?.custPhoneNum2)}
          testId="cust-phone-num-2"
          row={9}
          labelCol={1}
          labelWidth={8}
          valueCol={10}
          width={13}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="EFT Account Id:"
          value={displayText(account?.custEftAccountId)}
          testId="cust-eft-account-id"
          row={9}
          labelCol={24}
          labelWidth={16}
          valueCol={41}
          width={10}
        />
        <OutputField
          className="accountView__field"
          valueClassName="field"
          label="Primary Card Holder Y/N:"
          value={displayText(account?.custPriCardHolderInd)}
          testId="cust-pri-card-holder-ind"
          row={9}
          labelCol={53}
          labelWidth={24}
          valueCol={78}
          width={1}
        />
      </dl>
    </div>
  );
}

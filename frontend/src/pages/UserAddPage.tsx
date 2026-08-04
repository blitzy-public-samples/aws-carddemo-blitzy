/**
 * UserAddPage
 * ===========
 *
 * :purpose: Administrator add-user data-entry screen — the React replacement for
 *     BMS mapset ``app/bms/COUSR01.bms`` (CICS transaction ``CU01``, program
 *     ``app/cbl/COUSR01C.cbl``). Collects the ``CSUSR01Y`` security-user fields
 *     (first name, last name, user id, a masked password and the
 *     ``SEC-USR-TYPE`` code), reproduces the ``COUSR01C`` blank-field edits in
 *     their legacy evaluation order, writes the new user through ``addUser``
 *     (``POST /users``, the legacy ``EXEC CICS WRITE`` to ``USRSEC``), and
 *     publishes the screen chrome into the shared ``Layout`` shell.
 * :output: The add-user screen body only. The header, the line-23 message region
 *     and the line-24 function-key bar are rendered by ``Layout`` from the chrome
 *     this page publishes through :func:`useScreenChrome`.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ChangeEvent, FormEvent, ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { Role, UserAddRequestDto, UserAddResponseDto } from '../types';
import { addUser, ApiError } from '../api';
import { useApi } from '../hooks';

/** :purpose: CICS transaction id of the add-user screen. */
const TRANSACTION_ID = 'CU01';

/** :purpose: Legacy program name shown in the header (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COUSR01C';

/** :purpose: Route of the administrator menu reached by PF3 (``XCTL 'COADM01C'``). */
const ADMIN_MENU_ROUTE = '/admin';

/**
 * :purpose: Entry-field capacities, mirroring the BMS ``LENGTH=`` attributes and
 *     the ``CSUSR01Y`` picture clauses (``SEC-USR-FNAME``/``LNAME`` ``X(20)``,
 *     ``SEC-USR-ID``/``SEC-USR-PWD`` ``X(08)``, ``SEC-USR-TYPE`` ``X(01)``).
 */
const FIELD_MAX_LENGTH = {
  firstName: 20,
  lastName: 20,
  userId: 8,
  password: 8,
  userType: 1,
} as const;

/** :purpose: Verbatim ``COUSR01C`` blank-field message for ``FNAMEI``. */
const MSG_FIRST_NAME_EMPTY = 'First Name can NOT be empty...';

/** :purpose: Verbatim ``COUSR01C`` blank-field message for ``LNAMEI``. */
const MSG_LAST_NAME_EMPTY = 'Last Name can NOT be empty...';

/** :purpose: Verbatim ``COUSR01C`` blank-field message for ``USERIDI``. */
const MSG_USER_ID_EMPTY = 'User ID can NOT be empty...';

/** :purpose: Verbatim ``COUSR01C`` blank-field message for ``PASSWDI``. */
const MSG_PASSWORD_EMPTY = 'Password can NOT be empty...';

/** :purpose: Verbatim ``COUSR01C`` blank-field message for ``USRTYPEI``. */
const MSG_USER_TYPE_EMPTY = 'User Type can NOT be empty...';

/** :purpose: Leading affix of the ``COUSR01C`` confirmation ``STRING``. */
const MSG_USER_PREFIX = 'User ';

/** :purpose: Trailing affix of the ``COUSR01C`` confirmation ``STRING``. */
const MSG_ADDED_SUFFIX = ' has been added ...';

/** :purpose: Verbatim ``COUSR01C`` ``WHEN OTHER`` write-failure message. */
const MSG_UNABLE_ADD = 'Unable to Add User...';

/**
 * :purpose: The five ``COUSR1AI`` entry-field values held as controlled state.
 * :field firstName: ``FNAMEI`` -> ``SEC-USR-FNAME``.
 * :field lastName: ``LNAMEI`` -> ``SEC-USR-LNAME``.
 * :field userId: ``USERIDI`` -> ``SEC-USR-ID``.
 * :field password: ``PASSWDI`` -> ``SEC-USR-PWD``, entered masked (BMS ``DRK``).
 * :field userType: ``USRTYPEI`` -> ``SEC-USR-TYPE`` (``A`` admin / ``U`` user).
 */
interface UserAddFields {
  firstName: string;
  lastName: string;
  userId: string;
  password: string;
  userType: string;
}

/** :purpose: Empty entry fields, matching ``INITIALIZE-ALL-FIELDS``. */
const EMPTY_FIELDS: UserAddFields = {
  firstName: '',
  lastName: '',
  userId: '',
  password: '',
  userType: '',
};

/**
 * :purpose: Legacy ``= SPACES OR LOW-VALUES`` blank test for a 3270 entry field.
 * :param value: The current field value.
 * :returns: ``true`` when the field holds no non-blank character.
 */
function isBlank(value: string): boolean {
  return value.trim().length === 0;
}

/**
 * :purpose: Reproduce the ``COUSR01C`` ``PROCESS-ENTER-KEY`` edits, which stop at
 *     the first blank field and suppress the write.
 * :param fields: The current entry-field values.
 * :returns: The verbatim message of the first blank field in legacy evaluation
 *     order, or ``null`` when every field is filled.
 */
function firstBlankMessage(fields: UserAddFields): string | null {
  if (isBlank(fields.firstName)) {
    return MSG_FIRST_NAME_EMPTY;
  }
  if (isBlank(fields.lastName)) {
    return MSG_LAST_NAME_EMPTY;
  }
  if (isBlank(fields.userId)) {
    return MSG_USER_ID_EMPTY;
  }
  if (isBlank(fields.password)) {
    return MSG_PASSWORD_EMPTY;
  }
  if (isBlank(fields.userType)) {
    return MSG_USER_TYPE_EMPTY;
  }
  return null;
}

/**
 * :purpose: Resolve the line-23 text for a rejected ``POST /users``, preferring
 *     the standardized backend ``ApiErrorResponse.message`` (which carries the
 *     legacy literals, for example ``User ID already exist...``).
 * :param error: The normalized error from the failed call.
 * :returns: The message to display.
 */
function resolveErrorMessage(error: ApiError): string {
  return error.body?.message ?? MSG_UNABLE_ADD;
}

/**
 * :purpose: Build the ``COUSR01C`` confirmation message for a created user.
 * :param userId: The entered user id (``SEC-USR-ID``).
 * :returns: The message ``User <id> has been added ...``.
 */
function addedMessage(userId: string): string {
  return `${MSG_USER_PREFIX}${userId.trim()}${MSG_ADDED_SUFFIX}`;
}

/**
 * :purpose: The add-user screen.
 * :returns: The rendered screen body.
 */
export default function UserAddPage(): ReactElement {
  const navigate = useNavigate();
  const { setChrome } = useScreenChrome();

  const [fields, setFields] = useState<UserAddFields>(EMPTY_FIELDS);
  const [errorMessage, setErrorMessage] = useState('');
  const [infoMessage, setInfoMessage] = useState('');

  const { loading, error, run } = useApi(addUser);

  const updateField = useCallback(
    (name: keyof UserAddFields) => (event: ChangeEvent<HTMLInputElement>): void => {
      const { value } = event.target;
      setFields((previous) => ({ ...previous, [name]: value }));
    },
    [],
  );

  useEffect(() => {
    if (error !== null) {
      setInfoMessage('');
      setErrorMessage(resolveErrorMessage(error));
    }
  }, [error]);

  const handleEnter = useCallback(async (): Promise<void> => {
    if (loading) {
      return;
    }
    setErrorMessage('');
    setInfoMessage('');

    const blankMessage = firstBlankMessage(fields);
    if (blankMessage !== null) {
      setErrorMessage(blankMessage);
      return;
    }

    const request: UserAddRequestDto = {
      userId: fields.userId,
      firstName: fields.firstName,
      lastName: fields.lastName,
      userType: fields.userType as Role,
      password: fields.password,
    };
    const created: UserAddResponseDto | undefined = await run(request);
    if (created !== undefined) {
      setInfoMessage(addedMessage(fields.userId));
      setFields(EMPTY_FIELDS);
    }
  }, [fields, loading, run]);

  const handleExit = useCallback((): void => {
    navigate(ADMIN_MENU_ROUTE);
  }, [navigate]);

  const handleClear = useCallback((): void => {
    setFields(EMPTY_FIELDS);
    setErrorMessage('');
    setInfoMessage('');
  }, []);

  const handleSubmit = useCallback(
    (event: FormEvent<HTMLFormElement>): void => {
      event.preventDefault();
      void handleEnter();
    },
    [handleEnter],
  );

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Add User', onActivate: handleEnter },
      { action: PfKeyAction.PF3, label: 'F3=Back', onActivate: handleExit },
      { action: PfKeyAction.PF4, label: 'F4=Clear', onActivate: handleClear },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: 'CardDemo',
      title02: 'Add User',
      errorMessage,
      infoMessage,
      pfKeys,
    });
  }, [setChrome, errorMessage, infoMessage, handleEnter, handleExit, handleClear]);

  return (
    <>
      <h3 className="neutral">Add User</h3>
      <form className="userAdd" onSubmit={handleSubmit}>
        <div className="userAdd__row">
          <label className="prompt" htmlFor="fname">
            First Name:
          </label>
          <input
            id="fname"
            name="fname"
            data-testid="fname"
            className="field"
            type="text"
            value={fields.firstName}
            maxLength={FIELD_MAX_LENGTH.firstName}
            size={FIELD_MAX_LENGTH.firstName}
            aria-required="true"
            onChange={updateField('firstName')}
          />
          <label className="prompt" htmlFor="lname">
            Last Name:
          </label>
          <input
            id="lname"
            name="lname"
            data-testid="lname"
            className="field"
            type="text"
            value={fields.lastName}
            maxLength={FIELD_MAX_LENGTH.lastName}
            size={FIELD_MAX_LENGTH.lastName}
            aria-required="true"
            onChange={updateField('lastName')}
          />
        </div>
        <div className="userAdd__row">
          <label className="prompt" htmlFor="userid">
            User ID:
          </label>
          <input
            id="userid"
            name="userid"
            data-testid="userid"
            className="field"
            type="text"
            value={fields.userId}
            maxLength={FIELD_MAX_LENGTH.userId}
            size={FIELD_MAX_LENGTH.userId}
            aria-required="true"
            aria-describedby="userid-hint"
            autoComplete="off"
            onChange={updateField('userId')}
          />
          <span className="label" id="userid-hint">
            (8 Char)
          </span>
          <label className="prompt" htmlFor="passwd">
            Password:
          </label>
          <input
            id="passwd"
            name="passwd"
            data-testid="passwd"
            className="field"
            type="password"
            value={fields.password}
            maxLength={FIELD_MAX_LENGTH.password}
            size={FIELD_MAX_LENGTH.password}
            aria-required="true"
            aria-describedby="passwd-hint"
            autoComplete="new-password"
            onChange={updateField('password')}
          />
          <span className="label" id="passwd-hint">
            (8 Char)
          </span>
        </div>
        <div className="userAdd__row">
          <label className="prompt" htmlFor="usrtype">
            User Type:
          </label>
          <input
            id="usrtype"
            name="usrtype"
            data-testid="usrtype"
            className="field"
            type="text"
            value={fields.userType}
            maxLength={FIELD_MAX_LENGTH.userType}
            size={FIELD_MAX_LENGTH.userType}
            aria-required="true"
            aria-describedby="usrtype-hint"
            onChange={updateField('userType')}
          />
          <span className="label" id="usrtype-hint">
            (A=Admin, U=User)
          </span>
        </div>
      </form>
    </>
  );
}

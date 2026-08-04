/**
 * UserUpdatePage
 * ==============
 *
 * :purpose: Administrator update-user screen served at ``/users/update``, the 1:1
 *     replacement for the BMS mapset ``app/bms/COUSR02.bms`` (CICS transaction
 *     ``CU02``, program ``app/cbl/COUSR02C.cbl``). The entered — or handed-over — user id is read
 *     with ``GET /users/{id}``; the returned record seeds the editable first
 *     name, last name and user type, and the save issues ``PUT /users/{id}``.
 *     Field captions, the field widths, the masked password field, the line-24
 *     function-key legend and every message literal are reproduced from the
 *     mapset and from the program.
 * :output: The rendered screen body. The header, the line-23 message region and
 *     the line-24 function-key bar are published to the shared shell through
 *     :func:`useScreenChrome` and are not rendered here.
 */
import { useCallback, useEffect, useState } from 'react';
import type { FormEvent, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction } from '../types';
import type { Role, UserDto, UserUpdateRequestDto } from '../types';
import { ApiError, getUser, updateUser } from '../api';
import { useApi } from '../hooks';

/** :purpose: CICS transaction id of this screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CU02';

/** :purpose: Legacy program name reproduced by this screen (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COUSR02C';

/** :purpose: First header title line. */
const TITLE01 = 'CardDemo';

/**
 * :purpose: Second header title line, also the screen heading rendered by the
 *     mapset on line 4.
 */
const SCREEN_TITLE = 'Update User';

/** :purpose: Route of the administrator menu, the ``COADM01C`` return target. */
const ADMIN_MENU_ROUTE = '/admin';

/** :purpose: HTTP status the backend returns for an unknown user id. */
const HTTP_NOT_FOUND = 404;

/** :purpose: ``USRIDIN`` field width (``SEC-USR-ID`` ``PIC X(08)``). */
const USER_ID_MAX_LENGTH = 8;

/**
 * :purpose: ``FNAME`` / ``LNAME`` field width (``SEC-USR-FNAME`` /
 *     ``SEC-USR-LNAME`` ``PIC X(20)``).
 */
const NAME_MAX_LENGTH = 20;

/** :purpose: ``PASSWD`` field width (``SEC-USR-PWD`` ``PIC X(08)``). */
const PASSWORD_MAX_LENGTH = 8;

/** :purpose: ``USRTYPE`` field width (``SEC-USR-TYPE`` ``PIC X(01)``). */
const USER_TYPE_MAX_LENGTH = 1;

/** :purpose: Decorative rule of the mapset, 70 asterisks on line 8. */
const FIELD_SEPARATOR = '*'.repeat(70);

/** :purpose: ``USRIDIN`` empty (``PROCESS-ENTER-KEY`` / ``UPDATE-USER-INFO``). */
const MSG_USER_ID_EMPTY = 'User ID can NOT be empty...';

/** :purpose: ``FNAME`` empty (``UPDATE-USER-INFO``). */
const MSG_FIRST_NAME_EMPTY = 'First Name can NOT be empty...';

/** :purpose: ``LNAME`` empty (``UPDATE-USER-INFO``). */
const MSG_LAST_NAME_EMPTY = 'Last Name can NOT be empty...';

/** :purpose: ``PASSWD`` empty (``UPDATE-USER-INFO``). */
const MSG_PASSWORD_EMPTY = 'Password can NOT be empty...';

/** :purpose: ``USRTYPE`` empty (``UPDATE-USER-INFO``). */
const MSG_USER_TYPE_EMPTY = 'User Type can NOT be empty...';

/** :purpose: Keyed read returned ``NOTFND`` (``READ-USER-SEC-FILE``). */
const MSG_USER_ID_NOT_FOUND = 'User ID NOT found...';

/** :purpose: Prompt shown once the record is on the screen. */
const MSG_PRESS_PF5_UPDATE = 'Press PF5 key to save your updates ...';

/** :purpose: Keyed read failed with any other response code. */
const MSG_UNABLE_LOOKUP_USER = 'Unable to lookup User...';

/** :purpose: Rewrite failed with any other response code. */
const MSG_UNABLE_UPDATE_USER = 'Unable to Update User...';

/** :purpose: Leading literal of the update-confirmed message. */
const MSG_USER_PREFIX = 'User ';

/** :purpose: Trailing literal of the update-confirmed message. */
const MSG_UPDATED_SUFFIX = ' has been updated ...';

/** :purpose: Line-24 legend of the ENTER key. */
const PF_ENTER_LABEL = 'ENTER=Fetch';

/** :purpose: Line-24 legend of PF3. */
const PF3_LABEL = 'F3=Save&Exit';

/** :purpose: Line-24 legend of PF4. */
const PF4_LABEL = 'F4=Clear';

/** :purpose: Line-24 legend of PF5. */
const PF5_LABEL = 'F5=Save';

/** :purpose: Router-state keys the user-list screen may carry the selected id in. */
const INCOMING_USER_ID_KEYS = ['userId', 'selectedUserId'];

/** :purpose: Query-string parameter holding a handed-over user id. */
const INCOMING_USER_ID_PARAM = 'userId';

/**
 * :purpose: Read the user id handed over by the user-list screen, which selects a
 *     row with ``U`` and carries the id in the router state or in the query
 *     string (legacy COMMAREA field ``CDEMO-CU02-USR-SELECTED``).
 * :param state: the router location state, of unknown shape.
 * :param search: the location query string, including its leading ``?``.
 * :returns: The trimmed handed-over user id, or an empty string when none
 *     arrived.
 */
function readIncomingUserId(state: unknown, search: string): string {
  if (typeof state === 'object' && state !== null) {
    const record = state as Record<string, unknown>;
    for (const key of INCOMING_USER_ID_KEYS) {
      const value = record[key];
      if (typeof value === 'string' && value.trim().length > 0) {
        return value.trim();
      }
    }
  }
  const parameter = new URLSearchParams(search).get(INCOMING_USER_ID_PARAM);
  return parameter === null ? '' : parameter.trim();
}

/**
 * :purpose: Resolve the line-23 message of a failed REST call: ``404`` reports the
 *     legacy not-found literal, every other failure surfaces the backend
 *     ``ApiErrorResponse.message``.
 * :param error: the normalized error of the failed call.
 * :param fallback: literal published when the failure carries no message.
 * :returns: The message to publish.
 */
function resolveErrorMessage(error: ApiError, fallback: string): string {
  if (error.status === HTTP_NOT_FOUND) {
    return MSG_USER_ID_NOT_FOUND;
  }
  const message = error.body?.message ?? error.message;
  return message.trim().length > 0 ? message : fallback;
}

/**
 * :purpose: The update-user screen (``COUSR02`` / ``CU02``): look a security user
 *     up by id, edit the first name, last name, password and user type, and save
 *     the record.
 * :returns: The rendered screen body.
 */
export default function UserUpdatePage(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  const { setChrome } = useScreenChrome();

  const incomingUserId = readIncomingUserId(location.state, location.search);

  const [userId, setUserId] = useState<string>(incomingUserId);
  const [firstName, setFirstName] = useState<string>('');
  const [lastName, setLastName] = useState<string>('');
  const [password, setPassword] = useState<string>('');
  const [userType, setUserType] = useState<string>('');
  const [validationMessage, setValidationMessage] = useState<string>('');
  const [outcomeMessage, setOutcomeMessage] = useState<string>('');

  const { run: runFetch, reset: resetFetch, error: fetchError } = useApi(getUser);
  const {
    run: runUpdate,
    reset: resetUpdate,
    error: updateError,
    loading: updateLoading,
  } = useApi(updateUser);

  // Only one of the two calls carries an error at a time: each flow resets the
  // other before it starts, so the single line-23 region shows one message.
  const errorMessage =
    validationMessage ||
    (fetchError !== null
      ? resolveErrorMessage(fetchError, MSG_UNABLE_LOOKUP_USER)
      : '') ||
    (updateError !== null
      ? resolveErrorMessage(updateError, MSG_UNABLE_UPDATE_USER)
      : '');
  const infoMessage = errorMessage.length > 0 ? '' : outcomeMessage;

  /**
   * :purpose: Read the record of a user id and seed the editable fields
   *     (``PROCESS-ENTER-KEY``). The response carries no password, so that field
   *     stays empty and is re-entered before a save.
   * :param id: the user id to look up.
   * :returns: A promise that settles once the outcome has been published.
   */
  const fetchUser = useCallback(
    async (id: string): Promise<void> => {
      const key = id.trim();
      resetUpdate();
      setOutcomeMessage('');
      if (key.length === 0) {
        resetFetch();
        setValidationMessage(MSG_USER_ID_EMPTY);
        return;
      }
      setValidationMessage('');
      setFirstName('');
      setLastName('');
      setPassword('');
      setUserType('');
      const user: UserDto | undefined = await runFetch(key);
      if (user === undefined) {
        return;
      }
      setFirstName(user.firstName ?? '');
      setLastName(user.lastName ?? '');
      setUserType(user.userType ?? '');
      setOutcomeMessage(MSG_PRESS_PF5_UPDATE);
    },
    [resetFetch, resetUpdate, runFetch],
  );

  /**
   * :purpose: Validate the entered fields in the order of ``UPDATE-USER-INFO`` and
   *     rewrite the record. An unchanged record and every other rejection are
   *     reported by the service and surfaced verbatim.
   * :returns: A promise that settles once the outcome has been published.
   */
  const saveUser = useCallback(
    async (): Promise<void> => {
      if (updateLoading) {
        return;
      }
      const key = userId.trim();
      const enteredFirstName = firstName.trim();
      const enteredLastName = lastName.trim();
      const enteredUserType = userType.trim();

      resetFetch();
      setOutcomeMessage('');

      if (key.length === 0) {
        resetUpdate();
        setValidationMessage(MSG_USER_ID_EMPTY);
        return;
      }
      if (enteredFirstName.length === 0) {
        resetUpdate();
        setValidationMessage(MSG_FIRST_NAME_EMPTY);
        return;
      }
      if (enteredLastName.length === 0) {
        resetUpdate();
        setValidationMessage(MSG_LAST_NAME_EMPTY);
        return;
      }
      if (password.trim().length === 0) {
        resetUpdate();
        setValidationMessage(MSG_PASSWORD_EMPTY);
        return;
      }
      if (enteredUserType.length === 0) {
        resetUpdate();
        setValidationMessage(MSG_USER_TYPE_EMPTY);
        return;
      }
      setValidationMessage('');

      // Presence is the only edit COUSR02C applies to the type code; the
      // service rejects a code other than 'A' or 'U'.
      const request: UserUpdateRequestDto = {
        firstName: enteredFirstName,
        lastName: enteredLastName,
        userType: enteredUserType as Role,
        password,
      };
      const updated: UserDto | undefined = await runUpdate(key, request);
      if (updated === undefined) {
        return;
      }
      setOutcomeMessage(`${MSG_USER_PREFIX}${key}${MSG_UPDATED_SUFFIX}`);
    },
    [
      firstName,
      lastName,
      password,
      resetFetch,
      resetUpdate,
      runUpdate,
      updateLoading,
      userId,
      userType,
    ],
  );

  /**
   * :purpose: ENTER — look the entered user id up.
   */
  const handleFetch = useCallback((): void => {
    void fetchUser(userId);
  }, [fetchUser, userId]);

  /**
   * :purpose: PF5 — save the edited record.
   */
  const handleSave = useCallback((): void => {
    void saveUser();
  }, [saveUser]);

  /**
   * :purpose: PF3 — save the edited record and return to the administrator menu,
   *     the ``UPDATE-USER-INFO`` then ``XCTL COADM01C`` sequence behind the
   *     ``F3=Save&Exit`` legend.
   */
  const handleExit = useCallback((): void => {
    void saveUser();
    navigate(ADMIN_MENU_ROUTE);
  }, [navigate, saveUser]);

  /**
   * :purpose: PF4 — blank every field and the message region
   *     (``INITIALIZE-ALL-FIELDS``).
   */
  const handleClear = useCallback((): void => {
    setUserId('');
    setFirstName('');
    setLastName('');
    setPassword('');
    setUserType('');
    setValidationMessage('');
    setOutcomeMessage('');
    resetFetch();
    resetUpdate();
  }, [resetFetch, resetUpdate]);

  /**
   * :purpose: Submit the lookup form, which the ENTER key drives.
   * :param event: the form submit event, whose default navigation is suppressed.
   */
  const handleSubmit = useCallback(
    (event: FormEvent<HTMLFormElement>): void => {
      event.preventDefault();
      void fetchUser(userId);
    },
    [fetchUser, userId],
  );

  // A user id handed over by the user-list screen is looked up on entry, as
  // COUSR02C reads CDEMO-CU02-USR-SELECTED and performs PROCESS-ENTER-KEY.
  useEffect(() => {
    if (incomingUserId.length > 0) {
      setUserId(incomingUserId);
      void fetchUser(incomingUserId);
    }
  }, [fetchUser, incomingUserId]);

  useEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_ENTER_LABEL, onActivate: handleFetch },
      { action: PfKeyAction.PF3, label: PF3_LABEL, onActivate: handleExit },
      { action: PfKeyAction.PF4, label: PF4_LABEL, onActivate: handleClear },
      { action: PfKeyAction.PF5, label: PF5_LABEL, onActivate: handleSave },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: TITLE01,
      title02: SCREEN_TITLE,
      errorMessage,
      infoMessage,
      pfKeys,
    });
  }, [
    errorMessage,
    handleClear,
    handleExit,
    handleFetch,
    handleSave,
    infoMessage,
    setChrome,
  ]);

  return (
    <section className="userUpdate" aria-labelledby="userUpdateHeading">
      <h3 className="neutral" id="userUpdateHeading">
        {SCREEN_TITLE}
      </h3>

      <form className="userUpdate__form" onSubmit={handleSubmit}>
        <div className="userUpdate__row">
          <label className="label" htmlFor="usridin">
            Enter User ID:
          </label>{' '}
          <input
            className="field"
            data-testid="usridin"
            id="usridin"
            name="usridin"
            type="text"
            autoComplete="off"
            maxLength={USER_ID_MAX_LENGTH}
            size={USER_ID_MAX_LENGTH}
            value={userId}
            onChange={(event) => setUserId(event.target.value)}
          />
        </div>

        <div className="userUpdate__separator" aria-hidden="true">
          {FIELD_SEPARATOR}
        </div>

        <div className="userUpdate__row">
          <label className="prompt" htmlFor="fname">
            First Name:
          </label>{' '}
          <input
            className="field"
            data-testid="fname"
            id="fname"
            name="fname"
            type="text"
            autoComplete="off"
            maxLength={NAME_MAX_LENGTH}
            size={NAME_MAX_LENGTH}
            value={firstName}
            onChange={(event) => setFirstName(event.target.value)}
          />{' '}
          <label className="prompt" htmlFor="lname">
            Last Name:
          </label>{' '}
          <input
            className="field"
            data-testid="lname"
            id="lname"
            name="lname"
            type="text"
            autoComplete="off"
            maxLength={NAME_MAX_LENGTH}
            size={NAME_MAX_LENGTH}
            value={lastName}
            onChange={(event) => setLastName(event.target.value)}
          />
        </div>

        <div className="userUpdate__row">
          <label className="prompt" htmlFor="passwd">
            Password:
          </label>{' '}
          <input
            className="field"
            data-testid="passwd"
            id="passwd"
            name="passwd"
            type="password"
            autoComplete="new-password"
            aria-describedby="passwdHint"
            maxLength={PASSWORD_MAX_LENGTH}
            size={PASSWORD_MAX_LENGTH}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />{' '}
          <span className="label" id="passwdHint">
            (8 Char)
          </span>
        </div>

        <div className="userUpdate__row">
          <label className="prompt" htmlFor="usrtype">
            User Type:
          </label>{' '}
          <input
            className="field"
            data-testid="usrtype"
            id="usrtype"
            name="usrtype"
            type="text"
            autoComplete="off"
            aria-describedby="usrtypeHint"
            maxLength={USER_TYPE_MAX_LENGTH}
            size={USER_TYPE_MAX_LENGTH}
            value={userType}
            onChange={(event) => setUserType(event.target.value)}
          />{' '}
          <span className="label" id="usrtypeHint">
            (A=Admin, U=User)
          </span>
        </div>

        {/* ENTER submits the lookup. The mapset places no button in the screen
            body, so this control is hidden from sight, from assistive technology
            and from keyboard navigation. */}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
    </section>
  );
}

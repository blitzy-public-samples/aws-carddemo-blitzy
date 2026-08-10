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
import { useCallback, useEffect, useLayoutEffect, useState, useRef } from 'react';
import type { FormEvent, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import { invalidFieldProps } from '../components/ErrorBanner';
import type { PFKeyDef } from '../components/PFKeyBar';
import { PfKeyAction, CCDA_TITLE01, CCDA_TITLE02, CCDA_MSG_INVALID_KEY } from '../types';
import type {
  Role,
  UserDto,
  UserUpdateRequestDto,
  UserUpdateResponseDto,
} from '../types';
import { ApiError, getUser, updateUser } from '../api';
import { placeCursor, useApi, useScreenAction, useSelfRevocationExit } from '../hooks';
import { resolveApiErrorMessage, resolveFaultedField } from '../components/display';

/** :purpose: CICS transaction id of this screen (``WS-TRANID``). */
const TRANSACTION_ID = 'CU02';

/** :purpose: Legacy program name reproduced by this screen (``WS-PGMNAME``). */
const PROGRAM_NAME = 'COUSR02C';

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

/**
 * :purpose: ``PASSWD`` empty (``UPDATE-USER-INFO`` L200). Declared by the program and
 *     kept in the cursor and fault tables so the control is still addressed should the
 *     service report it, but unreachable from this screen: ``COUSR02C`` can only see an
 *     empty ``PASSWD`` because L169 pre-filled it, and a hashed credential cannot be
 *     pre-filled. The add screen reaches the same literal from ``COUSR01C`` L138.
 */
const MSG_PASSWORD_EMPTY = 'Password can NOT be empty...';

/**
 * :purpose: Native-tooltip text for ``PASSWD``, explaining that leaving the box empty
 *     keeps the stored credential. Not part of the mapset, so it is carried by the
 *     ``title`` attribute rather than as screen text.
 */
const PASSWORD_UNCHANGED_HINT = 'Leave blank to keep the current password';

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

/** ``COUSR02`` line-24 ``F12`` key, verbatim. */
const PF12_LABEL = 'F12=Cancel';

/**
 * :purpose: Control that receives the cursor for each line-23 outcome. ``COUSR02C``
 *     ends every path with ``MOVE -1 TO <field>L``: the five emptiness edits and the
 *     two not-found outcomes name their own field, while the remaining paths return
 *     the cursor to ``USRIDIN`` (the mapset's ``IC`` field) or to ``FNAME``.
 */
/**
 * :purpose: Map a request-payload property named in ``ErrorResponse.fieldErrors`` to the
 *     DOM id of the control that carries it, so the marker and the cursor land on the
 *     field the service refused. ``COUSR02.bms`` names its fields ``USRIDIN``, ``FNAME``,
 *     ``LNAME``, ``PASSWD`` and ``USRTYPE``; the payload uses the DTO property names.
 */
const ELEMENT_BY_PAYLOAD_PROPERTY: Readonly<Partial<Record<string, string>>> = {
  userId: 'usridin',
  firstName: 'fname',
  lastName: 'lname',
  password: 'passwd',
  userType: 'usrtype',
};

const CURSOR_BY_MESSAGE: Readonly<Record<string, string>> = {
  [MSG_USER_ID_EMPTY]: 'usridin',
  [MSG_FIRST_NAME_EMPTY]: 'fname',
  [MSG_LAST_NAME_EMPTY]: 'lname',
  [MSG_PASSWORD_EMPTY]: 'passwd',
  [MSG_USER_TYPE_EMPTY]: 'usrtype',
  [MSG_USER_ID_NOT_FOUND]: 'usridin',
  [MSG_UNABLE_LOOKUP_USER]: 'fname',
  [MSG_UNABLE_UPDATE_USER]: 'fname',
};

/**
 * :purpose: The messages that fault the entered value of their cursor field, as
 *     opposed to reporting a failed lookup or write. Only these mark the control
 *     ``aria-invalid``; the cursor still moves for every outcome.
 */
const FIELD_FAULT_MESSAGES: ReadonlySet<string> = new Set([
  MSG_USER_ID_EMPTY,
  MSG_FIRST_NAME_EMPTY,
  MSG_LAST_NAME_EMPTY,
  MSG_PASSWORD_EMPTY,
  MSG_USER_TYPE_EMPTY,
  MSG_USER_ID_NOT_FOUND,
]);

/** ``USRIDIN`` carries the mapset's ``IC`` attribute. */
const DEFAULT_CURSOR_FIELD = 'usridin';

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
  return resolveApiErrorMessage(error, fallback);
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

  const {
    run: runFetch,
    reset: resetFetch,
    error: fetchError,
    loading: fetchLoading,
  } = useApi(getUser);
  const {
    run: runUpdate,
    reset: resetUpdate,
    error: updateError,
    loading: updateLoading,
  } = useApi(updateUser);
  const exitOnSelfRevocation = useSelfRevocationExit();

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

  // The keyboard-locked interval covers both the fetch and the update.
  const busy = fetchLoading || updateLoading;

  /**
   * :purpose: Keyboard-lock latch: ``true`` from the instant a fetch or an update is
   *     dispatched until its answer has been applied. A 3270 locked the keyboard for
   *     exactly that interval, so one intent could never be sent twice. The latch is a
   *     ref rather than ``busy`` because two activations in the same task both observe
   *     the state as it was before either of them, and both would be admitted.
   */
  const requestLatch = useRef<boolean>(false);

  // ``MOVE -1 TO <field>L``: the cursor follows the current line-23 outcome. The
  // entry fields are disabled while a call is outstanding and focusing a disabled
  // control is a no-op, so placement waits for the call to settle.
  // The service names the property its edit refused in the error envelope, which is
  // preferred over matching the message text: the edit ran there, so it knows which
  // field failed, and a message no map has seen still reaches the right control.
  const serverField =
    ELEMENT_BY_PAYLOAD_PROPERTY[resolveFaultedField(updateError ?? fetchError) ?? ''];

  useEffect(() => {
    if (busy) {
      return;
    }
    const field = serverField ?? CURSOR_BY_MESSAGE[errorMessage] ?? DEFAULT_CURSOR_FIELD;
    placeCursor(document.getElementById(field));
  }, [busy, errorMessage, serverField]);

  // The cursor moves for every outcome, but only a rejected value is invalid.
  const faultedField: string | null =
    serverField ??
    (FIELD_FAULT_MESSAGES.has(errorMessage)
      ? (CURSOR_BY_MESSAGE[errorMessage] ?? null)
      : null);

  /**
   * :purpose: Read the record of a user id and seed the editable fields
   *     (``PROCESS-ENTER-KEY``). The response carries no password, so that field
   *     stays empty and is re-entered before a save.
   * :param id: the user id to look up.
   * :returns: A promise that settles once the outcome has been published.
   */
  const readUser = useCallback(
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
  const writeUser = useCallback(
    async (): Promise<void> => {
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
      /*
       * No blank-password edit here, deliberately. COUSR02C reaches its
       * 'Password can NOT be empty...' edit (L198-L202) with PASSWD already pre-filled
       * from SEC-USR-PWD (L169, under the mapset's DRK attribute), so the field was always
       * populated by the time PF5 ran and the field-by-field compare then found it equal;
       * an empty box THERE meant the operator had deliberately erased the credential. A
       * hashed credential cannot be pre-filled and is never sent to the client, so an empty
       * box HERE means "leave it alone" instead. Reproducing the edit literally made a
       * name-only or role-only update impossible -- something no legacy operator ever
       * experienced -- and it also put the 'Please modify to update ...' no-change guard out
       * of reach. The add screen keeps the literal, where COUSR01C L138 genuinely requires
       * a password.
       */
      if (enteredUserType.length === 0) {
        resetUpdate();
        setValidationMessage(MSG_USER_TYPE_EMPTY);
        return;
      }
      setValidationMessage('');

      // Presence is the only edit COUSR02C applies to the type code (its EVALUATE tests
      // SPACES/LOW-VALUES and nothing else), so no value-set check is made here either.
      // The password is carried only when one was entered, so an update of the profile
      // fields alone cannot reach -- and therefore cannot replace -- the credential.
      // The raw value is sent unpadded and untrimmed: it is compared against the stored
      // hash, so altering it here would change the credential the operator typed.
      const request: UserUpdateRequestDto = {
        firstName: enteredFirstName,
        lastName: enteredLastName,
        userType: enteredUserType as Role,
        ...(password.trim().length > 0 ? { password } : {}),
      };
      const updated: UserUpdateResponseDto | undefined = await runUpdate(key, request);
      if (updated === undefined) {
        return;
      }
      // The banner comes from the RESPONSE, never from what was typed: the service
      // upper-cases the user id (COSGN00C L132 / 3270 UCTRAN), so echoing the entered
      // text would report an id that is not the one stored.
      const confirmation =
        updated.message ?? `${MSG_USER_PREFIX}${updated.userId}${MSG_UPDATED_SUFFIX}`;
      // Changing one's own role or credential revokes one's own session, so the
      // consequence is reported by the action that caused it rather than surfacing as a
      // blank refusal of whatever the operator does next.
      if (await exitOnSelfRevocation(key, confirmation)) {
        return;
      }
      setOutcomeMessage(confirmation);
    },
    [
      exitOnSelfRevocation,
      firstName,
      lastName,
      password,
      resetFetch,
      resetUpdate,
      runUpdate,
      userId,
      userType,
    ],
  );

  /**
   * :purpose: Look a user id up under the keyboard lock, so a repeated activation in
   *     the same task issues exactly one read.
   * :param id: the user id to look up.
   * :returns: A promise that settles once the outcome has been published.
   */
  const fetchUser = useCallback(
    async (id: string): Promise<void> => {
      if (requestLatch.current) {
        return;
      }
      requestLatch.current = true;
      try {
        await readUser(id);
      } finally {
        requestLatch.current = false;
      }
    },
    [readUser],
  );

  /**
   * :purpose: Rewrite the record under the keyboard lock, so a repeated activation in
   *     the same task issues exactly one update.
   * :returns: A promise that settles once the outcome has been published.
   */
  const saveUser = useCallback(async (): Promise<void> => {
    if (requestLatch.current) {
      return;
    }
    requestLatch.current = true;
    try {
      await writeUser();
    } finally {
      requestLatch.current = false;
    }
  }, [writeUser]);

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
  const handleExit = useCallback(async (): Promise<void> => {
    await saveUser();
    void navigate(ADMIN_MENU_ROUTE);
  }, [navigate, saveUser]);

  // DFHPF12 returns to the caller without performing UPDATE-USER-INFO.
  const handleCancel = useCallback((): void => {
    void navigate(ADMIN_MENU_ROUTE);
  }, [navigate]);

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
    // PF4 re-sends the map, and every send honours ``ATTRB=IC`` on USRIDIN. The
    // message-driven cursor effect cannot observe a clear that leaves the message
    // region and the resolved target unchanged, so the send's cursor placement is
    // performed here; otherwise the cursor stays on whatever activated the key --
    // the line-24 ``F4`` button after a pointer click.
    placeCursor(document.getElementById(DEFAULT_CURSOR_FIELD));
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

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  /**
   * :purpose: ``EVALUATE EIBAID`` ``WHEN OTHER`` (``COUSR02C`` L127-131) — publish
   *     ``CCDA-MSG-INVALID-KEY`` and re-send the map, whose ``ATTRB=IC`` on USRIDIN
   *     returns the cursor to the user-id field. No entered value is rejected.
   */
  const handleUnhandledKey = useCallback((): void => {
    setOutcomeMessage('');
    setValidationMessage(CCDA_MSG_INVALID_KEY);
    placeCursor(document.getElementById(DEFAULT_CURSOR_FIELD));
  }, []);

  const activateFetch = useScreenAction(handleFetch);
  const activateClear = useScreenAction(handleClear);
  const activateSave = useScreenAction(handleSave);
  const activateCancel = useScreenAction(handleCancel);
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);
  const activateExit = useScreenAction((): void => {
    void handleExit();
  });

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: PF_ENTER_LABEL, onActivate: activateFetch },
      {
        action: PfKeyAction.PF3,
        label: PF3_LABEL,
        onActivate: activateExit,
      },
      { action: PfKeyAction.PF4, label: PF4_LABEL, onActivate: activateClear },
      { action: PfKeyAction.PF5, label: PF5_LABEL, onActivate: activateSave },
      { action: PfKeyAction.PF12, label: PF12_LABEL, onActivate: activateCancel },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      onUnhandledKey: activateUnhandledKey,
      busy,
    });
  }, [
    activateCancel,
    activateClear,
    activateExit,
    activateFetch,
    activateSave,
    activateUnhandledKey,
    busy,
    errorMessage,
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
          {/* COUSR02.bms:L80-L84 -- LENGTH=14 POS=(6,6) COLOR=GREEN. */}
          <label className="green" htmlFor="usridin">
            Enter User ID:
          </label>{' '}
          <input
            className="field"
            disabled={busy}
            data-testid="usridin"
            aria-required="true"
            id="usridin"
            name="usridin"
            {...invalidFieldProps(faultedField === 'usridin')}
            type="text"
            autoComplete="off"
            maxLength={USER_ID_MAX_LENGTH}
            size={USER_ID_MAX_LENGTH}
            value={userId}
            onChange={(event) => setUserId(event.target.value)}
          />
        </div>

        {/* COUSR02.bms:L93-L97 declares the 70-asterisk rule COLOR=YELLOW at POS=(8,6),
            exactly as COUSR03 does; `title` is the shared YELLOW tone. */}
        <div className="userUpdate__separator title" aria-hidden="true">
          {FIELD_SEPARATOR}
        </div>

        <div className="userUpdate__row">
          <label className="prompt" htmlFor="fname">
            First Name:
          </label>{' '}
          <input
            className="field"
            disabled={busy}
            data-testid="fname"
            aria-required="true"
            id="fname"
            name="fname"
            {...invalidFieldProps(faultedField === 'fname')}
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
            disabled={busy}
            data-testid="lname"
            aria-required="true"
            id="lname"
            name="lname"
            {...invalidFieldProps(faultedField === 'lname')}
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
            disabled={busy}
            data-testid="passwd"
            id="passwd"
            name="passwd"
            type="password"
            autoComplete="new-password"
            {...invalidFieldProps(faultedField === 'passwd', 'passwdHint')}
            maxLength={PASSWORD_MAX_LENGTH}
            size={PASSWORD_MAX_LENGTH}
            value={password}
            /* The mapset's own hint literal stays exactly ``(8 Char)``, so the
               leave-blank semantic is carried by the native tooltip: it adds no
               rendered text and no DOM node to the 24x80 screen contract. */
            title={PASSWORD_UNCHANGED_HINT}
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
            disabled={busy}
            data-testid="usrtype"
            aria-required="true"
            id="usrtype"
            name="usrtype"
            type="text"
            autoComplete="off"
            {...invalidFieldProps(faultedField === 'usrtype', 'usrtypeHint')}
            maxLength={USER_TYPE_MAX_LENGTH}
            size={USER_TYPE_MAX_LENGTH}
            value={userType}
            onChange={(event) => setUserType(event.target.value)}
          />{' '}
          <span className="label" id="usrtypeHint">
            (A=Admin, U=User)
          </span>
        </div>

      </form>
    </section>
  );
}

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
import { useCallback, useEffect, useLayoutEffect, useState, useRef } from 'react';
import type { ChangeEvent, FormEvent, ReactElement } from 'react';
import { useNavigate } from 'react-router';
import { useScreenChrome } from '../components/Layout';
import type { PFKeyDef } from '../components/PFKeyBar';
import {
  PfKeyAction,
  CCDA_TITLE01,
  CCDA_TITLE02,
  CCDA_MSG_INVALID_KEY,
} from '../types';
import type { Role, UserAddRequestDto, UserAddResponseDto } from '../types';
import { addUser } from '../api';
import {
  placeCursor,
  useApi,
  useFocusOnSettled,
  useInitialFocus,
  useScreenAction,
} from '../hooks';
import { invalidFieldProps } from '../components/ErrorBanner';
import { isBlank, resolveFaultedField } from '../components/display';

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

/** :purpose: Verbatim ``COUSR01C`` ``DUPKEY``/``DUPREC`` message. */
const MSG_USER_ID_EXISTS = 'User ID already exist...';

/** :purpose: Leading affix of the ``COUSR01C`` confirmation ``STRING``. */
const MSG_USER_PREFIX = 'User ';

/** :purpose: Trailing affix of the ``COUSR01C`` confirmation ``STRING``. */
const MSG_ADDED_SUFFIX = ' has been added ...';

/** :purpose: Verbatim ``COUSR01C`` ``WHEN OTHER`` write-failure message. */
const MSG_UNABLE_ADD = 'Unable to Add User...';

/** ``COUSR01`` line-24 legend keys, verbatim. */
const PF_ENTER_LABEL = 'ENTER=Add User';
const PF3_LABEL = 'F3=Back';
const PF4_LABEL = 'F4=Clear';
const PF12_LABEL = 'F12=Exit';

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

/**
 * :purpose: Element id of each entry field, so a cursor target resolves to a node.
 */
/**
 * :purpose: Map a request-payload property named in ``ErrorResponse.fieldErrors`` back to
 *     the screen field that carries it, so the marker and the cursor land on the control
 *     the service refused. The names are the DTO property names the service publishes.
 */
const FIELD_BY_PAYLOAD_PROPERTY: Readonly<Partial<Record<string, keyof UserAddFields>>> = {
  firstName: 'firstName',
  lastName: 'lastName',
  userId: 'userId',
  password: 'password',
  userType: 'userType',
};

const FIELD_ELEMENT_ID: Readonly<Record<keyof UserAddFields, string>> = {
  firstName: 'fname',
  lastName: 'lname',
  userId: 'userid',
  password: 'passwd',
  userType: 'usrtype',
};

/**
 * :purpose: Cursor target per outcome. ``COUSR01C`` cursors to the field the edit
 *     rejected rather than to a fixed field: ``MOVE -1 TO FNAMEL`` (L122), ``LNAMEL``
 *     (L128), ``USERIDL`` (L134), ``PASSWDL`` (L140) and ``USRTYPEL`` (L146) for the
 *     five emptiness edits, ``USERIDL`` (L265) for a duplicate key, and ``FNAMEL``
 *     (L272, L289) for everything else - which is also this mapset's ``ATTRB=IC``
 *     field and therefore the default.
 * :note: Any outcome absent from this map falls back to ``firstName``.
 */
const CURSOR_BY_MESSAGE: Readonly<Record<string, keyof UserAddFields>> = {
  [MSG_FIRST_NAME_EMPTY]: 'firstName',
  [MSG_LAST_NAME_EMPTY]: 'lastName',
  [MSG_USER_ID_EMPTY]: 'userId',
  [MSG_PASSWORD_EMPTY]: 'password',
  [MSG_USER_TYPE_EMPTY]: 'userType',
  [MSG_USER_ID_EXISTS]: 'userId',
  [MSG_UNABLE_ADD]: 'firstName',
};

/**
 * :purpose: The messages that fault the entered value of their cursor field, as
 *     opposed to reporting a rejected function key or a failed write. Only these mark
 *     the control ``aria-invalid``; the cursor still moves for every outcome.
 */
const FIELD_FAULT_MESSAGES: ReadonlySet<string> = new Set([
  MSG_FIRST_NAME_EMPTY,
  MSG_LAST_NAME_EMPTY,
  MSG_USER_ID_EMPTY,
  MSG_PASSWORD_EMPTY,
  MSG_USER_TYPE_EMPTY,
  MSG_USER_ID_EXISTS,
]);

/** :purpose: Empty entry fields, matching ``INITIALIZE-ALL-FIELDS``. */
const EMPTY_FIELDS: UserAddFields = {
  firstName: '',
  lastName: '',
  userId: '',
  password: '',
  userType: '',
};

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
 * :purpose: Build the ``COUSR01C`` confirmation message for a created user.
 * :param userId: The user id as STORED by the service (``SEC-USR-ID``), which is
 *     upper-cased; never the raw text the operator typed.
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

  const { loading, error, run, reset } = useApi(addUser);

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
      setErrorMessage((error.body?.message ?? MSG_UNABLE_ADD));
    }
  }, [error]);

  /**
   * :purpose: Keyboard-lock latch: ``true`` from the instant the request is dispatched
   *     until its answer has been applied. A 3270 locked the keyboard for exactly that
   *     interval, so one intent could never be sent twice. The latch is a ref rather
   *     than the request state because two activations in the same task both observe
   *     the state as it was before either of them and both would be admitted.
   */
  const addLatch = useRef<boolean>(false);

  const handleEnter = useCallback(async (): Promise<void> => {
    if (addLatch.current) {
      return;
    }
    addLatch.current = true;
    try {
      setErrorMessage('');
      setInfoMessage('');
      // Discard the previous answer before this send is edited. The screen's own edits
      // run first and return without calling the service, so a field the SERVICE named
      // on an earlier send would otherwise still be the newest named field: the marker
      // and the cursor would sit on the control that was refused last time instead of
      // the one refused now.
      reset();

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
        // The banner comes from the RESPONSE, never from the entered text: the service
        // upper-cases the user id (COSGN00C L132 / 3270 UCTRAN), so echoing what was
        // typed would report an id that differs from the one actually stored.
        setInfoMessage(created.message ?? addedMessage(created.userId));
        setFields(EMPTY_FIELDS);
      }
    } finally {
      addLatch.current = false;
    }
  }, [fields, reset, run]);

  const handleExit = useCallback((): void => {
    void navigate(ADMIN_MENU_ROUTE);
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

  // COUSR01 marks FNAME ``ATTRB=(FSET,IC,NORM,UNPROT)`` and COUSR01C returns the
  // cursor there for an unhandled key, so the first name carries the cursor.
  const firstNameRef = useInitialFocus<HTMLInputElement>();
  // The entry fields are disabled while the add is in flight, which blurs the focused one
  // to the document body; `COUSR01.bms` gives FNAME the IC attribute, so the cursor
  // returns to it once the write settles.
  useFocusOnSettled(loading, firstNameRef);

  // CICS honours the insert cursor on every send, so it is re-placed after each
  // request settles as well as on entry: a control disabled while the request was in
  // flight has been blurred by the browser by then. The target is the field the
  // outcome names, defaulting to the mapset's ``ATTRB=IC`` field.
  const messageCursorField: keyof UserAddFields =
    errorMessage === '' ? 'firstName' : (CURSOR_BY_MESSAGE[errorMessage] ?? 'firstName');

  // The service names the property its edit refused in the error envelope, and that is
  // preferred over matching the message text: the edit was performed there, so it knows
  // which field failed, and a message the map has never seen -- 'User Type must be A or
  // U' among them -- still marks and cursors to the right control.
  const serverField = FIELD_BY_PAYLOAD_PROPERTY[resolveFaultedField(error) ?? ''];

  const cursorField: keyof UserAddFields = serverField ?? messageCursorField;

  // The cursor moves for every outcome, but only a rejected value is invalid.
  const faultedField: keyof UserAddFields | null =
    serverField ?? (FIELD_FAULT_MESSAGES.has(errorMessage) ? cursorField : null);
  useEffect(() => {
    if (loading) {
      return;
    }
    placeCursor(document.getElementById(FIELD_ELEMENT_ID[cursorField]));
  }, [loading, cursorField, errorMessage, infoMessage]);

  const handleUnhandledKey = useCallback((): void => {
    setInfoMessage('');
    setErrorMessage(CCDA_MSG_INVALID_KEY);
  }, []);

  // The activators published to the shared frame are identity-stable and always
  // dispatch to the newest render's handler, so the line-24 legend is not rebuilt on
  // every keystroke and an AID can never act on a value the screen has replaced.
  const activateExit = useScreenAction(handleExit);
  const activateClear = useScreenAction(handleClear);
  const activateUnhandledKey = useScreenAction(handleUnhandledKey);
  const activateEnter = useScreenAction((): void => {
    void handleEnter();
  });

  // The frame's header, line-23 message region and line-24 key legend belong to the
  // SAME map as this body, so they are published in a LAYOUT effect: a CICS program
  // moved every field into the symbolic map before its one SEND, and nothing
  // half-built ever reached the terminal. A passive effect would paint the frame
  // once without them and then move it.
  useLayoutEffect(() => {
    const pfKeys: PFKeyDef[] = [
      {
        action: PfKeyAction.Enter,
        label: PF_ENTER_LABEL,
        onActivate: activateEnter,
      },
      { action: PfKeyAction.PF3, label: PF3_LABEL, onActivate: activateExit },
      { action: PfKeyAction.PF4, label: PF4_LABEL, onActivate: activateClear },
      {
        action: PfKeyAction.PF12,
        label: PF12_LABEL,
        onActivate: activateUnhandledKey,
      },
    ];
    setChrome({
      transactionId: TRANSACTION_ID,
      programName: PROGRAM_NAME,
      title01: CCDA_TITLE01,
      title02: CCDA_TITLE02,
      errorMessage,
      infoMessage,
      pfKeys,
      busy: loading,
    });
  }, [
    activateClear,
    activateEnter,
    activateExit,
    activateUnhandledKey,
    errorMessage,
    infoMessage,
    loading,
    setChrome,
  ]);

  return (
    // The screen body is a labelled region, as every other screen's body is: without one
    // the five entry rows sit directly in `main` with no programmatic grouping.
    <section className="userAdd__screen" aria-labelledby="userAddHeading">
      <h3 id="userAddHeading" className="neutral">
        Add User
      </h3>
      <form className="userAdd" onSubmit={handleSubmit}>
        <div className="userAdd__row">
          <label className="prompt" htmlFor="fname">
            First Name:
          </label>
          <input
            ref={firstNameRef}
            id="fname"
            name="fname"
            data-testid="fname"
            className="field"
            disabled={loading}
            type="text"
            value={fields.firstName}
            maxLength={FIELD_MAX_LENGTH.firstName}
            size={FIELD_MAX_LENGTH.firstName}
            aria-required="true"
            {...invalidFieldProps(faultedField === 'firstName')}
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
            disabled={loading}
            type="text"
            value={fields.lastName}
            maxLength={FIELD_MAX_LENGTH.lastName}
            size={FIELD_MAX_LENGTH.lastName}
            aria-required="true"
            {...invalidFieldProps(faultedField === 'lastName')}
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
            disabled={loading}
            type="text"
            value={fields.userId}
            maxLength={FIELD_MAX_LENGTH.userId}
            size={FIELD_MAX_LENGTH.userId}
            aria-required="true"
            {...invalidFieldProps(faultedField === 'userId', 'userid-hint')}
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
            disabled={loading}
            type="password"
            value={fields.password}
            maxLength={FIELD_MAX_LENGTH.password}
            size={FIELD_MAX_LENGTH.password}
            aria-required="true"
            {...invalidFieldProps(faultedField === 'password', 'passwd-hint')}
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
            disabled={loading}
            type="text"
            value={fields.userType}
            maxLength={FIELD_MAX_LENGTH.userType}
            size={FIELD_MAX_LENGTH.userType}
            aria-required="true"
            {...invalidFieldProps(faultedField === 'userType', 'usrtype-hint')}
            onChange={updateField('userType')}
          />
          <span className="label" id="usrtype-hint">
            (A=Admin, U=User)
          </span>
        </div>
      </form>
    </section>
  );
}

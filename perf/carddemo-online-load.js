/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

/**
 * carddemo-online-load
 * ====================
 *
 * :purpose: Measure the online non-functional targets of AAP 0.7.1 against a running
 *     CardDemo stack: response time under 200 ms at the 95th percentile while 150
 *     concurrent users are signed on. Every request goes through the api-gateway on the
 *     same session-cookie and CSRF contract the React SPA uses, so what is measured is
 *     the deployed request path (gateway route, session lookup in Redis, downstream
 *     service, PostgreSQL) rather than a service in isolation.
 * :output: A k6 summary carrying ``http_req_duration`` per named endpoint and overall,
 *     the achieved request rate, and pass/fail against the declared thresholds. Run with
 *     ``--summary-export`` to persist the measurement.
 *
 * Usage::
 *
 *     k6 run perf/carddemo-online-load.js
 *     BASE_URL=http://localhost:8080 VUS=150 DURATION=2m \
 *         k6 run --summary-export perf-summary.json perf/carddemo-online-load.js
 *
 * Environment:
 *     ``BASE_URL``    api-gateway base URL (default ``http://localhost:8080``).
 *     ``VUS``         TOTAL concurrent signed-on users (default ``150``), split across
 *                     the three mixes below so the run holds exactly the AAP 0.7.1
 *                     concurrency figure rather than that figure plus the write users.
 *     ``WRITE_VUS``   users within ``VUS`` driving the update mix (default ``5``).
 *     ``ADMIN_VUS``   users within ``VUS`` driving the administration mix (default ``5``).
 *     ``DURATION``    steady-state duration (default ``2m``).
 *     ``THINK_MS``    upper bound of the per-screen think time (default ``1000``).
 *
 * :note: The three mixes together drive all 17 screens of AAP 0.3.5. The inquiry mix
 *     covers sign-on, main menu, account view, card list, card detail, transaction list
 *     and transaction view, plus the administrator user list. The update mix covers card
 *     update and account update. The administration mix covers the administrator menu,
 *     the add/update/delete user screens, bill payment, add transaction and the report
 *     request.
 * :note: Both update-mix screens submit the values the screen just read, so they exercise
 *     the whole read-compare-rewrite path and its transaction boundary — including the
 *     ``@Version`` optimistic-lock check of AAP 0.6.2. Each write user owns a distinct
 *     account, so the measurement carries no manufactured lock contention. The card-update
 *     screen echoes its record exactly; the account-update screen must additionally correct
 *     five customer fields that the seeded fixture carries in a form ``COACTUPC`` refuses,
 *     which normalises those fields once on the ``WRITE_VUS`` accounts the mix owns. See
 *     ``updateAccountInPlace``, and re-seed the database after a run to restore the fixture.
 * :note: Bill payment and the report request are driven on their CONFIRMATION-GATE path,
 *     with a blank ``confirm`` flag. That is not a shortcut: it is the first-ENTER
 *     behaviour of ``COBIL00C`` and ``CORPT00C``, which redisplay the screen with a
 *     confirmation prompt and mutate nothing until the operator confirms. It measures the
 *     full request path — gateway, session, downstream service, account read and balance
 *     computation, or the whole date-window validation — while keeping the run repeatable:
 *     a confirmed bill payment zeroes an account balance, and a confirmed report request
 *     launches a Spring Batch job per call, so sustaining either for minutes at 150 users
 *     would destroy the fixture the measurement is taken against. The confirmed branches
 *     are covered by the service integration suites, and the batch jobs are timed
 *     separately (see the performance section of ``README-target.md``).
 * :note: Add-transaction IS driven on its confirmed path, because that is where the
 *     sequence-backed transaction-id generation of AAP 0.6.5 lives and it is worth
 *     measuring under concurrency. It appends rows; the seeded 300 are untouched, and the
 *     growth is bounded by the administration mix being the smallest of the three.
 *     The user add/update/delete cycle is self-cleaning: every iteration creates its own
 *     user id, updates it, and deletes it again, so the security table returns to its
 *     seeded ten rows.
 * :note: Requests carry an explicit ``name`` tag rather than letting k6 group by URL, so
 *     a path that embeds an identifier is reported as ONE endpoint instead of fifty.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

/** api-gateway base URL; every request is same-origin against it. */
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/** TOTAL concurrent signed-on users, split across the three mixes. */
const VUS = Number(__ENV.VUS || 150);

/** Concurrent signed-on users driving the update mix, taken out of ``VUS``. */
const WRITE_VUS = Number(__ENV.WRITE_VUS || 5);

/** Concurrent signed-on users driving the administration mix, taken out of ``VUS``. */
const ADMIN_VUS = Number(__ENV.ADMIN_VUS || 5);

/**
 * :purpose: Users left for the inquiry mix once the update and administration mixes have
 *     taken their share, so the three scenarios sum to exactly ``VUS``.
 */
const READ_VUS = Math.max(VUS - WRITE_VUS - ADMIN_VUS, 1);

/** Steady-state duration of both scenarios. */
const DURATION = __ENV.DURATION || '2m';

/** Upper bound of the per-screen think time, in milliseconds. */
const THINK_MS = Number(__ENV.THINK_MS || 1000);

/**
 * :purpose: Window over which virtual users arrive and sign on, in milliseconds. A
 *     ``constant-vus`` scenario starts every virtual user in the same instant, which no
 *     population of operators does: it would fire all ``VUS`` sign-ons simultaneously and
 *     make the sign-on percentile a measurement of a thundering herd against the password
 *     encoder rather than of the endpoint. Each user waits a slice of this window
 *     proportional to its index before its first request, so arrivals are spread.
 */
const RAMP_MS = Number(__ENV.RAMP_MS || 15000);

/**
 * :purpose: Edit-clean values for the five customer fields the seeded fixture carries in a
 *     form ``COACTUPC`` refuses, so the account-update screen can be driven to a successful
 *     rewrite. See ``updateAccountInPlace`` for why this substitution is necessary and what
 *     it costs.
 */
const EDIT_CLEAN_CUSTOMER_FIELDS = {
  custFicoCreditScore: 700,
  custPhoneNum1: '(201)555-0100',
  custPhoneNum2: '(201)555-0101',
  custAddrStateCd: 'WA',
  custAddrZip: '98101',
};

/**
 * :purpose: Expected statuses of the pre-sign-on session probe. ``GET /session`` answers
 *     ``401`` to an anonymous caller BY DESIGN — that answer is what tells the SPA to draw
 *     the sign-on screen, and it is the response that primes the CSRF cookie — so it must
 *     not be counted as a failed request.
 */
const ANONYMOUS_PROBE_STATUSES = http.expectedStatuses(200, 401);

/**
 * :purpose: Expected statuses of the account-update submit. ``409`` is the documented answer
 *     when the record was rewritten between this screen's read and its submit (AAP 0.6.2,
 *     ``COACTUPC`` "Record changed by some one else"), so it is a correct response rather
 *     than a failed request.
 */
const ACCOUNT_UPDATE_STATUSES = http.expectedStatuses(200, 409);

/** The 200 ms 95th-percentile target of AAP 0.7.1, in milliseconds. */
const P95_TARGET_MS = 200;

/** Seeded administrator ids; ``SEC-USR-TYPE`` 'A' reaches the user-administration screens. */
const ADMIN_IDS = ['ADMIN001', 'ADMIN002', 'ADMIN003', 'ADMIN004', 'ADMIN005'];

/** Seeded ordinary-user ids; ``SEC-USR-TYPE`` 'U'. */
const USER_IDS = ['USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005'];

/** The seeded credential shared by every fixture user (``app/jcl/DUSRSECJ.jcl``). */
const PASSWORD = 'PASSWORD';

/** Number of seeded accounts, one card each (``V3__seed_test_data.sql``). */
const SEEDED_ACCOUNTS = 50;

/**
 * :purpose: Every endpoint the three mixes drive, each of which must individually meet the
 *     95th-percentile target. Keeping one flat list means a newly driven screen cannot be
 *     measured without also being held to the target.
 */
const MEASURED_ENDPOINTS = [
  // inquiry mix
  'GET /session',
  'POST /auth/signon',
  'GET /menu',
  'POST /menu/select',
  'GET /accounts/{id}',
  'GET /cards',
  'GET /cards/{cardNumber}',
  'GET /transactions',
  'GET /transactions/{id}',
  'GET /users',
  // update mix
  'PUT /cards/{cardNumber}',
  'PUT /accounts/{id}',
  // administration mix
  'GET /admin/menu',
  'POST /admin/menu/select',
  'POST /users',
  'GET /users/{id}',
  'PUT /users/{id}',
  'DELETE /users/{id}',
  'POST /billpay',
  'POST /reports',
  'GET /transactions/last',
  'POST /transactions',
];

/**
 * :purpose: Build the threshold map: the overall 95th percentile, the per-scenario and
 *     per-endpoint 95th percentiles, and the error-rate ceiling.
 * :returns: the k6 ``thresholds`` object.
 */
function buildThresholds() {
  const thresholds = {
    http_req_duration: [`p(95)<${P95_TARGET_MS}`],
    'http_req_duration{scenario:online_reads}': [`p(95)<${P95_TARGET_MS}`],
    'http_req_duration{scenario:online_writes}': [`p(95)<${P95_TARGET_MS}`],
    'http_req_duration{scenario:admin_workflows}': [`p(95)<${P95_TARGET_MS}`],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  };
  for (const name of MEASURED_ENDPOINTS) {
    thresholds[`http_req_duration{name:${name}}`] = [`p(95)<${P95_TARGET_MS}`];
  }
  return thresholds;
}

export const options = {
  discardResponseBodies: false,
  scenarios: {
    online_reads: {
      executor: 'constant-vus',
      vus: READ_VUS,
      duration: DURATION,
      exec: 'onlineReads',
      tags: { scenario: 'online_reads' },
    },
    online_writes: {
      executor: 'constant-vus',
      vus: WRITE_VUS,
      duration: DURATION,
      exec: 'onlineWrites',
      tags: { scenario: 'online_writes' },
    },
    admin_workflows: {
      executor: 'constant-vus',
      vus: ADMIN_VUS,
      duration: DURATION,
      exec: 'adminWorkflows',
      tags: { scenario: 'admin_workflows' },
    },
  },
  thresholds: buildThresholds(),
};

/**
 * :purpose: Produce a per-run identifier so the administration mix can mint user ids that
 *     cannot collide with a user left behind by an earlier run whose final iteration was
 *     cut short at the duration boundary.
 * :returns: the setup payload handed to every VU function: a two-character run tag.
 */
export function setup() {
  const tag = Math.floor(Math.random() * 1296)
    .toString(36)
    .padStart(2, '0');
  return { runTag: tag };
}

/**
 * :purpose: This VU's cookie jar, holding the Spring Session cookie and the CSRF cookie
 *     for the whole run. An EXPLICIT jar is required: k6 resets the implicit per-VU jar at
 *     the start of every iteration, which would sign each user out after one screen and
 *     turn the measurement into a stream of 401s.
 */
const jar = new http.CookieJar();

/** Per-VU sign-on state, so each virtual user signs on once and then keeps its session. */
let session = null;

/**
 * :purpose: Read the double-submit CSRF token out of this VU's cookie jar, as the SPA's
 *     axios interceptor reads it from ``document.cookie``.
 * :returns: the token value, or an empty string when no cookie has been issued yet.
 */
function csrfToken() {
  const cookies = jar.cookiesForURL(`${BASE_URL}/`);
  return cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : '';
}

/**
 * :purpose: Sign this VU on once, priming the CSRF cookie first exactly as the SPA does
 *     with its initial ``GET /session`` probe.
 * :param admin: ``true`` to sign on as an administrator, so the role-gated user-
 *     administration screens are exercised.
 * :returns: the session descriptor: the signed-on user id and the account it owns.
 */
function signOn(admin) {
  // Spread arrivals across the ramp window; see RAMP_MS.
  sleep((((__VU - 1) % Math.max(VUS, 1)) / Math.max(VUS, 1)) * (RAMP_MS / 1000));

  http.get(`${BASE_URL}/session`, {
    jar,
    responseCallback: ANONYMOUS_PROBE_STATUSES,
    tags: { name: 'GET /session' },
  });

  const pool = admin ? ADMIN_IDS : USER_IDS;
  const userId = pool[__VU % pool.length];
  const response = http.post(
    `${BASE_URL}/auth/signon`,
    JSON.stringify({ userId, password: PASSWORD }),
    {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'POST /auth/signon' },
    },
  );
  check(response, { 'sign-on succeeded': (r) => r.status === 200 });

  return { userId, admin, accountId: (__VU % SEEDED_ACCOUNTS) + 1 };
}

/**
 * :purpose: Ensure this VU is signed on, signing on lazily on its first iteration.
 * :param admin: ``true`` for an administrator session.
 * :returns: the session descriptor.
 */
function currentSession(admin) {
  if (session === null) {
    session = signOn(admin);
  }
  return session;
}

/**
 * :purpose: Pause for a bounded, randomized think time, so 150 signed-on users produce a
 *     request pattern shaped like operators moving between screens rather than a tight
 *     loop.
 */
function think() {
  sleep((Math.random() * THINK_MS) / 1000);
}

/**
 * :purpose: One operator's inquiry round trip: the menu, an account, that account's card
 *     list and card detail, the transaction list and one transaction, and — for an
 *     administrator — the user list.
 */
export function onlineReads() {
  const current = currentSession(__VU % 3 === 0);

  check(http.get(`${BASE_URL}/menu`, { jar, tags: { name: 'GET /menu' } }), {
    'menu published options': (r) => r.status === 200,
  });
  think();

  check(
    http.post(`${BASE_URL}/menu/select`, JSON.stringify({ option: '1' }), {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'POST /menu/select' },
    }),
    { 'menu selection resolved': (r) => r.status === 200 },
  );
  think();

  check(
    http.get(`${BASE_URL}/accounts/${current.accountId}`, {
      jar,
      tags: { name: 'GET /accounts/{id}' },
    }),
    { 'account view returned': (r) => r.status === 200 },
  );
  think();

  const list = http.get(`${BASE_URL}/cards?accountId=${current.accountId}&page=1`, {
    jar,
    tags: { name: 'GET /cards' },
  });
  check(list, { 'card list returned': (r) => r.status === 200 });

  const cardNumber = firstCardNumber(list);
  if (cardNumber !== null) {
    think();
    check(
      http.get(`${BASE_URL}/cards/${cardNumber}?accountId=${current.accountId}`, {
        jar,
        tags: { name: 'GET /cards/{cardNumber}' },
      }),
      { 'card detail returned': (r) => r.status === 200 },
    );
  }
  think();

  const transactions = http.get(`${BASE_URL}/transactions?page=1`, {
    jar,
    tags: { name: 'GET /transactions' },
  });
  check(transactions, { 'transaction list returned': (r) => r.status === 200 });

  const transactionId = firstTransactionId(transactions);
  if (transactionId !== null) {
    think();
    check(
      http.get(`${BASE_URL}/transactions/${transactionId}`, {
        jar,
        tags: { name: 'GET /transactions/{id}' },
      }),
      { 'transaction view returned': (r) => r.status === 200 },
    );
  }

  if (current.admin) {
    think();
    check(http.get(`${BASE_URL}/users?page=1`, { jar, tags: { name: 'GET /users' } }), {
      'user list returned': (r) => r.status === 200,
    });
  }
  think();
}

/**
 * :purpose: One operator's update round trip: read a card, then submit the card-update
 *     screen carrying the values just read. Each write user owns a distinct account, so
 *     the measurement carries no manufactured lock contention and the seeded values are
 *     unchanged by construction.
 */
export function onlineWrites() {
  const current = currentSession(true);

  const list = http.get(`${BASE_URL}/cards?accountId=${current.accountId}&page=1`, {
    jar,
    tags: { name: 'GET /cards' },
  });
  const cardNumber = firstCardNumber(list);
  if (cardNumber === null) {
    think();
    return;
  }

  const detail = http.get(`${BASE_URL}/cards/${cardNumber}?accountId=${current.accountId}`, {
    jar,
    tags: { name: 'GET /cards/{cardNumber}' },
  });
  check(detail, { 'card detail returned': (r) => r.status === 200 });

  let card;
  try {
    card = detail.json();
  } catch (error) {
    return;
  }
  if (card === null || card.cardEmbossedName === undefined) {
    return;
  }
  think();

  const body = JSON.stringify({
    cardEmbossedName: card.cardEmbossedName,
    cardActiveStatus: card.cardActiveStatus,
    cardExpiraionDate: card.cardExpiraionDate,
  });
  check(
    http.put(`${BASE_URL}/cards/${cardNumber}?accountId=${current.accountId}`, body, {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'PUT /cards/{cardNumber}' },
    }),
    { 'card update accepted': (r) => r.status === 200 },
  );
  think();

  updateAccountInPlace(current);
  think();
}

/**
 * :purpose: Drive the account-update screen (``COACTUPC``) as one operator does: read the
 *     account, then submit the update carrying the values just read together with the
 *     ``version`` snapshot the screen was displayed with. Echoing the read values means the
 *     stored record is unchanged while the full edit, compare and rewrite path — and the
 *     ``@Version`` optimistic-lock check of AAP 0.6.2 — is exercised and measured.
 * :param current: this virtual user's session descriptor, supplying the account it owns.
 * :note: The regulated identifiers (SSN, government-issued id, EFT account id) come back
 *     from the read MASKED, and are submitted back masked. That is the real SPA round
 *     trip: ``AccountMapper.retainWhenMasked`` recognises an echoed mask and keeps the
 *     stored value, so a masked echo cannot overwrite the ciphertext.
 * :note: Five customer fields are submitted as edit-clean literals rather than echoed,
 *     because the seeded fixture is faithful to the legacy customer file and that file
 *     carries values the update screen's own edits refuse: NO seeded account can be
 *     rewritten unchanged. Measured against the shipped fixture, all 50 are rejected — 21
 *     for a FICO score outside 300-850, 22 for a phone area code absent from the North
 *     American lookup table, and the remainder for a state code or a state/zip pair the
 *     cross-edit refuses. That is authentic ``COACTUPC`` behaviour: an operator arriving at
 *     any of these accounts must correct the flagged field before the rewrite is accepted.
 *     Substituting the five fields is that correction. It normalises them once on the
 *     accounts the update mix owns — ``WRITE_VUS`` accounts, no more — after which every
 *     iteration is a pure unchanged echo. Re-seed the database after a run to restore the
 *     fixture exactly.
 */
function updateAccountInPlace(current) {
  const accountId = current.accountId;

  const read = http.get(`${BASE_URL}/accounts/${accountId}`, {
    jar,
    tags: { name: 'GET /accounts/{id}' },
  });
  if (read.status !== 200) {
    return;
  }

  let account;
  try {
    account = read.json();
  } catch (error) {
    return;
  }
  if (account === null || account.version === undefined) {
    return;
  }
  think();

  const body = JSON.stringify({
    ...{
    version: account.version,
    acctActiveStatus: account.acctActiveStatus,
    acctCurrBal: account.acctCurrBal,
    acctCreditLimit: account.acctCreditLimit,
    acctCashCreditLimit: account.acctCashCreditLimit,
    acctOpenDate: account.acctOpenDate,
    acctExpiraionDate: account.acctExpiraionDate,
    acctReissueDate: account.acctReissueDate,
    acctCurrCycCredit: account.acctCurrCycCredit,
    acctCurrCycDebit: account.acctCurrCycDebit,
    acctGroupId: account.acctGroupId,
    custFirstName: account.custFirstName,
    custMiddleName: account.custMiddleName,
    custLastName: account.custLastName,
    custAddrLine1: account.custAddrLine1,
    custAddrLine2: account.custAddrLine2,
    custAddrLine3: account.custAddrLine3,
    custAddrStateCd: account.custAddrStateCd,
    custAddrCountryCd: account.custAddrCountryCd,
    custAddrZip: account.custAddrZip,
    custPhoneNum1: account.custPhoneNum1,
    custPhoneNum2: account.custPhoneNum2,
    custSsn: account.custSsn,
    custGovtIssuedId: account.custGovtIssuedId,
    custDobYyyyMmDd: account.custDobYyyyMmDd,
    custEftAccountId: account.custEftAccountId,
    custPriCardHolderInd: account.custPriCardHolderInd,
    custFicoCreditScore: account.custFicoCreditScore,
    },
    ...EDIT_CLEAN_CUSTOMER_FIELDS,
  });

  check(
    http.put(`${BASE_URL}/accounts/${accountId}`, body, {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      responseCallback: ACCOUNT_UPDATE_STATUSES,
      tags: { name: 'PUT /accounts/{id}' },
    }),
    {
      // 409 is the CORRECT answer when another user rewrote the record between this
      // screen's read and its submit (AAP 0.6.2), so it is a pass, not an error.
      'account update accepted or reported a conflict': (r) =>
        r.status === 200 || r.status === 409,
    },
  );
}

/**
 * :purpose: One administrator's round trip across the screens the inquiry and update mixes
 *     do not reach: the administrator menu, the add/update/delete user cycle, bill payment,
 *     the report request and add transaction. Held to the smallest share of the virtual
 *     users because two of these screens are the only ones that can grow or zero seeded
 *     data.
 * :param data: the setup payload carrying this run's tag, used to mint user ids that cannot
 *     collide with a user left behind by an earlier run.
 */
export function adminWorkflows(data) {
  const current = currentSession(true);

  check(http.get(`${BASE_URL}/admin/menu`, { jar, tags: { name: 'GET /admin/menu' } }), {
    'admin menu published options': (r) => r.status === 200,
  });
  think();

  check(
    http.post(`${BASE_URL}/admin/menu/select`, JSON.stringify({ option: '1' }), {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'POST /admin/menu/select' },
    }),
    { 'admin menu selection resolved': (r) => r.status === 200 },
  );
  think();

  cycleUser(data);
  think();

  // COBIL00C first ENTER: the screen reads the account and redisplays with the balance and
  // a confirmation prompt. Nothing is written until the operator confirms.
  check(
    http.post(
      `${BASE_URL}/billpay`,
      JSON.stringify({ accountId: String(current.accountId), confirm: '' }),
      {
        jar,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
        tags: { name: 'POST /billpay' },
      },
    ),
    { 'bill payment prompted for confirmation': (r) => r.status === 200 },
  );
  think();

  // CORPT00C first ENTER: validate the requested window and redisplay with a confirmation
  // prompt. The batch job is launched only on confirmation.
  check(
    http.post(
      `${BASE_URL}/reports`,
      JSON.stringify({ monthly: 'Y', confirm: '' }),
      {
        jar,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
        tags: { name: 'POST /reports' },
      },
    ),
    { 'report request prompted for confirmation': (r) => r.status === 200 },
  );
  think();

  addTransaction(current);
  think();
}

/**
 * :purpose: Drive the add, update and delete user screens (``COUSR01C``, ``COUSR02C``,
 *     ``COUSR03C``) as one self-cleaning cycle: mint a user id unique to this run, virtual
 *     user and iteration, create it, read it back, update it, then delete it. The security
 *     table therefore returns to its seeded ten rows however many iterations run.
 * :param data: the setup payload carrying this run's tag.
 */
function cycleUser(data) {
  const userId = mintUserId(data);

  const created = http.post(
    `${BASE_URL}/users`,
    JSON.stringify({
      userId,
      firstName: 'Perf',
      lastName: 'Harness',
      userType: 'U',
      password: 'PASSWORD',
    }),
    {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'POST /users' },
    },
  );
  check(created, { 'user added': (r) => r.status === 201 });
  if (created.status !== 201) {
    return;
  }
  think();

  check(
    http.get(`${BASE_URL}/users/${userId}`, { jar, tags: { name: 'GET /users/{id}' } }),
    { 'user read back': (r) => r.status === 200 },
  );
  think();

  check(
    http.put(
      `${BASE_URL}/users/${userId}`,
      JSON.stringify({
        firstName: 'Perf',
        lastName: 'Updated',
        userType: 'U',
        password: 'PASSWORD',
      }),
      {
        jar,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
        tags: { name: 'PUT /users/{id}' },
      },
    ),
    { 'user updated': (r) => r.status === 200 },
  );
  think();

  check(
    http.del(`${BASE_URL}/users/${userId}`, null, {
      jar,
      headers: { 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'DELETE /users/{id}' },
    }),
    { 'user deleted': (r) => r.status === 204 },
  );
}

/**
 * :purpose: Mint a security-user id that is unique to this run, virtual user and iteration
 *     and fits ``SEC-USR-ID PIC X(8)`` exactly.
 * :param data: the setup payload carrying this run's two-character tag.
 * :returns: an 8-character user id.
 */
function mintUserId(data) {
  const tag = data && data.runTag ? data.runTag : '00';
  const vu = (__VU % 1296).toString(36).padStart(2, '0');
  const iteration = (__ITER % 46656).toString(36).padStart(3, '0');
  return `K${tag}${vu}${iteration}`.toUpperCase();
}

/**
 * :purpose: Drive the add-transaction screen (``COTRN02C``) on its CONFIRMED path, so the
 *     sequence-backed transaction-id generation of AAP 0.6.5 is measured under concurrency.
 *     The transaction list is read first, exactly as the screen's operator arrives from it.
 * :param current: this virtual user's session descriptor, supplying the account to post to.
 */
function addTransaction(current) {
  const last = http.get(`${BASE_URL}/transactions/last`, {
    jar,
    tags: { name: 'GET /transactions/last' },
  });
  check(last, { 'last transaction returned': (r) => r.status === 200 });
  think();

  const stamp = timestamp();
  const body = JSON.stringify({
    acctId: String(current.accountId),
    tranTypeCd: '01',
    tranCatCd: 5001,
    tranSource: 'POS TERM',
    tranDesc: 'Load harness transaction',
    tranAmt: '1.00',
    tranMerchantId: 123456789,
    tranMerchantName: 'Load Harness Merchant',
    tranMerchantCity: 'Seattle',
    tranMerchantZip: '98109',
    tranOrigTs: stamp,
    tranProcTs: stamp,
    confirm: 'Y',
  });

  check(
    http.post(`${BASE_URL}/transactions`, body, {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      tags: { name: 'POST /transactions' },
    }),
    { 'transaction added': (r) => r.status === 201 },
  );
}

/**
 * :purpose: Render the current instant in the 26-character COBOL timestamp form
 *     ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` that ``TRAN-ORIG-TS`` and ``TRAN-PROC-TS`` carry.
 * :returns: the formatted timestamp.
 */
function timestamp() {
  const now = new Date();
  const pad = (value, width) => String(value).padStart(width, '0');
  return (
    `${now.getUTCFullYear()}-${pad(now.getUTCMonth() + 1, 2)}-${pad(now.getUTCDate(), 2)}-` +
    `${pad(now.getUTCHours(), 2)}.${pad(now.getUTCMinutes(), 2)}.${pad(now.getUTCSeconds(), 2)}.` +
    `${pad(now.getUTCMilliseconds(), 3)}000`
  );
}

/**
 * :purpose: Read the first card number out of a card-list response.
 * :param response: the ``GET /cards`` response.
 * :returns: the card number, or ``null`` when the list is empty or unparseable.
 */
function firstCardNumber(response) {
  try {
    const body = response.json();
    if (body !== null && Array.isArray(body.cards) && body.cards.length > 0) {
      return body.cards[0].cardNum;
    }
  } catch (error) {
    return null;
  }
  return null;
}

/**
 * :purpose: Read the first transaction id out of a transaction-list response.
 * :param response: the ``GET /transactions`` response.
 * :returns: the transaction id, or ``null`` when the list is empty or unparseable.
 */
function firstTransactionId(response) {
  try {
    const body = response.json();
    if (body !== null && Array.isArray(body.transactions) && body.transactions.length > 0) {
      return body.transactions[0].tranId;
    }
  } catch (error) {
    return null;
  }
  return null;
}

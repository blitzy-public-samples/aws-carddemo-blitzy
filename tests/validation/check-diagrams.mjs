// SPDX-License-Identifier: Apache-2.0
/**
 * check-diagrams — renders every Mermaid file under docs/architecture/ with the pinned mmdc
 * and checks diagram integrity against the component catalogue, the security control table
 * and the environment-variable table of docs/technical-requirements.md:
 *
 * - every file renders to a non-empty SVG;
 * - the logical (flowchart LR), physical (flowchart TB) and four sequence diagrams
 *   (sequenceDiagram) exist at their fixed paths, and the security view is
 *   security-architecture.mmd (flowchart TB) or its split into
 *   security-architecture-network.mmd and security-architecture-access.mmd;
 * - at most 25 nodes or participants per file, annotations (Legend*, Acm*, Description)
 *   counted; no component without an edge or message;
 * - every component ID is a catalogue name, every subgraph ID a boundary name or Legend,
 *   and every component label runs 1–5 words after its P<n>/S<n> prefix;
 * - frontmatter title, a closing description of 1–2 complete sentences and all five
 *   legend entries in their category colours (flowchart Legend subgraph; sequence box
 *   groups);
 * - the security view maps every S<n> control to its components in the diagram form of
 *   its kind, and carries no physical P<n> numbering;
 * - every external system appears in the logical, physical and security views;
 * - labels, aliases and fills are read as the pinned Mermaid renders them: a later label
 *   or alias wins, #name; and #nnn; entity codes are decoded, and class fills apply in
 *   default → node → assignment order with style statements last;
 * - the catalogue's Diagrams column names exactly the diagrams each ID appears in, and
 *   every flowchart component carries the fill of its catalogue category.
 *
 * Run from the repository root as `node tests/validation/check-diagrams.mjs` (as run-all.sh
 * does), or from any directory by the script's absolute path; `-h` or `--help` prints usage.
 * Exit 0 with no violation, 1 with violations, 2 on a usage error or an internal error.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { createRequire } from 'node:module';
import {
  repoRoot,
  readText,
  exists,
  listFiles,
  sectionByHeading,
  parseTables,
  plainText,
  surplusCellRows,
  toolBin,
  report,
} from './lib/markdown.mjs';
import { NAMED_CHARACTER_REFERENCES, C1_NUMERIC_REFERENCES } from './lib/html-entities.mjs';

const CHECK = 'check-diagrams';
const DIAGRAM_DIR = 'docs/architecture';
const TRD = 'docs/technical-requirements.md';
const PUPPETEER_CONFIG = 'tests/validation/puppeteer-config.json';
/** Launches the chrome-headless-shell build pinned by the locked puppeteer for every render. */
const BROWSER_LAUNCHER = 'tests/validation/chrome-headless-shell.sh';
const RENDER_TIMEOUT_MS = 180000;
const RENDER_MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
/** Signals that stop a render in progress; the check then exits with 128 + the signal number. */
const STOP_SIGNALS = ['SIGINT', 'SIGTERM', 'SIGHUP'];
/** How long a stopped mmdc gets to close its browser before it is killed. */
const STOP_GRACE_MS = 5000;
const NODE_BUDGET = 25;
const LOGICAL = `${DIAGRAM_DIR}/logical-architecture.mmd`;
const PHYSICAL = `${DIAGRAM_DIR}/physical-deployment.mmd`;
const SECURITY_RE = /^security-architecture.*\.mmd$/;

/**
 * Diagrams the set must hold at fixed paths, each with the kind of its view and, for a
 * flowchart, its direction. The TRD catalogue's Diagrams column is editable, so it cannot
 * stand in for this list.
 */
const REQUIRED_DIAGRAMS = [
  { rel: LOGICAL, kind: 'flowchart', direction: 'LR' },
  { rel: PHYSICAL, kind: 'flowchart', direction: 'TB' },
  { rel: `${DIAGRAM_DIR}/sequence-balance-summary.mmd`, kind: 'sequence', direction: null },
  { rel: `${DIAGRAM_DIR}/sequence-category-balances.mmd`, kind: 'sequence', direction: null },
  { rel: `${DIAGRAM_DIR}/sequence-transaction-list.mmd`, kind: 'sequence', direction: null },
  { rel: `${DIAGRAM_DIR}/sequence-transaction-detail.mmd`, kind: 'sequence', direction: null },
];
/** The security view: one flowchart TB, or the split rule's two flowchart parts, whose direction is free. */
const SECURITY_VIEW = { rel: `${DIAGRAM_DIR}/security-architecture.mmd`, kind: 'flowchart', direction: 'TB' };
const SECURITY_SPLIT = [
  { rel: `${DIAGRAM_DIR}/security-architecture-network.mmd`, kind: 'flowchart', direction: null },
  { rel: `${DIAGRAM_DIR}/security-architecture-access.mmd`, kind: 'flowchart', direction: null },
];

/** Category order is also the required sequence-diagram box order. */
const CATEGORIES = [
  { name: 'Compute', legend: 'LegendCompute', rgb: [173, 216, 230] },
  { name: 'Data stores', legend: 'LegendDataStores', rgb: [144, 238, 144] },
  { name: 'External systems', legend: 'LegendExternal', rgb: [211, 211, 211] },
  { name: 'Security', legend: 'LegendSecurity', rgb: [244, 204, 204] },
  { name: 'Networking', legend: 'LegendNetworking', rgb: [255, 255, 224] },
];
const CATEGORY_BY_NAME = new Map(CATEGORIES.map((c) => [c.name.toLowerCase(), c]));
const LEGEND_IDS = new Set(CATEGORIES.map((c) => c.legend));

/**
 * The canonical component list every diagram and the TRD catalogue share, with the
 * category each must carry there; the catalogue may neither drop nor recategorise one.
 */
const EXPECTED_COMPONENTS = new Map([
  ['CardServicingUi', 'Compute'],
  ['ApiGateway', 'Compute'],
  ['CardServicingService', 'Compute'],
  ['CategoryBalanceAggregator', 'Compute'],
  ['FixtureLoader', 'Compute'],
  ['AzurePostgres', 'Data stores'],
  ['AzureManagedRedis', 'Data stores'],
  ['AccountAccessGuard', 'Security'],
  ['AzureKeyVault', 'Security'],
  ['CardDataService', 'External systems'],
  ['TransactionActivityService', 'External systems'],
  ['ConfigService', 'External systems'],
  ['ReferenceDataService', 'External systems'],
  ['AuthService', 'External systems'],
  ['PepPostgres', 'Networking'],
  ['PepRedis', 'Networking'],
  ['PepKeyVault', 'Networking'],
]);
const BOUNDARIES = new Set(['AzureSubscription', 'AzureVNet', 'AksSubnet', 'PepSubnet', 'AzureAKS']);
const EXTERNAL_SYSTEMS = [...EXPECTED_COMPONENTS].filter(([, c]) => c === 'External systems').map(([n]) => n);
const SECRET_VARIABLES = ['AUTH_SERVICE_API_KEY', 'REDIS_PASSWORD', 'DB_PASSWORD'];
/** The security view's three-row access-control matrix: exactly these Acm* rows. */
const ACCESS_MATRIX_ROWS = ['AcmRegularUser', 'AcmAdminUser', 'AcmInvalidToken'];
const KIND = Object.freeze({
  MTLS: 'mTLS',
  SECRET: 'Secret',
  ENCRYPTION: 'Encryption at rest',
  AUTH: 'AuthN/authZ',
  THREAT: 'Threat boundary',
});
/**
 * The AuthN/authZ purposes the security view must cover, each with its own control row
 * (named in that row's key) and its own AccountAccessGuard → AuthService edge.
 */
const AUTH_PURPOSES = Object.freeze([
  { name: 'token validation', re: /\btoken validation\b/i },
  { name: 'account authorization', re: /\baccount authori[sz]ation\b/i },
]);

const isAnnotation = (id) => id.startsWith('Legend') || id.startsWith('Acm') || id === 'Description';
const normaliseSpace = (s) => String(s ?? '').replace(/\s+/g, ' ').trim();
const fmtRgb = (rgb) => `rgb(${rgb.join(',')})`;
const fmtHex = (rgb) => `#${rgb.map((v) => v.toString(16).padStart(2, '0')).join('')}`;

/** Shared plainText of a table cell, `\|` unescaped and `<br>` read as a space; whitespace collapsed. */
function cellText(cell) {
  return normaliseSpace(
    plainText(
      String(cell ?? '')
        .replace(/\\\|/g, '|')
        .replace(/<br\s*\/?>/gi, ' '),
    ),
  );
}

/** Comma-, semicolon- or "and"-separated identifiers of a table cell. */
function cellList(cell) {
  return cellText(cell)
    .split(/\s*(?:,|;|\band\b)\s*/)
    .map((s) => s.trim())
    .filter((s) => s !== '');
}

/** Mermaid label as displayed: tags become spaces, quotes drop, entities decode as flowchart HTML or sequence SVG. */
function labelText(raw, mode) {
  if (mode !== 'flowchart' && mode !== 'sequence') throw new TypeError(`labelText: unknown mode ${mode}`);
  const text = String(raw ?? '')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/"/g, '');
  return normaliseSpace(mode === 'flowchart' ? decodeHtmlLabel(text) : decodeEntityMarkers(text));
}

/** Private-use pair standing for Mermaid's entity marker, which keeps a `#…;` code's `;` from ending a statement. */
const ENTITY_OPEN = '\uE010';
const ENTITY_CLOSE = '\uE011';
const ENTITY_MARKER_RE = new RegExp(`${ENTITY_OPEN}(\\w+)${ENTITY_CLOSE}`, 'g');

/** Mermaid's encodeEntities: drops the `;` closing a style or classDef colour, then marks every `#…;` code. */
function encodeEntities(text) {
  return String(text)
    .replace(/style.*:\S*#.*;/g, (s) => s.slice(0, -1))
    .replace(/classDef.*:\S*#.*;/g, (s) => s.slice(0, -1))
    .replace(/#\w+;/g, (s) => `${ENTITY_OPEN}${s.slice(1, -1)}${ENTITY_CLOSE}`);
}

/**
 * Character a numeric reference displays: U+FFFD for 0, a surrogate or a value past U+10FFFF,
 * the Windows-1252 character for 0x80–0x9F, and otherwise the code point itself, a control
 * character included, as browsers keep it.
 */
function codePointText(value) {
  const invalid = value === 0 || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff);
  return invalid ? '\uFFFD' : (C1_NUMERIC_REFERENCES.get(value) ?? String.fromCodePoint(value));
}

/** A character reference as HTML text content reads one: `&#N` or `&#xH` with an optional `;`, or a name. */
const CHARACTER_REFERENCE_RE = /&(?:#([0-9]+);?|#[xX]([0-9A-Fa-f]+);?|([^\t\n\f <&#;]{1,32};?))/g;

/**
 * Decodes the character references of `html` in one left-to-right pass, as HTML text content
 * does: a numeric one through codePointText; a named one as the whole `name;` when the WHATWG
 * table holds it, else as the longest table name prefixing it (only a legacy name without its
 * `;` can, so `&notit;` reads `¬it;`) followed by the rest; any other `&` stays as written.
 */
function decodeCharacterReferences(html) {
  return html.replace(CHARACTER_REFERENCE_RE, (ref, dec, hex, name) => {
    if (dec !== undefined) return codePointText(Number.parseInt(dec, 10));
    if (hex !== undefined) return codePointText(Number.parseInt(hex, 16));
    const whole = NAMED_CHARACTER_REFERENCES.get(name);
    if (whole !== undefined) return whole;
    for (let end = name.length - 1; end > 1; end -= 1) {
      const prefix = NAMED_CHARACTER_REFERENCES.get(name.slice(0, end));
      if (prefix !== undefined) return prefix + name.slice(end);
    }
    return ref;
  });
}

/** The reference Mermaid writes for a marked code at display: `&#N;` for `#N;`, `&name;` for `#name;`. */
const markerReference = (inner) => (/^\d+$/.test(inner) ? `&#${inner};` : `&${inner};`);

/**
 * Sequence SVG text: each marked code reaches the SVG markup as its reference and decodes on
 * its own; all other text, a typed `&…;` included, is escaped there and displays as written.
 */
function decodeEntityMarkers(text) {
  return text.replace(ENTITY_MARKER_RE, (_, inner) => decodeCharacterReferences(markerReference(inner)));
}

/** Flowchart HTML label: markers become their references, then every reference decodes in one pass. */
function decodeHtmlLabel(text) {
  return decodeCharacterReferences(text.replace(ENTITY_MARKER_RE, (_, inner) => markerReference(inner)));
}

/**
 * Complete sentences of a text, and the unterminated fragment after the last one. The text
 * splits into words at whitespace, so a number such as 1.5 never ends a sentence. A sentence
 * is a run of words whose last word ends at `.`, `!` or `?`, optionally followed by closing
 * quotes or brackets (' " ’ ” ) ]), and which holds a letter or digit; a terminated run of
 * bare punctuation is not one. The words after the last terminator form the fragment, ''
 * when there are none.
 */
function sentenceCount(text) {
  let sentences = 0;
  let run = [];
  for (const word of normaliseSpace(text).split(' ')) {
    if (word === '') continue;
    run.push(word);
    if (!/[.!?]['"’”)\]]*$/.test(word)) continue;
    if (/[\p{L}\p{N}]/u.test(run.join(' '))) sentences += 1;
    run = [];
  }
  return { sentences, fragment: run.join(' ') };
}

/** Violations of the 1–2 complete sentence rule for `text`, each message opening with `subject`. */
function sentenceRuleMessages(subject, text) {
  const { sentences, fragment } = sentenceCount(text);
  const messages = [];
  if (fragment !== '') {
    const shown = [...fragment].slice(0, 40).join('');
    messages.push(`${subject} ends with an unterminated fragment "${shown}"; expected 1–2 complete sentences`);
  }
  // A fragment standing alone is already reported above, so it adds no zero-count message.
  if (sentences > 2 || (sentences === 0 && fragment === '')) {
    messages.push(`${subject} has ${sentences} sentences; expected 1–2`);
  }
  return messages;
}

/** Word count of a label after a leading P<n> or S<n> item number. */
function labelWords(label) {
  const words = normaliseSpace(label).replace(/^[PS]\d+\s+/, '');
  return words === '' ? 0 : words.split(' ').length;
}

/** `rgb(...)`, `rgba(..., 1)`, `#rgb` or `#rrggbb` as an [r, g, b] array, else null. */
function parseColour(token) {
  const s = String(token ?? '').toLowerCase().replace(/\s+/g, '');
  let m = /^#([0-9a-f]{3})$/.exec(s);
  if (m) return [...m[1]].map((h) => parseInt(h + h, 16));
  m = /^#([0-9a-f]{6})$/.exec(s);
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
  m = /^rgba?\((\d{1,3}),(\d{1,3}),(\d{1,3})(?:,(?:1|1\.0+|100%))?\)$/.exec(s);
  if (m) {
    const rgb = m.slice(1, 4).map(Number);
    return rgb.every((v) => v <= 255) ? rgb : null;
  }
  return null;
}

const sameColour = (token, rgb) => {
  const parsed = parseColour(token);
  return parsed !== null && parsed.every((v, i) => v === rgb[i]);
};

/** First stderr lines of a failed tool run, with the repository root removed. */
function firstLines(buffer, count = 4) {
  const text = Buffer.isBuffer(buffer) ? buffer.toString('utf8') : String(buffer ?? '');
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.split(`${repoRoot}${path.sep}`).join('').trimEnd())
    .filter((l) => l.trim() !== '')
    .slice(0, count);
  const joined = lines.join(' | ');
  return joined.length > 400 ? `${joined.slice(0, 397)}...` : joined || 'no stderr output';
}

// ---------------------------------------------------------------------------------------
// Rendering

/**
 * Renders every diagram with mmdc, one at a time, into a private temporary folder. Renders
 * run without blocking the event loop, so SIGINT, SIGTERM or SIGHUP stops the running mmdc,
 * removes the folder and exits with 128 + the signal number.
 */
async function renderAll(files, violations) {
  const mmdc = toolBin('mmdc');
  if (!mmdc) {
    violations.push({
      path: 'tests/validation/node_modules/.bin/mmdc',
      line: 1,
      message: 'mmdc is not installed (run npm ci in tests/validation); no diagram was rendered',
    });
    return;
  }
  if (!exists(PUPPETEER_CONFIG)) {
    violations.push({
      path: PUPPETEER_CONFIG,
      line: 1,
      message: 'puppeteer configuration is missing; no diagram was rendered',
    });
    return;
  }
  if (!exists(BROWSER_LAUNCHER)) {
    violations.push({
      path: BROWSER_LAUNCHER,
      line: 1,
      message: 'browser launcher is missing; no diagram was rendered',
    });
    return;
  }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'check-diagrams-'));
  const options = {
    cwd: repoRoot,
    // Chrome leaves scratch directories in TMPDIR; pointing it at this run's
    // temporary folder removes them with that folder. PUPPETEER_EXECUTABLE_PATH
    // selects the pinned browser over any host value or .puppeteerrc setting.
    env: { ...process.env, TMPDIR: tmp, PUPPETEER_EXECUTABLE_PATH: path.join(repoRoot, BROWSER_LAUNCHER) },
    encoding: 'buffer',
    timeout: RENDER_TIMEOUT_MS,
    maxBuffer: RENDER_MAX_OUTPUT_BYTES,
  };

  let child = null;
  let stopping = false;
  const stop = (signal) => {
    if (stopping) {
      // A repeated signal skips the grace period.
      child?.kill('SIGKILL');
      return;
    }
    stopping = true;
    const finish = () => {
      try {
        fs.rmSync(tmp, { recursive: true, force: true });
      } catch (err) {
        console.error(`${CHECK}: could not remove ${tmp}: ${err?.message ?? err}`);
      }
      process.exit(128 + os.constants.signals[signal]);
    };
    const running = child;
    if (running === null || running.pid === undefined || running.exitCode !== null || running.signalCode !== null) {
      finish();
      return;
    }
    running.once('exit', finish);
    setTimeout(() => running.kill('SIGKILL'), STOP_GRACE_MS);
    running.kill(signal);
  };
  /** One mmdc run; resolves with null on success, or with the error and stderr of a failure. */
  const render = (args) =>
    new Promise((resolve) => {
      child = execFile(mmdc, args, options, (err, _stdout, stderr) => {
        child = null;
        resolve(err ? { err, stderr } : null);
      });
      child.stdin?.end();
    });

  for (const signal of STOP_SIGNALS) process.on(signal, stop);
  try {
    for (const [index, rel] of files.entries()) {
      // Diagrams in different folders can share a basename; the index gives every input
      // its own output, so one render's SVG never stands in for another's.
      const svg = path.join(tmp, `${index + 1}-${path.basename(rel, '.mmd')}.svg`);
      const failed = await render(['-p', PUPPETEER_CONFIG, '-i', rel, '-o', svg]);
      if (failed) {
        const { err, stderr } = failed;
        // err.killed marks a child killed from here: the timeout does that, and stop() exits
        // before a stopped render is classified. An exceeded output bound carries its own
        // code and message instead.
        const why =
          err.killed ? `timed out after ${RENDER_TIMEOUT_MS / 1000} s`
          : typeof err.code === 'number' ? `exit status ${err.code}`
          : err.signal ? `killed by ${err.signal}`
          : String(err.message ?? err);
        violations.push({ path: rel, line: 1, message: `mmdc render failed (${why}): ${firstLines(stderr)}` });
        continue;
      }
      let written = null;
      try {
        written = fs.lstatSync(svg);
      } catch (err) {
        if (err.code !== 'ENOENT') throw err;
      }
      if (!written?.isFile() || written.size === 0) {
        violations.push({ path: rel, line: 1, message: 'mmdc exited 0 but wrote no SVG output' });
      }
    }
  } finally {
    for (const signal of STOP_SIGNALS) process.off(signal, stop);
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------------------
// TRD inputs: component catalogue, security control table, environment variables

/**
 * The first table of a TRD section whose header holds every wanted column, with indexes, its
 * row lines and its header line, where a row the table lacks is reported. Each of its rows
 * with more cells than the header is reported, since a renderer drops the surplus cells and
 * the named-column readers never see them; every row is still returned.
 */
function sectionTable(trdText, heading, wanted, violations) {
  const section = sectionByHeading(trdText, heading);
  if (!section) {
    violations.push({ path: TRD, line: 1, message: `section "${heading}" is missing` });
    return null;
  }
  for (const table of parseTables(section.text)) {
    const header = table.header.map((h) => cellText(h).toLowerCase());
    const cols = wanted.map((w) => header.indexOf(w));
    if (cols.every((c) => c >= 0)) {
      const col = Object.fromEntries(wanted.map((w, i) => [w, cols[i]]));
      const rows = table.rows.map((cells, i) => ({ cells, line: section.startLine + table.rowLines[i] - 1 }));
      const label = /\btable$/i.test(heading) ? heading : `${heading} table`;
      for (const r of surplusCellRows(table, label)) {
        violations.push({ path: TRD, line: section.startLine + r.line - 1, message: r.message });
      }
      return { col, rows, line: section.startLine + table.line - 1 };
    }
  }
  violations.push({
    path: TRD,
    line: section.startLine,
    message: `section "${heading}" has no table with columns ${wanted.map((w) => `"${w}"`).join(', ')}`,
  });
  return null;
}

/** Component catalogue: name → { category, responsibility, diagrams, line, boundary }. */
function loadCatalogue(trdText, violations) {
  const catalogue = new Map();
  const bad = (line, message) => violations.push({ path: TRD, line, message });
  const columns = ['name', 'category', 'responsibility', 'diagrams'];
  const table = sectionTable(trdText, 'Component catalogue', columns, violations);
  if (!table) return catalogue;
  for (const { cells, line } of table.rows) {
    const name = cellText(cells[table.col.name]);
    const category = cellText(cells[table.col.category]);
    const responsibility = cellText(cells[table.col.responsibility]);
    const diagrams = cellList(cells[table.col.diagrams]);
    if (catalogue.has(name)) {
      bad(line, `component catalogue lists ${name} twice`);
      continue;
    }
    const boundary = BOUNDARIES.has(name);
    if (!boundary && !EXPECTED_COMPONENTS.has(name)) {
      bad(line, `component catalogue row "${name}" is not a canonical component or boundary name`);
      continue;
    }
    if (boundary) {
      if (!/boundar/i.test(category)) {
        bad(line, `boundary ${name} has category "${category}"; expected a boundary category`);
      }
    } else if (category.toLowerCase() !== EXPECTED_COMPONENTS.get(name).toLowerCase()) {
      bad(line, `component ${name} has category "${category}"; expected "${EXPECTED_COMPONENTS.get(name)}"`);
    }
    if (responsibility === '') bad(line, `component ${name} has no responsibility`);
    catalogue.set(name, {
      category: boundary ? 'Boundary' : EXPECTED_COMPONENTS.get(name),
      responsibility,
      diagrams,
      line,
      boundary,
    });
  }
  for (const name of [...EXPECTED_COMPONENTS.keys(), ...BOUNDARIES]) {
    if (!catalogue.has(name)) bad(table.line, `component catalogue has no row for ${name}`);
  }
  return catalogue;
}

function classifyKind(kind) {
  const k = kind.toLowerCase();
  if (k.includes('mtls')) return KIND.MTLS;
  if (k.includes('secret')) return KIND.SECRET;
  if (k.includes('encryption')) return KIND.ENCRYPTION;
  if (k.includes('authn') || k.includes('authz') || k.includes('auth')) return KIND.AUTH;
  if (k.includes('threat') || k.includes('boundary')) return KIND.THREAT;
  return null;
}

/** Security control table: S<n> → { kind, protectedComponents, line }. */
function loadControls(trdText, catalogue, violations) {
  const controls = new Map();
  const bad = (line, message) => violations.push({ path: TRD, line, message });
  const columns = ['control', 'kind', 'protected components', 'key'];
  const table = sectionTable(trdText, 'Security control table', columns, violations);
  if (!table) return controls;
  const authPurposes = new Set();
  for (const { cells, line } of table.rows) {
    const id = cellText(cells[table.col.control]);
    if (!/^S\d+$/.test(id)) {
      bad(line, `security control "${id}" is not of the form S<n>`);
      continue;
    }
    if (controls.has(id)) {
      bad(line, `security control ${id} has more than one row`);
      continue;
    }
    const kindText = cellText(cells[table.col.kind]);
    const kind = classifyKind(kindText);
    if (!kind) bad(line, `security control ${id} has unclassifiable kind "${kindText}"`);
    const protectedComponents = cellList(cells[table.col['protected components']]);
    if (protectedComponents.length === 0) bad(line, `security control ${id} names no protected component`);
    for (const c of protectedComponents) {
      if (!catalogue.has(c)) {
        bad(line, `security control ${id} protects "${c}", which is not in the component catalogue`);
      }
    }
    // A control's key is the one- or two-sentence explanation its S<n> label points to.
    for (const message of sentenceRuleMessages(`security control ${id} key`, cellText(cells[table.col.key]))) {
      bad(line, message);
    }
    if (kind === KIND.AUTH) {
      const named = AUTH_PURPOSES.filter((p) => p.re.test(cellText(cells[table.col.key]))).map((p) => p.name);
      if (named.length > 1) {
        bad(line, `security control ${id} key names ${named.join(' and ')}; each needs its own AuthN/authZ control`);
      }
      named.forEach((p) => authPurposes.add(p));
    }
    controls.set(id, { kind, protectedComponents: protectedComponents.filter((c) => catalogue.has(c)), line });
  }
  // The security view must show every control kind, so each needs at least one row.
  const kinds = new Set([...controls.values()].map((c) => c.kind));
  for (const kind of Object.values(KIND)) {
    if (!kinds.has(kind)) bad(table.line, `security control table has no ${kind} control`);
  }
  // AuthN/authZ covers token validation and account authorization, each with its own row.
  for (const { name } of AUTH_PURPOSES) {
    if (!authPurposes.has(name)) bad(table.line, `security control table has no ${KIND.AUTH} control for ${name}`);
  }
  return controls;
}

/**
 * Components the environment-variable table lists as reading a deployed secret, each with
 * the rows that list it: reader → [{ variable, line }]. Every row of a secret is read.
 */
function loadSecretConsumers(trdText, catalogue, violations) {
  const consumers = new Map();
  const bad = (line, message) => violations.push({ path: TRD, line, message });
  const table = sectionTable(trdText, 'Environment variables', ['variable', 'read by'], violations);
  if (!table) return consumers;
  for (const variable of SECRET_VARIABLES) {
    const rows = table.rows.filter(({ cells }) => cellText(cells[table.col.variable]) === variable);
    if (rows.length === 0) {
      bad(table.line, `environment-variable table has no row for ${variable}`);
      continue;
    }
    for (const row of rows.slice(1)) {
      bad(row.line, `environment-variable table lists ${variable} again; its first row is line ${rows[0].line}`);
    }
    for (const row of rows) {
      const readers = cellList(row.cells[table.col['read by']]);
      if (readers.length === 0) bad(row.line, `${variable} names no reader`);
      for (const reader of readers) {
        if (catalogue.has(reader) && !catalogue.get(reader).boundary) {
          if (!consumers.has(reader)) consumers.set(reader, []);
          consumers.get(reader).push({ variable, line: row.line });
        } else {
          bad(row.line, `${variable} is read by "${reader}", which is not a catalogue component`);
        }
      }
    }
  }
  return consumers;
}

// ---------------------------------------------------------------------------------------
// Mermaid parsers

/** The pinned Mermaid's frontmatter pattern, with match indices for line numbers. */
const FRONTMATTER_RE = /^([^\S\n\r]*)-{3}\s*[\n\r](.*?)[\n\r]\1-{3}\s*[\n\r]+/ds;

/** js-yaml's `{ load, JSON_SCHEMA }`: undefined until first use, null when it does not resolve. */
let frontmatterYaml;

/**
 * Resolves js-yaml once, on first use, from the locked tool tree, where the mermaid
 * toolchain hoists it. Never add a YAML package to package.json for this: the manifest
 * pins exactly the three validation tools. When js-yaml does not resolve, reports that
 * once and returns null.
 */
function yamlLoader(violations) {
  if (frontmatterYaml !== undefined) return frontmatterYaml;
  frontmatterYaml = null;
  try {
    const { load, JSON_SCHEMA } = createRequire(new URL('./package.json', import.meta.url))('js-yaml');
    if (typeof load === 'function' && JSON_SCHEMA) frontmatterYaml = { load, JSON_SCHEMA };
  } catch {
    // Not resolvable from tests/validation; reported below.
  }
  if (frontmatterYaml === null) {
    violations.push({
      path: 'tests/validation/package.json',
      line: 1,
      message: 'js-yaml is not resolvable from tests/validation (run npm ci); frontmatter titles were not decoded',
    });
  }
  return frontmatterYaml;
}

/**
 * Decodes frontmatter as the pinned Mermaid does before rendering: line endings and tag
 * attributes normalised first, the opener's indentation stripped from the body, the body
 * loaded with js-yaml's JSON schema, a non-mapping read as no keys, and the title kept
 * only when truthy, as its string. Returns null when Mermaid sees no frontmatter, else
 * `{ start, title, titleType, error }`: the 0-based line index the diagram body starts
 * at, the title (null when Mermaid shows none, undefined when not decoded), the decoded
 * title's YAML type (string, boolean, number, sequence or mapping; null when the key is
 * absent or null, undefined when not decoded) and a YAML error located at its file line.
 */
function decodeFrontmatter(text, violations) {
  const cleaned = text
    .replace(/\r\n?/g, '\n')
    .replace(/<(\w+)([^>]*)>/g, (_, tag, attrs) => `<${tag}${attrs.replace(/="([^"]*)"/g, "='$1'")}>`);
  const m = FRONTMATTER_RE.exec(cleaned);
  if (!m) return null;
  const start = m[0].split('\n').length - 1;
  const yamlLine = cleaned.slice(0, m.indices[2][0]).split('\n').length;
  const indent = m[1];
  const yamlText = indent
    ? m[2].split('\n').map((l) => (l.startsWith(indent) ? l.slice(indent.length) : l)).join('\n')
    : m[2];
  const yaml = yamlLoader(violations);
  if (yaml === null) return { start, title: undefined, titleType: undefined, error: null };
  let parsed;
  try {
    parsed = yaml.load(yamlText, { schema: yaml.JSON_SCHEMA }) ?? {};
  } catch (err) {
    if (err?.name !== 'YAMLException') throw err;
    const line = yamlLine + (Number.isInteger(err.mark?.line) ? err.mark.line : 0);
    const message = `frontmatter is not valid YAML: ${err.reason || err.message}`;
    return { start, title: undefined, titleType: undefined, error: { line, message } };
  }
  if (typeof parsed !== 'object' || Array.isArray(parsed)) parsed = {};
  const value = parsed.title ?? null;
  // The JSON schema yields only strings, booleans, numbers, sequences and mappings.
  const titleType =
    value === null ? null : Array.isArray(value) ? 'sequence' : typeof value === 'object' ? 'mapping' : typeof value;
  return { start, title: value ? String(value) : null, titleType, error: null };
}

/**
 * Splits YAML frontmatter from the diagram body. The title is the one the pinned Mermaid
 * renders, null when it renders none, and undefined when the frontmatter was not
 * decoded; titleType is the decoded title's YAML type, as decodeFrontmatter returns it,
 * and null when there is no frontmatter. A frontmatter error carries its file line. Body
 * statements keep their file line numbers; blank lines, `%%` comments and `%%{…}%%`
 * directives are dropped.
 */
function splitSource(text, violations) {
  const lines = text.split(/\r?\n/);
  let title = null;
  let titleType = null;
  let start = 0;
  let frontmatterError = null;
  const frontmatter = decodeFrontmatter(text, violations);
  if (frontmatter) {
    ({ title, titleType, start } = frontmatter);
    frontmatterError = frontmatter.error;
  } else if (lines[0]?.trim() === '---') {
    frontmatterError = { line: 1, message: 'frontmatter opened with --- is never closed' };
  }
  const body = [];
  for (let i = start; i < lines.length; i++) {
    const t = lines[i].trim();
    if (t === '' || t.startsWith('%%')) continue;
    body.push({ text: t, line: i + 1 });
  }
  return { title, titleType, body, frontmatterError };
}

/** Flowchart statements of one line: `;` separates them outside quotes, brackets and |labels|. */
function splitFlowchartStatements(text) {
  const out = [];
  let cur = '';
  let quote = false;
  let depth = 0;
  let pipe = false;
  for (const ch of text) {
    if (ch === '"') quote = !quote;
    else if (!quote) {
      if ('[({'.includes(ch)) depth++;
      else if ('])}'.includes(ch)) depth = Math.max(0, depth - 1);
      else if (ch === '|' && depth === 0) pipe = !pipe;
      else if (ch === ';' && depth === 0 && !pipe) {
        out.push(cur);
        cur = '';
        continue;
      }
    }
    cur += ch;
  }
  out.push(cur);
  return out.map((s) => s.trim()).filter((s) => s !== '');
}

/** `key:value,key:value` style properties of classDef and style statements. */
function parseProps(text) {
  const props = new Map();
  for (const part of text.split(',')) {
    const colon = part.indexOf(':');
    if (colon > 0) props.set(part.slice(0, colon).trim().toLowerCase(), part.slice(colon + 1).trim());
  }
  return props;
}

const FLOW_ID_RE = /^[A-Za-z0-9_]+/;
/** Node shape delimiters, longest opener first so `[(` wins over `[`. */
const SHAPES = [
  ['(((', [')))']],
  ['([', ['])']],
  ['[(', [')]']],
  ['[[', [']]']],
  ['[/', ['/]', '\\]']],
  ['[\\', ['\\]', '/]']],
  ['((', ['))']],
  ['{{', ['}}']],
  ['[', [']']],
  ['(', [')']],
  ['{', ['}']],
  ['>', [']']],
];

function parseShape(s, i) {
  for (const [open, closers] of SHAPES) {
    if (!s.startsWith(open, i)) continue;
    let j = i + open.length;
    while (s[j] === ' ') j++;
    if (s[j] === '"') {
      const q = s.indexOf('"', j + 1);
      if (q < 0) return { error: 'quoted label is not closed' };
      let k = q + 1;
      while (s[k] === ' ') k++;
      const closer = closers.find((c) => s.startsWith(c, k));
      if (!closer) return { error: `label is not closed with ${closers.join(' or ')}` };
      return { label: s.slice(j + 1, q), end: k + closer.length };
    }
    let best = -1;
    let bestCloser = null;
    for (const c of closers) {
      const p = s.indexOf(c, i + open.length);
      if (p >= 0 && (best < 0 || p < best)) {
        best = p;
        bestCloser = c;
      }
    }
    if (best < 0) return { error: `label is not closed with ${closers.join(' or ')}` };
    return { label: s.slice(i + open.length, best), end: best + bestCloser.length };
  }
  return null;
}

/** Plain links, optionally followed by |label|; `~~~` is an invisible link. */
const ARROW_RE = new RegExp(
  String.raw`^(<|x|o)?(-{2,}>|-{3,}|-\.+->|-\.+-|={2,}>|={3,}|-{2,}[xo](?![A-Za-z0-9_])|={2,}[xo](?![A-Za-z0-9_])` +
    String.raw`|~{3,})`,
);
/** Links with their label inline: `-- text -->`, `== text ==>`, `-. text .->`. */
const TEXT_ARROW_RES = [
  /^(<)?--\s+(.+?)\s+(-{2,}>|-{3,})/,
  /^(<)?==\s+(.+?)\s+(={2,}>|={3,})/,
  /^(<)?-\.\s*(.+?)\s*(\.->?)/,
];

/** A node statement or link chain (`A & B --> C -->|x| D`); returns nodes and links or an error. */
function parseChain(s) {
  let i = 0;
  const skipSpace = () => {
    while (i < s.length && /\s/.test(s[i])) i++;
  };
  const parseGroup = () => {
    const ids = [];
    for (;;) {
      skipSpace();
      const m = FLOW_ID_RE.exec(s.slice(i));
      if (!m) return { error: `expected a node ID at "${s.slice(i, i + 24)}"` };
      const node = { id: m[0], label: null, classes: [] };
      i += m[0].length;
      const shape = parseShape(s, i);
      if (shape?.error) return { error: `node ${node.id}: ${shape.error}` };
      if (shape) {
        node.label = shape.label;
        i = shape.end;
      }
      for (let cm = /^:::([A-Za-z0-9_-]+)/.exec(s.slice(i)); cm; cm = /^:::([A-Za-z0-9_-]+)/.exec(s.slice(i))) {
        node.classes.push(cm[1]);
        i += cm[0].length;
      }
      ids.push(node);
      skipSpace();
      if (s[i] !== '&') return { ids };
      i++;
    }
  };
  const first = parseGroup();
  if (first.error) return first;
  const nodes = [...first.ids];
  const links = [];
  let prev = first.ids;
  for (;;) {
    skipSpace();
    if (i >= s.length) return { nodes, links };
    const rest = s.slice(i);
    let label = null;
    // Arrowheads as Mermaid draws them: at `to` for a `>` end, at `from` for a `<` start with that end.
    let arrowAtTo = false;
    let arrowAtFrom = false;
    let invisible = false;
    const plain = ARROW_RE.exec(rest);
    if (plain) {
      i += plain[0].length;
      arrowAtTo = plain[2].endsWith('>');
      arrowAtFrom = arrowAtTo && plain[1] === '<';
      invisible = plain[2].startsWith('~');
      const piped = /^\s*\|([^|]*)\|/.exec(s.slice(i));
      if (piped) {
        label = piped[1];
        i += piped[0].length;
      }
    } else {
      const text = TEXT_ARROW_RES.map((re) => re.exec(rest)).find(Boolean);
      if (!text) return { error: `unexpected text "${rest.slice(0, 24)}"` };
      i += text[0].length;
      arrowAtTo = text[3].endsWith('>');
      arrowAtFrom = arrowAtTo && text[1] !== undefined;
      label = text[2];
    }
    const next = parseGroup();
    if (next.error) return next;
    nodes.push(...next.ids);
    for (const a of prev) {
      for (const b of next.ids) {
        const edgeLabel = labelText(label, 'flowchart');
        links.push({ from: a.id, to: b.id, label: edgeLabel, arrowAtTo, arrowAtFrom, invisible });
      }
    }
    prev = next.ids;
  }
}

function parseFlowchart(rel, statements, violations) {
  const fc = {
    kind: 'flowchart',
    nodes: new Map(),
    order: [],
    edges: [],
    subgraphs: new Map(),
    // Every subgraph block in file order, a repeated ID included; `subgraphs` keeps the first.
    subgraphOccurrences: [],
    classDefs: new Map(),
    styles: new Map(),
    statements: [],
  };
  const stack = [];
  const touch = (n, line) => {
    let node = fc.nodes.get(n.id);
    if (!node) {
      node = { id: n.id, line, label: null, labelLine: line, classes: [], stack: [] };
      fc.nodes.set(n.id, node);
      fc.order.push(n.id);
    }
    // Mermaid displays the last explicit label a node is given.
    if (n.label !== null) {
      node.label = labelText(n.label, 'flowchart');
      node.labelLine = line;
    }
    // A node belongs to the first subgraph block that mentions it, as Mermaid places it.
    if (node.stack.length === 0 && stack.length > 0) node.stack = [...stack];
    node.classes.push(...n.classes);
  };
  for (const { text, line } of statements) {
    let m;
    if ((m = /^subgraph(?:\s+(.*))?$/.exec(text))) {
      const rest = (m[1] ?? '').trim();
      const sm = /^([A-Za-z0-9_]+)\s*\[\s*(?:"([^"]*)"|([^\]]*))\s*\]$/.exec(rest);
      const qm = /^"([^"]*)"$/.exec(rest);
      const id = sm ? sm[1] : qm ? qm[1] : rest;
      const title = sm ? (sm[2] ?? sm[3]) : qm ? qm[1] : rest;
      if (id === '') violations.push({ path: rel, line, message: 'subgraph has no ID or title' });
      const occurrence = { id, title: labelText(title, 'flowchart'), line, ancestors: [...stack] };
      fc.subgraphOccurrences.push(occurrence);
      if (!fc.subgraphs.has(id)) fc.subgraphs.set(id, occurrence);
      stack.push(id);
      fc.statements.push({ kind: 'subgraph', line });
    } else if (text === 'end') {
      if (stack.length === 0) violations.push({ path: rel, line, message: '"end" closes no subgraph' });
      else stack.pop();
      fc.statements.push({ kind: 'end', line });
    } else if (/^direction\s+\w+$/.test(text)) {
      fc.statements.push({ kind: 'direction', line });
    } else if ((m = /^classDef\s+(\S+)\s+(.+)$/.exec(text))) {
      // Mermaid appends a redefined classDef's styles to the class, so a later key wins and earlier keys stay.
      for (const name of m[1].split(',')) {
        const props = fc.classDefs.get(name.trim()) ?? new Map();
        for (const [k, v] of parseProps(m[2])) props.set(k, v);
        fc.classDefs.set(name.trim(), props);
      }
      fc.statements.push({ kind: 'classDef', line });
    } else if ((m = /^class\s+(.+?)\s+([A-Za-z0-9_-]+)$/.exec(text))) {
      // Mermaid applies a class statement only to nodes that already exist.
      for (const id of m[1].split(/\s*,\s*/)) fc.nodes.get(id)?.classes.push(m[2]);
      fc.statements.push({ kind: 'class', line });
    } else if ((m = /^style\s+([A-Za-z0-9_]+)\s+(.+)$/.exec(text))) {
      const props = fc.styles.get(m[1]) ?? new Map();
      for (const [k, v] of parseProps(m[2])) props.set(k, v);
      fc.styles.set(m[1], props);
      fc.statements.push({ kind: 'style', line });
    } else if (/^(linkStyle|click)\b/.test(text)) {
      fc.statements.push({ kind: text.startsWith('click') ? 'click' : 'linkStyle', line });
    } else if (/^(accTitle|accDescr)\b/.test(text)) {
      fc.statements.push({ kind: 'acc', line });
    } else {
      const chain = parseChain(text);
      if (chain.error) {
        violations.push({ path: rel, line, message: `cannot parse flowchart statement: ${chain.error}` });
        continue;
      }
      chain.nodes.forEach((n) => touch(n, line));
      chain.links.forEach((l) => fc.edges.push({ ...l, line }));
      fc.statements.push({ kind: 'chain', line, ids: chain.nodes.map((n) => n.id) });
    }
  }
  for (const id of stack) {
    const line = fc.subgraphs.get(id)?.line;
    violations.push({ path: rel, line, message: `subgraph ${id} is never closed with "end"` });
  }
  // An ID used as a link endpoint that names a subgraph is that subgraph, not a node.
  for (const id of fc.subgraphs.keys()) {
    if (fc.nodes.delete(id)) fc.order = fc.order.filter((o) => o !== id);
  }
  return fc;
}

const SEQ_ID = String.raw`[^\s:;,+<>()\-]+`;
const SEQ_MESSAGE_RE = new RegExp(
  String.raw`^(${SEQ_ID})\s*(<<-->>|<<->>|-->>|->>|--x|-x|--\)|-\)|-->|->)\s*([+-]?)\s*(${SEQ_ID})\s*(?::\s*(.*))?$`,
);
const SEQ_BLOCK_RE = /^(opt|alt|loop|par_over|par|critical|break|rect)\b/;
const SEQ_IGNORED_RE =
  /^(else|and|option|autonumber|activate|deactivate|destroy|title|accTitle|accDescr|links?|properties|details)\b/;

function parseSequence(rel, statements, violations) {
  const sq = { kind: 'sequence', participants: new Map(), order: [], messages: [], boxes: [], notes: [], last: null };
  const stack = [];
  // Mermaid keeps one actor per ID: a later alias replaces its label, and a redeclaration
  // inside a box places an unboxed actor there but cannot move it to a different box.
  const addParticipant = (id, label, line, box) => {
    let p = sq.participants.get(id);
    if (!p) {
      p = { id, label: labelText(id, 'sequence'), labelLine: line, line, box: null };
      sq.participants.set(id, p);
      sq.order.push(id);
    }
    if (box !== null && p.box !== null && p.box !== box) {
      const boxes = `box "${sq.boxes[p.box].name}" and box "${sq.boxes[box].name}"`;
      violations.push({ path: rel, line, message: `participant ${id} is declared in ${boxes}; Mermaid allows one` });
      return;
    }
    if (label != null) {
      p.label = labelText(label, 'sequence');
      p.labelLine = line;
    }
    if (box !== null && p.box === null) {
      p.box = box;
      const members = sq.boxes[box].participants;
      members.push(id);
      members.sort((a, b) => sq.order.indexOf(a) - sq.order.indexOf(b));
    }
  };
  const openBox = () => {
    const top = stack.findLast((s) => s.kind === 'box');
    return top ? top.index : null;
  };
  for (const { text, line } of statements) {
    let m;
    let kind = 'other';
    if ((m = /^box(?:\s+(.*))?$/.exec(text))) {
      if (openBox() !== null) violations.push({ path: rel, line, message: 'box groups cannot be nested' });
      const rest = (m[1] ?? '').trim();
      const cm = /^(rgba?\([^)]*\)|#[0-9A-Fa-f]{3,8}|[A-Za-z]+)\s*(.*)$/.exec(rest);
      const name = normaliseSpace(decodeEntityMarkers(cm ? cm[2] : rest));
      sq.boxes.push({ colour: cm ? cm[1] : '', name, line, participants: [] });
      stack.push({ kind: 'box', index: sq.boxes.length - 1 });
      kind = 'box';
    } else if (text === 'end') {
      if (stack.length === 0) violations.push({ path: rel, line, message: '"end" closes no box or block' });
      else stack.pop();
      kind = 'end';
    } else if ((m = /^(?:create\s+)?(?:participant|actor)\s+(\S+?)(?:\s+as\s+(.+))?$/.exec(text))) {
      addParticipant(m[1], m[2], line, openBox());
      kind = 'participant';
    } else if ((m = /^note\s+(over|left of|right of)\s+(.+?)\s*:\s*(.*)$/i.exec(text))) {
      const over = m[2].split(',').map((p) => p.trim()).filter((p) => p !== '');
      over.forEach((p) => addParticipant(p, null, line, null));
      sq.notes.push({ over, text: labelText(m[3], 'sequence'), line });
      kind = 'note';
    } else if (SEQ_BLOCK_RE.test(text)) {
      stack.push({ kind: 'block', line });
      kind = 'block';
    } else if (SEQ_IGNORED_RE.test(text)) {
      kind = 'control';
    } else if ((m = SEQ_MESSAGE_RE.exec(text))) {
      addParticipant(m[1], null, line, null);
      addParticipant(m[4], null, line, null);
      sq.messages.push({ from: m[1], to: m[4], arrow: m[2], text: labelText(m[5] ?? '', 'sequence'), line });
      kind = 'message';
    } else {
      violations.push({ path: rel, line, message: `cannot parse sequence statement "${text.slice(0, 40)}"` });
      continue;
    }
    sq.last = { kind, line, note: kind === 'note' ? sq.notes[sq.notes.length - 1] : null };
  }
  for (const open of stack) {
    const box = open.kind === 'box' ? sq.boxes[open.index] : null;
    const what = box ? `box "${box.name}"` : 'a block';
    violations.push({ path: rel, line: box ? box.line : open.line, message: `${what} is never closed with "end"` });
  }
  return sq;
}

/**
 * Parses one .mmd file, with its header line and, for a flowchart, its effective direction;
 * returns null when its type cannot be determined.
 */
function parseDiagram(rel, violations) {
  const { title, titleType, body, frontmatterError } = splitSource(readText(rel), violations);
  if (frontmatterError) violations.push({ path: rel, ...frontmatterError });
  if (titleType !== undefined && titleType !== null && titleType !== 'string') {
    violations.push({ path: rel, line: 1, message: `frontmatter title: must be a string; found ${titleType}` });
  } else if (title !== undefined && (title === null || title.trim() === '')) {
    violations.push({ path: rel, line: 1, message: 'frontmatter title: is missing, null or blank' });
  }
  if (body.length === 0) {
    violations.push({ path: rel, line: 1, message: 'diagram has no statements' });
    return null;
  }
  const header = body[0];
  const flow = /^(flowchart|graph)\b/.test(header.text);
  if (!flow && !/^sequenceDiagram\b/.test(header.text)) {
    const type = header.text.split(/\s/)[0];
    violations.push({ path: rel, line: header.line, message: `unsupported diagram type "${type}"` });
    return null;
  }
  if (flow) {
    const statements = body.flatMap(({ text, line }) =>
      splitFlowchartStatements(encodeEntities(text)).map((t) => ({ text: t, line })),
    );
    const head = statements.shift();
    const headMatch = /^(flowchart|graph)(?:\s+(TB|TD|BT|LR|RL))?$/.exec(head.text);
    if (!headMatch) {
      violations.push({ path: rel, line: head.line, message: `malformed flowchart header "${head.text}"` });
    }
    // Mermaid lays a flowchart out top to bottom when its header names no direction and reads
    // TD as TB; a top-level direction statement does not change it. Null for a malformed header.
    const direction = headMatch ? (headMatch[2] ?? 'TB').replace('TD', 'TB') : null;
    return { rel, title, ...parseFlowchart(rel, statements, violations), headerLine: head.line, direction };
  }
  const statements = body.flatMap(({ text, line }) =>
    encodeEntities(text).split(';').map((t) => t.trim()).filter((t) => t !== '').map((t) => ({ text: t, line })),
  );
  statements.shift();
  return { rel, title, ...parseSequence(rel, statements, violations), headerLine: header.line, direction: null };
}

// ---------------------------------------------------------------------------------------
// Per-diagram checks

/** Statement kinds that may follow the flowchart Description node. */
const DESCRIPTION_TRAILERS = new Set(['classDef', 'class', 'style', 'linkStyle', 'click', 'end']);

const componentCategory = (catalogue, id) => {
  const entry = catalogue.get(id);
  return entry && !entry.boundary ? entry.category : null;
};

function checkDescriptionText(rel, line, text, violations) {
  for (const message of sentenceRuleMessages('description', text)) violations.push({ path: rel, line, message });
}

function checkLabel(rel, line, owner, label, violations) {
  const words = labelWords(label);
  if (words < 1 || words > 5) {
    const message = `label of ${owner} has ${words} words; expected 1–5 after any P<n>/S<n> prefix`;
    violations.push({ path: rel, line, message });
  }
}

/**
 * A flowchart node's fill as the pinned renderer paints it. Mermaid compiles the styles of
 * the `default` class, the `node` class and then the node's classes in assignment order,
 * follows them with its `style` statements, and writes the result inline on the shape with
 * `!important`, where a later value of a key wins. The inline styles override the class rules
 * in the stylesheet, so stylesheet order and classDef order never decide the colour.
 * `classFill` is the fill from the classes alone; `effective` is the painted fill.
 */
function fillOf(fc, node) {
  let classFill = null;
  for (const c of ['default', 'node', ...node.classes]) classFill = fc.classDefs.get(c)?.get('fill') ?? classFill;
  const styled = fc.styles.get(node.id)?.get('fill');
  return { classFill, effective: styled ?? classFill };
}

function checkFlowchart(fc, catalogue, violations) {
  const { rel } = fc;
  const v = (line, message) => violations.push({ path: rel, line, message });
  if (fc.nodes.size > NODE_BUDGET) {
    v(1, `${fc.nodes.size} nodes exceed the ${NODE_BUDGET}-node budget; split the diagram by domain boundary`);
  }
  const linked = new Set();
  for (const e of fc.edges) {
    if (e.invisible) continue;
    linked.add(e.from);
    linked.add(e.to);
  }
  for (const node of fc.nodes.values()) {
    if (isAnnotation(node.id)) {
      if (node.id.startsWith('Legend') && !LEGEND_IDS.has(node.id)) {
        v(node.line, `${node.id} is not one of the five legend entries`);
      }
      continue;
    }
    if (!linked.has(node.id)) v(node.line, `component ${node.id} has no edge`);
    const category = componentCategory(catalogue, node.id);
    if (!category) {
      v(node.line, `node ID ${node.id} is not a component name in the TRD component catalogue`);
      continue;
    }
    checkLabel(rel, node.labelLine, node.id, node.label ?? node.id, violations);
    // The catalogue states that a component's colour class follows its category, so the
    // legend can be read off every component node, as the box rule does for sequences.
    const { rgb } = CATEGORY_BY_NAME.get(category.toLowerCase());
    const { effective } = fillOf(fc, node);
    if (!sameColour(effective, rgb)) {
      const expected = `${fmtHex(rgb)} (${fmtRgb(rgb)})`;
      v(node.line, `${node.id} (${category}) has fill ${effective ?? 'none'}; expected ${expected}`);
    }
  }
  for (const sg of fc.subgraphs.values()) {
    if (sg.id === 'Legend') continue;
    if (!BOUNDARIES.has(sg.id)) v(sg.line, `subgraph ID ${sg.id} is not a boundary name or Legend`);
    else checkLabel(rel, sg.line, `subgraph ${sg.id}`, sg.title, violations);
  }

  const description = fc.nodes.get('Description');
  if (!description) {
    v(1, 'Description node is missing');
  } else {
    const at = fc.statements.findIndex((s) => s.kind === 'chain' && s.ids.includes('Description'));
    const after = fc.statements.slice(at + 1).find((s) => !DESCRIPTION_TRAILERS.has(s.kind));
    if (after) v(after.line, `a ${after.kind} statement follows the Description node, which must be the last element`);
    checkDescriptionText(rel, description.labelLine, description.label ?? '', violations);
  }

  const legend = fc.subgraphs.get('Legend');
  if (!legend) v(1, 'Legend subgraph is missing');
  for (const cat of CATEGORIES) {
    const node = fc.nodes.get(cat.legend);
    if (!node) {
      v(legend?.line ?? 1, `legend entry ${cat.legend} is missing`);
      continue;
    }
    if (legend && !node.stack.includes('Legend')) v(node.line, `${cat.legend} is not inside the Legend subgraph`);
    if (normaliseSpace(node.label ?? '').toLowerCase() !== cat.name.toLowerCase()) {
      v(node.labelLine, `${cat.legend} is labelled "${node.label ?? ''}"; expected "${cat.name}"`);
    }
    const { classFill, effective } = fillOf(fc, node);
    if (!sameColour(classFill, cat.rgb) || !sameColour(effective, cat.rgb)) {
      const expected = `${fmtHex(cat.rgb)} (${fmtRgb(cat.rgb)})`;
      const found = `class fill ${classFill ?? 'none'}, rendered fill ${effective ?? 'none'}`;
      v(node.line, `${cat.legend} must be filled ${expected} by its category classDef; found ${found}`);
    }
  }
}

function checkSequence(sq, catalogue, violations) {
  const { rel } = sq;
  const v = (line, message) => violations.push({ path: rel, line, message });
  const closingNote = sq.last?.kind === 'note' ? sq.last.note : null;
  const count = sq.participants.size + (closingNote ? 1 : 0);
  if (count > NODE_BUDGET) {
    v(1, `${count} participants and closing note exceed the ${NODE_BUDGET}-node budget; split the diagram`);
  }
  const linked = new Set(sq.messages.flatMap((m) => [m.from, m.to]));
  for (const p of sq.participants.values()) {
    if (isAnnotation(p.id)) {
      if (p.id.startsWith('Legend') && !LEGEND_IDS.has(p.id)) {
        v(p.line, `${p.id} is not one of the five legend entries`);
      }
      continue;
    }
    if (!linked.has(p.id)) v(p.line, `participant ${p.id} sends or receives no message`);
    const category = componentCategory(catalogue, p.id);
    if (!category) {
      v(p.line, `participant ID ${p.id} is not a component name in the TRD component catalogue`);
      continue;
    }
    checkLabel(rel, p.labelLine, p.id, p.label, violations);
    const box = p.box === null ? null : sq.boxes[p.box];
    if (!box || box.name.toLowerCase() !== category.toLowerCase()) {
      const at = box ? `in box "${box.name}"` : 'outside every box';
      v(p.line, `participant ${p.id} (${category}) sits ${at}; expected the "${category}" box`);
    }
  }

  if (!closingNote) v(sq.last?.line ?? 1, 'the last statement must be the closing description Note');
  else checkDescriptionText(rel, closingNote.line, closingNote.text, violations);

  const order = CATEGORIES.map((c) => c.name).join(', ');
  if (sq.boxes.length !== CATEGORIES.length) {
    v(1, `${sq.boxes.length} box groups; expected exactly ${CATEGORIES.length} (${order})`);
  }
  sq.boxes.forEach((box, i) => {
    const named = CATEGORY_BY_NAME.get(box.name.toLowerCase());
    if (!named) {
      v(box.line, `box "${box.name}" is not named for a legend category`);
      return;
    }
    if (CATEGORIES[i] && CATEGORIES[i] !== named) {
      v(box.line, `box ${i + 1} is "${box.name}"; expected "${CATEGORIES[i].name}" in the order ${order}`);
    }
    if (!sameColour(box.colour, named.rgb)) {
      const expected = `${fmtRgb(named.rgb)} (${fmtHex(named.rgb)})`;
      v(box.line, `box "${box.name}" has colour ${box.colour || 'none'}; expected ${expected}`);
    }
    if (box.participants[0] !== named.legend) {
      const first = box.participants[0] ?? 'no participant';
      v(box.line, `box "${box.name}" opens with ${first}; expected legend participant ${named.legend}`);
    }
  });
  for (const cat of CATEGORIES) {
    const p = sq.participants.get(cat.legend);
    if (!p) {
      v(1, `legend participant ${cat.legend} is missing`);
      continue;
    }
    if (p.label.toLowerCase() !== cat.name.toLowerCase()) {
      v(p.labelLine, `${cat.legend} is labelled "${p.label}"; expected "${cat.name}"`);
    }
    const box = p.box === null ? null : sq.boxes[p.box];
    if (!box || box.name.toLowerCase() !== cat.name.toLowerCase()) {
      v(p.line, `${cat.legend} is not inside the "${cat.name}" box`);
    }
  }
}

// ---------------------------------------------------------------------------------------
// Security mapping over the security diagram set, split parts merged

const MTLS_CATEGORIES = new Set(['Compute', 'External systems']);
const hasToken = (text, token) => new RegExp(`\\b${token}\\b`).test(text ?? '');

function mergeFlowcharts(parts) {
  // `subgraphs` holds every subgraph occurrence of every part, each with its file, so a split
  // view's repeated boundary keeps each title; `members` unions a boundary's contents across parts.
  const merged = { nodes: new Map(), labels: new Map(), edges: [], subgraphs: [], members: new Map() };
  const addMember = (sg, id) => {
    if (!merged.members.has(sg)) merged.members.set(sg, new Set());
    merged.members.get(sg).add(id);
  };
  for (const fc of parts) {
    for (const n of fc.nodes.values()) {
      if (!merged.nodes.has(n.id)) merged.nodes.set(n.id, { ...n, rel: fc.rel });
      if (!merged.labels.has(n.id)) merged.labels.set(n.id, []);
      merged.labels.get(n.id).push(n.label ?? n.id);
      n.stack.forEach((sg) => addMember(sg, n.id));
    }
    for (const e of fc.edges) if (!e.invisible) merged.edges.push({ ...e, rel: fc.rel });
    for (const sg of fc.subgraphOccurrences) {
      merged.subgraphs.push({ ...sg, rel: fc.rel });
      sg.ancestors.forEach((a) => addMember(a, sg.id));
    }
  }
  return merged;
}

function checkSecurity(parts, catalogue, controls, secretConsumers, violations) {
  const set = mergeFlowcharts(parts);
  const v = (rel, line, message) => violations.push({ path: rel, line, message });
  const categoryOf = (id) => componentCategory(catalogue, id);
  const joins = (e, from, to) =>
    (e.from === from && e.to === to && e.arrowAtTo) || (e.from === to && e.to === from && e.arrowAtFrom);
  const hasEdge = (from, to, token) => set.edges.some((e) => joins(e, from, to) && hasToken(e.label, token));

  const texts = [
    ...parts.map((p) => ({ rel: p.rel, line: 1, what: 'title', text: p.title ?? '' })),
    ...[...set.nodes.values()].flatMap((n) =>
      set.labels.get(n.id).map((text) => ({ rel: n.rel, line: n.labelLine, what: `label of ${n.id}`, text })),
    ),
    ...set.edges.map((e) => ({ rel: e.rel, line: e.line, what: `edge ${e.from} → ${e.to}`, text: e.label })),
    ...set.subgraphs.map((g) => ({
      rel: g.rel,
      line: g.line,
      what: `subgraph ${g.id} title`,
      text: g.title,
    })),
  ];
  const reported = new Set();
  for (const t of texts) {
    for (const m of t.text.matchAll(/\bS\d+\b/g)) {
      if (!controls.has(m[0]) && !reported.has(m[0])) {
        reported.add(m[0]);
        v(t.rel, t.line, `${m[0]} (${t.what}) has no row in the TRD security control table`);
      }
    }
    const p = /\bP\d+\b/.exec(t.text);
    if (p) v(t.rel, t.line, `${t.what} carries physical numbering ${p[0]}; the security view uses S<n> only`);
  }

  // The mTLS form lives only on edges between two Compute or External-systems components:
  // each of them must carry it, and only they can carry a protected component's form.
  const mtlsEdges = set.edges.filter(
    (e) => MTLS_CATEGORIES.has(categoryOf(e.from)) && MTLS_CATEGORIES.has(categoryOf(e.to)),
  );
  const mtlsIds = new Set([...controls].filter(([, c]) => c.kind === KIND.MTLS).map(([id]) => id));
  for (const e of mtlsEdges) {
    const m = /\b(S\d+)\s+mTLS\b/i.exec(e.label);
    if (!m || !mtlsIds.has(m[1])) {
      const edge = `edge ${e.from} → ${e.to} joins two Compute or External-systems components`;
      v(e.rel, e.line, `${edge} but is not labelled "S<n> mTLS" for an mTLS control`);
    }
  }

  for (const [id, control] of controls) {
    if (control.kind === KIND.MTLS) {
      const re = new RegExp(`\\b${id}\\s+mTLS\\b`, 'i');
      for (const c of control.protectedComponents) {
        if (!MTLS_CATEGORIES.has(categoryOf(c))) {
          v(TRD, control.line, `${id} (mTLS) protects ${c}, which is not a Compute or External-systems component`);
        } else if (!mtlsEdges.some((e) => (e.from === c || e.to === c) && re.test(e.label))) {
          const node = set.nodes.get(c);
          const edge = `edge between ${c} and another Compute or External-systems component`;
          const message = `${id} (mTLS) protects ${c}, but no ${edge} is labelled "${id} mTLS"`;
          v(node?.rel ?? TRD, node?.line ?? control.line, message);
        }
      }
    } else if (control.kind === KIND.SECRET) {
      const consumers = new Set([...control.protectedComponents, ...secretConsumers.keys()]);
      consumers.delete('AzureKeyVault');
      for (const c of consumers) {
        if (!hasEdge('AzureKeyVault', c, id)) {
          // At the consumer's node, else at the TRD row that names it.
          const node = set.nodes.get(c);
          const row = control.protectedComponents.includes(c) ? control.line : secretConsumers.get(c)[0].line;
          v(node?.rel ?? TRD, node?.line ?? row, `${id} (Secret) needs an edge AzureKeyVault → ${c} labelled ${id}`);
        }
      }
    } else if (control.kind === KIND.ENCRYPTION) {
      for (const store of new Set(['AzurePostgres', 'AzureManagedRedis', ...control.protectedComponents])) {
        const labels = set.labels.get(store);
        const node = set.nodes.get(store);
        if (!labels) {
          v(TRD, control.line, `${id} (Encryption at rest) protects ${store}, which is not in the security diagram`);
        } else if (!labels.some((l) => hasToken(l, id))) {
          v(node.rel, node.labelLine ?? node.line, `label of ${store} does not carry ${id} (Encryption at rest)`);
        }
      }
    } else if (control.kind === KIND.AUTH) {
      // The AuthN/authZ form is the AccountAccessGuard → AuthService edge, so it reaches only those two.
      const ends = ['AccountAccessGuard', 'AuthService'];
      for (const c of control.protectedComponents) {
        if (!ends.includes(c)) {
          const form = 'its form, the edge AccountAccessGuard → AuthService,';
          v(TRD, control.line, `${id} (AuthN/authZ) protects ${c}, but ${form} does not reach ${c}`);
        }
      }
      if (!hasEdge('AccountAccessGuard', 'AuthService', id)) {
        const node = set.nodes.get('AccountAccessGuard') ?? set.nodes.get('AuthService');
        const message = `${id} (AuthN/authZ) needs an edge AccountAccessGuard → AuthService labelled ${id}`;
        v(node?.rel ?? TRD, node?.line ?? control.line, message);
      }
    } else if (control.kind === KIND.THREAT) {
      const titled = new RegExp(`^${id}\\b`);
      // Each boundary ID titled with the control, located at its first such occurrence; its
      // contents are the union over every part that draws it.
      const boundaries = new Map();
      for (const g of set.subgraphs) if (titled.test(g.title) && !boundaries.has(g.id)) boundaries.set(g.id, g);
      if (boundaries.size === 0) {
        v(TRD, control.line, `${id} (Threat boundary) has no subgraph whose title starts with ${id}`);
        continue;
      }
      for (const boundary of boundaries.values()) {
        for (const c of control.protectedComponents) {
          if (c !== boundary.id && !set.members.get(boundary.id)?.has(c)) {
            v(boundary.rel, boundary.line, `${id} (Threat boundary) subgraph ${boundary.id} does not contain ${c}`);
          }
        }
      }
    }
  }

  const guard = set.nodes.get('AccountAccessGuard');
  const matrixAt = guard ? { rel: guard.rel, line: guard.line } : { rel: parts[0].rel, line: 1 };
  const matrixRows = ACCESS_MATRIX_ROWS.join(', ');
  for (const row of ACCESS_MATRIX_ROWS) {
    if (!set.nodes.has(row)) {
      v(matrixAt.rel, matrixAt.line, `access-control row ${row} is missing; the matrix has the rows ${matrixRows}`);
    }
  }
  for (const n of set.nodes.values()) {
    if (!n.id.startsWith('Acm')) continue;
    if (!ACCESS_MATRIX_ROWS.includes(n.id)) {
      v(n.rel, n.line, `${n.id} is not one of the three access-control rows ${matrixRows}`);
    }
    const linked = set.edges.some(
      (e) => (e.from === n.id && e.to === 'AccountAccessGuard') || (e.to === n.id && e.from === 'AccountAccessGuard'),
    );
    if (!linked) v(n.rel, n.line, `access-control row ${n.id} has no edge to AccountAccessGuard`);
  }
}

// ---------------------------------------------------------------------------------------
// Required diagrams: fixed paths, kinds and flowchart directions, and the security view's form

const kindName = (kind, direction) =>
  kind === 'sequence' ? 'sequenceDiagram' : direction ? `flowchart ${direction}` : 'flowchart';

/**
 * Reports every required diagram that is missing, of the wrong kind or laid out in the wrong
 * direction, and a security view that is neither security-architecture.mmd alone nor exactly
 * its two split parts. A file parseDiagram could not parse is skipped; it is reported already.
 */
function checkRequiredDiagrams(files, diagrams, violations) {
  const present = new Set(files);
  const v = (rel, line, message) => violations.push({ path: rel, line, message });
  const checkForm = ({ rel, kind, direction }) => {
    const d = diagrams.get(rel);
    if (!d) return;
    if (d.kind !== kind) {
      v(rel, d.headerLine, `must be a ${kindName(kind, direction)}; found ${kindName(d.kind, null)}`);
    } else if (direction !== null && d.direction !== null && d.direction !== direction) {
      v(rel, d.headerLine, `flowchart direction is ${d.direction}; expected ${direction}`);
    }
  };
  for (const required of REQUIRED_DIAGRAMS) {
    if (present.has(required.rel)) checkForm(required);
    else v(required.rel, 1, 'required diagram is missing');
  }

  const base = (rel) => path.posix.basename(rel);
  const [network, access] = SECURITY_SPLIT.map((part) => base(part.rel));
  const forms = `${base(SECURITY_VIEW.rel)} alone, or exactly its split ${network} and ${access}`;
  const single = present.has(SECURITY_VIEW.rel);
  const split = SECURITY_SPLIT.filter((part) => present.has(part.rel));
  if (!single && split.length === 0) {
    v(SECURITY_VIEW.rel, 1, `required diagram is missing (or its split ${network} and ${access})`);
  } else if (single && split.length > 0) {
    const parts = split.map((part) => base(part.rel)).join(' and ');
    v(SECURITY_VIEW.rel, 1, `the security view is present both whole and split (${parts}); keep ${forms}`);
  } else if (!single && split.length === 1) {
    const missing = SECURITY_SPLIT.find((part) => !present.has(part.rel));
    v(missing.rel, 1, `required diagram is missing; the split security view needs both ${network} and ${access}`);
  }
  const securityForms = new Map([SECURITY_VIEW, ...SECURITY_SPLIT].map((form) => [form.rel, form]));
  for (const rel of files.filter((f) => SECURITY_RE.test(base(f)))) {
    if (securityForms.has(rel)) {
      checkForm(securityForms.get(rel));
    } else {
      v(rel, 1, `not a security view file; the security view is ${forms}`);
      checkForm({ rel, kind: 'flowchart', direction: null });
    }
  }
}

// ---------------------------------------------------------------------------------------
// External systems in every architecture view, and the catalogue Diagrams column

function checkExternalSystems(diagrams, securityParts, violations) {
  // A missing logical or physical diagram is reported by checkRequiredDiagrams.
  for (const rel of [LOGICAL, PHYSICAL]) {
    const d = diagrams.get(rel);
    if (!d) continue;
    for (const ext of EXTERNAL_SYSTEMS) {
      const shown = d.kind === 'flowchart' ? d.nodes.has(ext) : d.participants.has(ext);
      if (!shown) violations.push({ path: rel, line: 1, message: `external system ${ext} is not shown` });
    }
  }
  if (securityParts.length === 0) return;
  // A split view is reported at its first part, the message naming every part searched.
  const where = securityParts[0].rel;
  const parts = securityParts.map((p) => path.posix.basename(p.rel)).join(', ');
  const scope = securityParts.length === 1 ? '' : ` in any part of the security view (${parts})`;
  for (const ext of EXTERNAL_SYSTEMS) {
    if (!securityParts.some((p) => p.nodes.has(ext))) {
      violations.push({ path: where, line: 1, message: `external system ${ext} is not shown${scope}` });
    }
  }
}

/**
 * The catalogue's Diagrams column claims every diagram an ID appears in; it is compared
 * with the parsed diagrams so the shared name list cannot drift from them.
 */
function checkCatalogueDiagrams(files, catalogue, diagrams, violations) {
  const stemOf = (rel) => path.posix.basename(rel, '.mmd');
  const allStems = new Set(files.map(stemOf));
  const appears = new Map();
  for (const d of diagrams.values()) {
    const ids = d.kind === 'flowchart' ? [...d.nodes.keys(), ...d.subgraphs.keys()] : [...d.participants.keys()];
    for (const id of ids) {
      if (!appears.has(id)) appears.set(id, new Set());
      appears.get(id).add(stemOf(d.rel));
    }
  }
  const parsedStems = new Set([...diagrams.keys()].map(stemOf));
  const bad = (line, message) => violations.push({ path: TRD, line, message });
  for (const [name, entry] of catalogue) {
    const listed = new Set(entry.diagrams);
    const actual = appears.get(name) ?? new Set();
    for (const stem of listed) {
      if (!allStems.has(stem)) {
        bad(entry.line, `catalogue row ${name} lists diagram ${stem}, which does not exist in ${DIAGRAM_DIR}`);
      } else if (parsedStems.has(stem) && !actual.has(stem)) {
        bad(entry.line, `catalogue row ${name} lists diagram ${stem}, where ${name} does not appear`);
      }
    }
    for (const stem of actual) {
      if (!listed.has(stem)) bad(entry.line, `${name} appears in ${stem}, which its catalogue Diagrams column omits`);
    }
  }
}

// ---------------------------------------------------------------------------------------

async function main() {
  const violations = [];
  const files = listFiles(DIAGRAM_DIR, { exts: ['.mmd'] });
  // An empty set renders nothing; checkRequiredDiagrams reports every diagram it lacks.
  if (files.length > 0) await renderAll(files, violations);

  let catalogue = new Map();
  let controls = new Map();
  let secretConsumers = new Map();
  if (!exists(TRD)) {
    violations.push({ path: TRD, line: 1, message: 'Technical Requirements Document is missing' });
  } else {
    const trdText = readText(TRD);
    catalogue = loadCatalogue(trdText, violations);
    controls = loadControls(trdText, catalogue, violations);
    secretConsumers = loadSecretConsumers(trdText, catalogue, violations);
  }

  const diagrams = new Map();
  for (const rel of files) {
    const d = parseDiagram(rel, violations);
    if (d) diagrams.set(rel, d);
  }
  for (const d of diagrams.values()) {
    if (d.kind === 'flowchart') checkFlowchart(d, catalogue, violations);
    else checkSequence(d, catalogue, violations);
  }

  checkRequiredDiagrams(files, diagrams, violations);
  // Every security-architecture*.mmd that parsed as a flowchart is a part of the security
  // view; checkRequiredDiagrams reports a missing view, a wrong kind and a stray name.
  const securityParts = files
    .filter((rel) => SECURITY_RE.test(path.posix.basename(rel)))
    .map((rel) => diagrams.get(rel))
    .filter((d) => d?.kind === 'flowchart');
  if (securityParts.length > 0) checkSecurity(securityParts, catalogue, controls, secretConsumers, violations);
  checkExternalSystems(diagrams, securityParts, violations);
  if (catalogue.size > 0) checkCatalogueDiagrams(files, catalogue, diagrams, violations);

  report(CHECK, violations);
}

const USAGE = [
  'Usage: node tests/validation/check-diagrams.mjs [-h | --help]',
  '',
  'Renders every docs/architecture/*.mmd with the pinned mmdc and checks it against the',
  'component catalogue, security control table and environment-variable table of',
  'docs/technical-requirements.md.',
  '',
  "Takes no arguments. Run it from the repository root, or from any directory by the script's absolute path.",
  'Exit status: 0 no violation, 1 one or more violations, 2 usage error or internal error.',
  '',
  'Renders go to a temporary folder under TMPDIR (the OS temp directory by default) that is',
  'removed afterwards. Keep TMPDIR short: Chrome aborts with "Socket path too long" on a long one.',
  'Prerequisites, as run-all.sh installs them: `npm ci` in tests/validation, then',
  '`npx --no-install puppeteer browsers install chrome-headless-shell` there.',
].join('\n');

const args = process.argv.slice(2);
const unexpected = args.filter((arg) => arg !== '-h' && arg !== '--help');
if (unexpected.length > 0) {
  const shown = unexpected.map((arg) => (arg === '' || /\s/.test(arg) ? JSON.stringify(arg) : arg));
  console.error(`${CHECK}: unexpected argument(s): ${shown.join(' ')}`);
  console.error(USAGE);
  process.exitCode = 2;
} else if (args.length > 0) {
  console.log(USAGE);
} else {
  try {
    await main();
  } catch (err) {
    console.error(`${CHECK}: internal error: ${err?.stack ?? err}`);
    process.exitCode = 2;
  }
}

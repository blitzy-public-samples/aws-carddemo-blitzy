// SPDX-License-Identifier: Apache-2.0
/**
 * check-diagrams — renders every Mermaid file under docs/architecture/ with the pinned mmdc
 * and checks diagram integrity against the component catalogue, the security control table
 * and the environment-variable table of docs/technical-requirements.md:
 *
 * - every file renders to a non-empty SVG;
 * - at most 25 nodes or participants per file, annotations (Legend*, Acm*, Description)
 *   counted; no component without an edge or message;
 * - every component ID is a catalogue name, every subgraph ID a boundary name or Legend,
 *   and every component label runs 1–5 words after its P<n>/S<n> prefix;
 * - frontmatter title, a closing 1–2 sentence description and all five legend entries in
 *   their category colours (flowchart Legend subgraph; sequence box groups);
 * - the security view maps every S<n> control to its components in the diagram form of
 *   its kind, and carries no physical P<n> numbering;
 * - every external system appears in the logical, physical and security views;
 * - the catalogue's Diagrams column names exactly the diagrams each ID appears in, and
 *   every flowchart component carries the fill of its catalogue category.
 *
 * Run from anywhere as `node tests/validation/check-diagrams.mjs`; exit 0 with no
 * violation, 1 with violations, 2 on an internal error.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import {
  repoRoot,
  readText,
  exists,
  listFiles,
  sectionByHeading,
  parseTables,
  toolBin,
  report,
} from './lib/markdown.mjs';

const CHECK = 'check-diagrams';
const DIAGRAM_DIR = 'docs/architecture';
const TRD = 'docs/technical-requirements.md';
const PUPPETEER_CONFIG = 'tests/validation/puppeteer-config.json';
const RENDER_TIMEOUT_MS = 180000;
const NODE_BUDGET = 25;
const LOGICAL = `${DIAGRAM_DIR}/logical-architecture.mmd`;
const PHYSICAL = `${DIAGRAM_DIR}/physical-deployment.mmd`;
const SECURITY_RE = /^security-architecture.*\.mmd$/;

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
const KIND = Object.freeze({
  MTLS: 'mTLS',
  SECRET: 'Secret',
  ENCRYPTION: 'Encryption at rest',
  AUTH: 'AuthN/authZ',
  THREAT: 'Threat boundary',
});

const isAnnotation = (id) => id.startsWith('Legend') || id.startsWith('Acm') || id === 'Description';
const normaliseSpace = (s) => String(s ?? '').replace(/\s+/g, ' ').trim();
const fmtRgb = (rgb) => `rgb(${rgb.join(',')})`;
const fmtHex = (rgb) => `#${rgb.map((v) => v.toString(16).padStart(2, '0')).join('')}`;

/** Table cell as plain text: links reduced to their text, code, emphasis and HTML removed. */
function cellText(cell) {
  return normaliseSpace(
    String(cell ?? '')
      .replace(/\\\|/g, '|')
      .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/<[^>]+>/g, ' ')
      .replace(/`/g, '')
      .replace(/\*\*|__/g, ''),
  );
}

/** Comma-, semicolon- or "and"-separated identifiers of a table cell. */
function cellList(cell) {
  return cellText(cell)
    .split(/\s*(?:,|;|\band\b)\s*/)
    .map((s) => s.trim())
    .filter((s) => s !== '');
}

/** Mermaid label text: line breaks and other HTML tags become spaces, quotes are dropped. */
function labelText(raw) {
  return normaliseSpace(
    String(raw ?? '')
      .replace(/<br\s*\/?>/gi, ' ')
      .replace(/<[^>]+>/g, ' ')
      .replace(/"/g, ''),
  );
}

/** Sentences of a text: runs ending at `.`, `!` or `?` followed by whitespace or the end. */
function sentenceCount(text) {
  return normaliseSpace(text)
    .split(/(?<=[.!?])\s+/)
    .filter((s) => s !== '').length;
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

function renderAll(files, violations) {
  const mmdc = toolBin('mmdc');
  if (!mmdc) {
    violations.push({
      path: 'tests/validation/node_modules/.bin/mmdc',
      message: 'mmdc is not installed (run npm ci in tests/validation); no diagram was rendered',
    });
    return;
  }
  if (!exists(PUPPETEER_CONFIG)) {
    violations.push({ path: PUPPETEER_CONFIG, message: 'puppeteer configuration is missing; no diagram was rendered' });
    return;
  }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'check-diagrams-'));
  try {
    for (const rel of files) {
      const svg = path.join(tmp, `${path.basename(rel, '.mmd')}.svg`);
      try {
        execFileSync(mmdc, ['-p', PUPPETEER_CONFIG, '-i', rel, '-o', svg], {
          cwd: repoRoot,
          // Chrome leaves scratch directories in TMPDIR; pointing it at this run's
          // temporary folder removes them with that folder.
          env: { ...process.env, TMPDIR: tmp },
          stdio: 'pipe',
          timeout: RENDER_TIMEOUT_MS,
          maxBuffer: 16 * 1024 * 1024,
        });
      } catch (err) {
        const why =
          err.code === 'ETIMEDOUT' ? `timed out after ${RENDER_TIMEOUT_MS / 1000} s`
          : err.status != null ? `exit status ${err.status}`
          : err.signal ? `killed by ${err.signal}`
          : String(err.message ?? err);
        violations.push({ path: rel, message: `mmdc render failed (${why}): ${firstLines(err.stderr)}` });
        continue;
      }
      let size = 0;
      try {
        size = fs.statSync(svg).size;
      } catch (err) {
        if (err.code !== 'ENOENT') throw err;
      }
      if (size === 0) violations.push({ path: rel, message: 'mmdc exited 0 but wrote no SVG output' });
    }
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------------------
// TRD inputs: component catalogue, security control table, environment variables

/** The first table of a TRD section whose header holds every wanted column, with indexes. */
function sectionTable(trdText, heading, wanted, violations) {
  const section = sectionByHeading(trdText, heading);
  if (!section) {
    violations.push({ path: TRD, message: `section "${heading}" is missing` });
    return null;
  }
  for (const table of parseTables(section.text)) {
    const header = table.header.map((h) => cellText(h).toLowerCase());
    const cols = wanted.map((w) => header.indexOf(w));
    if (cols.every((c) => c >= 0)) {
      const col = Object.fromEntries(wanted.map((w, i) => [w, cols[i]]));
      const rows = table.rows.map((cells, i) => ({ cells, line: section.startLine + table.rowLines[i] - 1 }));
      return { col, rows };
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
    if (!catalogue.has(name)) bad(undefined, `component catalogue has no row for ${name}`);
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
    const keySentences = sentenceCount(cellText(cells[table.col.key]));
    if (keySentences < 1 || keySentences > 2) {
      bad(line, `security control ${id} key has ${keySentences} sentences; expected 1–2`);
    }
    controls.set(id, { kind, protectedComponents: protectedComponents.filter((c) => catalogue.has(c)), line });
  }
  // The security view must show every control kind, so each needs at least one row.
  const kinds = new Set([...controls.values()].map((c) => c.kind));
  for (const kind of Object.values(KIND)) {
    if (!kinds.has(kind)) bad(undefined, `security control table has no ${kind} control`);
  }
  return controls;
}

/** Components the environment-variable table lists as reading a deployed secret. */
function loadSecretConsumers(trdText, catalogue, violations) {
  const consumers = new Set();
  const bad = (line, message) => violations.push({ path: TRD, line, message });
  const table = sectionTable(trdText, 'Environment variables', ['variable', 'read by'], violations);
  if (!table) return consumers;
  for (const variable of SECRET_VARIABLES) {
    const row = table.rows.find(({ cells }) => cellText(cells[table.col.variable]) === variable);
    if (!row) {
      bad(undefined, `environment-variable table has no row for ${variable}`);
      continue;
    }
    const readers = cellList(row.cells[table.col['read by']]);
    if (readers.length === 0) bad(row.line, `${variable} names no reader`);
    for (const reader of readers) {
      if (catalogue.has(reader) && !catalogue.get(reader).boundary) consumers.add(reader);
      else bad(row.line, `${variable} is read by "${reader}", which is not a catalogue component`);
    }
  }
  return consumers;
}

// ---------------------------------------------------------------------------------------
// Mermaid parsers

/**
 * Splits YAML frontmatter from the diagram body. Body statements keep their file line
 * numbers; blank lines, `%%` comments and `%%{…}%%` directives are dropped.
 */
function splitSource(text) {
  const lines = text.split(/\r?\n/);
  let title = null;
  let start = 0;
  let frontmatterError = null;
  if (lines[0]?.trim() === '---') {
    const close = lines.findIndex((l, i) => i > 0 && l.trim() === '---');
    if (close < 0) {
      frontmatterError = 'frontmatter opened with --- is never closed';
    } else {
      for (const l of lines.slice(1, close)) {
        const m = /^title:\s*(.*?)\s*$/.exec(l);
        if (m) title = m[1].replace(/^(['"])(.*)\1$/, '$2').trim();
      }
      start = close + 1;
    }
  }
  const body = [];
  for (let i = start; i < lines.length; i++) {
    const t = lines[i].trim();
    if (t === '' || t.startsWith('%%')) continue;
    body.push({ text: t, line: i + 1 });
  }
  return { title, body, frontmatterError };
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
  /^(<)?--\s+(.+?)\s+(?:-{2,}>|-{3,})/,
  /^(<)?==\s+(.+?)\s+(?:={2,}>|={3,})/,
  /^(<)?-\.\s*(.+?)\s*\.->?/,
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
    let bidi = false;
    let invisible = false;
    const plain = ARROW_RE.exec(rest);
    if (plain) {
      i += plain[0].length;
      bidi = plain[1] !== undefined;
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
      bidi = text[1] !== undefined;
      label = text[2];
    }
    const next = parseGroup();
    if (next.error) return next;
    nodes.push(...next.ids);
    for (const a of prev) {
      for (const b of next.ids) links.push({ from: a.id, to: b.id, label: labelText(label), bidi, invisible });
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
    classDefs: new Map(),
    styles: new Map(),
    statements: [],
  };
  const stack = [];
  const touch = (n, line) => {
    let node = fc.nodes.get(n.id);
    if (!node) {
      node = { id: n.id, line, label: null, classes: [], stack: [] };
      fc.nodes.set(n.id, node);
      fc.order.push(n.id);
    }
    if (node.label === null && n.label !== null) node.label = labelText(n.label);
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
      if (!fc.subgraphs.has(id)) fc.subgraphs.set(id, { id, title: labelText(title), line, ancestors: [...stack] });
      stack.push(id);
      fc.statements.push({ kind: 'subgraph', line });
    } else if (text === 'end') {
      if (stack.length === 0) violations.push({ path: rel, line, message: '"end" closes no subgraph' });
      else stack.pop();
      fc.statements.push({ kind: 'end', line });
    } else if (/^direction\s+\w+$/.test(text)) {
      fc.statements.push({ kind: 'direction', line });
    } else if ((m = /^classDef\s+(\S+)\s+(.+)$/.exec(text))) {
      const props = parseProps(m[2]);
      for (const name of m[1].split(',')) fc.classDefs.set(name.trim(), props);
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
  const addParticipant = (id, label, line, box) => {
    if (sq.participants.has(id)) return;
    sq.participants.set(id, { id, label: labelText(label ?? id), line, box });
    sq.order.push(id);
    if (box !== null) sq.boxes[box].participants.push(id);
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
      sq.boxes.push({ colour: cm ? cm[1] : '', name: normaliseSpace(cm ? cm[2] : rest), line, participants: [] });
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
      sq.notes.push({ over, text: labelText(m[3]), line });
      kind = 'note';
    } else if (SEQ_BLOCK_RE.test(text)) {
      stack.push({ kind: 'block' });
      kind = 'block';
    } else if (SEQ_IGNORED_RE.test(text)) {
      kind = 'control';
    } else if ((m = SEQ_MESSAGE_RE.exec(text))) {
      addParticipant(m[1], null, line, null);
      addParticipant(m[4], null, line, null);
      sq.messages.push({ from: m[1], to: m[4], arrow: m[2], text: labelText(m[5] ?? ''), line });
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
    violations.push({ path: rel, line: box?.line, message: `${what} is never closed with "end"` });
  }
  return sq;
}

/** Parses one .mmd file; returns null when its type cannot be determined. */
function parseDiagram(rel, violations) {
  const { title, body, frontmatterError } = splitSource(readText(rel));
  if (frontmatterError) violations.push({ path: rel, line: 1, message: frontmatterError });
  if (title === null || title === '') {
    violations.push({ path: rel, line: 1, message: 'frontmatter title: is missing or empty' });
  }
  if (body.length === 0) {
    violations.push({ path: rel, message: 'diagram has no statements' });
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
    const statements = body.flatMap(({ text, line }) => splitFlowchartStatements(text).map((t) => ({ text: t, line })));
    const head = statements.shift();
    if (!/^(flowchart|graph)(?:\s+(TB|TD|BT|LR|RL))?$/.test(head.text)) {
      violations.push({ path: rel, line: head.line, message: `malformed flowchart header "${head.text}"` });
    }
    return { rel, title, ...parseFlowchart(rel, statements, violations) };
  }
  const statements = body.flatMap(({ text, line }) =>
    text.split(';').map((t) => t.trim()).filter((t) => t !== '').map((t) => ({ text: t, line })),
  );
  statements.shift();
  return { rel, title, ...parseSequence(rel, statements, violations) };
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
  const n = sentenceCount(text);
  if (n < 1 || n > 2) violations.push({ path: rel, line, message: `description has ${n} sentences; expected 1–2` });
}

function checkLabel(rel, line, owner, label, violations) {
  const words = labelWords(label);
  if (words < 1 || words > 5) {
    const message = `label of ${owner} has ${words} words; expected 1–5 after any P<n>/S<n> prefix`;
    violations.push({ path: rel, line, message });
  }
}

/** Fills a flowchart node carries: from its classes in assignment order, and its effective fill. */
function fillOf(fc, node) {
  const classes = node.classes.length === 0 && fc.classDefs.has('default') ? ['default'] : node.classes;
  const classFills = classes.map((c) => fc.classDefs.get(c)?.get('fill')).filter((f) => f !== undefined);
  const styled = fc.styles.get(node.id)?.get('fill');
  return { classFills, effective: styled ?? classFills[classFills.length - 1] ?? null };
}

function checkFlowchart(fc, catalogue, violations) {
  const { rel } = fc;
  const v = (line, message) => violations.push({ path: rel, line, message });
  if (fc.nodes.size > NODE_BUDGET) {
    v(undefined, `${fc.nodes.size} nodes exceed the ${NODE_BUDGET}-node budget; split the diagram by domain boundary`);
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
    checkLabel(rel, node.line, node.id, node.label ?? node.id, violations);
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
    v(undefined, 'Description node is missing');
  } else {
    const at = fc.statements.findIndex((s) => s.kind === 'chain' && s.ids.includes('Description'));
    const after = fc.statements.slice(at + 1).find((s) => !DESCRIPTION_TRAILERS.has(s.kind));
    if (after) v(after.line, `a ${after.kind} statement follows the Description node, which must be the last element`);
    checkDescriptionText(rel, description.line, description.label ?? '', violations);
  }

  const legend = fc.subgraphs.get('Legend');
  if (!legend) v(undefined, 'Legend subgraph is missing');
  for (const cat of CATEGORIES) {
    const node = fc.nodes.get(cat.legend);
    if (!node) {
      v(legend?.line, `legend entry ${cat.legend} is missing`);
      continue;
    }
    if (legend && !node.stack.includes('Legend')) v(node.line, `${cat.legend} is not inside the Legend subgraph`);
    if (normaliseSpace(node.label ?? '').toLowerCase() !== cat.name.toLowerCase()) {
      v(node.line, `${cat.legend} is labelled "${node.label ?? ''}"; expected "${cat.name}"`);
    }
    const { classFills, effective } = fillOf(fc, node);
    if (!classFills.some((f) => sameColour(f, cat.rgb)) || !sameColour(effective, cat.rgb)) {
      const expected = `${fmtHex(cat.rgb)} (${fmtRgb(cat.rgb)})`;
      v(node.line, `${cat.legend} needs a class whose classDef fill is ${expected}; found ${effective ?? 'none'}`);
    }
  }
}

function checkSequence(sq, catalogue, violations) {
  const { rel } = sq;
  const v = (line, message) => violations.push({ path: rel, line, message });
  const closingNote = sq.last?.kind === 'note' ? sq.last.note : null;
  const count = sq.participants.size + (closingNote ? 1 : 0);
  if (count > NODE_BUDGET) {
    v(undefined, `${count} participants and closing note exceed the ${NODE_BUDGET}-node budget; split the diagram`);
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
    checkLabel(rel, p.line, p.id, p.label, violations);
    const box = p.box === null ? null : sq.boxes[p.box];
    if (!box || box.name.toLowerCase() !== category.toLowerCase()) {
      const at = box ? `in box "${box.name}"` : 'outside every box';
      v(p.line, `participant ${p.id} (${category}) sits ${at}; expected the "${category}" box`);
    }
  }

  if (!closingNote) v(sq.last?.line, 'the last statement must be the closing description Note');
  else checkDescriptionText(rel, closingNote.line, closingNote.text, violations);

  const order = CATEGORIES.map((c) => c.name).join(', ');
  if (sq.boxes.length !== CATEGORIES.length) {
    v(undefined, `${sq.boxes.length} box groups; expected exactly ${CATEGORIES.length} (${order})`);
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
      v(undefined, `legend participant ${cat.legend} is missing`);
      continue;
    }
    if (p.label.toLowerCase() !== cat.name.toLowerCase()) {
      v(p.line, `${cat.legend} is labelled "${p.label}"; expected "${cat.name}"`);
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
  const merged = { nodes: new Map(), labels: new Map(), edges: [], subgraphs: new Map(), members: new Map() };
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
    for (const sg of fc.subgraphs.values()) {
      if (!merged.subgraphs.has(sg.id)) merged.subgraphs.set(sg.id, { ...sg, rel: fc.rel });
      sg.ancestors.forEach((a) => addMember(a, sg.id));
    }
  }
  return merged;
}

function checkSecurity(parts, catalogue, controls, secretConsumers, violations) {
  const set = mergeFlowcharts(parts);
  const where = parts.length === 1 ? parts[0].rel : `${DIAGRAM_DIR}/security-architecture*.mmd`;
  const v = (rel, line, message) => violations.push({ path: rel, line, message });
  const categoryOf = (id) => componentCategory(catalogue, id);
  const joins = (e, from, to) => (e.from === from && e.to === to) || (e.bidi && e.from === to && e.to === from);
  const hasEdge = (from, to, token) => set.edges.some((e) => joins(e, from, to) && hasToken(e.label, token));

  const texts = [
    ...parts.map((p) => ({ rel: p.rel, line: 1, what: 'title', text: p.title ?? '' })),
    ...[...set.nodes.values()].flatMap((n) =>
      set.labels.get(n.id).map((text) => ({ rel: n.rel, line: n.line, what: `label of ${n.id}`, text })),
    ),
    ...set.edges.map((e) => ({ rel: e.rel, line: e.line, what: `edge ${e.from} → ${e.to}`, text: e.label })),
    ...[...set.subgraphs.values()].map((g) => ({
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

  const mtlsIds = new Set([...controls].filter(([, c]) => c.kind === KIND.MTLS).map(([id]) => id));
  for (const e of set.edges) {
    if (!MTLS_CATEGORIES.has(categoryOf(e.from)) || !MTLS_CATEGORIES.has(categoryOf(e.to))) continue;
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
        if (!set.edges.some((e) => (e.from === c || e.to === c) && re.test(e.label))) {
          v(where, undefined, `${id} (mTLS) protects ${c}, but no edge at ${c} is labelled "${id} mTLS"`);
        }
      }
    } else if (control.kind === KIND.SECRET) {
      const consumers = new Set([...control.protectedComponents, ...secretConsumers]);
      consumers.delete('AzureKeyVault');
      for (const c of consumers) {
        if (!hasEdge('AzureKeyVault', c, id)) {
          v(where, undefined, `${id} (Secret) needs an edge AzureKeyVault → ${c} labelled ${id}`);
        }
      }
    } else if (control.kind === KIND.ENCRYPTION) {
      for (const store of new Set(['AzurePostgres', 'AzureManagedRedis', ...control.protectedComponents])) {
        const labels = set.labels.get(store);
        const node = set.nodes.get(store);
        if (!labels) {
          v(where, undefined, `${id} (Encryption at rest) protects ${store}, which is not in the security diagram`);
        } else if (!labels.some((l) => hasToken(l, id))) {
          v(node.rel, node.line, `label of ${store} does not carry ${id} (Encryption at rest)`);
        }
      }
    } else if (control.kind === KIND.AUTH) {
      if (!hasEdge('AccountAccessGuard', 'AuthService', id)) {
        v(where, undefined, `${id} (AuthN/authZ) needs an edge AccountAccessGuard → AuthService labelled ${id}`);
      }
    } else if (control.kind === KIND.THREAT) {
      const boundary = [...set.subgraphs.values()].find((g) => new RegExp(`^${id}\\b`).test(g.title));
      if (!boundary) {
        v(where, undefined, `${id} (Threat boundary) has no subgraph whose title starts with ${id}`);
        continue;
      }
      for (const c of control.protectedComponents) {
        if (c !== boundary.id && !set.members.get(boundary.id)?.has(c)) {
          v(boundary.rel, boundary.line, `${id} (Threat boundary) subgraph ${boundary.id} does not contain ${c}`);
        }
      }
    }
  }

  for (const n of set.nodes.values()) {
    if (!n.id.startsWith('Acm')) continue;
    const linked = set.edges.some(
      (e) => (e.from === n.id && e.to === 'AccountAccessGuard') || (e.to === n.id && e.from === 'AccountAccessGuard'),
    );
    if (!linked) v(n.rel, n.line, `access-control row ${n.id} has no edge to AccountAccessGuard`);
  }
}

// ---------------------------------------------------------------------------------------
// External systems in every architecture view, and the catalogue Diagrams column

function checkExternalSystems(files, diagrams, securityParts, violations) {
  for (const rel of [LOGICAL, PHYSICAL]) {
    if (!files.includes(rel)) {
      violations.push({ path: rel, message: 'required architecture diagram is missing' });
      continue;
    }
    const d = diagrams.get(rel);
    if (!d) continue;
    for (const ext of EXTERNAL_SYSTEMS) {
      const shown = d.kind === 'flowchart' ? d.nodes.has(ext) : d.participants.has(ext);
      if (!shown) violations.push({ path: rel, message: `external system ${ext} is not shown` });
    }
  }
  if (securityParts.length === 0) return;
  const where = securityParts.length === 1 ? securityParts[0].rel : `${DIAGRAM_DIR}/security-architecture*.mmd`;
  for (const ext of EXTERNAL_SYSTEMS) {
    if (!securityParts.some((p) => p.nodes.has(ext))) {
      violations.push({ path: where, message: `external system ${ext} is not shown` });
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

function main() {
  const violations = [];
  const files = listFiles(DIAGRAM_DIR, { exts: ['.mmd'] });
  if (files.length === 0) violations.push({ path: DIAGRAM_DIR, message: 'no .mmd diagram found' });
  else renderAll(files, violations);

  let catalogue = new Map();
  let controls = new Map();
  let secretConsumers = new Set();
  if (!exists(TRD)) {
    violations.push({ path: TRD, message: 'Technical Requirements Document is missing' });
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

  const securityFiles = files.filter((rel) => SECURITY_RE.test(path.posix.basename(rel)));
  const securityParts = [];
  for (const rel of securityFiles) {
    const d = diagrams.get(rel);
    if (d?.kind === 'flowchart') securityParts.push(d);
    else if (d) violations.push({ path: rel, message: 'security architecture diagram must be a flowchart' });
  }
  if (securityFiles.length === 0) {
    violations.push({ path: DIAGRAM_DIR, message: 'no security-architecture*.mmd diagram found' });
  } else if (securityParts.length > 0) {
    checkSecurity(securityParts, catalogue, controls, secretConsumers, violations);
  }
  checkExternalSystems(files, diagrams, securityParts, violations);
  if (catalogue.size > 0) checkCatalogueDiagrams(files, catalogue, diagrams, violations);

  report(CHECK, violations);
}

try {
  main();
} catch (err) {
  console.error(`${CHECK}: internal error: ${err?.stack ?? err}`);
  process.exitCode = 2;
}

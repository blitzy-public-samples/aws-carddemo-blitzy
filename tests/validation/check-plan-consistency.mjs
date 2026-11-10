// SPDX-License-Identifier: Apache-2.0
/**
 * check-plan-consistency — implementation plan gate for the Card Servicing backlog.
 *
 * Run third by tests/validation/run-all.sh as `node tests/validation/check-plan-consistency.mjs`
 * from any working directory; every path is repository-root-relative through ./lib/markdown.mjs.
 * Prints one line per violation and a final count; exits 0 with none, 1 with any, 2 on an
 * internal error. Reads docs/implementation-plan.md, docs/backlog/epics/README.md, the 13 epic
 * READMEs and the 58 story files only — never the CardDemo reference roots.
 *
 * Blocks and the rule gate each one enforces:
 * - Backlog ground truth (story map, per-epic and per-wave sums from story metadata): the values
 *   every later block compares against; "Enforce Implementation Plan, Epic and Story Structure —
 *   Base" (epic and wave values equal their story sums) and "Epic and Story Structure — Multi-File
 *   Variant" (one identifier across title line and filename).
 * - Plan existence and opening summary: "Enforce Implementation Plan, Epic and Story Structure —
 *   Base" (plan at the named path; the opening summary precedes every table).
 * - Wave summary table: "Enforce Implementation Plan, Epic and Story Structure — Base" (wave summary
 *   inside the opening summary, one row per wave, closed by a totals row equal to the sum of waves).
 * - Wave sections: both rules — Base (one section per wave in order, story table rows with
 *   position, linked story, title, linked epic, points and files touched, positions 1..n with
 *   same-wave prerequisites first, Build wave equal to the plan wave, totals line equal to the table
 *   and the wave summary) and Multi-File Variant (every story file in exactly one wave story table,
 *   story links resolving to existing story files, epic links to that epic folder's README, one
 *   identifier format across plan, title line and filename).
 * - Epics-in-scope table, epic index and epic README Stories tables: Base (epic values equal their
 *   story sums; files touched per story row equals its metadata) and Multi-File Variant (epic links
 *   resolve to the epic folder's README).
 * - Files-touched rule: Base (every table whose rows are waves, epics or stories carries a files
 *   touched column summed in its totals row or totals line). Applied inside each table block.
 * - Inventory guard: the binding story inventory of the Agent Action Plan.
 * - Plan layout, required sections and gap list: the Agent Action Plan's plan layout (section
 *   order, Recommendations, Feature Checklist coverage, the CARD_REQ_TBD gap list last with all
 *   stories and the Customer Details unmapped-field table equal to the CS-01 epic README's
 *   field-gap table).
 * - Name and number sync across plan tables, the epic index and epic READMEs (story titles, epic
 *   names and groupings, Build waves, Depends on cells): the Agent Action Plan's rule that every
 *   table carries the inventory's values; no other check compares these cells with metadata.
 *
 * This check never re-validates metadata form (check-backlog-structure owns that); a metadata
 * value it needs for a comparison or a sum and cannot parse is reported here.
 */
import {
  readText,
  exists,
  listFiles,
  parseHeadings,
  sections,
  sectionByHeading,
  parseTables,
  isTotalsRow,
  resolveLink,
  linkInCell,
  parseMetadata,
  intStrict,
  fencedBlocks,
  report,
} from './lib/markdown.mjs';

const CHECK = 'check-plan-consistency';
const PLAN = 'docs/implementation-plan.md';
const EPICS_DIR = 'docs/backlog/epics';
const EPIC_INDEX = `${EPICS_DIR}/README.md`;
const CS01_README = `${EPICS_DIR}/CS-01-account-balance-summary-view/README.md`;

// Binding inventory (Agent Action Plan 0.5.6.1 epic count, 0.5.6.2 story inventory): stories,
// points and estimated files touched per wave and in total. Waves are listed in execution order.
const INVENTORY = Object.freeze({
  epics: 13,
  total: Object.freeze({ stories: 58, points: 175, files: 224 }),
  waves: Object.freeze({
    W0: Object.freeze({ stories: 12, points: 40, files: 83 }),
    W1: Object.freeze({ stories: 21, points: 63, files: 75 }),
    W2: Object.freeze({ stories: 13, points: 42, files: 44 }),
    W3: Object.freeze({ stories: 12, points: 30, files: 22 }),
  }),
});
const WAVES = Object.freeze(Object.keys(INVENTORY.waves));

// Customer Details field gaps (Agent Action Plan 0.5.6.4): 17 labels, 16 carrying the first
// status and Customer id alone carrying the second. Both use an em dash (U+2014).
const FIELD_GAP_LABELS = 17;
const STATUS_NO_ENTITY = 'Field gap \u2014 no baseline entity or operation';
const STATUS_NOT_EXPOSED = 'Field gap \u2014 baseline field not exposed on balance summary';
const NOT_EXPOSED_LABEL = 'customer id';
const NO_ENTITY_COUNT = 16;

const GAP_LIST_SLUG = 'card_req_tbd-gap-list';

const STORY_PATH_RE = /^docs\/backlog\/epics\/([^/]+)\/stories\/([^/]+\.md)$/;
const EPIC_README_RE = /^docs\/backlog\/epics\/([^/]+)\/README\.md$/;
const STORY_ID_IN_NAME_RE = /^(CS-\d{2}-\d{3})(?=-|\.md$)/;
const EPIC_ID_IN_NAME_RE = /^(CS-\d{2})(?=-|$)/;
const STORY_ID_G = /CS-\d{2}-\d{3}/g;
const WAVE_ID_G = /\bW\d+\b/g;
const KEY_TITLE_RE = /^(\S+?):\s+(.+)$/;
const TOTALS_LINE_RE = /^W(\d+) totals: (\d+) stories, (\d+) points, (\d+) files touched\.?$/;
const COUNT_CELL_RE = /^(\d+)\s+(stories|story|epics|epic)$/i;

// Header matchers; every column is located by its header, never by position.
const COL = Object.freeze({
  pos: /pos/i,
  title: /title/i,
  epic: /epic/i,
  points: /point/i,
  files: /files touched/i,
  stories: /stor/i,
  waveExact: /^wave$/i,
  wave: /wave/i,
  unlocks: /unlock/i,
  ciGate: /ci gate/i,
  name: /^name$/i,
  grouping: /grouping/i,
  depends: /depends/i,
  id: /^id$/i,
  label: /label/i,
  status: /status/i,
  storyHeader: /^story( id)?$/i,
  epicHeader: /^(epic|folder)$/i,
});

const violations = [];
const fail = (path, line, message) => violations.push({ path, line, message });

/** Cell text for comparison: link markup reduced to its text, `**` and backticks removed, spaces collapsed. */
function norm(cell) {
  return String(cell ?? '')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\*\*/g, '')
    .replace(/`/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Integer value of a cell; a cell that is not a plain integer is reported and gives null. */
function cellInt(path, line, cell, what) {
  const value = intStrict(norm(cell));
  if (value === null) fail(path, line, `${what} is not an integer: "${norm(cell)}"`);
  return value;
}

/** Sum of values, or null when any value is missing (that value is reported where it is read). */
function sumOrNull(values) {
  let sum = 0;
  for (const v of values) {
    if (v == null) return null;
    sum += v;
  }
  return sum;
}

const waveSet = (text) => [...new Set(String(text ?? '').match(WAVE_ID_G) ?? [])].sort();
const sameList = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

function colIndex(table, ...matchers) {
  for (const re of matchers) {
    const idx = table.header.findIndex((h) => re.test(norm(h)));
    if (idx >= 0) return idx;
  }
  return -1;
}

/** Splits a table into body rows and totals rows, each with its document line and row index. */
function rowsOf(table) {
  const body = [];
  const totals = [];
  table.rows.forEach((cells, index) => {
    (isTotalsRow(cells) ? totals : body).push({ cells, line: table.rowLines[index], index });
  });
  return { body, totals };
}

/**
 * The column whose cells link to paths matching pathRe (preferring a header matching headerRe),
 * or -1. With `fallback`, a column whose header matches headerRe is returned when no cell links.
 */
function linkColumn(table, fromRel, pathRe, headerRe, { fallback = false } = {}) {
  const body = table.rows.filter((r) => !isTotalsRow(r));
  const candidates = table.header
    .map((_, c) =>
      body.some((r) => {
        const link = linkInCell(r[c]);
        return link !== null && pathRe.test(resolveLink(fromRel, link.target).path);
      })
        ? c
        : -1,
    )
    .filter((c) => c >= 0);
  const preferred = candidates.find((c) => headerRe.test(norm(table.header[c])));
  if (preferred !== undefined) return preferred;
  if (candidates.length > 0) return candidates[0];
  return fallback ? table.header.findIndex((h) => headerRe.test(norm(h))) : -1;
}

const storyColumn = (table, fromRel, opts) => linkColumn(table, fromRel, STORY_PATH_RE, COL.storyHeader, opts);
const epicColumn = (table, fromRel, opts) => linkColumn(table, fromRel, EPIC_README_RE, COL.epicHeader, opts);
const isStoryTable = (table, fromRel) =>
  storyColumn(table, fromRel) >= 0 || table.header.some((h) => COL.storyHeader.test(norm(h)));

/* ------------------------------------------------------------------------------------------------
 * Backlog ground truth
 * ---------------------------------------------------------------------------------------------- */

function loadBacklog() {
  const epics = new Map(); // folder -> epic
  const byPath = new Map(); // story path -> story
  const byId = new Map(); // story id -> story
  const storyFiles = [];

  for (const file of listFiles(EPICS_DIR, { exts: ['.md'] })) {
    const em = EPIC_README_RE.exec(file);
    if (em) {
      epics.set(em[1], loadEpic(file, em[1]));
      continue;
    }
    if (STORY_PATH_RE.test(file)) storyFiles.push(file);
  }

  for (const file of storyFiles) {
    const story = loadStory(file);
    if (story === null) continue;
    byPath.set(file, story);
    if (byId.has(story.id)) {
      fail(file, undefined, `story identifier ${story.id} is also carried by ${byId.get(story.id).path}`);
      continue;
    }
    byId.set(story.id, story);
  }

  for (const epic of epics.values()) {
    const own = [...byPath.values()].filter((s) => s.folder === epic.folder);
    epic.stories = own;
    epic.count = own.length;
    epic.points = sumOrNull(own.map((s) => s.points));
    epic.files = sumOrNull(own.map((s) => s.files));
    epic.waves = [...new Set(own.map((s) => s.wave).filter(Boolean))].sort();
  }
  return { epics, byPath, byId };
}

function loadEpic(readme, folder) {
  const id = EPIC_ID_IN_NAME_RE.exec(folder)?.[1] ?? null;
  if (id === null) fail(readme, undefined, `epic folder "${folder}" does not start with a CS-NN identifier`);
  const h1 = parseHeadings(readText(readme)).find((h) => !h.quoted);
  const m = h1 && h1.level === 1 ? KEY_TITLE_RE.exec(h1.text) : null;
  if (m === null) fail(readme, h1?.line, 'epic README has no "# CS-NN: <name>" title line to compare epic names against');
  return { folder, id, readme, name: m ? norm(m[2]) : null };
}

function loadStory(file) {
  const [, folder, fileName] = STORY_PATH_RE.exec(file);
  const id = STORY_ID_IN_NAME_RE.exec(fileName)?.[1] ?? null;
  if (id === null) {
    fail(file, undefined, 'story filename does not start with a CS-NN-NNN identifier');
    return null;
  }
  const text = readText(file);
  const story = { id, path: file, folder, title: null, wave: null, points: null, files: null, deps: [] };

  // One identifier format: the title line KEY equals the filename identifier.
  const h1 = parseHeadings(text).find((h) => !h.quoted);
  const m = h1 && h1.level === 1 ? KEY_TITLE_RE.exec(h1.text) : null;
  if (m === null) {
    fail(file, h1?.line, 'story has no "# <KEY>: <title>" title line');
  } else {
    story.title = norm(m[2]);
    if (norm(m[1]) !== id) fail(file, h1.line, `title line KEY "${norm(m[1])}" differs from the filename identifier ${id}`);
  }

  const meta = sectionByHeading(text, /^metadata$/i);
  if (meta === null) {
    fail(file, undefined, 'story has no ## Metadata section to compare plan rows against');
    return story;
  }
  const fields = parseMetadata(meta.text);
  const field = (key) => {
    if (!Object.prototype.hasOwnProperty.call(fields, key)) {
      fail(file, meta.startLine, `metadata has no "${key}" field`);
      return null;
    }
    return fields[key];
  };
  const wave = field('build wave');
  if (wave !== null) {
    story.wave = /^W\d+$/.test(norm(wave)) ? norm(wave) : null;
    if (story.wave === null) fail(file, meta.startLine, `metadata Build wave "${norm(wave)}" is not a wave identifier`);
  }
  const points = field('story points');
  if (points !== null) story.points = cellInt(file, meta.startLine, points, 'metadata Story Points');
  const files = field('estimated files touched');
  if (files !== null) story.files = cellInt(file, meta.startLine, files, 'metadata Estimated Files Touched');
  const deps = field('depends on');
  if (deps !== null) story.deps = [...new Set(deps.match(STORY_ID_G) ?? [])];
  return story;
}

const totalsOf = (stories) => ({
  stories: stories.length,
  points: sumOrNull(stories.map((s) => s.points)),
  files: sumOrNull(stories.map((s) => s.files)),
});

const LABEL = Object.freeze({ stories: 'story count', points: 'points', files: 'estimated files touched' });

/** Compares a {stories, points, files} triple with the binding inventory, key by key. */
function checkInventory(path, line, what, actual, expected) {
  for (const key of ['stories', 'points', 'files']) {
    if (actual[key] != null && actual[key] !== expected[key]) {
      fail(path, line, `${what} ${LABEL[key]} is ${actual[key]}; the binding inventory has ${expected[key]}`);
    }
  }
}

/* ------------------------------------------------------------------------------------------------
 * Shared row comparisons
 * ---------------------------------------------------------------------------------------------- */

const has = (idx) => Number.isInteger(idx) && idx >= 0;

/**
 * The story a table row names through its story column. A row whose story ID is not a link, or
 * whose link does not resolve to an existing story file, is reported; the story its text names is
 * still returned so the exactly-once count stays exact. Link text must be the story identifier.
 */
function storyOfRow(fromRel, row, col, what, backlog) {
  if (!has(col)) return null;
  const cell = row.cells[col];
  const link = linkInCell(cell);
  if (link === null) {
    fail(fromRel, row.line, `${what} row "${norm(cell)}": the story ID is not a link to its story file`);
    return backlog.byId.get(norm(cell)) ?? null;
  }
  const target = resolveLink(fromRel, link.target).path;
  const story = backlog.byPath.get(target);
  if (story === undefined) {
    fail(fromRel, row.line, `${what} row "${norm(link.text)}": link ${link.target} does not resolve to an existing story file`);
    return backlog.byId.get(norm(link.text)) ?? null;
  }
  if (norm(link.text) !== story.id) {
    fail(fromRel, row.line, `${what} row: link text "${norm(link.text)}" differs from ${story.id}, the identifier of its title line and filename`);
  }
  return story;
}

/** The epic a table row links to through its epic column; a missing or stray link is reported. */
function epicOfRow(fromRel, row, col, what, backlog) {
  if (!has(col)) return null;
  const link = linkInCell(row.cells[col]);
  if (link === null) {
    fail(fromRel, row.line, `${what} row "${norm(row.cells[col])}": the epic is not a link to its epic README`);
    return null;
  }
  const target = resolveLink(fromRel, link.target).path;
  const m = EPIC_README_RE.exec(target);
  const epic = m ? backlog.epics.get(m[1]) : undefined;
  if (epic === undefined) {
    fail(fromRel, row.line, `${what} row "${norm(link.text)}": link ${link.target} does not resolve to an existing epic folder README`);
    return null;
  }
  return epic;
}

/** Compares the title, points, files touched, Build wave and Depends on cells a row carries with the story's own values. */
function compareStoryCells(path, row, c, story, what) {
  if (has(c.title) && story.title !== null && norm(row.cells[c.title]) !== story.title) {
    fail(path, row.line, `${what} ${story.id}: title "${norm(row.cells[c.title])}" differs from the story title "${story.title}"`);
  }
  if (has(c.points)) {
    const v = cellInt(path, row.line, row.cells[c.points], `${what} ${story.id} points`);
    if (v !== null && story.points !== null && v !== story.points) {
      fail(path, row.line, `${what} ${story.id}: points ${v} differ from metadata Story Points ${story.points}`);
    }
  }
  if (has(c.files)) {
    const v = cellInt(path, row.line, row.cells[c.files], `${what} ${story.id} estimated files touched`);
    if (v !== null && story.files !== null && v !== story.files) {
      fail(path, row.line, `${what} ${story.id}: estimated files touched ${v} differs from metadata Estimated Files Touched ${story.files}`);
    }
  }
  if (has(c.wave) && story.wave !== null && !sameList(waveSet(row.cells[c.wave]), [story.wave])) {
    fail(path, row.line, `${what} ${story.id}: wave "${norm(row.cells[c.wave])}" differs from metadata Build wave ${story.wave}`);
  }
  // A plan table's Depends on column is a plan dependency as well; holding it equal to metadata
  // lets the Dependency Map, which check-dependency-order compares with metadata, cover it too.
  if (has(c.depends)) {
    const listed = [...new Set(norm(row.cells[c.depends]).match(STORY_ID_G) ?? [])].sort();
    const declared = [...story.deps].sort();
    if (!sameList(listed, declared)) {
      fail(path, row.line, `${what} ${story.id}: Depends on "${listed.join(', ') || 'none'}" differs from metadata Depends on "${declared.join(', ') || 'none'}"`);
    }
  }
}

/** Compares the identifier, name, story count, points, files touched and waves cells of an epic row with the epic's derived values. */
function compareEpicCells(path, row, c, epic, what) {
  const label = epic.id ?? epic.folder;
  if (has(c.id) && epic.id !== null && norm(row.cells[c.id]) !== epic.id) {
    fail(path, row.line, `${what} ${label}: ID "${norm(row.cells[c.id])}" differs from the epic folder identifier ${epic.id}`);
  }
  if (has(c.name) && epic.name !== null && norm(row.cells[c.name]) !== epic.name) {
    fail(path, row.line, `${what} ${label}: name "${norm(row.cells[c.name])}" differs from the epic README title "${epic.name}"`);
  }
  for (const [key, col] of [['stories', c.stories], ['points', c.points], ['files', c.files]]) {
    if (!has(col)) continue;
    const v = cellInt(path, row.line, row.cells[col], `${what} ${label} ${LABEL[key]}`);
    const derived = key === 'stories' ? epic.count : epic[key];
    if (v !== null && derived !== null && v !== derived) {
      fail(path, row.line, `${what} ${label}: ${LABEL[key]} ${v} differs from the sum of its stories, ${derived}`);
    }
  }
  if (has(c.waves) && !sameList(waveSet(row.cells[c.waves]), epic.waves)) {
    fail(path, row.line, `${what} ${label}: waves "${norm(row.cells[c.waves])}" differ from its stories' Build waves "${epic.waves.join(', ')}"`);
  }
}

/**
 * Checks a table's totals row: exactly one, the last row, and every listed column equal to its
 * body sum. Returns { row, values } with the stated totals by key, or null when there is none.
 */
function checkTotalsRow(path, table, what, specs) {
  const { body, totals } = rowsOf(table);
  if (totals.length === 0) {
    fail(path, table.line, `${what} has no totals row`);
    return null;
  }
  if (totals.length > 1) fail(path, totals[1].line, `${what} has more than one totals row`);
  const row = totals[0];
  if (row.index !== table.rows.length - 1) fail(path, row.line, `${what} totals row is not its last row`);
  const values = {};
  for (const { key, idx } of specs) {
    if (!has(idx)) continue;
    const sum = sumOrNull(body.map((r) => intStrict(norm(r.cells[idx]))));
    const value = cellInt(path, row.line, row.cells[idx], `${what} totals ${LABEL[key]}`);
    values[key] = value;
    if (value !== null && sum !== null && value !== sum) {
      fail(path, row.line, `${what} totals ${LABEL[key]} is ${value}, but its rows sum to ${sum}`);
    }
  }
  return { row, values };
}

/** A totals cell such as "13 epics" or "5 stories" must state the expected count. */
function checkCountCell(path, totalsRow, expected, what) {
  for (const cell of totalsRow.cells.slice(1)) {
    const m = COUNT_CELL_RE.exec(norm(cell));
    if (m && Number(m[1]) !== expected) {
      fail(path, totalsRow.line, `${what} totals row states "${norm(cell)}", but the count is ${expected}`);
    }
  }
}

function requireColumns(path, table, what, c, labels) {
  for (const [key, label] of labels) {
    if (has(c[key])) continue;
    if (key === 'files') fail(path, table.line, `${what} carries no estimated files touched column`);
    else fail(path, table.line, `${what} has no ${label} column`);
  }
}

function fencedLineSet(text) {
  const set = new Set();
  for (const block of fencedBlocks(text)) {
    for (let n = block.startLine; n <= block.endLine; n++) set.add(n);
  }
  return set;
}

/* ------------------------------------------------------------------------------------------------
 * Plan opening summary and wave summary table
 * ---------------------------------------------------------------------------------------------- */

function checkOpening(plan) {
  const h1 = plan.heads[0];
  if (h1 === undefined || h1.level !== 1) fail(PLAN, h1?.line, 'the plan does not open with a level-1 title');
  const first = plan.tables[0];
  if (first === undefined) return;
  const start = h1 ? h1.line : 0;
  if (first.line < start) {
    fail(PLAN, first.line, 'a table precedes the plan title and its opening summary');
    return;
  }
  const headingLines = new Set(plan.allHeads.map((h) => h.line));
  for (let n = start + 1; n < first.line; n++) {
    const s = plan.lines[n - 1].trim();
    if (s !== '' && !plan.fenced.has(n) && !headingLines.has(n) && !s.startsWith('|')) return;
  }
  fail(PLAN, first.line, 'a table precedes the opening summary: no summary prose stands between the title and the first table');
}

/**
 * Last line (exclusive) of the opening summary. The summary runs from the title to the first
 * `##` heading; a first `##` heading named "Opening summary" opens the summary itself, so the
 * region then runs to the second `##` heading. This reads "inside the plan's opening summary"
 * as the plan layout and the Enforce Implementation Plan, Epic and Story Structure — Base rule
 * state it, for plans that title their summary section.
 */
function openingEnd(h2Heads) {
  const [first, second] = h2Heads;
  if (first && /^opening summary$/i.test(norm(first.text))) return second ? second.line : Infinity;
  return first ? first.line : Infinity;
}

function checkWaveSummary(plan) {
  const summary = new Map();
  const what = 'wave summary table';
  const table = plan.tables.find(
    (t) => has(colIndex(t, COL.wave)) && has(colIndex(t, COL.unlocks)) && has(colIndex(t, COL.ciGate)),
  );
  if (table === undefined) {
    fail(PLAN, undefined, `no ${what} (columns Wave, Unlocks and CI gate stage added) in the opening summary`);
    return { summary, table: null };
  }
  if (table.line >= openingEnd(plan.h2Heads)) fail(PLAN, table.line, `the ${what} lies outside the opening summary`);
  const c = {
    wave: colIndex(table, COL.waveExact, COL.wave),
    name: colIndex(table, COL.name),
    stories: colIndex(table, COL.stories),
    points: colIndex(table, COL.points),
    files: colIndex(table, COL.files),
    unlocks: colIndex(table, COL.unlocks),
    ci: colIndex(table, COL.ciGate),
  };
  requireColumns(PLAN, table, what, c, [['name', 'Name'], ['stories', 'Stories'], ['points', 'Points'], ['files', '']]);

  const ids = [];
  for (const row of rowsOf(table).body) {
    const cell = norm(row.cells[c.wave]);
    const id = /^(W\d+)\b/.exec(cell)?.[1] ?? cell;
    ids.push(id);
    if (summary.has(id)) {
      fail(PLAN, row.line, `${what} lists wave ${id} more than once`);
      continue;
    }
    const entry = {
      line: row.line,
      name: has(c.name) ? norm(row.cells[c.name]) : null,
      stories: has(c.stories) ? cellInt(PLAN, row.line, row.cells[c.stories], `${what} ${id} story count`) : null,
      points: has(c.points) ? cellInt(PLAN, row.line, row.cells[c.points], `${what} ${id} points`) : null,
      files: has(c.files) ? cellInt(PLAN, row.line, row.cells[c.files], `${what} ${id} estimated files touched`) : null,
    };
    summary.set(id, entry);
    for (const [key, label] of [['name', 'Name'], ['unlocks', 'Unlocks'], ['ci', 'CI gate stage added']]) {
      if (has(c[key]) && norm(row.cells[c[key]]) === '') fail(PLAN, row.line, `${what} ${id} row has an empty ${label} cell`);
    }
    if (Object.hasOwn(INVENTORY.waves, id)) checkInventory(PLAN, row.line, `${what} ${id}`, entry, INVENTORY.waves[id]);
  }
  if (!sameList(ids, WAVES)) {
    fail(PLAN, table.line, `${what} rows are "${ids.join(', ')}"; expected ${WAVES.join(', ')} in that order`);
  }
  const totals = checkTotalsRow(PLAN, table, what, [
    { key: 'stories', idx: c.stories },
    { key: 'points', idx: c.points },
    { key: 'files', idx: c.files },
  ]);
  if (totals) checkInventory(PLAN, totals.row.line, `${what} total`, totals.values, INVENTORY.total);
  return { summary, table };
}


/* ------------------------------------------------------------------------------------------------
 * Wave sections
 * ---------------------------------------------------------------------------------------------- */

const waveOfHeading = (text) => /^(W\d+)\b/.exec(norm(text))?.[1] ?? null;

function checkWaveSections(plan, backlog, summary) {
  const byWave = new Map();
  const order = [];
  for (const sec of plan.h2Secs) {
    const id = waveOfHeading(sec.heading);
    if (id === null) continue;
    if (!WAVES.includes(id)) {
      fail(PLAN, sec.startLine, `wave section ${id} is not one of ${WAVES.join(', ')}`);
    } else if (byWave.has(id)) {
      fail(PLAN, sec.startLine, `wave ${id} has more than one section`);
    } else {
      byWave.set(id, sec);
      order.push(id);
    }
  }
  const present = WAVES.filter((id) => byWave.has(id));
  if (!sameList(order, present)) {
    fail(PLAN, byWave.get(order[0])?.startLine, `wave sections run ${order.join(', ')}; expected ${WAVES.join(', ')} in execution order`);
  }

  const appearances = new Map(); // story path -> [{ wave, line }]
  for (const id of WAVES) {
    const sec = byWave.get(id);
    if (sec === undefined) {
      fail(PLAN, undefined, `the plan has no "## ${id} …" wave section`);
      continue;
    }
    checkWaveSection(plan, backlog, summary, id, sec, appearances);
  }

  for (const story of backlog.byPath.values()) {
    const seen = appearances.get(story.path) ?? [];
    if (seen.length === 0) {
      fail(story.path, undefined, `${story.id} appears in no wave story table of ${PLAN}`);
    } else if (seen.length > 1) {
      fail(PLAN, seen[1].line, `${story.id} appears in ${seen.length} wave story table rows (${seen.map((s) => s.wave).join(', ')}); a story appears in exactly one`);
    }
  }
}

function checkWaveSection(plan, backlog, summary, id, sec, appearances) {
  const what = `${id} story table`;
  const tables = plan.tables.filter((t) => t.line >= sec.startLine && t.line <= sec.endLine && isStoryTable(t, PLAN));
  if (tables.length === 0) {
    fail(PLAN, sec.startLine, `${id} section has no story table`);
    return;
  }
  if (tables.length > 1) fail(PLAN, tables[1].line, `${id} section has more than one story table`);
  const table = tables[0];
  const c = {
    pos: colIndex(table, COL.pos),
    story: storyColumn(table, PLAN, { fallback: true }),
    title: colIndex(table, COL.title),
    epic: epicColumn(table, PLAN, { fallback: true }),
    points: colIndex(table, COL.points),
    files: colIndex(table, COL.files),
    depends: colIndex(table, COL.depends),
  };
  requireColumns(PLAN, table, what, c, [
    ['pos', 'Pos.'],
    ['story', 'Story'],
    ['title', 'Title'],
    ['epic', 'Epic'],
    ['points', 'Points'],
    ['files', ''],
  ]);

  const { body } = rowsOf(table);
  const rows = [];
  body.forEach((row, i) => {
    const pos = has(c.pos) ? cellInt(PLAN, row.line, row.cells[c.pos], `${what} position`) : null;
    if (pos !== null && pos !== i + 1) {
      fail(PLAN, row.line, `${what} position ${pos} breaks the 1..n sequence; row ${i + 1} carries position ${i + 1}`);
    }
    const story = storyOfRow(PLAN, row, c.story, what, backlog);
    if (story === null) return;
    rows.push({ story, pos: pos ?? i + 1, line: row.line });
    if (!appearances.has(story.path)) appearances.set(story.path, []);
    appearances.get(story.path).push({ wave: id, line: row.line });

    if (has(c.epic)) {
      const expected = `${EPICS_DIR}/${story.folder}/README.md`;
      const link = linkInCell(row.cells[c.epic]);
      if (link === null) {
        fail(PLAN, row.line, `${what} ${story.id}: the parent epic is not a link to its epic README`);
      } else {
        const target = resolveLink(PLAN, link.target).path;
        if (target !== expected) {
          fail(PLAN, row.line, `${what} ${story.id}: epic link ${link.target} resolves to ${target}, not the story's epic README ${expected}`);
        } else if (!backlog.epics.has(story.folder)) {
          fail(PLAN, row.line, `${what} ${story.id}: epic README ${expected} does not exist`);
        }
      }
    }
    compareStoryCells(PLAN, row, { title: c.title, points: c.points, files: c.files, depends: c.depends }, story, what);
    if (story.wave !== null && story.wave !== id) {
      fail(PLAN, row.line, `${story.id} sits in the ${id} story table, but its metadata Build wave is ${story.wave}`);
    }
  });

  // Same-wave prerequisites carry a lower position.
  const posOf = new Map(rows.map((r) => [r.story.id, r.pos]));
  for (const r of rows) {
    for (const dep of r.story.deps) {
      const depPos = posOf.get(dep);
      if (depPos !== undefined && depPos >= r.pos) {
        fail(PLAN, r.line, `${what}: ${r.story.id} at position ${r.pos} is not after its same-wave prerequisite ${dep} at position ${depPos}`);
      }
    }
  }

  const stated = {
    stories: body.length,
    points: has(c.points) ? sumOrNull(body.map((r) => intStrict(norm(r.cells[c.points])))) : null,
    files: has(c.files) ? sumOrNull(body.map((r) => intStrict(norm(r.cells[c.files])))) : null,
  };
  if (rowsOf(table).totals.length > 0) {
    checkTotalsRow(PLAN, table, what, [
      { key: 'points', idx: c.points },
      { key: 'files', idx: c.files },
    ]);
  }
  checkTotalsLine(plan, id, sec, table, stated);

  const entry = summary.get(id);
  if (entry) {
    for (const key of ['stories', 'points', 'files']) {
      if (entry[key] !== null && stated[key] !== null && entry[key] !== stated[key]) {
        fail(PLAN, entry.line, `wave summary ${id} ${LABEL[key]} is ${entry[key]}, but the ${id} story table has ${stated[key]}`);
      }
    }
    const name = norm(sec.heading).replace(/^W\d+\s*/, '');
    if (entry.name && entry.name !== name) {
      fail(PLAN, entry.line, `wave summary ${id} name "${entry.name}" differs from its section heading "${norm(sec.heading)}"`);
    }
  }
  checkInventory(PLAN, table.line, what, stated, INVENTORY.waves[id]);
}

/** The closing "Wn totals: …" line: present once, after the table, equal to the table's count and sums. */
function checkTotalsLine(plan, id, sec, table, stated) {
  const found = [];
  for (let n = sec.startLine; n <= sec.endLine; n++) {
    if (plan.fenced.has(n)) continue;
    const s = plan.lines[n - 1].replace(/\*\*|__/g, '').trim();
    if (/^W\d+\s+totals\b/i.test(s)) found.push({ n, s });
  }
  const form = `"${id} totals: <n> stories, <n> points, <n> files touched"`;
  if (found.length === 0) {
    fail(PLAN, sec.startLine, `${id} section has no closing totals line ${form}`);
    return;
  }
  if (found.length > 1) fail(PLAN, found[1].n, `${id} section has more than one totals line`);
  const { n, s } = found[0];
  const lastRowLine = table.rowLines.length > 0 ? table.rowLines[table.rowLines.length - 1] : table.line + 1;
  if (n < lastRowLine) fail(PLAN, n, `${id} totals line precedes the story table it closes`);
  const m = TOTALS_LINE_RE.exec(s);
  if (m === null) {
    fail(PLAN, n, `${id} totals line "${s}" does not read ${form}`);
    return;
  }
  if (`W${m[1]}` !== id) fail(PLAN, n, `totals line names W${m[1]}, but it closes the ${id} section`);
  const line = { stories: Number(m[2]), points: Number(m[3]), files: Number(m[4]) };
  for (const key of ['stories', 'points', 'files']) {
    if (stated[key] !== null && line[key] !== stated[key]) {
      fail(PLAN, n, `${id} totals line states ${LABEL[key]} ${line[key]}, but its story table has ${stated[key]}`);
    }
  }
}

/* ------------------------------------------------------------------------------------------------
 * Epics-in-scope table, epic index and epic README Stories tables
 * ---------------------------------------------------------------------------------------------- */

/** Shared body of the epics-in-scope table and the epic index: one row per epic, totals row. */
function checkEpicTable(path, table, what, c, backlog) {
  const seen = new Map(); // folder -> row
  const { body } = rowsOf(table);
  for (const row of body) {
    const epic = epicOfRow(path, row, c.epic, what, backlog);
    if (epic === null) continue;
    if (seen.has(epic.folder)) {
      fail(path, row.line, `${what} lists epic ${epic.id ?? epic.folder} more than once`);
      continue;
    }
    seen.set(epic.folder, row);
    compareEpicCells(path, row, c, epic, what);
  }
  for (const epic of backlog.epics.values()) {
    if (!seen.has(epic.folder)) fail(path, table.line, `${what} has no row for epic ${epic.id ?? epic.folder}`);
  }
  if (body.length !== INVENTORY.epics) {
    fail(path, table.line, `${what} has ${body.length} epic rows; the binding inventory has ${INVENTORY.epics}`);
  }
  const totals = checkTotalsRow(path, table, what, [
    { key: 'stories', idx: c.stories },
    { key: 'points', idx: c.points },
    { key: 'files', idx: c.files },
  ]);
  if (totals) {
    checkCountCell(path, totals.row, body.length, what);
    checkInventory(path, totals.row.line, `${what} total`, totals.values, INVENTORY.total);
    if (has(c.waves) && norm(totals.row.cells[c.waves]) !== '') {
      const union = [...new Set(body.flatMap((r) => waveSet(r.cells[c.waves])))].sort();
      if (!sameList(waveSet(totals.row.cells[c.waves]), union)) {
        fail(path, totals.row.line, `${what} totals waves "${norm(totals.row.cells[c.waves])}" differ from the waves of its rows "${union.join(', ')}"`);
      }
    }
  }
  return seen;
}

const epicColumns = (table, fromRel) => ({
  epic: epicColumn(table, fromRel, { fallback: true }),
  id: colIndex(table, COL.id),
  name: colIndex(table, COL.name),
  grouping: colIndex(table, COL.grouping),
  stories: colIndex(table, COL.stories),
  points: colIndex(table, COL.points),
  files: colIndex(table, COL.files),
  waves: colIndex(table, COL.wave),
});

function groupingsOf(rowsByFolder, col) {
  const out = new Map();
  if (!has(col)) return out;
  for (const [folder, row] of rowsByFolder) out.set(folder, { grouping: norm(row.cells[col]), line: row.line });
  return out;
}

function checkEpicsInScope(plan, backlog) {
  const what = 'epics-in-scope table';
  const table = plan.tables.find(
    (t) => has(colIndex(t, COL.epic)) && has(colIndex(t, COL.grouping)) && has(colIndex(t, COL.wave)),
  );
  if (table === undefined) {
    fail(PLAN, undefined, `no ${what} (columns Epic, Grouping, Estimated files touched and Waves)`);
    return { table: null, groupings: new Map() };
  }
  const c = epicColumns(table, PLAN);
  requireColumns(PLAN, table, what, c, [['epic', 'Epic'], ['stories', 'Stories'], ['points', 'Points'], ['files', '']]);
  const rows = checkEpicTable(PLAN, table, what, c, backlog);
  return { table, groupings: groupingsOf(rows, c.grouping) };
}

function checkEpicIndex(backlog, planGroupings) {
  const what = 'epic index table';
  if (!exists(EPIC_INDEX)) {
    fail(EPIC_INDEX, undefined, 'the epic index does not exist');
    return;
  }
  const table = parseTables(readText(EPIC_INDEX)).find((t) => has(epicColumn(t, EPIC_INDEX)));
  if (table === undefined) {
    fail(EPIC_INDEX, undefined, `no ${what} linking every epic folder README`);
    return;
  }
  const c = epicColumns(table, EPIC_INDEX);
  requireColumns(EPIC_INDEX, table, what, c, [['stories', 'Stories'], ['points', 'Points'], ['files', '']]);
  const groupings = groupingsOf(checkEpicTable(EPIC_INDEX, table, what, c, backlog), c.grouping);
  for (const [folder, { grouping, line }] of groupings) {
    const planned = planGroupings.get(folder);
    if (planned && planned.grouping !== grouping) {
      const epic = backlog.epics.get(folder);
      fail(EPIC_INDEX, line, `${what} ${epic.id ?? folder}: grouping "${grouping}" differs from the plan's epics-in-scope grouping "${planned.grouping}"`);
    }
  }
}


function checkEpicReadmes(backlog) {
  const epics = [...backlog.epics.values()].sort((a, b) => (a.folder < b.folder ? -1 : a.folder > b.folder ? 1 : 0));
  for (const epic of epics) {
    const what = `${epic.id ?? epic.folder} Stories table`;
    const table = parseTables(readText(epic.readme)).find((t) => isStoryTable(t, epic.readme));
    if (table === undefined) {
      fail(epic.readme, undefined, 'epic README has no Stories table linking its story files');
      continue;
    }
    const c = {
      story: storyColumn(table, epic.readme, { fallback: true }),
      title: colIndex(table, COL.title),
      points: colIndex(table, COL.points),
      files: colIndex(table, COL.files),
      wave: colIndex(table, COL.wave),
    };
    requireColumns(epic.readme, table, what, c, [['story', 'Story'], ['points', 'Points'], ['files', '']]);
    for (const row of rowsOf(table).body) {
      const story = storyOfRow(epic.readme, row, c.story, what, backlog);
      if (story === null) continue;
      if (story.folder !== epic.folder) {
        fail(epic.readme, row.line, `${what} links ${story.id}, a story of another epic folder (${story.folder})`);
        continue;
      }
      compareStoryCells(epic.readme, row, c, story, what);
    }
    const totals = checkTotalsRow(epic.readme, table, what, [
      { key: 'points', idx: c.points },
      { key: 'files', idx: c.files },
    ]);
    if (totals === null) continue;
    checkCountCell(epic.readme, totals.row, epic.count, what);
    for (const key of ['points', 'files']) {
      const v = totals.values[key];
      if (v != null && epic[key] !== null && v !== epic[key]) {
        fail(epic.readme, totals.row.line, `${what} totals ${LABEL[key]} is ${v}, but the epic's stories sum to ${epic[key]}`);
      }
    }
    if (has(c.wave) && norm(totals.row.cells[c.wave]) !== '' && !sameList(waveSet(totals.row.cells[c.wave]), epic.waves)) {
      fail(epic.readme, totals.row.line, `${what} totals waves "${norm(totals.row.cells[c.wave])}" differ from its stories' Build waves "${epic.waves.join(', ')}"`);
    }
  }
}

/* ------------------------------------------------------------------------------------------------
 * Inventory guard, plan layout, required sections and gap list
 * ---------------------------------------------------------------------------------------------- */

function checkBacklogInventory(backlog) {
  if (backlog.epics.size !== INVENTORY.epics) {
    fail(EPICS_DIR, undefined, `the backlog has ${backlog.epics.size} epic folder READMEs; the binding inventory has ${INVENTORY.epics}`);
  }
  const all = [...backlog.byPath.values()];
  checkInventory(EPICS_DIR, undefined, 'backlog total', totalsOf(all), INVENTORY.total);
  for (const id of WAVES) {
    checkInventory(EPICS_DIR, undefined, `backlog ${id} (stories whose Build wave is ${id})`, totalsOf(all.filter((s) => s.wave === id)), INVENTORY.waves[id]);
  }
}

const SECTION = Object.freeze({
  sizing: ['Sizing basis', /^sizing basis$/i],
  dependencyMap: ['Dependency Map', /^dependency map$/i],
  coverage: ['Feature Checklist coverage', /^feature checklist coverage$/i],
  recommendations: ['Recommendations', /^recommendations$/i],
});

/**
 * Plan layout order: opening summary with the wave summary table, epics-in-scope table, sizing
 * basis, wave sections, Dependency Map, Feature Checklist coverage, Recommendations and, last, the
 * CARD_REQ_TBD gap list. Wave-to-wave order is checked by the wave-section block, so only the
 * first and last wave sections are placed here.
 */
function checkLayout(plan, summaryTable, epicsTable) {
  const find = (re) => plan.h2Heads.find((h) => re.test(norm(h.text)));
  for (const [label, re] of Object.values(SECTION)) {
    if (find(re) === undefined) fail(PLAN, undefined, `the plan has no "## ${label}" section`);
  }
  const last = plan.h2Heads[plan.h2Heads.length - 1];
  if (last === undefined || last.slug !== GAP_LIST_SLUG) {
    fail(PLAN, last?.line, `the last section is "${last ? norm(last.text) : 'none'}"; the CARD_REQ_TBD gap list (#${GAP_LIST_SLUG}) must be the last section`);
  }
  const waveHead = (id) => plan.h2Heads.find((h) => waveOfHeading(h.text) === id);
  const markers = [
    ['the wave summary table', summaryTable?.line],
    ['the epics-in-scope table', epicsTable?.line],
    ['"## Sizing basis"', find(SECTION.sizing[1])?.line],
    [`the ${WAVES[0]} section`, waveHead(WAVES[0])?.line],
    [`the ${WAVES[WAVES.length - 1]} section`, waveHead(WAVES[WAVES.length - 1])?.line],
    ['"## Dependency Map"', find(SECTION.dependencyMap[1])?.line],
    ['"## Feature Checklist coverage"', find(SECTION.coverage[1])?.line],
    ['"## Recommendations"', find(SECTION.recommendations[1])?.line],
    ['the CARD_REQ_TBD gap list', plan.h2Heads.find((h) => h.slug === GAP_LIST_SLUG)?.line],
  ].filter(([, line]) => line !== undefined);
  for (let i = 1; i < markers.length; i++) {
    if (markers[i][1] < markers[i - 1][1]) {
      fail(PLAN, markers[i][1], `${markers[i][0]} comes before ${markers[i - 1][0]}, against the plan layout order`);
    }
  }
}

/** Label → status map of a Customer Details field-gap table, with the per-table status rules. */
function fieldGapMap(path, table, what) {
  const lc = colIndex(table, COL.label);
  const sc = colIndex(table, COL.status);
  const { body } = rowsOf(table);
  if (body.length !== FIELD_GAP_LABELS) {
    fail(path, table.line, `${what} has ${body.length} label rows; the Customer Details section has ${FIELD_GAP_LABELS}`);
  }
  const map = new Map();
  let noEntity = 0;
  let notExposed = 0;
  for (const row of body) {
    const label = norm(row.cells[lc]);
    const status = norm(row.cells[sc]);
    const key = label.toLowerCase();
    if (map.has(key)) {
      fail(path, row.line, `${what} lists label "${label}" more than once`);
      continue;
    }
    map.set(key, { label, status, line: row.line });
    if (status.startsWith(STATUS_NO_ENTITY)) {
      noEntity++;
      if (key === NOT_EXPOSED_LABEL) fail(path, row.line, `${what}: "${label}" must carry "${STATUS_NOT_EXPOSED}"`);
    } else if (status.startsWith(STATUS_NOT_EXPOSED)) {
      notExposed++;
      if (key !== NOT_EXPOSED_LABEL) fail(path, row.line, `${what}: "${label}" carries "${STATUS_NOT_EXPOSED}", which only Customer id carries`);
    } else {
      fail(path, row.line, `${what}: status of "${label}" starts with neither "${STATUS_NO_ENTITY}" nor "${STATUS_NOT_EXPOSED}"`);
    }
  }
  if (noEntity !== NO_ENTITY_COUNT) {
    fail(path, table.line, `${what} has ${noEntity} rows with "${STATUS_NO_ENTITY}"; expected ${NO_ENTITY_COUNT}`);
  }
  if (notExposed !== 1) {
    fail(path, table.line, `${what} has ${notExposed} rows with "${STATUS_NOT_EXPOSED}"; expected 1 (Customer id)`);
  }
  return map;
}

const isFieldGapTable = (t) => has(colIndex(t, COL.label)) && has(colIndex(t, COL.status));

function checkGapList(plan, backlog) {
  const head = plan.h2Heads.find((h) => h.slug === GAP_LIST_SLUG);
  if (head === undefined) {
    fail(PLAN, undefined, `the plan has no CARD_REQ_TBD gap list section (#${GAP_LIST_SLUG})`);
    return;
  }
  const sec = plan.h2Secs.find((s) => s.startLine === head.line);
  const tables = plan.tables.filter((t) => t.line >= sec.startLine && t.line <= sec.endLine);

  const storyTable = tables.find((t) => isStoryTable(t, PLAN));
  if (storyTable === undefined) {
    fail(PLAN, sec.startLine, 'the gap list has no story table linking every story');
  } else {
    const what = 'gap-list story table';
    const c = { story: storyColumn(storyTable, PLAN, { fallback: true }), files: colIndex(storyTable, COL.files) };
    requireColumns(PLAN, storyTable, what, c, [['story', 'Story'], ['files', '']]);
    const { body } = rowsOf(storyTable);
    const seen = new Set();
    for (const row of body) {
      const story = storyOfRow(PLAN, row, c.story, what, backlog);
      if (story === null) continue;
      if (seen.has(story.path)) {
        fail(PLAN, row.line, `${what} lists ${story.id} more than once`);
        continue;
      }
      seen.add(story.path);
      compareStoryCells(PLAN, row, { files: c.files }, story, what);
    }
    for (const story of backlog.byPath.values()) {
      if (!seen.has(story.path)) fail(PLAN, storyTable.line, `${what} omits ${story.id}`);
    }
    if (body.length !== INVENTORY.total.stories) {
      fail(PLAN, storyTable.line, `${what} has ${body.length} story rows; the binding inventory has ${INVENTORY.total.stories}`);
    }
    const totals = checkTotalsRow(PLAN, storyTable, what, [{ key: 'files', idx: c.files }]);
    if (totals) {
      checkCountCell(PLAN, totals.row, body.length, what);
      checkInventory(PLAN, totals.row.line, `${what} total`, totals.values, INVENTORY.total);
    }
  }

  const planFields = tables.find(isFieldGapTable);
  if (planFields === undefined) {
    fail(PLAN, sec.startLine, 'the gap list has no unmapped-field table (columns Label and Status)');
    return;
  }
  const planMap = fieldGapMap(PLAN, planFields, 'unmapped-field table');
  if (!exists(CS01_README)) {
    fail(CS01_README, undefined, 'the CS-01 epic README holding the Customer Details field gaps does not exist');
    return;
  }
  const epicFields = parseTables(readText(CS01_README)).find(isFieldGapTable);
  if (epicFields === undefined) {
    fail(CS01_README, undefined, 'the CS-01 epic README has no Customer Details field-gap table (columns Label and Status)');
    return;
  }
  const epicMap = fieldGapMap(CS01_README, epicFields, 'Customer Details field-gap table');
  for (const [key, ref] of epicMap) {
    const got = planMap.get(key);
    if (got === undefined) {
      fail(PLAN, planFields.line, `unmapped-field table omits "${ref.label}", which ${CS01_README} lists`);
    } else if (got.status !== ref.status) {
      fail(PLAN, got.line, `unmapped-field table status of "${got.label}" is "${got.status}", but ${CS01_README} has "${ref.status}"`);
    }
  }
  for (const [key, got] of planMap) {
    if (!epicMap.has(key)) fail(PLAN, got.line, `unmapped-field table lists "${got.label}", which ${CS01_README} does not`);
  }
}

/* ------------------------------------------------------------------------------------------------
 * Entry point
 * ---------------------------------------------------------------------------------------------- */

function run() {
  if (!exists(PLAN)) {
    fail(PLAN, undefined, `the implementation plan does not exist at ${PLAN}`);
    return;
  }
  const backlog = loadBacklog();
  checkBacklogInventory(backlog);

  const text = readText(PLAN);
  const allHeads = parseHeadings(text);
  const heads = allHeads.filter((h) => !h.quoted);
  const plan = {
    lines: text.split(/\r?\n/),
    allHeads,
    heads,
    h2Heads: heads.filter((h) => h.level === 2),
    h2Secs: sections(text, 2),
    tables: parseTables(text),
    fenced: fencedLineSet(text),
  };

  checkOpening(plan);
  const { summary, table: summaryTable } = checkWaveSummary(plan);
  checkWaveSections(plan, backlog, summary);
  const { table: epicsTable, groupings } = checkEpicsInScope(plan, backlog);
  checkEpicIndex(backlog, groupings);
  checkEpicReadmes(backlog);
  checkLayout(plan, summaryTable, epicsTable);
  checkGapList(plan, backlog);
}

try {
  run();
  report(CHECK, violations);
} catch (err) {
  console.error(`${CHECK}: internal error: ${err?.stack ?? err}`);
  process.exitCode = 2;
}


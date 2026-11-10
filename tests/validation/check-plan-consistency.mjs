// SPDX-License-Identifier: Apache-2.0
/**
 * check-plan-consistency — implementation plan gate for the Card Servicing backlog.
 *
 * Run third by tests/validation/run-all.sh as `node tests/validation/check-plan-consistency.mjs`
 * from any working directory; every path is repository-root-relative through ./lib/markdown.mjs.
 * Takes no arguments: `-h` or `--help` prints the usage to stdout and exits 0 without checking;
 * any other argument prints it to stderr and exits 2. Prints one line per violation and a final
 * count; exits 0 with none, 1 with any, 2 on a usage or internal error. Reads
 * docs/implementation-plan.md, docs/backlog/epics/README.md, the 13 epic READMEs and the 58 story
 * files only — never the CardDemo reference roots.
 *
 * Blocks and the rule gate each one enforces:
 * - Backlog ground truth (story map, per-epic and per-wave sums from story metadata): the values
 *   every later block compares against; "Enforce Implementation Plan, Epic and Story Structure —
 *   Base" (epic and wave values equal their story sums) and "Epic and Story Structure — Multi-File
 *   Variant" (one identifier across title line and filename).
 * - Plan existence and opening summary: "Enforce Implementation Plan, Epic and Story Structure —
 *   Base" (plan at the named path; the opening summary precedes every table: visible summary
 *   prose, never a comment, tag, break or definition, stands before the first table, and no
 *   table stands above a "## Opening summary" heading).
 * - Wave summary table: "Enforce Implementation Plan, Epic and Story Structure — Base" (wave summary
 *   inside the opening summary, one row per wave, closed by a totals row equal to the sum of waves).
 * - Wave sections: both rules — Base (one section per wave in order, story table rows with
 *   position, linked story, title, linked epic, points and files touched, positions 1..n with
 *   same-wave prerequisites first, Build wave equal to the plan wave, totals line equal to the table
 *   and the wave summary) and Multi-File Variant (every story file in exactly one wave story table,
 *   story links resolving to existing story files and epic links to the story's own epic folder
 *   README, each story link's text equal to the story identifier, each epic link's text displaying
 *   the epic identifier and no other, each anchor a heading slug of the target, one identifier
 *   format across plan, title line and filename).
 * - Epics-in-scope table, epic index and epic README Stories tables: Base (epic values equal their
 *   story sums; files touched per story row equals its metadata) and Multi-File Variant (epic links
 *   resolve to the epic folder's README and display that epic's identifier, as the folder label in
 *   the epic index; an anchor names a heading of the linked file).
 * - Plan links: both rules (every story link and epic link in the plan resolves). Every other plan
 *   story or epic link, Feature Checklist coverage and prose links included, resolves to an
 *   existing story file, epic folder README or the epic index; its anchor names a heading of that
 *   file, and any identifier its text displays is that file's own. A link counts as a story or epic
 *   link by its text or path, not by a correct destination: it resolves into docs/backlog/epics/,
 *   its text displays a story or epic identifier, a path segment starts with one, or its path ends
 *   in an epics-folder layout form.
 * - Files-touched rule: Base (every table whose rows are waves, epics or stories carries a files
 *   touched column summed in its totals row or totals line, with each row's value equal to its
 *   story's metadata or its epic's or wave's story sum). Applied inside each table block, then to
 *   every other plan table whose rows are waves, epics or stories; the Dependency Map, Feature
 *   Checklist coverage and field-gap tables are exempt, and a second wave summary, epics-in-scope,
 *   gap-list story or unmapped-field table is reported where it stands. A second epic index table
 *   or epic README Stories table is reported where it stands and held to the same rule.
 * - Inventory guard: the binding story inventory of the Agent Action Plan.
 * - Plan layout, required sections and gap list: the Agent Action Plan's plan layout (section
 *   order, Recommendations, Feature Checklist coverage, the CARD_REQ_TBD gap list last — no level-1
 *   or level-2 heading after it — with all stories and the Customer Details unmapped-field table
 *   equal to the CS-01 epic README's field-gap table).
 * - Name and number sync across plan tables, the epic index and epic READMEs (story titles, epic
 *   names and groupings, Build waves, wave-table Depends on cells): the Agent Action Plan's rule
 *   that every table carries the inventory's values. Only this check compares the titles, names,
 *   groupings and Build waves in these tables with metadata; check-dependency-order also compares
 *   each wave-table Depends on cell with metadata and with the Dependency Map.
 *
 * This check never re-validates metadata form (check-backlog-structure owns that); a metadata
 * value it needs for a comparison or a sum and cannot parse is reported here.
 */
import path from 'node:path';
import {
  readText,
  exists,
  listFiles,
  parseHeadings,
  sections,
  sectionByHeading,
  parseTables,
  isTotalsRow,
  surplusCellRows,
  resolveLink,
  linkInCell,
  parseLinks,
  parseMetadata,
  intStrict,
  fencedBlocks,
  plainText,
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
const COUNT_CELL_RE = /^(0|[1-9]\d*) (stories|story|epics|epic)$/;

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

// Tables whose row widths have been checked, so a row is reported once however many blocks read it.
const widthChecked = new WeakSet();

/**
 * Reports, once per table of the file at `path`, every row with more cells than its header,
 * totals rows included, under `what`: a renderer drops those cells, so no reader sees them.
 */
function checkRowWidths(path, table, what) {
  if (widthChecked.has(table)) return;
  widthChecked.add(table);
  for (const { line, message } of surplusCellRows(table, what)) fail(path, line, message);
}

/** Splits a table into body rows and totals rows, each with its document line and row index. */
function splitRows(table) {
  const body = [];
  const totals = [];
  table.rows.forEach((cells, index) => {
    (isTotalsRow(cells) ? totals : body).push({ cells, line: table.rowLines[index], index });
  });
  return { body, totals };
}

/** The rows of a table this check reads, after its row widths are checked. */
function rowsOf(path, table, what) {
  checkRowWidths(path, table, what);
  return splitRows(table);
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
 * Story and epic links: destinations, heading anchors and displayed identifiers
 * ---------------------------------------------------------------------------------------------- */

// A story identifier CS-NN-NNN, or an epic identifier CS-NN not followed by a story number.
const LINK_ID_G = /\bCS-\d{2}(?:-\d{3})?(?!\d)/g;

// Links a table-row reader has validated, keyed by file, line, text and target, so the plan link
// pass reports each link once.
const checkedLinks = new Set();
const linkKey = (fromRel, line, link) => `${fromRel}\u0000${line}\u0000${link.text}\u0000${link.target}`;

const slugCache = new Map(); // Markdown file -> Set of its heading slugs

/**
 * The anchor defect of a link whose target is an existing backlog file, or null: an anchor must
 * equal one of that file's heading slugs, the lower-case hyphenated heading text.
 */
function anchorDefect(link, target) {
  if (!target.anchor) return null;
  if (!slugCache.has(target.path)) {
    slugCache.set(target.path, new Set(parseHeadings(readText(target.path)).map((h) => h.slug)));
  }
  if (slugCache.get(target.path).has(target.anchor)) return null;
  return `link ${link.target}: no heading in ${target.path} has the slug "${target.anchor}"`;
}

/**
 * Identifiers a link's text displays (story CS-NN-NNN, epic CS-NN) other than `id`, the identifier
 * of the file it links. Text equal to `folder`, with or without a trailing "/", is that epic's
 * folder label; descriptive text without an identifier gives [].
 */
function foreignIds(text, id, folder = null) {
  const t = norm(text);
  if (folder !== null && t.replace(/\/$/, '') === folder) return [];
  return [...new Set(t.match(LINK_ID_G) ?? [])].filter((x) => x !== id);
}

// A letter, digit or underscore: after an identifier, it makes the identifier part of a longer token.
const ID_TAIL_RE = /[\p{L}\p{N}_]/u;

/** True when a link's text displays `id` as a whole identifier token: "CS-05" or "CS-05 Name", never "CS-05x". */
function displaysId(text, id) {
  const t = norm(text);
  for (const m of t.matchAll(LINK_ID_G)) {
    if (m[0] === id && !ID_TAIL_RE.test(t.charAt(m.index + m[0].length))) return true;
  }
  return false;
}

/**
 * The defect of a plan Epic cell's link text, worded to follow `link text "<text>" `, or null.
 * The text displays the linked epic's identifier as a whole token and no other story or epic
 * identifier, alone ("CS-05") or with the epic name ("CS-05 Balance Summary Endpoint"); `whose`
 * names the epic folder for the message.
 */
function epicTextDefect(text, id, whose) {
  if (id === null) return null;
  const foreign = foreignIds(text, id);
  const own = displaysId(text, id);
  if (foreign.length > 0) {
    return `displays ${foreign.join(', ')} ${own ? 'besides' : 'instead of'} ${id}, the identifier of ${whose}`;
  }
  if (!own) return `does not display ${id}, the identifier of ${whose}; the epic cell must display it`;
  return null;
}

// A path segment that starts with a story or epic identifier: an epic folder "CS-NN-<slug>" or a
// story file "CS-NN-NNN-<slug>.md", wherever the path places it.
const ID_SEGMENT_RE = /^CS-\d{2}(?!\d)/;
// A path ending in a layout form of an epics folder, whichever directory holds it: the folder,
// its README.md, an epic folder README.md or a story file under an epic folder's stories/.
const EPICS_LAYOUT_RE = /(?:^|\/)epics(?:\/README\.md|\/[^/]+\/README\.md|\/[^/]+\/stories\/[^/]+\.md)?$/i;

/**
 * True when a plan link is a story or epic link, whether or not its destination is right: its
 * resolved path lies under docs/backlog/epics/, its text displays a story or epic identifier, a
 * segment of its resolved path starts with one, or that path ends in an epics-folder layout form.
 * Pure: a leading-slash, scheme or escaping target is classified by its text and path string,
 * never by the filesystem.
 */
function isBacklogLink(link, target) {
  if (target.path === EPICS_DIR || target.path.startsWith(`${EPICS_DIR}/`)) return true;
  if ((norm(link.text).match(LINK_ID_G) ?? []).length > 0) return true;
  return target.path.split('/').some((segment) => ID_SEGMENT_RE.test(segment)) || EPICS_LAYOUT_RE.test(target.path);
}

/**
 * Every plan story or epic link (isBacklogLink) that no table-row reader validated, Feature
 * Checklist coverage and prose links included: it resolves to an existing story file, epic folder
 * README or the epic index, its anchor names a heading of that file, and every identifier its text
 * displays is that file's own. A misspelled, leading-slash, scheme or escaping destination is
 * reported as not resolving. Images are not story or epic links.
 */
function checkPlanLinks(plan, backlog) {
  const listed = new Set(listFiles(EPICS_DIR, { exts: ['.md'] }));
  for (const link of plan.links) {
    if (link.image) continue;
    const target = resolveLink(PLAN, link.target);
    if (!isBacklogLink(link, target)) continue;
    if (checkedLinks.has(linkKey(PLAN, link.line, link))) continue;
    const story = backlog.byPath.get(target.path);
    const m = EPIC_README_RE.exec(target.path);
    const epic = story === undefined && m ? backlog.epics.get(m[1]) : undefined;
    let dest;
    let foreign;
    if (story !== undefined) {
      dest = `the story file of ${story.id}`;
      foreign = foreignIds(link.text, story.id);
    } else if (epic !== undefined) {
      dest = `the epic folder README of ${epic.id ?? epic.folder}`;
      foreign = foreignIds(link.text, epic.id, epic.folder);
    } else if (target.path === EPIC_INDEX && listed.has(EPIC_INDEX)) {
      dest = 'the epic index';
      foreign = foreignIds(link.text, null);
    } else {
      fail(PLAN, link.line, `link ${link.target} does not resolve to an existing story file, epic folder README or the epic index`);
      continue;
    }
    if (foreign.length > 0) {
      fail(PLAN, link.line, `link "${norm(link.text)}" (${link.target}) displays ${foreign.join(', ')}, but it links ${dest}`);
    }
    const defect = anchorDefect(link, target);
    if (defect !== null) fail(PLAN, link.line, defect);
  }
}

/* ------------------------------------------------------------------------------------------------
 * Shared row comparisons
 * ---------------------------------------------------------------------------------------------- */

const has = (idx) => Number.isInteger(idx) && idx >= 0;

/**
 * The story a table row names through its story column. A row whose story ID is not a link, or
 * whose link does not resolve to an existing story file, is reported; the story its text names is
 * still returned so the exactly-once count stays exact. Link text must be the story identifier,
 * and an anchor must name a heading of the story file.
 */
function storyOfRow(fromRel, row, col, what, backlog) {
  if (!has(col)) return null;
  const cell = row.cells[col];
  const link = linkInCell(cell);
  if (link === null) {
    fail(fromRel, row.line, `${what} row "${norm(cell)}": the story ID is not a link to its story file`);
    return backlog.byId.get(norm(cell)) ?? null;
  }
  checkedLinks.add(linkKey(fromRel, row.line, link));
  const target = resolveLink(fromRel, link.target);
  const story = backlog.byPath.get(target.path);
  if (story === undefined) {
    fail(fromRel, row.line, `${what} row "${norm(link.text)}": link ${link.target} does not resolve to an existing story file`);
    return backlog.byId.get(norm(link.text)) ?? null;
  }
  if (norm(link.text) !== story.id) {
    fail(fromRel, row.line, `${what} row: link text "${norm(link.text)}" differs from ${story.id}, the identifier of its title line and filename`);
  }
  const defect = anchorDefect(link, target);
  if (defect !== null) fail(fromRel, row.line, `${what} row ${story.id}: ${defect}`);
  return story;
}

/**
 * The epic a table row links to through its epic column; a missing or stray link, an anchor that
 * names no heading of the README and displayed text naming another epic are reported. In the plan
 * the link text displays the epic identifier as a whole token and no other identifier, alone or
 * with the epic name (epicTextDefect); in the epic index it may also be the folder label, with or
 * without its trailing "/".
 */
function epicOfRow(fromRel, row, col, what, backlog) {
  if (!has(col)) return null;
  const link = linkInCell(row.cells[col]);
  if (link === null) {
    fail(fromRel, row.line, `${what} row "${norm(row.cells[col])}": the epic is not a link to its epic README`);
    return null;
  }
  checkedLinks.add(linkKey(fromRel, row.line, link));
  const target = resolveLink(fromRel, link.target);
  const m = EPIC_README_RE.exec(target.path);
  const epic = m ? backlog.epics.get(m[1]) : undefined;
  if (epic === undefined) {
    fail(fromRel, row.line, `${what} row "${norm(link.text)}": link ${link.target} does not resolve to an existing epic folder README`);
    return null;
  }
  const label = epic.id ?? epic.folder;
  if (fromRel === PLAN) {
    const textDefect = epicTextDefect(link.text, epic.id, 'the epic folder it links');
    if (textDefect !== null) fail(fromRel, row.line, `${what} row: link text "${norm(link.text)}" ${textDefect}`);
  } else {
    const foreign = foreignIds(link.text, epic.id, epic.folder);
    if (foreign.length > 0) {
      fail(fromRel, row.line, `${what} row: link text "${norm(link.text)}" names ${foreign.join(', ')}, but the link resolves to the epic folder README of ${label}`);
    }
  }
  const defect = anchorDefect(link, target);
  if (defect !== null) fail(fromRel, row.line, `${what} row ${label}: ${defect}`);
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
  // A wave table's Depends on column is a plan dependency as well, so it is held equal to metadata
  // here; check-dependency-order also compares the same cell with metadata and the Dependency Map.
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
  const { body, totals } = rowsOf(path, table, what);
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

/**
 * The count cell of a totals row, the cell right after its Total label, must read "<n> epics" or
 * "<n> stories" (`noun`; the singular only for 1) and state the expected count. A missing cell,
 * any other text and a wrong number are each reported with the cell's text.
 */
function checkCountCell(path, totalsRow, expected, what, noun) {
  const text = norm(totalsRow.cells[1]);
  const form = `"<n> ${noun}"`;
  if (text === '') {
    fail(path, totalsRow.line, `${what} totals row has no count cell ${form} after its Total label; the count is ${expected}`);
    return;
  }
  const m = COUNT_CELL_RE.exec(text);
  const singular = noun === 'stories' ? 'story' : 'epic';
  if (m === null || !(m[2] === noun || (m[2] === singular && m[1] === '1'))) {
    fail(path, totalsRow.line, `${what} totals row count cell "${text}" does not read ${form}; the count is ${expected}`);
    return;
  }
  if (Number(m[1]) !== expected) {
    fail(path, totalsRow.line, `${what} totals row states "${text}", but the count is ${expected}`);
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

// Opening-summary prose is text a reader sees as prose. These lines are not: thematic breaks, link
// reference and footnote definitions, indented code, and paragraphs a setext underline turns into
// headings. A line starting a list item, blockquote or HTML block ends a paragraph before any
// setext underline.
const THEMATIC_BREAK_RE = /^ {0,3}(?:(?:-[ \t]*){3,}|(?:\*[ \t]*){3,}|(?:_[ \t]*){3,})$/;
const SETEXT_UNDERLINE_RE = /^ {0,3}(?:=+|-+)[ \t]*$/;
const INDENTED_CODE_RE = /^(?: {4}| {0,3}\t)/;
const LINK_DEFINITION_RE =
  /^ {0,3}(?:\[\^[^\]]+\]:|\[(?:\\.|[^\\\]])+\]:[ \t]*(?:<[^<>]*>|\S+)?(?:[ \t]+(?:"[^"]*"|'[^']*'|\([^()]*\)))?[ \t]*$)/;
const BLOCK_START_RE = /^ {0,3}(?:[-+*](?:[ \t]|$)|\d{1,9}[.)](?:[ \t]|$)|>|<)/;
const AUTOLINK_RE = /<((?:[A-Za-z][A-Za-z0-9+.-]{1,31}:|[^\s<>@]+@)[^\s<>]*)>/g;
const ENTITY_RE = /&(?:#\d{1,7}|#[xX][0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});/g;
const LETTER_OR_DIGIT_RE = /[\p{L}\p{N}]/u;

/**
 * Visible text of every plan line, `text[n - 1]` holding line n: HTML comments removed, a
 * comment left open hiding every line up to its closing `-->`, fenced lines empty. `hidden`
 * holds the lines a comment covers from their start, so a table opening there renders nothing.
 */
function visibleLines(plan) {
  const text = [];
  const hidden = new Set();
  let open = false;
  plan.lines.forEach((line, i) => {
    if (!open && plan.fenced.has(i + 1)) {
      text.push('');
      return;
    }
    if (open) hidden.add(i + 1);
    let rest = line;
    let kept = '';
    for (;;) {
      const at = rest.indexOf(open ? '-->' : '<!--');
      if (at < 0) {
        if (!open) kept += rest;
        break;
      }
      if (!open) kept += rest.slice(0, at);
      rest = rest.slice(at + (open ? 3 : 4));
      open = !open;
    }
    text.push(kept);
  });
  return { text, hidden };
}

/** True when a line's comment-free text shows a letter or digit once HTML tags, entities and inline markup are stripped. */
function isVisibleProse(s) {
  const t = s.trim();
  if (t === '' || t.startsWith('|') || THEMATIC_BREAK_RE.test(s) || LINK_DEFINITION_RE.test(s)) return false;
  return LETTER_OR_DIGIT_RE.test(plainText(t.replace(AUTOLINK_RE, '$1')).replace(ENTITY_RE, ''));
}

/**
 * Line of the setext underline that turns the paragraph opening at line n into a heading, or 0
 * when line n opens another block (its raw text, so a leading comment counts) or the paragraph
 * ends first (blank or fenced line, heading, thematic break, new block, or line `end`).
 */
function setextUnderline(plan, text, headingLines, n, end) {
  if (BLOCK_START_RE.test(plan.lines[n - 1])) return 0;
  for (let k = n + 1; k < end; k++) {
    const s = text[k - 1];
    if (plan.fenced.has(k) || headingLines.has(k) || s.trim() === '') return 0;
    if (SETEXT_UNDERLINE_RE.test(s)) return k;
    if (THEMATIC_BREAK_RE.test(s) || BLOCK_START_RE.test(s)) return 0;
  }
  return 0;
}

/**
 * True when a visible prose line stands strictly between lines `from` and `end`. An indented
 * line that opens a block (after the start, a blank, fenced or heading line, or more indented
 * code) is an indented code block, not prose.
 */
function hasSummaryProse(plan, text, headingLines, from, end) {
  let blockStart = true;
  for (let n = from + 1; n < end; n++) {
    const s = text[n - 1];
    if (plan.fenced.has(n) || headingLines.has(n) || s.trim() === '') {
      blockStart = true;
      continue;
    }
    if (blockStart && INDENTED_CODE_RE.test(s)) continue;
    blockStart = false;
    if (!isVisibleProse(s)) continue;
    const underline = setextUnderline(plan, text, headingLines, n, end);
    if (underline === 0) return true;
    n = underline;
    blockStart = true;
  }
  return false;
}

/**
 * The plan's first non-quoted `##` heading when it is named "Opening summary" (the reading
 * openingEnd applies), else undefined: the summary then starts at that heading, not the title.
 */
function namedOpening(h2Heads) {
  const [first] = h2Heads;
  return first && /^opening summary$/i.test(norm(first.text)) ? first : undefined;
}

/**
 * The title opens the plan, and visible summary prose precedes every rendered table. With a
 * first `##` heading named "Opening summary", every table above that heading precedes the
 * summary, and prose must stand between the heading and the first table after it; otherwise it
 * must stand between the title and the first table. Either way the prose lies inside the opening
 * summary (openingEnd) and is visible text: comments, tags, breaks and definitions do not count.
 */
function checkOpening(plan) {
  const h1 = plan.heads[0];
  if (h1 === undefined || h1.level !== 1) fail(PLAN, h1?.line, 'the plan does not open with a level-1 title');
  const visible = visibleLines(plan);
  const tables = plan.tables.filter((t) => !visible.hidden.has(t.line));
  const first = tables[0];
  if (first === undefined) return;
  const start = h1 ? h1.line : 0;
  if (first.line < start) {
    fail(PLAN, first.line, 'a table precedes the plan title and its opening summary');
    return;
  }
  const named = namedOpening(plan.h2Heads);
  const from = named ? named.line : start;
  for (const t of tables) {
    if (t.line < from) {
      fail(PLAN, t.line, `a table precedes the opening summary: it stands before the "Opening summary" heading at line ${from}`);
    }
  }
  const next = tables.find((t) => t.line > from);
  if (next === undefined) return;
  const end = Math.min(next.line, openingEnd(plan.h2Heads));
  const headingLines = new Set(plan.allHeads.map((h) => h.line));
  if (hasSummaryProse(plan, visible.text, headingLines, from, end)) return;
  const after = named ? 'the "Opening summary" heading' : 'the title';
  const before =
    end < next.line ? `the end of the opening summary at line ${end}` : named ? 'the first table after it' : 'the first table';
  fail(PLAN, next.line, `a table precedes the opening summary: no visible summary prose stands between ${after} and ${before}`);
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
  const [table, ...extra] = plan.tables.filter(
    (t) => has(colIndex(t, COL.wave)) && has(colIndex(t, COL.unlocks)) && has(colIndex(t, COL.ciGate)),
  );
  if (table === undefined) {
    fail(PLAN, undefined, `no ${what} (columns Wave, Unlocks and CI gate stage added) in the opening summary`);
    return { summary, table: null };
  }
  for (const t of extra) fail(PLAN, t.line, `the plan has more than one ${what}; the first is at line ${table.line}`);
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
  for (const row of rowsOf(PLAN, table, what).body) {
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

  const { body } = rowsOf(PLAN, table, what);
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
        checkedLinks.add(linkKey(PLAN, row.line, link));
        const target = resolveLink(PLAN, link.target);
        const epic = backlog.epics.get(story.folder);
        if (target.path !== expected) {
          fail(PLAN, row.line, `${what} ${story.id}: epic link ${link.target} resolves to ${target.path}, not the story's epic README ${expected}`);
        } else if (epic === undefined) {
          fail(PLAN, row.line, `${what} ${story.id}: epic README ${expected} does not exist`);
        } else {
          const textDefect = epicTextDefect(link.text, epic.id, "the story's epic folder");
          if (textDefect !== null) fail(PLAN, row.line, `${what} ${story.id}: epic link text "${norm(link.text)}" ${textDefect}`);
          const defect = anchorDefect(link, target);
          if (defect !== null) fail(PLAN, row.line, `${what} ${story.id}: epic ${defect}`);
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
  if (rowsOf(PLAN, table, what).totals.length > 0) {
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
  const { body } = rowsOf(path, table, what);
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
    checkCountCell(path, totals.row, body.length, what, 'epics');
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
  const [table, ...extra] = plan.tables.filter(
    (t) => has(colIndex(t, COL.epic)) && has(colIndex(t, COL.grouping)) && has(colIndex(t, COL.wave)),
  );
  if (table === undefined) {
    fail(PLAN, undefined, `no ${what} (columns Epic, Grouping, Estimated files touched and Waves)`);
    return { table: null, groupings: new Map() };
  }
  for (const t of extra) fail(PLAN, t.line, `the plan has more than one ${what}; the first is at line ${table.line}`);
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
  if (!isRegularFile(EPIC_INDEX)) {
    fail(EPIC_INDEX, undefined, 'the epic index is not a regular file');
    return;
  }
  const [table, ...extra] = parseTables(readText(EPIC_INDEX)).filter((t) => has(epicColumn(t, EPIC_INDEX)));
  if (table === undefined) {
    fail(EPIC_INDEX, undefined, `no ${what} linking every epic folder README`);
    return;
  }
  for (const t of extra) {
    fail(EPIC_INDEX, t.line, `the epic index has more than one ${what}; the first is at line ${table.line}`);
    const cols = epicColumns(t, EPIC_INDEX);
    const extraWhat = `extra ${what}`;
    checkExtraTable(EPIC_INDEX, t, extraWhat, cols, 'epics', (row) => {
      const epic = epicOfRow(EPIC_INDEX, row, cols.epic, extraWhat, backlog);
      if (epic !== null) compareEpicCells(EPIC_INDEX, row, cols, epic, extraWhat);
    });
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


/**
 * Files-touched rule over an epic index table or an epic README Stories table after the first,
 * which its block reports where it stands: the table carries an estimated files touched column,
 * `checkRow` holds each body row to its epic's story sums or its story's metadata, and the table
 * closes with a totals row summing its story count (epic rows only), points and files touched
 * columns, any count cell right after the Total label stating its row count.
 */
function checkExtraTable(path, table, what, c, noun, checkRow) {
  requireColumns(path, table, what, c, [['files', '']]);
  const { body } = rowsOf(path, table, what);
  for (const row of body) checkRow(row);
  const specs = [
    { key: 'stories', idx: noun === 'epics' ? c.stories : -1 },
    { key: 'points', idx: c.points },
    { key: 'files', idx: c.files },
  ];
  const totals = checkTotalsRow(path, table, what, specs);
  if (totals && !specs.some((s) => s.idx === 1) && norm(totals.row.cells[1]) !== '') {
    checkCountCell(path, totals.row, body.length, what, noun);
  }
}

function checkEpicReadmes(backlog) {
  const epics = [...backlog.epics.values()].sort((a, b) => (a.folder < b.folder ? -1 : a.folder > b.folder ? 1 : 0));
  for (const epic of epics) {
    const what = `${epic.id ?? epic.folder} Stories table`;
    const [table, ...extra] = parseTables(readText(epic.readme)).filter((t) => isStoryTable(t, epic.readme));
    if (table === undefined) {
      fail(epic.readme, undefined, 'epic README has no Stories table linking its story files');
      continue;
    }
    const columnsOf = (t) => ({
      story: storyColumn(t, epic.readme, { fallback: true }),
      title: colIndex(t, COL.title),
      points: colIndex(t, COL.points),
      files: colIndex(t, COL.files),
      wave: colIndex(t, COL.wave),
    });
    // Each row names a story of this epic folder whose cells equal its metadata.
    const checkRow = (row, cols, label) => {
      const story = storyOfRow(epic.readme, row, cols.story, label, backlog);
      if (story === null) return;
      if (story.folder !== epic.folder) {
        fail(epic.readme, row.line, `${label} links ${story.id}, a story of another epic folder (${story.folder})`);
        return;
      }
      compareStoryCells(epic.readme, row, cols, story, label);
    };
    for (const t of extra) {
      fail(epic.readme, t.line, `the epic README has more than one Stories table; the first is at line ${table.line}`);
      const cols = columnsOf(t);
      checkExtraTable(epic.readme, t, `extra ${what}`, cols, 'stories', (row) => checkRow(row, cols, `extra ${what}`));
    }
    const c = columnsOf(table);
    requireColumns(epic.readme, table, what, c, [['story', 'Story'], ['points', 'Points'], ['files', '']]);
    for (const row of rowsOf(epic.readme, table, what).body) checkRow(row, c, what);
    const totals = checkTotalsRow(epic.readme, table, what, [
      { key: 'points', idx: c.points },
      { key: 'files', idx: c.files },
    ]);
    if (totals === null) continue;
    checkCountCell(epic.readme, totals.row, epic.count, what, 'stories');
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
 * CARD_REQ_TBD gap list. Last means no rendered, non-quoted level-1 or level-2 heading, ATX or
 * setext, follows the rendered gap-list heading; its level-3 and deeper subsections (story gaps,
 * unmapped-field table) stay inside it. A heading an HTML comment block hides renders no section,
 * so it neither ends the plan nor stands for the gap list. Wave-to-wave order is checked by the
 * wave-section block, so only the first and last wave sections are placed here.
 */
function checkLayout(plan, summaryTable, epicsTable) {
  const find = (re) => plan.h2Heads.find((h) => re.test(norm(h.text)));
  for (const [label, re] of Object.values(SECTION)) {
    if (find(re) === undefined) fail(PLAN, undefined, `the plan has no "## ${label}" section`);
  }
  // plan.heads ignores HTML comments, so a heading line visibleLines hides is checked against the
  // comment's block. An unfenced line opening with `<!--` (up to three spaces in) starts an HTML
  // block that ends at the first line holding `-->`, and a heading inside it renders nothing. A
  // comment opened after other text on its line is inline HTML instead: the heading line
  // interrupts its paragraph and renders, as it does after `<!-- a --> <!--` or `<!-->`, whose
  // block ends on that same line.
  const visible = visibleLines(plan);
  const commentHidden = (n) => {
    if (!visible.hidden.has(n)) return false;
    for (let k = n - 1; k >= 1; k--) {
      const line = plan.lines[k - 1];
      if (line.includes('-->')) return false;
      if (!plan.fenced.has(k) && /^ {0,3}<!--/.test(line)) return true;
    }
    return false;
  };
  const renderedH2 = plan.h2Heads.filter((h) => !commentHidden(h.line));
  const last = renderedH2[renderedH2.length - 1];
  if (last === undefined || last.slug !== GAP_LIST_SLUG) {
    fail(PLAN, last?.line, `the last section is "${last ? norm(last.text) : 'none'}"; the CARD_REQ_TBD gap list (#${GAP_LIST_SLUG}) must be the last section`);
  }
  // A level-1 heading ends the gap section without becoming an h2 head, so terminality also
  // scans every rendered heading after the gap-list heading; the last h2, reported above, is not
  // repeated.
  const gapHead = renderedH2.find((h) => h.slug === GAP_LIST_SLUG);
  if (gapHead !== undefined) {
    for (const h of plan.heads) {
      if (h.line <= gapHead.line || h.level > 2 || h.line === last.line || commentHidden(h.line)) continue;
      fail(PLAN, h.line, `"${'#'.repeat(h.level)} ${norm(h.text)}" follows the CARD_REQ_TBD gap list (#${GAP_LIST_SLUG}); the gap list must be the last section, with only level-3 or deeper subsections after its heading`);
    }
    // Setext headings are not in plan.heads, so the blocks after the gap-list heading are walked
    // and each paragraph a `=` (level 1) or `-` (level 2) underline closes is reported at its first
    // line. Only a line opening a block can open that paragraph: a line continuing a paragraph,
    // list item, blockquote, table or HTML block does not, nor does indented code, a link
    // definition or a thematic break (a `---` after a blank line). A table line before the
    // underline interrupts the paragraph, so the underline then belongs to the table. Lines an
    // HTML comment block hides are skipped; a line hidden only by inline HTML (a comment opened
    // after text on an earlier line) is read as written, since block structure ignores inline HTML.
    const blockText = visible.text.map((s, i) => (visible.hidden.has(i + 1) && !commentHidden(i + 1) ? plan.lines[i] : s));
    const headingLines = new Set(plan.allHeads.map((h) => h.line));
    const tableLines = new Set(plan.tables.flatMap((t) => [t.line, t.line + 1, ...t.rowLines]));
    const opened = (line) => (/^ {0,3}</.test(line) ? 'html' : 'container');
    const end = plan.lines.length + 1;
    let block = 'none';
    for (let n = gapHead.line + 1; n < end; n++) {
      const s = blockText[n - 1];
      if (plan.fenced.has(n) || headingLines.has(n) || commentHidden(n) || s.trim() === '') {
        block = 'none';
        continue;
      }
      if (block === 'html') continue;
      if (THEMATIC_BREAK_RE.test(s)) {
        block = 'none';
        continue;
      }
      if (tableLines.has(n)) {
        block = 'container';
        continue;
      }
      if (block === 'paragraph' && BLOCK_START_RE.test(s)) block = opened(s);
      if (block !== 'none' || INDENTED_CODE_RE.test(s) || LINK_DEFINITION_RE.test(s)) continue;
      let underline = setextUnderline(plan, blockText, headingLines, n, end);
      for (let k = n + 1; underline > 0 && k < underline; k++) {
        if (tableLines.has(k)) underline = 0;
      }
      if (underline === 0) {
        block = BLOCK_START_RE.test(plan.lines[n - 1]) ? opened(plan.lines[n - 1]) : 'paragraph';
        continue;
      }
      const level = /^ {0,3}=/.test(blockText[underline - 1]) ? 1 : 2;
      const heading = norm(blockText.slice(n - 1, underline - 1).join(' '));
      fail(PLAN, n, `"${heading}" (setext level-${level} heading) follows the CARD_REQ_TBD gap list (#${GAP_LIST_SLUG}); the gap list must be the last section, with only level-3 or deeper subsections after its heading`);
      n = underline;
      block = 'none';
    }
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

/**
 * Files-touched rule over every plan table no block above validates. A table whose rows are
 * stories (a story-link column, a Story or Story ID column, or a column of story identifiers,
 * linked or plain, first or headed Story, Story ID or ID), epics (epic README links under an
 * Epic or Folder column, or a column of epic identifiers that is first or headed Epic, Folder or
 * ID) or waves (a Wave column of wave identifiers) carries an estimated files touched column; each
 * row's values equal the story's metadata, the epic's story sums or the sums of the stories whose
 * Build wave it is; and the table closes with that column summed in its totals row, or else in a
 * totals line straight after it. The blocks above own the first wave summary, epics-in-scope,
 * per-wave and gap-list story tables; a second table in one of those roles is reported by its
 * block and checked here as well. Exempt, as the plan layout classifies them: the Dependency Map
 * tables (edge tables, not story tables), the Feature Checklist coverage table (its rows are
 * checklist items) and Label and Status field-gap tables.
 */
function checkRepresentedTables(plan, backlog, summaryTable, epicsTable) {
  const within = (t, sec) => t.line >= sec.startLine && t.line <= sec.endLine;
  const firstStoryTable = (sec) => plan.tables.find((t) => within(t, sec) && isStoryTable(t, PLAN));
  const owned = new Set([summaryTable, epicsTable]);
  const waveIds = new Set();
  for (const sec of plan.h2Secs) {
    const id = waveOfHeading(sec.heading);
    if (!WAVES.includes(id) || waveIds.has(id)) continue;
    waveIds.add(id);
    owned.add(firstStoryTable(sec));
  }
  const gapHead = plan.h2Heads.find((h) => h.slug === GAP_LIST_SLUG);
  const gapSec = gapHead ? plan.h2Secs.find((s) => s.startLine === gapHead.line) : undefined;
  if (gapSec !== undefined) owned.add(firstStoryTable(gapSec));
  const depMap = plan.h2Secs.filter((s) => SECTION.dependencyMap[1].test(norm(s.heading)));
  const exempt = (t) =>
    depMap.some((s) => within(t, s)) || /^feature checklist\b/i.test(norm(t.header[0])) || isFieldGapTable(t);
  const epicsById = new Map([...backlog.epics.values()].map((e) => [e.id, e]));
  const stories = [...backlog.byPath.values()];

  for (const table of plan.tables) {
    if (owned.has(table) || exempt(table)) continue;
    const { body, totals } = splitRows(table);
    const head = (c) => norm(table.header[c]);
    const every = (c, re) => body.length > 0 && body.every((r) => re.test(norm(r.cells[c])));
    const linked = epicColumn(table, PLAN);
    const epicCol =
      has(linked) && COL.epicHeader.test(head(linked))
        ? linked
        : table.header.findIndex(
            (_, c) => (c === 0 || COL.epicHeader.test(head(c)) || COL.id.test(head(c))) && every(c, /^CS-\d{2}$/),
          );
    // Story-identifier key column: first or headed Story, Story ID or ID, every body cell a story
    // ID, linked or plain; a Story or Story ID header is preferred, as for linked story columns.
    const keyCols = table.header
      .map((_, c) => c)
      .filter(
        (c) => (c === 0 || COL.storyHeader.test(head(c)) || COL.id.test(head(c))) && every(c, /^CS-\d{2}-\d{3}$/),
      );
    const storyKey = keyCols.find((c) => COL.storyHeader.test(head(c))) ?? keyCols[0] ?? -1;
    const waveCol = colIndex(table, COL.waveExact, COL.wave);
    let kind;
    if (isStoryTable(table, PLAN) || has(storyKey)) kind = 'story';
    else if (has(epicCol)) kind = 'epic';
    else if (has(waveCol) && every(waveCol, /^W\d+\b/)) kind = 'wave';
    else continue;

    const heading = norm(table.headingPath[table.headingPath.length - 1] ?? '');
    const what = heading === '' ? `${kind} table` : `${kind} table under "${heading}"`;
    const files = colIndex(table, COL.files);
    const points = colIndex(table, COL.points);
    const count = colIndex(table, COL.stories);
    requireColumns(PLAN, table, what, { files }, [['files', '']]);
    checkRowWidths(PLAN, table, what);

    if (kind === 'story') {
      // A linked story column first, then the story-identifier key column, then a Story header.
      const linkedCol = storyColumn(table, PLAN);
      const col = has(linkedCol) ? linkedCol : has(storyKey) ? storyKey : storyColumn(table, PLAN, { fallback: true });
      const c = {
        title: colIndex(table, COL.title),
        points,
        files,
        wave: colIndex(table, COL.wave),
        depends: colIndex(table, COL.depends),
      };
      for (const row of body) {
        const story = storyOfRow(PLAN, row, col, what, backlog);
        if (story !== null) compareStoryCells(PLAN, row, c, story, what);
      }
    } else if (kind === 'epic') {
      const c = { ...epicColumns(table, PLAN), epic: epicCol };
      for (const row of body) {
        const epic =
          linkInCell(row.cells[epicCol]) !== null
            ? epicOfRow(PLAN, row, epicCol, what, backlog)
            : epicsById.get(norm(row.cells[epicCol]));
        if (epic === undefined) fail(PLAN, row.line, `${what} row "${norm(row.cells[epicCol])}" names no epic folder of the backlog`);
        if (epic != null) compareEpicCells(PLAN, row, c, epic, what);
      }
    } else {
      for (const row of body) {
        const id = /^(W\d+)\b/.exec(norm(row.cells[waveCol]))[1];
        if (!WAVES.includes(id)) {
          fail(PLAN, row.line, `${what} row ${id} is not one of ${WAVES.join(', ')}`);
          continue;
        }
        const derived = totalsOf(stories.filter((s) => s.wave === id));
        for (const [key, col] of [['stories', count], ['points', points], ['files', files]]) {
          if (!has(col)) continue;
          const v = cellInt(PLAN, row.line, row.cells[col], `${what} ${id} ${LABEL[key]}`);
          if (v !== null && derived[key] !== null && v !== derived[key]) {
            fail(PLAN, row.line, `${what} ${id}: ${LABEL[key]} ${v} differs from the sum of the stories whose Build wave is ${id}, ${derived[key]}`);
          }
        }
      }
    }

    if (totals.length > 0) {
      const specs = [
        { key: 'stories', idx: kind === 'story' ? -1 : count },
        { key: 'points', idx: points },
        { key: 'files', idx: files },
      ];
      const stated = checkTotalsRow(PLAN, table, what, specs);
      // A stated story or epic count right after the Total label must be true as well.
      if (stated && kind !== 'wave' && !specs.some((s) => s.idx === 1) && norm(stated.row.cells[1]) !== '') {
        checkCountCell(PLAN, stated.row, body.length, what, kind === 'story' ? 'stories' : 'epics');
      }
      continue;
    }
    const last = table.rowLines.length > 0 ? table.rowLines[table.rowLines.length - 1] : table.line + 1;
    let n = last + 1;
    while (n <= plan.lines.length && plan.lines[n - 1].trim() === '') n++;
    const s = n <= plan.lines.length && !plan.fenced.has(n) ? plan.lines[n - 1].replace(/\*\*|__/g, '').trim() : '';
    if (!/^(?:\S+\s+){0,3}totals?:/i.test(s)) {
      fail(PLAN, table.line, `${what} closes with no totals row or totals line summing its estimated files touched column`);
      continue;
    }
    const m = /\b(\d+) files touched\b/i.exec(s);
    const sum = has(files) ? sumOrNull(body.map((r) => intStrict(norm(r.cells[files])))) : null;
    if (m === null) fail(PLAN, n, `${what} totals line "${s}" does not state its estimated files touched sum`);
    else if (sum !== null && Number(m[1]) !== sum) {
      fail(PLAN, n, `${what} totals line states estimated files touched ${m[1]}, but its rows sum to ${sum}`);
    }
  }
}

/** Label → status map of a Customer Details field-gap table, with the per-table status rules. */
function fieldGapMap(path, table, what) {
  const lc = colIndex(table, COL.label);
  const sc = colIndex(table, COL.status);
  const { body } = rowsOf(path, table, what);
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

  const [storyTable, ...extraStoryTables] = tables.filter((t) => isStoryTable(t, PLAN));
  for (const t of extraStoryTables) {
    fail(PLAN, t.line, `the gap list has more than one story table; the first is at line ${storyTable.line}`);
  }
  if (storyTable === undefined) {
    fail(PLAN, sec.startLine, 'the gap list has no story table linking every story');
  } else {
    const what = 'gap-list story table';
    const c = { story: storyColumn(storyTable, PLAN, { fallback: true }), files: colIndex(storyTable, COL.files) };
    requireColumns(PLAN, storyTable, what, c, [['story', 'Story'], ['files', '']]);
    const { body } = rowsOf(PLAN, storyTable, what);
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
      checkCountCell(PLAN, totals.row, body.length, what, 'stories');
      checkInventory(PLAN, totals.row.line, `${what} total`, totals.values, INVENTORY.total);
    }
  }

  const [planFields, ...extraFieldTables] = tables.filter(isFieldGapTable);
  for (const t of extraFieldTables) {
    fail(PLAN, t.line, `the gap list has more than one unmapped-field table (columns Label and Status); the first is at line ${planFields.line}`);
  }
  if (planFields === undefined) {
    fail(PLAN, sec.startLine, 'the gap list has no unmapped-field table (columns Label and Status)');
    return;
  }
  const planMap = fieldGapMap(PLAN, planFields, 'unmapped-field table');
  if (!exists(CS01_README)) {
    fail(CS01_README, undefined, 'the CS-01 epic README holding the Customer Details field gaps does not exist');
    return;
  }
  if (!isRegularFile(CS01_README)) {
    fail(CS01_README, undefined, 'the CS-01 epic README holding the Customer Details field gaps is not a regular file');
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

/**
 * True when a root-relative path is a regular file, a symlink to one included: it is listed by
 * listFiles of its parent directory. exists() is also true for a directory, which readText cannot
 * read, so each file this check reads by a fixed path passes this test first.
 */
function isRegularFile(rel) {
  return listFiles(path.posix.dirname(rel)).includes(rel);
}

/* ------------------------------------------------------------------------------------------------
 * Entry point
 * ---------------------------------------------------------------------------------------------- */

function run() {
  if (!exists(PLAN)) {
    fail(PLAN, undefined, `the implementation plan does not exist at ${PLAN}`);
    return;
  }
  if (!isRegularFile(PLAN)) {
    fail(PLAN, undefined, `the implementation plan at ${PLAN} is not a regular file`);
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
    links: parseLinks(text),
  };

  checkOpening(plan);
  const { summary, table: summaryTable } = checkWaveSummary(plan);
  checkWaveSections(plan, backlog, summary);
  const { table: epicsTable, groupings } = checkEpicsInScope(plan, backlog);
  checkEpicIndex(backlog, groupings);
  checkEpicReadmes(backlog);
  checkLayout(plan, summaryTable, epicsTable);
  checkRepresentedTables(plan, backlog, summaryTable, epicsTable);
  checkGapList(plan, backlog);
  checkPlanLinks(plan, backlog);
}

const USAGE = [
  'Usage: node tests/validation/check-plan-consistency.mjs [-h | --help]',
  '',
  'Implementation plan gate for the Card Servicing backlog: checks that docs/implementation-plan.md',
  'agrees with the backlog (wave, epic and story tables, totals, links, identifiers) and keeps its',
  'layout, with the CARD_REQ_TBD gap list last.',
  '',
  'Runs from any working directory; every path resolves from the repository root. Reads the plan',
  '(docs/implementation-plan.md), the epic index (docs/backlog/epics/README.md), and the epic',
  'READMEs and story files under docs/backlog/epics/.',
  '',
  'Options:',
  '  -h, --help  print this usage and exit without checking',
  '',
  'Exit codes:',
  '  0  no violations',
  '  1  violations, printed one per line before their count',
  '  2  usage error or internal error',
].join('\n');
const HELP_ARGS = new Set(['-h', '--help']);

const args = process.argv.slice(2);
const unknownArg = args.find((arg) => !HELP_ARGS.has(arg));
if (unknownArg !== undefined) {
  console.error(`${CHECK}: unknown argument ${JSON.stringify(unknownArg)}`);
  console.error(USAGE);
  process.exitCode = 2;
} else if (args.length > 0) {
  console.log(USAGE);
  process.exitCode = 0;
} else {
  try {
    run();
    report(CHECK, violations);
  } catch (err) {
    console.error(`${CHECK}: internal error: ${err?.stack ?? err}`);
    process.exitCode = 2;
  }
}

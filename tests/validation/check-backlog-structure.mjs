// SPDX-License-Identifier: Apache-2.0
/**
 * check-backlog-structure — backlog structure and story content gate.
 *
 * Run second by tests/validation/run-all.sh as `node tests/validation/check-backlog-structure.mjs`.
 * It reads every file under docs/backlog/epics/ and nothing else, prints one line per
 * violation and the final count through report(), and exits 0 with no violation, 1 with
 * any, and 2 on a usage error (any argument but --help or -h) or an internal error. It
 * takes no arguments; --help or -h prints the usage and exits 0 without scanning. A
 * failing predicate is fixed in the backlog artifact, never by relaxing the predicate.
 *
 * Predicate groups and the gate each one implements:
 *  - S (paths, epic index, epic README records, story part order): the Validation Gate —
 *    structure of "Epic and Story Structure — Multi-File Variant". Only its three path
 *    forms exist under the epics area, every epic folder follows one identifier
 *    convention, carries a README and no subfolder but stories/, the epic index is a single
 *    table listing every epic README exactly once whose ID, Name and Folder cells equal the
 *    linked epic's folder identifier, README title name and folder, every epic README links
 *    every file in its stories/ folder and ends with "Owner: TBD" then "Status: TBD", a
 *    story's title KEY equals its filename identifier, its parent epic line carries the
 *    epic's README title name and links its README, and story parts follow the rule's
 *    order. The request's sections 4–7 sit between the
 *    reproduced source body and Implementation Design, and the epic README holds the
 *    request's four epic sections inside the rule's record (the resolved backlog structure),
 *    each with content: a business justification, requirements as list items carrying their
 *    traces, criteria numbered 1..n each with text, and dependencies naming each
 *    prerequisite epic with its producing story edges, or "N/A".
 *  - A (story statement, trace, classification lines, AC1–ACn, authorization and
 *    resilience obligations): the request's story and acceptance-criteria pass/fail lines,
 *    with AC1–ACn numbering from "Enforce Implementation Plan, Epic and Story Structure —
 *    Base".
 *  - D (quoted source body, Design links, Test Plan thresholds, exactly two design options,
 *    one named Recommendation and a framing line naming it, at most one Mermaid sequence
 *    diagram, per-option settled and open assumptions with a verdict for every AC): the
 *    Validation Gate — content of "Enforce Implementation Plan, Epic and Story Structure —
 *    Base", plus the request's Design and Test Plan pass/fail lines.
 *  - Voice (no link, code span, path, code identifier, config key or maturity tag in
 *    Implementation Design and Design Validation, and single-clause table cells): the design
 *    voice requirements and content gate of "Enforce Implementation Plan, Epic and Story
 *    Structure — Base".
 *  - M (metadata block last holding each of its 20 fields exactly once and no other field,
 *    no blank value, Issue Type Story and Priority High as the request supplies them, TBD
 *    in every field no input supplies, integer Story Points of 5 or less, integer
 *    Estimated Files Touched, Build wave, Epic Name, Epic Link, and Depends on as None or a
 *    comma-separated list of distinct identifiers of other existing stories): the metadata
 *    requirements and content gate of "Enforce Implementation Plan, Epic and Story
 *    Structure — Base".
 * The plan-side half of that content gate is check-plan-consistency.mjs.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  repoRoot,
  readText,
  exists,
  listFiles,
  fencedBlocks,
  parseHeadings,
  sections,
  sectionByHeading,
  stripBlockquote,
  parseTables,
  isTotalsRow,
  parseLinks,
  resolveLink,
  linkInCell,
  metadataEntries,
  intStrict,
  plainText,
  report,
} from './lib/markdown.mjs';

const CHECK = 'check-backlog-structure';
const EPICS_DIR = 'docs/backlog/epics';
const INDEX_README = `${EPICS_DIR}/README.md`;
const PATH_FORMS = `${EPICS_DIR}/README.md, ${EPICS_DIR}/<epic-folder>/README.md or ${EPICS_DIR}/<epic-folder>/stories/<story-file>.md`;

// The backlog inventory is fixed at 13 epics (CS-01 to CS-13) and 58 stories.
const EPIC_IDS = Array.from({ length: 13 }, (_, i) => `CS-${String(i + 1).padStart(2, '0')}`);
const STORY_COUNT = 58;

const EPIC_FOLDER_RE = /^CS-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/;
const STORY_FILE_RE = /^CS-(\d{2})-(\d{3})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$/;
const DEPENDS_ON_ID_RE = /^CS-\d{2}-\d{3}$/;
const EPIC_TITLE_RE = /^(CS-\d{2}): (.+)$/;
const STORY_TITLE_RE = /^# (CS-\d{2}-\d{3}): (.+)$/;
const PARENT_EPIC_RE = /^Parent epic: \[(CS-\d{2}) ([^\]]+)\]\(([^)]+)\)$/;
const STATEMENT_RE = /^As an? .+, I want .+, so that .+\.$/;
const TRACE_RE = /^Trace: CARD_REQ_(\d{3}|TBD) · (Use Cases|Data Layouts|Requirements Analysis) · .+$/;
const ACCESS_LABEL = 'Account-data access path:';
const ACCESS_RE = /^Account-data access path: (Yes|No)$/;
const EXTERNAL_LABEL = 'External integration or SLA-bound:';
const EXTERNAL_RE = /^External integration or SLA-bound: (Yes|No)$/;
const AC_START_RE = /^(?:[-*+]\s+|\d+\.\s+)?(?:\*\*)?AC(\d+)\b/;
const OPTION_MENTION_RE = /\bOption ([AB])\b/;

const EPIC_SECTIONS = ['Goal', 'Business Justification', 'Requirements', 'Acceptance Criteria', 'Dependencies', 'Stories'];
const STORY_SECTIONS = [
  'Source Story (verbatim)',
  '4. Design',
  '5. Documentation Changes',
  '6. Test Plan',
  '7. Validation Results',
  'Implementation Design',
  'Design Validation',
  'Metadata',
];
const BODY_SECTIONS = ['1. Details/Requirements', '2. Acceptance Criteria', '3. Dependencies'];
const METADATA_FIELDS = [
  'Build wave',
  'Depends on',
  'Status',
  'Estimated Files Touched',
  'Issue Type',
  'Epic Name',
  'Epic Link',
  'Story Points',
  'Priority',
  'Components',
  'Fix Version/s',
  'Team',
  'Assignee',
  'Reporter',
  'Labels',
  'Sprint',
  'Due Date',
  'Environment',
  'Persona',
  'Estimated Effort',
];
// The metadata requirements of "Enforce Implementation Plan, Epic and Story Structure —
// Base" carry each input value and write TBD where no input supplies one. The request
// header supplies Issue Type and Priority; it names no workflow state, component, version,
// team or person, so these fields are TBD in every story.
const SUPPLIED_METADATA = new Map([
  ['Issue Type', 'Story'],
  ['Priority', 'High'],
]);
const TBD_METADATA_FIELDS = [
  'Status',
  'Components',
  'Fix Version/s',
  'Team',
  'Assignee',
  'Reporter',
  'Labels',
  'Sprint',
  'Due Date',
  'Environment',
  'Persona',
  'Estimated Effort',
];
const VERDICTS = new Set(['Holds', 'Conditional', 'At risk', 'Breaks']);
const WAVES = new Set(['W0', 'W1', 'W2', 'W3']);
const OPENAPI_DIR = 'card-servicing-service/src/main/resources/openapi/';

// Product and protocol names whose standard spelling carries internal capitals. In plain
// prose they are proper nouns, not code identifiers, so they are removed before the
// camelCase and PascalCase token tests.
const PLAIN_PROPER_NAMES = [
  'gRPC',
  'mTLS',
  'OpenAPI',
  'PostgreSQL',
  'CardDemo',
  'GitHub',
  'JaCoCo',
  'JUnit',
  'AuthN',
  'AuthZ',
  'authN',
  'authZ',
  'macOS',
  'JavaScript',
  'NoSQL',
];
const PROPER_NAME_RE = new RegExp(`(?<![A-Za-z0-9_])(?:${PLAIN_PROPER_NAMES.join('|')})(?![A-Za-z0-9_])`, 'g');

const LINK_RE = /\[[^\]]*\]\([^)]*\)/g;
const CODE_SPAN_RE = /`[^`]+`/g;
const PATH_RES = [
  /[^\s()[\]<>"'`]+\.(?:md|mmd|proto|yaml|yml|json|java|gradle|sql|sh|mjs|toml|txt|properties)\b/g,
  /(?<![^\s([<"'])\.{1,2}\/[^\s)\]>"']*/g,
  /(?<![^\s([<"'])(?:docs|app|tests|card-servicing-service|card-servicing-contracts|gradle|src|samples|diagrams|\.github)\/[^\s)\]>"']*/g,
  /(?<![^\s(])\/[A-Za-z0-9{}_-]+(?:\/[A-Za-z0-9{}_.-]*)*/g,
];
// Ordered most specific first; each match is masked so one token yields one violation.
const TOKEN_TESTS = [
  ['call form', /\b[A-Za-z_]\w*\(\)/g],
  ['dotted identifier', /\b[A-Za-z_]\w*\.[A-Za-z_]\w*\.[A-Za-z_]\w*\b/g],
  ['UPPER_SNAKE key', /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/g],
  ['snake_case identifier', /\b[a-z][a-z0-9]*_[a-z0-9_]+\b/g],
  ['camelCase identifier', /\b[a-z]+[A-Z][A-Za-z0-9]*\b/g],
  ['PascalCase identifier', /\b[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*\b/g],
  ['maturity tag', /(?<![A-Za-z0-9-])(?:DELIVERED|AS-IS|POC|TO-BE|DESIGNED|UNDECIDED)(?![A-Za-z0-9-])/g],
];
// Abbreviations whose period ends no sentence, so "e.g. the cache" stays one clause.
const ABBREVIATION_RE = /\b(?:e\.g|i\.e|etc|vs|cf)\./gi;
const QUOTE_PREFIX_RE = /^[ \t]{0,3}(?:>[ \t]?)+/;

const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const normaliseSpace = (s) => String(s ?? '').replace(/\s+/g, ' ').trim();

/** Label-line form: no blockquote marker, list marker, bold marker or surrounding backticks. */
function normaliseLabel(line) {
  let s = String(line ?? '').replace(QUOTE_PREFIX_RE, '');
  s = s.replace(/^\s*(?:[-*+]|\d+\.)\s+/, '');
  s = s.replace(/\*\*|__/g, '');
  s = s.trim().replace(/^`+|`+$/g, '');
  return normaliseSpace(s);
}

/** A table cell or metadata value as plain, whitespace-normalised text. */
const plainCell = (cell) => normaliseSpace(plainText(String(cell ?? '')));

const hasSecondSentence = (s) => /[.!?]\s+\S/.test(String(s).replace(ABBREVIATION_RE, 'abbr'));

const splitLines = (text) => text.split(/\r?\n/);

/** 1-based line numbers covered by fenced code, opening and closing fences included. */
function fenceLineSet(text) {
  const set = new Set();
  for (const block of fencedBlocks(text)) {
    for (let l = block.startLine; l <= block.endLine; l++) set.add(l);
  }
  return set;
}

/** The level-2 section whose heading text, whitespace-normalised, equals `name`. */
function h2Section(text, name) {
  return sections(text, 2).find((s) => normaliseSpace(s.heading) === name) ?? null;
}

/** Non-quoted headings of a section text with document line numbers. */
function sectionHeadings(section) {
  return parseHeadings(section.text)
    .filter((h) => !h.quoted)
    .map((h) => ({ ...h, docLine: section.startLine + h.line - 1 }));
}

/**
 * The lines from a heading to the line before the next heading of the same or a higher
 * level inside the section, as { text, startLine } with startLine the heading's document line.
 */
function subsection(section, heads, head) {
  const lines = splitLines(section.text);
  const next = heads.find((h) => h.docLine > head.docLine && h.level <= head.level);
  const end = next ? next.docLine - 1 : section.endLine;
  return {
    text: lines.slice(head.docLine - section.startLine, end - section.startLine + 1).join('\n'),
    startLine: head.docLine,
    endLine: end,
  };
}

/**
 * Compares a heading list with the required names and order: reports headings that are
 * missing, unexpected, duplicated or out of order.
 */
function checkHeadingSequence(found, expected, rel, marks, v) {
  const seen = new Set();
  let maxIndex = -1;
  for (const h of found) {
    const name = normaliseSpace(h.text);
    const index = expected.indexOf(name);
    if (index < 0) {
      v.push({ path: rel, line: h.line, message: `unexpected heading "${marks} ${name}"; required, in order: ${expected.join(', ')}` });
      continue;
    }
    if (seen.has(name)) {
      v.push({ path: rel, line: h.line, message: `duplicate heading "${marks} ${name}"` });
      continue;
    }
    seen.add(name);
    if (index < maxIndex) {
      v.push({ path: rel, line: h.line, message: `heading "${marks} ${name}" is out of order; it must precede "${marks} ${expected[maxIndex]}"` });
    } else {
      maxIndex = index;
    }
  }
  for (const name of expected) {
    if (!seen.has(name)) v.push({ path: rel, message: `missing heading "${marks} ${name}"` });
  }
}

/**
 * Every folder below a root-relative directory, plus entries listFiles does not report as
 * files (links to folders, dangling links, sockets), so empty or stray folders are caught.
 * Nothing is skipped: a node_modules or .git folder under the epics area is itself stray.
 */
function walkTree(relDir) {
  const dirs = [];
  const others = [];
  const walk = (rel) => {
    const abs = path.join(repoRoot, ...rel.split('/'));
    const entries = fs.readdirSync(abs, { withFileTypes: true }).sort((a, b) => compare(a.name, b.name));
    for (const entry of entries) {
      const childRel = `${rel}/${entry.name}`;
      if (entry.isDirectory()) {
        dirs.push(childRel);
        walk(childRel);
      } else if (entry.isSymbolicLink()) {
        let target = null;
        try {
          target = fs.statSync(path.join(abs, entry.name));
        } catch (err) {
          if (err.code !== 'ENOENT') throw err;
        }
        if (target === null) others.push({ path: childRel, kind: 'dangling symbolic link' });
        else if (target.isDirectory()) others.push({ path: childRel, kind: 'symbolic link to a folder' });
      } else if (!entry.isFile()) {
        others.push({ path: childRel, kind: 'entry that is neither a file nor a folder' });
      }
    }
  };
  walk(relDir);
  return { dirs, others };
}

/** Group S: path forms, folder and filename conventions, epic and story counts. */
function checkPaths(v) {
  if (!exists(EPICS_DIR)) {
    v.push({ path: EPICS_DIR, message: 'epics area is missing' });
    return { files: new Set(), epics: [], storyKeys: new Map() };
  }
  const prefix = `${EPICS_DIR}/`;
  const files = listFiles(EPICS_DIR);
  const { dirs, others } = walkTree(EPICS_DIR);
  for (const o of others) {
    v.push({ path: o.path, message: `${o.kind} under the epics area; only ${PATH_FORMS} may exist there` });
  }

  const epicByFolder = new Map();
  const invalidFolders = new Set();
  const dirSet = new Set(dirs);
  for (const d of dirs) {
    const parts = d.slice(prefix.length).split('/');
    if (parts.length === 1) {
      const m = EPIC_FOLDER_RE.exec(parts[0]);
      if (!m) {
        invalidFolders.add(parts[0]);
        v.push({ path: d, message: 'epic folder name does not follow the CS-NN-<slug> convention of the other epic folders (mixed identifier format)' });
        continue;
      }
      epicByFolder.set(parts[0], { folder: parts[0], dir: d, id: `CS-${m[1]}`, num: m[1], readme: `${d}/README.md`, stories: [] });
    } else if (parts.length === 2 && parts[1] !== 'stories') {
      v.push({ path: d, message: 'subfolder other than stories/ inside an epic folder' });
    } else if (parts.length === 3 && parts[1] === 'stories') {
      v.push({ path: d, message: 'subfolder inside a stories/ folder' });
    }
  }

  const fileSet = new Set(files);
  const storyKeys = new Map();
  for (const f of files) {
    const parts = f.slice(prefix.length).split('/');
    if (parts.length === 1 && parts[0] === 'README.md') continue;
    if (parts.length === 2 && parts[1] === 'README.md') continue;
    if (parts.length === 3 && parts[1] === 'stories' && parts[2].endsWith('.md')) {
      const epic = epicByFolder.get(parts[0]);
      if (!epic) continue; // its folder is already reported as off-convention
      const m = STORY_FILE_RE.exec(parts[2]);
      if (!m) {
        v.push({ path: f, message: 'story filename does not follow the CS-NN-NNN-<slug>.md convention of the epic folders' });
        continue;
      }
      if (`CS-${m[1]}` !== epic.id) {
        v.push({ path: f, message: `story filename carries epic number CS-${m[1]} but sits in the ${epic.id} folder` });
      }
      const key = `CS-${m[1]}-${m[2]}`;
      if (storyKeys.has(key)) {
        v.push({ path: f, message: `duplicate story identifier ${key}, also used by ${storyKeys.get(key)}` });
        continue;
      }
      storyKeys.set(key, f);
      epic.stories.push({ rel: f, key, epic });
      continue;
    }
    v.push({ path: f, message: `path is not one of the three permitted forms: ${PATH_FORMS}` });
  }

  if (!fileSet.has(INDEX_README)) v.push({ path: INDEX_README, message: 'epic index README is missing' });

  const epics = [...epicByFolder.values()].sort((a, b) => compare(a.folder, b.folder));
  const foldersById = new Map();
  for (const epic of epics) {
    if (!fileSet.has(epic.readme)) v.push({ path: epic.dir, message: 'epic folder has no README.md' });
    if (!dirSet.has(`${epic.dir}/stories`)) v.push({ path: epic.dir, message: 'epic folder has no stories/ folder' });
    foldersById.set(epic.id, (foldersById.get(epic.id) ?? []).concat(epic.dir));
  }
  for (const [id, folders] of foldersById) {
    if (folders.length > 1) v.push({ path: EPICS_DIR, message: `epic identifier ${id} is used by ${folders.length} folders: ${folders.join(', ')}` });
    if (!EPIC_IDS.includes(id)) v.push({ path: folders[0], message: `epic identifier ${id} is outside the inventory ${EPIC_IDS[0]}–${EPIC_IDS[EPIC_IDS.length - 1]}` });
  }
  for (const id of EPIC_IDS) {
    if (!foldersById.has(id)) v.push({ path: EPICS_DIR, message: `no epic folder for ${id}` });
  }
  const folderCount = epics.length + invalidFolders.size;
  if (folderCount !== EPIC_IDS.length) {
    v.push({ path: EPICS_DIR, message: `${folderCount} epic folders found; the inventory holds ${EPIC_IDS.length}` });
  }
  if (storyKeys.size !== STORY_COUNT) {
    v.push({ path: EPICS_DIR, message: `${storyKeys.size} story files found; the inventory holds ${STORY_COUNT}` });
  }
  for (const epic of epics) epic.stories.sort((a, b) => compare(a.rel, b.rel));
  return { files: fileSet, epics, storyKeys };
}

/**
 * Group S: the epic index is one table that lists every epic README exactly once, and each
 * row names the epic it links by ID, by README title name and by folder. `epicNames` maps
 * an epic identifier to its README title name.
 */
function checkEpicIndex(files, epics, epicNames, v) {
  if (!files.has(INDEX_README)) return;
  const text = readText(INDEX_README);
  const readmeRe = /^docs\/backlog\/epics\/[^/]+\/README\.md$/;
  const linkedReadme = (row) => {
    for (const cell of row) {
      const link = linkInCell(cell);
      if (!link) continue;
      const target = resolveLink(INDEX_README, link.target).path;
      if (readmeRe.test(target)) return target;
    }
    return null;
  };
  const tables = parseTables(text).filter((t) => t.rows.some((r) => !isTotalsRow(r) && linkedReadme(r)));
  if (tables.length === 0) {
    v.push({ path: INDEX_README, message: 'no table in the epic index links the epic READMEs' });
    return;
  }
  if (tables.length > 1) {
    v.push({ path: INDEX_README, line: tables[1].line, message: `epic index holds ${tables.length} tables that link epic READMEs; exactly one index table is allowed` });
  }
  const [table] = tables;

  // Identity columns, located by header text; every column under a required header is compared.
  const columns = (name) => {
    const found = [];
    table.header.forEach((cell, k) => {
      if (plainCell(cell).toLowerCase() === name.toLowerCase()) found.push(k);
    });
    if (found.length === 0) v.push({ path: INDEX_README, line: table.line, message: `epic index table has no "${name}" column` });
    return found;
  };
  const idCols = columns('ID');
  const nameCols = columns('Name');
  const folderCols = columns('Folder');
  const epicByReadme = new Map(epics.map((e) => [e.readme, e]));
  const folderPrefix = `${EPICS_DIR}/`;

  const listed = new Map();
  table.rows.forEach((row, i) => {
    if (isTotalsRow(row)) return;
    const line = table.rowLines[i];
    const target = linkedReadme(row);
    if (!target) {
      v.push({ path: INDEX_README, line, message: 'epic index row has no link to an epic README' });
      return;
    }
    if (!files.has(target)) {
      v.push({ path: INDEX_README, line, message: `epic index row links ${target}, which does not exist` });
      return;
    }
    listed.set(target, (listed.get(target) ?? []).concat(line));
    const epic = epicByReadme.get(target);
    if (!epic) return; // its folder is already reported as off-convention

    for (const k of idCols) {
      const id = plainCell(row[k]);
      if (id !== epic.id) v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: ID "${id}" differs from the folder identifier ${epic.id}` });
    }
    const expectedName = epicNames.get(epic.id);
    if (expectedName !== undefined) {
      for (const k of nameCols) {
        const name = plainCell(row[k]);
        if (name !== expectedName) {
          v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: Name "${name}" differs from the epic README title name "${expectedName}"` });
        }
      }
    }
    for (const k of folderCols) {
      const shown = plainCell(row[k]);
      let folder = shown.endsWith('/') ? shown.slice(0, -1) : shown;
      if (folder.startsWith(folderPrefix)) folder = folder.slice(folderPrefix.length);
      if (folder !== epic.folder) {
        v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: Folder "${shown}" differs from the epic folder ${epic.folder}/` });
      }
      const link = linkInCell(row[k]);
      const linkTarget = link ? resolveLink(INDEX_README, link.target).path : null;
      if (linkTarget !== null && linkTarget !== target) {
        v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: Folder cell links ${linkTarget}, not the row's epic README ${target}` });
      }
    }
  });
  for (const epic of epics) {
    if (!files.has(epic.readme)) continue;
    const lines = listed.get(epic.readme);
    if (!lines) v.push({ path: INDEX_README, line: table.line, message: `epic index does not list ${epic.readme}` });
    else if (lines.length > 1) v.push({ path: INDEX_README, line: lines[1], message: `epic index lists ${epic.readme} ${lines.length} times` });
  }
}

/** Group S: one epic README record. Returns the epic display name, or null when unreadable. */
function checkEpicReadme(epic, v) {
  const rel = epic.readme;
  const text = readText(rel);
  const lines = splitLines(text);
  const heads = parseHeadings(text).filter((h) => !h.quoted);

  let name = null;
  const first = heads[0];
  const titleMatch = first && first.level === 1 ? EPIC_TITLE_RE.exec(normaliseSpace(first.text)) : null;
  if (!titleMatch) {
    v.push({ path: rel, line: first?.line, message: `first heading must be the level-1 title "# ${epic.id}: <epic name>"` });
  } else if (titleMatch[1] !== epic.id) {
    v.push({ path: rel, line: first.line, message: `title identifier ${titleMatch[1]} differs from the folder identifier ${epic.id}` });
  } else {
    name = normaliseSpace(titleMatch[2]);
  }

  checkHeadingSequence(heads.filter((h) => h.level === 2), EPIC_SECTIONS, rel, '##', v);

  const goal = h2Section(text, 'Goal');
  if (goal) {
    const fences = fenceLineSet(goal.text);
    const sentence = normaliseSpace(
      splitLines(goal.text)
        .slice(1)
        .filter((l, k) => l.trim() !== '' && !fences.has(k + 2))
        .map((l) => plainText(normaliseLabel(l)))
        .join(' '),
    );
    if (sentence === '') v.push({ path: rel, line: goal.startLine, message: 'Goal is empty; it states the epic goal in one sentence' });
    else if (hasSecondSentence(sentence)) v.push({ path: rel, line: goal.startLine, message: 'Goal holds more than one sentence' });
  }

  // The request's four epic sections hold content, not only their headings, and the README
  // covers the epic's acceptance criteria as "Epic and Story Structure — Multi-File Variant"
  // requires. The resolved backlog structure sets each section's form: requirements are list
  // items carrying their traces, criteria are numbered 1..n, and Dependencies lists the
  // prerequisite epics, each with its producing story edges, or reads "N/A". Whether those
  // entries equal the epic-dependency table is check-dependency-order.mjs's predicate.
  const LIST_ITEM_RE = /^( {0,3})([-*+]|\d{1,9}[.)])(?:([ \t]+)(.*))?$/;
  const HEADING_LINE_RE = /^ {0,3}#{1,6}(?:[ \t]|$)/;
  const TRACE_LABEL_RE = /(?<![\p{L}\p{N}_])Trace:/u;
  const EPIC_REF_RE = /(?<![\w-])CS-\d{2}(?![\w-])/g;
  const STORY_EDGE_RE = /(?<![\w-])CS-\d{2}-\d{3}\s*(?:→|->)\s*CS-\d{2}-\d{3}(?![\w-])/;
  const hasText = (s) => /[\p{L}\p{N}]/u.test(plainText(s));
  // Content lines of one H2 section with document line numbers, blank and fenced lines left
  // out. A list item gathers its continuation lines: lazy lines straight after it, lines
  // indented to its content after a gap, and nested items. Other lines are headings or loose.
  const requestSection = (sectionName) => {
    const section = h2Section(text, sectionName);
    if (!section) return null;
    const fences = fenceLineSet(section.text);
    const content = [];
    const items = [];
    let item = null;
    let afterGap = false;
    splitLines(section.text).forEach((raw, k) => {
      // Line k + 1 of the section text is document line section.startLine + k; line 1 is the heading.
      if (k === 0) return;
      if (raw.trim() === '' || fences.has(k + 1)) {
        afterGap = true;
        return;
      }
      const line = section.startLine + k;
      const indent = raw.length - raw.trimStart().length;
      const marker = LIST_ITEM_RE.exec(raw);
      let kind;
      if (HEADING_LINE_RE.test(raw)) {
        item = null;
        kind = 'heading';
      } else if (marker && !(item && indent >= item.contentIndent)) {
        const spaces = (marker[3] ?? ' ').length;
        item = {
          line,
          number: /^\d/.test(marker[2]) ? Number.parseInt(marker[2], 10) : null,
          text: marker[4] ?? '',
          contentIndent: marker[1].length + marker[2].length + (spaces <= 4 ? spaces : 1),
        };
        items.push(item);
        kind = 'item';
      } else if (item && (!afterGap || indent >= item.contentIndent)) {
        item.text += ` ${raw.trim()}`;
        kind = 'continuation';
      } else {
        item = null;
        kind = 'loose';
      }
      content.push({ line, raw, kind, item: kind === 'item' ? item : null });
      afterGap = false;
    });
    return { section, content, items };
  };

  const justification = requestSection('Business Justification');
  if (justification && !justification.content.some((c) => c.kind !== 'heading' && hasText(normaliseLabel(c.raw)))) {
    v.push({ path: rel, line: justification.section.startLine, message: 'Business Justification is empty; it states why the epic is built' });
  }

  const requirements = requestSection('Requirements');
  if (requirements) {
    if (requirements.items.length === 0) {
      v.push({ path: rel, line: requirements.section.startLine, message: 'Requirements lists no requirement; each requirement is a list item carrying its trace' });
    }
    for (const req of requirements.items) {
      const plain = normaliseSpace(req.text.replace(/`/g, ''));
      const at = plain.search(TRACE_LABEL_RE);
      if (at < 0) {
        v.push({ path: rel, line: req.line, message: 'Requirements item carries no "Trace:"; each requirement carries its trace' });
      } else if (!TRACE_RE.test(plain.slice(at))) {
        v.push({ path: rel, line: req.line, message: `Requirements item trace "${plain.slice(at)}" does not take the trace form of the stories, "Trace: CARD_REQ_<id> · <document> · <anchor>"` });
      } else if (!hasText(plain.slice(0, at))) {
        v.push({ path: rel, line: req.line, message: 'Requirements item holds a trace but no requirement' });
      }
    }
  }

  const criteria = requestSection('Acceptance Criteria');
  if (criteria) {
    for (const c of criteria.content) {
      if (c.kind === 'heading' || c.kind === 'loose' || (c.kind === 'item' && c.item.number === null)) {
        v.push({ path: rel, line: c.line, message: 'Acceptance Criteria line is not part of a numbered criterion; each criterion is a numbered list item' });
      }
    }
    const numbered = criteria.items.filter((c) => c.number !== null);
    if (numbered.length === 0) {
      v.push({ path: rel, line: criteria.section.startLine, message: 'Acceptance Criteria lists no numbered criterion; the epic README covers its acceptance criteria' });
    }
    const outOfSequence = numbered.findIndex((c, k) => c.number !== k + 1);
    if (outOfSequence >= 0) {
      const c = numbered[outOfSequence];
      v.push({ path: rel, line: c.line, message: `Acceptance Criteria item is numbered ${c.number} where ${outOfSequence + 1} is next; criteria run 1..n in order with no gap or duplicate` });
    }
    for (const c of numbered) {
      if (!hasText(c.text)) v.push({ path: rel, line: c.line, message: `Acceptance Criteria item ${c.number} has no text` });
    }
  }

  const dependencies = requestSection('Dependencies');
  if (dependencies) {
    const shape = 'it lists the prerequisite epics, each with its producing story edges, or reads "N/A"';
    const body = dependencies.content.filter((c) => c.kind !== 'heading');
    const notApplicable = dependencies.content.filter((c) => plainText(normaliseLabel(c.raw)) === 'N/A');
    if (body.length === 0) {
      v.push({ path: rel, line: dependencies.section.startLine, message: `Dependencies is empty; ${shape}` });
    } else if (notApplicable.length > 0) {
      if (dependencies.content.length > 1) {
        v.push({ path: rel, line: notApplicable[0].line, message: `Dependencies mixes "N/A" with other content; ${shape}` });
      }
    } else if (dependencies.items.length === 0) {
      v.push({ path: rel, line: dependencies.section.startLine, message: `Dependencies lists no entry; ${shape}` });
    } else {
      for (const entry of dependencies.items) {
        const plain = normaliseSpace(plainText(entry.text));
        if (!(plain.match(EPIC_REF_RE) ?? []).some((id) => id !== epic.id)) {
          v.push({ path: rel, line: entry.line, message: 'Dependencies entry names no prerequisite epic "CS-NN"' });
        }
        if (!STORY_EDGE_RE.test(plain)) {
          v.push({ path: rel, line: entry.line, message: 'Dependencies entry carries no producing story edge "CS-NN-NNN → CS-NN-NNN"' });
        }
      }
    }
  }

  const stories = h2Section(text, 'Stories');
  if (!stories) return name;
  const table = parseTables(stories.text).find(
    (t) => t.header.some((c) => /points/i.test(c)) && t.header.some((c) => /files touched/i.test(c)),
  );
  if (!table) {
    v.push({ path: rel, line: stories.startLine, message: 'Stories section has no table with a points column and an estimated files touched column' });
    return name;
  }
  const toDoc = (l) => stories.startLine + l - 1;
  const storyDir = `${epic.dir}/stories/`;
  const expected = new Set(epic.stories.map((s) => s.rel));
  const linked = new Map();
  let hasTotals = false;
  table.rows.forEach((row, i) => {
    const line = toDoc(table.rowLines[i]);
    if (isTotalsRow(row)) {
      hasTotals = true;
      return;
    }
    let target = null;
    for (const cell of row) {
      const link = linkInCell(cell);
      if (!link) continue;
      const resolved = resolveLink(rel, link.target).path;
      if (resolved.endsWith('.md') && resolved.includes('/stories/')) {
        target = resolved;
        break;
      }
    }
    if (!target) {
      v.push({ path: rel, line, message: 'Stories row has no link to a story file' });
    } else if (!target.startsWith(storyDir) || !expected.has(target)) {
      v.push({ path: rel, line, message: `Stories row links ${target}, which is not a file in this epic's stories/ folder` });
    } else if (linked.has(target)) {
      v.push({ path: rel, line, message: `Stories table links ${target} more than once` });
    } else {
      linked.set(target, line);
    }
  });
  if (!hasTotals) v.push({ path: rel, line: toDoc(table.line), message: 'Stories table has no totals row' });
  for (const storyRel of expected) {
    if (!linked.has(storyRel)) v.push({ path: rel, line: toDoc(table.line), message: `Stories table does not link story file ${storyRel}` });
  }

  const tableEnd = toDoc(table.rowLines.length ? table.rowLines[table.rowLines.length - 1] : table.line + 1);
  const nonBlank = lines.map((l, i) => ({ text: normaliseLabel(l), line: i + 1 })).filter((l) => l.text !== '');
  const [owner, status] = nonBlank.slice(-2);
  // No input names an epic owner or a workflow state, so both carry TBD: any other owner
  // is one that "Enforce Implementation Plan, Epic and Story Structure — Base" calls invented.
  const found = (l) => (l ? `; found "${l.text}"` : '');
  if (!owner || owner.text !== 'Owner: TBD' || owner.line <= tableEnd) {
    v.push({ path: rel, line: owner?.line, message: `the line before the last non-blank line must be "Owner: TBD", after the Stories table${found(owner)}` });
  }
  if (!status || status.text !== 'Status: TBD' || status.line <= tableEnd) {
    v.push({ path: rel, line: status?.line, message: `the last non-blank line must be "Status: TBD", after the Stories table${found(status)}` });
  }
  return name;
}

/** Group S: title line and parent epic line, whose epic name equals the epic README title name. */
function checkTitleAndParent(story, lines, ctx, v) {
  const nonBlank = lines.map((l, i) => ({ text: l.trim(), line: i + 1 })).filter((l) => l.text !== '');
  const [title, parent] = nonBlank;
  const titleMatch = title ? STORY_TITLE_RE.exec(title.text) : null;
  if (!titleMatch) {
    v.push({ path: story.rel, line: title?.line, message: `first line must be the title "# ${story.key}: <title>"` });
  } else if (titleMatch[1] !== story.key) {
    v.push({ path: story.rel, line: title.line, message: `title KEY ${titleMatch[1]} differs from the filename identifier ${story.key}` });
  }
  const parentMatch = parent ? PARENT_EPIC_RE.exec(parent.text) : null;
  if (!parentMatch) {
    v.push({ path: story.rel, line: parent?.line, message: `second line must be "Parent epic: [${story.epic.id} <epic name>](../README.md)"` });
    return;
  }
  if (parentMatch[1] !== story.epic.id) {
    v.push({ path: story.rel, line: parent.line, message: `parent epic line names ${parentMatch[1]}, not the folder's epic ${story.epic.id}` });
  }
  const parentName = normaliseSpace(parentMatch[2]);
  const expectedName = ctx.epicNames.get(story.epic.id);
  if (expectedName !== undefined && parentName !== expectedName) {
    v.push({ path: story.rel, line: parent.line, message: `parent epic line names the epic "${parentName}", not the epic README title name "${expectedName}"` });
  }
  if (resolveLink(story.rel, parentMatch[3]).path !== story.epic.readme) {
    v.push({ path: story.rel, line: parent.line, message: `parent epic link does not resolve to ${story.epic.readme}` });
  }
}

/**
 * Groups S and A on the reproduced source body: every line quoted, sections 1–3 present
 * once each in order, story statement, trace, classification lines and AC1–ACn with their
 * authorization and resilience obligations. Returns the AC identifiers, or null when
 * section 2 cannot be read.
 */
function checkSourceBody(story, text, lines, v) {
  const rel = story.rel;
  const source = h2Section(text, 'Source Story (verbatim)');
  if (!source) return null;
  for (let l = source.startLine + 1; l <= source.endLine; l++) {
    const line = lines[l - 1];
    if (line.trim() !== '' && !line.startsWith('>')) {
      v.push({ path: rel, line: l, message: 'reproduced source body line is not quoted; every source body line starts with ">"' });
    }
  }

  // Line k (1-based) of the stripped body is document line offset + k.
  const offset = source.startLine;
  const body = stripBlockquote(lines.slice(source.startLine, source.endLine).join('\n'));
  const bodyLines = splitLines(body);
  const bodyFences = fenceLineSet(body);
  const level3 = parseHeadings(body).filter((h) => h.level === 3);
  let maxIndex = -1;
  const firstSeen = new Set();
  for (const h of level3) {
    const name = normaliseSpace(h.text);
    const index = BODY_SECTIONS.indexOf(name);
    if (index < 0) continue;
    if (firstSeen.has(name)) {
      v.push({ path: rel, line: offset + h.line, message: `duplicate source body heading "### ${name}"` });
      continue;
    }
    firstSeen.add(name);
    if (index < maxIndex) {
      v.push({ path: rel, line: offset + h.line, message: `source body heading "### ${name}" is out of order; it must precede "### ${BODY_SECTIONS[maxIndex]}"` });
    } else {
      maxIndex = index;
    }
  }
  for (const name of BODY_SECTIONS) {
    if (!firstSeen.has(name)) v.push({ path: rel, line: source.startLine, message: `source body has no "### ${name}" heading` });
  }

  const details = firstSeen.has(BODY_SECTIONS[0]) ? sectionByHeading(body, BODY_SECTIONS[0]) : null;
  if (details) {
    const k = splitLines(details.text).findIndex((l, i) => i > 0 && l.trim() !== '');
    const statementLine = k < 0 ? details.startLine : details.startLine + k;
    const statement = k < 0 ? '' : normaliseLabel(splitLines(details.text)[k]);
    if (!STATEMENT_RE.test(statement)) {
      v.push({ path: rel, line: offset + statementLine, message: 'section 1 must open with the story statement "As a <role>, I want <capability>, so that <outcome>."' });
    }
  }

  const labelled = (label) =>
    bodyLines
      .map((l, i) => ({ text: normaliseLabel(l), line: i + 1 }))
      .filter((l) => !bodyFences.has(l.line) && l.text.startsWith(label));
  const traces = labelled('Trace:');
  if (traces.length !== 1) {
    v.push({ path: rel, line: source.startLine, message: `source body carries ${traces.length} "Trace:" lines; exactly one is required` });
  }
  for (const t of traces) {
    if (!TRACE_RE.test(t.text.replace(/`/g, ''))) {
      v.push({ path: rel, line: offset + t.line, message: 'trace must read "Trace: CARD_REQ_<NNN|TBD> · <Use Cases|Data Layouts|Requirements Analysis> · <anchor>"' });
    }
  }
  const classification = (label, re) => {
    const found = labelled(label);
    if (found.length !== 1) {
      v.push({ path: rel, line: source.startLine, message: `source body carries ${found.length} "${label}" lines; exactly one is required` });
      return null;
    }
    const m = re.exec(found[0].text);
    if (!m) v.push({ path: rel, line: offset + found[0].line, message: `classification line must read "${label} Yes" or "${label} No"` });
    return m ? m[1] : null;
  };
  const access = classification(ACCESS_LABEL, ACCESS_RE);
  const external = classification(EXTERNAL_LABEL, EXTERNAL_RE);

  const acSection = firstSeen.has(BODY_SECTIONS[1]) ? sectionByHeading(body, BODY_SECTIONS[1]) : null;
  if (!acSection) return null;
  const acHeadLine = offset + acSection.startLine;
  const acs = [];
  splitLines(acSection.text).forEach((l, k) => {
    const bodyLine = acSection.startLine + k;
    if (k === 0 || bodyFences.has(bodyLine)) return;
    const m = AC_START_RE.exec(l);
    if (m) acs.push({ num: Number(m[1]), line: offset + bodyLine, text: l });
    else if (acs.length) acs[acs.length - 1].text += `\n${l}`;
  });
  if (acs.length === 0) {
    v.push({ path: rel, line: acHeadLine, message: 'section 2 holds no acceptance criterion numbered AC1–ACn' });
    return [];
  }
  const nums = new Set();
  let maxNum = 0;
  for (const ac of acs) {
    if (nums.has(ac.num)) v.push({ path: rel, line: ac.line, message: `duplicate acceptance criterion AC${ac.num}` });
    else if (ac.num < maxNum) v.push({ path: rel, line: ac.line, message: `acceptance criterion AC${ac.num} is out of order after AC${maxNum}` });
    nums.add(ac.num);
    maxNum = Math.max(maxNum, ac.num);
  }
  for (let n = 1; n <= maxNum; n++) {
    if (!nums.has(n)) v.push({ path: rel, line: acHeadLine, message: `acceptance criterion AC${n} is missing; criteria run AC1–AC${maxNum} with no gap` });
  }

  if (access === 'Yes' && !acs.some((ac) => /\b403\b|PERMISSION_DENIED/.test(ac.text))) {
    v.push({ path: rel, line: acHeadLine, message: 'account-data access path story: no acceptance criterion requires 403 PERMISSION_DENIED for an account outside the caller\'s entitlements' });
  }
  if (external === 'Yes') {
    const all = acs.map((ac) => ac.text).join('\n');
    const obligations = [
      ['the 300 ms per-attempt timeout', /300\s*ms/.test(all)],
      ['the retry cap', /retr(?:y|ies)/i.test(all)],
      ['the 900 ms request deadline', /900\s*ms/.test(all)],
      ['a degrade or fail-hard outcome (STALE, PARTIAL, 503, 504, degrade or fail)', /STALE|PARTIAL|503|504/.test(all) || /degrad|fail/i.test(all)],
    ];
    for (const [what, present] of obligations) {
      if (!present) v.push({ path: rel, line: acHeadLine, message: `external integration or SLA-bound story: acceptance criteria do not state ${what}` });
    }
  }
  return [...nums].sort((a, b) => a - b).map((n) => `AC${n}`);
}

/** Group D: Design links and Test Plan thresholds. */
function checkDesignAndTestPlan(story, text, v) {
  const rel = story.rel;
  const design = h2Section(text, '4. Design');
  if (design) {
    const targets = parseLinks(design.text).map((l) => resolveLink(rel, l.target).path);
    if (!targets.some((t) => t.endsWith('.mmd'))) {
      v.push({ path: rel, line: design.startLine, message: 'Design links no architecture diagram (.mmd)' });
    }
    if (!targets.includes('docs/data-model.md')) {
      v.push({ path: rel, line: design.startLine, message: 'Design links no section of docs/data-model.md' });
    }
    const contract = targets.some((t) => t.endsWith('.proto') || (t.startsWith(OPENAPI_DIR) && /\.ya?ml$/.test(t)));
    if (!contract && !design.text.includes('Contracts: N/A')) {
      v.push({ path: rel, line: design.startLine, message: 'Design links no .proto or OpenAPI contract and does not state "Contracts: N/A"' });
    }
  }
  const testPlan = h2Section(text, '6. Test Plan');
  if (testPlan) {
    const lower = testPlan.text.toLowerCase();
    for (const suite of ['Unit', 'Integration', 'Performance', 'End-to-end']) {
      if (!lower.includes(suite.toLowerCase())) v.push({ path: rel, line: testPlan.startLine, message: `Test Plan does not name the ${suite} suite` });
    }
    if (!testPlan.text.includes('80%')) v.push({ path: rel, line: testPlan.startLine, message: 'Test Plan does not state the 80% line coverage threshold' });
    if (!/1,?000 ms/.test(testPlan.text)) v.push({ path: rel, line: testPlan.startLine, message: 'Test Plan does not state the 1,000 ms latency threshold' });
  }
}

/** The first "Option A" or "Option B" named in a block of lines, skipping fenced lines. */
function firstOptionNamed(lines, firstDocLine, fences) {
  for (let k = 0; k < lines.length; k++) {
    if (fences.has(firstDocLine + k)) continue;
    const m = OPTION_MENTION_RE.exec(lines[k]);
    if (m) return m[1];
  }
  return null;
}

/** Group D: two design options, one Recommendation and a framing line naming the same option. */
function checkImplementationDesign(story, impl, fences, v) {
  const rel = story.rel;
  const heads = sectionHeadings(impl).filter((h) => h.level >= 3);
  const options = heads.filter((h) => plainCell(h.text).startsWith('Design Option'));
  if (options.length !== 2) {
    v.push({ path: rel, line: impl.startLine, message: `Implementation Design carries ${options.length} design options; exactly two, Design Option A and Design Option B, are required` });
  }
  if (!options.some((h) => plainCell(h.text).startsWith('Design Option A'))) {
    v.push({ path: rel, line: impl.startLine, message: 'Implementation Design has no "Design Option A" heading' });
  }
  if (!options.some((h) => plainCell(h.text).startsWith('Design Option B'))) {
    v.push({ path: rel, line: impl.startLine, message: 'Implementation Design has no "Design Option B" heading' });
  }
  const recommendations = heads.filter((h) => plainCell(h.text).startsWith('Recommendation'));
  if (recommendations.length !== 1) {
    v.push({ path: rel, line: impl.startLine, message: `Implementation Design carries ${recommendations.length} Recommendation headings; exactly one is required` });
  }

  let recommended = null;
  if (recommendations.length >= 1) {
    const rec = subsection(impl, heads, recommendations[0]);
    recommended = firstOptionNamed(splitLines(rec.text).slice(1), rec.startLine + 1, fences);
    if (!recommended) v.push({ path: rel, line: rec.startLine, message: 'Recommendation names no option (Option A or Option B)' });
  }

  const implLines = splitLines(impl.text);
  const k = implLines.findIndex((l, i) => i > 0 && l.trim() !== '' && !fences.has(impl.startLine + i));
  const framingLine = impl.startLine + Math.max(k, 0);
  const framed = k < 0 ? null : firstOptionNamed([implLines[k]], framingLine, new Set());
  if (!framed) {
    v.push({ path: rel, line: framingLine, message: 'framing line of Implementation Design names no option (Option A or Option B)' });
  } else if (recommended && framed !== recommended) {
    v.push({ path: rel, line: framingLine, message: `framing line names Option ${framed} but the Recommendation names Option ${recommended}` });
  }
}

/** Group D and voice: at most one Mermaid diagram, a sequence diagram inside Implementation Design. */
function checkMermaid(story, text, impl, v) {
  const rel = story.rel;
  const blocks = fencedBlocks(text).filter((b) => b.lang === 'mermaid');
  blocks.forEach((block, i) => {
    if (i > 0) {
      v.push({ path: rel, line: block.startLine, message: `Mermaid diagram ${i + 1} of ${blocks.length}; a story carries at most one` });
    }
    if (!impl || block.startLine < impl.startLine || block.endLine > impl.endLine) {
      v.push({ path: rel, line: block.startLine, message: 'Mermaid diagram lies outside Implementation Design' });
    }
    const bodyLines = splitLines(block.body);
    const first = bodyLines.find((l) => l.trim() !== '');
    if (!first || first.trim() !== 'sequenceDiagram') {
      v.push({ path: rel, line: block.startLine, message: 'Mermaid diagram is not a sequence diagram; its first line must be "sequenceDiagram"' });
    }
    bodyLines.forEach((l, k) => {
      const m = /^\s*(?:participant|actor)\s+(\S+?)(?:\s+as\s+(.+?))?\s*$/.exec(l);
      if (m) checkVoiceText(m[2] ?? m[1], rel, block.startLine + 1 + k, v);
    });
  });
}

/** Group D: per-option settled and open assumptions and an AC verdict table covering every AC. */
function checkDesignValidation(story, validation, acIds, v) {
  const rel = story.rel;
  const heads = sectionHeadings(validation).filter((h) => h.level >= 3);
  for (const option of ['A', 'B']) {
    const matching = heads.filter((h) => plainCell(h.text).includes(`Option ${option}`));
    if (matching.length !== 1) {
      v.push({ path: rel, line: validation.startLine, message: `Design Validation carries ${matching.length} headings naming Option ${option}; exactly one is required` });
      if (matching.length === 0) continue;
    }
    const sub = subsection(validation, heads, matching[0]);
    const where = { path: rel, line: sub.startLine };
    if (!/\bSettled\b/.test(sub.text)) v.push({ ...where, message: `Option ${option} validation states no settled assumptions` });
    if (!splitLines(sub.text).some((l) => l.includes('Open →'))) {
      v.push({ ...where, message: `Option ${option} validation has no open assumption written as "Open → what settles it"` });
    }
    const tables = parseTables(sub.text).filter(
      (t) => t.header.length === 3 && t.header.map((c) => plainCell(c).toLowerCase()).join('|') === 'ac|verdict|why',
    );
    if (tables.length === 0) {
      v.push({ ...where, message: `Option ${option} validation has no "AC | Verdict | Why" table` });
      continue;
    }
    const rows = new Map();
    for (const t of tables) {
      t.rows.forEach((row, i) => {
        const line = sub.startLine + t.rowLines[i] - 1;
        const ac = plainCell(row[0]);
        if (!/^AC\d+$/.test(ac)) {
          v.push({ path: rel, line, message: `Option ${option} verdict row does not start with an AC identifier: "${ac}"` });
          return;
        }
        if (rows.has(ac)) v.push({ path: rel, line, message: `Option ${option} verdict table repeats ${ac}` });
        else rows.set(ac, line);
        const verdict = plainCell(row[1]);
        if (!VERDICTS.has(verdict)) {
          v.push({ path: rel, line, message: `Option ${option} verdict for ${ac} is "${verdict}"; verdicts are Holds, Conditional, At risk or Breaks` });
        }
      });
    }
    if (acIds === null) continue;
    for (const ac of acIds) {
      if (!rows.has(ac)) v.push({ ...where, message: `Option ${option} verdict table has no row for ${ac}` });
    }
    for (const [ac, line] of rows) {
      if (!acIds.includes(ac)) v.push({ path: rel, line, message: `Option ${option} verdict table names ${ac}, which section 2 does not define` });
    }
  }
}

const mask = (s, start, end) => s.slice(0, start) + ' '.repeat(end - start) + s.slice(end);
const trimToken = (t) => t.trim().replace(/[.,;:!?)\]]+$/, '');

/**
 * Voice: one violation per link, code span, path, code identifier, config key or maturity
 * tag in one line of design prose. Each reported span is masked before the next test so a
 * token is reported once.
 */
function checkVoiceText(raw, rel, line, v) {
  let s = String(raw);
  const flag = (kind, token) => v.push({ path: rel, line, message: `design voice: ${kind} "${token}"` });
  for (const [kind, re] of [['link', LINK_RE], ['backticked span', CODE_SPAN_RE]]) {
    for (const m of [...s.matchAll(re)]) {
      flag(kind, m[0]);
      s = mask(s, m.index, m.index + m[0].length);
    }
  }

  const spans = [];
  for (const re of PATH_RES) {
    for (const m of s.matchAll(re)) spans.push([m.index, m.index + m[0].length]);
  }
  spans.sort((a, b) => a[0] - b[0] || b[1] - a[1]);
  const merged = [];
  for (const span of spans) {
    const last = merged[merged.length - 1];
    if (last && span[0] < last[1]) last[1] = Math.max(last[1], span[1]);
    else merged.push([...span]);
  }
  for (const [start, end] of merged) flag('path', trimToken(s.slice(start, end)));
  for (const [start, end] of merged) s = mask(s, start, end);

  s = s.replace(PROPER_NAME_RE, (name) => ' '.repeat(name.length));
  for (const [kind, re] of TOKEN_TESTS) {
    for (const m of [...s.matchAll(re)]) {
      flag(kind, m[0]);
      s = mask(s, m.index, m.index + m[0].length);
    }
  }
}

/** Voice over a whole design section: every line outside fenced code, then every table cell. */
function checkVoiceSection(story, section, fences, v) {
  const rel = story.rel;
  splitLines(section.text).forEach((l, k) => {
    const line = section.startLine + k;
    if (!fences.has(line)) checkVoiceText(l, rel, line, v);
  });
  for (const table of parseTables(section.text)) {
    const rows = [table.header, ...table.rows];
    const rowLines = [table.line, ...table.rowLines];
    rows.forEach((row, r) => {
      const line = section.startLine + rowLines[r] - 1;
      for (const cell of row) {
        const plain = plainCell(cell);
        if (plain.includes(';')) v.push({ path: rel, line, message: `design voice: table cell with a semicolon "${plain}"` });
        if (hasSecondSentence(plain)) v.push({ path: rel, line, message: `design voice: table cell with a second sentence "${plain}"` });
      }
    });
  }
}

/**
 * Group M: the metadata block holds each field once and no other field, every value is
 * non-blank, the supplied values and TBD defaults hold, and points, files touched, wave,
 * epic and dependencies are valid. A finding about a value names the line that gives it.
 */
function checkMetadata(story, text, ctx, v) {
  const rel = story.rel;
  const section = h2Section(text, 'Metadata');
  if (!section) return;
  const fieldByKey = new Map(METADATA_FIELDS.map((field) => [normaliseSpace(field).toLowerCase(), field]));
  // The first entry of each field is the one read; a repeat is reported at its own line.
  const first = new Map();
  for (const entry of metadataEntries(section.text)) {
    const line = section.startLine + entry.line - 1;
    const field = fieldByKey.get(entry.key);
    if (field === undefined) {
      v.push({ path: rel, line, message: `unexpected metadata field "${entry.key}"; the metadata block holds only ${METADATA_FIELDS.join(', ')}` });
    } else if (first.has(field)) {
      v.push({ path: rel, line, message: `metadata field "${field}" is given again; it is first given at line ${first.get(field).line}` });
    } else {
      first.set(field, { line, value: plainCell(entry.value) });
    }
  }
  // A missing or blank field is reported once here; the value checks read only the rest.
  const given = new Map();
  for (const field of METADATA_FIELDS) {
    const entry = first.get(field);
    if (!entry) v.push({ path: rel, line: section.startLine, message: `metadata field "${field}" is missing` });
    else if (entry.value === '') v.push({ path: rel, line: entry.line, message: `metadata field "${field}" has no value` });
    else given.set(field, entry);
  }
  const value = (field) => (given.has(field) ? given.get(field).value : null);
  const at = (field) => ({ path: rel, line: given.get(field).line });

  for (const [field, expected] of SUPPLIED_METADATA) {
    const actual = value(field);
    if (actual !== null && actual !== expected) {
      v.push({ ...at(field), message: `${field} "${actual}" is not ${expected}, the value the request supplies` });
    }
  }
  for (const field of TBD_METADATA_FIELDS) {
    const actual = value(field);
    if (actual !== null && actual !== 'TBD') {
      v.push({ ...at(field), message: `${field} "${actual}" is not TBD; no input supplies a value for it` });
    }
  }

  const points = value('Story Points');
  if (points !== null) {
    const n = intStrict(points);
    if (n === null || n < 1 || n > 5) v.push({ ...at('Story Points'), message: `Story Points "${points}" is not an integer from 1 to 5` });
  }
  const files = value('Estimated Files Touched');
  if (files !== null && intStrict(files) === null) {
    v.push({ ...at('Estimated Files Touched'), message: `Estimated Files Touched "${files}" is not a single integer` });
  }
  const wave = value('Build wave');
  if (wave !== null && !WAVES.has(wave)) v.push({ ...at('Build wave'), message: `Build wave "${wave}" is not one of W0, W1, W2, W3` });

  const epicLink = value('Epic Link');
  if (epicLink !== null && epicLink !== story.epic.id) {
    v.push({ ...at('Epic Link'), message: `Epic Link "${epicLink}" differs from the parent epic ${story.epic.id}` });
  }
  const epicName = value('Epic Name');
  const expectedName = ctx.epicNames.get(story.epic.id);
  if (epicName !== null && expectedName && epicName !== expectedName) {
    v.push({ ...at('Epic Name'), message: `Epic Name "${epicName}" differs from the epic README title "${expectedName}"` });
  }

  // Depends on is exactly None or a comma-separated list of distinct story identifiers.
  // Each member is matched whole, so text beside an identifier is reported, never dropped.
  const dependsOn = value('Depends on');
  if (dependsOn !== null && dependsOn !== 'None') {
    const where = at('Depends on');
    const ids = new Set();
    dependsOn.split(',').forEach((raw, k) => {
      const member = raw.trim();
      const position = `member ${k + 1} of Depends on "${dependsOn}"`;
      if (member === '') {
        v.push({ ...where, message: `${position} is empty (a leading, trailing or doubled comma)` });
      } else if (member === 'None') {
        v.push({ ...where, message: `${position} is None inside a list; None stands alone, never beside story identifiers` });
      } else if (!DEPENDS_ON_ID_RE.test(member)) {
        v.push({ ...where, message: `${position} is "${member}", not a story identifier CS-NN-NNN; the value is None or story identifiers separated by commas` });
      } else if (ids.has(member)) {
        v.push({ ...where, message: `${position} repeats ${member}` });
      } else {
        ids.add(member);
      }
    });
    for (const id of ids) {
      if (id === story.key) v.push({ ...where, message: `Depends on names the story itself (${id})` });
      else if (!ctx.storyKeys.has(id)) v.push({ ...where, message: `Depends on names ${id}, which has no story file` });
    }
  }
}

/** All story predicates for one story file. */
function checkStory(story, ctx, v) {
  const text = readText(story.rel);
  const lines = splitLines(text);
  const fences = fenceLineSet(text);

  checkTitleAndParent(story, lines, ctx, v);
  const h2 = parseHeadings(text).filter((h) => !h.quoted && h.level === 2);
  checkHeadingSequence(h2, STORY_SECTIONS, story.rel, '##', v);
  if (h2.some((h) => normaliseSpace(h.text) === 'Metadata') && normaliseSpace(h2[h2.length - 1].text) !== 'Metadata') {
    v.push({ path: story.rel, line: h2[h2.length - 1].line, message: '"## Metadata" must be the last level-2 section' });
  }

  const acIds = checkSourceBody(story, text, lines, v);
  checkDesignAndTestPlan(story, text, v);

  const impl = h2Section(text, 'Implementation Design');
  if (impl) {
    checkImplementationDesign(story, impl, fences, v);
    checkVoiceSection(story, impl, fences, v);
  }
  checkMermaid(story, text, impl, v);
  const validation = h2Section(text, 'Design Validation');
  if (validation) {
    checkDesignValidation(story, validation, acIds, v);
    checkVoiceSection(story, validation, fences, v);
  }
  checkMetadata(story, text, ctx, v);
}

function main() {
  const v = [];
  const { files, epics, storyKeys } = checkPaths(v);
  const epicNames = new Map();
  for (const epic of epics) {
    if (!files.has(epic.readme)) continue;
    const name = checkEpicReadme(epic, v);
    if (name !== null) epicNames.set(epic.id, name);
  }
  checkEpicIndex(files, epics, epicNames, v);
  const ctx = { storyKeys, epicNames };
  for (const epic of epics) {
    for (const story of epic.stories) checkStory(story, ctx, v);
  }
  report(CHECK, v);
}

const HELP_FLAGS = new Set(['--help', '-h']);
const USAGE = `Usage: node tests/validation/check-backlog-structure.mjs [--help | -h]

Checks the backlog structure and story content under ${EPICS_DIR}/. It takes no
arguments: --help or -h prints this message and exits 0 without scanning, and any other
argument is a usage error. It runs from any directory, because every path resolves
against the repository root derived from this script's own location.

It scans every file and folder under ${EPICS_DIR}/ and nothing else: the epic
index ${INDEX_README}, each epic folder's README.md and the story files in
its stories/ folder. It never reads app/, diagrams/ or samples/.

Output on stdout, one line per violation, then the count:
  ${CHECK}: <path>[:<line>]: <message>
  ${CHECK}: <N> violation(s)

Exit status: 0 with no violation, 1 with one or more, 2 on a usage or internal error.`;

/**
 * The command-line contract, applied before any scan. With no argument the scan runs.
 * Help flags alone print the usage on stdout. Any other argument, alone or beside a help
 * flag, is a usage error: it is named on stderr with the usage, and the exit status is 2.
 * Returns true only when the scan should run.
 */
function acceptArguments(args) {
  if (args.length === 0) return true;
  const unknown = args.filter((arg) => !HELP_FLAGS.has(arg));
  if (unknown.length === 0) {
    console.log(USAGE);
  } else {
    console.error(`${CHECK}: unknown argument(s): ${unknown.map((arg) => JSON.stringify(arg)).join(', ')}`);
    console.error(USAGE);
    process.exitCode = 2;
  }
  return false;
}

try {
  if (acceptArguments(process.argv.slice(2))) main();
} catch (err) {
  console.error(`${CHECK}: internal error`);
  console.error(err && err.stack ? err.stack : String(err));
  process.exitCode = 2;
}

// SPDX-License-Identifier: Apache-2.0
/**
 * Dependency Map and ordering check, run sixth by tests/validation/run-all.sh as
 * `node tests/validation/check-dependency-order.mjs` from the repository root.
 *
 * Reads docs/implementation-plan.md (wave story tables and the Dependency Map), the
 * Metadata "Depends on" field of every story file, the epics index and the Dependencies
 * section of every epic README, and verifies that:
 *   1. story metadata yields the story edge set E and names only existing stories;
 *   2. the story-edge table holds exactly E, each row with a valid reason type and reason;
 *   3. the epic-dependency table holds exactly the epic pairs derived from E, each row
 *      with exactly its producing story edges and the union of their reason types;
 *   4. no story is positioned before a prerequisite, and every dependency a wave table
 *      lists appears in the Dependency Map;
 *   5. every epic README's Dependencies section lists exactly its epic-dependency rows;
 *   6. the edge and pair counts equal the backlog inventory.
 * Every table row it reads is also held to its header's width: a row with more parsed cells
 * than its header is reported, because a renderer drops the cells no comparison reads.
 * Prints one line per violation and a final count, then exits 0 when clean, 1 on any
 * violation and 2 on an internal error. A failure is fixed in the artifacts, never by
 * loosening this check.
 * Takes no arguments: -h or --help prints usage and exits 0; any other argument exits 2.
 */
import path from 'node:path';
import {
  exists,
  fencedBlocks,
  intStrict,
  isTotalsRow,
  listFiles,
  parseHeadings,
  parseMetadata,
  parseTables,
  pathKind,
  plainText,
  readText,
  report,
  repoRoot,
  sectionByHeading,
  sections,
  surplusCellRows,
} from './lib/markdown.mjs';

const CHECK = 'check-dependency-order';
const PLAN = 'docs/implementation-plan.md';
const EPICS_DIR = 'docs/backlog/epics';
const EPIC_INDEX = `${EPICS_DIR}/README.md`;

// Backlog inventory: 102 story dependency edges (AAP 0.5.6.2), summarised as 37 ordered
// epic pairs in the Dependency Map's epic-dependency table (AAP 0.5.6.9).
const EXPECTED_STORY_EDGES = 102;
const EXPECTED_EPIC_PAIRS = 37;

const REASON_TYPES = Object.freeze([
  'data model',
  'upstream service',
  'stubbed integration',
  'shared component',
  'test harness',
]);

const STORY_ID_RE = /\bCS-\d{2}-\d{3}\b/g;
const EPIC_ID_RE = /\bCS-\d{2}\b(?!-\d)/g;
const EDGE_PAIR_RE = /\b(CS-\d{2}-\d{3})\b\s*(?:→|->)\s*\b(CS-\d{2}-\d{3})\b/g;

const STORY_FILE_RE = /^docs\/backlog\/epics\/[^/]+\/stories\/[^/]+\.md$/;
const EPIC_README_RE = /^docs\/backlog\/epics\/(CS-\d{2})-[^/]+\/README\.md$/;
const LIST_ITEM_RE = /^[ \t]{0,3}(?:[-*+]|\d{1,9}[.)])[ \t]+(.*)$/;
const HEADING_LINE_RE = /^[ \t]{0,3}#{1,6}(?:[ \t]|$)/;

// Edge text grammar: an arrow (`→` or `->`) selects the pair form, otherwise the text is a
// story ID list. A story-ID-like token, malformed or not, marks text as naming stories.
const EDGE_ARROW_RE = /→|->/;
const EDGE_SEPARATOR_RE = /[,;]/;
const WORD_CHAR_RE = /\w/;
const WHOLE_STORY_ID_RE = /^CS-\d{2}-\d{3}$/;
const STORY_ID_LIKE_RE = /CS-\d+-\d/;

const STORY_EDGE_COLUMNS = ['story', 'prerequisite', 'reason type', 'reason'];
const EPIC_PAIR_COLUMNS = ['dependent epic', 'prerequisite epic', 'story edges', 'reason types'];
const POSITION_COLUMNS = ['pos.', 'pos', 'position'];

// Words that mean "no prerequisite": story metadata writes None; wave tables also use a dash.
const METADATA_NONE = Object.freeze(['None']);
const WAVE_TABLE_NONE = Object.freeze(['—', '–', '-', 'None']);

const normalise = (s) => plainText(String(s ?? '')).replace(/\s+/g, ' ').trim().toLowerCase();
const storyIdsIn = (text) => String(text).match(STORY_ID_RE) ?? [];
const epicIdsIn = (text) => String(text).match(EPIC_ID_RE) ?? [];
const epicOf = (storyId) => storyId.slice(0, 5);
const edgeKey = (story, prerequisite) => `${story} → ${prerequisite}`;
const pairKey = (dependentEpic, prerequisiteEpic) => `${dependentEpic} → ${prerequisiteEpic}`;
const distinct = (items) => [...new Set(items)];

/**
 * The whole visible text of a table cell: every link reduced to its link text and all other
 * content kept, with whitespace collapsed and trimmed, so nothing after a link is dropped.
 */
function cellIdText(cell) {
  return plainText(cell).replace(/\s+/g, ' ').trim();
}

function columnOf(header, names) {
  const normalised = header.map(normalise);
  for (const name of names) {
    const index = normalised.indexOf(name);
    if (index >= 0) return index;
  }
  return -1;
}

/**
 * The one ID a table cell names, matched by `re`. The cell's whole visible text must be
 * exactly that ID: an empty cell, several IDs, or any other content (a suffix or prefix
 * such as CS-11-0010 or XCS-05-001, trailing text after a linked ID, a repeated ID) is an
 * error, so a malformed cell never yields an ID.
 */
function singleId(cell, re, kind) {
  const text = cellIdText(cell);
  const found = distinct(text.match(re) ?? []);
  if (found.length === 1 && found[0] === text) return { id: found[0] };
  if (text === '') return { error: `names no ${kind} ID` };
  if (found.length > 1) return { error: `names ${found.length} ${kind} IDs (${found.join(', ')})` };
  return { error: `holds "${text}", which is not a single ${kind} ID` };
}

const singleStoryId = (cell) => singleId(cell, STORY_ID_RE, 'story');
const singleEpicId = (cell) => singleId(cell, EPIC_ID_RE, 'epic');

/**
 * Story IDs of a comma-separated list such as "CS-11-001, CS-05-002", or none for one of
 * `noneWords`. Any other text in the list is an error, so a mistyped ID is never dropped.
 */
function parseIdList(value, noneWords) {
  const plain = plainText(String(value ?? '')).replace(/\s+/g, ' ').trim();
  if (noneWords.some((word) => word.toLowerCase() === plain.toLowerCase())) return { ids: [] };
  const ids = storyIdsIn(plain);
  const allowed = noneWords.map((word) => `"${word}"`).join(' or ');
  if (ids.length === 0) return { ids, error: `"${plain}" names no story ID and is not ${allowed}` };
  if (plain.replace(STORY_ID_RE, '').replace(/[\s,;]/g, '') !== '') {
    return { ids, error: `"${plain}" holds text other than comma-separated story IDs` };
  }
  return { ids };
}

/**
 * The single table of a section whose header carries every column in `columns`, with
 * those columns' indexes and the offset that turns its line numbers into plan lines. Each
 * of its rows with more cells than its header is reported at its plan line.
 */
function findTable(section, columns, label, add) {
  const offset = section.startLine - 1;
  const matches = parseTables(section.text).filter((t) => columns.every((c) => columnOf(t.header, [c]) >= 0));
  if (matches.length === 0) {
    add(PLAN, section.startLine, `"${section.heading}" has no ${label} table with columns ${columns.join(', ')}`);
    return null;
  }
  if (matches.length > 1) {
    add(PLAN, offset + matches[1].line, `"${section.heading}" holds a second ${label} table; only the one at line ${offset + matches[0].line} is read`);
  }
  const table = matches[0];
  for (const s of surplusCellRows(table, `${label} table`)) add(PLAN, offset + s.line, s.message);
  const col = Object.fromEntries(columns.map((c) => [c, columnOf(table.header, [c])]));
  return { table, col, offset };
}

// Phase 1: the story edge set E, from every story's Metadata "Depends on".
function loadStories(backlogFiles, add) {
  const stories = new Map();
  for (const file of backlogFiles) {
    if (!STORY_FILE_RE.test(file)) continue;
    const m = /^CS-\d{2}-\d{3}(?!\d)/.exec(path.posix.basename(file));
    if (!m) {
      add(file, 1, 'story file name does not start with a story ID (CS-NN-NNN)');
      continue;
    }
    const id = m[0];
    if (stories.has(id)) {
      add(file, 1, `story ID ${id} is also carried by ${stories.get(id).path}`);
      continue;
    }
    const story = { id, path: file, prereqs: [], line: undefined };
    stories.set(id, story);
    const meta = sectionByHeading(readText(file), 'Metadata');
    if (!meta) {
      add(file, 1, 'no "Metadata" section, so Depends on cannot be read');
      continue;
    }
    const fieldLine = meta.text.split(/\r?\n/).findIndex((line) => /depends on/i.test(line));
    story.line = fieldLine < 0 ? meta.startLine : meta.startLine + fieldLine;
    const value = parseMetadata(meta.text)['depends on'];
    if (value === undefined) {
      add(file, meta.startLine, 'Metadata has no Depends on field');
      continue;
    }
    const list = parseIdList(value, METADATA_NONE);
    if (list.error) add(file, story.line, `Metadata Depends on ${list.error}`);
    story.prereqs = list.ids;
  }
  return stories;
}

function collectEdges(stories, add) {
  const edges = new Map();
  for (const story of stories.values()) {
    for (const prerequisite of story.prereqs) {
      if (prerequisite === story.id) {
        add(story.path, story.line, `Metadata Depends on lists the story itself (${prerequisite})`);
        continue;
      }
      const key = edgeKey(story.id, prerequisite);
      if (edges.has(key)) {
        add(story.path, story.line, `Metadata Depends on lists ${prerequisite} more than once`);
        continue;
      }
      if (!stories.has(prerequisite)) {
        add(story.path, story.line, `Metadata Depends on names ${prerequisite}, which is not an existing story`);
      }
      edges.set(key, { s: story.id, p: prerequisite });
    }
  }
  return edges;
}

// Phase 2: the story-edge table equals E, one valid reason type and a reason per row.
function checkStoryEdgeTable(depMap, edges, add) {
  const found = findTable(depMap, STORY_EDGE_COLUMNS, 'story-edge', add);
  if (!found) return null;
  const { table, col, offset } = found;
  const rows = new Map();
  let rowCount = 0;
  table.rows.forEach((row, r) => {
    if (isTotalsRow(row)) return;
    rowCount++;
    const line = offset + table.rowLines[r];
    const story = singleStoryId(row[col.story]);
    const prerequisite = singleStoryId(row[col.prerequisite]);
    if (story.error) add(PLAN, line, `story-edge row: Story cell ${story.error}`);
    if (prerequisite.error) add(PLAN, line, `story-edge row: Prerequisite cell ${prerequisite.error}`);
    if (story.error || prerequisite.error) return;
    const key = edgeKey(story.id, prerequisite.id);
    const reasonType = normalise(row[col['reason type']]);
    const valid = REASON_TYPES.includes(reasonType);
    if (!valid) {
      add(PLAN, line, `story-edge row ${key}: reason type "${reasonType}" is not one of ${REASON_TYPES.join(', ')}`);
    }
    if (normalise(row[col.reason]) === '') add(PLAN, line, `story-edge row ${key}: Reason is empty`);
    if (rows.has(key)) {
      add(PLAN, line, `duplicate story-edge row ${key} (first at line ${rows.get(key).line})`);
      return;
    }
    rows.set(key, { s: story.id, line, reasonType: valid ? reasonType : null });
  });
  for (const [key, edge] of edges) {
    if (!rows.has(key)) {
      add(PLAN, offset + table.line, `story-edge table has no row ${key}, which ${edge.s}'s metadata Depends on lists`);
    }
  }
  for (const [key, row] of rows) {
    if (!edges.has(key)) add(PLAN, row.line, `story-edge row ${key} is not in ${row.s}'s metadata Depends on`);
  }
  return { rows, rowCount, line: offset + table.line };
}

// Phase 3: epic pairs derived from E, each with the story edges that produce it.
function derivePairs(edges) {
  const pairs = new Map();
  for (const [key, edge] of edges) {
    const de = epicOf(edge.s);
    const pe = epicOf(edge.p);
    if (de === pe) continue;
    const pk = pairKey(de, pe);
    if (!pairs.has(pk)) pairs.set(pk, { de, pe, edges: new Set() });
    pairs.get(pk).edges.add(key);
  }
  return pairs;
}

/**
 * Edges stated by arrow-form edge text: "story → story" pairs (`→` or `->`) separated only
 * by "," or ";" and whitespace. Each pair is oriented by epic membership, so "A → B" and
 * "B → A" state the same edge. Any other piece of the text (prose, another arrow form, a
 * stray or malformed ID) and any edge listed twice is a problem.
 */
function arrowPairEdges(plain, de, pe) {
  const problems = [];
  const edges = new Set();
  const residue = [];
  let from = 0;
  for (const m of plain.matchAll(EDGE_PAIR_RE)) {
    const end = m.index + m[0].length;
    // A pair glued to further word characters (CS-11-0010) is malformed text, not a pair.
    if (WORD_CHAR_RE.test(plain[m.index - 1] ?? '') || WORD_CHAR_RE.test(plain[end] ?? '')) continue;
    residue.push(plain.slice(from, m.index));
    from = end;
    const [, a, b] = m;
    let key;
    if (epicOf(a) === de && epicOf(b) === pe) key = edgeKey(a, b);
    else if (epicOf(a) === pe && epicOf(b) === de) key = edgeKey(b, a);
    else {
      problems.push(`hold pair ${a} → ${b}, which does not join a ${de} story to a ${pe} story`);
      continue;
    }
    if (edges.has(key)) problems.push(`list ${key} twice`);
    edges.add(key);
  }
  residue.push(plain.slice(from));
  for (const piece of residue.flatMap((text) => text.split(EDGE_SEPARATOR_RE)).map((text) => text.trim())) {
    if (piece === '') continue;
    problems.push(WHOLE_STORY_ID_RE.test(piece) ? `name ${piece} outside any arrow pair` : `hold "${piece}", which is not a story → story pair`);
  }
  return { edges, problems };
}

/**
 * Edges stated by a story ID list, the edge text form without an arrow: story IDs of the two
 * epics separated by commas, one side holding exactly one story, which then has an edge to
 * every story on the other side. With two or more stories on both sides the list cannot say
 * which edges it means, so it states none; nor does a list holding anything but story IDs.
 */
function idListEdges(plain, de, pe) {
  const problems = [];
  const dependents = [];
  const prerequisites = [];
  const seen = new Set();
  for (const item of plain.split(',').map((text) => text.trim())) {
    if (!WHOLE_STORY_ID_RE.test(item)) {
      problems.push(
        item === ''
          ? 'hold an empty item in a comma-separated story ID list'
          : `hold "${item}", which is not a story ID; text without an arrow must be a comma-separated story ID list`,
      );
      continue;
    }
    if (seen.has(item)) {
      problems.push(`name ${item} twice`);
      continue;
    }
    seen.add(item);
    if (epicOf(item) === de) dependents.push(item);
    else if (epicOf(item) === pe) prerequisites.push(item);
    else problems.push(`name ${item}, which is neither a ${de} nor a ${pe} story`);
  }
  if (problems.length > 0) return { edges: null, problems };
  if (dependents.length === 0 || prerequisites.length === 0) {
    return { edges: null, problems: [`name no ${dependents.length === 0 ? de : pe} story, so the ID list states no edge`] };
  }
  if (dependents.length > 1 && prerequisites.length > 1) {
    return {
      edges: null,
      problems: [
        `list ${dependents.length} ${de} and ${prerequisites.length} ${pe} stories without arrows, which cannot state which story depends on which; write each edge as a story → prerequisite pair`,
      ],
    };
  }
  const edges = new Set();
  for (const s of dependents) for (const p of prerequisites) edges.add(edgeKey(s, p));
  return { edges, problems };
}

/**
 * Story edges stated by `text`, the edge text of an epic-dependency Story edges cell or of a
 * README Dependencies entry, for the pair joining `de` (dependent epic) to `pe` (prerequisite
 * epic): arrow pairs when the text holds an arrow, else a story ID list. `edges` is null when
 * the text states no definite edge set; `problems` lists every departure from the grammar.
 */
function parseEdgeText(text, de, pe) {
  const plain = plainText(String(text ?? '')).replace(/\s+/g, ' ').trim();
  if (plain === '') return { edges: null, problems: ['name no story edge'] };
  return EDGE_ARROW_RE.test(plain) ? arrowPairEdges(plain, de, pe) : idListEdges(plain, de, pe);
}

/** Differences between the edges stated by edge text `text` and the producing edges of `pair`. */
function compareEdgeText(text, pair) {
  const { edges, problems } = parseEdgeText(text, pair.de, pair.pe);
  const out = [...problems];
  if (!edges) return out;
  for (const key of pair.edges) if (!edges.has(key)) out.push(`lack ${key}`);
  for (const key of edges) if (!pair.edges.has(key)) out.push(`list ${key}, which is not a producing story edge`);
  return out;
}

function compareReasonTypes(cell, pair, storyTable) {
  if (!storyTable) return [];
  const expected = new Set();
  for (const key of pair.edges) {
    const reasonType = storyTable.rows.get(key)?.reasonType;
    // An edge without a valid story-edge row has no known reason type; that row's own
    // violation is already reported, and the union is compared once it is fixed.
    if (!reasonType) return [];
    expected.add(reasonType);
  }
  const listed = new Set(plainText(String(cell ?? '')).split(',').map(normalise).filter(Boolean));
  const out = [];
  for (const reasonType of expected) if (!listed.has(reasonType)) out.push(`lack "${reasonType}"`);
  for (const reasonType of listed) {
    if (!expected.has(reasonType)) out.push(`list "${reasonType}", which no producing story edge carries`);
  }
  return out;
}

function checkEpicPairTable(depMap, derived, storyTable, add) {
  const found = findTable(depMap, EPIC_PAIR_COLUMNS, 'epic-dependency', add);
  if (!found) return null;
  const { table, col, offset } = found;
  const rows = new Map();
  let rowCount = 0;
  table.rows.forEach((row, r) => {
    if (isTotalsRow(row)) return;
    rowCount++;
    const line = offset + table.rowLines[r];
    const de = singleEpicId(row[col['dependent epic']]);
    const pe = singleEpicId(row[col['prerequisite epic']]);
    if (de.error) add(PLAN, line, `epic-dependency row: Dependent epic cell ${de.error}`);
    if (pe.error) add(PLAN, line, `epic-dependency row: Prerequisite epic cell ${pe.error}`);
    if (de.error || pe.error) return;
    if (de.id === pe.id) {
      add(PLAN, line, `epic-dependency row joins ${de.id} to itself`);
      return;
    }
    const pk = pairKey(de.id, pe.id);
    if (rows.has(pk)) {
      add(PLAN, line, `duplicate epic-dependency row ${pk} (first at line ${rows.get(pk).line})`);
      return;
    }
    rows.set(pk, { de: de.id, pe: pe.id, line });
    const pair = derived.get(pk);
    if (!pair) {
      add(PLAN, line, `epic-dependency row ${pk} has no producing story edge in story metadata`);
      return;
    }
    for (const m of compareEdgeText(row[col['story edges']], pair)) {
      add(PLAN, line, `epic-dependency row ${pk}: Story edges ${m}`);
    }
    for (const m of compareReasonTypes(row[col['reason types']], pair, storyTable)) {
      add(PLAN, line, `epic-dependency row ${pk}: Reason types ${m}`);
    }
  });
  for (const [pk, pair] of derived) {
    if (!rows.has(pk)) add(PLAN, offset + table.line, `epic-dependency table has no row ${pk}, produced by ${[...pair.edges].join(', ')}`);
  }
  return { rows, rowCount, line: offset + table.line };
}

// Phase 4: wave placement and ordering.
/** Level-2 wave sections (## W0 …), each ending before the next heading of level 1 or 2. */
function waveSections(plan) {
  return sections(plan, 2).flatMap((s) => {
    const m = /^W(\d+)\b/.exec(plainText(s.heading));
    return m ? [{ wave: Number(m[1]), label: `W${m[1]}`, startLine: s.startLine, text: s.text }] : [];
  });
}

function placeStories(plan, add) {
  const placed = new Map();
  const listedDeps = new Map();
  const waves = waveSections(plan);
  if (waves.length === 0) add(PLAN, 1, 'no wave sections ("## W0 …") found');
  const firstSection = new Map();
  for (const wave of waves) {
    if (firstSection.has(wave.wave)) {
      add(PLAN, wave.startLine, `second section for wave ${wave.label} (first at line ${firstSection.get(wave.wave)})`);
    } else {
      firstSection.set(wave.wave, wave.startLine);
    }
    const offset = wave.startLine - 1;
    const tables = parseTables(wave.text).filter(
      (t) => columnOf(t.header, ['story']) >= 0 && columnOf(t.header, POSITION_COLUMNS) >= 0,
    );
    if (tables.length === 0) add(PLAN, wave.startLine, `wave ${wave.label} has no story table with position and story columns`);
    for (const table of tables) {
      const iStory = columnOf(table.header, ['story']);
      const iPosition = columnOf(table.header, POSITION_COLUMNS);
      const iDepends = columnOf(table.header, ['depends on']);
      for (const s of surplusCellRows(table, `wave ${wave.label} story table`)) add(PLAN, offset + s.line, s.message);
      table.rows.forEach((row, r) => {
        if (isTotalsRow(row)) return;
        const line = offset + table.rowLines[r];
        const story = singleStoryId(row[iStory]);
        if (story.error) {
          add(PLAN, line, `wave ${wave.label} row: Story cell ${story.error}`);
          return;
        }
        const position = intStrict(plainText(row[iPosition]));
        if (position === null) {
          add(PLAN, line, `wave ${wave.label} row ${story.id}: position "${plainText(row[iPosition])}" is not an integer`);
          return;
        }
        const prior = placed.get(story.id);
        if (prior) {
          add(PLAN, line, `${story.id} appears in wave tables ${prior.label} (line ${prior.line}) and ${wave.label}`);
          return;
        }
        placed.set(story.id, { wave: wave.wave, label: wave.label, position, line });
        if (iDepends >= 0) listedDeps.set(story.id, { ...parseIdList(row[iDepends], WAVE_TABLE_NONE), line });
      });
    }
  }
  return { placed, listedDeps };
}

function checkOrdering(plan, stories, edges, storyTable, add) {
  const { placed, listedDeps } = placeStories(plan, add);
  const inEdges = new Set();
  for (const edge of edges.values()) {
    inEdges.add(edge.s);
    if (stories.has(edge.p)) inEdges.add(edge.p);
  }
  // An unplaced story has no row of its own: the first wave section locates it, else line 1.
  const waveLine = waveSections(plan)[0]?.startLine ?? 1;
  for (const id of inEdges) {
    if (!placed.has(id)) add(PLAN, waveLine, `${id} takes part in a dependency edge but appears in no wave story table`);
  }

  // Enforce Implementation Plan, Epic and Story Structure — Base: a same-wave prerequisite
  // carries a lower position. The same test is applied across waves, so a prerequisite in
  // a later wave is a violation too.
  for (const edge of edges.values()) {
    const s = placed.get(edge.s);
    const p = placed.get(edge.p);
    if (!s || !p) continue;
    if (p.wave < s.wave || (p.wave === s.wave && p.position < s.position)) continue;
    add(
      PLAN,
      s.line,
      `${edge.s} is positioned before its prerequisite ${edge.p} (${edge.s} at ${s.label} position ${s.position}, ${edge.p} at ${p.label} position ${p.position})`,
    );
  }

  // The plan's own Depends on column is a dependency in the Implementation Plan, so the
  // pass/fail line "every dependency in the Implementation Plan also appears in the
  // Dependency Map" covers it alongside story metadata.
  for (const [id, listed] of listedDeps) {
    if (listed.error) add(PLAN, listed.line, `wave table Depends on for ${id}: ${listed.error}`);
    const listedSet = new Set(listed.ids);
    const story = stories.get(id);
    for (const prerequisite of listedSet) {
      const key = edgeKey(id, prerequisite);
      if (storyTable && !storyTable.rows.has(key)) {
        add(PLAN, listed.line, `wave table lists ${prerequisite} as a prerequisite of ${id}, but the Dependency Map has no story-edge row ${key}`);
      }
      if (story && !edges.has(key)) {
        add(PLAN, listed.line, `wave table lists ${prerequisite} as a prerequisite of ${id}, but ${id}'s metadata Depends on does not`);
      }
    }
    if (!story) continue;
    for (const prerequisite of distinct(story.prereqs)) {
      if (prerequisite !== id && !listedSet.has(prerequisite)) {
        add(PLAN, listed.line, `wave table Depends on for ${id} omits ${prerequisite}, which its metadata lists`);
      }
    }
  }
}

// Phase 5: every epic README's Dependencies section lists exactly its rows.
function indexedEpics(add) {
  const out = new Map();
  if (!exists(EPIC_INDEX)) {
    add(EPIC_INDEX, undefined, 'epics index is missing');
    return out;
  }
  if (!isFile(EPIC_INDEX)) {
    add(EPIC_INDEX, undefined, 'epics index is not a regular file');
    return out;
  }
  const table = parseTables(readText(EPIC_INDEX)).find((t) => columnOf(t.header, ['id']) >= 0);
  if (!table) {
    add(EPIC_INDEX, 1, 'no epic table with an ID column');
    return out;
  }
  const iId = columnOf(table.header, ['id']);
  for (const s of surplusCellRows(table, 'epic index table')) add(EPIC_INDEX, s.line, s.message);
  table.rows.forEach((row, r) => {
    if (isTotalsRow(row)) return;
    const epic = singleEpicId(row[iId]);
    if (epic.error) add(EPIC_INDEX, table.rowLines[r], `ID cell ${epic.error}`);
    else if (!out.has(epic.id)) out.set(epic.id, table.rowLines[r]);
  });
  return out;
}

function epicReadmes(backlogFiles, add) {
  const readmes = new Map();
  for (const file of backlogFiles) {
    const m = EPIC_README_RE.exec(file);
    if (!m) continue;
    if (readmes.has(m[1])) add(file, 1, `epic ${m[1]} has a second README (first ${readmes.get(m[1])})`);
    else readmes.set(m[1], file);
  }
  return readmes;
}

/**
 * Entries of a Dependencies section, in line order and outside fenced code: list items (with
 * their continuation lines), table body rows, every standalone paragraph that names a
 * story or an epic other than `epic` (its lines joined, at its first line), and every
 * subheading below the section heading that names one (its heading text, without the `#`
 * markers, at its line); plus the section's body text for the N/A test, which holds such
 * subheadings but no subheading naming neither. Each table row with more cells than its
 * header is reported against `file` at its README line.
 */
function dependencyEntries(section, epic, file, add) {
  const lines = section.text.split(/\r?\n/);
  const offset = section.startLine - 1;
  const fenced = new Set();
  for (const block of fencedBlocks(section.text)) {
    for (let k = block.startLine; k <= block.endLine; k++) fenced.add(k);
  }
  const tableLines = new Set();
  const entries = [];
  for (const table of parseTables(section.text)) {
    tableLines.add(table.line);
    tableLines.add(table.line + 1);
    for (const s of surplusCellRows(table, 'Dependencies table')) add(file, offset + s.line, s.message);
    table.rows.forEach((row, r) => {
      tableLines.add(table.rowLines[r]);
      if (!isTotalsRow(row)) entries.push({ line: offset + table.rowLines[r], text: row.join(' | '), cells: row });
    });
  }
  const body = [];
  let open = null;
  // A standalone paragraph declares a dependency when it names a story, or an epic other
  // than the README's own; one naming neither stays body text only.
  const close = () => {
    if (open?.paragraph) {
      const plain = plainText(open.text);
      if (STORY_ID_LIKE_RE.test(plain) || epicIdsIn(plain).some((id) => id !== epic)) {
        entries.push({ line: open.line, text: open.text });
      }
    }
    open = null;
  };
  // Line 1 is the section heading itself.
  for (let k = 2; k <= lines.length; k++) {
    const line = lines[k - 1];
    if (fenced.has(k) || line.trim() === '') {
      close();
      continue;
    }
    // A subheading ends any open entry. One naming a story, or an epic other than the
    // README's own, is itself a declaration, recorded at its line by its heading text; one
    // naming neither contributes nothing. A heading-like line the shared parser does not
    // read as a heading keeps its whole text, and a table line is already a table entry.
    if (HEADING_LINE_RE.test(line)) {
      close();
      const heading = parseHeadings(line.trimStart())[0]?.text ?? line.trim();
      const plain = plainText(heading);
      if (!tableLines.has(k) && (STORY_ID_LIKE_RE.test(plain) || epicIdsIn(plain).some((id) => id !== epic))) {
        entries.push({ line: offset + k, text: heading });
        body.push(heading);
      }
      continue;
    }
    if (tableLines.has(k)) {
      close();
      body.push(line.trim());
      continue;
    }
    const item = LIST_ITEM_RE.exec(line);
    if (item) {
      close();
      open = { line: offset + k, text: item[1] };
      entries.push(open);
      body.push(item[1]);
      continue;
    }
    // A line directly after a list item continues it; any other line starts or continues
    // a standalone paragraph.
    if (open) open.text += ` ${line.trim()}`;
    else open = { line: offset + k, text: line.trim(), paragraph: true };
    body.push(line.trim());
  }
  close();
  entries.sort((a, b) => a.line - b.line);
  return { entries, body: plainText(body.join(' ')).replace(/\s+/g, ' ').trim() };
}

/**
 * The edge text of a Dependencies entry. A table row's edge text is its cells that name
 * stories. A list item or paragraph is a label naming the prerequisite epic, then its story
 * edges: the edge text starts after the last ":" before the first story-ID-like token, or
 * at that token when no ":" precedes it, and runs to the end of the entry, less one trailing
 * ".", "," or ";". An entry naming no story-ID-like token has empty edge text.
 */
function entryEdgeText(entry) {
  if (entry.cells) {
    const cells = entry.cells.map((cell) => plainText(cell).trim()).filter((cell) => STORY_ID_LIKE_RE.test(cell));
    return cells.join(', ');
  }
  const plain = plainText(entry.text).replace(/\s+/g, ' ').trim();
  const first = plain.search(STORY_ID_LIKE_RE);
  if (first < 0) return '';
  const colon = first > 0 ? plain.lastIndexOf(':', first - 1) : -1;
  return plain.slice(colon >= 0 ? colon + 1 : first).replace(/[.,;]$/, '').trim();
}

function checkEpicReadme(epic, file, expected, derived, add) {
  const section = singleSection(file, readText(file), 'Dependencies', add);
  if (!section) return;
  const { entries, body } = dependencyEntries(section, epic, file, add);
  if (expected.size === 0) {
    if (body !== 'N/A') {
      const shown = body.length > 80 ? `${body.slice(0, 77)}...` : body;
      add(file, section.startLine, `Dependencies must read "N/A", because no epic-dependency row has ${epic} as dependent epic; found "${shown}"`);
    }
    return;
  }
  const listed = new Map();
  for (const entry of entries) {
    const others = distinct(epicIdsIn(plainText(entry.text))).filter((id) => id !== epic);
    if (others.length !== 1) {
      const named = others.length === 0 ? 'no prerequisite epic' : `${others.length} prerequisite epics (${others.join(', ')})`;
      add(file, entry.line, `Dependencies entry names ${named}; each entry names exactly one`);
      continue;
    }
    const pe = others[0];
    if (listed.has(pe)) {
      add(file, entry.line, `Dependencies lists prerequisite epic ${pe} twice (first at line ${listed.get(pe)})`);
      continue;
    }
    listed.set(pe, entry.line);
    if (!expected.has(pe)) {
      add(file, entry.line, `Dependencies lists prerequisite epic ${pe}, which the epic-dependency table does not list for ${epic}`);
      continue;
    }
    const pair = derived.get(pairKey(epic, pe)) ?? { de: epic, pe, edges: new Set() };
    for (const m of compareEdgeText(entryEdgeText(entry), pair)) add(file, entry.line, `Dependencies entry for ${pe}: story edges ${m}`);
  }
  for (const pe of expected) {
    if (listed.has(pe)) continue;
    const produced = [...(derived.get(pairKey(epic, pe))?.edges ?? [])].join(', ');
    add(file, section.startLine, `Dependencies omits prerequisite epic ${pe}${produced ? ` (${produced})` : ''}`);
  }
}

function checkEpicReadmes(backlogFiles, derived, epicTable, add) {
  // Each README is held to the epic-dependency table; without a readable table the
  // derived pairs stand in, so README defects are still reported.
  const expectedRows = epicTable ? [...epicTable.rows.values()] : [...derived.values()];
  const expectedByEpic = new Map();
  for (const row of expectedRows) {
    if (!expectedByEpic.has(row.de)) expectedByEpic.set(row.de, new Set());
    expectedByEpic.get(row.de).add(row.pe);
  }
  const indexed = indexedEpics(add);
  const readmes = epicReadmes(backlogFiles, add);
  const epics = [...new Set([...indexed.keys(), ...readmes.keys(), ...expectedByEpic.keys()])].sort();
  for (const epic of epics) {
    const file = readmes.get(epic);
    if (!file) {
      // Without an epic-dependency table an existing plan is located at line 1; a missing
      // or unreadable plan has no line.
      const planLine = epicTable ? epicTable.line : isFile(PLAN) ? 1 : undefined;
      const where = indexed.has(epic) ? [EPIC_INDEX, indexed.get(epic)] : [PLAN, planLine];
      add(where[0], where[1], `epic ${epic} has no epic README under ${EPICS_DIR}, so its Dependencies cannot be checked`);
      continue;
    }
    checkEpicReadme(epic, file, expectedByEpic.get(epic) ?? new Set(), derived, add);
  }
}

// Phase 6: counts fixed by the backlog inventory.
function checkInventory(edges, storyTable, derived, epicTable, add) {
  if (edges.size !== EXPECTED_STORY_EDGES) {
    add(EPICS_DIR, undefined, `story metadata Depends on fields give ${edges.size} story edges; the backlog inventory fixes ${EXPECTED_STORY_EDGES}`);
  }
  if (storyTable && storyTable.rowCount !== EXPECTED_STORY_EDGES) {
    add(PLAN, storyTable.line, `story-edge table has ${storyTable.rowCount} rows; the backlog inventory fixes ${EXPECTED_STORY_EDGES}`);
  }
  if (derived.size !== EXPECTED_EPIC_PAIRS) {
    add(EPICS_DIR, undefined, `story edges derive ${derived.size} epic pairs; the backlog inventory fixes ${EXPECTED_EPIC_PAIRS}`);
  }
  if (epicTable && epicTable.rowCount !== EXPECTED_EPIC_PAIRS) {
    add(PLAN, epicTable.line, `epic-dependency table has ${epicTable.rowCount} rows; the backlog inventory fixes ${EXPECTED_EPIC_PAIRS}`);
  }
}

/**
 * True when the root-relative path is a regular file, or a symbolic link the portability
 * guard confines to one; false when it is missing, sits under a non-directory or is not a
 * file. exists() is also true for a directory, which readText cannot read. The guard throws
 * for an unsafe link before its target is statted.
 */
function isFile(rel) {
  return pathKind(rel) === 'file';
}

/**
 * The section of `file` under the first non-quoted heading matching `name` the way
 * sectionByHeading matches a string (whitespace-normalised, raw or plain text), as
 * sectionByHeading returns it. Every later non-quoted matching heading is reported at its
 * own line, so a second dependency section is never left unchecked. Quoted material never
 * stands in for the section: with no non-quoted matching heading, one missing-section
 * violation is reported at line 1, naming the line of every blockquoted matching heading,
 * and null is returned.
 */
function singleSection(file, text, name, add) {
  const wanted = name.replace(/\s+/g, ' ').trim();
  const matches = (s) => s.replace(/\s+/g, ' ').trim() === wanted;
  const named = parseHeadings(text).filter((h) => matches(h.text) || matches(plainText(h.text)));
  const heads = named.filter((h) => !h.quoted);
  if (heads.length === 0) {
    const quoted = named.map((h) => h.line);
    const why =
      quoted.length === 0
        ? ''
        : quoted.length === 1
          ? `; the heading at line ${quoted[0]} is inside a blockquote`
          : `; the headings at lines ${quoted.join(', ')} are inside a blockquote`;
    add(file, 1, `no "${name}" section${why}`);
    return null;
  }
  for (const h of heads.slice(1)) {
    add(file, h.line, `second "${name}" section (first at line ${heads[0].line}); a dependency section must appear once`);
  }
  return sectionByHeading(text, name);
}

function main() {
  const violations = [];
  const add = (file, line, message) => violations.push({ path: file, line, message });

  const backlogFiles = listFiles(EPICS_DIR, { exts: ['.md'] });
  const stories = loadStories(backlogFiles, add);
  const edges = collectEdges(stories, add);
  const derived = derivePairs(edges);

  let storyTable = null;
  let epicTable = null;
  if (!exists(PLAN)) {
    add(PLAN, undefined, 'implementation plan is missing');
  } else if (!isFile(PLAN)) {
    add(PLAN, undefined, 'implementation plan is not a regular file');
  } else {
    const plan = readText(PLAN);
    const depMap = singleSection(PLAN, plan, 'Dependency Map', add);
    if (depMap) {
      storyTable = checkStoryEdgeTable(depMap, edges, add);
      epicTable = checkEpicPairTable(depMap, derived, storyTable, add);
    }
    checkOrdering(plan, stories, edges, storyTable, add);
  }
  checkEpicReadmes(backlogFiles, derived, epicTable, add);
  checkInventory(edges, storyTable, derived, epicTable, add);
  report(CHECK, violations);
}

const USAGE = [
  'Usage: node tests/validation/check-dependency-order.mjs [-h|--help]',
  '',
  'Dependency Map and ordering check: story edges, epic pairs and wave positions agree with story metadata.',
  'Reads docs/implementation-plan.md and the story metadata, epics index and epic READMEs under',
  'docs/backlog/epics/, resolved against the repository root, so it runs from any working directory.',
  'Exit codes: 0 clean, 1 violations, 2 usage or internal error.',
].join('\n');

const args = process.argv.slice(2);
const unknownArg = args.find((arg) => arg !== '-h' && arg !== '--help');
if (unknownArg !== undefined) {
  console.error(`${CHECK}: unknown argument "${unknownArg}"`);
  console.error(USAGE);
  process.exitCode = 2;
} else if (args.length > 0) {
  console.log(USAGE);
  process.exitCode = 0;
} else {
  try {
    main();
  } catch (err) {
    console.error(`${CHECK}: internal error in ${repoRoot}: ${err?.stack ?? err}`);
    process.exitCode = 2;
  }
}

// SPDX-License-Identifier: Apache-2.0
/**
 * check-reachability: the link graph from the implementation plan.
 *
 * Starting at docs/implementation-plan.md and following relative Markdown links, the check
 * passes when:
 * - each universe root (docs/, the two proto roots, the OpenAPI folder) is a directory holding
 *   files of its family, and the entry point is readable and links onward;
 * - every file under docs/, every *.proto of the two proto roots and every OpenAPI document
 *   is reached, and every reached file is a readable regular file;
 * - no link is non-relative, leaves the delivery set or is broken, and every anchor into a
 *   Markdown file names one of its heading slugs (GitHub algorithm, duplicate suffixes included);
 * - every story has a "4. Design" section, and every path it names, as a link or as a backticked
 *   path, exists.
 *
 * The plan is the root because the Enforce Implementation Plan, Epic and Story Structure — Base
 * rule makes it the single navigation surface. The Epic and Story Structure — Multi-File Variant
 * rule's plan-link resolution is asserted structurally by check-plan-consistency; this check
 * covers every link in the graph.
 *
 * Portability: the run-created set moves unchanged to the delivery repository, so a link target
 * is classified by its path alone before any filesystem access, and nothing under app/,
 * diagrams/ or samples/ is ever opened or statted.
 *
 * Usage: node tests/validation/check-reachability.mjs [--help | -h] (no other arguments; from any
 * working directory).
 * Exit: 0 with no violations, 1 with violations, 2 on an internal error or a bad argument.
 */
import path from 'node:path';
import {
  readText,
  exists,
  pathKind,
  listFiles,
  parseLinks,
  resolveLink,
  parseHeadings,
  sections,
  fencedBlocks,
  isReferencePath,
  report,
} from './lib/markdown.mjs';

const CHECK = 'check-reachability';
const ENTRY = 'docs/implementation-plan.md';
const DESIGN_HEADING = '4. Design';
const STORY_RE = /^docs\/backlog\/epics\/[^/]+\/stories\/[^/]+\.md$/;

// The delivery set is what moves to the delivery repository: these top-level directories and
// .spectral.yaml. Besides escapes and the CardDemo reference roots, this also rejects links to
// CardDemo's own root files (README.md, LICENSE and the like): they resolve in this checkout but
// would break after the move, so the check gives the same result in both places.
const DELIVERY_DIRS = new Set(['docs', 'card-servicing-contracts', 'card-servicing-service', 'tests']);
const DELIVERY_FILES = new Set(['.spectral.yaml']);

// The universe every file of which must be reached: each root with the extensions it contributes
// (null for every file) and the family a root without one of them lacks.
const UNIVERSE_ROOTS = [
  { root: 'docs', exts: null, family: 'files' },
  { root: 'card-servicing-contracts', exts: ['.proto'], family: '.proto files' },
  { root: 'card-servicing-service/src/main/proto', exts: ['.proto'], family: '.proto files' },
  {
    root: 'card-servicing-service/src/main/resources/openapi',
    exts: ['.yaml', '.yml'],
    family: 'OpenAPI documents (.yaml, .yml)',
  },
];

// Backticked tokens in a Design section that name a path: relative to the story, anchored at a
// run root, or a bare artifact filename. HTTP paths, identifiers and "Contracts: N/A" match none.
const STORY_RELATIVE_RE = /^\.\.?\//;
const RUN_ROOT_RE = /^(?:docs|card-servicing-contracts|card-servicing-service|tests)\/|^\.spectral\.yaml$/;
const BARE_FILE_RE = /^[^\s/]+\.(?:md|mmd|proto|yaml|yml|json)$/;
const URI_SCHEME_RE = /^[A-Za-z][A-Za-z0-9+.-]*:/;

/** True for a normalised root-relative path inside the delivery set; pure. */
function inDeliverySet(rel) {
  if (rel === '.') return true;
  if (DELIVERY_FILES.has(rel)) return true;
  const first = rel.split('/')[0];
  return DELIVERY_DIRS.has(first);
}

// Story section headings that carry the word Design yet are not the Design section.
const OTHER_DESIGN_HEADINGS = new Set(['implementation design', 'design validation']);

/**
 * True for a non-quoted story section heading (level 2) that names Design but is neither
 * Implementation Design nor Design Validation: the likely, misspelt "4. Design" heading.
 */
function looksLikeDesignHeading(heading) {
  if (heading.quoted || heading.level !== 2) return false;
  const text = heading.text.replace(/\s+/g, ' ').trim();
  return /\bdesign\b/i.test(text) && !OTHER_DESIGN_HEADINGS.has(text.toLowerCase());
}

/**
 * Classifies a link target written in `fromRel` without touching the filesystem.
 * @returns {{ path: string, anchor: string | null } | { violation: string }}
 */
function classifyLink(fromRel, target) {
  if (target.startsWith('/') || URI_SCHEME_RE.test(target)) {
    return { violation: `non-relative link: ${target}` };
  }
  const resolved = resolveLink(fromRel, target);
  // Percent-decoding inside resolveLink can still yield a leading slash ("%2Fdocs").
  if (resolved.path.startsWith('/')) return { violation: `non-relative link: ${target}` };
  if (!inDeliverySet(resolved.path)) return { violation: `link leaves the delivery set: ${target}` };
  return resolved;
}

/** True for a failed system call (EACCES, EISDIR and the like); guard throws and bugs are not. */
function isFsError(err) {
  return err instanceof Error && typeof err.code === 'string' && typeof err.syscall === 'string';
}

/**
 * 'file' for a regular file, 'dir' for a directory, 'other' for anything else (a FIFO, socket or
 * device, never opened) and null when nothing is there. Callers classify delivery-set membership
 * first; pathKind runs the shared library's guard, which rejects a path or symbolic link leading
 * outside the repository or into a reference root, before the stat that tells them apart.
 */
function kindOf(rel) {
  return pathKind(rel);
}

/**
 * Inline code span contents of one line, CommonMark style: a backtick run closes on the next run
 * of equal length, an unmatched run is literal, and a backslash outside code escapes the next
 * character. The shared library keeps its span scanner private, so this mirrors it.
 */
function codeSpans(line) {
  const out = [];
  let i = 0;
  while (i < line.length) {
    if (line[i] === '\\') {
      i += 2;
      continue;
    }
    if (line[i] !== '`') {
      i++;
      continue;
    }
    let j = i;
    while (j < line.length && line[j] === '`') j++;
    const run = j - i;
    let close = -1;
    let k = j;
    while (k < line.length) {
      if (line[k] !== '`') {
        k++;
        continue;
      }
      let m = k;
      while (m < line.length && line[m] === '`') m++;
      if (m - k === run) {
        close = k;
        break;
      }
      k = m;
    }
    if (close < 0) {
      i = j;
      continue;
    }
    out.push(line.slice(j, close));
    i = close + run;
  }
  return out;
}

function main() {
  const violations = [];
  const texts = new Map();
  const slugs = new Map();
  const readability = new Map();
  /** The file's text, or null after recording it unreadable; any other error propagates. */
  const tryRead = (rel) => {
    try {
      return readText(rel);
    } catch (err) {
      if (!isFsError(err)) throw err;
      violations.push({ path: rel, message: `file is unreadable (${err.code})` });
      return null;
    }
  };
  const textOf = (rel) => {
    if (!texts.has(rel)) texts.set(rel, tryRead(rel));
    return texts.get(rel);
  };
  /** Heading slugs of a Markdown file, or null when it is unreadable. */
  const slugsOf = (rel) => {
    if (!slugs.has(rel)) {
      const text = textOf(rel);
      slugs.set(rel, text === null ? null : new Set(parseHeadings(text).map((h) => h.slug)));
    }
    return slugs.get(rel);
  };
  /** Probes a reached file once; a leaf's text is read and dropped, a Markdown file's is kept. */
  const readable = (rel) => {
    if (rel.endsWith('.md')) return textOf(rel) !== null;
    if (!readability.has(rel)) readability.set(rel, tryRead(rel) !== null);
    return readability.get(rel);
  };

  /** Checks every link of one Markdown file; `onReach` receives each regular file it links. */
  const scanLinks = (fromRel, onReach) => {
    const text = textOf(fromRel);
    // An unreadable file is recorded as such and has no links to follow.
    if (text === null) return;
    for (const { target, line } of parseLinks(text)) {
      const link = classifyLink(fromRel, target);
      if ('violation' in link) {
        violations.push({ path: fromRel, line, message: link.violation });
        continue;
      }
      const kind = kindOf(link.path);
      if (kind === null) {
        violations.push({ path: fromRel, line, message: `broken link: ${target} (resolves to ${link.path})` });
        continue;
      }
      // A folder link reaches no file, so it never makes a file reachable.
      if (kind === 'dir') continue;
      if (kind === 'other') {
        violations.push({
          path: fromRel,
          line,
          message: `link target is not a regular file: ${target} (resolves to ${link.path})`,
        });
        continue;
      }
      // An unreadable target is still linked, so it is reached; only its anchor goes unchecked.
      const anchored = readable(link.path) && link.path.endsWith('.md') && link.anchor;
      if (anchored && !slugsOf(link.path).has(link.anchor)) {
        violations.push({
          path: fromRel,
          line,
          message: `missing anchor: ${target} (no heading in ${link.path} has the slug "${link.anchor}")`,
        });
      }
      if (onReach) onReach(link.path);
    }
  };

  // Each root must be a directory holding files of its family; otherwise a whole artifact family
  // would drop out of the universe and the check would pass without seeing it.
  const universe = [];
  for (const { root, exts, family } of UNIVERSE_ROOTS) {
    const kind = kindOf(root);
    if (kind !== 'dir') {
      const message = kind === null ? 'universe root is missing' : 'universe root is not a directory';
      violations.push({ path: root, message });
      continue;
    }
    const files = listFiles(root, { exts });
    if (files.length === 0) violations.push({ path: root, message: `universe root holds no ${family}` });
    universe.push(...files);
  }

  if (kindOf(ENTRY) !== 'file') {
    violations.push({ path: ENTRY, message: 'entry point is missing; nothing is reachable' });
    return report(CHECK, violations);
  }
  const entryText = textOf(ENTRY);
  // Its unreadable violation is already recorded, and without its text nothing is reachable.
  if (entryText === null) return report(CHECK, violations);
  // An empty or link-free entry point reaches nothing, so every other universe file is then
  // reported unreachable as well.
  if (entryText.trim() === '') {
    violations.push({ path: ENTRY, message: 'entry point is empty; nothing is reachable' });
  } else if (parseLinks(entryText).length === 0) {
    violations.push({ path: ENTRY, message: 'entry point has no links; nothing is reachable' });
  }

  // Breadth-first walk: Markdown files are expanded once; every other file is a leaf.
  const reached = new Set([ENTRY]);
  const queue = [ENTRY];
  for (let next = 0; next < queue.length; next++) {
    scanLinks(queue[next], (rel) => {
      if (reached.has(rel)) return;
      reached.add(rel);
      if (rel.endsWith('.md')) queue.push(rel);
    });
  }

  for (const rel of universe) {
    if (reached.has(rel)) continue;
    violations.push({ path: rel, message: `unreachable from ${ENTRY}` });
    // Links of an unreached Markdown file are still checked, so one run reports every broken
    // link and an unreached story's Design links are covered; they reach nothing.
    if (rel.endsWith('.md')) scanLinks(rel, null);
  }

  const basenames = new Set(universe.map((rel) => path.posix.basename(rel)));
  for (const rel of ['card-servicing-contracts/buf.yaml', '.spectral.yaml']) {
    if (kindOf(rel) === 'file') basenames.add(path.posix.basename(rel));
  }
  for (const story of universe.filter((rel) => STORY_RE.test(rel))) {
    const text = textOf(story);
    // Its unreadable violation is already recorded.
    if (text === null) continue;
    // Only the non-quoted level-2 heading is the Design section: a quoted "> ## 4. Design" is
    // part of a source excerpt, so it never stands in for a missing or misspelt real heading.
    const design = sections(text, 2).find((s) => s.heading.replace(/\s+/g, ' ').trim() === DESIGN_HEADING);
    if (design) {
      checkDesignPaths(story, design, basenames, violations);
      continue;
    }
    // Without the section its paths go unchecked, so the precondition is reported here, at the
    // heading that was most likely meant when there is one.
    const near = parseHeadings(text).find(looksLikeDesignHeading);
    const unchecked = 'its paths cannot be checked';
    if (near) {
      violations.push({
        path: story,
        line: near.line,
        message: `Design section heading "${near.text}" is not "${DESIGN_HEADING}"; ${unchecked}`,
      });
    } else {
      violations.push({ path: story, message: `no "${DESIGN_HEADING}" section; ${unchecked}` });
    }
  }

  return report(CHECK, violations);
}

/**
 * Every backticked path-like token in a story's Design section must exist. Its links were
 * checked by the link scan, which covers every Markdown file in the universe.
 */
function checkDesignPaths(story, design, basenames, violations) {
  const lines = design.text.split(/\r?\n/);
  const inFence = new Array(lines.length).fill(false);
  for (const block of fencedBlocks(design.text)) {
    for (let i = block.startLine - 1; i < block.endLine; i++) inFence[i] = true;
  }
  lines.forEach((text, i) => {
    if (inFence[i]) return;
    const line = design.startLine + i;
    for (const span of codeSpans(text)) {
      const token = span.trim();
      const bare = token.replace(/#.*$/, '');
      if (bare === '') continue;
      let resolved;
      if (STORY_RELATIVE_RE.test(bare)) {
        resolved = path.posix.normalize(path.posix.join(path.posix.dirname(story), bare));
      } else if (RUN_ROOT_RE.test(bare) || (isReferencePath(bare) && bare.includes('/'))) {
        resolved = path.posix.normalize(bare);
      } else if (BARE_FILE_RE.test(bare)) {
        if (!basenames.has(bare)) {
          violations.push({ path: story, line, message: `Design path does not exist: \`${token}\`` });
        }
        continue;
      } else {
        continue;
      }
      if (resolved.length > 1 && resolved.endsWith('/')) resolved = resolved.slice(0, -1);
      if (!inDeliverySet(resolved)) {
        violations.push({ path: story, line, message: `Design path leaves the delivery set: \`${token}\`` });
      } else if (!exists(resolved)) {
        violations.push({ path: story, line, message: `Design path does not exist: \`${token}\`` });
      }
    }
  });
}

/** Printed for --help and -h, and after an unknown argument; ends with a newline. */
const USAGE = `Usage: node tests/validation/${CHECK}.mjs [--help | -h]

Takes no arguments and gives the same result from any working directory. Starting at
${ENTRY} it follows relative Markdown links. The universe is every file
under docs/, every .proto under card-servicing-contracts/ and
card-servicing-service/src/main/proto/, and every .yaml/.yml OpenAPI document under
card-servicing-service/src/main/resources/openapi/. It reports unreachable files, non-relative
links, links leaving the delivery set, broken links, missing anchors, missing paths named in a
story's "4. Design" section, and failed preconditions (missing or empty roots or entry point, a
missing Design section, unreadable files). Nothing under app/, diagrams/ or samples/ is ever
opened.

Output: one line per violation, "${CHECK}: <path>[:<line>]: <message>", then
the final line "${CHECK}: <N> violation(s)".
Exit: 0 with no violations, 1 with violations, 2 on an internal error or a bad argument.
`;

// run-all.sh calls the check with no arguments. Any other argument is refused before the scan,
// so a mistyped option never yields a passing count; the argument is quoted so an empty value
// or one carrying a line break stays on the one diagnostic line.
const args = process.argv.slice(2);
const unknown = args.find((arg) => arg !== '-h' && arg !== '--help');
if (unknown !== undefined) {
  process.stderr.write(`${CHECK}: unknown argument: ${JSON.stringify(unknown)}\n${USAGE}`);
  process.exitCode = 2;
} else if (args.length > 0) {
  process.stdout.write(USAGE);
  process.exitCode = 0;
} else {
  try {
    main();
  } catch (err) {
    console.error(`${CHECK}: internal error: ${err && err.stack ? err.stack : err}`);
    process.exitCode = 2;
  }
}

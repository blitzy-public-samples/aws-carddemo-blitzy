// SPDX-License-Identifier: Apache-2.0
/**
 * check-reachability: the link graph from the implementation plan.
 *
 * Starting at docs/implementation-plan.md and following relative Markdown links, the check
 * passes when:
 * - every file under docs/, every *.proto of the two proto roots and every OpenAPI document
 *   is reached;
 * - no link is non-relative, leaves the delivery set or is broken, and every anchor into a
 *   Markdown file names one of its heading slugs (GitHub algorithm, duplicate suffixes included);
 * - every path a story's "4. Design" section names, as a link or as a backticked path, exists.
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
 * Usage: node tests/validation/check-reachability.mjs (from any working directory).
 * Exit: 0 with no violations, 1 with violations, 2 on an internal error.
 */
import fs from 'node:fs';
import path from 'node:path';
import {
  repoRoot,
  readText,
  exists,
  listFiles,
  parseLinks,
  resolveLink,
  parseHeadings,
  sectionByHeading,
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

/**
 * 'file', 'dir' or null for a delivery-set path. exists() runs first so the shared library's
 * guard rejects any path outside the delivery set before the stat that tells files from folders.
 */
function kindOf(rel) {
  if (!exists(rel)) return null;
  return fs.statSync(path.join(repoRoot, rel)).isDirectory() ? 'dir' : 'file';
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
  const textOf = (rel) => {
    if (!texts.has(rel)) texts.set(rel, readText(rel));
    return texts.get(rel);
  };
  const slugsOf = (rel) => {
    if (!slugs.has(rel)) slugs.set(rel, new Set(parseHeadings(textOf(rel)).map((h) => h.slug)));
    return slugs.get(rel);
  };

  /** Checks every link of one Markdown file; `onReach` receives each existing file it links. */
  const scanLinks = (fromRel, onReach) => {
    for (const { target, line } of parseLinks(textOf(fromRel))) {
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
      if (link.path.endsWith('.md') && link.anchor && !slugsOf(link.path).has(link.anchor)) {
        violations.push({
          path: fromRel,
          line,
          message: `missing anchor: ${target} (no heading in ${link.path} has the slug "${link.anchor}")`,
        });
      }
      if (onReach) onReach(link.path);
    }
  };

  if (kindOf(ENTRY) !== 'file') {
    violations.push({ path: ENTRY, message: 'entry point is missing; nothing is reachable' });
    return report(CHECK, violations);
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

  const universe = [
    ...listFiles('docs'),
    ...listFiles('card-servicing-contracts', { exts: ['.proto'] }),
    ...listFiles('card-servicing-service/src/main/proto', { exts: ['.proto'] }),
    ...listFiles('card-servicing-service/src/main/resources/openapi', { exts: ['.yaml', '.yml'] }),
  ];
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
    // A missing Design section is a check-backlog-structure violation, not a path to resolve.
    const design = sectionByHeading(textOf(story), DESIGN_HEADING);
    if (design) checkDesignPaths(story, design, basenames, violations);
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

try {
  main();
} catch (err) {
  console.error(`${CHECK}: internal error: ${err && err.stack ? err.stack : err}`);
  process.exitCode = 2;
}

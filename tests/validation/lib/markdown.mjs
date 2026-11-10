// SPDX-License-Identifier: Apache-2.0
/**
 * Shared Markdown, link and table parsing for the Card Servicing validation suite.
 *
 * Consumers, in the order tests/validation/run-all.sh runs them:
 * check-forbidden-terms, check-backlog-structure, check-plan-consistency,
 * check-diagrams, check-contracts, check-dependency-order and check-reachability.
 * Each imports its helpers from here and re-implements none of them.
 *
 * Portability guard: the suite moves unchanged to the delivery repository, so no
 * helper ever opens, stats or lists anything under the CardDemo reference roots
 * (app/, diagrams/, samples/) or outside the repository. File helpers take
 * repository-root-relative POSIX paths and throw on such a path; callers classify
 * link targets with isReferencePath before touching the filesystem.
 *
 * Node.js built-ins only (node:fs, node:path, node:url); no side effect at import
 * time beyond computing repoRoot. Every parser is fence-aware: headings, tables,
 * links and metadata inside fenced code are ignored, and fence content is reached
 * only through fencedBlocks. Line numbers are 1-based indexes into the text split
 * on /\r?\n/.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Absolute repository root; this file sits at <root>/tests/validation/lib/. */
export const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** Top-level CardDemo reference roots that validation must never read. */
export const REFERENCE_ROOTS = Object.freeze(['app', 'diagrams', 'samples']);

const SKIPPED_DIRS = new Set(['node_modules', '.git']);

/**
 * True when a root-relative path points into a reference root. Pure: never touches disk.
 * @param {string} rel
 * @returns {boolean}
 */
export function isReferencePath(rel) {
  let p = String(rel ?? '').replace(/\\/g, '/');
  while (p.startsWith('./')) p = p.slice(2);
  p = path.posix.normalize(p);
  return REFERENCE_ROOTS.includes(p.split('/')[0]);
}

/** Resolves a root-relative path to an absolute one, enforcing the portability guard. */
function toAbs(rel) {
  const r = String(rel ?? '');
  const abs = path.resolve(repoRoot, r === '' ? '.' : r);
  const fromRoot = path.relative(repoRoot, abs);
  const segments = fromRoot.split(path.sep);
  if (segments[0] === '..' || path.isAbsolute(fromRoot)) {
    throw new Error(`path outside repository: ${r}`);
  }
  if (REFERENCE_ROOTS.includes(segments[0])) {
    throw new Error(`reference source is off-limits to validation: ${r}`);
  }
  return abs;
}

/**
 * Reads a UTF-8 text file by root-relative path, removing one leading byte-order mark.
 * Line endings are left as they are; the parsers accept LF and CRLF.
 * @param {string} rel
 * @returns {string}
 */
export function readText(rel) {
  const text = fs.readFileSync(toAbs(rel), 'utf8');
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

/**
 * True for an existing file or directory. A caller that needs a file specifically
 * checks the path's extension or its presence in listFiles.
 * @param {string} rel
 * @returns {boolean}
 */
export function exists(rel) {
  return fs.existsSync(toAbs(rel));
}

/**
 * Lists every file below a root-relative directory, recursively, as sorted root-relative
 * POSIX paths. Dotfiles are included so the path-form gate of the Epic and Story
 * Structure — Multi-File Variant rule sees stray files; node_modules
 * and .git are skipped at any depth, and the reference roots are skipped when walking
 * from the repository root. A missing path or a non-directory gives [].
 * @param {string} relDir
 * @param {{ exts?: string[] }} [options] extensions matched case-sensitively against path.extname
 * @returns {string[]}
 */
export function listFiles(relDir, { exts } = {}) {
  if (exts != null && !Array.isArray(exts)) {
    throw new TypeError('listFiles: exts must be an array of extensions such as [".md"]');
  }
  const startAbs = toAbs(relDir);
  let startStat;
  try {
    startStat = fs.statSync(startAbs);
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'ENOTDIR') return [];
    throw err;
  }
  if (!startStat.isDirectory()) return [];

  const extSet = exts ? new Set(exts) : null;
  const startRel = path.relative(repoRoot, startAbs).split(path.sep).join('/');
  const out = [];
  const walk = (absDir, relPosix) => {
    for (const entry of fs.readdirSync(absDir, { withFileTypes: true })) {
      const name = entry.name;
      const childAbs = path.join(absDir, name);
      const childRel = relPosix === '' ? name : `${relPosix}/${name}`;
      let isDir = entry.isDirectory();
      let isFile = entry.isFile();
      if (entry.isSymbolicLink()) {
        // Symlinked files are followed; symlinked directories are not descended, so a
        // link cannot loop or lead the walk outside the guarded tree.
        let target;
        try {
          target = fs.statSync(childAbs);
        } catch (err) {
          if (err.code === 'ENOENT') continue; // dangling link: no file to report
          throw err;
        }
        isDir = false;
        isFile = target.isFile();
      }
      if (isDir) {
        if (SKIPPED_DIRS.has(name)) continue;
        if (relPosix === '' && REFERENCE_ROOTS.includes(name)) continue;
        walk(childAbs, childRel);
      } else if (isFile) {
        // A .git file is a worktree or submodule pointer, never repository content.
        if (name === '.git') continue;
        if (extSet && !extSet.has(path.extname(name))) continue;
        out.push(childRel);
      }
    }
  };
  walk(startAbs, startRel);
  return out.sort(compareCodeUnits);
}

function compareCodeUnits(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

function asText(text, fn) {
  if (typeof text !== 'string') throw new TypeError(`${fn}: text must be a string`);
  return text;
}

function splitLines(text) {
  return text.split(/\r?\n/);
}

const QUOTE_RE = /^[ \t]{0,3}(?:>[ \t]?)+/;

/** Strips leading blockquote markers, nested ones included. */
function unquote(line) {
  const m = QUOTE_RE.exec(line);
  return m ? { quoted: true, rest: line.slice(m[0].length) } : { quoted: false, rest: line };
}

const FENCE_OPEN_RE = /^( {0,3})(`{3,}|~{3,})(.*)$/;
const FENCE_CLOSE_RE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/;

/**
 * Finds fenced code blocks over split lines. Returns 0-based opening and last lines.
 * A fence opened inside a blockquote ends with its blockquote, as in CommonMark; an
 * unclosed fence otherwise runs to the last line.
 */
function scanFences(lines) {
  const blocks = [];
  let open = null;
  for (let i = 0; i < lines.length; i++) {
    const { quoted, rest } = unquote(lines[i]);
    if (open) {
      if (open.quoted && !quoted) {
        blocks.push({ ...open, end: i - 1, closed: false });
        open = null;
      } else {
        const m = FENCE_CLOSE_RE.exec(open.quoted ? rest : lines[i]);
        if (m && m[1][0] === open.ch && m[1].length >= open.len) {
          blocks.push({ ...open, end: i, closed: true });
          open = null;
        }
        continue;
      }
    }
    const m = FENCE_OPEN_RE.exec(rest);
    if (!m) continue;
    const run = m[2];
    const info = m[3];
    if (run[0] === '`' && info.includes('`')) continue;
    open = { ch: run[0], len: run.length, indent: m[1].length, info: info.trim(), quoted, start: i };
  }
  if (open) blocks.push({ ...open, end: lines.length - 1, closed: false });
  return blocks;
}

/** Boolean per split line: true from an opening fence to its closing fence inclusive. */
function fenceMask(lines) {
  const mask = new Array(lines.length).fill(false);
  for (const b of scanFences(lines)) {
    for (let i = b.start; i <= b.end; i++) mask[i] = true;
  }
  return mask;
}

/**
 * Fenced code blocks, blockquoted ones included.
 * `body` holds the lines strictly between the fences (to the end of the block when
 * unclosed), with blockquote markers and the opener's indentation removed.
 * @param {string} text
 * @returns {{ lang: string, body: string, startLine: number, endLine: number }[]}
 */
export function fencedBlocks(text) {
  const lines = splitLines(asText(text, 'fencedBlocks'));
  return scanFences(lines).map((b) => {
    const lastBody = b.closed ? b.end - 1 : b.end;
    const body = [];
    for (let i = b.start + 1; i <= lastBody; i++) {
      const raw = b.quoted ? unquote(lines[i]).rest : lines[i];
      body.push(b.indent > 0 ? raw.replace(new RegExp(`^ {0,${b.indent}}`), '') : raw);
    }
    return {
      lang: (b.info.split(/\s+/)[0] || '').toLowerCase(),
      body: body.join('\n'),
      startLine: b.start + 1,
      endLine: b.end + 1,
    };
  });
}

/**
 * Inline code spans of one line, CommonMark style: a backtick run opens a span that the
 * next run of the same length closes, and an unmatched run is literal. A backslash
 * outside code escapes the next character, so an escaped backtick never opens a span.
 * One leading and one trailing space are trimmed from content that has both and is not
 * all spaces. Returns [{ start, end, content }] with `end` exclusive.
 */
function findCodeSpans(s) {
  const spans = [];
  let i = 0;
  while (i < s.length) {
    const ch = s[i];
    if (ch === '\\') {
      i += 2;
      continue;
    }
    if (ch !== '`') {
      i++;
      continue;
    }
    let j = i;
    while (j < s.length && s[j] === '`') j++;
    const len = j - i;
    let close = -1;
    let k = j;
    while (k < s.length) {
      if (s[k] !== '`') {
        k++;
        continue;
      }
      let m = k;
      while (m < s.length && s[m] === '`') m++;
      if (m - k === len) {
        close = k;
        break;
      }
      k = m;
    }
    if (close < 0) {
      i = j;
      continue;
    }
    let content = s.slice(j, close);
    if (content.length >= 2 && content[0] === ' ' && content[content.length - 1] === ' ' && /[^ ]/.test(content)) {
      content = content.slice(1, -1);
    }
    spans.push({ start: i, end: close + len, content });
    i = close + len;
  }
  return spans;
}

// Private-use code points delimit placeholders: they never occur in the artifacts and
// are neither letters nor digits, so emphasis stripping treats them as word boundaries.
const PH_OPEN = '\uE000';
const PH_CLOSE = '\uE001';
const PH_RE = /\uE000(\d+)\uE001/g;
const ASCII_PUNCT_RE = /[!-/:-@[-`{-~]/;
const IMAGE_RE = /!\[([^\]]*)\]\([^)]*\)/g;
const INLINE_LINK_TEXT_RE = /\[([^\]]*)\]\([^)]*\)/g;
const HTML_RE = /<!--[\s\S]*?-->|<\/?[A-Za-z][^<>]*>/g;
const UNDERSCORE_EMPHASIS_RE = /(^|[^\p{L}\p{N}])_{1,2}(?=\S)(.+?)(?<=\S)_{1,2}(?=[^\p{L}\p{N}]|$)/gu;

/** Replaces code spans and backslash-escaped punctuation with placeholders. */
function protectInline(s) {
  const held = [];
  const hold = (value) => {
    held.push(value);
    return `${PH_OPEN}${held.length - 1}${PH_CLOSE}`;
  };
  const spans = findCodeSpans(s);
  let out = '';
  let next = 0;
  for (let i = 0; i < s.length; i++) {
    if (next < spans.length && i === spans[next].start) {
      out += hold(spans[next].content);
      i = spans[next].end - 1;
      next++;
      continue;
    }
    if (s[i] === '\\' && i + 1 < s.length && ASCII_PUNCT_RE.test(s[i + 1])) {
      out += hold(s[i + 1]);
      i++;
      continue;
    }
    out += s[i];
  }
  return { out, held };
}

/**
 * Heading text as GitHub renders it, before slugging: code spans reduced to their content,
 * images to their alt text, links to their text, HTML tags removed, `*` removed and `_`/`__`
 * emphasis delimiters removed at word boundaries (intraword underscores survive).
 * Used by sectionByHeading for matching.
 * @param {string} text
 * @returns {string}
 */
export function plainText(text) {
  const { out, held } = protectInline(String(text ?? ''));
  const stripped = out
    .replace(IMAGE_RE, '$1')
    .replace(INLINE_LINK_TEXT_RE, '$1')
    .replace(HTML_RE, '')
    .replace(/\*/g, '')
    .replace(UNDERSCORE_EMPHASIS_RE, '$1$2');
  return stripped.replace(PH_RE, (_, n) => held[Number(n)]);
}

/**
 * GitHub (github-slugger) heading anchor: plain text, lower-cased, every character other
 * than a letter, mark, number, `_`, space or `-` removed, then each space turned into `-`
 * with no collapsing or trimming. With a `seen` Map, repeated slugs get `-1`, `-2`, …
 * exactly as github-slugger assigns them within one document.
 * @param {string} text
 * @param {Map<string, number>} [seen]
 * @returns {string}
 */
export function slugify(text, seen) {
  if (seen != null && !(seen instanceof Map)) throw new TypeError('slugify: seen must be a Map');
  let slug = plainText(text)
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}_ -]/gu, '')
    .replace(/ /g, '-');
  if (seen) {
    const base = slug;
    if (seen.has(base)) {
      let n = seen.get(base);
      do {
        n++;
        slug = `${base}-${n}`;
      } while (seen.has(slug));
      seen.set(base, n);
    }
    seen.set(slug, 0);
  }
  return slug;
}

const ATX_RE = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$/;

/** ATX heading on an already-unquoted line, or null; a heading of only `#` is empty. */
function matchAtx(rest) {
  const m = ATX_RE.exec(rest);
  if (!m) return null;
  const text = (m[2] ?? '').trim();
  return { level: m[1].length, text: /^#+$/.test(text) ? '' : text };
}

/**
 * ATX headings outside fenced code, blockquoted ones included and flagged `quoted`.
 * `text` is the trimmed raw content; `slug` is unique within the document.
 * @param {string} text
 * @returns {{ level: number, text: string, line: number, slug: string, quoted: boolean }[]}
 */
export function parseHeadings(text) {
  const lines = splitLines(asText(text, 'parseHeadings'));
  const mask = fenceMask(lines);
  const seen = new Map();
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    if (mask[i]) continue;
    const { quoted, rest } = unquote(lines[i]);
    const h = matchAtx(rest);
    if (h) out.push({ level: h.level, text: h.text, line: i + 1, slug: slugify(h.text, seen), quoted });
  }
  return out;
}

function makeSection(lines, heading, endLine) {
  return {
    heading: heading.text,
    level: heading.level,
    text: lines.slice(heading.line - 1, endLine).join('\n'),
    startLine: heading.line,
    endLine,
  };
}

/**
 * One section per non-quoted heading of `level`, ending before the next non-quoted heading
 * of level ≤ `level` or at the last line. Quoted headings never start or end a section.
 * `text` holds lines startLine..endLine, heading included: line k of it is document line
 * startLine + k - 1.
 * @param {string} text
 * @param {number} level
 * @returns {{ heading: string, level: number, text: string, startLine: number, endLine: number }[]}
 */
export function sections(text, level) {
  if (!Number.isInteger(level) || level < 1 || level > 6) {
    throw new TypeError('sections: level must be an integer from 1 to 6');
  }
  const lines = splitLines(asText(text, 'sections'));
  const heads = parseHeadings(text).filter((h) => !h.quoted);
  const out = [];
  heads.forEach((h, i) => {
    if (h.level !== level) return;
    const next = heads.slice(i + 1).find((n) => n.level <= level);
    out.push(makeSection(lines, h, next ? next.line - 1 : lines.length));
  });
  return out;
}

const normaliseSpace = (s) => s.replace(/\s+/g, ' ').trim();

function headingMatches(heading, matcher) {
  if (matcher instanceof RegExp) {
    matcher.lastIndex = 0;
    const onRaw = matcher.test(heading.text);
    matcher.lastIndex = 0;
    const hit = onRaw || matcher.test(plainText(heading.text));
    matcher.lastIndex = 0;
    return hit;
  }
  const wanted = normaliseSpace(String(matcher));
  return normaliseSpace(heading.text) === wanted || normaliseSpace(plainText(heading.text)) === wanted;
}

/**
 * The first section whose heading matches: a string compares whitespace-normalised against
 * the raw and the plain heading text; a RegExp is tested against both. Non-quoted headings
 * are searched first and their section ends before the next non-quoted heading of level ≤
 * its own. Only when none matches is the first matching quoted heading used; that section
 * ends before the next heading of any kind of level ≤ its own and keeps its `>` markers.
 * @param {string} text
 * @param {string | RegExp} matcher
 * @returns {{ heading: string, level: number, text: string, startLine: number, endLine: number } | null}
 */
export function sectionByHeading(text, matcher) {
  const lines = splitLines(asText(text, 'sectionByHeading'));
  const heads = parseHeadings(text);
  const plain = heads.filter((h) => !h.quoted);
  const hit = plain.find((h) => headingMatches(h, matcher));
  if (hit) {
    const next = plain.find((n) => n.line > hit.line && n.level <= hit.level);
    return makeSection(lines, hit, next ? next.line - 1 : lines.length);
  }
  const quotedHit = heads.find((h) => h.quoted && headingMatches(h, matcher));
  if (!quotedHit) return null;
  const next = heads.find((n) => n.line > quotedHit.line && n.level <= quotedHit.level);
  return makeSection(lines, quotedHit, next ? next.line - 1 : lines.length);
}

/**
 * Removes leading blockquote markers (each `>` with at most one following space or tab,
 * nested markers included) from every line; the line count is preserved.
 * @param {string} text
 * @returns {string}
 */
export function stripBlockquote(text) {
  return splitLines(asText(text, 'stripBlockquote'))
    .map((line) => line.replace(QUOTE_RE, ''))
    .join('\n');
}


/**
 * Splits a table line on every `|` that is neither backslash-escaped nor inside an inline
 * code span. Cells keep their raw Markdown, `\|` included.
 */
function splitRaw(s) {
  const spans = findCodeSpans(s);
  const cells = [];
  let cur = '';
  let next = 0;
  for (let i = 0; i < s.length; i++) {
    if (next < spans.length && i === spans[next].start) {
      cur += s.slice(i, spans[next].end);
      i = spans[next].end - 1;
      next++;
      continue;
    }
    const ch = s[i];
    if (ch === '\\' && i + 1 < s.length) {
      cur += ch + s[i + 1];
      i++;
      continue;
    }
    if (ch === '|') {
      cells.push(cur);
      cur = '';
      continue;
    }
    cur += ch;
  }
  cells.push(cur);
  return cells;
}

/** Trimmed cells; the empty edge cell a leading or trailing pipe produces is removed. */
function splitCells(s) {
  const cells = splitRaw(s).map((c) => c.trim());
  if (cells.length > 1 && cells[0] === '') cells.shift();
  if (cells.length > 1 && cells[cells.length - 1] === '') cells.pop();
  return cells;
}

const hasCellSeparator = (s) => splitRaw(s).length > 1;
const ALIGN_RE = /^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;

/**
 * GFM pipe tables outside fenced code, blockquoted ones included. A table is a line with a
 * cell separator followed by an alignment row with the same cell count; body rows run
 * until a blank line, a line without a separator, a heading, a fence or a change of
 * quoted state. Short rows are padded with ''; extra cells are kept so a malformed row
 * shows as row.length > header.length. `headingPath` lists the enclosing heading texts,
 * outermost first, with quoted headings counted.
 * @param {string} text
 * @returns {{ header: string[], rows: string[][], line: number, rowLines: number[], headingPath: string[] }[]}
 */
export function parseTables(text) {
  const lines = splitLines(asText(text, 'parseTables'));
  const mask = fenceMask(lines);
  const tables = [];
  const stack = [];
  let i = 0;
  while (i < lines.length) {
    if (mask[i]) {
      i++;
      continue;
    }
    const { quoted, rest } = unquote(lines[i]);
    const heading = matchAtx(rest);
    if (heading) {
      while (stack.length && stack[stack.length - 1].level >= heading.level) stack.pop();
      stack.push(heading);
      i++;
      continue;
    }
    if (i + 1 < lines.length && !mask[i + 1] && rest.trim() !== '' && hasCellSeparator(rest)) {
      const align = unquote(lines[i + 1]);
      if (align.quoted === quoted && align.rest.includes('|') && ALIGN_RE.test(align.rest)) {
        const header = splitCells(rest);
        if (splitCells(align.rest).length === header.length) {
          const rows = [];
          const rowLines = [];
          let j = i + 2;
          while (j < lines.length && !mask[j]) {
            const row = unquote(lines[j]);
            if (row.quoted !== quoted || row.rest.trim() === '' || matchAtx(row.rest) || !hasCellSeparator(row.rest)) {
              break;
            }
            const cells = splitCells(row.rest);
            while (cells.length < header.length) cells.push('');
            rows.push(cells);
            rowLines.push(j + 1);
            j++;
          }
          tables.push({ header, rows, line: i + 1, rowLines, headingPath: stack.map((h) => h.text) });
          i = j;
          continue;
        }
      }
    }
    i++;
  }
  return tables;
}

/**
 * True when the first cell, with `**` and `__` removed and trimmed, is "total" or "totals"
 * in any letter case.
 * @param {string[]} row
 * @returns {boolean}
 */
export function isTotalsRow(row) {
  const first = String(row?.[0] ?? '')
    .replace(/\*\*|__/g, '')
    .trim()
    .toLowerCase();
  return first === 'total' || first === 'totals';
}

/** Replaces inline code spans with spaces of equal length, so positions survive. */
function maskCodeSpans(line) {
  const spans = findCodeSpans(line);
  if (spans.length === 0) return line;
  let out = '';
  let pos = 0;
  for (const span of spans) {
    out += line.slice(pos, span.start) + ' '.repeat(span.end - span.start);
    pos = span.end;
  }
  return out + line.slice(pos);
}

const EXTERNAL_SCHEME_RE = /^(?:https?|mailto):/i;
const REF_DEF_RE = /^ {0,3}\[([^\]]+)\]:[ \t]*(<[^>]*>|\S+)(?:[ \t]+("[^"]*"|'[^']*'|\([^)]*\)))?[ \t]*$/;
const unescapePunct = (s) => s.replace(/\\([!-/:-@[-`{-~])/g, '$1');
const isBlank = (c) => c === ' ' || c === '\t';

function isEscaped(s, index) {
  let n = 0;
  for (let k = index - 1; k >= 0 && s[k] === '\\'; k--) n++;
  return n % 2 === 1;
}

/**
 * Matches an inline link whose text opens at `open` (a `[`) on a code-masked line: text
 * with up to one nested bracket level, then `(destination "optional title")`. The
 * destination is `<…>` or a space-free run with balanced parentheses.
 */
function matchInlineLink(s, open) {
  let depth = 0;
  let j = open + 1;
  for (; j < s.length; j++) {
    const c = s[j];
    if (c === '\\') {
      j++;
    } else if (c === '[') {
      depth++;
      if (depth > 1) return null;
    } else if (c === ']') {
      if (depth === 0) break;
      depth--;
    }
  }
  if (j >= s.length || s[j + 1] !== '(') return null;
  const textEnd = j;
  let k = j + 2;
  while (k < s.length && isBlank(s[k])) k++;
  let destStart;
  let destEnd;
  if (s[k] === '<') {
    destStart = k + 1;
    let m = destStart;
    while (m < s.length && s[m] !== '>') {
      if (s[m] === '<') return null;
      m += s[m] === '\\' ? 2 : 1;
    }
    if (m >= s.length) return null;
    destEnd = m;
    k = m + 1;
  } else {
    destStart = k;
    let parens = 0;
    while (k < s.length && !isBlank(s[k])) {
      const c = s[k];
      if (c === '\\' && k + 1 < s.length) {
        k += 2;
        continue;
      }
      if (c === '(') parens++;
      else if (c === ')') {
        if (parens === 0) break;
        parens--;
      }
      k++;
    }
    if (parens !== 0) return null;
    destEnd = k;
  }
  while (k < s.length && isBlank(s[k])) k++;
  if (s[k] === '"' || s[k] === "'" || s[k] === '(') {
    const closer = s[k] === '(' ? ')' : s[k];
    let m = k + 1;
    while (m < s.length && s[m] !== closer) m += s[m] === '\\' ? 2 : 1;
    if (m >= s.length) return null;
    k = m + 1;
    while (k < s.length && isBlank(s[k])) k++;
  }
  if (s[k] !== ')') return null;
  return { textEnd, destStart, destEnd, end: k + 1 };
}

/**
 * Inline links and images of one line, in position order (a link before the images or
 * links nested in its text). Structure comes from the code-masked line; text and target
 * are sliced from the original line at the same positions.
 */
function inlineLinks(masked, original) {
  const found = [];
  let i = 0;
  while (i < masked.length) {
    const ch = masked[i];
    if (ch === '\\') {
      i += 2;
      continue;
    }
    if (ch !== '[') {
      i++;
      continue;
    }
    const link = matchInlineLink(masked, i);
    if (!link) {
      i++;
      continue;
    }
    const image = i > 0 && masked[i - 1] === '!' && !isEscaped(masked, i - 1);
    found.push({
      text: original.slice(i + 1, link.textEnd),
      target: unescapePunct(original.slice(link.destStart, link.destEnd)),
      image,
    });
    found.push(...inlineLinks(masked.slice(i + 1, link.textEnd), original.slice(i + 1, link.textEnd)));
    i = link.end;
  }
  return found;
}

/**
 * Inline links, images (flagged `image: true`) and reference definitions outside fenced
 * code and inline code. Targets with an http:, https: or mailto: scheme are excluded;
 * any other scheme is returned so callers can flag it as non-relative.
 * @param {string} text
 * @returns {{ text: string, target: string, line: number, image?: boolean }[]}
 */
export function parseLinks(text) {
  const lines = splitLines(asText(text, 'parseLinks'));
  const mask = fenceMask(lines);
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    if (mask[i]) continue;
    const line = lines[i];
    const def = REF_DEF_RE.exec(unquote(line).rest);
    // "[^label]:" opens a footnote, whose text is not a link target.
    if (def && !def[1].startsWith('^')) {
      const raw = def[2].startsWith('<') ? def[2].slice(1, -1) : def[2];
      const target = unescapePunct(raw);
      if (!EXTERNAL_SCHEME_RE.test(target)) out.push({ text: def[1], target, line: i + 1 });
      continue;
    }
    for (const link of inlineLinks(maskCodeSpans(line), line)) {
      if (EXTERNAL_SCHEME_RE.test(link.target)) continue;
      const entry = { text: link.text, target: link.target, line: i + 1 };
      if (link.image) entry.image = true;
      out.push(entry);
    }
  }
  return out;
}

/**
 * Resolves a link target against the root-relative file that holds it. The target is
 * percent-decoded (kept raw when decoding fails) and split at the first `#`. An
 * anchor-only target resolves to the file itself; a leading-slash path is returned as is
 * so callers can flag it; a path that escapes the root starts with `..`. Pure: no disk
 * access and no guard.
 * @param {string} fromRel
 * @param {string} target
 * @returns {{ path: string, anchor: string | null }}
 */
export function resolveLink(fromRel, target) {
  const rawTarget = String(target ?? '');
  let decoded;
  try {
    decoded = decodeURIComponent(rawTarget);
  } catch {
    decoded = rawTarget; // a malformed escape such as "%zz" is kept as written
  }
  const hash = decoded.indexOf('#');
  const linkPath = hash < 0 ? decoded : decoded.slice(0, hash);
  const anchor = hash < 0 ? null : decoded.slice(hash + 1);
  if (linkPath === '') return { path: fromRel, anchor };
  if (linkPath.startsWith('/')) return { path: linkPath, anchor };
  let resolved = path.posix.normalize(path.posix.join(path.posix.dirname(fromRel), linkPath));
  if (resolved.length > 1 && resolved.endsWith('/')) resolved = resolved.slice(0, -1);
  return { path: resolved, anchor };
}

/**
 * The first inline link or image in a table cell, outside inline code, or null. No scheme
 * is excluded; the caller decides what an external target means.
 * @param {string} cell
 * @returns {{ text: string, target: string } | null}
 */
export function linkInCell(cell) {
  const s = String(cell ?? '');
  const [first] = inlineLinks(maskCodeSpans(s), s);
  return first ? { text: first.text, target: first.target } : null;
}


const BOLD_FIELD_RE = /^(?:[-*+][ \t]+)?(\*\*|__)(.+?)\1([ \t]*:)?[ \t]*(.*)$/;
const PLAIN_FIELD_RE = /^(?:[-*+][ \t]+)?([^:|]+?):[ \t]*(.*)$/;

function metadataKey(raw) {
  return raw
    .replace(/\*\*|__/g, '')
    .trim()
    .replace(/:$/, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
}

/** The field entries of metadataEntries; `fn` names the public caller in a type error. */
function collectMetadataEntries(sectionText, fn) {
  const lines = splitLines(asText(sectionText, fn));
  const mask = fenceMask(lines);
  const entries = [];
  const tableLines = new Set();
  for (const table of parseTables(sectionText)) {
    tableLines.add(table.line);
    tableLines.add(table.line + 1);
    table.rowLines.forEach((line) => tableLines.add(line));
    if (table.header.length < 2) continue;
    table.rows.forEach((row, r) => entries.push({ line: table.rowLines[r], key: metadataKey(row[0]), value: row[1].trim() }));
  }
  for (let i = 0; i < lines.length; i++) {
    if (mask[i] || tableLines.has(i + 1)) continue;
    const s = lines[i].trim();
    if (s === '' || s.startsWith('|') || matchAtx(s)) continue;
    const bold = BOLD_FIELD_RE.exec(s);
    // A bold label is a field only with a colon inside or after it; "**Note** text" is prose.
    if (bold && (bold[3] !== undefined || bold[2].trimEnd().endsWith(':'))) {
      entries.push({ line: i + 1, key: metadataKey(bold[2]), value: bold[4].trim() });
      continue;
    }
    const plain = PLAIN_FIELD_RE.exec(s);
    if (plain) entries.push({ line: i + 1, key: metadataKey(plain[1]), value: plain[2].trim() });
  }
  entries.sort((a, b) => a.line - b.line);
  return entries.filter((entry) => entry.key !== '');
}

/**
 * Every metadata field entry of a section, in document line order, repeated keys
 * included, so a caller can see a field given twice or a field outside its expected set.
 * `line` is 1-based within sectionText, `key` the lower-cased, whitespace-collapsed field
 * name (`'estimated files touched'`, `'fix version/s'`) and `value` the trimmed raw text.
 * Line forms: `- **Field:** v`, `* **Field:** v`, `- **Field**: v`, `- Field: v`,
 * `**Field:** v` and `Field: v`; table form: every body row of a table of two or more
 * columns maps its first cell to its second. Fenced code, headings and table lines are not
 * read as line forms, and an entry whose key is empty is dropped.
 * @param {string} sectionText
 * @returns {{ line: number, key: string, value: string }[]}
 */
export function metadataEntries(sectionText) {
  return collectMetadataEntries(sectionText, 'metadataEntries');
}

/**
 * Metadata fields of a section as a plain object built from metadataEntries: keyed by the
 * lower-cased, whitespace-collapsed field name and valued by the trimmed raw text, with the
 * same line and table forms. The first occurrence of a key in document order wins; a
 * later repeat is visible only through metadataEntries.
 * @param {string} sectionText
 * @returns {Record<string, string>}
 */
export function parseMetadata(sectionText) {
  const out = {};
  for (const { key, value } of collectMetadataEntries(sectionText, 'parseMetadata')) {
    if (Object.prototype.hasOwnProperty.call(out, key)) continue;
    // defineProperty keeps a key such as "__proto__" an own data property.
    Object.defineProperty(out, key, { value, enumerable: true, writable: true, configurable: true });
  }
  return out;
}

/**
 * A non-negative integer written as digits only, else null: '12' → 12; '1-2', 'TBD',
 * '' and '5 files' → null.
 * @param {unknown} s
 * @returns {number | null}
 */
export function intStrict(s) {
  const t = String(s ?? '').trim();
  return /^\d+$/.test(t) ? Number(t) : null;
}

/**
 * Prints each violation as `<check>: <path>:<line>: <message>` (`:<line>` omitted when
 * absent, `<check>: <message>` when path is empty), sorted by path, line and message,
 * then the final line `<check>: <N> violation(s)` that run-all.sh parses. Sets
 * process.exitCode to 0 or 1 and returns N; never calls process.exit.
 * @param {string} check
 * @param {{ path: string, line?: number, message: string }[]} violations
 * @returns {number}
 */
export function report(check, violations) {
  const list = Array.from(violations ?? [], (v) => ({
    path: String(v?.path ?? ''),
    line: v?.line == null ? undefined : Number(v.line),
    message: String(v?.message ?? ''),
  }));
  list.sort(
    (a, b) =>
      compareCodeUnits(a.path, b.path) || (a.line ?? 0) - (b.line ?? 0) || compareCodeUnits(a.message, b.message),
  );
  for (const v of list) {
    if (v.path === '') console.log(`${check}: ${v.message}`);
    else if (v.line === undefined) console.log(`${check}: ${v.path}: ${v.message}`);
    else console.log(`${check}: ${v.path}:${v.line}: ${v.message}`);
  }
  console.log(`${check}: ${list.length} violation(s)`);
  process.exitCode = list.length === 0 ? 0 : 1;
  return list.length;
}

/**
 * Absolute path of a locally installed tool binary (buf, spectral, mmdc) under
 * tests/validation/node_modules/.bin, or null when it is not installed. Callers run it
 * with cwd repoRoot and report a missing binary as their own violation.
 * @param {string} name
 * @returns {string | null}
 */
export function toolBin(name) {
  const n = String(name ?? '');
  if (n === '' || n !== path.basename(n)) throw new TypeError(`toolBin: not a binary name: ${n}`);
  const bin = path.join(repoRoot, 'tests', 'validation', 'node_modules', '.bin', n);
  return fs.existsSync(bin) ? bin : null;
}


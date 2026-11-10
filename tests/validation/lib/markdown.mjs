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
 * repository-root-relative POSIX paths and throw on such a path, judged lexically and
 * then through every symbolic link in it: each component is examined with lstat from
 * the repository root, a link's relative target is resolved from the link's own folder,
 * and a destination outside the repository or inside a reference root, an absolute link
 * target, or a chain of more than 40 links throws before the destination is touched.
 * Past a missing component the path is judged by its lexical destination, so a dangling
 * link into a reference root throws here and in the delivery repository alike. Walks
 * confine every link they meet the same way, and listEntries never follows one.
 * Callers classify link targets with isReferencePath before touching the filesystem.
 *
 * Node.js built-ins (node:fs, node:path, node:url) and the suite's own entity table,
 * html-entities.mjs, only; no side effect at import time beyond computing repoRoot.
 * Every parser skips fenced code and HTML comments:
 * headings, tables, links and metadata inside a fenced code block or an HTML comment
 * block are ignored, links inside an inline HTML comment are ignored too, fence content
 * is reached only through fencedBlocks, and hiddenLines names every line of both kinds
 * of block for checks that scan lines themselves. A fence or comment opened inside a
 * blockquote ends when the quote level it opened in ends, even if an outer quote
 * continues. Line numbers are 1-based indexes into the text split on /\r?\n/.
 *
 * Links come from one left-to-right scanner that follows CommonMark's inline rules
 * for links, images, reference links and autolinks, adds GitHub's autolink literals
 * and reads a paragraph's lines together. It approximates block structure and does
 * not model indented code blocks, list-container indentation beyond the paragraph a
 * list item opens, or inline raw HTML other than comments.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NAMED_CHARACTER_REFERENCES } from './html-entities.mjs';

/** Absolute repository root; this file sits at <root>/tests/validation/lib/. */
export const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** Top-level CardDemo reference roots that validation must never read. */
export const REFERENCE_ROOTS = Object.freeze(['app', 'diagrams', 'samples']);

/**
 * Root-relative paths that both walkers, listFiles and listEntries, neither report nor
 * descend: the repository's own metadata and installed packages at its root, the suite's
 * installed tools, and the reference roots. Each is matched as a whole root-relative path,
 * so a node_modules or .git anywhere else, under docs/ for example, is listed like any
 * other content.
 */
const LISTING_EXCLUSIONS = new Set(['.git', 'node_modules', 'tests/validation/node_modules', ...REFERENCE_ROOTS]);

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

/** Most symbolic links one path may pass through, the same limit Linux applies. */
const MAX_LINK_HOPS = 40;

/**
 * Resolves a root-relative path to an absolute one, enforcing the portability guard: the
 * lexical path is tested first, then every symbolic link in it is followed by confineLinks
 * before anything touches the destination. The returned path is the lexical one, which
 * reaches only the destination confined here.
 * @param {string} rel
 * @returns {string}
 * @throws {Error} `path outside repository: <rel>`, `reference source is off-limits to
 *   validation: <rel>` or `too many symbolic links: <rel>`; when a symbolic link led there,
 *   the message ends with `(symbolic link <link> -> <target>)`
 */
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
  confineLinks(r, fromRoot === '' ? [] : segments);
  return abs;
}

/**
 * The physical half of the guard. Walks the lexically normalised components of `rel` from
 * repoRoot one at a time with lstat, which never follows a link. A symbolic link is replaced
 * by its target, resolved from the link's own folder: each `..` leaves a folder already
 * resolved, and leaving repoRoot is outside the repository. Each resolved prefix is tested
 * against the reference roots before it is touched, so nothing under them is ever statted.
 * An absolute target throws, because it does not move with the repository, and so does a
 * chain of more than MAX_LINK_HOPS links. Once a component is missing (ENOENT) or sits
 * under a non-folder (ENOTDIR), the rest of the path is judged by its lexical destination,
 * so a dangling link into a reference root throws here and in the delivery repository alike.
 * @param {string} r the caller's path, for messages
 * @param {string[]} segments lexically normalised components below repoRoot
 */
function confineLinks(r, segments) {
  const resolved = [];
  const pending = [...segments];
  let hops = 0;
  let physical = true;
  let via = '';
  while (pending.length > 0) {
    const name = pending.shift();
    if (name === '' || name === '.') continue;
    if (name === '..') {
      if (resolved.length === 0) throw new Error(`path outside repository: ${r}${via}`);
      resolved.pop();
      continue;
    }
    resolved.push(name);
    if (REFERENCE_ROOTS.includes(resolved[0])) {
      throw new Error(`reference source is off-limits to validation: ${r}${via}`);
    }
    if (!physical) continue;
    const abs = path.join(repoRoot, ...resolved);
    let stat;
    try {
      stat = fs.lstatSync(abs);
    } catch (err) {
      if (err.code !== 'ENOENT' && err.code !== 'ENOTDIR') throw err;
      physical = false;
      continue;
    }
    if (!stat.isSymbolicLink()) continue;
    const target = fs.readlinkSync(abs, 'utf8');
    via = ` (symbolic link ${resolved.join('/')} -> ${target})`;
    if (path.isAbsolute(target)) {
      throw new Error(`path outside repository: ${r}${via.slice(0, -1)}, an absolute target that does not move with the repository)`);
    }
    hops += 1;
    if (hops > MAX_LINK_HOPS) throw new Error(`too many symbolic links: ${r}${via}`);
    resolved.pop();
    pending.unshift(...target.split('/'));
  }
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
 * 'file', 'dir' or 'other' for an existing root-relative path, null when it is missing or
 * sits under a non-directory (ENOENT, ENOTDIR). The portability guard runs first, so a
 * symbolic link is followed only once its destination is confined.
 * @param {string} rel
 * @returns {'file' | 'dir' | 'other' | null}
 */
export function pathKind(rel) {
  return statKind(toAbs(rel));
}

/** pathKind for an absolute path the guard has already confined; follows symbolic links. */
function statKind(abs) {
  let stat;
  try {
    stat = fs.statSync(abs);
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'ENOTDIR') return null;
    throw err;
  }
  return stat.isFile() ? 'file' : stat.isDirectory() ? 'dir' : 'other';
}

/**
 * The guarded start of a walk as { abs, rel } with rel in root-relative POSIX form, or null
 * when the directory is missing or is not a directory.
 */
function walkStart(relDir) {
  const abs = toAbs(relDir);
  if (statKind(abs) !== 'dir') return null;
  return { abs, rel: path.relative(repoRoot, abs).split(path.sep).join('/') };
}

/**
 * Depth-first walk that never follows a symbolic link: entries come from readdir's Dirents,
 * so a link is seen as itself and its target is neither statted nor read here.
 * visit(entry, childRel, childAbs) sees every entry, and the walk descends into a real
 * directory only when visit returns true.
 */
function walkDirents(absDir, relPosix, visit) {
  for (const entry of fs.readdirSync(absDir, { withFileTypes: true })) {
    const childRel = relPosix === '' ? entry.name : `${relPosix}/${entry.name}`;
    const childAbs = path.join(absDir, entry.name);
    if (visit(entry, childRel, childAbs) && entry.isDirectory()) walkDirents(childAbs, childRel, visit);
  }
}

/**
 * Lists every file below a root-relative directory, recursively, as sorted root-relative
 * POSIX paths. Dotfiles are included so the path-form gate of the Epic and Story
 * Structure — Multi-File Variant rule sees stray files. Only LISTING_EXCLUSIONS are left
 * out: .git and node_modules directly under the repository root, tests/validation/node_modules,
 * and the reference roots when walking from the root; a node_modules or .git anywhere else
 * is listed like other content. The start directory and every symbolic link met pass the
 * portability guard before anything stats them, and an unsafe link throws. A safe link to a
 * file is listed under its own path, a link to a folder is not descended, so a link can
 * neither loop nor widen the walk, and a dangling link is skipped. A missing path or a
 * non-directory gives [].
 * @param {string} relDir
 * @param {{ exts?: string[] }} [options] extensions matched case-sensitively against path.extname
 * @returns {string[]}
 * @throws {Error} the portability guard's error for an unsafe start directory or link
 */
export function listFiles(relDir, { exts } = {}) {
  if (exts != null && !Array.isArray(exts)) {
    throw new TypeError('listFiles: exts must be an array of extensions such as [".md"]');
  }
  const start = walkStart(relDir);
  if (start === null) return [];
  const extSet = exts ? new Set(exts) : null;
  const wanted = (name) => !extSet || extSet.has(path.extname(name));
  const out = [];
  walkDirents(start.abs, start.rel, (entry, childRel, childAbs) => {
    if (LISTING_EXCLUSIONS.has(childRel)) return false;
    if (entry.isSymbolicLink()) {
      toAbs(childRel);
      if (statKind(childAbs) === 'file' && wanted(entry.name)) out.push(childRel);
      return false;
    }
    if (entry.isFile() && wanted(entry.name)) out.push(childRel);
    return entry.isDirectory();
  });
  return out.sort(compareCodeUnits);
}

/**
 * Every entry below a root-relative directory, recursively, as { path, type } sorted by
 * path in code-unit order, with type 'file', 'dir', 'symlink' or 'other'. A symbolic link
 * is reported as 'symlink' and never followed, statted or read; only real directories are
 * descended. LISTING_EXCLUSIONS are neither reported nor descended, the same as in
 * listFiles, so the reference roots are never listed, even from the repository root; a
 * node_modules or .git anywhere else is reported like other content. The start directory
 * passes the portability guard first. A missing path or a non-directory gives [].
 * @param {string} relDir
 * @returns {{ path: string, type: 'file' | 'dir' | 'symlink' | 'other' }[]}
 * @throws {Error} the portability guard's error for an unsafe start directory
 */
export function listEntries(relDir) {
  const start = walkStart(relDir);
  if (start === null) return [];
  const out = [];
  walkDirents(start.abs, start.rel, (entry, childRel) => {
    if (LISTING_EXCLUSIONS.has(childRel)) return false;
    let type = 'other';
    if (entry.isSymbolicLink()) type = 'symlink';
    else if (entry.isDirectory()) type = 'dir';
    else if (entry.isFile()) type = 'file';
    out.push({ path: childRel, type });
    return type === 'dir';
  });
  return out.sort((a, b) => compareCodeUnits(a.path, b.path));
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

/** Strips leading blockquote markers, nested ones included; `depth` counts the `>` markers. */
function unquote(line) {
  const m = QUOTE_RE.exec(line);
  return m
    ? { quoted: true, rest: line.slice(m[0].length), depth: m[0].split('>').length - 1 }
    : { quoted: false, rest: line, depth: 0 };
}

/**
 * The line with only its first `depth` blockquote markers removed, each with the one space or
 * tab QUOTE_RE allows after it; deeper markers stay as content.
 */
function stripQuoteMarkers(line, depth) {
  const m = depth > 0 ? QUOTE_RE.exec(line) : null;
  if (!m) return line;
  let seen = 0;
  for (let k = 0; k < m[0].length; k++) {
    if (m[0][k] === '>' && ++seen === depth) {
      const next = m[0][k + 1];
      return line.slice(next === ' ' || next === '\t' ? k + 2 : k + 1);
    }
  }
  return line.slice(m[0].length);
}

const FENCE_OPEN_RE = /^( {0,3})(`{3,}|~{3,})(.*)$/;
const FENCE_CLOSE_RE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/;
const COMMENT_OPEN_RE = /^ {0,3}<!--/;

/** True when `content`, a line with the block's own quote markers removed, ends the open block. */
function closesBlock(block, content) {
  if (block.kind === 'comment') return content.includes('-->');
  const m = FENCE_CLOSE_RE.exec(content);
  return m !== null && m[1][0] === block.ch && m[1].length >= block.len;
}

/**
 * Finds, over split lines, the blocks whose lines the heading, table, link and metadata
 * parsers skip: fenced code blocks (`kind: 'fence'`) and HTML comment blocks
 * (`kind: 'comment'`, CommonMark HTML block type 2). Returns 0-based opening and last lines.
 * A comment block opens on a line whose content after its quote markers starts with `<!--`
 * after at most three spaces, and its last line is the first one, the opening line
 * included, that contains `-->`; text after the `-->` belongs to the block. Inside a fence
 * `<!--` is code, and inside a comment block a fence is comment text. A block opened
 * inside a blockquote ends with the quote level it opened in, as in CommonMark, even where
 * an outer quote continues, and a fence closes only at that level, so a deeper `> ~~~` is
 * code. A block opened outside any quote treats quoted lines as its content. An unclosed
 * block otherwise runs to the last line.
 */
function scanBlocks(lines) {
  const blocks = [];
  let open = null;
  for (let i = 0; i < lines.length; i++) {
    const { rest, depth } = unquote(lines[i]);
    if (open && depth < open.depth) {
      blocks.push({ ...open, end: i - 1, closed: false });
      open = null;
    }
    if (open) {
      if (closesBlock(open, stripQuoteMarkers(lines[i], open.depth))) {
        blocks.push({ ...open, end: i, closed: true });
        open = null;
      }
      continue;
    }
    const m = FENCE_OPEN_RE.exec(rest);
    if (m) {
      const run = m[2];
      const info = m[3];
      if (run[0] === '`' && info.includes('`')) continue;
      open = { kind: 'fence', ch: run[0], len: run.length, indent: m[1].length, info: info.trim(), depth, start: i };
      continue;
    }
    if (!COMMENT_OPEN_RE.test(rest)) continue;
    open = { kind: 'comment', depth, start: i };
    if (closesBlock(open, rest)) {
      blocks.push({ ...open, end: i, closed: true });
      open = null;
    }
  }
  if (open) blocks.push({ ...open, end: lines.length - 1, closed: false });
  return blocks;
}

/**
 * Boolean per split line: true on every line of a fenced code block, its fences included,
 * and of an HTML comment block.
 */
function hiddenMask(lines) {
  const mask = new Array(lines.length).fill(false);
  for (const b of scanBlocks(lines)) {
    for (let i = b.start; i <= b.end; i++) mask[i] = true;
  }
  return mask;
}

/**
 * End, exclusive, of the HTML comment whose `<!--` sits at `i` (CommonMark 0.31: `<!-->`,
 * `<!--->`, or `<!--`, text without `-->`, then `-->`), or -1 when no `-->` closes it in `s`.
 */
function commentEnd(s, i) {
  if (s.startsWith('<!-->', i)) return i + 5;
  if (s.startsWith('<!--->', i)) return i + 6;
  const close = s.indexOf('-->', i + 4);
  return close < 0 ? -1 : close + 3;
}

/**
 * The inline link of `s` whose text, matched by the caller, closes at the `]` at `close`:
 * the inlineTail that must follow that `]`, read on raw characters, with no line break
 * between the `]` and the link's end, or null.
 */
function linkClosedAt(s, close) {
  const tail = s[close + 1] === '(' ? inlineTail(s, close + 1) : null;
  return tail && !s.slice(close, tail.end).includes('\n') ? tail : null;
}

/**
 * One paragraph run, its lines' contents joined by '\n', with every character of each
 * inline HTML comment other than '\n' turned into a space. Scanning runs left to right, as
 * GitHub parses inline content, so whichever construct starts first wins. A backslash
 * escapes the next character and a code span is skipped, so neither opens a comment, and a
 * `<!--` that no `-->` closes is literal text. Each unescaped `[` reached opens link text
 * and each `]` reached closes the latest one still open; a comment that starts before a
 * `]` hides it. Where `(` follows the `]` and an inline link, with the comments before it
 * blanked, closes there and ends on the same line, the scan resumes after the link, so a
 * `<!--` in its destination or title is literal text. A link (not an image) stops
 * every earlier `[` but `![` from opening a link, since links do not nest. Where a `](`
 * forms no link when reached and blanking a later comment would complete one there, its
 * `(` is blanked too, so the result forms no link the run does not.
 */
function blankRunComments(s) {
  let out = '';
  let copied = 0;
  let base = 0;
  let spans = findCodeSpans(s);
  let next = 0;
  const openers = [];
  const unlinked = [];
  let i = 0;
  while (i < s.length) {
    if (next < spans.length && base + spans[next].start === i) {
      i = base + spans[next].end;
      next++;
      continue;
    }
    if (s[i] === '\\') {
      i += 2;
      continue;
    }
    if (s[i] === '[') {
      openers.push({ at: i, image: i > 0 && s[i - 1] === '!' && !isEscaped(s, i - 1), active: true });
      i++;
      continue;
    }
    if (s[i] === ']') {
      const opener = openers.pop();
      if (opener?.active && s[i + 1] === '(') {
        const link = linkClosedAt(out + s.slice(copied), i);
        if (link) {
          if (!opener.image) for (const o of openers) if (!o.image) o.active = false;
          i = link.end;
          while (next < spans.length && base + spans[next].start < i) next++;
          continue;
        }
        unlinked.push({ close: i });
      }
      i++;
      continue;
    }
    const end = s.startsWith('<!--', i) ? commentEnd(s, i) : -1;
    if (end < 0) {
      i++;
      continue;
    }
    out += s.slice(copied, i) + s.slice(i, end).replace(/[^\n]/g, ' ');
    copied = end;
    i = end;
    // Backticks inside a comment open no code span, so spans are matched again from its end.
    base = end;
    spans = findCodeSpans(s.slice(end));
    next = 0;
  }
  let blanked = out + s.slice(copied);
  for (const { close } of unlinked) {
    if (linkClosedAt(blanked, close)) blanked = `${blanked.slice(0, close + 1)} ${blanked.slice(close + 2)}`;
  }
  return blanked;
}

const LIST_ITEM_RE = /^ {0,3}(?:[-+*]|\d{1,9}[.)])(?:[ \t]|$)/;

/**
 * The lines with every inline HTML comment blanked to spaces of equal length, so positions
 * survive; quote markers and the lines `hidden` marks are returned unchanged. A comment
 * may continue over the following lines of its paragraph run, which a blank line, a hidden
 * line, an ATX heading line, a line starting a list item or a change of quote depth ends; a
 * `<!--` that no `-->` closes within its run is literal text. Each run is scanned left to
 * right as blankRunComments states: a `<!--` inside an inline link's destination or title
 * is literal text, a comment that opens before a link text's `]` hides that `]`, and a
 * blanked comment never completes a link the source does not form.
 * @param {string[]} lines
 * @param {boolean[]} hidden
 * @returns {string[]}
 */
function blankInlineComments(lines, hidden) {
  const out = lines.slice();
  let i = 0;
  while (i < lines.length) {
    const first = unquote(lines[i]);
    if (hidden[i] || first.rest.trim() === '') {
      i++;
      continue;
    }
    let j = i + 1;
    if (!matchAtx(first.rest)) {
      while (j < lines.length && !hidden[j]) {
        const q = unquote(lines[j]);
        if (q.depth !== first.depth || q.rest.trim() === '' || matchAtx(q.rest) || LIST_ITEM_RE.test(q.rest)) break;
        j++;
      }
    }
    const contents = lines.slice(i, j).map((line) => unquote(line).rest);
    const joined = contents.join('\n');
    if (joined.includes('<!--')) {
      const blanked = blankRunComments(joined);
      let at = 0;
      contents.forEach((content, k) => {
        const line = lines[i + k];
        out[i + k] = line.slice(0, line.length - content.length) + blanked.slice(at, at + content.length);
        at += content.length + 1;
      });
    }
    i = j;
  }
  return out;
}

/**
 * Fenced code blocks outside HTML comment blocks, blockquoted ones included.
 * `body` holds the lines strictly between the fences (to the end of the block when
 * unclosed), with the blockquote markers of the fence's own quote level and then the
 * opener's indentation removed.
 * @param {string} text
 * @returns {{ lang: string, body: string, startLine: number, endLine: number }[]}
 */
export function fencedBlocks(text) {
  const lines = splitLines(asText(text, 'fencedBlocks'));
  const fences = scanBlocks(lines).filter((b) => b.kind === 'fence');
  return fences.map((b) => {
    const lastBody = b.closed ? b.end - 1 : b.end;
    const body = [];
    for (let i = b.start + 1; i <= lastBody; i++) {
      const raw = stripQuoteMarkers(lines[i], b.depth);
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
 * The lines every parser here skips, for checks that scan prose lines themselves: each line
 * of a fenced code block, its fences included, and of an HTML comment block, its `<!--` and
 * `-->` lines included. They come from the block scan the heading, table, link and metadata
 * parsers use, quote depth included. fencedBlocks, by contrast, returns fenced code only.
 * @param {string} text
 * @returns {Set<number>} 1-based line numbers
 */
export function hiddenLines(text) {
  const mask = hiddenMask(splitLines(asText(text, 'hiddenLines')));
  const out = new Set();
  mask.forEach((hidden, i) => {
    if (hidden) out.add(i + 1);
  });
  return out;
}

/**
 * The code span opened by the whole backtick run that starts at `i`, CommonMark style: the
 * next run of exactly the same length closes it. Line endings in the content become
 * spaces, then one leading and one trailing space are removed from content that has both
 * and is not all spaces. Returns { end, content } with `end` exclusive, or null when no
 * run closes it, in which case the opening run is literal text.
 */
function codeSpanAt(s, i) {
  let j = i;
  while (j < s.length && s[j] === '`') j++;
  const len = j - i;
  let k = j;
  while (k < s.length) {
    if (s[k] !== '`') {
      k++;
      continue;
    }
    let m = k;
    while (m < s.length && s[m] === '`') m++;
    if (m - k === len) {
      let content = s.slice(j, k).replace(/\r\n|\r|\n/g, ' ');
      if (content.length >= 2 && content[0] === ' ' && content[content.length - 1] === ' ' && /[^ ]/.test(content)) {
        content = content.slice(1, -1);
      }
      return { end: m, content };
    }
    k = m;
  }
  return null;
}

/**
 * Inline code spans of a string, CommonMark style: a backtick run opens a span that the
 * next run of the same length closes, and an unmatched run is literal. A backslash
 * outside code escapes the next character, so an escaped backtick never opens a span. A
 * span may cross a line ending, which its content holds as a space; one leading and one
 * trailing space are trimmed from content that has both and is not all spaces.
 * @param {string} s
 * @returns {{ start: number, end: number, content: string }[]} offsets into `s`, `end` exclusive
 */
export function findCodeSpans(s) {
  asText(s, 'findCodeSpans');
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
    const span = codeSpanAt(s, i);
    if (!span) {
      while (i < s.length && s[i] === '`') i++;
      continue;
    }
    spans.push({ start: i, end: span.end, content: span.content });
    i = span.end;
  }
  return spans;
}

const CHAR_REF_RE = /&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z][A-Za-z0-9]*));/y;

/**
 * The text the named character reference `&name;` stands for, or undefined for a name not in
 * the list. The lookup reads only the `;` entries of the WHATWG table in html-entities.mjs:
 * CommonMark decodes no legacy name without its `;`.
 */
function namedReference(name) {
  return NAMED_CHARACTER_REFERENCES.get(`${name};`);
}

/**
 * The CommonMark character reference opening at `s[i]` as { value, end } with `end`
 * exclusive, or null: `&name;` for a name in the WHATWG list, `&#` with 1–7 decimal digits
 * or `&#x`/`&#X` with 1–6 hex digits, then `;`. A numeric reference to code point 0, a
 * surrogate or a code point above U+10FFFF stands for U+FFFD.
 */
function charReference(s, i) {
  CHAR_REF_RE.lastIndex = i;
  const m = CHAR_REF_RE.exec(s);
  if (!m) return null;
  let value;
  if (m[3] !== undefined) {
    value = namedReference(m[3]);
    if (value === undefined) return null;
  } else {
    const cp = m[1] !== undefined ? parseInt(m[1], 10) : parseInt(m[2], 16);
    value = cp === 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) ? '\uFFFD' : String.fromCodePoint(cp);
  }
  return { value, end: i + m[0].length };
}

// Private-use code points delimit placeholders. protectInline holds each of them found in
// its input as a placeholder too, so every delimiter in its output is one it wrote. They are
// neither letters nor digits, so emphasis stripping treats them as word boundaries.
const PH_OPEN = '\uE000';
const PH_CLOSE = '\uE001';
const PH_RE = /\uE000(\d+)\uE001/g;
const ASCII_PUNCT_RE = /[!-/:-@[-`{-~]/;
const HTML_RE = /<!--[\s\S]*?-->|<\/?[A-Za-z][^<>]*>/g;
const UNDERSCORE_EMPHASIS_RE = /(^|[^\p{L}\p{N}])_{1,2}(?=\S)(.+?)(?<=\S)_{1,2}(?=[^\p{L}\p{N}]|$)/gu;

/**
 * Replaces code spans, backslash-escaped punctuation, character references and the
 * placeholder delimiters themselves with placeholders. A reference's placeholder holds its
 * decoded text, so a decoded `*`, `_`, `<` or `[` is never read as markup; inside code or
 * after `\` an `&` stays literal. Returns { out, held }: placeholder n stands for held[n].
 */
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
    const ref = s[i] === '&' ? charReference(s, i) : null;
    if (ref) {
      out += hold(ref.value);
      i = ref.end - 1;
      continue;
    }
    out += s[i] === PH_OPEN || s[i] === PH_CLOSE ? hold(s[i]) : s[i];
  }
  return { out, held };
}

/**
 * A link destination decoded as CommonMark's unescapeString decodes it, in one left-to-right
 * pass: a backslash before ASCII punctuation gives that character, so `\&amp;` stays the text
 * `&amp;`; a character reference gives the text it stands for (charReference); every other
 * character is kept. Decoded text is not read again, so a decoded `\` or `&` is literal.
 */
function unescapeString(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && i + 1 < s.length && ASCII_PUNCT_RE.test(s[i + 1])) {
      out += s[i + 1];
      i++;
      continue;
    }
    const ref = s[i] === '&' ? charReference(s, i) : null;
    if (ref) {
      out += ref.value;
      i = ref.end - 1;
      continue;
    }
    out += s[i];
  }
  return out;
}

/**
 * Inline links and images of a protected string reduced to their text: a link keeps its
 * text and an image (`!` before the `[`) its alt text, each reduced in turn, while the
 * destination and title are dropped. As in CommonMark, a link whose text holds a link is
 * not a link, so its brackets stay literal, and so does a `[` that opens no link.
 * Returns { text, linked }, with `linked` true when a link, not only images, was reduced.
 */
function reduceLinks(s) {
  let text = '';
  let linked = false;
  let i = 0;
  while (i < s.length) {
    const link = s[i] === '[' ? matchInlineLink(s, i) : null;
    if (link) {
      const image = i > 0 && s[i - 1] === '!';
      const inner = reduceLinks(s.slice(i + 1, link.textEnd));
      if (image || !inner.linked) {
        text = (image ? text.slice(0, -1) : text) + inner.text;
        linked = linked || inner.linked || !image;
        i = link.end;
        continue;
      }
    }
    text += s[i];
    i++;
  }
  return { text, linked };
}

/**
 * Heading text as GitHub renders it, before slugging: code spans reduced to their content,
 * character references (`&amp;`, `&#65;`, `&#x41;`) decoded, images reduced to their alt text
 * and links to their text with the destination (balanced parentheses included) and title
 * dropped, HTML tags and comments removed, `*` removed and `_`/`__` emphasis delimiters
 * removed at word boundaries (intraword underscores survive). Backslash-escaped punctuation,
 * code-span content and decoded characters stay literal. Used by sectionByHeading for matching.
 * @param {string} text
 * @returns {string}
 */
export function plainText(text) {
  const { out, held } = protectInline(String(text ?? ''));
  const stripped = reduceLinks(out)
    .text.replace(HTML_RE, '')
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
 * ATX headings outside fenced code and HTML comment blocks, blockquoted ones included and
 * flagged `quoted`.
 * `text` is the trimmed raw content; `slug` is unique within the document.
 * @param {string} text
 * @returns {{ level: number, text: string, line: number, slug: string, quoted: boolean }[]}
 */
export function parseHeadings(text) {
  const lines = splitLines(asText(text, 'parseHeadings'));
  const mask = hiddenMask(lines);
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
const ALIGN_RE = /^ {0,3}(?:\|\s*)?:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;
/** Four columns of indentation, as four spaces or a tab within the first four, make indented code. */
const CODE_INDENT_RE = /^(?: {0,3}\t| {4})/;

/**
 * GFM pipe tables outside fenced code and HTML comment blocks, blockquoted ones included.
 * A table is a line with a cell separator followed by an alignment row with the same cell
 * count, each indented at most three spaces (more, or a tab, is indented code); body rows
 * run until a blank line, a line without a separator, a heading, a fence, an HTML comment
 * block or a change of quote depth. Short rows are padded with ''; extra cells are kept so
 * a malformed row shows as row.length > header.length. `headingPath` lists the enclosing
 * heading texts, outermost first, with quoted headings counted.
 * @param {string} text
 * @returns {{ header: string[], rows: string[][], line: number, rowLines: number[], headingPath: string[] }[]}
 */
export function parseTables(text) {
  const lines = splitLines(asText(text, 'parseTables'));
  const mask = hiddenMask(lines);
  const tables = [];
  const stack = [];
  let i = 0;
  while (i < lines.length) {
    if (mask[i]) {
      i++;
      continue;
    }
    const { depth, rest } = unquote(lines[i]);
    const heading = matchAtx(rest);
    if (heading) {
      while (stack.length && stack[stack.length - 1].level >= heading.level) stack.pop();
      stack.push(heading);
      i++;
      continue;
    }
    if (i + 1 < lines.length && !mask[i + 1] && rest.trim() !== '' && !CODE_INDENT_RE.test(rest) && hasCellSeparator(rest)) {
      const align = unquote(lines[i + 1]);
      if (align.depth === depth && !CODE_INDENT_RE.test(align.rest) &&
          align.rest.includes('|') && ALIGN_RE.test(align.rest)) {
        const header = splitCells(rest);
        if (splitCells(align.rest).length === header.length) {
          const rows = [];
          const rowLines = [];
          let j = i + 2;
          while (j < lines.length && !mask[j]) {
            const row = unquote(lines[j]);
            if (row.depth !== depth || row.rest.trim() === '' || matchAtx(row.rest) || !hasCellSeparator(row.rest)) {
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

/**
 * Rows of a parseTables result with more cells than the header, totals rows included, in
 * row order. GFM drops the cells past the header when it renders, so a surplus cell holds
 * content no reader sees and no check compares. Short rows are not listed: GFM pads them.
 * `line` is table.rowLines[index], relative to the text parseTables was given; callers add
 * their own section offset. `label` names the table at the start of `message`.
 * @param {{ header: string[], rows: string[][], rowLines: number[] }} table
 * @param {string} [label]
 * @returns {{ index: number, line: number, cells: number, message: string }[]}
 */
export function surplusCellRows(table, label = 'table') {
  const isStrings = (a) => Array.isArray(a) && [...a].every((c) => typeof c === 'string');
  if (table === null || typeof table !== 'object') {
    throw new TypeError('surplusCellRows: table must be a parseTables result');
  }
  const { header, rows, rowLines } = table;
  if (!isStrings(header)) throw new TypeError('surplusCellRows: table.header must be an array of strings');
  if (!Array.isArray(rows) || ![...rows].every(isStrings)) {
    throw new TypeError('surplusCellRows: table.rows must be an array of string arrays');
  }
  if (!Array.isArray(rowLines) || rowLines.length !== rows.length || ![...rowLines].every((n) => Number.isInteger(n) && n >= 1)) {
    throw new TypeError('surplusCellRows: table.rowLines must hold one positive integer line per row');
  }
  if (typeof label !== 'string' || label.trim() === '') {
    throw new TypeError('surplusCellRows: label must be a non-empty string');
  }
  const surplus = [];
  for (let index = 0; index < rows.length; index++) {
    const cells = rows[index].length;
    if (cells <= header.length) continue;
    surplus.push({
      index,
      line: rowLines[index],
      cells,
      message: `${label} row has ${cells} cells but its header has ${header.length}; a renderer drops the cells past the header`,
    });
  }
  return surplus;
}

const EXTERNAL_SCHEME_RE = /^(?:https?|mailto):/i;
// A setext underline; its indentation is measured against the paragraph it would close.
const SETEXT_UNDERLINE_RE = /^[ \t]*(?:=+|-+)[ \t]*$/;
// The list-item markers that open a line, nested ones on the same line included.
const LIST_PREFIX_RE = /^[ \t]*(?:(?:[-+*]|\d{1,9}[.)])(?:[ \t]+|$))+/;
// A list item that may interrupt a paragraph of its own container: not empty, and starting
// at 1 when ordered.
const LIST_INTERRUPT_RE = /^[ \t]*(?:[-+*]|0{0,8}1[.)])[ \t]+[^ \t]/;
// The starts of CommonMark HTML blocks of types 1–6, the kinds that can interrupt a paragraph.
const HTML_BLOCK_START_RE = new RegExp(
  '^[ \\t]*<(?:(?:script|pre|style|textarea)(?:[ \\t>]|$)|!--|\\?|![A-Za-z]|!\\[CDATA\\[|/?(?:' +
    'address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|' +
    'dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|' +
    'header|hr|html|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|' +
    'param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?:[ \\t>]|/>|$))',
  'i',
);
// A list-item marker opens a block at any indentation: a nested item sits deeper than three
// columns inside its parent's content, and container indentation is not tracked here.
const LIST_MARKER_RE = /^[ \t]*(?:[-+*]|\d{1,9}[.)])(?:[ \t]|$)/;
const THEMATIC_BREAK_RE = /^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$/;
const HOST_LABEL = '[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?';
const EMAIL_AUTOLINK_RE = new RegExp(`<([A-Za-z0-9.!#$%&'*+/=?^_\`{|}~-]+@${HOST_LABEL}(?:\\.${HOST_LABEL})*)>`, 'y');
const URI_AUTOLINK_RE = /<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^\x00-\x20\x7f<>]*)>/y;
const LINK_LABEL_RE = /\[((?:[^\\[\]]|\\[\s\S]){0,999})\]/y;
const LABEL_TEXT_RE = /^(?:[^\\[\]]|\\[\s\S]){0,999}$/;
// GitHub's extended autolink literals: www. and http(s):// at a line start or after
// whitespace, *, _, ~ or (, and bare email addresses.
const URL_LITERAL_RE = /(?<=^|[\s*_~(])(?:https?:\/\/|www\.)[^\s<]*/g;
const EMAIL_LITERAL_RE = /(?<![A-Za-z0-9._+-])[A-Za-z0-9._+-]+@[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+/g;
const DOMAIN_RE = /^[\p{L}\p{N}_-]+(?:\.[\p{L}\p{N}_-]+)*/u;
const MAX_EXCERPT = 80;
const NO_DEFINITIONS = new Map();

/** True when the character at `index` follows an odd run of backslashes, which escapes it. */
function isEscaped(s, index) {
  let n = 0;
  for (let k = index - 1; k >= 0 && s[k] === '\\'; k--) n++;
  return n % 2 === 1;
}

/** Link-label matching key: trimmed, inner whitespace runs collapsed to one space, case-folded. */
function labelKey(label) {
  return label.trim().replace(/[ \t\r\n]+/g, ' ').toUpperCase().toLowerCase();
}

/** One-line diagnostic excerpt: whitespace runs collapsed, at most MAX_EXCERPT characters. */
function excerpt(s) {
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > MAX_EXCERPT ? `${one.slice(0, MAX_EXCERPT - 1)}…` : one;
}

/** Skips optional spaces and tabs holding at most one line ending. */
function skipLinkSpace(s, k) {
  while (s[k] === ' ' || s[k] === '\t') k++;
  if (s[k] === '\n') k++;
  while (s[k] === ' ' || s[k] === '\t') k++;
  return k;
}

/** End, exclusive, of the link title `"…"`, `'…'` or `(…)` that opens at `k`, or -1. */
function titleEnd(s, k) {
  const close = s[k] === '(' ? ')' : s[k];
  for (let m = k + 1; m < s.length; m++) {
    if (s[m] === '\\' && m + 1 < s.length && ASCII_PUNCT_RE.test(s[m + 1])) m++;
    else if (s[m] === close) return m + 1;
    else if (close === ')' && s[m] === '(') return -1;
  }
  return -1;
}

/**
 * The link destination that opens at `k`, read on raw characters, for inline links and
 * reference definitions alike: `<…>` (no line ending, no unescaped `<`) or a run without
 * whitespace or control characters whose parentheses balance, which may be empty only
 * before a `)`; backticks in it are ordinary characters. Returns { start, stop, end }, the
 * destination being s.slice(start, stop) and `end` the offset after it, or null.
 */
function linkDestination(s, k) {
  if (s[k] === '<') {
    let m = k + 1;
    while (m < s.length && s[m] !== '>') {
      if (s[m] === '<' || s[m] === '\n') return null;
      m += s[m] === '\\' && m + 1 < s.length && s[m + 1] !== '\n' ? 2 : 1;
    }
    return m >= s.length ? null : { start: k + 1, stop: m, end: m + 1 };
  }
  const start = k;
  let parens = 0;
  for (; k < s.length; k++) {
    const c = s[k];
    if (c === '\\' && k + 1 < s.length && ASCII_PUNCT_RE.test(s[k + 1])) {
      k++;
    } else if (c === '(') {
      parens++;
    } else if (c === ')') {
      if (parens === 0) break;
      parens--;
    } else if (c <= ' ' || c === '\x7f') {
      break;
    }
  }
  if (parens !== 0 || (k === start && s[k] !== ')')) return null;
  return { start, stop: k, end: k };
}

/**
 * The inline-link tail that opens at `open`, the `(` right after a link text's `]`, read on
 * raw characters: optional whitespace, a linkDestination, an optional title set off from it
 * by whitespace, optional whitespace and `)`. Returns { destStart, destEnd, end }.
 */
function inlineTail(s, open) {
  // Destination first, as in CommonMark: `[a]( "t")` has the destination `"t"`, quotes
  // included, and only `<>` gives an empty destination that a title can follow.
  const dest = linkDestination(s, skipLinkSpace(s, open + 1));
  if (!dest) return null;
  let k = skipLinkSpace(s, dest.end);
  if (k > dest.end && (s[k] === '"' || s[k] === "'" || s[k] === '(')) {
    const end = titleEnd(s, k);
    if (end > 0) k = skipLinkSpace(s, end);
  }
  return s[k] === ')' ? { destStart: dest.start, destEnd: dest.stop, end: k + 1 } : null;
}

/**
 * The inline link whose text opens at `open`, a `[`: the text runs to the `]` that balances
 * it, a backslash escaping the character after it, and an inlineTail must follow that `]` at
 * once. Code spans are not skipped, so callers pass text whose code spans are already held
 * as placeholders. Returns { textEnd, destStart, destEnd, end }, `textEnd` being the `]`.
 */
function matchInlineLink(s, open) {
  let depth = 0;
  for (let j = open + 1; j < s.length; j++) {
    if (s[j] === '\\') {
      j++;
    } else if (s[j] === '[') {
      depth++;
    } else if (s[j] === ']') {
      if (depth > 0) {
        depth--;
        continue;
      }
      const tail = s[j + 1] === '(' ? inlineTail(s, j + 1) : null;
      return tail ? { textEnd: j, ...tail } : null;
    }
  }
  return null;
}


/** The CommonMark autolink `<scheme:…>` or `<user@host>` that opens at `i`, or null. */
function autolinkAt(s, i) {
  EMAIL_AUTOLINK_RE.lastIndex = i;
  const email = EMAIL_AUTOLINK_RE.exec(s);
  if (email) return { end: i + email[0].length, text: email[1], target: `mailto:${email[1]}` };
  URI_AUTOLINK_RE.lastIndex = i;
  const uri = URI_AUTOLINK_RE.exec(s);
  return uri ? { end: i + uri[0].length, text: uri[1], target: uri[1] } : null;
}

/**
 * Scans one block's inline text left to right, as CommonMark's inline parser does for
 * links. A backslash escapes the ASCII punctuation after it; a code span or an autolink is
 * taken whole, so nothing inside it is a bracket; `[` and `![` are pushed as openers, and
 * each `]` closes the nearest one as an inline link or, failing that, as a full, collapsed
 * or shortcut reference to `definitions`. A link deactivates the link openers before it, so
 * a link inside a link's text wins and the outer brackets stay text; images may hold links
 * and links may hold images. Offsets index `s`. Returns `links`
 * [{ start, end, text, target, image?, autolink?, inline? }], `problems` [{ start, message }]
 * and `covered`, the [start, end) ranges of code spans, autolinks, links and images.
 */
function scanInline(s, definitions) {
  const links = [];
  const problems = [];
  const covered = [];
  const openers = [];
  const sites = []; // every `](` outside code: { from, at, closed }
  const reportedLabels = new Set();

  const closeBracket = (i) => {
    const opener = openers.pop();
    const site = s[i + 1] === '(' ? { from: opener ? opener.start : i, at: i, closed: false } : null;
    if (site) sites.push(site);
    if (!opener || !opener.active) return i + 1;
    const text = s.slice(opener.textStart, i);
    const tail = site ? inlineTail(s, i + 1) : null;
    let link = null;
    if (tail) {
      site.closed = true;
      link = { end: tail.end, target: unescapeString(s.slice(tail.destStart, tail.destEnd)), inline: true };
    } else {
      LINK_LABEL_RE.lastIndex = i + 1;
      const label = LINK_LABEL_RE.exec(s);
      const full = label !== null && label[1] !== '';
      const name = full ? label[1] : text;
      const end = label ? i + 1 + label[0].length : i + 1;
      const key = labelKey(name);
      // A `[^…]` label is a footnote and a blank one names nothing: neither is a reference.
      if (key !== '' && !name.startsWith('^')) {
        const target = full || LABEL_TEXT_RE.test(name) ? definitions.get(key) : undefined;
        if (target !== undefined) {
          link = { end, target };
        } else if (label && !reportedLabels.has(opener.start)) {
          // Only the full and collapsed forms are reported: a shortcut without a definition
          // is ordinary bracketed text such as [Name] or a task-list [ ].
          const message = `undefined link reference: ${excerpt(s.slice(opener.start, end))}`;
          problems.push({ start: opener.start, message });
          if (full) reportedLabels.add(i + 1);
        }
      }
    }
    if (!link) return i + 1;
    links.push({
      start: opener.start,
      end: link.end,
      text,
      target: link.target,
      image: opener.image,
      inline: link.inline,
    });
    covered.push([opener.start, link.end]);
    if (!opener.image) for (const o of openers) if (!o.image) o.active = false;
    return link.end;
  };

  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === '\\' && i + 1 < s.length && ASCII_PUNCT_RE.test(s[i + 1])) {
      i += 2;
    } else if (c === '`') {
      const span = codeSpanAt(s, i);
      if (span) {
        covered.push([i, span.end]);
        i = span.end;
      } else {
        while (s[i] === '`') i++;
      }
    } else if (c === '<') {
      const auto = autolinkAt(s, i);
      if (auto) {
        links.push({ start: i, end: auto.end, text: auto.text, target: auto.target, autolink: true });
        covered.push([i, auto.end]);
        i = auto.end;
      } else {
        i++;
      }
    } else if (c === '[' || (c === '!' && s[i + 1] === '[')) {
      const image = c === '!';
      openers.push({ start: i, textStart: i + (image ? 2 : 1), image, active: true });
      i += image ? 2 : 1;
    } else if (c === ']') {
      i = closeBracket(i);
    } else {
      i++;
    }
  }

  // A `](` that closes no inline link or image is reported once, from its opener (else from
  // the `](`) to the end of the `](` line; a construct inside a reported excerpt is skipped.
  let reportedTo = -1;
  for (const site of sites.filter((x) => !x.closed).sort((a, b) => a.from - b.from)) {
    if (site.from < reportedTo) continue;
    const eol = s.indexOf('\n', site.at);
    reportedTo = eol < 0 ? s.length : eol;
    problems.push({ start: site.from, message: `malformed link: ${excerpt(s.slice(site.from, reportedTo))}` });
  }
  problems.sort((a, b) => a.start - b.start);
  links.sort((a, b) => a.start - b.start);
  return { links, problems, covered };
}

/** Removes the trailing characters GitHub leaves out of an autolink literal. */
function trimLiteral(text) {
  let t = text;
  for (;;) {
    const last = t.slice(-1);
    if (last !== '' && '?!.,:*_~'.includes(last)) t = t.slice(0, -1);
    else if (last === ')' && t.split(')').length > t.split('(').length) t = t.slice(0, -1);
    else if (last === ';' && /&[A-Za-z0-9]+;$/.test(t)) t = t.replace(/&[A-Za-z0-9]+;$/, '');
    else return t;
  }
}

/** True when `host` opens with a domain GitHub autolinks: no `_` in its last two labels. */
function validDomain(host, needPeriod) {
  const m = DOMAIN_RE.exec(host);
  if (!m) return false;
  const labels = m[0].split('.');
  return (!needPeriod || labels.length > 1) && !labels.slice(-2).some((label) => label.includes('_'));
}

/**
 * GitHub's extended autolink literals in `s` outside the `covered` ranges: `www.` (target
 * `http://…`), `http://` and `https://` links, and bare email addresses (target `mailto:…`).
 * A literal ends at whitespace, `<` or a covered range; trailing `?!.,:*_~`, an unbalanced
 * `)` and an entity-like `&name;` are left out of it.
 */
function literalAutolinks(s, covered) {
  const found = [];
  const stopAt = (start) => {
    let stop = s.length;
    for (const [from, to] of covered) {
      if (start >= from && start < to) return -1;
      if (from > start && from < stop) stop = from;
    }
    return stop;
  };
  for (const m of s.matchAll(URL_LITERAL_RE)) {
    const stop = stopAt(m.index);
    if (stop < 0) continue;
    const text = trimLiteral(m[0].slice(0, stop - m.index));
    const www = m[0].startsWith('www.');
    const hostStart = www ? 0 : m[0].indexOf('//') + 2;
    if (www ? !text.startsWith('www.') : text.length <= hostStart) continue;
    if (!validDomain(text.slice(hostStart), www)) continue;
    const target = www ? `http://${text}` : text;
    found.push({ start: m.index, end: m.index + text.length, text, target, autolink: true });
  }
  for (const m of s.matchAll(EMAIL_LITERAL_RE)) {
    const end = m.index + m[0].length;
    if (stopAt(m.index) < end || /[-_]$/.test(m[0])) continue;
    if (found.some((f) => m.index < f.end && end > f.start)) continue;
    found.push({ start: m.index, end, text: m[0], target: `mailto:${m[0]}`, autolink: true });
  }
  return found;
}

/** Width of `s` in columns, a tab advancing to the next multiple of four. */
function columns(s) {
  let col = 0;
  for (const c of s) col = c === '\t' ? col + 4 - (col % 4) : col + 1;
  return col;
}

/**
 * The reference definition that opens at `k` in a paragraph's raw content, as CommonMark
 * reads it: a link label holding non-whitespace that is not a `[^…]` footnote, then `:`; a
 * linkDestination after optional spaces and tabs holding at most one line ending; an
 * optional title set off from it by the same kind of whitespace, which may span lines; then
 * only spaces and tabs to the end of the line. A title followed by other text on its line is
 * dropped when the destination ends its own line, and leaves no definition otherwise.
 * Returns { key, target, end }, `end` being the offset after the definition's line ending
 * (the content's length when nothing follows), or null.
 */
function definitionAt(c, k) {
  LINK_LABEL_RE.lastIndex = k;
  const label = LINK_LABEL_RE.exec(c);
  const colon = label ? k + label[0].length : -1;
  if (!label || c[colon] !== ':' || label[1].startsWith('^') || labelKey(label[1]) === '') return null;
  const dest = linkDestination(c, skipLinkSpace(c, colon + 1));
  if (!dest) return null;
  const lineEnd = (j) => {
    while (c[j] === ' ' || c[j] === '\t') j++;
    if (j >= c.length) return c.length;
    return c[j] === '\n' ? j + 1 : -1;
  };
  let end = -1;
  const t = skipLinkSpace(c, dest.end);
  if (t > dest.end && (c[t] === '"' || c[t] === "'" || c[t] === '(')) {
    const close = titleEnd(c, t);
    if (close > 0) end = lineEnd(close);
  }
  if (end < 0) end = lineEnd(dest.end);
  if (end < 0) return null;
  return { key: labelKey(label[1]), target: unescapeString(c.slice(dest.start, dest.stop)), end };
}

/**
 * Takes the reference definitions that open a paragraph, as CommonMark does when the
 * paragraph closes: passes each to `define(key, target)` in order and returns how many of
 * the paragraph's lines they consume. Definitions are read on the paragraph's raw content,
 * each line without its leading spaces and tabs and the first also without the list
 * markers that open it. They follow one another from the first line; the first line that
 * completes none ends them, and it and every later line are paragraph text.
 */
function takeDefinitions(lines, para, define = () => {}) {
  const starts = [];
  let c = '';
  para.lines.forEach((i, n) => {
    const { rest } = unquote(lines[i]);
    if (n > 0) c += '\n';
    starts.push(c.length);
    c += (n === 0 ? rest.slice(para.prefix) : rest).replace(/^[ \t]+/, '');
  });
  let k = 0;
  let taken = 0;
  while (taken < starts.length && c[k] === '[') {
    const def = definitionAt(c, k);
    if (!def) break;
    define(def.key, def.target);
    k = def.end;
    while (taken < starts.length && starts[taken] < k) taken++;
  }
  return taken;
}

/**
 * The one pass behind parseLinks and linkDiagnostics, in two steps. Blocks come first, with
 * block structure approximated: fenced code and HTML comment blocks are skipped and inline
 * HTML comments blanked (blankInlineComments); table lines and ATX headings are
 * blocks of one line; a blank line or a thematic break ends a paragraph; a deeper
 * blockquote, the start of a CommonMark HTML block of types 1–6 or a list-item marker ends
 * it and opens a new one, an empty list item opening none; a setext underline at the
 * paragraph's own quote depth ends it as a heading, unless reference definitions are all
 * it holds, and is no text itself; any other line continues it, lazily when its quote is
 * shallower. A list item in the paragraph's own container, not on a lazy line, ends it only
 * when the item is not empty and, if ordered, starts at 1. In a paragraph that a list item
 * opens, a line indented less than the item's content is lazy too, and indentation counts
 * from that content. A closing paragraph gives up the reference definitions that open it
 * (takeDefinitions); each applies to the whole document, the first of a label winning, so a
 * use may come before its definition. Each block's remaining lines are then scanned
 * together. Returns links and problems in document order, each with its 1-based line.
 */
function scanDocument(text) {
  const lines = splitLines(text);
  const mask = hiddenMask(lines);
  // Inline HTML comments are blanked in place, so offsets and line lengths survive.
  const visible = blankInlineComments(lines, mask);
  const tableLines = new Set();
  for (const table of parseTables(text)) {
    tableLines.add(table.line);
    tableLines.add(table.line + 1);
    table.rowLines.forEach((line) => tableLines.add(line));
  }
  const blocks = [];
  const definitions = new Map();
  const define = (key, target) => {
    if (!definitions.has(key)) definitions.set(key, target);
  };
  let para = null;
  const closePara = () => {
    if (para) para.scan = para.lines.slice(takeDefinitions(visible, para, define));
    para = null;
  };
  for (let i = 0; i < lines.length; i++) {
    const { rest, depth } = unquote(lines[i]);
    if (mask[i] || rest.trim() === '' || THEMATIC_BREAK_RE.test(rest)) {
      closePara();
      continue;
    }
    if (tableLines.has(i + 1) || matchAtx(rest)) {
      closePara();
      blocks.push({ scan: [i] });
      continue;
    }
    if (para && depth <= para.depth) {
      const indent = columns(/^[ \t]*/.exec(rest)[0]);
      const inner = indent - para.contentIndent; // negative: lazy past the list item
      const own = depth === para.depth && inner >= 0; // inside the paragraph's own container
      const underline = own && inner <= 3 && SETEXT_UNDERLINE_RE.test(rest);
      if (underline && takeDefinitions(visible, para) < para.lines.length) {
        closePara();
        continue;
      }
      const html = (own ? inner : indent) <= 3 && HTML_BLOCK_START_RE.test(rest);
      const item = LIST_MARKER_RE.test(rest) && (!own || LIST_INTERRUPT_RE.test(rest));
      if (!html && !item) {
        para.lines.push(i);
        continue;
      }
    }
    closePara();
    const marker = LIST_PREFIX_RE.exec(rest)?.[0] ?? '';
    if (marker !== '' && marker.length === rest.length) continue; // an empty list item
    para = { depth, lines: [i], prefix: marker.length, contentIndent: columns(marker) };
    blocks.push(para);
  }
  closePara();

  const links = [];
  const problems = [];
  for (const { scan: block } of blocks) {
    if (block.length === 0) continue;
    const starts = [];
    let s = '';
    for (const i of block) {
      if (starts.length > 0) s += '\n';
      starts.push(s.length);
      s += unquote(visible[i]).rest;
    }
    const lineOf = (offset) => {
      let k = starts.length - 1;
      while (k > 0 && starts[k] > offset) k--;
      return block[k] + 1;
    };
    const scan = scanInline(s, definitions);
    const found = [...scan.links, ...literalAutolinks(s, scan.covered)].sort((a, b) => a.start - b.start);
    for (const link of found) links.push({ ...link, line: lineOf(link.start) });
    for (const problem of scan.problems) problems.push({ line: lineOf(problem.start), message: problem.message });
  }
  return { links, problems };
}

/**
 * Links of a Markdown document in document order, outside fenced code, HTML comments and
 * code spans:
 * inline links and images (`image: true`); uses of reference definitions in full
 * (`[text][label]`), collapsed (`[label][]`) and shortcut (`[label]`) form, carrying the
 * definition's target; CommonMark autolinks (`<scheme:…>`, and `<user@host>` as `mailto:`)
 * and GitHub's autolink literals (`www.`, `http://`, `https://`, bare email addresses), both
 * flagged `autolink: true`. Definitions are read where CommonMark reads them, at the start
 * of a paragraph (a list item's or a blockquote's included), and may span lines; each serves
 * the whole document, uses before it included, and a definition nobody uses is no link. A
 * paragraph's lines are read together, so a link may span lines, but not across a setext
 * underline or the start of an HTML block that ends the paragraph; `line` is the 1-based
 * line where it opens.
 * Labels match case-insensitively with whitespace collapsed, on their raw text. An inline or
 * definition destination becomes its target through unescapeString: backslash-escaped
 * punctuation and character references (`&amp;`, `&#35;`) give the characters they stand
 * for, and nothing is percent-decoded. Autolink targets are not decoded. Targets with an
 * http:, https: or mailto: scheme are left out unless
 * `external` is true; any other scheme is always returned so callers can flag it. Block
 * structure is approximated as scanDocument describes; this is not a full CommonMark parser.
 * @param {string} text
 * @param {{ external?: boolean }} [options]
 * @returns {{ text: string, target: string, line: number, image?: boolean, autolink?: boolean }[]}
 */
export function parseLinks(text, { external = false } = {}) {
  if (typeof external !== 'boolean') throw new TypeError('parseLinks: external must be a boolean');
  const out = [];
  for (const link of scanDocument(asText(text, 'parseLinks')).links) {
    if (!external && EXTERNAL_SCHEME_RE.test(link.target)) continue;
    const entry = { text: link.text, target: link.target, line: link.line };
    if (link.image) entry.image = true;
    if (link.autolink) entry.autolink = true;
    out.push(entry);
  }
  return out;
}

/**
 * Link syntax that renders as plain text, from the same scan as parseLinks, in document
 * order. `malformed link: <excerpt>` marks a `](` outside code and HTML comments that
 * closes no inline link or image, such as an unclosed destination, a destination holding
 * a space, or a link whose text holds another link; `undefined link reference: <excerpt>` marks a full or
 * collapsed reference whose label has no definition. A shortcut `[label]` without one is
 * ordinary text, as in `[Name]` or a task-list `[ ]`, and is never reported. `line` is the
 * line of the opening `[` (of the `](` when nothing opens it). An excerpt runs from there
 * to the end of the `](` line, at most 80 characters, and a construct inside an excerpt
 * already reported is not reported again.
 * @param {string} text
 * @returns {{ line: number, message: string }[]}
 */
export function linkDiagnostics(text) {
  return scanDocument(asText(text, 'linkDiagnostics')).problems;
}

/**
 * Resolves a link target against the root-relative file that holds it, as a browser
 * resolves a URL. The target, as parseLinks and linkInCell return it with backslash escapes
 * and character references already decoded, is first split at its first `#`, so a `#`
 * written `&#35;` delimits the fragment as it does in a browser; the path part
 * and the fragment are then percent-decoded independently, so an encoded `%23` stays a
 * filename character rather than becoming a fragment delimiter. A part whose decoding
 * fails on a malformed escape such as `%zz` is kept as written, without stopping the
 * other part from decoding. The anchor is null when the target has no literal `#` and
 * '' when the `#` ends it. An anchor-only target resolves to the file itself; a path
 * that decodes to a leading slash is returned as is so callers can flag it; a path that
 * escapes the root starts with `..`. Pure: no disk access and no guard.
 * @param {string} fromRel
 * @param {string} target
 * @returns {{ path: string, anchor: string | null }}
 */
export function resolveLink(fromRel, target) {
  const rawTarget = String(target ?? '');
  const decode = (part) => {
    try {
      return decodeURIComponent(part);
    } catch {
      return part; // a malformed escape such as "%zz" is kept as written
    }
  };
  const hash = rawTarget.indexOf('#');
  const linkPath = decode(hash < 0 ? rawTarget : rawTarget.slice(0, hash));
  const anchor = hash < 0 ? null : decode(rawTarget.slice(hash + 1));
  if (linkPath === '') return { path: fromRel, anchor };
  if (linkPath.startsWith('/')) return { path: linkPath, anchor };
  let resolved = path.posix.normalize(path.posix.join(path.posix.dirname(fromRel), linkPath));
  if (resolved.length > 1 && resolved.endsWith('/')) resolved = resolved.slice(0, -1);
  return { path: resolved, anchor };
}

/**
 * The first inline link or image of a table cell, outside code spans and HTML comments, or
 * null; an enclosing link comes before the image in its text. Reference links and autolinks
 * are not considered. The target is decoded as parseLinks decodes an inline destination.
 * No scheme is excluded; the caller decides what an external target means.
 * @param {string} cell
 * @returns {{ text: string, target: string } | null}
 */
export function linkInCell(cell) {
  const visible = blankInlineComments([String(cell ?? '')], [false])[0];
  const first = scanInline(visible, NO_DEFINITIONS).links.find((link) => link.inline);
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
  const mask = hiddenMask(lines);
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
 * columns maps its first cell to its second. Fenced code, HTML comment blocks, headings and
 * table lines are not read as line forms, and an entry whose key is empty is dropped.
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
 * then the final line `<check>: <N> violation(s)` that run-all.sh parses. Each
 * violation is exactly one physical line: every CR in path or message prints as the
 * two characters `\r` and every LF as `\n`; nothing else is escaped, and sorting uses
 * the raw values. Sets process.exitCode to 0 or 1 and returns N; never calls
 * process.exit.
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
  // Printed text only: CR and LF become the two characters \r and \n, so each
  // violation stays one physical line and the count line stays the last one.
  const oneLine = (s) => s.replace(/[\r\n]/g, (c) => (c === '\r' ? '\\r' : '\\n'));
  for (const v of list) {
    const p = oneLine(v.path);
    const m = oneLine(v.message);
    if (p === '') console.log(`${check}: ${m}`);
    else if (v.line === undefined) console.log(`${check}: ${p}: ${m}`);
    else console.log(`${check}: ${p}:${v.line}: ${m}`);
  }
  console.log(`${check}: ${list.length} violation(s)`);
  process.exitCode = list.length === 0 ? 0 : 1;
  return list.length;
}

/**
 * Absolute path of a locally installed tool binary (buf, spectral, mmdc) under
 * tests/validation/node_modules/.bin, or null when it is not installed. The path is
 * returned only when the entry, after following its link, is a regular file; a missing
 * entry, a dangling link, a link loop, a directory or any other non-file gives null.
 * There is no fallback to PATH. Callers run it with cwd repoRoot and report a missing
 * binary as their own violation.
 * @param {string} name a single file name, never '', '.', '..' or a path
 * @returns {string | null}
 * @throws {TypeError} when name is not a single file name
 */
export function toolBin(name) {
  const n = String(name ?? '');
  if (n === '' || n === '.' || n === '..' || n.includes('\0') || n !== path.basename(n)) {
    throw new TypeError(`toolBin: not a binary name: ${n}`);
  }
  const bin = path.join(repoRoot, 'tests', 'validation', 'node_modules', '.bin', n);
  let stat;
  try {
    stat = fs.statSync(bin);
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'ENOTDIR' || err.code === 'ELOOP') return null;
    throw err;
  }
  return stat.isFile() ? bin : null;
}

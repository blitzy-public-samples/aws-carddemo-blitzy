// SPDX-License-Identifier: Apache-2.0
/**
 * Forbidden vague-term scan: a case-insensitive whole-word search of every file under
 * docs/backlog/, which passes when it finds zero matches.
 *
 * The scan reads raw text. Code fences, inline code, links and blockquotes are not
 * stripped, so design sections and quoted source bodies are covered like any other
 * line. A word boundary is any character other than an ASCII letter or digit: the
 * standalone term and the term beside punctuation or a hyphen match, while a longer
 * word containing it (for example the negated or adverbial form) does not.
 *
 * Output: one `check-forbidden-terms: <path>:<line>: "<term>"` line per match
 * occurrence, then `check-forbidden-terms: <N> violation(s)`. Exit status is 0 when
 * N = 0, 1 when N > 0, and 2 on an internal error.
 */
import fs from 'node:fs';
import path from 'node:path';
import { listFiles, readText, repoRoot, report } from './lib/markdown.mjs';

const CHECK = 'check-forbidden-terms';
const BACKLOG_DIR = 'docs/backlog';

// The request's forbidden-term directive fixes this list of 13 terms. A failing scan is
// resolved by rewording the backlog artifact, never by adding, removing or loosening a term.
const FORBIDDEN_TERMS = Object.freeze([
  'approximately',
  'several',
  'various',
  'adequate',
  'appropriate',
  'properly',
  'correctly',
  'efficiently',
  'quickly',
  'easily',
  'user-friendly',
  'reasonable',
  'sufficient',
]);

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const TERM_PATTERNS = Object.freeze(
  FORBIDDEN_TERMS.map((term) => new RegExp(`(?<![A-Za-z0-9])${escapeRegExp(term)}(?![A-Za-z0-9])`, 'gi')),
);

/** True when the root-relative path is an existing directory. */
function isDirectory(rel) {
  try {
    return fs.statSync(path.join(repoRoot, rel)).isDirectory();
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'ENOTDIR') return false;
    throw err;
  }
}

/**
 * One violation per occurrence of a forbidden term in the file's text.
 * @param {string} rel root-relative POSIX path of the scanned file
 * @param {string} text the file's content
 * @returns {{ path: string, line: number, message: string }[]}
 */
function scanText(rel, text) {
  const found = [];
  text.split(/\r?\n/).forEach((lineText, index) => {
    for (const pattern of TERM_PATTERNS) {
      // matchAll iterates a copy of the global pattern, so no lastIndex carries over.
      for (const match of lineText.matchAll(pattern)) {
        found.push({ path: rel, line: index + 1, message: `"${match[0].toLowerCase()}"` });
      }
    }
  });
  return found;
}

function main() {
  if (!isDirectory(BACKLOG_DIR)) {
    return report(CHECK, [{ path: BACKLOG_DIR, message: 'directory missing' }]);
  }
  const violations = [];
  for (const rel of listFiles(BACKLOG_DIR)) {
    violations.push(...scanText(rel, readText(rel)));
  }
  return report(CHECK, violations);
}

try {
  main();
} catch (err) {
  console.error(err?.stack ?? String(err));
  process.exitCode = 2;
}

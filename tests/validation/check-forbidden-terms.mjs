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
 * Setup: when docs/backlog/ is not a directory, that is the only violation reported.
 * Otherwise the epics area docs/backlog/epics/ must be a directory holding at least one
 * file, so a clean result always means the backlog was scanned; a missing or empty epics
 * area is a `directory missing` or `no files to scan` violation at docs/backlog/epics,
 * reported together with the matches in every other backlog file.
 *
 * Usage: node tests/validation/check-forbidden-terms.mjs [-h|--help], from any working
 * directory (the repository root is taken from the script location). With no argument
 * the scan runs. -h or --help prints the usage on stdout and exits 0 without scanning.
 * Any other argument prints `check-forbidden-terms: unknown argument: <arg>` and the
 * usage on stderr and exits 2 without scanning.
 *
 * Output: one `check-forbidden-terms: <path>:<line>: "<term>"` line per match
 * occurrence, one `check-forbidden-terms: <path>: <message>` line per setup problem,
 * then `check-forbidden-terms: <N> violation(s)`. Exit status is 0 when N = 0, 1 when
 * N > 0, and 2 on an internal error or an invalid argument.
 */
import { escapeControls, formatError, listFiles, pathKind, readText, report } from './lib/markdown.mjs';

const CHECK = 'check-forbidden-terms';
const BACKLOG_DIR = 'docs/backlog';
const EPICS_DIR = 'docs/backlog/epics';

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

const USAGE = [
  'Usage: node tests/validation/check-forbidden-terms.mjs [-h|--help]',
  '',
  'Takes no arguments; -h or --help prints this text. Runs from any working directory:',
  'the repository root is taken from the script location.',
  '',
  `Scans every file under ${BACKLOG_DIR}/ (${EPICS_DIR}/ must exist and hold files) for the`,
  `${FORBIDDEN_TERMS.length} forbidden terms, case-insensitive, whole-word, in raw text:`,
  `  ${FORBIDDEN_TERMS.join(', ')}`,
  '',
  'Output:',
  `  ${CHECK}: <path>:<line>: "<term>"   one line per occurrence`,
  `  ${CHECK}: <path>: <message>        one line per setup problem`,
  `  ${CHECK}: <N> violation(s)         the final count`,
  '',
  'Exit: 0 no violations, 1 violations, 2 internal error or invalid argument.',
].join('\n');

/** True when the root-relative path is an existing directory. */
function isDirectory(rel) {
  return pathKind(rel) === 'dir';
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

/**
 * Reads the command line. No argument runs the scan; -h and --help, alone or together,
 * ask for the usage; any other argument is invalid, and the first such one is named.
 * @param {string[]} args the arguments after the script path
 * @returns {{ mode: 'scan' } | { mode: 'help' } | { mode: 'invalid', arg: string }}
 */
function parseArgs(args) {
  const unknown = args.find((arg) => arg !== '-h' && arg !== '--help');
  if (unknown !== undefined) return { mode: 'invalid', arg: unknown };
  return { mode: args.length === 0 ? 'scan' : 'help' };
}

function main() {
  if (!isDirectory(BACKLOG_DIR)) {
    return report(CHECK, [{ path: BACKLOG_DIR, message: 'directory missing' }]);
  }
  const files = listFiles(BACKLOG_DIR);
  const violations = [];
  // The epics area holds the backlog itself; without files there, a clean scan proves nothing.
  if (!isDirectory(EPICS_DIR)) {
    violations.push({ path: EPICS_DIR, message: 'directory missing' });
  } else if (!files.some((rel) => rel.startsWith(`${EPICS_DIR}/`))) {
    violations.push({ path: EPICS_DIR, message: 'no files to scan' });
  }
  for (const rel of files) {
    // One push per match: spreading a file's matches into one call overflows the
    // engine's argument limit on a match-dense file.
    for (const violation of scanText(rel, readText(rel))) violations.push(violation);
  }
  return report(CHECK, violations);
}

try {
  const cli = parseArgs(process.argv.slice(2));
  if (cli.mode === 'invalid') {
    console.error(`${CHECK}: unknown argument: ${escapeControls(cli.arg)}`);
    console.error(USAGE);
    process.exitCode = 2;
  } else if (cli.mode === 'help') {
    console.log(USAGE);
  } else {
    main();
  }
} catch (err) {
  console.error(`${CHECK}: internal error: ${formatError(err)}`);
  process.exitCode = 2;
}

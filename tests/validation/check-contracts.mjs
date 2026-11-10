// SPDX-License-Identifier: Apache-2.0
/**
 * check-contracts — buf, Spectral and contract-content check for the Card Servicing
 * interfaces. run-all.sh runs it fifth, from any working directory, as
 * `node tests/validation/check-contracts.mjs`. It takes no arguments; -h or --help
 * prints its usage and exits 0, and any other argument exits 2.
 *
 * Passes when every listed interface has its `.proto` and OpenAPI 3.1 document,
 * buf.yaml and .spectral.yaml pass validation (checkLintConfigs), no path this check
 * reads or a tool lints (buf.yaml, the two proto roots and everything inside them,
 * .spectral.yaml and the six OpenAPI documents) is or sits below a symbolic link,
 * `buf lint` and Spectral report zero errors, no proto carries a `buf:lint:ignore`
 * directive (a comment line that begins with it), the fourteen Stub Contract Baseline
 * operations are present under their baseline RPC names, REST methods and paths,
 * every RPC and OpenAPI operation carries examples and the standard error object, and
 * the ErrorObject schema is identical across the six OpenAPI documents and mirrors
 * cardservicing.common.v1.ErrorObject in camelCase.
 *
 * Nothing reached through a symbolic link is read by this check or linted by a tool,
 * because a link may lead outside the repository or into a CardDemo reference root.
 * Before any file is opened, every fixed path (common.proto, the six service protos,
 * the six OpenAPI documents, buf.yaml, .spectral.yaml and the two proto roots) is
 * checked with lstat from the repository root down, and the walk of each root never
 * follows a link inside it. Each link is reported once, at the link, and stands for
 * every path below it: such a path is not checked for existence, read, parsed or
 * linted. The tools run only after their configuration and inputs are validated: buf
 * only on a buf.yaml that passed, when neither buf.yaml nor a proto root has a
 * symlinked component and no link sits inside a root; Spectral only on a .spectral.yaml
 * that passed and has no symlinked component, and only for the documents that have
 * none, parse and hold local `#` references alone. A tool or document held back is one
 * more violation naming why, so neither tool reads a path the checks did not allow.
 *
 * A failing check is fixed in the contracts, never by weakening this script.
 * Output: one line per violation and a final count (lib/markdown.mjs report).
 * Informational lines read `check-contracts (note): <note>` and are not violations.
 * Exit 0 with no violation, 1 with any, 2 on an internal or setup error, such as no
 * resolvable YAML parser or @stoplight/spectral-rulesets (the count is still printed).
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { repoRoot, readText, exists, toolBin, report } from './lib/markdown.mjs';

const CHECK = 'check-contracts';

const PACKAGE_JSON = 'tests/validation/package.json';
const BUF_CONFIG = 'card-servicing-contracts/buf.yaml';
const SPECTRAL_RULESET = '.spectral.yaml';
const CONTRACTS_PROTO_ROOT = 'card-servicing-contracts/src/main/proto';
const SERVICE_PROTO_ROOT = 'card-servicing-service/src/main/proto';
const COMMON_IMPORT = 'cardservicing/common/v1/common.proto';
const COMMON_PROTO = `${CONTRACTS_PROTO_ROOT}/${COMMON_IMPORT}`;
const SERVICE_PROTOS = `${SERVICE_PROTO_ROOT}/cardservicing`;
const OPENAPI_DIR = 'card-servicing-service/src/main/resources/openapi';
const REFERENCE_OPENAPI = `${OPENAPI_DIR}/card-servicing-api.yaml`;
const ERROR_SCHEMA_REF = '#/components/schemas/ErrorObject';
const JSON_MEDIA = 'application/json';
const HTTP_METHODS = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'];
const RPC_COMMENT_MARKERS = ['Example request:', 'Example response:', 'Errors:'];

/**
 * The Stub Contract Baseline: each interface's proto, service, OpenAPI twin and its
 * operations. Extra RPCs and operations are allowed, because the baseline is only
 * ever extended additively; a missing or renamed one is a violation.
 */
const INTERFACES = [
  {
    proto: `${SERVICE_PROTOS}/api/v1/card_servicing_service.proto`,
    service: 'CardServicingService',
    openapi: `${OPENAPI_DIR}/card-servicing-api.yaml`,
    operations: [
      { rpc: 'GetBalanceSummary', method: 'get', path: '/v1/accounts/{accountId}/balance-summary' },
      { rpc: 'ListCategoryBalances', method: 'get', path: '/v1/accounts/{accountId}/category-balances' },
      { rpc: 'ListTransactions', method: 'get', path: '/v1/accounts/{accountId}/transactions' },
      { rpc: 'GetTransaction', method: 'get', path: '/v1/accounts/{accountId}/transactions/{transactionId}' },
    ],
  },
  {
    proto: `${SERVICE_PROTOS}/carddata/v1/card_data_service.proto`,
    service: 'CardDataService',
    openapi: `${OPENAPI_DIR}/card-data-service.yaml`,
    operations: [
      { rpc: 'GetAccount', method: 'get', path: '/v1/card-data/accounts/{accountId}' },
      { rpc: 'GetCardCrossReference', method: 'get', path: '/v1/card-data/accounts/{accountId}/card-cross-references' },
      { rpc: 'ListCategoryBalances', method: 'get', path: '/v1/card-data/accounts/{accountId}/category-balances' },
    ],
  },
  {
    proto: `${SERVICE_PROTOS}/transactionactivity/v1/transaction_activity_service.proto`,
    service: 'TransactionActivityService',
    openapi: `${OPENAPI_DIR}/transaction-activity-service.yaml`,
    operations: [
      { rpc: 'ListTransactions', method: 'get', path: '/v1/transaction-activity/accounts/{accountId}/transactions' },
      { rpc: 'GetTransaction', method: 'get', path: '/v1/transaction-activity/transactions/{transactionId}' },
    ],
  },
  {
    proto: `${SERVICE_PROTOS}/config/v1/config_service.proto`,
    service: 'ConfigService',
    openapi: `${OPENAPI_DIR}/config-service.yaml`,
    operations: [{ rpc: 'GetConfig', method: 'get', path: '/v1/config' }],
  },
  {
    proto: `${SERVICE_PROTOS}/referencedata/v1/reference_data_service.proto`,
    service: 'ReferenceDataService',
    openapi: `${OPENAPI_DIR}/reference-data-service.yaml`,
    operations: [
      { rpc: 'ListTransactionTypes', method: 'get', path: '/v1/reference-data/transaction-types' },
      { rpc: 'ListTransactionCategories', method: 'get', path: '/v1/reference-data/transaction-categories' },
    ],
  },
  {
    proto: `${SERVICE_PROTOS}/auth/v1/auth_service.proto`,
    service: 'AuthService',
    openapi: `${OPENAPI_DIR}/auth-service.yaml`,
    operations: [
      { rpc: 'ValidateToken', method: 'post', path: '/v1/auth/token-validations' },
      { rpc: 'AuthorizeAccountAccess', method: 'get', path: '/v1/auth/subjects/{subject}/accounts/{accountId}/access' },
    ],
  },
];

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const hasOwn = (o, k) => isObject(o) && Object.prototype.hasOwnProperty.call(o, k);
const toPosix = (p) => p.split(path.sep).join('/');

/** Root-relative POSIX form of a path a tool printed; null when it lies outside the repository. */
function repoRelative(printed) {
  const abs = path.resolve(repoRoot, printed);
  const rel = path.relative(repoRoot, abs);
  if (rel === '' || rel.startsWith('..') || path.isAbsolute(rel)) return null;
  return toPosix(rel);
}

// ---------------------------------------------------------------------------
// YAML parser
// ---------------------------------------------------------------------------

/** A YAML syntax error, duplicate keys included, with its 1-based line (undefined when unknown). */
class YamlSyntaxError extends Error {
  constructor(message, line) {
    super(message);
    this.name = 'YamlSyntaxError';
    this.line = line;
  }
}

/**
 * Resolves a strict YAML parser from the locked tool tree: @stoplight/yaml (Spectral's
 * own parser), else js-yaml (hoisted by the mermaid toolchain). The parser it returns
 * throws a YamlSyntaxError on any syntax error or duplicate key; @stoplight/yaml's plain
 * `parse` returns a partial result instead, which would let a malformed configuration
 * be judged by its fragments. Never add a YAML package to package.json for this: the
 * manifest pins exactly the three validation tools.
 */
function resolveYamlParser() {
  const require = createRequire(new URL('./package.json', import.meta.url));
  try {
    const { parseWithPointers } = require('@stoplight/yaml');
    if (typeof parseWithPointers === 'function') {
      return (text) => {
        const { data, diagnostics } = parseWithPointers(text);
        const error = (Array.isArray(diagnostics) ? diagnostics : []).find((d) => d?.severity === 0);
        if (error) {
          const line = error.range?.start?.line;
          throw new YamlSyntaxError(String(error.message ?? 'syntax error'), Number.isInteger(line) ? line + 1 : undefined);
        }
        return data;
      };
    }
  } catch {
    // Not resolvable from tests/validation; js-yaml is tried next.
  }
  try {
    const { load } = require('js-yaml');
    if (typeof load === 'function') {
      return (text) => {
        try {
          return load(text);
        } catch (err) {
          if (err?.name !== 'YAMLException') throw err;
          const line = err.mark?.line;
          throw new YamlSyntaxError(String(err.reason ?? err.message), Number.isInteger(line) ? line + 1 : undefined);
        }
      };
    }
  } catch {
    // Neither parser resolves; the caller reports it.
  }
  return null;
}

// ---------------------------------------------------------------------------
// Linters
// ---------------------------------------------------------------------------

const BUF_FINDING_RE = /^(\S[^:]*\.proto):(\d+)(?::\d+)?:\s*(.*)$/;

function runBuf(violations) {
  const bin = toolBin('buf');
  if (!bin) {
    violations.push({
      path: PACKAGE_JSON,
      message: 'buf is not installed in tests/validation/node_modules/.bin; run `npm ci` in tests/validation',
    });
    return;
  }
  // main runs buf only when no symlink sits on buf.yaml, on a module root or inside one;
  // --disable-symlinks still keeps buf off a link that appears inside a root after that
  // walk, whose target may sit outside the repository or under a CardDemo reference root.
  const res = spawnSync(bin, ['lint', '--config', BUF_CONFIG, '--disable-symlinks'], {
    cwd: repoRoot,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (res.error) {
    violations.push({ path: BUF_CONFIG, message: `buf lint could not run: ${res.error.message}` });
    return;
  }
  const lines = `${res.stdout ?? ''}\n${res.stderr ?? ''}`.split(/\r?\n/).filter((l) => l.trim() !== '');
  for (const line of lines) {
    const m = BUF_FINDING_RE.exec(line.trim());
    const file = m ? repoRelative(m[1]) : null;
    if (m && file) violations.push({ path: file, line: Number(m[2]), message: `buf: ${m[3]}` });
    else violations.push({ path: BUF_CONFIG, message: `buf: ${line.trim()}` });
  }
  if (res.status !== 0 && lines.length === 0) {
    violations.push({
      path: BUF_CONFIG,
      message: `buf lint exited with ${res.status ?? res.signal} and printed nothing`,
    });
  }
}

/**
 * The only text Spectral 6.15.0 prints after its JSON findings: the line it appends when
 * nothing reaches the failure threshold, as in `[]No results with a severity of 'error' found!`.
 */
const SPECTRAL_TRAILER_RE = /^No results with a severity of '(error|warn|info|hint)'( or higher)? found!$/;

/**
 * Decodes Spectral's `-f json` stdout into its top-level findings array.
 *
 * Read from the top, stdout holds three parts in order. First, blank lines and banner
 * lines: a banner line is plain text holding none of `[ ] { }`, such as Spectral 5's
 * `OpenAPI 3.x detected`, and the banner lines are returned trimmed as `banner` for the
 * caller to surface. Then the findings array, opened by `[` at column 0 of the first
 * line that holds a bracket; every bracket in it must match by type outside string
 * literals (balancedEnd), and exactly its span must parse as JSON. Last, only
 * whitespace or the SPECTRAL_TRAILER_RE line.
 *
 * A line before the array that carries a bracket, such as `{"findings":` or `[info]`,
 * is refused, never passed over as a banner: it cannot be told apart from malformed
 * protocol output. The first array is the only candidate. A malformed one is an error,
 * never a reason to look for a later array or to take an inner array for the findings.
 *
 * @param {string | null | undefined} text Spectral's stdout
 * @returns {{ results: unknown[], banner: string } | { error: string }} the findings
 *   and the trimmed banner (empty when none), or why no findings array was decoded
 */
function extractJsonArray(text) {
  const src = String(text ?? '');
  if (src.trim() === '') return { error: 'stdout is empty' };
  // The first line holding a bracket must open the array; every line before it holds
  // none, so it is blank or banner text.
  const lines = src.split('\n');
  const first = lines.findIndex((line) => /[[\]{}]/.test(line));
  if (first < 0) return { error: 'no line starts with a JSON array' };
  if (!lines[first].startsWith('[')) {
    return { error: `unexpected text before the JSON findings: ${clipSpectralText(lines[first].trim())}` };
  }
  const lineNo = first + 1;
  const start = lines.slice(0, first).reduce((offset, line) => offset + line.length + 1, 0);
  const end = balancedEnd(src, start);
  if (end === -2) return { error: `the array opened on line ${lineNo} has mismatched brackets` };
  if (end < 0) return { error: `the array opened on line ${lineNo} never closes` };
  let results;
  try {
    // A JSON text that opens with `[` and parses is an array.
    results = JSON.parse(src.slice(start, end + 1));
  } catch (err) {
    // V8 quotes the failing source, line breaks included, so whitespace is collapsed
    // to keep the violation on one line.
    const reason = String(err?.message ?? err).replace(/\s+/g, ' ');
    return { error: `the array opened on line ${lineNo} is not valid JSON (${clipSpectralText(reason)})` };
  }
  const rest = src.slice(end + 1).trim();
  if (rest !== '' && !SPECTRAL_TRAILER_RE.test(rest)) {
    return { error: `unexpected text follows the array opened on line ${lineNo}: ${firstLines(rest)}` };
  }
  return { results, banner: src.slice(0, start).trim() };
}

/**
 * Where the bracket at `start` closes, matching `[` with `]` and `{` with `}` outside
 * JSON string literals: the index of its closing bracket, -1 when it never closes, or
 * -2 at the first closing bracket that does not match the innermost open one, as in
 * `[oops}`.
 */
function balancedEnd(src, start) {
  // The closing bracket each open bracket needs, innermost last.
  const needed = [];
  let inString = false;
  for (let i = start; i < src.length; i++) {
    const ch = src[i];
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '[') needed.push(']');
    else if (ch === '{') needed.push('}');
    else if (ch === ']' || ch === '}') {
      if (needed.pop() !== ch) return -2;
      if (needed.length === 0) return i;
    }
  }
  return -1;
}

/** `text` cut to `max` characters, ending in `…` when it was cut, so one violation stays one readable line. */
function clipSpectralText(text, max = 200) {
  const s = String(text);
  return s.length <= max ? s : `${s.slice(0, max)}…`;
}

/**
 * The first five non-blank lines of `texts`, each trimmed and clipped to 200
 * characters, joined with ` | `; `(no output)` when every text is blank.
 */
function firstLines(...texts) {
  const lines = texts.join('\n').split(/\r?\n/).map((l) => l.trim()).filter((l) => l !== '');
  return lines.length === 0 ? '(no output)' : lines.slice(0, 5).map((l) => clipSpectralText(l)).join(' | ');
}

/**
 * Why a decoded Spectral result cannot be counted, or null when it is a finding: an
 * object with a string or numeric `code`, a string `message`, an integer `severity`
 * from 0 (error) to 3 (hint) and a non-negative integer `range.start.line`.
 */
function spectralFindingProblem(r) {
  if (!isObject(r)) return 'not an object';
  if (typeof r.code !== 'string' && typeof r.code !== 'number') return 'code is missing or not a string or number';
  if (typeof r.message !== 'string') return 'message is missing or not a string';
  if (!Number.isInteger(r.severity) || r.severity < 0 || r.severity > 3) {
    return 'severity is missing or not an integer from 0 to 3';
  }
  const line = r.range?.start?.line;
  if (!Number.isInteger(line) || line < 0) return 'range.start.line is missing or not a non-negative integer';
  return null;
}

/**
 * Node.js runtime warnings a tool may print on stderr, which say nothing about the
 * contracts: `(node:<pid>) [DEPnnnn] DeprecationWarning: …`, `(node:<pid>)
 * ExperimentalWarning: …`, and the "(Use node --trace-… to show where the warning was
 * created)" line that follows them.
 */
const NODE_RUNTIME_WARNING_RE = /^\(node:\d+\) (?:\[DEP\d+\] DeprecationWarning|ExperimentalWarning): \S/;
const NODE_TRACE_HINT_RE = /^\(Use `node --trace-[a-z-]+(?: [^`]*)?` to show where the warning was created\)$/;

/**
 * Lints each OpenAPI document in `files` with the local Spectral, against .spectral.yaml
 * at the error threshold, and judges stdout, stderr and the exit status separately.
 *
 * Violations, per document: Spectral could not start; stdout is not one decodable
 * findings array (extractJsonArray), whatever the exit status; one or more results are
 * not findings (spectralFindingProblem), reported once with the count and the first;
 * each error-severity finding, at its source line; a signal kill; a non-zero exit with
 * no error finding; stderr holding anything but Node.js runtime warnings, reported once
 * with its first unrecognised lines.
 *
 * Notes: text printed before the findings array; each distinct Node.js runtime warning,
 * once across all documents and without its process id; the warning, info and hint
 * counts across all documents.
 *
 * @param {string[]} files root-relative OpenAPI document paths
 * @param {{ path: string, line?: number, message: string }[]} violations appended to
 * @param {string[]} notes plain informational lines, appended to
 */
function runSpectral(files, violations, notes) {
  const bin = toolBin('spectral');
  if (!bin) {
    violations.push({
      path: PACKAGE_JSON,
      message: 'spectral is not installed in tests/validation/node_modules/.bin; run `npm ci` in tests/validation',
    });
    return;
  }
  // Findings below error severity, indexed by severity: 1 warning, 2 info, 3 hint.
  const belowError = [0, 0, 0, 0];
  const runtimeNotes = new Set();
  for (const rel of files) {
    const res = spawnSync(bin, ['lint', rel, '--ruleset', SPECTRAL_RULESET, '--fail-severity=error', '-f', 'json'], {
      cwd: repoRoot,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    });
    if (res.error) {
      violations.push({ path: rel, message: `spectral lint could not run: ${res.error.message}` });
      continue;
    }

    // Findings arrive on stdout only, so stderr is judged on its own, whatever stdout and the exit status say.
    const unrecognised = [];
    for (const raw of String(res.stderr ?? '').split(/\r?\n/)) {
      const line = raw.trim();
      if (line === '') continue;
      if (NODE_RUNTIME_WARNING_RE.test(line) || NODE_TRACE_HINT_RE.test(line)) {
        const note = `spectral printed a Node.js runtime warning on stderr: ${line.replace(/^\(node:\d+\) /, '')}`;
        if (!runtimeNotes.has(note)) {
          runtimeNotes.add(note);
          notes.push(note);
        }
      } else {
        unrecognised.push(line);
      }
    }
    if (unrecognised.length > 0) {
      violations.push({ path: rel, message: `spectral stderr: ${firstLines(...unrecognised)}` });
    }

    const exit = res.status === null ? `signal ${res.signal}` : `exit ${res.status}`;
    const decoded = extractJsonArray(res.stdout);
    if ('error' in decoded) {
      violations.push({
        path: rel,
        message: `spectral lint printed no valid JSON findings array (${exit}; ${decoded.error}): ${firstLines(res.stdout)}`,
      });
      continue;
    }
    if (decoded.banner !== '') {
      notes.push(`spectral printed text before the JSON findings of ${rel}: ${firstLines(decoded.banner)}`);
    }
    let errors = 0;
    let malformed = 0;
    let firstMalformed = '';
    for (const r of decoded.results) {
      const problem = spectralFindingProblem(r);
      if (problem !== null) {
        malformed++;
        if (malformed === 1) firstMalformed = `${problem}: ${clipSpectralText(JSON.stringify(r))}`;
        continue;
      }
      if (r.severity === 0) {
        errors++;
        const file = typeof r.source === 'string' ? repoRelative(r.source) ?? rel : rel;
        violations.push({ path: file, line: r.range.start.line + 1, message: `spectral ${r.code}: ${r.message}` });
      } else {
        belowError[r.severity]++;
      }
    }
    if (malformed > 0) {
      violations.push({
        path: rel,
        message: `spectral printed ${malformed} malformed finding(s) that cannot be counted; the first: ${firstMalformed}`,
      });
    }
    if (res.status === null) {
      violations.push({ path: rel, message: `spectral lint was killed by ${res.signal}` });
    } else if (res.status !== 0 && errors === 0) {
      violations.push({ path: rel, message: `spectral lint exited with ${res.status} without an error result` });
    }
  }
  const counted = [
    [belowError[1], 'warning(s)'],
    [belowError[2], 'info finding(s)'],
    [belowError[3], 'hint(s)'],
  ].filter(([n]) => n > 0);
  if (counted.length > 0) {
    const summary = counted.map(([n, label]) => `${n} ${label}`).join(', ');
    notes.push(`spectral reported ${summary} across the OpenAPI documents; findings below error severity are not violations`);
  }
}

// ---------------------------------------------------------------------------
// Lint configuration
// ---------------------------------------------------------------------------

/** The top-level keys buf.yaml may hold. */
const BUF_KEYS = ['version', 'modules', 'lint', 'breaking'];
/** Why a top-level buf.yaml key outside BUF_KEYS is refused, where a specific reason exists. */
const BUF_KEY_REASONS = {
  deps: 'it makes buf fetch remote modules',
  plugins: 'it makes buf run external programs',
};
/** Why a lint key other than use is refused, where a specific reason exists. */
const BUF_LINT_KEY_REASONS = {
  except: 'it removes rules from the categories in lint.use',
  ignore: 'it exempts the paths it lists from every rule',
  ignore_only: 'it exempts the paths it lists from the rules it names',
  disable_builtin: 'it turns off the built-in rules',
};
/** The top-level keys .spectral.yaml may hold. */
const SPECTRAL_KEYS = ['extends', 'rules'];
/** Why a top-level .spectral.yaml key outside SPECTRAL_KEYS is refused, where a specific reason exists. */
const SPECTRAL_KEY_REASONS = {
  functions: 'it loads custom rule functions from disk',
  functionsDir: 'it loads custom rule functions from disk',
  overrides: 'it changes the rules for selected documents',
  parserOptions: 'it changes how documents are parsed',
  aliases: 'it redefines what rules apply to',
  formats: 'it restricts which documents rules apply to',
};
const OAS_RULESET = 'spectral:oas';
/** The modes of an ["spectral:oas", <mode>] extends entry that keep its rules active. */
const OAS_MODES = ['recommended', 'all'];

/**
 * Every string `$ref` in a parsed YAML value that is not a local `#` reference, in
 * document order. Spectral loads such a reference itself (a file or a URL) before any
 * rule runs, so a document or ruleset holding one is never handed to it.
 */
function nonLocalRefs(value) {
  const out = [];
  const seen = new Set();
  const walk = (node) => {
    if (node === null || typeof node !== 'object' || seen.has(node)) return;
    seen.add(node);
    if (Array.isArray(node)) {
      for (const item of node) walk(item);
      return;
    }
    for (const [key, child] of Object.entries(node)) {
      if (key === '$ref' && typeof child === 'string' && !child.startsWith('#')) out.push(child);
      walk(child);
    }
  };
  walk(value);
  return out;
}

/** The first three references, quoted, followed by the count of any others. */
function listRefs(refs) {
  const shown = refs
    .slice(0, 3)
    .map((r) => JSON.stringify(r))
    .join(', ');
  return refs.length > 3 ? `${shown} and ${refs.length - 3} more` : shown;
}

/** A rules entry as one short phrase: a scalar as JSON, a mapping by its keys. */
function describeSetting(setting) {
  if (isObject(setting)) {
    const keys = Object.keys(setting);
    return keys.length === 0 ? 'an empty mapping' : `a mapping of ${keys.join(', ')}`;
  }
  return JSON.stringify(setting) ?? String(setting);
}

/**
 * Names of the spectral:oas rules whose default severity is error, read from the locked
 * @stoplight/spectral-rulesets that Spectral itself loads; null when that package does
 * not resolve or lists no such rule, which is a setup failure.
 */
function oasErrorRules() {
  const require = createRequire(new URL('./package.json', import.meta.url));
  let rules;
  try {
    rules = require('@stoplight/spectral-rulesets')?.oas?.rules;
  } catch {
    return null;
  }
  if (!isObject(rules)) return null;
  const names = Object.entries(rules)
    .filter(([, rule]) => rule?.severity === 0 || rule?.severity === 'error')
    .map(([name]) => name);
  return names.length > 0 ? new Set(names) : null;
}

/**
 * True when a rules entry keeps an error-severity rule at error severity: exactly
 * "error" or true. Spectral 6.15.0 rejects the ruleset for any other override that
 * names the rule without redefining it (a numeric severity such as 0, or a mapping
 * without given and then), and every remaining value downgrades or disables it.
 */
function keepsErrorSeverity(setting) {
  return setting === 'error' || setting === true;
}

/** Validates buf.yaml as checkLintConfigs sets out; true when buf may run on it. */
function checkBufConfig(parseYaml, violations) {
  const before = violations.length;
  const add = (message) => violations.push({ path: BUF_CONFIG, message });
  const buf = loadYaml(BUF_CONFIG, parseYaml, violations);
  if (buf === undefined) return false;
  if (!isObject(buf)) {
    add('configuration is not a YAML mapping');
    return false;
  }
  for (const key of Object.keys(buf)) {
    if (!BUF_KEYS.includes(key)) {
      add(`key "${key}" is not allowed: ${BUF_KEY_REASONS[key] ?? `buf.yaml may hold only ${BUF_KEYS.join(', ')}`}`);
    }
  }
  if (buf.version !== 'v2') {
    add(`version is ${buf.version === undefined ? 'missing' : JSON.stringify(buf.version)}, expected "v2"`);
  }

  const roots = [CONTRACTS_PROTO_ROOT, SERVICE_PROTO_ROOT];
  const paths = [];
  if (!Array.isArray(buf.modules)) {
    add('modules is not a list of path entries');
  } else {
    buf.modules.forEach((entry, i) => {
      const label = typeof entry?.path === 'string' ? `module ${entry.path}` : `module entry ${i + 1}`;
      if (!isObject(entry)) {
        add(`${label} is not a mapping`);
        return;
      }
      for (const key of Object.keys(entry)) {
        if (key !== 'path') {
          add(`${label}: key "${key}" is not allowed: a module entry holds only path, so nothing narrows what buf lints in it`);
        }
      }
      if (typeof entry.path === 'string') paths.push(entry.path);
      else add(`${label}: path is missing or not a string`);
    });
  }
  for (const root of roots) {
    if (!paths.includes(root)) add(`modules lack path ${root}`);
  }
  const seen = new Set();
  for (const p of paths) {
    if (seen.has(p)) add(`modules list path ${p} more than once`);
    else if (!roots.includes(p)) add(`modules include path ${p}, which buf would read; the modules must be exactly ${roots.join(' and ')}`);
    seen.add(p);
  }

  const lint = buf.lint;
  if (lint !== undefined && !isObject(lint)) add('lint is not a mapping');
  const settings = isObject(lint) ? lint : {};
  const use = settings.use;
  if (use !== undefined && !(Array.isArray(use) && use.every((name) => typeof name === 'string'))) {
    add('lint.use is not a list of rule and category names');
  }
  const used = Array.isArray(use) ? use : [];
  for (const category of ['STANDARD', 'COMMENTS']) {
    if (!used.includes(category)) add(`lint.use lacks ${category}`);
  }
  for (const [key, value] of Object.entries(settings)) {
    if (key === 'use' || (key === 'disallow_comment_ignores' && value === true)) continue;
    if (key === 'disallow_comment_ignores') {
      add(`lint.disallow_comment_ignores is ${JSON.stringify(value)}; only true is allowed`);
    } else {
      const reason =
        BUF_LINT_KEY_REASONS[key] ??
        'lint may hold only use and disallow_comment_ignores: true, because any other setting relaxes the STANDARD or COMMENTS rules';
      add(`lint.${key} is not allowed: ${reason}`);
    }
  }
  return violations.length === before;
}

/**
 * Validates .spectral.yaml as checkLintConfigs sets out. Returns { ready, setupFailure }:
 * whether Spectral may run on it, and whether @stoplight/spectral-rulesets failed to
 * resolve while rules needed checking against it.
 */
function checkSpectralRuleset(parseYaml, violations) {
  const before = violations.length;
  const add = (message) => violations.push({ path: SPECTRAL_RULESET, message });
  const ruleset = loadYaml(SPECTRAL_RULESET, parseYaml, violations);
  if (ruleset === undefined) return { ready: false, setupFailure: false };
  if (!isObject(ruleset)) {
    add('ruleset is not a YAML mapping');
    return { ready: false, setupFailure: false };
  }
  for (const key of Object.keys(ruleset)) {
    if (!SPECTRAL_KEYS.includes(key)) {
      add(`key "${key}" is not allowed: ${SPECTRAL_KEY_REASONS[key] ?? `the ruleset may hold only ${SPECTRAL_KEYS.join(' and ')}`}`);
    }
  }

  const ext = ruleset.extends;
  const entries = ext === undefined ? [] : Array.isArray(ext) ? ext : [ext];
  const namesOas = (entry) => entry === OAS_RULESET || (Array.isArray(entry) && entry[0] === OAS_RULESET);
  if (!entries.some(namesOas)) add(`extends does not include "${OAS_RULESET}"`);
  let oasEntries = 0;
  for (const entry of entries) {
    if (!namesOas(entry)) {
      add(`extends entry ${describeSetting(entry)} is not allowed: Spectral would load it before any check; extends holds only "${OAS_RULESET}"`);
    } else if (entry !== OAS_RULESET && !(entry.length === 2 && OAS_MODES.includes(entry[1]))) {
      add(`extends entry ${JSON.stringify(entry)} is not allowed: only "${OAS_RULESET}" or ["${OAS_RULESET}", "recommended" | "all"] keeps its rules active`);
    } else if (++oasEntries > 1) {
      add(`extends lists "${OAS_RULESET}" more than once`);
    }
  }

  for (const ref of nonLocalRefs(ruleset)) {
    add(`$ref ${JSON.stringify(ref)} is not allowed: Spectral would load it before any check; only local "#" references are`);
  }

  let setupFailure = false;
  if (hasOwn(ruleset, 'rules')) {
    const rules = ruleset.rules;
    if (!isObject(rules)) {
      add('rules is not a mapping of rule names to settings');
    } else if (Object.keys(rules).length > 0) {
      const errorRules = oasErrorRules();
      if (errorRules === null) {
        setupFailure = true;
        violations.push({
          path: PACKAGE_JSON,
          message: `@stoplight/spectral-rulesets does not resolve or lists no error-severity ${OAS_RULESET} rule, so the rules in ${SPECTRAL_RULESET} could not be checked`,
        });
      } else {
        for (const [name, setting] of Object.entries(rules)) {
          if (errorRules.has(name) && !keepsErrorSeverity(setting)) {
            add(
              `rules.${name} is ${describeSetting(setting)}: ${OAS_RULESET} reports this rule as an error, and an override may only keep it at error ("error" or true)`,
            );
          }
        }
      }
    }
  }
  return { ready: violations.length === before, setupFailure };
}

/**
 * Zero linter errors prove nothing when the configuration lints nothing, or when it
 * excepts, ignores or downgrades the rules that would have failed; and buf and Spectral
 * read whatever their configuration points them at. Both configurations are therefore
 * validated here, before either tool runs, and a tool runs only on a configuration that
 * passes:
 *
 * - buf.yaml is a mapping of only version, modules, lint and breaking (deps fetches
 *   remote modules, plugins runs external programs); version is v2; modules are exactly
 *   the two proto roots, each entry holding only path (no excludes or module-level lint)
 *   and none repeated; lint.use is a list of names including STANDARD and COMMENTS; and
 *   lint holds no other key (except, ignore, ignore_only, disable_builtin and the rule
 *   options each relax those categories) apart from disallow_comment_ignores: true.
 * - .spectral.yaml is a mapping of only extends and rules (functions and functionsDir
 *   load code from disk; overrides, parserOptions, aliases and formats change what the
 *   rules see); extends holds exactly one entry, spectral:oas, bare or as
 *   ["spectral:oas", "recommended" | "all"]; no $ref is non-local; and every rules entry
 *   naming a spectral:oas rule whose default severity is error keeps it at error.
 *
 * A configuration for which `linked(rel)` is true is reached through a symbolic link:
 * it is neither read nor validated, and its tool is not ready. The caller reports the
 * link and names it as the reason the tool was not run.
 *
 * Returns { bufReady, spectralReady, setupFailure }: whether each tool may run, and
 * whether @stoplight/spectral-rulesets failed to resolve (a setup error, exit 2).
 */
function checkLintConfigs(parseYaml, violations, linked) {
  const bufReady = !linked(BUF_CONFIG) && checkBufConfig(parseYaml, violations);
  const spectral = linked(SPECTRAL_RULESET)
    ? { ready: false, setupFailure: false }
    : checkSpectralRuleset(parseYaml, violations);
  return { bufReady, spectralReady: spectral.ready, setupFailure: spectral.setupFailure };
}

/**
 * Parses a root-relative YAML file; undefined (with a violation) when it is absent,
 * cannot be read (a directory, no permission and the like) or does not parse. Only a
 * YamlSyntaxError, duplicate keys included, is a parse failure, reported at the line
 * the parser names; any other error the parser throws is an internal error. Callers
 * never pass a path reached through a symbolic link: main reports the link instead.
 */
function loadYaml(rel, parseYaml, violations) {
  if (!exists(rel)) {
    violations.push({ path: rel, message: 'expected file is missing' });
    return undefined;
  }
  let text;
  try {
    text = readText(rel);
  } catch (err) {
    violations.push({ path: rel, message: `cannot be read: ${String(err?.message ?? err).split('\n')[0]}` });
    return undefined;
  }
  try {
    return parseYaml(text) ?? null;
  } catch (err) {
    if (!(err instanceof YamlSyntaxError)) throw err;
    violations.push({
      path: rel,
      line: Number.isInteger(err.line) ? err.line : undefined,
      message: `YAML does not parse: ${String(err.message).split('\n')[0]}`,
    });
    return undefined;
  }
}

// ---------------------------------------------------------------------------
// Proto parsing (a light parser: enough structure for the content checks; buf
// owns syntax and semantics)
// ---------------------------------------------------------------------------

/**
 * Splits proto source into per-line code, with comments blanked out, and per-line
 * `//` comment text (null when the line has none). String literals are kept, and a
 * comment marker inside one is not a comment.
 */
function scanProto(text) {
  const code = [];
  const comments = [];
  let inBlock = false;
  for (const line of text.split(/\r?\n/)) {
    let out = '';
    let comment = null;
    let quote = null;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (inBlock) {
        if (ch === '*' && line[i + 1] === '/') {
          inBlock = false;
          out += '  ';
          i++;
        } else {
          out += ' ';
        }
      } else if (quote) {
        out += ch;
        if (ch === '\\' && i + 1 < line.length) out += line[++i];
        else if (ch === quote) quote = null;
      } else if (ch === '"' || ch === "'") {
        quote = ch;
        out += ch;
      } else if (ch === '/' && line[i + 1] === '/') {
        comment = line.slice(i + 2);
        break;
      } else if (ch === '/' && line[i + 1] === '*') {
        inBlock = true;
        out += '  ';
        i++;
      } else {
        out += ch;
      }
    }
    code.push(out);
    comments.push(comment);
  }
  return { code, comments };
}

/** End index (exclusive) of the string literal opening at `start`; proto strings never span lines. */
function stringEnd(src, start) {
  const quote = src[start];
  for (let i = start + 1; i < src.length; i++) {
    if (src[i] === '\\') i++;
    else if (src[i] === quote || src[i] === '\n') return i + 1;
  }
  return src.length;
}

/** Index of the brace closing the one at `open`, skipping string literals; src.length when unclosed. */
function closingBrace(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const ch = src[i];
    if (ch === '"' || ch === "'") {
      i = stringEnd(src, i) - 1;
    } else if (ch === '{') {
      depth++;
    } else if (ch === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return src.length;
}

/**
 * A message body's own statements: nested message, enum and extend blocks and option
 * aggregates are dropped, while oneof bodies are kept, because oneof members are
 * fields of the enclosing message.
 */
function topLevelStatements(body) {
  let out = '';
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (ch === '"' || ch === "'") {
      const end = stringEnd(body, i);
      out += body.slice(i, end);
      i = end - 1;
    } else if (ch === '{') {
      const close = closingBrace(body, i);
      const inner = body.slice(i + 1, close);
      const oneof = /\boneof\s+[A-Za-z_]\w*\s*$/.exec(out);
      if (oneof) out = `${out.slice(0, oneof.index)};${topLevelStatements(inner)};`;
      else out = `${out.replace(/\b(?:message|enum|extend)\s+[\w.]+\s*$/, '')};`;
      i = close;
    } else {
      out += ch;
    }
  }
  return out;
}

const TYPE_NAME = String.raw`\.?[A-Za-z_][\w.]*`;
const MAP_TYPE = String.raw`map\s*<\s*[\w.]+\s*,\s*[\w.]+\s*>`;
/** One field statement: label, type (map types included), name, number and options. */
const FIELD_RE = new RegExp(
  String.raw`^(?:(?:repeated|optional|required)\s+)?(${MAP_TYPE}|${TYPE_NAME})\s+([A-Za-z_]\w*)\s*=\s*(\d+)\s*(\[[\s\S]*\])?$`,
);
/** One RPC declaration, ended by `;` or an options body. */
const RPC_RE = new RegExp(
  String.raw`\brpc\s+([A-Za-z_]\w*)\s*\(\s*(?:stream\s+)?${TYPE_NAME}\s*\)\s*returns\s*\(\s*(?:stream\s+)?${TYPE_NAME}\s*\)\s*[;{]`,
  'g',
);

/** proto3 JSON name of a field: underscores dropped and the following character upper-cased. */
function jsonName(fieldName) {
  let out = '';
  let upper = false;
  for (const ch of fieldName) {
    if (ch === '_') upper = true;
    else {
      out += upper ? ch.toUpperCase() : ch;
      upper = false;
    }
  }
  return out;
}

/**
 * Imports, services with their RPCs (name and 1-based line), and messages with their
 * fields (name and JSON name, json_name option honoured) of one proto file.
 */
function parseProto(text) {
  const { code, comments } = scanProto(text);
  const src = code.join('\n');
  const lineStarts = [0];
  for (let i = 0; i < src.length; i++) if (src[i] === '\n') lineStarts.push(i + 1);
  const lineAt = (offset) => {
    let lo = 0;
    let hi = lineStarts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (lineStarts[mid] <= offset) lo = mid;
      else hi = mid - 1;
    }
    return lo + 1;
  };

  const imports = [];
  for (const m of src.matchAll(/\bimport\s+(?:(?:public|weak)\s+)?"([^"]*)"\s*;/g)) imports.push(m[1]);

  const services = new Map();
  for (const m of src.matchAll(/\bservice\s+([A-Za-z_]\w*)\s*\{/g)) {
    const open = m.index + m[0].length - 1;
    const body = src.slice(open, closingBrace(src, open));
    const rpcs = [];
    for (const r of body.matchAll(RPC_RE)) {
      const line = lineAt(open + r.index);
      rpcs.push({ name: r[1], line, comment: leadingComment(code, comments, line - 1) });
    }
    if (!services.has(m[1])) services.set(m[1], { line: lineAt(m.index), rpcs });
  }

  const messages = new Map();
  for (const m of src.matchAll(/\bmessage\s+([A-Za-z_]\w*)\s*\{/g)) {
    if (messages.has(m[1])) continue;
    const open = m.index + m[0].length - 1;
    const fields = [];
    for (const statement of topLevelStatements(src.slice(open + 1, closingBrace(src, open))).split(';')) {
      const f = FIELD_RE.exec(statement.trim());
      if (!f || f[1] === 'option') continue;
      const explicit = f[4] ? /\bjson_name\s*=\s*"([^"]*)"/.exec(f[4]) : null;
      fields.push({ name: f[2], json: explicit ? explicit[1] : jsonName(f[2]) });
    }
    messages.set(m[1], { line: lineAt(m.index), fields });
  }
  return { imports, services, messages };
}

/**
 * The contiguous `//` comment block directly above a 0-based line: comment-only lines,
 * stopping at the first blank or code line.
 */
function leadingComment(code, comments, index) {
  const block = [];
  for (let i = index - 1; i >= 0; i--) {
    if (comments[i] === null || code[i].trim() !== '') break;
    block.unshift(comments[i]);
  }
  return block.join('\n');
}

// ---------------------------------------------------------------------------
// Proto content
// ---------------------------------------------------------------------------

/**
 * Checks the common import, every RPC's comment blocks and the baseline RPCs of each
 * service proto, and returns the camelCase field names of the shared ErrorObject and
 * FieldViolation messages (null for a message that cannot be read).
 *
 * A proto for which `linked(rel)` is true, common.proto included, is reached through a
 * symbolic link and is skipped unread, as a missing one is: main reports the link. A
 * skipped common.proto leaves both shared field lists null.
 */
function checkProtos(violations, linked) {
  for (const iface of INTERFACES) {
    if (linked(iface.proto) || !exists(iface.proto)) continue;
    const proto = parseProto(readText(iface.proto));
    if (!proto.imports.includes(COMMON_IMPORT)) {
      violations.push({ path: iface.proto, message: `missing import "${COMMON_IMPORT}"` });
    }
    for (const service of proto.services.values()) {
      for (const rpc of service.rpcs) {
        for (const marker of RPC_COMMENT_MARKERS) {
          if (!rpc.comment.includes(marker)) {
            violations.push({
              path: iface.proto,
              line: rpc.line,
              message: `rpc ${rpc.name}: leading comment lacks "${marker}"`,
            });
          }
        }
      }
    }
    const service = proto.services.get(iface.service);
    if (!service) {
      violations.push({ path: iface.proto, message: `missing baseline service ${iface.service}` });
      continue;
    }
    const names = new Set(service.rpcs.map((r) => r.name));
    for (const op of iface.operations) {
      if (!names.has(op.rpc)) {
        violations.push({
          path: iface.proto,
          line: service.line,
          message: `service ${iface.service} lacks baseline rpc ${op.rpc}`,
        });
      }
    }
  }

  const shared = { errorObject: null, fieldViolation: null };
  if (linked(COMMON_PROTO) || !exists(COMMON_PROTO)) return shared;
  const common = parseProto(readText(COMMON_PROTO));
  for (const [key, name] of [
    ['errorObject', 'ErrorObject'],
    ['fieldViolation', 'FieldViolation'],
  ]) {
    const message = common.messages.get(name);
    if (!message || message.fields.length === 0) {
      violations.push({ path: COMMON_PROTO, message: `missing message ${name} with fields` });
    } else {
      shared[key] = message.fields.map((f) => f.json);
    }
  }
  return shared;
}

/** A symbolic link on, or at the end of, a path this check reads or buf or Spectral would lint. */
const LINK_MESSAGE =
  'is a symbolic link: nothing reached through it is read by this check or linted by buf or Spectral, because its target may sit outside the repository or under a CardDemo reference root; the delivery set must hold the real file or directory, so replace the link with its target';
/** A symbolic link the walk found inside a module root. */
const SYMLINK_MESSAGE =
  'is a symbolic link inside a module root: buf lint is not run while one is present, because a proto behind it would drop out of the lint unseen; the delivery set must hold the real file or directory, so replace the link with its target';

/**
 * The first component of a root-relative path that is a symbolic link, checked with
 * lstat from the repository root down to the path itself, as a root-relative path;
 * null when no component is a link or the path is missing (a component that does not
 * exist, or is not a directory, ends the walk). No link is followed, so its target is
 * never statted.
 */
function symlinkedComponent(rel) {
  const segments = rel.split('/');
  for (let i = 1; i <= segments.length; i++) {
    const part = segments.slice(0, i).join('/');
    let stat;
    try {
      stat = fs.lstatSync(path.join(repoRoot, part));
    } catch (err) {
      if (err.code === 'ENOENT' || err.code === 'ENOTDIR') return null;
      throw err;
    }
    if (stat.isSymbolicLink()) return part;
  }
  return null;
}

/**
 * Reports each symbolic link met on the check's or a tool's inputs once, at the link
 * itself, however many inputs sit below it. onPath(rel) returns symlinkedComponent(rel)
 * and reports that component the first time; found(rel) reports a link the module-root
 * walk met.
 */
function linkTracker(violations) {
  const reported = new Set();
  const add = (rel, message) => {
    if (reported.has(rel)) return;
    reported.add(rel);
    violations.push({ path: rel, message });
  };
  return {
    onPath(rel) {
      const link = symlinkedComponent(rel);
      if (link !== null) add(link, LINK_MESSAGE);
      return link;
    },
    found(rel) {
      add(rel, SYMLINK_MESSAGE);
    },
  };
}

/**
 * Why a tool input is withheld from its tool: `link`, a component of its path (the
 * input itself included), is a symbolic link. `subject` names the input in the
 * sentence (default: its path).
 */
function linkReason(rel, link, subject = rel) {
  return `${subject} is reached through symbolic link ${link}`;
}

/**
 * Walks the two module roots without following any symbolic link, for two rules:
 *
 * - buf honours a `buf:lint:ignore <RULE>` directive in an element's leading comment
 *   unless buf.yaml sets disallow_comment_ignores, so one such line silences a STANDARD
 *   or COMMENTS rule for that element. A directive is a line that, after leading
 *   whitespace and an optional `//`, `/*` or block-continuation `*` marker and
 *   whitespace, begins with `buf:lint:ignore`, extra words after it included. Every
 *   directive line, in every regular .proto file under the roots, is a violation. A
 *   mention after other text on its line, prose before the token or code before a
 *   trailing comment, is not a directive: buf does not honour it, so it is not flagged.
 * - buf follows a symbolic link at or above a module root to wherever it points, and
 *   with --disable-symlinks it skips one inside a root, so a proto behind it would drop
 *   out of the lint unseen. A link at or above a root is reported once (links.onPath)
 *   and the root is not walked; every link inside a root, file or directory, is a
 *   violation, and its target is never statted, read or descended. A missing root is
 *   skipped without a violation: the expected-file checks and buf report it.
 *
 * Returns, in root order, why buf must not run: each root reached through a link, and
 * each root holding a link. Output is in path order within each root.
 */
function checkLintIgnoreComments(violations, links) {
  const bufBlocked = [];
  for (const root of [CONTRACTS_PROTO_ROOT, SERVICE_PROTO_ROOT]) {
    const link = links.onPath(root);
    if (link !== null) {
      bufBlocked.push(linkReason(root, link));
      continue;
    }
    let isDir;
    try {
      isDir = fs.lstatSync(path.join(repoRoot, root)).isDirectory();
    } catch (err) {
      if (err.code === 'ENOENT' || err.code === 'ENOTDIR') continue;
      throw err;
    }
    if (!isDir) continue;
    const found = [];
    const walk = (rel) => {
      for (const entry of fs.readdirSync(path.join(repoRoot, rel), { withFileTypes: true })) {
        const child = `${rel}/${entry.name}`;
        if (entry.isSymbolicLink()) found.push({ rel: child, link: true });
        else if (entry.isDirectory()) walk(child);
        else if (entry.isFile() && path.extname(entry.name) === '.proto') found.push({ rel: child, link: false });
      }
    };
    walk(root);
    found.sort((a, b) => (a.rel < b.rel ? -1 : a.rel > b.rel ? 1 : 0));
    const inRoot = found.filter((f) => f.link).length;
    if (inRoot > 0) bufBlocked.push(`module root ${root} holds ${inRoot} symbolic link(s)`);
    for (const { rel, link } of found) {
      if (link) {
        links.found(rel);
        continue;
      }
      readText(rel)
        .split(/\r?\n/)
        .forEach((text, i) => {
          if (/^\s*(?:\/\/+|\/\*+|\*+)?\s*buf:lint:ignore/.test(text)) {
            violations.push({
              path: rel,
              line: i + 1,
              message: 'buf:lint:ignore comment silences a buf lint rule; remove it so STANDARD and COMMENTS apply here',
            });
          }
        });
    }
  }
  return bufBlocked;
}

// ---------------------------------------------------------------------------
// OpenAPI content
// ---------------------------------------------------------------------------

/**
 * Resolves a local `#/…` JSON-pointer reference against its own document. Returns
 * { value } or { error }; any other reference form is unresolvable here, because each
 * OpenAPI document must stand alone.
 */
function resolvePointer(doc, ref) {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) {
    return { error: `$ref "${ref}" is not a local "#/" reference` };
  }
  let node = doc;
  for (const raw of ref.slice(2).split('/')) {
    let token;
    try {
      token = decodeURIComponent(raw).replace(/~1/g, '/').replace(/~0/g, '~');
    } catch {
      return { error: `$ref "${ref}" is not a valid JSON pointer` };
    }
    if (Array.isArray(node) && /^\d+$/.test(token) && Number(token) < node.length) node = node[Number(token)];
    else if (hasOwn(node, token)) node = node[token];
    else return { error: `$ref "${ref}" does not resolve` };
  }
  return { value: node };
}

/** Follows a chain of local references; undefined (with one violation) when it breaks or loops. */
function deref(doc, node, rel, where, violations) {
  const seen = new Set();
  let current = node;
  while (isObject(current) && typeof current.$ref === 'string') {
    if (seen.has(current.$ref)) {
      violations.push({ path: rel, message: `${where}: $ref "${current.$ref}" is circular` });
      return undefined;
    }
    seen.add(current.$ref);
    const { value, error } = resolvePointer(doc, current.$ref);
    if (error) {
      violations.push({ path: rel, message: `${where}: ${error}` });
      return undefined;
    }
    current = value;
  }
  return current;
}

const hasMediaExample = (media) =>
  hasOwn(media, 'example') || (isObject(media?.examples) && Object.keys(media.examples).length > 0);

const hasSchemaExample = (schema) =>
  hasOwn(schema, 'example') ||
  (Array.isArray(schema?.examples) && schema.examples.length > 0) ||
  (isObject(schema?.examples) && Object.keys(schema.examples).length > 0);

/** Every operation of a document as { method, path, op }, Path Item references resolved. */
function operationsOf(doc, rel, violations) {
  const out = [];
  for (const [p, rawItem] of Object.entries(isObject(doc.paths) ? doc.paths : {})) {
    const item = deref(doc, rawItem, rel, `path ${p}`, violations);
    if (!isObject(item)) continue;
    for (const method of HTTP_METHODS) {
      if (isObject(item[method])) out.push({ method, path: p, op: item[method] });
    }
  }
  return out;
}

function checkOperation(doc, rel, { method, path: p, op }, violations) {
  const id = typeof op.operationId === 'string' ? ` (${op.operationId})` : '';
  const where = `${method.toUpperCase()} ${p}${id}`;
  if (typeof op.description !== 'string' || op.description.trim() === '') {
    violations.push({ path: rel, message: `${where}: description is missing or empty` });
  }
  const responses = isObject(op.responses) ? Object.entries(op.responses) : [];
  let success = 0;
  let successWithExample = 0;
  let errors = 0;
  for (const [status, raw] of responses) {
    const isSuccess = /^2(?:\d\d|XX)$/i.test(status);
    const isError = /^[45](?:\d\d|XX)$/i.test(status);
    if (!isSuccess && !isError) continue;
    const response = deref(doc, raw, rel, `${where} response ${status}`, violations);
    const media = isObject(response?.content) ? response.content[JSON_MEDIA] : undefined;
    if (isSuccess) {
      success++;
      if (!isObject(media)) continue;
      const schema =
        media.schema === undefined
          ? undefined
          : deref(doc, media.schema, rel, `${where} response ${status} schema`, violations);
      if (hasMediaExample(media) || hasSchemaExample(schema)) successWithExample++;
      continue;
    }
    errors++;
    if (response === undefined) continue;
    if (!isObject(media)) {
      violations.push({ path: rel, message: `${where}: response ${status} has no ${JSON_MEDIA} content` });
      continue;
    }
    if (media.schema?.$ref !== ERROR_SCHEMA_REF) {
      violations.push({ path: rel, message: `${where}: response ${status} schema is not $ref ${ERROR_SCHEMA_REF}` });
    } else {
      deref(doc, media.schema, rel, `${where} response ${status} schema`, violations);
    }
    if (!hasMediaExample(media)) {
      violations.push({ path: rel, message: `${where}: response ${status} has no ${JSON_MEDIA} example` });
    }
  }
  if (success === 0) {
    violations.push({ path: rel, message: `${where}: no 2xx response` });
  } else if (successWithExample === 0) {
    violations.push({ path: rel, message: `${where}: no 2xx ${JSON_MEDIA} response carries an example` });
  }
  if (errors === 0) violations.push({ path: rel, message: `${where}: no 4xx or 5xx response` });
}

function checkBaselineOperations(doc, iface, violations) {
  for (const op of iface.operations) {
    const where = `${op.method.toUpperCase()} ${op.path}`;
    // A broken Path Item reference is reported once, by operationsOf, so it is not collected here.
    const item = hasOwn(doc.paths, op.path) ? deref(doc, doc.paths[op.path], iface.openapi, where, []) : undefined;
    const operation = isObject(item) ? item[op.method] : undefined;
    if (!isObject(operation)) {
      violations.push({ path: iface.openapi, message: `missing baseline operation ${where} (${op.rpc})` });
    } else if (operation.operationId !== op.rpc) {
      violations.push({
        path: iface.openapi,
        message: `${where}: operationId is "${operation.operationId ?? ''}", expected "${op.rpc}"`,
      });
    }
  }
}

// ---------------------------------------------------------------------------
// ErrorObject identity
// ---------------------------------------------------------------------------

/** JSON text with object keys sorted at every depth, so equal schemas compare equal. */
function canonicalJson(value) {
  const sort = (v) => {
    if (Array.isArray(v)) return v.map(sort);
    if (!isObject(v)) return v;
    const out = {};
    for (const key of Object.keys(v).sort()) {
      Object.defineProperty(out, key, { value: sort(v[key]), enumerable: true, writable: true, configurable: true });
    }
    return out;
  };
  return JSON.stringify(sort(value));
}

function compareNames(rel, label, actual, expected, violations) {
  const missing = expected.filter((n) => !actual.includes(n));
  const extra = actual.filter((n) => !expected.includes(n));
  if (missing.length > 0 || extra.length > 0) {
    const parts = [];
    if (missing.length > 0) parts.push(`missing ${missing.join(', ')}`);
    if (extra.length > 0) parts.push(`unexpected ${extra.join(', ')}`);
    violations.push({
      path: rel,
      message: `${label} properties must be ${expected.join(', ')} (camelCase of common.proto): ${parts.join('; ')}`,
    });
  }
}

/**
 * Requires components.schemas.ErrorObject in every document, identical (canonical JSON)
 * to the one in card-servicing-api.yaml, with property names that mirror common.proto.
 * A `$ref`'d details item schema is compared across documents as well.
 */
function checkErrorObjects(docs, shared, violations) {
  const entries = [];
  for (const [rel, doc] of docs) {
    const schema = isObject(doc.components?.schemas) ? doc.components.schemas.ErrorObject : undefined;
    if (!isObject(schema)) {
      violations.push({ path: rel, message: 'components.schemas.ErrorObject is missing' });
      continue;
    }
    const itemsRaw = schema.properties?.details?.items;
    const items =
      itemsRaw === undefined ? undefined : deref(doc, itemsRaw, rel, 'ErrorObject.details.items', violations);
    entries.push({ rel, schema, items, itemsIsRef: typeof itemsRaw?.$ref === 'string' });
  }
  if (entries.length === 0) return;

  const base = entries.find((e) => e.rel === REFERENCE_OPENAPI) ?? entries[0];
  const baseJson = canonicalJson(base.schema);
  const baseItemsJson = canonicalJson(base.items ?? null);
  for (const e of entries) {
    if (e === base) continue;
    if (canonicalJson(e.schema) !== baseJson) {
      violations.push({ path: e.rel, message: `ErrorObject schema differs from the one in ${base.rel}` });
    }
    if ((e.itemsIsRef || base.itemsIsRef) && canonicalJson(e.items ?? null) !== baseItemsJson) {
      violations.push({ path: e.rel, message: `ErrorObject details item schema differs from the one in ${base.rel}` });
    }
  }

  for (const e of entries) {
    if (shared.errorObject) {
      const props = isObject(e.schema.properties) ? Object.keys(e.schema.properties) : [];
      compareNames(e.rel, 'ErrorObject', props, shared.errorObject, violations);
    }
    if (shared.fieldViolation) {
      if (!isObject(e.items?.properties)) {
        violations.push({ path: e.rel, message: 'ErrorObject.details has no item schema with properties' });
      } else {
        const props = Object.keys(e.items.properties);
        compareNames(e.rel, 'ErrorObject.details item', props, shared.fieldViolation, violations);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

/** --help text. No line starts with `check-contracts:`, so none reads as a violation. */
const USAGE = `Usage: node tests/validation/check-contracts.mjs [--help]

Validates card-servicing-contracts/buf.yaml and .spectral.yaml, then runs buf lint over
both proto roots and Spectral over the six OpenAPI 3.1 documents, and checks that the
fourteen Stub Contract Baseline operations are present under their RPC names, methods and
paths, that every RPC and operation carries examples and the standard error object, and
that ErrorObject is identical across the OpenAPI documents and mirrors common.proto.

Runs from any working directory: every path resolves against the repository root.
Takes no argument other than -h or --help, which prints this text.

TMPDIR needs no setting. This check starts no browser and runs only the local buf and
Spectral from tests/validation/node_modules/.bin, so any TMPDIR works, long paths
included. TMPDIR matters to the suite only for check-diagrams: the Chrome that mmdc
starts there aborts with "Socket path too long" under a long TMPDIR.

Exit status:
  0  no violation
  1  one or more violations, one line each, then the count line
  2  usage, setup or internal error, such as an unknown argument or no YAML parser
`;

function main() {
  const args = process.argv.slice(2);
  const unknown = args.find((arg) => arg !== '-h' && arg !== '--help');
  if (unknown !== undefined) {
    console.error(`${CHECK}: unknown argument "${unknown}"; run with --help for usage`);
    process.exitCode = 2;
    return;
  }
  if (args.length > 0) {
    process.stdout.write(USAGE);
    process.exitCode = 0;
    return;
  }

  const violations = [];
  const notes = [];
  // A setup failure (a parser or ruleset package that does not resolve) exits 2, not 1.
  let setupFailure = false;

  // Nothing reached through a symbolic link is read here or handed to a tool, because
  // the link may lead outside the repository or under a CardDemo reference root. Every
  // fixed path this check opens or a tool lints is therefore checked with lstat alone,
  // before anything is statted or read through it, and each link is reported once
  // however many of those paths sit below it. Every later step skips a path for which
  // linked() is true: its link violation stands for it.
  const links = linkTracker(violations);
  const expected = [COMMON_PROTO, ...INTERFACES.flatMap((i) => [i.proto, i.openapi])];
  const fixedPaths = [...expected, BUF_CONFIG, SPECTRAL_RULESET, CONTRACTS_PROTO_ROOT, SERVICE_PROTO_ROOT];
  const linkOf = new Map(fixedPaths.map((rel) => [rel, links.onPath(rel)]));
  const linked = (rel) => linkOf.get(rel) !== null;

  for (const rel of expected) {
    if (!linked(rel) && !exists(rel)) violations.push({ path: rel, message: 'expected contract file is missing' });
  }

  const parseYaml = resolveYamlParser();
  const shared = checkProtos(violations, linked);

  const bufBlocked = [];
  const bufConfigLink = linkOf.get(BUF_CONFIG);
  if (bufConfigLink !== null) bufBlocked.push(linkReason(BUF_CONFIG, bufConfigLink));
  // The root walk asks links.onPath about each root again; a link already reported is not repeated.
  bufBlocked.push(...checkLintIgnoreComments(violations, links));
  const rulesetLink = linkOf.get(SPECTRAL_RULESET);

  if (!parseYaml) {
    setupFailure = true;
    violations.push({
      path: PACKAGE_JSON,
      message:
        'no YAML parser resolvable (@stoplight/yaml or js-yaml); buf lint, Spectral and the YAML content checks were not run',
    });
  } else {
    // Each tool runs only on a configuration that passed validation and on inputs
    // reached through no symbolic link, so neither reads a path, module, ruleset or
    // function that the checks did not allow. A tool held back is one violation that
    // names every reason.
    const configs = checkLintConfigs(parseYaml, violations, linked);
    setupFailure = configs.setupFailure;
    // A linked configuration is never validated: the link, already listed, is the reason.
    if (bufConfigLink === null && !configs.bufReady) bufBlocked.unshift('this configuration failed validation');
    if (bufBlocked.length === 0) runBuf(violations);
    else violations.push({ path: BUF_CONFIG, message: `buf lint was not run: ${bufBlocked.join('; ')}` });

    // Spectral resolves every $ref of a document it lints, so a document reaches it only
    // when it is reached through no symbolic link, parses and holds local references
    // alone. A linked document is not loaded or content-checked either: its not-run line
    // names the link, and the ErrorObject identity check covers the documents loaded.
    const loaded = [];
    const lintable = [];
    for (const iface of INTERFACES) {
      const link = linkOf.get(iface.openapi);
      if (link !== null) {
        violations.push({
          path: iface.openapi,
          message: `spectral lint was not run on this document: ${linkReason(iface.openapi, link, 'it')}`,
        });
        continue;
      }
      if (!exists(iface.openapi)) continue;
      const withheld = [];
      const doc = loadYaml(iface.openapi, parseYaml, violations);
      if (doc === undefined) {
        withheld.push('it does not parse as YAML');
      } else {
        const remote = nonLocalRefs(doc);
        if (remote.length > 0) {
          withheld.push(`it holds non-local $ref ${listRefs(remote)}; only local "#" references are allowed`);
        }
        loaded.push({ iface, doc });
      }
      if (withheld.length > 0) {
        violations.push({
          path: iface.openapi,
          message: `spectral lint was not run on this document: ${withheld.join('; ')}`,
        });
      } else {
        lintable.push(iface.openapi);
      }
    }
    const spectralBlocked = [];
    if (rulesetLink === null && !configs.spectralReady) spectralBlocked.push('this ruleset failed validation');
    if (rulesetLink !== null) spectralBlocked.push(linkReason(SPECTRAL_RULESET, rulesetLink));
    if (spectralBlocked.length > 0) {
      violations.push({ path: SPECTRAL_RULESET, message: `spectral lint was not run: ${spectralBlocked.join('; ')}` });
    } else if (lintable.length > 0) {
      runSpectral(lintable, violations, notes);
    }

    const docs = new Map();
    for (const { iface, doc } of loaded) {
      if (!isObject(doc)) {
        violations.push({ path: iface.openapi, message: 'document is not a YAML mapping' });
        continue;
      }
      if (!/^3\.1\.\d+$/.test(String(doc.openapi ?? ''))) {
        violations.push({ path: iface.openapi, message: `openapi is "${doc.openapi ?? ''}", expected 3.1.x` });
      }
      docs.set(iface.openapi, doc);
      checkBaselineOperations(doc, iface, violations);
      for (const operation of operationsOf(doc, iface.openapi, violations)) {
        checkOperation(doc, iface.openapi, operation, violations);
      }
    }
    checkErrorObjects(docs, shared, violations);
  }

  // `<check> (note): …` can never be read as a `<check>: <path>: <message>` violation line.
  for (const note of notes) console.log(`${CHECK} (note): ${note}`);
  report(CHECK, violations);
  // report() sets exit 0 or 1; a setup failure overrides it after the count is printed.
  if (setupFailure) process.exitCode = 2;
}

try {
  main();
} catch (err) {
  console.error(`${CHECK}: internal error: ${err?.stack ?? err}`);
  process.exitCode = 2;
}

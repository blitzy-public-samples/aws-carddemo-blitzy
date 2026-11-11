// SPDX-License-Identifier: Apache-2.0
/**
 * check-contracts — buf, Spectral and contract-content check for the Card Servicing
 * interfaces. run-all.sh runs it fifth, from any working directory, as
 * `node tests/validation/check-contracts.mjs`. It takes no arguments; -h or --help
 * prints its usage and exits 0, and any other argument exits 2.
 *
 * Passes when every listed interface has its `.proto` and OpenAPI 3.1 document and:
 * - buf.yaml and .spectral.yaml pass validation (checkLintConfigs), and `buf lint`
 *   (buf.yaml v2 over both proto roots with STANDARD and COMMENTS) and Spectral
 *   (.spectral.yaml extending spectral:oas) report zero errors;
 * - no path this check reads or a tool lints (buf.yaml, the two proto roots and
 *   everything inside them, .spectral.yaml and the six OpenAPI documents) is or sits
 *   below a symbolic link, and no proto carries a `buf:lint:ignore` directive (a
 *   comment line that begins with it);
 * - the fourteen Stub Contract Baseline operations are present under their RPC
 *   names, REST methods, paths and operationIds;
 * - the fixed baseline field contracts hold, extended only additively: proto message
 *   field types, labels, numbers, JSON names and oneof membership and RPC signatures;
 *   REST parameters, request bodies, 200 headers and 200 schemas, by every JSON Schema
 *   contract keyword, those beside a `$ref` included; and the ErrorObject schema;
 * - each baseline RPC agrees with its REST twin field by field, added fields included;
 * - every service proto imports common.proto and declares the ErrorDetail carrier of
 *   cardservicing.common.v1.ErrorObject, and every RPC's comment carries an Example
 *   request and an Example response, each JSON matching its message, and Errors
 *   codes that are standard ErrorObject codes matching its REST twin's error
 *   statuses, with the commitment that the ErrorObject is packed into
 *   google.rpc.Status details;
 * - every OpenAPI operation has a description, its baseline status set (any other
 *   operation a 200 and an error response), a 200 with a JSON schema and an example,
 *   examples that carry a value valid against their schema, and on every error
 *   response, `default` included, the ErrorObject schema with no keyword beside its
 *   `$ref`, and an example, carrying its status's ErrorObject code where the status
 *   maps to one;
 * - ErrorObject is identical across the six OpenAPI documents and mirrors
 *   cardservicing.common.v1.ErrorObject in camelCase.
 * A content violation prints as path:line. One with no source site prints as path:
 * a missing or unreadable file, a symbolic link, a missing tool or YAML parser, a
 * linter process failure or a tool held back, or YAML content read by the js-yaml
 * fallback.
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
 * Hostile content cannot hold the check up. YAML is read under the core schema, so a
 * tag outside it does not parse, and a document is refused before its data is built
 * when it holds more than 1 MiB of text, nests deeper than 64 levels, or holds an alias
 * or an `!include`. One example's schema evaluation stops after a fixed step budget, a
 * pattern match after 1 s (10 s for all matches of a run), buf after 120 s and Spectral
 * after 120 s per document; each stop is a violation. The tools run in a process group
 * of their own, which is killed whole when they are stopped and once more after they
 * end, and a SIGINT, SIGTERM or SIGHUP while one runs kills it and exits 128 + n.
 *
 * A failing check is fixed in the contracts, never by weakening this script.
 * Output: one line per distinct violation and a final count (lib/markdown.mjs report).
 * Informational lines read `check-contracts (note): <note>` and are not violations.
 * Exit 0 with no violation, 1 with any, 2 on an internal or setup error, such as no
 * resolvable YAML parser or @stoplight/spectral-rulesets (the count is still printed).
 */
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import {
  repoRoot,
  readText,
  exists,
  pathKind,
  listEntries,
  linkedComponent,
  toolBin,
  report,
  escapeControls,
  relativizeHostPaths,
  formatError,
} from './lib/markdown.mjs';

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

/** Maps a 0-based character offset in `text` to its 1-based line. */
function lineLocator(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i++) if (text[i] === '\n') starts.push(i + 1);
  return (offset) => {
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= offset) lo = mid;
      else hi = mid - 1;
    }
    return lo + 1;
  };
}

// ---------------------------------------------------------------------------
// YAML parser and source locations
// ---------------------------------------------------------------------------

/** A YAML syntax error, duplicate keys included, with its 1-based line (undefined when unknown). */
class YamlSyntaxError extends Error {
  constructor(message, line) {
    super(message);
    this.name = 'YamlSyntaxError';
    this.line = line;
  }
}

/** Most characters a YAML document may hold; the largest contract holds about 63 000. */
const YAML_MAX_CHARS = 1024 * 1024;
/** Deepest nesting of mappings and sequences a YAML document may hold, its root being level 1. */
const YAML_MAX_DEPTH = 64;

/**
 * A YAML document refused before its data is built: longer than YAML_MAX_CHARS, nested
 * deeper than YAML_MAX_DEPTH, or holding an alias or an `!include`. It is a
 * YamlSyntaxError, so loadYaml reports it as a violation at its line, never as an
 * internal error.
 */
class YamlLimitError extends YamlSyntaxError {
  constructor(message, line) {
    super(message, line);
    this.name = 'YamlLimitError';
  }
}

const YAML_TOO_DEEP = `it nests mappings and sequences deeper than ${YAML_MAX_DEPTH} levels`;
const YAML_TOO_DEEP_TO_PARSE = 'it nests mappings and sequences too deeply to parse';

/** Refuses a document longer than YAML_MAX_CHARS, before any parser reads it. */
function refuseLongYaml(text) {
  if (text.length > YAML_MAX_CHARS) {
    throw new YamlLimitError(`it holds ${text.length} characters, more than the ${YAML_MAX_CHARS} allowed`, undefined);
  }
}

/**
 * Loads the @stoplight/yaml-ast-parser AST of `text` under `schema` and walks it, without
 * building any data, in document order and without recursion: throws a YamlLimitError
 * at the first alias, `!include` or mapping or sequence nested deeper than
 * YAML_MAX_DEPTH. An alias is refused outright, because @stoplight/yaml copies its
 * target at every use, so a few nested aliases build millions of nodes from a few
 * hundred bytes. A stack overflow while loading is refused as nesting too deep to parse.
 */
function refuseUnboundedYamlAst(loadAst, schema, Kind, text, lineAt) {
  let ast;
  try {
    ast = loadAst(text, { schema, ignoreDuplicateKeys: true });
  } catch (err) {
    if (err instanceof RangeError) throw new YamlLimitError(YAML_TOO_DEEP_TO_PARSE, undefined);
    throw err;
  }
  const lineOf = (node) =>
    Number.isInteger(node.startPosition) && node.startPosition >= 0 ? lineAt(node.startPosition) : undefined;
  // [node, nesting level]; children are pushed last-first so they are visited in document order.
  const stack = ast ? [[ast, 1]] : [];
  while (stack.length > 0) {
    const [node, depth] = stack.pop();
    if (node === null || typeof node !== 'object') continue;
    if (node.kind === Kind.ANCHOR_REF) {
      throw new YamlLimitError(`alias *${node.referencesAnchor} is not allowed; write the value out in full`, lineOf(node));
    }
    if (node.kind === Kind.INCLUDE_REF) throw new YamlLimitError('an !include tag is not allowed', lineOf(node));
    if (node.kind === Kind.MAPPING) {
      // A key/value pair sits at its mapping's child level; the key is visited first.
      stack.push([node.value, depth], [node.key, depth]);
      continue;
    }
    if (node.kind !== Kind.MAP && node.kind !== Kind.SEQ) continue;
    if (depth > YAML_MAX_DEPTH) throw new YamlLimitError(YAML_TOO_DEEP, lineOf(node));
    const children = node.kind === Kind.MAP ? node.mappings : node.items;
    if (!Array.isArray(children)) continue;
    for (let i = children.length - 1; i >= 0; i--) stack.push([children[i], depth + 1]);
  }
}

/**
 * Walks data js-yaml built, without recursion, and throws a YamlLimitError when a
 * mapping or sequence is reached twice (an alias to it, or a cycle) or sits deeper than
 * YAML_MAX_DEPTH, so every later walker sees an acyclic, unshared tree of bounded depth.
 */
function refuseSharedYamlData(root) {
  const seen = new Set();
  const stack = [[root, 1]];
  while (stack.length > 0) {
    const [node, depth] = stack.pop();
    if (node === null || typeof node !== 'object') continue;
    if (seen.has(node)) {
      throw new YamlLimitError(
        'an alias repeats a mapping or sequence, or forms a cycle; aliases to mappings and sequences are not allowed',
        undefined,
      );
    }
    if (depth > YAML_MAX_DEPTH) throw new YamlLimitError(YAML_TOO_DEEP, undefined);
    seen.add(node);
    for (const child of Array.isArray(node) ? node : Object.values(node)) stack.push([child, depth + 1]);
  }
}

/**
 * Resolves a strict YAML parser from the locked tool tree: @stoplight/yaml (Spectral's
 * own parser), else js-yaml (hoisted by the mermaid toolchain). Never add a YAML package
 * to package.json for this: the manifest pins exactly the three validation tools.
 *
 * The parser maps YAML text to { data, locate } and throws a YamlSyntaxError on any
 * syntax error or duplicate key; @stoplight/yaml's plain `parse` returns a partial
 * result instead, which would let a malformed document be judged by its fragments.
 * `locate(jsonPath)` gives the 1-based line of the key a JSON path reaches (see
 * yamlLine); @stoplight/yaml provides it from its AST. js-yaml keeps no node positions,
 * so under it `locate` is null and every content violation of a YAML document falls
 * back to file-level output.
 *
 * Both parsers load under the YAML core schema (strings, numbers, booleans, null,
 * mappings and sequences), never a schema that constructs functions, regular
 * expressions, binary data or dates, so any other tag is a syntax error. Before any
 * data is built, a YamlLimitError refuses a document longer than YAML_MAX_CHARS, nested
 * deeper than YAML_MAX_DEPTH or too deeply to parse, holding an `!include`, or holding
 * an alias (under js-yaml, an alias to a mapping or sequence). The data returned is
 * therefore an acyclic tree with no shared node and at most YAML_MAX_DEPTH levels.
 */
function resolveYamlParser() {
  const require = createRequire(new URL('./package.json', import.meta.url));
  try {
    const { parseWithPointers, Kind } = require('@stoplight/yaml');
    // The AST loader and core schema of the yaml-ast-parser that @stoplight/yaml itself loads.
    const stoplightRequire = createRequire(require.resolve('@stoplight/yaml'));
    const { load: loadAst, Kind: AstKind } = stoplightRequire('@stoplight/yaml-ast-parser');
    const CORE_SCHEMA = stoplightRequire('@stoplight/yaml-ast-parser/dist/src/schema/core');
    if (
      typeof parseWithPointers === 'function' &&
      isObject(Kind) &&
      typeof loadAst === 'function' &&
      isObject(AstKind) &&
      isObject(CORE_SCHEMA)
    ) {
      return (text) => {
        refuseLongYaml(text);
        const lineAt = lineLocator(text);
        refuseUnboundedYamlAst(loadAst, CORE_SCHEMA, AstKind, text, lineAt);
        let result;
        try {
          // An options object without ignoreDuplicateKeys: false makes parseWithPointers
          // accept duplicate keys silently.
          result = parseWithPointers(text, { schema: CORE_SCHEMA, ignoreDuplicateKeys: false });
        } catch (err) {
          if (err instanceof RangeError) throw new YamlLimitError(YAML_TOO_DEEP_TO_PARSE, undefined);
          throw err;
        }
        const diagnostics = Array.isArray(result.diagnostics) ? result.diagnostics : [];
        const error = diagnostics.find((d) => d?.severity === 0);
        if (error) {
          const line = error.range?.start?.line;
          throw new YamlSyntaxError(String(error.message ?? 'syntax error'), Number.isInteger(line) ? line + 1 : undefined);
        }
        const locate = result.ast ? astKeyLocator(result.ast, Kind, lineAt) : null;
        return { data: result.data, locate };
      };
    }
  } catch {
    // Not resolvable from tests/validation; js-yaml is tried next.
  }
  try {
    const { load, CORE_SCHEMA } = require('js-yaml');
    if (typeof load === 'function' && isObject(CORE_SCHEMA)) {
      return (text) => {
        refuseLongYaml(text);
        let data;
        try {
          // js-yaml refuses any node, a scalar included, more than maxDepth levels deep;
          // YAML_MAX_DEPTH + 1 still admits a scalar inside the deepest collection allowed.
          data = load(text, { schema: CORE_SCHEMA, maxDepth: YAML_MAX_DEPTH + 1 });
        } catch (err) {
          if (err instanceof RangeError) throw new YamlLimitError(YAML_TOO_DEEP_TO_PARSE, undefined);
          if (err?.name !== 'YAMLException') throw err;
          const mark = err.mark?.line;
          const line = Number.isInteger(mark) ? mark + 1 : undefined;
          if (/^nesting exceeded maxDepth\b/.test(String(err.reason))) throw new YamlLimitError(YAML_TOO_DEEP, line);
          throw new YamlSyntaxError(String(err.reason ?? err.message), line);
        }
        refuseSharedYamlData(data);
        return { data, locate: null };
      };
    }
  } catch {
    // Neither parser resolves; the caller reports it.
  }
  return null;
}

/**
 * The `locate` of one @stoplight/yaml AST: `locate(jsonPath)` gives the 1-based line of
 * the deepest key a JSON path reaches, the line of the path's last mapping key (or
 * sequence item), so a path whose tail does not exist gives its nearest existing
 * ancestor, and the empty path gives line 1. A key is matched as the data is built, the
 * last of duplicate keys winning. Each mapping's scalar keys are indexed once, at its
 * first lookup, so a lookup never scans a mapping's keys again.
 */
function astKeyLocator(ast, Kind, lineAt) {
  // Mapping node → Map of each scalar key's value to the last pair that carries it.
  const keyIndexes = new WeakMap();
  const pairOf = (map, key) => {
    let index = keyIndexes.get(map);
    if (!index) {
      index = new Map();
      for (const m of map.mappings) if (m?.key?.kind === Kind.SCALAR) index.set(m.key.value, m);
      keyIndexes.set(map, index);
    }
    return index.get(key);
  };
  return (jsonPath) => {
    let node = ast;
    let offset = 0;
    for (const segment of jsonPath) {
      while (node?.kind === Kind.ANCHOR_REF) node = node.value;
      if (node?.kind === Kind.MAP && Array.isArray(node.mappings)) {
        const mapping = pairOf(node, String(segment));
        if (!mapping) break;
        offset = mapping.key.startPosition;
        node = mapping.value;
      } else if (node?.kind === Kind.SEQ && Array.isArray(node.items)) {
        const item = /^\d+$/.test(String(segment)) ? node.items[Number(segment)] : undefined;
        if (!item) break;
        offset = item.startPosition;
        node = item;
      } else {
        break;
      }
    }
    return lineAt(offset);
  };
}

/** Location of every object and array loadYaml returned: node → { locate, path }. */
const yamlLocations = new WeakMap();

/**
 * Records each object and array of a loaded document with the document's locator and
 * the JSON path that reaches it (the first one walked, should a node be shared).
 */
function recordYamlLocations(root, locate) {
  const stack = [[root, []]];
  while (stack.length > 0) {
    const [node, jsonPath] = stack.pop();
    if (node === null || typeof node !== 'object' || yamlLocations.has(node)) continue;
    yamlLocations.set(node, { locate, path: jsonPath });
    const entries = Array.isArray(node) ? node.map((v, i) => [i, v]) : Object.entries(node);
    for (let i = entries.length - 1; i >= 0; i--) stack.push([entries[i][1], [...jsonPath, entries[i][0]]]);
  }
}

/**
 * Source line for a violation's `line`, from the YAML location of an object or array
 * that loadYaml returned or that is reachable from it. A deref result is located at
 * its own definition, because a resolved `$ref` target is that node itself.
 *
 *   yamlLine(node)       line of node's own key (or sequence item); 1 for a document root
 *   yamlLine(node, key)  line of node[key]'s key; when node has no key `key`, node's own
 *                        line, so a missing key is reported at its nearest existing ancestor
 *
 * Returns undefined when nothing locates the node: a scalar, null or undefined, a value
 * not taken from a loaded document, or any node under the js-yaml fallback parser.
 * Pass the result as `line` as it is: report() prints an undefined line in the
 * file-level form.
 *
 * @param {unknown} node
 * @param {string | number} [key] mapping key, or sequence index
 * @returns {number | undefined}
 */
function yamlLine(node, key) {
  const at = node !== null && typeof node === 'object' ? yamlLocations.get(node) : undefined;
  if (!at) return undefined;
  return at.locate(key === undefined ? at.path : [...at.path, key]);
}

/**
 * yamlLine for a document root that loadYaml returned. An empty or scalar document has
 * no node to locate, and its site is line 1.
 * @param {unknown} doc
 * @param {string | number} [key]
 * @returns {number | undefined}
 */
const yamlDocLine = (doc, key) => (doc !== null && typeof doc === 'object' ? yamlLine(doc, key) : 1);

// ---------------------------------------------------------------------------
// Linters
// ---------------------------------------------------------------------------

/** Longest buf lint, or one Spectral run over one document, may take before it is stopped. */
const TOOL_TIMEOUT_MS = 120_000;
/** Most bytes a tool may print on each of stdout and stderr; one more stops it. */
const TOOL_OUTPUT_MAX_BYTES = 64 * 1024 * 1024;
/** Longest a buf line or a Spectral finding message may run in a violation. */
const TOOL_MESSAGE_MAX = 400;
/** Signals that, while a tool runs, stop its process group and end this check. */
const TOOL_STOP_SIGNALS = ['SIGINT', 'SIGTERM', 'SIGHUP'];

/**
 * Runs a local tool from the repository root and resolves, as spawnSync would return,
 * to { status, signal, stdout, stderr, error, timedOut, overflow }: stdout and stderr
 * decoded as UTF-8, `error` set when the tool could not start or its output could not
 * be read. `label` names the run in messages, as "buf lint".
 *
 * The tool runs detached, as the leader of a process group of its own, so the tool and
 * every process it starts (buf's node wrapper starts the buf binary) are stopped
 * together with SIGKILL: after TOOL_TIMEOUT_MS (timedOut), as soon as stdout or stderr
 * passes TOOL_OUTPUT_MAX_BYTES (overflow; output past the limit is not kept), and once
 * more after the tool has exited, so no process it left behind outlives the run. While
 * it runs, SIGINT, SIGTERM or SIGHUP stops the group and ends this check with status
 * 128 + the signal's number; the handlers are removed when the run ends. Otherwise a
 * failure to stop the group, other than its being gone already, rejects as an internal
 * error.
 *
 * @param {string} label
 * @param {string} bin absolute path from toolBin
 * @param {string[]} args
 * @returns {Promise<{ status: number | null, signal: string | null, stdout: string, stderr: string,
 *   error: Error | null, timedOut: boolean, overflow: boolean }>}
 */
function runTool(label, bin, args) {
  return new Promise((resolve, reject) => {
    const kept = { stdout: [], stderr: [] };
    const bytes = { stdout: 0, stderr: 0 };
    const signalHandlers = new Map();
    let timedOut = false;
    let overflow = false;
    let error = null;
    let settled = false;
    let timer;
    let child;

    // SIGKILL to the tool's process group; a group that is already gone is not an error.
    const stopGroup = () => {
      if (child?.pid === undefined) return;
      try {
        process.kill(-child.pid, 'SIGKILL');
      } catch (err) {
        if (err?.code !== 'ESRCH') throw err;
      }
    };
    const settle = (finish) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      for (const [signal, handler] of signalHandlers) process.removeListener(signal, handler);
      finish();
    };
    // Ends the run as an internal error, leaving nothing of the tool that keeps this process alive.
    const abort = (err) =>
      settle(() => {
        child?.stdout?.destroy();
        child?.stderr?.destroy();
        child?.unref();
        reject(err);
      });
    // Stops the group before the tool ends by itself. Its output streams are closed too,
    // so 'close' follows the tool's exit even if a process outside the group holds a pipe.
    const stopEarly = () => {
      try {
        stopGroup();
      } catch (err) {
        abort(err);
        return;
      }
      child.stdout.destroy();
      child.stderr.destroy();
    };

    try {
      child = spawn(bin, args, { cwd: repoRoot, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (err) {
      resolve({ status: null, signal: null, stdout: '', stderr: '', error: err, timedOut, overflow });
      return;
    }

    for (const signal of TOOL_STOP_SIGNALS) {
      const handler = () => {
        let outcome = 'its processes were stopped';
        try {
          stopGroup();
        } catch (err) {
          outcome = `its processes could not be stopped (${err?.code ?? err})`;
        }
        console.error(`${CHECK}: ${signal} received while ${label} ran; ${outcome}`);
        process.exit(128 + os.constants.signals[signal]);
      };
      signalHandlers.set(signal, handler);
      process.on(signal, handler);
    }
    timer = setTimeout(() => {
      timedOut = true;
      stopEarly();
    }, TOOL_TIMEOUT_MS);

    for (const name of ['stdout', 'stderr']) {
      child[name].on('data', (chunk) => {
        if (overflow) return;
        bytes[name] += chunk.length;
        if (bytes[name] > TOOL_OUTPUT_MAX_BYTES) {
          overflow = true;
          stopEarly();
          return;
        }
        kept[name].push(chunk);
      });
      child[name].on('error', (err) => {
        error ??= err;
      });
    }
    child.on('error', (err) => {
      error ??= err;
      // A tool that never started has no process to wait for.
      if (child.pid === undefined) {
        settle(() => resolve({ status: null, signal: null, stdout: '', stderr: '', error, timedOut, overflow }));
      }
    });
    child.on('close', (status, signal) => {
      try {
        stopGroup();
      } catch (err) {
        abort(err);
        return;
      }
      settle(() =>
        resolve({
          status,
          signal,
          stdout: Buffer.concat(kept.stdout).toString('utf8'),
          stderr: Buffer.concat(kept.stderr).toString('utf8'),
          error,
          timedOut,
          overflow,
        }),
      );
    });
  });
}

/** The violation for a tool run that was stopped early, or null when it ended by itself. */
function stoppedToolMessage(label, res, where = '') {
  if (res.timedOut) return `${label} did not finish within ${TOOL_TIMEOUT_MS / 1000} s${where} and was stopped`;
  if (res.overflow) {
    return `${label} printed more than ${TOOL_OUTPUT_MAX_BYTES / (1024 * 1024)} MiB on stdout or stderr${where} and was stopped`;
  }
  return null;
}

const BUF_FINDING_RE = /^(\S[^:]*\.proto):(\d+)(?::\d+)?:\s*(.*)$/;

async function runBuf(violations) {
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
  const res = await runTool('buf lint', bin, ['lint', '--config', BUF_CONFIG, '--disable-symlinks']);
  if (res.error) {
    violations.push({ path: BUF_CONFIG, message: `buf lint could not run: ${formatError(res.error)}` });
    return;
  }
  const stopped = stoppedToolMessage('buf lint', res);
  if (stopped) {
    violations.push({ path: BUF_CONFIG, message: stopped });
    return;
  }
  const lines = `${res.stdout ?? ''}\n${res.stderr ?? ''}`.split(/\r?\n/).filter((l) => l.trim() !== '');
  for (const line of lines) {
    const m = BUF_FINDING_RE.exec(line.trim());
    const file = m ? repoRelative(m[1]) : null;
    // Each line is one violation; `unclipped` keeps two lines that clip alike from merging in distinctViolations.
    const text = relativizeHostPaths(m && file ? m[3] : line.trim());
    const finding = { message: `buf: ${clipSpectralText(text, TOOL_MESSAGE_MAX)}`, unclipped: `buf: ${text}` };
    if (m && file) violations.push({ path: file, line: Number(m[2]), ...finding });
    else violations.push({ path: BUF_CONFIG, ...finding });
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

/**
 * `text` with host paths relativized, cut to `max` characters, ending in `…` when it was
 * cut, so one violation stays one readable line.
 */
function clipSpectralText(text, max = 200) {
  const s = relativizeHostPaths(String(text));
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
 * Violations, per document: Spectral could not start; it was stopped (runTool) after
 * TOOL_TIMEOUT_MS or for printing more than TOOL_OUTPUT_MAX_BYTES, reported alone, its
 * output not decoded; stdout is not one decodable findings array (extractJsonArray),
 * whatever the exit status; one or more results are not findings
 * (spectralFindingProblem), reported once with the count and the first; each
 * error-severity finding, at its source line, its text clipped to TOOL_MESSAGE_MAX
 * characters; a signal kill; a non-zero exit with no error finding; stderr holding
 * anything but Node.js runtime warnings, reported once with its first unrecognised lines.
 *
 * Notes: text printed before the findings array; each distinct Node.js runtime warning,
 * once across all documents and without its process id; the warning, info and hint
 * counts across all documents.
 *
 * @param {string[]} files root-relative OpenAPI document paths
 * @param {{ path: string, line?: number, message: string, unclipped?: string }[]} violations appended to
 * @param {string[]} notes plain informational lines, appended to
 */
async function runSpectral(files, violations, notes) {
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
    const args = ['lint', rel, '--ruleset', SPECTRAL_RULESET, '--fail-severity=error', '-f', 'json'];
    const res = await runTool(`spectral lint of ${rel}`, bin, args);
    if (res.error) {
      violations.push({ path: rel, message: `spectral lint could not run: ${formatError(res.error)}` });
      continue;
    }
    // Output of a run that was stopped is incomplete, so it is not decoded.
    const stopped = stoppedToolMessage('spectral lint', res, ' on this document');
    if (stopped) {
      violations.push({ path: rel, message: stopped });
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
        // Each finding is one violation; `unclipped` keeps two that clip alike from merging in distinctViolations.
        const text = `spectral ${r.code}: ${relativizeHostPaths(r.message)}`;
        violations.push({
          path: file,
          line: r.range.start.line + 1,
          message: clipSpectralText(text, TOOL_MESSAGE_MAX),
          unclipped: text,
        });
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

/**
 * Line of the first `$ref` key in a loaded YAML value whose target is `ref`, in the
 * document order nonLocalRefs walks; undefined when no node is located (yamlLine).
 */
function refLine(value, ref) {
  const seen = new Set();
  const walk = (node) => {
    if (node === null || typeof node !== 'object' || seen.has(node)) return undefined;
    seen.add(node);
    if (!Array.isArray(node) && node.$ref === ref) return yamlLine(node, '$ref');
    for (const child of Array.isArray(node) ? node : Object.values(node)) {
      const line = walk(child);
      if (line !== undefined) return line;
    }
    return undefined;
  };
  return walk(value);
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
  const add = (message, line) => violations.push({ path: BUF_CONFIG, line, message });
  const buf = loadYaml(BUF_CONFIG, parseYaml, violations);
  if (buf === undefined) return false;
  if (!isObject(buf)) {
    add('configuration is not a YAML mapping', yamlDocLine(buf));
    return false;
  }
  for (const key of Object.keys(buf)) {
    if (!BUF_KEYS.includes(key)) {
      add(`key "${key}" is not allowed: ${BUF_KEY_REASONS[key] ?? `buf.yaml may hold only ${BUF_KEYS.join(', ')}`}`, yamlLine(buf, key));
    }
  }
  if (buf.version !== 'v2') {
    add(`version is ${buf.version === undefined ? 'missing' : JSON.stringify(buf.version)}, expected "v2"`, yamlDocLine(buf, 'version'));
  }

  const roots = [CONTRACTS_PROTO_ROOT, SERVICE_PROTO_ROOT];
  const paths = [];
  // The line of each entry in paths, so a repeated or extra path is reported at its entry.
  const pathLines = [];
  const modulesLine = yamlDocLine(buf, 'modules');
  if (!Array.isArray(buf.modules)) {
    add('modules is not a list of path entries', modulesLine);
  } else {
    buf.modules.forEach((entry, i) => {
      const label = typeof entry?.path === 'string' ? `module ${entry.path}` : `module entry ${i + 1}`;
      const entryLine = yamlLine(buf.modules, i);
      if (!isObject(entry)) {
        add(`${label} is not a mapping`, entryLine);
        return;
      }
      for (const key of Object.keys(entry)) {
        if (key !== 'path') {
          add(`${label}: key "${key}" is not allowed: a module entry holds only path, so nothing narrows what buf lints in it`, yamlLine(entry, key));
        }
      }
      if (typeof entry.path === 'string') {
        paths.push(entry.path);
        pathLines.push(yamlLine(entry, 'path'));
      } else {
        add(`${label}: path is missing or not a string`, yamlLine(entry, 'path'));
      }
    });
  }
  for (const root of roots) {
    if (!paths.includes(root)) add(`modules lack path ${root}`, modulesLine);
  }
  const seen = new Set();
  paths.forEach((p, j) => {
    if (seen.has(p)) add(`modules list path ${p} more than once`, pathLines[j]);
    else if (!roots.includes(p)) add(`modules include path ${p}, which buf would read; the modules must be exactly ${roots.join(' and ')}`, pathLines[j]);
    seen.add(p);
  });

  const lint = buf.lint;
  if (lint !== undefined && !isObject(lint)) add('lint is not a mapping', yamlDocLine(buf, 'lint'));
  const settings = isObject(lint) ? lint : {};
  const use = settings.use;
  if (use !== undefined && !(Array.isArray(use) && use.every((name) => typeof name === 'string'))) {
    add('lint.use is not a list of rule and category names', yamlLine(settings, 'use'));
  }
  const used = Array.isArray(use) ? use : [];
  for (const category of ['STANDARD', 'COMMENTS']) {
    if (!used.includes(category)) add(`lint.use lacks ${category}`, yamlLine(settings, 'use') ?? yamlDocLine(buf, 'lint'));
  }
  for (const [key, value] of Object.entries(settings)) {
    if (key === 'use' || (key === 'disallow_comment_ignores' && value === true)) continue;
    if (key === 'disallow_comment_ignores') {
      add(`lint.disallow_comment_ignores is ${JSON.stringify(value)}; only true is allowed`, yamlLine(settings, key));
    } else {
      const reason =
        BUF_LINT_KEY_REASONS[key] ??
        'lint may hold only use and disallow_comment_ignores: true, because any other setting relaxes the STANDARD or COMMENTS rules';
      add(`lint.${key} is not allowed: ${reason}`, yamlLine(settings, key));
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
  const add = (message, line) => violations.push({ path: SPECTRAL_RULESET, line, message });
  const ruleset = loadYaml(SPECTRAL_RULESET, parseYaml, violations);
  if (ruleset === undefined) return { ready: false, setupFailure: false };
  if (!isObject(ruleset)) {
    add('ruleset is not a YAML mapping', yamlDocLine(ruleset));
    return { ready: false, setupFailure: false };
  }
  for (const key of Object.keys(ruleset)) {
    if (!SPECTRAL_KEYS.includes(key)) {
      add(`key "${key}" is not allowed: ${SPECTRAL_KEY_REASONS[key] ?? `the ruleset may hold only ${SPECTRAL_KEYS.join(' and ')}`}`, yamlLine(ruleset, key));
    }
  }

  const ext = ruleset.extends;
  const entries = ext === undefined ? [] : Array.isArray(ext) ? ext : [ext];
  const namesOas = (entry) => entry === OAS_RULESET || (Array.isArray(entry) && entry[0] === OAS_RULESET);
  const extendsLine = yamlDocLine(ruleset, 'extends');
  // An entry's line: its item when extends is a list, else the extends key.
  const entryLine = (i) => (Array.isArray(ext) ? yamlLine(ext, i) ?? extendsLine : extendsLine);
  if (!entries.some(namesOas)) add(`extends does not include "${OAS_RULESET}"`, extendsLine);
  let oasEntries = 0;
  entries.forEach((entry, i) => {
    if (!namesOas(entry)) {
      add(`extends entry ${describeSetting(entry)} is not allowed: Spectral would load it before any check; extends holds only "${OAS_RULESET}"`, entryLine(i));
    } else if (entry !== OAS_RULESET && !(entry.length === 2 && OAS_MODES.includes(entry[1]))) {
      add(`extends entry ${JSON.stringify(entry)} is not allowed: only "${OAS_RULESET}" or ["${OAS_RULESET}", "recommended" | "all"] keeps its rules active`, entryLine(i));
    } else if (++oasEntries > 1) {
      add(`extends lists "${OAS_RULESET}" more than once`, entryLine(i));
    }
  });

  for (const ref of nonLocalRefs(ruleset)) {
    add(`$ref ${JSON.stringify(ref)} is not allowed: Spectral would load it before any check; only local "#" references are`, refLine(ruleset, ref));
  }

  let setupFailure = false;
  if (hasOwn(ruleset, 'rules')) {
    const rules = ruleset.rules;
    if (!isObject(rules)) {
      add('rules is not a mapping of rule names to settings', yamlLine(ruleset, 'rules'));
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
              yamlLine(rules, name),
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

/*
 * Source anchors. A violation about content that exists in a file carries the
 * 1-based `line` of its site (yamlLine for a YAML node, the parseProto model's lines
 * for a proto), and report() prints it as `path:line:`. A violation with no source
 * site keeps the file-level `path:` form: an expected file that is missing or cannot
 * be read, a symbolic link, a missing tool binary, a buf or Spectral process failure
 * or a tool held back, no resolvable YAML parser, and any YAML content under the
 * js-yaml fallback, which keeps no node positions.
 */

/**
 * Parses a root-relative YAML file and records its node locations for yamlLine;
 * undefined (with a violation) when it is absent, cannot be read (a directory, no
 * permission and the like), does not parse or is refused. Only a YamlSyntaxError,
 * duplicate keys included, is a parse failure, reported at the line the parser names,
 * and a YamlLimitError among them prints as "YAML is refused"; any other error the
 * parser throws is an internal error. Callers never pass a path reached through a
 * symbolic link: main reports the link instead.
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
    violations.push({ path: rel, message: `cannot be read: ${formatError(err)}` });
    return undefined;
  }
  let parsed;
  try {
    parsed = parseYaml(text);
  } catch (err) {
    if (!(err instanceof YamlSyntaxError)) throw err;
    const verdict = err instanceof YamlLimitError ? 'YAML is refused' : 'YAML does not parse';
    violations.push({
      path: rel,
      line: Number.isInteger(err.line) ? err.line : undefined,
      message: `${verdict}: ${String(err.message).split('\n')[0]}`,
    });
    return undefined;
  }
  const data = parsed.data ?? null;
  if (parsed.locate) recordYamlLocations(data, parsed.locate);
  return data;
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

/** `text` from `start` on replaced by `lead` and spaces, keeping its length. */
const blankFrom = (text, start, lead) => `${text.slice(0, start)}${lead}${' '.repeat(text.length - start - lead.length)}`;

/**
 * A message body's own statements: nested message, enum and extend blocks and option
 * aggregates are dropped, while oneof bodies are kept, because oneof members are
 * fields of the enclosing message. Dropped text is blanked and a dropped block or a
 * oneof's closing brace becomes `;`, so the result is as long as the body and an
 * offset in it is the same offset in the body. When `oneofs` is an array, each oneof
 * is appended to it as { name, start, end }: the body offsets its members lie between.
 */
function topLevelStatements(body, oneofs) {
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
      const oneof = /\boneof\s+([A-Za-z_]\w*)\s*$/.exec(out);
      if (oneof) {
        oneofs?.push({ name: oneof[1], start: i + 1, end: close });
        out = `${blankFrom(out, oneof.index, ';')} ${topLevelStatements(inner)};`;
      } else {
        const header = /\b(?:message|enum|extend)\s+[\w.]+\s*$/.exec(out);
        if (header) out = blankFrom(out, header.index, '');
        out += `${' '.repeat(close - i)};`;
      }
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
  String.raw`^(?:(repeated|optional|required)\s+)?(${MAP_TYPE}|${TYPE_NAME})\s+([A-Za-z_]\w*)\s*=\s*(\d+)\s*(\[[\s\S]*\])?$`,
);
/** One RPC declaration, ended by `;` or an options body: name, then each side's stream flag and type. */
const RPC_RE = new RegExp(
  String.raw`\brpc\s+([A-Za-z_]\w*)\s*\(\s*(stream\s+)?(${TYPE_NAME})\s*\)\s*returns\s*\(\s*(stream\s+)?(${TYPE_NAME})\s*\)\s*[;{]`,
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
 * The model of one proto file that the content checks read. Every line is 1-based,
 * of the statement's first token.
 *
 *   imports   [{ path, line }]  each import statement, in file order
 *   package   { name, line } of the package statement, or null when there is none
 *   services  Map name → { name, line, rpcs }; rpcs: [{ name, line, requestType,
 *             requestStream, responseType, responseStream, comment, commentLines }],
 *             where a type is as written (a leading `.` kept), a stream flag is true
 *             when the side is declared `stream`, comment is the leading `//` block
 *             joined with "\n" (each line's text after `//`), and commentLines is
 *             that block as [{ line, text }]
 *   messages  Map name → { name, line, fields } for every message, nested ones
 *             included (the first of a repeated name kept); fields: [{ name, json,
 *             type, label, number, oneof, line }] for the message's own fields, oneof
 *             members included and nested messages' fields excluded, where json is
 *             the proto3 JSON name (json_name option honoured), type is as written
 *             (`map<K, V>` included), label is 'repeated', 'optional', 'required'
 *             (proto2 only) or '' for a singular field, number is an integer, and
 *             oneof is the name of the oneof the field is a member of, '' for none (a
 *             proto3 `optional` field is not a member of any)
 */
function parseProto(text) {
  const { code, comments } = scanProto(text);
  const src = code.join('\n');
  const lineAt = lineLocator(src);

  const imports = [];
  for (const m of src.matchAll(/\bimport\s+(?:(?:public|weak)\s+)?"([^"]*)"\s*;/g)) {
    imports.push({ path: m[1], line: lineAt(m.index) });
  }

  const pkg = /\bpackage\s+([A-Za-z_][\w.]*)\s*;/.exec(src);

  const services = new Map();
  for (const m of src.matchAll(/\bservice\s+([A-Za-z_]\w*)\s*\{/g)) {
    const open = m.index + m[0].length - 1;
    const body = src.slice(open, closingBrace(src, open));
    const rpcs = [];
    for (const r of body.matchAll(RPC_RE)) {
      const line = lineAt(open + r.index);
      const commentLines = leadingComment(code, comments, line - 1);
      rpcs.push({
        name: r[1],
        line,
        requestType: r[3],
        requestStream: r[2] !== undefined,
        responseType: r[5],
        responseStream: r[4] !== undefined,
        comment: commentLines.map((c) => c.text).join('\n'),
        commentLines,
      });
    }
    if (!services.has(m[1])) services.set(m[1], { name: m[1], line: lineAt(m.index), rpcs });
  }

  const messages = new Map();
  for (const m of src.matchAll(/\bmessage\s+([A-Za-z_]\w*)\s*\{/g)) {
    if (messages.has(m[1])) continue;
    const open = m.index + m[0].length - 1;
    const fields = [];
    const oneofs = [];
    let offset = open + 1;
    for (const statement of topLevelStatements(src.slice(open + 1, closingBrace(src, open)), oneofs).split(';')) {
      const start = offset + (statement.length - statement.trimStart().length);
      offset += statement.length + 1;
      const f = FIELD_RE.exec(statement.trim());
      if (!f || f[2] === 'option') continue;
      // A proto string literal takes either quote.
      const explicit = f[5] ? /\bjson_name\s*=\s*(?:"([^"]*)"|'([^']*)')/.exec(f[5]) : null;
      const at = start - (open + 1);
      fields.push({
        name: f[3],
        json: explicit ? (explicit[1] ?? explicit[2]) : jsonName(f[3]),
        type: f[2],
        label: f[1] ?? '',
        number: Number(f[4]),
        oneof: oneofs.find((o) => at >= o.start && at < o.end)?.name ?? '',
        line: lineAt(start),
      });
    }
    messages.set(m[1], { name: m[1], line: lineAt(m.index), fields });
  }
  return { imports, package: pkg ? { name: pkg[1], line: lineAt(pkg.index) } : null, services, messages };
}

/**
 * The contiguous `//` comment block directly above a 0-based line, as [{ line, text }]
 * with 1-based lines: comment-only lines, stopping at the first blank or code line.
 */
function leadingComment(code, comments, index) {
  const block = [];
  for (let i = index - 1; i >= 0; i--) {
    if (comments[i] === null || code[i].trim() !== '') break;
    block.unshift({ line: i + 1, text: comments[i] });
  }
  return block;
}

// ---------------------------------------------------------------------------
// RPC examples, Errors blocks and the error carrier
// ---------------------------------------------------------------------------

/** The example markers of RPC_COMMENT_MARKERS, each with the rpc model key of the type it shows. */
const RPC_EXAMPLE_TYPES = { 'Example request:': 'requestType', 'Example response:': 'responseType' };
const RPC_ERRORS_MARKER = 'Errors:';
/** One Errors entry, `CODE - description`. */
const ERROR_ENTRY_RE = /^\s*([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*)\s+-\s*(.*)$/;
/**
 * The standard ErrorObject codes, each with the REST status it maps to, as the
 * ErrorObject.code comment in common.proto lists them.
 */
const ERROR_CODE_STATUS = new Map([
  ['INVALID_ARGUMENT', '400'],
  ['UNAUTHENTICATED', '401'],
  ['PERMISSION_DENIED', '403'],
  ['NOT_FOUND', '404'],
  ['UNAVAILABLE', '503'],
  ['DEADLINE_EXCEEDED', '504'],
]);
/**
 * The baseline status mapping, ERROR_CODE_STATUS keyed by status: the ErrorObject
 * `code` every example of a mapped status carries.
 */
const ERROR_CODE_BY_STATUS = new Map(Array.from(ERROR_CODE_STATUS, ([code, status]) => [status, code]));
const ERROR_OBJECT_TYPE = 'cardservicing.common.v1.ErrorObject';
/** The message each service proto declares to name the error object its RPCs pack. */
const ERROR_CARRIER = 'ErrorDetail';

const SCALAR_TYPES = new Set([
  'double', 'float', 'int32', 'int64', 'uint32', 'uint64', 'sint32', 'sint64',
  'fixed32', 'fixed64', 'sfixed32', 'sfixed64', 'bool', 'string', 'bytes',
]);
const INT32_RANGE = [-(2n ** 31n), 2n ** 31n - 1n];
const UINT32_RANGE = [0n, 2n ** 32n - 1n];
const INT64_RANGE = [-(2n ** 63n), 2n ** 63n - 1n];
const UINT64_RANGE = [0n, 2n ** 64n - 1n];
/** The value range of each integer scalar, which proto3 JSON gives as a number or a decimal string. */
const INT_RANGES = {
  int32: INT32_RANGE, sint32: INT32_RANGE, sfixed32: INT32_RANGE,
  uint32: UINT32_RANGE, fixed32: UINT32_RANGE,
  int64: INT64_RANGE, sint64: INT64_RANGE, sfixed64: INT64_RANGE,
  uint64: UINT64_RANGE, fixed64: UINT64_RANGE,
};
const MAP_TYPE_RE = /^map\s*<\s*([\w.]+)\s*,\s*([\w.]+)\s*>$/;
const JSON_NUMBER_RE = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
const BASE64_RE = /^[A-Za-z0-9+/_-]*={0,2}$/;

/** JSON path of an object member or array item, from `$` for the root value. */
const memberPath = (p, key) => (/^[A-Za-z_$][\w$]*$/.test(key) ? `${p}.${key}` : `${p}[${JSON.stringify(key)}]`);
const itemPath = (p, n) => `${p}[${n}]`;

/** A JSON value for a message: its kind, and a scalar's text cut to 40 characters. */
function jsonKind(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'an array';
  if (typeof value === 'object') return 'an object';
  const text = JSON.stringify(value);
  return `${typeof value} ${text.length > 40 ? `${text.slice(0, 37)}...` : text}`;
}

/** Simple names of every enum a proto file declares, nested ones included. */
function protoEnumNames(text) {
  const src = scanProto(text).code.join('\n');
  return new Set(Array.from(src.matchAll(/\benum\s+([A-Za-z_]\w*)\s*\{/g), (m) => m[1]));
}

/**
 * Every parsed proto as a type scope for resolveProtoType: root-relative path →
 * { rel, pkg, model, enums }, pkg being '' for a file without a package statement.
 */
function protoTypeFiles(protos) {
  const files = new Map();
  for (const [rel, model] of protos) {
    files.set(rel, { rel, pkg: model.package?.name ?? '', model, enums: protoEnumNames(readText(rel)) });
  }
  return files;
}

/**
 * Resolves a message or enum type as written in the file `from` (a protoTypeFiles
 * entry) under protobuf scoping: a leading `.` makes the name absolute, else it is
 * tried in the file's package and then in each enclosing package. A candidate is
 * looked up in the file itself and in the parsed files it imports, a nested name by
 * its chain of message names. Returns { kind: 'message', message, file } or
 * { kind: 'enum' }, or null for a type declared in a file this check does not parse.
 */
function resolveProtoType(written, from, typeFiles) {
  const absolute = written.startsWith('.');
  const name = absolute ? written.slice(1) : written;
  const visible = [from];
  for (const imported of from.model.imports) {
    for (const root of [CONTRACTS_PROTO_ROOT, SERVICE_PROTO_ROOT]) {
      const file = typeFiles.get(`${root}/${imported.path}`);
      if (file && !visible.includes(file)) visible.push(file);
    }
  }
  const scopes = [];
  if (!absolute) for (let p = from.pkg; p !== ''; p = p.slice(0, Math.max(p.lastIndexOf('.'), 0))) scopes.push(`${p}.`);
  scopes.push('');
  for (const scope of scopes) {
    const full = `${scope}${name}`;
    for (const file of visible) {
      const prefix = file.pkg === '' ? '' : `${file.pkg}.`;
      if (!full.startsWith(prefix)) continue;
      const parts = full.slice(prefix.length).split('.');
      const simple = parts.pop();
      if (!parts.every((outer) => file.model.messages.has(outer))) continue;
      if (file.model.messages.has(simple)) return { kind: 'message', message: file.model.messages.get(simple), file };
      if (file.enums.has(simple)) return { kind: 'enum' };
    }
  }
  return null;
}

/** What a proto3 JSON value of a scalar type must be, or null when `value` is one. */
function scalarExpectation(value, type) {
  if (type === 'string') return typeof value === 'string' ? null : 'a string';
  if (type === 'bytes') return typeof value === 'string' && BASE64_RE.test(value) ? null : 'a base64 string';
  if (type === 'bool') return typeof value === 'boolean' ? null : 'true or false';
  if (type === 'double' || type === 'float') {
    const ok =
      typeof value === 'number' ||
      (typeof value === 'string' && (JSON_NUMBER_RE.test(value) || ['NaN', 'Infinity', '-Infinity'].includes(value)));
    return ok ? null : 'a number, or "NaN", "Infinity" or "-Infinity"';
  }
  const [min, max] = INT_RANGES[type];
  let n = null;
  if (typeof value === 'number' && Number.isInteger(value)) n = BigInt(value);
  else if (typeof value === 'string' && /^-?\d+$/.test(value)) n = BigInt(value);
  return n !== null && n >= min && n <= max ? null : `an integer from ${min} to ${max}, as a number or a decimal string`;
}

/**
 * Appends to `out` each way a singular value (a repeated item or map value included)
 * departs from the proto3 JSON form of a type as written in `file`. A type declared
 * in a file this check does not parse has a JSON form of its own (a well-known type,
 * for example), so its value is not checked.
 */
function valueProblems(value, type, file, typeFiles, path, out) {
  if (SCALAR_TYPES.has(type)) {
    const expected = scalarExpectation(value, type);
    if (expected) out.push({ path, problem: `is ${jsonKind(value)}, expected ${type} (${expected})` });
    return;
  }
  const resolved = resolveProtoType(type, file, typeFiles);
  if (resolved?.kind === 'message') {
    if (isObject(value)) messageProblems(value, resolved.message, resolved.file, typeFiles, path, out);
    else out.push({ path, problem: `is ${jsonKind(value)}, expected an object (message ${type})` });
  } else if (resolved?.kind === 'enum' && typeof value !== 'string' && !Number.isInteger(value)) {
    out.push({ path, problem: `is ${jsonKind(value)}, expected enum ${type} (a value name or an integer)` });
  }
}

/** valueProblems for a field's value: repeated fields take an array, map fields an object. */
function fieldProblems(value, field, file, typeFiles, path, out) {
  const map = MAP_TYPE_RE.exec(field.type);
  if (map) {
    if (!isObject(value)) {
      out.push({ path, problem: `is ${jsonKind(value)}, expected an object (${field.type})` });
      return;
    }
    const [, keyType, valueType] = map;
    for (const [key, item] of Object.entries(value)) {
      const child = memberPath(path, key);
      let expected = null;
      if (keyType === 'bool') {
        expected = key === 'true' || key === 'false' ? null : '"true" or "false"';
      } else if (hasOwn(INT_RANGES, keyType)) {
        const [min, max] = INT_RANGES[keyType];
        const inRange = /^-?\d+$/.test(key) && BigInt(key) >= min && BigInt(key) <= max;
        expected = inRange ? null : `a decimal integer from ${min} to ${max}`;
      }
      if (expected) out.push({ path: child, problem: `has a key that is not ${keyType} (${expected})` });
      valueProblems(item, valueType, file, typeFiles, child, out);
    }
  } else if (field.label === 'repeated') {
    if (!Array.isArray(value)) {
      out.push({ path, problem: `is ${jsonKind(value)}, expected an array (repeated ${field.type})` });
      return;
    }
    value.forEach((item, n) => valueProblems(item, field.type, file, typeFiles, itemPath(path, n), out));
  } else {
    valueProblems(value, field.type, file, typeFiles, path, out);
  }
}

/**
 * Appends to `out` each way a JSON object departs from a message under the proto3
 * JSON mapping: every key is a field's JSON name or proto name, no field is set under
 * both, every non-null value has its field's JSON form (null stands for any field's
 * default, so a null oneof member is unset), and at most one member of each oneof
 * carries a non-null value. A field's second key is reported at its own path, naming
 * the field and its first key's path; a null value still sets the key, as proto3 JSON
 * parsers reject a field already set before reading its value. Each further member of
 * a oneof is reported at its own path, naming the member set first and its path. A
 * key repeated verbatim is out of reach here, since JSON.parse keeps only its last
 * occurrence; jsonSites finds it in the text.
 */
function messageProblems(obj, message, file, typeFiles, path, out) {
  // field → path of the first key that set it.
  const fieldsSet = new Map();
  // oneof name → { field, path } of its first member with a non-null value.
  const oneofsSet = new Map();
  for (const [key, value] of Object.entries(obj)) {
    const child = memberPath(path, key);
    const field = message.fields.find((f) => f.json === key) ?? message.fields.find((f) => f.name === key);
    if (!field) {
      out.push({ path: child, problem: `is not a field of ${message.name}` });
      continue;
    }
    const setAt = fieldsSet.get(field);
    if (setAt === undefined) {
      fieldsSet.set(field, child);
    } else {
      out.push({
        path: child,
        problem:
          `sets field ${message.name}.${field.name}, already set at ${setAt}; proto3 JSON sets a field ` +
          `once, under its JSON name ${field.json} or its proto name ${field.name}`,
      });
    }
    if (value === null) continue;
    if (field.oneof) {
      const first = oneofsSet.get(field.oneof);
      if (!first) {
        oneofsSet.set(field.oneof, { field, path: child });
      } else if (first.field !== field) {
        out.push({
          path: child,
          problem:
            `sets member ${field.name} of oneof ${message.name}.${field.oneof}, whose member ` +
            `${first.field.name} is already set at ${first.path}; a oneof holds at most one member`,
        });
      }
    }
    fieldProblems(value, field, file, typeFiles, child, out);
  }
}

/**
 * Offset of each value's site in a valid JSON text, by the JSON path memberPath and
 * itemPath build: a member's key, an array item's first character, 0 for the root. A
 * key repeated within one object keeps its last site, the occurrence JSON.parse keeps,
 * and each repetition is appended to `repeats` as { path, key, site, first }, first
 * being the offset of the key's first occurrence in that object.
 */
function jsonSites(text, repeats = []) {
  const sites = new Map([['$', 0]]);
  let i = 0;
  const skip = () => {
    while (i < text.length && /\s/.test(text[i])) i++;
  };
  const string = () => {
    const start = i++;
    while (i < text.length && text[i] !== '"') i += text[i] === '\\' ? 2 : 1;
    i++;
    return JSON.parse(text.slice(start, i));
  };
  const value = (p) => {
    skip();
    const open = text[i];
    if (open === '{' || open === '[') {
      const close = open === '{' ? '}' : ']';
      // An object's key → offset of its first occurrence.
      const keys = new Map();
      i++;
      skip();
      for (let n = 0; i < text.length && text[i] !== close; n++) {
        const site = i;
        let child = itemPath(p, n);
        if (open === '{') {
          const key = string();
          child = memberPath(p, key);
          if (keys.has(key)) repeats.push({ path: child, key, site, first: keys.get(key) });
          else keys.set(key, site);
          skip();
          i++; // the ':'
        }
        sites.set(child, site);
        value(child);
        skip();
        if (text[i] === ',') i++;
        skip();
      }
      i++;
    } else if (open === '"') {
      string();
    } else {
      while (i < text.length && !/[\s,\]}]/.test(text[i])) i++;
    }
  };
  value('$');
  return sites;
}

/**
 * The JSON value an example marker introduces in an RPC's leading comment. After the
 * marker only whitespace, across comment lines, may come before a value opening with
 * `{` or `[`; the value runs to its balanced close, string-aware, and prose after it
 * is not part of it. Returns { error, line } when no value follows or it never
 * closes, else { text, line, lineOf }, lineOf mapping an offset in text to its line.
 */
function exampleValue(rpc, marker) {
  const lines = rpc.commentLines;
  const at = lines.findIndex((c) => c.text.includes(marker));
  const first = lines[at];
  // The rest of the marker line, then every later line of the block, whose lines are consecutive.
  const rest = first.text.slice(first.text.indexOf(marker) + marker.length);
  const src = [rest, ...lines.slice(at + 1).map((c) => c.text)].join('\n');
  const lineAt = lineLocator(src);
  const start = src.search(/\S/);
  if (start < 0 || (src[start] !== '{' && src[start] !== '[')) {
    const found = start < 0 ? '' : ` (found "${src.slice(start).split('\n')[0].trim().slice(0, 40)}")`;
    return { error: `is not followed by a JSON example${found}`, line: first.line };
  }
  const startLine = first.line + lineAt(start) - 1;
  const end = balancedEnd(src, start);
  if (end === -2) return { error: 'example opens a JSON value whose brackets do not match', line: startLine };
  if (end < 0) return { error: 'example opens a JSON value that never closes', line: startLine };
  return { text: src.slice(start, end + 1), line: startLine, lineOf: (offset) => first.line + lineAt(start + offset) - 1 };
}

/**
 * Checks the example after one example marker: a JSON object that parses, repeats no
 * key within any of its objects, and matches the RPC's request or response message
 * under the proto3 JSON mapping, so `{}` suits every message. A type declared in a
 * file this check does not parse is held to being an object with no repeated key.
 */
function checkRpcExample(rel, rpc, marker, typeName, typeFiles, violations) {
  const where = `rpc ${rpc.name}: "${marker}"`;
  const found = exampleValue(rpc, marker);
  if (found.error) {
    violations.push({ path: rel, line: found.line, message: `${where} ${found.error}` });
    return;
  }
  let value;
  try {
    value = JSON.parse(found.text);
  } catch (err) {
    const reason = String(err?.message ?? err).split('\n')[0];
    violations.push({ path: rel, line: found.line, message: `${where} example does not parse as JSON: ${reason}` });
    return;
  }
  if (!isObject(value)) {
    violations.push({ path: rel, line: found.line, message: `${where} example is ${jsonKind(value)}, expected a JSON object (${typeName})` });
    return;
  }
  // JSON.parse keeps only the last of a repeated key, so repetitions are read from the text.
  const repeats = [];
  const sites = jsonSites(found.text, repeats);
  for (const { path: at, key, site, first } of repeats) {
    violations.push({
      path: rel,
      line: found.lineOf(site),
      message:
        `${where} example ${at}: key ${JSON.stringify(key)} repeated (first at line ${found.lineOf(first)}); ` +
        'proto3 JSON allows each field once',
    });
  }
  const from = typeFiles.get(rel);
  const resolved = from ? resolveProtoType(typeName, from, typeFiles) : null;
  if (resolved?.kind !== 'message') return;
  const problems = [];
  messageProblems(value, resolved.message, resolved.file, typeFiles, '$', problems);
  for (const { path: at, problem } of problems) {
    violations.push({ path: rel, line: found.lineOf(sites.get(at) ?? 0), message: `${where} example ${at} ${problem}` });
  }
}

/**
 * The Errors block of an RPC's leading comment, or null when the comment has no
 * "Errors:" line: the rest of that line and the comment lines after it, up to a blank
 * comment line, the next marker or the end of the comment. Returns { line, entries,
 * prose }: line is the marker's, entries holds each `CODE - description` line as
 * { code, description, line }, and prose the trimmed text of every other line.
 */
function rpcErrorsBlock(rpc) {
  const lines = rpc.commentLines;
  const at = lines.findIndex((c) => c.text.includes(RPC_ERRORS_MARKER));
  if (at < 0) return null;
  const marker = lines[at];
  const body = [{ line: marker.line, text: marker.text.slice(marker.text.indexOf(RPC_ERRORS_MARKER) + RPC_ERRORS_MARKER.length) }];
  for (const c of lines.slice(at + 1)) {
    if (c.text.trim() === '' || RPC_COMMENT_MARKERS.some((m) => c.text.includes(m))) break;
    body.push(c);
  }
  const entries = [];
  const prose = [];
  for (const c of body) {
    if (c.text.trim() === '') continue;
    const m = ERROR_ENTRY_RE.exec(c.text);
    if (m) entries.push({ code: m[1], description: m[2].trim(), line: c.line });
    else prose.push(c.text.trim());
  }
  return { line: marker.line, entries, prose };
}

/**
 * One whole sentence declaring the packing affirmatively for every error status; group 1
 * is the packed type as written and group 2 the status type. The leading lookahead
 * refuses any sentence that carries a denial or restriction token.
 */
const PACKING_DECLARATION_RE = new RegExp(
  [
    String.raw`^(?!.*(?:\b(?:no|not|never|none|nothing|neither|nor|without|cannot|except|unless|only|some)\b|n['’]t\b))`,
    String.raw`(?:every|each|all)\s+(?:non-ok\s+status(?:es)?|error\s+status(?:es)?|errors?)\s+`,
    String.raw`(?:packs?|(?:is|are)\s+packed)\s+(?:as\s+)?(\.?[A-Za-z_][\w.]*)\s+`,
    String.raw`in(?:to)?\s+(?:the\s+)?([A-Za-z_][\w.]*)\s+details\.?$`,
  ].join(''),
  'i',
);

/**
 * True when one sentence of `text` declares, affirmatively and for every error status,
 * that cardservicing.common.v1.ErrorObject is packed into google.rpc.Status details.
 * The sentence opens with its subject, Every, Each or All followed by non-OK status(es),
 * error status(es) or error(s); its verb is pack, packs, is packed or are packed, an
 * "as" allowed after it; then come the type, a leading "." allowed, "in" or "into",
 * an optional "the", google.rpc.Status and "details", where the sentence ends. A
 * sentence carrying no, not, never, none, nothing, neither, nor, without, cannot, n't,
 * except, unless, only or some never counts, so a denial such as "No non-OK status ever
 * packs …" or a restriction such as "Some non-OK statuses pack …" fails. Words match
 * in any case, the two type names exactly. Sentences end at a full stop before
 * whitespace, so the dots of a qualified name stay inside their sentence.
 */
function statesPacking(text) {
  return text.split(/(?<=\.)\s+/).some((sentence) => {
    const m = PACKING_DECLARATION_RE.exec(sentence.trim());
    return m !== null && m[1].replace(/^\./, '') === ERROR_OBJECT_TYPE && m[2] === 'google.rpc.Status';
  });
}

/**
 * Checks an RPC's Errors block: at least one `CODE - description` entry, each code a
 * standard ErrorObject code listed once with a description, and the commitment that
 * cardservicing.common.v1.ErrorObject is packed into google.rpc.Status details.
 */
function checkRpcErrors(rel, rpc, violations) {
  const block = rpcErrorsBlock(rpc);
  if (!block) return;
  const where = `rpc ${rpc.name}: "${RPC_ERRORS_MARKER}"`;
  if (block.entries.length === 0) {
    violations.push({ path: rel, line: block.line, message: `${where} lists no "CODE - description" entry` });
  }
  const first = new Map();
  for (const entry of block.entries) {
    if (!ERROR_CODE_STATUS.has(entry.code)) {
      violations.push({
        path: rel,
        line: entry.line,
        message: `${where} code ${entry.code} is not a standard ErrorObject code (${[...ERROR_CODE_STATUS.keys()].join(', ')})`,
      });
    }
    if (first.has(entry.code)) {
      violations.push({ path: rel, line: entry.line, message: `${where} lists ${entry.code} again (first at line ${first.get(entry.code)})` });
    } else {
      first.set(entry.code, entry.line);
    }
    if (entry.description === '') {
      violations.push({ path: rel, line: entry.line, message: `${where} entry ${entry.code} has no description` });
    }
  }
  if (!statesPacking(block.prose.join(' '))) {
    violations.push({
      path: rel,
      line: block.line,
      message: `${where} block does not state that ${ERROR_OBJECT_TYPE} is packed into google.rpc.Status details`,
    });
  }
}

/**
 * Checks a service proto's error carrier: message ErrorDetail, whose field `error` is
 * singular, numbered 1 and of type cardservicing.common.v1.ErrorObject, the message
 * every RPC packs into google.rpc.Status details. A missing message is reported at the
 * package statement, a missing field at the message and a wrong field at the field.
 */
function checkErrorCarrier(rel, proto, typeFiles, violations) {
  const carrier = proto.messages.get(ERROR_CARRIER);
  if (!carrier) {
    violations.push({
      path: rel,
      line: proto.package?.line ?? 1,
      message: `missing message ${ERROR_CARRIER} with field "${ERROR_OBJECT_TYPE} error = 1", the standard error carrier`,
    });
    return;
  }
  const field = carrier.fields.find((f) => f.name === 'error');
  if (!field) {
    violations.push({ path: rel, line: carrier.line, message: `${ERROR_CARRIER} lacks field "${ERROR_OBJECT_TYPE} error = 1"` });
    return;
  }
  const where = `${ERROR_CARRIER}.error`;
  if (field.label === 'repeated' || MAP_TYPE_RE.test(field.type)) {
    violations.push({ path: rel, line: field.line, message: `${where} is ${field.label === 'repeated' ? 'repeated' : 'a map'}, expected a singular field` });
  }
  if (field.number !== 1) {
    violations.push({ path: rel, line: field.line, message: `${where} has number ${field.number}, expected 1` });
  }
  // Resolved when common.proto is parsed, so a relative name counts; else compared as written.
  const from = typeFiles.get(rel);
  const resolved = from ? resolveProtoType(field.type, from, typeFiles) : null;
  const errorPackage = ERROR_OBJECT_TYPE.slice(0, ERROR_OBJECT_TYPE.lastIndexOf('.'));
  const errorName = ERROR_OBJECT_TYPE.slice(errorPackage.length + 1);
  const isErrorObject = resolved
    ? resolved.kind === 'message' && resolved.file.pkg === errorPackage && resolved.message.name === errorName
    : field.type.replace(/^\./, '') === ERROR_OBJECT_TYPE;
  if (!isErrorObject) {
    violations.push({ path: rel, line: field.line, message: `${where} has type ${field.type}, expected ${ERROR_OBJECT_TYPE}` });
  }
}

/**
 * Holds each baseline RPC's Errors codes to the error statuses of its REST twin under
 * the ErrorObject code-to-status mapping: a twin status that maps to a code needs that
 * code under the RPC's "Errors:", reported at that line, and every standard code
 * listed there needs its status among the twin's responses, reported at `responses`.
 * Statuses with no code (429, 4XX, default) are not compared. A missing RPC, Errors
 * block or twin operation is reported by the other checks and skipped here.
 */
function checkRpcErrorStatuses(protos, docs, violations) {
  for (const iface of INTERFACES) {
    const service = protos.get(iface.proto)?.services.get(iface.service);
    const doc = docs.get(iface.openapi);
    if (!service || !doc) continue;
    for (const op of iface.operations) {
      const rpc = service.rpcs.find((r) => r.name === op.rpc);
      const block = rpc ? rpcErrorsBlock(rpc) : null;
      const twin = `${op.method.toUpperCase()} ${op.path}`;
      // A broken Path Item reference is reported once, by operationsOf, so it is not collected here.
      const item = hasOwn(doc.paths, op.path) ? deref(doc, doc.paths[op.path], iface.openapi, twin, []) : undefined;
      const operation = isObject(item) ? item[op.method] : undefined;
      if (!block || !isObject(operation)) continue;
      const statuses = isObject(operation.responses) ? Object.keys(operation.responses) : [];
      const codes = new Set(block.entries.map((e) => e.code));
      for (const status of statuses) {
        const code = ERROR_CODE_BY_STATUS.get(status);
        if (code && !codes.has(code)) {
          violations.push({
            path: iface.proto,
            line: block.line,
            message: `rpc ${op.rpc}: "${RPC_ERRORS_MARKER}" lacks ${code}, the code of the ${status} response of REST twin ${twin} in ${iface.openapi}`,
          });
        }
      }
      for (const code of codes) {
        const status = ERROR_CODE_STATUS.get(code);
        if (status && !statuses.includes(status)) {
          violations.push({
            path: iface.openapi,
            line: yamlLine(operation, 'responses'),
            message: `${twin} (${op.rpc}): responses lack ${status}, the status of ${code}, which rpc ${op.rpc} lists under "${RPC_ERRORS_MARKER}" in ${iface.proto}`,
          });
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Proto content
// ---------------------------------------------------------------------------

/**
 * Checks the common import, every RPC's comment blocks (examples and Errors block), the
 * ErrorDetail error carrier and the baseline RPCs of each service proto, and returns
 * the camelCase field names of the shared ErrorObject and FieldViolation messages
 * (null for a message that cannot be read). `protos` maps each existing proto's
 * root-relative path to its parseProto model, and `typeFiles` is
 * protoTypeFiles(protos). main leaves out a proto reached through a symbolic link, as
 * a missing one is, and reports the link; a left-out common.proto leaves both shared
 * field lists null. A missing declaration is reported at the package statement, where
 * imports and declarations follow, or at line 1 when the file has none.
 */
function checkProtos(protos, typeFiles, violations) {
  for (const iface of INTERFACES) {
    const proto = protos.get(iface.proto);
    if (!proto) continue;
    const head = proto.package?.line ?? 1;
    if (!proto.imports.some((i) => i.path === COMMON_IMPORT)) {
      violations.push({ path: iface.proto, line: head, message: `missing import "${COMMON_IMPORT}"` });
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
          } else if (hasOwn(RPC_EXAMPLE_TYPES, marker)) {
            checkRpcExample(iface.proto, rpc, marker, rpc[RPC_EXAMPLE_TYPES[marker]], typeFiles, violations);
          } else if (marker === RPC_ERRORS_MARKER) {
            checkRpcErrors(iface.proto, rpc, violations);
          }
        }
      }
    }
    checkErrorCarrier(iface.proto, proto, typeFiles, violations);
    const service = proto.services.get(iface.service);
    if (!service) {
      violations.push({ path: iface.proto, line: head, message: `missing baseline service ${iface.service}` });
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
  const common = protos.get(COMMON_PROTO);
  if (!common) return shared;
  for (const [key, name] of [
    ['errorObject', 'ErrorObject'],
    ['fieldViolation', 'FieldViolation'],
  ]) {
    const message = common.messages.get(name);
    if (!message || message.fields.length === 0) {
      violations.push({
        path: COMMON_PROTO,
        line: message?.line ?? common.package?.line ?? 1,
        message: `missing message ${name} with fields`,
      });
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
 * The first component of a root-relative path that is a symbolic link, checked from the
 * repository root down to the path itself, as a root-relative path; null when no
 * component is a link or the path is missing (a component that does not exist, or is
 * not a directory, ends the walk). The shared linkedComponent classifies each component
 * relative to its held parent folder and follows no link, so a link's target is never
 * statted and a component swapped for a link mid-walk cannot redirect the walk.
 */
function symlinkedComponent(rel) {
  return linkedComponent(rel);
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
    // The root is reached through no link, so pathKind has none to follow.
    if (pathKind(root) !== 'dir') continue;
    // listEntries never follows a link and opens each folder relative to its held parent;
    // its entries come in path order, code unit by code unit.
    const found = [];
    for (const { path: rel, type } of listEntries(root)) {
      if (type === 'symlink') found.push({ rel, link: true });
      else if (type === 'file' && path.extname(rel) === '.proto') found.push({ rel, link: false });
    }
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
      violations.push({ path: rel, line: yamlLine(current, '$ref'), message: `${where}: $ref "${current.$ref}" is circular` });
      return undefined;
    }
    seen.add(current.$ref);
    const { value, error } = resolvePointer(doc, current.$ref);
    if (error) {
      violations.push({ path: rel, line: yamlLine(current, '$ref'), message: `${where}: ${error}` });
      return undefined;
    }
    current = value;
  }
  return current;
}

/** Key of BASELINE_RESPONSE_STATUSES: an operation's OpenAPI document, method and path. */
const baselineResponseKey = (rel, method, p) => `${rel} ${method.toUpperCase()} ${p}`;

/**
 * The responses each of the fourteen baseline operations must document: its 200 and
 * every status of the baseline status mapping the operation can return, as the
 * delivered documents define them. Each member must be present under its exact key, so
 * '201' or '2XX' does not stand in for '200'. Statuses beyond the set are allowed,
 * because the baseline is only ever extended additively.
 */
const BASELINE_RESPONSE_STATUSES = new Map(
  [
    [
      'card-servicing-api.yaml',
      ['200', '400', '401', '403', '404', '503', '504'],
      [
        ['get', '/v1/accounts/{accountId}/balance-summary'],
        ['get', '/v1/accounts/{accountId}/category-balances'],
        ['get', '/v1/accounts/{accountId}/transactions'],
        ['get', '/v1/accounts/{accountId}/transactions/{transactionId}'],
      ],
    ],
    [
      'card-data-service.yaml',
      ['200', '400', '401', '404', '503', '504'],
      [
        ['get', '/v1/card-data/accounts/{accountId}'],
        ['get', '/v1/card-data/accounts/{accountId}/card-cross-references'],
        ['get', '/v1/card-data/accounts/{accountId}/category-balances'],
      ],
    ],
    [
      'transaction-activity-service.yaml',
      ['200', '400', '401', '404', '503', '504'],
      [
        ['get', '/v1/transaction-activity/accounts/{accountId}/transactions'],
        ['get', '/v1/transaction-activity/transactions/{transactionId}'],
      ],
    ],
    ['config-service.yaml', ['200', '400', '401', '503', '504'], [['get', '/v1/config']]],
    [
      'reference-data-service.yaml',
      ['200', '401', '503', '504'],
      [
        ['get', '/v1/reference-data/transaction-types'],
        ['get', '/v1/reference-data/transaction-categories'],
      ],
    ],
    [
      'auth-service.yaml',
      ['200', '400', '401', '503', '504'],
      [
        ['post', '/v1/auth/token-validations'],
        ['get', '/v1/auth/subjects/{subject}/accounts/{accountId}/access'],
      ],
    ],
  ].flatMap(([file, statuses, operations]) =>
    operations.map(([method, p]) => [baselineResponseKey(`${OPENAPI_DIR}/${file}`, method, p), statuses]),
  ),
);

/** Schema every error example is validated against. */
const ERROR_SCHEMA = { $ref: ERROR_SCHEMA_REF };

/**
 * The sample payloads a media type entry carries, as { label, value, line }: its own
 * `example`, and each `examples` entry that resolves, through local references, to an
 * Example Object with its own `value`. Any other entry is reported at its line, because
 * a summary or description alone is a label, not a payload. `at` prefixes each message,
 * as "<operation>: response <status>".
 */
function mediaExampleValues(doc, rel, at, media, violations) {
  const out = [];
  if (hasOwn(media, 'example')) out.push({ label: 'example', value: media.example, line: yamlLine(media, 'example') });
  if (media.examples === undefined) return out;
  if (!isObject(media.examples)) {
    violations.push({
      path: rel,
      line: yamlLine(media, 'examples'),
      message: `${at} examples is not a map of Example Objects`,
    });
    return out;
  }
  for (const [name, raw] of Object.entries(media.examples)) {
    const label = `example "${name}"`;
    const line = yamlLine(media.examples, name);
    const example = deref(doc, raw, rel, `${at} ${label}`, violations);
    if (example === undefined) continue; // a broken or circular reference, reported by deref
    if (hasOwn(example, 'value')) {
      out.push({ label, value: example.value, line });
    } else if (hasOwn(example, 'externalValue')) {
      // externalValue names a payload outside the contract files. This check runs offline
      // and reads only those files, so it can neither fetch nor validate that payload.
      violations.push({
        path: rel,
        line,
        message: `${at} ${label} has only externalValue, which an offline check can neither fetch nor validate`,
      });
    } else if (isObject(example)) {
      violations.push({
        path: rel,
        line,
        message: `${at} ${label} has no value; a summary or description is not an example`,
      });
    } else {
      violations.push({ path: rel, line, message: `${at} ${label} is not an Example Object` });
    }
  }
  return out;
}

/**
 * Sample payloads a resolved media schema carries itself, as { label, value, line }: its
 * `example` and each item of its `examples` array. A success response with no media-level
 * example may take its payload from here.
 */
function schemaExampleValues(schema) {
  const out = [];
  if (hasOwn(schema, 'example')) {
    out.push({ label: 'schema example', value: schema.example, line: yamlLine(schema, 'example') });
  }
  if (Array.isArray(schema?.examples)) {
    schema.examples.forEach((value, i) => {
      const line = yamlLine(schema.examples, i) ?? yamlLine(schema, 'examples');
      out.push({ label: `schema examples[${i}]`, value, line });
    });
  }
  return out;
}

/** JSON-path form of a child of `at`: `.name` for an identifier, `["…"]` for any other key, `[i]` for an index. */
const childPath = (at, key) =>
  typeof key === 'number'
    ? `${at}[${key}]`
    : /^[A-Za-z_$][\w$]*$/.test(key)
      ? `${at}.${key}`
      : `${at}[${JSON.stringify(key)}]`;

/** JSON Schema type name of a YAML-loaded value; a value JSON cannot carry is named by what it is. */
function jsonTypeOf(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (value instanceof Date) return 'timestamp';
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return 'non-finite number';
    return Number.isInteger(value) ? 'integer' : 'number';
  }
  return typeof value;
}

function matchesType(value, type) {
  const actual = jsonTypeOf(value);
  if (type === 'number') return actual === 'number' || actual === 'integer';
  return actual === type;
}

/** A value for a message: its JSON text, cut at 60 characters. */
function shown(value) {
  const text = JSON.stringify(value) ?? String(value);
  return text.length > 60 ? `${text.slice(0, 57)}...` : text;
}

const compiledPatterns = new Map();

/** A JSON Schema `pattern` compiled as an ECMA-262 Unicode regular expression; null when it does not compile. */
function compilePattern(pattern) {
  if (!compiledPatterns.has(pattern)) {
    let re = null;
    try {
      re = new RegExp(pattern, 'u');
    } catch {
      // Reported by the caller as an invalid pattern.
    }
    compiledPatterns.set(pattern, re);
  }
  return compiledPatterns.get(pattern);
}

/** Longest one pattern match may run before it is stopped. */
const PATTERN_TIMEOUT_MS = 1000;
/** Longest all pattern matches of one run may take together: ten stopped matches spend it. */
const PATTERN_RUN_BUDGET_MS = 10 * PATTERN_TIMEOUT_MS;

/*
 * A contract's pattern is matched inside a vm context, because only there can a match
 * be stopped: a valid pattern such as ^(a+)+$ backtracks for longer than any run can
 * wait on a short subject, and RegExp has no time limit of its own. One context and one
 * compiled script serve every match.
 */
const patternContext = vm.createContext({ pattern: null, subject: '' });
const patternScript = new vm.Script('pattern.test(subject)');
/** Sources of the patterns a match of which was stopped; none of them is run again. */
const stoppedPatterns = new Set();
/** Milliseconds this run has spent matching patterns. */
let patternRunMs = 0;

/**
 * Matches `subject` against `re`, compiled from `source`, and stops the match after
 * PATTERN_TIMEOUT_MS, or sooner when the run's PATTERN_RUN_BUDGET_MS runs out. Returns
 * { matched } or, when no answer was reached, { stopped } naming why: 'timeout' (this
 * match was stopped, and the pattern is never run again), 'earlier' (a match of this
 * pattern was stopped before, so it was not run) or 'budget' (the run's matching time
 * is spent).
 */
function matchPattern(re, source, subject) {
  if (stoppedPatterns.has(source)) return { stopped: 'earlier' };
  const remaining = PATTERN_RUN_BUDGET_MS - patternRunMs;
  if (remaining <= 0) return { stopped: 'budget' };
  const timeout = Math.max(1, Math.min(PATTERN_TIMEOUT_MS, Math.floor(remaining)));
  patternContext.pattern = re;
  patternContext.subject = subject;
  const start = performance.now();
  try {
    return { matched: patternScript.runInContext(patternContext, { timeout }) === true };
  } catch (err) {
    if (err?.code !== 'ERR_SCRIPT_EXECUTION_TIMEOUT') throw err;
    // A shortened limit ran out because the run's budget did, not because of this pattern.
    if (timeout < PATTERN_TIMEOUT_MS) return { stopped: 'budget' };
    stoppedPatterns.add(source);
    return { stopped: 'timeout' };
  } finally {
    patternRunMs += performance.now() - start;
    patternContext.pattern = null;
    patternContext.subject = '';
  }
}

/**
 * The violation text for a match matchPattern stopped: `label` is "pattern" or
 * "patternProperties pattern", `what` names the subject, `stopped` is matchPattern's reason.
 */
function patternStopMessage(label, source, what, stopped) {
  const limit = `${PATTERN_TIMEOUT_MS / 1000} s`;
  if (stopped === 'timeout') return `${label} "${source}" did not finish matching ${what} within ${limit}; it is not run again`;
  if (stopped === 'earlier') {
    return `${label} "${source}" was not matched against ${what}: it did not finish matching an earlier value within ${limit}`;
  }
  const budget = `${PATTERN_RUN_BUDGET_MS / 1000} s`;
  return `${label} "${source}" was not matched against ${what}: pattern matching has spent its ${budget} for this run`;
}

/**
 * Most steps one sample's schema evaluation may take. A step is one visit of
 * schemaMismatchWithin, one element a visit iterates (a type name, an enum member, a
 * required name, an object key once and again per patternProperties pattern, or a
 * uniqueItems item), or SCHEMA_STEP_CHARS characters of text a visit reads or builds
 * (a string value, an object key, a $ref, canonical JSON for enum, const and
 * uniqueItems). The contracts' largest sample takes a small fraction of it.
 */
const SCHEMA_STEP_BUDGET = 20000;
const SCHEMA_STEP_CHARS = 1024;

/** Takes `n` steps from `budget`; true once the budget is overspent, so the caller stops. */
function spendSteps(budget, n) {
  budget.steps -= n;
  return budget.steps < 0;
}

/** Steps for reading or building `text`: one per SCHEMA_STEP_CHARS characters, none for anything but a string. */
const textSteps = (text) => (typeof text === 'string' ? Math.floor(text.length / SCHEMA_STEP_CHARS) : 0);

/**
 * First place where a JSON value breaks a JSON Schema, as { at, message } with `at` a
 * JSON path from `$`; null when the value conforms. Local `$ref`s resolve against the
 * document. The validating keywords are those the contracts use, and their JSON Schema
 * 2020-12 neighbours: type (a name or a list, "null" included), enum, const, pattern
 * (Unicode), minLength and maxLength (in code points), minimum, maximum,
 * exclusiveMinimum, exclusiveMaximum, multipleOf, minItems, maxItems, uniqueItems,
 * items, prefixItems, properties, required, additionalProperties (boolean or schema),
 * patternProperties, minProperties, maxProperties, allOf, anyOf, oneOf, not and $ref.
 * `format` and every other keyword are annotations here and never fail a value.
 *
 * `refs` holds the references already followed for this same value, so a reference
 * cycle that never descends into the value is reported instead of recursing forever.
 *
 * One sample's evaluation may take SCHEMA_STEP_BUDGET steps (schemaMismatchWithin
 * counts them). A sample that needs more, such as one whose `$ref`s fan out into a
 * graph far larger than the document, is reported as stopped at `$`, never judged
 * conforming. A pattern match stopped for time (matchPattern) leaves the sample
 * undecided too, and the first such stop is its result.
 */
function schemaMismatch(doc, schema, value) {
  const budget = { steps: SCHEMA_STEP_BUDGET, undecided: null };
  const found = schemaMismatchWithin(doc, schema, value, '$', new Set(), budget);
  // An anyOf, oneOf or not may have taken a stopped visit or match for an ordinary
  // mismatch, so a stop decides the result, whatever was returned.
  if (budget.steps < 0) {
    return {
      at: '$',
      message: `schema evaluation stopped after ${SCHEMA_STEP_BUDGET} steps; the value was not judged against its schema`,
    };
  }
  return budget.undecided ?? found;
}

/**
 * schemaMismatch at JSON path `at`, `refs` being the references followed for this
 * value, taking steps from the sample's `budget` ({ steps, undecided }). Once the
 * budget is overspent, every visit fails at once and an enclosing anyOf, oneOf or not
 * stops too, so the remaining work is bounded; schemaMismatch then reports the stop.
 * A stopped pattern match is recorded as `budget.undecided`, first one kept.
 */
function schemaMismatchWithin(doc, schema, value, at, refs, budget) {
  const fail = (message) => ({ at, message });
  const stopped = () => fail(`schema evaluation stopped after ${SCHEMA_STEP_BUDGET} steps`);
  const undecided = (message) => {
    const found = fail(message);
    budget.undecided ??= found;
    return found;
  };
  if (spendSteps(budget, 1)) return stopped();
  if (schema === undefined || schema === true) return null;
  if (schema === false) return fail('no value is allowed here (schema false)');
  if (!isObject(schema)) return null;

  if (typeof schema.$ref === 'string') {
    if (spendSteps(budget, refs.size + textSteps(schema.$ref))) return stopped();
    if (refs.has(schema.$ref)) return fail(`$ref "${schema.$ref}" is circular`);
    const { value: target, error } = resolvePointer(doc, schema.$ref);
    if (error) return fail(error);
    const found = schemaMismatchWithin(doc, target, value, at, new Set([...refs, schema.$ref]), budget);
    if (found) return found;
  }

  if (schema.type !== undefined) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (spendSteps(budget, types.length)) return stopped();
    if (!types.some((t) => matchesType(value, t))) {
      return fail(`is ${jsonTypeOf(value)}, expected ${types.join(' or ')}`);
    }
  }
  if (Array.isArray(schema.enum)) {
    // The value's canonical JSON is built once, not once per member.
    const valueJson = canonicalJson(value);
    if (spendSteps(budget, schema.enum.length + textSteps(valueJson))) return stopped();
    let member = false;
    for (const e of schema.enum) {
      const memberJson = canonicalJson(e);
      if (spendSteps(budget, textSteps(memberJson))) return stopped();
      if (memberJson === valueJson) {
        member = true;
        break;
      }
    }
    if (!member) return fail(`${shown(value)} is not one of ${shown(schema.enum)}`);
  }
  if (hasOwn(schema, 'const')) {
    const constJson = canonicalJson(schema.const);
    const valueJson = canonicalJson(value);
    if (spendSteps(budget, textSteps(constJson) + textSteps(valueJson))) return stopped();
    if (constJson !== valueJson) return fail(`${shown(value)} is not the const ${shown(schema.const)}`);
  }

  if (typeof value === 'string') {
    if (spendSteps(budget, textSteps(value))) return stopped();
    const length = [...value].length;
    if (typeof schema.minLength === 'number' && length < schema.minLength) {
      return fail(`length ${length} is below minLength ${schema.minLength}`);
    }
    if (typeof schema.maxLength === 'number' && length > schema.maxLength) {
      return fail(`length ${length} is above maxLength ${schema.maxLength}`);
    }
    if (typeof schema.pattern === 'string') {
      const re = compilePattern(schema.pattern);
      if (re === null) return fail(`pattern "${schema.pattern}" is not a valid regular expression`);
      const match = matchPattern(re, schema.pattern, value);
      if (match.stopped) return undecided(patternStopMessage('pattern', schema.pattern, shown(value), match.stopped));
      if (!match.matched) return fail(`${shown(value)} does not match pattern "${schema.pattern}"`);
    }
  }

  if (typeof value === 'number') {
    if (typeof schema.minimum === 'number' && value < schema.minimum) {
      return fail(`${value} is below minimum ${schema.minimum}`);
    }
    if (typeof schema.maximum === 'number' && value > schema.maximum) {
      return fail(`${value} is above maximum ${schema.maximum}`);
    }
    if (typeof schema.exclusiveMinimum === 'number' && value <= schema.exclusiveMinimum) {
      return fail(`${value} is not above exclusiveMinimum ${schema.exclusiveMinimum}`);
    }
    if (typeof schema.exclusiveMaximum === 'number' && value >= schema.exclusiveMaximum) {
      return fail(`${value} is not below exclusiveMaximum ${schema.exclusiveMaximum}`);
    }
    if (typeof schema.multipleOf === 'number' && schema.multipleOf > 0) {
      const quotient = value / schema.multipleOf;
      if (Math.abs(quotient - Math.round(quotient)) > 1e-9 * Math.max(1, Math.abs(quotient))) {
        return fail(`${value} is not a multiple of ${schema.multipleOf}`);
      }
    }
  }

  if (Array.isArray(value)) {
    if (typeof schema.minItems === 'number' && value.length < schema.minItems) {
      return fail(`item count ${value.length} is below minItems ${schema.minItems}`);
    }
    if (typeof schema.maxItems === 'number' && value.length > schema.maxItems) {
      return fail(`item count ${value.length} is above maxItems ${schema.maxItems}`);
    }
    if (schema.uniqueItems === true) {
      if (spendSteps(budget, value.length)) return stopped();
      const seen = new Map();
      for (let i = 0; i < value.length; i++) {
        const key = canonicalJson(value[i]);
        if (spendSteps(budget, textSteps(key))) return stopped();
        if (seen.has(key)) return fail(`items [${seen.get(key)}] and [${i}] are equal, but uniqueItems is true`);
        seen.set(key, i);
      }
    }
    const prefix = Array.isArray(schema.prefixItems) ? schema.prefixItems : [];
    for (let i = 0; i < value.length; i++) {
      const item = i < prefix.length ? prefix[i] : schema.items;
      const found = schemaMismatchWithin(doc, item, value[i], childPath(at, i), new Set(), budget);
      if (found) return found;
    }
  }

  if (isObject(value) && !(value instanceof Date)) {
    const keys = Object.keys(value);
    const required = Array.isArray(schema.required) ? schema.required : [];
    const patterns = isObject(schema.patternProperties) ? Object.entries(schema.patternProperties) : [];
    if (spendSteps(budget, required.length + keys.length * (1 + patterns.length))) return stopped();
    if (typeof schema.minProperties === 'number' && keys.length < schema.minProperties) {
      return fail(`property count ${keys.length} is below minProperties ${schema.minProperties}`);
    }
    if (typeof schema.maxProperties === 'number' && keys.length > schema.maxProperties) {
      return fail(`property count ${keys.length} is above maxProperties ${schema.maxProperties}`);
    }
    for (const name of required) {
      if (!hasOwn(value, name)) return fail(`required property "${name}" is missing`);
    }
    const properties = isObject(schema.properties) ? schema.properties : {};
    for (const key of keys) {
      if (spendSteps(budget, textSteps(key))) return stopped();
      const keyAt = childPath(at, key);
      let declared = false;
      if (hasOwn(properties, key)) {
        declared = true;
        const found = schemaMismatchWithin(doc, properties[key], value[key], keyAt, new Set(), budget);
        if (found) return found;
      }
      for (const [pattern, sub] of patterns) {
        const re = compilePattern(pattern);
        if (re === null) return fail(`patternProperties pattern "${pattern}" is not a valid regular expression`);
        const match = matchPattern(re, pattern, key);
        if (match.stopped) {
          return undecided(patternStopMessage('patternProperties pattern', pattern, `key ${shown(key)}`, match.stopped));
        }
        if (!match.matched) continue;
        declared = true;
        const found = schemaMismatchWithin(doc, sub, value[key], keyAt, new Set(), budget);
        if (found) return found;
      }
      if (!declared && schema.additionalProperties !== undefined) {
        if (schema.additionalProperties === false) {
          return { at: keyAt, message: 'property is not allowed (additionalProperties is false)' };
        }
        const found = schemaMismatchWithin(doc, schema.additionalProperties, value[key], keyAt, new Set(), budget);
        if (found) return found;
      }
    }
  }

  for (const sub of Array.isArray(schema.allOf) ? schema.allOf : []) {
    const found = schemaMismatchWithin(doc, sub, value, at, refs, budget);
    if (found) return found;
  }
  // A subschema stopped by the budget reads as a mismatch here, so each of these stops
  // as soon as the budget is spent instead of judging the value by it.
  if (Array.isArray(schema.anyOf)) {
    const matchedAny = schema.anyOf.some((sub) => schemaMismatchWithin(doc, sub, value, at, refs, budget) === null);
    if (budget.steps < 0) return stopped();
    if (!matchedAny) return fail('matches none of the anyOf schemas');
  }
  if (Array.isArray(schema.oneOf)) {
    let matched = 0;
    for (const sub of schema.oneOf) {
      if (schemaMismatchWithin(doc, sub, value, at, refs, budget) === null) matched++;
      if (budget.steps < 0) return stopped();
    }
    if (matched !== 1) return fail(`matches ${matched} of the oneOf schemas, expected exactly 1`);
  }
  if (schema.not !== undefined) {
    const matchedNot = schemaMismatchWithin(doc, schema.not, value, at, refs, budget) === null;
    if (budget.steps < 0) return stopped();
    if (matchedNot) return fail('matches the schema under not');
  }
  return null;
}

/**
 * Reports, at the sample's line, the first mismatch of each sample payload that does
 * not conform to `schema`; `at` prefixes each message, as "<operation>: response <status>".
 */
function reportExampleMismatches(doc, rel, at, schema, samples, violations) {
  for (const { label, value, line } of samples) {
    const mismatch = schemaMismatch(doc, schema, value);
    if (mismatch) {
      violations.push({
        path: rel,
        line,
        message: `${at} ${label} does not match its schema at ${mismatch.at}: ${mismatch.message}`,
      });
    }
  }
}

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

/**
 * Checks one operation: a description; a 200 under that exact key, whose JSON content
 * carries an example; for a baseline operation, every other status of
 * BASELINE_RESPONSE_STATUSES too, and for any other operation at least one error
 * response. Every 2xx JSON content has a schema, and every error response (4xx, 5xx,
 * a 4XX/5XX range or `default`) has JSON content with the ErrorObject schema and at
 * least one example. Every example must carry a value that conforms to its schema, and
 * a mapped status's examples carry that status's code.
 */
function checkOperation(doc, rel, { method, path: p, op }, violations) {
  const id = typeof op.operationId === 'string' ? ` (${op.operationId})` : '';
  const where = `${method.toUpperCase()} ${p}${id}`;
  if (typeof op.description !== 'string' || op.description.trim() === '') {
    violations.push({ path: rel, line: yamlLine(op, 'description'), message: `${where}: description is missing or empty` });
  }
  const responsesLine = yamlLine(op, 'responses');
  const required = BASELINE_RESPONSE_STATUSES.get(baselineResponseKey(rel, method, p));
  for (const status of required ?? ['200']) {
    if (!hasOwn(op.responses, status)) {
      const kind = required === undefined ? 'response' : 'baseline response';
      violations.push({ path: rel, line: responsesLine, message: `${where}: ${kind} ${status} is missing` });
    }
  }
  const errorSchemaResolves = resolvePointer(doc, ERROR_SCHEMA_REF).value !== undefined;
  const responses = isObject(op.responses) ? Object.entries(op.responses) : [];
  let errors = 0;
  for (const [status, raw] of responses) {
    const isSuccess = /^2(?:\d\d|XX)$/i.test(status);
    // `default` answers every status the operation does not list, so it is an error response.
    const isError = /^[45](?:\d\d|XX)$/i.test(status) || status === 'default';
    if (!isSuccess && !isError) continue;
    const at = `${where}: response ${status}`;
    const response = deref(doc, raw, rel, `${where} response ${status}`, violations);
    if (isError) errors++;
    if (response === undefined) continue;
    const media = isObject(response?.content) ? response.content[JSON_MEDIA] : undefined;
    const is200 = status === '200';
    if (!isObject(media)) {
      if (isError || is200) {
        violations.push({
          path: rel,
          line: yamlLine(response, 'content') ?? yamlLine(op.responses, status),
          message: `${at} has no ${JSON_MEDIA} content`,
        });
      }
      continue;
    }
    const examples = mediaExampleValues(doc, rel, at, media, violations);
    if (isSuccess) {
      let schema;
      if (media.schema === undefined || media.schema === null) {
        violations.push({
          path: rel,
          line: yamlLine(media, 'schema'),
          message: `${at} ${JSON_MEDIA} content has no schema`,
        });
      } else {
        schema = deref(doc, media.schema, rel, `${where} response ${status} schema`, violations);
      }
      const samples = examples.length === 0 && schema !== undefined ? schemaExampleValues(schema) : examples;
      if (samples.length === 0 && is200) {
        violations.push({
          path: rel,
          line: yamlLine(media, 'examples'),
          message: `${at} has no ${JSON_MEDIA} example`,
        });
      }
      if (schema !== undefined) reportExampleMismatches(doc, rel, at, media.schema, samples, violations);
      continue;
    }
    if (media.schema?.$ref !== ERROR_SCHEMA_REF) {
      violations.push({
        path: rel,
        line: yamlLine(media, 'schema'),
        message: `${at} schema is not $ref ${ERROR_SCHEMA_REF}`,
      });
    } else {
      deref(doc, media.schema, rel, `${where} response ${status} schema`, violations);
      // OpenAPI 3.1 applies a keyword beside `$ref` with the referenced schema, so one would change the error object.
      for (const keyword of CONTRACT_KEYWORDS.filter((k) => hasOwn(media.schema, k))) {
        violations.push({
          path: rel,
          line: yamlLine(media.schema, keyword),
          message: `${at} schema sets ${keyword} beside $ref ${ERROR_SCHEMA_REF}; the standard error object takes none`,
        });
      }
    }
    if (examples.length === 0) {
      violations.push({ path: rel, line: yamlLine(media, 'examples'), message: `${at} has no ${JSON_MEDIA} example` });
    }
    // A missing ErrorObject is reported once per document, by checkErrorObjects.
    if (errorSchemaResolves) reportExampleMismatches(doc, rel, at, ERROR_SCHEMA, examples, violations);
    const code = ERROR_CODE_BY_STATUS.get(status);
    for (const { label, value, line } of code === undefined ? [] : examples) {
      if (hasOwn(value, 'code') && value.code !== code) {
        violations.push({
          path: rel,
          line,
          message: `${at} ${label} has code ${shown(value.code)}, expected "${code}"`,
        });
      }
    }
  }
  // A baseline operation's error statuses are each required above.
  if (required === undefined && errors === 0) {
    violations.push({ path: rel, line: responsesLine, message: `${where}: no 4xx or 5xx response` });
  }
}

function checkBaselineOperations(doc, iface, violations) {
  for (const op of iface.operations) {
    const where = `${op.method.toUpperCase()} ${op.path}`;
    // A broken Path Item reference is reported once, by operationsOf, so it is not collected here.
    const item = hasOwn(doc.paths, op.path) ? deref(doc, doc.paths[op.path], iface.openapi, where, []) : undefined;
    const operation = isObject(item) ? item[op.method] : undefined;
    if (!isObject(operation)) {
      // Reported at the nearest existing ancestor: the path item, else `paths`.
      let line;
      if (isObject(item)) line = yamlLine(item, op.method);
      else if (isObject(doc.paths)) line = yamlLine(doc.paths, op.path);
      else line = yamlLine(doc, 'paths');
      violations.push({ path: iface.openapi, line, message: `missing baseline operation ${where} (${op.rpc})` });
    } else if (operation.operationId !== op.rpc) {
      violations.push({
        path: iface.openapi,
        line: yamlLine(operation, 'operationId'),
        message: `${where}: operationId is "${operation.operationId ?? ''}", expected "${op.rpc}"`,
      });
    }
  }
}

// ---------------------------------------------------------------------------
// ErrorObject identity
// ---------------------------------------------------------------------------

/**
 * JSON text with object keys sorted at every depth, so equal schemas compare equal. It
 * recurses and copies without memo or cycle guard. That relies on what it receives,
 * values loadYaml returned and this file's own baseline constants, being acyclic trees
 * with no shared node: resolveYamlParser refuses aliases, cycles and nesting deeper
 * than YAML_MAX_DEPTH, so its work and depth stay linear in the document.
 */
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

function compareNames(rel, line, label, actual, expected, violations) {
  const missing = expected.filter((n) => !actual.includes(n));
  const extra = actual.filter((n) => !expected.includes(n));
  if (missing.length > 0 || extra.length > 0) {
    const parts = [];
    if (missing.length > 0) parts.push(`missing ${missing.join(', ')}`);
    if (extra.length > 0) parts.push(`unexpected ${extra.join(', ')}`);
    violations.push({
      path: rel,
      line,
      message: `${label} properties must be ${expected.join(', ')} (camelCase of common.proto): ${parts.join('; ')}`,
    });
  }
}

/** Line of an ErrorObject schema's properties.details.items, or of its nearest existing ancestor. */
function itemsLine(schema) {
  const properties = isObject(schema?.properties) ? schema.properties : undefined;
  if (isObject(properties?.details)) return yamlLine(properties.details, 'items');
  if (properties) return yamlLine(properties, 'details');
  return yamlLine(schema, 'properties');
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
      // Reported at the nearest existing ancestor of components.schemas.ErrorObject.
      let line;
      if (isObject(doc.components?.schemas)) line = yamlLine(doc.components.schemas, 'ErrorObject');
      else if (isObject(doc.components)) line = yamlLine(doc.components, 'schemas');
      else line = yamlLine(doc, 'components');
      violations.push({ path: rel, line, message: 'components.schemas.ErrorObject is missing' });
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
      violations.push({
        path: e.rel,
        line: yamlLine(e.schema),
        message: `ErrorObject schema differs from the one in ${base.rel}`,
      });
    }
    if ((e.itemsIsRef || base.itemsIsRef) && canonicalJson(e.items ?? null) !== baseItemsJson) {
      violations.push({
        path: e.rel,
        line: yamlLine(e.items) ?? itemsLine(e.schema),
        message: `ErrorObject details item schema differs from the one in ${base.rel}`,
      });
    }
  }

  for (const e of entries) {
    if (shared.errorObject) {
      const props = isObject(e.schema.properties) ? Object.keys(e.schema.properties) : [];
      compareNames(e.rel, yamlLine(e.schema, 'properties'), 'ErrorObject', props, shared.errorObject, violations);
    }
    if (shared.fieldViolation) {
      if (!isObject(e.items?.properties)) {
        violations.push({
          path: e.rel,
          line: yamlLine(e.items, 'properties') ?? itemsLine(e.schema),
          message: 'ErrorObject.details has no item schema with properties',
        });
      } else {
        const props = Object.keys(e.items.properties);
        const line = yamlLine(e.items, 'properties');
        compareNames(e.rel, line, 'ErrorObject.details item', props, shared.fieldViolation, violations);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Baseline field contracts and proto/REST twin agreement
// ---------------------------------------------------------------------------

/*
 * The Stub Contract Baseline is only ever extended additively, so its field contracts
 * are fixed here. PROTO_BASELINE, REST_BASELINE and ERROR_OBJECT_BASELINE were
 * generated from the delivered contracts with this file's parseProto and loadYaml,
 * never typed by hand. Each RPC and the REST operation that is its HTTP/JSON form must
 * also agree field by field, added fields included (checkTwinAgreement).
 */

/**
 * Stub Contract Baseline proto messages per proto file: every message's fields as
 * `[label ]type name = number`, with types relative to the file's package
 * (protoFieldSpec, which also writes a oneof membership and a non-default JSON name;
 * the baseline has neither), and every baseline RPC's signature (rpcSignature). The
 * service protos' ErrorDetail carrier is held to the RPC error contract instead.
 * Messages, fields and RPCs may be added; a baseline one is never removed, retyped,
 * relabelled, renumbered, given another JSON name, moved into or out of a oneof or made
 * streaming, and an added field never reuses a baseline number.
 */
const PROTO_BASELINE = {
  [COMMON_PROTO]: {
    messages: {
      ErrorObject: [
        'string code = 1',
        'string message = 2',
        'string trace_id = 3',
        'repeated FieldViolation details = 4',
      ],
      FieldViolation: ['string field = 1', 'string description = 2'],
    },
  },
  [`${SERVICE_PROTOS}/api/v1/card_servicing_service.proto`]: {
    rpcs: {
      GetBalanceSummary: '(GetBalanceSummaryRequest) returns (GetBalanceSummaryResponse)',
      ListCategoryBalances: '(ListCategoryBalancesRequest) returns (ListCategoryBalancesResponse)',
      ListTransactions: '(ListTransactionsRequest) returns (ListTransactionsResponse)',
      GetTransaction: '(GetTransactionRequest) returns (GetTransactionResponse)',
    },
    messages: {
      GetBalanceSummaryRequest: ['string account_id = 1'],
      GetBalanceSummaryResponse: [
        'string account_id = 1',
        'string current_balance = 2',
        'string credit_limit = 3',
        'repeated TypeGroupBalance balances_by_type_group = 4',
        'string data_status = 5',
        'string as_of = 6',
        'string active_status = 7',
        'string open_date = 8',
        'string expiration_date = 9',
        'string reissue_date = 10',
        'string cash_credit_limit = 11',
        'string current_cycle_credit = 12',
        'string current_cycle_debit = 13',
        'optional string group_id = 14',
      ],
      TypeGroupBalance: ['string type_code = 1', 'optional string type_description = 2', 'string balance = 3'],
      ListCategoryBalancesRequest: ['string account_id = 1', 'optional string type_code = 2'],
      ListCategoryBalancesResponse: [
        'string account_id = 1',
        'repeated CategoryBalance category_balances = 2',
        'string data_status = 3',
        'string as_of = 4',
      ],
      CategoryBalance: [
        'string type_code = 1',
        'optional string type_description = 2',
        'string category_code = 3',
        'optional string category_description = 4',
        'string balance = 5',
      ],
      ListTransactionsRequest: [
        'string account_id = 1',
        'optional string type_code = 2',
        'optional string category_code = 3',
        'optional int32 page_size = 4',
        'string page_token = 5',
      ],
      ListTransactionsResponse: [
        'string account_id = 1',
        'repeated TransactionSummary transactions = 2',
        'string next_page_token = 3',
        'string data_status = 4',
        'string as_of = 5',
      ],
      TransactionSummary: [
        'string transaction_id = 1',
        'string type_code = 2',
        'string category_code = 3',
        'string description = 4',
        'string amount = 5',
        'string original_timestamp = 6',
        'string processed_timestamp = 7',
      ],
      GetTransactionRequest: ['string account_id = 1', 'string transaction_id = 2'],
      GetTransactionResponse: [
        'string account_id = 1',
        'Transaction transaction = 2',
        'string data_status = 3',
        'string as_of = 4',
      ],
      Transaction: [
        'string transaction_id = 1',
        'string type_code = 2',
        'string category_code = 3',
        'string source = 4',
        'string description = 5',
        'string amount = 6',
        'string merchant_id = 7',
        'string merchant_name = 8',
        'string merchant_city = 9',
        'string merchant_zip = 10',
        'string card_number = 11',
        'string original_timestamp = 12',
        'string processed_timestamp = 13',
      ],
    },
  },
  [`${SERVICE_PROTOS}/carddata/v1/card_data_service.proto`]: {
    rpcs: {
      GetAccount: '(GetAccountRequest) returns (GetAccountResponse)',
      GetCardCrossReference: '(GetCardCrossReferenceRequest) returns (GetCardCrossReferenceResponse)',
      ListCategoryBalances: '(ListCategoryBalancesRequest) returns (ListCategoryBalancesResponse)',
    },
    messages: {
      GetAccountRequest: ['string account_id = 1'],
      GetAccountResponse: ['Account account = 1'],
      Account: [
        'string account_id = 1',
        'string active_status = 2',
        'string current_balance = 3',
        'string credit_limit = 4',
        'string cash_credit_limit = 5',
        'string open_date = 6',
        'string expiration_date = 7',
        'string reissue_date = 8',
        'string current_cycle_credit = 9',
        'string current_cycle_debit = 10',
        'string address_zip = 11',
        'optional string group_id = 12',
      ],
      GetCardCrossReferenceRequest: ['string account_id = 1'],
      GetCardCrossReferenceResponse: ['repeated CardCrossReference card_cross_references = 1'],
      CardCrossReference: ['string card_number = 1', 'string customer_id = 2', 'string account_id = 3'],
      ListCategoryBalancesRequest: ['string account_id = 1'],
      ListCategoryBalancesResponse: ['repeated CategoryBalance category_balances = 1'],
      CategoryBalance: [
        'string account_id = 1',
        'string type_code = 2',
        'string category_code = 3',
        'string balance = 4',
      ],
    },
  },
  [`${SERVICE_PROTOS}/transactionactivity/v1/transaction_activity_service.proto`]: {
    rpcs: {
      ListTransactions: '(ListTransactionsRequest) returns (ListTransactionsResponse)',
      GetTransaction: '(GetTransactionRequest) returns (GetTransactionResponse)',
    },
    messages: {
      ListTransactionsRequest: [
        'string account_id = 1',
        'optional string type_code = 2',
        'optional string category_code = 3',
        'int32 page_size = 4',
        'string page_token = 5',
      ],
      ListTransactionsResponse: ['repeated Transaction transactions = 1', 'string next_page_token = 2'],
      GetTransactionRequest: ['string transaction_id = 1'],
      GetTransactionResponse: ['Transaction transaction = 1'],
      Transaction: [
        'string transaction_id = 1',
        'string type_code = 2',
        'string category_code = 3',
        'string source = 4',
        'string description = 5',
        'string amount = 6',
        'string merchant_id = 7',
        'string merchant_name = 8',
        'string merchant_city = 9',
        'string merchant_zip = 10',
        'string card_number = 11',
        'string original_timestamp = 12',
        'string processed_timestamp = 13',
      ],
    },
  },
  [`${SERVICE_PROTOS}/config/v1/config_service.proto`]: {
    rpcs: { GetConfig: '(GetConfigRequest) returns (GetConfigResponse)' },
    messages: { GetConfigRequest: ['repeated string keys = 1'], GetConfigResponse: ['map<string, string> values = 1'] },
  },
  [`${SERVICE_PROTOS}/referencedata/v1/reference_data_service.proto`]: {
    rpcs: {
      ListTransactionTypes: '(ListTransactionTypesRequest) returns (ListTransactionTypesResponse)',
      ListTransactionCategories: '(ListTransactionCategoriesRequest) returns (ListTransactionCategoriesResponse)',
    },
    messages: {
      ListTransactionTypesRequest: [],
      ListTransactionTypesResponse: ['repeated TransactionType transaction_types = 1'],
      TransactionType: ['string type_code = 1', 'string type_description = 2'],
      ListTransactionCategoriesRequest: [],
      ListTransactionCategoriesResponse: ['repeated TransactionCategory transaction_categories = 1'],
      TransactionCategory: ['string type_code = 1', 'string category_code = 2', 'string category_description = 3'],
    },
  },
  [`${SERVICE_PROTOS}/auth/v1/auth_service.proto`]: {
    rpcs: {
      ValidateToken: '(ValidateTokenRequest) returns (ValidateTokenResponse)',
      AuthorizeAccountAccess: '(AuthorizeAccountAccessRequest) returns (AuthorizeAccountAccessResponse)',
    },
    messages: {
      ValidateTokenRequest: ['string token = 1'],
      ValidateTokenResponse: ['string subject = 1', 'repeated string roles = 2'],
      AuthorizeAccountAccessRequest: ['string subject = 1', 'string account_id = 2'],
      AuthorizeAccountAccessResponse: ['bool allowed = 1'],
    },
  },
};

/**
 * Stub Contract Baseline REST operations per OpenAPI document and RPC name: the
 * parameters, the request body, the 200 response headers and the 200 application/json
 * schema, every schema reduced to CONTRACT_KEYWORDS, with `$ref`s that resolve in the
 * document's `schemas` here. An operation may add optional parameters, response
 * properties and headers. Every baseline parameter, header and property keeps its
 * constraints and required flag, a response's baseline `required` names stay required,
 * and a request never gains a required parameter, body or property.
 */
const REST_BASELINE = {
  [`${OPENAPI_DIR}/card-servicing-api.yaml`]: {
    operations: {
      GetBalanceSummary: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
        ],
        headers: {
          'X-Data-Status': { required: true, schema: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] } },
        },
        response: { $ref: '#/components/schemas/GetBalanceSummaryResponse' },
      },
      ListCategoryBalances: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
          { name: 'typeCode', in: 'query', required: false, schema: { type: 'string', pattern: '^[\\s\\S]{2}$' } },
        ],
        headers: {
          'X-Data-Status': { required: true, schema: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] } },
        },
        response: { $ref: '#/components/schemas/ListCategoryBalancesResponse' },
      },
      ListTransactions: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
          { name: 'typeCode', in: 'query', required: false, schema: { type: 'string', pattern: '^[\\s\\S]{2}$' } },
          { name: 'categoryCode', in: 'query', required: false, schema: { type: 'string', pattern: '^[0-9]{4}$' } },
          { name: 'pageSize', in: 'query', required: false, schema: { type: 'integer', minimum: 1, maximum: 100 } },
          {
            name: 'pageToken',
            in: 'query',
            required: false,
            schema: { type: 'string', pattern: '^[A-Za-z0-9_-]*$', maxLength: 166 },
          },
        ],
        headers: {
          'X-Data-Status': { required: true, schema: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] } },
        },
        response: { $ref: '#/components/schemas/ListTransactionsResponse' },
      },
      GetTransaction: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
          { name: 'transactionId', in: 'path', required: true, schema: { type: 'string', pattern: '^[\\s\\S]{16}$' } },
        ],
        headers: {
          'X-Data-Status': { required: true, schema: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] } },
        },
        response: { $ref: '#/components/schemas/GetTransactionResponse' },
      },
    },
    schemas: {
      GetBalanceSummaryResponse: {
        type: 'object',
        required: [
          'accountId', 'currentBalance', 'creditLimit', 'balancesByTypeGroup', 'dataStatus', 'asOf', 'activeStatus',
          'openDate', 'expirationDate', 'reissueDate', 'cashCreditLimit', 'currentCycleCredit', 'currentCycleDebit',
        ],
        properties: {
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
          currentBalance: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          creditLimit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          balancesByTypeGroup: { type: 'array', items: { $ref: '#/components/schemas/TypeGroupBalance' } },
          dataStatus: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] },
          asOf: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
          activeStatus: { type: 'string', maxLength: 1 },
          openDate: { type: 'string', pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', format: 'date' },
          expirationDate: { type: 'string', pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', format: 'date' },
          reissueDate: { type: 'string', pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', format: 'date' },
          cashCreditLimit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          currentCycleCredit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          currentCycleDebit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          groupId: { type: 'string', maxLength: 10 },
        },
        additionalProperties: false,
      },
      TypeGroupBalance: {
        type: 'object',
        required: ['typeCode', 'typeDescription', 'balance'],
        properties: {
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          typeDescription: { type: ['string', 'null'], maxLength: 50 },
          balance: { type: 'string', pattern: '^-?[0-9]+\\.[0-9]{2}$' },
        },
        additionalProperties: false,
      },
      ListCategoryBalancesResponse: {
        type: 'object',
        required: ['accountId', 'categoryBalances', 'dataStatus', 'asOf'],
        properties: {
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
          categoryBalances: { type: 'array', items: { $ref: '#/components/schemas/CategoryBalance' } },
          dataStatus: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] },
          asOf: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
        },
        additionalProperties: false,
      },
      CategoryBalance: {
        type: 'object',
        required: ['typeCode', 'typeDescription', 'categoryCode', 'categoryDescription', 'balance'],
        properties: {
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          typeDescription: { type: ['string', 'null'], maxLength: 50 },
          categoryCode: { type: 'string', pattern: '^[0-9]{4}$' },
          categoryDescription: { type: ['string', 'null'], maxLength: 50 },
          balance: { type: 'string', pattern: '^-?[0-9]{1,9}\\.[0-9]{2}$' },
        },
        additionalProperties: false,
      },
      ListTransactionsResponse: {
        type: 'object',
        required: ['accountId', 'transactions', 'dataStatus', 'asOf'],
        properties: {
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
          transactions: { type: 'array', items: { $ref: '#/components/schemas/TransactionSummary' } },
          nextPageToken: { type: 'string', pattern: '^[A-Za-z0-9_-]+$', maxLength: 166 },
          dataStatus: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] },
          asOf: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
        },
        additionalProperties: false,
      },
      TransactionSummary: {
        type: 'object',
        required: [
          'transactionId', 'typeCode', 'categoryCode', 'description', 'amount', 'originalTimestamp',
          'processedTimestamp',
        ],
        properties: {
          transactionId: { type: 'string', pattern: '^[\\s\\S]{16}$' },
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          categoryCode: { type: 'string', pattern: '^[0-9]{4}$' },
          description: { type: 'string', maxLength: 100 },
          amount: { type: 'string', pattern: '^-?[0-9]{1,9}\\.[0-9]{2}$' },
          originalTimestamp: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
          processedTimestamp: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
        },
        additionalProperties: false,
      },
      GetTransactionResponse: {
        type: 'object',
        required: ['accountId', 'transaction', 'dataStatus', 'asOf'],
        properties: {
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
          transaction: { $ref: '#/components/schemas/Transaction' },
          dataStatus: { type: 'string', enum: ['FRESH', 'STALE', 'PARTIAL'] },
          asOf: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
        },
        additionalProperties: false,
      },
      Transaction: {
        type: 'object',
        required: [
          'transactionId', 'typeCode', 'categoryCode', 'source', 'description', 'amount', 'merchantId', 'merchantName',
          'merchantCity', 'merchantZip', 'cardNumber', 'originalTimestamp', 'processedTimestamp',
        ],
        properties: {
          transactionId: { type: 'string', pattern: '^[\\s\\S]{16}$' },
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          categoryCode: { type: 'string', pattern: '^[0-9]{4}$' },
          source: { type: 'string', maxLength: 10 },
          description: { type: 'string', maxLength: 100 },
          amount: { type: 'string', pattern: '^-?[0-9]{1,9}\\.[0-9]{2}$' },
          merchantId: { type: 'string', pattern: '^[0-9]{9}$' },
          merchantName: { type: 'string', maxLength: 50 },
          merchantCity: { type: 'string', maxLength: 50 },
          merchantZip: { type: 'string', maxLength: 10 },
          cardNumber: { type: 'string', maxLength: 16 },
          originalTimestamp: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
          processedTimestamp: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
        },
        additionalProperties: false,
      },
    },
  },
  [`${OPENAPI_DIR}/card-data-service.yaml`]: {
    operations: {
      GetAccount: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
        ],
        response: { $ref: '#/components/schemas/GetAccountResponse' },
      },
      GetCardCrossReference: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
        ],
        response: { $ref: '#/components/schemas/GetCardCrossReferenceResponse' },
      },
      ListCategoryBalances: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
        ],
        response: { $ref: '#/components/schemas/ListCategoryBalancesResponse' },
      },
    },
    schemas: {
      GetAccountResponse: {
        type: 'object',
        required: ['account'],
        properties: { account: { $ref: '#/components/schemas/Account' } },
        additionalProperties: false,
      },
      Account: {
        type: 'object',
        required: [
          'accountId', 'activeStatus', 'currentBalance', 'creditLimit', 'cashCreditLimit', 'openDate', 'expirationDate',
          'reissueDate', 'currentCycleCredit', 'currentCycleDebit', 'addressZip',
        ],
        properties: {
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
          activeStatus: { type: 'string', maxLength: 1 },
          currentBalance: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          creditLimit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          cashCreditLimit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          openDate: { type: 'string', pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', format: 'date' },
          expirationDate: { type: 'string', pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', format: 'date' },
          reissueDate: { type: 'string', pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}$', format: 'date' },
          currentCycleCredit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          currentCycleDebit: { type: 'string', pattern: '^-?[0-9]{1,10}\\.[0-9]{2}$' },
          addressZip: { type: 'string', maxLength: 10 },
          groupId: { type: 'string', maxLength: 10 },
        },
        additionalProperties: false,
      },
      GetCardCrossReferenceResponse: {
        type: 'object',
        required: ['cardCrossReferences'],
        properties: {
          cardCrossReferences: { type: 'array', items: { $ref: '#/components/schemas/CardCrossReference' } },
        },
        additionalProperties: false,
      },
      CardCrossReference: {
        type: 'object',
        required: ['cardNumber', 'customerId', 'accountId'],
        properties: {
          cardNumber: { type: 'string', maxLength: 16 },
          customerId: { type: 'string', pattern: '^[0-9]{9}$' },
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
        },
        additionalProperties: false,
      },
      ListCategoryBalancesResponse: {
        type: 'object',
        required: ['categoryBalances'],
        properties: { categoryBalances: { type: 'array', items: { $ref: '#/components/schemas/CategoryBalance' } } },
        additionalProperties: false,
      },
      CategoryBalance: {
        type: 'object',
        required: ['accountId', 'typeCode', 'categoryCode', 'balance'],
        properties: {
          accountId: { type: 'string', pattern: '^[0-9]{11}$' },
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          categoryCode: { type: 'string', pattern: '^[0-9]{4}$' },
          balance: { type: 'string', pattern: '^-?[0-9]{1,9}\\.[0-9]{2}$' },
        },
        additionalProperties: false,
      },
    },
  },
  [`${OPENAPI_DIR}/transaction-activity-service.yaml`]: {
    operations: {
      ListTransactions: {
        parameters: [
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
          { name: 'typeCode', in: 'query', required: false, schema: { type: 'string', pattern: '^[\\s\\S]{2}$' } },
          { name: 'categoryCode', in: 'query', required: false, schema: { type: 'string', pattern: '^[0-9]{4}$' } },
          { name: 'pageSize', in: 'query', required: true, schema: { type: 'integer', minimum: 1, maximum: 100 } },
          {
            name: 'pageToken',
            in: 'query',
            required: false,
            schema: { type: 'string', pattern: '^[A-Za-z0-9_-]*$', maxLength: 166 },
          },
        ],
        response: { $ref: '#/components/schemas/ListTransactionsResponse' },
      },
      GetTransaction: {
        parameters: [
          { name: 'transactionId', in: 'path', required: true, schema: { type: 'string', pattern: '^[\\s\\S]{16}$' } },
        ],
        response: { $ref: '#/components/schemas/GetTransactionResponse' },
      },
    },
    schemas: {
      ListTransactionsResponse: {
        type: 'object',
        required: ['transactions'],
        properties: {
          transactions: { type: 'array', items: { $ref: '#/components/schemas/Transaction' } },
          nextPageToken: { type: 'string', pattern: '^[A-Za-z0-9_-]+$', maxLength: 166 },
        },
        additionalProperties: false,
      },
      Transaction: {
        type: 'object',
        required: [
          'transactionId', 'typeCode', 'categoryCode', 'source', 'description', 'amount', 'merchantId', 'merchantName',
          'merchantCity', 'merchantZip', 'cardNumber', 'originalTimestamp', 'processedTimestamp',
        ],
        properties: {
          transactionId: { type: 'string', pattern: '^[\\s\\S]{16}$' },
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          categoryCode: { type: 'string', pattern: '^[0-9]{4}$' },
          source: { type: 'string', maxLength: 10 },
          description: { type: 'string', maxLength: 100 },
          amount: { type: 'string', pattern: '^-?[0-9]{1,9}\\.[0-9]{2}$' },
          merchantId: { type: 'string', pattern: '^[0-9]{9}$' },
          merchantName: { type: 'string', maxLength: 50 },
          merchantCity: { type: 'string', maxLength: 50 },
          merchantZip: { type: 'string', maxLength: 10 },
          cardNumber: { type: 'string', maxLength: 16 },
          originalTimestamp: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
          processedTimestamp: {
            type: 'string',
            pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$',
            format: 'date-time',
          },
        },
        additionalProperties: false,
      },
      GetTransactionResponse: {
        type: 'object',
        required: ['transaction'],
        properties: { transaction: { $ref: '#/components/schemas/Transaction' } },
        additionalProperties: false,
      },
    },
  },
  [`${OPENAPI_DIR}/config-service.yaml`]: {
    operations: {
      GetConfig: {
        parameters: [
          {
            name: 'keys',
            in: 'query',
            required: true,
            style: 'form',
            explode: false,
            schema: { type: 'array', items: { type: 'string', minLength: 1 }, minItems: 1 },
          },
        ],
        response: { $ref: '#/components/schemas/GetConfigResponse' },
      },
    },
    schemas: {
      GetConfigResponse: {
        type: 'object',
        required: ['values'],
        properties: { values: { type: 'object', additionalProperties: { type: 'string', pattern: '^[0-9]+$' } } },
        additionalProperties: false,
      },
    },
  },
  [`${OPENAPI_DIR}/reference-data-service.yaml`]: {
    operations: {
      ListTransactionTypes: { parameters: [], response: { $ref: '#/components/schemas/ListTransactionTypesResponse' } },
      ListTransactionCategories: {
        parameters: [],
        response: { $ref: '#/components/schemas/ListTransactionCategoriesResponse' },
      },
    },
    schemas: {
      ListTransactionTypesResponse: {
        type: 'object',
        required: ['transactionTypes'],
        properties: { transactionTypes: { type: 'array', items: { $ref: '#/components/schemas/TransactionType' } } },
        additionalProperties: false,
      },
      TransactionType: {
        type: 'object',
        required: ['typeCode', 'typeDescription'],
        properties: {
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          typeDescription: { type: 'string', maxLength: 50 },
        },
        additionalProperties: false,
      },
      ListTransactionCategoriesResponse: {
        type: 'object',
        required: ['transactionCategories'],
        properties: {
          transactionCategories: { type: 'array', items: { $ref: '#/components/schemas/TransactionCategory' } },
        },
        additionalProperties: false,
      },
      TransactionCategory: {
        type: 'object',
        required: ['typeCode', 'categoryCode', 'categoryDescription'],
        properties: {
          typeCode: { type: 'string', pattern: '^[\\s\\S]{2}$' },
          categoryCode: { type: 'string', pattern: '^[0-9]{4}$' },
          categoryDescription: { type: 'string', maxLength: 50 },
        },
        additionalProperties: false,
      },
    },
  },
  [`${OPENAPI_DIR}/auth-service.yaml`]: {
    operations: {
      ValidateToken: {
        parameters: [],
        requestBody: { required: true, schema: { $ref: '#/components/schemas/ValidateTokenRequest' } },
        response: { $ref: '#/components/schemas/ValidateTokenResponse' },
      },
      AuthorizeAccountAccess: {
        parameters: [
          { name: 'subject', in: 'path', required: true, schema: { type: 'string', minLength: 1, maxLength: 64 } },
          { name: 'accountId', in: 'path', required: true, schema: { type: 'string', pattern: '^[0-9]{11}$' } },
        ],
        response: { $ref: '#/components/schemas/AuthorizeAccountAccessResponse' },
      },
    },
    schemas: {
      ValidateTokenRequest: {
        type: 'object',
        required: ['token'],
        properties: { token: { type: 'string', minLength: 1 } },
        additionalProperties: false,
      },
      ValidateTokenResponse: {
        type: 'object',
        required: ['subject', 'roles'],
        properties: {
          subject: { type: 'string', minLength: 1, maxLength: 64 },
          roles: { type: 'array', items: { type: 'string', enum: ['REGULAR', 'ADMIN'] } },
        },
        additionalProperties: false,
      },
      AuthorizeAccountAccessResponse: {
        type: 'object',
        required: ['allowed'],
        properties: { allowed: { type: 'boolean' } },
        additionalProperties: false,
      },
    },
  },
};

/**
 * The standard error object, components.schemas.ErrorObject, reduced to
 * CONTRACT_KEYWORDS. checkErrorObjects holds the six documents' copies identical to
 * each other; this holds each copy to the baseline, so a change made to all six at
 * once still fails.
 */
const ERROR_OBJECT_BASELINE = {
  type: 'object',
  required: ['code', 'message', 'traceId', 'details'],
  properties: {
    code: { type: 'string' },
    message: { type: 'string' },
    traceId: { type: 'string' },
    details: {
      type: 'array',
      items: {
        type: 'object',
        required: ['field', 'description'],
        properties: { field: { type: 'string' }, description: { type: 'string' } },
        additionalProperties: false,
      },
    },
  },
  additionalProperties: false,
};

/**
 * JSON-schema keywords that carry a contract: every validation, applicator, unevaluated,
 * format and content keyword of JSON Schema 2020-12 (the OpenAPI 3.1 dialect), the
 * earlier drafts' dependencies and additionalItems, which a declared dialect may still
 * apply, and $dynamicRef. Annotations (title, description, default, examples,
 * deprecated, readOnly, writeOnly), $comment and OpenAPI's example, discriminator, xml
 * and externalDocs carry none.
 */
const CONTRACT_KEYWORDS = [
  'type', 'required', 'properties', 'items', 'additionalProperties', 'enum', 'const', 'pattern', 'format', 'minimum',
  'maximum', 'exclusiveMinimum', 'exclusiveMaximum', 'multipleOf', 'minLength', 'maxLength', 'minItems', 'maxItems',
  'uniqueItems', 'minProperties', 'maxProperties', 'dependentRequired', 'minContains', 'maxContains', 'allOf', 'anyOf',
  'oneOf', 'not', 'if', 'then', 'else', 'dependentSchemas', 'prefixItems', 'contains', 'patternProperties',
  'propertyNames', 'unevaluatedProperties', 'unevaluatedItems', 'contentEncoding', 'contentMediaType', 'contentSchema',
  'dependencies', 'additionalItems', '$dynamicRef',
];

/** The CONTRACT_KEYWORDS compared by value; required, properties, items and additionalProperties are walked. */
const CONTRACT_VALUE_KEYWORDS = CONTRACT_KEYWORDS.filter((k) => !['required', 'properties', 'items', 'additionalProperties'].includes(k));

/** CONTRACT_KEYWORDS whose value is one schema; an earlier draft's items and additionalItems may be a list of them. */
const SUBSCHEMA_KEYWORDS = new Set([
  'items', 'additionalProperties', 'additionalItems', 'not', 'if', 'then', 'else', 'contains', 'propertyNames',
  'unevaluatedProperties', 'unevaluatedItems', 'contentSchema',
]);

/** CONTRACT_KEYWORDS whose value is a list of schemas. */
const SUBSCHEMA_LIST_KEYWORDS = new Set(['allOf', 'anyOf', 'oneOf', 'prefixItems']);

/** CONTRACT_KEYWORDS whose value maps names to schemas (a dependencies entry may instead be a list of names). */
const SUBSCHEMA_MAP_KEYWORDS = new Set(['properties', 'patternProperties', 'dependentSchemas', 'dependencies']);

/**
 * A schema reduced to its `$ref` and CONTRACT_KEYWORDS at every depth, the form the REST
 * baselines hold, so two schemas stating one contract compare equal under canonicalJson.
 * A `$ref` is kept as written and never followed; a boolean schema, or any other value
 * that is not an object, is kept as it is.
 */
function contractForm(schema) {
  if (!isObject(schema)) return schema;
  const reduce = (keyword, value) => {
    if (SUBSCHEMA_LIST_KEYWORDS.has(keyword) || (SUBSCHEMA_KEYWORDS.has(keyword) && Array.isArray(value))) {
      return Array.isArray(value) ? value.map(contractForm) : value;
    }
    if (SUBSCHEMA_KEYWORDS.has(keyword)) return contractForm(value);
    if (SUBSCHEMA_MAP_KEYWORDS.has(keyword) && isObject(value)) {
      return Object.fromEntries(Object.entries(value).map(([name, sub]) => [name, contractForm(sub)]));
    }
    return value;
  };
  const out = {};
  if (hasOwn(schema, '$ref')) out.$ref = schema.$ref;
  for (const keyword of CONTRACT_KEYWORDS) if (hasOwn(schema, keyword)) out[keyword] = reduce(keyword, schema[keyword]);
  return out;
}

const SCHEMA_REF_PREFIX = '#/components/schemas/';

/** JSON-schema types a proto scalar's REST twin may carry under the proto3 JSON mapping. */
const PROTO_SCALAR_JSON_TYPES = new Map([
  ['string', ['string']],
  ['bytes', ['string']],
  ['bool', ['boolean']],
  ...['int32', 'sint32', 'sfixed32', 'uint32', 'fixed32'].map((t) => [t, ['integer']]),
  ...['int64', 'sint64', 'sfixed64', 'uint64', 'fixed64'].map((t) => [t, ['integer', 'string']]),
  ['float', ['number']],
  ['double', ['number']],
]);

/**
 * The twin presence rule: a singular or repeated proto field (a map field included)
 * has a REST twin that is required, a path parameter always being required; an
 * `optional` proto field or a oneof member has a REST twin that is not required; and no
 * REST twin admits null. These are its documented exceptions, keyed by JSON name, each
 * applying only to a proto field with the label given:
 *   typeDescription, categoryDescription: proto `optional string`, unset when dataStatus
 *     is PARTIAL; REST required with type [string, "null"], null when PARTIAL.
 *   nextPageToken: proto singular string, empty on the last page; REST omits the
 *     property then, so it is not required.
 *   pageToken: proto singular string, empty for the first page; REST omits the
 *     parameter then, so it is not required.
 */
const TWIN_PRESENCE_EXCEPTIONS = new Map([
  ['typeDescription', { label: 'optional', required: true, nullable: true }],
  ['categoryDescription', { label: 'optional', required: true, nullable: true }],
  ['nextPageToken', { label: '', required: false, nullable: false }],
  ['pageToken', { label: '', required: false, nullable: false }],
]);

/** A proto type relative to the package `pkg`: a leading `.` and the package prefix dropped, map spacing fixed. */
function baselineProtoType(type, pkg) {
  const map = /^map\s*<\s*([\w.]+)\s*,\s*([\w.]+)\s*>$/.exec(type);
  if (map) return `map<${baselineProtoType(map[1], pkg)}, ${baselineProtoType(map[2], pkg)}>`;
  const name = type.startsWith('.') ? type.slice(1) : type;
  return pkg && name.startsWith(`${pkg}.`) ? name.slice(pkg.length + 1) : name;
}

/**
 * A parseProto field in PROTO_BASELINE form: `[oneof O: ][label ]type name = number[ [json_name = "j"]]`,
 * the oneof prefix only for a member of oneof O and the json_name suffix only when the
 * JSON name is not the default jsonName(name).
 */
const protoFieldSpec = (field, pkg) =>
  `${field.oneof ? `oneof ${field.oneof}: ` : ''}${field.label ? `${field.label} ` : ''}` +
  `${baselineProtoType(field.type, pkg)} ${field.name} = ${field.number}` +
  `${field.json === jsonName(field.name) ? '' : ` [json_name = "${field.json}"]`}`;

/** One PROTO_BASELINE field: oneof, label, type, name, number and a non-default JSON name. */
const PROTO_FIELD_SPEC_RE =
  /^(?:oneof (?<oneof>[A-Za-z_]\w*): )?(?:(?<label>repeated|optional|required) )?(?<type>map<[\w.]+, [\w.]+>|[\w.]+) (?<name>[A-Za-z_]\w*) = (?<number>\d+)(?: \[json_name = "(?<json>[^"]*)"\])?$/;

/** A parseProto RPC in PROTO_BASELINE form: `(stream? Request) returns (stream? Response)`. */
const rpcSignature = (rpc, pkg) =>
  `(${rpc.requestStream ? 'stream ' : ''}${baselineProtoType(rpc.requestType, pkg)}) returns ` +
  `(${rpc.responseStream ? 'stream ' : ''}${baselineProtoType(rpc.responseType, pkg)})`;

/**
 * Holds every proto to PROTO_BASELINE. A missing baseline message is reported at the
 * package statement, a missing field at its message, a changed or number-reusing field
 * at that field, and a changed RPC at the RPC. A missing file, service or RPC is
 * reported by main and checkProtos.
 */
function checkProtoBaseline(protos, violations) {
  for (const [rel, baseline] of Object.entries(PROTO_BASELINE)) {
    const proto = protos.get(rel);
    if (!proto) continue;
    const pkg = proto.package?.name ?? '';
    const head = proto.package?.line ?? 1;
    for (const [name, specs] of Object.entries(baseline.messages)) {
      const message = proto.messages.get(name);
      if (!message) {
        violations.push({ path: rel, line: head, message: `missing baseline message ${name} { ${specs.join('; ')} }` });
        continue;
      }
      const fields = new Map(message.fields.map((f) => [f.name, f]));
      const numbers = new Map();
      for (const spec of specs) {
        const { name: fieldName, number } = PROTO_FIELD_SPEC_RE.exec(spec).groups;
        numbers.set(Number(number), spec);
        const field = fields.get(fieldName);
        if (!field) {
          violations.push({ path: rel, line: message.line, message: `message ${name} lacks baseline field "${spec}"` });
        } else if (protoFieldSpec(field, pkg) !== spec) {
          violations.push({
            path: rel,
            line: field.line,
            message: `message ${name} field ${fieldName} is "${protoFieldSpec(field, pkg)}", baseline "${spec}"`,
          });
        }
      }
      const baselineNames = new Set(specs.map((spec) => PROTO_FIELD_SPEC_RE.exec(spec).groups.name));
      for (const field of message.fields) {
        const taken = baselineNames.has(field.name) ? undefined : numbers.get(field.number);
        if (taken) {
          violations.push({
            path: rel,
            line: field.line,
            message: `message ${name} added field "${protoFieldSpec(field, pkg)}" reuses the number of baseline field "${taken}"`,
          });
        }
      }
    }
    const iface = INTERFACES.find((i) => i.proto === rel);
    const service = iface ? proto.services.get(iface.service) : undefined;
    for (const [rpcName, signature] of Object.entries(baseline.rpcs ?? {})) {
      const rpc = service?.rpcs.find((r) => r.name === rpcName);
      if (rpc && rpcSignature(rpc, pkg) !== signature) {
        violations.push({
          path: rel,
          line: rpc.line,
          message: `rpc ${rpcName} is ${rpcSignature(rpc, pkg)}, baseline ${signature}`,
        });
      }
    }
  }
}

/** The schema a REST_BASELINE `$ref` names, from its document's baseline `schemas`. */
function restBaselineSchema(baseline, schema) {
  let current = schema;
  for (let hops = 0; isObject(current) && typeof current.$ref === 'string'; hops++) {
    const name = current.$ref.startsWith(SCHEMA_REF_PREFIX) ? current.$ref.slice(SCHEMA_REF_PREFIX.length) : '';
    if (!hasOwn(baseline.schemas, name) || hops > 64) throw new Error(`REST_BASELINE $ref ${current.$ref} does not resolve`);
    current = baseline.schemas[name];
  }
  return current;
}

/** A schema's `type` as a sorted list of names; [] when absent. */
function schemaTypeList(schema) {
  const type = isObject(schema) ? schema.type : undefined;
  if (type === undefined) return [];
  return [...new Set((Array.isArray(type) ? type : [type]).map(String))].sort();
}

/** True when two values of a keyword state the same constraint; `type` and `enum` compare as sets. */
function sameConstraint(keyword, a, b) {
  if (a === undefined || b === undefined) return a === b;
  if (keyword === 'type') return canonicalJson(schemaTypeList({ type: a })) === canonicalJson(schemaTypeList({ type: b }));
  if (keyword === 'enum' && Array.isArray(a) && Array.isArray(b)) {
    const members = (v) => v.map(canonicalJson).sort();
    return canonicalJson(members(a)) === canonicalJson(members(b));
  }
  return canonicalJson(a) === canonicalJson(b);
}

const constraintText = (value) => (value === undefined ? 'absent' : JSON.stringify(value));

/** The schema a `$ref` names in a REST_BASELINE document's baseline `schemas`; undefined when it names none. */
function baselineRefTarget(baseline, ref) {
  const name = ref.startsWith(SCHEMA_REF_PREFIX) ? ref.slice(SCHEMA_REF_PREFIX.length) : '';
  return hasOwn(baseline.schemas, name) ? baseline.schemas[name] : undefined;
}

/**
 * The CONTRACT_KEYWORDS set beside `$ref` along the reference chain that starts at
 * `schema`, as [{ node, keyword, value }] in chain order, value in contractForm.
 * `target` maps a `$ref` to the schema it names, undefined when none. OpenAPI 3.1
 * applies such a keyword together with the referenced schema, and deref and
 * restBaselineSchema keep only the referenced schema.
 */
function refSiblings(schema, target) {
  const out = [];
  const seen = new Set();
  for (let node = schema; isObject(node) && typeof node.$ref === 'string' && !seen.has(node.$ref); node = target(node.$ref)) {
    seen.add(node.$ref);
    const form = contractForm(node);
    for (const keyword of CONTRACT_KEYWORDS) if (hasOwn(form, keyword)) out.push({ node, keyword, value: form[keyword] });
  }
  return out;
}

/**
 * Compares the current schema `raw` with its baseline `base`, reporting at `at` (a
 * JSON-path-like site such as `200 body.balancesByTypeGroup[].typeCode`). ctx is
 * { doc, rel, where, baseline, mode, violations, refViolations }: in mode 'response' a
 * baseline `required` name must stay required, in mode 'request' `required` must stay
 * exactly the baseline's. The keywords beside each `$ref` on the way to the schema must
 * be the baseline's beside its `$ref`s, none when it has none. Every other contract
 * keyword (CONTRACT_KEYWORDS) must be absent where the baseline lacks it and equal it
 * where present, subschemas compared in contractForm; every baseline property must
 * remain and match, and an added property passes. A broken `$ref` goes to refViolations.
 */
function compareToBaseline(ctx, base, raw, at, fallbackLine) {
  const expected = restBaselineSchema(ctx.baseline, base);
  const push = (line, text) =>
    ctx.violations.push({ path: ctx.rel, line: line ?? fallbackLine, message: `${ctx.where}: ${at}: ${text}` });
  const found = refSiblings(raw, (ref) => resolvePointer(ctx.doc, ref).value);
  const wanted = refSiblings(base, (ref) => baselineRefTarget(ctx.baseline, ref));
  for (const keyword of new Set([...wanted, ...found].map((s) => s.keyword))) {
    const now = found.filter((s) => s.keyword === keyword);
    const was = wanted.filter((s) => s.keyword === keyword);
    if (now.length !== was.length || now.some((s, i) => !sameConstraint(keyword, was[i].value, s.value))) {
      const text = (list) => (list.length === 0 ? 'absent' : list.map((s) => JSON.stringify(s.value)).join(', '));
      push(now.length > 0 ? yamlLine(now[0].node, keyword) : yamlLine(raw), `${keyword} beside $ref is ${text(now)}, baseline ${text(was)}`);
    }
  }
  const node = deref(ctx.doc, raw, ctx.rel, `${ctx.where} ${at}`, ctx.refViolations);
  if (!isObject(node)) {
    if (!(isObject(raw) && typeof raw.$ref === 'string')) push(undefined, `schema is ${constraintText(node)}, expected a schema object`);
    return;
  }
  const form = contractForm(node);
  for (const keyword of CONTRACT_VALUE_KEYWORDS) {
    if (!sameConstraint(keyword, expected[keyword], form[keyword])) {
      push(yamlLine(node, keyword), `${keyword} is ${constraintText(form[keyword])}, baseline ${constraintText(expected[keyword])}`);
    }
  }
  const baseRequired = Array.isArray(expected.required) ? expected.required : [];
  const required = Array.isArray(node.required) ? node.required.map(String) : [];
  const dropped = baseRequired.filter((n) => !required.includes(n));
  if (dropped.length > 0) push(yamlLine(node, 'required'), `required lacks baseline ${dropped.join(', ')}`);
  const added = ctx.mode === 'request' ? required.filter((n) => !baseRequired.includes(n)) : [];
  if (added.length > 0) {
    push(yamlLine(node, 'required'), `required adds ${added.join(', ')}; a request may add only optional properties`);
  }
  const props = isObject(node.properties) ? node.properties : {};
  for (const [name, prop] of Object.entries(isObject(expected.properties) ? expected.properties : {})) {
    if (hasOwn(props, name)) compareToBaseline(ctx, prop, props[name], `${at}.${name}`, yamlLine(props, name));
    else push(yamlLine(node, 'properties'), `lacks baseline property ${name}`);
  }
  if (expected.items !== undefined) {
    if (node.items === undefined) push(yamlLine(node, 'items'), 'items is absent; the baseline has an item schema');
    else compareToBaseline(ctx, expected.items, node.items, `${at}[]`, yamlLine(node, 'items'));
  }
  const extra = expected.additionalProperties;
  if (isObject(extra)) {
    if (isObject(node.additionalProperties)) {
      compareToBaseline(ctx, extra, node.additionalProperties, `${at}{*}`, yamlLine(node, 'additionalProperties'));
    } else {
      const found = constraintText(node.additionalProperties);
      push(yamlLine(node, 'additionalProperties'), `additionalProperties is ${found}; the baseline has a value schema`);
    }
  } else if (node.additionalProperties !== extra) {
    const found = isObject(node.additionalProperties) ? 'a schema' : constraintText(node.additionalProperties);
    push(yamlLine(node, 'additionalProperties'), `additionalProperties is ${found}, baseline ${constraintText(extra)}`);
  }
}

/** A baseline operation's path item and operation object, or null when absent (checkBaselineOperations reports it). */
function locateBaselineOperation(doc, rel, op) {
  const item = hasOwn(doc.paths, op.path) ? deref(doc, doc.paths[op.path], rel, op.path, []) : undefined;
  const operation = isObject(item) ? item[op.method] : undefined;
  return isObject(operation) ? { item, operation } : null;
}

/** A parameter's OpenAPI serialization: its style and explode, defaulted for its location. */
function parameterSerialization(param) {
  let style = param.in === 'query' || param.in === 'cookie' ? 'form' : 'simple';
  if (typeof param.style === 'string') style = param.style;
  return { style, explode: typeof param.explode === 'boolean' ? param.explode : style === 'form' };
}

/** A parameter's identity: its location and name, a header name case-insensitively. */
const parameterIdentity = (param) => `${param.in} ${param.in === 'header' ? param.name.toLowerCase() : param.name}`;

/**
 * An operation's parameters, the path item's overridden by the operation's own, as a
 * Map parameterIdentity → resolved Parameter Object. A broken `$ref` goes to `violations`;
 * a parameter without a name and location is left out, because Spectral reports it.
 */
function effectiveParameters(doc, rel, where, { item, operation }, violations) {
  const params = new Map();
  for (const list of [item.parameters, operation.parameters]) {
    if (!Array.isArray(list)) continue;
    list.forEach((raw, i) => {
      const param = deref(doc, raw, rel, `${where} parameter ${i + 1}`, violations);
      if (isObject(param) && typeof param.name === 'string' && typeof param.in === 'string') {
        params.set(parameterIdentity(param), param);
      }
    });
  }
  return params;
}

/** Holds an operation's parameters to its baseline ones: each one kept as it was, and no new one required. */
function compareBaselineParameters(ctx, expected, located) {
  const current = effectiveParameters(ctx.doc, ctx.rel, ctx.where, located, ctx.violations);
  const opLine = yamlLine(located.operation, 'parameters') ?? yamlLine(located.operation);
  const push = (line, text) => ctx.violations.push({ path: ctx.rel, line: line ?? opLine, message: `${ctx.where}: ${text}` });
  for (const base of expected) {
    const at = `${base.in} parameter ${base.name}`;
    const param = current.get(parameterIdentity(base));
    if (!param) {
      push(opLine, `lacks baseline ${at}`);
      continue;
    }
    const required = param.required === true;
    if (required !== base.required) {
      push(yamlLine(param, 'required') ?? yamlLine(param), `${at}: required is ${required}, baseline ${base.required}`);
    }
    const found = parameterSerialization(param);
    const wanted = parameterSerialization(base);
    for (const key of ['style', 'explode']) {
      if (found[key] !== wanted[key]) push(yamlLine(param, key), `${at}: ${key} is ${found[key]}, baseline ${wanted[key]}`);
    }
    if (param.schema === undefined) push(yamlLine(param), `${at}: schema is absent; the baseline has one`);
    else compareToBaseline(ctx, base.schema, param.schema, `${at} schema`, yamlLine(param, 'schema'));
  }
  const baseKeys = new Set(expected.map(parameterIdentity));
  for (const [key, param] of current) {
    if (!baseKeys.has(key) && param.required === true) {
      push(
        yamlLine(param, 'required'),
        `adds required ${param.in} parameter ${param.name}; a baseline operation may add only optional parameters`,
      );
    }
  }
}

/** Holds an operation's request body to the baseline one, or to being absent or optional when the baseline has none. */
function compareBaselineRequestBody(ctx, expected, operation) {
  const push = (line, text) =>
    ctx.violations.push({ path: ctx.rel, line: line ?? yamlLine(operation), message: `${ctx.where}: ${text}` });
  if (operation.requestBody === undefined) {
    if (expected) push(yamlLine(operation), 'lacks the baseline request body');
    return;
  }
  const body = deref(ctx.doc, operation.requestBody, ctx.rel, `${ctx.where} request body`, ctx.violations);
  if (!isObject(body)) return;
  const required = body.required === true;
  if (!expected) {
    if (required) push(yamlLine(body, 'required'), 'adds a required request body; a baseline operation may add only an optional one');
    return;
  }
  if (required !== expected.required) {
    push(yamlLine(body, 'required') ?? yamlLine(body), `request body required is ${required}, baseline ${expected.required}`);
  }
  const media = isObject(body.content) ? body.content[JSON_MEDIA] : undefined;
  if (!isObject(media) || media.schema === undefined) {
    push(yamlLine(body, 'content') ?? yamlLine(body), `request body has no ${JSON_MEDIA} schema; the baseline has one`);
    return;
  }
  compareToBaseline(ctx, expected.schema, media.schema, 'request body', yamlLine(media, 'schema'));
}

/**
 * Holds an operation's 200 response headers and application/json schema to the
 * baseline. A missing 200 response, JSON media or schema is reported by checkOperation,
 * so it is skipped here.
 */
function compareBaselineSuccess(ctx, expected, operation) {
  const raw = isObject(operation.responses) ? operation.responses['200'] : undefined;
  const response = raw === undefined ? undefined : deref(ctx.doc, raw, ctx.rel, '', []);
  if (!isObject(response)) return;
  const push = (line, text) =>
    ctx.violations.push({ path: ctx.rel, line: line ?? yamlLine(response), message: `${ctx.where}: ${text}` });
  const headers = isObject(response.headers) ? response.headers : {};
  for (const [name, base] of Object.entries(expected.headers ?? {})) {
    const at = `200 header ${name}`;
    const key = Object.keys(headers).find((k) => k.toLowerCase() === name.toLowerCase());
    if (key === undefined) {
      push(yamlLine(response, 'headers'), `200 response lacks baseline header ${name}`);
      continue;
    }
    const header = deref(ctx.doc, headers[key], ctx.rel, `${ctx.where} ${at}`, ctx.violations);
    if (!isObject(header)) continue;
    const required = header.required === true;
    if (required !== base.required) {
      push(yamlLine(header, 'required') ?? yamlLine(header), `${at}: required is ${required}, baseline ${base.required}`);
    }
    if (header.schema === undefined) push(yamlLine(header), `${at}: schema is absent; the baseline has one`);
    else compareToBaseline(ctx, base.schema, header.schema, `${at} schema`, yamlLine(header, 'schema'));
  }
  const media = isObject(response.content) ? response.content[JSON_MEDIA] : undefined;
  const schema = isObject(media) && media.schema !== undefined ? deref(ctx.doc, media.schema, ctx.rel, '', []) : undefined;
  // The schema as written, so the keywords beside its `$ref` are compared too.
  if (isObject(schema)) compareToBaseline(ctx, expected.response, media.schema, '200 body', yamlLine(media, 'schema'));
}

/** Holds the baseline operations of every document to REST_BASELINE, and every ErrorObject to ERROR_OBJECT_BASELINE. */
function checkRestBaseline(docs, violations) {
  for (const iface of INTERFACES) {
    const doc = docs.get(iface.openapi);
    const baseline = REST_BASELINE[iface.openapi];
    if (!doc || !baseline) continue;
    for (const op of iface.operations) {
      const located = locateBaselineOperation(doc, iface.openapi, op);
      if (!located) continue;
      const where = `${op.method.toUpperCase()} ${op.path} (${op.rpc})`;
      const ctx = { doc, rel: iface.openapi, where, baseline, mode: 'request', violations, refViolations: violations };
      const expected = baseline.operations[op.rpc];
      compareBaselineParameters(ctx, expected.parameters, located);
      compareBaselineRequestBody(ctx, expected.requestBody, located.operation);
      compareBaselineSuccess({ ...ctx, mode: 'response' }, expected, located.operation);
    }
  }
  for (const [rel, doc] of docs) {
    const schema = isObject(doc.components?.schemas) ? doc.components.schemas.ErrorObject : undefined;
    if (!isObject(schema)) continue;
    // A missing ErrorObject or a broken details-item reference is reported by checkErrorObjects.
    const ctx = {
      doc,
      rel,
      where: 'standard error object',
      baseline: { schemas: {} },
      mode: 'response',
      violations,
      refViolations: [],
    };
    compareToBaseline(ctx, ERROR_OBJECT_BASELINE, schema, 'ErrorObject', yamlLine(schema));
  }
}

/** A proto field as `Message.field (path:line)`. */
const protoFieldSite = (owner, field) => `${owner.message.name}.${field.name} (${owner.file.rel}:${field.line})`;

/** A proto message as `Message (path:line)`. */
const protoMessageSite = (owner) => `${owner.message.name} (${owner.file.rel}:${owner.message.line})`;

/**
 * Compares a proto message with its REST object schema: the field types, and, as tc
 * sets them, presence (tc.presence) and name parity in both directions (tc.names). tc
 * is { typeFiles, doc, rel, where, violations, presence, names }, typeFiles being the
 * run's protoTypeFiles map; owner is a message as resolveProtoType returns it,
 * { message, file }; `stack` holds the messages being compared, so a recursive one stops.
 */
function compareTwinMessage(tc, owner, schema, at, line, stack) {
  const push = (l, text) => tc.violations.push({ path: tc.rel, line: l ?? line, message: `${tc.where}: ${at}: ${text}` });
  const types = schemaTypeList(schema).filter((t) => t !== 'null');
  if (types.length !== 1 || types[0] !== 'object') {
    push(yamlLine(schema, 'type'), `type is ${constraintText(schema.type)}, expected "object" for proto message ${protoMessageSite(owner)}`);
    return;
  }
  const props = isObject(schema.properties) ? schema.properties : {};
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  for (const field of owner.message.fields) {
    if (!hasOwn(props, field.json)) {
      if (tc.names) push(yamlLine(schema, 'properties'), `lacks property ${field.json} of proto field ${protoFieldSite(owner, field)}`);
      continue;
    }
    compareTwinField(tc, owner, field, {
      schema: props[field.json],
      required: required.has(field.json),
      requiredLine: yamlLine(schema, 'required'),
      at: `${at}.${field.json}`,
      line: yamlLine(props, field.json),
    }, stack);
  }
  if (!tc.names) return;
  const names = new Set(owner.message.fields.map((f) => f.json));
  for (const name of Object.keys(props)) {
    if (!names.has(name)) push(yamlLine(props, name), `property ${name} has no field in proto message ${protoMessageSite(owner)}`);
  }
}

/**
 * Compares one proto field with its REST twin { schema, required, requiredLine, at,
 * line }: presence under the twin presence rule (TWIN_PRESENCE_EXCEPTIONS), null
 * admitted only where that rule says, and the type: repeated ↔ array of the element,
 * map ↔ object whose additionalProperties is the value, anything else ↔ its value.
 */
function compareTwinField(tc, owner, field, twin, stack) {
  const site = protoFieldSite(owner, field);
  const push = (line, text) => tc.violations.push({ path: tc.rel, line: line ?? twin.line, message: `${tc.where}: ${twin.at}: ${text}` });
  const exception = TWIN_PRESENCE_EXCEPTIONS.get(field.json);
  const rule = exception?.label === field.label ? exception : undefined;
  if (tc.presence) {
    // A oneof member tracks presence as an `optional` field does: at most one member is set.
    const expected = rule ? rule.required : field.label !== 'optional' && field.oneof === '';
    if (twin.required !== expected) {
      const state = (r) => (r ? 'required' : 'not required');
      const kind = field.oneof ? `oneof ${field.oneof} member` : field.label || 'singular';
      push(twin.requiredLine, `is ${state(twin.required)}, expected ${state(expected)} for proto ${kind} field ${site}`);
    }
  }
  const schema = deref(tc.doc, twin.schema, tc.rel, '', []);
  if (!isObject(schema)) return;
  const nullable = schemaTypeList(schema).includes('null');
  if (nullable !== (rule?.nullable === true)) {
    push(
      yamlLine(schema, 'type'),
      nullable
        ? `type ${constraintText(schema.type)} admits null, which proto field ${site} never carries`
        : `type ${constraintText(schema.type)} lacks "null", which stands for the unset proto field ${site}`,
    );
  }
  const types = schemaTypeList(schema).filter((t) => t !== 'null');
  const map = /^map\s*<\s*[\w.]+\s*,\s*([\w.]+)\s*>$/.exec(field.type);
  if (field.label === 'repeated') {
    if (types.length !== 1 || types[0] !== 'array') {
      push(yamlLine(schema, 'type'), `type is ${constraintText(schema.type)}, expected "array" for repeated proto field ${site}`);
    } else if (schema.items === undefined) {
      push(yamlLine(schema, 'type'), `items is absent; repeated proto field ${site} needs an item schema`);
    } else {
      compareTwinValue(tc, owner, field, field.type, { schema: schema.items, at: `${twin.at}[]`, line: yamlLine(schema, 'items') }, stack);
    }
  } else if (map) {
    if (types.length !== 1 || types[0] !== 'object' || !isObject(schema.additionalProperties)) {
      push(
        yamlLine(schema, 'additionalProperties') ?? yamlLine(schema, 'type'),
        `expected an object whose additionalProperties is the value schema of map proto field ${site}`,
      );
    } else {
      const value = { schema: schema.additionalProperties, at: `${twin.at}{*}`, line: yamlLine(schema, 'additionalProperties') };
      compareTwinValue(tc, owner, field, map[1], value, stack);
    }
  } else {
    compareTwinValue(tc, owner, field, field.type, { schema, at: twin.at, line: twin.line }, stack);
  }
}

/**
 * Compares one value of a proto field (the field itself, a repeated field's element or
 * a map's value) of proto type `type` with its REST schema { schema, at, line }: a
 * message ↔ an object compared field by field, an enum ↔ string, a scalar ↔ the
 * PROTO_SCALAR_JSON_TYPES of its type.
 */
function compareTwinValue(tc, owner, field, type, value, stack) {
  const schema = deref(tc.doc, value.schema, tc.rel, '', []);
  if (!isObject(schema)) return;
  const scalar = PROTO_SCALAR_JSON_TYPES.get(type);
  const resolved = scalar ? null : resolveProtoType(type, owner.file, tc.typeFiles);
  if (resolved?.kind === 'message') {
    const key = `${resolved.file.rel}#${resolved.message.name}`;
    if (!stack.includes(key)) compareTwinMessage(tc, resolved, schema, value.at, value.line, [...stack, key]);
    return;
  }
  const allowed = scalar ?? (resolved?.kind === 'enum' ? ['string'] : null);
  if (!allowed) return;
  const types = schemaTypeList(schema).filter((t) => t !== 'null');
  if (types.length === 0 || types.some((t) => !allowed.includes(t))) {
    tc.violations.push({
      path: tc.rel,
      line: yamlLine(schema, 'type') ?? value.line,
      message:
        `${tc.where}: ${value.at}: type is ${constraintText(schema.type)}, expected ${allowed.map((t) => `"${t}"`).join(' or ')} ` +
        `for proto ${type} field ${protoFieldSite(owner, field)}`,
    });
  }
}

/**
 * Compares an RPC's request message with its REST operation's path and query
 * parameters and request body properties, by JSON name in both directions. Header and
 * cookie parameters travel as gRPC metadata, not as message fields, so they have no twin.
 */
function compareTwinRequest(tc, owner, located) {
  const opLine = yamlLine(located.operation, 'parameters') ?? yamlLine(located.operation);
  const push = (line, text) => tc.violations.push({ path: tc.rel, line: line ?? opLine, message: `${tc.where}: ${text}` });
  const twins = new Map();
  for (const param of effectiveParameters(tc.doc, tc.rel, tc.where, located, []).values()) {
    if (param.in !== 'path' && param.in !== 'query') continue;
    twins.set(param.name, {
      schema: param.schema,
      required: param.in === 'path' || param.required === true,
      requiredLine: yamlLine(param, 'required') ?? yamlLine(param),
      at: `${param.in} parameter ${param.name}`,
      line: yamlLine(param, 'schema') ?? yamlLine(param),
    });
  }
  const rawBody = located.operation.requestBody;
  const body = rawBody === undefined ? undefined : deref(tc.doc, rawBody, tc.rel, '', []);
  const media = isObject(body?.content) ? body.content[JSON_MEDIA] : undefined;
  const bodySchema = isObject(media) && media.schema !== undefined ? deref(tc.doc, media.schema, tc.rel, '', []) : undefined;
  if (isObject(bodySchema)) {
    const types = schemaTypeList(bodySchema).filter((t) => t !== 'null');
    if (types.length !== 1 || types[0] !== 'object') {
      push(
        yamlLine(bodySchema, 'type') ?? yamlLine(media, 'schema'),
        `request body: type is ${constraintText(bodySchema.type)}, expected "object" for proto message ${protoMessageSite(owner)}`,
      );
    }
    const props = isObject(bodySchema.properties) ? bodySchema.properties : {};
    const required = new Set(Array.isArray(bodySchema.required) ? bodySchema.required.map(String) : []);
    for (const [name, schema] of Object.entries(props)) {
      twins.set(name, {
        schema,
        required: body.required === true && required.has(name),
        requiredLine: yamlLine(bodySchema, 'required') ?? yamlLine(body, 'required'),
        at: `request body.${name}`,
        line: yamlLine(props, name),
      });
    }
  }
  const stack = [`${owner.file.rel}#${owner.message.name}`];
  for (const field of owner.message.fields) {
    const twin = twins.get(field.json);
    if (twin) compareTwinField(tc, owner, field, twin, stack);
    else push(opLine, `request lacks a parameter or body property ${field.json} for proto field ${protoFieldSite(owner, field)}`);
  }
  const names = new Set(owner.message.fields.map((f) => f.json));
  for (const [name, twin] of twins) {
    if (!names.has(name)) push(twin.line, `${twin.at} has no field in proto request message ${protoMessageSite(owner)}`);
  }
}

/**
 * Holds every baseline RPC and its REST operation to twin agreement, field by field,
 * added fields included: the request message with the parameters and request body, the
 * response message with the 200 application/json schema. Also holds the types of
 * cardservicing.common.v1.ErrorObject's fields to every document's ErrorObject (name
 * parity there is checkErrorObjects'). A twin difference is reported at the REST site,
 * since each OpenAPI operation is the HTTP/JSON form of its RPC.
 */
function checkTwinAgreement(protos, typeFiles, docs, violations) {
  for (const iface of INTERFACES) {
    const proto = protos.get(iface.proto);
    const doc = docs.get(iface.openapi);
    const service = proto?.services.get(iface.service);
    if (!service || !doc) continue;
    const self = typeFiles.get(iface.proto);
    for (const op of iface.operations) {
      const rpc = service.rpcs.find((r) => r.name === op.rpc);
      const located = locateBaselineOperation(doc, iface.openapi, op);
      if (!rpc || !located) continue;
      const where = `${op.method.toUpperCase()} ${op.path} (${op.rpc})`;
      const tc = { typeFiles, doc, rel: iface.openapi, where, violations, presence: true, names: true };
      const request = resolveProtoType(rpc.requestType, self, typeFiles);
      if (request?.kind === 'message') compareTwinRequest(tc, request, located);
      const response = resolveProtoType(rpc.responseType, self, typeFiles);
      const responses = located.operation.responses;
      const raw = isObject(responses) ? responses['200'] : undefined;
      const success = raw === undefined ? undefined : deref(doc, raw, iface.openapi, '', []);
      const media = isObject(success?.content) ? success.content[JSON_MEDIA] : undefined;
      const schema = isObject(media) && media.schema !== undefined ? deref(doc, media.schema, iface.openapi, '', []) : undefined;
      if (response?.kind === 'message' && isObject(schema)) {
        const stack = [`${response.file.rel}#${response.message.name}`];
        compareTwinMessage(tc, response, schema, '200 body', yamlLine(media, 'schema'), stack);
      }
    }
  }
  const common = protos.get(COMMON_PROTO);
  const errorObject = common?.messages.get('ErrorObject');
  if (!errorObject) return;
  const owner = { message: errorObject, file: typeFiles.get(COMMON_PROTO) };
  for (const [rel, doc] of docs) {
    const schema = isObject(doc.components?.schemas) ? doc.components.schemas.ErrorObject : undefined;
    if (!isObject(schema)) continue;
    const tc = { typeFiles, doc, rel, where: 'standard error object', violations, presence: false, names: false };
    compareTwinMessage(tc, owner, schema, 'ErrorObject', yamlLine(schema), [`${COMMON_PROTO}#ErrorObject`]);
  }
}

/**
 * The fixed baseline field contracts and the proto/REST twin agreement: every proto
 * against PROTO_BASELINE, every baseline operation and ErrorObject against REST_BASELINE
 * and ERROR_OBJECT_BASELINE, and every baseline RPC against its REST twin.
 */
function checkBaselineContracts(protos, typeFiles, docs, violations) {
  checkProtoBaseline(protos, violations);
  checkRestBaseline(docs, violations);
  checkTwinAgreement(protos, typeFiles, docs, violations);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

/**
 * The violations with each (path, line, message) once, first occurrence kept. A YAML
 * syntax error makes @stoplight/yaml (in loadYaml) and Spectral's parser rule report a
 * diagnostic at each column it disturbs, and a violation keeps only the line, so
 * several of one line's diagnostics would print as the same line. A tool finding whose
 * message was clipped is compared by its `unclipped` text, so distinct findings never
 * merge.
 */
function distinctViolations(violations) {
  const seen = new Set();
  return violations.filter((v) => {
    const key = JSON.stringify([v.path, v.line ?? null, v.unclipped ?? v.message]);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** --help text. No line starts with `check-contracts:`, so none reads as a violation. */
const USAGE = `Usage: node tests/validation/check-contracts.mjs [--help]

Validates card-servicing-contracts/buf.yaml and .spectral.yaml, then runs buf lint over
both proto roots and Spectral over the six OpenAPI 3.1 documents, and checks that the
fourteen Stub Contract Baseline operations are present under their RPC names, methods and
paths, that the baseline field contracts hold and each RPC agrees with its REST twin, that
every RPC and operation carries real, valid examples and the standard error object, and
that ErrorObject is identical across the OpenAPI documents and mirrors common.proto.
Content violations print as path:line.

YAML is read under the core schema; a document over ${YAML_MAX_CHARS / (1024 * 1024)} MiB, nested deeper than ${YAML_MAX_DEPTH} levels
or holding an alias or an !include is refused. Each stop is a violation: one example's
schema evaluation after ${SCHEMA_STEP_BUDGET} steps, a pattern match after ${PATTERN_TIMEOUT_MS / 1000} s (${PATTERN_RUN_BUDGET_MS / 1000} s for all of a run),
buf lint after ${TOOL_TIMEOUT_MS / 1000} s, Spectral after ${TOOL_TIMEOUT_MS / 1000} s per document, and either tool once it prints
more than ${TOOL_OUTPUT_MAX_BYTES / (1024 * 1024)} MiB. SIGINT, SIGTERM or SIGHUP while a tool runs kills the tool's process
group and exits 128 + the signal number.

Runs from any working directory: every path resolves against the repository root.
Takes no argument other than -h or --help, which prints this text.

TMPDIR needs no setting. This check starts no browser and runs only the local buf and
Spectral from tests/validation/node_modules/.bin, so any TMPDIR works, long paths
included. TMPDIR matters to the suite only for check-diagrams: the Chrome that mmdc
starts there aborts with "Socket path too long" under a long TMPDIR.

Exit status:
  0        no violation
  1        one or more violations, one line each, then the count line
  2        usage, setup or internal error, such as an unknown argument or no YAML parser
  128 + n  stopped by signal n (SIGHUP, SIGINT or SIGTERM) while buf or Spectral ran
`;

async function main() {
  const args = process.argv.slice(2);
  const unknown = args.find((arg) => arg !== '-h' && arg !== '--help');
  if (unknown !== undefined) {
    console.error(`${CHECK}: unknown argument "${escapeControls(unknown)}"; run with --help for usage`);
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

  // Every existing proto reached through no symbolic link, parsed once: root-relative
  // path → parseProto model. A linked proto is left out, as a missing one is; its link
  // violation stands for it.
  const protos = new Map();
  for (const rel of [COMMON_PROTO, ...INTERFACES.map((i) => i.proto)]) {
    if (!linked(rel) && exists(rel)) protos.set(rel, parseProto(readText(rel)));
  }
  // The same protos as type scopes, built once for every type resolution of the run.
  const typeFiles = protoTypeFiles(protos);

  const parseYaml = resolveYamlParser();
  const shared = checkProtos(protos, typeFiles, violations);

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
    if (bufBlocked.length === 0) await runBuf(violations);
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
      await runSpectral(lintable, violations, notes);
    }

    const docs = new Map();
    for (const { iface, doc } of loaded) {
      if (!isObject(doc)) {
        violations.push({ path: iface.openapi, line: 1, message: 'document is not a YAML mapping' });
        continue;
      }
      if (!/^3\.1\.\d+$/.test(String(doc.openapi ?? ''))) {
        violations.push({
          path: iface.openapi,
          line: yamlLine(doc, 'openapi'),
          message: `openapi is "${doc.openapi ?? ''}", expected 3.1.x`,
        });
      }
      docs.set(iface.openapi, doc);
      checkBaselineOperations(doc, iface, violations);
      for (const operation of operationsOf(doc, iface.openapi, violations)) {
        checkOperation(doc, iface.openapi, operation, violations);
      }
    }
    checkRpcErrorStatuses(protos, docs, violations);
    checkErrorObjects(docs, shared, violations);
    checkBaselineContracts(protos, typeFiles, docs, violations);
  }

  // `<check> (note): …` can never be read as a `<check>: <path>: <message>` violation line.
  for (const note of notes) console.log(`${CHECK} (note): ${escapeControls(relativizeHostPaths(note))}`);
  report(CHECK, distinctViolations(violations));
  // report() sets exit 0 or 1; a setup failure overrides it after the count is printed.
  if (setupFailure) process.exitCode = 2;
}

try {
  await main();
} catch (err) {
  console.error(`${CHECK}: internal error: ${formatError(err)}`);
  process.exitCode = 2;
}

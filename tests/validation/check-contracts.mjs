// SPDX-License-Identifier: Apache-2.0
/**
 * check-contracts — buf, Spectral and contract-content check for the Card Servicing
 * interfaces. run-all.sh runs it fifth, from any working directory, as
 * `node tests/validation/check-contracts.mjs`.
 *
 * Passes when every listed interface has its `.proto` and OpenAPI 3.1 document,
 * `buf lint` and Spectral report zero errors, the fourteen Stub Contract Baseline
 * operations are present under their baseline RPC names, REST methods and paths,
 * every RPC and OpenAPI operation carries examples and the standard error object,
 * and the ErrorObject schema is identical across the six OpenAPI documents and
 * mirrors cardservicing.common.v1.ErrorObject in camelCase.
 *
 * A failing check is fixed in the contracts, never by weakening this script.
 * Output: one line per violation and a final count (lib/markdown.mjs report);
 * exit 0 with no violation, 1 with any, 2 on an internal error.
 */
import { spawnSync } from 'node:child_process';
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

/**
 * Resolves a YAML parser from the locked tool tree: @stoplight/yaml (Spectral's own
 * parser), else js-yaml (hoisted by the mermaid toolchain). Never add a YAML package
 * to package.json for this: the manifest pins exactly the three validation tools.
 */
function resolveYamlParser() {
  const require = createRequire(new URL('./package.json', import.meta.url));
  try {
    const { parse } = require('@stoplight/yaml');
    if (typeof parse === 'function') return (text) => parse(text);
  } catch {
    // Not resolvable from tests/validation; js-yaml is tried next.
  }
  try {
    const { load } = require('js-yaml');
    if (typeof load === 'function') return (text) => load(text);
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
  const res = spawnSync(bin, ['lint', '--config', BUF_CONFIG], {
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
 * Parses the first JSON array in a tool's output. Spectral appends a plain-text line
 * to its JSON (for example `[]No results with a severity of 'error' found!`) and may
 * print a banner before it, so each `[` is tried in turn and the array's end is found
 * by bracket matching outside string literals.
 */
function extractJsonArray(text) {
  const src = String(text ?? '');
  for (let start = src.indexOf('['); start >= 0; start = src.indexOf('[', start + 1)) {
    const end = balancedEnd(src, start);
    if (end < 0) return null;
    try {
      const parsed = JSON.parse(src.slice(start, end + 1));
      if (Array.isArray(parsed)) return parsed;
    } catch {
      // Not JSON from this bracket; a banner such as "[info]" precedes the array.
    }
  }
  return null;
}

/** Index of the bracket closing the one at `start`, or -1 when it never closes. */
function balancedEnd(src, start) {
  let depth = 0;
  let inString = false;
  for (let i = start; i < src.length; i++) {
    const ch = src[i];
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '[' || ch === '{') depth++;
    else if (ch === ']' || ch === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function firstLines(...texts) {
  const lines = texts.join('\n').split(/\r?\n/).map((l) => l.trim()).filter((l) => l !== '');
  return lines.length === 0 ? '(no output)' : lines.slice(0, 5).join(' | ');
}

function runSpectral(files, violations, notes) {
  const bin = toolBin('spectral');
  if (!bin) {
    violations.push({
      path: PACKAGE_JSON,
      message: 'spectral is not installed in tests/validation/node_modules/.bin; run `npm ci` in tests/validation',
    });
    return;
  }
  let warnings = 0;
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
    const results = extractJsonArray(res.stdout);
    if (results === null) {
      if (res.status !== 0) {
        violations.push({
          path: rel,
          message: `spectral lint exited with ${res.status ?? res.signal}: ${firstLines(res.stdout, res.stderr)}`,
        });
      } else {
        notes.push(`${rel}: spectral output was not JSON, so its warnings were not counted`);
      }
      continue;
    }
    let errors = 0;
    for (const r of results) {
      if (r?.severity === 0) {
        errors++;
        const file = typeof r.source === 'string' ? repoRelative(r.source) ?? rel : rel;
        const line = Number.isInteger(r.range?.start?.line) ? r.range.start.line + 1 : undefined;
        violations.push({ path: file, line, message: `spectral ${r.code}: ${r.message}` });
      } else if (r?.severity === 1) {
        warnings++;
      }
    }
    if (res.status !== 0 && errors === 0) {
      const status = res.status ?? res.signal;
      violations.push({
        path: rel,
        message: `spectral lint exited with ${status} without an error result: ${firstLines(res.stderr)}`,
      });
    }
  }
  if (warnings > 0) {
    notes.push(`spectral reported ${warnings} warning(s) across the OpenAPI documents; warnings are not violations`);
  }
}

/**
 * Zero linter errors prove nothing when the configuration lints nothing, so the buf
 * configuration must cover both proto roots with STANDARD and COMMENTS, and the
 * Spectral ruleset must extend spectral:oas.
 */
function checkLintConfigs(parseYaml, violations) {
  const buf = loadYaml(BUF_CONFIG, parseYaml, violations);
  if (buf !== undefined) {
    if (String(buf?.version ?? '') !== 'v2') {
      violations.push({ path: BUF_CONFIG, message: `version is "${buf?.version ?? ''}", expected "v2"` });
    }
    const modules = Array.isArray(buf?.modules) ? buf.modules.map((m) => String(m?.path ?? '')) : [];
    for (const root of [CONTRACTS_PROTO_ROOT, SERVICE_PROTO_ROOT]) {
      if (!modules.includes(root)) violations.push({ path: BUF_CONFIG, message: `modules lack path ${root}` });
    }
    const use = Array.isArray(buf?.lint?.use) ? buf.lint.use.map(String) : [];
    for (const category of ['STANDARD', 'COMMENTS']) {
      if (!use.includes(category)) violations.push({ path: BUF_CONFIG, message: `lint.use lacks ${category}` });
    }
  }
  const ruleset = loadYaml(SPECTRAL_RULESET, parseYaml, violations);
  if (ruleset !== undefined) {
    const ext = ruleset?.extends;
    const names = (Array.isArray(ext) ? ext : [ext]).map((e) => (Array.isArray(e) ? e[0] : e));
    if (!names.includes('spectral:oas')) {
      violations.push({ path: SPECTRAL_RULESET, message: 'extends does not include "spectral:oas"' });
    }
  }
}

/** Parses a root-relative YAML file; undefined (with a violation) when absent or unparseable. */
function loadYaml(rel, parseYaml, violations) {
  if (!exists(rel)) {
    violations.push({ path: rel, message: 'expected file is missing' });
    return undefined;
  }
  try {
    return parseYaml(readText(rel)) ?? null;
  } catch (err) {
    violations.push({ path: rel, message: `YAML does not parse: ${String(err?.message ?? err).split('\n')[0]}` });
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
 */
function checkProtos(violations) {
  for (const iface of INTERFACES) {
    if (!exists(iface.proto)) continue;
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
  if (!exists(COMMON_PROTO)) return shared;
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

function main() {
  const violations = [];
  const notes = [];

  const expected = [COMMON_PROTO, ...INTERFACES.flatMap((i) => [i.proto, i.openapi])];
  for (const rel of expected) {
    if (!exists(rel)) violations.push({ path: rel, message: 'expected contract file is missing' });
  }
  const openapiFiles = INTERFACES.map((i) => i.openapi).filter((rel) => exists(rel));

  runBuf(violations);
  runSpectral(openapiFiles, violations, notes);

  const shared = checkProtos(violations);

  const parseYaml = resolveYamlParser();
  if (!parseYaml) {
    violations.push({ path: PACKAGE_JSON, message: 'no YAML parser resolvable (@stoplight/yaml or js-yaml)' });
  } else {
    checkLintConfigs(parseYaml, violations);
    const docs = new Map();
    for (const iface of INTERFACES) {
      if (!exists(iface.openapi)) continue;
      const doc = loadYaml(iface.openapi, parseYaml, violations);
      if (doc === undefined) continue;
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

  for (const note of notes) console.log(`${CHECK}: note: ${note}`);
  report(CHECK, violations);
}

try {
  main();
} catch (err) {
  console.error(`${CHECK}: internal error: ${err?.stack ?? err}`);
  process.exitCode = 2;
}

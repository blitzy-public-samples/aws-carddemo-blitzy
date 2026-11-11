// SPDX-License-Identifier: Apache-2.0
/**
 * check-backlog-structure — backlog structure and story content gate.
 *
 * Run second by tests/validation/run-all.sh as `node tests/validation/check-backlog-structure.mjs`.
 * It reads every file under docs/backlog/epics/ and nothing else, prints one line per
 * violation and the final count through report(), and exits 0 with no violation, 1 with
 * any, and 2 on a usage error (any argument but --help or -h) or an internal error. It
 * takes no arguments; --help or -h prints the usage and exits 0 without scanning. A
 * failing predicate is fixed in the backlog artifact, never by relaxing the predicate.
 *
 * Predicate groups and the gate each one implements:
 *  - S (paths, epic index, epic README records, story part order and outer boundary): the
 *    Validation Gate — structure of "Epic and Story Structure — Multi-File Variant". Only
 *    its three path forms exist under the epics area, every epic folder follows one
 *    identifier convention, carries a README and no subfolder but stories/, the epic index
 *    is a single table listing every epic README exactly once whose ID, Name and Folder
 *    cells equal the linked epic's folder identifier, README title name and folder, every
 *    epic README links every file in its stories/ folder and ends with "Owner: TBD" then
 *    "Status: TBD", a story's title KEY equals its filename identifier, its parent epic line
 *    carries the epic's README title name and links its README, and story parts follow the
 *    rule's order: one level-1 heading (the title), only blank lines between the parent epic
 *    line and the source body, no metadata field record before the metadata block (the
 *    Base rule's ban on scattered metadata fields) and nothing after it. The request's
 *    sections 4–7 sit between the reproduced source body and Implementation Design, the
 *    reproduced source body holds no heading but its three section headings, and the epic
 *    README holds the request's four epic sections inside the rule's record (the resolved
 *    backlog structure), each with content: a business justification, requirements as list
 *    items carrying their traces, criteria numbered 1..n each with text, and dependencies
 *    naming each prerequisite epic with its producing story edges, or "N/A".
 *  - A (story statement; trace CARD_REQ_TBD · Use Cases · <program> or CARD_REQ_TBD · Data
 *    Layouts · <copybook> · <field>; section 1 subparts in order, with Capability naming a
 *    target path and In scope and Not in this story bullets; section 3 prerequisites with
 *    reasons and one Risk line; classification lines checked against each story's canonical
 *    role; AC1–ACn written "- ACn: <criterion>" with positive numbers, no gap and no
 *    malformed criterion line, each stating an expected outcome through an outcome verb or
 *    a comparison; an authorization criterion requiring a non-negated 403 with
 *    PERMISSION_DENIED for an account outside the caller's entitlements and an affirmed
 *    rule that no account data is read before access is allowed; the timeout, two-retry
 *    cap, 50/100 ms backoff, retried statuses, retry fit, deadline and per-call degrade or
 *    fail-hard obligations, each affirmed within one clause): the request's story and
 *    acceptance-criteria pass/fail lines, with AC1–ACn numbering from "Enforce
 *    Implementation Plan, Epic and Story Structure — Base". Whether a criterion's outcome
 *    is observable in practice, and whether a story's criteria cover valid input, invalid
 *    input, error conditions and boundaries, stays with human review.
 *  - D (quoted source body equal to its creation-time record character for character,
 *    trailing whitespace and blank-line counts included, SCOPE-QUESTION notes and the
 *    blank lines beside them excepted; Design links to a diagram, an anchored data-model
 *    section and each interface's .proto with its OpenAPI twin, the epic's own interface
 *    among them,
 *    or a "Contracts: N/A" line outside the endpoint epics; one Test Plan record per suite,
 *    opening with its applicability and stating its bounded thresholds and conditions, the
 *    Performance record with p50, p95 and p99 as one list below 1,000 ms, no percentile
 *    bound to another figure and the load as one phrase;
 *    Implementation Design parts in order: framing line,
 *    rationale rejecting the other option, at most one Mermaid sequence diagram, a shared
 *    block with its eight topics, Design Option A and Design Option B each with a summary,
 *    How bullets, a decisions table and an effort and risk line, and the Recommendation
 *    records; one negation-aware recommended option ("Reject Option B; choose Option A"
 *    names A), named alike by the framing line and the Recommendation; per-option settled
 *    and open assumptions with a verdict for every AC): the Validation Gate — content of
 *    "Enforce Implementation Plan, Epic and Story Structure — Base", plus the request's
 *    Design and Test Plan pass/fail lines.
 *  - Voice (no link, code span, path or line-number citation, code identifier, SQL in any
 *    letter case, config key or maturity tag in Implementation Design and Design
 *    Validation, escaped punctuation read as its rendered character, in prose, in
 *    non-Mermaid fences, each itself a violation, and in the Mermaid diagram's participant
 *    labels, undeclared participant names and message, note and block label text; table
 *    cells with no semicolon, second sentence or second clause that a comma conjunction
 *    outside a short series, a conjunction before a pronoun subject or a conjunction
 *    between two clause openings starts): the design voice requirements and
 *    content gate of "Enforce Implementation Plan, Epic and Story Structure — Base".
 *  - M (metadata block last holding each of its 20 fields exactly once and no other field,
 *    no blank value, Issue Type Story and Priority High as the request supplies them, TBD
 *    in every field no input supplies, integer Story Points of 5 or less, integer
 *    Estimated Files Touched, Build wave, Epic Name, Epic Link, and Depends on as None or a
 *    comma-separated list of distinct identifiers of other existing stories): the metadata
 *    requirements and content gate of "Enforce Implementation Plan, Epic and Story
 *    Structure — Base".
 * The plan-side half of that content gate is check-plan-consistency.mjs.
 */
import crypto from 'node:crypto';
import {
  readText,
  pathKind,
  listEntries,
  fencedBlocks,
  hiddenLines,
  blankComments,
  parseHeadings,
  sections,
  sectionByHeading,
  stripBlockquote,
  parseTables,
  isTotalsRow,
  surplusCellRows,
  parseLinks,
  resolveLink,
  linkInCell,
  metadataEntries,
  intStrict,
  intAboveSafe,
  plainText,
  unescapeInline,
  report,
  escapeControls,
  formatError,
} from './lib/markdown.mjs';

const CHECK = 'check-backlog-structure';
const EPICS_DIR = 'docs/backlog/epics';
const INDEX_README = `${EPICS_DIR}/README.md`;
const PATH_FORMS = `${EPICS_DIR}/README.md, ${EPICS_DIR}/<epic-folder>/README.md or ${EPICS_DIR}/<epic-folder>/stories/<story-file>.md`;

// The backlog inventory is fixed at 13 epics (CS-01 to CS-13) and 58 stories.
const EPIC_IDS = Array.from({ length: 13 }, (_, i) => `CS-${String(i + 1).padStart(2, '0')}`);
const STORY_COUNT = 58;

const EPIC_FOLDER_RE = /^CS-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/;
const STORY_FILE_RE = /^CS-(\d{2})-(\d{3})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$/;
const DEPENDS_ON_ID_RE = /^CS-\d{2}-\d{3}$/;
const EPIC_TITLE_RE = /^(CS-\d{2}): (.+)$/;
const STORY_TITLE_RE = /^# (CS-\d{2}-\d{3}): (.+)$/;
const PARENT_EPIC_RE = /^Parent epic: \[(CS-\d{2}) ([^\]]+)\]\(([^)]+)\)$/;
const STATEMENT_RE = /^As an? .+, I want .+, so that .+\.$/;
// With no Requirements Analysis, a trace carries CARD_REQ_TBD and cites a Use Cases program
// or a Data Layouts copybook and field, each anchor a CardDemo name.
const TRACE_RE = /^Trace: CARD_REQ_TBD · (?:Use Cases · [A-Z0-9][A-Z0-9-]*|Data Layouts · [A-Z0-9][A-Z0-9-]* · [A-Z0-9][A-Z0-9-]*)$/;
const ACCESS_LABEL = 'Account-data access path:';
const ACCESS_RE = /^Account-data access path: (Yes|No)$/;
const EXTERNAL_LABEL = 'External integration or SLA-bound:';
const EXTERNAL_RE = /^External integration or SLA-bound: (Yes|No)$/;
// An acceptance criterion starts "- AC<n>" at the left margin of section 2 once quote
// markers are removed; its full form "- AC<n>: <criterion>" is checked per entry.
const AC_START_RE = /^- AC(\d+)(?!\w)/;
// Any other line opening with an AC label: leftover indentation or quote markers, an
// optional bullet (ASCII, Unicode dash, bullet or middle dot, or a list number), optional
// emphasis or code markers, then "AC" in any case with an optional separator before its
// digits. `sep` is set when a label separator or the line end follows the digits. Each
// part is bounded by a different character class, so matching stays linear in the line.
const AC_LIKE_RE = /^(?<lead>[\s>]*)(?:(?<bullet>[-*+\u2010-\u2015\u2212\u2022\u2023\u2043\u00b7\u2219\u25aa\u25e6]|\d+[.)])\s*)?(?:(?<emphasis>[*_`~]+)\s*)?AC(?:\s*[-\u2010-\u2015\u2212_.#])?\s*\d+[*_`~]*\s*(?<sep>[:\uff1a.)\]]|[-\u2010-\u2015\u2212](?!\s*AC)|$)?/iu;
// A SCOPE-QUESTION note line in section 2, after any leftover indentation or quote markers.
const AC_NOTE_START_RE = /^[\s>]*SCOPE-QUESTION\b/;
// One left-to-right scan of design prose: group 1 is an option mention ("Option A" or
// "Design Option B"), group 2 a contrast cue, group 3 a clause boundary (a semicolon, or a
// sentence end before space or line end), group 4 a negated auxiliary ("cannot", "isn't",
// "won't"), which is a contrast cue carrying its own auxiliary, and group 5 a copula or
// auxiliary word ("is", "was", "would", "stays", "then"), which links a mention to a
// contrast cue that follows it ("Option B is rejected").
const OPTION_MENTION_RE =
  /\b(?:Design )?Option ([AB])\b|\b([Rr]eject(?:s|ed)?|[Nn]ot|[Nn]ever|[Ii]nstead of|[Rr]ather than|[Oo]ver|[Vv]s|[Vv]ersus|[Aa]gainst|[Uu]nlike)\b|(;|[.!?](?=\s|$))|\b([Cc]annot|(?:[Ii]s|[Aa]re|[Ww]as|[Ww]ere|[Dd]oes|[Dd]o|[Dd]id|[Hh]as|[Hh]ave|[Hh]ad|[Ww]ould|[Ss]hould|[Cc]ould|[Mm]ust|[Cc]a|[Ww]o)n['’]t)\b|\b(is|was|are|were|be|been|being|stays?|stayed|remains?|remained|gets?|got|has|have|had|does|did|do|will|would|shall|should|can|could|may|might|must|then)\b/g;
// A design option heading: "Design Option A" alone, or followed by " — ", " – ", " - " or ":" and a name.
const OPTION_HEADING_RE = /^Design Option ([AB])(?:$| [—–-] \S|:\s*\S)/;
const BULLET_RE = /^[ \t]*[-*+](?:[ \t]+|$)/;
const HEADING_LINE_RE = /^ {0,3}#{1,6}(?:[ \t]|$)/;
const SHARED_LABEL = 'Shared by both options';
const SHARED_TOPICS = ['Topology', 'Naming', 'Addressing', 'Isolation', 'Fallback', 'Provisioning', 'Cut-over', 'Failures'];
const HOW_LABELS = ['How it behaves:', 'How it differs:'];
const DECISION_HEADER = ['No.', 'Decision (vs rejected)', 'Pro', 'Con'];
const EFFORT_LABEL = 'Effort and risk:';
// Each Recommendation record by its accepted labels; the first label names the record.
const RECOMMENDATION_RECORDS = [
  ['Option:'],
  ['Why:'],
  ['Strongest argument against:'],
  ['Shared items that do not decide the choice:'],
  ['Flip conditions:', 'Flip condition:'],
];

const EPIC_SECTIONS = ['Goal', 'Business Justification', 'Requirements', 'Acceptance Criteria', 'Dependencies', 'Stories'];
const STORY_SECTIONS = [
  'Source Story (verbatim)',
  '4. Design',
  '5. Documentation Changes',
  '6. Test Plan',
  '7. Validation Results',
  'Implementation Design',
  'Design Validation',
  'Metadata',
];
const BODY_SECTIONS = ['1. Details/Requirements', '2. Acceptance Criteria', '3. Dependencies'];
const METADATA_FIELDS = [
  'Build wave',
  'Depends on',
  'Status',
  'Estimated Files Touched',
  'Issue Type',
  'Epic Name',
  'Epic Link',
  'Story Points',
  'Priority',
  'Components',
  'Fix Version/s',
  'Team',
  'Assignee',
  'Reporter',
  'Labels',
  'Sprint',
  'Due Date',
  'Environment',
  'Persona',
  'Estimated Effort',
];
// The metadata requirements of "Enforce Implementation Plan, Epic and Story Structure —
// Base" carry each input value and write TBD where no input supplies one. The request
// header supplies Issue Type and Priority; it names no workflow state, component, version,
// team or person, so these fields are TBD in every story.
const SUPPLIED_METADATA = new Map([
  ['Issue Type', 'Story'],
  ['Priority', 'High'],
]);
const TBD_METADATA_FIELDS = [
  'Status',
  'Components',
  'Fix Version/s',
  'Team',
  'Assignee',
  'Reporter',
  'Labels',
  'Sprint',
  'Due Date',
  'Environment',
  'Persona',
  'Estimated Effort',
];
const VERDICTS = new Set(['Holds', 'Conditional', 'At risk', 'Breaks']);
const WAVES = new Set(['W0', 'W1', 'W2', 'W3']);
const OPENAPI_DIR = 'card-servicing-service/src/main/resources/openapi/';
const SERVICE_PROTO_DIR = 'card-servicing-service/src/main/proto/cardservicing/';
const DATA_MODEL = 'docs/data-model.md';
// The shared-types proto: every interface uses it, and it has no OpenAPI twin.
const COMMON_PROTO = 'card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto';
// The six interfaces, each described by one .proto and one OpenAPI document (root-relative).
const INTERFACE_CONTRACTS = [
  { name: 'UI-facing API', proto: `${SERVICE_PROTO_DIR}api/v1/card_servicing_service.proto`, openapi: `${OPENAPI_DIR}card-servicing-api.yaml` },
  { name: 'Card Data', proto: `${SERVICE_PROTO_DIR}carddata/v1/card_data_service.proto`, openapi: `${OPENAPI_DIR}card-data-service.yaml` },
  {
    name: 'Transaction Activity',
    proto: `${SERVICE_PROTO_DIR}transactionactivity/v1/transaction_activity_service.proto`,
    openapi: `${OPENAPI_DIR}transaction-activity-service.yaml`,
  },
  { name: 'Config', proto: `${SERVICE_PROTO_DIR}config/v1/config_service.proto`, openapi: `${OPENAPI_DIR}config-service.yaml` },
  { name: 'Reference Data', proto: `${SERVICE_PROTO_DIR}referencedata/v1/reference_data_service.proto`, openapi: `${OPENAPI_DIR}reference-data-service.yaml` },
  { name: 'Auth', proto: `${SERVICE_PROTO_DIR}auth/v1/auth_service.proto`, openapi: `${OPENAPI_DIR}auth-service.yaml` },
];
const CONTRACT_FILES = new Set([COMMON_PROTO, ...INTERFACE_CONTRACTS.flatMap((i) => [i.proto, i.openapi])]);
// Each epic's own interface, taken from the backlog's three epic groupings: CS-01–CS-04, one
// per UI interaction, and CS-05–CS-08, one per UI-facing endpoint, are served by the
// UI-facing API; CS-09–CS-13, one per external interaction, each own that interaction's
// interface. Every story of an endpoint epic serves its endpoint, so it never states
// "Contracts: N/A".
const EPIC_INTERFACES = new Map([
  ...['CS-01', 'CS-02', 'CS-03', 'CS-04', 'CS-05', 'CS-06', 'CS-07', 'CS-08'].map((id) => [id, 'UI-facing API']),
  ['CS-09', 'Card Data'],
  ['CS-10', 'Transaction Activity'],
  ['CS-11', 'Config'],
  ['CS-12', 'Auth'],
  ['CS-13', 'Reference Data'],
]);
const ENDPOINT_EPICS = new Set(['CS-05', 'CS-06', 'CS-07', 'CS-08']);

// Test Plan suite records. A record opens with an unindented list item whose first word,
// after optional bold, is exactly a suite name followed by a colon or a spaced dash, so
// "Unitless" or "Unit-tested" names no suite. Every pattern below is linear-time: only
// character classes and fixed-width groups repeat, every other group is at most optional,
// and no pattern holds a backtracking window. Each number is bounded by the operator before
// it or by a lookbehind, so 180% is not 80% and 11,000 ms is not 1,000 ms.
const SUITES = ['Unit', 'Integration', 'Performance', 'End-to-end'];
const TOP_LEVEL_ITEM_RE = /^(?:[-*+]|\d{1,9}[.)])[ \t]/;
const SUITE_RECORD_RE =
  /^(?:[-*+]|\d{1,9}[.)])[ \t]+(?:\*\*|__)?(Unit|Integration|Performance|End-to-end)(?:\*\*|__)?(?::|[ \t]+[—–-](?=[ \t]))(?:\*\*|__)?[ \t]*(?:\*\*|__)?(.*)$/;
const APPLICABILITY_RE = /^(?:Applies|Not applicable)(?![A-Za-z0-9-])/;
// Per-suite conditions, each [what the record must state, pattern].
const SUITE_CONDITIONS = {
  Unit: [
    ['the ≥80% line coverage threshold (≥80%, >=80% or at least 80%)', /(?:≥|>=|\bat least)[ \t]*80[ \t]*%/],
    ['"line coverage"', /\bline coverage\b/i],
    ['"per module"', /\bper module\b/i],
  ],
  Integration: [
    ['one suite per external epic', /\bone suite (?:per external epic|for the CS-\d{2} external epic)\b/i],
    ['a stub exception', /\bstub exceptions?\b/i],
    ['latency above the timeout', /\blatency above (?:`?UPSTREAM_TIMEOUT_MS\b|the (?:per-attempt )?timeout\b)/i],
    ['the retry cap', /\bretry cap\b|MAX_RETRIES`?[ \t]+cap\b/i],
  ],
  Performance: [
    [
      'p50/p95/p99 below 1,000 ms as one list ("p50, p95 and p99 below 1,000 ms" or "p50/p95/p99 below 1,000 ms")',
      /\bp50(?:\/|,[ \t]+)p95(?:\/|,?[ \t]+and[ \t]+)p99[ \t]+(?:are[ \t]+)?(?:each[ \t]+)?(?:below|under|<)[ \t]*1,?000[ \t]*ms\b/,
    ],
    ['"over successful requests"', /\bsuccessful requests\b/i],
    ['"failure injection off"', /\bfailure injection off\b/i],
    [
      'the load of 10 users × 100 sequential calls as one phrase',
      /(?<![\d,.])10[ \t]+(?:concurrent[ \t]+)?users[ \t]+[×x][ \t]+100[ \t]+(?:sequential[ \t]+)?calls\b/,
    ],
    ['the volume of 100,000 category balances', /(?<![\d,.])100,?000[ \t]+category balances\b/],
    ['the volume of 1,000,000 transactions', /(?<![\d,.])1,?000,?000[ \t]+transactions\b/],
  ],
  'End-to-end': [['the four-step flow', /\bfour-step flow\b/i]],
};
// Injected failures are proven in the Performance or the Integration record: a cut-off at or
// before 900 ms and a client-measured response below 1,000 ms.
const INJECTED_CUTOFF_RE = /(?:\bcutoffMs`?|\bcut-off)[ \t]+(?:≤|<=|at most|at or before)[ \t]*900(?!\d|[.,]\d)/;
const INJECTED_CLIENT_RE = /\bclient-measured[ \t]+response[ \t]+(?:arrives[ \t]+)?(?:below|under|<)[ \t]*1,?000[ \t]*ms\b/i;
// Percentile bounds in the Performance record. One global scan finds, in order, each
// percentile (p50, p95, p99, or any other such as p90 or p99.9) and each latency figure
// with its unit (1,000 ms, 2 s, 1.5 seconds). A percentile is bound to the figure that
// follows it, or follows the list of percentiles it ends ("p50, p95 and p99"), within
// PERCENTILE_TIE_WINDOW characters of the same clause, and to a figure directly before it
// ("the 150 ms p99 trigger"). Every bound is 1,000 ms; the one other bound is p99 at 150 ms,
// the stub ListTransactions trigger for the filtered transaction-list shapes, half the
// 300 ms per-attempt timeout.
const PERCENTILE_FIGURE_RE =
  /(?<![A-Za-z0-9_])p(\d{1,3}(?:\.\d{1,3})?)(?![A-Za-z0-9_]|\.\d)|(?<![\d,.])(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?[ \t]*(ms|milliseconds?|s|secs?|seconds?)(?![A-Za-z0-9_])/g;
const PERCENTILE_JOIN_RE = /^(?:\/|,[ \t]*|,?[ \t]+(?:and|or)[ \t]+)$/;
const FIGURE_BEFORE_PERCENTILE_RE = /^[ \t]+(?:at[ \t]+)?$/;
const CLAUSE_BREAK_RE = /;|[.!?](?:\s|$)|\n[ \t]*(?:[-*+]|\d{1,9}[.)])(?:[ \t]|$)/;
const PERCENTILE_TIE_WINDOW = 40;
const PERCENTILE_BOUND_MS = 1000;
const STUB_TRIGGER_BOUND = { percentile: 'p99', ms: 150 };

// Product and protocol names whose standard spelling carries internal capitals. In plain
// prose they are proper nouns, not code identifiers, so they are removed before the
// camelCase and PascalCase token tests.
const PLAIN_PROPER_NAMES = [
  'gRPC',
  'mTLS',
  'OpenAPI',
  'PostgreSQL',
  'CardDemo',
  'GitHub',
  'JaCoCo',
  'JUnit',
  'AuthN',
  'AuthZ',
  'authN',
  'authZ',
  'macOS',
  'JavaScript',
  'NoSQL',
];
const PROPER_NAME_RE = new RegExp(`(?<![A-Za-z0-9_])(?:${PLAIN_PROPER_NAMES.join('|')})(?![A-Za-z0-9_])`, 'g');

// Links are found by linkSpans, a single-pass scanner. Every pattern below is anchored at a
// token boundary or a literal keyword, so the voice check stays linear in the line length.
// A file name takes the suite's own extensions and the CardDemo source extensions in both
// spellings the CardDemo tree uses, and a line-number citation after it (":281" or
// ":281-301") belongs to the same path token.
const CODE_SPAN_RE = /`[^`]+`/g;
const PATH_RES = [
  /(?<![^\s()[\]<>"'`])[^\s()[\]<>"'`]+\.(?:md|mmd|proto|yaml|yml|json|java|gradle|sql|sh|mjs|toml|txt|properties|cbl|CBL|cpy|CPY|jcl|JCL|bms|BMS|csd|CSD|prc|PRC|ctl|CTL)\b(?::\d+(?:-\d+)?)?/g,
  /(?<![^\s([<"'])\.{1,2}\/[^\s)\]>"']*/g,
  /(?<![^\s([<"'])(?:docs|app|tests|card-servicing-service|card-servicing-contracts|gradle|src|samples|diagrams|\.github)\/[^\s)\]>"']*/g,
  /(?<![^\s(])\/[A-Za-z0-9{}_-]+(?:\/[A-Za-z0-9{}_.-]*)*/g,
];
// SQL in any letter case keeps its statement shape, which plain prose such as "select one
// from the list" lacks: a select list of "*" or comma-separated names, then FROM and a name
// that the line end, a punctuation mark or a clause keyword follows; INSERT INTO a name
// before "(" or VALUES; UPDATE a name SET a name "="; DELETE FROM, CREATE [UNIQUE]
// TABLE|INDEX|VIEW, ALTER TABLE or DROP TABLE|INDEX with the object's name. A WHERE
// condition after a SELECT or DELETE belongs to its statement. The select list is read
// atomically (a lookahead capture replayed by its backreference), so no match backtracks
// into it and each scan stays linear in the line length.
const SQL_NAME = String.raw`[A-Za-z_]\w*(?:\.(?:[A-Za-z_]\w*|\*))?`;
const SQL_WHERE = String.raw`(?:\s+where\s+${SQL_NAME}\s*(?:<>|!=|<=|>=|[=<>]|\bin\b|\blike\b))?`;
const SQL_SHAPE_RE = new RegExp(
  String.raw`\b(?:select\s+(?=(\*|${SQL_NAME}(?:\s*,\s*${SQL_NAME})*))\1\s+from\s+${SQL_NAME}` +
    String.raw`(?=\s*(?:$|[;,.)|]|(?:where|join|inner|left|right|order|group|limit|as)\b))${SQL_WHERE}` +
    String.raw`|insert\s+into\s+${SQL_NAME}(?=\s*\(|\s+values\b)` +
    String.raw`|update\s+${SQL_NAME}\s+set\s+${SQL_NAME}\s*=` +
    String.raw`|delete\s+from\s+${SQL_NAME}${SQL_WHERE}` +
    String.raw`|create\s+(?:unique\s+)?(?:table|index|view)\s+${SQL_NAME}` +
    String.raw`|alter\s+table\s+${SQL_NAME}` +
    String.raw`|drop\s+(?:table|index)\s+${SQL_NAME})`,
  'gi',
);
// Ordered most specific first; each match is masked so one token yields one violation.
// Upper-case statement keywords are SQL on their own: a SELECT or UPDATE runs to its FROM
// or SET without crossing a semicolon, a cell border or the next statement of its kind,
// which keeps each scan bounded by the next keyword. SQL_SHAPE_RE then takes statements in
// any case, an upper-case WHERE is a condition on its own, and column type declarations
// count in any case.
const TOKEN_TESTS = [
  [
    'SQL statement',
    /\b(?:SELECT\b(?:(?!\bSELECT\b)[^;|])*?\bFROM|UPDATE\b(?:(?!\bUPDATE\b)[^;|])*?\bSET|INSERT\s+INTO|DELETE\s+FROM|CREATE\s+(?:UNIQUE\s+)?(?:TABLE|INDEX|VIEW)|ALTER\s+TABLE|DROP\s+(?:TABLE|INDEX))\b(?:\s+[A-Za-z_]\w*)?/g,
  ],
  ['SQL statement', SQL_SHAPE_RE],
  ['SQL condition', /\bWHERE\s+[A-Za-z_][\w.]*\s*(?:<>|!=|<=|>=|[=<>]|\bIN\b|\bLIKE\b)/g],
  ['SQL type definition', /\b(?:VARCHAR|CHAR|NUMERIC|DECIMAL)\s*\(\s*\d+(?:\s*,\s*\d+)?\s*\)/gi],
  ['call form', /\b[A-Za-z_]\w*\(\)/g],
  ['dotted identifier', /\b[A-Za-z_]\w*\.[A-Za-z_]\w*\.[A-Za-z_]\w*\b/g],
  ['UPPER_SNAKE key', /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/g],
  ['snake_case identifier', /\b[a-z][a-z0-9]*_[a-z0-9_]+\b/g],
  ['camelCase identifier', /\b[a-z]+[A-Z][A-Za-z0-9]*\b/g],
  ['PascalCase identifier', /\b[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*\b/g],
  ['maturity tag', /(?<![A-Za-z0-9-])(?:DELIVERED|AS-IS|POC|TO-BE|DESIGNED|UNDECIDED)(?![A-Za-z0-9-])/g],
];
// Mermaid sequence-diagram line forms, matched on the trimmed line; Mermaid keywords are
// case-insensitive. A participant or actor line: its identifier and optional display label.
const MERMAID_PARTICIPANT_RE = /^(?:create\s+)?(?:participant|actor)\s+(\S+)(?:\s+as\s+(.+))?$/i;
// Lines of keywords and identifiers only, with no human-readable text.
const MERMAID_SILENT_RE = /^(?:sequenceDiagram|end|autonumber(?:\s.*)?|(?:activate|deactivate|destroy)\s+\S+)$/i;
// Block and grouping keywords whose remainder is a label.
const MERMAID_LABEL_RE = /^(?:alt|else|opt|loop|par|par_over|and|critical|option|break|rect|box|title)(?:\s+(.*))?$/i;
// Lines whose text follows the first ":" (or the "{" of a multi-line description).
const MERMAID_COLON_TEXT_RE = /^(?:note|links?|properties|details|accTitle|accDescr|title)\b/i;
// A message arrow: ->, -->, ->>, -->>, -x, --x, -), --) and the two-way <<->> and <<-->>.
const MERMAID_ARROW_RE = /-{1,2}(?:>>?|x|\))/;
// The part of a note line before its ":": "Note over A,B", "Note left of A" or "Note right of A".
const MERMAID_NOTE_HEAD_RE = /^note\s+(?:over|left\s+of|right\s+of)\s+(.+)$/i;
const MERMAID_WRAP_RE = /^\s*(?:no)?wrap:/i;
// A comma followed by a coordinating conjunction, which opens a second clause in a cell.
const COMMA_CONJUNCTION_RE = /,\s+(and|but|or|so|yet|nor)\b/g;
// A coordinating conjunction followed by a pronoun subject: "and it expires".
const PRONOUN_CLAUSE_RE = /\b(?:and|but|or|so|yet|nor)\s+(?:it|they|we|he|she|there)\b/;
// A clause opening in any letter case: a determiner, up to two words, a verb ending in "s"
// or "ed", then a determiner or preposition ("the cache holds the", "The source applies both").
const CLAUSE_OPENING =
  String.raw`(?:the|a|an|each|every|its|their|this|that|no|one)\s+(?:[\w-]+\s+){0,2}?[\w-]+(?:s|ed)\s+` +
  String.raw`(?:the|a|an|its|their|every|each|no|one|both|all|any|to|on|at|in|from|with|by|into|for)\b`;
const LEFT_CLAUSE_RE = new RegExp(String.raw`\b${CLAUSE_OPENING}`, 'i');
const RIGHT_CLAUSE_RE = new RegExp(CLAUSE_OPENING, 'iy');
// A conjunction that joins two clauses with no comma between them.
const NOUN_CLAUSE_JOIN_RE = /\b(?:and|but|so|yet)\s+/g;
// Abbreviations whose period ends no sentence, so "e.g. the cache" stays one clause.
const ABBREVIATION_RE = /\b(?:e\.g|i\.e|etc|vs|cf)\./gi;
const QUOTE_PREFIX_RE = /^[ \t]{0,3}(?:>[ \t]?)+/;

const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
// Every whitespace run becomes one space, the text trimmed. The pattern matches only runs
// that are not already one space, so a long single-spaced text is scanned once instead of
// rebuilt one space at a time; its result equals that of replacing /\s+/g with a space.
const normaliseSpace = (s) => String(s ?? '').replace(/ ?[^\S ]\s*| {2,}\s*/g, ' ').trim();

/** Label-line form: no blockquote marker, list marker, bold marker or surrounding backticks. */
function normaliseLabel(line) {
  let s = String(line ?? '').replace(QUOTE_PREFIX_RE, '');
  s = s.replace(/^\s*(?:[-*+]|\d+\.)\s+/, '');
  s = s.replace(/\*\*|__/g, '');
  s = s.trim().replace(/^`+/, '');
  // A backwards scan cuts the trailing run: a /`+$/ search restarts at every backtick of a
  // run that does not end the line, which is quadratic in the run's length.
  let end = s.length;
  while (end > 0 && s[end - 1] === '`') end--;
  return normaliseSpace(s.slice(0, end));
}

/** A table cell or metadata value as plain, whitespace-normalised text. */
const plainCell = (cell) => normaliseSpace(plainText(String(cell ?? '')));

const hasSecondSentence = (s) => /[.!?]\s+\S/.test(String(s).replace(ABBREVIATION_RE, 'abbr'));

const splitLines = (text) => text.split(/\r?\n/);

/**
 * 1-based line numbers covered by fenced code, opening and closing fences included; HTML
 * comment blocks are not in it. The voice scan skips it. Presence and structure scans skip
 * hiddenLines(text) instead, which covers comment blocks too, and the story design reads take
 * what a line says from blankComments, which blanks inline comments as well; the epic README's
 * Owner and Status read skips commentLineSet(text) alone.
 */
function fenceLineSet(text) {
  const set = new Set();
  for (const block of fencedBlocks(text)) {
    for (let l = block.startLine; l <= block.endLine; l++) set.add(l);
  }
  return set;
}

/**
 * 1-based line numbers covered by HTML comment blocks alone, their `<!--` and `-->` lines
 * included: hiddenLines(text) without the fenceLineSet(text) lines. Their text never renders,
 * so scans that still read visible fenced code skip these lines.
 */
function commentLineSet(text) {
  const fences = fenceLineSet(text);
  return new Set([...hiddenLines(text)].filter((l) => !fences.has(l)));
}

/** The level-2 section whose heading text, whitespace-normalised, equals `name`. */
function h2Section(text, name) {
  return sections(text, 2).find((s) => normaliseSpace(s.heading) === name) ?? null;
}

/** Non-quoted headings of a section text with document line numbers. */
function sectionHeadings(section) {
  return parseHeadings(section.text)
    .filter((h) => !h.quoted)
    .map((h) => ({ ...h, docLine: section.startLine + h.line - 1 }));
}

/**
 * The lines from a heading to the line before the next heading of the same or a higher
 * level inside the section, as { text, startLine } with startLine the heading's document line.
 */
function subsection(section, heads, head) {
  const lines = splitLines(section.text);
  const next = heads.find((h) => h.docLine > head.docLine && h.level <= head.level);
  const end = next ? next.docLine - 1 : section.endLine;
  return {
    text: lines.slice(head.docLine - section.startLine, end - section.startLine + 1).join('\n'),
    startLine: head.docLine,
    endLine: end,
  };
}

/**
 * Compares a heading list with the required names and order: reports headings that are
 * missing, unexpected, duplicated or out of order.
 */
function checkHeadingSequence(found, expected, rel, marks, v) {
  const seen = new Set();
  let maxIndex = -1;
  for (const h of found) {
    const name = normaliseSpace(h.text);
    const index = expected.indexOf(name);
    if (index < 0) {
      v.push({ path: rel, line: h.line, message: `unexpected heading "${marks} ${name}"; required, in order: ${expected.join(', ')}` });
      continue;
    }
    if (seen.has(name)) {
      v.push({ path: rel, line: h.line, message: `duplicate heading "${marks} ${name}"` });
      continue;
    }
    seen.add(name);
    if (index < maxIndex) {
      v.push({ path: rel, line: h.line, message: `heading "${marks} ${name}" is out of order; it must precede "${marks} ${expected[maxIndex]}"` });
    } else {
      maxIndex = index;
    }
  }
  for (const name of expected) {
    if (!seen.has(name)) v.push({ path: rel, message: `missing heading "${marks} ${name}"` });
  }
}

/**
 * Group S: path forms, folder and filename conventions, epic and story counts. The epics
 * area is listed with listEntries, which never follows a symbolic link, so only real files
 * reach readText. Every link, whatever its target, and every entry that is neither a file
 * nor a folder is itself stray, and nothing is skipped: a node_modules or .git folder under
 * the epics area is stray too.
 */
function checkPaths(v) {
  const kind = pathKind(EPICS_DIR);
  if (kind !== 'dir') {
    v.push({ path: EPICS_DIR, message: kind === null ? 'epics area is missing' : 'epics area is not a directory' });
    return { files: new Set(), epics: [], storyKeys: new Map() };
  }
  const prefix = `${EPICS_DIR}/`;
  const dirs = [];
  const files = [];
  for (const entry of listEntries(EPICS_DIR)) {
    if (entry.type === 'dir') dirs.push(entry.path);
    else if (entry.type === 'file') files.push(entry.path);
    else if (entry.type === 'symlink') {
      v.push({ path: entry.path, message: `symbolic link under the epics area; only ${PATH_FORMS} may exist there` });
    } else {
      v.push({ path: entry.path, message: `entry that is neither a file nor a folder under the epics area; only ${PATH_FORMS} may exist there` });
    }
  }

  const epicByFolder = new Map();
  const invalidFolders = new Set();
  const dirSet = new Set(dirs);
  for (const d of dirs) {
    const parts = d.slice(prefix.length).split('/');
    if (parts.length === 1) {
      const m = EPIC_FOLDER_RE.exec(parts[0]);
      if (!m) {
        invalidFolders.add(parts[0]);
        v.push({ path: d, message: 'epic folder name does not follow the CS-NN-<slug> convention of the other epic folders (mixed identifier format)' });
        continue;
      }
      epicByFolder.set(parts[0], { folder: parts[0], dir: d, id: `CS-${m[1]}`, num: m[1], readme: `${d}/README.md`, stories: [] });
    } else if (parts.length === 2 && parts[1] !== 'stories') {
      v.push({ path: d, message: 'subfolder other than stories/ inside an epic folder' });
    } else if (parts.length === 3 && parts[1] === 'stories') {
      v.push({ path: d, message: 'subfolder inside a stories/ folder' });
    }
  }

  const fileSet = new Set(files);
  const storyKeys = new Map();
  for (const f of files) {
    const parts = f.slice(prefix.length).split('/');
    if (parts.length === 1 && parts[0] === 'README.md') continue;
    if (parts.length === 2 && parts[1] === 'README.md') continue;
    if (parts.length === 3 && parts[1] === 'stories' && parts[2].endsWith('.md')) {
      const epic = epicByFolder.get(parts[0]);
      if (!epic) continue; // its folder is already reported as off-convention
      const m = STORY_FILE_RE.exec(parts[2]);
      if (!m) {
        v.push({ path: f, message: 'story filename does not follow the CS-NN-NNN-<slug>.md convention of the epic folders' });
        continue;
      }
      if (`CS-${m[1]}` !== epic.id) {
        v.push({ path: f, message: `story filename carries epic number CS-${m[1]} but sits in the ${epic.id} folder` });
      }
      const key = `CS-${m[1]}-${m[2]}`;
      if (storyKeys.has(key)) {
        v.push({ path: f, message: `duplicate story identifier ${key}, also used by ${storyKeys.get(key)}` });
        continue;
      }
      storyKeys.set(key, f);
      epic.stories.push({ rel: f, key, epic });
      continue;
    }
    v.push({ path: f, message: `path is not one of the three permitted forms: ${PATH_FORMS}` });
  }

  if (!fileSet.has(INDEX_README)) v.push({ path: INDEX_README, message: 'epic index README is missing' });

  const epics = [...epicByFolder.values()].sort((a, b) => compare(a.folder, b.folder));
  const foldersById = new Map();
  for (const epic of epics) {
    if (!fileSet.has(epic.readme)) v.push({ path: epic.dir, message: 'epic folder has no README.md' });
    if (!dirSet.has(`${epic.dir}/stories`)) v.push({ path: epic.dir, message: 'epic folder has no stories/ folder' });
    foldersById.set(epic.id, (foldersById.get(epic.id) ?? []).concat(epic.dir));
  }
  for (const [id, folders] of foldersById) {
    if (folders.length > 1) v.push({ path: EPICS_DIR, message: `epic identifier ${id} is used by ${folders.length} folders: ${folders.join(', ')}` });
    if (!EPIC_IDS.includes(id)) v.push({ path: folders[0], message: `epic identifier ${id} is outside the inventory ${EPIC_IDS[0]}–${EPIC_IDS[EPIC_IDS.length - 1]}` });
  }
  for (const id of EPIC_IDS) {
    if (!foldersById.has(id)) v.push({ path: EPICS_DIR, message: `no epic folder for ${id}` });
  }
  const folderCount = epics.length + invalidFolders.size;
  if (folderCount !== EPIC_IDS.length) {
    v.push({ path: EPICS_DIR, message: `${folderCount} epic folders found; the inventory holds ${EPIC_IDS.length}` });
  }
  if (storyKeys.size !== STORY_COUNT) {
    v.push({ path: EPICS_DIR, message: `${storyKeys.size} story files found; the inventory holds ${STORY_COUNT}` });
  }
  for (const epic of epics) epic.stories.sort((a, b) => compare(a.rel, b.rel));
  return { files: fileSet, epics, storyKeys };
}

/**
 * Group S: the epic index is one table that lists every epic README exactly once, and each
 * row names the epic it links by ID, by README title name and by folder. `epicNames` maps
 * an epic identifier to its README title name.
 */
function checkEpicIndex(files, epics, epicNames, v) {
  if (!files.has(INDEX_README)) return;
  const text = readText(INDEX_README);
  const readmeRe = /^docs\/backlog\/epics\/[^/]+\/README\.md$/;
  const linkedReadme = (row) => {
    for (const cell of row) {
      const link = linkInCell(cell);
      if (!link) continue;
      const target = resolveLink(INDEX_README, link.target).path;
      if (readmeRe.test(target)) return target;
    }
    return null;
  };
  const tables = parseTables(text).filter((t) => t.rows.some((r) => !isTotalsRow(r) && linkedReadme(r)));
  if (tables.length === 0) {
    v.push({ path: INDEX_README, message: 'no table in the epic index links the epic READMEs' });
    return;
  }
  if (tables.length > 1) {
    v.push({ path: INDEX_README, line: tables[1].line, message: `epic index holds ${tables.length} tables that link epic READMEs; exactly one index table is allowed` });
  }
  const [table] = tables;
  for (const s of surplusCellRows(table, 'epic index table')) v.push({ path: INDEX_README, line: s.line, message: s.message });

  // Identity columns, located by header text; every column under a required header is compared.
  const columns = (name) => {
    const found = [];
    table.header.forEach((cell, k) => {
      if (plainCell(cell).toLowerCase() === name.toLowerCase()) found.push(k);
    });
    if (found.length === 0) v.push({ path: INDEX_README, line: table.line, message: `epic index table has no "${name}" column` });
    return found;
  };
  const idCols = columns('ID');
  const nameCols = columns('Name');
  const folderCols = columns('Folder');
  const epicByReadme = new Map(epics.map((e) => [e.readme, e]));
  const folderPrefix = `${EPICS_DIR}/`;

  const listed = new Map();
  table.rows.forEach((row, i) => {
    if (isTotalsRow(row)) return;
    const line = table.rowLines[i];
    const target = linkedReadme(row);
    if (!target) {
      v.push({ path: INDEX_README, line, message: 'epic index row has no link to an epic README' });
      return;
    }
    if (!files.has(target)) {
      v.push({ path: INDEX_README, line, message: `epic index row links ${target}, which does not exist` });
      return;
    }
    listed.set(target, (listed.get(target) ?? []).concat(line));
    const epic = epicByReadme.get(target);
    if (!epic) return; // its folder is already reported as off-convention

    for (const k of idCols) {
      const id = plainCell(row[k]);
      if (id !== epic.id) v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: ID "${id}" differs from the folder identifier ${epic.id}` });
    }
    const expectedName = epicNames.get(epic.id);
    if (expectedName !== undefined) {
      for (const k of nameCols) {
        const name = plainCell(row[k]);
        if (name !== expectedName) {
          v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: Name "${name}" differs from the epic README title name "${expectedName}"` });
        }
      }
    }
    for (const k of folderCols) {
      const shown = plainCell(row[k]);
      let folder = shown.endsWith('/') ? shown.slice(0, -1) : shown;
      if (folder.startsWith(folderPrefix)) folder = folder.slice(folderPrefix.length);
      if (folder !== epic.folder) {
        v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: Folder "${shown}" differs from the epic folder ${epic.folder}/` });
      }
      const link = linkInCell(row[k]);
      const linkTarget = link ? resolveLink(INDEX_README, link.target).path : null;
      if (linkTarget !== null && linkTarget !== target) {
        v.push({ path: INDEX_README, line, message: `epic index row for ${epic.id}: Folder cell links ${linkTarget}, not the row's epic README ${target}` });
      }
    }
  });
  for (const epic of epics) {
    if (!files.has(epic.readme)) continue;
    const lines = listed.get(epic.readme);
    if (!lines) v.push({ path: INDEX_README, line: table.line, message: `epic index does not list ${epic.readme}` });
    else if (lines.length > 1) v.push({ path: INDEX_README, line: lines[1], message: `epic index lists ${epic.readme} ${lines.length} times` });
  }
}

/** Group S: one epic README record. Returns the epic display name, or null when unreadable. */
function checkEpicReadme(epic, v) {
  const rel = epic.readme;
  const text = readText(rel);
  const lines = splitLines(text);
  const heads = parseHeadings(text).filter((h) => !h.quoted);

  let name = null;
  const first = heads[0];
  const titleMatch = first && first.level === 1 ? EPIC_TITLE_RE.exec(normaliseSpace(first.text)) : null;
  if (!titleMatch) {
    v.push({ path: rel, line: first?.line, message: `first heading must be the level-1 title "# ${epic.id}: <epic name>"` });
  } else if (titleMatch[1] !== epic.id) {
    v.push({ path: rel, line: first.line, message: `title identifier ${titleMatch[1]} differs from the folder identifier ${epic.id}` });
  } else {
    name = normaliseSpace(titleMatch[2]);
  }

  checkHeadingSequence(heads.filter((h) => h.level === 2), EPIC_SECTIONS, rel, '##', v);

  const goal = h2Section(text, 'Goal');
  if (goal) {
    const hidden = hiddenLines(goal.text);
    const sentence = normaliseSpace(
      splitLines(goal.text)
        .slice(1)
        .filter((l, k) => l.trim() !== '' && !hidden.has(k + 2))
        .map((l) => plainText(normaliseLabel(l)))
        .join(' '),
    );
    if (sentence === '') v.push({ path: rel, line: goal.startLine, message: 'Goal is empty; it states the epic goal in one sentence' });
    else if (hasSecondSentence(sentence)) v.push({ path: rel, line: goal.startLine, message: 'Goal holds more than one sentence' });
  }

  // The request's four epic sections hold content, not only their headings, and the README
  // covers the epic's acceptance criteria as "Epic and Story Structure — Multi-File Variant"
  // requires. The resolved backlog structure sets each section's form: requirements are list
  // items carrying their traces, criteria are numbered 1..n, and Dependencies lists the
  // prerequisite epics, each with its producing story edges, or reads "N/A". Whether those
  // entries equal the epic-dependency table is check-dependency-order.mjs's predicate.
  const LIST_ITEM_RE = /^( {0,3})([-*+]|\d{1,9}[.)])(?:([ \t]+)(.*))?$/;
  const HEADING_LINE_RE = /^ {0,3}#{1,6}(?:[ \t]|$)/;
  const TRACE_LABEL_RE = /(?<![\p{L}\p{N}_])Trace:/u;
  const EPIC_REF_RE = /(?<![\w-])CS-\d{2}(?![\w-])/g;
  const STORY_EDGE_RE = /(?<![\w-])CS-\d{2}-\d{3}\s*(?:→|->)\s*CS-\d{2}-\d{3}(?![\w-])/;
  const hasText = (s) => /[\p{L}\p{N}]/u.test(plainText(s));
  // Content lines of one H2 section with document line numbers, blank lines and the lines of
  // fenced code and HTML comment blocks left out. A list item gathers its continuation lines:
  // lazy lines straight after it, lines indented to its content after a gap, and nested
  // items. Other lines are headings or loose.
  const requestSection = (sectionName) => {
    const section = h2Section(text, sectionName);
    if (!section) return null;
    const hidden = hiddenLines(section.text);
    const content = [];
    const items = [];
    let item = null;
    let afterGap = false;
    splitLines(section.text).forEach((raw, k) => {
      // Line k + 1 of the section text is document line section.startLine + k; line 1 is the heading.
      if (k === 0) return;
      if (raw.trim() === '' || hidden.has(k + 1)) {
        afterGap = true;
        return;
      }
      const line = section.startLine + k;
      const indent = raw.length - raw.trimStart().length;
      const marker = LIST_ITEM_RE.exec(raw);
      let kind;
      if (HEADING_LINE_RE.test(raw)) {
        item = null;
        kind = 'heading';
      } else if (marker && !(item && indent >= item.contentIndent)) {
        const spaces = (marker[3] ?? ' ').length;
        item = {
          line,
          number: /^\d/.test(marker[2]) ? Number.parseInt(marker[2], 10) : null,
          text: marker[4] ?? '',
          contentIndent: marker[1].length + marker[2].length + (spaces <= 4 ? spaces : 1),
        };
        items.push(item);
        kind = 'item';
      } else if (item && (!afterGap || indent >= item.contentIndent)) {
        item.text += ` ${raw.trim()}`;
        kind = 'continuation';
      } else {
        item = null;
        kind = 'loose';
      }
      content.push({ line, raw, kind, item: kind === 'item' ? item : null });
      afterGap = false;
    });
    return { section, content, items };
  };

  const justification = requestSection('Business Justification');
  if (justification && !justification.content.some((c) => c.kind !== 'heading' && hasText(normaliseLabel(c.raw)))) {
    v.push({ path: rel, line: justification.section.startLine, message: 'Business Justification is empty; it states why the epic is built' });
  }

  const requirements = requestSection('Requirements');
  if (requirements) {
    if (requirements.items.length === 0) {
      v.push({ path: rel, line: requirements.section.startLine, message: 'Requirements lists no requirement; each requirement is a list item carrying its trace' });
    }
    for (const req of requirements.items) {
      const plain = normaliseSpace(req.text.replace(/`/g, ''));
      const at = plain.search(TRACE_LABEL_RE);
      if (at < 0) {
        v.push({ path: rel, line: req.line, message: 'Requirements item carries no "Trace:"; each requirement carries its trace' });
      } else if (!TRACE_RE.test(plain.slice(at))) {
        v.push({ path: rel, line: req.line, message: `Requirements item trace "${plain.slice(at)}" does not take the trace form of the stories, "Trace: CARD_REQ_<id> · <document> · <anchor>"` });
      } else if (!hasText(plain.slice(0, at))) {
        v.push({ path: rel, line: req.line, message: 'Requirements item holds a trace but no requirement' });
      }
    }
  }

  const criteria = requestSection('Acceptance Criteria');
  if (criteria) {
    for (const c of criteria.content) {
      if (c.kind === 'heading' || c.kind === 'loose' || (c.kind === 'item' && c.item.number === null)) {
        v.push({ path: rel, line: c.line, message: 'Acceptance Criteria line is not part of a numbered criterion; each criterion is a numbered list item' });
      }
    }
    const numbered = criteria.items.filter((c) => c.number !== null);
    if (numbered.length === 0) {
      v.push({ path: rel, line: criteria.section.startLine, message: 'Acceptance Criteria lists no numbered criterion; the epic README covers its acceptance criteria' });
    }
    const outOfSequence = numbered.findIndex((c, k) => c.number !== k + 1);
    if (outOfSequence >= 0) {
      const c = numbered[outOfSequence];
      v.push({ path: rel, line: c.line, message: `Acceptance Criteria item is numbered ${c.number} where ${outOfSequence + 1} is next; criteria run 1..n in order with no gap or duplicate` });
    }
    for (const c of numbered) {
      if (!hasText(c.text)) v.push({ path: rel, line: c.line, message: `Acceptance Criteria item ${c.number} has no text` });
    }
  }

  const dependencies = requestSection('Dependencies');
  if (dependencies) {
    const shape = 'it lists the prerequisite epics, each with its producing story edges, or reads "N/A"';
    const body = dependencies.content.filter((c) => c.kind !== 'heading');
    const notApplicable = dependencies.content.filter((c) => plainText(normaliseLabel(c.raw)) === 'N/A');
    if (body.length === 0) {
      v.push({ path: rel, line: dependencies.section.startLine, message: `Dependencies is empty; ${shape}` });
    } else if (notApplicable.length > 0) {
      if (dependencies.content.length > 1) {
        v.push({ path: rel, line: notApplicable[0].line, message: `Dependencies mixes "N/A" with other content; ${shape}` });
      }
    } else if (dependencies.items.length === 0) {
      v.push({ path: rel, line: dependencies.section.startLine, message: `Dependencies lists no entry; ${shape}` });
    } else {
      for (const entry of dependencies.items) {
        const plain = normaliseSpace(plainText(entry.text));
        if (!(plain.match(EPIC_REF_RE) ?? []).some((id) => id !== epic.id)) {
          v.push({ path: rel, line: entry.line, message: 'Dependencies entry names no prerequisite epic "CS-NN"' });
        }
        if (!STORY_EDGE_RE.test(plain)) {
          v.push({ path: rel, line: entry.line, message: 'Dependencies entry carries no producing story edge "CS-NN-NNN → CS-NN-NNN"' });
        }
      }
    }
  }

  const stories = h2Section(text, 'Stories');
  if (!stories) return name;
  const table = parseTables(stories.text).find(
    (t) => t.header.some((c) => /points/i.test(c)) && t.header.some((c) => /files touched/i.test(c)),
  );
  if (!table) {
    v.push({ path: rel, line: stories.startLine, message: 'Stories section has no table with a points column and an estimated files touched column' });
    return name;
  }
  const toDoc = (l) => stories.startLine + l - 1;
  for (const s of surplusCellRows(table, 'Stories table')) v.push({ path: rel, line: toDoc(s.line), message: s.message });
  const storyDir = `${epic.dir}/stories/`;
  const expected = new Set(epic.stories.map((s) => s.rel));
  const linked = new Map();
  let hasTotals = false;
  table.rows.forEach((row, i) => {
    const line = toDoc(table.rowLines[i]);
    if (isTotalsRow(row)) {
      hasTotals = true;
      return;
    }
    let target = null;
    for (const cell of row) {
      const link = linkInCell(cell);
      if (!link) continue;
      const resolved = resolveLink(rel, link.target).path;
      if (resolved.endsWith('.md') && resolved.includes('/stories/')) {
        target = resolved;
        break;
      }
    }
    if (!target) {
      v.push({ path: rel, line, message: 'Stories row has no link to a story file' });
    } else if (!target.startsWith(storyDir) || !expected.has(target)) {
      v.push({ path: rel, line, message: `Stories row links ${target}, which is not a file in this epic's stories/ folder` });
    } else if (linked.has(target)) {
      v.push({ path: rel, line, message: `Stories table links ${target} more than once` });
    } else {
      linked.set(target, line);
    }
  });
  if (!hasTotals) v.push({ path: rel, line: toDoc(table.line), message: 'Stories table has no totals row' });
  for (const storyRel of expected) {
    if (!linked.has(storyRel)) v.push({ path: rel, line: toDoc(table.line), message: `Stories table does not link story file ${storyRel}` });
  }

  const tableEnd = toDoc(table.rowLines.length ? table.rowLines[table.rowLines.length - 1] : table.line + 1);
  // An HTML comment block never renders, so its lines are not read as the README's last
  // lines; a fenced line renders as code, never as the Owner or Status field.
  const comments = commentLineSet(text);
  const fences = fenceLineSet(text);
  const nonBlank = lines
    .map((l, i) => ({ text: normaliseLabel(l), line: i + 1 }))
    .filter((l) => l.text !== '' && !comments.has(l.line));
  const [owner, status] = nonBlank.slice(-2);
  // No input names an epic owner or a workflow state, so both carry TBD: any other owner
  // is one that "Enforce Implementation Plan, Epic and Story Structure — Base" calls invented.
  const found = (l) => (l ? `; found "${l.text}"${fences.has(l.line) ? ' inside fenced code' : ''}` : '');
  if (!owner || owner.text !== 'Owner: TBD' || owner.line <= tableEnd || fences.has(owner.line)) {
    v.push({ path: rel, line: owner?.line, message: `the line before the last non-blank line must be "Owner: TBD", after the Stories table${found(owner)}` });
  }
  if (!status || status.text !== 'Status: TBD' || status.line <= tableEnd || fences.has(status.line)) {
    v.push({ path: rel, line: status?.line, message: `the last non-blank line must be "Status: TBD", after the Stories table${found(status)}` });
  }
  return name;
}

/** Group S: title line and parent epic line, whose epic name equals the epic README title name. */
function checkTitleAndParent(story, lines, ctx, v) {
  const nonBlank = lines.map((l, i) => ({ text: l.trim(), line: i + 1 })).filter((l) => l.text !== '');
  const [title, parent] = nonBlank;
  const titleMatch = title ? STORY_TITLE_RE.exec(title.text) : null;
  if (!titleMatch) {
    v.push({ path: story.rel, line: title?.line, message: `first line must be the title "# ${story.key}: <title>"` });
  } else if (titleMatch[1] !== story.key) {
    v.push({ path: story.rel, line: title.line, message: `title KEY ${titleMatch[1]} differs from the filename identifier ${story.key}` });
  }
  const parentMatch = parent ? PARENT_EPIC_RE.exec(parent.text) : null;
  if (!parentMatch) {
    v.push({ path: story.rel, line: parent?.line, message: `second line must be "Parent epic: [${story.epic.id} <epic name>](../README.md)"` });
    return;
  }
  if (parentMatch[1] !== story.epic.id) {
    v.push({ path: story.rel, line: parent.line, message: `parent epic line names ${parentMatch[1]}, not the folder's epic ${story.epic.id}` });
  }
  const parentName = normaliseSpace(parentMatch[2]);
  const expectedName = ctx.epicNames.get(story.epic.id);
  if (expectedName !== undefined && parentName !== expectedName) {
    v.push({ path: story.rel, line: parent.line, message: `parent epic line names the epic "${parentName}", not the epic README title name "${expectedName}"` });
  }
  if (resolveLink(story.rel, parentMatch[3]).path !== story.epic.readme) {
    v.push({ path: story.rel, line: parent.line, message: `parent epic link does not resolve to ${story.epic.readme}` });
  }
}

/**
 * An acceptance-criterion label for a diagnostic. A digit suffix longer than 20 digits is
 * cut to its first 20 and its digit count, so an overlong suffix keeps the line bounded.
 */
function acLabel(digits) {
  return digits.length > 20 ? `AC${digits.slice(0, 20)}… (${digits.length} digits)` : `AC${digits}`;
}

/**
 * Why an acceptance-criterion digit suffix is not an AC1–ACn ordinal, or null when it is a
 * positive safe integer written without a leading zero. The suffix is document text of any
 * length, so its digits and length are judged before Number() reads it.
 */
function acNumberProblem(digits) {
  if (/^0+$/.test(digits)) return 'is numbered 0; criteria are numbered from AC1';
  if (digits.startsWith('0')) return 'carries a leading zero; criteria are numbered AC1–ACn without leading zeros';
  if (digits.length > 16 || !Number.isSafeInteger(Number(digits))) {
    return `has a number above ${Number.MAX_SAFE_INTEGER}, the largest acceptance-criterion number this check compares`;
  }
  return null;
}

/**
 * Whether a section 2 line, quote markers removed, is written as an acceptance criterion
 * without the "- AC<n>" start: a Unicode dash, bullet, middle dot, "*", "+" or list number
 * in place of "- ", no bullet, "AC" in lower case or with a space, hyphen, dot, "#" or "_"
 * before its digits, or emphasis or code markers around the label. At the left margin
 * outside a SCOPE-QUESTION note, any such label counts. Indented, after a leftover quote
 * marker, or inside a note, the label counts when a separator (":", ".", ")", "]" or a
 * dash not opening a range) or the line end follows its digits, or, at the left margin of
 * a note, when a bullet or emphasis precedes it. Sub-bullets and notes that open by naming
 * criteria, such as "AC13–AC16 leave no row" or "AC5 and AC6 hold", stay continuation text.
 */
function isMalformedAcLine(line, inNote) {
  if (AC_START_RE.test(line)) return false;
  const m = AC_LIKE_RE.exec(line);
  if (!m) return false;
  const { lead, bullet, emphasis, sep } = m.groups;
  return (lead === '' && (!inNote || bullet !== undefined || emphasis !== undefined)) || sep !== undefined;
}

/** A line trimmed and cut to 60 characters for a diagnostic. */
function lineExcerpt(line) {
  const s = line.trim();
  return s.length > 60 ? `${s.slice(0, 60)}…` : s;
}

// The fewest words that name a condition or input, what is observed and its outcome.
const AC_MIN_WORDS = 5;

// The expected-outcome lexicon. A criterion states its outcome through a finite verb or a
// comparison; a noun phrase such as "Account data and transaction activity details." has
// neither. Auxiliaries, modals and copulas make a statement wherever they stand: "is
// rejected", "has exactly one", "does not fail", "must hold".
const OUTCOME_AUXILIARIES = new Set(
  "is are was were be been being isn't aren't wasn't weren't has have had hasn't haven't does do did doesn't don't didn't must shall will won't should would can cannot can't may might".split(' '),
);
// Outcome verb stems, matched in their base form ("both parse to") and their third-person
// form ("returns", "decodes", "applies", "matches").
const OUTCOME_VERBS = `
  accept add adopt allow answer append appear apply arrive assert assign become begin behave bind block build cache call carry
  cause change check close commit compile complete contain continue convert count cover create decode degrade delete deny detect
  differ display drop emit encode end equal exceed exist exit expire expose fail fall fetch fill find finish fit flag follow gain
  generate get give grant group happen hit hold ignore include install invoke issue keep lack land last launch leave limit list load
  log make map mark match measure meet move name need omit open order parse pass pause perform place precede proceed produce
  propagate prove publish raise reach read receive record redact refresh refuse register reject reload remain remove render repeat
  replace reply report request require rerun reset resolve respond restart retain retry return reuse reveal roll round route run
  save see select send serve set share show skip sort split start stay stop store submit substitute succeed support surface take
  terminate test throw time total treat trigger truncate turn unpause update use validate verify wait walk warn write yield
`.trim().split(/\s+/);
// Forms of those stems that also read as a noun or adjective: "request logs", "two runs",
// "cache reads", "the complete summary". Such a form counts as the verb only when the next
// word opens its object or completes a phrasal verb, as in "logs the call", "holds exactly
// one", "times out" or "reads back".
const NOUN_LIKE_VERB_FORMS = new Set(`
  answer answers block blocks build builds cache caches call calls cause causes change changes check checks close commit commits
  complete count counts cover covers display displays drop drops end ends equal exit fall fill fills find finds fit flag flags gain
  gains group groups hit hits hold holds issue issues lack lacks last lasts launch launches leave limit limits list lists load loads
  log logs make map maps mark marks match matches measure measures move moves name names need needs open order orders pass pause
  pauses place places raise read reads record records refresh refreshes reload reloads repeat replace reply replies report reports
  request requests rerun reruns reset resets restart retry retries return roll rolls round rounds route routes run runs save set sets
  share shares show skip sort sorts split splits start starts stay stays support supports surface surfaces take test tests throw time
  times total totals trigger triggers turn turns update updates use uses wait waits walk walks write writes yield yields
`.trim().split(/\s+/));
const thirdPerson = (stem) => (/(?:s|x|z|ch|sh)$/.test(stem) ? `${stem}es` : /[^aeiou]y$/.test(stem) ? `${stem.slice(0, -1)}ies` : `${stem}s`);
const OUTCOME_VERB_FORMS = new Set(OUTCOME_VERBS.flatMap((stem) => [stem, thirdPerson(stem)]));
// Words that open a verb's object or complete a phrasal verb. A number, code span or quoted
// value opens an object too.
const OBJECT_OPENERS = new Set(
  'the a an no zero one two three four five six seven eight nine ten every each all both only exactly its their this that these those any none nothing not out back'.split(' '),
);
// A comparison followed by a number or a value: "≤ 900", "at most 900", "below 1,000 ms",
// "equal to `FRESH`", "→ `81.44`".
const OUTCOME_RELATION_RE = /(?:[<>=\u2192\u2260\u2264\u2265]|\b(?:at\s+(?:most|least)|(?:fewer|more|less|greater|lower|higher)\s+than|equal\s+to|below|above))\s*\p{N}/iu;
// Code spans and quoted values are values the criterion names, not its sentence, so each
// is read as the one value word "0". Each alternative stops at the next quote of its kind,
// so masking stays linear in the text.
const OUTCOME_VALUE_RE = /`[^`]*`|"[^"]*"|\u201c[^\u201c\u201d]*\u201d/g;
const OUTCOME_WORD_RE = /[\p{L}\p{N}][\p{L}\p{N}'\u2019_-]*/gu;

/**
 * Whether a criterion carries an expectation structure: a comparison followed by a number
 * or value, an auxiliary, modal or copula, a form of an outcome verb that reads only as a
 * verb, or a noun-like verb form whose next word opens its object. A noun phrase carries
 * none of these.
 */
function statesOutcome(criterion) {
  const text = criterion.replace(OUTCOME_VALUE_RE, ' 0 ');
  if (OUTCOME_RELATION_RE.test(text)) return true;
  const words = text.match(OUTCOME_WORD_RE) ?? [];
  return words.some((word, i) => {
    const w = word.toLowerCase().replace(/\u2019/g, "'");
    if (OUTCOME_AUXILIARIES.has(w)) return true;
    if (!OUTCOME_VERB_FORMS.has(w)) return false;
    if (!NOUN_LIKE_VERB_FORMS.has(w)) return true;
    const next = words[i + 1];
    return next !== undefined && (OBJECT_OPENERS.has(next.toLowerCase()) || /^\p{N}/u.test(next));
  });
}

/**
 * Why an acceptance criterion is not written as a testable criterion, or null when it is.
 * `ac.text` is the criterion line and its continuation lines as they render, so a word
 * inside an HTML comment counts for nothing. The line must read
 * "- AC<n>: <criterion>". The criterion, its line together with the sub-bullets beneath
 * it, holds at least AC_MIN_WORDS words, carries no TBD, TBA, TODO or FIXME outside code
 * spans, ends as a complete statement: with "." or with ":" introducing sub-bullets, and
 * states an expected outcome through a finite verb from the expected-outcome lexicon or a
 * comparison with a number or value (statesOutcome). These are the mechanical signs of a
 * pass/fail determination; a noun phrase such as "The balance summary endpoint." carries
 * none. The lexicon is closed: a criterion whose only verb is missing from it fails until
 * the verb is added, with any of its forms that read as nouns in NOUN_LIKE_VERB_FORMS.
 * Human review keeps what no predicate decides: a noun phrase built on a verb-only form,
 * such as "Data stores for the service.", which passes; whether the stated outcome is
 * observable and decides pass or fail; and whether a story's criteria cover valid input,
 * invalid input, error conditions and boundaries.
 */
function acCriterionProblem(ac) {
  const [head, ...rest] = splitLines(ac.text);
  const form = /^- AC\d+: (\S.*)$/.exec(head.trimEnd());
  if (!form) {
    return /^- AC\d+:?\s*$/.test(head) ? `has no criterion text after "- AC${ac.num}:"` : `must be written "- AC${ac.num}: <criterion>"`;
  }
  const body = form[1];
  const subBullets = rest.filter((l) => /^\s+[-*+]\s+\S/.test(l));
  const criterion = [body, ...subBullets].join(' ');
  const placeholder = /\b(?:TBD|TBA|TODO|FIXME)\b/.exec(criterion.replace(/`[^`]*`/g, ' '));
  if (placeholder) return `holds the placeholder "${placeholder[0]}"; a criterion states a decided pass/fail outcome`;
  const words = criterion.split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).length;
  if (words < AC_MIN_WORDS) {
    return `states ${words} word${words === 1 ? '' : 's'}; a criterion names its condition and its observable pass/fail outcome in at least ${AC_MIN_WORDS} words`;
  }
  if (!/\.[)\]"'\u2019\u201d*_`]*$/.test(body) && !(body.endsWith(':') && subBullets.length > 0)) {
    return 'does not end as a complete statement; it ends with "." or with ":" followed by sub-bullets';
  }
  if (!statesOutcome(criterion)) {
    return 'states no expected outcome; a criterion names what is observed and its pass/fail result with a verb such as "returns", "fails", "contains" or "is", or a comparison such as "at most 900"';
  }
  return null;
}

/**
 * Canonical classification roles. Each role fixes the two values its stories carry,
 * [Account-data access path, External integration or SLA-bound], and the reason a
 * diagnostic quotes after "<story> is". The rules behind the values:
 *  - Account-data access path is Yes for a story that serves or verifies an account-data
 *    read on the request path, because the account access guard decides access before every
 *    such read, cache reads included: the guard and its integration suite, the endpoint
 *    assembly, retrieval and REST and gRPC stories, the end-to-end steps and SLA scenarios,
 *    and the Card Data and Transaction Activity clients, the service's only path to account,
 *    cross-reference, category-balance and transaction records. Those clients add no access
 *    check of their own, because every caller runs behind the guard. Stubs do not
 *    re-authorize, and input validation, data loads, client integration suites, harnesses
 *    and foundation stories read no account data on a request path, so all of them carry No.
 *  - External integration or SLA-bound is Yes for a story that makes, bounds or verifies an
 *    upstream call or an SLA-bound run: the guard, every client, the call policy and the
 *    cache, every integration suite, the endpoint stories, the harnesses, and the end-to-end
 *    steps and SLA scenarios. Stubs answer calls and make none, input validation rejects a
 *    request before any upstream call, and data and foundation stories make no upstream
 *    call of their own, so they carry No.
 */
const CLASSIFICATION_ROLES = {
  guard: ['Yes', 'Yes', 'the account access guard, which decides access before every account-data read and calls the Auth service under the call policy'],
  guardSuite: ['Yes', 'Yes', 'the Auth integration suite, which verifies the 403 denial and the no-read-before-allow rule through the guard against the Auth stub'],
  accountDataClient: ['Yes', 'Yes', 'a client that is the service\'s only path to account-data records, read on the request path once the guard allows access, through upstream calls under the call policy'],
  endpoint: ['Yes', 'Yes', 'an endpoint story that serves an account-data read on the request path through upstream calls under the request deadline'],
  interaction: ['Yes', 'Yes', 'an end-to-end step or SLA scenario that verifies an account-data read on the request path against the stubbed upstream services'],
  client: ['No', 'Yes', 'a client for configuration, authentication or reference data, which is not account data, making upstream calls under the call policy'],
  clientSuite: ['No', 'Yes', 'an integration suite that drives a client against its stub with failure injection, outside any caller\'s request'],
  callSupport: ['No', 'Yes', 'the call policy or the read-through cache, which bounds upstream calls and their stale fallback and decides no access'],
  harness: ['No', 'Yes', 'a test stack or harness for the end-to-end and SLA-bound runs, which reads no account data on a request path itself'],
  stub: ['No', 'No', 'a stub service, which answers calls, makes none and does not re-authorize'],
  data: ['No', 'No', 'a table, fixture-load or volume story whose data is written before any request'],
  validation: ['No', 'No', 'an input validation story that rejects a request before any upstream call or account-data read'],
  foundation: ['No', 'No', 'a build, configuration, error-mapping, stub-hosting, codec, aggregation or secret-source story with no account-data read and no upstream call of its own'],
};

/** Every story identifier mapped to its classification role; a story with no entry is reported. */
const STORY_ROLES = new Map(
  Object.entries({
    guard: ['CS-12-003'],
    guardSuite: ['CS-12-005'],
    accountDataClient: ['CS-09-005', 'CS-10-006'],
    endpoint: ['CS-05-005', 'CS-05-006', 'CS-06-002', 'CS-06-003', 'CS-07-002', 'CS-07-003', 'CS-08-002', 'CS-08-003'],
    interaction: ['CS-01-004', 'CS-01-005', 'CS-02-001', 'CS-02-002', 'CS-03-001', 'CS-03-002', 'CS-03-003', 'CS-04-001', 'CS-04-002'],
    client: ['CS-11-006', 'CS-12-002', 'CS-13-003'],
    clientSuite: ['CS-09-006', 'CS-10-007', 'CS-11-007', 'CS-13-004'],
    callSupport: ['CS-11-003', 'CS-11-004'],
    harness: ['CS-01-001', 'CS-01-002', 'CS-01-003'],
    stub: ['CS-09-004', 'CS-10-004', 'CS-10-005', 'CS-11-005', 'CS-12-001', 'CS-13-002'],
    data: ['CS-09-001', 'CS-09-002', 'CS-09-003', 'CS-09-007', 'CS-10-001', 'CS-10-002', 'CS-10-008', 'CS-13-001'],
    validation: ['CS-05-003', 'CS-06-001', 'CS-07-001', 'CS-08-001'],
    foundation: ['CS-05-001', 'CS-05-002', 'CS-05-004', 'CS-10-003', 'CS-11-001', 'CS-11-002', 'CS-12-004'],
  }).flatMap(([role, ids]) => ids.map((id) => [id, role])),
);

const SCOPE_NOTE_RE = /^SCOPE-QUESTION \((Resolved|Open)\)/;

/**
 * The values that SCOPE-QUESTION (Resolved) notes placed directly beneath a classification
 * line state for it, in the form "<label> Yes" or "<label> No". `lineNumber` is the line's
 * 1-based number in `bodyLines`. Every note paragraph that follows the line, blank lines
 * apart, is read in turn, and an Open note changes nothing. Note heads and paragraph bounds
 * come from `bodyLines`, the lines as written; the values come from `shownLines`, the same
 * lines as they render, so a value inside an HTML comment resolves nothing. Returns
 * { values, line }: the distinct values stated and the 1-based body line of the first note
 * stating one.
 */
function resolvedClassification(bodyLines, shownLines, lineNumber, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const valueRe = new RegExp(`${escaped} (Yes|No)\\b`, 'g');
  const values = new Set();
  let line = null;
  let i = lineNumber;
  for (;;) {
    while (i < bodyLines.length && bodyLines[i].trim() === '') i++;
    const head = i < bodyLines.length ? SCOPE_NOTE_RE.exec(normaliseLabel(bodyLines[i])) : null;
    if (!head) break;
    const start = i;
    const paragraph = [];
    while (i < bodyLines.length && bodyLines[i].trim() !== '') paragraph.push(normaliseLabel(shownLines[i++]));
    if (head[1] !== 'Resolved') continue;
    for (const m of paragraph.join(' ').replace(/`/g, '').matchAll(valueRe)) {
      values.add(m[1]);
      line ??= start + 1;
    }
  }
  return { values: [...values], line };
}

// The authorization constraint of an account-data access path story, read per acceptance
// criterion on its plain text as it renders (HTML comments blanked, code spans unwrapped,
// whitespace collapsed).
// Denial: "returns 403 … PERMISSION_DENIED" inside one clause as what the request gets, read
// as the clause's first DENIAL_HEAD_RE match and the first DENIAL_CODE_RE match after it.
// The read-first pairs below are found the same way, by two forward scans each run once, and
// the outside pair by one pass over its words, so the work stays linear in the criterion's
// length; a lazy span between two phrases would be re-scanned from every repeated first one.
const DENIAL_HEAD_RE = /\b(?:returns?|receives?|(?:responds?|answers?|ends?|fails?) with)\s+(?:(?:an?|the|REST|HTTP|status)\s+)*403\b/gi;
const DENIAL_CODE_RE = /\bPERMISSION_DENIED\b/gi;
// A word that negates the clause it stands in, before the denial or the opposite outcome.
const NEGATION_RE = /\b(?:not|never|no|none|zero|nor|neither|without|cannot|unless|except|instead of|rather than)\b|n't\b/i;
// Another status code or an alternative between 403 and PERMISSION_DENIED: a list of codes.
const CODE_LIST_RE = /\b[1-5]\d\d\b|\bor\b/i;
// The words of "outside … entitled/entitlement(s)" and "entitled … outside", and the full
// stop or semicolon that ends the stretch in which such a pair counts.
const OUTSIDE_WORD_RE = /\b(?:(outside)|(entitled)|entitlements?)\b|[.;]/gi;
const NO_READ_RE = /\bno account data is read(?: from (?:upstream|the cache|cache)(?: or (?:from )?(?:upstream|the cache|cache))?)? before access is allowed\b/gi;
// A NO_READ_RE phrase states the prohibition only when its own clause asserts it. That clause
// is the part of its sentence clause bounded by the nearest NO_READ_CLAUSE_RE connective on
// either side, or by the sentence clause's ends. A connective followed by "that", "whether",
// "if", "which", "this", "it", "to" or a second connective bounds nothing, since what follows
// it is governed by, or refers back to, what precedes it. A NO_READ_REVERSAL_RE word in that
// clause, before or after the phrase, takes the prohibition back: negation, exception,
// alternative or optionality, so account data may be read before access is allowed. A
// NO_READ_HEDGE_RE word leaves it unasserted: a modal, a concession or an open question. So
// do "if", "when" or "or" directly before the phrase, "or" directly after it, and a question.
// "no", "none" and "zero" are neither, because the proof that leads into the phrase states
// them: "the stub request log holds zero entries, so no account data is read …".
const NO_READ_CLAUSE_RE = /,\s+(?:(?:and\s+)?(?:so|hence|thus|therefore|consequently)|and|but|yet)\s+(?!(?:that|whether|if|which|this|it|to|so|hence|thus|therefore|consequently)\b)/i;
const NO_READ_REVERSAL_RE = /\b(?:not|never|cannot|nor|neither|unless|except(?:ing)?|other than|apart from|aside from|save for|only (?:if|when|where|while)|optional(?:ly)?|discretionary|waived|best[- ]effort|(?:where|if|when) possible|false|untrue|wrong|incorrect|rather than|instead of)\b|n['\u2019]t\b/gi;
const NO_READ_HEDGE_RE = /\b(?:may|might|could|can|would|should|perhaps|possibly|maybe|ideally|preferably|hopefully|desirable|recommended|preferred|aspirational|although|though|albeit|either|whether|unclear|uncertain|undecided|unsettled|unresolved|unspecified|TBD|to be (?:decided|confirmed)|left open|remains open|open question)\b/gi;
const NO_READ_CONDITION_RE = /\b(?:if|when|whenever|lest|or)\s+$/i;
const NO_READ_ALTERNATIVE_RE = /^,?\s+or\b/i;
// The opposite of NO_READ_RE: a read stated to happen before the access decision, as the
// clause's first read word and the first later phrase that places it ahead of the decision.
const READ_FIRST_PAIRS = [
  [/\bread(?:s|ing)?\b/gi, /\bbefore (?:access is allowed|access is decided|the access decision|authori[sz]ation|the guard)\b/gi],
  [/\breads?\b/gi, /\bprecedes? (?:the access decision|access|authori[sz]ation|the guard)\b/gi],
];

/** The first match of the global pattern `re` in `s` starting at or after index `from`, or null. */
function matchFrom(re, s, from) {
  re.lastIndex = from;
  const m = re.exec(s);
  re.lastIndex = 0;
  return m;
}

/**
 * Whether sentence `s` names "outside" before "entitled", "entitlement" or "entitlements",
 * or "entitled" before "outside", with no full stop or semicolon between the two words.
 * One pass over those words decides it.
 */
function namesOutsideEntitlements(s) {
  let outside = false;
  let entitled = false;
  for (const m of s.matchAll(OUTSIDE_WORD_RE)) {
    if (m[0].length === 1) {
      outside = false;
      entitled = false;
    } else if (m[1]) {
      if (entitled) return true;
      outside = true;
    } else {
      if (outside) return true;
      if (m[2]) entitled = true;
    }
  }
  return false;
}

/**
 * The parts of the authorization constraint one acceptance criterion states, its notes
 * included: { denial, outside, noRead, readFirst }. `denial` is a non-negated 403 with
 * PERMISSION_DENIED that the request returns, never one code of a list; `outside` names an
 * account outside the caller's entitlements; `noRead` asserts, in the phrase's own clause, that
 * no account data is read before access is allowed; `readFirst` holds the clause stating the
 * opposite, a read ahead of the access decision or that prohibition taken back, or null.
 */
function authorizationParts(text) {
  const plain = normaliseSpace(String(text).replace(/`/g, '').replace(/\*\*|__/g, ''));
  const clauses = (s) => s.split(/(?<=[.;:!?])\s+/);
  const denial = clauses(plain).some((c) => {
    const head = matchFrom(DENIAL_HEAD_RE, c, 0);
    const code = head && matchFrom(DENIAL_CODE_RE, c, head.index + head[0].length);
    if (!code) return false;
    const between = c.slice(head.index + head[0].length, code.index);
    return !NEGATION_RE.test(c.slice(0, head.index)) && !NEGATION_RE.test(between) && !CODE_LIST_RE.test(between);
  });
  const outside = plain
    .split(/(?<=[.;!?])\s+/)
    .some((s) => namesOutsideEntitlements(s) && !/\b(?:not|never)\s+outside\b/i.test(s));
  // Each NO_READ_RE phrase outside a question, read in its own clause: one scan per pattern
  // over that clause and constant work per phrase keep this linear in the criterion's length.
  const span = (re, s) => {
    let first = -1;
    let last = -1;
    for (let m = matchFrom(re, s, 0); m; m = matchFrom(re, s, m.index + m[0].length)) {
      if (first < 0) first = m.index;
      last = m.index;
    }
    return { first, last };
  };
  let noRead = false;
  let reversed = null;
  for (const c of clauses(plain)) {
    if (/\?[)\]"'\u201d\u2019]*$/.test(c)) continue;
    for (const own of c.split(NO_READ_CLAUSE_RE)) {
      const phrase = matchFrom(NO_READ_RE, own, 0);
      if (!phrase) continue;
      const reversal = span(NO_READ_REVERSAL_RE, own);
      const hedge = span(NO_READ_HEDGE_RE, own);
      for (let m = phrase; m; m = matchFrom(NO_READ_RE, own, m.index + m[0].length)) {
        const end = m.index + m[0].length;
        const around = (s) => (s.first >= 0 && s.first < m.index) || s.last >= end;
        if (around(reversal)) reversed ??= c.replace(/^[\s,;:]+/, '').trim();
        else if (!around(hedge) && !NO_READ_CONDITION_RE.test(own.slice(Math.max(0, m.index - 10), m.index)) && !NO_READ_ALTERNATIVE_RE.test(own.slice(end, end + 5))) noRead = true;
      }
    }
  }
  let readFirst = null;
  for (const c of clauses(plain.replace(NO_READ_RE, ' '))) {
    for (const [wordRe, phraseRe] of READ_FIRST_PAIRS) {
      const read = matchFrom(wordRe, c, 0);
      const phrase = read && matchFrom(phraseRe, c, read.index + read[0].length);
      if (phrase && !NEGATION_RE.test(c.slice(0, read.index)) && !NEGATION_RE.test(c.slice(read.index, phrase.index + phrase[0].length))) readFirst ??= c.replace(/^[\s,;:]+/, '').trim();
    }
  }
  readFirst ??= reversed;
  return { denial, outside, noRead, readFirst };
}

// The upstream calls whose degrade or fail-hard outcome an "External integration or
// SLA-bound" story states, one per distinct outcome of the TRD degradation table, each with
// its service and the words that name it: its operations, its client calls or the read it
// serves. Within a story whose calls include no other call of the same service, the
// service's own name, gRPC service or stub also names the call.
const AUTH_CALLS = 'Auth calls';
const CARD_DATA_READS = 'Card Data account and category-balance reads';
const CROSS_REFERENCE_READ = 'Card Data cross-reference read';
const LIST_READ = 'Transaction Activity list read';
const DETAIL_READ = 'Transaction Activity detail read';
const CONFIG_CALL = 'Config call';
const REFERENCE_READS = 'Reference Data reads';
const UPSTREAM_CALLS = {
  [AUTH_CALLS]: { service: 'Auth', re: /\bValidateToken\b|\bAuthorizeAccountAccess\b/ },
  [CARD_DATA_READS]: { service: 'Card Data', re: /\bGetAccount\b|\bListCategoryBalances\b|\bcategoryBalances\b|\b(?:account|category-balance) (?:reads?|entr(?:y|ies))\b/i },
  [CROSS_REFERENCE_READ]: { service: 'Card Data', re: /\bGetCardCrossReference\b|\bcardCrossReferences\b|\bcross-references?\b/i },
  [LIST_READ]: { service: 'Transaction Activity', re: /\bListTransactions\b|\blist(?:ing)?\b|\bpages?\b/i },
  [DETAIL_READ]: { service: 'Transaction Activity', re: /\bGetTransaction\b|\btransaction reads?\b|\btransaction[- ]detail(?:,? (?:and|or) [\w-]+)? (?:reads?|failures?)\b/i },
  [CONFIG_CALL]: { service: 'Config', re: /\bGetConfig\b/ },
  [REFERENCE_READS]: { service: 'Reference Data', re: /\bListTransactionTypes\b|\bListTransactionCategories\b|\btransaction (?:type|category) list\b/i },
};
const UPSTREAM_SERVICE_RES = {
  Auth: /\bAuth\b|\bAuthService\b|\bauth-stub\b|\bstub auth\b/,
  'Card Data': /\bCard Data\b|\bCardDataService\b|\bcard-data\b/,
  'Transaction Activity': /\bTransaction Activity\b|\bTransactionActivityService\b|\btransaction-activity\b/,
  Config: /\bConfig\b|\bConfigService\b|\bconfig-stub\b|\bstub config\b/,
  'Reference Data': /\bReference Data\b|\bReferenceDataService\b|\breference-data\b/,
};
const ANY_UPSTREAM = 'upstream calls';
const ANY_UPSTREAM_RE = new RegExp(
  [
    ...Object.values(UPSTREAM_CALLS).map((call) => call.re.source),
    ...Object.values(UPSTREAM_SERVICE_RES).map((re) => re.source),
    String.raw`\bupstream\b`,
    String.raw`\bstubs?\b`,
  ].join('|'),
  'i',
);

/**
 * Canonical upstream calls of each "External integration or SLA-bound: Yes" story: its
 * acceptance criteria state the degradation-table outcome of each one. Derived from the
 * request-path stages and the resilience ownership of the plan, checked against each story's
 * capability and the TRD degradation table:
 *  - An endpoint story, end-to-end step or SLA scenario covers its whole request path: the
 *    Stage 1 and 2 Auth calls (ValidateToken, AuthorizeAccountAccess), the Stage 3 reads of
 *    its endpoint, and the Config call, whose snapshot supplies every call's timeout and retry
 *    values and whose background refresh starts once access is allowed.
 *  - Stage 3 reads: balance summary GetAccount, ListCategoryBalances and ListTransactionTypes;
 *    category balances ListCategoryBalances, ListTransactionCategories and
 *    ListTransactionTypes; transaction list ListTransactions; transaction detail
 *    GetTransaction and GetCardCrossReference.
 *  - An assembly or retrieval story is Stage 3 and the cut-off only, after access is allowed,
 *    so it carries its Stage 3 reads and the Config call, and the guard owns the Auth outcome.
 *  - A client and its integration suite carry the calls of the one service they reach. The
 *    Auth suite also drives the Config refresh behind the guard's access-allowed hook, so it
 *    carries the Config call.
 *  - The call policy, the read-through cache, the Compose stack and the two harnesses serve
 *    every upstream call alike, so they carry an outcome stated for upstream calls in
 *    general. A story absent from this map carries the same general obligation.
 */
const BALANCE_PATH = [AUTH_CALLS, CARD_DATA_READS, REFERENCE_READS, CONFIG_CALL];
const LIST_PATH = [AUTH_CALLS, LIST_READ, CONFIG_CALL];
const DETAIL_PATH = [AUTH_CALLS, DETAIL_READ, CROSS_REFERENCE_READ, CONFIG_CALL];
const STAGE3_ONLY = (calls) => calls.filter((call) => call !== AUTH_CALLS);
const RESILIENCE_UPSTREAMS = new Map([
  ...['CS-05-006', 'CS-06-003', 'CS-01-004', 'CS-01-005', 'CS-02-001', 'CS-02-002'].map((id) => [id, BALANCE_PATH]),
  ...['CS-07-003', 'CS-03-001', 'CS-03-002', 'CS-03-003'].map((id) => [id, LIST_PATH]),
  ...['CS-08-003', 'CS-04-001', 'CS-04-002'].map((id) => [id, DETAIL_PATH]),
  ['CS-05-005', STAGE3_ONLY(BALANCE_PATH)],
  ['CS-06-002', STAGE3_ONLY(BALANCE_PATH)],
  ['CS-07-002', STAGE3_ONLY(LIST_PATH)],
  ['CS-08-002', STAGE3_ONLY(DETAIL_PATH)],
  ['CS-09-005', [CARD_DATA_READS, CROSS_REFERENCE_READ]],
  ['CS-09-006', [CARD_DATA_READS, CROSS_REFERENCE_READ]],
  ['CS-10-006', [LIST_READ, DETAIL_READ]],
  ['CS-10-007', [LIST_READ, DETAIL_READ]],
  ['CS-11-006', [CONFIG_CALL]],
  ['CS-11-007', [CONFIG_CALL]],
  ['CS-12-002', [AUTH_CALLS]],
  ['CS-12-003', [AUTH_CALLS]],
  ['CS-12-005', [AUTH_CALLS, CONFIG_CALL]],
  ['CS-13-003', [REFERENCE_READS]],
  ['CS-13-004', [REFERENCE_READS]],
  ...['CS-11-003', 'CS-11-004', 'CS-01-001', 'CS-01-002', 'CS-01-003'].map((id) => [id, [ANY_UPSTREAM]]),
]);

// A degrade or fail-hard outcome, and Config's own: the request left unaffected, its data
// status unchanged or FRESH, or the held values or built-in defaults used.
const OUTCOME_RE = /\bSTALE\b|\bPARTIAL\b|\b50[34]\b|\b[Ff]ail(?:s|ing)?[ -](?:closed|hard)\b|\b[Dd]egrad\w*|\b[Ff]alls? back\b|\b[Ff]allback\b/;
const CONFIG_OUTCOME_RE = /\b[Uu]naffected\b|\b[Uu]nchanged\b|\bFRESH\b|\b[Hh]eld (?:snapshot )?values\b|\b[Bb]uilt-in defaults\b/;
// Words that state no outcome of a service and are removed before matching: the test's own
// failure ("failure injection", "the step fails", "fails the scenario"), a citation of the
// degradation table, and a service named only as healthy.
const SERVICE_NAMES = 'Auth|Card Data|Transaction Activity|Config|Reference Data';
const OUTCOME_NOISE_RE = new RegExp(
  [
    String.raw`\bfailure[ -]injection\b`,
    String.raw`\b(?:the )?(?:step|scenario|suite|test|run) fails\b`,
    String.raw`\bfails? the (?:step|scenario|suite|test|run|build|gate)\b`,
    String.raw`\bdegradation table\b`,
    String.raw`\bhealthy (?:${SERVICE_NAMES})(?: stubs?)?`,
    String.raw`\b(?:${SERVICE_NAMES})(?: and (?:${SERVICE_NAMES}))? stubs? (?:healthy|at zero latency)\b`,
  ].join('|'),
  'gi',
);
const RETRY_RE = /\bretr(?:y|ies|ied|ying)\b/i;
const UNBOUNDED_RETRY_RE = /\bforever\b|\bindefinitely\b|\bendlessly\b|\bunlimited\b|\bunbounded\b|\bwithout (?:a |any )?(?:limit|cap|bound)\b|\buntil (?:it|the (?:call|read|upstream|request)) succeeds\b|\bno (?:retry )?(?:limit|cap)\b/i;
// Polarity of a stated value or verb within its clause. "not", "never", "cannot", "neither",
// "nor", "no longer" and "n't" govern everything after them up to the next comma, semicolon,
// colon or dash outside parentheses; a parenthetical that closes before the value is left
// out, and one that encloses it is not. "no", "none" and "without" govern that far only when
// they open the segment ("No attempt …", ", and no retry occurred …"); elsewhere they are a
// determiner of a noun ("with no filters") and govern only a value within the next three
// words, as does a negation inside a relative clause ("a call that has not answered
// leaves …"). A negation inside the value, or within the two words after it ("is not", "are
// never"), governs it too. A comparative bound such as "no more than" or "not later than"
// states a limit, and a hyphenated word such as "no-op" or "not-found" names a thing; neither
// is a negation.
const RESILIENCE_NEGATION_RE = /\b(?:not|never|no|none|neither|nor|without|cannot)\b(?!-)|n['\u2019]t\b/gi;
const RESILIENCE_NEGATION_TEST_RE = new RegExp(RESILIENCE_NEGATION_RE.source, 'i');
const RESILIENCE_DETERMINERS = new Set(['no', 'none', 'without']);
const RESILIENCE_RELATIVE_BEFORE_RE = /\b(?:that|which|who)\s+(?:[\w'\u2019-]+\s+){0,2}[\w-]*$/i;
const RESILIENCE_SEGMENT_OPENING_RE = /^[\s(]*(?:(?:and|but|or|so|yet|then|therefore|thus|hence)\s+)?$/i;
const RESILIENCE_NEAR_BEFORE_RE = /(?:\b(?:no|none|without)\b(?!-)|\b(?:that|which|who)\s+(?:[\w-]+\s+){0,2}?(?:(?:not|never|cannot)\b|\w+n['\u2019]t\b))\s+(?:[\w-]+\s+){0,2}$/i;
const RESILIENCE_BOUND_RE = /\b(?:no|not) (?:[\w-]+ ){0,6}?(?:more|later|earlier|fewer|less|longer|sooner) than\b/gi;
const RESILIENCE_NEGATED_AFTER_RE = /^[ \t]*(?:\([^()]{0,80}\)[ \t]*)?(?:[\w-]+[ \t]+){0,2}?(?:(?:not|never|cannot)\b|\w+n['\u2019]t\b)/i;
const RESILIENCE_SEGMENT_MARKS = new Set([',', ';', ':', '\u2014']);

/**
 * The polarity record of one clause, built in one pass so its cost follows the clause length:
 * negated[i] is 1 when a segment-wide negation governs text starting at offset i, segment[i]
 * numbers the comma, semicolon, colon or dash segment outside parentheses that holds offset
 * i, and starts[k] is the offset where segment k begins.
 */
function resiliencePolarity(text) {
  const masked = text.replace(RESILIENCE_BOUND_RE, (m) => ' '.repeat(m.length));
  const negationAt = new Uint8Array(text.length + 1);
  for (const m of masked.matchAll(RESILIENCE_NEGATION_RE)) {
    const before = masked.slice(Math.max(0, m.index - 40), m.index);
    const determiner = RESILIENCE_DETERMINERS.has(m[0].toLowerCase()) && !/^no\s+longer\b/i.test(masked.slice(m.index, m.index + 12));
    negationAt[m.index] = determiner ? 2 : RESILIENCE_RELATIVE_BEFORE_RE.test(before) ? 0 : 1;
  }
  const negated = new Uint8Array(text.length + 1);
  const segment = new Uint32Array(text.length + 1);
  const starts = [0];
  // One governing state and start offset per open parenthesis; a closing one drops what its
  // aside added.
  const levels = [0];
  const opened = [0];
  for (let i = 0; i < text.length; i++) {
    const top = levels.length - 1;
    negated[i] = levels[top];
    segment[i] = starts.length - 1;
    const ch = text[i];
    if (negationAt[i] === 1 || (negationAt[i] === 2 && i - opened[top] <= 40 && RESILIENCE_SEGMENT_OPENING_RE.test(masked.slice(opened[top], i)))) levels[top] = 1;
    if (ch === '(') {
      levels.push(levels[top]);
      opened.push(i + 1);
    } else if (ch === ')') {
      if (top > 0) {
        levels.pop();
        opened.pop();
      }
    } else if (top === 0 && RESILIENCE_SEGMENT_MARKS.has(ch)) {
      levels[0] = 0;
      opened[0] = i + 1;
      starts.push(i + 1);
    }
  }
  negated[text.length] = levels[levels.length - 1];
  segment[text.length] = starts.length - 1;
  return { text, masked, negated, segment, starts };
}

/** Whether a negation governs text.slice(start, end); inner false skips the match itself. */
function resilienceNegated(pol, start, end, inner = true) {
  return (
    pol.negated[start] === 1 ||
    RESILIENCE_NEAR_BEFORE_RE.test(pol.masked.slice(Math.max(0, start - 60), start)) ||
    (inner && RESILIENCE_NEGATION_TEST_RE.test(pol.masked.slice(start, end))) ||
    RESILIENCE_NEGATED_AFTER_RE.test(pol.masked.slice(end, end + 120))
  );
}

/** Whether the clause holds a match of the global pattern re that no negation governs. */
function resilienceAffirmed(pol, re, inner = true) {
  for (const m of pol.text.matchAll(re)) {
    if (!resilienceNegated(pol, m.index, m.index + m[0].length, inner)) return true;
  }
  return false;
}

// Each call-policy obligation is met only by an affirmative statement of its values in their
// relationship, within one sentence or clause: the attempt timing out at 300 ms or the lesser
// of 300 ms and the remaining budget; at most two retries or three attempts; 50 ms then
// 100 ms as the waits before the retries; retries on UNAVAILABLE and DEADLINE_EXCEEDED only,
// with no wider status set; a retry starting only when its backoff plus a full 300 ms attempt
// fits the remaining budget, never a bare name of that rule; and the 900 ms deadline bound to
// the cut-off.
const RESILIENCE_TIMEOUT_WORD_RE = /\btime(?:s|d)?[ -]?out\b|\btimeouts?\b|\battempt deadline\b|\bUPSTREAM_TIMEOUT_MS\b|\bupstream_timeout_ms\b/i;
const RESILIENCE_TIMEOUT_RE = /\btime[sd]? out (?:at|after) (?:the lesser of )?(?:the )?300\s*ms\b|\blesser of (?:the )?300\s*ms\b|\b300\s*ms (?:per-attempt |upstream |attempt )?(?:timeout|attempt deadline|UPSTREAM_TIMEOUT_MS)\b|\b(?:timeout|attempt deadline) (?:is|of) 300\s*ms\b|\bUPSTREAM_TIMEOUT_MS\s*=\s*300\b(?![,.]\d)/gi;
const RESILIENCE_CAP_RE = /\b(?:at most|up to|no more than|maximum(?: of)?|cap(?: of)?:?|capped at)\s+(?:two|2)\b(?![,.]\d)|\b(?:two|2) retries\b|\bMAX_RETRIES\s*=\s*2\b(?![,.]\d)|\b(?:three|3) attempts\b/gi;
const RESILIENCE_BACKOFF_WORD_RE = /\bback-?offs?\b/i;
const RESILIENCE_BACKOFF_RE = /\b50\s*ms\b[^;]{0,120}?\b100\s*ms\b/g;
const RESILIENCE_STATUS_PAIR = String.raw`(?:UNAVAILABLE(?: (?:or|and) | ?\/ ?)DEADLINE_EXCEEDED|DEADLINE_EXCEEDED(?: (?:or|and) | ?\/ ?)UNAVAILABLE)`;
const RESILIENCE_STATUS_ONLY_RE = new RegExp(
  String.raw`\bonly (?:(?:on|after|for|upon|with) )?(?:an? |the )?${RESILIENCE_STATUS_PAIR}\b|\b(?:on|after|for|upon) (?:an? )?${RESILIENCE_STATUS_PAIR}(?: (?:outcomes?|status(?:es)?|answers?|failures?))? only\b`,
  'gi',
);
const RESILIENCE_STATUS_NAMED_RE = new RegExp(String.raw`\b${RESILIENCE_STATUS_PAIR}\b`, 'g');
// "Never after any other status", "any other status is not retried": the exclusion stated
// as its own phrase, whose own negation is the statement.
const RESILIENCE_STATUS_OTHERS_RE = /\b(?:never|not) (?:on|after|for|upon) any other (?:status|statuses|code|codes|outcome|outcomes)\b|\b(?:any|every|all) other (?:status|statuses|code|codes|outcome|outcomes)(?:,[^,;]{1,80},)? (?:is|are) (?:not|never) retried\b|\bno other (?:status|statuses|code|codes|outcome|outcomes) (?:is|are) retried\b/gi;
const RESILIENCE_STATUS_WIDENING_RE = /\bnot only\b|\bbut also\b|\balso (?:on|after|for|upon)\b|\bas well as\b|\bincluding\b|\bin addition to\b|\bretr(?:y|ies|ied|ying) (?:on |after |for )?(?:every|any|all) (?:other )?(?:status|statuses|codes?|outcomes?|failures?|errors?)\b/i;
const RESILIENCE_FIT_RE = /\bonly (?:when|if|while) (?:its |the |their |each |a )?(?:(?:50|100)\s*ms )?back-?off plus (?:(?:a |one )?full (?:300\s*ms )?attempt|300\s*ms)(?: still)? fits\b(?=[ \t]*(?:$|[.,;:)]|(?:in(?:to)? |within )?(?:the |its |their )?(?:remaining|residual|budget|time|deadline|request|refresh)\b|before\b))/gi;
const RESILIENCE_FIT_WEAKENING_RE = /\bregardless\b|\birrespective\b|\bwhether or not\b|\beven (?:when|if|though)\b/i;
const RESILIENCE_DEADLINE_RE = /\b900\s*ms\b/g;
const RESILIENCE_CUTOFF_RE = /\bcut-?offs?\b|\bcutoffMs\b/gi;
// Each test takes the clauses of one criterion and the index of the one it reads; the
// retried-status exclusion may stand in the clause after the one naming the two statuses.
const RESILIENCE_OBLIGATIONS = [
  ['the 300 ms per-attempt timeout', (cs, i) => RESILIENCE_TIMEOUT_WORD_RE.test(cs[i].text) && resilienceAffirmed(cs[i].pol, RESILIENCE_TIMEOUT_RE)],
  ['the cap of two retries', (cs, i) => RETRY_RE.test(cs[i].text) && resilienceAffirmed(cs[i].pol, RESILIENCE_CAP_RE)],
  ['the 50 ms then 100 ms backoff', (cs, i) => (RETRY_RE.test(cs[i].text) || RESILIENCE_BACKOFF_WORD_RE.test(cs[i].text)) && resilienceAffirmed(cs[i].pol, RESILIENCE_BACKOFF_RE)],
  [
    'that only UNAVAILABLE and DEADLINE_EXCEEDED are retried',
    (cs, i) => {
      const c = cs[i];
      if (!RETRY_RE.test(c.text) || RESILIENCE_STATUS_WIDENING_RE.test(c.text)) return false;
      if (resilienceAffirmed(c.pol, RESILIENCE_STATUS_ONLY_RE)) return true;
      if (!resilienceAffirmed(c.pol, RESILIENCE_STATUS_NAMED_RE)) return false;
      return [c, cs[i + 1]].some((o) => o && !RESILIENCE_STATUS_WIDENING_RE.test(o.text) && resilienceAffirmed(o.pol, RESILIENCE_STATUS_OTHERS_RE, false));
    },
  ],
  [
    'that a retry starts only when its backoff plus a full 300 ms attempt fits the remaining budget',
    (cs, i) => RETRY_RE.test(cs[i].text) && !RESILIENCE_FIT_WEAKENING_RE.test(cs[i].text) && resilienceAffirmed(cs[i].pol, RESILIENCE_FIT_RE),
  ],
  ['the 900 ms request deadline measured to the cut-off', (cs, i) => resilienceAffirmed(cs[i].pol, RESILIENCE_DEADLINE_RE) && resilienceAffirmed(cs[i].pol, RESILIENCE_CUTOFF_RE)],
];

/**
 * The sentences and semicolon-separated clauses of an acceptance criterion as it renders
 * (`ac.text`), with code spans unwrapped and SCOPE-QUESTION (Open) paragraphs left out,
 * because an open question settles nothing. A line blank as written (`ac.written`) ends
 * such a paragraph, so a line that renders blank because a comment fills it keeps it open.
 */
function resilienceClauses(ac) {
  const written = splitLines(ac.written);
  let open = false;
  const settled = splitLines(ac.text).filter((l, k) => {
    if (normaliseLabel(written[k]) === '') open = false;
    else if (/^SCOPE-QUESTION \(Open\)/.test(normaliseLabel(l))) open = true;
    return !open;
  });
  return settled
    .join('\n')
    .replace(/`/g, '')
    .replace(/\b(e\.g|i\.e)\./g, '$1')
    .split(/\n|;\s+|(?<=[.!?])\s+(?=[A-Z0-9("])/)
    .map((c) => c.trim())
    .filter((c) => c !== '');
}

const OUTCOME_ANY_RE = new RegExp(OUTCOME_RE.source, 'g');
const CONFIG_OUTCOME_ANY_RE = new RegExp(CONFIG_OUTCOME_RE.source, 'g');
// The operations of each service, which with its name tell whose outcome a segment states.
const UPSTREAM_OPERATION_RES = {
  Auth: /\bValidateToken\b|\bAuthorizeAccountAccess\b/,
  'Card Data': /\bGetAccount\b|\bListCategoryBalances\b|\bGetCardCrossReference\b/,
  'Transaction Activity': /\bListTransactions\b|\bGetTransaction\b/,
  Config: /\bGetConfig\b/,
  'Reference Data': /\bListTransactionTypes\b|\bListTransactionCategories\b/,
};

/**
 * Whether a clause that names an upstream call states its outcome: a degrade or fail-hard
 * outcome, or for Config the request left unaffected, that no negation governs ("never fails
 * closed", "does not return 503" state none) and whose comma, semicolon, colon or dash
 * segment names that call's service or no other service, so another service's outcome in
 * the same sentence is not taken for it. Each segment is read at most once.
 */
function upstreamOutcomeStated(pol, known, config) {
  const owners = new Map();
  const owns = (k) => {
    if (!known) return true;
    if (!owners.has(k)) {
      const seg = pol.text.slice(pol.starts[k], k + 1 < pol.starts.length ? pol.starts[k + 1] : pol.text.length);
      const self = known.re.test(seg) || UPSTREAM_SERVICE_RES[known.service].test(seg);
      owners.set(k, self || !Object.keys(UPSTREAM_SERVICE_RES).some((s) => s !== known.service && (UPSTREAM_SERVICE_RES[s].test(seg) || UPSTREAM_OPERATION_RES[s].test(seg))));
    }
    return owners.get(k);
  };
  for (const re of config ? [OUTCOME_ANY_RE, CONFIG_OUTCOME_ANY_RE] : [OUTCOME_ANY_RE]) {
    for (const m of pol.text.matchAll(re)) {
      if (!resilienceNegated(pol, m.index, m.index + m[0].length) && owns(pol.segment[m.index])) return true;
    }
  }
  return false;
}

/**
 * Group A resilience obligations of an "External integration or SLA-bound: Yes" story: each
 * one its acceptance criteria leave unmet, as a phrase that follows "acceptance criteria".
 * Every obligation holds only by an affirmative statement within one sentence or clause, so
 * disconnected keywords, negated values and bare rule names never meet it, and the degrade or
 * fail-hard outcome is required once per canonical upstream call. `acs` are the criteria of
 * checkSourceBody, read as they render through resilienceClauses.
 */
function unmetResilienceObligations(storyKey, acs) {
  const perAc = acs.map((ac) => resilienceClauses(ac).map((text) => ({ text, pol: resiliencePolarity(text) })));
  const unmet = RESILIENCE_OBLIGATIONS.filter(([, holds]) => !perAc.some((cs) => cs.some((_, i) => holds(cs, i)))).map(([what]) => `do not state ${what}`);
  const clauses = perAc.flat().map((c) => c.text);
  const unbounded = clauses.find((c) => RETRY_RE.test(c) && UNBOUNDED_RETRY_RE.test(c));
  if (unbounded) unmet.push(`state unbounded retries ("${unbounded.length > 80 ? `${unbounded.slice(0, 77)}...` : unbounded}"); a call makes at most two retries`);
  const outcomes = clauses.map((c) => resiliencePolarity(c.replace(OUTCOME_NOISE_RE, ' ')));
  const calls = RESILIENCE_UPSTREAMS.get(storyKey) ?? [ANY_UPSTREAM];
  for (const call of calls) {
    const known = UPSTREAM_CALLS[call];
    const alone = known && !calls.some((other) => other !== call && UPSTREAM_CALLS[other]?.service === known.service);
    const names = (c) => (known ? known.re.test(c) || (alone && UPSTREAM_SERVICE_RES[known.service].test(c)) : ANY_UPSTREAM_RE.test(c));
    const config = call === CONFIG_CALL;
    if (!outcomes.some((pol) => names(pol.text) && upstreamOutcomeStated(pol, known, config))) {
      const outcome = config ? 'the request left unaffected' : 'STALE, PARTIAL, 503 or 504, fail closed or hard, or degrade';
      unmet.push(`do not state the degrade or fail-hard outcome of the ${call} (${outcome}) in a sentence naming the call or its service`);
    }
  }
  return unmet;
}

// An inner run of spaces and tabs, which renders as one space; leading indentation is not inner.
const INNER_SPACE_RUN_RE = /(?<=\S)[ \t]{2,}(?=\S)/g;

/**
 * The stripped body lines as they render, element k - 1 being stripped body line k.
 * blankComments over the quoted body empties each HTML comment block line and turns each
 * inline comment into spaces, quote depth included, and the quote markers are then removed
 * as for the stripped body, so every line keeps its number. On a line a comment changed,
 * each inner run of spaces and tabs reads as one space, as it renders, so a comment beside
 * visible text leaves the words around it adjacent; every other line is `bodyLines[k - 1]`.
 */
function renderedBodyLines(quotedBody, bodyLines) {
  return splitLines(stripBlockquote(blankComments(quotedBody))).map((l, i) =>
    l === bodyLines[i] ? l : l.replace(INNER_SPACE_RUN_RE, ' '),
  );
}

/**
 * Groups S, A and D on the reproduced source body: every line quoted, sections 1–3 present
 * once each in order and no other heading, the protected text equal to its creation-time
 * record, story statement, trace, the section 1 and section 3 subparts, classification
 * lines and AC1–ACn with their authorization and resilience obligations. Returns the AC
 * identifiers, or null when section 2 cannot be read.
 */
function checkSourceBody(story, text, lines, v) {
  const rel = story.rel;
  const source = h2Section(text, 'Source Story (verbatim)');
  if (!source) return null;
  for (let l = source.startLine + 1; l <= source.endLine; l++) {
    const line = lines[l - 1];
    if (line.trim() !== '' && !line.startsWith('>')) {
      v.push({ path: rel, line: l, message: 'reproduced source body line is not quoted; every source body line starts with ">"' });
    }
  }

  // Line k (1-based) of the quoted body and of the stripped body is document line offset + k.
  // Headings, section bounds and the lines of fenced code and HTML comment blocks come from
  // the quoted body, which keeps each line's quote depth, so a block opened in a nested note
  // ends with that note. The stripped body, every quote level removed, places note and
  // paragraph bounds as written. What the statement, label, classification-note and criterion
  // predicates read, nested notes' criteria included, is that body as it renders (shownLines),
  // so text inside an HTML comment meets no obligation; the quote markers, the creation-time
  // record and the headings are read as written.
  const offset = source.startLine;
  const quotedBody = lines.slice(source.startLine, source.endLine).join('\n');
  const body = stripBlockquote(quotedBody);
  const bodyLines = splitLines(body);
  const bodyHidden = hiddenLines(quotedBody);
  const shownLines = renderedBodyLines(quotedBody, bodyLines);
  const quoted = quotedSourceLines(lines, source, bodyHidden);
  // A blank line without ">" between the first and the last quoted line ends the blockquote,
  // fenced or not; the blank lines before and after the body stand outside it, and a
  // non-blank line without ">" is reported above.
  const firstMarked = quoted.findIndex((q) => q.marked);
  const lastMarked = quoted.findLastIndex((q) => q.marked);
  for (let k = firstMarked + 1; k < lastMarked; k++) {
    if (!quoted[k].marked && quoted[k].text === '') {
      v.push({ path: rel, line: offset + k + 1, message: 'reproduced source body line is blank and not quoted, which ends the blockquote and splits the body in two; a blank line inside the body carries ">"' });
    }
  }
  checkSourceRecord(story, quoted, source.startLine, v);
  const bodyHeadings = parseHeadings(quotedBody);
  let maxIndex = -1;
  const firstSeen = new Set();
  for (const h of bodyHeadings) {
    const name = normaliseSpace(h.text);
    const index = h.level === 3 ? BODY_SECTIONS.indexOf(name) : -1;
    if (index < 0) {
      v.push({ path: rel, line: offset + h.line, message: unexpectedBodyHeading(`${'#'.repeat(h.level)} ${name}`) });
      continue;
    }
    if (firstSeen.has(name)) {
      v.push({ path: rel, line: offset + h.line, message: `duplicate source body heading "### ${name}"` });
      continue;
    }
    firstSeen.add(name);
    if (index < maxIndex) {
      v.push({ path: rel, line: offset + h.line, message: `source body heading "### ${name}" is out of order; it must precede "### ${BODY_SECTIONS[maxIndex]}"` });
    } else {
      maxIndex = index;
    }
  }
  for (const name of BODY_SECTIONS) {
    if (!firstSeen.has(name)) v.push({ path: rel, line: source.startLine, message: `source body has no "### ${name}" heading` });
  }
  checkContainerHeadings(rel, bodyLines, bodyHidden, bodyHeadings, offset, v);

  // A section bounded in the quoted body, its text taken from the same lines of the stripped
  // body as it renders.
  const bodySection = (name) => {
    const s = sectionByHeading(quotedBody, name);
    return s && { ...s, text: shownLines.slice(s.startLine - 1, s.endLine).join('\n') };
  };
  const details = firstSeen.has(BODY_SECTIONS[0]) ? bodySection(BODY_SECTIONS[0]) : null;
  if (details) {
    // SCOPE-QUESTION notes may stand anywhere and are not the opening line.
    const k = splitLines(details.text).findIndex((l, i) => i > 0 && l.trim() !== '' && !quoted[details.startLine + i - 1].note);
    const statementLine = k < 0 ? details.startLine : details.startLine + k;
    const statement = k < 0 ? '' : normaliseLabel(splitLines(details.text)[k]);
    if (!STATEMENT_RE.test(statement)) {
      v.push({ path: rel, line: offset + statementLine, message: 'section 1 must open with the story statement "As a <role>, I want <capability>, so that <outcome>."' });
    }
    checkDetailsSubparts(rel, quoted, details, offset, v);
  }
  const dependencies = firstSeen.has(BODY_SECTIONS[2]) ? bodySection(BODY_SECTIONS[2]) : null;
  if (dependencies) checkDependencySubparts(rel, quoted, dependencies, offset, v);

  // Label lines as they render, outside fences, comment blocks and SCOPE-QUESTION notes, which
  // are not content.
  const labelled = (label) =>
    shownLines
      .map((l, i) => ({ text: normaliseLabel(l), line: i + 1 }))
      .filter((l) => !bodyHidden.has(l.line) && !quoted[l.line - 1].note && l.text.startsWith(label));
  const traces = labelled('Trace:');
  if (traces.length !== 1) {
    v.push({ path: rel, line: source.startLine, message: `source body carries ${traces.length} "Trace:" lines; exactly one is required` });
  }
  for (const t of traces) {
    for (const message of traceProblems(t.text.replace(/`/g, ''))) v.push({ path: rel, line: offset + t.line, message });
  }
  const role = STORY_ROLES.get(story.key);
  if (!role) {
    v.push({ path: rel, line: source.startLine, message: `${story.key} has no entry in the canonical classification map, so its classification lines cannot be checked against its role` });
  }
  // A line's effective value is its own Yes or No, or the one value that Resolved notes
  // directly beneath it state. An obligation runs when the effective value or the story's
  // role says Yes, so relabelling or deleting a line cannot switch an obligation off.
  const classification = (label, re, index) => {
    const expected = role ? CLASSIFICATION_ROLES[role][index] : null;
    const obliged = (value) => (value === 'Yes' || expected === 'Yes' ? 'Yes' : value);
    const found = labelled(label);
    if (found.length !== 1) {
      v.push({ path: rel, line: source.startLine, message: `source body carries ${found.length} "${label}" lines; exactly one is required` });
      return obliged(null);
    }
    const m = re.exec(found[0].text);
    if (!m) v.push({ path: rel, line: offset + found[0].line, message: `classification line must read "${label} Yes" or "${label} No"` });
    let value = m ? m[1] : null;
    const note = resolvedClassification(bodyLines, shownLines, found[0].line, label);
    if (note.values.length > 1) {
      v.push({ path: rel, line: offset + note.line, message: `SCOPE-QUESTION (Resolved) notes beneath "${label}" state both Yes and No; they must resolve the line to one value` });
    } else if (note.values.length === 1) {
      value = note.values[0];
    }
    if (expected && value && value !== expected) {
      v.push({ path: rel, line: offset + found[0].line, message: `"${label}" resolves to ${value}, but ${story.key} is ${CLASSIFICATION_ROLES[role][2]}, so it must resolve to ${expected} (a SCOPE-QUESTION (Resolved) note directly beneath the line states "${label} ${expected}")` });
    }
    return obliged(value);
  };
  const access = classification(ACCESS_LABEL, ACCESS_RE, 0);
  const external = classification(EXTERNAL_LABEL, EXTERNAL_RE, 1);

  const acSection = firstSeen.has(BODY_SECTIONS[1]) ? bodySection(BODY_SECTIONS[1]) : null;
  if (!acSection) return null;
  const acHeadLine = offset + acSection.startLine;
  const acs = [];
  // The entry that continuation lines join. It is null before the first criterion and after
  // a line that is reported and kept out, whose own continuation lines then go with it.
  let current = null;
  let started = false;
  let inNote = false;
  const preText = 'section 2 text before the first acceptance criterion is neither a criterion nor a SCOPE-QUESTION note';
  // Each line `l` is read as it renders; the same line as written (`written`) places the note
  // and paragraph bounds. A criterion keeps both: `text` for its predicates, `written` for
  // the bounds of the notes inside it.
  splitLines(acSection.text).forEach((l, k) => {
    const bodyLine = acSection.startLine + k;
    if (k === 0) return;
    const line = offset + bodyLine;
    const written = bodyLines[bodyLine - 1];
    if (bodyHidden.has(bodyLine)) {
      if (!started && !inNote && !bodyHidden.has(bodyLine - 1)) v.push({ path: rel, line, message: preText });
      return;
    }
    if (written.trim() === '') inNote = false;
    else if (AC_NOTE_START_RE.test(written)) inNote = true;
    const m = AC_START_RE.exec(l);
    if (m) {
      started = true;
      inNote = false;
      const problem = acNumberProblem(m[1]);
      if (problem) {
        v.push({ path: rel, line, message: `acceptance criterion ${acLabel(m[1])} ${problem}` });
        current = null;
      } else {
        current = { num: Number(m[1]), line, text: l, written };
        acs.push(current);
      }
    } else if (isMalformedAcLine(l, inNote)) {
      started = true;
      current = null;
      v.push({ path: rel, line, message: `malformed acceptance-criterion line "${lineExcerpt(l)}"; a criterion starts "- AC<n>: " at the left margin of section 2` });
    } else if (current) {
      current.text += `\n${l}`;
      current.written += `\n${written}`;
    } else if (!started && !inNote && l.trim() !== '') v.push({ path: rel, line, message: preText });
  });
  if (acs.length === 0) {
    v.push({ path: rel, line: acHeadLine, message: 'section 2 holds no acceptance criterion numbered AC1–ACn' });
    return [];
  }
  const nums = new Set();
  let maxNum = 0;
  for (const ac of acs) {
    if (nums.has(ac.num)) v.push({ path: rel, line: ac.line, message: `duplicate acceptance criterion AC${ac.num}` });
    else if (ac.num < maxNum) v.push({ path: rel, line: ac.line, message: `acceptance criterion AC${ac.num} is out of order after AC${maxNum}` });
    nums.add(ac.num);
    maxNum = Math.max(maxNum, ac.num);
    const problem = acCriterionProblem(ac);
    if (problem) v.push({ path: rel, line: ac.line, message: `acceptance criterion AC${ac.num} ${problem}` });
  }
  // Gaps lie between consecutive sorted numbers, so the work follows the entry count and
  // never the largest number a document writes; each gap is one diagnostic.
  const sorted = [...nums].sort((a, b) => a - b);
  let prev = 0;
  for (const n of sorted) {
    if (n > prev + 1) {
      const missing = n - 1 === prev + 1 ? `acceptance criterion AC${prev + 1} is missing` : `acceptance criteria AC${prev + 1}–AC${n - 1} are missing`;
      v.push({ path: rel, line: acHeadLine, message: `${missing}; criteria run AC1–AC${maxNum} with no gap` });
    }
    prev = n;
  }

  if (access === 'Yes') {
    const parts = acs.map((ac) => ({ ac, ...authorizationParts(ac.text) }));
    for (const p of parts) {
      if (p.readFirst) {
        v.push({ path: rel, line: p.ac.line, message: `account-data access path story: AC${p.ac.num} states that account data is read before access is allowed ("${p.readFirst}"); access is decided before every account-data read, cache reads included` });
      }
    }
    if (!parts.some((p) => p.denial && p.outside && p.noRead && !p.readFirst)) {
      const named = [
        ['denial', 'a non-negated "returns 403 … PERMISSION_DENIED" outcome'],
        ['outside', 'an account outside the caller\'s entitlements'],
        ['noRead', 'the statement that no account data is read before access is allowed'],
      ];
      const held = (p) => named.filter(([k]) => p[k]).length;
      const best = parts.reduce((a, p) => (held(p) > (a ? held(a) : 0) ? p : a), null);
      const missing = best ? named.filter(([k]) => !best[k]).map(([, what]) => what) : [];
      const lacks = !best ? '' : missing.length ? `; AC${best.ac.num} comes closest and lacks ${missing.join(' and ')}` : `; AC${best.ac.num} states all three but also states that account data is read before access is allowed`;
      v.push({ path: rel, line: acHeadLine, message: `account-data access path story: no acceptance criterion requires 403 with PERMISSION_DENIED for an account outside the caller's entitlements and states that no account data is read before access is allowed${lacks}` });
    }
  }
  if (external === 'Yes') {
    for (const what of unmetResilienceObligations(story.key, acs)) {
      v.push({ path: rel, line: acHeadLine, message: `external integration or SLA-bound story: acceptance criteria ${what}` });
    }
  }
  return sorted.map((n) => `AC${n}`);
}

/**
 * Group A diagnostics for one "Trace:" line with its backticks removed; none when it has the
 * CARD_REQ_TBD form. A numbered CARD_REQ identifier or a Requirements Analysis citation cannot
 * be resolved: Requirements Analysis is absent from this delivery, this check reads only the
 * epics area, and a CARD_REQ_NNN identifier is never invented.
 */
function traceProblems(text) {
  if (TRACE_RE.test(text)) return [];
  const problems = [];
  const numbered = /CARD_REQ_(?!TBD(?![A-Za-z0-9_]))([A-Za-z0-9_]+)/.exec(text);
  if (numbered) {
    problems.push(`trace names CARD_REQ_${numbered[1]}, which cannot be resolved: Requirements Analysis is absent from this delivery, this check reads only ${EPICS_DIR}, and a CARD_REQ_NNN identifier is never invented; the trace carries CARD_REQ_TBD`);
  }
  const citesAnalysis = /·\s*Requirements Analysis\s*(?:·|$)/.test(text);
  if (citesAnalysis) {
    problems.push('trace cites Requirements Analysis, which is absent from this delivery and cannot be resolved; the trace cites "Use Cases · <program>" or "Data Layouts · <copybook> · <field>"');
  }
  if (!citesAnalysis && !TRACE_RE.test(text.replace(/CARD_REQ_[A-Za-z0-9_]+/, 'CARD_REQ_TBD'))) {
    problems.push('trace must read "Trace: CARD_REQ_TBD · Use Cases · <program>" or "Trace: CARD_REQ_TBD · Data Layouts · <copybook> · <field>"');
  }
  return problems;
}

// One blockquote level with the space after it.
const ONE_QUOTE_RE = /^[ \t]{0,3}>[ \t]?/;
// A SCOPE-QUESTION note at the body's left margin, one indented as a list item's paragraph
// (">     SCOPE-QUESTION …"), and one inside a nested quote, whose quote prefix ("> > ",
// ">   > ", ">     > " …) is group 1.
const TOP_NOTE_RE = /^SCOPE-QUESTION\b/;
const INDENTED_NOTE_RE = /^[ \t]+SCOPE-QUESTION\b/;
const NESTED_NOTE_RE = /^((?:[ \t]*>)+)[ \t]?SCOPE-QUESTION\b/;
// A list marker at the start of a line.
const LIST_MARK_RE = /^(?:[-*+]|\d{1,9}[.)])(?=[ \t]|$)/;
// A line that opens a block of its own after any indentation, and so ends a note paragraph
// at the body's left margin or indented: a list item, an ATX heading, a nested quote or a
// code fence.
const BLOCK_START_RE = /^[ \t]*(?:(?:[-*+]|\d{1,9}[.)]|#{1,6})(?=[ \t]|$)|>|`{3}|~{3})/;
// Section 1 label lines in their required order; "Directive:" applies to platform stories.
const DETAIL_PARTS = ['Trace:', 'Directive:', ACCESS_LABEL, EXTERNAL_LABEL, 'Capability:', 'In scope:', 'Not in this story:'];
// A backticked target path: a root file or a path below a root that the stories build.
const TARGET_PATH_RE = /^(?:settings\.gradle|gradlew|gradlew\.bat|\.gitignore|(?:gradle|\.github|card-servicing-service|card-servicing-contracts|tests)\/\S*)$/;
const TARGET_ROOTS = 'settings.gradle, gradlew, gradlew.bat, .gitignore, gradle/, .github/, card-servicing-service/, card-servicing-contracts/ or tests/';
// A section 3 prerequisite: a story identifier or None, an em dash and the reason.
const PREREQUISITE_RE = /^[-*+][ \t]+(?:CS-\d{2}-\d{3}|None)[ \t]+—[ \t]+\S/;
// Container markers before a line's content (quote markers, list markers, indentation),
// then an ATX heading.
const CONTAINER_PREFIX_RE = /^(?:[ \t]*(?:>|(?:[-*+]|\d{1,9}[.)])(?=[ \t]|$)))*[ \t]*/;
const ATX_OPEN_RE = /^#{1,6}(?=[ \t]|$)/;

/**
 * The ATX heading a line holds once its container markers are removed, or null: its "#"
 * run and its text without the spaces and tabs around it or a closing "#" run that a space
 * or tab precedes. Index scans from both ends keep the work linear in the line's length.
 */
function atxHeading(line) {
  const open = ATX_OPEN_RE.exec(line);
  if (!open) return null;
  const blank = (i) => line[i] === ' ' || line[i] === '\t';
  let start = open[0].length;
  while (start < line.length && blank(start)) start++;
  let end = line.length;
  while (end > start && blank(end - 1)) end--;
  let close = end;
  while (close > start && line[close - 1] === '#') close--;
  if (close < end && close > start && blank(close - 1)) {
    end = close;
    while (end > start && blank(end - 1)) end--;
  }
  return { hashes: open[0], text: line.slice(start, end) };
}

/** The diagnostic for a heading that the reproduced source body must not carry. */
function unexpectedBodyHeading(heading) {
  return `unexpected heading "${heading}" in the reproduced source body; it holds no heading but "### ${BODY_SECTIONS[0]}", "### ${BODY_SECTIONS[1]}" and "### ${BODY_SECTIONS[2]}"`;
}

/**
 * The reproduced source body line by line with one blockquote level removed:
 * { raw, text, marked, fenced, note }, element k - 1 being stripped body line k. `raw` is the
 * line without its quotation markup (the indentation before one ">", the ">" and the space or
 * tab after it), trailing whitespace kept; `text` is `raw` with trailing whitespace trimmed.
 * `marked` is whether the line carried that markup, so an unquoted blank line, which ends the
 * blockquote, stays distinct from a quoted ">" blank line although both have `text` "".
 * `note` marks a line of a "> SCOPE-QUESTION" note, Open or Resolved. A note at the body's
 * left margin, or indented by spaces or tabs as a paragraph of a list item
 * (">     SCOPE-QUESTION …"), is its paragraph, up to the next blank quoted line or the next
 * line that opens a list item, a heading, a nested quote or a fence, any of which interrupts
 * a paragraph in CommonMark; a criterion or list item directly beneath it stays protected
 * text. A note inside a nested quote ("> > ", ">   > ", ">     > " …) is its opening line
 * plus every following line that carries the same nested-quote prefix, its blank nested
 * lines and any criteria inside it included. A fenced line opens no note.
 */
function quotedSourceLines(lines, source, fences) {
  const out = [];
  let note = null;
  for (let l = source.startLine + 1; l <= source.endLine; l++) {
    const marked = ONE_QUOTE_RE.test(lines[l - 1]);
    const raw = lines[l - 1].replace(ONE_QUOTE_RE, '');
    const text = raw.trimEnd();
    const fenced = fences.has(l - source.startLine);
    if (note !== null) {
      const rest = note.prefix === null ? '' : text.slice(note.prefix.length);
      const inside =
        note.prefix === null
          ? text !== '' && !fenced && !BLOCK_START_RE.test(text)
          : text.startsWith(note.prefix) && (rest === '' || /^[ \t>]/.test(rest));
      if (!inside) note = null;
    }
    if (note === null && !fenced) {
      const nested = NESTED_NOTE_RE.exec(text);
      if (TOP_NOTE_RE.test(text) || INDENTED_NOTE_RE.test(text)) note = { prefix: null };
      else if (nested) note = { prefix: nested[1] };
    }
    out.push({ raw, text, marked, fenced, note: note !== null });
  }
  return out;
}

/**
 * Group S: headings held by a list item, a nested quote or deeper indentation, which
 * parseHeadings does not report. The reproduced source body carries none.
 */
function checkContainerHeadings(rel, bodyLines, fences, headings, offset, v) {
  const parsed = new Set(headings.map((h) => h.line));
  bodyLines.forEach((l, i) => {
    if (fences.has(i + 1) || parsed.has(i + 1)) return;
    const h = atxHeading(l.replace(CONTAINER_PREFIX_RE, ''));
    if (h) v.push({ path: rel, line: offset + i + 1, message: unexpectedBodyHeading(`${h.hashes} ${normaliseSpace(h.text)}`.trim()) });
  });
}

/** Whether a line, quote markers removed, is a list item with text. */
const isBullet = (text) => LIST_MARK_RE.test(text) && text.replace(LIST_MARK_RE, '').trim() !== '';

/**
 * Groups S and A on section 1 below the story statement, SCOPE-QUESTION notes and fenced
 * lines left out: "Trace:", "Directive:" where it applies, the two classification lines,
 * "Capability:" with text and at least one backticked target path beneath it, then
 * "In scope:" and "Not in this story:" each with at least one bullet, in that order. Label
 * lines stand at the left margin of the body, outside lists. checkSourceBody counts and
 * reads the Trace and classification lines; here they take part in the order only.
 */
function checkDetailsSubparts(rel, quoted, details, offset, v) {
  const content = [];
  for (let k = details.startLine + 1; k <= details.endLine; k++) {
    const q = quoted[k - 1];
    if (!q.note && !q.fenced && q.text.trim() !== '') content.push({ text: q.text, line: offset + k });
  }
  const parts = [];
  content.forEach((c, at) => {
    if (/^[\s>]/.test(c.text) || LIST_MARK_RE.test(c.text)) return;
    const label = normaliseLabel(c.text);
    const index = DETAIL_PARTS.findIndex((p) => label.startsWith(p));
    if (index >= 0) parts.push({ index, name: DETAIL_PARTS[index], value: label.slice(DETAIL_PARTS[index].length).trim(), line: c.line, at });
  });
  let maxIndex = -1;
  const seen = new Set();
  for (const p of parts) {
    if (seen.has(p.index)) continue;
    seen.add(p.index);
    if (p.index < maxIndex) {
      v.push({ path: rel, line: p.line, message: `section 1 "${p.name}" line is out of order; it must precede "${DETAIL_PARTS[maxIndex]}"` });
    } else {
      maxIndex = p.index;
    }
  }
  // The content a label line governs: the lines after it, up to the next label line.
  const block = (p) => {
    const next = parts.find((n) => n.at > p.at);
    return content.slice(p.at + 1, next ? next.at : content.length);
  };
  for (const name of ['Directive:', 'Capability:', 'In scope:', 'Not in this story:']) {
    const found = parts.filter((p) => p.name === name);
    const optional = name === 'Directive:';
    if (found.length > 1 || (found.length === 0 && !optional)) {
      const line = found.length > 1 ? found[1].line : offset + details.startLine;
      v.push({ path: rel, line, message: `section 1 carries ${found.length} "${name}" lines; ${optional ? 'at most one is allowed' : 'exactly one is required'}` });
    }
    if (found.length !== 1) continue;
    const [p] = found;
    if ((name === 'Directive:' || name === 'Capability:') && p.value === '') {
      v.push({ path: rel, line: p.line, message: `section 1 "${name}" line carries no text` });
    }
    if (name === 'Capability:' && !block(p).some((c) => [...c.text.matchAll(/`([^`]+)`/g)].some((m) => TARGET_PATH_RE.test(m[1].trim())))) {
      v.push({ path: rel, line: p.line, message: `section 1 names no target path beneath "Capability:"; at least one backticked repository path under ${TARGET_ROOTS} is required` });
    }
    if ((name === 'In scope:' || name === 'Not in this story:') && !block(p).some((c) => isBullet(c.text))) {
      v.push({ path: rel, line: p.line, message: `section 1 "${name}" carries no bullet; at least one is required` });
    }
  }
}

/**
 * Groups S and A on section 3, SCOPE-QUESTION notes and fenced lines left out: at least one
 * prerequisite bullet, every bullet reading "- <CS-NN-NNN or None> — <reason>", then exactly
 * one "Risk:" line with text.
 */
function checkDependencySubparts(rel, quoted, deps, offset, v) {
  let bullets = 0;
  const risks = [];
  for (let k = deps.startLine + 1; k <= deps.endLine; k++) {
    const q = quoted[k - 1];
    if (q.note || q.fenced || q.text.trim() === '') continue;
    const line = offset + k;
    if (LIST_MARK_RE.test(q.text)) {
      bullets++;
      if (!PREREQUISITE_RE.test(q.text)) {
        v.push({ path: rel, line, message: 'section 3 bullet must read "- <CS-NN-NNN or None> — <reason>"' });
      } else if (risks.length) {
        v.push({ path: rel, line, message: 'section 3 prerequisite bullet follows the "Risk:" line; prerequisites come first' });
      }
    } else if (!/^[\s>]/.test(q.text) && normaliseLabel(q.text).startsWith('Risk:')) {
      risks.push({ line, value: normaliseLabel(q.text).slice('Risk:'.length).trim() });
    }
  }
  if (bullets === 0) {
    v.push({ path: rel, line: offset + deps.startLine, message: 'section 3 lists no prerequisite; at least one bullet "- <CS-NN-NNN or None> — <reason>" is required' });
  }
  if (risks.length !== 1) {
    v.push({ path: rel, line: risks.length > 1 ? risks[1].line : offset + deps.startLine, message: `section 3 carries ${risks.length} "Risk:" lines; exactly one is required` });
  } else if (risks[0].value === '') {
    v.push({ path: rel, line: risks[0].line, message: 'section 3 "Risk:" line carries no text' });
  }
}

/**
 * The protected source that SOURCE_RECORD fixes at creation, from the reproduced source body
 * as quotedSourceLines gives it (the lines after "## Source Story (verbatim)" to the section
 * end): its protected lines and the blank lines around them. A protected line is a line
 * outside every SCOPE-QUESTION note that is not blank, taken as `raw` gives it, so only its
 * quotation markup is removed and its indentation, deeper quote markers and trailing
 * whitespace stay. A blank line is empty or holds only spaces and tabs once that markup is
 * removed; inside a fence every line is protected. Gap g (0-based) holds the lines before
 * protected line g + 1, and the last gap the lines after the last protected line; its entry
 * is its number of blank lines, or "*" when it holds a note line, since a note may stand
 * anywhere with or without blank lines around it. Returns { text: the protected lines joined
 * by "\n", lines: their 1-based body line numbers, gaps: the entries, one per gap }.
 */
function protectedSource(quoted) {
  const kept = [];
  const lines = [];
  const gaps = [];
  let blanks = 0;
  let noted = false;
  quoted.forEach((q, i) => {
    if (q.note) noted = true;
    else if (q.text === '' && !q.fenced) blanks++;
    else {
      gaps.push(noted ? '*' : blanks);
      kept.push(q.raw);
      lines.push(i + 1);
      blanks = 0;
      noted = false;
    }
  });
  gaps.push(noted ? '*' : blanks);
  return { text: kept.join('\n'), lines, gaps };
}

// One SOURCE_RECORD gap entry: "0"–"9" blank lines, "[n]" for ten or more, "*" for a gap
// that held a SCOPE-QUESTION note when the record was made.
const GAP_ENTRY_RE = /\[(\d+)\]|[0-9*]/y;
const encodeGaps = (gaps) => gaps.map((g) => (g === '*' || g < 10 ? String(g) : `[${g}]`)).join('');

/** The entries of a recorded gap string in order, or null when the string does not parse. */
function decodeGaps(s) {
  const out = [];
  GAP_ENTRY_RE.lastIndex = 0;
  while (GAP_ENTRY_RE.lastIndex < s.length) {
    const m = GAP_ENTRY_RE.exec(s);
    if (!m) return null;
    out.push(m[1] !== undefined ? Number(m[1]) : m[0] === '*' ? '*' : Number(m[0]));
  }
  return out;
}

/**
 * Group D (verbatim source body): the story's protected source equals its creation-time
 * record, character for character. The SHA-256 digest of the protected lines equals the
 * recorded one, so a changed, added, removed or moved character outside the notes is
 * reported, trailing whitespace included. Each gap then holds its recorded number of blank
 * lines, unless it holds a note now or held one when the record was made ("*"), where any
 * number stands. An edit to the quoted text is restored and raised as a "> SCOPE-QUESTION"
 * note beneath the text it questions, never absorbed into the record. Body line k is
 * document line offset + k, offset being the "## Source Story (verbatim)" line.
 */
function checkSourceRecord(story, quoted, offset, v) {
  const source = protectedSource(quoted);
  const digest = crypto.createHash('sha256').update(source.text, 'utf8').digest('hex');
  const recorded = Object.hasOwn(SOURCE_RECORD, story.key) ? SOURCE_RECORD[story.key] : null;
  const where = { path: story.rel, line: offset };
  if (recorded === null) {
    v.push({ ...where, message: `${story.key} has no creation-time source record; its protected source lines have SHA-256 ${digest} and blank-line gaps "${encodeGaps(source.gaps)}"` });
    return;
  }
  const expected = typeof recorded?.sha256 === 'string' && /^[0-9a-f]{64}$/.test(recorded.sha256) && typeof recorded.gaps === 'string' ? decodeGaps(recorded.gaps) : null;
  if (expected === null) {
    v.push({ ...where, message: `creation-time source record of ${story.key} is malformed; an entry reads { sha256: '<64 lowercase hex digits>', gaps: '<one entry per blank-line gap>' }` });
    return;
  }
  if (recorded.sha256 !== digest) {
    v.push({ ...where, message: `reproduced source body differs from its creation-time record (SHA-256 ${recorded.sha256}, now ${digest}); restore the quoted text character for character and raise the change as a "> SCOPE-QUESTION" note beneath the text it questions; every line outside a note that is not blank counts as written once its quote marker is removed, trailing whitespace included` });
    return;
  }
  const n = source.lines.length;
  if (expected.length !== n + 1) {
    v.push({ ...where, message: `creation-time source record of ${story.key} lists ${expected.length} blank-line gaps; its ${n} protected lines have ${n + 1}` });
    return;
  }
  const count = (c) => `${c} blank line${c === 1 ? '' : 's'}`;
  source.gaps.forEach((have, g) => {
    const want = expected[g];
    if (have === '*' || want === '*' || have === want) return;
    const before = g > 0 ? offset + source.lines[g - 1] : null;
    const after = g < n ? offset + source.lines[g] : null;
    let at = 'in the reproduced source body';
    if (before !== null && after !== null) at = `between line ${before} and line ${after}`;
    else if (after !== null) at = `before line ${after}, the first protected line,`;
    else if (before !== null) at = `after line ${before}, the last protected line,`;
    v.push({ path: story.rel, line: after ?? before ?? offset, message: `reproduced source body has ${count(have)} ${at} where its creation-time record has ${want}; restore that number: blank lines between protected lines stay as written, and only a gap that holds a "> SCOPE-QUESTION" note takes any number` });
  });
}

// Creation-time record, one entry per story: sha256 is the SHA-256 hex digest of its
// protected lines as protectedSource joins them, and gaps its blank-line gap entries as
// encodeGaps writes them. Generated from the stories by those functions, never typed.
const SOURCE_RECORD = Object.freeze({
  'CS-01-001': { sha256: '3ba3912779de0e3f42f14fa00a7bb0d70c29239e089b5899bc8ab461f34f6fa0', gaps: '111111110000000000000000000000000000011000011000110000000000110000011' },
  'CS-01-002': { sha256: '837e1f765886441ffe7b2751ba2b30e13229b6b49bea9f35ab070d6cd4746a61', gaps: '11111111000000000000000011000001100001100000001111' },
  'CS-01-003': { sha256: '0b7bcd9a72904acb7f3644d9bdce9bdb1cf1203076bc427de995130f3b262ea4', gaps: '11111111000000000000000000110000011000011000000001111' },
  'CS-01-004': { sha256: '8b0c40ebc2f074fc69746e80b63bdbfe81b3e4907150dcfac684439888345ecc', gaps: '11111110000011000110001100000001111' },
  'CS-01-005': { sha256: '7b7ba8ae9b7fd44b677a0b3033a2c1bebdaf0baaada21cf9d98e6ac565adbefa', gaps: '1111111100001100011000110000*01111' },
  'CS-02-001': { sha256: 'eb7ceff1422ade07ca8eda8dacadf5dcc47b34038f40544818143076ab5bb585', gaps: '111111100000110001100000110000000001111' },
  'CS-02-002': { sha256: '14b06ee92bf267e59677871f05b5b8e9ae9e308c3f741b120815d4f2ca145e09', gaps: '1111111100000000000110000110000110000000001111' },
  'CS-03-001': { sha256: '644c8fc89bb402b6efb8ef36854e8f9be10846294742dd8efa2f74f7a45f131f', gaps: '11111110000011000110000011000000001111' },
  'CS-03-002': { sha256: 'ed110eac2d06504f2bdef7f81e774f0f70a8d005494c417ec885869142d3aec9', gaps: '1111111000001100011000000001100000000001111' },
  'CS-03-003': { sha256: 'aee2738d686482c71b38a21a63a9b838c8ef5fd6408d866413bec9fccd787c04', gaps: '111111110000000000000011000110000011000000000001111' },
  'CS-04-001': { sha256: '76152378b6ef9fd0d3fb0968332e1833e4e3ca18a3ee7de80a11b4a66e791454', gaps: '1111111000001100001100000110000*000*1111' },
  'CS-04-002': { sha256: 'b44f6a432f11ea7e499488468978063446f08c28b06924b7ba813b3676522e9d', gaps: '1111111100000000011000001100011000000**00011111' },
  'CS-05-001': { sha256: 'ba159a197aa5777445825eb66f448658a40e8c8ea4dc07f9e16f70470a01689c', gaps: '1111111100000000000000000000000000000000001100001100000110000000001111' },
  'CS-05-002': { sha256: '38419725a771cbb482e234d60903eb21e31aa066bb53ada3fa8516019c9c7a3a', gaps: '11111111000000000001100001100001100000001111' },
  'CS-05-003': { sha256: '7a9a1d10e5555f76997eb17de574f7c1b9b638af10a77715185a1ea94f8cfe42', gaps: '1111111000000000000110011000110000*01111' },
  'CS-05-004': { sha256: 'edc8885905fe144600afe1645b37080db96c1a0760a0f2b781d995d39124e661', gaps: '11111110000000011000011000110000001111' },
  'CS-05-005': { sha256: '68787d111474e54a545265e6b5cdb054f1c2f248b0977494661dfe81d914868a', gaps: '11111110000000000000000110000011000*0110000000000**0011000011' },
  'CS-05-006': { sha256: '0b62323a80218cf11e947d71e9453da89cd22bbcebe597befd968cefb226b5dc', gaps: '111111100000000000000011000011000011000000000000*00000*0011011' },
  'CS-06-001': { sha256: '62f4e1e26f267c65036e48ca83c321314ee778bc1301830ebcb37ff554788f75', gaps: '11111110000000000000011000110000110000000011011' },
  'CS-06-002': { sha256: '9724132ba94d99871916e32247557ae7a0d868188f7c04e41cf0b360e35d9b34', gaps: '11111110*00000000000000000000011001110000001100000110000000000*0001100011' },
  'CS-06-003': { sha256: '56ca33745959ece298bd0567a832508f5e921ac4a4691c00d2c5f1ab109439f6', gaps: '11111110000000000000000000000001100000011000110000000000000000*011011' },
  'CS-07-001': { sha256: '79352814d1cb4accc38c1a83b548ddd8d07f31df5bba9183d5d665f1bf1772dc', gaps: '11111110000000000000000110000110000011000000000110011' },
  'CS-07-002': { sha256: '84bb0eb53033d6b502c86a99ff28bbf2e10f67784793ae1efb0772fe3ea8c010', gaps: '11111110000000000000001100000000110000110000110000000000*00110011' },
  'CS-07-003': { sha256: '3d8527e38610c6c7adaf3363155899c8bb47a1f4120fb99b0cfedac069924f42', gaps: '111111100000000000000000000000000011000011000110000000000000000011011' },
  'CS-08-001': { sha256: 'ce1a10b46c8fb27cd429f408382517fb856b608536e2692769e4268708d5fb39', gaps: '11111110*0000000011001100011000*00*000*111' },
  'CS-08-002': { sha256: '6352d5ac814d140081b4ee8b3e35e459261da9a721b6d74e632cba625d0af941', gaps: '11111110000000000000000000000110000110000110000000000000001100011' },
  'CS-08-003': { sha256: 'fc255253345a09dc60b4d4350b14549ea08b2e22286f3d0bb6e8adf1736cc6ec', gaps: '1111111000000000000000000000000000011000001100011000000*000*011011' },
  'CS-09-001': { sha256: 'e0b963d35f84bcb6b2e5d3297e8d1263e52b091b8ab94776af5121809d746489', gaps: '11111111*000000110000**0*1000000000011000011000000000000000*111' },
  'CS-09-002': { sha256: '54b5240da4be62080a5fc3589cdb3a8ef2e49adc86b266adb6fb0b6df209977e', gaps: '1111111000000000011000011000001100000000000001111' },
  'CS-09-003': { sha256: '6e3316805cef6778b76a78fb492b37360946a5b4364dcd480bac7ecc624191ff', gaps: '1111111*00000000*01100*0*110000000110000110000000000**111' },
  'CS-09-004': { sha256: '72400aa869f092f8ed0ef89de5dbde35bfb00cf710cd139c7e264e2c8e233b5d', gaps: '111111100000000000110000011000011000000110000000000000*011011' },
  'CS-09-005': { sha256: 'f929318fa99a1483bf5803995dbdfccd417a33337eeb58550f36b3b5df271bbd', gaps: '1111*11000000000000000001100000011000000011000000110000000000000*10011' },
  'CS-09-006': { sha256: 'b94db68849014be61c47940ca860a1ba6f0db617684ba4c29686d8d36d0ed163', gaps: '11111111100000000000000011100001100001100*000000*00011011' },
  'CS-09-007': { sha256: 'd5b5e2bafb1c750cd65a15077e8c0322e8c9c3fc4ecf6124aa351ef8fd327f39', gaps: '111111110000000110000000000110000110001100000000001111' },
  'CS-10-001': { sha256: '44ef50258f0b760008cc2247dc7571fd0b77375b1e3b32ac7e0725fe80f51046', gaps: '11*1111000000001100000110000001100000001111' },
  'CS-10-002': { sha256: '925c9f7bce8e1cfc6eb4fba28011d9664169a1081f92db8e6fd2d2abc3c77c00', gaps: '111111*00011000000011000001100000000*1011' },
  'CS-10-003': { sha256: 'c35d43d020c94e067b0e0e0b2ef10ff97072fb0bdca04dbb6fe91290f55dab86', gaps: '111111*0011000011000110000000*1111' },
  'CS-10-004': { sha256: '6efb37ac2257a44390c31cae1d542061b7597d7204bf51f632d357951252f1c8', gaps: '111111100000000000001100011000001100000000110000000000000110011' },
  'CS-10-005': { sha256: 'ca16bbf1373cefb5d7012afbd9172a6bbc5399c9e3e86a01eecf0138e3ad488e', gaps: '1111111000000000011000110001100000011000001111' },
  'CS-10-006': { sha256: '04a6cb574ac2ff47a2fd422c405e16d0e7934b99132594f08d93198e1db3a9f1', gaps: '1111*110000000000000001100000011000000011000000011000000000000*10011' },
  'CS-10-007': { sha256: '5e9debe3c09430df08736b2b4114a6ac57d9827557ca0ca508b56a2aae64daca', gaps: '11111111100000000000000111000000110000110000*0*00000011011' },
  'CS-10-008': { sha256: '9ed4c163559086f683d4d0ecf6aa4da4a996cd92b66479dc038088f8b04f7cf0', gaps: '111111110001100000000001100001100011000000011011' },
  'CS-11-001': { sha256: '4a1b0fcb126b64e8b33d043225f5fdfe675ada77b1f174bb6f65625929f29e45', gaps: '11111111100000000100000000000000011100001110001100000000110000001111' },
  'CS-11-002': { sha256: '07cb599fc0a093f62748a9f59dce1693f51be4ecab7cff9fdb974d0727edf7b9', gaps: '1111111110000*0000*000*0000000000000011100000110000001100*000*00001111' },
  'CS-11-003': { sha256: '5ceef50fca0107c27163341cb99771101558fc90df89d52ec9888ddf5aea71d5', gaps: '11111111100*00000000*0000*00001*1100000011000000110000000000*1011' },
  'CS-11-004': { sha256: '93482723754e3c404c7a07081b394bb7e6aafc78bb8c4bad8f6eef1281afe82a', gaps: '111111111000*0****00*0*00001100000110000011000000*0***0111*' },
  'CS-11-005': { sha256: '1c137f9d040185c19aa50085dec936199ff5dd87a18cea5a293bdf0cb38f67e1', gaps: '11111111000000*000001100*011000011000*000001111' },
  'CS-11-006': { sha256: '64c8922b748f2a7a8d1fac2777655bcfa08c95f3684409d9b27ee85e3e82e6f8', gaps: '1111111100000*0**0*0*000*0001110000001100000001100000*00*0**10011' },
  'CS-11-007': { sha256: '0fedabb0d1e84c1bdda53be6d46cda35e0d866f063d4225ab41cf6781dcc7f4b', gaps: '1111111110000000000000*0000001110000011000000001100*000**001111' },
  'CS-12-001': { sha256: '6d1d0cb896047148c0f800dc3a1b472c641837d4366395eaa42ec46eedd02b01', gaps: '11111111000000*00011000000001110000011000011000000110000*00*111' },
  'CS-12-002': { sha256: '24aad3fe2721535d40f3a06ec94ac21bbea51661d37606fd587ea604a06e4aa7', gaps: '111111110000*0*00000000011000000110000110000000110000000*0*1011' },
  'CS-12-003': { sha256: '6480992b6333aed97cb8d3acd280ee2e5102e4e6a88268549ff05bf38c72c939', gaps: '111111110000000000000000000000110000000*0011000001100000011000000000*0011011' },
  'CS-12-004': { sha256: '94a4a31c8359f8c3e851463d5657273c73c48e68bae962890a220082ea20c0a1', gaps: '111111111000000011000001100011000011000001111' },
  'CS-12-005': { sha256: 'bd5af0b36b784f7f0a32af446e8105e2d588c8b8e66c7bb4e73fbbf1ee62e2e3', gaps: '1111111110000000000111000110000110000*000011011' },
  'CS-13-001': { sha256: '6738dc41e53cbb8434362b7e3f94a77c4fd136250de5bd41fc7d99fde3977012', gaps: '111111100000000000*000000111000000011000011000*001111' },
  'CS-13-002': { sha256: '1f87f51da7d767a3ac95232d137ac331435fed092284410ded399d0d95ac5b91', gaps: '111111100000000000000110001110000011000011000000000011011' },
  'CS-13-003': { sha256: 'b6b80a700ee880f7a6f94b1792ec3f3c0a059767c7cd222de8396951f6ee5d68', gaps: '1111111100000000000000000000*0000001111000000110000001100000000*110011' },
  'CS-13-004': { sha256: '73dce77e7cf395a2f8e001c498d45d9de3bbe1acfd88963e53f7dbc9c531cbb8', gaps: '1111111110000000000000001110000110001100*0000*1011' },
});

/**
 * Group D: Design contract links. Every interface the story links is linked by both its
 * .proto and its OpenAPI document, common.proto appears only beside such a pair, every
 * linked .proto or OpenAPI file is one of the 13 contract files, and a story that links no
 * interface states "Contracts: N/A" on a line of its own instead, never as well. A story
 * that links any interface links its epic's own interface (EPIC_INTERFACES) too, and a
 * story of an endpoint epic (ENDPOINT_EPICS) always links the UI-facing API and never
 * states "Contracts: N/A". `links` holds the resolved Design links, each with its document
 * line.
 */
function checkDesignContracts(story, design, links, v) {
  const rel = story.rel;
  const where = { path: rel, line: design.startLine };
  const contracts = links.filter((l) => l.path.endsWith('.proto') || `${l.path}/`.startsWith(OPENAPI_DIR));
  for (const l of contracts) {
    if (!CONTRACT_FILES.has(l.path)) {
      v.push({ path: rel, line: l.line, message: `Design links "${l.path}", which is none of the 13 contract files: the six interface .proto and OpenAPI pairs and ${COMMON_PROTO}` });
    }
  }
  const linked = new Set(contracts.map((l) => l.path));
  let pairs = 0;
  let halves = 0;
  for (const i of INTERFACE_CONTRACTS) {
    const proto = linked.has(i.proto);
    const openapi = linked.has(i.openapi);
    if (proto && openapi) {
      pairs++;
    } else if (proto || openapi) {
      halves++;
      const [has, missing, twin] = proto ? ['.proto', 'OpenAPI document', i.openapi] : ['OpenAPI document', '.proto', i.proto];
      v.push({ ...where, message: `Design links the ${i.name} ${has} but not its ${missing} twin ${twin}` });
    }
  }
  const epicId = story.epic.id;
  const own = INTERFACE_CONTRACTS.find((i) => i.name === EPIC_INTERFACES.get(epicId));
  const endpoint = ENDPOINT_EPICS.has(epicId);
  const ownLinked = !own || linked.has(own.proto) || linked.has(own.openapi);
  const fences = fenceLineSet(design.text);
  const notApplicable = splitLines(design.text).some((l, k) => !fences.has(k + 1) && normaliseLabel(l) === 'Contracts: N/A');
  if (notApplicable) {
    if (contracts.length > 0) {
      v.push({ ...where, message: 'Design states "Contracts: N/A" and also links contract files; it carries one or the other' });
    } else if (endpoint) {
      v.push({
        ...where,
        message: `Design states "Contracts: N/A", but a ${epicId} story serves its UI-facing endpoint and links the ${own.name} pair ${own.proto} and ${own.openapi}`,
      });
    }
  } else if (!ownLinked && (endpoint || pairs + halves > 0)) {
    v.push({
      ...where,
      message: endpoint
        ? `Design links no ${own.name} contract; a ${epicId} story serves its UI-facing endpoint and links ${own.proto} and ${own.openapi}`
        : `Design links another interface's contracts but not the ${own.name} pair of its epic ${epicId}: ${own.proto} and ${own.openapi}`,
    });
  } else if (pairs === 0 && halves === 0) {
    v.push({
      ...where,
      message: linked.has(COMMON_PROTO)
        ? 'Design links common.proto without the .proto and OpenAPI pair of an interface, and does not state "Contracts: N/A"'
        : 'Design links no interface .proto and OpenAPI pair and does not state "Contracts: N/A"',
    });
  }
}

/**
 * The suite records of a Test Plan section. A record is an unindented list item naming a
 * suite (SUITE_RECORD_RE) plus every following blank or indented line, its continuation and
 * sub-items included, up to the next unindented list item or unindented text. Fenced lines
 * never open a record, and an unindented fenced line ends one.
 * @returns {{ suite: string, line: number, lead: string, text: string }[]} `line` is the
 *   item's document line and `lead` the item text after the suite name and separator.
 */
function testPlanRecords(testPlan) {
  const fences = fenceLineSet(testPlan.text);
  const records = [];
  let current = null;
  splitLines(testPlan.text).forEach((l, k) => {
    if (k === 0) return;
    if (!fences.has(k + 1) && TOP_LEVEL_ITEM_RE.test(l)) {
      const m = SUITE_RECORD_RE.exec(l);
      current = m ? { suite: m[1], line: testPlan.startLine + k, lead: m[2], text: l } : null;
      if (current) records.push(current);
    } else if (l.trim() === '' || /^[ \t]/.test(l)) {
      if (current) current.text += `\n${l}`;
    } else {
      current = null;
    }
  });
  return records;
}

/**
 * The latency figure each percentile of a record is bound to, under PERCENTILE_FIGURE_RE:
 * the figure after the percentile, or after the list of percentiles it belongs to, when no
 * more than PERCENTILE_TIE_WINDOW characters and no clause break separate them, and the
 * figure directly before the list's first percentile. Each whitespace run counts as one
 * character, as it renders, so the spaces of a blanked comment never widen the gap. A
 * percentile with no such figure is bound to none. One scan of the text, so the work is
 * linear in its length.
 * @returns {{ percentile: string, figure: string, ms: number }[]}
 */
function percentileBounds(text) {
  const bounds = [];
  let list = [];
  let before = null;
  let prev = null;
  for (const m of text.matchAll(PERCENTILE_FIGURE_RE)) {
    const gap = text.slice(prev ? prev.end : 0, m.index);
    const end = m.index + m[0].length;
    if (m[1] !== undefined) {
      const percentile = `p${m[1]}`;
      if (prev?.percentile && PERCENTILE_JOIN_RE.test(gap)) {
        list.push(percentile);
      } else {
        list = [percentile];
        before = prev?.figure && FIGURE_BEFORE_PERCENTILE_RE.test(gap) ? prev.figure : null;
      }
      if (before) bounds.push({ percentile, ...before });
      prev = { percentile, end };
    } else {
      const unit = m[4].toLowerCase();
      const value = Number(`${m[2].replace(/,/g, '')}${m[3] ?? ''}`);
      const figure = { figure: m[0], ms: unit.startsWith('m') ? value : value * 1000 };
      const width = gap.replace(/\s+/g, ' ').length;
      if (prev?.percentile && width <= PERCENTILE_TIE_WINDOW && !CLAUSE_BREAK_RE.test(gap)) {
        for (const percentile of list) bounds.push({ percentile, ...figure });
      }
      list = [];
      before = null;
      prev = { figure, end };
    }
  }
  return bounds;
}

/**
 * Group D: Test Plan suites. Each of the four suites has exactly one record, which opens
 * with its applicability and states its own thresholds and conditions; the injected-failure
 * proof sits in the Performance or the Integration record, and every percentile the
 * Performance record binds to a latency figure is bound to 1,000 ms, or is the p99 stub
 * trigger at 150 ms.
 */
function checkTestPlan(rel, testPlan, v) {
  const records = testPlanRecords(testPlan);
  const bySuite = new Map(SUITES.map((s) => [s, records.filter((r) => r.suite === s)]));
  for (const [suite, found] of bySuite) {
    if (found.length === 0) {
      v.push({ path: rel, line: testPlan.startLine, message: `Test Plan does not name the ${suite} suite as a top-level item ("- ${suite}: …" or "- ${suite} — …")` });
      continue;
    }
    for (const extra of found.slice(1)) {
      v.push({ path: rel, line: extra.line, message: `Test Plan names the ${suite} suite in ${found.length} top-level items; exactly one is required` });
    }
    const record = found[0];
    if (!APPLICABILITY_RE.test(record.lead)) {
      v.push({ path: rel, line: record.line, message: `Test Plan ${suite} suite does not open with its applicability, "Applies" or "Not applicable"` });
    }
    for (const [what, re] of SUITE_CONDITIONS[suite]) {
      if (!re.test(record.text)) v.push({ path: rel, line: record.line, message: `Test Plan ${suite} suite does not state ${what}` });
    }
    if (suite !== 'Performance') continue;
    for (const b of percentileBounds(record.text)) {
      if (b.ms === PERCENTILE_BOUND_MS || (b.percentile === STUB_TRIGGER_BOUND.percentile && b.ms === STUB_TRIGGER_BOUND.ms)) continue;
      v.push({
        path: rel,
        line: record.line,
        message: `Test Plan Performance suite binds ${b.percentile} to "${b.figure}"; every percentile bound is 1,000 ms, and p99 also 150 ms as the stub trigger`,
      });
    }
  }
  const proofHolders = ['Performance', 'Integration'].flatMap((s) => bySuite.get(s).slice(0, 1));
  if (proofHolders.length > 0 && !proofHolders.some((r) => INJECTED_CUTOFF_RE.test(r.text) && INJECTED_CLIENT_RE.test(r.text))) {
    v.push({
      path: rel,
      line: proofHolders[0].line,
      message: 'Test Plan states in neither the Performance nor the Integration suite the injected-failure proof: a cut-off at or before 900 ms (≤ 900 or at most 900) and a client-measured response below 1,000 ms',
    });
  }
}

/** Group D: Design links and Test Plan thresholds. */
function checkDesignAndTestPlan(story, text, v) {
  const rel = story.rel;
  // HTML comments never render, so the "Contracts: N/A" statement and the Test Plan's suites
  // and thresholds are read from section text with every comment blanked (blankComments):
  // comment blocks and inline comments alike, one running over lines included; visible
  // fenced code still counts. Blanking keeps line k (0-based) of a section text at document
  // line startLine + k, which the record and violation line numbers rely on.
  const shown = (s) => ({ ...s, text: blankComments(s.text) });
  const design = h2Section(text, '4. Design');
  if (design) {
    const links = parseLinks(design.text).map((l) => ({ ...resolveLink(rel, l.target), line: design.startLine + l.line - 1 }));
    if (!links.some((l) => l.path.endsWith('.mmd'))) {
      v.push({ path: rel, line: design.startLine, message: 'Design links no architecture diagram (.mmd)' });
    }
    // Only the anchor's presence is checked here, because this check reads nothing outside
    // docs/backlog/epics; check-reachability resolves every anchor against the data model.
    const dataModel = links.filter((l) => l.path === DATA_MODEL);
    if (dataModel.length === 0) {
      v.push({ path: rel, line: design.startLine, message: 'Design links no section of docs/data-model.md' });
    }
    for (const l of dataModel) {
      if (!l.anchor) v.push({ path: rel, line: l.line, message: 'Design links docs/data-model.md without a section anchor (docs/data-model.md#<section>)' });
    }
    checkDesignContracts(story, shown(design), links, v);
  }
  const testPlan = h2Section(text, '6. Test Plan');
  if (testPlan) checkTestPlan(rel, shown(testPlan), v);
}

/**
 * The options one line of design prose names, by stance, from one left-to-right scan. A
 * contrast cue (reject, rejects, rejected, not, never, instead of, rather than, over, vs,
 * versus, against, unlike, or a negated auxiliary such as cannot, isn't or won't) rejects
 * one mention in its clause, chosen by where the cue stands:
 *  - after a mention, joined to it only by copula or auxiliary words (is, was, are, were,
 *    be, been, being, stays, remains, gets, has, does, will, would, should, must, then and
 *    their kin), it rejects that earlier mention: "Option B is rejected", "Option A is not
 *    recommended", "Option B was never chosen", "Option B stays rejected". A negated
 *    auxiliary carries its own auxiliary, so "Option B isn't viable" rejects B as well;
 *  - anywhere else it rejects the next mention, with no other mention between them:
 *    "Reject Option B", "Option A over Option B", "Option A, not Option B".
 * Only whitespace and emphasis markers may separate a mention from its linking words. A
 * semicolon or a sentence end closes the clause, and the period of an abbreviation such as
 * "vs." closes none. Every other mention is affirmed, so "Option B is rejected; choose
 * Option A", "Reject Option B; choose Option A" and "Option A is chosen because Option B is
 * rejected" each affirm only A, while "Reject Option B; Option A is not recommended"
 * affirms none.
 * @returns {{ affirmed: string[], rejected: string[] }}
 */
function optionStances(text) {
  const s = String(text ?? '').replace(ABBREVIATION_RE, (abbr) => abbr.replace(/\./g, ' '));
  const stances = [];
  let cue = false;
  // The mention a following contrast cue rejects: its index in stances, or -1 once other
  // words, a contrast cue or a clause boundary follow it. links counts the copula and
  // auxiliary words between it and the current token, and end is the previous token's end.
  let open = -1;
  let links = 0;
  let end = 0;
  for (const m of s.matchAll(OPTION_MENTION_RE)) {
    if (open >= 0 && /[^\s*_]/.test(s.slice(end, m.index))) open = -1;
    end = m.index + m[0].length;
    if (m[1]) {
      stances.push({ option: m[1], rejected: cue });
      cue = false;
      open = stances.length - 1;
      links = 0;
    } else if (m[5]) {
      if (open >= 0) links += 1;
    } else if (m[2] || m[4]) {
      if (open >= 0 && (links > 0 || m[4])) stances[open].rejected = true;
      else cue = true;
      open = -1;
    } else {
      cue = false;
      open = -1;
    }
  }
  const options = (rejected) => [...new Set(stances.filter((x) => x.rejected === rejected).map((x) => x.option))].sort();
  return { affirmed: options(false), rejected: options(true) };
}

/**
 * The one option a line of design prose affirms. When it affirms none or both, one
 * violation says what `what` names instead and the result is null.
 */
function recommendedOption(text, what, where, v) {
  const { affirmed, rejected } = optionStances(text);
  if (affirmed.length === 1) return affirmed[0];
  let named = 'no option (Option A or Option B)';
  if (affirmed.length > 1) named = 'both Option A and Option B as chosen';
  else if (rejected.length > 0) named = `only the rejected ${rejected.map((o) => `Option ${o}`).join(' and ')}`;
  v.push({ ...where, message: `${what} names ${named}; it must name exactly one recommended option` });
  return null;
}

/** The plain text after `label` when a line, in label-line form, starts with it; otherwise null. */
function labelValue(line, label) {
  const s = normaliseLabel(line);
  return s.startsWith(label) ? plainCell(s.slice(label.length)) : null;
}

/** Sentences in one paragraph of prose; the period of an abbreviation such as "e.g." ends none. */
function sentenceCount(text) {
  const s = normaliseSpace(plainText(String(text ?? '')).replace(ABBREVIATION_RE, 'abbr'));
  return s === '' ? 0 : 1 + (s.match(/[.!?]["')\]]* (?=\S)/g) ?? []).length;
}

/**
 * Reports each part that comes after a part it must precede. `found` holds { rank, line }
 * entries, with rank indexing `names`, which lists the parts in their required order.
 */
function checkPartOrder(found, names, owner, rel, v) {
  let maxRank = -1;
  for (const part of [...found].sort((a, b) => a.line - b.line || a.rank - b.rank)) {
    if (part.rank < maxRank) {
      v.push({ path: rel, line: part.line, message: `${owner}: ${names[part.rank]} is out of order; it must precede ${names[maxRank]}` });
    } else {
      maxRank = part.rank;
    }
  }
}

/**
 * Group D: the "Shared by both options" block, from its heading or label line to the next
 * level-3 heading, carries exactly one non-empty "- <Topic>: …" bullet per shared topic.
 * Bullets on the `hidden` lines (document lines of fenced code and HTML comment blocks)
 * never count, and a topic and its value are read with inline comments blanked.
 */
function checkSharedBlock(rel, block, hidden, v) {
  const topics = new Map(SHARED_TOPICS.map((t) => [t, []]));
  const shown = splitLines(blankComments(block.text));
  splitLines(block.text).forEach((l, k) => {
    const line = block.startLine + k;
    if (k === 0 || hidden.has(line) || !BULLET_RE.test(l)) return;
    for (const topic of SHARED_TOPICS) {
      const value = labelValue(shown[k], `${topic}:`);
      if (value !== null) topics.get(topic).push({ line, value });
    }
  });
  for (const [topic, found] of topics) {
    if (found.length === 0) {
      v.push({ path: rel, line: block.startLine, message: `"${SHARED_LABEL}" block has no "- ${topic}: …" bullet` });
    } else if (found.length > 1) {
      v.push({ path: rel, line: found[1].line, message: `"${SHARED_LABEL}" block carries ${found.length} "${topic}:" bullets; exactly one is required` });
    }
    for (const f of found) {
      if (f.value === '') v.push({ path: rel, line: f.line, message: `"${SHARED_LABEL}" bullet "${topic}:" is empty` });
    }
  }
}

/**
 * Group D: one design option's parts, in order — a summary paragraph of 2–4 sentences, one
 * "How it behaves:" or "How it differs:" line followed by bullets, one decisions table
 * "No. | Decision (vs rejected) | Pro | Con" with rows numbered 1..n and no empty cell, and
 * one single-line "Effort and risk:" statement. Each missing part is one violation. Parts
 * on the `hidden` lines (document lines of fenced code and HTML comment blocks) never count.
 * What a prose line says comes from `shown`, the lines with inline comments blanked; line
 * structure, and the table cells, which plainCell reads one by one, come from the text as written.
 */
function checkDesignOption(rel, option, part, hidden, v) {
  const name = `Design Option ${option}`;
  const where = { path: rel, line: part.startLine };
  const lines = splitLines(part.text);
  const shown = splitLines(blankComments(part.text));
  const at = (k) => part.startLine + k;
  const prose = (k) => !hidden.has(at(k));
  const tables = parseTables(part.text);
  const tableLines = new Set();
  for (const t of tables) {
    for (const n of [t.line, t.line + 1, ...t.rowLines]) tableLines.add(n - 1);
  }
  const isHow = (k) => prose(k) && HOW_LABELS.includes(normaliseLabel(shown[k]));
  const isEffort = (k) => prose(k) && labelValue(shown[k], EFFORT_LABEL) !== null;
  const isParagraph = (k) =>
    lines[k].trim() !== '' && prose(k) && !BULLET_RE.test(lines[k]) && !HEADING_LINE_RE.test(lines[k]) && !tableLines.has(k) && !isHow(k) && !isEffort(k);
  const found = [];

  const first = lines.findIndex((l, k) => k > 0 && l.trim() !== '');
  if (first < 0 || !isParagraph(first)) {
    v.push({ ...where, message: `${name} has no summary paragraph directly after its heading` });
  } else {
    let end = first;
    while (end + 1 < lines.length && isParagraph(end + 1)) end++;
    const n = sentenceCount(shown.slice(first, end + 1).join(' '));
    if (n < 2 || n > 4) {
      v.push({ path: rel, line: at(first), message: `${name} summary holds ${n === 1 ? '1 sentence' : `${n} sentences`}; a design option summary holds 2 to 4` });
    }
    found.push({ rank: 0, line: at(first) });
  }

  const hows = lines.flatMap((l, k) => (k > 0 && isHow(k) ? [k] : []));
  if (hows.length === 0) {
    v.push({ ...where, message: `${name} has no "How it behaves:" or "How it differs:" line` });
  } else {
    if (hows.length > 1) {
      v.push({ path: rel, line: at(hows[1]), message: `${name} carries ${hows.length} "How it behaves:" or "How it differs:" lines; exactly one is required` });
    }
    const how = hows[0];
    const label = normaliseLabel(shown[how]);
    let bullets = 0;
    for (let k = how + 1; k < lines.length; k++) {
      if (lines[k].trim() === '') continue;
      if (!prose(k) || !BULLET_RE.test(lines[k])) break;
      const empty = plainCell(shown[k].replace(BULLET_RE, '')) === '';
      if (empty) v.push({ path: rel, line: at(k), message: `${name} "${label}" list holds an empty bullet` });
      else bullets++;
    }
    if (bullets === 0) v.push({ path: rel, line: at(how), message: `${name} "${label}" line is followed by no bullet` });
    found.push({ rank: 1, line: at(how) });
  }

  const decisions = tables.filter(
    (t) => t.header.length === DECISION_HEADER.length && t.header.every((c, n) => plainCell(c) === DECISION_HEADER[n]),
  );
  if (decisions.length === 0) {
    v.push({ ...where, message: `${name} has no decisions table "${DECISION_HEADER.join(' | ')}"` });
  } else {
    if (decisions.length > 1) {
      v.push({ path: rel, line: at(decisions[1].line - 1), message: `${name} carries ${decisions.length} decisions tables; exactly one is required` });
    }
    const table = decisions[0];
    if (table.rows.length === 0) v.push({ path: rel, line: at(table.line - 1), message: `${name} decisions table has no decision row` });
    table.rows.forEach((row, r) => {
      const line = at(table.rowLines[r] - 1);
      if (row.length > DECISION_HEADER.length) {
        v.push({ path: rel, line, message: `${name} decisions table row ${r + 1} carries ${row.length} cells; the header has ${DECISION_HEADER.length}` });
      }
      DECISION_HEADER.forEach((col, c) => {
        if (plainCell(row[c]) === '') v.push({ path: rel, line, message: `${name} decisions table row ${r + 1} has an empty "${col}" cell` });
      });
      const no = plainCell(row[0]);
      if (no !== '' && no !== String(r + 1)) {
        v.push({ path: rel, line, message: `${name} decisions table row ${r + 1} is numbered "${no}"; rows are numbered 1 to n in order` });
      }
    });
    found.push({ rank: 2, line: at(table.line - 1) });
  }

  const efforts = lines.flatMap((l, k) => (k > 0 && isEffort(k) ? [k] : []));
  if (efforts.length === 0) {
    v.push({ ...where, message: `${name} has no "${EFFORT_LABEL}" line` });
  } else {
    if (efforts.length > 1) {
      v.push({ path: rel, line: at(efforts[1]), message: `${name} carries ${efforts.length} "${EFFORT_LABEL}" lines; exactly one is required` });
    }
    const effort = efforts[0];
    const statement = labelValue(shown[effort], EFFORT_LABEL);
    if (statement === '') v.push({ path: rel, line: at(effort), message: `${name} "${EFFORT_LABEL}" line is empty` });
    const next = effort + 1;
    if (next < lines.length && lines[next].trim() !== '' && prose(next) && !HEADING_LINE_RE.test(lines[next])) {
      v.push({ path: rel, line: at(next), message: `${name} "${EFFORT_LABEL}" statement runs past one line` });
    }
    found.push({ rank: 3, line: at(effort) });
  }

  const names = ['the summary', 'the "How it behaves:" or "How it differs:" line', 'the decisions table', `the "${EFFORT_LABEL}" line`];
  checkPartOrder(found, names, name, rel, v);
}

/**
 * Group D: Implementation Design carries its parts in order — a framing line naming the
 * recommended option; one "Rationale:" line, then a line rejecting each other option; any
 * Mermaid diagram; one "Shared by both options" block; Design Option A; Design Option B;
 * and a closing Recommendation with each of its five records once, whose "Option:" record
 * names the option the framing line names. Every part is read outside the `hidden` lines
 * (document lines of fenced code and HTML comment blocks), and what a line says from
 * `shown`, the lines with inline comments blanked, so text no reader sees never names an
 * option, a label or a value.
 */
function checkImplementationDesign(story, impl, hidden, v) {
  const rel = story.rel;
  const lines = splitLines(impl.text);
  const shown = splitLines(blankComments(impl.text));
  const at = (k) => impl.startLine + k;
  const prose = (k) => !hidden.has(at(k));
  const heads = sectionHeadings(impl).filter((h) => h.level >= 3);
  const headLines = new Set(heads.map((h) => h.docLine));

  const options = { A: [], B: [] };
  const recommendations = [];
  const shared = [];
  for (const h of heads.filter((x) => x.level === 3)) {
    const name = plainCell(h.text);
    const m = OPTION_HEADING_RE.exec(name);
    if (m) options[m[1]].push(h);
    else if (name === SHARED_LABEL) shared.push(h);
    else if (name === 'Recommendation') recommendations.push(h);
    else {
      v.push({ path: rel, line: h.docLine, message: `unexpected heading "### ${name}" in Implementation Design; its level-3 parts are "${SHARED_LABEL}", "Design Option A" (or "Design Option A — <name>"), "Design Option B" and "Recommendation"` });
    }
  }
  shown.forEach((l, k) => {
    if (k > 0 && prose(k) && !headLines.has(at(k)) && normaliseLabel(l) === `${SHARED_LABEL}:`) {
      shared.push({ docLine: at(k), level: 3 });
    }
  });
  shared.sort((a, b) => a.docLine - b.docLine);

  const optionCount = options.A.length + options.B.length;
  if (optionCount !== 2) {
    const carried = optionCount === 1 ? '1 design option' : `${optionCount} design options`;
    v.push({ path: rel, line: impl.startLine, message: `Implementation Design carries ${carried}; exactly two, Design Option A and Design Option B, are required` });
  }
  for (const o of ['A', 'B']) {
    if (options[o].length === 0) {
      v.push({ path: rel, line: impl.startLine, message: `Implementation Design has no "Design Option ${o}" heading` });
    } else if (options[o].length > 1) {
      v.push({ path: rel, line: options[o][1].docLine, message: `Implementation Design carries ${options[o].length} "Design Option ${o}" headings; exactly one is required` });
    }
  }
  if (recommendations.length !== 1) {
    v.push({ path: rel, line: impl.startLine, message: `Implementation Design carries ${recommendations.length} Recommendation headings; exactly one is required` });
  }
  if (shared.length !== 1) {
    v.push({
      path: rel,
      line: shared.length > 1 ? shared[1].docLine : impl.startLine,
      message: `Implementation Design carries ${shared.length} "${SHARED_LABEL}" blocks; exactly one, as a "### ${SHARED_LABEL}" heading or a "${SHARED_LABEL}:" line, is required`,
    });
  }

  const rationale = shown.flatMap((l, k) => (k > 0 && prose(k) && labelValue(l, 'Rationale:') !== null ? [k] : []));
  if (rationale.length === 0) {
    v.push({ path: rel, line: impl.startLine, message: 'Implementation Design has no "Rationale:" line after its framing line' });
  } else {
    if (rationale.length > 1) {
      v.push({ path: rel, line: at(rationale[1]), message: `Implementation Design carries ${rationale.length} "Rationale:" lines; exactly one is required` });
    }
    if (labelValue(shown[rationale[0]], 'Rationale:') === '') {
      v.push({ path: rel, line: at(rationale[0]), message: '"Rationale:" line of Implementation Design is empty' });
    }
  }

  // The framing line is the first non-blank line outside the hidden lines, unless that line
  // is already another part.
  const k = lines.findIndex((l, i) => i > 0 && l.trim() !== '' && prose(i));
  const framing = k > 0 && !headLines.has(at(k)) && !rationale.includes(k) && !shared.some((s) => s.docLine === at(k)) ? k : -1;
  let framed = null;
  if (framing < 0) {
    v.push({ path: rel, line: k > 0 ? at(k) : impl.startLine, message: 'Implementation Design has no framing line naming the recommended option before its other parts' });
  } else {
    const where = { path: rel, line: at(framing) };
    framed = recommendedOption(shown[framing], 'framing line of Implementation Design', where, v);
  }

  let recommended = null;
  if (recommendations.length >= 1) {
    const rec = subsection(impl, heads, recommendations[0]);
    const records = new Map(RECOMMENDATION_RECORDS.map((labels) => [labels[0], []]));
    splitLines(blankComments(rec.text)).forEach((l, i) => {
      const line = rec.startLine + i;
      if (i === 0 || hidden.has(line)) return;
      for (const labels of RECOMMENDATION_RECORDS) {
        const label = labels.find((x) => labelValue(l, x) !== null);
        if (label) records.get(labels[0]).push({ line, label, value: labelValue(l, label) });
      }
    });
    for (const labels of RECOMMENDATION_RECORDS) {
      const found = records.get(labels[0]);
      const names = labels.map((x) => `"${x}"`).join(' or ');
      if (found.length === 0) {
        v.push({ path: rel, line: rec.startLine, message: `Recommendation has no ${names} record` });
      } else if (found.length > 1) {
        v.push({ path: rel, line: found[1].line, message: `Recommendation carries ${found.length} ${names} records; exactly one is required` });
      }
      for (const r of found) {
        if (r.value === '') v.push({ path: rel, line: r.line, message: `Recommendation record "${r.label}" is empty` });
      }
    }
    const option = records.get('Option:')[0];
    if (option && option.value !== '') {
      recommended = recommendedOption(option.value, 'Recommendation "Option:" record', { path: rel, line: option.line }, v);
    }
  }
  if (framed && recommended && framed !== recommended) {
    v.push({ path: rel, line: at(framing), message: `framing line names Option ${framed} but the Recommendation names Option ${recommended}` });
  }

  // Rejected alternatives: from the Rationale line (or the line after the framing line) to
  // the line before the "Shared by both options" block or the next level-3 heading.
  const chosen = recommended ?? framed;
  if (chosen) {
    const first = rationale.length > 0 ? rationale[0] : framing > 0 ? framing + 1 : 1;
    const stop = shared.find((s) => s.docLine > at(first)) ?? heads.find((h) => h.level === 3 && h.docLine > at(first));
    const last = (stop ? stop.docLine : impl.endLine + 1) - impl.startLine - 1;
    const rejected = new Set();
    for (let i = first; i <= last; i++) {
      if (!prose(i)) continue;
      for (const o of optionStances(shown[i]).rejected) rejected.add(o);
    }
    for (const o of ['A', 'B']) {
      if (o === chosen && rejected.has(o)) {
        v.push({ path: rel, line: at(first), message: `rationale rejects Option ${o}, the recommended option` });
      } else if (o !== chosen && !rejected.has(o)) {
        v.push({ path: rel, line: at(first), message: `rationale names no rejected alternative for Option ${o}; reject it in a line such as "- Rejected: Option ${o}, …" before the "${SHARED_LABEL}" block` });
      }
    }
  }

  const mermaid = fencedBlocks(impl.text).filter((b) => b.lang === 'mermaid');
  const found = [
    ...(framing > 0 ? [{ rank: 0, line: at(framing) }] : []),
    ...rationale.map((i) => ({ rank: 1, line: at(i) })),
    ...mermaid.map((b) => ({ rank: 2, line: at(b.startLine - 1) })),
    ...shared.map((s) => ({ rank: 3, line: s.docLine })),
    ...options.A.map((h) => ({ rank: 4, line: h.docLine })),
    ...options.B.map((h) => ({ rank: 5, line: h.docLine })),
    ...recommendations.map((h) => ({ rank: 6, line: h.docLine })),
  ];
  const names = ['the framing line', 'the "Rationale:" line', 'the Mermaid diagram', `the "${SHARED_LABEL}" block`, 'Design Option A', 'Design Option B', 'the Recommendation'];
  checkPartOrder(found, names, 'Implementation Design', rel, v);

  if (shared.length >= 1) checkSharedBlock(rel, subsection(impl, heads, shared[0]), hidden, v);
  for (const o of ['A', 'B']) {
    if (options[o].length >= 1) checkDesignOption(rel, o, subsection(impl, heads, options[o][0]), hidden, v);
  }
}

/**
 * The human-readable text of one Mermaid sequence-diagram line, or null when the line holds
 * keywords and identifiers only: a participant's display label (its identifier when it has
 * none), message and note text after the first ":" and block labels. Arrows and the
 * identifiers before a message or note ":" are never returned; a line of no known form is
 * returned whole, so text that renders nowhere is still voice-checked.
 */
function mermaidText(raw) {
  const t = String(raw).trim();
  if (t === '' || MERMAID_SILENT_RE.test(t)) return null;
  const participant = MERMAID_PARTICIPANT_RE.exec(t);
  if (participant) return participant[2] ?? participant[1];
  const label = MERMAID_LABEL_RE.exec(t);
  if (label) return label[1] ?? null;
  if (MERMAID_COLON_TEXT_RE.test(t)) {
    const at = t.search(/[:{]/);
    return at < 0 ? t : t.slice(at + 1);
  }
  const colon = t.indexOf(':');
  if (colon > 0 && MERMAID_ARROW_RE.test(t.slice(0, colon))) return t.slice(colon + 1).replace(MERMAID_WRAP_RE, '');
  return t;
}

/**
 * The participant identifiers a Mermaid message or note line names before its ":": the
 * sender and receiver around a message arrow, with any activation mark removed, or the one
 * or two participants of a note. Mermaid adds a participant it first meets this way and
 * renders it by its identifier.
 */
function mermaidReferences(raw) {
  const t = String(raw).trim();
  const colon = t.indexOf(':');
  if (colon <= 0) return [];
  const head = t.slice(0, colon).trim();
  const note = MERMAID_NOTE_HEAD_RE.exec(head);
  if (note) return note[1].split(',').map((id) => id.trim()).filter((id) => id !== '');
  const arrow = MERMAID_ARROW_RE.exec(head);
  if (!arrow) return [];
  const from = head.slice(0, arrow.index).replace(/<<$/, '').trim();
  const to = head.slice(arrow.index + arrow[0].length).replace(/^\s*[+-]/, '').trim();
  return [from, to].filter((id) => id !== '');
}

/**
 * Group D and voice: at most one Mermaid diagram, a sequence diagram inside Implementation
 * Design, whose participant labels, message and note text, block labels and the identifiers
 * of participants no participant or actor line declares meet the design voice.
 */
function checkMermaid(story, text, impl, v) {
  const rel = story.rel;
  const blocks = fencedBlocks(text).filter((b) => b.lang === 'mermaid');
  blocks.forEach((block, i) => {
    if (i > 0) {
      v.push({ path: rel, line: block.startLine, message: `Mermaid diagram ${i + 1} of ${blocks.length}; a story carries at most one` });
    }
    if (!impl || block.startLine < impl.startLine || block.endLine > impl.endLine) {
      v.push({ path: rel, line: block.startLine, message: 'Mermaid diagram lies outside Implementation Design' });
    }
    const bodyLines = splitLines(block.body);
    const first = bodyLines.find((l) => l.trim() !== '');
    if (!first || first.trim() !== 'sequenceDiagram') {
      v.push({ path: rel, line: block.startLine, message: 'Mermaid diagram is not a sequence diagram; its first line must be "sequenceDiagram"' });
    }
    const declared = new Set();
    for (const l of bodyLines) {
      const participant = MERMAID_PARTICIPANT_RE.exec(l.trim());
      if (participant) declared.add(participant[1]);
    }
    bodyLines.forEach((l, k) => {
      const line = block.startLine + 1 + k;
      const words = mermaidText(l);
      if (words !== null) checkVoiceText(words, rel, line, v);
      for (const id of new Set(mermaidReferences(l))) {
        if (!declared.has(id)) checkVoiceText(id, rel, line, v);
      }
    });
  });
}

/**
 * Group D: per option, one disjoint subsection under a heading naming that option alone; settled records under a "Settled"
 * label line or heading and open lines stating what settles them, outside tables and the `hidden` lines (fenced code
 * and HTML comment blocks); an AC verdict table with a Why for every AC.
 */
function checkDesignValidation(story, validation, acIds, hidden, v) {
  const OPEN_ARROW = 'Open →';
  const rel = story.rel;
  const heads = sectionHeadings(validation).filter((h) => h.level >= 3);
  // A heading names an option by the whole words "Option <letter>", so "Option Apple" names none.
  const named = heads.map((h) => ({
    head: h,
    options: [...new Set([...plainCell(h.text).matchAll(/\bOption ([A-Z])\b/g)].map((m) => m[1]))],
  }));
  for (const { head, options } of named) {
    if (options.length > 1) {
      v.push({ path: rel, line: head.docLine, message: `Design Validation heading "${plainCell(head.text)}" names Options ${options.join(' and ')}; each option needs a heading of its own` });
    }
  }
  const chosen = new Map();
  for (const option of ['A', 'B']) {
    const matching = named.filter((n) => n.options.length === 1 && n.options[0] === option);
    if (matching.length !== 1) {
      v.push({ path: rel, line: validation.startLine, message: `Design Validation carries ${matching.length} headings naming Option ${option}; exactly one is required` });
    }
    if (matching.length > 0) chosen.set(option, matching[0].head);
  }
  for (const option of ['A', 'B']) {
    const head = chosen.get(option);
    if (!head) continue;
    const other = option === 'A' ? 'B' : 'A';
    let sub = subsection(validation, heads, head);
    // The two subsections are disjoint: one ends before any heading naming the other option.
    const intruder = named.find((n) => n.options.includes(other) && n.head.docLine > head.docLine && n.head.docLine <= sub.endLine);
    if (intruder) {
      v.push({ path: rel, line: intruder.head.docLine, message: `heading "${plainCell(intruder.head.text)}" names Option ${other} but lies inside the Option ${option} subsection; the two options need disjoint subsections` });
      const end = intruder.head.docLine - 1;
      sub = { text: splitLines(sub.text).slice(0, end - sub.startLine + 1).join('\n'), startLine: sub.startLine, endLine: end };
    }
    const where = { path: rel, line: sub.startLine };
    const subLines = splitLines(sub.text);
    // What an assumption line says comes from `shown`, the lines with inline comments blanked;
    // which lines are blank, headings or table rows comes from subLines, the text as written.
    const shown = splitLines(blankComments(sub.text));
    const subTables = parseTables(sub.text);
    // Assumption records are prose: lines of fenced code and HTML comment blocks are skipped,
    // and heading and table lines (header, delimiter and body rows) end a record list. Both
    // come from the same block model, so a hidden heading or table is skipped, never a
    // boundary or a record. Indexes k are 0-based in subLines.
    const subHeads = parseHeadings(sub.text);
    const boundary = new Set(subHeads.map((h) => h.line - 1));
    for (const t of subTables) [t.line, t.line + 1, ...t.rowLines].forEach((l) => boundary.add(l - 1));
    const isHidden = (k) => hidden.has(sub.startLine + k);
    const prose = (k) => !isHidden(k) && !boundary.has(k);
    // Case-sensitive, so the lower-case "settled by" of an open assumption never starts a label.
    const isSettled = (l) => /^Settled\b/.test(normaliseLabel(l));
    const isOpen = (l) => l.includes(OPEN_ARROW) || /^Open\b/.test(normaliseLabel(l));
    // A bare list marker is an empty list item, not a record.
    const recordText = (l) => plainCell(normaliseLabel(l)).replace(/^(?:[-*+]|\d+\.)$/, '');
    // An unquoted heading below the option heading whose plain text starts with "Settled" is a label
    // too, keyed by its subLines index; its records follow it under the same rule as a label line's.
    const settledHeads = new Map(
      subHeads
        .filter((h) => !h.quoted && h.line > 1 && !isHidden(h.line - 1) && /^Settled\b/.test(plainCell(h.text)))
        .map((h) => [h.line - 1, plainCell(h.text)]),
    );

    const settled = shown.flatMap((l, k) => ((prose(k) && isSettled(l)) || settledHeads.has(k) ? [k] : []));
    if (settled.length === 0) v.push({ ...where, message: `Option ${option} validation states no settled assumptions` });
    for (const k of settled) {
      const label = settledHeads.get(k) ?? normaliseLabel(shown[k]);
      const colon = label.indexOf(':');
      const records = colon < 0 ? [] : [label.slice(colon + 1)];
      for (let j = k + 1; j < subLines.length; j++) {
        if (isHidden(j) || subLines[j].trim() === '') continue;
        if (boundary.has(j) || isOpen(shown[j]) || isSettled(shown[j])) break;
        records.push(shown[j]);
      }
      if (!records.some((r) => recordText(r) !== '')) {
        const name = plainCell(colon < 0 ? label : label.slice(0, colon));
        v.push({ path: rel, line: sub.startLine + k, message: `Option ${option} validation lists no settled assumption under "${name}"` });
      }
    }

    const open = shown.flatMap((l, k) => (prose(k) && l.includes(OPEN_ARROW) ? [k] : []));
    if (open.length === 0) {
      v.push({ ...where, message: `Option ${option} validation has no open assumption written as "Open → what settles it"` });
    }
    // An open line states the item and what settles it: "<assumption> Open → <what settles it>", or
    // "Open → <assumption>, settled by <what settles it>" ("settles" and "settled when" alike).
    const substantive = (s) => /[\p{L}\p{N}]/u.test(s);
    // The settler is the text after a leading "settles", "settled by" or "settled when".
    const settler = (s) => s.replace(/^[^\p{L}\p{N}]*settle(?:s|d)?\b[^\p{L}\p{N}]*(?:(?:by|when)\b)?/iu, '');
    const forms = '"<assumption> Open → <what settles it>" or "Open → <assumption>, settled by <what settles it>"';
    for (const k of open) {
      const l = shown[k];
      const at = l.indexOf(OPEN_ARROW);
      const before = plainCell(normaliseLabel(l.slice(0, at)));
      const after = plainCell(l.slice(at + OPEN_ARROW.length));
      const settles = substantive(before)
        ? substantive(settler(after))
        : [...after.matchAll(/\bsettle(?:s|d)?\b/gi)].some((m) => substantive(after.slice(0, m.index)) && substantive(settler(after.slice(m.index))));
      if (!substantive(after)) {
        v.push({ path: rel, line: sub.startLine + k, message: `Option ${option} open assumption names nothing after "Open →"; write ${forms}` });
      } else if (!settles) {
        v.push({ path: rel, line: sub.startLine + k, message: `Option ${option} open assumption does not say what settles it; write ${forms}` });
      }
    }

    const tables = subTables.filter(
      (t) => t.header.length === 3 && t.header.map((c) => plainCell(c).toLowerCase()).join('|') === 'ac|verdict|why',
    );
    if (tables.length === 0) {
      v.push({ ...where, message: `Option ${option} validation has no "AC | Verdict | Why" table` });
      continue;
    }
    const rows = new Map();
    for (const t of tables) {
      for (const s of surplusCellRows(t, `Option ${option} verdict table`)) {
        v.push({ path: rel, line: sub.startLine + s.line - 1, message: s.message });
      }
      t.rows.forEach((row, i) => {
        const line = sub.startLine + t.rowLines[i] - 1;
        const ac = plainCell(row[0]);
        if (!/^AC\d+$/.test(ac)) {
          v.push({ path: rel, line, message: `Option ${option} verdict row does not start with an AC identifier: "${ac}"` });
          return;
        }
        if (rows.has(ac)) v.push({ path: rel, line, message: `Option ${option} verdict table repeats ${ac}` });
        else rows.set(ac, line);
        const verdict = plainCell(row[1]);
        if (!VERDICTS.has(verdict)) {
          v.push({ path: rel, line, message: `Option ${option} verdict for ${ac} is "${verdict}"; verdicts are Holds, Conditional, At risk or Breaks` });
        }
        if (plainCell(row[2]) === '') v.push({ path: rel, line, message: `Option ${option} verdict row for ${ac} has an empty Why` });
      });
    }
    if (acIds === null) continue;
    for (const ac of acIds) {
      if (!rows.has(ac)) v.push({ ...where, message: `Option ${option} verdict table has no row for ${ac}` });
    }
    for (const [ac, line] of rows) {
      if (!acIds.includes(ac)) v.push({ path: rel, line, message: `Option ${option} verdict table names ${ac}, which section 2 does not define` });
    }
  }
}

/** `s` with every [start, end) span, sorted and non-overlapping, replaced by spaces in one pass. */
function maskSpans(s, spans) {
  if (spans.length === 0) return s;
  const parts = [];
  let pos = 0;
  for (const [start, end] of spans) {
    parts.push(s.slice(pos, start), ' '.repeat(end - start));
    pos = end;
  }
  parts.push(s.slice(pos));
  return parts.join('');
}

/** [start, end) spans of every match of a global pattern, in position order. */
const matchSpans = (s, re) => [...s.matchAll(re)].map((m) => [m.index, m.index + m[0].length]);

const TRAILING_PUNCTUATION = new Set(['.', ',', ';', ':', '!', '?', ')', ']']);

/** A reported token without surrounding space or trailing sentence punctuation. */
function trimToken(t) {
  const s = t.trim();
  let end = s.length;
  while (end > 0 && TRAILING_PUNCTUATION.has(s[end - 1])) end--;
  return s.slice(0, end);
}

/**
 * [start, end) spans of "[text](destination)" links in one line, text without "]" and
 * destination without ")": the matches of /\[[^\]]*\]\([^)]*\)/g, found in one pass. Every
 * "[" before a "]" that no "(" follows shares that "]", so the scan resumes after it, and
 * once no "]" or no ")" remains, no later "[" can complete a link.
 */
function linkSpans(s) {
  const spans = [];
  let open = s.indexOf('[');
  while (open >= 0) {
    const close = s.indexOf(']', open + 1);
    if (close < 0) break;
    if (s[close + 1] !== '(') {
      open = s.indexOf('[', close + 1);
      continue;
    }
    const end = s.indexOf(')', close + 2);
    if (end < 0) break;
    spans.push([open, end + 1]);
    open = s.indexOf('[', end + 1);
  }
  return spans;
}

/**
 * Voice: one violation per link, code span, path, code identifier, SQL, config key or
 * maturity tag in one line of design prose. Links and code spans are found on the raw text;
 * every later test reads the remaining characters as they render, and a backtick pair that
 * character references render around text counts as a backticked span. Each reported span
 * is masked before the next test so a token is reported once.
 */
function checkVoiceText(raw, rel, line, v) {
  let s = String(raw);
  const flag = (kind, token) => v.push({ path: rel, line, message: `design voice: ${kind} "${token}"` });
  for (const [kind, find] of [['link', linkSpans], ['backticked span', (t) => matchSpans(t, CODE_SPAN_RE)]]) {
    const found = find(s);
    for (const [start, end] of found) flag(kind, s.slice(start, end));
    s = maskSpans(s, found);
  }
  // "page\_token" and "page&#95;token" render as "page_token", while "\&amp;" renders as
  // "&amp;", so the tests below read the rendered characters.
  const rawTicks = s.split('`').length;
  s = unescapeInline(s);
  // A backtick a reference renders ("&#96;x&#96;") opens no code span, yet a pair of them
  // around text still shows that text in backticks.
  if (s.split('`').length > rawTicks) {
    const shown = matchSpans(s, CODE_SPAN_RE).filter(([start, end]) => /\S/.test(s.slice(start + 1, end - 1)));
    for (const [start, end] of shown) flag('backticked span', s.slice(start, end));
    s = maskSpans(s, shown);
  }

  const spans = [];
  for (const re of PATH_RES) {
    for (const m of s.matchAll(re)) spans.push([m.index, m.index + m[0].length]);
  }
  spans.sort((a, b) => a[0] - b[0] || b[1] - a[1]);
  const merged = [];
  for (const span of spans) {
    const last = merged[merged.length - 1];
    if (last && span[0] < last[1]) last[1] = Math.max(last[1], span[1]);
    else merged.push([...span]);
  }
  for (const [start, end] of merged) flag('path', trimToken(s.slice(start, end)));
  s = maskSpans(s, merged);

  s = s.replace(PROPER_NAME_RE, (name) => ' '.repeat(name.length));
  for (const [kind, re] of TOKEN_TESTS) {
    const found = matchSpans(s, re);
    for (const [start, end] of found) flag(kind, s.slice(start, end));
    s = maskSpans(s, found);
  }
}

/** The number of whitespace-separated words in `s`. */
const wordCount = (s) => s.split(/\s+/).filter((w) => w !== '').length;

/**
 * The phrase that opens a second clause in a table cell, or null. Three structural signals
 * mark one:
 *  - a comma before "and", "but", "or", "so", "yet" or "nor", except the closing ", and" or
 *    ", or" of a short series: the two comma-separated items right before it hold at most
 *    three words each and the item after the conjunction at most four ("unavailable, slow,
 *    and not-found answers");
 *  - a coordinating conjunction before a pronoun subject ("and it expires");
 *  - "and", "but", "so" or "yet" before a clause opening when an earlier clause opening ends
 *    before that conjunction ("the cache holds the answer and the service sends the reply").
 * Each signal takes one pass over the cell: the earliest clause opening ends where the first
 * match of LEFT_CLAUSE_RE ends, and every later conjunction is tested in place with the
 * sticky RIGHT_CLAUSE_RE, so the check stays linear in the cell length.
 * Cell clauses beyond these signals stay with the content gate's human readability review.
 */
function secondClauseJoin(s) {
  const commas = [];
  for (let i = s.indexOf(','); i >= 0; i = s.indexOf(',', i + 1)) commas.push(i);
  let k = 0;
  for (const m of s.matchAll(COMMA_CONJUNCTION_RE)) {
    while (commas[k] < m.index) k++;
    let series = false;
    if ((m[1] === 'and' || m[1] === 'or') && k >= 1) {
      const last = s.slice(commas[k - 1] + 1, m.index);
      const previous = s.slice(k >= 2 ? commas[k - 2] + 1 : 0, commas[k - 1]);
      const after = s.slice(m.index + m[0].length, k + 1 < commas.length ? commas[k + 1] : s.length);
      series = wordCount(previous) <= 3 && wordCount(last) <= 3 && wordCount(after) <= 4;
    }
    if (!series) return m[0];
  }
  const pronoun = PRONOUN_CLAUSE_RE.exec(s);
  if (pronoun) return pronoun[0];
  const left = LEFT_CLAUSE_RE.exec(s);
  if (left === null) return null;
  const leftEnd = left.index + left[0].length;
  for (const m of s.matchAll(NOUN_CLAUSE_JOIN_RE)) {
    if (m.index < leftEnd) continue;
    RIGHT_CLAUSE_RE.lastIndex = m.index + m[0].length;
    const right = RIGHT_CLAUSE_RE.exec(s);
    if (right) return `${m[0]}${right[0]}`;
  }
  return null;
}

/**
 * Voice over a whole design section: every line outside fenced code; every fenced block
 * other than a Mermaid diagram, which is itself a violation since the story's one sequence
 * diagram is the only fenced content a design section admits, and each of its lines
 * (checkMermaid checks the diagram's text); then every table cell.
 */
function checkVoiceSection(story, section, fences, v) {
  const rel = story.rel;
  const lines = splitLines(section.text);
  lines.forEach((l, k) => {
    const line = section.startLine + k;
    if (!fences.has(line)) checkVoiceText(l, rel, line, v);
  });
  for (const block of fencedBlocks(section.text)) {
    if (block.lang === 'mermaid') continue;
    const line = section.startLine + block.startLine - 1;
    v.push({
      path: rel,
      line,
      message: `design voice: fenced block "${lines[block.startLine - 1].trim()}" in ${section.heading}; the only fenced content a design section carries is its Mermaid sequence diagram`,
    });
    splitLines(block.body).forEach((l, k) => checkVoiceText(l, rel, line + 1 + k, v));
  }
  for (const table of parseTables(section.text)) {
    const rows = [table.header, ...table.rows];
    const rowLines = [table.line, ...table.rowLines];
    rows.forEach((row, r) => {
      const line = section.startLine + rowLines[r] - 1;
      for (const cell of row) {
        const plain = plainCell(cell);
        if (plain.includes(';')) v.push({ path: rel, line, message: `design voice: table cell with a semicolon "${plain}"` });
        if (hasSecondSentence(plain)) v.push({ path: rel, line, message: `design voice: table cell with a second sentence "${plain}"` });
        const join = secondClauseJoin(plain);
        if (join) v.push({ path: rel, line, message: `design voice: table cell with a second clause at "${join}" "${plain}"` });
      }
    });
  }
}

/**
 * Group M: the metadata block holds each field once and no other field, every value is
 * non-blank, the supplied values and TBD defaults hold, and points, files touched, wave,
 * epic and dependencies are valid. A finding about a value names the line that gives it.
 */
function checkMetadata(story, text, ctx, v) {
  const rel = story.rel;
  const section = h2Section(text, 'Metadata');
  if (!section) return;
  for (const t of parseTables(section.text)) {
    for (const s of surplusCellRows(t, 'Metadata table')) v.push({ path: rel, line: section.startLine + s.line - 1, message: s.message });
  }
  const fieldByKey = new Map(METADATA_FIELDS.map((field) => [normaliseSpace(field).toLowerCase(), field]));
  // The first entry of each field is the one read; a repeat is reported at its own line.
  const first = new Map();
  for (const entry of metadataEntries(section.text)) {
    const line = section.startLine + entry.line - 1;
    const field = fieldByKey.get(entry.key);
    if (field === undefined) {
      v.push({ path: rel, line, message: `unexpected metadata field "${entry.key}"; the metadata block holds only ${METADATA_FIELDS.join(', ')}` });
    } else if (first.has(field)) {
      v.push({ path: rel, line, message: `metadata field "${field}" is given again; it is first given at line ${first.get(field).line}` });
    } else {
      first.set(field, { line, value: plainCell(entry.value) });
    }
  }
  // A missing or blank field is reported once here; the value checks read only the rest.
  const given = new Map();
  for (const field of METADATA_FIELDS) {
    const entry = first.get(field);
    if (!entry) v.push({ path: rel, line: section.startLine, message: `metadata field "${field}" is missing` });
    else if (entry.value === '') v.push({ path: rel, line: entry.line, message: `metadata field "${field}" has no value` });
    else given.set(field, entry);
  }
  const value = (field) => (given.has(field) ? given.get(field).value : null);
  const at = (field) => ({ path: rel, line: given.get(field).line });

  for (const [field, expected] of SUPPLIED_METADATA) {
    const actual = value(field);
    if (actual !== null && actual !== expected) {
      v.push({ ...at(field), message: `${field} "${actual}" is not ${expected}, the value the request supplies` });
    }
  }
  for (const field of TBD_METADATA_FIELDS) {
    const actual = value(field);
    if (actual !== null && actual !== 'TBD') {
      v.push({ ...at(field), message: `${field} "${actual}" is not TBD; no input supplies a value for it` });
    }
  }

  const points = value('Story Points');
  if (points !== null) {
    const n = intStrict(points);
    if (n === null || n < 1 || n > 5) v.push({ ...at('Story Points'), message: `Story Points "${points}" is not an integer from 1 to 5` });
  }
  const files = value('Estimated Files Touched');
  if (files !== null && intStrict(files) === null) {
    const problem = intAboveSafe(files)
      ? `is above ${Number.MAX_SAFE_INTEGER}, the largest integer this check compares`
      : 'is not a single integer';
    v.push({ ...at('Estimated Files Touched'), message: `Estimated Files Touched "${files}" ${problem}` });
  }
  const wave = value('Build wave');
  if (wave !== null && !WAVES.has(wave)) v.push({ ...at('Build wave'), message: `Build wave "${wave}" is not one of W0, W1, W2, W3` });

  const epicLink = value('Epic Link');
  if (epicLink !== null && epicLink !== story.epic.id) {
    v.push({ ...at('Epic Link'), message: `Epic Link "${epicLink}" differs from the parent epic ${story.epic.id}` });
  }
  const epicName = value('Epic Name');
  const expectedName = ctx.epicNames.get(story.epic.id);
  if (epicName !== null && expectedName && epicName !== expectedName) {
    v.push({ ...at('Epic Name'), message: `Epic Name "${epicName}" differs from the epic README title "${expectedName}"` });
  }

  // Depends on is exactly None or a comma-separated list of distinct story identifiers.
  // Each member is matched whole, so text beside an identifier is reported, never dropped.
  const dependsOn = value('Depends on');
  if (dependsOn !== null && dependsOn !== 'None') {
    const where = at('Depends on');
    const ids = new Set();
    dependsOn.split(',').forEach((raw, k) => {
      const member = raw.trim();
      const position = `member ${k + 1} of Depends on "${dependsOn}"`;
      if (member === '') {
        v.push({ ...where, message: `${position} is empty (a leading, trailing or doubled comma)` });
      } else if (member === 'None') {
        v.push({ ...where, message: `${position} is None inside a list; None stands alone, never beside story identifiers` });
      } else if (!DEPENDS_ON_ID_RE.test(member)) {
        v.push({ ...where, message: `${position} is "${member}", not a story identifier CS-NN-NNN; the value is None or story identifiers separated by commas` });
      } else if (ids.has(member)) {
        v.push({ ...where, message: `${position} repeats ${member}` });
      } else {
        ids.add(member);
      }
    });
    for (const id of ids) {
      if (id === story.key) v.push({ ...where, message: `Depends on names the story itself (${id})` });
      else if (!ctx.storyKeys.has(id)) v.push({ ...where, message: `Depends on names ${id}, which has no story file` });
    }
  }
}

// A row's first cell does not depend on the header above it, so each table row is keyed
// under this fixed two-column header: the work per row stays bounded by the row's length.
const FIELD_ROW_HEADER = '| Field | Value |\n|---|---|\n';

/**
 * Whether a field's value has a form the metadata block gives that field: TBD for any
 * field, the value the request supplies, a wave W<n>, an integer count, None, N/A or story
 * identifiers, the parent epic's identifier, or its name (`epicName`, null when unread).
 * @param {string} field one of METADATA_FIELDS
 * @param {string} value the plain-text value
 * @param {string | null} epicName
 * @returns {boolean}
 */
function metadataValueForm(field, value, epicName) {
  if (value === 'TBD' || value === SUPPLIED_METADATA.get(field)) return true;
  switch (field) {
    case 'Build wave':
      return /^W\d+$/.test(value);
    case 'Story Points':
    case 'Estimated Files Touched':
      // A digit run above the safe-integer range is still written as a count.
      return intStrict(value) !== null || intAboveSafe(value);
    case 'Depends on':
      return value === 'None' || value === 'N/A' || value.split(',').every((m) => DEPENDS_ON_ID_RE.test(m.trim()));
    case 'Epic Link':
      return /^CS-\d{2}$/.test(value);
    case 'Epic Name':
      return value === epicName;
    default:
      return false;
  }
}

/**
 * Group S: metadata fields are collected in the metadata block, never scattered through the
 * story. Each line and table row above "## Metadata" (`end`), the quoted source body included
 * and fenced code and HTML comments skipped, is keyed as the block is, by metadataEntries
 * under FIELD_ROW_HEADER for a table line. A record naming a metadata field is reported at
 * its line when it is a body row of a "Field | Value" table or its value has the field's form.
 * @param {string} rel
 * @param {string} text
 * @param {string[]} lines
 * @param {number} end the line of "## Metadata"
 * @param {string | null} epicName the parent epic's name, null when its line cannot be read
 * @param {{ path: string, line: number, message: string }[]} v
 */
function checkScatteredMetadata(rel, text, lines, end, epicName, v) {
  const hidden = hiddenLines(text);
  const fieldByKey = new Map(METADATA_FIELDS.map((field) => [normaliseSpace(field).toLowerCase(), field]));
  // Each table line before the block maps to true when it is a body row under a "Field | Value" header.
  const tableRows = new Map();
  for (const t of parseTables(text)) {
    if (t.line >= end) continue;
    const header = t.header.map((c) => plainCell(c).toLowerCase());
    const fieldTable = header.length === 2 && header[0] === 'field' && header[1] === 'value';
    tableRows.set(t.line, false);
    tableRows.set(t.line + 1, false);
    for (const at of t.rowLines) tableRows.set(at, fieldTable);
  }
  for (let l = 1; l < end; l++) {
    const s = lines[l - 1].replace(QUOTE_PREFIX_RE, '').trim();
    if (s === '' || hidden.has(l)) continue;
    const asRow = tableRows.has(l) || s.startsWith('|');
    for (const entry of metadataEntries(asRow ? FIELD_ROW_HEADER + s : s)) {
      const field = fieldByKey.get(entry.key);
      if (field === undefined) continue;
      // A prose label line ("Labels: descriptions come from the type list") has no field value form, so it passes.
      if (tableRows.get(l) || metadataValueForm(field, plainCell(entry.value).replace(/\.$/, ''), epicName)) {
        const where = `metadata field "${field}" before "## Metadata": "${s}"`;
        v.push({ path: rel, line: l, message: `${where}; metadata fields are collected in the metadata block` });
      }
    }
  }
}

/**
 * Group S on the story's outer boundary. The title line is the only non-quoted level-1
 * heading. Only blank lines separate the parent epic line from the first level-2 heading.
 * No metadata field record appears before "## Metadata" (checkScatteredMetadata).
 * The metadata block ends the file: after "## Metadata" no heading or fenced code appears,
 * and every other non-blank line is a header, alignment or field row of an unquoted table,
 * or a field line in a form metadataEntries reads, and each row or line names a metadata field.
 */
function checkStoryBoundary(story, text, lines, fences, v) {
  const rel = story.rel;
  const heads = parseHeadings(text).filter((h) => !h.quoted);
  const nonBlank = [];
  for (let i = 0; i < lines.length && nonBlank.length < 2; i++) {
    if (lines[i].trim() !== '') nonBlank.push(i + 1);
  }
  const [titleLine, parentLine] = nonBlank;

  for (const h of heads) {
    if (h.level === 1 && h.line !== titleLine) {
      v.push({ path: rel, line: h.line, message: `level-1 heading "# ${h.text}" besides the title line; a story has one level-1 heading, its title` });
    }
  }

  // A malformed or missing parent epic line is reported by checkTitleAndParent.
  const firstH2 = heads.find((h) => h.level === 2);
  if (parentLine !== undefined && firstH2 && firstH2.line > parentLine && PARENT_EPIC_RE.test(lines[parentLine - 1].trim())) {
    for (let l = parentLine + 1; l < firstH2.line; l++) {
      const s = lines[l - 1].trim();
      if (s !== '') {
        v.push({ path: rel, line: l, message: `only blank lines may separate the parent epic line from "## ${firstH2.text}"; found "${s}"` });
      }
    }
  }

  const meta = heads.find((h) => h.level === 2 && normaliseSpace(h.text) === 'Metadata');
  if (!meta) return;
  const parentEpic = parentLine === undefined ? null : PARENT_EPIC_RE.exec(lines[parentLine - 1].trim());
  checkScatteredMetadata(rel, text, lines, meta.line, parentEpic ? normaliseSpace(parentEpic[2]) : null, v);
  const fields = new Set(METADATA_FIELDS.map((f) => normaliseSpace(f).toLowerCase()));
  // metadataEntries keys a line or a one-row table exactly as checkMetadata reads it.
  const namesField = (s) => metadataEntries(s).some((entry) => fields.has(entry.key));
  const blockLines = new Set();
  for (const t of parseTables(text)) {
    if (t.line <= meta.line || QUOTE_PREFIX_RE.test(lines[t.line - 1]) || t.header.length < 2 || t.rows.length === 0) continue;
    blockLines.add(t.line);
    blockLines.add(t.line + 1);
    t.rowLines.forEach((at) => {
      if (namesField(FIELD_ROW_HEADER + lines[at - 1])) blockLines.add(at);
    });
  }
  for (const block of fencedBlocks(text)) {
    if (block.startLine > meta.line) {
      v.push({ path: rel, line: block.startLine, message: 'fenced code after "## Metadata"; the metadata block is the last part of a story' });
    }
  }
  const headAt = new Map(heads.filter((h) => h.line > meta.line).map((h) => [h.line, h]));
  for (let l = meta.line + 1; l <= lines.length; l++) {
    const s = lines[l - 1].trim();
    if (s === '' || fences.has(l) || blockLines.has(l)) continue;
    const h = headAt.get(l);
    if (h) {
      v.push({ path: rel, line: l, message: `heading "${'#'.repeat(h.level)} ${h.text}" after "## Metadata"; the metadata block is the last part of a story` });
    } else if (!namesField(lines[l - 1])) {
      v.push({ path: rel, line: l, message: `content after the metadata block "${s}"; only metadata field rows or field lines follow "## Metadata"` });
    }
  }
}

/** All story predicates for one story file. */
function checkStory(story, ctx, v) {
  const text = readText(story.rel);
  const lines = splitLines(text);
  const fences = fenceLineSet(text);
  const hidden = hiddenLines(text);

  checkTitleAndParent(story, lines, ctx, v);
  const h2 = parseHeadings(text).filter((h) => !h.quoted && h.level === 2);
  checkHeadingSequence(h2, STORY_SECTIONS, story.rel, '##', v);
  if (h2.some((h) => normaliseSpace(h.text) === 'Metadata') && normaliseSpace(h2[h2.length - 1].text) !== 'Metadata') {
    v.push({ path: story.rel, line: h2[h2.length - 1].line, message: '"## Metadata" must be the last level-2 section' });
  }
  checkStoryBoundary(story, text, lines, fences, v);

  const acIds = checkSourceBody(story, text, lines, v);
  checkDesignAndTestPlan(story, text, v);

  const impl = h2Section(text, 'Implementation Design');
  if (impl) {
    checkImplementationDesign(story, impl, hidden, v);
    checkVoiceSection(story, impl, fences, v);
  }
  checkMermaid(story, text, impl, v);
  const validation = h2Section(text, 'Design Validation');
  if (validation) {
    checkDesignValidation(story, validation, acIds, hidden, v);
    checkVoiceSection(story, validation, fences, v);
  }
  checkMetadata(story, text, ctx, v);
}

function main() {
  const v = [];
  const { files, epics, storyKeys } = checkPaths(v);
  const epicNames = new Map();
  for (const epic of epics) {
    if (!files.has(epic.readme)) continue;
    const name = checkEpicReadme(epic, v);
    if (name !== null) epicNames.set(epic.id, name);
  }
  checkEpicIndex(files, epics, epicNames, v);
  const ctx = { storyKeys, epicNames };
  for (const epic of epics) {
    for (const story of epic.stories) checkStory(story, ctx, v);
  }
  report(CHECK, v);
}

const HELP_FLAGS = new Set(['--help', '-h']);
const USAGE = `Usage: node tests/validation/check-backlog-structure.mjs [--help | -h]

Checks the backlog structure and story content under ${EPICS_DIR}/. It takes no
arguments: --help or -h prints this message and exits 0 without scanning, and any other
argument is a usage error. It runs from any directory, because every path resolves
against the repository root derived from this script's own location.

It scans every file and folder under ${EPICS_DIR}/ and nothing else: the epic
index ${INDEX_README}, each epic folder's README.md and the story files in
its stories/ folder. It never reads app/, diagrams/ or samples/.

Output on stdout, one line per violation, then the count:
  ${CHECK}: <path>[:<line>]: <message>
  ${CHECK}: <N> violation(s)

Exit status: 0 with no violation, 1 with one or more, 2 on a usage or internal error.`;

/**
 * The command-line contract, applied before any scan. With no argument the scan runs.
 * Help flags alone print the usage on stdout. Any other argument, alone or beside a help
 * flag, is a usage error: it is named on stderr with the usage, and the exit status is 2.
 * Returns true only when the scan should run.
 */
function acceptArguments(args) {
  if (args.length === 0) return true;
  const unknown = args.filter((arg) => !HELP_FLAGS.has(arg));
  if (unknown.length === 0) {
    console.log(USAGE);
  } else {
    const shown = unknown.map((arg) => escapeControls(JSON.stringify(arg)));
    console.error(`${CHECK}: unknown argument(s): ${shown.join(', ')}`);
    console.error(USAGE);
    process.exitCode = 2;
  }
  return false;
}

try {
  if (acceptArguments(process.argv.slice(2))) main();
} catch (err) {
  console.error(`${CHECK}: internal error: ${formatError(err)}`);
  process.exitCode = 2;
}

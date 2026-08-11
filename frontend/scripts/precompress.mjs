#!/usr/bin/env node
/**
 * Pre-compress the Vite build output so nginx can serve Brotli.
 *
 * :purpose: The runtime image is stock ``nginx:alpine``, which is built without
 *     ``ngx_brotli``. nginx can therefore negotiate Brotli only from a file that is
 *     ALREADY Brotli-compressed on disk, the way its bundled ``gzip_static`` module
 *     negotiates gzip. This script produces those files: for every ``.js`` and
 *     ``.css`` emitted by ``vite build`` it writes a ``<name>.br`` and a
 *     ``<name>.gz`` sibling next to the original. ``frontend/nginx.conf`` selects
 *     the ``.br`` for a client whose ``Accept-Encoding`` names ``br`` and lets
 *     ``gzip_static`` serve the ``.gz`` otherwise, with dynamic gzip switched off in
 *     those locations so no response is compressed twice.
 *
 *     Compressing here rather than per request is what makes maximum effort
 *     affordable: Brotli quality 11 and gzip level 9 run ONCE per build instead of
 *     on every hit, so the bytes on the wire are smaller than a per-request
 *     ``gzip_comp_level 6`` and cost the server nothing at all.
 *
 * :param argv[2]: Directory holding the build output to walk, recursively.
 *     Defaults to ``dist`` (the Vite ``outDir``).
 * :returns: Exit status 0 when at least one candidate was compressed, 2 when the
 *     directory holds no ``.js`` or ``.css`` file at all. The non-zero status is
 *     deliberate: it fails the image build loudly rather than shipping a bundle
 *     whose compressed siblings are silently absent.
 */

import { brotliCompressSync, constants, gzipSync } from 'node:zlib';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { extname, join, relative } from 'node:path';

/** Extensions whose compressed siblings frontend/nginx.conf actually serves. */
const COMPRESSIBLE = new Set(['.js', '.css']);

/**
 * Collect every compressible file under a directory.
 *
 * :param dir: Directory to walk.
 * :param found: Accumulator carried through the recursion.
 * :returns: Absolute paths of the compressible files, in directory order.
 */
function collect(dir, found = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      collect(path, found);
    } else if (entry.isFile() && COMPRESSIBLE.has(extname(entry.name))) {
      found.push(path);
    }
  }
  return found;
}

/**
 * Compress one file into its ``.br`` and ``.gz`` siblings.
 *
 * A sibling is written only when it is genuinely smaller than the original: a
 * variant larger than the file it encodes would make the response bigger for a
 * client that asked for compression, so in that case none is written and nginx
 * falls back to the plain file on its own.
 *
 * :param path: File to compress.
 * :returns: ``{ raw, br, gz }`` byte counts, where ``br``/``gz`` are 0 when no
 *     sibling was written.
 */
function compress(path) {
  const raw = readFileSync(path);
  const br = brotliCompressSync(raw, {
    params: {
      [constants.BROTLI_PARAM_MODE]: constants.BROTLI_MODE_TEXT,
      [constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY,
      [constants.BROTLI_PARAM_SIZE_HINT]: raw.length,
    },
  });
  const gz = gzipSync(raw, { level: constants.Z_BEST_COMPRESSION });

  let brWritten = 0;
  let gzWritten = 0;
  if (br.length < raw.length) {
    writeFileSync(`${path}.br`, br);
    brWritten = br.length;
  }
  if (gz.length < raw.length) {
    writeFileSync(`${path}.gz`, gz);
    gzWritten = gz.length;
  }
  return { raw: raw.length, br: brWritten, gz: gzWritten };
}

const root = process.argv[2] ?? 'dist';

if (!statSync(root, { throwIfNoEntry: false })?.isDirectory()) {
  process.stderr.write(`precompress: "${root}" is not a directory\n`);
  process.exit(2);
}

const files = collect(root);
if (files.length === 0) {
  process.stderr.write(
    `precompress: no .js or .css file under "${root}" -- nothing to compress. ` +
      'The build output shape changed; frontend/nginx.conf expects a .br sibling ' +
      'for every script and stylesheet it serves.\n',
  );
  process.exit(2);
}

let totalRaw = 0;
let totalBr = 0;
let totalGz = 0;
for (const path of files) {
  const { raw, br, gz } = compress(path);
  totalRaw += raw;
  totalBr += br || raw;
  totalGz += gz || raw;
  process.stdout.write(
    `precompress: ${relative(root, path)} ${raw} -> br ${br || raw} / gz ${gz || raw}\n`,
  );
}

const pct = (part) => ((1 - part / totalRaw) * 100).toFixed(1);
process.stdout.write(
  `precompress: ${files.length} file(s), ${totalRaw} bytes -> ` +
    `br ${totalBr} (-${pct(totalBr)}%) / gz ${totalGz} (-${pct(totalGz)}%)\n`,
);

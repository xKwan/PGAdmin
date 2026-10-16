// Build checks on dist/:
// - plan Phase 5: the bundle must not reference any external origin (http(s):// URLs outside a small allow-list of
//   inert namespace strings);
// - plan Phase 8: size budgets in gzip bytes. The editor (CodeMirror) is a lazily loaded chunk of its own.
// Sizes are written to dist-report.json so each phase can record them.
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { gzipSync } from 'node:zlib';

export const BUDGETS = [
  { name: 'editor chunk', match: /^SqlEditor-.*\.js$/, maxGzip: 400 * 1024 },
  { name: 'entry chunk', match: /^index-.*\.js$/, maxGzip: 250 * 1024 },
];

/** Gzip size of every JS/CSS asset and the budget verdicts. */
export function sizeReport(files, read = readFileSync) {
  const assets = files
    .filter((f) => /\.(js|css)$/.test(f))
    .map((f) => ({ file: basename(f), bytes: read(f).length, gzip: gzipSync(read(f)).length }));
  const budgets = BUDGETS.map((b) => {
    const hits = assets.filter((a) => b.match.test(a.file));
    const gzip = hits.reduce((n, a) => n + a.gzip, 0);
    return { name: b.name, files: hits.map((a) => a.file), gzip, maxGzip: b.maxGzip, ok: hits.length > 0 && gzip <= b.maxGzip };
  });
  return { assets, budgets };
}

const ALLOWED = [
  /^https?:\/\/www\.w3\.org\//, // SVG / XML namespaces inside libraries
  /^https?:\/\/reactjs\.org\/docs\/error-decoder/, // React error decoder text in minified errors
  /^https?:\/\/react\.dev\/errors\//, // React 19 error decoder text
];

export function findExternalUrls(text) {
  const found = [];
  for (const m of text.matchAll(/https?:\/\/[^\s"'`)<>\\]+/g)) {
    if (!ALLOWED.some((re) => re.test(m[0]))) found.push(m[0]);
  }
  return found;
}

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

if (process.argv[1] && process.argv[1].endsWith('check-dist.mjs')) {
  const problems = [];
  for (const file of walk('dist')) {
    if (!/\.(js|css|html|svg|json)$/.test(file)) continue;
    for (const url of findExternalUrls(readFileSync(file, 'utf8'))) problems.push(`${file}: ${url}`);
  }
  if (problems.length > 0) {
    console.error('External URLs found in the build output:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log('check-dist: no external URLs in dist/');
  const report = sizeReport(walk('dist'));
  writeFileSync('dist-report.json', JSON.stringify(report, null, 2));
  for (const b of report.budgets) {
    console.log(`check-dist: ${b.name} ${(b.gzip / 1024).toFixed(1)} KB gzip (budget ${b.maxGzip / 1024} KB) ${b.ok ? 'ok' : 'OVER BUDGET or missing'}`);
  }
  if (report.budgets.some((b) => !b.ok)) process.exit(1);
}

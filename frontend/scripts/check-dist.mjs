// Build check (plan Phase 5): the production bundle must not reference any external origin.
// Fails the build if dist/ contains an http(s):// URL outside the small allow-list of inert namespace strings.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

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
}

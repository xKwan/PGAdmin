import { describe, expect, it } from 'vitest';
import { findExternalUrls, sizeReport } from './check-dist.mjs';

describe('findExternalUrls', () => {
  it('reports CDN and API origins', () => {
    expect(findExternalUrls('import "https://cdn.example.com/x.js"; fetch("http://evil.test/a")')).toEqual([
      'https://cdn.example.com/x.js',
      'http://evil.test/a',
    ]);
  });

  it('allows inert namespace and error-decoder strings', () => {
    expect(findExternalUrls('"http://www.w3.org/2000/svg" "https://react.dev/errors/418"')).toEqual([]);
  });
});

describe('sizeReport', () => {
  const files = {
    'dist/assets/SqlEditor-abc.js': 'x'.repeat(5000),
    'dist/assets/index-def.js': 'y'.repeat(3000),
    'dist/assets/index-def.css': 'z'.repeat(100),
    'dist/index.html': '<html></html>',
  };
  const read = (f) => Buffer.from(files[f]);

  it('measures JS and CSS assets and checks each budget', () => {
    const r = sizeReport(Object.keys(files), read);
    expect(r.assets.map((a) => a.file)).toEqual(['SqlEditor-abc.js', 'index-def.js', 'index-def.css']);
    expect(r.budgets.map((b) => [b.name, b.ok])).toEqual([
      ['editor chunk', true],
      ['entry chunk', true],
    ]);
    expect(r.budgets[0].gzip).toBeLessThan(5000);
  });

  it('fails a budget whose chunk is missing (the editor must stay a separate chunk)', () => {
    const r = sizeReport(['dist/assets/index-def.js'], read);
    expect(r.budgets[0]).toMatchObject({ name: 'editor chunk', files: [], ok: false });
  });
});

import { describe, expect, it } from 'vitest';
import { findExternalUrls } from './check-dist.mjs';

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

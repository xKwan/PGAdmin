import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from '../mocks/server.js';

// jsdom has no layout. CodeMirror measures text through Range rectangles; give it empty ones.
if (typeof Range !== 'undefined') {
  const empty = () => ({ x: 0, y: 0, width: 0, height: 0, top: 0, right: 0, bottom: 0, left: 0, toJSON() {} });
  Range.prototype.getBoundingClientRect ??= empty;
  Range.prototype.getClientRects ??= () => ({ length: 0, item: () => null, [Symbol.iterator]: [][Symbol.iterator] });
}

// The result grid is virtualised and only renders rows that fit its scroll area, which has no size in jsdom.
// Give that one element a screen-sized box (TanStack Virtual measures offsetWidth/offsetHeight).
for (const [prop, size] of [['offsetWidth', 1024], ['offsetHeight', 600]]) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
  Object.defineProperty(HTMLElement.prototype, prop, {
    configurable: true,
    get() {
      return this.classList?.contains('result-scroll') ? size : original.get.call(this);
    },
  });
}

// jsdom has no fetch base URL; MSW intercepts relative /api/ calls against this origin.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  window.localStorage.clear(); // e.g. the remembered last database: each test starts with a fresh browser
  window.sessionStorage.clear(); // the query history
});
afterAll(() => server.close());

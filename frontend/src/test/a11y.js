import axe from 'axe-core';

/**
 * Runs axe-core on a rendered container and returns the violations (empty = accessible). Colour contrast is not
 * checked here because jsdom does no layout; Playwright checks it in the real browser (Phase 11).
 */
export async function axeViolations(container) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`);
}

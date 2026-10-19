// Phase 8: the main workflow in a real browser. Windows sign-in (NTLM loopback on the workstation), a database
// session through the production SSPI path, the CodeMirror editor under the strict CSP, and every result view.
import { expect, test } from '@playwright/test';

const seed = Number(process.env.E2E_SEED || '1');
const role = process.env.E2E_ROLE;

// Small seeded PRNG (mulberry32) so a reported seed replays the same choices.
function rng(name) {
  let h = seed >>> 0;
  for (const ch of name) h = Math.imul(h ^ ch.charCodeAt(0), 2654435761) >>> 0;
  return () => {
    h = (h + 0x6d2b79f5) >>> 0;
    let t = h;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Collects CSP violations and page errors; the test fails if any occur. */
async function watch(page) {
  const problems = [];
  const origin = new URL(process.env.E2E_BASE_URL).origin;
  page.on('request', (r) => {
    if (!r.url().startsWith(origin) && !r.url().startsWith('data:')) problems.push(`request to another origin: ${r.url()}`);
  });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && /Content Security Policy|Refused to/.test(m.text())) problems.push(`console: ${m.text()}`);
  });
  page.on('dialog', (d) => {
    problems.push(`dialog: ${d.message()}`);
    d.dismiss();
  });
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      window.__cspViolations = [...(window.__cspViolations || []), `${e.violatedDirective} ${e.blockedURI}`];
    });
  });
  return async () => {
    const csp = await page.evaluate(() => window.__cspViolations || []);
    return [...problems, ...csp.map((v) => `csp: ${v}`)];
  };
}

// Signing out ends the app session, and with it every DB session of this sign-in (otherwise the per-user limit of
// 3 sessions fills up across tests and repetitions).
test.afterEach(async ({ page }) => {
  const signOut = page.getByRole('button', { name: 'Sign out' });
  if ((await signOut.count()) > 0) {
    await signOut.click();
    await expect(page.getByText('You have been signed out of the application.')).toBeVisible();
  }
});

async function connect(page) {
  await page.goto('/');
  await expect(page.getByText('pgweb', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: /External PostgreSQL/ }).click();
  await page.getByRole('list', { name: 'Databases', exact: true }).getByRole('button', { name: /^e2e_db\b/ }).click();
  await expect(page.getByLabel('Database session')).toContainText(`as ${role}`);
  const editor = page.getByRole('textbox', { name: 'SQL editor' });
  await expect(editor).toBeVisible();
  return editor;
}

async function run(page, editor, sql) {
  await editor.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Delete');
  await page.keyboard.insertText(sql);
  await page.keyboard.press('Control+Enter');
}

test('sign in, connect as my own role, run statements and see every result view', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const results = page.getByRole('region', { name: 'Results' });

  const n = 1 + Math.floor(rng('rows')() * 20);
  await run(page, editor, `SELECT id, name, NULL::text AS nothing, 'NULL' AS word FROM items WHERE id <= ${n} ORDER BY id`);
  await expect(results.getByText(`${n} ${n === 1 ? 'row' : 'rows'}`)).toBeVisible();
  await expect(results.getByRole('columnheader', { name: /name text/ })).toBeVisible();
  // SQL NULL (marked cell) and the text 'NULL' (plain cell) are told apart: one of each per row.
  await expect(results.locator('td.cell-null .null-marker')).toHaveCount(n);
  await expect(results.locator('td:not(.cell-null)', { hasText: /^NULL$/ })).toHaveCount(n);

  await run(page, editor, 'SELECT id FROM items');
  await expect(results.getByRole('note')).toContainText('Only the first 1,000 rows are shown');
  await expect(results.getByText('Rows 1–200 of 1,000')).toBeVisible();
  await results.getByRole('button', { name: 'Next' }).click();
  await expect(results.getByText('Rows 201–400 of 1,000')).toBeVisible();

  await run(page, editor, "UPDATE items SET name = name WHERE id <= 3");
  await expect(results.getByRole('status')).toHaveText('Completed. 3 rows affected.');

  await run(page, editor, "SELECT '<img src=x onerror=alert(1)>' AS html");
  await expect(results.getByText('<img src=x onerror=alert(1)>')).toBeVisible();

  await run(page, editor, 'SELECT * FROM no_such_table');
  await expect(results.getByRole('alert')).toContainText('42P01');
  await results.getByRole('button', { name: /Show position 15/ }).click();

  // Ctrl+Enter with the cursor in the text runs only the statement there ...
  await run(page, editor, 'SELECT 1 AS first; SELECT 2 AS second');
  await expect(results.getByRole('columnheader', { name: /second/ })).toBeVisible();
  // ... but a selection is sent as it is, and the server's gate refuses two statements.
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Control+Enter');
  await expect(results.getByRole('alert')).toContainText('found 2');

  expect(await problems()).toEqual([]);
});

test('a long statement shows a timer and can be cancelled', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const results = page.getByRole('region', { name: 'Results' });
  await run(page, editor, 'SELECT pg_sleep(15)');
  await expect(results.getByText(/Running… \d+\.\d s/)).toBeVisible();
  await results.getByRole('button', { name: 'Cancel' }).click();
  await expect(results.getByRole('alert')).toContainText('Cancelled');
  await expect(results.getByRole('alert')).toContainText('PostgreSQL stopped the statement.');
  await run(page, editor, 'SELECT 1 AS still_usable');
  await expect(results.getByRole('columnheader', { name: /still_usable/ })).toBeVisible();
  expect(await problems()).toEqual([]);
});

test('manual transactions roll back and commit; destructive statements ask first', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const results = page.getByRole('region', { name: 'Results' });
  const id = 100000 + Math.floor(rng('tx')() * 100000);

  await page.getByRole('checkbox', { name: 'Manual commit' }).check();
  await page.getByRole('combobox', { name: 'Isolation' }).selectOption('repeatable read');
  await run(page, editor, `INSERT INTO items VALUES (${id}, 'pending')`);
  await expect(page.getByLabel('Transaction')).toContainText('Transaction open');
  await expect(page.getByLabel('Transaction')).toContainText('repeatable read');
  await page.getByRole('button', { name: 'Rollback' }).click();
  await expect(page.getByLabel('Transaction')).toContainText('No transaction');
  await run(page, editor, `SELECT count(*) AS n FROM items WHERE id = ${id}`);
  await expect(results.locator('td').nth(1)).toHaveText('0');
  // That SELECT began a new transaction (manual mode); commit it, then insert and commit.
  await page.getByRole('button', { name: 'Commit' }).click();
  await run(page, editor, `INSERT INTO items VALUES (${id}, 'kept')`);
  await page.getByRole('button', { name: 'Commit' }).click();
  await expect(page.getByLabel('Transaction')).toContainText('No transaction');
  await page.getByRole('checkbox', { name: 'Manual commit' }).uncheck();

  await run(page, editor, `DELETE FROM items WHERE id = ${id}`); // has WHERE: no question
  await expect(results.getByRole('status')).toHaveText('Completed. 1 row affected.');
  await run(page, editor, 'DELETE FROM items');
  const dialog = page.getByRole('dialog', { name: 'Run this statement?' });
  await expect(dialog).toContainText('without a WHERE clause');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await run(page, editor, 'SELECT count(*) FROM items');
  await expect(results.locator('td').nth(1)).toHaveText('2500');
  expect(await problems()).toEqual([]);
});

test('the object browser shows my tables and inserts a name into the editor', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const objects = page.getByRole('region', { name: 'Objects' });
  await objects.getByRole('button', { name: 'public', exact: true }).click();
  await objects.getByRole('button', { name: 'items', exact: true }).click();
  await expect(objects.getByText('Columns')).toBeVisible();
  await expect(objects.getByText('PRIMARY KEY (id)')).toBeVisible();
  await editor.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Delete');
  await page.keyboard.insertText('SELECT count(*) AS n FROM ');
  await objects.getByRole('button', { name: 'Insert public.items' }).click();
  await expect(editor).toContainText('SELECT count(*) AS n FROM public.items');
  await page.keyboard.press('Control+Enter');
  const results = page.getByRole('region', { name: 'Results' });
  await expect(results.locator('td').nth(1)).toHaveText('2500');
  expect(await problems()).toEqual([]);
});

test('admin Phase 2: a script stops at its first error, a large result is paged, and the history keeps both', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const results = page.getByRole('region', { name: 'Results' });

  // Run script: every statement in order; one confirmation for the DDL; the third fails and the fourth never runs.
  await editor.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Delete');
  await page.keyboard.insertText("CREATE TEMP TABLE t_script AS SELECT 7 AS a;\nSELECT a FROM t_script;\nSELECT 1 / 0;\nSELECT 'never'");
  await page.getByRole('button', { name: 'Run script' }).click();
  await page.getByRole('dialog', { name: 'Run this script?' }).getByRole('button', { name: 'Run the script' }).click();
  await expect(results.getByText(/Stopped at statement 3 of 4: it failed. 2 completed before it/)).toBeVisible();
  const statements = results.getByRole('list', { name: 'Script statements' }).getByRole('listitem');
  await expect(statements).toHaveCount(4);
  await expect(statements.nth(1).getByRole('cell', { name: '7', exact: true })).toBeVisible();
  await expect(statements.nth(2)).toContainText('22012');
  await expect(statements.nth(3)).toContainText('Not run');
  await statements.nth(2).getByRole('button', { name: 'Show statement 3 in the editor' }).click();
  await expect(editor).toBeFocused();

  // Paging: in auto-commit the result stops at one page; a read-only transaction lets the rest be loaded.
  await run(page, editor, 'SELECT id FROM items ORDER BY id');
  await expect(results.getByText(/Only the first 1,000 rows are shown/)).toBeVisible();
  await results.getByRole('button', { name: 'Run again in a read-only transaction to load more' }).click();
  await expect(results.getByText(/1,000 rows loaded; more rows are waiting on the server/)).toBeVisible();
  await results.getByRole('button', { name: 'Load more rows' }).click();
  await expect(results.getByText(/2,000 rows loaded/)).toBeVisible();
  await results.getByRole('button', { name: 'Load more rows' }).click();
  await expect(results.getByText('2,500 rows', { exact: true })).toBeVisible();
  await expect(results.getByRole('button', { name: 'Load more rows' })).toHaveCount(0);
  await expect(page.getByLabel('Transaction')).toContainText('Transaction open');
  await page.getByRole('button', { name: 'Rollback' }).click();
  await expect(page.getByLabel('Transaction')).toContainText('No transaction');

  // History: this tab's runs, newest first; a script can be reopened in its own editor tab.
  await page.getByRole('tab', { name: /^History/ }).click();
  const history = page.getByRole('region', { name: 'Query history' }).getByRole('listitem');
  await expect(history.first()).toContainText('SELECT id FROM items ORDER BY id');
  const script = history.filter({ hasText: 'CREATE TEMP TABLE t_script' });
  await expect(script).toContainText('Script (4)');
  await script.getByRole('button', { name: 'Open in a new tab' }).click();
  await expect(page.getByRole('button', { name: 'Query 2', exact: true })).toHaveAttribute('aria-current', 'true');
  await expect(page.getByRole('textbox', { name: 'SQL editor' })).toContainText("SELECT 'never'");
  expect(await problems()).toEqual([]);
});

test('the workspace fits 1366x768 and 1920x1080 without page-level horizontal scrolling', async ({ page }) => {
  for (const size of [{ width: 1366, height: 768 }, { width: 1920, height: 1080 }]) {
    await page.setViewportSize(size);
    await connect(page);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${size.width}x${size.height}`).toBeLessThanOrEqual(0);
    await page.getByRole('button', { name: 'Disconnect' }).click();
  }
});

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

test('admin Phase 3: explain a query, open a definition, and read the activity, database and roles', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const results = page.getByRole('region', { name: 'Results' });

  // Explain: the plan as a tree, and the statement is not run.
  await editor.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Delete');
  await page.keyboard.insertText('SELECT name FROM items WHERE id = 42');
  await page.getByRole('button', { name: 'Explain', exact: true }).click();
  await expect(results.getByText(/Estimated plan \(EXPLAIN\): the statement was not run/)).toBeVisible();
  await expect(results.getByRole('list', { name: 'Query plan' })).toContainText('on items');

  // Explain analyze asks first, because ANALYZE runs the statement.
  await page.getByRole('button', { name: 'Explain analyze', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Run this statement?' });
  await expect(dialog).toContainText('runs the statement to measure it');
  await dialog.getByRole('button', { name: 'Run it' }).click();
  await expect(results.getByText(/Plan with actual times \(EXPLAIN ANALYZE\)/)).toBeVisible();
  await expect(results.getByRole('list', { name: 'Query plan' })).toContainText('rows ×');

  // A table's definition opens in its own editor tab; nothing runs.
  const objects = page.getByRole('region', { name: 'Objects' });
  await objects.getByRole('button', { name: 'public', exact: true }).click();
  await objects.getByRole('button', { name: 'Open definition of public.items' }).click();
  await expect(page.getByRole('button', { name: 'items.sql', exact: true })).toHaveAttribute('aria-current', 'true');
  await expect(page.getByRole('textbox', { name: 'SQL editor' })).toContainText('CREATE TABLE public.items (');
  await expect(page.getByRole('textbox', { name: 'SQL editor' })).toContainText('CONSTRAINT items_pkey PRIMARY KEY (id)');

  // Read-only administration views.
  await page.getByRole('tab', { name: 'Activity', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Server activity' }).getByText('this session')).toBeVisible();
  await page.getByRole('tab', { name: 'Database', exact: true }).click();
  const database = page.getByRole('region', { name: 'Database', exact: true });
  await expect(database.getByText(role, { exact: true }).first()).toBeVisible();
  await expect(database.getByRole('table', { name: /Largest tables you can use/ })).toContainText('public.items');
  await page.getByRole('tab', { name: 'Roles', exact: true }).click();
  const roles = page.getByRole('region', { name: 'Roles', exact: true });
  await expect(roles.getByRole('row').filter({ hasText: role }).getByText('you', { exact: true })).toBeVisible();
  expect(await problems()).toEqual([]);
});

test("admin Phase 4: cancel another session's query and end that session, after confirming", async ({ page, browser }) => {
  const problems = await watch(page);
  await connect(page);
  // A second sign-in (another browser context) of the same Windows user runs a long statement.
  const other = await browser.newContext();
  const busy = await other.newPage();
  try {
    const busyEditor = await connect(busy);
    const busyResults = busy.getByRole('region', { name: 'Results' });
    await run(busy, busyEditor, 'SELECT pg_sleep(60) AS long_wait');
    await expect(busyResults.getByText(/Running… \d+\.\d s/)).toBeVisible();

    await page.getByRole('tab', { name: 'Activity', exact: true }).click();
    const activity = page.getByRole('region', { name: 'Server activity' });
    const row = activity.getByRole('row').filter({ hasText: 'pg_sleep(60) AS long_wait' });
    await expect(async () => {
      await activity.getByRole('button', { name: 'Refresh' }).click();
      await expect(row.getByRole('button', { name: /^Cancel query of session \d+$/ })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 20_000 });

    // Cancelling asks first; Cancel in the dialog sends nothing.
    await row.getByRole('button', { name: /^Cancel query of session/ }).click();
    const ask = page.getByRole('dialog', { name: /^Cancel the running query of session \d+\?$/ });
    await expect(ask).toContainText('audit log');
    await ask.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(busyResults.getByText(/Running… \d+\.\d s/)).toBeVisible();
    await row.getByRole('button', { name: /^Cancel query of session/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel query', exact: true }).click();
    await expect(activity.getByText(/Cancel request sent to session/)).toBeVisible(); // by text: the loading spinner is a status too
    await expect(busyResults.getByRole('alert')).toContainText(/cancel/i);

    // Ending the session: the other sign-in is told its session ended and can reconnect.
    await row.getByRole('button', { name: /^End session \d+$/ }).click();
    await page.getByRole('dialog', { name: /^End session \d+\?$/ }).getByRole('button', { name: 'End session', exact: true }).click();
    await expect(activity.getByText(/was told to end/)).toBeVisible();
    await run(busy, busyEditor, 'SELECT 1');
    await expect(busy.getByRole('button', { name: 'Reconnect to e2e_db' })).toBeVisible({ timeout: 45_000 });
  } finally {
    const signOut = busy.getByRole('button', { name: 'Sign out' });
    if ((await signOut.count()) > 0) await signOut.click();
    await other.close();
  }
  expect(await problems()).toEqual([]);
});

test('admin Phase 5: manage a table, a database and a role through generated SQL', async ({ page }) => {
  const problems = await watch(page);
  await connect(page);
  const results = page.getByRole('region', { name: 'Results' });
  const tag = `${Date.now().toString(36)}${Math.floor(rng('manage')() * 1e6).toString(36)}`;
  const manage = page.getByRole('region', { name: 'Manage' });
  const generate = async (action, fields) => {
    await page.getByRole('tab', { name: 'Manage', exact: true }).click();
    await manage.getByLabel('What to do').selectOption(action);
    for (const [label, value] of fields) {
      const field = manage.getByLabel(label, { exact: true });
      if (value === true) await field.check();
      else await field.fill(value);
    }
    await manage.getByRole('button', { name: 'Open in editor' }).click();
    last = action;
    await expect(page.getByRole('button', { name: `${action}.sql`, exact: true }).last()).toHaveAttribute('aria-current', 'true');
  };
  let last = null;
  const runScriptConfirmed = async () => {
    await page.getByRole('button', { name: 'Run script', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Run this script?' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Run the script' }).click();
    // The result of this tab's script (the toolbar names the tab it came from), not the previous one.
    await expect(results.getByText(`${last}.sql · Retrieved at`)).toBeVisible();
    await expect(results.getByText(/All \d+ statements? completed\./)).toBeVisible();
  };

  // A table with a key, a NOT NULL column with a default, and a quoted name.
  const table = `Managed ${tag}`;
  await generate('create-table', [['Schema', 'public'], ['Name', table], ['Column 1 name', 'id'], ['Column 1 type', 'bigint'], ['Column 1 default', '']]);
  await expect(page.getByRole('textbox', { name: 'SQL editor' })).toContainText(`CREATE TABLE public."${table}"`);
  await runScriptConfirmed();
  await generate('add-column', [['Schema', 'public'], ['Table', table], ['Column name', 'label'], ['Type', 'text'], ['NOT NULL', true], ['Default (SQL expression, optional)', "'none'"]]);
  await runScriptConfirmed();
  await generate('add-constraint', [['Schema', 'public'], ['Table', table], ['Columns', 'id']]);
  await runScriptConfirmed();
  await generate('create-index', [['Schema', 'public'], ['Table', table], ['Columns', 'label']]);
  await runScriptConfirmed();
  await run(page, page.getByRole('textbox', { name: 'SQL editor' }), `INSERT INTO public."${table}" (id) VALUES (1) RETURNING label`);
  await expect(results.locator('td', { hasText: 'none' })).toBeVisible();

  // A database of the role's own (the test role has CREATEDB); the connected one is refused in the form.
  await page.getByRole('tab', { name: 'Manage', exact: true }).click();
  await manage.getByLabel('What to do').selectOption('drop-database');
  await manage.getByLabel('Database', { exact: true }).fill('e2e_db');
  await expect(manage.getByRole('status')).toContainText('connected to');
  const db = `managed_db_${tag}`;
  await generate('create-database', [['Name', db]]);
  await runScriptConfirmed();
  await generate('drop-database', [['Database', db]]);
  await runScriptConfirmed();

  // A role (the test role has CREATEROLE), then clean up.
  const r = `managed_role_${tag}`;
  await generate('create-role', [['Name', r]]);
  await runScriptConfirmed();
  await generate('grant-table', [['Schema', 'public'], ['Name', table], ['SELECT', true], ['To role (or PUBLIC)', r]]);
  await runScriptConfirmed();
  await generate('revoke-table', [['Schema', 'public'], ['Name', table], ['SELECT', true], ['From role (or PUBLIC)', r]]);
  await runScriptConfirmed();
  await generate('drop-role', [['Role', r]]);
  await runScriptConfirmed();
  await generate('drop-relation', [['Schema', 'public'], ['Name', table]]);
  await runScriptConfirmed();
  expect(await problems()).toEqual([]);
});

test('admin Phase 6: browse a table, then change, add and delete rows in reviewed transactions', async ({ page }) => {
  const problems = await watch(page);
  const editor = await connect(page);
  const results = page.getByRole('region', { name: 'Results' });
  const id = -Math.floor(1 + rng('data')() * 1e9) - (Date.now() % 1000);
  const label = `edited ${Math.abs(id).toString(36)}`;
  // Start from the seeded rows, even if an earlier run stopped half-way (the cluster is shared by all repetitions).
  await run(page, editor, 'DELETE FROM items WHERE id < 0');
  await expect(results.getByText(/^Completed\./)).toBeVisible();
  await run(page, editor, "UPDATE items SET name = 'item 1' WHERE id = 1");
  await expect(results.getByText('Completed. 1 row affected.')).toBeVisible();
  const objects = page.getByRole('region', { name: 'Objects' });
  await objects.getByRole('button', { name: 'public', exact: true }).click();
  await objects.getByRole('button', { name: 'View data of public.items' }).click();
  const data = page.getByRole('region', { name: 'Table data' });
  await expect(data.getByText('item 1', { exact: true })).toBeVisible();
  const save = async (summary) => {
    await data.getByRole('button', { name: 'Review and save' }).click();
    const dialog = page.getByRole('dialog', { name: 'Save these changes?' });
    await expect(dialog.getByLabel('Statements to run')).toBeVisible();
    await dialog.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(data.getByText(summary)).toBeVisible(); // by text: the loading spinner is a status too
  };

  // Change row 1 (id 1).
  await data.getByRole('button', { name: 'Edit row 1', exact: true }).click();
  const form = data.getByRole('group', { name: 'Row with id = 1' });
  await form.getByLabel(/^name/).fill(label);
  await form.getByRole('button', { name: 'Keep this change' }).click();
  await save('Saved: 1 changed, 0 added, 0 deleted.');
  await expect(data.getByText(label, { exact: true })).toBeVisible();

  // Add a row with a negative key (it sorts first).
  await data.getByRole('button', { name: 'Add row' }).click();
  const add = data.getByRole('group', { name: 'New row' });
  await add.getByRole('checkbox', { name: 'Default' }).first().uncheck();
  await add.getByLabel(/^id/).fill(String(id));
  await add.getByRole('checkbox', { name: 'Default' }).last().uncheck();
  await add.getByLabel(/^name/).fill("O'Brien \\ new");
  await add.getByRole('button', { name: 'Keep this change' }).click();
  await save('Saved: 0 changed, 1 added, 0 deleted.');
  await expect(data.getByText("O'Brien \\ new", { exact: true })).toBeVisible();

  // In one save: delete the new row and put row id 1 back as it was.
  await data.getByRole('button', { name: 'Delete row 1', exact: true }).click();
  await data.getByRole('button', { name: 'Edit row 2', exact: true }).click();
  await data.getByRole('group', { name: 'Row with id = 1' }).getByLabel(/^name/).fill('item 1');
  await data.getByRole('group', { name: 'Row with id = 1' }).getByRole('button', { name: 'Keep this change' }).click();
  await save('Saved: 1 changed, 0 added, 1 deleted.');

  // The database agrees.
  await run(page, editor, `SELECT count(*) AS n FROM items WHERE id = ${id} OR name = '${label}'`);
  await expect(results.locator('td', { hasText: /^0$/ })).toBeVisible();
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

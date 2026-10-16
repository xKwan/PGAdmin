// Phase 11 journeys in a real browser: DataAdmin and non-DataAdmin, unauthenticated access, session expiry, audit
// outage, markup in object names, and no requests to other origins. Environment from scripts/test/Invoke-E2E.ps1.
import { execFileSync } from 'node:child_process';
import { expect, request, test } from '@playwright/test';

const admin = process.env.E2E_BASE_URL;
const noAdmin = process.env.E2E_BASE_URL_NOADMIN;
const role = process.env.E2E_ROLE;

/** Runs SQL as the throwaway cluster's superuser (only to simulate an audit outage). */
function superSql(sql) {
  execFileSync(process.env.E2E_PSQL, ['-X', '-q', '-h', 'localhost', '-p', process.env.E2E_PG_PORT, '-U', 'pgweb_e2e_super', '-d', 'postgres', '-c', sql], {
    env: { ...process.env, PGPASSWORD: process.env.E2E_PG_SUPER_PASSWORD },
    stdio: 'ignore',
  });
}

function guard(page, base) {
  const problems = [];
  const origin = new URL(base).origin;
  page.on('request', (r) => {
    if (!r.url().startsWith(origin) && !r.url().startsWith('data:')) problems.push(`request to another origin: ${r.url()}`);
  });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  page.on('dialog', (d) => {
    problems.push(`dialog: ${d.message()}`);
    d.dismiss();
  });
  return problems;
}

async function signOut(page) {
  const b = page.getByRole('button', { name: 'Sign out' });
  if ((await b.count()) > 0) await b.click();
}

test('a DataAdmin sees both servers and can use the internal one, with its banner', async ({ page }) => {
  const problems = guard(page, admin);
  await page.goto(`${admin}/`);
  await expect(page.getByText('DataAdmin', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /External PostgreSQL/ })).toBeVisible();
  await page.getByRole('button', { name: /Internal PostgreSQL/ }).click();
  await expect(page.getByRole('note').filter({ hasText: 'INTERNAL' })).toBeVisible();
  await page.getByRole('button', { name: 'Show my databases' }).click();
  await page.getByRole('button', { name: /e2e_db/ }).click();
  await expect(page.getByLabel('Database session')).toContainText(`as ${role}`);
  await signOut(page);
  expect(problems).toEqual([]);
});

test('someone who is not DataAdmin sees only the external server and is refused the internal API', async ({ page }) => {
  const problems = guard(page, noAdmin);
  await page.goto(`${noAdmin}/`);
  await expect(page.getByRole('button', { name: /External PostgreSQL/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Internal PostgreSQL/ })).toHaveCount(0);
  await expect(page.getByText('DataAdmin', { exact: true })).toHaveCount(0);
  // Calling the internal API directly (as a script on the page could): 403, with the page's own CSRF token.
  const status = await page.evaluate(async () => {
    const me = await (await fetch('/api/me')).json();
    const r = await fetch('/api/db-sessions/internal', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': me.csrfToken },
      body: JSON.stringify({ database: 'e2e_db' }),
    });
    return r.status;
  });
  expect(status).toBe(403);
  await signOut(page);
  expect(problems).toEqual([]);
});

test('without Windows sign-in nothing is served: a Negotiate challenge, no data, no server details', async () => {
  const anonymous = await request.newContext({ baseURL: admin }); // Playwright's API client does no Negotiate
  for (const path of ['/', '/api/me', '/api/profiles', '/api/db-sessions']) {
    const r = await anonymous.get(path);
    expect(r.status(), path).toBe(401);
    expect(r.headers()['www-authenticate'], path).toMatch(/Negotiate/);
    const body = await r.text();
    expect(body, path).not.toMatch(/Tomcat|Apache|Exception|at pgweb\./);
  }
  const post = await anonymous.post('/api/db-sessions/external', { data: { database: 'e2e_db' } });
  expect(post.status()).toBe(401);
  await anonymous.dispose();
});

test('an expired app session signs in again by itself', async ({ page }) => {
  test.setTimeout(150_000);
  const problems = guard(page, noAdmin);
  await page.goto(`${noAdmin}/`);
  await page.getByRole('button', { name: /External PostgreSQL/ }).click();
  await expect(page.getByRole('button', { name: 'Show my databases' })).toBeVisible();
  await page.waitForTimeout(65_000); // idle timeout of that instance: 1 minute
  await page.getByRole('button', { name: 'Show my databases' }).click();
  // 401 → one reload with ?reauth=1 → Windows sign-in again → the landing page, in a fresh app session.
  await expect(page.getByRole('heading', { name: 'Choose a database server' })).toBeVisible({ timeout: 30_000 });
  await expect(page).not.toHaveURL(/reauth/);
  await signOut(page);
  expect(problems).toEqual([]);
});

// The approved audit policy (docs/audit-policy.md): fail-closed for execution; signing in and connecting go on, with
// their records spooled and replayed.
test('an audit outage refuses execution but not sign-in, and then recovers', async ({ page, browser }) => {
  const problems = guard(page, admin);
  await page.goto(`${admin}/`);
  await page.getByRole('button', { name: /External PostgreSQL/ }).click();
  await page.getByRole('button', { name: 'Show my databases' }).click();
  await page.getByRole('button', { name: /e2e_db/ }).click();
  const editor = page.getByRole('textbox', { name: 'SQL editor' });
  await editor.click();
  await page.keyboard.insertText('SELECT 1 AS during_outage');
  superSql("ALTER ROLE pgweb_app NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'pgweb_app';");
  const results = page.getByRole('region', { name: 'Results' });
  // A second browser (fresh cookies) can still sign in: the login record is spooled.
  const other = await browser.newContext();
  const fresh = await other.newPage();
  try {
    await page.keyboard.press('Control+Enter');
    await expect(results.getByRole('alert')).toContainText('the audit database is unavailable');
    await fresh.goto(`${admin}/`);
    await expect(fresh.getByRole('heading', { name: 'Choose a database server' })).toBeVisible();
  } finally {
    superSql('ALTER ROLE pgweb_app LOGIN;');
  }
  await signOut(fresh);
  await other.close();
  // The first page's session goes on: the statement runs once the audit database is back.
  await expect(async () => {
    await editor.click();
    await page.keyboard.press('Control+Enter');
    await expect(results.getByRole('columnheader', { name: /during_outage/ })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  await signOut(page);
  expect(problems).toEqual([]);
});

test('an object named with markup is shown as text', async ({ page }) => {
  const problems = guard(page, admin);
  await page.goto(`${admin}/`);
  await page.getByRole('button', { name: /External PostgreSQL/ }).click();
  await page.getByRole('button', { name: 'Show my databases' }).click();
  await page.getByRole('button', { name: /e2e_db/ }).click();
  const objects = page.getByRole('region', { name: 'Objects' });
  await objects.getByRole('button', { name: 'public', exact: true }).click();
  await expect(objects.getByText('<img src=x onerror=alert(1)>', { exact: true })).toBeVisible();
  await expect(objects.locator('img')).toHaveCount(0);
  await objects.getByRole('button', { name: '<img src=x onerror=alert(1)>', exact: true }).click();
  await expect(objects.getByText('Columns')).toBeVisible();
  await signOut(page);
  expect(problems).toEqual([]);
});

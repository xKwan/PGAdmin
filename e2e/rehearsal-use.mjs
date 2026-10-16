// Deployment rehearsal helper (scripts/test/Invoke-DeploymentRehearsal.ps1): in the installed Edge, signs in with this
// Windows account, opens a DB session (the browser answers the fresh Negotiate challenge, as for a user) and runs
// the given statements. Prints {session, results} as JSON. Usage: node rehearsal-use.mjs <baseUrl> <database> <sql>...
import { chromium } from '@playwright/test';

const [base, database, ...statements] = process.argv.slice(2);
const browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--auth-server-allowlist=localhost'] });
try {
  const page = await browser.newPage();
  await page.goto(`${base}/`); // the SPA page: its CSP allows same-origin fetch (the API answers with default-src none)
  const out = await page.evaluate(
    async ({ database, statements }) => {
      const me = await (await fetch('/api/me')).json();
      const post = async (path, body) => {
        const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': me.csrfToken }, body: JSON.stringify(body) });
        const text = await r.text();
        if (!r.ok) throw new Error(`${path} -> ${r.status}: ${text}`);
        return JSON.parse(text);
      };
      const session = await post('/api/db-sessions/external', { database });
      const results = [];
      for (const sql of statements) results.push(await post(`/api/db-sessions/${session.id}/execute`, { sql, confirmed: true }));
      return { session: session.id, results };
    },
    { database, statements },
  );
  process.stdout.write(JSON.stringify(out));
} finally {
  await browser.close();
}

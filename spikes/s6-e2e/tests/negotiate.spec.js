// Spike S6 (partial): does the installed Edge, driven by Playwright, perform Windows integrated authentication
// (Negotiate/NTLM through SSPI) against the S1 Waffle service, and does the allowlist policy control it?
// Environment (set by Invoke-S6Spike.ps1): S6_PORT, S6_EXPECT_SID, S6_SEED.
import { test, expect, chromium } from '@playwright/test';

const port = Number(process.env.S6_PORT);
const expectSid = process.env.S6_EXPECT_SID;
const seed = Number(process.env.S6_SEED || '1');

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

function note(type, description) {
  test.info().annotations.push({ type, description: String(description) });
}

async function open(args) {
  const browser = await chromium.launch({ channel: 'msedge', headless: true, args });
  note('edge-version', browser.version());
  return browser;
}

function whoamiPath(r) {
  return r() < 0.5 ? '/whoami' : `/whoami?r=${Math.floor(r() * 1e6)}`;
}

test('allowlisted host: Edge authenticates automatically with the Windows account', async () => {
  const r = rng('allowlisted');
  const browser = await open(['--auth-server-allowlist=localhost']);
  try {
    const page = await browser.newPage();
    const res = await page.goto(`http://localhost:${port}${whoamiPath(r)}`);
    expect(res.status()).toBe(200);
    const body = JSON.parse(await page.locator('body').innerText());
    expect(body.sid).toBe(expectSid);
    expect(body.groupSids).toContain('S-1-5-32-545');
    note('result', `200 as ${body.sid === expectSid ? 'expected SID' : 'OTHER SID'}`);
  } finally {
    await browser.close();
  }
});

// Without an allowlist match Edge does not send the Windows logon. It asks for explicit credentials instead, which a
// headless browser cannot answer, so navigation fails with ERR_INVALID_AUTH_CREDENTIALS (observed: after about 30 s).
async function expectNoAutomaticLogon(browser, url) {
  const page = await browser.newPage();
  await expect(page.goto(url, { timeout: 50_000 })).rejects.toThrow(/ERR_INVALID_AUTH_CREDENTIALS/);
}

test('host outside the allowlist: Edge does not send Windows credentials', async () => {
  const browser = await open(['--auth-server-allowlist=intranet.invalid']);
  try {
    await expectNoAutomaticLogon(browser, `http://localhost:${port}/whoami`);
    note('result', 'no automatic logon (explicit credentials requested)');
  } finally {
    await browser.close();
  }
});

test('DataAdmin endpoint through the browser', async () => {
  const browser = await open(['--auth-server-allowlist=localhost']);
  try {
    const page = await browser.newPage();
    const res = await page.goto(`http://localhost:${port}/admin/ping`);
    expect(res.status()).toBe(200);
    note('result', '200');
  } finally {
    await browser.close();
  }
});

test('several pages at once all authenticate', async () => {
  const r = rng('parallel');
  const n = 2 + Math.floor(r() * 3);
  const browser = await open(['--auth-server-allowlist=localhost']);
  try {
    const context = await browser.newContext();
    const pages = await Promise.all(Array.from({ length: n }, () => context.newPage()));
    const results = await Promise.all(pages.map((p) => p.goto(`http://localhost:${port}${whoamiPath(r)}`)));
    for (const res of results) expect(res.status()).toBe(200);
    const sids = await Promise.all(pages.map(async (p) => JSON.parse(await p.locator('body').innerText()).sid));
    for (const sid of sids) expect(sid).toBe(expectSid);
    note('result', `${n} pages, all 200`);
  } finally {
    await browser.close();
  }
});

// Observation only: depends on machine policy (intranet zone, GPO), so it is recorded, not asserted.
test('observe: default policy (no allowlist switch)', async () => {
  const browser = await open([]);
  try {
    const page = await browser.newPage();
    const res = await page.goto(`http://localhost:${port}/whoami`);
    expect([200, 401]).toContain(res.status());
    note('observed', `localhost without allowlist -> ${res.status()}`);
  } finally {
    await browser.close();
  }
});

// The allowlist matches host names exactly: the same server reached as 127.0.0.1 is not covered by "localhost".
test('allowlist is per host name: 127.0.0.1 is not covered by "localhost"', async () => {
  const browser = await open(['--auth-server-allowlist=localhost']);
  try {
    await expectNoAutomaticLogon(browser, `http://127.0.0.1:${port}/whoami`);
    note('result', '127.0.0.1 with allowlist=localhost: no automatic logon');
  } finally {
    await browser.close();
  }
});

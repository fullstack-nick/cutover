import { chromium } from '../../apps/operations-console/node_modules/playwright/index.mjs';
import { credentials } from './client.mjs';

/** Real local Authorization Code/PKCE login. Credentials and observed tokens stay in memory. */
export async function humanSession(username, { offline = false, onRequest = () => {} } = {}) {
  const keys = { 'operator-a': 'operator_a', 'operator-b': 'operator_b', 'supervisor-a': 'supervisor_a', 'supervisor-a2': 'supervisor_a2', 'platform-admin': 'platform_admin' };
  if (!Object.hasOwn(keys, username)) throw new Error('Only seeded fictional lab users can be used in this check.');
  const browser = await chromium.launch({ headless: true });
  let phase='prepare browser';
  try {
    const context = await browser.newContext({ serviceWorkers: 'block' });
    if (offline) await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      const allowed = url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname) && ['8780', '8781', '8782', '8783'].includes(url.port);
      // Record only route metadata, never token-bearing query strings, headers or bodies.
      onRequest({ origin: url.origin, path: url.pathname, method: route.request().method(), allowed });
      if (allowed) await route.continue(); else await route.abort('internetdisconnected');
    });
    const page = await context.newPage();
    let authorization, identityToken;
    page.on('response', async response => {
      if (response.url() === 'http://localhost:8780/identity/realms/cutover/protocol/openid-connect/token' && response.ok()) {
        try { const tokens = await response.json(); if (typeof tokens.id_token === 'string') identityToken = tokens.id_token; } catch { /* A closed page cannot provide additional token evidence. */ }
      }
    });
    page.on('request', request => {
      if (request.url().startsWith('http://localhost:8780/api/v1/sites/')) {
        const value = request.headers().authorization;
        if (value?.startsWith('Bearer ')) authorization = value.slice(7);
      }
    });
    phase='open console';await page.goto('http://localhost:8780/');
    phase='open identity';
    await page.getByRole('button', { name: 'Sign in to operations', exact: true }).click();
    phase='enter username';await page.getByRole('textbox', { name: 'Username or email', exact: true }).fill(username);
    phase='enter password';
    await page.getByRole('textbox', { name: 'Password', exact: true }).fill(credentials.passwords[keys[username]]);
    phase='submit login';await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    phase='complete profile';
    // A realm initially seeded before the profile fields were supplied may request them once.
    if (page.url().includes('execution=VERIFY_PROFILE')) {
      await page.getByRole('textbox', { name: 'Email', exact: true }).fill(`${username}@cutover.invalid`);
      await page.getByRole('textbox', { name: 'First name', exact: true }).fill('Local');
      await page.getByRole('textbox', { name: 'Last name', exact: true }).fill(username);
      await page.getByRole('button', { name: 'Submit', exact: true }).click();
    }
    phase='observe signed-in console';await page.getByRole('button', { name: 'Sign out', exact: true }).waitFor({ state: 'visible', timeout: 20000 });
    phase='observe API authorization';
    if (!authorization) await page.waitForRequest(request => request.url().startsWith('http://localhost:8780/api/v1/sites/') && request.headers().authorization?.startsWith('Bearer '), { timeout: 15000 });
    return { page, bearer: () => authorization, idToken: () => identityToken, close: () => browser.close() };
  } catch {
    await browser.close();
    // Playwright call logs from a failed fill can include its argument; do not expose that error.
    throw new Error(`The local PKCE login for ${username} did not complete (${phase}).`);
  }
}

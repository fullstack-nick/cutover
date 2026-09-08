import { chromium } from '../../apps/operations-console/node_modules/playwright/index.mjs';
import { credentials } from './client.mjs';

/** Real local Authorization Code/PKCE login. Credentials and observed tokens stay in memory. */
export async function humanSession(username) {
  const keys = { 'operator-a': 'operator_a', 'operator-b': 'operator_b', 'supervisor-a': 'supervisor_a', 'supervisor-a2': 'supervisor_a2', 'platform-admin': 'platform_admin' };
  if (!Object.hasOwn(keys, username)) throw new Error('Only seeded fictional lab users can be used in this check.');
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    let authorization;
    page.on('request', request => {
      if (request.url().startsWith('http://localhost:8780/api/v1/sites/')) {
        const value = request.headers().authorization;
        if (value?.startsWith('Bearer ')) authorization = value.slice(7);
      }
    });
    await page.goto('http://localhost:8780/');
    await page.getByRole('button', { name: 'Sign in to operations', exact: true }).click();
    await page.getByRole('textbox', { name: 'Username or email', exact: true }).fill(username);
    await page.getByRole('textbox', { name: 'Password', exact: true }).fill(credentials.passwords[keys[username]]);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    // A realm initially seeded before the profile fields were supplied may request them once.
    if (page.url().includes('execution=VERIFY_PROFILE')) {
      await page.getByRole('textbox', { name: 'Email', exact: true }).fill(`${username}@cutover.invalid`);
      await page.getByRole('textbox', { name: 'First name', exact: true }).fill('Local');
      await page.getByRole('textbox', { name: 'Last name', exact: true }).fill(username);
      await page.getByRole('button', { name: 'Submit', exact: true }).click();
    }
    await page.getByRole('button', { name: 'Sign out', exact: true }).waitFor({ state: 'visible', timeout: 20000 });
    if (!authorization) await page.waitForRequest(request => request.url().startsWith('http://localhost:8780/api/v1/sites/') && request.headers().authorization?.startsWith('Bearer '), { timeout: 15000 });
    return { page, bearer: () => authorization, close: () => browser.close() };
  } catch {
    await browser.close();
    // Playwright call logs from a failed fill can include its argument; do not expose that error.
    throw new Error(`The local PKCE login for ${username} did not complete.`);
  }
}

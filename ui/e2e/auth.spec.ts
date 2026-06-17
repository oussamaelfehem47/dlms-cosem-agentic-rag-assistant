/**
 * Suite 1 - Authentication Flows
 *
 * All tests in this file use the real backend (no mocking).
 * The app uses state-based routing: the URL stays at http://localhost:5173
 * regardless of login state. "Redirect to login" means the login form appears.
 *
 * IMPORTANT: Tests 1.1-1.3 and 1.5-1.7 need fresh, unauthenticated contexts.
 * Test 1.4 requires an already-authenticated session (uses storageState).
 * Each test therefore creates its own browser context to control auth state
 * precisely - the default `page` fixture is NOT used to avoid storageState leakage.
 */

import { test, expect } from '@playwright/test';
import { adminAuthFile } from './helpers/paths';

const BASE_URL = 'http://localhost:5173';

const sel = {
  identifier: 'input[placeholder="you@company.com"]',
  password: 'input[type="password"]',
  submit: 'button[type="submit"]',
  chatInput: '[data-testid="chat-input"]',
};

test('1.1 Login with valid admin credentials', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await page.goto(BASE_URL);
  await page.locator(sel.identifier).waitFor({ timeout: 10_000 });
  await page.locator(sel.identifier).fill('admin');
  await page.locator(sel.password).fill('admin123');
  await page.locator(sel.submit).click();

  await expect(page.locator(sel.chatInput)).toBeVisible({ timeout: 20_000 });
  await expect(page.locator(sel.submit)).not.toBeVisible();

  await ctx.close();
});

test('1.2 Login with wrong password shows error', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await page.goto(BASE_URL);
  await page.locator(sel.identifier).waitFor({ timeout: 10_000 });
  await page.locator(sel.identifier).fill('admin');
  await page.locator(sel.password).fill('WRONG_PASSWORD_XYZ');
  await page.locator(sel.submit).click();

  const errorDiv = page.locator('text=/invalid|incorrect|wrong|unauthorized|failed|password/i').first();
  await expect(errorDiv).toBeVisible({ timeout: 10_000 });

  await expect(page.locator(sel.submit)).toBeVisible();
  await expect(page.locator(sel.chatInput)).not.toBeVisible();

  await ctx.close();
});

test('1.3 Login with empty fields shows validation error', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await page.goto(BASE_URL);
  await page.locator(sel.identifier).waitFor({ timeout: 10_000 });
  await page.locator(sel.submit).click();

  await expect(page.locator('text=/required/i').first()).toBeVisible({ timeout: 5_000 });
  await expect(page.locator(sel.submit)).toBeVisible();

  await ctx.close();
});

test('1.4 Logout returns to login form and re-login works', async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: adminAuthFile });
  const page = await ctx.newPage();

  await page.goto(BASE_URL);
  await page.locator(sel.chatInput).waitFor({ state: 'visible', timeout: 20_000 });

  await page.getByRole('button', { name: /sign out/i }).click();

  await expect(page.locator(sel.submit)).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(sel.chatInput)).not.toBeVisible();

  await page.locator(sel.identifier).fill('admin');
  await page.locator(sel.password).fill('admin123');
  await page.locator(sel.submit).click();
  await expect(page.locator(sel.chatInput)).toBeVisible({ timeout: 20_000 });

  await ctx.close();
});

test('1.5 Login page does not advertise self-signup when registration is admin-only', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await page.goto(BASE_URL);
  await page.locator(sel.identifier).waitFor({ timeout: 10_000 });

  await expect(page.locator(sel.chatInput)).not.toBeVisible();
  await expect(page.locator('text=Sign up')).not.toBeVisible();
  await expect(
    page.locator('text=/contact an administrator to create an account/i'),
  ).toBeVisible({ timeout: 10_000 });

  await ctx.close();
});

test('1.6 Access token stored in localStorage after login', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await page.goto(BASE_URL);
  await page.locator(sel.identifier).waitFor({ timeout: 10_000 });
  await page.locator(sel.identifier).fill('admin');
  await page.locator(sel.password).fill('admin123');
  await page.locator(sel.submit).click();
  await page.locator(sel.chatInput).waitFor({ state: 'visible', timeout: 20_000 });

  const token = await page.evaluate(() => localStorage.getItem('dlms_access_token'));
  expect(token).not.toBeNull();
  expect(token!.split('.').length).toBe(3);

  await ctx.close();
});

test('1.7 Unauthenticated visit shows login form, not chat', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await page.goto(BASE_URL);

  await expect(page.locator(sel.submit)).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('text=Welcome back')).toBeVisible();
  await expect(page.locator(sel.chatInput)).not.toBeVisible();

  await ctx.close();
});

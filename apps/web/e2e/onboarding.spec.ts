import { expect, test, type Page } from '@playwright/test';

/**
 * BC-0115 acceptance: the whole first-run flow against the mocks at 375 and 1280 (see
 * playwright.config.ts projects). Each page load has fresh mock state, so the username
 * below is new every time. The axe scan per step is in e2e/a11y.spec.ts (BC-0119) and per
 * screen in src/features/onboarding/OnboardingPage.test.tsx.
 */

const stepHeading = (page: Page, name: RegExp) =>
  page.getByRole('heading', { level: 2, name });

test('completes account, consent, board and goals and lands on /explore', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/onboarding\/account$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Set up Beacon Climb',
  );
  await expect(stepHeading(page, /choose a username/i)).toBeVisible();

  await page.getByRole('textbox', { name: 'Username' }).fill('e2e_climber');
  await page.getByLabel('Password', { exact: true }).fill('a-long-password');
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page).toHaveURL(/\/onboarding\/consent$/);
  await expect(stepHeading(page, /decide what beacon may keep/i)).toBeVisible();
  await expect(page.getByText('Beacon Climb consent v1')).toBeVisible();
  const service = page.getByRole('checkbox', {
    name: /i agree to the service use/i,
  });
  const record = page.getByRole('button', { name: /record my choice/i });
  await expect(service).not.toBeChecked();
  await expect(
    page.getByRole('checkbox', { name: /research use/i }),
  ).not.toBeChecked();
  await expect(record).toBeDisabled();
  await service.check();
  await expect(record).toBeEnabled();
  await record.click();

  await expect(page).toHaveURL(/\/onboarding\/board$/);
  await expect(stepHeading(page, /which board do you climb on/i)).toBeVisible();
  await expect(page.getByRole('radio', { name: /synthetic/i })).toBeChecked();
  await page.getByRole('radio', { name: '45°' }).check();
  await page.getByRole('button', { name: 'Continue' }).click();

  await expect(page).toHaveURL(/\/onboarding\/goals$/);
  await expect(stepHeading(page, /where to start/i)).toBeVisible();
  await page.getByRole('radio', { name: /intermediate/i }).check();
  await page.getByRole('checkbox', { name: /technique/i }).check();
  await page.getByRole('checkbox', { name: /project/i }).check();
  await page.getByRole('button', { name: /save and start exploring/i }).click();

  await expect(page).toHaveURL(/\/explore$/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Explore routes' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Routes at 45 degrees' }),
  ).toBeVisible();
  await expect(
    page.getByText('10 routes loaded, more available'),
  ).toBeVisible();
});

test('a returning user who signs in skips straight to /explore', async ({
  page,
}) => {
  await page.goto('/onboarding');
  await page.getByRole('button', { name: 'Sign in instead' }).click();
  await page.getByRole('textbox', { name: 'Username' }).fill('demo');
  await page.getByLabel('Password', { exact: true }).fill('demo-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/explore$/);
  await expect(
    page.getByRole('heading', { level: 2, name: 'Routes at 40 degrees' }),
  ).toBeVisible();
});

test('the account step works with the keyboard alone', async ({ page }) => {
  await page.goto('/onboarding');
  await expect(stepHeading(page, /choose a username/i)).toBeVisible();
  await page.getByRole('textbox', { name: 'Username' }).focus();
  await page.keyboard.type('keyboard_user');
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused();
  await page.keyboard.type('a-long-password');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/onboarding\/consent$/);
  // The step heading takes focus on arrival.
  await expect(stepHeading(page, /decide what beacon may keep/i)).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(
    page.getByRole('checkbox', { name: /i agree to the service use/i }),
  ).toBeFocused();
  await page.keyboard.press('Space');
  await expect(
    page.getByRole('button', { name: /record my choice/i }),
  ).toBeEnabled();
});

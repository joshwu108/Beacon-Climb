import path from 'node:path';
import { expect, test } from '@playwright/test';

/**
 * BC-0308: log into Grafana as `admin` with GRAFANA_ADMIN_PASSWORD, open the provisioned
 * dashboard (uid `beacon-streaming`, infra/observability/grafana/dashboards/beacon-streaming.json)
 * and screenshot the whole page. The password only ever goes into the login form; it is never
 * logged, asserted on or written to the trace (trace is retain-on-failure and the form field is
 * type=password).
 */
const DASHBOARD_UID = 'beacon-streaming';
const SCREENSHOT = path.resolve(
  __dirname,
  '../..',
  process.env.GRAFANA_SCREENSHOT_PATH ?? 'docs/evidence/BC-0308/dashboard.png',
);

test('provisioned beacon-streaming dashboard renders', async ({ page }) => {
  const password = process.env.GRAFANA_ADMIN_PASSWORD;
  if (!password) {
    throw new Error(
      'GRAFANA_ADMIN_PASSWORD is unset: run with `set -a; . ./.env; set +a` in a subshell',
    );
  }

  await page.goto('/login');
  // Grafana 13 login form: "Email or username" textbox and the single password input.
  await page.getByRole('textbox', { name: /email or username/i }).fill('admin');
  await page.locator('input[type="password"]').fill(password);
  await page.getByRole('button', { name: /log in/i }).click();
  await expect(page).not.toHaveURL(/\/login/);

  // Provisioned dashboards are looked up by uid; ?kiosk hides the nav chrome so the panels
  // fill the viewport, and the 15 min window matches what the proof traffic produced.
  await page.goto(
    `/d/${DASHBOARD_UID}/beacon-streaming?orgId=1&from=now-15m&to=now&kiosk`,
  );
  // Kiosk mode hides the title bar, so assert on the first provisioned row instead.
  await expect(
    page.getByRole('button', { name: /collapse row at a glance/i }),
  ).toBeVisible();

  // Grafana mounts and queries only the panels inside the viewport and renders a time series
  // as a uPlot <canvas> once its query returned (a stat panel renders text instead), so wait for
  // the panel shells and then for most of the charts to have drawn; "No data" panels (Flink
  // rows on the core profile) render no canvas, hence the lower bound rather than the total.
  const panels = page.locator('[data-viz-panel-key]');
  await expect
    .poll(async () => panels.count(), { timeout: 30_000 })
    .toBeGreaterThan(8);
  const charts = page.locator('[data-viz-panel-key] canvas');
  await expect
    .poll(async () => charts.count(), { timeout: 60_000 })
    .toBeGreaterThanOrEqual(8);
  await page.waitForLoadState('networkidle');

  await page.screenshot({ path: SCREENSHOT, fullPage: true });
  test.info().annotations.push({ type: 'screenshot', description: SCREENSHOT });
});

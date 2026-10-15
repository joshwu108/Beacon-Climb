import { expect, test } from '@playwright/test';
import {
  ANGLE_DEG,
  BOARD_ID,
  DEMO,
  apiClient,
  loginAsDemo,
  readToken,
  type Me,
} from './fixtures';

/** The `make seed-demo` account signs in through the real UI and sees its seeded board. */
test('the seeded demo user signs in and lands on the synthetic board at 40 degrees', async ({
  page,
  request,
}) => {
  await loginAsDemo(page);
  await expect(
    page.getByRole('heading', {
      level: 2,
      name: `Routes at ${ANGLE_DEG} degrees`,
    }),
  ).toBeVisible();
  await expect(
    page.getByText('Synthetic demo board', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('status').filter({ hasText: /\d+ routes loaded/ }),
  ).toBeVisible();

  const me = await apiClient(request, await readToken(page)).get<Me>('/v1/me');
  expect(me.status).toBe(200);
  expect(me.body.username).toBe(DEMO.username);
  expect(me.body.preferences).toMatchObject({
    board_id: BOARD_ID,
    angle_deg: ANGLE_DEG,
  });
});

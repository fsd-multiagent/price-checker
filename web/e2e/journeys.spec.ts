import { expect, test, type Page } from '@playwright/test';

// UJ-1 and UJ-2 through the real browser, web app and backend (story 2.14, T-4). Retailer HTTP is
// the backend's FixtureHttp stub; each test uses its own EAN so order does not matter (T-5).
const backendPort = process.env.E2E_BACKEND_PORT ?? '18080';

async function lookUp(page: Page, input: string) {
  const ean = page.getByLabel('EAN (8 or 13 digits)');
  await ean.fill(input);
  await ean.press('Enter');
}

function row(page: Page, retailer: string) {
  return page
    .getByRole('region', { name: 'Price results' })
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: retailer, exact: true }) });
}

test('2.14.1 UJ-1: both retailers found and the cheaper one is marked Lowest price', async ({
  page,
}) => {
  await page.goto('/');
  await lookUp(page, '5940000000011  ');

  const results = page.getByRole('region', { name: 'Price results' });
  await expect(results.getByRole('row')).toHaveCount(3);
  await expect(results).not.toContainText('Unavailable');
  await expect(row(page, 'cel.ro')).toContainText('Lowest price');
  await expect(row(page, 'evomag.ro')).not.toContainText('Lowest price');
  await expect(page.locator('.badge-lowest')).toHaveCount(1);
  await expect(page.getByText('Found prices at 2 of 2 retailers.')).toBeVisible();
});

test('2.14.2 UJ-2: a hanging retailer is Unavailable and the other is still Lowest', async ({
  page,
}) => {
  await page.goto('/');
  await lookUp(page, '5940000000028');

  await expect(page.getByText('Found prices at 1 of 2 retailers.')).toBeVisible({
    timeout: 10_000,
  });
  await expect(row(page, 'evomag.ro')).toContainText('Unavailable');
  await expect(row(page, 'cel.ro')).toContainText('Lowest price');
});

test('2.14.3 a repeated Lookup is served from the cache', async ({ page, request }) => {
  const requests = async () => {
    const response = await request.get(
      `http://127.0.0.1:${backendPort}/e2e/requests?ean=5940000000035`,
    );
    expect(response.ok()).toBe(true);
    return ((await response.json()) as { requests: number }).requests;
  };
  const fetched = page.getByRole('region', { name: 'Price results' }).locator('time');

  await page.goto('/');
  await lookUp(page, '5940000000035');
  await expect(page.getByText('Found prices at 2 of 2 retailers.')).toBeVisible();
  const firstTimes = await fetched.evaluateAll((els) => els.map((e) => e.getAttribute('datetime')));
  const firstText = await fetched.allTextContents();
  const afterFirst = await requests();
  expect(afterFirst).toBe(2);

  await page.goto('/');
  await lookUp(page, '5940000000035');
  await expect(page.getByText('Found prices at 2 of 2 retailers.')).toBeVisible();
  expect(await fetched.evaluateAll((els) => els.map((e) => e.getAttribute('datetime')))).toEqual(
    firstTimes,
  );
  expect(await fetched.allTextContents()).toEqual(firstText);
  expect(await requests()).toBe(afterFirst);
});

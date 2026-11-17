import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { App } from './App';

const webRoot = resolve(import.meta.dirname, '..');

describe('AC 1.2.3 page shell', () => {
  it('AC 1.2.3: shows the heading, explanation line and footer note', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1, name: 'Price Checker' })).toBeInTheDocument();
    expect(
      screen.getByText("Enter a product's EAN to compare prices at approved Romanian retailers."),
    ).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent(
      'Prices include VAT. Times are Romanian local time. Only approved retailers are checked.',
    );
  });

  it('AC 1.2.3: index.html declares lang="en"', () => {
    const html = readFileSync(resolve(webRoot, 'index.html'), 'utf8');
    expect(html).toMatch(/<html[^>]*\slang="en"/);
  });

  it('AC 1.2.3: the column is 880px wide and centred', () => {
    const tokens = readFileSync(resolve(import.meta.dirname, 'styles/tokens.css'), 'utf8');
    const base = readFileSync(resolve(import.meta.dirname, 'styles/base.css'), 'utf8');
    expect(tokens).toMatch(/--spacing-content-max-width:\s*880px;/);
    const page = /\.page\s*\{([^}]*)\}/.exec(base)?.[1] ?? '';
    expect(page).toMatch(/max-width:\s*var\(--spacing-content-max-width\);/);
    expect(page).toMatch(/margin-left:\s*auto;/);
    expect(page).toMatch(/margin-right:\s*auto;/);
    expect(page).toMatch(/padding:\s*var\(--spacing-xl\)/);
  });

  it('AC 1.2.3: axe reports no serious or critical violations', async () => {
    render(<App />);
    const results = await axe.run(document.body);
    const severe = results.violations.filter(
      (v) => v.impact === 'serious' || v.impact === 'critical',
    );
    expect(severe.map((v) => v.id)).toEqual([]);
  });
});

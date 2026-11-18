import { fireEvent, render, screen } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';
import { EanForm } from './EanForm';

function renderForm(props: Partial<Parameters<typeof EanForm>[0]> = {}) {
  const onChange = vi.fn();
  const onSubmit = vi.fn();
  render(<EanForm value="" error={null} onChange={onChange} onSubmit={onSubmit} {...props} />);
  return { onChange, onSubmit };
}

describe('AC 1.7.2 EAN form', () => {
  it('AC 1.7.2: shows the label, hint, numeric autofocused input and button', () => {
    renderForm();
    const input = screen.getByLabelText('EAN (8 or 13 digits)');
    expect(input).toHaveAttribute('inputmode', 'numeric');
    expect(input).toHaveAttribute('autocomplete', 'off');
    expect(input).toHaveFocus();
    expect(screen.getByText('Find it under the barcode on the box.')).toBeInTheDocument();
    expect(input).toHaveAccessibleDescription('Find it under the barcode on the box.');
    expect(screen.getByRole('button', { name: 'Check prices' })).toHaveAttribute('type', 'submit');
  });

  it('AC 1.7.2: the button submits and focus returns to the input', () => {
    const { onSubmit } = renderForm();
    const button = screen.getByRole('button', { name: 'Check prices' });
    button.focus();
    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('EAN (8 or 13 digits)')).toHaveFocus();
  });

  it('AC 1.7.2: submitting the form (Enter) calls onSubmit', () => {
    const { onSubmit } = renderForm();
    fireEvent.submit(screen.getByLabelText('EAN (8 or 13 digits)'));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('AC 1.7.2: typing reports the new value', () => {
    const { onChange } = renderForm();
    fireEvent.change(screen.getByLabelText('EAN (8 or 13 digits)'), {
      target: { value: '123' },
    });
    expect(onChange).toHaveBeenCalledWith('123');
  });
});

describe('AC 1.7.3 EAN form error state', () => {
  it('AC 1.7.3: an error is an alert linked to the invalid input, which gets focus', () => {
    renderForm({ error: 'Error: enter an EAN.' });
    const input = screen.getByLabelText('EAN (8 or 13 digits)');
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Error: enter an EAN.');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input.getAttribute('aria-describedby')?.split(' ')).toContain(alert.id);
    expect(input).toHaveFocus();
  });

  it('AC 1.7.3: without an error the input is not marked invalid and no alert shows', () => {
    renderForm();
    expect(screen.getByLabelText('EAN (8 or 13 digits)')).not.toHaveAttribute('aria-invalid');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('AC 1.7.3: axe reports no serious or critical violations with an error shown', async () => {
    renderForm({ value: '123', error: 'Error: an EAN has 8 or 13 digits. You entered 3.' });
    const results = await axe.run(document.body);
    const severe = results.violations.filter(
      (v) => v.impact === 'serious' || v.impact === 'critical',
    );
    expect(severe.map((v) => v.id)).toEqual([]);
  });
});

import { fireEvent, render, screen } from '@testing-library/react';
import { Button, StatusBadge } from '../index';

describe('Button', () => {
  it('is a real button (type=button by default) and handles clicks', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Retry</Button>);
    const button = screen.getByRole('button', { name: 'Retry' });
    expect(button.getAttribute('type')).toBe('button');
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('StatusBadge', () => {
  it('announces label and status as text, not colour only', () => {
    render(<StatusBadge label="Backend API" status="down" />);
    const badge = screen.getByRole('status');
    expect(badge.textContent).toBe('Backend API: Unavailable');
    expect(badge.getAttribute('data-status')).toBe('down');
  });

  it('shows a loading state', () => {
    render(<StatusBadge label="Database" status="loading" />);
    expect(screen.getByRole('status').textContent).toBe('Database: Checking…');
  });
});

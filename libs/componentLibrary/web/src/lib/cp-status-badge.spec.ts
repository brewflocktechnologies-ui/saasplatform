import { CpStatusBadge, defineCpStatusBadge } from '../index';

describe('cp-status-badge', () => {
  beforeAll(() => defineCpStatusBadge());

  it('registers idempotently', () => {
    expect(() => defineCpStatusBadge()).not.toThrow();
    expect(customElements.get('cp-status-badge')).toBe(CpStatusBadge);
  });

  it('renders label and status text in its shadow root and reflects status', async () => {
    const el = document.createElement('cp-status-badge');
    el.label = 'Widget';
    el.status = 'up';
    document.body.append(el);
    await el.updateComplete;

    const status = el.shadowRoot?.querySelector('[role="status"]');
    expect(status?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Widget: Healthy');
    expect(el.getAttribute('status')).toBe('up');
    el.remove();
  });
});

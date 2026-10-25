import { WIDGET_TAG } from './embed/v1';

describe('<chat-platform-widget>', () => {
  it('is registered by the v1 embed entry point', () => {
    expect(customElements.get(WIDGET_TAG)).toBeDefined();
  });

  it('renders the greeting and appends sent messages in memory', async () => {
    const el = document.createElement('chat-platform-widget');
    document.body.append(el);
    await el.updateComplete;
    const root = el.shadowRoot as ShadowRoot;

    expect(root.querySelector('[data-testid="greeting"]')?.textContent).toContain('How can we help');

    const input = root.querySelector('input') as HTMLInputElement;
    input.value = 'Hello from the test';
    input.dispatchEvent(new Event('input'));
    root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await el.updateComplete;

    const messages = [...root.querySelectorAll('[data-testid="message"]')].map((m) => m.textContent);
    expect(messages).toEqual(['Hello from the test']);
    expect(input.value).toBe('');
    el.remove();
  });

  it('ignores blank messages', async () => {
    const el = document.createElement('chat-platform-widget');
    document.body.append(el);
    await el.updateComplete;
    el.shadowRoot?.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('[data-testid="message"]').length).toBe(0);
    el.remove();
  });
});

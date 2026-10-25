import { LitElement, css, html } from 'lit';
import { live } from 'lit/directives/live.js';

interface DemoMessage {
  id: number;
  author: 'visitor' | 'agent';
  text: string;
}

/**
 * `<chat-platform-widget>` — local POC chat panel. Messages live in memory only; nothing is sent to a server.
 * Styles consume semantic design tokens inherited from the host page (CSS custom properties cross the shadow boundary).
 */
export class ChatPlatformWidget extends LitElement {
  static override properties = {
    greeting: { type: String },
    messages: { state: true },
    draft: { state: true },
  };

  declare greeting: string;
  declare messages: DemoMessage[];
  declare draft: string;
  private nextId = 1;

  constructor() {
    super();
    this.greeting = 'Hi! How can we help you today?';
    this.messages = [];
    this.draft = '';
  }

  static override styles = css`
    :host {
      display: block;
      max-width: 360px;
      font-family: var(--cp-font-family);
      color: var(--cp-color-text);
    }
    .panel {
      display: flex;
      flex-direction: column;
      background: var(--cp-color-surface);
      border: var(--cp-border);
      border-radius: var(--cp-radius-card);
      overflow: hidden;
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--cp-spacing-sm);
      padding: var(--cp-spacing-sm) var(--cp-spacing-md);
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
    }
    h2 {
      margin: 0;
      font-size: var(--cp-text-md);
    }
    ol {
      list-style: none;
      margin: 0;
      padding: var(--cp-spacing-md);
      display: flex;
      flex-direction: column;
      gap: var(--cp-spacing-sm);
      min-height: 160px;
      max-height: 280px;
      overflow-y: auto;
    }
    li {
      padding: var(--cp-spacing-sm) var(--cp-spacing-md);
      border-radius: var(--cp-radius-card);
      max-width: 80%;
      font-size: var(--cp-text-sm);
    }
    .agent {
      align-self: flex-start;
      background: var(--cp-color-background);
    }
    .visitor {
      align-self: flex-end;
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
    }
    form {
      display: flex;
      gap: var(--cp-spacing-sm);
      padding: var(--cp-spacing-sm);
      border-top: var(--cp-border);
    }
    input {
      flex: 1;
      font: inherit;
      padding: var(--cp-spacing-sm);
      border: var(--cp-border);
      border-radius: var(--cp-radius-control);
      color: var(--cp-color-text);
      background: var(--cp-color-surface);
    }
    button {
      font: inherit;
      padding: var(--cp-spacing-sm) var(--cp-spacing-md);
      border: none;
      border-radius: var(--cp-radius-control);
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
      cursor: pointer;
    }
    input:focus-visible,
    button:focus-visible {
      outline: none;
      box-shadow: var(--cp-focus-ring);
    }
    .note {
      margin: 0;
      padding: 0 var(--cp-spacing-md) var(--cp-spacing-sm);
      font-size: var(--cp-text-sm);
      color: var(--cp-color-text-muted);
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `;

  private send(e: SubmitEvent) {
    e.preventDefault();
    const text = this.draft.trim();
    if (!text) return;
    this.messages = [...this.messages, { id: this.nextId++, author: 'visitor', text }];
    this.draft = '';
  }

  override render() {
    return html`
      <section class="panel" aria-labelledby="title">
        <header>
          <h2 id="title">Chat with us</h2>
          <cp-status-badge label="Mode" status="degraded" title="In-memory demo"></cp-status-badge>
        </header>
        <ol role="log" aria-live="polite" aria-label="Messages">
          <li class="agent" data-testid="greeting">${this.greeting}</li>
          ${this.messages.map(
            (m) => html`<li class=${m.author} data-testid="message">${m.text}</li>`,
          )}
        </ol>
        <form @submit=${this.send}>
          <label class="sr-only" for="composer">Message</label>
          <input
            id="composer"
            autocomplete="off"
            placeholder="Type a message…"
            .value=${live(this.draft)}
            @input=${(e: InputEvent) => (this.draft = (e.target as HTMLInputElement).value)}
          />
          <button type="submit">Send</button>
        </form>
        <p class="note">Local POC: messages stay in this browser tab and are not sent to a server.</p>
      </section>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'chat-platform-widget': ChatPlatformWidget;
  }
}

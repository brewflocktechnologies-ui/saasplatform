import { LitElement, css, html } from 'lit';

export type CpStatus = 'loading' | 'up' | 'degraded' | 'down';

const STATUS_TEXT: Record<CpStatus, string> = {
  loading: 'Checking…',
  up: 'Healthy',
  degraded: 'Degraded',
  down: 'Unavailable',
};

/**
 * `<cp-status-badge label="Widget" status="up">` — framework-independent status indicator.
 * Shadow styles consume semantic design tokens, which inherit from the host document.
 */
export class CpStatusBadge extends LitElement {
  static override properties = {
    label: { type: String },
    status: { type: String, reflect: true },
  };

  declare label: string;
  declare status: CpStatus;

  constructor() {
    super();
    this.label = '';
    this.status = 'loading';
  }

  static override styles = css`
    :host {
      display: inline-flex;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: var(--cp-spacing-xs);
      padding: var(--cp-spacing-xs) var(--cp-spacing-sm);
      border-radius: var(--cp-radius-badge);
      border: var(--cp-border);
      font-family: var(--cp-font-family);
      font-size: var(--cp-text-sm);
      color: var(--cp-color-text);
      background: var(--cp-color-surface);
    }
    .dot {
      width: 0.6em;
      height: 0.6em;
      border-radius: 50%;
      background: var(--cp-color-text-muted);
    }
    :host([status='up']) .dot {
      background: var(--cp-color-success);
    }
    :host([status='degraded']) .dot {
      background: var(--cp-color-warning);
    }
    :host([status='down']) .dot {
      background: var(--cp-color-danger);
    }
  `;

  override render() {
    return html`<span class="badge" role="status" aria-live="polite">
      <span class="dot" aria-hidden="true"></span>${this.label}: ${STATUS_TEXT[this.status] ?? this.status}
    </span>`;
  }
}

export const CP_STATUS_BADGE_TAG = 'cp-status-badge';

/** Idempotent registration so multiple bundles on one page do not throw. */
export function defineCpStatusBadge(registry: CustomElementRegistry = customElements): void {
  if (!registry.get(CP_STATUS_BADGE_TAG)) registry.define(CP_STATUS_BADGE_TAG, CpStatusBadge);
}

declare global {
  interface HTMLElementTagNameMap {
    'cp-status-badge': CpStatusBadge;
  }
}

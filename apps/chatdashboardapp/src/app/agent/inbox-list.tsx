'use client';

import type { ConversationView, MessageCreatedEvent } from '@chat-platform/chat-client';

export type ListView = 'queue' | 'mine' | 'all';

const time = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

/**
 * Applies a `message.created` event from the inbox channel to the list only: preview, time and unread count of the affected
 * item. It never produces thread messages, so an event for another conversation cannot leak into the open thread.
 * The open conversation and the agent's own replies do not raise the unread badge.
 */
export function applyInboxMessage(
  items: ConversationView[],
  event: MessageCreatedEvent,
  selectedId: string | null,
): ConversationView[] {
  const { message } = event.data;
  const index = items.findIndex((c) => c.id === message.conversationId);
  if (index < 0 || message.sequence <= items[index].lastSequence) return items;
  const current = items[index];
  const updated: ConversationView = {
    ...current,
    lastSequence: message.sequence,
    lastMessagePreview: message.body.slice(0, 120),
    lastMessageAt: message.createdAt,
    unreadCount:
      current.id === selectedId || message.senderType === 'AGENT' ? current.unreadCount : current.unreadCount + 1,
  };
  return [updated, ...items.filter((_, i) => i !== index)];
}

interface Props {
  items: ConversationView[] | null;
  selectedId: string | null;
  view: ListView;
  archived: boolean;
  onView: (view: ListView) => void;
  onArchived: (archived: boolean) => void;
  onSelect: (id: string) => void;
}

const TABS: Array<{ view: ListView; label: string; testId: string }> = [
  { view: 'queue', label: 'Queue', testId: 'queue-tab' },
  { view: 'mine', label: 'Mine', testId: 'mine-tab' },
  { view: 'all', label: 'All', testId: 'all-tab' },
];

export function InboxList({ items, selectedId, view, archived, onView, onArchived, onSelect }: Props) {
  return (
    <div>
      <div role="tablist" aria-label="Inbox views" className="tabs">
        {TABS.map((tab) => (
          <button
            key={tab.view}
            type="button"
            role="tab"
            aria-selected={view === tab.view}
            data-testid={tab.testId}
            className="tab"
            onClick={() => onView(tab.view)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <label className="muted">
        <input
          type="checkbox"
          data-testid="archived-toggle"
          checked={archived}
          onChange={(e) => onArchived(e.target.checked)}
        />{' '}
        Show archived
      </label>
      {items === null ? (
        <p className="muted">Loading…</p>
      ) : items.length === 0 ? (
        <p className="muted" data-testid="inbox-empty">
          No conversations here. Open the visitor demo to start one.
        </p>
      ) : (
        <ul className="inbox" data-testid="inbox">
          {items.map((c) => (
            <li key={c.id}>
              <button
                type="button"
                className="inbox-item"
                aria-current={c.id === selectedId}
                data-testid="inbox-item"
                data-conversation-id={c.id}
                onClick={() => onSelect(c.id)}
              >
                <strong>{c.contactDisplayName ?? c.contactId}</strong>{' '}
                <span className="status" data-testid="item-status">
                  {c.archivedAt ? 'ARCHIVED' : c.status} · {c.assignment.state}
                </span>
                {c.unreadCount > 0 && (
                  <span className="unread" data-testid="unread-count">
                    {c.unreadCount}
                  </span>
                )}
                <span className="muted preview">{c.lastMessagePreview ?? 'No messages yet'}</span>
                <span className="muted">{time(c.lastMessageAt ?? c.createdAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

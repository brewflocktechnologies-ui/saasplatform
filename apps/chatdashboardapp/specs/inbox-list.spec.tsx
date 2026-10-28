import { fireEvent, render, screen } from '@testing-library/react';
import type { MessageCreatedEvent } from '@chat-platform/chat-client';
import { InboxList, applyInboxMessage } from '../src/app/agent/inbox-list';
import { conversation, event, message } from './fixtures';

const created = (conversationId: string, sequence: number, body = 'hi') =>
  event(
    'message.created',
    { message: message(sequence, body, 'CONTACT', conversationId) },
    `e-${conversationId}-${sequence}`,
    conversationId,
  ) as MessageCreatedEvent;

describe('inbox list', () => {
  it('inboxEventDoesNotLeakIntoOpenThread', () => {
    const open = conversation({ id: 'c1' });
    const other = conversation({ id: 'c2' });
    const next = applyInboxMessage([open, other], created('c2', 1, 'for the other chat'), 'c1');

    expect(next.map((c) => c.id)).toEqual(['c2', 'c1']);
    expect(next[0].lastMessagePreview).toBe('for the other chat');
    expect(next[0].unreadCount).toBe(1);
    // The open conversation's item is untouched, and the reducer never yields thread messages.
    expect(next[1]).toBe(open);
  });

  it('does not raise unread for the open conversation, agent replies or stale sequences', () => {
    const open = conversation({ id: 'c1', lastSequence: 1 });
    expect(applyInboxMessage([open], created('c1', 2), 'c1')[0].unreadCount).toBe(0);
    expect(applyInboxMessage([open], created('c1', 1), null)).toEqual([open]);
    const agentReply = event('message.created', { message: message(2, 'x', 'AGENT') }, 'ea') as MessageCreatedEvent;
    expect(applyInboxMessage([open], agentReply, null)[0].unreadCount).toBe(0);
  });

  it('unreadBadgeUsesUnreadCount', () => {
    render(
      <InboxList
        items={[conversation({ id: 'c1', unreadCount: 3 }), conversation({ id: 'c2', unreadCount: 0 })]}
        selectedId={null}
        view="queue"
        archived={false}
        onView={() => undefined}
        onArchived={() => undefined}
        onSelect={() => undefined}
      />,
    );
    const badges = screen.getAllByTestId('unread-count');
    expect(badges).toHaveLength(1);
    expect(badges[0].textContent).toBe('3');
  });

  it('switches tabs and the archived toggle', () => {
    const onView = vi.fn();
    const onArchived = vi.fn();
    render(
      <InboxList
        items={[]}
        selectedId={null}
        view="queue"
        archived={false}
        onView={onView}
        onArchived={onArchived}
        onSelect={() => undefined}
      />,
    );
    fireEvent.click(screen.getByTestId('mine-tab'));
    fireEvent.click(screen.getByTestId('archived-toggle'));
    expect(onView).toHaveBeenCalledWith('mine');
    expect(onArchived).toHaveBeenCalledWith(true);
  });
});

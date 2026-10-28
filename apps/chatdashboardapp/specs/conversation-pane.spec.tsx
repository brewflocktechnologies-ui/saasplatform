import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  ApiError,
  type AgentApi,
  type ChatEvent,
  type ConversationView,
  type MessagePage,
} from '@chat-platform/chat-client';
import { ConversationPane } from '../src/app/agent/conversation-pane';
import { conversation, event, message } from './fixtures';

type Handlers = { onEvent: (e: ChatEvent) => void };

function setup(initial: ConversationView, pages: { latest?: MessagePage; older?: MessagePage } = {}) {
  const state = { current: initial };
  let handlers: Handlers | undefined;
  const api = {
    getConversation: vi.fn(async () => state.current),
    listMessages: vi.fn(async (_id: string, options: { before?: number } = {}) =>
      options.before !== undefined
        ? (pages.older ?? { items: [], hasMore: false })
        : (pages.latest ?? { items: [], hasMore: false }),
    ),
    sendMessage: vi.fn(),
    markRead: vi.fn(async () => undefined),
    typing: vi.fn(async () => undefined),
    assign: vi.fn(),
    resolve: vi.fn(),
    reopen: vi.fn(),
    pending: vi.fn(),
    archive: vi.fn(async (_id: string, _body: unknown) => state.current),
    unarchive: vi.fn(),
  };
  const realtime = {
    subscribe: vi.fn((_channel: string, h: Handlers) => {
      handlers = h;
      return () => undefined;
    }),
  };
  render(<ConversationPane api={api as unknown as AgentApi} realtime={realtime} conversationId="c1" />);
  const fire = (e: ChatEvent) => act(async () => handlers?.onEvent(e));
  return { api, state, fire };
}

const bodies = () => screen.queryAllByTestId('message-body').map((m) => m.textContent);

describe('conversation pane', () => {
  it('staleVersionReloadsAndShowsNotice', async () => {
    const { api } = setup(conversation({ version: 4 }));
    await screen.findByTestId('assignment-state');
    api.resolve.mockRejectedValue(new ApiError(409, 'stale', 'STALE_VERSION'));
    const before = api.getConversation.mock.calls.length;

    fireEvent.click(screen.getByTestId('action-resolve'));

    await waitFor(() => expect(screen.getByTestId('agent-notice').textContent).toContain('STALE_VERSION'));
    expect(api.resolve).toHaveBeenCalledWith('c1', { expectedVersion: 4 });
    await waitFor(() => expect(api.getConversation.mock.calls.length).toBe(before + 1));
  });

  it('archiveAfterReleaseUsesFreshVersion', async () => {
    const released = conversation({
      status: 'RESOLVED',
      version: 5,
      assignment: { state: 'RELEASE_PENDING', agentId: 'agentbob' },
    });
    const { api, state, fire } = setup(released);
    await waitFor(() => expect(screen.getByTestId('assignment-state').textContent).toBe('RELEASE_PENDING'));

    state.current = { ...released, version: 6, assignment: { state: 'NONE', agentId: null } };
    await fire(event('conversation.updated', { conversation: state.current, change: 'ASSIGNMENT_UPDATED' }, 'e6'));
    await waitFor(() => expect(screen.getByTestId('assignment-state').textContent).toBe('NONE'));

    fireEvent.click(screen.getByTestId('action-archive'));
    await waitFor(() => expect(api.archive).toHaveBeenCalledWith('c1', { expectedVersion: 6 }));
    expect(screen.queryByTestId('agent-notice')).toBeNull();
  });

  it('conversationUpdatedRefetchesAssignmentDetails', async () => {
    const detailed = (explanation: string | null) =>
      conversation({
        assignment: { state: 'ASSIGNED', agentId: 'agentbob', decisionId: 'd1', reason: 'LEAST_BUSY', explanation },
      });
    const { api, state, fire } = setup(detailed(null));
    await screen.findByTestId('assignment-state');
    expect(screen.queryByTestId('assignment-explanation')).toBeNull();

    state.current = { ...detailed('Chosen: least busy agent'), version: 2 };
    const before = api.getConversation.mock.calls.length;
    const payload = { ...state.current, assignment: { state: 'ASSIGNED' as const, agentId: 'agentbob' } };
    await fire(event('conversation.updated', { conversation: payload, change: 'ASSIGNED' }, 'e2'));
    await waitFor(() =>
      expect(screen.getByTestId('assignment-explanation').textContent).toBe('Chosen: least busy agent'),
    );
    expect(api.getConversation.mock.calls.length).toBe(before + 1);

    await fire(event('conversation.updated', { conversation: conversation({ id: 'c2' }), change: 'ASSIGNED' }, 'e3', 'c2'));
    expect(api.getConversation.mock.calls.length).toBe(before + 1);
  });

  it('bodiesRenderAsText', async () => {
    const evil = '<img src=x onerror="window.__pwned=1">';
    const { fire } = setup(conversation(), { latest: { items: [message(1, evil)], hasMore: false } });
    await waitFor(() => expect(bodies()).toEqual([evil]));
    expect(screen.getByTestId('agent-messages').querySelector('img')).toBeNull();
    await fire(event('message.created', { message: message(2, '<b>bold</b>') }, 'e-b'));
    await waitFor(() => expect(bodies()).toEqual([evil, '<b>bold</b>']));
    expect(screen.getByTestId('agent-messages').querySelector('b')).toBeNull();
  });

  it('loadOlderPrependsPreviousPageOnce', async () => {
    const { api } = setup(conversation(), {
      latest: { items: [message(3), message(4)], hasMore: true },
      older: { items: [message(1), message(2)], hasMore: false },
    });
    await waitFor(() => expect(bodies()).toEqual(['message 3', 'message 4']));

    fireEvent.click(await screen.findByTestId('load-older'));

    await waitFor(() => expect(bodies()).toEqual(['message 1', 'message 2', 'message 3', 'message 4']));
    expect(
      api.listMessages.mock.calls.filter(([, o]) => (o as { before?: number })?.before !== undefined),
    ).toEqual([['c1', { before: 3, limit: 50 }]]);
    expect(screen.queryByTestId('load-older')).toBeNull();
  });
});

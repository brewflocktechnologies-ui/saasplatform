'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import {
  ApiError,
  MAX_MESSAGE_LENGTH,
  conversationChannel,
  createConversationSync,
  createPendingQueue,
  createTypingEmitter,
  createTypingReceiver,
  mergeMessages,
  type AgentApi,
  type ChatEvent,
  type ConversationView,
  type MessageView,
  type PendingMessage,
  type PendingQueue,
  type Realtime,
} from '@chat-platform/chat-client';
import { Button } from '@chat-platform/component-library-react';

const time = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

const errorText = (e: unknown) => (e instanceof ApiError ? e.message : 'unexpected error');

const PAGE = 50;
const READ_DEBOUNCE_MS = 1_000;

interface Props {
  api: AgentApi;
  realtime: Pick<Realtime, 'subscribe'>;
  conversationId: string;
  /** Called with every fresh view of the open conversation so the list can follow. */
  onChanged?: (view: ConversationView) => void;
}

export function ConversationPane({ api, realtime, conversationId, onChanged }: Props) {
  const [view, setView] = useState<ConversationView | null>(null);
  const [synced, setSynced] = useState<MessageView[]>([]);
  const [older, setOlder] = useState<MessageView[]>([]);
  const [hasOlder, setHasOlder] = useState(false);
  const [pending, setPending] = useState<PendingMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [customerTyping, setCustomerTyping] = useState(false);
  const [subscribed, setSubscribed] = useState(false);

  const queue = useRef<PendingQueue | null>(null);
  const emitter = useRef<ReturnType<typeof createTypingEmitter> | null>(null);
  const viewRef = useRef<ConversationView | null>(null);
  const onChangedRef = useRef(onChanged);
  const lastRead = useRef(0);
  const logRef = useRef<HTMLOListElement>(null);
  viewRef.current = view;
  onChangedRef.current = onChanged;

  const applyView = useCallback((next: ConversationView) => {
    setView(next);
    onChangedRef.current?.(next);
  }, []);

  const refetch = useCallback(async () => {
    try {
      applyView(await api.getConversation(conversationId));
    } catch (e) {
      setError(`Could not load the conversation: ${errorText(e)}`);
    }
  }, [api, conversationId, applyView]);

  useEffect(() => {
    let active = true;
    setView(null);
    setSynced([]);
    setOlder([]);
    setHasOlder(false);
    setPending([]);
    setError('');
    setNotice('');
    setCustomerTyping(false);
    setSubscribed(false);
    lastRead.current = 0;
    const seen = new Set<string>();

    const sync = createConversationSync({
      conversationId,
      fetchLatest: async () => {
        const page = await api.listMessages(conversationId, { limit: PAGE });
        if (active) setHasOlder(page.hasMore);
        return page;
      },
      fetchAfter: (after) => api.listMessages(conversationId, { after, limit: 200 }),
      fetchConversation: () => api.getConversation(conversationId),
      onMessages: (messages) => active && setSynced(messages),
      onConversationUpdated: (updated) => active && applyView(updated),
    });

    const pendingQueue = createPendingQueue({
      send: async (clientMessageId, body) => {
        const message = await api.sendMessage(conversationId, { clientMessageId, body });
        void sync.handleEvent({
          eventId: `sent:${message.id}`,
          eventType: 'message.created',
          schemaVersion: 1,
          tenantId: viewRef.current?.tenantId ?? '',
          conversationId,
          occurredAt: message.createdAt,
          data: { message },
        });
        return message;
      },
    });
    queue.current = pendingQueue;
    const stopPending = pendingQueue.subscribe((items) => active && setPending(items));

    const typingEmitter = createTypingEmitter({ send: (typing) => api.typing(conversationId, typing) });
    emitter.current = typingEmitter;
    const receiver = createTypingReceiver({ onChange: (typing) => active && setCustomerTyping(typing) });

    const onEvent = (event: ChatEvent) => {
      if (event.conversationId !== conversationId) return;
      switch (event.eventType) {
        case 'typing':
          if (event.data.participantType === 'CONTACT') receiver.handle(event);
          return;
        case 'message.created': {
          const { clientMessageId } = event.data.message;
          if (clientMessageId) pendingQueue.markSent(clientMessageId, event.data.message);
          void sync.handleEvent(event);
          return;
        }
        case 'conversation.created':
        case 'conversation.updated':
          // Events carry only `state`/`agentId` (R-P3): reason and explanation always come from the agent GET.
          if (!seen.has(event.eventId)) {
            seen.add(event.eventId);
            void refetch();
          }
          return;
        default:
          return;
      }
    };

    const unsubscribe = realtime.subscribe(conversationChannel(conversationId), {
      onEvent,
      onSubscribed: (ctx) => {
        setSubscribed(true);
        void sync.handleSubscribed(ctx).catch((e) => active && setError(`Could not load messages: ${errorText(e)}`));
      },
      onError: (message) => active && setError(`Live conversation unavailable: ${message}`),
    });
    // History must not depend on the socket: reconcile now; a later `subscribed` shares or repeats the run.
    void sync.reconcile().catch((e) => active && setError(`Could not load messages: ${errorText(e)}`));

    return () => {
      active = false;
      unsubscribe();
      stopPending();
      typingEmitter.stop();
      receiver.reset();
      queue.current = null;
      emitter.current = null;
    };
  }, [api, realtime, conversationId, applyView, refetch]);

  const messages = mergeMessages(older, synced);
  const lastSequence = synced.length > 0 ? synced[synced.length - 1].sequence : 0;

  // Read cursor, debounced, only while the tab is visible.
  useEffect(() => {
    if (lastSequence <= lastRead.current || document.visibilityState !== 'visible') return;
    const timer = setTimeout(() => {
      const previous = lastRead.current;
      lastRead.current = lastSequence;
      api.markRead(conversationId, lastSequence).catch(() => (lastRead.current = previous));
    }, READ_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [api, conversationId, lastSequence]);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [synced, pending]);

  async function loadOlder() {
    const first = messages[0];
    if (!first) return;
    try {
      const page = await api.listMessages(conversationId, { before: first.sequence, limit: PAGE });
      setOlder((current) => mergeMessages(current, page.items));
      setHasOlder(page.hasMore);
    } catch (e) {
      setError(`Could not load older messages: ${errorText(e)}`);
    }
  }

  async function run(action: (current: ConversationView) => Promise<ConversationView>) {
    const current = viewRef.current;
    if (!current) return;
    setError('');
    setNotice('');
    try {
      applyView(await action(current));
    } catch (e) {
      if (e instanceof ApiError && e.code === 'STALE_VERSION') {
        setNotice('This conversation changed elsewhere (STALE_VERSION); reloaded the latest state.');
        await refetch();
      } else {
        setError(`Action failed: ${errorText(e)}`);
      }
    }
  }

  const expected = (c: ConversationView) => ({ expectedVersion: c.version });
  const assign = (mode: 'CLAIM' | 'AUTO' | 'REASSIGN') =>
    run((c) => api.assign(c.id, { mode, expectedVersion: c.version }, crypto.randomUUID()));

  function reply(e: FormEvent) {
    e.preventDefault();
    const text = draft.trim();
    if (!text) {
      setNotice('Type a reply first.');
      return;
    }
    setError('');
    setNotice('');
    emitter.current?.onSent();
    queue.current?.enqueue(text);
    setDraft('');
  }

  const confirmed = new Set(messages.map((m) => m.clientMessageId).filter(Boolean));
  const unconfirmed = pending.filter((p) => !confirmed.has(p.clientMessageId));
  const archived = view?.archivedAt != null;
  const resolved = view?.status === 'RESOLVED';
  const details = view && 'explanation' in view.assignment ? view.assignment : null;

  return (
    <section className="card conversation" aria-labelledby="conversation-heading">
      <h2 id="conversation-heading">
        {view ? `Chat with ${view.contactDisplayName ?? view.contactId}` : 'Loading conversation…'}
      </h2>
      {view && (
        <div className="assignment" data-testid="conversation-meta">
          <span data-testid="conversation-status">{archived ? 'ARCHIVED' : view.status}</span> · assignment{' '}
          <strong data-testid="assignment-state">{view.assignment.state}</strong>
          {view.assignment.agentId && <span> ({view.assignment.agentId})</span>}
          {details?.explanation && (
            <p className="muted" data-testid="assignment-explanation">
              {details.explanation}
            </p>
          )}
          <div className="actions">
            {!archived && (
              <>
                <Button variant="secondary" data-testid="action-assign-me" onClick={() => assign('CLAIM')}>
                  Assign to me
                </Button>
                <Button variant="secondary" data-testid="action-auto-assign" onClick={() => assign('AUTO')}>
                  Auto-assign
                </Button>
                <Button variant="secondary" data-testid="action-reassign" onClick={() => assign('REASSIGN')}>
                  Reassign
                </Button>
                {resolved ? (
                  <Button variant="secondary" data-testid="action-reopen" onClick={() => run((c) => api.reopen(c.id, expected(c)))}>
                    Reopen
                  </Button>
                ) : (
                  <>
                    <Button variant="secondary" data-testid="action-resolve" onClick={() => run((c) => api.resolve(c.id, expected(c)))}>
                      Resolve
                    </Button>
                    {view.status !== 'PENDING' && (
                      <Button variant="secondary" data-testid="action-pending" onClick={() => run((c) => api.pending(c.id, expected(c)))}>
                        Mark pending
                      </Button>
                    )}
                  </>
                )}
                <Button variant="secondary" data-testid="action-archive" onClick={() => run((c) => api.archive(c.id, expected(c)))}>
                  Archive
                </Button>
              </>
            )}
            {archived && (
              <Button variant="secondary" data-testid="action-unarchive" onClick={() => run((c) => api.unarchive(c.id, expected(c)))}>
                Unarchive
              </Button>
            )}
          </div>
        </div>
      )}
      {hasOlder && (
        <Button variant="secondary" data-testid="load-older" onClick={() => void loadOlder()}>
          Load older messages
        </Button>
      )}
      <ol
        ref={logRef}
        className="messages"
        role="log"
        aria-live="polite"
        aria-label="Messages"
        data-testid="agent-messages"
        data-conversation-id={conversationId}
        data-subscribed={String(subscribed)}
      >
        {messages.map((m) => (
          <li
            key={m.id}
            className={`bubble ${m.senderType}`}
            data-testid="message"
            data-message-id={m.id}
            data-sequence={m.sequence}
            data-state="sent"
          >
            <span className="meta">
              {m.senderType === 'AGENT' ? 'You' : m.senderId} · {time(m.createdAt)}
            </span>
            <span data-testid="message-body">{m.body}</span>
          </li>
        ))}
        {unconfirmed.map((p) => (
          <li
            key={p.clientMessageId}
            className="bubble AGENT"
            data-testid="message"
            data-client-message-id={p.clientMessageId}
            data-state={p.state}
          >
            <span className="meta">You</span>
            <span data-testid="message-body">{p.body}</span>
            {p.state === 'failed' && (
              <>
                <span className="error" data-testid="message-error">
                  Not sent: {p.error}
                </span>
                <Button variant="secondary" data-testid="retry" onClick={() => queue.current?.retry(p.clientMessageId)}>
                  Retry
                </Button>
              </>
            )}
          </li>
        ))}
      </ol>
      {customerTyping && (
        <p className="muted" data-testid="typing-indicator" role="status">
          Customer is typing…
        </p>
      )}
      {error && (
        <p className="error" role="alert" data-testid="agent-error">
          {error}
        </p>
      )}
      {notice && (
        <p className="warn" role="status" data-testid="agent-notice">
          {notice}
        </p>
      )}
      <form className="composer" onSubmit={reply}>
        <label className="sr-only" htmlFor="reply">
          Reply
        </label>
        <input
          id="reply"
          autoComplete="off"
          maxLength={MAX_MESSAGE_LENGTH}
          placeholder="Type a reply…"
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            emitter.current?.onInput();
          }}
          disabled={!view || archived || resolved}
        />
        <Button type="submit" disabled={!view || archived || resolved}>
          Send
        </Button>
      </form>
    </section>
  );
}

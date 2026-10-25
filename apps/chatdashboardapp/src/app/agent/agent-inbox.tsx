'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import {
  ApiError,
  CONNECTION_LABEL,
  INBOX_CHANNEL,
  MAX_MESSAGE_LENGTH,
  conversationChannel,
  createChatApi,
  createRealtime,
  mergeMessages,
  type ChatMessage,
  type ConnectionState,
  type Conversation,
  type Realtime,
} from '@chat-platform/chat-client';
import { Button } from '@chat-platform/component-library-react';

/** LOCAL POC — NOT REAL AUTHENTICATION: the dashboard always acts as the hardcoded demo agent. */
const AGENT = 'agentbob' as const;

const time = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

const errorText = (e: unknown) => (e instanceof ApiError ? e.message : 'unexpected error');

export function AgentInbox({ apiBaseUrl, realtimeUrl }: { apiBaseUrl: string; realtimeUrl: string }) {
  const api = useMemo(() => createChatApi(apiBaseUrl), [apiBaseUrl]);
  const realtime = useRef<Realtime | null>(null);
  const logRef = useRef<HTMLOListElement>(null);

  const [connection, setConnection] = useState<ConnectionState>('connecting');
  const [inbox, setInbox] = useState<Conversation[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [subscribedId, setSubscribedId] = useState<string | null>(null);

  const loadInbox = useCallback(async () => {
    try {
      const list = await api.listConversations(AGENT);
      setInbox(list);
      // Single-conversation MVP: select the most recent one if nothing is selected yet.
      setSelectedId((current) => current ?? list[0]?.id ?? null);
    } catch (e) {
      setError(`Could not load the inbox: ${errorText(e)}`);
    }
  }, [api]);

  // Connect, subscribe to the inbox, then fetch it (after subscribing so no startup event is lost).
  useEffect(() => {
    const rt = createRealtime({ url: realtimeUrl, api, actorId: AGENT, onState: setConnection });
    realtime.current = rt;
    const unsubscribe = rt.subscribe(INBOX_CHANNEL, {
      onEvent: () => void loadInbox(),
      onSubscribed: () => void loadInbox(),
      onError: (m) => setError(`Live inbox unavailable: ${m}`),
    });
    rt.connect();
    return () => {
      unsubscribe();
      rt.disconnect();
      realtime.current = null;
    };
  }, [api, realtimeUrl, loadInbox]);

  // Selected conversation: history from the API + live events from its channel (fresh subscription token).
  useEffect(() => {
    const rt = realtime.current;
    if (!selectedId || !rt) return;
    setMessages([]);
    setSubscribedId(null);
    const reload = () =>
      api.listMessages(selectedId, AGENT).then(
        (history) => setMessages((m) => mergeMessages(m, history)),
        (e) => setError(`Could not load messages: ${errorText(e)}`),
      );
    void reload();
    return rt.subscribe(conversationChannel(selectedId), {
      onEvent: (event) => {
        if (event.type === 'message.created' && event.conversationId === selectedId) {
          setMessages((m) => mergeMessages(m, [event.message]));
        }
      },
      onSubscribed: () => {
        setSubscribedId(selectedId);
        void reload();
      },
      onError: (m) => setError(`Live conversation unavailable: ${m}`),
    });
  }, [api, selectedId]);

  async function reply(e: FormEvent) {
    e.preventDefault();
    const text = draft.trim();
    if (!text) {
      setNotice('Type a reply first.');
      return;
    }
    if (!selectedId || sending) return;
    setSending(true);
    setError('');
    setNotice('');
    try {
      const result = await api.sendMessage(selectedId, AGENT, text);
      setMessages((m) => mergeMessages(m, [result.message]));
      setDraft('');
      if (result.realtimeDelivery === 'FAILED') {
        setNotice('Reply saved, but live delivery failed. The visitor will see it after reloading.');
      }
    } catch (err) {
      setError(`Reply not sent: ${errorText(err)}`);
    } finally {
      setSending(false);
    }
  }

  // Keep the newest message in view.
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [messages]);

  const selected = inbox?.find((c) => c.id === selectedId) ?? null;

  return (
    <div className="agent-layout">
      <section className="card" aria-labelledby="inbox-heading">
        <h2 id="inbox-heading">
          Conversations{' '}
          <span className="conn" data-testid="connection-state" data-state={connection} role="status">
            {CONNECTION_LABEL[connection]}
          </span>
        </h2>
        {inbox === null ? (
          <p className="muted">Loading…</p>
        ) : inbox.length === 0 ? (
          <p className="muted" data-testid="inbox-empty">
            No conversations yet. Open the visitor demo to start one.
          </p>
        ) : (
          <ul className="inbox" data-testid="inbox">
            {inbox.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  className="inbox-item"
                  aria-current={c.id === selectedId}
                  data-testid="inbox-item"
                  data-conversation-id={c.id}
                  onClick={() => setSelectedId(c.id)}
                >
                  <strong>{c.visitorId}</strong> <span className="status">{c.status}</span>
                  <span className="muted preview">{c.lastMessagePreview ?? 'No messages yet'}</span>
                  <span className="muted">{time(c.lastMessageAt ?? c.createdAt)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card conversation" aria-labelledby="conversation-heading">
        <h2 id="conversation-heading">{selected ? `Chat with ${selected.visitorId}` : 'No conversation selected'}</h2>
        <ol ref={logRef} className="messages" role="log" aria-live="polite" aria-label="Messages" data-testid="agent-messages"
          data-conversation-id={selectedId ?? ''}
          data-subscribed={String(subscribedId === selectedId && connection === 'live')}
        >
          {messages.map((m) => (
            <li key={m.id} className={`bubble ${m.senderRole}`} data-testid="message" data-message-id={m.id}>
              <span className="meta">
                {m.senderRole === 'AGENT' ? 'You' : m.senderId} · {time(m.createdAt)}
              </span>
              <span data-testid="message-body">{m.body}</span>
            </li>
          ))}
        </ol>
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
            onChange={(e) => setDraft(e.target.value)}
            disabled={!selectedId}
          />
          <Button type="submit" disabled={sending || !selectedId}>
            {sending ? 'Sending…' : 'Send'}
          </Button>
        </form>
      </section>
    </div>
  );
}

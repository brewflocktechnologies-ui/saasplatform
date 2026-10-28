'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiError,
  createAgentApi,
  createRealtime,
  inboxChannel,
  type AgentMe,
  type Availability,
  type ChatEvent,
  type ConnectionState,
  type ConversationView,
  type InboxView,
  type Realtime,
} from '@chat-platform/chat-client';
import { ConversationPane } from './conversation-pane';
import { InboxList, applyInboxMessage, type ListView } from './inbox-list';
import { PresenceControl } from './presence-control';

/** LOCAL POC — NOT REAL AUTHENTICATION: the dashboard always logs in as the hardcoded demo agent. */
const AGENT = 'agentbob';
const SESSION_KEY = 'cp.agent.session';
const HEARTBEAT_MS = 30_000;
const SEEN_LIMIT = 500;

const errorText = (e: unknown) => (e instanceof ApiError ? e.message : 'unexpected error');

function loadToken(): string | null {
  try {
    return sessionStorage.getItem(SESSION_KEY);
  } catch {
    return null;
  }
}
function saveToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(SESSION_KEY, token);
    else sessionStorage.removeItem(SESSION_KEY);
  } catch {
    // storage disabled: the agent simply logs in again on reload
  }
}

export function AgentInbox({ apiBaseUrl, realtimeUrl }: { apiBaseUrl: string; realtimeUrl: string }) {
  const token = useRef<string | null>(null);
  const api = useMemo(() => createAgentApi(apiBaseUrl, () => token.current), [apiBaseUrl]);

  const [realtime, setRealtime] = useState<Realtime | null>(null);
  const [connection, setConnection] = useState<ConnectionState>('connecting');
  const [me, setMe] = useState<AgentMe | null>(null);
  const [availability, setAvailability] = useState<Availability>('OFFLINE');
  const [heartbeatAt, setHeartbeatAt] = useState<string | null>(null);
  const [inboxes, setInboxes] = useState<InboxView[]>([]);
  const [inboxId, setInboxId] = useState<string | null>(null);
  const [view, setView] = useState<ListView>('queue');
  const [archived, setArchived] = useState(false);
  const [items, setItems] = useState<ConversationView[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState('');

  // Latest values for realtime callbacks, so the subscriptions are not torn down on every state change.
  const latest = useRef({ inboxId, view, archived, selectedId });
  latest.current = { inboxId, view, archived, selectedId };
  const seen = useRef({ set: new Set<string>(), order: [] as string[] });

  const loadList = useCallback(async () => {
    const { inboxId: id, view: v, archived: a } = latest.current;
    if (!id) return;
    try {
      const page = await api.listConversations(id, { view: v, archived: a, limit: 50 });
      setItems(page.items);
    } catch (e) {
      setError(`Could not load the inbox: ${errorText(e)}`);
    }
  }, [api]);

  // Login (stored token first), then agent profile and inboxes.
  useEffect(() => {
    let active = true;
    (async () => {
      try {
        token.current = loadToken();
        let profile: AgentMe | null = null;
        if (token.current) {
          try {
            profile = await api.me();
          } catch (e) {
            if (!(e instanceof ApiError) || e.status !== 401) throw e;
          }
        }
        if (!profile) {
          const session = await api.localLogin(AGENT);
          token.current = session.sessionToken;
          saveToken(session.sessionToken);
          profile = await api.me();
        }
        const list = await api.inboxes();
        if (!active) return;
        setMe(profile);
        setAvailability(profile.availability);
        setHeartbeatAt(profile.lastHeartbeatAt);
        setInboxes(list);
        setInboxId(list[0]?.id ?? null);
      } catch (e) {
        if (active) setError(`Could not sign in: ${errorText(e)}`);
      }
    })();
    return () => {
      active = false;
    };
  }, [api]);

  // Realtime: one connection, one subscription per inbox channel. The list keeps its own seen-eventId set.
  useEffect(() => {
    if (!me || inboxes.length === 0) return;
    const rt = createRealtime({
      url: realtimeUrl,
      getConnectionToken: async () => (await api.connectionToken()).token,
      getSubscriptionToken: async (channel) => (await api.subscriptionToken(channel)).token,
      onState: setConnection,
    });
    const onInboxEvent = (inbox: InboxView, event: ChatEvent) => {
      const s = seen.current;
      if (s.set.has(event.eventId)) return;
      s.set.add(event.eventId);
      s.order.push(event.eventId);
      if (s.order.length > SEEN_LIMIT) s.set.delete(s.order.shift() as string);
      if (inbox.id !== latest.current.inboxId) return;
      if (event.eventType === 'message.created') {
        setItems((current) => current && applyInboxMessage(current, event, latest.current.selectedId));
      } else if (event.eventType === 'conversation.created' || event.eventType === 'conversation.updated') {
        void loadList();
      }
    };
    const stops = inboxes.map((inbox) =>
      rt.subscribe(inboxChannel(me.tenantId, inbox.id), {
        onEvent: (event) => onInboxEvent(inbox, event),
        onSubscribed: () => void loadList(),
        onError: (m) => setError(`Live inbox unavailable: ${m}`),
      }),
    );
    rt.connect();
    setRealtime(rt);
    return () => {
      stops.forEach((stop) => stop());
      rt.disconnect();
      setRealtime(null);
    };
  }, [api, me, inboxes, realtimeUrl, loadList]);

  useEffect(() => {
    setItems(null);
    void loadList();
  }, [inboxId, view, archived, loadList]);

  // Heartbeat: re-send the selected availability every 30 s (and immediately when it changes).
  useEffect(() => {
    if (!me) return;
    const beat = () =>
      api.setPresence(availability).then(
        (next) => setHeartbeatAt(next.lastHeartbeatAt),
        (e) => setError(`Presence update failed: ${errorText(e)}`),
      );
    void beat();
    const timer = setInterval(() => void beat(), HEARTBEAT_MS);
    return () => clearInterval(timer);
  }, [api, me, availability]);

  const select = (id: string) => {
    setSelectedId(id);
    setItems((current) => current && current.map((c) => (c.id === id ? { ...c, unreadCount: 0 } : c)));
  };

  const onChanged = useCallback(
    (updated: ConversationView) => {
      setItems((current) =>
        current && current.some((c) => c.id === updated.id)
          ? current.map((c) => (c.id === updated.id ? { ...updated, unreadCount: 0 } : c))
          : current,
      );
      // Status/assignment changes can move the item between tabs.
      void loadList();
    },
    [loadList],
  );

  return (
    <div className="agent-layout">
      <section className="card" aria-labelledby="inbox-heading">
        <h2 id="inbox-heading">Conversations</h2>
        <PresenceControl
          availability={availability}
          lastHeartbeatAt={heartbeatAt}
          connection={connection}
          onChange={setAvailability}
        />
        {inboxes.length > 1 && (
          <select aria-label="Inbox" data-testid="inbox-select" value={inboxId ?? ''} onChange={(e) => setInboxId(e.target.value)}>
            {inboxes.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name}
              </option>
            ))}
          </select>
        )}
        {error && (
          <p className="error" role="alert" data-testid="agent-error">
            {error}
          </p>
        )}
        <InboxList
          items={items}
          selectedId={selectedId}
          view={view}
          archived={archived}
          onView={setView}
          onArchived={setArchived}
          onSelect={select}
        />
      </section>

      {selectedId && realtime ? (
        <ConversationPane key={selectedId} api={api} realtime={realtime} conversationId={selectedId} onChanged={onChanged} />
      ) : (
        <section className="card conversation">
          <h2>No conversation selected</h2>
        </section>
      )}
    </div>
  );
}

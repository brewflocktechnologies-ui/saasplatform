import { Centrifuge } from 'centrifuge';
import type { ChatApi } from './api';
import type { ChatEvent, DemoActorId } from './types';

export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline';

export const CONNECTION_LABEL: Record<ConnectionState, string> = {
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
};

export interface SubscribeHandlers {
  onEvent: (event: ChatEvent) => void;
  /** Fires on the first subscribe and after every resubscribe: reload history here to fill gaps. */
  onSubscribed?: () => void;
  onError?: (message: string) => void;
}

/**
 * Centrifugo client for one demo actor using the official `centrifuge` SDK. Tokens come only from the backend
 * (connection + per-channel subscription JWTs); clients never publish.
 */
export function createRealtime(options: {
  url: string;
  api: ChatApi;
  actorId: DemoActorId;
  onState?: (state: ConnectionState) => void;
}) {
  const { url, api, actorId, onState } = options;
  let everConnected = false;
  const client = new Centrifuge(url, {
    getToken: async () => (await api.connectionToken(actorId)).token,
  });
  client.on('connecting', () => onState?.(everConnected ? 'reconnecting' : 'connecting'));
  client.on('connected', () => {
    everConnected = true;
    onState?.('live');
  });
  client.on('disconnected', () => onState?.('offline'));

  return {
    connect: () => client.connect(),
    disconnect: () => client.disconnect(),
    /** @returns cleanup that unsubscribes and removes the subscription */
    subscribe(channel: string, handlers: SubscribeHandlers): () => void {
      const sub = client.newSubscription(channel, {
        getToken: async () => (await api.subscriptionToken(actorId, channel)).token,
      });
      sub.on('publication', (ctx) => handlers.onEvent(ctx.data as ChatEvent));
      sub.on('subscribed', () => handlers.onSubscribed?.());
      sub.on('error', (ctx) => handlers.onError?.(`Subscription ${channel}: ${ctx.error.message}`));
      // Terminal server rejections (e.g. 103 permission denied) arrive as 'unsubscribed'; code 0 = our own cleanup.
      sub.on('unsubscribed', (ctx) => {
        if (ctx.code !== 0) handlers.onError?.(`Subscription ${channel} rejected: ${ctx.reason} (${ctx.code})`);
      });
      sub.subscribe();
      return () => {
        sub.unsubscribe();
        client.removeSubscription(sub);
      };
    },
  };
}

export type Realtime = ReturnType<typeof createRealtime>;

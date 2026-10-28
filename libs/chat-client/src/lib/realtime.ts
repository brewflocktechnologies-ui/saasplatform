import { Centrifuge } from 'centrifuge';
import type { ChatEvent } from './types';

export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline';

export const CONNECTION_LABEL: Record<ConnectionState, string> = {
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
};

export interface SubscribedContext {
  /** True when the subscription is trying to recover missed publications from the Centrifugo history. */
  wasRecovering: boolean;
  recovered: boolean;
}

export interface SubscribeHandlers {
  onEvent: (event: ChatEvent) => void;
  /** Fires on the first subscribe and after every resubscribe; the sync always reconciles from the API here. */
  onSubscribed?: (context: SubscribedContext) => void;
  onError?: (message: string) => void;
}

/**
 * Centrifugo client for one principal: the backend issues the connection token and one exact-channel subscription token;
 * browsers never publish. A server-side rejection arrives as `unsubscribed` (e.g. code 103), not `error`.
 */
export function createRealtime(options: {
  url: string;
  getConnectionToken: () => Promise<string>;
  getSubscriptionToken: (channel: string) => Promise<string>;
  onState?: (state: ConnectionState) => void;
}) {
  const { url, getConnectionToken, getSubscriptionToken, onState } = options;
  let everConnected = false;
  const client = new Centrifuge(url, { getToken: getConnectionToken });
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
      const sub = client.newSubscription(channel, { getToken: () => getSubscriptionToken(channel) });
      sub.on('publication', (ctx) => handlers.onEvent(ctx.data as ChatEvent));
      sub.on('subscribed', (ctx) =>
        handlers.onSubscribed?.({ wasRecovering: ctx.wasRecovering, recovered: ctx.recovered }),
      );
      sub.on('error', (ctx) => handlers.onError?.(`Subscription ${channel}: ${ctx.error.message}`));
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

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { ApiError, createChatApi, mergeMessages, type ChatMessage, type MessageCreatedEvent } from '../index';

const msg = (id: string, createdAt: string, body = id): ChatMessage => ({
  id,
  conversationId: '019a0000-0000-7000-8000-000000000001',
  senderId: 'userone',
  senderRole: 'VISITOR',
  body,
  createdAt,
});

describe('mergeMessages', () => {
  it('deduplicates by id (HTTP response + WebSocket event) and keeps server order', () => {
    const a = msg('a', '2026-10-25T10:00:00Z');
    const b = msg('b', '2026-10-25T10:00:01Z');
    const merged = mergeMessages([b], [a, b, a]);
    expect(merged.map((m) => m.id)).toEqual(['a', 'b']);
  });

  it('breaks createdAt ties by id like the history endpoint', () => {
    const t = '2026-10-25T10:00:00Z';
    expect(mergeMessages([], [msg('z', t), msg('m', t)]).map((m) => m.id)).toEqual(['m', 'z']);
  });
});

describe('createChatApi', () => {
  it('surfaces the problem-details detail on 4xx', async () => {
    const fetchImpl = vi.fn(async () =>
      new Response(JSON.stringify({ title: 'Bad Request', status: 400, detail: 'text must not be empty' }), {
        status: 400,
      }),
    );
    const api = createChatApi('http://api.test', fetchImpl as typeof fetch);
    await expect(api.sendMessage('c1', 'userone', ' ')).rejects.toEqual(new ApiError(400, 'text must not be empty'));
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://api.test/api/v1/demo/conversations/c1/messages',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ actorId: 'userone', text: ' ' }) }),
    );
  });

  it('maps network failures to status 0', async () => {
    const api = createChatApi('http://api.test', (() => Promise.reject(new TypeError('fetch failed'))) as typeof fetch);
    await expect(api.listConversations('agentbob')).rejects.toMatchObject({ status: 0 });
  });

  it('encodes channel names in subscription-token requests', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ token: 't', expiresAt: 'x' })));
    await createChatApi('http://api.test', fetchImpl as typeof fetch).subscriptionToken('agentbob', 'chat:inbox');
    expect(fetchImpl.mock.calls[0][0]).toBe(
      'http://api.test/api/v1/demo/realtime/subscription-token?actorId=agentbob&channel=chat%3Ainbox',
    );
  });
});

describe('event types match the committed JSON Schemas', () => {
  const dir = join(import.meta.dirname, '../../../contracts/json-schema/chat');
  const ajv = new Ajv2020({ strict: true });
  addFormats(ajv);
  for (const f of readdirSync(dir)) ajv.addSchema(JSON.parse(readFileSync(join(dir, f), 'utf8')));

  it('a message.created built from the client types validates', () => {
    const event: MessageCreatedEvent = {
      type: 'message.created',
      conversationId: '019a0000-0000-7000-8000-000000000001',
      message: { ...msg('019a0000-0000-7000-8000-0000000000aa', '2026-10-25T10:00:00Z', 'hi') },
    };
    const validate = ajv.getSchema('https://schemas.chat-platform.local/chat/demo-message-created.v1.schema.json');
    expect(validate?.(event), JSON.stringify(validate?.errors)).toBe(true);
    expect(validate?.({ ...event, message: { ...event.message, body: '' } })).toBe(false);
  });
});

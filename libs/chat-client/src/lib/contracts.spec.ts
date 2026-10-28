import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import type {
  ConversationCreatedEvent,
  ConversationUpdatedEvent,
  MessageCreatedEvent,
  TypingEvent,
} from '../index';

const BASE = 'https://schemas.chat-platform.local/chat/';
const dir = join(import.meta.dirname, '../../../contracts/json-schema/chat');
const ajv = new Ajv2020({ strict: true });
addFormats(ajv);
for (const file of readdirSync(dir)) {
  ajv.addSchema(JSON.parse(readFileSync(join(dir, file), 'utf8')));
}
const schema = (name: string) => {
  const validate = ajv.getSchema(`${BASE}${name}`);
  if (!validate) throw new Error(`schema ${name} is not registered`);
  return validate;
};

const message = {
  id: '019a1b2c-3d4e-7f80-9a1b-2c3d4e5f6071',
  conversationId: '019a1b2c-0000-7000-8000-000000000001',
  sequence: 1,
  senderType: 'CONTACT',
  senderId: '019a1b2c-0000-7000-8000-000000000003',
  messageType: 'TEXT',
  body: 'Hello!',
  clientMessageId: '0f4a2d1c-1b1e-4c8e-9d3a-1f2b3c4d5e6f',
  createdAt: '2026-10-27T12:00:00Z',
  editedAt: null,
  deletedAt: null,
};

const conversation = {
  id: '019a1b2c-0000-7000-8000-000000000001',
  tenantId: 'tenant-local',
  inboxId: '019a1b2c-0000-7000-8000-000000000002',
  contactId: '019a1b2c-0000-7000-8000-000000000003',
  contactDisplayName: 'User One',
  status: 'OPEN',
  version: 1,
  lastSequence: 1,
  lastMessageAt: '2026-10-27T12:00:00Z',
  lastMessagePreview: 'Hello!',
  assignment: { state: 'REQUESTED', agentId: null },
  resolvedAt: null,
  archivedAt: null,
  purgedAt: null,
  createdAt: '2026-10-27T11:59:00Z',
  updatedAt: '2026-10-27T12:00:00Z',
};

const envelope = (eventType: string, data: unknown) => ({
  eventId: '019a1b2c-0000-7000-8000-000000000010',
  eventType,
  schemaVersion: 1,
  tenantId: 'tenant-local',
  conversationId: '019a1b2c-0000-7000-8000-000000000001',
  occurredAt: '2026-10-27T12:00:00Z',
  data,
});

const expectValid = (name: string, value: unknown) => {
  const validate = schema(name);
  expect(validate(value), JSON.stringify(validate.errors)).toBe(true);
};

describe('client event fixtures match the committed schemas (R14, R15, R-P23, R-P29)', () => {
  it('message.created', () => {
    const event: MessageCreatedEvent = envelope('message.created', { message }) as MessageCreatedEvent;

    expectValid('message-created.v1.schema.json', event);
    expect(schema('message-created.v1.schema.json')({ ...event, data: { message: { ...message, body: '' } } })).toBe(false);
  });

  it('conversation.created carries the two-key assignment and no per-caller fields', () => {
    const event: ConversationCreatedEvent = envelope('conversation.created', {
      conversation,
    }) as ConversationCreatedEvent;

    expectValid('conversation-created.v1.schema.json', event);
    expect(
      schema('conversation-created.v1.schema.json')(
        envelope('conversation.created', { conversation: { ...conversation, unreadCount: 0 } }),
      ),
    ).toBe(false);
  });

  it('conversation.updated accepts every documented change value and rejects routing details in the assignment', () => {
    const changes = [
      'ASSIGNED',
      'RESOLVED',
      'REOPENED',
      'PENDING',
      'ARCHIVED',
      'UNARCHIVED',
      'ASSIGNMENT_QUEUED',
      'ASSIGNMENT_FAILED',
      'ASSIGNMENT_UPDATED',
    ];
    for (const change of changes) {
      const event: ConversationUpdatedEvent = envelope('conversation.updated', {
        conversation,
        change,
      }) as ConversationUpdatedEvent;
      expectValid('conversation-updated.v1.schema.json', event);
    }
    expect(schema('conversation-updated.v1.schema.json')(envelope('conversation.updated', { conversation, change: 'BOGUS' }))).toBe(
      false,
    );
    expect(
      schema('conversation-updated.v1.schema.json')(
        envelope('conversation.updated', {
          conversation: { ...conversation, assignment: { ...conversation.assignment, reason: 'sweep' } },
          change: 'ASSIGNED',
        }),
      ),
    ).toBe(false);
  });

  it('typing is ephemeral and carries no message body', () => {
    const event: TypingEvent = envelope('typing', {
      participantType: 'CONTACT',
      participantId: '019a1b2c-0000-7000-8000-000000000003',
      typing: true,
    }) as TypingEvent;

    expectValid('typing.v1.schema.json', event);
    expect(schema('typing.v1.schema.json')(envelope('typing', { participantType: 'CONTACT', typing: true }))).toBe(false);
  });

  it('rejects an envelope without the idempotency eventId', () => {
    const { eventId: _omitted, ...withoutId } = envelope('message.created', { message });

    expect(schema('event-envelope.v1.schema.json')(withoutId)).toBe(false);
  });
});

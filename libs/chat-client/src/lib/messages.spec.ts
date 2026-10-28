import { mergeMessages, type MessageView } from '../index';

const message = (sequence: number, id = `m-${sequence}`): MessageView => ({
  id,
  conversationId: 'c1',
  sequence,
  senderType: 'CONTACT',
  senderId: 'k',
  messageType: 'TEXT',
  body: `body ${sequence}`,
  clientMessageId: null,
  createdAt: `2026-10-27T10:00:0${sequence}Z`,
  editedAt: null,
  deletedAt: null,
});

describe('mergeMessages', () => {
  it('deduplicates by id across the HTTP response and a live event', () => {
    const merged = mergeMessages([message(2)], [message(1), message(2, 'm-2'), message(1)]);

    expect(merged.map((m) => m.id)).toEqual(['m-1', 'm-2']);
  });

  it('orders by sequence, not by wall clock', () => {
    const early = { ...message(1), createdAt: '2026-10-27T10:00:09Z' };
    const late = { ...message(2), createdAt: '2026-10-27T10:00:00Z' };

    expect(mergeMessages([], [late, early]).map((m) => m.sequence)).toEqual([1, 2]);
  });

  it('breaks sequence ties by id', () => {
    expect(mergeMessages([], [message(1, 'm-z'), message(1, 'm-a')]).map((m) => m.id)).toEqual(['m-a', 'm-z']);
  });
});

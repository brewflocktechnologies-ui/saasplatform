import { createPendingQueue, type MessageView } from '../index';

const sent = (clientMessageId: string, sequence = 1): MessageView => ({
  id: `m-${sequence}`,
  conversationId: 'c1',
  sequence,
  senderType: 'CONTACT',
  senderId: 'k1',
  messageType: 'TEXT',
  body: 'hi',
  clientMessageId,
  createdAt: '2026-10-27T10:00:00Z',
  editedAt: null,
  deletedAt: null,
});

describe('createPendingQueue', () => {
  it('assigns a stable clientMessageId when enqueuing', async () => {
    const send = vi.fn(async (clientMessageId: string) => sent(clientMessageId));
    const queue = createPendingQueue({ send, newId: () => 'fixed-id' });

    const item = queue.enqueue('hello');
    await vi.waitFor(() => expect(queue.list()[0].state).toBe('sent'));

    expect(item.clientMessageId).toBe('fixed-id');
    expect(send).toHaveBeenCalledWith('fixed-id', 'hello');
  });

  it('retries a failed send with the same clientMessageId', async () => {
    const send = vi
      .fn<(clientMessageId: string, body: string) => Promise<MessageView>>()
      .mockRejectedValueOnce(new Error('timeout'))
      .mockResolvedValueOnce(sent('fixed-id'));
    const queue = createPendingQueue({ send, newId: () => 'fixed-id' });
    queue.enqueue('hello');
    await vi.waitFor(() => expect(queue.list()[0].state).toBe('failed'));

    queue.retry('fixed-id');
    await vi.waitFor(() => expect(queue.list()[0].state).toBe('sent'));

    expect(send.mock.calls.map((call) => call[0])).toEqual(['fixed-id', 'fixed-id']);
    expect(queue.list()[0].messageId).toBe('m-1');
  });

  it('keeps the body and the error on a failed send', async () => {
    const queue = createPendingQueue({
      send: async () => {
        throw new Error('offline');
      },
      newId: () => 'fixed-id',
    });

    queue.enqueue('hello');
    await vi.waitFor(() => expect(queue.list()[0].state).toBe('failed'));

    expect(queue.list()[0]).toMatchObject({ body: 'hello', state: 'failed', error: 'unexpected error' });
  });
});

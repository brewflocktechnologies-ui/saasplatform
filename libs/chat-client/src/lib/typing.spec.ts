import { createTypingEmitter, createTypingReceiver } from '../index';

describe('createTypingEmitter', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('emits at most one typing:true signal per interval', () => {
    const send = vi.fn(async () => undefined);
    const emitter = createTypingEmitter({ send, intervalMs: 2000, idleMs: 3000 });

    emitter.onInput();
    emitter.onInput();
    vi.advanceTimersByTime(1000);
    emitter.onInput();
    expect(send.mock.calls.filter(([typing]) => typing === true)).toHaveLength(1);

    vi.advanceTimersByTime(1000);
    emitter.onInput();
    expect(send.mock.calls.filter(([typing]) => typing === true)).toHaveLength(2);
  });

  it('sends a stop signal when input goes idle', () => {
    const send = vi.fn(async () => undefined);
    const emitter = createTypingEmitter({ send, intervalMs: 2000, idleMs: 3000 });

    emitter.onInput();
    vi.advanceTimersByTime(3000);

    expect(send.mock.calls.at(-1)).toEqual([false]);
  });

  it('stops after the message is sent', () => {
    const send = vi.fn(async () => undefined);
    const emitter = createTypingEmitter({ send, intervalMs: 2000, idleMs: 3000 });

    emitter.onInput();
    emitter.onSent();

    expect(send.mock.calls.at(-1)).toEqual([false]);
  });
});

describe('createTypingReceiver', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('clears the indicator after the timeout even when the stop signal is lost', () => {
    const onChange = vi.fn();
    const receiver = createTypingReceiver({ onChange, timeoutMs: 5000 });

    receiver.handle({ data: { typing: true } } as never);
    expect(onChange).toHaveBeenLastCalledWith(true);

    vi.advanceTimersByTime(5000);
    expect(onChange).toHaveBeenLastCalledWith(false);
  });
});

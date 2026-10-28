import type { TypingEvent } from './types';

type Timer = (callback: () => void, ms: number) => unknown;
type ClearTimer = (handle: unknown) => void;

const defaultSetTimeout: Timer = (callback, ms) => setTimeout(callback, ms);
const defaultClearTimeout: ClearTimer = (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>);

/** Emits `typing: true` once per interval while the user types and a stop signal after the idle window. */
export function createTypingEmitter(options: {
  send: (typing: boolean) => Promise<void>;
  intervalMs?: number;
  idleMs?: number;
  now?: () => number;
  setTimer?: Timer;
  clearTimer?: ClearTimer;
}) {
  const intervalMs = options.intervalMs ?? 2000;
  const idleMs = options.idleMs ?? 3000;
  const now = options.now ?? (() => Date.now());
  const setTimer = options.setTimer ?? defaultSetTimeout;
  const clearTimer = options.clearTimer ?? defaultClearTimeout;
  let lastSentAt = Number.NEGATIVE_INFINITY;
  let active = false;
  let idleHandle: unknown;

  function send(typing: boolean): void {
    void options.send(typing).catch(() => {
      // best effort: a failed typing signal never surfaces to the visitor
    });
  }

  function stop(): void {
    if (idleHandle !== undefined) {
      clearTimer(idleHandle);
      idleHandle = undefined;
    }
    if (active) {
      active = false;
      send(false);
    }
  }

  return {
    onInput(): void {
      const at = now();
      if (!active || at - lastSentAt >= intervalMs) {
        active = true;
        lastSentAt = at;
        send(true);
      }
      if (idleHandle !== undefined) {
        clearTimer(idleHandle);
      }
      idleHandle = setTimer(() => {
        idleHandle = undefined;
        stop();
      }, idleMs);
    },
    onSent(): void {
      stop();
    },
    stop,
  };
}

/** Clears the other side's indicator after a timeout, also when the stop signal is lost. */
export function createTypingReceiver(options: {
  onChange: (typing: boolean) => void;
  timeoutMs?: number;
  setTimer?: Timer;
  clearTimer?: ClearTimer;
}) {
  const timeoutMs = options.timeoutMs ?? 5000;
  const setTimer = options.setTimer ?? defaultSetTimeout;
  const clearTimer = options.clearTimer ?? defaultClearTimeout;
  let handle: unknown;

  function clear(): void {
    if (handle !== undefined) {
      clearTimer(handle);
      handle = undefined;
    }
  }

  return {
    handle(event: TypingEvent): void {
      clear();
      if (event.data.typing) {
        options.onChange(true);
        handle = setTimer(() => {
          handle = undefined;
          options.onChange(false);
        }, timeoutMs);
      }
      else {
        options.onChange(false);
      }
    },
    reset(): void {
      clear();
      options.onChange(false);
    },
  };
}

/**
 * Hermes (iOS / Android) lacks some DOM event classes that msw's core extends at import
 * time (rettime's TypedEvent extends MessageEvent; ws.mjs opens a BroadcastChannel). Mock mode only: mock-fetch.ts imports
 * this before msw. Each class is defined only when the runtime does not have it, and only
 * with the members msw's in-process getResponse path touches.
 */
type Listener = (event: Event) => void;

const g = globalThis as Record<string, unknown>;

if (typeof g.Event === 'undefined') {
  class EventPolyfill {
    readonly type: string;
    defaultPrevented = false;
    readonly cancelable: boolean;
    constructor(type: string, init: { cancelable?: boolean } = {}) {
      this.type = type;
      this.cancelable = init.cancelable ?? false;
    }
    preventDefault(): void {
      if (this.cancelable) this.defaultPrevented = true;
    }
    stopPropagation(): void {}
    stopImmediatePropagation(): void {}
  }
  g.Event = EventPolyfill;
}

if (typeof g.EventTarget === 'undefined') {
  class EventTargetPolyfill {
    #listeners = new Map<string, Set<Listener>>();
    addEventListener(type: string, listener: Listener): void {
      const set = this.#listeners.get(type) ?? new Set<Listener>();
      set.add(listener);
      this.#listeners.set(type, set);
    }
    removeEventListener(type: string, listener: Listener): void {
      this.#listeners.get(type)?.delete(listener);
    }
    dispatchEvent(event: Event): boolean {
      for (const listener of this.#listeners.get(event.type) ?? [])
        listener(event);
      return !event.defaultPrevented;
    }
  }
  g.EventTarget = EventTargetPolyfill;
}

if (typeof g.MessageEvent === 'undefined') {
  const Base = g.Event as typeof Event;
  class MessageEventPolyfill extends Base {
    readonly data: unknown;
    constructor(
      type: string,
      init: { data?: unknown; cancelable?: boolean } = {},
    ) {
      super(type, init);
      this.data = init.data;
    }
  }
  g.MessageEvent = MessageEventPolyfill;
}

/* msw's WebSocket module opens a BroadcastChannel at import; in-process mocks never post to
   it, so a channel that delivers nothing is enough. */
if (typeof g.BroadcastChannel === 'undefined') {
  const Target = g.EventTarget as typeof EventTarget;
  class BroadcastChannelPolyfill extends Target {
    readonly name: string;
    onmessage: ((event: MessageEvent) => void) | null = null;
    constructor(name: string) {
      super();
      this.name = name;
    }
    postMessage(): void {}
    close(): void {}
  }
  g.BroadcastChannel = BroadcastChannelPolyfill;
}

export {};

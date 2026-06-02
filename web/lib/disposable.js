export class DisposableStore {
  constructor() {
    this.disposables = new Set();
    this.disposed = false;
  }

  add(disposable) {
    if (!disposable) return disposable;
    const normalized = normalizeDisposable(disposable);
    if (this.disposed) {
      safeDispose(normalized);
      return disposable;
    }
    const wrapper = {
      dispose: () => {
        this.disposables.delete(wrapper);
        safeDispose(normalized);
      }
    };
    this.disposables.add(wrapper);
    return wrapper;
  }

  addEventListener(target, type, listener, options) {
    if (!target?.addEventListener) return null;
    target.addEventListener(type, listener, options);
    const disposable = {
      dispose: () => target.removeEventListener(type, listener, options),
    };
    this.add(disposable);
    return disposable;
  }

  addTimeout(id, clear = clearTimeout) {
    return this.add({ dispose: () => clear(id) });
  }

  setTimeout(callback, delay) {
    let disposable;
    const id = setTimeout(() => {
      try {
        callback();
      } finally {
        if (disposable) disposable.dispose();
      }
    }, delay);
    disposable = this.add({ dispose: () => clearTimeout(id) });
    return disposable;
  }

  get size() {
    return this.disposables.size;
  }

  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    for (const disposable of [...this.disposables].reverse()) {
      safeDispose(disposable);
    }
    this.disposables.clear();
  }
}

export function toDisposable(fn) {
  return { dispose: fn };
}

function normalizeDisposable(disposable) {
  if (typeof disposable === "function") return toDisposable(disposable);
  if (typeof disposable.dispose === "function") return disposable;
  throw new TypeError("disposable must be a function or expose dispose()");
}

function safeDispose(disposable) {
  try {
    disposable.dispose();
  } catch {
    // Disposal should be best-effort so cleanup can continue.
  }
}

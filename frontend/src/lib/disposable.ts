export interface IDisposable {
  dispose(): void;
}

export class DisposableStore implements IDisposable {
  private disposables = new Set<IDisposable>();
  private disposed = false;

  add<T extends IDisposable | (() => void)>(disposable: T): IDisposable {
    if (!disposable) return { dispose: () => {} };
    const normalized = normalizeDisposable(disposable);
    if (this.disposed) {
      safeDispose(normalized);
      return { dispose: () => {} };
    }
    const wrapper: IDisposable = {
      dispose: () => {
        this.disposables.delete(wrapper);
        safeDispose(normalized);
      }
    };
    this.disposables.add(wrapper);
    return wrapper;
  }

  addEventListener(
    target: EventTarget | null | undefined,
    type: string,
    listener: EventListenerOrEventListenerObject,
    options?: boolean | AddEventListenerOptions
  ): IDisposable | null {
    if (!target?.addEventListener) return null;
    target.addEventListener(type, listener, options);
    const disposable = {
      dispose: () => target.removeEventListener(type, listener, options),
    };
    this.add(disposable);
    return disposable;
  }

  addTimeout(id: any, clear = clearTimeout): IDisposable {
    return this.add({ dispose: () => clear(id) });
  }

  setTimeout(callback: () => void, delay: number): IDisposable {
    let disposable: IDisposable;
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

  get size(): number {
    return this.disposables.size;
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const disposable of Array.from(this.disposables).reverse()) {
      safeDispose(disposable);
    }
    this.disposables.clear();
  }
}

export function toDisposable(fn: () => void): IDisposable {
  return { dispose: fn };
}

function normalizeDisposable(disposable: any): IDisposable {
  if (typeof disposable === "function") return toDisposable(disposable);
  if (disposable && typeof disposable.dispose === "function") return disposable;
  throw new TypeError("disposable must be a function or expose dispose()");
}

function safeDispose(disposable: IDisposable): void {
  try {
    disposable.dispose();
  } catch {
    // Disposal should be best-effort so cleanup can continue.
  }
}

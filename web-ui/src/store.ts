import { useSyncExternalStore } from "react";

// Minimal observable snapshot store ("client-store" idea from dsh): state is
// immutable snapshots; subscribers receive the new snapshot after each update.

export interface Store<T> {
  get(): T;
  set(patch: Partial<T> | ((current: T) => Partial<T>)): void;
  subscribe(listener: () => void): () => void;
}

export function createStore<T>(initial: T): Store<T> {
  let state = initial;
  const listeners = new Set<() => void>();
  return {
    get: () => state,
    set(patch) {
      const partial =
        typeof patch === "function" ? (patch as (current: T) => Partial<T>)(state) : patch;
      const next = { ...state, ...partial };
      if (next === state) return;
      state = next;
      for (const listener of [...listeners]) listener();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export function useSnapshot<T>(store: Store<T>): T {
  return useSyncExternalStore(store.subscribe, store.get, store.get);
}

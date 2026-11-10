/**
 * The snapshot store engine — upstream `packages/client/store` at the pinned
 * commit (`src/index.ts` + `src/contract.ts`, two files).
 *
 * Upstream ships this as a React-free package consumed by the renderer's slot
 * machinery; this project has no slots, so the parts that only exist to serve
 * that machinery are cut (recorded in `webui/UPSTREAM.md`):
 *
 * - `PropsStore`/`StoreDecl`/`HandleOf`/`BoundActions`/`StoreFactory` — the
 *   registration-side types that describe how a slot terminal declares a store.
 *   With no slots, nothing declares one.
 * - `MaybeSnapshotSelectorHook` — the no-session selector shape of the slot
 *   props. Its stated purpose (keeping hook call sites stable across
 *   no-session/session transitions) does not arise here: this app's stores are
 *   module-level singletons read through `useStore` on a session that always
 *   exists.
 * - The `persist` machinery on `createSnapshotStore`/`defineStore` — kept, but
 *   see the note on the persistence key below.
 * - The ui-slots re-export block at the top of upstream's `index.ts` — those
 *   names live in `./contract.ts` here, which is the single import path.
 *
 * `createSnapshotStore` is kept verbatim: `ResourceRegistry` depends on its
 * exact semantics (immer `produce` for `update`, whole-value `set`, the
 * `subscribeWithSelector` middleware). The `flush: 'raf'` branch is kept even
 * though no store here asks for it — it is upstream's own batching contract and
 * removing it would silently change any future store that opts in.
 *
 * React binding: upstream synthesizes the selector hook in ui-renderer. Here
 * that binding is `src/store/useStore.ts`, one `useSyncExternalStore` bridge.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import { subscribeWithSelector } from 'zustand/middleware'
import { shallow } from 'zustand/shallow'
import { freeze, produce } from 'immer'

/** Minimal observable snapshot source shared by controllers, stores, and render adapters. */
export interface ObservableSnapshot<T> {
  /** Read the cached snapshot reference. */
  getSnapshot(): T
  /**
   * Subscribe to snapshot invalidation.
   * @param fn - invalidation callback.
   * @returns unsubscribe function.
   */
  subscribe(fn: () => void): () => void
}

/**
 * Typed selector hook over a snapshot source. Canonical shape for the whole
 * slot system (ui-renderer's engine hook is structurally identical; the
 * framework is the only party that ever constructs one).
 */
export type SnapshotSelectorHook<T> = <S>(sel: (s: T) => S, eq?: (a: S, b: S) => boolean) => S

/**
 * Action declaration table: pure immer-draft transforms over the store state,
 * declared as the store's complete write set (the audit face — components can
 * only write through these).
 */
/* oxlint-disable-next-line typescript/no-explicit-any --
 * any[] (not unknown[]): each action carries its own parameter list, and
 * unknown[] would reject every concrete signature under strict parameter
 * contravariance. Params are re-inferred per action by BakedActions. */
export type ActionsDecl<T> = Record<string, (draft: T, ...params: any[]) => void>

/**
 * Draft-stripped callback form of an actions table: what components
 * (`props.actions`) and inject factories receive — the framework bakes the
 * draft parameter away by binding each action to the resolved instance.
 */
export type BakedActions<T, A extends ActionsDecl<T>> = {
  [K in keyof A]: A[K] extends (draft: T, ...params: infer P) => void ? (...params: P) => void : never
}

/**
 * Store declaration spec: initial-state factory (a lambda so every instance
 * gets a fresh state), optional persistence key (mechanical, framework-run),
 * and the actions write set.
 */
export interface StoreSpec<T, A extends ActionsDecl<T>> {
  init: () => T
  persist?: string
  actions: A
}

/**
 * Live engine instance: the create() product consumed by the render machinery
 * and by tests. A bare snapshot source plus the baked write set.
 */
export interface StoreInstance<T, A extends ActionsDecl<T>> {
  readonly actions: BakedActions<T, A>
  getSnapshot(): T
  /**
   * Subscribe to state changes (uSES subscribe side).
   * @param fn - change callback.
   * @returns unsubscribe.
   */
  subscribe(fn: () => void): () => void
  /**
   * Explicitly drop this instance's persisted value.
   * Runtime scope teardown does not call this operation.
   */
  clearPersisted(): void
}

/**
 * Store handle: spec + state/actions types + shared identity + instance
 * factory in one value. Never export a handle at module level — module-cache
 * identity is a disguised singleton across plugin reloads.
 */
export interface StoreHandle<T, A extends ActionsDecl<T>> {
  readonly spec: StoreSpec<T, A>
  /**
   * Create a live engine instance (framework machinery and tests only).
   * @param scopeKey - session id for session-scope instances; suffixes the
   * persist key so per-session instances persist independently (root-scope
   * instances omit it).
   * @returns a fresh instance seeded from `spec.init()`.
   */
  create(scopeKey?: string): StoreInstance<T, A>
}

/** Writable snapshot store (bare data face). */
export interface SnapshotStore<T> extends ObservableSnapshot<T> {
  /**
   * Mutate the state through an immer draft.
   * @param mutator - draft mutator.
   */
  update(mutator: (draft: T) => void): void
  /**
   * Replace the state wholesale.
   * @param next - next state.
   */
  set(next: T): void
}

/**
 * Notify an observer set without allowing one callback to starve the rest.
 * @param listeners - current observer callbacks; copied before dispatch.
 * @param label - diagnostic owner prefix.
 * @param args - callback arguments.
 */
export function notifySubscribers<Args extends readonly unknown[]>(
  listeners: Iterable<(...args: Args) => void>,
  label: string,
  ...args: Args
): void {
  for (const listener of [...listeners]) {
    try {
      listener(...args)
    } catch (error) {
      console.error(`${label} subscriber failed:`, error)
    }
  }
}

/**
 * Shallow equality for selector slices (zustand/shallow semantics; travels
 * with the engine so hook consumers need no zustand dependency).
 * @param a - left value.
 * @param b - right value.
 * @returns whether the values are shallowly equal.
 */
export function shallowEqual(a: unknown, b: unknown): boolean {
  return shallow(a, b)
}

/** Batches subscriber notification into one flush per animation frame. */
function rafBatch(notify: () => void): () => void {
  // Fall back to microtask batching where rAF is absent (node unit tests);
  // both preserve the N-changes=1-notification contract within a tick.
  const schedule: (fn: () => void) => void =
    typeof requestAnimationFrame === 'function'
      ? (fn) => { requestAnimationFrame(() => { fn() }) }
      : (fn) => { queueMicrotask(fn) }
  let scheduled = false
  return () => {
    if (scheduled) return
    scheduled = true
    schedule(() => {
      scheduled = false
      notify()
    })
  }
}

/**
 * Create a snapshot store.
 *
 * Flush default is 'sync' (controlled inputs need same-tick echo); frame-driven
 * stores opt into 'raf', where a frame's worth of updates coalesces into one
 * notification. Known raf-mode tradeoff: a component mounting mid-frame reads
 * fresh state while existing subscribers hear it next flush — transient
 * frame-level skew, same nature as the object layer's microtask batching.
 *
 * @param init - initial state.
 * @param opts - flush mode and opt-in persistence (localStorage, keyed by name).
 * @returns the store.
 */
export function createSnapshotStore<T>(
  init: T, opts?: { flush?: 'raf' | 'sync'; persist?: { name: string } }): SnapshotStore<T> {
  // Immer enters through produce() in update() below (identical semantics to
  // the immer middleware without its setState-signature mutator generics).
  const withSelector = subscribeWithSelector(() => init)
  const api: StoreApi<T> = createStore<T>()(withSelector)
  if (opts?.persist) attachPersistence(api, opts.persist.name)

  let subscribe = (fn: () => void) => api.subscribe(() => {
    notifySubscribers([fn], '[client-store]')
  })
  if (opts?.flush === 'raf') {
    const listeners = new Set<() => void>()
    const flush = rafBatch(() => { notifySubscribers(listeners, '[client-store]') })
    api.subscribe(flush)
    subscribe = (fn: () => void) => {
      listeners.add(fn)
      return () => { listeners.delete(fn) }
    }
  }

  return {
    getSnapshot: () => api.getState(),
    subscribe: fn => subscribe(fn),
    update: (mutator) => {
      // Immer's produce (not setState's partial-merge path) so scalar and
      // array roots replace correctly; produce also freezes in dev.
      api.setState(produce(api.getState(), (draft) => { mutator(draft as T) }), true)
    },
    set: (next) => {
      api.setState(devFreeze(next), true)
    },
  }
}

/**
 * Whole-value JSON persistence to localStorage. Hand-rolled instead of the
 * zustand persist middleware: its write path spreads state into an object
 * (`partialize({ ...get() })`), exploding primitive state (a persisted string
 * draft becomes {0:'h',1:'e',...}) — not fixable via merge/deserialize options
 * because the corruption happens before serialization. Storage failures
 * (quota, private mode) only disable persistence, never break the store.
 */
function attachPersistence<T>(api: StoreApi<T>, name: string): void {
  // Non-browser runs (node e2e booting the client tree) have no localStorage:
  // persistence silently disables — same contract as a storage failure, minus
  // the per-store console noise a ReferenceError would produce.
  if (typeof localStorage === 'undefined') return
  try {
    const raw = localStorage.getItem(name)
    if (raw !== null) {
      api.setState(devFreeze(JSON.parse(raw) as T), true)
    }
  } catch (error) {
    console.error(`snapshot store '${name}' rehydration failed:`, error)
  }
  api.subscribe((state) => {
    try {
      localStorage.setItem(name, JSON.stringify(state))
    } catch (error) {
      console.error(`snapshot store '${name}' persistence failed:`, error)
    }
  })
}

/**
 * Deep-freeze draftable wholesale-set state outside production: set() bypasses
 * immer's freeze.
 *
 * Upstream reads `process.env.NODE_ENV`, which is the Node/bundler convention.
 * This project builds through Vite and carries no node type definitions, so the
 * same test is written as `import.meta.env.PROD` — Vite substitutes it at build
 * time and it is `false` under vitest, matching upstream's dev behaviour (freeze
 * on, so a mutation of published state throws in tests exactly as it does
 * upstream).
 */
function devFreeze<T>(value: T): T {
  if (import.meta.env.PROD) return value
  return freeze(value, true)
}

/** A live engine instance: the contract instance plus the raw engine store. */
export interface EngineStoreInstance<T, A extends ActionsDecl<T>> extends StoreInstance<T, A> {
  /** The underlying engine store (framework/test API; components never see it). */
  readonly store: SnapshotStore<T>
}

/** The engine-backed handle: create() narrowed to the engine instance. */
export interface EngineStoreHandle<T, A extends ActionsDecl<T>> extends StoreHandle<T, A> {
  /**
   * Construct a live engine instance.
   * @param scopeKey - session id for session-scope instances; omitted for root scope.
   * @returns the engine instance.
   */
  create(scopeKey?: string): EngineStoreInstance<T, A>
}

/**
 * Declare a store: initial state, optional persistence, and the full write
 * set as pure draft mutators.
 *
 * The `A & ActionsDecl<T>` actions position is load-bearing: T resolves from
 * `init` in the first inference round, and the intersection then contextually
 * types each mutator's draft parameter (context-sensitive functions defer),
 * so call sites write `(d, x: X) => { ... }` with no draft annotation.
 * @param decl - init lambda (fresh state per instance), optional persist key, actions table.
 * @returns the store handle.
 */
export function defineStore<T, A extends ActionsDecl<T>>(
  decl: StoreSpec<T, A> & { actions: A & ActionsDecl<T> }): EngineStoreHandle<T, A> {
  return {
    spec: decl,
    create(scopeKey?: string): EngineStoreInstance<T, A> {
      const persistKey = decl.persist === undefined
        ? undefined
        : scopeKey === undefined ? decl.persist : `${decl.persist}.${scopeKey}`
      const store = createSnapshotStore<T>(
        decl.init(),
        persistKey !== undefined ? { persist: { name: persistKey } } : undefined)
      const actions = {} as Record<string, (...params: unknown[]) => void>
      for (const key of Object.keys(decl.actions)) {
        const mutate = decl.actions[key] as (draft: T, ...params: unknown[]) => void
        actions[key] = (...params: unknown[]) => { store.update((draft) => { mutate(draft, ...params) }) }
      }
      return {
        actions: actions as BakedActions<T, A>,
        getSnapshot: () => store.getSnapshot(),
        subscribe: fn => store.subscribe(fn),
        store,
        clearPersisted: () => {
          if (persistKey === undefined || typeof localStorage === 'undefined') return
          try {
            localStorage.removeItem(persistKey)
          } catch {
            // Storage failures (private mode, quota teardown races) only skip
            // cleanup — the same non-fatal contract as attachPersistence.
          }
        },
      }
    },
  }
}
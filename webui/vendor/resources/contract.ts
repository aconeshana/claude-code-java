/**
 * The resource model's published face — upstream `packages/client/resources`
 * at the pinned commit (`src/client/contract.ts`), reduced.
 *
 * A resource is one address, and a resource address is a
 * `dsh-resource://<type>/…` URL: the host names the protocol. The protocol's
 * owning client package registers one {@link ResourceProvider} that turns an
 * address into a frame stream, and any component reads that stream through
 * {@link UseResource}. A protocol that needs a scope (a session, a workspace)
 * encodes it in the path, as `dsh-resource://file/session/<sessionId>/<absolute
 * path>` does; the model itself knows only addresses. Addresses under any other
 * scheme (`sidebar://guide`) are navigation addresses and name no resource.
 *
 * Cut from upstream's file (recorded in `webui/UPSTREAM.md`):
 *
 * - `declare module '@deepseek-ai/cordis'` — the service merge that put
 *   `resources` on the context. There is no context here; `ResourceRegistry`
 *   is constructed by the app and reached as a module singleton.
 * - `declare module '@deepseek-ai/dsh-client-ui-slots'` — the slot-prop merge
 *   that put `useResource` on every slot component's props.
 * - `ResourceProtocolMap` and the `ResourceProtocol` extraction — upstream's
 *   declaration-merged roster of protocol → value type, which exists so a
 *   *slot* can name a protocol as a type argument without importing its owner's
 *   runtime. With no slots and two protocols this buys nothing; the frame value
 *   is typed at each provider instead (see {@link ResourceSnapshot}).
 *
 * Kept verbatim: every status, every field, and the whole provider contract —
 * including the rule that a failure is always a frame and a throw inside the
 * stream is a programming error that is not caught. That rule is what
 * `ResourceRegistry.consume` is built around and the reason a provider must not
 * throw.
 */

/**
 * Where one resource stands. `none`: no provider is registered for the
 * address's protocol, or the address is not a resource address. `loading`: a
 * provider is open and has not yielded yet. `live`: `value` is the latest `ok`
 * frame's value. `failed`: the latest frame reported a failure.
 */
export type ResourceStatus = 'none' | 'loading' | 'live' | 'failed'

/** One address's current state, as the resource hook returns it. */
export interface ResourceSnapshot<Value = unknown> {
  readonly status: ResourceStatus
  /** The latest `ok` frame's value; kept through a later failure frame, absent before the first. */
  readonly value: Value | undefined
  /** The latest frame's failure; present only while `status` is `failed`. */
  readonly failure: ResourceFailure | undefined
}

/**
 * Upstream's frame error is `RemoteFailure` from its RPC protocol package,
 * which this project does not vendor. Only `message` and `code` are ever read
 * by a consumer here, so the shape is declared locally with exactly those
 * fields rather than pulling in the transport's whole result algebra.
 */
export interface ResourceFailure {
  readonly code: string
  readonly message: string
}

/**
 * One frame of a provider's stream: either a value or a failure. Upstream's
 * `RemoteResult<T>` discriminated union, reduced to the two cases the stream
 * carries.
 */
export type ResourceFrame<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: ResourceFailure }

/** What a provider's `open` receives beside the address. */
export interface ResourceOpenContext {
  /** Aborted when the last subscriber or pin releases the resource; the stream must end. */
  readonly signal: AbortSignal
}

/** One protocol's provider, registered through {@link ResourceRegistry.register}. */
export interface ResourceProvider<Value = unknown> {
  /** The URL scheme this provider serves (the address's host). */
  readonly protocol: string
  /**
   * Open one frame stream for an address. The first frame is the current
   * content and every later frame one change. An `ok` frame replaces the value;
   * a failure frame marks the resource `failed` with its error and keeps the
   * last value. Ending the stream keeps the last state. A failure is always a
   * frame: a throw inside the stream is a programming error and is not caught.
   * @param address - the full address, a `dsh-resource://<type>/…` URL.
   * @param ctx - the stream's abort signal.
   * @returns the frame stream; it must stop once `ctx.signal` aborts.
   */
  open(address: string, ctx: ResourceOpenContext): AsyncIterable<ResourceFrame<Value>>
}

/**
 * The resource registry. One resource is one address; it stays open while at
 * least one `source` subscriber or one pin holds it, and the provider's stream
 * is aborted and the state discarded when the last holder releases.
 */
export interface Resources {
  /**
   * Register the provider for one protocol.
   * @param provider - the protocol's provider.
   * @returns idempotent disposer.
   * @throws when the protocol already has a provider.
   */
  register(provider: ResourceProvider): () => void
  /**
   * Hold one resource open without subscribing to it.
   * @param address - the full address, a `dsh-resource://<type>/…` URL.
   * @param signal - aborting it releases the pin; an already-aborted signal pins nothing.
   */
  pin(address: string, signal: AbortSignal): void
  /**
   * The live source of one resource. Reference-stable for one address while
   * the resource is held; the first subscriber or pin opens the provider's
   * stream, and a subscriber arriving later reads the latest value at once.
   * @param address - the full address, a `dsh-resource://<type>/…` URL.
   * @returns the observable state; `getSnapshot` reads without holding the resource.
   */
  source(address: string): ObservableResourceSource
}

/** The observable state of one address, as `source()` returns it. */
export interface ObservableResourceSource {
  getSnapshot(): ResourceSnapshot
  subscribe(listener: () => void): () => void
}
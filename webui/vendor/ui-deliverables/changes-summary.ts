/**
 * Fetch-once cache of the change summaries the client serves.
 *
 * Upstream `packages/client/ui-deliverables/src/client/changes-summary.ts` at
 * the pinned commit, with one change: the summary URL is built from
 * `(sessionId, turn)` rather than upstream's `(sessionId, seq)`, because this
 * app serves a turn-addressed summary endpoint instead of reading one off the
 * announcing `workspace/changes` event — see the header note in `changes.ts`.
 *
 * The policy is upstream's, with one change this app's transport forces: a
 * `401` decodes to a failed state a later read replaces, where upstream's
 * `retryable: () => false` keeps it. Upstream's Host authorizes the read with
 * credentials the browser already carries, so a failed read there is final;
 * here the credential is a launch token the tab captures from its landing URL,
 * and a tab loaded before the gateway was rebound carries the previous
 * launch's token. Keeping its 401 as `missing` made "this tab has not picked
 * up the token yet" indistinguishable from "this turn changed no files", and
 * the card stayed absent until the tab was reloaded. Non-auth answers keep
 * upstream's rule: a `missing` answer is kept for the connection.
 *
 * Recorded in `webui/UPSTREAM.md`.
 */
import { changesSummaryUrl, isChangesSummary, type ChangesSummary } from './changes.ts'
import { HostReadStore } from './host-read-store.ts'

/** A summary, `'missing'` once the client no longer serves it, `'error'` for a failed read, or `'loading'` while the request runs. */
export type ChangesSummaryState = ChangesSummary | 'missing' | 'error' | 'loading'

/** One client's summary reads; a summary or a missing answer is kept until the connection is replaced, a failed read is read again. */
export class ChangesSummaryStore extends HostReadStore<ChangesSummaryState> {
  constructor() {
    super({
      loading: 'loading',
      failed: 'error',
      retryable: state => state === 'error',
      decode: async (response) => {
        if (response.status === 401) return 'error'
        if (!response.ok) return 'missing'
        const value: unknown = await response.json()
        return isChangesSummary(value) ? value : 'missing'
      },
    })
  }

  /**
   * Read one summary once; a later read of the same coordinates returns the cached state.
   * @param sessionId - viewed Session.
   * @param turn - the 1-based turn number.
   * @returns after the state is published.
   */
  load(sessionId: string, turn: number): Promise<void> {
    return this.loadUrl(changesSummaryUrl(sessionId, turn))
  }
}
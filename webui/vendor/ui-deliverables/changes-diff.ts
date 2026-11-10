/**
 * Cache of the file comparisons the client serves for listed changed files,
 * read once per comparison and again after a failure.
 *
 * Upstream `packages/client/ui-deliverables/src/client/changes-diff.ts` at the
 * pinned commit, with one change: the comparison URL is built from
 * `(sessionId, path)` rather than upstream's `(sessionId, seq, index)` — see the
 * header note in `changes.ts`. The policy is upstream's verbatim, including the
 * asymmetry that makes a failed read the one state a later request replaces
 * while a decoded comparison and a `missing` answer are both kept.
 */
import { changesDiffUrl, isChangesDiff, type ChangesDiff } from './changes.ts'
import { HostReadStore } from './host-read-store.ts'

/**
 * A comparison, `'missing'` once the client no longer serves it, `'error'` for
 * a failed read a later request retries, or `'loading'` while the request runs.
 */
export type ChangesDiffState = ChangesDiff | 'missing' | 'error' | 'loading'

/** One client's comparison reads; a failed read is the one state a later request replaces. */
export class ChangesDiffStore extends HostReadStore<ChangesDiffState> {
  constructor() {
    super({
      loading: 'loading',
      failed: 'error',
      retryable: state => state === 'error',
      decode: async (response) => {
        if (response.status === 404) return 'missing'
        // 401 already falls through to 'error' here; the summary store's
        // policy needed the same answer without its own 404 branch. See the
        // header note in `changes-summary.ts` for why an auth failure is not
        // kept as final.
        if (!response.ok) return 'error'
        const value: unknown = await response.json()
        return isChangesDiff(value) ? value : 'error'
      },
    })
  }

  /**
   * Read one comparison; a cached comparison or a missing one is kept, a failed one is read again.
   * @param sessionId - viewed Session.
   * @param path - the file's workspace-relative path.
   * @returns after the state is published.
   */
  load(sessionId: string, path: string): Promise<void> {
    return this.loadUrl(changesDiffUrl(sessionId, path))
  }
}
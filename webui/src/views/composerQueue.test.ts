import { beforeEach, describe, expect, it } from 'vitest'
import {
  QUEUED_SESSION_KEY,
  QUEUE_STORAGE_KEY,
  clearQueuedSubmission,
  readQueuedSubmission,
  storeQueuedSubmission,
} from './composerQueue'

/**
 * The queue rides localStorage so a queued-but-unsent submission survives a
 * reload (the page unloads while a turn runs). jsdom provides real storage.
 */
describe('composer queue persistence', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('round-trips a queued submission for its session', () => {
    storeQueuedSubmission('sess-a', 'hello')

    expect(readQueuedSubmission('sess-a')).toBe('hello')
  })

  it('returns null for a different session (a stale queue never crosses sessions)', () => {
    storeQueuedSubmission('sess-a', 'hello')

    expect(readQueuedSubmission('sess-b')).toBeNull()
  })

  it('round-trips content blocks, not just plain text', () => {
    const blocks = [{ type: 'text' as const, text: 'see this' }]
    storeQueuedSubmission('sess-a', blocks)

    expect(readQueuedSubmission('sess-a')).toEqual(blocks)
  })

  it('clears the queue for its session only', () => {
    storeQueuedSubmission('sess-a', 'a')
    storeQueuedSubmission('sess-b', 'b')

    clearQueuedSubmission('sess-a')

    expect(readQueuedSubmission('sess-a')).toBeNull()
    expect(readQueuedSubmission('sess-b')).toBe('b')
  })

  it('survives a store reload (read creates a fresh store view)', () => {
    storeQueuedSubmission('sess-a', 'hello')
    // Simulated reload: the module-level state must not be the only home.
    localStorage.setItem(QUEUE_STORAGE_KEY, localStorage.getItem(QUEUE_STORAGE_KEY) ?? '')

    expect(readQueuedSubmission('sess-a')).toBe('hello')
  })

  it('ignores a malformed stored payload', () => {
    localStorage.setItem(QUEUE_STORAGE_KEY, JSON.stringify({ no: 'session map' }))

    expect(readQueuedSubmission('sess-a')).toBeNull()
  })

  it('keys queued rows per session, never one shared row', () => {
    storeQueuedSubmission('sess-a', 'a')
    storeQueuedSubmission('sess-b', 'b')

    expect(localStorage.getItem(QUEUE_STORAGE_KEY)).toBeNull()
    expect(QUEUED_SESSION_KEY('sess-a')).not.toBe(QUEUED_SESSION_KEY('sess-b'))
  })
})

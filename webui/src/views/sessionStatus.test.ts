import { describe, expect, it } from 'vitest'
import { sessionStatus, showSessionStatus } from './SessionRows'
import type { CatalogSession } from '../api/types'
import type { SessionActivity } from '../store/sessions'

/**
 * The row's status dot, reduced from upstream's `sessionStatuses`. The
 * precedence is the whole point: a session waiting on the user outranks a
 * running turn, which outranks an unread completion, which outranks idle.
 * Upstream additionally hides the dot entirely for idle — the one deviation
 * being this port's grey anchor on the TUI's own session.
 */

function session(overrides: Partial<CatalogSession> = {}): CatalogSession {
  return {
    id: 's1',
    summary: 'a',
    message_count: 1,
    modified_at: '2026-09-08',
    git_branch: null,
    cwd: '/repo',
    custom_title: null,
    first_prompt: null,
    active: false,
    headless_open: false,
    ...overrides,
  }
}

const RUNNING: SessionActivity = { running: true, completed: false }
const COMPLETED: SessionActivity = { running: false, completed: true }
const IDLE: SessionActivity = { running: false, completed: false }

describe('sessionStatus', () => {
  it('prefers a pending approval over a running turn', () => {
    expect(sessionStatus(RUNNING, 'approval').state).toBe('warning')
  })

  it('prefers a pending question over a running turn', () => {
    expect(sessionStatus(RUNNING, 'question').state).toBe('warning')
  })

  it('distinguishes the two pending kinds by label', () => {
    expect(sessionStatus(undefined, 'approval').labelKey).toBe('status.waitingApproval')
    expect(sessionStatus(undefined, 'question').labelKey).toBe('status.waitingAnswer')
  })

  it('reports a running turn while both flags are set', () => {
    // `turn.started` clears the reminder, but a frame ordering that left both
    // set must still render running.
    expect(sessionStatus({ running: true, completed: true })).toEqual({
      state: 'ongoing', labelKey: 'status.running',
    })
  })

  it('reports an unread completion', () => {
    expect(sessionStatus(COMPLETED)).toEqual({ state: 'done', labelKey: 'status.completed' })
  })

  it('reports idle for a session the stream has said nothing about', () => {
    expect(sessionStatus(undefined)).toEqual({ state: 'idle', labelKey: 'status.idle' })
  })

  it('reports idle for an explicitly idle session', () => {
    expect(sessionStatus(IDLE).state).toBe('idle')
  })
})

describe('showSessionStatus', () => {
  it('hides the dot for an idle session', () => {
    expect(showSessionStatus(session(), IDLE)).toBe(false)
  })

  it('hides the dot for an unobserved session', () => {
    expect(showSessionStatus(session(), undefined)).toBe(false)
  })

  it('shows the dot while a turn runs', () => {
    expect(showSessionStatus(session(), RUNNING)).toBe(true)
  })

  it('shows the dot for an unread completion', () => {
    expect(showSessionStatus(session(), COMPLETED)).toBe(true)
  })

  it('shows the dot for a pending interaction', () => {
    expect(showSessionStatus(session(), undefined, 'approval')).toBe(true)
  })

  it('shows a grey dot on the TUI session while it is idle', () => {
    // The one deviation from upstream's "idle draws nothing": this sidebar is
    // a window onto a TUI, so the TUI's own session needs to stay locatable.
    expect(showSessionStatus(session({ active: true }), IDLE)).toBe(true)
    expect(sessionStatus(IDLE).state).toBe('idle')
  })

  it('does not treat an open headless row as active', () => {
    // `headless_open` says the row is routable, never that a turn runs — the
    // confusion that made every open session permanently green.
    expect(showSessionStatus(session({ headless_open: true }), undefined)).toBe(false)
  })
})
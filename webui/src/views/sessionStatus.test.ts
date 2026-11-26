import { describe, expect, it } from 'vitest'
import { sessionStatus, showSessionStatus } from './SessionRows'
import type { CatalogSession } from '../api/types'
import type { SessionActivity } from '../store/sessions'

/**
 * The row's status dot, matching upstream's `sessionStatuses`. The precedence
 * is: a session waiting on the user outranks a running turn, which outranks an
 * unread completion, which outranks idle. Idle hides the dot entirely so there
 * is no grey idle dot and no Web/TUI ownership tinting (TUI rows already carry
 * a dedicated `TUI` badge).
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
    interactive: true,
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
  it('hides the dot when idle, regardless of Web or TUI ownership', () => {
    expect(showSessionStatus(session(), IDLE)).toBe(false)
    expect(showSessionStatus(session(), undefined)).toBe(false)
    expect(showSessionStatus(session({ remote: true }), undefined)).toBe(false)
    expect(showSessionStatus(session({ headless_open: true }), undefined)).toBe(false)
  })

  it('shows the dot for running, unread completion, and pending interaction', () => {
    expect(showSessionStatus(session(), RUNNING)).toBe(true)
    expect(showSessionStatus(session(), COMPLETED)).toBe(true)
    expect(showSessionStatus(session(), undefined, 'approval')).toBe(true)
    expect(showSessionStatus(session(), undefined, 'question')).toBe(true)
  })
})

import { describe, expect, it } from 'vitest'
import { sessionStatus, showSessionStatus, statusState } from './SessionRows'
import localCss from './Sidebar.module.css'
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
  it('draws the dot on every row, idle included', () => {
    // The tint, not the dot's presence, names a row's owner — so an idle row
    // must still carry one or its ownership would be invisible.
    expect(showSessionStatus(session(), IDLE)).toBe(true)
    expect(showSessionStatus(session(), undefined)).toBe(true)
    expect(showSessionStatus(session(), RUNNING)).toBe(true)
    expect(showSessionStatus(session(), COMPLETED)).toBe(true)
    expect(showSessionStatus(session(), undefined, 'approval')).toBe(true)
  })

  it('does not treat an open headless row as running', () => {
    // `headless_open` says the row is routable, never that a turn runs — the
    // confusion that made every open session permanently green.
    expect(sessionStatus(undefined).state).toBe('idle')
    expect(statusState(session({ headless_open: true }), false))
      .toBe(localCss.stateWeb)
  })
})

describe('statusState', () => {
  it('tints a row this browser drives as the web session', () => {
    // Nothing on the wire says "opened from here" — a row the gateway serves
    // that the terminal is not on is this browser's own session.
    expect(statusState(session(), false)).toBe(localCss.stateWeb)
  })

  it('tints a session the terminal hosts as the TUI session', () => {
    expect(statusState(session({ remote: true }), false)).toBe(localCss.stateTerminal)
  })

  it('puts the row being read above ownership', () => {
    // The row the user has open must stay findable even when the terminal
    // hosts it.
    expect(statusState(session({ remote: true }), true)).toBe(localCss.stateCurrent)
  })

  it('leaves activity out of the tint, so running keeps its ownership color', () => {
    // A running turn is the state's business (`ongoing` picks the chase
    // matrix); the class only names the owner. Folding `running` in here would
    // repaint a running web row as something that is not web.
    expect(statusState(session(), false)).toBe(localCss.stateWeb)
    expect(statusState(session({ remote: true }), false)).toBe(localCss.stateTerminal)
  })
})

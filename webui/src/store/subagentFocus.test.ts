import { beforeEach, describe, expect, it } from 'vitest'
import {
  dropForeignSubagentFocus,
  subagentFocusSession,
  useSubagentFocus,
} from './subagentFocus'

beforeEach(() => {
  useSubagentFocus.getState().clear()
})

describe('subagentFocus', () => {
  it('publishes a request the addressed row can claim', () => {
    useSubagentFocus.getState().open('s1', 'agent-7')
    expect(useSubagentFocus.getState().pending).toEqual({ agentId: 'agent-7' })
    expect(useSubagentFocus.getState().claim('agent-7')).toBe(true)
  })

  it('keeps the mark standing after the request is claimed', () => {
    // The catalog's check reads `current`, not `pending`. If it read the
    // request, the check would erase itself at the very moment it became
    // correct — the row claims the request in order to open.
    useSubagentFocus.getState().open('s1', 'agent-7')
    expect(useSubagentFocus.getState().current).toBe('agent-7')
    useSubagentFocus.getState().claim('agent-7')
    expect(useSubagentFocus.getState().pending).toBeNull()
    expect(useSubagentFocus.getState().current).toBe('agent-7')
  })

  it('moves the mark to the newest pick', () => {
    useSubagentFocus.getState().open('s1', 'agent-7')
    useSubagentFocus.getState().claim('agent-7')
    useSubagentFocus.getState().open('s1', 'agent-9')
    expect(useSubagentFocus.getState().current).toBe('agent-9')
  })

  it('is destructive: one request expands exactly one row', () => {
    useSubagentFocus.getState().open('s1', 'agent-7')
    expect(useSubagentFocus.getState().claim('agent-7')).toBe(true)
    // A second row that happens to share the id (a remount, a duplicated
    // render) must not reopen what the reader already closed.
    expect(useSubagentFocus.getState().claim('agent-7')).toBe(false)
    expect(useSubagentFocus.getState().pending).toBeNull()
  })

  it('leaves a request addressed to another agent alone', () => {
    useSubagentFocus.getState().open('s1', 'agent-7')
    expect(useSubagentFocus.getState().claim('agent-9')).toBe(false)
    expect(useSubagentFocus.getState().pending).toEqual({ agentId: 'agent-7' })
  })

  it('replaces an unclaimed request rather than queueing it', () => {
    // Two catalog picks in a row address the newest one; a queue would expand
    // a row the reader has already moved past.
    useSubagentFocus.getState().open('s1', 'agent-7')
    useSubagentFocus.getState().open('s1', 'agent-9')
    expect(useSubagentFocus.getState().pending).toEqual({ agentId: 'agent-9' })
  })

  it('drops a request when the reader leaves its session', () => {
    useSubagentFocus.getState().open('s1', 'agent-7')
    dropForeignSubagentFocus('s2')
    expect(useSubagentFocus.getState().pending).toBeNull()
    // The mark is session-scoped too: an id that recurs in the session the
    // reader moved to must not inherit the check.
    expect(useSubagentFocus.getState().current).toBeNull()
    expect(subagentFocusSession()).toBeNull()
  })

  it('keeps a request that belongs to the session in view', () => {
    useSubagentFocus.getState().open('s1', 'agent-7')
    dropForeignSubagentFocus('s1')
    expect(useSubagentFocus.getState().pending).toEqual({ agentId: 'agent-7' })
  })

  it('never treats a session-less reader as foreign', () => {
    // The first render has no id yet; it must not wipe a request published
    // for the session the stream is about to select.
    useSubagentFocus.getState().open('s1', 'agent-7')
    dropForeignSubagentFocus(null)
    expect(useSubagentFocus.getState().pending).toEqual({ agentId: 'agent-7' })
  })
})
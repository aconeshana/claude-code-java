import { beforeEach, describe, expect, it } from 'vitest'
import type { MirrorFrame, SessionTaskBoard, SessionTaskBoardItem } from '../api/types'
import { useTaskBoard } from './taskBoard'

function item(id: string, status: SessionTaskBoardItem['status'], subject = id): SessionTaskBoardItem {
  return { id, subject, description: '', active_form: '', owner: '', status, blocks: [], blocked_by: [] }
}

function board(revision: number, tasks: readonly SessionTaskBoardItem[]): SessionTaskBoard {
  return { list_id: 'list-1', revision, hidden: tasks.length === 0, tasks }
}

function boardFrame(sessionId: string, value: SessionTaskBoard): MirrorFrame {
  return {
    event: 'task_board',
    id: 1,
    data: { session_id: sessionId, source: 'event', ...value },
  } as MirrorFrame
}

describe('task board mirror frames', () => {
  beforeEach(() => {
    useTaskBoard.setState({ board: null, sessionId: null })
  })

  it('applies a frame while following the active TUI session', () => {
    const value = board(1, [item('a', 'in_progress')])
    useTaskBoard.getState().applyFrame(boardFrame('tui-session', value))
    expect(useTaskBoard.getState().board?.tasks).toHaveLength(1)
  })

  it('applies a frame addressed to the session this store is bound to', () => {
    useTaskBoard.setState({ sessionId: 'sess-a' })
    useTaskBoard.getState().applyFrame(boardFrame('sess-a', board(1, [item('a', 'pending')])))
    expect(useTaskBoard.getState().board?.revision).toBe(1)
  })

  it('ignores a frame for a different session', () => {
    useTaskBoard.setState({ sessionId: 'sess-a', board: null })
    useTaskBoard.getState().applyFrame(boardFrame('sess-b', board(1, [item('a', 'pending')])))
    expect(useTaskBoard.getState().board).toBeNull()
  })

  it('ignores a frame of another event kind', () => {
    useTaskBoard.getState().applyFrame({
      event: 'session.idle', id: 2, data: { session_id: 'tui-session' },
    } as MirrorFrame)
    expect(useTaskBoard.getState().board).toBeNull()
  })

  // A revision is the complete list, so a fold replaces it outright rather
  // than merging item-wise — a task the model dropped must disappear.
  it('replaces the board wholesale rather than merging items', () => {
    useTaskBoard.getState().applyFrame(boardFrame('s', board(1, [item('a', 'pending'), item('b', 'pending')])))
    useTaskBoard.getState().applyFrame(boardFrame('s', board(2, [item('b', 'completed')])))
    const tasks = useTaskBoard.getState().board?.tasks ?? []
    expect(tasks.map(task => task.id)).toEqual(['b'])
    expect(tasks[0]?.status).toBe('completed')
  })
})
import { beforeEach, describe, expect, it } from 'vitest'
import { toast, useToast } from './toast'

beforeEach(() => {
  useToast.getState().clear()
})

describe('useToast queue', () => {
  it('enqueues and dismisses toasts in FIFO order and caps backlog at 4', () => {
    const id1 = toast.success('one')
    const id2 = toast.error('two')
    expect(useToast.getState().queue.map((t) => t.message)).toEqual(['one', 'two'])

    useToast.getState().dismiss(id1)
    expect(useToast.getState().queue.map((t) => t.id)).toEqual([id2])

    toast.info('three')
    toast.warning('four')
    toast.info('five')
    toast.info('six')
    expect(useToast.getState().queue).toHaveLength(4)
    expect(useToast.getState().queue.at(-1)?.message).toBe('six')
  })
})

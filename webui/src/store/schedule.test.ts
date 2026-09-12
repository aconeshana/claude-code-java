import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSchedule } from './schedule'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function errorResponse(status: number, message: string): Response {
  return new Response(
    JSON.stringify({ error: { type: 'api_error', message } }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function urlOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input.toString()
}

const LISTING = {
  tasks: [
    { id: 'seed00001', cron: '0 9 * * *', prompt: 'morning report', recurring: true, durable: true, created_at: 1_700_000_000_000 },
  ],
}

describe('schedule store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSchedule.setState({ tasks: [], loading: false, error: null })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('refresh populates the task list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(LISTING)))
    await useSchedule.getState().refresh()
    expect(useSchedule.getState().tasks).toEqual(LISTING.tasks)
    expect(useSchedule.getState().error).toBeNull()
  })

  it('refresh surfaces a failed fetch as a store error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, 'scheduling is not configured')))
    await useSchedule.getState().refresh()
    expect(useSchedule.getState().error).toBe('scheduling is not configured')
    expect(useSchedule.getState().tasks).toEqual([])
  })

  it('addTask posts the task body then refreshes the listing', async () => {
    const calls: string[] = []
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = urlOf(input)
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'POST') {
        expect(url).toBe('/api/schedule')
        expect(JSON.parse(init.body as string)).toEqual({
          cron: '*/5 * * * *',
          prompt: 'check the build',
          recurring: true,
          durable: false,
        })
        return Promise.resolve(jsonResponse({ id: 'abcd1234' }))
      }
      return Promise.resolve(jsonResponse({
        tasks: [...LISTING.tasks, {
          id: 'abcd1234', cron: '*/5 * * * *', prompt: 'check the build',
          recurring: true, durable: false, created_at: 1_700_000_000_001,
        }],
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSchedule.getState().addTask('*/5 * * * *', 'check the build', true, false, null)

    expect(calls).toEqual(['POST /api/schedule', 'GET /api/schedule'])
    expect(useSchedule.getState().tasks.map((task) => task.id)).toEqual(['seed00001', 'abcd1234'])
  })

  it('addTask threads the per-task model override into the POST body', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        expect(JSON.parse(init.body as string)).toEqual({
          cron: '0 9 * * *',
          prompt: 'morning report',
          recurring: true,
          durable: true,
          model: 'opus',
        })
        return Promise.resolve(jsonResponse({ id: 'abcd1234' }))
      }
      return Promise.resolve(jsonResponse(LISTING))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSchedule.getState().addTask('0 9 * * *', 'morning report', true, true, 'opus')

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(useSchedule.getState().error).toBeNull()
  })

  it('a rejected add reports failure so the caller keeps the draft input', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return Promise.resolve(errorResponse(400, 'Invalid cron expression'))
      return Promise.resolve(jsonResponse(LISTING))
    })
    vi.stubGlobal('fetch', fetchMock)

    const added = await useSchedule.getState().addTask('bad', 'x', false, false, null)

    expect(added).toBe(false)
    expect(useSchedule.getState().error).toBe('Invalid cron expression')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('removeTask deletes by id and updates the list in place', async () => {
    useSchedule.setState({ tasks: LISTING.tasks })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.method).toBe('DELETE')
      expect(urlOf(input)).toBe('/api/schedule/seed00001')
      return Promise.resolve(jsonResponse({ removed: true }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSchedule.getState().removeTask('seed00001')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(useSchedule.getState().tasks).toEqual([])
    expect(useSchedule.getState().error).toBeNull()
  })

  it('a failed remove keeps the task and surfaces the error', async () => {
    useSchedule.setState({ tasks: LISTING.tasks })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(404, 'unknown task: gone')))

    await useSchedule.getState().removeTask('gone')

    expect(useSchedule.getState().error).toBe('unknown task: gone')
    expect(useSchedule.getState().tasks).toEqual(LISTING.tasks)
  })

  it('a failed add surfaces the error without refreshing', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return Promise.resolve(errorResponse(500, 'cron parse failed'))
      return Promise.resolve(jsonResponse(LISTING))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useSchedule.getState().addTask('not a cron', 'x', false, false, null)

    expect(useSchedule.getState().error).toBe('cron parse failed')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

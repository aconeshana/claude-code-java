import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSpinnerVerbs } from './spinnerVerbs'
import type { SpinnerVerbsListing } from '../api/types'

function urlOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input.toString()
}

const LISTING: SpinnerVerbsListing = { active: ['Brewing', 'Zesting'], tools: { Read: 'Reading' } }

describe('spinnerVerbs store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useSpinnerVerbs.setState({ vocabulary: { active: [], tools: {} } })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('loads the vocabulary once and serves later loads from memory', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      expect(urlOf(input)).toBe('/api/spinner-verbs')
      return new Response(JSON.stringify(LISTING), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)

    await Promise.all([useSpinnerVerbs.getState().load(), useSpinnerVerbs.getState().load()])
    await useSpinnerVerbs.getState().load()

    expect(useSpinnerVerbs.getState().vocabulary).toEqual(LISTING)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('keeps the empty vocabulary on failure and retries on the next load', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('{}', { status: 500 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(LISTING), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await useSpinnerVerbs.getState().load()
    expect(useSpinnerVerbs.getState().vocabulary.active).toEqual([])

    await useSpinnerVerbs.getState().load()
    expect(useSpinnerVerbs.getState().vocabulary).toEqual(LISTING)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

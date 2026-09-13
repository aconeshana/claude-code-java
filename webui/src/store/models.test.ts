import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useModels } from './models'
import type { ModelsListing } from '../api/types'

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

const LISTING: ModelsListing = {
  models: [
    {
      model_name: 'seed-model', protocol: 'anthropic', base_url: 'https://api.example.com',
      has_api_key: true, headers: {},
    },
  ],
}

describe('models store', () => {
  beforeEach(() => {
    sessionStorage.setItem('gateway-token', 'fake-token')
    useModels.setState({ models: [], loading: false, error: null })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('refresh populates the model list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(LISTING)))
    await useModels.getState().refresh()
    expect(useModels.getState().models).toEqual(LISTING.models)
    expect(useModels.getState().error).toBeNull()
  })

  it('refresh surfaces a failed fetch as a store error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, 'models are not configured')))
    await useModels.getState().refresh()
    expect(useModels.getState().error).toBe('models are not configured')
    expect(useModels.getState().models).toEqual([])
  })

  it('save with an explicit api key sends it and applies the refreshed listing', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      expect(urlOf(input)).toBe('/api/models')
      expect(init?.method).toBe('POST')
      expect(JSON.parse(init?.body as string)).toEqual({
        model_name: 'new-model',
        protocol: 'chat',
        base_url: 'https://example.com/v1',
        api_key: 'sk-123',
        headers: {},
      })
      return Promise.resolve(jsonResponse({
        models: [...LISTING.models, {
          model_name: 'new-model', protocol: 'chat', base_url: 'https://example.com/v1',
          has_api_key: true, headers: {},
        }],
      }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useModels.getState().save({
      modelName: 'new-model', protocol: 'chat', baseUrl: 'https://example.com/v1',
      apiKey: 'sk-123', headers: {},
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(useModels.getState().models.map((m) => m.model_name)).toEqual(['seed-model', 'new-model'])
  })

  it('save with apiKey omitted does not send the api_key field', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(init?.body as string) as Record<string, unknown>
      expect('api_key' in body).toBe(false)
      return Promise.resolve(jsonResponse(LISTING))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useModels.getState().save({
      modelName: 'seed-model', protocol: 'anthropic', baseUrl: 'https://api.example.com',
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('save with apiKey null sends an explicit null to clear it', async () => {
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(init?.body as string) as Record<string, unknown>
      expect(body.api_key).toBeNull()
      return Promise.resolve(jsonResponse(LISTING))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useModels.getState().save({
      modelName: 'seed-model', protocol: 'anthropic', baseUrl: 'https://api.example.com',
      apiKey: null,
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('save serializes the multimodal tri-state: unset omits, null/false/true pass through', async () => {
    const seenBodies: Record<string, unknown>[] = []
    const fetchMock = vi.fn().mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
      seenBodies.push(JSON.parse(init?.body as string) as Record<string, unknown>)
      return Promise.resolve(jsonResponse(LISTING))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useModels.getState().save({
      modelName: 'seed-model', protocol: 'anthropic', baseUrl: 'https://api.example.com',
    })
    await useModels.getState().save({
      modelName: 'seed-model', protocol: 'anthropic', baseUrl: 'https://api.example.com',
      multimodal: null,
    })
    await useModels.getState().save({
      modelName: 'seed-model', protocol: 'anthropic', baseUrl: 'https://api.example.com',
      multimodal: false,
    })
    await useModels.getState().save({
      modelName: 'seed-model', protocol: 'anthropic', baseUrl: 'https://api.example.com',
      multimodal: true,
    })

    expect(fetchMock).toHaveBeenCalledTimes(4)
    // An unset flag keeps the existing server-side value (field omitted).
    expect('multimodal' in seenBodies[0]).toBe(false)
    expect(seenBodies[1].multimodal).toBeNull()
    expect(seenBodies[2].multimodal).toBe(false)
    expect(seenBodies[3].multimodal).toBe(true)
  })

  it('remove deletes by name and updates the list in place', async () => {
    useModels.setState({ models: LISTING.models })
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.method).toBe('DELETE')
      expect(urlOf(input)).toBe('/api/models/seed-model')
      return Promise.resolve(jsonResponse({ removed: true }))
    })
    vi.stubGlobal('fetch', fetchMock)

    await useModels.getState().remove('seed-model')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(useModels.getState().models).toEqual([])
    expect(useModels.getState().error).toBeNull()
  })

  it('a failed remove keeps the model and surfaces the error', async () => {
    useModels.setState({ models: LISTING.models })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(404, 'unknown model: gone')))

    await useModels.getState().remove('gone')

    expect(useModels.getState().error).toBe('unknown model: gone')
    expect(useModels.getState().models).toEqual(LISTING.models)
  })

  it('a failed save surfaces the error without clobbering the listing', async () => {
    useModels.setState({ models: LISTING.models })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(400, 'unknown model protocol: bogus')))

    await useModels.getState().save({
      modelName: 'x', protocol: 'anthropic', baseUrl: 'https://x',
    })

    expect(useModels.getState().error).toBe('unknown model protocol: bogus')
    expect(useModels.getState().models).toEqual(LISTING.models)
  })
})

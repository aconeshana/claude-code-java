import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('transcript view store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('defaults to compact when storage is empty', async () => {
    const { useTranscriptView } = await import('./transcriptView')
    expect(useTranscriptView.getState().mode).toBe('compact')
  })

  it('initializes from a persisted mode', async () => {
    localStorage.setItem('webui-transcript-view', 'normal')
    const { useTranscriptView } = await import('./transcriptView')
    expect(useTranscriptView.getState().mode).toBe('normal')
  })

  it('falls back to compact for a corrupt persisted value', async () => {
    localStorage.setItem('webui-transcript-view', 'ultra-wide')
    const { useTranscriptView } = await import('./transcriptView')
    expect(useTranscriptView.getState().mode).toBe('compact')
  })

  it('setMode persists and updates the store', async () => {
    const { useTranscriptView } = await import('./transcriptView')
    useTranscriptView.getState().setMode('normal')
    expect(useTranscriptView.getState().mode).toBe('normal')
    expect(localStorage.getItem('webui-transcript-view')).toBe('normal')
  })

  it('picks up a cross-tab write via the storage event', async () => {
    const { useTranscriptView } = await import('./transcriptView')
    localStorage.setItem('webui-transcript-view', 'normal')
    window.dispatchEvent(new StorageEvent('storage', { key: 'webui-transcript-view', newValue: 'normal' }))
    expect(useTranscriptView.getState().mode).toBe('normal')
  })

  it('ignores storage events for unrelated keys', async () => {
    const { useTranscriptView } = await import('./transcriptView')
    window.dispatchEvent(new StorageEvent('storage', { key: 'unrelated-key', newValue: 'normal' }))
    expect(useTranscriptView.getState().mode).toBe('compact')
  })
})

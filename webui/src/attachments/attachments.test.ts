import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  AttachmentError,
  MAX_FILE_BYTES,
  MAX_TOTAL_BYTES,
  buildContentBlocks,
  formatBytes,
  hasSubmittableContent,
  readAttachment,
} from './attachments'

// jsdom does not implement object URLs; the preview field only needs to be
// a distinguishable string on both ends.
const createObjectURL = vi.fn(() => `blob:mock-${createObjectURL.mock.calls.length}`)
URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL
const revokeObjectURL = vi.fn()
URL.revokeObjectURL = revokeObjectURL as unknown as typeof URL.revokeObjectURL

afterEach(() => {
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
})

function fileOf(name: string, type: string, bytes: number, fill = 0x61): File {
  return new File([new Uint8Array(bytes).fill(fill)], name, { type })
}

describe('readAttachment', () => {
  it('reads an image file into an image draft with a preview url', async () => {
    const draft = await readAttachment(fileOf('pic.png', 'image/png', 8), 0)
    expect(draft.isImage).toBe(true)
    expect(draft.mediaType).toBe('image/png')
    expect(draft.base64).toBe(btoa('aaaaaaaa')) // 8 × 'a'
    expect(draft.previewUrl).toMatch(/^blob:mock-/)
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
  })

  it('treats non-image media types as documents without a preview', async () => {
    const draft = await readAttachment(fileOf('notes.txt', 'text/plain', 3), 0)
    expect(draft.isImage).toBe(false)
    expect(draft.previewUrl).toBeNull()
  })

  it('defaults a blank media type to octet-stream', async () => {
    const draft = await readAttachment(fileOf('blob', '', 3), 0)
    expect(draft.mediaType).toBe('application/octet-stream')
    expect(draft.isImage).toBe(false)
  })

  it('rejects a file above the per-file cap', async () => {
    await expect(
      readAttachment(fileOf('big.png', 'image/png', MAX_FILE_BYTES + 1), 0),
    ).rejects.toThrow(AttachmentError)
  })

  it('rejects a file that would exceed the total-bytes cap', async () => {
    await expect(
      readAttachment(fileOf('two.bin', 'application/octet-stream', 10), MAX_TOTAL_BYTES - 5),
    ).rejects.toThrow(AttachmentError)
  })
})

describe('buildContentBlocks', () => {
  it('emits a single text block for plain text', () => {
    expect(buildContentBlocks('hello', [])).toEqual([{ type: 'text', text: 'hello' }])
  })

  it('omits the text block when only attachments are present', () => {
    const image = {
      id: 1, name: 'p.png', mediaType: 'image/png', isImage: true,
      bytes: 4, base64: 'QUJD', previewUrl: null,
    }
    expect(buildContentBlocks('', [image])).toEqual([
      { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'QUJD' } },
    ])
  })

  it('orders text first, then images, then documents', () => {
    const image = {
      id: 1, name: 'p.png', mediaType: 'image/png', isImage: true,
      bytes: 4, base64: 'QUJD', previewUrl: null,
    }
    const doc = {
      id: 2, name: 'r.pdf', mediaType: 'application/pdf', isImage: false,
      bytes: 4, base64: 'REVG', previewUrl: null,
    }
    expect(buildContentBlocks('look', [image, doc])).toEqual([
      { type: 'text', text: 'look' },
      { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'QUJD' } },
      { type: 'document', title: 'r.pdf', source: { type: 'base64', media_type: 'application/pdf', data: 'REVG' } },
    ])
  })
})

describe('hasSubmittableContent', () => {
  it('requires non-blank text or at least one attachment', () => {
    expect(hasSubmittableContent('hi', [])).toBe(true)
    expect(hasSubmittableContent('  ', [])).toBe(false)
    const doc = {
      id: 1, name: 'a', mediaType: 'text/plain', isImage: false,
      bytes: 1, base64: 'QQ==', previewUrl: null,
    }
    expect(hasSubmittableContent('', [doc])).toBe(true)
  })
})

describe('formatBytes', () => {
  it('formats bytes, kilobytes, and megabytes', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB')
  })
})

import type { UserContentBlock } from '../api/types'

/**
 * Composer attachment handling: File → validated in-memory draft →
 * Anthropic content blocks on submit.
 *
 * Images (png/jpeg/gif/webp) become inline base64 image blocks; anything
 * else becomes a document block the session owner persists to disk and
 * references by path. Limits mirror the TUI paste path's guards: a hard
 * byte cap per file and a total cap per turn.
 */

/** Hard per-file cap (5 MB decoded), matching conservative API limits. */
export const MAX_FILE_BYTES = 5 * 1024 * 1024
/** Total decoded bytes across one turn's attachments. */
export const MAX_TOTAL_BYTES = 20 * 1024 * 1024
/** Media types the API accepts as inline image blocks. */
const IMAGE_MEDIA_TYPES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp'])

export class AttachmentError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AttachmentError'
  }
}

/** One picked/pasted/dropped file, already read and validated. */
export interface DraftAttachment {
  readonly id: number
  readonly name: string
  readonly mediaType: string
  readonly isImage: boolean
  readonly bytes: number
  /** Base64 without the data-URL prefix; empty for oversized previews. */
  readonly base64: string
  /** object URL for image previews; null for documents. */
  readonly previewUrl: string | null
}

let nextAttachmentId = 1

/** Reads one picked/pasted/dropped file into a validated draft attachment. */
export async function readAttachment(
  file: File,
  alreadyAttachedBytes: number,
): Promise<DraftAttachment> {
  if (file.size > MAX_FILE_BYTES) {
    throw new AttachmentError(`文件过大（${formatBytes(file.size)}），单文件上限 ${formatBytes(MAX_FILE_BYTES)}`)
  }
  if (alreadyAttachedBytes + file.size > MAX_TOTAL_BYTES) {
    throw new AttachmentError(`附件总量超过 ${formatBytes(MAX_TOTAL_BYTES)} 上限`)
  }
  const mediaType = file.type === '' ? 'application/octet-stream' : file.type
  const buffer = await file.arrayBuffer()
  const base64 = arrayBufferToBase64(buffer)
  const isImage = IMAGE_MEDIA_TYPES.has(mediaType)
  return {
    id: nextAttachmentId++,
    name: file.name === '' ? 'attachment' : file.name,
    mediaType,
    isImage,
    bytes: file.size,
    base64,
    // Only images get a preview URL; the caller revokes it on removal.
    previewUrl: isImage ? URL.createObjectURL(file) : null,
  }
}

/** Extracts image files from a paste or drop event, ignoring plain text. */
export function filesFromTransfer(dataTransfer: DataTransfer | null): readonly File[] {
  if (dataTransfer == null) return []
  const files: File[] = []
  for (const item of dataTransfer.items) {
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (file != null) files.push(file)
    }
  }
  // Fallback for browsers without DataTransferItemList (or depleted lists).
  return files.length > 0 ? files : [...dataTransfer.files]
}

/** Builds the wire blocks for one turn: the text plus every attachment. */
export function buildContentBlocks(
  text: string,
  attachments: readonly DraftAttachment[],
): readonly UserContentBlock[] {
  const blocks: UserContentBlock[] = []
  if (text !== '') blocks.push({ type: 'text', text })
  for (const attachment of attachments) {
    if (attachment.isImage) {
      blocks.push({
        type: 'image',
        source: { type: 'base64', media_type: attachment.mediaType, data: attachment.base64 },
      })
    } else {
      blocks.push({
        type: 'document',
        ...(attachment.name == null ? {} : { title: attachment.name }),
        source: { type: 'base64', media_type: attachment.mediaType, data: attachment.base64 },
      })
    }
  }
  return blocks
}

/** True when a submit would carry something (text or attachments). */
export function hasSubmittableContent(
  text: string,
  attachments: readonly DraftAttachment[],
): boolean {
  return text.trim() !== '' || attachments.length > 0
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  // Chunked to stay under String.fromCharCode's argument cap.
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

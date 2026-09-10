import { useEffect } from 'react'
import { DocumentFileIcon, IconCloseFill14 } from '@primitives'
import { formatBytes, type DraftAttachment } from '../attachments/attachments'
import css from '@chat-styles/InputBar.module.css'

/**
 * The pending-attachment strip above the composer text area: one row of
 * thumbnail chips for picked/pasted/dropped files. Image chips show the
 * object-URL preview; document chips show the vendored document glyph.
 * Removal releases the preview URL.
 */
export function AttachmentStrip({ attachments, onRemove }: {
  attachments: readonly DraftAttachment[]
  onRemove: (id: number) => void
}) {
  if (attachments.length === 0) return null
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 8,
        padding: '0 12px',
      }}
      aria-label="待发送附件"
    >
      {attachments.map((attachment) => (
        <AttachmentChip
          key={attachment.id}
          attachment={attachment}
          onRemove={onRemove}
        />
      ))}
    </div>
  )
}

function AttachmentChip({ attachment, onRemove }: {
  attachment: DraftAttachment
  onRemove: (id: number) => void
}) {
  // object URLs are revoked when the chip unmounts (removal or submit-clear).
  useEffect(() => {
    return () => {
      if (attachment.previewUrl != null) URL.revokeObjectURL(attachment.previewUrl)
    }
  }, [attachment.previewUrl])

  return (
    <div
      style={{
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        maxWidth: 220,
        padding: '4px 8px',
        borderRadius: 8,
        background: 'var(--dsw-alias-interactive-bg-hover)',
      }}
    >
      {attachment.isImage && attachment.previewUrl != null ? (
        <img
          src={attachment.previewUrl}
          alt={attachment.name}
          style={{ width: 36, height: 36, borderRadius: 6, objectFit: 'cover' }}
        />
      ) : (
        <DocumentFileIcon />
      )}
      <span
        style={{
          display: 'flex',
          flexDirection: 'column',
          minWidth: 0,
          fontSize: 12,
          lineHeight: '16px',
          color: 'var(--dsw-alias-label-primary)',
        }}
      >
        <span
          style={{
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
          title={attachment.name}
        >
          {attachment.name}
        </span>
        <span style={{ color: 'var(--dsw-alias-label-tertiary)' }}>
          {formatBytes(attachment.bytes)}
        </span>
      </span>
      <button
        type="button"
        className={css.add}
        style={{
          width: 20,
          height: 20,
          marginLeft: 4,
          background: 'var(--dsw-alias-interactive-bg-hover-solid)',
        }}
        onClick={() => { onRemove(attachment.id) }}
        aria-label={`移除附件 ${attachment.name}`}
      >
        <IconCloseFill14 />
      </button>
    </div>
  )
}

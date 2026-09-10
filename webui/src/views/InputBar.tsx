import { useLayoutEffect, useRef, useState } from 'react'
import { IconPlusOutline16, IconSendOutline16 } from '@primitives'
import type { UserContentBlock } from '../api/types'
import {
  AttachmentError,
  buildContentBlocks,
  filesFromTransfer,
  hasSubmittableContent,
  readAttachment,
  type DraftAttachment,
} from '../attachments/attachments'
import { AttachmentStrip } from './AttachmentStrip'
import css from '@chat-styles/InputBar.module.css'

const MAX_INPUT_HEIGHT_PX = 200

/**
 * Composer over the vendored dsh InputBar card (22px radius, input-major
 * fill, soft elevation). v1 uses a plain textarea — no Lexical editor —
 * submitting on Enter (Shift+Enter for a newline). The send control reuses
 * the vendored .primary circle (34px, info-fill blue, white glyph) rather
 * than the generic ui-primitives Button, matching figma 34:10465.
 *
 * Attachments: the vendored .add circle (figma + control) opens a file
 * picker; image pastes onto the textarea and drops onto the card land in
 * the same pending strip. On submit the text and attachments become one
 * Anthropic content-block array (text + image/document base64 blocks).
 */
export function InputBar({ disabled, placeholder, onSubmit }: {
  disabled: boolean
  placeholder: string
  onSubmit: (content: string | readonly UserContentBlock[]) => void
}) {
  const [draft, setDraft] = useState('')
  const [attachments, setAttachments] = useState<readonly DraftAttachment[]>([])
  const [attachmentError, setAttachmentError] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useLayoutEffect(() => {
    // Plain <textarea> doesn't auto-grow; ConversationRoot's ResizeObserver
    // republishes --dsh-composer-height off this element's box, so the
    // back-to-bottom button's offset depends on this height tracking content.
    const el = textareaRef.current
    if (el == null) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, MAX_INPUT_HEIGHT_PX)}px`
  }, [draft])

  const addFiles = (files: readonly File[]): void => {
    if (files.length === 0) return
    const totalBytes = attachments.reduce((sum, a) => sum + a.bytes, 0)
    void (async () => {
      const added: DraftAttachment[] = []
      let runningBytes = totalBytes
      try {
        for (const file of files) {
          const draft = await readAttachment(file, runningBytes)
          added.push(draft)
          runningBytes += draft.bytes
        }
        setAttachments((current) => [...current, ...added])
        setAttachmentError(null)
      } catch (failure) {
        // Validation failures surface as a notice; earlier files stay added.
        releasePreviews(added)
        setAttachmentError(failure instanceof AttachmentError
          ? failure.message
          : '附件读取失败')
      }
    })()
  }

  const removeAttachment = (id: number): void => {
    setAttachments((current) => current.filter((a) => a.id !== id))
  }

  const submit = (): void => {
    if (disabled || !hasSubmittableContent(draft, attachments)) return
    const text = draft.trim()
    const content = attachments.length === 0
      ? text
      : buildContentBlocks(text, attachments)
    setDraft('')
    setAttachments([])
    onSubmit(content)
  }

  return (
    <div className={css.root}>
      <div
        className={css.card}
        onDragOver={(event) => {
          // Allow drops without navigating the browser to the file.
          if (event.dataTransfer.types.includes('Files')) event.preventDefault()
        }}
        onDrop={(event) => {
          if (!event.dataTransfer.types.includes('Files')) return
          event.preventDefault()
          addFiles(filesFromTransfer(event.dataTransfer))
        }}
      >
        {attachmentError != null && (
          <div className={css.notice} role="alert">{attachmentError}</div>
        )}
        <AttachmentStrip attachments={attachments} onRemove={removeAttachment} />
        <div className={css.scroll}>
          <textarea
            ref={textareaRef}
            className={css.input}
            style={{
              // The vendored .input styles the Lexical contenteditable; a
              // textarea needs the same box metrics spelled out.
              width: '100%',
              boxSizing: 'border-box',
              minHeight: 28,
              maxHeight: MAX_INPUT_HEIGHT_PX,
              resize: 'none',
              border: 'none',
              outline: 'none',
              background: 'transparent',
              fontFamily: 'inherit',
              whiteSpace: 'pre-wrap',
            }}
            rows={1}
            value={draft}
            placeholder={placeholder}
            disabled={disabled}
            onChange={(event) => { setDraft(event.target.value) }}
            onPaste={(event) => {
              const files = filesFromTransfer(event.clipboardData)
              if (files.length === 0) return
              // Image pastes never insert the file name as text.
              event.preventDefault()
              addFiles(files)
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault()
                submit()
              }
            }}
          />
        </div>
        <div className={css.row}>
          <div className={css.tools}>
            <button
              type="button"
              className={css.add}
              disabled={disabled}
              onClick={() => { fileInputRef.current?.click() }}
              aria-label="添加附件"
            >
              <IconPlusOutline16 />
            </button>
          </div>
          <div className={css.modes}>
            <span style={{ fontSize: 12, color: 'var(--dsw-alias-label-tertiary)' }}>
              Enter 发送 · Shift+Enter 换行
            </span>
          </div>
          <div className={css.trailing}>
            <button
              type="button"
              className={css.primary}
              disabled={disabled || !hasSubmittableContent(draft, attachments)}
              onClick={submit}
              aria-label="发送"
            >
              <IconSendOutline16 />
            </button>
          </div>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={(event) => {
            addFiles([...event.target.files ?? []])
            // Reset so picking the same file again re-fires change.
            event.target.value = ''
          }}
        />
      </div>
    </div>
  )
}

/** Revokes preview URLs for drafts that never made it into the strip. */
function releasePreviews(drafts: readonly DraftAttachment[]): void {
  for (const draft of drafts) {
    if (draft.previewUrl != null) URL.revokeObjectURL(draft.previewUrl)
  }
}

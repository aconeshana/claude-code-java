import { useCallback, useEffect, useRef, useState } from 'react'
import { LexicalComposer } from '@lexical/react/LexicalComposer'
import { PlainTextPlugin } from '@lexical/react/LexicalPlainTextPlugin'
import { ContentEditable } from '@lexical/react/LexicalContentEditable'
import { ClearEditorPlugin } from '@lexical/react/LexicalClearEditorPlugin'
import { HistoryPlugin } from '@lexical/react/LexicalHistoryPlugin'
import { OnChangePlugin } from '@lexical/react/LexicalOnChangePlugin'
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext'
import {
  $getRoot,
  CLEAR_EDITOR_COMMAND,
  COMMAND_PRIORITY_CRITICAL,
  KEY_ENTER_COMMAND,
  type EditorState,
  type LexicalEditor,
} from 'lexical'
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

/**
 * Composer over the vendored dsh InputBar card (22px radius, input-major
 * fill, soft elevation), built on Lexical plain-text editing: paragraphs are
 * <p> blocks exactly as the vendored .input CSS expects, Enter submits,
 * Shift+Enter breaks a line, and IME composition is protected on the keydown
 * edge. The send control reuses the vendored .primary circle (34px,
 * info-fill blue, white glyph) matching figma 34:10465.
 *
 * Attachments: the vendored .add circle (figma + control) opens a file
 * picker; image pastes onto the composer and drops onto the card land in the
 * same pending strip. On submit the draft text and attachments become one
 * Anthropic content-block array (text + image/document base64 blocks).
 */
export function InputBar({ disabled, placeholder, onSubmit }: {
  disabled: boolean
  placeholder: string
  onSubmit: (content: string | readonly UserContentBlock[]) => void
}) {
  const [attachments, setAttachments] = useState<readonly DraftAttachment[]>([])
  const [attachmentError, setAttachmentError] = useState<string | null>(null)
  const [draftText, setDraftText] = useState('')
  const editorRef = useRef<LexicalEditor | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

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

  const submit = useCallback((): void => {
    if (disabled || !hasSubmittableContent(draftText, attachments)) return
    const text = draftText.trim()
    const content = attachments.length === 0
      ? text
      : buildContentBlocks(text, attachments)
    setDraftText('')
    setAttachments([])
    onSubmit(content)
    editorRef.current?.dispatchCommand(CLEAR_EDITOR_COMMAND, undefined)
  }, [disabled, draftText, attachments, onSubmit])

  const onChange = useCallback((state: EditorState, editor: LexicalEditor) => {
    editorRef.current = editor
    state.read(() => {
      setDraftText($getRoot().getTextContent())
    })
  }, [])

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
        <LexicalComposer
          initialConfig={{
            namespace: 'ccj-composer',
            onError(error) { throw error },
          }}
        >
          <div className={css.grow}>
            <PlainTextPlugin
              contentEditable={
                <ContentEditable
                  className={css.input}
                  style={{
                    // The vendored .input styles the Lexical contenteditable;
                    // this keeps the textarea-era box metrics contract with
                    // ConversationRoot's ResizeObserver.
                    boxSizing: 'border-box',
                    minHeight: 28,
                  }}
                  ariaLabel="消息输入框"
                />
              }
              placeholder={<span className={css.placeholder}>{placeholder}</span>}
              ErrorBoundary={ComposerErrorBoundary}
            />
            <HistoryPlugin />
            <ClearEditorPlugin />
            <OnChangePlugin ignoreSelectionChange onChange={onChange} />
            <SubmitKeyPlugin disabled={disabled} onSubmit={submit} />
            <PasteFilesPlugin onFiles={addFiles} />
          </div>
        </LexicalComposer>
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
              disabled={disabled || !hasSubmittableContent(draftText, attachments)}
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

/** Lexical render crash surface: reraise so onError's contract holds. */
function ComposerErrorBoundary({ children }: { children: React.ReactNode }) {
  return <>{children}</>
}

/**
 * Enter submits (Shift+Enter inserts a newline via the default handler);
 * IME composition never submits. The keydown confirming an IME candidate is
 * also an Enter — submitting then would eat the candidate, so composing
 * events pass through untouched.
 */
function SubmitKeyPlugin({ disabled, onSubmit }: {
  disabled: boolean
  onSubmit: () => void
}) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    return editor.registerCommand(
      KEY_ENTER_COMMAND,
      (event: KeyboardEvent | null) => {
        if (event == null || disabled) return false
        if (event.isComposing || event.keyCode === 229) return false
        if (event.shiftKey) return false
        event.preventDefault()
        onSubmit()
        return true
      },
      COMMAND_PRIORITY_CRITICAL,
    )
  }, [editor, disabled, onSubmit])
  return null
}

/**
 * Intercepts file pastes (screenshots, dragged file copies) before the
 * plain-text plugin can insert anything; text pastes flow through untouched.
 */
function PasteFilesPlugin({ onFiles }: { onFiles: (files: readonly File[]) => void }) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    return editor.registerRootListener((root, previous) => {
      const handlePaste = (event: ClipboardEvent): void => {
        const files = filesFromTransfer(event.clipboardData)
        if (files.length === 0) return
        // Image pastes never insert the file name as text.
        event.preventDefault()
        onFiles(files)
      }
      previous?.removeEventListener('paste', handlePaste)
      root?.addEventListener('paste', handlePaste)
      return () => {
        root?.removeEventListener('paste', handlePaste)
      }
    })
  }, [editor, onFiles])
  return null
}

/** Revokes preview URLs for drafts that never made it into the strip. */
function releasePreviews(drafts: readonly DraftAttachment[]): void {
  for (const draft of drafts) {
    if (draft.previewUrl != null) URL.revokeObjectURL(draft.previewUrl)
  }
}

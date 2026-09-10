import { useLayoutEffect, useRef, useState } from 'react'
import { IconSendOutline16 } from '@primitives'
import css from '@chat-styles/InputBar.module.css'

const MAX_INPUT_HEIGHT_PX = 200

/**
 * Composer over the vendored dsh InputBar card (22px radius, input-major
 * fill, soft elevation). v1 uses a plain textarea — no Lexical editor, no
 * attachments — submitting on Enter (Shift+Enter for a newline). The send
 * control reuses the vendored .primary circle (34px, info-fill blue, white
 * glyph) rather than the generic ui-primitives Button, matching figma
 * 34:10465 instead of a generic pill.
 */
export function InputBar({ disabled, placeholder, onSubmit }: {
  disabled: boolean
  placeholder: string
  onSubmit: (text: string) => void
}) {
  const [draft, setDraft] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useLayoutEffect(() => {
    // Plain <textarea> doesn't auto-grow; ConversationRoot's ResizeObserver
    // republishes --dsh-composer-height off this element's box, so the
    // back-to-bottom button's offset depends on this height tracking content.
    const el = textareaRef.current
    if (el == null) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, MAX_INPUT_HEIGHT_PX)}px`
  }, [draft])

  const submit = (): void => {
    const text = draft.trim()
    if (text === '' || disabled) return
    setDraft('')
    onSubmit(text)
  }

  return (
    <div className={css.root}>
      <div className={css.card}>
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
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault()
                submit()
              }
            }}
          />
        </div>
        <div className={css.row}>
          <div className={css.modes}>
            <span style={{ fontSize: 12, color: 'var(--dsw-alias-label-tertiary)' }}>
              Enter 发送 · Shift+Enter 换行
            </span>
          </div>
          <div className={css.trailing}>
            <button
              type="button"
              className={css.primary}
              disabled={disabled || draft.trim() === ''}
              onClick={submit}
              aria-label="发送"
            >
              <IconSendOutline16 />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

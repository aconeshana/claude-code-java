/**
 * The composer's `/` trigger: on every editor update (typing *and* caret
 * movement) it reports the active `/` command token under the caret, or null.
 *
 * Completes the `/` half of dsh's `ui-input-trigger` detect pipeline alongside
 * `AtTriggerPlugin`. Emits only when the active token actually changes so
 * unrelated selection/render updates do not re-open a dismissed menu.
 */
import { useEffect, useRef } from 'react'
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext'
import { $getRoot, $getSelection, $isRangeSelection } from 'lexical'
import { activeSlashToken, sameSlashToken, type SlashToken } from './slashCommand'

/**
 * The caret's offset into the draft's plain text, or null when the selection
 * is not a collapsed caret. Paragraphs are joined with the newline the
 * editor's own `getTextContent()` inserts.
 */
function caretOffset(): number | null {
  const selection = $getSelection()
  if (!$isRangeSelection(selection) || !selection.isCollapsed()) return null
  const anchor = selection.anchor
  const target = anchor.getNode()
  let offset = 0
  for (const child of $getRoot().getChildren()) {
    if (child === target || child.isParentOf(target)) {
      return offset + anchor.offset
    }
    offset += child.getTextContentSize() + 1
  }
  return null
}

/** Reports the active `/` command token when it changes. */
export function SlashTriggerPlugin({ onToken }: {
  onToken: (token: SlashToken | null) => void
}) {
  const [editor] = useLexicalComposerContext()
  const lastRef = useRef<SlashToken | null>(null)
  useEffect(() => {
    return editor.registerUpdateListener(({ editorState }) => {
      editorState.read(() => {
        const text = $getRoot().getTextContent()
        const caret = caretOffset()
        const next = caret == null ? null : activeSlashToken(text, caret)
        if (sameSlashToken(lastRef.current, next)) return
        lastRef.current = next
        onToken(next)
      })
    })
  }, [editor, onToken])
  return null
}

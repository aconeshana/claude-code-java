/**
 * The composer's `@` trigger: on every editor update (typing *and* caret
 * movement) it reports the active `@` token under the caret, or null.
 *
 * This is the piece of dsh's `ui-input-trigger` detect pipeline this app
 * needs — upstream's version also drives a typed `/`, but this composer
 * opens its command menu from the "+" button only, so only the `@` half is
 * ported. The token grammar itself lives in `activeAtToken`, which is written
 * against the backend provider's own boundaries rather than dsh's.
 */
import { useEffect } from 'react'
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext'
import { $getRoot, $getSelection, $isRangeSelection } from 'lexical'
import { activeAtToken, type AtToken } from './atReference'

/**
 * The caret's offset into the draft's plain text, or null when the selection
 * is not a collapsed caret. Paragraphs are joined with the newline the
 * editor's own `getTextContent()` inserts, so the offset lines up with the
 * string the token scanner sees.
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

/** Reports the active `@` token on every editor update. */
export function AtTriggerPlugin({ onToken }: {
  onToken: (token: AtToken | null) => void
}) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    return editor.registerUpdateListener(({ editorState }) => {
      editorState.read(() => {
        const text = $getRoot().getTextContent()
        const caret = caretOffset()
        onToken(caret == null ? null : activeAtToken(text, caret))
      })
    })
  }, [editor, onToken])
  return null
}
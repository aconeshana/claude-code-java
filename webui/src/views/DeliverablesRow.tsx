import type { ToolCallState } from '../store/conversations'
import { collectDeliverables } from './deliverables'

/**
 * The produced-file chip row below one assistant message's tool calls —
 * a compact pill list (not the large `.fileCard` attachment style, which is
 * a different, right-aligned user-attachment element). Visual language
 * mirrors `AttachmentStrip.tsx`'s chip: same radius, padding, hover background.
 * Chips are non-interactive — clicking is a no-op until a desktop host gives
 * this webui local filesystem access, so no onClick handler is wired.
 */
export function DeliverablesRow({ toolCalls }: { toolCalls: readonly ToolCallState[] }) {
  const { shown, overflow } = collectDeliverables(toolCalls)
  if (shown.length === 0) return null
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {shown.map((path) => (
        <span
          key={path}
          title={path}
          style={{
            display: 'inline-flex',
            maxWidth: 220,
            padding: '4px 8px',
            borderRadius: 8,
            background: 'var(--dsw-alias-interactive-bg-hover)',
            fontSize: 12,
            lineHeight: '16px',
            color: 'var(--dsw-alias-label-primary)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {basename(path)}
        </span>
      ))}
      {overflow > 0 && (
        <span
          style={{
            display: 'inline-flex',
            padding: '4px 8px',
            borderRadius: 8,
            background: 'var(--dsw-alias-interactive-bg-hover)',
            fontSize: 12,
            lineHeight: '16px',
            color: 'var(--dsw-alias-label-tertiary)',
          }}
        >
          {`+${overflow} files`}
        </span>
      )}
    </div>
  )
}

function basename(path: string): string {
  return path.split('/').pop() ?? path
}

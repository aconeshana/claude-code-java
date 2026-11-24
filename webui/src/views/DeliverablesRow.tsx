import type { ToolCallState } from '../store/conversations'
import { openFileInRightDock } from '../store/rightSidebar'
import { useSessions } from '../store/sessions'
import { FileTypeIcon } from './FileTypeIcon'
import { collectDeliverables } from './deliverables'

/**
 * The produced-file chip row below one assistant message's tool calls.
 * Each chip shows a color-coded FileTypeIcon and opens the file directly in
 * the right multi-tab dock when clicked.
 */
export function DeliverablesRow({ toolCalls }: { toolCalls: readonly ToolCallState[] }) {
  const sessionId = useSessions((state) => state.selectedSessionId)
  const { shown, overflow } = collectDeliverables(toolCalls)
  if (shown.length === 0) return null
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {shown.map((path) => (
        <button
          key={path}
          type="button"
          title={`在右侧栏预览 ${path}`}
          onClick={() => {
            if (sessionId != null) openFileInRightDock(sessionId, path)
          }}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            maxWidth: 240,
            padding: '4px 8px',
            border: 'none',
            borderRadius: 8,
            background: 'var(--dsw-alias-interactive-bg-hover)',
            fontSize: 12,
            lineHeight: '16px',
            color: 'var(--dsw-alias-label-primary)',
            cursor: sessionId != null ? 'pointer' : 'default',
          }}
        >
          <FileTypeIcon path={path} size={15} />
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {basename(path)}
          </span>
        </button>
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

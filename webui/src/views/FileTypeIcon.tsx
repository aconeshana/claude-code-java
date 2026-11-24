import type { ReactNode } from 'react'
import { FileTypeIcon as UpstreamFileTypeIcon } from '@primitives'

/**
 * Re-exports upstream `ui-primitives/FileTypeIcon` (authentic colorful SVG
 * file-type & language-specific icons) with a stable `{ path, size }` surface
 * for ToolCallRow, DeliverablesRow, and WorkspaceFilesTab.
 */
export function FileTypeIcon({
  path,
  size = 16,
}: {
  readonly path: string
  readonly size?: number
}): ReactNode {
  return (
    <span data-file-type-icon style={{ display: 'inline-flex', alignItems: 'center', flex: 'none' }}>
      <UpstreamFileTypeIcon path={path} size={size} />
    </span>
  )
}

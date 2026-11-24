import { useCallback, useState, type ReactNode } from 'react'
import {
  IconChevronDownOutline14,
  IconChevronUpOutline14,
  IconFolderOpenOutline16,
  IconRightUpOutline16,
  Tooltip,
} from '@primitives'
import css from '@vendor/ui-deliverables/Deliverables.module.css'
import { openWorkspaceFileInHost } from '../api/client'
import type { ToolCallState } from '../store/conversations'
import { openFileInRightDock } from '../store/rightSidebar'
import { useSessions } from '../store/sessions'
import { toast } from '../store/toast'
import { FileTypeIcon } from './FileTypeIcon'
import { collectAllDeliverables } from './deliverables'

const COLLAPSED_LIMIT = 4

function basename(path: string): string {
  return path.split('/').pop() ?? path
}

function extensionLabel(path: string): string {
  const name = basename(path)
  const dot = name.lastIndexOf('.')
  if (dot > 0 && dot < name.length - 1) return name.slice(dot + 1).toUpperCase()
  return '文件'
}

/**
 * Delivered / Presented Files 2-column card grid (adapted from upstream
 * `ui-deliverables/Deliverables.tsx` + `PresentedFileCard.tsx`).
 *
 * Each card renders a 40×40 tile with the vendored upstream SVG `FileTypeIcon`,
 * opens the file in the Right Multi-Tab Dock on click, and offers native host
 * desktop actions (`用默认应用打开` / `在访达中显示`) backed by
 * `POST /api/workspace-open` with Global Toast feedback.
 */
export function DeliverablesRow({ toolCalls }: { toolCalls: readonly ToolCallState[] }): ReactNode {
  const sessionId = useSessions((state) => state.selectedSessionId)
  const [expanded, setExpanded] = useState(false)
  const [openingPath, setOpeningPath] = useState<string | null>(null)
  const all = collectAllDeliverables(toolCalls)

  const handleOpenInHost = useCallback(async (path: string, reveal: boolean) => {
    setOpeningPath(path)
    try {
      await openWorkspaceFileInHost(sessionId, path, reveal)
      toast.success(reveal ? `已在访达中显示 ${basename(path)}` : `已用默认应用打开 ${basename(path)}`)
    } catch {
      toast.error(`无法在宿主打开 ${basename(path)}（文件不存在或超出工作区范围）`)
    } finally {
      setOpeningPath((prev) => (prev === path ? null : prev))
    }
  }, [sessionId])

  if (all.length === 0) return null

  const visible = expanded ? all : all.slice(0, COLLAPSED_LIMIT)
  const hiddenCount = Math.max(0, all.length - COLLAPSED_LIMIT)

  return (
    <div className={css.root} data-delivered-files>
      <div className={css.presented} data-single={visible.length === 1 ? 'true' : undefined}>
        {visible.map((path) => {
          const name = basename(path)
          const busy = openingPath === path
          return (
            <div key={path} className={css.file} data-presented-file>
              <button
                type="button"
                className={css.cardPreview}
                title={path}
                aria-label={`在右侧栏预览 ${path}`}
                onClick={() => {
                  if (sessionId != null) openFileInRightDock(sessionId, path)
                }}
              />
              <span className={css.fileIcon}>
                <FileTypeIcon path={path} size={22} />
              </span>
              <div className={css.fileBody}>
                <div className={css.details}>
                  <span className={css.fileName}>{name}</span>
                  <span className={css.description} data-presented-description>
                    <span className={css.secondaryText}>
                      {busy ? '正在调用桌面应用…' : extensionLabel(path)}
                    </span>
                    <span className={css.previewHint}>点击在右侧栏预览</span>
                  </span>
                </div>
                <div className={css.actions}>
                  <Tooltip label="用桌面默认应用打开" side="top">
                    <button
                      type="button"
                      aria-label={`用桌面默认应用打开 ${name}`}
                      data-presented-action="open-in-app"
                      disabled={busy}
                      onClick={(e) => {
                        e.stopPropagation()
                        void handleOpenInHost(path, false)
                      }}
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        width: 26,
                        height: 26,
                        padding: 0,
                        border: 'none',
                        borderRadius: 6,
                        background: 'transparent',
                        color: 'var(--dsw-alias-label-secondary)',
                        cursor: 'pointer',
                      }}
                    >
                      <IconRightUpOutline16 size={14} />
                    </button>
                  </Tooltip>
                  <Tooltip label="在访达中显示" side="top">
                    <button
                      type="button"
                      aria-label={`在访达中显示 ${name}`}
                      data-presented-action="reveal"
                      disabled={busy}
                      onClick={(e) => {
                        e.stopPropagation()
                        void handleOpenInHost(path, true)
                      }}
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        width: 26,
                        height: 26,
                        padding: 0,
                        border: 'none',
                        borderRadius: 6,
                        background: 'transparent',
                        color: 'var(--dsw-alias-label-secondary)',
                        cursor: 'pointer',
                      }}
                    >
                      <IconFolderOpenOutline16 size={14} />
                    </button>
                  </Tooltip>
                </div>
              </div>
            </div>
          )
        })}
      </div>
      {hiddenCount > 0 && (
        <button
          type="button"
          className={css.toggle}
          onClick={() => { setExpanded((v) => !v) }}
        >
          {expanded ? <IconChevronUpOutline14 size={14} /> : <IconChevronDownOutline14 size={14} />}
          <span>{expanded ? '收起文件列表' : `展开其余 ${hiddenCount} 个交付文件`}</span>
        </button>
      )}
    </div>
  )
}

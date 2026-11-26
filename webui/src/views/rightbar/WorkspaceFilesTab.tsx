import { useCallback, useEffect, useState, type ReactNode } from 'react'
import {
  IconChevronDownOutline14,
  IconChevronRightOutline14,
  IconFolderCloseRegular,
  IconFolderOpenRegular,
  IconRefreshOutline14,
} from '@primitives'
import { fetchWorkspaceTree, type WorkspaceTreeEntry } from '../../api/client'
import { FileTypeIcon } from '../FileTypeIcon'
import css from './WorkspaceFilesTab.module.css'

function formatSize(bytes: number): string {
  if (bytes <= 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function WorkspaceFilesTab({
  sessionId,
  onOpenFile,
}: {
  readonly sessionId: string
  readonly onOpenFile: (path: string) => void
}): ReactNode {
  const [dirs, setDirs] = useState<Readonly<Record<string, readonly WorkspaceTreeEntry[]>>>({})
  const [expanded, setExpanded] = useState<Readonly<Record<string, boolean>>>({})
  const [loading, setLoading] = useState<Readonly<Record<string, boolean>>>({})
  const [error, setError] = useState<string | null>(null)

  const loadDir = useCallback(async (dir: string) => {
    setLoading((prev) => ({ ...prev, [dir]: true }))
    try {
      const result = await fetchWorkspaceTree(sessionId, dir)
      setDirs((prev) => ({ ...prev, [dir]: result.entries }))
      if (dir === '') setError(null)
    } catch (err: unknown) {
      if (dir === '') {
        setError(err instanceof Error ? err.message : '无法加载工作区目录')
      }
    } finally {
      setLoading((prev) => ({ ...prev, [dir]: false }))
    }
  }, [sessionId])

  useEffect(() => {
    setDirs({})
    setExpanded({})
    setError(null)
    void loadDir('')
  }, [loadDir])

  const toggleDir = useCallback((dirPath: string) => {
    setExpanded((prev) => {
      const nextOpen = !prev[dirPath]
      if (nextOpen && dirs[dirPath] === undefined) {
        void loadDir(dirPath)
      }
      return { ...prev, [dirPath]: nextOpen }
    })
  }, [dirs, loadDir])

  const renderLevel = (dirPath: string, depth: number): ReactNode => {
    const entries = dirs[dirPath]
    if (entries === undefined) {
      if (loading[dirPath]) {
        return (
          <div className={css.status} style={{ paddingLeft: 12 + depth * 16 }}>
            加载中…
          </div>
        )
      }
      return null
    }
    if (entries.length === 0 && depth === 0) {
      return <div className={css.status}>当前工作区为空</div>
    }
    return entries.map((entry) => {
      const isOpen = entry.directory && expanded[entry.path] === true
      return (
        <div key={entry.path}>
          <button
            type="button"
            className={css.row}
            style={{ paddingLeft: 8 + depth * 16 }}
            onClick={() => {
              if (entry.directory) toggleDir(entry.path)
              else onOpenFile(entry.path)
            }}
            title={entry.path}
          >
            <span className={css.chevron}>
              {entry.directory ? (
                isOpen ? <IconChevronDownOutline14 size={12} /> : <IconChevronRightOutline14 size={12} />
              ) : null}
            </span>
            <span className={css.icon}>
              {entry.directory
                ? (isOpen ? <IconFolderOpenRegular size={15} /> : <IconFolderCloseRegular size={15} />)
                : <FileTypeIcon path={entry.path} size={15} />}
            </span>
            <span className={css.name}>{entry.name}</span>
            {!entry.directory && entry.size > 0 && (
              <span className={css.meta}>{formatSize(entry.size)}</span>
            )}
          </button>
          {isOpen && renderLevel(entry.path, depth + 1)}
        </div>
      )
    })
  }

  return (
    <div className={css.root} data-workspace-files-tab>
      <div className={css.toolbar}>
        <span className={css.heading}>工作区文件</span>
        <button
          type="button"
          className={css.refreshBtn}
          aria-label="刷新文件树"
          title="刷新文件树"
          onClick={() => {
            setDirs({})
            setExpanded({})
            void loadDir('')
          }}
        >
          <IconRefreshOutline14 size={14} />
        </button>
      </div>
      <div className={css.tree}>
        {error !== null ? (
          <div className={css.status}>{error}</div>
        ) : (
          renderLevel('', 0)
        )}
      </div>
    </div>
  )
}

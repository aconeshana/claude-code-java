import { useEffect, useState } from 'react'
import {
  Button,
  IconCloseOutline16,
  IconFolderOpenOutline16,
  IconPlusOutline16,
  Input,
  Modal,
} from '@primitives'
import { DirectoryBrowser } from '@vendor/ui-directory-picker-browse/DirectoryBrowser'
import { createDirectory, listDirectory } from '../api/client'
import { useTranslate } from '../i18n/useTranslate'
import {
  DIRECTORY_BROWSER_NS,
  directoryBrowserDicts,
} from '@vendor/ui-directory-picker-browse/locales'
import { basenameOf, useProjects } from '../store/projects'
import css from './CreateProjectModal.module.css'

export interface CreateProjectModalProps {
  open: boolean
  onClose: () => void
  onCreated: (project: { path: string; name: string; additionalDirectories: readonly string[] }) => void
}

/**
 * Antigravity-inspired Create Project modal: lets the user pick one primary
 * directory plus zero or more additional bound directories (persisted as
 * standard Claude Code `permissions.additionalDirectories` in
 * `<primary>/.claude/settings.local.json`) and give the project a custom
 * display name.
 */
export function CreateProjectModal({ open, onClose, onCreated }: CreateProjectModalProps) {
  const dirT = useTranslate(DIRECTORY_BROWSER_NS, directoryBrowserDicts)
  const createProject = useProjects((state) => state.createProject)

  const [name, setName] = useState('')
  const [nameTouched, setNameTouched] = useState(false)
  const [folders, setFolders] = useState<string[]>([])
  const [pickerOpen, setPickerOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setName('')
    setNameTouched(false)
    setFolders([])
    setPickerOpen(false)
    setSubmitting(false)
    setError(null)
  }, [open])

  const handleAddFolder = (pickedPath: string) => {
    const trimmed = pickedPath.trim()
    setPickerOpen(false)
    if (trimmed === '') return
    setFolders((prev) => {
      if (prev.includes(trimmed)) return prev
      const next = [...prev, trimmed]
      if (!nameTouched && next.length === 1) {
        setName(basenameOf(trimmed))
      }
      return next
    })
    setError(null)
  }

  const handleRemoveFolder = (target: string) => {
    setFolders((prev) => {
      const next = prev.filter((item) => item !== target)
      if (!nameTouched) {
        setName(next[0] != null ? basenameOf(next[0]) : '')
      }
      return next
    })
  }

  const handleCreate = async () => {
    const primary = folders[0]
    if (primary == null) return
    setSubmitting(true)
    setError(null)
    try {
      const additional = folders.slice(1)
      const displayName = name.trim() === basenameOf(primary) ? '' : name.trim()
      const created = await createProject({
        path: primary,
        name: displayName,
        additional_directories: additional,
        tier: 'local',
      })
      onCreated({
        path: created.path,
        name: created.name || basenameOf(created.path),
        additionalDirectories: created.additional_directories,
      })
      onClose()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <Modal
        open={open && !pickerOpen}
        onClose={onClose}
        title="新建项目"
        closeLabel="关闭"
        className={css.modal}
        footer={(
          <>
            <Button variant="outline" onClick={onClose} disabled={submitting}>
              取消
            </Button>
            <Button
              variant="primary"
              disabled={folders.length === 0 || submitting}
              onClick={() => { void handleCreate() }}
            >
              {submitting ? '创建中…' : '创建项目'}
            </Button>
          </>
        )}
      >
        <div className={css.form}>
          <div className={css.field}>
            <label className={css.label} htmlFor="create-project-name">
              项目名称
            </label>
            <Input
              id="create-project-name"
              value={name}
              placeholder="默认使用主目录名称，可自定义"
              onChange={(event) => {
                setNameTouched(true)
                setName(event.target.value)
              }}
            />
          </div>

          <div className={css.field}>
            <div className={css.label}>绑定目录（Folders）</div>
            <div className={css.folderList}>
              {folders.map((folder, index) => (
                <div key={folder} className={css.folderCard}>
                  <IconFolderOpenOutline16 size={16} className={css.folderIcon} />
                  <div className={css.folderMeta}>
                    <div className={css.folderNameRow}>
                      <span className={css.folderName}>{basenameOf(folder)}/</span>
                      {index === 0 && <span className={css.primaryBadge}>主目录</span>}
                    </div>
                    <span className={css.folderPath} title={folder}>{folder}</span>
                  </div>
                  <button
                    type="button"
                    className={css.removeBtn}
                    aria-label={`移除目录 ${folder}`}
                    onClick={() => { handleRemoveFolder(folder) }}
                  >
                    <IconCloseOutline16 size={12} />
                  </button>
                </div>
              ))}
              <button
                type="button"
                className={css.addFolderBtn}
                disabled={submitting}
                onClick={() => { setPickerOpen(true) }}
              >
                <IconPlusOutline16 size={14} />
                <span>添加目录</span>
              </button>
            </div>
            <div className={css.hint}>
              第 1 个目录为主目录；其余目录将作为标准 Claude Code 附加目录写入本地配置（.claude/settings.local.json）。
            </div>
          </div>

          {error != null && <div className={css.error} role="alert">{error}</div>}
        </div>
      </Modal>

      <DirectoryBrowser
        open={pickerOpen}
        listDirectory={listDirectory}
        createDirectory={createDirectory}
        onOpen={handleAddFolder}
        onClose={() => { setPickerOpen(false) }}
        busy={false}
        t={dirT}
      />
    </>
  )
}

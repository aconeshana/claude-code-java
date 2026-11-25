import { useEffect, useState } from 'react'
import clsx from 'clsx'
import { Button, Modal } from '@primitives'
import { executeRewind, previewRewind, submitTurn } from '../api/client'
import type { RewindMode, RewindPreviewResponse } from '../api/types'
import { useConversations } from '../store/conversations'
import { useSessions } from '../store/sessions'
import { toast } from '../store/toast'
import css from './RewindConfirmModal.module.css'

export interface RewindConfirmModalProps {
  readonly open: boolean
  readonly sessionId: string
  readonly messageId: string
  readonly editedText: string
  readonly onClose: () => void
  readonly onCompleted: () => void
}

/**
 * Step 2 of the two-step guided edit-and-rewind flow:
 * fetches a dry-run preview of conversation & code rollback impact (with
 * expandable per-file unified diffs), lets the user choose between:
 *   - Case 1: Rewind conversation + roll back code diffs
 *   - Case 2: Rewind conversation context only (keep workspace files)
 * then executes the rewind and immediately submits the edited prompt.
 */
export function RewindConfirmModal({
  open,
  sessionId,
  messageId,
  editedText,
  onClose,
  onCompleted,
}: RewindConfirmModalProps) {
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<RewindPreviewResponse | null>(null)
  const [mode, setMode] = useState<RewindMode>('conversation_only')
  const [expandedFiles, setExpandedFiles] = useState<ReadonlySet<string>>(new Set())

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    setError(null)
    setPreview(null)
    setExpandedFiles(new Set())
    void previewRewind(sessionId, messageId)
      .then((res) => {
        if (cancelled) return
        setPreview(res)
        setMode(res.can_rewind_code && res.files.length > 0 ? 'both' : 'conversation_only')
      })
      .catch((failure: unknown) => {
        if (cancelled) return
        setError(failure instanceof Error ? failure.message : '获取回撤预览失败')
        setMode('conversation_only')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [open, sessionId, messageId])

  const canRollbackCode = preview != null && preview.can_rewind_code && preview.files.length > 0

  const toggleFile = (path: string): void => {
    setExpandedFiles((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }

  const handleConfirm = async (): Promise<void> => {
    if (submitting || loading) return
    setSubmitting(true)
    setError(null)
    try {
      const effectiveMode: RewindMode = mode === 'both' && !canRollbackCode ? 'conversation_only' : mode
      const result = await executeRewind(sessionId, messageId, effectiveMode)
      await useConversations.getState().loadSnapshot(sessionId)
      await useSessions.getState().refresh()
      await submitTurn(sessionId, editedText)
      toast.success(
        result.rewound_code && result.restored_files.length > 0
          ? `已回撤对话并回滚 ${result.restored_files.length} 个文件，正在重新发送`
          : '已回撤对话上下文，正在重新发送',
      )
      onCompleted()
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : '回撤执行失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="确认编辑并回撤"
      closeLabel="关闭对话框"
      footer={(
        <>
          <Button variant="outline" disabled={submitting} onClick={onClose}>
            取消
          </Button>
          <Button
            variant="primary"
            disabled={loading || submitting || preview == null}
            onClick={() => { void handleConfirm() }}
            data-testid="rewind-confirm-submit"
          >
            {submitting ? '正在回撤并发送…' : '确认回撤并重新发送'}
          </Button>
        </>
      )}
    >
      <div className={css.body} data-testid="rewind-confirm-modal">
        {loading && (
          <div className={css.summary}>正在分析受影响的对话轮次与代码变更…</div>
        )}
        {error != null && (
          <div className={css.errorBox} role="alert">{error}</div>
        )}
        {preview != null && (
          <>
            <div className={css.summary}>
              将回撤此消息及之后的{' '}
              <span className={css.summaryStrong}>{preview.turns_to_drop} 轮对话</span>
              （共 {preview.messages_to_drop} 条消息记录），并以修改后的内容重新发送。
            </div>

            <div className={css.diffSection}>
              <div className={css.diffHeader}>
                <span>代码影响预览</span>
                {preview.can_rewind_code && preview.files.length > 0 && (
                  <span className={css.stats}>
                    <span>{preview.files.length} 个文件</span>
                    <span className={css.ins}>+{preview.insertions}</span>
                    <span className={css.del}>-{preview.deletions}</span>
                  </span>
                )}
              </div>

              {!preview.can_rewind_code ? (
                <div className={css.summary}>
                  {preview.code_unavailable_reason ?? '未检测到该步骤的文件快照，仅支持回撤对话上下文。'}
                </div>
              ) : preview.files.length === 0 ? (
                <div className={css.summary}>此步骤之后无代码文件变更。</div>
              ) : (
                <div className={css.fileList} data-testid="rewind-diff-file-list">
                  {preview.files.map((file) => {
                    const expanded = expandedFiles.has(file.path)
                    return (
                      <div key={file.path} className={css.fileItem}>
                        <button
                          type="button"
                          className={css.fileToggle}
                          aria-expanded={expanded}
                          onClick={() => { toggleFile(file.path) }}
                        >
                          <span className={css.filePath} title={file.path}>{file.path}</span>
                          {file.will_delete && <span className={css.deleteTag}>将移除</span>}
                          <span className={css.stats}>
                            <span className={css.ins}>+{file.insertions}</span>
                            <span className={css.del}>-{file.deletions}</span>
                          </span>
                        </button>
                        {expanded && file.unified_diff !== '' && (
                          <pre className={css.diffPre}>
                            {file.unified_diff.split('\n').map((line, idx) => (
                              <span
                                key={idx}
                                className={
                                  line.startsWith('@@')
                                    ? css.diffLineHunk
                                    : line.startsWith('+')
                                      ? css.diffLineAdd
                                      : line.startsWith('-')
                                        ? css.diffLineDel
                                        : css.diffLineCtx
                                }
                              >
                                {line}
                              </span>
                            ))}
                          </pre>
                        )}
                      </div>
                    )
                  })}
                </div>
              )}
            </div>

            <div className={css.options} role="radiogroup" aria-label="回撤模式">
              <label
                className={clsx(
                  css.optionCard,
                  mode === 'both' && canRollbackCode && css.optionSelected,
                  !canRollbackCode && css.optionDisabled,
                )}
                data-testid="rewind-mode-both"
              >
                <input
                  type="radio"
                  name="rewind-mode"
                  className={css.optionRadio}
                  checked={mode === 'both' && canRollbackCode}
                  disabled={!canRollbackCode}
                  onChange={() => { setMode('both') }}
                />
                <span className={css.optionBody}>
                  <span className={css.optionTitle}>回撤对话上下文并回滚代码 Diff</span>
                  <span className={css.optionDesc}>
                    将上述受影响文件恢复到此步骤之前的快照，并截断后续对话后重发。
                  </span>
                </span>
              </label>

              <label
                className={clsx(
                  css.optionCard,
                  mode === 'conversation_only' && css.optionSelected,
                )}
                data-testid="rewind-mode-conversation"
              >
                <input
                  type="radio"
                  name="rewind-mode"
                  className={css.optionRadio}
                  checked={mode === 'conversation_only'}
                  onChange={() => { setMode('conversation_only') }}
                />
                <span className={css.optionBody}>
                  <span className={css.optionTitle}>仅回撤对话上下文（保留当前代码）</span>
                  <span className={css.optionDesc}>
                    保留工作区中现有的全部代码改动，仅截断后续对话上下文后重发。
                  </span>
                </span>
              </label>
            </div>

            <div className={css.footnote}>
              仅回滚由文件编辑工具（Edit / Write / NotebookEdit）修改的文件；Bash 命令产生的文件变更不受快照控制。
            </div>
          </>
        )}
      </div>
    </Modal>
  )
}

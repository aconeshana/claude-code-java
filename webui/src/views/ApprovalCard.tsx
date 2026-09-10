import { useState } from 'react'
import { Button } from '@primitives'
import css from '@chat-styles/ApprovalPanel.module.css'
import type { PermissionAsk } from '../api/types'

/**
 * Approval card over the vendored dsh ApprovalPanel styles: warn strip +
 * detail body + reject/allow-once actions. The decision posts to
 * /api/permissions/respond; a 409 (another endpoint answered first) is
 * success for this UI — the resolved frame removes the card either way.
 */
export function ApprovalCard({ ask, onDecision }: {
  ask: PermissionAsk
  onDecision: (ask: PermissionAsk, allowed: boolean) => Promise<void>
}) {
  const [answered, setAnswered] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const answer = (allowed: boolean): void => {
    setAnswered(true)
    setFailure(null)
    onDecision(ask, allowed).catch(() => {
      // Transport failure only: a 409 raced resolution is a win, and the
      // resolved frame already removed the card.
      setAnswered(false)
      setFailure('应答未送达，请重试')
    })
  }

  const headline = ask.decision_reason_detail
    ?? ask.destructive_warning
    ?? ask.custom_message
    ?? `工具 ${ask.tool} 请求权限`

  return (
    <div className={css.root}>
      <div className={css.card}>
        <div className={css.strip}><span className={css.dot} />等待授权</div>
        <div className={css.body}>
          <div className={css.headline}>{headline}</div>
          <div className={css.command}>{commandText(ask)}</div>
          {failure != null && (
            <div className={css.command} style={{ color: 'var(--dsw-alias-state-error-primary)' }}>
              {failure}
            </div>
          )}
        </div>
        <div className={css.actionRow}>
          <Button
            variant="outline"
            className={css.reject}
            disabled={answered}
            onClick={() => { answer(false) }}
          >
            拒绝
          </Button>
          <Button variant="primary" disabled={answered} onClick={() => { answer(true) }}>
            允许一次
          </Button>
        </div>
      </div>
    </div>
  )
}

function commandText(ask: PermissionAsk): string {
  const input = ask.input
  if (input == null) return ask.tool
  const command = input.command
  if (typeof command === 'string') return command
  const path = input.file_path ?? input.path
  if (typeof path === 'string') return path
  try {
    return JSON.stringify(input, null, 2)
  } catch {
    return ask.tool
  }
}

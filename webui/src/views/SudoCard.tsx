import { useState } from 'react'
import { Button } from '@primitives'
import css from '@chat-styles/ApprovalPanel.module.css'
import type { SudoAsk } from '../api/types'

/** Mirrors the gateway's and the TUI dialog's cap on a sudo password. */
const MAX_PASSWORD_CHARS = 1024

/**
 * The sudo password prompt.
 *
 * A sudo invocation that needs a password blocks in its session's interaction
 * coordinator; the terminal dialog and this card observe the same ask, and
 * whichever submits first wins. A 409 from the respond endpoint means the
 * terminal (or another tab) got there first — the resolved frame has already
 * removed this card, so it is a success, not an error.
 *
 * The password lives only in this component's state and in the one POST body
 * that carries it. It is cleared on submit, on cancel, and whenever the ask
 * this card belongs to changes, and it is never read back from anywhere.
 */
export function SudoCard({ ask, onRespond }: {
  ask: SudoAsk
  onRespond: (ask: SudoAsk, password: string | null) => Promise<void>
}) {
  const [password, setPassword] = useState('')
  const [revealed, setRevealed] = useState(false)
  const [answered, setAnswered] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const answer = (value: string | null): void => {
    if (answered) return
    setAnswered(true)
    setFailure(null)
    // Drop the secret from this component's state before the request leaves,
    // so a slow or failed response cannot leave it sitting in a live field.
    setPassword('')
    setRevealed(false)
    onRespond(ask, value).catch(() => {
      // Transport failure only: a 409 raced resolution is a win, and the
      // resolved frame has already removed the card.
      setAnswered(false)
      setFailure('应答未送达，请重试')
    })
  }

  const canSubmit = password.length > 0 && password.length <= MAX_PASSWORD_CHARS

  return (
    <div className={css.root}>
      <div className={css.card}>
        <div className={css.strip}><span className={css.dot} />需要 sudo 密码</div>
        <div className={css.body}>
          <div className={css.headline}>
            {ask.executable == null
              ? '有一项命令正在请求 sudo 授权'
              : `${ask.executable} 正在请求授权`}
          </div>
          {ask.command != null && <div className={css.command}>{ask.command}</div>}
          <div className={css.secretRow}>
            <input
              className={css.secretInput}
              type={revealed ? 'text' : 'password'}
              value={password}
              maxLength={MAX_PASSWORD_CHARS}
              autoFocus
              autoComplete="off"
              spellCheck={false}
              disabled={answered}
              placeholder="输入密码"
              aria-label="sudo 密码"
              onChange={(event) => { setPassword(event.target.value) }}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && canSubmit) {
                  event.preventDefault()
                  answer(password)
                } else if (event.key === 'Escape') {
                  event.preventDefault()
                  answer(null)
                }
              }}
            />
            <button
              type="button"
              className={css.reveal}
              disabled={answered}
              aria-label={revealed ? '隐藏密码' : '显示密码'}
              onClick={() => { setRevealed((current) => !current) }}
            >
              {revealed ? '隐藏' : '显示'}
            </button>
          </div>
          <div className={css.hint}>密码只发送给系统 sudo，不会写入会话记录或日志。</div>
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
            onClick={() => { answer(null) }}
          >
            取消
          </Button>
          <Button variant="primary" disabled={answered || !canSubmit} onClick={() => { answer(password) }}>
            确认
          </Button>
        </div>
      </div>
    </div>
  )
}

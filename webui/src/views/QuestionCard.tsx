import { useState, type ChangeEvent, type KeyboardEvent } from 'react'
import clsx from 'clsx'
import {
  Button, IconCheckOutline14, IconChevronLeftOutline14, IconChevronRightOutline14,
  IconCloseOutline16, IconEditOutline16,
} from '@primitives'
import css from '@vendor/dsh-user-questions/QuestionComposer.module.css'
import type { PermissionAsk, PermissionQuestion } from '../api/types'

/** One question's in-progress answer: option indices, free text, or explicitly skipped. */
interface QuestionDraft {
  readonly selected: readonly number[]
  readonly custom: string
  readonly skipped: boolean
}

/** Split the conventional recommendation suffix without changing the answer value. */
function parseRecommendedLabel(label: string): { label: string; recommended: boolean } {
  const suffix = /\s*(?:\((?:recommended|推荐)\)|（(?:recommended|推荐)）)\s*$/i
  return suffix.test(label)
    ? { label: label.replace(suffix, ''), recommended: true }
    : { label, recommended: false }
}

function isComposing(event: KeyboardEvent<HTMLTextAreaElement>): boolean {
  // keyCode 229 is the legacy IME-composition signal engines emit without isComposing.
  return event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
}

function answered(item: QuestionDraft): boolean {
  return item.selected.length > 0 || item.custom.trim() !== ''
}

function completed(item: QuestionDraft): boolean {
  return answered(item) || item.skipped
}

function formatAnswer(question: PermissionQuestion, item: QuestionDraft): string {
  const text = item.custom.trim()
  if (text !== '' && item.selected.length === 0) return text
  const joined = item.selected
    .map((optionIndex) => parseRecommendedLabel(question.options[optionIndex]?.label ?? '').label)
    .join(', ')
  return text !== '' ? `${joined}, ${text}` : joined
}

/**
 * Auto-growing free-text answer: a hidden mirror div sizes the shared grid
 * cell (counting soft-wrapped rows a plain `rows` count can't see), and the
 * textarea stretches to it. Mirror and textarea must share font, padding and
 * wrapping or the two heights diverge.
 */
function AnswerField(props: {
  value: string
  placeholder: string
  disabled: boolean
  onChange: (event: ChangeEvent<HTMLTextAreaElement>) => void
  onKeyDown: (event: KeyboardEvent<HTMLTextAreaElement>) => void
}) {
  return (
    <div className={clsx(css.field, css.customInline)}>
      <div aria-hidden className={css.fieldMirror}>{`${props.value}\n`}</div>
      <textarea
        className={css.fieldInput}
        value={props.value}
        disabled={props.disabled}
        rows={1}
        placeholder={props.placeholder}
        onChange={props.onChange}
        onKeyDown={props.onKeyDown}
      />
    </div>
  )
}

export function QuestionCard({ ask, onSubmit, onCancel }: {
  ask: PermissionAsk
  onSubmit: (ask: PermissionAsk, updatedInput: Record<string, unknown>) => Promise<void>
  onCancel: (ask: PermissionAsk) => Promise<void>
}) {
  const questions = ask.questions ?? []
  const [index, setIndex] = useState(0)
  const [drafts, setDrafts] = useState<readonly QuestionDraft[]>(
    () => questions.map(() => ({ selected: [], custom: '', skipped: false })),
  )
  const [busy, setBusy] = useState<'answer' | 'cancel' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const question = questions[index]
  const draft = drafts[index]
  if (question == null || draft == null) return null

  const replaceProgress = (nextIndex: number, nextDrafts: readonly QuestionDraft[]): void => {
    setIndex(nextIndex)
    setDrafts(nextDrafts)
  }

  const updateDraft = (
    update: (current: QuestionDraft) => QuestionDraft,
    nextIndex = index,
  ): void => {
    replaceProgress(nextIndex, drafts.map((item, itemIndex) => itemIndex === index ? update(item) : item))
    setError(null)
  }

  const choose = (optionIndex: number): void => {
    updateDraft((current) => {
      if (question.multi_select === true) {
        const selected = current.selected.includes(optionIndex)
          ? current.selected.filter((item) => item !== optionIndex)
          : [...current.selected, optionIndex]
        return { ...current, selected, skipped: false }
      }
      return { selected: [optionIndex], custom: '', skipped: false }
    }, question.multi_select !== true && index < questions.length - 1 ? index + 1 : index)
  }

  const submitDrafts = (values: readonly QuestionDraft[]): void => {
    const missing = values.findIndex((item) => !completed(item))
    if (missing >= 0) {
      replaceProgress(missing, values)
      setError('还有问题未回答')
      return
    }
    const answers: Record<string, string> = {}
    values.forEach((item, itemIndex) => {
      if (item.skipped) return
      const text = formatAnswer(questions[itemIndex] as PermissionQuestion, item)
      if (text === '') return
      answers[(questions[itemIndex] as PermissionQuestion).question] = text
    })
    setBusy('answer')
    setError(null)
    onSubmit(ask, { ...ask.input, answers, annotations: {} }).catch((cause: unknown) => {
      setBusy(null)
      setError(cause instanceof Error ? cause.message : String(cause))
    })
  }

  const continueFlow = (): void => {
    if (!answered(draft)) {
      setError('请先回答本题，或点击"跳过本题"')
      return
    }
    if (index < questions.length - 1) {
      replaceProgress(index + 1, drafts)
      setError(null)
      return
    }
    submitDrafts(drafts)
  }

  const draftCustom = (event: ChangeEvent<HTMLTextAreaElement>): void => {
    const value = event.target.value
    updateDraft((current) => ({
      ...current,
      selected: question.multi_select === true ? current.selected : [],
      custom: value,
      skipped: false,
    }))
  }

  const continueFromCustom = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (event.key !== 'Enter' || event.shiftKey || isComposing(event)) return
    event.preventDefault()
    continueFlow()
  }

  const skipQuestion = (): void => {
    const nextDrafts = drafts.map((item, itemIndex) => itemIndex === index
      ? { selected: [], custom: '', skipped: true }
      : item)
    if (index < questions.length - 1) {
      replaceProgress(index + 1, nextDrafts)
      setError(null)
      return
    }
    setDrafts(nextDrafts)
    submitDrafts(nextDrafts)
  }

  const cancelFlow = (): void => {
    setBusy('cancel')
    setError(null)
    onCancel(ask).catch((cause: unknown) => {
      setBusy(null)
      setError(cause instanceof Error ? cause.message : String(cause))
    })
  }

  return (
    <div className={css.frame}>
      <section className={css.card} aria-labelledby={`question-${ask.request_id}-${String(index)}`}>
        <header className={css.header}>
          <div className={css.headingBlock}>
            {question.header != null && question.header !== '' && (
              <div className={css.eyebrow}>{question.header}</div>
            )}
            <h2 className={css.title} id={`question-${ask.request_id}-${String(index)}`}>
              {question.question}
            </h2>
          </div>
          <div className={css.headerActions}>
            <button
              type="button" className={css.iconButton} aria-label="取消"
              title="取消" disabled={busy != null} onClick={cancelFlow}
            >
              <IconCloseOutline16 />
            </button>
          </div>
        </header>

        <div className={css.body}>
          <div className={css.options} role={question.multi_select === true ? 'group' : 'radiogroup'}>
            {question.options.map((option, optionIndex) => {
              const selected = draft.selected.includes(optionIndex)
              const display = parseRecommendedLabel(option.label)
              return (
                <button
                  type="button" key={`${option.label}-${String(optionIndex)}`}
                  className={clsx(css.option, selected && question.multi_select !== true && css.optionSelected)}
                  role={question.multi_select === true ? 'checkbox' : 'radio'}
                  aria-checked={selected}
                  aria-label={display.label}
                  disabled={busy != null}
                  onClick={() => { choose(optionIndex) }}
                >
                  {question.multi_select === true
                    ? (
                      <span className={clsx(css.checkbox, selected && css.checkboxChecked)} aria-hidden="true">
                        {selected && <IconCheckOutline14 size={12} />}
                      </span>
                    )
                    : <span className={css.number}>{optionIndex + 1}</span>}
                  <span className={css.optionCopy}>
                    <span className={css.optionLine}>
                      <span className={css.optionLabel}>{display.label}</span>
                      {display.recommended && <span className={css.badge}>推荐</span>}
                      {option.description != null && (
                        <span className={css.description}>{option.description}</span>
                      )}
                    </span>
                  </span>
                </button>
              )
            })}

            <div className={clsx(css.customRow, draft.custom !== '' && css.customRowActive)}>
              {question.multi_select === true
                ? (
                  <span className={clsx(css.checkbox, draft.custom !== '' && css.checkboxChecked)} aria-hidden="true">
                    {draft.custom !== '' && <IconCheckOutline14 size={12} />}
                  </span>
                )
                : (
                  <span className={css.number} aria-hidden="true">
                    <IconEditOutline16 size={12} />
                  </span>
                )}
              <AnswerField
                value={draft.custom}
                disabled={busy != null}
                placeholder="或输入自定义答案…"
                onChange={draftCustom}
                onKeyDown={continueFromCustom}
              />
            </div>
          </div>
        </div>

        <footer className={css.footer}>
          <div className={css.pager}>
            <button
              type="button" className={css.iconButton} aria-label="上一题"
              disabled={index === 0 || busy != null}
              onClick={() => { replaceProgress(index - 1, drafts); setError(null) }}
            >
              <IconChevronLeftOutline14 />
            </button>
            <span className={css.progress}>{index + 1} / {questions.length}</span>
            <button
              type="button" className={css.iconButton} aria-label="下一题"
              disabled={index === questions.length - 1 || busy != null}
              onClick={() => { replaceProgress(index + 1, drafts); setError(null) }}
            >
              <IconChevronRightOutline14 />
            </button>
          </div>
          <div className={css.feedback} role="status">{error}</div>
          <div className={css.footerActions}>
            <Button variant="outline" disabled={busy != null} onClick={skipQuestion}>跳过本题</Button>
            <Button variant="primary" disabled={busy != null || !answered(draft)} onClick={continueFlow}>
              {busy === 'answer' ? '提交中…' : index === questions.length - 1 ? '提交' : '下一题'}
            </Button>
          </div>
        </footer>
      </section>
    </div>
  )
}

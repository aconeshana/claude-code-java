import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { fetchWorkspaceFile } from '../api/client'
import type { WorkspaceFileContent } from '../api/client'
import { useTranslate } from '../i18n/useTranslate'
import { DELIVERABLES_NS, deliverablesDicts } from '../i18n/dictionaries/deliverables'
import css from './FilePreview.module.css'

type Load =
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly file: WorkspaceFileContent }
  | { readonly status: 'error'; readonly message: string }

/**
 * The right column's file preview: the whole file with line numbers, under a
 * header naming it. Upstream's `text` sidebar tab renders the same content
 * through `CodeBlock`; this app's vendored primitive has no line-number gutter,
 * so the gutter is drawn here and the text is left unhighlighted.
 *
 * @param props.sessionId - the session whose workspace holds the file.
 * @param props.path - the file path, relative to that workspace.
 * @param props.onClose - dismiss the preview.
 * @returns the preview panel.
 */
export function FilePreview({ sessionId, path, onClose }: {
  sessionId: string
  path: string
  onClose: () => void
}): ReactNode {
  const t = useTranslate(DELIVERABLES_NS, deliverablesDicts)
  const [load, setLoad] = useState<Load>({ status: 'loading' })

  useEffect(() => {
    let live = true
    setLoad({ status: 'loading' })
    fetchWorkspaceFile(sessionId, path).then(
      (file) => { if (live) setLoad({ status: 'ready', file }) },
      (error: unknown) => {
        if (live) setLoad({ status: 'error', message: error instanceof Error ? error.message : String(error) })
      },
    )
    return () => { live = false }
  }, [sessionId, path])

  const name = path.slice(path.lastIndexOf('/') + 1)
  return (
    <section className={css.preview} data-file-preview={path}>
      <header className={css.header}>
        <span className={css.name} title={path}>{name}</span>
        <button type="button" className={css.close} aria-label={t('preview.closeAria')}
          onClick={onClose}>{t('preview.close')}</button>
      </header>
      <div className={css.body}>{renderBody(load, t)}</div>
    </section>
  )
}

function renderBody(load: Load, t: (key: string, params?: Readonly<Record<string, string | number>>) => string): ReactNode {
  if (load.status === 'loading') return <p className={css.notice}>{t('preview.loading')}</p>
  if (load.status === 'error') {
    return <p className={css.notice} role="alert">{t('preview.error', { message: load.message })}</p>
  }
  const { file } = load
  if (file.kind === 'binary') return <p className={css.notice}>{t('preview.binary')}</p>
  if (file.kind === 'oversized') return <p className={css.notice}>{t('preview.oversized')}</p>
  return <TextLines text={file.text ?? ''} />
}

function TextLines({ text }: { text: string }): ReactNode {
  const lines = splitLines(text)
  const numbers = lines.map((_, index) => String(index + 1)).join('\n')
  return (
    <div className={css.lines}>
      <pre className={css.gutter} aria-hidden="true">{numbers}</pre>
      <pre className={css.code}>{lines.join('\n')}</pre>
    </div>
  )
}

function splitLines(text: string): string[] {
  return text.replace(/\r?\n$/, '').split(/\r?\n/)
}

import { LinkIcon, classifyLinkPath } from '../../vendor/ui-primitives'
import { SHOWN_LIMIT, basename } from './turnDeliverables'
import css from './ProducedFiles.module.css'

/**
 * The produced-files row a finished turn ends with — vendored from upstream
 * `ui-deliverables/src/client/ProducedFiles.tsx`. The chips are the turn's
 * successful mutation paths; the full path rides `title` and stays the
 * disambiguator when two turns produce files sharing a basename.
 *
 * Deviation — one row per turn, not per closing message. Upstream mounts this
 * into the chat view's `conversation.chat.turnTail` hole with a matched list
 * scoped to the closing assistant's seq. This project has no turn-tail hole or
 * turn Location index, so `MessageItem` mounts it on the turn's tail anchor row
 * and hands it the paths aggregated over the whole turn. The row itself is
 * unchanged by that.
 *
 * Deviation — no locale seat. Upstream takes its strings from `PropsLocale`;
 * this project passes a `t` function down from `useTranslate`.
 */
export function ProducedFiles({ paths, openFile, t }: {
  paths: readonly string[]
  openFile: (path: string) => void
  t: (key: string, params?: Readonly<Record<string, string>>) => string
}) {
  const shown = paths.slice(0, SHOWN_LIMIT)
  return (
    <div className={css.root}>
      <span className={css.label}>{t('produced.label')}</span>
      <div className={css.lane}>
        <div className={css.row} data-produced-files-row>
          {shown.map((path) => (
            <button
              key={path}
              type="button"
              className={css.file}
              // The full path is the disambiguator when two turns produce files
              // that share a basename; the chip itself stays short.
              title={path}
              aria-label={t('produced.open', { name: path })}
              onClick={() => { openFile(path) }}
            >
              <LinkIcon kind={classifyLinkPath(path)} className={css.fileIcon} />
              <span className={css.fileName}>{basename(path)}</span>
            </button>
          ))}
          {/* Upstream emits one remainder span per shown count and lets the
              container-query bands pick which one displays, so narrowing the
              row degrades the count without any layout observation in JS. */}
          {shown.map((_, index) => {
            const shownCount = index + 1
            const remainder = paths.length - shownCount
            if (remainder <= 0) return null
            return (
              <span key={shownCount} className={css.more} data-shown={shownCount}>
                {remainder === 1
                  ? t('produced.moreOne')
                  : t('produced.more', { count: String(remainder) })}
              </span>
            )
          })}
        </div>
      </div>
    </div>
  )
}
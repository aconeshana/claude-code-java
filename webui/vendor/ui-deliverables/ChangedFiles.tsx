/**
 * Turn changes: a compact single-file card, or a header with a folded file
 * list. This is the conversation-area presentation — the card, the file rows
 * with their `+N -M` counts, the 500ms hover preview, and the bottom fold.
 *
 * Vendored from upstream `packages/client/ui-deliverables/src/client/ChangedFiles.tsx`
 * at the pinned commit. Every structural decision is upstream's: `COLLAPSED_ROWS`
 * = 4, the `Intl.NumberFormat` grouping of the counts, the header carrying the
 * read-path description in a hidden span for screen readers, the hover card
 * mounting its preview only while open (so passing over a row reads nothing),
 * and the `data-single` attribute that swaps the border for the one-file card.
 *
 * The bindings replaced (recorded in `webui/UPSTREAM.md`):
 *
 * - `PropsLocale`/`InjectFace`/`SessionStandardProps` — cordis's injected props.
 *   Here the same values arrive as ordinary props: `t` from the app's
 *   translator, `diff`/`loadDiff` from the vendored `ChangesDiffStore`.
 * - `resolveWorkspacePath(cwd, path)` and the `cwd` prop — this app's gateway
 *   answers with the file's own `display` path, already resolved, so the card
 *   uses `display` directly and takes no `cwd`.
 * - The comparison is addressed by `(sessionId, path)` rather than upstream's
 *   `(sessionId, seq, index)`; the row's `index` still names which file
 *   `openReview` shows.
 * - `useChangesDiff(value => value[url])` — upstream's slot injects a selector
 *   hook that subscribes to the store. Here the store face is `diff`, and it
 *   is passed down to the preview so the preview can subscribe through it; a
 *   plain `diff.state(url)` read would never see the store publish and the
 *   preview would sit on the loading text forever.
 * - `FileTypeIcon` is not vendored (it pulls in its own artwork tree, its own
 *   CSS, and the code-file classifier). The tile uses the vendored
 *   `classifyLinkPath` + `LinkIcon`, which carry the same category vocabulary —
 *   `code`/`image`/`document`/`folder`/`url`/`other` — at the size the card
 *   needs. Recorded as a deviation with the follow-up noted in UPSTREAM.md.
 */
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { classifyLinkPath, HoverCard, IconChevronDownOutline14, IconChevronUpOutline14, LinkIcon } from '@primitives'
import type { ChangesDiffState } from './changes-diff.ts'
import { changesDiffUrl, type ChangesSummary } from './changes.ts'
import { FileDiff } from './FileDiff.tsx'
import diffCss from './FileDiff.module.css'
import css from './ChangedFiles.module.css'

/** Rows shown before the fold; the design's summary height for a closing message. */
const COLLAPSED_ROWS = 4

const GROUPED = new Intl.NumberFormat('en-US')

/**
 * The copy the card consumes. Upstream receives this as a `PropsLocale` face
 * from its dictionary; here it is the same translator signature the vendored
 * `FileDiff` takes, so one translator satisfies a card and the viewer inside
 * its hover card without an adapter.
 */
export type ChangedFilesLabels =
  (key: string, params?: Readonly<Record<string, string | number>>) => string

/** What the card needs from the comparison store. */
export interface ChangedFilesDiffAccess {
  /**
   * One comparison's live state: subscribed, so a publish re-renders the
   * reader. Upstream's `useChangesDiff(selector)` receives the whole record map
   * and selects in the selector; this is that same call with the selector
   * already applied to one URL. The selector must return the record itself
   * rather than a fresh wrapper, or React's snapshot identity check loops.
   */
  useState(url: string): ChangesDiffState | undefined
  /** Read one comparison unless a standing state is kept. */
  load(sessionId: string, path: string): Promise<void>
}

/** Added and deleted line counts in the card's colors. */
function Counts({ added, deleted, t }: { added: number; deleted: number; t: ChangedFilesLabels }) {
  return <>
    <span className={css.added}>{t('changes.added', { count: GROUPED.format(added) })}</span>
    <span className={css.deleted}>{t('changes.deleted', { count: GROUPED.format(deleted) })}</span>
  </>
}

/**
 * Render one turn's changed files. The header opens the turn's review in the
 * right column on its first file. A single file uses only the header; it and
 * multi-file rows preview their comparison after a 500ms hover.
 *
 * @param props.changes - the summary to draw.
 * @param props.sessionId - the viewed Session.
 * @param props.openReview - open the turn's review on the file at a summary index.
 * @param props.diff - the comparison store's read face.
 * @param props.t - the card's copy.
 * @returns the card.
 */
export function ChangedFiles({ changes, sessionId, openReview, diff, t }: {
  changes: Pick<ChangesSummary, 'files' | 'total' | 'added' | 'deleted'>
  sessionId: string
  /** Open the turn's review on the file at an original summary index. */
  openReview: (index: number) => void
  diff: ChangedFilesDiffAccess
  t: ChangedFilesLabels
}): ReactNode {
  const cardRef = useRef<HTMLDivElement>(null)
  const pathDescriptionId = useId()
  const [expanded, setExpanded] = useState(false)
  const singleFile = changes.total === 1 ? changes.files[0] : undefined
  const foldable = changes.files.length > COLLAPSED_ROWS
  const rows = foldable && !expanded ? changes.files.slice(0, COLLAPSED_ROWS) : changes.files
  const header = <button type="button" className={css.header}
    aria-label={singleFile === undefined
      ? t('changes.openReview')
      : t('changes.viewDiff', { name: singleFile.display })}
    aria-describedby={singleFile === undefined ? undefined : pathDescriptionId}
    onClick={() => { openReview(0) }}>
    <span className={css.tile}>
      <LinkIcon kind={singleFile === undefined ? 'code' : classifyLinkPath(singleFile.path)} size={20} />
    </span>
    <span className={css.titles}>
      <span className={css.title}>{singleFile === undefined
        ? t('changes.title', { count: String(changes.total) })
        : t('changes.singleTitle', { name: basename(singleFile.path) })}</span>
      <span className={css.stat}>
        <span className={css.statCounts}>{singleFile?.binary === true ? t('changes.binary')
          : singleFile?.oversized === true ? t('changes.oversized')
            : <Counts t={t} added={changes.added} deleted={changes.deleted} />}</span>
        <span className={css.previewHint}>{t('presented.preview')}</span>
      </span>
    </span>
  </button>
  return <div ref={cardRef} className={css.card} data-changed-files data-single={singleFile !== undefined || undefined}>
    {singleFile === undefined ? header : <>
      <HoverCard variant="preview" widthAnchorRef={cardRef} openDelayMs={500} anchor={header}
        content={<ChangedFilePreview sessionId={sessionId} path={singleFile.path} display={singleFile.display}
          diff={diff} t={t} />} />
      <span id={pathDescriptionId} hidden>{singleFile.display}</span>
    </>}
    {singleFile === undefined && <ul className={css.list}>
      {rows.map((file) => (
        <li key={file.display}>
          <HoverCard variant="preview" widthAnchorRef={cardRef} openDelayMs={500}
            content={<ChangedFilePreview sessionId={sessionId} path={file.path} display={file.display}
              diff={diff} t={t} />}
            anchor={<button type="button" className={css.row}
              aria-label={t('changes.viewDiff', { name: file.display })}
              aria-describedby={`${pathDescriptionId}-${file.path}`}
              onClick={() => { openReview(changes.files.indexOf(file)) }}>
              <span className={css.path}>{file.display}</span>
              <span className={css.counts}>
                {file.binary === true ? t('changes.binary')
                  : file.oversized === true ? t('changes.oversized')
                    : <Counts t={t} added={file.added} deleted={file.deleted} />}
              </span>
            </button>} />
          <span id={`${pathDescriptionId}-${file.path}`} hidden>{file.display}</span>
        </li>
      ))}
    </ul>}
    {foldable && <button type="button" className={css.toggle}
      aria-expanded={expanded}
      aria-label={t(expanded ? 'changes.collapseAria' : 'changes.expandAria',
        { count: String(changes.files.length) })}
      onClick={() => { setExpanded(value => !value) }}>
      <span>{t(expanded ? 'changes.collapse' : 'changes.all', { count: String(changes.files.length) })}</span>
      {expanded ? <IconChevronUpOutline14 /> : <IconChevronDownOutline14 />}
    </button>}
  </div>
}

/**
 * The file name without its directory. Upstream imports this from
 * `turn-deliverables.ts`; it is four lines and that module is not vendored (its
 * rest is the conversation-node definition this app has no seat for), so it is
 * inlined here rather than vendoring a module for one function.
 * @param path - a workspace-relative path.
 * @returns the last path segment.
 */
function basename(path: string): string {
  const cut = path.lastIndexOf('/')
  return cut === -1 ? path : path.slice(cut + 1)
}

/** Mounted only while its hover card is open, so passing over a row does not read a comparison. */
function ChangedFilePreview({ sessionId, path, display, diff, t }: {
  sessionId: string
  path: string
  display: string
  diff: ChangedFilesDiffAccess
  t: ChangedFilesLabels
}): ReactNode {
  const state = diff.useState(changesDiffUrl(sessionId, path))
  useEffect(() => {
    if (state === undefined) void diff.load(sessionId, path)
  }, [state, sessionId, path, diff])
  return <div className={`${diffCss.root} ${css.preview}`} data-changes-hover-preview>
    <div className={diffCss.header}><span className={css.previewPath} data-changes-preview-path>{display}</span></div>
    <FileDiff state={state} split={false} wrap={false} t={t} retry={() => { void diff.load(sessionId, path) }} />
  </div>
}
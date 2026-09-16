/**
 * The `/context` modal — claude-code-java re-assembly of dsh-context's
 * `client/components/contextModal.tsx`: the current composition bar over
 * the Context browser, centered over the conversation column, Escape and
 * mask-click to close. The composer intercepts `/context` (InputBar) and
 * opens this instead of submitting a turn, the same client-side command
 * dsh registers on its slash source. Data plane as in ContextView.
 */

import { useCallback, useEffect, useMemo, useState, type ReactElement } from 'react'
import { createPortal } from 'react-dom'
import { useSessionContext } from '../../store/sessionContext'
import { detailFetcher, makeContentFetcher, makeHeaderFetcher, useContextTimeline } from '../../store/contextTimeline'
import { useContextKit } from './useContextKit'
import { breakdownOf, pressureOf } from '@dsh-context/client/adapters'
import { headlineOf } from '@dsh-context/client/headline'
import { contextSettings } from '@dsh-context/client/settings'
import { useTimelineSource } from '@dsh-context/client/timelineSource'
import type { ViewKit } from '@dsh-context/client/viewkit'
import { makeContextBrowser } from '@dsh-context/client/components/browser'
import { makeCurrentComposition } from '@dsh-context/client/components/currentComposition'
import { makeErrorBoundary } from '@dsh-context/client/components/errorBoundary'
import { useEscapeClose } from '@dsh-context/client/components/escapeClose'
import { makeLegend, makeStackedBar } from '@dsh-context/client/components/stackedBar'
import '@dsh-context/index'

function makeCards(kit: ViewKit) {
  const StackedBar = makeStackedBar(kit)
  return {
    CurrentComposition: makeCurrentComposition(kit, StackedBar, makeLegend(kit)),
    ContextBrowser: makeContextBrowser(kit, StackedBar, contextSettings),
    ErrorBoundary: makeErrorBoundary(kit.t),
  }
}

export function ContextModal({ sessionId, open, onClose }: {
  sessionId: string | null
  open: boolean
  onClose: () => void
}): ReactElement | null {
  const { kit } = useContextKit()
  const cards = useMemo(() => makeCards(kit), [kit])
  if (!open) return null
  const { ErrorBoundary } = cards
  return createPortal(
    <ErrorBoundary>
      <ContextModalBody sessionId={sessionId} kit={kit} cards={cards} onClose={onClose} />
    </ErrorBoundary>,
    document.body,
  )
}

function ContextModalBody({ sessionId, kit, cards, onClose }: {
  sessionId: string | null
  kit: ViewKit
  cards: ReturnType<typeof makeCards>
  onClose: () => void
}): ReactElement {
  const { t } = kit
  const { CurrentComposition, ContextBrowser } = cards
  const key = sessionId ?? ''

  const openViewer = useContextTimeline((state) => state.open)
  useEffect(() => {
    if (key === '') return
    return openViewer(key)
  }, [key, openViewer])
  const entry = useContextTimeline((state) => state.sessions[key])
  const source = useTimelineSource(entry?.head ?? null, key, detailFetcher)
  const data = source.data

  const meterSession = useSessionContext((state) => state.sessionId)
  const meterUsage = useSessionContext((state) => state.usage)
  const meterMatches = meterSession === sessionId
  const pressure = useMemo(() => (meterMatches ? pressureOf(meterUsage) : null), [meterMatches, meterUsage])
  const breakdown = useMemo(() => (meterMatches ? breakdownOf(meterUsage) : null), [meterMatches, meterUsage])

  const [hoverCat, setHoverCat] = useState<string | null>(null)
  const fetchContent = useMemo(() => (key === '' ? undefined : makeContentFetcher(key)), [key])
  const fetchHeader = useMemo(() => (key === '' ? undefined : makeHeaderFetcher(key)), [key])

  const close = useCallback(() => { onClose() }, [onClose])
  useEscapeClose(true, close)

  const head = data !== null ? headlineOf(data, pressure, breakdown) : null
  const subtitle = data !== null ? (data.model ?? '') + (data.provider ? ' · ' + data.provider : '') : ''

  return (
    <div className="lc-modal-backdrop" onClick={close} data-testid="context-modal">
      <div className="lc-modal-card" role="dialog" aria-modal="true" aria-label={t('shell.modal.title')} onClick={(ev) => { ev.stopPropagation() }}>
        <div className="lc-modal-head">
          <span className="lc-modal-title">{t('tab')}</span>
          <button
            type="button"
            className="lc-modal-close hover:text-(--dsw-alias-label-primary) hover:bg-(--dsw-alias-bg-layer-2)"
            aria-label={t('cmd.close')}
            onClick={close}
          >×</button>
        </div>

        {sessionId === null
          ? <div className="lc-empty">{t('shell.noSession')}</div>
          : entry?.cold === true
            ? <div className="lc-empty">{t('shell.cold')}</div>
            : data === null || head === null
              ? <div className="lc-empty">{t('loading')}</div>
              : (
                <div>
                  <CurrentComposition head={head} subtitle={subtitle} hoverKey={hoverCat} onHoverKey={setHoverCat} />
                  <ContextBrowser
                    data={data}
                    headers={source.detail?.headers ?? null}
                    fetchContent={fetchContent}
                    fetchHeader={fetchHeader}
                    hoverKey={hoverCat}
                    onHoverKey={setHoverCat}
                    detailState={source.detailState}
                    onDetailRetry={source.retryDetail}
                  />
                </div>
              )}
      </div>
    </div>
  )
}

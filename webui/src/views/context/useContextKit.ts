import { useMemo } from 'react'
import { CONTEXT_NS, contextDicts } from '../../i18n/dictionaries/context'
import { useTranslate } from '../../i18n/useTranslate'
import { useLocale } from '../../store/locale'
import { makeViewKit, type ViewKit } from '@dsh-context/client/viewkit'

/**
 * The vendored dsh-context `ViewKit` (translate + formatters) bound to the
 * app's active locale. Memoized per locale so the component factories the
 * views build from it (`make*` closures) are rebuilt only on a locale
 * switch — they capture `t`, so a stale kit would keep the old language.
 */
export function useContextKit(): { kit: ViewKit; locale: string } {
  const t = useTranslate(CONTEXT_NS, contextDicts)
  const locale = useLocale((state) => state.locale)
  // `t` is a fresh closure per render; the kit keys on the locale instead.
  const kit = useMemo(() => makeViewKit(t), [locale]) // eslint-disable-line react-hooks/exhaustive-deps
  return { kit, locale }
}

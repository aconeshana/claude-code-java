import { create } from 'zustand'
import { LOCALE_IDS, type LocaleId } from '../i18n/types'

const STORAGE_KEY = 'webui-locale'

/**
 * The first shipped locale the browser asks for, matched on the primary
 * subtag so regional variants land on their language (`zh-Hans-CN` -> zh).
 */
function detectBrowserLocale(): LocaleId | undefined {
  const tags = [...(navigator.languages ?? []), navigator.language]
  for (const tag of tags) {
    const primary = tag.toLowerCase().split('-')[0]
    if ((LOCALE_IDS as readonly string[]).includes(primary)) return primary as LocaleId
  }
  return undefined
}

function readLocale(): LocaleId {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if ((LOCALE_IDS as readonly string[]).includes(raw ?? '')) return raw as LocaleId
  } catch { /* storage unavailable (private mode): fall through to detection */ }
  return detectBrowserLocale() ?? 'zh'
}

export interface LocaleStore {
  readonly locale: LocaleId
  setLocale(next: LocaleId): void
}

export const useLocale = create<LocaleStore>(set => ({
  locale: readLocale(),

  setLocale(next) {
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch { /* storage unavailable (private mode): preference stays session-only */ }
    set({ locale: next })
  },
}))

window.addEventListener('storage', (event) => {
  if (event.key === STORAGE_KEY) useLocale.setState({ locale: readLocale() })
})

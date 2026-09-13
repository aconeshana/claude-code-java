export const LOCALE_IDS = ['zh', 'en'] as const

export type LocaleId = (typeof LOCALE_IDS)[number]

export interface LocaleDefinition {
  readonly id: LocaleId
  readonly label: string
}

/** Each locale's own display label, read directly here — never looked up via the active locale's dictionary. */
export const LOCALES: readonly LocaleDefinition[] = [
  { id: 'zh', label: '中文' },
  { id: 'en', label: 'English' },
]

export type LocaleDict = Record<string, string>

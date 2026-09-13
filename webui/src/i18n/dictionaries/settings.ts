import type { LocaleDict, LocaleId } from '../types'

export const SETTINGS_NS = 'settings'

export const settingsZh = {
  'language.title': '语言',
} as const

export const settingsEn: Record<keyof typeof settingsZh, string> = {
  'language.title': 'Language',
}

export const settingsDicts: Readonly<Record<LocaleId, LocaleDict>> = { zh: settingsZh, en: settingsEn }

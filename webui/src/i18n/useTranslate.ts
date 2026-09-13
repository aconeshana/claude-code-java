import { useMemo } from 'react'
import { useLocale } from '../store/locale'
import { BASE_REGISTRY, translate, type NamespaceRegistry } from './runtime'
import type { LocaleDict, LocaleId } from './types'

export function useTranslate(ns: string, dicts?: Readonly<Record<LocaleId, LocaleDict>>) {
  const locale = useLocale((state) => state.locale)
  const registry = useMemo<NamespaceRegistry>(() => {
    if (dicts == null) return BASE_REGISTRY
    return new Map([...BASE_REGISTRY, [ns, dicts]])
  }, [ns, dicts])
  return (key: string, params?: Readonly<Record<string, string | number>>) => translate(registry, ns, locale, key, params)
}

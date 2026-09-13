import type { LocaleDict, LocaleId } from './types'
import { COMMON_NS, commonDicts } from './dictionaries/common'

export type NamespaceRegistry = ReadonlyMap<string, Readonly<Record<LocaleId, LocaleDict>>>

/** Lookup chain within one namespace: the active locale, then that namespace's zh fallback. */
function lookup(registry: NamespaceRegistry, ns: string, locale: LocaleId, key: string): string | undefined {
  const dict = registry.get(ns)
  return dict?.[locale]?.[key] ?? dict?.zh?.[key]
}

/**
 * Full lookup chain: the entry's namespace (active, then zh) -> the shared
 * common namespace (active, then zh) -> the key itself (missing text stays
 * visible, fail loud in the UI rather than blank).
 */
export function translate(
  registry: NamespaceRegistry,
  ns: string,
  locale: LocaleId,
  key: string,
  params?: Readonly<Record<string, string | number>>,
): string {
  const template = lookup(registry, ns, locale, key)
    ?? (ns !== COMMON_NS ? lookup(registry, COMMON_NS, locale, key) : undefined)
    ?? key
  if (params == null) return template
  return template.replace(/\{(\w+)\}/g, (match, name) => (name in params ? String(params[name]) : match))
}

export const BASE_REGISTRY: NamespaceRegistry = new Map([[COMMON_NS, commonDicts]])

/** Compact human-readable byte counts shared by attachment presenters. @module @deepseek-ai/dsh-client-ui-primitives/file-size */

/**
 * Byte count as compact user-facing size text (`312B`, `4.2KB`, `1.5MB`, `2.4GB`).
 * @param bytes - exact byte count.
 * @returns whole-unit text with one decimal below ten of the chosen unit.
 */
export function fileSizeText(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)}KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)}MB`
  const gb = mb / 1024
  return `${gb < 10 ? gb.toFixed(1) : Math.round(gb)}GB`
}

/** Lowercase extension without leading dot, or empty when absent. */
export function fileExtension(path: string): string {
  const base = path.split(/[\\/]/).pop() ?? ''
  const dot = base.lastIndexOf('.')
  return dot <= 0 || dot === base.length - 1 ? '' : base.slice(dot + 1).toLowerCase()
}

/**
 * The local-day key (`YYYY-MM-DD`) shared by the activity fold (host) and the
 * overview heatmap (client). Both halves bucket by the machine's OWN local
 * day: the harness host serves the operator's machine, so the host-folded
 * keys and the browser's cells speak the same calendar. Lexicographic order
 * on the key IS chronological order — the fold's retention cap and the
 * heatmap's week math both ride that.
 */

/** The strict key shape (round-trip validated on construction too). */
export const DAY_KEY_RE = /^\d{4}-\d{2}-\d{2}$/

/** Zero-pad to two digits (no Intl allocation on the hot path). */
function pad2(n: number): string {
  return n < 10 ? '0' + String(n) : String(n)
}

/**
 * The local calendar-day key of an epoch-ms instant, or null when the time
 * is not a finite number or falls outside the representable date range —
 * untrusted log data can never produce a garbage key.
 */
export function dayKeyOf(time: number): string | null {
  if (!Number.isFinite(time)) return null
  const d = new Date(time)
  const y = d.getFullYear()
  if (!Number.isFinite(y) || y < 1970 || y > 9999) return null
  return `${String(y).padStart(4, '0')}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

/**
 * Advance a day key by `delta` calendar days (local). Date-field arithmetic
 * keeps DST-short/long days exact (an epoch-ms add would drift an hour into
 * the neighbouring day). Null propagates (the caller's input guard), and a
 * malformed key — including one whose fields would roll over (2026-13-40) —
 * yields null as well.
 */
export function shiftDayKey(key: string, delta: number): string | null {
  if (!DAY_KEY_RE.test(key)) return null
  const y = Number(key.slice(0, 4))
  const m = Number(key.slice(5, 7))
  const d = Number(key.slice(8, 10))
  const date = new Date(y, m - 1, d)
  if (date.getFullYear() !== y || date.getMonth() !== m - 1 || date.getDate() !== d) return null
  date.setDate(date.getDate() + delta)
  return dayKeyOf(date.getTime())
}

/** The Monday (local) of the week containing `key` — the heatmap's column anchor. */
export function mondayOfWeek(key: string): string | null {
  if (!DAY_KEY_RE.test(key)) return null
  const y = Number(key.slice(0, 4))
  const m = Number(key.slice(5, 7))
  const d = Number(key.slice(8, 10))
  const date = new Date(y, m - 1, d)
  if (date.getFullYear() !== y || date.getMonth() !== m - 1 || date.getDate() !== d) return null
  // getDay: 0=Sunday..6=Saturday → days since Monday.
  return shiftDayKey(key, -((date.getDay() + 6) % 7))
}

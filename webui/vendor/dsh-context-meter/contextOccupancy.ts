/** Context usage rendered by conversation and Chat status surfaces. */
export interface ContextOccupancy {
  percent: number
  usedTokens: number
  contextWindow: number
}

/**
 * The provider-anchored sample the gateway's /api/session/context serves:
 * `usedTokens`/`contextWindow` are optional because the endpoint leaves the
 * token counts out until a finalized API usage anchor exists (this app's
 * port of upstream's independently-updated pressure fields).
 */
export interface ContextPressureSample {
  usedTokens?: number
  contextWindow?: number
}

/**
 * Resolve bounded display occupancy from independently updated pressure fields.
 * @param pressure - latest token-meter projection.
 * @returns occupancy, or null until numerator and capacity are known.
 */
export function contextOccupancy(
  pressure: ContextPressureSample | undefined,
): ContextOccupancy | null {
  const usedTokens = pressure?.usedTokens
  if (usedTokens === undefined || pressure?.contextWindow === undefined) return null
  return {
    percent: Math.min(100, Math.round(usedTokens / pressure.contextWindow * 100)),
    usedTokens,
    contextWindow: pressure.contextWindow,
  }
}

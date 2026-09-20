import { useId } from 'react'
import type { IconProps } from '@primitives'

/**
 * The Code Orb brand mark, rendered inline.
 *
 * Geometry is the same vector source as `public/code-orb-master.svg` (see
 * `docs/brand-assets.md`); it is inlined rather than loaded as an `<img>` so
 * the 24px sidebar mark costs no extra request and needs no base-path
 * resolution. The mark is deliberately multi-colour and does NOT ride
 * `currentColor` — unlike a single-ink silhouette it carries the palette
 * itself, and it stays legible on both sidebar fills (verified at 24px
 * against the light and dark `--dsw-specific-sidebar-fill` values).
 *
 * Below 24px use the re-cut geometry in `public/code-orb-favicon.svg`
 * instead: at that size this mark's hub ring falls under half a pixel.
 *
 * @param props.size - square edge in px (default 24, the upstream brand-mark size).
 * @param props.className - extra class for layout placement.
 * @returns the mark svg (aria-hidden; pair with the wordmark for accessibility).
 */
export function CodeOrbMark({ size = 24, className }: IconProps) {
  // Two instances can be mounted at once (the expanded brand row and the rail
  // toggle), so the clip path needs a per-instance id.
  const clip = `code-orb-clip-${useId()}`
  return (
    <svg
      width={size}
      height={size}
      className={className}
      viewBox="0 0 512 512"
      fill="none"
      aria-hidden="true"
    >
      <defs>
        <clipPath id={clip}>
          <circle cx="256" cy="254" r="223" />
        </clipPath>
      </defs>
      <circle cx="256" cy="254" r="223" fill="#F6F6F4" />
      <g clipPath={`url(#${clip})`}>
        <path d="M33 231.5A223 223 0 0 1 479 231.5H33Z" fill="#EC3431" />
        <rect x="24" y="231.5" width="464" height="44.5" fill="#16181A" />
        <circle cx="256" cy="248" r="76.5" fill="#16181A" />
        <circle cx="256" cy="248" r="76.5" stroke="#F6F6F4" strokeWidth="8.5" />
        <path d="M224.5 233.5L205 251L224.5 268.5" stroke="#FFFFFF" strokeWidth="8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M265.2 224.5L247.3 274.5" stroke="#FFFFFF" strokeWidth="7.9" strokeLinecap="round" />
        <path d="M287.5 233.5L307 251L287.5 268.5" stroke="#FFFFFF" strokeWidth="8" strokeLinecap="round" strokeLinejoin="round" />
      </g>
      <circle cx="256" cy="254" r="223" stroke="#16181A" strokeWidth="24" />
    </svg>
  )
}

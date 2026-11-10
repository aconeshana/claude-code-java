/** Shared props for every ic_ds_* icon component. */
export interface IconProps {
  /** Square edge in px; defaults to the glyph's own drawn size. */
  size?: number | undefined
  /** Extra class for layout placement; color rides currentColor.
   * (`| undefined` for exactOptionalPropertyTypes: callers forward their own optional prop.) */
  className?: string | undefined
}

/**
 * The weight of a product icon whose artwork comes in two stroke weights.
 *
 * Upstream splits "glyph" from "weight": one `productIcon(glyph, weight)`
 * factory serves both weights of a glyph, and the weight is a parametric
 * stroke-width in the artwork (1 regular, 1.3 medium) — the *size* stays a
 * prop with the glyph's own default. The glyphs ported here keep that split,
 * so `IconXRegular` and `IconXMedium` differ only in stroke width, exactly as
 * upstream's do.
 *
 * The older `ic_ds_*` icons in this tree predate that port: they are hand-drawn
 * 14-box glyphs whose size IS the component's identity
 * (`IconChevronDownOutline14`), because the design export they came from
 * carried one weight only. Both conventions coexist; do not convert one to the
 * other without re-exporting the artwork.
 */
export type ProductIconWeight = 'regular' | 'medium'

/** The stroke width each weight draws at, per upstream's `artwork.ts` table. */
export const PRODUCT_ICON_STROKE = { regular: 1, medium: 1.3 } as const satisfies
  Record<ProductIconWeight, number>

import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import {
  IconChevronDownOutlineRegular, IconChevronDownOutlineMedium,
  IconCompareSplitOutlineRegular, IconNowrapFillRegular, IconWrapFillRegular,
} from '@primitives'

describe('ported product icons', () => {
  it('renders each glyph with upstream path data and its weight stroke', () => {
    const regular = renderToStaticMarkup(<IconChevronDownOutlineRegular />)
    const medium = renderToStaticMarkup(<IconChevronDownOutlineMedium />)
    expect(regular).toContain('stroke-width="1"')
    expect(medium).toContain('stroke-width="1.3"')
    expect(regular).toContain('M4 6L7.29289 9.29289')
    expect(regular).toContain('data-icon="dsw-icon-chevron-down-outline-regular"')
  })

  it('sizes each glyph at its own drawn default and honours an override', () => {
    expect(renderToStaticMarkup(<IconCompareSplitOutlineRegular />)).toContain('width="16"')
    expect(renderToStaticMarkup(<IconCompareSplitOutlineRegular size={12} />)).toContain('width="12"')
    // chevron-down-outline draws on a 14 grid despite the shared 16 viewBox
    expect(renderToStaticMarkup(<IconChevronDownOutlineRegular />)).toContain('width="14"')
  })

  it('marks the glyph aria-hidden so a labelled button owns the accessible name', () => {
    for (const el of [<IconWrapFillRegular />, <IconNowrapFillRegular />]) {
      expect(renderToStaticMarkup(el)).toContain('aria-hidden="true"')
    }
  })
})

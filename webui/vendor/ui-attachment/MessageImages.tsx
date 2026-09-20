/**
 * Chat-history image attachments — ported from upstream ui-attachment's
 * MessageImage.tsx (single vs. tile variants, click-to-lightbox), adapted to
 * this project's transport: images arrive already inline as base64 (no
 * ImageLoader, no durable attachment ids, no loading/retry states). Upstream's
 * `singleFit()` precomputes a bounding box from stored dimensions so an async
 * load has a jank-free placeholder to grow into; that problem doesn't exist
 * here (the bytes are already in hand), so sizing is plain CSS
 * (max-width/max-height + object-fit) instead of a ported layout formula.
 * The lightbox is the exact dsh-context AttachmentLightbox, reused rather
 * than duplicated so both call sites share one implementation and one set of
 * `.lc-att-lightbox*` tokens.
 */

import { useState } from 'react'
import type { MessageImageAttachment } from '../../src/api/types'
import { AttachmentLightbox } from '../dsh-context/client/components/images'
import css from './MessageImages.module.css'

function dataUrlOf(image: MessageImageAttachment): string {
  return `data:${image.media_type};base64,${image.data}`
}

export function ImageGallery({ images }: { images: readonly MessageImageAttachment[] }) {
  const [openIndex, setOpenIndex] = useState<number | null>(null)
  if (images.length === 0) return null
  const variant = images.length === 1 ? 'single' : 'tile'
  return (
    <div className={css.gallery}>
      {images.map((image, index) => (
        <button
          key={index}
          type="button"
          className={css.frame}
          data-variant={variant}
          onClick={() => { setOpenIndex(index) }}
          aria-label={`预览图片 ${index + 1}`}
        >
          <img src={dataUrlOf(image)} alt={`图片 ${index + 1}`} className={css.image} />
        </button>
      ))}
      {openIndex !== null && (
        <AttachmentLightbox
          src={dataUrlOf(images[openIndex])}
          alt={`图片 ${openIndex + 1}`}
          labels={{ dialog: '图片预览', close: '关闭预览' }}
          onClose={() => { setOpenIndex(null) }}
        />
      )}
    </div>
  )
}

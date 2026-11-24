import type { ReactNode } from 'react'
import { IconCheckOutline16, IconWarningOutline16, Toast } from '@primitives'
import { useToast, type ToastKind } from '../store/toast'

function iconFor(kind: ToastKind): ReactNode {
  switch (kind) {
    case 'success':
      return <IconCheckOutline16 size={15} />
    case 'warning':
    case 'error':
      return <IconWarningOutline16 size={15} />
    default:
      return undefined
  }
}

/**
 * Global toast host mounted once at the App root. Renders the head of the
 * `useToast` queue through the vendored `@primitives/Toast` portal banner.
 */
export function ToastHost(): ReactNode {
  const active = useToast((state) => state.queue[0] ?? null)
  const dismiss = useToast((state) => state.dismiss)

  if (active === null) return null
  return (
    <Toast
      key={active.id}
      text={active.message}
      icon={iconFor(active.kind)}
      holdMs={active.holdMs}
      onDone={() => { dismiss(active.id) }}
    />
  )
}

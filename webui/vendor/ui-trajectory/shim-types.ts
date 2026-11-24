/**
 * Cordis-free type shims for the vendored `packages/client/ui-trajectory` tree
 * (same pattern as `vendor/dsh-context/client/narrow.ts` and `vendor/ui-deliverables`).
 */
import type { ReactNode } from 'react'
import type { TrajectoryCellProps } from './trajectory-record.ts'

export type JsonValue =
  | string
  | number
  | boolean
  | null
  | readonly JsonValue[]
  | { readonly [key: string]: JsonValue | undefined }

export function assertNever(value: never): never {
  throw new Error(`Unhandled trajectory case: ${String(value)}`)
}

export interface ImageAttachmentRef {
  readonly id?: string | undefined
  readonly name?: string | undefined
  readonly mediaType?: string | undefined
  readonly bytes: number
  readonly width: number
  readonly height: number
  /** Inline data URL when served directly from our gateway snapshot. */
  readonly dataUrl?: string | undefined
}

export interface FileAttachmentRef {
  readonly name?: string | undefined
  readonly bytes: number
}

export interface ToolSchema {
  readonly name: string
  readonly description: string
  readonly parameters: Record<string, unknown>
  readonly [key: string]: unknown
}

export interface AssistantRequestConfig {
  readonly provider?: string | undefined
  readonly model?: string | undefined
  readonly [key: string]: JsonValue | undefined
}

export interface ConversationPromptSnapshot {
  readonly config: AssistantRequestConfig
  readonly system: string
  readonly tools: readonly ToolSchema[]
}

export type MessageImageLoader = (attachment: ImageAttachmentRef) => Promise<string>

export interface TrajectoryImageSlotProps {
  readonly images: readonly { readonly attachment: ImageAttachmentRef; readonly label?: string | undefined }[]
  readonly align?: 'start' | 'end' | undefined
  readonly thumbnail?: boolean | undefined
  readonly loadImage: MessageImageLoader
}

export interface PropsRenderSlots<_K extends string = string> {
  readonly renderSlot: (slot: 'conversation.trajectory.images', props: TrajectoryImageSlotProps) => ReactNode
}

export type WatchToolResult = (
  resultSeq: number,
  onValue: (state: { readonly available: boolean; readonly value?: JsonValue | undefined }) => void,
) => () => void

/** One Message or Step group inside a turn (`layout.ts`). */
export interface TrajectoryGroupModel {
  readonly title: string
  readonly description?: string | undefined
  readonly cells: readonly TrajectoryCellProps[]
}

/** One sticky turn, or a standalone compaction section between turns (`layout.ts`). */
export interface TrajectoryTurnModel {
  readonly turn: number | null
  readonly groups: readonly TrajectoryGroupModel[]
}

export type TranslateKeyMap = Record<string, string>

export type TranslateFn<Keys extends string> = (
  key: Keys | string,
  params?: Readonly<Record<string, string | number>> | undefined,
) => string

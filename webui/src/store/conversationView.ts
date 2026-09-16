/**
 * Which face of the conversation column is showing: the chat transcript or
 * the vendored dsh-context Context tab (dsh's `conversation.view` slot
 * ring). Per session, so switching sessions lands on the face the user
 * left that session on; the Chat→Context jump flips it programmatically.
 */
import { create } from 'zustand'
import type { ConversationView } from '../views/ConversationRoot'

export interface ConversationViewStore {
  readonly views: Readonly<Record<string, ConversationView>>
  viewOf(sessionId: string | null): ConversationView
  setView(sessionId: string | null, view: ConversationView): void
}

export const useConversationView = create<ConversationViewStore>((set, get) => ({
  views: {},
  viewOf(sessionId) {
    return get().views[sessionId ?? ''] ?? 'chat'
  },
  setView(sessionId, view) {
    set((state) => ({ views: { ...state.views, [sessionId ?? '']: view } }))
  },
}))

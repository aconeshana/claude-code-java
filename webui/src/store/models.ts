import { create } from 'zustand'
import { fetchCustomModels, removeCustomModel, saveCustomModel } from '../api/client'
import type { CustomModelEntry, ModelProtocol } from '../api/types'

/**
 * Custom model catalogue state: the process-wide list `/model` and this
 * panel share. Save answers with the refreshed listing, so a successful
 * write updates the view without a second fetch.
 */

export interface SaveModelInput {
  readonly modelName: string
  readonly protocol: ModelProtocol
  readonly baseUrl: string
  readonly apiKey?: string | null
  readonly headers?: Readonly<Record<string, string>>
  readonly contextWindow?: number | null
  /** `undefined` keeps the existing flag; `null`/`false`/`true` set it. */
  readonly multimodal?: boolean | null
}

export interface ModelsStore {
  readonly models: readonly CustomModelEntry[]
  readonly loading: boolean
  readonly error: string | null
  refresh(): Promise<void>
  save(input: SaveModelInput): Promise<void>
  remove(modelName: string): Promise<void>
}

export const useModels = create<ModelsStore>((set, get) => ({
  models: [],
  loading: false,
  error: null,

  async refresh() {
    set({ loading: true })
    try {
      const listing = await fetchCustomModels()
      set({ models: listing.models, loading: false, error: null })
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async save(input) {
    try {
      const listing = await saveCustomModel(input)
      set({ models: listing.models, error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },

  async remove(modelName) {
    try {
      await removeCustomModel(modelName)
      set({ models: get().models.filter((model) => model.model_name !== modelName), error: null })
    } catch (failure) {
      set({ error: messageOf(failure) })
    }
  },
}))

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

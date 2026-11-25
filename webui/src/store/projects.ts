import { create } from 'zustand'
import {
  createWebProject,
  deleteWebProject,
  fetchWebProjects,
  renameWebProject,
} from '../api/client'
import type { WebProjectEntry } from '../api/types'

export interface ProjectsStore {
  readonly projects: readonly WebProjectEntry[]
  readonly loading: boolean
  readonly error: string | null
  refresh(): Promise<void>
  createProject(input: {
    path: string
    name?: string
    additional_directories?: readonly string[]
    tier?: 'local' | 'project'
  }): Promise<WebProjectEntry>
  renameProject(path: string, name: string): Promise<WebProjectEntry>
  removeProject(path: string): Promise<void>
}

export function basenameOf(path: string): string {
  const cleaned = path.replace(/[\\/]+$/, '')
  const segments = cleaned.split(/[\\/]/).filter(Boolean)
  return segments[segments.length - 1] ?? path
}

export function projectDisplayName(
  path: string,
  projects: readonly WebProjectEntry[],
): string {
  const found = projects.find((entry) => entry.path === path)
  if (found != null && found.name.trim() !== '') return found.name.trim()
  return basenameOf(path)
}

export const useProjects = create<ProjectsStore>((set) => ({
  projects: [],
  loading: false,
  error: null,

  async refresh() {
    set({ loading: true })
    try {
      const listing = await fetchWebProjects()
      set({ projects: listing.projects, loading: false, error: null })
    } catch (failure) {
      set({ loading: false, error: messageOf(failure) })
    }
  },

  async createProject(input) {
    const response = await createWebProject(input)
    set({ projects: response.projects, error: null })
    return response.project
  },

  async renameProject(path, name) {
    const response = await renameWebProject(path, name)
    set({ projects: response.projects, error: null })
    return response.project
  },

  async removeProject(path) {
    const listing = await deleteWebProject(path)
    set({ projects: listing.projects, error: null })
  },
}))

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

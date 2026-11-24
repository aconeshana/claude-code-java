/**
 * Directory browser wire types, matching upstream `@deepseek-ai/dsh-api-remotes/client`.
 */
export interface DirectoryEntry {
  name: string
  path: string
  hidden: boolean
}

export interface DirectoryListing {
  path: string
  home: string
  crumbs: DirectoryEntry[]
  entries: DirectoryEntry[]
  truncated: boolean
}

export type Translate = (key: string, params?: Record<string, string | number>) => string

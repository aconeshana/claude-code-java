/**
 * The composer's multi-source `@` reference grammar & candidate engine.
 *
 * Supports three context sources in a single prompt (and arbitrary multiple
 * `@` mentions on the same line):
 * - Files:    `@path/to/file.ts` or `@src/my\ file.ts`
 * - Sessions: `@session:<sessionId>`
 * - Agents:   `@agent:<agentIdOrExpert>`
 *
 * File escaping matches the backend `AtMentionedFilesProvider` pattern
 * `(?:^|\s)@((?:[^\s\\]|\\ )+)` verbatim so every picked file round-trips.
 */

/** The active `@` token under the caret, or null when there is none. */
export interface AtToken {
  /** Offset of the `@` in the draft text. */
  readonly start: number
  /** Offset just past the token (the caret). */
  readonly end: number
  /** The typed query after `@`, unescaped. */
  readonly query: string
}

export type AtReferenceKind = 'file' | 'session' | 'agent'

export interface ParsedAtReference {
  readonly kind: AtReferenceKind
  readonly target: string
  readonly raw: string
}

export interface AtFileCandidateInput {
  readonly path: string
  readonly directory: boolean
}

export interface AtSessionCandidateInput {
  readonly id: string
  readonly title: string
  readonly projectName?: string | undefined
}

export interface AtAgentCandidateInput {
  readonly id: string
  readonly label: string
  readonly description?: string | undefined
  readonly type?: string | undefined
}

export interface MultiSourceAtCandidates {
  readonly files: readonly AtFileCandidateInput[]
  readonly sessions: readonly AtSessionCandidateInput[]
  readonly agents: readonly AtAgentCandidateInput[]
}

/** Built-in agent experts always selectable via `@agent:` or bare `@`. */
export const BUILTIN_AGENT_EXPERTS: readonly AtAgentCandidateInput[] = [
  { id: 'explore', label: 'Explore', description: '只读代码库检索与架构分析专家', type: 'builtin' },
  { id: 'plan', label: 'Plan', description: '方案设计与分步实施规划专家', type: 'builtin' },
  { id: 'general-purpose', label: 'General', description: '通用多步执行子智能体', type: 'builtin' },
  { id: 'code-reviewer', label: 'Code Reviewer', description: '代码审查与回归风险检查专家', type: 'builtin' },
]

const MENTION_PATTERN = /(?:^|\s)(@((?:[^\s\\]|\\ )+))/g
const MAX_SESSIONS = 6
const MAX_AGENTS = 6

/**
 * The active `@` token ending at `caret`, or null.
 */
export function activeAtToken(text: string, caret: number): AtToken | null {
  if (caret <= 0 || caret > text.length) return null
  let start = caret
  while (start > 0) {
    const previous = text[start - 1]!
    if (/\s/.test(previous)) {
      if (start >= 2 && text[start - 2] === '\\') {
        start -= 2
        continue
      }
      break
    }
    start--
  }
  if (text[start] !== '@') return null
  return { start, end: caret, query: unescapeQuery(text.slice(start + 1, caret)) }
}

/** `@my\ file.ts` → `my file.ts` (the provider's own unescaping rule). */
function unescapeQuery(raw: string): string {
  return raw.replace(/\\ /g, ' ')
}

function escapeMentionBody(body: string): string {
  return body.replace(/ /g, '\\ ')
}

/** Format a file path candidate as `@path`. */
export function formatMention(path: string): string {
  return `@${escapeMentionBody(path)}`
}

/** Format a historical session candidate as `@session:<id>`. */
export function formatSessionMention(sessionId: string): string {
  return `@session:${escapeMentionBody(sessionId)}`
}

/** Format an agent/expert candidate as `@agent:<id>`. */
export function formatAgentMention(agentId: string): string {
  return `@agent:${escapeMentionBody(agentId)}`
}

/**
 * Extract all `@` mentions from a prompt in order and classify each into
 * `file`, `session`, or `agent`.
 */
export function parseAtReferences(text: string): ParsedAtReference[] {
  const out: ParsedAtReference[] = []
  for (const match of text.matchAll(MENTION_PATTERN)) {
    const raw = match[1]!
    const body = unescapeQuery(match[2]!)
    if (body.startsWith('session:') && body.length > 'session:'.length) {
      out.push({ kind: 'session', target: body.slice('session:'.length), raw })
    } else if (body.startsWith('agent:') && body.length > 'agent:'.length) {
      out.push({ kind: 'agent', target: body.slice('agent:'.length), raw })
    } else {
      out.push({ kind: 'file', target: body, raw })
    }
  }
  return out
}

function matchesQuery(needle: string, fields: readonly (string | undefined)[]): boolean {
  if (needle === '') return true
  const lower = needle.toLowerCase()
  return fields.some((f) => f !== undefined && f.toLowerCase().includes(lower))
}

/**
 * Filter & group candidates across files, historical sessions, and agents.
 * Supports explicit `session:` and `agent:` prefix scoping as well as unified
 * cross-source search on bare `@` / `@query`.
 */
export function buildMultiSourceAtCandidates(
  rawQuery: string,
  sources: {
    readonly files: readonly AtFileCandidateInput[]
    readonly sessions: readonly AtSessionCandidateInput[]
    readonly agents: readonly AtAgentCandidateInput[]
  },
): MultiSourceAtCandidates {
  const trimmed = rawQuery.trim()
  const lower = trimmed.toLowerCase()
  const sessionScoped = lower.startsWith('session:')
  const agentScoped = lower.startsWith('agent:')

  const sessionQuery = sessionScoped ? trimmed.slice('session:'.length).trim() : trimmed
  const agentQuery = agentScoped ? trimmed.slice('agent:'.length).trim() : trimmed

  // Merge live session subagents ahead of built-in experts, deduped by id.
  const seenAgentIds = new Set<string>()
  const mergedAgents: AtAgentCandidateInput[] = []
  for (const a of [...sources.agents, ...BUILTIN_AGENT_EXPERTS]) {
    const key = a.id.toLowerCase()
    if (seenAgentIds.has(key)) continue
    seenAgentIds.add(key)
    mergedAgents.push(a)
  }

  const files = sessionScoped || agentScoped
    ? []
    : sources.files

  const sessions = agentScoped
    ? []
    : sources.sessions
      .filter((s) => matchesQuery(sessionQuery, [s.id, s.title, s.projectName]))
      .slice(0, MAX_SESSIONS)

  const agents = sessionScoped
    ? []
    : mergedAgents
      .filter((a) => matchesQuery(agentQuery, [a.id, a.label, a.description, a.type]))
      .slice(0, MAX_AGENTS)

  return { files, sessions, agents }
}

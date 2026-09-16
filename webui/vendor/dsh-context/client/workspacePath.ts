/**
 * Shim for the two `@deepseek-ai/dsh-util-workspace-path` helpers the vendored
 * dsh-context client reads. claude-code-java has no right-Sidebar resource
 * addresses, so `fileAddressFor` yields the workspace-resolved absolute path
 * (the caller treats any string as "previewable"; the ContextView never wires
 * `onPreview`, so it is only reached from tests).
 */

/** The workspace browser's basename derivation (both separators). */
export function workspaceTitleOf(cwd: string): string {
  const trimmed = cwd.replace(/[\\/]+$/, '')
  const index = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  return index >= 0 ? trimmed.slice(index + 1) : trimmed
}

export function fileAddressFor(sessionId: string, workspace: string | undefined, path: string): string {
  void sessionId
  if (/^([a-zA-Z]:[\\/]|\/)/.test(path) || workspace === undefined || workspace === '') return path
  return workspace.replace(/\/+$/, '') + '/' + path.replace(/^\.?\/+/, '')
}

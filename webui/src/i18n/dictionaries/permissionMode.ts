/**
 * `permissionMode` namespace: copy for the composer's permission-mode chip
 * (PermissionModeSelect.tsx) — the webui counterpart of the TUI Shift+Tab
 * cycle. Not a port of any upstream dsh dictionary: dsh's own permission
 * surface (`ui-permission-presets`) models a different concept (Codex-style
 * sandbox presets), so this namespace's keys are authored fresh for Claude
 * Code's five remote-selectable `PermissionMode` values. See
 * webui/UPSTREAM.md for the deviation note.
 */
import type { LocaleDict, LocaleId } from '../types'

export const PERMISSION_MODE_NS = 'permissionMode'

export const permissionModeZh = {
  'trigger.fallback': '权限模式',
  'trigger.aria': '权限模式：{title}',
  'menuAria': '权限模式',
  'selectError': '切换失败：{message}',
  'confirm.acknowledge': '我已知晓此模式的风险',
  'confirm.cancel': '取消',
  'confirm.confirm': '切换',
  'confirm.closeAria': '关闭对话框',
  'mode.default.title': '默认',
  'mode.plan.title': '计划模式',
  'mode.acceptEdits.title': '自动接受编辑',
  'mode.bypassPermissions.title': '跳过权限确认',
  'mode.dontAsk.title': '不再询问',
  'mode.bypassPermissions.confirmTitle': '切换到「跳过权限确认」？',
  'mode.bypassPermissions.confirmDescription': '此模式下所有工具调用都会自动放行，不再逐次确认——包括文件写入、命令执行等具有破坏性的操作。请仅在你完全信任当前任务时使用。',
  'mode.dontAsk.confirmTitle': '切换到「不再询问」？',
  'mode.dontAsk.confirmDescription': '此模式下所有权限请求都会被自动批准，不会再弹出确认提示。请仅在你完全信任当前任务时使用。',
  'bypassUnavailable.flag': '未以 --dangerously-skip-permissions 启动，此会话无法使用该模式',
  'bypassUnavailable.policy': '该模式已被管理策略禁用',
} as const

export const permissionModeEn: Record<keyof typeof permissionModeZh, string> = {
  'trigger.fallback': 'Permission mode',
  'trigger.aria': 'Permission mode: {title}',
  'menuAria': 'Permission mode',
  'selectError': 'Switch failed: {message}',
  'confirm.acknowledge': 'I understand the risk of this mode',
  'confirm.cancel': 'Cancel',
  'confirm.confirm': 'Switch',
  'confirm.closeAria': 'Close dialog',
  'mode.default.title': 'Default',
  'mode.plan.title': 'Plan mode',
  'mode.acceptEdits.title': 'Accept edits',
  'mode.bypassPermissions.title': 'Bypass permissions',
  'mode.dontAsk.title': "Don't ask",
  'mode.bypassPermissions.confirmTitle': 'Switch to "Bypass permissions"?',
  'mode.bypassPermissions.confirmDescription': 'Every tool call is auto-approved with no further confirmation — including destructive operations like file writes and command execution. Only use this when you fully trust the current task.',
  'mode.dontAsk.confirmTitle': 'Switch to "Don’t ask"?',
  'mode.dontAsk.confirmDescription': 'Every permission request is auto-approved with no further prompts. Only use this when you fully trust the current task.',
  'bypassUnavailable.flag': 'Not launched with --dangerously-skip-permissions; this session cannot use this mode',
  'bypassUnavailable.policy': 'This mode is disabled by managed policy',
}

export const permissionModeDicts: Readonly<Record<LocaleId, LocaleDict>> = { zh: permissionModeZh, en: permissionModeEn }

import type { LocaleDict, LocaleId } from '../types'
import { DICT_EN, DICT_ZH } from '@dsh-context/client/i18n'

/**
 * The vendored dsh-context dictionaries (client/i18n.ts, ~280 keys per
 * locale) registered under their upstream namespace, plus the few keys the
 * claude-code-java shell adds around them (tab label, dashboard entry,
 * settings section, cold-session note).
 */
export const CONTEXT_NS = 'dsh-context'

const shellZh: LocaleDict = {
  'shell.tab.chat': '对话',
  'shell.tab.context': '上下文',
  'shell.cold': '该会话未在当前进程中打开：上下文时间线仅对活跃会话可用。',
  'shell.noSession': '先在侧栏选择一个会话。',
  'shell.dashboard': '上下文看板',
  'shell.dashboard.tip': '所有活跃会话的上下文用量',
  'shell.jump': '在上下文页签中查看此轮',
  'shell.settings.title': '上下文',
  'shell.settings.desc': '上下文页签的默认视图偏好（保存在本浏览器）。',
  'shell.modal.title': '当前上下文',
  'shell.close': '关闭',
}

const shellEn: LocaleDict = {
  'shell.tab.chat': 'Chat',
  'shell.tab.context': 'Context',
  'shell.cold': 'This session is not open in the current process: the context timeline is available for live sessions only.',
  'shell.noSession': 'Pick a session in the sidebar first.',
  'shell.dashboard': 'Context Dashboard',
  'shell.dashboard.tip': 'Context usage across every live session',
  'shell.jump': 'Show this turn in the Context tab',
  'shell.settings.title': 'Context',
  'shell.settings.desc': 'Default view preferences of the Context tab (stored in this browser).',
  'shell.modal.title': 'Current context',
  'shell.close': 'Close',
}

export const contextDicts: Readonly<Record<LocaleId, LocaleDict>> = {
  zh: { ...DICT_ZH, ...shellZh },
  en: { ...DICT_EN, ...shellEn },
}

import { useCallback, useEffect, useState } from 'react'
import clsx from 'clsx'
import {
  Button,
  IconCloseOutline16,
  IconEditOutline16,
  IconFolderOpenOutline16,
  IconPlusOutline16,
  Input,
} from '@primitives'
import { DirectoryBrowser } from '@vendor/ui-directory-picker-browse/DirectoryBrowser'
import {
  addAdditionalDirectories,
  createDirectory,
  fetchProjectCustomizations,
  listDirectory,
  removeAdditionalDirectories,
} from '../api/client'
import type { ProjectCustomizations } from '../api/types'
import { useTranslate } from '../i18n/useTranslate'
import {
  DIRECTORY_BROWSER_NS,
  directoryBrowserDicts,
} from '@vendor/ui-directory-picker-browse/locales'
import { basenameOf, projectDisplayName, useProjects } from '../store/projects'
import { toast } from '../store/toast'
import css from './ProjectSettingsSection.module.css'

const RULES_TOKEN_BUDGET = 20_000

export function ProjectSettingsSection({ projectPath }: { projectPath: string }) {
  const dirT = useTranslate(DIRECTORY_BROWSER_NS, directoryBrowserDicts)
  const webProjects = useProjects((state) => state.projects)
  const renameProject = useProjects((state) => state.renameProject)
  const refreshProjects = useProjects((state) => state.refresh)

  const displayName = projectDisplayName(projectPath, webProjects)

  const [editingName, setEditingName] = useState(false)
  const [nameDraft, setNameDraft] = useState(displayName)
  const [writeTier, setWriteTier] = useState<'local' | 'project'>('local')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [mutating, setMutating] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [data, setData] = useState<ProjectCustomizations | null>(null)
  const [showRulesBreakdown, setShowRulesBreakdown] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await fetchProjectCustomizations(projectPath)
      setData(result)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setLoading(false)
    }
  }, [projectPath])

  useEffect(() => {
    setEditingName(false)
    setNameDraft(displayName)
    setShowRulesBreakdown(false)
    void load()
  }, [projectPath, load])

  useEffect(() => {
    if (!editingName) setNameDraft(displayName)
  }, [displayName, editingName])

  const handleSaveName = async () => {
    const trimmed = nameDraft.trim()
    const normalized = trimmed === basenameOf(projectPath) ? '' : trimmed
    setMutating(true)
    try {
      await renameProject(projectPath, normalized)
      setEditingName(false)
      toast.success('已更新项目名称')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setMutating(false)
    }
  }

  const handleAddFolder = async (pickedPath: string) => {
    setPickerOpen(false)
    const trimmed = pickedPath.trim()
    if (trimmed === '' || trimmed === projectPath) return
    setMutating(true)
    setError(null)
    try {
      await addAdditionalDirectories([trimmed], writeTier, projectPath)
      await Promise.all([load(), refreshProjects()])
      toast.success(`已添加目录「${basenameOf(trimmed)}」`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setMutating(false)
    }
  }

  const handleRemoveFolder = async (dir: string, tier: 'local' | 'project') => {
    setMutating(true)
    setError(null)
    try {
      await removeAdditionalDirectories([dir], tier, projectPath)
      await Promise.all([load(), refreshProjects()])
      toast.info(`已移除目录「${basenameOf(dir)}」`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setMutating(false)
    }
  }

  const localDirs = data?.folders.local_directories ?? []
  const projectDirs = data?.folders.project_directories ?? []
  const userDirs = data?.folders.user_directories ?? []
  const localSet = new Set(localDirs)
  const projectSet = new Set(projectDirs)
  const additionalList = data?.folders.additional ?? []

  const rulesTokens = data?.rules.total_tokens ?? 0
  const rulesPct = Math.min(100, Math.round((rulesTokens / RULES_TOKEN_BUDGET) * 1000) / 10)
  const ruleFiles = data?.rules.files ?? []
  const skills = data?.skills ?? []
  const mcpServers = data?.mcp_servers ?? []

  return (
    <div className={css.root}>
      <div className={css.headerBlock}>
        {editingName ? (
          <div className={css.renameInline}>
            <Input
              value={nameDraft}
              autoFocus
              aria-label="项目名称"
              onChange={(event) => { setNameDraft(event.target.value) }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void handleSaveName()
                if (event.key === 'Escape') {
                  setEditingName(false)
                  setNameDraft(displayName)
                }
              }}
            />
            <Button variant="primary" disabled={mutating} onClick={() => { void handleSaveName() }}>
              保存
            </Button>
            <Button
              variant="outline"
              disabled={mutating}
              onClick={() => {
                setEditingName(false)
                setNameDraft(displayName)
              }}
            >
              取消
            </Button>
          </div>
        ) : (
          <div className={css.titleRow}>
            <h2 className={css.title}>{displayName}</h2>
            <button
              type="button"
              className={css.editTitleBtn}
              aria-label="重命名项目"
              title="重命名项目"
              onClick={() => { setEditingName(true) }}
            >
              <IconEditOutline16 size={14} />
            </button>
          </div>
        )}
        <div className={css.subtitle}>管理项目绑定目录与自定义配置（完全兼容原生 Claude Code 配置）</div>
      </div>

      {error != null && <div className={css.error} role="alert">{error}</div>}

      {/* Folders */}
      <section className={css.block}>
        <div className={css.blockHeader}>
          <div className={css.blockTitle}>
            <span>Folders</span>
            <span className={css.countBadge}>{1 + additionalList.length}</span>
          </div>
          <div className={css.tierSwitch} role="group" aria-label="附加目录保存范围">
            <button
              type="button"
              className={clsx(css.tierBtn, writeTier === 'local' && css.tierBtnActive)}
              onClick={() => { setWriteTier('local') }}
              title="写入 .claude/settings.local.json（仅本机生效，不提交 Git）"
            >
              仅本机 .local
            </button>
            <button
              type="button"
              className={clsx(css.tierBtn, writeTier === 'project' && css.tierBtnActive)}
              onClick={() => { setWriteTier('project') }}
              title="写入 .claude/settings.json（随项目仓库共享）"
            >
              团队共享
            </button>
          </div>
        </div>

        <div className={css.folderList}>
          <div className={css.folderCard}>
            <IconFolderOpenOutline16 size={16} className={css.folderIcon} />
            <div className={css.folderInfo}>
              <div className={css.folderNameRow}>
                <span className={css.folderName}>{basenameOf(projectPath)}/</span>
                <span className={css.scopeTag}>主目录</span>
              </div>
              <span className={css.folderPath} title={projectPath}>{projectPath}</span>
            </div>
          </div>

          {additionalList.map((dir) => {
            const isLocal = localSet.has(dir)
            const isProject = projectSet.has(dir)
            const isUserOnly = !isLocal && !isProject && userDirs.includes(dir)
            const removableTier: 'local' | 'project' | null = isLocal ? 'local' : isProject ? 'project' : null
            return (
              <div key={dir} className={css.folderCard}>
                <IconFolderOpenOutline16 size={16} className={css.folderIcon} />
                <div className={css.folderInfo}>
                  <div className={css.folderNameRow}>
                    <span className={css.folderName}>{basenameOf(dir)}/</span>
                    <span className={css.scopeTag}>
                      {isLocal ? '本机 .local' : isProject ? '项目共享' : isUserOnly ? '全局 user' : '附加目录'}
                    </span>
                  </div>
                  <span className={css.folderPath} title={dir}>{dir}</span>
                </div>
                {removableTier != null && (
                  <button
                    type="button"
                    className={css.removeBtn}
                    disabled={mutating}
                    aria-label={`移除目录 ${dir}`}
                    title="从项目中移除此附加目录"
                    onClick={() => { void handleRemoveFolder(dir, removableTier) }}
                  >
                    <IconCloseOutline16 size={12} />
                  </button>
                )}
              </div>
            )
          })}

          <button
            type="button"
            className={css.addFolderBtn}
            disabled={mutating || loading}
            onClick={() => { setPickerOpen(true) }}
          >
            <IconPlusOutline16 size={14} />
            <span>添加目录</span>
          </button>
        </div>
      </section>

      {/* Customizations: Rules / Skills / MCP */}
      <section className={css.block}>
        <div className={css.blockTitle}>Customizations</div>
        <div className={css.card}>
          <div className={css.cardDesc}>
            展示当前项目生效的规则文件（CLAUDE.md / .claude/rules）、Skills 与 MCP 服务概览。
          </div>
          <div className={css.rulesMeterRow}>
            <span className={css.rulesLabel}>Rules</span>
            <span className={css.rulesTokens}>
              {rulesTokens.toLocaleString()} / {RULES_TOKEN_BUDGET.toLocaleString()} tokens ({rulesPct}%)
            </span>
          </div>
          <div className={css.progressTrack}>
            <div className={css.progressFill} style={{ width: `${rulesPct}%` }} />
          </div>
          {ruleFiles.length > 0 && (
            <button
              type="button"
              className={css.breakdownToggle}
              onClick={() => { setShowRulesBreakdown((prev) => !prev) }}
            >
              {showRulesBreakdown ? '收起规则明细' : `查看 ${ruleFiles.length} 个规则文件`}
            </button>
          )}
          {showRulesBreakdown && ruleFiles.length > 0 && (
            <div className={css.breakdownList}>
              {ruleFiles.map((file) => (
                <div key={file.path} className={css.breakdownRow}>
                  <div className={css.breakdownLeft}>
                    <span className={css.scopeTag}>{file.scope}</span>
                    <span className={css.breakdownPath} title={file.path}>{file.name}</span>
                  </div>
                  <span className={css.rulesTokens}>{file.tokens.toLocaleString()} tokens</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className={css.block}>
        <div className={css.blockTitle}>
          <span>Skills</span>
          <span className={css.countBadge}>{skills.length}</span>
        </div>
        {loading && data == null ? (
          <div className={css.empty}>加载中…</div>
        ) : skills.length === 0 ? (
          <div className={css.empty}>当前项目未发现自定义 Skills。</div>
        ) : (
          <div className={css.itemList}>
            {skills.map((skill) => (
              <div key={`${skill.source}:${skill.name}`} className={css.itemRow}>
                <div className={css.itemHeader}>
                  <span className={css.itemName}>{skill.name}</span>
                  <span className={css.scopeTag}>{skill.source === 'project' ? 'Project' : 'Global'}</span>
                </div>
                {skill.description !== '' && (
                  <div className={css.itemDesc}>{skill.description}</div>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      <section className={css.block}>
        <div className={css.blockTitle}>
          <span>MCP Servers</span>
          <span className={css.countBadge}>{mcpServers.length}</span>
        </div>
        {loading && data == null ? (
          <div className={css.empty}>加载中…</div>
        ) : mcpServers.length === 0 ? (
          <div className={css.empty}>当前项目未配置 MCP 服务。</div>
        ) : (
          <div className={css.itemList}>
            {mcpServers.map((server) => (
              <div key={`${server.scope}:${server.name}`} className={css.itemRow}>
                <div className={css.itemHeader}>
                  <span className={css.itemName}>{server.name}</span>
                  <span className={css.scopeTag}>{server.scope}</span>
                  <span className={css.scopeTag}>{server.type}</span>
                  {server.disabled && <span className={css.scopeTag}>已禁用</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <DirectoryBrowser
        open={pickerOpen}
        listDirectory={listDirectory}
        createDirectory={createDirectory}
        onOpen={(picked) => { void handleAddFolder(picked) }}
        onClose={() => { setPickerOpen(false) }}
        busy={mutating}
        t={dirT}
      />
    </div>
  )
}

import { useEffect, useState } from 'react'
import { Input, Pill, Switch } from '@primitives'
import { useCcConnect } from '../store/ccConnect'
import type { CcConnectSetupMode, CcConnectTarget } from '../api/types'
import { MenuSelect, SettingsRow } from './SettingsPanel'
import localCss from './SettingsPanel.module.css'
import cardCss from './ModelsSection.module.css'

const SETUP_MODE_OPTIONS: readonly { id: CcConnectSetupMode; label: string }[] = [
  { id: 'bind', label: '绑定已有飞书应用 (App ID / Secret)' },
  { id: 'create', label: '扫码创建新飞书机器人' },
  { id: 'resume', label: '继续目标群发现 (Resume)' },
]

function targetStatusLabel(target: CcConnectTarget): string {
  if (target.attached && target.status === 'connected') {
    return target.channel !== '' ? `监听中 · ${target.channel}` : '监听中'
  }
  if (target.status === 'starting') return '启动中…'
  if (target.status === 'error') return '异常'
  return '未监听'
}

export function CcConnectSection() {
  const snapshot = useCcConnect((state) => state.snapshot)
  const loading = useCcConnect((state) => state.loading)
  const busyPids = useCcConnect((state) => state.busyPids)
  const error = useCcConnect((state) => state.error)
  const logsByPid = useCcConnect((state) => state.logsByPid)
  const setupRunning = useCcConnect((state) => state.setupRunning)
  const setupLogs = useCcConnect((state) => state.setupLogs)
  const setupResult = useCcConnect((state) => state.setupResult)
  const refresh = useCcConnect((state) => state.refresh)
  const attach = useCcConnect((state) => state.attach)
  const detach = useCcConnect((state) => state.detach)
  const loadLogs = useCcConnect((state) => state.loadLogs)
  const runSetup = useCcConnect((state) => state.runSetup)
  const cancelSetup = useCcConnect((state) => state.cancelSetup)

  const [selectedChannels, setSelectedChannels] = useState<Record<number, string>>({})
  const [expandedLogsPid, setExpandedLogsPid] = useState<number | null>(null)
  const [showSetup, setShowSetup] = useState(false)
  const [setupMode, setSetupMode] = useState<CcConnectSetupMode>('bind')
  const [appId, setAppId] = useState('')
  const [appSecret, setAppSecret] = useState('')

  useEffect(() => {
    void refresh()
  }, [refresh])

  const channels = snapshot?.available_channels ?? []
  const targets = snapshot?.targets ?? []
  const channelOptions = (channels.length > 0 ? channels : ['feishu']).map((ch) => ({
    id: ch,
    label: ch.charAt(0).toUpperCase() + ch.slice(1),
  }))

  function channelFor(target: CcConnectTarget): string {
    return selectedChannels[target.pid]
      ?? (target.channel !== '' ? target.channel : (channels[0] ?? 'feishu'))
  }

  function handleToggleLogs(pid: number) {
    if (expandedLogsPid === pid) {
      setExpandedLogsPid(null)
      return
    }
    setExpandedLogsPid(pid)
    void loadLogs(pid)
  }

  async function handleStartSetup() {
    await runSetup({
      mode: setupMode,
      ...(setupMode === 'bind' ? { appId: appId.trim(), appSecret } : {}),
    })
    setAppSecret('')
  }

  return (
    <>
      <p className={cardCss.intro}>
        CC Connector 由 Web 守护进程统一托管，可在此配置飞书/外部 IM 渠道，并按需开启或关闭对任意在线 TUI 终端进程的实时双向同步与权限审批。从 TUI 底栏开启外部协作时也会自动上报至此同步显示。
      </p>

      {error != null && <div className={localCss.error} role="alert">{error}</div>}

      <div className={cardCss.card}>
        <SettingsRow
          title="IM 通道配置状态"
          description={
            snapshot == null
              ? '正在读取配置状态…'
              : !snapshot.supported
                ? '当前平台不支持 CC Connector（需要 macOS 或 Linux）'
                : snapshot.configured
                  ? `已就绪 · 配置文件：${snapshot.config_path}`
                  : snapshot.setup_pending
                    ? `已保存飞书凭据，请向机器人发一条消息以完成目标群发现`
                    : `尚未配置飞书/IM 外部协作（配置文件：${snapshot.config_path || '~/.claude/cc-connect.toml'}）`
          }
        >
          <div className={localCss.inline}>
            <Pill>
              {snapshot?.configured
                ? `已配置 (${channels.join(', ') || 'feishu'})`
                : snapshot?.setup_pending
                  ? '待发现目标群'
                  : '未配置'}
            </Pill>
            <button
              type="button"
              className={localCss.button}
              disabled={snapshot != null && !snapshot.supported}
              onClick={() => {
                if (snapshot?.setup_pending) setSetupMode('resume')
                setShowSetup((open) => !open)
              }}
            >
              {showSetup ? '收起向导' : snapshot?.configured ? '重新配置飞书' : '配置飞书'}
            </button>
          </div>
        </SettingsRow>

        {showSetup && (
          <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--border-subtle, rgba(128,128,128,0.2))' }}>
            <SettingsRow title="配置方式" description="App Secret 通过标准输入安全写入 0600 凭据文件，不进入命令行参数">
              <MenuSelect
                value={setupMode}
                label={SETUP_MODE_OPTIONS.find((o) => o.id === setupMode)?.label ?? setupMode}
                options={SETUP_MODE_OPTIONS}
                ariaLabel="Feishu setup mode"
                onChange={(id) => { if (!setupRunning) setSetupMode(id as CcConnectSetupMode) }}
              />
            </SettingsRow>

            {setupMode === 'bind' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
                <Input
                  value={appId}
                  placeholder="飞书 App ID (例如 cli_a1b2c3...)"
                  aria-label="Feishu App ID"
                  disabled={setupRunning}
                  onChange={(e) => { setAppId(e.target.value) }}
                />
                <Input
                  type="password"
                  value={appSecret}
                  placeholder="飞书 App Secret"
                  aria-label="Feishu App Secret"
                  disabled={setupRunning}
                  onChange={(e) => { setAppSecret(e.target.value) }}
                />
              </div>
            )}

            <div className={localCss.inline} style={{ marginTop: 10 }}>
              <button
                type="button"
                className={localCss.button}
                disabled={setupRunning || (setupMode === 'bind' && (appId.trim() === '' || appSecret.trim() === ''))}
                onClick={() => { void handleStartSetup() }}
              >
                {setupRunning ? '配置进行中…' : '开始配置'}
              </button>
              {setupRunning && (
                <button
                  type="button"
                  className={localCss.button}
                  onClick={() => { void cancelSetup() }}
                >
                  取消
                </button>
              )}
              {setupResult != null && (
                <Pill>{setupResult.ok ? `✓ ${setupResult.message}` : `✕ ${setupResult.message}`}</Pill>
              )}
            </div>

            {setupLogs.length > 0 && (
              <pre
                style={{
                  marginTop: 10,
                  padding: 10,
                  maxHeight: 200,
                  overflow: 'auto',
                  fontSize: 12,
                  lineHeight: 1.45,
                  borderRadius: 8,
                  background: 'var(--bg-subtle, rgba(0,0,0,0.06))',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {setupLogs.join('\n')}
              </pre>
            )}
          </div>
        )}
      </div>

      <div className={cardCss.card} style={{ marginTop: 12 }}>
        <SettingsRow
          title={`在线 TUI 监听目标 (${targets.length})`}
          description="选择需要由 CC Connector 监听的在线 TUI 进程；支持同时监听多个 TUI，进程退出时自动回收 sidecar"
        >
          <button
            type="button"
            className={localCss.button}
            disabled={loading}
            onClick={() => { void refresh() }}
          >
            {loading ? '刷新中…' : '刷新列表'}
          </button>
        </SettingsRow>

        {targets.length === 0 ? (
          <p className={cardCss.intro} style={{ marginTop: 10, marginBottom: 0 }}>
            暂无在线 TUI 进程。在终端启动 <code>poco</code> 后，该实例将自动出现在此处。
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 12 }}>
            {targets.map((target) => {
              const busy = Boolean(busyPids[target.pid])
              const selectedChannel = channelFor(target)
              const showingLogs = expandedLogsPid === target.pid
              const logLines = logsByPid[target.pid] ?? []
              return (
                <div
                  key={target.pid}
                  style={{
                    padding: 12,
                    borderRadius: 10,
                    border: '1px solid var(--border-subtle, rgba(128,128,128,0.22))',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 8,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontWeight: 600, fontSize: 13, wordBreak: 'break-all' }}>
                        {target.work_dir || '(unknown workspace)'}
                      </div>
                      <div style={{ fontSize: 12, opacity: 0.72, marginTop: 2 }}>
                        PID {target.pid}
                        {target.session_title !== '' ? ` · ${target.session_title}` : ''}
                        {target.session_id !== '' ? ` (${target.session_id.slice(0, 8)})` : ''}
                      </div>
                    </div>
                    <div className={localCss.inline}>
                      <Pill>{targetStatusLabel(target)}</Pill>
                      {channelOptions.length > 1 && (
                        <MenuSelect
                          value={selectedChannel}
                          label={channelOptions.find((o) => o.id === selectedChannel)?.label ?? selectedChannel}
                          options={channelOptions}
                          ariaLabel={`Channel for PID ${target.pid}`}
                          onChange={(id) => {
                            if (busy || target.attached) return
                            setSelectedChannels((prev) => ({ ...prev, [target.pid]: id }))
                          }}
                        />
                      )}
                      <Switch
                        checked={target.attached}
                        disabled={busy || (!target.attached && !(snapshot?.configured ?? false))}
                        label={`Toggle CC Connector for PID ${target.pid}`}
                        onChange={(checked) => {
                          if (checked) {
                            void attach(target.pid, target.session_id, selectedChannel)
                          } else {
                            void detach(target.pid, target.session_id)
                          }
                        }}
                      />
                    </div>
                  </div>

                  {target.last_error !== '' && (
                    <div className={localCss.error} role="alert" style={{ margin: 0 }}>
                      {target.last_error}
                    </div>
                  )}

                  <div className={localCss.inline} style={{ justifyContent: 'space-between' }}>
                    <span style={{ fontSize: 11, opacity: 0.65 }}>
                      {target.started_at !== '' ? `启动时间：${target.started_at}` : '尚未挂载 sidecar'}
                    </span>
                    <div className={localCss.inline}>
                      {showingLogs && (
                        <button
                          type="button"
                          className={localCss.button}
                          onClick={() => { void loadLogs(target.pid) }}
                        >
                          刷新日志
                        </button>
                      )}
                      <button
                        type="button"
                        className={localCss.button}
                        onClick={() => { handleToggleLogs(target.pid) }}
                      >
                        {showingLogs ? '收起日志' : '查看日志'}
                      </button>
                    </div>
                  </div>

                  {showingLogs && (
                    <pre
                      style={{
                        margin: 0,
                        padding: 10,
                        maxHeight: 220,
                        overflow: 'auto',
                        fontSize: 11.5,
                        lineHeight: 1.4,
                        borderRadius: 8,
                        background: 'var(--bg-subtle, rgba(0,0,0,0.06))',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {logLines.length > 0 ? logLines.join('\n') : '(暂无日志输出)'}
                    </pre>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </>
  )
}

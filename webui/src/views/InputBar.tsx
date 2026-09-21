import { useCallback, useEffect, useRef, useState } from 'react'
import { LexicalComposer } from '@lexical/react/LexicalComposer'
import { PlainTextPlugin } from '@lexical/react/LexicalPlainTextPlugin'
import { ContentEditable } from '@lexical/react/LexicalContentEditable'
import { ClearEditorPlugin } from '@lexical/react/LexicalClearEditorPlugin'
import { HistoryPlugin } from '@lexical/react/LexicalHistoryPlugin'
import { OnChangePlugin } from '@lexical/react/LexicalOnChangePlugin'
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext'
import {
  $createParagraphNode,
  $createTextNode,
  $getRoot,
  CLEAR_EDITOR_COMMAND,
  COMMAND_PRIORITY_CRITICAL,
  KEY_ENTER_COMMAND,
  type EditorState,
  type LexicalEditor,
} from 'lexical'
import { IconPaperclipOutline16, IconPlusOutline16, IconSendOutline16, IconSkillOutline16 } from '@primitives'
import type { UserContentBlock } from '../api/types'
import { fetchComposerCommands, type ComposerCommandEntry } from '../api/client'
import {
  AttachmentError,
  buildContentBlocks,
  filesFromTransfer,
  hasSubmittableContent,
  readAttachment,
  type DraftAttachment,
} from '../attachments/attachments'
import { AttachmentStrip } from './AttachmentStrip'
import {
  clearQueuedSubmission,
  readQueuedSubmission,
  storeQueuedSubmission,
} from './composerQueue'
import { useEnterBehavior } from '../store/enterBehavior'
import { ComposerMenu } from '../../vendor/dsh-composer-menu/ComposerMenu'
import { menuReduce, MENU_CLOSED, type MenuCandidate, type MenuState } from '../../vendor/dsh-composer-menu/menuCore'
import { sectionRows } from '../../vendor/dsh-composer-menu/sectionRows'
import { ModelSelect } from '../../vendor/dsh-model-select/ModelSelect'
import { ContextMeter } from '../../vendor/dsh-context-meter/ContextMeter'
import { StatsPills } from '../../vendor/dsh-stats-pills/StatsPills'
import { PermissionModeSelect } from '../../vendor/dsh-permission-select/PermissionModeSelect'
import { useSessionContext } from '../store/sessionContext'
import { usePermissionMode } from '../store/permissionMode'
import { useTranslate } from '../i18n/useTranslate'
import { STATS_NS, statsDicts } from '../i18n/dictionaries/stats'
import css from '@chat-styles/InputBar.module.css'

/**
 * Composer over the vendored dsh InputBar card (22px radius, input-major
 * fill, soft elevation), built on Lexical plain-text editing: paragraphs are
 * <p> blocks exactly as the vendored .input CSS expects, Enter submits,
 * Shift+Enter breaks a line, and IME composition is protected on the keydown
 * edge. The send control reuses the vendored .primary circle (34px,
 * info-fill blue, white glyph) matching figma 34:10465.
 *
 * Attachments: image pastes onto the composer and drops onto the card land
 * in the same pending strip; the file picker itself lives in the "+" menu's
 * Add section (dsh's composer registers File through the same command-menu
 * API, so the + button and a typed / open one menu). On submit the draft
 * text and attachments become one Anthropic content-block array (text +
 * image/document base64 blocks).
 *
 * The "+" menu (vendored dsh ComposerMenu): opens over the card's overlay
 * anchor with the Add section (file picker) and a Commands section fed by
 * GET /api/commands — the gateway's slash-command registry with skills
 * included. A command pick inserts "/name " into the draft (dsh's claim
 * flow simplified: this composer has no argument-claim machine, so the
 * token lands as plain text and Enter submits it through the turn pipeline,
 * which dispatches slash commands server-side).
 *
 * Busy-Enter behavior (mirrors dsh ui-conversation's queue/steer preference):
 * while a turn is running and the preference is 'queue', submitting holds
 * the built content client-side and flushes it via onSubmit once `busy`
 * flips back to false. The queue is per-session and persisted (see
 * ./composerQueue): a switch to another session never flushes the parked
 * text — only the session it was queued under can, on its own busy→idle
 * edge. 'steer' has no backend turn-injection route in this gateway, so it
 * falls through to today's immediate-send behavior.
 */
export function InputBar({ disabled, busy, placeholder, onSubmit, sessionId, onClientCommand }: {
  disabled: boolean
  busy: boolean
  placeholder: string
  onSubmit: (content: string | readonly UserContentBlock[]) => void
  /** The conversation this composer addresses; the model seat and meter follow it. */
  sessionId?: string | null
  /**
   * Client-side slash commands (dsh-context's `/context` registers on the
   * harness slash source and never reaches the model): the trimmed draft
   * is offered here first; `true` = handled, the draft clears, no turn.
   */
  onClientCommand?: ((command: string) => boolean) | undefined
}) {
  const [attachments, setAttachments] = useState<readonly DraftAttachment[]>([])
  const [attachmentError, setAttachmentError] = useState<string | null>(null)
  const [draftText, setDraftText] = useState('')
  // The parked submission (composerQueue) re-rendered into state so the
  // notice row and the flush effect both react to it; localStorage stays the
  // source of truth (it survives a reload mid-turn).
  const [queuedContent, setQueuedContent] = useState<string | readonly UserContentBlock[] | null>(null)
  // "+" menu: the vendored reducer state over the /api/commands catalogue.
  // The catalogue fetch is per-open (not interval-polled) — dsh warms its
  // directory per session scope; a fresh open is this app's equivalent.
  const [menu, setMenu] = useState<MenuState>(MENU_CLOSED)
  // Selectors, not the whole-store subscription: zustand hands a new state
  // object to whole-store subscribers on every set (even no-op writes), and
  // the refresh effect below keys on these handles — a whole-store handle
  // would re-run the effect on every refresh, and each refresh writes state,
  // closing a render loop that storms the gateway with GETs and freezes the
  // page. The store's action functions are stable references.
  const sessionContextSelection = useSessionContext((state) => state.selection)
  const sessionContextUsage = useSessionContext((state) => state.usage)
  const sessionContextMetrics = useSessionContext((state) => state.metrics)
  const refreshSessionContext = useSessionContext((state) => state.refresh)
  const selectModel = useSessionContext((state) => state.selectModel)
  const selectEffort = useSessionContext((state) => state.selectEffort)
  const permissionMode = usePermissionMode((state) => state.state)
  const refreshPermissionMode = usePermissionMode((state) => state.refresh)
  const selectPermissionMode = usePermissionMode((state) => state.select)
  // The stat pills' locale seat (upstream ui-chat's own 'chat' namespace).
  const statsT = useTranslate(STATS_NS, statsDicts)
  const editorRef = useRef<LexicalEditor | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const enterBehavior = useEnterBehavior((state) => state.behavior)
  const wasBusyRef = useRef(busy)
  // The session the current queue state was read for: a queue row parked
  // under another session must never flush through this composer.
  const queuedSessionRef = useRef(sessionId)

  // A session switch (or mount) re-bases the composer's queue view onto the
  // addressed session: its own parked row rehydrates (a reload mid-turn loses
  // component state; localStorage keeps the row), any other session's row
  // drops from view (it stays in storage for its own session's next mount),
  // and the busy-edge baseline resets so the switch itself never flushes.
  useEffect(() => {
    queuedSessionRef.current = sessionId
    wasBusyRef.current = busy
    setQueuedContent(sessionId == null ? null : readQueuedSubmission(sessionId))
  }, [sessionId]) // busy is the edge baseline, read once per re-base

  // The flush: only the session the row was queued under, only on its own
  // busy→idle edge. A session switch resets the baseline above, so a parked
  // row can never ride another session's edge out.
  useEffect(() => {
    if (wasBusyRef.current && !busy
        && queuedContent != null && queuedSessionRef.current === sessionId) {
      onSubmit(queuedContent)
      if (sessionId != null) clearQueuedSubmission(sessionId)
      setQueuedContent(null)
    }
    wasBusyRef.current = busy
  }, [busy, queuedContent, onSubmit, sessionId])

  // Model seat + context meter: refreshed on mount, on every conversation
  // switch (the addressed session moves), after every settled turn (the
  // finalized usage anchor moves then), and after each selection change
  // (the store already refreshes itself there).
  useEffect(() => {
    void refreshSessionContext(sessionId)
  }, [refreshSessionContext, busy, sessionId])

  // Permission-mode chip: same refresh cadence as the model seat above —
  // mount, conversation switch, and every settled turn (a hook or /permission
  // command mid-turn can move the mode without a chip-driven refresh).
  useEffect(() => {
    void refreshPermissionMode(sessionId)
  }, [refreshPermissionMode, busy, sessionId])

  const addFiles = (files: readonly File[]): void => {
    if (files.length === 0) return
    const totalBytes = attachments.reduce((sum, a) => sum + a.bytes, 0)
    void (async () => {
      const added: DraftAttachment[] = []
      let runningBytes = totalBytes
      try {
        for (const file of files) {
          const draft = await readAttachment(file, runningBytes)
          added.push(draft)
          runningBytes += draft.bytes
        }
        setAttachments((current) => [...current, ...added])
        setAttachmentError(null)
      } catch (failure) {
        // Validation failures surface as a notice; earlier files stay added.
        releasePreviews(added)
        setAttachmentError(failure instanceof AttachmentError
          ? failure.message
          : '附件读取失败')
      }
    })()
  }

  const removeAttachment = (id: number): void => {
    setAttachments((current) => current.filter((a) => a.id !== id))
  }

  // --- "+" menu (vendored dsh ComposerMenu over the /api/commands catalogue) ---

  const closeMenu = useCallback((): void => {
    setMenu((current) => menuReduce(current, { type: 'close' }))
  }, [])

  // A disabled composer (no session selected) closes the menu; its trigger
  // button is disabled anyway, but an open menu must not outlive the state
  // that opened it.
  useEffect(() => {
    if (disabled) closeMenu()
  }, [disabled, closeMenu])

  /** Opens the menu: seeds the pending group, then settles the fetch. */
  const openMenu = useCallback((): void => {
    if (disabled) return
    setMenu((current) => {
      const hit = menuReduce(current, { type: 'hit' })
      // The vendored reducer only ever UPDATES already-registered groups —
      // source-settled/source-failed with an unknown source are dropped — and
      // upstream seeded its groups from the multi-source roster, which this
      // app cut. The single 'command' group must therefore be registered
      // here, or every settlement is discarded and the menu opens empty. A
      // refinement with the group already present keeps its stale rows.
      if (hit.groups.some((group) => group.source === 'command')) return hit
      return {
        ...hit,
        groups: [...hit.groups, { source: 'command', status: 'pending' as const, items: [] }],
      }
    })
    void (async () => {
      try {
        const listing = await fetchComposerCommands()
        setMenu((current) => menuReduce(current, {
          type: 'source-settled',
          generation: current.generation,
          source: 'command',
          items: composerCandidates(listing.commands),
        }))
      } catch {
        // A failed source drops its group silently (upstream behavior); an
        // empty ready group auto-closes the menu.
        setMenu((current) => menuReduce(current, {
          type: 'source-failed',
          generation: current.generation,
          source: 'command',
        }))
      }
    })()
  }, [disabled])

  /**
   * A menu pick: the File row opens the picker; a command row inserts
   * "/name " at the draft head (this composer has no argument-claim machine
   * — the token lands as plain text and the turn pipeline dispatches it).
   */
  const pickMenuItem = useCallback((source: string, index: number): void => {
    const group = menu.groups.find((g) => g.source === source)
    const item = group?.status === 'ready' ? group.items[index] : undefined
    if (item === undefined) return // pending rows are fenced off picks
    if (item.value === 'file') {
      closeMenu()
      fileInputRef.current?.click()
      return
    }
    if (item.value === 'command') {
      closeMenu()
      insertAtDraftHead(`/${item.name} `)
    }
  }, [menu.groups, closeMenu])

  /** Inserts text at the draft head over the Lexical root, keeping focus. */
  const insertAtDraftHead = (text: string): void => {
    const editor = editorRef.current
    if (editor == null) return
    editor.update(() => {
      const root = $getRoot()
      const current = root.getTextContent()
      root.clear()
      const textNode = $createTextNode(text + current)
      root.append($createParagraphNode().append(textNode))
      // Park the caret at the end of the merged line so typing continues
      // the command's arguments.
      textNode.select()
    })
  }

  const hoverMenuItem = useCallback((source: string, index: number): void => {
    setMenu((current) => menuReduce(current, { type: 'hover', source, index }))
  }, [])

  /**
   * The open menu's keyboard share (combobox pattern): ArrowUp/ArrowDown
   * cycle the shared highlight, Escape closes, Enter settles the
   * highlighted row. Returns true only when the key was consumed; a closed
   * menu consumes nothing so plain editing is untouched.
   */
  const onMenuKeyDown = useCallback((event: KeyboardEvent): boolean => {
    if (!menu.open) return false
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setMenu((current) => menuReduce(current, { type: 'move', dir: event.key === 'ArrowDown' ? 1 : -1 }))
      return true
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      closeMenu()
      return true
    }
    if (event.key === 'Enter' && menu.highlight !== null) {
      event.preventDefault()
      pickMenuItem(menu.highlight.source, menu.highlight.index)
      return true
    }
    return false
  }, [menu.open, menu.highlight, closeMenu, pickMenuItem])

  const submit = useCallback((): void => {
    if (disabled || !hasSubmittableContent(draftText, attachments)) return
    const text = draftText.trim()
    if (attachments.length === 0 && onClientCommand?.(text) === true) {
      setDraftText('')
      editorRef.current?.dispatchCommand(CLEAR_EDITOR_COMMAND, undefined)
      return
    }
    const content = attachments.length === 0
      ? text
      : buildContentBlocks(text, attachments)
    setDraftText('')
    setAttachments([])
    editorRef.current?.dispatchCommand(CLEAR_EDITOR_COMMAND, undefined)
    if (busy && enterBehavior === 'queue') {
      // Park under the addressed session only; another session's composer
      // never reads it back.
      if (sessionId != null) storeQueuedSubmission(sessionId, content)
      setQueuedContent(content)
      return
    }
    onSubmit(content)
  }, [disabled, draftText, attachments, onSubmit, busy, enterBehavior, sessionId, onClientCommand])

  const onChange = useCallback((state: EditorState) => {
    state.read(() => {
      setDraftText($getRoot().getTextContent())
    })
  }, [])

  return (
    <div className={css.root}>
      <div
        className={`ccj-composer-card ${css.card}`}
        onDragOver={(event) => {
          // Allow drops without navigating the browser to the file.
          if (event.dataTransfer.types.includes('Files')) event.preventDefault()
        }}
        onDrop={(event) => {
          if (!event.dataTransfer.types.includes('Files')) return
          event.preventDefault()
          addFiles(filesFromTransfer(event.dataTransfer))
        }}
      >
        <ComposerMenu
          state={menu}
          onPick={pickMenuItem}
          onHover={hoverMenuItem}
          onDismiss={closeMenu}
          groupTitle={() => '指令'}
          loadingLabel="正在加载…"
          listboxLabel="命令候选建议"
        />
        {attachmentError != null && (
          <div className={css.notice} role="alert">{attachmentError}</div>
        )}
        <AttachmentStrip attachments={attachments} onRemove={removeAttachment} />
        <LexicalComposer
          initialConfig={{
            namespace: 'ccj-composer',
            onError(error) { throw error },
          }}
        >
          <div className={css.grow}>
            <PlainTextPlugin
              contentEditable={
                <ContentEditable
                  className={css.input}
                  style={{
                    // The vendored .input styles the Lexical contenteditable;
                    // this keeps the textarea-era box metrics contract with
                    // ConversationRoot's ResizeObserver.
                    boxSizing: 'border-box',
                    minHeight: 28,
                  }}
                  ariaLabel="消息输入框"
                />
              }
              placeholder={<span className={css.placeholder}>{placeholder}</span>}
              ErrorBoundary={ComposerErrorBoundary}
            />
            <HistoryPlugin />
            <ClearEditorPlugin />
            <EditorRefPlugin editorRef={editorRef} />
            <OnChangePlugin ignoreSelectionChange onChange={onChange} />
            <SubmitKeyPlugin disabled={disabled} onSubmit={submit} onMenuKeyDown={onMenuKeyDown} />
            <MenuArrowKeysPlugin onMenuKeyDown={onMenuKeyDown} />
            <PasteFilesPlugin onFiles={addFiles} />
          </div>
        </LexicalComposer>
        <div className={css.row}>
          <div className={css.tools}>
            <button
              type="button"
              className={css.add}
              disabled={disabled}
              // mousedown, not click: the composer keeps focus (combobox
              // pattern), so typing continues right after the menu opens.
              onMouseDown={(event) => { event.preventDefault() }}
              onClick={openMenu}
              aria-label="命令与技能菜单"
              aria-haspopup="listbox"
              aria-expanded={menu.open}
            >
              <IconPlusOutline16 />
            </button>
          </div>
          <div className={css.modes}>
            <PermissionModeSelect
              state={permissionMode}
              busy={busy}
              select={selectPermissionMode}
            />
            <span style={{ fontSize: 12, color: 'var(--dsw-alias-label-tertiary)' }}>
              {queuedContent != null ? '已排队，将在当前回复结束后发送' : 'Enter 发送 · Shift+Enter 换行'}
            </span>
          </div>
          <div className={css.trailing}>
            <ModelSelect
              selection={sessionContextSelection}
              available={sessionContextSelection != null}
              busy={busy}
              select={selectModel}
              selectEffort={selectEffort}
              s={MODEL_SELECT_STRINGS}
            />
            <ContextMeter
              pressure={sessionContextUsage == null
                ? null
                : sessionContextUsage.used_tokens == null
                  ? { contextWindow: sessionContextUsage.context_window }
                  : { usedTokens: sessionContextUsage.used_tokens, contextWindow: sessionContextUsage.context_window }}
              breakdown={sessionContextUsage?.breakdown == null ? undefined : {
                systemTokens: sessionContextUsage.breakdown.system_tokens,
                toolsTokens: sessionContextUsage.breakdown.tools_tokens,
                messageTokens: sessionContextUsage.breakdown.message_tokens,
              }}
              ariaLabel={(percent) => `已用上下文 ${percent}%`}
              headline={['上下文已用', '']}
              segmentLabels={['系统提示词', '工具定义', '对话消息']}
            />
            <button
              type="button"
              className={css.primary}
              disabled={disabled || !hasSubmittableContent(draftText, attachments)}
              onClick={submit}
              aria-label="发送"
            >
              <IconSendOutline16 />
            </button>
          </div>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={(event) => {
            addFiles([...event.target.files ?? []])
            // Reset so picking the same file again re-fires change.
            event.target.value = ''
          }}
        />
      </div>
      {/* Inside .root (not the card): the vendored `:has([data-composer-stats])`
          rule keys on this subtree, and the row inherits the ancestor tokens. */}
      <StatsPills metrics={sessionContextMetrics} t={statsT} />
    </div>
  )
}

/** Lexical render crash surface: reraise so onError's contract holds. */
function ComposerErrorBoundary({ children }: { children: React.ReactNode }) {
  return <>{children}</>
}

/**
 * The vendored ModelSelect's label strings (upstream locales.ts's `model`
 * namespace, zh column, minus the directory-load lifecycle keys this app's
 * synchronous catalogue never uses).
 */
const MODEL_SELECT_STRINGS = {
  triggerLoading: '正在加载模型…',
  triggerFallback: '选择模型',
  menuAria: '模型与推理等级',
  menuModel: '模型',
  menuEffort: '推理等级',
  effortDefault: 'Default',
  emptyModels: '没有可用的模型。',
  emptyEfforts: '当前模型未提供推理等级。',
  loadError: (message: string) => `目录加载失败：${message}`,
  selectError: (message: string) => `模型操作失败：${message}`,
}

/**
 * Projects the /api/commands listing onto the menu's Add + Commands
 * sections: the File row (this composer's one client-side addition, dsh
 * registers File through the same menu API) then every catalogue row in
 * registry order — skills and commands interleaved, as dsh merges host
 * commands with client contributions.
 */
function composerCandidates(catalogue: readonly ComposerCommandEntry[]): readonly MenuCandidate[] {
  const fileRow: MenuCandidate = {
    name: 'file',
    label: '文件',
    description: '添加图片或文件到输入框',
    icon: IconPaperclipOutline16,
    value: 'file',
  }
  const rows: readonly MenuCandidate[] = catalogue.map((entry) => ({
    name: entry.name,
    ...(entry.description == null ? {} : { description: entry.description }),
    ...(entry.kind === 'skill' ? { icon: IconSkillOutline16 } : {}),
    value: 'command',
  }))
  return sectionRows(fileRow, rows, { add: '添加', commands: '指令' })
}

/**
 * Publishes the editor instance to the parent's ref at mount. OnChangePlugin
 * cannot serve this: its listener only fires on real content updates (and
 * skips empty-prev-state ones), so a command-menu pick landing before the
 * user's first keystroke would find the ref null and silently drop the
 * insert.
 */
function EditorRefPlugin({ editorRef }: {
  editorRef: React.MutableRefObject<LexicalEditor | null>
}) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    editorRef.current = editor
  }, [editor, editorRef])
  return null
}

/**
 * Enter submits (Shift+Enter inserts a newline via the default handler);
 * IME composition never submits. The keydown confirming an IME candidate is
 * also an Enter — submitting then would eat the candidate, so composing
 * events pass through untouched.
 */
function SubmitKeyPlugin({ disabled, onSubmit, onMenuKeyDown }: {
  disabled: boolean
  onSubmit: () => void
  onMenuKeyDown: (event: KeyboardEvent) => boolean
}) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    return editor.registerCommand(
      KEY_ENTER_COMMAND,
      (event: KeyboardEvent | null) => {
        if (event == null) return false
        // The open "+" menu owns Enter while it is open (combobox pattern:
        // focus stays here, so the menu's key handling rides this hook).
        if (onMenuKeyDown(event)) return true
        if (disabled) return false
        if (event.isComposing || event.keyCode === 229) return false
        if (event.shiftKey) return false
        event.preventDefault()
        onSubmit()
        return true
      },
      COMMAND_PRIORITY_CRITICAL,
    )
  }, [editor, disabled, onSubmit, onMenuKeyDown])
  return null
}

/**
 * ArrowUp/ArrowDown/Escape for the open "+" menu. Registered on the editor
 * (the composer keeps focus while the menu is open), so these ride the
 * same KEY_ENTER_COMMAND-style interception: the menu handler consumes the
 * key when the menu is open and returns false otherwise, leaving plain
 * editing untouched.
 */
function MenuArrowKeysPlugin({ onMenuKeyDown }: {
  onMenuKeyDown: (event: KeyboardEvent) => boolean
}) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (onMenuKeyDown(event)) event.preventDefault()
    }
    return editor.registerRootListener((root, previous) => {
      previous?.removeEventListener('keydown', onKeyDown)
      root?.addEventListener('keydown', onKeyDown)
      return () => {
        root?.removeEventListener('keydown', onKeyDown)
      }
    })
  }, [editor, onMenuKeyDown])
  return null
}

/**
 * Intercepts file pastes (screenshots, dragged file copies) before the
 * plain-text plugin can insert anything; text pastes flow through untouched.
 */
function PasteFilesPlugin({ onFiles }: { onFiles: (files: readonly File[]) => void }) {
  const [editor] = useLexicalComposerContext()
  useEffect(() => {
    return editor.registerRootListener((root, previous) => {
      const handlePaste = (event: ClipboardEvent): void => {
        const files = filesFromTransfer(event.clipboardData)
        if (files.length === 0) return
        // Image pastes never insert the file name as text.
        event.preventDefault()
        onFiles(files)
      }
      previous?.removeEventListener('paste', handlePaste)
      root?.addEventListener('paste', handlePaste)
      return () => {
        root?.removeEventListener('paste', handlePaste)
      }
    })
  }, [editor, onFiles])
  return null
}

/** Revokes preview URLs for drafts that never made it into the strip. */
function releasePreviews(drafts: readonly DraftAttachment[]): void {
  for (const draft of drafts) {
    if (draft.previewUrl != null) URL.revokeObjectURL(draft.previewUrl)
  }
}

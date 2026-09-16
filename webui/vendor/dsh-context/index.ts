/**
 * Vendored dsh-context client (Apache-2.0, see LICENSE / NOTICE) — the
 * pure-props cards, helpers, and sheets the claude-code-java webui composes
 * into its Context tab, `/context` modal, and Context Dashboard
 * (`src/views/context/*`). The cordis plugin shell (slots, projections,
 * remotes) is not vendored: data arrives from the Java gateway's
 * `/api/session/context/*` routes through `src/store/contextTimeline.ts`.
 *
 * Import order IS cascade order across same-specificity rules: Tailwind
 * utilities first (the sibling sheets win ties), then base, then the
 * per-component sheets in their upstream order.
 */

import './styles/tailwind.css'
import './styles/base.css'
import './styles/stats.css'
import './styles/jump.css'
import './styles/settings.css'
import './styles/stackedBar.css'
import './styles/trendChart.css'
import './styles/requestDetail.css'
import './styles/events.css'
import './styles/fileCard.css'
import './styles/modal.css'
import './styles/browser.css'
import './styles/detailSections.css'
import './styles/attachments.css'
import './styles/agentGraph.css'
import './styles/overview.css'

export * from './client/viewkit'
export { DICT_EN, DICT_ZH } from './client/i18n'
export type { Translate } from './client/i18n'

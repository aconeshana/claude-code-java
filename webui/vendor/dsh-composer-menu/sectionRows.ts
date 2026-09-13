/**
 * Composer menu grouping — vendored from dsh's ui-commands
 * `src/client/presentation.ts` (`sectionRows`), adapted to this app's
 * flat /api/commands listing.
 *
 * Upstream arranges two sections by a fixed usage-order row list per
 * section, with unlisted rows closing the Commands section in catalog
 * order. This app's catalogue is the gateway's full slash-command registry
 * (commands plus skill projections) with no fixed usage order, so the Add
 * section carries the one client-side addition (the file picker) and every
 * catalogue row lands in the Commands section in registry order — skills
 * and commands interleaved, exactly as dsh merges host commands with
 * client contributions into one list.
 */
import type { MenuCandidate } from './menuCore'

/** Arrange the menu: the Add section first, then the Commands section. */
export function sectionRows(
  fileRow: MenuCandidate | null,
  catalogue: readonly MenuCandidate[],
  labels: { readonly add: string; readonly commands: string },
): readonly MenuCandidate[] {
  const add = fileRow === null ? [] : [{ ...fileRow, section: labels.add }]
  const commands = catalogue.map(row => ({ ...row, section: labels.commands }))
  return [...add, ...commands]
}

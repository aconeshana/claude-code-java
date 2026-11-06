/**
 * The subagent catalog's users glyph — upstream `IconUsersOutlineRegular`
 * (`packages/client/ui-primitives/src/icons/index.tsx`, deepseek-ai/deepseek-harness, MIT).
 *
 * **Deviation, deliberate.** Upstream resolves this glyph through a document
 * `<symbol>` sprite that `ui-theme` maintains (`icons/artwork.ts`):
 * `productIcon('users-outline', 'regular')` renders `<use href="#dsw-icon-users-outline-regular">`,
 * and the theme injects the paths. The local vendored `ui-primitives` carries
 * hand-written inline-`<svg>` icons instead and has no sprite, so porting the
 * catalog for one glyph would mean porting the whole artwork pipeline. The
 * paths below are copied from that sprite's `users-outline` symbol and drawn
 * inline in the local convention (16px box, `currentColor`, `aria-hidden`).
 *
 * Everything else about the glyph's use is upstream's: a 14px slot in the
 * trigger, a 14px icon in the section header.
 */
/** The local primitive icons' own props (square size + placement class). */
interface IconProps {
  size?: number | undefined
  className?: string | undefined
}

/**
 * Two-person outline, one-pixel stroke in a 16px box.
 * @param props.size - rendered square size in px (default 16).
 * @param props.className - extra class for placement.
 * @returns the glyph, hidden from assistive technology.
 */
export function IconUsersOutline({ size = 16, className }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      className={className}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      data-icon="users-outline"
    >
      <path
        d="M6.2 2.8C5.11929 2.8 4.24318 3.67611 4.24318 4.75682C4.24318 5.83753 5.11929 6.71364 6.2 6.71364C7.28071 6.71364 8.15682 5.83753 8.15682 4.75682C8.15682 3.67611 7.28071 2.8 6.2 2.8ZM3.09318 4.75682C3.09318 3.04088 4.48406 1.65 6.2 1.65C7.91594 1.65 9.30682 3.04088 9.30682 4.75682C9.30682 6.47276 7.91594 7.86364 6.2 7.86364C4.48406 7.86364 3.09318 6.47276 3.09318 4.75682Z"
        fill="currentColor"
      />
      <path
        d="M3.10227 8.43636C1.68227 8.43636 0.53125 9.58738 0.53125 11.0074V13.35C0.53125 13.6676 0.788786 13.925 1.10625 13.925H11.2938C11.6112 13.925 11.8688 13.6676 11.8688 13.35V11.0074C11.8688 9.58738 10.7177 8.43636 9.29773 8.43636H3.10227ZM1.68125 11.0074C1.68125 10.2224 2.31727 9.58636 3.10227 9.58636H9.29773C10.0827 9.58636 10.7188 10.2224 10.7188 11.0074V12.775H1.68125V11.0074Z"
        fill="currentColor"
      />
      <path
        d="M11.6 3.03409C11.2161 3.03409 10.9048 3.34536 10.9048 3.72955C10.9048 4.11373 11.2161 4.425 11.6 4.425C12.1288 4.425 12.5575 4.85366 12.5575 5.3825C12.5575 5.91134 12.1288 6.34 11.6 6.34C11.2161 6.34 10.9048 6.65127 10.9048 7.03545C10.9048 7.41964 11.2161 7.73091 11.6 7.73091C12.8975 7.73091 13.9493 6.67905 13.9493 5.3825C13.9493 4.08595 12.8975 3.03409 11.6 3.03409Z"
        fill="currentColor"
      />
      <path
        d="M12.6989 8.74864C13.9973 8.74864 15.0535 9.80484 15.0535 11.1032V13.35C15.0535 13.6676 14.7959 13.925 14.4785 13.925C14.161 13.925 13.9035 13.6676 13.9035 13.35V11.1032C13.9035 10.4396 13.3625 9.89864 12.6989 9.89864C12.3814 9.89864 12.1239 9.64118 12.1239 9.32364C12.1239 9.00609 12.3814 8.74864 12.6989 8.74864Z"
        fill="currentColor"
      />
    </svg>
  )
}
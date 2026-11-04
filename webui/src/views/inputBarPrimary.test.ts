import { describe, expect, it } from 'vitest'
import { resolvesToStop } from './InputBar'

// The composer's two-form primary button, decision only (mounting the Lexical
// composer would test React and Lexical, not this rule). The rule is the
// local projection of upstream ui-conversation's `primaryStops`
// (skeleton/InputBar.tsx), which this suite pins so a later edit cannot
// silently drop the empty-composer condition and make Stop swallow a send.

const stop = (input: { busy: boolean; stopAvailable: boolean; hasContent: boolean }) =>
  resolvesToStop(input)

describe('primary button form', () => {
  it('stops a running turn when the composer is empty', () => {
    expect(stop({ busy: true, stopAvailable: true, hasContent: false })).toBe(true)
  })

  it('keeps Send over an actionable draft, even while running', () => {
    // The condition that matters: a queued/steered click must reach submit,
    // never the stop the user did not aim at.
    expect(stop({ busy: true, stopAvailable: true, hasContent: true })).toBe(false)
  })

  it('keeps Send while idle, empty or not', () => {
    expect(stop({ busy: false, stopAvailable: true, hasContent: false })).toBe(false)
    expect(stop({ busy: false, stopAvailable: true, hasContent: true })).toBe(false)
  })

  it('never renders Stop on a surface that wires no stop callback', () => {
    // Belt for the wiring: a composer with no onStop can never show a stop
    // affordance whose click does nothing.
    expect(stop({ busy: true, stopAvailable: false, hasContent: false })).toBe(false)
    expect(stop({ busy: true, stopAvailable: false, hasContent: true })).toBe(false)
  })
})

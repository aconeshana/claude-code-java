/**
 * Pins the review-pin (`fcc0ad462172`) behaviour of the vendored address
 * grammar — specifically the parts that differ from the earlier `5dda764`
 * base, because that mismatch was shipped once and nothing caught it:
 * `pathPartsOf` did not exist there, and `fileAddressFor` fell back to an
 * `absolute` address where the review pin falls back to a `session` one.
 *
 * If a future re-fetch takes this directory from the wrong commit, these fail.
 */
import { describe, expect, it } from 'vitest'
import {
  absoluteFileAddress, fileAddressFor, parseFileAddress, pathPartsOf, sessionFileAddress,
} from './index.ts'

describe('pathPartsOf', () => {
  it('splits a path at its last separator, keeping the separator on the directory', () => {
    expect(pathPartsOf('src/app/main.ts')).toEqual({ directory: 'src/app/', name: 'main.ts' })
    expect(pathPartsOf('main.ts')).toEqual({ directory: '', name: 'main.ts' })
  })

  it('reads a Windows separator the same way as a POSIX one', () => {
    expect(pathPartsOf('C:\\x\\y.txt')).toEqual({ directory: 'C:\\x\\', name: 'y.txt' })
  })

  it('trims trailing separators before splitting, so a directory reads as a bare name', () => {
    expect(pathPartsOf('src/')).toEqual({ directory: '', name: 'src' })
    expect(pathPartsOf('src/app/')).toEqual({ directory: 'src/', name: 'app' })
  })

  it('returns the whole input as the name when there is no segment to keep', () => {
    expect(pathPartsOf('')).toEqual({ directory: '', name: '' })
    expect(pathPartsOf('///')).toEqual({ directory: '', name: '///' })
  })
})

describe('fileAddressFor', () => {
  it('addresses a workspace-relative path against the session', () => {
    expect(fileAddressFor('s1', '/root', 'src/a.ts')).toBe('dsh-resource://file/session/s1/src/a.ts')
  })

  it('strips the workspace root from an absolute path inside it', () => {
    expect(fileAddressFor('s1', '/root', '/root/src/a.ts')).toBe('dsh-resource://file/session/s1/src/a.ts')
  })

  // The review pin replaced the older `absoluteFileAddress` fallback here: a
  // path outside the root still rides the session, which is what lets the
  // reader's own session resolve it under host-side confinement. The leading
  // separator is NOT re-stripped on this branch (only the in-root branch trims
  // the root), so the encoded path keeps it as an empty first segment — the
  // address round-trips to `/elsewhere/a.ts`. Pinned because it looks like a
  // bug and is upstream's actual behaviour: do not "fix" the extra slash.
  it('keeps a path outside the workspace on the session rather than going absolute', () => {
    const address = fileAddressFor('s1', '/root', '/elsewhere/a.ts')
    expect(address).toBe('dsh-resource://file/session/s1//elsewhere/a.ts')
    expect(parseFileAddress(address)).toEqual({ scope: 'session', sessionId: 's1', path: '/elsewhere/a.ts' })
  })

  it('addresses the workspace root itself as the empty session path', () => {
    expect(fileAddressFor('s1', '/root', '/root')).toBe('dsh-resource://file/session/s1/')
  })

  it('normalizes Windows separators before building the address', () => {
    expect(fileAddressFor('s1', 'C:\\root', 'src\\a.ts')).toBe('dsh-resource://file/session/s1/src/a.ts')
  })
})

describe('address round trip', () => {
  it('parses a session address back to its parts', () => {
    expect(parseFileAddress(sessionFileAddress('s1', 'src/a.ts')))
      .toEqual({ scope: 'session', sessionId: 's1', path: 'src/a.ts' })
  })

  it('parses an absolute address back to a leading-slash path', () => {
    expect(parseFileAddress(absoluteFileAddress('/home/u/notes.txt')))
      .toEqual({ scope: 'absolute', path: '/home/u/notes.txt' })
  })

  it('keeps a drive letter literal and a UNC path double-slashed', () => {
    expect(parseFileAddress(absoluteFileAddress('C:/x/y.txt')))
      .toEqual({ scope: 'absolute', path: 'C:/x/y.txt' })
    expect(parseFileAddress(absoluteFileAddress('//server/share/x.txt')))
      .toEqual({ scope: 'absolute', path: '//server/share/x.txt' })
  })

  it('survives a name carrying a space, a hash or a question mark', () => {
    const address = sessionFileAddress('s1', 'a b#c?d.txt')
    expect(parseFileAddress(address)).toEqual({ scope: 'session', sessionId: 's1', path: 'a b#c?d.txt' })
  })

  // The review pin slices the literal prefix instead of going through `new URL`,
  // so a query or fragment is cut rather than throwing or being mis-parsed.
  it('cuts a query or fragment instead of failing', () => {
    expect(parseFileAddress('dsh-resource://file/session/s1/a.ts?x=1'))
      .toEqual({ scope: 'session', sessionId: 's1', path: 'a.ts' })
    expect(parseFileAddress('dsh-resource://file/session/s1/a.ts#frag'))
      .toEqual({ scope: 'session', sessionId: 's1', path: 'a.ts' })
  })

  it('rejects a string that is not a file address', () => {
    for (const bad of ['', 'src/a.ts', 'dsh-resource://folder/session/s1/a.ts', 'dsh-resource://file/session/s1', 'http://x/a.ts']) {
      expect(parseFileAddress(bad), bad).toBeUndefined()
    }
  })
})
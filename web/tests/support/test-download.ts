import { vi } from 'vitest'

type UrlObjectUrls = {
  createObjectURL?: typeof URL.createObjectURL
  revokeObjectURL?: typeof URL.revokeObjectURL
}

/**
 * Mocks the browser download plumbing jsdom does not implement:
 * - `URL.createObjectURL` / `URL.revokeObjectURL` (jsdom has neither)
 * - anchor clicks, capturing every anchor that would trigger a download
 */
export function mockDownloads() {
  const createObjectURL = vi.fn((_blob: Blob) => 'blob:mock-download')
  const revokeObjectURL = vi.fn()
  const clickedAnchors: HTMLAnchorElement[] = []

  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL })

  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    clickedAnchors.push(this)
  })

  return { clickedAnchors, createObjectURL, revokeObjectURL }
}

export function restoreDownloads() {
  delete (URL as UrlObjectUrls).createObjectURL
  delete (URL as UrlObjectUrls).revokeObjectURL
  vi.restoreAllMocks()
}

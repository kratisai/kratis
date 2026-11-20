import { describe, expect, it } from 'vitest'

import type { WikiPageDto } from '@/lib/wiki-api'

import { buildWikiTreeFromList, compareWikiPages } from '@/lib/wiki-tree'

function page(overrides: Partial<WikiPageDto>): WikiPageDto {
  return {
    content: '',
    hasChildren: false,
    id: 'id-unset',
    orderIndex: 0,
    pageSlug: 'slug-unset',
    parentPageId: null,
    repoName: 'repo',
    title: 'Title',
    ...overrides,
  }
}

describe('compareWikiPages', () => {
  it('orders by orderIndex first', () => {
    const a = page({ id: 'a', orderIndex: 0, pageSlug: 'a', title: 'Alpha' })
    const b = page({ id: 'b', orderIndex: 10, pageSlug: 'b', title: 'Zulu' })

    expect(compareWikiPages(a, b)).toBeLessThan(0)
    expect(compareWikiPages(b, a)).toBeGreaterThan(0)
  })

  it('tie-breaks on title when orderIndex is equal', () => {
    const a = page({ id: 'a', orderIndex: 10, pageSlug: 'zulu', title: 'Alpha' })
    const b = page({ id: 'b', orderIndex: 10, pageSlug: 'alpha', title: 'Zulu' })

    expect(compareWikiPages(a, b)).toBeLessThan(0)
    expect(compareWikiPages(b, a)).toBeGreaterThan(0)
  })

  it('tie-breaks on slug when orderIndex and title are equal', () => {
    const a = page({ id: 'a', orderIndex: 10, pageSlug: 'architecture', title: 'Overview' })
    const b = page({ id: 'b', orderIndex: 10, pageSlug: 'database', title: 'Overview' })

    expect(compareWikiPages(a, b)).toBeLessThan(0)
    expect(compareWikiPages(b, a)).toBeGreaterThan(0)
  })

  it('treats equal keys as equal', () => {
    const a = page({ id: 'a', orderIndex: 5, pageSlug: 'same', title: 'Same' })
    const b = page({ id: 'b', orderIndex: 5, pageSlug: 'same', title: 'Same' })

    expect(compareWikiPages(a, b)).toBe(0)
  })
})

describe('buildWikiTreeFromList', () => {
  it('builds topLevel, childrenMap, and allPagesMap from parent references', () => {
    const root = page({ id: 'root', pageSlug: 'overview', title: 'Overview' })
    const child = page({ id: 'child', pageSlug: 'setup', parentPageId: 'root', title: 'Setup' })
    const grandChild = page({
      id: 'grand-child',
      pageSlug: 'advanced',
      parentPageId: 'child',
      title: 'Advanced',
    })

    const tree = buildWikiTreeFromList([child, grandChild, root])

    expect(tree.topLevel.map((p) => p.id)).toEqual(['root'])
    expect(tree.childrenMap.get('root')?.map((p) => p.id)).toEqual(['child'])
    expect(tree.childrenMap.get('child')?.map((p) => p.id)).toEqual(['grand-child'])
    expect(tree.allPagesMap.size).toBe(3)
  })

  it('sorts siblings deterministically by orderIndex then title then slug', () => {
    const root = page({ id: 'root', pageSlug: 'overview', title: 'Overview' })
    const bySlug = page({
      id: 'by-slug',
      orderIndex: 10,
      pageSlug: 'beta-slug',
      parentPageId: 'root',
      title: 'Overview',
    })
    const byTitle = page({
      id: 'by-title',
      orderIndex: 0,
      pageSlug: 'alpha-slug',
      parentPageId: 'root',
      title: 'Zulu',
    })
    const byOrder = page({
      id: 'by-order',
      orderIndex: 5,
      pageSlug: 'zeta',
      parentPageId: 'root',
      title: 'Alpha',
    })

    const mixedRoots = [
      page({ id: 'z-root', orderIndex: 20, pageSlug: 'z-root', title: 'Z Root' }),
      page({ id: 'a-root', orderIndex: 20, pageSlug: 'a-root', title: 'A Root' }),
    ]

    const tree = buildWikiTreeFromList([bySlug, byOrder, byTitle, ...mixedRoots, root])

    expect(tree.topLevel.map((p) => p.id)).toEqual(['root', 'a-root', 'z-root'])
    expect(tree.childrenMap.get('root')?.map((p) => p.id)).toEqual(['by-title', 'by-order', 'by-slug'])
  })

  it('deduplicates repeated pages in the input list', () => {
    const root = page({ id: 'root', pageSlug: 'overview', title: 'Overview' })

    const tree = buildWikiTreeFromList([root, root])

    expect(tree.topLevel).toHaveLength(1)
    expect(tree.allPagesMap.size).toBe(1)
  })

  it('keeps self-referencing pages out of childrenMap', () => {
    const selfRoot = page({ id: 'self', pageSlug: 'self', parentPageId: 'self', title: 'Self' })

    const tree = buildWikiTreeFromList([selfRoot])

    expect(tree.topLevel.map((p) => p.id)).toEqual(['self'])
    expect(tree.childrenMap.size).toBe(0)
  })

  it('ignores unknown-parent pages when walking from the roots', () => {
    const root = page({ id: 'root', pageSlug: 'overview', title: 'Overview' })
    const orphan = page({
      id: 'orphan',
      pageSlug: 'orphan',
      parentPageId: 'missing-parent',
      title: 'Orphan',
    })

    const tree = buildWikiTreeFromList([root, orphan])

    expect(tree.topLevel.map((p) => p.id)).toEqual(['root'])
    expect(tree.childrenMap.get('missing-parent')?.map((p) => p.id)).toEqual(['orphan'])
  })
})

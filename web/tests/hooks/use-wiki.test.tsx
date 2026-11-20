import type { ReactNode } from 'react'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { useWikiTree } from '@/hooks/use-wiki'

import {
  addFetchHandler,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import { setAuthenticated } from '../support/test-render'

const backendOrder = [
  {
    content: '# Z Page',
    hasChildren: true,
    id: 'page-z',
    orderIndex: 10,
    pageSlug: 'zulu',
    parentPageId: null,
    repoName: 'my-repo',
    title: 'Zulu',
  },
  {
    content: '# A Page',
    hasChildren: false,
    id: 'page-a',
    orderIndex: 10,
    pageSlug: 'alpha',
    parentPageId: null,
    repoName: 'my-repo',
    title: 'Alpha',
  },
  {
    content: '# M Page',
    hasChildren: false,
    id: 'page-m',
    orderIndex: 0,
    pageSlug: 'mid',
    parentPageId: null,
    repoName: 'my-repo',
    title: 'Midway',
  },
]

describe('useWikiTree sibling ordering', () => {
  setupFetchMock()

  it('orders the left-hand menu tree by orderIndex ASC then title ASC', async () => {
    addFetchHandler((url) => {
      if (url.includes('/api/v1/teams/team-1/repositories/repo-1/wiki/pages') && url.endsWith('/pages')) {
        return new Response(JSON.stringify(backendOrder), {
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.includes('/wiki/pages/page-z/children')) {
        // Children arrive already sorted; defensive re-sort keeps the DB order irrelevant.
        return new Response(JSON.stringify([]), { headers: { 'Content-Type': 'application/json' } })
      }
      return null
    })
    setAuthenticated({ teamId: 'team-1' })

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )

    const { result } = renderHook(() => useWikiTree('repo-1'), { wrapper })

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    const tree = result.current.data!
    expect(tree.topLevel.map((p) => p.pageSlug)).toEqual(['mid', 'alpha', 'zulu'])
    expect(tree.allPagesMap.size).toBe(3)
  })
})

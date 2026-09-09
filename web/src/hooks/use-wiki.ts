import { useQuery } from '@tanstack/react-query'

import {
  getWikiChildPages,
  getWikiPage,
  getWikiTopLevelPages,
  type WikiPageDto,
} from '@/lib/wiki-api'
import { useAuthStore } from '@/store/auth-store'

export function useWikiChildPages(repoId: string, pageId: null | string) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!repoId && !!pageId,
    queryFn: () => getWikiChildPages(teamId!, repoId, pageId!),
    queryKey: ['wiki-children', teamId, repoId, pageId],
  })
}

export function useWikiPage(repoId: string, pageId: null | string) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!repoId && !!pageId,
    queryFn: () => getWikiPage(teamId!, repoId, pageId!),
    queryKey: ['wiki-page', teamId, repoId, pageId],
  })
}

export function useWikiTopLevelPages(repoId: string) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!repoId,
    queryFn: () => getWikiTopLevelPages(teamId!, repoId),
    queryKey: ['wiki-pages', teamId, repoId],
  })
}

export function useWikiTree(repoId: string) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!repoId,
    queryFn: async () => {
      const topLevel = await getWikiTopLevelPages(teamId!, repoId)
      const allPagesMap = new Map<string, WikiPageDto>()
      const childrenMap = new Map<string, WikiPageDto[]>()

      for (const page of topLevel) {
        allPagesMap.set(page.id, page)
      }

      // Fetch children for top level pages (Level 2)
      const level1Promises = topLevel
        .filter((page) => page.hasChildren)
        .map(async (parent) => {
          try {
            const children = await getWikiChildPages(teamId!, repoId, parent.id)
            childrenMap.set(parent.id, children)
            for (const child of children) {
              allPagesMap.set(child.id, child)
            }

            // Fetch children for level 2 pages (Level 3)
            const level2Promises = children
              .filter((child) => child.hasChildren)
              .map(async (subChildParent) => {
                try {
                  const subChildren = await getWikiChildPages(teamId!, repoId, subChildParent.id)
                  childrenMap.set(subChildParent.id, subChildren)
                  for (const subChild of subChildren) {
                    allPagesMap.set(subChild.id, subChild)
                  }
                } catch (err) {
                  console.error(`Error fetching sub-children for ${subChildParent.id}:`, err)
                }
              })
            await Promise.all(level2Promises)
          } catch (err) {
            console.error(`Error fetching children for ${parent.id}:`, err)
          }
        })

      await Promise.all(level1Promises)

      return {
        allPagesMap,
        childrenMap,
        topLevel,
      }
    },
    queryKey: ['wiki-tree', teamId, repoId],
  })
}

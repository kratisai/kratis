import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import {
  getTeamWikiBatchLogs,
  getTeamWikiChildPages,
  getTeamWikiPage,
  getTeamWikiStatus,
  getTeamWikiTopLevelPages,
  getWikiChildPages,
  getWikiPage,
  getWikiTopLevelPages,
  type TeamWikiBuildMode,
  triggerTeamWikiRebuild,
  type WikiPageDto,
} from '@/lib/wiki-api'
import { useAuthStore } from '@/store/auth-store'

export function useTeamWikiBatchLogs(batchId: null | string, enabled = true) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!batchId && enabled,
    queryFn: () => getTeamWikiBatchLogs(teamId!, batchId!),
    queryKey: ['team-wiki', teamId, 'logs', batchId],
    refetchInterval: enabled ? 4000 : false,
  })
}

export function useTeamWikiPage(pageId: null | string) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!pageId,
    queryFn: () => getTeamWikiPage(teamId!, pageId!),
    queryKey: ['team-wiki', teamId, 'page', pageId],
  })
}

export function useTeamWikiStatus() {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId,
    queryFn: () => getTeamWikiStatus(teamId!),
    queryKey: ['team-wiki', teamId, 'status'],
  })
}

export function useTeamWikiTree(enabled = true) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && enabled,
    queryFn: () =>
      buildWikiTree(
        () => getTeamWikiTopLevelPages(teamId!),
        (pageId) => getTeamWikiChildPages(teamId!, pageId),
      ),
    queryKey: ['team-wiki', teamId, 'tree'],
  })
}

export function useTriggerTeamWikiRebuild() {
  const teamId = useAuthStore((state) => state.currentTeamId)
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (mode?: TeamWikiBuildMode) => triggerTeamWikiRebuild(teamId!, mode),
    onSuccess: () => {
      if (teamId) {
        void queryClient.invalidateQueries({ queryKey: ['team-wiki', teamId] })
      }
    },
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

export function useWikiTree(repoId: string) {
  const teamId = useAuthStore((state) => state.currentTeamId)

  return useQuery({
    enabled: !!teamId && !!repoId,
    queryFn: () =>
      buildWikiTree(
        () => getWikiTopLevelPages(teamId!, repoId),
        (pageId) => getWikiChildPages(teamId!, repoId, pageId),
      ),
    queryKey: ['wiki-tree', teamId, repoId],
  })
}

async function buildWikiTree(
  fetchTopLevel: () => Promise<WikiPageDto[]>,
  fetchChildren: (pageId: string) => Promise<WikiPageDto[]>,
) {
  const topLevel = await fetchTopLevel()
  const allPagesMap = new Map<string, WikiPageDto>()
  const childrenMap = new Map<string, WikiPageDto[]>()

  for (const page of topLevel) {
    allPagesMap.set(page.id, page)
  }

  const level1Promises = topLevel
    .filter((page) => page.hasChildren)
    .map(async (parent) => {
      try {
        const children = await fetchChildren(parent.id)
        childrenMap.set(parent.id, children)
        for (const child of children) {
          allPagesMap.set(child.id, child)
        }

        const level2Promises = children
          .filter((child) => child.hasChildren)
          .map(async (subChildParent) => {
            try {
              const subChildren = await fetchChildren(subChildParent.id)
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
}

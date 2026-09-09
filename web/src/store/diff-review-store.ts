import { create } from 'zustand'

import type { DiffCommentDraft } from '@/types/diff-types'

interface ActiveBreadcrumb {
  header: string
  path: string
}

interface ActiveCommentBox {
  codeSnippet?: string
  endLine?: number
  line: number
  path: string
  side?: 'new' | 'old'
}

interface DiffReviewState {
  activeCommentBox: ActiveCommentBox | null
  activeStickyBreadcrumb: ActiveBreadcrumb | null
  addDraftComment: (
    executionId: string,
    path: string,
    line: number,
    comment: string,
    codeSnippet?: string,
    endLine?: number,
    side?: 'new' | 'old',
  ) => void
  clearDraftComments: (executionId: string) => void
  collapseAll: (paths: string[]) => void
  collapsedFiles: Record<string, boolean>
  diffViewMode: 'split' | 'unified'
  draftComments: Record<string, DiffCommentDraft[]>
  expandAll: (paths: string[]) => void
  isSteeringFocused: boolean
  removeDraftComment: (executionId: string, commentId: string) => void
  setActiveCommentBox: (box: ActiveCommentBox | null) => void
  setActiveStickyBreadcrumb: (breadcrumb: ActiveBreadcrumb | null) => void
  setDiffViewMode: (mode: 'split' | 'unified') => void
  setIsSteeringFocused: (focused: boolean) => void
  toggleFileCollapsed: (path: string) => void
}

export const useDiffReviewStore = create<DiffReviewState>((set) => ({
  activeCommentBox: null,
  activeStickyBreadcrumb: null,
  addDraftComment: (executionId, path, line, comment, codeSnippet, endLine, side) =>
    set((state) => {
      const list = state.draftComments[executionId] ?? []
      const newDraft: DiffCommentDraft = {
        codeSnippet,
        comment,
        createdAt: Date.now(),
        endLine,
        id: `draft-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        line,
        path,
        side,
      }
      return {
        activeCommentBox: null,
        draftComments: {
          ...state.draftComments,
          [executionId]: [...list, newDraft],
        },
      }
    }),
  clearDraftComments: (executionId) =>
    set((state) => ({
      draftComments: {
        ...state.draftComments,
        [executionId]: [],
      },
    })),
  collapseAll: (paths) =>
    set((state) => {
      const next = { ...state.collapsedFiles }
      for (const p of paths) {
        next[p] = true
      }
      return { collapsedFiles: next }
    }),
  collapsedFiles: {},
  diffViewMode: 'unified',
  draftComments: {},
  expandAll: (paths) =>
    set((state) => {
      const next = { ...state.collapsedFiles }
      for (const p of paths) {
        next[p] = false
      }
      return { collapsedFiles: next }
    }),
  isSteeringFocused: false,
  removeDraftComment: (executionId, commentId) =>
    set((state) => {
      const list = state.draftComments[executionId] ?? []
      return {
        draftComments: {
          ...state.draftComments,
          [executionId]: list.filter((c) => c.id !== commentId),
        },
      }
    }),
  setActiveCommentBox: (box) => set({ activeCommentBox: box }),
  setActiveStickyBreadcrumb: (breadcrumb) => set({ activeStickyBreadcrumb: breadcrumb }),
  setDiffViewMode: (mode) => set({ diffViewMode: mode }),
  setIsSteeringFocused: (focused) => set({ isSteeringFocused: focused }),
  toggleFileCollapsed: (path) =>
    set((state) => {
      const current = state.collapsedFiles[path] ?? true
      return {
        collapsedFiles: {
          ...state.collapsedFiles,
          [path]: !current,
        },
      }
    }),
}))

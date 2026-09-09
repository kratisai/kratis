import { beforeEach, describe, expect, it } from 'vitest'

import { useDiffReviewStore } from '@/store/diff-review-store'

describe('useDiffReviewStore', () => {
  beforeEach(() => {
    useDiffReviewStore.setState({
      activeCommentBox: null,
      activeStickyBreadcrumb: null,
      collapsedFiles: {},
      draftComments: {},
      isSteeringFocused: false,
    })
  })

  it('adds and removes draft comments per execution', () => {
    const store = useDiffReviewStore.getState()
    store.addDraftComment('exec-1', 'src/Main.java', 42, 'Refactor this logic', 'int x = 1;')

    let state = useDiffReviewStore.getState()
    expect(state.draftComments['exec-1']).toHaveLength(1)
    expect(state.draftComments['exec-1'][0].path).toBe('src/Main.java')
    expect(state.draftComments['exec-1'][0].line).toBe(42)
    expect(state.draftComments['exec-1'][0].comment).toBe('Refactor this logic')
    expect(state.draftComments['exec-1'][0].codeSnippet).toBe('int x = 1;')

    const commentId = state.draftComments['exec-1'][0].id
    store.removeDraftComment('exec-1', commentId)

    state = useDiffReviewStore.getState()
    expect(state.draftComments['exec-1']).toHaveLength(0)
  })

  it('clears draft comments for an execution', () => {
    const store = useDiffReviewStore.getState()
    store.addDraftComment('exec-1', 'a.ts', 1, 'note 1')
    store.addDraftComment('exec-1', 'b.ts', 5, 'note 2')

    expect(useDiffReviewStore.getState().draftComments['exec-1']).toHaveLength(2)

    store.clearDraftComments('exec-1')
    expect(useDiffReviewStore.getState().draftComments['exec-1']).toHaveLength(0)
  })

  it('toggles collapsed files and supports expandAll and collapseAll', () => {
    const store = useDiffReviewStore.getState()
    // Initially collapsed by default, first toggle expands (false)
    store.toggleFileCollapsed('fileA.ts')
    expect(useDiffReviewStore.getState().collapsedFiles['fileA.ts']).toBe(false)

    // Second toggle collapses (true)
    store.toggleFileCollapsed('fileA.ts')
    expect(useDiffReviewStore.getState().collapsedFiles['fileA.ts']).toBe(true)

    store.collapseAll(['fileA.ts', 'fileB.ts'])
    expect(useDiffReviewStore.getState().collapsedFiles['fileA.ts']).toBe(true)
    expect(useDiffReviewStore.getState().collapsedFiles['fileB.ts']).toBe(true)

    store.expandAll(['fileA.ts', 'fileB.ts'])
    expect(useDiffReviewStore.getState().collapsedFiles['fileA.ts']).toBe(false)
    expect(useDiffReviewStore.getState().collapsedFiles['fileB.ts']).toBe(false)
  })

  it('updates active comment box and steering focus state', () => {
    const store = useDiffReviewStore.getState()
    store.setActiveCommentBox({ line: 10, path: 'src/App.tsx' })
    expect(useDiffReviewStore.getState().activeCommentBox).toEqual({
      line: 10,
      path: 'src/App.tsx',
    })

    store.setIsSteeringFocused(true)
    expect(useDiffReviewStore.getState().isSteeringFocused).toBe(true)
  })

  it('updates diffViewMode between unified and split', () => {
    const store = useDiffReviewStore.getState()
    expect(store.diffViewMode).toBe('unified')

    store.setDiffViewMode('split')
    expect(useDiffReviewStore.getState().diffViewMode).toBe('split')

    store.setDiffViewMode('unified')
    expect(useDiffReviewStore.getState().diffViewMode).toBe('unified')
  })

  it('supports multi-line comments with endLine and side', () => {
    const store = useDiffReviewStore.getState()
    store.addDraftComment(
      'exec-1',
      'src/Main.java',
      10,
      'Multi-line note',
      'line1\nline2',
      15,
      'new',
    )

    const comment = useDiffReviewStore.getState().draftComments['exec-1'][0]
    expect(comment.line).toBe(10)
    expect(comment.endLine).toBe(15)
    expect(comment.side).toBe('new')
    expect(comment.comment).toBe('Multi-line note')
  })
})

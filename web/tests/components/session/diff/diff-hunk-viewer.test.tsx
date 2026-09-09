import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { DiffCommentDraft } from '@/types/diff-types'

import { buildCommentExtendData, DiffHunkViewer } from '@/components/session/diff/diff-hunk-viewer'
import * as diffApi from '@/lib/diff-api'
import { useDiffReviewStore } from '@/store/diff-review-store'

const SAMPLE_PATCH = `@@ -10,4 +10,5 @@ function calculateTotal()
 const a = 1;
-const b = 2;
+const b = 3;
+const c = 4;
 return a + b;
`

const MULTI_HUNK_PATCH = `@@ -10,4 +10,4 @@ function calculateTotal()
 const a = 1;
-const b = 2;
+const b = 3;
 const c = 4;
 const d = 5;
@@ -30,6 +30,6 @@ function calculateTotal()
 const x = 1;
-const y = 2;
+const y = 3;
 const z = 3;
 const q = 4;
 const r = 5;
 const s = 6;
`

describe('DiffHunkViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useDiffReviewStore.setState({
      activeCommentBox: null,
      activeStickyBreadcrumb: null,
      diffViewMode: 'unified',
      draftComments: {},
    })
  })

  it('renders hunk scope header and diff content', () => {
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={SAMPLE_PATCH}
        path="src/calc.ts"
      />,
    )

    const container = screen.getByTestId('diff-hunk-viewer')
    expect(container).toBeInTheDocument()
  })

  it('shows expansion buttons and fetches slice on expand', async () => {
    vi.spyOn(diffApi, 'fetchFileSlice').mockResolvedValue({
      lines: ['// expanded context line'],
      path: 'src/calc.ts',
      startLine: 1,
    })

    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={SAMPLE_PATCH}
        path="src/calc.ts"
      />,
    )

    const buttons = screen.getAllByRole('button', { name: /show more/i })
    const topButton = buttons[0]
    expect(topButton).toBeInTheDocument()

    fireEvent.click(topButton)
    expect(diffApi.fetchFileSlice).toHaveBeenCalledWith('chat-1', 'exec-1', 'src/calc.ts', 1, 9)

    await waitFor(() => {
      expect(screen.getByText('// expanded context line')).toBeInTheDocument()
    })
  })

  it('supports toggling split and unified view mode from store', () => {
    const { rerender } = render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={SAMPLE_PATCH}
        path="src/calc.ts"
      />,
    )

    expect(screen.getByTestId('diff-hunk-viewer')).toBeInTheDocument()

    // Switch to split mode
    useDiffReviewStore.getState().setDiffViewMode('split')
    rerender(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={SAMPLE_PATCH}
        path="src/calc.ts"
      />,
    )

    expect(screen.getByTestId('diff-hunk-viewer')).toBeInTheDocument()
  })

  it('renders diff viewer on mobile viewport', () => {
    window.innerWidth = 500
    window.dispatchEvent(new Event('resize'))

    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={SAMPLE_PATCH}
        path="src/calc.ts"
      />,
    )

    expect(screen.getByTestId('diff-hunk-viewer')).toBeInTheDocument()

    // Restore desktop width
    window.innerWidth = 1024
    window.dispatchEvent(new Event('resize'))
  })

  it('scopes horizontal scrolling to each hunk and keeps expand controls outside the scroll area', () => {
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={MULTI_HUNK_PATCH}
        path="src/calc.ts"
      />,
    )

    // One independent horizontal scroll container per hunk: wide diffs scroll l/r
    // inside their hunk instead of stretching the page
    const scrollers = screen.getAllByTestId(/^diff-hunk-scroll-/)
    expect(scrollers).toHaveLength(2)
    for (const scroller of scrollers) {
      expect(scroller).toHaveClass('overflow-x-auto')
    }

    // Expand controls live outside the scroll containers so they never scroll away
    const gapButtons = screen.getAllByTitle('Show more lines above')
    expect(gapButtons.length).toBeGreaterThan(0)
    for (const button of gapButtons) {
      for (const scroller of scrollers) {
        expect(scroller.contains(button)).toBe(false)
      }
    }
  })

  it('renders full git diff payload including git header and hunks', () => {
    const fullGitPatch = `diff --git a/control-plane/src/main/java/com/kratisai/controlplane/service/ChatFluxRegistry.java b/control-plane/src/main/java/com/kratisai/controlplane/service/ChatFluxRegistry.java
index 191f8cb..45e4ba8 100644
--- a/control-plane/src/main/java/com/kratisai/controlplane/service/ChatFluxRegistry.java
+++ b/control-plane/src/main/java/com/kratisai/controlplane/service/ChatFluxRegistry.java
@@ -22,6 +22,10 @@ public class ChatFluxRegistry {
         activeStreams.put(chatId, managedFlux);
     }
 
+    public void remove(UUID chatId) {
+        activeStreams.remove(chatId);
+    }
+
     public Flux<ClientPayload.ChatStreamPayload> get(UUID chatId) {
         return activeStreams.get(chatId);
     }
`
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={fullGitPatch}
        path="control-plane/src/main/java/com/kratisai/controlplane/service/ChatFluxRegistry.java"
      />,
    )

    const container = screen.getByTestId('diff-hunk-viewer')
    expect(container).toHaveTextContent('public class ChatFluxRegistry {')
    expect(container).toHaveTextContent('public void remove(UUID chatId)')
    expect(container).toHaveTextContent('activeStreams.remove(chatId);')
  })

  it('suppresses top Show more button when diff starts at line 1', () => {
    const patchAtLine1 = `@@ -1,4 +1,5 @@\n const a = 1;\n+const b = 2;\n const c = 3;\n const d = 4;\n`
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={patchAtLine1}
        path="src/start.ts"
      />,
    )

    // Should only have 1 Show more button (the bottom one), not 2
    const buttons = screen.getAllByRole('button', { name: /show more/i })
    expect(buttons).toHaveLength(1)
  })

  it('suppresses bottom Show more button when expanding bottom reaches EOF', async () => {
    vi.spyOn(diffApi, 'fetchFileSlice').mockResolvedValue({
      lines: ['// last line of file'],
      path: 'src/calc.ts',
      startLine: 15,
    })

    const patchAtLine1 = `@@ -1,4 +1,5 @@\n const a = 1;\n+const b = 2;\n const c = 3;\n const d = 4;\n`
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={patchAtLine1}
        path="src/calc.ts"
      />,
    )

    const bottomButton = screen.getByRole('button', { name: /show more/i })
    fireEvent.click(bottomButton)

    await waitFor(() => {
      // After fetching only 1 line (< 10), EOF is reached and bottom button is suppressed
      expect(screen.queryByRole('button', { name: /show more/i })).not.toBeInTheDocument()
    })
  })

  it('does not crash in split mode when a comment is attached to an added-only line', () => {
    useDiffReviewStore.setState({ diffViewMode: 'split' })

    const addedOnlyPatch = `@@ -1,1 +1,2 @@\n const a = 1;\n+const b = 2;\n`
    const comment: DiffCommentDraft = {
      comment: 'review note',
      createdAt: 1,
      id: 'c1',
      line: 2,
      path: 'src/add.ts',
      side: 'new',
    }

    expect(() =>
      render(
        <DiffHunkViewer
          chatId="chat-1"
          comments={[comment]}
          executionId="exec-1"
          patch={addedOnlyPatch}
          path="src/add.ts"
        />,
      ),
    ).not.toThrow()
  })

  describe('buildCommentExtendData', () => {
    it('places a new-side comment only in the new file map', () => {
      const comment: DiffCommentDraft = {
        comment: 'note',
        createdAt: 1,
        id: 'c-new',
        line: 32,
        path: 'src/c.ts',
        side: 'new',
      }

      const { newFile, oldFile } = buildCommentExtendData([comment])

      expect(newFile['32'].data.comments).toEqual([comment])
      expect(oldFile['32']).toBeUndefined()
    })

    it('places an old-side comment only in the old file map', () => {
      const comment: DiffCommentDraft = {
        comment: 'note',
        createdAt: 1,
        id: 'c-old',
        line: 32,
        path: 'src/c.ts',
        side: 'old',
      }

      const { newFile, oldFile } = buildCommentExtendData([comment])

      expect(oldFile['32'].data.comments).toEqual([comment])
      expect(newFile['32']).toBeUndefined()
    })

    it('defaults comments without a side to the new side', () => {
      const comment: DiffCommentDraft = {
        comment: 'legacy note',
        createdAt: 1,
        id: 'c-legacy',
        line: 32,
        path: 'src/c.ts',
      }

      const { newFile, oldFile } = buildCommentExtendData([comment])

      expect(newFile['32'].data.comments).toEqual([comment])
      expect(oldFile['32']).toBeUndefined()
    })

    it('keeps old-side and new-side comments on the same line in their own maps', () => {
      const oldComment: DiffCommentDraft = {
        comment: 'old-side note',
        createdAt: 1,
        id: 'c-old',
        line: 32,
        path: 'src/c.ts',
        side: 'old',
      }
      const newComment: DiffCommentDraft = {
        comment: 'new-side note',
        createdAt: 1,
        id: 'c-new',
        line: 32,
        path: 'src/c.ts',
        side: 'new',
      }

      const { newFile, oldFile } = buildCommentExtendData([oldComment, newComment])

      expect(oldFile['32'].data.comments).toEqual([oldComment])
      expect(newFile['32'].data.comments).toEqual([newComment])
    })

    it('groups multiple comments on the same line and side', () => {
      const first: DiffCommentDraft = {
        comment: 'first note',
        createdAt: 1,
        id: 'c1',
        line: 10,
        path: 'src/c.ts',
        side: 'new',
      }
      const second: DiffCommentDraft = {
        comment: 'second note',
        createdAt: 1,
        id: 'c2',
        line: 10,
        path: 'src/c.ts',
        side: 'new',
      }

      const { newFile } = buildCommentExtendData([first, second])

      expect(newFile['10'].data.comments).toEqual([first, second])
    })
  })

  it('immediately suppresses bottom Show more button when totalLines is covered by patch', () => {
    const patchFullFile = `@@ -1,4 +1,5 @@\n const a = 1;\n+const b = 2;\n const c = 3;\n const d = 4;\n`
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={patchFullFile}
        path="src/start.ts"
        totalLines={5}
      />,
    )

    // Diff covers lines 1..5 and totalLines is 5 -> both top and bottom buttons suppressed
    expect(screen.queryByRole('button', { name: /show more/i })).not.toBeInTheDocument()
  })

  it('renders Show and More link buttons between hunks for multi-hunk diffs', () => {
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={MULTI_HUNK_PATCH}
        path="src/multi.ts"
      />,
    )

    // top and bottom Show more buttons
    expect(screen.getAllByRole('button', { name: /^show more$/i })).toHaveLength(2)
    // between-hunks buttons: Show (up) and More (down)
    expect(screen.getByRole('button', { name: /^show$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^more$/i })).toBeInTheDocument()
  })

  it('adds lines to the previous hunk when expanding down between hunks', async () => {
    const sliceSpy = vi.spyOn(diffApi, 'fetchFileSlice')
    sliceSpy.mockResolvedValueOnce({
      lines: Array.from({ length: 10 }, (_, k) => `// gap line ${k + 1}`),
      path: 'src/multi.ts',
      startLine: 14,
    })

    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={MULTI_HUNK_PATCH}
        path="src/multi.ts"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /^more$/i }))
    expect(sliceSpy).toHaveBeenCalledWith('chat-1', 'exec-1', 'src/multi.ts', 14, 23)

    await waitFor(() => {
      expect(screen.getByText('// gap line 1')).toBeInTheDocument()
    })

    // Gap lines 24..29 remain, so both between-hunks buttons stay
    expect(screen.getByRole('button', { name: /^show$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^more$/i })).toBeInTheDocument()
  })

  it('adds lines to the next hunk when expanding up between hunks', async () => {
    const sliceSpy = vi.spyOn(diffApi, 'fetchFileSlice')
    sliceSpy.mockResolvedValueOnce({
      lines: Array.from({ length: 10 }, (_, k) => `// gap line ${k + 1}`),
      path: 'src/multi.ts',
      startLine: 20,
    })

    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={MULTI_HUNK_PATCH}
        path="src/multi.ts"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /^show$/i }))
    expect(sliceSpy).toHaveBeenCalledWith('chat-1', 'exec-1', 'src/multi.ts', 20, 29)

    await waitFor(() => {
      expect(screen.getByText('// gap line 1')).toBeInTheDocument()
    })

    // Gap lines 14..19 remain, so both between-hunks buttons stay
    expect(screen.getByRole('button', { name: /^show$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^more$/i })).toBeInTheDocument()
  })

  it('hides the between-hunks buttons once the gap is fully expanded', async () => {
    const sliceSpy = vi.spyOn(diffApi, 'fetchFileSlice')
    sliceSpy
      .mockResolvedValueOnce({
        lines: Array.from({ length: 10 }, (_, k) => `// gap line ${k + 1}`),
        path: 'src/multi.ts',
        startLine: 14,
      })
      .mockResolvedValueOnce({
        lines: Array.from({ length: 6 }, (_, k) => `// gap line ${k + 11}`),
        path: 'src/multi.ts',
        startLine: 24,
      })

    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={MULTI_HUNK_PATCH}
        path="src/multi.ts"
      />,
    )

    // Expand down first (lines 14..23), then up (lines 24..29)
    fireEvent.click(screen.getByRole('button', { name: /^more$/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^more$/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /^show$/i }))
    expect(sliceSpy).toHaveBeenLastCalledWith('chat-1', 'exec-1', 'src/multi.ts', 24, 29)

    await waitFor(() => {
      expect(screen.getByText('// gap line 16')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^show$/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^more$/i })).not.toBeInTheDocument()
    })

    // top and bottom Show more buttons remain
    expect(screen.getAllByRole('button', { name: /^show more$/i })).toHaveLength(2)
  })

  it('does not render between-hunks buttons when hunks are adjacent', () => {
    const adjacentHunksPatch = `@@ -5,4 +5,4 @@
 const a = 1;
+const b = 2;
-const c = 3;
 const d = 4;
 const e = 5;
@@ -9,4 +9,4 @@
 const f = 6;
+const g = 7;
-const h = 7;
 const i = 8;
 const j = 9;
`
    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={adjacentHunksPatch}
        path="src/adjacent.ts"
      />,
    )

    // Only top and bottom buttons; no gap between hunks
    expect(screen.getAllByRole('button', { name: /^show more$/i })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: /^show$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^more$/i })).not.toBeInTheDocument()
  })

  it('suppresses the between-hunks buttons when the slice fetch fails', async () => {
    vi.spyOn(diffApi, 'fetchFileSlice').mockRejectedValue(new Error('slice fetch failed'))

    render(
      <DiffHunkViewer
        chatId="chat-1"
        comments={[]}
        executionId="exec-1"
        patch={MULTI_HUNK_PATCH}
        path="src/multi.ts"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /^more$/i }))

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /^show$/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^more$/i })).not.toBeInTheDocument()
    })
  })
})

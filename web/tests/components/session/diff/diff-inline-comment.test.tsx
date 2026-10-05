import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DiffInlineComment } from '@/components/session/diff/diff-inline-comment'
import { useDiffReviewStore } from '@/store/diff-review-store'

describe('DiffInlineComment', () => {
  const onCloseDraft = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    useDiffReviewStore.setState({
      activeCommentBox: null,
      draftComments: {},
    })
  })

  it('renders nothing when not drafting and no comments exist', () => {
    const { container } = render(
      <DiffInlineComment
        comments={[]}
        executionId="exec-1"
        isDrafting={false}
        line={15}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders existing comments on the line', () => {
    render(
      <DiffInlineComment
        comments={[
          {
            codeSnippet: 'const total = a + b;',
            comment: 'Consider edge case when negative',
            createdAt: Date.now(),
            id: 'c1',
            line: 15,
            path: 'src/app.ts',
          },
        ]}
        executionId="exec-1"
        isDrafting={false}
        line={15}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
      />,
    )

    expect(screen.getByText('Line 15 Feedback')).toBeInTheDocument()
    expect(screen.getByText('Consider edge case when negative')).toBeInTheDocument()
    expect(screen.getByText('const total = a + b;')).toBeInTheDocument()
  })

  it('saves new draft comment on button click', () => {
    render(
      <DiffInlineComment
        codeSnippet="function test() {}"
        comments={[]}
        executionId="exec-1"
        isDrafting={true}
        line={20}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
        side="new"
      />,
    )

    const textarea = screen.getByLabelText('Write a review comment')
    fireEvent.change(textarea, { target: { value: 'Please add docstring' } })

    const saveButton = screen.getByRole('button', { name: 'Save Comment' })
    fireEvent.click(saveButton)

    const drafts = useDiffReviewStore.getState().draftComments['exec-1']
    expect(drafts).toHaveLength(1)
    expect(drafts[0].comment).toBe('Please add docstring')
    expect(drafts[0].line).toBe(20)
    expect(drafts[0].side).toBe('new')
    expect(onCloseDraft).toHaveBeenCalled()
  })

  it('cancels drafting on cancel button click', () => {
    render(
      <DiffInlineComment
        comments={[]}
        executionId="exec-1"
        isDrafting={true}
        line={20}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
      />,
    )

    const cancelButton = screen.getByRole('button', { name: /cancel/i })
    fireEvent.click(cancelButton)

    expect(onCloseDraft).toHaveBeenCalled()
  })

  it('edits an existing comment in place', () => {
    useDiffReviewStore.setState({
      draftComments: {
        'exec-1': [
          {
            comment: 'Original note',
            createdAt: Date.now(),
            id: 'c1',
            line: 15,
            path: 'src/app.ts',
          },
        ],
      },
    })

    render(
      <DiffInlineComment
        comments={useDiffReviewStore.getState().draftComments['exec-1']}
        executionId="exec-1"
        isDrafting={false}
        line={15}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Edit comment' }))

    const editTextarea = screen.getByLabelText('Edit review comment')
    expect(editTextarea).toHaveValue('Original note')
    fireEvent.change(editTextarea, { target: { value: 'Updated note' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save Comment' }))

    const drafts = useDiffReviewStore.getState().draftComments['exec-1']
    expect(drafts[0].comment).toBe('Updated note')
    expect(screen.queryByLabelText('Edit review comment')).not.toBeInTheDocument()
  })

  it('cancels comment editing and keeps the original text', () => {
    useDiffReviewStore.setState({
      draftComments: {
        'exec-1': [
          {
            comment: 'Original note',
            createdAt: Date.now(),
            id: 'c1',
            line: 15,
            path: 'src/app.ts',
          },
        ],
      },
    })

    render(
      <DiffInlineComment
        comments={useDiffReviewStore.getState().draftComments['exec-1']}
        executionId="exec-1"
        isDrafting={false}
        line={15}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Edit comment' }))
    fireEvent.change(screen.getByLabelText('Edit review comment'), {
      target: { value: 'Changed but discarded' },
    })
    fireEvent.click(screen.getAllByRole('button', { name: /cancel/i })[0])

    expect(useDiffReviewStore.getState().draftComments['exec-1'][0].comment).toBe('Original note')
    expect(screen.getByText('Original note')).toBeInTheDocument()
  })

  it('wraps long code snippets and comments so they never widen the comment box', () => {
    render(
      <DiffInlineComment
        codeSnippet="const total = a + b;"
        comments={[
          {
            codeSnippet: 'const total = a + b;',
            comment: 'Consider edge case when negative',
            createdAt: Date.now(),
            id: 'c1',
            line: 15,
            path: 'src/app.ts',
          },
        ]}
        executionId="exec-1"
        isDrafting={true}
        line={15}
        onCloseDraft={onCloseDraft}
        path="src/app.ts"
      />,
    )

    // Code snippets wrap instead of pushing the box (and the whole page) wider
    for (const snippet of screen.getAllByText('const total = a + b;')) {
      expect(snippet.closest('pre')).toHaveClass('break-words', 'whitespace-pre-wrap')
    }
  })
})

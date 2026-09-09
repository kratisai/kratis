import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { PlanEntriesList, planStats, planSummaryLabel } from '@/components/session/plan-entries'

describe('planStats', () => {
  it('counts completed, pending and picks the first in-progress entry', () => {
    const stats = planStats([
      { content: 'Setup repo', priority: 'high', status: 'in_progress' },
      { content: 'Write tests', priority: 'medium', status: 'pending' },
      { content: 'Run tests', priority: 'low', status: 'completed' },
    ])
    expect(stats.completed).toBe(1)
    expect(stats.pending).toBe(2)
    expect(stats.total).toBe(3)
    expect(stats.inProgress?.content).toBe('Setup repo')
  })

  it('reports all done for a fully completed plan', () => {
    const stats = planStats([{ content: 'A', priority: 'medium', status: 'completed' }])
    expect(stats.completed).toBe(1)
    expect(stats.pending).toBe(0)
    expect(stats.inProgress).toBeNull()
  })

  it('handles an empty plan', () => {
    const stats = planStats([])
    expect(stats).toEqual({ completed: 0, inProgress: null, pending: 0, total: 0 })
  })
})

describe('planSummaryLabel', () => {
  it('shows All Done when every entry is completed', () => {
    expect(planSummaryLabel([{ content: 'A', priority: 'medium', status: 'completed' }])).toBe(
      'All Done',
    )
  })

  it('shows completed/total for a mixed plan', () => {
    expect(
      planSummaryLabel([
        { content: 'A', priority: 'medium', status: 'completed' },
        { content: 'B', priority: 'medium', status: 'pending' },
      ]),
    ).toBe('1/2')
  })

  it('shows N Tasks when nothing is completed yet', () => {
    expect(
      planSummaryLabel([
        { content: 'A', priority: 'medium', status: 'pending' },
        { content: 'B', priority: 'medium', status: 'in_progress' },
      ]),
    ).toBe('2 Tasks')
  })
})

describe('PlanEntriesList', () => {
  it('renders every entry with its content', () => {
    render(
      <PlanEntriesList
        entries={[
          { content: 'Setup repo', priority: 'high', status: 'in_progress' },
          { content: 'Run tests', priority: 'low', status: 'completed' },
        ]}
      />,
    )
    expect(screen.getByText('Setup repo')).toBeInTheDocument()
    expect(screen.getByText('Run tests')).toBeInTheDocument()
  })

  it('renders no entries for an empty plan', () => {
    const { container } = render(<PlanEntriesList entries={[]} />)
    expect(container.querySelector('[data-testid="plan-entries"]')).toBeInTheDocument()
    expect(container.querySelector('[data-testid="plan-entries"]')).toBeEmptyDOMElement()
  })

  it('does not constrain height or add inner scrollbars', () => {
    render(
      <PlanEntriesList
        entries={[
          { content: 'Setup repo', priority: 'high', status: 'in_progress' },
          { content: 'Write tests', priority: 'medium', status: 'pending' },
        ]}
      />,
    )
    const list = screen.getByTestId('plan-entries')
    expect(list.className).not.toMatch(/max-h-/)
    expect(list.className).not.toMatch(/overflow-/)
  })

  it('does not draw separator borders between entries', () => {
    render(
      <PlanEntriesList
        entries={[
          { content: 'Setup repo', priority: 'high', status: 'in_progress' },
          { content: 'Write tests', priority: 'medium', status: 'pending' },
        ]}
      />,
    )
    const first = screen.getByText('Setup repo').closest('div')
    const second = screen.getByText('Write tests').closest('div')
    expect(first?.className).not.toMatch(/border-b/)
    expect(second?.className).not.toMatch(/border-b/)
  })

  it('wraps long plan item text instead of truncating with an ellipsis', () => {
    render(
      <PlanEntriesList
        entries={[
          {
            content: 'A very long task description that needs to wrap instead of ellipse',
            priority: 'high',
            status: 'pending',
          },
        ]}
      />,
    )

    const span = screen.getByText(/A very long task description/)
    expect(span).toHaveClass('break-words', 'min-w-0')
    expect(span).not.toHaveClass('truncate')
  })
})

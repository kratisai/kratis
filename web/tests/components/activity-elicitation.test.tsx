import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ElicitationActivity } from '@/types/execution-activity-types'

import { ActivityElicitation } from '@/components/session/activities/activity-elicitation'
import { useActivityStore } from '@/store/activity-store'

const resolveHitl = vi.fn()

function pendingActivity(overrides: Partial<ElicitationActivity> = {}): ElicitationActivity {
  return {
    collapsed: false,
    executionId: 'exec-1',
    hitlId: 'el-1',
    id: 'act-1',
    message: 'Choose a deployment target',
    startedAt: '2026-01-01T00:00:00Z',
    state: 'active',
    type: 'elicitation',
    ...overrides,
  }
}

describe('ActivityElicitation', () => {
  beforeEach(() => {
    resolveHitl.mockResolvedValue(undefined)
    useActivityStore.setState({ resolveHitl })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders the question message', () => {
    render(<ActivityElicitation activity={pendingActivity()} executionId="exec-1" />)
    expect(screen.getByText('Choose a deployment target')).toBeInTheDocument()
  })

  it('renders a text input for a string schema property', () => {
    render(
      <ActivityElicitation
        activity={pendingActivity({
          form: {
            properties: { name: { title: 'Your name', type: 'string' } },
          },
        })}
        executionId="exec-1"
      />,
    )
    expect(screen.getByText('Your name')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toBeInTheDocument()
  })

  it('always renders Submit, Decline and Cancel actions', () => {
    render(<ActivityElicitation activity={pendingActivity()} executionId="exec-1" />)
    expect(screen.getByRole('button', { name: /submit/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /decline/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument()
  })

  it('submits an answered response with entered values', async () => {
    const user = userEvent.setup()
    render(
      <ActivityElicitation
        activity={pendingActivity({
          form: { properties: { name: { title: 'Name', type: 'string' } } },
        })}
        executionId="exec-1"
      />,
    )

    await user.type(screen.getByRole('textbox'), 'staging')
    await user.click(screen.getByRole('button', { name: /submit/i }))

    expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'el-1', 'answered', undefined, {
      name: 'staging',
    })
  })

  it('declines without content', async () => {
    const user = userEvent.setup()
    render(<ActivityElicitation activity={pendingActivity()} executionId="exec-1" />)

    await user.click(screen.getByRole('button', { name: /decline/i }))

    expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'el-1', 'declined', undefined, undefined)
  })

  it('cancels without content', async () => {
    const user = userEvent.setup()
    render(<ActivityElicitation activity={pendingActivity()} executionId="exec-1" />)

    await user.click(screen.getByRole('button', { name: /cancel/i }))

    expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'el-1', 'cancelled', undefined, undefined)
  })

  it('renders a select for an enum (oneOf) schema property', () => {
    render(
      <ActivityElicitation
        activity={pendingActivity({
          form: {
            properties: {
              target: {
                oneOf: [
                  { const: 'staging', title: 'Staging' },
                  { const: 'prod', title: 'Production' },
                ],
                title: 'Target',
                type: 'string',
              },
            },
          },
        })}
        executionId="exec-1"
      />,
    )
    expect(screen.getByText('Target')).toBeInTheDocument()
    expect(screen.getByText('Select an option')).toBeInTheDocument()
  })

  it('renders the resolved summary for an answered elicitation', () => {
    render(
      <ActivityElicitation
        activity={pendingActivity({ response: 'answered', state: 'completed' })}
        executionId="exec-1"
      />,
    )
    expect(screen.getByText(/Question answered/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /submit/i })).not.toBeInTheDocument()
  })

  it('renders the resolved summary for a declined elicitation', () => {
    render(
      <ActivityElicitation
        activity={pendingActivity({ response: 'declined', state: 'error' })}
        executionId="exec-1"
      />,
    )
    expect(screen.getByText(/Question declined/)).toBeInTheDocument()
  })
})

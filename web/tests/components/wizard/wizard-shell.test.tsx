import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { WizardFooter, WizardShell } from '@/components/wizard/wizard-shell'

function renderShell() {
  return render(
    <WizardShell
      currentStepId="list"
      description="Pick one"
      footer={<WizardFooter onNext={() => undefined} />}
      onOpenChange={() => undefined}
      open
      steps={[
        { id: 'list', label: 'List' },
        { id: 'details', label: 'Details' },
      ]}
      title="Test Wizard"
    >
      <div>step content</div>
    </WizardShell>,
  )
}

describe('WizardShell', () => {
  it('renders title, description, step indicator, content, and footer', () => {
    renderShell()

    expect(screen.getByText('Test Wizard')).toBeInTheDocument()
    expect(screen.getByText('Pick one')).toBeInTheDocument()
    expect(screen.getByText('step content')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument()
    expect(screen.getByText('List')).toBeInTheDocument()
    expect(screen.getByText('Details')).toBeInTheDocument()
  })

  it('stays full-screen on mobile', () => {
    renderShell()

    const content = document.querySelector('[data-slot="dialog-content"]')
    expect(content).not.toBeNull()
    expect(content).toHaveClass('h-screen', 'w-screen', 'max-w-none', 'rounded-none')
  })

  it('uses a definite responsive height on desktop so inner scroll areas do not collapse', () => {
    renderShell()

    // With h-auto, flex-1/overflow-y-auto children with flex-basis 0 collapse to
    // zero height and clip the list content on medium desktop screens.
    const content = document.querySelector('[data-slot="dialog-content"]')
    expect(content).toHaveClass('sm:h-[min(85vh,40rem)]')
    expect(content).not.toHaveClass('sm:h-auto')

    // Centered floating dialog with the expected width steps
    expect(content).toHaveClass('sm:max-w-[720px]', 'md:max-w-[800px]', 'sm:rounded-lg')
  })
})

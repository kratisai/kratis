import { Eye, EyeOff } from 'lucide-react'
import * as React from 'react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

function PasswordInput({
  className,
  ...props
}: React.ComponentProps<'input'>) {
  const [visible, setVisible] = React.useState(false)

  return (
    <div className="relative">
      <Input
        {...props}
        className={cn('pr-9', className)}
        type={visible ? 'text' : 'password'}
      />
      <Button
        aria-label={visible ? 'Hide password' : 'Show password'}
        className="absolute top-1/2 right-1 h-7 w-7 -translate-y-1/2"
        onClick={() => setVisible((prev) => !prev)}
        size="icon-sm"
        type="button"
        variant="ghost"
      >
        {visible ? <EyeOff /> : <Eye />}
      </Button>
    </div>
  )
}

export { PasswordInput }

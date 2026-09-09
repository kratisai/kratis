import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'

export function IngestImmediatelyCheckbox({
  checked,
  compact = false,
  onChange,
  repoCount,
}: {
  checked: boolean
  compact?: boolean
  onChange: (checked: boolean) => void
  repoCount?: number
}) {
  if (compact) {
    return (
      <Label
        className="text-muted-foreground hover:text-foreground flex cursor-pointer items-center gap-2 text-xs font-medium whitespace-nowrap"
        htmlFor="ingest-immediately"
      >
        <Checkbox
          checked={checked}
          id="ingest-immediately"
          onCheckedChange={(next) => onChange(next === true)}
        />
        <span>Start ingesting immediately</span>
      </Label>
    )
  }

  const count = repoCount ?? 1
  const isBatch = count > 1

  return (
    <div className="bg-muted/50 border-border/40 flex items-start gap-2.5 rounded-lg border p-3">
      <Checkbox
        checked={checked}
        id="ingest-immediately"
        onCheckedChange={(next) => onChange(next === true)}
      />
      <Label
        className="grid cursor-pointer gap-0.5 text-sm font-medium"
        htmlFor="ingest-immediately"
      >
        <span>Start ingesting immediately after onboarding</span>
        <span className="text-muted-foreground text-xs leading-relaxed font-normal">
          {isBatch
            ? `Index and generate the wiki for all ${count} selected repositories once they are added.`
            : 'Index and generate the wiki once the repository is added. You can also run it later from the repository page.'}
        </span>
      </Label>
    </div>
  )
}

import { Building2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'

interface EnterpriseAccessBannerProps {
  onRequestAccess: () => void
}

export function EnterpriseAccessBanner({ onRequestAccess }: EnterpriseAccessBannerProps) {
  return (
    <Card
      className="border-border/80 bg-card/60 py-0 shadow-xs"
      data-testid="enterprise-access-banner"
    >
      <CardContent className="flex flex-col gap-4 p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
        <div className="flex items-start gap-3.5 sm:items-center sm:gap-4">
          <div className="border-primary/20 bg-primary/10 text-primary shrink-0 rounded-lg border p-2.5 sm:p-3">
            <Building2 className="h-6 w-6 sm:h-7 sm:w-7" />
          </div>
          <div className="space-y-1">
            <h3 className="text-foreground text-sm font-semibold sm:text-base">
              Kratis Enterprise
            </h3>
            <p className="text-muted-foreground text-xs leading-relaxed sm:text-sm">
              Scale Kratis across your engineering team with dedicated VPC sandboxes, SAML/SSO,
              custom RBAC, and priority SLA.
            </p>
          </div>
        </div>
        <Button className="shrink-0 gap-2 self-start sm:self-center" onClick={onRequestAccess}>
          Request Enterprise Access
        </Button>
      </CardContent>
    </Card>
  )
}

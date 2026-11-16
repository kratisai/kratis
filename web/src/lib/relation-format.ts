import type { RelationNature, RelationSide } from '@/types/auth-types'

export function relationActionLabel(nature: RelationNature, side: RelationSide): string {
  switch (nature) {
    case 'BUILDS_AGAINST':
      switch (side) {
        case 'BIDIRECTIONAL':
          return 'Has a package dependency with'
        case 'DOWNSTREAM':
          return 'Builds against'
        case 'UNKNOWN':
          return 'Has a package relationship with'
        case 'UPSTREAM':
          return 'Provides a package to'
      }
      break
    case 'CALLS':
      switch (side) {
        case 'BIDIRECTIONAL':
          return 'Calls and is called by'
        case 'DOWNSTREAM':
          return 'Calls'
        case 'UNKNOWN':
          return 'Has a call relationship with'
        case 'UPSTREAM':
          return 'Called by'
      }
      break
    case 'HOSTS':
      switch (side) {
        case 'BIDIRECTIONAL':
          return 'Has a hosting relationship with'
        case 'DOWNSTREAM':
          return 'Runs on'
        case 'UNKNOWN':
          return 'Has a hosting relationship with'
        case 'UPSTREAM':
          return 'Hosts'
      }
      break
    case 'MESSAGES':
      return 'Exchanges messages with'
    case 'PEER':
      return 'Shares a domain with'
    case 'SHARES_STATE':
      return 'Shares state with'
  }
  throw new Error(`Unsupported relation nature: ${nature}`)
}

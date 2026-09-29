import { useSyncExternalStore } from 'react'

export function useIsMobile(breakpoint = 768): boolean {
  return useSyncExternalStore(
    subscribeToResize,
    () => window.innerWidth < breakpoint,
    () => false,
  )
}

function subscribeToResize(callback: () => void): () => void {
  window.addEventListener('resize', callback)
  return () => window.removeEventListener('resize', callback)
}

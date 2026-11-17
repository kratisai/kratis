export function buildRepoWikiSplat(repoName: string, pageSlug?: string): string {
  return pageSlug ? `${repoName}/${pageSlug}` : repoName
}

export function parseRepoWikiSplat<T extends { id: string; name: string }>(
  splat: string | undefined,
  repositories: T[] | undefined,
): { pageSlug?: string; repo?: T } {
  if (!splat || !repositories || repositories.length === 0) {
    return {}
  }

  const trimmed = splat.replace(/^\/+|\/+$/g, '')
  if (!trimmed) return {}

  const exactRepo = repositories.find((r) => r.name === trimmed)
  if (exactRepo) {
    return { repo: exactRepo }
  }

  const lastSlash = trimmed.lastIndexOf('/')
  if (lastSlash > 0) {
    const candidateRepoName = trimmed.slice(0, lastSlash)
    const candidateSlug = trimmed.slice(lastSlash + 1)
    const matchedRepo = repositories.find((r) => r.name === candidateRepoName)
    if (matchedRepo) {
      return { pageSlug: candidateSlug, repo: matchedRepo }
    }
  }

  return {}
}

export function isEnvironmentFeaturesEnabled(): boolean {
  return import.meta.env.VITE_ENABLE_ENVIRONMENT_FEATURES === 'true'
}

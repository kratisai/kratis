import js from '@eslint/js'
import tanstackQuery from '@tanstack/eslint-plugin-query'
import globals from 'globals'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import perfectionist from 'eslint-plugin-perfectionist'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'node_modules', 'coverage', 'src/components/ui', 'eslint.config.js']),

  // Base TypeScript config
  {
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        // Enables type-aware rules.
        projectService: true,
      },
    },
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
    ],
    files: ['**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/await-thenable': 'error',
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      '@typescript-eslint/no-redundant-type-constituents': 'error',
      '@typescript-eslint/no-unnecessary-condition': 'error',
      '@typescript-eslint/no-unnecessary-type-assertion': 'error',
      '@typescript-eslint/no-unused-expressions': 'error',
    },
  },

  // React hooks and refresh
  {
    extends: [
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      jsxA11y.flatConfigs.recommended,
    ],
    files: ['**/*.{tsx}'],
  },

  // TanStack Query
  ...tanstackQuery.configs['flat/recommended'],

  // Import and object ordering with perfectionist
  perfectionist.configs['recommended-natural'],
])

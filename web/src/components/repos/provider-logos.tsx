import React from 'react'

interface LogoProps extends React.SVGProps<SVGSVGElement> {
  size?: number
}

export function AzureDevOpsLogo({ className, size = 24, ...props }: LogoProps) {
  return (
    <svg
      className={className}
      fill="none"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      {...props}
    >
      <path d="M1.38 6.42L12.5 1.34v21.32L1.38 17.58V6.42z" fill="#0078D4" />
      <path d="M12.5 1.34L22.62 6.42v11.16L12.5 22.66V1.34z" fill="#5C2D91" />
      <path d="M6.94 12L12.5 9.17v5.66L6.94 12z" fill="#FFFFFF" opacity="0.8" />
    </svg>
  )
}

export function BitbucketLogo({ className, size = 24, ...props }: LogoProps) {
  return (
    <svg
      className={className}
      fill="currentColor"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      {...props}
    >
      <path
        d="M22.38 3a1.37 1.37 0 0 0-.31-.07A1.5 1.5 0 0 0 21.75 3H2.25a1.5 1.5 0 0 0-.31.06 1.41 1.41 0 0 0-.32.07 1.48 1.48 0 0 0-.58.62c-.22.42-.32.93-.32 1.54L2.83 20.3c.05.51.27.93.65 1.25s.87.45 1.46.45h14.12a1.5 1.5 0 0 0 1.46-.45c.38-.32.6-.74.65-1.25l2.11-15.01c0-.61-.1-1.12-.32-1.54a1.48 1.48 0 0 0-.58-.62zM14.7 15.65H9.3l-1.12-5.3h7.64l-1.12 5.3z"
        fill="#0052CC"
      />
    </svg>
  )
}

export function CustomGitLogo({ className, size = 24, ...props }: LogoProps) {
  return (
    <svg
      className={className}
      fill="currentColor"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      {...props}
    >
      <path
        d="M23.27 11.58L12.42.73a1.57 1.57 0 0 0-2.22 0l-2.02 2.02 2.87 2.87a2.53 2.53 0 0 1 3.5 0 2.53 2.53 0 0 1 0 3.5l-2.88 2.88a2.53 2.53 0 0 1-3.23.23L5.3 9.09a2.53 2.53 0 0 1-.22-3.23l2.06-2.06L4.72 1.38.73 5.37a1.57 1.57 0 0 0 0 2.22l10.85 10.85a1.57 1.57 0 0 0 2.22 0l9.47-9.47a1.57 1.57 0 0 0 0-2.22zM8.33 13.91a2.53 2.53 0 1 1-3.57-3.57 2.53 2.53 0 0 1 3.57 3.57z"
        fill="#F05032"
      />
    </svg>
  )
}

export function GitHubLogo({ className, size = 24, ...props }: LogoProps) {
  return (
    <svg
      className={className}
      fill="currentColor"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      {...props}
    >
      <path
        clipRule="evenodd"
        d="M12 2C6.477 2 2 6.477 2 12c0 4.42 2.87 8.17 6.84 9.5.5.08.66-.23.66-.5v-1.69c-2.77.6-3.36-1.34-3.36-1.34-.46-1.16-1.11-1.47-1.11-1.47-.9-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.9 1.52 2.34 1.07 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.92 0-1.11.38-2 1.03-2.71-.1-.25-.45-1.29.1-2.64 0 0 .84-.27 2.75 1.02.79-.22 1.65-.33 2.5-.33.85 0 1.71.11 2.5.33 1.91-1.29 2.75-1.02 2.75-1.02.55 1.35.2 2.39.1 2.64.65.71 1.03 1.6 1.03 2.71 0 3.82-2.34 4.66-4.57 4.91.36.31.69.92.69 1.85V21c0 .27.16.59.67.5C19.14 20.16 22 16.42 22 12A10 10 0 0 0 12 2z"
        fillRule="evenodd"
      />
    </svg>
  )
}

export function GitLabLogo({ className, size = 24, ...props }: LogoProps) {
  return (
    <svg
      className={className}
      fill="none"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      {...props}
    >
      <path
        d="M22.65 14.39L12 3.74L1.35 14.39a.82.82 0 0 0-.07 1.05l7.79 9.68a.81.81 0 0 0 .63.31h4.6a.81.81 0 0 0 .63-.31l7.79-9.68a.82.82 0 0 0-.07-1.05z"
        fill="#FC6D26"
      />
      <path d="M12 3.74L1.35 14.39a.82.82 0 0 0-.07 1.05l7.79 9.68L12 3.74z" fill="#E24329" />
      <path d="M12 3.74L22.65 14.39a.82.82 0 0 0 .07 1.05l-7.79 9.68L12 3.74z" fill="#FCA326" />
    </svg>
  )
}

export function PublicRepoLogo({ className, size = 24, ...props }: LogoProps) {
  return (
    <svg
      className={className}
      fill="none"
      height={size}
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={2.5}
      viewBox="0 0 24 24"
      width={size}
      {...props}
    >
      <circle cx="12" cy="12" r="10" />
      <path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20" />
      <path d="M2 12h20" />
    </svg>
  )
}

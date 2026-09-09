import { configure as configureRtl } from '@testing-library/react';
import '@testing-library/jest-dom'
import { vi } from 'vitest';

declare const process: { env: { TEST_MODE?: string } };
const testMode = process.env.TEST_MODE;
if (testMode !== 'full') {
  configureRtl({
    // Hide RTL's HTML dumps
    getElementError: function customGetElementError(message: null | string | undefined) {
      const error = new Error(message ?? 'TestingLibraryElementError');
      error.name = 'TestingLibraryElementError';
      const V8Error = Error as typeof Error & { captureStackTrace?: (error: Error, constructorOpt?: (...args: unknown[]) => unknown) => void };
      if (typeof V8Error.captureStackTrace === 'function') {
        // Remove this setup method from the error stack trace
        V8Error.captureStackTrace(error, customGetElementError as (...args: unknown[]) => unknown);
      }
      return error;
    },
  });
}

Object.defineProperty(window, 'scrollTo', { value: vi.fn(), writable: true });

const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    clear: () => {
      store = {}
    },
    getItem: (key: string) => store[key] || null,
    removeItem: (key: string) => {
      delete store[key]
    },
    setItem: (key: string, value: string) => {
      store[key] = String(value)
    },
  }
})()
Object.defineProperty(window, 'localStorage', { value: localStorageMock })

// Polyfills for jsdom - Radix UI, @git-diff-view and other libs use methods that jsdom doesn't implement
if (typeof Element.prototype.scrollIntoView !== 'function') {
  Element.prototype.scrollIntoView = () => {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserver {
    disconnect() {}
    observe() {}
    unobserve() {}
  }
}

// Polyfill HTMLCanvasElement.prototype.getContext for libraries measuring text (e.g. @git-diff-view/react)
const originalGetContext = HTMLCanvasElement.prototype.getContext
HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, contextId: string, options?: unknown) {
  const ctx = originalGetContext.call(this, contextId as '2d', options)
  if (!ctx && contextId === '2d') {
    return {
      font: '',
      measureText: (text: string) => ({ width: (text || '').length * 8 }),
    } as unknown as CanvasRenderingContext2D
  }
  return ctx
} as unknown as typeof HTMLCanvasElement.prototype.getContext

// Mock window.matchMedia for next-themes and other libraries
Object.defineProperty(window, 'matchMedia', {
  value: vi.fn().mockImplementation((query) => ({
    addEventListener: vi.fn(),
    addListener: vi.fn(), // deprecated
    dispatchEvent: vi.fn(),
    matches: false,
    media: query,
    onchange: null,
    removeEventListener: vi.fn(),
    removeListener: vi.fn(), // deprecated
  })),
  writable: true,
});

// Suppress React 19 act() warnings - these are false positives in React 19
// because React 19 automatically batches state updates. The warnings appear
// when state updates happen asynchronously (e.g., from mutations) but are
// handled correctly by React 19's automatic batching.
const originalError = console.error
console.error = (...args: unknown[]) => {
  const message = args[0]
  if (
    typeof message === 'string' &&
    message.includes('was not wrapped in act(...)')
  ) {
    return
  }
  originalError.apply(console, args)
}

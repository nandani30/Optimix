import { useState, useEffect } from 'react'

/**
 * A useState hook that persists to localStorage.
 * Handles JSON serialization/deserialization automatically.
 *
 * Usage:
 *   const [theme, setTheme] = useLocalStorage('theme', 'dark')
 */
export function useLocalStorage<T>(key: string, defaultValue: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = localStorage.getItem(key)
      return stored ? (JSON.parse(stored) as T) : defaultValue
    } catch {
      return defaultValue
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(value))
    } catch {
      // localStorage may be unavailable in some contexts
    }
  }, [key, value])

  return [value, setValue]
}

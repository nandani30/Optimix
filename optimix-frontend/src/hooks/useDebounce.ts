import { useState, useEffect } from 'react'

/**
 * Returns a debounced version of a value.
 * Only updates after the specified delay with no further changes.
 *
 * Usage:
 *   const debouncedSearch = useDebounce(searchTerm, 300)
 */
export function useDebounce<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState<T>(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}

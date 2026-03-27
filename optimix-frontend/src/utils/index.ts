// ── Formatting ────────────────────────────────────────────────────────────

/** Format a number as a cost with unit, e.g. 12345 → "12,345 units" */
export function formatCost(cost: number): string {
  if (cost >= 1_000_000) return `${(cost / 1_000_000).toFixed(1)}M units`
  if (cost >= 1_000)     return `${(cost / 1_000).toFixed(1)}K units`
  return `${cost.toLocaleString(undefined, { maximumFractionDigits: 1 })} units`
}

/** Format a speedup factor, e.g. 12.5 → "12.5×" */
export function formatSpeedup(speedup: number): string {
  return `${speedup.toFixed(1)}×`
}

/** Format an ISO date string to a relative or absolute label */
export function formatDate(iso: string): string {
  const d    = new Date(iso)
  const now  = new Date()
  const diff = now.getTime() - d.getTime()
  const mins  = Math.floor(diff / 60_000)
  const hours = Math.floor(diff / 3_600_000)
  const days  = Math.floor(diff / 86_400_000)

  if (mins  <  1) return 'just now'
  if (mins  < 60) return `${mins}m ago`
  if (hours < 24) return `${hours}h ago`
  if (days  <  7) return `${days}d ago`
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

/** Truncate a SQL query for display, collapsing whitespace */
export function truncateSql(sql: string, maxLen = 120): string {
  const normalized = sql.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLen) return normalized
  return normalized.slice(0, maxLen) + '…'
}

/** Parse a JSON string safely, returning a default on failure */
export function safeJsonParse<T>(raw: string, fallback: T): T {
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

// ── Validation ────────────────────────────────────────────────────────────

/** Validate an email address format */
export function isValidEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

/** Validate password meets requirements */
export function validatePassword(password: string): string | null {
  if (password.length < 8)          return 'Password must be at least 8 characters'
  if (!/[A-Z]/.test(password))      return 'Password must contain at least one uppercase letter'
  if (!/[a-z]/.test(password))      return 'Password must contain at least one lowercase letter'
  if (!/[0-9]/.test(password))      return 'Password must contain at least one number'
  return null
}

// ── SQL helpers ───────────────────────────────────────────────────────────

/** Detect if a string looks like a SQL query */
export function looksLikeSql(text: string): boolean {
  const upper = text.trim().toUpperCase()
  return (
    upper.startsWith('SELECT') ||
    upper.startsWith('WITH') ||
    upper.startsWith('EXPLAIN')
  )
}

/** Count approximate number of tables in a SQL string */
export function estimateTableCount(sql: string): number {
  const matches = sql.match(/\bFROM\b|\bJOIN\b/gi)
  return matches ? matches.length : 0
}

// ── Clipboard ─────────────────────────────────────────────────────────────

/** Copy text to clipboard, returns true on success */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

// ── Color helpers (for speedup badges) ───────────────────────────────────

/** Return a Tailwind text color class based on speedup factor */
export function speedupColor(speedup: number): string {
  if (speedup >= 20) return 'text-accent'
  if (speedup >= 5)  return 'text-yellow'
  if (speedup >= 2)  return 'text-blue'
  return 'text-text-muted'
}

export function speedupBgColor(speedup: number): string {
  if (speedup >= 20) return 'bg-accent/15 border-accent/30'
  if (speedup >= 5)  return 'bg-yellow/15 border-yellow/30'
  if (speedup >= 2)  return 'bg-blue/15 border-blue/30'
  return 'bg-bg-overlay border-border'
}

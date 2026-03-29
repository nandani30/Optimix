import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { clsx } from 'clsx'
import { useHistoryStore, useOptimizerStore } from '../store'
import { historyApi } from '../api'
import type { HistoryEntry } from '../types'

export default function HistoryPage() {
  const { entries, setEntries, removeEntry } = useHistoryStore()
  const { setInputQuery, reset }             = useOptimizerStore()
  const navigate                             = useNavigate()

  const [loading, setLoading]     = useState(false)
  const [search, setSearch]       = useState('')
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  useEffect(() => {
    setLoading(true)
    historyApi.list()
      .then(setEntries)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [setEntries])

  const handleReRun = (entry: HistoryEntry) => {
    setInputQuery(entry.originalQuery)
    reset()
    navigate('/optimizer')
  }

  const handleDelete = async (id: number) => {
    setDeletingId(id)
    try {
      await historyApi.delete(id)
      removeEntry(id)
    } catch { /* ignore */ }
    finally { setDeletingId(null) }
  }

  const parsePatterns = (raw: string | null): string[] => {
    if (!raw) return []
    try {
      return JSON.parse(raw) as string[]
    } catch {
      return []
    }
  }

  // CRITICAL FIX: Safe null-checking to prevent React crashes
  const filtered = entries.filter((e) => {
    if (!search.trim()) return true
    const q = search.toLowerCase()
    return (
      (e.originalQuery || '').toLowerCase().includes(q) ||
      (e.patternsApplied || '').toLowerCase().includes(q)
    )
  })

  const formatDate = (iso: string) => {
    // Timezone Fix: Force browser to parse backend time as UTC so it converts to IST correctly
    const dateStr = iso.endsWith('Z') ? iso : `${iso}Z`
    const d = new Date(dateStr)
    const now = new Date()
    const diffMs = now.getTime() - d.getTime()
    
    // Prevent negative times if clock sync is slightly off
    if (diffMs < 0) return 'just now'
    
    const diffMins  = Math.floor(diffMs / 60_000)
    const diffHours = Math.floor(diffMs / 3_600_000)
    const diffDays  = Math.floor(diffMs / 86_400_000)
    if (diffMins < 1)   return 'just now'
    if (diffMins < 60)  return `${diffMins}m ago`
    if (diffHours < 24) return `${diffHours}h ago`
    if (diffDays < 7)   return `${diffDays}d ago`
    return d.toLocaleDateString()
  }

  const SpeedupBadge = ({ v }: { v: number }) => (
    <span className={clsx(
      'inline-flex items-center px-2 py-0.5 rounded text-xs font-mono font-bold',
      v >= 10 ? 'bg-accent/20 text-accent border border-accent/30' :
      v >= 3  ? 'bg-yellow/20 text-yellow border border-yellow/30' :
                'bg-bg-overlay text-text-muted border border-border'
    )}>
      {v.toFixed(1)}×
    </span>
  )

  return (
    <div className="h-full flex flex-col bg-bg-base">
      <div className="px-6 py-4 border-b border-border bg-bg-surface flex-shrink-0">
        <div className="flex items-center justify-between mb-3">
          <div>
            <h1 className="text-lg font-semibold text-text-primary">Optimization History</h1>
            <p className="text-xs text-text-muted mt-0.5">
              {entries.length} quer{entries.length === 1 ? 'y' : 'ies'} optimized
            </p>
          </div>
        </div>
        <input
          type="text"
          placeholder="Search queries or patterns…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full bg-bg-base border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent/50 transition-colors placeholder:text-text-disabled"
        />
      </div>

      <div className="flex-1 overflow-y-auto p-6">
        {loading ? (
          <div className="flex justify-center py-10"><div className="w-6 h-6 border-2 border-accent border-t-transparent rounded-full animate-spin"/></div>
        ) : filtered.length === 0 ? (
          <div className="text-center py-20 text-text-muted text-sm">No history found.</div>
        ) : (
          <div className="space-y-4 max-w-4xl mx-auto">
            {filtered.map((entry) => {
              const patterns = parsePatterns(entry.patternsApplied)
              const isExpanded = expandedId === entry.historyId
              return (
                <div key={entry.historyId} className="bg-bg-surface border border-border rounded-lg overflow-hidden flex flex-col transition-colors hover:border-border-hover">
                  <div 
                    className="p-4 cursor-pointer flex gap-4"
                    onClick={() => setExpandedId(isExpanded ? null : entry.historyId)}
                  >
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-3">
                          <span className="text-xs font-medium text-text-muted">{formatDate(entry.createdAt)}</span>
                          {entry.speedupFactor != null && <SpeedupBadge v={entry.speedupFactor} />}
                        </div>
                        <div className="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                          <button onClick={(e) => { e.stopPropagation(); handleReRun(entry) }} className="text-xs text-accent hover:text-accent-hover px-2 py-1">Re-run</button>
                          <button onClick={(e) => { e.stopPropagation(); handleDelete(entry.historyId) }} className="text-xs text-red hover:text-red/80 px-2 py-1">
                            {deletingId === entry.historyId ? '…' : 'Delete'}
                          </button>
                        </div>
                      </div>
                      <div className="font-mono text-xs text-text-secondary truncate bg-bg-base p-2 rounded border border-border/50">
                        {entry.originalQuery}
                      </div>
                      {patterns.length > 0 && (
                        <div className="mt-3 flex flex-wrap gap-1.5">
                          {patterns.map((p, i) => (
                            <span key={i} className="px-1.5 py-0.5 rounded bg-bg-overlay border border-border text-[10px] text-text-muted font-mono">
                              {p}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                  {isExpanded && (
                    <div className="px-4 pb-4 pt-2 border-t border-border/50 bg-bg-base/50">
                      <p className="text-xs font-semibold text-text-primary mb-1.5">Optimized Query</p>
                      <div className="font-mono text-xs text-accent whitespace-pre-wrap break-all bg-bg-surface p-3 rounded border border-border/50">
                        {entry.optimizedQuery}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
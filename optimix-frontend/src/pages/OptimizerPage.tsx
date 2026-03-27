import { useState, useRef, useEffect } from 'react'
import { clsx } from 'clsx'
import { useNavigate } from 'react-router-dom'
import { useOptimizerStore, useConnectionStore } from '../store'
import { optimizerApi } from '../api'
import SqlEditor from '../components/editor/SqlEditor'
import ResultsPanel from '../components/results/ResultsPanel'

type PanelView = 'split' | 'editor' | 'results'

// Examples shown as plain text — educational, not loaded into editor
const EXAMPLES = [
  {
    label: 'Slow: Correlated Subquery',
    problem: 'This runs a separate query for every single row in orders — if you have 10,000 orders, it runs 10,000 extra queries.',
    before: `SELECT o.id,
  (SELECT COUNT(*) FROM order_items
   WHERE order_id = o.id) AS items
FROM orders o
WHERE o.status = 'pending'`,
    after: `SELECT o.id,
  COALESCE(agg.items, 0) AS items
FROM orders o
LEFT JOIN (
  SELECT order_id, COUNT(*) AS items
  FROM order_items
  GROUP BY order_id
) agg ON agg.order_id = o.id
WHERE o.status = 'pending'`,
    result: '~30× faster — one join instead of N queries',
  },
  {
    label: 'Slow: Function on Indexed Column',
    problem: "Wrapping a column in YEAR() stops MySQL from using the index — it has to scan every row.",
    before: `SELECT id, total, status
FROM orders
WHERE YEAR(created_at) = 2024
  AND MONTH(created_at) = 3`,
    after: `SELECT id, total, status
FROM orders
WHERE created_at >= '2024-03-01'
  AND created_at < '2024-04-01'`,
    result: '~60× faster — index can now be used',
  },
  {
    label: 'Slow: NOT IN Subquery',
    problem: 'NOT IN runs a nested loop and breaks completely if there are any NULLs in the subquery.',
    before: `SELECT id, name FROM customers
WHERE id NOT IN (
  SELECT customer_id FROM orders
  WHERE status = 'delivered'
)`,
    after: `SELECT c.id, c.name
FROM customers c
LEFT JOIN orders o
  ON o.customer_id = c.id
  AND o.status = 'delivered'
WHERE o.customer_id IS NULL`,
    result: '~7× faster — anti-join is much more efficient',
  },
  {
    label: 'Slow: COUNT(*) > 0',
    problem: 'COUNT(*) scans and counts all matching rows before checking if there are any. EXISTS stops at the first match.',
    before: `SELECT id FROM orders o
WHERE (
  SELECT COUNT(*) FROM order_items
  WHERE order_id = o.id
) > 0`,
    after: `SELECT id FROM orders o
WHERE EXISTS (
  SELECT 1 FROM order_items
  WHERE order_id = o.id
)`,
    result: '~50× faster — stops at first match',
  },
]

export default function OptimizerPage() {
  const { inputQuery, setInputQuery, result, isLoading, error, setResult, setLoading, setError, reset } = useOptimizerStore()
  const { connections, activeConnectionId, setActiveConnectionId } = useConnectionStore()
  const [view, setView]         = useState<PanelView>('split')
  const [splitPct, setSplitPct] = useState(52)
  const [expandedEx, setExpandedEx] = useState<number | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const isDragging   = useRef(false)
  const navigate     = useNavigate()

  const hasConnection = activeConnectionId !== null
  const activeConn    = connections.find(c => c.connectionId === activeConnectionId)

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!isDragging.current || !containerRef.current) return
      const rect = containerRef.current.getBoundingClientRect()
      setSplitPct(Math.min(74, Math.max(28, ((e.clientX - rect.left) / rect.width) * 100)))
    }
    const onUp = () => { isDragging.current = false }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp) }
  }, [])

  useEffect(() => {
    if (!hasConnection) return
    const h = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); handleOptimize() }
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [inputQuery, activeConnectionId, hasConnection])

  const handleOptimize = async () => {
    if (!inputQuery.trim() || isLoading || !hasConnection) return
    reset(); setLoading(true)
    try {
      const data = await optimizerApi.optimize({ query: inputQuery.trim(), connectionId: activeConnectionId! })
      setResult(data)
      if (view === 'editor') setView('split')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Optimization failed')
    }
  }

  const canOptimize = inputQuery.trim().length > 0 && !isLoading && hasConnection

  // ── No DB connection gate ────────────────────────────────────────────────
  if (!hasConnection) {
    return (
      <div className="h-full flex flex-col bg-bg-base">
        {/* Toolbar */}
        <div className="flex items-center gap-2 px-4 py-2 bg-bg-surface border-b border-border flex-shrink-0">
          <span className="text-sm font-bold text-text-primary">Optimizer</span>
          <div className="flex-1"/>
          {connections.length > 0 && (
            <select value="" onChange={e => setActiveConnectionId(Number(e.target.value))}
              className="bg-bg-raised border border-border text-text-primary text-xs rounded-lg px-3 py-1.5 focus:outline-none focus:border-accent">
              <option value="">Select a database connection</option>
              {connections.map(c => <option key={c.connectionId} value={c.connectionId}>{c.profileName} — {c.databaseName}</option>)}
            </select>
          )}
          <button disabled className="flex items-center gap-2 px-4 py-1.5 rounded-lg text-sm font-bold bg-bg-raised text-text-disabled border border-border cursor-not-allowed">
            ⚡ Optimize
          </button>
        </div>

        {/* Two column layout: gate + examples */}
        <div className="flex-1 flex overflow-hidden">

          {/* Left: Connection required gate */}
          <div className="w-1/2 flex flex-col items-center justify-center p-8 border-r border-border">
            <div className="max-w-xs text-center">
              <div className="w-14 h-14 rounded-2xl bg-accent/10 border border-accent/20 flex items-center justify-center text-2xl mx-auto mb-4">🔌</div>
              <h2 className="text-base font-bold text-text-primary mb-2">Connect your MySQL database</h2>
              <p className="text-sm text-text-muted leading-relaxed mb-6">
                Optimization uses real table statistics from your database — row counts, indexes, and data distribution — to produce accurate results.
              </p>
              <button onClick={() => navigate('/connections')}
                className="px-6 py-2.5 bg-accent hover:bg-accent/90 text-black text-sm font-bold rounded-xl transition-colors">
                + Add Connection
              </button>
              {connections.length > 0 && (
                <p className="text-xs text-text-muted mt-4">
                  You have {connections.length} saved connection{connections.length > 1 ? 's' : ''} — select one above
                </p>
              )}
            </div>
          </div>

          {/* Right: Examples as plain text */}
          <div className="w-1/2 overflow-y-auto">
            <div className="px-5 py-4 border-b border-border">
              <p className="text-xs font-semibold text-text-muted uppercase tracking-widest">What Optimix fixes — examples</p>
            </div>
            <div className="divide-y divide-border">
              {EXAMPLES.map((ex, i) => (
                <div key={i} className="p-4">
                  <button onClick={() => setExpandedEx(expandedEx === i ? null : i)}
                    className="w-full text-left flex items-center justify-between gap-3">
                    <p className="text-sm font-semibold text-text-primary">{ex.label}</p>
                    <span className="text-text-disabled text-xs flex-shrink-0">{expandedEx === i ? '▲' : '▼'}</span>
                  </button>
                  <p className="text-xs text-text-muted mt-1.5 leading-relaxed">{ex.problem}</p>

                  {expandedEx === i && (
                    <div className="mt-3 space-y-2 animate-fade-in">
                      <div className="grid grid-cols-2 gap-2">
                        <div>
                          <p className="text-2xs font-semibold text-red uppercase tracking-wider mb-1.5">Before</p>
                          <pre className="text-2xs font-mono bg-bg-raised border border-red/20 rounded-lg p-3 text-text-secondary overflow-x-auto leading-relaxed whitespace-pre-wrap">{ex.before}</pre>
                        </div>
                        <div>
                          <p className="text-2xs font-semibold text-accent uppercase tracking-wider mb-1.5">After</p>
                          <pre className="text-2xs font-mono bg-bg-raised border border-accent/20 rounded-lg p-3 text-accent overflow-x-auto leading-relaxed whitespace-pre-wrap">{ex.after}</pre>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 bg-accent/5 border border-accent/20 rounded-lg px-3 py-2">
                        <span className="text-accent text-sm">⚡</span>
                        <p className="text-xs font-medium text-accent">{ex.result}</p>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    )
  }

  // ── Connected: full optimizer ────────────────────────────────────────────
  return (
    <div className="h-full flex flex-col bg-bg-base">
      {/* Toolbar */}
      <div className="flex items-center gap-2 px-4 py-2 bg-bg-surface border-b border-border flex-shrink-0">
        <span className="text-sm font-bold text-text-primary">Optimizer</span>

        {/* Active connection */}
        <div className="flex items-center gap-1.5 px-2.5 py-1 bg-accent/10 border border-accent/20 rounded-lg">
          <div className="w-1.5 h-1.5 rounded-full bg-accent"/>
          <span className="text-xs text-accent font-medium">{activeConn?.databaseName ?? activeConn?.profileName}</span>
          <button onClick={() => setActiveConnectionId(null)} className="text-accent/50 hover:text-accent text-xs ml-0.5 leading-none">✕</button>
        </div>

        {/* Switch connection */}
        {connections.length > 1 && (
          <select value={activeConnectionId ?? ''} onChange={e => setActiveConnectionId(Number(e.target.value))}
            className="bg-bg-raised border border-border text-text-muted text-xs rounded-lg px-2 py-1 focus:outline-none focus:border-accent max-w-32">
            {connections.map(c => <option key={c.connectionId} value={c.connectionId}>{c.profileName}</option>)}
          </select>
        )}

        <div className="flex-1"/>

        {/* View toggle */}
        <div className="flex bg-bg-raised border border-border rounded-lg p-0.5 gap-0.5">
          {[['editor','✎'], ['split','⊟'], ['results','◧']].map(([v, icon]) => (
            <button key={v} onClick={() => setView(v as PanelView)}
              className={clsx('px-2.5 py-1 rounded-md text-xs transition-colors',
                view === v ? 'bg-accent text-black font-bold' : 'text-text-muted hover:text-text-primary')}>
              {icon}
            </button>
          ))}
        </div>

        <button onClick={handleOptimize} disabled={!canOptimize}
          className={clsx('flex items-center gap-2 px-4 py-1.5 rounded-lg text-sm font-bold transition-all',
            canOptimize ? 'bg-accent hover:bg-accent/90 text-black shadow-glow-sm' : 'bg-bg-raised text-text-disabled border border-border cursor-not-allowed')}>
          {isLoading ? <><span className="animate-spin-slow inline-block">⟳</span> Optimizing…</> : <>⚡ Optimize <kbd className="text-2xs opacity-50 font-mono ml-1">⌘↵</kbd></>}
        </button>
      </div>

      {/* Panels */}
      <div className="flex-1 flex overflow-hidden" ref={containerRef}>

        {/* Left: Editor */}
        {(view === 'split' || view === 'editor') && (
          <div className="flex flex-col border-r border-border overflow-hidden"
            style={{ width: view === 'split' ? `${splitPct}%` : '100%' }}>
            <div className="flex items-center justify-between px-4 py-1.5 bg-bg-surface border-b border-border flex-shrink-0">
              <span className="text-2xs text-text-muted font-mono uppercase tracking-widest">SQL Input</span>
              {inputQuery.trim() && (
                <button onClick={() => { setInputQuery(''); reset() }} className="text-2xs text-text-disabled hover:text-red transition-colors">Clear</button>
              )}
            </div>
            <div className="flex-1 overflow-hidden min-h-0">
              <SqlEditor value={inputQuery} onChange={setInputQuery} aria-label="SQL input"/>
            </div>
          </div>
        )}

        {view === 'split' && <div className="resize-handle" onMouseDown={() => { isDragging.current = true }}/>}

        {/* Right: Results */}
        {(view === 'split' || view === 'results') && (
          <div className="flex flex-col overflow-hidden" style={{ width: view === 'split' ? `${100 - splitPct}%` : '100%' }}>
            <div className="flex items-center px-4 py-1.5 bg-bg-surface border-b border-border flex-shrink-0">
              <span className="text-2xs text-text-muted font-mono uppercase tracking-widest">Results</span>
              {result && (
                <div className="ml-auto flex items-center gap-3">
                  <span className="text-xs font-mono font-bold text-accent">{result.speedupFactor != null
  ? `${result.speedupFactor.toFixed(1)}× faster`
  : '—'}</span>
                </div>
              )}
            </div>
            <div className="flex-1 overflow-hidden">
              <ResultsPanel result={result} isLoading={isLoading} error={error}/>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

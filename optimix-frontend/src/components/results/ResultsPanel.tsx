import { useState, useCallback } from 'react'
import { clsx } from 'clsx'
import type { OptimizationResult, PatternApplication, ResultTab } from '../../types'
import SqlEditor from '../editor/SqlEditor'

interface Props {
  result: OptimizationResult | null
  isLoading: boolean
  error: string | null
}

export default function ResultsPanel({ result, isLoading, error }: Props) {
  const [tab, setTab] = useState<ResultTab>('query')
  const [copied, setCopied] = useState(false)

  const copy = useCallback(() => {
    if (!result) return
    navigator.clipboard.writeText(result.optimizedQuery)
    setCopied(true); setTimeout(() => setCopied(false), 2000)
  }, [result])

  if (isLoading) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-4 text-center px-8">
        <div className="relative">
          <div className="w-12 h-12 rounded-full border-2 border-accent/20 border-t-accent animate-spin"/>
          <span className="absolute inset-0 flex items-center justify-center text-lg">⚡</span>
        </div>
        <div>
          <p className="text-sm font-semibold text-text-primary">Analyzing your query…</p>
          <p className="text-xs text-text-muted mt-1">This usually takes under a second</p>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-3 px-8 text-center">
        <div className="w-10 h-10 rounded-full bg-red/10 border border-red/30 flex items-center justify-center text-lg">⚠️</div>
        <div>
          <p className="text-sm font-semibold text-red">Could not optimize</p>
          <p className="text-xs text-text-muted mt-1 max-w-xs">{error}</p>
        </div>
      </div>
    )
  }

  if (!result) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-3 px-8 text-center">
        <div className="text-5xl opacity-10 select-none">⚡</div>
        <p className="text-sm text-text-muted">Paste a MySQL query and click Optimize</p>
        <p className="text-xs text-text-disabled mt-0.5">Or click one of the examples on the left</p>
      </div>
    )
  }

  // Only show tabs with actual content
  const hasIndexes = result.indexRecommendations?.length > 0
  const hasPatterns = result.patternsApplied?.length > 0
  const hasPlan = result.optimizedPlan?.root != null

  const tabs: { id: ResultTab; label: string; badge?: number }[] = [
    { id: 'query',    label: 'Optimized SQL' },
    { id: 'explain',  label: 'Explanation' },
    { id: 'cost',     label: 'Cost' },
    ...(hasIndexes ? [{ id: 'indexes' as ResultTab, label: 'Indexes', badge: result.indexRecommendations.length }] : []),
    ...(hasPlan ? [{ id: 'plan' as ResultTab, label: 'Plan' }] : []),
  ]

  return (
    <div className="h-full flex flex-col">

      {/* Speedup banner */}
      <div className="flex items-center gap-3 px-4 py-2.5 bg-accent/5 border-b border-accent/20 flex-shrink-0">
        <span className="text-2xl font-bold text-accent leading-none">{result.speedupFactor != null
  ? result.speedupFactor.toFixed(1)
  : '—'}×</span>
        <span className="text-xs text-text-muted">faster</span>
        <div className="w-px h-4 bg-border"/>
        <p className="flex-1 text-xs text-text-secondary">{result.summary}</p>
        <button onClick={copy}
          className={clsx('flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs border transition-colors flex-shrink-0',
            copied ? 'border-accent text-accent bg-accent/10' : 'border-border text-text-muted hover:border-accent hover:text-accent')}>
          {copied ? '✓ Copied' : '⎘ Copy SQL'}
        </button>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border bg-bg-surface flex-shrink-0 px-2">
        {tabs.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            className={clsx('flex items-center gap-1.5 px-3 py-2.5 text-xs font-medium border-b-2 transition-colors',
              tab===t.id ? 'border-accent text-text-primary' : 'border-transparent text-text-muted hover:text-text-secondary')}>
            {t.label}
            {t.badge !== undefined && t.badge > 0 && (
              <span className={clsx('inline-flex items-center justify-center rounded-full w-4 h-4 text-2xs font-bold',
                tab===t.id ? 'bg-accent text-black' : 'bg-bg-overlay text-text-muted')}>
                {t.badge}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-hidden">

        {/* Optimized SQL */}
        {tab === 'query' && (
          <SqlEditor value={result.optimizedQuery} readOnly height="100%" aria-label="Optimized SQL"/>
        )}

        {/* Explanation — what changed and WHY */}
        {tab === 'explain' && (
          <div className="h-full overflow-y-auto p-4 space-y-3">
            {!hasPatterns ? (
              <div className="flex flex-col items-center py-10 text-center">
                <span className="text-3xl mb-2">🎉</span>
                <p className="text-sm font-semibold text-text-primary">This query looks good!</p>
                <p className="text-xs text-text-muted mt-1">No significant issues were found.</p>
              </div>
            ) : (
              result.patternsApplied.map((p, i) => <ExplanationCard key={p.patternId} pattern={p} index={i+1}/>)
            )}
          </div>
        )}

        {/* Cost */}
        {tab === 'cost' && (
          <div className="h-full overflow-y-auto p-4 space-y-4">
            <CostComparison result={result}/>
          </div>
        )}

        {/* Indexes */}
        {tab === 'indexes' && (
          <div className="h-full overflow-y-auto p-4 space-y-3">
            {result.indexRecommendations.map((rec, i) => (
              <div key={i} className="bg-bg-raised border border-border rounded-xl p-4">
                <div className="flex items-center justify-between gap-2 mb-2">
                  <p className="text-sm font-semibold text-text-primary">
                    Add index on <span className="text-accent font-mono">{rec.tableName}</span>
                  </p>
                  <span className="text-xs font-mono text-accent bg-accent/10 border border-accent/20 px-2 py-0.5 rounded-lg flex-shrink-0">
                    +{Math.round(rec.estimatedImprovement*100)}% faster
                  </span>
                </div>
                <p className="text-xs text-text-secondary mb-3 leading-relaxed">{rec.reason}</p>
                <pre className="text-xs font-mono bg-bg-base rounded-lg p-3 text-accent overflow-x-auto border border-accent/20">
                  {rec.createStatement}
                </pre>
              </div>
            ))}
          </div>
        )}

        {/* Plan */}
        {tab === 'plan' && hasPlan && (
          <div className="h-full overflow-y-auto p-4 space-y-3">
            {result.joinOrderExplanation && (
              <div className="bg-bg-raised border border-border rounded-xl p-4">
                <p className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-2">Join Order</p>
                <pre className="text-xs font-mono text-text-primary whitespace-pre-wrap leading-relaxed">
                  {result.joinOrderExplanation}
                </pre>
              </div>
            )}
            <div className="bg-bg-raised border border-border rounded-xl p-4">
              <p className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-3">Execution Plan</p>
              <PlanTree node={result.optimizedPlan!.root} depth={0}/>
            </div>
          </div>
        )}

      </div>
    </div>
  )
}

// ── Explanation card — focused on WHY, not what pattern was used ────────────
function ExplanationCard({ pattern, index }: { pattern: PatternApplication; index: number }) {
  const [open, setOpen] = useState(false)

  return (
    <div className="bg-bg-raised border border-border rounded-xl overflow-hidden">
      <button onClick={() => setOpen(!open)}
        className="w-full flex items-start gap-3 p-4 text-left hover:bg-bg-overlay transition-colors">
        <span className="w-5 h-5 rounded-full bg-accent/20 border border-accent/30 flex items-center justify-center text-2xs font-bold text-accent flex-shrink-0 mt-0.5">
          {index}
        </span>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-text-primary">{pattern.patternName}</p>
          <p className="text-xs text-text-muted mt-0.5 leading-relaxed">{pattern.benefit}</p>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <span className="text-sm font-bold font-mono text-accent">{pattern.estimatedSpeedup != null
  ? pattern.estimatedSpeedup.toFixed(1)
  : '—'}×</span>
          <span className="text-text-disabled text-xs">{open ? '▲' : '▼'}</span>
        </div>
      </button>

      {open && (
        <div className="border-t border-border p-4 space-y-4 animate-fade-in">
          <div>
            <p className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-2">What was wrong</p>
            <p className="text-xs text-text-secondary leading-relaxed">{pattern.problem}</p>
          </div>
          <div>
            <p className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-2">How it was fixed</p>
            <p className="text-xs text-text-secondary leading-relaxed">{pattern.transformation}</p>
          </div>
          {(pattern.beforeSnippet || pattern.afterSnippet) && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <p className="text-2xs font-semibold text-red uppercase tracking-wider mb-1.5">Before</p>
                <pre className="text-2xs font-mono bg-bg-base rounded-lg p-3 text-text-muted overflow-x-auto border border-red/15 leading-relaxed">
                  {pattern.beforeSnippet}
                </pre>
              </div>
              <div>
                <p className="text-2xs font-semibold text-accent uppercase tracking-wider mb-1.5">After</p>
                <pre className="text-2xs font-mono bg-bg-base rounded-lg p-3 text-accent overflow-x-auto border border-accent/15 leading-relaxed">
                  {pattern.afterSnippet}
                </pre>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function CostComparison({ result }: { result: OptimizationResult }) {
  const pct = (v: number) => {
  const base = result.originalCost ?? 1
  return Math.max(4, (v / base) * 100)
}

  return (
    <>
      <div className="space-y-4">
        <div>
          <div className="flex justify-between text-xs mb-2">
            <span className="text-text-secondary font-medium">Original query cost</span>
            <span className="font-mono font-bold text-red">{(result.originalCost ?? 0).toLocaleString()} units</span>
          </div>
          <div className="h-3 bg-bg-raised rounded-full overflow-hidden border border-border">
            <div className="h-full bg-red/60 rounded-full transition-all duration-700" style={{ width: '100%' }}/>
          </div>
        </div>
        <div>
          <div className="flex justify-between text-xs mb-2">
            <span className="text-text-secondary font-medium">Optimized query cost</span>
            <span className="font-mono font-bold text-accent">{(result.optimizedCost ?? 0).toLocaleString()} units</span>
          </div>
          <div className="h-3 bg-bg-raised rounded-full overflow-hidden border border-border">
            <div className="h-full bg-accent rounded-full transition-all duration-700" style={{ width: `${pct(result.optimizedCost ?? 0)}%` }}/>
          </div>
        </div>
      </div>
      <div className="flex items-center justify-center gap-4 bg-accent/5 border border-accent/20 rounded-xl p-5">
        <span className="text-4xl font-bold text-accent">{result.speedupFactor != null
  ? result.speedupFactor.toFixed(1)
  : '—'}×</span>
        <div>
          <p className="text-sm font-semibold text-text-primary">Estimated speedup</p>
          <p className="text-xs text-text-muted mt-0.5">
            Cost reduced from {(result.originalCost ?? 0).toLocaleString()} to {(result.optimizedCost ?? 0).toLocaleString()} units
          </p>
        </div>
      </div>
    </>
  )
}

function PlanTree({ node, depth }: { node: any; depth: number }) {
  if (!node) return null
  const isJoin = (node.children?.length ?? 0) > 0
  return (
    <div style={{ marginLeft: depth * 14 }}>
      <div className={clsx('flex items-center gap-2 py-1.5 px-2.5 rounded-lg text-xs mb-1 border',
        isJoin ? 'bg-blue/10 border-blue/20' : 'bg-bg-overlay border-border')}>
        <span className={clsx('font-bold', isJoin ? 'text-blue' : 'text-text-secondary')}>{node.operation}</span>
        {node.table && <span className="text-accent font-mono">{node.table}</span>}
        {node.condition && <span className="text-text-muted truncate">{node.condition}</span>}
        <span className="text-text-disabled ml-auto font-mono text-2xs">{node.estimatedRows?.toLocaleString()} rows</span>
      </div>
      {node.children?.map((child: any, i: number) => <PlanTree key={i} node={child} depth={depth+1}/>)}
    </div>
  )
}

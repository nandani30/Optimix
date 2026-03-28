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

  const hasIndexes = result.indexRecommendations?.length > 0
  const hasPatterns = result.patternsApplied?.length > 0
  // CRITICAL FIX: If optimizedPlan is null, check for originalPlan so we always show visuals
  const hasPlan = result.optimizedPlan?.root != null || result.originalPlan?.root != null

  const tabs: { id: ResultTab; label: string; badge?: number }[] = [
    { id: 'query',    label: 'Optimized SQL' },
    { id: 'explain',  label: 'Explanation' },
    { id: 'cost',     label: 'Cost' },
    ...(hasIndexes ? [{ id: 'indexes' as ResultTab, label: 'Indexes', badge: result.indexRecommendations.length }] : []),
    ...(hasPlan ? [{ id: 'plan' as ResultTab, label: 'Plan' }] : []),
  ]

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center gap-3 px-4 py-2.5 bg-accent/5 border-b border-accent/20 flex-shrink-0">
        <span className="text-2xl font-bold text-accent leading-none">{result.speedupFactor != null ? result.speedupFactor.toFixed(1) : '—'}×</span>
        <span className="text-xs text-text-muted">faster</span>
        <div className="w-px h-4 bg-border"/>
        <p className="flex-1 text-xs text-text-secondary whitespace-pre-wrap">{result.summary}</p>
        <button onClick={copy}
          className={clsx('flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs border transition-colors flex-shrink-0',
            copied ? 'border-accent text-accent bg-accent/10' : 'border-border text-text-muted hover:border-accent hover:text-accent')}>
          {copied ? '✓ Copied' : '⎘ Copy SQL'}
        </button>
      </div>

      <div className="flex border-b border-border bg-bg-surface flex-shrink-0 px-2 overflow-x-auto">
        {tabs.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            className={clsx('flex items-center whitespace-nowrap gap-1.5 px-3 py-2.5 text-xs font-medium border-b-2 transition-colors',
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

      <div className="flex-1 overflow-hidden">
        {tab === 'query' && (
          <SqlEditor value={result.optimizedQuery} readOnly height="100%" aria-label="Optimized SQL"/>
        )}

        {tab === 'explain' && (
          <div className="h-full overflow-y-auto p-4 space-y-3">
            {!hasPatterns ? (
              <div className="flex flex-col items-center py-10 text-center">
                <span className="text-3xl mb-2">🎉</span>
                <p className="text-sm font-semibold text-text-primary">This query looks good!</p>
                <p className="text-xs text-text-muted mt-1">No significant issues were found.</p>
              </div>
            ) : (
              result.patternsApplied.map((p, i) => <ExplanationCard key={i} app={p} index={i} />)
            )}
          </div>
        )}

        {tab === 'cost' && (
          <div className="h-full p-4 overflow-y-auto flex flex-col gap-6">
            <div className="grid grid-cols-2 gap-4">
               <div className="bg-bg-surface border border-border rounded-lg p-4">
                 <p className="text-xs text-text-muted mb-1 font-medium">Original query cost</p>
                 <p className="text-2xl font-mono text-red">{result.originalCost != null ? result.originalCost.toLocaleString() : '—'} <span className="text-xs text-text-muted">units</span></p>
               </div>
               <div className="bg-bg-surface border border-accent/20 rounded-lg p-4 relative overflow-hidden">
                 <div className="absolute inset-0 bg-accent/5 pointer-events-none"/>
                 <p className="text-xs text-accent/80 mb-1 font-medium">Optimized query cost</p>
                 <p className="text-2xl font-mono text-accent">{result.optimizedCost != null ? result.optimizedCost.toLocaleString() : '—'} <span className="text-xs text-accent/50">units</span></p>
               </div>
            </div>
            {result.speedupFactor != null && result.speedupFactor > 1.0 && (
              <div className="flex flex-col items-center justify-center p-6 bg-bg-surface border border-border rounded-lg text-center">
                <span className="text-4xl font-bold text-accent mb-2">{result.speedupFactor.toFixed(1)}×</span>
                <p className="text-sm font-medium text-text-primary">Estimated speedup</p>
                <p className="text-xs text-text-muted mt-1">
                  Cost reduced from {result.originalCost?.toLocaleString()} to {result.optimizedCost?.toLocaleString()} units
                </p>
              </div>
            )}
          </div>
        )}

        {tab === 'indexes' && (
          <div className="h-full overflow-y-auto p-4 space-y-3">
            {result.indexRecommendations?.map((rec, i) => (
              <div key={i} className="bg-bg-surface border border-border rounded-lg p-4">
                <div className="flex items-start justify-between mb-2">
                  <div>
                    <h4 className="text-sm font-semibold text-text-primary">Missing Index on `{rec.tableName}`</h4>
                    <p className="text-xs text-text-muted mt-0.5">{rec.reason}</p>
                  </div>
                  {rec.confirmed && (
                    <span className="px-2 py-0.5 bg-accent/10 text-accent text-[10px] font-bold rounded uppercase tracking-wide">
                      Confirmed Missing
                    </span>
                  )}
                </div>
                <div className="mt-3 relative">
                  <pre className="text-xs text-accent bg-bg-base p-3 rounded font-mono overflow-x-auto border border-border/50">
                    {rec.createStatement}
                  </pre>
                </div>
              </div>
            ))}
          </div>
        )}

        {tab === 'plan' && (
          <div className="h-full p-4 overflow-y-auto bg-bg-base">
            {result.joinOrderExplanation && result.joinOrderExplanation.trim() !== '' && (
              <div className="bg-bg-surface p-4 rounded-lg border border-border mb-4">
                <h3 className="text-sm font-semibold text-text-primary mb-2 flex items-center gap-2">
                  <svg className="w-4 h-4 text-accent" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4-8-4s8 1.79 8 4m0 5c0 2.21-3.582 4-8-4s-8-1.79-8-4" />
                  </svg>
                  ACCESS ORDER
                </h3>
                <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono">
                  {result.joinOrderExplanation}
                </pre>
              </div>
            )}

            <div className="bg-bg-surface p-4 rounded-lg border border-border">
              <h3 className="text-sm font-semibold text-text-primary mb-3">EXECUTION PLAN</h3>
              {(result.optimizedPlan?.root || result.originalPlan?.root) ? (
                <div className="space-y-4">
                  <div className="overflow-x-auto pb-2">
                    <PlanTree node={result.optimizedPlan?.root || result.originalPlan?.root} />
                  </div>
                  
                  {/* NEW: Render the Raw MySQL Table Output! */}
                  {(result.optimizedPlan?.rawExplain || result.originalPlan?.rawExplain) && (
                    <div className="mt-4 pt-4 border-t border-border/50">
                      <p className="text-[10px] font-bold text-text-muted uppercase tracking-wider mb-2">Raw MySQL Output</p>
                      <pre className="text-xs text-accent bg-bg-base p-3 rounded font-mono overflow-x-auto border border-border/50 whitespace-pre">
                        {result.optimizedPlan?.rawExplain || result.originalPlan?.rawExplain}
                      </pre>
                    </div>
                  )}
                </div>
              ) : (
                <p className="text-xs text-text-muted italic py-4 text-center border border-dashed border-border rounded">
                  No visual execution plan available for this query.
                </p>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function ExplanationCard({ app, index }: { app: PatternApplication; index: number }) {
  const impactColor = 
    app.impactLevel === 'HIGH' ? 'text-red' :
    app.impactLevel === 'MEDIUM' ? 'text-yellow' : 'text-accent'

  return (
    <div className="bg-bg-surface border border-border rounded-lg overflow-hidden flex flex-col">
      <div className="p-3 border-b border-border/50 bg-bg-base/30 flex items-center gap-3">
        <span className="flex items-center justify-center w-5 h-5 rounded-full bg-bg-overlay text-xs font-bold text-text-muted">
          {index + 1}
        </span>
        <h3 className="text-sm font-semibold text-text-primary flex-1">{app.patternName}</h3>
        {app.impactLevel && (
          <span className={clsx('text-[10px] font-bold tracking-wider px-2 py-0.5 rounded bg-bg-overlay border border-border', impactColor)}>
            {app.impactLevel}
          </span>
        )}
      </div>
      <div className="p-3 space-y-3">
        <div>
          <p className="text-[10px] font-bold text-text-muted uppercase tracking-wider mb-1">What was wrong</p>
          <p className="text-xs text-text-secondary leading-relaxed">{app.problem}</p>
        </div>
        <div>
          <p className="text-[10px] font-bold text-text-muted uppercase tracking-wider mb-1">How it was fixed</p>
          <p className="text-xs text-text-secondary leading-relaxed">{app.transformation}</p>
        </div>
        <div className="grid grid-cols-2 gap-3 pt-2">
          <div>
            <p className="text-[10px] font-bold text-text-muted uppercase tracking-wider mb-1">Before</p>
            <div className="font-mono text-xs text-red bg-red/5 p-2 rounded border border-red/10 overflow-x-auto whitespace-pre">
              {app.beforeSnippet}
            </div>
          </div>
          <div>
            <p className="text-[10px] font-bold text-text-muted uppercase tracking-wider mb-1">After</p>
            <div className="font-mono text-xs text-accent bg-accent/5 p-2 rounded border border-accent/10 overflow-x-auto whitespace-pre">
              {app.afterSnippet}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function PlanTree({ node }: { node: any }) {
  if (!node) return null
  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-2 mb-1">
        <span className="px-1.5 py-0.5 bg-accent/10 border border-accent/20 rounded text-[10px] text-accent font-bold tracking-wider font-mono">
          {node.accessType || 'UNKNOWN'}
        </span>
        {node.table && (
          <span className="text-xs font-semibold text-text-primary">
            {node.table}
          </span>
        )}
        {node.rowEstimate != null && (
          <span className="text-xs text-text-muted ml-auto">
            ~{node.rowEstimate.toLocaleString()} rows
          </span>
        )}
      </div>
      
      {(node.keyUsed || node.extra) && (
        <div className="ml-2 pl-4 border-l-2 border-border/50 pb-2">
          {node.keyUsed && (
            <p className="text-[11px] text-text-secondary mt-1">
              <span className="text-text-muted font-medium">Index:</span> <code className="text-accent bg-bg-overlay px-1 rounded">{node.keyUsed}</code>
            </p>
          )}
          {node.extra && (
            <p className="text-[11px] text-text-secondary mt-1">
              <span className="text-text-muted font-medium">Extra:</span> {node.extra}
            </p>
          )}
        </div>
      )}

      {node.children && node.children.length > 0 && (
        <div className="ml-2 pl-4 border-l-2 border-border mt-1 pt-1 space-y-3">
          {node.children.map((child: any, i: number) => (
            <PlanTree key={i} node={child} />
          ))}
        </div>
      )}
    </div>
  )
}
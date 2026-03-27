import { useEffect, useState } from 'react'
import { clsx } from 'clsx'
import { useConnectionStore } from '../store'
import { connectionsApi } from '../api'
import type { ConnectionForm, ConnectionTestResult } from '../types'

const EMPTY: ConnectionForm = {
  profileName: '', host: 'localhost', port: 3306,
  databaseName: '', mysqlUsername: '', mysqlPassword: ''
}

export default function ConnectionsPage() {
  const { connections, setConnections, addConnection, removeConnection, activeConnectionId, setActiveConnectionId } = useConnectionStore()
  const [showForm, setShowForm]     = useState(false)
  const [form, setForm]             = useState<ConnectionForm>(EMPTY)
  const [busy, setBusy]             = useState<'idle'|'testing'|'saving'>('idle')
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null)
  const [formError, setFormError]   = useState('')
  const [savedTests, setSavedTests] = useState<Record<number, ConnectionTestResult>>({})
  const [deletingId, setDeletingId] = useState<number|null>(null)
  const [testingId, setTestingId]   = useState<number|null>(null)

  useEffect(() => { connectionsApi.list().then(setConnections).catch(() => {}) }, [])

  const f = (k: keyof ConnectionForm, v: string | number) => {
    setForm(p => ({ ...p, [k]: v })); setFormError('')
  }

  const handleTest = async () => {
    setBusy('testing'); setTestResult(null)
    try { setTestResult(await connectionsApi.test(form)) }
    catch (e: unknown) { setTestResult({ success: false, message: e instanceof Error ? e.message : 'Connection failed' }) }
    finally { setBusy('idle') }
  }

  const handleSave = async () => {
    if (!form.profileName.trim()) { setFormError('Give this connection a name'); return }
    if (!form.host.trim())        { setFormError('Host is required'); return }
    if (!form.databaseName.trim()){ setFormError('Database name is required'); return }
    if (!form.mysqlUsername.trim()){ setFormError('MySQL username is required'); return }
    setBusy('saving')
    try {
      const { id } = await connectionsApi.save(form)
      addConnection({ connectionId: id, profileName: form.profileName, host: form.host, port: form.port, databaseName: form.databaseName, mysqlUsername: form.mysqlUsername, createdAt: new Date().toISOString() })
      setShowForm(false); setForm(EMPTY); setTestResult(null)
    } catch (e: unknown) { setFormError(e instanceof Error ? e.message : 'Save failed') }
    finally { setBusy('idle') }
  }

  const inp = 'w-full bg-bg-raised border border-border rounded-lg px-3 py-2.5 text-sm text-text-primary placeholder-text-muted focus:outline-none focus:border-accent transition-colors'

  return (
    <div className="h-full overflow-y-auto bg-bg-base">
      <div className="max-w-xl mx-auto px-6 py-6">

        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-base font-bold text-text-primary">MySQL Connections</h1>
            <p className="text-xs text-text-muted mt-0.5">Connect to your local or remote MySQL database</p>
          </div>
          {!showForm && (
            <button onClick={() => { setShowForm(true); setTestResult(null); setFormError('') }}
              className="px-3 py-2 bg-accent hover:bg-accent/90 text-black text-sm font-semibold rounded-lg transition-colors">
              + Add Connection
            </button>
          )}
        </div>

        {/* Add form */}
        {showForm && (
          <div className="bg-bg-surface border border-border rounded-xl p-5 mb-5 animate-slide-up">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-sm font-semibold text-text-primary">New Connection</h2>
              <button onClick={() => { setShowForm(false); setForm(EMPTY); setTestResult(null) }}
                className="text-text-muted hover:text-text-primary text-lg leading-none">✕</button>
            </div>
            <div className="space-y-3">
              <input placeholder="Connection name (e.g. My Local DB)"
                value={form.profileName} onChange={e => f('profileName', e.target.value)} className={inp} />
              <div className="grid grid-cols-3 gap-2">
                <div className="col-span-2">
                  <input placeholder="Host (e.g. localhost or 127.0.0.1)"
                    value={form.host} onChange={e => f('host', e.target.value)} className={inp} />
                </div>
                <input placeholder="Port" type="number"
                  value={form.port} onChange={e => f('port', parseInt(e.target.value) || 3306)} className={inp} />
              </div>
              <input placeholder="Database name (e.g. optimix_test)"
                value={form.databaseName} onChange={e => f('databaseName', e.target.value)} className={inp} />
              <div className="grid grid-cols-2 gap-2">
                <input placeholder="MySQL username (e.g. root)"
                  value={form.mysqlUsername} onChange={e => f('mysqlUsername', e.target.value)} className={inp} />
                <input type="password" placeholder="MySQL password"
                  value={form.mysqlPassword} onChange={e => f('mysqlPassword', e.target.value)} className={inp} />
              </div>

              {testResult && (
                <div className={clsx('flex items-start gap-2 p-3 rounded-lg text-xs border',
                  testResult.success ? 'bg-accent/5 border-accent/20 text-accent' : 'bg-red/5 border-red/20 text-red')}>
                  <span className="font-bold flex-shrink-0">{testResult.success ? '✓' : '✗'}</span>
                  <div>
                    <p className="font-medium">{testResult.message}</p>
                    {testResult.success && testResult.mysqlVersion && (
                      <p className="text-text-muted mt-0.5">MySQL {testResult.mysqlVersion} · {testResult.tableCount} tables</p>
                    )}
                  </div>
                </div>
              )}

              {formError && <p className="text-xs text-red">{formError}</p>}

              <div className="flex gap-2 pt-1">
                <button onClick={handleTest} disabled={busy !== 'idle'}
                  className="flex-1 py-2.5 border border-border rounded-lg text-sm text-text-secondary hover:text-text-primary hover:border-text-muted disabled:opacity-40 transition-colors">
                  {busy === 'testing' ? '⟳ Testing…' : 'Test Connection'}
                </button>
                <button onClick={handleSave} disabled={busy !== 'idle'}
                  className="flex-1 py-2.5 bg-accent hover:bg-accent/90 text-black text-sm font-semibold rounded-lg disabled:opacity-40 transition-colors">
                  {busy === 'saving' ? '⟳ Saving…' : 'Save'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Connections list */}
        {connections.length === 0 && !showForm ? (
          <div className="flex flex-col items-center py-16 text-center">
            <span className="text-4xl mb-3 opacity-20">🔌</span>
            <p className="text-sm text-text-muted">No connections yet</p>
            <p className="text-xs text-text-disabled mt-1 mb-5">
              Add your MySQL database to get real statistics during optimization
            </p>
            <button onClick={() => setShowForm(true)}
              className="px-4 py-2 bg-accent hover:bg-accent/90 text-black text-sm font-semibold rounded-lg transition-colors">
              + Add Connection
            </button>
          </div>
        ) : (
          <div className="space-y-2">
            {connections.map(c => {
              const test = savedTests[c.connectionId]
              const active = activeConnectionId === c.connectionId
              return (
                <div key={c.connectionId}
                  className={clsx('bg-bg-surface border rounded-xl p-4 transition-colors',
                    active ? 'border-accent/40' : 'border-border')}>
                  <div className="flex items-start gap-3">
                    <div className={clsx('w-2 h-2 rounded-full mt-1.5 flex-shrink-0 transition-colors',
                      test?.success === true ? 'bg-accent' : test?.success === false ? 'bg-red' : 'bg-border')} />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-semibold text-text-primary">{c.profileName}</span>
                        {active && (
                          <span className="text-2xs px-1.5 py-0.5 bg-accent-subtle border border-accent-muted rounded text-accent font-semibold">
                            Active
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-text-muted font-mono mt-0.5">
                        {c.mysqlUsername}@{c.host}:{c.port}/{c.databaseName}
                      </p>
                      {test && (
                        <p className={clsx('text-2xs mt-1', test.success ? 'text-accent' : 'text-red')}>
                          {test.success ? `✓ MySQL ${test.mysqlVersion} · ${test.tableCount} tables` : `✗ ${test.message}`}
                        </p>
                      )}
                    </div>
                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      <button
                        onClick={() => setActiveConnectionId(active ? null : c.connectionId)}
                        className={clsx('text-xs px-2.5 py-1 rounded-lg border transition-colors',
                          active ? 'border-accent/50 text-accent bg-accent-subtle' : 'border-border text-text-muted hover:border-accent hover:text-accent')}>
                        {active ? 'Active' : 'Use'}
                      </button>
                      <button
                        onClick={async () => {
                          setTestingId(c.connectionId)
                          try {
                            const r = await connectionsApi.testSaved(c.connectionId)
                            setSavedTests(p => ({ ...p, [c.connectionId]: r }))
                          } catch (e: unknown) {
                            setSavedTests(p => ({ ...p, [c.connectionId]: { success: false, message: e instanceof Error ? e.message : 'Failed' } }))
                          } finally { setTestingId(null) }
                        }}
                        disabled={testingId === c.connectionId}
                        className="text-xs px-2.5 py-1 rounded-lg border border-border text-text-muted hover:border-blue hover:text-blue transition-colors disabled:opacity-40">
                        {testingId === c.connectionId ? '⟳' : 'Test'}
                      </button>
                      <button
                        onClick={async () => {
                          if (!confirm(`Delete "${c.profileName}"?`)) return
                          setDeletingId(c.connectionId)
                          try { await connectionsApi.delete(c.connectionId); removeConnection(c.connectionId) }
                          finally { setDeletingId(null) }
                        }}
                        disabled={deletingId === c.connectionId}
                        className="text-xs px-2.5 py-1 rounded-lg border border-border text-text-muted hover:border-red hover:text-red transition-colors disabled:opacity-40">
                        {deletingId === c.connectionId ? '⟳' : 'Delete'}
                      </button>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

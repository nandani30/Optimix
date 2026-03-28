import { useState, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store'
import { authApi } from '../api'
import type { AuthScreen } from '../types'

const GOOGLE_CLIENT_ID = '59742668602-7r8dmcbsvmm4vjc4ib80lp7kcch67g7b.apps.googleusercontent.com'
const isElectron = typeof window !== 'undefined' && navigator.userAgent.includes('Electron')

declare global {
  interface Window { google?: any }
}

export default function AuthPage() {
  const [screen, setScreen]        = useState<AuthScreen>('login')
  const [pendingEmail, setPending] = useState('')
  const [error, setError]          = useState('')
  const [success, setSuccess]      = useState('')
  const [loading, setLoading]      = useState(false)
  const [otpDigits, setOtpDigits]  = useState(['','','','','',''])
  const otpRefs = [0,1,2,3,4,5].map(() => useRef<HTMLInputElement>(null))

  const { setAuth } = useAuthStore()
  const navigate    = useNavigate()
  const go = (s: AuthScreen) => { setScreen(s); setError(''); setSuccess('') }

  // 🔴 CRITICAL FIX: Listen for token passed back from Electron main.js 🔴
  useEffect(() => {
    if (!isElectron) return;
    const handleMessage = async (e: MessageEvent) => {
      if (e.data?.type === 'GOOGLE_AUTH_TOKEN') {
        setError(''); setLoading(true);
        try {
          const res = await authApi.googleLogin(e.data.token);
          setAuth(res.user, res.token);
          window.location.href = '/optimizer';
        } catch (err: any) {
          setError(err?.message || 'Google sign-in failed');
        } finally {
          setLoading(false);
        }
      }
    };
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [setAuth]);

  useEffect(() => {
    if (isElectron || !window.google) return
    const tryInit = () => {
      if (!window.google) return
      window.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        use_fedcm_for_prompt: false,
        callback: async (r: any) => {
          setError(''); setLoading(true)
          try {
              const res = await authApi.googleLogin(r.credential)
              setAuth(res.user, res.token)
              window.location.href = '/optimizer'
            }
          catch (e: any) { setError(e?.message || 'Google sign-in failed') }
          finally { setLoading(false) }
        },
      })
      const el = document.getElementById('google-btn')
      if (el && !el.childNodes.length)
        window.google.accounts.id.renderButton(el, { theme: 'filled_black', size: 'large', text: 'continue_with', width: 300 })
    }
    tryInit(); const t = setTimeout(tryInit, 1000); return () => clearTimeout(t)
  }, [screen, setAuth])

  const handleLogin = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault(); setError(''); setLoading(true)
    const fd = new FormData(e.currentTarget)
    try {
      const res = await authApi.login({ email: (fd.get('email') as string).trim().toLowerCase(), password: fd.get('password') as string })
      setAuth(res.user, res.token); navigate('/optimizer')
    } catch (e: any) { setError(e?.message || 'Login failed') }
    finally { setLoading(false) }
  }

  const handleSignup = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault(); setError(''); setLoading(true)
    const fd = new FormData(e.currentTarget)
    const email = (fd.get('email') as string).trim().toLowerCase()
    try {
      await authApi.signup({ fullName: (fd.get('fullName') as string).trim(), email, password: fd.get('password') as string })
      setPending(email); setOtpDigits(['','','','','','']); go('otp')
    } catch (e: any) { setError(e?.message || 'Signup failed') }
    finally { setLoading(false) }
  }

  const handleOtp = async () => {
    const code = otpDigits.join(''); if (code.length !== 6) { setError('Enter all 6 digits'); return }
    setError(''); setLoading(true)
    try {
      const res = await authApi.verifyOtp({ email: pendingEmail, otpCode: code })
      setAuth(res.user, res.token); navigate('/optimizer')
    } catch (e: any) {
      setError(e?.message || 'Invalid code'); setOtpDigits(['','','','','','']); setTimeout(() => otpRefs[0].current?.focus(), 50)
    } finally { setLoading(false) }
  }

  const handleForgot = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault(); setError(''); setLoading(true)
    const email = (new FormData(e.currentTarget).get('email') as string).trim().toLowerCase()
    try { await authApi.forgotPassword(email); setPending(email); setOtpDigits(['','','','','','']); go('reset'); setSuccess('Code sent! Check your inbox.') }
    catch (e: any) { setError(e?.message || 'Failed') }
    finally { setLoading(false) }
  }

  const handleReset = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault(); setError(''); setLoading(true)
    const fd = new FormData(e.currentTarget)
    const pwd = fd.get('newPassword') as string
    if (pwd !== fd.get('confirm')) { setError("Passwords don't match"); setLoading(false); return }
    try { await authApi.resetPassword(pendingEmail, otpDigits.join(''), pwd); go('login'); setSuccess('Password reset! Sign in with your new password.') }
    catch (e: any) { setError(e?.message || 'Reset failed') }
    finally { setLoading(false) }
  }

  const handleOtpKey = (i: number, val: string) => {
    if (!/^\d?$/.test(val)) return
    const next = [...otpDigits]; next[i] = val; setOtpDigits(next)
    if (val && i < 5) setTimeout(() => otpRefs[i+1].current?.focus(), 0)
    if (!val && i > 0) setTimeout(() => otpRefs[i-1].current?.focus(), 0)
  }

  const inp = 'w-full bg-bg-raised border border-border rounded-xl px-4 py-3 text-sm text-text-primary placeholder-text-muted focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent/20 transition-all'
  const primary = (off?: boolean) => `w-full py-3 rounded-xl text-sm font-semibold transition-all ${off ? 'bg-accent/30 text-black/40 cursor-not-allowed' : 'bg-accent hover:bg-accent/90 text-black'}`

  const OtpBoxes = () => (
    <div className="flex gap-2 justify-center mb-5" onPaste={e => {
      const p = e.clipboardData.getData('text').replace(/\D/g,'').slice(0,6)
      if (p.length===6) { setOtpDigits(p.split('')); setTimeout(()=>otpRefs[5].current?.focus(),0) }
    }}>
      {otpDigits.map((d,i)=>(
        <input key={i} ref={otpRefs[i]} type="text" inputMode="numeric" maxLength={1} value={d}
          autoFocus={i===0} onChange={e=>handleOtpKey(i,e.target.value)}
          onKeyDown={e=>{ if(e.key==='Backspace'&&!d&&i>0){e.preventDefault();setTimeout(()=>otpRefs[i-1].current?.focus(),0)} }}
          className="w-11 h-12 text-center text-xl font-bold font-mono bg-bg-raised border border-border rounded-xl text-text-primary focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent/20 transition-all" />
      ))}
    </div>
  )

  const Divider = () => (
    <div className="relative my-5">
      <div className="absolute inset-0 flex items-center"><div className="w-full border-t border-border"/></div>
      <div className="relative flex justify-center"><span className="bg-bg-surface px-3 text-xs text-text-muted uppercase tracking-widest">or</span></div>
    </div>
  )

  const GoogleBtn = () => isElectron ? (
    <button onClick={async () => {
      const params = new URLSearchParams({ client_id: GOOGLE_CLIENT_ID, redirect_uri: 'http://localhost:5173', response_type: 'id_token', scope: 'openid email profile', nonce: Math.random().toString(36).slice(2) })
      window.open(`https://accounts.google.com/o/oauth2/v2/auth?${params}`, '_blank')
    }} className="w-full flex items-center justify-center gap-3 py-2.5 px-4 border border-border rounded-xl text-sm font-medium text-text-primary hover:bg-bg-raised transition-all cursor-pointer">
      <svg className="w-4 h-4" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/></svg>
      Continue with Google
    </button>
  ) : <div id="google-btn" className="flex justify-center min-h-[44px] items-center"/>

  return (
    <div className="min-h-screen bg-bg-base flex overflow-hidden">
      {/* Left panel */}
      <div className="hidden lg:flex lg:w-[45%] flex-col p-12 bg-bg-surface border-r border-border relative overflow-hidden">
        <div className="absolute inset-0 pointer-events-none">
          <div className="absolute -top-40 -left-40 w-96 h-96 bg-accent/5 rounded-full blur-3xl"/>
          <div className="absolute -bottom-40 -right-40 w-80 h-80 bg-blue/5 rounded-full blur-3xl"/>
        </div>
        <div className="relative z-10 flex-1 flex flex-col justify-between">
          <div>
            <div className="flex items-center gap-2.5 mb-14">
              <div className="w-8 h-8 rounded-xl bg-accent/10 border border-accent/20 flex items-center justify-center">⚡</div>
              <span className="text-lg font-bold tracking-tight">Optimix</span>
            </div>
            <h1 className="text-4xl font-bold leading-tight tracking-tight mb-4">
              Paste a slow query.<br/>
              <span className="gradient-text">Get a fast one.</span>
            </h1>
            <p className="text-text-secondary text-base leading-relaxed mb-10 max-w-xs">
              Optimix analyzes your MySQL queries and rewrites them — with a clear explanation of what changed and why.
            </p>
            <div className="space-y-4">
              {[
                { icon: '⚡', text: 'Detects 40 types of SQL inefficiencies automatically' },
                { icon: '📝', text: 'Explains every change in plain English' },
                { icon: '📊', text: 'Shows cost reduction before and after' },
                { icon: '🔒', text: 'Everything runs locally — your data never leaves your machine' },
              ].map(f => (
                <div key={f.icon} className="flex items-center gap-3">
                  <span className="text-xl">{f.icon}</span>
                  <p className="text-sm text-text-secondary">{f.text}</p>
                </div>
              ))}
            </div>
          </div>
          {/* Query preview */}
          <div className="bg-bg-raised border border-border rounded-2xl p-5 font-mono text-xs">
            <div className="flex gap-1.5 mb-3">
              <div className="w-3 h-3 rounded-full bg-red/40"/><div className="w-3 h-3 rounded-full bg-yellow/40"/><div className="w-3 h-3 rounded-full bg-accent/40"/>
            </div>
            <p className="text-red/60 line-through mb-1"><span className="text-blue/60">WHERE</span> YEAR(created_at) = 2024</p>
            <p className="text-accent mb-3"><span className="text-blue">WHERE</span> created_at &gt;= '2024-01-01'<br/><span className="text-blue">  AND</span> created_at &lt; '2025-01-01'</p>
            <div className="border-t border-border pt-3 flex items-center gap-2">
              <span className="text-accent font-bold">60× faster</span>
              <span className="text-text-muted font-sans">— Index can now be used</span>
            </div>
          </div>
        </div>
      </div>

      {/* Right panel — auth forms */}
      <div className="flex-1 flex items-center justify-center px-6 py-10 overflow-y-auto">
        <div className="w-full max-w-[340px] animate-fade-in">
          <div className="lg:hidden text-center mb-8">
            <div className="w-10 h-10 rounded-xl bg-accent/10 border border-accent/20 flex items-center justify-center text-lg mx-auto mb-2">⚡</div>
            <p className="font-bold text-text-primary">Optimix</p>
          </div>

          <div className="bg-bg-surface border border-border rounded-2xl shadow-panel overflow-hidden">

            {screen === 'login' && (
              <div className="p-7">
                <h2 className="text-lg font-bold text-text-primary mb-1">Sign in</h2>
                <p className="text-sm text-text-muted mb-6">Welcome back to Optimix</p>
                {success && <div className="text-xs text-accent bg-accent/10 border border-accent/20 rounded-xl px-3 py-2.5 mb-4">{success}</div>}
                <form onSubmit={handleLogin} className="space-y-3">
                  <input name="email" type="email" required autoFocus placeholder="Email address" className={inp}/>
                  <input name="password" type="password" required placeholder="Password" className={inp}/>
                  {error && <p className="text-xs text-red pl-1">{error}</p>}
                  <button type="submit" disabled={loading} className={primary(loading)}>{loading ? 'Signing in…' : 'Sign in'}</button>
                </form>
                <div className="text-right mt-2.5">
                  <button onClick={() => go('forgot')} className="text-xs text-text-muted hover:text-accent transition-colors">Forgot password?</button>
                </div>
                <Divider/>
                <GoogleBtn/>
                <p className="text-center text-sm text-text-muted mt-5">No account? <button onClick={() => go('signup')} className="text-accent hover:underline font-semibold">Create one</button></p>
              </div>
            )}

            {screen === 'signup' && (
              <div className="p-7">
                <h2 className="text-lg font-bold text-text-primary mb-1">Create account</h2>
                <p className="text-sm text-text-muted mb-6">Free to use, runs on your machine</p>
                <form onSubmit={handleSignup} className="space-y-3">
                  <input name="fullName" type="text" required autoFocus placeholder="Full name" className={inp}/>
                  <input name="email" type="email" required placeholder="Email address" className={inp}/>
                  <input name="password" type="password" required minLength={8} placeholder="Password (min 8 chars)" className={inp}/>
                  {error && <p className="text-xs text-red pl-1">{error}</p>}
                  <button type="submit" disabled={loading} className={primary(loading)}>{loading ? 'Sending code…' : 'Create account'}</button>
                </form>
                <Divider/>
                <GoogleBtn/>
                <p className="text-center text-sm text-text-muted mt-5">Have an account? <button onClick={() => go('login')} className="text-accent hover:underline font-semibold">Sign in</button></p>
              </div>
            )}

            {screen === 'otp' && (
              <div className="p-7">
                <div className="text-center mb-6">
                  <div className="w-12 h-12 rounded-2xl bg-accent/10 border border-accent/20 flex items-center justify-center text-xl mx-auto mb-3">📧</div>
                  <h2 className="text-base font-bold text-text-primary">Check your email</h2>
                  <p className="text-sm text-text-muted mt-1">Code sent to <span className="text-text-primary font-medium">{pendingEmail}</span></p>
                </div>
                <OtpBoxes/>
                {error && <p className="text-xs text-red text-center mb-3">{error}</p>}
                <button onClick={handleOtp} disabled={loading||otpDigits.some(d=>!d)} className={primary(loading||otpDigits.some(d=>!d))}>
                  {loading ? 'Verifying…' : 'Verify & create account'}
                </button>
                <div className="flex justify-between mt-4 text-xs text-text-muted">
                  <button onClick={() => authApi.resendOtp(pendingEmail).catch(()=>{})} className="hover:text-accent transition-colors">Resend code</button>
                  <button onClick={() => go('signup')} className="hover:text-text-primary transition-colors">← Back</button>
                </div>
              </div>
            )}

            {screen === 'forgot' && (
              <div className="p-7">
                <div className="text-center mb-6">
                  <div className="w-12 h-12 rounded-2xl bg-yellow/10 border border-yellow/20 flex items-center justify-center text-xl mx-auto mb-3">🔐</div>
                  <h2 className="text-base font-bold text-text-primary">Reset your password</h2>
                  <p className="text-sm text-text-muted mt-1">Enter your email to get a reset code</p>
                </div>
                <form onSubmit={handleForgot} className="space-y-3">
                  <input name="email" type="email" required autoFocus placeholder="Email address" className={inp}/>
                  {error && <p className="text-xs text-red pl-1">{error}</p>}
                  <button type="submit" disabled={loading} className={primary(loading)}>{loading ? 'Sending…' : 'Send reset code'}</button>
                </form>
                <p className="text-center text-sm text-text-muted mt-5"><button onClick={() => go('login')} className="text-accent hover:underline">← Back to sign in</button></p>
              </div>
            )}

            {screen === 'reset' && (
              <div className="p-7">
                <div className="text-center mb-6">
                  <div className="w-12 h-12 rounded-2xl bg-accent/10 border border-accent/20 flex items-center justify-center text-xl mx-auto mb-3">🔑</div>
                  <h2 className="text-base font-bold text-text-primary">Set new password</h2>
                  {success && <p className="text-xs text-accent mt-2">{success}</p>}
                  <p className="text-sm text-text-muted mt-1">Code sent to <span className="text-text-primary">{pendingEmail}</span></p>
                </div>
                <OtpBoxes/>
                <form onSubmit={handleReset} className="space-y-3">
                  <input name="newPassword" type="password" required minLength={8} placeholder="New password" className={inp}/>
                  <input name="confirm" type="password" required minLength={8} placeholder="Confirm new password" className={inp}/>
                  {error && <p className="text-xs text-red pl-1">{error}</p>}
                  <button type="submit" disabled={loading||otpDigits.some(d=>!d)} className={primary(loading||otpDigits.some(d=>!d))}>{loading ? 'Resetting…' : 'Reset password'}</button>
                </form>
                <div className="flex justify-between mt-4 text-xs text-text-muted">
                  <button onClick={() => go('forgot')} className="hover:text-accent transition-colors">Resend code</button>
                  <button onClick={() => go('login')} className="hover:text-text-primary transition-colors">← Sign in</button>
                </div>
              </div>
            )}

          </div>
        </div>
      </div>
    </div>
  )
}
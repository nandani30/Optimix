/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        'bg-base':       '#09090b',   // zinc-950
        'bg-surface':    '#111113',   // slightly lighter
        'bg-raised':     '#18181b',   // zinc-900
        'bg-overlay':    '#1f1f23',   // zinc-800 area
        'border':        '#27272a',   // zinc-800
        'border-subtle': '#1f1f23',

        'text-primary':   '#fafafa',  // zinc-50
        'text-secondary': '#a1a1aa',  // zinc-400
        'text-muted':     '#71717a',  // zinc-500
        'text-disabled':  '#3f3f46',  // zinc-700

        'accent':        '#22c55e',   // green-500 — brighter, more readable
        'accent-muted':  '#16a34a',   // green-600
        'accent-subtle': '#052e16',   // green-950

        'blue':    '#3b82f6',
        'yellow':  '#eab308',
        'red':     '#ef4444',
        'purple':  '#a855f7',

        'tier1': '#a855f7',
        'tier2': '#3b82f6',
        'tier3': '#22c55e',
      },
      fontFamily: {
        sans: ['"Geist"', '"Inter"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"Fira Code"', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.65rem', { lineHeight: '1rem' }],
      },
      boxShadow: {
        'glow':    '0 0 24px rgba(34,197,94,0.18)',
        'glow-sm': '0 0 10px rgba(34,197,94,0.12)',
        'panel':   '0 8px 32px rgba(0,0,0,0.5)',
        'card':    '0 2px 8px rgba(0,0,0,0.3)',
      },
      animation: {
        'fade-in':  'fadeIn 0.2s ease-out',
        'slide-up': 'slideUp 0.2s ease-out',
        'spin-slow': 'spin 1.5s linear infinite',
        'pulse-green': 'pulseGreen 2s ease-in-out infinite',
      },
      keyframes: {
        fadeIn:  { from: { opacity: '0' }, to: { opacity: '1' } },
        slideUp: { from: { opacity: '0', transform: 'translateY(8px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        pulseGreen: { '0%,100%': { boxShadow: '0 0 0 0 rgba(34,197,94,0)' }, '50%': { boxShadow: '0 0 0 4px rgba(34,197,94,0.1)' } },
      },
    },
  },
  plugins: [],
}

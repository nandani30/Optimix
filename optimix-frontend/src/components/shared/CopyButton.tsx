import { useState, useCallback } from 'react'
import { clsx } from 'clsx'
import { copyToClipboard } from '../../utils'

interface Props {
  text: string
  label?: string
  className?: string
}

/**
 * Button that copies text to clipboard with a brief "Copied!" confirmation.
 */
export default function CopyButton({ text, label = 'Copy', className }: Props) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(async () => {
    const ok = await copyToClipboard(text)
    if (ok) {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    }
  }, [text])

  return (
    <button
      onClick={handleCopy}
      className={clsx(
        'inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs border transition-colors',
        copied
          ? 'border-accent text-accent bg-accent-subtle'
          : 'border-border text-text-muted hover:border-accent hover:text-accent',
        className
      )}
      aria-label={copied ? 'Copied!' : `Copy ${label}`}
    >
      <span>{copied ? '✓' : '⎘'}</span>
      {copied ? 'Copied!' : label}
    </button>
  )
}

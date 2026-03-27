import { clsx } from 'clsx'

interface Props {
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

export default function Spinner({ size = 'md', className }: Props) {
  const sizeClass = {
    sm: 'w-4 h-4 border',
    md: 'w-6 h-6 border-2',
    lg: 'w-10 h-10 border-2',
  }[size]

  return (
    <div
      className={clsx(
        'rounded-full border-border border-t-accent animate-spin',
        sizeClass,
        className
      )}
      role="status"
      aria-label="Loading"
    />
  )
}

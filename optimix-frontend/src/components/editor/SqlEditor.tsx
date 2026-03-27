import Editor, { type OnMount, type Monaco } from '@monaco-editor/react'
import type { editor } from 'monaco-editor'
import { useRef, useCallback } from 'react'

interface Props {
  value: string
  onChange?: (value: string) => void
  readOnly?: boolean
  height?: string
  placeholder?: string
  'aria-label'?: string
}

function defineOptimixTheme(monaco: Monaco) {
  monaco.editor.defineTheme('optimix-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword.sql',           foreground: '58A6FF', fontStyle: 'bold' },
      { token: 'keyword',               foreground: '58A6FF' },
      { token: 'string.sql',            foreground: 'A8FF78' },
      { token: 'string',                foreground: 'A8FF78' },
      { token: 'number',                foreground: 'FFA657' },
      { token: 'comment.sql',           foreground: '6E7681', fontStyle: 'italic' },
      { token: 'comment',               foreground: '6E7681', fontStyle: 'italic' },
      { token: 'operator.sql',          foreground: 'FF7B72' },
      { token: 'delimiter.sql',         foreground: 'E6EDF3' },
      { token: 'identifier',            foreground: 'E6EDF3' },
      { token: 'type',                  foreground: 'BC8CFF' },
    ],
    colors: {
      'editor.background':              '#0D1117',
      'editor.foreground':              '#E6EDF3',
      'editor.lineHighlightBackground': '#161B2288',
      'editor.selectionBackground':     '#264F7880',
      'editor.inactiveSelectionBackground': '#264F7840',
      'editorCursor.foreground':        '#39D353',
      'editorLineNumber.foreground':    '#484F58',
      'editorLineNumber.activeForeground': '#8B949E',
      'editorGutter.background':        '#0D1117',
      'editorIndentGuide.background':   '#21262D',
      'editorIndentGuide.activeBackground': '#30363D',
      'scrollbarSlider.background':     '#30363D80',
      'scrollbarSlider.hoverBackground':'#484F5880',
      'editorWidget.background':        '#161B22',
      'editorWidget.border':            '#30363D',
      'input.background':               '#0D1117',
      'input.border':                   '#30363D',
    },
  })
}

function registerSqlCompletions(monaco: Monaco) {
  monaco.languages.registerCompletionItemProvider('sql', {
    provideCompletionItems(model, position) {
      const word  = model.getWordUntilPosition(position)
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber:   position.lineNumber,
        startColumn:     word.startColumn,
        endColumn:       word.endColumn,
      }
      const kw = (label: string) => ({
        label,
        kind:       monaco.languages.CompletionItemKind.Keyword,
        insertText: label,
        range,
      })
      return {
        suggestions: [
          'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'NOT IN',
          'EXISTS', 'NOT EXISTS', 'BETWEEN', 'LIKE', 'IS NULL', 'IS NOT NULL',
          'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'OUTER JOIN', 'CROSS JOIN',
          'ON', 'USING', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'OFFSET',
          'UNION', 'UNION ALL', 'INTERSECT', 'EXCEPT',
          'INSERT INTO', 'UPDATE', 'DELETE FROM', 'SET',
          'CREATE TABLE', 'CREATE INDEX', 'DROP TABLE', 'DROP INDEX',
          'COUNT', 'SUM', 'AVG', 'MAX', 'MIN', 'DISTINCT',
          'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
          'COALESCE', 'NULLIF', 'IFNULL', 'IF',
          'YEAR', 'MONTH', 'DAY', 'DATE', 'NOW', 'CURDATE',
          'CONCAT', 'SUBSTRING', 'LENGTH', 'LOWER', 'UPPER', 'TRIM',
          'CAST', 'CONVERT', 'OVER', 'PARTITION BY', 'ROW_NUMBER', 'RANK',
          'WITH', 'AS',
        ].map(kw),
      }
    },
  })
}

export default function SqlEditor({ value, onChange, readOnly = false, height = '100%', 'aria-label': ariaLabel }: Props) {
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null)

  const handleMount = useCallback<OnMount>((ed, monaco) => {
    editorRef.current = ed

    defineOptimixTheme(monaco)
    monaco.editor.setTheme('optimix-dark')
    registerSqlCompletions(monaco)

    // Format on mount if read-only (show formatted result)
    if (readOnly) {
      setTimeout(() => {
        ed.getAction('editor.action.formatDocument')?.run()
      }, 100)
    }
  }, [readOnly])

  return (
    <div style={{ height }} className="w-full" role="textbox" aria-label={ariaLabel}>
      <Editor
        height="100%"
        language="sql"
        theme="optimix-dark"
        value={value}
        onChange={(v) => onChange?.(v ?? '')}
        onMount={handleMount}
        options={{
          readOnly,
          fontSize:              13,
          fontFamily:            '"JetBrains Mono", "Fira Code", "Cascadia Code", monospace',
          fontLigatures:         true,
          lineHeight:            22,
          letterSpacing:         0.2,
          minimap:               { enabled: false },
          scrollBeyondLastLine:  false,
          wordWrap:              'on',
          tabSize:               2,
          insertSpaces:          true,
          padding:               { top: 16, bottom: 16 },
          renderLineHighlight:   'gutter',
          cursorBlinking:        'smooth',
          cursorSmoothCaretAnimation: 'on',
          smoothScrolling:       true,
          contextmenu:           !readOnly,
          overviewRulerLanes:    0,
          hideCursorInOverviewRuler: true,
          renderWhitespace:      'none',
          folding:               true,
          lineNumbers:           'on',
          lineDecorationsWidth:  0,
          lineNumbersMinChars:   3,
          scrollbar: {
            verticalScrollbarSize:   6,
            horizontalScrollbarSize: 6,
            useShadows:              false,
          },
          suggest: {
            showKeywords:  true,
            showSnippets:  true,
          },
          quickSuggestions: {
            other:    true,
            comments: false,
            strings:  false,
          },
        }}
      />
    </div>
  )
}

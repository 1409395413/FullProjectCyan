/**
 * 一次性脚本：把「跟随系统」的暗色 CSS 改造成「可被手动选择强制覆盖」的版本。
 *
 * 原理：
 *   原块   @media (prefers-color-scheme:dark) { .x { ... } }
 *   变成两块：
 *     A. @media (...) { :root:not([data-theme="light"]) .x { ... } }   ← 没手动选过才跟随系统
 *     B. :root[data-theme="dark"] .x { ... }                            ← 手动选了夜间就强制生效
 *
 * 为什么必须拆成两块而不是只加 B：
 *   只有 B 的话，用户手动选「白天」时，系统暗色媒体查询仍然命中，暗色会盖回来 —— 白天选不动。
 *   A 里的 :not([data-theme="light"]) 就是专门堵这个的。
 *
 * 用法：node gen-theme-css.js <css文件>
 */
const fs = require('fs')

const file = process.argv[2]
let src = fs.readFileSync(file, 'utf8')

const re = /@media\s*\(prefers-color-scheme:\s*dark\)\s*\{/g
const out = []
let cursor = 0
let match

function findBlockEnd(text, openIdx) {
  // openIdx 指向 '{'
  let depth = 0
  for (let i = openIdx; i < text.length; i++) {
    const ch = text[i]
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return i
    }
  }
  return -1
}

/** 把顶层规则逐条切开，返回 [{sel, body}] */
function splitRules(text) {
  const rules = []
  let i = 0
  while (i < text.length) {
    // 跳过空白与注释
    if (/\s/.test(text[i])) { i++; continue }
    if (text.startsWith('/*', i)) {
      const end = text.indexOf('*/', i + 2)
      i = end < 0 ? text.length : end + 2
      continue
    }
    const open = text.indexOf('{', i)
    if (open < 0) break
    const sel = text.slice(i, open).trim()
    const close = findBlockEnd(text, open)
    if (close < 0) break
    rules.push({ sel, body: text.slice(open + 1, close) })
    i = close + 1
  }
  return rules
}

let count = 0
while ((match = re.exec(src)) !== null) {
  const blockStart = match.index
  const openIdx = blockStart + match[0].length - 1
  const closeIdx = findBlockEnd(src, openIdx)
  if (closeIdx < 0) throw new Error('括号没闭合，中止')

  const inner = src.slice(openIdx + 1, closeIdx)
  const rules = splitRules(inner)

  const prefixA = ':root:not([data-theme="light"])'
  const prefixB = ':root[data-theme="dark"]'

  const render = (prefix) =>
    rules
      .map((r) => {
        // :root 自身不是后代，直接拼成 :root[data-theme="dark"] 而不是加空格
        const sel = r.sel
          .split(',')
          .map((s) => {
            const t = s.trim()
            if (!t) return t
            return t === ':root' ? prefix : `${prefix} ${t}`
          })
          .join(',\n  ')
        return `  ${sel} {${r.body}}`
      })
      .join('\n')

  const blockA = `@media (prefers-color-scheme: dark) {\n${render(prefixA)}\n}`
  const blockB = `${prefixB}-only {}\n${render(prefixB)}`.replace(`${prefixB}-only {}\n`, '')

  out.push({ blockStart, closeIdx, blockA, blockB, n: rules.length })
  count++
}

if (!count) {
  console.log('没找到暗色块，文件未改动：' + file)
  process.exit(0)
}

// 从后往前替换，避免前面的插入打乱后面的下标
let result = src
for (let i = out.length - 1; i >= 0; i--) {
  const o = out[i]
  const orig = result.slice(o.blockStart, o.closeIdx + 1)
  const header =
    '/* ---- 昼夜手动切换：以下两块由 gen-theme-css.js 从上方系统暗色块生成 ----\n' +
    '   第一块：没手动选过才跟随系统；第二块：手动选了夜间就强制生效。\n' +
    '   改动上方暗色样式后请重新生成本块，否则手动切换会漏掉新样式。 ---- */\n'
  const gen = header + o.blockA + '\n' + o.blockB
  result = result.slice(0, o.blockStart) + orig + '\n\n' + gen + result.slice(o.closeIdx + 1)
}

fs.writeFileSync(file, result)
out.forEach((o) => console.log(`${file}: 处理了 ${o.n} 条规则`))

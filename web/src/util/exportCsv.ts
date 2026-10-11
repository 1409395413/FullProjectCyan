/**
 * 把二维数组导出成表格文件（CSV）。
 * ---------------------------------------------------------------------------
 * 用 CSV 而不是 xlsx：Excel / WPS / Numbers / 飞书表格都能直接双击打开，
 * 不需要引入几百 KB 的表格库。
 *
 * 两个必须注意的细节：
 *   1. 文件头要写 ﻿（UTF-8 BOM）。没有它 Excel 会按本地编码解析，中文全是乱码。
 *   2. 字段里有逗号 / 引号 / 换行时，整个字段要用双引号包起来，内部引号翻倍。
 *      工单里的「项目简介」是用户自由填写的长文本，几乎必然会撞上这几种字符。
 */
export type CsvCell = string | number | null | undefined

export function downloadCsv(filename: string, rows: CsvCell[][]) {
  const esc = (v: CsvCell) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const body = rows.map((r) => r.map(esc).join(',')).join('\r\n')
  const blob = new Blob(['﻿' + body], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 文件名用的时间戳：20261008-2245 */
export function fileStamp(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`
}

/**
 * 时间戳 → 年月日时分秒。
 * ---------------------------------------------------------------------------
 * 后端存的是 UTC 的 Instant（如 2026-10-08T14:20:21.123Z），
 * 这里用浏览器本地时区渲染，国内访问者看到的就是北京时间。
 */
export function formatDateTime(s?: string | null): string {
  if (!s) return '—'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return String(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

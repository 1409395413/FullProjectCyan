/**
 * 模块字段说明表。
 * ---------------------------------------------------------------------------
 * 后台表单是<b>由这张表驱动</b>生成的：新增一种模块类型时，只要在这里补一条
 * 描述，后台立刻拥有对应的编辑界面，不需要再写 Vue 组件；
 * 前台遇到没描述的类型也不会白屏 —— ModuleSection 会退回 GenericModule 渲染。
 */

export type FieldType =
  | 'text'
  | 'textarea'
  | 'richtext'
  | 'select'
  | 'switch'
  | 'stringlist'
  | 'kv'
  | 'media'
  | 'medialist'
  | 'group'
  | 'list'
  /** 链接：可以从预置页面里挑一个，也可以自己填任意 URL / 锚点 */
  | 'link'

export interface FieldDef {
  key: string
  label: string
  type: FieldType
  hint?: string
  options?: { v: string; label: string }[]
  /** group / list 的子字段 */
  fields?: FieldDef[]
  /** list 中用于显示标题的字段 */
  titleKey?: string
}

export interface TypeDef {
  type: string
  label: string
  hint?: string
  fields: FieldDef[]
}

/* ------------------------------ 预置页面 ------------------------------ */
/**
 * 站点里已经存在的静态页面。
 * ---------------------------------------------------------------------------
 * 后台填链接时可以直接从这里挑一个，不用记住路径；
 * 这些页面放在 web/public/pages/ 下，构建时会原样拷进 dist，
 * 由 Nginx 直出，不经过前端路由。
 */
export const PRESET_PAGES: { v: string; label: string }[] = [
  { v: '/pages/wip.html', label: '预置页：功能开发中（请耐心等待）' },
  { v: '/pages/coming-soon.html', label: '预置页：近期准备中' }
]

/** 判断一个链接值是否是预置页；自定义链接返回 '__custom' */
export function presetKeyOf(value: string | undefined | null): string {
  const v = String(value || '').trim()
  return PRESET_PAGES.some((p) => p.v === v) ? v : '__custom'
}

/* ------------------------------ 公共字段 ------------------------------ */
const SIZE_OPTIONS = [
  { v: 'auto', label: '自动（推荐）' },
  { v: 'full', label: '通栏 12/12' },
  { v: 'xl', label: '宽 8/12' },
  { v: 'lg', label: '半栏 6/12' },
  { v: 'md', label: '窄 4/12' },
  { v: 'sm', label: '极窄 3/12' }
]

const COMMON_HEAD: FieldDef[] = [
  { key: 'eyebrow', label: '眉标题', type: 'text', hint: '模块上方的小字，例如 02 — Photography' },
  { key: 'title', label: '标题', type: 'richtext', hint: '可包含少量强调标签' },
  { key: 'lead', label: '导语', type: 'richtext' }
]

const VISIBILITY: FieldDef[] = [
  { key: 'visible', label: '在前台显示', type: 'switch' },
  { key: 'showNav', label: '加入导航', type: 'switch' },
  { key: 'navLabel', label: '导航文字', type: 'text' },
  { key: 'flat', label: '不用玻璃卡片（通体扁平）', type: 'switch' },
  { key: 'size', label: '占栏', type: 'select', options: SIZE_OPTIONS }
]

/* ------------------------------ 素材字段 ------------------------------ */
/** 素材条目的可编辑字段（MediaItemEditor 目前按此列表渲染） */
export const MEDIA_ITEM: FieldDef[] = [
  { key: 'kind', label: '类型', type: 'select', options: [
    { v: 'image', label: '图片' },
    { v: 'video', label: '视频' }
  ] },
  { key: 'title', label: '标题 / 占位文字', type: 'text' },
  { key: 'meta', label: '副标题', type: 'text', hint: '卡片上的第二行小字' },
  { key: 'badge', label: '左上角标记', type: 'text', hint: '如 4K · 60fps / Live' },
  { key: 'dur', label: '时长标记', type: 'text', hint: '如 02:14' },
  { key: 'hint', label: '空占位时的尺寸提示', type: 'text', hint: '只在没上传素材时显示' },
  {
    key: 'fit',
    label: '画框',
    type: 'select',
    hint: '竖屏与横屏混排时，画框策略决定它怎么被裁切',
    options: [
      { v: 'auto', label: '自动（按素材真实方向）' },
      { v: 'uniform', label: '统一画框（叠牌画廊推荐）' },
      { v: 'native', label: '原始比例（不裁切）' }
    ]
  },
  { key: 'w', label: '原始宽度 px', type: 'text', hint: '上传后自动填入，通常不用改' },
  { key: 'h', label: '原始高度 px', type: 'text', hint: '上传后自动填入，通常不用改' }
]

/* ------------------------------ 类型定义 ------------------------------ */
export const TYPE_DEFS: TypeDef[] = [
  {
    type: 'hero',
    label: '首屏横幅',
    hint: '左侧大标题 + 按钮，右侧 Showreel 视频，底部数据条',
    fields: [
      ...COMMON_HEAD,
      { key: 'chips', label: '标签', type: 'stringlist' },
      {
        key: 'actions',
        label: '按钮',
        type: 'list',
        titleKey: 'label',
        fields: [
          { key: 'label', label: '文字', type: 'text' },
          { key: 'href', label: '链接', type: 'text', hint: '站内锚点写 #photo 这种形式' },
          { key: 'kind', label: '样式', type: 'select', options: [
            { v: 'primary', label: '主按钮（蓝底）' },
            { v: 'ghost', label: '次按钮（玻璃）' }
          ] }
        ]
      },
      {
        key: 'stats',
        label: '数据条',
        type: 'list',
        titleKey: 'label',
        fields: [
          { key: 'value', label: '数值', type: 'text' },
          { key: 'label', label: '说明', type: 'text' }
        ]
      },
      {
        key: 'reel',
        label: 'Showreel',
        type: 'group',
        fields: [
          { key: 'bar', label: '顶部标题', type: 'text' },
          { key: 'media', label: '视频', type: 'media' }
        ]
      },
      ...VISIBILITY
    ]
  },
  {
    type: 'about',
    label: '个人简介',
    hint: '竖版形象照 + 履历段落 + 履历表 + 能力标签',
    fields: [
      ...COMMON_HEAD,
      { key: 'paragraphs', label: '正文段落', type: 'stringlist', hint: '一行一段' },
      { key: 'media', label: '形象照', type: 'media' },
      { key: 'facts', label: '履历表', type: 'kv' },
      { key: 'skills', label: '能力关键词', type: 'stringlist' },
      ...VISIBILITY
    ]
  },
  {
    type: 'stack',
    label: '作品陈列（叠牌）',
    hint: '卡牌叠加画廊：悬停哪张，哪张放大成主播放位',
    fields: [
      ...COMMON_HEAD,
      { key: 'media', label: '作品', type: 'medialist', hint: '建议 5 张以上，叠牌效果最明显' },
      {
        key: 'actions',
        label: '按钮',
        type: 'list',
        titleKey: 'label',
        fields: [
          { key: 'label', label: '文字', type: 'text' },
          { key: 'href', label: '链接', type: 'text' },
          { key: 'kind', label: '样式', type: 'select', options: [
            { v: 'primary', label: '主按钮' },
            { v: 'ghost', label: '次按钮' }
          ] }
        ]
      },
      ...VISIBILITY
    ]
  },
  {
    type: 'cases',
    label: '案例列表',
    hint: '图文左右分栏 + 参数表 + 标签',
    fields: [
      ...COMMON_HEAD,
      {
        key: 'cases',
        label: '案例',
        type: 'list',
        titleKey: 'title',
        fields: [
          { key: 'title', label: '标题', type: 'text' },
          { key: 'desc', label: '描述', type: 'textarea' },
          {
            key: 'mediaList',
            label: '素材组',
            type: 'medialist',
            hint: '可放多个视频和图片混排；第一条是封面，其余在下方悬浮缩略条里点选切换观看'
          },
          { key: 'specs', label: '参数表', type: 'kv' },
          { key: 'tags', label: '标签', type: 'stringlist' }
        ]
      },
      ...VISIBILITY
    ]
  },
  {
    type: 'quote',
    label: '金句通栏',
    hint: '居中大字，用来给两个板块之间换口气',
    fields: [
      { key: 'title', label: '主句', type: 'richtext' },
      { key: 'lead', label: '补充', type: 'richtext' },
      ...VISIBILITY
    ]
  },
  {
    type: 'contact',
    label: '联系方式',
    hint: '信息列表 + 询单表单（留言会存进数据库）',
    fields: [
      ...COMMON_HEAD,
      { key: 'rows', label: '联系信息', type: 'kv' },
      { key: 'projectTypes', label: '项目类型（下拉选项）', type: 'stringlist' },
      { key: 'formNote', label: '表单下方说明', type: 'text' },
      { key: 'submitLabel', label: '提交按钮文字', type: 'text' },
      ...VISIBILITY
    ]
  },
  {
    type: 'text',
    label: '纯文本段落',
    hint: '放宽内容的通用区块',
    fields: [
      ...COMMON_HEAD,
      { key: 'body', label: '正文', type: 'richtext' },
      ...VISIBILITY
    ]
  },
  {
    type: 'custom',
    label: '自定义内容',
    hint: '不限定结构：正文（富文本）+ 可选素材网格 + 可选参数表，编辑完提交即可',
    fields: [
      ...COMMON_HEAD,
      { key: 'body', label: '正文', type: 'richtext', hint: '支持段落、加粗、链接、列表；留空就是纯素材墙' },
      { key: 'media', label: '素材', type: 'medialist', hint: '可上传任意数量的图片/视频，横竖屏会自动适配' },
      {
        key: 'columns',
        label: '素材列数',
        type: 'select',
        options: [
          { v: '1', label: '1 列（大图）' },
          { v: '2', label: '2 列' },
          { v: '3', label: '3 列（紧凑）' }
        ]
      },
      { key: 'facts', label: '参数表', type: 'kv', hint: '可选，用来放规格、时间、预算之类' },
      {
        key: 'actions',
        label: '按钮',
        type: 'list',
        titleKey: 'label',
        fields: [
          { key: 'label', label: '文字', type: 'text' },
          { key: 'href', label: '链接', type: 'text' },
          { key: 'kind', label: '样式', type: 'select', options: [
            { v: 'primary', label: '主按钮' },
            { v: 'ghost', label: '次按钮' }
          ] }
        ]
      },
      ...VISIBILITY
    ]
  }
]

export function typeDef(type: string): TypeDef | undefined {
  return TYPE_DEFS.find((t) => t.type === type)
}

export const SITE_FIELDS: FieldDef[] = [
  { key: 'brandName', label: '站点名称', type: 'text' },
  { key: 'brandMark', label: '导航标志字', type: 'text', hint: '通常取姓氏首字母' },
  { key: 'footerNote', label: '页脚简介', type: 'richtext' },
  { key: 'copyright', label: '版权行', type: 'text' },
  {
    key: 'showBeian',
    label: '显示备案信息',
    type: 'switch',
    hint: '关掉后页脚不显示备案号；号码可以留着，想恢复时再打开即可'
  },
  {
    key: 'icpBeian',
    label: 'ICP 备案号',
    type: 'text',
    hint: '如「粤ICP备2026xxxxxx号」。留空则不显示。备案下来后务必填上并保存，管局会抽查页脚'
  },
  {
    key: 'policeBeian',
    label: '公安备案号',
    type: 'text',
    hint: '如「粤公网安备 44010602000123号」。留空则不显示。网站上线 30 日内需到 beian.mps.gov.cn 提交'
  },
  {
    key: 'footerCols',
    label: '页脚分栏',
    type: 'list',
    titleKey: 'title',
    fields: [
      { key: 'title', label: '分栏标题', type: 'text' },
      {
        key: 'links',
        label: '链接',
        type: 'list',
        titleKey: 'label',
        fields: [
          { key: 'label', label: '文字', type: 'text' },
          {
            key: 'href',
            label: '链接',
            type: 'link',
            hint: '可从预置页面里选，也可自己填 https:// 外链或 #锚点',
            options: PRESET_PAGES
          }
        ]
      }
    ]
  }
]

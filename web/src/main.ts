import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { installVideoGovernor } from './util/video'
import './assets/styles/main.css'
import './assets/styles/admin.css'

/**
 * 素材保护：前台的图片 / 视频禁掉右键菜单和拖拽另存。
 * ---------------------------------------------------------------------------
 * 说白了这只是「抬门槛」，不是防盗：浏览器能把视频播出来，就一定能拿到完整数据，
 * 开了 F12 的人几秒钟就能把原文件存走，前端拦多少层都没用。
 * 真想保版权，靠的是画面水印、法律，以及「只放低清版、原片不外露」。
 * 这里堵掉的只是右键另存 / 长按保存 / 拖到桌面这三条最省力的路。
 *
 * 后台 /admin 不拦 —— 自己人还要用右键取材素材地址、拖进来上传。
 */
function installMediaGuard() {
  const inAdmin = () => location.pathname.startsWith('/admin')
  const isMedia = (t: EventTarget | null) => {
    const el = t as HTMLElement | null
    return !!el && /^(IMG|VIDEO|CANVAS)$/.test(el.tagName)
  }
  document.addEventListener('contextmenu', (e) => {
    if (!inAdmin() && isMedia(e.target)) e.preventDefault()
  })
  document.addEventListener('dragstart', (e) => {
    if (!inAdmin() && isMedia(e.target)) e.preventDefault()
  })
}
installMediaGuard()

// 全局只保留一个视频在播（首屏 Showreel 例外），滚出视口自动关闭
installVideoGovernor()

createApp(App).use(router).mount('#app')

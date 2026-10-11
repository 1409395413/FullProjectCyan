/* =========================================================================
   ContentAPI — 内容读取层（前端与后端之间唯一的接口）
   -------------------------------------------------------------------------
   两种适配器，通过 window.API_MODE 或 localStorage 切换：

   1) 'local'（默认，开箱即用）
      读：localStorage['Cyan:content']  → 没有则用 DEFAULT_CONTENT
      写：localStorage['Cyan:content']
      后台编辑器 admin.html 就是写这里。
      素材上传：FileReader → dataURL（注意 localStorage 约 5MB 上限，
      正式环境请改用对象存储）。

   2) 'rest'（接真实后端）
      启动时 ContentAPI.useRest(baseUrl) 即可，前后端契约如下：
        GET    {base}/content           → 返回整份 content JSON
        PUT    {base}/content           → 提交整份 content JSON
        POST   {base}/media             → 上传素材，返回 { url }
        DELETE {base}/media?id=         → 删除素材
      只要后端满足这四个接口，页面零改动切换。
   ========================================================================= */
(function () {
  var KEY = 'Cyan:content';
  var MODE_KEY = 'Cyan:apiMode';
  var BASE_KEY = 'Cyan:apiBase';

  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  function normalize(data) {
    data = data || {};
    data.site = data.site || clone(window.DEFAULT_CONTENT.site);
    data.modules = Array.isArray(data.modules) ? data.modules : clone(window.DEFAULT_CONTENT.modules);
    data.modules.forEach(function (m, i) {
      if (!m.id) m.id = 'mod-' + Date.now() + '-' + i;
      m.media = Array.isArray(m.media) ? m.media : [];
      if (m.visible === undefined) m.visible = true;
      if (m.size === undefined) m.size = 'auto';
    });
    return data;
  }

  var ContentAPI = {
    mode: 'local',
    base: '',

    init: function () {
      try {
        this.mode = localStorage.getItem(MODE_KEY) || 'local';
        this.base = localStorage.getItem(BASE_KEY) || '';
      } catch (e) {
        this.mode = 'local';
      }
      return this;
    },

    /* 切到真实后端：ContentAPI.useRest('https://api.xxx.com') */
    useRest: function (base) {
      this.mode = 'rest';
      this.base = base || '';
      try {
        localStorage.setItem(MODE_KEY, 'rest');
        localStorage.setItem(BASE_KEY, this.base);
      } catch (e) {}
      return this;
    },

    useLocal: function () {
      this.mode = 'local';
      try { localStorage.setItem(MODE_KEY, 'local'); } catch (e) {}
      return this;
    },

    /* GET content */
    get: function () {
      var self = this;
      if (this.mode === 'rest') {
        return fetch(this.base + '/content', { headers: { Accept: 'application/json' } })
          .then(function (r) {
            if (!r.ok) throw new Error('GET /content ' + r.status);
            return r.json();
          })
          .then(normalize)
          .catch(function (e) {
            console.warn('[ContentAPI] REST 读取失败，回退本地数据：', e.message);
            return self._localGet();
          });
      }
      return Promise.resolve(this._localGet());
    },

    /* PUT content */
    save: function (data) {
      data = normalize(clone(data));
      if (this.mode === 'rest') {
        return fetch(this.base + '/content', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data)
        }).then(function (r) {
          if (!r.ok) throw new Error('PUT /content ' + r.status);
          return r.json().catch(function () { return data; });
        });
      }
      try { localStorage.setItem(KEY, JSON.stringify(data)); }
      catch (e) { alert('本地存储写入失败（可能超出 5MB 限额）：\n' + e.message); }
      return Promise.resolve(data);
    },

    /* 上传素材 → 返回可访问 url */
    uploadMedia: function (file) {
      var self = this;
      if (this.mode === 'rest') {
        var fd = new FormData();
        fd.append('file', file);
        return fetch(this.base + '/media', { method: 'POST', body: fd })
          .then(function (r) {
            if (!r.ok) throw new Error('POST /media ' + r.status);
            return r.json();
          })
          .then(function (j) { return j.url; })
          .catch(function (e) {
            console.warn('[ContentAPI] 上传失败，转本地 dataURL：', e.message);
            return self._toDataUrl(file);
          });
      }
      return this._toDataUrl(file);
    },

    reset: function () {
      try { localStorage.removeItem(KEY); } catch (e) {}
      return Promise.resolve(this._localGet());
    },

    exportJSON: function () { return JSON.stringify(this._localGet(), null, 2); },

    importJSON: function (text) {
      var data = JSON.parse(text);
      return this.save(data);
    },

    /* ---- internal ---- */
    _localGet: function () {
      try {
        var raw = localStorage.getItem(KEY);
        if (raw) return normalize(JSON.parse(raw));
      } catch (e) {
        console.warn('[ContentAPI] 本地数据解析失败，使用默认内容', e);
      }
      return normalize(clone(window.DEFAULT_CONTENT));
    },

    _toDataUrl: function (file) {
      return new Promise(function (resolve, reject) {
        var fr = new FileReader();
        fr.onload = function () { resolve(fr.result); };
        fr.onerror = reject;
        fr.readAsDataURL(file);
      });
    }
  };

  window.ContentAPI = ContentAPI.init();
})();

(() => {
  const storageKey = 'muyun-file-test-console.files';
  const state = { files: loadFiles(), logs: [] };
  const $ = (selector) => document.querySelector(selector);
  const prefix = location.pathname.replace(/\/test-console\/?$/, '');

  function apiPath(path) { return `${prefix}${path}`; }
  function headers() {
    const result = {};
    const tenantId = $('#tenant-id').value.trim();
    const userId = $('#user-id').value.trim();
    const requestId = $('#request-id').value.trim();
    if (!tenantId || !userId) throw new Error('请先填写 Tenant ID 和 User ID。');
    result['X-Tenant-Id'] = tenantId;
    result['X-User-Id'] = userId;
    if (requestId) result['X-Request-Id'] = requestId;
    return result;
  }
  function log(method, path, response, body) {
    state.logs.unshift(`${new Date().toLocaleTimeString()}  ${method} ${path}\n${response.status} ${response.statusText}\n${body || ''}`);
    $('#request-log').textContent = state.logs.join('\n\n');
  }
  async function request(method, path, options = {}) {
    const response = await fetch(apiPath(path), { method, headers: { ...headers(), ...options.headers }, body: options.body });
    const text = await response.text();
    log(method, path, response, text);
    let payload;
    try { payload = text ? JSON.parse(text) : null; } catch { payload = text; }
    if (!response.ok) throw new Error(payload?.message || `${response.status} ${response.statusText}`);
    return payload?.data;
  }
  async function downloadTrusted(fileId) {
    const path = `/api/v1/files/${encodeURIComponent(fileId)}/download`;
    const response = await fetch(apiPath(path), { headers: headers() });
    log('GET', path, response, response.ok ? '[binary response]' : await response.text());
    if (!response.ok) throw new Error(`下载失败：${response.status} ${response.statusText}`);
    const disposition = response.headers.get('Content-Disposition') || '';
    const match = disposition.match(/filename\*=UTF-8''([^;]+)/);
    const filename = match ? decodeURIComponent(match[1]) : fileId;
    const blobUrl = URL.createObjectURL(await response.blob());
    const link = document.createElement('a'); link.href = blobUrl; link.download = filename; link.click();
    URL.revokeObjectURL(blobUrl);
  }
  async function createDownloadLink(fileId, expiresInSeconds) {
    return request('POST', `/api/v1/files/${encodeURIComponent(fileId)}/download-link`, {
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(expiresInSeconds ? { expiresInSeconds } : {})
    });
  }
  function saveFiles() { sessionStorage.setItem(storageKey, JSON.stringify(state.files)); }
  function loadFiles() { try { return JSON.parse(sessionStorage.getItem(storageKey)) || []; } catch { return []; } }
  function escape(value) { const node = document.createElement('span'); node.textContent = value; return node.innerHTML; }
  function bytes(value) { return new Intl.NumberFormat('zh-CN', { style: 'unit', unit: 'byte', unitDisplay: 'short', notation: value > 1024 ? 'compact' : 'standard' }).format(value); }
  function renderFiles() {
    const list = $('#file-list'); const empty = $('#files-empty');
    empty.hidden = state.files.length > 0;
    list.innerHTML = state.files.map((file) => `
      <article class="file" data-file-id="${escape(file.id)}">
        <div class="file-head"><div><div class="file-name">${escape(file.originalFilename)}</div><div class="file-meta">${escape(file.mimeType)} · ${bytes(file.sizeBytes)} · ${escape(file.id)}</div></div></div>
        <div class="actions"><button class="download-button" type="button">下载</button><button class="preview-button secondary" type="button">预览</button><button class="share-button" type="button">生成限时下载链接</button><button class="delete-button secondary" type="button">删除</button></div>
        <div class="share" hidden><input readonly aria-label="限时下载链接" /><button class="copy-button" type="button">复制链接</button></div><div class="expires" hidden></div>
      </article>`).join('');
  }
  async function health() {
    const el = $('#health-status');
    try { const response = await fetch(apiPath('/q/health/ready')); const body = await response.text(); log('GET', '/q/health/ready', response, body); if (!response.ok) throw new Error(); el.textContent = '服务已就绪'; el.className = 'status ok'; }
    catch { el.textContent = '服务未就绪'; el.className = 'status error'; }
  }
  $('#upload-form').addEventListener('submit', async (event) => {
    event.preventDefault(); const submit = event.submitter; submit.disabled = true;
    try { const form = new FormData(); [...$('#files').files].forEach((file) => form.append('files', file)); if ($('#remark').value.trim()) form.append('remark', $('#remark').value.trim()); form.append('temporary', String($('#temporary').checked)); const data = await request('POST', '/api/v1/files', { body: form }); state.files.unshift(...data.items); saveFiles(); renderFiles(); event.target.reset(); }
    catch (error) { alert(error.message); } finally { submit.disabled = false; }
  });
  $('#file-list').addEventListener('click', async (event) => {
    const card = event.target.closest('[data-file-id]'); if (!card) return; const fileId = card.dataset.fileId;
    try {
      if (event.target.closest('.download-button')) await downloadTrusted(fileId);
      if (event.target.closest('.preview-button')) { const data = await createDownloadLink(fileId); const accessToken = new URL(data.downloadPath, location.origin).searchParams.get('access_token'); window.open(apiPath(`/view/public/files/${encodeURIComponent(fileId)}?access_token=${encodeURIComponent(accessToken)}`), '_blank', 'noopener'); }
      if (event.target.closest('.share-button')) { const ttl = prompt('有效期（秒，留空为默认 15 分钟）：', '900'); if (ttl === null) return; const data = await createDownloadLink(fileId, ttl.trim() ? Number(ttl) : null); const url = new URL(apiPath(data.downloadPath), location.origin).href; card.querySelector('.share input').value = url; card.querySelector('.share').hidden = false; const expires = card.querySelector('.expires'); expires.textContent = `有效至：${new Date(data.expiresAt).toLocaleString()}`; expires.hidden = false; }
      if (event.target.closest('.copy-button')) { await navigator.clipboard.writeText(card.querySelector('.share input').value); event.target.textContent = '已复制'; setTimeout(() => { event.target.textContent = '复制链接'; }, 1200); }
      if (event.target.closest('.delete-button')) { if (!confirm('确认软删除该文件？')) return; await request('DELETE', `/api/v1/files/${encodeURIComponent(fileId)}`); state.files = state.files.filter((item) => item.id !== fileId); saveFiles(); renderFiles(); }
    } catch (error) { alert(error.message); }
  });
  $('#clear-files').addEventListener('click', () => { state.files = []; saveFiles(); renderFiles(); });
  $('#clear-log').addEventListener('click', () => { state.logs = []; $('#request-log').textContent = '等待操作…'; });
  renderFiles(); health();
})();

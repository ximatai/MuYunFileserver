import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import './styles.css';

GlobalWorkerOptions.workerSrc = workerUrl;

const app = document.querySelector('#app');

const route = parseRoute(window.location);
if (!route) {
  renderError('地址无效', '当前 viewer 地址无法识别，请检查文件链接是否完整。');
} else {
  bootstrap(route).catch((error) => {
    console.error(error);
    renderError('加载失败', normalizeError(error));
  });
}

async function bootstrap(routeConfig) {
  renderLoading();
  const descriptor = await fetchDescriptor(routeConfig);
  renderShell(descriptor, routeConfig);
}

function parseRoute(location) {
  const pathname = location.pathname.replace(/\/+$/, '');
  const internalMatch = pathname.match(/^\/view\/files\/([^/]+)$/);
  if (internalMatch) {
    return {
      mode: 'internal',
      fileId: decodeURIComponent(internalMatch[1]),
      accessToken: null
    };
  }
  const publicMatch = pathname.match(/^\/view\/public\/files\/([^/]+)$/);
  if (publicMatch) {
    return {
      mode: 'public',
      fileId: decodeURIComponent(publicMatch[1]),
      accessToken: new URLSearchParams(location.search).get('access_token')
    };
  }
  return null;
}

async function fetchDescriptor(routeConfig) {
  const descriptorUrl = routeConfig.mode === 'public'
    ? `/api/v1/public/files/${encodeURIComponent(routeConfig.fileId)}/view?access_token=${encodeURIComponent(routeConfig.accessToken || '')}`
    : `/api/v1/files/${encodeURIComponent(routeConfig.fileId)}/view`;

  const response = await fetch(descriptorUrl, {
    credentials: 'same-origin'
  });

  if (!response.ok) {
    throw new Error(await describeError(response));
  }

  const payload = await response.json();
  return payload.data;
}

function renderLoading() {
  app.innerHTML = `
    <main class="viewer-shell">
      <section class="empty-state">
        <h2>正在准备文件查看器</h2>
        <p>正在向文件服务请求展示描述和可展示内容，请稍候。</p>
      </section>
    </main>
  `;
}

function renderShell(descriptor, routeConfig) {
  app.innerHTML = `
    <main class="viewer-shell">
      <header class="viewer-header">
        <div class="viewer-title">
          <div class="viewer-kicker">MuYun File Viewer</div>
          <h1>${escapeHtml(descriptor.displayName)}</h1>
          <div class="viewer-meta">${escapeHtml(descriptor.sourceMimeType)} · ${escapeHtml(descriptor.viewerType)}</div>
        </div>
        <div class="viewer-actions">
          <a class="button" href="${descriptor.downloadUrl}" target="_blank" rel="noopener">下载原文件</a>
        </div>
      </header>
      <section class="viewer-main" id="viewer-main"></section>
    </main>
  `;

  const target = document.querySelector('#viewer-main');
  const renderers = {
    pdf: renderPdfViewer,
    fallback: renderFallback
  };
  const renderer = renderers[descriptor.viewerType] || renderFallback;
  renderer(target, descriptor, routeConfig);
}

async function renderPdfViewer(target, descriptor) {
  target.innerHTML = `
    <div class="pdf-viewer">
      <div class="panel pdf-toolbar">
        <button class="ghost-button" data-action="prev">上一页</button>
        <div class="pdf-counter" data-role="counter">- / -</div>
        <button class="ghost-button" data-action="next">下一页</button>
        <button class="ghost-button" data-action="zoom-out">缩小</button>
        <button class="ghost-button" data-action="zoom-in">放大</button>
        <button class="ghost-button" data-action="fit">适应宽度</button>
        <div class="status-note" data-role="status">正在加载 PDF…</div>
      </div>
      <div class="panel pdf-stage">
        <div class="pdf-stage-inner" data-role="stage">
          <canvas class="pdf-canvas" data-role="canvas"></canvas>
        </div>
      </div>
    </div>
  `;

  const canvas = target.querySelector('[data-role="canvas"]');
  const stage = target.querySelector('[data-role="stage"]');
  const counter = target.querySelector('[data-role="counter"]');
  const status = target.querySelector('[data-role="status"]');

  const loadingTask = getDocument({
    url: descriptor.contentUrl,
    withCredentials: true
  });
  const pdf = await loadingTask.promise;
  const state = {
    pdf,
    pageNumber: 1,
    scale: 1,
    fitWidth: true
  };

  async function draw() {
    status.textContent = '正在渲染页面…';
    status.classList.remove('error');
    const page = await state.pdf.getPage(state.pageNumber);
    const viewport = page.getViewport({ scale: 1 });
    if (state.fitWidth) {
      const availableWidth = Math.max(stage.clientWidth - 48, 320);
      state.scale = availableWidth / viewport.width;
    }
    const scaledViewport = page.getViewport({ scale: state.scale });
    const context = canvas.getContext('2d');
    canvas.width = Math.floor(scaledViewport.width);
    canvas.height = Math.floor(scaledViewport.height);
    canvas.style.width = `${Math.floor(scaledViewport.width)}px`;
    canvas.style.height = `${Math.floor(scaledViewport.height)}px`;
    await page.render({
      canvasContext: context,
      viewport: scaledViewport
    }).promise;
    counter.textContent = `${state.pageNumber} / ${state.pdf.numPages}`;
    status.textContent = `缩放 ${Math.round(state.scale * 100)}%`;
  }

  target.addEventListener('click', async (event) => {
    const action = event.target.dataset.action;
    if (!action) {
      return;
    }
    if (action === 'prev' && state.pageNumber > 1) {
      state.pageNumber -= 1;
      await draw();
    } else if (action === 'next' && state.pageNumber < state.pdf.numPages) {
      state.pageNumber += 1;
      await draw();
    } else if (action === 'zoom-in') {
      state.fitWidth = false;
      state.scale = Math.min(state.scale + 0.1, 3);
      await draw();
    } else if (action === 'zoom-out') {
      state.fitWidth = false;
      state.scale = Math.max(state.scale - 0.1, 0.4);
      await draw();
    } else if (action === 'fit') {
      state.fitWidth = true;
      await draw();
    }
  });

  window.addEventListener('resize', debounce(async () => {
    if (state.fitWidth) {
      await draw();
    }
  }, 120));

  await draw();
}

function renderFallback(target, descriptor) {
  target.innerHTML = `
    <section class="empty-state">
      <h2>当前文件暂不支持在线查看</h2>
      <p>文件服务已识别该文件，但当前 viewer 一期只正式支持 PDF 与 Office 转 PDF 展示。你仍然可以直接下载原文件。</p>
      <div class="empty-state-actions">
        <a class="button" href="${descriptor.downloadUrl}" target="_blank" rel="noopener">下载原文件</a>
      </div>
    </section>
  `;
}

function renderError(title, message) {
  app.innerHTML = `
    <main class="viewer-shell">
      <section class="empty-state">
        <h2>${escapeHtml(title)}</h2>
        <p>${escapeHtml(message)}</p>
      </section>
    </main>
  `;
}

function normalizeError(error) {
  const message = error instanceof Error ? error.message : String(error);
  if (message.includes('401')) {
    return '当前请求缺少有效身份或 token，无法打开文件查看器。';
  }
  if (message.includes('403')) {
    return '当前身份无权访问该文件。';
  }
  if (message.includes('404')) {
    return '文件或展示描述不存在。';
  }
  return message || '文件查看器加载失败。';
}

async function describeError(response) {
  let suffix = '';
  try {
    const payload = await response.json();
    if (payload && payload.message) {
      suffix = `: ${payload.message}`;
    }
  } catch (_ignored) {
    // ignore parse error
  }
  return `${response.status}${suffix}`;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function debounce(fn, wait) {
  let timerId;
  return (...args) => {
    window.clearTimeout(timerId);
    timerId = window.setTimeout(() => {
      fn(...args);
    }, wait);
  };
}

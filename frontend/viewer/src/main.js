import './styles.css';
import pdfjsVersionConfig from '../pdfjs-version.json';

const app = document.querySelector('#app');
const PDFJS_VERSION = pdfjsVersionConfig.version;

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
  const internalMatch = pathname.match(/^(.*)\/view\/files\/([^/]+)$/);
  if (internalMatch) {
    return {
      mode: 'internal',
      pathPrefix: internalMatch[1] || '',
      fileId: decodeURIComponent(internalMatch[2]),
      accessToken: null,
      viewerBasePath: `${internalMatch[1] || ''}/viewer`
    };
  }
  const publicMatch = pathname.match(/^(.*)\/view\/public\/files\/([^/]+)$/);
  if (publicMatch) {
    return {
      mode: 'public',
      pathPrefix: publicMatch[1] || '',
      fileId: decodeURIComponent(publicMatch[2]),
      accessToken: new URLSearchParams(location.search).get('access_token'),
      viewerBasePath: `${publicMatch[1] || ''}/viewer`
    };
  }
  return null;
}

async function fetchDescriptor(routeConfig) {
  const descriptorUrl = routeConfig.mode === 'public'
    ? `${routeConfig.pathPrefix}/api/v1/public/files/${encodeURIComponent(routeConfig.fileId)}/view?access_token=${encodeURIComponent(routeConfig.accessToken || '')}`
    : `${routeConfig.pathPrefix}/api/v1/files/${encodeURIComponent(routeConfig.fileId)}/view`;

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

function renderPdfViewer(target, descriptor, routeConfig) {
  const viewerUrl = `${routeConfig.viewerBasePath}/pdfjs/web/viewer.html?v=${encodeURIComponent(PDFJS_VERSION)}&file=${encodeURIComponent(descriptor.contentUrl)}`;
  target.innerHTML = `
    <div class="pdf-viewer-frame-shell">
      <iframe
        class="pdf-viewer-frame"
        title="PDF Viewer"
        src="${viewerUrl}"
        loading="eager"
        referrerpolicy="same-origin"
      ></iframe>
    </div>
  `;
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

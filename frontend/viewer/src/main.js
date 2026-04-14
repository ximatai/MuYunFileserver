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
  const descriptor = normalizeDescriptorUrls(await fetchDescriptor(routeConfig), routeConfig);
  await renderShell(descriptor, routeConfig);
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

async function renderShell(descriptor, routeConfig) {
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
    image: renderImageViewer,
    video: renderVideoViewer,
    audio: renderAudioViewer,
    text: renderTextViewer,
    fallback: renderFallback
  };
  const renderer = renderers[descriptor.viewerType] || renderFallback;
  await renderer(target, descriptor, routeConfig);
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

function renderImageViewer(target, descriptor) {
  target.innerHTML = `
    <section class="image-viewer-shell panel">
      <div class="image-viewer-toolbar">
        <div class="image-viewer-toolbar-group">
          <button class="button button-secondary" type="button" data-action="fit">适配窗口</button>
          <button class="button button-secondary" type="button" data-action="actual">原始尺寸</button>
        </div>
        <div class="image-viewer-toolbar-group">
          <button class="button button-secondary" type="button" data-action="zoom-out">缩小</button>
          <span class="image-viewer-scale" data-role="scale-label">适配窗口</span>
          <button class="button button-secondary" type="button" data-action="zoom-in">放大</button>
        </div>
      </div>
      <div class="image-viewer-stage" data-role="stage">
        <div class="image-viewer-canvas is-fit" data-role="canvas">
          <img
            class="image-viewer-image"
            data-role="image"
            alt="${escapeHtml(descriptor.displayName)}"
            src="${descriptor.contentUrl}"
            loading="eager"
          />
        </div>
      </div>
    </section>
  `;

  const stage = target.querySelector('[data-role="stage"]');
  const canvas = target.querySelector('[data-role="canvas"]');
  const image = target.querySelector('[data-role="image"]');
  const scaleLabel = target.querySelector('[data-role="scale-label"]');
  const state = {
    fit: true,
    scale: 1
  };

  const applyState = () => {
    if (state.fit) {
      canvas.classList.add('is-fit');
      image.style.removeProperty('--image-scale');
      scaleLabel.textContent = '适配窗口';
      return;
    }
    canvas.classList.remove('is-fit');
    image.style.setProperty('--image-scale', String(state.scale));
    scaleLabel.textContent = `${Math.round(state.scale * 100)}%`;
  };

  target.querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', () => {
      const action = button.getAttribute('data-action');
      if (action === 'fit') {
        state.fit = true;
      } else if (action === 'actual') {
        state.fit = false;
        state.scale = 1;
      } else if (action === 'zoom-in') {
        state.fit = false;
        state.scale = Math.min(4, Number((state.scale + 0.25).toFixed(2)));
      } else if (action === 'zoom-out') {
        state.fit = false;
        state.scale = Math.max(0.25, Number((state.scale - 0.25).toFixed(2)));
      }
      applyState();
    });
  });

  image.addEventListener('load', () => {
    stage.classList.add('is-ready');
    applyState();
  });

  image.addEventListener('error', () => {
    target.innerHTML = `
      <section class="empty-state">
        <h2>图片加载失败</h2>
        <p>文件服务已经返回图片查看地址，但浏览器未能成功加载当前图片内容。你仍然可以直接下载原文件。</p>
        <div class="empty-state-actions">
          <a class="button" href="${descriptor.downloadUrl}" target="_blank" rel="noopener">下载原文件</a>
        </div>
      </section>
    `;
  });
}

function renderVideoViewer(target, descriptor) {
  renderMediaViewer(target, descriptor, {
    title: '在线视频查看',
    tagName: 'video',
    attributes: 'controls playsinline preload="metadata"'
  });
}

function renderAudioViewer(target, descriptor) {
  renderMediaViewer(target, descriptor, {
    title: '在线音频播放',
    tagName: 'audio',
    attributes: 'controls preload="metadata"'
  });
}

function renderMediaViewer(target, descriptor, options) {
  target.innerHTML = `
    <section class="media-viewer-shell panel">
      <div class="media-viewer-toolbar">
        <div class="media-viewer-toolbar-title">${escapeHtml(options.title)}</div>
        <div class="media-viewer-toolbar-meta">${escapeHtml(descriptor.contentMimeType)}</div>
      </div>
      <div class="media-viewer-stage">
        <${options.tagName}
          class="media-viewer-player"
          ${options.attributes}
          src="${descriptor.contentUrl}"
        ></${options.tagName}>
      </div>
    </section>
  `;

  const player = target.querySelector('.media-viewer-player');
  player.addEventListener('error', () => {
    target.innerHTML = `
      <section class="empty-state">
        <h2>媒体加载失败</h2>
        <p>文件服务已经返回媒体查看地址，但浏览器未能成功播放当前内容。你仍然可以直接下载原文件。</p>
        <div class="empty-state-actions">
          <a class="button" href="${descriptor.downloadUrl}" target="_blank" rel="noopener">下载原文件</a>
        </div>
      </section>
    `;
  });
}

async function renderTextViewer(target, descriptor) {
  target.innerHTML = `
    <section class="text-viewer-shell panel">
      <div class="text-viewer-toolbar">
        <div class="text-viewer-toolbar-title">纯文本查看</div>
        <div class="text-viewer-toolbar-meta">${escapeHtml(descriptor.contentMimeType)}</div>
      </div>
      <div class="text-viewer-stage">
        <div class="text-viewer-loading">正在加载文本内容...</div>
      </div>
    </section>
  `;

  try {
    const response = await fetch(descriptor.contentUrl, { credentials: 'same-origin' });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const text = await response.text();
    target.innerHTML = `
      <section class="text-viewer-shell panel">
        <div class="text-viewer-toolbar">
          <div class="text-viewer-toolbar-title">纯文本查看</div>
          <div class="text-viewer-toolbar-meta">${escapeHtml(descriptor.contentMimeType)}</div>
        </div>
        <div class="text-viewer-stage">
          <pre class="text-viewer-content">${escapeHtml(text)}</pre>
        </div>
      </section>
    `;
  } catch (error) {
    target.innerHTML = `
      <section class="empty-state">
        <h2>文本加载失败</h2>
        <p>${escapeHtml(normalizeTextError(error))}</p>
        <div class="empty-state-actions">
          <a class="button" href="${descriptor.downloadUrl}" target="_blank" rel="noopener">下载原文件</a>
        </div>
      </section>
    `;
  }
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

function normalizeDescriptorUrls(descriptor, routeConfig) {
  return {
    ...descriptor,
    contentUrl: normalizeServiceUrl(descriptor.contentUrl, routeConfig.pathPrefix),
    downloadUrl: normalizeServiceUrl(descriptor.downloadUrl, routeConfig.pathPrefix)
  };
}

function normalizeServiceUrl(url, pathPrefix) {
  if (!url) {
    return url;
  }
  if (/^https?:\/\//i.test(url)) {
    return url;
  }
  if (!url.startsWith('/')) {
    return url;
  }
  if (!pathPrefix) {
    return url;
  }
  if (url.startsWith(`${pathPrefix}/`)) {
    return url;
  }
  return `${pathPrefix}${url}`;
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

function normalizeTextError(error) {
  const message = error instanceof Error ? error.message : String(error);
  if (message.includes('413')) {
    return '当前文本文件过大，首版 viewer 不做分页或流式加载，建议直接下载查看。';
  }
  if (message.includes('422')) {
    return '当前文本文件不是可安全内联展示的 UTF-8 内容，建议直接下载查看。';
  }
  return normalizeError(error);
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

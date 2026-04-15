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
          <h1>${escapeHtml(descriptor.displayName)}</h1>
          <div class="viewer-meta">${escapeHtml(formatBytes(descriptor.sizeBytes))}</div>
        </div>
        <div class="viewer-actions">
          <a class="button" href="${descriptor.downloadUrl}" target="_blank" rel="noopener">下载</a>
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
        <div class="image-viewer-canvas" data-role="canvas">
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
    scale: 1,
    fitScale: 1,
    minScale: 0.25,
    maxScale: 6,
    tx: 0,
    ty: 0,
    naturalWidth: 0,
    naturalHeight: 0,
    isDragging: false,
    dragStartX: 0,
    dragStartY: 0,
    dragOriginX: 0,
    dragOriginY: 0
  };

  const updateScaleLabel = () => {
    scaleLabel.textContent = `${Math.round(state.scale * 100)}%`;
  };

  const getStageMetrics = () => {
    const rect = stage.getBoundingClientRect();
    const styles = window.getComputedStyle(stage);
    const paddingLeft = parseFloat(styles.paddingLeft) || 0;
    const paddingRight = parseFloat(styles.paddingRight) || 0;
    const paddingTop = parseFloat(styles.paddingTop) || 0;
    const paddingBottom = parseFloat(styles.paddingBottom) || 0;
    return {
      rect,
      paddingLeft,
      paddingRight,
      paddingTop,
      paddingBottom,
      width: Math.max(1, Math.min(rect.width, window.innerWidth - rect.left) - paddingLeft - paddingRight),
      height: Math.max(1, Math.min(rect.height, window.innerHeight - rect.top) - paddingTop - paddingBottom)
    };
  };

  const constrainTranslation = () => {
    const { width: stageWidth, height: stageHeight } = getStageMetrics();
    const scaledWidth = state.naturalWidth * state.scale;
    const scaledHeight = state.naturalHeight * state.scale;

    if (scaledWidth <= stageWidth) {
      state.tx = 0;
    } else {
      state.tx = Math.min(0, Math.max(stageWidth - scaledWidth, state.tx));
    }

    if (scaledHeight <= stageHeight) {
      state.ty = 0;
    } else {
      state.ty = Math.min(0, Math.max(stageHeight - scaledHeight, state.ty));
    }
  };

  const applyTransform = () => {
    canvas.style.width = `${state.naturalWidth}px`;
    canvas.style.height = `${state.naturalHeight}px`;
    image.style.transform = `translate(${state.tx}px, ${state.ty}px) scale(${state.scale})`;
    stage.classList.toggle('is-interactive', state.scale > state.fitScale + 0.001);
    updateScaleLabel();
  };

  const setScale = (nextScale, anchorX, anchorY) => {
    const metrics = getStageMetrics();
    const pointX = anchorX ?? metrics.rect.left + metrics.paddingLeft + metrics.width / 2;
    const pointY = anchorY ?? metrics.rect.top + metrics.paddingTop + metrics.height / 2;
    const localX = pointX - metrics.rect.left - metrics.paddingLeft;
    const localY = pointY - metrics.rect.top - metrics.paddingTop;
    const clampedScale = Math.min(state.maxScale, Math.max(state.minScale, nextScale));
    const contentX = (localX - state.tx) / state.scale;
    const contentY = (localY - state.ty) / state.scale;

    state.scale = clampedScale;
    state.tx = localX - contentX * state.scale;
    state.ty = localY - contentY * state.scale;
    constrainTranslation();
    applyTransform();
  };

  const resetToScale = (nextScale) => {
    state.scale = nextScale;
    state.tx = 0;
    state.ty = 0;
    constrainTranslation();
    applyTransform();
  };

  const zoomByStep = (step, anchorX, anchorY) => {
    const nextScale = Number((state.scale + step).toFixed(2));
    setScale(nextScale, anchorX, anchorY);
  };

  stage.addEventListener('wheel', (event) => {
    event.preventDefault();
    const delta = event.deltaY === 0 && event.deltaX ? event.deltaX : event.deltaY;
    const direction = delta < 0 ? 1 : -1;
    const ratio = Math.exp((direction * 0.16) / 3);
    setScale(state.scale * ratio, event.clientX, event.clientY);
  }, { passive: false });

  stage.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || state.scale <= state.fitScale + 0.001) {
      return;
    }
    state.isDragging = true;
    state.dragStartX = event.clientX;
    state.dragStartY = event.clientY;
    state.dragOriginX = state.tx;
    state.dragOriginY = state.ty;
    stage.classList.add('is-dragging');
    stage.setPointerCapture(event.pointerId);
  });

  stage.addEventListener('pointermove', (event) => {
    if (!state.isDragging) {
      return;
    }
    state.tx = state.dragOriginX + (event.clientX - state.dragStartX);
    state.ty = state.dragOriginY + (event.clientY - state.dragStartY);
    constrainTranslation();
    applyTransform();
  });

  const stopDragging = (event) => {
    if (!state.isDragging) {
      return;
    }
    state.isDragging = false;
    stage.classList.remove('is-dragging');
    if (event && stage.hasPointerCapture(event.pointerId)) {
      stage.releasePointerCapture(event.pointerId);
    }
  };

  stage.addEventListener('pointerup', stopDragging);
  stage.addEventListener('pointercancel', stopDragging);

  target.querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', () => {
      const action = button.getAttribute('data-action');
      if (action === 'fit') {
        resetToScale(state.fitScale);
      } else if (action === 'actual') {
        resetToScale(1);
      } else if (action === 'zoom-in') {
        zoomByStep(0.25);
      } else if (action === 'zoom-out') {
        zoomByStep(-0.25);
      }
    });
  });

  image.addEventListener('dblclick', (event) => {
    event.preventDefault();
    if (Math.abs(state.scale - state.fitScale) < 0.01) {
      setScale(1, event.clientX, event.clientY);
      return;
    }
    if (Math.abs(state.scale - 1) < 0.01) {
      setScale(Math.min(2, Math.max(1.25, state.fitScale * 1.5)), event.clientX, event.clientY);
      return;
    }
    resetToScale(state.fitScale);
  });

  image.addEventListener('load', () => {
    stage.classList.add('is-ready');
    state.naturalWidth = image.naturalWidth;
    state.naturalHeight = image.naturalHeight;
    const metrics = getStageMetrics();
    const availableWidth = metrics.width;
    const availableHeight = metrics.height;
    const fitScale = Math.min(
      availableWidth / image.naturalWidth,
      availableHeight / image.naturalHeight,
      1
    );
    state.fitScale = Number.isFinite(fitScale) && fitScale > 0 ? fitScale : 1;
    resetToScale(state.fitScale);
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

function formatBytes(sizeBytes) {
  const size = Number(sizeBytes);
  if (!Number.isFinite(size) || size < 0) {
    return '-';
  }
  if (size < 1024) {
    return `${size} B`;
  }

  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = size / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const digits = value >= 10 ? 1 : 2;
  const formatted = value.toFixed(digits).replace(/\.0+$|(\.\d*[1-9])0+$/u, '$1');
  return `${formatted} ${units[unitIndex]}`;
}

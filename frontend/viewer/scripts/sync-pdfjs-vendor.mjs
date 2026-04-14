import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const viewerRoot = path.resolve(__dirname, '..');
const versionConfigPath = path.join(viewerRoot, 'pdfjs-version.json');
const versionConfig = JSON.parse(fs.readFileSync(versionConfigPath, 'utf8'));
const pdfjsVersion = versionConfig.version;
const sourceDir = path.join(viewerRoot, 'vendor', 'pdfjs', pdfjsVersion);
const targetDir = path.join(viewerRoot, 'public', 'pdfjs');
const overridesDir = path.join(viewerRoot, 'pdfjs-overrides');

if (!fs.existsSync(sourceDir)) {
  throw new Error(`Missing vendored PDF.js assets: ${sourceDir}`);
}

fs.rmSync(targetDir, { recursive: true, force: true });
fs.mkdirSync(path.dirname(targetDir), { recursive: true });
fs.cpSync(sourceDir, targetDir, { recursive: true });
fs.writeFileSync(path.join(targetDir, 'VERSION.txt'), `${pdfjsVersion}\n`, 'utf8');

const overrideCssSource = path.join(overridesDir, 'muyun-overrides.css');
const overrideCssTarget = path.join(targetDir, 'web', 'muyun-overrides.css');
if (!fs.existsSync(overrideCssSource)) {
  throw new Error(`Missing PDF.js override stylesheet: ${overrideCssSource}`);
}
fs.copyFileSync(overrideCssSource, overrideCssTarget);

const viewerHtmlPath = path.join(targetDir, 'web', 'viewer.html');
const officialViewerHtml = fs.readFileSync(viewerHtmlPath, 'utf8');
const patchedViewerHtml = officialViewerHtml
  .replace('<title>PDF.js viewer</title>', '<title>MuYun PDF Viewer</title>')
  .replace(
    '<script src="../build/pdf.mjs" type="module"></script>',
    '<script src="../build/pdf.mjs" type="module"></script>\n\n    <link rel="icon" type="image/png" href="../../favicon.png" />'
  )
  .replace(
    '<link rel="stylesheet" href="viewer.css" />',
    '<link rel="stylesheet" href="viewer.css" />\n    <link rel="stylesheet" href="muyun-overrides.css" />'
  );

if (patchedViewerHtml === officialViewerHtml) {
  throw new Error(`Failed to apply MuYun PDF.js viewer patch for ${viewerHtmlPath}`);
}

fs.writeFileSync(viewerHtmlPath, patchedViewerHtml, 'utf8');

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

if (!fs.existsSync(sourceDir)) {
  throw new Error(`Missing vendored PDF.js assets: ${sourceDir}`);
}

fs.rmSync(targetDir, { recursive: true, force: true });
fs.mkdirSync(path.dirname(targetDir), { recursive: true });
fs.cpSync(sourceDir, targetDir, { recursive: true });
fs.writeFileSync(path.join(targetDir, 'VERSION.txt'), `${pdfjsVersion}\n`, 'utf8');

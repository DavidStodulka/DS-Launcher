/*
 * build-single.js — sestaví samostatný "artifact" parkoviste.html
 * z index.html + app.js + data.js + icon.svg (vše inline, bez externích souborů).
 * Spuštění:  node build-single.js
 */
const fs = require('fs');
const path = require('path');
const dir = __dirname;

let html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const data = fs.readFileSync(path.join(dir, 'data.js'), 'utf8');
const app = fs.readFileSync(path.join(dir, 'app.js'), 'utf8');
const icon = fs.readFileSync(path.join(dir, 'icon.svg'), 'utf8');

const iconData = 'data:image/svg+xml;base64,' + Buffer.from(icon).toString('base64');
const manifest = {
  name: 'Parkoviště — správa vozidel', short_name: 'Parkoviště',
  description: 'Správa parkování vozidel v řadách.',
  start_url: '.', scope: '.', display: 'standalone', orientation: 'portrait',
  background_color: '#0f1729', theme_color: '#0f1729',
  icons: [{ src: iconData, sizes: '512x512', type: 'image/svg+xml', purpose: 'any maskable' }]
};
const manData = 'data:application/manifest+json;base64,' + Buffer.from(JSON.stringify(manifest)).toString('base64');

html = html
  .replace('<link rel="manifest" href="manifest.webmanifest" />', '<link rel="manifest" href="' + manData + '" />')
  .replace('<link rel="apple-touch-icon" href="icon.svg" />', '<link rel="apple-touch-icon" href="' + iconData + '" />')
  .replace('<link rel="icon" href="icon.svg" type="image/svg+xml" />', '<link rel="icon" href="' + iconData + '" type="image/svg+xml" />')
  .replace('<script src="data.js"></script>', '<script>\n' + data + '\n</' + 'script>')
  .replace('<script src="app.js"></script>', '<script>\n' + app + '\n</' + 'script>')
  .replace(/  if \('serviceWorker' in navigator\) \{[\s\S]*?\}\n/,
           '  /* single-file build: service worker vynechán */\n');

fs.writeFileSync(path.join(dir, 'parkoviste.html'), html);
console.log('parkoviste.html sestaveno (' + (html.length / 1024).toFixed(1) + ' kB)');

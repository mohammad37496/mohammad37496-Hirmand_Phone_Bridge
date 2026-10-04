import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';

const HOST = process.env.HOST || '0.0.0.0';
const PORT = Number(process.env.PORT || 3000);
const TOKEN = process.env.DEVICE_SYNC_TOKEN || '';
const DATA_DIR = path.resolve(process.env.DATA_DIR || './data');
const MAX_BODY = 4 * 1024 * 1024;
const MAX_FILE = 8 * 1024 * 1024;
const FILE_DIR = path.join(DATA_DIR, 'files');

await fs.mkdir(DATA_DIR, { recursive: true });
await fs.mkdir(FILE_DIR, { recursive: true });

function authorized(req) {
  if (!TOKEN) return true;
  return req.headers.authorization === `Bearer ${TOKEN}`;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY) {
        reject(new Error('payload too large'));
        req.destroy();
      }
    });
    req.on('end', () => {
      try { resolve(JSON.parse(body)); } catch { reject(new Error('invalid json')); }
    });
    req.on('error', reject);
  });
}

function readBinary(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', chunk => {
      total += chunk.length;
      if (total > MAX_FILE) {
        reject(new Error('file too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function decodeFileName(value) {
  try {
    return Buffer.from(String(value || ''), 'base64').toString('utf8')
      .replace(/[\\/\x00-\x1f]+/g, '-').slice(-160) || 'file';
  } catch {
    return 'file';
  }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && (req.url === '/health' || req.url === '/api/device-sync/v1')) {
      if (!authorized(req)) {
        res.writeHead(401, {'content-type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'unauthorized'}));
      }
      res.writeHead(200, {'content-type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({
        ok:true,
        service:'hirmand-phone-bridge',
        protocol:'hirmand.phone-bridge.v1'
      }));
    }

    if (req.method === 'POST' && req.url === '/api/device-sync/v1/files') {
      if (!authorized(req)) {
        res.writeHead(401, {'content-type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'unauthorized'}));
      }

      const deviceId = String(req.headers['x-hirmand-device-id'] || '').trim().slice(0,120);
      const sha256 = String(req.headers['x-hirmand-file-sha256'] || '').trim().toLowerCase();
      const fileSize = Number(req.headers['x-hirmand-file-size'] || '');
      const mime = String(req.headers['x-hirmand-file-mime'] || 'application/octet-stream').slice(0,180);

      if (!deviceId || !/^[a-f0-9]{64}$/.test(sha256) || !Number.isInteger(fileSize) || fileSize < 1 || fileSize > MAX_FILE) {
        res.writeHead(400, {'content-type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'invalid file metadata'}));
      }

      const bytes = await readBinary(req);
      if (bytes.length !== fileSize) {
        res.writeHead(400, {'content-type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'size mismatch'}));
      }

      const crypto = await import('node:crypto');
      const actual = crypto.createHash('sha256').update(bytes).digest('hex');
      if (actual !== sha256) {
        res.writeHead(400, {'content-type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'hash mismatch'}));
      }

      const deviceDir = path.join(FILE_DIR, deviceId.replace(/[^a-zA-Z0-9_-]/g,'_'));
      await fs.mkdir(deviceDir, { recursive: true });
      const name = decodeFileName(req.headers['x-hirmand-file-name']);
      const filePath = path.join(deviceDir, `${sha256}-${name}`);

      let stored = false;
      try { await fs.access(filePath); }
      catch { await fs.writeFile(filePath, bytes); stored = true; }

      res.writeHead(201, {'content-type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({
        ok:true,
        stored,
        deduplicated:!stored,
        fileId:sha256,
        deviceId,
        name,
        sizeBytes:bytes.length,
        mimeType:mime,
        sha256
      }));
    }

    if (req.method !== 'POST' || req.url !== '/api/device-sync/v1') {
      res.writeHead(404);
      return res.end();
    }

    if (!authorized(req)) {
      res.writeHead(401, {'content-type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:false,error:'unauthorized'}));
    }

    const payload = await readJson(req);
    const stamp = new Date().toISOString().replace(/[:.]/g,'-');
    const device = String(payload?.device?.name || 'device')
      .replace(/[^\p{L}\p{N}_-]+/gu,'_').slice(0,80);
    const file = path.join(DATA_DIR, `${stamp}_${device}.json`);
    await fs.writeFile(file, JSON.stringify(payload, null, 2), 'utf8');

    res.writeHead(201, {'content-type':'application/json; charset=utf-8'});
    res.end(JSON.stringify({ok:true, stored:path.basename(file)}));
  } catch (error) {
    res.writeHead(
      error?.message === 'payload too large' || error?.message === 'file too large' ? 413 : 400,
      {'content-type':'application/json; charset=utf-8'}
    );
    res.end(JSON.stringify({ok:false,error:String(error?.message || error)}));
  }
});

server.listen(PORT, HOST, () => console.log(`Hirmand Phone Bridge listening on http://${HOST}:${PORT}`));

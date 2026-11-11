/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import express from 'express';
import path from 'path';
import { createServer as createViteServer } from 'vite';
import dotenv from 'dotenv';

dotenv.config();

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(express.json());

  // High-precision ping endpoint
  app.get('/api/ping', (req, res) => {
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
    res.json({
      status: 'pong',
      serverTime: Date.now(),
      service: 'WiFi Analyzer Pro Enterprise'
    });
  });

  // Real download speed test payload endpoint
  app.get('/api/speedtest', (req, res) => {
    const size = parseInt(req.query.bytes as string) || 1024 * 1024; // Default 1MB
    const clampedSize = Math.min(Math.max(size, 1024), 15 * 1024 * 1024); // max 15MB
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Content-Length', clampedSize.toString());
    const chunk = Buffer.alloc(clampedSize, 0x58); // fill with 'X'
    res.send(chunk);
  });

  // Serve with Vite in development or static in production
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[WiFi Analyzer Pro] Servidor activo en http://localhost:${PORT}`);
  });
}

startServer();

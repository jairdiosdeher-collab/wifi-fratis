export default function handler(req: any, res: any) {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
  res.status(200).json({
    status: 'pong',
    serverTime: Date.now(),
    service: 'WiFi Analyzer Pro Enterprise (Vercel Serverless)'
  });
}

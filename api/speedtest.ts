export default function handler(req: any, res: any) {
  const size = parseInt(req.query?.bytes as string) || 1024 * 1024; // Default 1MB
  const clampedSize = Math.min(Math.max(size, 1024), 8 * 1024 * 1024); // max 8MB on serverless
  res.setHeader('Content-Type', 'application/octet-stream');
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Content-Length', clampedSize.toString());
  const chunk = Buffer.alloc(clampedSize, 0x58); // fill with 'X'
  res.status(200).send(chunk);
}

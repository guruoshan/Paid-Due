const TOKEN_REGEX = /^[A-Za-z0-9_-]{32,128}$/;
const headers = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store, max-age=0",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
};
function page(message: string, status = 200) {
  return new Response(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Paid&amp;Due invitation</title><style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f5f6f8;color:#171717;font:16px system-ui,sans-serif}
main{width:min(440px,calc(100% - 40px));box-sizing:border-box;padding:28px;border-radius:18px;background:white;box-shadow:0 10px 30px #00000012;text-align:center}
a{display:block;padding:14px 18px;margin-top:20px;border-radius:12px;background:#171717;color:white;text-decoration:none;font-weight:650}
p{line-height:1.5;color:#555}
</style></head><body><main><h1>Paid&amp;Due</h1><p>${message}</p></main></body></html>`, { status, headers });
}
Deno.serve((request: Request) => {
  if (request.method !== "GET") return page("Open this invitation link in your browser.", 405);
  const token = new URL(request.url).searchParams.get("t") || "";
  if (!TOKEN_REGEX.test(token)) return page("This invitation link is invalid or incomplete.", 400);
  const appLink = `paiddue://invite?t=${encodeURIComponent(token)}`;
  const safeLink = appLink.replace(/&/g, "&amp;").replace(/"/g, "&quot;");
  const body = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Paid&amp;Due invitation</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f5f6f8;color:#171717;font:16px system-ui,sans-serif}main{width:min(440px,calc(100% - 40px));box-sizing:border-box;padding:28px;border-radius:18px;background:white;box-shadow:0 10px 30px #00000012;text-align:center}a{display:block;padding:14px 18px;margin-top:20px;border-radius:12px;background:#171717;color:white;text-decoration:none;font-weight:650}p{line-height:1.5;color:#555}</style></head><body><main><h1>Paid&amp;Due</h1><p>Your administrator invited you to join their business. Tap below to open Paid&amp;Due and set your six-digit PIN.</p><a href="${safeLink}">Open Paid&amp;Due</a><p>This invitation can be used once and expires after 48 hours.</p></main><script>window.location.replace(${JSON.stringify(appLink)});</script></body></html>`;
  return new Response(body, { status: 200, headers });
});
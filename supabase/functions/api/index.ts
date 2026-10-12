import {
  health, activateStaff, loginStaff, uploadFinancial, staffMetadata, downloadFinancial, syncCustomerDirectory, json,
} from "./handlers.ts";

// Supabase Function gateway normally forwards /api/<route> after /functions/v1/api.
// Both forms are accepted to preserve Android compatibility and local testing.

function publicInvitePage(request: Request): Response {
  if (request.method !== "GET") {
    return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { Allow: "GET" });
  }
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Paid&amp;Due Staff Invitation</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f7f7f7;color:#151515;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
main{width:min(430px,calc(100% - 40px));box-sizing:border-box;padding:32px;border-radius:18px;background:#fff;text-align:center;box-shadow:0 10px 30px rgba(0,0,0,.08)}
h1{margin:0 0 10px}p{line-height:1.55;color:#555}
button{width:100%;margin-top:18px;padding:15px 18px;border:0;border-radius:12px;background:#111;color:#fff;font-size:16px;font-weight:700;cursor:pointer}
button:disabled{opacity:.45;cursor:default}#message{min-height:24px;margin-top:16px}
</style>
</head>
<body><main>
<h1>Paid&amp;Due</h1>
<p>You've been invited to join a business on Paid&amp;Due.</p>
<button id="openButton" type="button">Open Paid&amp;Due</button>
<p id="message"></p>
</main>
<script>
const token=window.location.hash.replace(/^#/,"").trim();
const button=document.getElementById("openButton");
const message=document.getElementById("message");
if(!/^[A-Za-z0-9_-]{32,128}$/.test(token)){
 button.disabled=true;message.textContent="This invitation link is invalid.";
}else{
 button.addEventListener("click",()=>{
  window.location.href="paiddue://invite?t="+encodeURIComponent(token);
  window.setTimeout(()=>{message.textContent="If Paid&Due did not open, install the app and open this invitation again.";},1400);
 });
}
</script>
</body>
</html>`;
  return new Response(html, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
    },
  });
}

export async function handleRequest(request: Request): Promise<Response> {
  const rawPath = new URL(request.url).pathname;
  const path = rawPath.startsWith("/functions/v1/api/")
    ? rawPath.slice("/functions/v1/api".length)
    : rawPath;
  try {
    if (path === "/open/invite" || path === "/open/invite/") {
      return publicInvitePage(request);
    }
    if (path === "/api/health") {
      if (request.method !== "GET") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { Allow: "GET" });
      return health();
    }
    if (path === "/api/staff/activate") return await activateStaff(request);
    if (path === "/api/staff/login") return await loginStaff(request);
    if (path === "/api/financial/upload") return await uploadFinancial(request);
    if (path === "/api/staff/metadata") return await staffMetadata(request);
    if (path === "/api/financial/download") return await downloadFinancial(request);
    if (path === "/api/customers/sync") return await syncCustomerDirectory(request);
    if (path === "/api/staff/notifications") return await listCashierNotifications(request);
    if (path === "/api/staff/notifications/read") return await markCashierNotificationRead(request);
    if (path === "/api/staff/permission-requests") return await requestCashierPermission(request);
    return json({ outcome: "NOT_FOUND" }, 404);
  } catch (error: unknown) {
    // Never leak DB details, credential material, or bearer tokens in HTTP output.
    console.error("PAIDDUE_API_FAILURE", error instanceof Error ? error.name : "UNKNOWN_ERROR");
    return json({ outcome: "INTERNAL_SERVER_ERROR" }, 500);
  }
}

// Deno's supported entrypoint. The previous Cloudflare-style default export
// was not a Supabase Edge Function HTTP listener.
if (import.meta.main) Deno.serve(handleRequest);

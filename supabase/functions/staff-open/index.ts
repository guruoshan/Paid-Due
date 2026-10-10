const TOKEN_REGEX = /^[A-Za-z0-9_-]{32,128}$/;
const headers = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store, max-age=0",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
};
Deno.serve((request: Request) => {
  if (request.method !== "GET") {
    return new Response("Method not allowed", {
      status: 405,
      headers: { ...headers, "Content-Type": "text/plain; charset=utf-8", "Allow": "GET" },
    });
  }

  // The invitation token stays in the URL fragment. Browsers never send a fragment
  // in the HTTP request, so edge access logs only see the static landing page path.
  const body = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Paid&amp;Due invitation</title><style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f5f6f8;color:#171717;font:16px system-ui,sans-serif}
main{width:min(440px,calc(100% - 40px));box-sizing:border-box;padding:28px;border-radius:18px;background:white;box-shadow:0 10px 30px #00000012;text-align:center}
a{display:block;padding:14px 18px;margin-top:20px;border-radius:12px;background:#171717;color:white;text-decoration:none;font-weight:650}
p{line-height:1.5;color:#555}
</style></head><body><main><h1>Paid&amp;Due</h1><p id="message">Opening your invitation…</p><a id="open" href="#" style="display:none">Open Paid&amp;Due</a><p>Invitations expire after 48 hours and can be used once.</p></main>
<script>
(function () {
  const token = window.location.hash.replace(/^#/, "").trim();
  const message = document.getElementById("message");
  const button = document.getElementById("open");
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(token)) {
    message.textContent = "This invitation link is invalid or incomplete.";
    return;
  }
  const appLink = "paiddue://invite?t=" + encodeURIComponent(token);
  button.href = appLink;
  button.style.display = "block";
  message.textContent = "Your administrator invited you to join a business. Open Paid&Due to create your six-digit PIN.";
  window.location.replace(appLink);
})();
</script></body></html>`;
  return new Response(body, { status: 200, headers });
});
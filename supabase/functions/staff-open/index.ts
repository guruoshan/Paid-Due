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

  // Invitation tokens stay in the URL fragment. Browsers do not send fragments
  // in HTTP requests, so the token is not exposed in this function's request URL.
  const body = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Paid&amp;Due invitation</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f5f6f8;color:#171717;font:16px system-ui,sans-serif}
main{width:min(440px,calc(100% - 40px));box-sizing:border-box;padding:28px;border-radius:18px;background:white;box-shadow:0 10px 30px #00000012;text-align:center}
#open{display:block;box-sizing:border-box;padding:14px 18px;margin-top:20px;border-radius:12px;background:#171717;color:white;text-decoration:none;font-weight:650}
#open:focus-visible{outline:3px solid #64748b;outline-offset:3px}
p{line-height:1.5;color:#555}
#status{min-height:1.5em}
</style>
</head>
<body>
<main>
<h1>Paid&amp;Due</h1>
<p id="message">Checking your invitation…</p>
<a id="open" href="#" hidden>Open Paid&amp;Due</a>
<p id="status" aria-live="polite">Tap the button to continue in the app.</p>
<p>If Paid&amp;Due is not installed, install it and open this invitation link again.</p>
<p>Invitations expire after 48 hours and can be used once.</p>
</main>
<script>
(function () {
  const token = window.location.hash.replace(/^#/, "").trim();
  const message = document.getElementById("message");
  const button = document.getElementById("open");
  const status = document.getElementById("status");

  if (!/^[A-Za-z0-9_-]{32,128}$/.test(token)) {
    message.textContent = "This invitation link is invalid or incomplete. Ask your administrator for a new link.";
    status.textContent = "";
    return;
  }

  button.href = "paiddue://invite?t=" + encodeURIComponent(token);
  button.hidden = false;
  message.textContent = "Your administrator invited you to join Paid&Due.";
  status.textContent = "Tap Open Paid&Due to create your six-digit PIN.";

  // Deliberately do not auto-redirect. Browsers may block external-app launches
  // without a user gesture; the visible link is the supported handoff path.
  button.addEventListener("click", function () {
    status.textContent = "Opening Paid&Due…";
  });
})();
</script>
</body>
</html>`;
  return new Response(body, { status: 200, headers });
});

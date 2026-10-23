const headers = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
  "X-Content-Type-Options": "nosniff",
  "Content-Security-Policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'",
};
Deno.serve((_request: Request) => new Response(
  "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Paid&amp;Due</title></head><body><main><h1>Paid&amp;Due</h1><p>Staff invitations have been retired.</p></main></body></html>",
  { status: 410, headers },
));

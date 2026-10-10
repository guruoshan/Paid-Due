const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
};
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers });
}
async function rpc(url: string, key: string, body: unknown) {
  const response = await fetch(url + "/rest/v1/rpc/preview_staff_invite", {
    method: "POST",
    headers: { apikey: key, Authorization: "Bearer " + key, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error("PREVIEW_RPC_FAILED_" + response.status);
  return raw ? JSON.parse(raw) : null;
}
Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers });
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405);
  let body: any;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }
  const tokenHash = typeof body?.token_hash === "string" ? body.token_hash.trim().toLowerCase() : "";
  if (!/^[0-9a-f]{64}$/.test(tokenHash)) return json({ outcome: "INVALID_INVITE" }, 404);

  const url = Deno.env.get("SUPABASE_URL") || "";
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  if (!url.startsWith("https://") || !key) return json({ outcome: "BACKEND_NOT_CONFIGURED" }, 503);
  try {
    const rows = await rpc(url, key, { p_token_hash: tokenHash });
    if (!Array.isArray(rows) || rows.length !== 1) return json({ outcome: "INVALID_INVITE" }, 404);
    return json(rows[0], 200);
  } catch (error) {
    console.error("STAFF_INVITE_PREVIEW_FAILURE", error instanceof Error ? error.name : "UNKNOWN");
    return json({ outcome: "PREVIEW_UNAVAILABLE" }, 503);
  }
});
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
};
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: cors });
}
function hex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function sha256Hex(value: string): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))));
}
async function rpc(supabaseUrl: string, serviceKey: string, name: string, body: unknown) {
  const response = await fetch(`${supabaseUrl}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`RPC_${name}_FAILED_${response.status}`);
  return raw ? JSON.parse(raw) : null;
}
async function deriveCredential(pin: string, tokenHash: string, pepper: string) {
  const pepperKey = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const material = new TextEncoder().encode(`paiddue-cashier-salt-v2|${tokenHash}`);
  const salt = new Uint8Array(await crypto.subtle.sign("HMAC", pepperKey, material)).slice(0, 16);
  const pinKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: 100000 }, pinKey, 256);
  return { credentialHash: `pbkdf2-sha256:100000:${hex(new Uint8Array(bits))}`, credentialSalt: hex(salt) };
}
async function rateLimit(supabaseUrl: string, serviceKey: string, secret: string, identity: string) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const message = new TextEncoder().encode(`paiddue-auth-v1:ACTIVATE:${identity}`);
  const bucketKey = hex(new Uint8Array(await crypto.subtle.sign("HMAC", key, message)));
  const rows = await rpc(supabaseUrl, serviceKey, "check_auth_rate_limit", {
    p_scope: "ACTIVATE", p_bucket_key: bucketKey,
  });
  if (!Array.isArray(rows) || rows.length !== 1 || typeof rows[0]?.allowed !== "boolean") {
    throw new Error("RATE_LIMIT_RESULT_INVALID");
  }
  return rows[0] as { allowed: boolean; retry_after_seconds: number };
}
Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405);

  let body: any;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }
  const token = typeof body?.token === "string" ? body.token.trim() : "";
  const pin = typeof body?.pin === "string" ? body.pin : "";
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(token) || !/^[0-9]{6}$/.test(pin)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const pepper = Deno.env.get("CASHIER_PIN_PEPPER") || "";
  const rateSecret = Deno.env.get("AUTH_RATE_LIMIT_SECRET") || "";
  if (!supabaseUrl.startsWith("https://") || !serviceKey || pepper.length < 32 || rateSecret.length < 32) {
    return json({ outcome: "BACKEND_NOT_CONFIGURED" }, 503);
  }

  try {
    const tokenHash = await sha256Hex(token);
    const decision = await rateLimit(supabaseUrl, serviceKey, rateSecret, tokenHash);
    if (!decision.allowed) return json({ outcome: "RATE_LIMITED", retry_after_seconds: decision.retry_after_seconds }, 429);
    const credential = await deriveCredential(pin, tokenHash, pepper);
    const rows = await rpc(supabaseUrl, serviceKey, "activate_staff_invite", {
      p_token_hash: tokenHash,
      p_credential_hash: credential.credentialHash,
      p_credential_salt: credential.credentialSalt,
    });
    if (!Array.isArray(rows) || rows.length !== 1 || typeof rows[0]?.outcome !== "string") {
      throw new Error("ACTIVATION_RESULT_INVALID");
    }
    const row = rows[0];
    const status = row.outcome === "ACTIVATED" || row.outcome === "ALREADY_ACTIVATED" ? 200 :
      row.outcome === "PHONE_ALREADY_REGISTERED" ? 409 :
      row.outcome === "INVALID_INVITE" ? 400 : 400;
    return json(row, status);
  } catch (error) {
    console.error("STAFF_ACTIVATION_FAILURE", error instanceof Error ? error.name : "UNKNOWN");
    return json({ outcome: "ACTIVATION_FAILED" }, 500);
  }
});
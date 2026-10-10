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
async function getAuthenticatedUser(request: Request, supabaseUrl: string, anonKey: string) {
  const authorization = request.headers.get("authorization") || "";
  if (!/^Bearer\s+\S+$/i.test(authorization)) return null;
  const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
    headers: { apikey: anonKey, Authorization: authorization },
  });
  if (!response.ok) return null;
  const user = await response.json();
  return typeof user?.id === "string" ? user : null;
}
async function rpc(supabaseUrl: string, serviceKey: string, name: string, body: unknown) {
  const response = await fetch(`${supabaseUrl}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`RPC_${name}_FAILED_${response.status}`);
  return await response.json();
}
async function rateLimit(supabaseUrl: string, serviceKey: string, secret: string, identity: string) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const message = new TextEncoder().encode(`paiddue-auth-v1:ACTIVATE:${identity}`);
  const bucketKey = hex(new Uint8Array(await crypto.subtle.sign("HMAC", key, message)));
  const rows = await rpc(supabaseUrl, serviceKey, "check_auth_rate_limit", {
    p_scope: "ACTIVATE",
    p_bucket_key: bucketKey,
  });
  if (!Array.isArray(rows) || rows.length !== 1 || typeof rows[0]?.allowed !== "boolean") {
    throw new Error("RATE_LIMIT_RESULT_INVALID");
  }
  return rows[0] as { allowed: boolean; retry_after_seconds: number };
}
async function deriveCredential(pin: string, tokenHash: string, pepper: string) {
  const pepperKey = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const material = new TextEncoder().encode(`paiddue-cashier-salt-v2|${tokenHash}`);
  const hmac = new Uint8Array(await crypto.subtle.sign("HMAC", pepperKey, material));
  const salt = hmac.slice(0, 16);
  const pinKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: 100000 }, pinKey, 256);
  return { credentialHash: `pbkdf2-sha256:100000:${hex(new Uint8Array(bits))}`, credentialSalt: hex(salt) };
}
Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405);
  let body: any;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }

  const token = typeof body?.token === "string" ? body.token.trim() : "";
  const pin = typeof body?.pin === "string" ? body.pin : "";
  const phone = typeof body?.canonical_phone === "string" ? body.canonical_phone.trim() : "";
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(token) || !/^[0-9]{6}$/.test(pin) || !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") || Deno.env.get("SUPABASE_PUBLISHABLE_KEY") || "";
  const pepper = Deno.env.get("CASHIER_PIN_PEPPER") || "";
  const rateSecret = Deno.env.get("AUTH_RATE_LIMIT_SECRET") || "";
  if (!supabaseUrl.startsWith("https://") || !serviceKey || !anonKey || pepper.length < 32 || rateSecret.length < 32) {
    return json({ outcome: "BACKEND_NOT_CONFIGURED" }, 503);
  }

  const user = await getAuthenticatedUser(request, supabaseUrl, anonKey);
  if (!user) return json({ outcome: "UNAUTHORIZED" }, 401);

  try {
    const tokenHash = await sha256Hex(token);
    const decision = await rateLimit(supabaseUrl, serviceKey, rateSecret, tokenHash);
    if (!decision.allowed) {
      return json({ outcome: "RATE_LIMITED", retry_after_seconds: decision.retry_after_seconds }, 429);
    }
    const credential = await deriveCredential(pin, tokenHash, pepper);
    const result = await rpc(supabaseUrl, serviceKey, "activate_staff_invite_for_auth", {
      p_token_hash: tokenHash,
      p_auth_user_id: user.id,
      p_credential_hash: credential.credentialHash,
      p_credential_salt: credential.credentialSalt,
      p_canonical_phone: phone,
    });
    if (!result || typeof result.outcome !== "string") return json({ outcome: "ACTIVATION_FAILED" }, 502);
    const status = result.outcome === "ACTIVATED" || result.outcome === "ALREADY_ACTIVATED" ? 200 :
      result.outcome === "EMAIL_NOT_VERIFIED" ? 403 :
      result.outcome === "ALREADY_MEMBER" || result.outcome === "PHONE_ALREADY_REGISTERED" || result.outcome === "EMAIL_ALREADY_LINKED" || result.outcome === "ALREADY_MEMBER" ? 409 :
      result.outcome === "PHONE_MISMATCH" ? 400 :
      result.outcome === "RATE_LIMITED" ? 429 : 400;
    return json(result, status);
  } catch (error) {
    console.error("STAFF_ACTIVATION_FAILURE", error instanceof Error ? error.name : "UNKNOWN");
    return json({ outcome: "ACTIVATION_FAILED" }, 500);
  }
});
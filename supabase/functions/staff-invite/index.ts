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
function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
async function getAuthenticatedUser(request: Request, supabaseUrl: string, anonKey: string) {
  const authorization = request.headers.get("authorization") || "";
  if (!/^Bearer\s+\S+$/i.test(authorization)) return null;
  const response = await fetch(supabaseUrl + "/auth/v1/user", {
    headers: { apikey: anonKey, Authorization: authorization },
  });
  if (!response.ok) return null;
  const user = await response.json();
  return typeof user?.id === "string" ? user : null;
}
async function rpc(supabaseUrl: string, serviceKey: string, name: string, body: unknown) {
  const response = await fetch(supabaseUrl + "/rest/v1/rpc/" + name, {
    method: "POST",
    headers: { apikey: serviceKey, Authorization: "Bearer " + serviceKey, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error("RPC_" + name + "_FAILED_" + response.status);
  return raw ? JSON.parse(raw) : null;
}
Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405);

  let body: any;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }

  const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") || Deno.env.get("SUPABASE_PUBLISHABLE_KEY") || "";
  if (!supabaseUrl.startsWith("https://") || !serviceKey || !anonKey) {
    return json({ outcome: "BACKEND_NOT_CONFIGURED" }, 503);
  }
  const user = await getAuthenticatedUser(request, supabaseUrl, anonKey);
  if (!user) return json({ outcome: "UNAUTHORIZED" }, 401);

  const rateSecret = Deno.env.get("AUTH_RATE_LIMIT_SECRET") || "";
  if (rateSecret.length < 32) return json({ outcome: "BACKEND_NOT_CONFIGURED" }, 503);
  const rateKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(rateSecret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const rateMaterial = new TextEncoder().encode("paiddue-auth-v1:INVITE:" + user.id);
  const bucketKey = hex(new Uint8Array(await crypto.subtle.sign("HMAC", rateKey, rateMaterial)));
  const rateRows = await rpc(supabaseUrl, serviceKey, "check_auth_rate_limit", {
    p_scope: "INVITE", p_bucket_key: bucketKey,
  });
  if (!Array.isArray(rateRows) || rateRows.length !== 1 || typeof rateRows[0]?.allowed !== "boolean") {
    return json({ outcome: "AUTH_TEMPORARILY_UNAVAILABLE" }, 503);
  }
  if (!rateRows[0].allowed) {
    return json({ outcome: "RATE_LIMITED", retry_after_seconds: rateRows[0].retry_after_seconds }, 429);
  }

  const businessId = typeof body?.business_id === "string" ? body.business_id.trim() : "";
  const branchId = typeof body?.branch_id === "string" ? body.branch_id.trim() : "";
  const displayName = typeof body?.display_name === "string" ? body.display_name.trim() : "";
  const phone = typeof body?.canonical_phone === "string" ? body.canonical_phone.trim() : "";
  const permissions = body?.permissions && typeof body.permissions === "object" && !Array.isArray(body.permissions)
    ? body.permissions : {};
  if (!businessId || !branchId || displayName.length < 1 || displayName.length > 100 ||
      !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const token = randomToken();
  const tokenHash = await sha256Hex(token);
  try {
    const created = await rpc(supabaseUrl, serviceKey, "create_staff_invite_for_admin", {
      p_token_hash: tokenHash,
      p_business_id: businessId,
      p_branch_id: branchId,
      p_display_name: displayName,
      p_canonical_phone: phone,
      p_permissions: permissions,
      p_created_by_auth_user_id: user.id,
    });
    if (!Array.isArray(created) || created.length !== 1 ||
        typeof created[0]?.invite_id !== "string" || typeof created[0]?.expires_at !== "string") {
      throw new Error("INVITE_CREATE_RESULT_INVALID");
    }
    const baseUrl = supabaseUrl.endsWith("/") ? supabaseUrl.slice(0, -1) : supabaseUrl;
    const landingUrl = baseUrl + "/functions/v1/staff-open#" + encodeURIComponent(token);
    // Share a normal HTTPS URL so WhatsApp and browsers can open it reliably.
    // The landing page keeps the token in the URL fragment and hands off to the app.
    const inviteUrl = landingUrl;
    return json({ outcome: "INVITATION_CREATED", invite_url: inviteUrl, expires_at: created[0].expires_at });
  } catch (error) {
    console.error("STAFF_INVITE_FAILURE", error instanceof Error ? error.name : "UNKNOWN");
    return json({ outcome: "INVITATION_FAILED" }, 500);
  }
});
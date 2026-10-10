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
async function sha256Hex(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
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
    headers: {
      apikey: serviceKey,
      Authorization: `Bearer ${serviceKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  const raw = await response.text();
  if (!response.ok) throw new Error(`RPC_${name}_FAILED_${response.status}`);
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
  const rateKey = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(rateSecret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const rateMaterial = new TextEncoder().encode(`paiddue-auth-v1:INVITE:${user.id}`);
  const bucketBytes = new Uint8Array(await crypto.subtle.sign("HMAC", rateKey, rateMaterial));
  const bucketKey = Array.from(bucketBytes).map((b) => b.toString(16).padStart(2, "0")).join("");
  const rateRows = await rpc(supabaseUrl, serviceKey, "check_auth_rate_limit", {
    p_scope: "INVITE",
    p_bucket_key: bucketKey,
  });
  if (!Array.isArray(rateRows) || rateRows.length !== 1 || typeof rateRows[0]?.allowed !== "boolean") {
    return json({ outcome: "AUTH_TEMPORARILY_UNAVAILABLE" }, 503);
  }
  if (!rateRows[0].allowed) {
    return json({ outcome: "RATE_LIMITED", retry_after_seconds: rateRows[0].retry_after_seconds }, 429);
  }

  const email = typeof body?.email === "string" ? body.email.trim().toLowerCase() : "";
  const businessId = typeof body?.business_id === "string" ? body.business_id.trim() : "";
  const branchId = typeof body?.branch_id === "string" ? body.branch_id.trim() : "";
  const displayName = typeof body?.display_name === "string" ? body.display_name.trim() : "";
  const phone = typeof body?.canonical_phone === "string" ? body.canonical_phone.trim() : "";
  const permissions = body?.permissions && typeof body.permissions === "object" && !Array.isArray(body.permissions) ? body.permissions : {};
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254 ||
      !businessId || !branchId || displayName.length < 1 || displayName.length > 100 ||
      !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const token = randomToken();
  const tokenHash = await sha256Hex(token);
  let inviteCreated = false;
  try {
    const created = await rpc(supabaseUrl, serviceKey, "create_staff_invite_for_email", {
      p_token_hash: tokenHash,
      p_business_id: businessId,
      p_branch_id: branchId,
      p_display_name: displayName,
      p_canonical_phone: phone,
      p_invited_email: email,
      p_permissions: permissions,
      p_created_by_auth_user_id: user.id,
    });
    if (!Array.isArray(created) || created.length !== 1 || typeof created[0]?.invite_id !== "string") {
      throw new Error("INVITE_CREATE_RESULT_INVALID");
    }
    inviteCreated = true;

    const redirectTo = `paiddue://invite?t=${encodeURIComponent(token)}`;
    const inviteUrl = new URL(`${supabaseUrl}/auth/v1/invite`);
    inviteUrl.searchParams.set("redirect_to", redirectTo);
    const authHeaders = {
      apikey: serviceKey,
      Authorization: `Bearer ${serviceKey}`,
      "Content-Type": "application/json",
    };
    let emailResponse = await fetch(inviteUrl, {
      method: "POST",
      headers: authHeaders,
      body: JSON.stringify({ email, data: { paid_due_staff_invite: true } }),
    });

    if (!emailResponse.ok) {
      const errorText = (await emailResponse.text()).toLowerCase();
      if (errorText.includes("already registered") || errorText.includes("already been registered") ||
          errorText.includes("user already exists") || errorText.includes("already exists")) {
        const otpUrl = new URL(`${supabaseUrl}/auth/v1/otp`);
        otpUrl.searchParams.set("redirect_to", redirectTo);
        emailResponse = await fetch(otpUrl, {
          method: "POST",
          headers: { apikey: anonKey, "Content-Type": "application/json" },
          body: JSON.stringify({ email, create_user: false }),
        });
      }
    }

    if (!emailResponse.ok) {
      await emailResponse.text();
      await rpc(supabaseUrl, serviceKey, "revoke_staff_invite_for_email", {
        p_token_hash: tokenHash,
        p_created_by_auth_user_id: user.id,
      }).catch(() => null);
      return json({ outcome: "INVITATION_EMAIL_FAILED", hint: "Check Supabase Auth email provider and redirect URL allowlist." }, 502);
    }

    return json({ outcome: "INVITATION_SENT", expires_in_hours: 48 });
  } catch (error) {
    if (inviteCreated) {
      await rpc(supabaseUrl, serviceKey, "revoke_staff_invite_for_email", {
        p_token_hash: tokenHash,
        p_created_by_auth_user_id: user.id,
      }).catch(() => null);
    }
    console.error("STAFF_INVITE_FAILURE", error instanceof Error ? error.name : "UNKNOWN");
    return json({ outcome: "INVITATION_FAILED" }, 500);
  }
});
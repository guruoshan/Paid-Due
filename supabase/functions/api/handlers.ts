import { sha256Hex, deriveCredential, hexToBytes } from "./crypto.ts";
import { rpc } from "./supabase.ts";
import { checkAuthRateLimit } from "./rateLimit.ts";

type RateGate = (
  scope: "LOGIN" | "ACTIVATE",
  identity: string,
) => Promise<{ allowed: boolean; retryAfterSeconds: number }>;

async function enforceAuthLimit(
  scope: "LOGIN" | "ACTIVATE",
  identity: string,
  gate: RateGate,
): Promise<Response | null> {
  try {
    const decision = await gate(scope, identity);
    if (decision.allowed) return null;

    const seconds = Math.max(
      1,
      Math.min(3600, Math.ceil(decision.retryAfterSeconds)),
    );

    return json(
      { outcome: "RATE_LIMITED" },
      429,
      { "Retry-After": String(seconds) },
    );
  } catch {
    // Fail closed if the shared limiter is unavailable.
    return json({ outcome: "AUTH_TEMPORARILY_UNAVAILABLE" }, 503);
  }
}

export function json(body: any, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

const TOKEN_REGEX = /^[A-Za-z0-9_-]{32,128}$/;
const PIN_REGEX = /^[0-9]{6}$/;
const PHONE_REGEX = /^\+[1-9][0-9]{7,14}$/;

export function health() {
  return json({
    service: "Paid&Due",
    status: "OK",
    backend_version: "3",
    credential_format: "pbkdf2-sha256",
    pbkdf2_iterations: 100000,
  }, 200);
}

export async function activateStaff(
  request: Request,
  rateGate: RateGate = checkAuthRateLimit,
) {
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { "Allow": "POST" });
  
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.toLowerCase().startsWith("application/json")) return json({ outcome: "INVALID_REQUEST" }, 400);

  let body;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }

  const token = body.token || "";
  const pin = body.pin || "";
  const canonicalPhone = body.canonical_phone || "";

  if (!TOKEN_REGEX.test(token) || !PIN_REGEX.test(pin) || !PHONE_REGEX.test(canonicalPhone)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const tokenHash = await sha256Hex(token);

  const blocked = await enforceAuthLimit(
    "ACTIVATE",
    tokenHash,
    rateGate,
  );
  if (blocked) return blocked;
  // Rate limiting omitted for local testing, would be here.
  const pepper = Deno.env.get("CASHIER_PIN_PEPPER");

  if (!pepper || pepper.length < 32) {
    throw new Error("PIN_PEPPER_NOT_CONFIGURED");
  }

  const credential = await deriveCredential(pin, tokenHash, pepper);
  
  try {
    const result = await rpc("activate_staff_invite", {
      p_token_hash: tokenHash,
      p_credential_hash: credential.credentialHash,
      p_credential_salt: credential.credentialSalt,
    });
    
    if (!Array.isArray(result) || result.length !== 1) throw new Error("ACTIVATION_RESULT_INVALID");
    const row = result[0];
    
    if (row.outcome === "ACTIVATED" || row.outcome === "ALREADY_ACTIVATED") {
      return json(row, 200);
    } else if (row.outcome === "PHONE_ALREADY_REGISTERED") {
      return json({ outcome: "PHONE_ALREADY_REGISTERED" }, 409);
    } else if (row.outcome === "INVALID_INVITE") {
      return json({ outcome: "INVALID_INVITE" }, 400);
    }
    return json({ outcome: "UNKNOWN_ACTIVATION_RESULT" }, 400);
  } catch (error: any) {
    if (error.status === 400 || error.status === 409) return json({ outcome: error.outcome }, error.status);
    throw error;
  }
}

export async function loginStaff(
  request: Request,
  rateGate: RateGate = checkAuthRateLimit,
) {
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { "Allow": "POST" });
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.toLowerCase().startsWith("application/json")) return json({ outcome: "INVALID_REQUEST" }, 400);

  let body;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }

  const phone = body.canonical_phone;
  const pin = body.pin;

  if (typeof phone !== "string" || !PHONE_REGEX.test(phone) || typeof pin !== "string" || !PIN_REGEX.test(pin)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const blocked = await enforceAuthLimit(
    "LOGIN",
    phone,
    rateGate,
  );
  if (blocked) return blocked;

  try {
    const rows = await rpc("cashier_login_credential", { p_phone: phone });
    if (!Array.isArray(rows) || rows.length !== 1) return json({ outcome: "INVALID_CREDENTIALS" }, 401);
    
    const staff = rows[0];
    const match = /^pbkdf2-sha256:(\d+):([0-9a-f]{64})$/.exec(staff.credential_hash || "");
    if (!match || !/^[0-9a-f]{32}$/.test(staff.credential_salt || "")) return json({ outcome: "INVALID_CREDENTIALS" }, 401);

    const iterations = Number(match[1]);
    const salt = hexToBytes(staff.credential_salt);

    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: salt.buffer as ArrayBuffer, iterations }, key, 256);
    const actual = new Uint8Array(bits);
    const expected = hexToBytes(match[2]);

    let difference = 0;
    for (let i = 0; i < 32; i++) difference |= actual[i] ^ expected[i];
    if (difference !== 0) return json({ outcome: "INVALID_CREDENTIALS" }, 401);

    const tokenBytes = new Uint8Array(32);
    crypto.getRandomValues(tokenBytes);
    const sessionToken = Array.from(tokenBytes).map(x => x.toString(16).padStart(2, "0")).join("");
    const tokenHash = await sha256Hex(sessionToken);

    // Resolve the complete, current membership snapshot only after the
    // server has verified the PIN. This response is used for safe local
    // restoration; the Android client must still match stable tenant IDs.
    if (typeof staff.business_id !== "string" ||
        typeof staff.branch_id !== "string" ||
        typeof staff.local_user_id !== "string") {
      throw new Error("CASHIER_AUTHORITY_FIELDS_MISSING");
    }
    const metadataRows = await rpc("fetch_cashier_metadata", {
      p_business_id: staff.business_id,
      p_branch_id: staff.branch_id,
      p_membership_id: staff.membership_id
    });
    if (!Array.isArray(metadataRows) || metadataRows.length !== 1) {
      return json({ outcome: "MEMBERSHIP_NOT_ACTIVE" }, 403);
    }
    const metadata = metadataRows[0];
    const p = metadata.permissions;
    if (!p || typeof p !== "object") throw new Error("CASHIER_PERMISSIONS_MISSING");
    const permissions = {
      CREATE_SALE: p.can_create_sale,
      ACCEPT_CASH: p.can_accept_cash,
      ACCEPT_ONLINE: p.can_accept_online,
      CREATE_CREDIT_SALE: p.can_create_credit_sale,
      USE_COUPON: p.can_use_coupon,
      ISSUE_INVOICE: p.can_issue_invoice,
      ISSUE_RECEIPT: p.can_issue_receipt,
      REFUND: p.can_refund,
      VOID: p.can_void,
      CORRECT_AMOUNT: p.can_correct_amount,
      CHANGE_PAYMENT_METHOD: p.can_change_payment_method,
      VIEW_BROAD_HISTORY: p.can_view_broad_history,
      MANAGE_PRODUCTS: p.can_manage_products,
      VIEW_REPORTS: p.can_view_reports
    };
    if (Object.values(permissions).some(value => typeof value !== "boolean")) {
      throw new Error("CASHIER_PERMISSIONS_INVALID");
    }

    const sessions = await rpc("create_cashier_sync_session", {
      p_membership_id: staff.membership_id,
      p_token_hash: tokenHash
    });
    if (!Array.isArray(sessions) || sessions.length !== 1) throw new Error("SESSION_CREATION_FAILED");

    return json({
      outcome: "AUTHENTICATED",
      membership_id: staff.membership_id,
      session_token: sessionToken,
      expires_at: sessions[0].expires_at,
      restoration: {
        ...metadata,
        local_user_id: staff.local_user_id,
        permissions
      }
    }, 200);

  } catch (error: any) {
    if (error.status === 400 || error.status === 401) return json({ outcome: "INVALID_CREDENTIALS" }, 401);
    throw error;
  }
}

async function verifyToken(request: Request): Promise<any> {
  const auth = request.headers.get("Authorization") || "";
  const match = /^Bearer\s+([0-9a-f]{64})$/i.exec(auth);
  if (!match) throw new Error("UNAUTHORIZED");
  const tokenHash = await sha256Hex(match[1]);
  
  try {
    const rows = await rpc("verify_cashier_sync_session", { p_token_hash: tokenHash });
    if (!Array.isArray(rows) || rows.length !== 1) throw new Error("UNAUTHORIZED");
    return rows[0];
  } catch {
    throw new Error("UNAUTHORIZED");
  }
}

export async function uploadFinancial(request: Request) {
  if (request.method !== "POST") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { "Allow": "POST" });
  
  let session;
  try { session = await verifyToken(request); } catch { return json({ outcome: "UNAUTHORIZED" }, 401); }

  let body;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }

  // Must match session
  if (body.p_business_id !== session.business_id || 
      body.p_branch_id !== session.branch_id || 
      body.p_actor_membership_id !== session.membership_id) {
    return json({ outcome: "FORBIDDEN" }, 403);
  }

  try {
    const result = await rpc("ingest_cashier_financial_envelope", body);
    if (Array.isArray(result) && result.length === 1) {
      return json({
        status: result[0].status,
        operation_id: result[0].operation_id,
        canonical_sha256: result[0].canonical_sha256,
        duplicate: result[0].duplicate
      }, 200);
    }
    throw new Error("INVALID_INGEST_RESULT");
  } catch (error: any) {
    if (error.status === 400 || error.status === 403) {
      return json({ outcome: error.outcome }, error.status);
    }
    throw error;
  }
}

export async function staffMetadata(request: Request) {
  if (request.method !== "GET") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { "Allow": "GET" });
  
  let session;
  try { session = await verifyToken(request); } catch { return json({ outcome: "UNAUTHORIZED" }, 401); }

  try {
    const result = await rpc("fetch_cashier_metadata", {
      p_business_id: session.business_id,
      p_branch_id: session.branch_id,
      p_membership_id: session.membership_id
    });
    if (Array.isArray(result) && result.length === 1) {
      return json(result[0], 200);
    }
    return json({ outcome: "NOT_FOUND" }, 404);
  } catch (error: any) {
    if (error.status === 400) return json({ outcome: error.outcome }, 400);
    throw error;
  }
}

export async function downloadFinancial(request: Request) {
  if (request.method !== "GET") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { "Allow": "GET" });
  let session;
  try { session = await verifyToken(request); } catch { return json({ outcome: "UNAUTHORIZED" }, 401); }
  const url = new URL(request.url);
  const limit = Number(url.searchParams.get("limit") || "100");
  const afterReceivedAt = url.searchParams.get("after_received_at");
  const afterOperationId = url.searchParams.get("after_operation_id");
  if (!Number.isInteger(limit) || limit < 1 || limit > 100 ||
      (!!afterReceivedAt !== !!afterOperationId)) return json({ outcome: "INVALID_REQUEST" }, 400);
  try {
    const rows = await rpc("fetch_cashier_financial_envelopes", {
      p_business_id: session.business_id, p_branch_id: session.branch_id,
      p_membership_id: session.membership_id, p_after_received_at: afterReceivedAt,
      p_after_operation_id: afterOperationId, p_limit: limit,
    });
    return json({ envelopes: Array.isArray(rows) ? rows : [] }, 200);
  } catch (error: any) {
    if (error.status === 400 || error.status === 403) return json({ outcome: error.outcome }, error.status);
    throw error;
  }
}

import { rpcAsUser } from "./supabase.ts";

export function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}) {
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

export function health() {
  return json({
    service: "Paid&Due",
    status: "OK",
    backend_version: "4",
    feature_mode: "ADMIN_ONLY",
  });
}

const PHONE_REGEX = /^\+[1-9][0-9]{7,14}$/;

export async function syncCustomerDirectory(request: Request) {
  const authorization = request.headers.get("Authorization") || "";
  const bearer = /^Bearer\s+([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/i.exec(authorization)?.[1];
  if (!bearer) return json({ outcome: "UNAUTHORIZED" }, 401);
  return await syncCustomerDirectoryAdmin(request, bearer);
}

async function syncCustomerDirectoryAdmin(request: Request, accessToken: string) {
  const url = new URL(request.url);
  if (request.method !== "GET" && request.method !== "POST") {
    return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { Allow: "GET, POST" });
  }

  if (request.method === "GET") {
    const businessId = url.searchParams.get("business_id") || "";
    const branchId = url.searchParams.get("branch_id") || "";
    const rawLimit = url.searchParams.get("limit") || "100";
    const limit = Number(rawLimit);
    const after = url.searchParams.get("after_updated_at");
    const afterPhone = url.searchParams.get("after_canonical_phone");
    if (!businessId || businessId.length > 128 || !branchId || branchId.length > 128 ||
        !Number.isInteger(limit) || limit < 1 || limit > 500 ||
        (after !== null && (!Number.isFinite(Date.parse(after)) || after.length > 64)) ||
        (afterPhone !== null && (!after || !PHONE_REGEX.test(afterPhone)))) {
      return json({ outcome: "INVALID_REQUEST" }, 400);
    }
    try {
      const rows = await rpcAsUser("sync_customer_directory_admin_list", {
        p_business_id: businessId, p_branch_id: branchId,
        p_after_updated_at: after, p_after_canonical_phone: afterPhone, p_limit: limit,
      }, accessToken);
      return json({ customers: Array.isArray(rows) ? rows : [] }, 200);
    } catch (error: any) {
      if (error.status === 400) return json({ outcome: "INVALID_REQUEST" }, 400);
      if (error.status === 401 || error.status === 403) return json({ outcome: "FORBIDDEN" }, 403);
      return json({ outcome: "CUSTOMER_SYNC_UNAVAILABLE" }, 503);
    }
  }

  const contentType = request.headers.get("content-type") || "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }
  let body: any;
  try { body = await request.json(); } catch { return json({ outcome: "INVALID_REQUEST" }, 400); }
  if (typeof body.business_id !== "string" || !body.business_id.trim() || body.business_id.length > 128 ||
      typeof body.branch_id !== "string" || !body.branch_id.trim() || body.branch_id.length > 128 ||
      typeof body.customer_id !== "string" || !body.customer_id.trim() || body.customer_id.trim().length > 128 ||
      typeof body.canonical_phone !== "string" || !PHONE_REGEX.test(body.canonical_phone) ||
      typeof body.display_name !== "string" || !body.display_name.trim() || body.display_name.trim().length > 200 ||
      (body.created_at != null && (typeof body.created_at !== "string" ||
        !Number.isFinite(Date.parse(body.created_at)) || body.created_at.length > 64))) {
    return json({ outcome: "INVALID_CUSTOMER" }, 400);
  }
  try {
    const rows = await rpcAsUser("sync_customer_directory_admin_upsert", {
      p_business_id: body.business_id.trim(), p_branch_id: body.branch_id.trim(),
      p_customer_id: body.customer_id.trim(), p_canonical_phone: body.canonical_phone,
      p_display_name: body.display_name.trim(), p_created_at: body.created_at || null,
    }, accessToken);
    if (!Array.isArray(rows) || rows.length !== 1) throw new Error("CUSTOMER_SYNC_RESULT_INVALID");
    return json({ customer: rows[0] }, 200);
  } catch (error: any) {
    if (error.outcome === "CUSTOMER_IDENTITY_CONFLICT" || error.message === "CUSTOMER_IDENTITY_CONFLICT") {
      return json({ outcome: "CUSTOMER_IDENTITY_CONFLICT" }, 409);
    }
    if (error.status === 400) return json({ outcome: "INVALID_CUSTOMER" }, 400);
    if (error.status === 401 || error.status === 403) return json({ outcome: "FORBIDDEN" }, 403);
    return json({ outcome: "CUSTOMER_SYNC_UNAVAILABLE" }, 503);
  }
}

import { health, loginStaff, uploadFinancial, staffMetadata, downloadFinancial, listCashierNotifications, markCashierNotificationRead, requestCashierPermission, json } from "./handlers.ts";

// Supabase Function gateway normally forwards /api/<route> after /functions/v1/api.
// Both forms are accepted to preserve Android compatibility and local testing.

export async function handleRequest(request: Request): Promise<Response> {
  const rawPath = new URL(request.url).pathname;
  const path = rawPath.startsWith("/functions/v1/api/")
    ? rawPath.slice("/functions/v1/api".length)
    : rawPath;
  try {
    if (path === "/api/health") {
      if (request.method !== "GET") return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { Allow: "GET" });
      return health();
    }
    if (path === "/api/staff/login") return await loginStaff(request);
    if (path === "/api/financial/upload") return await uploadFinancial(request);
    if (path === "/api/staff/metadata") return await staffMetadata(request);
    if (path === "/api/financial/download") return await downloadFinancial(request);
    if (path === "/api/staff/notifications") return await listCashierNotifications(request);
    if (path === "/api/staff/notifications/read") return await markCashierNotificationRead(request);
    if (path === "/api/staff/permission-requests") return await requestCashierPermission(request);
    return json({ outcome: "NOT_FOUND" }, 404);
  } catch (error: unknown) {
    // Never leak DB details, credential material, or bearer tokens in HTTP output.
    console.error("PAIDDUE_API_FAILURE", error instanceof Error ? error.name : "UNKNOWN_ERROR");
    return json({ outcome: "INTERNAL_SERVER_ERROR" }, 500);
  }
}

// Supabase Edge Function entrypoint.
if (import.meta.main) Deno.serve(handleRequest);

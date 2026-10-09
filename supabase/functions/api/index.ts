import {
  health, activateStaff, loginStaff, uploadFinancial, staffMetadata, downloadFinancial, json,
} from "./handlers.ts";

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
    if (path === "/api/staff/activate") return await activateStaff(request);
    if (path === "/api/staff/login") return await loginStaff(request);
    if (path === "/api/financial/upload") return await uploadFinancial(request);
    if (path === "/api/staff/metadata") return await staffMetadata(request);
    if (path === "/api/financial/download") return await downloadFinancial(request);
    return json({ outcome: "NOT_FOUND" }, 404);
  } catch (error: unknown) {
    // Never leak DB details, credential material, or bearer tokens in HTTP output.
    console.error("PAIDDUE_API_FAILURE", error instanceof Error ? error.name : "UNKNOWN_ERROR");
    return json({ outcome: "INTERNAL_SERVER_ERROR" }, 500);
  }
}

// Deno's supported entrypoint. The previous Cloudflare-style default export
// was not a Supabase Edge Function HTTP listener.
if (import.meta.main) Deno.serve(handleRequest);

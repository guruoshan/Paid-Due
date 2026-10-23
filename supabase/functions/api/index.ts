import { health, uploadFinancial, downloadFinancial, syncCustomerDirectory, json } from "./handlers.ts";

function normalizeApiPath(rawPath: string): string {
  const prefix = "/functions/v1/api";
  let path = rawPath;
  if (path === prefix) path = "/";
  else if (path.startsWith(prefix + "/")) path = path.slice(prefix.length);
  while (path === "/api" || path.startsWith("/api/")) {
    path = path === "/api" ? "/" : path.slice("/api".length);
  }
  return path;
}

export async function handleRequest(request: Request): Promise<Response> {
  const path = normalizeApiPath(new URL(request.url).pathname);
  try {
    if (path === "/health" && request.method === "GET") return health();
    if (path === "/financial/upload") return await uploadFinancial(request);
    if (path === "/financial/download") return await downloadFinancial(request);
    if (path === "/customers/sync") return await syncCustomerDirectory(request);
    if (path.startsWith("/staff/") || path === "/open/invite" || path.startsWith("/open/invite/")) {
      return json({ outcome: "FEATURE_RETIRED" }, 410, { "Cache-Control": "no-store" });
    }
    return json({ outcome: "NOT_FOUND" }, 404);
  } catch (error: unknown) {
    console.error("PAIDDUE_API_FAILURE", error instanceof Error ? error.name : "UNKNOWN_ERROR");
    return json({ outcome: "INTERNAL_SERVER_ERROR" }, 500);
  }
}

if (import.meta.main) Deno.serve(handleRequest);

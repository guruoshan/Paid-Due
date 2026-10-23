import { assertEquals } from "https://deno.land/std@0.200.0/assert/mod.ts";
import { handleRequest } from "./index.ts";

Deno.test("Router: health works", async () => {
  const response = await handleRequest(new Request("http://localhost/api/health"));
  assertEquals(response.status, 200);
  assertEquals((await response.json()).status, "OK");
});

Deno.test("Router: unknown route returns 404", async () => {
  const response = await handleRequest(new Request("http://localhost/api/not-a-route"));
  assertEquals(response.status, 404);
});

for (const path of [
  "/api/staff/login",
  "/api/staff/activate",
  "/api/staff/metadata",
  "/api/staff/notifications",
  "/api/staff/permission-requests",
  "/api/financial/upload",
  "/api/financial/download",
  "/api/open/invite",
]) {
  Deno.test(`Router retires ${path}`, async () => {
    const response = await handleRequest(new Request(`http://localhost${path}`, { method: "POST", body: "{}" }));
    assertEquals(response.status, 410);
    assertEquals((await response.json()).outcome, "FEATURE_RETIRED");
  });
}

Deno.test("Customer sync without Admin JWT is rejected", async () => {
  const response = await handleRequest(new Request("http://localhost/api/customers/sync"));
  assertEquals(response.status, 401);
});

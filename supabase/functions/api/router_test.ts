import { assertEquals } from "https://deno.land/std@0.200.0/assert/mod.ts";
import { handleRequest } from "./index.ts";

Deno.test("Router: GET health works on /api/health", async () => {
  const response = await handleRequest(new Request("http://localhost/api/health"));
  assertEquals(response.status, 200);
  assertEquals((await response.json()).status, "OK");
});
Deno.test("Router: health works via gateway path", async () => {
  const response = await handleRequest(new Request("http://localhost/functions/v1/api/api/health"));
  assertEquals(response.status, 200);
});
Deno.test("Router: health refuses POST", async () => {
  const response = await handleRequest(new Request("http://localhost/api/health", {method: "POST"}));
  assertEquals(response.status, 405);
});
Deno.test("Router: unknown route returns 404", async () => {
  const response = await handleRequest(new Request("http://localhost/api/not-a-route"));
  assertEquals(response.status, 404);
});
Deno.test("Router: unauthenticated financial upload returns 401", async () => {
  const response = await handleRequest(new Request("http://localhost/api/financial/upload", {
    method: "POST", headers: {"content-type":"application/json"}, body: "{}",
  }));
  assertEquals(response.status, 401);
});
Deno.test("Router: unauthenticated cashier metadata returns 401", async () => {
  const response = await handleRequest(new Request("http://localhost/api/staff/metadata"));
  assertEquals(response.status, 401);
});
Deno.test("Router: staff activation rejects malformed invite input before backend access", async () => {
  const response = await handleRequest(new Request("http://localhost/api/staff/activate", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token: "bad", pin: "123", canonical_phone: "+96891234567" }),
  }));
  assertEquals(response.status, 400);
});

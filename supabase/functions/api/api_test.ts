import { assertEquals } from "https://deno.land/std@0.200.0/assert/mod.ts";
import { health, syncCustomerDirectory } from "./handlers.ts";

Deno.test("Health reports admin-only feature mode", async () => {
  const response = health();
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.service, "Paid&Due");
  assertEquals(body.status, "OK");
  assertEquals(body.feature_mode, "ADMIN_ONLY");
});

Deno.test("Customer directory rejects requests without an Admin JWT", async () => {
  const response = await syncCustomerDirectory(new Request("http://localhost/customers/sync"));
  assertEquals(response.status, 401);
});

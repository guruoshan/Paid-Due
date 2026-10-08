import {
  assertEquals,
} from "https://deno.land/std@0.200.0/assert/mod.ts";

import {
  activateStaff,
  loginStaff,
} from "./handlers.ts";

function loginRequest(): Request {
  return new Request("http://localhost/api/staff/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      canonical_phone: "+96891234567",
      pin: "123456",
    }),
  });
}

function activationRequest(): Request {
  return new Request("http://localhost/api/staff/activate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      token: "A".repeat(48),
      pin: "123456",
      canonical_phone: "+96891234567",
    }),
  });
}

Deno.test("Login rejects throttled attempts with 429", async () => {
  const response = await loginStaff(
    loginRequest(),
    async (scope, identity) => {
      assertEquals(scope, "LOGIN");
      assertEquals(identity, "+96891234567");
      return { allowed: false, retryAfterSeconds: 240 };
    },
  );

  assertEquals(response.status, 429);
  assertEquals(response.headers.get("Retry-After"), "240");
  assertEquals((await response.json()).outcome, "RATE_LIMITED");
});

Deno.test("Login fails closed when limiter fails", async () => {
  const response = await loginStaff(
    loginRequest(),
    async () => {
      throw new Error("DATABASE_UNAVAILABLE");
    },
  );

  assertEquals(response.status, 503);
  assertEquals(
    (await response.json()).outcome,
    "AUTH_TEMPORARILY_UNAVAILABLE",
  );
});

Deno.test("Activation rejects throttled attempts", async () => {
  const response = await activateStaff(
    activationRequest(),
    async (scope, identity) => {
      assertEquals(scope, "ACTIVATE");
      assertEquals(/^[0-9a-f]{64}$/.test(identity), true);
      return { allowed: false, retryAfterSeconds: 1200 };
    },
  );

  assertEquals(response.status, 429);
  assertEquals(response.headers.get("Retry-After"), "1200");
});

Deno.test("Activation fails closed on limiter failure", async () => {
  const response = await activateStaff(
    activationRequest(),
    async () => {
      throw new Error("DATABASE_UNAVAILABLE");
    },
  );

  assertEquals(response.status, 503);
  assertEquals(
    (await response.json()).outcome,
    "AUTH_TEMPORARILY_UNAVAILABLE",
  );
});
import {
  assertEquals,
  assertRejects,
} from "https://deno.land/std@0.200.0/assert/mod.ts";

import {
  checkAuthRateLimit,
} from "./rateLimit.ts";

const SECRET =
  "test_only_auth_rate_limit_secret_1234567890";

Deno.test("Rate limit allows approved request", async () => {
  const decision = await checkAuthRateLimit(
    "LOGIN",
    "+96891234567",
    async (_name, args) => {
      assertEquals(
        _name,
        "check_auth_rate_limit",
      );

      assertEquals(args.p_scope, "LOGIN");

      assertEquals(
        /^[0-9a-f]{64}$/.test(
          String(args.p_bucket_key),
        ),
        true,
      );

      return [{
        allowed: true,
        retry_after_seconds: 0,
      }];
    },
    SECRET,
  );

  assertEquals(decision.allowed, true);
  assertEquals(decision.retryAfterSeconds, 0);
});

Deno.test("Rate limit blocks excessive attempts", async () => {
  const decision = await checkAuthRateLimit(
    "LOGIN",
    "+96891234567",
    async () => [{
      allowed: false,
      retry_after_seconds: 240,
    }],
    SECRET,
  );

  assertEquals(decision.allowed, false);
  assertEquals(decision.retryAfterSeconds, 240);
});

Deno.test("Rate limit requires server secret", async () => {
  await assertRejects(
    () => checkAuthRateLimit(
      "LOGIN",
      "+96891234567",
      async () => [],
      "",
    ),
    Error,
    "RATE_LIMIT_SECRET_MISSING",
  );
});

Deno.test("Rate limit fails closed on DB error", async () => {
  await assertRejects(
    () => checkAuthRateLimit(
      "LOGIN",
      "+96891234567",
      async () => {
        throw new Error("DB_UNAVAILABLE");
      },
      SECRET,
    ),
    Error,
    "DB_UNAVAILABLE",
  );
});

Deno.test("Rate limit rejects malformed RPC result", async () => {
  await assertRejects(
    () => checkAuthRateLimit(
      "LOGIN",
      "+96891234567",
      async () => [{ allowed: true }],
      SECRET,
    ),
    Error,
    "RATE_LIMIT_RESULT_INVALID",
  );
});

Deno.test("Same identity produces stable bucket", async () => {
  const keys: string[] = [];

  for (let i = 0; i < 2; i++) {
    await checkAuthRateLimit(
      "ACTIVATE",
      "test-invite-hash",
      async (_name, args) => {
        keys.push(String(args.p_bucket_key));
        return [{
          allowed: true,
          retry_after_seconds: 0,
        }];
      },
      SECRET,
    );
  }

  assertEquals(keys[0], keys[1]);
});

Deno.test("Different scopes use different buckets", async () => {
  const keys: string[] = [];

  for (const scope of ["LOGIN", "ACTIVATE"] as const) {
    await checkAuthRateLimit(
      scope,
      "+96891234567",
      async (_name, args) => {
        keys.push(String(args.p_bucket_key));
        return [{
          allowed: true,
          retry_after_seconds: 0,
        }];
      },
      SECRET,
    );
  }

  assertEquals(keys[0] === keys[1], false);
});
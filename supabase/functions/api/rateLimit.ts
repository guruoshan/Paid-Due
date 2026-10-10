import { rpc } from "./supabase.ts";

export type AuthScope = "LOGIN" | "ACTIVATE";

export interface RateLimitDecision {
  allowed: boolean;
  retryAfterSeconds: number;
}

type RpcCall = (
  name: string,
  args: Record<string, unknown>,
) => Promise<unknown>;

export async function checkAuthRateLimit(
  scope: AuthScope,
  identity: string,
  rpcCall: RpcCall = rpc,
  secret: string | undefined =
    Deno.env.get("AUTH_RATE_LIMIT_SECRET"),
): Promise<RateLimitDecision> {
  if (scope !== "LOGIN" && scope !== "ACTIVATE") {
    throw new Error("RATE_LIMIT_INVALID_SCOPE");
  }

  if (!identity || identity.length > 256) {
    throw new Error("RATE_LIMIT_INVALID_IDENTITY");
  }

  if (!secret || secret.length < 32) {
    throw new Error("RATE_LIMIT_SECRET_MISSING");
  }

  const encoder = new TextEncoder();

  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    {
      name: "HMAC",
      hash: "SHA-256",
    },
    false,
    ["sign"],
  );

  const material = encoder.encode(
    `paiddue-auth-v1:${scope}:${identity}`,
  );

  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    material,
  );

  const bucketKey = Array.from(
    new Uint8Array(signature),
  ).map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  const result = await rpcCall(
    "check_auth_rate_limit",
    {
      p_scope: scope,
      p_bucket_key: bucketKey,
    },
  );

  if (
    !Array.isArray(result) ||
    result.length !== 1 ||
    typeof result[0]?.allowed !== "boolean" ||
    !Number.isInteger(result[0]?.retry_after_seconds) ||
    result[0].retry_after_seconds < 0
  ) {
    throw new Error("RATE_LIMIT_RESULT_INVALID");
  }

  return {
    allowed: result[0].allowed,
    retryAfterSeconds:
      result[0].retry_after_seconds,
  };
}
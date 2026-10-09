const encoder = new TextEncoder();

const TOKEN_REGEX =
  /^[A-Za-z0-9_-]{32,128}$/;

const PIN_REGEX =
  /^[0-9]{6}$/;

const PHONE_REGEX =
  /^\+[1-9][0-9]{7,14}$/;

const PBKDF2_ITERATIONS =
  100000;

const PBKDF2_BITS =
  256;

const SALT_BYTES =
  16;


function json(
  body,
  status = 200,
  extraHeaders = {},
) {
  return new Response(
    JSON.stringify(body),
    {
      status,
      headers: {
        "Content-Type":
          "application/json; charset=utf-8",

        "Cache-Control":
          "no-store",

        "X-Content-Type-Options":
          "nosniff",

        ...extraHeaders,
      },
    },
  );
}


function bytesToHex(bytes) {

  return Array.from(bytes)
    .map(
      (value) =>
        value
          .toString(16)
          .padStart(2, "0"),
    )
    .join("");
}


async function sha256Hex(value) {

  const digest =
    await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(value),
    );

  return bytesToHex(
    new Uint8Array(digest),
  );
}


async function importPepper(
  pepper,
) {

  return crypto.subtle.importKey(
    "raw",

    encoder.encode(
      pepper,
    ),

    {
      name:
        "HMAC",

      hash:
        "SHA-256",
    },

    false,

    [
      "sign",
    ],
  );
}


async function deterministicSalt(
  pepper,
  tokenHash,
) {

  if (
    typeof pepper !== "string" ||
    pepper.length < 32
  ) {

    throw new Error(
      "PIN_PEPPER_INVALID",
    );
  }


  const pepperKey =
    await importPepper(
      pepper,
    );


  const material =
    await crypto.subtle.sign(
      "HMAC",

      pepperKey,

      encoder.encode(
        `paiddue-cashier-salt-v2|${tokenHash}`,
      ),
    );


  return new Uint8Array(
    material,
  ).slice(
    0,
    SALT_BYTES,
  );
}


/*
 * IMPORTANT:
 *
 * PBKDF2 input is the actual Cashier PIN.
 *
 * This deliberately matches Android PinHasher:
 *
 *   PBKDF2WithHmacSHA256(
 *       PIN,
 *       salt,
 *       100000,
 *       256 bits
 *   )
 *
 * We do NOT HMAC-transform the PIN before PBKDF2.
 */
async function deriveCredential(
  pin,
  tokenHash,
  pepper,
) {

  const salt =
    await deterministicSalt(
      pepper,
      tokenHash,
    );


  const key =
    await crypto.subtle.importKey(
      "raw",

      encoder.encode(
        pin,
      ),

      "PBKDF2",

      false,

      [
        "deriveBits",
      ],
    );


  const derived =
    await crypto.subtle.deriveBits(
      {
        name:
          "PBKDF2",

        hash:
          "SHA-256",

        salt,

        iterations:
          PBKDF2_ITERATIONS,
      },

      key,

      PBKDF2_BITS,
    );


  return {

    credentialHash:
      `pbkdf2-sha256:${PBKDF2_ITERATIONS}:` +
      bytesToHex(
        new Uint8Array(
          derived,
        ),
      ),

    credentialSalt:
      bytesToHex(
        salt,
      ),
  };
}


/*
 * Fresh Paid&Due backend contract:
 *
 * SUPABASE_SECRET_KEY MUST be a modern sb_secret_ key.
 *
 * It is sent only through the apikey header.
 *
 * It is never sent as Authorization: Bearer.
 */
function supabaseHeaders(
  secretKey,
) {

  if (
    typeof secretKey !== "string" ||
    !secretKey.startsWith(
      "sb_secret_",
    ) ||
    secretKey.length < 20
  ) {

    throw new Error(
      "SUPABASE_SECRET_INVALID",
    );
  }


  return {

    "apikey":
      secretKey,

    "Content-Type":
      "application/json",

    "Accept":
      "application/json",
  };
}


async function rpc(
  env,
  name,
  parameters,
) {

  if (
    typeof env.SUPABASE_URL !== "string" ||
    !env.SUPABASE_URL.startsWith(
      "https://",
    )
  ) {

    throw new Error(
      "SUPABASE_URL_INVALID",
    );
  }


  const response =
    await fetch(
      `${env.SUPABASE_URL}/rest/v1/rpc/${name}`,

      {
        method:
          "POST",

        headers:
          supabaseHeaders(
            env.SUPABASE_SECRET_KEY,
          ),

        body:
          JSON.stringify(
            parameters,
          ),
      },
    );


  const raw =
    await response.text();


  if (!response.ok) {

    let code =
      "UNKNOWN";

    let message =
      "UNKNOWN";


    if (raw) {

      try {

        const parsed =
          JSON.parse(raw);


        if (
          typeof parsed?.code ===
          "string"
        ) {

          code =
            parsed.code;
        }


        if (
          typeof parsed?.message ===
          "string"
        ) {

          message =
            parsed.message
              .replace(
                /[A-Fa-f0-9]{32,}/g,
                "[REDACTED]",
              )
              .slice(
                0,
                250,
              );
        }

      }
      catch {

        code =
          "NON_JSON";

        message =
          "NON_JSON_RESPONSE";
      }
    }


    console.error(
      "PAIDDUE_RPC_FAILURE",
      name,
      response.status,
      code,
      message,
    );


    throw new Error(
      `RPC_${name}_${response.status}`,
    );
  }


  if (!raw) {

    throw new Error(
      `RPC_${name}_EMPTY_RESPONSE`,
    );
  }


  try {

    return JSON.parse(
      raw,
    );

  }
  catch {

    throw new Error(
      `RPC_${name}_INVALID_JSON`,
    );
  }
}


function safeActivation(
  row,
) {

  return {

    outcome:
      row.outcome,

    staff_identity_id:
      row.staff_identity_id ??
      null,

    membership_id:
      row.membership_id ??
      null,

    local_user_id:
      row.local_user_id ??
      null,

    business_id:
      row.business_id ??
      null,

    business_name:
      row.business_name ??
      null,

    branch_id:
      row.branch_id ??
      null,

    branch_name:
      row.branch_name ??
      null,

    branch_code:
      row.branch_code ??
      null,

    country_code:
      row.country_code ??
      null,

    currency_code:
      row.currency_code ??
      null,

    currency_decimal_places:
      row.currency_decimal_places ??
      null,

    currency_symbol:
      row.currency_symbol ??
      null,

    timezone_id:
      row.timezone_id ??
      null,

    tax_rounding_mode:
      row.tax_rounding_mode ??
      null,

    default_tax_rate_bps:
      row.default_tax_rate_bps ??
      null,

    branch_address:
      row.branch_address ??
      null,

    branch_phone:
      row.branch_phone ??
      null,

    tax_registration_number:
      row.tax_registration_number ??
      null,

    display_name:
      row.display_name ??
      null,

    canonical_phone:
      row.canonical_phone ??
      null,

    role:
      row.role ??
      null,

    permissions:
      row.permissions ??
      null,

    activated_at:
      row.activated_at ??
      null,
  };
}


async function activateStaff(
  request,
  env,
) {

  if (
    request.method !==
    "POST"
  ) {

    return json(
      {
        outcome:
          "METHOD_NOT_ALLOWED",
      },

      405,

      {
        "Allow":
          "POST",
      },
    );
  }


  const contentType =
    request.headers.get(
      "content-type",
    ) ?? "";


  if (
    !contentType
      .toLowerCase()
      .startsWith(
        "application/json",
      )
  ) {

    return json(
      {
        outcome:
          "INVALID_REQUEST",
      },

      400,
    );
  }


  const raw =
    await request.text();


  if (
    raw.length === 0 ||
    raw.length > 4096
  ) {

    return json(
      {
        outcome:
          "INVALID_REQUEST",
      },

      400,
    );
  }


  let body;


  try {

    body =
      JSON.parse(raw);

  }
  catch {

    return json(
      {
        outcome:
          "INVALID_REQUEST",
      },

      400,
    );
  }


  const token =
    typeof body.token ===
      "string"
      ? body.token
      : "";


  const pin =
    typeof body.pin ===
      "string"
      ? body.pin
      : "";


  /*
   * canonical_phone is validated as part of the Android/Worker
   * request contract, but it is NOT authoritative identity input.
   *
   * Supabase activate_staff_invite owns the Cashier identity and
   * returns the authoritative phone from the invitation.
   */
  const canonicalPhone =
    typeof body.canonical_phone ===
      "string"
      ? body.canonical_phone
      : "";


  if (
    !TOKEN_REGEX.test(
      token,
    ) ||

    !PIN_REGEX.test(
      pin,
    ) ||

    !PHONE_REGEX.test(
      canonicalPhone,
    )
  ) {

    return json(
      {
        outcome:
          "INVALID_REQUEST",
      },

      400,
    );
  }


  const tokenHash =
    await sha256Hex(
      token,
    );


  const rate =
    await env
      .ACTIVATION_RATE_LIMITER
      .limit({
        key:
          tokenHash,
      });


  if (!rate.success) {

    return json(
      {
        outcome:
          "TRY_AGAIN_LATER",
      },

      429,

      {
        "Retry-After":
          "60",
      },
    );
  }


  const credential =
    await deriveCredential(
      pin,
      tokenHash,
      env.CASHIER_PIN_PEPPER,
    );


  const result =
    await rpc(
      env,

      "activate_staff_invite",

      {
        p_token_hash:
          tokenHash,

        p_credential_hash:
          credential
            .credentialHash,

        p_credential_salt:
          credential
            .credentialSalt,
      },
    );


  if (
    !Array.isArray(
      result,
    ) ||
    result.length !== 1
  ) {

    throw new Error(
      "ACTIVATION_RESULT_INVALID",
    );
  }


  const row =
    result[0];


  switch (
    row.outcome
  ) {

    case "ACTIVATED":

    case "ALREADY_ACTIVATED":

      return json(
        safeActivation(
          row,
        ),
        200,
      );


    case "PHONE_ALREADY_REGISTERED":

      return json(
        {
          outcome:
            "PHONE_ALREADY_REGISTERED",
        },

        409,
      );


    case "INVALID_INVITE":

      return json(
        {
          outcome:
            "INVALID_INVITE",
        },

        400,
      );


    default:

      throw new Error(
        "UNKNOWN_ACTIVATION_RESULT",
      );
  }
}


async function previewStaff(request, env) {
  if (request.method !== "POST") {
    return json({ outcome: "METHOD_NOT_ALLOWED" }, 405, { "Allow": "POST" });
  }

  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const raw = await request.text();
  if (raw.length === 0 || raw.length > 1024) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const token = typeof body.token === "string" ? body.token : "";
  if (!TOKEN_REGEX.test(token)) {
    return json({ outcome: "INVALID_REQUEST" }, 400);
  }

  const tokenHash = await sha256Hex(token);
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";

  /*
   * Reuses the existing rate-limit binding with distinct key prefixes, so no
   * new Cloudflare configuration is needed. Both the caller and the token are
   * limited.
   */
  const byIp = await env.ACTIVATION_RATE_LIMITER.limit({ key: `preview-ip:${ip}` });
  const byToken = await env.ACTIVATION_RATE_LIMITER.limit({ key: `preview-token:${tokenHash}` });
  if (!byIp.success || !byToken.success) {
    return json({ outcome: "TRY_AGAIN_LATER" }, 429, { "Retry-After": "60" });
  }

  const result = await rpc(env, "preview_staff_invite", { p_token_hash: tokenHash });
  if (!Array.isArray(result) || result.length === 0) {
    return json({ outcome: "INVALID_INVITE" }, 404);
  }

  const row = result[0];
  return json({
    outcome: "OK",
    business_id: row.business_id ?? null,
    business_name: row.business_name ?? null,
    branch_id: row.branch_id ?? null,
    branch_name: row.branch_name ?? null,
    display_name: row.display_name ?? null,
    canonical_phone: row.canonical_phone ?? null,
    role: row.role ?? null,
    permissions: row.permissions ?? null,
    expires_at: row.expires_at ?? null,
  }, 200);
}


function health() {

  return json(
    {
      service:
        "Paid&Due",

      status:
        "OK",

      backend_version:
        "2",

      credential_format:
        "pbkdf2-sha256",

      pbkdf2_iterations:
        PBKDF2_ITERATIONS,
    },

    200,
  );
}


export default {

  async fetch(
    request,
    env,
  ) {

    const url =
      new URL(
        request.url,
      );


    try {

      if (
        url.pathname ===
        "/api/health"
      ) {

        if (
          request.method !==
          "GET"
        ) {

          return json(
            {
              outcome:
                "METHOD_NOT_ALLOWED",
            },

            405,

            {
              "Allow":
                "GET",
            },
          );
        }


        return health();
      }


      if (
        url.pathname ===
        "/api/staff/activate"
      ) {

        return await activateStaff(
          request,
          env,
        );
      }


      if (
        url.pathname ===
        "/api/staff/preview"
      ) {

        return await previewStaff(
          request,
          env,
        );
      }


      return json(
        {
          outcome:
            "NOT_FOUND",
        },

        404,
      );

    }
    catch (error) {

      console.error(
        "PAIDDUE_WORKER_FAILURE",

        error instanceof Error
          ? error.message
          : "UNKNOWN_ERROR",
      );


      return json(
        {
          outcome:
            "ACTIVATION_UNAVAILABLE",
        },

        503,
      );
    }
  },
};
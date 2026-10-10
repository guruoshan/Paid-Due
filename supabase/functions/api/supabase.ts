export function supabaseHeaders(secretKey: string) {
  if (typeof secretKey !== "string" || !secretKey.startsWith("sb_secret_") && !secretKey.startsWith("ey")) {
    // Edge functions locally use Anon/Service keys which start with 'ey'. 
    // Worker used 'sb_secret_'. We accept both.
    throw new Error("SUPABASE_SECRET_INVALID");
  }
  return {
    "apikey": secretKey,
    "Authorization": `Bearer ${secretKey}`,
    "Content-Type": "application/json",
    "Accept": "application/json",
  };
}

export async function rpc(name: string, parameters: any): Promise<any> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

  if (!supabaseUrl || !supabaseUrl.startsWith("http")) {
    throw new Error("SUPABASE_URL_INVALID");
  }
  if (!supabaseKey) {
    throw new Error("SUPABASE_KEY_MISSING");
  }

  const response = await fetch(`${supabaseUrl}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: supabaseHeaders(supabaseKey),
    body: JSON.stringify(parameters),
  });

  const raw = await response.text();

  if (!response.ok) {
    let code = "UNKNOWN";
    let message = "UNKNOWN";
    if (raw) {
      try {
        const parsed = JSON.parse(raw);
        if (typeof parsed?.code === "string") code = parsed.code;
        if (typeof parsed?.message === "string") {
          message = parsed.message
            .replace(/[A-Fa-f0-9]{32,}/g, "[REDACTED]")
            .slice(0, 250);
        }
      } catch {
        code = "NON_JSON";
        message = "NON_JSON_RESPONSE";
      }
    }
    const err = new Error(message || "RPC_FAILED");
    (err as any).status = response.status;
    (err as any).outcome = message;
    throw err;
  }

  if (!raw) {
    throw new Error(`RPC_${name}_EMPTY_RESPONSE`);
  }

  try {
    return JSON.parse(raw);
  } catch {
    throw new Error(`RPC_${name}_INVALID_JSON`);
  }
}

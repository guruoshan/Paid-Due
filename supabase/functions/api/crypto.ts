const encoder = new TextEncoder();

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

export function hexToBytes(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return bytes;
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return bytesToHex(new Uint8Array(digest));
}

export async function importPepper(pepper: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(pepper),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
}

export async function deterministicSalt(pepper: string, tokenHash: string): Promise<Uint8Array> {
  if (typeof pepper !== "string" || pepper.length < 32) {
    throw new Error("PIN_PEPPER_INVALID");
  }
  const pepperKey = await importPepper(pepper);
  const material = await crypto.subtle.sign(
    "HMAC",
    pepperKey,
    encoder.encode(`paiddue-cashier-salt-v2|${tokenHash}`)
  );
  return new Uint8Array(material).slice(0, 16); // SALT_BYTES
}

export async function deriveCredential(pin: string, tokenHash: string, pepper: string) {
  const PBKDF2_ITERATIONS = 100000;
  const PBKDF2_BITS = 256;
  const salt = await deterministicSalt(pepper, tokenHash);

  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(pin),
    "PBKDF2",
    false,
    ["deriveBits"]
  );

  const derived = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: salt.buffer as ArrayBuffer, iterations: PBKDF2_ITERATIONS },
    key,
    PBKDF2_BITS
  );

  return {
    credentialHash: `pbkdf2-sha256:${PBKDF2_ITERATIONS}:` + bytesToHex(new Uint8Array(derived)),
    credentialSalt: bytesToHex(salt),
  };
}

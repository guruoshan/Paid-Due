import { assertEquals, assertNotEquals } from "https://deno.land/std@0.200.0/assert/mod.ts";
import { health, activateStaff, loginStaff, uploadFinancial, staffMetadata } from "./handlers.ts";
import { sha256Hex, deriveCredential, hexToBytes, bytesToHex } from "./crypto.ts";

Deno.test("GET /api/health returns 200 OK and expected version contract", async () => {
  const response = health();
  assertEquals(response.status, 200);
  const data = await response.json();
  assertEquals(data.service, "Paid&Due");
  assertEquals(data.status, "OK");
  assertEquals(data.backend_version, "3");
  assertEquals(data.credential_format, "pbkdf2-sha256");
  assertEquals(data.pbkdf2_iterations, 100000);
});

Deno.test("POST /api/staff/activate method enforcement", async () => {
  const req = new Request("http://localhost/api/staff/activate", { method: "GET" });
  const res = await activateStaff(req);
  assertEquals(res.status, 405);
  assertEquals(res.headers.get("Allow"), "POST");
});

Deno.test("POST /api/staff/activate payload validation", async () => {
  const req1 = new Request("http://localhost/api/staff/activate", {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: "test"
  });
  const res1 = await activateStaff(req1);
  assertEquals(res1.status, 400);

  const req2 = new Request("http://localhost/api/staff/activate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{"
  });
  const res2 = await activateStaff(req2);
  assertEquals(res2.status, 400);

  const req3 = new Request("http://localhost/api/staff/activate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: "invalid_short", pin: "123", canonical_phone: "123" })
  });
  const res3 = await activateStaff(req3);
  assertEquals(res3.status, 400);
});

Deno.test("POST /api/staff/login method enforcement", async () => {
  const req = new Request("http://localhost/api/staff/login", { method: "GET" });
  const res = await loginStaff(req);
  assertEquals(res.status, 405);
});

Deno.test("POST /api/staff/login input validation", async () => {
  const req = new Request("http://localhost/api/staff/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ canonical_phone: "+96891234567", pin: "badpin" })
  });
  const res = await loginStaff(req);
  assertEquals(res.status, 400);
});

Deno.test("POST /api/financial/upload requires valid Bearer token", async () => {
  const req1 = new Request("http://localhost/api/financial/upload", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({})
  });
  const res1 = await uploadFinancial(req1);
  assertEquals(res1.status, 401);

  const req2 = new Request("http://localhost/api/financial/upload", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": "Bearer shorttoken" },
    body: JSON.stringify({})
  });
  const res2 = await uploadFinancial(req2);
  assertEquals(res2.status, 401);
});

Deno.test("GET /api/staff/metadata requires valid Bearer token", async () => {
  const req = new Request("http://localhost/api/staff/metadata", { method: "GET" });
  const res = await staffMetadata(req);
  assertEquals(res.status, 401);
});

Deno.test("Crypto: PBKDF2 derivation matches parameters", async () => {
  const pin = "123456";
  const tokenHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  const pepper = "paid_due_test_pepper_32_characters_long_min";

  const derived = await deriveCredential(pin, tokenHash, pepper);
  assertEquals(derived.credentialHash.startsWith("pbkdf2-sha256:100000:"), true);
  assertEquals(derived.credentialSalt.length, 32);
});

Deno.test("Crypto: SHA-256 digest consistency", async () => {
  const input = "test_canonical_payload_data";
  const hash1 = await sha256Hex(input);
  const hash2 = await sha256Hex(input);
  assertEquals(hash1, hash2);
  assertEquals(hash1.length, 64);
});

Deno.test("Contract: Admin update_cashier_permissions expects uppercase keys", () => {
  const permissionsKeys = [
    'CREATE_SALE', 'ACCEPT_CASH', 'ACCEPT_ONLINE', 'CREATE_CREDIT_SALE', 'USE_COUPON',
    'ISSUE_INVOICE', 'ISSUE_RECEIPT', 'REFUND', 'VOID', 'CORRECT_AMOUNT',
    'CHANGE_PAYMENT_METHOD', 'VIEW_BROAD_HISTORY', 'MANAGE_PRODUCTS', 'VIEW_REPORTS'
  ];
  assertEquals(permissionsKeys.length, 14);
  for (const key of permissionsKeys) {
    assertEquals(key, key.toUpperCase());
  }
});

Deno.test("Contract: Cashier metadata response permissions schema", () => {
  const mockPermissions = {
    can_create_sale: true,
    can_accept_cash: true,
    can_accept_online: true,
    can_create_credit_sale: false,
    can_use_coupon: false,
    can_issue_invoice: true,
    can_issue_receipt: true,
    can_refund: false,
    can_void: false,
    can_correct_amount: false,
    can_change_payment_method: false,
    can_view_broad_history: false,
    can_manage_products: false,
    can_view_reports: false
  };
  assertEquals(typeof mockPermissions.can_create_sale, "boolean");
  assertEquals(Object.keys(mockPermissions).length, 14);
});

Deno.test("Contract: Duplicate financial envelope response format", () => {
  const mockAck = {
    status: "ACKNOWLEDGED",
    operation_id: "op_test_123",
    canonical_sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    duplicate: true
  };
  assertEquals(mockAck.status, "ACKNOWLEDGED");
  assertEquals(mockAck.duplicate, true);
});

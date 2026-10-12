import { assertEquals, assertThrows } from "jsr:@std/assert";
import { buildStaffInviteUrl, STAFF_INVITE_LANDING_PREFIX } from "./invite-link.ts";

Deno.test("builds the canonical GitHub Pages invite URL", () => {
  const token = "A".repeat(43);
  assertEquals(
    buildStaffInviteUrl(token),
    STAFF_INVITE_LANDING_PREFIX + token,
  );
});

Deno.test("rejects malformed tokens before creating a URL", () => {
  assertThrows(() => buildStaffInviteUrl("bad-token"), Error, "INVALID_STAFF_INVITE_TOKEN");
});

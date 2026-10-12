import { buildStaffInviteUrl, STAFF_INVITE_LANDING_PREFIX } from "./invite-link.ts";

Deno.test("builds the canonical GitHub Pages invite URL", () => {
  const token = "A".repeat(43);
  if (buildStaffInviteUrl(token) !== STAFF_INVITE_LANDING_PREFIX + token) {
    throw new Error("Invite URL does not match the canonical landing page");
  }
});

Deno.test("rejects malformed tokens before creating a URL", () => {
  let rejected = false;
  try {
    buildStaffInviteUrl("bad-token");
  } catch (error) {
    rejected = error instanceof Error && error.message === "INVALID_STAFF_INVITE_TOKEN";
  }
  if (!rejected) throw new Error("Malformed invite token was accepted");
});

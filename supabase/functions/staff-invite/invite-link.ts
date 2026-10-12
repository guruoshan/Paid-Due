/**
 * Public URL contract shared by staff-invite implementation and its tests.
 * Keep aligned with app/.../StaffInviteLinkContract.kt.
 */
export const STAFF_INVITE_LANDING_PREFIX =
  "https://guruoshan.github.io/Paid-Due/invite/#";

const TOKEN_REGEX = /^[A-Za-z0-9_-]{32,128}$/;

export function buildStaffInviteUrl(token: string): string {
  if (!TOKEN_REGEX.test(token)) {
    throw new Error("INVALID_STAFF_INVITE_TOKEN");
  }
  // Tokens are URL-safe base64url; keep them in the fragment so they are not
  // sent to the static host in HTTP requests or server access logs.
  return STAFF_INVITE_LANDING_PREFIX + token;
}

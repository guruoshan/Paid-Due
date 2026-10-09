/*
 * Paid&Due public invite landing-page host.
 *
 * Backend API routes have moved to Supabase Edge Functions:
 * https://ttvdpgxqvvtjnxxfkoui.supabase.co/functions/v1/api
 *
 * Static files (including /open/invite/) are served by the Cloudflare
 * Workers Assets binding before this fallback. No authentication,
 * activation, login, or financial API is implemented in this Worker.
 */
export default {
  async fetch(request) {
    const url = new URL(request.url);
    console.warn("PAIDDUE_API_MOVED_TO_SUPABASE", url.pathname);
    return new Response(JSON.stringify({ outcome: "NOT_FOUND" }), {
      status: 404,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
      },
    });
  },
};

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { linkOnly } from "../src/auth";
import type { Env } from "../src/env";
import { BASE, LINK_ID, ORIGIN, bearer, call, docBody, login, uid, uniqueIp } from "./helpers";

/** NOTES_ACCESS = "link": the private link alone opens the vault. No password secret needed. */
const open: Env = { ...env, NOTES_ACCESS: "link", NOTES_PASSWORD: "" };

function put(path: string, body: unknown, headers: Record<string, string> = {}, e: Env = open): Promise<Response> {
  return call(path, { method: "PUT", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify(body), env: e });
}

describe("link-only access", () => {
  it("is on only for NOTES_ACCESS = link", () => {
    expect(linkOnly({ ...env, NOTES_ACCESS: "link" })).toBe(true);
    expect(linkOnly({ ...env, NOTES_ACCESS: " Link " })).toBe(true);
    expect(linkOnly({ ...env, NOTES_ACCESS: "password" })).toBe(false);
    expect(linkOnly({ ...env, NOTES_ACCESS: "" })).toBe(false);
    expect(linkOnly({ ...env, NOTES_ACCESS: undefined })).toBe(false);
    expect(linkOnly({ ...env, NOTES_ACCESS: "links" })).toBe(false);
  });

  it("the page opens straight to the notes, with nothing to unlock or lock", async () => {
    const res = await call(BASE, { env: open });
    expect(res.status).toBe(200);
    const html = await res.text();
    expect(html).toContain('<body data-signed-in="true">');
    expect(html).toContain('<main id="login-view" class="login" hidden>');
    expect(html).toContain('<button id="lock-btn" class="btn" type="button" hidden>Lock</button>');
    expect(res.headers.get("Set-Cookie")).toBeNull();
    expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
  });

  it("the API answers without any login", async () => {
    const ping = await call("/api/ping", { env: open });
    expect(ping.status).toBe(200);
    expect(((await ping.json()) as { ok: boolean }).ok).toBe(true);

    const id = uid();
    expect((await put(`/api/docs/${id}`, docBody({ title: "Link only" }))).status).toBe(200);
    const list = await call("/api/docs", { env: open });
    expect(list.status).toBe(200);
    const docs = ((await list.json()) as { docs: { id: string; title: string }[] }).docs;
    expect(docs.find((d) => d.id === id)?.title).toBe("Link only");
  });

  it("an app that still logs in gets a token whatever it sends, and its token is ignored", async () => {
    for (const body of [JSON.stringify({ password: "anything at all" }), JSON.stringify({ password: "" }), "{", ""]) {
      const res = await call("/api/session", { method: "POST", headers: { "CF-Connecting-IP": uniqueIp() }, body, env: open });
      expect(res.status).toBe(200);
      const data = (await res.json()) as { token: string; expiresAt: number };
      expect(data.token).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
      expect(data.expiresAt).toBeGreaterThan(Date.now());
      expect(res.headers.get("Set-Cookie")).toBeNull();
    }
    expect((await call("/api/ping", { headers: bearer("stale.token"), env: open })).status).toBe(200);
    expect((await put(`/api/docs/${uid()}`, docBody(), bearer("garbage"))).status).toBe(200);
    const logout = await call("/api/logout", { method: "POST", env: open });
    expect(logout.status).toBe(200);
  });

  it("a wrong link is still a plain 404", async () => {
    for (const path of ["/notes/wrong-link-id", "/notes/wrong-link-id/api/ping", `/notes/${LINK_ID}x/api/docs`]) {
      const res = await call(ORIGIN + path, { env: open });
      expect(res.status).toBe(404);
      expect((await res.text()).trim()).toBe("Not Found");
    }
  });

  it("another site's page cannot change anything", async () => {
    const foreign = { Origin: "https://evil.example" };
    const res = await put(`/api/docs/${uid()}`, docBody(), foreign);
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ error: "bad_origin" });
    expect((await call("/api/session", { method: "POST", headers: foreign, body: "{}", env: open })).status).toBe(403);
    // Our own page, and reads from anywhere, are fine.
    expect((await put(`/api/docs/${uid()}`, docBody(), { Origin: ORIGIN })).status).toBe(200);
    expect((await call("/api/docs", { headers: foreign, env: open })).status).toBe(200);
  });

  it("still needs the session secret (503 without it)", async () => {
    const res = await call("/api/ping", { env: { ...open, SESSION_SECRET: "" } });
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: "not_configured" });
  });
});

describe("password access stays the default", () => {
  it("without NOTES_ACCESS the API needs a login and the page asks for the password", async () => {
    const e: Env = { ...env, NOTES_ACCESS: undefined };
    expect((await call("/api/ping", { env: e })).status).toBe(401);
    const html = await (await call(BASE, { env: e })).text();
    expect(html).toContain('<body data-signed-in="false">');
    expect(html).toContain('<button id="lock-btn" class="btn" type="button">Lock</button>');
  });

  it("a token minted while link-only does not open the vault once switched back", async () => {
    // Link mode with the password secret still set: the pv must still differ.
    const wasOpen: Env = { ...env, NOTES_ACCESS: "link" };
    const res = await call("/api/session", { method: "POST", body: "{}", env: wasOpen });
    expect(res.status).toBe(200);
    const { token } = (await res.json()) as { token: string };
    const e: Env = { ...env, NOTES_ACCESS: "password" };
    expect((await call("/api/ping", { headers: bearer(token), env: e })).status).toBe(401);
    const html = await (await call(BASE, { headers: { Cookie: `nv_session=${token}` }, env: e })).text();
    expect(html).toContain('<body data-signed-in="false">');
    // A token from a real login still works.
    const fresh = await login(e);
    expect((await call("/api/ping", { headers: bearer(fresh.token), env: e })).status).toBe(200);
  });

  it("without a password secret it is not configured, not open", async () => {
    const e: Env = { ...env, NOTES_ACCESS: "password", NOTES_PASSWORD: "" };
    const res = await call("/api/ping", { env: e });
    expect(res.status).toBe(503);
    const html = await (await call(BASE, { env: e })).text();
    expect(html).toContain('<body data-signed-in="false">');
  });
});

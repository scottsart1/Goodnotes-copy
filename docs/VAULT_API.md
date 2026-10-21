# Notes vault API (yourpitbox.com/notes)

The private notes vault is a Cloudflare Worker (`web/notes-vault`) mounted on
`yourpitbox.com/notes/*`. The Android app's `SyncManager` pushes notes, transcripts and
audio to it when the user picks "Your Pit Box · Notes vault" as a destination.

## Addressing

```
BASE = https://yourpitbox.com/notes/<LINK_ID>
```

`LINK_ID` is a long random secret (the "special link"). Any request whose path does not
start with `/notes/<LINK_ID>` gets a plain 404, indistinguishable from a missing page.

- **Link-only access** (`NOTES_ACCESS = "link"` in `wrangler.toml`, what this vault uses):
  that check is the whole of access control.
- **Password access:** the password is checked on top of the link.

Neither secret is stored in this repository: both are Worker secrets (`NOTES_LINK_ID`,
`NOTES_PASSWORD`).

The Worker never redirects, and the app does not follow redirects, so the bearer token cannot
travel to another server. The one exception is a site moving between `www.host` and `host`.
The zone sends `www.yourpitbox.com/*` to the apex with a 301. When a permanent redirect (301
or 308) keeps the scheme, port and path and only adds or drops a leading `www.`, the app
follows it once and saves the new link (`VaultRedirect`). It shows any other redirect as an
error.

## Authentication

**Link-only access.** No authentication. Every `/api/*` call works without a token. A
state-changing call (anything but GET, HEAD and OPTIONS) whose `Origin` is another site
gets `403 {"error":"bad_origin"}`. `POST {BASE}/api/session` still answers
`200 {"token", "expiresAt"}` for any body and sets no cookie, so older apps that log in keep
working; a token they send is ignored. The app calls the API without a token when no
password is saved. If such a call gets a 401, the app tells the user the vault asks for a
password.

**Password access:**

`POST {BASE}/api/session` with JSON `{"password": "..."}`

- `200 {"token": "<opaque>", "expiresAt": <epoch ms>}`, and a `Set-Cookie` (HttpOnly,
  Secure, SameSite=Strict, Path=/notes/<LINK_ID>) for the web UI.
- `401 {"error": "invalid_password"}`
- `429 {"error": "locked", "retryAfterSeconds": n}` after repeated failures (per IP, rolling).

Every other `/api/*` call needs `Authorization: Bearer <token>` (app) or the cookie (web).
Tokens are HMAC-signed, expire after 30 days, and are invalidated when the password changes
or when the vault is switched from link-only to password access.
`401 {"error":"unauthorized"}` means "log in again".

`POST {BASE}/api/logout` clears the cookie.

## Endpoints

| Method | Path | Body / result |
|---|---|---|
| GET | `/api/ping` | `{"ok":true,"name":"Your Pit Box · Notes"}` |
| GET | `/api/docs` | `{"docs":[DocSummary…]}` newest first; `?q=` filters by title/markdown/transcript text |
| GET | `/api/docs/{docId}` | `DocDetail` |
| PUT | `/api/docs/{docId}` | `DocUpsert` JSON → `{"ok":true}` (creates or replaces metadata + markdown) |
| DELETE | `/api/docs/{docId}` | removes the doc, its transcripts and files |
| PUT | `/api/docs/{docId}/transcripts/{recordingId}` | `TranscriptUpsert` JSON |
| PUT | `/api/docs/{docId}/files/{name}` | raw bytes, `Content-Type` set; ≤ 95 MB; `name` matches `[A-Za-z0-9._-]{1,120}` |
| GET | `/api/docs/{docId}/files/{name}` | the bytes (download) |
| DELETE | `/api/docs/{docId}/files/{name}` | |

```ts
type DocUpsert = {
  title: string;           // "20261015_Financial Markets"
  kind: "NOTEBOOK" | "PDF" | "WHITEBOARD" | "TYPED";
  createdAt: number;       // epoch ms (original creation)
  modifiedAt: number;
  folderPath?: string;     // "Economics / Term 1"
  pageCount?: number;
  tags?: string[];
  markdown?: string;       // recognised handwriting + typed + OCR text, ≤ 2 MB
};
type TranscriptUpsert = {
  title: string;
  startedAt: number;
  durationMs: number;
  source: string;          // "openai:whisper-1"
  segments: { startMs: number; endMs: number; text: string; speaker?: string | null }[];
};
type DocSummary = { id: string; title: string; kind: string; createdAt: number; modifiedAt: number;
                    folderPath: string | null; pageCount: number; transcriptCount: number; fileCount: number };
type DocDetail = DocSummary & { tags: string[]; markdown: string;
  transcripts: (TranscriptUpsert & { recordingId: string })[];
  files: { name: string; contentType: string; size: number; uploadedAt: number }[] };
```

`docId` is the app's document id (`[A-Za-z0-9-]{1,64}`). Uploads are idempotent PUTs, so the
app can retry safely. Files the app uploads by convention: `notes.pdf`, `notes.md`,
`rec_<recordingId>.m4a`.

## Storage

- Cloudflare D1 database `notes-vault`: tables `docs`, `transcripts`, `files`, `login_attempts`.
- Cloudflare R2 bucket `notes-vault-files`: file bodies at `<docId>/<name>`.

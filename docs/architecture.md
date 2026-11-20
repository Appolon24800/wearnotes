# WearNotes architecture

## Product shape

WearNotes models a note as a card rather than as a timeline entry. A card has
a title, Markdown body, optional checklist items, labels, a color, a pinned
state, archive and trash states, a reminder, and zero or more photos.

The Android app exposes the complete model. The watch app exposes only the
fast path:

- recent notes
- pinned notes
- note details
- new text note
- append text to an existing note
- archive and restore

The watch does not edit long Markdown documents. It can append short text and
create a new note with voice input.

## Server

The server is a Go binary. SQLite is the default database and the data
directory contains:

```text
/data/
  wearnotes.db
  attachments/
```

The binary serves REST and MCP from the same HTTPS origin. A reverse proxy
handles TLS. The server does not require a separate database or queue.

### Main tables

`notes` stores the current note state. `labels` stores user labels.
`note_labels` joins them. `attachments` stores image metadata and a relative
file path. `devices` stores hashed device tokens. `changes` stores the
append-only sync log.

Every change has a monotonically increasing integer cursor. A client sends its
cursor to `GET /v1/sync?after=123`. The response includes all changed records
and a new cursor. A deleted note is represented by a tombstone until the
retention window expires.

### Conflict rules

The API uses optimistic concurrency. A patch includes `ifVersion`. If it does
not match the current note version, the server returns `409` with the current
note. The Android client keeps both versions as a local conflict and offers a
keep-local or keep-server action. Watch writes use append operations where
possible, which avoids replacing an entire note from a small screen.

### API groups

```text
POST   /v1/auth/devices
GET    /v1/sync?after=<cursor>
GET    /v1/notes
POST   /v1/notes
GET    /v1/notes/:id
PATCH  /v1/notes/:id
DELETE /v1/notes/:id
POST   /v1/notes/:id/append
POST   /v1/notes/:id/archive
POST   /v1/notes/:id/restore
POST   /v1/notes/:id/trash
POST   /v1/notes/:id/attachments
GET    /v1/attachments/:id
GET    /v1/labels
POST   /v1/labels
PATCH  /v1/labels/:id
DELETE /v1/labels/:id
GET    /v1/search?q=<query>
GET    /healthz
POST   /mcp
```

The list endpoint supports `state=active|archived|trash`, `label`, `pinned`,
`updatedAfter`, `limit`, and `page`.

## MCP

MCP uses Streamable HTTP at `/mcp`. A personal access token is sent as a
Bearer token. Tools call the same service layer as REST:

```text
list_notes
search_notes
get_note
create_note
update_note
append_to_note
archive_note
restore_note
trash_note
delete_note
list_labels
create_label
update_label
delete_label
set_note_labels
upload_attachment
delete_attachment
```

Destructive tools are marked as destructive in their MCP annotations. Agents
cannot bypass note ownership or device permissions.

## Android design

The phone app uses a warm paper background with ink-blue primary actions and
small citrus accents for note colors. The app does not use a generic card
grid. Notes are arranged in a responsive two-column masonry layout on wider
phones and a single column on compact widths.

The main routes are:

```text
Home
  Active notes
  Pinned notes
  Labels
  Archive
  Trash
Search
Note editor
  Markdown text
  Checklist
  Photos
  Labels
  Color
  Reminder
Settings
```

Photo content uses Coil with a zoomable detail surface. The editor uses the
system photo picker and camera intent. Uploads are queued in WorkManager.

## Wear OS design

The watch app uses a dark ink surface and compact rounded list rows. The main
screen is intentionally close to Keep's watch flow:

```text
Recent
  Search
  New note
  Note row
  Note row
  Note row
```

The Galaxy Watch 6 Classic 47 mm has a round 480 by 480 display. The layout
keeps a 16 dp safe inset, uses a 225 dp content box, and avoids controls near
the top and bottom curves. A note detail screen uses large type and one
primary action per row.

The watch asks the server directly for changes. When the phone is nearby, the
client may use the Wear Data Layer as a transport optimization, but it never
becomes a requirement.

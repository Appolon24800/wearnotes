<img src="assets/icon.svg" width="64" height="64" alt="">

# WearNotes

WearNotes is a private, self-hosted Google Keep alternative for Android and
standalone Wear OS devices.

The project has three parts:

- `server`: a small Go service with SQLite, REST, cursor sync, attachments,
  and MCP.
- `android`: the full Material 3 phone application.
- `wear`: the small Wear OS companion that can sync directly over Wi-Fi.

The server is designed to run as one Docker container. It stores note bodies
as Markdown and keeps images as files in the same data directory.

## Product decisions

- The server is the source of truth.
- Each client keeps a local Room cache.
- Every write creates a change record.
- Clients request only changes after their last cursor.
- A watch can read and write without the phone.
- Images are uploaded separately from note metadata.
- Agents use the same permissions as normal clients through `/mcp`.

## Start the server

```bash
docker compose up -d
```

The API is available at `http://localhost:8080`. Set `WEARNOTES_PUBLIC_URL`
and put the service behind HTTPS before connecting a watch or exposing it to
the internet.

## Releases

Push a version tag to publish both debug APKs as a GitHub release and publish
the server image to GHCR:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The image is available at:

```text
ghcr.io/appolon24800/wearnotes:latest
```

Version tags also publish a matching image tag.

## Android build

The Android project targets API 35 and uses Kotlin, Jetpack Compose,
Material 3, Room, WorkManager, Coil, and Compose for Wear OS. The first
implementation targets a 412 dp phone content width, which fits the Pixel 10a
class of devices, and a 225 dp round Wear OS content box for the Galaxy Watch
6 Classic 47 mm.

## Current status

The repository now contains the initial server contract, SQLite store,
Docker deployment, and Compose application shells. The next slice is the
end-to-end note flow: create a note on Android, sync it to the server, then
read it from the watch over Wi-Fi.

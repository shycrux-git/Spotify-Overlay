# Spotify Overlay

Fabric mod for Minecraft **26.2** — a Skija media HUD showing the currently playing Spotify track (cover, title, artist, progress, synced lyrics).

## How it works

- **Now playing:** Windows SMTC (Spotify desktop session)
- **Lyrics:** Musixmatch, with LRCLIB fallback
- **Artwork / multi-artist credits:** iTunes / Deezer lookup
- **Rendering:** HumbleUI Skija over Minecraft’s picture-in-picture path — works with **OpenGL** and **Vulkan**

No Spotify Client ID or browser cookies required.

## Requirements

- Windows 10/11
- Minecraft 26.2 + Fabric Loader `>=0.19.3`, Fabric API, Fabric Language Kotlin
- Spotify desktop app with an active media session

## Setup

1. Install the mod and run Minecraft
2. Play a track in Spotify
3. Toggle with `O` (lyrics with `L`)

Optional layout in `config/spotify-overlay.json`:

```json
{
  "overlayEnabled": true,
  "showLyrics": true,
  "overlayX": 14.0,
  "overlayY": -16.0,
  "overlayScale": 1.0
}
```

Negative `overlayX` / `overlayY` anchor from the right / bottom.

## Controls

| Binding | Default | Action |
|---------|---------|--------|
| Toggle Overlay | `O` | Show / hide |
| Toggle Lyrics | `L` | Show / hide lyrics |
| Chat open + drag | LMB | Move overlay |
| Chat open + scroll | Wheel | Scale overlay |

## Commands

```
/spotify
/spotify reload
```

## Development

JDK **25** required.

```bash
./gradlew runClient
./gradlew build
```

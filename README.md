# Sulav Proxy — Android App

Native Android/Jetpack Compose local debugging proxy.

## What it does

Sulav Proxy is a small forwarding proxy you run on-device:

- It **listens** on a Host/Port you choose (default `127.0.0.1:8080`).
- It **forwards** every request it receives to the target URL you set.
- It relays the upstream response straight back to the caller.
- Each request/response pair is logged in the Traffic list (method, path,
  status code, and a hex preview of both sides).

Point your own app's API base URL at the listen address, and Sulav Proxy
will sit in the middle and show you exactly what's going over the wire —
the same idea as running Charles Proxy or mitmproxy locally, just packaged
as a small Android app. It does not perform TLS interception or automatic
credential/token extraction; it's a plain-text relay + logger for traffic
you control.

## UI

- **Forward requests to (target URL)** — where the proxy relays to.
- **Listen Host / Listen Port** — where the proxy accepts connections.
- **Start / Stop Proxy** — starts and stops the embedded server.
- **LocalConfig** — the JSON snippet other tools/apps can point at; **Export
  localconfig** saves it to a file you pick via the system file picker.
- **Traffic** — live list of exchanges, with an optional HEX view.

## Build

Open this folder in Android Studio (Giraffe+) and build the debug APK, or
build from the command line with a local Gradle install:

```
gradle assembleDebug
```

### Fixed in this pass

The original project pulled in Jetpack Compose but never enabled it and
never applied Kotlin's Compose compiler plugin — with Kotlin 2.0+, Compose
requires the `org.jetbrains.kotlin.plugin.compose` plugin plus
`buildFeatures.compose = true`, neither of which was present, so the module
would not have compiled. Both are now set in `build.gradle.kts` /
`app/build.gradle.kts`. The UI was also wired up to a real embedded proxy
server (`ProxyServer.kt`) instead of static sample data, cleartext HTTP was
allowed via a network security config (required for a plain HTTP debug
proxy on modern Android), and a launcher icon was added.

## GitHub Actions APK Build

The repository includes `.github/workflows/build-apk.yml`.
After pushing to GitHub, open **Actions → Build Sulav Proxy APK**. The
workflow builds the debug APK automatically and uploads
`SulavProxy-debug-apk` as a workflow artifact. You can also start it
manually with **Run workflow**.

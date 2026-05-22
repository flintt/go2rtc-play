# Go2RTC Play — Android WebView Multi-Camera Viewer

An Android app that displays multiple IP camera streams from a [go2rtc](https://github.com/AlexxIT/go2rtc) server in a grid layout inside a WebView.

## Features

- **Grid view** — all cameras visible simultaneously, auto-relayout on hide/show
- **MJPEG frame polling** — codec-independent; shows H.264, H.265, and MJPEG cameras
- **Persistent camera list** — remembers working cameras across restarts
- **Fullscreen playback** — WebView with go2rtc's adaptive streaming (WebRTC/MSE/HLS)
- **LAN auto-discovery** — scans subnet for go2rtc servers
- **D-pad navigation** — works with Android TV remotes
- **Auto-hide UI** — top bar fades after 3 seconds

## How it works

1. App connects to a go2rtc server (port 1984)
2. Fetches camera list via `/api/streams`
3. Renders a CSS Grid inside a WebView
4. Each camera polls `/api/frame.jpeg?src=name` every 5 seconds
5. Clicking a camera opens FullscreenActivity with `stream.html?src=name&mode=...`

## Build

```bash
git clone https://github.com/YOUR_USER/go2rtc-play
cd go2rtc-play
ANDROID_HOME=/path/to/android/sdk ./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android SDK (minSdk 26, targetSdk 35)
- go2rtc server accessible on the network
- For H.265 cameras on old TVs: server-side FFmpeg transcoding in `go2rtc.yaml`

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT

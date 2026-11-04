# Universal Remote (Wi-Fi)

An Android app that turns your phone into a TV remote over Wi-Fi. No IR blaster needed.

| TV | How it connects | First-time setup |
|---|---|---|
| Android TV / Google TV (Sony, Mi, OnePlus, TCL, Chromecast with Google TV…) | Android TV Remote protocol v2 (same as Google's own remote app) | TV shows a 6-character code → type it in the app |
| Samsung (Tizen, 2016+) | WebSocket, port 8002 | Choose **Allow** on the TV |
| LG (webOS) | WebSocket, port 3000 / 3001 | Accept the prompt on the TV |
| Roku | HTTP (ECP), port 8060 | Nothing |
| Amazon Fire TV | Network ADB, port 5555 | Turn on **ADB debugging**, then choose **Allow** on the TV |

Features: auto-discovery on the network, D-pad and swipe touchpad, volume/channel rockers (hold to repeat),
media keys, number pad, keyboard (type into TV search boxes), app launcher, "open link on TV"
(Android TV), Wake-on-LAN power-on for Samsung/LG, haptic feedback, dark UI.

---

## Get the APK without installing anything (GitHub, ~10 minutes)

1. Make a free account at github.com.
2. Click **+ → New repository**, name it `universal-remote`, choose **Private**, click **Create repository**.
3. On the new repo page click **uploading an existing file**, drag in **everything inside this folder**
   (including the `.github` folder), then click **Commit changes**.
   - On a Mac, hidden folders like `.github` don't show in Finder. Press `Cmd + Shift + .` to show them.
   - If the `.github` folder didn't upload: click **Add file → Create new file**, type the name
     `.github/workflows/build-apk.yml`, paste the contents of that file, and commit.
4. Open the **Actions** tab. A run called **Build APK** starts by itself (takes 5–8 minutes).
5. When it shows a green tick, open the run and scroll to **Artifacts**. Download
   **UniversalRemote-release** (smaller) or **UniversalRemote-debug**.
6. Unzip it, copy the `.apk` to your phone and open it. Allow "Install unknown apps" when asked.

If the release artifact is missing, use the debug one — it is the same app, just larger.

## Or build with Android Studio

Open this folder in Android Studio (Ladybug or newer), wait for Gradle sync, then
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

---

## TV settings that matter

- **Same Wi-Fi**: phone and TV must be on the same network (guest networks often block this).
- **Fire TV**: Settings → My Fire TV → Developer options → **ADB debugging: ON**.
  If Developer options is missing: Settings → My Fire TV → About → click the device name 7 times.
- **Roku**: Settings → System → Advanced system settings → Control by mobile apps → Network access → **Default**.
- **Samsung**: if you pressed Deny by mistake: Settings → General → External Device Manager →
  Device Connection Manager → Device List → remove "Universal Remote".
  To power on from standby, enable *Power On with Mobile* in network settings.
- **LG**: to power on from standby, enable *Turn on via Wi-Fi* (or *Mobile TV On*).
- **Android TV pairing fails repeatedly**: on the TV go to Settings → Apps → System apps →
  *Android TV Remote Service* → Clear data, then pair again.

## Before publishing on Google Play

- `app/build.gradle.kts` signs release builds with the debug key so they install straight away.
  For Play you need your own upload key (Android Studio → Build → Generate Signed App Bundle).
- Change `applicationId` if you want a different package name, and replace the app icon.
- Play requires a privacy policy; the app stores only TV names/IPs and pairing keys on the phone.

## Project layout

```
app/src/main/java/studio/zavi/uniremote/
  core/            protocol code, no Android dependencies
    androidtv/     pairing (port 6467) + remote channel (6466), hand-rolled protobuf, self-signed cert
    samsung/  lg/  roku/  firetv/
    net/           tiny WebSocket client, TLS helpers, HTTP, Wake-on-LAN
    discovery/     SSDP search and classification
  data/            saved TVs, mDNS (NsdManager) scan
  ui/              Jetpack Compose screens
  MainViewModel.kt connection state, pairing prompts, key sending
```

Protocol details follow the open-source implementations used by Home Assistant
(androidtvremote2, aiowebostv, samsungtvws) and Roku's ECP docs. The Fire TV path uses the
[dadb](https://github.com/mobile-dev-inc/dadb) library.

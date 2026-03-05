# 📱 Online Session Explorer v1.7

A sleek Android file server app — browse, upload, rename, delete and download files from any browser on your local network.

## 🚀 Deploy to GitHub (3 steps)

1. **Create a new GitHub repository** (public or private)
2. **Extract this ZIP** and push its contents to the repo root:
   ```bash
   unzip OnlineSessionExplorer.zip
   cd OSE
   git init
   git add .
   git commit -m "Initial release v1.7"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```
3. **GitHub Actions builds automatically** — go to the **Actions** tab, watch the build, then find your APK under **Releases**.

## ⚙️ How the build works

Every push to `main` triggers `.github/workflows/build.yml` which:
- Sets up JDK 17 + Android SDK
- Runs `./gradlew assembleRelease`
- Signs with the debug key (no keystore needed)
- Creates a GitHub Release with the APK attached

## 📲 App Features

| Feature | Description |
|---------|-------------|
| 🌐 Web UI | Access from any browser via `http://LOCAL_IP:PORT` |
| 🔒 Password | Login gate with show/hide password toggle |
| 🗂️ Browse | Grid & list views, search, sort by name/size/date |
| ⬆️ Upload | Drag & drop or click, with per-file live progress bar |
| ✏️ Rename | Rename files and folders inline |
| 🗑️ Delete | Delete files/folders with confirmation |
| 📁 New Folder | Create directories from the browser |
| 📥 Download | Direct download any file |
| 🎵 Streaming | Video/audio streaming with range request support |
| 📋 Logs | Live server log in the app |
| ⚙️ Settings | Port, serve dir, password, theme (Light/Dark/System) |

## 📋 Defaults

| Setting | Default |
|---------|---------|
| Password | `702152` |
| Port | `8001` |
| Serve directory | `/sdcard` |

## 🔧 Customization

Change defaults in **Settings** tab inside the app, or edit:
- `app/src/main/java/com/body777/fileexp/ServerService.kt` — change default port/dir/password
- `app/src/main/assets/template.html` — modify the web UI
- `app/build.gradle` — change `versionCode` / `versionName`

## 📦 Build locally

```bash
# Requires: JDK 17, Android SDK (ANDROID_HOME set)
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/
```

## 🏗️ Project Structure

```
OSE/
├── .github/workflows/build.yml   ← CI/CD pipeline
├── app/src/main/
│   ├── assets/
│   │   ├── template.html          ← Web browser UI
│   │   └── login.html             ← Login page
│   └── java/com/body777/fileexp/
│       ├── FileServer.kt          ← NanoHTTPD web server
│       ├── ServerService.kt       ← Android foreground service
│       ├── MainActivity.kt        ← Bottom navigation host
│       └── ui/
│           ├── LogsFragment.kt    ← Logs + server control
│           └── SettingsFragment.kt← App settings
```

---
**Package:** `com.body777.fileexp` · **Min SDK:** Android 8.0 (API 26)

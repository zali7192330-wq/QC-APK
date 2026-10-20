# QuickCapture — Android

Two Android builds of the QuickCapture note-catcher. Both capture notes with
shorthand modifiers and save them to the same Notion database as the
[Windows app](https://github.com/zali7192330-wq/Quick-Capture-EXE-).

## Variants

| | `native/` | `maui/` |
|---|---|---|
| Language | Kotlin | C# |
| UI | Native Android XML layouts | .NET MAUI (XAML) |
| Build tool | Gradle (`./gradlew assembleDebug`) | `dotnet build -f net8.0-android` |
| Shares code with Windows app | No — logic rewritten in Kotlin | Yes — same parser + Notion code |
| APK size | ~1.6 MB | ~18.6 MB |

Both are debug builds: on install, allow "unknown sources" when Android asks.

## Features (both)

- Capture screen with **live colored modifier preview** and detection chips
- Modifiers: `#1`/`#2`/`#3` (`` `1 ``/`` `2 ``/`` `3 ``) priorities, `/high` etc.,
  `~` topic list popup, `!` category popup (Spark, TIL, Prompt),
  `#tag` topics, natural dates/times
- **Attachments** via the system file picker
- **Share intent**: share text from any app into QuickCapture
- Saves to Notion via HTTPS POST (Name, Note, Priority, Topic, Category,
  Date, Source = Quick Capture; images first, then files, then text)

Native-only extras (v3/v4):

- **Source = QC-MOBILE** — mobile captures are tagged separately in Notion
- **Paste button** — a copied image becomes an attachment (copied text
  pastes at the cursor)
- **Attach menu** — Camera photo, Camera video, Photos, Videos, Files
  (camera capture goes through a FileProvider in `res/xml/file_paths.xml`)
- **Voice recording** — Record button captures an .m4a voice note as an
  attachment (needs the RECORD_AUDIO permission)

## Setup

**Native** (`native/`):
1. Open the folder in Android Studio (or build with the Gradle wrapper).
2. Copy `native/config.master.example.json` to `native/config.master.json`
   and fill in your Notion token and database ID
   (or pass `-PnotionConfig=/path/to/config.json`).
   `config.master.json` is git-ignored — never commit your token.
3. `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

**MAUI** (`maui/`):
1. Install the .NET 8 SDK and run `dotnet workload install maui-android`
   (needs JDK 17 and the Android SDK).
2. Copy `maui/Secrets.example.cs` to `maui/Secrets.cs` and fill in your
   Notion token and database ID. `Secrets.cs` is git-ignored.
3. `dotnet build -f net8.0-android -c Debug`

## Notes

- The repo builds with the default MAUI SVG app icons. To restore the teal
  lightning-bolt icon, drop your `appicon.png` into `maui/Resources/AppIcon/`
  and change the `<MauiIcon>` line in the csproj to point at it.
- `native/` launcher icon is a vector drawable (teal bolt) at
  `app/src/main/res/drawable/ic_launcher.xml` — no binary assets needed.
- The Windows `.ico` icon is not committed (too large for the API push);
  `src/Assets/icon.png` is included.

## Version history

- **native v4** — attach menu (Camera photo/video, Photos, Videos, Files)
  via FileProvider; in-app voice recording (.m4a attachments)
- **native v3** — Source = QC-MOBILE; clipboard image paste (Paste button).
  First v3 build was missing versionCode and would not install; the fixed
  rebuild sets versionCode 3 / versionName 3.0
- **v2 (both)** — Topic sent as a Notion multi_select (database column was
  converted from rich text; v1 saves failed with a 400 error)
- **v1 (both)** — first builds: modifiers, attachments, share intent

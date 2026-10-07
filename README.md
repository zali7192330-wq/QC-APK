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

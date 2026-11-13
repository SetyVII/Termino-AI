# Notices and attributions

## Base project

**Término** is based on **[ReadAny](https://github.com/codedogQBY/ReadAny)**
by codedogQBY / ReadAny Team, released under the
[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

Término reuses ReadAny's idea, experience design (reader + AI assistant with
local RAG, BYOK, highlights and chat with citations) and product approach,
reimplemented natively for Android (Kotlin + Jetpack Compose, no WebView). In
compliance with the GPL-3.0, Término is distributed under the same license
(see [`LICENSE`](LICENSE)) and its full source code is available.

## Third-party dependencies

| Component | License |
|---|---|
| Kotlin, kotlinx.coroutines | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, Room, WorkManager, DataStore, SplashScreen) | Apache-2.0 |
| Jetpack Compose, Material 3, Material Icons | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| OkHttp / Okio (Square) | Apache-2.0 |
| jsoup (Jonathan Hedley) | MIT |
| PdfBox-Android (Tom Roush, port of Apache PDFBox) | Apache-2.0 |

All are compatible with the GPL-3.0. The full Apache-2.0 and MIT license texts
are available at <https://www.apache.org/licenses/LICENSE-2.0> and
<https://opensource.org/license/mit>.

## Assets

- The images and texts of the sample books (`app/src/main/assets/samples/`)
  are original to this project. The images were generated with the Noto Serif /
  Noto Sans fonts (SIL Open Font License 1.1).
- Fonts the user imports into the app remain under their own license and are
  not distributed with Término.

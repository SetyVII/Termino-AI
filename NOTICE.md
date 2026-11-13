# Avisos y atribuciones

## Proyecto base

**Término** está basado en **[ReadAny](https://github.com/codedogQBY/ReadAny)**
de codedogQBY / ReadAny Team, publicado bajo la
[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

Término reutiliza la idea, el diseño de la experiencia (lector + asistente de IA
con RAG local, BYOK, subrayados y chat con citas) y el enfoque de producto de
ReadAny, reimplementados de forma nativa para Android (Kotlin + Jetpack Compose,
sin WebView). En cumplimiento de la GPL-3.0, Término se distribuye bajo la misma
licencia (ver [`LICENSE`](LICENSE)) y su código fuente completo está disponible.

## Dependencias de terceros

| Componente | Licencia |
|---|---|
| Kotlin, kotlinx.coroutines | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, Room, WorkManager, DataStore, SplashScreen) | Apache-2.0 |
| Jetpack Compose, Material 3, Material Icons | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| OkHttp / Okio (Square) | Apache-2.0 |
| jsoup (Jonathan Hedley) | MIT |
| PdfBox-Android (Tom Roush, port de Apache PDFBox) | Apache-2.0 |

Todas son compatibles con la GPL-3.0. Los textos completos de las licencias
Apache-2.0 y MIT están en <https://www.apache.org/licenses/LICENSE-2.0> y
<https://opensource.org/license/mit>.

## Recursos

- Las imágenes y textos de los libros de muestra (`app/src/main/assets/samples/`)
  son originales de este proyecto. Las imágenes se generaron con las fuentes
  Noto Serif / Noto Sans (SIL Open Font License 1.1).
- Las fuentes que el usuario importe en la app quedan bajo su propia licencia y
  no se distribuyen con Término.

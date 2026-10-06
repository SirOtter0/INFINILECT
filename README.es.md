# INFINILECT

**Open knowledge. Infinite reading.**

[English](README.md)

INFINILECT es un lector open-source y multiplataforma de libros, cómics, revistas,
artículos y documentos legalmente disponibles.

> Una fuente obtiene publicaciones. Un lector las muestra. INFINILECT conecta ambos.

INFINILECT **no aloja publicaciones**. Las fuentes proporcionan metadatos y recursos;
los usuarios deben respetar los derechos de cada publicación y las leyes de su país.
Que Project Gutenberg ofrezca una obra en EE. UU. no implica que sea de dominio público en todo el mundo.

## Estado

El proyecto está en una fase muy temprana. Desktop y la primera aplicación Android
debug comparten la misma UI. Internet Archive (subset público CC0) es la fuente inicial de lectura TEXT real.
**Project Gutenberg (experimental)** busca en el catálogo OPDS2 de desarrollo indicado
por Gutenberg y permite guardar metadata en Library. Se retiraron el feed OPDS0.9
sin mantenimiento y la adquisición RDF no confirmada; **Gutenberg no permite leer**.
[Pruebas y bloqueos externos](docs/GUTENBERG.md). Buscar y Next page requieren
acciones explícitas, sin búsquedas automáticas ni precarga.

Selecciona **Internet Archive**, pulsa **Open text** y lee UTF-8 estrictamente válido
con tamaño conocido≤**16MiB**. Back conserva fuente/consulta/resultados. El texto
usa preparación temporal privada y ventanas acotadas;
[política del lector](docs/TEXT_READER.md).

Es una primera ruta de lectura conservadora, con progreso TEXT aproximado y local
entre reinicios, sin adquisición EPUB de producción, lectura PDF ni descargas explícitas. La caché de disco automática
y acotada solo reutiliza recursos con revisiones fiables; los recursos actuales
de Archive no tienen revisión y volver a abrir aún adquiere de nuevo.
Consulta la [política de caché](docs/CACHE.md).
Library guarda metadatos de publicaciones localmente; History registra aperturas
correctas. Ambas sobreviven a reinicios y al borrado de caché. Abrir una entrada
guardada vuelve a consultar su fuente, adquiere normalmente y restaura el progreso.
Clear History requiere confirmación y conserva Library/progreso.
[Política local](docs/LIBRARY_HISTORY.md).

**Import local file** copia un TEXT UTF-8, EPUB3 compatible o CBZ PNG/JPEG a
almacenamiento privado y duradero, lo añade a Library y lo abre con los lectores
existentes. Library/History y el progreso sobreviven a reinicios, borrado de caché
y eliminación del archivo original. Límite: 32 MiB (TEXT: 16 MiB); duplicados por
contenido, sin borrar copias al quitar filas de Library. Android usa SAF y Desktop
un selector nativo. [Propiedad, límites y verificación manual pendiente](docs/LOCAL_IMPORT.md).
**v0.0.1 no está terminada**. [Alcance](docs/INTERNET_ARCHIVE.md).

La interfaz alternativa oficial de metadatos OAPEN es accesible y proporciona
enlaces de descarga. REST rechaza este entorno con HTTP 403 y la transferencia PDF
sigue bloqueada/no verificada. Consulta [OAPEN](docs/OAPEN.md) y la
[comparación](docs/ACQUISITION_COMPARISON.md). Gutenberg sigue como catálogo experimental; no hay crawling masivo.
No se anuncia fuente/UI OAPEN ni lector PDF.

El objetivo deliberadamente pequeño de v0.0.1 es: abrir INFINILECT → buscar un libro
→ obtener resultados reales → abrir uno → leerlo. Project Gutenberg/OPDS es la primera fuente de búsqueda funcional.
Desktop y Android son destinos ejecutables. Android requiere API 26+; iOS queda
para el futuro. El usuario reporta verificación física Android satisfactoria hasta
PR #11 fusionado, incluyendo persistencia, lectura/progreso IA, búsqueda/paginación
Gutenberg experimental y Next empezando en el primer resultado. Codex no realizó
esa prueba física. La verificación gráfica Desktop sigue pendiente; los checks
automatizados están en [VERIFICATION.md](docs/VERIFICATION.md).

## Compilar y ejecutar

Instala JDK 21 y Android SDK (plataforma 37, build-tools 36.0.0). Configura
`ANDROID_HOME` o un `local.properties` no versionado con la ruta del SDK. El wrapper Gradle incluido descarga Gradle en el primer uso;
las dependencias requieren acceso a Internet.

```sh
./gradlew :core:jvmTest :app:desktopTest
./gradlew build
./gradlew :desktopApp:run
```

En Windows utiliza `gradlew.bat`. La ejecución requiere un escritorio gráfico
y conexión a Internet para buscar.
No se incluyen instaladores nativos. Para compilar/instalar el APK debug estándar:

```sh
./gradlew :app:testAndroidHostTest :core:testAndroidHostTest :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Android usa la misma selección de fuente, búsqueda y TextReader. Back del sistema
desde Loading/Reader/Error conserva resultados; en Search sigue al sistema Android.
Recrear la Activity o perder el proceso inicia una sesión nueva: no se guardan
consulta/resultados/documento/scroll en píxeles. El progreso lógico se guarda aparte
y se restaura tras abrir y adquirir de nuevo correctamente. No hay tracking ni acceso extra a datos del
dispositivo. INTERNET es la única capacidad de plataforma solicitada; AndroidX
también declara un permiso interno de firma, exclusivo de la app, para proteger
receivers no exportados. El icono es geometría original provisional.

Para el catálogo Gutenberg, busca `Frankenstein` o `shakespeare`; puedes guardar
metadata en Library, pero no abrir EPUB/TEXT desde esta fuente.
Para un ejemplo de Archive, selecciona Internet Archive y busca
`identifier:gmb-2015-93040`; después pulsa **Open text**. Es un documento público
CC0 del gobierno neerlandés. No se enriquecen ni adquieren resultados de forma
automática. Cambiar de fuente cancela la sesión anterior y vacía consulta/resultados;
volver desde el lector conserva la sesión actual, guardando la posición lógica de lectura localmente.

Versiones: Kotlin/compilador Compose 2.4.20, Compose Multiplatform 1.12.1,
Gradle 9.7.1, AGP 9.3.1, compileSdk 37 / targetSdk 37 / minSdk 26. Consulta las [referencias oficiales de compatibilidad](docs/TOOLCHAIN.md)
y los [avisos de terceros](THIRD_PARTY_NOTICES.md).

Gutenberg utiliza Ktor 3.6.0 y serialization-json existentes fuera de core.
Desktop/Android comparten el mismo parser JSON seguro. [Endpoints/límites](docs/SOURCES.md).
Los tests offline usan fixtures OPDS2 pequeñas propias y MockEngine. El check opt-in
de desarrollo consulta raíz y una página de resultados, sin adquirir contenido:

```sh
./gradlew :app:gutenbergSearchCheck --args="shakespeare"
```

El diagnóstico OAPEN independiente `./gradlew :app:oapenApiAccessCheck --args=water`
hace una sola petición, sin mapear publicaciones ni descargarlas. Es opt-in y falla
si el acceso se rechaza; un HTTP 200 tampoco demostraría la adquisición.

Dos checks adicionales opt-in nunca se ejecutan durante tests/build:

```sh
./gradlew :app:oapenAlternateAccessCheck
./gradlew :app:internetArchiveAcquisitionCheck
```

El primero solicita un registro OAI-PMH y hace solo HEAD; el segundo busca un
documento gubernamental CC0 y consume hasta 512 bytes mediante ResourceLoader.
No registra ni guarda texto. Consulta límites y política conservadora de hosts/
acceso en los documentos de cada fuente. El parser JSON kotlinx.serialization-json
1.11.0 (Apache-2.0) se comparte entre JVM/Android; core sigue puro.

El nuevo check de documento completo usa la misma lógica de búsqueda/apertura/sesión
que la UI, verifica UTF-8 estricto y Back, y solo registra cantidades, nunca texto:

```sh
./gradlew :app:internetArchiveTextReadingCheck
```

Es opt-in, no se ejecuta en tests/build y lee un único documento pequeño verificado
bajo el límite de 16 MiB. El check CLI no equivale a una prueba gráfica de la UI.

Los resultados reales de verificación y límites del entorno están en [VERIFICATION.md](docs/VERIFICATION.md).

## Estructura inicial pequeña

- `core`: modelos/contratos Kotlin puros en `commonMain`, targets JVM y biblioteca Android.
- `app`: UI Compose, sesión/controladores/reader compartidos; `jvmSharedMain` comparte
  políticas y mapping de fuentes. Desktop usa HTTP Java; Android HTTP Android; ambos comparten el parser JSON.
- `desktopApp`: launcher, runtime del sistema y empaquetado Desktop; depende de `app`.
- `androidApp`: Activity, Back/insets, manifest y APK; depende de `app`.
  [Decisión Android](docs/adr/0013-first-android-application.md) desarrolla ADR 0008.
- `docs`: arquitectura, política de fuentes, caché, hoja de ruta y decisiones.

Ktor es el cliente HTTP de los adapters de plataforma, fuera de `core`. SQLDelight
2.4.0 guarda metadatos de Library/History en app; el progreso mantiene su almacén
independiente de archivos. Readium no está incluido y solo podrá incorporarse en
un lector específico de Android.

## Documentación y contribuciones

- [Arquitectura](docs/ARCHITECTURE.md)
- [Fuentes](docs/SOURCES.md)
- [Caché y descargas](docs/CACHE.md)
- [Progreso de lectura persistente](docs/PROGRESS.md)
- [Biblioteca local e historial](docs/LIBRARY_HISTORY.md)
- [Hoja de ruta](docs/ROADMAP.md)
- [Decisiones arquitectónicas](docs/adr/README.md)
- [Cómo contribuir](CONTRIBUTING.md)

## Licencia

El código original de INFINILECT se distribuye bajo `GPL-3.0-or-later`, véase [LICENSE](LICENSE).

INFINILECT es software libre: puedes redistribuirlo y/o modificarlo bajo los
términos de la Licencia Pública General de GNU publicada por la Free Software
Foundation, ya sea la versión 3 de la Licencia o, a tu elección, cualquier versión
posterior.

La licencia del proyecto no cambia la licencia de las publicaciones de las fuentes.

Copyright © 2026 SirOtter0 and INFINILECT contributors.

## Lector EPUB experimental (subconjunto pasivo acotado)

El APK debug ofrece **EPUB development demo**: selecciónalo, busca `original` y
pulsa Open EPUB. En Desktop: `INFINILECT_EPUB_DEMO=1 ./gradlew :desktopApp:run`.
La publicación original de tres capítulos usa lectura Compose pasiva compartida,
índice/enlaces internos, progreso semántico persistente y reapertura desde Library/
History. **La adquisición EPUB de producción está deshabilitada.** No se afirma
compatibilidad EPUB general, CSS del editor ni scripts. PR #16 añade ajustes de
presentación EPUB globales persistentes, listas numeradas e imágenes PNG/JPEG locales acotadas; SVG
sigue como texto alternativo y la adquisición de producción sigue deshabilitada. TEXT conserva su
comportamiento. [Alcance, seguridad, límites y pruebas manuales](docs/EPUB_READER.md).
v0.0.1 sigue sin estar terminada.

## Lector de páginas de cómic experimental

Android debug ofrece **Comic development demo**: selecciónalo, busca `original` y
pulsa **Open pages**. En Desktop: `INFINILECT_COMIC_DEMO=1 ./gradlew :desktopApp:run`.
El cómic original de 24 páginas permite modos RTL/LTR, zoom/pan y lectura vertical/
webtoon acotada, con progreso semántico y modo global persistentes. Solo PNG/JPEG,
con un conjunto de trabajo de tres páginas. **Sin adquisición de cómics de producción,
CBZ ni PDF**. [Política, límites y aceptación física pendiente](docs/PAGE_READER.md).
Es la primera base acotada de lectura por páginas; no se afirma compatibilidad manga
ni Mihon. v0.0.1 sigue sin estar terminada.

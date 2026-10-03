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
debug comparten la misma UI. La aplicación busca
en el catálogo OPDS oficial de Project Gutenberg y muestra resultados reales,
autores e idiomas cuando la fuente los proporciona. La búsqueda se envía mediante
una acción explícita; la siguiente página solo se solicita al pulsar **Next page**.
No hay búsqueda automática ni precarga.

Selecciona **Internet Archive** para buscar textos públicos CC0, pulsa **Open text**
y lee un recurso TEXT real en el primer TextReader mínimo. **Back to results**
conserva la fuente seleccionada, consulta y resultados actuales. Solo admite UTF-8
estrictamente válido, con tamaño conocido de hasta **512 KiB**; la ausencia de TEXT
produce un error controlado. Project Gutenberg sigue siendo **solo búsqueda**.

Es una primera ruta de lectura conservadora, sin persistencia de progreso, ajustes
de lector, lector EPUB/PDF ni downloads persistentes. La caché de disco automática
y acotada solo reutiliza recursos con revisiones fiables; los recursos actuales
de Archive no tienen revisión y volver a abrir aún adquiere de nuevo.
Consulta la [política de caché](docs/CACHE.md).
**v0.0.1 no está terminada**. [Alcance](docs/INTERNET_ARCHIVE.md).

La interfaz alternativa oficial de metadatos OAPEN es accesible y proporciona
enlaces de descarga. REST rechaza este entorno con HTTP 403 y la transferencia PDF
sigue bloqueada/no verificada. Consulta [OAPEN](docs/OAPEN.md) y la
[comparación](docs/ACQUISITION_COMPARISON.md). Gutenberg espera orientación oficial
para adquisición. No se anuncia fuente/UI OAPEN ni lector PDF.

El objetivo deliberadamente pequeño de v0.0.1 es: abrir INFINILECT → buscar un libro
→ obtener resultados reales → abrir uno → leerlo. Project Gutenberg/OPDS es la primera fuente de búsqueda funcional.
Desktop y Android son destinos ejecutables. Android requiere API 26+; iOS queda
para el futuro. Se verifica el APK; la prueba física está pendiente.

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
consulta/resultados/documento/scroll. No hay tracking ni acceso extra a datos del
dispositivo. INTERNET es la única capacidad de plataforma solicitada; AndroidX
también declara un permiso interno de firma, exclusivo de la app, para proteger
receivers no exportados. El icono es geometría original provisional.

Para un ejemplo pequeño verificado, selecciona Internet Archive y busca
`identifier:gmb-2015-93040`; después pulsa **Open text**. Es un documento público
CC0 del gobierno neerlandés. No se enriquecen ni adquieren resultados de forma
automática. Cambiar de fuente cancela la sesión anterior y vacía consulta/resultados;
volver desde el lector conserva la sesión actual, sin guardar posición de lectura.

Versiones: Kotlin/compilador Compose 2.4.20, Compose Multiplatform 1.12.1,
Gradle 9.7.1, AGP 9.3.1, compileSdk 37 / targetSdk 37 / minSdk 26. Consulta las [referencias oficiales de compatibilidad](docs/TOOLCHAIN.md)
y los [avisos de terceros](THIRD_PARTY_NOTICES.md).

El transporte Gutenberg utiliza Ktor 3.6.0 fuera de core. El parser de escritorio
usa StAX incluido en JDK 21, con acceso XML externo deshabilitado. Consulta los
[endpoints y límites de la fuente](docs/SOURCES.md). Los tests sin conexión utilizan
fixtures OPDS pequeñas de autoría propia y Ktor MockEngine. Una comprobación opcional
de una página real, independiente de tests/build y sin interfaz gráfica, es:

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
bajo el límite de 512 KiB. El check CLI no equivale a una prueba gráfica de la UI.

Los resultados reales de verificación y límites del entorno están en [VERIFICATION.md](docs/VERIFICATION.md).

## Estructura inicial pequeña

- `core`: modelos/contratos Kotlin puros en `commonMain`, targets JVM y biblioteca Android.
- `app`: UI Compose, sesión/controladores/reader compartidos; `jvmSharedMain` comparte
  políticas y mapping de fuentes. Desktop usa HTTP Java/StAX; Android HTTP Android/XmlPull.
- `desktopApp`: launcher, runtime del sistema y empaquetado Desktop; depende de `app`.
- `androidApp`: Activity, Back/insets, manifest y APK; depende de `app`.
  [Decisión Android](docs/adr/0013-first-android-application.md) desarrolla ADR 0008.
- `docs`: arquitectura, política de fuentes, caché, hoja de ruta y decisiones.

Ktor es el cliente HTTP de los adapters de plataforma, fuera de `core`. SQLDelight se añadirá cuando la
persistencia lo necesite. Readium solo podrá incorporarse en una implementación
de lector específica de Android. SQLDelight y Readium no están incluidos.

## Documentación y contribuciones

- [Arquitectura](docs/ARCHITECTURE.md)
- [Fuentes](docs/SOURCES.md)
- [Caché, descargas y progreso](docs/CACHE.md)
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

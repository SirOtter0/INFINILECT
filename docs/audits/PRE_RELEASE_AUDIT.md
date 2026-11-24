# Auditoría previa al primer release de INFINILECT

## Alcance y entorno

- Base `9432d90a4440cbbd1232e00a36eee374bd4eefd4`, HEAD de main verificado el 24 de noviembre de 2026 con checkout limpio.
- Debian 13 x86-64, Temurin JDK 21.0.13.1, Gradle 9.7.1, Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.3.1 y SQLDelight 2.4.0. Android compile/target SDK 37 y minSdk 26. Las mediciones corresponden al host y las compilaciones locales. La instalación, TalkBack, Android físico y macOS/Windows requieren validación aparte.
- Cuatro módulos. Core contiene modelos y contratos KMP. App reúne Compose, source sets común, JVM compartido, Android y Desktop y sus pruebas. AndroidApp y desktopApp empaquetan los launchers. Los adaptadores de plataforma poseen motores y almacenamiento.
- 569 archivos versionados en la base, 209 fuentes Kotlin de producción inventariadas y 462 archivos compatibles sometidos al escáner. La revisión manual siguió límites, implementaciones y pruebas representativas de cada superficie. La cobertura automática y la revisión manual se contabilizan por separado.

Se aplicó la metodología de revisión local porque el entorno no expuso el ejecutor nativo de deep scan. Se combinaron inventario verificable, análisis estático, comprobaciones upstream, revisión manual dirigida, suite completa antes y después y una regresión sintética. Esta auditoría no certifica cobertura integral de MASVS.

## Kotlin Footguns

Revisión externa `2c391bc9c9f873580aa855d7a7b1c3a2ed9e68ae`, en un checkout independiente. Se revisaron README, LICENSE GPL-3.0, CATALOG, los índices SKILL y los 32 detectores del script. El escáner usa Python estándar y consulta archivos y Git local. Pasó su prueba propia. Se ejecutó `python3 /ruta/kotlin-footguns/skills/footgun-scan/scripts/scan.py --all` desde el checkout de INFINILECT, sin importar el repositorio ni modificar el historial real. El modo aplica los 32 detectores a líneas versionadas compatibles, dentro de un catálogo de 228 patrones. [Kotlin Footguns](https://github.com/maxrave-dev/kotlin-footguns/tree/2c391bc9c9f873580aa855d7a7b1c3a2ed9e68ae)

| Área | Patrones y evidencia | Resultado |
| --- | --- | --- |
| Arquitectura KMP | clean-arch-kmp-readiness, formato de recursos. Contratos Core, adaptadores por plataforma y recursos Compose. | Módulos y destinos inventariados. Sin hits de formato. |
| Estado y background | named-job-lifecycle-discipline y compose-desktop-runtime-hardening. ReadingSession impide relanzar un Job activo y cancela al cerrar. OfflineDownloads registra un Job local LAZY por mutación y lo retira al terminar. | Dos look descartados. Se verificaron cancelación y resultados tardíos en Discovery y lectores. |
| Persistencia | cascading-delete-ordering y contrato de paginación. SQLDelight usa transacción para esquema y migración. El progreso y las descargas tienen almacenamiento separado. | Pruebas host y revisión de flujos. Falta instalar encima de una build anterior firmada. |
| Pantallas | swipe-action-list-row y control-range-must-cover-stored-values. Los dos pointerInput(Unit) en ComicControls consumen gestos sin capturar flags. Los índices de posición y scrub están acotados. | Tres look descartados. Navegación y escalado cubiertos por pruebas host. |
| Visuales | touch-indication-bounds-and-alpha, gradientes y transiciones. | Sin coincidencias. Contraste, foco y Android permanecen en el checklist físico. |
| Red | retry-needs-backoff-and-cap y ktor-kmp-client-architecture. | Nueve likely descartados. HomeScreen y LanguageTranslationPanel invocan acciones explícitas. Las otras siete llamadas son fixtures. Ninguna usa el operador ilimitado de Flow. |
| Desktop y build | config-fails-open-verify-artifact, integridad del wrapper y Foxlet. | Compilación JVM y distribución Linux. ABI nativo de otros sistemas pendiente. |
| WebView | Índice WebView y búsqueda de WebView, JavaScript y carga dinámica en producción. | No aplicable. EPUB se analiza con SAX y se presenta en Compose. |
| Recursos de lector | derive-seek-from-progress-flow, cursores confirmados, cancelación y retiros. | Aplicación parcial. Audio, DSP y sesiones musicales quedan fuera. |
| Método | run-discriminating-experiment-first y read-build-logs-bottom-up. | Línea base fresca y regresión PDF ante un cambio discriminante. Suites ordinarias independientes de servicios de red. |

Los 14 avisos del escáner fueron descartados tras leer los cuatro documentos que enlaza y seguir sus llamadas. El análisis registró nueve likely y cinco look en diez archivos. Los falsos positivos de reintento en pruebas pertenecen a ApplicationAppearanceTest, EpubNavigationPerformanceTest, EpubTranslationTest, EpubViewportNavigationTest y TranslationModelsTest.

## Cadena de suministro y distribución

- Se resolvieron 23 configuraciones runtime de los cuatro módulos, con debug, release, Android-host, Desktop y configuraciones dev. 260 coordenadas/versiones Maven únicas y Foxlet 0.5.0, AAR local SHA-256 comprobado. El [SBOM CycloneDX 1.6](runtime-sbom.cdx.json) validado contra el esquema oficial es un inventario plano con configuraciones Gradle. No incluye bibliotecas de sistema ni descompone binarios nativos.
- Ktor 3.6.0, OkHttp Android 5.5.0, coroutines/serialization 1.11.0, SQLDelight 2.4.0 y PDFBox 3.0.8. Foxlet se descarga con pin SHA-256 y los modelos tienen manifiestos específicos. Maven Central y Google para dependencias. Los plugins añaden Gradle Plugin Portal.
- OSV Maven consultado por coordenada y versión exactas el 24 de noviembre de 2026. Cero coincidencias en los seis lotes con 260 coordenadas runtime y seis coordenadas de build seleccionadas. GitHub Advisory Database tampoco coincidió con PDFBox 3.0.8. El resultado está sujeto a la cobertura del índice, incluidas las variantes Maven. Foxlet/Bergamot, modelos, SDK, JDK y nativos necesitan seguimiento upstream propio. [OSV](https://osv.dev/) · [Avisos GitHub](https://github.com/advisories) · [Seguridad de PDFBox](https://pdfbox.apache.org/security.html)
- El ZIP y el JAR del Gradle Wrapper coinciden con el hash fijado y el publicado, respectivamente. La base aún carecía de metadatos de verificación Gradle para artefactos Maven. La remediación se detalla a continuación. [Checksums Gradle](https://gradle.org/release-checksums/)
- La API lista cinco workflows históricos. Se revisó el YAML de la última ejecución de cada uno. Push acotado por rama y un workflow manual, permisos contents: read, acciones fijadas por SHA y artefactos temporales. El árbol auditado de main carece de workflows, configuración de CodeQL, Dependency Review, Dependabot y escaneo de secretos versionados. No se cambiaron ajustes del repositorio. [Historial de Actions](https://github.com/SirOtter0/INFINILECT/actions)
- Distribuir avisos y licencias vigentes, incluidos PDFBox y FontBox, Skia/Skiko, SQLite JDBC, OkHttp/PSL, Foxlet/Bergamot/Marian y modelos. Los ABIs y las licencias de sistema varían según plataforma. [Inventario de licencias](../../THIRD_PARTY_NOTICES.md) · [Textos de terceros](../../third-party)

### Avisos del entorno de compilación

Se inventariaron 130 coordenadas de build mediante los informes buildEnvironment de Gradle. [Inventario del classpath de build](build-dependencies.tsv). Tras deduplicar seis consultadas antes, OSV recibió otras 124 coordenadas. Diez avisos en seis módulos coinciden con versiones del classpath de AGP 9.3.1. Se verificaron rangos, CVE y versiones corregidas en GitHub Advisory Database. Esas seis coordenadas están ausentes de las configuraciones runtime resueltas de la aplicación.

| Aviso | Gravedad del aviso | Componente y versión | Rango relevante | Primera corrección de esa rama | Condición de aplicabilidad |
| --- | --- | --- | --- | --- | --- |
| [CVE-2026-102554](https://github.com/advisories/GHSA-xxph-c9ww-hj94) | HIGH | com.google.guava:guava 33.4.0-jre | >= 4.0, <= 33.7.1 | 33.7.2 | Deserialización Java de colecciones Guava controladas externamente. |
| [CVE-2025-48924](https://github.com/advisories/GHSA-j288-q9x7-2f5v) | MEDIUM | org.apache.commons:commons-lang3 3.16.0 | >= 3.0, < 3.18.0 | 3.18.0 | Conversión de nombres de clase con una entrada anormalmente larga. |
| [CVE-2024-29371](https://github.com/advisories/GHSA-3677-xxcr-wjqv) | HIGH | org.bitbucket.b_c:jose4j 0.9.5 | < 0.9.6 | 0.9.6 | Procesamiento de JWE comprimido proporcionado externamente. |
| [CVE-2026-5588](https://github.com/advisories/GHSA-wg6q-6289-32hp) | MEDIUM | org.bouncycastle:bcpkix-jdk18on 1.79 | >= 1.49, < 1.84 | 1.84 | Uso del método de verificación criptográfica afectado en BCPKIX. |
| [CVE-2025-14813](https://github.com/advisories/GHSA-574f-3g2m-x479) | CRITICAL | org.bouncycastle:bcprov-jdk18on 1.79 | >= 1.59, <= 1.80.1 | 1.80.2 | Uso de GOST 28147 CTR con el proveedor afectado. |
| [CVE-2026-8763](https://github.com/advisories/GHSA-9pwp-9qqc-pr26) | CRITICAL | org.bouncycastle:bcprov-jdk18on 1.79 | < 1.85 | 1.85 | Validación PKIX de restricciones de nombres mediante el proveedor afectado. |
| [CVE-2026-0636](https://github.com/advisories/GHSA-c3fc-8qff-9hwx) | MEDIUM | org.bouncycastle:bcprov-jdk18on 1.79 | >= 1.74, < 1.80.2 | 1.80.2 | Uso de búsquedas LDAP a través del proveedor. |
| [CVE-2026-5598](https://github.com/advisories/GHSA-p93r-85wp-75v3) | HIGH | org.bouncycastle:bcprov-jdk18on 1.79 | >= 1.71, < 1.80.2 | 1.80.2 | Operación criptográfica afectada con tiempos observables. |
| [CVE-2026-13506](https://github.com/advisories/GHSA-qp49-qgx5-5m26) | HIGH | org.bouncycastle:bcprov-jdk18on 1.79 | < 1.85 | 1.85 | Análisis ASN.1 afectado con secuencias diferidas bajo entrada no confiable. |
| [CVE-2021-33813](https://github.com/advisories/GHSA-2363-cqg2-863c) | HIGH | org.jdom:jdom2 2.0.6 | < 2.0.6.1 | 2.0.6.1 | Análisis XML externo con configuración JDOM que permite entidades. |

El alcance confirmado era el classpath de compilación. AGP introduce los módulos afectados a través de sdk-common/sdklib, builder, apkzlib, bundletool y jetifier-processor. La auditoría no identificó una ruta desde archivos o respuestas de red de usuario hasta esas dependencias. Jetifier no está habilitado en el proyecto.

### Remediación en la PR #41

La remediación parte de `3f77b20eaa26bd281d9f605feda306cadb0c701a`. Se evaluaron los POM publicados por Google Maven. Los de sdk-common 32.3.3 y 32.5.2 aún usan Bouncy Castle 1.79 y 1.80.2, respectivamente. Actualizar AGP por sí solo con esas dependencias conservaría los dos avisos que requieren 1.85. Se mantuvo AGP 9.3.1 y se aplicaron restricciones Gradle normales en el classpath de build del proyecto raíz. Gradle permite que el grafo seleccione una versión superior compatible. Los classpaths de la aplicación quedan fuera de estas restricciones.

| Módulo | Classpath anterior | Classpath resuelto |
| --- | --- | --- |
| Guava | 33.4.0-jre | 33.7.2-jre |
| Commons Lang3 | 3.16.0 | 3.18.0 |
| jose4j | 0.9.5 | 0.9.6 |
| Bouncy Castle PKIX, Provider y utilidades | 1.79 | 1.85 los tres |
| JDOM2 | 2.0.6 | 2.0.6.1 |

El grafo efectivo se obtuvo con `buildEnvironment`. El [inventario actualizado](build-dependencies.tsv) contiene 131 coordenadas Maven. OSV consultado el 24 de noviembre de 2026 devolvió cero coincidencias para esas coordenadas, Gradle Core 9.7.1 y las 260 coordenadas Maven runtime. La comparación del [SBOM](runtime-sbom.cdx.json) conservó los mismos 261 componentes, contando Foxlet. Los diez avisos de la tabla anterior ya quedan fuera de los rangos afectados en el grafo de build resuelto. El resultado de OSV está limitado a las coordenadas e índices consultados. [OSV](https://osv.dev/) · [Avisos de GitHub](https://github.com/advisories) · [Google Maven](https://maven.google.com/)

### Verificación de dependencias y CI

Los metadatos de Gradle fijan SHA-256 para archivos publicados y metadatos de resolución, incluidos los de los plugins. El workflow ejecuta Gradle con `--dependency-verification strict`. El checksum del ZIP del Wrapper reside en sus propiedades. El workflow verifica el JAR antes de invocar el Wrapper. El AAR de Foxlet mantiene su verificación SHA-256 propia, ya que se obtiene desde una tarea y queda fuera de la resolución Maven. [Metadatos Gradle](../../gradle/verification-metadata.xml) · [Checksums Gradle](https://gradle.org/release-checksums/)

La lista definitiva contiene 1.327 hashes de 786 componentes. Se contrastaron con SHA-256 publicados, bytes obtenidos de repositorios oficiales o el descriptor Gradle previamente comprobado. En coordenadas Compose donde Gradle Plugin Portal y Maven Central ofrecen bytes distintos, se fijó el artefacto exacto que resolvió Gradle. La lista incluye las variantes publicadas de Compose/Skiko para Linux ARM64, macOS y Windows, y los binarios AAPT2 de Google para macOS y Windows. Se verificó su procedencia desde Linux; la ejecución en esos sistemas permanece pendiente.

Se excluyeron 37 archivos de instrumentación Android en dispositivo de `com.android.tools.utp` y `com.google.testing.platform`. El proxy devolvió HTTP 503 al contrastarlos con Google Maven. Las tareas de dispositivo que los necesiten requieren comprobar y añadir sus hashes antes de ejecutarse con verificación estricta. Los tests Android-host usan otro classpath.

Para actualizar dependencias de forma verificable:

1. Revisar avisos upstream, coordenadas, repositorio oficial y compatibilidad antes de cambiar la versión. Obtener el grafo anterior y el nuevo con `buildEnvironment` y los informes de dependencias runtime.
2. En un checkout de revisión aislado, generar una propuesta con `./gradlew --write-verification-metadata sha256 help --dry-run`. Para resoluciones iniciadas al ejecutar tareas, generar la propuesta ejecutando las tareas afectadas en ese mismo checkout. Considerar el resultado generado como una lista de hashes por contrastar.
3. Comparar cada SHA-256 nuevo con la publicación o el checksum del editor mediante HTTPS en el repositorio oficial. Si el editor publica firmas, contrastarlas con su clave autenticada cuando corresponda. Cuando el repositorio solo ofrezca el artefacto, obtener los bytes directamente y calcular el hash. Investigar cualquier diferencia, archivo desconocido o segundo hash de una misma coordenada antes de aceptar el cambio.
4. Incorporar únicamente el metadato revisado, conservar `verify-metadata=true` y resolver en un `GRADLE_USER_HOME` limpio con verificación estricta. Ejecutar el workflow de verificación y actualizar inventarios y avisos de licencias que hayan cambiado. Los fallos de verificación requieren revisar el origen y los bytes antes de continuar. [Guía de Gradle](https://docs.gradle.org/9.7.1/userguide/dependency_verification.html)

El [workflow Verification](../../.github/workflows/verification.yml) se activa en `pull_request` y `push` a `main`. Usa un runner hospedado, token de lectura, checkout sin persistencia de credenciales y tres acciones fijadas por SHA. Ejecuta las pruebas Core, App Android-host y Desktop, builds Android debug y release sin firmar, lint, manifiestos, recursos y app image Linux. Firma de producción y publicación pertenecen a un flujo separado.

Dependabot para Gradle queda para otra revisión junto con el mantenimiento de hashes y la verificación de sus propuestas. CodeQL admite Java/Kotlin, pero su configuración de compilación para KMP y las condiciones de disponibilidad del repositorio requieren una evaluación separada. El escaneo continuo de secretos queda pendiente de confirmar las capacidades nativas de GitHub con el mantenedor. La configuración de este workflow se limita al repositorio versionado. [Dependabot](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates) · [CodeQL](https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/) · [Secret scanning](https://docs.github.com/en/code-security/secret-scanning)

## Revisión por superficie

| Superficie | Controles y evidencia |
| --- | --- |
| Android | Manifiestos y MainActivity. Permisos de Internet y estado de red, más permiso interno de firma. Launcher público y ProfileInstallReceiver protegido por DUMP. Providers y receptor de actualización privados. Sin FileProvider, enlaces profundos públicos, permiso de instalación ni tráfico en claro. Exclusiones de backup y transferencia. Release sin depuración. Controles MASVS STORAGE-1/2, NETWORK-1 y PLATFORM-1. Se revisó la parte estática de los casos MASTG-TEST-0235, 0262, 0364 y 0366. La captura de red y las pruebas de backup del dispositivo quedan pendientes. [OWASP MASVS](https://mas.owasp.org/MASVS/) · [Índice MASTG](https://mas.owasp.org/MASTG/tests/) |
| EPUB | EpubZip, EpubXml, EpubLegacyXml, EpubContent y EpubReferences. Rutas e identidad de entradas, expansión acotada, CRC, SAX sin entidades externas, DOCTYPE XHTML limitado, rechazo de contenido activo y recursos externos. Enlaces HTTPS pasivos con gesto explícito. [EPUB](../EPUB.md) |
| PDF | FilePdfPreparer, límites Core y adaptadores PDFBox/PdfRenderer. Spool privado, geometría y raster final acotados, un motor activo y cierre de página. Memoria interna de códecs y motor permanece fuera del presupuesto del raster. [PDF](../PDF_READER.md) |
| TXT y CBZ | FileTextPreparer valida UTF-8 incremental, índices y tamaño. CbzPagePreparer limita ZIP, rutas y handles, inspecciona cabeceras de imagen y conserva orden y locators. [TXT](../TEXT_READER.md) · [CBZ](../PAGE_READER.md) |
| Red y fuentes | Motores plataforma y TLS normal. Redirección automática deshabilitada. Archive vincula host, ruta y archivo. Wikisource usa ediciones configuradas y WS Export con validación de derechos, límites y cooldown de 429/maxlag. Gutenberg es catálogo experimental oculto por defecto. Las consultas públicas llegan al proveedor. Biblioteca y perfil quedan en local. [Fuentes](../SOURCES.md) |
| Datos, descargas y concurrencia | SQLDelight por transacciones, progreso mediante reemplazo atómico, importación mediante staging. FileOfflineDownloads separa parcial, payload y registro, verifica hash al abrir offline y conserva estado completado si gana la carrera con la cancelación. Quitar una descarga se limita a su almacenamiento. Revisados cancelación, callbacks tardíos y propiedad en Discovery, lectura, persistencia y adquisición. [Descargas](../OFFLINE_DOWNLOADS.md) · [Biblioteca](../LIBRARY_ORGANIZATION.md) · [Progreso](../PROGRESS.md) |
| Rendimiento y accesibilidad | Cuadrículas lazy, identidad por fuente e ID, caché limitada de portadas, preparación IO y lectura por ventanas. Pruebas host de bibliotecas grandes, teclado, pantallas estrechas, es/en/ca, temas y escalado. Arranque, frames, picos de memoria, contraste y TalkBack en Android requieren dispositivo. [Rendimiento](../ANDROID_PERFORMANCE.md) |
| Actualizador | Versiones estructuradas y canales, release/URLs GitHub validados, manifiesto firmado, hash y certificado independientes, consentimiento y entrega al instalador oficial. Instalación integrada deshabilitada en variantes actuales. [Actualizaciones](../SECURE_UPDATES.md) |

### Artefactos y procedencia comprobados

Los manifiestos empaquetados de debug y release para org.infinilect.app tienen Internet, estado de red y el permiso interno de receptores dinámicos. Solo Launcher y ProfileInstallReceiver figuran exportados, y este último requiere DUMP. Release declara depuración desactivada. Ambos APK incluyen arm64-v8a, armeabi-v7a, x86 y x86_64. El inventario de entradas ZIP no muestra nombres identificables de archivos de credenciales, claves privadas o fixtures de prueba. La búsqueda de patrones de credenciales comunes en Git no coincidió. Son comprobaciones acotadas y no sustituyen el escaneo continuo.

Las dos APK de desarrollo de referencia de la PR #39, be97f00 y 0f2b17d, tienen package org.infinilect.app, versionCode 1 y el mismo certificado público SHA-256. Apksigner verificó sus esquemas v2/v3. Se usaron únicamente esos binarios existentes para comparar sus certificados. La compatibilidad de una firma pública futura y su instalación conservando datos quedan pendientes.

## Hallazgos

| ID | Severidad | Ubicación, condición e impacto | Corrección o estado |
| --- | --- | --- | --- |
| RA-01 | MEDIUM | [DesktopPdfEngine.kt:105](../../app/src/desktopMain/kotlin/org/infinilect/app/reader/pdf/DesktopPdfEngine.kt#L105), preflight de imágenes y PageDrawer. La página podía tener raster de salida pequeño y una imagen de origen con dimensiones declaradas fuera de un presupuesto razonable. Posible presión de memoria o fallo al decodificar. | Límite de imágenes declaradas antes del decodificador. Regresiones de recurso sin dibujar, imagen inline, máscara alfa y control de un escaneo A3 a 300 ppp. |
| RA-02 | MEDIUM | [FilePdfPreparer.kt:40](../../app/src/jvmSharedMain/kotlin/org/infinilect/app/reader/pdf/FilePdfPreparer.kt#L40) y PdfRenderer. Un PDF se analiza dentro del proceso y puede consumir recursos de motor por encima de los presupuestos de salida. | Riesgo residual. Aislar el parser requiere diseño de IPC y autorización para el cambio arquitectónico. |
| RA-03 | MEDIUM | [settings.gradle.kts:1](../../settings.gradle.kts#L1) y wrapper. Repositorios fijos y checksums del wrapper y Foxlet, pero sin verificación reproducible de todos los artefactos Maven. | Implementada verificación SHA-256 estricta de artefactos y metadatos Maven. |
| RA-04 | MEDIUM | [AndroidUpdates.kt:22](../../app/src/androidMain/kotlin/org/infinilect/app/updates/AndroidUpdates.kt#L22) y proceso de release. Aún falta la validación física de la firma pública y su compatibilidad con las instalaciones de desarrollo. | Pendiente antes de publicar. Instalación integrada bloqueada. |
| RA-05 | LOW | Main sin CI versionada. Una revisión futura puede prescindir de comprobaciones automáticas. | Implementado workflow de verificación de solo lectura para PR y main. |
| RA-06 | LOW | Discovery Android en realme C55. Una búsqueda de Wikisource puede dejar el libro pendiente. | Resolución aplazada. La comprobación desde ficha o la segunda búsqueda son alternativas. [Issue #40](https://github.com/SirOtter0/INFINILECT/issues/40) |
| RA-08 | MEDIUM | [build.gradle.kts:1](../../build.gradle.kts#L1), classpath AGP 9.3.1. Seis módulos resuelven versiones con avisos upstream críticos, altos o medios. Sin ruta desde los lectores y fuentes en los binarios actuales. | Diez avisos remediados en el classpath de build. Versiones efectivas y alcance arriba. |
| RA-09 | LOW | OfflineDownloads.enqueue y remove. Un rechazo de persistencia podía retener progreso de una solicitud sin registro. Una eliminación parcial podía dejar su último progreso en memoria aun desaparecido el registro. | Retrasar la inicialización hasta aceptar la descarga y limpiar el progreso si la recarga confirma que el registro ya no existe. Regresión con backend de prueba que conserva el StateFlow original antes de eliminar el registro y comprueba su reinicio. |
| RA-07 | INFORMATIONAL | [ApplicationInterfaceTest.kt:578](../../app/src/desktopTest/kotlin/org/infinilect/app/ApplicationInterfaceTest.kt#L578) y PageSpreadLayoutTest Desktop. El historial distingue fallos Compose RectList y un gesto anterior estabilizado con reloj sintético. | Seguimiento. Ambas suites completas, antes y después, pasaron. La intermitencia no se reprodujo y su causa sigue abierta. |

## Línea base, regresión y comprobaciones finales

Línea base completa de 13 min 13 s. Core JVM 60, Core Android-host 60, App Android-host 1.536, App Desktop 1.823. Total 3.479, sin fallos, errores ni omisiones. Compilaron Android debug, Android release sin firmar y Desktop. Los manifiestos, recursos y lint se comprobaron con el pipeline base.

La regresión PDF falló en la base porque el preflight acepta el recurso sintético con dimensiones fuera del presupuesto. En Descargas, los dos casos de rechazo de persistencia y eliminación parcial también fallan en la base por conservar progreso del registro inexistente.

| Suite | Antes | Después | Fallos, errores u omisiones |
| --- | ---: | ---: | ---: |
| Core JVM | 60 | 60 | 0 |
| Core Android-host | 60 | 60 | 0 |
| App Android-host | 1.536 | 1.537 | 0 |
| App Desktop | 1.823 | 1.825 | 0 |
| **Total** | **3.479** | **3.482** | **0** |

Pasaron la nueva regresión PDF y los dos casos de progreso residual. También pasaron las suites dentro de la ejecución global para EPUB, PDF, TXT, CBZ, descargas, biblioteca, historial, preferencias, Discovery, proveedores y actualizador. Las tres traducciones tienen 599 cadenas y tres plurales. Los ocho casos de política y los dos manifiestos empaquetados se verificaron. La pasada del escáner limitada al diff dio cero coincidencias y pasó git diff --check.

Se compilaron Android debug y release sin firmar y se comprobó alineación de 16 KiB en ambos. Se generó un app image Linux x86-64 con el launcher ejecutable y la clase principal org.infinilect.app.MainKt. La salida de 169.666.080 bytes es del artefacto empaquetado, no una medición de memoria en ejecución. La apertura del launcher en un escritorio empaquetado y las distribuciones macOS/Windows quedan pendientes.

Comando de suites y artefactos, con los cuatro tests forzados con --rerun en ambas pasadas. En el host se usó la distribución Gradle 9.7.1 ya verificada y el SDK local.

~~~sh
./gradlew --continue :core:jvmTest --rerun :core:testAndroidHostTest --rerun \
  :app:testAndroidHostTest --rerun :app:desktopTest --rerun \
  :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:check \
  :androidApp:lintRelease :androidApp:verifyDebugLanguageResources \
  :desktopApp:compileKotlin :desktopApp:createDistributable
~~~

El app image se añadió solo a la pasada final. La pasada final terminó en 18 min 55 s. Lint debug y release terminaron con cero errores y un aviso preexistente GradleDependency por variante.

### Pruebas de remediación

Se volvieron a ejecutar las cuatro suites con el classpath corregido. Pasaron **3.484 pruebas**, sin fallos, errores ni omisiones.

| Suite | Pruebas |
| --- | ---: |
| Core JVM | 60 |
| Core Android-host | 60 |
| App Android-host | 1.537 |
| App Desktop | 1.827 |

El caso de descarga parcial conserva el flujo obtenido antes de borrar y comprueba que pierde su contador aunque falle la limpieza posterior. El caso comprueba también el estado que observa un nuevo suscriptor. Las pruebas PDF cubren las rutas reales de imagen inline y máscara de transparencia y comprueban que una imagen dentro del límite se puede dibujar. El escaneo A3 de 3.508 × 4.961 píxeles a 300 ppp se rasteriza dentro del presupuesto de salida. Las pruebas de página doble completaron esta pasada de Desktop con renderizado lento en Skiko. Se mantiene el seguimiento histórico de RA-07. La comprobación final con `--dependency-verification strict` terminó en 3 min 3 s y reprodujo el mismo grafo. Gradle reutilizó los resultados de las cuatro suites recién ejecutadas. Kotlin Footguns dio cero coincidencias sobre el diff y pasó `git diff --check`.

Para la resolución limpia se inició un `GRADLE_USER_HOME` vacío. El primer intento estricto rechazó cinco POM o metadatos de módulos padre ausentes. Sus bytes se contrastaron con Maven Central antes de fijarlos. El segundo intento resolvió el classpath y reprodujo las 23 configuraciones runtime y sus 2.682 filas sin módulos irresueltos. En un checkout separado, la modificación deliberada del hash del JAR de AGP fue rechazada con el modo estricto.

La ejecución completa con el nuevo classpath duró 25 min 54 s. Generó debug y release sin firmar, comprobó los manifiestos y los ocho casos de política, y verificó los recursos empaquetados de en, es y ca. Lint debug y release dieron cero errores y un aviso `GradleDependency` preexistente por variante. Se compiló Desktop y se generó el app image Linux x86-64 de 169.666.218 bytes.

Se verificó la integridad ZIP, la alineación de 16 KiB y el manifiesto de las dos APK. Conservan el paquete `org.infinilect.app`, SDK objetivo 37, SDK mínimo 26, permisos y cuatro ABI. La APK debug pasó la verificación de firma APK v2. Se comprobó que release quedó sin firmar. SHA-256 locales para trazar estas dos compilaciones:

| Artefacto | SHA-256 |
| --- | --- |
| Android debug | `9eedb1245865d6acea611f9572c9c94a53880f388fe44bcdad2932c9b4206c1c` |
| Android release sin firmar | `30bfbb88c8811fd21a1698f74a41cd01719e49a7f0bad7682b0708b900e9ac2c` |

## Candidato de release

**Propuesta** `versionName=0.1.0`, `versionCode=2`, etiqueta futura `v0.1.0`. GitHub devolvió listas vacías de releases y tags. El proyecto declara `0.0.1-SNAPSHOT` y código 1. Confirmar el mayor versionCode de todos los artefactos previamente distribuidos antes de fijarlo.

### Notas y changelog propuestos

- Primera distribución candidata para Android y Desktop con EPUB, PDF, TXT y CBZ, Biblioteca, Historial y progreso persistente.
- Descargas offline autorizadas, descubrimiento de Wikisource y catálogo experimental de Gutenberg opcional.
- Favoritos, etiquetas, estados de lectura y localización español, inglés y catalán.
- Límite preventivo para geometría de imágenes PDF en Desktop. La instalación de actualizaciones sigue deshabilitada mientras falte la confianza pública.
- Problema conocido en Android. Una búsqueda de Wikisource puede necesitar un segundo envío o comprobación desde ficha. [Issue #40](https://github.com/SirOtter0/INFINILECT/issues/40)

### Compilación, firma e instalación

1. Fijar SHA y versión aprobados. En checkout limpio ejecutar `./gradlew --dependency-verification strict :androidApp:assembleRelease :desktopApp:createDistributable` con JDK 21, SDK 37 y artefactos revisados. Comprobar el nombre que ve el usuario en el paquete Desktop y probar el app image Linux. Construir macOS y Windows en sus hosts.
2. Alinear la APK antes de firmar. Usar apksigner en el entorno de confianza con el almacén de firma autorizado y la entrada secreta de ese entorno. Verificar firmas y certificado público con `apksigner verify --verbose --print-certs`. Comparar paquete, código, ABIs, permisos y certificado o linaje contra el artefacto anterior. [Firma Android](https://developer.android.com/studio/publish/app-signing)
3. Ejecutar `sha256sum <archivo>` después de firmar y volver a comprobar los bytes entregados. Adjuntar SBOM y avisos. Si se habilita el instalador integrado, verificar también el manifiesto con la raíz independiente descrita en SECURE_UPDATES.
4. Instalar encima de una versión con certificado compatible. Con firma incompatible o alerta de Play Protect, detenerse y revisar la procedencia. Para recuperación, preferir una compilación corregida con código superior y certificado compatible, preservando los datos.

### Lista física antes de publicar

- [ ] Instalación limpia y actualización firmada. Comprobar Biblioteca, Historial, progreso, favoritos, etiquetas, preferencias, descargas y traducción antes y después.
- [ ] Play Protect activo, firma v2/v3, paquete, permisos y versión. Validar realme C55 y, cuando sea posible, API mínima y un dispositivo reciente.
- [ ] Abrir EPUB/PDF/TXT/CBZ e importar desde el selector. Probar rotación, poco espacio, cancelación, reintento, reinicio y lectura en modo avión.
- [ ] TalkBack, es/en/ca, claro/oscuro, 150% y 180%, retrato estrecho y paisaje. Revisar arranque frío, desplazamiento, portadas, PDF y memoria con mediciones físicas.
- [ ] Confirmar que el instalador integrado siga deshabilitado. Si se habilita, validar rechazo de firma, consentimiento y cancelación en otra revisión.

**Recomendación.** Revisar la PR como candidata y posponer la publicación hasta validar firma, instalación física y conservación de datos. Mantener seguimiento separado de la Issue #40.

<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright © 2026 SirOtter0 and INFINILECT contributors. -->

# Mihon e INFINILECT: rendimiento y viabilidad de separar interfaces

## Decisión y alcance

**La inspección no justifica migrar ahora a dos interfaces independientes.** En Android, INFINILECT ya ejecuta los artefactos Android de Jetpack Compose. Mover los mismos composables a un módulo Android no elimina por sí mismo su coste de composición, medida o dibujo. La recomendación es conservar la interfaz compartida, medir el Inicio real y evaluar componentes Android específicos únicamente cuando exista una ventaja demostrable.

Esta intervención de la PR #32 es **documental**: no cambia código ejecutable, dependencias, cachés, concurrencia, lectores, traducción, firma ni distribución. No implementa el prototipo propuesto. No copia código ni recursos de Mihon.

El cierre observado en el realme C55 **sigue sin causa confirmada y sin reproducirse en Android**. La comparación revela hipótesis y formas de comprobarlas; no constituye una corrección del cierre ni una demostración de fluidez física.

## 1. Revisiones, método y límites

Auditoría realizada el **15 de octubre de 2026**:

| Proyecto | Revisión exacta | Alcance |
| --- | --- | --- |
| Mihon | `446f6df82c390ab760802c2fce3b677bce3f31c1` | `main`, commit del 13 de octubre de 2026, «Update androidx.paging to v3.5.3 (#4099)»; lectura de fuentes y configuración |
| INFINILECT | `610c5af934f95c95566dafc9b03b065557ad8393` | Rama existente `perf/android-responsiveness`, después de la segunda fase de la PR #32; lectura de fuentes, resolución Gradle y comprobaciones locales |

Las referencias `[M…]` y `[I…]` enlazan archivos de esas revisiones inmutables. No se asume que el Mihon instalado por el usuario corresponda a la revisión estudiada. Se desconoce su variante, versión, compilación y configuración de lector. No se ha compilado ni ejecutado Mihon aquí.

Se inspeccionaron listas, estado, carga de imágenes, rutas de navegación, lectores y configuración de ambos proyectos. Se contrastó además la documentación primaria de [Compose Multiplatform y Jetpack Compose][P1], [rendimiento de Compose Android][P2] y [Baseline Profiles][P3]. Se resolvieron las dependencias efectivas Android y Desktop de INFINILECT; no se deduce la plataforma de ejecución únicamente del nombre de una dependencia declarada.

No hay realme C55, otro Android físico ni emulador conectado en este entorno. El usuario tampoco dispone ahora de PC para ADB. Las pruebas host no reproducen SurfaceFlinger, HWUI, controlador Mali, presión térmica, almacenamiento ni políticas de cierre de realme. Las conclusiones sobre causas de jank y crash que requieren esas señales se mantienen como hipótesis.

## 2. Arquitectura real de Mihon

### Módulos y versiones

`settings.gradle.kts` incluye `app`, `baseline-profile`, `core-metadata`, `core:archive`, `core:common`, `core:metro`, `data`, `domain`, `i18n`, tres módulos de iconos, `presentation-core`, `presentation-widget`, `source-api`, `source-local` y `telemetry` [M1]. La configuración reside también en `gradle/build-logic`. Existen módulos multiplataforma; eso no convierte su aplicación Android en una interfaz Desktop.

El catálogo declara Kotlin **2.4.21**, AGP **9.4.1**, BOM **`androidx.compose:compose-bom-beta:2026.10.00`**, Activity Compose **1.13.0**, Lifecycle **2.11.0**, Paging **3.5.3**, coroutines **1.11.0**, Coil **3.6.3**, Voyager **2.2.21-1.10.3**, Metro **1.4.5** y SQLDelight **2.4.1** [M2]. El BOM controla las versiones concretas de Compose: su número no es la versión individual de cada artefacto. Son declaraciones de la revisión, no un grafo resuelto por una compilación de Mihon en este trabajo.

`gradle/mihon.versions.toml` declara minSdk 26, targetSdk 36 y compileSdk 37.2. INFINILECT usa Kotlin **2.4.20**, AGP **9.3.1**, Compose Multiplatform **1.12.1**, coroutines **1.11.0**, SQLDelight **2.4.0**, minSdk 26 y target/compileSdk 37 [I1], [I2], [I3], [I4]. Ninguna de estas pequeñas diferencias demuestra por sí sola el origen de los tirones.

### No es exclusivamente Views ni exclusivamente Compose

La Biblioteca (`LibraryTab.Content`, `LibraryCompactGrid`), Historial y búsqueda global son Compose; los componentes comunes están en `presentation-core` y el diseño utiliza Material 3 [M5], [M6], [M7], [M8], [M9], [M19], [M35]. `HomeScreen` es principalmente el contenedor de pestañas Biblioteca, Actualizaciones, Historial, Explorar y Más: **no equivale al Inicio con recomendaciones de INFINILECT** [M4], [I6]. El carrusel de búsqueda global es un comparador más próximo, aunque tampoco representa la misma carga.

El lector usa `ReaderActivity` con layout/ViewBinding y un overlay Compose (`setComposeOverlay`), además de visores Android `View` [M22]. La ruta tradicional emplea `DirectionalViewPager`/ViewPager, RecyclerView para Webtoon y `SubsamplingScaleImageView`, PhotoView o `WebtoonStripImageView` para imágenes [M25], [M26], [M27]. Por tanto, deducir la tecnología del lector a partir de la Biblioteca sería incorrecto.

Hay también un renderer WebGPU: `ReadingMode.toViewer` selecciona `WebGpuViewer`/`WebGpuViewerContinuous` cuando `BasePreferences.highQualityRenderer` está habilitado; **su valor por defecto en esta revisión es `false`** [M23], [M24], [M31]. No es un WebView ni prueba de que el usuario esté usando esa ruta. No se recomienda trasladar este backend experimental a INFINILECT para resolver el jank de Inicio.

### Listas, composición y desplazamiento

* `LazyLibraryGrid` envuelve `FastScrollLazyVerticalGrid`, con columnas adaptativas de 128 dp o número fijo. `FastScrollLazyColumn` utiliza una `LazyColumn` real y `rememberLazyListState`; el scroller añade su propia medición/dibujo [M5], [M35]. No compone deliberadamente toda la biblioteca como una `Column` eager.
* `LibraryPager` usa `HorizontalPager` y evita componer páginas fuera de `currentPage ± 1` [M6]. El número de elementos que Compose precompone/remeasure internamente sigue dependiendo de su implementación y restricciones; no se atribuye una cifra de prefetch universal a todas sus listas.
* `LibraryCompactGrid` especifica `contentType` pero **no una key explícita**. `GlobalSearchCardRow` usa `items(titles)` sin key explícita. Historial diferencia tipos y usa claves derivadas de `hashCode` [M7], [M8], [M9]. No es correcto afirmar que todas sus listas se benefician de claves estables basadas en ID. INFINILECT ya tiene claves compuestas de publicación y tipos de contenido; quitarlas sería una regresión [I6], [I13].
* El carrusel global usa tarjetas de **96 dp** de ancho con título de hasta tres líneas [M8]. INFINILECT usa portadas de **144 × 216 dp** en Inicio y metadatos adicionales en descubrimiento [I6], [I7], [I8]. La cantidad de texto, tamaño de imagen y número de filas visibles son variables que hay que igualar antes de comparar frameworks.
* `CommonMangaItem` tiene gradientes, clip, selección, badges y acciones opcionales; el Historial usa `animateItem` [M9], [M36]. Mihon no elimina todos los efectos ni todas las animaciones. Su presencia tampoco prueba que esos mismos efectos sean baratos en cualquier composición.

### Imágenes y cachés

`MangaCover` usa **Coil `AsyncImage`**, proporción libro 2:3, placeholder/error y `ContentScale.Crop` [M10]. La llamada no fija un tamaño de decode explícito; transmite restricciones mediante la integración de Coil. El tamaño finalmente decodificado depende además del fetcher, decoder y request; no se presupone que todas las rutas eviten una imagen completa.

`App.newImageLoader` configura un loader compartido con OkHttp, fetchers/keyers propios y `CoverRequestInterceptor`; límite de paralelismo de fetch **8**, decode **3**, memoria mediante `maxSizePercent(context)`, crossfade de **300 ms × escala de animaciones** y RGB565 opcional para dispositivos que Android clasifica como low-RAM [M11]. No se inventa aquí un porcentaje/byte budget de memoria o disco: esta función delega esos valores a defaults de Coil. No se ha demostrado que el C55 sea clasificado low-RAM.

`MangaCoverFetcher` consulta archivos de portada de biblioteca, snapshots de disco de Coil y red; `MangaCoverKeyer` incorpora la revisión `lastModified` [M13], [M15]. `CoverCache` guarda portadas de biblioteca en el directorio específico de la aplicación obtenido por `getExternalFilesDir("covers")`, con fallback interno, y mantiene un índice de portadas personalizadas [M12]. **Esa clase no impone una cuota total de bytes**; no debe confundirse con la caché de disco gestionada por Coil. INFINILECT necesita conservar su almacenamiento privado y su propiedad estricta de recursos.

`CoverRequestInterceptor` agrupa por objeto/destino y tamaño. `DeDupeConcurrentRequests.apply` hace esperar al seguidor y luego permite volver a ejecutar su bloque para leer el resultado ya cacheado; no es simplemente «un único resultado Deferred compartido». El fetcher también deduplica por clave de disco [M13], [M14], [M37]. La cancelación/error libera al líder sin dejar un resultado exitoso ficticio. INFINILECT ya comparte trabajo por `PublicationId` dentro de su repositorio de leases [I9].

**Contraejemplo importante:** el decoder nativo alternativo de Mihon llama `decodeNext`, copia un frame de resolución completa a Bitmap y luego reduce en la ruta normal; la ruta de tiras serializa decodes, usa tiras de aproximadamente 4 MiB y un objetivo máximo de página de 128 MiB, manteniendo temporalmente el frame nativo [M16]. Esto describe esa implementación, no todos los formatos/decoders de Coil. No conviene copiar esa asignación transitoria ni ese presupuesto para miniaturas del C55. INFINILECT realiza bounds/preflight y `inSampleSize` antes de asignar su miniatura [I10].

Las tareas de imagen están ligadas al request/View o composición y hay rutas explícitas de dispose/recycle en el lector [M27]. No todas las precargas de capítulo se cancelan simplemente por salir un elemento del viewport: algunas pertenecen deliberadamente al lector/ViewModel. Deben distinguirse trabajo visible, precarga y almacenamiento persistente.

### Navegación, estado y trabajo fuera de Main

`HomeScreen` usa Voyager `TabNavigator`, `saveableState("currentTab", tab)` y `AnimatedContent` con fade-through de 200 ms [M4]. Guarda estado, pero **no mantiene necesariamente todas las pestañas compuestas**. `LibraryTab` obtiene un ViewModel y usa `collectAsStateWithLifecycle` [M19].

`LibraryViewModel` combina favoritos/categorías/filtros y query con debounce de 250 ms; deduplica antes de agrupar/ordenar, desplaza el pipeline a `Dispatchers.Default` y conserva resultados con `stateIn` [M17]. `SubscriberGate` retiene el estado y pausa entregas frecuentes sin suscriptores mediante conflation y una espera de cinco segundos en ese uso [M18]. Es un patrón distinto de destruir/reconsultar siempre, pero mantiene trabajo/estado con un coste que también hay que medir.

`BrowseSourceViewModel` utiliza `Pager(PagingConfig(pageSize = 25))` y `cachedIn(viewModelScope)` [M20]. La búsqueda global mantiene estados por fuente, cancela su job anterior, reutiliza resultados compatibles y limita peticiones con `Dispatchers.IO.limitedParallelism(5)`; antes de publicar comprueba actividad [M21]. Su ecosistema de extensiones y cardinalidad no son los de las dos fuentes de INFINILECT. No se propone importar su framework de navegación, DI o Paging para obtener beneficios aparentes.

La consulta de biblioteca de `MangaRepositoryImpl.getLibraryMangaAsFlow` usa `subscribeToList`; `data/QueryExtension.kt` aplica el gate antes de `mapToList`. El contexto por defecto de esa extensión es `EmptyCoroutineContext`, no IO universal: en la biblioteca el `flowOn(Default)` del pipeline es relevante [M17], [M38], [M39]. Se evitan así generalizaciones como «Mihon fuerza todas las consultas a IO».

### Lectores: técnicas transferibles y límites

| Ruta Mihon | Comportamiento comprobado | Aplicación posible |
| --- | --- | --- |
| `PagerViewer` | `offscreenPageLimit = 1`, navegación separada del zoom, scope cancelado al destruir [M25] | Mantener vecindad acotada y contratos de gestos en CBZ; no aumentar la precarga de INFINILECT por imitación |
| `WebtoonViewer` | RecyclerView/LayoutManager propios, `itemAnimator = null`, caché de cuatro ViewHolders; adapter/holders liberan recursos al reciclar [M26], [M27] | Evaluar reciclado/tiles para cómics muy largos si un perfil demuestra rebind o uploads caros; Android específico |
| `ReaderPageImageView` | Subsampling para imagen estática, PhotoView para animación y tiras Webtoon; dispose de requests, comprobación de pan, zoom/doble toque; algunas peticiones de lector deshabilitan memoryCache [M27] | Aplicable con adaptación a CBZ; miniaturas y páginas requieren políticas distintas. No reemplaza selección/texto/TOC de EPUB |
| `HttpPageLoader` | Jobs por página con `putIfAbsent`, eliminación condicionada al mismo job, prioridad a la página solicitada, ventana de chunks y cancelación al reciclar [M28] | Principio de identidad/posesión de jobs y prioridad visible; no importar descargas, extensiones o sus tamaños de chunks |
| `ArchivePageLoader` | Enumera entradas ordenadas y entrega proveedores de stream; cierra ArchiveReader en `recycle`, sin decodificar todas las páginas al enumerar [M29] | INFINILECT ya separa documento/bytes y raster; conservar validación CBZ propia |
| `ReaderChapter.ref/unref` | Mantiene loaders mientras tienen propietario y recicla al llegar a cero; ViewModel coordina capítulos y progreso [M30] | Patrón de ownership ya presente en leases/tickets de INFINILECT, no sustituir por reciclado agresivo |
| WebGPU opcional | Un executor de decode y otro de streaming, cache con páginas fijadas durante transiciones, prioridad/evicción por distancia; `destroy` cancela, interrumpe workers y limpia recursos [M31] | Enseña a evitar liberar la imagen que se dibuja; backend y presupuestos no son una mejora portable demostrada |

Ninguna técnica de raster sustituye el índice semántico, reflow, selección, enlaces ni correspondencia Original/Traducido de EPUB. Aplicar ViewPager de imágenes al EPUB destruiría requisitos que funcionan actualmente [I19], [I20], [I23].

En INFINILECT, `PagePolicy` y `PageReaderController` retienen como máximo tres páginas en single/continuo o cuatro en doble, con worker de decode serial y rechazo por generación/ticket. EPUB usa ventanas de hasta 128 bloques/65.536 unidades de texto y dos ventanas retenidas; sus ilustraciones tienen un worker y dos imágenes retenidas. Son presupuestos por subsistema, no un límite global de RAM del proceso [I19], [I21], [I28], [I33], [I34]. Se deben sumar los temporales y conversiones al perfilar. No se han cambiado esos límites ni los límites de archivos/ZIP/XML durante esta investigación.

### Configuración de distribución: variable de comparación

Mihon habilita minificación, shrink de recursos y `isProfileable` en release; tiene módulo `baseline-profile`, ProfileInstaller y perfiles de startup/baseline versionados [M3], [M32], [M33]. Su generador visita pestañas. Sus Macrobenchmarks miden **StartupTimingMetric**, frío/templado/caliente, diez iteraciones y variantes de compilación. **No constituyen un benchmark publicado de scroll del Inicio equivalente ni aportan FPS comparables en este C55.**

INFINILECT se ha probado mediante APK debug y no declara ese pipeline de perfiles en su host Android [I3]. Comparar debug con una posible distribución optimizada de Mihon mezcla configuración y arquitectura. Una comparación futura debe igualar optimización/compilación y firma de prueba autorizada; este trabajo no altera release ni firma. Tampoco se atribuye todo el problema a debug: puede existir jank real de implementación.

## 3. Comparación directa

Clasificación: **D** directamente aplicable; **A** requiere adaptación; **Android** específica de plataforma; **N** no recomendable; **M** necesita medición. Que un patrón sea aplicable no significa que falte en INFINILECT.

| Aspecto | Mihon | INFINILECT | Diferencia y oportunidad | Clase |
| --- | --- | --- | --- | --- |
| Listas verticales/grid | Lazy wrappers + grid adaptativa; biblioteca paginada por categorías [M5], [M6], [M7], [M35] | LazyColumn Inicio, LazyRows y LazyVerticalGrid Biblioteca [I6], [I13] | Ambos virtualizan. Medir measure/layout del carrusel anidado, no reemplazar por RecyclerView por defecto | M |
| Identidad | `contentType` en grid; no key explícita en compact grid/carrusel global [M7], [M8] | Clave compuesta propietario/ID y `contentType` [I6], [I13] | Conservar las garantías existentes y rechazo de duplicados; no copiar claves posicionales | D conservar; N copiar |
| Portadas | Coil AsyncImage, pipeline Android, formatos ampliados [M10], [M11], [M16] | Repositorio propio, lease por elemento; decoder Android BitmapFactory y conversión específica [I8], [I9], [I10], [I11] | Instrumentar decode/copy/upload por separado; cambiar de loader no prueba mejorar dibujo de imágenes residentes | A, M |
| Memoria de portadas | Porcentaje Coil dependiente de plataforma; biblioteca tiene archivos persistentes separados [M11], [M12], [M13] | 24 slots incluyendo pendientes/negativos/activos; 192×288 máximo; un trabajador; sin caché propia de thumbnails en disco [I9] | Menor paralelismo no explica por sí solo jank en caliente. Mantener límites hasta medir miss rate y memoria transitoria | M |
| Disco/revisión | `lastModified`, CoverCache y snapshots Coil [M12], [M13], [M15] | Identidad de publicación/propietario; cover local revalida digest del payload en miss [I12] | Candidato: thumbnail privado derivado, acotado y revisionado, solo si se comprueba coste al volver a una portada expulsada | A, M |
| Navegación | Voyager saveableState + transición; ViewModels por pantalla [M4], [M19] | Session retenida, SaveableStateHolder por destino, estados de scroll en raíz; Android ViewModel retiene runtime [I5], [I14], [I27] | Ya evita recrear sesión. Medir collectors activos en segundo plano; no introducir segundo router ni segunda sesión | A, M |
| Estado | Lifecycle collection, `flowOn(Default)`, `distinctUntilChanged`, SubscriberGate [M17], [M18], [M19], [M20], [M21] | StateFlows compartidos, secciones con `remember`; parte del ranking/transformación está en composición [I6], [I14], [I15], [I16], [I17], [I18] | Seleccionar estado mínimo por sección y mover derivación cara si se demuestra coste; conservar instantánea estable | A, M |
| Red/paginación | Paging 25; global search con cinco llamadas acotadas [M20], [M21] | Dos peticiones de catálogo simultáneas; 8 páginas LRU, TTL 5 min, 25 resultados/página y límites de resultados [I17], [I18] | No subir concurrencia a cinco/ocho ni sustituir los límites de adquisición por los de extensiones | D conservar; N copiar cifras |
| Lectores | Views especializadas + overlay Compose; WebGPU opt-in [M22], [M23], [M24], [M25], [M26], [M27], [M28], [M29], [M30], [M31] | Presentación Compose compartida, raster/parsers por plataforma, CBZ coalescing y tickets [I19], [I20], [I21], [I22], [I23], [I28], [I29] | Tiles/decoder específico solo para cuello de botella CBZ demostrado; ninguna razón para reescribir EPUB/traducción | Android, A, M |
| Configuración | Release R8/perfiles y benchmarks startup [M3], [M32], [M33] | Debug + FrameMetrics opt-in y pruebas host [I3], [I25], [I26] | Asegurar paridad de builds y compilación antes del experimento; perfiles no sustituyen corregir layout o ownership | Android, M |

## 4. ¿Compose Multiplatform añade una emulación Desktop en Android?

**No.** La documentación de Kotlin explica la selección mediante Gradle Module Metadata de los artefactos Android publicados por Google [P1]. Se verificó en este repositorio con:

```sh
./gradlew :androidApp:dependencyInsight --configuration debugRuntimeClasspath \
  --dependency androidx.compose.ui:ui-android
./gradlew :androidApp:dependencies --configuration debugRuntimeClasspath
./gradlew :desktopApp:dependencies --configuration runtimeClasspath
```

Resultado efectivo: Android resuelve **`androidx.compose.ui:ui-android:1.12.1`**, **`androidx.compose.foundation:foundation-android:1.12.1`** y **`androidx.compose.runtime:runtime-android:1.12.1`** a través de las coordenadas multiplataforma. No aparece Skiko en su grafo `debugRuntimeClasspath`. Desktop resuelve **`org.jetbrains.compose.ui:ui-desktop:1.12.1`**, **Skiko AWT 0.150.1** y el runtime Linux x64 correspondiente. Se usó Gradle 9.7.1/JDK 21 y dependencias verificadas ya disponibles localmente; los logs quedaron fuera del repositorio.

| Tecnología | Composición/renderizado e integración | Implicación |
| --- | --- | --- |
| Jetpack Compose Android | Runtime/compiler Compose, árbol de layout y dibujo integrado mediante el host Android; canvas/HWUI y ciclo de vida Android | No exige Views tradicionales para ser una interfaz nativa Android |
| CMP en Android | Código common compilado para JVM/Android que enlaza esos artefactos Jetpack; actuals para servicios de plataforma | Compartir código no introduce un renderizador Desktop. Puede limitar las APIs elegidas o el diseño de adaptaciones, no demuestra penalización automática por frame |
| RecyclerView/Views | Views y ViewHolders reciclados, DiffUtil/layout manager, pipeline Android sin composición Compose para esos hijos | Puede servir a un componente concreto. También paga measure/layout/draw, binds, imágenes y gestión de recursos; interop añade coordinación de estado/gestos |
| Compose Desktop | Artefactos desktop, host de ventana/AWT y Skiko; servicios de entrada, fuente e imagen distintos | Los tiempos software-Skia host no son frame timings Android ni validan SurfaceFlinger/GPU del C55 |

En INFINILECT están compartidos navegación/presentación, tarjetas, Lazy layouts, controles y gestos de los lectores. Ya son específicos: `MainActivity`, SAF/picker, apariencia de barras, decodificación y conversión de raster, PdfRenderer Android frente a PDFBox Desktop, engine HTTP Android frente a Java, selección contextual y Foxlet Android frente a proveedor Desktop no disponible [I2], [I3], [I4], [I10], [I11], [I15], [I27], [I31]. Las APIs de traducción no necesitan rehacerse para añadir un componente de presentación Android.

Hay costes reales posibles de compartir UI: igualar densidad/mouse/touch, estado de pantalla sin Lifecycle Android en common, gestos portables, conversión intermedia de raster y elegir componentes comunes que no aprovechan un widget especializado. Esos costes deben medirse en sus rutas. Usar Material de Compose compartido frente a Material 3 de Mihon es una diferencia de componente/diseño, no prueba de un runtime más lento.

Clasificación del diagnóstico actual:

* **Implementación comprobada:** existían alturas dependientes de metadatos; se estabilizaron en la fase anterior. Ranking/índices derivados y lectura de estados amplios siguen en `HomeScreen`; el coste por frame no está probado [I6], [I7], [I25].
* **Configuración comprobada:** variantes debug frente a release potencialmente diferentes; toolchains y componentes diferentes [I2], [I3], [M2], [M3].
* **Dependencias comprobadas:** Android usa Jetpack; no hay Skiko Desktop en el runtime Android. Un cambio de versión podría alterar rendimiento, pero no hay bisección que lo demuestre.
* **Arquitectura:** la abstracción de raster obliga a copiar para producir el ImageBitmap Android; hay límites y trazas existentes. No demuestra tirones una vez residentes las imágenes [I10], [I11].
* **Hardware/causas no demostradas:** GC, uploads GPU, scheduling térmico, saturación CPU, LMKD, bug de Compose/driver o una excepción concreta. No existe captura del C55 para atribuir el cierre a ninguna.

## 5. Tres opciones de arquitectura

| Opción | Reutilización y cambios | Ventaja potencial | Coste/riesgo y condición |
| --- | --- | --- | --- |
| **1. Mantener CMP** | Todos los módulos/pantallas actuales; optimizaciones locales y perfiles comparables | Menos divergencia; siguen disponibles división de estado, trabajo derivado fuera de Main, simplificar layout/draw medido y prioridad visible | No garantiza fluidez por sí sola. Primera elección mientras no exista perfil causal; requiere medir Android además de host |
| **2. Compartida + componentes Android** | Source sets `androidMain`/`desktopMain`, `expect/actual` pequeños o servicios inyectados como los actuales; posible AndroidView aislado | Optimizar conversión, decoder o un lector específico sin duplicar Biblioteca/EPUB/settings | Contratos públicos de ownership, tamaño, cancelación, selección y test. Evitar un `expect HomeScreen` que esconda toda una segunda UI; adoptar solo ventaja medida |
| **3. UIs independientes** | Núcleo/datos compartidos; módulo Android con Jetpack Compose y frontend Desktop con Compose Desktop; presentación distinta | Libertad de UX y APIs por plataforma; posible evolución especializada | Misma base de runtime en Android; no hay mejora automática. Duplica interacción, accesibilidad, localización, pruebas, coordinación de versiones y correcciones |

La opción 2 es una continuación de separaciones existentes, no obliga a migrar. Un seam de imagen debería aceptar identidad/revisión, tamaño y lease cancelable; no URLs arbitrarias ni acceso del núcleo a Bitmap/Context. La opción 3 requiere extraer contratos reutilizables del actual `:app`: **`:core` sí es independiente de Compose, pero `:app` no es un módulo de lógica sin UI**. Por ejemplo, `PublicationCovers` expone ImageBitmap y la geometría de páginas usa Offset/Size de Compose [I9], [I29]. Los parsers, modelos semánticos, stores y varios controllers sí son reutilizables; la compilación de un nuevo módulo sin Compose exigiría depurar esos límites o aceptar dependencias de geometría/presentación explícitas.

Una separación razonable futura podría tener `:core`, datos/servicios compartidos, estados/controllers compartidos, UI Android, UI Desktop y, opcionalmente, UI de lectores compartida. Gradle necesitaría invertir dependencias sin ciclos, mover paquetes/internal APIs con pruebas, declarar Compose Android/Activity/Lifecycle solo donde corresponda y mantener variantes JVM/Android de SQLite, red, PDF e inferencia. No basta con duplicar `MainActivity`.

Los ViewModels Android pueden envolver los mismos StateFlows/controllers y poseer jobs mediante lifecycle sin repetir adquisición, ranking, progreso o validación. Desktop necesita su propio propietario de sesión. Foxlet, instalador atómico, licencias/modelos, caché y un trabajador nativo permanecen servicios Android existentes; las dos UIs deben usar el mismo almacén y locators. Compartir inicialmente los lectores reduce riesgo, pero **ya no sería una separación completa de interfaces**. Rehacerlos no aporta un beneficio demostrado para Inicio.

RecyclerView se considera solo como experimento aislado si el perfil localiza coste en el layout virtualizado y una implementación equivalente lo reduce. No se recomienda convertir toda la aplicación a Views ni introducir loaders, Voyager, Metro, Paging o WebGPU solo para parecerse a Mihon.

## 6. Inventario real y estimación de esfuerzo

Conteo reproducible de archivos Kotlin y líneas físicas, incluidos comentarios/blancos, **solo source sets de producción indicados**: `app/commonMain` 79 archivos / 8.337 líneas; `app/jvmSharedMain` 56 / 6.661; `app/androidMain` 13 / 671; `app/desktopMain` 13 / 667; host Android 2 / 152; host Desktop 1 / 29. No mide complejidad ni líneas a duplicar. `:core` contiene los contratos de publicación/documentos/recursos/colecciones/progreso [I1], [I2].

Las siguientes estimaciones son para **opción 3 con presentación Android distinta**, dejando Desktop operativo; no son un compromiso. Una jornada = una persona con unas seis horas efectivas, conocedora del repositorio. Incluyen adaptación y pruebas enfocadas; no revisión física final, traducciones nuevas ni nuevas funciones. Incertidumbre aproximada ±40% y mayor en selección/gestos. Las líneas entre paréntesis son archivos de presentación ancla, no todo su subsistema.

| Pantalla/componente | Reutilizable | Adaptar o reescribir / dependencias | Riesgos y pruebas | Jornadas |
| --- | --- | --- | --- | ---: |
| Inicio (`HomeScreen`, 167) | Datos locales, DiscoveryCatalog, ranking, thumbnails | Presentación de secciones/carruseles, suscriptores y scroll | Llegadas parciales, claves, cache fría/caliente y carga concurrente | 3–5 |
| Biblioteca (`CollectionScreen`, 140, compartido con Historial) | CollectionsController, membership/progreso, fuentes | Grid, selección/context menu, detalles | Multiselección, Back, confirmación y no borrar archivos | 3–5 |
| Historial (mismo archivo) | Orden/fechas y stores | Filas, separación cover/details/resume | Eliminar solo historial, locator intacto y scroll | 2–3 |
| Búsqueda (`DiscoveryUi`, 221) | Controllers, errores, fuentes, derechos/paginación | Filtros, grids, detalles, lifecycle | Stale results, cambio de pestaña, retry y límites | 4–6 |
| Ajustes/perfil (`ApplicationSettingsScreen`, 86; `ProfileEditor`, 93) | Preferencias, migración, country/language resources | Filas, editor, foco y diálogos | Persistencia, grandes fuentes, tema/barras y teclado | 3–5 |
| Detalles (`PublicationPresentation`, 158) | Metadatos/sinopsis, membership y acción contextual | Modal/página, constraints y foco | Scroll largo, avisos legales, Start/Continue | 2–3 |
| Importación (`App` + picker) | Validación, copia atómica, ownership, progreso de import | Binding de SAF/resultados y feedback | Cancelación, duplicado, permisos existentes y retorno | 2–3 |
| EPUB (`EpubReader`, 463; controles 194) | Parser/índice, ventanas/controller/locators y traducción | Lazy layout, rebase, selección, enlaces, chrome | EPUB2/3, deep restore, Unicode, cambio tipográfico y Back | 8–14 |
| CBZ (`PageReader`, 436; `ComicControls`/gestos adicionales) | Archivo seguro, decode, coalescing, spreads/controller | Canvas/gestos/chrome y acknowledgements de frame | RTL, dobles, pinch/pan, slider y progreso confirmado | 8–14 |
| PDF (`PdfReader`, 56) | Controller, PdfRenderer/PDFBox y límites | Presentación y estado de página | Cierre/reapertura, resize y liberación de documento | 2–4 |
| TXT (`TextReader`, 125) | Preparación, locator y persistencia | Selección/scroll y chrome | Fuente grande, copia, enlaces aplicables y restore | 2–4 |
| Traducción (controles EPUB, 94) | Motor Foxlet Android, segmentación, cache, mapping | Acciones selección y panel Original/Traducido | Exactitud selección, stale native result y locator original | 3–6 |
| Modelos (`LanguageTranslationPanel`, 141; `TranslationModels`) | Instalación atómica, hashes, pin/temporary/quotas | Presentación y observación de progreso | Cierre durante descarga, retry, limpieza y no tocar libros | 3–5 |
| Shell, seam de módulos y navegación (`App`, 360; hosts) | ApplicationSession/Sources y ProgressPersistence | Gradle/visibilidad, inyección, adaptadores UI/lifecycle | Sesión única, rotación, cinco pestañas y salida de lectores | 5–8 |

Suma orientativa de esas partidas: **50–85 jornadas**; con integración transversal, accesibilidad y regresión física de ambas plataformas: **60–105 jornadas**. Se presupone reutilizar comportamiento y mantener los motores, no reimplementarlos. Un nuevo renderer de cómic o cambio de biblioteca de imágenes quedaría fuera. Las partidas EPUB/CBZ/traducción se solapan en infraestructura; el rango refleja esa incertidumbre, no una contabilidad exacta.

Una variante que separe solo el shell/pantallas de aplicación y **mantenga los lectores compartidos** tendría aproximadamente **30–50 jornadas** incluida integración, no una UI totalmente independiente. Un experimento de Inicio equivalente, sin migración, podría acotarse a **5–10 jornadas**, más acceso a dispositivo/medición. Una mejora localizada de opción 2 suele ser de **2–6 jornadas** por componente pequeño, sin garantía de beneficio.

Mantenimiento: cada cambio visible común requeriría dos implementaciones/revisiones y dos familias de UI tests en la opción 3, aunque los tests de dominio sigan compartidos. Como presupuesto de planificación, reservar aproximadamente **25–50% adicional del esfuerzo de cambios de UI comunes** y una validación por plataforma; no es un porcentaje de todo el desarrollo ni un dato medido. La complejidad de traducción/selección y lectores hace más costosa la divergencia que duplicar una pantalla de catálogo.

## 7. Prototipo comparativo propuesto — no implementado

Hipótesis: una implementación Android especializada de Inicio puede reducir trabajo de layout/estado/imagen, **no** que su mera ubicación en `androidMain` acelere Jetpack Compose.

1. **A**: Inicio actual. **B**: pantalla Android específica con el mismo contenido y diseño. Compartir snapshot de datos, controlador/repo de portadas, originales y miniaturas, cuotas, tamaños 144×216, fuentes, títulos/metadatos, gaps, gradientes, progreso, insets y acciones. Primero un traslado mecánico de control; después variar **una** técnica por ensayo. Si B cambia simultáneamente loader, dimensiones, filas o tipografía, no mide el coste de compartir UI.
2. Un selector solo de diagnóstico en una futura PR, no dos aplicaciones de producto. Mantener package/firma del circuito de pruebas y mismos datos locales. No alterar originales, history ni locators. No descargar libros reales para el benchmark.
3. Fixture sintético determinista con ID único, metadatos largos/cortos/Unicode, portadas de tamaños/colores controlados, duplicados upstream rechazados, retrasos y fallos de red/imagen programados. Añadir corpus local propio grande. Para aislar rendering, imágenes precargadas idénticas; luego repetir desde caché fría con llegadas incrementales.
4. Mismo C55, Android, resolución/densidad, refresco, fuente, tema, navegación por gestos/botones, estado térmico y batería. Builds de igual optimización/compilación; no debug A contra release B. Separar primer uso, JIT caliente y posible baseline/profile. No modificar producción sin autorización posterior.
5. Orden A/B alternado o aleatorio, por ejemplo diez repeticiones de 30 segundos por caso, calentamiento separado y pausas para estabilizar temperatura. Macrobenchmark/UIAutomator si compatible, FrameMetrics/FrameTimeline y Perfetto; gesto idéntico, mismos puntos de captura y sin build concurrente. Startup frío/caliente se mide aparte. Confirmar sincronización y coste del harness.
6. Repetir: A caché caliente, B llegada de recomendaciones, C cinco pestañas, D/E bibliotecas pequeña/grande, F EPUB largo/fuente grande, G CBZ rápido, H traducción pendiente, I volver de lector y J startup. Los casos F–J son controles de no regresión, no motivo para reescribir lectores en el experimento.

Métricas: duraciones totales y CPU/render por frame, P50/P95/P99 con número de muestras y umbral según refresco, frames fuera del deadline y congelados, cadencia/FPS efectivo durante gesto; latencia input→frame (marcas instrumentadas, no solo tiempo del callback); tiempo hasta primer contenido e interactivo; CPU por hilo, runnable/scheduling, GC/asignaciones, PSS/Java/native/graphics y picos; requests/decodes/cache hits y cola/tiempo por etapa. Separar memoria retenida de transitoria. Los histogramas FrameMetrics existentes aproximan percentiles por bins: usar las trazas crudas para precisión, no tratarlos como valores exactos.

Umbral de decisión **propuesto**, no resultado: mejora reproducible, por ejemplo ≥15% en P95 del caso caliente y reducción de frames perdidos en la mayoría de ejecuciones, sin peor P99/memoria ni regresiones funcionales. Publicar dispersión y térmica; un cierre invalida la aceptación. Si solo cambia la optimización de build, o A mejora igual con una pequeña adaptación, preferir esa corrección compartida. Si no hay diferencia consistente, descartar la migración como solución de rendimiento.

## 8. Cinco optimizaciones candidatas, por prioridad

No se implementan aquí: ninguna tiene todavía beneficio físico causal demostrado. Las garantías ya incorporadas (keys, residentes, un worker, tamaño de miniatura, slots y altura estable) no se vuelven a presentar como novedades.

| Prioridad y evidencia | Causa que pretende resolver / cambio mínimo | Riesgo y regresión | Cómo decidir/medir |
| --- | --- | --- | --- |
| **1. Estado mínimo por sección**. Mihon desplaza/filtra su pipeline y gates de suscripción; Home observa search completo y deriva `known`, preferencias/ranking en su composición [M17], [M18], [I6] | Aislar el estado derivado relevante, deduplicarlo antes de emitir y, si es caro, calcular fuera de Main manteniendo snapshot coherente. No añadir `@Stable` a modelos mutables ni `remember` indiscriminado | Stale ranking, datos de biblioteca fuera de fecha. Test: búsqueda/página ajena no recalcula filas sin cambios, llegada parcial y retorno conservan selección/scroll | Contadores diagnósticos de recomposición + trazas ranking/layout, con/sin carga; mejora en Main CPU/P95, no solo número de recomposiciones |
| **2. Revisar measure/draw de la tarjeta residente**. Ambos usan gradiente/clip; INFINILECT tiene más texto y geometría distinta [M8], [M36], [I7], [I8] | Si Perfetto muestra layout/draw dominante, aislar dibujo/caption, reusar medidas u omitir únicamente trabajo redundante comprobado; conservar diseño y accesibilidad | Más capas GPU, contraste roto o grandes fuentes recortadas. Test: tamaños idénticos antes/después, title Unicode y UI stress | Caso caliente sin HTTP/decode, FrameTimeline layout/draw/sync/GPU. Prototipo un cambio a la vez; ya se ha recordado el Brush y eliminado clip duplicado |
| **3. Reducir conversiones transitorias Android**. Bounds decode → Bitmap → IntArray → presentation Bitmap [I10], [I11], frente a integración directa de loader Android [M10], [M11] | Si copy/upload/GC domina en frío, seam de miniatura Android que transfiera ownership de una imagen ya reducida sin copia intermedia, conservando fallback Desktop y un trabajador | Liberación prematura, imagen de otro propietario y mayor memoria nativa. Test de dibujo tras evicción, cancelación no cooperativa y fallo durante conversión | Bytes/pico Java+native, GC, duración decode/copy, GPU upload. No mejora esperable en scroll realmente caliente sin nuevas conversiones |
| **4. Thumbnail privado persistente, solo tras medir misses**. Mihon reutiliza disco/revisión; cover local de INFINILECT rehash/extracción en miss [M12], [M13], [I12] | Cache derivada pequeña con clave digest+versión/tamaño/decoder, escritura atómica, límite y limpieza. Conservar validación original y ownership, no saltarse el hash porque el título coincide | Corrupción, cache obsoleta, espacio/I/O. Tests traversal/colisión/revisión/quota, cancelación y limpieza al eliminar | Número/tiempo de misses al recorrer >24 obras y regresar; I/O real del payload. No resolverá jank con todos los hits residentes |
| **5. Prioridad visible y suscripción por lifecycle**. Mihon prioriza página solicitada y cancela jobs identificados; INFINILECT ya tiene worker único y pausa discovery al salir [M18], [M28], [I9], [I14], [I18] | Medir si el FIFO de miniaturas o collectors ocultos hacen trabajo inútil; si se confirma, prioridad por viewport/edad con starvation controlada y adapters lifecycle Android. Mantener cuotas y rechazo de stale results | Reordenaciones, trabajo duplicado o blank en retorno. Test de cambio rápido de tab, fallos/red lenta, mismo ID y última lease cancelada | Cola/tiempo hasta portada visible, CPU de pestaña oculta, latencia de navegación. No aumentar workers ni cerrar el motor nativo por recomposición |

Para CBZ queda como propuesta posterior medir tiles/subsampling en imágenes enormes y acknowledgements de frame; para EPUB, layout del párrafo/selección grande y rebase. No se modifica ningún lector sin perfil. La inferencia Foxlet ya está separada y serializada; no se inicia al entrar en Home ni se propone ejecutar más trabajadores para competir con Main.

## 9. Crash pendiente: hipótesis y evidencia necesaria

Orden de investigación, **no probabilidades establecidas**:

| Hipótesis | Evidencia de código / límite del análisis | Señal que la confirmaría o descartaría |
| --- | --- | --- |
| Memoria transitoria/graphics durante cargas | Hay copias de miniatura y recursos de otras pantallas/modelos; 24 slots no limita toda la memoria del proceso. Un raster ARGB máximo de portada son 221.184 bytes: 24 equivalen a ~5,06 MiB de píxeles finales, **no** PSS ni pico total [I9], [I10], [I11] | `OutOfMemoryError`, allocation failure/GC thrashing, `ApplicationExitInfo` low-memory, LMKD; Perfetto PSS/native/graphics y picos correlacionados con decode/upload. La ausencia de excepción Java no descarta kill |
| Excepción lazy por keys/índices/restore | Keys compuestas, dedup y alturas estables reducen riesgo; no prueban ausencia de bug framework/restauración [I5], [I6], [I7], [I13] | `FATAL EXCEPTION` con `LazyLayout`, duplicate key, measure/placement/SaveableStateHolder, stack y versión. Reproducir misma llegada parcial/scroll, sin asumir que toda excepción lazy es altura |
| Ownership o callback tardío de imagen | Worker serial, leases, comparación de Entry/job, no reciclado del bitmap ya prestado y tests de evicción [I8], [I9], [I10], [I11], [I32] | Stack de imagen reciclada/uso tras cierre, tombstone native/HWUI; traza acquire/release/generation sanitizada y estado de job. Los tests host cubren contratos, no driver ni frame Android |
| Error recuperable no aislado en catálogo/cover | Failure por fuente y `Exception` de cover aisladas; CancellationException se propaga, errores fatales no se enmascaran [I9], [I17], [I18] | Stack del fallo real y etapa/exception class sin query ni texto. Comprobar cancelación/timeout/datos remotos defectuosos por la ruta exacta |
| Driver/GPU, OEM kill, ANR o presión térmica | Ninguna evidencia disponible; no equivale a bug de Compose/CMP | `SIGSEGV`/tombstone, EGL/HWUI/Vulkan/RenderThread, `REASON_ANR`, system kill/exit reason, estado térmico y frames sync/GPU. Correlacionar timestamps con ausencia/presencia de Java crash |

`PublicationCovers` no llama `Bitmap.recycle` sobre la imagen entregada a Compose; el bitmap temporal del decoder se recicla **después** de copiar sus píxeles [I9], [I10], [I11]. Eso descarta la afirmación genérica «la LRU siempre recicla lo que se está dibujando», no todos los problemas posibles. No se añaden catches de `Throwable`/OOM ni se ocultan errores fatales.

### Captura cuando vuelva a estar disponible un PC

La instrumentación opt-in actual y los comandos completos están en [ANDROID_PERFORMANCE.md](ANDROID_PERFORMANCE.md#c55-capture-instructions-ready-to-copy-device-execution-pending). No se añade telemetría ni un recolector remoto. Esta investigación no exige al usuario disponer de ADB para tomar una decisión provisional conservadora.

```sh
adb logcat -c
adb shell am start -W -n org.infinilect.app/.MainActivity --ez infinilect.performance true
adb logcat -v threadtime InfinilectCrash:E InfinilectExit:I InfinilectPerf:I AndroidRuntime:E libc:F '*:S' > infinilect-home-logcat.txt
```

Tras reproducir (detener el comando anterior con Ctrl+C), capturar además:

```sh
adb logcat -b crash -d -v threadtime > infinilect-home-crash.txt
adb shell dumpsys activity exit-info org.infinilect.app > infinilect-home-exit.txt
adb shell dumpsys meminfo org.infinilect.app > infinilect-home-memory.txt
adb shell dumpsys gfxinfo org.infinilect.app framestats > infinilect-home-frames.txt
```

`exit-info` requiere Android 11+ y su disponibilidad/detalle varía por fabricante; si el proceso ya murió, `meminfo` no reconstruye su pico. La sesión debe estar activada antes del fallo y se debe relanzar para consultar las salidas anteriores. `gfxinfo`/FrameMetrics y sus umbrales tampoco sustituyen el FrameTimeline de Perfetto. Para GC/LMKD/driver utilizar una captura de sistema local alrededor del fallo siguiendo el documento existente; puede contener actividad de otras apps y debe revisarse/filtrarse antes de compartir. No enviar libros, queries, nombre, país ni rutas de usuario. Las trazas actuales de etapas no contienen esos datos.

Sin PC: anotar variante/commit, tema, modelo cargado o no, red, pantalla/gesto exacto, frío/caliente y momento aproximado; comparar los mismos gestos con recomendaciones cargadas y durante su llegada. Una grabación breve puede ayudar a identificar el evento visual, aunque introduce carga y **no identifica una causa de cierre**. No pedir al usuario borrar biblioteca, desinstalar ni perder progreso para estas pruebas.

## 10. Verificación y mediciones

### Lo comprobado en esta intervención

* Checkout separado de Mihon fijado al SHA indicado, sin ejecutar su build ni incorporar sus fuentes/recursos a INFINILECT. Configuración y rutas de código citadas inspeccionadas; no se auditó exhaustivamente cada dependencia de Mihon.
* Resolución real de los grafos Android/Desktop y `dependencyInsight`: correcta, con la distinción de artefactos descrita. No nuevas dependencias.
* Invocación completa de tareas de verificación/compilación existente con JDK **21.0.12.1**, Gradle **9.7.1**, Linux x64, sin red de proveedores ni modelos en ejecución:

```sh
./gradlew :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources \
  :desktopApp:compileKotlin
```

En este workspace se empleó el wrapper externo de construcción ya existente para JDK/SDK/cache, AAR Foxlet verificado y Skiko host. **BUILD SUCCESSFUL**, 97 tareas accionables: 5 ejecutadas y 92 up-to-date. Las cuatro tareas de tests, compilación Desktop y APK estaban **UP-TO-DATE**; no se presentan como nuevas ejecuciones de sus casos. `verifyDebugLanguageResources` sí ejecutó de nuevo la comprobación del APK (en/es/ca). El aviso local de métricas SDK por `/home/agent/.android` no writable no hizo fallar la construcción; no se cambió HOME ni configuración del producto para silenciarlo.

Resultados completos **ya ejecutados sobre el mismo código de aplicación `610c5af`**, documentados en [ANDROID_PERFORMANCE.md](ANDROID_PERFORMANCE.md): **Desktop 1.542**, **Android-host 1.297**, cero fallos/errores/skips en las últimas suites completas; Core JVM/Android-host **59/59** resultados conservados. No se suman a las tareas up-to-date como si fueran otra ejecución. Hubo un fallo intermitente previo de arranque de fixture CBZ en un run Desktop; el test aislado y la suite completa posterior pasaron sin cambiar código ni aserciones. Su causa sigue abierta y no se equipara al cierre de Home.

No se añadieron tests de comportamiento porque solo cambia documentación. Se comprobaron referencias locales/de fuente, límites de líneas, diff whitespace y ausencia de binarios/secretos en el diff. No se publicó APK ni release, ni se alteró firma. No se ejecutaron benchmarks Android ni tests físicos nuevos.

### Medidas históricas relevantes, no inventadas como resultado nuevo

La fase anterior comparó el tile inicial y final en el mismo fixture host: JDK21/Linux, dos CPU, 8 GiB, software Skia, cinco muestras calientes de 60 pasos con 12 portadas residentes y sin nuevos loads [I25].

| Medida host previa | Control inicial | Tile final | Interpretación |
| --- | ---: | ---: | --- |
| Mediana / 60 pasos | 375,07 ms | 399,88 ms | No demuestra mejora; mediana final mayor |
| Rango | 357,41–429,78 ms | 370,26–414,39 ms | Se solapan |
| Bytes asignados en hilo llamante, mediana | 1.160.128 | 1.165.448 | No demuestra reducción; excluye otros hilos/native |
| Altura metadata presente/ausente, fuente 1,5× | 292 / 348 px | 300 / 300 px | Corrección comprobada de estabilidad geométrica, no velocidad Android |

El stress host anterior atravesó carga/frío/caliente, scroll mixto, fallos, duplicados y navegación: 35 cargas de portadas, máximo un worker y 24 entradas; ocho coroutines/1.200 leases comprobaron límites adicionales. Son pruebas de contratos, no FPS ni reproducción del cierre C55. No existe una cifra antes/después Mihon↔INFINILECT en el mismo Android que permita atribuir la diferencia a su arquitectura.

## 11. Licencias y atribución

La raíz de Mihon en la revisión auditada declara **Apache License 2.0** [M34]. Los archivos concretos citados no mostraron avisos alternativos que permitan atribuirles otra licencia por esta lectura; esto no sustituye una auditoría de todas las dependencias o ficheros de terceros. Según la [lista de licencias de GNU][P4], Apache-2.0 es compatible con GPLv3, pero no con GPLv2. INFINILECT es GPL-3.0-or-later.

Esta entrega es una implementación independiente de documentación/análisis y **no incorpora código, binarios, iconos ni arte de Mihon**. Si en el futuro se copiara código concreto, revisar su procedencia/licencia individual y dependencias, conservar copyright y licencia, identificar modificaciones y cumplir cualquier NOTICE aplicable y condiciones de patentes. La compatibilidad del código no concede derechos sobre marcas, ilustraciones, modelos o extensiones. No se recomienda introducir sus dependencias nativas sin revisar licencias y distribución individualmente. No es una garantía jurídica universal.

## 12. Recomendación y criterio de continuación

Mantener **opción 1**, preparando únicamente experimentos pequeños de **opción 2** donde la traza los justifique. No comenzar opción 3: los costes de duplicar interacción y probar lectores/selección/traducción son importantes y el runtime Android ya es Jetpack Compose. Mihon demuestra que Compose, Views especializadas, ownership y configuración pueden convivir; no demuestra que separar toda la UI acelere esta aplicación.

La siguiente decisión necesita dos evidencias: **motivo del cierre** y **perfil del caso caliente sin nuevas imágenes**. Si domina layout/draw, priorizar tarjeta/estado; si domina copia/GC durante llegadas, evaluar el seam Android; si solo se detectan misses al volver, evaluar caché derivada; si un componente especializado gana de forma reproducible sin regresiones, incorporarlo aisladamente. No declarar la PR lista ni fusionarla hasta la aceptación física pendiente.

Checklist C55: mismo build/configuración; Inicio cargado y durante carga; scroll vertical/horizontal rápido e inversión; cinco pestañas y regreso desde lector; biblioteca grande; EPUB con fuente grande y selección; CBZ rápido/RTL/dobles; traducción pendiente sin cargar modelos desde Home; offline/reconexión; observar cierres y memoria cuando sea medible. Conservar biblioteca/historial/posiciones. **Draft PR #32, sin merge.**

## Referencias primarias de código

Las referencias siguientes fijan revisión y punto de entrada; el texto identifica las funciones/rutas complementarias del mismo archivo. Los rangos no representan una medición de rendimiento.

- **M1**: [settings.gradle.kts][M1] (línea 32).
- **M2**: [gradle/libs.versions.toml][M2] (línea 1).
- **M3**: [app/build.gradle.kts][M3] (línea 77).
- **M4**: [app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt][M4] (línea 54).
- **M5**: [app/src/main/java/eu/kanade/presentation/library/components/LazyLibraryGrid.kt][M5] (línea 15).
- **M6**: [app/src/main/java/eu/kanade/presentation/library/components/LibraryPager.kt][M6] (línea 31).
- **M7**: [app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt][M7] (línea 13).
- **M8**: [app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt][M8] (línea 27).
- **M9**: [app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt][M9] (línea 33).
- **M10**: [app/src/main/java/eu/kanade/presentation/manga/components/MangaCover.kt][M10] (línea 18).
- **M11**: [app/src/main/java/eu/kanade/tachiyomi/App.kt][M11] (línea 221).
- **M12**: [app/src/main/java/eu/kanade/tachiyomi/data/cache/CoverCache.kt][M12] (línea 26).
- **M13**: [app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt][M13] (línea 47).
- **M14**: [app/src/main/java/eu/kanade/tachiyomi/data/coil/CoverRequestInterceptor.kt][M14] (línea 14).
- **M15**: [app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverKeyer.kt][M15] (línea 20).
- **M16**: [app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt][M16] (línea 29).
- **M17**: [app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt][M17] (línea 83).
- **M18**: [core/common/src/main/kotlin/tachiyomi/core/common/util/lang/SubscriberGate.kt][M18] (línea 26).
- **M19**: [app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt][M19] (línea 65).
- **M20**: [app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceViewModel.kt][M20] (línea 66).
- **M21**: [app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchViewModel.kt][M21] (línea 31).
- **M22**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt][M22] (línea 104).
- **M23**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReadingMode.kt][M23] (línea 79).
- **M24**: [app/src/main/java/eu/kanade/domain/base/BasePreferences.kt][M24] (línea 40).
- **M25**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt][M25] (línea 31).
- **M26**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt][M26] (línea 30).
- **M27**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt][M27] (línea 53).
- **M28**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt][M28] (línea 24).
- **M29**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ArchivePageLoader.kt][M29] (línea 12).
- **M30**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt][M30] (línea 8).
- **M31**: [app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webgpu/WebGpuViewer.kt][M31] (línea 86).
- **M32**: [baseline-profile/src/main/kotlin/mihon/baselineprofile/BaselineProfileGenerator.kt][M32] (línea 16).
- **M33**: [baseline-profile/src/main/kotlin/mihon/baselineprofile/StartupBenchmarks.kt][M33] (línea 18).
- **M34**: [LICENSE][M34] (línea 1).
- **M35**: [presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyList.kt][M35] (línea 60).
- **M36**: [app/src/main/java/eu/kanade/presentation/library/components/CommonMangaItem.kt][M36] (línea 72).
- **M37**: [app/src/main/java/eu/kanade/tachiyomi/data/coil/DeDupeConcurrentRequests.kt][M37] (línea 14).
- **I1**: [settings.gradle.kts][I1] (línea 18).
- **I2**: [app/build.gradle.kts][I2] (línea 7).
- **I3**: [androidApp/build.gradle.kts][I3] (línea 4).
- **I4**: [desktopApp/build.gradle.kts][I4] (línea 3).
- **I5**: [app/src/commonMain/kotlin/org/infinilect/app/App.kt][I5] (línea 59).
- **I6**: [app/src/commonMain/kotlin/org/infinilect/app/ui/HomeScreen.kt][I6] (línea 24).
- **I7**: [app/src/commonMain/kotlin/org/infinilect/app/discovery/DiscoveryUi.kt][I7] (línea 152).
- **I8**: [app/src/commonMain/kotlin/org/infinilect/app/ui/PublicationCover.kt][I8] (línea 54).
- **I9**: [app/src/commonMain/kotlin/org/infinilect/app/covers/PublicationCovers.kt][I9] (línea 15).
- **I10**: [app/src/androidMain/kotlin/org/infinilect/app/covers/CoverThumbnailDecoder.kt][I10] (línea 14).
- **I11**: [app/src/androidMain/kotlin/org/infinilect/app/media/RasterImageBitmap.kt][I11] (línea 10).
- **I12**: [app/src/jvmSharedMain/kotlin/org/infinilect/app/imports/FileLocalPublicationSource.kt][I12] (línea 131).
- **I13**: [app/src/commonMain/kotlin/org/infinilect/app/ui/CollectionScreen.kt][I13] (línea 25).
- **I14**: [app/src/commonMain/kotlin/org/infinilect/app/ApplicationSession.kt][I14] (línea 18).
- **I15**: [app/src/commonMain/kotlin/org/infinilect/app/ApplicationSources.kt][I15] (línea 16).
- **I16**: [app/src/commonMain/kotlin/org/infinilect/app/collections/CollectionsController.kt][I16] (línea 32).
- **I17**: [app/src/commonMain/kotlin/org/infinilect/app/discovery/DiscoveryCatalog.kt][I17] (línea 17).
- **I18**: [app/src/commonMain/kotlin/org/infinilect/app/discovery/HomeDiscovery.kt][I18] (línea 12).
- **I19**: [app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubPresentation.kt][I19] (línea 49).
- **I20**: [app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubReader.kt][I20] (línea 56).
- **I21**: [app/src/commonMain/kotlin/org/infinilect/app/reader/page/PageReaderController.kt][I21] (línea 43).
- **I22**: [app/src/commonMain/kotlin/org/infinilect/app/reader/page/PageReader.kt][I22] (línea 60).
- **I23**: [app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubTranslation.kt][I23] (línea 21).
- **I24**: [app/src/commonMain/kotlin/org/infinilect/app/reader/epub/TranslationModels.kt][I24] (línea 22).
- **I25**: [docs/ANDROID_PERFORMANCE.md][I25] (línea 8).
- **I26**: [app/src/androidMain/kotlin/org/infinilect/app/diagnostics/AndroidPerformanceTrace.kt][I26] (línea 9).
- **I27**: [androidApp/src/main/kotlin/org/infinilect/app/MainActivity.kt][I27] (línea 22).
- **I28**: [app/src/commonMain/kotlin/org/infinilect/app/media/Raster.kt][I28] (línea 6).
- **I29**: [app/src/commonMain/kotlin/org/infinilect/app/reader/page/PageViewportGeometry.kt][I29] (línea 15).
- **I30**: [app/src/commonMain/kotlin/org/infinilect/app/ui/LanguageTranslationPanel.kt][I30] (línea 23).
- **I31**: [app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubSelectionToolbar.kt][I31] (línea 40).
- **I32**: [app/src/desktopTest/kotlin/org/infinilect/app/HomeLoadingStressTest.kt][I32] (línea 23).

[M1]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/settings.gradle.kts#L32
[M2]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/gradle/libs.versions.toml#L1
[M3]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/build.gradle.kts#L77
[M4]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt#L54
[M5]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/library/components/LazyLibraryGrid.kt#L15
[M6]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/library/components/LibraryPager.kt#L31
[M7]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt#L13
[M8]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt#L27
[M9]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt#L33
[M10]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/manga/components/MangaCover.kt#L18
[M11]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/App.kt#L221
[M12]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/data/cache/CoverCache.kt#L26
[M13]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt#L47
[M14]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/data/coil/CoverRequestInterceptor.kt#L14
[M15]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverKeyer.kt#L20
[M16]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt#L29
[M17]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt#L83
[M18]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/core/common/src/main/kotlin/tachiyomi/core/common/util/lang/SubscriberGate.kt#L26
[M19]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt#L65
[M20]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceViewModel.kt#L66
[M21]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchViewModel.kt#L31
[M22]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt#L104
[M23]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReadingMode.kt#L79
[M24]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/domain/base/BasePreferences.kt#L40
[M25]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt#L31
[M26]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt#L30
[M27]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt#L53
[M28]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt#L24
[M29]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ArchivePageLoader.kt#L12
[M30]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt#L8
[M31]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webgpu/WebGpuViewer.kt#L86
[M32]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/baseline-profile/src/main/kotlin/mihon/baselineprofile/BaselineProfileGenerator.kt#L16
[M33]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/baseline-profile/src/main/kotlin/mihon/baselineprofile/StartupBenchmarks.kt#L18
[M34]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/LICENSE#L1
[M35]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyList.kt#L60
[M36]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/presentation/library/components/CommonMangaItem.kt#L72
[M37]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/app/src/main/java/eu/kanade/tachiyomi/data/coil/DeDupeConcurrentRequests.kt#L14
[I1]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/settings.gradle.kts#L18
[I2]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/build.gradle.kts#L7
[I3]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/androidApp/build.gradle.kts#L4
[I4]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/desktopApp/build.gradle.kts#L3
[I5]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/App.kt#L59
[I6]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/ui/HomeScreen.kt#L24
[I7]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/discovery/DiscoveryUi.kt#L152
[I8]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/ui/PublicationCover.kt#L54
[I9]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/covers/PublicationCovers.kt#L15
[I10]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/androidMain/kotlin/org/infinilect/app/covers/CoverThumbnailDecoder.kt#L14
[I11]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/androidMain/kotlin/org/infinilect/app/media/RasterImageBitmap.kt#L10
[I12]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/jvmSharedMain/kotlin/org/infinilect/app/imports/FileLocalPublicationSource.kt#L131
[I13]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/ui/CollectionScreen.kt#L25
[I14]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/ApplicationSession.kt#L18
[I15]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/ApplicationSources.kt#L16
[I16]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/collections/CollectionsController.kt#L32
[I17]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/discovery/DiscoveryCatalog.kt#L17
[I18]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/discovery/HomeDiscovery.kt#L12
[I19]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubPresentation.kt#L49
[I20]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubReader.kt#L56
[I21]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/page/PageReaderController.kt#L43
[I22]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/page/PageReader.kt#L60
[I23]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubTranslation.kt#L21
[I24]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/epub/TranslationModels.kt#L22
[I25]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/docs/ANDROID_PERFORMANCE.md#L8
[I26]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/androidMain/kotlin/org/infinilect/app/diagnostics/AndroidPerformanceTrace.kt#L9
[I27]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/androidApp/src/main/kotlin/org/infinilect/app/MainActivity.kt#L22
[I28]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/media/Raster.kt#L6
[I29]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/page/PageViewportGeometry.kt#L15
[I30]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/ui/LanguageTranslationPanel.kt#L23
[I31]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubSelectionToolbar.kt#L40
[I32]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/desktopTest/kotlin/org/infinilect/app/HomeLoadingStressTest.kt#L23

[P1]: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-and-jetpack-compose.html
[P2]: https://developer.android.com/develop/ui/compose/performance
[P3]: https://developer.android.com/topic/performance/baselineprofiles/overview
[P4]: https://www.gnu.org/licenses/license-list.html#apache2

[M38]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/data/src/main/java/tachiyomi/data/QueryExtension.kt#L13

[M39]: https://github.com/mihonapp/mihon/blob/446f6df82c390ab760802c2fce3b677bce3f31c1/data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt#L70

[I33]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/page/PagePreparer.kt#L8

[I34]: https://github.com/SirOtter0/INFINILECT/blob/610c5af934f95c95566dafc9b03b065557ad8393/app/src/commonMain/kotlin/org/infinilect/app/reader/epub/EpubMedia.kt#L12

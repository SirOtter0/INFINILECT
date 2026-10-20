# Localización de INFINILECT: español, inglés y catalán

Base: `6a95580a60f1119770c035b380a8b89af9ff4746` (integración de PR #32).
Trabajo de PR #33, rama `feat/complete-ui-localization`. **Draft; no fusionar sin
aceptación del usuario.** **Bloqueante físico pendiente:** el nuevo journal de
`f8cbaef` ya muestra la causa terminal: `IllegalStateException` en
`com.android.okhttp.okio.AsyncTimeout.enter`, al cerrar el body HTTP desde
`RawSourceChannel.closeSource`. Mover la cancelación a IO fue insuficiente. Se
sustituye el adaptador HTTP Android por el de OkHttp mantenido por Ktor, con una
regresión de lectura/cierre retenidos roja antes y verde después. La aceptación en
el C55 sigue pendiente; los lectores y recursos no cambian. Véase el seguimiento
al final; se distinguen pruebas host, compilación y validación física.

## Arquitectura y selección de idioma

Se amplía el sistema de PR #31; no se añade un segundo sistema ni una dependencia.
Los textos estáticos revisados están en Compose Multiplatform resources:

- `app/src/commonMain/composeResources/values/`: inglés y respaldo.
- `values-es/`: español.
- `values-ca/`: catalán.
- `strings.xml`: **410 cadenas por idioma**, frente a 166 en la base. Se añaden
  245 claves y se retira `language_scope`, cuyo aviso de localización parcial ya
  no describe estas pantallas.
- `plurals.xml`: **2 recursos plurales** por idioma, para selección de publicaciones
  y fuentes seleccionadas. Cada uno contiene formas `one` y `other`.

`UiText` conserva acceso tipado y respaldo inglés. `InterfaceLanguage` captura el
entorno público de recursos y carga una tabla completa en `Dispatchers.IO`. Incluye
las formas plurales y una clave interna `interface_locale_tag` que identifica el
idioma de la tabla sin acceder a propiedades internas de `ResourceEnvironment`.
La publicación de la tabla es atómica: no se mezclan idiomas al llegar resultados
asíncronos; cancelación y rechazo de cargas obsoletas siguen funcionando. Un fallo
real conserva el último idioma completo, muestra el error y permite reintentar;
no se usa inglés silenciosamente para aprobar tests.

La preferencia sigue en `LocalProfile.interfaceLanguage`, dentro del almacenamiento
existente de preferencias, con los códigos `en`, `es`, `ca` o `null` para Sistema.
No cambia el formato persistente, ninguna base de datos ni los registros de
biblioteca/historial/progreso. Sin elección explícita se usa el idioma del sistema
capturado al iniciar el proceso; un idioma no admitido utiliza inglés. La elección
manual se aplica sin reiniciar ni cambiar claves de sesiones, lectores o pantallas.
El proveedor JVM conserva la política existente de `Locale.setDefault` para las
APIs de recursos y fechas. No se introduce una política de locale nativa adicional.

El idioma de lectura sigue siendo **otra preferencia**. Una elección explícita se
conserva al cambiar la interfaz; `null` significa la opción existente «Seguir el
idioma de la interfaz». Un destino de traducción explícito en el lector también
se conserva. Cambiar el idioma de interfaz no activa el modo Traducido ni descarga
modelos. La preferencia voluntaria de seguir la interfaz mantiene su significado.

## Inventario de pantallas y cadenas revisadas

Los archivos siguientes permiten verificar el inventario en el diff y los
recursos. Incluye mensajes dinámicos, no solo llamadas `Text("literal")`.

| Área | Implementaciones revisadas | Cobertura completada |
| --- | --- | --- |
| Marco/navegación | `App.kt`, `ui/ApplicationNavigation.kt` | Cinco destinos, Back, importación, apertura, errores y acciones de biblioteca en lectores |
| Inicio | `ui/HomeScreen.kt`, `discovery/DiscoveryUi.kt` | Perfil inicial, estados locales, recomendaciones, géneros propios, descubrimiento opcional, reintentos y avisos |
| Biblioteca | `ui/CollectionScreen.kt`, `ui/PublicationCover.kt` | Vacío/carga/error, selección y plurales, detalles, acciones de teclado/contextuales, confirmaciones, progreso y accesibilidad |
| Historial | mismos componentes y `ui/LastOpened.kt` JVM | Hoy/Ayer, fecha real localizada, quitar/limpiar, confirmaciones y continuidad de biblioteca/progreso |
| Búsqueda unificada y ruta de búsqueda existente | `discovery/DiscoveryUi.kt`, `App.kt` | Campo, borrar, filtros, idiomas, categorías, ayuda, resultados, paginación y errores independientes de fuentes |
| Detalles | `ui/PublicationPresentation.kt`, `ui/PublicationDescriptions.kt` | Título del panel, etiquetas de metadatos, sinopsis/expandir/contraer, Start/Continue, biblioteca, derechos y cierre |
| Ajustes/perfil | `ui/ApplicationSettingsScreen.kt`, `ProfileEditor.kt`, `LanguageTranslationPanel.kt` | Apariencia, perfil, país, idioma, modelos y avisos; se retira el aviso obsoleto de alcance parcial |
| EPUB | `reader/epub/EpubReader*.kt`, `EpubSelectionToolbar.kt`, `EpubTranslationControls.kt` | Controles, índice, tipografía, selección, original/traducido, errores, descarga/modelos y accesibilidad previamente localizados; se completan acciones/avisos del marco y alternativas de imagen generadas por la app |
| CBZ | `reader/page/ComicControls.kt`, `PageReader.kt` | Hojas, modo/dirección, ajuste, página simple/doble, zoom, slider/vista previa, carga/error y acciones accesibles |
| PDF | `reader/pdf/PdfReader.kt` | Navegación, errores, contador, descripción de página y persistencia |
| TXT | `reader/TextReader.kt` | Progreso aproximado, carga, Back y errores de lectura/guardado |
| Importación | `App.kt`, `imports/LocalFilePicker.kt`, selector Desktop, tooltip Desktop | Progreso, cancelación, límites/validación/almacenamiento, recuperación y título del selector nativo |
| Adquisición/fuentes | `DiscoveryErrors.kt`, contratos de apertura/importación/colecciones | Mensajes propios completos en presentación; atribución y declaraciones originales de derechos conservadas |
| Traducción/modelos | `LanguageTranslationPanel.kt`, `EpubTranslationControls.kt`, recursos de PR #31 | Modelos reales inglés↔español e inglés↔catalán, estados, errores, fijados/temporales, consentimiento y almacenamiento; sin modelos nuevos |

Los estados de controlador y los enums internos que ya emitían etiquetas/mensajes
ingleses mantienen su contrato. Solo sus puntos de presentación utilizan
`LocalizedStrings.status`: un índice de mensajes exactos y plantillas de frases
completas con parámetros. No sustituye palabras aisladas ni se aplica a datos de
libros. Las expresiones de plantillas se crean bajo demanda en rutas de estado,
no para cada portada ni cada frame. Los contratos conocidos tienen tests que
rechazan una clave ausente. Una excepción inesperada muestra un error genérico
localizado, sin rutas privadas ni stack trace.

Para inspeccionar las claves completas, consultar los tres `strings.xml` y el enum
`UiText`; para impedir nuevas omisiones evidentes:

```sh
python3 tools/check_localization.py
```

La herramienta verifica claves, duplicados, parámetros, plurales y literales de
presentación en Kotlin de ambos targets. No es un parser Kotlin ni demuestra la
procedencia de cada cadena dinámica: se complementa con los contratos y pruebas
UI descritos abajo. Los glifos decorativos (`Aa`, `A↔文`, `?`, etc.), la marca
INFINILECT y las URLs de atribución son excepciones explícitas; no son texto inglés
pendiente. «Original», «Vertical», «Webtoon», «Categories»/«Format» en catalán y
«Romance» en español son coincidencias lingüísticas revisadas, no fallbacks.

## Parámetros, plurales y formatos

`substituteArguments` procesa los parámetros posicionales de la plantilla **una
sola vez**. Un nombre/consulta que contenga `%2$s` no vuelve a interpretarse como
formato. Las traducciones pueden reordenar parámetros; los tests verifican índices
y multiplicidad, no fuerzan el orden gramatical inglés.

Los plurales se cargan con `getPluralString` del mismo entorno de recursos. Para
los tres idiomas admitidos se elige la forma singular al contar uno y la plural
para cero y cantidades mayores. Añadir idiomas con otras reglas exige ampliar la
selección de cantidades; no se presume una regla universal.

Los contadores enteros utilizan dígitos decimales sin agrupación, comunes a estos
tres idiomas y adecuados para controles compactos. Los decimales usan el locale
de la tabla (coma en es/ca, punto en en), sin crear un formateador por cada contador
entero. Los tamaños de modelos siguen expresados en MiB con el redondeo y los
límites previos; no cambian tamaños de descarga ni presupuestos. El progreso sigue
siendo progreso semántico o aproximado según el lector, nunca páginas EPUB ficticias.

Historial agrupa por el día real y la zona horaria existente. La clave de su
`remember` incluye ahora el idioma aplicado: las etiquetas de fechas ya no quedan
memorizadas en el idioma anterior. «Hoy»/«Ayer» se resuelven como etiquetas propias;
las otras fechas se formatean con `DateFormat` y no pasan por un traductor de texto.

## EPUB: contenido y posiciones

Títulos, autores, sinopsis, derechos, idiomas declarados y categorías recibidas de
fuentes conservan sus datos originales. La interfaz puede estar en catalán y
mostrar un título/sinopsis en inglés: es contenido de la publicación, no mezcla
de etiquetas pendientes. Tampoco se reescribe el nombre del perfil, la consulta ni
el texto original del libro.

El único texto dentro de la presentación EPUB que se adapta en esta PR es el
**envoltorio generado por la aplicación** para una imagen no representable, como
`[Image: …]`. `EpubImageFallback.kt` reconoce los runs no traducibles generados por
el parser y localiza únicamente ese envoltorio; el alt del editor se conserva.
En párrafos mixtos reutiliza `EpubTranslatedBlock` para mapear offsets de vuelta al
original. No cambia XML, bloques fuente, parser, índices, caché, modelos ni
localizadores persistentes. Para libros sin estos marcadores devuelve la proyección
anterior sin duplicar bloques; el cálculo se recuerda por ventana/proyección/tabla.
La ventana sigue acotada por la política EPUB existente. Hay pruebas de Unicode,
formato y offsets del texto posterior a una alternativa de imagen.

Cambiar la interfaz no crea otra sesión EPUB ni hace parsing otra vez. Los tests
comprueban texto Original, destino de traducción explícito, ticket/posición y
número de llamadas al parser, así como alternancia Original/Traducido con proveedor
**determinista de prueba**. No se presenta ese proveedor como validación de
inferencia nativa. Los mecanismos existentes de progreso profundo, tipografía,
ventanas y EPUB2/EPUB3 permanecen en las suites de regresión.

## Glosario

| Inglés | Español | Catalán |
| --- | --- | --- |
| Home | Inicio | Inici |
| Library | Biblioteca | Biblioteca |
| History (reading) | Historial | Historial |
| History (genre) | Historia | Història |
| Search | Búsqueda | Cerca |
| Settings | Ajustes | Configuració |
| Read / Start reading | Leer / Empezar a leer | Llegir / Comença a llegir |
| Continue reading | Continuar leyendo | Continua llegint |
| Translate | Traducir | Traduir |
| Download | Descargar | Baixa |
| Remove from Library | Quitar de la biblioteca | Treu de la biblioteca |
| Delete model | Eliminar modelo | Elimina el model |
| Copy | Copiar | Copia |
| Synopsis | Sinopsis | Sinopsi |
| Fit width | Ajustar al ancho | Ajusta a l'amplada |

Se respeta la terminología de los recursos previos de modelos; la eliminación de
un modelo y quitar una publicación de una lista son acciones diferentes.

La prueba de PDF con ventana de **320 × 520** y escala de fuente **1,8**
identificó un defecto de presentación previo: la fila única dejaba el botón
`Next` inglés con límites de tamaño cero. La barra ahora usa `FlowRow` para
repartir controles entre líneas. Se conservan callbacks, página y ticket de
presentación; la prueba comprueba espacio visible para cada acción en los tres
idiomas. No se modifica el motor PDF.

## Plataformas, limitaciones y cadenas conservadas

- Android usa los recursos Compose empaquetados como assets, conservando la
  habilitación de recursos de PR #31. `verifyDebugLanguageResources` comprueba ahora
  **cadenas y plurales**, comparando los seis CVR del APK byte a byte con los
  generados. No se cambia el ID, firma, manifiesto, permisos ni release.
- Desktop usa los recursos de su classpath; se comprueba la resolución real de
  cada clave contra el XML de los tres idiomas. El título del selector AWT y su
  tooltip son localizados por la app.
- Los botones/ayuda propios del selector de archivos de Android/AWT, interfaces del
  sistema, teclado y servicios de accesibilidad externos pertenecen al SO. Pueden
  conservar su idioma del sistema. No se reemplazan ni se manipulan esas ventanas.
- La opción Sistema sigue la política existente: captura al iniciar el proceso.
  No se incorpora detección continua de cambios de idioma del SO desde fuera de
  la app. Los cambios manuales en Ajustes sí actualizan la UI sin relanzarla.
- Los nombres/códigos oficiales de formatos y proveedores y el contenido externo
  no se traducen. Metadatos de importaciones antiguas, incluido un título genérico
  guardado por una versión anterior, se conservan: no se puede distinguir de forma
  fiable un título real equivalente sin añadir procedencia al modelo persistente.
- No hay cadenas propias conocidas pendientes en las rutas revisadas. La guardia
  estática no constituye una prueba exhaustiva de todas las interacciones físicas;
  se requiere revisar pronunciación, foco, selección táctil y recortes en el C55.
- No hay mediciones nuevas de FPS, memoria nativa o respuesta física. Las keys,
  visibilidad/leases de portadas, snapshots y límites de PR #32 se conservan; no se
  añade trabajo de modelos/red al inicio ni al cambiar de idioma.

## Pruebas y compilaciones

Primera entrega automatizada, anterior al seguimiento físico: **19 de octubre de 2026**, host Linux x86_64, JDK 21,
Gradle 9.7.1, SDK Android 37 / build-tools 36. Las cuatro suites se ejecutaron
explícitamente, sin reutilizar su resultado de test como `UP-TO-DATE`.

| Comprobación | Resultado final |
| --- | --- |
| `:app:desktopTest` | 1.560 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `:app:testAndroidHostTest` | 1.303 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `:core:jvmTest` | 59 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `:core:testAndroidHostTest` | 59 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Total de las cuatro suites | **2.981 pruebas aprobadas** |
| `:androidApp:assembleDebug` | Correcto; APK debug compilada |
| `:androidApp:verifyDebugLanguageResources` | Correcto; cadenas y plurales de en/es/ca presentes e idénticos a los CVR generados |
| `:desktopApp:compileKotlin` | Correcto |
| `python3 tools/check_localization.py` | Correcto; 402 cadenas + 2 plurales por idioma, 168 archivos Kotlin examinados |
| `git diff --check` | Correcto |
| Revisión de dependencias, artefactos y secretos | Sin dependencias nuevas, binarios ni secretos añadidos; firma/ID/manifiesto sin cambios |

La matriz final terminó con `BUILD SUCCESSFUL` en **10 min 45 s**. Ese tiempo
corresponde a tests/compilación en este host, **no** a una medida de rendimiento
Android. La pasada enfocada final previa también aprobó sus 22 casos.
Los tests incluyen regresiones existentes de selección EPUB, traducción,
seguridad, CBZ/PDF/TXT, importación, biblioteca, historial, búsqueda y preferencias.

La primera pasada completa encontró una expectativa antigua en `SearchLayoutTest`.
Ese caso conserva su error sintético no registrado y ahora exige el mensaje seguro
localizado correspondiente, en lugar de exigir texto crudo arbitrario del controlador. Mantiene las comprobaciones exactas de contenido,
viewport y accesibilidad mediante scroll; no se omiten ni relajan aserciones.

Los tests nuevos cubren recursos/argumentos/plurales/contratos de error,
parámetros Unicode con sintaxis de formato, independencia de preferencias,
alternativas de imagen y anclas, cinco destinos, accesibilidad, diálogos abiertos,
selección de idioma desde Ajustes y conservación de sesión/contenido EPUB.
Las pruebas de UI usan Compose sobre un host Linux sin pantalla, tamaños Android
y Desktop y fuente ampliada. **No son pruebas físicas en Android.** La comprobación
del APK sí examina el artefacto empaquetado, no solo el classpath JVM.

Comandos (JDK 21 y SDK configurados):

```sh
python3 tools/check_localization.py
./gradlew :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources \
  :desktopApp:compileKotlin
git diff --check
```

En entornos headless/restringidos puede ser necesario dirigir la extracción de
Skiko a un directorio escribible y usar renderizado software en el proceso de
test. Esa configuración se mantuvo fuera de Git; no cambia la aplicación. Los
logs y artefactos generados de esta ejecución tampoco se versionan.

## Checklist físico: realme C55 y Desktop

1. Actualizar conservando datos con la identidad de firma existente. No desinstalar
   ni borrar datos. Elegir Español, English y Català; cerrar/reabrir y comprobar
   preferencia, perfil y país. Sistema: comprobar un idioma admitido y el respaldo.
2. Recorrer Inicio, Biblioteca, Historial, Búsqueda y Ajustes. Desplazar Inicio con
   portadas calientes; cambiar pestañas y confirmar scroll, consultas y resultados.
3. Abrir filtros/ayuda, errores offline, reintentos, paginación y atribución/derechos;
   no se traducen títulos, autores ni metadatos externos.
4. Seleccionar una y varias publicaciones: contador, seleccionar todo, quitar y
   cancelar confirmación. Verificar archivos, biblioteca, historial y posiciones.
5. Historial: Hoy/Ayer/fechas anteriores, detalles y reanudar, quitar sin perder la
   posición. Detalles: título largo, sin autor, sinopsis expandida y avisos legales.
6. Con fuente grande y pantalla estrecha, comprobar ajustes/perfil/idiomas/modelos,
   botones de cierre y scroll. Cambiar idioma con un panel abierto y mediante
   teclado en Desktop; revisar etiquetas TalkBack y foco.
7. EPUB2/EPUB3 y capítulo largo: índice, enlaces, selección Unicode, Copiar/Traducir,
   fuente/tema, posición al reabrir. Cambiar idioma de UI no traduce el contenido.
   Con destino de lectura explícito, confirmar que se conserva; probar por separado
   la opción voluntaria de seguir la interfaz y Original/Traducido offline.
8. CBZ: LTR/RTL, dobles, vertical/Webtoon, slider, zoom, hojas y errores; PDF/TXT:
   controles, carga, errores y posición. Revisar ambos temas y barras del sistema.
9. Importar un archivo compatible y uno rechazado; comprobar cancelación y avisos
   sin cambios de seguridad. El selector externo puede mostrar el idioma del SO.

## Añadir otro idioma

1. Crear `values-<idioma>/strings.xml` y `plurals.xml` con las mismas claves y
   parámetros; traducir frases completas y revisar el glosario.
2. Añadir el código al selector, validación del perfil y mecanismo persistente
   correspondiente. Revisar migración sin sobrescribir preferencias existentes.
3. Ampliar las reglas de cantidad si el idioma no comparte `one/other` de en/es/ca;
   no copiar ciegamente el selector binario actual.
4. Añadir `interface_locale_tag` correcto, traducción del nombre de idioma y fallback
   explícito. Esto no implica añadir modelos o pares de traducción de libros.
5. Ampliar tests, guardia y verificación del artefacto; probar navegación, diálogos,
   accesibilidad, fuente grande y texto original independiente en ambos targets.

## Correcciones posteriores a pruebas físicas — realme C55

Seguimiento de la APK del commit `290122a3d7c210c890a9d36edabe7735e85f676f`.
**La PR continúa en Draft y bloqueada para fusión por el cierre de arranque sin
causa confirmada.** No hay acceso al C55 ni runtime Android ejecutable en este
entorno; una prueba Compose en Linux no demuestra que desaparezca el cierre.

### Arranque y navegación: evidencia y límites

Se revisaron `MainActivity.onCreate/onStart/onStop`, la creación de
`ApplicationSources`, `App`, `InterfaceLanguage`, `ApplicationAppearancePreferences`,
`ApplicationSession.navigate`, `CollectionsController`, `HomeDiscovery` y
`PublicationCovers`. La secuencia real es: propietario retenido de fuentes → sesión
→ cargas independientes de preferencias/colecciones → marco navegable con tabla
inglesa inicial → tabla localizada completa. El propietario de la tabla está fuera
de `SaveableStateProvider(destination.name)`; cambiar una pestaña no lo destruye.
Los repositorios y motores tampoco pertenecen a cada pestaña. Inicio/Búsqueda
cancelan sus trabajos al abandonarse, con generaciones y descarte de respuestas
obsoletas. La tabla se obtiene en IO y se publica en el contexto Compose; una carga
fallida mantiene la última válida.

Las pruebas nuevas `StartupNavigationTest` cubren:

- Los seis recorridos solicitados en en/es/ca/Sistema, antes de liberar preferencias
  y colecciones: **48 arranques controlados**, incluyendo fallos de almacenamiento
  y preferencias. Usan el `App` real y propietarios/repositorios de prueba.
- El marco y las pantallas reales con `InterfaceLanguage` y una carga de recursos
  gobernada por `CompletableDeferred`: ocho combinaciones de preferencia y fallo,
  con los seis recorridos antes y después de resolver recursos. Las acciones de
  pestaña deben estar habilitadas y conservar el destino elegido. Sistema usa el
  locale capturado del host; no simula un cambio de configuración del SO Android.
- Resultados de Inicio que terminan después de cancelar/abandonar la pantalla,
  reintento tras error de recursos, persistencia de identidades y ausencia de
  mutaciones de biblioteca/historial. Un transporte no cancelable termina su
  callback, pero sus resultados obsoletos no se publican.
- Retirada del marco antes de su primer layout, sin solicitar un foco no adjunto.

La primera pasada de estos tres métodos pasó **antes de cambiar código de
producción**. No se ha identificado una excepción de arranque ni una causa raíz;
no existe una prueba roja de ese crash ni se presenta el ajuste visual como su
corrección. No se añadieron esperas, pestañas deshabilitadas, reinicios ni `catch`
genéricos. Permanecen pendientes de evidencia Android, en este orden:

| Hipótesis sin confirmar | Evidencia necesaria |
| --- | --- |
| Excepción Android de UI/lifecycle durante el primer cambio de destino | Clase y frames de `InfinilectCrash`/`AndroidRuntime`, destino y momento aproximado |
| Presión Java/nativa/GPU durante arranque y portadas | Motivo de salida, `OutOfMemoryError` o señal nativa, PSS/GC y traza; PSS aislado no diagnostica OOM |
| Bloqueo/ANR de inicialización | Motivo ANR y Perfetto/stack del hilo principal; esperar cinco segundos no prueba esta causa |
| Diferencia Android de recursos, foco o restauración | Excepción y frames reales; los fallos controlados de recursos en host conservan navegación y tabla válida |

El diagnóstico de PR #32 permanece intacto: opt-in debug, métricas y motivo de
salida, sin telemetría remota ni logs de libros, consultas o perfiles. Cuando se
disponga de PC, abrir **primero** un terminal y dejarlo capturando:

```sh
adb devices
adb logcat -v monotonic -s InfinilectPerf:I InfinilectCrash:E InfinilectExit:I INFINILECT.Locale:W '*:S' > infinilect-pr33-startup.txt
```

En otro terminal:

```sh
adb shell am force-stop org.infinilect.app
adb shell am start -W -n org.infinilect.app/.MainActivity --ez infinilect.performance true
```

Cambiar de pestaña en cuanto aparezca la interfaz. Si se cierra, **no hacer otro
force-stop antes de recoger el motivo**. Reabrir con el mismo flag y recoger:

```sh
adb shell am start -W -n org.infinilect.app/.MainActivity --ez infinilect.performance true
adb shell dumpsys activity exit-info org.infinilect.app > infinilect-pr33-exit.txt
adb shell dumpsys meminfo org.infinilect.app > infinilect-pr33-memory.txt
```

La primera orden force-stop produce su propio motivo de salida; distinguirlo del
cierre posterior. Motivos habituales: 3 memoria baja, 4 crash Java, 5 nativo,
6 ANR, 10 solicitado por el usuario. OEMs pueden omitir información. Si falta el
stack, consultar localmente `adb logcat -b crash -d -v threadtime`; revisar y
recortar al proceso de INFINILECT antes de compartir, evitando rutas privadas u
otros datos. Perfetto y escenarios calientes/cargando: [ANDROID_PERFORMANCE.md](ANDROID_PERFORMANCE.md).
Sin PC, anotar build, versión Android, idioma, pestaña, tiempo desde apertura y si
había conexión/portadas pendientes. Ese registro ayuda, pero no sustituye la traza.

### Modelos: diagnóstico por dirección

La comparación del código Android de PR #31 con `290122a` no muestra cambios en el
catálogo. La infraestructura admite códigos en/es/ca, pero eso no implica que
existan las seis combinaciones. El registro primario fijado es
[Foxlet `registry.json` en `0eff2a15032362115848a496ce1df8ed9fc68650`](https://github.com/yinvoke/foxlet-translate/blob/0eff2a15032362115848a496ce1df8ed9fc68650/registry.json),
SHA-256 `1a3b02c2a91be818350e2b3e1968cc5413feeb1ea3bdf4726f75d95354e6aa89`.
Se verificó también el hash fijado del AAR 0.5.0 y su `models.tsv` real: mismos
archivos, hashes, versiones, tamaños y URLs para las cuatro direcciones admitidas;
ninguna entrada es→ca o ca→es. El SDK contiene 106 direcciones; INFINILECT solo
expone las cuatro verificadas, sin prometer las restantes.

| Dirección | Versión | Descarga (bytes) | Estado real |
| --- | --- | --- | --- |
| en→es | 2.1 | 36.576.277 | Admitida, sin cambios |
| es→en | 2.0 | 37.014.089 | Admitida, sin cambios |
| en→ca | 2.0 | 36.716.532 | Admitida, sin cambios |
| ca→en | 2.0 | 36.731.612 | Admitida, sin cambios |
| es→ca | — | — | Sin artefacto directo en proveedor fijado |
| ca→es | — | — | Sin artefacto directo en proveedor fijado |

No son pares ocultos por localización ni modelos desaparecidos. La prueba mediante
idioma puente de PR #31 quedó expresamente fuera de la integración; requiere dos
etapas, otras garantías de caché/calidad y medición de memoria/latencia. Se difiere
esa funcionalidad sin fabricar manifiestos ni habilitarla silenciosamente.

`VerifiedTranslationModels.kt` centraliza **solo los mismos metadatos** para poder
probarlos desde host. El adaptador Android conserva toda la validación de
`descriptor`, instalación, hashes, almacenamiento, mutex y liberación nativa.
No cambian identidad del proveedor, revisiones, rutas privadas ni registros de
modelos instalados; no hay migración ni nuevas descargas. Desktop continúa sin
motor real. La licencia del motor/modelos y los avisos existentes no cambian;
el extracto factual de registro para tests conserva origen y atribución MPL-2.0.

El panel en/es/ca explica ahora el límite de pares directos. Los tests de catálogo
comparan metadatos con el extracto primario independiente, ausencia de duplicados
y hosts HTTPS. Las pruebas de UI usan esos cuatro descriptores con un proveedor
administrativo **determinista**, no inferencia ni descargas reales: visibilidad en
los tres idiomas, preferencia de lectura independiente, progreso, cancelación,
fallo/reintento, eliminación y restauración de instalaciones/retención.
Una selección es↔ca sin modelo conserva Original y devuelve el error localizado
sin solicitar una descarga inexistente. Las pruebas previas de archivos de
retención, instalación atómica y presupuestos siguen en las suites completas.

### «Configuració»: causa y corrección

`ApplicationNavigation.NavigationItem` asignaba cinco pesos iguales y 4 dp de
padding por lado, con `maxLines=2`. Una palabra más ancha que su celda podía saltar
por caracteres y dejar una letra aislada. La nueva prueba falló antes del cambio
con `en/320 dp/1,5: Library`, dos líneas en vez de una; el mismo contrato de layout
afectaba a la etiqueta catalana comunicada por el usuario.

La barra mide las cinco etiquetas con el estilo y escala reales, conserva al menos
48 dp por destino en los tamaños solicitados y distribuye el ancho adicional
según la necesidad del texto. El cálculo se recuerda por idioma/geometría/estilo;
no depende del scroll, las portadas ni el progreso de traducción. Son cinco textos
y una caché de medición de cinco entradas, sin dependencia nueva.

Las etiquetas compactas usan una sola línea. Si la fuente aumentada supera el
ancho disponible, se elide con puntos suspensivos, conservando nombre completo,
rol, estado seleccionado y acción accesible. No se reduce el tamaño SP, no se
inventa una abreviatura ni se cambia el destino o sus atajos. La barra puede crecer
en altura según la fuente; iconos y objetivos táctiles conservan espacio. La rail
Desktop mantiene su política y umbral existentes.

`NavigationLabelLayoutTest` cubre **54 combinaciones**: tres idiomas × 320/360/600 dp
× fuente 100/150/180 % × claro/oscuro. Comprueba las **270 etiquetas**, acciones,
semántica y objetivos de 48 dp. La ejecución enfocada midió cero etiquetas en
varias líneas y cero etiquetas elididas a escala normal. Se generaron ocho PNG
catalanes comparables de 320/360 dp, 100/180 %, ambos temas, fuera del repositorio.
Son renders headless Linux; no capturas físicas Android ni una medida de FPS.

### Verificación del seguimiento

Ejecución del seguimiento anterior al journal físico de `55d694d`: **20 de octubre de 2026**, Linux x86_64,
JDK 21, Gradle 9.7.1 y SDK 37. Las cuatro suites se forzaron a ejecutar de nuevo;
no se omite ni debilita ningún test existente.

| Comprobación | Resultado |
| --- | --- |
| Desktop completo | **1.568/1.568**, 0 fallos/errores/omitidas |
| Android-host completo | **1.305/1.305**, 0 fallos/errores/omitidas |
| Core JVM | **59/59**, 0 fallos/errores/omitidas |
| Core Android-host | **59/59**, 0 fallos/errores/omitidas |
| Total | **2.991 pruebas aprobadas** |
| APK Android debug | `assembleDebug` correcto |
| Desktop | `compileKotlin` correcto |
| Recursos APK | `verifyDebugLanguageResources` correcto, cadenas/plurales en/es/ca |
| Guardia de localización | 403 cadenas + 2 plurales por idioma, 169 archivos Kotlin revisados |
| Catálogo | Registro primario y TSV real del AAR verificados; cuatro descriptores idénticos; es↔ca ausente |
| Higiene | `git diff --check` correcto; sin secretos, binarios, dependencias, permisos, firma ni ID añadidos/modificados |

`BUILD SUCCESSFUL` en **10 min 36 s**: tiempo de tests/compilación de este host,
no una medida de FPS Android. La pasada enfocada final aprobó 19 tests. Las
regresiones completas incluyen importación/ZIP/XML/adquisición, recursos/selección,
traducción/caché/modelos/persistencia y los cuatro lectores; no se han cambiado sus
motores ni garantías. No se ejecutó inferencia nueva en Android ni una prueba
física del cierre. Los PNG/CSV y logs de diagnóstico se generaron fuera de Git.
La APK compilada no se publicó como release ni se cambió configuración de firma.

### Checklist de nueva validación física

1. Actualizar conservando datos/firma; no desinstalar ni borrar datos. En cada idioma
   y Sistema: abrir desde proceso detenido y tocar inmediatamente Biblioteca,
   Historial, Búsqueda o Ajustes. Repetir Inicio→Biblioteca→Inicio e
   Inicio→Ajustes→Búsqueda; comparar arranque frío y caliente. Registrar cualquier
   cierre y recoger el motivo con los comandos anteriores.
2. Repetir con red lenta/sin red y con Inicio cargando portadas; después con portadas
   calientes, scroll vertical/horizontal rápido y cambios de pestaña. Mantener
   comparables fuente, dispositivo, temperatura y datos. No se afirma fluidez nueva.
3. Revisar las cinco etiquetas en claro/oscuro, normal/150/180 %. «Configuració»
   completa cuando cabe, una sola línea y nombre accesible completo si se elide.
   Confirmar pulsación, TalkBack y regreso desde lectores.
4. Ajustes → Idioma y traducción: los cuatro pares reales deben ser iguales en los
   tres idiomas. Revisar aviso de es↔ca no admitido; no hay botón para ese modelo.
   Comprobar instalaciones existentes, descarga explícita de un par admitido,
   progreso/cancelar/reintentar y eliminación sin afectar otros modelos ni libros.
5. EPUB2/3: selección, Copiar/Traducir, Original/Traducido, idioma de destino y
   posición al reabrir. Cambiar idioma UI no modifica el catálogo ni el texto.
   CBZ/PDF/TXT: abrir/reanudar, controles, gestos y posiciones; Biblioteca/Historial/
   Búsqueda y preferencias deben conservarse. Comprobar datos tras reiniciar.

**Las pruebas host no levantan el bloqueo del crash: se necesita nueva validación
física y evidencia para confirmar su causa y resolución. No fusionar.**

### Seguimiento: salida de Inicio durante descubrimiento inferior

El nuevo informe físico posterior a `8614b9f` acota el cierre a abandonar Inicio
mientras se muestra `discovery-loading`. Ya no se observa el fallo inmediato de
arranque en la última prueba del usuario; no se extrapola esa observación a todos
los escenarios. La línea corresponde al job `HomeDiscovery.refresh`, no a
paginación por scroll ni al progreso de portadas.

La cadena de propiedad, pruebas con App real y barreras, diagnóstico de desarrollo
copiable **sin PC**, hipótesis y checklist C55 se documentan en
[ANDROID_PERFORMANCE.md](ANDROID_PERFORMANCE.md#pr-33--investigación-del-cierre-al-abandonar-inicio-durante-la-carga-inferior).
El diagnóstico es optativo, local y borrable, con siete cadenas nuevas revisadas
en español, inglés y catalán (410 cadenas y dos plurales por idioma). No contiene
texto de publicaciones ni datos del usuario y no se muestra en release/Desktop.
Se mantienen la navegación catalana, el catálogo real de modelos y la separación
idioma de interfaz/contenido. No se declara resuelto el cierre por pasar pruebas host.

### Seguimiento del journal físico: Gutenberg

El informe aportado desde el C55 identifica una excepción de completion handlers
al cancelar Gutenberg durante `HOME_PAUSED`. El último wrapper físico todavía no
está visible en el log de tres causas. Dos regresiones con el motor Android Ktor
real y una conexión sintética reprodujeron cancelación/cierre en UI; la corrección
lleva esas operaciones a IO y conserva la navegación. El formateador captura más
causas **sin superar las 51 líneas anteriores** ni registrar datos privados.

Evidencia, alcance, resultados y pasos para limpiar el informe antiguo antes de
repetir la prueba: [ANDROID_PERFORMANCE.md](ANDROID_PERFORMANCE.md#pr-33--traza-del-c55-cancelación-de-gutenberg).
No se cambian los 410 textos, idiomas, recursos empaquetados, modelos, lectores,
progreso, firma ni ID. No se declara resuelto físicamente hasta la nueva prueba.

La verificación posterior a esta corrección ejecuta **3.024 tests**, sin fallos,
errores ni omisiones: Desktop 1.584, Android-host 1.322 y Core 59 + 59. Android
debug, Desktop y los seis recursos de idioma empaquetados compilan/verifican
correctamente. Véase el detalle de ejecución y pruebas rojas/verdes en el apartado
anterior de rendimiento; la aceptación física sigue pendiente.

## Segundo journal físico: cierre del body HTTP Android

La traza recibida de `f8cbaef` conserva por primera vez la causa profunda. No es una
confirmación de que bastase mover `cancelAndJoin` a IO. La corrección posterior
sustituye únicamente el adaptador HTTP específico de Android; Desktop, lectores,
recursos de idioma, gestión de modelos y progreso permanecen intactos. Las
regresiones incluyen lectura retenida/cierre concurrente, 12 recorridos de cuatro
destinos y retorno, sockets locales detenidos, redirects y liberación de recursos.

El resultado completo, las fuentes y licencias de la dependencia, las diferencias
entre fixture y teléfono y la validación pendiente están en
[ANDROID_PERFORMANCE.md](ANDROID_PERFORMANCE.md#pr-33--causa-profunda-readclose-del-stream-android).
No se declara listo para fusionar hasta repetir la prueba física. PR #33 sigue Draft.

Verificación de este segundo seguimiento: **3.029 pruebas** (Desktop 1.584,
Android-host 1.327, Core 59 + 59), cero fallos, errores u omisiones; Android y
Desktop compilan. Los seis CVR y la licencia del transporte están empaquetados;
el engine legado permanece solo en tests. Aceptación física pendiente.

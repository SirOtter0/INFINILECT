# Localización de INFINILECT: español, inglés y catalán

Base: `6a95580a60f1119770c035b380a8b89af9ff4746` (integración de PR #32).
Trabajo de PR #33, rama `feat/complete-ui-localization`. **Draft; no fusionar sin
aceptación del usuario.** Este documento distingue comprobaciones automáticas,
compilación y validación física pendiente.

## Arquitectura y selección de idioma

Se amplía el sistema de PR #31; no se añade un segundo sistema ni una dependencia.
Los textos estáticos revisados están en Compose Multiplatform resources:

- `app/src/commonMain/composeResources/values/`: inglés y respaldo.
- `values-es/`: español.
- `values-ca/`: catalán.
- `strings.xml`: **402 cadenas por idioma**, frente a 166 en la base. Se añaden
  237 claves y se retira `language_scope`, cuyo aviso de localización parcial ya
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

Ejecución final: **19 de octubre de 2026**, host Linux x86_64, JDK 21,
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

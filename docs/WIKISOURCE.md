# Wikisource: availability and rights follow-up — PR #39

## Problemas conocidos

### Android · Verificación automática de EPUB en Buscar

- **Plataforma.** Android, reproducido en un realme C55 con la versión de la PR #39 basada en `0f2b17d`.
- **Comportamiento esperado.** Las obras confirmadas aparecen automáticamente en **Legibles** tras una sola búsqueda.
- **Comportamiento observado.** En ocasiones, la primera búsqueda de «A Venus» deja la obra en **Pendientes de comprobar**. Una segunda búsqueda con la misma consulta permite que aparezca en **Legibles**.
- **Solución temporal.** Repetir la búsqueda o abrir la ficha y pulsar **Comprobar EPUB**.
- **Impacto observado.** Afecta a la experiencia de descubrimiento. Sigue sin demostrarse un fallo asociado de integridad, seguridad o lectura offline.
- **Causa y estado.** La causa en Android físico sigue sin confirmar. La resolución se aplaza. [Incidencia #40](https://github.com/SirOtter0/INFINILECT/issues/40)

El defecto de tiempo de espera descrito a continuación se reprodujo y corrigió
en las pruebas automatizadas. La prueba posterior en el realme C55 confirmó
que el síntoma físico persiste con esa corrección.

## Primera búsqueda con metadatos lentos

En el realme C55, una búsqueda de «A Venus» dejó la obra en **Pendientes
de comprobar** y una segunda pulsación mostró el libro. La prueba de
regresión reprodujo el primer resultado en el controlador anterior:
el catálogo entrega el candidato autorizado a los 15 segundos, la
comprobación EPUB necesita 4 segundos y el reloj de 18 segundos cancela
la operación al cumplirse el tercer segundo de la comprobación. La
primera búsqueda se queda sin promoción. La regresión aisló ese defecto del controlador.
El síntoma observado en el realme C55 sigue abierto y se registra por separado
en la [incidencia #40](https://github.com/SirOtter0/INFINILECT/issues/40).

El plazo de 18 segundos empieza ahora al recibir el primer candidato
elegible. Los metadatos conservan sus plazos y los EPUB conservan su
tope de 8 segundos por comprobación, los límites de tamaño, los
presupuestos de solicitudes y el control de concurrencia. Si la
confirmación llegó a la caché justo antes de vencer el plazo, se
consulta la evidencia en memoria sin otra exportación. Un tiempo
agotado muestra su motivo y permite reintentar únicamente ese EPUB.
Repetir la misma búsqueda con un proveedor fallido reintenta solo ese
proveedor y conserva los libros ya confirmados.

La prueba determinista comprueba la promoción y la retirada de
**Pendientes** con una sola búsqueda, tanto con metadatos inmediatos
como con la espera simulada. También comprueba recuperación desde la
caché al vencer el plazo, reintento del EPUB, conservación de resultados
al fallar otro proveedor y rechazo de resultados cancelados al cambiar
de consulta, filtro o pantalla. El probe Desktop del 23 de noviembre de
2026 confirmó «A Venus» (1927) a los 4.502 ms en frío con una exportación
de 21.370 bytes. En caliente apareció a los 307 ms y sin otra exportación.
La medición funcional de Desktop queda separada de la validación física.

Para repetirlo en el realme C55, fuerza la detención de INFINILECT y
vuelve a abrirlo. En **Buscar → Legibles**, escribe «A Venus» y pulsa
una sola vez la lupa. Espera a que termine la comprobación y comprueba
que la obra aparece en la cuadrícula. Si la comprobación agota su plazo,
comprueba que la ficha pendiente indica el motivo y ofrece **Reintentar**.
Abre la ficha después de que el libro aparezca en la cuadrícula.

## Verificación EPUB automática en Buscar

En el realme C55, «A Venus» quedaba como candidato autorizado y requería
abrir la ficha y pulsar **Comprobar EPUB**. La búsqueda solo cargaba metadatos
y consultaba confirmaciones ya guardadas en memoria. Con caché vacía, el
controlador carecía de una ruta para incorporar nuevos EPUB confirmados.

Al enviar la consulta en **Legibles**, el controlador usa los candidatos que
ya superaron las reglas de derechos. Prioriza coincidencia exacta de título,
coincidencia tras normalizar mayúsculas, espacios y guiones bajos, coincidencia
parcial y el orden del proveedor para los empates. Una coincidencia exacta
puede comenzar mientras responde otra edición. La cuadrícula incorpora cada
EPUB al terminar su preparación. **Pendientes de comprobar** conserva las
fichas autorizadas que necesitan comprobación manual y distingue un fallo
temporal de un EPUB incompatible. **Todos** cancela las comprobaciones y
muestra el catálogo. Escribir sin enviar la consulta e Inicio consultan
metadatos. Volver a Legibles o solicitar otra página permite otra comprobación
acotada. El ajuste de modo se conserva en la preferencia existente.

| Recurso | Límite |
| --- | --- |
| Metadatos | Dos páginas y ocho fichas en ocho segundos por operación y edición. 24 solicitudes y un presupuesto de 8 MiB del cuerpo por minuto y edición, en bloques de hasta 8 KiB e incluidas las revalidaciones automáticas. |
| Verificaciones EPUB | Dos por operación explícita y cuatro comienzos por minuto en toda la sesión, una verificación automática activa entre todas las ediciones. |
| Tiempo | Ocho segundos por EPUB y 18 segundos desde el primer candidato elegible. Los metadatos tienen sus propios plazos. |
| Bytes EPUB | 512 KiB de cuerpo por intento, hasta 1 MiB por operación y 2 MiB por minuto de inicios. El exceso queda pendiente y sigue disponible la vía manual. |
| Memoria y espacio | El mismo preparador en IO, con sus límites existentes de entradas, ratio, análisis y descompresión. Una comprobación automática usa un archivo temporal de hasta 512 KiB y se libera al terminar o al cancelar. |
| Red móvil y Wi-Fi | Android ya declara el permiso que permite consultar si la conexión es medida. El mismo límite conservador se aplica en toda red para evitar depender de esa señal y mantener el comportamiento en Desktop. |

La comprobación usa la ruta HTTPS y las restricciones de red existentes.
Vuelve a comprobar derechos e identidad del recurso antes de solicitar el
EPUB y al finalizar su lectura. La validación ZIP/XML y de capítulos es la
misma que en la ficha. Las comprobaciones manual y automática se serializan
por edición y la ficha puede reutilizar una confirmación recién terminada.
El lector y la descarga consentida siguen siendo consumidores independientes
del transporte serializado. Una apertura posterior puede necesitar recuperar
el EPUB de nuevo. La comprobación temporal no crea una descarga offline.

La caché guarda 32 observaciones por edición. La identidad incluye la fuente,
página, revisión y clave de recurso con la política EPUB. Las confirmaciones
caducan en diez minutos, y los errores transitorios en 30 segundos. Una nueva
consulta, el cambio de fuente, **Todos** o salir de Buscar cancelan la
verificación activa. Al volver se conservan los resultados ya confirmados.
Sin conexión, los errores de proveedor son recuperables y las copias
descargadas siguen disponibles en su flujo independiente.

Para probar en el realme C55 con caché fría, usa «Forzar detención» en
Información de la aplicación, vuelve a abrir INFINILECT, entra en Buscar y envía «A Venus» con **Legibles** activo. Espera el estado de
comprobación y abre la ficha únicamente después de que la obra aparezca en
la cuadrícula. Repite la consulta con la caché caliente. Prueba también «A» y
comprueba que una revisión limitada permite elegir **Todos** o cargar otra
página. Cambia de modo o pestaña durante una comprobación y reabre en modo avión
un libro descargado previamente.

### Medición de la búsqueda automática del 23 de noviembre de 2026

El comprobador opt-in ejecutó el controlador de Buscar, el adaptador real de
cada edición y FileEpubPreparer en Desktop. Cada consulta partió de una
sesión de Wikisource nueva. «Caliente» repitió la consulta en la misma sesión
y ventana de presupuesto. Las peticiones y bytes de metadatos incluyen la
revalidación de derechos. Los bytes EPUB miden el cuerpo leído por la
aplicación. Los tiempos corresponden a una ejecución, incluido el transporte.

| Edición · consulta | Fichas evaluadas | Autorizadas | EPUB confirmados / pendientes | Metadatos fría peticiones / bytes | EPUB fría peticiones / bytes | Primer libro / total fría (ms) | Caliente peticiones/bytes de metadatos · total (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| es · A Venus (1927) | 8 | 1 | 1 / 0 | 14 / 33.226 | 1 / 21.372 | 7.412 / 7.412 | 2 / 2.475 · 314 |
| es · A | 8 | 0 | 0 / 0 | 11 / 44.229 | 0 / 0 | — / 5.043 | 0 / 0 · 6 |
| es · Rima LIII (1630) | 8 | 4 | 1 / 3 | 24 / 111.243 | 1 / 19.594 | 7.884 / 8.014 | 0 / 0 · 10 |
| es · Canción del pirata (2331) | 8 | 1 | 1 / 0 | 17 / 107.850 | 1 / 19.800 | 7.442 / 7.442 | 0 / 0 · 49 |
| en · Smokey the Bear Sutra (3879) | 1 | 1 | 1 / 0 | 4 / 17.447 | 1 / 23.773 | 5.006 / 5.006 | 0 / 0 · 10 |
| ca · L’emigrant (1683) | 8 | 1 | 1 / 0 | 15 / 34.073 | 1 / 20.570 | 7.902 / 7.902 | 0 / 0 · 4 |

Los cinco EPUB transferidos en frío pasaron la preparación. No se transfirió
ningún EPUB al repetir las consultas o pasar a «Todos». En «A», el catálogo
devolvió 20 fichas y un cursor. Ocho fichas entraron en la evaluación de
derechos y ninguna obtuvo autorización positiva. La cuadrícula estricta
quedó vacía y siguió disponible la continuación. «Rima LIII» agotó las
24 solicitudes de metadatos con tres candidatos pendientes. «A Venus»
se confirmó aunque hubo metadatos inválidos en otra ficha.
La repetición mediante la tarea Gradle oficial confirmó «A Venus» a los
5.009 ms en frío con 21.373 bytes EPUB y 33.226 bytes de metadatos. En
caliente apareció a los 268 ms y el comprobador leyó cero bytes EPUB.

Los picos muestreados de ZIP temporal fueron de 19.594 a 23.773 bytes en los
cinco casos, y los archivos de preparación se liberaron al cerrar el
comprobador. La variación de heap JVM muestreada entre inicio y pico fue de
7.515.664 a 135.847.680 bytes en los casos con EPUB. Incluye inicialización,
metadatos, red y recolección del proceso Desktop. El realme C55 queda
pendiente para medir memoria, datos y tiempos en Android.

### Compilación y regresiones

La ejecución completa aprobó 3.471 pruebas. Desktop aprobó 1.819,
Android-host 1.532 y las suites Core JVM y Core Android-host 60 cada una.
Compilaron Desktop, Android debug y Android release sin firmar. Pasaron los
ocho casos de la política de manifiestos, las comprobaciones de ambas
variantes y los recursos empaquetados. La revisión de localización validó
598 cadenas y tres plurales en español, inglés y catalán. Lint terminó con
cero errores y el aviso preexistente GradleDependency por variante sobre
Compose Foundation. Al ajustar el mensaje de presupuesto agotado se repitieron
las 1.532 pruebas de Android-host, las 120 del núcleo y 29 pruebas enfocadas
de Desktop que cubren descubrimiento, verificación y la vista a 320 dp con
fuente al 180 %.

Se repitieron juntas 53 pruebas de ApplicationInterfaceTest y 24 de
PageSpreadLayoutTest en 82d4b3f. Pasaron las 77. En la pasada completa actual
también pasaron ambas clases. Las excepciones anteriores de RectList
ocurrieron en el cálculo de posiciones interno de Compose durante la
selección en Biblioteca y la navegación hacia Historial. La evidencia
confirma su carácter intermitente, pero no permite atribuirlas a una carrera
concreta ni a una configuración del entorno. El tercer fallo anterior
correspondía a la alineación que comprobaba la aserción del selector y se
corrigió antes de esta revisión. Se conserva también el toque de duración
controlada de PageSpreadLayoutTest.

Los apartados siguientes conservan las mediciones históricas de las revisiones
previas, antes de la comprobación automática.

## Descubrimiento con caché fría en la PR #39

La búsqueda de una letra en el realme C55 dejó vacía la cuadrícula de EPUB
confirmados. La interfaz anterior mostraba a la vez «sin coincidencias»,
«sin lectura confirmada» y «revisión limitada». Cada mensaje se evaluaba
por separado. El 19 de noviembre la API española devolvió 20 fichas y un
cursor adicional para «A», así que el estado vacío no demostraba que el
catálogo careciera de coincidencias.

La búsqueda registra si encontró fichas y si terminó su evaluación. Omitir
una ficha con metadatos inválidos conserva los candidatos ya evaluados y
muestra el aviso correspondiente. Los fallos de transporte conservan su
tratamiento como errores de proveedor con opción de reintento.

Los candidatos con autorización positiva aparecen en **Pendientes de comprobar**
con un acceso para abrir su ficha. La acción **Comprobar EPUB** utiliza el
mismo preparador y actualiza el resultado al cerrar la ficha. «Legibles»
incluye solamente EPUB confirmados en su cuadrícula. «Todos» muestra las
fichas del catálogo con sus indicadores, incluso cuando los derechos siguen
sin verificar. El modo se sigue guardando en las preferencias existentes.
El selector ocupa una fila. La acción **Cargar más de Wikisource** permite
elegir una edición con su cursor y presupuesto propios.

Se conservan los límites de dos páginas, ocho fichas y ocho segundos por
operación, y 24 peticiones y 8 MiB de metadatos por minuto y edición.
La búsqueda no inicia exportaciones. En consultas amplias, los candidatos
pueden quedar fuera del tramo evaluado. El mensaje indica el alcance y
permite continuar o pasar a «Todos». Se conservan las copias offline
y sus posiciones de lectura.

### Medición del adaptador el 19 de noviembre de 2026

Se ejercitaron consultas literales por título en un proveedor nuevo por caso.
Las cifras son peticiones y bytes del cuerpo de metadatos y milisegundos
del adaptador en Desktop. La columna «Todos» mide su primera página con
la caché de esa consulta ya poblada. Los candidatos de la primera
evaluación estaban autorizados y pendientes. Solo se comprobó el EPUB
de cada una de las cinco fichas objetivo autorizadas.

| Edición | Consulta | Todos | Pendientes en frío | Confirmadas tras comprobación | Fría peticiones / bytes / ms | Caliente peticiones / bytes / ms | Evaluación limitada |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| es | A | 20 | 0 | 0 | 11 / 44229 / 4507 | 0 / 0 / 6 | Sí |
| es | A Venus | 20 | 1 | 1 | 12 / 27780 / 3109 | 2 / 2475 / 244 | Sí |
| es | Rima LIII | 11 | 4 | 1 | 17 / 102164 / 3544 | 0 / 0 / 3 | Sí |
| es | Canción del pirata | 20 | 1 | 1 | 11 / 99414 / 3007 | 0 / 0 / 1 | Sí |
| en | Smokey the Bear Sutra | 2 | 1 | 1 | 2 / 6589 / 1321 | 0 / 0 / 1 | No |
| ca | L’emigrant | 20 | 1 | 1 | 13 / 30159 / 3049 | 0 / 0 / 16 | Sí |

Para «A», la página general contenía una ficha web y 19 resultados sin
verificación positiva registrada en la proyección de esa página. La evaluación
limitada no devolvió candidatos ni confirmaciones. Para «A Venus», dos
peticiones en la repetición correspondieron a una evaluación no completada
de otra ficha. El probe registró metadatos inválidos en esa consulta y
conservó la candidata A Venus. Todas las demás repeticiones medidas
reutilizaron la caché sin tráfico adicional. Cambiar a Todos reutilizó los
metadatos en los seis casos. La búsqueda no inició exportaciones.

El probe admite, por ejemplo:

```sh
./gradlew :app:wikisourceReadabilityCheck --args='es --query "A"'
./gradlew :app:wikisourceReadabilityCheck --args='es --query "A Venus" 1927'
```

### Verificación de esta revisión

Compilaron Desktop, Android debug y Android release sin firmar. Pasaron la
verificación de ambos manifiestos, la de recursos empaquetados y el control
de 596 cadenas y tres plurales en español, inglés y catalán. Lint terminó sin
errores y emitió un aviso GradleDependency por variante en Compose Foundation.

Las suites del núcleo aprobaron 60 pruebas cada una. Android-host aprobó
1524. La pasada completa Desktop aprobó 1808 de 1811. Dos pruebas de
Biblioteca registraron excepciones de Compose RectList. Ambas pasaron aisladas
en esta rama y en el checkout histórico 82d4b3f. El tercer fallo correspondió
a la nueva prueba del selector. Su aserción comparaba los bordes de botones
con distinta altura al ampliar el texto. La versión corregida comprueba
alineación por el centro y separación horizontal, y pasó en el reintento
junto con las dos pruebas de Biblioteca.

Pasaron las 24 pruebas de PageSpreadLayoutTest en la ejecución completa.
El toque lateral posterior al deslizamiento ahora usa 16 ms de tiempo
sintético, igual que el resto de la secuencia. El reloj real podía convertir
el toque breve en uno mantenido bajo carga. Se conserva la aserción de
cambio de página y el código de producción del lector.

Desde un inicio en frío en Android, abre Buscar, escribe «A» y pulsa la lupa.
Comprueba que aparece una sola explicación vacía y una única acción de
paginación para Wikisource. Busca «A Venus», abre la ficha desde **Pendientes
de comprobar**, pulsa **Comprobar EPUB** y cierra la ficha para ver la obra
en la cuadrícula. Repite la consulta y alterna entre los modos. Después
cambia de idioma o edición y carga otra página. Prueba a 180 % de tamaño de
texto y verifica que los modos permanecen juntos.

## Filtro de disponibilidad de la PR #39

**Legibles** es el modo inicial. La cuadrícula muestra únicamente resultados con
derechos autorizados y una exportación preparada y validada con el lector real.
Las obras con autorización y un EPUB pendiente aparecen en un listado separado
de candidatos. Desde su ficha, **Comprobar EPUB** inicia una descarga temporal
explícita con los límites y el aviso existentes. Al cerrar la ficha, el resultado
se actualiza con la evidencia disponible sin repetir la búsqueda. **Todos**
conserva el catálogo paginado y muestra el estado conocido de cada resultado.
Una clasificación aún desconocida se actualiza al consultar su ficha.

La capacidad opcional `ReadabilityCatalogSource` conecta Wikisource con el motor
de búsqueda existente. El filtro y su preferencia son independientes del ajuste
de visibilidad de fuentes. Los estados distinguen confirmados, candidatos,
derechos sin verificar, incompatibilidad o ausencia, página web y error temporal.
Los EPUB que ya están en Biblioteca o Descargas conservan su identidad y lectura
offline. Con la caché fría puede no haber resultados confirmados. El texto del
estado vacío se refiere únicamente a la muestra evaluada y permite cambiar a
**Todos** conservando la consulta.

Cada operación estricta examina hasta dos páginas y ocho fichas en ocho segundos.
Cada edición tiene un presupuesto de 24 solicitudes y 8 MiB de metadatos por
ventana de un minuto. El cuerpo se lee en bloques de hasta 8 KiB y se cancela
al sobrepasar el límite. La búsqueda descarga cero EPUB. Los cursores conservan
el desplazamiento de la parte todavía no evaluada, están vinculados a consulta
y edición, y rechazan continuaciones repetidas. Se mantiene el límite de cuatro
operaciones por fuente en la interfaz. Los errores HTTP y la espera de Wikimedia
siguen el tratamiento existente. Inicio proyecta evidencia conocida sin evaluar
candidatos automáticamente.

Cada edición conserva hasta ocho páginas brutas (120 segundos) y 32 observaciones
técnicas, vinculadas al identificador y la clave de recurso con la revisión y
la política de validación. La evidencia técnica dura diez minutos y los errores
transitorios, 30 segundos. La proyección con caché requiere también metadatos
vigentes. Las confirmaciones son de sesión y una revisión nueva exige evidencia
nueva. Cambiar de modo cancela las cargas de Wikisource y conserva las demás fuentes.
Salir de la ficha cancela la comprobación pendiente mediante su ciclo de composición.

El probe manual acepta una edición y hasta diez IDs. Mide solicitudes y bytes del cuerpo
de metadatos y tiempo de cada llamada de búsqueda. Excluye la consulta inicial
para recuperar el título y la descarga explícita. Para recorrer la muestra
española anterior, usa sus diez IDs. En inglés se
puede usar 3879 y en catalán 1683.

```sh
./gradlew :app:wikisourceReadabilityCheck --args='es 1927'
```

El 19 de noviembre de 2026, una petición agrupada a la API oficial por edición
devolvió los diez IDs de la muestra española y ambos casos adicionales. Estas
peticiones confirman identidad y revisión, no la legibilidad del filtro. Don
Quijote de la Mancha pasó a la revisión 1693406. Las otras nueve revisiones
españolas y las dos adicionales coincidieron con las de referencia. No se
solicitaron exportaciones durante este control preliminar.

| Edición | Fichas | Peticiones | Bytes de cuerpo | Tiempo |
| --- | ---: | ---: | ---: | ---: |
| Español | 10 | 1 | 5472 | 549 ms |
| Inglés | 1 | 1 | 531 | 392 ms |
| Catalán | 1 | 1 | 488 | 347 ms |


El probe del adaptador recuperó primero la ficha objetivo para obtener su título.
Registró la primera búsqueda de ese título con metadatos de la ficha ya en caché
y sin confirmaciones EPUB. El tiempo y los bytes corresponden a metadatos
de esa búsqueda, excluyendo la descarga explícita y las cabeceras de transporte.

| Edición | ID | Estado tras la comprobación | En Legibles | En Todos | Peticiones / bytes / ms | Presupuesto alcanzado |
| --- | ---: | --- | --- | --- | ---: | --- |
| es | 51981 | UNVERIFIED | No | Sí | 1 / 638 / 243 | No |
| es | 1425 | UNVERIFIED | No | Sí | 1 / 551 / 176 | No |
| es | 2331 | READABLE_CONFIRMED | Sí | Sí | 1 / 624 / 144 | No |
| es | 1630 | READABLE_CONFIRMED | Sí | Sí | 1 / 674 / 243 | No |
| es | 3830 | WEB_ONLY | No | Sí | 10 / 46306 / 2148 | No |
| es | 18672 | WEB_ONLY | No | Sí | 8 / 85825 / 1454 | Sí |
| es | 256323 | WEB_ONLY | No | Sí | 1 / 783 / 191 | No |
| es | 1563 | UNVERIFIED | No | Sí | 22 / 44411 / 3665 | Sí |
| es | 15758 | UNVERIFIED | No | Sí | 1 / 563 / 172 | No |
| es | 1927 | READABLE_CONFIRMED | Sí | Sí | 1 / 534 / 137 | No |
| en | 3879 | READABLE_CONFIRMED | Sí | Sí | 1 / 593 / 234 | No |
| en | 3879 | READABLE_CONFIRMED | Sí | Sí | 1 / 593 / 234 | No |
| ca | 1683 | READABLE_CONFIRMED | Sí | Sí | 1 / 544 / 245 | No |

Con la caché caliente, la repetición de Legibles y el paso a Todos hicieron cero
peticiones adicionales para las doce fichas y tardaron 0–11 ms en el adaptador.
La primera búsqueda española tardó 137–3665 ms, con 1–22 peticiones y
534–85825 bytes de metadatos. Ninguna búsqueda descargó un EPUB. Las cinco
validaciones explícitas autorizadas usaron el preparador real y confirmaron los
tres textos españoles, Smokey the Bear Sutra y L’emigrant.

Las cuatro suites ejecutaron 3450 pruebas en total. Android-host aprobó 1522,
y las dos suites del núcleo aprobaron 60 cada una. Desktop aprobó 1807 de 1808.
El único fallo de la ejecución completa fue el gesto lateral del lector en
`PageSpreadLayoutTest`, que esperaba la presentación de la página 5. El mismo
caso pasó al ejecutarlo de forma aislada tanto en el filtro como en el commit
base 82d4b3f. La causa de la diferencia entre ejecuciones queda sin confirmar.

Compilaron Desktop, Android debug y Android release sin firmar. Los dos
manifiestos conservaron el identificador, SDK y permisos. Pasaron los ocho casos
de la política del manifiesto y la verificación de 588 cadenas y tres plurales
en los tres idiomas. Lint debug y release terminaron sin errores y con el mismo
aviso GradleDependency sobre Compose Foundation para teclado y ratón en ambas
variantes. El aviso apunta a una dependencia ajena al diff.

En Android, buscar A Venus con caché fría, abrir su ficha y comprobar el EPUB.
Cerrar la ficha y verificar su promoción a la cuadrícula. Cambiar a Todos y
volver a Legibles sin perder la consulta, probar el resultado web de un autor
y reabrir una copia descargada en modo avión. Repetir en inglés y catalán.

## Current result — 3 November 2026

Continues `8a41360aeaa22b49c36994042270d3b0614709c0`. The realme C55
report was reproduced **at the production source/metadata/UI-policy boundary**,
using official Action API responses. No Android device was available here.
The application previously recognized only English direct `PD-old` and a narrow
Commons scan chain. It also omitted the candidate EPUB resource whenever rights
were unknown. Thus missing positive legal evidence appeared together with an
apparently unavailable format and a generic “checked when opening” message,
although opening was disabled. This is a confirmed implementation problem;
it does not establish that the ten user-selected pages were legally downloadable.

The exact reported page, **Cardenal A. Mattei (Juicios)**, remains unavailable for
new acquisition: it is a dictionary entry, and its own revision provides no
recognized work/translation/edition permission. Its author cannot be inferred
from the historical person described by the entry. We do not inherit permission
from a dictionary index, an author biography or the general site footer.

Changes:

- Independent `CatalogPageKind`, `CatalogEpubState` and `CatalogRightsState`.
  A header identifies a **text with unverified extent**, not a complete book.
  Applied dictionary-navigation templates identify a section. Author/index/
  versions/disambiguation pages remain web-only.
- Non-auxiliary pages retain a candidate resource even when rights are unknown.
  **POTENTIAL means an unchecked export route, not proof that a file exists.**
  It never grants byte acquisition by itself. `EXPORTABLE` records the service's
  accepted response; only full production preparation records `VALIDATED`.
- Explicit, localized **Check EPUB** action for authorized pages, with notice
  that it temporarily downloads and discards an export. No export during search,
  composition or ordinary metadata lookup. Unknown rights instead offer a fresh
  source-declaration review; they do not trigger an exploratory binary download.
- Four direct work rules and one scan-chain rule, documented with scope,
  exclusions and revision evidence in [Wikisource rights](WIKISOURCE_RIGHTS.md).
  New ES/CA `DP-100` and EN `PD-release` rules do not depend on titles.
  Quoted/commented examples cannot authorize. Translations, edition fields,
  unknown transclusions, artwork and contradictory statements block direct grants.
  Rendered citations are checked for these additional scope hazards too.
- Bounded technical observations (32 revision-bound resources), distinct HTTP
  404, temporary service error and reader incompatibility. Cancellation, including
  a late non-cooperative inspector result, cannot publish a completed UI check.
- Retained copies still open from their original snapshot/hash without live rights
  checks. No migration, resource-key change, second download manager, parser
  relaxation, new permission/dependency or changes to other providers.

## Ten Spanish pages: reproducible before/after audit

Date: **3 November 2026**. All ten pages were inspected through real APIs before implementation. Baseline
`8a41360` source probes completed for six cohort pages; a second probe was
interrupted by the source rate-limit/cooldown and produced no page outcomes.
For the other four rows (marked †), the before state is **static evaluation of
the baseline classification/rights code against those real metadata responses**,
not an executed baseline source probe. The updated production source evaluated
all ten successfully. This distinction applies to the aggregate before counts.
Metadata requests used the identifying
INFINILECT User-Agent, HTTPS, `maxlag=5`, bounded responses and official APIs.
Samples were selected from real query results/linked pages, including applied
`DP-100` examples returned by the official `embeddedin` query. No embedded title
allowlist is used by the application. This is a deliberately mixed diagnostic
cohort, **not an estimate of the percentage of readable books in Wikisource**.

Before: “candidate” below means a `PublicationResource`, not a downloaded file.
Baseline runs checked metadata/rights only; they did **not** measure baseline
EPUB readability. After: real exports were acquired only for positively authorized
pages, then checked with `FileEpubPreparer` and every bounded spine window.
All other pages deliberately remained unexported.

| Page / official revision | NS | Before: type; rights; candidate | After: type; rights / evidence | Actual EPUB / decision |
| --- | --- | --- | --- | --- |
| [Cardenal A. Mattei (Juicios)](https://es.wikisource.org/w/index.php?curid=51981&oldid=154306) | 0 | Unknown; unknown; no | Section; unknown. Applied `Dicc`/`navegarendiccionarios`; no effective work-rights declaration. | Unchecked candidate; new reading/download blocked, official page available. |
| [El Matadero](https://es.wikisource.org/w/index.php?curid=1425&oldid=1393104) | 0 | Unknown; unknown; no | Text, extent unverified; unknown. Header/author alone do not grant permission. | Unchecked candidate; new acquisition blocked. Existing retained copies unaffected. |
| [Canción del pirata](https://es.wikisource.org/w/index.php?curid=2331&oldid=1248127) | 0 | Section; verified; yes | Section; verified `commons.scan.pd100.v1`. Single ProofreadPage slice → exact index → Commons declaration. | **Validated and readable** with real production preparation. No whole-volume claim. |
| [Rimas (Bécquer, 1885)/Rima LIII](https://es.wikisource.org/w/index.php?curid=1630&oldid=1614255) | 0 | Section; verified; yes | Section; verified `commons.scan.pd100.v1`. Exact scan declaration, not the author's age. | **Validated, read, downloaded and reopened offline**, three spine items. |
| [Autor:Miguel de Cervantes](https://es.wikisource.org/w/index.php?curid=3830&oldid=1626564) | 106 | Author; unknown; no | Author; rights not assessed as a book. | Web-only; not an EPUB work. |
| [Don Quijote de la Mancha](https://es.wikisource.org/w/index.php?curid=18672&oldid=1626565) | 0 | † Versions; unknown; no | Versions; applied editions/version evidence. | Web-only; does not choose a particular edition silently. |
| [Índice:José de Espronceda - Obras poéticas completas.djvu](https://es.wikisource.org/w/index.php?curid=256323&oldid=1607011) | 104 | † Index; unknown; no | Index; `proofread-index` model. | Web-only; an index is not a downloadable book. |
| [Coplas por la muerte de su padre](https://es.wikisource.org/w/index.php?curid=1563&oldid=1290321) | 0 | † Section; unknown; no | Section/composite; unknown. Scanned cover and forty linked descendants require separate scope evidence. | Unchecked candidate. Multi-chapter/illustrated scan graph deliberately not exported. |
| [A un olmo seco](https://es.wikisource.org/w/index.php?curid=15758&oldid=1564295) | 0 | † Unknown; unknown; no | Text, extent unverified; unknown. No applied recognized declaration. | Unchecked candidate; no author-age inference. |
| [A Venus](https://es.wikisource.org/w/index.php?curid=1927&oldid=1393137) | 0 | Unknown; unknown; no | Text, extent unverified; **verified `es.work.dp100.v1`**, applied direct `DP-100`. | **Validated, read, downloaded and reopened offline**, three spine items. Newly accepted by the general rule. |

The scan chain for Canción uses index `256323/r1607011` and Commons
`84568029/r1121815753`; Rima LIII uses index `148915/r1614406` and Commons
`26316893/r1189166312`. Both explicitly use `PD-scan|PD-old-100-expired`.

| Metric, fixed ten-page cohort | Baseline | Updated | Interpretation |
| --- | --- | --- | --- |
| Useful non-UNKNOWN classification | 6/10 | 10/10 | Text is not promoted to complete work. |
| Candidate resource represented | 2/10 | 7/10 | Five additional routes represented; no legal grant implied. |
| Positive rights evidence | 2/10 | 3/10 | A Venus newly accepted. |
| Authorized acquisition/read routes | 2/10 | 3/10 | All three updated routes passed actual EPUB preparation. |
| EPUB files actually checked in this audit | Not checked in baseline | 3/10 validated | Cannot claim a measured baseline reading-success increase. |
| Auxiliary exclusions | 3/10 | 3/10 | Appropriate author/index/versions exclusions. |
| Non-auxiliary rights unresolved | 5/10 | 4/10 | Insufficient evidence is not a copyright verdict or a technical failure. |

**Seven of ten still cannot be newly read/downloaded.** Three are auxiliary pages;
four lack a verified, sufficiently scoped grant. The remaining constraints are
missing original/translation/edition evidence or an unverified descendant graph,
not a demonstrated EPUB failure. The audit does not establish that those four
are protected or that no EPUB exists. Removing this block by inheriting the site
license, guessing lifespan, or licensing forty children from one cover would
introduce false authorizations. General availability is therefore **not declared
solved**; these rules reduce demonstrated false rejections while exposing the
remaining evidence gap honestly.

## Actual reading and retained-file checks

The opt-in `:app:wikisourceOfflineCheck --args='<language> <page ID>'` now performs:
real title search → details/rights → explicit production check + chapter navigation
→ existing manager download → close source/store → recreate store → prepare/read
using retained bytes with the network source closed → restore the durable locator
→ delete download while retaining progress → close resources/remove scratch files.
These are host integration results, not Android physical validation. Dynamic
exports can differ between requests; hashes below identify the retained artifacts
from this run and are **not application allowlists**.

| Edition / page / root revision | Rule | Bytes / SHA-256 | Result on 3 November 2026 |
| --- | --- | --- | --- |
| ES [A Venus](https://es.wikisource.org/w/index.php?curid=1927&oldid=1393137) | `es.work.dp100.v1` | 21,380 / `03ec8548258bfee945562f301d70fe6431740dbc406ed73e586807b452a3e2cb` | Explicit check, read all 3 spine items, retained reopen/locator/deletion passed; 2 rights statements retained. |
| ES [Rima LIII](https://es.wikisource.org/w/index.php?curid=1630&oldid=1614255) | `commons.scan.pd100.v1` | 19,593 / `b46a42e254aae3d8f91cbdabf2075f84cf1ad85e662ecf88542d9b23c989b517` | Same full flow passed; 3 spine items, 2 rights statements. |
| EN [Smokey the Bear Sutra](https://en.wikisource.org/w/index.php?curid=3879&oldid=15899522) | `en.work.release.v1` | 23,772 / `ccc1bfa5ee3889fd5ae6ea9a632a0d9b41823684f556d6648089232b0f699cd7` | Same full flow passed; 3 spine items, 2 rights statements. Release by copyright holder, not an age assumption. |
| CA [L'emigrant](https://ca.wikisource.org/w/index.php?curid=1683&oldid=63283) | `ca.work.dp100.v1` | 20,571 / `d4baa79d87ba34ba3c60f3fcda4b4308aba8fc656e825442fa6a7dc0c44532f3` | Same full flow passed; 3 spine items, 2 rights statements. |
| CA [Introducció](https://ca.wikisource.org/w/index.php?curid=3094&oldid=178423) | `commons.scan.pd100.v1` | 66,314 / `461012a10a630573a7e98a279b8beca7cd9caaa7a32605c2791cea275d068779` | Existing scan-chain regression passed; 3 spine items, 2 rights statements. Check current root revision in probe output when repeating. |

English **The New Colossus** (`72137/r15492692`) also passed the complete flow:
22,448 bytes, SHA-256 `4a5c8c3d905f75150b6dc3584cdb791cb0cb61804a63af9caacb448a3ea6488c`,
three spine items and two retained rights statements, using `en.work.pdold.v1`.
The first rerun found a newly overstrict rejection of the reviewed
`WP link` header annotation. Its official template `14965389` only constructs a
Wikipedia link. Acceptance is now restricted to EN/header/one plain bounded
positional argument; arbitrary templates, top-level use and parameter injection
remain rejected. This is a template-pattern rule, not a book exception.

## Additional deterministic regressions

A new rights-scope fixture demonstrated that a direct work banner could otherwise
license an unverified relative child-page graph (`[[/Different edition chapter]]`).
It failed before the guard was added; direct grants now require that graph to be
absent. This is independent of a chapter's own explicit grant and does not rewrite
retained files. A separate UI regression ensures that **Review source declarations**
only refreshes metadata, even if a new positive grant appears. Only the subsequent
explicit **Check EPUB** action may acquire the export with the displayed notice.
The production refresh path bypasses the selected-page TTL without exporting.

## Reproduction, tests and physical acceptance

Ordinary suites use synthetic public-API/EPUB fixtures, never live network. Added
regressions cover independent candidates/rights, applied-vs-cited declarations,
translation/edition/artwork hazards including notes, contradictory licenses,
dictionary classification, real preparer checks/corrupt ZIP recovery, 404/503,
cooperative and delayed cancellation, explicit UI consent, localized large-font
layout, and search → retained offline progress/delete behavior.

Opt-in metadata/limited authorized-export audit (not a normal build dependency):

```sh
./gradlew :app:wikisourceAvailabilityCheck --args='es 51981 1425 2331 1630 3830 18672 256323 1563 15758 1927'
./gradlew :app:wikisourceOfflineCheck --args='es 1927'
./gradlew :app:wikisourceOfflineCheck --args='es 1630'
./gradlew :app:wikisourceOfflineCheck --args='en 3879'
./gradlew :app:wikisourceOfflineCheck --args='ca 1683'
```

The availability probe exports only authorized entries. No bulk crawl, speculative
unknown-rights downloads or personal/profile data is involved. Prior bounds,
source visibility preference, stable IDs, resource keys and retained revisions
remain in force. The actual parser and downloader, not fixtures, were used for
live checks. No remote images/scripts/CSS, XML entities/DTDs or external resource
requests become permitted. Passive attribution links and separate rights remain
preserved by the existing parser.

Final full-suite/build, manifest, packaged-resource and lint results are recorded
in the verification table below after the frozen-source run. The initial new
child-graph regression failed on Android-host, and the new consent UI regression
failed with one unexpected export call. Both passed the focused rerun after the
minimal fixes. No existing test was disabled or weakened to hide those failures.

| Final executed suite | Cases | Failures / errors / skipped |
| --- | ---: | --- |
| Desktop | 1802 | 0 / 0 / 0 |
| Android-host | 1516 | 0 / 0 / 0 |
| Core JVM | 60 | 0 / 0 / 0 |
| Core Android-host | 60 | 0 / 0 / 0 |
| **Total** | **3438** | **0 / 0 / 0** |

**BUILD SUCCESSFUL in 15m 2s.** All four suites actually executed with final code.
Android debug, Android unsigned release, Desktop compilation, Android check,
Lint debug/release (**zero issues**) and both final merged-manifest checks passed.
Eight synthetic updater-manifest safety cases also passed (additional to JUnit).
Localization: **577 strings and three plurals** in en/es/ca; all six prepared
resource assets in each APK matched byte-for-byte. No probe/test classes,
private keys, fixture EPUBs or partial files were found in either packaged APK.

Both manifests retain `org.infinilect.app`, minSdk 26/targetSdk 37, INTERNET,
ACCESS_NETWORK_STATE and the existing internal AndroidX permission. No install
permission; updater receiver remains non-exported with no intent filter. Debug
signature validation passed; release remains unsigned. No APK was published.
The 457 source/configuration files were unchanged during this final run.
Protected provider/download/reader/updater/Android configurations are unchanged
from the starting commit. `git diff --check` passed.


Realme C55 checklist: find A Venus and Rima LIII in ES; verify page type and
separate rights/EPUB explanation; explicitly Check EPUB; read and navigate; download
with consent; close/reopen with airplane mode and confirm position; delete only
the download and confirm Library/history/progress remain. Repeat EN Smokey the
Bear Sutra and CA L'emigrant; try cancellation/tab switching. Cardenal must show
section + unchecked EPUB + unresolved authorization and official-page access,
without falsely offering acquisition. Author/index/versions pages stay web-only.
Check ES/EN/CA, light/dark and large fonts. Do not treat this as physical acceptance
until the user runs it. PR #39 remains Draft; no APK/release is published.

---

## Previous 31 October implementation record (historical)

The record below describes the preceding two-rule implementation. The states,
rights-rule list and observed availability above supersede it; retained-file,
network, parser and identity limits continue to apply.

### Wikisource: under-demand catalog, EPUB and retained reading — PR #39

Continuation of `3ff47bba94dbeead061d5dac5e3e24c0fd453f09`, **31 October 2026**.
The catalog no longer intersects results with an incorporated list of titles,
page IDs, revisions, text fingerprints or attribution URLs. A single adapter is
configured for **Spanish, English and Catalan**. It searches public pages under
demand, including author and index namespaces. Search never exports a book.

This is **not universal book completeness, copyright clearance or EPUB support**.
Unknown pages remain exploratory documents. Individual pages may have only web
information, insufficient rights evidence, a potential export, a previously
validated export, or a controlled reader/download error. An ordinary API hit,
namespace 0, a header, a MIME label and HTTP 200 do not prove a complete book.
No PDF/EPUB2 export or other linguistic project is advertised. The generic EPUB
boundary itself continues to support its existing EPUB2/3 subset.

PR #39 remains Draft. Android physical acceptance is pending. No APK/release is
published. PR #38/OAPEN, Gutenberg, Internet Archive's policy, updater, permissions,
signing, SDK, dependencies and persistent schemas are unchanged.

### Official references and observations

- [Action API](https://www.mediawiki.org/wiki/API:Action_API),
  [Search / generator](https://www.mediawiki.org/wiki/API:Search),
  [Revisions](https://www.mediawiki.org/wiki/API:Revisions),
  [Allpages](https://www.mediawiki.org/wiki/API:Allpages).
- [ProofreadPage index / scan model](https://www.mediawiki.org/wiki/Extension:ProofreadPage),
  [WS Export](https://wikisource.org/wiki/Wikisource:WSexport),
  [export service](https://ws-export.wmcloud.org/).
- [Wikimedia User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy),
  [API etiquette](https://www.mediawiki.org/wiki/API:Etiquette),
  [terms of use](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
- [Spanish rights policy](https://es.wikisource.org/wiki/Wikisource:Derechos_de_autor),
  [English copyright policy](https://en.wikisource.org/wiki/Wikisource:Copyright_policy),
  [Catalan rights policy](https://ca.wikisource.org/wiki/Viquitexts:Drets_d%27autor).
- [English PD-old declaration](https://en.wikisource.org/wiki/Template:PD-old),
  [Commons PD-scan](https://commons.wikimedia.org/wiki/Template:PD-scan),
  [PD-old-100-expired](https://commons.wikimedia.org/wiki/Template:PD-old-100-expired),
  [Commons licensing](https://commons.wikimedia.org/wiki/Commons:Licensing).
- EPUB/XML/HTML specifications and retained DOCTYPE/citation protections:
  [EPUB boundary](EPUB.md).

Small read-only research calls on 31 October verified official siteinfo, page
metadata, wikitext declarations, indexes and Commons file declarations in all
three editions. Namespace IDs differ: ES author 106 / index 104; EN author 102 /
index 106; CA author 106 / index 104 (`Llibre`). Site rightsinfo declares CC BY-SA
4.0 for contributions; **this is not an original-work license**. No HTML scraping,
service protection bypass, massive crawl or private wiki request was used.

### Architecture and API

`WikisourceEdition` internally fixes project, API, language and namespace IDs.
Only `es.wikisource.org`, `en.wikisource.org`, `ca.wikisource.org`, the fixed
Commons Action API (scan-rights metadata only), and `ws-export.wmcloud.org` are
used. Remote data cannot select another project, API, path or export server.

`WikisourceSource` owns transport/lifetime and delegates to:

- `WikisourceCatalog`: general queries, page identities, pagination, metadata and
  small cache. `WikisourceJson` applies strict UTF-8/JSON bounds and duplicate-key
  rejection. `WikiTextMetadata` reads bounded declarations, not rendered HTML or
  an executable MediaWiki interpreter.
- `WikisourceRights`: separate positive-evidence rules. No title/page/revision
  allowlist. Unknown syntax, missing evidence and contradictory restrictions
  fail closed for new acquisition, while metadata remains useful.
- `WikisourceExportValidation`: pure technical identity/semantic validation,
  after all ordinary `FileEpubPreparer` ZIP/XML/resource validation. The exact
  acquired/retained `Publication` snapshot is passed to the optional rejection
  hook. It cannot bypass the preparer or require a live network on reopening.
- Common `CatalogPublicationSource` / `CatalogPublicationInfo`: individual page
  capability and resolved bibliographic snapshot, separate from source-wide
  implemented reader capability. Details show localized classification and EPUB
  state, available authors/rights and an explicit official-page browser action.
  Delayed metadata for another page cannot enable its controls. Retained copies
  remain readable/removable even if current metadata fails.

Official metadata requests use `action=query&format=json&formatversion=2&maxlag=5`:

| Operation | Parameters / authority |
| --- | --- |
| Terms/title/author-name search | `generator=search`, encoded `gsrsearch`, configured main/author/index namespaces, `gsrlimit=20`, `gsroffset`; inexpensive `info|pageprops|revisions`, `rvprop=ids` (no `rvlimit` on a generator). |
| Selected page | Positive numeric `pageids`, same metadata plus `rvprop=ids|content&rvslots=main&rvlimit=1`. |
| General Home browsing | Under-demand `list=allpages`, namespace 0, nonredirects, 20/page, then metadata enrichment of only those IDs. No featured title list. |
| Scan-rights chain, if applicable | Selected page's single ProofreadPage index → matching index namespace record → same-named Commons File record. Only fixed official APIs, never a metadata-supplied file URL. |
| EPUB export, explicit open/download | `https://ws-export.wmcloud.org/?lang=<configured>&page=<encoded canonical title>&format=epub-3`. |

Search includes author pages and full-text matches of author names. This is not
an exhaustive structured “all works by author” index: project markup varies.
No author is invented from snippets; the selected page's plain header author is
used when reliable. Missing covers/descriptions/publishers/ISBNs remain absent.
Users can use normal MediaWiki search terms, including `intitle:`. Query text is
encoded as data and cannot inject URL parameters.

Only the selected page requests categories: category-property continuation must
not mask the search generator's next page. Real requests exposed and corrected
MediaWiki's rejection of `rvlimit` for multiple pages and the official
`proofread-index` content model on indexes. Synthetic regressions now enforce
both contracts. Search rank is retained when supplied; IDs are deduplicated within a page and by
the existing discovery controller across loaded pages. Opaque continuation
values are query **and edition** bound, never network URLs; search offsets stop
at 1,000 and the discovery UI's existing loaded-result limits still apply.
Requests are cancelable. Hiding a provider retires its generation and prevents
late results from returning. An unrecognized page remains `DOCUMENT`, not `BOOK`.
Author/index/disambiguation/versions evidence excludes acquisition; a header's
nonempty section or proofread slice is displayed as a section, without promising
the entire source volume. No generic complete-work inference is introduced.

### Rights policy, independent of parser validity

Positive rules currently implemented:

1. English namespace-0 page with a **direct, unparameterized, standalone
   `{{PD-old}}` declaration**. This template explicitly declares publication
   expiry and at least 100 years since the author's death. Unknown alternate PD
   declarations, copyright warnings, examples and ambiguous translation/edition
   scope do not grant. This is source evidence, not a warranty about every country.
2. A single proofread-page slice, with no unverified descendant TOC/transclusions
   or additional prose/artwork, tied to a matching official index and Commons
   scan. The Commons file must explicitly declare `PD-scan|PD-old-100-expired`.
   The declaration must be top-level or the Book's actual Permission field.
   Automatic-expiry/death-year inference is not enabled; a device clock is not
   authoritative rights evidence.
   Generic “Public domain”, `Copyrighted=False`, PD-old-80, mismatching files,
   unknown/contradictory claims and a site-wide footer are insufficient.

These rules accept arbitrary titles satisfying the evidence, not arbitrary
licenses. Other valid free licenses may remain unrecognized and catalog-only.
The UI states that evidence is insufficient and offers the official page; it
does not suggest a corrupt file or universal permission. Broader license patterns
need documented semantic/territorial analysis and reproducible tests, not more
approved-title entries. Runtime template definitions are not evaluated; the
semantics of recognized declarations are a reviewed policy dependency.

Original works, modern editions/translations, scan reproductions, wiki
contributions and API facts are distinct. The official declaration and revision
references are retained as source evidence. The EPUB's own separate `dc:rights`,
credit text and links are preserved **without substituting the website's CC
BY-SA 4.0, merging licenses or applying INFINILECT's GPL to a book**. A valid EPUB
alone never grants download rights. Profile/country is not sent or used as a
false legal-eligibility proof. No credentials or book text are logged.

**Legacy El Matadero:** existing copies retain `wikisource-es` / page `1425`,
resource `epub3-policy1-r1393104`, original bytes and progress keys. Reopening uses
only the retained snapshot and hash-checked file. The new generic policy does
not grandfather a title whitelist: its current header does not itself establish
one of the automatic positive declarations, so a **new acquisition** can be
catalog-only. This does not revoke, overwrite, hide or reauthorize old saved
copies. Additional general rights rules would need independently verified data.

### Dynamic EPUB validation, limits and versioning

No exact chapter count, text/reference hash, fixed rights list or known revision
is required. After complete structural validation, the policy binds configured
edition/page identity, EPUB resource key, language and the official OPF identifier
to the source snapshot's canonical page URL. An old first-letter case alias is
accepted only together with an exact bibliographic title match. Bibliographic
titles may omit an edition suffix if the identifier matches exactly. All spine
items are interpreted with the real bounded parser windows; nonempty readable
content is required. Fingerprints remain an **opt-in evidence tool only**.

Original ZIP/XHTML is never rewritten. HTTPS-only passive citations, inert
DOCTYPE forms, all rights/attributions and internal navigation are preserved.
Scripts, remote images/CSS/fonts/embedded resources, external DTD/entities,
unsafe paths, ZIP bombs and XML/resource excesses remain rejected by the same
production parser. A narrowly attribute-limited XHTML `link` with a single qualified RDFa relation
(e.g. `dc:relation`, not a provider-name rule) is retained as inert metadata.
Only `rel`/`href` attributes and a strictly valid HTTPS target qualify; mixed
stylesheet/preload relations, loading hints and other URL-bearing nodes keep
resource validation. This fixes the actual Catalan interlanguage annotation
`mw:PageProp/Language` without granting access to an external resource. The
reader does not evaluate RDFa or render/fetch these annotations. Their original
bytes remain intact. [RDFa Core](https://www.w3.org/TR/rdfa-core/) and
[HTML link-type distinction](https://html.spec.whatwg.org/multipage/links.html#links)
were checked on 31 October. No automatic network request/browser opening is
introduced by EPUB analysis. The details dialog's separate official-page button opens a
validated HTTPS project page only after an explicit user click.

| Bound | Current policy |
| --- | --- |
| Metadata transport / selected wikitext string | 1 MiB / 512 Ki UTF-16 units, strict UTF-8 and duplicate-key rejection; depth 24, 10,000 structural tokens. The larger metadata envelope is for one selected page, not EPUB/XML limits. |
| Detailed cache | 32 records, 120 s TTL; raw wikitext discarded after evidence evaluation. No whole catalog/index cached. |
| Technical validation observations | At most 32 page/resource pairs per source/session; not immutable future-byte promises. Every acquired file is still validated. |
| Network | One metadata operation and one streamed export per configured project; existing global retained-download concurrency remains one. No new background crawler/retries. |
| Timeouts | Connect 5 s, metadata total 20 s/socket 15 s; export request/handle 60 s. |
| HTTP errors | No HTTP redirects, including same-site redirects. 403/404/5xx fail; 429 Retry-After/maxlag/busy impose bounded 30–300 s cooldown, then explicit retry. Provider response text is not surfaced. |
| EPUB | 32 MiB archive, 64 MiB expanded, 8 MiB entry, ratio 100, 512 ZIP entries, 1 MiB XML, 256 manifest items, 128 spine entries; existing semantic quotas unchanged. |

Source ID is `wikisource-<language>`, local ID the positive MediaWiki page ID.
Resource key remains `epub3-policy1-r<root revision>`, including existing books.
The root revision is checked fresh before and at EOF after export, including
rights evidence. It is **not** a snapshot of all templates, images or descendant
revisions; WS Export is dynamic. `resource.revision` remains null so changing ZIP
bytes are never an unconditional resource-cache hit. A retained copy stores its
complete SHA-256 and original publication/resource snapshot. No automatic update
or overwrite occurs: the existing store rejects a new pending download while
that ID already has a completed copy. Explicit removal/reacquisition is required;
there is no cross-edition locator migration. Exact-position checks here concern
the **same retained bytes**. After explicit deletion/reacquisition, descendants
can have changed without a new root revision; the existing reader may restore
an approximate position or fall back to a valid location. No exact cross-edit
position guarantee is made. All actual acquisitions
are fully validated regardless of the last observed session state.

### Offline route and source visibility

The existing manager owns consent, streamed progress (indeterminate when size is
unknown), cancellation, explicit retry, quotas, atomic publish, partial cleanup,
leases, deletion and recovery. No second manager, SQL schema or import copy is
created. Abandoning a dialog does not leak a source/client; source cancellation
and stream cleanup run on owned IO jobs, and final application closure joins the
Wikisource owners. Android background/process recovery semantics are unchanged:
interrupted downloads require explicit retry; no HTTP-range resume is promised.

Opening first tries the real private retained copy; its source and transport can
be closed. A new store/reader/progress writer can reopen it without API/WS Export.
Library membership, statuses, favorites, tags, History and reading positions stay
independent. Removing the downloaded file does not remove those records.

“Show sources without integrated reading” remains **false by default**, persisted
with existing preferences. All three Wikisource editions have implemented EPUB
routes but page capabilities vary. Internet Archive remains visible; Gutenberg
remains catalog-only/hidden initially. Capability is implementation-based, never
source-name exceptions or transient network health. Library and History always
use the full source registry, even when a discovery provider is hidden.

### Reproducible checks and recorded results

Ordinary suites use original synthetic EPUBs and deterministic HTTP fixtures.
Coverage includes es/en/ca, general queries/author namespaces, bound pagination,
classification, incomplete metadata, strict JSON, cancellation, HTTP/redirect/
rate limits, dynamic rights, unknown/contradictory claims, scan binding, arbitrary
EPUB2/3 shapes/attributions, identity mismatch, legacy keys, unsafe resources,
revision changes, partial cleanup, retained restart, no overwrite, source-closed
opening, UI late results, translated narrow/large-font details and explicit links.
The fixed-text rejection tests were replaced by arbitrary-valid-text acceptance
plus identity/security rejection, as required by the new policy; parser/security
assertions are retained.

Network tasks are explicit opt-in, separate from ordinary test/check/build:

```sh
./gradlew :app:wikisourceCatalogCheck --args='en Austen 72137 4172199'
./gradlew :app:wikisourceOfflineCheck --args='en 72137'
./gradlew :app:wikisourceOfflineCheck --args='es 2331'
./gradlew :app:wikisourceOfflineCheck --args='ca 3094'
# Legacy real-file compatibility regression; reads local original bytes, no network:
./gradlew :app:wikisourceFeasibilityCheck --args=/path/to/official-el-matadero.epub
```

Each offline check uses the actual adapter, rights policy, preparer/parser,
retained manager and reader. It records technical counts/hash, closes the source,
recreates the store, navigates chapters, persists/restores a real locator, removes
the download while retaining progress, then cleans private temporary files.
Maintain the execution environment's existing proxy/CA for JavaExec when needed.

### Real sample matrix — 31 October 2026

These were limited production-adapter checks, not synthetic availability claims:

| Official case / project / page / observed root revision | Classification and rights | Export, real preparation and reading |
| --- | --- | --- |
| [The New Colossus](https://en.wikisource.org/wiki/The_New_Colossus), en / 72137 / 15492692 | Proofread slice, direct PD-old; positive original-work evidence | **Passed**, 22,453 bytes, 3 spine sections, 2 rights. Network source closed, store recreated, real locator restored. |
| [Canción del pirata](https://es.wikisource.org/wiki/Canci%C3%B3n_del_pirata), es / 2331 / 1248127 | Proofread slice, exact index 256323/r1607011 → Commons file 84568029/r1121815753, PD-scan/PD-old-100-expired | **Passed**, 19,801 bytes, 3 sections, 2 rights; same source-closed reopening/progress/removal checks. |
| [L’Atlàntida / Introducció](https://ca.wikisource.org/wiki/L%27Atl%C3%A0ntida/Introducci%C3%B3), ca / 3094 / 178423 | Chapter, index 10336/r167458 → Commons file 11771737/r1097684182, PD-scan/PD-old-100-expired | **Passed after inert RDFa metadata correction**, 66,320 bytes, 3 sections, 2 rights; source-closed reopening and locator restored. The unmodified inspected export also declares local PNG/JPEG resources. |
| [Pride and Prejudice (1813)](https://en.wikisource.org/wiki/Pride_and_Prejudice_(1813)), en / 4172199 / 14498612 | Unclassified complete-volume candidate, direct PD-old positive | Real export acquired but **rejected with EpubFailure.LIMIT** in XML element text accumulation (existing 8,192-unit per-element bound). Reader/offline-ready not reached; partial file removed. No limit was raised to admit it. |
| [Author:Jane Austen](https://en.wikisource.org/wiki/Author:Jane_Austen), en / 941 / 15193679; [Autor:Miguel de Cervantes](https://es.wikisource.org/wiki/Autor:Miguel_de_Cervantes), es / 3830 / 1626564 | AUTHOR / web-only | Real metadata, no export/download offered. |
| [The Raven](https://en.wikisource.org/wiki/The_Raven), en / 4031 / 15436642; [Tirant lo Blanc](https://ca.wikisource.org/wiki/Tirant_lo_Blanc), ca / 21078 / 168278 | DISAMBIGUATION / web-only | Real metadata, no export. |
| [Pride and Prejudice](https://en.wikisource.org/wiki/Pride_and_Prejudice), en / 4185790 / 13374640; [Don Quijote de la Mancha](https://es.wikisource.org/wiki/Don_Quijote_de_la_Mancha), es / 18672 / 1626565 | VERSIONS / web-only | Category evidence verified; not presented as one exportable book. |
| [Llibre:La Atlantida (1886).djvu](https://ca.wikisource.org/wiki/Llibre:La_Atlantida_(1886).djvu), ca / 10336 / 167458; [Espronceda index](https://es.wikisource.org/wiki/%C3%8Dndice:Jos%C3%A9_de_Espronceda_-_Obras_po%C3%A9ticas_completas.djvu), es / 256323 / 1607011 | INDEX / web-only | Actual proofread-index metadata accepted; no source scan downloaded. |
| [La vaca cega](https://ca.wikisource.org/wiki/La_vaca_cega), ca / 1909 / 136085 | Slice, insufficient recognized original/edition rights evidence | Catalog-only, no export. Its Commons scan's PD-old-80 is not the required positive rule; extra page transclusions are not assumed licensed. |
| [El Matadero](https://es.wikisource.org/wiki/El_Matadero), es / 1425 / 1393104 | General catalog metadata; no recognized automatic rights declaration | New acquisition withheld. Separate **legacy original-file regression** verifies the previous retained identity and unchanged EPUB through the current technical policy/reader, not a new authorization. |

Observed SHA-256 values of the successful dynamic exports (no fixed admission hashes):

```text
en 72137   2026-10-31T08:07:03Z  9acc020741964aa6b76a8d06c0974df96323f4fcf8d8656cdd97d954374242c5
es 2331    2026-10-31T08:15:27Z  3ce1908e02ec61d79ab183b7a1a71c0b82d5859a3fc9001ec24b1de38fe2eaed
ca 3094    2026-10-31T08:28:29Z  403b01ef683d5a87fde4870f8ac59b47ddcf1b8456b69314999518195aba1e98
```

A separate unmodified Catalan inspection export was 66,321 bytes /
`c4f54de3cef92276c22882b294a1ec803183edaa9a15a164624fc1a61e9624fa`.
Different ZIP timestamps produce different bytes; no fixed byte hash is used for
remote admission. Ordinary suites contain no downloaded book binary/text.

Real term/author-name queries `Austen`, `Cervantes` and `Maragall` each produced
20 results, a real continuation and another 20 results with zero overlapping IDs
in the checked two pages. This verifies a sample of paging, not exhaustiveness
or an immutable server order. Classification/rights examples above used selected
page IDs; no EPUB was downloaded while searching. Early failed probes revealed
API-contract and RDFa issues and published no retained copies. Those failures
were corrected with bounded generic behavior; the long-novel limit is retained.

### Final full verification

Linux x86_64, JDK 21 / Gradle 9.7.1, one Gradle worker and the existing cached
Skiko runtime for headless Compose. Source/build inputs were held unchanged
through the run (454 files checked). **BUILD SUCCESSFUL in 13m 45s.**

| Complete suite | Executed cases | Failures / errors / skipped |
| --- | ---: | --- |
| Desktop | 1,785 | 0 / 0 / 0 |
| Android-host | 1,501 | 0 / 0 / 0 |
| Core JVM | 60 | 0 / 0 / 0 |
| Core Android-host | 60 | 0 / 0 / 0 |
| **Total** | **3,406** | **0 / 0 / 0** |

All four suites actually ran, with no disabled/skipped test cases or weakened
security assertions. The prior 3,333-case figure is the preceding commit's
baseline, not this continuation's result. This continuation adds 38 Desktop and
35 Android-host cases. Ordinary fixtures are original; live probes are separate.

Android debug, **unsigned** Android release, Desktop compilation, Android check,
Lint debug/release, packaged languages and both updater-manifest policies passed.
Both Lint XML reports contain **zero issues**. The manifest-policy task also ran
its eight synthetic cases separately from the JUnit count above. Existing
unrelated compiler warnings (deprecated test clipboard API, unused expressions,
PDF non-null assertions) were not suppressed or changed.

`tools/check_localization.py`: **566 strings and 3 plurals** in en/es/ca. All six
resource assets in **both APKs** were compared byte-for-byte with generated
resources. No test/probe class names, private keys or book/partial-file assets are
packaged. Debug APK signature verification passes; the release output is verified
unsigned and is not an installable public release. Signing configuration is
unchanged; no externally supplied key or password was needed/published here.

Both built manifests retain `org.infinilect.app`, versionCode 1 /
`0.0.1-SNAPSHOT`, minSdk 26, targetSdk 37, INTERNET, ACCESS_NETWORK_STATE and the
AndroidX internal signature permission. REQUEST_INSTALL_PACKAGES remains absent;
the updater receiver remains non-exported. No manifest/SDK/updater/download-store/
Archive/Gutenberg/OAPEN/schema/dependency implementation changes were made.
Changed-file checks found only Kotlin/XML/Markdown/Gradle source, no private-key
markers or generated artifacts. `git diff --check` passes. No APK/release is
published and no Android device/Play Protect/FPS acceptance is claimed.

```sh
./gradlew --max-workers=1 --continue \
  :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:assembleRelease \
  :androidApp:verifyDebugLanguageResources :androidApp:check \
  :androidApp:lintRelease :desktopApp:compileKotlin
python3 tools/check_localization.py
git diff --check
```


### Physical acceptance and remaining limits

1. On Android/Desktop search arbitrary title/author terms with each of the three
   Wikisource editions; load another page. Main pages must not all say “complete
   book”; author/index/versions/disambiguation pages must not offer fake readers.
2. Open a positively authorized sample from the recorded table; distinguish
   potential from validated EPUB. Verify original rights/credits, local images,
   notes, TOC and internal links; passive external citations do not fetch/open.
3. Explicitly download, cancel, retry and switch tabs during transfer. Use a slow
   or absent connection. No partial copy should become offline-ready; cooldowns
   must not cause unbounded retries or UI blocking.
4. Complete, disable Wi-Fi/mobile data, close/reopen, read from Library/Downloads,
   then reopen again and check exact locator/History. Test a previously retained
   El Matadero and existing Archive/imported EPUB/TXT/CBZ/PDF without clearing data.
5. Deleting a download must preserve Library/status/favorite/tags/History/progress.
   A remote revision must not silently replace an existing retained copy.
6. Toggle catalog-only visibility during pending requests; Gutenberg stays hidden
   initially, saved publications remain accessible and late hidden results stay
   retired. Temporary source outages must not change capabilities.
7. Repeat es/en/ca UI, themes, narrow screens, fonts 150/180%, keyboard/TalkBack.

Catalog search is general; rights patterns, project markup, export availability,
format/parser subset and sample coverage remain limited. There is no wholesale
wiki index, reliable universal completeness detector, all-author bibliography,
whole-transclusion revision snapshot, arbitrary external resource download or
universal territorial clearance. No Android frame-time or physical acceptance
claim follows from JVM probes. Keep Draft until review and physical validation.

### Adding an edition

Verify official siteinfo/namespaces, copyright semantics, search and a real
authorized export. Add an internally fixed `WikisourceEdition`, localized label
and namespace/rights fixtures. Reuse the same source/catalog/manager; no remote
host configuration. Test identifiers, Unicode, actual reader/offline persistence,
negative rights/unsafe files, state/visibility and all platforms before declaring
its reader capability. Add general rights rules only with policy evidence and
negative tests, never a title/revision allowlist.

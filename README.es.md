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

El proyecto está en una fase muy temprana. La aplicación de escritorio ahora busca
en el catálogo OPDS oficial de Project Gutenberg y muestra resultados reales,
autores e idiomas cuando la fuente los proporciona. La búsqueda se envía mediante
una acción explícita; la siguiente página solo se solicita al pulsar **Next page**.
No hay búsqueda automática ni precarga.

Los detalles de publicaciones, la adquisición de recursos, la caché y la lectura
**todavía no están implementados**. Este slice de búsqueda no completa v0.0.1.
Se mantienen los contratos de dominio puros, acceso limitado a recursos, identidad
con revisión de contenido y documentación arquitectónica.

El objetivo deliberadamente pequeño de v0.0.1 es: abrir INFINILECT → buscar un libro
→ obtener resultados reales → abrir uno → leerlo. Project Gutenberg/OPDS será la primera fuente.
Escritorio es el primer destino ejecutable; Android e iOS son destinos futuros,
no compilaciones actualmente soportadas.

## Compilar y ejecutar

Instala JDK 21. El wrapper Gradle incluido descarga Gradle en el primer uso;
las dependencias requieren acceso a Internet.

```sh
./gradlew :core:jvmTest :app:desktopTest
./gradlew build
./gradlew :app:run
```

En Windows utiliza `gradlew.bat`. La ejecución requiere un escritorio gráfico
y conexión a Internet para buscar.
Todavía no se incluyen instaladores nativos ni aplicaciones móviles.

Versiones: Kotlin/compilador Compose 2.4.20, Compose Multiplatform 1.12.1,
Gradle 9.7.1. Consulta las [referencias oficiales de compatibilidad](docs/TOOLCHAIN.md)
y los [avisos de terceros](THIRD_PARTY_NOTICES.md).

El transporte Gutenberg utiliza Ktor 3.6.0 fuera de core. El parser de escritorio
usa StAX incluido en JDK 21, con acceso XML externo deshabilitado. Consulta los
[endpoints y límites de la fuente](docs/SOURCES.md). Los tests sin conexión utilizan
fixtures OPDS pequeñas de autoría propia y Ktor MockEngine. Una comprobación opcional
de una página real, independiente de tests/build y sin interfaz gráfica, es:

```sh
./gradlew :app:gutenbergSearchCheck --args="shakespeare"
```

Los resultados reales de verificación y límites del entorno están en [VERIFICATION.md](docs/VERIFICATION.md).

## Estructura inicial pequeña

- `core`: modelos y contratos en Kotlin puro en `commonMain`; destino JVM para verificar.
- `app`: interfaz/estado de búsqueda Compose compartidos y adaptador/entrada Gutenberg de escritorio; depende de `core`.
  La [migración documentada](docs/adr/0008-platform-entrypoints.md) separará interfaz
  compartida y aplicaciones de escritorio/Android cuando se incorpore Android.
- `docs`: arquitectura, política de fuentes, caché, hoja de ruta y decisiones.

Ktor es el cliente HTTP de la fuente de escritorio, fuera de `core`. SQLDelight se añadirá cuando la
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

GNU General Public License versión 3 únicamente (`GPL-3.0-only`), véase [LICENSE](LICENSE).
La licencia del proyecto no cambia la licencia de las publicaciones de las fuentes.

Copyright © 2026 SirOtter0 and INFINILECT contributors.

# TaskFlow — Memoria técnica

**Módulo:** Programación de Servicios y Procesos · 2.º DAM · Curso 2026-2027
**Grupo:** *[nombres de los integrantes]* **Repositorio:** *[URL]*



## 1. Introducción

TaskFlow es una aplicación de consola en Java 21 que recibe solicitudes de trabajo sobre archivos, las valida,
las organiza en una cola y las ejecuta de forma concurrente con cuatro hilos trabajadores. Registra el resultado o el
error de cada trabajo y, al cerrar, genera un CSV de resultados y un resumen de incidencias.
Una de las operaciones (`ESTADISTICAS_CSV`) se ejecuta en un programa Java independiente lanzado con `ProcessBuilder`,
combinando así programación **multihilo** y **multiproceso**. No se usan librerías externas.

Las tres operaciones son `ANALIZAR_TEXTO` (líneas, palabras, caracteres), `BUSCAR_TEXTO` (apariciones de un término
en uno o varios archivos) y `ESTADISTICAS_CSV` (cantidad, suma, media, mínimo, máximo y registros descartados).

## 2. Arquitectura

La idea central es sencilla: **una cola compartida, varios trabajadores que la consumen y un registro único con todos los trabajos**.

```
 Consola (Main) ──► GestorTrabajos.enviar() ──► validar ──► registro (Map) ──► BlockingQueue (cap. 20)
                                                                                   │ poll()
                                         ┌────────────────┬────────────────┬───────┴────────┐
                                    trabajador-1     trabajador-2     trabajador-3     trabajador-4   (ExecutorService)
                                         │
              ANALIZAR_TEXTO / BUSCAR_TEXTO: se ejecutan en el propio hilo
              ESTADISTICAS_CSV:  LanzadorProceso ──► Semaphore(2) ──► ProcessBuilder ──► java ProcesoEstadisticas (hijo)

 Monitor (ScheduledExecutorService) ── imprime un resumen cada N segundos
```

| Paquete / clase | Responsabilidad |
|---|---|
| `main.Main` | Consola: lee comandos y los pasa al gestor. |
| `motor.GestorTrabajos` | Validación, política de admisión, cola, trabajadores, monitor, balance y cierre. |
| `motor.Configuracion` | Parámetros (trabajadores, capacidad de cola, tiempos...). |
| `modelos.Trabajo` | Datos del trabajo y **única** vía para cambiar su estado. |
| `operaciones.*` | Lógica de cada operación; `ProcesoEstadisticas` es el programa hijo. |
| `gestionFicheros.LanzadorProceso` | Lanza y controla el proceso hijo. |
| `gestionFicheros.GestorCSV` | Lee el CSV de solicitudes; escribe `resultados.csv` e `incidencias.txt`. |
| `pruebas.PruebasTaskFlow` | Pruebas reproducibles. |

Se respetó la estructura de paquetes inicial del repositorio y se añadió únicamente el paquete `motor`.

## 3. Estados de los trabajos

```
 PENDIENTE ──► EN_COLA ──► EJECUTANDO ──► COMPLETADO
     │            │             └───────► FALLIDO
     └────────────┴──► CANCELADO
```

| Transición | Método | Quién la hace |
|---|---|---|
| PENDIENTE → EN_COLA | `marcarEnCola()` | `enviar()`, justo antes de insertar en la cola |
| EN_COLA → EJECUTANDO | `iniciar()` | un trabajador, al sacar el trabajo de la cola |
| EJECUTANDO → COMPLETADO / FALLIDO | `completar()` / `fallar()` | el trabajador que lo ejecuta |
| PENDIENTE o EN_COLA → CANCELADO | `cancelar()` | el usuario (o el cierre forzado) |

**Cómo se evitan los estados inconsistentes.** Todos esos métodos de `Trabajo` son `synchronized` y **comprueban el estado de partida**:
si no es el esperado, no hacen nada y devuelven `false`. De ahí se deduce:

- Un trabajo **cancelado no puede pasar a COMPLETADO**: `iniciar()` solo funciona desde EN_COLA, así que un trabajador que
  saque de la cola un trabajo ya cancelado recibe `false` y lo descarta.
- Un trabajo **no se ejecuta dos veces**: solo un hilo puede hacer la transición EN_COLA → EJECUTANDO.
- Un trabajo **no acaba en dos estados**: los estados finales no tienen salida.
- Solo se puede cancelar lo que aún no ha empezado; cancelar algo en ejecución o ya cancelado se rechaza con un mensaje.

## 4. Concurrencia

**Trabajadores.** `Executors.newFixedThreadPool(4)` crea 4 hilos una sola vez; no se crea un hilo por solicitud. Cada trabajador es un
`Callable<Integer>` que repite: sacar un trabajo de la cola → ejecutarlo. Al terminar devuelve cuántos trabajos procesó y se
recoge con su `Future` en el cierre (así se comprueba, por ejemplo, que 100 solicitudes dieron exactamente 100 procesados).

**Sin espera activa.** Los trabajadores usan `BlockingQueue.poll(200 ms)`: el hilo queda bloqueado (sin consumir CPU) hasta que llega un
trabajo. El tiempo de espera solo sirve para revisar de vez en cuando si el sistema se está cerrando. El semáforo también bloquea, no consulta en bucle.

**Datos compartidos.**

| Dato | Protección |
|---|---|
| Estado, resultado y tiempos de cada `Trabajo` | métodos `synchronized` |
| Registro de trabajos | `ConcurrentHashMap` (`putIfAbsent` evita ids duplicados) |
| Contadores (ocupados, rechazadas, máximos) | `AtomicInteger` |
| Lista de incidencias | `Collections.synchronizedList` |
| Bandera de aceptación | `volatile` + bloque `synchronized(candado)` |

**Cola y política de admisión.** La cola es una `LinkedBlockingQueue` con capacidad configurable (20 por defecto), por lo que **nunca puede superarla**.
Cuando está llena, `enviar()` espera como máximo `admision` ms (10 s por defecto) a que se libere un hueco:

- si se libera, el trabajo se acepta; no se pierde ni se duplica;
- si no, la solicitud se **rechaza con el motivo "cola llena"**, se elimina del registro y **no cuenta como aceptada**.

El bloque `synchronized(candado)` hace atómico "comprobar si se aceptan trabajos + registrar + encolar". Sin él, un trabajo podría insertarse en la cola
justo después de que los trabajadores decidan terminar y quedarse sin procesar.

**Supervisión.** Un `ScheduledExecutorService` imprime cada `resumen` segundos: trabajos en cola, trabajadores ocupados, procesos hijo activos y el contador de cada estado.

## 5. Multiproceso

`ESTADISTICAS_CSV` se resuelve en `operaciones.ProcesoEstadisticas`, un `main` independiente. `LanzadorProceso` lo arranca con:

```
java -Dfile.encoding=UTF-8 ... -cp <carpeta de clases> operaciones.ProcesoEstadisticas <archivo> <columna> <retardoMs>
```

| Aspecto | Decisión |
|---|---|
| **Parámetros** | Argumentos de línea de comandos (archivo y columna). El tercero (`retardoMs`) solo se usa en pruebas. |
| **Resultado** | El hijo escribe **una línea** por la salida estándar: `OK;cantidad=5;suma=81.00;media=16.20;minimo=5.25;maximo=30.25;descartados=2` |
| **Errores** | El hijo escribe el motivo por la salida de error y termina con código ≠ 0: `1` argumentos, `2` archivo ilegible, `3` sin registros válidos. |
| **Lectura** | `redirectErrorStream(true)`: un único flujo que se lee entero cuando el hijo termina (la salida es de pocas líneas, no llena el búfer). |
| **Límite de 2 hijos** | `Semaphore(2)`: el tercer trabajador que lo necesite queda bloqueado hasta que otro hijo termine. |
| **Fallo de arranque** | `ProcessBuilder.start()` lanza `IOException` → el trabajo pasa a FALLIDO ("no se pudo arrancar..."). |
| **Tiempo máximo** | `waitFor(timeout)`; si expira, `destroyForcibly()` y el trabajo pasa a FALLIDO. |
| **Sin hijos residuales** | Un `finally` mata el proceso si sigue vivo (timeout, interrupción o error) y **espera a que muera** antes de liberar el semáforo. |
| **Flujos** | Se cierra la entrada del hijo al arrancar y el lector de su salida con try-with-resources. |

El hijo localiza sus clases mediante la ubicación de `LanzadorProceso` (`getCodeSource()`), por lo que funciona tanto desde `bin/` como desde Eclipse o un jar.

## 6. Gestión de errores y cierre

**Un fallo nunca detiene a los demás.** `ejecutar()` captura cualquier `Exception` y marca el trabajo FALLIDO con el motivo (archivo inexistente, no es UTF-8,
hijo con código de error, tiempo excedido...). Un `finally` garantiza que ningún trabajo se quede en EJECUTANDO.

**Interrupciones.** Nunca se ignoran: si un trabajador es interrumpido durante un cierre forzado, el trabajo pasa a FALLIDO, se restaura la bandera de interrupción
(`Thread.currentThread().interrupt()`) y el hilo termina.

**Política de cierre** (`salir` o fin de la entrada):

1. Se dejan de aceptar solicitudes (las nuevas se rechazan con "el sistema se está cerrando").
2. Los trabajadores **terminan todos los trabajos pendientes** y se paran (`shutdown` + `awaitTermination`).
3. Si pasan más de `cierre` segundos (60 por defecto), los que sigan en cola se **cancelan** y se interrumpe a los trabajadores (`shutdownNow`); los que se estaban ejecutando pasan a FALLIDO.
4. Se recogen los `Future`, se apaga el monitor y se generan `resultados.csv` e `incidencias.txt` (los resultados ya confirmados se conservan siempre).

**Balance obligatorio.** `GestorTrabajos.balance()` comprueba `ACEPTADOS = COMPLETADOS + FALLIDOS + CANCELADOS` y se imprime al cerrar y en cada prueba. Se cumple porque
los trabajos rechazados (por validación o por saturación) nunca llegan a estar en el registro, y los aceptados siempre terminan en un estado final.

## 7. Pruebas

Se ejecutan con `java -cp bin pruebas.PruebasTaskFlow` (desde la raíz del proyecto). Cada prueba imprime entrada, procedimiento, resultado esperado y obtenido.
Los valores esperados de los archivos de `datos/` se calcularon a mano de forma independiente.
El informe completo está en `docs/informe_pruebas.txt`.

| # | Escenario | Qué se comprueba | Resultado |
|---|---|---|---|
| 1 | Ejecución normal | 5 trabajos válidos terminan COMPLETADO con los recuentos exactos | OK |
| 2 | 100 solicitudes | 100 aceptados, 100 completados; los 4 trabajadores procesaron 13+38+37+12 = 100 | OK |
| 3 | Cola saturada | Cola de 5: 13 aceptadas y 27 rechazadas; máximo observado en cola = 5 | OK |
| 4 | Archivos/datos incorrectos | 4 FALLIDO con su motivo; los 2 correctos COMPLETADO | OK |
| 5 | Error del proceso hijo | Códigos de error 2 y 3; timeout de 1 s mata al hijo; 0 hijos vivos | OK |
| 6 | Cancelación pendiente | Los cancelados no tienen hora de inicio (nunca se ejecutaron); cancelar uno en ejecución se rechaza | OK |
| 7 | Límite de recursos | Máximo de hijos simultáneos = 2 (contador interno y muestreo del sistema) | OK |
| 8 | Cierre controlado | Balance correcto, envío posterior rechazado, 0 hilos y 0 hijos vivos, archivos generados | OK |
| 9 | Validación (extra) | 1 solicitud aceptada y 9 rechazadas, cada una por su motivo | OK |

Las pruebas se repitieron varias veces seguidas sin fallos. Los números concretos de las pruebas 2 y 3 varían de una ejecución a otra (dependen del reparto entre hilos
y del momento de las salidas), pero las condiciones que se verifican (suma = 100, cola ≤ capacidad, aceptadas + rechazadas = enviadas) se cumplen siempre.

## 8. Reparto del equipo *(completar)*

| Área | Responsable | Contenido |
|---|---|---|
| Entrada y gestión | *[nombre]* | `Main`, `GestorCSV` (lectura), validación en `enviar()` |
| Motor concurrente | *[nombre]* | `GestorTrabajos` (cola, trabajadores, cierre), `Trabajo` |
| Procesadores y multiproceso | *[nombre]* | `operaciones.*`, `LanzadorProceso` |
| Supervisión y pruebas | *[nombre]* | monitor, exportación, `PruebasTaskFlow`, archivos de datos |

## 9. Decisiones y dificultades

**Decisiones de diseño**

- *Un solo registro de trabajos.* Los contadores por estado se calculan recorriendo el registro en vez de mantener contadores aparte: así no pueden desincronizarse del estado real.
- *Cola con rechazo tras espera, en lugar de bloqueo indefinido.* Así una solicitud nunca congela la consola para siempre y la política es fácil de explicar y de probar.
- *Terminar los pendientes al cerrar.* Es la política que menos trabajo descarta; el cierre forzado solo actúa si se supera el tiempo máximo.
- *Un único flujo para el hijo.* Mezclar salida de error y estándar simplifica el código y evita bloqueos por búferes llenos; el código de salida distingue éxito de error.

**Dificultades encontradas**

- *Condición de carrera al cerrar:* un trabajador podía ver la cola vacía y terminar justo antes de que entrase un último trabajo. Se resolvió haciendo atómico el "comprobar + registrar + encolar" y exigiendo `!aceptando && cola.isEmpty()` para salir.
- *Marcar EN_COLA antes de insertar:* si se marcaba después, un trabajador rápido podía sacar el trabajo todavía PENDIENTE y descartarlo (trabajo perdido).
- *Codificación del hijo:* el proceso hijo escribía los acentos con la codificación del sistema; se fuerza UTF-8 en los argumentos de la JVM hija.
- *Archivo binario de prueba:* al generarlo con `printf` en `sh` se guardó texto literal en lugar de bytes inválidos y la prueba 4 falló; se regeneró con Python. Es un ejemplo de por qué conviene verificar también los datos de prueba.

**Posibles ampliaciones:** reintentos ante fallos, cancelación cooperativa de trabajos en ejecución, prioridades y número de trabajadores modificable.

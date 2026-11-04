package motor;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import gestionFicheros.GestorCSV;
import gestionFicheros.LanzadorProceso;
import modelos.Trabajo;
import modelos.Trabajo.Estado;
import operaciones.AnalizadorTexto;
import operaciones.BuscadorTexto;

/**
 * Motor de TaskFlow: valida solicitudes, las mete en la cola, las ejecutan N trabajadores
 * (ExecutorService), muestra un resumen periódico (ScheduledExecutorService) y cierra de forma ordenada.
 *
 * POLÍTICA DE ADMISIÓN (cola llena): enviar() espera hasta 'admisionMs' a que se libere un hueco.
 *   - Si se libera: el trabajo se acepta (nunca se pierde).
 *   - Si no: la solicitud se RECHAZA ("cola llena"), no cuenta como aceptada y no queda registrada.
 *
 * POLÍTICA DE CIERRE: se dejan de aceptar solicitudes, los trabajadores terminan TODOS los trabajos
 *   pendientes y después se paran. Si tardan más de 'cierreSegundos', los que sigan en cola se
 *   CANCELAN y se interrumpe a los trabajadores (los que estaban ejecutándose pasan a FALLIDO).
 */
public class GestorTrabajos {

    private static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9_-]{1,30}");
    private static final Set<String> OPERACIONES = Set.of("ANALIZAR_TEXTO", "BUSCAR_TEXTO", "ESTADISTICAS_CSV");
    private static final int MAX_PROCESOS_HIJOS = 2;

    private final Configuracion config;
    private final BlockingQueue<Trabajo> cola;
    private final Map<String, Trabajo> registro = new ConcurrentHashMap<>(); // = trabajos ACEPTADOS
    private final Object candado = new Object();       // protege "comprobar cierre + registrar + encolar"
    private volatile boolean aceptando = true;
    private final AtomicBoolean cerrado = new AtomicBoolean(false);

    private final AtomicInteger ocupados = new AtomicInteger();
    private final AtomicInteger rechazadasValidacion = new AtomicInteger();
    private final AtomicInteger rechazadasSaturacion = new AtomicInteger();
    private final AtomicInteger maxCola = new AtomicInteger();
    private final List<String> incidencias = Collections.synchronizedList(new ArrayList<>());

    private final LanzadorProceso lanzador;
    private final ExecutorService trabajadores;
    private final List<Future<Integer>> futuros = new ArrayList<>();
    private final ScheduledExecutorService monitor;
    private volatile List<Integer> procesadosPorTrabajador = List.of();

    public GestorTrabajos(Configuracion config) {
        this.config = config;
        this.cola = new LinkedBlockingQueue<>(config.capacidadCola);
        this.lanzador = new LanzadorProceso(MAX_PROCESOS_HIJOS, config.timeoutProcesoMs);

        // Cada trabajador es un Callable que devuelve cuántos trabajos ha procesado (se lee con el Future).
        this.trabajadores = Executors.newFixedThreadPool(config.trabajadores, fabricaHilos("trabajador-"));
        for (int i = 0; i < config.trabajadores; i++) {
            Callable<Integer> trabajador = this::bucleTrabajador;
            futuros.add(trabajadores.submit(trabajador));
        }

        this.monitor = Executors.newSingleThreadScheduledExecutor(fabricaHilos("monitor-"));
        if (config.resumenSegundos > 0) {
            monitor.scheduleAtFixedRate(() -> {
                try {
                    // Solo se muestra si hay actividad, para no llenar la consola cuando no hay trabajos.
                    if (cola.size() > 0 || ocupados.get() > 0) System.out.println("\n[RESUMEN] " + resumen());
                } catch (RuntimeException e) {
                    System.err.println("Error en el monitor: " + e);
                }
            }, config.resumenSegundos, config.resumenSegundos, TimeUnit.SECONDS);
        }
    }

    private static ThreadFactory fabricaHilos(String prefijo) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> new Thread(r, prefijo + n.getAndIncrement());
    }

    // =====================================================================
    //  ENTRADA: validación y admisión
    // =====================================================================

    /** Valida y registra una solicitud "id;operacion;ruta;parametros". Devuelve ACEPTADO... o RECHAZADO... */
    public String enviar(String linea) {
        String[] c = linea.split(";", -1);
        String error = validar(c);
        if (error != null) return rechazarValidacion(linea, error);

        String id = c[0].trim();
        Trabajo t = new Trabajo(id, c[1].trim().toUpperCase(), c[2].trim(), c.length > 3 ? c[3].trim() : "");

        synchronized (candado) {
            if (!aceptando) return rechazarValidacion(linea, "el sistema se está cerrando");
            if (registro.putIfAbsent(id, t) != null) return rechazarValidacion(linea, "identificador duplicado: " + id);

            // Se marca EN_COLA ANTES de insertarlo: así un trabajador que lo coja enseguida lo ve ya EN_COLA.
            if (t.marcarEnCola()) {
                try {
                    if (!cola.offer(t, config.admisionMs, TimeUnit.MILLISECONDS)) {
                        return rechazarSaturacion(t, linea, "cola llena (capacidad " + config.capacidadCola + ")");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return rechazarSaturacion(t, linea, "interrumpido mientras esperaba hueco en la cola");
                }
                maxCola.accumulateAndGet(cola.size(), Math::max);
            }
        }
        return "ACEPTADO " + id;
    }

    private String validar(String[] c) {
        if (c.length < 3 || c.length > 4) return "formato incorrecto (se esperan: id;operacion;ruta;parametros)";
        String id = c[0].trim(), op = c[1].trim().toUpperCase(), ruta = c[2].trim();
        String param = c.length > 3 ? c[3].trim() : "";

        if (!ID_VALIDO.matcher(id).matches()) return "identificador no válido (solo letras, números, '_' y '-', máx. 30)";
        if (!OPERACIONES.contains(op)) return "operación desconocida: '" + c[1].trim() + "'";
        if (ruta.isEmpty()) return "falta la ruta del archivo";
        if (op.equals("BUSCAR_TEXTO") && param.isEmpty()) return "BUSCAR_TEXTO necesita el término a buscar";
        if (op.equals("ESTADISTICAS_CSV") && !param.isEmpty()) {
            try {
                if (Integer.parseInt(param) < 1) return "la columna debe ser un número >= 1";
            } catch (NumberFormatException e) {
                return "la columna debe ser un número entero: '" + param + "'";
            }
        }
        return null;
    }

    private String rechazarValidacion(String linea, String motivo) {
        rechazadasValidacion.incrementAndGet();
        incidencias.add("RECHAZADA (validación): " + linea + " -> " + motivo);
        return "RECHAZADO: " + motivo;
    }

    // Se llama dentro del bloque synchronized(candado).
    private String rechazarSaturacion(Trabajo t, String linea, String motivo) {
        registro.remove(t.getId());      // no llegó a ser un trabajo aceptado
        t.cancelar(motivo);
        rechazadasSaturacion.incrementAndGet();
        incidencias.add("RECHAZADA (saturación): " + linea + " -> " + motivo);
        return "RECHAZADO: " + motivo;
    }

    // =====================================================================
    //  TRABAJADORES
    // =====================================================================

    /** Bucle de cada trabajador: coge trabajos de la cola (bloqueado, sin espera activa) hasta el cierre. */
    private Integer bucleTrabajador() {
        int procesados = 0;
        try {
            while (true) {
                Trabajo t = cola.poll(200, TimeUnit.MILLISECONDS);
                if (t == null) {
                    // Cola vacía: solo se sale si ya no se aceptan trabajos y no queda ninguno.
                    if (!aceptando && cola.isEmpty()) break;
                    continue;
                }
                if (!t.iniciar()) continue; // estaba CANCELADO: no se ejecuta
                ocupados.incrementAndGet();
                evento("[INICIO] " + t.getId() + " " + t.getTipoOperacion() + " (" + Thread.currentThread().getName() + ")");
                try {
                    ejecutar(t);
                } finally {
                    ocupados.decrementAndGet();
                }
                evento("[FIN]    " + t.getId() + " " + t.getEstadoActual() + " en " + t.getDuracionMs() + " ms -> " + t.getResultado());
                procesados++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // cierre forzado
        }
        return procesados;
    }

    private void ejecutar(Trabajo t) {
        try {
            String resultado = switch (t.getTipoOperacion()) {
                case "ANALIZAR_TEXTO" -> {
                    pausaDeDemostracion();
                    yield AnalizadorTexto.analizar(Path.of(t.getRutaArchivo()));
                }
                case "BUSCAR_TEXTO" -> {
                    pausaDeDemostracion();
                    List<Path> archivos = new ArrayList<>();
                    for (String r : t.getRutaArchivo().split("\\|")) {
                        if (!r.isBlank()) archivos.add(Path.of(r.trim()));
                    }
                    yield BuscadorTexto.buscar(archivos, t.getParametroAdicional());
                }
                case "ESTADISTICAS_CSV" -> {
                    int columna = t.getParametroAdicional().isEmpty() ? 1 : Integer.parseInt(t.getParametroAdicional());
                    yield lanzador.ejecutar(t.getRutaArchivo(), columna, config.retardoMs);
                }
                default -> throw new IllegalStateException("operación desconocida: " + t.getTipoOperacion());
            };
            t.completar(resultado);
        } catch (InterruptedException e) {
            t.fallar("interrumpido durante el cierre forzado");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            t.fallar(describir(e, t));
        } finally {
            t.fallar("la ejecución terminó sin resultado"); // red de seguridad: nunca queda EJECUTANDO
            if (t.getEstadoActual() == Estado.FALLIDO) {
                incidencias.add("FALLIDO " + t.getId() + " (" + t.getTipoOperacion() + "): " + t.getResultado());
            }
        }
    }

    private void evento(String mensaje) {
        if (config.mostrarEventos) System.out.println("\n" + mensaje);
    }

    /** Classpath que se le pasa al proceso hijo (útil para diagnosticar problemas de arranque). */
    public String getClasspathHijo() { return lanzador.getClasspath(); }

    private void pausaDeDemostracion() throws InterruptedException {
        if (config.retardoMs > 0) Thread.sleep(config.retardoMs);
    }

    private static String describir(Exception e, Trabajo t) {
        if (e instanceof NoSuchFileException) return "archivo no encontrado: " + e.getMessage();
        if (e instanceof CharacterCodingException) return "el archivo no es texto UTF-8 válido: " + t.getRutaArchivo();
        if (e instanceof AccessDeniedException) return "acceso denegado: " + e.getMessage();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // =====================================================================
    //  CONSULTAS Y CANCELACIÓN
    // =====================================================================

    public String cancelar(String id) {
        Trabajo t = registro.get(id);
        if (t == null) return "No existe ningún trabajo aceptado con id '" + id + "'";
        if (t.cancelar("cancelado por el usuario antes de ejecutarse")) {
            cola.remove(t); // libera el hueco (si un trabajador lo cogió a la vez, iniciar() fallará y lo descartará)
            incidencias.add("CANCELADO " + id + " por el usuario");
            return "Trabajo " + id + " cancelado.";
        }
        return "No se puede cancelar " + id + ": su estado es " + t.getEstadoActual()
                + " (solo se cancelan PENDIENTE o EN_COLA).";
    }

    public String estado(String id) {
        Trabajo t = registro.get(id);
        return t == null ? "No existe ningún trabajo aceptado con id '" + id + "'" : t.toString();
    }

    public int contar(Estado estado) {
        int n = 0;
        for (Trabajo t : registro.values()) if (t.getEstadoActual() == estado) n++;
        return n;
    }

    /** Una línea con el estado del sistema (la usa el monitor periódico). */
    public String resumen() {
        StringBuilder sb = new StringBuilder();
        sb.append("en_cola=").append(cola.size())
          .append(" | trabajadores_ocupados=").append(ocupados.get()).append('/').append(config.trabajadores)
          .append(" | procesos_hijo_activos=").append(lanzador.getActivos())
          .append(" |");
        for (Estado e : Estado.values()) sb.append(' ').append(e).append('=').append(contar(e));
        return sb.toString();
    }

    public String estadisticas() {
        Map<Estado, Integer> cuentas = new EnumMap<>(Estado.class);
        for (Estado e : Estado.values()) cuentas.put(e, contar(e));
        return "--- Estadísticas ---\n"
                + "Solicitudes aceptadas:            " + registro.size() + "\n"
                + "Solicitudes rechazadas (validación): " + rechazadasValidacion.get() + "\n"
                + "Solicitudes rechazadas (cola llena): " + rechazadasSaturacion.get() + "\n"
                + "Por estado:                       " + cuentas + "\n"
                + "En cola ahora / máximo observado: " + cola.size() + " / " + maxCola.get()
                + " (capacidad " + config.capacidadCola + ")\n"
                + "Trabajadores ocupados:            " + ocupados.get() + "/" + config.trabajadores + "\n"
                + "Procesos hijo lanzados / máx. simultáneos: " + lanzador.getLanzados() + " / "
                + lanzador.getMaximoSimultaneos() + " (límite " + MAX_PROCESOS_HIJOS + ")";
    }

    public List<Trabajo> getTrabajos() {
        List<Trabajo> lista = new ArrayList<>(registro.values());
        lista.sort(Comparator.comparingLong(Trabajo::getOrden));
        return lista;
    }

    // =====================================================================
    //  BALANCE Y CIERRE
    // =====================================================================

    /** ACEPTADOS = COMPLETADOS + FALLIDOS + CANCELADOS (y ninguno en estado intermedio). */
    public boolean balanceCorrecto() {
        int aceptados = registro.size();
        int finales = contar(Estado.COMPLETADO) + contar(Estado.FALLIDO) + contar(Estado.CANCELADO);
        return aceptados == finales;
    }

    public String balance() {
        int a = registro.size(), c = contar(Estado.COMPLETADO), f = contar(Estado.FALLIDO), x = contar(Estado.CANCELADO);
        return String.format("BALANCE: aceptados=%d | completados=%d + fallidos=%d + cancelados=%d = %d -> %s",
                a, c, f, x, c + f + x, balanceCorrecto() ? "CORRECTO" : "INCORRECTO");
    }

    /** Escribe resultados.csv e incidencias.txt en la carpeta de salida. */
    public String exportar() throws IOException {
        Path dir = Path.of(config.dirSalida);
        GestorCSV.escribirResultados(dir.resolve("resultados.csv"), getTrabajos());
        List<String> lineas = new ArrayList<>();
        lineas.add("RESUMEN DE INCIDENCIAS");
        lineas.add(balance());
        lineas.add("Rechazadas por validación: " + rechazadasValidacion.get()
                + " | Rechazadas por cola llena: " + rechazadasSaturacion.get());
        lineas.add("---");
        synchronized (incidencias) {
            lineas.addAll(incidencias);
        }
        GestorCSV.escribirIncidencias(dir.resolve("incidencias.txt"), lineas);
        return "Archivos generados en " + dir.toAbsolutePath() + " (resultados.csv, incidencias.txt)";
    }

    public boolean estaCerrado() { return cerrado.get(); }
    public List<Integer> getProcesadosPorTrabajador() { return procesadosPorTrabajador; }
    public int getAceptados() { return registro.size(); }
    public int getRechazadasValidacion() { return rechazadasValidacion.get(); }
    public int getRechazadasSaturacion() { return rechazadasSaturacion.get(); }
    public int getMaxCola() { return maxCola.get(); }
    public int getMaxProcesosHijoSimultaneos() { return lanzador.getMaximoSimultaneos(); }
    public Trabajo getTrabajo(String id) { return registro.get(id); }

    /** Cierre ordenado. Se puede llamar varias veces; solo la primera hace algo. */
    public void cerrar() {
        if (!cerrado.compareAndSet(false, true)) return;

        synchronized (candado) { // espera a que termine cualquier enviar() en curso
            aceptando = false;
        }
        trabajadores.shutdown();
        boolean terminado = false;
        try {
            terminado = trabajadores.awaitTermination(config.cierreSegundos, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!terminado) {
            List<Trabajo> restantes = new ArrayList<>();
            cola.drainTo(restantes);
            for (Trabajo t : restantes) {
                if (t.cancelar("cancelado por cierre forzado")) incidencias.add("CANCELADO " + t.getId() + " por cierre forzado");
            }
            trabajadores.shutdownNow(); // interrumpe a los trabajadores
            try {
                trabajadores.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Future: recoger cuántos trabajos procesó cada trabajador.
        List<Integer> cuentas = new ArrayList<>();
        for (Future<Integer> f : futuros) {
            try {
                cuentas.add(f.get(5, TimeUnit.SECONDS));
            } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                incidencias.add("ERROR en un trabajador: " + e);
                cuentas.add(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cuentas.add(0);
            }
        }
        procesadosPorTrabajador = List.copyOf(cuentas);

        monitor.shutdownNow();
        try {
            monitor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

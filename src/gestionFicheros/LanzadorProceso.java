package gestionFicheros;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lanza el programa hijo (operaciones.ProcesoEstadisticas) con ProcessBuilder.
 *
 * - PARÁMETROS: se pasan como argumentos de la línea de comandos (archivo, columna, retardo).
 * - RESULTADOS: el hijo escribe una línea "OK;..." por su salida estándar.
 *   La salida de error se mezcla con la estándar (redirectErrorStream) para leer un único flujo.
 * - ERRORES: fallo al arrancar (IOException), código de salida distinto de 0 o tiempo máximo superado.
 * - LÍMITE: un Semaphore impide que haya más de 'maxProcesos' hijos a la vez.
 */
public class LanzadorProceso {

    private final Semaphore permisos;
    private final long timeoutMs;
    private final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private final String clases;

    private final AtomicInteger activos = new AtomicInteger();
    private final AtomicInteger maximoSimultaneos = new AtomicInteger();
    private final AtomicInteger lanzados = new AtomicInteger();

    public LanzadorProceso(int maxProcesos, long timeoutMs) {
        this.permisos = new Semaphore(maxProcesos);
        this.timeoutMs = timeoutMs;
        try {
            // Carpeta (o jar) donde están las clases de TaskFlow, para que el hijo las encuentre.
            this.clases = Path.of(LanzadorProceso.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("No se pudo localizar el directorio de clases", e);
        }
    }

    public int getMaximoSimultaneos() { return maximoSimultaneos.get(); }
    public int getLanzados() { return lanzados.get(); }
    public int getActivos() { return activos.get(); }

    /** Ejecuta el proceso hijo y devuelve su línea de resultado. Lanza IOException si algo falla. */
    public String ejecutar(String archivo, int columna, long retardoMs) throws IOException, InterruptedException {
        permisos.acquire(); // espera (bloqueada, sin consumir CPU) si ya hay 2 hijos
        try {
            return lanzar(archivo, columna, retardoMs);
        } finally {
            permisos.release();
        }
    }

    private String lanzar(String archivo, int columna, long retardoMs) throws IOException, InterruptedException {
        Process proceso = null;
        int ahora = activos.incrementAndGet();
        maximoSimultaneos.accumulateAndGet(ahora, Math::max);
        try {
            ProcessBuilder pb = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", clases, "operaciones.ProcesoEstadisticas",
                    archivo, String.valueOf(columna), String.valueOf(retardoMs));
            pb.redirectErrorStream(true);
            try {
                proceso = pb.start();
            } catch (IOException e) {
                throw new IOException("no se pudo arrancar el proceso hijo: " + e.getMessage(), e);
            }
            lanzados.incrementAndGet();
            proceso.getOutputStream().close(); // el hijo no necesita entrada

            if (!proceso.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("tiempo máximo superado (" + timeoutMs + " ms): proceso hijo finalizado a la fuerza");
            }

            String salida;
            try (BufferedReader lector = new BufferedReader(
                    new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
                salida = String.join(" | ", lector.lines().toList());
            }
            int codigo = proceso.exitValue();
            if (codigo != 0) {
                throw new IOException("el proceso hijo terminó con código " + codigo + ": " + salida);
            }
            if (!salida.startsWith("OK;")) {
                throw new IOException("respuesta inesperada del proceso hijo: " + salida);
            }
            return salida.substring(3);
        } finally {
            // Garantía: no queda ningún hijo vivo (timeout, interrupción o error).
            if (proceso != null && proceso.isAlive()) {
                proceso.destroyForcibly();
                try {
                    proceso.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            activos.decrementAndGet();
        }
    }
}

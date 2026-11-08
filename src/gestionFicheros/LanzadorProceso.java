package gestionFicheros;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        this.clases = localizarClases();
    }

   
    private static String localizarClases() {
        Set<String> candidatos = new LinkedHashSet<>();
        try {
            CodeSource origen = LanzadorProceso.class.getProtectionDomain().getCodeSource();
            if (origen != null) candidatos.add(Path.of(origen.getLocation().toURI()).toString());
        } catch (URISyntaxException | RuntimeException e) {
            
        }
        for (String propiedad : new String[] {"java.class.path", "jdk.module.path"}) {
            String valor = System.getProperty(propiedad);
            if (valor != null) {
                for (String ruta : valor.split(File.pathSeparator)) {
                    if (!ruta.isBlank()) candidatos.add(Path.of(ruta).toAbsolutePath().toString());
                }
            }
        }
        candidatos.add(Path.of("bin").toAbsolutePath().toString());

        List<String> validos = new ArrayList<>();
        for (String c : candidatos) {
            Path ruta = Path.of(c);
            boolean sirve = Files.isDirectory(ruta)
                    ? Files.exists(ruta.resolve("operaciones").resolve("ProcesoEstadisticas.class"))
                    : Files.isRegularFile(ruta); // un .jar
            if (sirve) validos.add(c);
        }
        if (validos.isEmpty()) {
            throw new IllegalStateException("No se encuentran las clases compiladas de TaskFlow (operaciones/ProcesoEstadisticas.class). "
                    + "Compile el proyecto y ejecute desde la raíz. Rutas probadas: " + candidatos);
        }
        return String.join(File.pathSeparator, validos);
    }

    public String getClasspath() { return clases; }

    public int getMaximoSimultaneos() { return maximoSimultaneos.get(); }
    public int getLanzados() { return lanzados.get(); }
    public int getActivos() { return activos.get(); }

    
    public String ejecutar(String archivo, int columna, long retardoMs) throws IOException, InterruptedException {
        permisos.acquire(); 
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
            proceso.getOutputStream().close(); 

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

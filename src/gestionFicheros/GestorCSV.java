package gestionFicheros;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import modelos.Trabajo;

/**
 * Lectura del CSV de solicitudes y escritura de los archivos de salida.
 *
 * FORMATO DEL CSV DE SOLICITUDES (separador ';'):
 *     id;operacion;ruta;parametros
 *   - Las líneas vacías y las que empiezan por '#' se ignoran.
 *   - La línea de cabecera "id;operacion;..." (si existe) se ignora.
 *   - ruta: un archivo; en BUSCAR_TEXTO pueden ser varios separados por '|'.
 *   - parametros: BUSCAR_TEXTO = término a buscar; ESTADISTICAS_CSV = nº de columna (por defecto 1);
 *                 ANALIZAR_TEXTO = sin parámetros.
 */
public class GestorCSV {

    public static List<String> leerSolicitudes(Path archivo) throws IOException {
        List<String> lineas = new ArrayList<>();
        boolean primera = true;
        try (BufferedReader lector = Files.newBufferedReader(archivo)) {
            String linea;
            while ((linea = lector.readLine()) != null) {
                String t = linea.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (primera && t.toLowerCase().startsWith("id;")) { primera = false; continue; }
                primera = false;
                lineas.add(t);
            }
        }
        return lineas;
    }

    /** resultados.csv: id;operacion;estado;duracion_ms;resultado_o_error */
    public static void escribirResultados(Path destino, Collection<Trabajo> trabajos) throws IOException {
        crearCarpeta(destino);
        try (BufferedWriter w = Files.newBufferedWriter(destino)) {
            w.write("id;operacion;estado;duracion_ms;resultado_o_error");
            w.newLine();
            for (Trabajo t : trabajos) {
                w.write(String.join(";", t.getId(), t.getTipoOperacion(), t.getEstadoActual().name(),
                        String.valueOf(t.getDuracionMs()), limpiar(t.getResultado())));
                w.newLine();
            }
        }
    }

    public static void escribirIncidencias(Path destino, List<String> lineas) throws IOException {
        crearCarpeta(destino);
        Files.write(destino, lineas);
    }

    private static void crearCarpeta(Path archivo) throws IOException {
        Path padre = archivo.toAbsolutePath().getParent();
        if (padre != null) Files.createDirectories(padre);
    }

    // El texto del resultado no puede romper el CSV.
    private static String limpiar(String texto) {
        return texto.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}

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

    
    private static String limpiar(String texto) {
        return texto.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}

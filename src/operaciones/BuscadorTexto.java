package operaciones;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * BUSCAR_TEXTO: cuenta las apariciones de un término literal en uno o varios archivos.
 *
 * Criterios:
 *  - Búsqueda literal (no es una expresión regular) y distingue mayúsculas de minúsculas.
 *  - Se busca dentro de cada línea (un término no puede ocupar dos líneas).
 *  - Las apariciones no se solapan: "aa" en "aaaa" cuenta 2.
 *  - Si algún archivo no se puede leer, falla todo el trabajo.
 */
public class BuscadorTexto {

    public static String buscar(List<Path> archivos, String termino) throws IOException {
        if (termino == null || termino.isEmpty()) {
            throw new IllegalArgumentException("el término de búsqueda está vacío");
        }
        StringBuilder sb = new StringBuilder();
        long total = 0;
        for (Path archivo : archivos) {
            long cuenta = contar(archivo, termino);
            total += cuenta;
            sb.append(archivo.getFileName()).append('=').append(cuenta).append(", ");
        }
        return sb.append("total=").append(total).toString();
    }

    private static long contar(Path archivo, String termino) throws IOException {
        long cuenta = 0;
        try (BufferedReader lector = Files.newBufferedReader(archivo)) {
            String linea;
            while ((linea = lector.readLine()) != null) {
                int pos = linea.indexOf(termino);
                while (pos >= 0) {
                    cuenta++;
                    pos = linea.indexOf(termino, pos + termino.length());
                }
            }
        }
        return cuenta;
    }
}

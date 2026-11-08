package operaciones;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

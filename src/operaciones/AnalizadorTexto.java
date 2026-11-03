package operaciones;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ANALIZAR_TEXTO: cuenta líneas, palabras y caracteres.
 *
 * Criterios:
 *  - Archivo en UTF-8 (si no lo es, se lanza un error).
 *  - Línea: cada línea que devuelve readLine() (la última sin salto de línea también cuenta).
 *  - Palabra: cualquier grupo de caracteres sin espacios en blanco (espacio, tabulador...).
 *  - Carácter: todos los caracteres de las líneas, incluidos los espacios, SIN los saltos de línea.
 */
public class AnalizadorTexto {

    public static String analizar(Path archivo) throws IOException {
        long inicio = System.nanoTime();
        long lineas = 0, palabras = 0, caracteres = 0;

        try (BufferedReader lector = Files.newBufferedReader(archivo)) {
            String linea;
            while ((linea = lector.readLine()) != null) {
                lineas++;
                caracteres += linea.codePointCount(0, linea.length());
                String limpia = linea.trim();
                if (!limpia.isEmpty()) {
                    palabras += limpia.split("\\s+").length;
                }
            }
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        return String.format("lineas=%d palabras=%d caracteres=%d tiempo=%dms", lineas, palabras, caracteres, ms);
    }
}

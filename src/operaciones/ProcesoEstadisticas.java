package operaciones;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.charset.CharacterCodingException;
import java.util.Locale;

public class ProcesoEstadisticas {

    private static final String FORMATO_NUMERO = "[-+]?\\d+(\\.\\d+)?";

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: ProcesoEstadisticas <archivo.csv> <columna> [retardoMs]");
            System.exit(1);
        }
        int columna;
        long retardo = 0;
        try {
            columna = Integer.parseInt(args[1]);
            if (args.length > 2) retardo = Long.parseLong(args[2]);
            if (columna < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Columna o retardo no válidos");
            System.exit(1);
            return;
        }

        try {
            if (retardo > 0) Thread.sleep(retardo); 
            System.out.println(calcular(Path.of(args[0]), columna));
        } catch (NoSuchFileException e) {
            System.err.println("archivo no encontrado: " + e.getMessage());
            System.exit(2);
        } catch (CharacterCodingException e) {
            System.err.println("el archivo no es texto UTF-8 válido");
            System.exit(2);
        } catch (IOException e) {
            System.err.println("error de lectura: " + e.getMessage());
            System.exit(2);
        } catch (SinRegistros e) {
            System.err.println(e.getMessage());
            System.exit(3);
        } catch (InterruptedException e) {
            System.err.println("proceso hijo interrumpido");
            System.exit(1);
        }
    }

    private static class SinRegistros extends Exception {
        SinRegistros(String mensaje) { super(mensaje); }
    }

    static String calcular(Path archivo, int columna) throws IOException, SinRegistros {
        long cantidad = 0, descartados = 0;
        double suma = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;

        try (BufferedReader lector = Files.newBufferedReader(archivo)) {
            lector.readLine();
            String linea;
            while ((linea = lector.readLine()) != null) {
                if (linea.isBlank()) continue;
                String[] campos = linea.split(";", -1);
                if (campos.length < columna || !campos[columna - 1].trim().matches(FORMATO_NUMERO)) {
                    descartados++;
                    continue;
                }
                double valor = Double.parseDouble(campos[columna - 1].trim());
                cantidad++;
                suma += valor;
                min = Math.min(min, valor);
                max = Math.max(max, valor);
            }
        }
        if (cantidad == 0) {
            throw new SinRegistros("ningún registro válido en la columna " + columna + " (descartados=" + descartados + ")");
        }
        return String.format(Locale.ROOT, "OK;cantidad=%d;suma=%.2f;media=%.2f;minimo=%.2f;maximo=%.2f;descartados=%d",
                cantidad, suma, suma / cantidad, min, max, descartados);
    }
}

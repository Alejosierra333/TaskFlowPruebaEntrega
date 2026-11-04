package main;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

import gestionFicheros.GestorCSV;
import motor.Configuracion;
import motor.GestorTrabajos;


public class Main {

    public static void main(String[] args) throws IOException {
        Configuracion config;
        try {
            config = Configuracion.desdeArgumentos(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error en los argumentos: " + e.getMessage());
            System.err.println("Opciones: trabajadores=4 cola=20 admision=10000 timeoutProceso=30000 "
                    + "retardo=0 resumen=5 cierre=60 salida=salida");
            return;
        }

        GestorTrabajos gestor = new GestorTrabajos(config);
        System.out.println("TaskFlow listo (" + config.trabajadores + " trabajadores, cola de " + config.capacidadCola
                + "). Escriba 'ayuda' para ver los comandos.");

        BufferedReader entrada = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("taskflow> ");
            String linea = entrada.readLine();
            if (linea == null) break; // fin de la entrada = salir
            linea = linea.trim();
            if (linea.isEmpty()) continue;

            String[] partes = linea.split("\\s+", 2);
            String comando = partes[0].toLowerCase();
            String resto = partes.length > 1 ? partes[1].trim() : "";

            if (comando.equals("salir")) break;
            switch (comando) {
                case "ayuda" -> ayuda();
                case "cargar" -> cargar(gestor, resto);
                case "anadir", "añadir" -> System.out.println(resto.isEmpty() ? "Uso: anadir id;operacion;ruta;parametros" : gestor.enviar(resto));
                case "estado" -> System.out.println(resto.isEmpty() ? "Uso: estado <id>" : gestor.estado(resto));
                case "cancelar" -> System.out.println(resto.isEmpty() ? "Uso: cancelar <id>" : gestor.cancelar(resto));
                case "estadisticas" -> System.out.println(gestor.estadisticas());
                case "listar" -> gestor.getTrabajos().forEach(System.out::println);
                case "exportar" -> System.out.println(gestor.exportar());
                default -> System.out.println("Comando desconocido. Escriba 'ayuda'.");
            }
        }

        System.out.println("Cerrando: se terminarán los trabajos pendientes...");
        gestor.cerrar();
        System.out.println(gestor.exportar());
        System.out.println(gestor.balance());
        System.out.println(gestor.estadisticas());
    }

    private static void cargar(GestorTrabajos gestor, String ruta) {
        if (ruta.isEmpty()) { System.out.println("Uso: cargar <archivo.csv>"); return; }
        try {
            List<String> lineas = GestorCSV.leerSolicitudes(Path.of(ruta));
            int aceptadas = 0, rechazadas = 0;
            for (String l : lineas) {
                String r = gestor.enviar(l);
                if (r.startsWith("ACEPTADO")) {
                    aceptadas++;
                } else {
                    rechazadas++;
                    System.out.println("  " + l + "  ->  " + r);
                }
            }
            System.out.println("Cargadas " + lineas.size() + " solicitudes: " + aceptadas + " aceptadas, " + rechazadas + " rechazadas.");
        } catch (IOException e) {
            System.out.println("No se pudo leer el archivo de solicitudes: " + e);
        }
    }

    private static void ayuda() {
        System.out.println("""
                Comandos:
                  cargar <archivo.csv>              carga solicitudes desde un CSV    (cargar datos/solicitudes_ejemplo.csv)
                  anadir id;operacion;ruta;param    añade una solicitud               (anadir A1;ANALIZAR_TEXTO;datos/texto1.txt;)
                  estado <id>                       muestra el estado de un trabajo   (estado T001)
                  cancelar <id>                     cancela un trabajo que no ha empezado
                  estadisticas                      contadores generales
                  listar                            todos los trabajos
                  exportar                          genera resultados.csv e incidencias.txt
                  salir                             cierre ordenado y balance final
                Operaciones: ANALIZAR_TEXTO, BUSCAR_TEXTO (param = término), ESTADISTICAS_CSV (param = nº de columna)""");
    }
}

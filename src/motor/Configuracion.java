package motor;

/** Parámetros de TaskFlow. Se pueden cambiar al arrancar con argumentos clave=valor. */
public class Configuracion {
    public int trabajadores = 4;            // hilos trabajadores
    public int capacidadCola = 20;          // capacidad de la BlockingQueue
    public long admisionMs = 10_000;        // espera máxima por un hueco si la cola está llena
    public long timeoutProcesoMs = 30_000;  // tiempo máximo de un proceso hijo
    public long retardoMs = 0;              // pausa artificial por trabajo (solo para pruebas/demos)
    public int resumenSegundos = 5;         // cada cuánto se imprime el resumen (0 = desactivado)
    public int cierreSegundos = 60;         // tiempo máximo de espera al cerrar
    public String dirSalida = "salida";     // carpeta de resultados.csv e incidencias.txt
    public boolean mostrarEventos = false;  // imprime [INICIO] y [FIN] de cada trabajo (la consola lo activa)

    public static Configuracion desdeArgumentos(String[] args) {
        Configuracion c = new Configuracion();
        c.mostrarEventos = true; // en la consola interesa ver qué hace cada trabajador
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("Argumento no válido: '" + a + "' (use clave=valor)");
            }
            String v = kv[1].trim();
            switch (kv[0].trim()) {
                case "trabajadores" -> c.trabajadores = Integer.parseInt(v);
                case "cola" -> c.capacidadCola = Integer.parseInt(v);
                case "admision" -> c.admisionMs = Long.parseLong(v);
                case "timeoutProceso" -> c.timeoutProcesoMs = Long.parseLong(v);
                case "retardo" -> c.retardoMs = Long.parseLong(v);
                case "resumen" -> c.resumenSegundos = Integer.parseInt(v);
                case "cierre" -> c.cierreSegundos = Integer.parseInt(v);
                case "salida" -> c.dirSalida = v;
                case "eventos" -> c.mostrarEventos = Boolean.parseBoolean(v);
                default -> throw new IllegalArgumentException("Opción desconocida: '" + kv[0] + "'");
            }
        }
        c.validar();
        return c;
    }

    public void validar() {
        if (trabajadores < 1) throw new IllegalArgumentException("trabajadores debe ser >= 1");
        if (capacidadCola < 1) throw new IllegalArgumentException("cola debe ser >= 1");
        if (admisionMs < 0 || timeoutProcesoMs < 1 || retardoMs < 0 || resumenSegundos < 0 || cierreSegundos < 1) {
            throw new IllegalArgumentException("Valores numéricos fuera de rango");
        }
    }
}

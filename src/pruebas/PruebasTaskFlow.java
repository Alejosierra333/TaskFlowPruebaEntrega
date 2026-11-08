package pruebas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import gestionFicheros.GestorCSV;
import modelos.Trabajo;
import modelos.Trabajo.Estado;
import motor.Configuracion;
import motor.GestorTrabajos;


public class PruebasTaskFlow {

    private static int total = 0, fallos = 0;

    public static void main(String[] args) throws Exception {
        prueba1EjecucionNormal();
        prueba2CienSolicitudes();
        prueba3ColaSaturada();
        prueba4ErrorProcesoHijo();
        prueba5CancelacionPendiente();
        prueba6LimiteProcesosHijo();
        prueba7CierreControlado();
        prueba8Validacion();

        System.out.println("\n==============================================");
        System.out.println("RESUMEN: " + (total - fallos) + " de " + total + " pruebas correctas");
        System.out.println("==============================================");
        System.exit(fallos == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // 1. Ejecución normal
    // ------------------------------------------------------------------
    static void prueba1EjecucionNormal() {
        GestorTrabajos g = nuevo("p1", 0, 20, 10_000, 30_000);
        g.enviar("N1;ANALIZAR_TEXTO;datos/texto1.txt;");
        g.enviar("N2;BUSCAR_TEXTO;datos/texto1.txt|datos/texto2.txt;tarea");
        g.enviar("N3;ESTADISTICAS_CSV;datos/ventas.csv;2");
        g.enviar("N4;BUSCAR_TEXTO;datos/texto2.txt;hilo");
        g.enviar("N5;ESTADISTICAS_CSV;datos/ventas.csv;3");
        g.cerrar();

        boolean ok = g.getAceptados() == 5 && g.contar(Estado.COMPLETADO) == 5 && g.balanceCorrecto()
                && g.getTrabajo("N1").getResultado().startsWith("lineas=4 palabras=21 caracteres=133")
                && g.getTrabajo("N2").getResultado().equals("texto1.txt=4, texto2.txt=0, total=4")
                && g.getTrabajo("N3").getResultado().startsWith("cantidad=5;suma=81.00;media=16.20;minimo=5.25;maximo=30.25;descartados=2")
                && g.getTrabajo("N4").getResultado().equals("texto2.txt=3, total=3")
                && g.getTrabajo("N5").getResultado().startsWith("cantidad=6;suma=17.00");
        informe(1, "Ejecución normal",
                "5 solicitudes válidas (2 ANALIZAR/BUSCAR texto, 1 BUSCAR, 2 ESTADISTICAS_CSV con proceso hijo)",
                "Enviar las solicitudes, cerrar y comparar cada resultado con los valores calculados a mano.",
                "5 trabajos COMPLETADO, con los recuentos exactos (p. ej. N1: 4 líneas, 21 palabras, 133 caracteres; N3: 5 válidos, suma 81.00, 2 descartados).",
                "COMPLETADOS=" + g.contar(Estado.COMPLETADO) + "; N1=[" + g.getTrabajo("N1").getResultado() + "]; N3=["
                        + g.getTrabajo("N3").getResultado() + "]; " + g.balance(), ok);
    }

   
    static void prueba2CienSolicitudes() throws IOException {
        GestorTrabajos g = nuevo("p2", 0, 20, 30_000, 30_000);
        List<String> lineas = GestorCSV.leerSolicitudes(Path.of("datos/solicitudes_100.csv"));
        int aceptadas = 0;
        for (String l : lineas) if (g.enviar(l).startsWith("ACEPTADO")) aceptadas++;
        g.cerrar();

        int procesados = g.getProcesadosPorTrabajador().stream().mapToInt(Integer::intValue).sum();
        boolean ok = lineas.size() == 100 && aceptadas == 100 && g.getAceptados() == 100
                && g.contar(Estado.COMPLETADO) == 100 && procesados == 100 && g.balanceCorrecto()
                && g.getMaxCola() <= 20;
        informe(2, "100 solicitudes",
                "datos/solicitudes_100.csv (50 ANALIZAR_TEXTO, 30 BUSCAR_TEXTO, 20 ESTADISTICAS_CSV)",
                "Cargar las 100 solicitudes con 4 trabajadores y cola de 20; sumar los trabajos que ha procesado cada trabajador (Future).",
                "100 aceptados, 100 COMPLETADO, suma de procesados por los 4 trabajadores = 100 (ninguno perdido ni duplicado).",
                "aceptadas=" + aceptadas + ", COMPLETADO=" + g.contar(Estado.COMPLETADO) + ", procesados por trabajador="
                        + g.getProcesadosPorTrabajador() + " (suma " + procesados + "), máx. cola=" + g.getMaxCola()
                        + ". " + g.balance(), ok);
    }

   
    static void prueba3ColaSaturada() {
        
        GestorTrabajos g = nuevo("p3", 1000, 5, 50, 30_000);
        int aceptadas = 0, rechazadas = 0;
        for (int i = 1; i <= 40; i++) {
            String r = g.enviar(String.format("S%02d;ANALIZAR_TEXTO;datos/texto1.txt;", i));
            if (r.startsWith("ACEPTADO")) aceptadas++; else rechazadas++;
        }
        g.cerrar();

        boolean ok = rechazadas > 0 && aceptadas + rechazadas == 40 && g.getMaxCola() <= 5
                && g.getAceptados() == aceptadas && g.getRechazadasSaturacion() == rechazadas
                && g.contar(Estado.COMPLETADO) == aceptadas && g.balanceCorrecto();
        informe(3, "Cola saturada",
                "40 solicitudes enviadas de golpe; cola de capacidad 5; cada trabajo tarda 1 s; espera máxima por hueco 50 ms",
                "Enviar las 40 y observar cuántas se aceptan/rechazan y el máximo de la cola.",
                "La cola nunca pasa de 5; las que no caben se RECHAZAN (no cuentan como aceptadas); aceptadas + rechazadas = 40; todas las aceptadas terminan.",
                "aceptadas=" + aceptadas + ", rechazadas por cola llena=" + rechazadas + ", máx. cola observado=" + g.getMaxCola()
                        + " (capacidad 5), COMPLETADO=" + g.contar(Estado.COMPLETADO) + ". " + g.balance(), ok);
    }


   
    static void prueba4ErrorProcesoHijo() throws InterruptedException {
       
        GestorTrabajos g = nuevo("p5a", 0, 20, 10_000, 30_000);
        g.enviar("H1;ESTADISTICAS_CSV;datos/no_existe.csv;1");
        g.enviar("H2;ESTADISTICAS_CSV;datos/solo_invalidos.csv;1");
        g.enviar("H3;ESTADISTICAS_CSV;datos/ventas.csv;2");
        g.enviar("H4;ANALIZAR_TEXTO;datos/texto1.txt;");
        g.cerrar();
        boolean okA = g.getTrabajo("H1").getEstadoActual() == Estado.FALLIDO && g.getTrabajo("H1").getResultado().contains("código 2")
                && g.getTrabajo("H2").getEstadoActual() == Estado.FALLIDO && g.getTrabajo("H2").getResultado().contains("código 3")
                && g.getTrabajo("H3").getEstadoActual() == Estado.COMPLETADO
                && g.getTrabajo("H4").getEstadoActual() == Estado.COMPLETADO && g.balanceCorrecto();

        
        GestorTrabajos t = nuevo("p5b", 5000, 20, 10_000, 1000);
        long inicio = System.currentTimeMillis();
        t.enviar("K1;ESTADISTICAS_CSV;datos/ventas.csv;2");
        t.enviar("K2;ESTADISTICAS_CSV;datos/ventas.csv;2");
        t.cerrar();
        long ms = System.currentTimeMillis() - inicio;
        Thread.sleep(200);
        long hijosVivos = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
        boolean okB = t.contar(Estado.FALLIDO) == 2 && t.getTrabajo("K1").getResultado().contains("tiempo máximo")
                && hijosVivos == 0 && ms < 4000 && t.balanceCorrecto();

        informe(4, "Error del proceso hijo",
                "H1 CSV inexistente, H2 CSV sin datos válidos, H3 y H4 correctos; después K1 y K2 con hijo que supera el tiempo máximo (1 s)",
                "Enviar los trabajos; en 5b el hijo duerme 5 s con límite de 1 s. Comprobar estados y que no quedan procesos hijos vivos.",
                "H1 FALLIDO (código 2), H2 FALLIDO (código 3), H3/H4 COMPLETADO; K1/K2 FALLIDO por tiempo máximo en ~1 s y 0 procesos hijos residuales.",
                "5a: H1=[" + g.getTrabajo("H1").getResultado() + "], H2=[" + g.getTrabajo("H2").getResultado() + "], H3="
                        + g.getTrabajo("H3").getEstadoActual() + ", H4=" + g.getTrabajo("H4").getEstadoActual()
                        + " | 5b: K1=[" + t.getTrabajo("K1").getResultado() + "], duración total=" + ms + " ms, hijos vivos=" + hijosVivos,
                okA && okB);
    }

   
    static void prueba5CancelacionPendiente() throws InterruptedException {
        GestorTrabajos g = nuevo("p6", 1500, 20, 10_000, 30_000); 
        for (int i = 1; i <= 4; i++) g.enviar("B" + i + ";ANALIZAR_TEXTO;datos/texto1.txt;"); 
        for (int i = 1; i <= 3; i++) g.enviar("C" + i + ";ANALIZAR_TEXTO;datos/texto1.txt;"); 
        Thread.sleep(300);
        String intentoEnEjecucion = g.cancelar("B1");
        String c2 = g.cancelar("C2");
        String c3 = g.cancelar("C3");
        String repetido = g.cancelar("C2");
        g.cerrar();

        Trabajo t2 = g.getTrabajo("C2"), t3 = g.getTrabajo("C3");
        boolean ok = intentoEnEjecucion.startsWith("No se puede") && c2.contains("cancelado") && c3.contains("cancelado")
                && repetido.startsWith("No se puede")
                && t2.getEstadoActual() == Estado.CANCELADO && t2.getTiempoInicio() == null
                && t3.getEstadoActual() == Estado.CANCELADO && t3.getTiempoInicio() == null
                && g.getTrabajo("C1").getEstadoActual() == Estado.COMPLETADO
                && g.contar(Estado.COMPLETADO) == 5 && g.contar(Estado.CANCELADO) == 2 && g.balanceCorrecto();
        informe(5, "Cancelación de un trabajo pendiente",
                "7 trabajos de 1,5 s: B1-B4 ocupan los 4 trabajadores, C1-C3 esperan en cola. Se cancelan C2 y C3 (y se intenta cancelar B1, que ya se ejecuta)",
                "Enviar, esperar 300 ms, cancelar, cerrar y revisar estados y hora de inicio.",
                "C2 y C3 CANCELADO sin hora de inicio (nunca se ejecutaron); B1 no se puede cancelar; C1 y B1-B4 COMPLETADO; cancelar dos veces se rechaza.",
                "B1: '" + intentoEnEjecucion + "' | C2: '" + c2 + "' | repetir C2: '" + repetido + "' | inicio de C2=" + t2.getTiempoInicio()
                        + " | COMPLETADO=" + g.contar(Estado.COMPLETADO) + ", CANCELADO=" + g.contar(Estado.CANCELADO) + ". " + g.balance(), ok);
    }

    
    static void prueba6LimiteProcesosHijo() throws InterruptedException {
        GestorTrabajos g = nuevo("p7", 500, 20, 20_000, 30_000); 
        AtomicInteger maximoMedido = new AtomicInteger();
        Thread muestreo = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int n = (int) ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
                    maximoMedido.accumulateAndGet(n, Math::max);
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "muestreo-procesos");
        muestreo.start();

        for (int i = 1; i <= 12; i++) g.enviar("L" + i + ";ESTADISTICAS_CSV;datos/ventas.csv;2");
        g.cerrar();
        muestreo.interrupt();
        muestreo.join();

        boolean ok = g.getMaxProcesosHijoSimultaneos() <= 2 && g.getMaxProcesosHijoSimultaneos() >= 1
                && maximoMedido.get() <= 2 && g.contar(Estado.COMPLETADO) == 12 && g.balanceCorrecto();
        informe(6, "Límite de recursos (máx. 2 procesos hijo)",
                "12 trabajos ESTADISTICAS_CSV con 4 trabajadores (los 4 intentan lanzar un hijo a la vez); cada hijo dura >= 0,5 s",
                "Contar los hijos simultáneos de dos formas: contador interno del lanzador y muestreo cada 10 ms de los procesos hijos reales del sistema operativo.",
                "Máximo simultáneo <= 2 en ambas mediciones y los 12 trabajos COMPLETADO.",
                "máx. según el lanzador=" + g.getMaxProcesosHijoSimultaneos() + ", máx. medido en el sistema=" + maximoMedido.get()
                        + ", COMPLETADO=" + g.contar(Estado.COMPLETADO) + ". " + g.balance(), ok);
    }

    
    static void prueba7CierreControlado() throws Exception {
        GestorTrabajos g = nuevo("p8", 200, 20, 10_000, 30_000);
        for (int i = 1; i <= 8; i++) g.enviar("Z" + i + ";ANALIZAR_TEXTO;datos/texto2.txt;");
        for (int i = 9; i <= 15; i++) g.enviar("Z" + i + ";ESTADISTICAS_CSV;datos/ventas.csv;2");
        g.cerrar(); 
        String trasCierre = g.enviar("Z16;ANALIZAR_TEXTO;datos/texto1.txt;");
        String salida = g.exportar();
        Thread.sleep(200);

        List<String> hilosVivos = new ArrayList<>();
        for (Thread h : Thread.getAllStackTraces().keySet()) {
            if (h.isAlive() && (h.getName().startsWith("trabajador-") || h.getName().startsWith("monitor-"))) hilosVivos.add(h.getName());
        }
        long hijosVivos = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
        boolean archivos = Files.exists(Path.of("salida_pruebas/p8/resultados.csv")) && Files.exists(Path.of("salida_pruebas/p8/incidencias.txt"));

        boolean ok = g.estaCerrado() && g.balanceCorrecto() && g.getAceptados() == 15 && g.contar(Estado.COMPLETADO) == 15
                && trasCierre.startsWith("RECHAZADO") && hilosVivos.isEmpty() && hijosVivos == 0 && archivos;
        informe(7, "Cierre controlado",
                "15 trabajos (8 de texto + 7 con proceso hijo) y orden de cierre mientras aún hay trabajos pendientes; después, un envío nuevo",
                "Llamar a cerrar(); comprobar balance, que el envío posterior se rechaza, que no quedan hilos ni procesos hijo, y que se generan resultados.csv e incidencias.txt.",
                "Política: se terminan los pendientes. ACEPTADOS (15) = COMPLETADOS + FALLIDOS + CANCELADOS; envío posterior RECHAZADO; 0 hilos TaskFlow vivos; 0 hijos vivos; archivos generados.",
                g.balance() + " | envío tras el cierre: '" + trasCierre + "' | hilos vivos=" + hilosVivos + " | hijos vivos=" + hijosVivos
                        + " | archivos de salida=" + archivos, ok);
    }

   
    static void prueba8Validacion() throws IOException {
        GestorTrabajos g = nuevo("p9", 0, 20, 10_000, 30_000);
        List<String> lineas = GestorCSV.leerSolicitudes(Path.of("datos/solicitudes_incorrectas.csv"));
        int aceptadas = 0;
        for (String l : lineas) if (g.enviar(l).startsWith("ACEPTADO")) aceptadas++;
        g.cerrar();

        boolean ok = lineas.size() == 10 && aceptadas == 1 && g.getRechazadasValidacion() == 9
                && g.getAceptados() == 1 && g.balanceCorrecto();
        informe(8, "Validación de solicitudes (extra)",
                "datos/solicitudes_incorrectas.csv: 1 válida + 9 incorrectas (id duplicado, operación desconocida, id vacío, falta término, columna no numérica, columna 0, ruta vacía, formato, id con espacio)",
                "Cargar el archivo y contar aceptadas y rechazadas.",
                "1 aceptada y 9 rechazadas; las rechazadas NO cuentan como trabajos aceptados.",
                "líneas=" + lineas.size() + ", aceptadas=" + aceptadas + ", rechazadas=" + g.getRechazadasValidacion() + ". " + g.balance(), ok);
    }

    
    private static GestorTrabajos nuevo(String carpeta, long retardo, int cola, long admision, long timeoutProceso) {
        Configuracion c = new Configuracion();
        c.resumenSegundos = 0;
        c.retardoMs = retardo;
        c.capacidadCola = cola;
        c.admisionMs = admision;
        c.timeoutProcesoMs = timeoutProceso;
        c.dirSalida = "salida_pruebas/" + carpeta;
        return new GestorTrabajos(c);
    }

    private static void informe(int n, String titulo, String entrada, String procedimiento, String esperado, String obtenido, boolean ok) {
        total++;
        if (!ok) fallos++;
        System.out.println("\n=== PRUEBA " + n + ": " + titulo + " ===");
        System.out.println("Entrada:       " + entrada);
        System.out.println("Procedimiento: " + procedimiento);
        System.out.println("Esperado:      " + esperado);
        System.out.println("Obtenido:      " + obtenido);
        System.out.println("RESULTADO:     " + (ok ? "OK" : "FALLO"));
    }
}

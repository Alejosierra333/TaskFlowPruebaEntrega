package modelos;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Un trabajo aceptado por TaskFlow.
 *
 * Los cambios de estado SOLO se hacen con los métodos de abajo (marcarEnCola, iniciar,
 * completar, fallar, cancelar). Todos son "synchronized" y comprueban el estado de partida,
 * así que es imposible que un trabajo termine en dos estados distintos o se ejecute dos veces.
 *
 * Transiciones válidas:
 *   PENDIENTE  -> EN_COLA      (marcarEnCola)
 *   PENDIENTE  -> CANCELADO    (cancelar)
 *   EN_COLA    -> EJECUTANDO   (iniciar)      <- solo lo hace un trabajador
 *   EN_COLA    -> CANCELADO    (cancelar)
 *   EJECUTANDO -> COMPLETADO   (completar)
 *   EJECUTANDO -> FALLIDO      (fallar)
 */
public class Trabajo {

    public enum Estado {
        PENDIENTE, EN_COLA, EJECUTANDO, COMPLETADO, FALLIDO, CANCELADO;

        public boolean esFinal() {
            return this == COMPLETADO || this == FALLIDO || this == CANCELADO;
        }
    }

    private static final AtomicLong SECUENCIA = new AtomicLong();

    private final long orden = SECUENCIA.incrementAndGet(); // para ordenar los resultados
    private final String id;
    private final String tipoOperacion;
    private final String rutaArchivo;
    private final String parametroAdicional;

    private Estado estadoActual = Estado.PENDIENTE;
    private final LocalDateTime tiempoCreacion = LocalDateTime.now();
    private LocalDateTime tiempoInicio;
    private LocalDateTime tiempoFin;
    private String resultado = "";

    public Trabajo(String id, String tipoOperacion, String rutaArchivo, String parametroAdicional) {
        this.id = id;
        this.tipoOperacion = tipoOperacion;
        this.rutaArchivo = rutaArchivo;
        this.parametroAdicional = parametroAdicional;
    }

    // ---- Datos que no cambian ----
    public long getOrden() { return orden; }
    public String getId() { return id; }
    public String getTipoOperacion() { return tipoOperacion; }
    public String getRutaArchivo() { return rutaArchivo; }
    public String getParametroAdicional() { return parametroAdicional; }
    public LocalDateTime getTiempoCreacion() { return tiempoCreacion; }

    // ---- Datos que cambian (siempre leídos con synchronized) ----
    public synchronized Estado getEstadoActual() { return estadoActual; }
    public synchronized String getResultado() { return resultado; }
    public synchronized LocalDateTime getTiempoInicio() { return tiempoInicio; }
    public synchronized LocalDateTime getTiempoFin() { return tiempoFin; }

    /** Duración de la ejecución en milisegundos (0 si nunca se ejecutó). */
    public synchronized long getDuracionMs() {
        if (tiempoInicio == null || tiempoFin == null) return 0;
        return Duration.between(tiempoInicio, tiempoFin).toMillis();
    }

    // ---- Transiciones de estado ----
    // Devuelven true si el cambio se hizo, false si el estado de partida no era el esperado.

    public synchronized boolean marcarEnCola() {
        if (estadoActual != Estado.PENDIENTE) return false;
        estadoActual = Estado.EN_COLA;
        return true;
    }

    public synchronized boolean iniciar() {
        if (estadoActual != Estado.EN_COLA) return false; // p. ej. ya estaba CANCELADO
        estadoActual = Estado.EJECUTANDO;
        tiempoInicio = LocalDateTime.now();
        return true;
    }

    public synchronized boolean completar(String mensaje) {
        if (estadoActual != Estado.EJECUTANDO) return false;
        estadoActual = Estado.COMPLETADO;
        resultado = mensaje == null ? "" : mensaje;
        tiempoFin = LocalDateTime.now();
        return true;
    }

    public synchronized boolean fallar(String error) {
        if (estadoActual != Estado.EJECUTANDO) return false;
        estadoActual = Estado.FALLIDO;
        resultado = error == null ? "" : error;
        tiempoFin = LocalDateTime.now();
        return true;
    }

    /** Solo se puede cancelar un trabajo que todavía NO ha empezado. */
    public synchronized boolean cancelar(String motivo) {
        if (estadoActual != Estado.PENDIENTE && estadoActual != Estado.EN_COLA) return false;
        estadoActual = Estado.CANCELADO;
        resultado = motivo == null ? "" : motivo;
        tiempoFin = LocalDateTime.now();
        return true;
    }

    @Override
    public synchronized String toString() {
        return String.format("Trabajo[ID=%s | Operacion=%s | Archivo=%s | Estado=%s | Duracion=%d ms | Resultado=%s]",
                id, tipoOperacion, rutaArchivo, estadoActual, getDuracionMs(),
                resultado.isEmpty() ? "-" : resultado);
    }
}

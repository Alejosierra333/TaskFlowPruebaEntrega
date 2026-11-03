package modelos;
import java.time.LocalDateTime;


public class Trabajo {

    
    public enum Estado {
        PENDIENTE,
        EN_COLA,
        EJECUTANDO,
        COMPLETADO,
        FALLIDO,
        CANCELADO
    }

    
    private final String id;
    private final String tipoOperacion;
    private final String rutaArchivo;
    private final String parametroAdicional; 
    
    
    private Estado estadoActual;
    private LocalDateTime tiempoCreacion;
    private LocalDateTime tiempoInicio;
    private LocalDateTime tiempoFin;
    private String resultado;

    
    public Trabajo(String id, String tipoOperacion, String rutaArchivo, String parametroAdicional) {
        this.id = id;
        this.tipoOperacion = tipoOperacion;
        this.rutaArchivo = rutaArchivo;
        this.parametroAdicional = parametroAdicional;
        this.estadoActual = Estado.PENDIENTE; 
        this.tiempoCreacion = LocalDateTime.now();
        this.resultado = "";
    }

    
    public String getId() { return id; }
    public String getTipoOperacion() { return tipoOperacion; }
    public String getRutaArchivo() { return rutaArchivo; }
    public String getParametroAdicional() { return parametroAdicional; }
    public LocalDateTime getTiempoCreacion() { return tiempoCreacion; }
    public LocalDateTime getTiempoInicio() { return tiempoInicio; }
    public LocalDateTime getTiempoFin() { return tiempoFin; }

    
    public synchronized Estado getEstadoActual() {
        return estadoActual;
    }

    
    public synchronized String getResultado() {
        return resultado;
    }

   
    public synchronized void cambiarEstado(Estado nuevoEstado, String mensajeResultado) {
        this.estadoActual = nuevoEstado;
        
        if (mensajeResultado != null) {
            this.resultado = mensajeResultado;
        }

        
        if (nuevoEstado == Estado.EJECUTANDO) {
            this.tiempoInicio = LocalDateTime.now();
        } else if (nuevoEstado == Estado.COMPLETADO || nuevoEstado == Estado.FALLIDO || nuevoEstado == Estado.CANCELADO) {
            this.tiempoFin = LocalDateTime.now();
        }
    }

    
    @Override
    public synchronized String toString() {
        return String.format("Trabajo[ID=%s | Tipo=%s | Estado=%s | Resultado=%s]", 
                id, tipoOperacion, estadoActual, resultado.isEmpty() ? "Ninguno" : resultado);
    }
}

# TaskFlow



## Importar el proyecto en Eclipse

1. Descarga o clona el repositorio. Si es un .zip, descomprímelo.
2. En Eclipse: **File > Import... > General > Existing Projects into Workspace**.
3. En "Select root directory" pulsa **Browse** y elige la carpeta del proyecto (la que contiene `src`, `bin` y `datos`).
4. Marca el proyecto y pulsa **Finish**.

Alternativa: **File > Open Projects from File System...**, elige la carpeta del proyecto y pulsa **Finish**.

Si Eclipse no encuentra el JRE, haz clic derecho sobre el proyecto, entra en **Properties > Java Build Path > Libraries** y selecciona un JDK 21.

## Ejecutar

### Desde Eclipse

1. Abre `src/main/Main.java`, haz clic derecho y elige **Run As > Java Application**.
2. Escribe los comandos en la pestaña **Console**, junto al prompt `taskflow>`.

Para ejecutar las pruebas, haz lo mismo con `src/pruebas/PruebasTaskFlow.java`.



## Uso básico

Al arrancar, TaskFlow espera órdenes. Prueba con:

    cargar datos/solicitudes_100.csv
    estadisticas
    listar
    salir

Escribe `ayuda` para ver todos los comandos. Termina siempre con `salir`, para que se haga el cierre ordenado y se generen `salida/resultados.csv` e `salida/incidencias.txt`.
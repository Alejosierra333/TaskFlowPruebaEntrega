#!/bin/sh
# Compila TaskFlow (Java 21). Ejecutar desde la carpeta raíz del proyecto.
rm -rf bin && mkdir bin && javac -d bin $(find src -name "*.java") && echo "Compilado en ./bin"

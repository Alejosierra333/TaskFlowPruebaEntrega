@echo off
rem Compila TaskFlow (Java 21). Ejecutar desde la carpeta raiz del proyecto.
if exist bin rmdir /s /q bin
mkdir bin
dir /s /b src\*.java > fuentes.txt
javac -d bin @fuentes.txt && echo Compilado en bin
del fuentes.txt

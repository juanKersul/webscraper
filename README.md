## Configuración inicial

* Instalar Java 8.
  * Comprobar si lo tienen instalado y si es la versión correcta `$ java -version`
  * En ubuntu, se instala sólo con `sudo apt install openjdk-8-jdk`
  * Comprobar la variable de entorno `$JAVA_HOME`. Instrucciones [acá](https://docs.opsgenie.com/docs/setting-java_home)

* Instalar scala 2
  * https://docs.scala-lang.org/getting-started/index.html

## Ejecución
las paginas web a ser scrapeadas deben cargarse en el archivo subscriptions.json
luego para ejecutar
```bash
$ sbt run
```
los dumps se realizan separados por pagina en la carpeta Dumps, 
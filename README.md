
# questdb-madridJUG

## 1. De donde sale el proyecto? 

QuestDB nacio como proyecto personal en 2014, en el contexto de tener que analizar series 
temporales con una base de datos relacional no especializada.
Este tipo de datos es producido de continuo, en gran cantidad, y mientras esto ocurre, la 
base de datos tiene que dar respuesta rapida a las herramientas que usan estos datos, sin 
detener la ingesta y devolviendo resultados de forma interactiva, en pseudo tiempo real.

Como resultado de la frustracion con las soluciones existentes, en 2018 decidimos entrar en 
produccion con nuestro proyecto, y en 2019 creamos la empresa, porque vimos la oportunidad 
en el mercado. Immediatamente logramos la confianza de Y-Combinator, lo que facilito capital, 
contactos, y ayuda con la transicion de ser simplemente programadores a ser empresarios con
un term sheet.
A partir de ahi nuestra plantilla crecio, enseguida llego la serie A, que resulto en una 
muy buena posicion para nosotros. En la actualidad hemos crecido hasta ser 20, vamos rumbo 
de ser 25, acabamos de hacer la entrega de la version 7.0.1 y pronto anunciaremos 
disponibilidad general del servicio en nuestra plataforma en la nube. La intencion es 
monetizar el servicio para que la companyia perdure en el tiempo y el producto se convierta 
en el estandard de-facto.


## 2. Ejemplo de SQL

```sql
CREATE TABLE trades AS (
    SELECT
        rnd_symbol('EURO', 'USD', 'OTHER') symbol,
        rnd_double() * 50.0 price,
        rnd_double() * 20.0 amount,
        to_timestamp('2023-01-01', 'yyyy-MM-dd') + x * 60 * 100000 timestamp
    FROM long_sequence(500000)
), INDEX(symbol capacity 128) TIMESTAMP(timestamp) PARTITION BY MONTH;
```

Esta tabla tiene una columna `timestamp`, que es la designada para indexar los datos en el 
tiempo, que ademas esta particionada en unidades de un mes. Si vamos al sistema de ficheros 
podemos comprobar que hay 11 nuevas carpetas, cada una conteniendo los ficheros correspondinentes 
a las columnas de la tabla. Esta seria una query valida:

```sql
SELECT * FROM trades
WHERE symbol='EURO' AND price > 49.99 AND amount > 15.0 AND timestamp BETWEEN '2022-12' AND '2023-02';
```

Los datos del final de la tabla son mas valiosos, las particiones nos permiten desechar aquellas
que no son de interes, o son antiguas, asi como acceder a los datos directamente sin necesidad de 
escanear toda la tabla.
 

## 3. Por que Java?

En la city de Londres, Java y C++ son los lenguages estandard, son lingua franca. Ambos son usados
para programar aplicaciones, pero quiza C++ se reserva para cuando realmente hay que sacar velocidad
al hardware, porque es mas afin a este. Java por otro lado parece mas "estructurado" a la hora de 
programar comparado con C++, sus herramientas son muy buenas, hay soluciones documentadas para todo, 
es un lenguage que permite escribir un sistema grande y mantenerlo, y resulta relativamente sencillo 
encontrar programadores.

Java puede ser muy rapido, ya que te deja gestionar la memoria con llamadas de bajo nivel, de
forma que podemos tener estructuras de datos fuera del heap, y podemos pasar un puntero a las mismas
tanto en Java, como en C++, sin necesidad de copias, cruzando la linea a traves de JNI. La de legacion
entre Java y C++ es como 7 ns.

testJavaNativeSum, testJavaHeapSum, testNativeSum.

## 4. Arquitectura

![Arch](images/architecture.png)

Lo que se ve, logicamente dividida en tres partes, la parte que le da acceso a traves de la red, mediante
los protocols PGWire, ILP y HTTP, nuestro paquete Cutlass. Despues esta el motor SQL, nuestro paquete Griffin.
Por ultimo esta el motor de acceso al sistema de ficheros, nuestro paquete Cairo.

### 4.1 Storage Engine

La idea es que nuestros datos en disco tienen la misma representacion que en memoria. Dado un array creado en
C++ y guardado en disco, tendra la misma forma y representacion cuando accedido desde Java. Evitamos cambio
de representacion y conversiones. 
Cada particion esta en su directorio, y en este se encuentra los ficheros de las columnas. Es una base de datos
columnar, pero se accede de una forma similar a una base de datos relacional. Los ficheros de columnas estan
ordenados de acuerdo con la columna timestamp, con lo que acceder a una partition es immediato, y una vez dentro
de la misma podemos emplear busqueda binaria y escaneos de arriba abajo, o alreves. Tener particiones nos ayuda
a tener un tamanyo de ingestion constante cuando ocurre las 24/7 horas de la semana.

Las columnas son de tipo fijo o de tipo variable. Si son de tipo fijo, cada valor es de un ancho en concreto.
Si son de tipo variable entonces hay dos ficheros, el de indices (*.i) y el de datos (*.d). En el de indices
apuntamos la direccion, u offset, donde empieza el dato en el fichero de datos, mientras que en el fichero de
datos anotamos la longitud, seguida por los bytes que componen el dato, lo cual sirve para reducir scaneos en
disco. Tambien existe el tipo diccionario (simbolo).

Al hacer un insert, puede que ocurra simplemente como append, o que haya colisiones, datos fuera de orden.
Para ello existe una zona de memoria donde se hace el merge, ordenando, y cuando llega el commit se guarda
ordenado. En caso de tener que hacer un merge, se abre la particion que toca y se lanza 3 tareas por columna,
para ordenar. Penalizamos las escrituras, por lo que podriamos decir que optimizamos las lecturas.

Las particiones son versionadas con numero de txn a la hora de modificarlas. Los lectores pueden leer lo que
haya disponible hasta el commit, pero no el area de O3. Cuando el commit ocurre, la nueva particion es registrada
en el fichero de metadatos _txn y nuevos lectores pueden leer sus datos atomicamente. Estos ficheros son
memoria compartida (los de metadatos), con lo que la sincronizacion entre dos instancias seria a traves de 
memoria compartida. QuestDB tiene threads que borran particiones huerfanas. Transaction score-board mantiene los
numeros de _txn de las transacciones en vuelo. Coordinacion entre lectores y escritores es por memoria compartida,
y la clase de leer esta separada de la de escribir.

Esta capa da suficientes primitivas como para que la capa de computacion quede desacoplada de la capa de 
almacenamiento.

![Arch](images/FS_layout.png)

### 4.2 Compute Engine

Esta capa simplemente toma las entradas, el SQL, y lo hace cruzar un pipeline hecho a base de operadores.
Los operadores realizan transformaciones de datos y funciona todo a base de llamadas a funciones, que pueden
actuar como predicados. La computacion viene definida por el function call stack. Los operadores son componentes
reutilizables. Dependiendo de la maquina donde corramos, el optimizador de clausulas where generara filtros
nativos (en un Mac M1 no). Hay un optimizador basado en normas, cuyo objetivo es tener un tiempo de ejecucion
predecible, y evitar leer datos no necesarios.

#### 4.2.1 Ejemplo 1

```sql
SELECT * FROM trades
WHERE price > 0.1 AND time IN '2023-02-13T15';
```

Si hay tres partitiones, dos seran no consideradas, y la tercera sera buscada mediante busqueda binaria y
su resultado sera una fraccion de la particion. Con esto tenemos seleccionado la zona temporal en toda la
base de datos y solo falta aplicar SIMD concurrente para encontrar que filas cumplen con la otra condicion.

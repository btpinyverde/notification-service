# Hub de notificaciones

Servicio HTTP en Java 17 y Spring Boot 3.5.

## Precondiciones

JDK 17 y Maven 3.6.3 o posterior para ejecutar la aplicación en desarrollo.

Docker con Compose v2.20 o posterior. Las pruebas de integración con Testcontainers también requieren Docker.

Para levantar el proyecto completo, alcanza con Docker Compose. La primera construcción descarga imágenes y dependencias de Maven Central.

## Cómo levantar el proyecto

### Entrega con Compose

Desde la raíz del repositorio:

```sh
docker compose up --build -d --wait
curl -fsS http://localhost:8080/actuator/health
```

Compose levanta la API, PostgreSQL y el receptor HTTP de prueba.

La API queda disponible en `http://localhost:8080`.

Para ver los logs de la aplicación:

```sh
docker compose logs -f app
```

Para detener los servicios conservando los datos:

```sh
docker compose down
```

### Desarrollo local

Primero, levantá PostgreSQL y el receptor de prueba:

```sh
docker compose up -d --wait db receiver
```

Después, ejecutá la aplicación:

```sh
NOTIFICATIONS_API_KEY=local-demo-key \
NOTIFICATIONS_SERVICE_TARGETS=http://localhost:18081/delivery,http://localhost:18081/unavailable \
mvn spring-boot:run
```

La aplicación usa el puerto `8080`. PostgreSQL queda en `localhost:15433` y el receptor HTTP en `localhost:18081`.

Flyway aplica las migraciones al arrancar la aplicación.

## Credenciales de prueba

Los valores predeterminados para la ejecución local son:

| Configuración | Valor |
| --- | --- |
| Base de datos | `notifications` |
| Usuario PostgreSQL | `notifications` |
| Contraseña PostgreSQL | `local-demo-password` |
| API key | `local-demo-key` |

La API pide el encabezado `X-API-Key` para crear y consultar notificaciones y para consultar métricas.

Para cambiar las credenciales de Compose, copiá `.env.example` a `.env` y modificá sus valores.

## Probar el servicio

### Crear una notificación

```sh
curl -i http://localhost:8080/notifications \
  -H 'X-API-Key: local-demo-key' \
  -H 'Content-Type: application/json' \
  -d '{
    "recipient": "diagnostico",
    "channel": "LOG",
    "subject": "Prueba",
    "body": "Hola",
    "priority": "HIGH",
    "metadata": {
      "source": "demo"
    }
  }'
```

El POST devuelve `202 Accepted`, un ID y el encabezado `Location`.

La notificación se guarda en estado `PENDING`. El worker la procesa en segundo plano y, si el despacho funciona, la marca como `SENT`.

Cada solicitud se despacha por un único canal: el indicado en `channel`. Los canales implementados son `LOG` y `SERVICE`.

### Consultar el estado

Reemplazá `ID` por el identificador recibido:

```sh
curl -fsS \
  -H 'X-API-Key: local-demo-key' \
  http://localhost:8080/notifications/ID
```

La respuesta incluye el estado, la cantidad de intentos, el último error y las fechas de creación y actualización.

### Probar el canal HTTP

Con la aplicación ejecutándose dentro de Compose:

```sh
curl -i http://localhost:8080/notifications \
  -H 'X-API-Key: local-demo-key' \
  -H 'Content-Type: application/json' \
  -d '{
    "recipient": "http://receiver:8080/delivery",
    "channel": "SERVICE",
    "subject": "Prueba HTTP",
    "body": "Hola",
    "priority": "MEDIUM",
    "metadata": {
      "source": "demo"
    }
  }'
```

El destino debe estar habilitado en `NOTIFICATIONS_SERVICE_TARGETS`.

Los destinos de prueba dependen de dónde se ejecute la aplicación:

| Ejecución de la aplicación | Destino exitoso | Destino con error |
| --- | --- | --- |
| Dentro de Compose | `http://receiver:8080/delivery` | `http://receiver:8080/unavailable` |
| Local, fuera de Compose | `http://localhost:18081/delivery` | `http://localhost:18081/unavailable` |

El destino `/delivery` devuelve 204 y la notificación termina en `SENT`.

El destino `/unavailable` devuelve 503. La notificación termina en `FAILED` después de tres intentos, con `lastError: HTTP_503`.

### Salud, logs y métricas

El endpoint de salud no requiere API key:

```sh
curl -fsS http://localhost:8080/actuator/health
```

Los logs se escriben en JSON. El campo `notificationId` permite seguir la aceptación, el procesamiento y el resultado de una notificación.

Para consultar los resultados del despacho:

```sh
curl -fsS \
  -H 'X-API-Key: local-demo-key' \
  http://localhost:8080/actuator/metrics/notifications.dispatch.outcomes
```

Cada resultado aparece en el contador después de su primera ocurrencia.

## Pruebas

Para ejecutar los tests de API y dispatcher sin PostgreSQL:

```sh
mvn -Dtest=NotificationApiTest,DispatcherTest test
```

Para ejecutar toda la suite, con Docker disponible:

```sh
mvn test
```

Los tests de integración usan PostgreSQL mediante Testcontainers. No necesitan que la base de desarrollo esté levantada.

La suite incluye:

- `PersistenceIntegrationTest`: guarda y recupera una notificación en PostgreSQL.
- `NotificationApiTest`: verifica aceptación, autenticación, campos obligatorios, enums numéricos, destinos no habilitados y caracteres nulos.
- `DispatcherTest`: verifica el envío, la política de reintentos, las métricas de resultado y el caso en que se pierde la reserva.
- `QueueConcurrencyIntegrationTest`: verifica que dos consumidores no reservan la misma fila y pueden avanzar sobre filas distintas. También comprueba el orden por prioridad y antigüedad, el rollback de una reserva y el rechazo de operaciones con reservas vencidas o tokens anteriores.
- `HttpChannelTest`: verifica el timeout cuando el receptor acepta el pedido pero no responde.
- `NotificationIntegrationTest`: verifica ambos canales, el fallo después de agotar reintentos, la aceptación de pedidos mientras un despacho falla y la recuperación de reservas vencidas.

Las pruebas concurrentes usan transacciones distintas sobre PostgreSQL. No constituyen una prueba de carga ni una demostración con varias instancias de la aplicación.

## Decisiones de diseño

### Persistencia y aceptación

PostgreSQL guarda las notificaciones y sus estados. JDBC permite revisar las consultas y Flyway versiona el esquema.

El POST responde después del guardado. La misma fila persistida queda disponible para que el worker la procese, por lo que no hay una operación separada de publicación en otro sistema.

### Autenticación

Elegí una API key para la integración entre servicios porque el cliente puede autenticarse con un encabezado y una clave compartida.

Esto permite probar el flujo sin desplegar un proveedor de identidad. La clave se lee de configuración y el servicio no usa sesiones.

### Cola y concurrencia

Una sentencia usa `FOR UPDATE SKIP LOCKED` para seleccionar y reservar una notificación. Esto permite que otros consumidores avancen sobre trabajos distintos.

El envío se realiza fuera de la transacción de reserva, evitando mantener bloqueos de base de datos durante la llamada HTTP.

Cada reserva tiene un token y un vencimiento. Para guardar el resultado, el consumidor debe conservar una reserva vigente con el mismo token. Así, un consumidor atrasado no puede sobrescribir el resultado de una reserva posterior.

Un proceso periódico recupera las reservas vencidas. Las vuelve a dejar pendientes o las marca como fallidas cuando se agotó el presupuesto de intentos.

Elegí una cola en PostgreSQL para guardar y hacer elegible el pedido en una sola operación. Un broker separado agregaría otro componente y requeriría coordinar la persistencia con la publicación.

### Orden y reintentos

Entre los trabajos elegibles, se seleccionan primero los de mayor prioridad y, a igual prioridad, los más antiguos.

El worker permite hasta tres intentos. Después de los primeros dos fallos, programa esperas de uno y dos segundos, respectivamente.

Las notificaciones que agotan los intentos quedan persistidas en estado `FAILED`, con el último error.

### Canales

Cada solicitud se despacha por el canal indicado en `channel`.

LOG permite verificar localmente la entrega mediante un registro JSON con el contenido de la notificación.

SERVICE permite probar la integración con otro servicio y sus fallos HTTP. La URL se indica por notificación y debe pertenecer a la lista de destinos habilitados por configuración.

El cliente HTTP usa un timeout de conexión de dos segundos y un timeout de solicitud de cinco segundos. No sigue redirecciones.

El ID de la notificación se envía en el encabezado `Idempotency-Key` para que el receptor pueda deduplicar entregas repetidas.

## Trade-offs y limitaciones

- La prioridad alta puede postergar trabajos de prioridad baja bajo carga continua.
- Una reserva vencida consume un intento, aunque no se pueda confirmar si el envío llegó a ejecutarse.
- Si el worker cae después del envío y antes de registrar el éxito, el destino puede recibir la notificación nuevamente. La deduplicación depende del receptor y del uso de `Idempotency-Key`.
- El POST de entrada admite pedidos repetidos. No implementa deduplicación de solicitudes del cliente.
- Todos los fallos HTTP usan el mismo presupuesto de reintentos. En producción distinguiría errores permanentes y transitorios, y consideraría `Retry-After` y jitter.
- Los contadores son por instancia. En producción agregaría métricas de acumulación de pendientes y alertas.
- El canal LOG escribe el contenido como parte de la entrega. En producción definiría políticas de acceso y retención.
- Para un entorno compartido usaría TLS, credenciales por cliente con rotación y permisos de consulta.
- La API y el worker comparten el binario. La propiedad `notifications.worker.enabled=false` permite iniciar una instancia sin worker. Al ejecutar Java directamente, puede configurarse mediante `NOTIFICATIONS_WORKER_ENABLED=false`; para hacerlo en Compose, debe pasarse esa variable al servicio `app`.
- La suite no simula cortes físicos de la base de datos ni la muerte real de un proceso durante el envío. Esos escenarios y las pruebas de carga quedarían como trabajo adicional.

## Consideración sobre Jakarta EE

En WildFly usaría Jakarta REST para los endpoints, CDI para inyección, Bean Validation y un datasource del servidor administrado por JNDI.

Mantendría el SQL de reserva y el envío fuera de la transacción. Para el worker usaría Jakarta Concurrency y un `ManagedScheduledExecutorService`, de modo que los hilos fueran administrados por el servidor.

Ejecutaría las migraciones en una etapa explícita del despliegue.

Si el entorno ofreciera un broker administrado, consideraría Jakarta Messaging y acordaría la política de reentrega y el manejo de mensajes agotados.

El historial en PostgreSQL y la publicación requerirían un límite transaccional definido, por ejemplo un outbox. Con ambas arquitecturas, la entrega HTTP seguiría necesitando una estrategia de idempotencia en el receptor.
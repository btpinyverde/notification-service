# Hub de notificaciones

Servicio HTTP en Java 17 y Spring Boot 3.5.

## Precondiciones

JDK 17 y Maven 3.6.3 o posterior para ejecutar la aplicación en desarrollo.

Docker con Compose v2.20 o posterior para PostgreSQL. Las pruebas con Testcontainers también requieren Docker.

Para levantar el proyecto completo, alcanza con Docker Compose. La primera construcción descarga imágenes y dependencias de Maven Central.

## Cómo levantar el proyecto

### Entrega con Compose

```sh
docker compose up --build -d --wait
curl -fsS http://localhost:8080/actuator/health
```

Compose levanta la API, PostgreSQL y el receptor de prueba. Usá `docker compose logs app` para ver el despacho y `docker compose down` para detener los servicios conservando los datos.

### Desarrollo local

```sh
docker compose up -d --wait db receiver

NOTIFICATIONS_API_KEY=local-demo-key \
NOTIFICATIONS_SERVICE_TARGETS=http://localhost:18081/delivery,http://localhost:18081/unavailable \
mvn spring-boot:run
```

La aplicación usa el puerto `8080`. PostgreSQL queda en `localhost:15433`. Flyway aplica las migraciones al arrancar Java.

El receptor queda en `localhost:18081` cuando Java corre en la Mac.

## Credenciales de prueba

La base y el usuario de PostgreSQL se llaman `notifications`. La contraseña de ejemplo es `local-demo-password`.

La API pide `X-API-Key: local-demo-key` para crear y consultar notificaciones.

Usá también la API key para consultar métricas.

Para cambiar los valores de Compose, copiá `.env.example` a `.env`.

## Probar el servicio

### Crear y consultar una notificación

```sh
curl -i http://localhost:8080/notifications \
  -H 'X-API-Key: local-demo-key' \
  -H 'Content-Type: application/json' \
  -d '{"recipient":"diagnostico","channel":"LOG","subject":"Prueba","body":"Hola","priority":"HIGH","metadata":{"source":"demo"}}'
```

El `POST` devuelve `202 Accepted` con un ID y el encabezado `Location`. Usá ese ID para consultar el estado.

```sh
curl -fsS -H 'X-API-Key: local-demo-key' http://localhost:8080/notifications/ID
```

La fila se crea en `PENDING`. El worker procesa el canal LOG en segundo plano y la marca como `SENT`.

### Probar el canal HTTP

Enviá otro pedido con `channel: SERVICE` y uno de estos destinos en `recipient`:

| Destino con Java local | Resultado esperado |
| --- | --- |
| `http://localhost:18081/delivery` | El receptor devuelve 204. El trabajo termina en `SENT`. |
| `http://localhost:18081/unavailable` | El receptor devuelve 503. El trabajo termina en `FAILED` con tres intentos y `lastError: HTTP_503`. |

Si la API también corre en Compose, usá `http://receiver:8080/delivery` o `http://receiver:8080/unavailable`.

### Salud, logs y métricas

Consultá `GET /actuator/health` para verificar el estado del servicio. Buscá `notificationId` en los logs JSON para seguir un pedido.

```sh
curl -fsS -H 'X-API-Key: local-demo-key' 'http://localhost:8080/actuator/metrics/notifications.dispatch.outcomes'
```

Cada resultado aparece en el contador después de su primera ocurrencia.

## Pruebas

```sh
mvn -Dtest=NotificationApiTest,DispatcherTest test
mvn test
```

- `PersistenceIntegrationTest` guarda y recupera un pedido en PostgreSQL.
- `NotificationApiTest` cubre aceptación, autenticación y validación.
- `DispatcherTest` cubre el envío y los reintentos.
- `QueueConcurrencyIntegrationTest` verifica que dos transacciones no reservan la misma fila y pueden avanzar sobre filas distintas.
- `NotificationIntegrationTest` verifica ambos canales, el fallo tras agotar reintentos y la recuperación de reservas vencidas.

## Decisiones de diseño

- **Persistencia.** PostgreSQL guarda las notificaciones y sus estados. JDBC permite revisar las consultas y Flyway versiona el esquema.
- **Aceptación.** El POST responde después del guardado. La API key se lee de configuración y el servicio no usa sesiones.
- **Cola.** Una sentencia usa `FOR UPDATE SKIP LOCKED` para seleccionar y reservar. El envío se hace fuera de la transacción. Un token impide que un consumidor con una reserva vencida actualice el resultado. Una cola en PostgreSQL permite guardar y hacer elegible el pedido en una sola operación, evitando coordinar un broker separado.
- **Orden y reintentos.** Entre trabajos elegibles se elige prioridad y después antigüedad. El worker permite hasta tres intentos con esperas de uno y dos segundos tras los primeros fallos.
- **Canales.** LOG permite una verificación local. SERVICE reproduce integración y fallos HTTP. El destino debe estar habilitado en configuración. El cliente HTTP usa límites de tiempo y desactiva redirecciones.

## Trade-offs y limitaciones

- La prioridad alta puede postergar trabajos de prioridad baja bajo carga continua. Una reserva que vence consume un intento.
- Si el worker cae después del envío, el destino puede recibirlo de nuevo. El receptor puede deduplicar con el ID de `Idempotency-Key`. El POST de entrada admite pedidos repetidos.
- Todos los fallos HTTP usan el mismo presupuesto de reintentos. En producción distinguiría errores permanentes y consideraría `Retry-After` y jitter.
- Los contadores son por instancia. El canal LOG escribe el contenido como parte de la entrega. En producción harían falta políticas de retención y alertas por acumulación.
- Para un entorno compartido usaría TLS, credenciales por cliente con rotación y permisos de consulta. La API y el worker comparten el binario; `NOTIFICATIONS_WORKER_ENABLED=false` permite ejecutar una instancia solo como API.

## Consideración sobre Jakarta EE

En WildFly usaría Jakarta REST para los endpoints, CDI para inyección, Bean Validation y un datasource del servidor administrado por JNDI. Mantendría el SQL de reserva y el envío fuera de la transacción. Para el worker usaría Jakarta Concurrency y un `ManagedScheduledExecutorService`. Ejecutaría las migraciones en una etapa explícita del despliegue.

Si el entorno ofreciera un broker administrado, consideraría Jakarta Messaging y acordaría la política de reentrega y el manejo de mensajes agotados. El historial en PostgreSQL y la publicación requerirían un límite transaccional definido, por ejemplo un outbox. Con ambas arquitecturas, la entrega HTTP seguiría necesitando una estrategia de idempotencia en el receptor.

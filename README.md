# Hub de notificaciones

Servicio HTTP en Java 17 y Spring Boot 3.5.

## Precondiciones

JDK 17 y Maven 3.6.3 o posterior para ejecutar la aplicación en desarrollo.

Docker con Compose v2.20 o posterior para PostgreSQL. Las pruebas con Testcontainers también requieren Docker.

## Cómo levantar el proyecto

### Desarrollo local

```sh
docker compose up -d --wait db

NOTIFICATIONS_API_KEY=local-demo-key \
mvn spring-boot:run
```

La aplicación usa el puerto `8080`. PostgreSQL queda en `localhost:15433`. Flyway aplica las migraciones al arrancar Java.

## Credenciales de prueba

La base y el usuario de PostgreSQL se llaman `notifications`. La contraseña de ejemplo es `local-demo-password`.

La API pide `X-API-Key: local-demo-key` para crear y consultar notificaciones.

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

## Pruebas

```sh
mvn -Dtest=NotificationApiTest,DispatcherTest test
mvn test
```

- `PersistenceIntegrationTest` guarda y recupera un pedido en PostgreSQL.
- `NotificationApiTest` cubre aceptación, autenticación y validación.
- `DispatcherTest` cubre el envío y los reintentos.
- `QueueConcurrencyIntegrationTest` verifica que dos transacciones no reservan la misma fila y pueden avanzar sobre filas distintas.

## Decisiones de diseño

- **Persistencia.** PostgreSQL guarda las notificaciones y sus estados. JDBC permite revisar las consultas y Flyway versiona el esquema.
- **Aceptación.** El POST responde después del guardado. La API key se lee de configuración y el servicio no usa sesiones.
- **Cola.** Una sentencia usa `FOR UPDATE SKIP LOCKED` para seleccionar y reservar. El envío se hace fuera de la transacción. Un token impide que un consumidor con una reserva vencida actualice el resultado. Una cola en PostgreSQL permite guardar y hacer elegible el pedido en una sola operación, evitando coordinar un broker separado.
- **Orden y reintentos.** Entre trabajos elegibles se elige prioridad y después antigüedad. El worker permite hasta tres intentos con esperas de uno y dos segundos tras los primeros fallos.

## Trade-offs y limitaciones

- La prioridad alta puede postergar trabajos de prioridad baja bajo carga continua. Una reserva que vence consume un intento.

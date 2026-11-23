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

La fila recién creada queda en `PENDING`.

## Pruebas

```sh
mvn -Dtest=NotificationApiTest test
mvn test
```

- `PersistenceIntegrationTest` guarda y recupera un pedido en PostgreSQL.
- `NotificationApiTest` cubre aceptación, autenticación y validación.

## Decisiones de diseño

- **Persistencia.** PostgreSQL guarda las notificaciones y sus estados. JDBC permite revisar las consultas y Flyway versiona el esquema.
- **Aceptación.** El POST responde después del guardado. La API key se lee de configuración y el servicio no usa sesiones.

# 🧭 Order Management API

A high-performance, robust, and auditable RESTful Order Management microservice built with **Scala 3**, **Play Framework 3.0**, and **sbt**.

The service handles full lifecycle order operations (Create, Read, Update, Delete, Search) while maintaining an immutable audit log using **Write-Ahead Logging (WAL)** and **Event Sourcing** semantics.

---

## 🏗 System Architecture & Design

### High-Level Architecture

```
                 +---------------------------------------------+
                 |              REST API Clients               |
                 |     (Postman, Frontend, Upstream Services)  |
                 +----------------------+----------------------+
                                        |
                                        v
                 +---------------------------------------------+
                 |            Play Controller Layer            |
                 | - Route matching & strongly-typed DTOs      |
                 | - Multi-field error validation accumulation |
                 +----------------------+----------------------+
                                        |
                                        v
                 +---------------------------------------------+
                 |            Order Service Layer              |
                 | - In-memory state projection (ConcurrentMap)|
                 | - Write-Ahead Logging (WAL) state sync      |
                 +----------------------+----------------------+
                                        |
                                        v
                 +---------------------------------------------+
                 |        AuditLogRepository Abstraction       |
                 |               (Guice DI Bound)              |
                 +----+-------------------+----------------+---+
                      |                   |                |
                      v                   v                v
                 [File Storage]     [In-Memory]     [Kafka / MySQL]
                 (Default JSON)   (Testing/Fast)     (Placeholders)
```

---

## 🎯 Key Design Principles

1. **Write-Ahead Logging (WAL) & Event Sourcing**:
   - Order mutations (`OrderCreated`, `OrderUpdated`, `OrderDeleted`) are persisted to the `AuditLogRepository` **before** updating in-memory state.
   - If writing to the audit log fails (e.g. disk failure or broker outage), the in-memory state remains untouched, preventing data drift.
   - System state can be reconstructed on restart by replaying events from the audit log.

2. **Decoupled Repositories & Dependency Injection**:
   - `AuditLogRepository` is defined as a clean `trait` (`append`, `readAll`, `clear`).
   - Implementations are dynamically bound via Google Guice in `Module.scala` based on the `app.audit-log.type` configuration.
   - Ready-to-use implementations include `FileAuditLogRepository`, `InMemoryAuditLogRepository`, `KafkaAuditLogRepository` (placeholder), and `MySqlAuditLogRepository` (placeholder).

3. **Strongly-Typed Domain Models & Validation**:
   - Request DTOs use strongly-typed fields (`Currency`, `TransactionType`, `BigDecimal`, `OffsetDateTime`).
   - JSON parsing automatically accumulates **all** field validation errors into a structured `details` array, avoiding single-error fail-fast limitations.

---

## 📦 Domain Model

| Property | Type | Description |
| :--- | :--- | :--- |
| `id` | `UUID` | Unique identifier, auto-assigned on order creation. |
| `date` | `OffsetDateTime` | Order timestamp (ISO-8601 with timezone offset). Defaults to now if omitted. |
| `amount` | `BigDecimal` | Monetary value preserved with exact decimal precision. |
| `currencyCode` | `Currency` | ISO-4217 standard currency code (e.g., `USD`, `EUR`, `CAD`). |
| `transactionType` | `TransactionType` | Enumeration: `Sale` or `Refund`. |

---

## ⚙️ Configuration & Pluggable Storage

The active audit log implementation is configured in `conf/application.conf` or overridden via environment variables:

```hocon
app {
  audit-log {
    # Options: "file", "memory", "kafka", "mysql"
    type = "file"
    type = ${?AUDIT_LOG_TYPE}

    file {
      path = "data/audit.log"
      path = ${?AUDIT_LOG_FILE_PATH}
    }

    kafka {
      bootstrap-servers = "localhost:9092"
      topic = "orders-audit"
    }

    mysql {
      url = "jdbc:mysql://localhost:3306/orders_db"
      table = "order_audit_logs"
    }
  }
}
```

---

## 🚀 API Endpoints

### 1. Create Order
* **Method**: `POST /orders`
* **Request Body**:
  ```json
  {
    "date": "2026-08-20T12:00:00Z",
    "amount": 149.99,
    "currencyCode": "USD",
    "transactionType": "Sale"
  }
  ```
* **Response**: `201 Created` with `Location: /orders/{id}` header and created order payload.

### 2. Get Order by ID
* **Method**: `GET /orders/:id`
* **Response**: `200 OK` with order JSON or `404 Not Found`.

### 3. Update Order
* **Method**: `PATCH /orders/:id`
* **Request Body** (all fields optional):
  ```json
  {
    "amount": 199.99,
    "currencyCode": "EUR",
    "transactionType": "Refund"
  }
  ```
* **Response**: `200 OK` with updated order or `404 Not Found`.

### 4. Delete Order
* **Method**: `DELETE /orders/:id`
* **Response**: `204 No Content` or `404 Not Found`.

### 5. Search Orders
* **Method**: `GET /orders/search`
* **Query Parameters**:
  - `currencyCode` (e.g. `USD`)
  - `transactionType` (e.g. `Sale`, `Refund`)
  - `startDate` (ISO-8601, e.g. `2026-08-01T00:00:00Z`)
  - `endDate` (ISO-8601, e.g. `2026-08-31T23:59:59Z`)
* **Response**: `200 OK` with array of matching orders.

---

## 🛡 Standardized Error Format

Validation errors return `400 Bad Request` with full error accumulation across all invalid fields:

```json
{
  "timestamp": "2026-08-20T18:00:00-04:00",
  "error": "ValidationError",
  "message": "Validation failed for 2 fields",
  "details": [
    {
      "field": "currencyCode",
      "message": "Invalid currency code 'INVALID'. Must follow ISO 4217 (e.g., USD, EUR, CAD)."
    },
    {
      "field": "transactionType",
      "message": "Invalid transactionType 'UNKNOWN'. Must be one of: Sale, Refund"
    }
  ]
}
```

---

## 🧪 Testing & Verification

The test suite contains **37 unit tests** across 5 test suites covering controllers, services, repositories, and error paths.

```bash
# Run all tests
sbt test
```

### Test Suites:
- `OrderControllerSpec`: Validates all HTTP endpoints, header generation, multi-field error accumulation, and query filtering.
- `InMemoryOrderServiceSpec`: Tests CRUD logic, WAL state consistency, search filtering, and rollback when audit persistence fails.
- `PlaceholderAuditLogRepositoriesSpec`: Tests Kafka & MySQL placeholder lifecycle and Guice dynamic binding.
- `InMemoryAuditLogRepositorySpec`: Tests thread-safe in-memory event recording.
- `FileAuditLogRepositorySpec`: Tests file persistence, formatting, and replay.

---

## 📬 Postman Collection

A complete Postman collection is included in [`order-api.postman_collection.json`](./order-api.postman_collection.json).

### Features:
- Pre-configured requests for all 5 endpoints.
- Auto-extracts created `id` and populates the `{{orderId}}` collection variable.
- Configurable `{{baseUrl}}` variable (`http://localhost:9000`).

---

## 🏃 Running the Application

```bash
# Start Play development server
sbt run
```

Access the service at `http://localhost:9000`.

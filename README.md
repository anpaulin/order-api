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
   - `AuditLogRepository` is defined as a clean append-only `trait` (`append`, `readAll`).
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

## 🏃 Quick Start: Running the Application

### 1. Prerequisites
- **Java 17+** (e.g. Eclipse Adoptium / Temurin)
- **sbt 1.9+**

### 2. Start the Server

```bash
# Start the Play development server (runs on port 9000 by default)
sbt run
```

Once started, the API is live at `http://localhost:9000`.

### 3. Verify Health / Test via cURL

```bash
# Create an order
curl -i -X POST http://localhost:9000/orders \
  -H "Content-Type: application/json" \
  -d '{"amount": 99.50, "currencyCode": "USD", "transactionType": "Sale"}'

# Search orders
curl -i "http://localhost:9000/orders/search?currencyCode=USD&transactionType=Sale"
```

---

## 🧪 Testing & Verification

The test suite includes **36 unit and integration tests** across 5 comprehensive test suites covering controllers, services, repositories, validation, and error paths.

```bash
# Run the complete test suite
sbt test

# Run a specific test suite
sbt "testOnly controllers.OrderControllerSpec"
sbt "testOnly services.InMemoryOrderServiceSpec"
sbt "testOnly repositories.FileAuditLogRepositorySpec"
```

### Test Suites Overview:
- `OrderControllerSpec`: Validates all 5 HTTP endpoints, status codes, header generation, non-blocking `Future` resolution, and multi-field validation error accumulation.
- `InMemoryOrderServiceSpec`: Tests CRUD logic, asynchronous Write-Ahead Logging (WAL) state sync, search filtering, and atomicity guarantees when audit log persistence fails.
- `FileAuditLogRepositorySpec`: Tests filesystem persistence, file creation, event formatting, and stream reading.
- `InMemoryAuditLogRepositorySpec`: Tests thread-safe in-memory event recording.
- `PlaceholderAuditLogRepositoriesSpec`: Tests simulated Kafka producer/consumer, MySQL table operations, and configuration-driven Guice dynamic DI bindings.

---

## 🚦 Traffic Generator & Simulator

A high-performance, standalone traffic simulator is included to generate a continuous, realistic stream of orders, patches, deletes, and searches against the running server.

### Operation Distribution:
- **60%** Order Creations (`POST /orders`)
- **25%** Order Updates (`PATCH /orders/:id`)
- **10%** Order Deletions (`DELETE /orders/:id`)
- **5%** Search Queries (`GET /orders/search`)

### Running the Simulator:

In a separate terminal (while `sbt run` is active):

```bash
# Run with default settings (10 reqs/sec against http://localhost:9000 indefinitely)
sbt "runMain tools.OrderSimulator"

# Run at 50 reqs/sec for a 60-second benchmark
sbt "runMain tools.OrderSimulator --rate 50 --duration 60 --target http://localhost:9000"
```

### Simulator CLI Options:
| Flag | Description | Default |
| :--- | :--- | :--- |
| `--rate <int>` | Target request throughput in requests/second | `10` |
| `--target <url>` | Base URL of the Order Service | `http://localhost:9000` |
| `--duration <sec>` | Run duration in seconds (`0` = infinite until `Ctrl+C`) | `0` |

### Sample Output:
```
============================================================
🚀 Order Service Traffic Simulator
   Target URL : http://localhost:9000
   Rate       : 20 reqs/sec
   Duration   : Unlimited (Ctrl+C to stop)
============================================================
[STATS] Throughput:   20.0 req/s | Active IDs:   24 | Created:    24 | Updated:    10 | Deleted:     4 | Errors: 0
[STATS] Throughput:   20.1 req/s | Active IDs:   48 | Created:    49 | Updated:    21 | Deleted:     8 | Errors: 0
^C
============================================================
📊 Simulation Summary
   Total Requests Success : 1,200
   Total Requests Failed  : 0
   - Created Orders       : 720
   - Updated Orders       : 300
   - Deleted Orders       : 120
   - Search Queries       : 60
   - Remaining In-Memory  : 600
============================================================
```

---

## 📬 Postman Collection

A complete Postman collection is included in [`order-api.postman_collection.json`](./order-api.postman_collection.json).

### Features:
- Pre-configured requests for all 5 endpoints.
- Auto-extracts created `id` from `POST /orders` response into the `{{orderId}}` collection variable.
- Configurable `{{baseUrl}}` variable (`http://localhost:9000`).



# Distributed Order Processing System

A distributed e-commerce backend built with Spring Boot microservices, designed to model how a real-world order processing system can be decomposed into independent services.

The project is being developed incrementally to understand and implement microservices architecture, service-to-service communication, database-per-service design, concurrency, caching, containerization, Kubernetes, CI/CD, and cloud deployment.

---

## Architecture

```text
                         ┌───────────────┐
                         │    Client     │
                         └───────┬───────┘
                                 │
                                 │ HTTP
                                 ▼
                       ┌───────────────────┐
                       │   Order Service   │
                       │      :8080        │
                       └─────────┬─────────┘
                                 │
                                 │ REST
                                 ▼
                     ┌───────────────────────┐
                     │  Inventory Service   │
                     │        :8081          │
                     └───────────┬───────────┘
                                 │
                                 ▼
                         ┌───────────────┐
                         │  PostgreSQL   │
                         │ inventory_db  │
                         └───────────────┘


                    Future Services
                    ────────────────

              ┌──────────────────────┐
              │    Payment Service   │
              └──────────────────────┘

              ┌──────────────────────┐
              │ Notification Service │
              └──────────────────────┘
```

Each microservice is responsible for its own business logic and data.

Services communicate through APIs rather than directly accessing another service's database.

---

## Current Status

### Inventory Service

- [x] Spring Boot application
- [x] PostgreSQL integration
- [x] JPA / Hibernate
- [x] Inventory entity
- [x] Repository layer
- [x] Service layer
- [x] REST controller
- [x] Add inventory
- [x] Get inventory
- [x] Reduce inventory
- [x] Product-not-found exception handling
- [x] Insufficient-inventory exception handling
- [x] Global exception handling

### Order Service

- [x] Spring Boot application
- [x] PostgreSQL integration
- [x] JPA / Hibernate
- [x] Order entity
- [x] Repository layer
- [x] Service layer
- [x] REST controller
- [x] Order creation
- [x] Request validation
- [x] Global exception handling
- [x] Communication with Inventory Service
- [x] Inventory availability check
- [x] Inventory reduction during order creation

---

## Order Processing Flow

When a client creates an order, the Order Service communicates with the Inventory Service before saving the order.

```text
Client
  │
  │ POST /orders
  ▼
Order Controller
  │
  ▼
Order Service
  │
  │ GET /inventory/{productId}
  ▼
Inventory Service
  │
  │ Check stock
  ▼
Order Service
  │
  │ PUT /inventory/{productId}/reduce
  ▼
Inventory Service
  │
  │ Reduce stock
  ▼
Order Service
  │
  │ Save order
  ▼
PostgreSQL
```

Example request:

```http
POST /orders
```

```json
{
  "productId": 101,
  "quantity": 2,
  "status": "CREATED"
}
```

The Order Service:

1. Receives the order request.
2. Validates the request.
3. Checks inventory availability.
4. Requests the Inventory Service to reduce the stock.
5. Saves the order in its own database.

---

## Microservices

### Order Service

Responsible for:

- Creating orders
- Validating order requests
- Managing order state
- Communicating with other services

**Port:** `8080`

```text
order-service/
├── controller/
├── service/
├── repository/
├── entity/
├── dto/
└── exception/
```

### Inventory Service

Responsible for:

- Managing product inventory
- Checking stock availability
- Increasing inventory
- Reducing inventory
- Handling inventory-related errors

**Port:** `8081`

```text
inventory-service/
├── controller/
├── service/
├── repository/
├── entity/
└── exception/
```

---

## API Endpoints

### Inventory Service

Base URL:

```text
http://localhost:8081
```

#### Get Inventory

```http
GET /inventory/{productId}
```

Example:

```http
GET /inventory/101
```

Example response:

```json
{
  "id": 1,
  "productId": 101,
  "productName": "iPhone",
  "quantity": 8
}
```

#### Add Inventory

```http
POST /inventory
```

Example request:

```json
{
  "productId": 101,
  "productName": "iPhone",
  "quantity": 10
}
```

#### Reduce Inventory

```http
PUT /inventory/{productId}/reduce?quantity={quantity}
```

Example:

```http
PUT /inventory/101/reduce?quantity=2
```

---

### Order Service

Base URL:

```text
http://localhost:8080
```

#### Create Order

```http
POST /orders
```

Example request:

```json
{
  "productId": 101,
  "quantity": 2,
  "status": "CREATED"
}
```

---

## Error Handling

The services use custom exceptions and centralized exception handling

### Product Not Found

```text
GET /inventory/9999
```

Response:

```text
404 Not Found
Product not found
```

### Insufficient Inventory

Attempting to purchase more inventory than is available results in:

```text
409 Conflict
Not enough inventory
```

### Invalid Order

Requests failing validation return:

```text
400 Bad Request
```

For example, an order with a quantity below `1` is rejected before reaching the business logic.

---

## Database Architecture

The system follows a **database-per-service** approach.

```text
┌──────────────────┐
│   Order Service  │
└────────┬─────────┘
         │
         ▼
   ┌───────────┐
   │ order_db  │
   └───────────┘


┌──────────────────────┐
│  Inventory Service   │
└──────────┬───────────┘
           │
           ▼
    ┌──────────────┐
    │ inventory_db │
    └──────────────┘
```

A service owns its database and other services interact with it through APIs.

This keeps service responsibilities separated and prevents direct database coupling between services.

---

## Tech Stack

### Backend

- Java
- Spring Boot
- Spring Web
- Spring Data JPA
- Hibernate
- REST APIs

### Database

- PostgreSQL

### Caching & Distributed Systems

- Redis
- Distributed locking
- Rate limiting

### Containerization & Orchestration

- Docker
- Docker Compose
- Kubernetes

### CI/CD

- GitHub Actions

### Cloud

- Microsoft Azure
- Azure Container Registry
- Azure Kubernetes Service
- Azure Cache for Redis

### Testing

- JUnit
- Integration Testing

---

## Project Structure

```text
distributed-order-processing-system/
│
├── inventory-service/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   └── test/
│   ├── pom.xml
│   └── ...
│
├── order-service/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   └── test/
│   ├── pom.xml
│   └── ...
│
└── README.md
```

---

## Local Development

### Prerequisites

Install:

- Java
- Maven
- Docker
- IntelliJ IDEA (recommended)
- PostgreSQL client (optional)

### Start PostgreSQL

PostgreSQL is currently running inside Docker.

```bash
docker run --name inventory-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=inventory_db \
  -p 5432:5432 \
  -d postgres
```

The container exposes PostgreSQL on:

```text
localhost:5432
```

### Start Inventory Service

Run the Spring Boot application from:

```text
inventory-service
```

The service runs on:

```text
http://localhost:8081
```

### Start Order Service

Run the Spring Boot application from:

```text
order-service
```

The service runs on:

```text
http://localhost:8080
```

Both services must be running for the complete order flow to work.

---

## Example End-to-End Test

### 1. Add inventory

```http
POST http://localhost:8081/inventory
```

```json
{
  "productId": 101,
  "productName": "iPhone",
  "quantity": 10
}
```

### 2. Create an order

```http
POST http://localhost:8080/orders
```

```json
{
  "productId": 101,
  "quantity": 2,
  "status": "CREATED"
}
```

### 3. Check inventory

```http
GET http://localhost:8081/inventory/101
```

The inventory should now reflect the reduced quantity.

---

## Important Distributed Systems Challenges

The current implementation intentionally keeps the core flow simple.

The current order process is:

```text
1. Check inventory
2. Reduce inventory
3. Save order
```

This creates several distributed-systems challenges.

### Failure During Order Creation

For example:

```text
Inventory reduced
       ↓
Order database fails
       ↓
Inventory is reduced
but order was not created
```

This can result in inconsistent state between services.

### Concurrent Orders

Consider inventory containing:

```text
Quantity = 5
```

Two customers simultaneously request:

```text
Customer A → 5 items
Customer B → 5 items
```

Both requests could potentially see the same available quantity before either reduction completes.

This can lead to **overselling**.

These problems will be addressed in later stages using concurrency control, Redis, distributed locking, atomic operations, and improved failure handling.

---

## Roadmap

### Phase 1 — Core Services

- [x] Inventory Service
- [x] Order Service
- [x] PostgreSQL
- [x] REST communication
- [x] Validation
- [x] Exception handling
- [x] Basic order processing

### Phase 2 — Distributed Systems

- [ ] Redis
- [ ] Inventory caching
- [ ] Distributed locking
- [ ] Concurrency protection
- [ ] Overselling prevention
- [ ] Rate limiting
- [ ] Improved failure handling
- [ ] Idempotency

### Phase 3 — Containerization

- [ ] Dockerize Order Service
- [ ] Dockerize Inventory Service
- [ ] Docker Compose
- [ ] Container networking
- [ ] Environment-based configuration

### Phase 4 — Kubernetes

- [ ] Kubernetes deployments
- [ ] Kubernetes services
- [ ] ConfigMaps
- [ ] Secrets
- [ ] Health checks
- [ ] Scaling
- [ ] Resource limits

### Phase 5 — CI/CD

- [ ] GitHub Actions
- [ ] Automated tests
- [ ] Docker image builds
- [ ] Push images to Azure Container Registry
- [ ] Automated deployment

### Phase 6 — Azure

- [ ] Azure Container Registry
- [ ] Azure Kubernetes Service
- [ ] Azure Cache for Redis
- [ ] Cloud database
- [ ] Production-style configuration

### Phase 7 — Additional Services

- [ ] Payment Service
- [ ] Notification Service
- [ ] Order status management
- [ ] Failure/retry handling

### Phase 8 — Event-Driven Architecture

- [ ] Kafka / RabbitMQ / Azure Service Bus
- [ ] Asynchronous order events
- [ ] Payment events
- [ ] Notification events
- [ ] Event-based service communication

---

## Learning Goals

This project is being built incrementally to understand the engineering concepts behind distributed systems rather than simply assembling technologies.

Key concepts include:

- Microservice architecture
- Separation of responsibilities
- REST communication
- Service-to-service communication
- Dependency injection
- JPA and Hibernate
- Repository and service layers
- Database-per-service architecture
- Input validation
- Exception handling
- Distributed transactions
- Concurrency
- Caching
- Distributed locking
- Containerization
- Kubernetes orchestration
- CI/CD
- Cloud deployment
- Event-driven architecture

---

## Goal

The final goal is to build a realistic distributed e-commerce backend that demonstrates how independently deployable services can work together while handling the challenges of consistency, concurrency, failures, scaling, and deployment.

The project will evolve from a simple Spring Boot implementation into a distributed system deployed using containers, Kubernetes, CI/CD, and cloud infrastructure.

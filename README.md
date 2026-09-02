# Distributed Order Processing System

A distributed e-commerce backend built using Spring Boot microservices.

The system is designed to simulate the backend architecture of an e-commerce platform, where different services are responsible for orders, inventory, payments, and notifications.

The project focuses on learning and implementing microservices architecture, REST APIs, database-per-service design, distributed systems concepts, caching, concurrency, containerization, Kubernetes, and CI/CD.

---

##  Architecture

The planned architecture consists of multiple independent microservices:

```text
                         ┌─────────────────┐
                         │     Client      │
                         └────────┬────────┘
                                  │
                                  ▼
                         ┌─────────────────┐
                         │  Order Service  │
                         │    :8080        │
                         └────────┬────────┘
                                  │
                         REST API │
                                  ▼
                       ┌─────────────────────┐
                       │  Inventory Service  │
                       │       :8081         │
                       └──────────┬──────────┘
                                  │
                                  ▼
                         ┌─────────────────┐
                         │   PostgreSQL    │
                         │  Inventory DB   │
                         └─────────────────┘


       Future Services

       ┌─────────────────┐
       │ Payment Service │
       └─────────────────┘

       ┌────────────────────┐
       │ Notification       │
       │ Service            │
       └────────────────────┘

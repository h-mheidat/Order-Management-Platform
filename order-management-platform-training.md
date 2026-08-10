# Java / Spring Boot Senior Practical Training Project

## Order Management Platform

تدريب عملي شامل يجمع: Java 21، Core Java، Streams، Spring Boot،
JPA/Hibernate، Spring Security، JWT، Roles/Authorization،
WebFlux/Mono/WebClient، PostgreSQL، Redis، Kafka، Docker، Resilience4j،
JUnit 5، Mockito، Testcontainers وActuator.

## 1. فكرة النظام

Web Application لإدارة الطلبات.

المستخدمون: - ADMIN - CUSTOMER - SUPPORT

CUSTOMER: - Register - Login - Create Order - View Orders - View Order
Details - Cancel Order

SUPPORT: - View Orders - View Order Details - Update Order Status

ADMIN: - كل ما سبق - View Statistics

## 2. Architecture

نبدأ بـ Modular Monolith مع تصميم يسمح بالفصل لاحقًا إلى Microservices.

``` text
Client
  |
  v
Spring Boot API
  |-------- PostgreSQL
  |-------- Redis
  |-------- Kafka
                 |
                 v
            Order Events
```

## 3. Project Structure

``` text
com.example.orders
├── config
├── controller
├── service
├── repository
├── entity
├── dto
├── security
├── kafka
├── cache
├── exception
└── mapper
```

## 4. Authentication

### Register

``` http
POST /api/auth/register
```

``` json
{
  "username": "ahmad",
  "email": "ahmad@test.com",
  "password": "Password123"
}
```

المطلوب: - BCrypt للـ password. - Validation. - منع تكرار Email. - عدم
تخزين password كنص صريح.

### Login

``` http
POST /api/auth/login
```

``` json
{
  "email": "ahmad@test.com",
  "password": "Password123"
}
```

Response:

``` json
{
  "accessToken": "eyJhbGciOi..."
}
```

استخدم JWT.

## 5. Roles & Authorization

``` java
public enum Role {
    CUSTOMER,
    SUPPORT,
    ADMIN
}
```

CUSTOMER:

``` text
POST   /api/orders
GET    /api/orders
GET    /api/orders/{id}
DELETE /api/orders/{id}
```

SUPPORT:

``` text
GET   /api/orders
GET   /api/orders/{id}
PATCH /api/orders/{id}/status
```

ADMIN:

``` text
GET /api/admin/statistics
```

مثال:

``` java
@PreAuthorize("hasRole('ADMIN')")
@GetMapping("/admin/statistics")
public Statistics getStatistics() {
    // ...
}
```

يجب التفريق بين Authentication وAuthorization.

## 6. Order Domain

Order:

``` text
id
customerId
status
totalPrice
createdAt
updatedAt
version
```

``` java
public enum OrderStatus {
    CREATED,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
```

OrderItem:

``` text
id
order
productId
quantity
unitPrice
```

العلاقة:

``` text
User
 |
 +--< Orders
        |
        +--< OrderItems
```

استخدم `@Entity` و`@OneToMany` و`@ManyToOne`.

## 7. Create Order

``` http
POST /api/orders
```

``` json
{
  "items": [
    {"productId": 10, "quantity": 2},
    {"productId": 20, "quantity": 1}
  ]
}
```

العملية: 1. التحقق من المستخدم. 2. التحقق من المنتجات. 3. حساب السعر. 4.
إنشاء Order. 5. إنشاء OrderItems. 6. حفظ البيانات. 7. إنشاء Outbox
Event.

## 8. Java Streams

استخدم Streams في العمليات المناسبة.

``` java
BigDecimal total = items.stream()
        .map(item -> /* price */)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

تدرب على: - map - filter - reduce - collect - groupingBy

## 9. Redis Cache

لـ:

``` http
GET /api/orders/10
```

التدفق:

``` text
Request
  |
Redis
  |-- HIT --> Return
  |
  +-- MISS --> PostgreSQL --> Redis --> Return
```

يمكن استخدام:

``` java
@Cacheable(value = "orders", key = "#id")
public OrderDto getOrder(Long id) {
    // ...
}
```

عند تحديث Order يجب منع stale cache باستخدام `@CacheEvict` أو تحديث
cache.

## 10. Kafka

عند إنشاء Order:

``` text
POST /orders
  |
PostgreSQL
  |
Kafka
  |
OrderCreated Event
```

Topic:

``` text
orders
```

Event:

``` json
{
  "eventType": "ORDER_CREATED",
  "eventId": "unique-id",
  "orderId": 100,
  "customerId": 5,
  "timestamp": "..."
}
```

Consumer:

``` java
@KafkaListener(topics = "orders")
public void consume(OrderCreatedEvent event) {
    // Notification / Audit
}
```

## 11. Kafka Idempotency

قد يصل نفس event أكثر من مرة.

``` text
ORDER_CREATED 100
ORDER_CREATED 100
```

لا تعالج الحدث مرتين.

استخدم `eventId` وسجل الأحداث المعالجة في Redis أو PostgreSQL.

## 12. WebFlux / Mono

استخدم WebClient للتعامل مع Product Service:

``` java
public Mono<Product> getProduct(Long id) {
    return webClient.get()
            .uri("/products/{id}", id)
            .retrieve()
            .bodyToMono(Product.class);
}
```

تدرب على: - Mono - map - flatMap

## 13. Error Handling

استخدم:

``` text
@ControllerAdvice
```

Response موحد:

``` json
{
  "timestamp": "...",
  "status": 404,
  "error": "ORDER_NOT_FOUND",
  "message": "Order 100 was not found"
}
```

غطِّ: - Validation Error - Authentication Error - Authorization Error -
Resource Not Found - Database Error - External Service Error

## 14. Resilience

Product Service قد يفشل.

طبق:

``` text
Timeout
Retry
Circuit Breaker
Fallback
```

التدفق:

``` text
Product Service
  |
Timeout
  |
Retry
  |
Circuit Breaker
  |
Fallback
```

استخدم Resilience4j.

## 15. Database / JPA

PostgreSQL.

Entities الأساسية:

``` text
User
Order
OrderItem
ProcessedEvent
OutboxEvent
```

يجب الانتباه إلى: - Lazy Loading - N+1 - Transactions - Optimistic
Locking

## 16. N+1 Challenge

قد ينتج:

``` text
SELECT * FROM orders;
SELECT * FROM order_items WHERE order_id = 1;
SELECT * FROM order_items WHERE order_id = 2;
...
```

حلها باستخدام: - JOIN FETCH - EntityGraph

## 17. Optimistic Locking

في Order:

``` java
@Version
private Long version;
```

الهدف اكتشاف التعديلات المتزامنة على نفس الطلب.

## 18. Transactions

إنشاء Order يجب أن يكون Local Transaction واحدة:

``` java
@Transactional
public Order createOrder(...) {
    // ...
}
```

``` text
Transaction
 |
 +-- Save Order
 +-- Save OrderItems
 +-- Save Outbox Event
 |
Commit
```

## 19. Outbox Pattern

لا تعتمد على:

``` text
DB Save
then
Kafka Send
```

استخدم:

``` text
Transaction
 |
 +-- Order
 +-- Outbox Event
 |
Commit
 |
Outbox Publisher
 |
Kafka
```

## 20. Docker

أنشئ `docker-compose.yml` لتشغيل:

``` text
PostgreSQL
Redis
Kafka
```

ويجب أن يعمل:

``` bash
docker compose up
```

ويستطيع Spring Boot الاتصال بالخدمات الثلاث.

## 21. Testing

### Unit Tests

استخدم: - JUnit 5 - Mockito

اختبر: - Create Order - Order Not Found - Invalid Quantity - Cancel
Order - Authorization Rules

### Integration Tests

استخدم Testcontainers لتشغيل: - PostgreSQL - Kafka - Redis

## 22. Observability

أضف Spring Boot Actuator:

``` text
/actuator/health
/actuator/metrics
```

وأضف Logging يحتوي على:

``` text
TraceId
CorrelationId
```

## 23. Security Requirements

يجب تطبيق: - Password Hashing - JWT - Role-Based Authorization - Input
Validation - SQL Injection Prevention - Secrets Management - Least
Privilege

لا تضع secrets داخل source code أو Git.

## 24. Git

تدرب على:

``` text
branch
commit
merge
rebase
cherry-pick
revert
reset
stash
bisect
```

## 25. Final Architecture

``` text
                         Client
                           |
                           v
                  +-----------------+
                  |  Spring Boot    |
                  |      API        |
                  +--------+--------+
                           |
          +----------------+------------------+
          |                |                  |
          v                v                  v
       Security          Service            Redis
       JWT/Roles           Layer             Cache
                           |
                 +---------+---------+
                 |                   |
                 v                   v
                JPA             WebClient
                 |                   |
                 v                   v
             PostgreSQL        Product API
                 |
                 v
               Outbox
                 |
                 v
               Kafka
                 |
                 v
              Consumer
```

# طريقة التدريب

لن أعطيك الحل كاملًا.

سنمشي بهذه الدورة:

``` text
Task
  |
Your Implementation
  |
Code Review
  |
Bug Detection
  |
Performance Review
  |
Security Review
  |
Architecture Review
  |
Next Task
```

وسأسألك أسئلة Senior مثل:

-   Why did you choose this approach?
-   What happens if Kafka is down?
-   What happens if Redis is unavailable?
-   Can this code produce N+1?
-   Is this code thread-safe?
-   What happens if the same Kafka event arrives twice?
-   How would you scale this to multiple instances?
-   Where is the transaction boundary?

# المرحلة الأولى --- Project Setup

## Task 1

أنشئ Spring Boot Project باستخدام:

``` text
Java 21
Spring Boot
Maven
```

Dependencies:

``` text
Spring Web
Spring Data JPA
Spring Security
OAuth2 Resource Server
Validation
PostgreSQL Driver
Redis
Kafka
Lombok (optional)
Actuator
```

أنشئ:

``` text
com.example.orders
├── config
├── controller
├── service
├── repository
├── entity
├── dto
├── security
├── kafka
├── cache
├── exception
└── mapper
```

وأنشئ:

``` text
docker-compose.yml
```

لتشغيل:

``` text
PostgreSQL
Redis
Kafka
```

## المطلوب الآن

لا تبدأ Business Logic أو Authentication.

أول هدف:

``` bash
docker compose up
```

يعمل بنجاح.

ثم اجعل Spring Boot قادرًا على الاتصال بـ:

``` text
PostgreSQL
Redis
Kafka
```

بعد ذلك جهز:

``` text
1. pom.xml
2. docker-compose.yml
3. application.yml
```

وسنراجعها كأنها Code Review لمهندس Senior.

إذا واجهتك أي مشكلة أو Error، أرسلها كما هي ولا تتجاوزها وحدك.

# Roadmap

``` text
01. Project Setup
02. Database + Entities
03. Authentication + JWT
04. Roles + Authorization
05. Order CRUD
06. Java Streams
07. Redis Cache
08. WebClient + Mono
09. Resilience4j
10. Kafka
11. Outbox Pattern
12. Kafka Idempotency
13. Transactions
14. Optimistic Locking
15. N+1 + Performance
16. Testing + Testcontainers
17. Observability
18. Dockerization
19. Production Review
20. Senior-Level Interview
```

**ابدأ بالمرحلة الأولى فقط. لا تنتقل للمرحلة الثانية حتى نراجع الـ
Setup.**

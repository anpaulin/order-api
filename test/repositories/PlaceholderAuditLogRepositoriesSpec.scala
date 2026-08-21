package repositories

import models.{EventType, Order, OrderEvent, TransactionType}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}

class PlaceholderAuditLogRepositoriesSpec extends PlaySpec with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))


  private def sampleEvent(
    id: UUID = UUID.randomUUID(),
    eventType: EventType = EventType.OrderCreated
  ): OrderEvent = OrderEvent(
    eventType = eventType,
    order = Order(
      id = id,
      date = OffsetDateTime.now(),
      amount = BigDecimal(200),
      currencyCode = Currency.getInstance("USD"),
      transactionType = TransactionType.Sale
    ),
    timestamp = Instant.now()
  )

  "KafkaAuditLogRepository" should {

    "append and readAll events in simulated topic" in {
      val config = Configuration(
        "app.audit-log.kafka.bootstrap-servers" -> "localhost:9092",
        "app.audit-log.kafka.topic" -> "test-orders"
      )
      val repo = new KafkaAuditLogRepository(config)
      repo.readAll().futureValue mustBe empty

      val event = sampleEvent()
      repo.append(event).futureValue
      repo.readAll().futureValue must have size 1
      repo.readAll().futureValue.head.order.id mustBe event.order.id
    }
  }

  "MySqlAuditLogRepository" should {

    "append and readAll events in simulated table" in {
      val config = Configuration(
        "app.audit-log.mysql.url" -> "jdbc:mysql://localhost:3306/test_db",
        "app.audit-log.mysql.table" -> "test_audit_logs"
      )
      val repo = new MySqlAuditLogRepository(config)
      repo.readAll().futureValue mustBe empty

      val event = sampleEvent()
      repo.append(event).futureValue
      repo.readAll().futureValue must have size 1
      repo.readAll().futureValue.head.order.id mustBe event.order.id
    }
  }



  "Module configuration-driven binding" should {

    "bind KafkaAuditLogRepository when type is 'kafka'" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.audit-log.type" -> "kafka")
        .build()

      val repo = app.injector.instanceOf[AuditLogRepository]
      repo mustBe a[KafkaAuditLogRepository]
    }

    "bind MySqlAuditLogRepository when type is 'mysql'" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.audit-log.type" -> "mysql")
        .build()

      val repo = app.injector.instanceOf[AuditLogRepository]
      repo mustBe a[MySqlAuditLogRepository]
    }

    "bind InMemoryAuditLogRepository when type is 'memory'" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.audit-log.type" -> "memory")
        .build()

      val repo = app.injector.instanceOf[AuditLogRepository]
      repo mustBe a[InMemoryAuditLogRepository]
    }

    "bind FileAuditLogRepository by default" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.audit-log.file-path" -> "./data/test-default.log")
        .build()

      val repo = app.injector.instanceOf[AuditLogRepository]
      repo mustBe a[FileAuditLogRepository]
    }
  }
}

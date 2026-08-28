package repositories

import models.{EventType, Order, OrderEvent, TransactionType}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}

class PlaceholderEventStoreRepositoriesSpec extends PlaySpec with ScalaFutures {

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

  "KafkaEventStoreRepository" should {

    "append and readAll events in simulated topic" in {
      val config = Configuration(
        "app.event-store.kafka.bootstrap-servers" -> "localhost:9092",
        "app.event-store.kafka.topic" -> "test-orders"
      )
      val repo = new KafkaEventStoreRepository(config)
      repo.readAll().futureValue mustBe empty

      val event = sampleEvent()
      repo.append(event).futureValue
      repo.readAll().futureValue must have size 1
      repo.readAll().futureValue.head.order.id mustBe event.order.id
    }
  }

  "MySqlEventStoreRepository" should {

    "append and readAll events in simulated table" in {
      val config = Configuration(
        "app.event-store.mysql.url" -> "jdbc:mysql://localhost:3306/test_db",
        "app.event-store.mysql.table" -> "test_event_store"
      )
      val repo = new MySqlEventStoreRepository(config)
      repo.readAll().futureValue mustBe empty

      val event = sampleEvent()
      repo.append(event).futureValue
      repo.readAll().futureValue must have size 1
      repo.readAll().futureValue.head.order.id mustBe event.order.id
    }
  }

  "Module configuration-driven binding" should {

    "bind KafkaEventStoreRepository when type is 'kafka'" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.event-store.type" -> "kafka")
        .build()

      val repo = app.injector.instanceOf[EventStoreRepository]
      repo mustBe a[KafkaEventStoreRepository]
    }

    "bind MySqlEventStoreRepository when type is 'mysql'" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.event-store.type" -> "mysql")
        .build()

      val repo = app.injector.instanceOf[EventStoreRepository]
      repo mustBe a[MySqlEventStoreRepository]
    }

    "bind InMemoryEventStoreRepository when type is 'memory'" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.event-store.type" -> "memory")
        .build()

      val repo = app.injector.instanceOf[EventStoreRepository]
      repo mustBe a[InMemoryEventStoreRepository]
    }

    "bind FileEventStoreRepository by default" in {
      val app = new GuiceApplicationBuilder()
        .configure("app.event-store.file-path" -> "./data/test-default.log")
        .build()

      val repo = app.injector.instanceOf[EventStoreRepository]
      repo mustBe a[FileEventStoreRepository]
    }
  }
}

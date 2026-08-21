package repositories

import models.{EventType, Order, OrderEvent, TransactionType}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.PlaySpec

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}

class InMemoryAuditLogRepositorySpec extends PlaySpec with ScalaFutures {

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
      amount = BigDecimal(100),
      currencyCode = Currency.getInstance("USD"),
      transactionType = TransactionType.Sale
    ),
    timestamp = Instant.now()
  )

  "InMemoryAuditLogRepository" should {

    "start empty" in {
      val repo = new InMemoryAuditLogRepository()
      repo.readAll().futureValue mustBe empty
    }

    "append and read back events in order" in {
      val repo = new InMemoryAuditLogRepository()
      val event1 = sampleEvent()
      val event2 = sampleEvent(eventType = EventType.OrderUpdated)

      repo.append(event1).futureValue
      repo.append(event2).futureValue

      val events = repo.readAll().futureValue
      events must have size 2
      events.head.order.id mustBe event1.order.id
      events.last.order.id mustBe event2.order.id
    }

    "clear all recorded events" in {
      val repo = new InMemoryAuditLogRepository()
      repo.append(sampleEvent()).futureValue
      repo.readAll().futureValue must have size 1

      repo.clear().futureValue
      repo.readAll().futureValue mustBe empty
    }
  }
}


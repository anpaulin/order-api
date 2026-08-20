package repositories

import models.{EventType, Order, OrderEvent, TransactionType}
import org.scalatestplus.play.PlaySpec

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}

class InMemoryAuditLogRepositorySpec extends PlaySpec {

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
      repo.readAll() mustBe empty
    }

    "append and read back events in order" in {
      val repo = new InMemoryAuditLogRepository()
      val event1 = sampleEvent()
      val event2 = sampleEvent(eventType = EventType.OrderUpdated)

      repo.append(event1)
      repo.append(event2)

      val events = repo.readAll()
      events must have size 2
      events.head.order.id mustBe event1.order.id
      events.last.order.id mustBe event2.order.id
    }

    "clear all recorded events" in {
      val repo = new InMemoryAuditLogRepository()
      repo.append(sampleEvent())
      repo.readAll() must have size 1

      repo.clear()
      repo.readAll() mustBe empty
    }
  }
}

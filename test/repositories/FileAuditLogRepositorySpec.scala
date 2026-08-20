package repositories

import models.{EventType, Order, OrderEvent, TransactionType}
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import java.nio.file.{Files, Paths}
import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}

class FileAuditLogRepositorySpec extends PlaySpec {

  private val testFilePath = "./data/test-file-audit.log"

  private def sampleEvent(
    id: UUID = UUID.randomUUID(),
    eventType: EventType = EventType.OrderCreated
  ): OrderEvent = OrderEvent(
    eventType = eventType,
    order = Order(
      id = id,
      date = OffsetDateTime.now(),
      amount = BigDecimal(150),
      currencyCode = Currency.getInstance("CAD"),
      transactionType = TransactionType.Sale
    ),
    timestamp = Instant.now()
  )

  private def createRepo(): FileAuditLogRepository = {
    val config = Configuration("app.audit-log.file-path" -> testFilePath)
    new FileAuditLogRepository(config)
  }

  "FileAuditLogRepository" should {

    "create file if it does not exist" in {
      val path = Paths.get(testFilePath)
      Files.deleteIfExists(path)

      val repo = createRepo()
      Files.exists(path) mustBe true
      repo.clear()
    }

    "append and read back persisted events" in {
      val repo = createRepo()
      repo.clear()

      val event1 = sampleEvent()
      val event2 = sampleEvent(eventType = EventType.OrderUpdated)

      repo.append(event1)
      repo.append(event2)

      val events = repo.readAll()
      events must have size 2
      events.head.order.id mustBe event1.order.id
      events.last.order.id mustBe event2.order.id

      repo.clear()
    }

    "clear file contents" in {
      val repo = createRepo()
      repo.append(sampleEvent())
      repo.readAll() must not be empty

      repo.clear()
      repo.readAll() mustBe empty
    }
  }
}

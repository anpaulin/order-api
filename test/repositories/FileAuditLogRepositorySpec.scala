package repositories

import models.{EventType, Order, OrderEvent, TransactionType}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import java.nio.file.{Files, Paths}
import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}
import scala.concurrent.ExecutionContext.Implicits.global

class FileAuditLogRepositorySpec extends PlaySpec with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

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
      repo.clear().futureValue
    }

    "append and read back persisted events" in {
      val repo = createRepo()
      repo.clear().futureValue

      val event1 = sampleEvent()
      val event2 = sampleEvent(eventType = EventType.OrderUpdated)

      repo.append(event1).futureValue
      repo.append(event2).futureValue

      val events = repo.readAll().futureValue
      events must have size 2
      events.head.order.id mustBe event1.order.id
      events.last.order.id mustBe event2.order.id

      repo.clear().futureValue
    }

    "clear file contents" in {
      val repo = createRepo()
      repo.append(sampleEvent()).futureValue
      repo.readAll().futureValue must not be empty

      repo.clear().futureValue
      repo.readAll().futureValue mustBe empty
    }
  }
}


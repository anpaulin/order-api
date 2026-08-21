package services

import models.*
import repositories.AuditLogRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import play.api.Configuration

import java.time.OffsetDateTime
import java.util.{Currency, UUID}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class InMemoryOrderServiceSpec extends PlaySpec with MockitoSugar with BeforeAndAfterEach with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  private var audit: AuditLogRepository = _

  private var service: InMemoryOrderService = _

  override def beforeEach(): Unit = {
    audit = mock[AuditLogRepository]
    when(audit.readAll()).thenReturn(Future.successful(List.empty))
    when(audit.append(any[OrderEvent])).thenReturn(Future.successful(()))


    val config = Configuration(
      "app.audit-log.file-path" -> "./data/test-audit.log",
      "app.startup.replay-audit" -> false
    )
    service = new InMemoryOrderService(audit, config)
  }

  private def sampleOrder(
    amount: BigDecimal = BigDecimal(10),
    currency: String = "USD",
    txType: TransactionType = TransactionType.Sale,
    date: OffsetDateTime = OffsetDateTime.now()
  ): Order = Order(
    id              = null,
    date            = date,
    amount          = amount,
    currencyCode    = Currency.getInstance(currency),
    transactionType = txType
  )

  "InMemoryOrderService" should {

    "assign an ID and persist on create" in {
      val created = service.create(sampleOrder()).futureValue

      created.id must not be null
      verify(audit, times(1)).append(any[OrderEvent])
    }

    "modify existing values on update" in {
      val created = service.create(sampleOrder(amount = BigDecimal(1))).futureValue

      val patch = UpdateOrderRequest(amount = Some(BigDecimal(25)))
      val result = service.update(created.id, patch).futureValue

      result mustBe a[Right[_, _]]
      result.toOption.get.amount mustBe BigDecimal(25)
    }

    "return filtered results when searching by currency" in {
      service.create(sampleOrder(currency = "USD")).futureValue
      service.create(sampleOrder(currency = "CAD", txType = TransactionType.Refund)).futureValue

      val usdOrders = service.search(
        currency = Some(Currency.getInstance("USD")),
        txType   = None,
        start    = None,
        end      = None
      ).futureValue

      usdOrders must have size 1
      usdOrders.head.currencyCode mustBe Currency.getInstance("USD")
    }

    "return only orders within date range" in {
      val now = OffsetDateTime.now()
      service.create(sampleOrder(date = now.minusDays(10))).futureValue
      val recent = service.create(sampleOrder(date = now.minusDays(1), txType = TransactionType.Refund)).futureValue

      val results = service.search(
        currency = None,
        txType   = None,
        start    = Some(now.minusDays(5)),
        end      = Some(now)
      ).futureValue

      results must have size 1
      results.head.id mustBe recent.id
    }

    "remove order from state on delete" in {
      val o = service.create(sampleOrder()).futureValue

      service.search(None, None, None, None).futureValue must not be empty

      service.delete(o.id).futureValue mustBe a[Right[_, _]]

      service.search(None, None, None, None).futureValue mustBe empty
    }

    "remove only the correct order on delete" in {
      val o1 = service.create(sampleOrder(currency = "USD")).futureValue
      val o2 = service.create(sampleOrder(currency = "CAD", txType = TransactionType.Refund)).futureValue

      service.delete(o1.id).futureValue

      val remaining = service.search(None, None, None, None).futureValue
      remaining must have size 1
      remaining.head.currencyCode mustBe Currency.getInstance("CAD")
    }

    "not modify in-memory state if audit logging fails on create" in {
      when(audit.append(any[OrderEvent])).thenReturn(Future.failed(new RuntimeException("Disk full")))

      val failure = service.create(sampleOrder()).failed.futureValue
      failure.getMessage must include("Disk full")

      service.search(None, None, None, None).futureValue mustBe empty
    }

    "not modify in-memory state if audit logging fails on update" in {
      val order = service.create(sampleOrder(amount = BigDecimal(50))).futureValue
      when(audit.append(any[OrderEvent])).thenReturn(Future.failed(new RuntimeException("Audit failure")))

      val failure = service.update(order.id, UpdateOrderRequest(amount = Some(BigDecimal(999)))).failed.futureValue
      failure.getMessage must include("Audit failure")

      service.get(order.id).futureValue.get.amount mustBe BigDecimal(50)
    }

    "not modify in-memory state if audit logging fails on delete" in {
      val order = service.create(sampleOrder()).futureValue
      when(audit.append(any[OrderEvent])).thenReturn(Future.failed(new RuntimeException("Audit failure")))

      val failure = service.delete(order.id).failed.futureValue
      failure.getMessage must include("Audit failure")

      service.get(order.id).futureValue mustBe defined
    }
  }
}



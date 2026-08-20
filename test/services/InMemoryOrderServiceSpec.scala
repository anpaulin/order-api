package services

import models.*
import repositories.AuditLogRepository
import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import play.api.Configuration

import java.time.OffsetDateTime
import java.util.{Currency, UUID}

class InMemoryOrderServiceSpec extends PlaySpec with MockitoSugar with BeforeAndAfterEach {

  private var audit: AuditLogRepository = _
  private var service: InMemoryOrderService = _

  override def beforeEach(): Unit = {
    audit = mock[AuditLogRepository]
    when(audit.readAll()).thenReturn(List.empty)

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
      val created = service.create(sampleOrder())

      created.id must not be null
      verify(audit, times(1)).append(any[OrderEvent])
    }

    "modify existing values on update" in {
      val created = service.create(sampleOrder(amount = BigDecimal(1)))

      val patch = UpdateOrderRequest(amount = Some(BigDecimal(25)))
      val result = service.update(created.id, patch)


      result mustBe a[Right[_, _]]
      result.toOption.get.amount mustBe BigDecimal(25)
    }

    "return filtered results when searching by currency" in {
      service.create(sampleOrder(currency = "USD"))
      service.create(sampleOrder(currency = "CAD", txType = TransactionType.Refund))

      val usdOrders = service.search(
        currency = Some(Currency.getInstance("USD")),
        txType   = None,
        start    = None,
        end      = None
      )

      usdOrders must have size 1
      usdOrders.head.currencyCode mustBe Currency.getInstance("USD")
    }

    "return only orders within date range" in {
      val now = OffsetDateTime.now()
      service.create(sampleOrder(date = now.minusDays(10)))
      val recent = service.create(sampleOrder(date = now.minusDays(1), txType = TransactionType.Refund))

      val results = service.search(
        currency = None,
        txType   = None,
        start    = Some(now.minusDays(5)),
        end      = Some(now)
      )

      results must have size 1
      results.head.id mustBe recent.id
    }

    "remove order from state on delete" in {
      val o = service.create(sampleOrder())

      service.search(None, None, None, None) must not be empty

      service.delete(o.id) mustBe a[Right[_, _]]

      service.search(None, None, None, None) mustBe empty
    }

    "remove only the correct order on delete" in {
      val o1 = service.create(sampleOrder(currency = "USD"))
      val o2 = service.create(sampleOrder(currency = "CAD", txType = TransactionType.Refund))

      service.delete(o1.id)

      val remaining = service.search(None, None, None, None)
      remaining must have size 1
      remaining.head.currencyCode mustBe Currency.getInstance("CAD")
    }

    "not modify in-memory state if audit logging fails on create" in {
      doThrow(new RuntimeException("Disk full")).when(audit).append(any[OrderEvent])

      an[RuntimeException] must be thrownBy {
        service.create(sampleOrder())
      }

      service.search(None, None, None, None) mustBe empty
    }

    "not modify in-memory state if audit logging fails on update" in {
      val order = service.create(sampleOrder(amount = BigDecimal(50)))
      doThrow(new RuntimeException("Audit failure")).when(audit).append(any[OrderEvent])

      an[RuntimeException] must be thrownBy {
        service.update(order.id, UpdateOrderRequest(amount = Some(BigDecimal(999))))
      }


      service.get(order.id).get.amount mustBe BigDecimal(50)
    }


    "not modify in-memory state if audit logging fails on delete" in {
      val order = service.create(sampleOrder())
      doThrow(new RuntimeException("Audit failure")).when(audit).append(any[OrderEvent])

      an[RuntimeException] must be thrownBy {
        service.delete(order.id)
      }

      service.get(order.id) mustBe defined
    }
  }
}


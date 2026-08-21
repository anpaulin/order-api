package controllers

import models.*
import services.OrderService
import org.scalatestplus.play.*


import org.scalatestplus.play.guice.*
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.*
import play.api.test.*
import play.api.test.Helpers.*



import java.time.OffsetDateTime
import java.util.{Currency, UUID}
import scala.concurrent.Future

class OrderControllerSpec extends PlaySpec with MockitoSugar {


  private def buildApp(svc: OrderService): Application =
    new GuiceApplicationBuilder()
      .overrides(bind[OrderService].toInstance(svc))
      .configure(
        "app.audit-log.file-path" -> "./data/test-audit.log",
        "app.startup.replay-audit" -> false
      )
      .build()

  private def mockOrder(
    id: UUID = UUID.randomUUID(),
    amount: BigDecimal = BigDecimal(50),
    currency: String = "USD",
    txType: TransactionType = TransactionType.Sale
  ): Order = Order(
    id              = id,
    date            = OffsetDateTime.now(),
    amount          = amount,
    currencyCode    = Currency.getInstance(currency),
    transactionType = txType
  )

  "OrderController POST /orders" should {

    "return BadRequest for invalid transactionType" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(POST, "/orders")
          .withJsonBody(Json.obj(
            "amount" -> 50,
            "currencyCode" -> "USD",
            "transactionType" -> "BLEH"
          ))

        val result = route(app, request).get
        status(result) mustBe BAD_REQUEST
        (contentAsJson(result) \ "error").as[String] mustBe "ValidationError"
      }
    }

    "return Created for valid request" in {
      val svc = mock[OrderService]
      val order = mockOrder()
      when(svc.create(any[Order])).thenReturn(Future.successful(order))

      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(POST, "/orders")
          .withJsonBody(Json.obj(
            "amount" -> 50,
            "currencyCode" -> "USD",
            "transactionType" -> "Sale"
          ))

        val result = route(app, request).get
        status(result) mustBe CREATED
        header("Location", result) mustBe defined
        (contentAsJson(result) \ "id").as[String] mustBe order.id.toString
        (contentAsJson(result) \ "transactionType").as[String] mustBe "Sale"
        (contentAsJson(result) \ "currencyCode").as[String] mustBe "USD"
      }
    }


    "return BadRequest for invalid currency code" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(POST, "/orders")
          .withJsonBody(Json.obj(
            "amount" -> 100,
            "currencyCode" -> "INVALID",
            "transactionType" -> "Sale"
          ))

        val result = route(app, request).get
        status(result) mustBe BAD_REQUEST
        (contentAsJson(result) \ "error").as[String] mustBe "ValidationError"
      }
    }

    "return BadRequest for missing transactionType" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(POST, "/orders")
          .withJsonBody(Json.obj(
            "amount" -> 100,
            "currencyCode" -> "USD"
          ))

        val result = route(app, request).get
        status(result) mustBe BAD_REQUEST
        val json = contentAsJson(result)
        (json \ "error").as[String] mustBe "ValidationError"
        (json \ "details").as[Seq[JsObject]] must not be empty
      }
    }

    "return BadRequest with multiple validation errors in details when multiple fields are missing" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(POST, "/orders")
          .withJsonBody(Json.obj())

        val result = route(app, request).get
        status(result) mustBe BAD_REQUEST
        val json = contentAsJson(result)
        (json \ "error").as[String] mustBe "ValidationError"
        (json \ "message").as[String] must include("Validation failed")
        val details = (json \ "details").as[Seq[JsObject]]
        details.map(d => (d \ "field").as[String]) must contain allOf ("amount", "currencyCode", "transactionType")
      }
    }


    "return BadRequest with message for invalid transactionType" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(POST, "/orders")
          .withJsonBody(Json.obj(
            "amount" -> 100,
            "currencyCode" -> "USD",
            "transactionType" -> "INVALID_TYPE"
          ))

        val result = route(app, request).get
        status(result) mustBe BAD_REQUEST
        (contentAsJson(result) \ "error").as[String] mustBe "ValidationError"
        (contentAsJson(result) \ "message").as[String] must include("INVALID_TYPE")
      }
    }
  }

  "OrderController GET /orders/:id" should {

    "return Ok for existing order" in {
      val svc = mock[OrderService]
      val id = UUID.randomUUID()
      val order = mockOrder(id = id)
      when(svc.get(id)).thenReturn(Future.successful(Some(order)))

      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET, s"/orders/$id")).get
        status(result) mustBe OK
        (contentAsJson(result) \ "id").as[String] mustBe id.toString
        (contentAsJson(result) \ "currencyCode").as[String] mustBe "USD"
      }
    }

    "return NotFound for missing order" in {
      val svc = mock[OrderService]
      val id = UUID.randomUUID()
      when(svc.get(id)).thenReturn(Future.successful(None))

      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET, s"/orders/$id")).get
        status(result) mustBe NOT_FOUND
      }
    }
  }

  "OrderController PATCH /orders/:id" should {

    "return Ok for valid update" in {
      val svc = mock[OrderService]
      val id = UUID.randomUUID()
      val updated = mockOrder(id = id, amount = BigDecimal("99.99"), currency = "EUR", txType = TransactionType.Refund)
      when(svc.update(org.mockito.ArgumentMatchers.eq(id), any[UpdateOrderRequest])).thenReturn(Future.successful(Right(updated)))


      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(PATCH, s"/orders/$id")
          .withJsonBody(Json.obj(
            "amount" -> 99.99,
            "currencyCode" -> "EUR",
            "transactionType" -> "Refund"
          ))

        val result = route(app, request).get
        status(result) mustBe OK
        (contentAsJson(result) \ "currencyCode").as[String] mustBe "EUR"
        (contentAsJson(result) \ "transactionType").as[String] mustBe "Refund"
      }
    }

    "return BadRequest with multiple validation errors in details for invalid patch fields" in {
      val svc = mock[OrderService]
      val id = UUID.randomUUID()
      val app = buildApp(svc)
      running(app) {
        val request = FakeRequest(PATCH, s"/orders/$id")
          .withJsonBody(Json.obj(
            "currencyCode" -> "INVALID_CURRENCY",
            "transactionType" -> "INVALID_TYPE"
          ))

        val result = route(app, request).get
        status(result) mustBe BAD_REQUEST
        val json = contentAsJson(result)
        (json \ "error").as[String] mustBe "ValidationError"
        val details = (json \ "details").as[Seq[JsObject]]
        details.map(d => (d \ "field").as[String]) must contain allOf ("currencyCode", "transactionType")
      }
    }

  }

  "OrderController DELETE /orders/:id" should {

    "return NoContent on successful delete" in {
      val svc = mock[OrderService]
      val id = UUID.randomUUID()
      when(svc.delete(id)).thenReturn(Future.successful(Right(())))

      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(DELETE, s"/orders/$id")).get
        status(result) mustBe NO_CONTENT
        verify(svc).delete(id)
      }
    }
  }

  "OrderController GET /orders/search" should {

    "return BadRequest for invalid date range" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET,
          "/orders/search?startDate=2025-11-11T10:00:00Z&endDate=2025-11-10T10:00:00Z"
        )).get
        status(result) mustBe BAD_REQUEST
        (contentAsJson(result) \ "message").as[String] mustBe "startDate must be before endDate"
      }
    }

    "return Ok with missing startDate" in {
      val svc = mock[OrderService]
      when(svc.search(any, any, any, any)).thenReturn(Future.successful(List.empty))

      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET, "/orders/search?endDate=2025-11-10T10:00:00Z")).get
        status(result) mustBe OK
      }
    }

    "return Ok with missing endDate" in {
      val svc = mock[OrderService]
      when(svc.search(any, any, any, any)).thenReturn(Future.successful(List.empty))

      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET, "/orders/search?startDate=2025-11-10T10:00:00Z")).get
        status(result) mustBe OK
      }
    }


    "return BadRequest for malformed date" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET,
          "/orders/search?startDate=2025-11-XXT10:00:00Z&endDate=2025-11-11T10:00:00Z"
        )).get
        status(result) mustBe BAD_REQUEST
        (contentAsJson(result) \ "message").as[String] must include("ISO-8601")
      }
    }

    "return BadRequest with multiple validation error details when multiple query params are invalid" in {
      val svc = mock[OrderService]
      val app = buildApp(svc)
      running(app) {
        val result = route(app, FakeRequest(GET,
          "/orders/search?currencyCode=INVALID&transactionType=UNKNOWN&startDate=BAD_DATE"
        )).get
        status(result) mustBe BAD_REQUEST
        val json = contentAsJson(result)
        (json \ "error").as[String] mustBe "ValidationError"
        val details = (json \ "details").as[Seq[JsObject]]
        details.map(d => (d \ "field").as[String]) must contain allOf ("currencyCode", "transactionType", "startDate")
      }
    }
  }
}




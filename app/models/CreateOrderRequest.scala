package models

import play.api.libs.json.*

import java.time.OffsetDateTime
import java.util.Currency

/** Request body for creating a new order. All fields except `date` are required. */
case class CreateOrderRequest(
  date: Option[OffsetDateTime] = None,
  amount: BigDecimal,
  currencyCode: Currency,
  transactionType: TransactionType
)

object CreateOrderRequest {
  import JsonFormats.given
  given Reads[CreateOrderRequest] = Json.reads[CreateOrderRequest]
}

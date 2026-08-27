package models

import play.api.libs.json.*

import java.time.OffsetDateTime
import java.util.Currency

/** Request body for patching an existing order. All fields are optional. */
case class UpdateOrderRequest(
  date: Option[OffsetDateTime] = None,
  amount: Option[BigDecimal] = None,
  currencyCode: Option[Currency] = None,
  transactionType: Option[TransactionType] = None
)

object UpdateOrderRequest {
  import JsonFormats.given
  given Reads[UpdateOrderRequest] = Json.reads[UpdateOrderRequest]
}

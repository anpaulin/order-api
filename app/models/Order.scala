package models

import play.api.libs.json.*

import java.time.OffsetDateTime
import java.util.{Currency, UUID}

case class Order(
  id: UUID,
  date: OffsetDateTime,
  amount: BigDecimal,
  currencyCode: Currency,
  transactionType: TransactionType
)

object Order {
  import JsonFormats.given
  given Format[Order] = Json.format[Order]
}

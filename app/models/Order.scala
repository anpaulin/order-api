package models

import play.api.libs.json.*

import java.time.OffsetDateTime
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.{Currency, UUID}

/** Shared parsing functions with consistent validation error messages. */
object Parsers {

  def parseUUID(s: String): Either[String, UUID] =
    try Right(UUID.fromString(s))
    catch {
      case _: IllegalArgumentException =>
        Left(s"Invalid UUID format: '$s'")
    }

  def parseDate(s: String): Either[String, OffsetDateTime] =
    try Right(OffsetDateTime.parse(s))
    catch {
      case _: DateTimeParseException =>
        Left(s"Invalid date format '$s'. Expected ISO-8601, e.g., 2025-11-10T10:00:00Z.")
    }

  def parseCurrency(s: String): Either[String, Currency] =
    try Right(Currency.getInstance(s.toUpperCase))
    catch {
      case _: IllegalArgumentException =>
        Left(s"Invalid currency code '$s'. Must follow ISO 4217 (e.g., USD, EUR, CAD).")
    }
}

/** Shared JSON format instances for common Java types used across models. */
object JsonFormats {

  given Format[UUID] = Format(
    Reads(_.validate[String].flatMap(s => Parsers.parseUUID(s).fold(JsError(_), JsSuccess(_)))),
    Writes(u => JsString(u.toString))
  )

  given Format[OffsetDateTime] = Format(
    Reads(_.validate[String].flatMap(s => Parsers.parseDate(s).fold(JsError(_), JsSuccess(_)))),
    Writes(dt => JsString(dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
  )

  given Format[Currency] = Format(
    Reads(_.validate[String].flatMap(s => Parsers.parseCurrency(s).fold(JsError(_), JsSuccess(_)))),
    Writes(c => JsString(c.getCurrencyCode))
  )
}


case class Order(
  id: UUID,
  date: OffsetDateTime,
  amount: BigDecimal,
  currencyCode: Currency,
  transactionType: TransactionType
)

/** Request body for creating a new order. All fields except `date` are required. */
case class CreateOrderRequest(
  date: Option[OffsetDateTime] = None,
  amount: BigDecimal,
  currencyCode: Currency,
  transactionType: TransactionType
) {
  def toOrder: Order = Order(
    id              = null,
    date            = date.orNull,
    amount          = amount,
    currencyCode    = currencyCode,
    transactionType = transactionType
  )
}

/** Request body for patching an existing order. All fields are optional. */
case class UpdateOrderRequest(
  date: Option[OffsetDateTime] = None,
  amount: Option[BigDecimal] = None,
  currencyCode: Option[Currency] = None,
  transactionType: Option[TransactionType] = None
)


// --- JSON codecs ---

object Order {
  import JsonFormats.given
  given Format[Order] = Json.format[Order]
}

object CreateOrderRequest {
  import JsonFormats.given
  given Reads[CreateOrderRequest] = Json.reads[CreateOrderRequest]
}

object UpdateOrderRequest {
  import JsonFormats.given
  given Reads[UpdateOrderRequest] = Json.reads[UpdateOrderRequest]
}



package models

import play.api.libs.json.*

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.{Currency, UUID}

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

package models

import play.api.libs.json.*
import play.api.mvc.QueryStringBindable

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * Strongly-typed DTO for order search queries.
 * Validates currency code, transaction type, ISO-8601 dates, and date range before reaching the controller.
 */
case class OrderSearchQuery(
  currencyCode: Option[Currency] = None,
  transactionType: Option[TransactionType] = None,
  startDate: Option[OffsetDateTime] = None,
  endDate: Option[OffsetDateTime] = None
)

object OrderSearchQuery {

  implicit def queryStringBindable: QueryStringBindable[OrderSearchQuery] =
    new QueryStringBindable[OrderSearchQuery] {

      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, OrderSearchQuery]] = {
        val currencyParam = params.get("currencyCode").flatMap(_.headOption).filter(_.nonEmpty)
        val txTypeParam   = params.get("transactionType").flatMap(_.headOption).filter(_.nonEmpty)
        val startParam    = params.get("startDate").flatMap(_.headOption).filter(_.nonEmpty)
        val endParam      = params.get("endDate").flatMap(_.headOption).filter(_.nonEmpty)

        val curResult   = currencyParam.map(parseCurrency)
        val txResult    = txTypeParam.map(TransactionType.fromString)
        val startResult = startParam.map(parseDate)
        val endResult   = endParam.map(parseDate)

        val paramErrors: Seq[JsObject] = Seq(
          curResult.filter(_.isLeft).flatMap(_.left.toOption).map(msg => Json.obj("field" -> "currencyCode", "message" -> msg)),
          txResult.filter(_.isLeft).flatMap(_.left.toOption).map(msg => Json.obj("field" -> "transactionType", "message" -> msg)),
          startResult.filter(_.isLeft).flatMap(_.left.toOption).map(msg => Json.obj("field" -> "startDate", "message" -> msg)),
          endResult.filter(_.isLeft).flatMap(_.left.toOption).map(msg => Json.obj("field" -> "endDate", "message" -> msg))
        ).flatten

        val dateRangeError: Option[JsObject] = for {
          s <- startResult.flatMap(_.toOption)
          e <- endResult.flatMap(_.toOption)
          if s.isAfter(e)
        } yield Json.obj("field" -> "startDate", "message" -> "startDate must be before endDate")

        val allErrors = paramErrors ++ dateRangeError.toSeq

        if (allErrors.nonEmpty) {
          val summary = if (allErrors.size == 1) (allErrors.head \ "message").as[String]
                        else s"Validation failed for ${allErrors.size} query parameters"
          val errJson = Json.obj(
            "timestamp" -> OffsetDateTime.now().toString,
            "error"     -> "ValidationError",
            "message"   -> summary,
            "details"   -> allErrors
          )
          Some(Left(Json.stringify(errJson)))
        } else {
          Some(Right(OrderSearchQuery(
            currencyCode    = curResult.flatMap(_.toOption),
            transactionType = txResult.flatMap(_.toOption),
            startDate       = startResult.flatMap(_.toOption),
            endDate         = endResult.flatMap(_.toOption)
          )))
        }
      }

      override def unbind(key: String, value: OrderSearchQuery): String = {
        val params = Seq(
          value.currencyCode.map(c => s"currencyCode=${c.getCurrencyCode}"),
          value.transactionType.map(t => s"transactionType=$t"),
          value.startDate.map(s => s"startDate=$s"),
          value.endDate.map(e => s"endDate=$e")
        ).flatten
        params.mkString("&")
      }

      private def parseCurrency(code: String): Either[String, Currency] = {
        try {
          Right(Currency.getInstance(code.toUpperCase))
        } catch {
          case _: IllegalArgumentException =>
            Left(s"Invalid currency code '$code'. Must follow ISO 4217 (e.g., USD, EUR, CAD).")
        }
      }

      private def parseDate(s: String): Either[String, OffsetDateTime] = {
        try {
          Right(OffsetDateTime.parse(s))
        } catch {
          case _: DateTimeParseException =>
            Left("Invalid date format. Expected ISO-8601, e.g., 2025-11-10T10:00:00Z.")
        }
      }
    }
}

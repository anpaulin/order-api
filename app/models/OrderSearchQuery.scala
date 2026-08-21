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
        val curResult   = parseParam(params, "currencyCode", parseCurrency)
        val txResult    = parseParam(params, "transactionType", TransactionType.fromString)
        val startResult = parseParam(params, "startDate", parseDate)
        val endResult   = parseParam(params, "endDate", parseDate)

        val paramErrors = Seq(curResult, txResult, startResult, endResult).flatMap(_.left.toOption)

        val dateRangeError: Option[JsObject] = for {
          s <- startResult.toOption.flatten
          e <- endResult.toOption.flatten
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
            currencyCode    = curResult.toOption.flatten,
            transactionType = txResult.toOption.flatten,
            startDate       = startResult.toOption.flatten,
            endDate         = endResult.toOption.flatten
          )))
        }
      }

      private def parseParam[T](
        params: Map[String, Seq[String]],
        key: String,
        parser: String => Either[String, T]
      ): Either[JsObject, Option[T]] = {
        params.get(key).flatMap(_.headOption).filter(_.nonEmpty) match {
          case Some(raw) => parser(raw).left.map(msg => Json.obj("field" -> key, "message" -> msg)).map(Some(_))
          case None      => Right(None)
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

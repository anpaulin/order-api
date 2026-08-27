package models

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
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

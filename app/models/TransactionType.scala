package models

import play.api.libs.json.*

enum TransactionType {
  case Sale, Refund
}

object TransactionType {

  def fromString(value: String): Either[String, TransactionType] = {
    values.find(_.toString.equalsIgnoreCase(value))
      .toRight(s"Invalid transactionType '$value'. Must be one of: ${values.mkString(", ")}")
  }

  given Format[TransactionType] = Format(
    Reads { json =>
      json.validate[String].flatMap { s =>
        fromString(s).fold(
          err => JsError(err),
          tt  => JsSuccess(tt)
        )
      }
    },
    Writes(tt => JsString(tt.toString))
  )
}


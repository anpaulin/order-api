package models

import play.api.libs.json.*

enum TransactionType {
  case Sale, Refund
}

object TransactionType {

  private val lookup: Map[String, TransactionType] = values.map(v => v.toString.toLowerCase -> v).toMap

  def fromString(value: String): Either[String, TransactionType] = {
    lookup.get(value.toLowerCase)
      .toRight(s"Invalid transactionType '$value'. Must be one of: ${values.mkString(", ")}")
  }

  given Format[TransactionType] = Format(
    Reads(_.validate[String].flatMap(s => fromString(s).fold(JsError(_), JsSuccess(_)))),
    Writes(tt => JsString(tt.toString))
  )
}



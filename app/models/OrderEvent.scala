package models

import play.api.libs.json.*

import java.time.Instant

case class OrderEvent(
  eventType: EventType,
  order: Order,
  timestamp: Instant
)

object OrderEvent {
  import Order.given

  private given Format[Instant] = Format(
    Reads(_.validate[String].map(Instant.parse)),
    Writes(i => JsString(i.toString))
  )

  given Format[OrderEvent] = Json.format[OrderEvent]
}


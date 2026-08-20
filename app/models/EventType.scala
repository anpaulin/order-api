package models

import play.api.libs.json.*

enum EventType {
  case OrderCreated, OrderUpdated, OrderDeleted
}


object EventType {
  given Format[EventType] = Format(
    Reads(_.validate[String].map(EventType.valueOf)),
    Writes(e => JsString(e.toString))
  )
}

package controllers

import play.api.http.HttpErrorHandler
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.*
import play.api.mvc.Results.*

import java.time.OffsetDateTime
import javax.inject.Singleton
import scala.concurrent.Future

@Singleton
class ErrorHandler extends HttpErrorHandler {

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    val json = try {
      Json.parse(message).as[JsObject]
    } catch {
      case _: Exception =>
        Json.obj(
          "timestamp" -> OffsetDateTime.now().toString,
          "error"     -> (if (statusCode == 404) "NotFound" else "ClientError"),
          "message"   -> message
        )
    }
    Future.successful(Status(statusCode)(json))
  }

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    Future.successful(
      InternalServerError(Json.obj(
        "timestamp" -> OffsetDateTime.now().toString,
        "error"     -> "InternalError",
        "message"   -> "An unexpected error occurred"
      ))
    )
}


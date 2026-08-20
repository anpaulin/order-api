package controllers

import play.api.http.HttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.mvc.Results.*

import java.time.OffsetDateTime
import javax.inject.Singleton
import scala.concurrent.Future

@Singleton
class ErrorHandler extends HttpErrorHandler {

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    Future.successful(
      Status(statusCode)(Json.obj(
        "timestamp" -> OffsetDateTime.now().toString,
        "error"     -> "ClientError",
        "message"   -> message
      ))
    )



  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    Future.successful(
      InternalServerError(Json.obj(
        "timestamp" -> OffsetDateTime.now().toString,
        "error"     -> "InternalError",
        "message"   -> "An unexpected error occurred"
      ))
    )
}

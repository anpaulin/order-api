package controllers

import models.*
import services.OrderService
import play.api.libs.json.*
import play.api.mvc.*

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.{Currency, UUID}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OrderController @Inject()(
  svc: OrderService,
  val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController {

  def create: Action[JsValue] = Action.async(parse.json) { request =>
    request.body.validate[CreateOrderRequest] match {
      case JsError(errors)   => Future.successful(BadRequest(jsErrorJson(errors)))
      case JsSuccess(req, _) =>
        svc.create(req.toOrder).map { created =>
          Created(Json.toJson(created))
            .withHeaders("Location" -> s"/orders/${created.id}")
        }
    }
  }

  def get(id: UUID): Action[AnyContent] = Action.async {
    svc.get(id).map {
      case Some(order) => Ok(Json.toJson(order))
      case None        => NotFound
    }
  }

  def update(id: UUID): Action[JsValue] = Action.async(parse.json) { request =>
    request.body.validate[UpdateOrderRequest] match {
      case JsError(errors)   => Future.successful(BadRequest(jsErrorJson(errors)))
      case JsSuccess(req, _) =>
        svc.update(id, req).map {
          case Right(updated) => Ok(Json.toJson(updated))
          case Left(_)        => NotFound
        }
    }
  }

  def delete(id: UUID): Action[AnyContent] = Action.async {
    svc.delete(id).map {
      case Right(_) => NoContent
      case Left(_)  => NotFound
    }
  }

  def search(query: OrderSearchQuery): Action[AnyContent] = Action.async {
    svc.search(
      currency = query.currencyCode,
      txType   = query.transactionType,
      start    = query.startDate,
      end      = query.endDate
    ).map { results =>
      Ok(Json.toJson(results))
    }
  }

  // --- Helpers ---


  private def jsErrorJson(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): JsObject = {
    val details: Seq[JsObject] = errors.flatMap { case (jsPath, validationErrors) =>
      val field = formatJsPath(jsPath)
      validationErrors.map { e =>
        val msg = if (e.message == "error.path.missing") "Field is required" else e.message
        Json.obj(
          "field"   -> field,
          "message" -> msg
        )
      }
    }.toSeq


    val summary = if (details.size == 1) {
      val firstField = (details.head \ "field").as[String]
      val firstMsg   = (details.head \ "message").as[String]
      s"$firstField: $firstMsg"
    } else {
      s"Validation failed for ${details.size} fields"
    }

    errorJson("ValidationError", summary, details)
  }

  private def formatJsPath(jsPath: JsPath): String = {
    val path = jsPath.toJsonString.stripPrefix("obj.").stripPrefix(".")
    if (path.isEmpty || path == "obj") "body" else path
  }



  private def errorJson(error: String, message: String, details: Seq[JsObject] = Seq.empty): JsObject = {

    val base = Json.obj(
      "timestamp" -> java.time.OffsetDateTime.now().toString,
      "error"     -> error,
      "message"   -> message
    )
    if (details.nonEmpty) {
      base + ("details" -> Json.toJson(details))
    } else {
      base
    }
  }
}





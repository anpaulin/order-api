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

  def search(
    currencyCode: Option[String],
    transactionType: Option[String],
    startDate: Option[String],
    endDate: Option[String]
  ): Action[AnyContent] = Action.async {

    val curResult   = currencyCode.map(parseCurrency)
    val txResult    = transactionType.map(TransactionType.fromString)
    val startResult = startDate.map(parseDate)
    val endResult   = endDate.map(parseDate)

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
      Future.successful(BadRequest(errorJson("ValidationError", summary, allErrors)))
    } else {
      svc.search(
        currency = curResult.flatMap(_.toOption),
        txType   = txResult.flatMap(_.toOption),
        start    = startResult.flatMap(_.toOption),
        end      = endResult.flatMap(_.toOption)
      ).map { results =>
        Ok(Json.toJson(results))
      }
    }
  }


  // --- Helpers ---

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
    jsPath.path match {
      case Nil   => "body"
      case nodes =>
        nodes.foldLeft("") {
          case ("", KeyPathNode(k))  => k
          case ("", IdxPathNode(i))  => s"[$i]"
          case (acc, KeyPathNode(k)) => s"$acc.$k"
          case (acc, IdxPathNode(i)) => s"$acc[$i]"
          case ("", other)           => other.toString.stripPrefix("/")
          case (acc, other)          => s"$acc.${other.toString.stripPrefix("/")}"
        }
    }
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





package services

import models.{Order, TransactionType, UpdateOrderRequest}

import java.time.OffsetDateTime
import java.util.{Currency, UUID}
import scala.concurrent.Future

/** Defines the order management contract. */
trait OrderService {
  def create(order: Order): Future[Order]
  def get(id: UUID): Future[Option[Order]]
  def update(id: UUID, patch: UpdateOrderRequest): Future[Either[String, Order]]
  def delete(id: UUID): Future[Either[String, Unit]]
  def search(
    currency: Option[Currency],
    txType: Option[TransactionType],
    start: Option[OffsetDateTime],
    end: Option[OffsetDateTime]
  ): Future[List[Order]]
  def replayFromAuditLog(): Future[Unit]
  def clearStateAndLog(): Future[Unit]
}



package services

import models.{Order, TransactionType, UpdateOrderRequest}

import java.time.OffsetDateTime
import java.util.{Currency, UUID}

/** Defines the order management contract. */
trait OrderService {
  def create(order: Order): Order
  def get(id: UUID): Option[Order]
  def update(id: UUID, patch: UpdateOrderRequest): Either[String, Order]
  def delete(id: UUID): Either[String, Unit]
  def search(
    currency: Option[Currency],
    txType: Option[TransactionType],
    start: Option[OffsetDateTime],
    end: Option[OffsetDateTime]
  ): List[Order]
  def replayFromAuditLog(): Unit
  def clearStateAndLog(): Unit
}


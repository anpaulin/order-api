package services

import models.{EventType, Order, OrderEvent, TransactionType, UpdateOrderRequest}

import repositories.AuditLogRepository
import play.api.Logging

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.jdk.CollectionConverters.*

@Singleton
class InMemoryOrderService @Inject()(
  audit: AuditLogRepository,
  config: play.api.Configuration
) extends OrderService with Logging {

  private val state = new ConcurrentHashMap[UUID, Order]()

  // Replay on startup if configured
  if (config.get[Boolean]("app.startup.replay-audit")) {
    replayFromAuditLog()
  }

  override def create(order: Order): Order = synchronized {
    val id   = if (order.id == null) UUID.randomUUID() else order.id
    val date = if (order.date == null) OffsetDateTime.now() else order.date
    val created = order.copy(id = id, date = date)
    applyEvent(OrderEvent(EventType.OrderCreated, created, Instant.now()), persist = true)
    created
  }

  override def get(id: UUID): Option[Order] = {
    Option(state.get(id))
  }

  override def update(id: UUID, patch: UpdateOrderRequest): Either[String, Order] = synchronized {
    Option(state.get(id)) match {
      case None => Left(s"Order not found: $id")
      case Some(existing) =>
        val updated = existing.copy(
          date            = patch.date.getOrElse(existing.date),
          amount          = patch.amount.getOrElse(existing.amount),
          currencyCode    = patch.currencyCode.getOrElse(existing.currencyCode),
          transactionType = patch.transactionType.getOrElse(existing.transactionType)
        )
        applyEvent(OrderEvent(EventType.OrderUpdated, updated, Instant.now()), persist = true)
        Right(updated)
    }
  }

  override def delete(id: UUID): Either[String, Unit] = synchronized {
    Option(state.get(id)) match {
      case None => Left(s"Order not found: $id")
      case Some(existing) =>
        applyEvent(OrderEvent(EventType.OrderDeleted, existing, Instant.now()), persist = true)
        Right(())
    }
  }

  override def search(
    currency: Option[Currency],
    txType: Option[TransactionType],
    start: Option[OffsetDateTime],
    end: Option[OffsetDateTime]
  ): List[Order] = {
    state.values().asScala.toList
      .filter(o => currency.forall(_ == o.currencyCode))
      .filter(o => txType.forall(_ == o.transactionType))
      .filter(o => start.forall(s => !o.date.isBefore(s)))
      .filter(o => end.forall(e => !o.date.isAfter(e)))
      .sortBy(_.date)
  }

  override def replayFromAuditLog(): Unit = synchronized {
    state.clear()
    audit.readAll().foreach { event =>
      logger.debug(s"Replaying event: $event")
      applyEvent(event, persist = false)
    }
    logger.info(s"Replay complete — total orders in memory: ${state.size}")
  }

  override def clearStateAndLog(): Unit = synchronized {
    state.clear()
    audit.clear()
  }

  private def applyEvent(event: OrderEvent, persist: Boolean): Unit = {
    if (persist) {
      audit.append(event)
    }
    event.eventType match {
      case EventType.OrderCreated | EventType.OrderUpdated =>
        state.put(event.order.id, event.order)
      case EventType.OrderDeleted =>
        state.remove(event.order.id)
    }
  }
}



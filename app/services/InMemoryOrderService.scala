package services

import models.{EventType, Order, OrderEvent, TransactionType, UpdateOrderRequest}
import repositories.AuditLogRepository
import play.api.Logging

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

@Singleton
class InMemoryOrderService @Inject()(
  audit: AuditLogRepository,
  config: play.api.Configuration
)(implicit ec: ExecutionContext) extends OrderService with Logging {

  private val state = new ConcurrentHashMap[UUID, Order]()

  // Replay on startup if configured
  if (config.get[Boolean]("app.startup.replay-audit")) {
    logger.info("[OrderService] Startup: replaying events from audit log...")
    replayFromAuditLog()
  }

  override def create(order: Order): Future[Order] = {
    val id      = if (order.id == null) UUID.randomUUID() else order.id
    val date    = if (order.date == null) OffsetDateTime.now() else order.date
    val created = order.copy(id = id, date = date)
    val event   = OrderEvent(EventType.OrderCreated, created, Instant.now())

    logger.info(s"[OrderService] [CREATE] Persisting order ${created.id} (amount=${created.amount} ${created.currencyCode}, type=${created.transactionType}) to audit log")

    // WAL: Append to audit log first; mutate state strictly after append succeeds
    audit.append(event).map { _ =>
      state.put(created.id, created)
      logger.info(s"[OrderService] [CREATE] Order ${created.id} committed to in-memory state (active orders: ${state.size})")
      created
    }
  }

  override def get(id: UUID): Future[Option[Order]] = {
    val result = Option(state.get(id))
    logger.debug(s"[OrderService] [GET] Order $id => ${if (result.isDefined) "Found" else "Not Found"}")
    Future.successful(result)
  }

  override def update(id: UUID, patch: UpdateOrderRequest): Future[Either[String, Order]] = {
    Option(state.get(id)) match {
      case None =>
        logger.warn(s"[OrderService] [UPDATE] Order $id not found")
        Future.successful(Left(s"Order not found: $id"))
      case Some(existing) =>
        val updated = existing.copy(
          date            = patch.date.getOrElse(existing.date),
          amount          = patch.amount.getOrElse(existing.amount),
          currencyCode    = patch.currencyCode.getOrElse(existing.currencyCode),
          transactionType = patch.transactionType.getOrElse(existing.transactionType)
        )
        val event = OrderEvent(EventType.OrderUpdated, updated, Instant.now())

        logger.info(s"[OrderService] [UPDATE] Persisting update for order $id to audit log (amount: ${existing.amount} -> ${updated.amount}, type: ${existing.transactionType} -> ${updated.transactionType})")

        // WAL: Append to audit log first; mutate state strictly after append succeeds
        audit.append(event).map { _ =>
          state.put(updated.id, updated)
          logger.info(s"[OrderService] [UPDATE] Order $id update committed to in-memory state")
          Right(updated)
        }
    }
  }

  override def delete(id: UUID): Future[Either[String, Unit]] = {
    Option(state.get(id)) match {
      case None =>
        logger.warn(s"[OrderService] [DELETE] Order $id not found")
        Future.successful(Left(s"Order not found: $id"))
      case Some(existing) =>
        val event = OrderEvent(EventType.OrderDeleted, existing, Instant.now())

        logger.info(s"[OrderService] [DELETE] Persisting deletion of order $id to audit log")

        // WAL: Append to audit log first; remove from state strictly after append succeeds
        audit.append(event).map { _ =>
          state.remove(existing.id)
          logger.info(s"[OrderService] [DELETE] Order $id removed from in-memory state (active orders: ${state.size})")
          Right(())
        }
    }
  }

  override def search(
    currency: Option[Currency],
    txType: Option[TransactionType],
    start: Option[OffsetDateTime],
    end: Option[OffsetDateTime]
  ): Future[List[Order]] = {
    val results = state.values().asScala.toList
      .filter(o => currency.forall(_ == o.currencyCode))
      .filter(o => txType.forall(_ == o.transactionType))
      .filter(o => start.forall(s => !o.date.isBefore(s)))
      .filter(o => end.forall(e => !o.date.isAfter(e)))
      .sortBy(_.date)

    logger.debug(s"[OrderService] [SEARCH] Matched ${results.size} order(s)")
    Future.successful(results)
  }

  override def replayFromAuditLog(): Future[Unit] = {
    logger.info("[OrderService] [REPLAY] Replaying audit log to reconstruct state...")
    audit.readAll().map { events =>

      state.clear()
      events.foreach { event =>
        logger.debug(s"Replaying event: $event")
        event.eventType match {
          case EventType.OrderCreated | EventType.OrderUpdated =>
            state.put(event.order.id, event.order)
          case EventType.OrderDeleted =>
            state.remove(event.order.id)
        }
      }
      logger.info(s"Replay complete — total orders in memory: ${state.size}")
    }
  }
}





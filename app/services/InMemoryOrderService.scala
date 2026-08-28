package services

import models.{CreateOrderRequest, EventType, Order, OrderEvent, TransactionType, UpdateOrderRequest}
import repositories.EventStoreRepository
import play.api.Logging

import java.time.{Instant, OffsetDateTime}
import java.util.{Currency, UUID}
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

@Singleton
class InMemoryOrderService @Inject()(
  eventStore: EventStoreRepository,
  config: play.api.Configuration
)(implicit ec: ExecutionContext) extends OrderService with Logging {

  private val state = new ConcurrentHashMap[UUID, Order]()

  // Replay on startup if configured
  if (config.get[Boolean]("app.startup.replay-events")) {
    logger.info("[OrderService] Startup: replaying events from event store...")
    replayFromEventStore()
  }

  override def create(req: CreateOrderRequest): Future[Order] = {
    val newOrder = Order(
      id              = UUID.randomUUID(),
      date            = req.date.getOrElse(OffsetDateTime.now()),
      amount          = req.amount,
      currencyCode    = req.currencyCode,
      transactionType = req.transactionType
    )
    val event = OrderEvent(EventType.OrderCreated, newOrder, Instant.now())

    logger.info(s"[OrderService] [CREATE] Persisting order ${newOrder.id} (amount=${newOrder.amount} ${newOrder.currencyCode}, type=${newOrder.transactionType}) to event store")

    // We are using WAL (Write Ahead Logging)
    // Only if the append to the Event Store is successful, do we update the internal memory
    eventStore.append(event).map { _ =>
      state.put(newOrder.id, newOrder)
      logger.info(s"[OrderService] [CREATE] Order ${newOrder.id} committed to in-memory state (active orders: ${state.size})")
      newOrder
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

        logger.info(s"[OrderService] [UPDATE] Persisting update for order $id to event store (amount: ${existing.amount} -> ${updated.amount}, type: ${existing.transactionType} -> ${updated.transactionType})")

        // WAL: Append to event store first, only mutate state after append succeeds
        // Note: This naive in-memory implementation would cause lost updates if two concurrent PATCH requests arrive for the same order:
        // Req 1 -> reads old data, updates some fields, leaves others -> writes to WAL -> updates MAP
        // Req 2 -> reads old data, updates diff fields, leaves others -> writes to WAL -> updates MAP
        // Now changes from Req 1 are overwritten and lost in the MAP
        eventStore.append(event).map { _ =>
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

        logger.info(s"[OrderService] [DELETE] Persisting deletion of order $id to event store")

        // WAL: Append to event store first; remove from state strictly after append succeeds
        eventStore.append(event).map { _ =>
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

  override def replayFromEventStore(): Future[Unit] = {
    logger.info("[OrderService] [REPLAY] Replaying event store to reconstruct state...")
    eventStore.readAll().map { events =>

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

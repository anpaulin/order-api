package repositories

import models.OrderEvent
import play.api.Logging

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

@Singleton
class InMemoryEventStoreRepository @Inject()() extends EventStoreRepository with Logging {

  private val events = new CopyOnWriteArrayList[OrderEvent]()

  override def append(event: OrderEvent): Future[Unit] = {
    logger.info(s"[InMemoryEventStore] Appending event '${event.eventType}' for order ${event.order.id} (total recorded events: ${events.size + 1})")
    events.add(event)
    Future.successful(())
  }

  override def readAll(): Future[List[OrderEvent]] = {
    logger.info(s"[InMemoryEventStore] Reading all recorded events (total: ${events.size})")
    Future.successful(events.asScala.toList)
  }

  /** Test helper for resetting in-memory state between tests. */
  def clear(): Unit = {
    events.clear()
  }
}

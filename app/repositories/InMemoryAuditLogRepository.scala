package repositories

import models.OrderEvent

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

@Singleton
class InMemoryAuditLogRepository @Inject()() extends AuditLogRepository {

  private val events = new CopyOnWriteArrayList[OrderEvent]()

  override def append(event: OrderEvent): Future[Unit] = {
    events.add(event)
    Future.successful(())
  }

  override def readAll(): Future[List[OrderEvent]] = {
    Future.successful(events.asScala.toList)
  }

  /** Test helper for resetting in-memory state between tests. */
  def clear(): Unit = {
    events.clear()
  }
}



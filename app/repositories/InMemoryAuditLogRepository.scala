package repositories

import models.OrderEvent

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.{Inject, Singleton}
import scala.jdk.CollectionConverters.*

@Singleton
class InMemoryAuditLogRepository @Inject()() extends AuditLogRepository {

  private val events = new CopyOnWriteArrayList[OrderEvent]()

  override def append(event: OrderEvent): Unit = {
    events.add(event)
  }

  override def readAll(): List[OrderEvent] = {
    events.asScala.toList
  }

  override def clear(): Unit = {
    events.clear()
  }
}

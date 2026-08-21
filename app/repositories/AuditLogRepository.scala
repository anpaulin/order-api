package repositories

import models.OrderEvent
import scala.concurrent.Future

/** Immutable, append-only repository contract for audit log events. */
trait AuditLogRepository {
  def append(event: OrderEvent): Future[Unit]
  def readAll(): Future[List[OrderEvent]]
}




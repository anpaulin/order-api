package repositories

import models.OrderEvent
import scala.concurrent.Future

trait AuditLogRepository {
  def append(event: OrderEvent): Future[Unit]
  def readAll(): Future[List[OrderEvent]]
  def clear(): Future[Unit]
}



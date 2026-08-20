package repositories

import models.OrderEvent

trait AuditLogRepository {
  def append(event: OrderEvent): Unit
  def readAll(): List[OrderEvent]
  def clear(): Unit
}


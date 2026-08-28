package repositories

import models.OrderEvent
import scala.concurrent.Future

/**
 * Defines the immutable append-only event store contract.
 */
trait EventStoreRepository {
  def append(event: OrderEvent): Future[Unit]
  def readAll(): Future[List[OrderEvent]]
}

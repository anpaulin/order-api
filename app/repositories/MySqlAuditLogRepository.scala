package repositories

import models.OrderEvent
import play.api.{Configuration, Logging}

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Placeholder implementation for persisting audit log events to a MySQL database table.
 *
 * Sample Production DDL Schema:
 * {{{
 * CREATE TABLE IF NOT EXISTS audit_logs (
 *     event_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
 *     event_type       VARCHAR(32) NOT NULL,                         -- 'OrderCreated', 'OrderUpdated', 'OrderDeleted'
 *     order_id         CHAR(36) NOT NULL,                            -- Order UUID
 *     order_date       DATETIME(6) NOT NULL,                         -- Order business timestamp
 *     amount           DECIMAL(12, 2) NOT NULL,                      -- Monetary amount
 *     currency_code    CHAR(3) NOT NULL,                             -- ISO 4217 currency code (e.g., USD, CAD, EUR)
 *     transaction_type VARCHAR(16) NOT NULL,                         -- 'Sale', 'Refund'
 *     payload          JSON NULL,                                    -- Full serialized JSON event snapshot
 *     created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), -- Audit event emission timestamp
 *
 *     INDEX idx_order_id (order_id),
 *     INDEX idx_created_at (created_at),
 *     INDEX idx_event_type (event_type)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
 * }}}
 */
@Singleton
class MySqlAuditLogRepository @Inject()(config: Configuration) extends AuditLogRepository with Logging {


  private val jdbcUrl = config.getOptional[String]("app.audit-log.mysql.url")
    .getOrElse("jdbc:mysql://localhost:3306/order_db")
  private val tableName = config.getOptional[String]("app.audit-log.mysql.table")
    .getOrElse("audit_logs")

  // Placeholder in-memory buffer simulating database table rows
  private val simulatedTable = new CopyOnWriteArrayList[OrderEvent]()

  logger.info(s"Initialized MySqlAuditLogRepository with url=$jdbcUrl, table=$tableName")

  override def append(event: OrderEvent): Future[Unit] = {
    logger.info(s"[MySQL INSERT] Inserting into table `$tableName` event ${event.eventType} for order ${event.order.id}")
    simulatedTable.add(event)
    Future.successful(())
  }

  override def readAll(): Future[List[OrderEvent]] = {
    logger.info(s"[MySQL SELECT] Querying all events from table `$tableName`")
    Future.successful(simulatedTable.asScala.toList)
  }
}



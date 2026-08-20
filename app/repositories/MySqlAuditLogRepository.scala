package repositories

import models.OrderEvent
import play.api.{Configuration, Logging}

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.{Inject, Singleton}
import scala.jdk.CollectionConverters.*

/**
 * Placeholder implementation for persisting audit log events to a MySQL database table.
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

  override def append(event: OrderEvent): Unit = {
    logger.info(s"[MySQL INSERT] Inserting into table `$tableName` event ${event.eventType} for order ${event.order.id}")
    simulatedTable.add(event)
  }

  override def readAll(): List[OrderEvent] = {
    logger.info(s"[MySQL SELECT] Querying all events from table `$tableName`")
    simulatedTable.asScala.toList
  }

  override def clear(): Unit = {
    logger.info(s"[MySQL TRUNCATE] Truncating table `$tableName`")
    simulatedTable.clear()
  }
}

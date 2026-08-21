package repositories

import models.OrderEvent
import play.api.{Configuration, Logging}

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Placeholder implementation for publishing audit log events to Apache Kafka.
 */
@Singleton
class KafkaAuditLogRepository @Inject()(config: Configuration) extends AuditLogRepository with Logging {

  private val bootstrapServers = config.getOptional[String]("app.audit-log.kafka.bootstrap-servers")
    .getOrElse("localhost:9092")
  private val topic = config.getOptional[String]("app.audit-log.kafka.topic")
    .getOrElse("order-audit-events")

  // Placeholder in-memory buffer simulating Kafka topic retention for testing/replay
  private val simulatedTopic = new CopyOnWriteArrayList[OrderEvent]()

  logger.info(s"Initialized KafkaAuditLogRepository with bootstrapServers=$bootstrapServers, topic=$topic")

  override def append(event: OrderEvent): Future[Unit] = {
    logger.info(s"[Kafka Producer] Publishing event to topic '$topic': ${event.eventType} for order ${event.order.id}")
    simulatedTopic.add(event)
    Future.successful(())
  }

  override def readAll(): Future[List[OrderEvent]] = {
    logger.info(s"[Kafka Consumer] Consuming all events from topic '$topic'")
    Future.successful(simulatedTopic.asScala.toList)
  }

  override def clear(): Future[Unit] = {
    logger.info(s"[Kafka Admin] Purging topic '$topic'")
    simulatedTopic.clear()
    Future.successful(())
  }
}


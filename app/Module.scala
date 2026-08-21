import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}
import repositories.{AuditLogRepository, FileAuditLogRepository, InMemoryAuditLogRepository, KafkaAuditLogRepository, MySqlAuditLogRepository}
import services.{InMemoryOrderService, OrderService}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule {

  def this() = this(Environment.simple(), Configuration.empty)

  override def configure(): Unit = {
    val auditLogType = configuration.getOptional[String]("app.audit-log.type").getOrElse("file")

    auditLogType.toLowerCase match {
      case "memory" => bind(classOf[AuditLogRepository]).to(classOf[InMemoryAuditLogRepository]).asEagerSingleton()
      case "kafka"  => bind(classOf[AuditLogRepository]).to(classOf[KafkaAuditLogRepository]).asEagerSingleton()
      case "mysql"  => bind(classOf[AuditLogRepository]).to(classOf[MySqlAuditLogRepository]).asEagerSingleton()
      case "file"   => bind(classOf[AuditLogRepository]).to(classOf[FileAuditLogRepository]).asEagerSingleton()
      case other    => throw new IllegalArgumentException(s"Unknown audit log type '$other'. Valid options are: file, memory, kafka, mysql")
    }

    bind(classOf[OrderService]).to(classOf[InMemoryOrderService]).asEagerSingleton()
  }
}




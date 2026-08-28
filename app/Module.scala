import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}
import repositories.{EventStoreRepository, FileEventStoreRepository, InMemoryEventStoreRepository, KafkaEventStoreRepository, MySqlEventStoreRepository}
import services.{InMemoryOrderService, OrderService}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule {

  def this() = this(Environment.simple(), Configuration.empty)

  override def configure(): Unit = {
    val eventStoreType = configuration.getOptional[String]("app.event-store.type").getOrElse("file")

    eventStoreType.toLowerCase match {
      case "memory" => bind(classOf[EventStoreRepository]).to(classOf[InMemoryEventStoreRepository]).asEagerSingleton()
      case "kafka"  => bind(classOf[EventStoreRepository]).to(classOf[KafkaEventStoreRepository]).asEagerSingleton()
      case "mysql"  => bind(classOf[EventStoreRepository]).to(classOf[MySqlEventStoreRepository]).asEagerSingleton()
      case "file"   => bind(classOf[EventStoreRepository]).to(classOf[FileEventStoreRepository]).asEagerSingleton()
      case other    => throw new IllegalArgumentException(s"Unknown event store type '$other'. Valid options are: file, memory, kafka, mysql")
    }

    bind(classOf[OrderService]).to(classOf[InMemoryOrderService]).asEagerSingleton()
  }
}

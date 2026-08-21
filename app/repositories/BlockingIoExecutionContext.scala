package repositories

import org.apache.pekko.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import javax.inject.{Inject, Singleton}

/**
 * Dedicated thread pool execution context for blocking I/O operations (file system, database).
 * Prevents I/O blocking from starving the Play Netty/HTTP worker thread pools.
 */
@Singleton
class BlockingIoExecutionContext @Inject()(actorSystem: ActorSystem)
  extends CustomExecutionContext(actorSystem, "app.blocking-io-dispatcher")

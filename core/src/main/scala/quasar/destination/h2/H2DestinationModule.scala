/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.destination.h2

import slamdata.Predef._
import quasar.api.destination.{DestinationError => DE, _}
import quasar.concurrent._
import quasar.connector.MonadResourceErr
import quasar.connector.destination.{Destination, DestinationModule, PushmiPullyu}
import quasar.destination.h2.server.H2Server

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import argonaut._, Argonaut._
import cats.data.EitherT
import cats.effect._
import cats.implicits._
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.slf4s.Logging

object H2DestinationModule extends DestinationModule with Logging {

  type InitErr = DE.InitializationError[Json]

  val destinationType = DestinationType("h2", 1L)

  val name = destinationType.name

  // The duration to await validation of the initial connection.
  val ValidationTimeout: FiniteDuration = 10.seconds

  // Maximum number of database connections per destination.
  val ConnectionPoolSize: Int = 10

  def sanitizeDestinationConfig(config: Json): Json =
    config.as[Config]
      .map(_.sanitized.asJson)
      .getOr(jEmptyObject)

  def destination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      config: Json,
      pushPull: PushmiPullyu[F])
      : Resource[F, Either[InitErr, Destination[F]]] = {

    val cfg0: Either[InitErr, Config] =
      config.as[Config].fold(
        (err, c) =>
          Left(DE.malformedConfiguration[Json, InitErr](
            destinationType,
            jString(Redacted),
            err)),

        Right(_))

    val validateConnection: ConnectionIO[Either[InitErr, Unit]] =
      FC.isValid(ValidationTimeout.toSeconds.toInt) map { v =>
        if (!v)
          Left(connectionInvalid(sanitizeDestinationConfig(config)))
        else
          Right(())
      }

    val init = for {
      cfg <- EitherT(cfg0.pure[Resource[F, ?]])

      freshTag <- EitherT.right(Resource.liftF(randomAlphaNum[F](6)))

      connPool <- EitherT.right(boundedPool[F](s"$name-connection-$freshTag", ConnectionPoolSize))

      blocker <- EitherT.right(Blocker.cached[F](s"$name-transact-$freshTag"))

      _ <- EitherT.right(cfg.server.traverse_(H2Server[F](_, blocker)))

      xa <- EitherT.right(hikariTransactor[F](cfg, connPool, blocker))

      _ <- EitherT(Resource.liftF(validateConnection.transact(xa) recover {
        case NonFatal(ex: Exception) =>
          Left(DE.connectionFailed[Json, InitErr](destinationType, sanitizeDestinationConfig(config), ex))
      }))

      _ <- EitherT.right[InitErr](Resource.liftF(Sync[F].delay(
        log.info(s"Initialized $name destination: tag = $freshTag, config = ${cfg.sanitized.asJson}"))))

    } yield new H2Destination(destinationType, xa, blocker): Destination[F]

    init.value
  }

  ////

  private val H2DriverFqcn: String = "org.h2.Driver"

  private def boundedPool[F[_]](name: String, size: Int)(implicit F: Sync[F])
      : Resource[F, ExecutionContext] = {

    val alloc =
      F.delay(Executors.newFixedThreadPool(size, NamedDaemonThreadFactory(name)))

    Resource.make(alloc)(es => F.delay(es.shutdown()))
      .map(ExecutionContext.fromExecutor)
  }

  private def connectionInvalid(c: Json): InitErr =
    DE.connectionFailed[Json, InitErr](
      destinationType, c, new RuntimeException("Connection is invalid."))

  private def hikariTransactor[F[_]: Async: ContextShift](
      cfg: Config,
      connectPool: ExecutionContext,
      xaBlocker: Blocker)
      : Resource[F, HikariTransactor[F]] = {

    HikariTransactor.initial[F](connectPool, xaBlocker) evalMap { xa =>
      xa.configure { ds =>
        Sync[F] delay {
          ds.setJdbcUrl(jdbcUri(cfg.connectionUri))
          ds.setDriverClassName(H2DriverFqcn)
          ds.setMaximumPoolSize(ConnectionPoolSize)
          xa
        }
      }
    }
  }
}

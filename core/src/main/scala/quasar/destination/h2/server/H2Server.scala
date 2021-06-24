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

package quasar.destination.h2.server

import slamdata.Predef._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.{file => jfile}

import cats.effect._
import cats.implicits._
import fs2.{io, text, Stream}

import org.h2.{tools => h2}
import org.slf4s.Logging

object H2Server extends Logging {

  def apply[F[_]: ContextShift: Sync](config: ServerConfig, blocker: Blocker)
      : Resource[F, Unit] =
    for {
      ini <- config.init.traverse_(i => Resource.eval(init[F](i, blocker)))
      tcp <- config.tcp.traverse_(tcpServer[F](_).void)
      pg <- config.pg.traverse_(pgServer[F](_).void)
    } yield pg

  private def init[F[_]: ContextShift](
      cfg: InitConfig, blocker: Blocker)(
      implicit F: Sync[F])
      : F[Unit] =
    for {
      tmpFile <- createTempFile[F]("init", "sql")
      bytes = Stream[F, String](cfg.script).through(text.utf8Encode)
      _ <- save[F](tmpFile, bytes, blocker)
      _ <-
        Sync[F].delay {
          h2.RunScript.execute(cfg.url, cfg.user, cfg.password, tmpFile.toString, UTF_8, false)
          log.info("Initialized H2")
        } handleErrorWith { _ =>
          Sync[F].delay {
            log.info("Assuming that H2 is already initialized")
          }
        }
    } yield ()

  private def tcpServer[F[_]](cfg: TcpConfig)(implicit F: Sync[F]): Resource[F, h2.Server] = {
    val acquire = F.delay {
      val server = h2.Server.createTcpServer(cfg.args: _*).start()
      log.info(s"""Started H2 TCP server with args: ${cfg.args.mkString(" ")}""")
      server
    }
    def release(srv: h2.Server): F[Unit] = F.delay {
      srv.stop()
      log.info("Stopped H2 TCP server")
    }
    Resource.make(acquire)(release(_))
  }

  private def pgServer[F[_]](cfg: PgConfig)(implicit F: Sync[F]): Resource[F, h2.Server] = {
    val acquire = F.delay {
      val server = h2.Server.createPgServer(cfg.args: _*).start()
      log.info(s"""Started H2 postgres server with args: ${cfg.args.mkString(" ")}""")
      server
    }
    def release(srv: h2.Server): F[Unit] = F.delay {
      srv.stop()
      log.info("Stopped H2 postgres server")
    }
    Resource.make(acquire)(release(_))
  }

  private def createTempFile[F[_]](prefix: String, suffix: String)(implicit F: Sync[F])
      : F[jfile.Path] =
    F.delay(jfile.Files.createTempFile(prefix, suffix))

  private def save[F[_]: ContextShift: Sync](path: jfile.Path, bytes: Stream[F, Byte], blocker: Blocker): F[Unit] = {
    val fileSink = io.file.writeAll[F](path, blocker)
    bytes.through(fileSink).compile.drain
  }
}

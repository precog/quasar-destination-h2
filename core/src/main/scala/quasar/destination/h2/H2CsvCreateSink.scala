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
import quasar.api.{Column, ColumnType}
import quasar.api.resource._
import quasar.connector._

import java.nio.{file => jfile}

import cats.data._
import cats.effect.{Blocker, ContextShift, Effect}
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.util.log.{ExecFailure, ProcessingFailure, Success}
import fs2.{io, Pipe, Stream}
import org.slf4s.Logging

object H2CsvCreateSink extends Logging {

  def apply[F[_]: Effect: MonadResourceErr: ContextShift](
      xa: Transactor[F],
      blocker: Blocker)(
      path: ResourcePath,
      columns: NonEmptyList[Column[ColumnType.Scalar]])
      : Pipe[F, Byte, Unit] = { bytes =>

    val res =
      for {
        tableName <- ensureValidTableName(path)

        cols <- ensureValidColumns(columns)

        csvTmpFile <- createTempFile[F]("h2-", ".csv")

        _ <- save(csvTmpFile, bytes, blocker)

        _ <-
          (for {
            _ <- dropTableIfExistsQuery(tableName).updateWithLogHandler(logHandler).run
            _ <- createTableQuery(tableName, cols, csvTmpFile.toString).updateWithLogHandler(logHandler).run
          } yield ()).transact(xa)

        _ <- deleteIfExists[F](csvTmpFile)
      } yield ()

    Stream.eval(res)
  }

  private def ensureValidTableName[F[_]: Effect: MonadResourceErr](p: ResourcePath): F[String] =
    p match {
      case t /: ResourcePath.Root => t.pure[F]
      case _ => MonadResourceErr[F].raiseError(ResourceError.notAResource(p))
    }

  private def ensureValidColumns[F[_]: Effect](columns: NonEmptyList[Column[ColumnType.Scalar]])
      : F[NonEmptyList[(Fragment, Fragment)]] = {
    columns
      .traverse(mkColumn(_))
      .toEither
      .fold(
        invalid => Effect[F].raiseError(ColumnTypesNotSupported(invalid)),
        _.pure[F])
  }

  private def mkColumn(c: Column[ColumnType.Scalar]): ValidatedNel[ColumnType.Scalar, (Fragment, Fragment)] =
    columnTypeToH2(c.tpe).map((Fragment.const(hygienicIdent(c.name)), _))

  private def columnTypeToH2(ct: ColumnType.Scalar)
      : ValidatedNel[ColumnType.Scalar, Fragment] =
    ct match {
      case ColumnType.Null => fr0"TINYINT".validNel
      case ColumnType.Boolean => fr0"BOOLEAN".validNel
      case ColumnType.LocalTime => fr0"TIME(9)".validNel
      case ColumnType.OffsetTime => fr0"TIME(9) WITH TIME ZONE".validNel
      case ColumnType.LocalDate => fr0"DATE".validNel
      case od @ ColumnType.OffsetDate => od.invalidNel
      case ColumnType.LocalDateTime => fr0"TIMESTAMP(3)".validNel
      case ColumnType.OffsetDateTime => fr0"TIMESTAMP(3) WITH TIME ZONE".validNel
      case i @ ColumnType.Interval => i.invalidNel
      case ColumnType.Number => fr0"NUMERIC".validNel
      case ColumnType.String => fr0"VARCHAR".validNel
    }

  private def save[F[_]: ContextShift: Effect: MonadResourceErr](path: jfile.Path, bytes: Stream[F, Byte], blocker: Blocker): F[Unit] = {
    val fileSink = io.file.writeAll[F](path, blocker)
    bytes.through(fileSink).compile.drain
  }

  private def createTableQuery[F[_]: Effect: MonadResourceErr](
      tableName: String,
      cols: NonEmptyList[(Fragment, Fragment)],
      fileName: String)
      : Fragment = {

    val tableFragment = Fragment.const(hygienicIdent(tableName))
    val colsFragment = Fragments.parentheses(cols.map(p => p._1 ++ p._2).intercalate(fr","))
    val csvReadArgsFragment =
      List(
        Fragment.const(singleQuote(fileName)),
        singleQuoteFragment(cols.map(_._1).intercalate(fr",")),
        Fragment.const(singleQuote("charset=UTF-8"))
      ).intercalate(fr",")

    fr"CREATE TABLE" ++ tableFragment ++ colsFragment ++
      fr"AS SELECT * FROM CSVREAD" ++ Fragments.parentheses(csvReadArgsFragment)
  }

  private def dropTableIfExistsQuery(table: String): Fragment =
    fr"DROP TABLE IF EXISTS" ++ Fragment.const(hygienicIdent(table))

  private val logHandler: LogHandler =
    LogHandler {
      case Success(q, _, e, p) =>
        log.debug(s"SUCCESS: `$q` in ${(e + p).toMillis}ms (${e.toMillis} ms exec, ${p.toMillis} ms proc)")

      case ExecFailure(q, _, e, t) =>
        log.debug(s"EXECUTION_FAILURE: `$q` after ${e.toMillis} ms, detail: ${t.getMessage}", t)

      case ProcessingFailure(q, _, e, p, t) =>
        log.debug(s"PROCESSING_FAILURE: `$q` after ${(e + p).toMillis} ms (${e.toMillis} ms exec, ${p.toMillis} ms proc (failed)), detail: ${t.getMessage}", t)
    }
}

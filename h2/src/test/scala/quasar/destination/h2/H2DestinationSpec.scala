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
import quasar.EffectfulQSpec
import quasar.api.{Column, ColumnType}
import quasar.api.destination._
import quasar.api.resource._
import quasar.contrib.scalaz.MonadError_
import quasar.connector._
import quasar.connector.destination._
import quasar.connector.render.RenderConfig

import java.time._
import scala.Float
import scala.concurrent.ExecutionContext.Implicits.global

import argonaut._, Argonaut._, ArgonautScalaz._
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.implicits.javatime._
import doobie.util.Read
import eu.timepit.refined.auto._
import fs2.{Pull, Stream}
import org.specs2.matcher.MatchResult
import scalaz.-\/
import scalaz.syntax.show._
import shapeless._
import shapeless.ops.hlist.{Mapper, ToList}
import shapeless.ops.record.{Keys, Values}
import shapeless.record._
import shapeless.syntax.singleton._
import shims.applicativeToScalaz

object H2DestinationSpec extends EffectfulQSpec[IO] with CsvSupport {

  import AbstractDestinationModule._

  sequential

  val B = TemporalBounds

  "initialization" should {
    "fail with malformed config when not decodable" >>* {
      val cfg = Json("malformed" := true)

      dest(cfg)(r => IO.pure(r match {
        case Left(DestinationError.MalformedConfiguration(_, c, _)) =>
          c must_=== jString(Redacted)

        case _ => ko("Expected a malformed configuration")
      }))
    }

    "fail when unable to connect to database" >>* {
      val cfg = config(url = "h2:tcp://localhost:1234/~/foo")

      dest(cfg)(r => IO.pure(r match {
        case Left(DestinationError.ConnectionFailed(_, c, _)) =>
          c must_=== cfg

        case _ => ko("Expected a connection failed")
      }))
    }
  }

  "csv sink" should {
    "reject empty paths with NotAResource" >>* {
      csv(config()) { sink =>
        val p = ResourcePath.root()
        val r = sink.consume(p, NonEmptyList.one(Column("a", ColumnType.Boolean)), Stream.empty).compile.drain

        MRE.attempt(r).map(_ must beLike {
          case -\/(ResourceError.NotAResource(p2)) => p2 must_=== p
        })
      }
    }

    "reject paths with > 1 segments with NotAResource" >>* {
      csv(config()) { sink =>
        val p = ResourcePath.root() / ResourceName("foo") / ResourceName("bar")
        val r = sink.consume(p, NonEmptyList.one(Column("a", ColumnType.Boolean)), Stream.empty).compile.drain

        MRE.attempt(r).map(_ must beLike {
          case -\/(ResourceError.NotAResource(p2)) => p2 must_=== p
        })
      }
    }

    "simple table" >>* {
      csv(config()) { sink =>
        val recs = List(("x" ->> "s") :: ("y" ->> 8) :: HNil)

        drainAndSelect(
          TestConnectionUrl,
          "simple",
          sink,
          Stream.emits(recs)
        ).map(_ must_=== recs)
      }
    }

    "table with supported types" >>* {
      csv(config()) { sink =>
        val offset = ZoneOffset.ofHoursMinutes(4, 0)
        val recs = List(
          ("boolean" ->> true) ::
          ("number" ->> 42) ::
          ("string" ->> "foo bar") ::
          ("localtime" ->> LocalTime.of(13,59,58)) ::
          ("offsettime" ->> LocalTime.of(1,2,3).atOffset(offset)) ::
          ("localdate" ->> LocalDate.of(2019, 12, 31)) ::
          ("localdatetime" ->> LocalDateTime.of(2019, 12, 31, 23, 59, 59)) ::
          ("offsetdatetime" ->> LocalDateTime.of(2019, 12, 31, 23, 59, 59).atOffset(offset)) ::
          HNil)

        drainAndSelect(
          TestConnectionUrl,
          "supportedtypes",
          sink,
          Stream.emits(recs)
        ).map(_ must_=== recs)
      }
    }

    "quote table names to prevent injection" >>* {
      csv(config()) { sink =>
        val recs = List(("x" ->> 2.3) :: ("y" ->> 8.1) :: HNil)

        drainAndSelect(
          TestConnectionUrl,
          "foobar; drop table really_important; create table haha",
          sink,
          Stream.emits(recs)
        ).map(_ must_=== recs)
      }
    }

    "support table names containing double quote" >>* {
      csv(config()) { sink =>
        val recs = List(("x" ->> 934.23) :: ("y" ->> 1234424.1239847) :: HNil)

        drainAndSelect(
          TestConnectionUrl,
          """the "table" name""",
          sink,
          Stream.emits(recs)
        ).map(_ must_=== recs)
      }
    }

    "quote column names to prevent injection" >>* {
      mustRoundtrip(("from nowhere; drop table super_mission_critical; select *" ->> 42) :: HNil)
    }

    "support columns names containing a double quote" >>* {
      mustRoundtrip(("""the "column" name""" ->> 76) :: HNil)
    }

    "create an empty table when input is empty" >>* {
      csv(config()) { sink =>
        type R = Record.`"a" -> String, "b" -> Int, "c" -> LocalDate`.T

        for {
          tbl <- freshTableName
          r <- drainAndSelect(TestConnectionUrl, tbl, sink, Stream.empty.covaryAll[IO, R])
        } yield r must beEmpty
      }
    }

    "create a row for each line of input" >>* mustRoundtrip(
      ("foo" ->> 1) :: ("bar" ->> "baz1") :: ("quux" ->> 34.34234) :: HNil,
      ("foo" ->> 2) :: ("bar" ->> "baz2") :: ("quux" ->> 35.34234) :: HNil,
      ("foo" ->> 3) :: ("bar" ->> "baz3") :: ("quux" ->> 36.34234) :: HNil)

    "overwrite any existing table with the same name" >>* {
      csv(config()) { sink =>
        val r1 = ("x" ->> 1) :: ("y" ->> "two") :: ("z" ->> 3.00001) :: HNil
        val r2 = ("a" ->> "b") :: ("c" ->> "d") :: HNil

        for {
          tbl <- freshTableName
          res1 <- drainAndSelect(TestConnectionUrl, tbl, sink, Stream(r1))
          res2 <- drainAndSelect(TestConnectionUrl, tbl, sink, Stream(r2))
        } yield (res1 must_=== List(r1)) and (res2 must_=== List(r2))
      }
    }

    "roundtrip Boolean" >>* mustRoundtrip(("min" ->> true) :: ("max" ->> false) :: HNil)
    "roundtrip Short" >>* mustRoundtrip(("min" ->> Short.MinValue) :: ("max" ->> Short.MaxValue) :: HNil)
    "roundtrip Int" >>* mustRoundtrip(("min" ->> Int.MinValue) :: ("max" ->> Int.MaxValue) :: HNil)
    "roundtrip Long" >>* mustRoundtrip(("min" ->> Long.MinValue) :: ("max" ->> Long.MaxValue) :: HNil)
    "roundtrip Float" >>* mustRoundtrip(("min" ->> Float.MinValue) :: ("max" ->> Float.MaxValue) :: HNil)
    "roundtrip Double" >>* mustRoundtrip(("min" ->> Double.MinValue) :: ("max" ->> Double.MaxValue) :: HNil)
    "roundtrip BigDecimal" >>* mustRoundtrip(("value" ->> BigDecimal(Long.MaxValue).pow(5)) :: HNil)

    "roundtrip String" >>* {
      IO(scala.util.Random.nextString(32)) flatMap { s =>
        mustRoundtrip(("value" ->> s) :: HNil)
      }
    }

    "roundtrip empty string as NULL" >>* {
      freshTableName flatMap { table =>
        val cfg = config(url = TestConnectionUrl)
        // testing with 2 values here because 1 empty value would yield an empty csv row
        // which is not loaded into the table
        val rs = Stream(("x" ->> "") :: ("y" ->> "") :: HNil)

        csv(cfg)(drainAndSelectAs[IO, (Option[String], Option[String])](TestConnectionUrl, table, _, rs))
          .map(_ must_=== List((None, None)))
      }
    }

    "roundtrip LocalTime" >>* mustRoundtrip(
      ("min" ->> B.MinLocalTime) ::
      ("max" ->> B.MaxLocalTime) ::
      HNil)

    "roundtrip OffsetTime" >>* mustRoundtrip(
      ("min" ->> B.MinOffsetTime) ::
      ("max" ->> B.MaxOffsetTime) ::
      HNil)

    "roundtrip LocalDate" >>* mustRoundtrip(
      ("min" ->> B.MinLocalDate) ::
      ("max" ->> B.MaxLocalDate) ::
      HNil)

    "roundtrip LocalDateTime" >>* mustRoundtrip(
      ("min" ->> B.MinLocalDateTime) ::
      ("max" ->> B.MaxLocalDateTime) ::
      HNil)

    "roundtrip OffsetDateTime" >>* mustRoundtrip(
        ("min" ->> B.MinOffsetDateTime) ::
        ("max" ->> B.MaxOffsetDateTime) ::
        HNil)
  }

  val DM = new AbstractDestinationModule(DestinationType("h2-test", 1L))

  val TestConnectionUrl: String = "h2:~/testing"

  implicit val CS: ContextShift[IO] = IO.contextShift(global)

  implicit val TM: Timer[IO] = IO.timer(global)

  implicit val MRE: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)

  object renderRow extends renderForCsv(RenderConfigCsv)

  val freshTableName: IO[Ident] =
    randomAlphaNum[IO](6).map(suffix => s"h2_test_${suffix}")

  def runDb[F[_]: Async: ContextShift, A](fa: ConnectionIO[A], uri: String = TestConnectionUrl): F[A] =
    fa.transact(Transactor.fromDriverManager[F]("org.h2.Driver", jdbcUri(uri)))

  def config(url: String = TestConnectionUrl): Json =
    ("connectionUri" := url) ->:
    jEmptyObject

  def csv[A](cfg: Json)(f: ResultSink.CreateSink[IO, ColumnType.Scalar] => IO[A]): IO[A] =
    dest(cfg) {
      case Left(err) =>
        IO.raiseError(new RuntimeException(err.shows))

      case Right(dst) =>
        dst.sinks.toList
          .collectFirst { case c @ ResultSink.CreateSink(_: RenderConfig.Csv, _) => c }
          .map(s => f(s.asInstanceOf[ResultSink.CreateSink[IO, ColumnType.Scalar]]))
          .getOrElse(IO.raiseError(new RuntimeException("No CSV sink found")))
    }

  def dest[A](cfg: Json)(f: Either[InitErr, Destination[IO]] => IO[A]): IO[A] =
    DM.destination[IO](cfg).use(f)

  def drainAndSelect[F[_]: Async: ContextShift, R <: HList, K <: HList, V <: HList, T <: HList, S <: HList](
      connectionUri: String,
      table: Ident,
      sink: ResultSink.CreateSink[F, ColumnType.Scalar],
      records: Stream[F, R])(
      implicit
      keys: Keys.Aux[R, K],
      values: Values.Aux[R, V],
      read: Read[V],
      getTypes: Mapper.Aux[asColumnType.type, V, T],
      rrow: Mapper.Aux[renderRow.type, V, S],
      ktl: ToList[K, String],
      vtl: ToList[S, String],
      ttl: ToList[T, ColumnType.Scalar])
      : F[List[V]] =
    drainAndSelectAs[F, V](connectionUri, table, sink, records)

  object drainAndSelectAs {
    def apply[F[_], A]: PartiallyApplied[F, A] =
      new PartiallyApplied[F, A]

    final class PartiallyApplied[F[_], A]() {
      def apply[R <: HList, K <: HList, V <: HList, T <: HList, S <: HList](
          connectionUri: String,
          table: Ident,
          sink: ResultSink.CreateSink[F, ColumnType.Scalar],
          records: Stream[F, R])(
          implicit
          async: Async[F],
          cshift: ContextShift[F],
          read: Read[A],
          keys: Keys.Aux[R, K],
          values: Values.Aux[R, V],
          getTypes: Mapper.Aux[asColumnType.type, V, T],
          rrow: Mapper.Aux[renderRow.type, V, S],
          ktl: ToList[K, String],
          vtl: ToList[S, String],
          ttl: ToList[T, ColumnType.Scalar])
          : F[List[A]] = {

        val dst = ResourcePath.root() / ResourceName(table)

        val go = records.pull.peek1 flatMap {
          case Some((r, rs)) =>
            val colList =
              r.keys.toList
                .map(k => Fragment.const(hygienicIdent(k)))
                .intercalate(fr",")

            val q = fr"SELECT" ++ colList ++ fr"FROM" ++ Fragment.const(hygienicIdent(table))

            val run = for {
              _ <- toCsvSink(dst, sink, renderRow, rs).compile.drain
              rows <- runDb[F, List[A]](q.query[A].to[List], connectionUri)
            } yield rows

            Pull.eval(run).flatMap(Pull.output1)

          case None =>
            Pull.done
        }

        go.stream.compile.last.map(_ getOrElse Nil)
      }
    }
  }

  def loadAndRetrieve[R <: HList, K <: HList, V <: HList, T <: HList, S <: HList](
      record: R,
      records: R*)(
      implicit
      keys: Keys.Aux[R, K],
      values: Values.Aux[R, V],
      read: Read[V],
      getTypes: Mapper.Aux[asColumnType.type, V, T],
      rrow: Mapper.Aux[renderRow.type, V, S],
      ktl: ToList[K, String],
      vtl: ToList[S, String],
      ttl: ToList[T, ColumnType.Scalar])
      : IO[List[V]] =
    randomAlphaNum[IO](6) flatMap { tableSuffix =>
      val table = s"h2_test_${tableSuffix}"
      val cfg = config(url = TestConnectionUrl)
      val rs = record +: records

      csv(cfg)(drainAndSelect(TestConnectionUrl, table, _, Stream.emits(rs)))
    }

  def mustRoundtrip[R <: HList, K <: HList, V <: HList, T <: HList, S <: HList](
      record: R,
      records: R*)(
      implicit
      keys: Keys.Aux[R, K],
      values: Values.Aux[R, V],
      read: Read[V],
      getTypes: Mapper.Aux[asColumnType.type, V, T],
      rrow: Mapper.Aux[renderRow.type, V, S],
      ktl: ToList[K, String],
      vtl: ToList[S, String],
      ttl: ToList[T, ColumnType.Scalar])
      : IO[MatchResult[List[V]]] =
    loadAndRetrieve(record, records: _*)
      .map(_ must containTheSameElementsAs(record +: records))
}

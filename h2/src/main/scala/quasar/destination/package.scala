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

package quasar.destination

import slamdata.Predef._
import quasar.connector.render.RenderConfig

import java.net.URI
import java.nio.{file => jfile}
import scala.util.Random

import cats.effect.Sync
import doobie.Fragment
import doobie.implicits._

package object h2 {

  type Ident = String

  val Redacted: String = "--REDACTED--"

  val RenderConfigCsv = RenderConfig.Csv()

  /** Returns a random alphanumeric string of the specified length. */
  def randomAlphaNum[F[_]: Sync](size: Int): F[String] =
    Sync[F].delay(Random.alphanumeric.take(size).mkString)

  /** Returns a quoted and escaped version of `ident`. */
  def hygienicIdent(ident: Ident): Ident =
    s""""${ident.replace("\"", "\"\"")}""""

  def singleQuote(s: String) = s"'$s'"

  def singleQuoteFragment(f: Fragment) = fr"'" ++ f ++ fr"'"

  /** Returns the JDBC connection string corresponding to the given database URI. */
  def jdbcUri(dbUri: URI): String =
    s"jdbc:${dbUri}"

  def createTempFile[F[_]](prefix: String, suffix: String)(implicit F: Sync[F])
      : F[jfile.Path] =
    F.delay(jfile.Files.createTempFile(prefix, suffix))

  def deleteIfExists[F[_]](path: jfile.Path)(implicit F: Sync[F])
      : F[Boolean] =
    F.delay(jfile.Files.deleteIfExists(path))
}

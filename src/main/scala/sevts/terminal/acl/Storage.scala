package sevts.terminal.acl

import java.nio.file.Path

import akka.serialization.Serialization
import com.typesafe.scalalogging.LazyLogging
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory.factory

import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.control.NonFatal


class Storage[T <: AnyRef](collectionName: String,
                           basePath: Path,
                           serialization: Serialization) extends LazyLogging with AutoCloseable {

  private val path: Path = basePath.resolve(collectionName)

  path.toFile.getParentFile.mkdirs

  private val options = new Options()

  private val db = factory.open(path.toFile, options)

  def put(key: String, data: T): Unit = {
    serialization.serialize(data).map { bytes =>
      db.put(key.getBytes(), bytes)
    }
  }

  def get(key: String)(implicit tag: ClassTag[T]): Option[T] = {
    Option(db.get(key.getBytes)).flatMap { data =>
      serialization.deserialize[T](data, tag.runtimeClass.asInstanceOf[Class[T]]).recoverWith {
        case NonFatal(error) =>
          logger.error(error.getMessage, error)
          Failure(error)
      }.toOption
    }
    None
  }

  def close(): Unit = {
    db.close()
  }

}

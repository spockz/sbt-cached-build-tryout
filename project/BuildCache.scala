import java.io.File
import java.nio.file.{CopyOption, Files, Path, StandardCopyOption}

import sbt.{File, RichFile}
import sbt.Keys.{javacOptions, scalaVersion, scalacOptions}
import sbt.io.Hash
import sbt.util.{Log, Logger}

import scala.concurrent.Future

object BuildCache {

  val jdkIdentifier =
    Seq(
      System.getProperty("java.vm.name"),
      System.getProperty("java.vm.version"),
      System.getProperty("java.vm.vendor")).mkString("-")


  /**
    * Should take
    */
  def sourceFileToReproducableIdentifier( javacArgs: Seq[String],
                                         scalaCompilerVersion: String,
                                         scalaCompilerArguments: Seq[String])(baseDirectory: File,
                                                                         file: File): String = {
    val hashedargs =
      Hash.toHex(Hash(jdkIdentifier ++ Seq(javacArgs, scalaCompilerVersion, scalaCompilerArguments).mkString("-")))

    val sourceHash = sourceFileContentsHash(file)


    val stableIdentifier =
      (new RichFile(file.getParentFile) / s"$hashedargs-$sourceHash-${file.getName}").getAbsolutePath.stripPrefix(baseDirectory + "/")

    stableIdentifier
  }

  def sourceFileContentsHash(file: File): String = {
    Hash.toHex(Hash.apply(file))
  }

  def sourceFileToClassFile(source: String): String = source.stripSuffix(".scala").stripSuffix(".java") + ".class"
  def classFileToSourceFile(compiled: String): String = compiled.stripSuffix(".class") + ".scala" // hardcoding to scala files here

}

trait BuildCacheTrait {
  def retrieve(log: Logger)(globalIdentifier: String, target: File): Future[Unit]

  def store(log: Logger)(source: File, globalIdentifier: String): Future[Unit]
}

object TmpFsBuildCache extends BuildCacheTrait {
  private def globalIdentifierToPath(globalIdentifier: String): Path = {
    new File("/tmp/sbt-build-cache/myproject/", globalIdentifier).toPath
  }


  override def retrieve(log: Logger)(globalIdentifier: String, target: File): Future[Unit] = {
    Future.successful {
      val source = globalIdentifierToPath(globalIdentifier)

      Files.createDirectories(target.toPath.getParent)
      Files.copy(source, target.toPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
      log.info(s"Copied $source to $target")
    }
  }

  override def store(log: Logger)(source: File, globalIdentifier: String): Future[Unit] = {
    Future.successful {
      val destination = globalIdentifierToPath(globalIdentifier)

      Files.createDirectories(destination.getParent)
      Files.copy(source.toPath, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
      log.info(s"Copied $source to $destination")
    }
  }
}
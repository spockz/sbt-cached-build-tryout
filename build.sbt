import java.nio.file.Paths

import BuildCache.jdkIdentifier

name := "test-build-cache"

version := "0.1"

scalaVersion := "2.12.8"

val retrieveCachedBuild = taskKey[Unit]("Retrieve the cached build files")
val storeCachedBuild = taskKey[Unit]("Store build files to cache")

retrieveCachedBuild := {
  val log = streams.value.log
  // Restore class files
  val f: (File, File) => String =
    BuildCache.sourceFileToReproducableIdentifier(javacOptions.value, scalaVersion.value, scalacOptions.value)


  // for each file determine stable identifier to search for class file in cache
  val sourceFilesWithStableIds =
    (Compile / sourceDirectories)
      .value
      .flatMap(sourceDirectory =>
        Path.selectSubpaths(sourceDirectory, FileFilter.globFilter("*.scala"))
          .map {
            case (sourceFile, filename) =>
              (filename, s"${f(sourceDirectory, sourceFile)}")
          }
      )

  log.info(s"Existing source files ${sourceFilesWithStableIds.mkString("\n")}")

  val existingClassFiles =
    Path.selectSubpaths((Compile / crossTarget).value, FileFilter.globFilter("*.class")).map(t => t.copy(_2 = t._2.stripPrefix("classes/")))

  log.info(s"Existing class files ${existingClassFiles.mkString("\n")}")

  val basePath = crossTarget.value.getAbsolutePath
  log.info(s"BasePath: $basePath")

  val missingClassFiles: Seq[(String, String)] =
    sourceFilesWithStableIds.collect {
      case (sourcePath, identifier) if !existingClassFiles.exists(_._2.replaceFirst(".class", ".scala") == sourcePath) =>
        (sourcePath, identifier.stripPrefix(basePath).stripSuffix(".scala").stripSuffix(".java") + ".class")
    }

  log.info(s"Missing class files ${missingClassFiles.mkString("\n")}")

  missingClassFiles.foreach { case (sourcePath, identifier) =>
    TmpFsBuildCache.retrieve(log)(identifier, new File(basePath) / "classes" / BuildCache.sourceFileToClassFile(sourcePath))
  }

}

Compile / compile := {
  retrieveCachedBuild.value

  val f: (File, File) => String =
    BuildCache.sourceFileToReproducableIdentifier(javacOptions.value, scalaVersion.value, scalacOptions.value)

  // compile
  val compileAnalysis = (Compile / compile).value

  val log = streams.value.log

  log.info(s"compilations count ${compileAnalysis.readCompilations().getAllCompilations.length}")
  compileAnalysis.readCompilations().getAllCompilations.last.getOutput.getSingleOutput.asScala.foreach { singleCompiledFile =>
    log.info(s"Compiled single file $singleCompiledFile")
    // need access to the hash of the source here...
    Path.selectSubpaths(singleCompiledFile, FileFilter.globFilter("*.class")).foreach { case (classFileFile, classFilePath) =>
      val matchingSourceFile = BuildCache.classFileToSourceFile(classFilePath)
      val sourceFileGlobalIds: Seq[(File, String)] = (Compile / sourceDirectories)
        .value.flatMap(sd => {
        val x = new RichFile(sd) / matchingSourceFile

        if (x.canRead) {
          Some(classFileFile -> BuildCache.sourceFileToClassFile(f(sd, x)))
        } else {
          None
        }
      })

      sourceFileGlobalIds.foreach { case (classFile, globalIdentifier) =>
        TmpFsBuildCache.store(log)(classFile, globalIdentifier)
      }
    }
  }

  compileAnalysis
}

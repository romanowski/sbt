/* sbt -- Simple Build Tool
 * Copyright 2010 Mark Harrah
 */
package sbt
package compiler

import inc._

import scala.annotation.tailrec
import java.io.File
import classpath.ClasspathUtilities
import classfile.Analyze
import inc.Locate.DefinesClass
import inc.IncOptions
import CompileSetup._
import sbinary.DefaultProtocol.{ immutableMapFormat, immutableSetFormat, StringFormat }

import xsbti.{ Reporter, AnalysisCallback }
import xsbti.api.Source
import xsbti.compile.{ CompileOrder, DependencyChanges, GlobalsCache, Output, SingleOutput, MultipleOutput, CompileProgress }
import CompileOrder.{ JavaThenScala, Mixed, ScalaThenJava }

@deprecated("Use MixedAnalyzingCompiler or IC instead.", "0.13.8")
class AggressiveCompile(cacheFile: File) {
  @deprecated("Use IC.compile instead.", "0.13.8")
  def apply(compiler: AnalyzingCompiler,
    javac: xsbti.compile.JavaCompiler,
    sources: Seq[File], classpath: Seq[File],
    output: Output,
    cache: GlobalsCache,
    progress: Option[CompileProgress] = None,
    options: Seq[String] = Nil,
    javacOptions: Seq[String] = Nil,
    analysisMap: File => Option[Analysis] = { _ => None },
    definesClass: DefinesClass = Locate.definesClass _,
    reporter: Reporter,
    compileOrder: CompileOrder = Mixed,
    skip: Boolean = false,
    incrementalCompilerOptions: IncOptions)(implicit log: Logger): Analysis =
    {
      val setup = new CompileSetup(output, new CompileOptions(options, javacOptions),
        compiler.scalaInstance.actualVersion, compileOrder, incrementalCompilerOptions.nameHashing)
      compile1(sources, classpath, setup, progress, store, analysisMap, definesClass,
        compiler, javac, reporter, skip, cache, incrementalCompilerOptions)
    }

  def withBootclasspath(args: CompilerArguments, classpath: Seq[File]): Seq[File] =
    args.bootClasspathFor(classpath) ++ args.extClasspath ++ args.finishClasspath(classpath)

  def compile1(sources: Seq[File],
    classpath: Seq[File],
    setup: CompileSetup, progress: Option[CompileProgress],
    store: AnalysisStore,
    analysis: File => Option[Analysis],
    definesClass: DefinesClass,
    compiler: AnalyzingCompiler,
    javac: xsbti.compile.JavaCompiler,
    reporter: Reporter, skip: Boolean,
    cache: GlobalsCache,
    incrementalCompilerOptions: IncOptions)(implicit log: Logger): Analysis =
    {
      val (previousAnalysis, previousSetup) = extract(store.get(), incrementalCompilerOptions)
      if (skip)
        previousAnalysis
      else {
        val config = new CompileConfiguration(sources, classpath, previousAnalysis, previousSetup, setup,
          progress, analysis, definesClass, reporter, compiler, javac, cache, incrementalCompilerOptions)
        val (modified, result) = compile2(config)
        if (modified)
          store.set(result, setup)
        result
      }
    }
  def compile2(config: CompileConfiguration)(implicit log: Logger, equiv: Equiv[CompileSetup]): (Boolean, Analysis) =
    {
      import config._
      import currentSetup._
      val absClasspath = classpath.map(_.getAbsoluteFile)
      val apiOption = (api: Either[Boolean, Source]) => api.right.toOption
      val cArgs = new CompilerArguments(compiler.scalaInstance, compiler.cp)
      val searchClasspath = explicitBootClasspath(options.options) ++ withBootclasspath(cArgs, absClasspath)
      val entry = Locate.entry(searchClasspath, definesClass)

      val compile0 = (include: Set[File], changes: DependencyChanges, callback: AnalysisCallback) => {
        val outputDirs = outputDirectories(output)
        outputDirs foreach (IO.createDirectory)
        val incSrc = sources.filter(include)
        val (javaSrcs, scalaSrcs) = incSrc partition javaOnly
        logInputs(log, javaSrcs.size, scalaSrcs.size, outputDirs)
        def compileScala() =
          if (scalaSrcs.nonEmpty) {
            val sources = if (order == Mixed) incSrc else scalaSrcs
            val arguments = cArgs(Nil, absClasspath, None, options.options)
            timed("Scala compilation", log) {
              compiler.compile(sources, changes, arguments, output, callback, reporter, cache, log, progress)
            }
          }
        def compileJava() =
          if (javaSrcs.nonEmpty) {
            import Path._
            @tailrec def ancestor(f1: File, f2: File): Boolean =
              if (f2 eq null) false else if (f1 == f2) true else ancestor(f1, f2.getParentFile)

            val chunks: Map[Option[File], Seq[File]] = output match {
              case single: SingleOutput => Map(Some(single.outputDirectory) -> javaSrcs)
              case multi: MultipleOutput =>
                javaSrcs groupBy { src =>
                  multi.outputGroups find { out => ancestor(out.sourceDirectory, src) } map (_.outputDirectory)
                }
            }
            chunks.get(None) foreach { srcs =>
              log.error("No output directory mapped for: " + srcs.map(_.getAbsolutePath).mkString(","))
            }
            val memo = for ((Some(outputDirectory), srcs) <- chunks) yield {
              val classesFinder = PathFinder(outputDirectory) ** "*.class"
              (classesFinder, classesFinder.get, srcs)
            }

            val loader = ClasspathUtilities.toLoader(searchClasspath)
            timed("Java compilation", log) {
              try javac.compileWithReporter(javaSrcs.toArray, absClasspath.toArray, output, options.javacOptions.toArray, reporter, log)
              catch {
                // Handle older APIs
                case _: NoSuchMethodError =>
                  javac.compile(javaSrcs.toArray, absClasspath.toArray, output, options.javacOptions.toArray, log)
              }
            }

            def readAPI(source: File, classes: Seq[Class[_]]): Set[String] = {
              val (api, inherits) = ClassToAPI.process(classes)
              callback.api(source, api)
              inherits.map(_.getName)
            }

            timed("Java analysis", log) {
              for ((classesFinder, oldClasses, srcs) <- memo) {
                val newClasses = Set(classesFinder.get: _*) -- oldClasses
                Analyze(newClasses.toSeq, srcs, log)(callback, loader, readAPI)
              }
            }
          }
        if (order == JavaThenScala) { compileJava(); compileScala() } else { compileScala(); compileJava() }
      }

      val sourcesSet = sources.toSet
      val analysis = previousSetup match {
        case Some(previous) if previous.nameHashing != currentSetup.nameHashing =>
          // if the value of `nameHashing` flag has changed we have to throw away
          // previous Analysis completely and start with empty Analysis object
          // that supports the particular value of the `nameHashing` flag.
          // Otherwise we'll be getting UnsupportedOperationExceptions
          log.warn("Ignoring previous analysis due to incompatible nameHashing setting.")
          Analysis.empty(currentSetup.nameHashing)
        case Some(previous) if equiv.equiv(previous, currentSetup) => previousAnalysis
        case _ =>
          log.warn("Pruning sources from previous analysis, due to incompatible CompileSetup.")
          Incremental.prune(sourcesSet, previousAnalysis)
      }
      IncrementalCompile(sourcesSet, entry, compile0, analysis, getAnalysis, output, log, incOptions)
    }
  private[this] def outputDirectories(output: Output): Seq[File] = output match {
    case single: SingleOutput => List(single.outputDirectory)
    case mult: MultipleOutput => mult.outputGroups map (_.outputDirectory)
  }
  private[this] def timed[T](label: String, log: Logger)(t: => T): T =
    {
      val start = System.nanoTime
      val result = t
      val elapsed = System.nanoTime - start
      log.debug(label + " took " + (elapsed / 1e9) + " s")
      result
    }
  private[this] def logInputs(log: Logger, javaCount: Int, scalaCount: Int, outputDirs: Seq[File]): Unit = {
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaCount)
    val javaMsg = Analysis.counted("Java source", "", "s", javaCount)
    val combined = scalaMsg ++ javaMsg
    if (combined.nonEmpty)
      log.info(combined.mkString("Compiling ", " and ", " to " + outputDirs.map(_.getAbsolutePath).mkString(",") + "..."))
  }
  private def extract(previous: Option[(Analysis, CompileSetup)], incOptions: IncOptions): (Analysis, Option[CompileSetup]) =
    previous match {
      case Some((an, setup)) => (an, Some(setup))
      case None              => (Analysis.empty(nameHashing = incOptions.nameHashing), None)
    }
  def javaOnly(f: File) = f.getName.endsWith(".java")

  private[this] def explicitBootClasspath(options: Seq[String]): Seq[File] =
    options.dropWhile(_ != CompilerArguments.BootClasspathOption).slice(1, 2).headOption.toList.flatMap(IO.parseClasspath)

  val store = MixedAnalyzingCompiler.staticCachedStore(cacheFile)

}
@deprecated("Use MixedAnalyzingCompiler instead.", "0.13.8")
object AggressiveCompile {
  @deprecated("Use MixedAnalyzingCompiler.staticCachedStore instead.", "0.13.8")
  def staticCachedStore(cacheFile: File) = MixedAnalyzingCompiler.staticCachedStore(cacheFile)

  @deprecated("Deprecated in favor of new sbt.compiler.javac package.", "0.13.8")
  def directOrFork(instance: ScalaInstance, cpOptions: ClasspathOptions, javaHome: Option[File]): JavaTool =
    if (javaHome.isDefined)
      JavaCompiler.fork(cpOptions, instance)(forkJavac(javaHome))
    else
      JavaCompiler.directOrFork(cpOptions, instance)(forkJavac(None))

  @deprecated("Deprecated in favor of new sbt.compiler.javac package.", "0.13.8")
  def forkJavac(javaHome: Option[File]): JavaCompiler.Fork =
    {
      import Path._
      def exec(jc: JavacContract) = javaHome match { case None => jc.name; case Some(jh) => (jh / "bin" / jc.name).absolutePath }
      (contract: JavacContract, args: Seq[String], log: Logger) => {
        log.debug("Forking " + contract.name + ": " + exec(contract) + " " + args.mkString(" "))
        val javacLogger = new JavacLogger(log)
        var exitCode = -1
        try {
          exitCode = Process(exec(contract), args) ! javacLogger
        } finally {
          javacLogger.flush(exitCode)
        }
        exitCode
      }
    }
}

@deprecated("Deprecated in favor of new sbt.compiler.javac package.", "0.13.8")
private[sbt] class JavacLogger(log: Logger) extends ProcessLogger {
  import scala.collection.mutable.ListBuffer
  import Level.{ Info, Warn, Error, Value => LogLevel }

  private val msgs: ListBuffer[(LogLevel, String)] = new ListBuffer()

  def info(s: => String): Unit =
    synchronized { msgs += ((Info, s)) }

  def error(s: => String): Unit =
    synchronized { msgs += ((Error, s)) }

  def buffer[T](f: => T): T = f

  private def print(desiredLevel: LogLevel)(t: (LogLevel, String)) = t match {
    case (Info, msg)  => log.info(msg)
    case (Error, msg) => log.log(desiredLevel, msg)
  }

  def flush(exitCode: Int): Unit = {
    val level = if (exitCode == 0) Warn else Error
    msgs foreach print(level)
    msgs.clear()
  }
}

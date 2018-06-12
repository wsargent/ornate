package com.novocode.ornate.sbtplugin

import java.net.URLClassLoader

import sbt._
import Keys._

object OrnatePlugin extends AutoPlugin {
  override def requires = plugins.IvyPlugin

  object autoImport {
    //#--doc-plugin
    val ornateBaseDir     = settingKey[Option[File]]("Base directory for Ornate")
    val ornateSourceDir   = settingKey[Option[File]]("Source directory for Ornate")
    val ornateResourceDir = settingKey[Option[File]]("Resource directory for Ornate")
    val ornateTargetDir   = settingKey[Option[File]]("Target directory for the Ornate-generated site")
    val ornateConfig      = settingKey[File]("Config file for Ornate")
    val ornateSettings    = settingKey[Map[String, String]]("Extra settings for Ornate")
    val ornate            = taskKey[File]("Run Ornate to generate the site, returning the target directory")
    lazy val Ornate       = config("ornate").hide // provides the classpath for Ornate
    //#--doc-plugin
  }
  import autoImport._

  override lazy val projectSettings = inConfig(Ornate)(Defaults.configSettings) ++ Seq(
    ornateBaseDir := Some(sourceDirectory.value),
    ornateSourceDir := ornateBaseDir.value.map(_ / "doc"),
    ornateResourceDir := ornateSourceDir.value,
    ornateTargetDir := Some(target.value / "doc"),
    ornateConfig := ornateBaseDir.value.getOrElse(sourceDirectory.value) / "ornate.conf",
    ornateSettings := Map.empty,
    ornate := ornateTask.value,
    scalaVersion in Ornate := BuildInfo.scalaVersion,
    ivyConfigurations += Ornate,
    libraryDependencies ++= Seq(
      BuildInfo.organization %% BuildInfo.name % BuildInfo.version % Ornate,
      "org.scala-lang" % "scala-library" % BuildInfo.scalaVersion % Ornate
    )
  )
  lazy val ornateTask = Def.task {
    val log = streams.value.log
    log.debug("ornateSourceDir   = "+ornateSourceDir.value)
    log.debug("ornateResourceDir = "+ornateResourceDir.value)
    log.debug("ornateTargetDir   = "+ornateTargetDir.value)
    log.debug("ornateConfig      = "+ornateConfig.value)
    log.debug("classpath         = "+(dependencyClasspath in Ornate).value.files)

    val args = Seq(
      ornateBaseDir.value.map(f => "--base-dir=" + f.getAbsolutePath),
      ornateSourceDir.value.map(f => "-Dglobal.sourceDir=" + f.getAbsolutePath),
      ornateResourceDir.value.map(f => "-Dglobal.resourceDir=" + f.getAbsolutePath),
      ornateTargetDir.value.map(f => "-Dglobal.targetDir=" + f.getAbsolutePath)
    ).flatten ++ ornateSettings.value.toSeq.map { case (k, v) => s"-D$k=$v" } :+ ornateConfig.value.getAbsolutePath
    log.debug("args              = "+args)

    val parent = ClassLoader.getSystemClassLoader.getParent
    val loader = new URLClassLoader((dependencyClasspath in Ornate).value.files.map(_.toURI.toURL).toArray, parent)
    val old = Thread.currentThread.getContextClassLoader
    try {
      Thread.currentThread.setContextClassLoader(loader)
      val cl = loader.loadClass("com.novocode.ornate.Main")
      val runToStatus = cl.getMethod("runToStatus", classOf[Array[String]])
      val res: Array[AnyRef] = runToStatus.invoke(null, args.toArray).asInstanceOf[Array[AnyRef]]
      val status = res(0).asInstanceOf[Int]
      val outDir = res(1).asInstanceOf[String]
      if(status != 0) throw new RuntimeException(s"Ornate run failed with status code ${res.toList}")
      if(outDir ne null) file(outDir)
      else ornateTargetDir.value.getOrElse(null)
    } finally {
      Thread.currentThread.setContextClassLoader(old)
      loader.close()
    }
  }
}

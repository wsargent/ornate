package com.novocode.ornate.theme

import java.io.FileNotFoundException
import java.net.{URL, URI}
import scala.collection.mutable

import com.novocode.ornate._
import com.novocode.ornate.commonmark.{ExpandTocProcessor, AttributeFencedCodeBlocksProcessor, SpecialImageProcessor}
import com.novocode.ornate.config.ConfigExtensionMethods.configExtensionMethods
import com.novocode.ornate.config.Global
import com.novocode.ornate.js.NashornSupport

import better.files._

/** Base class for themes. */
abstract class Theme(val global: Global) extends Logging {

  /** The full pipeline for building the site. */
  def build: Unit = {
    val pages = buildAllPages

    logger.info("Processing site")
    val toc = TocParser.parse(global.userConfig, pages)
    val site = new Site(pages, toc)

    val sip = new SpecialImageProcessor(global.userConfig)
    pages.foreach { p =>
      val pagepp = p.extensions.ornate.flatMap(_.pageProcessors(site))
      p.processors = (AttributeFencedCodeBlocksProcessor +: sip +: pagepp)
      p.applyProcessors()
    }

    val etp = new ExpandTocProcessor(toc)
    pages.foreach(etp)

    logger.info("Rendering site to "+global.userConfig.targetDir)
    render(site)
  }

  /** Render the site. May create additional synthetic pages and copy resources on demand. */
  def render(site: Site): Unit

  /** Get all source pages and synthetic pages */
  def buildAllPages: Vector[Page] = PageParser.parseSources(global) ++ synthesizePages

  /** Synthesize configured synthetic pages pre-TOC. Not all requested pages have to be
    * created but only the ones that are returned will be available for resolving the TOC. */
  def synthesizePages: Vector[Page] = Vector.empty

  /** Get synthetic page names and the mapped URIs for pages that should be created by the theme.
    * Any pages that have to be created before resolving the TOC should be part of this. */
  protected def syntheticPageURIs: Vector[(String, URI)] =
    global.userConfig.theme.config.getConfigMapOr("global.pages").iterator.filter(_._2.unwrapped ne null).map(e =>
      (e._1, Util.siteRootURI.resolve(e._2.unwrapped.asInstanceOf[String]))
    ).toVector

  /** Resolve a resource URI to a URL. Resource URIs can use use the following protocols:
    * file, site (static site resources), webjar (absolute WebJar resource), theme (relative
    * to theme class), classpath (relative to classpath root) */
  def resolveResource(uri: URI): URL = uri.getScheme match {
    case "file" => uri.toURL
    case "site" => global.userConfig.resourceDir.path.toUri.resolve(uri.getPath.replaceFirst("^/*", "")).toURL
    case "webjar" =>
      val parts = uri.getPath.split('/').filter(_.nonEmpty)
      val path = NashornSupport.locator.getFullPathExact(parts.head, parts.tail.mkString("/"))
      if(path eq null) throw new FileNotFoundException("WebJar resource not found: "+uri)
      getClass.getClassLoader.getResource(path)
    case "theme" =>
      val url = getClass.getResource(uri.getPath.replaceFirst("^/*", ""))
      if(url eq null) throw new FileNotFoundException("Theme resource not found: "+uri)
      url
    case "classpath" =>
      val url = getClass.getClassLoader.getResource(uri.getPath)
      if(url eq null) throw new FileNotFoundException("Classpath resource not found: "+uri)
      url
    case _ => throw new IllegalArgumentException("Unsupported scheme in resource URI "+uri)
  }

  /** Get a default relative path for a resource URI */
  def suggestRelativePath(uri: URI): String = uri.getScheme match {
    case "file" => uri.getPath.split('/').last
    case "site" => global.userConfig.resourceDir.path.toUri.resolve(uri.getPath).getPath.replaceFirst("^/*", "")
    case _ => uri.getPath.replaceFirst("^/*", "")
  }
}

class PageResources(val page: Page, theme: Theme, baseURI: URI) {
  private[this] val buf = new mutable.ArrayBuffer[ResourceSpec]
  private[this] val map = new mutable.HashMap[URL, ResourceSpec]

  final def get(path: String, targetFile: String = null, createLink: Boolean = false): URI =
    Util.relativeSiteURI(page.uri, getURI(Util.themeRootURI.resolve(path), targetFile, createLink))

  final def getLinks(suffix: String): Iterable[URI] =
    mappings.collect { case r: ResourceSpec if r.createLink && r.targetURI.toString.endsWith("."+suffix) => Util.relativeSiteURI(page.uri, r.targetURI) }

  def mappings: Iterable[ResourceSpec] = buf

  def getURI(sourceURI: URI, targetFile: String, createLink: Boolean): URI = {
    try {
      val url = theme.resolveResource(sourceURI)
      map.getOrElseUpdate(url, {
        val targetURI =
          if(sourceURI.getScheme == "site") sourceURI // link to site resources at their original location
          else {
            val tname =
              if(targetFile eq null) {
                theme.suggestRelativePath(sourceURI).replaceAll("^/*", "")
              } else if(targetFile.endsWith("/")) {
                val s = theme.suggestRelativePath(sourceURI).replaceAll("^/*", "")
                if(s.startsWith(targetFile)) s else targetFile + s
              } else targetFile.replaceAll("^/*", "")
            baseURI.resolve(tname)
          }
        val spec = ResourceSpec(sourceURI, url, targetURI, createLink, this)
        buf += spec
        spec
      }).targetURI
    } catch { case ex: Exception =>
      theme.logger.error(s"Error resolving theme resource URI $sourceURI -- Skipping resource and using original link")
      sourceURI
    }
  }
}

/** Resource to include in the generated site.
  *
  * @param sourceURI The source URI
  * @param sourceURL the resolved source URL to locate the file
  * @param targetURI the target `site:` URI
  * @param createLink whether to create a link to the resource (e.g. "script" or "style" tag)
  * @param resources the `Resources` object which created this ResourceSpec
  */
case class ResourceSpec(sourceURI: URI, sourceURL: URL, targetURI: URI, createLink: Boolean, resources: PageResources) {
  def minifiableType: Option[String] = {
    val p = sourceURI.getPath
    val sep = p.lastIndexOf('.')
    if(sep <= 0 || sep >= p.length-1) None
    else {
      if(p.substring(0, sep).endsWith(".min")) None
      else Some(p.substring(sep+1))
    }
  }
}

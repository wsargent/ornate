package com.novocode.mdoc

import better.files._
import com.novocode.mdoc.commonmark.{AttributeFencedCodeBlocksProcessor, ExpandTocProcessor, SpecialImageProcessor}
import com.novocode.mdoc.config.Global

object Main extends App {
  val startDir = file"doc"
  val global = new Global(startDir, startDir / "mdoc.conf")

  //#main
  val userPages = PageParser.parseSources(global)
  val themePageURIs = global.theme.syntheticPageURIs
  val themePages = global.theme.synthesizePages(themePageURIs)
  val pages = userPages ++ themePages

  val toc = TocParser.parse(global.userConfig, pages)
  val site = new Site(pages, toc)

  val sip = new SpecialImageProcessor(global.userConfig)
  pages.foreach { p =>
    val pagepp = p.extensions.mdoc.flatMap(_.pageProcessors(global, site))
    p.processors = (AttributeFencedCodeBlocksProcessor +: sip +: pagepp)
    p.applyProcessors()
  }

  val etp = new ExpandTocProcessor(toc)
  pages.foreach(etp)

  global.theme.render(site)
  //#main
}
